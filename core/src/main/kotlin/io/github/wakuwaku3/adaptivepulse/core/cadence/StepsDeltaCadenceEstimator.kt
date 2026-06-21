package io.github.wakuwaku3.adaptivepulse.core.cadence

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 累積歩数 (Health Services `DataType.STEPS`) から SPM を推定する。
 * `STEPS_PER_MINUTE` が出ない端末・運動でも、累積歩数だけは増えることがある
 * (pace-metric tier 2 のフォールバック)。
 *
 * Android 非依存 (`:core` 内、JVM 単体テストで検証)。
 * 呼び出し側は `TimeSource.Monotonic.markNow()` の `elapsedNow()` のような単調増加時刻を渡す。
 */
class StepsDeltaCadenceEstimator(
    private val window: Duration = 10.seconds,
    private val minSpan: Duration = 3.seconds,
) {

    private data class Point(val at: Duration, val cumulative: Long)

    private val points = ArrayDeque<Point>()

    /** 累積値を追加し、現時点の SPM 推定を返す。確定できないときは null */
    fun update(at: Duration, cumulative: Long): Double? {
        // 機器再接続等で累積値が後退したら履歴をリセット (負の rate を弾く)
        if (points.isNotEmpty() && cumulative < points.last().cumulative) {
            points.clear()
        }
        points.addLast(Point(at, cumulative))
        evict(at)
        return compute(at)
    }

    /** サンプル追加なしで現時点の推定だけ取り出す。窓外まで来たら null になる */
    fun spm(now: Duration): Double? {
        evict(now)
        return compute(now)
    }

    private fun compute(now: Duration): Double? {
        if (points.size < 2) return null
        val first = points.first()
        val last = points.last()
        val span = last.at - first.at
        if (span < minSpan) return null
        val deltaSteps = last.cumulative - first.cumulative
        if (deltaSteps <= 0) return null
        val seconds = span.inWholeMilliseconds / 1000.0
        return deltaSteps.toDouble() / seconds * 60.0
    }

    private fun evict(now: Duration) {
        while (points.isNotEmpty() && now - points.first().at > window) {
            points.removeFirst()
        }
    }
}
