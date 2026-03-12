# ARIA Demo — QNN Whisper NPU Implementation Guide
## Consumer APK Build | Samsung Galaxy S24 Ultra | Pre-Funding Demo
### Feed this document to Claude Code at session start for Demo Build ASR Sprint

**Document:** `ARIA_Demo_QNN_Whisper_S24Ultra_Guide.md`  
**Version:** 1.1  
**Last Modified:** March 2026  
**Author:** STELLiQ Engineering  
**Classification:** UNCLASSIFIED  
**Program:** ARIA — Automated Review Intelligence Assistant | STELLiQ Technologies, LLC  
**Governing Spec:** This document is self-contained and authoritative for all S24 Ultra demo build sessions. No companion spec file required.  
**LLM Decision:** Llama 3.1 7B Q4_K_M — locked for demo build. See Section 0B for full 7B vs. 3B trade-off analysis.

> **PURPOSE OF THIS DOCUMENT**  
> This guide defines the QNN Whisper NPU integration path for the ARIA pre-funding demo build
> running on a **Samsung Galaxy S24 Ultra as a standard consumer Android APK**. There is no
> ATAK dependency, no TAK SDK, no tactical edition hardware constraint, and no Knox enrollment
> requirement. This is a standalone demonstration APK designed to prove the core ARIA pipeline
> (voice → transcription → AAR summary) to funders and evaluators on readily available hardware.
>
> **This document is intentionally separate from `ARIA_QNN_Whisper_NPU_Implementation_Guide.md`**
> which governs the ATAK plugin build on S23 TE / XCover6 Pro TE tactical hardware. The two
> builds have different hardware targets, different Hexagon generations, different APK structures,
> and different deployment constraints. Do not conflate them.

---

## SECTION 0A — WHAT FAILED BEFORE (READ FIRST)

Prior Claude Code sessions attempted to build the demo APK and QNN Whisper integration
and failed at multiple points. These failures are documented here so Claude Code does
not repeat them. Every entry below has a corresponding fix in this guide.

| # | Failure | Where It Happened | Root Cause | Fix in This Guide |
|---|---|---|---|---|
| **F-1** | ONNX export crash — `Unsupported ONNX op: If` | Section 4 export script | Full model exported including decoder. Decoder has dynamic control flow (`if`/`else` on token values) that `torch.onnx.export` cannot trace statically | Export **encoder attribute only** (`model.encoder`). Never export `model` directly. Fixed input shape `(1, 80, 3000)`. See Section 4. |
| **F-2** | ONNX export crash — `Unsupported ONNX op: Loop` | Section 4 export script | Same root cause as F-1 — decoder included in export graph | Same fix as F-1. |
| **F-3** | AI Hub compile job `FAILED` — "unsupported op" | Section 5 compile script | Wrong opset version (`opset_version=14` or `15`) — Whisper attention layers use ops not available until opset 17 | `opset_version=17` is **mandatory**. Do not change this value. See Section 4 and 5. |
| **F-4** | AI Hub compile job `FAILED` — "device not found" | Section 5 compile script | Device string `"Samsung Galaxy S24 Ultra"` not verified against `hub.get_devices()` before submission | Run device verification check in Section 5.2 before submitting compile job. |
| **F-5** | APK installs, runs, transcribes — but HTP delegation count = 0 (silent CPU fallback) | Section 7 JNI init | `libQnnHtpV79Stub.so` missing from `jniLibs/arm64-v8a/` OR wrong stub version (V75 from SBIR build accidentally copied) | All four correct `.so` files must be in `jniLibs/`. For S24 Ultra: **V79Stub**, not V75Stub. See Section 3. |
| **F-6** | APK installs, runs — but HTP delegation count = 0 | Section 7 JNI init | Library load order wrong — `asr_jni` loaded before `QnnHtpPrepare` and `QnnHtp` | `static {}` block order is **mandatory**: `QnnHtpPrepare` → `QnnHtp` → `asr_jni`. Never reorder. See Section 7.1. |
| **F-7** | CMake build fails — `cannot find -lQnnHtp` | CMakeLists.txt | `libQnnHtp.so` not in `jniLibs/arm64-v8a/` at build time, or wrong path in `find_library()` | Copy all four `.so` files from QNN SDK `lib/aarch64-android/` before building. See Section 3. |
| **F-8** | CMake build produces CPU-only whisper, no QNN path | CMakeLists.txt | `examples/qnn/qnn-lib.cpp` omitted from `WHISPER_SOURCES` — this file is the QNN backend glue layer. Omitting it silently falls back to CPU with no error. | `qnn-lib.cpp` **must** be in `WHISPER_SOURCES`. `WHISPER_USE_QNN` and `GGML_USE_QNN` **must** be defined. See Section 6. |
| **F-9** | Context binary in `res/raw/` loads corrupted | Asset bundling | Android AAPT applies DEFLATE compression to `res/raw/` assets. DEFLATE on a binary `.bin` file corrupts it. | Binary model files go in `src/main/assets/models/` with `noCompress "bin"` in `build.gradle`. **Never** `res/raw/`. See Section 8. |
| **F-10** | LLM loads but produces garbled / incomplete AAR output | LlamaEngine config | `nGpuLayers` set to 0 (CPU-only) or context size too small for full transcript + AAR prompt | Set `nGpuLayers = 32` (all layers to Adreno 750 GPU) and `contextSize = 4096`. See Section 8. |

> **FOR CLAUDE CODE:** Before writing a single line of code in this session, confirm
> you have read all ten failure modes above. If you find yourself about to do something
> that matches any of the root causes in the table, stop and use the fix instead.

---

## SECTION 0B — LLM MODEL SELECTION: 7B vs. 3B TRADE-OFF ANALYSIS

This section documents the architecture decision on LLM model size for the demo build.
**The locked decision is Llama 3.1 7B Q4_K_M.** The 3B option was evaluated and rejected
for demo use. Rationale is documented below so Claude Code does not substitute a 3B model
without PI approval.

### Hardware Feasibility — Both Models Fit on S24 Ultra

| Model | Quantized Size | Peak RAM | S24 Ultra Headroom (12 GB) | Verdict |
|---|---|---|---|---|
| **Llama 3.1 7B Q4_K_M** | ~4.1 GB | ~5.5 GB | ~3.1 GB remaining | ✅ Fits comfortably |
| Llama 3.2 3B Q4_K_M | ~1.9 GB | ~2.5 GB | ~6.1 GB remaining | ✅ Fits easily |

RAM is **not** the differentiator on S24 Ultra. Either model fits without pressure.
The 12 GB envelope eliminates the RAM constraint that exists on the 8 GB S23 TE.

### AAR Extraction Quality — 7B Wins Decisively

The demo use case is structured extraction: take a multi-speaker AAR transcript and
produce a TC 7-0.1 four-step JSON output. This task requires:

- Multi-label classification across four AAR categories simultaneously
- Free-text generation within a constrained JSON schema
- Correct attribution of speech segments to AAR steps despite conversational overlap
- Graceful handling of fragmented, informal military dialogue

