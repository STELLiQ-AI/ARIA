/**
 * SessionController.java
 *
 * State machine controller for ARIA session lifecycle. Manages state transitions
 * and exposes LiveData for UI observation.
 *
 * <p>Responsibility:
 * <ul>
 *   <li>Manage session state transitions (IDLE → RECORDING → SUMMARIZING → COMPLETE)</li>
 *   <li>Validate state transitions using SessionState.canTransitionTo()</li>
 *   <li>Expose LiveData for session state, transcript, streaming output, and errors</li>
 *   <li>Track model readiness for UI gating</li>
 *   <li>This class does NOT perform inference — that's the engines' job</li>
 * </ul>
 *
 * <p>Architecture Position:
 * Service layer component owned by ARIASessionService. UI Fragments observe via LiveData.
 * All state updates must be posted to main thread for LiveData safety.
 *
 * <p>Thread Safety:
 * LiveData posting is thread-safe. All public methods can be called from any thread.
 * State transitions are synchronized via main thread posting.
 *
 * <p>Air-Gap Compliance:
 * N/A — no network involvement.
 *
 * @author STELLiQ Engineering
 * @version 0.1.0
 * @since ARIA Demo Build — 2026-03-02
 */
package com.stelliq.aria.service;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.AnyThread;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.stelliq.aria.model.AARSummary;
import com.stelliq.aria.model.SessionState;
import com.stelliq.aria.util.Constants;

import java.util.UUID;

public class SessionController {

    private static final String TAG = Constants.LOG_TAG_SESSION;

    // ═══════════════════════════════════════════════════════════════════════════
    // LIVEDATA — OBSERVED BY UI
    // ═══════════════════════════════════════════════════════════════════════════

    private final MutableLiveData<SessionState> mStateLiveData = new MutableLiveData<>(SessionState.IDLE);
    private final MutableLiveData<String> mTranscriptLiveData = new MutableLiveData<>("");
    private final MutableLiveData<String> mStreamingOutputLiveData = new MutableLiveData<>("");
    private final MutableLiveData<AARSummary> mSummaryLiveData = new MutableLiveData<>();
    private final MutableLiveData<String> mErrorLiveData = new MutableLiveData<>();
    private final MutableLiveData<Boolean> mModelsReadyLiveData = new MutableLiveData<>(false);
    private final MutableLiveData<Long> mRecordingStartTimeLiveData = new MutableLiveData<>();
    private final MutableLiveData<Integer> mParticipantCountLiveData = new MutableLiveData<>(1);

    // ═══════════════════════════════════════════════════════════════════════════
    // SESSION TRACKING
    // ═══════════════════════════════════════════════════════════════════════════

    @Nullable
    private String mCurrentSessionUuid;

    private long mSessionStartTimeMs;

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    // ═══════════════════════════════════════════════════════════════════════════
    // LIVEDATA ACCESSORS — FOR UI OBSERVATION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Returns LiveData for current session state.
     * UI should observe this to update button states and visibility.
     */
    @NonNull
    public LiveData<SessionState> getStateLiveData() {
        return mStateLiveData;
    }

    /**
     * Returns LiveData for live transcript during recording.
     * Updated incrementally as whisper.cpp produces output.
     */
    @NonNull
    public LiveData<String> getTranscriptLiveData() {
        return mTranscriptLiveData;
    }

    /**
     * Returns LiveData for streaming LLM output during summarization.
     * Updated token-by-token as llama.cpp produces output.
     */
    @NonNull
    public LiveData<String> getStreamingOutputLiveData() {
        return mStreamingOutputLiveData;
    }

    /**
     * Returns LiveData for final AAR summary after parsing.
     * Set when SUMMARIZING → COMPLETE transition occurs.
     */
    @NonNull
    public LiveData<AARSummary> getSummaryLiveData() {
        return mSummaryLiveData;
    }

    /**
     * Returns LiveData for error messages.
     * UI should observe and display toasts/snackbars.
     */
    @NonNull
    public LiveData<String> getErrorLiveData() {
        return mErrorLiveData;
    }

    /**
     * Returns LiveData for model readiness state.
     * Start button should be disabled until this is true.
     */
    @NonNull
    public LiveData<Boolean> getModelsReadyLiveData() {
        return mModelsReadyLiveData;
    }

