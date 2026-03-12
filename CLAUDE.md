# CLAUDE.md — ARIA NPU Build Specification
## Automated Review Intelligence Assistant | STELLiQ Technologies
**Current as of:** 2026-03-11 | **Version:** 0.2.0-npu
**Forked from:** ARIA Commercial v0.1.0-demo (CPU-only Whisper, Llama 3.2 3B)

> This file is the authoritative guide for Claude Code working in the ARIA_NPU codebase.
> This build upgrades ARIA to use **QNN HTP NPU acceleration for Whisper** and
> **Llama 3.1 8B** for LLM summarization on the Samsung Galaxy S24 Ultra.
>
> **Reference documents (read in full before modifying native code):**
> - `ARIA_Demo_QNN_Whisper_S24Ultra_Guide_v1.3.md` — primary QNN implementation guide for S24 Ultra
> - `ARIA_QNN_Whisper_NPU_Implementation_Guide_v1.1.md` — technical QNN step-by-step (SBIR, adapted here)
> - `ARIA_Android_Scaffold_Spec_v1_0_updated.md` — full SBIR project scaffold (architectural reference)

---

## Project Identity

| Field | Value |
|---|---|
| **Product** | ARIA — AI-Powered Recording & Intelligent Analysis |
| **Build Variant** | NPU-Accelerated Demo Build (QNN HTP V75 + 8B LLM) |
| **What it does** | Records meetings, transcribes speech on-device (Whisper small.en via NPU), summarizes via LLM (Llama 3.1 8B via GPU), displays structured AAR |
| **Target Device** | Samsung Galaxy S24 Ultra (Snapdragon 8 Gen 3, Hexagon V75, Adreno 750, 12GB RAM) |
| **Package** | `com.stelliq.aria` |
| **Language** | Java (not Kotlin) |
| **Min SDK / Target SDK** | 29 / 34 |
| **ABI** | `arm64-v8a` only |
| **Air-gapped** | Yes — full pipeline runs in airplane mode after initial model download |

---

## What Changed from ARIA Commercial (CPU Build)

| Component | Previous (CPU Build) | This Build (NPU Build) |
|---|---|---|
| **Whisper model** | base.en Q5_1 (59.7MB) | **small.en Q8_0 (~488MB) + QNN HTP V75 encoder** |
| **Whisper inference** | CPU ARM NEON (6 threads) | **Encoder: Hexagon NPU (V75), Decoder: CPU** |
| **Whisper RTF** | ~0.37-0.40x | **~0.3-0.4x** (similar RTF, vastly better accuracy with small.en 244M params) |
| **LLM model** | Llama 3.2 3B Q4_K_M (~2.3GB) | **Llama 3.1 8B Q4_K_M (~4.6GB)** |
| **LLM quality** | AAR template only; other templates degraded | **Dramatically better extraction across all templates** |
| **Peak RAM** | ~7.1GB | **~9.3GB** (within 12GB envelope, ~3GB headroom) |
| **New native deps** | None | **libQnnHtp.so, libQnnHtpPrepare.so, libQnnHtpV75Stub.so, libQnnSystem.so** |
| **CMake** | CPU-only whisper.cpp | **whisper.cpp + QNN backend (qnn-lib.cpp)** |
| **JNI load order** | `asr_jni` only | **cdsprpc (vendor) → QnnHtpPrepare → QnnHtp → asr_jni** |

---

## Build & Test Commands

```bash
# Build debug APK
cmd.exe //c "set JAVA_HOME=C:\Program Files\Android\Android Studio1\jbr&& .\gradlew.bat assembleDebug"

# Run unit tests
cmd.exe //c "set JAVA_HOME=C:\Program Files\Android\Android Studio1\jbr&& .\gradlew.bat testDebugUnitTest"

# Install to S24 Ultra
adb -s R5CWC3M25GL install -r "C:/STELLiQ/ARIA_NPU/app/build/outputs/apk/debug/app-debug.apk"

# Launch
adb -s R5CWC3M25GL shell am start -W -n com.stelliq.aria/.ui.SplashActivity

# Clean native build (required when CMake args change)
rm -rf app/.cxx

# Verify NPU delegation (CRITICAL — must show count > 0)
adb -s R5CWC3M25GL logcat | grep -E "HTP|QNN|delegation|hexagon|V75"

# Verify LLM GPU offload
adb -s R5CWC3M25GL logcat | grep -E "llama|ggml|opencl|gpu_layers|offload"
```

