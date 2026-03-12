/**
 * AccessibilityHelper.java
 *
 * Utility class for accessibility support throughout ARIA app.
 * Provides consistent accessibility announcements and view configuration.
 *
 * <p>Responsibility:
 * <ul>
 *   <li>Announce state changes to screen readers (TalkBack)</li>
 *   <li>Configure views for accessibility compliance</li>
 *   <li>Provide consistent content descriptions</li>
 *   <li>Support WCAG 2.1 AA compliance</li>
 * </ul>
 *
 * <p>Architecture Position:
 * Utility layer. Used by UI components for accessibility support.
 *
 * <p>Thread Safety:
 * All methods must be called on main thread (View operations).
 *
 * <p>IMPORTANT: ARIA is designed for Army personnel who may have
 * service-connected disabilities. Full accessibility support is
 * a requirement, not an enhancement.
 *
 * @author STELLiQ Engineering
 * @version 0.1.0
 * @since ARIA Demo Build — 2026-03-04
 */
package com.stelliq.aria.util;

import android.content.Context;
import android.os.Build;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.annotation.UiThread;
import androidx.core.view.AccessibilityDelegateCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;

/**
 * Accessibility utilities for ARIA app.
 *
 * <p>WHY: Consistent accessibility support improves usability for
 * users with visual, motor, or cognitive impairments. Army personnel
 * may have service-connected disabilities that require accessibility
 * features like TalkBack screen reader.
 */
public class AccessibilityHelper {

    private static final String TAG = Constants.LOG_TAG_UI;

    // ═══════════════════════════════════════════════════════════════════════════
    // ANNOUNCEMENTS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Announces a message to accessibility services (TalkBack).
     *
     * <p>WHY: Screen reader users need audio feedback for state changes
     * that sighted users observe visually.
     *
     * @param view    Any view in the hierarchy (for context)
     * @param message Message to announce
     */
    @UiThread
    public static void announce(@NonNull View view, @NonNull CharSequence message) {
        if (!isAccessibilityEnabled(view.getContext())) {
            return;
        }

        // WHY: announceForAccessibility handles TalkBack queue properly
        view.announceForAccessibility(message);
    }

    /**
     * Announces a message from string resource.
     *
     * @param view  Any view in the hierarchy
     * @param resId String resource ID
     */
    @UiThread
    public static void announce(@NonNull View view, @StringRes int resId) {
        announce(view, view.getContext().getString(resId));
    }

    /**
     * Announces a formatted message.
     *
     * @param view       Any view in the hierarchy
     * @param format     Format string
     * @param formatArgs Format arguments
     */
    @UiThread
    public static void announceFormatted(@NonNull View view,
                                         @NonNull String format,
                                         Object... formatArgs) {
        announce(view, String.format(format, formatArgs));
    }

    /**
     * Announces recording state change.
     *
     * @param view        Any view in the hierarchy
     * @param isRecording Whether recording is now active
     */
    @UiThread
    public static void announceRecordingState(@NonNull View view, boolean isRecording) {
        if (isRecording) {
            announce(view, "Recording started. Speak clearly into the microphone.");
        } else {
            announce(view, "Recording stopped. Processing audio.");
        }
    }

    /**
     * Announces transcript update.
     *
     * @param view       Any view in the hierarchy
     * @param newText    New transcript text
     * @param wordCount  Approximate word count
     */
    @UiThread
    public static void announceTranscriptUpdate(@NonNull View view,
                                                @Nullable String newText,
                                                int wordCount) {
        if (newText == null || newText.isEmpty()) {
            return;
        }

        // WHY: Don't spam announcements for every word
        // Only announce at significant milestones
        if (wordCount == 10 || wordCount == 50 || wordCount == 100 || wordCount % 100 == 0) {
            announceFormatted(view, "Transcript now contains %d words.", wordCount);
        }
    }

