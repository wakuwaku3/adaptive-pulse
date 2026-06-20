package io.github.wakuwaku3.adaptivepulse.mobile.ui.dashboard

import io.github.wakuwaku3.adaptivepulse.mobile.store.DailySnapshotEntity

/**
 * Today カード + trend で使う派生指標。Room の生エンティティから 1 か所で計算しておき、
 * UI 側ではそのまま表示するだけにする。
 *
 * 数値モデル:
 *  - TDEE は HC `totalCaloriesKcal` をそのまま採用 (BMR 込みの集計値、検証済み)
 *  - deficit = TDEE - intake
 *  - BMR_est は Mifflin-St Jeor (体重と身長から)
 */
data class DashboardComputed(
    val date: String,
    val weightKg: Double?,
    val bodyFatPct: Double?,
    val tdeeKcal: Double?,
    val intakeKcal: Double?,
    val deficitKcal: Double?,
    val proteinG: Double?,
    val fatG: Double?,
    val carbsG: Double?,
    val sleepHours: Double?,
    val sleepDeepMin: Long?,
    val sleepRemMin: Long?,
    val hrvMs: Double?,
    val restingHrBpm: Int?,
    val steps: Long?,
    val bmrEstKcal: Double?,
    val proteinPerKg: Double?,
    val spo2AvgPct: Double?,
)

fun DailySnapshotEntity.computed(ageYears: Int = 39): DashboardComputed {
    val bmr = bmrMifflinStJeor(weightKg, heightCm, ageYears)
    val deficit = if (totalCaloriesKcal != null && intakeKcal != null) totalCaloriesKcal - intakeKcal else null
    val sleepHours = sleepDurationMin?.let { it / 60.0 }
    val protKg = if (proteinG != null && weightKg != null) proteinG / weightKg else null
    return DashboardComputed(
        date = date,
        weightKg = weightKg,
        bodyFatPct = bodyFatPct?.takeIf { it > 1.0 }, // 0.0 が欠損として書かれている既知バグ
        tdeeKcal = totalCaloriesKcal,
        intakeKcal = intakeKcal,
        deficitKcal = deficit,
        proteinG = proteinG,
        fatG = fatG,
        carbsG = carbsG,
        sleepHours = sleepHours,
        sleepDeepMin = sleepDeepMin,
        sleepRemMin = sleepRemMin,
        hrvMs = hrvRmssdMs,
        restingHrBpm = restingHeartRateBpm,
        steps = steps,
        bmrEstKcal = bmr,
        proteinPerKg = protKg,
        spo2AvgPct = spo2AvgPct,
    )
}

/** Mifflin-St Jeor (男性): 10w + 6.25h - 5age + 5。性別 UI を持たないので男性固定 (本人 1 名運用) */
private fun bmrMifflinStJeor(weightKg: Double?, heightCm: Double?, ageYears: Int): Double? {
    if (weightKg == null || heightCm == null) return null
    return 10 * weightKg + 6.25 * heightCm - 5 * ageYears + 5
}
