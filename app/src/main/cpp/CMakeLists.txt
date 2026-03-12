# ============================================================================
# CMakeLists.txt
#
# Build configuration for ARIA native libraries (whisper.cpp and llama.cpp).
# Targets ARM64-v8a only for Samsung Galaxy S24 Ultra (Snapdragon 8 Gen 3).
#
# IMPORTANT:
# - whisper.cpp is compiled FROM SOURCE to guarantee header/struct alignment
# - llama.cpp uses pre-built OpenCL GPU libraries (Adreno 750)
#
# Author: STELLiQ Engineering
# Version: 0.2.0
# Since: ARIA Commercial Build — 2026-03-08
# ============================================================================

cmake_minimum_required(VERSION 3.22.1)

project("aria_native" LANGUAGES CXX C)

# WHY: Custom FindOpenCL.cmake provides OpenCL headers and stub library for
# Android NDK cross-compilation. The NDK doesn't include OpenCL, but Adreno
# provides it at runtime via /system/vendor/lib64/libOpenCL.so.
list(INSERT CMAKE_MODULE_PATH 0 "${CMAKE_SOURCE_DIR}/cmake")

# ═══════════════════════════════════════════════════════════════════════════
# BUILD CONFIGURATION
# ═══════════════════════════════════════════════════════════════════════════

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)

if(${ANDROID_ABI} STREQUAL "arm64-v8a")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv8-a+simd")
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -march=armv8-a+simd")
endif()

# WHY: whisper.cpp is 10-20x slower without optimization (scalar vs NEON vectorized).
# Apply -O3 to ALL configurations including Debug, since assembleDebug triggers
# CMake Debug mode which defaults to -O0. Performance-critical native DSP code
# must always be optimized regardless of build variant.
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -DNDEBUG")
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3 -DNDEBUG")

set(PREBUILT_DIR ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})

# ═══════════════════════════════════════════════════════════════════════════
# WHISPER.CPP — BUILT FROM SOURCE
# ═══════════════════════════════════════════════════════════════════════════
# WHY: The pre-built libwhisper.so had a struct layout mismatch with the
# header, causing whisper_full_params fields (detect_language, language) to
# be read at wrong offsets. Building from source guarantees alignment.

set(WHISPER_DIR ${CMAKE_SOURCE_DIR}/external/whisper.cpp)

# Disable whisper.cpp extras
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_SDL2 OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL OFF CACHE BOOL "" FORCE)
# WHY: Enable OpenCL GPU acceleration for Whisper ASR on Adreno 750.
# ggml-opencl is built as STATIC and hidden by asr_jni.version script,
# so it won't conflict with llama's prebuilt libggml-opencl.so.
# Adreno kernels enabled for optimized matmul on Qualcomm GPUs.
# Kernels are embedded into the binary (no .cl file deployment needed).
set(GGML_OPENCL ON CACHE BOOL "OpenCL GPU acceleration for Whisper" FORCE)
set(GGML_OPENCL_USE_ADRENO_KERNELS ON CACHE BOOL "Adreno-optimized kernels" FORCE)
set(GGML_OPENCL_EMBED_KERNELS ON CACHE BOOL "Embed .cl kernels in binary" FORCE)
set(GGML_OPENCL_TARGET_VERSION 200 CACHE STRING "OpenCL 2.0 target" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "Vulkan disabled" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)

# WHY: Python3 is required for OpenCL kernel embedding (embed_kernel.py).
# Set explicitly for Windows cross-compilation where find_package may fail.
set(Python3_EXECUTABLE "C:/Users/ssage/AppData/Local/Programs/Python/Python311/python.exe"
    CACHE FILEPATH "Python3 for kernel embedding" FORCE)

# WHY: Force ggml to build as STATIC libraries for whisper.
# The global BUILD_SHARED_LIBS=ON (from gradle) would make ggml produce
# shared libraries (libggml.so, libggml-cpu.so, libggml-base.so) with the
# same names as the prebuilt llama.cpp libraries in jniLibs/. At runtime,
# Android loads the prebuilt versions (which include OpenCL) instead of
# whisper's versions — causing symbol conflicts that disable GPU
# acceleration for LLM inference.
set(BUILD_SHARED_LIBS_SAVED ${BUILD_SHARED_LIBS})
set(BUILD_SHARED_LIBS OFF)

# Build ggml from whisper.cpp's bundled copy (for whisper only)
include(FetchContent)
FetchContent_Declare(ggml_whisper SOURCE_DIR ${WHISPER_DIR}/ggml)
FetchContent_MakeAvailable(ggml_whisper)

