package io.github.wakuwaku3.adaptivepulse.hr

import io.github.wakuwaku3.adaptivepulse.core.cadence.CadenceTier
import kotlinx.coroutines.flow.Flow

/** 運動センサーの 1 サンプル。心拍が主役で、取れる経路ではカロリー累計と step/min も載せる */
data class ExerciseSample(
    val bpm: Int,
    val totalCalories: Double? = null,
    /**
     * 直近のステップ毎分 (Health Services の STEPS_PER_MINUTE)。
     * クロストレーナーでは足が接地しないため取れないことがあり、その場合は null。
     */
    val stepsPerMinute: Double? = null,
    /**
     * stepsPerMinute を算出したロジック (3 段フォールバックのどれか)。
     * 確定前 (warm-up + discovery 窓中) は null。SessionRecord に永続化し、
     * 「どの tier で測ったセッションか」を後追いで評価できるようにする。
     */
    val cadenceTier: CadenceTier? = null,
)

/**
 * 運動データソースの抽象。Health Services / BLE (Polar H10) / 合成データを
 * 同一 interface の実装として扱い、ロジック側からは区別しない (.claude/rules/kotlin.md)。
 */
fun interface ExerciseSource {
    fun samples(): Flow<ExerciseSample>
}
