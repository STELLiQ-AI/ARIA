/**
 * whisper_jni.cpp
 *
 * JNI implementation bridging Java WhisperEngine to whisper.cpp.
 * Handles model loading, transcription, and resource management.
 *
 * Responsibility:
 * - Load whisper GGUF model from file path
 * - Convert Java float[] to native float* for transcription
 * - Return transcript string to Java
 * - Track inference timing
 * - Release native resources
 *
 * Architecture Position:
 * JNI bridge layer. Called from WhisperEngine.java on aria-asr-worker thread.
 *
 * Thread Safety:
 * Single-threaded access enforced by Java synchronized methods.
 * Native context pointer stored in Java field.
 *
 * Air-Gap Compliance:
 * Model loaded from local filesystem. No network calls.
 *
 * PERF: whisper.cpp uses ARM NEON SIMD for CPU inference.
 * Target RTF <= 0.5x on Snapdragon 8 Gen 3.
 *
 * @author STELLiQ Engineering
 * @version 0.1.0
 * @since ARIA Demo Build — 2026-03-02
 */

#include <jni.h>
#include <android/log.h>
#include <string>
#include <chrono>

// WHY: Include whisper.cpp header (from pre-built library)
#include "whisper.h"

// QNN: Include QNN wrapper for HTP NPU encoder acceleration
#ifdef ARIA_USE_QNN
#include "qnn_wrapper.h"
#endif

// ═══════════════════════════════════════════════════════════════════════════
// LOGGING MACROS
// ═══════════════════════════════════════════════════════════════════════════

#define LOG_TAG "ARIA_ASR_JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// WHY: Capture whisper.cpp internal logs to diagnose inference issues
static void whisper_log_callback(enum ggml_log_level level, const char* text, void* user_data) {
    if (text == nullptr) return;
    // Strip trailing newline for logcat
    std::string msg(text);
    while (!msg.empty() && (msg.back() == '\n' || msg.back() == '\r')) msg.pop_back();
    if (msg.empty()) return;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: LOGE("[whisper] %s", msg.c_str()); break;
        case GGML_LOG_LEVEL_WARN:  LOGW("[whisper] %s", msg.c_str()); break;
        default:                   LOGD("[whisper] %s", msg.c_str()); break;
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// ABORT CALLBACK — safety net for stalled inference
// ═══════════════════════════════════════════════════════════════════════════

// WHY: whisper-base.en with 10s window takes ~2.5-3.0s on Snapdragon 8 Gen 3.
// 15s safety net catches true hangs without aborting valid inference.
static constexpr long MAX_INFERENCE_MS = 15000;

struct AbortData {
    std::chrono::steady_clock::time_point startTime;
};

static bool whisper_abort_callback(void* data) {
    auto* abortData = static_cast<AbortData*>(data);
    auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - abortData->startTime).count();
    if (elapsed > MAX_INFERENCE_MS) {
        LOGW("Aborting whisper inference after %lld ms (limit=%ld ms)",
             (long long)elapsed, MAX_INFERENCE_MS);
        return true;
    }
    return false;
}

// ═══════════════════════════════════════════════════════════════════════════
// NATIVE CONTEXT STRUCTURE
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Native context holding whisper model and state.
 * Pointer stored in Java mNativeContext field.
 */
struct WhisperContext {
    struct whisper_context* ctx;
    long lastInferenceMs;
};

// ═══════════════════════════════════════════════════════════════════════════
// HELPER FUNCTIONS
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Gets native context from Java object.
 *
 * @param env JNI environment
 * @param obj Java WhisperEngine instance
 * @return Native context pointer, or nullptr if not initialized
 */
static WhisperContext* getNativeContext(JNIEnv* env, jobject obj) {
    jclass clazz = env->GetObjectClass(obj);
    jfieldID fieldId = env->GetFieldID(clazz, "mNativeContext", "J");
    if (fieldId == nullptr) {
        LOGE("Failed to get mNativeContext field ID");
        return nullptr;
    }
    return reinterpret_cast<WhisperContext*>(env->GetLongField(obj, fieldId));
}

