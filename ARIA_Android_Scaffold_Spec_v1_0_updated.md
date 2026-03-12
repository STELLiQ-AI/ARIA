# ARIA Android Project Scaffold Specification
## aria-atak-plugin — Complete Build Guide for Claude Code

**Document:**       ARIA_Android_Scaffold_Spec_v1.0.md
**Purpose:**        Authoritative Android project structure, module boundaries, CMake
                    configuration, JNI bridge interface contracts, and Gradle
                    dependency declarations for ARIA Phase I prototype.
                    Claude Code reads this document before generating any source file.
**Project:**        ARIA — Automated Review Intelligence Assistant
                    Army SBIR 24.4 | Topic A244-P037
**Author:**         [PI Name] — STELLiQ Technologies, LLC
**Version:**        1.0.0
**Last Modified:**  2026-02-25
**Classification:** UNCLASSIFIED // FOR OFFICIAL USE ONLY (FOUO)
**Doctrinal Ref:**  TC 7-0.1, *Training Management*, HQ DA, February 2025

> **CLAUDE CODE INSTRUCTION:** Read this file in full before generating any
> source file. Every class name, package path, method signature, and CMake
> target in this document is normative. Do not rename, restructure, or
> collapse modules without PI approval. Flag any deviation before proceeding.

---

## 1.0 Target Hardware Envelope

| Device | Role | SoC | GPU | RAM | Primary Inference |
|---|---|---|---|---|---|
| Samsung Galaxy S23 Tactical Edition (TE) | **Host ×1** | Snapdragon 8 Gen 2 | Adreno 740 (~3.8 TFLOPS FP16) | 8 GB | Whisper QNN HTP + Llama 3.1 7B GPU + WiFi Direct merge + ATAK COP push |
| Samsung Galaxy XCover6 Pro Tactical Edition (TE) | **Contributor ×2** | Snapdragon 778G | Adreno 642L (~1.4 TFLOPS FP16) | 6 GB | Whisper QNN HTP only + WiFi Direct TX |

**Hard RAM ceiling: 6 GB.** The Contributor device is the constraint.
Every memory allocation decision must be validated against 6 GB peak.
No single GGUF model load on Contributor. Llama 3.1 7B (~4.1 GB Q4_K_M)
loads only on Host (S23 TE, 8 GB).

**Performance targets — instrument every function on the critical path:**

| Stage | Target | Measurement Point |
|---|---|---|
| Whisper RTF | ≤ 0.5× | `whisper_inference_ms / audio_total_ms` |
| LLM TTFT | ≤ 2,000 ms | `llama_eval()` call → first output token |
| LLM decode throughput | ≥ 20 tokens/sec | `tokens_decoded / (decode_total_ms / 1000)` |
| Full pipeline (Host) | ≤ 10,000 ms | audio capture end → CoT push |
| Contributor ASR + TX | ≤ 2,000 ms | VAD gate close → WiFi Direct delivery ACK |

---

## 2.0 Android Project Structure

