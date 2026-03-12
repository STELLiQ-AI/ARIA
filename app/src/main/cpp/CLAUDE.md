# Native Code — JNI Bridge Layer (NPU Build)

## Responsibility
JNI bridge between Java inference engines and native C++ libraries (whisper.cpp, llama.cpp).
Handles model loading, inference execution, token streaming, and resource cleanup.
**NPU Build adds QNN HTP V75 backend for Whisper encoder offload to Hexagon V75 NPU (S24 Ultra).**

## What Changed from CPU Build
- **whisper_jni.cpp:** Accepts QNN context binary path, initializes HTP encoder delegation
- **qnn_wrapper.cpp:** NEW — QNN HTP backend lifecycle manager. Handles DSP path config
  (`ADSP_LIBRARY_PATH`), context binary loading, graph name discovery, and encoder execution.
- **CMakeLists.txt:** Adds QNN backend sources, compile definitions (`ARIA_USE_QNN`,
  `WHISPER_USE_QNN`, `GGML_USE_QNN`), QNN SDK include paths, links QNN HTP runtime
- **New dependencies:** libQnnHtp.so, libQnnHtpPrepare.so, libQnnHtpV75Stub.so, libQnnSystem.so
- **llama_jni.cpp:** Updated for Llama 3.1 8B (larger model, same GPU path)

## Files

| File | Purpose |
|------|---------|
| `whisper_jni.cpp` | JNI bridge for Whisper ASR. Loads GGUF model + **QNN V75 context binary**. Encoder on NPU, decoder on CPU. |
| `qnn_wrapper.cpp` | **NEW.** QNN HTP backend manager. `configureSkeletonSearchPath()` sets ADSP_LIBRARY_PATH, `discoverGraphName()` scans binary for auto-generated graph names, `loadContextBinary()` loads V75 context binary. |
| `llama_jni.cpp` | JNI bridge for Llama LLM. Loads GGUF model with OpenCL GPU. **Llama 3.1 8B** (was 3B). Token streaming + cancellation. |
| `CMakeLists.txt` | Build configuration. Whisper from source (static) **with QNN backend**. Llama from prebuilt (shared). |
| `asr_jni.version` | Linker version script — hides ggml symbols from libasr_jni.so to prevent conflict with llama's GPU ggml. |
| `include/` | Prebuilt headers for llama.cpp, ggml, whisper.cpp |
| `external/whisper.cpp/` | Full whisper.cpp source tree (built as static library) **including examples/qnn/ for QNN backend** |

## Build Architecture (NPU Build)

```
whisper.cpp (source, with QNN backend)
    |
    |── whisper.cpp, ggml.c, ggml-alloc.c, ggml-backend.c, ggml-quants.c
    |── examples/qnn/qnn-lib.cpp  ← QNN backend glue (MANDATORY — do not omit)
    |
    ├──build──> libwhisper.a + libggml.a (STATIC, QNN-enabled)
    |                   |
    |                   v
    |         whisper_jni.cpp + qnn_wrapper.cpp ──link──> libasr_jni.so
    |         (symbol visibility: only JNI_OnLoad + Java_* exported)
    |                   |
    |                   └──links──> libQnnHtp.so, libQnnHtpPrepare.so
    |
    └── Compile definitions: WHISPER_USE_QNN, GGML_USE_QNN, NDEBUG

llama.cpp (prebuilt, GPU) ──────────────────> libllama.so
libggml.so, libggml-base.so, libggml-cpu.so   libggml-opencl.so
                                |
                                v
                      llama_jni.cpp ──link──> libllm_jni.so

QNN Runtime Libraries (in jniLibs/arm64-v8a/):
    libQnnHtp.so          ← Core HTP runtime (v2.44.0)
    libQnnHtpPrepare.so   ← HTP graph preparation (v2.44.0)
    libQnnHtpV75Stub.so   ← S24 Ultra Hexagon V75 device stub (v2.44.0)
    libQnnSystem.so       ← QNN system library (v2.44.0)

QNN SDK (compile-time headers only, NOT linked):
    ${PROJECT_ROOT}/2.44.0.260225/include/QNN/  ← QNN API headers
    Referenced via QNN_SDK_DIR in CMakeLists.txt
```

**Critical:** Whisper's ggml is built as STATIC and symbols are hidden via version script.
This prevents runtime conflicts with llama's GPU-accelerated ggml shared libraries.

## CMakeLists.txt — QNN-Specific Requirements

### Three Mandatory Items (Missing any one → silent CPU fallback)

1. **`examples/qnn/qnn-lib.cpp` in WHISPER_SOURCES** — This is the QNN backend integration
   layer. Without it, whisper.cpp has no code path to call HTP. Build succeeds, but
   inference runs 100% on CPU. No error, no warning.

2. **`WHISPER_USE_QNN` compile definition** — Enables the QNN HTP encoder code path inside
   whisper.cpp. Without this, whisper.cpp ignores the context binary entirely.

3. **`GGML_USE_QNN` compile definition** — Enables the QNN backend in the ggml tensor
   library that whisper.cpp sits on. Without this, QNN calls are compiled out as no-ops.

### QNN Library Linking

