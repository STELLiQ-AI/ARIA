/**
 * AARPromptBuilderTest.java
 *
 * Unit tests for AARPromptBuilder Llama 3.2 Instruct prompt construction.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Prompt structure with Llama 3.2 Instruct tokens</li>
 *   <li>Transcript injection</li>
 *   <li>Custom system prompts</li>
 *   <li>Token estimation</li>
 *   <li>Context window fitting</li>
 *   <li>Truncation behavior</li>
 * </ul>
 *
 * @author STELLiQ Engineering
 * @version 0.1.0
 * @since ARIA Demo Build — 2026-03-02
 */
package com.stelliq.aria.llm;

import static org.junit.Assert.*;

import org.junit.Test;

public class AARPromptBuilderTest {

    // ═══════════════════════════════════════════════════════════════════════════
    // PROMPT STRUCTURE TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    public void build_containsLlama32Tokens() {
        // Act
        String prompt = AARPromptBuilder.build("Test transcript");

        // Assert — Llama 3.2 Instruct format tokens
        assertTrue("Should contain system header", prompt.contains("<|start_header_id|>system<|end_header_id|>"));
        assertTrue("Should contain user header", prompt.contains("<|start_header_id|>user<|end_header_id|>"));
        assertTrue("Should contain assistant header", prompt.contains("<|start_header_id|>assistant<|end_header_id|>"));
        assertTrue("Should contain end-of-turn tokens", prompt.contains("<|eot_id|>"));
    }

    @Test
    public void build_containsSystemPrompt() {
        // Act
        String prompt = AARPromptBuilder.build("Test transcript");

        // Assert — SummaryTemplate.RETROSPECTIVE system prompt
        assertTrue("Should contain meeting assistant role", prompt.contains("meeting analysis assistant"));
        assertTrue("Should contain JSON field names", prompt.contains("what_was_planned"));
        assertTrue("Should contain JSON output instruction", prompt.contains("JSON"));
    }

    @Test
    public void build_containsTranscript() {
        // Arrange
        String transcript = "Alpha team moved to objective at 0800 hours.";

        // Act
        String prompt = AARPromptBuilder.build(transcript);

        // Assert
        assertTrue("Should contain transcript", prompt.contains(transcript));
    }

    @Test
    public void build_endsWithAssistantToken() {
        // Act
        String prompt = AARPromptBuilder.build("Test");

        // Assert
        // WHY: Prompt ends with assistant header + JSON prefix to force JSON output.
        // The "{\n" primes the model to generate JSON immediately, preventing prose output.
        assertTrue("Should end with assistant header and JSON prefix",
                prompt.endsWith("<|start_header_id|>assistant<|end_header_id|>\n\n{\n"));
    }

    @Test
    public void build_correctTokenOrder() {
        // Act
        String prompt = AARPromptBuilder.build("Test");

        // Assert — Llama 3.2 header order: system → user → assistant
        int systemPos = prompt.indexOf("<|start_header_id|>system<|end_header_id|>");
        int userPos = prompt.indexOf("<|start_header_id|>user<|end_header_id|>");
        int assistantPos = prompt.indexOf("<|start_header_id|>assistant<|end_header_id|>");

        assertTrue("System should come before user", systemPos < userPos);
        assertTrue("User should come before assistant", userPos < assistantPos);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CUSTOM PROMPT TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    public void buildCustom_usesCustomSystemPrompt() {
        // Arrange
        String customPrompt = "You are a custom assistant.";
        String transcript = "Test transcript";

        // Act
        String prompt = AARPromptBuilder.buildCustom(transcript, customPrompt);

        // Assert
        assertTrue("Should contain custom prompt", prompt.contains(customPrompt));
        assertTrue("Should contain transcript", prompt.contains(transcript));
    }

    @Test
    public void buildCustom_maintainsLlama32Format() {
        // Act
        String prompt = AARPromptBuilder.buildCustom("Test", "Custom system");

        // Assert — Llama 3.2 Instruct format
        assertTrue("Should have system header", prompt.contains("<|start_header_id|>system<|end_header_id|>"));
        assertTrue("Should have user header", prompt.contains("<|start_header_id|>user<|end_header_id|>"));
        assertTrue("Should have assistant header", prompt.contains("<|start_header_id|>assistant<|end_header_id|>"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TOKEN ESTIMATION TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    public void estimateTokens_shortText_reasonable() {
        // Arrange
        String text = "Hello world";  // 11 chars

        // Act
        int estimate = AARPromptBuilder.estimateTokens(text);

        // Assert
        // WHY: ~4 chars per token, so 11 chars ≈ 2-3 tokens
        assertTrue("Estimate should be positive", estimate > 0);
        assertTrue("Estimate should be reasonable", estimate <= 5);
    }

    @Test
    public void estimateTokens_emptyString_returnsZero() {
        assertEquals(0, AARPromptBuilder.estimateTokens(""));
    }

    @Test
    public void estimateTokens_longText_scalesLinearly() {
        // Arrange
        String short100 = "a".repeat(100);
        String long400 = "a".repeat(400);

        // Act
        int shortEstimate = AARPromptBuilder.estimateTokens(short100);
        int longEstimate = AARPromptBuilder.estimateTokens(long400);

        // Assert
        // WHY: 4x text should be ~4x tokens
        assertEquals(4 * shortEstimate, longEstimate);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CONTEXT WINDOW TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    public void fitsInContext_shortTranscript_returnsTrue() {
        // Arrange
        String shortTranscript = "Brief mission debrief.";

        // Act & Assert
        assertTrue(AARPromptBuilder.fitsInContext(shortTranscript));
    }

    @Test
    public void fitsInContext_veryLongTranscript_returnsFalse() {
        // Arrange
        // WHY: Create transcript that exceeds context window
        // Context is typically 4096 tokens, so ~16K chars would exceed it
        StringBuilder longTranscript = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            longTranscript.append("This is a very long transcript segment. ");
        }

        // Act & Assert
        assertFalse(AARPromptBuilder.fitsInContext(longTranscript.toString()));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TRUNCATION TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    public void truncateToFit_shortTranscript_unchanged() {
        // Arrange
        String shortTranscript = "Brief mission debrief.";

        // Act
        String result = AARPromptBuilder.truncateToFit(shortTranscript);

        // Assert
        assertEquals(shortTranscript, result);
    }

    @Test
    public void truncateToFit_longTranscript_truncated() {
        // Arrange
        StringBuilder longTranscript = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            longTranscript.append("This is a very long transcript segment. ");
        }

        // Act
        String result = AARPromptBuilder.truncateToFit(longTranscript.toString());

        // Assert
        assertTrue("Should be shorter", result.length() < longTranscript.length());
        assertTrue("Should have truncation indicator", result.contains("[Transcript truncated"));
    }

    @Test
    public void truncateToFit_truncatedResultFits() {
        // Arrange
        StringBuilder longTranscript = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            longTranscript.append("This is a very long transcript segment. ");
        }

        // Act
        String result = AARPromptBuilder.truncateToFit(longTranscript.toString());

        // Assert
        assertTrue("Truncated result should fit", AARPromptBuilder.fitsInContext(result));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SYSTEM PROMPT ACCESSOR TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    public void getSystemPrompt_returnsNonEmpty() {
        // Act
        String systemPrompt = AARPromptBuilder.getSystemPrompt();

        // Assert
        assertNotNull(systemPrompt);
        assertFalse(systemPrompt.isEmpty());
    }

    @Test
    public void getSystemPrompt_containsAARFields() {
        // Act
        String systemPrompt = AARPromptBuilder.getSystemPrompt();

        // Assert — SummaryTemplate.RETROSPECTIVE uses JSON field names
        assertTrue("Should mention what_was_planned", systemPrompt.contains("what_was_planned"));
        assertTrue("Should mention what_happened", systemPrompt.contains("what_happened"));
        assertTrue("Should mention why_it_happened", systemPrompt.contains("why_it_happened"));
        assertTrue("Should mention how_to_improve", systemPrompt.contains("how_to_improve"));
    }

    @Test
    public void getSystemPrompt_specifiesJsonOutput() {
        // Act
        String systemPrompt = AARPromptBuilder.getSystemPrompt();

        // Assert
        assertTrue("Should specify JSON format", systemPrompt.contains("JSON"));
        assertTrue("Should show field names", systemPrompt.contains("what_was_planned"));
        assertTrue("Should show field names", systemPrompt.contains("what_happened"));
        assertTrue("Should show field names", systemPrompt.contains("why_it_happened"));
        assertTrue("Should show field names", systemPrompt.contains("how_to_improve"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EDGE CASE TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    public void build_emptyTranscript_stillValid() {
        // Act
        String prompt = AARPromptBuilder.build("");

        // Assert — Llama 3.2 Instruct format
        assertNotNull(prompt);
        assertTrue(prompt.contains("<|start_header_id|>system<|end_header_id|>"));
        assertTrue(prompt.contains("<|start_header_id|>user<|end_header_id|>"));
        assertTrue(prompt.contains("<|start_header_id|>assistant<|end_header_id|>"));
    }

    @Test
    public void build_transcriptWithSpecialChars_preserved() {
        // Arrange
        String transcript = "Alpha team: 100% mission success! <important>";

        // Act
        String prompt = AARPromptBuilder.build(transcript);

        // Assert
        assertTrue("Should preserve special characters", prompt.contains(transcript));
    }

    @Test
    public void build_transcriptWithNewlines_preserved() {
        // Arrange
        String transcript = "Line 1\nLine 2\nLine 3";

        // Act
        String prompt = AARPromptBuilder.build(transcript);

        // Assert
        assertTrue("Should preserve newlines", prompt.contains(transcript));
    }
}