```
aria-atak-plugin/                          ← Repository root
│
├── CLAUDE.md                              ← Operating spec. Read first. Always.
├── ARIA_Android_Scaffold_Spec_v1.0.md     ← This file.
├── README.md
├── .gitignore                             ← *.gguf, *.bin, *.db, /build EXCLUDED
│
├── app/                                   ← Single Android application module
│   ├── build.gradle.kts                   ← App-level Gradle config
│   ├── proguard-rules.pro
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml        ← See §3.0 for required permissions
│       │   ├── java/
│       │   │   └── com/stelliq/aria/
│       │   │       │
│       │   │       ├── atak/              ── ATAK PLUGIN LAYER ──────────────
│       │   │       │   ├── ARIAPlugin.java
│       │   │       │   ├── ARIADropDownReceiver.java
│       │   │       │   ├── ARIALifecycleListener.java
│       │   │       │   └── CopPushManager.java
│       │   │       │
│       │   │       ├── asr/               ── ASR LAYER ──────────────────────
│       │   │       │   ├── WhisperEngine.java
│       │   │       │   ├── VadProcessor.java
│       │   │       │   └── AudioCaptureService.java
│       │   │       │
│       │   │       ├── nlp/               ── NLP / LLM LAYER (Host only) ─────
│       │   │       │   ├── LlamaEngine.java
│       │   │       │   ├── AARExtractor.java
│       │   │       │   └── RagIndex.java
│       │   │       │
│       │   │       ├── network/           ── WIFI DIRECT / P2P LAYER ─────────
│       │   │       │   ├── WiFiDirectManager.java
│       │   │       │   ├── SessionSyncService.java
│       │   │       │   └── SegmentPacket.java
│       │   │       │
│       │   │       ├── db/                ── ROOM PERSISTENCE LAYER ──────────
│       │   │       │   ├── AARDatabase.java
│       │   │       │   ├── dao/
│       │   │       │   │   ├── SessionDao.java
│       │   │       │   │   ├── ParticipantDao.java
│       │   │       │   │   ├── TranscriptDao.java
│       │   │       │   │   ├── SegmentDao.java
│       │   │       │   │   ├── MetricsDao.java
│       │   │       │   │   ├── ExportHistoryDao.java
│       │   │       │   │   ├── SyncLogDao.java
│       │   │       │   │   └── DoctrineDao.java
│       │   │       │   └── entity/
│       │   │       │       ├── AARSessionEntity.java
│       │   │       │       ├── ParticipantEntity.java
│       │   │       │       ├── MergedTranscriptEntity.java
│       │   │       │       ├── TranscriptSegmentEntity.java
│       │   │       │       ├── PipelineMetricsEntity.java
│       │   │       │       ├── ExportHistoryEntity.java
│       │   │       │       ├── SyncLogEntity.java
│       │   │       │       └── DoctrineChunkMeta.java
│       │   │       │
│       │   │       ├── model/             ── DOMAIN MODEL / POJOS ────────────
│       │   │       │   ├── AAROutput.java
│       │   │       │   ├── AARStep.java
│       │   │       │   ├── LessonLearned.java
│       │   │       │   ├── TranscriptSegment.java
│       │   │       │   ├── SessionRecord.java
│       │   │       │   └── Participant.java
│       │   │       │
│       │   │       ├── session/           ── SESSION STATE MACHINE ───────────
│       │   │       │   ├── SessionController.java
│       │   │       │   └── SessionRole.java
│       │   │       │
│       │   │       ├── benchmark/         ── D-01 BENCHMARK HARNESS ──────────
│       │   │       │   ├── PerfLogger.java
│       │   │       │   └── BenchmarkActivity.java
│       │   │       │
│       │   │       └── util/              ── SHARED UTILITIES ────────────────
│       │   │           ├── Constants.java
│       │   │           ├── AARValidator.java
│       │   │           └── JsonUtil.java
│       │   │
│       │   ├── cpp/                       ── JNI / NATIVE LAYER ──────────────
│       │   │   ├── CMakeLists.txt         ← See §5.0 for full CMake spec
│       │   │   ├── whisper_jni.cpp        ← WhisperEngine ↔ whisper.cpp bridge
│       │   │   ├── llama_jni.cpp          ← LlamaEngine ↔ llama.cpp bridge
│       │   │   └── aria_common.h          ← Shared structs, error codes, PERF macros
│       │   │
│       │   ├── assets/
│       │   │   ├── models/
│       │   │   │   └── .gitkeep           ← GGUF + QNN .bin files: NEVER COMMIT
│       │   │   └── doctrine/
│       │   │       └── .gitkeep           ← doctrine_rag.db: NEVER COMMIT
│       │   │
│       │   └── res/
│       │       ├── layout/
│       │       │   ├── aria_plugin_main.xml
│       │       │   └── aria_aar_display.xml
│       │       ├── values/
│       │       │   └── strings.xml
│       │       └── raw/
│       │           └── silero_vad.onnx    ← Silero VAD model (public, committable)
│       │
│       ├── test/                          ← JUnit unit tests (no device required)
│       │   └── java/com/stelliq/aria/
│       │       ├── AARExtractorTest.java
│       │       ├── SegmentMergeTest.java
│       │       ├── AARValidatorTest.java
│       │       └── JsonUtilTest.java
│       │
│       └── androidTest/                   ← Instrumented tests (ADB + device)
│           └── java/com/stelliq/aria/
│               ├── WhisperEngineTest.java
│               ├── LlamaEngineTest.java
│               ├── DatabaseTest.java
│               └── WiFiDirectTest.java
│
├── buildSrc/
│   └── libs.versions.toml                 ← Version catalog (see §4.0)
│
├── scripts/
│   ├── build_qnn_context_binaries.sh      ← Whisper encoder → .bin (x86 Docker)
│   ├── generate_synthetic_corpus.py       ← GPT-4o corpus factory (Sprint B-1)
│   ├── finetune_llama.py                  ← QLoRA training script
│   ├── convert_to_gguf.sh                 ← llama.cpp GGUF conversion
│   └── benchmark_report.py               ← D-01 WER/RTF table generator
│
├── docker/
│   └── qnn-build/
│       └── Dockerfile                     ← QNN SDK x86 toolchain container
│
└── docs/
    ├── ADR_LOG.md                         ← Architecture Decision Record log
    ├── SCHEMA_AAR_v1.json                 ← Canonical AAR JSON schema
    └── PERF_BASELINE.md                   ← Benchmark results log
```

---

## 3.0 AndroidManifest.xml — Required Permissions and Plugin Registration