```cmake
set(QNN_LIB_DIR ${CMAKE_SOURCE_DIR}/../jniLibs/arm64-v8a)
find_library(QNN_HTP_LIB     QnnHtp     PATHS ${QNN_LIB_DIR} NO_DEFAULT_PATH)
find_library(QNN_HTP_PREP    QnnHtpPrepare PATHS ${QNN_LIB_DIR} NO_DEFAULT_PATH)

# Fatal error if not found — prevents silent CPU fallback builds
if(NOT QNN_HTP_LIB)
    message(FATAL_ERROR "libQnnHtp.so not found — copy from QNN SDK")
endif()

target_link_libraries(whisper ${QNN_HTP_LIB} ${QNN_HTP_PREP} ...)
```

## whisper_jni.cpp — NPU-Specific Changes

### `loadModel(String modelPath, String qnnContextPath)`
- `qnnContextPath` is the absolute filesystem path to `whisper_encoder_s24ultra.bin`
- Must be extracted from APK assets to `filesDir` before calling (native can't read APK ZIP)
- If `qnnContextPath` is non-null and valid, encoder delegates to HTP
- If `qnnContextPath` is null, falls back to CPU (not acceptable for NPU build)

### HTP Initialization Flow
1. whisper.cpp loads GGUF model (weights + decoder structure)
2. whisper.cpp opens QNN context binary (encoder computation graph)
3. QNN runtime loads HTP stub library matching device SoC (V75 for S24 Ultra)
4. Encoder inference runs on Hexagon NPU; decoder runs on CPU

### Verification After First Transcription
```bash
adb logcat | grep -E "HTP|QNN|delegation|V75"
# Must see: "HTP delegation count: N" where N > 0
# If N = 0: check .so files, load order, context binary path, stub version,
#           cdsprpc manifest declaration, skel deployment, ADSP_LIBRARY_PATH
```

## qnn_wrapper.cpp — QNN HTP Backend Manager (NEW)

### `configureSkeletonSearchPath(const char* appLibDir)`
Sets `ADSP_LIBRARY_PATH` env var BEFORE any QNN operations. Configures the `cdsprpcd` daemon
to search app lib dir first, then `/data/local/tmp`, then any existing path. Called from
`initialize()` before `backendCreate()`.

### `discoverGraphName(const std::vector<uint8_t>& binaryData)`
Scans the first 64KB of a context binary for null-terminated ASCII strings matching the
`"graph_"` prefix. AI Hub generates random graph names per compile job (e.g., `"graph_b_x4j60r"`).
Returns the first match, or empty string if not found.

### `loadContextBinary(const char* path)`
Loads the V75 HTP context binary:
1. Reads file into memory
2. Calls `discoverGraphName()` to find the auto-generated graph name
3. Falls back through: discovered name → `"main"` → `"model"` → `"encoder"` → `"forward"` → `"torch_jit"`
4. Creates QNN context from binary and retrieves graph handle

### CMakeLists.txt — QNN Include Paths
Two include directories needed for asr_jni:
- `${QNN_SDK_DIR}/include` — for `#include "QNN/QnnInterface.h"` style includes
- `${QNN_SDK_DIR}/include/QNN` — for System/ sub-headers that use `#include "QnnDevice.h"` (relative)

## llama_jni.cpp — Key Functions (Updated for 8B)

### `nativeInit(String modelPath, int nGpuLayers, ...)`
- Loads Llama 3.1 8B Q4_K_M GGUF (~4.6GB)
- `nGpuLayers = 32` — all layers on Adreno 750 (MANDATORY — see QD-8)
- Context size: 4096 tokens
- Kernel pre-warm: dummy BOS decode + KV clear after context creation

### `nativeComplete(String prompt, Object callback)`
- Tokenizes prompt with `parse_special=true` (Llama 3.1 Instruct control tokens)
- 128-token prompt batches with 5ms GPU yields
- Decode: 3ms sleep every 2 tokens for UI breathing room
- Cancellation: checks `isCancellationRequested()` every token via JNI
- ExceptionCheck after every CallVoidMethod

### `nativeRelease()`
- Frees sampler, context, model (all null-checked)
- Calls `llama_backend_free()`

## JNI Safety Rules (Audit-Enforced — Unchanged)

1. **ExceptionCheck after CallVoidMethod** — Both callback locations in decode loop are guarded
2. **DeleteLocalRef early** — `callbackClass` and `engineClass` freed immediately after `GetMethodID`
3. **Vocab null check** — `llama_model_get_vocab()` return checked before use
4. **Graceful cancellation degradation** — If `isCancellationRequested` method not found, LOGW and continue
5. **ExceptionClear on method lookup failure** — Clear pending NoSuchMethodError
6. **Batch cleanup on error** — `llama_batch_free(batch)` called on all error paths

## Important Notes
- **No GGML_OPENCL in cmake args for whisper** — OpenCL is only for llama's prebuilt libs
- **QNN HTP is separate from GPU OpenCL** — NPU (Hexagon HTP) handles Whisper encoder; GPU (Adreno OpenCL) handles LLM. They are separate silicon on the Snapdragon 8 Gen 3 and do not conflict.
- **NPU cannot run the LLM** — NPU executes static computation graphs (fixed shapes); LLM autoregressive decoding requires dynamic control flow that NPU cannot handle. GPU handles this via dynamic kernel dispatch.
- **Thread model:** `nativeComplete` on aria-llm-worker, `transcribe` on aria-asr-worker
- **Memory:** LLM ~5.5GB VRAM peak (8B model). Whisper ~600MB RAM (small.en + QNN runtime).
- **QNN SDK version:** v2.44.0.260225, backward-compatible with AI Hub QAIRT 2.43 context binaries.
