# ASR Package — Speech-to-Text Pipeline (NPU Build)

## Responsibility
Captures audio from microphone, filters silence via VAD, transcribes speech via Whisper
using **QNN HTP NPU-accelerated encoder** (Hexagon V75) and CPU decoder,
and post-processes text into timestamped, readable transcript segments.

## Data Flow (NPU Build)
```
AudioCaptureManager (16kHz PCM, aria-audio-capture thread)
    |
    v
SlidingWindowBuffer (5s window / 5s stride, 80Hz Butterworth HPF)
    |
    v
SileroVAD (ONNX, CPU, resets LSTM state per window, filters silence)
    |
    v
WhisperEngine
    |── ENCODER: QNN HTP V75 (Hexagon NPU context binary)
    |   - Input: mel spectrogram (1, 80, 3000) — fixed shape
    |   - Output: embeddings — passed to decoder
    |   - Context binary: whisper_encoder_s24ultra.bin
    |
    |── DECODER: CPU (autoregressive token generation)
    |   - Dynamic control flow — cannot be compiled to static NPU graph
    |   - 4 threads on Cortex-X4 big cores
    |
    |── Model: small.en Q8_0 (244M params, ~488MB GGUF)
    |── Target RTF: ≤ 0.5x (expected ~0.3-0.4x on V75)
    |
    v
TextPostProcessor.processSegment() (capitalize, collapse whitespace)
    |
    v
TranscriptAccumulator (dedup overlapping words, prepend [M:SS] timestamp)
```

## Files

| File | Responsibility | Thread Safety |
|------|---------------|---------------|
| `AudioCaptureManager.java` | AudioRecord wrapper, 32ms read chunks, delivers short[] to buffer | Runs on aria-audio-capture HandlerThread |
| `AudioBufferPool.java` | Object pool for short[] buffers to reduce GC pressure | Thread-safe (synchronized) |
| `SlidingWindowBuffer.java` | Circular buffer, short→float conversion, HPF, window emission | Same thread as caller (not thread-safe) |
| `SileroVAD.java` | Silero v5 ONNX voice activity detection, 512-sample chunks | Same thread as caller |
| `WhisperEngine.java` | JNI bridge to whisper.cpp with **QNN HTP V75 backend**. Model load, transcribe, context binary management, release. | Runs on aria-asr-worker HandlerThread |
| `TextPostProcessor.java` | Static utility — capitalize first letter, after `.!?`, collapse spaces | Stateless, thread-safe |
| `TranscriptAccumulator.java` | Accumulates segments, deduplicates overlap, adds timestamps | Main thread for callbacks |

## Key Contracts

### WhisperEngine (NPU Build — Changed from CPU Build)
- **Library load order (MANDATORY):**
  ```java
  static {
      try { System.loadLibrary("cdsprpc"); } catch (UnsatisfiedLinkError e) { /* non-QC */ }
      System.loadLibrary("QnnHtpPrepare"); // 1st
      System.loadLibrary("QnnHtp");        // 2nd
      System.loadLibrary("asr_jni");       // 3rd
  }
  ```
  cdsprpc loaded from vendor via `<uses-native-library>` manifest declaration.
  Wrong order = silent CPU fallback, HTP delegation count = 0, no error.

- `loadModel(String modelPath, String qnnContextPath)` — BLOCKING (~1.5-3.0s)
  - `modelPath`: absolute path to small.en GGUF
  - `qnnContextPath`: absolute path to extracted `whisper_encoder_s24ultra.bin`
  - `qnnContextPath = null` → CPU fallback (not acceptable for NPU build)
- `transcribe(float[] audioData)` → String — BLOCKING, target RTF ≤ 0.5x
- `isHallucination(String text)` — Static method, detects Whisper artifacts
- Native library: `libasr_jni.so` loaded AFTER QnnHtpPrepare and QnnHtp
- **Context binary extraction:** Must extract from `assets/models/` to `filesDir` on first run
  (native layer needs absolute filesystem path, not asset path)

### SlidingWindowBuffer (Unchanged)
- `addSamples(short[], int)` — Adds raw PCM samples, applies HPF
- `hasWindow()` → boolean — True when stride samples accumulated since last emit
- `getNextWindow()` → float[] — Returns **defensive copy** of normalized [-1.0, 1.0] window
- Window: 80,000 samples (5s), Stride: 80,000 samples (5s) — non-overlapping

### TranscriptAccumulator (Unchanged)
- `addSegment(String text, int segmentIndex, long timestampMs)` — Adds timestamped segment with dedup
- Output format: `[M:SS] Capitalized text` with newline separators
- Dedup: compares last N words (DEDUP_OVERLAP_WORDS=5)

## QNN NPU-Specific Notes

- **Encoder-only NPU delegation:** Only the Whisper encoder runs on HTP. The decoder uses
  conditional branching (if/else on token values) that cannot be compiled to a static context binary.
  This is by design — the encoder IS the compute bottleneck.
- **Context binary:** `whisper_encoder_s24ultra.bin` is compiled via Qualcomm AI Hub targeting
  S24 Ultra (Hexagon V75). It is NOT interchangeable with other Hexagon version binaries.
- **Four `.so` files required:** libQnnHtp.so, libQnnHtpPrepare.so, libQnnHtpV75Stub.so,
  libQnnSystem.so — all must be in `jniLibs/arm64-v8a/`
- **Manifest declaration:** `<uses-native-library android:name="libcdsprpc.so">` required in
  AndroidManifest.xml for FastRPC access to Hexagon DSP. Without this → error 4000, silent CPU fallback.
- **Skel deployment:** `libQnnHtpV75Skel.so` must be on DSP search path. For dev: push to
  `/data/local/tmp/` and set `ADSP_LIBRARY_PATH` in native code before QNN init.
- **Graph name discovery:** AI Hub auto-generates graph names (e.g., `"graph_b_x4j60r"`).
  Native `discoverGraphName()` scans binary metadata — never hardcode graph names.
- **Verification:** `adb logcat | grep HTP` must show delegation count > 0 after first transcription
- **Thermal yield:** 500ms sleep between 10-second Whisper windows (less than S23 TE's 750ms
  due to S24 Ultra's larger vapor chamber)

## Important Notes
- **VAD state reset:** `SileroVAD.resetState()` is called before each window
- **Hallucination filtering:** WhisperEngine.isHallucination() catches common Whisper artifacts
- **HPF:** 80Hz 2nd-order Butterworth high-pass filter removes DC offset and low-frequency noise
- **Model upgrade:** small.en (244M params) vs previous base.en (74M params) — dramatically
  better WER, especially on multi-speaker meeting audio. The accuracy improvement is critical
  because the transcript feeds directly into the 8B LLM for AAR extraction.