```xml
<!-- AndroidManifest.xml — ARIA ATAK Plugin -->
<!-- All permissions required for Phase I operation. -->
<!-- No INTERNET permission — air-gapped by design. -->

<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />   <!-- Required by WifiP2pManager -->
<uses-permission android:name="android.permission.CHANGE_NETWORK_STATE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"
    android:maxSdkVersion="28" />

<!-- ATAK Plugin registration meta-data -->
<meta-data
    android:name="com.atakmap.app.COMPONENTS"
    android:value="com.stelliq.aria.atak.ARIAPlugin" />
```

**Note:** `android.permission.INTERNET` is explicitly omitted. Any code
change that adds it must be flagged to PI immediately — it violates the
air-gapped DDIL design constraint (CLAUDE.md §12.0 PROHIBITED PATTERNS).

---

## 4.0 Gradle Version Catalog (libs.versions.toml)

```toml
# buildSrc/libs.versions.toml
# All dependency versions locked here. Never hardcode versions in build.gradle.kts.

[versions]
# Android SDK
compile-sdk             = "34"
min-sdk                 = "29"          # Android 10 — ATAK minimum
target-sdk              = "34"

# ATAK SDK — match version on target EUD. Verify against installed ATAK build.
atak-sdk                = "5.2.0"

# AndroidX / Room
room                    = "2.6.1"
lifecycle               = "2.7.0"
core-ktx                = "1.13.1"
appcompat               = "1.7.0"
work-runtime            = "2.9.0"

# JSON
gson                    = "2.10.1"

# ONNX Runtime (Silero VAD — CPU-only, small binary)
onnxruntime-android     = "1.17.3"

# Testing
junit                   = "4.13.2"
androidx-test-ext       = "1.1.5"
espresso                = "3.5.1"

[libraries]
# ATAK SDK (AAR file — local file dependency, not Maven)
# Path: app/libs/atak-sdk-{version}.jar or .aar
# Declared as fileTree dependency in app/build.gradle.kts

# Room
room-runtime            = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
room-compiler           = { group = "androidx.room", name = "room-compiler", version.ref = "room" }
room-ktx                = { group = "androidx.room", name = "room-ktx", version.ref = "room" }

# Lifecycle
lifecycle-service       = { group = "androidx.lifecycle", name = "lifecycle-service", version.ref = "lifecycle" }

# Core
core-ktx                = { group = "androidx.core", name = "core-ktx", version.ref = "core-ktx" }
appcompat               = { group = "androidx.appcompat", name = "appcompat", version.ref = "appcompat" }

# ONNX Runtime (Silero VAD)
onnxruntime             = { group = "com.microsoft.onnxruntime", name = "onnxruntime-android", version.ref = "onnxruntime-android" }

# JSON
gson                    = { group = "com.google.code.gson", name = "gson", version.ref = "gson" }

# Testing
junit                   = { group = "junit", name = "junit", version.ref = "junit" }
androidx-test-ext       = { group = "androidx.test.ext", name = "junit", version.ref = "androidx-test-ext" }
espresso-core           = { group = "androidx.test.espresso", name = "espresso-core", version.ref = "espresso" }
```

### app/build.gradle.kts (abridged — critical sections)

```kotlin
android {
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.stelliq.aria"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0-alpha"

        // NDK ABI filter — Snapdragon only. arm64-v8a required for QNN HTP.
        // Do NOT add x86_64 — it bloats APK and is not a target device ABI.
        ndk {
            abiFilters += setOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-O2", "-DANDROID")
                arguments += listOf(
                    "-DANDROID_ABI=arm64-v8a",
                    "-DANDROID_PLATFORM=android-29",
                    "-DANDROID_STL=c++_shared"
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    // PROHIBITED: No network security config relaxations. Air-gapped.
    // PROHIBITED: Do not add cleartext traffic permissions.
}

dependencies {
    // ATAK SDK — local AAR. Version must match target device ATAK install.
    compileOnly(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))

    // Room
    implementation(libs.room.runtime)
    annotationProcessor(libs.room.compiler)  // Java — not KSP

    // Lifecycle (foreground service)
    implementation(libs.lifecycle.service)

    // ONNX Runtime — Silero VAD only. CPU inference. ~30 MB AAR.
    implementation(libs.onnxruntime)

    // JSON serialization
    implementation(libs.gson)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext)
    androidTestImplementation(libs.espresso.core)
}
```

---

## 5.0 CMakeLists.txt — Native Build Configuration

