/**
 * AARSummary.java
 *
 * Data class representing a TC 7-0.1 compliant After Action Review summary.
 * Contains the four mandatory fields defined by Army doctrine.
 *
 * <p>Responsibility:
 * <ul>
 *   <li>Hold the four TC 7-0.1 AAR fields as immutable data</li>
 *   <li>Provide builder pattern for safe construction</li>
 *   <li>Provide validation for required field presence</li>
 *   <li>This class does NOT parse LLM output — that's AARJsonParser's job</li>
 * </ul>
 *
 * <p>TC 7-0.1 (February 2025) Four-Step AAR Structure:
 * <ol>
 *   <li>What was planned? — The intended plan, objective, or task</li>
 *   <li>What happened? — Factual account of what actually occurred</li>
 *   <li>Why did it happen? — Root causes explaining any differences</li>
 *   <li>How can we improve? — Specific actionable recommendations</li>
 * </ol>
 *
 * <p>Architecture Position:
 * Model layer data class. Created by AARJsonParser, consumed by SummaryFragment
 * and persisted via AARSession entity.
 *
 * <p>Thread Safety:
 * Immutable after construction — fully thread-safe.
 *
 * <p>Air-Gap Compliance:
 * N/A — pure data holder.
 *
 * @author STELLiQ Engineering
 * @version 0.1.0
 * @since ARIA Demo Build — 2026-03-02
 */
