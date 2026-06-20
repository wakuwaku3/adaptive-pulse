package io.github.wakuwaku3.adaptivepulse.mobile.store

import android.content.Context
import io.github.wakuwaku3.adaptivepulse.core.sync.SessionConfigSnapshot
import io.github.wakuwaku3.adaptivepulse.core.sync.SessionRecord
import io.github.wakuwaku3.adaptivepulse.mobile.health.HealthDataSource
import io.github.wakuwaku3.adaptivepulse.mobile.sync.PendingSessionStore
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.sin
import kotlin.random.Random

/**
 * デザイン確認用のダミーデータ投入。実データの分布 (体重 91kg 帯・TDEE 2000-3200・
 * 6/15 風チートデイ) を模した値を 7 日ぶん Room に入れる。
 *
 * **テスト・デモ専用**: HC 連携がない状態 (エミュレータ等) でもダッシュボードを見られるようにする。
 * 本番運用時は HealthSyncWorker が HC から書き込むので呼ばない。
 */
object DemoSeed {

    suspend fun seed(context: Context, today: LocalDate = LocalDate.now()) {
        val dao = DashboardDatabase.get(context).dashboardDao()
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()

        // 7 日ぶん。直近 (today) は intake が deficit を作る、6 日前にチートデイ風スパイクを置く
        val snapshots = (0 until 7).map { offset ->
            val date = today.minusDays(offset.toLong()).format(DateTimeFormatter.ISO_LOCAL_DATE)
            val isCheatDay = offset == 5
            val isHighActivity = offset == 0 || offset == 4
            val weight = 91.4 + sin(offset.toDouble()) * 0.5 + if (isCheatDay) 2.8 else 0.0
            val steps = when {
                isHighActivity -> 16000L + Random.nextLong(2000)
                isCheatDay -> 14000L
                else -> 3000L + Random.nextLong(4000)
            }
            val tdee = 1820.0 + steps * 0.040 + Random.nextDouble(100.0)
            val intake = when {
                isCheatDay -> 4451.0
                offset % 3 == 0 -> 1500.0 + Random.nextDouble(200.0)
                else -> 2100.0 + Random.nextDouble(300.0)
            }
            DailySnapshotEntity(
                date = date,
                syncedAtMs = now,
                restingHeartRateBpm = 63 + Random.nextInt(0, 8),
                hrvRmssdMs = 35.0 + Random.nextDouble(15.0),
                avgHeartRateBpm = 75 + Random.nextInt(10),
                minHeartRateBpm = 55 + Random.nextInt(5),
                maxHeartRateBpm = 155 + Random.nextInt(15),
                sleepDurationMin = (6 * 60 + Random.nextLong(90)),
                sleepDeepMin = 50 + Random.nextLong(20),
                sleepRemMin = 90 + Random.nextLong(30),
                sleepLightMin = 220 + Random.nextLong(60),
                sleepAwakeMin = 20 + Random.nextLong(15),
                steps = steps,
                distanceMeters = steps * 0.78,
                floorsClimbed = 4.0 + Random.nextDouble(8.0),
                elevationGainedMeters = 20.0 + Random.nextDouble(50.0),
                activeCaloriesKcal = (tdee - 1820.0).coerceAtLeast(0.0),
                totalCaloriesKcal = tdee,
                basalCaloriesKcal = 1820.0,
                weightKg = weight,
                bodyFatPct = 30.6 + sin(offset.toDouble()) * 0.4,
                leanBodyMassKg = weight * 0.69,
                heightCm = 175.0,
                intakeKcal = intake,
                proteinG = 130.0 + Random.nextDouble(40.0),
                fatG = 60.0 + Random.nextDouble(30.0),
                carbsG = if (isCheatDay) 400.0 else 200.0 + Random.nextDouble(150.0),
                fiberG = 12.0 + Random.nextDouble(8.0),
                sugarG = 30.0 + Random.nextDouble(40.0),
                sodiumMg = 2200.0 + Random.nextDouble(800.0),
                spo2AvgPct = 96.5 + Random.nextDouble(1.5),
                spo2MinPct = 92.0 + Random.nextDouble(3.0),
                respiratoryRateAvg = 14.0 + Random.nextDouble(3.0),
                skinTemperatureDeltaC = -0.2 + Random.nextDouble(0.6),
            )
        }
        dao.upsertSnapshots(snapshots)

        // 今日のソース別 breakdown — Google Health UI で見える「watch / phone / Fit」乖離を再現
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val watchPkg = "com.google.android.wearable.healthservices"
        val phonePkg = "com.google.android.apps.healthdata"
        val fitPkg = "com.google.android.apps.fitness"
        val asken = "jp.co.asken.asken"
        dao.replaceMetricBySource(
            todayStr, HealthDataSource.METRIC_TOTAL_KCAL,
            listOf(
                MetricBySourceEntity(todayStr, HealthDataSource.METRIC_TOTAL_KCAL, watchPkg, 4876.0),
                MetricBySourceEntity(todayStr, HealthDataSource.METRIC_TOTAL_KCAL, phonePkg, 3168.0),
                MetricBySourceEntity(todayStr, HealthDataSource.METRIC_TOTAL_KCAL, fitPkg, 2024.0),
            ),
        )
        dao.replaceMetricBySource(
            todayStr, HealthDataSource.METRIC_ACTIVE_KCAL,
            listOf(
                MetricBySourceEntity(todayStr, HealthDataSource.METRIC_ACTIVE_KCAL, watchPkg, 3056.0),
                MetricBySourceEntity(todayStr, HealthDataSource.METRIC_ACTIVE_KCAL, phonePkg, 1350.0),
                MetricBySourceEntity(todayStr, HealthDataSource.METRIC_ACTIVE_KCAL, fitPkg, 204.0),
            ),
        )
        dao.replaceMetricBySource(
            todayStr, HealthDataSource.METRIC_BASAL_KCAL,
            listOf(
                MetricBySourceEntity(todayStr, HealthDataSource.METRIC_BASAL_KCAL, watchPkg, 1820.0),
                MetricBySourceEntity(todayStr, HealthDataSource.METRIC_BASAL_KCAL, phonePkg, 1818.0),
            ),
        )
        dao.replaceMetricBySource(
            todayStr, HealthDataSource.METRIC_STEPS,
            listOf(
                MetricBySourceEntity(todayStr, HealthDataSource.METRIC_STEPS, watchPkg, 17430.0),
                MetricBySourceEntity(todayStr, HealthDataSource.METRIC_STEPS, phonePkg, 15200.0),
                MetricBySourceEntity(todayStr, HealthDataSource.METRIC_STEPS, fitPkg, 12030.0),
            ),
        )
        dao.replaceMetricBySource(
            todayStr, HealthDataSource.METRIC_INTAKE_KCAL,
            listOf(
                MetricBySourceEntity(todayStr, HealthDataSource.METRIC_INTAKE_KCAL, asken, 2204.0),
            ),
        )

        // 今日 + 昨日の HR 時系列 (15 分粒度の合成データ。20-24h × 4 = 80〜100 サンプル/日)
        val hrSamples = mutableListOf<HeartRateSampleEntity>()
        listOf(today.minusDays(1), today).forEach { d ->
            val startMs = d.atStartOfDay(zone).toInstant().toEpochMilli()
            for (q in 0 until 96) { // 15min × 96 = 24h
                val tMs = startMs + q * 15L * 60_000L
                // 朝の HIIT 帯 (6-7時) で 140-170、昼/夜は 65-90、睡眠帯 (1-5時) は 55-65
                val hour = (q * 15) / 60
                val bpm = when (hour) {
                    in 1..5 -> 55 + Random.nextInt(10)
                    in 6..6 -> 130 + Random.nextInt(40)
                    in 7..23 -> 70 + Random.nextInt(25)
                    else -> 70 + Random.nextInt(15)
                }
                hrSamples += HeartRateSampleEntity(tMs, watchPkg, bpm)
            }
        }
        dao.upsertHeartRateSamples(hrSamples)

        // 他アプリの運動セッション例 (Fit の walking session)
        val walkStart = today.atStartOfDay(zone).plusHours(12).toInstant().toEpochMilli()
        dao.upsertExerciseSessions(
            listOf(
                ExerciseSessionEntity(
                    id = "demo-walk-1",
                    startTimeMs = walkStart,
                    endTimeMs = walkStart + 35 * 60_000L,
                    exerciseType = 79, // WALKING
                    title = "Lunch walk",
                    sourcePackage = fitPkg,
                ),
            ),
        )

        // SpO2 サンプル
        val vitals = mutableListOf<VitalSampleEntity>()
        val startMs = today.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        for (i in 0 until 48) { // 30 分粒度 × 48 = 24h
            val tMs = startMs + i * 30L * 60_000L
            vitals += VitalSampleEntity(tMs, VitalKind.SPO2, 95.0 + Random.nextDouble(3.5), watchPkg)
            if (i % 6 == 0) {
                vitals += VitalSampleEntity(tMs, VitalKind.RESPIRATORY_RATE, 13.5 + Random.nextDouble(3.5), watchPkg)
            }
        }
        dao.upsertVitalSamples(vitals)

        // セッション履歴 (HIIT) もダミー投入。PendingSessionStore に save しておけば
        // HISTORY 画面の SessionCard と各セッショングラフがそれを表示する
        seedSessions(context, today, zone)
    }

