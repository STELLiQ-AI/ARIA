#!/bin/bash
# ============================================================================
# build_llama_opencl.sh
#
# Build script for llama.cpp with OpenCL support targeting Adreno 830 GPU.
# Produces libllama.so, libggml.so, and related libraries for Android arm64-v8a.
#
# Prerequisites:
#   - Android NDK 26.x installed (via Android Studio SDK Manager or cmdline-tools)
#   - CMake 3.22+ installed
#   - Ninja build system (recommended) or Make
#   - Git
#
# Usage:
#   chmod +x build_llama_opencl.sh
#   ./build_llama_opencl.sh
#
# Output:
#   ./output/arm64-v8a/*.so  (copy these to app/src/main/jniLibs/arm64-v8a/)
#
# Target Device: Samsung Galaxy S24 Ultra (Snapdragon 8 Gen 3 / Adreno 750)
# Note: Script also works for Adreno 830 (Snapdragon 8 Elite)
#
# Author: STELLiQ Engineering
# Version: 1.0.0
# Since: ARIA Demo Build — 2026-03-02
# ============================================================================

set -e  # Exit on error

# ═══════════════════════════════════════════════════════════════════════════
# CONFIGURATION
# ═══════════════════════════════════════════════════════════════════════════

# Use the local llama.cpp source from aria-build to avoid ABI mismatches.
# The JNI bridge (llama_jni.cpp) and this build MUST use the same llama.cpp commit.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LLAMA_CPP_LOCAL="${SCRIPT_DIR}/../aria-build/app/src/main/cpp/external/llama.cpp"

# Android configuration
ANDROID_ABI="arm64-v8a"
ANDROID_PLATFORM="android-29"  # Android 10+

# Build directory
BUILD_DIR="$(pwd)/llama-opencl-build"
OUTPUT_DIR="$(pwd)/output/${ANDROID_ABI}"

# Where to auto-install the output .so files
JNILIBS_DIR="${SCRIPT_DIR}/../aria-build/app/src/main/jniLibs/${ANDROID_ABI}"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# ═══════════════════════════════════════════════════════════════════════════
# HELPER FUNCTIONS
# ═══════════════════════════════════════════════════════════════════════════

log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

check_command() {
    if ! command -v "$1" &> /dev/null; then
        log_error "$1 is not installed or not in PATH"
        return 1
    fi
    return 0
}

# ═══════════════════════════════════════════════════════════════════════════
# DETECT ANDROID NDK
# ═══════════════════════════════════════════════════════════════════════════

detect_ndk() {
    log_info "Detecting Android NDK..."

    # Common NDK locations
    local ndk_paths=(
        "$ANDROID_NDK"
        "$ANDROID_NDK_HOME"
        "$ANDROID_SDK_ROOT/ndk"
        "$ANDROID_HOME/ndk"
        "$HOME/Android/Sdk/ndk"
        "$HOME/Library/Android/sdk/ndk"
        "/usr/local/android-sdk/ndk"
        "C:/Users/$USER/AppData/Local/Android/Sdk/ndk"
    )

    for base_path in "${ndk_paths[@]}"; do
        if [[ -d "$base_path" ]]; then
            # Find the latest NDK version (prefer 26.x)
            if [[ -f "$base_path/build/cmake/android.toolchain.cmake" ]]; then
                NDK_PATH="$base_path"
                break
            fi
            # Check for versioned subdirectories
            for ndk_ver in "$base_path"/26.* "$base_path"/27.* "$base_path"/25.*; do
                if [[ -d "$ndk_ver" && -f "$ndk_ver/build/cmake/android.toolchain.cmake" ]]; then
                    NDK_PATH="$ndk_ver"
                    break 2
                fi
            done
        fi
    done

    if [[ -z "$NDK_PATH" ]]; then
        log_error "Android NDK not found!"
        log_info "Please install NDK via Android Studio SDK Manager or set ANDROID_NDK environment variable"
        log_info "Recommended: NDK version 26.3.11579264 or later"
        exit 1
    fi

    log_success "Found NDK at: $NDK_PATH"
    TOOLCHAIN_FILE="$NDK_PATH/build/cmake/android.toolchain.cmake"
}

