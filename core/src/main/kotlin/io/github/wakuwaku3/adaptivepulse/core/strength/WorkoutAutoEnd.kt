package io.github.wakuwaku3.adaptivepulse.core.strength

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * Finish 押し忘れの自動終了判定。バックグラウンドタイマーは持たず、
 * (1) 画面 open (2) 有酸素ライブ受信 (3) 完了セッション受信 の 3 点から遅延評価される。
 * データはセット入力時点で保存済みなので、終了はあくまで状態管理でありリアルタイム性を要しない。
 */
object WorkoutAutoEnd {

    val TIMEOUT: Duration = 1.hours

    /**
     * 自動終了すべきなら終了済みレコードを返す。null = 進行中のまま。
     * endedAtMs は発見時刻ではなく lastInputAtMs (実際の運動終了に近い値) を使う。
     *
     * @param cardioStartedAtMs 有酸素セッションの開始時刻。workout 開始前の値は
     *   過去セッションの残骸なので無視する。
     */
    fun evaluate(workout: WorkoutRecord, nowMs: Long, cardioStartedAtMs: Long? = null): WorkoutRecord? {
        if (workout.endedAtMs != null) return null
        if (cardioStartedAtMs != null && cardioStartedAtMs >= workout.startedAtMs) {
            return workout.copy(endedAtMs = workout.lastInputAtMs, endReason = WorkoutEndReason.CARDIO)
        }
        if (nowMs - workout.lastInputAtMs >= TIMEOUT.inWholeMilliseconds) {
            return workout.copy(endedAtMs = workout.lastInputAtMs, endReason = WorkoutEndReason.TIMEOUT)
        }
        return null
    }
}
