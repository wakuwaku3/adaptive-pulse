package io.github.wakuwaku3.adaptivepulse.session

import io.github.wakuwaku3.adaptivepulse.core.SessionSuggestion
import io.github.wakuwaku3.adaptivepulse.core.sync.LivePhase
import kotlin.time.Duration

sealed interface SessionUiState {
    data object Idle : SessionUiState

    data class Running(
        val bpm: Int?,
        val phase: LivePhase,
        /** 現メニュー内の完了/目標本数 (心拍トリガー型)。時間制は 0/0 */
        val currentCycle: Int,
        val finalCycle: Int,
        /** セッション開始からの経過時間 */
        val elapsed: Duration,
        /** 現サイクル (= 現在の高強度フェーズ開始) からの経過時間。時間制は現メニュー経過 */
        val cycleElapsed: Duration,
        /** 現フェーズ (高強度 or 回復) 開始からの経過時間。時間制は現メニュー経過 */
        val phaseElapsed: Duration,
        val calories: Double?,
        /** 現メニューが見ている帯の上限 (心拍トリガー型はナッジ済みの現在値) */
        val upperBpm: Int,
        /** 帯の下限。時間制で下限なしのとき null */
        val lowerBpm: Int?,
        /** 高強度の目標 cadence (SPM)。phone 回転体の tempo に使う。時間制はメニューの値 */
        val targetCadenceHigh: Int = 0,
        /** 回復の目標 cadence (SPM)。同上 */
        val targetCadenceRecovery: Int = 0,
        /** engine が直近で出した行動提案。watch では未表示、phone ライブ画面で読ませる想定 */
        val suggestion: SessionSuggestion? = null,
        /** 実行中のメニュー名とプログラム内の現在地 (単体メニューは 0/1) */
        val menuName: String = "",
        val menuIndex: Int = 0,
        val menuCount: Int = 1,
        /** 時間制メニュー実行中のみ non-null: 目標時間 (残り = timedTarget - phaseElapsed) */
        val timedTarget: Duration? = null,
    ) : SessionUiState

    data class Finished(
        val cycles: Int,
        val elapsed: Duration,
        val calories: Double?,
        val zoneRatio: Double?,
        /** Finished 画面でも直近の提案を読み返せるよう保持する */
        val suggestion: SessionSuggestion? = null,
    ) : SessionUiState
}
