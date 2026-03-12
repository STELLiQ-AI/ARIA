/**
 * ModelFileManager.java
 *
 * Manages ML model file operations: asset extraction, download initiation,
 * progress tracking, and file integrity verification.
 *
 * <p>Responsibility:
 * <ul>
 *   <li>Copy whisper model from APK assets to internal filesDir on first launch</li>
 *   <li>Verify model file integrity using expected byte sizes</li>
 *   <li>Manage LLM download via Android DownloadManager</li>
 *   <li>Query download progress for UI display</li>
 *   <li>Provide absolute file paths for native engine initialization</li>
 *   <li>This class does NOT load models — that's the engines' job</li>
 * </ul>
 *
 * <p>Architecture Position:
 * Utility layer. Called by ARIASessionService during initialization and by
 * ModelDownloadFragment for download management.
 *
 * <p>Thread Safety:
 * Asset copy should be called from background thread (I/O bound).
 * Download methods can be called from any thread.
 *
 * <p>Air-Gap Compliance:
 * LLM download is the ONLY network operation in the app. Once downloaded,
 * no further network calls occur. Whisper model is bundled in APK.
 *
 * @author STELLiQ Engineering
 * @version 0.1.0
 * @since ARIA Demo Build — 2026-03-02
 */
package com.stelliq.aria.util;

