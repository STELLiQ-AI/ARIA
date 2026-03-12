/**
 * MainActivity.java
 *
 * Single-activity host for ARIA navigation. Uses Navigation Component
 * with bottom navigation bar for Home, Sessions, and Settings tabs.
 *
 * <p>Responsibility:
 * <ul>
 *   <li>Host NavHostFragment for fragment navigation</li>
 *   <li>Setup bottom navigation with NavController</li>
 *   <li>Handle runtime permission requests (RECORD_AUDIO, POST_NOTIFICATIONS)</li>
 *   <li>Bind to ARIASessionService for state observation</li>
 * </ul>
 *
 * <p>Navigation Destinations:
 * <ul>
 *   <li>HomeFragment - Main screen with recording CTA</li>
 *   <li>RecordingFragment - Active recording interface</li>
 *   <li>SessionsFragment - Session history list</li>
 *   <li>SessionDetailFragment - Individual session view</li>
 *   <li>SettingsFragment - App settings and model management</li>
 * </ul>
 *
 * @author STELLiQ Engineering
 * @version 1.0.0
 * @since ARIA Commercial Build — 2026-03-04
 */
package com.stelliq.aria.ui;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.navigation.NavController;
import androidx.navigation.NavOptions;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.stelliq.aria.R;
import com.stelliq.aria.service.ARIASessionService;
import com.stelliq.aria.util.Constants;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = Constants.LOG_TAG_UI;

    private NavController mNavController;
    private BottomNavigationView mBottomNav;

    @Nullable
    private ARIASessionService mService;
    private boolean mBound = false;

    // Permission launcher
    private ActivityResultLauncher<String[]> mPermissionLauncher;

    // Service connection
    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.d(TAG, "[MainActivity] Service connected");
            ARIASessionService.LocalBinder binder = (ARIASessionService.LocalBinder) service;
            mService = binder.getService();
            mBound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.d(TAG, "[MainActivity] Service disconnected");
            mService = null;
            mBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Log.i(TAG, "[MainActivity.onCreate] ARIA starting");

        // Initialize permission launcher
        mPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                this::onPermissionsResult
        );

        // Setup navigation
        setupNavigation();

        // Request permissions if needed
        if (!hasRequiredPermissions()) {
            requestRequiredPermissions();
        }

        // Handle deep link from completion notification (cold start)
        handleNotificationDeepLink(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // Handle deep link from completion notification (warm start / singleTop)
        handleNotificationDeepLink(intent);
    }

    /**
     * Navigates to SessionDetailFragment if the intent carries a session ID
     * from a completion notification deep link.
     */
    private void handleNotificationDeepLink(@Nullable Intent intent) {
        if (intent == null) return;
        String sessionId = intent.getStringExtra(Constants.EXTRA_NAVIGATE_SESSION_ID);
        if (sessionId != null) {
            Log.i(TAG, "[MainActivity] Deep link to session: " + sessionId);
            // WHY: Clear the extra so rotation/re-creation doesn't re-navigate
            intent.removeExtra(Constants.EXTRA_NAVIGATE_SESSION_ID);

            Bundle args = new Bundle();
            args.putString("sessionId", sessionId);
            if (mNavController != null) {
                mNavController.navigate(R.id.sessionDetailFragment, args,
                        new NavOptions.Builder()
                                .setPopUpTo(R.id.homeFragment, false)
                                .build());
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();

        // Bind to service
        Intent intent = new Intent(this, ARIASessionService.class);
        bindService(intent, mServiceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        super.onStop();

        if (mBound) {
            unbindService(mServiceConnection);
            mBound = false;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // NAVIGATION
    // ═══════════════════════════════════════════════════════════════════════════

    private void setupNavigation() {
        // Find NavHostFragment
        NavHostFragment navHostFragment = (NavHostFragment) getSupportFragmentManager()
                .findFragmentById(R.id.nav_host_fragment);

        if (navHostFragment == null) {
            Log.e(TAG, "[MainActivity] NavHostFragment not found!");
            return;
        }

        mNavController = navHostFragment.getNavController();

        // Setup bottom navigation
        mBottomNav = findViewById(R.id.bottom_navigation);
        if (mBottomNav != null) {
            // Define top-level destinations for proper back stack handling
            AppBarConfiguration appBarConfiguration = new AppBarConfiguration.Builder(
                    R.id.homeFragment, R.id.sessionsFragment, R.id.settingsFragment
            ).build();

            // Setup with NavController
            NavigationUI.setupWithNavController(mBottomNav, mNavController);

            // Custom item selection to ensure proper navigation with back stack clearing
            mBottomNav.setOnItemSelectedListener(item -> {
                int itemId = item.getItemId();

                // Build NavOptions to clear back stack when switching tabs
                NavOptions navOptions = new NavOptions.Builder()
                        .setPopUpTo(R.id.nav_graph, false)  // Pop to root
                        .setLaunchSingleTop(true)           // Avoid duplicate destinations
                        .build();

                try {
                    mNavController.navigate(itemId, null, navOptions);
                    return true;
                } catch (Exception e) {
                    Log.e(TAG, "[MainActivity] Navigation failed: " + e.getMessage());
                    return false;
                }
            });

            // Hide bottom nav on certain screens
            mNavController.addOnDestinationChangedListener((controller, destination, arguments) -> {
                int destId = destination.getId();

                // Hide bottom nav during recording
                if (destId == R.id.recordingFragment) {
                    hideBottomNav();
                } else {
                    showBottomNav();
                }

                // Update selected item to match current destination
                if (mBottomNav.getSelectedItemId() != destId) {
                    // Only update if it's a top-level destination
                    if (destId == R.id.homeFragment || destId == R.id.sessionsFragment || destId == R.id.settingsFragment) {
                        mBottomNav.getMenu().findItem(destId).setChecked(true);
                    }
                }
            });
        }
    }

    /**
     * Hides the bottom navigation bar.
     */
    public void hideBottomNav() {
        if (mBottomNav != null) {
            mBottomNav.setVisibility(View.GONE);
        }
    }

    /**
     * Shows the bottom navigation bar.
     */
    public void showBottomNav() {
        if (mBottomNav != null) {
            mBottomNav.setVisibility(View.VISIBLE);
        }
    }

    /**
     * Gets the NavController for navigation.
     */
    @Nullable
    public NavController getNavController() {
        return mNavController;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PERMISSIONS
    // ═══════════════════════════════════════════════════════════════════════════

    private boolean hasRequiredPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }

        return true;
    }

    private void requestRequiredPermissions() {
        List<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.RECORD_AUDIO);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        mPermissionLauncher.launch(permissions.toArray(new String[0]));
    }

    private void onPermissionsResult(@NonNull Map<String, Boolean> results) {
        boolean allGranted = true;
        for (Boolean granted : results.values()) {
            if (!granted) {
                allGranted = false;
                break;
            }
        }

        if (allGranted) {
            Log.i(TAG, "[MainActivity] All permissions granted");
        } else {
            Log.w(TAG, "[MainActivity] Some permissions denied");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SERVICE ACCESS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nullable
    public ARIASessionService getService() {
        return mService;
    }

    public boolean isServiceBound() {
        return mBound && mService != null;
    }
}
