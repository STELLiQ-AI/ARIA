# LLM Package — On-Device Language Model Inference (NPU Build)

## Responsibility
Builds prompts in Llama 3.1 Instruct format, runs LLM inference on Adreno 750 GPU
using **Llama 3.1 8B Instruct Q4_K_M** (~4.6GB), parses structured JSON output
into TC 7-0.1 AAR summaries.

## What Changed from CPU Build
- **Model:** Llama 3.2 3B → **Llama 3.1 8B** (locked decision — see root CLAUDE.md)
- **Quality:** Dramatically better AAR extraction across all four TC 7-0.1 fields
- **RAM:** ~2.5GB peak → **~5.5GB peak** (fits within S24 Ultra 12GB envelope)
- **Speed:** Still meets targets — TTFT ≤ 2s, decode ≥ 20 tok/s on Adreno 750
- **Prompt format:** Llama 3.1 Instruct uses same token family as Llama 3.2
- **GGUF download:** ~4.6GB (was ~2.3GB) — pre-download for demo logistics

## Data Flow
```
Transcript (from TranscriptAccumulator)
    |
    v
AARPromptBuilder.build(transcript, template)
    |  Wraps in Llama 3.1 Instruct tokens:
    |  <|start_header_id|>system<|end_header_id|>\n\n{system_prompt}<|eot_id|>
    |  <|start_header_id|>user<|end_header_id|>\n\n{transcript}<|eot_id|>
    |  <|start_header_id|>assistant<|end_header_id|>\n\n
    v
LlamaEngine.complete(prompt, callback)
    |  Native inference via llama.cpp on aria-llm-worker thread
    |  Adreno 750 GPU (32 layers, OpenCL), streams tokens via callback
    |  Model: Llama 3.1 8B Instruct Q4_K_M (~4.6GB GGUF)
    |  TTFT: ~1.2-1.5s | Decode: ~25-35 tok/s
    v
AARJsonParser.parse(rawOutput)
    |  Tries strict JSON first, falls back to regex extraction
    v
AARSummary (immutable data object)
    |  Fields: title, whatWasPlanned, whatHappened, whyItHappened, howToImprove
    |  Completeness score: populated fields / 4.0
    v
Room DB (via AARRepository)
```

## Files

| File | Responsibility |
|------|---------------|
| `LlamaEngine.java` | JNI bridge to llama.cpp. Load model, run inference, cancellation support. Synchronized — single inference at a time. |
| `AARPromptBuilder.java` | Builds Llama 3.1 Instruct-format prompts using SummaryTemplate. Truncates transcript to MAX_TRANSCRIPT_CHARS. |
| `AARJsonParser.java` | Parses LLM output into AARSummary. Dual strategy: strict JSON parse → regex field extraction fallback. |
| `SummaryTemplate.java` | Enum of prompt templates. RETROSPECTIVE is primary; 8B model supports all templates well. |

## Key Contracts

### LlamaEngine
- `complete(String prompt, TokenCallback callback)` → int — BLOCKING, runs on aria-llm-worker
- Return codes: 0=success, -1=not loaded, -2=no method, -3=bad prompt, -4=tokenize fail, -5=Java exception, **-6=cancelled**
- `requestCancellation()` — Sets AtomicBoolean, checked by native code every token
- `isCancellationRequested()` — Called from native via JNI on the LlamaEngine `obj` reference
- Native library: `libllm_jni.so` (loaded via `Constants.JNI_LIB_LLM`)
- **GPU config:** `nGpuLayers = 32` (all layers on Adreno 750 via OpenCL) — MANDATORY for performance targets
- **Context size:** 4096 tokens — sufficient for MAX_TRANSCRIPT_CHARS + prompt + AAR output

### AARPromptBuilder
- **Token format:** Llama 3.1 Instruct (same token family as Llama 3.2)
  - System: `<|start_header_id|>system<|end_header_id|>\n\n`
  - User: `<|start_header_id|>user<|end_header_id|>\n\n`
  - Assistant: `<|start_header_id|>assistant<|end_header_id|>\n\n`
  - End of turn: `<|eot_id|>`
- `parse_special=true` in native tokenizer so control tokens are recognized as special IDs
- **8B advantage:** The 8B model reliably handles the full TC 7-0.1 four-step extraction
  including nuanced attribution of speech segments to AAR steps despite conversational overlap.
  The 3B model struggled with this, producing same-content fields or missed steps.

### AARJsonParser
- `parse(String rawOutput)` → AARSummary
- `isComplete(AARSummary)` → boolean — treats null, empty, AND `NOT_CAPTURED` sentinel as incomplete
- Regex uses possessive quantifier `++` to prevent catastrophic backtracking
- Includes fallback regex for unquoted JSON values (8B model less likely to produce these, but fallback retained)

## Important Notes
- **Model:** Llama 3.1 8B Instruct Q4_K_M (~4.6GB GGUF) — NOT 3B, NOT Phi-3
- **GPU:** All 32 layers on Adreno 750 via OpenCL. Kernel pre-warm at model load.
- **GPU yielding:** Native code sleeps 3ms every 2 decode tokens to prevent UI starvation
- **Prompt batching:** 128-token prompt batches to avoid GPU fence timeouts
- **Context limit:** MAX_TRANSCRIPT_CHARS=13,600 prevents overflow of 4096-token context window
- **Download logistics:** ~4.6GB model must be pre-downloaded. `isModelReady()` check at launch.
  Backup copy on USB drive recommended for demo day.
- **FALLBACK ONLY:** Llama 3.2 3B Q4_K_M as logistics insurance if 8B unavailable on demo day.
  Do not substitute 3B as primary without PI approval.

## 8B vs. 3B Decision Rationale (Summary)

| Factor | 8B | 3B |
|---|---|---|
| AAR Quality | Excellent — all 4 TC 7-0.1 fields | Acceptable for AAR template only |
| TTFT | ~1.2-1.5s (meets ≤2s target) | ~0.5-0.8s |
| Decode | ~25-35 tok/s (meets ≥20 target) | ~50-70 tok/s |
| RAM | ~5.5GB peak | ~2.5GB peak |
| S24 Ultra Fit | ~9.3GB total, 3GB headroom | ~6.2GB total, 6GB headroom |

Quality of the output IS the demo. The 8B's structured extraction capability is
the reason the model was locked — a malformed AAR with missing TC 7-0.1 steps is
a larger demo risk than a 1-second latency difference.
