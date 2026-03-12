# GPU Whisper Acceleration Plan

## Objective
Enable GPU (OpenCL) acceleration for Whisper ASR on Adreno 750 (Samsung Galaxy S24 Ultra)
and reduce the sliding window from 5s to 3s for near-real-time transcription.

## Target Performance
| Metric | Current (CPU 5s) | Target (GPU 3s) |
|--------|-----------------|-----------------|
| Window fill time | 5.0s | 3.0s |
| Whisper inference | ~7.5s (RTF 1.5x) | ~1.0-2.0s (RTF 0.3-0.6x) |
| **Total latency** | **~12.5s** | **~4.0-5.0s** |
| First text appearance | ~12.5s after speech | ~4-5s after speech |

## Architecture Change
```
BEFORE (CPU-only, hidden symbols):
  libasr_jni.so
    ├── whisper.cpp (STATIC)
    ├── ggml (STATIC, CPU-only)       ← No GPU
    └── symbols HIDDEN via version script

AFTER (GPU-enabled, hidden symbols):
  libasr_jni.so
    ├── whisper.cpp (STATIC)
    ├── ggml (STATIC, CPU)            ← Still here
    ├── ggml-opencl (STATIC, GPU)     ← NEW: Adreno OpenCL backend
    ├── links: -lOpenCL (runtime)     ← Resolved from vendor libOpenCL.so
    └── symbols HIDDEN via version script (still works!)
```

Key insight: ggml-opencl is built STATIC and hidden by the version script.
No symbol conflicts with llama's prebuilt libggml-opencl.so because
libasr_jni.so doesn't export any ggml symbols.

## Prerequisites
- Samsung Galaxy S24 Ultra (R5CWC3M25GL) connected via ADB
- Python 3 on build machine (for OpenCL kernel embedding)
- Current build passing (baseline)

---

## Phase 1: OpenCL Build Infrastructure
**Goal:** Get whisper's ggml-opencl backend compiling as STATIC on Android NDK.

### Task 1.1: Provide OpenCL Headers
OpenCL headers are NOT in the Android NDK. We need Khronos headers for compilation.
The ggml-opencl.cpp `#include <CL/cl.h>` requires: cl.h, cl_platform.h, cl_ext.h, cl_version.h.

**Action:** Create `app/src/main/cpp/opencl-headers/CL/` with Khronos OpenCL headers.
**Source:** Extract from device or download from https://github.com/KhronosGroup/OpenCL-Headers

### Task 1.2: Provide OpenCL Stub Library for Linking
ggml-opencl links against `${OpenCL_LIBRARIES}`. On Android NDK, there's no libOpenCL.so
in the sysroot. We need a link-time reference that gets resolved at runtime from
`/system/vendor/lib64/libOpenCL.so` on the device.

**Action:** Pull libOpenCL.so from S24 Ultra as link-time stub:
```bash
adb -s R5CWC3M25GL pull /system/vendor/lib64/libOpenCL.so app/src/main/cpp/opencl-stub/
```

### Task 1.3: Modify CMakeLists.txt — Enable GGML_OPENCL
Changes to `app/src/main/cpp/CMakeLists.txt`:

1. Change `GGML_OPENCL OFF` → `GGML_OPENCL ON` (line 57)
2. Set `GGML_OPENCL_USE_ADRENO_KERNELS ON` (Adreno 750 optimized kernels)
3. Set `GGML_OPENCL_EMBED_KERNELS ON` (embed .cl kernels into binary — no file deployment)
4. Set `GGML_OPENCL_TARGET_VERSION 200` (OpenCL 2.0 — S24 Ultra supports this)
5. Bypass `find_package(OpenCL REQUIRED)` by pre-setting `OpenCL_FOUND`, `OpenCL_INCLUDE_DIRS`, `OpenCL_LIBRARIES`
6. Bypass `find_package(Python3 REQUIRED)` by pre-setting `Python3_EXECUTABLE`
7. Add `-lOpenCL` to libasr_jni.so link (for runtime symbol resolution)

**Critical:** `BUILD_SHARED_LIBS=OFF` is already set before FetchContent.
The `ggml_add_backend_library()` function (ggml/src/CMakeLists.txt:260) uses
`add_library(${backend} ${ARGN})` which respects this, creating STATIC targets.
So ggml-opencl will be STATIC → embedded in libasr_jni.so → hidden by version script.

### Task 1.4: Handle OpenCL Kernel Embedding
The ggml-opencl CMakeLists.txt uses Python3 to run `embed_kernel.py` which converts
.cl kernel files into C header strings. With `GGML_OPENCL_EMBED_KERNELS=ON`, kernels
are compiled into the binary (no .cl file deployment needed in APK).

**Action:** Ensure Python3 is findable by CMake. Set `Python3_EXECUTABLE` if needed.

### Task 1.5: Update Version Script
The current `asr_jni.version` hides ALL non-JNI symbols. This is correct — OpenCL
function calls (clCreateContext etc.) are IMPORTED symbols resolved from the system
libOpenCL.so, not exported from libasr_jni.so. The version script only affects EXPORTED
symbols, so no change is needed.

