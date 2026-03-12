/**
 * llama_jni.cpp
 *
 * JNI implementation bridging Java LlamaEngine to llama.cpp.
 * Handles model loading, streaming completion, and resource management.
 *
 * Responsibility:
 * - Load Llama 3.1 7B Instruct GGUF model with OpenCL GPU backend
 * - Provide streaming token callback to Java
 * - Track TTFT and decode speed for performance logging
 * - Handle CPU fallback if GPU initialization fails
 * - Release native resources
 *
 * Architecture Position:
 * JNI bridge layer. Called from LlamaEngine.java on aria-llm-worker thread.
 *
 * Thread Safety:
 * Single-threaded access enforced by Java synchronized methods.
 * Native context pointer stored in Java field.
 *
 * Air-Gap Compliance:
 * Model loaded from local filesystem. No network calls during inference.
 *
 * PERF: llama.cpp uses OpenCL for GPU inference on Adreno 750.
 * Target TTFT <= 2.0s, decode >= 20 tok/s.
 *
 * RISK-02: OpenCL cold start triggers kernel JIT compilation (~15-20s).
 * Mitigated by pre-warming kernels at model load time (see nativeLoadModel).
 *
 * @author STELLiQ Engineering
 * @version 0.1.0
 * @since ARIA Demo Build — 2026-03-02
 */

#include <jni.h>
#include <android/log.h>
#include <string>
#include <chrono>
#include <vector>
#include <cstdio>
#include <thread>

// WHY: Include llama.cpp headers (from pre-built library)
#include "llama.h"

// ═══════════════════════════════════════════════════════════════════════════
// LOGGING MACROS
// ═══════════════════════════════════════════════════════════════════════════

#define LOG_TAG "ARIA_LLM_JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// ═══════════════════════════════════════════════════════════════════════════
// CONSTANTS
// ═══════════════════════════════════════════════════════════════════════════

// WHY: Match Java Constants.java values
static const int DEFAULT_CONTEXT_SIZE = 4096;
static const int MAX_OUTPUT_TOKENS = 512;

// ═══════════════════════════════════════════════════════════════════════════
// NATIVE CONTEXT STRUCTURE
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Native context holding llama model and state.
 * Pointer stored in Java mNativeContext field.
 */
struct LlamaContext {
    struct llama_model* model;
    struct llama_context* ctx;
    struct llama_sampler* sampler;
    long lastTtftMs;
    float lastDecodeTokensPerSec;
    long lastPeakRamBytes;
    bool isGpuMode;
};

// ═══════════════════════════════════════════════════════════════════════════
// HELPER FUNCTIONS
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Gets native context from Java object.
 */
static LlamaContext* getNativeContext(JNIEnv* env, jobject obj) {
    jclass clazz = env->GetObjectClass(obj);
    jfieldID fieldId = env->GetFieldID(clazz, "mNativeContext", "J");
    if (fieldId == nullptr) {
        LOGE("Failed to get mNativeContext field ID");
        return nullptr;
    }
    return reinterpret_cast<LlamaContext*>(env->GetLongField(obj, fieldId));
}

/**
 * Sets native context in Java object.
 */
static void setNativeContext(JNIEnv* env, jobject obj, LlamaContext* ctx) {
    jclass clazz = env->GetObjectClass(obj);
    jfieldID fieldId = env->GetFieldID(clazz, "mNativeContext", "J");
    if (fieldId != nullptr) {
        env->SetLongField(obj, fieldId, reinterpret_cast<jlong>(ctx));
    }
}

/**
 * Gets current memory usage (approximate).
 */
static long getMemoryUsage() {
    // WHY: Simple approximation — Android doesn't expose precise native heap
    // In production, would use mallinfo() or /proc/self/statm
    return 0;
}

/**
 * Adds a token to the batch (inline helper for older llama.cpp API compatibility).
 * WHY: llama_batch_add() is not exported by this library version, so we inline it.
 *
 * @param batch The batch to add to
 * @param id Token ID
 * @param pos Position in sequence
 * @param seq_id Sequence ID (typically 0)
 * @param logits Whether to compute logits for this token
 */
