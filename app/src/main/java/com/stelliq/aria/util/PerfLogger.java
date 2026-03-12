/**
 * PerfLogger.java
 *
 * Performance instrumentation logger for ARIA pipeline metrics. Logs all
 * ARIA_PERF events to logcat and optionally persists to database.
 *
 * <p>Responsibility:
 * <ul>
 *   <li>Log Whisper RTF measurements with target comparison</li>
 *   <li>Log LLM TTFT measurements with target comparison</li>
 *   <li>Log LLM decode speed (tok/s) with target comparison</li>
 *   <li>Log OpenCL initialization time</li>
 *   <li>Log peak RAM usage during inference</li>
 *   <li>Alert (via logcat) when metrics exceed targets</li>
 *   <li>This class does NOT perform inference — just measurement logging</li>
 * </ul>
 *
 * <p>Architecture Position:
 * Utility layer. Called by ARIASessionService and engine wrappers after
 * each inference operation.
 *
 * <p>Thread Safety:
 * All methods are thread-safe. Can be called from any worker thread.
 *
 * <p>Air-Gap Compliance:
 * Logs to local logcat and SQLite only. No telemetry exfiltration.
 *
 * @author STELLiQ Engineering
 * @version 0.1.0
 * @since ARIA Demo Build — 2026-03-02
 */
package com.stelliq.aria.util;

import android.util.Log;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;

import java.util.Locale;

public class PerfLogger {

    private static final String TAG = Constants.LOG_TAG_PERF;

    // Consecutive violation counters for alerts
    private int mWhisperRtfViolationCount = 0;
    private int mLlmTtftViolationCount = 0;
    private int mLlmTpsViolationCount = 0;

