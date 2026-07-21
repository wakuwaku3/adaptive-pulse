package io.github.wakuwaku3.adaptivepulse.mobile.store

import android.content.Context
import android.util.Log
import io.github.wakuwaku3.adaptivepulse.core.sync.DailyHealthRecord
import io.github.wakuwaku3.adaptivepulse.core.sync.ExternalExerciseSession
import io.github.wakuwaku3.adaptivepulse.core.sync.MetricSourceValue
import io.github.wakuwaku3.adaptivepulse.core.sync.SessionRecord
import io.github.wakuwaku3.adaptivepulse.mobile.calories.CalorieEnricher
import io.github.wakuwaku3.adaptivepulse.mobile.health.HealthDataSource
import io.github.wakuwaku3.adaptivepulse.mobile.health.MetricBreakdownRow
import io.github.wakuwaku3.adaptivepulse.mobile.health.VitalKindData
import io.github.wakuwaku3.adaptivepulse.mobile.sync.FirestoreSync
import io.github.wakuwaku3.adaptivepulse.mobile.sync.PendingSessionStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
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

    companion object {
        /** per-source breakdown を保持する指標。空 REPLACE で消すときも同じ集合を回す */
        private val METRIC_KEYS = listOf(
            HealthDataSource.METRIC_STEPS,
            HealthDataSource.METRIC_DISTANCE,
            HealthDataSource.METRIC_FLOORS,
            HealthDataSource.METRIC_ACTIVE_KCAL,
            HealthDataSource.METRIC_TOTAL_KCAL,
            HealthDataSource.METRIC_BASAL_KCAL,
            HealthDataSource.METRIC_INTAKE_KCAL,
        )
    }

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

    suspend fun oldestSnapshotDate(): String? = dao.oldestSnapshotDate()

    /** [sinceMs] 以降にクリーン読みで確定した日付集合 (Resync の再開スキップ判定用) */
    suspend fun datesVerifiedSince(sinceMs: Long): Set<String> =
        dao.datesVerifiedSince(sinceMs).toSet()

    /**
     * 実測データ入りの行の日付集合。遡及同期が確定済みの過去日を再読しないための
     * スキップ判定に使う。空行 (遡及試行済みマーカーや null で潰れた行) と、HC の
     * BMR 由来合成カロリーしか無い行 (履歴権限なし等で実際は読めていない日) は
     * 含まれないので、再読対象として自然に拾い直される (`DailyHealthRecord.hasMeasuredData`)。
     */
    suspend fun datesWithData(): Set<String> =
        dao.allSnapshots().asSequence()
            .filter { it.toRecord().hasMeasuredData }
            .map { it.date }
            .toSet()

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
     * 再計算する (本アプリがマスター)。[appSessionsForDate] にはその日の自社 HIIT
     * セッションを渡す (二重計上回避と HIIT extra の MET 加算のため)。
     *
     * 上書きポリシー: 例外ゼロのクリーン読みは HC の現状そのものなので、空でも上書きして
     * HC 側で削除されたデータをキャッシュへ伝播させる (FB 2026-07-21)。例外が出た読みは
     * 「読めなかった」可能性があるため既存行を温存する。
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
        val dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
        if (snapshot.readFailed) {
            // レート制限・一時障害の巻き添えで正しいキャッシュを潰さない。行が無い日だけ
            // 空マーカー (verifiedAtMs = null) を残して oldestSnapshotDate 判定を成立させる
            if (dao.snapshot(dateStr) == null && snapshot.record.isEmpty) {
                dao.upsertSnapshot(snapshot.record.toEntity(syncedAtMs = System.currentTimeMillis()))
            } else if (!snapshot.record.isEmpty) {
                Log.i(TAG, "HC 読み取りに失敗を含むため $dateStr は部分データで上書きしない")
            }
            return
        }
        val verifiedAtMs = System.currentTimeMillis()
        if (snapshot.record.isEmpty) {
            // クリーン読みの空 = HC にデータが無い確証 (削除済み含む)。既存行ごと空で上書きし、
            // 内訳も消して HC の現状に合わせる
            dao.upsertSnapshot(
                snapshot.record.toEntity(syncedAtMs = verifiedAtMs, verifiedAtMs = verifiedAtMs),
            )
            METRIC_KEYS.forEach { dao.replaceMetricBySource(dateStr, it, emptyList()) }
            return
        }
        val (from, to) = zonedRangeOfDay(date, zone)
        val hcSessions = hc.readExerciseSessions(from, to)
        // 当日に体重実測が無い日でも TDEE は出したい (体重未測の日に消費だけ消えるのは体験ロスが大きい)
        val fallbackWeightKg = if (snapshot.record.weightKg == null) hc.readLatestWeightKgBefore(to) else null
        val enriched = CalorieEnricher.enrich(snapshot.record, hcSessions, appSessionsForDate, ageYears, fallbackWeightKg)
            // HC には端末横断でアクセスできないので、読み取り済みの内訳・他アプリセッションも
            // レコードに同梱して Firestore まで届ける (大容量時系列は対象外)。空は null に
            // 正規化して isEmpty 判定を保つ
            .copy(
                breakdown = snapshot.breakdown
                    .map { MetricSourceValue(it.metricKey, it.sourcePackage, it.value) }
                    .takeIf { it.isNotEmpty() },
                externalSessions = hcSessions
                    .map {
                        ExternalExerciseSession(
                            id = it.id,
                            startTimeMs = it.startTimeMs,
                            endTimeMs = it.endTimeMs,
                            exerciseType = it.exerciseType,
                            title = it.title,
                            sourcePackage = it.sourcePackage,
                        )
                    }
                    .takeIf { it.isNotEmpty() },
            )
        dao.upsertSnapshot(enriched.toEntity(syncedAtMs = verifiedAtMs, verifiedAtMs = verifiedAtMs))

        // breakdown は metric ごとに REPLACE して、書き込まないソースが消えたら自然に消える
        val byMetric = snapshot.breakdown.groupBy { it.metricKey }
        METRIC_KEYS.forEach { metricKey ->
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

    /**
     * Firestore 未反映の行をすべて upsert し、成功分に uploadedAtMs を付ける。
     * 行単位のマークなので、worker が途中停止しても次の実行 (通常/遡及どちらでも) が
     * 続きから上げられる。「1 回の実行で読んだ日」のような実行内メモリに依存しない。
     * 戻り値は失敗数 (0 なら全反映済み)。
     *
     * 空行 (遡及試行済みマーカー) は `upsertDailyHealth` が isEmpty ガードで skip して
     * true を返すため、アップロード済み扱いでマークされる。データが入って行が
     * 書き直されれば uploadedAtMs が null に戻り、再び対象になる。
     */
    suspend fun flushUnuploaded(nowMs: Long = System.currentTimeMillis()): Int {
        val pending = dao.unuploadedSnapshots()
        if (pending.isEmpty()) return 0
        val succeeded = mutableListOf<String>()
        var failed = 0
        pending.forEach { entity ->
            // クリーン読みで検証済み (verifiedAtMs 有り) の行は空でも上書きする:
            // HC 側で削除されたデータを Firestore にも伝播させるため。検証なしの空
            // (読み取り失敗時のマーカー) は従来通り skip される
            val ok = FirestoreSync.upsertDailyHealth(
                entity.toRecord(),
                allowEmpty = entity.verifiedAtMs != null,
            )
            if (ok) succeeded += entity.date else failed++
        }
        // SQLite の IN 句変数上限 (999) を超えないよう分割して UPDATE する
        succeeded.chunked(500).forEach { dao.markUploaded(it, nowMs) }
        Log.i(TAG, "Firestore flush: ${succeeded.size}/${pending.size} 反映 (失敗 $failed)")
        return failed
    }

    private fun rangeOfDay(date: LocalDate, zone: ZoneId): Pair<Long, Long> {
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return start to end
    }

    private fun zonedRangeOfDay(date: LocalDate, zone: ZoneId) =
        date.atStartOfDay(zone) to date.plusDays(1).atStartOfDay(zone)
}