# ═══════════════════════════════════════════════════════════════════════════
# MAIN BUILD PROCESS
# ═══════════════════════════════════════════════════════════════════════════

main() {
    echo ""
    echo "╔═══════════════════════════════════════════════════════════════════════╗"
    echo "║  llama.cpp OpenCL Build for Android (Adreno GPU)                      ║"
    echo "║  Target: arm64-v8a / Snapdragon 8 Gen 3 / Adreno 750-830              ║"
    echo "╚═══════════════════════════════════════════════════════════════════════╝"
    echo ""

    # Check prerequisites
    log_info "Checking prerequisites..."
    check_command git || exit 1
    check_command cmake || exit 1

    # Detect NDK
    detect_ndk

    # Create build directory
    log_info "Creating build directory: $BUILD_DIR"
    mkdir -p "$BUILD_DIR"
    cd "$BUILD_DIR"

    # ─────────────────────────────────────────────────────────────────────────
    # Step 1: Clone OpenCL Headers
    # ─────────────────────────────────────────────────────────────────────────
    log_info "Step 1/5: Cloning OpenCL Headers..."
    if [[ ! -d "OpenCL-Headers" ]]; then
        git clone https://github.com/KhronosGroup/OpenCL-Headers.git
    else
        log_info "OpenCL-Headers already exists, skipping..."
    fi

    # ─────────────────────────────────────────────────────────────────────────
    # Step 2: Clone and Build OpenCL ICD Loader
    # ─────────────────────────────────────────────────────────────────────────
    log_info "Step 2/5: Building OpenCL ICD Loader..."
    if [[ ! -d "OpenCL-ICD-Loader" ]]; then
        git clone https://github.com/KhronosGroup/OpenCL-ICD-Loader.git
    fi

    cd OpenCL-ICD-Loader
    mkdir -p build-android && cd build-android

    cmake .. -G Ninja \
        -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN_FILE" \
        -DANDROID_ABI="$ANDROID_ABI" \
        -DANDROID_PLATFORM="$ANDROID_PLATFORM" \
        -DOPENCL_ICD_LOADER_HEADERS_DIR="$BUILD_DIR/OpenCL-Headers" \
        -DCMAKE_INSTALL_PREFIX="$BUILD_DIR/opencl-install" \
        -DBUILD_TESTING=OFF

    cmake --build . --config Release -j$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)
    cmake --install .

    cd "$BUILD_DIR"
    log_success "OpenCL ICD Loader built successfully"

    # ─────────────────────────────────────────────────────────────────────────
    # Step 3: Use local llama.cpp source (matches JNI bridge exactly)
    # ─────────────────────────────────────────────────────────────────────────
    if [[ ! -d "$LLAMA_CPP_LOCAL" ]]; then
        log_error "Local llama.cpp not found at: $LLAMA_CPP_LOCAL"
        log_error "Expected: aria-build/app/src/main/cpp/external/llama.cpp"
        exit 1
    fi

    local llama_commit
    llama_commit=$(cd "$LLAMA_CPP_LOCAL" && git rev-parse --short HEAD 2>/dev/null || echo "unknown")
    log_info "Step 3/5: Using local llama.cpp (commit: $llama_commit)"

    # Copy source to build dir so we don't pollute the project tree
    if [[ ! -d "llama.cpp" ]]; then
        log_info "Copying local llama.cpp source to build directory..."
        cp -r "$LLAMA_CPP_LOCAL" llama.cpp
    else
        # Verify the existing copy matches our local source
        local existing_commit
        existing_commit=$(cd llama.cpp && git rev-parse --short HEAD 2>/dev/null || echo "unknown")
        if [[ "$existing_commit" != "$llama_commit" ]]; then
            log_warn "Existing llama.cpp ($existing_commit) doesn't match local ($llama_commit)"
            log_info "Removing old copy and re-copying..."
            rm -rf llama.cpp/build-android-opencl 2>/dev/null
            rm -rf llama.cpp 2>/dev/null || {
                log_warn "Cannot remove old llama.cpp, using rsync overwrite..."
                cp -rf "$LLAMA_CPP_LOCAL"/* llama.cpp/ 2>/dev/null
                cp -rf "$LLAMA_CPP_LOCAL"/.git llama.cpp/ 2>/dev/null
            }
            if [[ ! -d "llama.cpp" ]]; then
                cp -r "$LLAMA_CPP_LOCAL" llama.cpp
            fi
        else
            log_info "llama.cpp already at correct commit $existing_commit"
        fi
    fi

    cd llama.cpp
    log_success "Using local llama.cpp at commit $llama_commit"

    # ─────────────────────────────────────────────────────────────────────────
    # Step 4: Build llama.cpp with OpenCL + Adreno optimizations
    # ─────────────────────────────────────────────────────────────────────────
    log_info "Step 4/5: Building llama.cpp with OpenCL (Adreno optimizations)..."

    mkdir -p build-android-opencl && cd build-android-opencl

    # CMake configuration with all OpenCL and Adreno flags
    cmake .. -G Ninja \
        -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN_FILE" \
        -DANDROID_ABI="$ANDROID_ABI" \
        -DANDROID_PLATFORM="$ANDROID_PLATFORM" \
        -DANDROID_STL=c++_shared \
        -DCMAKE_BUILD_TYPE=Release \
        -DBUILD_SHARED_LIBS=ON \
        -DGGML_OPENCL=ON \
        -DGGML_OPENCL_EMBED_KERNELS=ON \
        -DGGML_OPENCL_USE_ADRENO_KERNELS=ON \
        -DOpenCL_INCLUDE_DIR="$BUILD_DIR/OpenCL-Headers" \
        -DOpenCL_LIBRARY="$BUILD_DIR/opencl-install/lib/libOpenCL.so" \
        -DLLAMA_BUILD_TESTS=OFF \
        -DLLAMA_BUILD_EXAMPLES=OFF \
        -DLLAMA_BUILD_SERVER=OFF

    # Build
    cmake --build . --config Release -j$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)

    log_success "llama.cpp built successfully with OpenCL!"

    # ─────────────────────────────────────────────────────────────────────────
    # Step 5: Copy output libraries
    # ─────────────────────────────────────────────────────────────────────────
    log_info "Step 5/5: Copying libraries to output directory..."

    mkdir -p "$OUTPUT_DIR"

    # Find and copy all .so files
    find . -name "*.so" -type f | while read -r lib; do
        lib_name=$(basename "$lib")
        log_info "  Copying $lib_name..."
        cp "$lib" "$OUTPUT_DIR/"
    done

    # Also copy libc++_shared.so from NDK (required for c++_shared STL)
    local libcpp_path="$NDK_PATH/toolchains/llvm/prebuilt/*/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so"
    for libcpp in $libcpp_path; do
        if [[ -f "$libcpp" ]]; then
            log_info "  Copying libc++_shared.so..."
            cp "$libcpp" "$OUTPUT_DIR/"
            break
        fi
    done

    cd "$BUILD_DIR"

    # ─────────────────────────────────────────────────────────────────────────
    # Summary
    # ─────────────────────────────────────────────────────────────────────────
    echo ""
    echo "╔═══════════════════════════════════════════════════════════════════════╗"
    echo "║  BUILD COMPLETE!                                                      ║"
    echo "╚═══════════════════════════════════════════════════════════════════════╝"
    echo ""
    log_success "Output libraries in: $OUTPUT_DIR"
    echo ""
    echo "Libraries built:"
    ls -la "$OUTPUT_DIR"/*.so 2>/dev/null || log_warn "No .so files found in output"
    echo ""
    # Auto-install to aria-build jniLibs
    log_info "Installing .so files to aria-build jniLibs..."
    mkdir -p "$JNILIBS_DIR"
    cp "$OUTPUT_DIR"/*.so "$JNILIBS_DIR/"
    log_success "Installed to: $JNILIBS_DIR"
    echo ""
    echo "Installed libraries:"
    ls -la "$JNILIBS_DIR"/*.so 2>/dev/null || log_warn "No .so files in jniLibs"
    echo ""
    echo "Next steps:"
    echo "  1. Build the ARIA app:  cd aria-build && ./gradlew assembleDebug"
    echo "  2. Install on device:   adb install -r app/build/outputs/apk/debug/app-debug.apk"
    echo ""
}

# Run main function
main "$@"
