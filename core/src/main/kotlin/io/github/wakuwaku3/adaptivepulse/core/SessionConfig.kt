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
    /**
     * 身長 (cm)。BMI 表示と BMR フォールバックの入力。
     * Health Connect の `HeightRecord` が無い環境 (Google Fit 切断後など) で値を埋めるための
     * ユーザ入力欄。null = 未設定 → BMI 表示は "—" のまま。
     * デフォルト値はハードコードしない (身長は個人値なので source に embed しない)。
     */
    val heightCm: Int? = null,
    val upperBpm: Int = HeartRateZones.defaultUpperBpm(ageYears, restingBpm),
    val lowerBpm: Int = HeartRateZones.defaultLowerBpm(ageYears, restingBpm),
    val targetCycles: Int = 5,
    /**
     * 高強度短縮の「ペースを緩めましょう」提案閾値: 現サイクルの高強度所要時間が
     * 初回基準の本比率以下になったら engine が提案を出す。auto-brake は廃止 (FB 2026-06-24)。
     */
    val fatigueRatio: Double = 0.5,
    /**
     * 回復遅延の「中断を検討してください」提案閾値: 現サイクルの回復所要時間が
     * 基準の本比率以上になったら提案を出す。auto-brake は廃止 (FB 2026-06-24)。
     */
    val recoveryFatigueRatio: Double = 1.5,
    val minBaseline: Duration = 45.seconds,
    // 上限-下限ギャップ ~30bpm (Karvonen 0.86/0.60) を前提に 4 分。
    // HRR は 1 分目 ~25-30bpm 降下後プラトーする (Imai 1994, Cole 1999) ため、
    // 3 分だと後半サイクルで届かないリスクが大きい
    val highPhaseTimeout: Duration = 4.minutes,
    val recoveryTimeout: Duration = 4.minutes,
    /**
     * 高強度フェーズの目標 cadence (SPM)。phone ライブ画面の回転体 tempo に使う。
     * 実測 SPM は精度が安定しないため計測はせず、ユーザ設定のみで決める。
     */
    val targetCadenceHigh: Int = 130,
    /** 回復フェーズの目標 cadence (SPM)。同様にユーザ設定のみ */
    val targetCadenceRecovery: Int = 90,
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
        require(heightCm == null || heightCm in 100..230) { "身長は 100〜230 cm の範囲" }
        require(targetCadenceHigh > targetCadenceRecovery) {
            "高強度 cadence ($targetCadenceHigh) は回復 cadence ($targetCadenceRecovery) より速いこと"
        }
        require(targetCadenceHigh in 60..220) { "高強度 cadence は 60〜220 SPM の範囲" }
        require(targetCadenceRecovery in 30..180) { "回復 cadence は 30〜180 SPM の範囲" }
    }
}