```cmake
# app/src/main/cpp/CMakeLists.txt
#
# Purpose:      CMake build configuration for ARIA native (C++) layer.
#               Builds two JNI shared libraries:
#                 1. libasr_jni.so  — whisper.cpp + QNN ASR bridge
#                 2. llm_jni.so     — llama.cpp + GPU LLM bridge
#               Links against QNN SDK stubs and Android NDK system libs.
#
# Build system: CMake 3.22.1 + Android NDK r26b+
# ABI target:   arm64-v8a only
#
# IMPORTANT: whisper.cpp and llama.cpp source trees are included as
# Git submodules under app/src/main/cpp/third_party/.
# Run: git submodule update --init --recursive before first build.

cmake_minimum_required(VERSION 3.22.1)
project(aria_native VERSION 1.0.0 LANGUAGES CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---------------------------------------------------------------------------
# Paths
# ---------------------------------------------------------------------------
set(THIRD_PARTY_DIR ${CMAKE_SOURCE_DIR}/third_party)
set(WHISPER_SRC     ${THIRD_PARTY_DIR}/whisper.cpp)
set(LLAMA_SRC       ${THIRD_PARTY_DIR}/llama.cpp)

# QNN SDK stubs — pre-built .so files from Qualcomm QNN SDK.
# Populated by build_qnn_context_binaries.sh into app/src/main/jniLibs/arm64-v8a/.
set(QNN_LIB_DIR     ${CMAKE_SOURCE_DIR}/../jniLibs/arm64-v8a)

# ---------------------------------------------------------------------------
# Android NDK system libraries
# ---------------------------------------------------------------------------
find_library(log-lib     log)
find_library(android-lib android)
find_library(OpenCL-lib  OpenCL)     # Adreno GPU OpenCL — for llama.cpp GPU
find_library(aaudio-lib  aaudio)     # Android Audio — for AudioCaptureService

# ---------------------------------------------------------------------------
# whisper.cpp — ASR inference library
# Configured for QNN HTP backend (Whisper encoder context binary only).
# Decoder runs CPU — encoder is the bottleneck; QNN HTP encoder halves RTF.
# ---------------------------------------------------------------------------
set(WHISPER_SOURCES
    ${WHISPER_SRC}/whisper.cpp
    ${WHISPER_SRC}/whisper.h
    # QNN backend integration source — from whisper.cpp examples/qnn/
    ${WHISPER_SRC}/examples/qnn/qnn-lib.cpp
)

add_library(whisper STATIC ${WHISPER_SOURCES})

target_include_directories(whisper PUBLIC
    ${WHISPER_SRC}
    ${WHISPER_SRC}/examples/qnn
)

target_compile_definitions(whisper PRIVATE
    WHISPER_USE_QNN          # Enable QNN HTP encoder path
    GGML_USE_QNN
    NDEBUG
)

# ---------------------------------------------------------------------------
# llama.cpp — LLM inference library
# GPU backend: OpenCL via GGML_OPENCL or Vulkan via GGML_VULKAN.
# ADR-7: NPU (QNN HTP) path attempted but exceeds address-space ceiling
# for 7B model. GPU (Adreno 740 OpenCL/Vulkan) is the locked backend.
# Host device only — Contributor devices do NOT link llama.cpp.
# ---------------------------------------------------------------------------
set(LLAMA_SOURCES
    ${LLAMA_SRC}/llama.cpp
    ${LLAMA_SRC}/llama.h
    ${LLAMA_SRC}/ggml.c
    ${LLAMA_SRC}/ggml-alloc.c
    ${LLAMA_SRC}/ggml-backend.c
    ${LLAMA_SRC}/ggml-opencl.cpp    # OpenCL GPU backend
)

add_library(llama STATIC ${LLAMA_SOURCES})

target_include_directories(llama PUBLIC
    ${LLAMA_SRC}
)

target_compile_definitions(llama PRIVATE
    GGML_USE_OPENCL          # GPU inference via Adreno OpenCL
    GGML_OPENCL_USE_ADRENO   # Adreno-specific optimizations
    NDEBUG
)

target_link_libraries(llama
    ${OpenCL-lib}
    ${log-lib}
)

# ---------------------------------------------------------------------------
# JNI Bridge: libasr_jni.so
# Exposes WhisperEngine Java class to whisper.cpp C++ inference.
# Deployed on BOTH Host and Contributor devices.
# ---------------------------------------------------------------------------
add_library(asr_jni SHARED
    ${CMAKE_SOURCE_DIR}/whisper_jni.cpp
)

target_include_directories(asr_jni PRIVATE
    ${WHISPER_SRC}
    ${CMAKE_SOURCE_DIR}
)

target_link_libraries(asr_jni
    whisper
    ${log-lib}
    ${android-lib}
    ${aaudio-lib}
)

# ---------------------------------------------------------------------------
# JNI Bridge: libllm_jni.so
# Exposes LlamaEngine Java class to llama.cpp C++ inference.
# Deployed on HOST device ONLY. Not included in Contributor APK variant.
# ---------------------------------------------------------------------------
add_library(llm_jni SHARED
    ${CMAKE_SOURCE_DIR}/llama_jni.cpp
)

target_include_directories(llm_jni PRIVATE
    ${LLAMA_SRC}
    ${CMAKE_SOURCE_DIR}
)

target_link_libraries(llm_jni
    llama
    ${OpenCL-lib}
    ${log-lib}
    ${android-lib}
)
```

