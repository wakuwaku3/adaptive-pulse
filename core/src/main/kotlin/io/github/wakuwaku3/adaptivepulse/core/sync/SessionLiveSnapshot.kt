package io.github.wakuwaku3.adaptivepulse.core.sync

import kotlinx.serialization.Serializable

/**
 * セッション中のライブ状態スナップショット (watch → phone)。
 * Wearable Data Layer の `/session/live` に最新値を上書きで置き、
 * phone は最新スナップショットを購読する (再接続時の last-write-wins と相性が良い)。
 *
 * watch UI の [SessionUiState] と分けて DTO にしているのは、
 * watch UI 側の表現都合 (sealed interface / Compose の都合) と
 * 転送境界を切り離して将来の変更に強くするため。
 */
@Serializable
data class SessionLiveSnapshot(
    val schema: Int = 4,
    val updatedAtMs: Long,
    val phase: LivePhase,
    val bpm: Int?,
    val currentCycle: Int,
    val finalCycle: Int,
    val elapsedSec: Double,
    val cycleElapsedSec: Double,
    val phaseElapsedSec: Double,
    val upperBpm: Int,
    val lowerBpm: Int,
    val calories: Double? = null,
    /**
     * 3〜5 秒窓の median による現在の cadence (SPM)。
     * pace-metric note の単位流儀 (step = 片足踏み込み 1 回 → SPM)。
     * 拍動円の色判定 (current vs target) と "now" 数値表示に使う。
     */
    val currentCadenceSpm: Double? = null,
    /**
     * 高強度フェーズの**動的に追従する**目標 cadence (SPM)。
     * Day-1 は seed (130)、cycle 毎に target duration 窓 (45〜90s) を
     * 達成するよう制御ループで調整される (pace-metric note §ペースをどう決めるか)。
     */
    val targetCadenceHigh: Double = 0.0,
    /** 回復フェーズの動的目標 cadence (SPM)。Day-1 seed 65、duration 窓 30〜75s */
    val targetCadenceRecovery: Double = 0.0,
)

/** 表示フェーズ。WARM_UP は engine の HIGH_INTENSITY かつ measureStartedAt==null を別ラベル化 */
@Serializable
enum class LivePhase { WARM_UP, HIGH, RECOVERY, DONE }
