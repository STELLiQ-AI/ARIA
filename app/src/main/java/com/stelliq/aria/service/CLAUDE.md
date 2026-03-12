# Service Package — Session Orchestration

## Responsibility
Foreground service that owns and coordinates the entire ARIA pipeline.
Manages lifecycle of audio capture, ASR, LLM, meeting networking, and database persistence.

## Files

| File | Responsibility |
|------|---------------|
| `ARIASessionService.java` | Foreground service (~600 lines). Creates/owns all engines and HandlerThreads. Orchestrates recording→transcription→summarization flow. Binds to UI via SessionController LiveData. |
| `SessionController.java` | State machine + LiveData hub. Validates state transitions. Exposes observable state, transcript, summary, errors, and participant count to UI. Does NOT perform inference. |

## Architecture

```
UI (Fragments) ──observes LiveData──> SessionController
                                          |
                                     owned by
                                          |
                                   ARIASessionService
                                    /    |    |    \
                          AudioCapture  ASR  LLM  MeetingManager
                          (thread)   (thread)(thread)
```

### ARIASessionService owns:
- `AudioCaptureManager` — microphone recording
- `SlidingWindowBuffer` — audio windowing with HPF + VAD
- `SileroVAD` — voice activity detection
- `WhisperEngine` — speech-to-text
- `LlamaEngine` — LLM summarization
- `TranscriptAccumulator` — transcript assembly
- `MeetingManager` — multi-device coordination
- `AARRepository` — database access
- `SessionController` — state machine
- `NotificationHelper` — foreground notification
- `ModelFileManager` — model file management
- `PerfLogger` — performance metrics

### HandlerThreads (3)
| Thread | Name | Purpose | Shutdown |
|--------|------|---------|----------|
| mAudioCaptureThread | aria-audio-capture | AudioRecord read loop | `quitSafely()` + `join(1000)` |
| mAsrWorkerThread | aria-asr-worker | Whisper inference | `quitSafely()` + `join(1000)` |
| mLlmWorkerThread | aria-llm-worker | Llama inference | `quitSafely()` + `join(1000)` |

All threads are joined with 1s timeout on shutdown, and handler references are nulled.

## SessionController State Machine

### States (SessionState enum)
- `IDLE` — No active session
- `INITIALIZING` — Models loading, UUID generated
- `RECORDING` — Audio capture + ASR active
- `SUMMARIZING` — LLM generating AAR summary
- `COMPLETE` — Summary ready, session saved
- `ERROR` — Recoverable error state

### LiveData Streams
| LiveData | Type | Purpose |
|----------|------|---------|
| mStateLiveData | SessionState | Current session state |
| mTranscriptLiveData | String | Accumulated transcript text |
| mStreamingOutputLiveData | String | Real-time LLM token stream |
| mSummaryLiveData | AARSummary | Final parsed summary |
| mErrorLiveData | String | Error messages |
| mModelsReadyLiveData | Boolean | Model initialization status |
| mParticipantCountLiveData | Integer | Multi-device participant count |

### Key Methods
- `transitionTo(SessionState)` — Validates and executes state transition, posts to main thread
- `updateTranscript(String)` — Updates live transcript observable
- `setSummary(AARSummary)` — Sets final summary
- `postError(String)` — Posts error to UI
- `getElapsedRecordingMs()` — Returns elapsed time since RECORDING state

## Important Notes
- **God Class:** ARIASessionService is a known architectural concern (~600 lines). Extraction of AudioPipeline, ASRPipeline, and LLMPipeline classes is planned for Phase 2.
- **Fragment lifecycle safety:** Background executor lambdas must capture `Context` before submission, then use null-checked `getActivity()` for UI thread posts. Never call `requireContext()` or `requireActivity()` inside executor lambdas.
- **Service start:** Use `am start -W` flag or launch from device. `monkey` and `am start` without `-W` can cause `BackgroundServiceStartNotAllowedException` on Android 12+.
- **Auto-reset:** `startRecording()` auto-resets from COMPLETE/ERROR → IDLE before starting a new session.