**7B models substantially outperform 3B models on all four requirements.** This is
documented in the ARIA SOW and the Army SBIR LLM intelligence report — the quality
gap on structured extraction tasks is the primary reason 7B was locked as the SBIR
contractual model.

For a funder demo, a half-formed AAR output that misses a TC 7-0.1 step, hallucinate
attribution, or produces malformed JSON is a significantly larger risk than a 1-second
latency difference. **Quality of the output IS the demo.**

### Inference Speed — Both Are Fast Enough

| Metric | 7B Q4_K_M (Adreno 750) | 3B Q4_K_M (Adreno 750) | Target |
|---|---|---|---|
| **TTFT** | ~1.2–1.5s | ~0.5–0.8s | ≤ 2s |
| **Decode speed** | ~25–35 tok/s | ~50–70 tok/s | ≥ 20 tok/s |
| **15-min transcript → AAR** | ~45–55s | ~20–25s | ≤ 60s |

Both models meet the performance targets on Adreno 750. The 7B is not slow — the
S24 Ultra's GPU is ~30% faster than the S23 TE Adreno 740, which already hits the
targets in the SBIR build. The extra latency of 7B vs. 3B is not visible to a funder
watching a live demo — the pipeline still completes in under a minute.

### The One Real Risk with 7B: Download Logistics

The 7B GGUF is ~4.1 GB. On demo day, this must be pre-downloaded. A 3B model
(~1.9 GB) is faster to recover if something goes wrong with storage or the device
is reset. This is a **logistics risk**, not a performance risk.

**Mitigation:** Pre-download the GGUF at least 24 hours before demo day. Verify
`isModelReady()` returns `true` before leaving for the demo location. Keep a backup
copy on a USB drive or a second device.

### Decision

```
LOCKED: Llama 3.1 7B Instruct Q4_K_M for demo build.
FALLBACK: Llama 3.2 3B Instruct Q4_K_M — pre-download as logistics insurance.
          Only activate fallback if 7B is unavailable on demo day.
          Do not substitute 3B as the primary without PI approval.

PROHIBITED: Phi-3-mini, Mistral, Gemma, or any model other than Llama family
            without PI approval. Llama family ensures consistency with SBIR
            contractual model narrative.
```

---

## SECTION 0C — DEMO BUILD VS. SBIR BUILD: KEY DIFFERENCES

Understanding these differences prevents Claude Code from importing wrong architectural
decisions from the SBIR build docs into this session.

| Dimension | SBIR Build (ATAK Plugin) | Demo Build (This Document) |
|---|---|---|
| **Target Device** | S23 TE (Host) + XCover6 Pro TE (Contributor) | Samsung Galaxy S24 Ultra only |
| **SoC** | Snapdragon 8 Gen 2 (S23 TE) | Snapdragon 8 Gen 3 for Galaxy |
| **Hexagon Generation** | Gen 2 HTP (V75) | **Gen 3 HTP (V79)** |
| **GPU** | Adreno 740 | **Adreno 750** |
| **RAM** | 8 GB (S23 TE host) | **12 GB** |
| **APK Type** | ATAK plugin (TAK SDK required) | **Standard Android APK — no ATAK** |
| **Knox Enrollment** | Required (or Knox stub pattern) | **Not required — consumer device** |
| **ATAK Dependency** | Full ATAK SDK integration | **Zero ATAK dependency** |
| **Multi-Device** | Host + Contributor WiFi Direct mesh | **Single device — standalone** |
| **LLM** | Llama 3.1 7B Q4_K_M via llama.cpp GPU | **Llama 3.1 7B Q4_K_M via llama.cpp GPU** |
| **Model Distribution** | GGUF downloaded via DownloadManager | **GGUF downloaded via DownloadManager** |
| **Deployment** | Sideload to enrolled Army EUD | **Standard APK install — sideload or Play Store** |
| **Context Binary Target** | `whisper_encoder_s23te.bin` (V75) | **`whisper_encoder_s24ultra.bin` (V79)** |
| **Governing Spec** | `CLAUDE.md` (SBIR builds) | **This document — self-contained, no companion spec required** |

**CRITICAL RULE FOR CLAUDE CODE:** This document is the sole governing spec for the S24 Ultra demo build. Treat it as self-contained. The five decision points that differ from the SBIR build are: hardware target (S24 Ultra), Hexagon generation (V79 not V75), context binary filename (`whisper_encoder_s24ultra.bin`), APK type (standard Android, no ATAK), and deployment method (standard install, no Knox enrollment). If you are about to implement something that matches a failure mode in Section 0A, stop and use the documented fix in the referenced section instead.

---

## SECTION 0D — QNN NPU ARCHITECTURE REQUIREMENTS (EXPLICIT CALLOUTS)

This section consolidates every architectural requirement that is mandatory for QNN HTP
to function on the S24 Ultra. These are not suggestions. Every item has caused a
silent or hard failure in prior sessions. Claude Code must verify each item is
implemented before considering any sprint gate closed.

### QD-1: Four Correct `.so` Files — Exact Filenames, V79 Stubs

```
REQUIRED in app/src/main/jniLibs/arm64-v8a/:

  libQnnHtp.so          — Core HTP runtime. Without this, QNN cannot initialize.
  libQnnHtpPrepare.so   — HTP graph preparation. Without this, libQnnHtp.so fails silently.
  libQnnHtpV79Stub.so   — Hexagon Gen 3 (V79) device stub for S24 Ultra.
                          ⚠ WRONG FILE: libQnnHtpV75Stub.so (S23 TE stub — SBIR build).
                          Using V75Stub on S24 Ultra causes HTP delegation count = 0 with no error.
  libQnnSystem.so       — QNN system library. Required for Hexagon Gen 3. Not needed on Gen 2.
                          ⚠ OMISSION: Omitting this causes runtime crash on S24 Ultra.

SOURCE: QNN SDK zip from softwarecenter.qualcomm.com
PATH:   /opt/qnn-sdk/lib/aarch64-android/
```

### QD-2: Java Static Block Library Load Order — Non-Negotiable

```java
// CORRECT ORDER — do not change, do not reorder:
static {
    System.loadLibrary("QnnHtpPrepare"); // 1st: dependency of QnnHtp
    System.loadLibrary("QnnHtp");        // 2nd: core HTP runtime
    System.loadLibrary("asr_jni");       // 3rd: JNI bridge (depends on QnnHtp)
}

// WRONG — causes silent CPU fallback, delegation count = 0:
static {
    System.loadLibrary("asr_jni");       // ❌ Too early — QnnHtp not loaded yet
    System.loadLibrary("QnnHtp");
    System.loadLibrary("QnnHtpPrepare");
}
```

### QD-3: CMakeLists.txt — Three Mandatory Definitions

All three of the following must be present in the CMakeLists.txt whisper build target.
Missing any one of them produces a CPU-only build with no compile error:

```cmake
# 1. qnn-lib.cpp MUST be in WHISPER_SOURCES
#    This is the QNN backend integration layer in whisper.cpp.
#    It is NOT compiled by default — it must be explicitly added.
${WHISPER_SRC}/examples/qnn/qnn-lib.cpp

# 2. WHISPER_USE_QNN MUST be a compile definition
#    Enables the QNN HTP encoder code path inside whisper.cpp.
#    Without this, whisper.cpp ignores the context binary entirely.
WHISPER_USE_QNN

# 3. GGML_USE_QNN MUST be a compile definition
#    Enables the QNN backend in the ggml layer that whisper.cpp sits on.
#    Without this, QNN calls are compiled out as no-ops.
GGML_USE_QNN
```

### QD-4: ONNX Export Constraints — All Five Required Simultaneously

The ONNX export must satisfy all five constraints at the same time.
Satisfying four out of five still fails:

```
1. model.encoder   — Export the encoder ATTRIBUTE, not the full model object.
                     model.encoder is a nn.Module. model is not directly exportable.

2. encoder.eval()  — Call before export. Disables dropout. Enables static graph.
                     Forgetting this exports non-deterministic ops that fail QNN compile.

3. (1, 80, 3000)   — Fixed concrete input shape. No dynamic axes. No symbolic dims.
                     Dynamic shapes prevent HTP delegation — encoder runs on CPU instead.

4. opset_version=17 — Minimum required. Whisper attention uses ops added in opset 17.
                      opset 14, 15, 16 all fail the AI Hub compile with "unsupported op".

5. do_constant_folding=True — Folds constant subgraphs before export.
                              Improves HTP delegation rate by reducing graph complexity.
```

### QD-5: AI Hub Device Target String — Must Match Exactly

```python
# CORRECT for S24 Ultra:
hub.Device("Samsung Galaxy S24 Ultra")

# VERIFY before submitting — run this and use the exact printed string:
import qai_hub as hub
for d in hub.get_devices():
    if "S24 Ultra" in str(d):
        print(repr(str(d)))  # Copy this exact string

# WRONG — will fail with "device not found":
hub.Device("Galaxy S24 Ultra")       # Missing "Samsung"
hub.Device("Samsung S24 Ultra")      # Missing "Galaxy"
hub.Device("Samsung Galaxy S23")     # Wrong device — produces V75 binary, not V79
```

### QD-6: Asset Location — Context Binary Must Be in `assets/`, Never `res/raw/`

```
CORRECT:   app/src/main/assets/models/whisper_encoder_s24ultra.bin
           + noCompress "bin" in aaptOptions

WRONG:     app/src/main/res/raw/whisper_encoder_s24ultra.bin
           Android AAPT applies DEFLATE to res/raw/ assets. DEFLATE corrupts
           binary model files. The file will load without error but produce
           garbage output or a native crash. This has no compile-time warning.
```

### QD-7: Context Binary Must Be Extracted to `filesDir` Before Native Load

```java
// WHY: The native whisper.cpp layer receives a filesystem path string.
//      Android assets are inside the APK ZIP — they have no filesystem path.
//      The native layer cannot open a path that points inside a ZIP archive.
//      Extract to context.getFilesDir() on first run, then pass that path.

// CORRECT:
String binPath = extractAssetToFilesDir(context, "models/whisper_encoder_s24ultra.bin");
whisperEngine.loadModel(ggufPath, binPath);  // binPath is an absolute filesystem path

// WRONG:
whisperEngine.loadModel(ggufPath, "models/whisper_encoder_s24ultra.bin");
// ❌ Relative asset path — native layer cannot open this
```

### QD-8: LlamaEngine GPU Layer Count — Must Be 32 for Full Adreno 750 Offload

```java
// CORRECT — all 32 transformer layers on Adreno 750 GPU:
int nGpuLayers = 32;   // Llama 3.1 7B has 32 transformer layers

// WRONG — CPU-only inference, TTFT will be 20–40 seconds:
int nGpuLayers = 0;    // ❌ All layers on CPU — misses the entire performance target

// WRONG — partial GPU offload, inconsistent performance:
int nGpuLayers = 16;   // ❌ Half on GPU, half on CPU — not the optimal configuration
```

### QD-9: Mandatory ADB Verification Before Sprint Gate Close

These two logcat checks are required evidence before closing any sprint gate
related to QNN functionality. Do not rely on "it seems to be working":

```bash
# Check 1 — Whisper HTP delegation active
adb logcat | grep -E "HTP|QNN|delegation|hexagon" --color=always
# Required output: "HTP delegation count: N" where N > 0
# If N = 0: work through QD-1 through QD-7 in order

# Check 2 — LLM GPU layers active
adb logcat | grep -E "llama|ggml|opencl|gpu_layers|offload" --color=always
# Required output: confirmation that N layers are GPU-offloaded
# If all layers show CPU: verify nGpuLayers = 32 in LlamaEngine (QD-8)
```

---

## SECTION 1 — TARGET HARDWARE PROFILE

### Samsung Galaxy S24 Ultra — Inference Capability Summary

| Component | Specification | ARIA Relevance |
|---|---|---|
| **SoC** | Snapdragon 8 Gen 3 for Galaxy (4nm TSMC N4P) | Custom Samsung/Qualcomm collaboration — slightly overclocked vs. stock 8 Gen 3 |
| **CPU** | 1× Cortex-X4 @ 3.39 GHz + 3× A720 @ 3.1 GHz + 2× A720 @ 2.9 GHz + 2× A520 @ 2.2 GHz | 8-core; Whisper decoder runs on big cores |
| **GPU** | Adreno 750 (~5.0 TFLOPS FP16, ~1 GHz peak) | LLM inference via llama.cpp OpenCL/Vulkan |
| **NPU** | Hexagon Gen 3 HTP (V79) (~45+ TOPS) | Whisper encoder context binary target |
| **RAM** | 12 GB LPDDR5X | Comfortable — Llama 3.1 7B Q4_K_M (~5.5 GB) + OS + app headroom |
| **Storage** | 256 GB / 512 GB / 1 TB UFS 4.0 | GGUF model storage, SQLite persistence |
| **OS** | Android 14 (One UI 6.x) | Target SDK 34; consumer device — no Army image |
| **Thermal** | Vapor chamber 92% larger than S23 Ultra | Sustained inference runs better than S23 TE — lower throttle risk |
| **Context Binary** | `whisper_encoder_s24ultra.bin` (V79 target) | Compiled via AI Hub targeting S24 Ultra |

### RAM Allocation — Demo Build (Single Device)

| Component | Estimated RAM |
|---|---|
| Android 14 OS (One UI 6) | ~3.0 GB |
| Demo APK + UI | ~150 MB |
| Whisper small.en + QNN runtime | ~600 MB |
| Llama 3.1 7B Q4_K_M (llama.cpp GPU) | ~5.5 GB peak |
| SQLite + buffers | ~50 MB |
| **Total Peak** | **~9.3 GB — within 12 GB envelope** |

