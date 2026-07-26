package io.github.wakuwaku3.adaptivepulse.core.analysis

import io.github.wakuwaku3.adaptivepulse.core.strength.TrainingSet
import io.github.wakuwaku3.adaptivepulse.core.strength.WorkoutEntry
import io.github.wakuwaku3.adaptivepulse.core.strength.WorkoutRecord
import io.github.wakuwaku3.adaptivepulse.core.sync.DailyHealthRecord
import io.github.wakuwaku3.adaptivepulse.core.sync.SessionConfigSnapshot
import io.github.wakuwaku3.adaptivepulse.core.sync.SessionRecord
import io.github.wakuwaku3.adaptivepulse.core.sync.SettingsDocument
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class HealthAnalysisTest {

    private val jst = ZoneId.of("Asia/Tokyo")

    private val settings = SettingsDocument(
        upperBpm = 150, lowerBpm = 120, targetCycles = 5, fatigueRatio = 1.3,
        minBaselineSec = 30, highTimeoutSec = 120, recoveryTimeoutSec = 120,
        updatedAtMs = 0, updatedBy = "test", targetWeightKg = 70.0,
    )

    private fun session(cycles: Int, planned: Int, brake: Boolean, high: List<Double>) = SessionRecord(
        id = "s-$cycles-$planned-$brake-${high.sum()}",
        startedAtMs = 0, durationSec = 0, cycles = cycles, plannedCycles = planned,
        fatigueBrake = brake, highDurationsSec = high,
        config = SessionConfigSnapshot(
            upperBpm = 150, lowerBpm = 120, targetCycles = planned, fatigueRatio = 1.3,
            minBaselineSec = 30, highTimeoutSec = 120, recoveryTimeoutSec = 120,
        ),
    )

    private fun set(weightKg: Double?, reps: Int) = TrainingSet(weightKg = weightKg, reps = reps, recordedAtMs = 0)

    @Test
    fun `体組成は最初と最後の実測差、体脂肪率の 0 は欠損として除外`() {
        val metrics = listOf(
            DailyHealthRecord(date = "2026-07-20", weightKg = 70.0, bodyFatPct = 20.0),
            DailyHealthRecord(date = "2026-07-21", weightKg = 68.0, bodyFatPct = 0.0),
        )
        val bc = analyzeHealthData(metrics, emptyList(), emptyList(), settings, jst).bodyComposition
        assertEquals(70.0, bc.weightFirstKg)
        assertEquals(68.0, bc.weightLastKg)
        assertEquals(-2.0, bc.weightDeltaKg)
        // 体脂肪は 20.0 のみが実測 (0 は除外) → 変化 0
        assertEquals(20.0, bc.bodyFatFirstPct)
        assertEquals(20.0, bc.bodyFatLastPct)
        assertEquals(0.0, bc.bodyFatDeltaPct)
    }

    @Test
    fun `食事の収支と栄養素の下限割れ日`() {
        val metrics = listOf(
            DailyHealthRecord(
                date = "2026-07-20", weightKg = 70.0,
                intakeKcal = 2000.0, tdeeKcal = 2500.0, proteinG = 100.0, fatG = 40.0,
            ),
            DailyHealthRecord(
                date = "2026-07-21", weightKg = 68.0,
                intakeKcal = 1800.0, tdeeKcal = 2400.0, proteinG = 120.0, fatG = 30.0,
            ),
        )
        val n = analyzeHealthData(metrics, emptyList(), emptyList(), settings, jst).nutrition
        assertEquals(2, n.daysWithIntakeAndTdee)
        assertEquals(3800.0, n.intakeTotalKcal)
        assertEquals(4900.0, n.tdeeTotalKcal)
        assertEquals(-1100.0, n.balanceKcal)
        assertEquals(-550.0, n.dailyAvgBalanceKcal)
        // タンパク質下限 = 目標体重 70 × 1.6 = 112 → 20 日が下回る
        assertEquals(112.0, n.proteinFloorGPerDay)
        assertEquals(listOf("2026-07-20"), n.proteinBelowFloorDates)
        // 脂質下限 = 直近体重 68 × 0.5 = 34 → 21 日が下回る
        assertEquals(34.0, n.fatFloorGPerDay)
        assertEquals(listOf("2026-07-21"), n.fatBelowFloorDates)
    }

    @Test
    fun `回復は安静時心拍の中央値と心拍の揺らぎの平均`() {
        val metrics = listOf(
            DailyHealthRecord(date = "2026-07-20", restingHeartRateBpm = 50, hrvRmssdMs = 60.0),
            DailyHealthRecord(date = "2026-07-21", restingHeartRateBpm = 54, hrvRmssdMs = 40.0),
        )
        val r = analyzeHealthData(metrics, emptyList(), emptyList(), settings, jst).recovery
        assertEquals(54.0, r.restingHrMedianBpm)
        assertEquals(50.0, r.hrvAvgMs)
    }

    @Test
    fun `HIIT は本数・完走数・ブレーキ数・ゾーン滞在分`() {
        val sessions = listOf(
            session(cycles = 5, planned = 5, brake = false, high = listOf(30.0, 30.0)),
            session(cycles = 3, planned = 5, brake = true, high = listOf(20.0)),
        )
        val h = analyzeHealthData(emptyList(), sessions, emptyList(), settings, jst).hiit
        assertEquals(2, h.sessionCount)
        assertEquals(1, h.completedCount)
        assertEquals(1, h.brakeCount)
        assertEquals(1, h.inZoneMinutes) // (60 + 20) / 60 = 1.33 → 1
    }

    @Test
    fun `筋トレは種目別にトップセットと総量を日別で出す (skipped と空セットは除外)`() {
        val day1Ms = 1_700_000_000_000L
        val day2Ms = day1Ms + 86_400_000L // 翌 JST 日
        val workouts = listOf(
            WorkoutRecord(
                id = "w1", gymId = "g", gymName = "Gym", startedAtMs = day1Ms, lastInputAtMs = day1Ms,
                entries = listOf(
                    WorkoutEntry("t-bench", "Bench", sets = listOf(set(60.0, 5), set(58.0, 8))),
                    WorkoutEntry("t-skip", "Skipped", skipped = true, sets = listOf(set(40.0, 10))),
                    WorkoutEntry("t-empty", "Empty", sets = emptyList()),
                ),
            ),
            WorkoutRecord(
                id = "w2", gymId = "g", gymName = "Gym", startedAtMs = day2Ms, lastInputAtMs = day2Ms,
                entries = listOf(WorkoutEntry("t-bench", "Bench", sets = listOf(set(62.0, 5)))),
            ),
        )
        val prog = analyzeHealthData(emptyList(), emptyList(), workouts, settings, jst).strengthProgression
        assertEquals(1, prog.size)
        val bench = prog.single()
        assertEquals("Bench", bench.trainingName)
        assertEquals(2, bench.points.size)
        // 1 日目: トップセットは重量最大の (60,5)、総量 = 60×5 + 58×8 = 764
        assertEquals("60.0kg×5", bench.points[0].topSet)
        assertEquals(764.0, bench.points[0].volumeKgReps)
        assertEquals("62.0kg×5", bench.points[1].topSet)
        assertEquals(310.0, bench.points[1].volumeKgReps)
    }

    @Test
    fun `負荷なし種目のトップセットは回数だけで表す`() {
        val workouts = listOf(
            WorkoutRecord(
                id = "w1", gymId = "g", gymName = "Gym", startedAtMs = 1_700_000_000_000L, lastInputAtMs = 0,
                entries = listOf(WorkoutEntry("t-plank", "Plank", sets = listOf(set(null, 12), set(null, 15)))),
            ),
        )
        val prog = analyzeHealthData(emptyList(), emptyList(), workouts, settings, jst).strengthProgression
        assertEquals("×15", prog.single().points.single().topSet)
    }
}
