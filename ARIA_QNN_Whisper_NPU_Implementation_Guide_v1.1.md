# ARIA — QNN Whisper NPU Implementation Guide
## Whisper tiny.en Encoder → Hexagon HTP Context Binary
### Feed this document to Claude Code at session start for Sprint A-2 through A-5

**Document:** `ARIA_QNN_Whisper_NPU_Implementation_Guide.md`  
**Version:** 1.0  
**Author:** STELLiQ Engineering  
**Classification:** UNCLASSIFIED  
**Program:** ARIA — Automated Review Intelligence Assistant | Army SBIR 24.4 | Topic A244-P037  

> **PURPOSE OF THIS DOCUMENT**  
> This guide exists because prior Claude Code sessions failed to produce a working Whisper QNN HTP
> integration. The failures were caused by four specific, documented error modes. This document
> defines the **only correct implementation path**. Claude Code must follow this guide exactly,
> in order. Do not improvise. Do not skip steps. If any step fails, stop and report the exact
> error output — do not attempt workarounds without PI approval.

---

## SECTION 0 — WHAT FAILED BEFORE (READ FIRST)

Four error modes were encountered in prior sessions. All four must be avoided:

| Prior Failure | Root Cause | This Guide's Fix |
|---|---|---|
| **A — ONNX export crash** | Dynamic control flow in Whisper decoder; full model exported instead of encoder-only | Export encoder-only with fixed mel shape `(1, 80, 3000)`. Never export decoder. |
| **B — AI Hub compile job FAILED** | Unsupported ops on Hexagon 770 (XCover6 778G) attempted first; wrong opset version | Always compile for S23 TE (8 Gen 2) first. Use `opset_version=17`. |
| **C — HTP delegation count = 0** | QNN runtime `.so` files missing from APK `jniLibs/`; load order wrong in JNI init | All four `.so` files must be in `jniLibs/arm64-v8a/`. Load order is mandatory. |
| **D — CMake build fails** | whisper.cpp built without QNN backend sources; `examples/qnn/qnn-lib.cpp` not included | Use the exact CMakeLists template in Section 5. No deviations. |

---

## SECTION 1 — ARCHITECTURE OVERVIEW

### What the QNN Path Does

```
Audio PCM (16kHz mono float[])
        ↓
   VAD (Silero, CPU)              ← strips silence
        ↓
   Whisper Encoder (QNN HTP)      ← mel spectrogram → embeddings on Hexagon NPU
        ↓
   Whisper Decoder (CPU)          ← autoregressive token generation
        ↓
   Transcript String              ← UTF-8 text, passed to merge engine
```

**KEY CONSTRAINT:** Only the encoder runs on HTP. The decoder has dynamic control flow
that cannot be compiled to a static context binary. This is by design, not a bug.
The encoder is the compute bottleneck — offloading it to HTP achieves RTF ≤ 0.5x.

### Device-Specific Context Binaries

| Device | SoC | Hexagon | Context Binary Filename |
|---|---|---|---|
| Samsung Galaxy S23 TE (Host) | Snapdragon 8 Gen 2 | Gen 2 HTP (V75) | `whisper_encoder_s23te.bin` |
| Samsung Galaxy XCover6 Pro TE (Contributor) | Snapdragon 778G | Hexagon 770 (V73) | `whisper_encoder_778g.bin` |

| Device | SoC | Hexagon | Whisper Model | Context Binary Filename |
|---|---|---|---|---|
| Samsung Galaxy S23 TE (Host) | Snapdragon 8 Gen 2 | Gen 2 HTP (V75) | **small.en (244M)** | `whisper_encoder_s23te.bin` |
| Samsung Galaxy XCover6 Pro TE (Contributor) | Snapdragon 778G | Hexagon 770 (V73) | **tiny.en (39M)** | `whisper_encoder_778g.bin` |

Both `.bin` files must be compiled separately from separate ONNX exports. They are NOT interchangeable.

### Model Selection: small.en on Host, tiny.en on Contributor (ADR-4)

