package io.github.wakuwaku3.adaptivepulse.core.analysis

import io.github.wakuwaku3.adaptivepulse.core.strength.TrainingSet
import io.github.wakuwaku3.adaptivepulse.core.strength.WorkoutRecord
import io.github.wakuwaku3.adaptivepulse.core.sync.DailyHealthRecord
import io.github.wakuwaku3.adaptivepulse.core.sync.SessionRecord
import io.github.wakuwaku3.adaptivepulse.core.sync.SettingsDocument
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable

/**
 * analyze skill (`scripts/analyze.sh` の「派生集計」) と同じ集計を JVM 単体で再現する。
 * エクスポート JSON に生データと並べて同梱し、AI が算術ミスなく現状を読めるようにする。
 * 集計の定義は analyze.sh と揃える (両者がずれると /analyze とスマホ添付で数値が食い違う)。
 */
@Serializable
data class HealthAnalysis(
    val bodyComposition: BodyCompositionChange,
    val nutrition: NutritionSummary,
    val recovery: RecoveryBaselines,
    val hiit: HiitSummary,
    val strengthProgression: List<ExerciseProgression>,
)

/** 期間の最初の実測 → 最後の実測。体脂肪率は 0 を欠損として除外する (過去日の 0 埋め実装対策) */
@Serializable
data class BodyCompositionChange(
    val weightFirstKg: Double? = null,
    val weightLastKg: Double? = null,
    val weightDeltaKg: Double? = null,
    val bodyFatFirstPct: Double? = null,
    val bodyFatLastPct: Double? = null,
    val bodyFatDeltaPct: Double? = null,
)

@Serializable
data class NutritionSummary(
    /** 摂取カロリーと TDEE が両方そろった日数 (収支の母数) */
    val daysWithIntakeAndTdee: Int,
    val intakeTotalKcal: Double? = null,
    val tdeeTotalKcal: Double? = null,
    /** 収支 = 摂取 - TDEE。マイナスは減量目的の意図的なもの (欠損扱い/不足と読まない) */
    val balanceKcal: Double? = null,
    val dailyAvgBalanceKcal: Double? = null,
    /** タンパク質下限 = アンカー体重 × 1.6 g/日。アンカー = 目標体重、無ければ身長の BMI 22 標準体重 */
    val proteinFloorGPerDay: Double? = null,
    val proteinBelowFloorDates: List<String> = emptyList(),
    /** 脂質下限 = 直近実測体重 × 0.5 g/日 */
    val fatFloorGPerDay: Double? = null,
    val fatBelowFloorDates: List<String> = emptyList(),
)

/** 回復の基準値。安静時心拍は期間中央値、心拍の揺らぎ (HRV/RMSSD) は期間平均 */
@Serializable
data class RecoveryBaselines(
    val restingHrMedianBpm: Double? = null,
    val hrvAvgMs: Double? = null,
)

@Serializable
data class HiitSummary(
    val sessionCount: Int,
    /** 予定本数を完走したセッション数 */
    val completedCount: Int,
    /** 疲労ブレーキが発動したセッション数 (探索中の設定調整でも出るので疲労と短絡しない) */
    val brakeCount: Int,
    /** 高強度ゾーン滞在の合計 (分)。目安は週 30-60 分 */
    val inZoneMinutes: Int,
)

/** 種目 1 つの日別推移 (漸進的過負荷の判定材料) */
@Serializable
data class ExerciseProgression(
    val trainingName: String,
    val points: List<ExerciseDayPoint>,
)

@Serializable
data class ExerciseDayPoint(
    val date: String,
    /** その日のトップセット。負荷ありは "60kg×8"、負荷なし (自重・ストレッチ) は "×12" */
    val topSet: String,
    /** その日の全セットの (重量 × 回数) 合計 */
    val volumeKgReps: Double,
)

/**
 * 生データ (日次指標 / HIIT / 筋トレ / 設定) から analyze 相当の集計を作る。
 * [zone] は筋トレレコードの `startedAtMs` を暦日に落とすのに使う (analyze.sh は JST 固定)。
 */
