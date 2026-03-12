/**
 * SessionViewModel.java
 *
 * ViewModel for SessionFragment. Manages recording state and transcript updates.
 * Survives configuration changes and provides clean separation between UI and business logic.
 *
 * <p>Responsibility:
 * <ul>
 *   <li>Hold LiveData for session state, transcript, and metrics</li>
 *   <li>Bridge between SessionFragment and ARIASessionService</li>
 *   <li>Survive configuration changes (rotation, etc.)</li>
 *   <li>Provide action methods for recording control</li>
 * </ul>
 *
 * <p>Architecture Position:
 * MVVM ViewModel layer. Owned by Fragment, observes Service.
 *
 * <p>Thread Safety:
 * All LiveData posts are thread-safe. Service methods must be called from main thread.
 *
 * @author STELLiQ Engineering
 * @version 0.1.0
 * @since ARIA Demo Build — 2026-03-04
 */
package com.stelliq.aria.viewmodel;

import android.app.Application;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;

import com.stelliq.aria.model.SessionState;
import com.stelliq.aria.service.ARIASessionService;
import com.stelliq.aria.util.Constants;

/**
 * ViewModel for SessionFragment.
 *
 * <p>WHY: MVVM pattern separates UI concerns from business logic.
 * The ViewModel survives configuration changes, preventing re-initialization
 * of recording state during screen rotation.
 */
public class SessionViewModel extends AndroidViewModel {

    private static final String TAG = Constants.LOG_TAG_UI;

    // ═══════════════════════════════════════════════════════════════════════════
    // LIVE DATA
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Current session state (IDLE, INITIALIZING, RECORDING, etc.).
     */
    private final MutableLiveData<SessionState> mState = new MutableLiveData<>(SessionState.IDLE);

    /**
     * Live transcript text, updated during recording.
     */
    private final MutableLiveData<String> mTranscript = new MutableLiveData<>("");

    /**
     * Recording start timestamp for timer calculation.
     */
    private final MutableLiveData<Long> mRecordingStartTime = new MutableLiveData<>(0L);

    /**
     * Whether models (ASR, LLM) are loaded and ready.
     */
    private final MutableLiveData<Boolean> mModelsReady = new MutableLiveData<>(false);

    /**
     * Last error message, null if no error.
     */
    private final MutableLiveData<String> mError = new MutableLiveData<>();

    /**
     * Whether service is currently bound.
     */
    private final MutableLiveData<Boolean> mServiceBound = new MutableLiveData<>(false);

    // ═══════════════════════════════════════════════════════════════════════════
    // SERVICE BINDING
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Bound service reference. Set via bindService().
     */
    @Nullable
    private ARIASessionService mService;

    /**
     * Observer for service state changes.
     * WHY: Need to keep reference to remove observer when service unbinds.
     */
    @Nullable
    private Observer<SessionState> mStateObserver;

    /**
     * Observer for service transcript changes.
     */
    @Nullable
    private Observer<String> mTranscriptObserver;

