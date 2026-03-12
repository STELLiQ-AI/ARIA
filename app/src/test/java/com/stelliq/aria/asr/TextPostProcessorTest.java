/**
 * TextPostProcessorTest.java
 *
 * Unit tests for TextPostProcessor utility methods.
 *
 * @author STELLiQ Engineering
 * @version 1.0.0
 * @since ARIA Commercial Build — 2026-03-09
 */
package com.stelliq.aria.asr;

import static org.junit.Assert.*;

import org.junit.Test;

public class TextPostProcessorTest {

    // ═══════════════════════════════════════════════════════════════════════════
    // processSegment TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    public void processSegment_null_returnsEmpty() {
        assertEquals("", TextPostProcessor.processSegment(null));
    }

    @Test
    public void processSegment_empty_returnsEmpty() {
        assertEquals("", TextPostProcessor.processSegment(""));
    }

    @Test
    public void processSegment_whitespaceOnly_returnsEmpty() {
        assertEquals("", TextPostProcessor.processSegment("   "));
    }

    @Test
    public void processSegment_lowercaseStart_capitalizesFirst() {
        assertEquals("Hello world", TextPostProcessor.processSegment("hello world"));
    }

    @Test
    public void processSegment_afterPeriod_capitalizes() {
        assertEquals("First. Second", TextPostProcessor.processSegment("first. second"));
    }

    @Test
    public void processSegment_afterExclamation_capitalizes() {
        assertEquals("Wow! Great", TextPostProcessor.processSegment("wow! great"));
    }

    @Test
    public void processSegment_afterQuestion_capitalizes() {
        assertEquals("What? Yes", TextPostProcessor.processSegment("what? yes"));
    }

    @Test
    public void processSegment_multipleSpaces_collapsed() {
        assertEquals("Hello world", TextPostProcessor.processSegment("hello   world"));
    }

    @Test
    public void processSegment_alreadyCapitalized_unchanged() {
        assertEquals("Hello World", TextPostProcessor.processSegment("Hello World"));
    }

    @Test
    public void processSegment_mixedPunctuation_correctCaps() {
        String input = "hello. how are you? fine! thanks.";
        String expected = "Hello. How are you? Fine! Thanks.";
        assertEquals(expected, TextPostProcessor.processSegment(input));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // formatWithSpeaker TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    public void formatWithSpeaker_basic_formatsCorrectly() {
        assertEquals("John: Hello everyone",
                TextPostProcessor.formatWithSpeaker("John", "Hello everyone"));
    }

    @Test
    public void formatWithSpeaker_emptyText_formatsCorrectly() {
        assertEquals("John: ", TextPostProcessor.formatWithSpeaker("John", ""));
    }
}
