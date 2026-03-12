#!/usr/bin/env python3
"""
export_whisper_encoder_onnx.py

Exports the Whisper small.en ENCODER to ONNX format for QNN HTP compilation.
This is the first step in creating the NPU context binary.

QD-4 Constraints (ALL five are mandatory):
  1. Export model.encoder ONLY — decoder has If/Loop ops that crash ONNX (F-1, F-2)
  2. encoder.eval() before export — disables dropout for deterministic graph
  3. Fixed input shape (1, 80, 3000) — no dynamic axes (HTP requires static shapes)
  4. opset_version=17 minimum — Whisper attention ops require it (F-3)
  5. do_constant_folding=True — improves HTP delegation rate

Usage:
  python export_whisper_encoder_onnx.py [--output whisper_encoder_small_en.onnx]

Output:
  whisper_encoder_small_en.onnx — encoder-only ONNX file ready for AI Hub compile

@author STELLiQ Engineering
@version 0.2.0-npu
@since ARIA NPU Build — 2026-03-11
"""

import argparse
import os
import sys

import torch
import numpy as np


def export_encoder(output_path: str):
    """Export Whisper small.en encoder to ONNX."""

    print("=" * 70)
    print("ARIA NPU — Whisper Encoder ONNX Export")
    print("=" * 70)

    # Step 1: Load model
    print("\n[1/4] Loading openai/whisper-small.en from HuggingFace...")
    from transformers import WhisperModel

    model = WhisperModel.from_pretrained("openai/whisper-small.en")
    encoder = model.encoder

    # QD-4 constraint 2: eval mode disables dropout
    encoder.eval()
    print(f"  Encoder loaded: {sum(p.numel() for p in encoder.parameters()):,} parameters")
    print(f"  n_audio_state (hidden dim): {model.config.d_model}")  # Should be 768 for small

    # Step 2: Create dummy input with fixed shape
    # QD-4 constraint 3: Fixed shape (1, 80, 3000) — batch=1, mels=80, frames=3000
    print("\n[2/4] Creating dummy input (1, 80, 3000)...")
    dummy_input = torch.randn(1, 80, 3000)
    print(f"  Input shape: {dummy_input.shape}")

    # Step 3: Trace to verify output shape
    print("\n[3/4] Tracing encoder to verify output...")
    with torch.no_grad():
        output = encoder(dummy_input)
        # WhisperEncoder returns BaseModelOutput with last_hidden_state
        enc_output = output.last_hidden_state
        print(f"  Output shape: {enc_output.shape}")
        # Expected: (1, 1500, 768) for small.en
        assert enc_output.shape == (1, 1500, 768), \
            f"Unexpected output shape: {enc_output.shape} (expected (1, 1500, 768))"
        print(f"  Verified: [1, 1500, 768] = [batch, enc_frames, n_audio_state]")

    # Step 4: Export to ONNX
    print(f"\n[4/4] Exporting to ONNX: {output_path}")
    print(f"  opset_version: 17 (QD-4 constraint 4)")
    print(f"  do_constant_folding: True (QD-4 constraint 5)")
    print(f"  dynamic_axes: None (QD-4 constraint 3 — fixed shape only)")

    torch.onnx.export(
        encoder,
        dummy_input,
        output_path,
        opset_version=17,                      # QD-4 constraint 4
        do_constant_folding=True,              # QD-4 constraint 5
        input_names=["mel"],
        output_names=["embeddings"],
        # QD-4 constraint 3: NO dynamic_axes — fixed shape only
    )

    # Verify output file
    file_size = os.path.getsize(output_path)
    print(f"\n{'=' * 70}")
    print(f"SUCCESS: Encoder exported to {output_path}")
    print(f"  File size: {file_size / 1024 / 1024:.1f} MB")
    print(f"  Input:  mel        — float32[1, 80, 3000]")
    print(f"  Output: embeddings — float32[1, 1500, 768]")
    print(f"\nNext step: Run compile_whisper_encoder_aihub.py to compile for S24 Ultra (V79)")
    print(f"{'=' * 70}")

    return output_path


def main():
    parser = argparse.ArgumentParser(
        description="Export Whisper small.en encoder to ONNX for QNN HTP compilation"
    )
    parser.add_argument(
        "--output", "-o",
        default="whisper_encoder_small_en.onnx",
        help="Output ONNX file path (default: whisper_encoder_small_en.onnx)"
    )
    args = parser.parse_args()

    export_encoder(args.output)


if __name__ == "__main__":
    main()