> **ADR-4 (Updated):** ARIA uses a two-model split for ASR:
>
> **Host (S23 TE) — `whisper small.en` (244M params, ~488 MB GGUF, ~600 MB RAM peak)**
> - Better WER on field audio — the Host is the device feeding transcripts to the LLM, so
>   accuracy here directly impacts AAR quality
> - small.en encoder fully delegates on V75 (Hexagon Gen 2) — validated path
> - RTF on V75: ~0.4–0.5x — within the ≤0.5x performance target
> - RAM impact on Host (8 GB): comfortable, leaves >1 GB headroom after full stack
>
> **Contributor (XCover6 Pro TE) — `whisper tiny.en` (39M params, ~75 MB GGUF, ~200 MB RAM peak)**
> - V73 (Hexagon 770) op coverage for small.en is **unvalidated** — compile job may fail
>   or produce partial delegation with CPU fallback on the XCover6
> - tiny.en V73 delegation is the validated path for Contributor nodes
> - RTF on V73: ~0.4–0.5x on tiny.en — meets target with comfortable margin
> - RAM impact on Contributor (6 GB): ~200 MB vs ~600 MB for small.en — preferred
>
> **Phase II Path:** If V73 compile validation for small.en succeeds (Sprint A-4b test),
> upgrade Contributor to small.en in Phase II for uniform accuracy across all nodes.
>
> **PROHIBITED:** Do not attempt small.en on Contributor (XCover6) without first
> running a V73 compile job and confirming delegation count > 0 on physical hardware.

---

## SECTION 2 — PREREQUISITES CHECKLIST

Complete every item before touching any code. Claude Code must verify each item with the
user before proceeding to Section 3.

### 2.1 Environment

- [ ] All ARIA files are inside WSL2 filesystem at `~/aria-atak-plugin/` (NOT `/mnt/c/...`)
- [ ] Docker Desktop running with WSL2 backend enabled for Ubuntu-22.04
- [ ] Python 3.10+ available in WSL2: `python3 --version`
- [ ] Git available in WSL2: `git --version`
- [ ] Qualcomm ID account created at `softwarecenter.qualcomm.com`
- [ ] AI Hub account created at `aihub.qualcomm.com`, API token generated
- [ ] Physical S23 TE connected via ADB from Windows host, visible via `adb devices`

### 2.2 Python Packages (WSL2, install before export step)

```bash
# Run inside WSL2 Ubuntu terminal
pip install openai-whisper torch onnx onnxruntime qai-hub

# Verify AI Hub connection
python3 -c "import qai_hub as hub; print(hub.get_devices())"
# Expected: list must include 'Samsung Galaxy S23 (Family)'
# If empty or error: qai-hub configure --api_token YOUR_TOKEN_HERE
```

### 2.3 QIDK Reference Repository (MANDATORY — do not skip)

```bash
# Clone Qualcomm's verified reference implementation
git clone https://github.com/quic/qidk.git /opt/qidk

# Verify the SpeechRecognition directory exists — this is your reference
ls /opt/qidk/SpeechRecognition/
# Expected: README.md, requirements.txt, and model export scripts
```

> **WHY THIS IS MANDATORY:** The QIDK SpeechRecognition example contains
> Qualcomm-verified ONNX export flags for Whisper that avoid the dynamic op
> failures (Failure Mode A). Do not write the export script from scratch.
> Adapt from QIDK. Every prior session that skipped this step hit Failure Mode A.

---

## SECTION 3 — STEP 1: ONNX ENCODER EXPORT

Two separate ONNX exports are required — one per model. ADR-4 mandates different
models for Host vs. Contributor. Run both scripts before proceeding to Section 4.

### 3.1 Export Script A — small.en (Host / S23 TE)

Save as `scripts/export_whisper_encoder_small.py`:

```python
#!/usr/bin/env python3
"""
export_whisper_encoder_small.py

Purpose: Export the Whisper small.en encoder to ONNX format with fixed input shape
         suitable for QNN HTP context binary compilation targeting the S23 TE Host.
         Exports ENCODER ONLY — the decoder is NOT exported.

         Model: whisper small.en — 244M params, ~488 MB GGUF.
         Target: S23 TE Host (Snapdragon 8 Gen 2, Hexagon Gen 2 V75).
         Output: whisper_small_en_encoder.onnx (~105 MB)

         FAILURE MODE A PREVENTION:
         - model.eval() MUST be called before export
         - opset_version MUST be 17
         - Input shape MUST be fixed (1, 80, 3000)
         - Export encoder attribute, NOT full model

Module:  scripts/
Program: ARIA — Automated Review Intelligence Assistant
         Army SBIR 24.4 | Topic A244-P037 | STELLiQ Technologies, LLC
Author:  [PI Name] / STELLiQ Engineering
Version: 1.1.0
Last Modified: [Date]
Classification: UNCLASSIFIED
"""

import torch
import whisper
import onnx
import os

# =============================================================================
# SECTION: Configuration
# =============================================================================

OUTPUT_PATH = "whisper_small_en_encoder.onnx"
MODEL_SIZE  = "small.en"   # LOCKED: Host (S23 TE) model. ADR-4.

# =============================================================================
# SECTION: Model Load + Encoder Isolation
# =============================================================================

print(f"[export] Loading whisper {MODEL_SIZE} model...")
model = whisper.load_model(MODEL_SIZE)

# WHY: Export encoder only. The decoder uses conditional branches (if/else on
#      token values) that torch.onnx.export cannot trace statically. Attempting
#      to export the full model will crash with "Unsupported ONNX op: If".
encoder = model.encoder

# WHY: model.eval() disables dropout and batch norm training modes.
#      Exporting in training mode produces ops (e.g., bernoulli) QNN does not support.
encoder.eval()

# =============================================================================
# SECTION: Fixed Input Shape — CRITICAL FOR NPU DELEGATION
# =============================================================================

mel_input = torch.zeros(1, 80, 3000)
print(f"[export] Encoder input shape: {mel_input.shape}")

# =============================================================================
# SECTION: ONNX Export
# =============================================================================

print(f"[export] Exporting to ONNX: {OUTPUT_PATH}")
torch.onnx.export(
    encoder,
    mel_input,
    OUTPUT_PATH,
    input_names=["mel"],
    output_names=["embeddings"],   # small.en output: (1, 1500, 768) — wider than tiny.en
    opset_version=17,              # LOCKED — do not change
    do_constant_folding=True,
    export_params=True,
    verbose=False,
)

import onnx as onnx_lib
print("[export] Validating ONNX model...")
onnx_lib.checker.check_model(onnx_lib.load(OUTPUT_PATH))
print(f"[export] ✅ ONNX export successful: {OUTPUT_PATH}")
print(f"[export] File size: {os.path.getsize(OUTPUT_PATH) / 1024 / 1024:.1f} MB")
print("[export] Input:  mel  (1, 80, 3000)")
print("[export] Output: embeddings  (1, 1500, 768)  ← small.en encoder output dim")
print("[export] Next: run export_whisper_encoder_tiny.py, then compile_qnn_context_binaries.py")
```

### 3.2 Export Script B — tiny.en (Contributor / XCover6 Pro TE)

Save as `scripts/export_whisper_encoder_tiny.py`:

```python
#!/usr/bin/env python3
"""
export_whisper_encoder_tiny.py

Purpose: Export the Whisper tiny.en encoder to ONNX format with fixed input shape
         suitable for QNN HTP context binary compilation targeting the XCover6 Pro TE
         Contributor nodes.

         Model: whisper tiny.en — 39M params, ~75 MB GGUF.
         Target: XCover6 Pro TE Contributor (Snapdragon 778G, Hexagon 770 V73).
         Output: whisper_tiny_en_encoder.onnx (~37 MB)

         WHY tiny.en for Contributor: V73 (Hexagon 770) op coverage for small.en
         is unvalidated. tiny.en V73 delegation is the proven path. See ADR-4.

Module:  scripts/
Program: ARIA — Automated Review Intelligence Assistant
         Army SBIR 24.4 | Topic A244-P037 | STELLiQ Technologies, LLC
Author:  [PI Name] / STELLiQ Engineering
Version: 1.1.0
Last Modified: [Date]
Classification: UNCLASSIFIED
"""

import torch
import whisper
import onnx
import os

OUTPUT_PATH = "whisper_tiny_en_encoder.onnx"
MODEL_SIZE  = "tiny.en"   # LOCKED: Contributor (XCover6 Pro TE) model. ADR-4.

print(f"[export] Loading whisper {MODEL_SIZE} model...")
model   = whisper.load_model(MODEL_SIZE)
encoder = model.encoder
encoder.eval()

mel_input = torch.zeros(1, 80, 3000)

print(f"[export] Exporting to ONNX: {OUTPUT_PATH}")
torch.onnx.export(
    encoder, mel_input, OUTPUT_PATH,
    input_names=["mel"],
    output_names=["embeddings"],   # tiny.en output: (1, 1500, 384)
    opset_version=17,
    do_constant_folding=True,
    export_params=True,
    verbose=False,
)

import onnx as onnx_lib
onnx_lib.checker.check_model(onnx_lib.load(OUTPUT_PATH))
print(f"[export] ✅ ONNX export successful: {OUTPUT_PATH}")
print(f"[export] File size: {os.path.getsize(OUTPUT_PATH) / 1024 / 1024:.1f} MB")
print("[export] Input:  mel  (1, 80, 3000)")
print("[export] Output: embeddings  (1, 1500, 384)  ← tiny.en encoder output dim")
print("[export] Next: run scripts/compile_qnn_context_binaries.py")
```

### 3.3 Run and Verify Both Exports