---

## Pipeline Overview (NPU Build)

```
User taps START
    |
    v
AudioCaptureManager (16kHz mono PCM, aria-audio-capture thread)
    |
    v
SlidingWindowBuffer (5s window / 5s stride, 80Hz HPF)
    |
    v
SileroVAD (ONNX, CPU, filters silence)
    |
    v
WhisperEngine — ENCODER on Hexagon NPU (V75 HTP context binary)
             — DECODER on CPU (autoregressive, dynamic control flow)
             — Model: small.en Q8_0 (244M params)
             — Target RTF: ≤ 0.5x (expected ~0.3-0.4x on V75)
    |
    v
TextPostProcessor (capitalize, collapse whitespace)
    |
    v
TranscriptAccumulator (dedup overlapping words, [M:SS] timestamps)
    |
User taps STOP
    |
    v
AARPromptBuilder (Llama 3.1 Instruct format)
    |
    v
LlamaEngine — Llama 3.1 8B Instruct Q4_K_M
           — Adreno 750 GPU via OpenCL (32 layers fully offloaded)
           — TTFT target: ≤ 2.0s (expected ~1.2-1.5s)
           — Decode target: ≥ 20 tok/s (expected ~25-35 tok/s)
    |
    v
AARJsonParser (regex + JSON fallback)
    |
    v
AARSummary (TC 7-0.1: Plan / Happened / Why / Improve)
    |
    v
Room DB (AARSession + TranscriptSegment + PipelineMetric)
```

---

## QNN NPU Architecture — Mandatory Requirements

> **These 12 requirements (QD-1 through QD-12) are non-negotiable.**
> Each one has caused a silent or hard failure in prior sessions.
> See `ARIA_Demo_QNN_Whisper_S24Ultra_Guide_v1.3.md` Section 0D for full details.

### QD-1: Four `.so` Files in `jniLibs/arm64-v8a/`
```
libQnnHtp.so          — Core HTP runtime
libQnnHtpPrepare.so   — HTP graph preparation
libQnnHtpV75Stub.so   — Hexagon V75 stub for S24 Ultra (Snapdragon 8 Gen 3)
                        ⚠ Stub version MUST match device SoC — wrong stub = delegation count 0
libQnnSystem.so       — QNN system library
```
Source: QNN SDK v2.44.0.260225 `lib/aarch64-android/` from `softwarecenter.qualcomm.com`
**Do NOT bundle `libcdsprpc.so`** — use vendor's copy via `<uses-native-library>` (see QD-10).

### QD-2: Java Static Block Library Load Order
```java
static {
    // 0th — vendor FastRPC (loaded via <uses-native-library>, try/catch for non-Qualcomm)
    try { System.loadLibrary("cdsprpc"); } catch (UnsatisfiedLinkError e) { /* non-QC device */ }
    System.loadLibrary("QnnHtpPrepare"); // 1st — dependency of QnnHtp
    System.loadLibrary("QnnHtp");        // 2nd — core HTP runtime
    System.loadLibrary("asr_jni");       // 3rd — JNI bridge (depends on QnnHtp)
}
```
**Wrong order = silent CPU fallback, delegation count = 0, NO error thrown.**
cdsprpc must resolve from vendor via manifest `<uses-native-library>` — see QD-10.

### QD-3: CMakeLists.txt — Three Mandatory Definitions
1. `${WHISPER_SRC}/examples/qnn/qnn-lib.cpp` in WHISPER_SOURCES
2. `WHISPER_USE_QNN` compile definition
3. `GGML_USE_QNN` compile definition

Missing any one → CPU-only build with no compile error.

### QD-4: ONNX Export — All Five Constraints
1. `model.encoder` — encoder attribute only, NOT full model
2. `encoder.eval()` — disable dropout before export
3. Fixed input shape `(1, 80, 3000)` — no dynamic axes
4. `opset_version=17` — minimum required for Whisper attention ops
5. `do_constant_folding=True` — improves HTP delegation rate