**No RAM ceiling risk.** The S24 Ultra's 12 GB provides ~3 GB headroom above the peak
working set. This is why it is the correct pre-funding demo device — RAM saturation
is a non-issue, allowing focus on pipeline quality and UI polish.

---

## SECTION 2 — DEMO APK ARCHITECTURE

The demo APK is a self-contained Android application with no ATAK dependency.
It proves the same core pipeline as ARIA SBIR Phase I but in an accessible,
demonstrable form factor.

### Pipeline

```
Microphone (Android AudioRecord, 16kHz mono)
        ↓
   Silero VAD (ONNX, CPU)         ← strips silence; 30ms frames
        ↓
   Whisper small.en Encoder (QNN HTP V79)  ← mel → embeddings on Hexagon NPU
        ↓
   Whisper small.en Decoder (CPU)          ← token generation
        ↓
   Transcript Buffer (in-memory + SQLite)
        ↓
   Llama 3.1 7B Q4_K_M (llama.cpp, Adreno 750 GPU)  ← AAR extraction
        ↓
   TC 7-0.1 AAR JSON Output       ← four-step structure
        ↓
   Demo UI Display + SQLite Persist
```

### APK Component Map

```
aria-demo/
├── app/
│   ├── src/main/
│   │   ├── java/com/stelliq/ariademo/
│   │   │   ├── MainActivity.java          ← Single-activity host
│   │   │   ├── asr/
│   │   │   │   ├── WhisperEngine.java      ← JNI bridge (QNN HTP path)
│   │   │   │   ├── VadProcessor.java       ← Silero VAD ONNX wrapper
│   │   │   │   └── AudioCaptureService.java
│   │   │   ├── nlp/
│   │   │   │   ├── LlamaEngine.java        ← JNI bridge (Adreno 750 GPU)
│   │   │   │   └── AARExtractor.java       ← Prompt builder + JSON parser
│   │   │   ├── db/
│   │   │   │   └── DemoDatabase.java       ← Room + SQLite
│   │   │   ├── model/
│   │   │   │   └── AAROutput.java          ← TC 7-0.1 schema POJO
│   │   │   └── ui/
│   │   │       ├── RecordFragment.java
│   │   │       ├── TranscriptFragment.java
│   │   │       └── AARResultFragment.java
│   │   ├── cpp/
│   │   │   ├── CMakeLists.txt
│   │   │   ├── whisper_jni.cpp
│   │   │   └── llama_jni.cpp
│   │   ├── assets/models/
│   │   │   └── .gitkeep  ← gguf + .bin files never committed
│   │   └── jniLibs/arm64-v8a/
│   │       ├── libQnnHtp.so
│   │       ├── libQnnHtpPrepare.so
│   │       ├── libQnnHtpV79Stub.so    ← S24 Ultra (Hexagon Gen 3 = V79)
│   │       └── libQnnSystem.so
```

**No `WifiDirectManager.java`. No `CopPushManager.java`. No ATAK SDK imports.
No TAK Server dependency. Single device, single APK.**

---

## SECTION 3 — CRITICAL HARDWARE DIFFERENCE: V79 vs. V75

The S24 Ultra runs Hexagon Gen 3 HTP (V79). The SBIR build targets Gen 2 (V75) on
the S23 TE. These are different architectures with different context binaries and
different stub libraries.

### What Changes for V79

| Item | S23 TE (SBIR) | S24 Ultra (Demo) |
|---|---|---|
| **Hexagon generation** | Gen 2 (V75) | Gen 3 (V79) |
| **HTP stub library** | `libQnnHtpV75Stub.so` | **`libQnnHtpV79Stub.so`** |
| **AI Hub device target** | `"Samsung Galaxy S23 (Family)"` | **`"Samsung Galaxy S24 Ultra"`** |
| **Context binary filename** | `whisper_encoder_s23te.bin` | **`whisper_encoder_s24ultra.bin`** |
| **Op set coverage** | Good (8 Gen 2) | **Better — Gen 3 has broader HTP op support** |
| **Address space** | ~3B param ceiling for QNN LLM | **Larger — Phase II LLM-on-NPU candidate** |

**The V79 has a broader op set than V75.** If small.en encoder compiles cleanly on V75
(S23 TE), it will compile on V79 (S24 Ultra). The demo build is actually the lower-risk
compile target.

### Stub Library for `jniLibs/arm64-v8a/`

The demo build requires these four `.so` files — note the V79 stub instead of V75:

```
jniLibs/arm64-v8a/
├── libQnnHtp.so           ← Core HTP runtime (same as SBIR build)
├── libQnnHtpPrepare.so    ← HTP preparation (same as SBIR build)
├── libQnnHtpV79Stub.so    ← S24 Ultra stub — DIFFERENT from SBIR build (V75)
└── libQnnSystem.so        ← QNN system library (required for Gen 3)
```

**Source:** QNN SDK zip from `softwarecenter.qualcomm.com`  
**Path inside SDK:** `lib/aarch64-android/libQnnHtp*.so` and `libQnnSystem.so`

```bash
# Copy correct stub for S24 Ultra from QNN SDK (WSL2 terminal)
cp /opt/qnn-sdk/lib/aarch64-android/libQnnHtp.so \
   /opt/qnn-sdk/lib/aarch64-android/libQnnHtpPrepare.so \
   /opt/qnn-sdk/lib/aarch64-android/libQnnHtpV79Stub.so \
   /opt/qnn-sdk/lib/aarch64-android/libQnnSystem.so \
   ~/aria-demo/app/src/main/jniLibs/arm64-v8a/

# Verify — libQnnHtpV79Stub.so must be present, NOT V75
ls -lh ~/aria-demo/app/src/main/jniLibs/arm64-v8a/
```

---

## SECTION 4 — STEP 1: ONNX ENCODER EXPORT

The export script is identical to the SBIR guide. The model, input shape, opset version,
and encoder-only constraint are the same. Copy `scripts/export_whisper_encoder.py` from
the SBIR build or use the version below.

```python
#!/usr/bin/env python3
"""
export_whisper_encoder.py

Purpose: Export Whisper small.en encoder to ONNX for QNN HTP V79 context binary
         compilation targeting Samsung Galaxy S24 Ultra (Snapdragon 8 Gen 3,
         Hexagon Gen 3 HTP V79).

         ENCODER ONLY — decoder is CPU. Fixed input shape (1, 80, 3000) is
         mandatory for NPU delegation. opset_version=17 required.

Module:  scripts/
Program: ARIA Demo Build — STELLiQ Technologies, LLC
Author:  [PI Name] / STELLiQ Engineering
Version: 1.1.0
Last Modified: [Date]
Classification: UNCLASSIFIED
"""

import torch
import whisper
import onnx
import os

OUTPUT_PATH = "whisper_small_en_encoder.onnx"

print("[export] Loading whisper small.en...")
model   = whisper.load_model("small.en")
encoder = model.encoder
encoder.eval()  # Required — disables dropout, enables static graph export

# Fixed shape — mandatory for QNN HTP delegation on V79
mel_input = torch.zeros(1, 80, 3000)

print("[export] Exporting encoder-only ONNX (opset 17, fixed shape)...")
torch.onnx.export(
    encoder, mel_input, OUTPUT_PATH,
    input_names=["mel"],
    output_names=["embeddings"],
    opset_version=17,            # Required — do not change
    do_constant_folding=True,
    export_params=True,
)

print("[export] Validating...")
onnx.checker.check_model(onnx.load(OUTPUT_PATH))
print(f"[export] ✅ Done: {OUTPUT_PATH}  ({os.path.getsize(OUTPUT_PATH)/1024/1024:.1f} MB)")
print("[export] Expected size: ~105 MB for small.en encoder")
print("[export] Next: run scripts/compile_qnn_s24ultra.py")
```

