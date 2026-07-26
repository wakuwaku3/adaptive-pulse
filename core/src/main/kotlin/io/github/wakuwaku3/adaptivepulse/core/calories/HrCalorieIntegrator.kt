package io.github.wakuwaku3.adaptivepulse.core.calories

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/**
 * 心拍サンプル (約 1Hz) からセッション中の消費カロリーを逐次積算する。
 *
 * 推定式は Keytel et al. 2005 (J Sports Sci 23(3)) の心拍-エネルギー消費回帰
 * (VO2max 無しモデル・男性):
 *
 *   EE (kJ/min) = -55.0969 + 0.6309*HR + 0.1988*weightKg + 0.2017*ageYears
 *
 * 性別定数は男性を採用する (TdeeCalc の Mifflin-St Jeor +5 と同じ前提)。
 * 回帰の適用域 (中〜高強度) を下回る低心拍では負値になりうるため 0 に clamp する。
 * サンプル間は台形則 (前後 bpm の平均) で積分する。
 */
class HrCalorieIntegrator(
    private val weightKg: Double,
    private val ageYears: Int,
) {
    var totalKcal: Double = 0.0
        private set

    private var lastElapsed: Duration? = null
    private var lastBpm: Int? = null

    fun onSample(elapsed: Duration, bpm: Int) {
        val prevElapsed = lastElapsed
        val prevBpm = lastBpm
        lastElapsed = elapsed
        lastBpm = bpm
        if (prevElapsed == null || prevBpm == null) return
        // センサードロップアウトの欠測区間を直前 HR で外挿しない (欠測は積算しない)
        val dt = (elapsed - prevElapsed).coerceAtMost(MAX_SAMPLE_GAP)
        if (dt <= Duration.ZERO) return
        totalKcal += kcalPerMinute((prevBpm + bpm) / 2.0) * dt.toDouble(DurationUnit.MINUTES)
    }

    private fun kcalPerMinute(bpm: Double): Double =
        ((-55.0969 + 0.6309 * bpm + 0.1988 * weightKg + 0.2017 * ageYears) / KJ_PER_KCAL)
            .coerceAtLeast(0.0)

    companion object {
        private val MAX_SAMPLE_GAP = 5.seconds
        private const val KJ_PER_KCAL = 4.184
    }
}