### QD-5: AI Hub Device Target String
```python
hub.Device("Samsung Galaxy S24 Ultra")  # Exact string — verify with hub.get_devices()
```

### QD-6: Context Binary in `assets/models/`, NEVER `res/raw/`
Android AAPT applies DEFLATE to `res/raw/` — corrupts binary files silently.
Must have `noCompress "bin", "gguf", "onnx"` in `aaptOptions`.

### QD-7: Context Binary Extracted to `filesDir` Before Native Load
Native whisper.cpp needs an absolute filesystem path. Android assets are inside APK ZIP.
Extract to `context.getFilesDir()` on first run.

### QD-8: LlamaEngine GPU Layers = 32
Full Adreno 750 offload for Llama 3.1 8B. `nGpuLayers = 0` → CPU-only, 20-40s TTFT.

### QD-9: ADB Verification Before Any Sprint Gate
```bash
adb logcat | grep -E "HTP|QNN|delegation"    # Must show delegation count > 0
adb logcat | grep -E "llama|opencl|offload"   # Must confirm GPU layer offload
```

### QD-10: AndroidManifest `<uses-native-library>` for `libcdsprpc.so`
The V75 stub communicates with the Hexagon DSP via FastRPC (`libcdsprpc.so`). This library
lives in `/vendor/lib64/` and is listed in `public.libraries.txt`, but Android 12+ namespace
isolation blocks it unless explicitly declared in the manifest:
```xml
<uses-native-library android:name="libcdsprpc.so" android:required="false" />
```
**Without this:** error 4000 (`loadRemoteSymbols failed`) and silent CPU fallback.
**Do NOT bundle cdsprpc in jniLibs** — it has deep vendor transitive deps (`libhidlbase.so`,
`vendor.qti.hardware.dsp@1.0.so`, etc.) that aren't resolvable in the app namespace.

### QD-11: Skeleton Library Deployment + `ADSP_LIBRARY_PATH`
The skeleton library (`libQnnHtpV75Skel.so`) runs ON the Hexagon DSP, not the ARM CPU.
The `cdsprpcd` daemon only searches `/vendor/dsp/cdsp/` by default. For dev builds, push
the skel to `/data/local/tmp/` and set the env var in native code BEFORE QNN backend init:
```cpp
setenv("ADSP_LIBRARY_PATH", "/data/local/tmp", 1);  // Before backendCreate()
```
**Source:** QNN SDK `lib/hexagon-v75/unsigned/libQnnHtpV75Skel.so`
**Dev push:** `adb push libQnnHtpV75Skel.so /data/local/tmp/`

### QD-12: Context Binary Graph Name Discovery
AI Hub auto-generates random graph names (e.g., `"graph_b_x4j60r"`) per compile job.
Hardcoding `"main"`, `"model"`, or `"encoder"` will fail. The native `discoverGraphName()`
function scans the first 64KB of the context binary for null-terminated strings matching
the `"graph_"` prefix. This runs at load time in `qnn_wrapper.cpp::loadContextBinary()`.
**Changes every recompilation** — never hardcode the graph name.

---

## Architecture

### Threading Model
| Thread | Responsibility | Key Class |
|--------|---------------|-----------|
| Main | UI updates, LiveData, service lifecycle | SessionController |
| `aria-audio-capture` | AudioRecord loop (32ms chunks) | AudioCaptureManager |
| `aria-asr-worker` | Whisper inference (encoder on NPU, decoder on CPU) | WhisperEngine |
| `aria-llm-worker` | Llama 3.1 8B inference (Adreno 750 GPU) | LlamaEngine |
| Room executor | Database writes | AARRepository |

### State Machine
```
IDLE -> INITIALIZING -> RECORDING -> SUMMARIZING -> COMPLETE
  ^                                                    |
  |__________________ (reset / new session) ___________|

Any state -> ERROR (recoverable) -> IDLE
```