**Run and verify:**
```bash
python3 scripts/export_whisper_encoder.py

# Success: ~37 MB output, no dynamic op errors
# STOP if file > 100 MB (wrong model size) or any "Unsupported ONNX op" error
```

---

## SECTION 5 — STEP 2: AI HUB COMPILE FOR S24 ULTRA

### 5.1 The Compile Script

Save as `scripts/compile_qnn_s24ultra.py`. Note the device target string —
it is different from the SBIR build.

```python
#!/usr/bin/env python3
"""
compile_qnn_s24ultra.py

Purpose: Compile Whisper small.en encoder ONNX to QNN HTP context binary
         targeting Samsung Galaxy S24 Ultra (Hexagon Gen 3, V79).
         Downloads whisper_encoder_s24ultra.bin for APK bundling.

         This is the DEMO BUILD compile script. Target device is S24 Ultra.
         Do NOT use this script for SBIR build targets (S23 TE, XCover6 778G).
         See ARIA_QNN_Whisper_NPU_Implementation_Guide.md for SBIR targets.

Module:  scripts/
Program: ARIA Demo Build — STELLiQ Technologies, LLC
Author:  [PI Name] / STELLiQ Engineering
Version: 1.1.0
Last Modified: [Date]
Classification: UNCLASSIFIED
"""

import qai_hub as hub
import os

# =============================================================================
# SECTION: Configuration
# =============================================================================

ONNX_PATH   = "whisper_small_en_encoder.onnx"
OUTPUT_BIN  = "app/src/main/assets/models/whisper_encoder_s24ultra.bin"

# WHY: S24 Ultra uses Hexagon Gen 3 (V79). AI Hub device string must match
#      exactly — check hub.get_devices() if compile fails with "device not found".
DEVICE_NAME = "Samsung Galaxy S24 Ultra"

# WHY: Fixed concrete shape — dynamic shapes prevent HTP delegation on V79.
INPUT_SPECS = {"mel": (1, 80, 3000)}

# =============================================================================
# SECTION: Pre-flight
# =============================================================================

if not os.path.exists(ONNX_PATH):
    raise FileNotFoundError(
        f"[compile] ONNX file not found: {ONNX_PATH}\n"
        f"          Run scripts/export_whisper_encoder.py first.\n"
        f"          Expected: whisper_small_en_encoder.onnx (~105 MB)"
    )

os.makedirs("app/src/main/assets/models", exist_ok=True)
print(f"[compile] Input: {ONNX_PATH}  ({os.path.getsize(ONNX_PATH)/1024/1024:.1f} MB)")
print(f"[compile] Target device: {DEVICE_NAME}")
print(f"[compile] Output: {OUTPUT_BIN}")

# =============================================================================
# SECTION: Verify Device Available on AI Hub
# =============================================================================

print("[compile] Checking AI Hub device availability...")
available = [str(d) for d in hub.get_devices()]
if not any("S24 Ultra" in d or "Galaxy S24 Ultra" in d for d in available):
    print("[compile] WARNING: 'Samsung Galaxy S24 Ultra' not found in hub.get_devices()")
    print("[compile] Available devices:")
    for d in available:
        if "S24" in d or "Galaxy S24" in d:
            print(f"          {d}")
    print("[compile] Use the exact device string from the list above and update DEVICE_NAME.")
    raise RuntimeError("Device not found. Update DEVICE_NAME before proceeding.")

# =============================================================================
# SECTION: Compile Job
# =============================================================================

print("[compile] Submitting compile job to AI Hub...")
compile_job = hub.submit_compile_job(
    model=ONNX_PATH,
    device=hub.Device(DEVICE_NAME),
    options="--target_runtime qnn_context_binary",
    input_specs=INPUT_SPECS,
)

print(f"[compile] Job ID: {compile_job.job_id}")
print(f"[compile] Monitor at: https://aihub.qualcomm.com/jobs/{compile_job.job_id}")
print("[compile] Waiting for completion (typically 5–15 minutes)...")

result = compile_job.wait()
if not result.success:
    raise RuntimeError(
        f"[compile] ❌ COMPILE FAILED\n"
        f"          Job ID: {compile_job.job_id}\n"
        f"          Check: https://aihub.qualcomm.com/jobs/{compile_job.job_id}\n"
        f"          Common fix: verify ONNX was exported with opset_version=17\n"
        f"          and encoder-only (not full model). V79 has broad op support\n"
        f"          — if this fails, the ONNX export is almost certainly the issue."
    )

print("[compile] ✅ Compile successful.")
compile_job.download_target_model(OUTPUT_BIN)
print(f"[compile] Downloaded: {OUTPUT_BIN}")
print(f"[compile] Size: {os.path.getsize(OUTPUT_BIN)/1024/1024:.1f} MB")

# =============================================================================
# SECTION: Profile Job (Get Real V79 Latency for Demo Talking Points)
# =============================================================================

print("[compile] Submitting profile job for V79 HTP latency data...")
profile_job = hub.submit_profile_job(
    model=compile_job.get_target_model(),
    device=hub.Device(DEVICE_NAME),
)
profile_result = profile_job.wait()
if profile_result.success:
    profile_data = profile_job.download_profile()
    latency_ms = profile_data.get("inference_time", "N/A")
    print(f"[compile] V79 HTP encoder latency: {latency_ms} ms")
    print(f"[compile] ← Use this number in demo talking points and investor materials")
else:
    print("[compile] Profile job failed (non-blocking). Resubmit manually if needed.")

print("\n[compile] ─── Demo Build Context Binary Ready ───")
print(f"  ✅  {OUTPUT_BIN}")
print("\n[compile] Next step: Section 6 — CMakeLists.txt and JNI integration")
```

### 5.2 Verify Device String

If the compile fails with a "device not found" error, run this to get the exact
AI Hub device string:

```python
import qai_hub as hub
devices = hub.get_devices()
for d in devices:
    if "S24" in str(d):
        print(d)
# Copy the exact string printed and paste it into DEVICE_NAME above
```

---

## SECTION 6 — STEP 3: CMAKELISTSTS.TXT — DEMO BUILD

The demo build CMakeLists.txt is structurally identical to the SBIR build but
simpler — no multi-APK split, no Contributor exclusions, and the QNN library
path references V79 stubs.

