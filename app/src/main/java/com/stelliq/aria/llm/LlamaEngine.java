/**
 * LlamaEngine.java
 *
 * JNI bridge to llama.cpp for on-device LLM inference.
 * Manages Llama 3.1 8B Instruct model lifecycle and provides streaming completion interface.
 *
 * <p>Responsibility:
 * <ul>
 *   <li>Load llama.cpp model with OpenCL GPU backend</li>
 *   <li>Provide streaming token completion interface</li>
 *   <li>Track TTFT and decode speed for performance logging</li>
 *   <li>Handle CPU fallback if GPU initialization fails</li>
 *   <li>This class does NOT build prompts — that's AARPromptBuilder's job</li>
 * </ul>
 *
 * <p>Architecture Position:
 * LLM layer engine. Owned by ARIASessionService. Called from aria-llm-worker thread.
 *
 * <p>Thread Safety:
 * complete() is synchronized — single inference at a time. Model state protected.
 *
 * <p>Air-Gap Compliance:
 * Model loaded from local externalFilesDir. No network calls during inference.
 *
 * <p>RISK: loadModel() includes OpenCL context initialization (2-4s cold start).
 * Pre-warm at service onCreate(), not during user action.
 *
 * @author STELLiQ Engineering
 * @version 0.1.0
 * @since ARIA Demo Build — 2026-03-02
 */
package com.stelliq.aria.llm;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import com.stelliq.aria.util.Constants;

import java.util.concurrent.atomic.AtomicBoolean;

public class LlamaEngine {

    private static final String TAG = Constants.LOG_TAG_LLM;

    /**
     * Callback interface for streaming token delivery.
     */
    public interface TokenCallback {
        /**
         * Called for each decoded token.
         *
         * @param token  Decoded string token (may be partial UTF-8)
         * @param isDone True on final token or EOS
         */
        void onToken(@NonNull String token, boolean isDone);
    }

