/**
 * RecordingFragment.java
 *
 * Full recording interface with live transcription display,
 * recording controls, timer, and status indicators.
 *
 * @author STELLiQ Engineering
 * @version 1.0.0
 * @since ARIA Commercial Build — 2026-03-04
 */
package com.stelliq.aria.ui.recording;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.stelliq.aria.R;
import com.stelliq.aria.model.SessionState;
import com.stelliq.aria.service.ARIASessionService;
import com.stelliq.aria.ui.MainActivity;
import com.stelliq.aria.util.Constants;

public class RecordingFragment extends Fragment {

    private static final String TAG = Constants.LOG_TAG_UI;

    // UI Components
    private ImageButton mBtnBack;
    private TextView mTextStatus;
    private View mRecordingIndicator;
    private TextView mTextTimer;
    private TextView mTextParticipantCount;
    private MaterialCardView mCardTranscript;
    private NestedScrollView mScrollTranscript;
    private TextView mTextTranscript;
    private LinearProgressIndicator mProgressProcessing;
    private ImageButton mBtnStartRecording;
    private ImageButton mBtnPauseRecording;
    private ImageButton mBtnStopRecording;

    // State
    private boolean mIsRecording = false;
    private long mRecordingStartTime = 0;
    private Handler mTimerHandler;
    private Runnable mTimerRunnable;
    private StringBuilder mTranscriptBuilder = new StringBuilder();
    // WHY: Prevents duplicate navigation when observer fires multiple times for COMPLETE
    private boolean mHasNavigatedToDetail = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_recording, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Find views
        mBtnBack = view.findViewById(R.id.btn_back);
        mTextStatus = view.findViewById(R.id.text_status);
        mRecordingIndicator = view.findViewById(R.id.recording_indicator);
        mTextTimer = view.findViewById(R.id.text_timer);
        mTextParticipantCount = view.findViewById(R.id.text_participant_count);
        mCardTranscript = view.findViewById(R.id.card_transcript);
        mScrollTranscript = view.findViewById(R.id.scroll_transcript);
        mTextTranscript = view.findViewById(R.id.text_transcript);
        mProgressProcessing = view.findViewById(R.id.progress_processing);
        mBtnStartRecording = view.findViewById(R.id.btn_start_recording);
        mBtnPauseRecording = view.findViewById(R.id.btn_pause_recording);
        mBtnStopRecording = view.findViewById(R.id.btn_stop_recording);

        // Setup
        mTimerHandler = new Handler(Looper.getMainLooper());
        mHasNavigatedToDetail = false;
        setupClickListeners();

        // Set initial participant count (fixes raw %d showing)
        if (mTextParticipantCount != null) {
            mTextParticipantCount.setText("1 Participant");
        }

        // WHY: Reset service from stale COMPLETE/ERROR state when entering recording screen.
        // This prevents "pulls the old recorded one" behavior when a session was deleted
        // or completed and the user navigates back to start a new recording.
        resetServiceIfStale();

        updateUIState();

