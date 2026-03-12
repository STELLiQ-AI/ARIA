/**
 * NotificationHelper.java
 *
 * Utility class for building and managing ARIA foreground service notifications.
 * Handles notification creation for recording and summarizing states.
 *
 * <p>Responsibility:
 * <ul>
 *   <li>Build foreground service notification for RECORDING state</li>
 *   <li>Build foreground service notification for SUMMARIZING state</li>
 *   <li>Provide notification action PendingIntents</li>
 *   <li>This class does NOT manage notification lifecycle — that's ARIASessionService's job</li>
 * </ul>
 *
 * <p>Architecture Position:
 * Utility layer. Called by ARIASessionService when entering foreground states.
 * Depends on Constants for channel/notification IDs.
 *
 * <p>Thread Safety:
 * All methods are stateless and thread-safe.
 *
 * <p>Air-Gap Compliance:
 * N/A — no network involvement.
 *
 * @author STELLiQ Engineering
 * @version 0.1.0
 * @since ARIA Demo Build — 2026-03-02
 */
package com.stelliq.aria.util;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import com.stelliq.aria.ui.MainActivity;
import com.stelliq.aria.R;

public final class NotificationHelper {

    // WHY: Private constructor — utility class should not be instantiated
    private NotificationHelper() {
        throw new AssertionError("NotificationHelper is a non-instantiable utility class");
    }

    /**
     * Builds a notification for the RECORDING state.
     *
     * <p>Displays "Recording in progress..." with a Stop action button.
     *
     * @param context Application or service context
     * @return Notification suitable for startForeground()
     */
    @NonNull
    public static Notification buildRecordingNotification(@NonNull Context context) {
        // WHY: PendingIntent to open the app when user taps notification
        PendingIntent contentIntent = buildContentIntent(context);

        // WHY: PendingIntent for STOP action button
        PendingIntent stopIntent = buildStopActionIntent(context);

        return new NotificationCompat.Builder(context, Constants.NOTIFICATION_CHANNEL_ID)
                .setContentTitle(context.getString(R.string.notification_recording_title))
                .setContentText(context.getString(R.string.notification_recording_text))
                .setSmallIcon(R.drawable.ic_mic)  // PHASE 2: Create proper ARIA icon
                .setContentIntent(contentIntent)
                .setOngoing(true)  // WHY: Cannot be swiped away — indicates active recording
                .setOnlyAlertOnce(true)  // WHY: Don't re-alert on updates
                .setPriority(NotificationCompat.PRIORITY_LOW)  // WHY: Visible but not intrusive
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .addAction(
                        R.drawable.ic_stop,  // PHASE 2: Create proper stop icon
                        context.getString(R.string.notification_action_stop),
                        stopIntent
                )
                .build();
    }

    /**
     * Builds a notification for the SUMMARIZING state.
     *
     * <p>Displays "Generating AAR summary..." without action buttons.
     *
     * @param context Application or service context
     * @return Notification suitable for startForeground()
     */
    @NonNull
    public static Notification buildSummarizingNotification(@NonNull Context context) {
        PendingIntent contentIntent = buildContentIntent(context);

        return new NotificationCompat.Builder(context, Constants.NOTIFICATION_CHANNEL_ID)
                .setContentTitle(context.getString(R.string.notification_summarizing_title))
                .setContentText(context.getString(R.string.notification_summarizing_text))
                .setSmallIcon(R.drawable.ic_summary)  // PHASE 2: Create proper summary icon
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setProgress(0, 0, true)  // WHY: Indeterminate progress indicator
                .build();
    }

    /**
     * Builds a notification for the SUMMARIZING state with progress.
     *
     * @param context  Application or service context
     * @param progress Progress percentage (0-100)
     * @return Notification with progress bar
     */
    @NonNull
    public static Notification buildSummarizingNotificationWithProgress(
            @NonNull Context context, int progress) {
        PendingIntent contentIntent = buildContentIntent(context);

        return new NotificationCompat.Builder(context, Constants.NOTIFICATION_CHANNEL_ID)
                .setContentTitle(context.getString(R.string.notification_summarizing_title))
                .setContentText(progress + "% complete")
                .setSmallIcon(R.drawable.ic_summary)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setProgress(100, progress, false)  // WHY: Determinate progress
                .build();
    }

    /**
     * Builds a notification for summary completion.
     * Tapping opens the session detail screen via deep link.
     *
     * @param context   Application or service context
     * @param sessionId UUID of the completed session for deep link
     * @return Notification with sound and heads-up display
     */
    @NonNull
    public static Notification buildCompletionNotification(
            @NonNull Context context, @NonNull String sessionId) {
        // WHY: Deep link intent carries session ID so MainActivity can navigate to detail
        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.putExtra(Constants.EXTRA_NAVIGATE_SESSION_ID, sessionId);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent contentIntent = PendingIntent.getActivity(context, 2, intent, flags);

        return new NotificationCompat.Builder(context, Constants.NOTIFICATION_COMPLETE_CHANNEL_ID)
                .setContentTitle(context.getString(R.string.notification_complete_title))
                .setContentText(context.getString(R.string.notification_complete_text))
                .setSmallIcon(R.drawable.ic_summary)
                .setContentIntent(contentIntent)
                .setAutoCancel(true)  // WHY: Dismiss when tapped
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build();
    }

    /**
     * Builds a PendingIntent that opens MainActivity when notification is tapped.
     */
    @NonNull
    private static PendingIntent buildContentIntent(@NonNull Context context) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        // WHY: FLAG_IMMUTABLE required on Android 12+ for security
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }

        return PendingIntent.getActivity(context, 0, intent, flags);
    }

    /**
     * Builds a PendingIntent for the STOP action that sends broadcast to service.
     */
    @NonNull
    private static PendingIntent buildStopActionIntent(@NonNull Context context) {
        Intent intent = new Intent(Constants.ACTION_STOP_RECORDING);
        intent.setPackage(context.getPackageName());

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }

        return PendingIntent.getBroadcast(context, 1, intent, flags);
    }
}
