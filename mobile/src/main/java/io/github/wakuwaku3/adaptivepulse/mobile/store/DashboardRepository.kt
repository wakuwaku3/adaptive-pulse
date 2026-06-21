package io.github.wakuwaku3.adaptivepulse.mobile.store

import android.content.Context
import android.util.Log
import io.github.wakuwaku3.adaptivepulse.core.sync.DailyHealthRecord
import io.github.wakuwaku3.adaptivepulse.core.sync.SessionRecord
import io.github.wakuwaku3.adaptivepulse.mobile.calories.CalorieEnricher
import io.github.wakuwaku3.adaptivepulse.mobile.health.HealthDataSource
import io.github.wakuwaku3.adaptivepulse.mobile.health.MetricBreakdownRow
import io.github.wakuwaku3.adaptivepulse.mobile.health.VitalKindData
import io.github.wakuwaku3.adaptivepulse.mobile.sync.FirestoreSync
import io.github.wakuwaku3.adaptivepulse.mobile.sync.PendingSessionStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val TAG = "AdaptivePulse"

/**
 * Health Connect (`HealthDataSource`) と Room (`DashboardDatabase`) の間に座る。
 * 同期ロジックの 1 か所だけがここを呼べばよくなる。
 */
class DashboardRepository(private val context: Context) {

    private val dao = DashboardDatabase.get(context).dashboardDao()
    private val hc = HealthDataSource(context)

    fun observeSnapshot(date: LocalDate): Flow<DailySnapshotEntity?> =
        dao.observeSnapshot(date.format(DateTimeFormatter.ISO_LOCAL_DATE))

    fun observeRecent(days: Int, today: LocalDate = LocalDate.now()): Flow<List<DailySnapshotEntity>> {
        val from = today.minusDays((days - 1).toLong()).format(DateTimeFormatter.ISO_LOCAL_DATE)
        val to = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        return dao.observeSnapshotRange(from, to)
    }

    suspend fun recentSnapshots(days: Int, today: LocalDate = LocalDate.now()): List<DailySnapshotEntity> {
        val from = today.minusDays((days - 1).toLong()).format(DateTimeFormatter.ISO_LOCAL_DATE)
        val to = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        return dao.snapshotRange(from, to)
    }

    fun observeMetricBreakdown(date: LocalDate): Flow<List<MetricBySourceEntity>> =
        dao.observeMetricBreakdown(date.format(DateTimeFormatter.ISO_LOCAL_DATE))

    fun observeHeartRateForDate(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()) =
        rangeOfDay(date, zone).let { (from, to) -> dao.observeHeartRateRange(from, to) }

    fun observeVitalForDate(kind: VitalKind, date: LocalDate, zone: ZoneId = ZoneId.systemDefault()) =
        rangeOfDay(date, zone).let { (from, to) -> dao.observeVitalRange(kind, from, to) }

