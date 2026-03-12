/**
 * AARSession.java
 *
 * Room entity representing a single ARIA recording session. Maps to the
 * `aar_sessions` table in SQLite.
 *
 * <p>Responsibility:
 * <ul>
 *   <li>Store session metadata (UUID, timestamps, duration)</li>
 *   <li>Store full transcript text</li>
 *   <li>Store raw LLM JSON response</li>
 *   <li>Store parsed TC 7-0.1 AAR fields</li>
 *   <li>Store device telemetry for demo debugging</li>
 * </ul>
 *
 * <p>Architecture Position:
 * Database entity. Created by AARRepository, queried by HistoryFragment.
 *
 * <p>Thread Safety:
 * Room handles thread safety. Entity is a simple POJO.
 *
 * <p>Air-Gap Compliance:
 * All data stored locally. No cloud sync.
 *
 * @author STELLiQ Engineering
 * @version 0.1.0
 * @since ARIA Demo Build — 2026-03-02
 */
package com.stelliq.aria.db.entity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "aar_sessions")
public class AARSession {

    /**
     * UUID v4 primary key, generated at session start.
     */
    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "session_uuid")
    public String sessionUuid;

    /**
     * LLM-generated session title in Title Case.
     */
    @Nullable
    @ColumnInfo(name = "session_title")
    public String sessionTitle;

    /**
     * Session start time (System.currentTimeMillis() at START button tap).
     */
    @ColumnInfo(name = "started_at_epoch_ms")
    public long startedAtEpochMs;

    /**
     * Session end time (System.currentTimeMillis() at STOP button tap).
     */
    @ColumnInfo(name = "ended_at_epoch_ms")
    public long endedAtEpochMs;

    /**
     * Recording duration in milliseconds (ended_at - started_at).
     */
    @ColumnInfo(name = "duration_ms")
    public long durationMs;

    /**
     * Complete accumulated transcript text from whisper.cpp.
     */
    @Nullable
    @ColumnInfo(name = "transcript_full")
    public String transcriptFull;

    /**
     * Raw LLM JSON response string (before parsing).
     */
    @Nullable
    @ColumnInfo(name = "aar_json")
    public String aarJson;

    /**
     * TC 7-0.1 Field 1: What was the plan?
     */
    @Nullable
    @ColumnInfo(name = "what_was_planned")
    public String whatWasPlanned;

    /**
     * TC 7-0.1 Field 2: What actually happened?
     */
    @Nullable
    @ColumnInfo(name = "what_happened")
    public String whatHappened;

    /**
     * TC 7-0.1 Field 3: Why did it happen that way?
     */
    @Nullable
    @ColumnInfo(name = "why_it_happened")
    public String whyItHappened;

    /**
     * TC 7-0.1 Field 4: How can we improve?
     */
    @Nullable
    @ColumnInfo(name = "how_to_improve")
    public String howToImprove;

    /**
     * Flag indicating whether LLM JSON was parsed successfully.
     */
    @ColumnInfo(name = "llm_parse_success", defaultValue = "0")
    public boolean llmParseSuccess;

    /**
     * Completeness score: populated TC 7-0.1 field count / 4.0.
     * 1.0 = all four AAR fields populated, 0.75 = three of four, etc.
     */
    @ColumnInfo(name = "completeness_score", defaultValue = "0.0")
    public float completenessScore;

    /**
     * Error message if session ended in error state.
     */
    @Nullable
    @ColumnInfo(name = "error_message")
    public String errorMessage;

    /**
     * Device model (Build.MODEL) for demo telemetry.
     */
    @Nullable
    @ColumnInfo(name = "device_model")
    public String deviceModel;

    /**
     * App version (BuildConfig.VERSION_NAME) for telemetry.
     */
    @Nullable
    @ColumnInfo(name = "app_version")
    public String appVersion;

    /**
     * Default constructor required by Room.
     */
    public AARSession() {
        this.sessionUuid = "";
    }

    /**
     * Convenience constructor for creating a new session.
     *
     * @param sessionUuid Unique session identifier
     */
    public AARSession(@NonNull String sessionUuid) {
        this.sessionUuid = sessionUuid;
    }
}