package com.stelliq.aria.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public final class AARSummary {

    /**
     * Default text when a field was not captured from the transcript.
     */
    public static final String NOT_CAPTURED = "Not captured in session.";

    @Nullable
    private final String mTitle;

    @NonNull
    private final String mWhatWasPlanned;

    @NonNull
    private final String mWhatHappened;

    @NonNull
    private final String mWhyItHappened;

    @NonNull
    private final String mHowToImprove;

    private final boolean mWasParsedSuccessfully;

    @Nullable
    private final String mRawLlmOutput;

    @Nullable
    private final String mParseError;

    /**
     * Private constructor — use Builder to create instances.
     */
    private AARSummary(@NonNull Builder builder) {
        mTitle = builder.mTitle;
        mWhatWasPlanned = builder.mWhatWasPlanned != null ? builder.mWhatWasPlanned : NOT_CAPTURED;
        mWhatHappened = builder.mWhatHappened != null ? builder.mWhatHappened : NOT_CAPTURED;
        mWhyItHappened = builder.mWhyItHappened != null ? builder.mWhyItHappened : NOT_CAPTURED;
        mHowToImprove = builder.mHowToImprove != null ? builder.mHowToImprove : NOT_CAPTURED;
        mWasParsedSuccessfully = builder.mWasParsedSuccessfully;
        mRawLlmOutput = builder.mRawLlmOutput;
        mParseError = builder.mParseError;
    }

    /**
     * Returns the LLM-generated session title.
     *
     * @return Descriptive title in Title Case, or null if not generated
     */
    @Nullable
    public String getTitle() {
        return mTitle;
    }

    /**
     * TC 7-0.1 Field 1: What was the plan, objective, or task?
     *
     * @return The intended plan, never null (may be NOT_CAPTURED placeholder)
     */
    @NonNull
    public String getWhatWasPlanned() {
        return mWhatWasPlanned;
    }

    /**
     * TC 7-0.1 Field 2: What actually happened during execution?
     *
     * @return Factual account of events, never null
     */
    @NonNull
    public String getWhatHappened() {
        return mWhatHappened;
    }

    /**
     * TC 7-0.1 Field 3: Why did it happen that way?
     *
     * @return Root cause analysis, never null
     */
    @NonNull
    public String getWhyItHappened() {
        return mWhyItHappened;
    }

    /**
     * TC 7-0.1 Field 4: How can we improve for next time?
     *
     * @return Actionable recommendations, never null
     */
    @NonNull
    public String getHowToImprove() {
        return mHowToImprove;
    }

    /**
     * Returns whether the LLM JSON output was parsed successfully.
     *
     * @return True if all four fields were extracted from valid JSON
     */
    public boolean wasParsedSuccessfully() {
        return mWasParsedSuccessfully;
    }

    /**
     * Returns the raw LLM output for debugging or fallback display.
     *
     * @return Raw LLM text, or null if not retained
     */
    @Nullable
    public String getRawLlmOutput() {
        return mRawLlmOutput;
    }

    /**
     * Returns any parse error message for debugging.
     *
     * @return Error description if parsing failed, null otherwise
     */
    @Nullable
    public String getParseError() {
        return mParseError;
    }

    /**
     * Checks if all four fields have meaningful content (not just placeholders).
     *
     * @return True if all fields contain content other than NOT_CAPTURED
     */
    public boolean hasAllFields() {
        return !NOT_CAPTURED.equals(mWhatWasPlanned)
                && !NOT_CAPTURED.equals(mWhatHappened)
                && !NOT_CAPTURED.equals(mWhyItHappened)
                && !NOT_CAPTURED.equals(mHowToImprove);
    }

    /**
     * Computes completeness score as populated field count / 4.0.
     * A field is "populated" if it contains meaningful content (not null,
     * not empty, and not the NOT_CAPTURED placeholder).
     *
     * @return Score from 0.0 (no fields) to 1.0 (all four fields populated)
     */
    public float computeCompletenessScore() {
        int populated = 0;
        if (isPopulated(mWhatWasPlanned)) populated++;
        if (isPopulated(mWhatHappened)) populated++;
        if (isPopulated(mWhyItHappened)) populated++;
        if (isPopulated(mHowToImprove)) populated++;
        return populated / 4.0f;
    }

    private static boolean isPopulated(@Nullable String field) {
        return field != null && !field.isEmpty() && !NOT_CAPTURED.equals(field);
    }

    /**
     * Builder for constructing AARSummary instances.
     */
    public static final class Builder {

        @Nullable
        private String mTitle;

        @Nullable
        private String mWhatWasPlanned;

        @Nullable
        private String mWhatHappened;

        @Nullable
        private String mWhyItHappened;

        @Nullable
        private String mHowToImprove;

        private boolean mWasParsedSuccessfully = false;

        @Nullable
        private String mRawLlmOutput;

        @Nullable
        private String mParseError;

        public Builder() {
        }

        @NonNull
        public Builder setTitle(@Nullable String title) {
            mTitle = sanitize(title);
            return this;
        }

        /** Alias for setTitle for fluent API. */
        @NonNull
        public Builder title(@Nullable String value) {
            return setTitle(value);
        }

        @NonNull
        public Builder setWhatWasPlanned(@Nullable String whatWasPlanned) {
            mWhatWasPlanned = sanitize(whatWasPlanned);
            return this;
        }

        /** Alias for setWhatWasPlanned for fluent API. */
        @NonNull
        public Builder whatWasPlanned(@Nullable String value) {
            return setWhatWasPlanned(value);
        }

        @NonNull
        public Builder setWhatHappened(@Nullable String whatHappened) {
            mWhatHappened = sanitize(whatHappened);
            return this;
        }

        /** Alias for setWhatHappened for fluent API. */
        @NonNull
        public Builder whatHappened(@Nullable String value) {
            return setWhatHappened(value);
        }

        @NonNull
        public Builder setWhyItHappened(@Nullable String whyItHappened) {
            mWhyItHappened = sanitize(whyItHappened);
            return this;
        }

        /** Alias for setWhyItHappened for fluent API. */
        @NonNull
        public Builder whyItHappened(@Nullable String value) {
            return setWhyItHappened(value);
        }

        @NonNull
        public Builder setHowToImprove(@Nullable String howToImprove) {
            mHowToImprove = sanitize(howToImprove);
            return this;
        }

        /** Alias for setHowToImprove for fluent API. */
        @NonNull
        public Builder howToImprove(@Nullable String value) {
            return setHowToImprove(value);
        }

        @NonNull
        public Builder setParsedSuccessfully(boolean success) {
            mWasParsedSuccessfully = success;
            return this;
        }

        /** Alias for setParsedSuccessfully for fluent API. */
        @NonNull
        public Builder parsedSuccessfully(boolean success) {
            return setParsedSuccessfully(success);
        }

        @NonNull
        public Builder setRawLlmOutput(@Nullable String rawOutput) {
            mRawLlmOutput = rawOutput;
            return this;
        }

        @NonNull
        public Builder setParseError(@Nullable String error) {
            mParseError = error;
            return this;
        }

        /**
         * Builds the immutable AARSummary instance.
         *
         * @return New AARSummary with all configured fields
         */
        @NonNull
        public AARSummary build() {
            return new AARSummary(this);
        }

        /**
         * Sanitizes field content by trimming whitespace and converting empty to null.
         */
        @Nullable
        private String sanitize(@Nullable String value) {
            if (value == null) {
                return null;
            }
            String trimmed = value.trim();
            return trimmed.isEmpty() ? null : trimmed;
        }
    }

    /**
     * Creates a fallback AARSummary when JSON parsing completely fails.
     *
     * @param rawOutput   The raw LLM output to preserve
     * @param parseError  Description of what went wrong
     * @return AARSummary with placeholder fields and raw output retained
     */
    @NonNull
    public static AARSummary createFallback(@NonNull String rawOutput, @NonNull String parseError) {
        return new Builder()
                .setRawLlmOutput(rawOutput)
                .setParseError(parseError)
                .setParsedSuccessfully(false)
                .build();
    }

    /**
     * Creates an empty AARSummary with all placeholder fields.
     * Used when parsing fails completely.
     *
     * @return AARSummary with all fields set to NOT_CAPTURED
     */
    @NonNull
    public static AARSummary empty() {
        return new Builder().build();
    }

    /**
     * Factory method to create a new Builder.
     * WHY: Convenience method for fluent construction.
     *
     * @return New Builder instance
     */
    @NonNull
    public static Builder builder() {
        return new Builder();
    }
}
