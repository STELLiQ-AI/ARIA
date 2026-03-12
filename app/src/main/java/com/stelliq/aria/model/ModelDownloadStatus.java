/**
 * ModelDownloadStatus.java
 *
 * Represents the current state of the Llama 3.1 8B LLM download process.
 * Provides status information, progress metrics, and user-friendly display strings.
 *
 * <p>Responsibility:
 * <ul>
 *   <li>Define all possible download states (NOT_STARTED, QUEUED, DOWNLOADING, etc.)</li>
 *   <li>Track download progress (bytes downloaded, total bytes, speed)</li>
 *   <li>Calculate ETA and format human-readable strings</li>
 *   <li>This class does NOT perform downloads — that's ModelFileManager's job</li>
 * </ul>
 *
 * <p>Architecture Position:
 * Model layer data class. Created/updated by ModelFileManager, observed by ModelDownloadFragment.
 *
 * <p>Thread Safety:
 * Immutable after construction — fully thread-safe.
 *
 * <p>Air-Gap Compliance:
 * Tracks the one-time LLM download on first launch. After download completes,
 * this status should remain COMPLETE and no further network activity occurs.
 *
 * @author STELLiQ Engineering
 * @version 0.1.0
 * @since ARIA Demo Build — 2026-03-02
 */
package com.stelliq.aria.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.stelliq.aria.util.Constants;

import java.util.Locale;

public final class ModelDownloadStatus {

    /**
     * Download state enumeration.
     */
    public enum State {
        /** LLM model not present and download not started */
        NOT_STARTED,
        /** Download request queued with DownloadManager */
        QUEUED,
        /** Download actively in progress */
        DOWNLOADING,
        /** Download paused (waiting for WiFi, user paused, etc.) */
        PAUSED,
        /** Download completed successfully, file verified */
        COMPLETE,
        /** Download failed — see error message */
        FAILED
    }

    @NonNull
    private final State mState;

    private final long mBytesDownloaded;
    private final long mTotalBytes;
    private final long mBytesPerSecond;
    private final long mDownloadId;

    @Nullable
    private final String mErrorMessage;

    @Nullable
    private final String mPauseReason;