/**
 * Sets native context in Java object.
 *
 * @param env JNI environment
 * @param obj Java WhisperEngine instance
 * @param ctx Native context pointer
 */
static void setNativeContext(JNIEnv* env, jobject obj, WhisperContext* ctx) {
    jclass clazz = env->GetObjectClass(obj);
    jfieldID fieldId = env->GetFieldID(clazz, "mNativeContext", "J");
    if (fieldId != nullptr) {
        env->SetLongField(obj, fieldId, reinterpret_cast<jlong>(ctx));
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// JNI FUNCTIONS
// ═══════════════════════════════════════════════════════════════════════════

extern "C" {

/**
 * Loads whisper model from file.
 *
 * PERF: Takes ~1.5-3.0s on Snapdragon 8 Gen 3. Call at service startup.
 *
 * @param env JNI environment
 * @param obj Java WhisperEngine instance
 * @param modelPath Absolute path to GGUF model file
 * @return 0 on success, negative error code on failure
 */
JNIEXPORT jint JNICALL
Java_com_stelliq_aria_asr_WhisperEngine_nativeLoadModel(
        JNIEnv* env,
        jobject obj,
        jstring modelPath,
        jstring qnnContextPath) {

    // WHY: Convert Java string to native string
    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    if (path == nullptr) {
        LOGE("Failed to get model path string");
        return -1;
    }

    LOGI("Loading whisper model: %s", path);

    // WHY: Register log callback to capture whisper.cpp internal diagnostics
    whisper_log_set(whisper_log_callback, nullptr);

    // WHY: Use whisper_init_from_file_with_params() with proper context params
    auto startTime = std::chrono::steady_clock::now();
    struct whisper_context_params cparams = whisper_context_default_params();
    // WHY: OpenCL produces wrong encoder output on Adreno 750 (all "!" hallucinations).
    // With NPU build, the encoder runs on HTP instead, and the decoder uses CPU ARM NEON.
    cparams.use_gpu = false;
    struct whisper_context* ctx = whisper_init_from_file_with_params(path, cparams);
    auto endTime = std::chrono::steady_clock::now();

    env->ReleaseStringUTFChars(modelPath, path);

    if (ctx == nullptr) {
        LOGE("Failed to load whisper model");
        return -2;
    }

    auto loadMs = std::chrono::duration_cast<std::chrono::milliseconds>(endTime - startTime).count();
    LOGI("Whisper model loaded in %lld ms (GPU=%s)", (long long)loadMs,
         cparams.use_gpu ? "ENABLED" : "DISABLED");
    LOGI("Whisper system info: %s", whisper_print_system_info());
    LOGI("Whisper model type: %s, multilingual: %d, n_vocab: %d, n_audio_ctx: %d",
         whisper_model_type_readable(ctx), whisper_is_multilingual(ctx),
         whisper_model_n_vocab(ctx), whisper_model_n_audio_ctx(ctx));

    // ── QNN HTP Initialization ──────────────────────────────────────────────
    // WHY: Initialize QNN after model load because whisper_ctx_enable_qnn_encoder
    // needs the whisper_context to exist. If any QNN step fails, we continue
    // with CPU-only inference (graceful degradation).
#ifdef ARIA_USE_QNN
    if (qnnContextPath != nullptr) {
        const char* qnnPath = env->GetStringUTFChars(qnnContextPath, nullptr);
        if (qnnPath != nullptr) {
            LOGI("QNN: Initializing HTP backend for NPU encoder acceleration");

            // Step 1: Initialize QNN runtime (dlopen libQnnHtp.so, get providers, create backend)
            bool qnnOk = aria::qnn::initialize();
            if (qnnOk) {
                LOGI("QNN: HTP backend initialized");

                // Step 2: Load pre-compiled V79 context binary (encoder graph)
                qnnOk = aria::qnn::loadContextBinary(qnnPath);
                if (qnnOk) {
                    LOGI("QNN: Context binary loaded: %s", qnnPath);

                    // Step 3: Enable QNN encoder path in whisper.cpp (sets wstate.use_qnn = true)
                    int qnnResult = whisper_ctx_enable_qnn_encoder(ctx);
                    if (qnnResult == 0) {
                        LOGI("QNN: Whisper encoder NPU acceleration ENABLED (V79 HTP)");
                    } else {
                        LOGW("QNN: whisper_ctx_enable_qnn_encoder failed (%d) — CPU fallback", qnnResult);
                    }
                } else {
                    LOGE("QNN: Failed to load context binary — CPU fallback");
                    LOGE("QNN: Check that %s is a valid V79 context binary", qnnPath);
                }
            } else {
                LOGE("QNN: Failed to initialize HTP backend — CPU fallback");
                LOGE("QNN: Check that libQnnHtp.so is loaded and QNN SDK version matches");
            }

            env->ReleaseStringUTFChars(qnnContextPath, qnnPath);
        }
    } else {
        LOGW("QNN: No context binary path provided — CPU-only inference");
    }
#else
    (void)qnnContextPath;  // Suppress unused parameter warning
    LOGI("QNN: Not compiled (ARIA_USE_QNN not defined) — CPU-only inference");
#endif

    // WHY: Allocate native context and store pointer in Java
    WhisperContext* nativeCtx = new WhisperContext();
    nativeCtx->ctx = ctx;
    nativeCtx->lastInferenceMs = 0;

    setNativeContext(env, obj, nativeCtx);

    return 0;
}

/**
 * Transcribes audio data to text.
 *
 * PERF: Target RTF <= 0.5x. 2s audio should transcribe in <= 1s.
 *
 * @param env JNI environment
 * @param obj Java WhisperEngine instance
 * @param audioData Float array of 16kHz PCM, normalized [-1.0, 1.0]
 * @return Transcript string, or null on error
 */
JNIEXPORT jstring JNICALL
Java_com_stelliq_aria_asr_WhisperEngine_nativeTranscribe(
        JNIEnv* env,
        jobject obj,
        jfloatArray audioData,
        jstring initialPrompt) {

    WhisperContext* nativeCtx = getNativeContext(env, obj);
    if (nativeCtx == nullptr || nativeCtx->ctx == nullptr) {
        LOGE("Native context not initialized");
        return nullptr;
    }

    // WHY: Get audio data from Java array
    jsize audioLen = env->GetArrayLength(audioData);
    float* samples = env->GetFloatArrayElements(audioData, nullptr);
    if (samples == nullptr) {
        LOGE("Failed to get audio samples");
        return nullptr;
    }

    LOGD("Transcribing %d samples (%.2fs audio)", audioLen, audioLen / 16000.0f);

    // WHY: Configure full parameters for transcription
    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);

    // PERF: Use 4 threads for whisper-small.en DECODER on Snapdragon 8 Gen 3.
    // WHY: Encoder now runs on Hexagon NPU (V79 HTP), so decoder only needs
    // 4 Cortex-X4 big cores. Remaining cores handle audio capture + UI.
    params.n_threads = 4;

    // WHY: English-only, disable auto-detection (was detecting Afrikaans at 1%)
    params.language = "en";
    params.detect_language = false;
    params.translate = false;

    // WHY: Disable features not needed for streaming ASR
    params.print_special = false;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;

    // WHY: Each 5s window is independent — no cross-window context
    params.no_context = true;
    params.single_segment = false;

    // WHY: Suppress non-speech tokens that cause hallucination
    params.suppress_blank = true;
    params.suppress_nst = true;

    // WHY: Disable whisper.cpp built-in VAD — we use Silero VAD upstream
    params.vad = false;
    params.vad_model_path = nullptr;

    // WHY: initial_prompt biases decoder toward domain vocabulary
    const char* promptStr = nullptr;
    if (initialPrompt != nullptr) {
        promptStr = env->GetStringUTFChars(initialPrompt, nullptr);
        if (promptStr != nullptr) {
            params.initial_prompt = promptStr;
        }
    }

    // WHY: Abort callback caps inference at 5s as safety net
    AbortData abortData;
    abortData.startTime = std::chrono::steady_clock::now();
    params.abort_callback = whisper_abort_callback;
    params.abort_callback_user_data = &abortData;

    // Run inference
    LOGI("Calling whisper_full() with %d samples...", audioLen);
    auto startTime = abortData.startTime;
    int result = whisper_full(nativeCtx->ctx, params, samples, audioLen);
    auto endTime = std::chrono::steady_clock::now();
    LOGI("whisper_full() returned with result=%d", result);

    env->ReleaseFloatArrayElements(audioData, samples, JNI_ABORT);

    if (promptStr != nullptr) {
        env->ReleaseStringUTFChars(initialPrompt, promptStr);
    }

    // Store inference time
    nativeCtx->lastInferenceMs = std::chrono::duration_cast<std::chrono::milliseconds>(
            endTime - startTime).count();

    if (result != 0) {
        // WHY: On abort timeout, return empty string instead of error
        if (nativeCtx->lastInferenceMs >= MAX_INFERENCE_MS) {
            LOGW("Whisper inference aborted after %ld ms", nativeCtx->lastInferenceMs);
            return env->NewStringUTF("");
        }
        LOGE("Whisper inference failed with code: %d", result);
        return nullptr;
    }

    // WHY: Collect all segments into single transcript
    std::string transcript;
    int numSegments = whisper_full_n_segments(nativeCtx->ctx);
    LOGI("whisper_full produced %d segments", numSegments);

    for (int i = 0; i < numSegments; i++) {
        const char* text = whisper_full_get_segment_text(nativeCtx->ctx, i);
        if (text != nullptr) {
            LOGD("  segment[%d]: '%s'", i, text);
            transcript += text;
        } else {
            LOGD("  segment[%d]: (null)", i);
        }
    }

    LOGD("Transcription complete in %ld ms: %d segments, %zu chars",
         nativeCtx->lastInferenceMs, numSegments, transcript.length());

    return env->NewStringUTF(transcript.c_str());
}

/**
 * Releases native resources.
 *
 * @param env JNI environment
 * @param obj Java WhisperEngine instance
 */
JNIEXPORT void JNICALL
Java_com_stelliq_aria_asr_WhisperEngine_nativeRelease(
        JNIEnv* env,
        jobject obj) {

    WhisperContext* nativeCtx = getNativeContext(env, obj);
    if (nativeCtx != nullptr) {
        // QNN: Release QNN resources before whisper context
#ifdef ARIA_USE_QNN
        aria::qnn::release();
        LOGI("QNN resources released");
#endif
        if (nativeCtx->ctx != nullptr) {
            LOGI("Releasing whisper context");
            whisper_free(nativeCtx->ctx);
        }
        delete nativeCtx;
        setNativeContext(env, obj, nullptr);
    }
}

/**
 * Returns last inference duration in milliseconds.
 *
 * @param env JNI environment
 * @param obj Java WhisperEngine instance
 * @return Inference time in ms
 */
JNIEXPORT jlong JNICALL
Java_com_stelliq_aria_asr_WhisperEngine_nativeGetLastInferenceMs(
        JNIEnv* env,
        jobject obj) {

    WhisperContext* nativeCtx = getNativeContext(env, obj);
    if (nativeCtx != nullptr) {
        return nativeCtx->lastInferenceMs;
    }
    return 0;
}

} // extern "C"
