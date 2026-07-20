package io.github.wakuwaku3.adaptivepulse.core

/**
 * セッションのトレーニング品質メトリクス。心拍サンプルは約 1Hz で届く前提のため、
 * サンプル数の比でゾーン滞在率を近似する (時間重み付けはしない)。
 *
 * ウォームアップ区間 (= サイクル 1 開始から心拍が下限閾値を上向きに超えるまで) の低心拍
 * サンプルは zoneRatio/avgBpm/maxBpm に算入しない (`requirements.md` のウォームアップ除外を
 * 高強度所要時間以外の指標にも一貫適用するため)。
 *
 * 帯はサンプルごとに受け取る (プログラム実行中はセグメントごとに帯が変わるため)。
 */
class SessionMetrics {

    private var zoneSamples = 0
    private var totalSamples = 0
    private var bpmSum = 0L

    var maxBpm: Int? = null
        private set

    fun onHeartRate(bpm: Int, inMeasurement: Boolean, lowerBpm: Int?, upperBpm: Int) {
        if (!inMeasurement) return
        totalSamples++
        bpmSum += bpm
        if (bpm > (maxBpm ?: Int.MIN_VALUE)) maxBpm = bpm
        if (bpm <= upperBpm && (lowerBpm == null || bpm >= lowerBpm)) zoneSamples++
    }

    /** 現セグメントの帯にいた割合 (0.0〜1.0)。サンプルが無い間は null */
    val zoneRatio: Double?
        get() = if (totalSamples == 0) null else zoneSamples.toDouble() / totalSamples

    val avgBpm: Int?
        get() = if (totalSamples == 0) null else (bpmSum / totalSamples).toInt()
}