    /**
     * Announces model loading state.
     *
     * @param view       Any view in the hierarchy
     * @param modelName  Name of model
     * @param isLoaded   Whether model is now loaded
     */
    @UiThread
    public static void announceModelState(@NonNull View view,
                                          @NonNull String modelName,
                                          boolean isLoaded) {
        if (isLoaded) {
            announceFormatted(view, "%s model loaded and ready.", modelName);
        } else {
            announceFormatted(view, "Loading %s model. Please wait.", modelName);
        }
    }

    /**
     * Announces error with recovery hint.
     *
     * @param view         Any view in the hierarchy
     * @param errorMessage Error description
     * @param recoveryHint Optional recovery suggestion
     */
    @UiThread
    public static void announceError(@NonNull View view,
                                     @NonNull String errorMessage,
                                     @Nullable String recoveryHint) {
        StringBuilder sb = new StringBuilder("Error: ");
        sb.append(errorMessage);
        if (recoveryHint != null && !recoveryHint.isEmpty()) {
            sb.append(". ").append(recoveryHint);
        }
        announce(view, sb.toString());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // VIEW CONFIGURATION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Configures a button with accessibility role and hint.
     *
     * @param button     Button to configure
     * @param actionHint Hint describing what happens on click
     */
    @UiThread
    public static void configureButton(@NonNull Button button,
                                       @NonNull String actionHint) {
        // WHY: Combine role and hint for clear TalkBack announcement
        ViewCompat.setAccessibilityDelegate(button, new AccessibilityDelegateCompat() {
            @Override
            public void onInitializeAccessibilityNodeInfo(@NonNull View host,
                                                          @NonNull AccessibilityNodeInfoCompat info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setRoleDescription("Button");

                // WHY: Add hint that describes the action
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    info.setHintText(actionHint);
                }
            }
        });
    }

