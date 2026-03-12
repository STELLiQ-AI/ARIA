/**
 * WaveformView.java
 *
 * Custom View that renders audio waveform visualization during recording.
 * Displays animated amplitude bars using ARIA brand colors.
 *
 * <p>Responsibility:
 * <ul>
 *   <li>Render real-time audio amplitude as vertical bars</li>
 *   <li>Maintain scrolling history of recent amplitudes</li>
 *   <li>Animate smoothly at 60fps during recording</li>
 *   <li>Use ARIA orange accent color for visual consistency</li>
 * </ul>
 *
 * <p>Architecture Position:
 * UI layer custom view. Used in SessionFragment to provide visual feedback
 * during audio recording. Receives amplitude updates from audio pipeline.
 *
 * <p>Thread Safety:
 * Amplitude updates can be posted from any thread via {@link #addAmplitude(float)}.
 * Drawing always occurs on main thread.
 *
 * <p>PERF: Uses hardware acceleration compatible drawing operations.
 * Amplitude history is capped to prevent unbounded memory growth.
 *
 * @author STELLiQ Engineering
 * @version 0.1.0
 * @since ARIA Demo Build — 2026-03-04
 */
package com.stelliq.aria.ui.view;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;

import java.util.Arrays;

/**
 * Custom View that renders audio waveform visualization.
 *
 * <p>WHY: Visual feedback during recording improves user experience and
 * confirms that audio is being captured correctly. The waveform also
 * helps users identify when they're speaking too quietly or too loudly.
 */
public class WaveformView extends View {

    // ═══════════════════════════════════════════════════════════════════════════
    // CONSTANTS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Default ARIA orange accent color.
     */
    private static final int DEFAULT_BAR_COLOR = 0xFFFF9100;

    /**
     * Default dark blue background hint color.
     */
    private static final int DEFAULT_BACKGROUND_COLOR = 0xFF1A3E5C;

    /**
     * Default number of bars to display.
     */
    private static final int DEFAULT_BAR_COUNT = 50;

    /**
     * Default bar width in dp.
     */
    private static final float DEFAULT_BAR_WIDTH_DP = 4f;

    /**
     * Default gap between bars in dp.
     */
    private static final float DEFAULT_BAR_GAP_DP = 2f;

    /**
     * Default corner radius for bars in dp.
     */
    private static final float DEFAULT_CORNER_RADIUS_DP = 2f;

    /**
     * Minimum bar height as fraction of view height.
     */
    private static final float MIN_BAR_HEIGHT_FRACTION = 0.05f;

    /**
     * Maximum amplitude history size to prevent unbounded memory.
     */
    private static final int MAX_AMPLITUDE_HISTORY = 500;

    /**
     * Smoothing factor for amplitude changes (0 = no smoothing, 1 = no change).
     */
    private static final float AMPLITUDE_SMOOTHING = 0.3f;

    // ═══════════════════════════════════════════════════════════════════════════
    // DRAWING STATE
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Paint for drawing amplitude bars.
     */
    private final Paint mBarPaint;

    /**
     * Paint for drawing inactive/background bars.
     */
    private final Paint mBackgroundBarPaint;

    /**
     * Reusable rect for drawing bars.
     */
    private final RectF mBarRect;

    /**
     * Amplitude history buffer.
     * WHY: Circular buffer allows efficient scrolling visualization.
     */
    private final float[] mAmplitudes;

    /**
     * Current write position in amplitude buffer.
     */
    private int mAmplitudeIndex = 0;

    /**
     * Total amplitudes received (used to determine buffer fill state).
     */
    private int mAmplitudeCount = 0;

    // ═══════════════════════════════════════════════════════════════════════════
    // CONFIGURATION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Number of bars to display.
     */
    private int mBarCount;

    /**
     * Width of each bar in pixels.
     */
    private float mBarWidthPx;

    /**
     * Gap between bars in pixels.
     */
    private float mBarGapPx;

    /**
     * Corner radius for rounded bars in pixels.
     */
    private float mCornerRadiusPx;

    /**
     * Primary bar color (ARIA orange).
     */
    @ColorInt
    private int mBarColor;

    /**
     * Background bar color.
     */
    @ColorInt
    private int mBackgroundBarColor;

    /**
     * Whether to use gradient for bars.
     */
    private boolean mUseGradient = true;

    /**
     * Whether waveform is currently active (recording).
     */
    private boolean mIsActive = false;

    /**
     * Last smoothed amplitude value for smooth transitions.
     */
    private float mLastSmoothedAmplitude = 0f;

    // ═══════════════════════════════════════════════════════════════════════════
    // CONSTRUCTORS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Creates a new WaveformView.
     *
     * @param context The Context the view is running in
     */
    public WaveformView(@NonNull Context context) {
        this(context, null);
    }

