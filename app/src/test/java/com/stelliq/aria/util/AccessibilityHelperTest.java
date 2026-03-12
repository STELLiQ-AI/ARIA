/**
 * AccessibilityHelperTest.java
 *
 * Unit tests for AccessibilityHelper utility methods.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Duration formatting for accessibility</li>
 *   <li>Word count formatting</li>
 *   <li>Edge cases for format methods</li>
 * </ul>
 *
 * <p>Note: View-related methods require Android instrumented tests.
 * This file tests the static utility methods only.
 *
 * @author STELLiQ Engineering
 * @version 0.1.0
 * @since ARIA Demo Build — 2026-03-04
 */
package com.stelliq.aria.util;

import static org.junit.Assert.*;

import org.junit.Test;

public class AccessibilityHelperTest {

    // ═══════════════════════════════════════════════════════════════════════════
    // DURATION FORMATTING TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    public void formatDurationForAccessibility_zero_returnsZeroSeconds() {
        // Act
        String result = AccessibilityHelper.formatDurationForAccessibility(0);

        // Assert
        assertEquals("0 seconds", result);
    }

    @Test
    public void formatDurationForAccessibility_oneSecond_returnsOneSingular() {
        // Act
        String result = AccessibilityHelper.formatDurationForAccessibility(1000);

        // Assert
        assertEquals("1 second", result);
    }

    @Test
    public void formatDurationForAccessibility_multipleSeconds_returnsPlural() {
        // Act
        String result = AccessibilityHelper.formatDurationForAccessibility(30000);

        // Assert
        assertEquals("30 seconds", result);
    }

    @Test
    public void formatDurationForAccessibility_oneMinute_returnsMinuteSingular() {
        // Act
        String result = AccessibilityHelper.formatDurationForAccessibility(60000);

        // Assert
        assertEquals("1 minute", result);
    }

    @Test
    public void formatDurationForAccessibility_multipleMinutes_returnsPlural() {
        // Act
        String result = AccessibilityHelper.formatDurationForAccessibility(180000);

        // Assert
        assertEquals("3 minutes", result);
    }

    @Test
    public void formatDurationForAccessibility_minutesAndSeconds_returnsBoth() {
        // Act - 2 minutes 30 seconds
        String result = AccessibilityHelper.formatDurationForAccessibility(150000);

        // Assert
        assertEquals("2 minutes 30 seconds", result);
    }

    @Test
    public void formatDurationForAccessibility_oneMinuteOneSecond_returnsSingular() {
        // Act - 1 minute 1 second
        String result = AccessibilityHelper.formatDurationForAccessibility(61000);

        // Assert
        assertEquals("1 minute 1 second", result);
    }

    @Test
    public void formatDurationForAccessibility_fiveMinutesTenSeconds_correct() {
        // Act - 5 minutes 10 seconds
        String result = AccessibilityHelper.formatDurationForAccessibility(310000);

        // Assert
        assertEquals("5 minutes 10 seconds", result);
    }

    @Test
    public void formatDurationForAccessibility_roundsDown_milliseconds() {
        // Act - 1.9 seconds should be 1 second
        String result = AccessibilityHelper.formatDurationForAccessibility(1900);

        // Assert
        assertEquals("1 second", result);
    }

    @Test
    public void formatDurationForAccessibility_longDuration_works() {
        // Act - 1 hour 30 minutes (90 minutes)
        String result = AccessibilityHelper.formatDurationForAccessibility(5400000);

        // Assert
        assertEquals("90 minutes", result);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // WORD COUNT FORMATTING TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    public void formatWordCountForAccessibility_zero_returnsZeroWords() {
        // Act
        String result = AccessibilityHelper.formatWordCountForAccessibility(0);

        // Assert
        assertEquals("0 words", result);
    }

    @Test
    public void formatWordCountForAccessibility_one_returnsSingular() {
        // Act
        String result = AccessibilityHelper.formatWordCountForAccessibility(1);

        // Assert
        assertEquals("1 word", result);
    }

    @Test
    public void formatWordCountForAccessibility_multiple_returnsPlural() {
        // Act
        String result = AccessibilityHelper.formatWordCountForAccessibility(25);

        // Assert
        assertEquals("25 words", result);
    }

    @Test
    public void formatWordCountForAccessibility_largeNumber_works() {
        // Act
        String result = AccessibilityHelper.formatWordCountForAccessibility(1500);

        // Assert
        assertEquals("1500 words", result);
    }

    @Test
    public void formatWordCountForAccessibility_negative_handlesGracefully() {
        // WHY: Edge case - negative shouldn't happen but should handle gracefully
        // Act
        String result = AccessibilityHelper.formatWordCountForAccessibility(-5);

        // Assert
        assertEquals("-5 words", result);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // INTEGRATION SCENARIOS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    public void formatDurationForAccessibility_typicalRecordingLength_readable() {
        // Arrange - typical AAR recording of 15 minutes 45 seconds
        long durationMs = (15 * 60 + 45) * 1000;

        // Act
        String result = AccessibilityHelper.formatDurationForAccessibility(durationMs);

        // Assert
        assertEquals("15 minutes 45 seconds", result);
    }

    @Test
    public void formatWordCountForAccessibility_typicalTranscriptLength_readable() {
        // Arrange - typical AAR transcript of ~500 words
        int wordCount = 537;

        // Act
        String result = AccessibilityHelper.formatWordCountForAccessibility(wordCount);

        // Assert
        assertEquals("537 words", result);
    }
}
