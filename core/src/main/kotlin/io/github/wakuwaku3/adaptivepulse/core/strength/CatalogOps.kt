package io.github.wakuwaku3.adaptivepulse.core.strength

/**
 * カタログ操作の純関数群。updatedAtMs / updatedBy の刻印は mobile 側 repository が
 * 行うため、ここでは扱わない (時刻はデータとして必要な箇所だけ引数で受ける)。
 * 名前系の操作は null 返しで「一意性違反 or 空名」を表す。
 */

/** 一意性判定は trim + 大文字小文字無視。"Chest Press" と "chest press " を同名扱いにする */
private fun normalize(name: String): String = name.trim().lowercase()

fun Gym.isTrainingNameAvailable(name: String, excludeTrainingId: String? = null): Boolean {
    val candidate = normalize(name)
    if (candidate.isEmpty()) return false
    // 非表示も含めて判定する。除外すると再表示時に重複が生まれる
    return trainings.none { it.id != excludeTrainingId && normalize(it.name) == candidate }
}

fun StrengthCatalog.isGymNameAvailable(name: String, excludeGymId: String? = null): Boolean {
    val candidate = normalize(name)
    if (candidate.isEmpty()) return false
    return gyms.none { it.id != excludeGymId && normalize(it.name) == candidate }
}

fun StrengthCatalog.addGym(id: String, name: String): StrengthCatalog? {
    if (!isGymNameAvailable(name)) return null
    return copy(gyms = gyms + Gym(id = id, name = name.trim()), lastGymId = id)
}

fun StrengthCatalog.renameGym(gymId: String, name: String): StrengthCatalog? {
    if (gyms.none { it.id == gymId }) return null
    if (!isGymNameAvailable(name, excludeGymId = gymId)) return null
    return updateGym(gymId) { it.copy(name = name.trim()) }
}

fun StrengthCatalog.selectGym(gymId: String): StrengthCatalog? {
    if (gyms.none { it.id == gymId }) return null
    return copy(lastGymId = gymId)
}

fun StrengthCatalog.addTraining(gymId: String, trainingId: String, name: String): StrengthCatalog? {
    val gym = gyms.firstOrNull { it.id == gymId } ?: return null
    if (!gym.isTrainingNameAvailable(name)) return null
    return updateGym(gymId) { it.copy(trainings = it.trainings + Training(id = trainingId, name = name.trim())) }
}

fun StrengthCatalog.renameTraining(gymId: String, trainingId: String, name: String): StrengthCatalog? {
    val gym = gyms.firstOrNull { it.id == gymId } ?: return null
    if (gym.trainings.none { it.id == trainingId }) return null
    if (!gym.isTrainingNameAvailable(name, excludeTrainingId = trainingId)) return null
    return updateTraining(gymId, trainingId) { it.copy(name = name.trim()) }
}

fun StrengthCatalog.setTrainingHidden(gymId: String, trainingId: String, hidden: Boolean): StrengthCatalog =
    updateTraining(gymId, trainingId) { it.copy(hidden = hidden) } ?: this

/** セット記録の反映。次回 workout の自動記入元になる直近実績を更新する */
fun StrengthCatalog.recordSetResult(
    gymId: String,
    trainingId: String,
    weightKg: Double?,
    reps: Int,
    nowMs: Long,
): StrengthCatalog =
    updateTraining(gymId, trainingId) {
        it.copy(lastWeightKg = weightKg, lastReps = reps, lastPerformedAtMs = nowMs)
    } ?: this

private fun StrengthCatalog.updateGym(gymId: String, transform: (Gym) -> Gym): StrengthCatalog =
    copy(gyms = gyms.map { if (it.id == gymId) transform(it) else it })

private fun StrengthCatalog.updateTraining(
    gymId: String,
    trainingId: String,
    transform: (Training) -> Training,
): StrengthCatalog? {
    val gym = gyms.firstOrNull { it.id == gymId } ?: return null
    if (gym.trainings.none { it.id == trainingId }) return null
    return updateGym(gymId) { g ->
        g.copy(trainings = g.trainings.map { if (it.id == trainingId) transform(it) else it })
    }
}
