/**
 * AARDatabase.java
 *
 * Room database definition for ARIA session persistence. Singleton instance
 * provides access to all DAO interfaces.
 *
 * <p>Responsibility:
 * <ul>
 *   <li>Define database schema version and entities</li>
 *   <li>Provide singleton database instance</li>
 *   <li>Configure WAL mode for concurrent access</li>
 *   <li>Provide DAO accessors</li>
 * </ul>
 *
 * <p>Architecture Position:
 * Database singleton. Initialized by ARIAApplication, accessed via AARRepository.
 *
 * <p>Thread Safety:
 * Room handles internal synchronization. Singleton uses double-checked locking.
 *
 * <p>Air-Gap Compliance:
 * All data stored locally in SQLite. No cloud sync.
 *
 * @author STELLiQ Engineering
 * @version 0.1.0
 * @since ARIA Demo Build — 2026-03-02
 */
package com.stelliq.aria.db;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.stelliq.aria.db.dao.AARSessionDao;
import com.stelliq.aria.db.dao.PipelineMetricDao;
import com.stelliq.aria.db.dao.TranscriptSegmentDao;
import com.stelliq.aria.db.entity.AARSession;
import com.stelliq.aria.db.entity.PipelineMetric;
import com.stelliq.aria.db.entity.TranscriptSegment;
import com.stelliq.aria.util.Constants;

@Database(
    entities = {
        AARSession.class,
        TranscriptSegment.class,
        PipelineMetric.class
    },
    version = Constants.DATABASE_VERSION,
    exportSchema = true
)
public abstract class AARDatabase extends RoomDatabase {

    // WHY: Volatile for double-checked locking thread safety
    private static volatile AARDatabase sInstance;

    /**
     * Migration v2 → v3: Add completeness_score column to aar_sessions.
     * Added for TC 7-0.1 AAR completeness tracking (populated field count / 4).
     */
    static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL(
                "ALTER TABLE aar_sessions ADD COLUMN completeness_score REAL NOT NULL DEFAULT 0.0"
            );
        }
    };

    /**
     * Returns the singleton database instance.
     *
     * @param context Application context
     * @return The AARDatabase singleton
     */
    @NonNull
    public static AARDatabase getInstance(@NonNull Context context) {
        if (sInstance == null) {
            synchronized (AARDatabase.class) {
                if (sInstance == null) {
                    sInstance = Room.databaseBuilder(
                            context.getApplicationContext(),
                            AARDatabase.class,
                            Constants.DATABASE_NAME
                    )
                    // WHY: WAL mode enables concurrent read/write during recording
                    .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                    // WHY: Proper migration for v2→v3 (completeness_score column)
                    .addMigrations(MIGRATION_2_3)
                    // WHY: Safety net for unknown version jumps
                    .fallbackToDestructiveMigration()
                    .build();
                }
            }
        }
        return sInstance;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DAO ACCESSORS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Returns the AARSession DAO.
     */
    @NonNull
    public abstract AARSessionDao aarSessionDao();

    /**
     * Returns the TranscriptSegment DAO.
     */
    @NonNull
    public abstract TranscriptSegmentDao transcriptSegmentDao();

    /**
     * Returns the PipelineMetric DAO.
     */
    @NonNull
    public abstract PipelineMetricDao pipelineMetricDao();
}
