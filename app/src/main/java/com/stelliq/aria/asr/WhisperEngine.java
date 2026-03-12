/**
 * WhisperEngine.java
 *
 * JNI bridge to whisper.cpp for on-device speech recognition.
 * Manages model lifecycle and provides transcription interface.
 *
 * <p>Responsibility:
 * <ul>
 *   <li>Load whisper.cpp model via JNI</li>
 *   <li>Provide thread-safe transcription interface</li>
 *   <li>Track inference timing for performance logging</li>
 *   <li>Implement hallucination detection</li>
 *   <li>This class does NOT handle audio capture — that's AudioCaptureManager's job</li>
 * </ul>
 *
 * <p>Architecture Position:
 * ASR layer engine. Owned by ARIASessionService. Called from aria-asr-worker thread.
 *
 * <p>Thread Safety:
 * transcribe() is synchronized — single inference at a time. Model state protected.
 *
 * <p>Air-Gap Compliance:
 * Model loaded from local filesDir. No network calls.
 *
 * <p>RISK: loadModel() and transcribe() are BLOCKING native calls.
 * Never call on main thread — will cause ANR.
 *
 * @author STELLiQ Engineering
 * @version 0.1.0
 * @since ARIA Demo Build — 2026-03-02
 */
package com.stelliq.aria.asr;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.stelliq.aria.util.Constants;

import java.util.regex.Pattern;

public class WhisperEngine {

    private static final String TAG = Constants.LOG_TAG_ASR;

    /**
     * Pattern for detecting whisper.cpp hallucination markers.
     * Matches: [BLANK_AUDIO], (Music), (Applause), etc.
     */
    private static final Pattern HALLUCINATION_PATTERN =
            Pattern.compile("^\\s*([\\[\\(].*[\\]\\)]|[.!?,;:\\s]+)\\s*$");

    /**
     * Common hallucination phrases that whisper outputs on silence.
     */
    private static final String[] HALLUCINATION_PHRASES = {
            "thank you for watching",
            "thanks for watching",
            "subscribe",
            "like and subscribe",
            "see you next time",
            "bye bye",
            "music",
            "applause"
    };