# Restore BUILD_SHARED_LIBS for subsequent targets
set(BUILD_SHARED_LIBS ${BUILD_SHARED_LIBS_SAVED})

# ── QNN SDK Configuration ──
# WHY: QNN SDK headers are needed by qnn_wrapper.cpp and whisper.cpp (via ARIA_USE_QNN).
# The SDK was downloaded to the project root. Only headers are used at compile time —
# the runtime libraries (libQnnHtp.so etc.) are loaded via dlopen, not linked directly.
set(QNN_SDK_DIR "${CMAKE_SOURCE_DIR}/../../../../2.44.0.260225")

# Verify QNN SDK headers exist
if(EXISTS "${QNN_SDK_DIR}/include/QNN/QnnInterface.h")
    message(STATUS "ARIA: QNN SDK found at ${QNN_SDK_DIR}")
    set(ARIA_QNN_ENABLED TRUE)
else()
    message(WARNING "ARIA: QNN SDK not found — building without NPU acceleration")
    set(ARIA_QNN_ENABLED FALSE)
endif()

# Build whisper as static library
add_library(whisper STATIC ${WHISPER_DIR}/src/whisper.cpp)

target_compile_definitions(whisper PUBLIC
    GGML_USE_CPU
    WHISPER_VERSION="1.7.0"
)

# QNN: Three mandatory compile definitions (QD-3). Missing ANY one → silent CPU fallback.
# - ARIA_USE_QNN: Enables QNN encoder path in whisper.cpp (#ifdef ARIA_USE_QNN)
# - WHISPER_USE_QNN: Enables QNN code paths in whisper's encoder layer
# - GGML_USE_QNN: Enables QNN backend in the ggml tensor library
if(ARIA_QNN_ENABLED)
    target_compile_definitions(whisper PUBLIC
        ARIA_USE_QNN
        WHISPER_USE_QNN
        GGML_USE_QNN
    )
    message(STATUS "ARIA: QNN definitions enabled (ARIA_USE_QNN, WHISPER_USE_QNN, GGML_USE_QNN)")
endif()

target_include_directories(whisper PUBLIC
    ${WHISPER_DIR}
    ${WHISPER_DIR}/src
    ${WHISPER_DIR}/include
    ${WHISPER_DIR}/ggml/include
    ${WHISPER_DIR}/ggml/src
    ${WHISPER_DIR}/ggml/src/ggml-cpu
    ${CMAKE_SOURCE_DIR}
)

# QNN: Add QNN SDK include path so qnn_wrapper.h can find QNN/*.h headers
if(ARIA_QNN_ENABLED)
    target_include_directories(whisper PUBLIC
        ${QNN_SDK_DIR}/include
    )
endif()

target_link_libraries(whisper PUBLIC ggml)

# ═══════════════════════════════════════════════════════════════════════════
# LLAMA.CPP — PRE-BUILT (OpenCL GPU acceleration)
# ═══════════════════════════════════════════════════════════════════════════

add_library(llama SHARED IMPORTED)
set_target_properties(llama PROPERTIES
    IMPORTED_LOCATION ${PREBUILT_DIR}/libllama.so
)

add_library(ggml_prebuilt SHARED IMPORTED)
set_target_properties(ggml_prebuilt PROPERTIES
    IMPORTED_LOCATION ${PREBUILT_DIR}/libggml.so
)

add_library(ggml-base_prebuilt SHARED IMPORTED)
set_target_properties(ggml-base_prebuilt PROPERTIES
    IMPORTED_LOCATION ${PREBUILT_DIR}/libggml-base.so
)

add_library(ggml-cpu_prebuilt SHARED IMPORTED)
set_target_properties(ggml-cpu_prebuilt PROPERTIES
    IMPORTED_LOCATION ${PREBUILT_DIR}/libggml-cpu.so
)

add_library(omp SHARED IMPORTED)
set_target_properties(omp PROPERTIES
    IMPORTED_LOCATION ${PREBUILT_DIR}/libomp.so
)

# OpenCL GPU acceleration for LLM (optional, prebuilt)
# WHY: Renamed from ggml-opencl to ggml-opencl-llm to avoid target name
# collision with whisper's source-built ggml-opencl (STATIC) backend.
if(EXISTS "${PREBUILT_DIR}/libggml-opencl.so")
    message(STATUS "ARIA: OpenCL libraries found - LLM GPU acceleration ENABLED")
    set(ARIA_OPENCL_ENABLED TRUE)

    add_library(ggml-opencl-llm SHARED IMPORTED)
    set_target_properties(ggml-opencl-llm PROPERTIES
        IMPORTED_LOCATION ${PREBUILT_DIR}/libggml-opencl.so
    )
