/**
 * Constants.java
 *
 * Central repository for all ARIA Demo APK configuration constants. This class eliminates
 * magic numbers throughout the codebase and provides single-source-of-truth for all
 * tunable parameters.
 *
 * <p>Responsibility:
 * <ul>
 *   <li>Define all audio capture configuration (sample rate, buffer sizes, VAD thresholds)</li>
 *   <li>Define all sliding window ASR parameters</li>
 *   <li>Define all LLM configuration (GPU layers, context size, inference params)</li>
 *   <li>Define model file paths and expected sizes for integrity verification</li>
 *   <li>Define performance targets and thresholds for ARIA_PERF logging</li>
 *   <li>Define database, notification, and logging constants</li>
 *   <li>This class does NOT contain runtime state — only compile-time constants</li>
 * </ul>
 *
 * <p>Architecture Position:
 * Leaf dependency — every other class may depend on Constants. Constants depends on nothing.
 * All values are public static final and initialized at class load time.
 *
 * <p>Thread Safety:
 * Fully thread-safe. All fields are immutable primitive or String constants.
 *
 * <p>Air-Gap Compliance:
 * LLM_DOWNLOAD_URL is the only network-related constant. It is used exclusively by
 * ModelFileManager during first-run setup. No network calls occur during normal operation.
 *
 * @author STELLiQ Engineering
 * @version 0.1.0
 * @since ARIA Demo Build — 2026-03-02
 */
package com.stelliq.aria.util;

public final class Constants {

