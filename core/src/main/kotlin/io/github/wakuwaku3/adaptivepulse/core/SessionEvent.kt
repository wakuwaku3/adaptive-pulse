package io.github.wakuwaku3.adaptivepulse.core

/**
 * フェーズ遷移イベント。1 回の遷移につき必ず 1 つだけ発火する
 * (アプリ側がイベント→振動パターンを 1:1 で対応づけるため、重複発火させない)。
 */
sealed interface SessionEvent {
    /** 高強度→回復 (上限閾値到達) */
    data object EnterRecovery : SessionEvent

    /** 回復→高強度 (下限閾値下回り、サイクル完了) */
    data object EnterHighIntensity : SessionEvent

    /** 疲労検知: 回復へ遷移しつつ現サイクルを最終サイクル化 */
    data object FatigueBrake : SessionEvent

    /** 最終サイクルの回復完了。セッション終了 */
    data object SessionFinished : SessionEvent
}