    // Load native library
    static {
        try {
            System.loadLibrary(Constants.JNI_LIB_LLM);
            Log.i(TAG, "[LlamaEngine] Native library loaded: " + Constants.JNI_LIB_LLM);
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "[LlamaEngine] Failed to load native library: " + e.getMessage(), e);
        }
    }

    // Native context pointer (managed by JNI)
    private long mNativeContext = 0;
    private volatile boolean mIsLoaded = false;
    private boolean mIsGpuMode = false;

    // Performance metrics from last inference
    private long mLastTtftMs = 0;
    private float mLastDecodeTokensPerSec = 0;
    private long mLastPeakRamBytes = 0;
    private int mLastTokenCount = 0;

    // WHY: Cancellation support for long-running inferences
    // The AtomicBoolean is passed to native code to check between tokens
    private final AtomicBoolean mCancellationRequested = new AtomicBoolean(false);

    // ═══════════════════════════════════════════════════════════════════════════
    // NATIVE METHODS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Validates GGUF file header without loading the model.
     * Checks file accessibility, magic bytes, version, and metadata count.
     *
     * @param modelPath Absolute path to GGUF model file
     * @return 0 on success, negative error code on failure
     */
    private native int nativeValidateGgufFile(String modelPath);

    /**
     * Loads GGUF model and initializes context. BLOCKING — call on background thread.
     *
     * @param modelPath   Absolute path to GGUF model file
     * @param nGpuLayers  Number of layers to offload to GPU (32 for full, 0 for CPU)
     * @param contextSize Context window in tokens (4096 recommended)
     * @return 0 on success, negative error code on failure
     */
    private native int nativeLoadModel(String modelPath, int nGpuLayers, int contextSize);

    /**
     * Runs completion with streaming callback. BLOCKING — call on background thread.
     *
     * @param prompt   Full prompt string
     * @param callback Token callback for streaming output
     * @return 0 on success, negative error code on failure
     */
    private native int nativeComplete(String prompt, TokenCallback callback);

    /**
     * Releases native resources.
     */
    private native void nativeRelease();

    /**
     * Returns last TTFT in milliseconds.
     */
    private native long nativeGetLastTtftMs();

    /**
     * Returns last decode speed in tokens per second.
     */
    private native float nativeGetLastDecodeTokensPerSec();

    /**
     * Returns last peak RAM usage in bytes.
     */
    private native long nativeGetLastPeakRamBytes();

    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Validates GGUF file header without loading the full model.
     * Use this to check file accessibility before committing to the
     * expensive (and potentially crash-prone) model load.
     *
     * @param modelPath Absolute path to GGUF model file
     * @return 0 if valid, negative error code on failure
     */
    public int validateGgufFile(@NonNull String modelPath) {
        Log.i(TAG, "[LlamaEngine.validateGgufFile] Validating: " + modelPath);
        int result = nativeValidateGgufFile(modelPath);
        if (result == 0) {
            Log.i(TAG, "[LlamaEngine.validateGgufFile] GGUF file is valid");
        } else {
            Log.e(TAG, "[LlamaEngine.validateGgufFile] Validation failed: " + result);
        }
        return result;
    }

    /**
     * Loads the LLM model.
     *
     * <p>PERF: OpenCL cold start takes 2-4s (SPIR-V kernel compilation).
     * Pre-warm at service startup — never during user action.
     *
     * @param modelPath   Absolute path to Llama 3.1 8B Instruct GGUF
     * @param nGpuLayers  32 for full GPU offload, 0 for CPU-only
     * @param contextSize Context window (4096 recommended)
     * @return 0 on success, negative error code on failure
     */
    @WorkerThread
    public synchronized int loadModel(@NonNull String modelPath, int nGpuLayers, int contextSize) {
        if (mIsLoaded) {
            Log.w(TAG, "[LlamaEngine.loadModel] Model already loaded");
            return 0;
        }

        Log.i(TAG, "[LlamaEngine.loadModel] Loading model: " + modelPath
                + " nGpuLayers=" + nGpuLayers + " contextSize=" + contextSize);
        long startTime = System.currentTimeMillis();

        int result = nativeLoadModel(modelPath, nGpuLayers, contextSize);

        long duration = System.currentTimeMillis() - startTime;
        if (result == 0) {
            mIsLoaded = true;
            mIsGpuMode = nGpuLayers > 0;
            Log.i(TAG, "[LlamaEngine.loadModel] Model loaded in " + duration + "ms"
                    + " (GPU=" + mIsGpuMode + ")");
        } else {
            Log.e(TAG, "[LlamaEngine.loadModel] Load failed with code: " + result);
        }

        return result;
    }

    /**
     * Runs streaming completion on the prompt.
     *
     * <p>PERF: Target TTFT ≤2.0s, decode ≥20 tok/s on Adreno 830.
     *
     * @param prompt   Full formatted prompt (see AARPromptBuilder)
     * @param callback Token callback for streaming delivery
     * @return 0 on success, negative error code on failure
     * @throws IllegalStateException if model not loaded
     */
    @WorkerThread
    public synchronized int complete(@NonNull String prompt, @NonNull TokenCallback callback) {
        if (!mIsLoaded) {
            Log.e(TAG, "[LlamaEngine.complete] Model not loaded");
            return -1;
        }

        // WHY: Clear any previous cancellation request
        clearCancellation();

        Log.i(TAG, "[LlamaEngine.complete] Starting inference. Prompt length: " + prompt.length());

        // Wrap callback to track token count
        final int[] tokenCount = {0};
        TokenCallback wrappedCallback = (token, isDone) -> {
            tokenCount[0]++;
            callback.onToken(token, isDone);
        };

        int result = nativeComplete(prompt, wrappedCallback);

        if (result == -6) {
            Log.i(TAG, "[LlamaEngine.complete] Inference cancelled by user");
        }

        // Retrieve metrics from native layer
        mLastTtftMs = nativeGetLastTtftMs();
        mLastDecodeTokensPerSec = nativeGetLastDecodeTokensPerSec();
        mLastPeakRamBytes = nativeGetLastPeakRamBytes();
        mLastTokenCount = tokenCount[0];

        Log.i(TAG, "[LlamaEngine.complete] Finished. tokens=" + mLastTokenCount
                + " ttft=" + mLastTtftMs + "ms"
                + " tps=" + mLastDecodeTokensPerSec);

        return result;
    }

    /**
     * Returns whether the model is loaded and ready.
     */
    public boolean isLoaded() {
        return mIsLoaded;
    }

    /**
     * Returns whether GPU mode is active.
     */
    public boolean isGpuMode() {
        return mIsGpuMode;
    }

    /**
     * Releases native resources. Safe to call multiple times.
     */
    public synchronized void release() {
        if (mIsLoaded) {
            Log.i(TAG, "[LlamaEngine.release] Releasing native resources");
            nativeRelease();
            mIsLoaded = false;
            mIsGpuMode = false;
            mNativeContext = 0;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PERFORMANCE METRICS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Returns last TTFT (time to first token) in milliseconds.
     */
    public long getLastTtftMs() {
        return mLastTtftMs;
    }

    /**
     * Returns last decode speed in tokens per second.
     */
    public float getLastDecodeTokensPerSec() {
        return mLastDecodeTokensPerSec;
    }

    /**
     * Returns last peak RAM usage in bytes.
     */
    public long getLastPeakRamBytes() {
        return mLastPeakRamBytes;
    }

    /**
     * Returns the number of tokens generated in last completion.
     */
    public int getLastTokenCount() {
        return mLastTokenCount;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CANCELLATION SUPPORT
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Requests cancellation of ongoing inference.
     *
     * <p>The current token generation will complete, then inference stops.
     * The complete() method will return with partial results.
     *
     * <p>Thread-safe: Can be called from any thread while inference is running.
     */
    public void requestCancellation() {
        mCancellationRequested.set(true);
        Log.i(TAG, "[LlamaEngine.requestCancellation] Cancellation requested");
    }

    /**
     * Checks whether cancellation was requested.
     *
     * <p>Used by native code to check between tokens.
     *
     * @return True if cancellation has been requested
     */
    public boolean isCancellationRequested() {
        return mCancellationRequested.get();
    }

    /**
     * Clears cancellation flag for new inference.
     *
     * <p>Called internally at the start of complete().
     */
    private void clearCancellation() {
        mCancellationRequested.set(false);
    }
}
