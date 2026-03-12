/**
 * PipelineMetricDao.java
 *
 * Room Data Access Object for pipeline_metrics table operations.
 *
 * <p>Responsibility:
 * <ul>
 *   <li>Insert performance metrics during inference</li>
 *   <li>Query metrics for analysis</li>
 *   <li>Calculate aggregate statistics</li>
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

import com.stelliq.aria.db.entity.PipelineMetric;

import java.util.List;

@Dao
public interface PipelineMetricDao {

    /**
     * Inserts a new metric.
     *
     * @param metric The metric to insert
     * @return The auto-generated metric ID
     */
    @Insert
    long insert(@NonNull PipelineMetric metric);

    /**
     * Inserts multiple metrics in a batch.
     *
     * @param metrics The metrics to insert
     */
    @Insert
    void insertAll(@NonNull List<PipelineMetric> metrics);

    /**
     * Returns all metrics for a session.
     *
     * @param sessionUuid The session UUID
     * @return List of metrics
     */
    @Query("SELECT * FROM pipeline_metrics WHERE session_uuid = :sessionUuid ORDER BY recorded_at_epoch_ms ASC")
    List<PipelineMetric> getMetricsForSession(@NonNull String sessionUuid);

    /**
     * Returns metrics of a specific type for a session.
     *
     * @param sessionUuid The session UUID
     * @param metricType  The metric type (WHISPER_RTF, LLM_TTFT, etc.)
     * @return List of matching metrics
     */
    @Query("SELECT * FROM pipeline_metrics WHERE session_uuid = :sessionUuid AND metric_type = :metricType ORDER BY recorded_at_epoch_ms ASC")
    List<PipelineMetric> getMetricsByType(@NonNull String sessionUuid, @NonNull String metricType);

    /**
     * Returns the average float value for a metric type.
     *
     * @param sessionUuid The session UUID
     * @param metricType  The metric type
     * @return Average value, or null if no metrics
     */
    @Query("SELECT AVG(value_float) FROM pipeline_metrics WHERE session_uuid = :sessionUuid AND metric_type = :metricType")
    Float getAverageFloat(@NonNull String sessionUuid, @NonNull String metricType);

    /**
     * Returns the maximum integer value for a metric type.
     *
     * @param sessionUuid The session UUID
     * @param metricType  The metric type
     * @return Maximum value, or null if no metrics
     */
    @Query("SELECT MAX(value_int) FROM pipeline_metrics WHERE session_uuid = :sessionUuid AND metric_type = :metricType")
    Long getMaxInt(@NonNull String sessionUuid, @NonNull String metricType);

    /**
     * Deletes all metrics for a session.
     *
     * @param sessionUuid The session UUID
     */
    @Query("DELETE FROM pipeline_metrics WHERE session_uuid = :sessionUuid")
    void deleteForSession(@NonNull String sessionUuid);
}
