/**
 * AARJsonParserTest.java
 *
 * Unit tests for AARJsonParser JSON parsing strategies.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Well-formed JSON parsing</li>
 *   <li>JSON extraction from surrounding text</li>
 *   <li>Malformed JSON handling</li>
 *   <li>Partial field extraction</li>
 *   <li>Empty and null input handling</li>
 * </ul>
 *
 * @author STELLiQ Engineering
 * @version 0.1.0
 * @since ARIA Demo Build — 2026-03-02
 */
package com.stelliq.aria.llm;

import static org.junit.Assert.*;

import com.stelliq.aria.model.AARSummary;

import org.junit.Test;

public class AARJsonParserTest {

    // ═══════════════════════════════════════════════════════════════════════════
    // WELL-FORMED JSON TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    public void parse_wellFormedJson_success() {
        // Arrange
        String json = "{"
                + "\"what_was_planned\": \"Test mission to secure objective.\","
                + "\"what_happened\": \"Team successfully secured objective.\","
                + "\"why_it_happened\": \"Good coordination and communication.\","
                + "\"how_to_improve\": \"Maintain current practices.\""
                + "}";

        // Act
        AARJsonParser.ParseResult result = AARJsonParser.parse(json);

        // Assert
        assertTrue("Parse should succeed", result.success);
        assertNull("Error should be null", result.error);
        assertEquals("Test mission to secure objective.", result.summary.getWhatWasPlanned());
        assertEquals("Team successfully secured objective.", result.summary.getWhatHappened());
        assertEquals("Good coordination and communication.", result.summary.getWhyItHappened());
        assertEquals("Maintain current practices.", result.summary.getHowToImprove());
        assertTrue("Summary should be marked as parsed successfully", result.summary.wasParsedSuccessfully());
    }

    @Test
    public void parse_wellFormedJsonWithWhitespace_success() {
        // Arrange
        String json = "{\n"
                + "  \"what_was_planned\": \"Plan A\",\n"
                + "  \"what_happened\": \"Result A\",\n"
                + "  \"why_it_happened\": \"Reason A\",\n"
                + "  \"how_to_improve\": \"Improve A\"\n"
                + "}";

        // Act
        AARJsonParser.ParseResult result = AARJsonParser.parse(json);

        // Assert
        assertTrue("Parse should succeed", result.success);
        assertEquals("Plan A", result.summary.getWhatWasPlanned());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // JSON EXTRACTION TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    public void parse_jsonWithPrefixText_extractsJson() {
        // Arrange
        // WHY: LLMs often add preamble before JSON
        String input = "Here is the summary:\n"
                + "{"
                + "\"what_was_planned\": \"Plan B\","
                + "\"what_happened\": \"Result B\","
                + "\"why_it_happened\": \"Reason B\","
                + "\"how_to_improve\": \"Improve B\""
                + "}";

        // Act
        AARJsonParser.ParseResult result = AARJsonParser.parse(input);

        // Assert
        assertTrue("Parse should succeed", result.success);
        assertEquals("Plan B", result.summary.getWhatWasPlanned());
    }

    @Test
    public void parse_jsonWithSuffixText_extractsJson() {
        // Arrange
        // WHY: LLMs sometimes add explanation after JSON
        String input = "{"
                + "\"what_was_planned\": \"Plan C\","
                + "\"what_happened\": \"Result C\","
                + "\"why_it_happened\": \"Reason C\","
                + "\"how_to_improve\": \"Improve C\""
                + "}\n\nI hope this helps!";

        // Act
        AARJsonParser.ParseResult result = AARJsonParser.parse(input);

        // Assert
        assertTrue("Parse should succeed", result.success);
        assertEquals("Plan C", result.summary.getWhatWasPlanned());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MALFORMED JSON TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    public void parse_missingFields_partialSuccess() {
        // Arrange
        String json = "{"
                + "\"what_was_planned\": \"Plan D\","
                + "\"what_happened\": \"Result D\""
                + "}";

        // Act
        AARJsonParser.ParseResult result = AARJsonParser.parse(json);

        // Assert
        assertFalse("Parse should not fully succeed", result.success);
        assertNotNull("Error should not be null", result.error);
        assertEquals("Plan D", result.summary.getWhatWasPlanned());
        assertEquals("Result D", result.summary.getWhatHappened());
    }

    @Test
    public void parse_unquotedKeys_lenientParseFallback() {
        // Arrange
        // WHY: Some LLMs output unquoted keys
        String input = "{"
                + "what_was_planned: \"Plan E\","
                + "what_happened: \"Result E\","
                + "why_it_happened: \"Reason E\","
                + "how_to_improve: \"Improve E\""
                + "}";

        // Act
        AARJsonParser.ParseResult result = AARJsonParser.parse(input);

        // Assert
        // WHY: Lenient parser should extract fields via regex
        assertTrue("Lenient parse should extract fields", result.success);
        assertEquals("Plan E", result.summary.getWhatWasPlanned());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EMPTY/NULL INPUT TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    public void parse_emptyString_failure() {
        // Act
        AARJsonParser.ParseResult result = AARJsonParser.parse("");

        // Assert
        assertFalse("Parse should fail", result.success);
        assertNotNull("Error should not be null", result.error);
    }

    @Test
    public void parse_whitespaceOnly_failure() {
        // Act
        AARJsonParser.ParseResult result = AARJsonParser.parse("   \n\t  ");

        // Assert
        assertFalse("Parse should fail", result.success);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ESCAPE SEQUENCE TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    public void parse_escapedNewlines_unescapesCorrectly() {
        // Arrange
        String json = "{"
                + "\"what_was_planned\": \"Line 1\\nLine 2\","
                + "\"what_happened\": \"Result\","
                + "\"why_it_happened\": \"Reason\","
                + "\"how_to_improve\": \"Improve\""
                + "}";

        // Act
        AARJsonParser.ParseResult result = AARJsonParser.parse(json);

        // Assert
        assertTrue("Parse should succeed", result.success);
        assertEquals("Line 1\nLine 2", result.summary.getWhatWasPlanned());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // isComplete() TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    public void isComplete_allFieldsPresent_returnsTrue() {
        // Arrange
        AARSummary summary = AARSummary.builder()
                .whatWasPlanned("Plan")
                .whatHappened("Result")
                .whyItHappened("Reason")
                .howToImprove("Improve")
                .build();

        // Act & Assert
        assertTrue(AARJsonParser.isComplete(summary));
    }

    @Test
    public void isComplete_missingField_returnsFalse() {
        // Arrange
        AARSummary summary = AARSummary.builder()
                .whatWasPlanned("Plan")
                .whatHappened("Result")
                .build();

        // Act & Assert
        assertFalse(AARJsonParser.isComplete(summary));
    }

    @Test
    public void isComplete_emptyField_returnsFalse() {
        // Arrange
        AARSummary summary = AARSummary.builder()
                .whatWasPlanned("")
                .whatHappened("Result")
                .whyItHappened("Reason")
                .howToImprove("Improve")
                .build();

        // Act & Assert
        assertFalse(AARJsonParser.isComplete(summary));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // UNICODE CHARACTER TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    public void parse_unicodeCharacters_preserved() {
        // Arrange - multiple languages
        String json = "{"
                + "\"what_was_planned\": \"Misión en área alpha\","
                + "\"what_happened\": \"Équipe arrivée à l'objectif\","
                + "\"why_it_happened\": \"天气很好\","
                + "\"how_to_improve\": \"Улучшить координацию\""
                + "}";

        // Act
        AARJsonParser.ParseResult result = AARJsonParser.parse(json);

        // Assert
        assertTrue("Should handle unicode", result.success);
        assertTrue("Should preserve Spanish", result.summary.getWhatWasPlanned().contains("Misión"));
        assertTrue("Should preserve French", result.summary.getWhatHappened().contains("Équipe"));
        assertTrue("Should preserve Chinese", result.summary.getWhyItHappened().contains("天气"));
        assertTrue("Should preserve Russian", result.summary.getHowToImprove().contains("Улучшить"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TRUNCATED JSON TESTS (Token limit simulation)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    public void parse_truncatedJson_extractsPartialFields() {
        // Arrange - simulates token limit truncation mid-output
        String input = "{"
                + "\"what_was_planned\": \"Plan F\","
                + "\"what_happened\": \"Result F\","
                + "\"why_it_happened\": \"Reason F\","
                + "\"how_to_impr";  // Truncated!

        // Act
        AARJsonParser.ParseResult result = AARJsonParser.parse(input);

        // Assert
        assertFalse("Parse should not fully succeed", result.success);
        // WHY: Lenient parser should still extract complete fields
        assertEquals("Plan F", result.summary.getWhatWasPlanned());
        assertEquals("Result F", result.summary.getWhatHappened());
        assertEquals("Reason F", result.summary.getWhyItHappened());
    }

    @Test
    public void parse_truncatedMidKey_failsGracefully() {
        // Arrange - truncation in the middle of a key
        String input = "{\"what_was_pla";

        // Act
        AARJsonParser.ParseResult result = AARJsonParser.parse(input);

        // Assert
        assertFalse("Parse should fail", result.success);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ESCAPE SEQUENCE TESTS (Additional)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    public void parse_escapedQuotes_unescapesCorrectly() {
        // Arrange
        String json = "{"
                + "\"what_was_planned\": \"He said \\\"Go now!\\\" loudly\","
                + "\"what_happened\": \"Result\","
                + "\"why_it_happened\": \"Reason\","
                + "\"how_to_improve\": \"Improve\""
                + "}";

        // Act
        AARJsonParser.ParseResult result = AARJsonParser.parse(json);

        // Assert
        assertTrue("Parse should succeed", result.success);
        assertTrue("Should contain unescaped quotes",
                result.summary.getWhatWasPlanned().contains("\"Go now!\""));
    }

    @Test
    public void parse_escapedTabs_unescapesCorrectly() {
        // Arrange
        String json = "{"
                + "\"what_was_planned\": \"Column1\\tColumn2\","
                + "\"what_happened\": \"Result\","
                + "\"why_it_happened\": \"Reason\","
                + "\"how_to_improve\": \"Improve\""
                + "}";

        // Act
        AARJsonParser.ParseResult result = AARJsonParser.parse(json);

        // Assert
        assertTrue("Parse should succeed", result.success);
        assertEquals("Column1\tColumn2", result.summary.getWhatWasPlanned());
    }

    @Test
    public void parse_escapedBackslash_unescapesCorrectly() {
        // Arrange
        String json = "{"
                + "\"what_was_planned\": \"Path: C:\\\\Users\\\\test\","
                + "\"what_happened\": \"Result\","
                + "\"why_it_happened\": \"Reason\","
                + "\"how_to_improve\": \"Improve\""
                + "}";

        // Act
        AARJsonParser.ParseResult result = AARJsonParser.parse(json);

        // Assert
        assertTrue("Parse should succeed", result.success);
        assertTrue("Should contain backslash",
                result.summary.getWhatWasPlanned().contains("\\Users\\"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TRAILING COMMA TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    public void parse_trailingComma_handledByLenientParse() {
        // Arrange - common LLM mistake
        String input = "{"
                + "\"what_was_planned\": \"Plan G\","
                + "\"what_happened\": \"Result G\","
                + "\"why_it_happened\": \"Reason G\","
                + "\"how_to_improve\": \"Improve G\","  // Trailing comma
                + "}";

        // Act
        AARJsonParser.ParseResult result = AARJsonParser.parse(input);

        // Assert
        // WHY: Strict JSON fails, but lenient extraction should work
        assertTrue("Should handle via lenient parsing", result.success);
        assertEquals("Plan G", result.summary.getWhatWasPlanned());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LONG VALUE TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    public void parse_veryLongValues_succeeds() {
        // Arrange - simulate verbose LLM output
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            longText.append("This is a very detailed explanation. ");
        }

        String json = "{"
                + "\"what_was_planned\": \"" + longText + "\","
                + "\"what_happened\": \"Result\","
                + "\"why_it_happened\": \"Reason\","
                + "\"how_to_improve\": \"Improve\""
                + "}";

        // Act
        AARJsonParser.ParseResult result = AARJsonParser.parse(json);

        // Assert
        assertTrue("Should handle long values", result.success);
        assertTrue("Should preserve long text", result.summary.getWhatWasPlanned().length() > 1000);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CODE FENCE TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    public void parse_jsonInCodeFence_extracts() {
        // Arrange - LLM might wrap in markdown code fences
        String input = "Here's the AAR:\n\n```json\n"
                + "{"
                + "\"what_was_planned\": \"Plan H\","
                + "\"what_happened\": \"Result H\","
                + "\"why_it_happened\": \"Reason H\","
                + "\"how_to_improve\": \"Improve H\""
                + "}\n```";

        // Act
        AARJsonParser.ParseResult result = AARJsonParser.parse(input);

        // Assert
        assertTrue("Should extract from code fence", result.success);
        assertEquals("Plan H", result.summary.getWhatWasPlanned());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // NESTED OBJECT TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    public void parse_withExtraFields_ignoresExtras() {
        // Arrange - LLM might add unexpected fields
        String json = "{"
                + "\"what_was_planned\": \"Plan I\","
                + "\"what_happened\": \"Result I\","
                + "\"why_it_happened\": \"Reason I\","
                + "\"how_to_improve\": \"Improve I\","
                + "\"extra_field\": \"Should be ignored\","
                + "\"metadata\": {\"timestamp\": \"2024-01-01\"}"
                + "}";

        // Act
        AARJsonParser.ParseResult result = AARJsonParser.parse(json);

        // Assert
        assertTrue("Should parse top-level fields", result.success);
        assertEquals("Plan I", result.summary.getWhatWasPlanned());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SPECIAL CHARACTER TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    public void parse_specialCharactersInValues_preserved() {
        // Arrange
        String json = "{"
                + "\"what_was_planned\": \"Plan with {braces} and [brackets]\","
                + "\"what_happened\": \"Event with : colon and ; semicolon\","
                + "\"why_it_happened\": \"Because of < and > symbols\","
                + "\"how_to_improve\": \"Use @ and # more\""
                + "}";

        // Act
        AARJsonParser.ParseResult result = AARJsonParser.parse(json);

        // Assert
        assertTrue("Should handle special chars", result.success);
        assertTrue("Should preserve braces",
                result.summary.getWhatWasPlanned().contains("{braces}"));
        assertTrue("Should preserve colons",
                result.summary.getWhatHappened().contains(":"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RAW JSON PRESERVATION TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    public void parse_preservesRawJson() {
        // Arrange
        String json = "{"
                + "\"what_was_planned\": \"Plan\","
                + "\"what_happened\": \"Result\","
                + "\"why_it_happened\": \"Reason\","
                + "\"how_to_improve\": \"Improve\""
                + "}";

        // Act
        AARJsonParser.ParseResult result = AARJsonParser.parse(json);

        // Assert
        assertNotNull("Should preserve raw JSON", result.rawJson);
        assertTrue("Raw JSON should contain original content",
                result.rawJson.contains("what_was_planned"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // NO JSON TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    public void parse_plainTextNoJson_fails() {
        // Arrange
        String input = "This is just plain text without any JSON structure at all.";

        // Act
        AARJsonParser.ParseResult result = AARJsonParser.parse(input);

        // Assert
        assertFalse("Should fail on non-JSON text", result.success);
    }
}