```bash
# From WSL2 terminal, in ~/aria-atak-plugin/
python3 scripts/export_whisper_encoder_small.py
python3 scripts/export_whisper_encoder_tiny.py

# Expected outputs:
# whisper_small_en_encoder.onnx   ~105 MB   (Host — small.en)
# whisper_tiny_en_encoder.onnx    ~37 MB    (Contributor — tiny.en)

# STOP if you see any of these — do not proceed:
# "Unsupported ONNX op: If"       → You exported the full model, not encoder only
# "Unsupported ONNX op: Loop"     → Same issue
# File size > 200 MB              → Wrong model size exported
# File size < 30 MB               → Check model name — may have loaded wrong variant
```

---

## SECTION 4 — STEP 2: AI HUB CONTEXT BINARY COMPILATION

### 4.1 Why AI Hub First

Use AI Hub (cloud compile) before the local Docker toolchain. Reasons:
- Validates your ONNX file is correct before investing time in Docker setup
- Generates a Qualcomm-guaranteed context binary for the exact target SoC
- Provides real latency benchmarks on physical S23 TE hardware (not estimated)
- Takes 5–15 minutes. Docker local compile takes 1–2 hours first time.

### 4.2 The Compile Script

Save as `scripts/compile_qnn_context_binaries.py`:

```python
#!/usr/bin/env python3
"""
compile_qnn_context_binaries.py

Purpose: Submit Whisper encoder ONNX files to Qualcomm AI Hub for compilation
         into QNN HTP context binaries.

         ADR-4 TWO-MODEL SPLIT:
         - S23 TE Host:        small.en ONNX → whisper_encoder_s23te.bin  (V75)
         - XCover6 Contributor: tiny.en ONNX → whisper_encoder_778g.bin   (V73)

         FAILURE MODE B PREVENTION:
         - Always compile S23 TE (8 Gen 2) FIRST. It has a broader HTP op set.
           If S23 TE compile fails, XCover6 will also fail. Fix S23 TE first.
         - opset_version=17 in the ONNX export is the prerequisite for this step.
         - If "unsupported op" error: check QIDK SpeechRecognition for op folding.

Module:  scripts/
Program: ARIA — Automated Review Intelligence Assistant
         Army SBIR 24.4 | Topic A244-P037 | STELLiQ Technologies, LLC
Author:  [PI Name] / STELLiQ Engineering
Version: 1.1.0
Last Modified: [Date]
Classification: UNCLASSIFIED
"""

import qai_hub as hub
import time
import os

# =============================================================================
# SECTION: Configuration — ADR-4 Two-Model Split
# =============================================================================

# Host S23 TE uses small.en; Contributor XCover6 uses tiny.en. See ADR-4.
ONNX_SMALL_EN      = "whisper_small_en_encoder.onnx"   # Host — small.en, ~105 MB
ONNX_TINY_EN       = "whisper_tiny_en_encoder.onnx"    # Contributor — tiny.en, ~37 MB
OUTPUT_S23TE       = "app/src/main/jniLibs/arm64-v8a/whisper_encoder_s23te.bin"
OUTPUT_778G        = "app/src/main/jniLibs/arm64-v8a/whisper_encoder_778g.bin"

# WHY: Compile S23 TE first. Snapdragon 8 Gen 2 Hexagon Gen 2 HTP has a broader
#      supported op catalog than the Hexagon 770 (778G). If S23 TE succeeds but
#      778G fails with "unsupported op", that is a device-specific constraint.
COMPILE_TARGETS = [
    {
        "name":    "S23 TE Host (Snapdragon 8 Gen 2) — small.en",
        "device":  "Samsung Galaxy S23 (Family)",   # AI Hub device name — exact string
        "onnx":    ONNX_SMALL_EN,
        "output":  OUTPUT_S23TE,
        "model":   "small.en",
    },
    {
        "name":    "XCover6 Pro TE Contributor (Snapdragon 778G) — tiny.en",
        "device":  "Samsung Galaxy XCover6 Pro",    # AI Hub device name — exact string
        "onnx":    ONNX_TINY_EN,
        "output":  OUTPUT_778G,
        "model":   "tiny.en",
    },
]

# WHY: Fixed input shape must match the ONNX export exactly.
INPUT_SPECS = {"mel": (1, 80, 3000)}

# =============================================================================
# SECTION: Pre-flight Check
# =============================================================================

for t in COMPILE_TARGETS:
    if not os.path.exists(t["onnx"]):
        raise FileNotFoundError(
            f"[compile] ONNX file not found: {t['onnx']}\n"
            f"          Run export_whisper_encoder_small.py and export_whisper_encoder_tiny.py first."
        )

os.makedirs("app/src/main/jniLibs/arm64-v8a", exist_ok=True)
print(f"[compile] small.en ONNX: {ONNX_SMALL_EN}  ({os.path.getsize(ONNX_SMALL_EN)/1024/1024:.1f} MB)")
print(f"[compile] tiny.en  ONNX: {ONNX_TINY_EN}   ({os.path.getsize(ONNX_TINY_EN)/1024/1024:.1f} MB)")

# =============================================================================
# SECTION: Compile Loop (S23 TE first, then 778G)
# =============================================================================

for target in COMPILE_TARGETS:
    print(f"\n[compile] ─── Target: {target['name']} ───")
    print(f"[compile] Submitting compile job to AI Hub...")
    print(f"[compile] Device: {target['device']}")
    print(f"[compile] Model:  {target['model']}")
    ONNX_PATH = target["onnx"]

    try:
        compile_job = hub.submit_compile_job(
            model=ONNX_PATH,
            device=hub.Device(target["device"]),
            options="--target_runtime qnn_context_binary",
            input_specs=INPUT_SPECS,
        )
        print(f"[compile] Job ID: {compile_job.job_id}")
        print(f"[compile] Waiting for completion (typically 5–15 minutes)...")

        # WHY: wait() blocks until terminal state. Check success before download.
        result = compile_job.wait()
        if not result.success:
            # RISK: If compile fails here, check AI Hub job log URL for op errors.
            #       Do not proceed to 778G compile if S23 TE fails.
            raise RuntimeError(
                f"[compile] ❌ COMPILE FAILED for {target['name']}\n"
                f"          Job ID: {compile_job.job_id}\n"
                f"          Check job log at: https://aihub.qualcomm.com/jobs/{compile_job.job_id}\n"
                f"          Common cause: unsupported op in encoder graph.\n"
                f"          Fix: see Section 4.3 (Op Fallback) in this guide."
            )

        print(f"[compile] ✅ Compile successful.")
        compile_job.download_target_model(target["output"])
        print(f"[compile] Downloaded: {target['output']}")
        print(f"[compile] Size: {os.path.getsize(target['output']) / 1024 / 1024:.1f} MB")

        # Optionally run a profile job to get real HTP latency data (for D-01)
        print(f"[compile] Submitting profile job for latency benchmark...")
        profile_job = hub.submit_profile_job(
            model=compile_job.get_target_model(),
            device=hub.Device(target["device"]),
        )
        profile_result = profile_job.wait()
        if profile_result.success:
            profile_data = profile_job.download_profile()
            latency_ms = profile_data.get("inference_time", "N/A")
            print(f"[compile] HTP inference latency: {latency_ms} ms  ← log this for D-01")
        else:
            print(f"[compile] Profile job failed (non-blocking) — resubmit manually if needed.")

    except Exception as e:
        print(f"[compile] ❌ ERROR on {target['name']}: {e}")
        if "S23" in target["name"]:
            raise  # S23 TE failure is fatal — stop here
        else:
            print(f"[compile] ⚠ XCover6 778G compile failed. See Section 7 (Fallback) in this guide.")
            print(f"[compile] Continuing — S23 TE binary is usable for Host node.")

print("\n[compile] ─── Summary ───")
for target in COMPILE_TARGETS:
    exists = os.path.exists(target["output"])
    status = "✅" if exists else "❌ MISSING"
    print(f"  {status}  {target['output']}")

print("\n[compile] Next step: Section 5 — CMakeLists.txt + JNI integration")
```

