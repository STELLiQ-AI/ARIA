/**
 * AARSummaryTest.java
 *
 * Unit tests for AARSummary data model, builder, and factory methods.
 *
 * @author STELLiQ Engineering
 * @version 1.0.0
 * @since ARIA Commercial Build — 2026-03-09
 */
package com.stelliq.aria.model;

import static org.junit.Assert.*;

import org.junit.Test;

public class AARSummaryTest {

    // ═══════════════════════════════════════════════════════════════════════════
    // BUILDER AND FIELD ACCESS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    public void builder_allFields_gettersReturnCorrectValues() {
        AARSummary summary = AARSummary.builder()
                .setTitle("Test Title")
                .setWhatWasPlanned("The plan")
                .setWhatHappened("What happened")
                .setWhyItHappened("Root cause")
                .setHowToImprove("Improvements")
                .setParsedSuccessfully(true)
                .setRawLlmOutput("{json}")
                .build();

        assertEquals("Test Title", summary.getTitle());
        assertEquals("The plan", summary.getWhatWasPlanned());
        assertEquals("What happened", summary.getWhatHappened());
        assertEquals("Root cause", summary.getWhyItHappened());
        assertEquals("Improvements", summary.getHowToImprove());
        assertTrue(summary.wasParsedSuccessfully());
        assertEquals("{json}", summary.getRawLlmOutput());
    }

    @Test
    public void builder_sanitize_trimsWhitespace() {
        AARSummary summary = AARSummary.builder()
                .setWhatWasPlanned("  trimmed  ")
                .build();

        assertEquals("trimmed", summary.getWhatWasPlanned());
    }

    @Test
    public void builder_sanitize_emptyBecomesNotCaptured() {
        AARSummary summary = AARSummary.builder()
                .setWhatWasPlanned("   ")
                .build();

        assertEquals(AARSummary.NOT_CAPTURED, summary.getWhatWasPlanned());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // hasAllFields
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    public void hasAllFields_allPopulated_returnsTrue() {
        AARSummary summary = AARSummary.builder()
                .setWhatWasPlanned("Plan")
                .setWhatHappened("Happened")
                .setWhyItHappened("Cause")
                .setHowToImprove("Improve")
                .build();

        assertTrue(summary.hasAllFields());
    }

    @Test
    public void hasAllFields_oneNotCaptured_returnsFalse() {
        AARSummary summary = AARSummary.builder()
                .setWhatWasPlanned("Plan")
                .setWhatHappened("Happened")
                .setWhyItHappened("Cause")
                // howToImprove left null → becomes NOT_CAPTURED
                .build();

        assertFalse(summary.hasAllFields());
    }

    @Test
    public void hasAllFields_allNotCaptured_returnsFalse() {
        AARSummary summary = AARSummary.empty();
        assertFalse(summary.hasAllFields());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // computeCompletenessScore
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    public void completenessScore_allPopulated_returnsOne() {
        AARSummary summary = AARSummary.builder()
                .setWhatWasPlanned("Plan")
                .setWhatHappened("Happened")
                .setWhyItHappened("Cause")
                .setHowToImprove("Improve")
                .build();

        assertEquals(1.0f, summary.computeCompletenessScore(), 0.001f);
    }

    @Test
    public void completenessScore_nonePopulated_returnsZero() {
        AARSummary summary = AARSummary.empty();
        assertEquals(0.0f, summary.computeCompletenessScore(), 0.001f);
    }

    @Test
    public void completenessScore_twoPopulated_returnsHalf() {
        AARSummary summary = AARSummary.builder()
                .setWhatWasPlanned("Plan")
                .setWhatHappened("Happened")
                .build();

        assertEquals(0.5f, summary.computeCompletenessScore(), 0.001f);
    }

    @Test
    public void completenessScore_notCaptured_treatedAsEmpty() {
        AARSummary summary = AARSummary.builder()
                .setWhatWasPlanned(AARSummary.NOT_CAPTURED)
                .build();

        assertEquals(0.0f, summary.computeCompletenessScore(), 0.001f);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // FACTORY METHODS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    public void empty_allFieldsAreNotCaptured() {
        AARSummary summary = AARSummary.empty();

        assertEquals(AARSummary.NOT_CAPTURED, summary.getWhatWasPlanned());
        assertEquals(AARSummary.NOT_CAPTURED, summary.getWhatHappened());
        assertEquals(AARSummary.NOT_CAPTURED, summary.getWhyItHappened());
        assertEquals(AARSummary.NOT_CAPTURED, summary.getHowToImprove());
    }

    @Test
    public void createFallback_setsRawOutputAndError() {
        AARSummary summary = AARSummary.createFallback("raw output", "parse failed");

        assertEquals("raw output", summary.getRawLlmOutput());
        assertEquals("parse failed", summary.getParseError());
    }

    @Test
    public void createFallback_parsedSuccessfullyIsFalse() {
        AARSummary summary = AARSummary.createFallback("raw", "error");
        assertFalse(summary.wasParsedSuccessfully());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // wasParsedSuccessfully
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    public void wasParsedSuccessfully_defaultIsFalse() {
        AARSummary summary = AARSummary.builder().build();
        assertFalse(summary.wasParsedSuccessfully());
    }

    @Test
    public void wasParsedSuccessfully_whenSetTrue_returnsTrue() {
        AARSummary summary = AARSummary.builder()
                .setParsedSuccessfully(true)
                .build();
        assertTrue(summary.wasParsedSuccessfully());
    }
}
