/**
 * SlidingWindowBufferTest.java
 *
 * Unit tests for SlidingWindowBuffer sliding window audio buffering.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>First window emission after WINDOW_SAMPLES</li>
 *   <li>Subsequent windows at STRIDE_SAMPLES intervals</li>
 *   <li>PCM short to float conversion accuracy</li>
 *   <li>Buffer reset functionality</li>
 *   <li>Timing calculation accuracy</li>
 * </ul>
 *
 * <p>Note: VAD tests require mocking SileroVAD and are in separate androidTest.
 *
 * @author STELLiQ Engineering
 * @version 0.1.0
 * @since ARIA Demo Build — 2026-03-02
 */
package com.stelliq.aria.asr;

import static org.junit.Assert.*;

import org.junit.Before;
import org.junit.Test;

public class SlidingWindowBufferTest {

    // WHY: Match Constants values for test calculations (5s window, 5s stride — NPU build)
    private static final int SAMPLE_RATE = 16000;
    private static final int WINDOW_MS = 5000;
    private static final int STRIDE_MS = 5000;
    private static final int WINDOW_SAMPLES = SAMPLE_RATE * WINDOW_MS / 1000;  // 80000
    private static final int STRIDE_SAMPLES = SAMPLE_RATE * STRIDE_MS / 1000;  // 80000

    private SlidingWindowBuffer mBuffer;

    @Before
    public void setUp() {
        mBuffer = new SlidingWindowBuffer();
        // WHY: Disable VAD for unit tests (no native ONNX runtime)
        mBuffer.setVadEnabled(false);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // WINDOW AVAILABILITY TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    public void hasWindow_emptyBuffer_returnsFalse() {
        assertFalse(mBuffer.hasWindow());
    }

    @Test
    public void hasWindow_lessThanWindowSamples_returnsFalse() {
        // Add less than one window worth of samples
        short[] samples = new short[WINDOW_SAMPLES - 100];
        mBuffer.addSamples(samples, samples.length);

        assertFalse(mBuffer.hasWindow());
    }

    @Test
    public void hasWindow_exactlyWindowSamples_returnsTrue() {
        // Add exactly one window worth of samples
        short[] samples = new short[WINDOW_SAMPLES];
        mBuffer.addSamples(samples, samples.length);

        assertTrue(mBuffer.hasWindow());
    }

    @Test
    public void hasWindow_afterFirstEmit_requiresStride() {
        // Add first window and emit it
        short[] samples = new short[WINDOW_SAMPLES];
        mBuffer.addSamples(samples, samples.length);
        mBuffer.getNextWindow();  // Consume first window

        // Immediately check - should not have window (need stride more samples)
        assertFalse(mBuffer.hasWindow());

        // Add stride worth of samples
        short[] strideSamples = new short[STRIDE_SAMPLES];
        mBuffer.addSamples(strideSamples, strideSamples.length);

        // Now should have window
        assertTrue(mBuffer.hasWindow());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // WINDOW RETRIEVAL TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    public void getNextWindow_noWindow_returnsNull() {
        assertNull(mBuffer.getNextWindow());
    }

    @Test
    public void getNextWindow_hasWindow_returnsFloatArray() {
        // Add window samples
        short[] samples = new short[WINDOW_SAMPLES];
        mBuffer.addSamples(samples, samples.length);

        // Get window
        float[] window = mBuffer.getNextWindow();

        assertNotNull(window);
        assertEquals(WINDOW_SAMPLES, window.length);
    }

