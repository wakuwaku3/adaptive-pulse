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
    // 上限-下限ギャップ ~30bpm (Karvonen 0.86/0.60) を前提に 4 分。
    // HRR は 1 分目 ~25-30bpm 降下後プラトーする (Imai 1994, Cole 1999) ため、
    // 3 分だと後半サイクルで届かないリスクが大きい
    val highPhaseTimeout: Duration = 4.minutes,
    val recoveryTimeout: Duration = 4.minutes,
    /** 高強度フェーズの目標 cadence (step/min)。ユーザの実測 2.4Hz ≈ 144 SPM (pace-metric note) */
    val targetHighSpm: Int = 144,
    /** 回復フェーズの目標 cadence (step/min)。ユーザの実測 1.2Hz ≈ 72 SPM */
    val targetRecoverySpm: Int = 72,
) {
    init {
        // チャタリング防止のため上限<下限の逆転を構造的に弾く
        require(upperBpm > lowerBpm) { "上限閾値 ($upperBpm) は下限閾値 ($lowerBpm) より大きいこと" }
        require(targetCycles >= 1) { "目標サイクル数は 1 以上" }
        require(fatigueRatio > 0.0 && fatigueRatio < 1.0) { "早期終了係数は 0 < r < 1" }
        // 回復遅延係数は「基準より長くなったら疲労」なので r > 1。1 だと初回サイクル = 疲労になる
        require(recoveryFatigueRatio > 1.0) { "回復疲労係数は r > 1" }
        require(ageYears in 10..120) { "年齢は 10〜120 の範囲" }
        require(restingBpm in 30..120) { "安静時心拍は 30〜120 の範囲" }
        // 高強度の目標は回復の目標より速い (機材実測でも約 2 倍差。pace-metric note)
        require(targetHighSpm > targetRecoverySpm) {
            "高強度目標 ($targetHighSpm) は回復目標 ($targetRecoverySpm) より速いこと"
        }
        require(targetHighSpm in 60..220) { "高強度目標 SPM は 60〜220 の範囲" }
        require(targetRecoverySpm in 30..180) { "回復目標 SPM は 30〜180 の範囲" }
    }
}
