#!/usr/bin/env python3
"""
train.py — Fine-tune Llama 3.1 8B Instruct for ARIA meeting summarization.

Uses QLoRA (4-bit quantized LoRA) via Unsloth for 2x faster training
with 70% less VRAM. Requires a cloud GPU (A100 40GB recommended).
Local RTX 3070 Ti (8GB) is too small for 8B QLoRA (~9-12GB peak VRAM).

Usage:
    python scripts/train.py --data data/synthetic_8b.jsonl --epochs 3
    python scripts/train.py --data data/synthetic_8b.jsonl --epochs 5 --lr 1e-4

Cloud GPU options (RunPod / Lambda / Colab Pro):
    A100 40GB:  Recommended. ~$1.10/hr, full run ~$6-8.
    A10 24GB:   Tight but works with batch_size=1, grad_accum=8.
    L4 24GB:    Works with reduced settings.

VRAM budget (~12GB peak on A100):
    Model (4-bit):     ~4.5GB
    LoRA adapters:     ~100MB
    Optimizer states:  ~400MB
    Activations:       ~4-7GB (with gradient checkpointing)
    ─────────────────────────
    Total:             ~9-12GB
"""
import argparse
import json
import sys
from pathlib import Path

def check_dependencies():
    """Verify all required packages are installed."""
    missing = []
    for pkg in ["torch", "unsloth", "transformers", "datasets", "trl", "peft"]:
        try:
            __import__(pkg)
        except ImportError:
            missing.append(pkg)
    if missing:
        print(f"ERROR: Missing packages: {', '.join(missing)}")
        print("Run: pip install -r requirements.txt")
        sys.exit(1)

check_dependencies()

import torch
from datasets import load_dataset
from unsloth import FastLanguageModel
from trl import SFTTrainer
from transformers import TrainingArguments
from unsloth.chat_templates import get_chat_template


