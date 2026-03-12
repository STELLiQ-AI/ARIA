#!/bin/bash
# ============================================================================
# build_whisper.sh
#
# Build whisper.cpp for Android ARM64-v8a.
# Generates libwhisper.so and whisper.h for ARIA ASR.
#
# Requirements:
# - Android NDK (set ANDROID_NDK_HOME)
# - CMake 3.22+
# - Git
#
# Usage:
#   ./build_whisper.sh
#
# Output:
#   scripts/output/arm64-v8a/libwhisper.so
#   app/src/main/cpp/include/whisper.h
#
# Author: STELLiQ Engineering
# Version: 0.1.0
# ============================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_DIR="${SCRIPT_DIR}/whisper-build"
OUTPUT_DIR="${SCRIPT_DIR}/output/arm64-v8a"
INCLUDE_DIR="${SCRIPT_DIR}/../app/src/main/cpp/include"

# Whisper.cpp version - use stable release
WHISPER_VERSION="v1.7.2"

# Android settings
ANDROID_ABI="arm64-v8a"
ANDROID_PLATFORM="android-28"
ANDROID_STL="c++_shared"

echo "============================================"
echo "Building whisper.cpp for Android ARM64"
echo "============================================"

# Check for NDK
if [ -z "$ANDROID_NDK_HOME" ]; then
    if [ -d "$HOME/Android/Sdk/ndk" ]; then
        ANDROID_NDK_HOME=$(ls -d "$HOME/Android/Sdk/ndk"/* | sort -V | tail -1)
    elif [ -d "/c/Users/$USER/AppData/Local/Android/Sdk/ndk" ]; then
        ANDROID_NDK_HOME=$(ls -d "/c/Users/$USER/AppData/Local/Android/Sdk/ndk"/* | sort -V | tail -1)
    fi
fi

if [ -z "$ANDROID_NDK_HOME" ] || [ ! -d "$ANDROID_NDK_HOME" ]; then
    echo "ERROR: ANDROID_NDK_HOME not set or NDK not found"
    echo "Please set ANDROID_NDK_HOME to your NDK installation path"
    exit 1
fi

echo "Using NDK: $ANDROID_NDK_HOME"

# Create directories
mkdir -p "$BUILD_DIR"
mkdir -p "$OUTPUT_DIR"
mkdir -p "$INCLUDE_DIR"

# Clone or update whisper.cpp
cd "$BUILD_DIR"
if [ ! -d "whisper.cpp" ]; then
    echo "Cloning whisper.cpp..."
    git clone --depth 1 --branch "$WHISPER_VERSION" https://github.com/ggerganov/whisper.cpp.git
else
    echo "Updating whisper.cpp to $WHISPER_VERSION..."
    cd whisper.cpp
    git fetch --tags
    git checkout "$WHISPER_VERSION"
    cd ..
fi

# Create build directory
BUILD_OUT="$BUILD_DIR/build-android-arm64"
rm -rf "$BUILD_OUT"
mkdir -p "$BUILD_OUT"
cd "$BUILD_OUT"

echo "Configuring CMake..."
cmake ../whisper.cpp \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ANDROID_ABI" \
    -DANDROID_PLATFORM="$ANDROID_PLATFORM" \
    -DANDROID_STL="$ANDROID_STL" \
    -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_SHARED_LIBS=ON \
    -DWHISPER_BUILD_TESTS=OFF \
    -DWHISPER_BUILD_EXAMPLES=OFF \
    -DGGML_OPENMP=ON \
    -DGGML_NEON=ON

echo "Building..."
cmake --build . --config Release -j$(nproc 2>/dev/null || echo 4)

# Copy output
echo "Copying output files..."
cp -v src/libwhisper.so "$OUTPUT_DIR/"
cp -v ggml/src/libggml.so "$OUTPUT_DIR/" 2>/dev/null || true
cp -v ggml/src/libggml-base.so "$OUTPUT_DIR/" 2>/dev/null || true
cp -v ggml/src/libggml-cpu.so "$OUTPUT_DIR/" 2>/dev/null || true

# Copy header
echo "Copying whisper.h..."
cp -v ../whisper.cpp/include/whisper.h "$INCLUDE_DIR/"

echo ""
echo "============================================"
echo "Build complete!"
echo "============================================"
echo "Library: $OUTPUT_DIR/libwhisper.so"
echo "Header:  $INCLUDE_DIR/whisper.h"
echo ""
echo "Next steps:"
echo "1. Copy libwhisper.so to app/src/main/jniLibs/arm64-v8a/"
echo "2. Rebuild the app"
echo ""
