package io.github.wakuwaku3.adaptivepulse.core.cadence

import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * 加速度の振幅 (例: 重力除去後の |a|) から SPM を peak detection で推定する。
 * `STEPS_PER_MINUTE` も累積歩数も使えないとき (クロストレーナーで watch の歩行検出が
 * 効かないケース) の tier 3 フォールバック。
 *
 * Android 非依存 (`:core` 内、JVM 単体テストで合成波形を流して検証)。
 * 呼び出し側で `Sensor.TYPE_LINEAR_ACCELERATION` の magnitude を投入する想定。
 */
class AccelerometerCadenceEstimator(
    private val window: Duration = 5.seconds,
    private val minPeakSpacing: Duration = 200.milliseconds,
    private val minPeaks: Int = 3,
    private val noiseFloor: Double = 0.2,
) {

    private data class Sample(val at: Duration, val value: Double)

    private val samples = ArrayDeque<Sample>()

    fun add(at: Duration, value: Double) {
        samples.addLast(Sample(at, value))
        evict(at)
    }

    /** 現時点の SPM 推定。サンプル不足・周期が見えないときは null */
    fun spm(now: Duration): Double? {
        evict(now)
        if (samples.size < 8) return null
        val values = samples.map { it.value }
        val mean = values.average()
        val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
        val sd = sqrt(variance)
        if (sd < noiseFloor) return null
        // 平均 + 0.3σ を超える局所極大を peak とする (HIIT 中の腕振りで十分マージンがある)
        val threshold = mean + 0.3 * sd
        val peakTimes = ArrayList<Duration>()
        for (i in 1 until samples.size - 1) {
            val cur = samples[i]
            val prev = samples[i - 1]
            val next = samples[i + 1]
            if (cur.value < threshold) continue
            if (cur.value < prev.value || cur.value < next.value) continue
            if (peakTimes.isEmpty() || cur.at - peakTimes.last() >= minPeakSpacing) {
                peakTimes.add(cur.at)
            }
        }
        if (peakTimes.size < minPeaks) return null
        val span = peakTimes.last() - peakTimes.first()
        val spanSeconds = span.inWholeMilliseconds / 1000.0
        if (spanSeconds <= 0.0) return null
        return (peakTimes.size - 1) / spanSeconds * 60.0
    }

    private fun evict(now: Duration) {
        while (samples.isNotEmpty() && now - samples.first().at > window) {
            samples.removeFirst()
        }
    }
}
