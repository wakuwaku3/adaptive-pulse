package io.github.wakuwaku3.adaptivepulse.core

import io.github.wakuwaku3.adaptivepulse.core.menu.MenuKind
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * 時間制メニューのステートマシン (docs/stock/requirements.md「メニューの 2 型」)。
 * 「帯に収めて時間を過ごす」だけの静かなエンジンで、疲労提案・タイムアウトは持たない
 * (時間で必ず終わるため)。
 *
 * 帯逸脱の通知は 1 回の逸脱につき 1 回だけ発火し、帯内に復帰した時点で再武装する
 * (上下対称)。境界をうろつくたびに振動が連射されるのを防ぐ。
 *
 * elapsed はこのメニューの開始からの経過時間 (単調増加) を渡す。
 */
class TimedEngine(
    val upperBpm: Int,
    val lowerBpm: Int?,
    val duration: Duration,
) {
    constructor(kind: MenuKind.Timed, amountMinutes: Int) : this(
        upperBpm = kind.upperBpm,
        lowerBpm = kind.lowerBpm,
        duration = amountMinutes.minutes,
    )

    var finished: Boolean = false
        private set

    private var aboveNotified = false
    private var belowNotified = false

    fun onHeartRate(bpm: Int, elapsed: Duration): SessionEvent? {
        if (finished) return null
        onTimePassed(elapsed)?.let { return it }
        if (bpm > upperBpm) {
            if (aboveNotified) return null
            aboveNotified = true
            return SessionEvent.AboveBand
        }
        aboveNotified = false
        val lower = lowerBpm ?: return null
        if (bpm < lower) {
            if (belowNotified) return null
            belowNotified = true
            return SessionEvent.BelowBand
        }
        belowNotified = false
        return null
    }

    /** 心拍サンプルが途絶えても時間経過で終われるよう、定期 tick からも呼べる */
    fun onTimePassed(elapsed: Duration): SessionEvent? {
        if (finished) return null
        if (elapsed < duration) return null
        finished = true
        return SessionEvent.SessionFinished
    }

    fun remaining(elapsed: Duration): Duration = (duration - elapsed).coerceAtLeast(Duration.ZERO)
}