    /**
     * Private constructor — use static factory methods.
     */
    private ModelDownloadStatus(@NonNull State state, long bytesDownloaded, long totalBytes,
                                 long bytesPerSecond, long downloadId,
                                 @Nullable String errorMessage, @Nullable String pauseReason) {
        mState = state;
        mBytesDownloaded = bytesDownloaded;
        mTotalBytes = totalBytes;
        mBytesPerSecond = bytesPerSecond;
        mDownloadId = downloadId;
        mErrorMessage = errorMessage;
        mPauseReason = pauseReason;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // STATIC FACTORY METHODS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Creates a NOT_STARTED status.
     */
    @NonNull
    public static ModelDownloadStatus notStarted() {
        return new ModelDownloadStatus(State.NOT_STARTED, 0, 0, 0, -1, null, null);
    }

    /**
     * Creates a QUEUED status.
     *
     * @param downloadId The DownloadManager download ID
     */
    @NonNull
    public static ModelDownloadStatus queued(long downloadId) {
        return new ModelDownloadStatus(State.QUEUED, 0, Constants.MODEL_LLM_EXPECTED_BYTES,
                0, downloadId, null, null);
    }

    /**
     * Creates a DOWNLOADING status with progress.
     *
     * @param bytesDownloaded Bytes downloaded so far
     * @param totalBytes      Total file size
     * @param bytesPerSecond  Current download speed
     * @param downloadId      The DownloadManager download ID
     */
    @NonNull
    public static ModelDownloadStatus downloading(long bytesDownloaded, long totalBytes,
                                                   long bytesPerSecond, long downloadId) {
        return new ModelDownloadStatus(State.DOWNLOADING, bytesDownloaded, totalBytes,
                bytesPerSecond, downloadId, null, null);
    }

    /**
     * Creates a PAUSED status.
     *
     * @param bytesDownloaded Bytes downloaded before pause
     * @param totalBytes      Total file size
     * @param downloadId      The DownloadManager download ID
     * @param pauseReason     Human-readable reason for pause
     */
    @NonNull
    public static ModelDownloadStatus paused(long bytesDownloaded, long totalBytes,
                                              long downloadId, @NonNull String pauseReason) {
        return new ModelDownloadStatus(State.PAUSED, bytesDownloaded, totalBytes,
                0, downloadId, null, pauseReason);
    }

    /**
     * Creates a COMPLETE status.
     */
    @NonNull
    public static ModelDownloadStatus complete() {
        return new ModelDownloadStatus(State.COMPLETE, Constants.MODEL_LLM_EXPECTED_BYTES,
                Constants.MODEL_LLM_EXPECTED_BYTES, 0, -1, null, null);
    }

    /**
     * Creates a FAILED status.
     *
     * @param errorMessage Description of what went wrong
     */
    @NonNull
    public static ModelDownloadStatus failed(@NonNull String errorMessage) {
        return new ModelDownloadStatus(State.FAILED, 0, 0, 0, -1, errorMessage, null);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GETTERS
    // ═══════════════════════════════════════════════════════════════════════════

    @NonNull
    public State getState() {
        return mState;
    }

    public long getBytesDownloaded() {
        return mBytesDownloaded;
    }

    public long getTotalBytes() {
        return mTotalBytes;
    }

    public long getBytesPerSecond() {
        return mBytesPerSecond;
    }

    public long getDownloadId() {
        return mDownloadId;
    }

    @Nullable
    public String getErrorMessage() {
        return mErrorMessage;
    }

    @Nullable
    public String getPauseReason() {
        return mPauseReason;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CALCULATED PROPERTIES
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Returns download progress as a percentage (0-100).
     *
     * @return Progress percentage, or 0 if total bytes unknown
     */
    public int getProgressPercent() {
        if (mTotalBytes <= 0) {
            return 0;
        }
        return (int) ((mBytesDownloaded * 100) / mTotalBytes);
    }

    /**
     * Returns estimated time remaining in seconds.
     *
     * @return ETA in seconds, or -1 if cannot be calculated
     */
    public long getEtaSeconds() {
        if (mBytesPerSecond <= 0 || mTotalBytes <= mBytesDownloaded) {
            return -1;
        }
        return (mTotalBytes - mBytesDownloaded) / mBytesPerSecond;
    }

    /**
     * Returns a human-readable ETA string (e.g., "~4 min remaining").
     *
     * @return Formatted ETA string, or empty string if unknown
     */
    @NonNull
    public String getEtaString() {
        long etaSeconds = getEtaSeconds();
        if (etaSeconds < 0) {
            return "";
        }

        if (etaSeconds < 60) {
            return String.format(Locale.US, "~%d sec remaining", etaSeconds);
        } else if (etaSeconds < 3600) {
            long minutes = etaSeconds / 60;
            return String.format(Locale.US, "~%d min remaining", minutes);
        } else {
            long hours = etaSeconds / 3600;
            long minutes = (etaSeconds % 3600) / 60;
            return String.format(Locale.US, "~%dh %dm remaining", hours, minutes);
        }
    }

    /**
     * Returns a human-readable download speed string (e.g., "4.2 MB/s").
     *
     * @return Formatted speed string
     */
    @NonNull
    public String getSpeedString() {
        if (mBytesPerSecond <= 0) {
            return "";
        }

        double mbps = mBytesPerSecond / (1024.0 * 1024.0);
        if (mbps >= 1.0) {
            return String.format(Locale.US, "%.1f MB/s", mbps);
        } else {
            double kbps = mBytesPerSecond / 1024.0;
            return String.format(Locale.US, "%.0f KB/s", kbps);
        }
    }

    /**
     * Returns a human-readable progress string (e.g., "1.2 GB / 2.3 GB").
     *
     * @return Formatted progress string
     */
    @NonNull
    public String getProgressString() {
        return String.format(Locale.US, "%s / %s",
                formatBytes(mBytesDownloaded),
                formatBytes(mTotalBytes));
    }

    /**
     * Returns a complete status line for UI display.
     * Example: "1.2 GB / 2.3 GB  ·  52%  ·  4.2 MB/s  ·  ~4 min remaining"
     *
     * @return Formatted status line
     */
    @NonNull
    public String getStatusLine() {
        switch (mState) {
            case NOT_STARTED:
                return "Ready to download";

            case QUEUED:
                return "Preparing download...";

            case DOWNLOADING:
                StringBuilder sb = new StringBuilder();
                sb.append(getProgressString());
                sb.append("  ·  ").append(getProgressPercent()).append("%");
                String speed = getSpeedString();
                if (!speed.isEmpty()) {
                    sb.append("  ·  ").append(speed);
                }
                String eta = getEtaString();
                if (!eta.isEmpty()) {
                    sb.append("  ·  ").append(eta);
                }
                return sb.toString();

            case PAUSED:
                String reason = mPauseReason != null ? mPauseReason : "Paused";
                return reason + " (" + getProgressPercent() + "% complete)";

            case COMPLETE:
                return "Download complete";

            case FAILED:
                return mErrorMessage != null ? "Failed: " + mErrorMessage : "Download failed";

            default:
                return "";
        }
    }

    /**
     * Formats bytes into human-readable string (KB, MB, GB).
     */
    @NonNull
    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
        } else {
            return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        }
    }

    /**
     * Checks if the download is in a state that allows user interaction (retry, cancel).
     *
     * @return True if user can take action
     */
    public boolean isActionable() {
        return mState == State.NOT_STARTED
                || mState == State.PAUSED
                || mState == State.FAILED;
    }

    /**
     * Checks if the download is actively in progress.
     *
     * @return True if downloading or queued
     */
    public boolean isInProgress() {
        return mState == State.QUEUED || mState == State.DOWNLOADING;
    }
}
