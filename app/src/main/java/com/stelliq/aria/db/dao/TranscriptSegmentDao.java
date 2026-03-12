/**
 * TranscriptSegmentDao.java
 *
 * Room Data Access Object for transcript_segments table operations.
 *
 * <p>Responsibility:
 * <ul>
 *   <li>Insert transcript segments during recording</li>
 *   <li>Query segments for a session</li>
 *   <li>Calculate aggregate RTF statistics</li>
 * </ul>
 *
 * <p>Architecture Position:
 * DAO interface. Implemented by Room at compile time.
 *
 * <p>Thread Safety:
 * All operations should be called from background thread.
 *
 * @author STELLiQ Engineering
 * @version 0.1.0
 * @since ARIA Demo Build — 2026-03-02
 */
package com.stelliq.aria.db.dao;

import androidx.annotation.NonNull;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import com.stelliq.aria.db.entity.TranscriptSegment;

import java.util.List;

@Dao
public interface TranscriptSegmentDao {

    /**
     * Inserts a new transcript segment.
     *
     * @param segment The segment to insert
     * @return The auto-generated segment ID
     */
    @Insert
    long insert(@NonNull TranscriptSegment segment);

    /**
     * Inserts multiple segments in a batch.
     *
     * @param segments The segments to insert
     */
    @Insert
    void insertAll(@NonNull List<TranscriptSegment> segments);

    /**
     * Returns all segments for a session, ordered by index.
     *
     * @param sessionUuid The session UUID
     * @return List of segments
     */
    @Query("SELECT * FROM transcript_segments WHERE session_uuid = :sessionUuid ORDER BY segment_index ASC")
    List<TranscriptSegment> getSegmentsForSession(@NonNull String sessionUuid);

    /**
     * Returns the number of segments for a session.
     *
     * @param sessionUuid The session UUID
     * @return Segment count
     */
    @Query("SELECT COUNT(*) FROM transcript_segments WHERE session_uuid = :sessionUuid")
    int getSegmentCount(@NonNull String sessionUuid);

    /**
     * Returns the average RTF for a session.
     *
     * @param sessionUuid The session UUID
     * @return Average RTF, or null if no segments
     */
    @Query("SELECT AVG(rtf) FROM transcript_segments WHERE session_uuid = :sessionUuid AND was_hallucination = 0")
    Float getAverageRtf(@NonNull String sessionUuid);

    /**
     * Returns the count of hallucination-filtered segments.
     *
     * @param sessionUuid The session UUID
     * @return Count of filtered segments
     */
    @Query("SELECT COUNT(*) FROM transcript_segments WHERE session_uuid = :sessionUuid AND was_hallucination = 1")
    int getHallucinationCount(@NonNull String sessionUuid);

    /**
     * Deletes all segments for a session.
     *
     * @param sessionUuid The session UUID
     */
    @Query("DELETE FROM transcript_segments WHERE session_uuid = :sessionUuid")
    void deleteForSession(@NonNull String sessionUuid);
}
