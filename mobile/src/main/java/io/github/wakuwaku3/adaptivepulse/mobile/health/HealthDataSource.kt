package io.github.wakuwaku3.adaptivepulse.mobile.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import io.github.wakuwaku3.adaptivepulse.core.sync.DailyHealthRecord
import io.github.wakuwaku3.adaptivepulse.core.sync.SleepDayWindow
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private const val TAG = "AdaptivePulse"

/**
 * Health Connect から日次の健康指標を集めて [DailyHealthRecord] に詰める + ダッシュボード
 * 表示用の per-source breakdown / 時系列を返す。
 *
 * 取得方針:
 *  - 数値メトリクス (歩数 / カロリー / 栄養) は aggregate API で日次集計を 1 リクエストで取る。
 *  - サンプル列 (心拍 / 安静時心拍 / HRV / 体重・体脂肪・LBM・身長) は最新値 1 点が
 *    意味を持つので read API で当日分を読み、平均 (or 最新) を取る。
 *  - 睡眠は [SleepSessionRecord] を「夕方 18:00 〜 翌 18:00」の sleep day で取り、stage 別に合算する
 *    (深夜入眠が翌日扱いになる暦日 cut の問題を回避。[SleepDayWindow] 参照)。
 *  - per-source breakdown は aggregate では返らないので readRecords + dataOrigin.packageName で
 *    集計し直す。
 *
 * 権限が一部しか付与されていない場合、付与済みのデータ種だけ埋め、欠損は null のまま返す。
 */
class HealthDataSource(private val context: Context) {

    val available: Boolean
        get() = HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    private val client by lazy {
        if (available) HealthConnectClient.getOrCreate(context) else null
    }

    suspend fun grantedPermissions(): Set<String> =
        client?.permissionController?.getGrantedPermissions().orEmpty()

    /** 過去 [days] 日 (今日を除く昨日まで) の集計を新しい順に返す (Firestore upload 互換) */
    suspend fun readDailySummaries(days: Int, zone: ZoneId = ZoneId.systemDefault()): List<DailyHealthRecord> {
        val hc = client ?: return emptyList()
        val granted = grantedPermissions()
        val yesterday = LocalDate.now(zone).minusDays(1)
        return (0 until days).map { offset ->
            val date = yesterday.minusDays(offset.toLong())
            readDay(hc, granted, date, zone)
        }
    }

    /**
     * 指定時点までで HC に記録された最新の体重 (kg) を返す。当日に体重が記録されていない日でも
     * TDEE を計算したいケース (`CalorieEnricher.enrich(fallbackWeightKg=...)`) で使う。
     * 表示用の体重 / BMI には使わない (当日実測の欠損は欠損のまま見せる)。
     */
    suspend fun readLatestWeightKgBefore(end: ZonedDateTime): Double? {
        val hc = client ?: return null
        val granted = grantedPermissions()
        return readLatestEver(hc, granted, end, WeightRecord::class) { it.weight.inKilograms }
    }

    /**
     * ダッシュボード用に 1 日分のスナップショットを per-source breakdown 付きで取得する。
     * [date] は今日を含めて任意の暦日を指定可。
     */
    suspend fun readSnapshot(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): SnapshotResult? {
        val hc = client ?: return null
        val granted = grantedPermissions()
        val record = readDay(hc, granted, date, zone)
        val breakdown = readBreakdown(hc, granted, date, zone)
        return SnapshotResult(record, breakdown)
    }

    /** [from] (inclusive) 〜 [to] (exclusive) の HR サンプルをデータソース付きで返す */
    suspend fun readHeartRateSamples(from: ZonedDateTime, to: ZonedDateTime): List<HrSample> {
        val hc = client ?: return emptyList()
        val granted = grantedPermissions()
        if (HealthPermission.getReadPermission(HeartRateRecord::class) !in granted) return emptyList()
        val range = TimeRangeFilter.between(from.toInstant(), to.toInstant())
        return runCatching {
            hc.readRecords(ReadRecordsRequest(HeartRateRecord::class, range)).records.flatMap { rec ->
                val origin = rec.metadata.dataOrigin.packageName
                rec.samples.map { sample ->
                    HrSample(
                        timestampMs = sample.time.toEpochMilli(),
                        bpm = sample.beatsPerMinute.toInt(),
                        sourcePackage = origin,
                    )
                }
            }
        }.onFailure { Log.w(TAG, "HC HR samples 読み込み失敗", it) }.getOrElse { emptyList() }
    }

