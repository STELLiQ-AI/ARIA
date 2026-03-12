/**
 * AudioCaptureManager.java
 *
 * Manages AudioRecord lifecycle and delivers raw PCM audio to the ASR pipeline.
 * Uses HandlerThread for background audio capture.
 *
 * <p>Responsibility:
 * <ul>
 *   <li>Initialize and manage AudioRecord lifecycle</li>
 *   <li>Capture 16kHz mono PCM audio</li>
 *   <li>Deliver audio chunks to callback on worker thread</li>
 *   <li>Handle audio focus and permission state</li>
 *   <li>This class does NOT process audio — that's WhisperEngine's job</li>
 * </ul>
 *
 * <p>Architecture Position:
 * ASR layer input. Owned by ARIASessionService. Runs on aria-audio-capture thread.
 *
 * <p>Thread Safety:
 * AudioRecord accessed only from capture thread. State flags are volatile.
 *
 * <p>Air-Gap Compliance:
 * Audio captured locally. No streaming to cloud.
 *
 * <p>RISK-06: PCM format is short[] from AudioRecord, but whisper.cpp needs float[-1.0, 1.0].
 * Conversion handled by SlidingWindowBuffer.
 *
 * @author STELLiQ Engineering
 * @version 0.1.0
 * @since ARIA Demo Build — 2026-03-02
 */
package com.stelliq.aria.asr;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.stelliq.aria.util.Constants;

public class AudioCaptureManager {

    private static final String TAG = Constants.LOG_TAG_ASR;

    /**
     * Callback interface for audio chunk delivery.
     */
    public interface AudioCallback {
        /**
         * Called when new audio data is available.
         *
         * @param audioData Raw PCM samples (16-bit signed, little-endian)
         * @param sampleCount Number of samples in array
         */
        void onAudioAvailable(@NonNull short[] audioData, int sampleCount);
    }

    // WHY: Audio format matches whisper.cpp expectations
    private static final int SAMPLE_RATE = Constants.WHISPER_SAMPLE_RATE;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    // WHY: Buffer size of ~100ms for responsive audio delivery
    // 16000 samples/sec * 0.1s * 2 bytes/sample = 3200 bytes
    private static final int BUFFER_SIZE_MS = 100;
    private static final int MIN_BUFFER_SIZE = SAMPLE_RATE * BUFFER_SIZE_MS / 1000 * 2;

    private final Context mContext;

    @Nullable
    private AudioRecord mAudioRecord;

    @Nullable
    private HandlerThread mCaptureThread;

    @Nullable
    private Handler mCaptureHandler;

    private volatile boolean mIsCapturing = false;

    @Nullable
    private AudioCallback mCallback;

    // WHY: Reusable buffer to avoid GC during capture
    private short[] mReadBuffer;
    private int mActualBufferSize;

    /**
     * Creates an AudioCaptureManager.
     *
     * @param context Application context for permission checks
     */
    public AudioCaptureManager(@NonNull Context context) {
        mContext = context.getApplicationContext();
    }

    /**
     * Checks if RECORD_AUDIO permission is granted.
     *
     * @return True if permission granted
     */
    public boolean hasAudioPermission() {
        return ContextCompat.checkSelfPermission(mContext, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Initializes AudioRecord. Call before startCapture().
     *
     * <p>PERF: AudioRecord initialization takes ~50-100ms. Call during service init.
     *
     * @return True on success, false if permission denied or hardware unavailable
     */
    public boolean initialize() {
        if (!hasAudioPermission()) {
            Log.e(TAG, "[AudioCaptureManager.initialize] RECORD_AUDIO permission not granted");
            return false;
        }

        // WHY: Calculate optimal buffer size
        int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "[AudioCaptureManager.initialize] Invalid buffer size: " + minBufferSize);
            return false;
        }

        // WHY: Use larger of calculated min and our target buffer
        mActualBufferSize = Math.max(minBufferSize, MIN_BUFFER_SIZE);
        mReadBuffer = new short[mActualBufferSize / 2];  // WHY: Buffer is in bytes, array is shorts

        Log.d(TAG, "[AudioCaptureManager.initialize] Buffer size: " + mActualBufferSize
                + " bytes, " + mReadBuffer.length + " samples");

        try {
            // WHY: VOICE_RECOGNITION (flat response, no AGC) is theoretically ideal for ASR,
            // but on Samsung S24 Ultra it produces audio ~20x quieter than MIC source
            // (maxAmp=0.02 vs ~0.4), causing Whisper to hallucinate on near-silence.
            // MIC with Samsung AGC gives usable signal levels. The 80 Hz HPF in
            // SlidingWindowBuffer handles low-frequency noise that AGC amplifies.
            mAudioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    mActualBufferSize
            );

            if (mAudioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "[AudioCaptureManager.initialize] AudioRecord failed to initialize");
                release();
                return false;
            }