### 4.3 If Compile Fails with "Unsupported Op"

This is Failure Mode B. Before escalating to PI, try:

1. Check the AI Hub job log at `https://aihub.qualcomm.com/jobs/JOB_ID` — note the exact op name.
2. Compare against the QIDK SpeechRecognition op set. The QIDK example has pre-export transforms
   that fold problematic ops (LayerNorm, GELU variants) into HTP-supported equivalents.
3. Run: `ls /opt/qidk/SpeechRecognition/` — adapt their export script instead of the one in
   Section 3.1 if their version succeeds where yours fails.
4. If the failure is on XCover6 778G only (S23 TE succeeded): implement the TFLite GPU fallback
   described in Section 7. Do not block S23 TE development on XCover6 778G.

---

## SECTION 5 — STEP 3: CMAKELISTSTS.TXT (WHISPER QNN BUILD)

### 5.1 Required `.so` Files in `jniLibs/arm64-v8a/`

> **FAILURE MODE C PREVENTION:** This is the most common silent failure.
> The context binary loads successfully but HTP delegation count = 0 because
> the HTP stub libraries are missing. All four files below are required.

```
app/src/main/jniLibs/arm64-v8a/
├── libQnnHtp.so              ← Core HTP runtime (required on all devices)
├── libQnnHtpPrepare.so       ← HTP graph preparation (required on all devices)
├── libQnnHtpV75Stub.so       ← S23 TE stub (Snapdragon 8 Gen 2 = Hexagon V75)
└── libQnnHtpV73Stub.so       ← XCover6 Pro stub (778G = Hexagon V73)
```