---

## 6.0 JNI Bridge Interface Contracts

### 6.1 WhisperEngine.java — JNI Method Signatures

```java
// Package: com.stelliq.aria.asr
// Native library loaded: System.loadLibrary("asr_jni")
// Deployed on: Host AND Contributor devices

public class WhisperEngine {

    // Loads the whisper.cpp model from a file path.
    // modelPath: absolute path to .gguf file.
    //   Host (S23 TE):       ggml-small.en.q8_0.gguf  (244M params, small.en)
    //   Contributor (XCover6): ggml-tiny.en.q8_0.gguf  (39M params, tiny.en)
    // qnnContextPath: absolute path to QNN .bin context binary, or null to use CPU path.
    // Returns: 0 on success, negative error code on failure.
    // PERF: Model load is one-time at init. Not on critical inference path.
    public native int loadModel(String modelPath, String qnnContextPath);

    // Transcribes a 16kHz mono PCM float array.
    // audioData: float[] of 16kHz 32-bit PCM samples. Max 30 seconds (~480,000 samples).
    // Returns: Whisper transcript string, or null on failure.
    // PERF: Target RTF ≤ 0.5×. Instrument wall-clock here; log to PerfLogger.
    public native String transcribe(float[] audioData);

    // Returns last inference duration in milliseconds. Call after transcribe().
    public native long getLastInferenceMs();

    // Releases native resources. Call in onDestroy().
    public native void release();
}
```

### 6.2 LlamaEngine.java — JNI Method Signatures

```java
// Package: com.stelliq.aria.nlp
// Native library loaded: System.loadLibrary("llm_jni")
// Deployed on: Host device ONLY
// Model: Llama 3.1 7B Q4_K_M (~4.1 GB GGUF) — fits in Host 8 GB RAM only.
// CRITICAL: DO NOT attempt to load on Contributor (6 GB ceiling, would OOM).

public class LlamaEngine {

    // Loads the GGUF model. nGpuLayers: number of transformer layers offloaded to GPU.
    // Set nGpuLayers = 32 for full Adreno 740 offload (Llama 3.1 7B has 32 layers).
    // contextSize: KV cache size in tokens. 2048 sufficient for AAR extraction prompts.
    // Returns: 0 on success, negative error code on failure.
    // PERF: One-time load at service init. Not on critical inference path.
    public native int loadModel(String modelPath, int nGpuLayers, int contextSize);

    // Runs inference on the prompt string. Returns full completion string.
    // maxTokens: hard cap on decode length. 512 sufficient for TC 7-0.1 JSON output.
    // PERF: Target TTFT ≤ 2,000 ms. Target decode ≥ 20 tokens/sec.
    //       Log TTFT and decode_tps to PerfLogger after each call.
    public native String complete(String prompt, int maxTokens);

    // Returns TTFT in milliseconds from last complete() call.
    public native long getLastTtftMs();

    // Returns token decode throughput (tokens/sec) from last complete() call.
    public native float getLastDecodeTps();

    // Returns peak RAM used (native heap bytes) from last complete() call.
    public native long getLastPeakRamBytes();

    // Releases context and model. Call in onDestroy().
    public native void release();
}
```

### 6.3 aria_common.h — Shared Structs and PERF Macros

