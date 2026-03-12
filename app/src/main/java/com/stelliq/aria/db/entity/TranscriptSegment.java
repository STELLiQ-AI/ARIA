/**
 * TranscriptSegment.java
 *
 * Room entity representing a single whisper.cpp transcript segment.
 * Maps to the `transcript_segments` table in SQLite.
 *
 * <p>Responsibility:
 * <ul>
 *   <li>Store individual whisper output chunks</li>
 *   <li>Track audio timestamps for each segment</li>
 *   <li>Store inference performance metrics (RTF)</li>
 *   <li>Flag hallucination-filtered segments</li>
 * </ul>
 *
 * <p>Architecture Position:
 * Database entity. Created by AARRepository during recording.
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
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
    tableName = "transcript_segments",
    foreignKeys = @ForeignKey(
        entity = AARSession.class,
        parentColumns = "session_uuid",
        childColumns = "session_uuid",
        onDelete = ForeignKey.CASCADE
    ),
    indices = @Index(value = "session_uuid")
)
public class TranscriptSegment {

    /**
     * Auto-increment primary key.
     */
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "segment_id")
    public long segmentId;

    /**
     * Foreign key to parent session.
     */
    @NonNull
    @ColumnInfo(name = "session_uuid")
    public String sessionUuid;

    /**
     * Sequential segment number within session.
     */
    @ColumnInfo(name = "segment_index")
    public int segmentIndex;

    /**
     * Whisper transcript text for this segment.
     */
    @NonNull
    @ColumnInfo(name = "text")
    public String text;

    /**
     * Audio start offset from session start in milliseconds.
     */
    @ColumnInfo(name = "audio_start_ms")
    public long audioStartMs;

    /**
     * Audio end offset from session start in milliseconds.
     */
    @ColumnInfo(name = "audio_end_ms")
    public long audioEndMs;

    /**
     * Whisper inference wall-clock time in milliseconds.
     */
    @ColumnInfo(name = "inference_ms")
    public long inferenceMs;

    /**
     * Real-Time Factor (inference_ms / audio_duration_ms).
     */
    @ColumnInfo(name = "rtf")
    public float rtf;

    /**
     * Flag indicating segment was filtered as hallucination.
     */
    @ColumnInfo(name = "was_hallucination", defaultValue = "0")
    public boolean wasHallucination;

    /**
     * Default constructor required by Room.
     */
    public TranscriptSegment() {
        this.sessionUuid = "";
        this.text = "";
    }

    /**
     * Convenience constructor.
     *
     * @param sessionUuid  Parent session UUID
     * @param segmentIndex Sequential index
     * @param text         Transcript text
     */
    public TranscriptSegment(@NonNull String sessionUuid, int segmentIndex, @NonNull String text) {
        this.sessionUuid = sessionUuid;
        this.segmentIndex = segmentIndex;
        this.text = text;
    }
}
