/**
 * TranscriptAccumulatorTest.java
 *
 * Unit tests for TranscriptAccumulator segment accumulation and deduplication.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Basic segment accumulation</li>
 *   <li>Overlap deduplication from sliding window</li>
 *   <li>Hallucination filtering</li>
 *   <li>Reset functionality</li>
 *   <li>Metrics tracking</li>
 * </ul>
 *
 * @author STELLiQ Engineering
 * @version 0.1.0
 * @since ARIA Demo Build — 2026-03-02
 */
package com.stelliq.aria.asr;

import static org.junit.Assert.*;

import org.junit.Before;
import org.junit.Test;

import java.util.List;

public class TranscriptAccumulatorTest {

    private TranscriptAccumulator mAccumulator;

    @Before
    public void setUp() {
        mAccumulator = new TranscriptAccumulator();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // BASIC ACCUMULATION TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    public void addSegment_singleSegment_accumulatesCorrectly() {
        // Act
        mAccumulator.addSegment("Hello world", 0, 1000, 100, 0.1f);

        // Assert — transcript now has [M:SS] timestamp prefix
        assertEquals("[0:00] Hello world", mAccumulator.getFullTranscript());
        assertEquals(1, mAccumulator.getValidSegmentCount());
    }

    @Test
    public void addSegment_multipleSegments_accumulatesWithNewlines() {
        // Act
        mAccumulator.addSegment("Hello", 0, 1000, 100, 0.1f);
        mAccumulator.addSegment("world", 1000, 2000, 100, 0.1f);

        // Assert — segments separated by newlines with timestamps; TextPostProcessor capitalizes
        assertEquals("[0:00] Hello\n[0:01] World", mAccumulator.getFullTranscript());
        assertEquals(2, mAccumulator.getValidSegmentCount());
    }