```cpp
// aria_common.h
// Shared definitions for whisper_jni.cpp and llama_jni.cpp.
// Include in all JNI bridge .cpp files.

#pragma once

#include <android/log.h>
#include <chrono>
#include <string>

// Log tag for all ARIA native logs — visible in logcat as "ARIA_NATIVE"
#define ARIA_TAG "ARIA_NATIVE"
#define ARIA_PERF_TAG "ARIA_PERF"

// Convenience log macros
#define ARIA_LOGI(...)  __android_log_print(ANDROID_LOG_INFO,  ARIA_TAG, __VA_ARGS__)
#define ARIA_LOGE(...)  __android_log_print(ANDROID_LOG_ERROR, ARIA_TAG, __VA_ARGS__)
#define ARIA_LOGD(...)  __android_log_print(ANDROID_LOG_DEBUG, ARIA_TAG, __VA_ARGS__)

// PERF logging macro — use on every critical path function.
// Emits: "ARIA_PERF [tag] duration_ms=XXX" to logcat for PerfLogger.java to parse.
#define ARIA_PERF_LOG(tag, duration_ms) \
    __android_log_print(ANDROID_LOG_INFO, ARIA_PERF_TAG, \
        "[%s] duration_ms=%lld", tag, (long long)(duration_ms))

// Monotonic wall-clock helper — use ARIA_CLOCK_NOW() to bracket timed sections.
// Returns nanoseconds as int64_t.
#define ARIA_CLOCK_NOW() \
    std::chrono::duration_cast<std::chrono::nanoseconds>( \
        std::chrono::steady_clock::now().time_since_epoch()).count()

#define ARIA_ELAPSED_MS(start_ns, end_ns) \
    (((end_ns) - (start_ns)) / 1000000LL)

// Error codes returned by native methods.
// Positive values = success. Negative = failure category.
enum AriaError : int {
    ARIA_OK                   =  0,
    ARIA_ERR_MODEL_LOAD       = -1,   // Failed to load GGUF or QNN binary
    ARIA_ERR_INFERENCE        = -2,   // Inference runtime error
    ARIA_ERR_OOM              = -3,   // Out of memory during model init
    ARIA_ERR_INVALID_INPUT    = -4,   // Null or malformed input
    ARIA_ERR_QNN_INIT         = -5,   // QNN HTP context binary init failure
    ARIA_ERR_GPU_BACKEND      = -6,   // OpenCL/Vulkan backend init failure
};
```

---

## 7.0 Room Entity — Field Mapping to aria_schema_v1.sql

Each Room `@Entity` class maps 1:1 to a DDL table in `aria_schema_v1.sql`.
The following table provides the mapping summary. Full entity implementations
must include `@ColumnInfo`, `@PrimaryKey`, `@ForeignKey`, and `@Index`
annotations matching the DDL exactly.

| Room Entity Class | DDL Table | Key Fields |
|---|---|---|
| `AARSessionEntity` | `aar_sessions` | `sessionUuid`, `sessionRole`, `processingState`, `aarJson`, `completenessScore` |
| `ParticipantEntity` | `session_participants` | `sessionUuid` (FK), `deviceId`, `role`, `speakerLabel` |
| `MergedTranscriptEntity` | `merged_transcripts` | `sessionUuid` (FK), `mergedTranscriptJson`, `fullText`, `speakerCount` |
| `TranscriptSegmentEntity` | `transcript_segments` | `sessionUuid` (FK), `deviceId`, `role`, `t0OffsetMs`, `segmentText`, `confidence`, `whisperRtf` |
| `PipelineMetricsEntity` | `pipeline_metrics` | `sessionUuid` (FK), `whisperRtf`, `llmTtftMs`, `llmDecodeTps`, `pipelineTotalMs`, `peakRamBytes`, `llmBackend`, `whisperBackend` |
| `ExportHistoryEntity` | `export_history` | `sessionUuid` (FK), `exportType`, `exportPath`, `exportResult` |
| `SyncLogEntity` | `sync_log` | `sessionUuid` (FK), `segmentId` (FK), `latencyMs`, `deliveryStatus` |
| `DoctrineChunkMeta` | `doctrine_chunks_meta` | `ftsRowid`, `sourceDoc`, `sectionRef`, `relevanceWeight` |

### AARDatabase.java — Room @Database declaration

```java
// AARDatabase.java — Room database singleton

@Database(
    entities = {
        AARSessionEntity.class,
        ParticipantEntity.class,
        MergedTranscriptEntity.class,
        TranscriptSegmentEntity.class,
        PipelineMetricsEntity.class,
        ExportHistoryEntity.class,
        SyncLogEntity.class,
        DoctrineChunkMeta.class
    },
    version = 1,               // Matches schema_version table in DDL.
    exportSchema = true        // Export schema JSON to /schemas/ for migration auditing.
)
@TypeConverters({JsonUtil.class})   // Gson-based converter for JSON blob fields.
public abstract class AARDatabase extends RoomDatabase {

    // DAO accessor methods — one per entity group.
    public abstract SessionDao       sessionDao();
    public abstract ParticipantDao   participantDao();
    public abstract TranscriptDao    transcriptDao();
    public abstract SegmentDao       segmentDao();
    public abstract MetricsDao       metricsDao();
    public abstract ExportHistoryDao exportHistoryDao();
    public abstract SyncLogDao       syncLogDao();
    public abstract DoctrineDao      doctrineDao();

    // Singleton instance with WAL mode and foreign key enforcement.
    // Instantiated once in ARIAPlugin.java via Application context.
    private static volatile AARDatabase INSTANCE;

    public static AARDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (AARDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                            context.getApplicationContext(),
                            AARDatabase.class,
                            "aria_v1.db"
                        )
                        .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                        .addCallback(new RoomDatabase.Callback() {
                            @Override
                            public void onOpen(SupportSQLiteDatabase db) {
                                super.onOpen(db);
                                // Enforce FK constraints at runtime — Room does not
                                // enable this by default.
                                db.execSQL("PRAGMA foreign_keys = ON");
                            }
                        })
                        .build();
                }
            }
        }
        return INSTANCE;
    }
}
```

