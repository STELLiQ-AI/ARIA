/**
 * SileroVAD.java
 *
 * Voice Activity Detection using Silero VAD v4 ONNX model.
 * Determines if audio contains speech to prevent whisper hallucination.
 *
 * <p>Responsibility:
 * <ul>
 *   <li>Load Silero VAD ONNX model via ONNX Runtime</li>
 *   <li>Run inference on audio chunks</li>
 *   <li>Return speech probability [0.0, 1.0]</li>
 *   <li>Apply configurable threshold for binary decision</li>
 * </ul>
 *
 * <p>Architecture Position:
 * ASR layer filter. Called by SlidingWindowBuffer before WhisperEngine.
 *
 * <p>Thread Safety:
 * Not thread-safe. Call only from aria-asr-worker thread.
 *
 * <p>Air-Gap Compliance:
 * Model loaded from local assets. No network calls.
 *
 * <p>RISK-03: Silero VAD prevents whisper.cpp hallucination on silence.
 * Without VAD, whisper generates "thank you for watching" type outputs.
 *
 * <p>PERF: Silero inference takes <5ms per 512 samples. Negligible overhead.
 *
 * @author STELLiQ Engineering
 * @version 0.2.0
 * @since ARIA Demo Build — 2026-03-02
 */
package com.stelliq.aria.asr;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import com.stelliq.aria.util.Constants;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.Map;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;

public class SileroVAD {

    private static final String TAG = Constants.LOG_TAG_ASR;

    // WHY: With 5s windows, Silero LSTM state drifts across 156 chunks,
    // dampening speech probabilities (observed max 0.134 for clear speech).
    // 0.03 passes all but dead silence. whisper-small.en handles silence
    // well via suppress_blank + suppress_nst, so false positives are cheap.
    private static final float DEFAULT_THRESHOLD = 0.03f;

    // WHY: Silero expects 16kHz sample rate
    private static final int EXPECTED_SAMPLE_RATE = 16000;

    // WHY: Silero window size (512 samples at 16kHz = 32ms)
    private static final int WINDOW_SIZE = 512;

    // WHY: Silero VAD v4 uses 2-layer LSTM with 64 units
    private static final int LSTM_LAYERS = 2;
    private static final int LSTM_UNITS = 64;

    private final Context mContext;
    private float mThreshold;
    private volatile boolean mIsLoaded = false;

    // ONNX Runtime
    private OrtEnvironment mEnv;
    private OrtSession mSession;

    // WHY: Silero VAD maintains internal LSTM state (h, c tensors)
    // Shape: [LSTM_LAYERS, 1, LSTM_UNITS] = [2, 1, 64]
    private float[][][] mHState;
    private float[][][] mCState;

    // WHY: Track which input format this model uses. Silero v5 uses only
    // "input" + "state" (2 inputs), while v4 uses "input", "sr", "h", "c" (4 inputs).
    private boolean mIsV5Format = false;
    // WHY: V5 state tensor shape — discovered at model load time
    private float[] mStateV5;
    private long[] mStateShape;

    /**
     * Creates a SileroVAD instance.
     *
     * @param context Application context for asset access
     */
    public SileroVAD(@NonNull Context context) {
        mContext = context.getApplicationContext();
        mThreshold = DEFAULT_THRESHOLD;
    }

    /**
     * Sets the speech detection threshold.
     *
     * @param threshold Probability threshold [0.0, 1.0]
     */
    public void setThreshold(float threshold) {
        mThreshold = Math.max(0.0f, Math.min(1.0f, threshold));
    }

    /**
     * Returns the current threshold.
     */
    public float getThreshold() {
        return mThreshold;
    }

