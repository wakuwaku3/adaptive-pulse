package io.github.wakuwaku3.adaptivepulse.mobile.calories

import androidx.health.connect.client.records.ExerciseSessionRecord
import io.github.wakuwaku3.adaptivepulse.core.calories.ExerciseKind
import io.github.wakuwaku3.adaptivepulse.core.calories.TdeeCalc
import io.github.wakuwaku3.adaptivepulse.core.sync.DailyHealthRecord
import io.github.wakuwaku3.adaptivepulse.core.sync.SessionRecord
import io.github.wakuwaku3.adaptivepulse.mobile.health.ExerciseSessionData

/**
 * `DailyHealthRecord` の `tdeeKcal` / `exerciseExtraKcal` を [TdeeCalc] で計算して埋める。
 * HC 由来 / 自社 HIIT 両方のセッションを必要とするため、`HealthDataSource` から切り出している。
 */
object CalorieEnricher {

    /** ユーザの年齢 (Mifflin-St Jeor フォールバック用)。`SessionConfig.ageYears` と揃える。 */
    private const val DEFAULT_AGE_YEARS = 39

    fun enrich(
        record: DailyHealthRecord,
        hcSessions: List<ExerciseSessionData>,
        appSessions: List<SessionRecord>,
        ageYears: Int = DEFAULT_AGE_YEARS,
    ): DailyHealthRecord {
        val br = TdeeCalc.compute(
            basalKcal = record.basalCaloriesKcal,
            weightKg = record.weightKg,
            heightCm = record.heightCm,
            ageYears = ageYears,
            steps = record.steps,
            hcSessions = hcSessions.map { it.toCore() },
            appSessions = appSessions.map { TdeeCalc.AppSession(it.startedAtMs, it.durationSec) },
        )
        return record.copy(
            tdeeKcal = br.tdeeKcal,
            exerciseExtraKcal = br.exerciseExtraKcal,
        )
    }

    private fun ExerciseSessionData.toCore(): TdeeCalc.HcSession =
        TdeeCalc.HcSession(startTimeMs, endTimeMs, exerciseType.toExerciseKind())

    private fun Int.toExerciseKind(): ExerciseKind = when (this) {
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> ExerciseKind.WALKING
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> ExerciseKind.RUNNING
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING -> ExerciseKind.BIKING
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> ExerciseKind.BIKING_STATIONARY
        ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL -> ExerciseKind.ELLIPTICAL
        ExerciseSessionRecord.EXERCISE_TYPE_ROWING -> ExerciseKind.ROWING
        ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE -> ExerciseKind.ROWING_MACHINE
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING -> ExerciseKind.STRENGTH_TRAINING
        ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> ExerciseKind.WEIGHTLIFTING
        ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING -> ExerciseKind.HIIT
        else -> ExerciseKind.OTHER
    }
}