fun analyzeHealthData(
    dailyMetrics: List<DailyHealthRecord>,
    sessions: List<SessionRecord>,
    workouts: List<WorkoutRecord>,
    settings: SettingsDocument?,
    zone: ZoneId = ZoneId.systemDefault(),
): HealthAnalysis {
    val metrics = dailyMetrics.sortedBy { it.date }
    val weights = metrics.mapNotNull { it.weightKg }
    // 体脂肪率の 0 は過去日の欠損 0 埋めなので実測から除外する (analyze.sh と同じ)
    val bodyFats = metrics.mapNotNull { it.bodyFatPct }.filter { it != 0.0 }

    val bodyComposition = BodyCompositionChange(
        weightFirstKg = weights.firstOrNull()?.let(::round1),
        weightLastKg = weights.lastOrNull()?.let(::round1),
        weightDeltaKg = weights.takeIf { it.isNotEmpty() }?.let { round1(it.last() - it.first()) },
        bodyFatFirstPct = bodyFats.firstOrNull()?.let(::round1),
        bodyFatLastPct = bodyFats.lastOrNull()?.let(::round1),
        bodyFatDeltaPct = bodyFats.takeIf { it.isNotEmpty() }?.let { round1(it.last() - it.first()) },
    )

    // タンパク質下限のアンカー体重: 目標体重、無ければ身長から BMI 22 標準体重
    val anchorWeight = settings?.targetWeightKg
        ?: settings?.heightCm?.let { cm -> val m = cm / 100.0; m * m * 22 }
    val proteinFloor = anchorWeight?.let { round1(it * 1.6) }
    val fatFloor = weights.lastOrNull()?.let { round1(it * 0.5) }
    val balanceDays = metrics.filter { it.intakeKcal != null && it.tdeeKcal != null }
    val intakeSum = balanceDays.sumOf { it.intakeKcal!! }
    val tdeeSum = balanceDays.sumOf { it.tdeeKcal!! }

    val nutrition = NutritionSummary(
        daysWithIntakeAndTdee = balanceDays.size,
        intakeTotalKcal = balanceDays.takeIf { it.isNotEmpty() }?.let { round0(intakeSum) },
        tdeeTotalKcal = balanceDays.takeIf { it.isNotEmpty() }?.let { round0(tdeeSum) },
        balanceKcal = balanceDays.takeIf { it.isNotEmpty() }?.let { round0(intakeSum - tdeeSum) },
        dailyAvgBalanceKcal = balanceDays.takeIf { it.isNotEmpty() }
            ?.let { round0((intakeSum - tdeeSum) / it.size) },
        proteinFloorGPerDay = proteinFloor,
        proteinBelowFloorDates = proteinFloor
            ?.let { floor -> metrics.filter { it.proteinG != null && it.proteinG < floor }.map { it.date } }
            .orEmpty(),
        fatFloorGPerDay = fatFloor,
        fatBelowFloorDates = fatFloor
            ?.let { floor -> metrics.filter { it.fatG != null && it.fatG < floor }.map { it.date } }
            .orEmpty(),
    )

    val recovery = RecoveryBaselines(
        restingHrMedianBpm = median(metrics.mapNotNull { it.restingHeartRateBpm?.toDouble() })?.let(::round0),
        hrvAvgMs = metrics.mapNotNull { it.hrvRmssdMs }.takeIf { it.isNotEmpty() }?.let { round1(it.average()) },
    )

    val hiit = HiitSummary(
        sessionCount = sessions.size,
        completedCount = sessions.count { it.cycles == it.plannedCycles && it.cycles > 0 },
        brakeCount = sessions.count { it.fatigueBrake },
        inZoneMinutes = (sessions.sumOf { s -> s.highDurationsSec.sum() } / 60.0).roundToInt(),
    )

    return HealthAnalysis(
        bodyComposition = bodyComposition,
        nutrition = nutrition,
        recovery = recovery,
        hiit = hiit,
        strengthProgression = strengthProgression(workouts, zone),
    )
}

private fun strengthProgression(workouts: List<WorkoutRecord>, zone: ZoneId): List<ExerciseProgression> {
    data class Perf(val date: String, val name: String, val sets: List<TrainingSet>)
    val perfs = workouts.flatMap { workout ->
        val date = Instant.ofEpochMilli(workout.startedAtMs).atZone(zone).toLocalDate().toString()
        workout.entries
            .filter { !it.skipped && it.sets.isNotEmpty() }
            .map { Perf(date, it.trainingName, it.sets) }
    }
    return perfs.groupBy { it.name }.toSortedMap().map { (name, byName) ->
        val points = byName.groupBy { it.date }.toSortedMap().map { (date, sameDay) ->
            val sets = sameDay.flatMap { it.sets }
            // トップセット = (重量, 回数) の辞書順最大。負荷なしは重量 0 とみなす (analyze.sh と同じ)
            val top = sets.maxWith(compareBy({ it.weightKg ?: 0.0 }, { it.reps }))
            ExerciseDayPoint(
                date = date,
                topSet = top.weightKg?.takeIf { it > 0.0 }
                    ?.let { "${round1(it)}kg×${top.reps}" }
                    ?: "×${top.reps}",
                volumeKgReps = round0(sets.sumOf { (it.weightKg ?: 0.0) * it.reps }),
            )
        }
        ExerciseProgression(trainingName = name, points = points)
    }
}

/** analyze.sh の median (sort して length/2 の floor index = 偶数長では上側中央値) に合わせる */
private fun median(xs: List<Double>): Double? {
    if (xs.isEmpty()) return null
    val sorted = xs.sorted()
    return sorted[sorted.size / 2]
}

private fun round1(v: Double): Double = (v * 10).roundToInt() / 10.0

private fun round0(v: Double): Double = v.roundToInt().toDouble()