    /**
     * Configures a toggle button with current state.
     *
     * @param button        Toggle button
     * @param isActive      Current toggle state
     * @param activeLabel   Label when active (e.g., "Recording")
     * @param inactiveLabel Label when inactive (e.g., "Start recording")
     */
    @UiThread
    public static void configureToggleButton(@NonNull Button button,
                                             boolean isActive,
                                             @NonNull String activeLabel,
                                             @NonNull String inactiveLabel) {
        String contentDescription = isActive ? activeLabel : inactiveLabel;
        button.setContentDescription(contentDescription);

        String actionHint = isActive
                ? "Double tap to stop"
                : "Double tap to start";

        ViewCompat.setAccessibilityDelegate(button, new AccessibilityDelegateCompat() {
            @Override
            public void onInitializeAccessibilityNodeInfo(@NonNull View host,
                                                          @NonNull AccessibilityNodeInfoCompat info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setRoleDescription("Toggle button");
                info.setCheckable(true);
                info.setChecked(isActive);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    info.setHintText(actionHint);
                }
            }
        });
    }

    /**
     * Configures a live region that announces updates automatically.
     *
     * @param view     View to configure as live region
     * @param polite   If true, uses polite mode (waits for pause). If false, uses assertive.
     */
    @UiThread
    public static void configureLiveRegion(@NonNull View view, boolean polite) {
        // WHY: Live regions automatically announce content changes to TalkBack
        ViewCompat.setAccessibilityLiveRegion(
                view,
                polite
                        ? ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE
                        : ViewCompat.ACCESSIBILITY_LIVE_REGION_ASSERTIVE
        );
    }

    /**
     * Configures transcript display for accessibility.
     *
     * @param textView Transcript TextView
     */
    @UiThread
    public static void configureTranscriptView(@NonNull TextView textView) {
        // WHY: Transcript should be announced as it updates
        configureLiveRegion(textView, true);

        // WHY: Allow focus for manual reading
        textView.setFocusable(true);
        textView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);

        // WHY: Role description helps users understand the element
        ViewCompat.setAccessibilityDelegate(textView, new AccessibilityDelegateCompat() {
            @Override
            public void onInitializeAccessibilityNodeInfo(@NonNull View host,
                                                          @NonNull AccessibilityNodeInfoCompat info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setRoleDescription("Transcript");
            }
        });
    }

    /**
     * Configures waveform visualization for accessibility.
     *
     * <p>WHY: Purely visual elements need accessible alternatives.
     *
     * @param view      Waveform view
     * @param isActive  Whether recording is active
     */
    @UiThread
    public static void configureWaveformView(@NonNull View view, boolean isActive) {
        String description = isActive
                ? "Audio waveform showing recording in progress"
                : "Audio waveform, no recording active";
        view.setContentDescription(description);

        // WHY: Decorative when not active, important during recording
        view.setImportantForAccessibility(
                isActive
                        ? View.IMPORTANT_FOR_ACCESSIBILITY_YES
                        : View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        );
    }

    /**
     * Configures a heading for navigation.
     *
     * @param view        View to mark as heading
     * @param headingText Heading text (or null to use view's text)
     */
    @UiThread
    public static void configureHeading(@NonNull View view,
                                        @Nullable String headingText) {
        // WHY: Headings allow users to navigate by section
        ViewCompat.setAccessibilityHeading(view, true);

        if (headingText != null) {
            view.setContentDescription(headingText);
        }
    }

    /**
     * Groups related views for accessibility.
     *
     * @param container       Container view
     * @param groupDescription Description of the group
     */
    @UiThread
    public static void configureGroup(@NonNull View container,
                                      @NonNull String groupDescription) {
        container.setContentDescription(groupDescription);
        container.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // FOCUS MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Requests accessibility focus on a view.
     *
     * <p>WHY: After state changes, focus should move to relevant content.
     *
     * @param view View to focus
     */
    @UiThread
    public static void requestAccessibilityFocus(@NonNull View view) {
        view.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED);
        view.requestFocus();
    }

    /**
     * Sets the next focus view for keyboard/switch navigation.
     *
     * @param view       Current view
     * @param nextFocus  Next view in focus order
     */
    @UiThread
    public static void setNextFocus(@NonNull View view, @NonNull View nextFocus) {
        view.setNextFocusDownId(nextFocus.getId());
        view.setNextFocusForwardId(nextFocus.getId());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // UTILITY
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Checks if accessibility services are enabled.
     *
     * @param context Application context
     * @return true if any accessibility service is active
     */
    public static boolean isAccessibilityEnabled(@NonNull Context context) {
        AccessibilityManager am = (AccessibilityManager)
                context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        return am != null && am.isEnabled();
    }

    /**
     * Checks if TalkBack specifically is enabled.
     *
     * @param context Application context
     * @return true if TalkBack is active
     */
    public static boolean isTalkBackEnabled(@NonNull Context context) {
        AccessibilityManager am = (AccessibilityManager)
                context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        return am != null && am.isTouchExplorationEnabled();
    }

    /**
     * Gets accessibility-friendly duration string.
     *
     * @param durationMs Duration in milliseconds
     * @return Speakable duration (e.g., "2 minutes 30 seconds")
     */
    @NonNull
    public static String formatDurationForAccessibility(long durationMs) {
        long totalSeconds = durationMs / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;

        if (minutes == 0) {
            return seconds == 1 ? "1 second" : seconds + " seconds";
        } else if (seconds == 0) {
            return minutes == 1 ? "1 minute" : minutes + " minutes";
        } else {
            String minPart = minutes == 1 ? "1 minute" : minutes + " minutes";
            String secPart = seconds == 1 ? "1 second" : seconds + " seconds";
            return minPart + " " + secPart;
        }
    }

    /**
     * Gets accessibility-friendly word count string.
     *
     * @param wordCount Number of words
     * @return Speakable word count (e.g., "25 words")
     */
    @NonNull
    public static String formatWordCountForAccessibility(int wordCount) {
        return wordCount == 1 ? "1 word" : wordCount + " words";
    }

    /**
     * Private constructor — use static methods.
     */
    private AccessibilityHelper() {
    }
}