    suspend fun readVitalSamples(from: ZonedDateTime, to: ZonedDateTime): List<VitalSample> {
        val hc = client ?: return emptyList()
        val granted = grantedPermissions()
        val range = TimeRangeFilter.between(from.toInstant(), to.toInstant())
        val out = mutableListOf<VitalSample>()
        if (HealthPermission.getReadPermission(OxygenSaturationRecord::class) in granted) {
            runCatching {
                hc.readRecords(ReadRecordsRequest(OxygenSaturationRecord::class, range)).records.forEach { rec ->
                    out += VitalSample(
                        timestampMs = rec.time.toEpochMilli(),
                        kind = VitalKindData.SPO2,
                        value = rec.percentage.value,
                        sourcePackage = rec.metadata.dataOrigin.packageName,
                    )
                }
            }.onFailure { Log.w(TAG, "HC SpO2 読み込み失敗", it) }
        }
        if (HealthPermission.getReadPermission(RespiratoryRateRecord::class) in granted) {
            runCatching {
                hc.readRecords(ReadRecordsRequest(RespiratoryRateRecord::class, range)).records.forEach { rec ->
                    out += VitalSample(
                        timestampMs = rec.time.toEpochMilli(),
                        kind = VitalKindData.RESPIRATORY_RATE,
                        value = rec.rate,
                        sourcePackage = rec.metadata.dataOrigin.packageName,
                    )
                }
            }.onFailure { Log.w(TAG, "HC RespiratoryRate 読み込み失敗", it) }
        }
        if (HealthPermission.getReadPermission(SkinTemperatureRecord::class) in granted) {
            runCatching {
                hc.readRecords(ReadRecordsRequest(SkinTemperatureRecord::class, range)).records.forEach { rec ->
                    // SkinTemperatureRecord は baseline からの差分を deltas として持つ。
                    // 最新の delta の値 (摂氏) を1サンプルとして使う (各 record の代表値)。
                    val delta = rec.deltas.lastOrNull()?.delta?.inCelsius ?: return@forEach
                    out += VitalSample(
                        timestampMs = rec.endTime.toEpochMilli(),
                        kind = VitalKindData.SKIN_TEMPERATURE_DELTA,
                        value = delta,
                        sourcePackage = rec.metadata.dataOrigin.packageName,
                    )
                }
            }.onFailure { Log.w(TAG, "HC SkinTemperature 読み込み失敗", it) }
        }
        return out
    }

    /**
     * 自前 TDEE を `TotalCaloriesBurnedRecord` として HC に書き出し、本アプリを HC 上の
     * カロリー master にする。1 日 1 レコード (`clientRecordId = "ap-total-{date}"`) で
     * 冪等に upsert する。今日の場合は end が未来にならないよう now で頭打ちにする
     * (HC は endTime > now を弾くため)。
     */
    suspend fun writeDailyTotalCalories(
        date: LocalDate,
        tdeeKcal: Double,
        zone: ZoneId = ZoneId.systemDefault(),
        now: java.time.Instant = java.time.Instant.now(),
    ): Boolean {
        val hc = client ?: return false
        if (tdeeKcal <= 0.0) return false
        val granted = grantedPermissions()
        if (HealthPermission.getWritePermission(TotalCaloriesBurnedRecord::class) !in granted) return false

        val start = date.atStartOfDay(zone).toInstant()
        val rawEnd = date.plusDays(1).atStartOfDay(zone).toInstant()
        val end = if (rawEnd.isAfter(now)) now else rawEnd
        if (!end.isAfter(start)) return false
        val startOffset = zone.rules.getOffset(start)
        val endOffset = zone.rules.getOffset(end)

        val record = TotalCaloriesBurnedRecord(
            startTime = start,
            startZoneOffset = startOffset,
            endTime = end,
            endZoneOffset = endOffset,
            energy = androidx.health.connect.client.units.Energy.kilocalories(tdeeKcal),
            metadata = androidx.health.connect.client.records.metadata.Metadata(
                clientRecordId = "ap-total-$date",
                recordingMethod = androidx.health.connect.client.records.metadata.Metadata.RECORDING_METHOD_AUTOMATICALLY_RECORDED,
            ),
        )
        return runCatching {
            hc.insertRecords(listOf(record))
            Log.i(TAG, "HC total write OK: $date (${tdeeKcal.roundToInt()} kcal)")
            true
        }.onFailure { Log.w(TAG, "HC total write 失敗: $date", it) }.getOrDefault(false)
    }