def main():
    parser = argparse.ArgumentParser(description="Fine-tune Llama 3.1 8B for ARIA")
    parser.add_argument("--data", required=True, help="Training data JSONL file")
    parser.add_argument("--epochs", type=int, default=3, help="Number of training epochs")
    parser.add_argument("--lr", type=float, default=2e-4, help="Learning rate")
    parser.add_argument("--batch-size", type=int, default=2, help="Per-device batch size")
    parser.add_argument("--grad-accum", type=int, default=4, help="Gradient accumulation steps")
    parser.add_argument("--lora-r", type=int, default=16, help="LoRA rank")
    parser.add_argument("--lora-alpha", type=int, default=16, help="LoRA alpha")
    parser.add_argument("--max-seq-len", type=int, default=4096, help="Max sequence length")
    parser.add_argument("--output-dir", default="output/aria-aar-8b-merged",
                        help="Output directory for merged model")
    parser.add_argument("--save-adapter-only", default="output/aria-aar-8b-lora",
                        help="Directory to save LoRA adapter weights")
    args = parser.parse_args()

    # Validate data file
    data_path = Path(args.data)
    if not data_path.exists():
        print(f"ERROR: Data file not found: {args.data}")
        sys.exit(1)

    # Count examples
    with open(data_path, "r", encoding="utf-8") as f:
        num_examples = sum(1 for _ in f)
    print(f"\nTraining data: {args.data} ({num_examples} examples)")

    # ─── Step 1: Load model in 4-bit ─────────────────────────────────
    print("\n" + "="*60)
    print("Step 1: Loading Llama 3.1 8B Instruct (4-bit quantized)")
    print("="*60)

    # WHY: 8B model produces dramatically better AAR extraction quality
    # across all 6 fields, especially the new ai_perspective field.
    # Requires cloud GPU (A100 40GB recommended) — RTX 3070 Ti (8GB) is too small.
    model, tokenizer = FastLanguageModel.from_pretrained(
        model_name="unsloth/Meta-Llama-3.1-8B-Instruct",
        max_seq_length=args.max_seq_len,
        dtype=None,  # Auto-detect (bfloat16 on A100, float16 on others)
        load_in_4bit=True,
    )

    print(f"Model loaded. VRAM: {torch.cuda.memory_allocated() / 1e9:.2f} GB")

    # ─── Step 2: Apply LoRA adapters ─────────────────────────────────
    print("\n" + "="*60)
    print("Step 2: Applying LoRA adapters")
    print("="*60)

    model = FastLanguageModel.get_peft_model(
        model,
        r=args.lora_r,
        target_modules=[
            "q_proj", "k_proj", "v_proj", "o_proj",
            "gate_proj", "up_proj", "down_proj",
        ],
        lora_alpha=args.lora_alpha,
        lora_dropout=0,  # Unsloth optimized — 0 dropout is faster
        bias="none",
        use_gradient_checkpointing="unsloth",  # 30% less VRAM
        random_state=42,
    )

    trainable = sum(p.numel() for p in model.parameters() if p.requires_grad)
    total = sum(p.numel() for p in model.parameters())
    print(f"Trainable params: {trainable:,} / {total:,} ({100*trainable/total:.2f}%)")
    print(f"VRAM after LoRA: {torch.cuda.memory_allocated() / 1e9:.2f} GB")

    # ─── Step 3: Setup chat template ─────────────────────────────────
    print("\n" + "="*60)
    print("Step 3: Setting up Llama 3.1 Instruct chat template")
    print("="*60)

    tokenizer = get_chat_template(
        tokenizer,
        chat_template="llama-3.1",
    )

    # ─── Step 4: Load and format training data ───────────────────────
    print("\n" + "="*60)
    print("Step 4: Loading training data")
    print("="*60)

    dataset = load_dataset("json", data_files=args.data, split="train")

    def format_chat(example):
        """Apply Llama 3.1 Instruct chat template to messages."""
        text = tokenizer.apply_chat_template(
            example["messages"],
            tokenize=False,
            add_generation_prompt=False,
        )
        return {"text": text}

    dataset = dataset.map(format_chat, batched=False)
    print(f"Dataset: {len(dataset)} examples")
    print(f"Sample (first 200 chars): {dataset[0]['text'][:200]}...")

    # ─── Step 5: Training ────────────────────────────────────────────
    print("\n" + "="*60)
    print(f"Step 5: Training for {args.epochs} epochs")
    print(f"  Batch size: {args.batch_size} x {args.grad_accum} grad accum = "
          f"{args.batch_size * args.grad_accum} effective")
    print(f"  Learning rate: {args.lr}")
    print(f"  Steps: ~{num_examples * args.epochs // (args.batch_size * args.grad_accum)}")
    print("="*60)

    trainer = SFTTrainer(
        model=model,
        tokenizer=tokenizer,
        train_dataset=dataset,
        dataset_text_field="text",
        max_seq_length=args.max_seq_len,
        dataset_num_proc=2,
        packing=False,  # Disable packing for chat-format data
        args=TrainingArguments(
            output_dir="output/checkpoints",
            per_device_train_batch_size=args.batch_size,
            gradient_accumulation_steps=args.grad_accum,
            warmup_steps=5,
            num_train_epochs=args.epochs,
            learning_rate=args.lr,
            fp16=not torch.cuda.is_bf16_supported(),
            bf16=torch.cuda.is_bf16_supported(),
            logging_steps=1,
            optim="adamw_8bit",
            weight_decay=0.01,
            lr_scheduler_type="linear",
            seed=42,
            save_strategy="epoch",
            save_total_limit=2,
            report_to="none",  # No W&B/MLflow
        ),
    )

    # Show GPU stats before training
    gpu_stats = torch.cuda.get_device_properties(0)
    print(f"\nGPU: {gpu_stats.name}")
    print(f"VRAM: {gpu_stats.total_memory / 1e9:.1f} GB total, "
          f"{torch.cuda.memory_allocated() / 1e9:.2f} GB allocated")

    # Train
    trainer_stats = trainer.train()

    print(f"\nTraining complete!")
    print(f"  Total time: {trainer_stats.metrics['train_runtime']:.0f}s "
          f"({trainer_stats.metrics['train_runtime']/60:.1f} min)")
    print(f"  Final loss: {trainer_stats.metrics.get('train_loss', 'N/A')}")
    print(f"  Peak VRAM: {torch.cuda.max_memory_allocated() / 1e9:.2f} GB")

    # ─── Step 6: Save LoRA adapter ───────────────────────────────────
    print("\n" + "="*60)
    print("Step 6: Saving LoRA adapter")
    print("="*60)

    adapter_dir = Path(args.save_adapter_only)
    adapter_dir.mkdir(parents=True, exist_ok=True)
    model.save_pretrained(str(adapter_dir))
    tokenizer.save_pretrained(str(adapter_dir))
    print(f"LoRA adapter saved to {adapter_dir}")

    # ─── Step 7: Merge and save full model ───────────────────────────
    print("\n" + "="*60)
    print("Step 7: Merging LoRA into base model")
    print("="*60)

    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    # Unsloth merge — done on CPU to avoid VRAM overflow
    model.save_pretrained_merged(
        str(output_dir),
        tokenizer,
        save_method="merged_16bit",  # Save as float16 for GGUF conversion
    )
    print(f"Merged model saved to {output_dir}")

    print("\n" + "="*60)
    print("TRAINING COMPLETE")
    print(f"  Adapter: {adapter_dir}")
    print(f"  Merged model: {output_dir}")
    print(f"  Next step: python scripts/export_gguf.py")
    print("="*60)


if __name__ == "__main__":
    main()
