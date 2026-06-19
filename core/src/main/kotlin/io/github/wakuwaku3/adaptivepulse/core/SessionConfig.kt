package io.github.wakuwaku3.adaptivepulse.core

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * セッションの設定値。デフォルトは docs/stock/requirements.md の表に従う。
 * すべて変更可能にする要件のため、ハードコードせずここに集約する。
 *
 * 上限/下限閾値のデフォルトは年齢と安静時心拍から Karvonen 式 ([HeartRateZones]) で
 * 導出する。upperBpm/lowerBpm を明示指定したときはそちらが優先される。
 */
data class SessionConfig(
    val ageYears: Int = 39,
    val restingBpm: Int = 60,
    val upperBpm: Int = HeartRateZones.defaultUpperBpm(ageYears, restingBpm),
    val lowerBpm: Int = HeartRateZones.defaultLowerBpm(ageYears, restingBpm),
    val targetCycles: Int = 7,
    val fatigueRatio: Double = 0.5,
    val recoveryFatigueRatio: Double = 1.5,
    val minBaseline: Duration = 45.seconds,
    val highPhaseTimeout: Duration = 3.minutes,
    val recoveryTimeout: Duration = 3.minutes,
) {
    init {
        // 15bpm のヒステリシス幅はチャタリング防止の要なので、逆転した設定を弾く
        require(upperBpm > lowerBpm) { "上限閾値 ($upperBpm) は下限閾値 ($lowerBpm) より大きいこと" }
        require(targetCycles >= 1) { "目標サイクル数は 1 以上" }
        require(fatigueRatio > 0.0 && fatigueRatio < 1.0) { "早期終了係数は 0 < r < 1" }
        // 回復遅延係数は「基準より長くなったら疲労」なので r > 1。1 だと初回サイクル = 疲労になる
        require(recoveryFatigueRatio > 1.0) { "回復疲労係数は r > 1" }
        require(ageYears in 10..120) { "年齢は 10〜120 の範囲" }
        require(restingBpm in 30..120) { "安静時心拍は 30〜120 の範囲" }
    }
}
