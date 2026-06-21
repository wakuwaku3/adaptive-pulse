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
    val targetCycles: Int = 5,
    val fatigueRatio: Double = 0.5,
    val recoveryFatigueRatio: Double = 1.5,
    val minBaseline: Duration = 45.seconds,
    // 上限-下限ギャップ ~30bpm (Karvonen 0.86/0.60) を前提に 4 分。
    // HRR は 1 分目 ~25-30bpm 降下後プラトーする (Imai 1994, Cole 1999) ため、
    // 3 分だと後半サイクルで届かないリスクが大きい
    val highPhaseTimeout: Duration = 4.minutes,
    val recoveryTimeout: Duration = 4.minutes,
    /**
     * Day-1 seed (高強度目標 cadence)。pace-metric note の「上から探索より下から探索」方針で
     * 実測自然値 (140〜150 SPM) よりやや低めに置き、制御ループが必要に応じて上向き探索する。
     * 2 セッション目以降は前回最終 target を持ち越し、これは初回限定 fallback。
     */
    val seedTargetCadenceHigh: Double = 130.0,
    /**
     * Day-1 seed (回復目標 cadence)。実測 70 SPM だが、最初の数セッションで「歩行域まで落としすぎ」になり
     * 回復が長引きやすかった。歩行よりやや上の 90 SPM スタートに変更 (2026-06-21 FB)。
     */
    val seedTargetCadenceRecovery: Double = 90.0,
    /**
     * 制御ループの anchor: 高強度フェーズの理想 duration 窓 (pace-metric note §ペースをどう決めるか)。
     * Buchheit & Laursen 2013 / 心肺ストレス十分 × 中枢疲労未到達。
     * observed < min → 速すぎて target を下げる / observed > max → 遅すぎて target を上げる。
     */
    val cadenceTargetHighDurationMin: Duration = 45.seconds,
    val cadenceTargetHighDurationMax: Duration = 90.seconds,
    /** 回復フェーズの理想 duration 窓 (HRR の急峻区間 = Cole et al. 1999 の根拠帯) */
    val cadenceTargetRecoveryDurationMin: Duration = 30.seconds,
    val cadenceTargetRecoveryDurationMax: Duration = 75.seconds,
    /** 制御ゲイン (SPM per second of deviation)。1 cycle で最大 ±10 SPM 程度に収まるよう 0.2 を初期値とする */
    val cadenceControlGain: Double = 0.2,

    /**
     * intra-cycle stall 検知の grace period。phase 開始直後は HR の応答ラグがあるので、
     * この時間は stall 判定を停止する (pace-metric 2026-06-21 FB)。
     */
    val cadenceStallGracePeriod: Duration = 20.seconds,
    /** stall チェック間隔。grace 後はこの間隔ごとに BPM の動きを評価する */
    val cadenceStallCheckInterval: Duration = 10.seconds,
    /** stall と判定する BPM 移動量。check interval の間にこの値未満しか動かなければ stall */
    val cadenceStallBpmThreshold: Int = 2,
    /** stall 判定での「実測 cadence が target に近い」許容差 (SPM) */
    val cadenceStallCadenceTolerance: Double = 5.0,
    /** stall 判定 1 回あたりの target nudge (SPM)。HIGH は +、RECOVERY は - 方向 */
    val cadenceStallNudge: Double = 1.0,
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
        require(seedTargetCadenceHigh > seedTargetCadenceRecovery) {
            "高強度 seed ($seedTargetCadenceHigh) は回復 seed ($seedTargetCadenceRecovery) より速いこと"
        }
        require(seedTargetCadenceHigh in 60.0..220.0) { "高強度 seed は 60〜220 SPM の範囲" }
        require(seedTargetCadenceRecovery in 30.0..180.0) { "回復 seed は 30〜180 SPM の範囲" }
        require(cadenceTargetHighDurationMin < cadenceTargetHighDurationMax) {
            "高強度 duration 窓は min < max"
        }
        require(cadenceTargetRecoveryDurationMin < cadenceTargetRecoveryDurationMax) {
            "回復 duration 窓は min < max"
        }
        require(cadenceControlGain > 0.0) { "cadence 制御ゲインは正" }
    }
}
