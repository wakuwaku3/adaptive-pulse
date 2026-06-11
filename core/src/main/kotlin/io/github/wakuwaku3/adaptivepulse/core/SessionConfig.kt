package io.github.wakuwaku3.adaptivepulse.core

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * セッションの設定値。デフォルトは docs/stock/requirements.md の表に従う。
 * すべて変更可能にする要件のため、ハードコードせずここに集約する。
 */
data class SessionConfig(
    val upperBpm: Int = 155,
    val lowerBpm: Int = 140,
    val targetCycles: Int = 7,
    val fatigueRatio: Double = 0.5,
    val minBaseline: Duration = 45.seconds,
    val highPhaseTimeout: Duration = 4.minutes,
    val recoveryTimeout: Duration = 3.minutes,
) {
    init {
        // 15bpm のヒステリシス幅はチャタリング防止の要なので、逆転した設定を弾く
        require(upperBpm > lowerBpm) { "上限閾値 ($upperBpm) は下限閾値 ($lowerBpm) より大きいこと" }
        require(targetCycles >= 1) { "目標サイクル数は 1 以上" }
        require(fatigueRatio > 0.0 && fatigueRatio < 1.0) { "早期終了係数は 0 < r < 1" }
    }
}
