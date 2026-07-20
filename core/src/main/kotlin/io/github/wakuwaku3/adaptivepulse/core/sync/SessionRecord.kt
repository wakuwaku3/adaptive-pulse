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
    val schema: Int = 4,
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
     * 実行したプラン (メニューの並びとセグメントごとの実績)。
     * プログラム 1 実行 = 1 セッション (schema 4)。旧レコード (schema 3 以前) は null。
     */
    val plan: SessionPlanSnapshot? = null,
)

/** 実行したプランの記録。programId = null はメニュー単体の実行 */
@Serializable
data class SessionPlanSnapshot(
    val programId: String?,
    val name: String,
    val segments: List<SegmentSnapshot>,
)

/** プラン内 1 メニュー実行分の記録 (実行時点のメニュー定義と実績を自己完結で残す) */
@Serializable
data class SegmentSnapshot(
    val menuId: String,
    val menuName: String,
    /** "interval" (心拍トリガー型) | "timed" (時間制) */
    val type: String,
    val upperBpm: Int,
    val lowerBpm: Int?,
    /** 量 (心拍トリガー型は本数 / 時間制は分数)。上書き解決済み */
    val plannedAmount: Int,
    /** 完了本数 (心拍トリガー型のみ) */
    val completedCycles: Int? = null,
    val elapsedSec: Double,
    val highDurationsSec: List<Double> = emptyList(),
    val recoveryDurationsSec: List<Double> = emptyList(),
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
    val targetCadenceHigh: Int = 130,
    val targetCadenceRecovery: Int = 90,
    val heightCm: Int? = null,
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
            targetCadenceHigh = config.targetCadenceHigh,
            targetCadenceRecovery = config.targetCadenceRecovery,
            heightCm = config.heightCm,
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
    val targetCadenceHigh: Int = 130,
    val targetCadenceRecovery: Int = 90,
    val heightCm: Int? = null,
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
        targetCadenceHigh = targetCadenceHigh,
        targetCadenceRecovery = targetCadenceRecovery,
        heightCm = heightCm,
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
            targetCadenceHigh = config.targetCadenceHigh,
            targetCadenceRecovery = config.targetCadenceRecovery,
            heightCm = config.heightCm,
        )
    }
}
