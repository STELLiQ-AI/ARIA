/**
 * SessionState.java
 *
 * Enumeration of all valid ARIA session states. Defines the complete state machine
 * for a recording session lifecycle.
 *
 * <p>Responsibility:
 * <ul>
 *   <li>Define the 5 valid session states: IDLE, INITIALIZING, RECORDING, SUMMARIZING, COMPLETE</li>
 *   <li>Provide human-readable display names for UI</li>
 *   <li>Provide validation for state transitions</li>
 *   <li>This enum does NOT enforce transitions — that's SessionController's job</li>
 * </ul>
 *
 * <p>State Machine:
 * <pre>
 * IDLE → INITIALIZING → RECORDING → SUMMARIZING → COMPLETE
 *   ↑                        ↓            ↓           ↓
 *   └────────────────────────┴────────────┴───────────┘
 *                      (cancel/error/new session)
 * </pre>
 *
 * <p>Architecture Position:
 * Model layer enum used by SessionController, ARIASessionService, and all UI Fragments.
 * Pure data definition — no behavior.
 *
 * <p>Thread Safety:
 * Enum instances are inherently thread-safe and immutable.
 *
 * <p>Air-Gap Compliance:
 * N/A — no network involvement.
 *
 * @author STELLiQ Engineering
 * @version 0.1.0
 * @since ARIA Demo Build — 2026-03-02
 */
package com.stelliq.aria.model;

import androidx.annotation.NonNull;

public enum SessionState {

    /**
     * Initial state. No recording in progress.
     * Start button is enabled (if models are loaded).
     * Transitions to: INITIALIZING (on user tap START)
     */
    IDLE("Ready", false, false),

    /**
     * Transitional state while models are being verified/loaded.
     * Shows "Initializing AI Engine..." overlay.
     * Transitions to: RECORDING (on successful init), IDLE (on init failure)
     */
    INITIALIZING("Initializing", true, false),

    /**
     * Active recording state.
     * AudioRecord + VAD + Whisper pipeline is running.
     * Live transcript updates displayed.
     * Transitions to: SUMMARIZING (on user tap STOP), IDLE (on cancel)
     */
    RECORDING("Recording", true, true),

    /**
     * LLM inference in progress.
     * Llama 3.1 8B Instruct is processing the transcript.
     * Streaming token output displayed.
     * Transitions to: COMPLETE (on LLM done), IDLE (on fatal error)
     */
    SUMMARIZING("Summarizing", true, false),

    /**
     * Session complete. AAR summary displayed.
     * TC 7-0.1 four-section format shown.
     * Session persisted to SQLite.
     * Transitions to: IDLE (on user tap NEW SESSION)
     */
    COMPLETE("Complete", false, false),

    /**
     * Error state. Something went wrong.
     * Error message displayed to user.
     * Transitions to: IDLE (on user acknowledgment)
     */
    ERROR("Error", false, false);

    @NonNull
    private final String mDisplayName;
    private final boolean mIsBusy;
    private final boolean mIsRecording;

    /**
     * Constructs a SessionState enum value.
     *
     * @param displayName Human-readable name for UI display
     * @param isBusy      True if user interaction should be limited
     * @param isRecording True if audio capture is active
     */
    SessionState(@NonNull String displayName, boolean isBusy, boolean isRecording) {
        mDisplayName = displayName;
        mIsBusy = isBusy;
        mIsRecording = isRecording;
    }

    /**
     * Returns the human-readable display name for this state.
     *
     * @return Display name suitable for UI (e.g., "Recording", "Summarizing")
     */
    @NonNull
    public String getDisplayName() {
        return mDisplayName;
    }

    /**
     * Returns whether this state represents a busy/processing condition.
     *
     * @return True if the system is actively processing (buttons should be disabled)
     */
    public boolean isBusy() {
        return mIsBusy;
    }

    /**
     * Returns whether audio capture is active in this state.
     *
     * @return True if AudioRecord is running and VAD/Whisper pipeline is active
     */
    public boolean isRecording() {
        return mIsRecording;
    }

    /**
     * Validates whether a transition from this state to the target state is legal.
     *
     * @param targetState The desired next state
     * @return True if the transition is valid per the state machine rules
     */
    public boolean canTransitionTo(@NonNull SessionState targetState) {
        switch (this) {
            case IDLE:
                // WHY: From IDLE, can only go to INITIALIZING (user starts session)
                return targetState == INITIALIZING;

            case INITIALIZING:
                // WHY: From INITIALIZING, either succeed to RECORDING or fail back to IDLE
                return targetState == RECORDING || targetState == IDLE;

            case RECORDING:
                // WHY: From RECORDING, either complete to SUMMARIZING or cancel to IDLE
                return targetState == SUMMARIZING || targetState == IDLE;

            case SUMMARIZING:
                // WHY: From SUMMARIZING, either complete or fail back to IDLE
                return targetState == COMPLETE || targetState == IDLE;

            case COMPLETE:
                // WHY: From COMPLETE, can only start a new session (back to IDLE)
                return targetState == IDLE;

            case ERROR:
                // WHY: From ERROR, can only go back to IDLE
                return targetState == IDLE;

            default:
                return false;
        }
    }
}
