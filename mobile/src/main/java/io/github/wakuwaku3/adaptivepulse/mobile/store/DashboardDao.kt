package io.github.wakuwaku3.adaptivepulse.mobile.store

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * ダッシュボード用 Room キャッシュへの read/write 入口。書き込みは upsert (REPLACE) に倒し、
 * `HealthSyncWorker` が冪等に再投入できる前提にする。
 */
@Dao
interface DashboardDao {

    // -- daily snapshot --

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSnapshot(snapshot: DailySnapshotEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSnapshots(snapshots: List<DailySnapshotEntity>)

    @Query("SELECT * FROM daily_snapshot WHERE date = :date LIMIT 1")
    fun observeSnapshot(date: String): Flow<DailySnapshotEntity?>

    @Query("SELECT * FROM daily_snapshot WHERE date = :date LIMIT 1")
    suspend fun snapshot(date: String): DailySnapshotEntity?

    // backfill の「再読不要な日」判定用。5 年分でも高々 1825 行なので全件ロードで足りる
    @Query("SELECT * FROM daily_snapshot")
    suspend fun allSnapshots(): List<DailySnapshotEntity>

    @Query("SELECT * FROM daily_snapshot WHERE date BETWEEN :from AND :to ORDER BY date DESC")
    fun observeSnapshotRange(from: String, to: String): Flow<List<DailySnapshotEntity>>

    @Query("SELECT * FROM daily_snapshot WHERE date BETWEEN :from AND :to ORDER BY date DESC")
    suspend fun snapshotRange(from: String, to: String): List<DailySnapshotEntity>

    // initial backfill が走ったか判定するため。Room destructive migration で wipe された後は
    // ここが null か直近窓内に戻り、自動再 backfill のトリガになる
    @Query("SELECT MIN(date) FROM daily_snapshot")
    suspend fun oldestSnapshotDate(): String?

    // -- per-source breakdown --

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMetricBySource(rows: List<MetricBySourceEntity>)

    @Query("DELETE FROM metric_by_source WHERE date = :date AND metricKey = :metricKey")
    suspend fun clearMetricBySource(date: String, metricKey: String)

    @Transaction
    suspend fun replaceMetricBySource(date: String, metricKey: String, rows: List<MetricBySourceEntity>) {
        clearMetricBySource(date, metricKey)
        if (rows.isNotEmpty()) upsertMetricBySource(rows)
    }

    @Query("SELECT * FROM metric_by_source WHERE date = :date ORDER BY metricKey, sourcePackage")
    fun observeMetricBreakdown(date: String): Flow<List<MetricBySourceEntity>>

    // -- heart rate samples --

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHeartRateSamples(samples: List<HeartRateSampleEntity>)

    @Query("DELETE FROM heart_rate_sample WHERE timestampMs < :olderThanMs")
    suspend fun pruneHeartRateSamples(olderThanMs: Long)

    @Query("SELECT * FROM heart_rate_sample WHERE timestampMs BETWEEN :from AND :to ORDER BY timestampMs")
    fun observeHeartRateRange(from: Long, to: Long): Flow<List<HeartRateSampleEntity>>

    // -- vital samples --

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertVitalSamples(samples: List<VitalSampleEntity>)

    @Query("DELETE FROM vital_sample WHERE timestampMs < :olderThanMs")
    suspend fun pruneVitalSamples(olderThanMs: Long)

    @Query("SELECT * FROM vital_sample WHERE kind = :kind AND timestampMs BETWEEN :from AND :to ORDER BY timestampMs")
    fun observeVitalRange(kind: VitalKind, from: Long, to: Long): Flow<List<VitalSampleEntity>>

    // -- exercise sessions --

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertExerciseSessions(sessions: List<ExerciseSessionEntity>)

    @Query("SELECT * FROM exercise_session WHERE startTimeMs BETWEEN :from AND :to ORDER BY startTimeMs DESC")
    fun observeExerciseSessions(from: Long, to: Long): Flow<List<ExerciseSessionEntity>>
}
