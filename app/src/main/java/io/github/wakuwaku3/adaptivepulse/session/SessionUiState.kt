package io.github.wakuwaku3.adaptivepulse.session

import io.github.wakuwaku3.adaptivepulse.core.Phase
import kotlin.time.Duration

sealed interface SessionUiState {
    data object Idle : SessionUiState

    data class Running(
        val bpm: Int?,
        val phase: Phase,
        val isWarmingUp: Boolean,
        val currentCycle: Int,
        val finalCycle: Int,
        /** セッション開始からの経過時間 */
        val elapsed: Duration,
        /** 現サイクル (= 現在の高強度フェーズ開始) からの経過時間 */
        val cycleElapsed: Duration,
        /** 現フェーズ (高強度 or 回復) 開始からの経過時間 */
        val phaseElapsed: Duration,
        val calories: Double?,
        /** セッション中に動かしうる現在の上限閾値 */
        val upperBpm: Int,
        /** セッション中に動かしうる現在の下限閾値 */
        val lowerBpm: Int,
        /** 3〜5 秒窓の median のステップ毎秒 (Hz)。pace-metric Phase A の計測のみ用途。watch UI は表示しないが phone live screen に流す */
        val currentRps: Double? = null,
        /** 現フェーズの目標 step/min。phone のペース調整・拍動円 tempo の入力 (pace-metric Phase B) */
        val targetSpm: Int = 0,
    ) : SessionUiState

    data class Finished(
        val cycles: Int,
        val elapsed: Duration,
        val calories: Double?,
        val zoneRatio: Double?,
    ) : SessionUiState
}