            Log.i(TAG, "[AudioCaptureManager.initialize] AudioRecord initialized. "
                    + "Sample rate: " + SAMPLE_RATE + "Hz");
            return true;

        } catch (SecurityException e) {
            Log.e(TAG, "[AudioCaptureManager.initialize] Security exception: " + e.getMessage(), e);
            return false;
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "[AudioCaptureManager.initialize] Invalid argument: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * Starts audio capture with callback.
     *
     * @param callback Callback for audio delivery
     * @return True if capture started
     */
    public boolean startCapture(@NonNull AudioCallback callback) {
        if (mAudioRecord == null || mAudioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "[AudioCaptureManager.startCapture] AudioRecord not initialized");
            return false;
        }

        if (mIsCapturing) {
            Log.w(TAG, "[AudioCaptureManager.startCapture] Already capturing");
            return true;
        }

        mCallback = callback;

        // WHY: Create dedicated thread for audio capture
        mCaptureThread = new HandlerThread("aria-audio-capture",
                android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
        mCaptureThread.start();
        mCaptureHandler = new Handler(mCaptureThread.getLooper());

        try {
            mAudioRecord.startRecording();
            mIsCapturing = true;

            // WHY: Post capture loop to handler thread
            mCaptureHandler.post(this::captureLoop);

            Log.i(TAG, "[AudioCaptureManager.startCapture] Capture started");
            return true;

        } catch (IllegalStateException e) {
            Log.e(TAG, "[AudioCaptureManager.startCapture] Failed to start: " + e.getMessage(), e);
            stopCapture();
            return false;
        }
    }

    /**
     * Stops audio capture.
     */
    public void stopCapture() {
        mIsCapturing = false;

        if (mAudioRecord != null) {
            try {
                mAudioRecord.stop();
            } catch (IllegalStateException e) {
                Log.w(TAG, "[AudioCaptureManager.stopCapture] Stop failed: " + e.getMessage());
            }
        }

        if (mCaptureThread != null) {
            mCaptureThread.quitSafely();
            try {
                mCaptureThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            mCaptureThread = null;
            mCaptureHandler = null;
        }

        mCallback = null;
        Log.i(TAG, "[AudioCaptureManager.stopCapture] Capture stopped");
    }

    /**
     * Releases all resources.
     */
    public void release() {
        stopCapture();

        if (mAudioRecord != null) {
            mAudioRecord.release();
            mAudioRecord = null;
        }

        mReadBuffer = null;
        Log.i(TAG, "[AudioCaptureManager.release] Released");
    }

    /**
     * Returns whether capture is active.
     */
    public boolean isCapturing() {
        return mIsCapturing;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PRIVATE METHODS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Capture loop running on dedicated thread.
     * Reads audio and delivers to callback until stopped.
     */
    private void captureLoop() {
        Log.d(TAG, "[AudioCaptureManager.captureLoop] Starting capture loop");

        while (mIsCapturing && mAudioRecord != null && mCallback != null) {
            // WHY: Blocking read - thread will wait for audio data
            int samplesRead = mAudioRecord.read(mReadBuffer, 0, mReadBuffer.length);

            if (samplesRead > 0) {
                // WHY: Apply software gain for quiet sources (e.g., laptop speaker demo)
                if (Constants.AUDIO_INPUT_GAIN != 1.0f) {
                    for (int i = 0; i < samplesRead; i++) {
                        int amplified = (int) (mReadBuffer[i] * Constants.AUDIO_INPUT_GAIN);
                        mReadBuffer[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, amplified));
                    }
                }
                // WHY: Deliver audio to callback
                mCallback.onAudioAvailable(mReadBuffer, samplesRead);
            } else if (samplesRead == AudioRecord.ERROR_INVALID_OPERATION) {
                Log.e(TAG, "[AudioCaptureManager.captureLoop] Invalid operation");
                break;
            } else if (samplesRead == AudioRecord.ERROR_BAD_VALUE) {
                Log.e(TAG, "[AudioCaptureManager.captureLoop] Bad value");
                break;
            } else if (samplesRead == AudioRecord.ERROR_DEAD_OBJECT) {
                Log.e(TAG, "[AudioCaptureManager.captureLoop] Dead object");
                break;
            }
            // WHY: samplesRead == 0 is normal during startup, continue
        }

        Log.d(TAG, "[AudioCaptureManager.captureLoop] Capture loop ended");
    }
}
