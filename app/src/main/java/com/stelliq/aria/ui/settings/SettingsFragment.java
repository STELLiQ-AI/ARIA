/**
 * SettingsFragment.java
 *
 * Settings screen with app configuration, storage management,
 * and AI model download/status.
 *
 * @author STELLiQ Engineering
 * @version 1.0.0
 * @since ARIA Commercial Build — 2026-03-04
 */
package com.stelliq.aria.ui.settings;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;
import com.stelliq.aria.R;
import com.stelliq.aria.db.AARDatabase;
import com.stelliq.aria.llm.SummaryTemplate;
import com.stelliq.aria.util.Constants;
import com.stelliq.aria.util.ModelFileManager;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SettingsFragment extends Fragment {

    private static final String TAG = Constants.LOG_TAG_UI;

    // Profile section
    private TextInputEditText mEditSpeakerName;

    // Template section
    private MaterialCardView mCardTemplate;
    private TextView mTextTemplateValue;

    // Storage section
    private TextView mTextStorageValue;
    private TextView mTextSessionCount;
    private MaterialCardView mCardClearCache;
    private MaterialCardView mCardDeleteAll;

    // About section
    private MaterialCardView mCardAbout;

    // Models section
    private ImageView mIconLlmStatus;
    private TextView mTextLlmStatus;
    private MaterialButton mBtnDownloadLlm;
    private LinearLayout mLayoutLlmProgress;
    private LinearProgressIndicator mProgressLlm;
    private TextView mTextLlmProgress;

    // Version
    private TextView mTextVersion;

    // Download tracking
    private long mDownloadId = -1;
    private BroadcastReceiver mDownloadReceiver;
    private Handler mProgressHandler;
    private Runnable mProgressRunnable;
    private static final long PROGRESS_POLL_INTERVAL_MS = 500;
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Find views
        mEditSpeakerName = view.findViewById(R.id.editSpeakerName);
        mCardTemplate = view.findViewById(R.id.cardTemplate);
        mTextTemplateValue = view.findViewById(R.id.textTemplateValue);
        mTextStorageValue = view.findViewById(R.id.textStorageValue);
        mTextSessionCount = view.findViewById(R.id.textSessionCount);
        mCardClearCache = view.findViewById(R.id.cardClearCache);
        mCardDeleteAll = view.findViewById(R.id.cardDeleteAll);
        mCardAbout = view.findViewById(R.id.cardAbout);
        mIconLlmStatus = view.findViewById(R.id.iconLlmStatus);
        mTextLlmStatus = view.findViewById(R.id.textLlmStatus);
        mBtnDownloadLlm = view.findViewById(R.id.btnDownloadLlm);
        mLayoutLlmProgress = view.findViewById(R.id.layoutLlmProgress);
        mProgressLlm = view.findViewById(R.id.progressLlm);
        mTextLlmProgress = view.findViewById(R.id.textLlmProgress);
        mTextVersion = view.findViewById(R.id.textVersion);

        // Setup click listeners
        setupClickListeners();

        // Load speaker name
        setupSpeakerName();

        // Load template selection
        updateTemplateDisplay();

        // Update UI
        updateStorageInfo();
        updateModelStatus();
        updateVersionInfo();

        // Register download receiver
        registerDownloadReceiver();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        stopProgressPolling();
        unregisterDownloadReceiver();
    }

    private void setupClickListeners() {
        if (mCardTemplate != null) {
            mCardTemplate.setOnClickListener(v -> showTemplateSelector());
        }

        if (mCardClearCache != null) {
            mCardClearCache.setOnClickListener(v -> clearCache());
        }

        if (mCardDeleteAll != null) {
            mCardDeleteAll.setOnClickListener(v -> confirmDeleteAll());
        }

        if (mBtnDownloadLlm != null) {
            mBtnDownloadLlm.setOnClickListener(v -> confirmDownloadModel());
        }

        if (mCardAbout != null) {
            mCardAbout.setOnClickListener(v -> showAboutDialog());
        }
    }

    private void setupSpeakerName() {
        if (mEditSpeakerName == null) return;

        SharedPreferences prefs = requireContext().getSharedPreferences(Constants.PREF_FILE, Context.MODE_PRIVATE);
        String savedName = prefs.getString(Constants.PREF_SPEAKER_NAME, "");
        if (!savedName.isEmpty()) {
            mEditSpeakerName.setText(savedName);
        }

        mEditSpeakerName.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                prefs.edit().putString(Constants.PREF_SPEAKER_NAME, s.toString().trim()).apply();
            }
        });
    }

    private void updateTemplateDisplay() {
        SharedPreferences prefs = requireContext().getSharedPreferences(Constants.PREF_FILE, Context.MODE_PRIVATE);
        String templateKey = prefs.getString(Constants.PREF_SUMMARY_TEMPLATE, "retrospective");
        SummaryTemplate template = SummaryTemplate.fromKey(templateKey);
        if (mTextTemplateValue != null) {
            mTextTemplateValue.setText(template.displayName);
        }
    }

    private void showTemplateSelector() {
        SharedPreferences prefs = requireContext().getSharedPreferences(Constants.PREF_FILE, Context.MODE_PRIVATE);
        String currentKey = prefs.getString(Constants.PREF_SUMMARY_TEMPLATE, "retrospective");

        SummaryTemplate[] templates = SummaryTemplate.values();
        String[] items = new String[templates.length];
        int checkedIndex = 0;
        for (int i = 0; i < templates.length; i++) {
            items[i] = templates[i].displayName + "\n" + templates[i].description;
            if (templates[i].key.equals(currentKey)) {
                checkedIndex = i;
            }
        }

        // Build display items (name only for the list, description shown as subtitle)
        String[] displayNames = SummaryTemplate.getDisplayNames();

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Summary Template")
                .setSingleChoiceItems(displayNames, checkedIndex, (dialog, which) -> {
                    SummaryTemplate selected = templates[which];
                    prefs.edit().putString(Constants.PREF_SUMMARY_TEMPLATE, selected.key).apply();
                    updateTemplateDisplay();
                    dialog.dismiss();
                    Toast.makeText(requireContext(),
                            "Template: " + selected.displayName, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showAboutDialog() {
        String version = getString(R.string.app_version);

        View dialogView = LayoutInflater.from(requireContext()).inflate(
                R.layout.dialog_about, null);

        new MaterialAlertDialogBuilder(requireContext())
                .setView(dialogView)
                .setPositiveButton(R.string.action_ok, null)
                .show();
    }

    private void updateStorageInfo() {
        // Calculate session data storage only (DB + cache, NOT AI model files)
        // AI models are shown separately in the AI Models section
        File dbDir = requireContext().getDatabasePath(Constants.DATABASE_NAME).getParentFile();
        long totalSize = 0;
        if (dbDir != null) {
            totalSize += calculateDirectorySize(dbDir);
        }
        totalSize += calculateDirectorySize(requireContext().getCacheDir());

        String sizeStr = formatFileSize(totalSize);
        if (mTextStorageValue != null) {
            mTextStorageValue.setText(sizeStr);
        }

        // Load session count from database
        AARDatabase.getInstance(requireContext()).aarSessionDao()
                .getAllSessionsLive().observe(getViewLifecycleOwner(), sessions -> {
            if (mTextSessionCount != null) {
                int count = sessions != null ? sessions.size() : 0;
                mTextSessionCount.setText(count + (count == 1 ? " recording" : " recordings"));
            }
        });
    }

    private void updateModelStatus() {
        boolean llmReady = ModelFileManager.isLlmModelReady(requireContext());

        if (llmReady) {
            // WHY: Show the active model display name so the user knows which model
            // is loaded. Critical after switching from 1B to 3B model.
            if (mTextLlmStatus != null) {
                mTextLlmStatus.setText(getString(R.string.settings_model_installed)
                        + " \u2014 " + Constants.MODEL_LLM_DISPLAY_NAME);
            }
            if (mBtnDownloadLlm != null) {
                mBtnDownloadLlm.setVisibility(View.GONE);
            }
            if (mLayoutLlmProgress != null) {
                mLayoutLlmProgress.setVisibility(View.GONE);
            }
            if (mIconLlmStatus != null) {
                mIconLlmStatus.setColorFilter(getResources().getColor(R.color.status_success, null));
            }
        } else {
            // Model not installed
            if (mTextLlmStatus != null) {
                mTextLlmStatus.setText(getString(R.string.settings_model_missing) + " (" + getString(R.string.settings_model_llm_size) + ")");
            }
            if (mBtnDownloadLlm != null) {
                mBtnDownloadLlm.setVisibility(View.VISIBLE);
            }
        }
    }

    private void updateVersionInfo() {
        if (mTextVersion != null) {
            mTextVersion.setText("ARIA " + getString(R.string.app_version));
        }
    }

    private void clearCache() {
        try {
            File cacheDir = requireContext().getCacheDir();
            deleteRecursive(cacheDir);
            Toast.makeText(requireContext(), "Cache cleared", Toast.LENGTH_SHORT).show();
            updateStorageInfo();
        } catch (Exception e) {
            Log.e(TAG, "[SettingsFragment] Clear cache failed: " + e.getMessage());
        }
    }

    private void confirmDeleteAll() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.delete_confirm_title)
                .setMessage(R.string.delete_confirm_message)
                .setPositiveButton(R.string.btn_delete, (dialog, which) -> deleteAllSessions())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void deleteAllSessions() {
        mExecutor.execute(() -> {
            try {
                AARDatabase db = AARDatabase.getInstance(requireContext());

                // WHY: Get count before deleting for user feedback
                int count = db.aarSessionDao().getSessionCount();

                // WHY: Delete all child tables first, then sessions
                // Transcript segments and pipeline metrics reference session UUIDs
                db.runInTransaction(() -> {
                    db.getOpenHelper().getWritableDatabase().execSQL("DELETE FROM transcript_segments");
                    db.getOpenHelper().getWritableDatabase().execSQL("DELETE FROM pipeline_metrics");
                    db.aarSessionDao().deleteAll();
                });

                Log.i(TAG, "[SettingsFragment] Deleted all " + count + " sessions");

                requireActivity().runOnUiThread(() -> {
                    String message = count == 0 ? "No recordings to delete"
                            : count + (count == 1 ? " recording" : " recordings") + " deleted";
                    Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
                    updateStorageInfo();
                });
            } catch (Exception e) {
                Log.e(TAG, "[SettingsFragment] Delete all failed: " + e.getMessage(), e);
                requireActivity().runOnUiThread(() ->
                    Toast.makeText(requireContext(), "Failed to delete recordings", Toast.LENGTH_SHORT).show()
                );
            }
        });
    }

    private void confirmDownloadModel() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_models_download_title)
                .setMessage(R.string.settings_models_download_message)
                .setPositiveButton(R.string.settings_models_download_button, (dialog, which) -> startModelDownload())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void startModelDownload() {
        Log.i(TAG, "[SettingsFragment] Starting model download");

        try {
            DownloadManager downloadManager = (DownloadManager) requireContext().getSystemService(Context.DOWNLOAD_SERVICE);

            Uri uri = Uri.parse(Constants.LLM_DOWNLOAD_URL);
            DownloadManager.Request request = new DownloadManager.Request(uri);

            request.setTitle("ARIA AI Model");
            request.setDescription("Downloading ARIA AAR 1B model");
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            // IMPORTANT: Must use MODEL_LLM_DIR ("models") - this is where ModelFileManager checks
            request.setDestinationInExternalFilesDir(requireContext(), Constants.MODEL_LLM_DIR, Constants.MODEL_LLM);
            request.setAllowedOverMetered(true);
            request.setAllowedOverRoaming(false);

            mDownloadId = downloadManager.enqueue(request);

            // Show progress UI
            if (mBtnDownloadLlm != null) {
                mBtnDownloadLlm.setVisibility(View.GONE);
            }
            if (mLayoutLlmProgress != null) {
                mLayoutLlmProgress.setVisibility(View.VISIBLE);
            }
            if (mProgressLlm != null) {
                mProgressLlm.setIndeterminate(false);
                mProgressLlm.setProgress(0);
            }
            if (mTextLlmProgress != null) {
                mTextLlmProgress.setText("Downloading... 0%");
            }

            // Start progress polling
            startProgressPolling();

        } catch (Exception e) {
            Log.e(TAG, "[SettingsFragment] Download failed: " + e.getMessage());
            Toast.makeText(requireContext(), "Download failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void registerDownloadReceiver() {
        mDownloadReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                long downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (downloadId == mDownloadId) {
                    onDownloadComplete();
                }
            }
        };

        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        requireContext().registerReceiver(mDownloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
    }

    private void unregisterDownloadReceiver() {
        if (mDownloadReceiver != null) {
            try {
                requireContext().unregisterReceiver(mDownloadReceiver);
            } catch (Exception e) {
                // Ignore
            }
        }
    }

    private void onDownloadComplete() {
        Log.i(TAG, "[SettingsFragment] Download complete");

        // Stop progress polling
        stopProgressPolling();
        mDownloadId = -1;

        // Update UI
        if (mLayoutLlmProgress != null) {
            mLayoutLlmProgress.setVisibility(View.GONE);
        }

        updateModelStatus();
        Toast.makeText(requireContext(), "Download complete!", Toast.LENGTH_SHORT).show();
    }

    private void onDownloadFailed(int reason) {
        Log.e(TAG, "[SettingsFragment] Download failed with reason: " + reason);

        // Stop progress polling
        stopProgressPolling();
        mDownloadId = -1;

        // Update UI
        if (mLayoutLlmProgress != null) {
            mLayoutLlmProgress.setVisibility(View.GONE);
        }
        if (mBtnDownloadLlm != null) {
            mBtnDownloadLlm.setVisibility(View.VISIBLE);
        }

        String errorMessage;
        switch (reason) {
            case DownloadManager.ERROR_INSUFFICIENT_SPACE:
                errorMessage = "Not enough storage space";
                break;
            case DownloadManager.ERROR_DEVICE_NOT_FOUND:
                errorMessage = "Storage not available";
                break;
            case DownloadManager.ERROR_HTTP_DATA_ERROR:
            case DownloadManager.ERROR_UNHANDLED_HTTP_CODE:
                errorMessage = "Network error - please try again";
                break;
            default:
                errorMessage = "Download failed - please try again";
        }

        Toast.makeText(requireContext(), errorMessage, Toast.LENGTH_LONG).show();
    }

    private void startProgressPolling() {
        mProgressHandler = new Handler(Looper.getMainLooper());
        mProgressRunnable = new Runnable() {
            @Override
            public void run() {
                if (mDownloadId == -1) return;

                try {
                    DownloadManager downloadManager = (DownloadManager) requireContext().getSystemService(Context.DOWNLOAD_SERVICE);
                    DownloadManager.Query query = new DownloadManager.Query();
                    query.setFilterById(mDownloadId);

                    Cursor cursor = downloadManager.query(query);
                    if (cursor != null && cursor.moveToFirst()) {
                        int statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
                        int bytesDownloadedIndex = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
                        int bytesTotalIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES);

                        if (statusIndex >= 0 && bytesDownloadedIndex >= 0 && bytesTotalIndex >= 0) {
                            int status = cursor.getInt(statusIndex);
                            long bytesDownloaded = cursor.getLong(bytesDownloadedIndex);
                            long bytesTotal = cursor.getLong(bytesTotalIndex);

                            if (status == DownloadManager.STATUS_RUNNING && bytesTotal > 0) {
                                int progress = (int) ((bytesDownloaded * 100) / bytesTotal);

                                if (mProgressLlm != null) {
                                    mProgressLlm.setProgress(progress);
                                }
                                if (mTextLlmProgress != null) {
                                    String progressText = String.format("Downloading... %d%% (%s / %s)",
                                            progress,
                                            formatFileSize(bytesDownloaded),
                                            formatFileSize(bytesTotal));
                                    mTextLlmProgress.setText(progressText);
                                }
                            } else if (status == DownloadManager.STATUS_PENDING) {
                                if (mTextLlmProgress != null) {
                                    mTextLlmProgress.setText("Starting download...");
                                }
                            } else if (status == DownloadManager.STATUS_SUCCESSFUL) {
                                // Download complete - trigger completion handler
                                cursor.close();
                                onDownloadComplete();
                                return; // Stop polling
                            } else if (status == DownloadManager.STATUS_FAILED) {
                                int reasonIndex = cursor.getColumnIndex(DownloadManager.COLUMN_REASON);
                                int reason = reasonIndex >= 0 ? cursor.getInt(reasonIndex) : -1;
                                cursor.close();
                                onDownloadFailed(reason);
                                return; // Stop polling
                            } else if (status == DownloadManager.STATUS_PAUSED) {
                                if (mTextLlmProgress != null) {
                                    mTextLlmProgress.setText("Download paused - waiting for network...");
                                }
                            }
                        }
                        cursor.close();
                    }

                    // Continue polling
                    mProgressHandler.postDelayed(this, PROGRESS_POLL_INTERVAL_MS);

                } catch (Exception e) {
                    Log.e(TAG, "[SettingsFragment] Progress poll error: " + e.getMessage());
                }
            }
        };

        mProgressHandler.post(mProgressRunnable);
    }

    private void stopProgressPolling() {
        if (mProgressHandler != null && mProgressRunnable != null) {
            mProgressHandler.removeCallbacks(mProgressRunnable);
        }
        mProgressHandler = null;
        mProgressRunnable = null;
    }

    // Utility methods
    private long calculateDirectorySize(File dir) {
        if (dir == null || !dir.exists()) return 0;

        long size = 0;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    size += calculateDirectorySize(file);
                } else {
                    size += file.length();
                }
            }
        }
        return size;
    }

    private void deleteRecursive(File dir) {
        if (dir == null || !dir.exists()) return;

        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteRecursive(file);
                }
                file.delete();
            }
        }
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