static void batch_add_token(llama_batch& batch, llama_token id, llama_pos pos,
                            llama_seq_id seq_id, bool logits) {
    batch.token[batch.n_tokens] = id;
    batch.pos[batch.n_tokens] = pos;
    batch.n_seq_id[batch.n_tokens] = 1;
    batch.seq_id[batch.n_tokens][0] = seq_id;
    batch.logits[batch.n_tokens] = logits;
    batch.n_tokens++;
}

// ═══════════════════════════════════════════════════════════════════════════
// JNI FUNCTIONS
// ═══════════════════════════════════════════════════════════════════════════

// WHY: Custom log callback to capture llama.cpp internal logging
// This helps debug crashes in model loading by showing the last log message
static void aria_llama_log_callback(enum ggml_log_level level, const char* text, void* user_data) {
    (void)user_data;
    if (text == nullptr) return;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR:
            LOGE("llama.cpp: %s", text);
            break;
        case GGML_LOG_LEVEL_WARN:
            LOGW("llama.cpp: %s", text);
            break;
        default:
            LOGD("llama.cpp: %s", text);
            break;
    }
}

extern "C" {

/**
 * Validates GGUF file header without loading the model.
 * Reads magic bytes, version, and metadata count to verify file accessibility.
 *
 * @return 0 on success, negative error code on failure
 */
JNIEXPORT jint JNICALL
Java_com_stelliq_aria_llm_LlamaEngine_nativeValidateGgufFile(
        JNIEnv* env,
        jobject obj,
        jstring modelPath) {

    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    if (path == nullptr) {
        LOGE("validateGguf: Failed to get path string");
        return -1;
    }

    LOGI("validateGguf: Checking file: %s", path);

    // WHY: Open file and read GGUF header manually to verify accessibility
    FILE* f = fopen(path, "rb");
    if (f == nullptr) {
        LOGE("validateGguf: Cannot open file (errno=%d)", errno);
        env->ReleaseStringUTFChars(modelPath, path);
        return -2;
    }

    // Check file size
    fseek(f, 0, SEEK_END);
    long fileSize = ftell(f);
    fseek(f, 0, SEEK_SET);
    LOGI("validateGguf: File size = %ld bytes", fileSize);

    if (fileSize < 20) {
        LOGE("validateGguf: File too small (%ld bytes)", fileSize);
        fclose(f);
        env->ReleaseStringUTFChars(modelPath, path);
        return -3;
    }

    // Read GGUF header: magic (4 bytes) + version (4 bytes) + n_tensors (8 bytes) + n_kv (8 bytes)
    // Total: 24 bytes for GGUF v3 header
    uint8_t header[24];
    size_t bytesRead = fread(header, 1, sizeof(header), f);
    fclose(f);

    if (bytesRead < 24) {
        LOGE("validateGguf: Could not read header (got %zu bytes)", bytesRead);
        env->ReleaseStringUTFChars(modelPath, path);
        return -4;
    }

    // Check magic: "GGUF" = G(0x47) G(0x47) U(0x55) F(0x46) → LE uint32 = 0x46554747
    uint32_t magic = *(uint32_t*)header;
    if (magic != 0x46554747) {
        LOGE("validateGguf: Bad magic: 0x%08X (expected 0x46475547)", magic);
        env->ReleaseStringUTFChars(modelPath, path);
        return -5;
    }

    uint32_t version = *(uint32_t*)(header + 4);
    uint64_t n_tensors = *(uint64_t*)(header + 8);
    uint64_t n_kv = *(uint64_t*)(header + 16);

    LOGI("validateGguf: GGUF v%u, %llu tensors, %llu KV entries",
         version, (unsigned long long)n_tensors, (unsigned long long)n_kv);

    if (version < 2 || version > 3) {
        LOGE("validateGguf: Unsupported GGUF version %u", version);
        env->ReleaseStringUTFChars(modelPath, path);
        return -6;
    }

    // Sanity check metadata count
    if (n_kv > 10000 || n_tensors > 10000) {
        LOGE("validateGguf: Suspicious counts (n_kv=%llu, n_tensors=%llu)",
             (unsigned long long)n_kv, (unsigned long long)n_tensors);
        env->ReleaseStringUTFChars(modelPath, path);
        return -7;
    }

    LOGI("validateGguf: File looks valid");
    env->ReleaseStringUTFChars(modelPath, path);
    return 0;
}

/**
 * Loads GGUF model and initializes context.
 *
 * RISK-02: OpenCL cold start takes 2-4s (SPIR-V kernel compilation).
 * Pre-warm at service onCreate(), not during user action.
 *
 * @param env JNI environment
 * @param obj Java LlamaEngine instance
 * @param modelPath Absolute path to GGUF model file
 * @param nGpuLayers Number of layers to offload to GPU (32 for full, 0 for CPU)
 * @param contextSize Context window in tokens (4096 recommended)
 * @return 0 on success, negative error code on failure
 */
JNIEXPORT jint JNICALL
Java_com_stelliq_aria_llm_LlamaEngine_nativeLoadModel(
        JNIEnv* env,
        jobject obj,
        jstring modelPath,
        jint nGpuLayers,
        jint contextSize) {

    // WHY: Set custom log callback to capture llama.cpp internal messages
    // This helps debug crashes by showing the last log message before SIGSEGV
    llama_log_set(aria_llama_log_callback, nullptr);

    // Initialize llama backend
    llama_backend_init();

    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    if (path == nullptr) {
        LOGE("Failed to get model path string");
        return -1;
    }

    LOGI("Loading llama model: %s (nGpuLayers=%d, contextSize=%d)",
         path, nGpuLayers, contextSize);

    auto startTime = std::chrono::steady_clock::now();

    // WHY: Configure model parameters
    struct llama_model_params modelParams = llama_model_default_params();

    // RISK-07: GPU to CPU fallback
    // WHY: Set GPU layers — 0 for CPU-only, 32+ for full GPU offload
    modelParams.n_gpu_layers = nGpuLayers;

    // Load model
    struct llama_model* model = llama_model_load_from_file(path, modelParams);

    env->ReleaseStringUTFChars(modelPath, path);

    if (model == nullptr) {
        LOGE("Failed to load llama model");
        return -2;
    }

    // WHY: Configure context parameters
    struct llama_context_params ctxParams = llama_context_default_params();
    ctxParams.n_ctx = contextSize;
    ctxParams.n_batch = 128;    // WHY: Match PROMPT_BATCH_SIZE. 512 wastes memory allocating buffers
                                // we never fill — actual prompt batches are 128 tokens max.
                                // Smaller batches also yield GPU back to HWUI sooner during prefill.
    ctxParams.n_ubatch = 128;   // WHY: Match n_batch — no benefit to larger micro-batch
    ctxParams.n_threads = 4;    // WHY: 4 threads optimal for Snapdragon 8 Gen 3
    ctxParams.n_threads_batch = 4;
    ctxParams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;  // WHY: Fused attention kernels reduce GPU memory bandwidth

    // Create context
    struct llama_context* ctx = llama_init_from_model(model, ctxParams);
    if (ctx == nullptr) {
        LOGE("Failed to create llama context");
        llama_model_free(model);
        return -3;
    }

    // Create sampler chain for generation
    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    struct llama_sampler* sampler = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.1f));  // WHY: Low temp for deterministic AAR summaries (matches Constants.LLM_TEMPERATURE)
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    // ─── PRE-WARM OPENCL KERNELS ──────────────────────────────────────────────
    // WHY: First llama_decode() triggers OpenCL kernel JIT compilation (~15-20s).
    // By running a dummy decode here during model load (background thread),
    // we pay that cost once. All subsequent inferences see warm kernels.
    // This moves ~18s from user-facing TTFT to background model initialization.
    if (nGpuLayers > 0) {
        LOGI("Pre-warming OpenCL kernels...");
        auto warmStart = std::chrono::steady_clock::now();

        const struct llama_vocab* warmVocab = llama_model_get_vocab(model);
        llama_batch warmBatch = llama_batch_init(1, 0, 1);
        batch_add_token(warmBatch, llama_vocab_bos(warmVocab), 0, 0, true);

        int warmResult = llama_decode(ctx, warmBatch);
        llama_batch_free(warmBatch);

        // Clear KV cache so warm-up doesn't pollute real inference
        llama_memory_clear(llama_get_memory(ctx), true);

        auto warmEnd = std::chrono::steady_clock::now();
        auto warmMs = std::chrono::duration_cast<std::chrono::milliseconds>(warmEnd - warmStart).count();
        LOGI("Kernel pre-warm %s in %lld ms",
             warmResult == 0 ? "completed" : "failed", (long long)warmMs);
    }
    // ──────────────────────────────────────────────────────────────────────────

    auto endTime = std::chrono::steady_clock::now();
    auto loadMs = std::chrono::duration_cast<std::chrono::milliseconds>(endTime - startTime).count();

    LOGI("Llama model loaded in %lld ms (requested_gpu_layers=%d, GPU=%s)",
         (long long)loadMs, nGpuLayers, nGpuLayers > 0 ? "true" : "false");

    // WHY: Log actual backend information for GPU debugging
    // If GPU layers were requested, the load time should be 2-8s (OpenCL SPIR-V compile)
    // If load time is <1s with nGpuLayers>0, GPU backend likely didn't initialize
    if (nGpuLayers > 0 && loadMs < 1000) {
        LOGW("GPU layers requested but load completed in <%lld ms — GPU may not be active", (long long)loadMs);
    }

    // Allocate native context
    LlamaContext* nativeCtx = new LlamaContext();
    nativeCtx->model = model;
    nativeCtx->ctx = ctx;
    nativeCtx->sampler = sampler;
    nativeCtx->lastTtftMs = 0;
    nativeCtx->lastDecodeTokensPerSec = 0;
    nativeCtx->lastPeakRamBytes = 0;
    nativeCtx->isGpuMode = nGpuLayers > 0;

    setNativeContext(env, obj, nativeCtx);

    return 0;
}