    // WHY: Private constructor prevents instantiation — this is a utility class
    private Constants() {
        throw new AssertionError("Constants is a non-instantiable utility class");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // AUDIO CONFIGURATION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * AudioRecord sample rate in Hz.
     * whisper.cpp requires 16kHz mono PCM — do not change without updating native code.
     *
     * @see com.stelliq.aria.asr.AudioCaptureManager
     */
    public static final int AUDIO_SAMPLE_RATE_HZ = 16_000;

    /**
     * Alias for AUDIO_SAMPLE_RATE_HZ for whisper-related code.
     * WHY: Semantic clarity in ASR pipeline components.
     */
    public static final int WHISPER_SAMPLE_RATE = AUDIO_SAMPLE_RATE_HZ;

    /**
     * AudioRecord buffer size in bytes.
     * Calculated as: 2048 samples × 2 bytes per sample (16-bit PCM) = 4096 bytes.
     * Provides ~128ms of buffer at 16kHz, balancing latency vs. underrun risk.
     */
    public static final int AUDIO_BUFFER_SIZE_BYTES = 4096;

    /**
     * AudioRecord read chunk size in samples.
     * Aligned with VAD chunk size for efficient pipeline processing.
     */
    public static final int AUDIO_READ_CHUNK_SAMPLES = 512;

    /**
     * Number of audio channels. Mono only — whisper.cpp does not support stereo.
     */
    public static final int AUDIO_CHANNELS = 1;

    /**
     * Bits per sample. 16-bit PCM is AudioRecord's most efficient format.
     */
    public static final int AUDIO_BITS_PER_SAMPLE = 16;

    /**
     * Software gain multiplier applied to raw PCM samples before VAD and ASR.
     * 1.0 = no gain (normal speech), 3.0 = 3x amplification (laptop speaker demo).
     * Samples are clamped to Short.MIN_VALUE/MAX_VALUE to prevent clipping wrap-around.
     */
    public static final float AUDIO_INPUT_GAIN = 3.0f;

    // ═══════════════════════════════════════════════════════════════════════════
    // VAD (VOICE ACTIVITY DETECTION) CONFIGURATION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * VAD chunk size in samples.
     * Silero VAD v4 is trained on 512-sample (32ms) windows at 16kHz.
     * Using other chunk sizes produces unreliable probability outputs.
     *
     * <p>RISK: Do not change without revalidating VAD accuracy.
     */
    public static final int VAD_CHUNK_SAMPLES = 512;

    /**
     * VAD chunk duration in milliseconds.
     * Calculated as: 512 samples / 16000 Hz × 1000 = 32ms.
     */
    public static final int VAD_CHUNK_DURATION_MS = 32;

    /**
     * Speech/silence classification threshold for Silero VAD.
     * Range: [0.0, 1.0]. Higher values = more conservative (fewer false positives).
     * 0.03 is very permissive — needed because 5s windows cause Silero LSTM state
     * drift that dampens probabilities (speech maxed at 0.134 with 0.35 threshold).
     * whisper-small.en handles silence well via suppress_blank + suppress_nst.
     */
    public static final float VAD_SPEECH_THRESHOLD = 0.03f;

    /**
     * Minimum silence duration in milliseconds before flushing accumulated speech
     * buffer to whisper.cpp for transcription.
     *
     * <p>WHY 800ms: Long enough to avoid mid-sentence flushes, short enough for
     * responsive perceived latency. Tunable based on field testing.
     */
    public static final int VAD_FLUSH_SILENCE_MS = 800;

    /**
     * Maximum silence duration in milliseconds before resetting VAD state.
     * After 3 seconds of silence, we consider any subsequent speech a new utterance
     * context — helps prevent context bleed across separate speaker turns.
     */
    public static final int VAD_RESET_SILENCE_MS = 3000;

    /**
     * ONNX Runtime intra-op thread count for Silero VAD.
     * 1 thread is sufficient for the lightweight VAD model — minimizes contention.
     */
    public static final int VAD_ONNX_THREADS = 1;

    // ═══════════════════════════════════════════════════════════════════════════
    // SLIDING WINDOW ASR CONFIGURATION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Whisper inference window size in samples.
     * 5.0 seconds at 16kHz = 80,000 samples.
     *
     * <p>WHY 5 seconds: NPU encoder (V79 HTP) processes small.en fast enough
     * to keep pace with 5s windows (expected RTF ~0.3-0.4x). Shorter windows
     * reduce perceived latency (~7s vs ~13s with 10s windows) while small.en's
     * 244M parameters compensate for the shorter decoder context.
     */
    public static final int WINDOW_SAMPLES = 80_000;

    /**
     * Window duration in milliseconds for logging and metrics.
     */
    public static final int WINDOW_DURATION_MS = 5_000;

    /**
     * Alias for WINDOW_DURATION_MS for ASR sliding window components.
     */
    public static final int ASR_WINDOW_MS = WINDOW_DURATION_MS;

    /**
     * Sliding window stride in samples.
     * 5.0 seconds = 80,000 samples. No overlap — matches window size.
     *
     * <p>WHY 5 second stride (no overlap): small.en with NPU encoder at
     * RTF ~0.3-0.4x means inference (~1.5-2.0s) completes well before next
     * window arrives (5s). ~3s idle + 500ms thermal yield between windows.
     */
    public static final int STRIDE_SAMPLES = 80_000;

    /**
     * Stride duration in milliseconds for logging and metrics.
     */
    public static final int STRIDE_DURATION_MS = 5_000;

    /**
     * Alias for STRIDE_DURATION_MS for ASR sliding window components.
     */
    public static final int ASR_STRIDE_MS = STRIDE_DURATION_MS;

    /**
     * Hard maximum buffer size in samples before forced flush.
     * whisper.cpp has a 30-second (480,000 samples) hard limit.
     * Exceeding this causes undefined behavior in native code.
     *
     * <p>RISK: Never accumulate more than this. Force flush if reached.
     */
    public static final int MAX_BUFFER_SAMPLES = 480_000;

    /**
     * Maximum buffer duration in milliseconds.
     */
    public static final int MAX_BUFFER_DURATION_MS = 30_000;

    /**
     * Deduplication overlap word count for transcript accumulation.
     * Compare last N words of previous window output to first N words of new output.
     * 5 words provides reliable overlap detection without over-aggressive stripping.
     */
    public static final int DEDUP_OVERLAP_WORDS = 5;

    // ═══════════════════════════════════════════════════════════════════════════
    // WHISPER ASR CONFIGURATION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Number of CPU threads for whisper.cpp decoder inference.
     * WHY: Encoder now runs on Hexagon NPU (V79 HTP), so decoder only needs
     * 4 Cortex-X4 big cores. Remaining cores handle audio capture + UI.
     */
    public static final int WHISPER_THREADS = 4;

    /**
     * Maximum tokens per whisper.cpp chunk output.
     * Caps runaway decoding on malformed audio. 256 tokens is ~50+ words.
     */
    public static final int WHISPER_MAX_TOKENS = 256;

    /**
     * Whisper language code. English-only for this deployment.
     */
    public static final String WHISPER_LANGUAGE = "en";

    // ═══════════════════════════════════════════════════════════════════════════
    // LLM CONFIGURATION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Number of Llama 3.1 8B transformer layers to offload to Adreno 750 GPU.
     * 32 = full model offload. All transformer layers run on OpenCL GPU.
     *
     * <p>QD-8: MANDATORY — setting to 0 causes CPU-only LLM with 20-40s TTFT.
     * <p>Set to 0 for CPU fallback if OpenCL initialization fails.
     */
    public static final int LLM_GPU_LAYERS = 32;

    /**
     * CPU fallback GPU layer count — zero means pure CPU inference.
     * ~3x slower than GPU but functionally correct for demo resilience.
     */
    public static final int LLM_CPU_FALLBACK_LAYERS = 0;

    /**
     * llama.cpp context window size in tokens.
     * 4096 tokens supports ~15 minutes of meeting transcript plus prompt overhead.
     */
    public static final int LLM_CONTEXT_TOKENS = 4096;

    /**
     * Alias for LLM_CONTEXT_TOKENS for prompt builder.
     */
    public static final int LLM_CONTEXT_SIZE = LLM_CONTEXT_TOKENS;

    /**
     * llama.cpp batch size for prompt prefill.
     * 128 tokens per GPU dispatch — reduces peak GPU memory during prefill by ~75%
     * compared to 512. Native code (llama_jni.cpp) uses this as PROMPT_BATCH_SIZE.
     *
     * <p>WHY 128 not 512: 512-token batches allocate ~128MB of intermediate GPU
     * tensors per dispatch on 8B model. 128-token batches reduce this to ~32MB,
     * preventing Adreno 750 GPU memory faults during prompt processing.
     * TTFT increases by ~200-400ms (acceptable — still within 2s target).
     */
    public static final int LLM_BATCH_SIZE = 128;

    /**
     * Number of CPU threads for llama.cpp non-GPU operations (sampling, etc.).
     */
    public static final int LLM_CPU_THREADS = 4;

    /**
     * Inference temperature for Llama 3.1 8B Instruct.
     * 0.1 = near-deterministic output for structured JSON extraction.
     * Higher values cause JSON format deviation.
     */
    public static final float LLM_TEMPERATURE = 0.1f;

    /**
     * Top-p (nucleus sampling) for Llama 3.1 8B Instruct inference.
     * 0.9 keeps output focused while allowing minor variation.
     */
    public static final float LLM_TOP_P = 0.9f;

    /**
     * Maximum new tokens to generate for AAR summary.
     * 512 tokens supports 4 TC 7-0.1 fields × ~100 words each.
     * Caps runaway generation — target full summary is ≤400 tokens.
     */
    public static final int LLM_MAX_NEW_TOKENS = 512;

    /**
     * Minimum interval between UI updates during LLM token streaming, in milliseconds.
     * Throttles main-thread posts from every token (~30/sec) to ~12/sec.
     *
     * <p>WHY 80ms: At 25-35 tok/s, unthrottled streaming fires 25-35 Handler.post() calls
     * per second, each with an O(n) StringBuilder.toString() copy and a LiveData dispatch
     * that triggers TextView re-render (competing with HWUI for GPU). 80ms = ~12 FPS,
     * which is perceptually smooth for streaming text while cutting main-thread pressure
     * by ~60%. Human perception of text streaming saturates at ~10-15 updates/sec.
     */
    public static final long LLM_TOKEN_STREAM_THROTTLE_MS = 80L;

    /**
     * Alias for LLM_MAX_NEW_TOKENS for prompt builder.
     */
    public static final int LLM_MAX_OUTPUT_TOKENS = LLM_MAX_NEW_TOKENS;

    /**
     * Maximum transcript length in characters before truncation.
     * Calculated as: 3400 tokens × 4 chars/token = 13,600 characters.
     * Safety net for 4096-token context window — prevents native context overflow.
     * Leaves ~700 tokens for system prompt + output + prompt overhead.
     *
     * <p>WHY truncate from beginning: Keeps most recent context which is typically
     * the summary/conclusion portion of a meeting — more valuable for AAR.
     */
    public static final int MAX_TRANSCRIPT_CHARS = 13_600;

    /**
     * Approximate characters per token for estimation.
     * English text averages ~4 characters per token with Phi-3 tokenizer.
     */
    public static final int APPROX_CHARS_PER_TOKEN = 4;

    /**
     * Maximum transcript tokens (used for truncation calculation).
     */
    public static final int MAX_TRANSCRIPT_TOKENS = 3500;

    // ═══════════════════════════════════════════════════════════════════════════
    // PERFORMANCE TARGETS
    // All targets must be instrumented via PerfLogger. Log ARIA_PERF warning if exceeded.
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Whisper Real-Time Factor target.
     * RTF = inference_time / audio_duration. Target ≤0.5x means 5s audio transcribes in ≤2.5s.
     *
     * <p>WHY 0.5x: NPU-accelerated encoder (V79 HTP) with small.en achieves ~0.3-0.4x RTF.
     * 0.5x target provides 25% headroom for thermal throttling or complex audio.
     *
     * <p>PERF: Log warning to ARIA_PERF if exceeded 3 consecutive times.
     */
    public static final float PERF_TARGET_WHISPER_RTF = 0.5f;

    /**
     * LLM Time-To-First-Token target in milliseconds (warm, model loaded).
     * Target: ≤2000ms from complete() call to first token callback.
     */
    public static final long PERF_TARGET_LLM_TTFT_MS = 2_000L;

    /**
     * LLM decode speed target in tokens per second.
     * Target: ≥20 tok/s on Adreno 750 GPU. Fallback threshold: 15 tok/s.
     */
    public static final float PERF_TARGET_LLM_DECODE_TPS = 20.0f;

    /**
     * LLM decode speed threshold for GPU fallback decision.
     * If GPU decode is below this, consider CPU might actually be faster.
     */
    public static final float PERF_LLM_DECODE_FALLBACK_THRESHOLD = 15.0f;

    /**
     * OpenCL cold-start initialization target in milliseconds.
     * First-ever OpenCL use triggers SPIR-V kernel compilation (2-4 seconds).
     *
     * <p>RISK: Pre-warm at ARIASessionService.onCreate() to avoid this penalty during user action.
     */
    public static final long PERF_TARGET_OPENCL_INIT_MS = 4_000L;

    /**
     * Full summary pipeline target in milliseconds (SUMMARIZING state duration).
     * For typical ~200 token output: ≤12 seconds total from transcript input to JSON output.
     */
    public static final long PERF_TARGET_SUMMARY_TOTAL_MS = 12_000L;

    /**
     * Peak RAM target in bytes during LLM inference.
     * Target: <8GB to stay within 12GB device envelope with comfortable headroom.
     */
    public static final long PERF_TARGET_PEAK_RAM_BYTES = 8_000_000_000L;

    /**
     * Perceived ASR latency target in milliseconds.
     * From VAD speech onset to text appearing on screen: ≤8000ms.
     *
     * <p>WHY 8s: small.en with 5s window + NPU encoder gives ~7-8s perceived latency
     * (5s audio fill + ~1.5-2.0s inference). Significant improvement over 10s window.
     */
    public static final long PERF_TARGET_PERCEIVED_ASR_LATENCY_MS = 8_000L;

    /**
     * Consecutive performance violation count before logging warning.
     */
    public static final int PERF_VIOLATION_THRESHOLD = 3;

    // ═══════════════════════════════════════════════════════════════════════════
    // WHISPER MODEL — BUNDLED IN APK assets/
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * AssetManager path for whisper model inside APK.
     * File lives at: src/main/assets/models/ggml-small.en-q8_0.gguf
     *
     * <p>WHY small.en Q8_0: 244M parameters — dramatically better WER than base.en.
     * Q8_0 quantization preserves near-FP16 accuracy with 2x size reduction.
     * NPU encoder handles the compute bottleneck, making the larger model viable.
     *
     * <p>RISK: NEVER place in res/raw/ — DEFLATE compression corrupts binary files.
     * assets/ directory preserves files byte-for-byte (QD-6).
     *
     * @see com.stelliq.aria.util.ModelFileManager#copyWhisperModelIfNeeded
     */
    public static final String MODEL_WHISPER_ASSET_PATH = "models/ggml-small.en-q8_0.bin";

    /**
     * Runtime filename after one-time copy from APK assets to internal filesDir.
     * Full path: context.getFilesDir() + "/models/" + MODEL_WHISPER_FILENAME
     * This absolute path is passed to whisper_jni.cpp loadModel().
     */
    public static final String MODEL_WHISPER_FILENAME = "ggml-small.en-q8_0.bin";

    /**
     * Subdirectory name within filesDir for whisper model storage.
     */
    public static final String MODEL_WHISPER_DIR = "models";

    /**
     * Expected byte size of whisper small.en Q8_0 GGUF after asset copy.
     * Used as integrity gate — if size mismatch after copy, model is corrupt.
     *
     * <p>Value: 264,477,561 bytes (264MB) for ggml-small.en-q8_0.bin
     */
    public static final long MODEL_WHISPER_EXPECTED_BYTES = 264_477_561L;

    /**
     * Expected SHA256 checksum of whisper model for integrity verification.
     * WHY: Empty placeholder — actual checksum populated in production builds.
     */
    public static final String MODEL_WHISPER_EXPECTED_SHA256 = "";

    // ═══════════════════════════════════════════════════════════════════════════
    // LLAMA 3.1 8B LLM — DOWNLOADED BY USER VIA IN-APP BUTTON
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Destination filename for LLM GGUF model.
     * Full path: context.getExternalFilesDir("models") + "/" + MODEL_LLM_FILENAME
     *
     * <p>WHY 8B not 3B: Llama 3.1 8B Instruct handles all 4 TC 7-0.1 AAR fields
     * reliably, while 3B degrades on complex transcripts. S24 Ultra's 12GB RAM
     * and Adreno 750 GPU comfortably handle 8B with ~3GB headroom.
     *
     * <p>WHY external files dir: Persists across APK reinstalls. User downloads once.
     */
    public static final String MODEL_LLM_FILENAME = "aria_llama31_8b_q4km.gguf";

    /**
     * Human-readable display name for the active LLM model.
     * Shown on the Settings page so the user knows which model is loaded.
     */
    public static final String MODEL_LLM_DISPLAY_NAME = "Llama 3.1 8B Instruct";

    /**
     * Alias for MODEL_LLM_FILENAME for backward compatibility.
     */
    public static final String MODEL_LLM = MODEL_LLM_FILENAME;

    /**
     * Subdirectory name within externalFilesDir for LLM model storage.
     */
    public static final String MODEL_LLM_DIR = "models";

    /**
     * HuggingFace direct download URL for Llama 3.1 8B Instruct Q4_K_M GGUF.
     *
     * <p>IMPORTANT: Verify this URL is valid before each new build:
     * <pre>curl -I &lt;URL&gt;</pre>
     * Should return HTTP 200 or 302. HuggingFace URLs can change when model owners
     * publish new revisions.
     *
     * <p>Source: bartowski/Meta-Llama-3.1-8B-Instruct-GGUF on HuggingFace
     * <p>Actual file: Meta-Llama-3.1-8B-Instruct-Q4_K_M.gguf (~4.6GB)
     * <p>Renamed to aria_llama31_8b_q4km.gguf on device for consistency.
     *
     * @see com.stelliq.aria.util.ModelFileManager#startLlmDownload
     */
    public static final String LLM_DOWNLOAD_URL =
            "https://huggingface.co/bartowski/Meta-Llama-3.1-8B-Instruct-GGUF/resolve/main/" +
            "Meta-Llama-3.1-8B-Instruct-Q4_K_M.gguf";

    /**
     * Expected byte size of Llama 3.1 8B Instruct Q4_K_M GGUF.
     * Used as integrity gate after download completes — if size mismatch, delete and retry.
     *
     * <p>Value: 4,920,739,232 bytes (~4.6GB) for Meta-Llama-3.1-8B-Instruct-Q4_K_M.gguf
     * <p>Source: bartowski/Meta-Llama-3.1-8B-Instruct-GGUF on HuggingFace
     */
    public static final long MODEL_LLM_EXPECTED_BYTES = 4_920_739_232L;

    /**
     * Expected SHA256 checksum of LLM model for integrity verification.
     * WHY: Empty placeholder — actual checksum populated in production builds.
     */
    public static final String MODEL_LLM_EXPECTED_SHA256 = "";

    /**
     * SharedPreferences key for storing DownloadManager download ID.
     * Used to track existing download and prevent duplicate enqueues.
     */
    public static final String PREF_KEY_LLM_DOWNLOAD_ID = "llm_download_id";

    /**
     * SharedPreferences key for LLM download completion flag.
     */
    public static final String PREF_KEY_LLM_DOWNLOAD_COMPLETE = "llm_download_complete";

    // ═══════════════════════════════════════════════════════════════════════════
    // SILERO VAD MODEL — BUNDLED IN res/raw/
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Resource ID reference name for Silero VAD ONNX model.
     * Actual resource: R.raw.silero_vad_v4
     *
     * <p>WHY res/raw/ is safe for ONNX: ONNX Runtime handles compressed input;
     * unlike GGUF, ONNX format is not binary-sensitive to DEFLATE compression.
     */
    public static final String MODEL_VAD_RAW_NAME = "silero_vad_v4";

    /**
     * Asset path for Silero VAD ONNX model.
     * Used by SileroVAD for model extraction.
     */
    public static final String MODEL_SILERO_VAD = "models/silero_vad_v4.onnx";

    /**
     * Expected byte size of Silero VAD v4 ONNX model.
     */
    public static final long MODEL_VAD_EXPECTED_BYTES = 1_800_000L;

    // ═══════════════════════════════════════════════════════════════════════════
    // LLM DOWNLOAD NOTIFICATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * DownloadManager notification title shown in system tray during LLM download.
     */
    public static final String LLM_DOWNLOAD_NOTIFICATION_TITLE = "ARIA AI Engine";

    /**
     * DownloadManager notification description shown in system tray during LLM download.
     */
    public static final String LLM_DOWNLOAD_NOTIFICATION_DESC = "Downloading Llama 3.1 8B model...";

    /**
     * Download progress polling interval in milliseconds.
     */
    public static final long DOWNLOAD_PROGRESS_POLL_INTERVAL_MS = 1_000L;

    // ═══════════════════════════════════════════════════════════════════════════
    // DATABASE CONFIGURATION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Room database filename.
     */
    public static final String DATABASE_NAME = "aria_demo.db";

    /**
     * Room database schema version.
     * Increment when adding/modifying @Entity fields. Update aria_schema_v1.sql.
     */
    public static final int DATABASE_VERSION = 3;

    // ═══════════════════════════════════════════════════════════════════════════
    // NOTIFICATION CONFIGURATION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Notification channel ID for ARIASessionService foreground notification.
     * Required for Android 8.0+ (API 26+) notification channels.
     */
    public static final String NOTIFICATION_CHANNEL_ID = "aria_recording";

    /**
     * Notification channel human-readable name shown in Android Settings.
     */
    public static final String NOTIFICATION_CHANNEL_NAME = "ARIA Recording";

    /**
     * Notification channel description shown in Android Settings.
     */
    public static final String NOTIFICATION_CHANNEL_DESC = "Shows when ARIA is recording or processing";

    /**
     * Foreground service notification ID.
     * Must be >0 and consistent for startForeground() calls.
     */
    public static final int NOTIFICATION_ID = 1001;

    /**
     * Notification ID for summary completion alerts.
     * Separate from NOTIFICATION_ID so it doesn't conflict with the foreground service.
     */
    public static final int NOTIFICATION_COMPLETE_ID = 1002;

    /**
     * Notification channel for summary completion alerts.
     * Separate from recording channel — uses DEFAULT importance for sound + heads-up.
     */
    public static final String NOTIFICATION_COMPLETE_CHANNEL_ID = "aria_complete";
    public static final String NOTIFICATION_COMPLETE_CHANNEL_NAME = "ARIA Summaries";
    public static final String NOTIFICATION_COMPLETE_CHANNEL_DESC = "Notifies when meeting summaries are ready";

    /**
     * Intent extra key for navigating to a specific session from notification deep link.
     */
    public static final String EXTRA_NAVIGATE_SESSION_ID = "navigate_session_id";

    // ═══════════════════════════════════════════════════════════════════════════
    // USER PREFERENCES
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * SharedPreferences file name for user settings.
     */
    public static final String PREF_FILE = "aria_preferences";

    /**
     * User's display name for speaker attribution in multi-device meetings.
     * Stored in SharedPreferences. Defaults to device model if not set.
     */
    public static final String PREF_SPEAKER_NAME = "speaker_name";

    /**
     * Selected summary template key for LLM summarization.
     * Stored in SharedPreferences. Defaults to "retrospective".
     */
    public static final String PREF_SUMMARY_TEMPLATE = "summary_template";

    // ═══════════════════════════════════════════════════════════════════════════
    // SESSION STATE MACHINE
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Minimum word count required in transcript to proceed from RECORDING to SUMMARIZING.
     * Sessions with fewer words are considered too short — show toast and return to IDLE.
     */
    public static final int MIN_TRANSCRIPT_WORDS = 10;

    /**
     * Maximum transition delay in milliseconds from STOP tap to SUMMARIZING state entry.
     * P0 acceptance criteria: <500ms.
     */
    public static final long MAX_STOP_TO_SUMMARIZING_DELAY_MS = 500L;

    // ═══════════════════════════════════════════════════════════════════════════
    // LOGCAT TAGS
    // Use these tags for all Log.* calls to enable filtered viewing via adb logcat -s
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Logcat tag for performance metrics.
     * Usage: Log.d(LOG_TAG_PERF, "[WHISPER_RTF] 0.32x (target: 0.5x)");
     */
    public static final String LOG_TAG_PERF = "ARIA_PERF";

    /**
     * Logcat tag for Voice Activity Detection events.
     */
    public static final String LOG_TAG_VAD = "ARIA_VAD";

    /**
     * Logcat tag for ASR/Whisper events.
     */
    public static final String LOG_TAG_ASR = "ARIA_ASR";

    /**
     * Logcat tag for LLM/Llama events.
     */
    public static final String LOG_TAG_LLM = "ARIA_LLM";

    /**
     * Logcat tag for audio capture events.
     */
    public static final String LOG_TAG_AUDIO = "ARIA_AUDIO";

    /**
     * Logcat tag for database operations.
     */
    public static final String LOG_TAG_DB = "ARIA_DB";

    /**
     * Logcat tag for session state machine transitions.
     */
    public static final String LOG_TAG_SESSION = "ARIA_SESSION";

    /**
     * Logcat tag for model file operations (copy, download, verify).
     */
    public static final String LOG_TAG_MODEL = "ARIA_MODEL";

    /**
     * Logcat tag for service lifecycle events.
     */
    public static final String LOG_TAG_SERVICE = "ARIA_SERVICE";

    /**
     * Logcat tag for UI component events.
     */
    public static final String LOG_TAG_UI = "ARIA_UI";

    /**
     * Logcat tag for export operations.
     */
    public static final String LOG_TAG_EXPORT = "ARIA_EXPORT";

    // ═══════════════════════════════════════════════════════════════════════════
    // APP METADATA
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Application ID for package identification.
     */
    public static final String APP_ID = "com.stelliq.aria";

    /**
     * App version name for telemetry and database records.
     */
    public static final String APP_VERSION = "0.2.0-npu";

    /**
     * Build type identifier.
     */
    public static final String BUILD_TYPE = "ARIA NPU Demo APK";

    // ═══════════════════════════════════════════════════════════════════════════
    // JNI LIBRARY NAMES
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Native library name for whisper.cpp JNI.
     * Loaded via System.loadLibrary("asr_jni") in WhisperEngine static block.
     */
    public static final String JNI_LIB_ASR = "asr_jni";

    /**
     * Native library name for llama.cpp JNI.
     * Loaded via System.loadLibrary("llm_jni") in LlamaEngine static block.
     */
    public static final String JNI_LIB_LLM = "llm_jni";

    /**
     * QNN HTP Prepare library name.
     * QD-2: Must load FIRST — dependency of QnnHtp.
     */
    public static final String JNI_LIB_QNN_HTP_PREPARE = "QnnHtpPrepare";

    /**
     * QNN HTP core runtime library name.
     * QD-2: Must load SECOND — after QnnHtpPrepare, before asr_jni.
     */
    public static final String JNI_LIB_QNN_HTP = "QnnHtp";

    // ═══════════════════════════════════════════════════════════════════════════
    // QNN NPU CONFIGURATION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * QNN context binary filename for Whisper encoder on V79 HTP.
     * Pre-compiled via Qualcomm AI Hub targeting Samsung Galaxy S24 Ultra.
     *
     * <p>QD-6: Must be in assets/models/, NEVER res/raw/ (DEFLATE corrupts binaries).
     * <p>QD-7: Must be extracted to filesDir before native load (APK is ZIP).
     */
    public static final String WHISPER_QNN_CONTEXT_FILENAME = "whisper_encoder_s24ultra.bin";

    /**
     * AssetManager path for QNN context binary inside APK.
     */
    public static final String WHISPER_QNN_CONTEXT_ASSET_PATH = "models/whisper_encoder_s24ultra.bin";

    /**
     * Minimum inter-window thermal yield in milliseconds.
     * Sleep between Whisper inference windows to prevent thermal throttling.
     *
     * <p>WHY 300ms minimum: S24 Ultra has 92% larger vapor chamber than S23 TE.
     * Adaptive yield uses RTF as a thermal proxy and scales up from this floor.
     */
    public static final int INTER_WINDOW_YIELD_MIN_MS = 300;

    /**
     * Maximum inter-window thermal yield in milliseconds.
     * Applied when RTF exceeds THERMAL_RTF_HOT — device is actively throttled.
     *
     * <p>WHY 2500ms: Gives Hexagon V75 and Cortex-X4 time to drop below thermal
     * threshold. At 5s windows, 2500ms yield still keeps total cycle under 10s.
     */
    public static final int INTER_WINDOW_YIELD_MAX_MS = 2500;

    /**
     * RTF threshold below which device is considered cool — use minimum yield.
     * Based on observed S24 Ultra baseline: ~0.38x at room temperature.
     */
    public static final float THERMAL_RTF_COOL = 0.38f;

    /**
     * RTF threshold above which device is warming — increase yield.
     * Corresponds to early thermal pressure before visible throttling.
     *
     * <p>NOTE: Not currently referenced by calculateAdaptiveYield(), which uses linear
     * interpolation between THERMAL_RTF_COOL and THERMAL_RTF_HOT. Retained as a
     * semantic marker for diagnostic logging (e.g., logging a warning when RTF enters
     * the warm zone).
     */
    public static final float THERMAL_RTF_WARM = 0.45f;

    /**
     * RTF threshold above which device is hot — aggressive yield.
     * Corresponds to significant thermal throttling (clock reduction visible).
     */
    public static final float THERMAL_RTF_HOT = 0.55f;

    /**
     * Legacy fixed yield constant — kept for reference and fallback.
     * Prefer adaptive yield via calculateAdaptiveYield() in ARIASessionService.
     */
    public static final int INTER_WINDOW_YIELD_MS = 500;

    // ═══════════════════════════════════════════════════════════════════════════
    // INTENT ACTIONS AND EXTRAS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Intent action to start a new recording session.
     */
    public static final String ACTION_START_RECORDING = "com.stelliq.aria.action.START_RECORDING";

    /**
     * Intent action to stop current recording and begin summarization.
     */
    public static final String ACTION_STOP_RECORDING = "com.stelliq.aria.action.STOP_RECORDING";

    /**
     * Intent action to cancel current session and return to IDLE.
     */
    public static final String ACTION_CANCEL_SESSION = "com.stelliq.aria.action.CANCEL_SESSION";

    /**
     * Intent action to regenerate the AAR summary for an existing session.
     * WHY: Allows users to re-run LLM summarization if the first attempt
     * failed or produced poor output (e.g., thermal throttling, incomplete JSON).
     * Requires EXTRA_SESSION_UUID to identify the target session.
     */
    public static final String ACTION_REGENERATE_SUMMARY = "com.stelliq.aria.action.REGENERATE_SUMMARY";

    /**
     * Intent extra key for session UUID.
     */
    public static final String EXTRA_SESSION_UUID = "session_uuid";
}