    // ═══════════════════════════════════════════════════════════════════════════
    // CONSTRUCTOR
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Creates a new SessionViewModel.
     *
     * @param application Application context for AndroidViewModel
     */
    public SessionViewModel(@NonNull Application application) {
        super(application);
        Log.d(TAG, "[SessionViewModel] Created");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LIVE DATA GETTERS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Returns LiveData for session state observation.
     *
     * @return Session state LiveData
     */
    @NonNull
    public LiveData<SessionState> getState() {
        return mState;
    }

    /**
     * Returns LiveData for live transcript observation.
     *
     * @return Transcript LiveData
     */
    @NonNull
    public LiveData<String> getTranscript() {
        return mTranscript;
    }

    /**
     * Returns LiveData for recording start time.
     * Use for timer calculation.
     *
     * @return Recording start time in milliseconds (System.currentTimeMillis())
     */
    @NonNull
    public LiveData<Long> getRecordingStartTime() {
        return mRecordingStartTime;
    }

    /**
     * Returns LiveData for models ready state.
     *
     * @return True when ASR and LLM models are loaded
     */
    @NonNull
    public LiveData<Boolean> getModelsReady() {
        return mModelsReady;
    }

    /**
     * Returns LiveData for error messages.
     *
     * @return Error message or null if no error
     */
    @NonNull
    public LiveData<String> getError() {
        return mError;
    }

    /**
     * Returns LiveData for service bound state.
     *
     * @return True when service is bound
     */
    @NonNull
    public LiveData<Boolean> getServiceBound() {
        return mServiceBound;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SERVICE BINDING
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Binds to ARIASessionService and observes its state.
     *
     * <p>WHY: ViewModel acts as bridge between Fragment and Service.
     * This allows the ViewModel to survive configuration changes while
     * maintaining service observation.
     *
     * @param service Bound ARIASessionService
     */
    public void bindService(@NonNull ARIASessionService service) {
        Log.i(TAG, "[SessionViewModel.bindService] Binding to service");

        // WHY: Unbind previous service if any
        if (mService != null) {
            unbindService();
        }

        mService = service;
        mServiceBound.setValue(true);

        // WHY: Observe service state and forward to ViewModel LiveData
        mStateObserver = state -> {
            Log.d(TAG, "[SessionViewModel] State changed: " + state);
            mState.setValue(state);

            // WHY: Track recording start time for timer
            if (state == SessionState.RECORDING) {
                mRecordingStartTime.setValue(System.currentTimeMillis());
            } else if (state == SessionState.IDLE || state == SessionState.COMPLETE) {
                mRecordingStartTime.setValue(0L);
            }
        };

        // WHY: Observe service transcript and forward to ViewModel LiveData
        mTranscriptObserver = transcript -> {
            if (transcript != null) {
                mTranscript.setValue(transcript);
            }
        };

        // WHY: observeForever because ViewModel outlives Fragment lifecycle
        service.getSessionState().observeForever(mStateObserver);
        service.getLiveTranscript().observeForever(mTranscriptObserver);

        // WHY: Check initial models ready state
        updateModelsReady();
    }

    /**
     * Unbinds from ARIASessionService.
     * Called when service disconnects or ViewModel is cleared.
     */
    public void unbindService() {
        if (mService == null) {
            return;
        }

        Log.i(TAG, "[SessionViewModel.unbindService] Unbinding from service");

        // WHY: Remove observers to prevent memory leaks
        if (mStateObserver != null) {
            mService.getSessionState().removeObserver(mStateObserver);
            mStateObserver = null;
        }
        if (mTranscriptObserver != null) {
            mService.getLiveTranscript().removeObserver(mTranscriptObserver);
            mTranscriptObserver = null;
        }

        mService = null;
        mServiceBound.setValue(false);
    }

    /**
     * Returns whether service is currently bound.
     *
     * @return True if service is bound
     */
    public boolean isServiceBound() {
        return mService != null;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RECORDING ACTIONS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Starts recording if in IDLE state.
     *
     * <p>Preconditions:
     * <ul>
     *   <li>Service must be bound</li>
     *   <li>Current state must be IDLE</li>
     *   <li>Models must be ready</li>
     * </ul>
     */
    public void startRecording() {
        if (mService == null) {
            Log.e(TAG, "[SessionViewModel.startRecording] Service not bound");
            mError.setValue("Service not available");
            return;
        }

        SessionState currentState = mState.getValue();
        if (currentState != SessionState.IDLE) {
            Log.w(TAG, "[SessionViewModel.startRecording] Invalid state: " + currentState);
            return;
        }

        Log.i(TAG, "[SessionViewModel.startRecording] Starting recording");
        mService.startRecording();
    }

    /**
     * Stops recording and triggers summarization if in RECORDING state.
     *
     * <p>Preconditions:
     * <ul>
     *   <li>Service must be bound</li>
     *   <li>Current state must be RECORDING</li>
     * </ul>
     */
    public void stopRecording() {
        if (mService == null) {
            Log.e(TAG, "[SessionViewModel.stopRecording] Service not bound");
            mError.setValue("Service not available");
            return;
        }

        SessionState currentState = mState.getValue();
        if (currentState != SessionState.RECORDING) {
            Log.w(TAG, "[SessionViewModel.stopRecording] Invalid state: " + currentState);
            return;
        }

        Log.i(TAG, "[SessionViewModel.stopRecording] Stopping recording");
        mService.stopRecordingAndSummarize();
    }

    /**
     * Toggles recording state.
     * Starts recording if IDLE, stops if RECORDING.
     */
    public void toggleRecording() {
        SessionState currentState = mState.getValue();
        if (currentState == SessionState.IDLE) {
            startRecording();
        } else if (currentState == SessionState.RECORDING) {
            stopRecording();
        } else {
            Log.w(TAG, "[SessionViewModel.toggleRecording] Cannot toggle in state: " + currentState);
        }
    }

    /**
     * Cancels current operation and returns to IDLE state.
     */
    public void cancel() {
        if (mService == null) {
            return;
        }

        Log.i(TAG, "[SessionViewModel.cancel] Cancelling current operation");
        mService.reset();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MODEL STATE
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Updates models ready state from service.
     */
    private void updateModelsReady() {
        if (mService != null) {
            boolean ready = mService.areModelsReady();
            mModelsReady.setValue(ready);
        }
    }

    /**
     * Requests model loading if not already loaded.
     */
    public void ensureModelsLoaded() {
        if (mService != null && !Boolean.TRUE.equals(mModelsReady.getValue())) {
            Log.i(TAG, "[SessionViewModel.ensureModelsLoaded] Requesting model load");
            // WHY: Model loading is handled by service initialization
            updateModelsReady();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ERROR HANDLING
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Clears current error.
     */
    public void clearError() {
        mError.setValue(null);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Called when ViewModel is cleared.
     * Cleans up service observation.
     */
    @Override
    protected void onCleared() {
        super.onCleared();
        Log.d(TAG, "[SessionViewModel.onCleared] Cleaning up");
        unbindService();
    }
}
