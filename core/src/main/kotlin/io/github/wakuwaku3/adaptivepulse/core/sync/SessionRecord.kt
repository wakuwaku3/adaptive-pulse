package io.github.wakuwaku3.adaptivepulse.core.sync

import io.github.wakuwaku3.adaptivepulse.core.SessionConfig
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.Serializable

/**
 * セッション履歴の 1 件。watch が生成し、Data Layer → phone → server → Firestore と
 * そのまま JSON で運ばれる (スキーマは docs/stock/sync.md と一致させる)。
 */
@Serializable
data class SessionRecord(
    val id: String,
    val schema: Int = 1,
    val startedAtMs: Long,
    val durationSec: Long,
    val cycles: Int,
    val plannedCycles: Int,
    val fatigueBrake: Boolean,
    val calories: Double? = null,
    val zoneRatio: Double? = null,
    /** per-cycle 高強度所要時間。体力トレンド (同負荷で上限到達が遅くなる = 向上) の源泉 */
    val highDurationsSec: List<Double> = emptyList(),
    val avgBpm: Int? = null,
    val maxBpm: Int? = null,
    val config: SessionConfigSnapshot,
    val device: String = "watch",
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
) {
    fun toSessionConfig() = SessionConfig(
        upperBpm = upperBpm,
        lowerBpm = lowerBpm,
        targetCycles = targetCycles,
        fatigueRatio = fatigueRatio,
        minBaseline = minBaselineSec.seconds,
        highPhaseTimeout = highTimeoutSec.seconds,
        recoveryTimeout = recoveryTimeoutSec.seconds,
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
        )
    }
}