    /**
     * Returns LiveData for recording start time (System.currentTimeMillis).
     * UI uses this for elapsed time display.
     */
    @NonNull
    public LiveData<Long> getRecordingStartTimeLiveData() {
        return mRecordingStartTimeLiveData;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // STATE ACCESSORS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Returns the current session state synchronously.
     * For one-time checks; prefer observing LiveData for reactive updates.
     */
    @NonNull
    public SessionState getCurrentState() {
        SessionState state = mStateLiveData.getValue();
        return state != null ? state : SessionState.IDLE;
    }

    /**
     * Returns the current session UUID.
     *
     * @return Session UUID, or null if no session is active
     */
    @Nullable
    public String getCurrentSessionUuid() {
        return mCurrentSessionUuid;
    }

    /**
     * Returns the session start time in milliseconds.
     */
    public long getSessionStartTimeMs() {
        return mSessionStartTimeMs;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // STATE TRANSITIONS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Transitions to a new session state.
     *
     * <p>Validates the transition is legal per SessionState rules.
     * Posts to main thread for LiveData safety.
     *
     * @param newState The target state
     * @return True if transition was valid and executed
     */
    @AnyThread
    public boolean transitionTo(@NonNull SessionState newState) {
        SessionState currentState = getCurrentState();

        // Validate transition
        if (!currentState.canTransitionTo(newState)) {
            Log.e(TAG, "[SessionController.transitionTo] Invalid transition: "
                    + currentState + " → " + newState);
            return false;
        }

        Log.i(TAG, "[SessionController.transitionTo] " + currentState + " → " + newState);

        // Handle state-specific setup/cleanup
        switch (newState) {
            case INITIALIZING:
                // Generate new session UUID
                mCurrentSessionUuid = UUID.randomUUID().toString();
                Log.d(TAG, "[SessionController.transitionTo] New session: " + mCurrentSessionUuid);
                break;

            case RECORDING:
                // Record start time for elapsed time display
                mSessionStartTimeMs = System.currentTimeMillis();
                postToMain(() -> {
                    mRecordingStartTimeLiveData.setValue(mSessionStartTimeMs);
                    mTranscriptLiveData.setValue("");  // WHY: Clear any stale transcript
                });
                break;

            case SUMMARIZING:
                // Clear streaming output for new summary
                postToMain(() -> mStreamingOutputLiveData.setValue(""));
                break;

            case COMPLETE:
                // Session complete — nothing to clean up
                break;

            case IDLE:
                // Reset for next session
                if (currentState != SessionState.IDLE) {
                    postToMain(() -> {
                        mTranscriptLiveData.setValue("");
                        mStreamingOutputLiveData.setValue("");
                        mSummaryLiveData.setValue(null);
                    });
                }
                mCurrentSessionUuid = null;
                break;
        }

        // Post state change to main thread
        postToMain(() -> mStateLiveData.setValue(newState));

        return true;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DATA UPDATES
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Updates the live transcript during recording.
     * Called from ASR worker thread as whisper.cpp produces output.
     *
     * @param transcript The full accumulated transcript
     */
    @AnyThread
    public void updateTranscript(@NonNull String transcript) {
        postToMain(() -> mTranscriptLiveData.setValue(transcript));
    }

    /**
     * Updates the streaming LLM output during summarization.
     * Called from LLM worker thread as llama.cpp produces tokens.
     *
     * @param output The accumulated LLM output so far
     */
    @AnyThread
    public void updateStreamingOutput(@NonNull String output) {
        postToMain(() -> mStreamingOutputLiveData.setValue(output));
    }

    /**
     * Sets the final AAR summary after parsing.
     * Called when LLM inference completes successfully.
     *
     * @param summary The parsed AAR summary
     */
    @AnyThread
    public void setSummary(@NonNull AARSummary summary) {
        postToMain(() -> mSummaryLiveData.setValue(summary));
    }

    /**
     * Posts an error message to the UI.
     * UI should observe and display toast/snackbar.
     *
     * @param message Human-readable error description
     */
    @AnyThread
    public void postError(@NonNull String message) {
        Log.e(TAG, "[SessionController.postError] " + message);
        postToMain(() -> mErrorLiveData.setValue(message));
    }

    /**
     * Clears the current error (after UI has displayed it).
     */
    @AnyThread
    public void clearError() {
        postToMain(() -> mErrorLiveData.setValue(null));
    }

    /**
     * Updates the participant count for multi-device meetings.
     */
    @AnyThread
    public void updateParticipantCount(int count) {
        postToMain(() -> mParticipantCountLiveData.setValue(count));
    }

    /**
     * Returns LiveData for participant count in multi-device meetings.
     */
    @NonNull
    public LiveData<Integer> getParticipantCountLiveData() {
        return mParticipantCountLiveData;
    }

    /**
     * Sets the model readiness state.
     * Called when WhisperEngine and LlamaEngine are both loaded.
     *
     * @param ready True if both models are loaded and ready
     */
    @AnyThread
    public void setModelsReady(boolean ready) {
        Log.i(TAG, "[SessionController.setModelsReady] " + ready);
        postToMain(() -> mModelsReadyLiveData.setValue(ready));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Posts a runnable to the main thread for LiveData safety.
     */
    private void postToMain(@NonNull Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
        } else {
            mMainHandler.post(action);
        }
    }

    /**
     * Returns the elapsed recording time in milliseconds.
     * Returns 0 if not currently recording.
     */
    public long getElapsedRecordingMs() {
        if (getCurrentState() == SessionState.RECORDING && mSessionStartTimeMs > 0) {
            return System.currentTimeMillis() - mSessionStartTimeMs;
        }
        return 0;
    }

    /**
     * Formats elapsed time as MM:SS or HH:MM:SS string.
     *
     * @param elapsedMs Elapsed time in milliseconds
     * @return Formatted time string
     */
    @NonNull
    public static String formatElapsedTime(long elapsedMs) {
        long totalSeconds = elapsedMs / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        if (hours > 0) {
            return String.format("%02d:%02d:%02d", hours, minutes, seconds);
        } else {
            return String.format("%02d:%02d", minutes, seconds);
        }
    }
}
