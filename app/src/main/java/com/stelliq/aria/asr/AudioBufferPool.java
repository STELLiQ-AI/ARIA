/**
 * AudioBufferPool.java
 *
 * Object pool for audio buffers to reduce GC pressure during recording.
 * Reuses short[] and float[] arrays to minimize allocations.
 *
 * <p>Responsibility:
 * <ul>
 *   <li>Pre-allocate reusable audio buffers</li>
 *   <li>Provide acquire/release API for buffer lifecycle</li>
 *   <li>Track pool statistics for monitoring</li>
 *   <li>Minimize GC pauses during real-time audio processing</li>
 * </ul>
 *
 * <p>Architecture Position:
 * ASR layer utility. Used by SlidingWindowBuffer and audio recording pipeline.
 *
 * <p>Thread Safety:
 * All methods are synchronized. Safe for multi-threaded access.
 *
 * <p>PERF: Pre-allocating 8 buffers prevents ~8KB allocations per second
 * during recording, reducing GC pressure and improving real-time performance.
 *
 * @author STELLiQ Engineering
 * @version 0.1.0
 * @since ARIA Demo Build — 2026-03-04
 */
package com.stelliq.aria.asr;

import android.util.Log;

import androidx.annotation.NonNull;

import com.stelliq.aria.util.Constants;

import java.util.ArrayDeque;

/**
 * Object pool for audio buffers to reduce GC pressure.
 *
 * <p>WHY: Real-time audio processing creates many short-lived arrays.
 * Without pooling, this causes frequent GC pauses that can cause audio dropouts.
 * Pooling reuses buffers to eliminate these allocations during recording.
 */
public class AudioBufferPool {

    private static final String TAG = Constants.LOG_TAG_ASR;

    // ═══════════════════════════════════════════════════════════════════════════
    // POOL CONFIGURATION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Default pool size. 8 buffers provides good coverage for sliding window.
     */
    private static final int DEFAULT_POOL_SIZE = 8;

    /**
     * Maximum pool size to prevent unbounded memory growth.
     */
    private static final int MAX_POOL_SIZE = 16;

    // ═══════════════════════════════════════════════════════════════════════════
    // POOL STATE
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Pool of short[] buffers (PCM audio).
     */
    private final ArrayDeque<short[]> mShortBufferPool;

    /**
     * Pool of float[] buffers (normalized audio for ASR).
     */
    private final ArrayDeque<float[]> mFloatBufferPool;

    /**
     * Required size for short buffers.
     */
    private final int mShortBufferSize;

    /**
     * Required size for float buffers.
     */
    private final int mFloatBufferSize;

    /**
     * Maximum pool size.
     */
    private final int mMaxPoolSize;

    // ═══════════════════════════════════════════════════════════════════════════
    // STATISTICS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Total short buffers acquired.
     */
    private int mShortAcquireCount = 0;

    /**
     * Short buffers that required new allocation (pool miss).
     */
    private int mShortMissCount = 0;

    /**
     * Total float buffers acquired.
     */
    private int mFloatAcquireCount = 0;

    /**
     * Float buffers that required new allocation (pool miss).
     */
    private int mFloatMissCount = 0;

    // ═══════════════════════════════════════════════════════════════════════════
    // CONSTRUCTORS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Creates a new buffer pool with specified buffer sizes.
     *
     * <p>Buffers are pre-allocated during construction to avoid
     * allocation during recording.
     *
     * @param shortBufferSize Size of short[] buffers (PCM samples)
     * @param floatBufferSize Size of float[] buffers (normalized samples)
     */
    public AudioBufferPool(int shortBufferSize, int floatBufferSize) {
        this(shortBufferSize, floatBufferSize, DEFAULT_POOL_SIZE);
    }

