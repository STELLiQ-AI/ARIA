/**
 * ARIAApplication.java
 *
 * Application singleton for the ARIA Demo APK. Handles global initialization tasks
 * that must complete before any Activity or Service starts.
 *
 * <p>Responsibility:
 * <ul>
 *   <li>Initialize notification channel for Android 8.0+ (required before first notification)</li>
 *   <li>Provide global Application context access via static getInstance()</li>
 *   <li>Initialize Room database singleton</li>
 *   <li>This class does NOT start services or load models — that's ARIASessionService's job</li>
 * </ul>
 *
 * <p>Architecture Position:
 * Root of the application component hierarchy. All Activities and Services can access
 * the Application instance. Depends only on Android framework and Constants.
 *
 * <p>Thread Safety:
 * onCreate() runs on main thread exactly once. getInstance() is thread-safe after onCreate().
 *
 * <p>Air-Gap Compliance:
 * No network initialization occurs here. Model download is user-initiated from ModelDownloadFragment.
 *
 * @author STELLiQ Engineering
 * @version 0.1.0
 * @since ARIA Demo Build — 2026-03-02
 */
package com.stelliq.aria;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.stelliq.aria.db.AARDatabase;
import com.stelliq.aria.util.Constants;

public class ARIAApplication extends Application {

    private static final String TAG = Constants.LOG_TAG_SERVICE;

    // WHY: Volatile ensures visibility across threads after onCreate() completes
    private static volatile ARIAApplication sInstance;

    @Nullable
    private AARDatabase mDatabase;

    /**
     * Returns the Application singleton instance.
     * Safe to call from any thread after Application.onCreate() has completed.
     *
     * @return The ARIAApplication instance
     * @throws IllegalStateException if called before onCreate() completes
     */
    @NonNull
    public static ARIAApplication getInstance() {
        ARIAApplication instance = sInstance;
        if (instance == null) {
            throw new IllegalStateException(
                    "[ARIAApplication.getInstance] Called before Application.onCreate() completed");
        }
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        // WHY: Store instance first so getInstance() can be called during subsequent init
        sInstance = this;

        Log.i(TAG, "[ARIAApplication.onCreate] Initializing ARIA Demo APK v" + Constants.APP_VERSION);

        // RISK: NotificationChannel must be created before any startForeground() call on Android 8.0+
        // Missing channel causes silent notification failure and service termination on Android 14
        createNotificationChannel();

        // WHY: Initialize database singleton eagerly to catch schema errors at startup
        // rather than first database access (which might be during recording)
        initializeDatabase();

        Log.i(TAG, "[ARIAApplication.onCreate] Initialization complete");
    }

    /**
     * Creates the notification channel required for foreground service notifications.
     *
     * <p>RISK: On Android 14, missing notification channel causes the foreground service
     * to be silently terminated when the app is backgrounded. This is the #1 cause of
     * audio recording failures in production.
     *
     * <p>Channel is idempotent — safe to call multiple times.
     */
    private void createNotificationChannel() {
        // WHY: NotificationChannel API only exists on Android 8.0+ (API 26)
        // Our minSdk is 29, so this check is technically redundant but kept for clarity
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    Constants.NOTIFICATION_CHANNEL_ID,
                    Constants.NOTIFICATION_CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW  // WHY: LOW = no sound, visible in tray
            );
            channel.setDescription(Constants.NOTIFICATION_CHANNEL_DESC);

            // WHY: Disable vibration and sound for recording indicator — should be unobtrusive
            channel.enableVibration(false);
            channel.setSound(null, null);

            // WHY: Separate channel for completion alerts — DEFAULT importance enables
            // sound + heads-up banner so the user notices when summary is ready.
            NotificationChannel completeChannel = new NotificationChannel(
                    Constants.NOTIFICATION_COMPLETE_CHANNEL_ID,
                    Constants.NOTIFICATION_COMPLETE_CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            completeChannel.setDescription(Constants.NOTIFICATION_COMPLETE_CHANNEL_DESC);

            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
                notificationManager.createNotificationChannel(completeChannel);
                Log.d(TAG, "[ARIAApplication.createNotificationChannel] Channels created: "
                        + Constants.NOTIFICATION_CHANNEL_ID + ", " + Constants.NOTIFICATION_COMPLETE_CHANNEL_ID);
            } else {
                // WHY: Log error but don't crash — service will fail later with clearer error
                Log.e(TAG, "[ARIAApplication.createNotificationChannel] NotificationManager is null");
            }
        }
    }

    /**
     * Initializes the Room database singleton.
     *
     * <p>WHY eagerly initialize: Room performs schema validation at build time, but
     * runtime initialization can still fail on corrupted database files. Better to
     * fail fast at app startup than during a recording session.
     */
    private void initializeDatabase() {
        try {
            mDatabase = AARDatabase.getInstance(this);
            Log.d(TAG, "[ARIAApplication.initializeDatabase] Room database initialized: "
                    + Constants.DATABASE_NAME);
        } catch (Exception e) {
            // WHY: Log but don't crash — database errors should surface when actually used
            Log.e(TAG, "[ARIAApplication.initializeDatabase] Database init failed: " + e.getMessage(), e);
        }
    }

    /**
     * Returns the Room database instance.
     *
     * @return The AARDatabase singleton, or null if initialization failed
     */
    @Nullable
    public AARDatabase getDatabase() {
        return mDatabase;
    }
}
