/**
 * PipelineMetric.java
 *
 * Room entity for storing pipeline performance telemetry.
 * Maps to the `pipeline_metrics` table in SQLite.
 *
 * <p>Responsibility:
 * <ul>
 *   <li>Persist WHISPER_RTF measurements</li>
 *   <li>Persist LLM_TTFT measurements</li>
 *   <li>Persist LLM_TPS measurements</li>
 *   <li>Persist PEAK_RAM measurements</li>
 *   <li>Enable post-hoc performance analysis</li>
 * </ul>
 *
 * <p>Architecture Position:
 * Database entity. Created by AARRepository via PerfLogger integration.
 *
 * <p>Thread Safety:
 * Room handles thread safety. Entity is a simple POJO.
 *
 * <p>Air-Gap Compliance:
 * All data stored locally. No cloud sync or telemetry exfiltration.
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
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
    tableName = "pipeline_metrics",
    foreignKeys = @ForeignKey(
        entity = AARSession.class,
        parentColumns = "session_uuid",
        childColumns = "session_uuid",
        onDelete = ForeignKey.CASCADE
    ),
    indices = @Index(value = "session_uuid")
)
public class PipelineMetric {

    /**
     * Metric type constants.
     */
    public static final String TYPE_WHISPER_RTF = "WHISPER_RTF";
    public static final String TYPE_LLM_TTFT = "LLM_TTFT";
    public static final String TYPE_LLM_TPS = "LLM_TPS";
    public static final String TYPE_PEAK_RAM = "PEAK_RAM";
    public static final String TYPE_OPENCL_INIT = "OPENCL_INIT";
    public static final String TYPE_PIPELINE_TOTAL = "PIPELINE_TOTAL";

    /**
     * Auto-increment primary key.
     */
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "metric_id")
    public long metricId;

    /**
     * Foreign key to parent session.
     */
    @NonNull
    @ColumnInfo(name = "session_uuid")
    public String sessionUuid;

    /**
     * Metric type identifier.
     */
    @NonNull
    @ColumnInfo(name = "metric_type")
    public String metricType;

    /**
     * Float value for ratio/rate metrics (RTF, TPS).
     */
    @Nullable
    @ColumnInfo(name = "value_float")
    public Float valueFloat;

    /**
     * Integer value for count/duration metrics (ms, bytes).
     */
    @Nullable
    @ColumnInfo(name = "value_int")
    public Long valueInt;

    /**
     * Timestamp when metric was recorded.
     */
    @ColumnInfo(name = "recorded_at_epoch_ms")
    public long recordedAtEpochMs;

    /**
     * Default constructor required by Room.
     */
    public PipelineMetric() {
        this.sessionUuid = "";
        this.metricType = "";
    }

    /**
     * Factory method for float metrics (RTF, TPS).
     */
    @NonNull
    public static PipelineMetric createFloat(@NonNull String sessionUuid,
                                              @NonNull String metricType,
                                              float value) {
        PipelineMetric metric = new PipelineMetric();
        metric.sessionUuid = sessionUuid;
        metric.metricType = metricType;
        metric.valueFloat = value;
        metric.recordedAtEpochMs = System.currentTimeMillis();
        return metric;
    }

    /**
     * Factory method for integer metrics (TTFT ms, RAM bytes).
     */
    @NonNull
    public static PipelineMetric createInt(@NonNull String sessionUuid,
                                            @NonNull String metricType,
                                            long value) {
        PipelineMetric metric = new PipelineMetric();
        metric.sessionUuid = sessionUuid;
        metric.metricType = metricType;
        metric.valueInt = value;
        metric.recordedAtEpochMs = System.currentTimeMillis();
        return metric;
    }
}
