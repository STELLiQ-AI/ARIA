/**
 * AARRepository.java
 *
 * Repository pattern implementation for ARIA database operations.
 * Provides single access point for all persistence operations.
 *
 * <p>Responsibility:
 * <ul>
 *   <li>Abstract DAO access behind repository interface</li>
 *   <li>Execute database operations on background executor</li>
 *   <li>Provide convenient methods for session lifecycle operations</li>
 *   <li>Coordinate multi-table operations</li>
 * </ul>
 *
 * <p>Architecture Position:
 * Repository layer between service/UI and Room DAOs. Owned by ARIASessionService.
 *
 * <p>Thread Safety:
 * All write operations execute on background executor. LiveData queries are thread-safe.
 *
 * <p>Air-Gap Compliance:
 * All data stored locally. No cloud sync.
 *
 * @author STELLiQ Engineering
 * @version 0.1.0
 * @since ARIA Demo Build — 2026-03-02
 */
package com.stelliq.aria.db;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.lifecycle.LiveData;

import com.stelliq.aria.db.dao.AARSessionDao;
import com.stelliq.aria.db.dao.PipelineMetricDao;
import com.stelliq.aria.db.dao.TranscriptSegmentDao;
import com.stelliq.aria.db.entity.AARSession;
import com.stelliq.aria.db.entity.PipelineMetric;
import com.stelliq.aria.db.entity.TranscriptSegment;
import com.stelliq.aria.model.AARSummary;
import com.stelliq.aria.util.Constants;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AARRepository {

    private static final String TAG = Constants.LOG_TAG_DB;

    private final AARSessionDao mSessionDao;
    private final TranscriptSegmentDao mSegmentDao;
    private final PipelineMetricDao mMetricDao;

    // WHY: Single-threaded executor ensures ordered database writes
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

    /**
     * Creates a repository instance.
     *
     * @param context Application context for database access
     */
    public AARRepository(@NonNull Context context) {
        AARDatabase database = AARDatabase.getInstance(context);
        mSessionDao = database.aarSessionDao();
        mSegmentDao = database.transcriptSegmentDao();
        mMetricDao = database.pipelineMetricDao();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SESSION OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Creates a new session record.
     *
     * @param sessionUuid Unique session identifier
     */
    public void createSession(@NonNull String sessionUuid) {
        mExecutor.execute(() -> {
            try {
                AARSession session = new AARSession(sessionUuid);
                session.startedAtEpochMs = System.currentTimeMillis();
                session.deviceModel = Build.MODEL;
                session.appVersion = Constants.APP_VERSION;

                mSessionDao.insert(session);
                Log.d(TAG, "[AARRepository.createSession] Created session: " + sessionUuid);
            } catch (Exception e) {
                Log.e(TAG, "[AARRepository.createSession] Failed: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Saves a completed session with transcript and AAR summary.
     *
     * <p>Uses insertOrUpdate to handle both new sessions and updates to existing sessions
     * created by {@link #createSession(String)}.
     *
     * @param sessionUuid Session UUID (from createSession or new)
     * @param transcript  Full transcript text
     * @param rawJson     Raw LLM JSON output
     * @param summary     Parsed AAR summary
     */
    public void saveSession(@NonNull String sessionUuid,
                            @NonNull String transcript,
                            @Nullable String rawJson,
                            @Nullable AARSummary summary) {
        mExecutor.execute(() -> {
            try {
                // WHY: Check if session already exists (created by createSession)
                AARSession session = mSessionDao.getByUuid(sessionUuid);

                if (session == null) {
                    session = new AARSession(sessionUuid);
                    session.startedAtEpochMs = System.currentTimeMillis();
                }

                session.endedAtEpochMs = System.currentTimeMillis();
                session.durationMs = session.endedAtEpochMs - session.startedAtEpochMs;
                session.transcriptFull = transcript;
                session.aarJson = rawJson;
                session.deviceModel = Build.MODEL;
                session.appVersion = Constants.APP_VERSION;

                // WHY: Summary may be null when LLM is unavailable (transcript-only save)
                if (summary != null) {
                    session.sessionTitle = summary.getTitle();
                    session.whatWasPlanned = summary.getWhatWasPlanned();
                    session.whatHappened = summary.getWhatHappened();
                    session.whyItHappened = summary.getWhyItHappened();
                    session.howToImprove = summary.getHowToImprove();
                    session.llmParseSuccess = summary.wasParsedSuccessfully();
                    session.completenessScore = summary.computeCompletenessScore();
                } else {
                    session.llmParseSuccess = false;
                    session.completenessScore = 0.0f;
                }

                mSessionDao.insertOrUpdate(session);
                Log.i(TAG, "[AARRepository.saveSession] Saved session: " + sessionUuid
                        + " (summary=" + (summary != null ? "yes" : "transcript-only") + ")");
            } catch (Exception e) {
                Log.e(TAG, "[AARRepository.saveSession] Failed: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Updates session end time and duration.
     *
     * @param sessionUuid Session to update
     */
    public void endSession(@NonNull String sessionUuid) {
        mExecutor.execute(() -> {
            try {
                AARSession session = mSessionDao.getByUuid(sessionUuid);
                if (session != null) {
                    session.endedAtEpochMs = System.currentTimeMillis();
                    session.durationMs = session.endedAtEpochMs - session.startedAtEpochMs;
                    mSessionDao.update(session);
                    Log.d(TAG, "[AARRepository.endSession] Ended session: " + sessionUuid
                            + " duration: " + session.durationMs + "ms");
                }
            } catch (Exception e) {
                Log.e(TAG, "[AARRepository.endSession] Failed: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Updates session with transcript.
     *
     * @param sessionUuid Session to update
     * @param transcript  Full transcript text
     */
    public void updateTranscript(@NonNull String sessionUuid, @NonNull String transcript) {
        mExecutor.execute(() -> {
            try {
                AARSession session = mSessionDao.getByUuid(sessionUuid);
                if (session != null) {
                    session.transcriptFull = transcript;
                    mSessionDao.update(session);
                }
            } catch (Exception e) {
                Log.e(TAG, "[AARRepository.updateTranscript] Failed: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Updates session with AAR summary.
     *
     * @param sessionUuid Session to update
     * @param rawJson     Raw LLM JSON output
     * @param summary     Parsed AAR summary
     */
    public void updateSummary(@NonNull String sessionUuid,
                              @NonNull String rawJson,
                              @NonNull AARSummary summary) {
        mExecutor.execute(() -> {
            try {
                AARSession session = mSessionDao.getByUuid(sessionUuid);
                if (session != null) {
                    session.aarJson = rawJson;
                    session.whatWasPlanned = summary.getWhatWasPlanned();
                    session.whatHappened = summary.getWhatHappened();
                    session.whyItHappened = summary.getWhyItHappened();
                    session.howToImprove = summary.getHowToImprove();
                    session.llmParseSuccess = summary.wasParsedSuccessfully();
                    session.completenessScore = summary.computeCompletenessScore();
                    mSessionDao.update(session);
                    Log.d(TAG, "[AARRepository.updateSummary] Updated summary for: " + sessionUuid
                            + " (completeness=" + session.completenessScore + ")");
                }
            } catch (Exception e) {
                Log.e(TAG, "[AARRepository.updateSummary] Failed: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Sets error message for a failed session.
     *
     * @param sessionUuid Session to update
     * @param error       Error description
     */
    public void setSessionError(@NonNull String sessionUuid, @NonNull String error) {
        mExecutor.execute(() -> {
            try {
                AARSession session = mSessionDao.getByUuid(sessionUuid);
                if (session != null) {
                    session.errorMessage = error;
                    session.endedAtEpochMs = System.currentTimeMillis();
                    mSessionDao.update(session);
                }
            } catch (Exception e) {
                Log.e(TAG, "[AARRepository.setSessionError] Failed: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Returns LiveData for all sessions (for HistoryFragment).
     */
    @NonNull
    public LiveData<List<AARSession>> getAllSessionsLive() {
        return mSessionDao.getAllSessionsLive();
    }

    /**
     * Returns a session by UUID (blocking).
     */
    @WorkerThread
    @Nullable
    public AARSession getSessionSync(@NonNull String sessionUuid) {
        return mSessionDao.getByUuid(sessionUuid);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SEGMENT OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Inserts a transcript segment.
     *
     * @param sessionUuid   Parent session UUID
     * @param segmentIndex  Sequential index
     * @param text          Transcript text
     * @param audioStartMs  Audio start offset
     * @param audioEndMs    Audio end offset
     * @param inferenceMs   Whisper inference time
     * @param rtf           Real-time factor
     * @param isHallucination Whether segment was filtered as hallucination
     */
    public void insertSegment(@NonNull String sessionUuid,
                              int segmentIndex,
                              @NonNull String text,
                              long audioStartMs,
                              long audioEndMs,
                              long inferenceMs,
                              float rtf,
                              boolean isHallucination) {
        mExecutor.execute(() -> {
            try {
                TranscriptSegment segment = new TranscriptSegment(sessionUuid, segmentIndex, text);
                segment.audioStartMs = audioStartMs;
                segment.audioEndMs = audioEndMs;
                segment.inferenceMs = inferenceMs;
                segment.rtf = rtf;
                segment.wasHallucination = isHallucination;

                mSegmentDao.insert(segment);
            } catch (Exception e) {
                Log.e(TAG, "[AARRepository.insertSegment] Failed: " + e.getMessage(), e);
            }
        });
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // METRIC OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Inserts a pipeline metric.
     *
     * @param metric The metric to insert
     */
    public void insertMetric(@NonNull PipelineMetric metric) {
        mExecutor.execute(() -> {
            try {
                mMetricDao.insert(metric);
            } catch (Exception e) {
                Log.e(TAG, "[AARRepository.insertMetric] Failed: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Inserts a float metric (RTF, TPS).
     */
    public void logFloatMetric(@NonNull String sessionUuid, @NonNull String metricType, float value) {
        insertMetric(PipelineMetric.createFloat(sessionUuid, metricType, value));
    }

    /**
     * Inserts an integer metric (TTFT ms, RAM bytes).
     */
    public void logIntMetric(@NonNull String sessionUuid, @NonNull String metricType, long value) {
        insertMetric(PipelineMetric.createInt(sessionUuid, metricType, value));
    }
}