### Package Structure
| Package | Responsibility | Key Files |
|---------|---------------|-----------|
| `asr/` | Audio capture, VAD, Whisper (NPU encoder + CPU decoder), transcript | WhisperEngine, SlidingWindowBuffer, TranscriptAccumulator |
| `llm/` | Prompt building (Llama 3.1), LLM inference (8B GPU), JSON parsing | LlamaEngine, AARPromptBuilder, AARJsonParser |
| `db/` | Room database, DAOs, repository | AARDatabase, AARRepository |
| `service/` | Foreground service, state machine | ARIASessionService, SessionController |
| `meeting/` | Multi-device TCP protocol, NSD discovery | MeetingManager, MeetingServer, MeetingClient |
| `model/` | Data classes | AARSummary, SessionState, ModelDownloadStatus |
| `ui/` | Fragments, activities, views | MainActivity, HomeFragment, RecordingFragment |
| `util/` | Constants, logging, notifications | Constants, PerfLogger, ModelFileManager |
| `export/` | Session export | AARExporter |
| `viewmodel/` | MVVM bridge | SessionViewModel |

### Native Libraries
| Library | Purpose | Inference Path |
|---------|---------|----------------|
| `libasr_jni.so` | Whisper JNI bridge (whisper.cpp + ggml + QNN backend) | Encoder: NPU (V75 HTP), Decoder: CPU |
| `libllm_jni.so` | Llama JNI bridge (links prebuilt libllama.so) | GPU (Adreno 750 OpenCL) |
| `libQnnHtp.so` | QNN HTP core runtime | NPU runtime |
| `libQnnHtpPrepare.so` | QNN HTP graph preparation | NPU runtime |
| `libQnnHtpV75Stub.so` | Hexagon V75 device stub (S24 Ultra) | NPU device interface |
| `libQnnSystem.so` | QNN system library | NPU system |
| `libonnxruntime.so` | ONNX Runtime for Silero VAD | CPU |

### AI Models
| Model | File | Size | Location | Inference |
|-------|------|------|----------|-----------|
| Whisper small.en Q8_0 | `ggml-small.en-q8_0.gguf` | ~488MB | APK assets or external | CPU decoder + NPU encoder |
| Whisper encoder context binary | `whisper_encoder_s24ultra.bin` | ~10-15MB | `assets/models/` → extracted to `filesDir` | NPU (V75 HTP) |
| Llama 3.1 8B Instruct Q4_K_M | `aria_llama31_8b_q4km.gguf` | ~4.6GB | Downloaded to `externalFilesDir/models/` | GPU (Adreno 750) |
| Silero VAD v5 | `silero_vad_v5.onnx` | 1.8MB | Bundled in `res/raw/` | CPU |

---

## Key Configuration (Constants.java — NPU Build Values)

| Parameter | Value | Why |
|-----------|-------|-----|
| `AUDIO_SAMPLE_RATE_HZ` | 16000 | Whisper requirement |
| `WINDOW_SAMPLES` | 80000 (5s) | Balance latency vs accuracy |
| `STRIDE_SAMPLES` | 80000 (5s) | Non-overlapping windows |
| `WHISPER_MODEL_FILENAME` | `ggml-small.en-q8_0.gguf` | 244M params, best accuracy |
| `WHISPER_QNN_CONTEXT_FILENAME` | `whisper_encoder_s24ultra.bin` | V75 HTP context binary |
| `WHISPER_THREADS` | 4 | small.en decoder on Cortex-X4 big cores |
| `WHISPER_RTF_TARGET` | 0.5f | ≤ 0.5x with NPU encoder |
| `LLM_MODEL_FILENAME` | `aria_llama31_8b_q4km.gguf` | 8B model, best AAR quality |
| `LLM_GPU_LAYERS` | 32 | All layers on Adreno 750 |
| `LLM_CONTEXT_TOKENS` | 4096 | Llama 3.1 context window |
| `LLM_TEMPERATURE` | 0.1 | Low randomness for structured output |
| `LLM_TTFT_TARGET_MS` | 2000 | ≤ 2s on Adreno 750 |
| `LLM_TPS_TARGET` | 20.0f | ≥ 20 tok/s on Adreno 750 |
| `MAX_TRANSCRIPT_CHARS` | 13600 | Prevents context overflow |
| `INTER_WINDOW_YIELD_MS` | 500 | Thermal management (less on S24 Ultra) |

---

## Performance Targets