    /**
     * Loads the Silero VAD model.
     *
     * <p>PERF: Takes ~200-500ms. Call at service startup.
     *
     * @return True on success
     */
    @WorkerThread
    public boolean loadModel() {
        if (mIsLoaded) {
            Log.w(TAG, "[SileroVAD.loadModel] Model already loaded");
            return true;
        }

        Log.i(TAG, "[SileroVAD.loadModel] Loading Silero VAD model via ONNX Runtime");

        try {
            // WHY: Copy model from assets to cache directory for ONNX Runtime
            File modelFile = extractModelFromAssets();
            if (modelFile == null || !modelFile.exists()) {
                Log.e(TAG, "[SileroVAD.loadModel] Failed to extract model from assets");
                return false;
            }

            long startMs = System.currentTimeMillis();

            // Initialize ONNX Runtime session
            mEnv = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            options.setIntraOpNumThreads(Constants.VAD_ONNX_THREADS);
            mSession = mEnv.createSession(modelFile.getAbsolutePath(), options);

            long loadMs = System.currentTimeMillis() - startMs;

            // Discover model input format
            Map<String, NodeInfo> inputInfo = mSession.getInputInfo();
            Map<String, NodeInfo> outputInfo = mSession.getOutputInfo();

            Log.i(TAG, "[SileroVAD.loadModel] Model inputs (" + inputInfo.size() + "):");
            for (Map.Entry<String, NodeInfo> entry : inputInfo.entrySet()) {
                Log.i(TAG, "  input: " + entry.getKey() + " → " + entry.getValue().getInfo());
            }
            Log.i(TAG, "[SileroVAD.loadModel] Model outputs (" + outputInfo.size() + "):");
            for (Map.Entry<String, NodeInfo> entry : outputInfo.entrySet()) {
                Log.i(TAG, "  output: " + entry.getKey() + " → " + entry.getValue().getInfo());
            }

            // WHY: Detect model version based on input count
            // V4 has 4 inputs (input, sr, h, c), V5 has 2 inputs (input, state)
            mIsV5Format = inputInfo.size() < 4;

            if (mIsV5Format) {
                // Discover state tensor shape from model metadata
                // WHY: The ONNX model metadata contains -1 for dynamic (batch) dims.
                // We substitute batch=1 to get a concrete shape for tensor creation.
                for (Map.Entry<String, NodeInfo> entry : inputInfo.entrySet()) {
                    String name = entry.getKey();
                    if (!name.equals("input") && !name.equals("sr")) {
                        TensorInfo ti = (TensorInfo) entry.getValue().getInfo();
                        long[] rawShape = ti.getShape();
                        // Replace -1 (dynamic) dims with 1 (batch size)
                        mStateShape = new long[rawShape.length];
                        int stateSize = 1;
                        for (int i = 0; i < rawShape.length; i++) {
                            mStateShape[i] = rawShape[i] > 0 ? rawShape[i] : 1;
                            stateSize *= (int) mStateShape[i];
                        }
                        mStateV5 = new float[stateSize];
                        Log.i(TAG, "[SileroVAD.loadModel] V5 format detected. State tensor '"
                                + name + "' raw=" + java.util.Arrays.toString(rawShape)
                                + " concrete=" + java.util.Arrays.toString(mStateShape)
                                + " (" + stateSize + " elements)");
                        break;
                    }
                }
                if (mStateV5 == null) {
                    Log.i(TAG, "[SileroVAD.loadModel] Single-input model detected");
                }
            } else {
                // V4 format — initialize LSTM state tensors
                mHState = new float[LSTM_LAYERS][1][LSTM_UNITS];
                mCState = new float[LSTM_LAYERS][1][LSTM_UNITS];
            }
            resetState();

            mIsLoaded = true;
            Log.i(TAG, "[SileroVAD.loadModel] Model loaded in " + loadMs + "ms"
                    + " (format=" + (mIsV5Format ? "v5" : "v4") + ")");
            return true;

        } catch (OrtException e) {
            Log.e(TAG, "[SileroVAD.loadModel] ONNX Runtime error: " + e.getMessage(), e);
            return false;
        } catch (Exception e) {
            Log.e(TAG, "[SileroVAD.loadModel] Failed: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * Detects if audio chunk contains speech.
     *
     * @param audioData Float array of 16kHz PCM, normalized [-1.0, 1.0]
     * @return Speech probability [0.0, 1.0]
     */
    @WorkerThread
    public float detectSpeech(@NonNull float[] audioData) {
        if (!mIsLoaded) {
            Log.e(TAG, "[SileroVAD.detectSpeech] Model not loaded");
            return 0.0f;
        }

        if (audioData.length == 0) {
            return 0.0f;
        }

        // WHY: Process audio in WINDOW_SIZE chunks, return max probability
        float maxProb = 0.0f;

        for (int offset = 0; offset + WINDOW_SIZE <= audioData.length; offset += WINDOW_SIZE) {
            float prob = runInference(audioData, offset, WINDOW_SIZE);
            maxProb = Math.max(maxProb, prob);
        }

        return maxProb;
    }

    /**
     * Detects if audio chunk contains speech (binary decision).
     *
     * @param audioData Float array of 16kHz PCM, normalized [-1.0, 1.0]
     * @return True if speech detected above threshold
     */
    @WorkerThread
    public boolean isSpeech(@NonNull float[] audioData) {
        float prob = detectSpeech(audioData);
        Log.d(TAG, "[SileroVAD.isSpeech] prob=" + String.format("%.4f", prob)
                + " threshold=" + mThreshold + " → " + (prob >= mThreshold ? "SPEECH" : "silence"));
        return prob >= mThreshold;
    }

    /**
     * Resets internal state. Call at start of each recording session.
     */
    public void resetState() {
        if (mStateV5 != null) {
            java.util.Arrays.fill(mStateV5, 0.0f);
        }
        if (mHState != null) {
            for (int l = 0; l < LSTM_LAYERS; l++) {
                java.util.Arrays.fill(mHState[l][0], 0.0f);
            }
        }
        if (mCState != null) {
            for (int l = 0; l < LSTM_LAYERS; l++) {
                java.util.Arrays.fill(mCState[l][0], 0.0f);
            }
        }
        Log.d(TAG, "[SileroVAD.resetState] State reset");
    }

    /**
     * Returns whether the model is loaded.
     */
    public boolean isLoaded() {
        return mIsLoaded;
    }

    /**
     * Releases model resources.
     */
    public void release() {
        if (mSession != null) {
            try {
                mSession.close();
            } catch (OrtException e) {
                Log.w(TAG, "[SileroVAD.release] Session close error: " + e.getMessage());
            }
            mSession = null;
        }
        // WHY: OrtEnvironment is a singleton — do NOT close it, other sessions may use it
        mEnv = null;
        mIsLoaded = false;
        mHState = null;
        mCState = null;
        Log.i(TAG, "[SileroVAD.release] Released");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PRIVATE METHODS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Extracts ONNX model from assets to cache directory.
     */
    private File extractModelFromAssets() throws IOException {
        File cacheDir = mContext.getCacheDir();
        File modelFile = new File(cacheDir, Constants.MODEL_SILERO_VAD);

        // WHY: Skip extraction if file exists and has reasonable size
        if (modelFile.exists() && modelFile.length() > 100000) {
            Log.d(TAG, "[SileroVAD.extractModelFromAssets] Using cached model");
            return modelFile;
        }

        // WHY: Ensure parent directory exists before writing file
        File parentDir = modelFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            if (!parentDir.mkdirs()) {
                throw new IOException("Failed to create directory: " + parentDir.getAbsolutePath());
            }
        }

        Log.d(TAG, "[SileroVAD.extractModelFromAssets] Extracting model from assets");

        try (InputStream is = mContext.getAssets().open(Constants.MODEL_SILERO_VAD);
             FileOutputStream fos = new FileOutputStream(modelFile)) {

            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
            }
        }

        return modelFile;
    }

    /**
     * Runs Silero VAD inference on a single 512-sample audio chunk.
     *
     * <p>Silero VAD v4 ONNX inputs:
     * <ul>
     *   <li>{@code input}  — float32[1, 512] audio samples</li>
     *   <li>{@code sr}     — int64[1] sample rate (16000)</li>
     *   <li>{@code h}      — float32[2, 1, 64] LSTM hidden state</li>
     *   <li>{@code c}      — float32[2, 1, 64] LSTM cell state</li>
     * </ul>
     *
     * <p>Outputs:
     * <ul>
     *   <li>{@code output} — float32[1, 1] speech probability</li>
     *   <li>{@code hn}     — float32[2, 1, 64] updated hidden state</li>
     *   <li>{@code cn}     — float32[2, 1, 64] updated cell state</li>
     * </ul>
     *
     * <p>PERF: <5ms per 512 samples on Snapdragon 8 Gen 3.
     *
     * @param audioData Full audio array
     * @param offset    Start offset in array
     * @param length    Number of samples to process (must be 512)
     * @return Speech probability [0.0, 1.0]
     */
    private float runInference(float[] audioData, int offset, int length) {
        try {
            if (mIsV5Format) {
                return runInferenceV5(audioData, offset, length);
            } else {
                return runInferenceV4(audioData, offset, length);
            }
        } catch (OrtException e) {
            Log.e(TAG, "[SileroVAD.runInference] ONNX inference failed: " + e.getMessage(), e);
            return 0.0f;
        }
    }

    /**
     * V5 format: 2 inputs (input, state) or 1 input (input only).
     */
    /**
     * V5/hybrid format: 3 inputs (input, state, sr).
     * Model: silero_vad_v4.onnx has input=[1,512], state=[2,1,128], sr=scalar(16000)
     */
    private float runInferenceV5(float[] audioData, int offset, int length) throws OrtException {
        // Prepare input audio tensor: shape [1, WINDOW_SIZE]
        float[][] inputAudio = new float[1][WINDOW_SIZE];
        System.arraycopy(audioData, offset, inputAudio[0], 0, length);

        OnnxTensor inputTensor = OnnxTensor.createTensor(mEnv, inputAudio);

        Map<String, OnnxTensor> inputs = new HashMap<>();
        inputs.put("input", inputTensor);

        // WHY: Add sample rate as int64 scalar — required by this model version
        OnnxTensor srTensor = OnnxTensor.createTensor(mEnv, (long) EXPECTED_SAMPLE_RATE);
        inputs.put("sr", srTensor);

        OnnxTensor stateTensor = null;
        if (mStateV5 != null && mStateShape != null) {
            // WHY: Discover state input name (whatever isn't "input" or "sr")
            String stateInputName = null;
            for (String name : mSession.getInputInfo().keySet()) {
                if (!name.equals("input") && !name.equals("sr")) {
                    stateInputName = name;
                    break;
                }
            }
            if (stateInputName != null) {
                // WHY: Use concrete shape (with -1 replaced by 1) not raw metadata shape
                stateTensor = OnnxTensor.createTensor(mEnv,
                        java.nio.FloatBuffer.wrap(mStateV5), mStateShape);
                inputs.put(stateInputName, stateTensor);
            }
        }

        OrtSession.Result result = mSession.run(inputs);

        // Extract speech probability — first output ("output")
        Object outputVal = result.get(0).getValue();
        float probability = extractProbability(outputVal);

        // Update state from second output ("stateN")
        if (mStateV5 != null && result.size() > 1) {
            Object stateVal = result.get(1).getValue();
            float[] newState = flattenFloats(stateVal);
            if (newState != null && newState.length == mStateV5.length) {
                System.arraycopy(newState, 0, mStateV5, 0, mStateV5.length);
            }
        }

        inputTensor.close();
        srTensor.close();
        if (stateTensor != null) stateTensor.close();
        result.close();

        return probability;
    }

    /**
     * V4 format: 4 inputs (input, sr, h, c).
     */
    private float runInferenceV4(float[] audioData, int offset, int length) throws OrtException {
        float[][] inputAudio = new float[1][WINDOW_SIZE];
        System.arraycopy(audioData, offset, inputAudio[0], 0, length);

        long[] sampleRate = new long[]{EXPECTED_SAMPLE_RATE};

        OnnxTensor inputTensor = OnnxTensor.createTensor(mEnv, inputAudio);
        OnnxTensor srTensor = OnnxTensor.createTensor(mEnv, sampleRate);
        OnnxTensor hTensor = OnnxTensor.createTensor(mEnv, mHState);
        OnnxTensor cTensor = OnnxTensor.createTensor(mEnv, mCState);

        Map<String, OnnxTensor> inputs = new HashMap<>();
        inputs.put("input", inputTensor);
        inputs.put("sr", srTensor);
        inputs.put("h", hTensor);
        inputs.put("c", cTensor);

        OrtSession.Result result = mSession.run(inputs);

        float[][] outputProb = (float[][]) result.get(0).getValue();
        float probability = outputProb[0][0];

        float[][][] newH = (float[][][]) result.get(1).getValue();
        float[][][] newC = (float[][][]) result.get(2).getValue();
        for (int l = 0; l < LSTM_LAYERS; l++) {
            System.arraycopy(newH[l][0], 0, mHState[l][0], 0, LSTM_UNITS);
            System.arraycopy(newC[l][0], 0, mCState[l][0], 0, LSTM_UNITS);
        }

        inputTensor.close();
        srTensor.close();
        hTensor.close();
        cTensor.close();
        result.close();

        return probability;
    }

    /**
     * Extracts a scalar probability from various output tensor shapes.
     */
    private float extractProbability(Object val) {
        if (val instanceof float[][]) {
            return ((float[][]) val)[0][0];
        } else if (val instanceof float[]) {
            return ((float[]) val)[0];
        } else if (val instanceof Float) {
            return (Float) val;
        }
        Log.w(TAG, "[SileroVAD.extractProbability] Unknown output type: " + val.getClass());
        return 0.0f;
    }

    /**
     * Flattens a multi-dimensional float array into a 1D array.
     */
    private float[] flattenFloats(Object val) {
        if (val instanceof float[]) {
            return (float[]) val;
        } else if (val instanceof float[][]) {
            float[][] arr2d = (float[][]) val;
            float[] flat = new float[arr2d.length * arr2d[0].length];
            int pos = 0;
            for (float[] row : arr2d) {
                System.arraycopy(row, 0, flat, pos, row.length);
                pos += row.length;
            }
            return flat;
        } else if (val instanceof float[][][]) {
            float[][][] arr3d = (float[][][]) val;
            int total = arr3d.length * arr3d[0].length * arr3d[0][0].length;
            float[] flat = new float[total];
            int pos = 0;
            for (float[][] plane : arr3d) {
                for (float[] row : plane) {
                    System.arraycopy(row, 0, flat, pos, row.length);
                    pos += row.length;
                }
            }
            return flat;
        }
        return null;
    }
}