/**
 * Runs completion with streaming callback.
 *
 * PERF: Target TTFT <= 2.0s, decode >= 20 tok/s on Adreno 750.
 *
 * @param env JNI environment
 * @param obj Java LlamaEngine instance
 * @param prompt Full prompt string
 * @param callback Token callback for streaming output
 * @return 0 on success, negative error code on failure
 */
JNIEXPORT jint JNICALL
Java_com_stelliq_aria_llm_LlamaEngine_nativeComplete(
        JNIEnv* env,
        jobject obj,
        jstring prompt,
        jobject callback) {

    LlamaContext* nativeCtx = getNativeContext(env, obj);
    if (nativeCtx == nullptr || nativeCtx->ctx == nullptr) {
        LOGE("Native context not initialized");
        return -1;
    }

    // Get callback method
    jclass callbackClass = env->GetObjectClass(callback);
    jmethodID onTokenMethod = env->GetMethodID(callbackClass, "onToken",
            "(Ljava/lang/String;Z)V");
    if (onTokenMethod == nullptr) {
        LOGE("Failed to get onToken method");
        env->DeleteLocalRef(callbackClass);
        return -2;
    }
    // WHY: Done with callbackClass — delete early since this method is long-running (inference loop)
    env->DeleteLocalRef(callbackClass);

    // WHY: Get cancellation check method from LlamaEngine for cooperative cancellation.
    // The 'obj' parameter is the LlamaEngine instance — we call isCancellationRequested()
    // on it each token to support user-initiated cancellation from the Java side.
    jclass engineClass = env->GetObjectClass(obj);
    jmethodID isCancelledMethod = env->GetMethodID(engineClass, "isCancellationRequested", "()Z");
    env->DeleteLocalRef(engineClass);
    if (isCancelledMethod == nullptr) {
        LOGW("Could not find isCancellationRequested method — cancellation disabled");
        env->ExceptionClear();  // Clear NoSuchMethodError if thrown
    }

    // Get prompt string
    const char* promptStr = env->GetStringUTFChars(prompt, nullptr);
    if (promptStr == nullptr) {
        LOGE("Failed to get prompt string");
        return -3;
    }

    LOGI("Starting completion. Prompt length: %zu", strlen(promptStr));

    // WHY: Tokenize prompt (b68d751 API requires llama_vocab*)
    // parse_special=true so Llama 3.2 control tokens (<|start_header_id|> etc.)
    // are recognized as special token IDs, not tokenized as literal text.
    const struct llama_vocab* vocab = llama_model_get_vocab(nativeCtx->model);
    if (vocab == nullptr) {
        LOGE("Failed to get vocab from model");
        env->ReleaseStringUTFChars(prompt, promptStr);
        return -3;
    }
    std::vector<llama_token> tokens(strlen(promptStr) + 32);
    int nTokens = llama_tokenize(vocab, promptStr, strlen(promptStr),
            tokens.data(), tokens.size(), true, true);

    env->ReleaseStringUTFChars(prompt, promptStr);

    if (nTokens < 0) {
        LOGE("Failed to tokenize prompt");
        return -4;
    }
    tokens.resize(nTokens);

    LOGD("Tokenized to %d tokens", nTokens);

    // WHY: Clear KV cache for fresh inference (b68d751 API)
    llama_memory_clear(llama_get_memory(nativeCtx->ctx), true);

    // WHY: Use 128-token prompt batches instead of 512 to create GPU yield points.
    // A single 512-token batch monopolizes the Adreno for 3+ seconds — long enough
    // to trigger GPU fence timeouts and ANR. 128-token batches take ~750ms each,
    // short enough to yield before HWUI's 3s fence timeout.
    static const int PROMPT_BATCH_SIZE = 128;
    llama_batch batch = llama_batch_init(PROMPT_BATCH_SIZE, 0, 1);

    // PERF: Measure time to first token
    auto promptStartTime = std::chrono::steady_clock::now();

    // Process prompt in smaller batches with GPU yields
    for (int i = 0; i < nTokens; i += PROMPT_BATCH_SIZE) {
        batch.n_tokens = 0;
        for (int j = i; j < nTokens && j < i + PROMPT_BATCH_SIZE; j++) {
            batch_add_token(batch, tokens[j], j, 0, false);
        }
        if (i + PROMPT_BATCH_SIZE >= nTokens) {
            // WHY: Mark last token for logits computation
            batch.logits[batch.n_tokens - 1] = true;
        }

        if (llama_decode(nativeCtx->ctx, batch) != 0) {
            LOGE("Failed to decode prompt batch");
            llama_batch_free(batch);
            return -5;
        }

        // WHY: Yield GPU between prompt batches to let HWUI render frames.
        // 15ms gives HWUI enough time for 1 full frame at 60Hz (16.7ms budget).
        // With 128-token batches, a 471-token prompt gets ~3 yields (45ms total)
        // — negligible vs the ~3s prompt eval time, but prevents ANR and fence
        // timeouts. 8B model needs longer yields than 3B because each batch
        // holds the GPU ~2x longer (more parameters per layer).
        if (i + PROMPT_BATCH_SIZE < nTokens) {
            std::this_thread::sleep_for(std::chrono::milliseconds(15));
        }
    }

    auto firstTokenTime = std::chrono::steady_clock::now();
    nativeCtx->lastTtftMs = std::chrono::duration_cast<std::chrono::milliseconds>(
            firstTokenTime - promptStartTime).count();

    LOGD("Prompt processed in %ld ms (TTFT)", nativeCtx->lastTtftMs);

    // Decoding loop
    int nDecoded = 0;
    auto decodeStartTime = std::chrono::steady_clock::now();

    for (int i = 0; i < MAX_OUTPUT_TOKENS; i++) {
        // WHY: Check Java cancellation flag every token (~200ms at 5 tok/s).
        // One JNI call per token adds ~2μs overhead — negligible vs decode time.
        if (isCancelledMethod != nullptr && env->CallBooleanMethod(obj, isCancelledMethod)) {
            LOGI("Inference cancelled by user at token %d", i);
            llama_batch_free(batch);
            return -6;
        }

        // Sample next token
        llama_token newToken = llama_sampler_sample(nativeCtx->sampler, nativeCtx->ctx, -1);

        // WHY: Check for end of generation (b68d751 API)
        if (llama_vocab_is_eog(vocab, newToken)) {
            LOGD("EOS token reached at position %d", i);

            // Send final callback
            jstring emptyStr = env->NewStringUTF("");
            env->CallVoidMethod(callback, onTokenMethod, emptyStr, JNI_TRUE);
            env->DeleteLocalRef(emptyStr);  // Safe with pending exception per JNI spec
            if (env->ExceptionCheck()) {
                LOGE("Java exception in final onToken callback");
                llama_batch_free(batch);
                return -5;
            }
            break;
        }

        // WHY: Convert token to string (b68d751 API)
        char tokenBuf[256];
        int tokenLen = llama_token_to_piece(vocab, newToken,
                tokenBuf, sizeof(tokenBuf), 0, false);

        if (tokenLen > 0) {
            tokenBuf[tokenLen] = '\0';

            // Send token to Java callback
            jstring tokenStr = env->NewStringUTF(tokenBuf);
            env->CallVoidMethod(callback, onTokenMethod, tokenStr, JNI_FALSE);
            env->DeleteLocalRef(tokenStr);  // Safe with pending exception per JNI spec
            if (env->ExceptionCheck()) {
                LOGE("Java exception in onToken callback");
                llama_batch_free(batch);
                return -5;
            }

            nDecoded++;
        }

        // Prepare next batch
        batch.n_tokens = 0;
        batch_add_token(batch, newToken, nTokens + i, 0, true);

        if (llama_decode(nativeCtx->ctx, batch) != 0) {
            LOGE("Failed to decode at position %d", i);
            break;
        }

        // WHY: Yield GPU EVERY token during decode to prevent UI starvation.
        // 8B model at ~25-35 tok/s: each llama_decode() takes ~30-40ms of GPU time.
        // 8ms yield per token gives HWUI half a 60Hz frame (16.7ms) to render.
        // Cost: ~400 yields × 8ms = 3.2s on a ~16s decode (~20% overhead),
        // dropping effective tok/s from ~30 to ~24 — still meets ≥20 target.
        // Benefit: Prevents launcher ANR, GPU fence stalls, and HWUI starvation
        // that was causing the phone to become "almost un-useable" during summary.
        std::this_thread::sleep_for(std::chrono::milliseconds(8));
    }

    auto decodeEndTime = std::chrono::steady_clock::now();
    auto decodeMs = std::chrono::duration_cast<std::chrono::milliseconds>(
            decodeEndTime - decodeStartTime).count();

    // Calculate tokens per second
    if (decodeMs > 0) {
        nativeCtx->lastDecodeTokensPerSec = (float)nDecoded / (decodeMs / 1000.0f);
    } else {
        nativeCtx->lastDecodeTokensPerSec = 0;
    }

    LOGI("Completion done. Decoded %d tokens in %lld ms (%.1f tok/s)",
         nDecoded, (long long)decodeMs, nativeCtx->lastDecodeTokensPerSec);

    llama_batch_free(batch);

    return 0;
}

