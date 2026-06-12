package io.github.wakuwaku3.adaptivepulse.core

/**
 * セッションのトレーニング品質メトリクス。心拍サンプルは約 1Hz で届く前提のため、
 * サンプル数の比でゾーン滞在率を近似する (時間重み付けはしない)。
 */
class SessionMetrics(private val config: SessionConfig) {

    private var zoneSamples = 0
    private var totalSamples = 0
    private var bpmSum = 0L

    var maxBpm: Int? = null
        private set

    fun onHeartRate(bpm: Int) {
        totalSamples++
        bpmSum += bpm
        if (bpm > (maxBpm ?: Int.MIN_VALUE)) maxBpm = bpm
        if (bpm in config.lowerBpm..config.upperBpm) zoneSamples++
    }

    /** 下限〜上限の帯にいた割合 (0.0〜1.0)。サンプルが無い間は null */
    val zoneRatio: Double?
        get() = if (totalSamples == 0) null else zoneSamples.toDouble() / totalSamples

    val avgBpm: Int?
        get() = if (totalSamples == 0) null else (bpmSum / totalSamples).toInt()
}
