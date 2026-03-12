/**
 * TranscriptAccumulator.java
 *
 * Accumulates transcript segments from streaming ASR into full transcript.
 * Handles deduplication and segment tracking for database storage.
 *
 * <p>Responsibility:
 * <ul>
 *   <li>Accumulate transcript segments with timing information</li>
 *   <li>Deduplicate overlapping content from sliding window</li>
 *   <li>Track segment indices for database storage</li>
 *   <li>Provide full transcript for LLM summarization</li>
 * </ul>
 *
 * <p>Architecture Position:
 * ASR layer output. Between WhisperEngine and LLM pipeline.
 *
 * <p>Thread Safety:
 * Synchronized for cross-thread access (ASR worker writes, UI thread reads).
 *
 * <p>RISK-04: Sliding window produces overlapping transcripts.
 * Simple deduplication by removing repeated prefix.
 *
 * @author STELLiQ Engineering
 * @version 0.1.0
 * @since ARIA Demo Build — 2026-03-02
 */
package com.stelliq.aria.asr;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.stelliq.aria.util.Constants;

import java.util.ArrayList;
import java.util.List;

public class TranscriptAccumulator {

    private static final String TAG = Constants.LOG_TAG_ASR;

    /**
     * Represents a single transcript segment with metadata.
     */
    public static class Segment {
        public final int index;
        public final String text;
        public final long audioStartMs;
        public final long audioEndMs;
        public final long inferenceMs;
        public final float rtf;
        public final boolean wasHallucination;

        public Segment(int index, String text, long audioStartMs, long audioEndMs,
                       long inferenceMs, float rtf, boolean wasHallucination) {
            this.index = index;
            this.text = text;
            this.audioStartMs = audioStartMs;
            this.audioEndMs = audioEndMs;
            this.inferenceMs = inferenceMs;
            this.rtf = rtf;
            this.wasHallucination = wasHallucination;
        }
    }

    /**
     * Callback for transcript updates.
     */
    public interface TranscriptCallback {
        /**
         * Called when transcript is updated.
         *
         * @param fullTranscript Current full transcript
         * @param latestSegment  Most recently added segment (may be null if filtered)
         */
        void onTranscriptUpdated(@NonNull String fullTranscript, @Nullable Segment latestSegment);
    }

    // WHY: Store all segments for database persistence
    private final List<Segment> mSegments = new ArrayList<>();

    // WHY: StringBuilder for efficient transcript accumulation
    private final StringBuilder mTranscript = new StringBuilder();

    // WHY: Track last segment for deduplication
    @Nullable
    private String mLastSegmentText;

    @Nullable
    private TranscriptCallback mCallback;

    private int mNextSegmentIndex = 0;

    // WHY: Speaker name for attribution in transcript display.
    // Prepended to the first segment so the transcript reads "Stephen: Hello..."
    // For multi-device meetings, MeetingManager handles per-speaker attribution separately.
    @Nullable
    private String mSpeakerName;

    /**
     * Sets the transcript update callback.
     *
     * @param callback Callback for updates
     */
    public void setCallback(@Nullable TranscriptCallback callback) {
        mCallback = callback;
    }

    /**
     * Sets the speaker name for transcript attribution.
     * The name is prepended to the first segment: "Stephen: Hello..."
     *
     * @param name Speaker display name, or null for no attribution
     */
    public void setSpeakerName(@Nullable String name) {
        mSpeakerName = (name != null && !name.trim().isEmpty()) ? name.trim() : null;
    }

    /**
     * Adds a transcript segment.
     *
     * <p>Handles deduplication of overlapping content from sliding window.
     *
     * @param text         Transcript text (may be empty or null)
     * @param audioStartMs Audio window start time
     * @param audioEndMs   Audio window end time
     * @param inferenceMs  Whisper inference time
     * @param rtf          Real-time factor (inferenceMs / audioDurationMs)
     */
    public synchronized void addSegment(@Nullable String text, long audioStartMs, long audioEndMs,
                                        long inferenceMs, float rtf) {
        // WHY: Check for hallucination
        boolean isHallucination = WhisperEngine.isHallucination(text);

        if (text == null || text.trim().isEmpty() || isHallucination) {
            // WHY: Store hallucination segments for metrics but don't add to transcript
            Segment segment = new Segment(
                    mNextSegmentIndex++,
                    text != null ? text : "",
                    audioStartMs,
                    audioEndMs,
                    inferenceMs,
                    rtf,
                    true  // wasHallucination
            );
            mSegments.add(segment);

            Log.d(TAG, "[TranscriptAccumulator.addSegment] Filtered segment (hallucination/empty)");

            if (mCallback != null) {
                mCallback.onTranscriptUpdated(mTranscript.toString(), segment);
            }
            return;
        }

        String trimmedText = text.trim();

        // WHY: Post-process for proper sentence structure (capitalize after .!?, collapse whitespace)
        trimmedText = TextPostProcessor.processSegment(trimmedText);

        // WHY: Deduplicate overlapping content from sliding window
        // RISK-04: Simple approach - check if new segment starts with end of last segment
        String deduplicatedText = deduplicateText(trimmedText);

        if (deduplicatedText.isEmpty()) {
            Log.d(TAG, "[TranscriptAccumulator.addSegment] Fully deduplicated");
            return;
        }

        // WHY: Format timestamp prefix for each segment.
        // Creates scannable transcript with natural breaks instead of a wall of text.
        // Format: "[M:SS] text" or for first segment: "Speaker:\n[0:00] text"
        // For multi-speaker future: "[M:SS] Speaker: text" per segment.
        String timestamp = formatTimestamp(audioStartMs);

        if (mTranscript.length() == 0) {
            // First segment — include speaker name if set
            if (mSpeakerName != null) {
                mTranscript.append(mSpeakerName).append(":\n");
            }
        } else {
            mTranscript.append("\n");
        }
        mTranscript.append("[").append(timestamp).append("] ").append(deduplicatedText);

        // WHY: Store segment for database
        Segment segment = new Segment(
                mNextSegmentIndex++,
                deduplicatedText,
                audioStartMs,
                audioEndMs,
                inferenceMs,
                rtf,
                false  // wasHallucination
        );
        mSegments.add(segment);

        mLastSegmentText = trimmedText;

        Log.d(TAG, "[TranscriptAccumulator.addSegment] Added: \"" + deduplicatedText + "\""
                + " (total length: " + mTranscript.length() + ")");

        if (mCallback != null) {
            mCallback.onTranscriptUpdated(mTranscript.toString(), segment);
        }
    }

