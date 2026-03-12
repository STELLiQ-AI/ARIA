/**
 * SessionStateTest.java
 *
 * Unit tests for SessionState enum and state machine transitions.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Display name correctness</li>
 *   <li>isBusy flag correctness</li>
 *   <li>isRecording flag correctness</li>
 *   <li>Valid state transitions</li>
 *   <li>Invalid state transitions</li>
 * </ul>
 *
 * @author STELLiQ Engineering
 * @version 0.1.0
 * @since ARIA Demo Build — 2026-03-02
 */
package com.stelliq.aria.model;

import static org.junit.Assert.*;

import org.junit.Test;

public class SessionStateTest {

    // ═══════════════════════════════════════════════════════════════════════════
    // DISPLAY NAME TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    public void getDisplayName_idle_returnsReady() {
        assertEquals("Ready", SessionState.IDLE.getDisplayName());
    }

    @Test
    public void getDisplayName_initializing_returnsInitializing() {
        assertEquals("Initializing", SessionState.INITIALIZING.getDisplayName());
    }

    @Test
    public void getDisplayName_recording_returnsRecording() {
        assertEquals("Recording", SessionState.RECORDING.getDisplayName());
    }

    @Test
    public void getDisplayName_summarizing_returnsSummarizing() {
        assertEquals("Summarizing", SessionState.SUMMARIZING.getDisplayName());
    }

    @Test
    public void getDisplayName_complete_returnsComplete() {
        assertEquals("Complete", SessionState.COMPLETE.getDisplayName());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // IS BUSY TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    public void isBusy_idle_returnsFalse() {
        assertFalse(SessionState.IDLE.isBusy());
    }

    @Test
    public void isBusy_initializing_returnsTrue() {
        assertTrue(SessionState.INITIALIZING.isBusy());
    }

    @Test
    public void isBusy_recording_returnsTrue() {
        assertTrue(SessionState.RECORDING.isBusy());
    }

    @Test
    public void isBusy_summarizing_returnsTrue() {
        assertTrue(SessionState.SUMMARIZING.isBusy());
    }

    @Test
    public void isBusy_complete_returnsFalse() {
        assertFalse(SessionState.COMPLETE.isBusy());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // IS RECORDING TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    public void isRecording_idle_returnsFalse() {
        assertFalse(SessionState.IDLE.isRecording());
    }

    @Test
    public void isRecording_initializing_returnsFalse() {
        assertFalse(SessionState.INITIALIZING.isRecording());
    }

    @Test
    public void isRecording_recording_returnsTrue() {
        // WHY: Only RECORDING state should return true
        assertTrue(SessionState.RECORDING.isRecording());
    }

    @Test
    public void isRecording_summarizing_returnsFalse() {
        assertFalse(SessionState.SUMMARIZING.isRecording());
    }

    @Test
    public void isRecording_complete_returnsFalse() {
        assertFalse(SessionState.COMPLETE.isRecording());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // VALID TRANSITION TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    public void canTransitionTo_idleToInitializing_returnsTrue() {
        assertTrue(SessionState.IDLE.canTransitionTo(SessionState.INITIALIZING));
    }

    @Test
    public void canTransitionTo_initializingToRecording_returnsTrue() {
        assertTrue(SessionState.INITIALIZING.canTransitionTo(SessionState.RECORDING));
    }

    @Test
    public void canTransitionTo_initializingToIdle_returnsTrue() {
        // WHY: Can cancel/fail during initialization
        assertTrue(SessionState.INITIALIZING.canTransitionTo(SessionState.IDLE));
    }

    @Test
    public void canTransitionTo_recordingToSummarizing_returnsTrue() {
        assertTrue(SessionState.RECORDING.canTransitionTo(SessionState.SUMMARIZING));
    }

    @Test
    public void canTransitionTo_recordingToIdle_returnsTrue() {
        // WHY: Can cancel during recording
        assertTrue(SessionState.RECORDING.canTransitionTo(SessionState.IDLE));
    }

    @Test
    public void canTransitionTo_summarizingToComplete_returnsTrue() {
        assertTrue(SessionState.SUMMARIZING.canTransitionTo(SessionState.COMPLETE));
    }

    @Test
    public void canTransitionTo_summarizingToIdle_returnsTrue() {
        // WHY: Can fail during summarization
        assertTrue(SessionState.SUMMARIZING.canTransitionTo(SessionState.IDLE));
    }

    @Test
    public void canTransitionTo_completeToIdle_returnsTrue() {
        // WHY: Start new session
        assertTrue(SessionState.COMPLETE.canTransitionTo(SessionState.IDLE));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // INVALID TRANSITION TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    public void canTransitionTo_idleToRecording_returnsFalse() {
        // WHY: Must go through INITIALIZING first
        assertFalse(SessionState.IDLE.canTransitionTo(SessionState.RECORDING));
    }

    @Test
    public void canTransitionTo_idleToSummarizing_returnsFalse() {
        assertFalse(SessionState.IDLE.canTransitionTo(SessionState.SUMMARIZING));
    }

    @Test
    public void canTransitionTo_idleToComplete_returnsFalse() {
        assertFalse(SessionState.IDLE.canTransitionTo(SessionState.COMPLETE));
    }

    @Test
    public void canTransitionTo_idleToIdle_returnsFalse() {
        // WHY: Self-transition not allowed
        assertFalse(SessionState.IDLE.canTransitionTo(SessionState.IDLE));
    }

    @Test
    public void canTransitionTo_initializingToSummarizing_returnsFalse() {
        // WHY: Must go through RECORDING first
        assertFalse(SessionState.INITIALIZING.canTransitionTo(SessionState.SUMMARIZING));
    }

    @Test
    public void canTransitionTo_initializingToComplete_returnsFalse() {
        assertFalse(SessionState.INITIALIZING.canTransitionTo(SessionState.COMPLETE));
    }

    @Test
    public void canTransitionTo_recordingToInitializing_returnsFalse() {
        // WHY: Cannot go backwards
        assertFalse(SessionState.RECORDING.canTransitionTo(SessionState.INITIALIZING));
    }

    @Test
    public void canTransitionTo_recordingToComplete_returnsFalse() {
        // WHY: Must go through SUMMARIZING
        assertFalse(SessionState.RECORDING.canTransitionTo(SessionState.COMPLETE));
    }

    @Test
    public void canTransitionTo_summarizingToRecording_returnsFalse() {
        // WHY: Cannot go backwards
        assertFalse(SessionState.SUMMARIZING.canTransitionTo(SessionState.RECORDING));
    }

    @Test
    public void canTransitionTo_completeToRecording_returnsFalse() {
        // WHY: Must go through IDLE first
        assertFalse(SessionState.COMPLETE.canTransitionTo(SessionState.RECORDING));
    }

    @Test
    public void canTransitionTo_completeToInitializing_returnsFalse() {
        // WHY: Must go through IDLE first
        assertFalse(SessionState.COMPLETE.canTransitionTo(SessionState.INITIALIZING));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // FULL LIFECYCLE TEST
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    public void fullLifecycle_happyPath_allTransitionsValid() {
        // Simulate a complete successful session lifecycle
        SessionState state = SessionState.IDLE;

        // Start session
        assertTrue(state.canTransitionTo(SessionState.INITIALIZING));
        state = SessionState.INITIALIZING;

        // Models loaded
        assertTrue(state.canTransitionTo(SessionState.RECORDING));
        state = SessionState.RECORDING;

        // User stops recording
        assertTrue(state.canTransitionTo(SessionState.SUMMARIZING));
        state = SessionState.SUMMARIZING;

        // LLM completes
        assertTrue(state.canTransitionTo(SessionState.COMPLETE));
        state = SessionState.COMPLETE;

        // User starts new session
        assertTrue(state.canTransitionTo(SessionState.IDLE));
    }

    @Test
    public void fullLifecycle_cancelDuringRecording_valid() {
        SessionState state = SessionState.IDLE;

        // Start session
        assertTrue(state.canTransitionTo(SessionState.INITIALIZING));
        state = SessionState.INITIALIZING;

        // Models loaded
        assertTrue(state.canTransitionTo(SessionState.RECORDING));
        state = SessionState.RECORDING;

        // User cancels
        assertTrue(state.canTransitionTo(SessionState.IDLE));
    }
}
