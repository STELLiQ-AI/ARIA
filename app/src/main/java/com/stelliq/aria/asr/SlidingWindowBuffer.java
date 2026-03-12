/**
 * SlidingWindowBuffer.java
 *
 * Implements sliding window buffering for streaming ASR.
 * Accumulates audio and emits overlapping chunks for transcription.
 *
 * <p>Responsibility:
 * <ul>
 *   <li>Buffer incoming short[] PCM audio samples</li>
 *   <li>Convert short[] to float[] normalized [-1.0, 1.0]</li>
 *   <li>Emit 2-second windows with 1-second stride</li>
 *   <li>Apply VAD gating before emission</li>
 * </ul>
 *
 * <p>Architecture Position:
 * ASR layer buffer. Between AudioCaptureManager and WhisperEngine.
 *
 * <p>Thread Safety:
 * addSamples() and getNextWindow() should be called from same thread.
 *
 * <p>PERF: 2s window with 1s stride provides ~1.5s perceived latency.
 * Window: [t-2s, t]. Next window: [t-1s, t+1s]. 1s overlap.
 *
 * <p>RISK-06: PCM format conversion.
 * AudioRecord gives short[-32768, 32767]. whisper.cpp needs float[-1.0, 1.0].
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

import java.util.Arrays;

public class SlidingWindowBuffer {

    private static final String TAG = Constants.LOG_TAG_ASR;

    // WHY: Configuration from Constants — 5s window, 3s stride at 16kHz for small.en
    private static final int SAMPLE_RATE = Constants.WHISPER_SAMPLE_RATE;
    private static final int WINDOW_MS = Constants.ASR_WINDOW_MS;
    private static final int STRIDE_MS = Constants.ASR_STRIDE_MS;

    // Derived constants
    private static final int WINDOW_SAMPLES = SAMPLE_RATE * WINDOW_MS / 1000;   // 80000 samples (5s)
    private static final int STRIDE_SAMPLES = SAMPLE_RATE * STRIDE_MS / 1000;   // 48000 samples (3s)

    // WHY: Circular buffer to avoid array copies
    private final short[] mBuffer;
    private int mWritePos = 0;
    private int mTotalSamples = 0;

    // WHY: Pre-allocated output buffer for float conversion
    private final float[] mWindowBuffer;

    // ─── 80 Hz 2nd-order Butterworth high-pass filter ─────────────────────
    // WHY: Removes low-frequency noise (HVAC, handling, wind) that Whisper's
    // mel-spectrogram misinterprets as speech energy. Butterworth gives flat
    // passband — no spectral distortion above 80 Hz.
    // Coefficients: bilinear transform at fs=16000 Hz, fc=80 Hz
    private static final double HP_B0 =  0.9684935838;
    private static final double HP_B1 = -1.9369871676;
    private static final double HP_B2 =  0.9684935838;
    private static final double HP_A1 = -1.9364182806;
    private static final double HP_A2 =  0.9375560547;

    // Direct Form II transposed state
    private double mHpZ1 = 0.0;
    private double mHpZ2 = 0.0;

    // VAD integration
    @Nullable
    private SileroVAD mVad;
    private boolean mVadEnabled = true;

    // WHY: Track window emission for stride calculation
    private int mSamplesAtLastEmit = 0;

    /**
     * Creates a SlidingWindowBuffer with default configuration.
     */
    public SlidingWindowBuffer() {
        // WHY: Buffer holds 2 windows worth of data for overlap handling
        mBuffer = new short[WINDOW_SAMPLES * 2];
        mWindowBuffer = new float[WINDOW_SAMPLES];

        Log.d(TAG, "[SlidingWindowBuffer] Created. Window: " + WINDOW_MS + "ms ("
                + WINDOW_SAMPLES + " samples), Stride: " + STRIDE_MS + "ms ("
                + STRIDE_SAMPLES + " samples)");
    }

    /**
     * Sets the VAD instance for speech gating.
     *
     * @param vad SileroVAD instance, or null to disable VAD
     */
    public void setVad(@Nullable SileroVAD vad) {
        mVad = vad;
    }

    /**
     * Enables or disables VAD gating.
     *
     * @param enabled True to enable VAD filtering
     */
    public void setVadEnabled(boolean enabled) {
        mVadEnabled = enabled;
    }

    /**
     * Adds audio samples to the buffer.
     *
     * <p>RISK-06: Input is short[] from AudioRecord.
     *
     * @param samples    PCM samples (16-bit signed)
     * @param numSamples Number of valid samples in array
     */
    public void addSamples(@NonNull short[] samples, int numSamples) {
        for (int i = 0; i < numSamples; i++) {
            // WHY: Apply 80 Hz HPF per-sample during ingestion (Direct Form II transposed)
            double x = samples[i];
            double y = HP_B0 * x + mHpZ1;
            mHpZ1 = HP_B1 * x - HP_A1 * y + mHpZ2;
            mHpZ2 = HP_B2 * x - HP_A2 * y;

            mBuffer[mWritePos] = (short) Math.max(-32768, Math.min(32767, (int) y));
            mWritePos = (mWritePos + 1) % mBuffer.length;
            mTotalSamples++;
        }
    }

    /**
     * Checks if a full window is available for emission.
     *
     * @return True if getNextWindow() will return valid data
     */
    public boolean hasWindow() {
        // WHY: Need full window AND stride since last emit
        int samplesAvailable = mTotalSamples - mSamplesAtLastEmit;
        boolean hasEnoughForFirstWindow = mTotalSamples >= WINDOW_SAMPLES && mSamplesAtLastEmit == 0;
        boolean hasEnoughForNextWindow = samplesAvailable >= STRIDE_SAMPLES && mTotalSamples >= WINDOW_SAMPLES;

        return hasEnoughForFirstWindow || hasEnoughForNextWindow;
    }

    /**
     * Gets the next audio window for transcription.
     *
     * <p>Returns float[] normalized to [-1.0, 1.0] for whisper.cpp.
     * Returns null if no speech detected by VAD (when enabled).
     *
     * @return Float audio window, or null if VAD filtered/no window available
     */
    @Nullable
    public float[] getNextWindow() {
        if (!hasWindow()) {
            return null;
        }

        // WHY: Calculate read position for window start
        int readStart = (mWritePos - WINDOW_SAMPLES + mBuffer.length) % mBuffer.length;

        // WHY: Copy and convert short[] to float[]
        // RISK-06: Normalize short[-32768, 32767] to float[-1.0, 1.0]
        for (int i = 0; i < WINDOW_SAMPLES; i++) {
            int readPos = (readStart + i) % mBuffer.length;
            mWindowBuffer[i] = mBuffer[readPos] / 32768.0f;
        }

        // WHY: VAD gating filters silence windows to save Whisper CPU cycles
        // WHY: Reset LSTM state before each window to prevent state drift.
        // With 5s windows (156 chunks), accumulated state dampens probabilities.
        // Each window should be evaluated independently, like whisper's no_context.
        if (mVadEnabled && mVad != null && mVad.isLoaded()) {
            mVad.resetState();
            if (!mVad.isSpeech(mWindowBuffer)) {
                mSamplesAtLastEmit = mTotalSamples;
                Log.d(TAG, "[SlidingWindowBuffer.getNextWindow] VAD filtered (no speech)");
                return null;
            }
        }

        // WHY: Mark window as emitted for stride tracking
        mSamplesAtLastEmit = mTotalSamples;
        Log.d(TAG, "[SlidingWindowBuffer.getNextWindow] Window emitted");

        // WHY: Return defensive copy to prevent callers from mutating internal buffer.
        // Cost: ~0.1ms for 80,000 floats — negligible vs Whisper inference (~800ms).
        return Arrays.copyOf(mWindowBuffer, mWindowBuffer.length);
    }

    /**
     * Gets the audio offset in milliseconds for the current window.
     *
     * @return Audio offset from session start in ms
     */
    public long getWindowStartMs() {
        // WHY: Calculate based on total samples processed
        long windowEndSamples = mTotalSamples;
        long windowStartSamples = windowEndSamples - WINDOW_SAMPLES;
        return windowStartSamples * 1000L / SAMPLE_RATE;
    }

    /**
     * Gets the audio end offset in milliseconds for the current window.
     *
     * @return Audio end offset from session start in ms
     */
    public long getWindowEndMs() {
        return mTotalSamples * 1000L / SAMPLE_RATE;
    }

    /**
     * Returns total samples received.
     */
    public int getTotalSamples() {
        return mTotalSamples;
    }

    /**
     * Returns elapsed time in milliseconds.
     */
    public long getElapsedMs() {
        return mTotalSamples * 1000L / SAMPLE_RATE;
    }

    /**
     * Resets the buffer for new session.
     */
    public void reset() {
        mWritePos = 0;
        mTotalSamples = 0;
        mSamplesAtLastEmit = 0;
        java.util.Arrays.fill(mBuffer, (short) 0);
        mHpZ1 = 0.0;
        mHpZ2 = 0.0;
        Log.d(TAG, "[SlidingWindowBuffer.reset] Buffer reset");
    }
}
