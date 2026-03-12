#!/usr/bin/env python3
"""
export_gguf.py — Export fine-tuned ARIA model to GGUF format for on-device inference.

Converts the merged float16 model to Q4_K_M quantized GGUF (~4.6GB for 8B).
This is the format consumed by llama.cpp on the Android device.

Usage:
    python scripts/export_gguf.py
    python scripts/export_gguf.py --input output/aria-aar-8b-merged --quant q4_k_m
    python scripts/export_gguf.py --input output/aria-aar-8b-merged --quant q8_0  # Higher quality

Output: output/aria-aar-8b-q4_k_m.gguf (~4.6GB)
"""
import argparse
import sys
from pathlib import Path


def check_dependencies():
    missing = []
    for pkg in ["torch", "unsloth"]:
        try:
            __import__(pkg)
        except ImportError:
            missing.append(pkg)
    if missing:
        print(f"ERROR: Missing packages: {', '.join(missing)}")
        print("Run: pip install -r requirements.txt")
        sys.exit(1)

check_dependencies()


# Quantization type descriptions (sizes for 8B model)
QUANT_INFO = {
    "q4_k_m": "4-bit quantization, medium quality. ~4.6GB for 8B model. Best balance for S24 Ultra.",
    "q4_k_s": "4-bit quantization, small quality. ~4.3GB. Slightly lower quality than q4_k_m.",
    "q5_k_m": "5-bit quantization, medium quality. ~5.3GB. Better quality, tighter on 12GB device.",
    "q8_0": "8-bit quantization. ~8.5GB. Near-original quality, may not fit on S24 Ultra.",
    "f16": "Float16 (no quantization). ~16GB. Full quality, does NOT fit on mobile device.",
}


def main():
    parser = argparse.ArgumentParser(description="Export ARIA model to GGUF")
    parser.add_argument("--input", default="output/aria-aar-8b-merged",
                        help="Path to merged model directory")
    parser.add_argument("--output-dir", default="output",
                        help="Output directory for GGUF file")
    parser.add_argument("--quant", default="q4_k_m",
                        choices=list(QUANT_INFO.keys()),
                        help="Quantization type (default: q4_k_m)")
    parser.add_argument("--model-name", default="aria-aar-8b",
                        help="Model name prefix for output file")
    args = parser.parse_args()

    input_path = Path(args.input)
    if not input_path.exists():
        print(f"ERROR: Merged model not found at {args.input}")
        print("Run 'python scripts/train.py' first to train and merge the model.")
        sys.exit(1)

    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    output_file = output_dir / f"{args.model_name}-{args.quant}.gguf"

    print(f"\n{'='*60}")
    print(f"ARIA GGUF Export")
    print(f"{'='*60}")
    print(f"Input: {input_path}")
    print(f"Output: {output_file}")
    print(f"Quantization: {args.quant}")
    print(f"  {QUANT_INFO[args.quant]}")
    print(f"{'='*60}\n")

    # Load model using Unsloth
    from unsloth import FastLanguageModel

    print("Loading merged model...")
    model, tokenizer = FastLanguageModel.from_pretrained(
        model_name=str(input_path),
        max_seq_length=4096,
        dtype=None,
        load_in_4bit=False,  # Load in full precision for export
    )

    # Export to GGUF using Unsloth's built-in converter
    print(f"\nExporting to GGUF ({args.quant})...")
    print("This may take a few minutes...\n")

    model.save_pretrained_gguf(
        str(output_dir),
        tokenizer,
        quantization_method=args.quant,
    )

    # Unsloth saves with a default name — rename to our convention
    # Find the generated GGUF file
    gguf_files = list(output_dir.glob("*.gguf"))
    if gguf_files:
        latest = max(gguf_files, key=lambda f: f.stat().st_mtime)
        if latest.name != output_file.name:
            latest.rename(output_file)
            print(f"Renamed {latest.name} -> {output_file.name}")

    if output_file.exists():
        size_mb = output_file.stat().st_size / (1024 * 1024)
        print(f"\n{'='*60}")
        print(f"EXPORT COMPLETE")
        print(f"  File: {output_file}")
        print(f"  Size: {size_mb:.1f} MB")
        print(f"{'='*60}")
        print(f"\nNext steps:")
        print(f"  1. Validate: python scripts/validate.py --model {output_file}")
        print(f"  2. Deploy to device:")
        print(f"     adb push {output_file} /sdcard/Android/data/com.stelliq.aria/files/models/")
    else:
        print(f"\nWARNING: Expected output file not found at {output_file}")
        print("Check the output directory for generated GGUF files:")
        for f in output_dir.glob("*.gguf"):
            print(f"  {f.name} ({f.stat().st_size / 1e6:.1f} MB)")


if __name__ == "__main__":
    main()