    // QNN: Load native libraries in MANDATORY order (QD-2).
    // WHY: QnnHtpPrepare must load before QnnHtp (dependency), and both must
    // load before asr_jni (which dlopen's QnnHtp at runtime). Wrong order =
    // silent CPU fallback with delegation count = 0, NO error thrown.
    static {
        // WHY: libcdsprpc.so is Qualcomm's FastRPC library for DSP communication.
        // It must be loaded from /vendor/lib64/ (via public.libraries-qti.txt) BEFORE
        // the QNN HTP stub, otherwise the stub can't establish transport to the Hexagon DSP.
        // Bundling it in jniLibs breaks because it has transitive vendor dependencies
        // (libhidlbase.so, etc.) that aren't resolvable from the app's linker namespace.
        try {
            System.loadLibrary("cdsprpc");
            Log.i(TAG, "[WhisperEngine] Loaded: cdsprpc (vendor FastRPC)");
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, "[WhisperEngine] cdsprpc not found — DSP transport may fail: " + e.getMessage());
        }
        try {
            System.loadLibrary(Constants.JNI_LIB_QNN_HTP_PREPARE);  // 1st
            Log.i(TAG, "[WhisperEngine] Loaded: " + Constants.JNI_LIB_QNN_HTP_PREPARE);
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, "[WhisperEngine] QnnHtpPrepare not found — NPU acceleration unavailable: " + e.getMessage());
        }
        try {
            System.loadLibrary(Constants.JNI_LIB_QNN_HTP);          // 2nd
            Log.i(TAG, "[WhisperEngine] Loaded: " + Constants.JNI_LIB_QNN_HTP);
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, "[WhisperEngine] QnnHtp not found — NPU acceleration unavailable: " + e.getMessage());
        }
        try {
            System.loadLibrary(Constants.JNI_LIB_ASR);              // 3rd — MUST be last
            Log.i(TAG, "[WhisperEngine] Loaded: " + Constants.JNI_LIB_ASR);
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "[WhisperEngine] Failed to load native library: " + e.getMessage(), e);
        }
    }

    // Native context pointer (managed by JNI)
    private long mNativeContext = 0;
    private volatile boolean mIsLoaded = false;
    private long mLastInferenceMs = 0;

    // ═══════════════════════════════════════════════════════════════════════════
    // NATIVE METHODS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Loads whisper model from file. BLOCKING — call on background thread.
     *
     * @param modelPath Absolute path to GGUF model file
     * @param qnnContextPath Absolute path to QNN V79 context binary, or null for CPU-only
     * @return 0 on success, negative error code on failure
     */
    private native int nativeLoadModel(String modelPath, String qnnContextPath);

    /**
     * Transcribes audio data. BLOCKING — call on background thread.
     *
     * @param audioData Float array of 16kHz PCM, normalized [-1.0, 1.0]
     * @return Transcript string, or null on error
     */
    private native String nativeTranscribe(float[] audioData, String initialPrompt);

    /**
     * Releases native resources.
     */
    private native void nativeRelease();

    /**
     * Returns last inference duration in milliseconds.
     */
    private native long nativeGetLastInferenceMs();

    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Loads the whisper model with QNN HTP NPU acceleration.
     *
     * <p>PERF: Takes ~1.5-3.0s on Snapdragon 8 Gen 3. Call at service startup.
     *
     * @param modelPath Absolute path to GGUF model (e.g., ggml-small.en-q8_0.gguf)
     * @param qnnContextPath Absolute path to QNN V79 context binary, or null for CPU-only
     * @return 0 on success, negative error code on failure
     * @throws IllegalStateException if model already loaded
     */
    @WorkerThread
    public synchronized int loadModel(@NonNull String modelPath, @Nullable String qnnContextPath) {
        if (mIsLoaded) {
            Log.w(TAG, "[WhisperEngine.loadModel] Model already loaded");
            return 0;
        }

        Log.i(TAG, "[WhisperEngine.loadModel] Loading model: " + modelPath);
        if (qnnContextPath != null) {
            Log.i(TAG, "[WhisperEngine.loadModel] QNN context binary: " + qnnContextPath);
        } else {
            // WHY: CPU-only fallback should be logged as warning — NPU build expects QNN
            Log.w(TAG, "[WhisperEngine.loadModel] No QNN context path — CPU-only inference");
        }
        long startTime = System.currentTimeMillis();

        int result = nativeLoadModel(modelPath, qnnContextPath);

        long duration = System.currentTimeMillis() - startTime;
        if (result == 0) {
            mIsLoaded = true;
            Log.i(TAG, "[WhisperEngine.loadModel] Model loaded in " + duration + "ms");
        } else {
            Log.e(TAG, "[WhisperEngine.loadModel] Load failed with code: " + result);
        }

        return result;
    }

    // WHY: Domain vocabulary prompt biases Whisper's decoder toward military/AAR terms
    private static final String INITIAL_PROMPT =
            "AAR, OPORD, FRAGO, WARNO, COP, TOC, SITREP, MEDEVAC, QRF, IED, "
            + "RPG, MRAP, humvee, platoon, squad, battalion, brigade, "
            + "grid coordinates, phase line, objective, rally point";

    /**
     * Transcribes audio data to text.
     *
     * <p>PERF: Target RTF ≤ 0.5x. 2s audio should transcribe in ≤1s.
     *
     * @param audioData Float array of 16kHz PCM, normalized to [-1.0, 1.0]
     * @return Transcript text, empty string for no speech, null on error
     * @throws IllegalStateException if model not loaded
     */
    @WorkerThread
    @Nullable
    public synchronized String transcribe(@NonNull float[] audioData) {
        if (!mIsLoaded) {
            Log.e(TAG, "[WhisperEngine.transcribe] Model not loaded");
            return null;
        }

        if (audioData.length == 0) {
            return "";
        }

        // PERF: This is a blocking native call
        long startTime = System.currentTimeMillis();
        String result = nativeTranscribe(audioData, INITIAL_PROMPT);
        mLastInferenceMs = System.currentTimeMillis() - startTime;

        if (result != null) {
            result = result.trim();
            Log.d(TAG, "[WhisperEngine.transcribe] Output (" + mLastInferenceMs + "ms): "
                    + (result.length() > 50 ? result.substring(0, 50) + "..." : result));
        }

        return result;
    }

    /**
     * Returns the last inference duration in milliseconds.
     * Call immediately after transcribe() for accurate timing.
     */
    public long getLastInferenceMs() {
        return mLastInferenceMs;
    }

    /**
     * Returns whether the model is loaded and ready.
     */
    public boolean isLoaded() {
        return mIsLoaded;
    }

    /**
     * Releases native resources. Safe to call multiple times.
     */
    public synchronized void release() {
        if (mIsLoaded) {
            Log.i(TAG, "[WhisperEngine.release] Releasing native resources");
            nativeRelease();
            mIsLoaded = false;
            mNativeContext = 0;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HALLUCINATION DETECTION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Checks if transcript text is likely a whisper hallucination.
     *
     * <p>WHY: whisper.cpp generates text on silence without VAD. This includes:
     * <ul>
     *   <li>Bracketed markers like [BLANK_AUDIO], (Music)</li>
     *   <li>Repeated punctuation or whitespace</li>
     *   <li>Common YouTube outros from training data</li>
     * </ul>
     *
     * @param text Transcript text to check
     * @return True if text appears to be hallucination
     */
    public static boolean isHallucination(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            return true;  // Empty output is effectively hallucination
        }

        String trimmed = text.trim().toLowerCase();

        // Check for bracketed/parenthesized markers
        if (HALLUCINATION_PATTERN.matcher(trimmed).matches()) {
            Log.d(TAG, "[WhisperEngine.isHallucination] Pattern match: " + text);
            return true;
        }

        // Check for known hallucination phrases
        for (String phrase : HALLUCINATION_PHRASES) {
            if (trimmed.contains(phrase)) {
                Log.d(TAG, "[WhisperEngine.isHallucination] Phrase match: " + text);
                return true;
            }
        }

        // Check for very short output (likely noise)
        if (trimmed.length() < 3) {
            return true;
        }

        return false;
    }
}