**Verify:** asr_jni.version stays as-is.

### Test 1: Build Compiles
```bash
cmd.exe //c "set JAVA_HOME=C:\Program Files\Android\Android Studio1\jbr&& .\gradlew.bat assembleDebug"
```
- [ ] Build succeeds with GGML_OPENCL=ON
- [ ] No linker errors for OpenCL symbols
- [ ] libasr_jni.so produced

### Test 2: Symbol Isolation Verified
```bash
adb -s R5CWC3M25GL shell "readelf -W --dyn-syms /data/app/*/com.stelliq.aria*/lib/arm64/libasr_jni.so" | grep -i ggml
```
- [ ] No ggml symbols exported from libasr_jni.so
- [ ] Version script still hiding symbols correctly

---

## Phase 2: GPU Whisper Activation
**Goal:** Enable GPU in whisper_jni.cpp and verify transcription works on Adreno 750.

### Task 2.1: Enable GPU in whisper_jni.cpp
**File:** `app/src/main/cpp/whisper_jni.cpp`

Change line 175:
```cpp
cparams.use_gpu = false;  // CPU-only for Whisper ASR
```
To:
```cpp
cparams.use_gpu = true;  // GPU acceleration on Adreno 750 (OpenCL)
```

### Task 2.2: Reduce Thread Count for GPU Mode
With GPU doing the heavy matrix ops, fewer CPU threads are needed.
Currently 6 threads (line 241). With GPU, 2-4 threads may be optimal.

**Action:** Consider reducing `params.n_threads` from 6 to 4 when GPU is active.
The CPU handles non-GPU ops (tokenization, sampling) while GPU handles matmul.

### Task 2.3: Adjust Abort Timeout
Current abort timeout is 20s (line 70), calibrated for CPU.
GPU inference should be much faster, but OpenCL kernel JIT compilation on first
run can take 10-20s. After kernels are cached, inference is fast.

**Action:** Keep 20s timeout initially. Consider reducing after JIT is warm.

### Task 2.4: Add GPU Diagnostic Logging
Add logging after model load to confirm GPU backend is active:
```cpp
LOGI("Whisper GPU mode: %s", cparams.use_gpu ? "ENABLED" : "DISABLED");
```

### Test 3: Whisper Loads with GPU
```bash
adb -s R5CWC3M25GL logcat -s ARIA_ASR_JNI:V | grep -i "gpu\|opencl\|loaded"
```
- [ ] Log shows GPU/OpenCL initialization
- [ ] Model loads without crash
- [ ] First load may take 15-25s (kernel JIT) — this is expected

### Test 4: Transcription Produces Output
- [ ] Start recording, speak clearly for 10s, stop
- [ ] Transcript appears in UI
- [ ] No hallucination artifacts from GPU compute
- [ ] logcat shows inference time (should be < 5s for 5s window)

---

## Phase 3: Window Optimization (5s → 3s)
**Goal:** Reduce window/stride from 5s to 3s for lower latency.

### Task 3.1: Update Constants.java
**File:** `app/src/main/java/com/stelliq/aria/util/Constants.java`

| Constant | Old Value | New Value |
|----------|-----------|-----------|
| `WINDOW_SAMPLES` (line 153) | `80_000` | `48_000` |
| `WINDOW_DURATION_MS` (line 158) | `5000` | `3000` |
| `STRIDE_SAMPLES` (line 175) | `80_000` | `48_000` |
| `STRIDE_DURATION_MS` (line 180) | `5000` | `3000` |

Also update Javadoc comments on these constants.

### Task 3.2: Update SlidingWindowBuffer Comments
**File:** `app/src/main/java/com/stelliq/aria/asr/SlidingWindowBuffer.java`

Update comments referencing "5s window" to "3s window".

### Task 3.3: Update whisper_jni.cpp Comments
**File:** `app/src/main/cpp/whisper_jni.cpp`

Update abort timeout comment (line 68) and any "5s window" references.

### Task 3.4: Verify VAD Compatibility
SileroVAD processes the window in 512-sample chunks. With 48,000 samples (3s),
that's 93 chunks instead of 156. VAD should work fine — it's designed for
segments as short as 512 samples (32ms).

**Action:** Verify `SileroVAD.isSpeech()` still works with 48,000-sample windows.
The LSTM state reset before each window (SlidingWindowBuffer line 182) is
already correct behavior.

### Test 5: 3s Window Emission
```bash
adb -s R5CWC3M25GL logcat -s ARIA_ASR:V | grep "Window emitted\|hasWindow\|Posting window"
```
- [ ] Windows emit every ~3s during recording
- [ ] No audio data loss at boundaries
- [ ] VAD correctly filters silence windows

### Test 6: Transcription Quality
- [ ] Record 30s of clear speech
- [ ] Compare transcript quality with previous 5s window
- [ ] No excessive word errors from shorter context
- [ ] Timestamps in transcript are accurate

---

## Phase 4: Integration & Validation
**Goal:** Verify the full pipeline works end-to-end with GPU Whisper + GPU LLM.