    fun observeExerciseSessions(daysBack: Int, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): Flow<List<ExerciseSessionEntity>> {
        val from = today.minusDays((daysBack - 1).toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
        val to = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return dao.observeExerciseSessions(from, to)
    }

    /**
     * 1 日分を HC から読み直して Room に書き込む。集約と per-source breakdown も更新する。
     * 時系列 (HR / Vital) は [includeTimeSeries] = true のときだけ書き込み (容量制御)。
     * TDEE は HC の生 total を信頼せず、`CalorieEnricher` が BMR + 歩数 + 運動 extra で
     * 再計算する。[appSessionsForDate] にはその日の自社 HIIT セッションを渡す
     * (二重計上回避と HIIT extra の MET 加算のため)。
     */
    suspend fun syncDay(
        date: LocalDate,
        includeTimeSeries: Boolean,
        zone: ZoneId = ZoneId.systemDefault(),
        appSessionsForDate: List<SessionRecord> = emptyList(),
        ageYears: Int = 39,
    ) {
        if (!hc.available) {
            Log.i(TAG, "HC 利用不可なので sync skip")
            return
        }
        val snapshot = hc.readSnapshot(date, zone) ?: return
        val (from, to) = zonedRangeOfDay(date, zone)
        val hcSessions = hc.readExerciseSessions(from, to)
        val enriched = CalorieEnricher.enrich(snapshot.record, hcSessions, appSessionsForDate, ageYears)
        val dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
        dao.upsertSnapshot(enriched.toEntity(syncedAtMs = System.currentTimeMillis()))

        // breakdown は metric ごとに REPLACE して、書き込まないソースが消えたら自然に消える
        val byMetric = snapshot.breakdown.groupBy { it.metricKey }
        listOf(
            HealthDataSource.METRIC_STEPS,
            HealthDataSource.METRIC_DISTANCE,
            HealthDataSource.METRIC_FLOORS,
            HealthDataSource.METRIC_ACTIVE_KCAL,
            HealthDataSource.METRIC_TOTAL_KCAL,
            HealthDataSource.METRIC_BASAL_KCAL,
            HealthDataSource.METRIC_INTAKE_KCAL,
        ).forEach { metricKey ->
            val rows = (byMetric[metricKey] ?: emptyList()).map { it.toEntity(dateStr) }
            dao.replaceMetricBySource(dateStr, metricKey, rows)
        }

        if (includeTimeSeries) {
            val hrSamples = hc.readHeartRateSamples(from, to)
                .map { HeartRateSampleEntity(it.timestampMs, it.sourcePackage, it.bpm) }
            if (hrSamples.isNotEmpty()) dao.upsertHeartRateSamples(hrSamples)

            val vitals = hc.readVitalSamples(from, to).map { v ->
                VitalSampleEntity(
                    timestampMs = v.timestampMs,
                    kind = when (v.kind) {
                        VitalKindData.SPO2 -> VitalKind.SPO2
                        VitalKindData.RESPIRATORY_RATE -> VitalKind.RESPIRATORY_RATE
                        VitalKindData.SKIN_TEMPERATURE_DELTA -> VitalKind.SKIN_TEMPERATURE_DELTA
                    },
                    value = v.value,
                    sourcePackage = v.sourcePackage,
                )
            }
            if (vitals.isNotEmpty()) dao.upsertVitalSamples(vitals)

            val sessions = hcSessions.map { s ->
                ExerciseSessionEntity(
                    id = s.id,
                    startTimeMs = s.startTimeMs,
                    endTimeMs = s.endTimeMs,
                    exerciseType = s.exerciseType,
                    title = s.title,
                    sourcePackage = s.sourcePackage,
                )
            }
            if (sessions.isNotEmpty()) dao.upsertExerciseSessions(sessions)
        }
    }

    /**
     * 自社 HIIT セッションを Firestore + ローカル pending から日付別に集める。
     * `syncDay` 呼び出しの前に 1 回だけ呼んで、各日に該当分を渡すと N+1 を避けられる。
     */
    suspend fun loadAppSessionsByDate(
        zone: ZoneId = ZoneId.systemDefault(),
        firestoreLimit: Int = 500,
    ): Map<String, List<SessionRecord>> {
        val remote = FirestoreSync.listSessions(limit = firestoreLimit).orEmpty()
        val local = PendingSessionStore(context).list()
        return (remote + local)
            .distinctBy { it.id }
            .groupBy { Instant.ofEpochMilli(it.startedAtMs).atZone(zone).toLocalDate().toString() }
    }

    /** 時系列の古いサンプルを掃除する (今日 + 昨日のみ保持) */
    suspend fun pruneOldTimeSeries(today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()) {
        val cutoff = today.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        dao.pruneHeartRateSamples(cutoff)
        dao.pruneVitalSamples(cutoff)
    }

    /** Room 上の指定日 snapshot を `DailyHealthRecord` に変換 (Firestore upload 用) */
    suspend fun snapshotsAsRecords(days: Int, today: LocalDate = LocalDate.now()): List<DailyHealthRecord> {
        return recentSnapshots(days, today).map { it.toRecord() }
    }

    private fun rangeOfDay(date: LocalDate, zone: ZoneId): Pair<Long, Long> {
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return start to end
    }

    private fun zonedRangeOfDay(date: LocalDate, zone: ZoneId) =
        date.atStartOfDay(zone) to date.plusDays(1).atStartOfDay(zone)
}

private fun MetricBreakdownRow.toEntity(date: String): MetricBySourceEntity =
    MetricBySourceEntity(
        date = date,
        metricKey = metricKey,
        sourcePackage = sourcePackage,
        value = value,
    )

internal fun DailyHealthRecord.toEntity(syncedAtMs: Long): DailySnapshotEntity = DailySnapshotEntity(
    date = date,
    syncedAtMs = syncedAtMs,
    restingHeartRateBpm = restingHeartRateBpm,
    hrvRmssdMs = hrvRmssdMs,
    avgHeartRateBpm = avgHeartRateBpm,
    minHeartRateBpm = minHeartRateBpm,
    maxHeartRateBpm = maxHeartRateBpm,
    sleepDurationMin = sleepDurationMin,
    sleepDeepMin = sleepDeepMin,
    sleepRemMin = sleepRemMin,
    sleepLightMin = sleepLightMin,
    sleepAwakeMin = sleepAwakeMin,
    steps = steps,
    distanceMeters = distanceMeters,
    floorsClimbed = floorsClimbed,
    elevationGainedMeters = elevationGainedMeters,
    activeCaloriesKcal = activeCaloriesKcal,
    totalCaloriesKcal = totalCaloriesKcal,
    basalCaloriesKcal = basalCaloriesKcal,
    tdeeKcal = tdeeKcal,
    exerciseExtraKcal = exerciseExtraKcal,
    weightKg = weightKg,
    bodyFatPct = bodyFatPct,
    leanBodyMassKg = leanBodyMassKg,
    heightCm = heightCm,
    intakeKcal = intakeKcal,
    proteinG = proteinG,
    fatG = fatG,
    carbsG = carbsG,
    fiberG = fiberG,
    sugarG = sugarG,
    sodiumMg = sodiumMg,
    spo2AvgPct = spo2AvgPct,
    spo2MinPct = spo2MinPct,
    respiratoryRateAvg = respiratoryRateAvg,
    skinTemperatureDeltaC = skinTemperatureDeltaC,
)

internal fun DailySnapshotEntity.toRecord(): DailyHealthRecord = DailyHealthRecord(
    date = date,
    restingHeartRateBpm = restingHeartRateBpm,
    hrvRmssdMs = hrvRmssdMs,
    avgHeartRateBpm = avgHeartRateBpm,
    minHeartRateBpm = minHeartRateBpm,
    maxHeartRateBpm = maxHeartRateBpm,
    sleepDurationMin = sleepDurationMin,
    sleepDeepMin = sleepDeepMin,
    sleepRemMin = sleepRemMin,
    sleepLightMin = sleepLightMin,
    sleepAwakeMin = sleepAwakeMin,
    steps = steps,
    distanceMeters = distanceMeters,
    floorsClimbed = floorsClimbed,
    elevationGainedMeters = elevationGainedMeters,
    activeCaloriesKcal = activeCaloriesKcal,
    totalCaloriesKcal = totalCaloriesKcal,
    basalCaloriesKcal = basalCaloriesKcal,
    tdeeKcal = tdeeKcal,
    exerciseExtraKcal = exerciseExtraKcal,
    weightKg = weightKg,
    bodyFatPct = bodyFatPct,
    leanBodyMassKg = leanBodyMassKg,
    heightCm = heightCm,
    intakeKcal = intakeKcal,
    proteinG = proteinG,
    fatG = fatG,
    carbsG = carbsG,
    fiberG = fiberG,
    sugarG = sugarG,
    sodiumMg = sodiumMg,
    spo2AvgPct = spo2AvgPct,
    spo2MinPct = spo2MinPct,
    respiratoryRateAvg = respiratoryRateAvg,
    skinTemperatureDeltaC = skinTemperatureDeltaC,
)
