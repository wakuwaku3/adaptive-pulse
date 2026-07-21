package io.github.wakuwaku3.adaptivepulse.core.strength

/**
 * workout 操作の純関数群。永続化と Firestore 送信は mobile 側が行い、
 * ここは「入力 → 新しいレコード」の変換だけを持つ (JVM 単体テストで検証する)。
 */

fun startWorkout(gym: Gym, id: String, nowMs: Long): WorkoutRecord =
    WorkoutRecord(
        id = id,
        gymId = gym.id,
        gymName = gym.name,
        startedAtMs = nowMs,
        lastInputAtMs = nowMs,
    )

fun WorkoutRecord.addSet(
    trainingId: String,
    trainingName: String,
    weightKg: Double?,
    reps: Int,
    nowMs: Long,
): WorkoutRecord {
    val set = TrainingSet(weightKg = weightKg, reps = reps, recordedAtMs = nowMs)
    // セットを記録した時点で「やらない」は成立しないので skipped は常に外す
    val updated = entries.firstOrNull { it.trainingId == trainingId }
        ?.let { entry ->
            entries.map {
                if (it.trainingId == trainingId) entry.copy(skipped = false, sets = entry.sets + set) else it
            }
        }
        ?: (entries + WorkoutEntry(trainingId = trainingId, trainingName = trainingName, sets = listOf(set)))
    return copy(entries = updated, lastInputAtMs = nowMs)
}

/** 誤入力の修正。recordedAtMs は実施時刻なので保持し、lastInputAtMs だけ進める */
fun WorkoutRecord.updateSet(
    trainingId: String,
    index: Int,
    weightKg: Double?,
    reps: Int,
    nowMs: Long,
): WorkoutRecord? {
    val entry = entries.firstOrNull { it.trainingId == trainingId } ?: return null
    val set = entry.sets.getOrNull(index) ?: return null
    val updatedEntry = entry.copy(
        sets = entry.sets.toMutableList().apply { this[index] = set.copy(weightKg = weightKg, reps = reps) },
    )
    return copy(
        entries = entries.map { if (it.trainingId == trainingId) updatedEntry else it },
        lastInputAtMs = nowMs,
    )
}

fun WorkoutRecord.removeSet(trainingId: String, index: Int, nowMs: Long): WorkoutRecord? {
    val entry = entries.firstOrNull { it.trainingId == trainingId } ?: return null
    if (entry.sets.getOrNull(index) == null) return null
    val remaining = entry.sets.filterIndexed { i, _ -> i != index }
    // セットが空になった entry は「未入力」に戻す (skipped マークだけは残す)
    val updated = when {
        remaining.isEmpty() && !entry.skipped -> entries.filterNot { it.trainingId == trainingId }
        else -> entries.map { if (it.trainingId == trainingId) entry.copy(sets = remaining) else it }
    }
    return copy(entries = updated, lastInputAtMs = nowMs)
}

fun WorkoutRecord.setSkipped(
    trainingId: String,
    trainingName: String,
    skipped: Boolean,
    nowMs: Long,
): WorkoutRecord {
    val entry = entries.firstOrNull { it.trainingId == trainingId }
    val updated = when {
        entry == null && skipped ->
            entries + WorkoutEntry(trainingId = trainingId, trainingName = trainingName, skipped = true)
        entry == null -> entries
        // マーク解除でセットも無ければ「未入力」に戻す
        !skipped && entry.sets.isEmpty() -> entries.filterNot { it.trainingId == trainingId }
        else -> entries.map { if (it.trainingId == trainingId) entry.copy(skipped = skipped) else it }
    }
    return copy(entries = updated, lastInputAtMs = nowMs)
}

fun WorkoutRecord.finished(nowMs: Long): WorkoutRecord =
    copy(endedAtMs = nowMs, endReason = WorkoutEndReason.FINISH)

data class Prefill(val weightKg: Double?, val reps: Int)

/**
 * セット追加ダイアログの自動記入値。優先順: 当日 workout 内の直前セット →
 * カタログの直近実績 → null (空欄)。
 */
fun prefillFor(workout: WorkoutRecord?, trainingId: String, catalogTraining: Training?): Prefill? {
    workout?.entries?.firstOrNull { it.trainingId == trainingId }?.sets?.lastOrNull()
        ?.let { return Prefill(weightKg = it.weightKg, reps = it.reps) }
    val lastReps = catalogTraining?.lastReps ?: return null
    return Prefill(weightKg = catalogTraining.lastWeightKg, reps = lastReps)
}

/** 未入力 (セットなし・skip マークなし) の表示中トレーニング数。Finish ボタン脇の表示に使う */
fun pendingTrainingCount(workout: WorkoutRecord?, visibleTrainings: List<Training>): Int {
    val touched = workout?.entries
        ?.filter { it.skipped || it.sets.isNotEmpty() }
        ?.map { it.trainingId }
        ?.toSet()
        .orEmpty()
    return visibleTrainings.count { it.id !in touched }
}
