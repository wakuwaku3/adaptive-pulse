package io.github.wakuwaku3.adaptivepulse.core.sync

import io.github.wakuwaku3.adaptivepulse.core.SessionSuggestion
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
    val schema: Int = 6,
    val updatedAtMs: Long,
    val phase: LivePhase,
    val bpm: Int?,
    val currentCycle: Int,
    val finalCycle: Int,
    val elapsedSec: Double,
    val cycleElapsedSec: Double,
    val phaseElapsedSec: Double,
    val upperBpm: Int,
    /** 現セグメントの帯下限。時間制で下限なしのとき null */
    val lowerBpm: Int?,
    val calories: Double? = null,
    /** 高強度フェーズの目標 cadence (SPM)。phone 回転体の tempo に使う。設定値そのまま */
    val targetCadenceHigh: Int = 0,
    /** 回復フェーズの目標 cadence (SPM)。同上 */
    val targetCadenceRecovery: Int = 0,
    /**
     * engine の最新行動提案 (ペース緩めるか中断するか)。null = 出ていない。
     * phone ライブ画面でユーザに判断を委ねる UI として表示する (FB 2026-06-24)。
     */
    val suggestion: SessionSuggestion? = null,
    /** 実行中のメニュー名 (プログラム内の現在地表示) */
    val menuName: String = "",
    /** 実行中メニューの位置 (0 始まり) と総数。単体メニューは 0/1 */
    val menuIndex: Int = 0,
    val menuCount: Int = 1,
    /** 時間制セグメント実行中のみ non-null: 目標時間と経過 (残り = target - elapsed) */
    val timedTargetSec: Double? = null,
    val timedElapsedSec: Double? = null,
)

/**
 * 表示フェーズ。WARM_UP は engine の HIGH_INTENSITY かつ measureStartedAt==null を別ラベル化。
 * TIMED は時間制メニューの実行中 (帯に収めて時間を過ごす区間)。
 */
@Serializable
enum class LivePhase { WARM_UP, HIGH, RECOVERY, DONE, TIMED }
