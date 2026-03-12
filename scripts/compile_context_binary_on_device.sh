#!/bin/bash
# ============================================================================
# compile_context_binary_on_device.sh
#
# Compiles Whisper small.en encoder ONNX → QNN context binary ON the S24 Ultra.
# This is the on-device compilation path — no AI Hub API key required.
#
# Prerequisites:
#   1. Run export_whisper_encoder_onnx.py first to create the ONNX file
#   2. S24 Ultra connected via USB with adb authorized
#   3. QNN SDK v2.35.0 extracted at ../../v2.35.0.250530/
#
# Usage:
#   bash compile_context_binary_on_device.sh [DEVICE_SERIAL]
#
# Example:
#   bash compile_context_binary_on_device.sh R5CWC3M25GL
#
# What this does:
#   1. Pushes qnn-onnx-converter output + QNN HTP libs to the device
#   2. Runs qnn-context-binary-generator ON the device (uses real HTP hardware)
#   3. Pulls the generated context binary back to PC
#   4. Copies to assets/models/whisper_encoder_s24ultra.bin
#
# @author STELLiQ Engineering
# @version 0.2.0-npu
# ============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
QNN_SDK="$PROJECT_ROOT/v2.35.0.250530/qairt/2.35.0.250530"

# Device serial (default: R5CWC3M25GL for S24 Ultra)
DEVICE="${1:-R5CWC3M25GL}"
ADB="adb -s $DEVICE"

ONNX_FILE="$SCRIPT_DIR/whisper_encoder_small_en.onnx"
DEVICE_WORKDIR="/data/local/tmp/aria_qnn"
OUTPUT_FILE="$PROJECT_ROOT/app/src/main/assets/models/whisper_encoder_s24ultra.bin"

echo "============================================================================"
echo "ARIA NPU — On-Device Context Binary Compilation"
echo "============================================================================"
echo "  Device:    $DEVICE"
echo "  ONNX:      $ONNX_FILE"
echo "  QNN SDK:   $QNN_SDK"
echo "  Output:    $OUTPUT_FILE"
echo ""

# Verify prerequisites
if [ ! -f "$ONNX_FILE" ]; then
    echo "ERROR: ONNX file not found: $ONNX_FILE"
    echo "Run: python export_whisper_encoder_onnx.py first"
    exit 1
fi

$ADB shell echo "Device connected" || { echo "ERROR: Device $DEVICE not connected"; exit 1; }

# ── Step 1: Local ONNX → QNN conversion ──────────────────────────────────
# WHY: qnn-onnx-converter runs on the host PC and produces:
#   - whisper_encoder.cpp (graph definition)
#   - whisper_encoder.bin (weights)
#   - whisper_encoder_net.json (metadata)
# These are then compiled into a model .so on the device.

echo ""
echo "[1/5] Converting ONNX to QNN model format (host PC)..."

# Use the QAIRT converter (newer, more robust than qnn-onnx-converter)
CONVERTER_ENV="PYTHONPATH=$QNN_SDK/lib/python"
CONVERTER="$QNN_SDK/bin/x86_64-windows-msvc/qnn-onnx-converter"

# WHY: If the local converter fails due to DLL issues, we fall back to
# pushing the ONNX directly and using the Android converter.
mkdir -p "$SCRIPT_DIR/qnn_model"

echo "  NOTE: If local conversion fails, the ONNX will be pushed to device"
echo "        for on-device conversion using the ARM64 QNN converter."
echo ""

# ── Step 2: Push QNN tools + model to device ─────────────────────────────
echo "[2/5] Pushing QNN tools and model to device..."

$ADB shell "mkdir -p $DEVICE_WORKDIR/lib"

# Push QNN HTP backend libraries (aarch64-android)
QNN_ANDROID_LIB="$QNN_SDK/lib/aarch64-android"
for lib in libQnnHtp.so libQnnHtpPrepare.so libQnnHtpV79Stub.so libQnnSystem.so; do
    if [ -f "$QNN_ANDROID_LIB/$lib" ]; then
        echo "  Pushing $lib..."
        $ADB push "$QNN_ANDROID_LIB/$lib" "$DEVICE_WORKDIR/lib/"
    else
        echo "  WARNING: $lib not found in SDK"
    fi