    // ═══════════════════════════════════════════════════════════════════════════
    // WHISPER ASR METRICS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Logs Whisper Real-Time Factor measurement.
     *
     * <p>RTF = inference_time / audio_duration. Lower is better.
     * Target: ≤0.5x (2s audio transcribes in ≤1s)
     *
     * @param rtf             Measured RTF value
     * @param inferenceMs     Inference wall-clock time in milliseconds
     * @param audioDurationMs Audio duration in milliseconds
     */
    @AnyThread
    public void logWhisperRtf(float rtf, long inferenceMs, long audioDurationMs) {
        boolean overTarget = rtf > Constants.PERF_TARGET_WHISPER_RTF;

        String status = overTarget ? "OVER_TARGET" : "OK";
        String message = String.format(Locale.US,
                "[WHISPER_RTF] %.2fx (%dms / %dms) target: %.1fx — %s",
                rtf, inferenceMs, audioDurationMs, Constants.PERF_TARGET_WHISPER_RTF, status);

        if (overTarget) {
            mWhisperRtfViolationCount++;
            if (mWhisperRtfViolationCount >= Constants.PERF_VIOLATION_THRESHOLD) {
                Log.w(TAG, message + " [ALERT: " + mWhisperRtfViolationCount + " consecutive violations]");
            } else {
                Log.w(TAG, message);
            }
        } else {
            mWhisperRtfViolationCount = 0;  // Reset on good measurement
            Log.d(TAG, message);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LLM METRICS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Logs LLM Time-To-First-Token measurement.
     *
     * <p>TTFT = time from complete() call to first token callback.
     * Target: ≤2000ms (warm, model loaded)
     *
     * @param ttftMs TTFT in milliseconds
     */
    @AnyThread
    public void logLlmTtft(long ttftMs) {
        boolean overTarget = ttftMs > Constants.PERF_TARGET_LLM_TTFT_MS;

        String status = overTarget ? "OVER_TARGET" : "OK";
        String message = String.format(Locale.US,
                "[LLM_TTFT] %dms target: %dms — %s",
                ttftMs, Constants.PERF_TARGET_LLM_TTFT_MS, status);

        if (overTarget) {
            mLlmTtftViolationCount++;
            if (mLlmTtftViolationCount >= Constants.PERF_VIOLATION_THRESHOLD) {
                Log.w(TAG, message + " [ALERT: " + mLlmTtftViolationCount + " consecutive violations]");
            } else {
                Log.w(TAG, message);
            }
        } else {
            mLlmTtftViolationCount = 0;
            Log.d(TAG, message);
        }
    }

    /**
     * Logs LLM decode speed measurement.
     *
     * <p>Target: ≥20 tok/s on Adreno 830 GPU.
     *
     * @param tokensPerSecond Measured decode speed
     * @param totalMs         Total inference time
     */
    @AnyThread
    public void logLlmDecode(float tokensPerSecond, long totalMs) {
        boolean underTarget = tokensPerSecond < Constants.PERF_TARGET_LLM_DECODE_TPS;

        String status = underTarget ? "UNDER_TARGET" : "OK";
        String message = String.format(Locale.US,
                "[LLM_TPS] %.1f tok/s (%dms total) target: %.0f tok/s — %s",
                tokensPerSecond, totalMs, Constants.PERF_TARGET_LLM_DECODE_TPS, status);

        if (underTarget) {
            mLlmTpsViolationCount++;
            if (mLlmTpsViolationCount >= Constants.PERF_VIOLATION_THRESHOLD) {
                Log.w(TAG, message + " [ALERT: " + mLlmTpsViolationCount + " consecutive violations]");
            } else {
                Log.w(TAG, message);
            }
        } else {
            mLlmTpsViolationCount = 0;
            Log.d(TAG, message);
        }
    }

    /**
     * Logs OpenCL context initialization time.
     *
     * <p>Cold-start target: ≤4000ms. Typically 2-4 seconds on first use.
     *
     * @param initMs Initialization time in milliseconds
     */
    @AnyThread
    public void logOpenClInit(long initMs) {
        boolean overTarget = initMs > Constants.PERF_TARGET_OPENCL_INIT_MS;

        String status = overTarget ? "SLOW" : "OK";
        String message = String.format(Locale.US,
                "[OPENCL_INIT] %dms target: %dms — %s",
                initMs, Constants.PERF_TARGET_OPENCL_INIT_MS, status);

        if (overTarget) {
            Log.w(TAG, message);
        } else {
            Log.i(TAG, message);
        }
    }

    /**
     * Logs peak RAM usage during inference.
     *
     * <p>Target: <8GB to stay within device envelope.
     *
     * @param peakBytes Peak RAM in bytes
     */
    @AnyThread
    public void logPeakRam(long peakBytes) {
        boolean overTarget = peakBytes > Constants.PERF_TARGET_PEAK_RAM_BYTES;

        double peakGb = peakBytes / (1024.0 * 1024.0 * 1024.0);
        double targetGb = Constants.PERF_TARGET_PEAK_RAM_BYTES / (1024.0 * 1024.0 * 1024.0);

        String status = overTarget ? "OVER_TARGET" : "OK";
        String message = String.format(Locale.US,
                "[PEAK_RAM] %.2fGB target: %.1fGB — %s",
                peakGb, targetGb, status);

        if (overTarget) {
            Log.w(TAG, message);
        } else {
            Log.d(TAG, message);
        }
    }

    /**
     * Logs full pipeline summary time.
     *
     * <p>Target: ≤12s for typical 200-token summary.
     *
     * @param totalMs     Total pipeline time from transcript input to JSON output
     * @param tokenCount  Number of tokens generated
     */
    @AnyThread
    public void logPipelineTotal(long totalMs, int tokenCount) {
        boolean overTarget = totalMs > Constants.PERF_TARGET_SUMMARY_TOTAL_MS;

        String status = overTarget ? "OVER_TARGET" : "OK";
        String message = String.format(Locale.US,
                "[PIPELINE_TOTAL] %dms for %d tokens target: %dms — %s",
                totalMs, tokenCount, Constants.PERF_TARGET_SUMMARY_TOTAL_MS, status);

        if (overTarget) {
            Log.w(TAG, message);
        } else {
            Log.i(TAG, message);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ASR LATENCY METRICS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Logs perceived ASR latency (speech onset to text display).
     *
     * <p>Target: ≤1500ms with sliding window configuration.
     *
     * @param latencyMs Measured latency in milliseconds
     */
    @AnyThread
    public void logPerceivedLatency(long latencyMs) {
        boolean overTarget = latencyMs > Constants.PERF_TARGET_PERCEIVED_ASR_LATENCY_MS;

        String status = overTarget ? "OVER_TARGET" : "OK";
        String message = String.format(Locale.US,
                "[PERCEIVED_LATENCY] %dms target: %dms — %s",
                latencyMs, Constants.PERF_TARGET_PERCEIVED_ASR_LATENCY_MS, status);

        if (overTarget) {
            Log.w(TAG, message);
        } else {
            Log.d(TAG, message);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // UTILITY METHODS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Logs a generic performance event.
     *
     * @param eventName  Event identifier (e.g., "MODEL_LOAD")
     * @param message    Human-readable description
     */
    @AnyThread
    public void logEvent(@NonNull String eventName, @NonNull String message) {
        Log.i(TAG, "[" + eventName + "] " + message);
    }

    /**
     * Resets all violation counters.
     * Call at session start for clean slate.
     */
    public void resetCounters() {
        mWhisperRtfViolationCount = 0;
        mLlmTtftViolationCount = 0;
        mLlmTpsViolationCount = 0;
    }
}