```cmake
cmake_minimum_required(VERSION 3.22)
project(aria_demo_native)

# ---------------------------------------------------------------------------
# Paths — adjust WHISPER_SRC and LLAMA_SRC to your local clone locations
# ---------------------------------------------------------------------------
set(WHISPER_SRC ${CMAKE_SOURCE_DIR}/../../../../whisper.cpp)
set(LLAMA_SRC   ${CMAKE_SOURCE_DIR}/../../../../llama.cpp)
set(QNN_LIB_DIR ${CMAKE_SOURCE_DIR}/../jniLibs/arm64-v8a)

find_library(log-lib     log)
find_library(android-lib android)
find_library(OpenCL-lib  OpenCL)
find_library(aaudio-lib  aaudio)

# ---------------------------------------------------------------------------
# whisper.cpp — QNN HTP V79 backend
# Target: Hexagon Gen 3 (V79) on Snapdragon 8 Gen 3 (S24 Ultra)
#
# REQUIRED: examples/qnn/qnn-lib.cpp MUST be included — it is the QNN
#           backend integration layer. Omitting it silently falls back to CPU.
# REQUIRED: WHISPER_USE_QNN and GGML_USE_QNN must both be defined.
# REQUIRED: libQnnHtpV79Stub.so in jniLibs/arm64-v8a/ (NOT V75Stub)
# ---------------------------------------------------------------------------
set(WHISPER_SOURCES
    ${WHISPER_SRC}/whisper.cpp
    ${WHISPER_SRC}/ggml.c
    ${WHISPER_SRC}/ggml-alloc.c
    ${WHISPER_SRC}/ggml-backend.c
    ${WHISPER_SRC}/ggml-quants.c
    ${WHISPER_SRC}/examples/qnn/qnn-lib.cpp   # QNN backend — do not remove
)

add_library(whisper STATIC ${WHISPER_SOURCES})

target_include_directories(whisper PUBLIC
    ${WHISPER_SRC}
    ${WHISPER_SRC}/examples/qnn
)

target_compile_definitions(whisper PRIVATE
    WHISPER_USE_QNN
    GGML_USE_QNN
    NDEBUG
)

find_library(QNN_HTP_LIB     QnnHtp     PATHS ${QNN_LIB_DIR} NO_DEFAULT_PATH)
find_library(QNN_HTP_PREP    QnnHtpPrepare PATHS ${QNN_LIB_DIR} NO_DEFAULT_PATH)

if(NOT QNN_HTP_LIB)
    message(FATAL_ERROR
        "libQnnHtp.so not found in ${QNN_LIB_DIR}\n"
        "Copy libQnnHtp.so, libQnnHtpPrepare.so, libQnnHtpV79Stub.so, "
        "and libQnnSystem.so from QNN SDK lib/aarch64-android/ — "
        "see Section 3 of ARIA_Demo_QNN_Whisper_S24Ultra_Guide.md"
    )
endif()

target_link_libraries(whisper ${QNN_HTP_LIB} ${QNN_HTP_PREP} ${log-lib} ${android-lib})

# ---------------------------------------------------------------------------
# whisper.cpp JNI bridge
# ---------------------------------------------------------------------------
add_library(asr_jni SHARED ${CMAKE_SOURCE_DIR}/whisper_jni.cpp)
target_include_directories(asr_jni PRIVATE ${WHISPER_SRC})
target_link_libraries(asr_jni whisper ${log-lib} ${android-lib} ${aaudio-lib})

# ---------------------------------------------------------------------------
# llama.cpp — Adreno 750 GPU (OpenCL backend)
# S24 Ultra Adreno 750 achieves TTFT ≤ 2s and ≥ 20 tok/s for 7B Q4_K_M.
# Same GPU backend as SBIR build (Adreno 740) but faster on Adreno 750.
# ---------------------------------------------------------------------------
set(LLAMA_SOURCES
    ${LLAMA_SRC}/llama.cpp
    ${LLAMA_SRC}/ggml.c
    ${LLAMA_SRC}/ggml-alloc.c
    ${LLAMA_SRC}/ggml-backend.c
    ${LLAMA_SRC}/ggml-opencl.cpp    # Adreno 750 GPU backend
)

add_library(llama STATIC ${LLAMA_SOURCES})
target_include_directories(llama PUBLIC ${LLAMA_SRC})
target_compile_definitions(llama PRIVATE GGML_USE_OPENCL GGML_OPENCL_USE_ADRENO NDEBUG)
target_link_libraries(llama ${OpenCL-lib} ${log-lib})

add_library(llm_jni SHARED ${CMAKE_SOURCE_DIR}/llama_jni.cpp)
target_include_directories(llm_jni PRIVATE ${LLAMA_SRC})
target_link_libraries(llm_jni llama ${OpenCL-lib} ${log-lib} ${android-lib})
```

---

## SECTION 7 — STEP 4: JNI INITIALIZATION — DEMO BUILD

### 7.1 Library Load Order (Same Rule, V79 Stubs)

```java
/**
 * WhisperEngine.java (Demo Build)
 *
 * Purpose: JNI bridge to whisper.cpp with QNN HTP V79 backend.
 *          Demo build — no ATAK dependency, no Knox enrollment.
 *          Targets Samsung Galaxy S24 Ultra (Snapdragon 8 Gen 3, Hexagon Gen 3).
 *
 * Module:  com.stelliq.ariademo.asr
 * Program: ARIA Demo — STELLiQ Technologies, LLC
 * Author:  [PI Name] / STELLiQ Engineering
 * Version: 1.0.0
 * Last Modified: [Date]
 * Classification: UNCLASSIFIED
 */
public class WhisperEngine {

    private static final String TAG = "ARIA_DEMO_ASR";

    // ==========================================================================
    // SECTION: QNN Runtime Library Load — ORDER IS MANDATORY
    // WHY: libQnnHtp.so depends on libQnnHtpPrepare.so at link time.
    //      Load Prepare first, then Htp, then the JNI bridge last.
    //      Wrong order = silent CPU fallback. HTP delegation count = 0.
    //      This applies to V79 (S24 Ultra) identically to V75 (S23 TE).
    // ==========================================================================
    static {
        System.loadLibrary("QnnHtpPrepare"); // Step 1 — must be first
        System.loadLibrary("QnnHtp");        // Step 2 — depends on Prepare
        System.loadLibrary("asr_jni");       // Step 3 — JNI bridge last
    }

    // PERF: Target RTF ≤ 0.5x on S24 Ultra QNN V79 path.
    //       Expected actual RTF: ~0.3–0.4x on small.en V79 (larger model than tiny.en).
    //       Log actual RTF to ARIA_DEMO logcat on every window.
    public native int    loadModel(String modelPath, String qnnContextPath);
    public native String transcribe(float[] audioData);
    public native long   getLastInferenceMs();
    public native void   release();
}
```

### 7.2 Model Path Resolution — Demo Build

The demo build stores the context binary in `assets/models/` with `noCompress`
in `build.gradle`. The GGUF models are downloaded at first launch via
`DownloadManager`.

