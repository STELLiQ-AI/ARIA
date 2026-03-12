/**
 * AARJsonParser.java
 *
 * Parses JSON output from Llama 3.1 8B Instruct into AARSummary objects.
 * Handles malformed JSON with fallback extraction strategies.
 *
 * <p>Responsibility:
 * <ul>
 *   <li>Parse well-formed JSON into AARSummary</li>
 *   <li>Handle malformed JSON (missing quotes, trailing commas, etc.)</li>
 *   <li>Extract fields from partially-formed responses</li>
 *   <li>Provide informative error messages for debugging</li>
 * </ul>
 *
 * <p>Architecture Position:
 * LLM layer output parser. Called after LlamaEngine.complete() finishes.
 *
 * <p>Thread Safety:
 * Stateless parser. Thread-safe.
 *
 * <p>RISK-05: Llama 3.1 8B may output malformed JSON including:
 * <ul>
 *   <li>Unquoted keys</li>
 *   <li>Missing commas</li>
 *   <li>Trailing commas</li>
 *   <li>Extra text before/after JSON</li>
 *   <li>Incomplete responses (token limit)</li>
 * </ul>
 *
 * <p>Strategy: Try strict JSON first, then progressively lenient parsing.
 *
 * @author STELLiQ Engineering
 * @version 0.1.0
 * @since ARIA Demo Build — 2026-03-02
 */