    /**
     * Adds a transcript segment with default timing values.
     *
     * <p>Convenience method for simple text accumulation when timing
     * metadata is not available (e.g., manual transcript entry or testing).
     *
     * @param text Transcript text
     */
    public synchronized void addSegment(@Nullable String text) {
        // WHY: Use default zeros for timing - this is a convenience method
        // for cases where timing info is not available
        addSegment(text, 0, 0, 0, 0.0f);
    }

    /**
     * Returns the full accumulated transcript.
     *
     * @return Full transcript text
     */
    @NonNull
    public synchronized String getFullTranscript() {
        return mTranscript.toString();
    }

    /**
     * Returns all segments.
     *
     * @return List of all segments (including filtered ones)
     */
    @NonNull
    public synchronized List<Segment> getSegments() {
        return new ArrayList<>(mSegments);
    }

    /**
     * Returns the number of valid (non-hallucination) segments.
     */
    public synchronized int getValidSegmentCount() {
        int count = 0;
        for (Segment segment : mSegments) {
            if (!segment.wasHallucination) {
                count++;
            }
        }
        return count;
    }

    /**
     * Returns total inference time across all segments.
     */
    public synchronized long getTotalInferenceMs() {
        long total = 0;
        for (Segment segment : mSegments) {
            total += segment.inferenceMs;
        }
        return total;
    }

    /**
     * Returns average RTF across valid segments.
     */
    public synchronized float getAverageRtf() {
        float totalRtf = 0;
        int count = 0;
        for (Segment segment : mSegments) {
            if (!segment.wasHallucination && segment.rtf > 0) {
                totalRtf += segment.rtf;
                count++;
            }
        }
        return count > 0 ? totalRtf / count : 0;
    }

    /**
     * Resets for new session.
     */
    public synchronized void reset() {
        mSegments.clear();
        mTranscript.setLength(0);
        mLastSegmentText = null;
        mNextSegmentIndex = 0;
        Log.d(TAG, "[TranscriptAccumulator.reset] Reset");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PRIVATE METHODS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Formats milliseconds as M:SS timestamp string.
     * Examples: 0 → "0:00", 65000 → "1:05", 3661000 → "61:01"
     *
     * @param ms Milliseconds from session start
     * @return Formatted timestamp
     */
    private static String formatTimestamp(long ms) {
        long totalSeconds = Math.max(0, ms / 1000);
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return minutes + ":" + String.format("%02d", seconds);
    }

    /**
     * Deduplicates text based on overlap with last segment.
     *
     * <p>RISK-04: Sliding window overlap causes repeated words.
     * Strategy: Find longest prefix of new text that matches suffix of last segment.
     *
     * @param newText New segment text
     * @return Deduplicated text
     */
    private String deduplicateText(@NonNull String newText) {
        if (mLastSegmentText == null || mLastSegmentText.isEmpty()) {
            return newText;
        }

        // WHY: Check for word-level overlap
        // Split both texts into words
        String[] lastWords = mLastSegmentText.toLowerCase().split("\\s+");
        String[] newWords = newText.toLowerCase().split("\\s+");

        // WHY: Find overlap by checking if end of last matches start of new
        int overlapWords = 0;
        for (int overlapLen = Math.min(lastWords.length, newWords.length);
             overlapLen > 0; overlapLen--) {

            boolean matches = true;
            for (int i = 0; i < overlapLen; i++) {
                int lastIdx = lastWords.length - overlapLen + i;
                if (!lastWords[lastIdx].equals(newWords[i])) {
                    matches = false;
                    break;
                }
            }

            if (matches) {
                overlapWords = overlapLen;
                break;
            }
        }

        if (overlapWords == 0) {
            return newText;
        }

        // WHY: Remove overlapping prefix words from new text
        String[] originalNewWords = newText.split("\\s+");
        StringBuilder result = new StringBuilder();
        for (int i = overlapWords; i < originalNewWords.length; i++) {
            if (result.length() > 0) {
                result.append(" ");
            }
            result.append(originalNewWords[i]);
        }

        Log.d(TAG, "[TranscriptAccumulator.deduplicateText] Removed " + overlapWords
                + " overlapping words");

        return result.toString();
    }
}