---

## 8.0 SessionController — State Machine Definition

`SessionController.java` is the central orchestrator. It owns the
session lifecycle and coordinates all subsystems.

### State Enum

```
IDLE         → Session not started. All subsystems dormant.
INITIALIZING → WiFiDirectManager forming P2P group. Participants registering.
RECORDING    → AudioCaptureService active. VAD gating segments to WhisperEngine.
TRANSCRIBING → WhisperEngine processing buffered audio. Segments emitting.
MERGING      → (Host only) SessionSyncService collecting Contributor segments.
                Waiting for all registered Contributor segment streams to close.
EXTRACTING   → (Host only) LlamaEngine.complete() running on merged transcript.
COMPLETE     → AAR JSON extracted. CopPushManager has pushed to ATAK COP.
               SQLite records persisted.
ERROR        → Unrecoverable failure. Error logged to aar_sessions.error_message.
               UI notified via EventBus.
```

### Transition Guards

| From | To | Guard Condition |
|---|---|---|
| IDLE | INITIALIZING | `WiFiDirectManager.isGroupFormed()` OR role == CONTRIBUTOR |
| INITIALIZING | RECORDING | All expected participants registered in `session_participants` |
| RECORDING | TRANSCRIBING | Session stop command received OR max duration exceeded (default 15 min) |
| TRANSCRIBING | MERGING | All local segments flushed to `transcript_segments` |
| MERGING | EXTRACTING | (Host) All Contributor segment streams received; `merged_transcripts` row written |
| EXTRACTING | COMPLETE | `LlamaEngine.complete()` returned; `aar_sessions.aar_json` written |
| ANY | ERROR | Any unhandled exception in any subsystem |

---

## 9.0 PerfLogger.java — D-01 Benchmark Instrumentation

`PerfLogger.java` is the single authority for all PERF metric collection.
Every class with a `// PERF:` annotation must route timing data through
this class. It writes to both logcat (`ARIA_PERF` tag) and the
`pipeline_metrics` Room table.

```java
// PerfLogger usage pattern — call from WhisperEngine, LlamaEngine, SessionController.

// PERF: Log Whisper RTF after each transcribe() call.
PerfLogger.logWhisperRtf(sessionUuid, deviceId, inferenceMs, audioDurationMs);

// PERF: Log LLM timing after each complete() call. Host only.
PerfLogger.logLlmMetrics(sessionUuid, deviceId, ttftMs, decodeTps, peakRamBytes, backend);

// PERF: Log full pipeline wall-clock on COMPLETE transition. Host only.
PerfLogger.logPipelineTotal(sessionUuid, deviceId, pipelineTotalMs);
```

Logcat format (parseable by `benchmark_report.py`):
```
ARIA_PERF [WHISPER_RTF] session=<uuid> device=<id> inference_ms=<n> audio_ms=<n> rtf=<f>
ARIA_PERF [LLM_TTFT]    session=<uuid> device=<id> ttft_ms=<n>
ARIA_PERF [LLM_TPS]     session=<uuid> device=<id> tps=<f> tokens=<n>
ARIA_PERF [PIPELINE]    session=<uuid> device=<id> total_ms=<n>
ARIA_PERF [PEAK_RAM]    session=<uuid> device=<id> bytes=<n>
```

---

## 10.0 Constants.java — All Magic Numbers

