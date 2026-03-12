/**
 * WhisperEngineHallucinationTest.java
 *
 * Unit tests for WhisperEngine.isHallucination() static method.
 * Tests hallucination detection without requiring native library loading.
 *
 * @author STELLiQ Engineering
 * @version 1.0.0
 * @since ARIA Commercial Build — 2026-03-09
 */
package com.stelliq.aria.asr;

import static org.junit.Assert.*;

import org.junit.Test;

public class WhisperEngineHallucinationTest {

    // ═══════════════════════════════════════════════════════════════════════════
    // NULL / EMPTY CASES
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    public void isHallucination_null_returnsTrue() {
        assertTrue(WhisperEngine.isHallucination(null));
    }

    @Test
    public void isHallucination_empty_returnsTrue() {
        assertTrue(WhisperEngine.isHallucination(""));
    }

    @Test
    public void isHallucination_whitespace_returnsTrue() {
        assertTrue(WhisperEngine.isHallucination("   "));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PATTERN MATCHES (BRACKETS, PUNCTUATION)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    public void isHallucination_bracketedText_returnsTrue() {
        assertTrue(WhisperEngine.isHallucination("[Music]"));
    }

    @Test
    public void isHallucination_parenthesized_returnsTrue() {
        assertTrue(WhisperEngine.isHallucination("(applause)"));
    }

    @Test
    public void isHallucination_punctuationOnly_returnsTrue() {
        assertTrue(WhisperEngine.isHallucination("..."));
    }

    @Test
    public void isHallucination_blankAudio_returnsTrue() {
        assertTrue(WhisperEngine.isHallucination("[BLANK_AUDIO]"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // KNOWN PHRASES
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    public void isHallucination_thankYouForWatching_returnsTrue() {
        assertTrue(WhisperEngine.isHallucination("Thank you for watching"));
    }

    @Test
    public void isHallucination_subscribe_returnsTrue() {
        assertTrue(WhisperEngine.isHallucination("subscribe"));
    }

    @Test
    public void isHallucination_containsPhrase_returnsTrue() {
        assertTrue(WhisperEngine.isHallucination("Please like and subscribe to my channel"));
    }

    @Test
    public void isHallucination_byeBye_returnsTrue() {
        assertTrue(WhisperEngine.isHallucination("bye bye"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SHORT TEXT
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    public void isHallucination_twoChars_returnsTrue() {
        assertTrue(WhisperEngine.isHallucination("hi"));
    }

    @Test
    public void isHallucination_threeChars_returnsFalse() {
        assertFalse(WhisperEngine.isHallucination("the"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LEGITIMATE TEXT
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    public void isHallucination_normalSpeech_returnsFalse() {
        assertFalse(WhisperEngine.isHallucination("We need to review the deployment plan"));
    }

    @Test
    public void isHallucination_longSentence_returnsFalse() {
        assertFalse(WhisperEngine.isHallucination(
                "The team discussed the timeline for the next sprint and agreed on priorities"));
    }
}