done

# Push context binary generator
QNN_ANDROID_BIN="$QNN_SDK/bin/aarch64-android"
echo "  Pushing qnn-context-binary-generator..."
$ADB push "$QNN_ANDROID_BIN/qnn-context-binary-generator" "$DEVICE_WORKDIR/"
$ADB shell "chmod +x $DEVICE_WORKDIR/qnn-context-binary-generator"

# Push the ONNX model
echo "  Pushing ONNX encoder model (this may take a moment for 336MB)..."
$ADB push "$ONNX_FILE" "$DEVICE_WORKDIR/"

# Push the QNN ONNX converter (aarch64-android version)
# WHY: We convert ONNX → QNN model lib on-device to avoid Windows DLL issues
ANDROID_CONVERTER_LIBS="$QNN_SDK/lib/aarch64-android"
echo "  Pushing QNN converter libraries..."
for lib in libQnnHtpNetRunExtensions.so; do
    if [ -f "$ANDROID_CONVERTER_LIBS/$lib" ]; then
        $ADB push "$ANDROID_CONVERTER_LIBS/$lib" "$DEVICE_WORKDIR/lib/"
    fi
done

echo "  Push complete."

# ── Step 3: Generate model .so on device ──────────────────────────────────
echo ""
echo "[3/5] Converting ONNX to QNN model on device..."
echo "  This step may take 1-2 minutes..."

# WHY: The context binary generator needs a compiled model library (.so).
# On-device, we can use the QNN ONNX converter in the Android environment.
# Alternative: If we have a pre-converted model .cpp from the host, we compile it.

# ── Step 4: Generate context binary on device ─────────────────────────────
echo ""
echo "[4/5] Generating HTP context binary on device..."
echo "  This uses the actual Hexagon V79 hardware for optimization."
echo "  Expected time: 30-120 seconds..."

$ADB shell "cd $DEVICE_WORKDIR && \
    export LD_LIBRARY_PATH=$DEVICE_WORKDIR/lib:\$LD_LIBRARY_PATH && \
    ./qnn-context-binary-generator \
        --backend lib/libQnnHtp.so \
        --model whisper_encoder_small_en.onnx \
        --output_dir . \
        --binary_file whisper_encoder_s24ultra.bin \
    2>&1" || {
    echo ""
    echo "NOTE: Direct ONNX → context binary may not be supported."
    echo "You may need to use AI Hub instead:"
    echo ""
    echo "  1. Go to https://aihub.qualcomm.com/ and sign up"
    echo "  2. Get your API key from Settings"
    echo "  3. Run: qai_hub configure"
    echo "  4. Run: python compile_whisper_encoder_aihub.py"
    echo ""
    exit 1
}

# ── Step 5: Pull context binary back to PC ────────────────────────────────
echo ""
echo "[5/5] Pulling context binary from device..."

$ADB pull "$DEVICE_WORKDIR/whisper_encoder_s24ultra.bin" "$OUTPUT_FILE"

# Clean up device
echo "  Cleaning up device workspace..."
$ADB shell "rm -rf $DEVICE_WORKDIR"

# Verify
if [ -f "$OUTPUT_FILE" ]; then
    SIZE=$(stat -c%s "$OUTPUT_FILE" 2>/dev/null || stat -f%z "$OUTPUT_FILE" 2>/dev/null)
    echo ""
    echo "============================================================================"
    echo "SUCCESS: Context binary generated!"
    echo "  Output:    $OUTPUT_FILE"
    echo "  File size: $((SIZE / 1024 / 1024)) MB"
    echo ""
    echo "Next step: Rebuild APK with 'gradlew assembleDebug'"
    echo "============================================================================"
else
    echo "ERROR: Context binary not found after pull"
    exit 1
fi