    suspend fun readExerciseSessions(from: ZonedDateTime, to: ZonedDateTime): List<ExerciseSessionData> {
        val hc = client ?: return emptyList()
        val granted = grantedPermissions()
        if (HealthPermission.getReadPermission(ExerciseSessionRecord::class) !in granted) return emptyList()
        val range = TimeRangeFilter.between(from.toInstant(), to.toInstant())
        return runCatching {
            hc.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, range)).records.map { rec ->
                ExerciseSessionData(
                    id = rec.metadata.id,
                    startTimeMs = rec.startTime.toEpochMilli(),
                    endTimeMs = rec.endTime.toEpochMilli(),
                    exerciseType = rec.exerciseType,
                    title = rec.title,
                    sourcePackage = rec.metadata.dataOrigin.packageName,
                )
            }
        }.onFailure { Log.w(TAG, "HC ExerciseSession 読み込み失敗", it) }.getOrElse { emptyList() }
    }

    private suspend fun readDay(
        hc: HealthConnectClient,
        granted: Set<String>,
        date: LocalDate,
        zone: ZoneId,
    ): DailyHealthRecord {
        val start = date.atStartOfDay(zone)
        val end = date.plusDays(1).atStartOfDay(zone)
        val range = TimeRangeFilter.between(start.toInstant(), end.toInstant())

        val aggregateMetrics = buildSet<AggregateMetric<*>> {
            if (HealthPermission.getReadPermission(StepsRecord::class) in granted) {
                add(StepsRecord.COUNT_TOTAL)
            }
            if (HealthPermission.getReadPermission(DistanceRecord::class) in granted) {
                add(DistanceRecord.DISTANCE_TOTAL)
            }
            if (HealthPermission.getReadPermission(FloorsClimbedRecord::class) in granted) {
                add(FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL)
            }
            if (HealthPermission.getReadPermission(ElevationGainedRecord::class) in granted) {
                add(ElevationGainedRecord.ELEVATION_GAINED_TOTAL)
            }
            if (HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class) in granted) {
                add(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)
            }
            if (HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class) in granted) {
                add(TotalCaloriesBurnedRecord.ENERGY_TOTAL)
            }
            if (HealthPermission.getReadPermission(BasalMetabolicRateRecord::class) in granted) {
                add(BasalMetabolicRateRecord.BASAL_CALORIES_TOTAL)
            }
            if (HealthPermission.getReadPermission(NutritionRecord::class) in granted) {
                add(NutritionRecord.ENERGY_TOTAL)
                add(NutritionRecord.PROTEIN_TOTAL)
                add(NutritionRecord.TOTAL_FAT_TOTAL)
                add(NutritionRecord.TOTAL_CARBOHYDRATE_TOTAL)
                add(NutritionRecord.DIETARY_FIBER_TOTAL)
                add(NutritionRecord.SUGAR_TOTAL)
                add(NutritionRecord.SODIUM_TOTAL)
            }
        }
        val agg = if (aggregateMetrics.isNotEmpty()) {
            runCatching {
                hc.aggregate(AggregateRequest(metrics = aggregateMetrics, timeRangeFilter = range))
            }.onFailure { Log.w(TAG, "HC aggregate 失敗: $date", it) }.getOrNull()
        } else null

        val hrStats = readHrStats(hc, granted, range)
        val spo2 = readSpo2Stats(hc, granted, range)
        val respiratoryRate = readRespiratoryAvg(hc, granted, range)
        val skinTempDelta = readSkinTempLatest(hc, granted, range)

        return DailyHealthRecord(
            date = date.toString(),
            restingHeartRateBpm = readRestingHeartRate(hc, granted, range),
            hrvRmssdMs = readHrv(hc, granted, range),
            avgHeartRateBpm = hrStats?.avg,
            minHeartRateBpm = hrStats?.min,
            maxHeartRateBpm = hrStats?.max,
            sleepDurationMin = readSleepDuration(hc, granted, date, zone),
            sleepDeepMin = readSleepStage(hc, granted, date, zone, SleepSessionRecord.STAGE_TYPE_DEEP),
            sleepRemMin = readSleepStage(hc, granted, date, zone, SleepSessionRecord.STAGE_TYPE_REM),
            sleepLightMin = readSleepStage(hc, granted, date, zone, SleepSessionRecord.STAGE_TYPE_LIGHT),
            sleepAwakeMin = readSleepStage(hc, granted, date, zone, SleepSessionRecord.STAGE_TYPE_AWAKE),
            steps = agg?.get(StepsRecord.COUNT_TOTAL),
            distanceMeters = agg?.get(DistanceRecord.DISTANCE_TOTAL)?.inMeters,
            floorsClimbed = agg?.get(FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL),
            elevationGainedMeters = agg?.get(ElevationGainedRecord.ELEVATION_GAINED_TOTAL)?.inMeters,
            activeCaloriesKcal = agg?.get(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)?.inKilocalories,
            totalCaloriesKcal = agg?.get(TotalCaloriesBurnedRecord.ENERGY_TOTAL)?.inKilocalories,
            basalCaloriesKcal = agg?.get(BasalMetabolicRateRecord.BASAL_CALORIES_TOTAL)?.inKilocalories,
            weightKg = readLatest(hc, granted, range, WeightRecord::class) { it.weight.inKilograms },
            bodyFatPct = readLatest(hc, granted, range, BodyFatRecord::class) { it.percentage.value },
            leanBodyMassKg = readLatest(hc, granted, range, LeanBodyMassRecord::class) { it.mass.inKilograms },
            heightCm = readLatestEver(hc, granted, end, HeightRecord::class) { it.height.inMeters * 100.0 },
            intakeKcal = agg?.get(NutritionRecord.ENERGY_TOTAL)?.inKilocalories,
            proteinG = agg?.get(NutritionRecord.PROTEIN_TOTAL)?.inGrams,
            fatG = agg?.get(NutritionRecord.TOTAL_FAT_TOTAL)?.inGrams,
            carbsG = agg?.get(NutritionRecord.TOTAL_CARBOHYDRATE_TOTAL)?.inGrams,
            fiberG = agg?.get(NutritionRecord.DIETARY_FIBER_TOTAL)?.inGrams,
            sugarG = agg?.get(NutritionRecord.SUGAR_TOTAL)?.inGrams,
            sodiumMg = agg?.get(NutritionRecord.SODIUM_TOTAL)?.inMilligrams,
            spo2AvgPct = spo2?.first,
            spo2MinPct = spo2?.second,
            respiratoryRateAvg = respiratoryRate,
            skinTemperatureDeltaC = skinTempDelta,
        )
    }

    /**
     * 主要指標 (TDEE / 活動カロリー / 歩数 / 距離 / フロア / 摂取カロリー) をデータソース別に合計。
     * watch / phone / Fit がそれぞれ書いている値の差を可視化するため。
     */
    private suspend fun readBreakdown(
        hc: HealthConnectClient,
        granted: Set<String>,
        date: LocalDate,
        zone: ZoneId,
    ): List<MetricBreakdownRow> {
        val start = date.atStartOfDay(zone)
        val end = date.plusDays(1).atStartOfDay(zone)
        val range = TimeRangeFilter.between(start.toInstant(), end.toInstant())
        val out = mutableListOf<MetricBreakdownRow>()

        suspend fun <T : Record> collect(
            type: kotlin.reflect.KClass<T>,
            metricKey: String,
            extract: (T) -> Double,
        ) {
            if (HealthPermission.getReadPermission(type) !in granted) return
            runCatching {
                hc.readRecords(ReadRecordsRequest(type, range)).records
                    .groupBy { it.metadata.dataOrigin.packageName }
                    .forEach { (origin, list) ->
                        val sum = list.sumOf(extract)
                        if (sum > 0.0) out += MetricBreakdownRow(metricKey, origin, sum)
                    }
            }.onFailure { Log.w(TAG, "HC breakdown 失敗: $metricKey", it) }
        }

        collect(StepsRecord::class, METRIC_STEPS) { it.count.toDouble() }
        collect(DistanceRecord::class, METRIC_DISTANCE) { it.distance.inMeters }
        collect(FloorsClimbedRecord::class, METRIC_FLOORS) { it.floors }
        collect(ActiveCaloriesBurnedRecord::class, METRIC_ACTIVE_KCAL) { it.energy.inKilocalories }
        collect(TotalCaloriesBurnedRecord::class, METRIC_TOTAL_KCAL) { it.energy.inKilocalories }
        collect(BasalMetabolicRateRecord::class, METRIC_BASAL_KCAL) {
            // 瞬時値 (BasalMetabolicRate) なので時間で重み付けたい場面はあるが、
            // 日次合計で十分粗く把握できる
            it.basalMetabolicRate.inKilocaloriesPerDay
        }
        collect(NutritionRecord::class, METRIC_INTAKE_KCAL) { it.energy?.inKilocalories ?: 0.0 }
        return out
    }

    private suspend fun readHrStats(
        hc: HealthConnectClient,
        granted: Set<String>,
        range: TimeRangeFilter,
    ): HrStats? {
        if (HealthPermission.getReadPermission(HeartRateRecord::class) !in granted) return null
        return runCatching {
            val bpms = hc.readRecords(ReadRecordsRequest(HeartRateRecord::class, range))
                .records.flatMap { it.samples }.map { it.beatsPerMinute.toInt() }
            if (bpms.isEmpty()) null else HrStats(
                avg = bpms.average().roundToInt(),
                min = bpms.min(),
                max = bpms.max(),
            )
        }.onFailure { Log.w(TAG, "HC HR 読み込み失敗", it) }.getOrNull()
    }

    private suspend fun readSpo2Stats(
        hc: HealthConnectClient,
        granted: Set<String>,
        range: TimeRangeFilter,
    ): Pair<Double, Double>? {
        if (HealthPermission.getReadPermission(OxygenSaturationRecord::class) !in granted) return null
        return runCatching {
            val values = hc.readRecords(ReadRecordsRequest(OxygenSaturationRecord::class, range))
                .records.map { it.percentage.value }
            if (values.isEmpty()) null else (values.average() to values.min())
        }.onFailure { Log.w(TAG, "HC SpO2 読み込み失敗", it) }.getOrNull()
    }

    private suspend fun readRespiratoryAvg(
        hc: HealthConnectClient,
        granted: Set<String>,
        range: TimeRangeFilter,
    ): Double? {
        if (HealthPermission.getReadPermission(RespiratoryRateRecord::class) !in granted) return null
        return runCatching {
            val values = hc.readRecords(ReadRecordsRequest(RespiratoryRateRecord::class, range))
                .records.map { it.rate }
            if (values.isEmpty()) null else values.average()
        }.onFailure { Log.w(TAG, "HC RespiratoryRate 読み込み失敗", it) }.getOrNull()
    }

    private suspend fun readSkinTempLatest(
        hc: HealthConnectClient,
        granted: Set<String>,
        range: TimeRangeFilter,
    ): Double? {
        if (HealthPermission.getReadPermission(SkinTemperatureRecord::class) !in granted) return null
        return runCatching {
            // baseline からの delta (摂氏) の中央値を 1 日の代表値とする
            val deltas = hc.readRecords(ReadRecordsRequest(SkinTemperatureRecord::class, range))
                .records.flatMap { it.deltas }.map { it.delta.inCelsius }
            if (deltas.isEmpty()) null else deltas.sorted()[deltas.size / 2]
        }.onFailure { Log.w(TAG, "HC SkinTemperature 読み込み失敗", it) }.getOrNull()
    }

    private suspend fun readRestingHeartRate(
        hc: HealthConnectClient,
        granted: Set<String>,
        range: TimeRangeFilter,
    ): Int? {
        if (HealthPermission.getReadPermission(RestingHeartRateRecord::class) !in granted) return null
        return runCatching {
            hc.readRecords(ReadRecordsRequest(RestingHeartRateRecord::class, range))
                .records.lastOrNull()?.beatsPerMinute?.toInt()
        }.onFailure { Log.w(TAG, "HC RHR 読み込み失敗", it) }.getOrNull()
    }

    private suspend fun readHrv(
        hc: HealthConnectClient,
        granted: Set<String>,
        range: TimeRangeFilter,
    ): Double? {
        if (HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class) !in granted) return null
        return runCatching {
            val list = hc.readRecords(ReadRecordsRequest(HeartRateVariabilityRmssdRecord::class, range))
                .records.map { it.heartRateVariabilityMillis }
            if (list.isEmpty()) null else list.average()
        }.onFailure { Log.w(TAG, "HC HRV 読み込み失敗", it) }.getOrNull()
    }

    private suspend fun readSleepDuration(
        hc: HealthConnectClient,
        granted: Set<String>,
        date: LocalDate,
        zone: ZoneId,
    ): Long? {
        if (HealthPermission.getReadPermission(SleepSessionRecord::class) !in granted) return null
        val window = sleepWindow(date, zone)
        return runCatching {
            val total = hc.readRecords(ReadRecordsRequest(SleepSessionRecord::class, window))
                .records
                .sumOf { (it.endTime.toEpochMilli() - it.startTime.toEpochMilli()) / 60_000.0 }
            if (total <= 0.0) null else total.roundToLong()
        }.onFailure { Log.w(TAG, "HC sleep 読み込み失敗", it) }.getOrNull()
    }

    private suspend fun readSleepStage(
        hc: HealthConnectClient,
        granted: Set<String>,
        date: LocalDate,
        zone: ZoneId,
        stage: Int,
    ): Long? {
        if (HealthPermission.getReadPermission(SleepSessionRecord::class) !in granted) return null
        val window = sleepWindow(date, zone)
        return runCatching {
            val total = hc.readRecords(ReadRecordsRequest(SleepSessionRecord::class, window))
                .records
                .flatMap { it.stages }
                .filter { it.stage == stage }
                .sumOf { (it.endTime.toEpochMilli() - it.startTime.toEpochMilli()) / 60_000.0 }
            if (total <= 0.0) null else total.roundToLong()
        }.onFailure { Log.w(TAG, "HC sleep stage 読み込み失敗", it) }.getOrNull()
    }

    private fun sleepWindow(date: LocalDate, zone: ZoneId): TimeRangeFilter =
        TimeRangeFilter.between(
            SleepDayWindow.startOf(date, zone).toInstant(),
            SleepDayWindow.endOf(date, zone).toInstant(),
        )

    private suspend fun <T : Record> readLatest(
        hc: HealthConnectClient,
        granted: Set<String>,
        range: TimeRangeFilter,
        type: kotlin.reflect.KClass<T>,
        extract: (T) -> Double,
    ): Double? {
        if (HealthPermission.getReadPermission(type) !in granted) return null
        return runCatching {
            hc.readRecords(ReadRecordsRequest(type, range)).records.lastOrNull()?.let(extract)
        }.onFailure { Log.w(TAG, "HC ${type.simpleName} 読み込み失敗", it) }.getOrNull()
    }

    /** 身長のように「過去の任意時点で最後に記録された値」を取りたいとき用 */
    private suspend fun <T : Record> readLatestEver(
        hc: HealthConnectClient,
        granted: Set<String>,
        before: ZonedDateTime,
        type: kotlin.reflect.KClass<T>,
        extract: (T) -> Double,
    ): Double? {
        if (HealthPermission.getReadPermission(type) !in granted) return null
        return runCatching {
            hc.readRecords(ReadRecordsRequest(type, TimeRangeFilter.before(before.toInstant())))
                .records.lastOrNull()?.let(extract)
        }.onFailure { Log.w(TAG, "HC ${type.simpleName} 読み込み失敗", it) }.getOrNull()
    }

    companion object {
        const val METRIC_STEPS = "steps"
        const val METRIC_DISTANCE = "distance"
        const val METRIC_FLOORS = "floors"
        const val METRIC_ACTIVE_KCAL = "activeKcal"
        const val METRIC_TOTAL_KCAL = "totalKcal"
        const val METRIC_BASAL_KCAL = "basalKcal"
        const val METRIC_INTAKE_KCAL = "intakeKcal"

        /** 起動時に MainActivity が登録する Activity Result contract のキー */
        val PERMISSIONS: Set<String> = setOf(
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(RestingHeartRateRecord::class),
            HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
            HealthPermission.getReadPermission(SleepSessionRecord::class),
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getReadPermission(DistanceRecord::class),
            HealthPermission.getReadPermission(FloorsClimbedRecord::class),
            HealthPermission.getReadPermission(ElevationGainedRecord::class),
            HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(BasalMetabolicRateRecord::class),
            HealthPermission.getReadPermission(ExerciseSessionRecord::class),
            HealthPermission.getReadPermission(WeightRecord::class),
            HealthPermission.getReadPermission(BodyFatRecord::class),
            HealthPermission.getReadPermission(LeanBodyMassRecord::class),
            HealthPermission.getReadPermission(HeightRecord::class),
            HealthPermission.getReadPermission(NutritionRecord::class),
            HealthPermission.getReadPermission(OxygenSaturationRecord::class),
            HealthPermission.getReadPermission(RespiratoryRateRecord::class),
            HealthPermission.getReadPermission(SkinTemperatureRecord::class),
            // 本アプリを HC のカロリー master にする: 自前 TDEE を TotalCalories として書き戻す
            HealthPermission.getWritePermission(TotalCaloriesBurnedRecord::class),
        )

        /** Android 14+ で過去 30 日より前を読むのに必要。許可されれば 5 年同期が回る */
        const val PERMISSION_HISTORY = "android.permission.health.READ_HEALTH_DATA_HISTORY"

        fun permissionRequestContract() =
            PermissionController.createRequestPermissionResultContract()
    }
}

private data class HrStats(val avg: Int, val min: Int, val max: Int)

data class SnapshotResult(
    val record: DailyHealthRecord,
    val breakdown: List<MetricBreakdownRow>,
)

data class MetricBreakdownRow(
    val metricKey: String,
    val sourcePackage: String,
    val value: Double,
)

data class HrSample(
    val timestampMs: Long,
    val bpm: Int,
    val sourcePackage: String,
)

enum class VitalKindData { SPO2, RESPIRATORY_RATE, SKIN_TEMPERATURE_DELTA }

data class VitalSample(
    val timestampMs: Long,
    val kind: VitalKindData,
    val value: Double,
    val sourcePackage: String,
)

data class ExerciseSessionData(
    val id: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val exerciseType: Int,
    val title: String?,
    val sourcePackage: String,
)
