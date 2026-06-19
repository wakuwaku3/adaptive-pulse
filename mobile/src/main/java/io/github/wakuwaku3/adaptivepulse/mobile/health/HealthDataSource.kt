package io.github.wakuwaku3.adaptivepulse.mobile.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import io.github.wakuwaku3.adaptivepulse.core.sync.DailyHealthRecord
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private const val TAG = "AdaptivePulse"

/**
 * Health Connect から日次の健康指標を集めて [DailyHealthRecord] に詰める。
 *
 * 取得方針:
 *  - 数値メトリクス (歩数 / カロリー / 栄養) は aggregate API で日次集計を 1 リクエストで取る。
 *  - サンプル列 (心拍 / 安静時心拍 / HRV / 体重・体脂肪・LBM・身長) は最新値 1 点が
 *    意味を持つので read API で当日分を読み、平均 (or 最新) を取る。
 *  - 睡眠は [SleepSessionRecord] を当日の暦日に重ねた区間として取り、stage 別に合算する。
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

    /** 過去 [days] 日 (今日を除く昨日まで) の集計を新しい順に返す */
    suspend fun readDailySummaries(days: Int, zone: ZoneId = ZoneId.systemDefault()): List<DailyHealthRecord> {
        val hc = client ?: return emptyList()
        val granted = grantedPermissions()
        // 今日はまだ進行中なので「昨日 〜 days-1 日前」までを取る
        val yesterday = LocalDate.now(zone).minusDays(1)
        return (0 until days).map { offset ->
            val date = yesterday.minusDays(offset.toLong())
            readDay(hc, granted, date, zone)
        }
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

        // aggregate: 歩数 / カロリー / 栄養を 1 リクエストにまとめる
        val aggregateMetrics = buildSet<AggregateMetric<*>> {
            if (HealthPermission.getReadPermission(StepsRecord::class) in granted) {
                add(StepsRecord.COUNT_TOTAL)
            }
            if (HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class) in granted) {
                add(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)
            }
            if (HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class) in granted) {
                add(TotalCaloriesBurnedRecord.ENERGY_TOTAL)
            }
            if (HealthPermission.getReadPermission(NutritionRecord::class) in granted) {
                add(NutritionRecord.ENERGY_TOTAL)
                add(NutritionRecord.PROTEIN_TOTAL)
                add(NutritionRecord.TOTAL_FAT_TOTAL)
                add(NutritionRecord.TOTAL_CARBOHYDRATE_TOTAL)
            }
        }
        val agg = if (aggregateMetrics.isNotEmpty()) {
            runCatching {
                hc.aggregate(AggregateRequest(metrics = aggregateMetrics, timeRangeFilter = range))
            }.onFailure { Log.w(TAG, "HC aggregate 失敗: $date", it) }.getOrNull()
        } else null

        return DailyHealthRecord(
            date = date.toString(),
            restingHeartRateBpm = readRestingHeartRate(hc, granted, range),
            hrvRmssdMs = readHrv(hc, granted, range),
            avgHeartRateBpm = readAvgHeartRate(hc, granted, range),
            sleepDurationMin = readSleepDuration(hc, granted, start, end),
            sleepDeepMin = readSleepStage(hc, granted, start, end, SleepSessionRecord.STAGE_TYPE_DEEP),
            sleepRemMin = readSleepStage(hc, granted, start, end, SleepSessionRecord.STAGE_TYPE_REM),
            sleepLightMin = readSleepStage(hc, granted, start, end, SleepSessionRecord.STAGE_TYPE_LIGHT),
            sleepAwakeMin = readSleepStage(hc, granted, start, end, SleepSessionRecord.STAGE_TYPE_AWAKE),
            steps = agg?.get(StepsRecord.COUNT_TOTAL),
            activeCaloriesKcal = agg?.get(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)?.inKilocalories,
            totalCaloriesKcal = agg?.get(TotalCaloriesBurnedRecord.ENERGY_TOTAL)?.inKilocalories,
            weightKg = readLatest(hc, granted, range, WeightRecord::class) { it.weight.inKilograms },
            bodyFatPct = readLatest(hc, granted, range, BodyFatRecord::class) { it.percentage.value },
            leanBodyMassKg = readLatest(hc, granted, range, LeanBodyMassRecord::class) { it.mass.inKilograms },
            heightCm = readLatestEver(hc, granted, end, HeightRecord::class) { it.height.inMeters * 100.0 },
            intakeKcal = agg?.get(NutritionRecord.ENERGY_TOTAL)?.inKilocalories,
            proteinG = agg?.get(NutritionRecord.PROTEIN_TOTAL)?.inGrams,
            fatG = agg?.get(NutritionRecord.TOTAL_FAT_TOTAL)?.inGrams,
            carbsG = agg?.get(NutritionRecord.TOTAL_CARBOHYDRATE_TOTAL)?.inGrams,
        )
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

    private suspend fun readAvgHeartRate(
        hc: HealthConnectClient,
        granted: Set<String>,
        range: TimeRangeFilter,
    ): Int? {
        if (HealthPermission.getReadPermission(HeartRateRecord::class) !in granted) return null
        return runCatching {
            // HeartRateRecord は連続サンプルを持つので、各 record の samples を全て集めて平均
            val bpms = hc.readRecords(ReadRecordsRequest(HeartRateRecord::class, range))
                .records.flatMap { it.samples }.map { it.beatsPerMinute }
            if (bpms.isEmpty()) null else bpms.average().roundToInt()
        }.onFailure { Log.w(TAG, "HC HR 読み込み失敗", it) }.getOrNull()
    }

    private suspend fun readSleepDuration(
        hc: HealthConnectClient,
        granted: Set<String>,
        start: ZonedDateTime,
        end: ZonedDateTime,
    ): Long? {
        if (HealthPermission.getReadPermission(SleepSessionRecord::class) !in granted) return null
        return runCatching {
            val total = hc.readRecords(
                ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.between(start.toInstant(), end.toInstant())),
            ).records.sumOf { (it.endTime.toEpochMilli() - it.startTime.toEpochMilli()) / 60_000.0 }
            if (total <= 0.0) null else total.roundToLong()
        }.onFailure { Log.w(TAG, "HC sleep 読み込み失敗", it) }.getOrNull()
    }

    private suspend fun readSleepStage(
        hc: HealthConnectClient,
        granted: Set<String>,
        start: ZonedDateTime,
        end: ZonedDateTime,
        stage: Int,
    ): Long? {
        if (HealthPermission.getReadPermission(SleepSessionRecord::class) !in granted) return null
        return runCatching {
            val total = hc.readRecords(
                ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.between(start.toInstant(), end.toInstant())),
            ).records
                .flatMap { it.stages }
                .filter { it.stage == stage }
                .sumOf { (it.endTime.toEpochMilli() - it.startTime.toEpochMilli()) / 60_000.0 }
            if (total <= 0.0) null else total.roundToLong()
        }.onFailure { Log.w(TAG, "HC sleep stage 読み込み失敗", it) }.getOrNull()
    }

    private suspend fun <T : androidx.health.connect.client.records.Record> readLatest(
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
    private suspend fun <T : androidx.health.connect.client.records.Record> readLatestEver(
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
        /** 起動時に MainActivity が登録する Activity Result contract のキー */
        val PERMISSIONS: Set<String> = setOf(
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(RestingHeartRateRecord::class),
            HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
            HealthPermission.getReadPermission(SleepSessionRecord::class),
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(WeightRecord::class),
            HealthPermission.getReadPermission(BodyFatRecord::class),
            HealthPermission.getReadPermission(LeanBodyMassRecord::class),
            HealthPermission.getReadPermission(HeightRecord::class),
            HealthPermission.getReadPermission(NutritionRecord::class),
        )

        fun permissionRequestContract() =
            PermissionController.createRequestPermissionResultContract()
    }
}
