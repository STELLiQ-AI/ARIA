# UI Package — User Interface Layer

## Responsibility
Single-activity architecture with fragment-based navigation.
Observes SessionController LiveData for state updates.
Does NOT contain business logic — delegates to ARIASessionService.

## Navigation Flow

```
SplashActivity (branding, model check)
    |
    v
MainActivity (single activity, NavHostFragment)
    |
    ├── HomeFragment (start recording, recent sessions)
    |
    ├── RecordingFragment (live waveform, transcript, timer, stop button)
    |
    ├── SessionsFragment (session history list)
    |       |
    |       v
    |   SessionDetailFragment (view transcript + AAR summary, delete)
    |
    └── SettingsFragment (speaker name, delete all, about)
```

## Files

| File | Responsibility |
|------|---------------|
| `SplashActivity.java` | Splash screen, model verification, navigate to MainActivity |
| `MainActivity.java` | Single activity host. `singleTop` launch mode, portrait only. NavHostFragment. |
| `home/HomeFragment.java` | Landing screen. Start recording button, recent session cards. |
| `recording/RecordingFragment.java` | Active recording UI. Live waveform, scrolling transcript, elapsed timer, stop/cancel buttons. Observes SessionController LiveData. |
| `sessions/SessionsFragment.java` | Session history list with RecyclerView. |
| `sessions/SessionAdapter.java` | RecyclerView adapter for session list items. |
| `session/SessionDetailFragment.java` | View completed session. Transcript + AAR summary tabs. Delete button with confirmation. |
| `settings/SettingsFragment.java` | User settings. Speaker name, delete all recordings, about dialog. |
| `view/WaveformView.java` | Custom View for real-time audio waveform visualization. |

## Key Patterns

### LiveData Observation
Fragments observe `SessionController` LiveData via the bound `ARIASessionService`:
```java
service.getSessionController().getStateLiveData().observe(getViewLifecycleOwner(), state -> {
    // Update UI based on state
});
```

### Fragment Lifecycle Safety
**Critical rule:** Never call `requireContext()` or `requireActivity()` inside background thread
lambdas (executor, Handler.post). Instead:
```java
Context context = requireContext();  // Safe — called on main thread
mExecutor.execute(() -> {
    AARDatabase db = AARDatabase.getInstance(context);  // Use captured context
    Activity activity = getActivity();
    if (activity != null) {
        activity.runOnUiThread(() -> { /* UI work */ });
    }
});
```

### Executor Cleanup
`SessionDetailFragment` creates a `newSingleThreadExecutor()` for DB operations.
It is shut down in `onDestroyView()` via `mExecutor.shutdownNow()`.

### Speaker Attribution Styling
Speaker names in transcript are styled with:
- Gold color (`#FFD700`)
- Bold typeface
- 115% text size
- Applied via `SpannableString` on the "Name:" prefix

## Important Notes
- **Portrait only** — `android:screenOrientation="portrait"` in manifest
- **No Kotlin** — All UI is Java
- **strings.xml** — No hardcoded strings in UI code
- **RecordingFragment stale state:** Calls `resetServiceIfStale()` on view creation. `mHasNavigatedToDetail` guard prevents duplicate navigation to summary screen.