| Metric | Target | Expected on S24 Ultra |
|--------|--------|-----------------------|
| Whisper RTF (QNN V75) | ≤ 0.5x | ~0.3-0.4x (small.en encoder on NPU) |
| LLM TTFT (Adreno 750 GPU) | ≤ 2.0s | ~1.2-1.5s |
| LLM decode speed | ≥ 20 tok/s | ~25-35 tok/s |
| Peak RAM | < 10GB | ~9.3GB (12GB device, 3GB headroom) |
| End-to-end pipeline | ≤ 60s | ~45-55s for 15-min transcript |
| Thermal throttle risk | Low | S24 Ultra has 92% larger vapor chamber |

---

## RAM Allocation (NPU Build)

| Component | Estimated RAM |
|---|---|
| Android 14 OS (One UI 6) | ~3.0 GB |
| Demo APK + UI | ~150 MB |
| Whisper small.en + QNN runtime | ~600 MB |
| Llama 3.1 8B Q4_K_M (GPU) | ~5.5 GB peak |
| SQLite + buffers | ~50 MB |
| **Total Peak** | **~9.3 GB — within 12 GB envelope** |

---

## LLM Decision: 8B vs. 3B

**LOCKED: Llama 3.1 8B Instruct Q4_K_M.**

| Factor | 8B Q4_K_M | 3B Q4_K_M |
|---|---|---|
| Quantized Size | ~4.6 GB | ~1.9 GB |
| Peak RAM | ~5.5 GB | ~2.5 GB |
| S24 Ultra Headroom | ~3.1 GB remaining | ~6.1 GB remaining |
| AAR Quality | Excellent — handles all 4 TC 7-0.1 fields reliably | Acceptable for AAR template only |
| TTFT (Adreno 750) | ~1.2-1.5s | ~0.5-0.8s |
| Decode Speed | ~25-35 tok/s | ~50-70 tok/s |

Both fit on S24 Ultra. 8B wins decisively on AAR extraction quality — the output IS the demo.

**FALLBACK:** Llama 3.2 3B Q4_K_M pre-downloaded as logistics insurance only.
**PROHIBITED:** Phi-3-mini, Mistral, Gemma, or any non-Llama model without PI approval.

---

## Coding Conventions

### Required on ALL code
- Full JavaDoc file header (author, version, since, responsibility, architecture position)
- `// WHY:` comments on non-obvious logic
- `// PERF:` tags on performance-sensitive methods
- No magic numbers — all in `Constants.java`
- No silent failures — log errors, propagate where possible

### Comment Tags
| Tag | Meaning |
|-----|---------|
| `// WHY:` | Explains non-obvious design decision |
| `// PERF:` | Performance-critical section |
| `// QNN:` | QNN/NPU-specific implementation detail |
| `// RISK-XX:` | Documents a known risk |

### Prohibited Patterns
```
PROHIBITED: Kotlin (Java only)
PROHIBITED: allowMainThreadQueries() on Room
PROHIBITED: Hardcoded strings in UI (use strings.xml)
PROHIBITED: Network calls during recording/summarization
PROHIBITED: Thread.sleep() on main thread
PROHIBITED: requireActivity() inside background thread lambdas
PROHIBITED: Exporting Whisper decoder to ONNX — encoder only, input shape (1,80,3000)
PROHIBITED: Using opset_version < 17 in Whisper ONNX export
PROHIBITED: Omitting examples/qnn/qnn-lib.cpp from WHISPER_SOURCES in CMakeLists.txt
PROHIBITED: Loading System.loadLibrary("asr_jni") before QnnHtpPrepare and QnnHtp
PROHIBITED: Deploying APK without all four libQnn*.so files in jniLibs/arm64-v8a/
PROHIBITED: Using dynamic input shapes in AI Hub compile job — always (1, 80, 3000)
PROHIBITED: Placing context binary in res/raw/ — must be assets/models/ with noCompress
PROHIBITED: Passing asset-relative path to native loadModel — must extract to filesDir first
PROHIBITED: Setting nGpuLayers < 32 for LlamaEngine on S24 Ultra
PROHIBITED: Using wrong HTP stub version (e.g., V73Stub on S24 Ultra) — must be V75Stub
PROHIBITED: Bundling libcdsprpc.so in jniLibs — use vendor copy via <uses-native-library>
PROHIBITED: Hardcoding QNN context binary graph names — use discoverGraphName() scanner
PROHIBITED: Calling QNN backendCreate() before setting ADSP_LIBRARY_PATH env var
```