```java
/**
 * Resolves the context binary path for QNN HTP V79 (S24 Ultra).
 * Demo build: single device, single target — no device detection branching needed.
 *
 * @param context Android application context for assets and filesDir access.
 * @return Absolute path to the extracted context binary in app private storage.
 *
 * // PERF: Model load is one-time at app start. Not on inference critical path.
 */
private String getContextBinaryPath(Context context) {
    // WHY: QNN context binary must be in a path accessible by the native layer.
    //      Android assets are read-only — extract to filesDir on first run.
    File destFile = new File(context.getFilesDir(), "whisper_encoder_s24ultra.bin");

    if (!destFile.exists()) {
        Log.d(TAG, "[WhisperEngine.getContextBinaryPath] Extracting context binary from assets...");
        try (InputStream is = context.getAssets().open("models/whisper_encoder_s24ultra.bin");
             FileOutputStream fos = new FileOutputStream(destFile)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
            Log.d(TAG, "[WhisperEngine.getContextBinaryPath] Extracted to: " + destFile.getAbsolutePath());
        } catch (IOException e) {
            Log.e(TAG, "[WhisperEngine.getContextBinaryPath] Failed to extract context binary: "
                + e.getMessage(), e);
            return null;  // Will fall back to CPU in loadModel()
        }
    }
    return destFile.getAbsolutePath();
}
```

### 7.3 Verify HTP Delegation on S24 Ultra

```bash
# From Windows ADB after first transcription call
adb logcat | grep -E "HTP|hexagon|QNN|V79|delegation" --color=always

# ✅ SUCCESS — any of these confirm V79 HTP is active:
#    "Using QNN HTP backend"
#    "HTP delegation count: N"   (N > 0)
#    "Hexagon Gen 3"
#    "V79"

# ❌ FAILURE — CPU fallback, investigate immediately:
#    "HTP delegation count: 0"
#    "Falling back to CPU"

# If V79 delegation count = 0, check in this order:
# 1. libQnnHtpV79Stub.so present in APK (NOT V75Stub)
# 2. Library load order in static {} block
# 3. Context binary path is not null in loadModel() call
# 4. AI Hub compile job targeted S24 Ultra (V79), not S23 (V75)
```

---

## SECTION 8 — MODEL DISTRIBUTION: DEMO BUILD

The demo APK uses a two-part model distribution strategy to keep APK size
manageable while ensuring models are available offline after first launch.

### Context Binary (Whisper Encoder — Small, Bundle in APK)

```groovy
// build.gradle (app module)
android {
    aaptOptions {
        // WHY: Android AAPT applies DEFLATE compression to assets by default.
        //      DEFLATE on binary GGUF/BIN files increases load time without
        //      reducing size (already compressed). noCompress is mandatory.
        noCompress "bin", "gguf", "onnx"
    }
}
```

Place `whisper_encoder_s24ultra.bin` in `app/src/main/assets/models/`.
It will be bundled in the APK (~10–15 MB) and extracted to `filesDir` on first run.

### GGUF Model (Llama 3.1 7B — Large, Download on First Launch)

```java
/**
 * DemoModelManager.java
 *
 * Purpose: Manages download and local storage of the Llama 3.1 7B Q4_K_M
 *          GGUF model on first launch. Uses Android DownloadManager with
 *          resume-on-drop capability. No cloud dependency after download.
 *
 * Module:  com.stelliq.ariademo
 * Program: ARIA Demo — STELLiQ Technologies, LLC
 * Author:  [PI Name] / STELLiQ Engineering
 * Version: 1.0.0
 * Last Modified: [Date]
 * Classification: UNCLASSIFIED
 */
public class DemoModelManager {

    private static final String TAG           = "ARIA_DEMO_MODELS";
    // WHY: Model hosted on Hugging Face — replace with your own host before
    //      investor demo if you want to control availability and bandwidth.
    private static final String LLAMA_URL     = "https://huggingface.co/bartowski/"
                                              + "Meta-Llama-3.1-8B-Instruct-GGUF/resolve/main/"
                                              + "Meta-Llama-3.1-8B-Instruct-Q4_K_M.gguf";
    private static final String LLAMA_FILENAME = "aria_llama31_7b_q4km.gguf";

    /**
     * Returns true if the GGUF model is already downloaded and ready.
     * // PERF: File existence check only — not on inference path.
     */
    public boolean isModelReady(Context context) {
        File modelFile = new File(context.getExternalFilesDir(null), LLAMA_FILENAME);
        return modelFile.exists() && modelFile.length() > 1_000_000_000L; // > 1 GB sanity check
    }

    /**
     * Enqueues GGUF download via Android DownloadManager.
     * Download resumes automatically if interrupted (WiFi drop, etc.).
     * Progress is observable via DownloadManager query.
     */
    public long enqueueModelDownload(Context context) {
        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(LLAMA_URL))
            .setTitle("ARIA Demo — Downloading AI Model")
            .setDescription("~4.1 GB — downloading Llama 3.1 7B for offline AAR generation")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, null, LLAMA_FILENAME)
            .setAllowedOverMetered(true)   // WHY: Demo context — user can choose; toggle in settings
            .setAllowedOverRoaming(false);
        long downloadId = dm.enqueue(req);
        Log.d(TAG, "[DemoModelManager.enqueueModelDownload] Download enqueued. ID: " + downloadId);
        return downloadId;
    }
}
```

---

## SECTION 9 — THERMAL MANAGEMENT: S24 ULTRA SPECIFICS

The S24 Ultra has a 92% larger vapor chamber than the S23 Ultra. In practice:

- Sustained whisper inference runs cooler than S23 TE at equivalent load
- Llama 3.1 7B on Adreno 750 GPU produces ~1.5W GPU draw — within thermal envelope
- The inter-window yield strategy from the SBIR guide still applies as a best practice
  even though the S24 Ultra is less prone to throttle

```java
// PERF: Inter-window yield — apply between Whisper 10-second windows.
//       Less critical on S24 Ultra (larger vapor chamber) but good practice.
//       Reduces thermal accumulation before LLM handoff.
//       500ms yield is sufficient on S24 Ultra vs. 750ms on S23 TE.
private static final long INTER_WINDOW_YIELD_MS = 500L;

// PERF: CPU thread count for Whisper small.en decoder.
//       S24 Ultra Cortex-X4 @ 3.39 GHz — 4 threads optimal for small.en.
//       small.en decoder is larger (244M params) — benefits from additional threads
//       more than tiny.en did. Monitor thermals; drop to 2 if sustained heat observed.
private static final int WHISPER_CPU_THREADS = 2;
```

### Performance Expectations (Demo Talking Points)

| Metric | S24 Ultra Expected | S23 TE Expected | Delta |
|---|---|---|---|
| Whisper RTF (QNN V79) | ~0.3–0.4x (small.en) | ~0.4–0.5x (small.en, V75) | V79 faster NPU |
| LLM TTFT (Adreno 750 GPU) | ~1.2–1.5s | ~1.8–2.0s | Faster GPU |
| LLM decode speed | ~25–35 tok/s | ~20–25 tok/s | More GPU bandwidth |
| Thermal throttle risk | Low (larger vapor chamber) | Medium | Less risk on S24 |