    @Test
    public void getNextWindow_consumesWindow() {
        // Add exactly one window
        short[] samples = new short[WINDOW_SAMPLES];
        mBuffer.addSamples(samples, samples.length);

        // First retrieval succeeds
        assertNotNull(mBuffer.getNextWindow());

        // Second retrieval fails (need more samples)
        assertFalse(mBuffer.hasWindow());
        assertNull(mBuffer.getNextWindow());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PCM CONVERSION TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    public void getNextWindow_convertsShortToFloat() {
        // Arrange - create samples with known values
        // WHY: The 80 Hz HPF modifies samples during addSamples(), so values are
        // attenuated. Use broader tolerance (0.05) to account for filter transient.
        short[] samples = new short[WINDOW_SAMPLES];
        samples[0] = 0;           // Should become ~0.0f
        samples[1] = 32767;       // Should become ~0.97f (HPF attenuates impulse)
        samples[2] = -32768;      // Should become ~-0.97f
        samples[3] = 16384;       // Should become ~0.48f

        mBuffer.addSamples(samples, samples.length);

        // Act
        float[] window = mBuffer.getNextWindow();

        // Assert — wider tolerance due to 80 Hz high-pass filter effect
        assertNotNull(window);
        assertEquals(0.0f, window[0], 0.05f);
        assertEquals(1.0f, window[1], 0.05f);
        assertEquals(-1.0f, window[2], 0.05f);
        assertEquals(0.5f, window[3], 0.05f);
    }

    @Test
    public void getNextWindow_floatValuesInRange() {
        // Fill with random values
        short[] samples = new short[WINDOW_SAMPLES];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = (short) ((Math.random() * 65536) - 32768);
        }
        mBuffer.addSamples(samples, samples.length);

        float[] window = mBuffer.getNextWindow();

        // All values should be in [-1.0, 1.0]
        for (float value : window) {
            assertTrue("Value should be >= -1.0", value >= -1.0f);
            assertTrue("Value should be <= 1.0", value <= 1.0f);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TIMING TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    public void getElapsedMs_emptyBuffer_returnsZero() {
        assertEquals(0, mBuffer.getElapsedMs());
    }

    @Test
    public void getElapsedMs_oneSecondOfAudio_returns1000() {
        // One second = 16000 samples at 16kHz
        short[] samples = new short[SAMPLE_RATE];
        mBuffer.addSamples(samples, samples.length);

        assertEquals(1000, mBuffer.getElapsedMs());
    }

    @Test
    public void getTotalSamples_tracksCorrectly() {
        short[] samples1 = new short[1000];
        short[] samples2 = new short[500];

        mBuffer.addSamples(samples1, samples1.length);
        assertEquals(1000, mBuffer.getTotalSamples());

        mBuffer.addSamples(samples2, samples2.length);
        assertEquals(1500, mBuffer.getTotalSamples());
    }

    @Test
    public void getWindowStartMs_firstWindow_correct() {
        // Add exactly one window (5 seconds)
        short[] samples = new short[WINDOW_SAMPLES];
        mBuffer.addSamples(samples, samples.length);

        // Window should start at 0ms
        assertEquals(0, mBuffer.getWindowStartMs());
    }

    @Test
    public void getWindowEndMs_firstWindow_correct() {
        // Add exactly one window (5 seconds)
        short[] samples = new short[WINDOW_SAMPLES];
        mBuffer.addSamples(samples, samples.length);

        // Window should end at 5000ms
        assertEquals(WINDOW_MS, mBuffer.getWindowEndMs());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RESET TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    public void reset_clearsAllState() {
        // Add some samples
        short[] samples = new short[WINDOW_SAMPLES];
        mBuffer.addSamples(samples, samples.length);
        assertTrue(mBuffer.hasWindow());

        // Reset
        mBuffer.reset();

        // Verify cleared
        assertEquals(0, mBuffer.getTotalSamples());
        assertEquals(0, mBuffer.getElapsedMs());
        assertFalse(mBuffer.hasWindow());
        assertNull(mBuffer.getNextWindow());
    }

    @Test
    public void reset_allowsNewRecording() {
        // First recording
        short[] samples1 = new short[WINDOW_SAMPLES];
        mBuffer.addSamples(samples1, samples1.length);
        assertNotNull(mBuffer.getNextWindow());

        // Reset
        mBuffer.reset();

        // Second recording should work
        short[] samples2 = new short[WINDOW_SAMPLES];
        mBuffer.addSamples(samples2, samples2.length);
        assertTrue(mBuffer.hasWindow());
        assertNotNull(mBuffer.getNextWindow());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // STRIDE/OVERLAP TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    public void multipleWindows_correctStriding() {
        // WHY: With stride == window (5s), add incrementally to allow per-window emission.
        // Matches production pattern where audio arrives in real-time chunks.

        // Add first window (5 seconds)
        short[] firstSamples = new short[WINDOW_SAMPLES];
        mBuffer.addSamples(firstSamples, firstSamples.length);

        // Should get first window
        assertNotNull(mBuffer.getNextWindow());
        assertFalse("Need stride more samples before next window", mBuffer.hasWindow());

        // Add stride worth of samples (5 seconds)
        short[] strideSamples = new short[STRIDE_SAMPLES];
        mBuffer.addSamples(strideSamples, strideSamples.length);

        // Should get second window
        assertTrue(mBuffer.hasWindow());
        assertNotNull(mBuffer.getNextWindow());

        // Should not have third window yet
        assertFalse(mBuffer.hasWindow());
    }

    @Test
    public void incrementalAddSamples_works() {
        // Add samples in small chunks
        int chunkSize = 160;  // 10ms at 16kHz
        int totalChunks = WINDOW_SAMPLES / chunkSize;

        for (int i = 0; i < totalChunks; i++) {
            short[] chunk = new short[chunkSize];
            mBuffer.addSamples(chunk, chunk.length);

            if (i < totalChunks - 1) {
                assertFalse("Should not have window before last chunk", mBuffer.hasWindow());
            }
        }

        // After all chunks, should have window
        assertTrue(mBuffer.hasWindow());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // VAD CONFIGURATION TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    public void setVadEnabled_togglesCorrectly() {
        // Default is enabled but we disabled in setUp
        // These tests just verify the API doesn't crash

        mBuffer.setVadEnabled(true);
        mBuffer.setVadEnabled(false);

        // Without VAD set, window should still work
        short[] samples = new short[WINDOW_SAMPLES];
        mBuffer.addSamples(samples, samples.length);
        assertNotNull(mBuffer.getNextWindow());
    }

    @Test
    public void setVad_acceptsNull() {
        // Should not crash with null VAD
        mBuffer.setVad(null);

        short[] samples = new short[WINDOW_SAMPLES];
        mBuffer.addSamples(samples, samples.length);
        assertNotNull(mBuffer.getNextWindow());
    }
}
