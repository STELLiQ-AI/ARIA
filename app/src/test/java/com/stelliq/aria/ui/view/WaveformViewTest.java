/**
 * WaveformViewTest.java
 *
 * Unit tests for WaveformView amplitude calculation utilities.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>RMS amplitude calculation from short[] samples</li>
 *   <li>RMS amplitude calculation from float[] samples</li>
 *   <li>Edge cases (empty, null, silence)</li>
 *   <li>Log scaling behavior</li>
 * </ul>
 *
 * <p>Note: UI rendering tests require Android instrumented tests.
 * This file tests the static utility methods only.
 *
 * @author STELLiQ Engineering
 * @version 0.1.0
 * @since ARIA Demo Build — 2026-03-04
 */
package com.stelliq.aria.ui.view;

import static org.junit.Assert.*;

import org.junit.Test;

public class WaveformViewTest {

    // ═══════════════════════════════════════════════════════════════════════════
    // SHORT ARRAY RMS CALCULATION
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    public void calculateRmsAmplitude_shortArray_silence_returnsZero() {
        // Arrange
        short[] samples = new short[100];  // All zeros

        // Act
        float amplitude = WaveformView.calculateRmsAmplitude(samples, 100);

        // Assert
        assertEquals(0f, amplitude, 0.001f);
    }

    @Test
    public void calculateRmsAmplitude_shortArray_maxAmplitude_returnsHigh() {
        // Arrange - max amplitude sine wave approximation
        short[] samples = new short[100];
        for (int i = 0; i < 100; i++) {
            samples[i] = (i % 2 == 0) ? Short.MAX_VALUE : Short.MIN_VALUE;
        }

        // Act
        float amplitude = WaveformView.calculateRmsAmplitude(samples, 100);

        // Assert - should be close to 1.0 due to log scaling
        assertTrue("Max amplitude should be high", amplitude > 0.9f);
        assertTrue("Amplitude should not exceed 1.0", amplitude <= 1.0f);
    }

    @Test
    public void calculateRmsAmplitude_shortArray_moderateSignal_returnsMidRange() {
        // Arrange - moderate amplitude
        short[] samples = new short[100];
        for (int i = 0; i < 100; i++) {
            samples[i] = (short) (8192 * Math.sin(2 * Math.PI * i / 10));  // ~25% amplitude
        }

        // Act
        float amplitude = WaveformView.calculateRmsAmplitude(samples, 100);

        // Assert
        assertTrue("Should be in mid range", amplitude > 0.2f && amplitude < 0.8f);
    }

    @Test
    public void calculateRmsAmplitude_shortArray_zeroCount_returnsZero() {
        // Arrange
        short[] samples = {1000, 2000, 3000};

        // Act
        float amplitude = WaveformView.calculateRmsAmplitude(samples, 0);

        // Assert
        assertEquals(0f, amplitude, 0.001f);
    }

    @Test
    public void calculateRmsAmplitude_shortArray_negativeCount_returnsZero() {
        // Arrange
        short[] samples = {1000, 2000, 3000};

        // Act
        float amplitude = WaveformView.calculateRmsAmplitude(samples, -5);

        // Assert
        assertEquals(0f, amplitude, 0.001f);
    }

    @Test
    public void calculateRmsAmplitude_shortArray_countExceedsLength_usesArrayLength() {
        // Arrange
        short[] samples = new short[10];
        for (int i = 0; i < 10; i++) {
            samples[i] = 1000;
        }

        // Act - request more samples than available
        float amplitude = WaveformView.calculateRmsAmplitude(samples, 100);

        // Assert - should not crash, uses array length
        assertTrue("Should handle gracefully", amplitude > 0);
    }