    /**
     * Creates a new WaveformView with attributes.
     *
     * @param context The Context the view is running in
     * @param attrs   The attributes of the XML tag
     */
    public WaveformView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    /**
     * Creates a new WaveformView with attributes and default style.
     *
     * @param context      The Context the view is running in
     * @param attrs        The attributes of the XML tag
     * @param defStyleAttr Default style attribute
     */
    public WaveformView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        // WHY: Initialize with defaults, then override from XML attributes
        float density = context.getResources().getDisplayMetrics().density;
        mBarCount = DEFAULT_BAR_COUNT;
        mBarWidthPx = DEFAULT_BAR_WIDTH_DP * density;
        mBarGapPx = DEFAULT_BAR_GAP_DP * density;
        mCornerRadiusPx = DEFAULT_CORNER_RADIUS_DP * density;
        mBarColor = DEFAULT_BAR_COLOR;
        mBackgroundBarColor = adjustAlpha(DEFAULT_BAR_COLOR, 0.2f);

        // WHY: Pre-allocate buffers
        mAmplitudes = new float[MAX_AMPLITUDE_HISTORY];
        mBarRect = new RectF();

        // WHY: Configure paints for efficient drawing
        mBarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mBarPaint.setStyle(Paint.Style.FILL);
        mBarPaint.setColor(mBarColor);

        mBackgroundBarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mBackgroundBarPaint.setStyle(Paint.Style.FILL);
        mBackgroundBarPaint.setColor(mBackgroundBarColor);

        // TODO: Parse custom XML attributes if we add them to attrs.xml
        // parseAttributes(context, attrs);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Adds an amplitude sample to the waveform visualization.
     *
     * <p>Thread-safe. Can be called from any thread.
     *
     * @param amplitude Normalized amplitude value (0.0 to 1.0)
     */
    public void addAmplitude(float amplitude) {
        // WHY: Clamp to valid range
        float clamped = Math.max(0f, Math.min(1f, amplitude));

        // WHY: Apply smoothing for visual appeal
        float smoothed = mLastSmoothedAmplitude + AMPLITUDE_SMOOTHING * (clamped - mLastSmoothedAmplitude);
        mLastSmoothedAmplitude = smoothed;

        // WHY: Thread-safe update of circular buffer
        synchronized (mAmplitudes) {
            mAmplitudes[mAmplitudeIndex] = smoothed;
            mAmplitudeIndex = (mAmplitudeIndex + 1) % MAX_AMPLITUDE_HISTORY;
            mAmplitudeCount++;
        }

        // WHY: Request redraw on UI thread
        postInvalidate();
    }

    /**
     * Clears all amplitude history.
     *
     * <p>Thread-safe. Can be called from any thread.
     */
    public void clear() {
        synchronized (mAmplitudes) {
            Arrays.fill(mAmplitudes, 0f);
            mAmplitudeIndex = 0;
            mAmplitudeCount = 0;
            mLastSmoothedAmplitude = 0f;
        }
        postInvalidate();
    }

    /**
     * Sets whether the waveform is active (recording).
     *
     * <p>When inactive, displays muted background bars.
     *
     * @param active true if recording is active
     */
    @UiThread
    public void setActive(boolean active) {
        if (mIsActive != active) {
            mIsActive = active;
            if (!active) {
                // WHY: Reset smoothing when stopping
                mLastSmoothedAmplitude = 0f;
            }
            invalidate();
        }
    }

    /**
     * Returns whether the waveform is currently active.
     *
     * @return true if active
     */
    public boolean isActive() {
        return mIsActive;
    }

    /**
     * Sets the primary bar color.
     *
     * @param color Bar color
     */
    @UiThread
    public void setBarColor(@ColorInt int color) {
        mBarColor = color;
        mBarPaint.setColor(color);
        mBackgroundBarColor = adjustAlpha(color, 0.2f);
        mBackgroundBarPaint.setColor(mBackgroundBarColor);
        invalidate();
    }

    /**
     * Returns the current bar color.
     *
     * @return Bar color
     */
    @ColorInt
    public int getBarColor() {
        return mBarColor;
    }

    /**
     * Sets the number of bars to display.
     *
     * @param count Number of bars
     */
    @UiThread
    public void setBarCount(int count) {
        mBarCount = Math.max(1, count);
        invalidate();
    }

    /**
     * Returns the number of bars displayed.
     *
     * @return Bar count
     */
    public int getBarCount() {
        return mBarCount;
    }

    /**
     * Sets whether to use gradient coloring for bars.
     *
     * @param useGradient true to use gradient
     */
    @UiThread
    public void setUseGradient(boolean useGradient) {
        mUseGradient = useGradient;
        invalidate();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DRAWING
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);

        int width = getWidth();
        int height = getHeight();
        if (width == 0 || height == 0) {
            return;
        }

        // WHY: Calculate bar dimensions based on view size
        float totalBarWidth = mBarWidthPx + mBarGapPx;
        int barsToShow = Math.min(mBarCount, (int) (width / totalBarWidth));

