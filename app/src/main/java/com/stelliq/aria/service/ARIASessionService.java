/**
 * ARIASessionService.java
 *
 * Foreground service that owns the entire ARIA recording and summarization pipeline.
 * This is the central coordinator for all inference operations.
 *
 * <p>Responsibility:
 * <ul>
 *   <li>Manage foreground service lifecycle with proper notification</li>
 *   <li>Own and coordinate AudioCaptureManager, VAD, WhisperEngine, LlamaEngine</li>
 *   <li>Manage HandlerThreads for ASR and LLM worker operations</li>
 *   <li>Expose session state via SessionController to UI layer</li>
 *   <li>Pre-warm OpenCL context at startup to avoid cold-start penalty during demo</li>
 *   <li>This service does NOT handle UI — that's the Fragments' job</li>
 * </ul>
 *
 * <p>Architecture Position:
 * Service layer — sits between UI (Fragments) and inference engines (WhisperEngine, LlamaEngine).
 * UI binds to this service and observes state via SessionController LiveData.
 *
 * <p>Thread Safety:
 * <ul>
 *   <li>onCreate/onDestroy: Main thread</li>
 *   <li>aria-audio-capture: Dedicated HandlerThread for AudioRecord loop</li>
 *   <li>aria-asr-worker: Dedicated HandlerThread for whisper.cpp inference (BLOCKING)</li>
 *   <li>aria-llm-worker: Dedicated HandlerThread for llama.cpp inference (BLOCKING)</li>
 *   <li>SessionController state updates: Posted to main thread for LiveData safety</li>
 * </ul>
 *
 * <p>Air-Gap Compliance:
 * No network calls during recording or summarization. Model files accessed from local storage only.
 *
 * <p>RISK: foregroundServiceType="microphone" MUST be declared in AndroidManifest.xml.
 * Missing this causes silent service termination on Android 14 when app is backgrounded.
 *
 * @author STELLiQ Engineering
 * @version 0.1.0
 * @since ARIA Demo Build — 2026-03-02
 */
package com.stelliq.aria.service;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.core.content.ContextCompat;

import com.stelliq.aria.asr.AudioCaptureManager;
import com.stelliq.aria.asr.SileroVAD;
import com.stelliq.aria.asr.SlidingWindowBuffer;
import com.stelliq.aria.asr.TextPostProcessor;
import com.stelliq.aria.asr.TranscriptAccumulator;
import com.stelliq.aria.asr.WhisperEngine;
import com.stelliq.aria.db.AARRepository;
import com.stelliq.aria.llm.AARJsonParser;
import com.stelliq.aria.llm.AARPromptBuilder;
import com.stelliq.aria.llm.LlamaEngine;
import com.stelliq.aria.meeting.MeetingManager;
import com.stelliq.aria.model.AARSummary;
import com.stelliq.aria.model.SessionState;
import com.stelliq.aria.util.Constants;
import com.stelliq.aria.util.ModelFileManager;
import com.stelliq.aria.util.NotificationHelper;
import com.stelliq.aria.util.PerfLogger;

public class ARIASessionService extends Service {

    private static final String TAG = Constants.LOG_TAG_SERVICE;

    // ═══════════════════════════════════════════════════════════════════════════
    // BINDER FOR UI BINDING
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Binder for local service binding. Provides direct access to service instance.
     */
    public class LocalBinder extends Binder {
        @NonNull
        public ARIASessionService getService() {
            return ARIASessionService.this;
        }
    }

    private final IBinder mBinder = new LocalBinder();

    // ═══════════════════════════════════════════════════════════════════════════
    // WORKER THREADS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nullable
    private HandlerThread mAudioCaptureThread;

    @Nullable
    private Handler mAudioCaptureHandler;

    @Nullable
    private HandlerThread mAsrWorkerThread;

    @Nullable
    private Handler mAsrWorkerHandler;

    @Nullable
    private HandlerThread mLlmWorkerThread;

    @Nullable
    private Handler mLlmWorkerHandler;

    @Nullable
    private Handler mMainHandler;

    // ═══════════════════════════════════════════════════════════════════════════
    // PIPELINE COMPONENTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nullable
    private SessionController mSessionController;

    @Nullable
    private AudioCaptureManager mAudioCaptureManager;

    @Nullable
    private SileroVAD mSileroVAD;

    @Nullable
    private SlidingWindowBuffer mSlidingWindowBuffer;

    @Nullable
    private TranscriptAccumulator mTranscriptAccumulator;

    @Nullable
    private WhisperEngine mWhisperEngine;

    @Nullable
    private LlamaEngine mLlamaEngine;

    @Nullable
    private AARRepository mRepository;

    @Nullable
    private PerfLogger mPerfLogger;

    @Nullable
    private MeetingManager mMeetingManager;

    @NonNull
    private String mSpeakerName = "User";

    // ═══════════════════════════════════════════════════════════════════════════
    // STATE FLAGS
    // ═══════════════════════════════════════════════════════════════════════════

    private volatile boolean mIsWhisperLoaded = false;
    private volatile boolean mIsLlamaLoaded = false;
    private volatile boolean mIsInitializing = false;

    // WHY: Debug counters for Whisper inference
    private volatile int mWhisperCallCount = 0;
    private volatile int mWindowsPosted = 0;

    // WHY: Track last RTF for adaptive thermal yield between inference windows.
    // RTF naturally degrades as SoC throttles — it's a free thermal proxy.
    private volatile float mLastRtf = 0.0f;

    // WHY: Track current session UUID for database persistence
    // Generated when recording starts, used when saving session
    @Nullable
    private String mCurrentSessionUuid;

