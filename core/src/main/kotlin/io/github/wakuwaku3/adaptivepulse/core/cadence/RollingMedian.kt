package io.github.wakuwaku3.adaptivepulse.core.cadence

import kotlin.time.Duration

/**
 * 時間窓ベースのローリング median。pace-metric note の方針通り、
 * 1 秒ごとの瞬時値はノイジーなため 3〜5 秒窓の median をフィルタとして掛ける。
 *
 * Android 非依存 (`:core` 内、JVM 単体テストで検証する)。
 * 時刻は単調増加の Duration を呼び出し側が渡す (`TimeSource.markNow()` の `elapsedNow()` を想定)。
 */
class RollingMedian(private val window: Duration) {

    private data class Sample(val at: Duration, val value: Double)

    private val samples = ArrayDeque<Sample>()

    fun add(at: Duration, value: Double) {
        samples.addLast(Sample(at, value))
        evict(at)
    }

    /** 現在の窓に収まる median。サンプルが無いときは null */
    fun median(now: Duration): Double? {
        evict(now)
        if (samples.isEmpty()) return null
        val sorted = samples.map { it.value }.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
    }

    private fun evict(now: Duration) {
        while (samples.isNotEmpty() && now - samples.first().at > window) {
            samples.removeFirst()
        }
    }
}