    @Test
    public void calculateRmsAmplitude_shortArray_partialBuffer_usesCount() {
        // Arrange - partially filled buffer
        short[] samples = new short[100];
        samples[0] = Short.MAX_VALUE;
        samples[1] = Short.MIN_VALUE;

        // Act - only process first 2 samples
        float amplitude = WaveformView.calculateRmsAmplitude(samples, 2);

        // Assert
        assertTrue("Should have high amplitude from active samples", amplitude > 0.9f);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // FLOAT ARRAY RMS CALCULATION
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    public void calculateRmsAmplitude_floatArray_silence_returnsZero() {
        // Arrange
        float[] samples = new float[100];  // All zeros

        // Act
        float amplitude = WaveformView.calculateRmsAmplitude(samples, 100);

        // Assert
        assertEquals(0f, amplitude, 0.001f);
    }

    @Test
    public void calculateRmsAmplitude_floatArray_maxAmplitude_returnsHigh() {
        // Arrange - max amplitude alternating
        float[] samples = new float[100];
        for (int i = 0; i < 100; i++) {
            samples[i] = (i % 2 == 0) ? 1.0f : -1.0f;
        }

        // Act
        float amplitude = WaveformView.calculateRmsAmplitude(samples, 100);

        // Assert
        assertTrue("Max amplitude should be 1.0", amplitude >= 0.99f);
        assertTrue("Amplitude should not exceed 1.0", amplitude <= 1.0f);
    }

    @Test
    public void calculateRmsAmplitude_floatArray_sineWave_returnsMidRange() {
        // Arrange - sine wave
        float[] samples = new float[100];
        for (int i = 0; i < 100; i++) {
            samples[i] = (float) Math.sin(2 * Math.PI * i / 10);
        }

        // Act
        float amplitude = WaveformView.calculateRmsAmplitude(samples, 100);

        // Assert
        // RMS of sine wave is ~0.707, log scaled
        assertTrue("Sine wave should have moderate amplitude", amplitude > 0.5f);
        assertTrue("Should not exceed 1.0", amplitude <= 1.0f);
    }

    @Test
    public void calculateRmsAmplitude_floatArray_zeroCount_returnsZero() {
        // Arrange
        float[] samples = {0.5f, 0.6f, 0.7f};

        // Act
        float amplitude = WaveformView.calculateRmsAmplitude(samples, 0);

        // Assert
        assertEquals(0f, amplitude, 0.001f);
    }

    @Test
    public void calculateRmsAmplitude_floatArray_smallSignal_returnsLow() {
        // Arrange - very quiet signal
        float[] samples = new float[100];
        for (int i = 0; i < 100; i++) {
            samples[i] = 0.01f * (float) Math.sin(2 * Math.PI * i / 10);
        }

        // Act
        float amplitude = WaveformView.calculateRmsAmplitude(samples, 100);

        // Assert
        assertTrue("Very quiet signal should have low amplitude", amplitude < 0.3f);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LOG SCALING BEHAVIOR
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    public void calculateRmsAmplitude_logScaling_lowAmplitudeBoost() {
        // WHY: Log scaling should boost low amplitudes for better visualization

        // Arrange - 10% amplitude signal
        float[] samples = new float[100];
        for (int i = 0; i < 100; i++) {
            samples[i] = 0.1f * (float) Math.sin(2 * Math.PI * i / 10);
        }

        // Act
        float amplitude = WaveformView.calculateRmsAmplitude(samples, 100);

        // Assert - log scaling should boost this above linear 0.07 (~10% * 0.707)
        assertTrue("Log scaling should boost low amplitudes", amplitude > 0.15f);
    }

    @Test
    public void calculateRmsAmplitude_monotonic_higherInputHigherOutput() {
        // WHY: Verify amplitude calculation is monotonically increasing

        float prevAmplitude = 0f;
        for (int level = 1; level <= 10; level++) {
            float[] samples = new float[100];
            float signalLevel = level / 10.0f;
            for (int i = 0; i < 100; i++) {
                samples[i] = signalLevel * (float) Math.sin(2 * Math.PI * i / 10);
            }

            float amplitude = WaveformView.calculateRmsAmplitude(samples, 100);
            assertTrue("Higher signal should produce higher amplitude",
                    amplitude > prevAmplitude);
            prevAmplitude = amplitude;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EDGE CASES
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    public void calculateRmsAmplitude_singleSample_works() {
        // Arrange
        short[] samples = {16384};  // 50% amplitude

        // Act
        float amplitude = WaveformView.calculateRmsAmplitude(samples, 1);

        // Assert
        assertTrue("Single sample should work", amplitude > 0);
    }

    @Test
    public void calculateRmsAmplitude_dcOffset_detected() {
        // Arrange - DC offset (constant value, no AC signal)
        float[] samples = new float[100];
        for (int i = 0; i < 100; i++) {
            samples[i] = 0.5f;  // DC offset
        }

        // Act
        float amplitude = WaveformView.calculateRmsAmplitude(samples, 100);

        // Assert - RMS of constant 0.5 is 0.5
        assertTrue("DC offset should show amplitude", amplitude > 0.4f);
    }

    @Test
    public void calculateRmsAmplitude_alternatingHighLow_accurate() {
        // Arrange - simple alternating pattern
        short[] samples = new short[100];
        for (int i = 0; i < 100; i++) {
            samples[i] = (i % 2 == 0) ? (short) 16384 : (short) -16384;
        }

        // Act
        float amplitude = WaveformView.calculateRmsAmplitude(samples, 100);

        // Assert - RMS should be close to 16384/32768 = 0.5, log scaled
        assertTrue("Alternating pattern should show moderate-high amplitude",
                amplitude > 0.6f && amplitude < 1.0f);
    }
}