        // Observe live transcription from service
        observeServiceLiveData();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (mTimerHandler != null && mTimerRunnable != null) {
            mTimerHandler.removeCallbacks(mTimerRunnable);
        }
    }

    private void setupClickListeners() {
        if (mBtnBack != null) {
            mBtnBack.setOnClickListener(v -> handleBackPress());
        }

        if (mBtnStartRecording != null) {
            mBtnStartRecording.setOnClickListener(v -> toggleRecording());
        }

        if (mBtnPauseRecording != null) {
            mBtnPauseRecording.setOnClickListener(v -> pauseRecording());
        }

        if (mBtnStopRecording != null) {
            mBtnStopRecording.setOnClickListener(v -> stopRecording());
        }
    }

    private void handleBackPress() {
        if (mIsRecording) {
            // Show confirmation dialog
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.dialog_discard_title)
                    .setMessage(R.string.dialog_discard_message)
                    .setPositiveButton(R.string.discard, (dialog, which) -> {
                        cancelRecording();
                        navigateBack();
                    })
                    .setNegativeButton(R.string.keep_recording, null)
                    .show();
        } else if (isServiceInSummarizingState()) {
            // WHY: During SUMMARIZING, the LLM is still running on the worker thread.
            // Navigating away without cancelling leaves a zombie inference that blocks
            // the next recording (isBusy=true). Show confirmation, then cancel via
            // LlamaEngine.requestCancellation() so the -6 return code cleans up.
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.dialog_cancel_processing_title)
                    .setMessage(R.string.dialog_cancel_processing_message)
                    .setPositiveButton(R.string.discard, (dialog, which) -> {
                        cancelRecording();
                        navigateBack();
                    })
                    .setNegativeButton(R.string.keep_processing, null)
                    .show();
        } else {
            navigateBack();
        }
    }

    /**
     * Checks if the bound service is currently in SUMMARIZING state.
     */
    private boolean isServiceInSummarizingState() {
        MainActivity activity = (MainActivity) getActivity();
        if (activity != null && activity.isServiceBound()) {
            ARIASessionService service = activity.getService();
            if (service != null && service.getSessionController() != null) {
                return service.getSessionController().getCurrentState() == SessionState.SUMMARIZING;
            }
        }
        return false;
    }

    private void navigateBack() {
        try {
            NavController navController = Navigation.findNavController(requireView());
            navController.popBackStack();
        } catch (Exception e) {
            Log.e(TAG, "[RecordingFragment] Navigation failed: " + e.getMessage());
        }
    }

    private void navigateToSessionDetail() {
        try {
            // Get session UUID from the service
            MainActivity activity = (MainActivity) getActivity();
            String sessionId = null;
            if (activity != null && activity.isServiceBound()) {
                ARIASessionService service = activity.getService();
                if (service != null) {
                    sessionId = service.getCurrentSessionUuid();
                }
            }

            if (sessionId != null) {
                Bundle args = new Bundle();
                args.putString("sessionId", sessionId);
                NavController navController = Navigation.findNavController(requireView());
                navController.navigate(R.id.action_recording_to_session, args);
                Log.i(TAG, "[RecordingFragment] Navigating to session detail: " + sessionId);
            } else {
                Log.w(TAG, "[RecordingFragment] No session UUID available, navigating back");
                navigateBack();
            }
        } catch (Exception e) {
            Log.e(TAG, "[RecordingFragment] Navigation to detail failed: " + e.getMessage());
            navigateBack();
        }
    }

    private void resetServiceIfStale() {
        MainActivity activity = (MainActivity) getActivity();
        if (activity != null && activity.isServiceBound()) {
            ARIASessionService service = activity.getService();
            if (service != null) {
                service.reset();
            }
        }
    }

    private void toggleRecording() {
        if (!checkAudioPermission()) {
            return;
        }

        if (mIsRecording) {
            // Already recording, this button acts as record
            return;
        }

        startRecording();
    }

    private boolean checkAudioPermission() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "[RecordingFragment] Audio permission not granted");
            return false;
        }
        return true;
    }

    private void startRecording() {
        Log.i(TAG, "[RecordingFragment] Starting recording");

        mIsRecording = true;
        mRecordingStartTime = System.currentTimeMillis();
        mTranscriptBuilder = new StringBuilder();

        // WHY: On Android 14+, startForeground() requires the service to have been
        // STARTED (via startForegroundService), not just bound. Without this call,
        // startForeground() throws ForegroundServiceStartNotAllowedException and crashes.
        android.content.Intent serviceIntent = new android.content.Intent(requireContext(), ARIASessionService.class);
        serviceIntent.setAction(Constants.ACTION_START_RECORDING);
        ContextCompat.startForegroundService(requireContext(), serviceIntent);

        // Start timer
        startTimer();

        // Update UI
        updateUIState();
    }

    private void pauseRecording() {
        Log.i(TAG, "[RecordingFragment] Pausing recording");
        // Pause logic would go here
    }

    private void stopRecording() {
        Log.i(TAG, "[RecordingFragment] Stopping recording");

        mIsRecording = false;
        stopTimer();

        // Stop service recording — summarization runs in background
        MainActivity activity = (MainActivity) getActivity();
        if (activity != null && activity.isServiceBound()) {
            ARIASessionService service = activity.getService();
            if (service != null) {
                service.stopRecording();
            }
        }

        // WHY: Navigate home immediately instead of showing a processing page.
        // Summarization runs in the background service. A notification is sent
        // when the summary is ready, so the user can do other things.
        navigateBack();
    }

    private void cancelRecording() {
        Log.i(TAG, "[RecordingFragment] Cancelling recording");

        mIsRecording = false;
        stopTimer();

        MainActivity activity = (MainActivity) getActivity();
        if (activity != null && activity.isServiceBound()) {
            ARIASessionService service = activity.getService();
            if (service != null) {
                service.cancelRecording();
            }
        }
    }

    private void startTimer() {
        mTimerRunnable = new Runnable() {
            @Override
            public void run() {
                if (mIsRecording && mTextTimer != null) {
                    long elapsed = System.currentTimeMillis() - mRecordingStartTime;
                    updateTimerDisplay(elapsed);
                    mTimerHandler.postDelayed(this, 1000);
                }
            }
        };
        mTimerHandler.post(mTimerRunnable);
    }

    private void stopTimer() {
        if (mTimerHandler != null && mTimerRunnable != null) {
            mTimerHandler.removeCallbacks(mTimerRunnable);
        }
    }

    private void updateTimerDisplay(long elapsedMs) {
        long seconds = (elapsedMs / 1000) % 60;
        long minutes = (elapsedMs / (1000 * 60)) % 60;
        long hours = elapsedMs / (1000 * 60 * 60);

        String timeStr;
        if (hours > 0) {
            timeStr = String.format("%d:%02d:%02d", hours, minutes, seconds);
        } else {
            timeStr = String.format("%d:%02d", minutes, seconds);
        }

        if (mTextTimer != null) {
            mTextTimer.setText(timeStr);
        }
    }

    private void updateUIState() {
        if (mIsRecording) {
            // Recording state
            if (mTextStatus != null) {
                mTextStatus.setText(R.string.status_recording);
                mTextStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.recording_red));
            }
            if (mRecordingIndicator != null) {
                mRecordingIndicator.setVisibility(View.VISIBLE);
                // Pulse animation for recording dot
                Animation pulse = AnimationUtils.loadAnimation(requireContext(), R.anim.pulse_recording);
                mRecordingIndicator.startAnimation(pulse);
            }
            if (mBtnPauseRecording != null) {
                mBtnPauseRecording.setVisibility(View.VISIBLE);
            }
            if (mBtnStopRecording != null) {
                mBtnStopRecording.setVisibility(View.VISIBLE);
            }
        } else {
            // Ready state
            if (mTextStatus != null) {
                mTextStatus.setText(R.string.status_ready);
                mTextStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary));
            }
            if (mRecordingIndicator != null) {
                mRecordingIndicator.clearAnimation();
                mRecordingIndicator.setVisibility(View.GONE);
            }
            if (mBtnPauseRecording != null) {
                mBtnPauseRecording.setVisibility(View.GONE);
            }
            if (mBtnStopRecording != null) {
                mBtnStopRecording.setVisibility(View.GONE);
            }
            if (mTextTimer != null) {
                mTextTimer.setText("00:00");
            }
        }
    }

    /**
     * Observes SessionController LiveData from ARIASessionService.
     * Wires up real-time transcription display and state-driven UI updates.
     */
    private void observeServiceLiveData() {
        MainActivity activity = (MainActivity) getActivity();
        if (activity == null || !activity.isServiceBound()) {
            Log.w(TAG, "[RecordingFragment] Service not bound yet — retrying in 500ms");
            // Retry after short delay to allow service binding to complete
            mTimerHandler.postDelayed(this::observeServiceLiveData, 500);
            return;
        }

        ARIASessionService service = activity.getService();
        if (service == null) {
            return;
        }

        // Observe live transcript — updates in real-time as Whisper produces output
        service.getLiveTranscript().observe(getViewLifecycleOwner(), transcript -> {
            if (transcript != null && !transcript.isEmpty()) {
                if (mTextTranscript != null) {
                    mTextTranscript.setText(styleSpeakerName(transcript));
                }
                // Auto-scroll to bottom
                if (mScrollTranscript != null) {
                    mScrollTranscript.post(() -> mScrollTranscript.fullScroll(View.FOCUS_DOWN));
                }
            }
        });

        // Observe participant count for multi-device meetings
        com.stelliq.aria.service.SessionController controller = service.getSessionController();
        if (controller != null) {
            controller.getParticipantCountLiveData().observe(getViewLifecycleOwner(), count -> {
                if (mTextParticipantCount != null && count != null) {
                    String text = count == 1 ? "1 Participant" : count + " Participants";
                    mTextParticipantCount.setText(text);
                }
            });
        }

        // Observe session state for UI updates
        service.getSessionState().observe(getViewLifecycleOwner(), state -> {
            Log.d(TAG, "[RecordingFragment] State changed: " + state);
            if (state == null) return;

            switch (state) {
                case RECORDING:
                    if (mTextStatus != null) mTextStatus.setText(R.string.status_recording);
                    if (mRecordingIndicator != null) mRecordingIndicator.setVisibility(View.VISIBLE);
                    break;
                case SUMMARIZING:
                    if (mTextStatus != null) mTextStatus.setText(R.string.status_processing);
                    if (mProgressProcessing != null) {
                        mProgressProcessing.setVisibility(View.VISIBLE);
                        mProgressProcessing.setIndeterminate(true);
                    }
                    break;
                case COMPLETE:
                    if (mTextStatus != null) mTextStatus.setText("Complete");
                    if (mProgressProcessing != null) mProgressProcessing.setVisibility(View.GONE);
                    // WHY: Guard prevents re-navigation when returning from SessionDetailFragment
                    // with state still COMPLETE (e.g. after deleting a session)
                    if (!mHasNavigatedToDetail) {
                        mHasNavigatedToDetail = true;
                        mTimerHandler.postDelayed(this::navigateToSessionDetail, 1500);
                    }
                    break;
                case IDLE:
                    // Reset UI
                    break;
            }
        });

        Log.i(TAG, "[RecordingFragment] LiveData observers attached to service");
    }

    /**
     * Styles the speaker name prefix ("Stephen:") in gold, bold, and slightly larger.
     * Returns a SpannableString if a "Name: " pattern is found at the start,
     * otherwise returns the plain transcript string.
     */
    private CharSequence styleSpeakerName(@NonNull String transcript) {
        SpannableString spannable = new SpannableString(transcript);
        int primaryColor = ContextCompat.getColor(requireContext(), R.color.primary);

        // Style speaker name ("Stephen:") if present at the start
        int colonNewline = transcript.indexOf(":\n");
        if (colonNewline > 0 && colonNewline <= 30) {
            int nameEnd = colonNewline + 1; // Include colon
            spannable.setSpan(
                    new ForegroundColorSpan(primaryColor),
                    0, nameEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            spannable.setSpan(
                    new StyleSpan(Typeface.BOLD),
                    0, nameEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            spannable.setSpan(
                    new RelativeSizeSpan(1.15f),
                    0, nameEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        // Style all [M:SS] timestamps in gold and bold
        int searchFrom = 0;
        while (searchFrom < transcript.length()) {
            int bracketOpen = transcript.indexOf('[', searchFrom);
            if (bracketOpen < 0) break;
            int bracketClose = transcript.indexOf(']', bracketOpen);
            if (bracketClose < 0) break;
            // Verify it looks like a timestamp (contains ':' and is short)
            String inside = transcript.substring(bracketOpen + 1, bracketClose);
            if (inside.contains(":") && inside.length() <= 7) {
                spannable.setSpan(
                        new ForegroundColorSpan(primaryColor),
                        bracketOpen, bracketClose + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                spannable.setSpan(
                        new StyleSpan(Typeface.BOLD),
                        bracketOpen, bracketClose + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            searchFrom = bracketClose + 1;
        }

        return spannable;
    }

    /**
     * Updates the transcript display with new text.
     */
    public void appendTranscript(String text) {
        if (text == null || text.isEmpty()) return;

        mTranscriptBuilder.append(text).append(" ");

        if (mTextTranscript != null) {
            mTextTranscript.setText(mTranscriptBuilder.toString());
        }

        // Auto-scroll to bottom
        if (mScrollTranscript != null) {
            mScrollTranscript.post(() -> mScrollTranscript.fullScroll(View.FOCUS_DOWN));
        }
    }
}
