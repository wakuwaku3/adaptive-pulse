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
    val schema: Int = 2,
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
    /** 3〜5 秒窓の median による現在のステップ毎秒 (Hz)。pace-metric note の単位流儀 */
    val currentRps: Double? = null,
    /** 現フェーズの目標 step/min。phone でペース調整・拍動円 tempo に使う (pace-metric Phase B) */
    val targetSpm: Int = 0,
)

/** 表示フェーズ。WARM_UP は engine の HIGH_INTENSITY かつ measureStartedAt==null を別ラベル化 */
@Serializable
enum class LivePhase { WARM_UP, HIGH, RECOVERY, DONE }