---

## Prior Failure Modes (Read Before Any QNN Work)

| # | Failure | Fix |
|---|---|---|
| F-1 | ONNX export crash: `Unsupported op: If` | Export `model.encoder` only, not `model` |
| F-2 | ONNX export crash: `Unsupported op: Loop` | Same as F-1 — decoder included |
| F-3 | AI Hub compile FAILED: unsupported op | `opset_version=17` mandatory |
| F-4 | AI Hub compile FAILED: device not found | Verify exact string with `hub.get_devices()` |
| F-5 | HTP delegation count = 0 | Wrong stub version for device SoC, or missing `.so` files |
| F-6 | HTP delegation count = 0 | Wrong library load order in static{} block |
| F-7 | CMake: cannot find -lQnnHtp | `.so` files not in `jniLibs/arm64-v8a/` |
| F-8 | CPU-only whisper, no QNN path | `qnn-lib.cpp` omitted from WHISPER_SOURCES |
| F-9 | Context binary loads corrupted | File was in `res/raw/` (DEFLATE compression) |
| F-10 | LLM garbled output | `nGpuLayers=0` or context size too small |
| F-11 | `dlopen failed: library 'libcdsprpc.so' not found` | Add `<uses-native-library android:name="libcdsprpc.so">` to AndroidManifest.xml (QD-10) |
| F-12 | Bundled cdsprpc → `libhidlbase.so not found` | Do NOT bundle cdsprpc in jniLibs — it has deep vendor transitive deps. Use vendor copy via manifest declaration |
| F-13 | Skel not found by cdsprpcd daemon | Push `libQnnHtpV75Skel.so` to `/data/local/tmp/` and set `ADSP_LIBRARY_PATH` before QNN init (QD-11) |
| F-14 | Graph name mismatch (`main`/`model`/`encoder` all fail) | AI Hub generates random graph names — use `discoverGraphName()` binary scanner (QD-12) |
| F-15 | `QnnDevice.h` not found during CMake build | Add `${QNN_SDK_DIR}/include/QNN` to asr_jni include dirs (System/ sub-headers use relative includes) |

---

## Database Schema (Room, version 3)

Identical to ARIA Commercial. See `db/CLAUDE.md` for entity details.

---

## Testing

205 unit tests across 10 test files (0 failures, inherited from ARIA Commercial).
Run with: `gradlew testDebugUnitTest`

NPU-specific verification requires physical device — see QD-9 ADB commands above.

---

## Known Gaps

| Priority | Issue | Status |
|----------|-------|--------|
| ~~HIGH~~ | ~~QNN SDK `.so` files not yet obtained~~ | **RESOLVED** — QNN SDK v2.44.0.260225 obtained, 4 `.so` files in jniLibs |
| ~~HIGH~~ | ~~Whisper encoder context binary not yet compiled~~ | **RESOLVED** — AI Hub job jgne8vmmg compiled for S24 Ultra (V75) |
| ~~HIGH~~ | ~~Whisper small.en GGUF not yet bundled~~ | **RESOLVED** — `ggml-small.en-q8_0.gguf` obtained |
| ~~HIGH~~ | ~~Llama 3.1 8B GGUF download URL/mechanism~~ | **RESOLVED** — bartowski HuggingFace URL in Constants.java |
| ~~MEDIUM~~ | ~~Constants.java not yet updated for NPU values~~ | **RESOLVED** — all NPU constants implemented |
| ~~MEDIUM~~ | ~~CMakeLists.txt not yet updated for QNN~~ | **RESOLVED** — QNN backend, compile defs, include paths all configured |
| ~~MEDIUM~~ | ~~WhisperEngine JNI load order not yet changed~~ | **RESOLVED** — cdsprpc → QnnHtpPrepare → QnnHtp → asr_jni |
| MEDIUM | Live transcription with NPU encoder not yet tested | Pending — need to record and verify RTF on device |
| MEDIUM | LLM 8B inference not yet tested on device | Pending — model staged but not deployed to device |
| MEDIUM | Skel library deployment for production (not `/data/local/tmp/`) | Pending — dev workaround only |
| LOW | ARIASessionService is a God Class (~600 lines) | Inherited — extract in Phase 2 |