### Task 4.1: Full Pipeline Test
1. Launch ARIA
2. Start recording (GPU Whisper transcription with 3s windows)
3. Speak for 30-60 seconds
4. Stop recording (triggers summarization with GPU LLM)
5. Verify summary appears

### Task 4.2: Verify Whisper Release Before LLM Load
**Critical path:** `runSummarization()` calls `mWhisperEngine.release()` before
loading the LLM. With GPU Whisper, release must also free OpenCL resources.

**Verify:** `whisper_free()` in whisper_jni.cpp properly releases GPU buffers.
whisper.cpp's `whisper_free()` calls `ggml_free()` which should clean up
the OpenCL backend's GPU allocations.

### Task 4.3: No LLM GPU Regression
After whisper releases the GPU, llama must still initialize OpenCL successfully.
The Adreno driver should allow a new OpenCL context after the previous one is freed.

- [ ] LLM loads with GPU after Whisper release
- [ ] TTFT and decode speed unchanged from baseline
- [ ] No GPU crashes or ANR

### Task 4.4: Cancellation Still Works
Test the cancellation feature implemented earlier:
1. Start recording → stop → processing starts
2. Press back/X button during "Processing"
3. Confirm cancel dialog
4. Start new recording immediately

- [ ] Cancel interrupts LLM inference
- [ ] New recording starts cleanly
- [ ] No zombie GPU contexts

### Task 4.5: Performance Benchmarking
Capture timing metrics from logcat:
```bash
adb -s R5CWC3M25GL logcat -s ARIA_ASR_JNI:I ARIA_ASR:I ARIA_SERVICE:I | grep -i "ms\|inference\|window\|loaded"
```

| Metric | Baseline (CPU 5s) | Target (GPU 3s) | Actual |
|--------|-------------------|-----------------|--------|
| Model load time | 1.5-3.0s | 3-5s (+ kernel JIT) | |
| 2nd+ load (cached) | 1.5-3.0s | 1.5-3.0s | |
| Inference per window | 7.5s | 1.0-2.0s | |
| End-to-end latency | 12.5s | 4.0-5.0s | |

### Test 7: Memory Profile
```bash
adb -s R5CWC3M25GL shell dumpsys meminfo com.stelliq.aria
```
- [ ] GPU memory usage reasonable during Whisper
- [ ] Memory freed after Whisper release
- [ ] No memory leak across multiple recordings

---

## Phase 5: Optimization & Hardening
**Goal:** Handle edge cases and optimize for production.

### Task 5.1: CPU Fallback
If OpenCL initialization fails (older device, driver issue), fall back to CPU.
whisper.cpp should handle this internally when `use_gpu = true` but no GPU backend
is available. Verify graceful degradation.

### Task 5.2: OpenCL Kernel Caching
After first launch, OpenCL kernels should be cached by the Adreno driver.
Verify that subsequent launches don't repeat the 10-20s JIT compilation.

### Task 5.3: Error Handling
Add error handling for GPU-specific failures:
- OpenCL context creation failure
- GPU out-of-memory during Whisper inference
- Driver crash recovery

### Task 5.4: Update CLAUDE.md Documentation
Update all CLAUDE.md files in:
- `app/src/main/cpp/CLAUDE.md` — GPU architecture
- `app/src/main/java/com/stelliq/aria/asr/CLAUDE.md` — 3s window, GPU pipeline
- `app/src/main/java/com/stelliq/aria/service/CLAUDE.md` — updated timing expectations

---

## Risk Register

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| OpenCL kernel JIT takes 15-20s on first launch | High | Medium | Expected behavior — kernels cached after first run |
| ggml-opencl STATIC conflicts with prebuilt libggml-opencl.so | Low | High | Version script hides all ggml symbols; STATIC doesn't export |
| Whisper accuracy degrades with 3s windows | Medium | Medium | Can revert to 5s if quality unacceptable |
| GPU OOM when Whisper + LLM both allocated | Low | High | Sequential: Whisper released before LLM loads |
| OpenCL headers version mismatch | Low | Medium | Use headers from same ggml source tree |
| Python3 not found for kernel embedding | Medium | Low | Set Python3_EXECUTABLE explicitly in CMake |

## Files Modified Summary

| File | Change |
|------|--------|
| `app/src/main/cpp/CMakeLists.txt` | Enable GGML_OPENCL, set Adreno flags, provide OpenCL paths |
| `app/src/main/cpp/whisper_jni.cpp` | `use_gpu = true`, diagnostic logging |
| `app/src/main/cpp/opencl-headers/CL/*.h` | NEW: Khronos OpenCL headers for compilation |
| `app/src/main/cpp/opencl-stub/libOpenCL.so` | NEW: Link-time reference from device |
| `app/src/main/java/.../util/Constants.java` | Window 5s→3s, stride 5s→3s |
| `app/src/main/java/.../asr/SlidingWindowBuffer.java` | Updated comments |
| `app/src/main/cpp/whisper_jni.cpp` | Updated comments, GPU flag |
