package io.github.wakuwaku3.adaptivepulse.core.calories

/**
 * HC `ExerciseSessionRecord.exerciseType` を MET 値にマップするための core 表現。
 * HC 自体に依存させたくないので Android 側で `Int → ExerciseKind` を解いて渡す。
 *
 * MET は Compendium of Physical Activities 2024 の中央値。`stepCovered = true` の
 * 種目は歩数 × kcal/step で別途加算するため、ここでの extra から **除外する**
 * (二重計上回避)。
 */
enum class ExerciseKind(val met: Double, val stepCovered: Boolean) {
    WALKING(3.5, stepCovered = true),
    RUNNING(9.0, stepCovered = true),
    BIKING(7.5, stepCovered = false),
    BIKING_STATIONARY(7.0, stepCovered = false),
    ELLIPTICAL(7.0, stepCovered = false),
    ROWING(7.0, stepCovered = false),
    ROWING_MACHINE(7.0, stepCovered = false),
    STRENGTH_TRAINING(5.0, stepCovered = false),
    WEIGHTLIFTING(3.5, stepCovered = false),
    HIIT(8.0, stepCovered = false),
    /** 上記以外は中強度想定で 5 MET 扱い */
    OTHER(5.0, stepCovered = false),
}