    /**
     * Returns the current session UUID, or null if no session is active.
     */
    @Nullable
    public String getCurrentSessionUuid() {
        return mCurrentSessionUuid;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // BROADCAST RECEIVER FOR STOP ACTION
    // ═══════════════════════════════════════════════════════════════════════════

    private final BroadcastReceiver mStopReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Constants.ACTION_STOP_RECORDING.equals(intent.getAction())) {
                Log.i(TAG, "[ARIASessionService.mStopReceiver] Stop action received from notification");
                stopRecordingAndSummarize();
            }
        }
    };

    // ═══════════════════════════════════════════════════════════════════════════
    // SERVICE LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "[ARIASessionService.onCreate] Service creating...");

        // WHY: Main handler for posting state updates from worker threads
        mMainHandler = new Handler(Looper.getMainLooper());

        // Initialize session controller (state machine)
        mSessionController = new SessionController();

        // Initialize performance logger
        mPerfLogger = new PerfLogger();

        // Initialize repository for database operations
        mRepository = new AARRepository(this);

        // Load speaker name from preferences
        android.content.SharedPreferences prefs = getSharedPreferences(Constants.PREF_FILE, MODE_PRIVATE);
        String savedName = prefs.getString(Constants.PREF_SPEAKER_NAME, "");
        if (savedName != null && !savedName.isEmpty()) {
            mSpeakerName = savedName;
        } else {
            mSpeakerName = android.os.Build.MODEL;
        }

        // Initialize meeting manager for multi-device support
        mMeetingManager = new MeetingManager(this, mSpeakerName);

        // Create worker threads
        initializeWorkerThreads();

        // Register broadcast receiver for stop action from notification
        IntentFilter filter = new IntentFilter(Constants.ACTION_STOP_RECORDING);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mStopReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(mStopReceiver, filter);
        }

        // PERF: Pre-warm OpenCL and load models in background
        // RISK: OpenCL cold-start takes 2-4 seconds. We pay this cost here at service start,
        // not during user action. Start button is blocked until warm-up completes.
        startModelInitialization();

        Log.i(TAG, "[ARIASessionService.onCreate] Service created. Beginning model initialization...");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "[ARIASessionService.onStartCommand] intent=" + (intent != null ? intent.getAction() : "null"));

        if (intent != null) {
            String action = intent.getAction();
            if (Constants.ACTION_START_RECORDING.equals(action)) {
                startRecording();
            } else if (Constants.ACTION_STOP_RECORDING.equals(action)) {
                stopRecordingAndSummarize();
            } else if (Constants.ACTION_CANCEL_SESSION.equals(action)) {
                cancelSession();
            } else if (Constants.ACTION_REGENERATE_SUMMARY.equals(action)) {
                String sessionUuid = intent.getStringExtra(Constants.EXTRA_SESSION_UUID);
                if (sessionUuid != null) {
                    regenerateSummary(sessionUuid);
                } else {
                    Log.e(TAG, "[ARIASessionService.onStartCommand] REGENERATE action missing session UUID");
                }
            }
        }

        // WHY: START_STICKY ensures service restarts if killed by system
        // However, state will be lost — recording cannot resume automatically
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "[ARIASessionService.onBind] Client binding...");
        return mBinder;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "[ARIASessionService.onDestroy] Service destroying...");

        // Unregister broadcast receiver
        try {
            unregisterReceiver(mStopReceiver);
        } catch (IllegalArgumentException e) {
            // WHY: Receiver may not be registered if onCreate failed early
            Log.w(TAG, "[ARIASessionService.onDestroy] Receiver not registered: " + e.getMessage());
        }

        // Stop any active recording
        if (mAudioCaptureManager != null) {
            mAudioCaptureManager.stopCapture();
        }

        // End meeting if active
        if (mMeetingManager != null) {
            mMeetingManager.endMeeting();
        }

        // Release native engines
        releaseNativeEngines();

        // Shutdown worker threads
        shutdownWorkerThreads();

        Log.i(TAG, "[ARIASessionService.onDestroy] Service destroyed");
        super.onDestroy();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // THREAD INITIALIZATION
    // ═══════════════════════════════════════════════════════════════════════════

    private void initializeWorkerThreads() {
        // Audio capture thread — real-time AudioRecord loop
        mAudioCaptureThread = new HandlerThread("aria-audio-capture");
        mAudioCaptureThread.start();
        mAudioCaptureHandler = new Handler(mAudioCaptureThread.getLooper());

        // ASR worker thread — blocking whisper.cpp inference
        mAsrWorkerThread = new HandlerThread("aria-asr-worker");
        mAsrWorkerThread.start();
        mAsrWorkerHandler = new Handler(mAsrWorkerThread.getLooper());

        // LLM worker thread — blocking llama.cpp inference
        mLlmWorkerThread = new HandlerThread("aria-llm-worker");
        mLlmWorkerThread.start();
        mLlmWorkerHandler = new Handler(mLlmWorkerThread.getLooper());

        Log.d(TAG, "[ARIASessionService.initializeWorkerThreads] Worker threads initialized");
    }

    private void shutdownWorkerThreads() {
        if (mAudioCaptureThread != null) {
            mAudioCaptureThread.quitSafely();
            try { mAudioCaptureThread.join(1000); } catch (InterruptedException ignored) {}
            mAudioCaptureThread = null;
            mAudioCaptureHandler = null;
        }
        if (mAsrWorkerThread != null) {
            mAsrWorkerThread.quitSafely();
            try { mAsrWorkerThread.join(1000); } catch (InterruptedException ignored) {}
            mAsrWorkerThread = null;
            mAsrWorkerHandler = null;
        }
        if (mLlmWorkerThread != null) {
            mLlmWorkerThread.quitSafely();
            try { mLlmWorkerThread.join(1000); } catch (InterruptedException ignored) {}
            mLlmWorkerThread = null;
            mLlmWorkerHandler = null;
        }
        Log.d(TAG, "[ARIASessionService.shutdownWorkerThreads] Worker threads shutdown");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MODEL INITIALIZATION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Starts background initialization of Whisper and VAD engines.
     * Called from onCreate(). LLM loading is DEFERRED until summarization is needed.
     *
     * <p>WHY deferred LLM: Loading the 2GB+ Llama model with GPU (OpenCL SPIR-V compile)
     * at startup consumes so much RAM that Android's LMK kills the process (SIGKILL).
     * By deferring LLM load until after recording, the app starts reliably and transcription
     * works immediately. LLM loads only when summarization is actually requested.
     */
    private void startModelInitialization() {
        if (mIsInitializing) {
            Log.w(TAG, "[ARIASessionService.startModelInitialization] Already initializing, skipping");
            return;
        }
        mIsInitializing = true;

        // WHY: Post to ASR worker — only loading Whisper + VAD (fast, <500ms)
        if (mAsrWorkerHandler != null) {
            mAsrWorkerHandler.post(this::initializeAsrModelsOnWorker);
        }
    }

    /**
     * Performs ASR model initialization on worker thread.
     * Only loads Whisper + VAD. LLM is loaded lazily via ensureLlamaLoaded().
     */
    @WorkerThread
    private void initializeAsrModelsOnWorker() {
        long startTime = System.currentTimeMillis();
        Log.i(TAG, "[ARIASessionService.initializeAsrModelsOnWorker] Starting ASR model initialization...");

        try {
            // Initialize Whisper engine
            initializeWhisperEngine();

            // Initialize Silero VAD
            initializeSileroVAD();

            long duration = System.currentTimeMillis() - startTime;
            Log.i(TAG, "[ARIASessionService.initializeAsrModelsOnWorker] ASR init complete in " + duration + "ms");

            // WHY: Models ready = Whisper loaded. LLM is optional for recording.
            if (mMainHandler != null && mSessionController != null) {
                mMainHandler.post(() -> {
                    mSessionController.setModelsReady(mIsWhisperLoaded);
                });
            }

        } catch (Exception e) {
            Log.e(TAG, "[ARIASessionService.initializeAsrModelsOnWorker] Initialization failed: " + e.getMessage(), e);
        } finally {
            mIsInitializing = false;
        }
    }

    /**
     * Lazily loads the LLM engine if not already loaded.
     * Called on LLM worker thread before summarization begins.
     *
     * @return true if LLM is loaded and ready for inference
     */
    @WorkerThread
    private boolean ensureLlamaLoaded() {
        if (mIsLlamaLoaded && mLlamaEngine != null) {
            return true;
        }
        Log.i(TAG, "[ARIASessionService.ensureLlamaLoaded] Loading LLM on demand...");
        initializeLlamaEngine();
        return mIsLlamaLoaded && mLlamaEngine != null;
    }

    @WorkerThread
    private void initializeWhisperEngine() {
        // WHY: Copy whisper model from assets to filesDir if not already present
        ModelFileManager.copyWhisperModelIfNeeded(this);

        String modelPath = ModelFileManager.getWhisperModelPath(this);
        if (modelPath == null || !ModelFileManager.isWhisperModelReady(this)) {
            Log.e(TAG, "[ARIASessionService.initializeWhisperEngine] Whisper model not available");
            return;
        }

        // QNN: Extract context binary from assets and pass path to loadModel (QD-7)
        // WHY: Native code needs an absolute filesystem path — can't read from APK ZIP.
        String qnnContextPath = ModelFileManager.getQnnContextPath(this);

        mWhisperEngine = new WhisperEngine();
        int result = mWhisperEngine.loadModel(modelPath, qnnContextPath);
        if (result == 0) {
            mIsWhisperLoaded = true;
            Log.i(TAG, "[ARIASessionService.initializeWhisperEngine] Whisper model loaded successfully");
        } else {
            Log.e(TAG, "[ARIASessionService.initializeWhisperEngine] Whisper load failed with code: " + result);
        }
    }

    @WorkerThread
    private void initializeSileroVAD() {
        try {
            mSileroVAD = new SileroVAD(this);
            // WHY: SileroVAD requires explicit loadModel() after construction
            if (mSileroVAD.loadModel()) {
                Log.i(TAG, "[ARIASessionService.initializeSileroVAD] Silero VAD initialized and loaded");
            } else {
                Log.w(TAG, "[ARIASessionService.initializeSileroVAD] VAD model load failed - continuing without VAD");
            }
        } catch (Exception e) {
            Log.e(TAG, "[ARIASessionService.initializeSileroVAD] VAD init failed: " + e.getMessage(), e);
        }
    }

    @WorkerThread
    private void initializeLlamaEngine() {
        // WHY: Check if LLM model was downloaded by user
        if (!ModelFileManager.isLlmModelReady(this)) {
            Log.w(TAG, "[ARIASessionService.initializeLlamaEngine] LLM model not downloaded yet");
            return;
        }

        String modelPath = ModelFileManager.getLlmModelPath(this);
        if (modelPath == null) {
            Log.e(TAG, "[ARIASessionService.initializeLlamaEngine] LLM model path is null");
            return;
        }

        // WHY: Crash sentinel — if a previous native load crashed the process,
        // skip loading to prevent boot-loop. User can reset via Settings.
        SharedPreferences prefs = getSharedPreferences("aria_llm_guard", MODE_PRIVATE);
        int crashCount = prefs.getInt("llm_load_crash_count", 0);
        if (crashCount >= 2) {
            Log.e(TAG, "[ARIASessionService.initializeLlamaEngine] LLM loading disabled — "
                    + crashCount + " previous native crashes detected. "
                    + "Clear app data or reinstall to retry.");
            return;
        }

        // Set sentinel BEFORE native call (synchronous write to survive process death)
        prefs.edit().putInt("llm_load_crash_count", crashCount + 1).commit();

        mLlamaEngine = new LlamaEngine();

        // WHY: Validate GGUF file header before loading to catch file access issues
        int validationResult = mLlamaEngine.validateGgufFile(modelPath);
        if (validationResult != 0) {
            Log.e(TAG, "[ARIASessionService.initializeLlamaEngine] GGUF validation failed (code: "
                    + validationResult + ") — skipping model load");
            // Clear sentinel since we didn't crash
            prefs.edit().putInt("llm_load_crash_count", 0).commit();
            return;
        }

        // PERF: OpenCL init happens here — 2-4 seconds on first call
        long openclStart = System.currentTimeMillis();
        int result = mLlamaEngine.loadModel(modelPath, Constants.LLM_GPU_LAYERS, Constants.LLM_CONTEXT_TOKENS);

        if (result == 0) {
            mIsLlamaLoaded = true;
            long openclDuration = System.currentTimeMillis() - openclStart;
            Log.i(TAG, "[ARIASessionService.initializeLlamaEngine] Llama model loaded with GPU in " + openclDuration + "ms");

            // Clear crash sentinel on success
            prefs.edit().putInt("llm_load_crash_count", 0).commit();

            if (mPerfLogger != null) {
                mPerfLogger.logOpenClInit(openclDuration);
            }
        } else {
            // RISK: GPU init failed — try CPU fallback
            Log.w(TAG, "[ARIASessionService.initializeLlamaEngine] GPU init failed (code: " + result + "), trying CPU fallback...");

            result = mLlamaEngine.loadModel(modelPath, Constants.LLM_CPU_FALLBACK_LAYERS, Constants.LLM_CONTEXT_TOKENS);
            if (result == 0) {
                mIsLlamaLoaded = true;
                Log.i(TAG, "[ARIASessionService.initializeLlamaEngine] Llama model loaded with CPU fallback");
                // Clear crash sentinel on success
                prefs.edit().putInt("llm_load_crash_count", 0).commit();
            } else {
                Log.e(TAG, "[ARIASessionService.initializeLlamaEngine] CPU fallback also failed with code: " + result);
                // Clear sentinel — we got a clean error code, not a crash
                prefs.edit().putInt("llm_load_crash_count", 0).commit();
            }
        }
    }

    private void releaseNativeEngines() {
        if (mWhisperEngine != null) {
            mWhisperEngine.release();
            mWhisperEngine = null;
            mIsWhisperLoaded = false;
        }
        if (mLlamaEngine != null) {
            mLlamaEngine.release();
            mLlamaEngine = null;
            mIsLlamaLoaded = false;
        }
        if (mSileroVAD != null) {
            mSileroVAD.release();
            mSileroVAD = null;
        }
        Log.d(TAG, "[ARIASessionService.releaseNativeEngines] Native engines released");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RECORDING CONTROL
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Starts a new recording session.
     * Called from UI when user taps START button.
     */
    @MainThread
    public void startRecording() {
        Log.i(TAG, "[ARIASessionService.startRecording] Start recording requested");

        if (mSessionController == null) {
            Log.e(TAG, "[ARIASessionService.startRecording] SessionController is null");
            return;
        }

        // Guard: Check Whisper is loaded (required for transcription)
        // LLM is loaded lazily when summarization is needed — NOT required for recording
        if (!mIsWhisperLoaded) {
            Log.e(TAG, "[ARIASessionService.startRecording] Whisper not ready");
            mSessionController.postError("Transcription engine not ready. Please wait.");
            return;
        }

        // WHY: Auto-reset from terminal states (COMPLETE/ERROR) so user can start
        // a new recording without manually resetting. Without this, the state machine
        // gets stuck because COMPLETE→INITIALIZING is not a valid transition.
        SessionState currentState = mSessionController.getCurrentState();
        if (currentState == SessionState.COMPLETE || currentState == SessionState.ERROR) {
            Log.i(TAG, "[ARIASessionService.startRecording] Auto-resetting from " + currentState + " → IDLE");
            reset();
        }

        // Guard: Check state transition is valid
        if (!mSessionController.getCurrentState().canTransitionTo(SessionState.INITIALIZING)) {
            Log.w(TAG, "[ARIASessionService.startRecording] Invalid state transition from " + mSessionController.getCurrentState());
            return;
        }

        // Transition to INITIALIZING
        mSessionController.transitionTo(SessionState.INITIALIZING);

        // Start foreground service with recording notification
        // RISK: This MUST happen before audio recording starts on Android 14
        startForeground(Constants.NOTIFICATION_ID, NotificationHelper.buildRecordingNotification(this));

        // Initialize recording components and start capture
        if (mAudioCaptureHandler != null) {
            mAudioCaptureHandler.post(this::initializeAndStartRecording);
        }
    }

    @WorkerThread
    private void initializeAndStartRecording() {
        try {
            // WHY: Generate session UUID at recording start for database tracking
            mCurrentSessionUuid = java.util.UUID.randomUUID().toString();
            mWhisperCallCount = 0;  // Reset call counter for this session
            mWindowsPosted = 0;     // Reset window counter for this session
            mLastRtf = 0.0f;        // Reset thermal tracking for this session
            Log.d(TAG, "[ARIASessionService.initializeAndStartRecording] New session: " + mCurrentSessionUuid);

            // WHY: Create session record in database
            if (mRepository != null) {
                mRepository.createSession(mCurrentSessionUuid);
            }

            // WHY: Initialize sliding window buffer with default 2s window, 1s stride
            mSlidingWindowBuffer = new SlidingWindowBuffer();
            if (mSileroVAD != null) {
                mSlidingWindowBuffer.setVad(mSileroVAD);
            }

            // Initialize transcript accumulator
            mTranscriptAccumulator = new TranscriptAccumulator();

            // Reload speaker name (may have changed in settings since service started)
            // WHY: Must reload BEFORE setting on accumulator so the latest name is used
            android.content.SharedPreferences prefs = getSharedPreferences(Constants.PREF_FILE, MODE_PRIVATE);
            String savedName = prefs.getString(Constants.PREF_SPEAKER_NAME, "");
            if (savedName != null && !savedName.isEmpty()) {
                mSpeakerName = savedName;
            }

            // WHY: Set speaker name on accumulator for transcript attribution.
            // Single-device: shows "Stephen: Hello..." in transcript.
            // Multi-device: MeetingManager handles per-speaker attribution separately.
            mTranscriptAccumulator.setSpeakerName(mSpeakerName);

            // Create meeting room for multi-device support
            if (mMeetingManager != null) {
                mMeetingManager.setCallback(new MeetingManager.MeetingCallback() {
                    @Override
                    public void onParticipantCountChanged(int count, @NonNull java.util.List<String> names) {
                        if (mSessionController != null) {
                            mSessionController.updateParticipantCount(count);
                        }
                    }

                    @Override
                    public void onRemoteSegmentReceived(@NonNull String speaker, @NonNull String text) {
                        // Remote segments are aggregated by MeetingManager
                        // Update transcript display with aggregated multi-speaker transcript
                        String aggregated = mMeetingManager.getAggregatedTranscript();
                        if (mMainHandler != null && mSessionController != null) {
                            mMainHandler.post(() -> mSessionController.updateTranscript(aggregated));
                        }
                    }

                    @Override
                    public void onMeetingEnded() {
                        Log.i(TAG, "[ARIASessionService] Meeting ended by remote event");
                    }

                    @Override
                    public void onError(@NonNull String error) {
                        Log.e(TAG, "[ARIASessionService] Meeting error: " + error);
                    }
                });
                mMeetingManager.createMeeting();
            }

            // WHY: AudioCaptureManager requires initialize() before startCapture()
            mAudioCaptureManager = new AudioCaptureManager(this);
            if (!mAudioCaptureManager.initialize()) {
                handleRecordingError("Failed to initialize audio capture");
                return;
            }

            // WHY: Callback delivers short[] PCM audio; we add to sliding window buffer
            boolean started = mAudioCaptureManager.startCapture(new AudioCaptureManager.AudioCallback() {
                @Override
                public void onAudioAvailable(@NonNull short[] audioData, int sampleCount) {
                    processAudioChunk(audioData, sampleCount);
                }
            });

            if (started) {
                // Transition to RECORDING state on main thread
                if (mMainHandler != null && mSessionController != null) {
                    mMainHandler.post(() -> mSessionController.transitionTo(SessionState.RECORDING));
                }
                Log.i(TAG, "[ARIASessionService.initializeAndStartRecording] Recording started");
            } else {
                handleRecordingError("Failed to start audio capture");
            }

        } catch (Exception e) {
            handleRecordingError("Recording initialization failed: " + e.getMessage());
        }
    }

    /**
     * Stops recording and begins summarization.
     * Called from UI when user taps STOP button.
     */
    @MainThread
    public void stopRecordingAndSummarize() {
        Log.i(TAG, "[ARIASessionService.stopRecordingAndSummarize] Stop requested. Stats: windowsPosted=" + mWindowsPosted + ", whisperCallsCompleted=" + mWhisperCallCount);

        if (mSessionController == null) {
            return;
        }

        SessionState currentState = mSessionController.getCurrentState();
        if (currentState != SessionState.RECORDING) {
            Log.w(TAG, "[ARIASessionService.stopRecordingAndSummarize] Not in RECORDING state: " + currentState);
            return;
        }

        // Stop audio capture — no new samples will be added to the buffer
        if (mAudioCaptureManager != null) {
            mAudioCaptureManager.stopCapture();
        }

        // End meeting (disconnects participants, unregisters NSD)
        if (mMeetingManager != null) {
            mMeetingManager.endMeeting();
        }

        // Transition to SUMMARIZING — shows "Processing..." UI while ASR queue drains
        mSessionController.transitionTo(SessionState.SUMMARIZING);

        // Update notification
        android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(Constants.NOTIFICATION_ID, NotificationHelper.buildSummarizingNotification(this));
        }

        // WHY: Drain the ASR handler queue before grabbing the transcript.
        // whisper-small.en runs at RTF ~1.5x, so the ASR handler may have
        // many pending windows queued. If we grab the transcript now, we lose
        // all audio in those queued windows. Instead, post a sentinel task to
        // the ASR handler — it runs AFTER all pending whisper inferences complete.
        // Only then do we collect the full transcript and start summarization.
        Log.i(TAG, "[ARIASessionService.stopRecordingAndSummarize] Draining ASR queue (windowsPosted=" + mWindowsPosted + ", processed=" + mWhisperCallCount + ")");

        if (mAsrWorkerHandler != null) {
            mAsrWorkerHandler.post(() -> {
                // This runs on the ASR worker thread AFTER all pending whisper inferences
                Log.i(TAG, "[ARIASessionService] ASR queue drained. Total whisper calls: " + mWhisperCallCount);

                // Collect final transcript on main thread, then start summarization
                if (mMainHandler != null) {
                    mMainHandler.post(() -> collectTranscriptAndSummarize());
                }
            });
        } else {
            // Fallback: no ASR handler, summarize immediately
            collectTranscriptAndSummarize();
        }
    }

    /**
     * Collects the final transcript (after ASR queue is drained) and starts summarization.
     * Called from the ASR drain sentinel via main thread post.
     */
    @MainThread
    private void collectTranscriptAndSummarize() {
        // Use aggregated multi-speaker transcript if meeting is active, otherwise local
        String transcript;
        if (mMeetingManager != null && mMeetingManager.getRole() == MeetingManager.Role.OWNER
                && mMeetingManager.getTotalParticipantCount() > 1) {
            transcript = mMeetingManager.getAggregatedTranscript();
        } else {
            transcript = mTranscriptAccumulator != null ? mTranscriptAccumulator.getFullTranscript() : "";
        }
        // WHY: "".split("\\s+").length returns 1 (empty element), so handle empty case
        int wordCount = transcript.trim().isEmpty() ? 0 : transcript.trim().split("\\s+").length;

        Log.i(TAG, "[ARIASessionService.collectTranscriptAndSummarize] Transcript: " + wordCount + " words (windowsPosted=" + mWindowsPosted + ", whisperCalls=" + mWhisperCallCount + ")");

        if (wordCount < Constants.MIN_TRANSCRIPT_WORDS) {
            Log.w(TAG, "[ARIASessionService.collectTranscriptAndSummarize] Transcript too short: " + wordCount + " words (minimum: " + Constants.MIN_TRANSCRIPT_WORDS + ")");
            if (mSessionController != null) {
                mSessionController.postError("Session too short. Please record more content.");
                mSessionController.transitionTo(SessionState.IDLE);
            }
            stopForeground(STOP_FOREGROUND_REMOVE);
            return;
        }

        // Start summarization on LLM worker thread (lazy-loads LLM if needed)
        if (mLlmWorkerHandler != null) {
            final String finalTranscript = transcript;
            mLlmWorkerHandler.post(() -> runSummarization(finalTranscript));
        }
    }

    /**
     * Stops recording without summarization.
     * Alias for UI compatibility with RecordingFragment.
     */
    @MainThread
    public void stopRecording() {
        stopRecordingAndSummarize();
    }

    /**
     * Cancels recording without saving.
     * Alias for UI compatibility with RecordingFragment.
     */
    @MainThread
    public void cancelRecording() {
        cancelSession();
    }

    /**
     * Cancels the current session and returns to IDLE.
     * If the session is in SUMMARIZING state, requests LLM cancellation first.
     */
    @MainThread
    public void cancelSession() {
        Log.i(TAG, "[ARIASessionService.cancelSession] Cancel requested");

        // WHY: If LLM inference is running, signal it to stop between tokens.
        // The -6 return code is handled in runSummarization() which calls
        // cleanupAfterCancellation() to transition to IDLE.
        SessionState currentState = mSessionController != null ? mSessionController.getCurrentState() : null;
        if (currentState == SessionState.SUMMARIZING && mLlamaEngine != null) {
            Log.i(TAG, "[ARIASessionService.cancelSession] Requesting LLM cancellation");
            mLlamaEngine.requestCancellation();
            // WHY: Don't transition to IDLE here — let runSummarization() handle the -6
            // return code and clean up properly on the LLM worker thread.
            // But we do stop foreground and clean up session data immediately.
            mCurrentSessionUuid = null;
            if (mTranscriptAccumulator != null) {
                mTranscriptAccumulator.reset();
            }
            if (mSlidingWindowBuffer != null) {
                mSlidingWindowBuffer.reset();
            }
            stopForeground(STOP_FOREGROUND_REMOVE);
            return;
        }

        // Stop audio capture
        if (mAudioCaptureManager != null) {
            mAudioCaptureManager.stopCapture();
        }

        // End meeting if active
        if (mMeetingManager != null) {
            mMeetingManager.endMeeting();
        }

        // WHY: Clean up all session data so a new recording starts fresh.
        // Previously only state was reset, leaving stale UUID, transcript, and
        // sliding window data — causing "pulls the old recorded one" behavior.
        mCurrentSessionUuid = null;

        if (mTranscriptAccumulator != null) {
            mTranscriptAccumulator.reset();
        }

        if (mSlidingWindowBuffer != null) {
            mSlidingWindowBuffer.reset();
        }

        if (mSessionController != null) {
            mSessionController.transitionTo(SessionState.IDLE);
        }

        stopForeground(STOP_FOREGROUND_REMOVE);
    }

    /**
     * Cleans up after LLM cancellation (-6 return code).
     * Called from runSummarization() on the LLM worker thread.
     */
    @WorkerThread
    private void cleanupAfterCancellation() {
        // WHY: Session data (UUID, transcript, buffer) was already cleared by cancelSession()
        // on the main thread. We only need to transition to IDLE here.
        if (mMainHandler != null && mSessionController != null) {
            mMainHandler.post(() -> mSessionController.transitionTo(SessionState.IDLE));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // AUDIO PROCESSING PIPELINE
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Processes an audio chunk through sliding window buffer with VAD gating.
     * Called on aria-audio-capture thread.
     *
     * <p>WHY: AudioCaptureManager delivers short[] PCM. SlidingWindowBuffer accumulates
     * samples and returns float[] windows for Whisper when ready.
     *
     * @param audioData   Raw PCM samples from AudioRecord
     * @param sampleCount Number of valid samples in array
     */
    @WorkerThread
    private void processAudioChunk(@NonNull short[] audioData, int sampleCount) {
        if (mSlidingWindowBuffer == null) {
            return;
        }

        // WHY: Add samples to sliding window buffer; VAD gating happens inside getNextWindow()
        mSlidingWindowBuffer.addSamples(audioData, sampleCount);

        // WHY: Check if window is ready for transcription
        while (mSlidingWindowBuffer.hasWindow()) {
            // WHY: Capture timing BEFORE getNextWindow() — values change after emission
            final long windowStartMs = mSlidingWindowBuffer.getWindowStartMs();
            final long windowEndMs = mSlidingWindowBuffer.getWindowEndMs();

            // WHY: getNextWindow() returns null if VAD filters out (no speech detected)
            float[] window = mSlidingWindowBuffer.getNextWindow();
            if (window != null && mAsrWorkerHandler != null) {
                // WHY: Copy window for thread safety — worker thread may process while we continue
                final float[] windowCopy = window.clone();
                int windowNum = ++mWindowsPosted;
                Log.i(TAG, "[ARIASessionService.processAudioChunk] Posting window #" + windowNum + " [" + windowStartMs + "-" + windowEndMs + "ms] to ASR handler (processed=" + mWhisperCallCount + ")");
                mAsrWorkerHandler.post(() -> runWhisperInference(windowCopy, windowStartMs, windowEndMs));
            }
        }
    }

    /**
     * Runs Whisper inference on audio window.
     * PERF: Target RTF ≤ 0.5x. Called on aria-asr-worker thread.
     *
     * @param audioData     Float audio window, normalized [-1.0, 1.0]
     * @param audioStartMs  Window start offset from session start
     * @param audioEndMs    Window end offset from session start
     */
    @WorkerThread
    private void runWhisperInference(@NonNull float[] audioData, long audioStartMs, long audioEndMs) {
        int callNumber = ++mWhisperCallCount;
        Log.i(TAG, "[ARIASessionService.runWhisperInference] >>> START call #" + callNumber + " (audioData.length=" + audioData.length + ")");

        if (mWhisperEngine == null || !mIsWhisperLoaded) {
            Log.e(TAG, "[ARIASessionService.runWhisperInference] SKIP call #" + callNumber + " - engine not ready");
            return;
        }

        // WHY: Log audio amplitude to verify we're getting real audio, not silence/zeros
        float maxAmp = 0;
        float sumSquares = 0;
        for (float sample : audioData) {
            maxAmp = Math.max(maxAmp, Math.abs(sample));
            sumSquares += sample * sample;
        }
        float rms = (float) Math.sqrt(sumSquares / audioData.length);
        Log.d(TAG, "[ARIASessionService.runWhisperInference] call #" + callNumber + " audio stats: maxAmp=" + String.format("%.4f", maxAmp) + ", rms=" + String.format("%.4f", rms));

        long startTime = System.currentTimeMillis();

        // PERF: This is a blocking native call — must be on worker thread
        Log.d(TAG, "[ARIASessionService.runWhisperInference] call #" + callNumber + " calling native transcribe...");
        String transcript = mWhisperEngine.transcribe(audioData);
        Log.d(TAG, "[ARIASessionService.runWhisperInference] call #" + callNumber + " native returned");

        long inferenceMs = System.currentTimeMillis() - startTime;
        float audioDurationMs = (audioData.length / (float) Constants.AUDIO_SAMPLE_RATE_HZ) * 1000;
        float rtf = inferenceMs / audioDurationMs;

        // Log performance
        if (mPerfLogger != null) {
            mPerfLogger.logWhisperRtf(rtf, inferenceMs, (long) audioDurationMs);
        }

        // Process transcript if valid
        boolean isValid = transcript != null && !transcript.isEmpty() && !WhisperEngine.isHallucination(transcript);
        Log.i(TAG, "[ARIASessionService.runWhisperInference] call #" + callNumber + " completed in " + inferenceMs + "ms, RTF=" + String.format("%.2f", rtf) + ", transcript=\"" + (transcript != null ? transcript : "null") + "\", isValid=" + isValid);

        if (isValid) {
            if (mTranscriptAccumulator != null) {
                mTranscriptAccumulator.addSegment(transcript, audioStartMs, audioEndMs, inferenceMs, rtf);

                // Add speaker-attributed segment to meeting manager for multi-device aggregation
                if (mMeetingManager != null && mMeetingManager.getRole() == MeetingManager.Role.OWNER) {
                    mMeetingManager.addLocalSegment(TextPostProcessor.processSegment(transcript));
                }

                // Update UI with transcript (use aggregated if meeting active, else local)
                String displayTranscript;
                if (mMeetingManager != null && mMeetingManager.getRole() == MeetingManager.Role.OWNER
                        && mMeetingManager.getTotalParticipantCount() > 1) {
                    displayTranscript = mMeetingManager.getAggregatedTranscript();
                } else {
                    displayTranscript = mTranscriptAccumulator.getFullTranscript();
                }
                Log.d(TAG, "[ARIASessionService.runWhisperInference] call #" + callNumber + " transcript length: " + displayTranscript.length());
                final String finalTranscript = displayTranscript;
                if (mMainHandler != null && mSessionController != null) {
                    mMainHandler.post(() -> mSessionController.updateTranscript(finalTranscript));
                }
            }
        }

        // QNN: Adaptive thermal yield — sleep proportional to observed RTF.
        // WHY: NPU + CPU generate continuous heat during inference. Without yield,
        // thermals saturate at ~minute 4 and RTF permanently degrades from 0.38x to 0.52x.
        // Using RTF as a thermal proxy avoids needing root access to read /sys/class/thermal/.
        mLastRtf = rtf;
        int yieldMs = calculateAdaptiveYield(rtf);
        if (yieldMs > 0) {
            try {
                Log.d(TAG, "[ARIASessionService.runWhisperInference] Thermal yield: " + yieldMs + "ms (RTF=" + String.format("%.2f", rtf) + ")");
                Thread.sleep(yieldMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        Log.i(TAG, "[ARIASessionService.runWhisperInference] <<< END call #" + callNumber);
    }

    /**
     * Calculates adaptive inter-window yield based on current RTF.
     *
     * <p>QNN: RTF increases as the Snapdragon 8 Gen 3 throttles. By ramping yield
     * proportionally, we give the V75 NPU and Cortex-X4 time to cool between windows,
     * preventing the permanent RTF degradation observed at ~minute 4 of sustained recording.
     *
     * <p>Uses linear interpolation between COOL and HOT RTF thresholds to produce a
     * smooth yield curve. This avoids the oscillation that step functions cause when
     * RTF hovers near a boundary, and mirrors actual thermal dissipation which is
     * continuous, not discrete.
     *
     * @param rtf Current window's Real-Time Factor (inference_time / audio_duration)
     * @return Yield duration in milliseconds
     */
    private int calculateAdaptiveYield(float rtf) {
        // PERF: Below cool threshold — device is running optimally, minimal yield
        if (rtf <= Constants.THERMAL_RTF_COOL) {
            return Constants.INTER_WINDOW_YIELD_MIN_MS;
        }
        // PERF: Above hot threshold — device is throttling, maximum recovery yield
        if (rtf >= Constants.THERMAL_RTF_HOT) {
            return Constants.INTER_WINDOW_YIELD_MAX_MS;
        }
        // PERF: Linear interpolation between cool and hot thresholds.
        // WHY linear: thermal dissipation is roughly proportional to temperature delta,
        // so a linear yield ramp matches the physics. Exponential would over-cool in
        // the warm zone and under-cool in the hot zone.
        float t = (rtf - Constants.THERMAL_RTF_COOL)
                / (Constants.THERMAL_RTF_HOT - Constants.THERMAL_RTF_COOL);
        return Constants.INTER_WINDOW_YIELD_MIN_MS
                + (int) (t * (Constants.INTER_WINDOW_YIELD_MAX_MS - Constants.INTER_WINDOW_YIELD_MIN_MS));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LLM SUMMARIZATION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Runs LLM summarization on the full transcript.
     * PERF: Target TTFT ≤ 2.0s, decode ≥ 20 tok/s. Called on aria-llm-worker thread.
     */
    @WorkerThread
    private void runSummarization(@NonNull String transcript) {
        Log.i(TAG, "[ARIASessionService.runSummarization] Starting summarization. Transcript length: " + transcript.length());

        // WHY: Release ALL ASR-phase native resources BEFORE loading LLM to prevent
        // OOM kill and reduce SoC thermal load. whisper-small.en holds ~600MB,
        // SileroVAD holds ~50MB (ONNX Runtime + thread pool). Llama 3.1 8B needs
        // ~5.5GB peak on GPU. Combined, they exceed Android's per-process memory
        // limit → SIGKILL. Neither Whisper nor VAD is needed during summarization.
        if (mWhisperEngine != null) {
            Log.i(TAG, "[ARIASessionService.runSummarization] Releasing Whisper engine to free RAM for LLM");
            mWhisperEngine.release();
            mWhisperEngine = null;
            mIsWhisperLoaded = false;
        }

        // WHY: SileroVAD's ONNX Runtime holds ~50MB of native memory + thread pool.
        // Not needed during summarization — release to maximize headroom for 8B LLM.
        if (mSileroVAD != null) {
            Log.i(TAG, "[ARIASessionService.runSummarization] Releasing SileroVAD to free RAM for LLM");
            mSileroVAD.release();
            mSileroVAD = null;
        }

        // WHY: Hint GC to reclaim ~650MB of freed native heap wrappers (Whisper + VAD)
        // and Java-side references BEFORE LLM allocates ~5.5GB on GPU. System.gc()
        // is a hint (not a guarantee), but ART reliably honors it when called from
        // a foreground service.
        System.gc();

        // WHY: Adaptive thermal cooldown — after 5-15 minutes of NPU/CPU inference
        // during recording, the SoC junction temperature is elevated. Immediately
        // slamming the Adreno 750 GPU to 100% for LLM model load (OpenCL SPIR-V
        // compile + 4.6GB weight transfer) can push the SoC past the Thermal
        // Management Unit's emergency threshold (~105°C on Snapdragon 8 Gen 3),
        // causing a full device reboot. A 3-8 second cooldown allows the vapor
        // chamber to dissipate 5-10°C before GPU ramps up.
        long cooldownMs = calculateThermalCooldownMs();
        Log.i(TAG, "[ARIASessionService.runSummarization] Thermal cooldown: " + cooldownMs + "ms");
        try {
            Thread.sleep(cooldownMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Lazy-load LLM if not already loaded (deferred from startup to save RAM)
        if (!ensureLlamaLoaded()) {
            Log.w(TAG, "[ARIASessionService.runSummarization] LLM not available - saving transcript without summary");

            // Save session with transcript only
            if (mRepository != null && mTranscriptAccumulator != null && mCurrentSessionUuid != null) {
                mRepository.saveSession(mCurrentSessionUuid, transcript, null, null);
            }
            if (mMainHandler != null && mSessionController != null) {
                mMainHandler.post(() -> {
                    mSessionController.updateTranscript(transcript);
                    mSessionController.transitionTo(SessionState.COMPLETE);
                });
            }
            stopForeground(STOP_FOREGROUND_REMOVE);
            return;
        }

        try {
            // Load selected template from preferences
            android.content.SharedPreferences templatePrefs = getSharedPreferences(Constants.PREF_FILE, MODE_PRIVATE);
            String templateKey = templatePrefs.getString(Constants.PREF_SUMMARY_TEMPLATE, "retrospective");
            com.stelliq.aria.llm.SummaryTemplate template = com.stelliq.aria.llm.SummaryTemplate.fromKey(templateKey);
            Log.i(TAG, "[ARIASessionService.runSummarization] Using template: " + template.displayName);

            // WHY: Safety net — truncate only if transcript would overflow 4096-token context.
            // Normal transcripts pass through unchanged. Only extreme-length recordings are trimmed.
            String safeTranscript = AARPromptBuilder.truncateToFit(transcript);

            // Build ChatML prompt using selected template
            String prompt = AARPromptBuilder.build(safeTranscript, template);

            // Accumulator for streaming tokens
            StringBuilder outputBuilder = new StringBuilder();
            long[] ttftHolder = {0};  // WHY: Array to capture TTFT from callback
            long[] lastStreamUpdate = {0};  // WHY: Throttle UI updates (see below)
            long startTime = System.currentTimeMillis();

            // PERF: This is a blocking native call with streaming callback
            int result = mLlamaEngine.complete(prompt, new LlamaEngine.TokenCallback() {
                @Override
                public void onToken(@NonNull String token, boolean isDone) {
                    // Capture TTFT on first token
                    if (outputBuilder.length() == 0) {
                        ttftHolder[0] = System.currentTimeMillis() - startTime;
                        if (mPerfLogger != null) {
                            mPerfLogger.logLlmTtft(ttftHolder[0]);
                        }
                    }

                    outputBuilder.append(token);

                    // WHY: Throttle UI updates to every ~80ms instead of every token.
                    // At 25-35 tok/s, unthrottled streaming fires 25-35 main-thread
                    // posts per second, each calling outputBuilder.toString() which
                    // copies the entire accumulated output (O(n) per call, O(n²) total).
                    // Each post also triggers LiveData → observer → TextView re-render,
                    // which competes with HWUI for GPU time. Throttling to ~12 updates/sec
                    // reduces main-thread pressure by ~60% while remaining perceptually
                    // smooth for text streaming. Always send on isDone so final output
                    // is displayed immediately.
                    long now = System.currentTimeMillis();
                    if (isDone || now - lastStreamUpdate[0] >= Constants.LLM_TOKEN_STREAM_THROTTLE_MS) {
                        lastStreamUpdate[0] = now;
                        if (mMainHandler != null && mSessionController != null) {
                            final String currentOutput = outputBuilder.toString();
                            mMainHandler.post(() -> mSessionController.updateStreamingOutput(currentOutput));
                        }
                    }
                }
            });

            // Log decode performance
            long totalTime = System.currentTimeMillis() - startTime;
            float tokensPerSec = mLlamaEngine.getLastDecodeTokensPerSec();
            if (mPerfLogger != null) {
                mPerfLogger.logLlmDecode(tokensPerSec, totalTime);
            }

            if (result == 0) {
                // WHY: Prepend "{" because AARPromptBuilder primes the assistant turn with "{"
                // to force JSON output. The model generates from after the "{", so we
                // reconstruct the full JSON object here before parsing.
                String llmOutput = "{\n" + outputBuilder.toString();
                AARJsonParser.ParseResult parseResult = AARJsonParser.parse(llmOutput);
                AARSummary summary = parseResult.summary;

                if (!parseResult.success) {
                    Log.w(TAG, "[ARIASessionService.runSummarization] Parse partial: " + parseResult.error);
                }

                // Complete session
                if (mMainHandler != null && mSessionController != null) {
                    mMainHandler.post(() -> {
                        mSessionController.setSummary(summary);
                        mSessionController.transitionTo(SessionState.COMPLETE);
                    });
                }

                // Save to database
                if (mRepository != null && mTranscriptAccumulator != null && mCurrentSessionUuid != null) {
                    mRepository.saveSession(
                            mCurrentSessionUuid,
                            mTranscriptAccumulator.getFullTranscript(),
                            llmOutput,
                            summary
                    );
                }

                Log.i(TAG, "[ARIASessionService.runSummarization] Summarization complete. TTFT=" + ttftHolder[0] + "ms, TPS=" + tokensPerSec);

                // WHY: Show completion notification so user knows the summary is ready.
                // User was navigated home when they hit Stop — this is their only signal.
                showCompletionNotification();
            } else if (result == -6) {
                // WHY: -6 = user cancelled via requestCancellation(). This is not an error —
                // the user deliberately interrupted processing. Clean up and return to IDLE
                // without posting an error message, so a new recording can start fresh.
                Log.i(TAG, "[ARIASessionService.runSummarization] Summarization cancelled by user");
                cleanupAfterCancellation();
            } else {
                handleSummarizationError("LLM inference failed with code: " + result);
            }

        } catch (Exception e) {
            Log.e(TAG, "[ARIASessionService.runSummarization] Exception: " + e.getMessage(), e);
            handleSummarizationError("Summarization failed: " + e.getMessage());
        } finally {
            // Stop foreground service
            stopForeground(STOP_FOREGROUND_REMOVE);

            // WHY: Whisper was released at the start of summarization to free RAM
            // for the LLM. Re-initialize it now so the user can start a new recording
            // without needing to restart the app. Runs on ASR worker thread.
            reInitializeWhisperAsync();
        }
    }

    /**
     * Re-loads whisper engine on the ASR worker thread after summarization
     * releases it to free RAM. Updates mModelsReadyLiveData when done.
     */
    private void reInitializeWhisperAsync() {
        if (mAsrWorkerHandler != null) {
            mAsrWorkerHandler.post(() -> {
                // WHY: Both Whisper and VAD are released in runSummarization() to free
                // RAM for the 8B LLM. Re-initialize both so the next recording works.
                if (!mIsWhisperLoaded && mWhisperEngine == null) {
                    Log.i(TAG, "[ARIASessionService.reInitializeWhisperAsync] Re-loading Whisper for next recording...");
                    initializeWhisperEngine();
                }
                if (mSileroVAD == null) {
                    Log.i(TAG, "[ARIASessionService.reInitializeWhisperAsync] Re-loading SileroVAD for next recording...");
                    initializeSileroVAD();
                }
                if (mMainHandler != null && mSessionController != null) {
                    mMainHandler.post(() -> mSessionController.setModelsReady(mIsWhisperLoaded));
                }
            });
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // THERMAL MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Calculates adaptive thermal cooldown duration based on SoC temperature.
     * Reads thermal zone from sysfs. Falls back to safe default if not readable.
     *
     * <p>WHY: After extended NPU+CPU recording, the SoC needs time to cool before
     * GPU ramps to 100% for LLM inference. Without cooldown, the thermal spike
     * from NPU→GPU transition can trigger hardware emergency shutdown (device reboot).
     *
     * @return Cooldown duration in milliseconds (2000-8000ms)
     */
    @WorkerThread
    private long calculateThermalCooldownMs() {
        int tempMilliC = readSocTemperatureMilliC();

        if (tempMilliC < 0) {
            // WHY: Can't read temperature — use conservative default (3s).
            // 3 seconds is sufficient for S24 Ultra's vapor chamber to dissipate
            // ~5°C at idle, which is typically the margin between normal and TMU trip.
            Log.i(TAG, "[ARIASessionService.calculateThermalCooldownMs] "
                    + "Thermal zone not readable — using default 3000ms");
            return 3000;
        }

        int tempC = tempMilliC / 1000;
        Log.i(TAG, "[ARIASessionService.calculateThermalCooldownMs] "
                + "SoC temperature: " + tempC + "°C");

        if (tempC < 40) {
            return 2000;   // Cool — minimal cooldown, GC settle time
        } else if (tempC < 50) {
            return 3000;   // Warm — standard cooldown
        } else if (tempC < 60) {
            return 5000;   // Hot — extended cooldown
        } else {
            return 8000;   // Very hot — aggressive cooldown to prevent TMU trip
        }
    }

    /**
     * Reads SoC die temperature from Linux sysfs thermal framework.
     *
     * <p>WHY: On Snapdragon 8 Gen 3, thermal_zone0 is typically the SoC/CPU cluster
     * die temperature. Samsung's SELinux policy on stock firmware usually allows read
     * access, but we try multiple zones as fallback since firmware versions may differ.
     *
     * @return Temperature in millidegrees Celsius (e.g., 45000 = 45°C), or -1 if not readable
     */
    @WorkerThread
    private int readSocTemperatureMilliC() {
        String[] thermalZones = {
                "/sys/class/thermal/thermal_zone0/temp",
                "/sys/class/thermal/thermal_zone1/temp",
                "/sys/class/thermal/thermal_zone3/temp"
        };

        for (String path : thermalZones) {
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.FileReader(path))) {
                String line = reader.readLine();
                if (line != null) {
                    int temp = Integer.parseInt(line.trim());
                    // WHY: Sanity check — valid SoC temperature range is 10°C-120°C.
                    // Values outside this range indicate a wrong thermal zone or corrupt read.
                    if (temp >= 10000 && temp <= 120000) {
                        return temp;
                    }
                }
            } catch (Exception e) {
                // WHY: SELinux denial or file not found — try next zone silently.
                // This is expected on some Samsung firmware versions.
            }
        }

        return -1;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ERROR HANDLING
    // ═══════════════════════════════════════════════════════════════════════════

    private void handleRecordingError(@NonNull String message) {
        Log.e(TAG, "[ARIASessionService.handleRecordingError] " + message);

        if (mMainHandler != null && mSessionController != null) {
            mMainHandler.post(() -> {
                mSessionController.postError(message);
                mSessionController.transitionTo(SessionState.IDLE);
            });
        }

        stopForeground(STOP_FOREGROUND_REMOVE);
    }

    private void handleSummarizationError(@NonNull String message) {
        Log.e(TAG, "[ARIASessionService.handleSummarizationError] " + message);

        if (mMainHandler != null && mSessionController != null) {
            mMainHandler.post(() -> {
                mSessionController.postError(message);
                mSessionController.transitionTo(SessionState.IDLE);
            });
        }

        stopForeground(STOP_FOREGROUND_REMOVE);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // REGENERATE SUMMARY
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Regenerates the AAR summary for an existing session by re-running the LLM
     * on the stored transcript. Loads transcript from DB, runs prompt builder +
     * LlamaEngine + parser, updates the DB with the new summary.
     *
     * WHY: Allows recovery when the initial summary failed (thermal throttling,
     * incomplete JSON, parse errors) without re-recording the entire meeting.
     *
     * @param sessionUuid UUID of the session to regenerate
     */
    private void regenerateSummary(@NonNull String sessionUuid) {
        Log.i(TAG, "[ARIASessionService.regenerateSummary] Regenerating summary for: " + sessionUuid);

        // WHY: Must be foreground to avoid BackgroundServiceStartNotAllowedException
        // on Android 12+. Reuse the summarizing notification.
        try {
            startForeground(Constants.NOTIFICATION_ID,
                    NotificationHelper.buildSummarizingNotification(this));
        } catch (Exception e) {
            Log.e(TAG, "[ARIASessionService.regenerateSummary] Failed to start foreground: " + e.getMessage());
            return;
        }

        // WHY: Set mCurrentSessionUuid so showCompletionNotification() can deep-link
        mCurrentSessionUuid = sessionUuid;

        // Transition state machine to SUMMARIZING for UI feedback
        if (mMainHandler != null && mSessionController != null) {
            mMainHandler.post(() -> {
                mSessionController.transitionTo(SessionState.INITIALIZING);
                mSessionController.transitionTo(SessionState.SUMMARIZING);
            });
        }

        // Run on LLM worker thread to avoid blocking main thread
        if (mLlmWorkerHandler != null) {
            mLlmWorkerHandler.post(() -> {
                try {
                    // Load transcript from database
                    com.stelliq.aria.db.entity.AARSession session =
                            com.stelliq.aria.db.AARDatabase.getInstance(this)
                                    .aarSessionDao().getByUuid(sessionUuid);

                    if (session == null || session.transcriptFull == null || session.transcriptFull.isEmpty()) {
                        Log.e(TAG, "[ARIASessionService.regenerateSummary] No transcript found for: " + sessionUuid);
                        handleSummarizationError("No transcript found for this session");
                        return;
                    }

                    String transcript = session.transcriptFull;
                    Log.i(TAG, "[ARIASessionService.regenerateSummary] Loaded transcript: " + transcript.length() + " chars");

                    // Reuse the standard summarization pipeline
                    runRegenerateSummarization(sessionUuid, transcript);
                } catch (Exception e) {
                    Log.e(TAG, "[ARIASessionService.regenerateSummary] Failed: " + e.getMessage(), e);
                    handleSummarizationError("Regeneration failed: " + e.getMessage());
                }
            });
        } else {
            Log.e(TAG, "[ARIASessionService.regenerateSummary] LLM worker thread not available");
            handleSummarizationError("AI engine not available. Please restart the app.");
        }
    }

    /**
     * Runs LLM summarization for a regeneration request.
     * Similar to runSummarization() but loads from DB and updates existing session.
     *
     * @param sessionUuid UUID of the session to update
     * @param transcript  Full transcript text from DB
     */
    @WorkerThread
    private void runRegenerateSummarization(@NonNull String sessionUuid, @NonNull String transcript) {
        Log.i(TAG, "[ARIASessionService.runRegenerateSummarization] Starting. Transcript length: " + transcript.length());

        // WHY: Release ASR resources before LLM to prevent OOM (same as normal summarization)
        if (mWhisperEngine != null) {
            Log.i(TAG, "[ARIASessionService.runRegenerateSummarization] Releasing Whisper for RAM headroom");
            mWhisperEngine.release();
            mWhisperEngine = null;
            mIsWhisperLoaded = false;
        }
        if (mSileroVAD != null) {
            Log.i(TAG, "[ARIASessionService.runRegenerateSummarization] Releasing SileroVAD for RAM headroom");
            mSileroVAD.release();
            mSileroVAD = null;
        }

        // WHY: Same thermal cooldown as normal summarization — prevents GPU thermal spike
        System.gc();
        long cooldownMs = calculateThermalCooldownMs();
        Log.i(TAG, "[ARIASessionService.runRegenerateSummarization] Thermal cooldown: " + cooldownMs + "ms");
        try {
            Thread.sleep(cooldownMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (!ensureLlamaLoaded()) {
            handleSummarizationError("Failed to load AI model");
            return;
        }

        try {
            // Load template preference
            SharedPreferences templatePrefs = getSharedPreferences(Constants.PREF_FILE, MODE_PRIVATE);
            String templateKey = templatePrefs.getString(Constants.PREF_SUMMARY_TEMPLATE, "retrospective");
            com.stelliq.aria.llm.SummaryTemplate template = com.stelliq.aria.llm.SummaryTemplate.fromKey(templateKey);
            Log.i(TAG, "[ARIASessionService.runRegenerateSummarization] Using template: " + template.displayName);

            String safeTranscript = AARPromptBuilder.truncateToFit(transcript);
            String prompt = AARPromptBuilder.build(safeTranscript, template);

            StringBuilder outputBuilder = new StringBuilder();
            long[] ttftHolder = {0};
            long startTime = System.currentTimeMillis();

            int result = mLlamaEngine.complete(prompt, new LlamaEngine.TokenCallback() {
                @Override
                public void onToken(@NonNull String token, boolean isDone) {
                    if (outputBuilder.length() == 0) {
                        ttftHolder[0] = System.currentTimeMillis() - startTime;
                        if (mPerfLogger != null) {
                            mPerfLogger.logLlmTtft(ttftHolder[0]);
                        }
                    }
                    outputBuilder.append(token);

                    if (mMainHandler != null && mSessionController != null) {
                        final String currentOutput = outputBuilder.toString();
                        mMainHandler.post(() -> mSessionController.updateStreamingOutput(currentOutput));
                    }
                }
            });

            long totalTime = System.currentTimeMillis() - startTime;
            float tokensPerSec = mLlamaEngine.getLastDecodeTokensPerSec();
            if (mPerfLogger != null) {
                mPerfLogger.logLlmDecode(tokensPerSec, totalTime);
            }

            if (result == 0) {
                String llmOutput = "{\n" + outputBuilder.toString();
                AARJsonParser.ParseResult parseResult = AARJsonParser.parse(llmOutput);
                AARSummary summary = parseResult.summary;

                if (!parseResult.success) {
                    Log.w(TAG, "[ARIASessionService.runRegenerateSummarization] Parse partial: " + parseResult.error);
                }

                // Update existing session in DB (not insert new)
                if (mRepository != null) {
                    mRepository.updateSummary(sessionUuid, llmOutput, summary);
                }

                if (mMainHandler != null && mSessionController != null) {
                    mMainHandler.post(() -> {
                        mSessionController.setSummary(summary);
                        mSessionController.transitionTo(SessionState.COMPLETE);
                    });
                }

                Log.i(TAG, "[ARIASessionService.runRegenerateSummarization] Complete. TTFT=" + ttftHolder[0] + "ms, TPS=" + tokensPerSec);
                showCompletionNotification();
            } else if (result == -6) {
                Log.i(TAG, "[ARIASessionService.runRegenerateSummarization] Cancelled by user");
                cleanupAfterCancellation();
            } else {
                handleSummarizationError("LLM inference failed with code: " + result);
            }
        } catch (Exception e) {
            Log.e(TAG, "[ARIASessionService.runRegenerateSummarization] Exception: " + e.getMessage(), e);
            handleSummarizationError("Regeneration failed: " + e.getMessage());
        } finally {
            stopForeground(STOP_FOREGROUND_REMOVE);
            reInitializeWhisperAsync();
        }
    }

    /**
     * Shows a notification alerting the user that their summary is ready.
     * Tapping the notification deep-links to the SessionDetailFragment.
     */
    private void showCompletionNotification() {
        String sessionId = mCurrentSessionUuid;
        if (sessionId == null) return;

        android.app.NotificationManager nm =
                (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(Constants.NOTIFICATION_COMPLETE_ID,
                    NotificationHelper.buildCompletionNotification(this, sessionId));
            Log.i(TAG, "[ARIASessionService.showCompletionNotification] Completion notification shown for session: " + sessionId);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC ACCESSORS FOR UI BINDING
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Returns the SessionController for UI observation.
     */
    @Nullable
    public SessionController getSessionController() {
        return mSessionController;
    }

    /**
     * Returns whether ASR models are loaded and ready for recording.
     * LLM is loaded lazily — NOT required for recording to start.
     */
    public boolean areModelsReady() {
        return mIsWhisperLoaded;
    }

    /**
     * Returns whether initialization is still in progress.
     */
    public boolean isInitializing() {
        return mIsInitializing;
    }

    /**
     * Returns the MeetingManager for multi-device meeting support.
     */
    @Nullable
    public MeetingManager getMeetingManager() {
        return mMeetingManager;
    }

    /**
     * Returns the current speaker name.
     */
    @NonNull
    public String getSpeakerName() {
        return mSpeakerName;
    }

    /**
     * Returns LiveData for session state observation.
     * UI components should observe this for state-driven navigation.
     *
     * @return LiveData containing current SessionState
     */
    @NonNull
    public androidx.lifecycle.LiveData<SessionState> getSessionState() {
        if (mSessionController != null) {
            return mSessionController.getStateLiveData();
        }
        // WHY: Return default LiveData if controller not initialized
        androidx.lifecycle.MutableLiveData<SessionState> defaultState =
                new androidx.lifecycle.MutableLiveData<>(SessionState.IDLE);
        return defaultState;
    }

    /**
     * Returns LiveData for live transcript observation.
     * UI components should observe this for real-time transcript display.
     *
     * @return LiveData containing accumulated transcript text
     */
    @NonNull
    public androidx.lifecycle.LiveData<String> getLiveTranscript() {
        if (mSessionController != null) {
            return mSessionController.getTranscriptLiveData();
        }
        // WHY: Return empty LiveData if controller not initialized
        androidx.lifecycle.MutableLiveData<String> emptyTranscript =
                new androidx.lifecycle.MutableLiveData<>("");
        return emptyTranscript;
    }

    /**
     * Returns LiveData for streaming LLM output during summarization.
     * UI components should observe this for real-time token display.
     *
     * @return LiveData containing accumulated streaming output
     */
    @NonNull
    public androidx.lifecycle.LiveData<String> getStreamingOutput() {
        if (mSessionController != null) {
            return mSessionController.getStreamingOutputLiveData();
        }
        // WHY: Return empty LiveData if controller not initialized
        androidx.lifecycle.MutableLiveData<String> emptyOutput =
                new androidx.lifecycle.MutableLiveData<>("");
        return emptyOutput;
    }

    /**
     * Returns the current AAR summary.
     * Called by SummaryFragment to display completed results.
     *
     * @return Current summary, or null if summarization not complete
     */
    @Nullable
    public AARSummary getCurrentSummary() {
        if (mSessionController != null) {
            return mSessionController.getSummaryLiveData().getValue();
        }
        return null;
    }

    /**
     * Returns last Time-To-First-Token from LLM inference.
     * Used for performance metrics display.
     *
     * @return TTFT in milliseconds, or 0 if not available
     */
    public long getLastTtftMs() {
        return mLlamaEngine != null ? mLlamaEngine.getLastTtftMs() : 0;
    }

    /**
     * Returns last decode speed from LLM inference.
     * Used for performance metrics display.
     *
     * @return Tokens per second, or 0.0 if not available
     */
    public float getLastDecodeTokensPerSec() {
        return mLlamaEngine != null ? mLlamaEngine.getLastDecodeTokensPerSec() : 0.0f;
    }

    /**
     * Resets the session state for a new recording.
     * Called when user taps "New Session", navigates back to recording screen,
     * or deletes a session from SessionDetailFragment.
     *
     * <p>Safe to call from any state. If already IDLE, this is a no-op.
     */
    public void reset() {
        if (mSessionController != null
                && mSessionController.getCurrentState() == SessionState.IDLE
                && mCurrentSessionUuid == null) {
            // Already clean — avoid redundant logging
            return;
        }

        Log.i(TAG, "[ARIASessionService.reset] Resetting from " +
                (mSessionController != null ? mSessionController.getCurrentState() : "null"));

        // WHY: Stop any lingering audio capture (e.g. cancel mid-recording)
        if (mAudioCaptureManager != null) {
            mAudioCaptureManager.stopCapture();
        }

        // WHY: Clear session UUID — new UUID generated when recording starts
        mCurrentSessionUuid = null;

        // WHY: Reset session controller state to IDLE
        if (mSessionController != null) {
            SessionState current = mSessionController.getCurrentState();
            if (current != SessionState.IDLE) {
                mSessionController.transitionTo(SessionState.IDLE);
            }
        }

        // WHY: Clear accumulated transcript for new session
        if (mTranscriptAccumulator != null) {
            mTranscriptAccumulator.reset();
        }

        // WHY: Clear sliding window buffer for new session
        if (mSlidingWindowBuffer != null) {
            mSlidingWindowBuffer.reset();
        }
    }

}
