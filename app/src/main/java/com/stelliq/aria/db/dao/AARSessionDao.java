/**
 * AARSessionDao.java
 *
 * Room Data Access Object for aar_sessions table operations.
 *
 * <p>Responsibility:
 * <ul>
 *   <li>CRUD operations for AARSession entities</li>
 *   <li>Query sessions for history display</li>
 *   <li>Query session by UUID for detail view</li>
 * </ul>
 *
 * <p>Architecture Position:
 * DAO interface. Implemented by Room at compile time.
 *
 * <p>Thread Safety:
 * All operations should be called from background thread (Room I/O executor).
 *
 * @author STELLiQ Engineering
 * @version 0.1.0
 * @since ARIA Demo Build — 2026-03-02
 */
package com.stelliq.aria.db.dao;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.stelliq.aria.db.entity.AARSession;

import java.util.List;

@Dao
public interface AARSessionDao {

    /**
     * Inserts a new session. Replaces if UUID already exists.
     *
     * @param session The session to insert
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(@NonNull AARSession session);

    /**
     * Inserts or updates a session.
     *
     * <p>WHY: Explicit upsert semantics for session lifecycle management.
     * Session is created early (createSession), then updated with transcript/summary.
     *
     * @param session The session to insert or update
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertOrUpdate(@NonNull AARSession session);

    /**
     * Updates an existing session.
     *
     * @param session The session to update
     */
    @Update
    void update(@NonNull AARSession session);

    /**
     * Deletes a session and all related entities (cascade).
     *
     * @param session The session to delete
     */
    @Delete
    void delete(@NonNull AARSession session);

    /**
     * Returns a session by UUID.
     *
     * @param sessionUuid The session UUID
     * @return The session, or null if not found
     */
    @Query("SELECT * FROM aar_sessions WHERE session_uuid = :sessionUuid")
    @Nullable
    AARSession getByUuid(@NonNull String sessionUuid);

    /**
     * Returns all sessions ordered by start time descending (newest first).
     *
     * @return List of all sessions
     */
    @Query("SELECT * FROM aar_sessions ORDER BY started_at_epoch_ms DESC")
    List<AARSession> getAllSessions();

    /**
     * Returns all sessions as LiveData for UI observation.
     *
     * @return LiveData list of sessions
     */
    @Query("SELECT * FROM aar_sessions ORDER BY started_at_epoch_ms DESC")
    LiveData<List<AARSession>> getAllSessionsLive();

    /**
     * Returns the count of sessions.
     *
     * @return Number of stored sessions
     */
    @Query("SELECT COUNT(*) FROM aar_sessions")
    int getSessionCount();

    /**
     * Deletes all sessions (for testing/reset).
     */
    @Query("DELETE FROM aar_sessions")
    void deleteAll();

    /**
     * Returns sessions within a time range.
     *
     * @param startMs Range start (epoch ms)
     * @param endMs   Range end (epoch ms)
     * @return Sessions within range
     */
    @Query("SELECT * FROM aar_sessions WHERE started_at_epoch_ms BETWEEN :startMs AND :endMs ORDER BY started_at_epoch_ms DESC")
    List<AARSession> getSessionsInRange(long startMs, long endMs);
}