**Source:** Extract from QNN SDK zip downloaded from `softwarecenter.qualcomm.com`.
Path inside SDK: `lib/aarch64-android/libQnnHtp*.so`

```bash
# From WSL2, after extracting QNN SDK to /opt/qnn-sdk:
mkdir -p ~/aria-atak-plugin/app/src/main/jniLibs/arm64-v8a/
cp /opt/qnn-sdk/lib/aarch64-android/libQnnHtp.so \
   /opt/qnn-sdk/lib/aarch64-android/libQnnHtpPrepare.so \
   /opt/qnn-sdk/lib/aarch64-android/libQnnHtpV75Stub.so \
   /opt/qnn-sdk/lib/aarch64-android/libQnnHtpV73Stub.so \
   ~/aria-atak-plugin/app/src/main/jniLibs/arm64-v8a/

# Verify all four files are present and non-zero:
ls -lh ~/aria-atak-plugin/app/src/main/jniLibs/arm64-v8a/libQnn*.so
```

### 5.2 CMakeLists.txt — Whisper QNN Section

> **FAILURE MODE D PREVENTION:** This is the exact CMake configuration required.
> Do not omit `examples/qnn/qnn-lib.cpp`. Do not remove `WHISPER_USE_QNN`.

```cmake
# ---------------------------------------------------------------------------
# whisper.cpp — ASR inference library
# Configured for QNN HTP backend (Whisper encoder context binary only).
# Decoder runs on CPU — encoder is the compute bottleneck; QNN HTP offload
# achieves RTF ≤ 0.5x target per ARIA performance spec.
#
# FAILURE MODE D PREVENTION:
# - examples/qnn/qnn-lib.cpp MUST be included — it is the QNN backend glue
# - WHISPER_USE_QNN and GGML_USE_QNN MUST be defined
# - QNN_LIB_DIR must point to arm64-v8a jniLibs where libQnnHtp*.so live
# ---------------------------------------------------------------------------

set(WHISPER_SRC ${CMAKE_SOURCE_DIR}/../../../whisper.cpp)
set(QNN_LIB_DIR ${CMAKE_SOURCE_DIR}/../jniLibs/arm64-v8a)

set(WHISPER_SOURCES
    ${WHISPER_SRC}/whisper.cpp
    ${WHISPER_SRC}/ggml.c
    ${WHISPER_SRC}/ggml-alloc.c
    ${WHISPER_SRC}/ggml-backend.c
    ${WHISPER_SRC}/ggml-quants.c
    # QNN backend integration — REQUIRED. Do not remove.
    ${WHISPER_SRC}/examples/qnn/qnn-lib.cpp
)

add_library(whisper STATIC ${WHISPER_SOURCES})

target_include_directories(whisper PUBLIC
    ${WHISPER_SRC}
    ${WHISPER_SRC}/examples/qnn      # Required for qnn-lib.h
    /opt/qnn-sdk/include/QNN         # QNN SDK headers (inside Docker or WSL2)
)

target_compile_definitions(whisper PRIVATE
    WHISPER_USE_QNN      # Enables QNN HTP encoder path in whisper.cpp
    GGML_USE_QNN         # Enables QNN backend in ggml layer
    NDEBUG
)

# Link QNN runtime stubs — required for dynamic loading of HTP backend
find_library(QNN_HTP_LIB QnnHtp PATHS ${QNN_LIB_DIR} NO_DEFAULT_PATH)
find_library(QNN_HTP_PREPARE_LIB QnnHtpPrepare PATHS ${QNN_LIB_DIR} NO_DEFAULT_PATH)

if(NOT QNN_HTP_LIB)
    message(FATAL_ERROR
        "libQnnHtp.so not found in ${QNN_LIB_DIR}. "
        "Copy from QNN SDK lib/aarch64-android/ — see Section 5.1 of "
        "ARIA_QNN_Whisper_NPU_Implementation_Guide.md"
    )
endif()

target_link_libraries(whisper
    ${QNN_HTP_LIB}
    ${QNN_HTP_PREPARE_LIB}
    ${log-lib}
    ${android-lib}
)
```

---

## SECTION 6 — STEP 4: JNI INITIALIZATION ORDER

> **FAILURE MODE C PREVENTION (JAVA SIDE):** The QNN HTP runtime libraries must
> be loaded in the correct order before the context binary is opened. Wrong order
> = silent CPU fallback, HTP delegation count = 0, no error thrown.

