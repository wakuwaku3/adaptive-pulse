package io.github.wakuwaku3.adaptivepulse.core.strength

/**
 * 推定 1RM (e1RM)。種目ごとの筋力の伸びを、重量×回数の組に依存しない
 * 単一数値で追うための標準指標 (docs/stock/requirements.md「筋トレ記録」)。
 */
object E1rm {

    /**
     * 推定式の妥当性が検証されているのは概ね 10 reps 以下
     * (LeSuer et al. 1997)。これを超えるセットは参考値扱いにする。
     */
    const val VALID_MAX_REPS = 10

    /** Epley (1985): weight × (1 + reps/30) */
    fun epley(weightKg: Double, reps: Int): Double = weightKg * (1 + reps / 30.0)
}

/** 種目の伸びを測る軸。負荷あり = e1RM、負荷なし (自重・ストレッチ) = 最高 reps */
enum class ProgressMetric { E1RM, REPS }

data class ProgressPoint(
    val atMs: Long,
    val value: Double,
    /** 10 reps 超のセットしかなく e1RM の妥当性範囲外から算出した参考値 */
    val estimateOnly: Boolean = false,
)

data class TrainingProgress(
    val trainingId: String,
    val trainingName: String,
    val metric: ProgressMetric,
    /** workout 開始時刻の昇順 */
    val points: List<ProgressPoint>,
)

/**
 * ジム 1 つ分の種目別成長系列。表示中トレーニングを登録順 (= 順路順) に並べ、
 * 1 workout = 1 点 (セッションベスト) の時系列を種目ごとに返す。
 * 実績が 1 点もない種目は返さない。
 */
fun trainingProgress(gym: Gym, workouts: List<WorkoutRecord>): List<TrainingProgress> {
    val gymWorkouts = workouts.filter { it.gymId == gym.id }.sortedBy { it.startedAtMs }
    return gym.trainings.filterNot { it.hidden }.mapNotNull { training ->
        val performed = gymWorkouts.mapNotNull { workout ->
            workout.entries
                .firstOrNull { it.trainingId == training.id && !it.skipped && it.sets.isNotEmpty() }
                ?.let { workout.startedAtMs to it.sets }
        }
        if (performed.isEmpty()) return@mapNotNull null
        val weighted = performed.any { (_, sets) -> sets.any { it.weightKg != null } }
        val points = performed.mapNotNull { (atMs, sets) ->
            if (weighted) e1rmBest(atMs, sets) else repsBest(atMs, sets)
        }
        if (points.isEmpty()) return@mapNotNull null
        TrainingProgress(
            trainingId = training.id,
            trainingName = training.name,
            metric = if (weighted) ProgressMetric.E1RM else ProgressMetric.REPS,
            points = points,
        )
    }
}

private fun e1rmBest(atMs: Long, sets: List<TrainingSet>): ProgressPoint? {
    val candidates = sets.filter { it.weightKg != null }
    if (candidates.isEmpty()) return null
    // 妥当性範囲内のセットを優先し、範囲外しかない日は欠測にせず参考値として点を打つ
    val valid = candidates.filter { it.reps <= E1rm.VALID_MAX_REPS }
    val pool = valid.ifEmpty { candidates }
    val best = pool.maxOf { E1rm.epley(it.weightKg!!, it.reps) }
    return ProgressPoint(atMs, best, estimateOnly = valid.isEmpty())
}

private fun repsBest(atMs: Long, sets: List<TrainingSet>): ProgressPoint =
    ProgressPoint(atMs, sets.maxOf { it.reps }.toDouble())
