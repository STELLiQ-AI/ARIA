# ARIA Fine-Tuning Pipeline

Fine-tune Llama 3.1 8B Instruct for ARIA's 6-field summary schema using QLoRA via Unsloth.

## Hardware Requirements

| Component | Minimum | Recommended |
|-----------|---------|-------------|
| GPU | 16GB VRAM (RTX 4090, RTX 5080, A100) | RTX 5080 Mobile 16GB |
| RAM | 32GB | 64GB |
| Disk | 30GB free | 50GB free |
| CUDA | 12.1+ | 12.4+ |

## Quick Start (RTX 5080 Laptop)

```bash
# 1. Clone the repo
git clone https://github.com/STELLiQ-AI/ARIA.git
cd ARIA/fine-tune

# 2. Create Python environment
python -m venv venv
source venv/bin/activate  # Linux/Mac
# OR: venv\Scripts\activate  # Windows

# 3. Install dependencies
pip install -r requirements.txt

# 4. Run training (~30-45 min on RTX 5080 16GB)
python scripts/train.py --data data/training_8b_final.jsonl --epochs 3

# 5. Export to GGUF Q4_K_M (~4.6GB, for S24 Ultra deployment)
python scripts/export_gguf.py

# 6. Validate the model output quality
python scripts/validate.py --model output/aria-aar-8b-q4_k_m.gguf
```

## Training Data

`data/training_8b_final.jsonl` — 160 high-quality training examples:

| Template | Count | Weight | Description |
|----------|-------|--------|-------------|
| Military AAR | 64 | 40% | TC 7-0.1 doctrine, tactical scenarios |
| Retrospective | 32 | 20% | Sprint retros, project reviews, business ops |
| Incident Postmortem | 32 | 20% | Outage reviews, security incidents, RCA |
| Simple Summary | 32 | 20% | Standups, 1-on-1s, general meetings |

Each example has the 3-message Llama 3.1 Instruct chat format:
```json
{
  "messages": [
    {"role": "system", "content": "<template system prompt>"},
    {"role": "user", "content": "Summarize this meeting transcript:\n\n<transcript>"},
    {"role": "assistant", "content": "<6-field JSON summary>"}
  ]
}
```

## Output Schema (6 Fields)

The fine-tuned model produces:
```json
{
  "title": "Descriptive Title In Title Case",
  "what_was_planned": "Goals, agenda, objectives",
  "what_happened": "Key events, decisions, outcomes",
  "why_it_happened": "Root causes, contributing factors",
  "how_to_improve": "Specific actions with owners",
  "ai_perspective": "Independent analytical assessment"
}
```

## Scripts

| Script | Purpose | Usage |
|--------|---------|-------|
| `train.py` | QLoRA fine-tuning via Unsloth | `python scripts/train.py --data data/training_8b_final.jsonl` |
| `export_gguf.py` | Merge LoRA adapter + export GGUF | `python scripts/export_gguf.py` |
| `validate.py` | Test model output quality | `python scripts/validate.py --model output/aria-aar-8b-q4_k_m.gguf` |
| `generate_data.py` | Generate more synthetic training data (requires Claude API) | `python scripts/generate_data.py --count 100` |
| `download_datasets.py` | Download real-world meeting datasets from HuggingFace | `python scripts/download_datasets.py` |

## Training Parameters

| Parameter | Value | Notes |
|-----------|-------|-------|
| Base model | `unsloth/Meta-Llama-3.1-8B-Instruct` | 4-bit quantized for QLoRA |
| LoRA rank | 64 | Good balance of capacity vs. memory |
| LoRA alpha | 64 | alpha = rank for stable training |
| Learning rate | 2e-4 | Standard for QLoRA |
| Epochs | 3 | Sufficient for 160 examples |
| Max seq length | 4096 | Matches ARIA's LLM_CONTEXT_TOKENS |
| Batch size | 2 (effective 8 with gradient accumulation) | Fits 16GB VRAM |

## Deployment to S24 Ultra

After training and export:

```bash
# Push the GGUF to the device
adb push output/aria-aar-8b-q4_k_m.gguf /sdcard/Android/data/com.stelliq.aria/files/models/

# Verify it loads
adb shell am start -W -n com.stelliq.aria/.ui.SplashActivity
adb logcat | grep -E "llama|ggml|opencl|gpu_layers|offload"
```