package com.stelliq.aria.llm;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.stelliq.aria.model.AARSummary;
import com.stelliq.aria.util.Constants;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AARJsonParser {

    private static final String TAG = Constants.LOG_TAG_LLM;

    // WHY: JSON field names matching prompt specification
    private static final String KEY_TITLE = "title";
    private static final String KEY_WHAT_PLANNED = "what_was_planned";
    private static final String KEY_WHAT_HAPPENED = "what_happened";
    private static final String KEY_WHY_HAPPENED = "why_it_happened";
    private static final String KEY_HOW_IMPROVE = "how_to_improve";

    // WHY: Regex patterns for fallback extraction
    private static final Pattern JSON_OBJECT_PATTERN =
            Pattern.compile("\\{[^{}]*\\}", Pattern.DOTALL);

    private static final Pattern FIELD_PATTERN =
            Pattern.compile("\"?(\\w+)\"?\\s*:\\s*\"([^\"]*?)\"", Pattern.DOTALL);

    /**
     * Result of parsing attempt.
     */
    public static class ParseResult {
        @NonNull
        public final AARSummary summary;
        public final boolean success;
        @Nullable
        public final String error;
        @NonNull
        public final String rawJson;

        private ParseResult(@NonNull AARSummary summary, boolean success,
                            @Nullable String error, @NonNull String rawJson) {
            this.summary = summary;
            this.success = success;
            this.error = error;
            this.rawJson = rawJson;
        }

        static ParseResult success(@NonNull AARSummary summary, @NonNull String rawJson) {
            return new ParseResult(summary, true, null, rawJson);
        }

        static ParseResult failure(@NonNull AARSummary summary, @NonNull String error,
                                   @NonNull String rawJson) {
            return new ParseResult(summary, false, error, rawJson);
        }
    }

    /**
     * Private constructor — use static parse() method.
     */
    private AARJsonParser() {
    }

    /**
     * Parses LLM output into AARSummary.
     *
     * <p>Attempts parsing in order:
     * <ol>
     *   <li>Strict JSON parsing</li>
     *   <li>JSON extraction from surrounding text</li>
     *   <li>Lenient field extraction via regex</li>
     * </ol>
     *
     * @param llmOutput Raw output from LlamaEngine
     * @return ParseResult with summary and status
     */
    @NonNull
    public static ParseResult parse(@NonNull String llmOutput) {
        if (llmOutput.isEmpty()) {
            Log.e(TAG, "[AARJsonParser.parse] Empty LLM output");
            return ParseResult.failure(
                    AARSummary.empty(),
                    "Empty LLM output",
                    ""
            );
        }

        String trimmed = llmOutput.trim();
        Log.d(TAG, "[AARJsonParser.parse] Parsing output (" + trimmed.length() + " chars)");

        // WHY: Strategy 1 — Try strict JSON parsing
        ParseResult strictResult = tryStrictParse(trimmed);
        if (strictResult.success) {
            Log.i(TAG, "[AARJsonParser.parse] Strict JSON parse succeeded");
            return strictResult;
        }

        // WHY: Strategy 2 — Extract JSON object from surrounding text
        ParseResult extractedResult = tryExtractJson(trimmed);
        if (extractedResult.success) {
            Log.i(TAG, "[AARJsonParser.parse] JSON extraction succeeded");
            return extractedResult;
        }

        // WHY: Strategy 3 — Lenient regex-based field extraction
        ParseResult lenientResult = tryLenientParse(trimmed);
        if (lenientResult.success) {
            Log.i(TAG, "[AARJsonParser.parse] Lenient parse succeeded");
            return lenientResult;
        }

        // WHY: All strategies failed — return partial result
        Log.w(TAG, "[AARJsonParser.parse] All parse strategies failed");
        return lenientResult;  // Return last attempt with partial data
    }

    /**
     * Attempts strict JSON parsing.
     */
    @NonNull
    private static ParseResult tryStrictParse(@NonNull String input) {
        try {
            JSONObject json = new JSONObject(input);
            return parseJsonObject(json, input);
        } catch (JSONException e) {
            Log.d(TAG, "[AARJsonParser.tryStrictParse] Failed: " + e.getMessage());
            return ParseResult.failure(AARSummary.empty(), e.getMessage(), input);
        }
    }

    /**
     * Attempts to extract JSON object from surrounding text.
     */
    @NonNull
    private static ParseResult tryExtractJson(@NonNull String input) {
        // WHY: Find JSON object boundaries
        int startBrace = input.indexOf('{');
        int endBrace = input.lastIndexOf('}');

        if (startBrace >= 0 && endBrace > startBrace) {
            String jsonCandidate = input.substring(startBrace, endBrace + 1);
            try {
                JSONObject json = new JSONObject(jsonCandidate);
                return parseJsonObject(json, jsonCandidate);
            } catch (JSONException e) {
                Log.d(TAG, "[AARJsonParser.tryExtractJson] Failed: " + e.getMessage());
            }
        }

        return ParseResult.failure(AARSummary.empty(), "No valid JSON found", input);
    }

    /**
     * Attempts lenient regex-based parsing.
     */
    @NonNull
    private static ParseResult tryLenientParse(@NonNull String input) {
        AARSummary.Builder builder = AARSummary.builder();
        int fieldsFound = 0;

        // Title is optional — doesn't count toward required field count
        String title = extractField(input, KEY_TITLE);
        if (title != null) {
            builder.title(title);
        }

        // WHY: Extract each field using regex
        String whatPlanned = extractField(input, KEY_WHAT_PLANNED);
        if (whatPlanned != null) {
            builder.whatWasPlanned(whatPlanned);
            fieldsFound++;
        }

        String whatHappened = extractField(input, KEY_WHAT_HAPPENED);
        if (whatHappened != null) {
            builder.whatHappened(whatHappened);
            fieldsFound++;
        }

        String whyHappened = extractField(input, KEY_WHY_HAPPENED);
        if (whyHappened != null) {
            builder.whyItHappened(whyHappened);
            fieldsFound++;
        }

        String howImprove = extractField(input, KEY_HOW_IMPROVE);
        if (howImprove != null) {
            builder.howToImprove(howImprove);
            fieldsFound++;
        }

        builder.parsedSuccessfully(fieldsFound == 4);

        if (fieldsFound > 0) {
            Log.d(TAG, "[AARJsonParser.tryLenientParse] Extracted " + fieldsFound + "/4 fields");
            return new ParseResult(
                    builder.build(),
                    fieldsFound == 4,
                    fieldsFound < 4 ? "Only " + fieldsFound + "/4 fields extracted" : null,
                    input
            );
        }

        return ParseResult.failure(builder.build(), "No fields could be extracted", input);
    }

    /**
     * Parses a JSONObject into AARSummary.
     */
    @NonNull
    private static ParseResult parseJsonObject(@NonNull JSONObject json, @NonNull String rawJson) {
        AARSummary.Builder builder = AARSummary.builder();
        int fieldsFound = 0;

        // Title is optional — doesn't count toward required field count
        if (json.has(KEY_TITLE)) {
            builder.title(json.optString(KEY_TITLE, ""));
        }

        if (json.has(KEY_WHAT_PLANNED)) {
            builder.whatWasPlanned(json.optString(KEY_WHAT_PLANNED, ""));
            fieldsFound++;
        }

        if (json.has(KEY_WHAT_HAPPENED)) {
            builder.whatHappened(json.optString(KEY_WHAT_HAPPENED, ""));
            fieldsFound++;
        }

        if (json.has(KEY_WHY_HAPPENED)) {
            builder.whyItHappened(json.optString(KEY_WHY_HAPPENED, ""));
            fieldsFound++;
        }

        if (json.has(KEY_HOW_IMPROVE)) {
            builder.howToImprove(json.optString(KEY_HOW_IMPROVE, ""));
            fieldsFound++;
        }

        builder.parsedSuccessfully(fieldsFound == 4);

        if (fieldsFound == 4) {
            return ParseResult.success(builder.build(), rawJson);
        } else {
            return new ParseResult(
                    builder.build(),
                    false,
                    "Only " + fieldsFound + "/4 fields found",
                    rawJson
            );
        }
    }

    /**
     * Extracts a field value using regex.
     *
     * <p>Handles various formats:
     * <ul>
     *   <li>"key": "value"</li>
     *   <li>key: "value"</li>
     *   <li>"key":"value"</li>
     * </ul>
     */
    @Nullable
    private static String extractField(@NonNull String input, @NonNull String fieldName) {
        // WHY: Build pattern for this specific field.
        // The value group handles escaped characters: [^"\\]+ matches runs of normal chars,
        // \\. matches any escape sequence (e.g., \", \\, \n).
        // Possessive quantifiers (++) prevent catastrophic backtracking on long values.
        String patternStr = "\"?" + Pattern.quote(fieldName) + "\"?\\s*:\\s*\"((?:[^\"\\\\]++|\\\\.)*)\"";
        Pattern pattern = Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(input);

        if (matcher.find()) {
            String value = matcher.group(1);
            if (value != null && !value.isEmpty()) {
                return unescapeJson(value);
            }
        }

        // WHY: Fallback for unquoted values — smaller LLMs (e.g., 1B) sometimes omit
        // quotes around string values in JSON output. Match value from after the colon
        // to the next field boundary (comma + newline + next key, or closing brace).
        String unquotedStr = "\"?" + Pattern.quote(fieldName) + "\"?\\s*:\\s*([^\"\\n{\\[][^\\n]*?)\\s*,?\\s*(?:\\n|$)";
        Pattern unquotedPattern = Pattern.compile(unquotedStr, Pattern.CASE_INSENSITIVE);
        Matcher unquotedMatcher = unquotedPattern.matcher(input);

        if (unquotedMatcher.find()) {
            String value = unquotedMatcher.group(1);
            if (value != null && !value.trim().isEmpty()) {
                Log.d(TAG, "[AARJsonParser.extractField] Unquoted fallback matched for: " + fieldName);
                return value.trim();
            }
        }

        return null;
    }

    /**
     * Unescapes JSON string escape sequences.
     */
    @NonNull
    private static String unescapeJson(@NonNull String input) {
        return input
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }

    /**
     * Validates that all required fields are present and non-empty.
     *
     * @param summary The AARSummary to validate
     * @return True if all fields are present and non-empty
     */
    public static boolean isComplete(@NonNull AARSummary summary) {
        return !isEmpty(summary.getWhatWasPlanned())
                && !isEmpty(summary.getWhatHappened())
                && !isEmpty(summary.getWhyItHappened())
                && !isEmpty(summary.getHowToImprove());
    }

    private static boolean isEmpty(@Nullable String str) {
        return str == null || str.trim().isEmpty() || AARSummary.NOT_CAPTURED.equals(str);
    }
}