        // WHY: Center bars horizontally
        float totalWidth = barsToShow * totalBarWidth - mBarGapPx;
        float startX = (width - totalWidth) / 2f;

        float centerY = height / 2f;
        float maxBarHeight = height * 0.9f;  // 90% of view height
        float minBarHeight = height * MIN_BAR_HEIGHT_FRACTION;

        // WHY: Setup gradient if enabled
        if (mUseGradient && mIsActive) {
            LinearGradient gradient = new LinearGradient(
                    0, 0, 0, height,
                    mBarColor,
                    adjustAlpha(mBarColor, 0.6f),
                    Shader.TileMode.CLAMP
            );
            mBarPaint.setShader(gradient);
        } else {
            mBarPaint.setShader(null);
        }

        // WHY: Draw bars from amplitude history
        synchronized (mAmplitudes) {
            for (int i = 0; i < barsToShow; i++) {
                float x = startX + i * totalBarWidth;

                // WHY: Read from circular buffer, most recent on right
                int amplitudeIdx;
                if (mAmplitudeCount < barsToShow) {
                    // Buffer not full yet — use sequential order
                    amplitudeIdx = i;
                } else {
                    // Buffer full — read backwards from current position
                    int offset = barsToShow - 1 - i;
                    amplitudeIdx = (mAmplitudeIndex - 1 - offset + MAX_AMPLITUDE_HISTORY) % MAX_AMPLITUDE_HISTORY;
                }

                float amplitude = mAmplitudes[amplitudeIdx];

                // WHY: Calculate bar height based on amplitude
                float barHeight;
                if (mIsActive && amplitude > 0) {
                    barHeight = minBarHeight + amplitude * (maxBarHeight - minBarHeight);
                } else {
                    // WHY: Inactive state shows minimal bars
                    barHeight = minBarHeight;
                }

                // WHY: Draw bar centered vertically
                float top = centerY - barHeight / 2f;
                float bottom = centerY + barHeight / 2f;
                mBarRect.set(x, top, x + mBarWidthPx, bottom);

                // WHY: Use appropriate paint based on state
                Paint paint = (mIsActive && amplitude > 0) ? mBarPaint : mBackgroundBarPaint;
                canvas.drawRoundRect(mBarRect, mCornerRadiusPx, mCornerRadiusPx, paint);
            }
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // WHY: Recalculate bar layout when size changes
        invalidate();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // UTILITY
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Adjusts the alpha of a color.
     *
     * @param color Original color
     * @param alpha New alpha (0.0 to 1.0)
     * @return Color with adjusted alpha
     */
    @ColorInt
    private static int adjustAlpha(@ColorInt int color, float alpha) {
        int alphaInt = Math.round(alpha * 255);
        return Color.argb(
                alphaInt,
                Color.red(color),
                Color.green(color),
                Color.blue(color)
        );
    }

    /**
     * Calculates RMS amplitude from PCM samples.
     *
     * <p>Utility method for converting raw audio to normalized amplitude.
     *
     * @param samples PCM samples (16-bit range: -32768 to 32767)
     * @param count   Number of samples to process
     * @return Normalized amplitude (0.0 to 1.0)
     */
    public static float calculateRmsAmplitude(@NonNull short[] samples, int count) {
        if (count <= 0) {
            return 0f;
        }

        // WHY: Calculate RMS (root mean square) for accurate amplitude
        long sum = 0;
        int actualCount = Math.min(count, samples.length);
        for (int i = 0; i < actualCount; i++) {
            sum += (long) samples[i] * samples[i];
        }

        double rms = Math.sqrt((double) sum / actualCount);

        // WHY: Normalize to 0-1 range (32768 is max for 16-bit audio)
        // Apply slight scaling to make visualization more dynamic
        float normalized = (float) (rms / 32768.0);

        // WHY: Apply log scaling for better visual representation
        // Human hearing is logarithmic, so linear amplitude looks flat
        if (normalized > 0) {
            normalized = (float) (Math.log10(1 + normalized * 9) / Math.log10(10));
        }

        return Math.min(1f, normalized);
    }

    /**
     * Calculates RMS amplitude from float samples.
     *
     * @param samples Float samples (expected range: -1.0 to 1.0)
     * @param count   Number of samples to process
     * @return Normalized amplitude (0.0 to 1.0)
     */
    public static float calculateRmsAmplitude(@NonNull float[] samples, int count) {
        if (count <= 0) {
            return 0f;
        }

        // WHY: Calculate RMS for float samples
        double sum = 0;
        int actualCount = Math.min(count, samples.length);
        for (int i = 0; i < actualCount; i++) {
            sum += samples[i] * samples[i];
        }

        double rms = Math.sqrt(sum / actualCount);

        // WHY: Apply log scaling for better visual representation
        float normalized = (float) rms;
        if (normalized > 0) {
            normalized = (float) (Math.log10(1 + normalized * 9) / Math.log10(10));
        }

        return Math.min(1f, normalized);
    }
}