else()
    message(STATUS "ARIA: OpenCL libraries not found - LLM CPU only")
    set(ARIA_OPENCL_ENABLED FALSE)
endif()

# ═══════════════════════════════════════════════════════════════════════════
# ONNX RUNTIME (for Silero VAD)
# ═══════════════════════════════════════════════════════════════════════════

add_library(onnxruntime SHARED IMPORTED)
set_target_properties(onnxruntime PROPERTIES
    IMPORTED_LOCATION ${PREBUILT_DIR}/libonnxruntime.so
)

# ═══════════════════════════════════════════════════════════════════════════
# ASR JNI LIBRARY (loaded as "asr_jni" by WhisperEngine)
# ═══════════════════════════════════════════════════════════════════════════

# QNN: qnn_wrapper.cpp is compiled as part of asr_jni (not whisper) because it's
# the bridge between our JNI layer and the QNN runtime via dlopen/dlsym.
if(ARIA_QNN_ENABLED)
    add_library(asr_jni SHARED
        whisper_jni.cpp
        qnn_wrapper.cpp
    )
else()
    add_library(asr_jni SHARED
        whisper_jni.cpp
    )
endif()

target_include_directories(asr_jni PRIVATE
    ${WHISPER_DIR}/include
    ${WHISPER_DIR}/ggml/include
)

# QNN: asr_jni needs QNN SDK headers for qnn_wrapper.cpp compilation
# WHY: Two include paths needed — ${QNN_SDK_DIR}/include for "QNN/..." includes,
# and ${QNN_SDK_DIR}/include/QNN for System/ sub-headers that do #include "QnnDevice.h"
# (relative include from System/ subdirectory needs parent QNN/ dir in search path).
if(ARIA_QNN_ENABLED)
    target_include_directories(asr_jni PRIVATE
        ${QNN_SDK_DIR}/include
        ${QNN_SDK_DIR}/include/QNN
    )
endif()

# WHY: Hide all ggml/whisper/opencl symbols from the global namespace using
# a version script. Only JNI entry points are exported. This prevents
# whisper's ggml symbols from shadowing llama.cpp's prebuilt ggml libraries.
# Both Whisper and LLM have their own OpenCL backend — Whisper's is STATIC
# (built from source, hidden), LLM's is SHARED (prebuilt in jniLibs/).
# The version script ensures zero cross-contamination.
set_target_properties(asr_jni PROPERTIES
    LINK_FLAGS "-Wl,--version-script=${CMAKE_SOURCE_DIR}/asr_jni.version"
)

# WHY: Link against OpenCL stub for symbol resolution only, using -L/-l flags
# instead of direct path linking. Direct path causes CMake/Gradle to PACKAGE
# the stub .so into the APK, which fails at runtime because the vendor's
# libOpenCL.so depends on system libc++.so not in the app namespace.
# Using -L/-l tells the linker where to find the library at build time,
# but the .so is NOT packaged. At runtime, Android's dynamic linker resolves
# libOpenCL.so from the Adreno vendor path (/system/vendor/lib64/).
target_link_libraries(asr_jni
    whisper
    android
    log
)
target_link_options(asr_jni PRIVATE
    -L${CMAKE_SOURCE_DIR}/opencl-stub
    -lOpenCL
)

# ═══════════════════════════════════════════════════════════════════════════
# LLM JNI LIBRARY (loaded as "llm_jni" by LlamaEngine)
# ═══════════════════════════════════════════════════════════════════════════

add_library(llm_jni SHARED
    llama_jni.cpp
)

target_include_directories(llm_jni PRIVATE
    ${CMAKE_SOURCE_DIR}/include
)

if(ARIA_OPENCL_ENABLED)
    target_link_libraries(llm_jni
        llama
        ggml_prebuilt
        ggml-base_prebuilt
        ggml-cpu_prebuilt
        ggml-opencl-llm
        omp
        android
        log
    )
    target_compile_definitions(llm_jni PRIVATE ARIA_OPENCL_ENABLED=1)
else()
    target_link_libraries(llm_jni
        llama
        ggml_prebuilt
        ggml-base_prebuilt
        ggml-cpu_prebuilt
        omp
        android
        log
    )
    target_compile_definitions(llm_jni PRIVATE ARIA_OPENCL_ENABLED=0)
endif()