    private fun seedSessions(context: Context, today: LocalDate, zone: ZoneId) {
        val store = PendingSessionStore(context)
        val config = SessionConfigSnapshot(
            upperBpm = 155,
            lowerBpm = 140,
            targetCycles = 7,
            fatigueRatio = 0.5,
            minBaselineSec = 45,
            highTimeoutSec = 180,
            recoveryTimeoutSec = 180,
        )
        // 過去 14 日のうち平日朝のジムセッションだけ生成 (週 5 想定 = 10 件)
        var produced = 0
        var d = today
        while (produced < 10) {
            d = d.minusDays(1)
            val dayOfWeek = d.dayOfWeek.value
            if (dayOfWeek > 5) continue // 週末スキップ
            val startMs = d.atStartOfDay(zone).plusHours(6).plusMinutes(40 + Random.nextLong(0, 25)).toInstant().toEpochMilli()
            val fatigueBrake = Random.nextDouble() < 0.10
            val cycles = if (fatigueBrake) Random.nextInt(0, 5) else 7
            val highDurations = if (fatigueBrake) emptyList() else (0..6).map {
                15.0 + Random.nextDouble(50.0)
            }
            val recoveryDurations = if (fatigueBrake) emptyList() else (0..6).map {
                25.0 + Random.nextDouble(35.0)
            }
            val duration = (highDurations.sum() + recoveryDurations.sum() + Random.nextDouble(60.0, 180.0)).toLong()
            val avgBpm = 140 + Random.nextInt(10)
            val maxBpm = 158 + Random.nextInt(15)
            val zoneRatio = if (fatigueBrake) 0.0 else 0.50 + Random.nextDouble(0.18)
            val record = SessionRecord(
                id = "demo-$startMs-${Random.nextInt(10000)}",
                startedAtMs = startMs,
                durationSec = duration,
                cycles = cycles,
                plannedCycles = 7,
                fatigueBrake = fatigueBrake,
                calories = 160.0 + Random.nextDouble(120.0),
                zoneRatio = zoneRatio,
                highDurationsSec = highDurations,
                recoveryDurationsSec = recoveryDurations,
                avgBpm = avgBpm,
                maxBpm = maxBpm,
                config = config,
            )
            store.save(record)
            produced++
        }
    }
}