    /**
     * Creates a new buffer pool with specified buffer sizes and pool size.
     *
     * @param shortBufferSize Size of short[] buffers (PCM samples)
     * @param floatBufferSize Size of float[] buffers (normalized samples)
     * @param poolSize        Number of buffers to pre-allocate
     */
    public AudioBufferPool(int shortBufferSize, int floatBufferSize, int poolSize) {
        mShortBufferSize = shortBufferSize;
        mFloatBufferSize = floatBufferSize;
        mMaxPoolSize = Math.min(poolSize, MAX_POOL_SIZE);

        mShortBufferPool = new ArrayDeque<>(mMaxPoolSize);
        mFloatBufferPool = new ArrayDeque<>(mMaxPoolSize);

        // WHY: Pre-allocate buffers to avoid allocation during recording
        preallocate();

        Log.i(TAG, "[AudioBufferPool] Created pool: shortSize=" + shortBufferSize
                + " floatSize=" + floatBufferSize + " poolSize=" + mMaxPoolSize);
    }

    /**
     * Creates a pool with default sizes for ARIA ASR pipeline.
     *
     * @return AudioBufferPool configured for 16kHz audio with 2s windows
     */
    @NonNull
    public static AudioBufferPool createDefault() {
        // WHY: 160 samples = 10ms at 16kHz (typical AudioRecord chunk)
        int shortBufferSize = 160;
        // WHY: 32000 samples = 2s window at 16kHz
        int floatBufferSize = Constants.WINDOW_DURATION_MS * Constants.AUDIO_SAMPLE_RATE_HZ / 1000;
        return new AudioBufferPool(shortBufferSize, floatBufferSize);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // BUFFER ACQUISITION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Acquires a short[] buffer from the pool.
     *
     * <p>If pool is empty, allocates a new buffer.
     * Buffer contents are not cleared — caller must overwrite.
     *
     * @return short[] buffer of size {@link #getShortBufferSize()}
     */
    @NonNull
    public synchronized short[] acquireShortBuffer() {
        mShortAcquireCount++;

        short[] buffer = mShortBufferPool.poll();
        if (buffer != null) {
            return buffer;
        }

        // WHY: Pool miss — allocate new buffer
        mShortMissCount++;
        return new short[mShortBufferSize];
    }

    /**
     * Releases a short[] buffer back to the pool.
     *
     * <p>Buffer will be reused by future acquireShortBuffer() calls.
     * Only accepts buffers of the correct size.
     *
     * @param buffer Buffer to release
     */
    public synchronized void releaseShortBuffer(@NonNull short[] buffer) {
        // WHY: Only accept correctly-sized buffers
        if (buffer.length != mShortBufferSize) {
            Log.w(TAG, "[AudioBufferPool.releaseShortBuffer] Wrong size: " + buffer.length
                    + " expected: " + mShortBufferSize);
            return;
        }

        // WHY: Don't exceed max pool size
        if (mShortBufferPool.size() < mMaxPoolSize) {
            mShortBufferPool.offer(buffer);
        }
    }

    /**
     * Acquires a float[] buffer from the pool.
     *
     * <p>If pool is empty, allocates a new buffer.
     * Buffer contents are not cleared — caller must overwrite.
     *
     * @return float[] buffer of size {@link #getFloatBufferSize()}
     */
    @NonNull
    public synchronized float[] acquireFloatBuffer() {
        mFloatAcquireCount++;

        float[] buffer = mFloatBufferPool.poll();
        if (buffer != null) {
            return buffer;
        }

        // WHY: Pool miss — allocate new buffer
        mFloatMissCount++;
        return new float[mFloatBufferSize];
    }

    /**
     * Releases a float[] buffer back to the pool.
     *
     * <p>Buffer will be reused by future acquireFloatBuffer() calls.
     * Only accepts buffers of the correct size.
     *
     * @param buffer Buffer to release
     */
    public synchronized void releaseFloatBuffer(@NonNull float[] buffer) {
        // WHY: Only accept correctly-sized buffers
        if (buffer.length != mFloatBufferSize) {
            Log.w(TAG, "[AudioBufferPool.releaseFloatBuffer] Wrong size: " + buffer.length
                    + " expected: " + mFloatBufferSize);
            return;
        }

        // WHY: Don't exceed max pool size
        if (mFloatBufferPool.size() < mMaxPoolSize) {
            mFloatBufferPool.offer(buffer);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // POOL MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Pre-allocates all pool buffers.
     *
     * <p>WHY: Allocate at construction time, not during recording.
     */
    private void preallocate() {
        for (int i = 0; i < mMaxPoolSize; i++) {
            mShortBufferPool.offer(new short[mShortBufferSize]);
            mFloatBufferPool.offer(new float[mFloatBufferSize]);
        }
    }

    /**
     * Clears all buffers from the pool.
     *
     * <p>Use when stopping recording to free memory.
     * Pool will reallocate on next acquire.
     */
    public synchronized void clear() {
        mShortBufferPool.clear();
        mFloatBufferPool.clear();
        Log.d(TAG, "[AudioBufferPool.clear] Pool cleared");
    }

    /**
     * Resets pool to initial pre-allocated state.
     *
     * <p>Clears existing buffers and pre-allocates fresh ones.
     * Also resets statistics.
     */
    public synchronized void reset() {
        clear();
        preallocate();
        resetStatistics();
        Log.d(TAG, "[AudioBufferPool.reset] Pool reset");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // STATISTICS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Returns the configured short buffer size.
     *
     * @return Size of short[] buffers in samples
     */
    public int getShortBufferSize() {
        return mShortBufferSize;
    }

    /**
     * Returns the configured float buffer size.
     *
     * @return Size of float[] buffers in samples
     */
    public int getFloatBufferSize() {
        return mFloatBufferSize;
    }

    /**
     * Returns current number of available short buffers.
     *
     * @return Available short buffer count
     */
    public synchronized int getAvailableShortBuffers() {
        return mShortBufferPool.size();
    }

    /**
     * Returns current number of available float buffers.
     *
     * @return Available float buffer count
     */
    public synchronized int getAvailableFloatBuffers() {
        return mFloatBufferPool.size();
    }

    /**
     * Returns pool hit rate for short buffers.
     *
     * <p>Higher is better — indicates fewer allocations during recording.
     *
     * @return Hit rate as percentage (0.0 to 1.0)
     */
    public synchronized float getShortBufferHitRate() {
        if (mShortAcquireCount == 0) {
            return 1.0f;
        }
        return 1.0f - ((float) mShortMissCount / mShortAcquireCount);
    }

    /**
     * Returns pool hit rate for float buffers.
     *
     * @return Hit rate as percentage (0.0 to 1.0)
     */
    public synchronized float getFloatBufferHitRate() {
        if (mFloatAcquireCount == 0) {
            return 1.0f;
        }
        return 1.0f - ((float) mFloatMissCount / mFloatAcquireCount);
    }

    /**
     * Returns total short buffer acquisitions.
     *
     * @return Total acquire calls
     */
    public synchronized int getShortAcquireCount() {
        return mShortAcquireCount;
    }

    /**
     * Returns total float buffer acquisitions.
     *
     * @return Total acquire calls
     */
    public synchronized int getFloatAcquireCount() {
        return mFloatAcquireCount;
    }

    /**
     * Resets all statistics counters.
     */
    public synchronized void resetStatistics() {
        mShortAcquireCount = 0;
        mShortMissCount = 0;
        mFloatAcquireCount = 0;
        mFloatMissCount = 0;
    }

    /**
     * Logs pool statistics.
     */
    public synchronized void logStatistics() {
        Log.i(TAG, "[AudioBufferPool.logStatistics]"
                + " shortAcquire=" + mShortAcquireCount
                + " shortMiss=" + mShortMissCount
                + " shortHitRate=" + String.format("%.1f%%", getShortBufferHitRate() * 100)
                + " floatAcquire=" + mFloatAcquireCount
                + " floatMiss=" + mFloatMissCount
                + " floatHitRate=" + String.format("%.1f%%", getFloatBufferHitRate() * 100));
    }
}