import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.stelliq.aria.model.ModelDownloadStatus;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class ModelFileManager {

    private static final String TAG = Constants.LOG_TAG_MODEL;
    private static final String PREFS_NAME = "aria_model_prefs";
    private static final int COPY_BUFFER_SIZE = 8192;

    // WHY: Retry configuration for failed downloads
    private static final int MAX_DOWNLOAD_RETRIES = 3;
    private static final long INITIAL_RETRY_DELAY_MS = 1000;
    private static final long MAX_RETRY_DELAY_MS = 30000;

    // WHY: Private constructor — utility class
    private ModelFileManager() {
        throw new AssertionError("ModelFileManager is a non-instantiable utility class");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // WHISPER MODEL — BUNDLED IN APK ASSETS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Copies whisper model from APK assets to internal filesDir if not already present.
     *
     * <p>This is a one-time operation on first launch. Subsequent launches detect
     * the existing file and return immediately.
     *
     * <p>RISK: Must be called from background thread — asset copy is I/O bound (~1-2 seconds).
     *
     * @param context Application context
     * @return True if model is ready (either already existed or successfully copied)
     */
    @WorkerThread
    public static boolean copyWhisperModelIfNeeded(@NonNull Context context) {
        File modelDir = new File(context.getFilesDir(), Constants.MODEL_WHISPER_DIR);
        File modelFile = new File(modelDir, Constants.MODEL_WHISPER_FILENAME);

        // Check if already copied and verified
        if (modelFile.exists() && modelFile.length() == Constants.MODEL_WHISPER_EXPECTED_BYTES) {
            Log.d(TAG, "[ModelFileManager.copyWhisperModelIfNeeded] Model already present: " + modelFile.getAbsolutePath());
            return true;
        }

        // Create directory if needed
        if (!modelDir.exists() && !modelDir.mkdirs()) {
            Log.e(TAG, "[ModelFileManager.copyWhisperModelIfNeeded] Failed to create model directory: " + modelDir);
            return false;
        }

        // Copy from assets
        Log.i(TAG, "[ModelFileManager.copyWhisperModelIfNeeded] Copying whisper model from assets...");
        long startTime = System.currentTimeMillis();

        try (InputStream in = context.getAssets().open(Constants.MODEL_WHISPER_ASSET_PATH);
             OutputStream out = new FileOutputStream(modelFile)) {

            byte[] buffer = new byte[COPY_BUFFER_SIZE];
            int bytesRead;
            long totalBytes = 0;

            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                totalBytes += bytesRead;
            }

            out.flush();

            long duration = System.currentTimeMillis() - startTime;
            Log.i(TAG, "[ModelFileManager.copyWhisperModelIfNeeded] Copy complete: "
                    + totalBytes + " bytes in " + duration + "ms");

            // Verify size
            if (modelFile.length() != Constants.MODEL_WHISPER_EXPECTED_BYTES) {
                Log.e(TAG, "[ModelFileManager.copyWhisperModelIfNeeded] Size mismatch! Expected: "
                        + Constants.MODEL_WHISPER_EXPECTED_BYTES + ", Got: " + modelFile.length());
                // WHY: Delete corrupted file so next launch retries
                modelFile.delete();
                return false;
            }

            return true;

        } catch (IOException e) {
            Log.e(TAG, "[ModelFileManager.copyWhisperModelIfNeeded] Copy failed: " + e.getMessage(), e);
            // WHY: Delete partial file
            if (modelFile.exists()) {
                modelFile.delete();
            }
            return false;
        }
    }

    /**
     * Checks if whisper model is ready for use.
     *
     * @param context Application context
     * @return True if model file exists and has correct size
     */
    public static boolean isWhisperModelReady(@NonNull Context context) {
        File modelFile = new File(context.getFilesDir(),
                Constants.MODEL_WHISPER_DIR + File.separator + Constants.MODEL_WHISPER_FILENAME);
        return modelFile.exists() && modelFile.length() == Constants.MODEL_WHISPER_EXPECTED_BYTES;
    }

    /**
     * Returns absolute path to whisper model for native engine.
     *
     * @param context Application context
     * @return Absolute path, or null if model not ready
     */
    @Nullable
    public static String getWhisperModelPath(@NonNull Context context) {
        File modelFile = new File(context.getFilesDir(),
                Constants.MODEL_WHISPER_DIR + File.separator + Constants.MODEL_WHISPER_FILENAME);
        if (modelFile.exists()) {
            return modelFile.getAbsolutePath();
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // QNN CONTEXT BINARY — BUNDLED IN assets/models/
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Copies QNN context binary from APK assets to filesDir if not already present.
     * WHY (QD-7): Native QNN code needs an absolute filesystem path — cannot read
     * from APK ZIP directly. Context binary must be extracted on first run.
     *
     * @param context Application context
     * @return True if context binary is ready (already existed or successfully copied)
     */
    @WorkerThread
    public static boolean copyQnnContextIfNeeded(@NonNull Context context) {
        File modelDir = new File(context.getFilesDir(), Constants.MODEL_WHISPER_DIR);
        File contextFile = new File(modelDir, Constants.WHISPER_QNN_CONTEXT_FILENAME);

        if (contextFile.exists() && contextFile.length() > 0) {
            Log.d(TAG, "[ModelFileManager.copyQnnContextIfNeeded] Context binary already present: "
                    + contextFile.getAbsolutePath());
            return true;
        }

        if (!modelDir.exists() && !modelDir.mkdirs()) {
            Log.e(TAG, "[ModelFileManager.copyQnnContextIfNeeded] Failed to create model directory: " + modelDir);
            return false;
        }

        Log.i(TAG, "[ModelFileManager.copyQnnContextIfNeeded] Copying QNN context binary from assets...");
        long startTime = System.currentTimeMillis();

        try (InputStream in = context.getAssets().open(Constants.WHISPER_QNN_CONTEXT_ASSET_PATH);
             OutputStream out = new FileOutputStream(contextFile)) {

            byte[] buffer = new byte[COPY_BUFFER_SIZE];
            int bytesRead;
            long totalBytes = 0;

            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                totalBytes += bytesRead;
            }

            out.flush();

            long duration = System.currentTimeMillis() - startTime;
            Log.i(TAG, "[ModelFileManager.copyQnnContextIfNeeded] Copy complete: "
                    + totalBytes + " bytes in " + duration + "ms");
            return true;

        } catch (IOException e) {
            // WHY: Context binary may not exist in assets yet (pending AI Hub compile).
            // Log as warning, not error — app falls back to CPU-only inference.
            Log.w(TAG, "[ModelFileManager.copyQnnContextIfNeeded] Context binary not found in assets: "
                    + e.getMessage());
            if (contextFile.exists()) {
                contextFile.delete();
            }
            return false;
        }
    }

    /**
     * Returns absolute path to QNN context binary, or null if not available.
     * WHY: Extracts from assets on first call, then returns cached filesystem path.
     *
     * @param context Application context
     * @return Absolute path to context binary, or null if not available
     */
    @Nullable
    public static String getQnnContextPath(@NonNull Context context) {
        copyQnnContextIfNeeded(context);
        File contextFile = new File(context.getFilesDir(),
                Constants.MODEL_WHISPER_DIR + File.separator + Constants.WHISPER_QNN_CONTEXT_FILENAME);
        if (contextFile.exists() && contextFile.length() > 0) {
            return contextFile.getAbsolutePath();
        }
        // WHY: Return null triggers CPU-only fallback in WhisperEngine.loadModel
        Log.w(TAG, "[ModelFileManager.getQnnContextPath] QNN context binary not available — CPU fallback");
        return null;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LLM MODEL — DOWNLOADED VIA DOWNLOADMANAGER
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Checks if LLM model is downloaded and ready for use.
     * Validates file exists, has correct size, and is valid GGUF format.
     *
     * @param context Application context
     * @return True if model file exists, has correct size, and is valid GGUF
     */
    public static boolean isLlmModelReady(@NonNull Context context) {
        File modelFile = getLlmModelFile(context);
        if (modelFile == null) {
            return false;
        }
        if (!modelFile.exists()) {
            return false;
        }
        if (modelFile.length() != Constants.MODEL_LLM_EXPECTED_BYTES) {
            Log.w(TAG, "[ModelFileManager.isLlmModelReady] Size mismatch. Expected: "
                    + Constants.MODEL_LLM_EXPECTED_BYTES + ", Actual: " + modelFile.length());
            return false;
        }
        // Validate GGUF magic number to prevent crash on corrupted files
        if (!isValidGgufFile(modelFile)) {
            Log.e(TAG, "[ModelFileManager.isLlmModelReady] Invalid GGUF format - file may be corrupted");
            return false;
        }
        return true;
    }

    /**
     * Validates that a file is a valid GGUF format by checking magic number.
     * GGUF files start with "GGUF" (0x46475547 in little-endian).
     *
     * @param file File to validate
     * @return True if file starts with valid GGUF magic number
     */
    private static boolean isValidGgufFile(@NonNull File file) {
        if (!file.exists() || file.length() < 4) {
            return false;
        }
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] magic = new byte[4];
            int bytesRead = fis.read(magic);
            if (bytesRead != 4) {
                return false;
            }
            // GGUF magic: 'G' 'G' 'U' 'F' (0x47 0x47 0x55 0x46)
            boolean isValid = magic[0] == 'G' && magic[1] == 'G' && magic[2] == 'U' && magic[3] == 'F';
            if (!isValid) {
                Log.w(TAG, "[ModelFileManager.isValidGgufFile] Magic bytes: "
                        + String.format("%02X %02X %02X %02X", magic[0], magic[1], magic[2], magic[3]));
            }
            return isValid;
        } catch (IOException e) {
            Log.e(TAG, "[ModelFileManager.isValidGgufFile] Error reading file: " + e.getMessage());
            return false;
        }
    }

    /**
     * Returns absolute path to LLM model for native engine.
     *
     * @param context Application context
     * @return Absolute path, or null if model not ready
     */
    @Nullable
    public static String getLlmModelPath(@NonNull Context context) {
        File modelFile = getLlmModelFile(context);
        if (modelFile != null && modelFile.exists()) {
            return modelFile.getAbsolutePath();
        }
        return null;
    }

    /**
     * Returns the LLM model file object.
     */
    @Nullable
    private static File getLlmModelFile(@NonNull Context context) {
        File externalDir = context.getExternalFilesDir(Constants.MODEL_LLM_DIR);
        if (externalDir == null) {
            Log.e(TAG, "[ModelFileManager.getLlmModelFile] External files dir is null");
            return null;
        }
        return new File(externalDir, Constants.MODEL_LLM_FILENAME);
    }

    /**
     * Starts LLM model download via Android DownloadManager.
     *
     * <p>WHY DownloadManager:
     * <ul>
     *   <li>Runs as system service — survives app death</li>
     *   <li>Automatic resume on network drop</li>
     *   <li>System tray notification with progress</li>
     *   <li>Respects Doze mode and battery optimization</li>
     * </ul>
     *
     * <p>RISK: Do not call if download already in progress — check getDownloadStatus() first.
     *
     * @param context Application context
     * @return Download ID for progress tracking, or -1 on failure
     */
    public static long startLlmDownload(@NonNull Context context) {
        // Check for existing download in progress
        long existingId = getStoredDownloadId(context);
        if (existingId != -1) {
            ModelDownloadStatus status = getDownloadStatus(context, existingId);
            if (status.isInProgress()) {
                Log.w(TAG, "[ModelFileManager.startLlmDownload] Download already in progress: " + existingId);
                return existingId;
            }
        }

        // Prepare destination file
        File destDir = context.getExternalFilesDir(Constants.MODEL_LLM_DIR);
        if (destDir == null) {
            Log.e(TAG, "[ModelFileManager.startLlmDownload] External files dir is null");
            return -1;
        }
        if (!destDir.exists() && !destDir.mkdirs()) {
            Log.e(TAG, "[ModelFileManager.startLlmDownload] Failed to create directory: " + destDir);
            return -1;
        }

        // Build download request
        Uri downloadUri = Uri.parse(Constants.LLM_DOWNLOAD_URL);
        Uri destUri = Uri.fromFile(new File(destDir, Constants.MODEL_LLM_FILENAME));

        DownloadManager.Request request = new DownloadManager.Request(downloadUri)
                .setTitle(Constants.LLM_DOWNLOAD_NOTIFICATION_TITLE)
                .setDescription(Constants.LLM_DOWNLOAD_NOTIFICATION_DESC)
                .setDestinationUri(destUri)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true)  // WHY: User may not have WiFi in field
                .setAllowedOverRoaming(false);  // WHY: Roaming data is expensive

        // Enqueue download
        DownloadManager downloadManager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (downloadManager == null) {
            Log.e(TAG, "[ModelFileManager.startLlmDownload] DownloadManager is null");
            return -1;
        }

        long downloadId = downloadManager.enqueue(request);
        storeDownloadId(context, downloadId);

        Log.i(TAG, "[ModelFileManager.startLlmDownload] Download started with ID: " + downloadId);
        return downloadId;
    }

    /**
     * Queries current download status.
     *
     * @param context    Application context
     * @param downloadId The download ID from startLlmDownload()
     * @return Current download status with progress information
     */
    @NonNull
    public static ModelDownloadStatus getDownloadStatus(@NonNull Context context, long downloadId) {
        if (downloadId == -1) {
            // Check if model already downloaded
            if (isLlmModelReady(context)) {
                return ModelDownloadStatus.complete();
            }
            return ModelDownloadStatus.notStarted();
        }

        DownloadManager downloadManager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (downloadManager == null) {
            return ModelDownloadStatus.failed("DownloadManager unavailable");
        }

        DownloadManager.Query query = new DownloadManager.Query();
        query.setFilterById(downloadId);

        try (Cursor cursor = downloadManager.query(query)) {
            if (cursor == null || !cursor.moveToFirst()) {
                // Download ID not found — may have completed or been cancelled
                if (isLlmModelReady(context)) {
                    return ModelDownloadStatus.complete();
                }
                clearStoredDownloadId(context);
                return ModelDownloadStatus.notStarted();
            }

            int statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
            int bytesDownloadedIndex = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
            int totalBytesIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES);
            int reasonIndex = cursor.getColumnIndex(DownloadManager.COLUMN_REASON);

            int status = cursor.getInt(statusIndex);
            long bytesDownloaded = cursor.getLong(bytesDownloadedIndex);
            long totalBytes = cursor.getLong(totalBytesIndex);

            // Estimate speed (simple moving average would be better, but this is demo)
            // PHASE 2: Implement proper speed calculation with timestamps
            long bytesPerSecond = 0;

            switch (status) {
                case DownloadManager.STATUS_PENDING:
                    return ModelDownloadStatus.queued(downloadId);

                case DownloadManager.STATUS_RUNNING:
                    return ModelDownloadStatus.downloading(bytesDownloaded, totalBytes, bytesPerSecond, downloadId);

                case DownloadManager.STATUS_PAUSED:
                    int pauseReason = cursor.getInt(reasonIndex);
                    String pauseMessage = getPauseReasonMessage(pauseReason);
                    return ModelDownloadStatus.paused(bytesDownloaded, totalBytes, downloadId, pauseMessage);

                case DownloadManager.STATUS_SUCCESSFUL:
                    clearStoredDownloadId(context);
                    // Verify downloaded file
                    if (isLlmModelReady(context)) {
                        return ModelDownloadStatus.complete();
                    } else {
                        return ModelDownloadStatus.failed("Downloaded file verification failed");
                    }

                case DownloadManager.STATUS_FAILED:
                    int failReason = cursor.getInt(reasonIndex);
                    String failMessage = getFailureReasonMessage(failReason);
                    clearStoredDownloadId(context);
                    return ModelDownloadStatus.failed(failMessage);

                default:
                    return ModelDownloadStatus.notStarted();
            }
        }
    }

    /**
     * Cancels an in-progress download.
     *
     * @param context    Application context
     * @param downloadId The download ID to cancel
     */
    public static void cancelDownload(@NonNull Context context, long downloadId) {
        if (downloadId == -1) {
            return;
        }

        DownloadManager downloadManager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (downloadManager != null) {
            downloadManager.remove(downloadId);
            Log.i(TAG, "[ModelFileManager.cancelDownload] Cancelled download: " + downloadId);
        }

        clearStoredDownloadId(context);
    }

    /**
     * Deletes the downloaded LLM model file.
     * Used when re-downloading is needed.
     */
    public static void deleteLlmModel(@NonNull Context context) {
        File modelFile = getLlmModelFile(context);
        if (modelFile != null && modelFile.exists()) {
            if (modelFile.delete()) {
                Log.i(TAG, "[ModelFileManager.deleteLlmModel] Model file deleted");
            } else {
                Log.w(TAG, "[ModelFileManager.deleteLlmModel] Failed to delete model file");
            }
        }
        clearStoredDownloadId(context);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SHARED PREFERENCES — DOWNLOAD ID PERSISTENCE
    // ═══════════════════════════════════════════════════════════════════════════

    private static void storeDownloadId(@NonNull Context context, long downloadId) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putLong(Constants.PREF_KEY_LLM_DOWNLOAD_ID, downloadId).apply();
    }

    /**
     * Returns stored download ID, or -1 if none.
     */
    public static long getStoredDownloadId(@NonNull Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getLong(Constants.PREF_KEY_LLM_DOWNLOAD_ID, -1);
    }

    private static void clearStoredDownloadId(@NonNull Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().remove(Constants.PREF_KEY_LLM_DOWNLOAD_ID).apply();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ═══════════════════════════════════════════════════════════════════════════

    @NonNull
    private static String getPauseReasonMessage(int reason) {
        switch (reason) {
            case DownloadManager.PAUSED_WAITING_FOR_NETWORK:
                return "Waiting for network...";
            case DownloadManager.PAUSED_WAITING_TO_RETRY:
                return "Waiting to retry...";
            case DownloadManager.PAUSED_QUEUED_FOR_WIFI:
                return "Waiting for WiFi...";
            case DownloadManager.PAUSED_UNKNOWN:
            default:
                return "Paused";
        }
    }

    @NonNull
    private static String getFailureReasonMessage(int reason) {
        switch (reason) {
            case DownloadManager.ERROR_CANNOT_RESUME:
                return "Cannot resume download";
            case DownloadManager.ERROR_DEVICE_NOT_FOUND:
                return "Storage device not found";
            case DownloadManager.ERROR_FILE_ALREADY_EXISTS:
                return "File already exists";
            case DownloadManager.ERROR_FILE_ERROR:
                return "Storage error";
            case DownloadManager.ERROR_HTTP_DATA_ERROR:
                return "Network data error";
            case DownloadManager.ERROR_INSUFFICIENT_SPACE:
                return "Insufficient storage space";
            case DownloadManager.ERROR_TOO_MANY_REDIRECTS:
                return "Too many redirects";
            case DownloadManager.ERROR_UNHANDLED_HTTP_CODE:
                return "Server error";
            case DownloadManager.ERROR_UNKNOWN:
            default:
                return "Download failed";
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DOWNLOAD RETRY LOGIC
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Callback interface for download operations with retry support.
     */
    public interface DownloadCallback {
        /**
         * Called when download progress updates.
         *
         * @param percent Progress percentage (0-100)
         */
        void onProgress(int percent);

        /**
         * Called when download completes successfully.
         */
        void onComplete();

        /**
         * Called when download fails after all retries exhausted.
         *
         * @param error Error description
         */
        void onError(String error);
    }

    /**
     * Starts LLM model download with automatic retry on failure.
     *
     * <p>Uses exponential backoff for retry delays. After max retries,
     * calls onError with failure reason.
     *
     * @param context  Application context
     * @param callback Callback for progress and completion notifications
     */
    public static void startLlmDownloadWithRetry(@NonNull Context context,
                                                  @NonNull DownloadCallback callback) {
        startLlmDownloadWithRetryInternal(context, callback, 0);
    }

    /**
     * Internal retry implementation with attempt counter.
     */
    private static void startLlmDownloadWithRetryInternal(@NonNull Context context,
                                                           @NonNull DownloadCallback callback,
                                                           int attempt) {
        if (attempt >= MAX_DOWNLOAD_RETRIES) {
            Log.e(TAG, "[ModelFileManager.startLlmDownloadWithRetry] Max retries exceeded");
            callback.onError("Download failed after " + MAX_DOWNLOAD_RETRIES + " attempts");
            return;
        }

        long downloadId = startLlmDownload(context);
        if (downloadId == -1) {
            // WHY: Immediate failure — retry with backoff
            long delay = calculateRetryDelay(attempt);
            Log.w(TAG, "[ModelFileManager.startLlmDownloadWithRetry] Failed to start, retrying in "
                    + delay + "ms (attempt " + (attempt + 1) + ")");

            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                startLlmDownloadWithRetryInternal(context, callback, attempt + 1);
            }, delay);
            return;
        }

        Log.i(TAG, "[ModelFileManager.startLlmDownloadWithRetry] Download started, ID: "
                + downloadId + " (attempt " + (attempt + 1) + ")");

        // WHY: Poll for status updates
        // In a real implementation, use BroadcastReceiver for completion
        pollDownloadStatus(context, downloadId, callback, attempt);
    }

    /**
     * Polls download status and handles completion/failure.
     */
    private static void pollDownloadStatus(@NonNull Context context,
                                            long downloadId,
                                            @NonNull DownloadCallback callback,
                                            int attempt) {
        android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        Runnable pollRunnable = new Runnable() {
            @Override
            public void run() {
                ModelDownloadStatus status = getDownloadStatus(context, downloadId);

                switch (status.getState()) {
                    case DOWNLOADING:
                    case QUEUED:
                        // WHY: Report progress and continue polling
                        callback.onProgress(status.getProgressPercent());
                        handler.postDelayed(this, 500);  // Poll every 500ms
                        break;

                    case COMPLETE:
                        callback.onProgress(100);
                        callback.onComplete();
                        break;

                    case FAILED:
                        // WHY: Retry on failure
                        String error = status.getErrorMessage();
                        Log.w(TAG, "[ModelFileManager.pollDownloadStatus] Failed: " + error);

                        if (attempt < MAX_DOWNLOAD_RETRIES - 1) {
                            long delay = calculateRetryDelay(attempt);
                            Log.i(TAG, "[ModelFileManager.pollDownloadStatus] Retrying in " + delay + "ms");
                            handler.postDelayed(() -> {
                                deleteLlmModel(context);  // Clean up partial file
                                startLlmDownloadWithRetryInternal(context, callback, attempt + 1);
                            }, delay);
                        } else {
                            callback.onError(error != null ? error : "Download failed");
                        }
                        break;

                    case PAUSED:
                        // WHY: Continue polling, download will auto-resume
                        handler.postDelayed(this, 1000);
                        break;

                    case NOT_STARTED:
                        // WHY: Unexpected state — likely cancelled
                        callback.onError("Download cancelled");
                        break;
                }
            }
        };

        handler.post(pollRunnable);
    }

    /**
     * Calculates retry delay with exponential backoff.
     */
    private static long calculateRetryDelay(int attempt) {
        long delay = INITIAL_RETRY_DELAY_MS * (long) Math.pow(2, attempt);
        return Math.min(delay, MAX_RETRY_DELAY_MS);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CHECKSUM VERIFICATION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Verifies file integrity using SHA-256 checksum.
     *
     * <p>RISK: Large file checksumming is I/O bound. Call from background thread.
     *
     * @param file           File to verify
     * @param expectedSha256 Expected SHA-256 hash (lowercase hex string)
     * @return True if checksum matches
     */
    @WorkerThread
    public static boolean verifyChecksum(@NonNull File file, @NonNull String expectedSha256) {
        if (!file.exists()) {
            Log.e(TAG, "[ModelFileManager.verifyChecksum] File does not exist: " + file);
            return false;
        }

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] buffer = new byte[COPY_BUFFER_SIZE];
                int bytesRead;

                while ((bytesRead = fis.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }
            }

            byte[] hashBytes = digest.digest();
            String actualSha256 = bytesToHex(hashBytes);

            boolean matches = actualSha256.equalsIgnoreCase(expectedSha256);

            if (matches) {
                Log.i(TAG, "[ModelFileManager.verifyChecksum] Checksum verified: " + file.getName());
            } else {
                Log.e(TAG, "[ModelFileManager.verifyChecksum] Checksum mismatch for " + file.getName()
                        + ". Expected: " + expectedSha256 + ", Actual: " + actualSha256);
            }

            return matches;

        } catch (NoSuchAlgorithmException e) {
            Log.e(TAG, "[ModelFileManager.verifyChecksum] SHA-256 not available: " + e.getMessage(), e);
            return false;
        } catch (IOException e) {
            Log.e(TAG, "[ModelFileManager.verifyChecksum] Read error: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * Verifies LLM model checksum.
     *
     * @param context Application context
     * @return True if checksum matches expected value
     */
    @WorkerThread
    public static boolean verifyLlmChecksum(@NonNull Context context) {
        File modelFile = getLlmModelFile(context);
        if (modelFile == null || !modelFile.exists()) {
            return false;
        }

        // WHY: Check if expected checksum is defined
        String expectedChecksum = Constants.MODEL_LLM_EXPECTED_SHA256;
        if (expectedChecksum == null || expectedChecksum.isEmpty()) {
            Log.w(TAG, "[ModelFileManager.verifyLlmChecksum] No expected checksum defined, skipping verification");
            return true;  // No checksum to verify against
        }

        return verifyChecksum(modelFile, expectedChecksum);
    }

    /**
     * Verifies Whisper model checksum.
     *
     * @param context Application context
     * @return True if checksum matches expected value
     */
    @WorkerThread
    public static boolean verifyWhisperChecksum(@NonNull Context context) {
        File modelFile = new File(context.getFilesDir(),
                Constants.MODEL_WHISPER_DIR + File.separator + Constants.MODEL_WHISPER_FILENAME);
        if (!modelFile.exists()) {
            return false;
        }

        // WHY: Check if expected checksum is defined
        String expectedChecksum = Constants.MODEL_WHISPER_EXPECTED_SHA256;
        if (expectedChecksum == null || expectedChecksum.isEmpty()) {
            Log.w(TAG, "[ModelFileManager.verifyWhisperChecksum] No expected checksum defined, skipping verification");
            return true;  // No checksum to verify against
        }

        return verifyChecksum(modelFile, expectedChecksum);
    }

    /**
     * Converts byte array to lowercase hex string.
     */
    @NonNull
    private static String bytesToHex(@NonNull byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