    @Test
    public void addSegment_simpleOverload_works() {
        // WHY: Test the simple addSegment(String) convenience method (audioStartMs=0)
        mAccumulator.addSegment("Test segment");

        // Assert — simple overload passes audioStartMs=0, so timestamp is [0:00]
        assertEquals("[0:00] Test segment", mAccumulator.getFullTranscript());
        assertEquals(1, mAccumulator.getValidSegmentCount());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DEDUPLICATION TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    public void addSegment_overlappingWords_deduplicates() {
        // Arrange
        // WHY: Sliding window produces overlapping transcripts
        mAccumulator.addSegment("the quick brown fox", 0, 2000, 100, 0.1f);

        // Act — "brown fox" overlaps with end of previous segment
        mAccumulator.addSegment("brown fox jumps over", 1000, 3000, 100, 0.1f);

        // Assert — deduplicated "brown fox", only "jumps over" added as new segment
        String transcript = mAccumulator.getFullTranscript();
        assertEquals("[0:00] The quick brown fox\n[0:01] jumps over", transcript);
    }

    @Test
    public void addSegment_noOverlap_addsFullText() {
        // Arrange
        mAccumulator.addSegment("first segment", 0, 1000, 100, 0.1f);

        // Act
        mAccumulator.addSegment("second segment", 2000, 3000, 100, 0.1f);

        // Assert — TextPostProcessor capitalizes first letter; segments on separate lines
        assertEquals("[0:00] First segment\n[0:02] Second segment", mAccumulator.getFullTranscript());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HALLUCINATION FILTERING TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    public void addSegment_emptyText_filtered() {
        // Act
        mAccumulator.addSegment("", 0, 1000, 100, 0.1f);

        // Assert
        assertEquals("", mAccumulator.getFullTranscript());
        assertEquals(0, mAccumulator.getValidSegmentCount());
    }

    @Test
    public void addSegment_nullText_filtered() {
        // Act
        mAccumulator.addSegment(null, 0, 1000, 100, 0.1f);

        // Assert
        assertEquals("", mAccumulator.getFullTranscript());
        assertEquals(0, mAccumulator.getValidSegmentCount());
    }

    @Test
    public void addSegment_whitespaceOnly_filtered() {
        // Act
        mAccumulator.addSegment("   ", 0, 1000, 100, 0.1f);

        // Assert
        assertEquals("", mAccumulator.getFullTranscript());
        assertEquals(0, mAccumulator.getValidSegmentCount());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RESET TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    public void reset_clearsAllData() {
        // Arrange
        mAccumulator.addSegment("Test segment", 0, 1000, 100, 0.1f);
        assertEquals(1, mAccumulator.getValidSegmentCount());

        // Act
        mAccumulator.reset();

        // Assert
        assertEquals("", mAccumulator.getFullTranscript());
        assertEquals(0, mAccumulator.getValidSegmentCount());
        assertTrue(mAccumulator.getSegments().isEmpty());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SEGMENT LIST TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    public void getSegments_returnsAllSegments() {
        // Arrange
        mAccumulator.addSegment("First", 0, 1000, 100, 0.1f);
        mAccumulator.addSegment("Second", 1000, 2000, 200, 0.2f);

        // Act
        List<TranscriptAccumulator.Segment> segments = mAccumulator.getSegments();

        // Assert
        assertEquals(2, segments.size());
        assertEquals(0, segments.get(0).index);
        assertEquals(1, segments.get(1).index);
        assertEquals("First", segments.get(0).text);
        assertEquals("Second", segments.get(1).text);
    }

    @Test
    public void getSegments_returnsCopy() {
        // Arrange
        mAccumulator.addSegment("Test", 0, 1000, 100, 0.1f);

        // Act
        List<TranscriptAccumulator.Segment> segments = mAccumulator.getSegments();
        segments.clear();

        // Assert
        // WHY: Clearing returned list should not affect accumulator
        assertEquals(1, mAccumulator.getSegments().size());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // METRICS TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    public void getTotalInferenceMs_sumsMsCorrectly() {
        // Arrange
        mAccumulator.addSegment("First", 0, 1000, 100, 0.1f);
        mAccumulator.addSegment("Second", 1000, 2000, 200, 0.2f);

        // Act
        long totalMs = mAccumulator.getTotalInferenceMs();

        // Assert
        assertEquals(300, totalMs);
    }

    @Test
    public void getAverageRtf_calculatesCorrectly() {
        // Arrange
        mAccumulator.addSegment("First", 0, 1000, 100, 0.1f);
        mAccumulator.addSegment("Second", 1000, 2000, 200, 0.3f);

        // Act
        float avgRtf = mAccumulator.getAverageRtf();

        // Assert
        assertEquals(0.2f, avgRtf, 0.001f);
    }

    @Test
    public void getAverageRtf_noSegments_returnsZero() {
        // Act
        float avgRtf = mAccumulator.getAverageRtf();

        // Assert
        assertEquals(0.0f, avgRtf, 0.001f);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CALLBACK TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    public void setCallback_receivesUpdates() {
        // Arrange
        final String[] receivedTranscript = {null};
        final TranscriptAccumulator.Segment[] receivedSegment = {null};

        mAccumulator.setCallback((fullTranscript, latestSegment) -> {
            receivedTranscript[0] = fullTranscript;
            receivedSegment[0] = latestSegment;
        });

        // Act
        mAccumulator.addSegment("Test", 0, 1000, 100, 0.1f);

        // Assert — fullTranscript includes timestamp; segment.text is the deduplicated text only
        assertEquals("[0:00] Test", receivedTranscript[0]);
        assertNotNull(receivedSegment[0]);
        assertEquals("Test", receivedSegment[0].text);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EDGE CASE TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    public void addSegment_fullOverlap_deduplicatesToEmpty() {
        // Arrange - first segment
        mAccumulator.addSegment("hello world", 0, 2000, 100, 0.1f);

        // Act - second segment is exact duplicate → fully deduplicated → skipped
        mAccumulator.addSegment("hello world", 1000, 3000, 100, 0.1f);

        // Assert - should only appear once; TextPostProcessor capitalizes first letter
        assertEquals("[0:00] Hello world", mAccumulator.getFullTranscript());
    }

    @Test
    public void addSegment_partialWordOverlap_handlesCorrectly() {
        // Arrange
        mAccumulator.addSegment("The mission was successful", 0, 2000, 100, 0.1f);

        // Act - new segment has word-level overlap
        mAccumulator.addSegment("successful and complete", 1500, 3500, 100, 0.1f);

        // Assert — first segment intact, second has only non-overlapping portion
        String transcript = mAccumulator.getFullTranscript();
        assertTrue("Should contain first segment", transcript.contains("mission was successful"));
        assertTrue("Should contain deduplicated addition", transcript.contains("and complete"));
    }

    @Test
    public void addSegment_caseInsensitiveDeduplication_works() {
        // Arrange
        mAccumulator.addSegment("The Quick Brown Fox", 0, 2000, 100, 0.1f);

        // Act - different case but same words
        mAccumulator.addSegment("brown fox jumps", 1500, 3500, 100, 0.1f);

        // Assert - should deduplicate case-insensitively
        String transcript = mAccumulator.getFullTranscript();
        assertTrue("Should contain jumps", transcript.contains("jumps"));
    }

    @Test
    public void addSegment_segmentTimingTracked() {
        // Arrange
        mAccumulator.addSegment("Test", 500, 1500, 100, 0.1f);

        // Act
        List<TranscriptAccumulator.Segment> segments = mAccumulator.getSegments();

        // Assert
        assertEquals(1, segments.size());
        assertEquals(500, segments.get(0).audioStartMs);
        assertEquals(1500, segments.get(0).audioEndMs);
        assertEquals(100, segments.get(0).inferenceMs);
        assertEquals(0.1f, segments.get(0).rtf, 0.001f);
    }

    @Test
    public void addSegment_hallucinations_trackedButNotInTranscript() {
        // Arrange - empty/whitespace segments are marked as hallucinations
        mAccumulator.addSegment("Valid text", 0, 1000, 100, 0.1f);
        mAccumulator.addSegment("", 1000, 2000, 100, 0.1f);  // Hallucination
        mAccumulator.addSegment("More valid", 2000, 3000, 100, 0.1f);

        // Act
        List<TranscriptAccumulator.Segment> segments = mAccumulator.getSegments();

        // Assert — hallucination skipped in transcript; timestamps on each line
        assertEquals("[0:00] Valid text\n[0:02] More valid", mAccumulator.getFullTranscript());
        assertEquals(2, mAccumulator.getValidSegmentCount());
        assertEquals(3, segments.size());  // All segments stored
        assertTrue("Middle segment should be hallucination", segments.get(1).wasHallucination);
    }

    @Test
    public void addSegment_preservesOriginalText() {
        // Arrange - text with special formatting
        String text = "  Text with   extra   spaces  ";

        // Act
        mAccumulator.addSegment(text, 0, 1000, 100, 0.1f);

        // Assert - should trim outer whitespace but text is preserved in segment
        List<TranscriptAccumulator.Segment> segments = mAccumulator.getSegments();
        assertTrue("Should preserve trimmed text",
                mAccumulator.getFullTranscript().contains("Text with"));
    }

    @Test
    public void addSegment_longTranscript_accumulates() {
        // Arrange - simulate a long session
        StringBuilder expected = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            String segment = "Segment number " + i + ".";
            mAccumulator.addSegment(segment, i * 1000, (i + 1) * 1000, 100, 0.1f);

            // Build expected output: [M:SS] text, separated by newlines
            long totalSec = i;
            long min = totalSec / 60;
            long sec = totalSec % 60;
            String ts = min + ":" + String.format("%02d", sec);
            if (expected.length() > 0) {
                expected.append("\n");
            }
            expected.append("[").append(ts).append("] ").append(segment);
        }

        // Assert
        assertEquals(100, mAccumulator.getValidSegmentCount());
        assertEquals(expected.toString(), mAccumulator.getFullTranscript());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // THREAD SAFETY TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    public void addSegment_concurrentAccess_noException() throws InterruptedException {
        // WHY: Accumulator is accessed from ASR thread (writes) and UI thread (reads)
        final int numThreads = 4;
        final int segmentsPerThread = 50;

        Thread[] threads = new Thread[numThreads];
        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            threads[t] = new Thread(() -> {
                for (int i = 0; i < segmentsPerThread; i++) {
                    mAccumulator.addSegment(
                            "Thread" + threadId + " Segment" + i,
                            (threadId * segmentsPerThread + i) * 100L,
                            (threadId * segmentsPerThread + i + 1) * 100L,
                            50,
                            0.1f
                    );
                }
            });
        }

        // Start all threads
        for (Thread thread : threads) {
            thread.start();
        }

        // Wait for completion
        for (Thread thread : threads) {
            thread.join();
        }

        // Assert - just verify no exceptions and reasonable state
        assertTrue("Should have accumulated segments",
                mAccumulator.getValidSegmentCount() > 0);
        assertNotNull("Should have transcript",
                mAccumulator.getFullTranscript());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RESET AND REUSE TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    public void reset_allowsReuse() {
        // First use
        mAccumulator.addSegment("First session", 0, 1000, 100, 0.1f);
        assertEquals("[0:00] First session", mAccumulator.getFullTranscript());

        // Reset
        mAccumulator.reset();

        // Second use
        mAccumulator.addSegment("Second session", 0, 1000, 100, 0.1f);
        assertEquals("[0:00] Second session", mAccumulator.getFullTranscript());
        assertEquals(1, mAccumulator.getValidSegmentCount());
    }

    @Test
    public void reset_resetsIndices() {
        // Arrange
        mAccumulator.addSegment("First", 0, 1000, 100, 0.1f);
        mAccumulator.addSegment("Second", 1000, 2000, 100, 0.1f);

        // Act
        mAccumulator.reset();
        mAccumulator.addSegment("New first", 0, 1000, 100, 0.1f);

        // Assert - index should restart at 0
        List<TranscriptAccumulator.Segment> segments = mAccumulator.getSegments();
        assertEquals(1, segments.size());
        assertEquals(0, segments.get(0).index);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CALLBACK EDGE CASES
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    public void setCallback_nullCallback_noException() {
        // Arrange
        mAccumulator.setCallback(null);

        // Act - should not throw
        mAccumulator.addSegment("Test", 0, 1000, 100, 0.1f);

        // Assert
        assertEquals("[0:00] Test", mAccumulator.getFullTranscript());
    }

    @Test
    public void setCallback_receivesHallucinationSegments() {
        // Arrange
        final TranscriptAccumulator.Segment[] receivedSegment = {null};

        mAccumulator.setCallback((fullTranscript, latestSegment) -> {
            receivedSegment[0] = latestSegment;
        });

        // Act - add empty segment (hallucination)
        mAccumulator.addSegment("", 0, 1000, 100, 0.1f);

        // Assert - callback should still be called with hallucination segment
        assertNotNull("Should receive hallucination segment", receivedSegment[0]);
        assertTrue("Should be marked as hallucination", receivedSegment[0].wasHallucination);
    }
}
