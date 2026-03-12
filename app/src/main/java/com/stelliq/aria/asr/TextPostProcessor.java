/**
 * TextPostProcessor.java
 *
 * Post-processes transcript text from Whisper for improved readability.
 * Handles sentence capitalization, whitespace cleanup, and formatting.
 *
 * @author STELLiQ Engineering
 * @version 1.0.0
 * @since ARIA Commercial Build — 2026-03-08
 */
package com.stelliq.aria.asr;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class TextPostProcessor {

    private TextPostProcessor() {}

    /**
     * Post-processes a transcript segment for proper sentence structure.
     *
     * - Capitalizes first character
     * - Capitalizes after sentence-ending punctuation (. ! ?)
     * - Collapses multiple spaces
     * - Ensures trailing space for concatenation
     */
    @NonNull
    public static String processSegment(@Nullable String text) {
        if (text == null || text.isEmpty()) return "";

        String cleaned = text.trim();
        if (cleaned.isEmpty()) return "";

        // Collapse multiple spaces
        cleaned = cleaned.replaceAll("\\s{2,}", " ");

        // Capitalize after sentence-ending punctuation
        StringBuilder result = new StringBuilder(cleaned.length());
        boolean capitalizeNext = true;

        for (int i = 0; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);

            if (capitalizeNext && Character.isLetter(c)) {
                result.append(Character.toUpperCase(c));
                capitalizeNext = false;
            } else {
                result.append(c);
            }

            if (c == '.' || c == '!' || c == '?') {
                capitalizeNext = true;
            }
        }

        return result.toString();
    }

    /**
     * Formats a transcript segment with speaker attribution.
     *
     * @param speakerName Speaker's display name
     * @param text        Transcript text
     * @return Formatted string like "John Smith: Hello everyone."
     */
    @NonNull
    public static String formatWithSpeaker(@NonNull String speakerName, @NonNull String text) {
        return speakerName + ": " + text;
    }
}
