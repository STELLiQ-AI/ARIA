#!/usr/bin/env python3
"""
compile_whisper_encoder_aihub.py

Compiles the Whisper small.en encoder ONNX to a QNN context binary
targeting Samsung Galaxy S24 Ultra (Snapdragon 8 Gen 3, Hexagon V79).

QD-5: Device target string must be EXACT — verify with hub.get_devices().

Prerequisites:
  1. Run export_whisper_encoder_onnx.py first to create the ONNX file
  2. pip install qai_hub
  3. qai_hub configure (set API key from aihub.qualcomm.com)

Usage:
  python compile_whisper_encoder_aihub.py [--onnx whisper_encoder_small_en.onnx]
                                          [--output whisper_encoder_s24ultra.bin]
                                          [--list-devices]

Output:
  whisper_encoder_s24ultra.bin — QNN context binary for S24 Ultra V79 NPU
  Place in: app/src/main/assets/models/

@author STELLiQ Engineering
@version 0.2.0-npu
@since ARIA NPU Build — 2026-03-11
"""

import argparse
import os
import sys


def list_matching_devices(query: str = "Samsung Galaxy S24"):
    """List AI Hub devices matching query string."""
    import qai_hub as hub

    print(f"Searching for devices matching '{query}'...")
    devices = hub.get_devices(query)
    if not devices:
        print(f"  No devices found matching '{query}'")
        print("  Try: hub.get_devices('Samsung Galaxy')")
        return []

    print(f"  Found {len(devices)} matching device(s):")
    for d in devices:
        print(f"    - {d.name}")
    return devices


def compile_encoder(onnx_path: str, output_path: str, device_name: str):
    """Compile ONNX encoder to QNN context binary via AI Hub."""
    import qai_hub as hub

    print("=" * 70)
    print("ARIA NPU — Whisper Encoder QNN Context Binary Compilation")
    print("=" * 70)

    # Step 1: Verify ONNX file exists
    if not os.path.exists(onnx_path):
        print(f"\nERROR: ONNX file not found: {onnx_path}")
        print("Run export_whisper_encoder_onnx.py first.")
        sys.exit(1)

    onnx_size = os.path.getsize(onnx_path)
    print(f"\n[1/4] ONNX file: {onnx_path} ({onnx_size / 1024 / 1024:.1f} MB)")

    # Step 2: Resolve device
    # QD-5: Device string must be exact — verify with hub.get_devices()
    print(f"\n[2/4] Resolving device: {device_name}")
    try:
        device = hub.Device(device_name)
        print(f"  Device resolved: {device.name}")
    except Exception as e:
        print(f"  ERROR: Device '{device_name}' not found: {e}")
        print("\n  Available Samsung Galaxy S24 devices:")
        list_matching_devices("Samsung Galaxy S24")
        sys.exit(1)

    # Step 3: Submit compile job
    print(f"\n[3/4] Submitting compile job to AI Hub...")
    print(f"  Target runtime: QNN Context Binary")
    print(f"  Target device:  {device.name}")
    print(f"  Input spec:     mel = float32[1, 80, 3000]")
    print(f"  This may take several minutes...")

    try:
        compile_job = hub.submit_compile_job(
            model=onnx_path,
            device=device,
            options="--target_runtime qnn_context_binary",
            input_specs=dict(mel=(1, 80, 3000)),
        )

        print(f"  Job submitted: {compile_job.job_id}")
        print(f"  Status URL: {compile_job.url}")

        # Wait for completion
        print(f"\n  Waiting for compilation to complete...")
        status = compile_job.wait()
        print(f"  Job status: {status}")

    except Exception as e:
        print(f"\n  ERROR: Compile job failed: {e}")
        print("\n  Common causes:")
        print("    - API key not configured (run: qai_hub configure)")
        print("    - ONNX contains unsupported ops (check opset version)")
        print("    - Device not available for compilation")
        sys.exit(1)

    # Step 4: Download result
    print(f"\n[4/4] Downloading context binary to: {output_path}")
    try:
        compile_job.download_target_model(output_path)
        file_size = os.path.getsize(output_path)

        print(f"\n{'=' * 70}")
        print(f"SUCCESS: Context binary compiled for {device.name}")
        print(f"  Output:    {output_path}")
        print(f"  File size: {file_size / 1024 / 1024:.1f} MB")
        print(f"\nNext step: Copy to app/src/main/assets/models/whisper_encoder_s24ultra.bin")
        print(f"{'=' * 70}")

    except Exception as e:
        print(f"  ERROR: Failed to download result: {e}")
        sys.exit(1)


def main():
    parser = argparse.ArgumentParser(
        description="Compile Whisper small.en encoder to QNN context binary for S24 Ultra"
    )
    parser.add_argument(
        "--onnx", "-i",
        default="whisper_encoder_small_en.onnx",
        help="Input ONNX file (default: whisper_encoder_small_en.onnx)"
    )
    parser.add_argument(
        "--output", "-o",
        default="whisper_encoder_s24ultra.bin",
        help="Output context binary (default: whisper_encoder_s24ultra.bin)"
    )
    parser.add_argument(
        "--device", "-d",
        default="Samsung Galaxy S24 (Family)",
        help="AI Hub device target (default: 'Samsung Galaxy S24 (Family)')"
    )
    parser.add_argument(
        "--list-devices",
        action="store_true",
        help="List available Samsung S24 devices and exit"
    )
    args = parser.parse_args()

    if args.list_devices:
        list_matching_devices("Samsung Galaxy S24")
        return

    compile_encoder(args.onnx, args.output, args.device)


if __name__ == "__main__":
    main()
