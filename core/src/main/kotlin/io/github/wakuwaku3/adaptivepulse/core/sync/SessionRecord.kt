package io.github.wakuwaku3.adaptivepulse.core.sync

import io.github.wakuwaku3.adaptivepulse.core.SessionConfig
import io.github.wakuwaku3.adaptivepulse.core.cadence.CadenceTier
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.Serializable

/**
 * セッション履歴の 1 件。watch が生成し、Data Layer → phone → server → Firestore と
 * そのまま JSON で運ばれる (スキーマは docs/stock/sync.md と一致させる)。
 */
@Serializable
data class SessionRecord(
    val id: String,
    val schema: Int = 2,
    val startedAtMs: Long,
    val durationSec: Long,
    val cycles: Int,
    val plannedCycles: Int,
    val fatigueBrake: Boolean,
    val calories: Double? = null,
    val zoneRatio: Double? = null,
    /** per-cycle 高強度所要時間。体力トレンド (同負荷で上限到達が遅くなる = 向上) の源泉 */
    val highDurationsSec: List<Double> = emptyList(),
    /** per-cycle 回復所要時間。同負荷で回復が速くなる = 向上の指標 */
    val recoveryDurationsSec: List<Double> = emptyList(),
    val avgBpm: Int? = null,
    val maxBpm: Int? = null,
    val config: SessionConfigSnapshot,
    val device: String = "watch",
    /**
     * セッション終了時点の target cadence (pace-metric Phase B)。
     * 次セッション開始時に直近 N 件の median を初期値として使う (= 1 セッションのブレを吸収)。
     */
    val finalTargetCadenceHigh: Double? = null,
    val finalTargetCadenceRecovery: Double? = null,
    /**
     * SPM を計測した tier (3 段フォールバックのどれか)。warm-up + discovery 窓内で
     * セッションが終わった場合は null。後追いで「どのロジックで測ったセッションか」を
     * 評価するために残す (schema 2 で追加, FB 2026-06-22)。
     */
    val lockedCadenceTier: CadenceTier? = null,
)

/** セッション時点の設定スナップショット (履歴の文脈として残す) */
@Serializable
data class SessionConfigSnapshot(
    val upperBpm: Int,
    val lowerBpm: Int,
    val targetCycles: Int,
    val fatigueRatio: Double,
    val minBaselineSec: Long,
    val highTimeoutSec: Long,
    val recoveryTimeoutSec: Long,
    val ageYears: Int = 39,
    val restingBpm: Int = 60,
    val recoveryFatigueRatio: Double = 1.5,
    val seedTargetCadenceHigh: Double = 130.0,
    val seedTargetCadenceRecovery: Double = 65.0,
    val heightCm: Int? = null,
    val upperBpmFatigueDecay: Int = 2,
) {
    companion object {
        fun from(config: SessionConfig) = SessionConfigSnapshot(
            upperBpm = config.upperBpm,
            lowerBpm = config.lowerBpm,
            targetCycles = config.targetCycles,
            fatigueRatio = config.fatigueRatio,
            minBaselineSec = config.minBaseline.inWholeSeconds,
            highTimeoutSec = config.highPhaseTimeout.inWholeSeconds,
            recoveryTimeoutSec = config.recoveryTimeout.inWholeSeconds,
            ageYears = config.ageYears,
            restingBpm = config.restingBpm,
            recoveryFatigueRatio = config.recoveryFatigueRatio,
            seedTargetCadenceHigh = config.seedTargetCadenceHigh,
            seedTargetCadenceRecovery = config.seedTargetCadenceRecovery,
            heightCm = config.heightCm,
            upperBpmFatigueDecay = config.upperBpmFatigueDecay,
        )
    }
}

/**
 * 設定の同期ペイロード (watch ⇄ phone ⇄ server)。
 * updatedAtMs の最終更新者勝ち (LWW) で解決する (docs/stock/sync.md)。
 */
@Serializable
data class SettingsDocument(
    val upperBpm: Int,
    val lowerBpm: Int,
    val targetCycles: Int,
    val fatigueRatio: Double,
    val minBaselineSec: Long,
    val highTimeoutSec: Long,
    val recoveryTimeoutSec: Long,
    val updatedAtMs: Long,
    val updatedBy: String,
    val ageYears: Int = 39,
    val restingBpm: Int = 60,
    val recoveryFatigueRatio: Double = 1.5,
    val seedTargetCadenceHigh: Double = 130.0,
    val seedTargetCadenceRecovery: Double = 65.0,
    val heightCm: Int? = null,
    val upperBpmFatigueDecay: Int = 2,
) {
    fun toSessionConfig() = SessionConfig(
        ageYears = ageYears,
        restingBpm = restingBpm,
        upperBpm = upperBpm,
        lowerBpm = lowerBpm,
        targetCycles = targetCycles,
        fatigueRatio = fatigueRatio,
        recoveryFatigueRatio = recoveryFatigueRatio,
        minBaseline = minBaselineSec.seconds,
        highPhaseTimeout = highTimeoutSec.seconds,
        recoveryTimeout = recoveryTimeoutSec.seconds,
        seedTargetCadenceHigh = seedTargetCadenceHigh,
        seedTargetCadenceRecovery = seedTargetCadenceRecovery,
        heightCm = heightCm,
        upperBpmFatigueDecay = upperBpmFatigueDecay,
    )

    companion object {
        fun from(config: SessionConfig, updatedAtMs: Long, updatedBy: String) = SettingsDocument(
            upperBpm = config.upperBpm,
            lowerBpm = config.lowerBpm,
            targetCycles = config.targetCycles,
            fatigueRatio = config.fatigueRatio,
            minBaselineSec = config.minBaseline.inWholeSeconds,
            highTimeoutSec = config.highPhaseTimeout.inWholeSeconds,
            recoveryTimeoutSec = config.recoveryTimeout.inWholeSeconds,
            updatedAtMs = updatedAtMs,
            updatedBy = updatedBy,
            ageYears = config.ageYears,
            restingBpm = config.restingBpm,
            recoveryFatigueRatio = config.recoveryFatigueRatio,
            seedTargetCadenceHigh = config.seedTargetCadenceHigh,
            seedTargetCadenceRecovery = config.seedTargetCadenceRecovery,
            heightCm = config.heightCm,
            upperBpmFatigueDecay = config.upperBpmFatigueDecay,
        )
    }
}