**For investor/funder demo:** Lead with the S24 Ultra numbers. They are better than
the spec targets and demonstrate headroom. The SBIR deployment hardware (S23 TE)
meets the performance targets at the lower end — that is the contractual claim.

---

## SECTION 10 — DEMO UI REQUIREMENTS

The demo APK UI communicates ARIA's value proposition visually. These requirements
guide the UI implementation — not feature requirements for the SBIR build.

### Screen Flow

```
Splash / Model Download Progress Screen
        ↓ (model ready)
Home Screen — [Start AAR Session] button
        ↓ (button tap)
Recording Screen
  - Live waveform visualization
  - Running transcript display (updates per Whisper window)
  - VAD indicator (speaking / silence)
  - Session timer
  - [Finalize] button
        ↓ (Finalize tap)
Processing Screen
  - "Generating AAR Summary..." progress
  - LLM token streaming display (shows model thinking in real-time)
        ↓ (complete)
AAR Result Screen
  - Four-step TC 7-0.1 AAR rendered in card format
    - Step 1: What Was Planned
    - Step 2: What Happened
    - Step 3: Why It Happened
    - Step 4: How to Improve
  - [Share] / [Export PDF] / [Save] buttons
  - [New Session] button
```

### UI Implementation Notes

- Use **Material Design 3** components — clean, professional, defensible to funders
- Token streaming on the Processing screen is a critical demo moment — show the model
  working on-device in real time. Do not buffer and display all at once.
- The "fully offline" badge / indicator should be visible on every screen — this is
  the core differentiator for the demo audience
- No network permission in `AndroidManifest.xml` for inference path — only
  `INTERNET` permission for initial GGUF download; clearly comment this in manifest

---

## SECTION 11 — GRADLE CONFIGURATION

```groovy
// app/build.gradle (Demo Build)
android {
    compileSdk 34
    defaultConfig {
        applicationId    "com.stelliq.ariademo"
        minSdk           31          // Android 12 minimum — required for QNN Gen 3 runtime
        targetSdk        34
        versionCode      1
        versionName      "0.1.0-demo"

        externalNativeBuild {
            cmake {
                cppFlags "-std=c++17 -O3 -DNDEBUG"
                abiFilters "arm64-v8a"  // S24 Ultra only — no x86 emulator builds needed
            }
        }
    }

    aaptOptions {
        noCompress "bin", "gguf", "onnx"  // Prevent DEFLATE on binary model files
    }

    externalNativeBuild {
        cmake { path "src/main/cpp/CMakeLists.txt" }
    }
}

dependencies {
    // Room + SQLite
    implementation "androidx.room:room-runtime:2.6.1"
    annotationProcessor "androidx.room:room-compiler:2.6.1"

    // QNN runtime — loads context binary on Android
    implementation "com.qualcomm.qti:qnn-runtime:2.34.0"

    // Material Design 3 UI
    implementation "com.google.android.material:material:1.11.0"

    // ONNX Runtime (Silero VAD)
    implementation "com.microsoft.onnxruntime:onnxruntime-android:1.17.0"
}
```

---

## SECTION 12 — SPRINT GATE CRITERIA: DEMO BUILD ASR

Demo Build ASR Sprint is COMPLETE when ALL of the following are true:

- [ ] `whisper_encoder_s24ultra.bin` (small.en, V79) downloaded from AI Hub, bundled in `assets/models/`
- [ ] APK installs on S24 Ultra without crash
- [ ] `adb logcat | grep HTP` shows delegation count > 0 on S24 Ultra
- [ ] At least one 10-second audio window transcribed successfully via QNN V79 path
- [ ] RTF measured and confirmed ≤ 0.5x (expected ~0.2–0.3x on V79)
- [ ] LLM TTFT measured and confirmed ≤ 2s on Adreno 750 GPU
- [ ] End-to-end pipeline (voice → transcript → AAR) completes without crash
- [ ] Token streaming visible on Processing screen during LLM inference

**Do not demo to funders with HTP delegation count = 0.**  
CPU-only transcription is a significant talking point risk — if asked "does the AI
chip run this?", the answer must be yes and backed by the logcat evidence.

---

## SECTION 13 — WHAT THIS DEMO PROVES TO FUNDERS

Prepare these specific talking points grounded in the technical implementation:

**"Fully offline — no cloud dependency"**  
→ `AndroidManifest.xml` has no INTERNET permission in the inference path. Show this.
   After GGUF download, device can be in airplane mode and the full pipeline runs.

**"Running on the AI chip in the phone, not the CPU"**  
→ Hexagon Gen 3 NPU (V79) handles Whisper transcription. Adreno 750 GPU handles
   LLM. Show `adb logcat | grep HTP` output with delegation count during the demo.

**"Same technology, ruggedized for battlefield use"**  
→ S24 Ultra proves the concept. The SBIR build retargets to S23 TE + XCover6 Pro TE
   (Army-enrolled hardware). The pipeline is identical; the platform changes.

**"State-of-the-art language model on the edge"**  
→ Llama 3.1 7B running locally. Same model class used in cloud AI products,
   running entirely on a phone with zero network latency.

**"TC 7-0.1 compliance built in"**  
→ The four-step AAR output is structurally enforced by the LLM prompt schema,
   not hoped for. Show the JSON schema in the app.

---

*ARIA_Demo_QNN_Whisper_S24Ultra_Guide.md v1.2*  
*v1.1 Changes: Added Section 0A (10 documented prior failure modes); Section 0B (7B vs. 3B*  
*LLM trade-off analysis and decision); Section 0C renamed from Section 0; LLM locked as*  
*Llama 3.1 7B Q4_K_M with Llama 3.2 3B as logistics fallback only.*  
*v1.2 Changes: All CLAUDE_DEMO.md references removed — document is now self-contained.*  
*Added Section 0D: QNN NPU Architecture Requirements (9 explicit callouts: QD-1 through QD-9)*  
*covering .so files, load order, CMakeLists.txt definitions, ONNX export constraints,*  
*AI Hub device string, asset location, context binary extraction, GPU layer count,*  
*and mandatory ADB verification.*  
*UNCLASSIFIED*  
*STELLiQ Technologies, LLC*  
*Pre-Funding Demo Build — Samsung Galaxy S24 Ultra Consumer APK*

---
*v1.3 Changes: Whisper model updated throughout — small.en (244M) replaces tiny.en (39M) for S24 Ultra demo.*  
*ONNX export script updated (whisper_small_en_encoder.onnx, ~105 MB expected size). Compile script ONNX_PATH updated.*  
*Thread count updated to 4 for small.en decoder. RTF expectation updated (~0.3–0.4x on V79).*  
*RAM total updated (~9.3 GB peak, within 12 GB). Sprint gate checklist updated with model label.*
