/**
 * qnn_wrapper.h
 *
 * QNN HTP runtime wrapper for ARIA Whisper encoder acceleration.
 * Provides the aria::qnn namespace expected by the ARIA-modified whisper.cpp.
 *
 * Responsibility:
 * - Initialize QNN HTP backend via dlopen/dlsym (no build-time link required)
 * - Load pre-compiled V75 context binary (whisper_encoder_s24ultra.bin)
 * - Execute encoder graph on Hexagon NPU
 * - Clean up all QNN resources
 *
 * Architecture Position:
 * Called from whisper.cpp encoder path when ARIA_USE_QNN is defined.
 * Sits between whisper.cpp and the QNN SDK C API.
 *
 * Thread Safety:
 * Single-threaded — called only from aria-asr-worker thread.
 * The QNN runtime itself is not thread-safe for a single context.
 *
 * QNN: Uses QNN SDK v2.44.0.260225 HTP backend targeting Hexagon V75.
 *
 * @author STELLiQ Engineering
 * @version 0.2.0-npu
 * @since ARIA NPU Build — 2026-03-11
 */

#pragma once

#include <cstddef>

namespace aria {
namespace qnn {

/**
 * Initialize the QNN HTP backend.
 * Loads libQnnHtp.so via dlopen, gets interface providers, creates backend.
 *
 * @return true on success, false on failure (logged via LOGE)
 */
bool initialize();

/**
 * Load a pre-compiled QNN context binary from disk.
 * The context binary contains the Whisper encoder graph compiled for a
 * specific Hexagon target (V75 for S24 Ultra).
 *
 * WHY: Context binaries are device-specific. A V73 binary will NOT work
 * on V75 hardware (delegation count = 0, silent CPU fallback).
 *
 * @param path Absolute filesystem path to the .bin context binary
 * @return true on success, false on failure
 */
bool loadContextBinary(const char* path);

/**
 * Check if QNN is initialized and a context binary is loaded.
 *
 * @return true if ready for executeGraph() calls
 */
bool isInitialized();

/**
 * Execute the encoder graph on HTP.
 *
 * @param inputData  Mel spectrogram data [80 * 3000] in row-major [mels, frames] order
 * @param inputSize  Number of floats in input (must be 80 * 3000 = 240,000)
 * @param outputData Buffer for encoder embeddings [1500 * n_state] in row-major order
 * @param outputSize Number of floats in output buffer
 * @return true on success, false on failure (caller should fall back to CPU)
 */
bool executeGraph(const float* inputData, size_t inputSize,
                  float* outputData, size_t outputSize);

/**
 * Release all QNN resources (context, backend, dlclose).
 * Safe to call multiple times or when not initialized.
 */
void release();

} // namespace qnn
} // namespace aria