/** entity の JSON 列 (breakdown / externalSessions) の変換用。スキーマ進化に耐えるよう unknown keys は無視 */
private val entityJson = Json { ignoreUnknownKeys = true }

private fun MetricBreakdownRow.toEntity(date: String): MetricBySourceEntity =
    MetricBySourceEntity(
        date = date,
        metricKey = metricKey,
        sourcePackage = sourcePackage,
        value = value,
    )

internal fun DailyHealthRecord.toEntity(syncedAtMs: Long, verifiedAtMs: Long? = null): DailySnapshotEntity = DailySnapshotEntity(
    date = date,
    syncedAtMs = syncedAtMs,
    verifiedAtMs = verifiedAtMs,
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
    breakdownJson = breakdown?.let {
        entityJson.encodeToString(ListSerializer(MetricSourceValue.serializer()), it)
    },
    externalSessionsJson = externalSessions?.let {
        entityJson.encodeToString(ListSerializer(ExternalExerciseSession.serializer()), it)
    },
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
    breakdown = breakdownJson?.let { s ->
        runCatching {
            entityJson.decodeFromString(ListSerializer(MetricSourceValue.serializer()), s)
        }.getOrNull()
    },
    externalSessions = externalSessionsJson?.let { s ->
        runCatching {
            entityJson.decodeFromString(ListSerializer(ExternalExerciseSession.serializer()), s)
        }.getOrNull()
    },
)