/**
 * Releases native resources.
 */
JNIEXPORT void JNICALL
Java_com_stelliq_aria_llm_LlamaEngine_nativeRelease(
        JNIEnv* env,
        jobject obj) {

    LlamaContext* nativeCtx = getNativeContext(env, obj);
    if (nativeCtx != nullptr) {
        LOGI("Releasing llama context");

        if (nativeCtx->sampler != nullptr) {
            llama_sampler_free(nativeCtx->sampler);
        }
        if (nativeCtx->ctx != nullptr) {
            llama_free(nativeCtx->ctx);
        }
        if (nativeCtx->model != nullptr) {
            llama_model_free(nativeCtx->model);
        }

        delete nativeCtx;
        setNativeContext(env, obj, nullptr);
    }

    llama_backend_free();
}

/**
 * Returns last TTFT in milliseconds.
 */
JNIEXPORT jlong JNICALL
Java_com_stelliq_aria_llm_LlamaEngine_nativeGetLastTtftMs(
        JNIEnv* env,
        jobject obj) {

    LlamaContext* nativeCtx = getNativeContext(env, obj);
    return nativeCtx != nullptr ? nativeCtx->lastTtftMs : 0;
}

/**
 * Returns last decode speed in tokens per second.
 */
JNIEXPORT jfloat JNICALL
Java_com_stelliq_aria_llm_LlamaEngine_nativeGetLastDecodeTokensPerSec(
        JNIEnv* env,
        jobject obj) {

    LlamaContext* nativeCtx = getNativeContext(env, obj);
    return nativeCtx != nullptr ? nativeCtx->lastDecodeTokensPerSec : 0;
}

/**
 * Returns last peak RAM usage in bytes.
 */
JNIEXPORT jlong JNICALL
Java_com_stelliq_aria_llm_LlamaEngine_nativeGetLastPeakRamBytes(
        JNIEnv* env,
        jobject obj) {

    LlamaContext* nativeCtx = getNativeContext(env, obj);
    return nativeCtx != nullptr ? nativeCtx->lastPeakRamBytes : 0;
}

} // extern "C"