### 6.1 Mandatory Library Load Order in `WhisperEngine.java`

```java
// =============================================================================
// SECTION: QNN Runtime Library Load — ORDER IS MANDATORY
// WHY: libQnnHtp.so depends on libQnnHtpPrepare.so. Loading Htp before Prepare
//      causes a silent linker failure — the context binary open succeeds but
//      delegates zero ops to HTP. The delegation count in logcat will be 0.
//      Load Prepare first, then Htp, then the JNI bridge last.
// =============================================================================

static {
    // Step 1: HTP preparation library (dependency of libQnnHtp.so)
    System.loadLibrary("QnnHtpPrepare");

    // Step 2: HTP core runtime
    System.loadLibrary("QnnHtp");

    // Step 3: whisper.cpp JNI bridge (depends on QnnHtp being loaded)
    System.loadLibrary("asr_jni");
}
```

### 6.2 Verify HTP Delegation via ADB Logcat

After APK install and first transcription call, run this from Windows ADB:

```bash
adb logcat | grep -E "HTP|hexagon|QNN|delegation" --color=always

# ✅ SUCCESS indicators (any of these confirm HTP is active):
#    "Using QNN HTP backend"
#    "HTP delegation count: N"   where N > 0
#    "Hexagon HTP delegate"

# ❌ FAILURE indicators (CPU fallback — investigate):
#    "HTP delegation count: 0"
#    "Falling back to CPU"
#    "QnnHtp initialization failed"

# If delegation count = 0, check in this order:
# 1. All four libQnnHtp*.so files present in APK jniLibs (adb shell, unzip)
# 2. Library load order (Section 6.1)
# 3. Context binary path passed to loadModel() is correct and file exists
# 4. Device SoC matches the compiled context binary (V75 for S23, V73 for XCover6)
```

---

## SECTION 7 — XCOVERB PRO 778G FALLBACK STRATEGY

If the XCover6 778G context binary compile fails with an unsupported op error that
cannot be resolved via QIDK op folding, implement this fallback:

**XCover6 778G Fallback: CPU Decoder Mode**

The XCover6 Pro is a Contributor node — it only runs Whisper ASR, not the LLM.
Its thermal envelope is less constrained than the Host (no LLM running concurrently).
CPU-mode Whisper tiny.en on the 778G (Adreno 642L) achieves RTF ≈ 0.6–0.8x,
which is above the ≤ 0.5x target but acceptable as a Phase I fallback.

```java
// WHY: Device-specific context binary selection with graceful CPU fallback.
//      XCover6 778G (Hexagon V73) may not support all encoder ops in tiny.en.
//      If QNN context binary fails to load on 778G, fall back to CPU inference.
//      Host (S23 TE) always uses QNN — no fallback acceptable on Host.
//
// PERF: QNN path target RTF ≤ 0.5x. CPU fallback RTF ≈ 0.6–0.8x.
//       Log actual RTF to ARIA_PERF on every invocation regardless of path.

private String selectContextBinaryPath(Context ctx) {
    String board = Build.BOARD.toLowerCase();
    String soc   = SystemProperties.get("ro.board.platform", "").toLowerCase();

    if (soc.contains("sm8550") || board.contains("kalama")) {
        // S23 TE — Snapdragon 8 Gen 2. QNN required — no fallback.
        return copyAssetToFilesDir(ctx, Constants.WHISPER_MODEL_S23TE_BIN);
    } else if (soc.contains("sm7450") || board.contains("cape")) {
        // XCover6 Pro — Snapdragon 778G. Try QNN; fall back to CPU.
        File binFile = new File(ctx.getFilesDir(), Constants.WHISPER_MODEL_778G_BIN);
        if (binFile.exists() && binFile.length() > 0) {
            return binFile.getAbsolutePath();
        } else {
            Log.w(ARIA_TAG, "[WhisperEngine.selectContextBinaryPath] "
                + "778G context binary not found — falling back to CPU inference. "
                + "RTF may exceed 0.5x target. Log actual RTF for D-01 report.");
            return null;  // null = CPU path in loadModel()
        }
    } else {
        Log.w(ARIA_TAG, "[WhisperEngine.selectContextBinaryPath] "
            + "Unknown SoC: " + soc + " / board: " + board
            + " — defaulting to CPU inference. Flag this for PI.");
        return null;
    }
}
```

---

## SECTION 8 — THERMAL MANAGEMENT (CPU PATH INTERIM)

Until QNN is working, the CPU-only path will overheat before LLM handoff.
Apply these mitigations immediately — they apply to both CPU and QNN paths:

```java
// PERF: Inter-window thermal yield. Insert between each 10-second Whisper window.
//       Target: keep SoC temp below 75°C before LLM handoff.
//       At RTF ≤ 0.5x, a 10s window completes in ≤ 5s, leaving ≥ 5s of margin.
//       750ms yield does not blow the RTF budget.
private static final long INTER_WINDOW_YIELD_MS = 750L;

// PERF: CPU thread count for Whisper tiny.en.
//       2 threads is optimal on Snapdragon big cores for this model size.
//       More threads = more heat, no RTF improvement at 39M params.
private static final int WHISPER_CPU_THREADS = 2;

// PERF: Thermal gate before LLM handoff.
//       Block LLM launch until CPU temp < 75°C, or timeout after 30s.
private void waitForThermalClearance() {
    // TODO(sprint-A4): implement sysfs thermal zone poll
    // /sys/class/thermal/thermal_zone*/temp
}
```

---

## SECTION 9 — CLAUDE.MD UPDATE INSTRUCTIONS

> **FOR CLAUDE CODE:** After successfully completing the QNN integration (HTP delegation
> count > 0 confirmed via logcat), update `CLAUDE.md` as follows:

### 9.1 Add to Section 12.0 PROHIBITED PATTERNS

```
PROHIBITED: Exporting Whisper decoder to ONNX — encoder only, input shape (1,80,3000)
PROHIBITED: Using opset_version < 17 in Whisper ONNX export
PROHIBITED: Omitting examples/qnn/qnn-lib.cpp from WHISPER_SOURCES in CMakeLists.txt
PROHIBITED: Loading System.loadLibrary("asr_jni") before System.loadLibrary("QnnHtpPrepare") and System.loadLibrary("QnnHtp")
PROHIBITED: Deploying APK without all four libQnnHtp*.so files in jniLibs/arm64-v8a/
PROHIBITED: Using dynamic input shapes in AI Hub compile job — always use (1, 80, 3000)
PROHIBITED: Skipping QIDK SpeechRecognition reference when writing Whisper ONNX export
```

### 9.2 Update Section 4.1 ASR Engine ADR Table

Replace the ADR-2 Rationale with:

```
ADR-2: whisper.cpp with QNN HTP backend. Model: tiny.en (39M params, ~75 MB GGUF).
Encoder: QNN INT8 HTP context binary (.bin). Decoder: CPU.
Fixed encoder input shape (1×80×3000) is mandatory for NPU delegation.
Library load order: QnnHtpPrepare → QnnHtp → asr_jni (mandatory, non-negotiable).
All four libQnnHtp*.so files must be present in jniLibs/arm64-v8a/.
QIDK SpeechRecognition is the canonical export reference — never write from scratch.
```

### 9.3 Update Section 13.0 Risk Register

Add:

```
R8 | XCover6 778G Hexagon V73 unsupported op in tiny.en encoder | Medium | Medium |
CPU fallback path implemented in WhisperEngine.selectContextBinaryPath().
Phase II: evaluate whisper-base or encoder op folding for full 778G HTP delegation.
```

---

## SECTION 10 — SPRINT GATE CRITERIA

Sprint A-4 is COMPLETE when ALL of the following are true:

- [ ] `whisper_encoder_s23te.bin` compiled, downloaded, present in `jniLibs/arm64-v8a/`
- [ ] `whisper_encoder_778g.bin` compiled OR XCover6 CPU fallback implemented and logged
- [ ] APK installs and runs on S23 TE without crash
- [ ] `adb logcat | grep HTP` shows delegation count > 0 on S23 TE
- [ ] At least one 10-second audio window transcribed successfully via QNN path
- [ ] RTF measured and logged to `ARIA_PERF` logcat tag
- [ ] AI Hub profile job latency recorded for D-01 report

**Do not declare Sprint A-4 complete if HTP delegation count = 0.** CPU fallback is an
interim thermal mitigation only — it is not a Sprint A-4 deliverable.

---

*ARIA_QNN_Whisper_NPU_Implementation_Guide.md v1.1*
*v1.1 Changes: ADR-4 two-model split implemented — small.en (244M) on S23 TE Host,*
*tiny.en (39M) on XCover6 Pro TE Contributor. Device table updated with model column.*
*Section 3 split into two export scripts (export_whisper_encoder_small.py +*
*export_whisper_encoder_tiny.py). Section 4 compile script updated for two ONNX inputs.*
*ONNX output dim comments updated (small.en: 768 wide vs tiny.en: 384 wide).*
*UNCLASSIFIED // FOR OFFICIAL USE ONLY (FOUO)*
*STELLiQ Technologies, LLC | Army SBIR 24.4 | Topic A244-P037*
