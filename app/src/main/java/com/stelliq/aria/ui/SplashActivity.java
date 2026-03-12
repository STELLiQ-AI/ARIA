/**
 * SplashActivity.java
 *
 * Professional splash screen for ARIA app. Shows STELLiQ branding,
 * A.R.I.A. product identity, and loading progress while initializing
 * AI models in the background.
 *
 * <p>Flow:
 * 1. Show branded splash screen
 * 2. Start ARIASessionService to load models
 * 3. Update progress bar as models load
 * 4. Transition to MainActivity when ready
 *
 * @author STELLiQ Engineering
 * @version 1.0.0
 * @since ARIA Commercial Build — 2026-03-04
 */
package com.stelliq.aria.ui;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.splashscreen.SplashScreen;

import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.stelliq.aria.R;
import com.stelliq.aria.service.ARIASessionService;
import com.stelliq.aria.util.Constants;
import com.stelliq.aria.util.ModelFileManager;

@SuppressLint("CustomSplashScreen")
public class SplashActivity extends AppCompatActivity {

    private static final String TAG = "ARIA_SPLASH";
    private static final long MIN_SPLASH_DURATION_MS = 2000;
    private static final long MAX_SPLASH_DURATION_MS = 10000;

    private TextView mLoadingStatus;
    private LinearProgressIndicator mLoadingProgress;

    private ARIASessionService mService;
    private boolean mBound = false;
    private long mStartTime;
    private Handler mHandler;
    private boolean mNavigatedAway = false;

    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.d(TAG, "[SplashActivity] Service connected");
            ARIASessionService.LocalBinder binder = (ARIASessionService.LocalBinder) service;
            mService = binder.getService();
            mBound = true;

            // Check model loading state
            checkModelState();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.d(TAG, "[SplashActivity] Service disconnected");
            mService = null;
            mBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Handle Android 12+ system splash
        SplashScreen splashScreen = SplashScreen.installSplashScreen(this);

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        mStartTime = System.currentTimeMillis();
        mHandler = new Handler(Looper.getMainLooper());

        // Find views
        mLoadingStatus = findViewById(R.id.loadingStatus);
        mLoadingProgress = findViewById(R.id.loadingProgress);

        // Initialize progress
        if (mLoadingProgress != null) {
            mLoadingProgress.setProgress(0);
        }
        updateLoadingStatus(getString(R.string.splash_loading_init));

        // Start service binding
        Intent serviceIntent = new Intent(this, ARIASessionService.class);
        startService(serviceIntent);
        bindService(serviceIntent, mServiceConnection, Context.BIND_AUTO_CREATE);

        // Safety timeout - navigate to MainActivity after max duration
        mHandler.postDelayed(this::navigateToMain, MAX_SPLASH_DURATION_MS);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mBound) {
            unbindService(mServiceConnection);
            mBound = false;
        }
        mHandler.removeCallbacksAndMessages(null);
    }

    private void checkModelState() {
        // Check if LLM model is ready
        boolean llmReady = ModelFileManager.isLlmModelReady(this);
        boolean whisperReady = true; // Bundled with APK

        if (llmReady) {
            updateLoadingStatus(getString(R.string.splash_loading_models));
            animateProgress(50, 90, 1500);

            // Wait for models to load
            mHandler.postDelayed(() -> {
                updateLoadingStatus(getString(R.string.splash_loading_ready));
                animateProgress(90, 100, 500);

                // Ensure minimum splash duration
                long elapsed = System.currentTimeMillis() - mStartTime;
                long remaining = Math.max(0, MIN_SPLASH_DURATION_MS - elapsed);

                mHandler.postDelayed(this::navigateToMain, remaining + 500);
            }, 1500);
        } else {
            // LLM not downloaded - go to main activity which will show download screen
            updateLoadingStatus("AI Model required");
            animateProgress(0, 100, 1000);

            long elapsed = System.currentTimeMillis() - mStartTime;
            long remaining = Math.max(0, MIN_SPLASH_DURATION_MS - elapsed);

            mHandler.postDelayed(this::navigateToMain, remaining + 500);
        }
    }

    private void updateLoadingStatus(@NonNull String status) {
        if (mLoadingStatus != null) {
            mLoadingStatus.setText(status);
        }
    }

    private void animateProgress(int from, int to, long duration) {
        if (mLoadingProgress == null) return;

        int steps = 20;
        long stepDuration = duration / steps;
        int stepSize = (to - from) / steps;

        for (int i = 0; i <= steps; i++) {
            final int progress = from + (stepSize * i);
            mHandler.postDelayed(() -> {
                if (mLoadingProgress != null) {
                    mLoadingProgress.setProgress(Math.min(progress, 100));
                }
            }, stepDuration * i);
        }
    }

    private void navigateToMain() {
        if (mNavigatedAway) return;
        mNavigatedAway = true;

        Log.i(TAG, "[SplashActivity] Navigating to MainActivity");

        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();

        // Smooth transition
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }
}