```java
// Package: com.stelliq.aria.util
// All hardcoded values must be defined here. No magic numbers in source files.

public final class Constants {

    // WiFi Direct P2P
    public static final int    P2P_SEGMENT_PORT        = 7443;
    public static final int    P2P_BEACON_PORT         = 7444;
    public static final long   P2P_CONNECTION_TIMEOUT_MS = 10_000L;
    public static final long   P2P_SEGMENT_TIMEOUT_MS  = 2_000L;   // Max TX latency target

    // Audio capture
    public static final int    AUDIO_SAMPLE_RATE_HZ    = 16_000;   // Whisper input: 16kHz
    public static final int    AUDIO_CHANNELS          = 1;         // Mono
    public static final int    AUDIO_ENCODING          = AudioFormat.ENCODING_PCM_FLOAT;
    public static final int    VAD_CHUNK_MS            = 30;         // Silero VAD chunk size
    public static final int    MAX_SESSION_DURATION_MS = 15 * 60 * 1000; // 15 min hard cap

    // Whisper — device-role split (ADR-4)
    // Host (S23 TE): small.en, 244M params, ~488 MB GGUF. Full V75 NPU delegation validated.
    // Contributor (XCover6 Pro TE): tiny.en, 39M params, ~75 MB GGUF. V73 validated.
    // small.en on V73 (Contributor) is NOT validated — deferred to Phase II.
    public static final String WHISPER_MODEL_HOST_FILENAME        = "ggml-small.en.q8_0.gguf";
    public static final String WHISPER_MODEL_CONTRIBUTOR_FILENAME = "ggml-tiny.en.q8_0.gguf";
    public static final String WHISPER_QNN_BIN_S23     = "whisper_encoder_s23te.bin";   // small.en, V75
    public static final String WHISPER_QNN_BIN_XCPRO   = "whisper_encoder_778g.bin";    // tiny.en, V73
    public static final float  WHISPER_RTF_TARGET      = 0.5f;      // PERF target — both models

    // Llama / LLM
    public static final String LLAMA_MODEL_FILENAME    = "aria_llama31_7b_q4km.gguf";
    public static final int    LLAMA_N_GPU_LAYERS      = 32;         // Full Adreno 740 offload
    public static final int    LLAMA_CONTEXT_SIZE      = 2048;       // KV cache tokens
    public static final int    LLAMA_MAX_TOKENS        = 512;        // AAR JSON output cap
    public static final long   LLAMA_TTFT_TARGET_MS    = 2_000L;    // PERF target
    public static final float  LLAMA_TPS_TARGET        = 20.0f;     // PERF target

    // RAM constraints
    public static final long   RAM_HARD_CEILING_BYTES  = 6L * 1024 * 1024 * 1024; // 6 GB
    public static final long   RAM_WARN_THRESHOLD_BYTES = 5_500_000_000L;           // Warn at 5.5 GB

    // Pipeline
    public static final long   PIPELINE_TOTAL_TARGET_MS      = 10_000L;  // Host end-to-end
    public static final long   CONTRIBUTOR_ASR_TX_TARGET_MS  =  2_000L;  // Contributor path

    // Database
    public static final String DB_NAME                 = "aria_v1.db";
    public static final int    DB_VERSION              = 1;

    // CoT schema
    public static final String COT_AAR_TYPE            = "b-r-f-h-c"; // AAR COP overlay type
    public static final String COT_DETAIL_NAMESPACE    = "aria";

    // AAR validation
    public static final float  COMPLETENESS_TARGET     = 0.625f; // 2.5/4.0 — D-05 threshold

    // Classification
    public static final String CLASSIFICATION_DEFAULT  = "UNCLASSIFIED";

    private Constants() {} // Utility class — no instantiation.
}
```

---

## 11.0 Prohibited Patterns — Scaffold Enforcement

The following are hard violations. Claude Code must flag and refuse to
generate these patterns. See CLAUDE.md §12.0 for full list.

```
PROHIBITED: android.permission.INTERNET in AndroidManifest.xml
PROHIBITED: import com.google.firebase.*
PROHIBITED: import retrofit2.*  (or any HTTP client library)
PROHIBITED: OkHttpClient, HttpURLConnection, or any external network call
PROHIBITED: System.loadLibrary("llm_jni") on Contributor device variant
PROHIBITED: Loading *.gguf models > 1 GB on Contributor device
PROHIBITED: Magic numbers in source — all values in Constants.java
PROHIBITED: Silent exception catch blocks — all must log with context
PROHIBITED: New @Entity fields added to Room without updating aria_schema_v1.sql
PROHIBITED: ndk.abiFilters including x86_64 or armeabi-v7a
```

---

## 12.0 First Sprint (A-1) Deliverables Checklist

Sprint A-1 target: **Week 1–2 gate — Benchmark harness operational, RTF data collection running.**

- [ ] `aria-atak-plugin/` Android project created with structure per §2.0
- [ ] `app/build.gradle.kts` configured per §4.0
- [ ] `CMakeLists.txt` created per §5.0; `cmake ..` succeeds with NDK r26b+
- [ ] whisper.cpp and llama.cpp added as Git submodules
- [ ] `whisper_jni.cpp` stub compiles and loads via `System.loadLibrary("asr_jni")`
- [ ] `WhisperEngine.java` stubs present with all JNI signatures from §6.1
- [ ] `AARDatabase.java` + all 8 `@Entity` classes created; Room compile-time verification passes
- [ ] `PerfLogger.java` emitting `ARIA_PERF` logcat lines
- [ ] `BenchmarkActivity.java` runs Whisper on 3 synthetic audio clips; RTF logged to `pipeline_metrics`
- [ ] `Constants.java` populated; zero magic numbers in any source file

---

*UNCLASSIFIED // FOR OFFICIAL USE ONLY (FOUO)*
*ARIA_Android_Scaffold_Spec_v1.0.md | STELLiQ Technologies, LLC*
*Army SBIR 24.4 | Topic A244-P037 | 2026-02-25*
