package io.github.wakuwaku3.adaptivepulse.core.strength

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WorkoutAutoEndTest {

    private val timeoutMs = WorkoutAutoEnd.TIMEOUT.inWholeMilliseconds

    private fun inProgress(startedAtMs: Long = 1000, lastInputAtMs: Long = 5000) =
        startWorkout(Gym(id = "gym-1", name = "Main Gym"), id = "w", nowMs = startedAtMs)
            .copy(lastInputAtMs = lastInputAtMs)

    @Test
    fun `タイムアウト境界 (TIMEOUT 未満なら継続、以上なら終了)`() {
        val w = inProgress(lastInputAtMs = 5000)
        assertNull(WorkoutAutoEnd.evaluate(w, nowMs = 5000 + timeoutMs - 1))
        val ended = WorkoutAutoEnd.evaluate(w, nowMs = 5000 + timeoutMs)!!
        assertEquals(WorkoutEndReason.TIMEOUT, ended.endReason)
    }

    @Test
    fun `自動終了の endedAtMs は発見時刻ではなく lastInputAtMs`() {
        val w = inProgress(lastInputAtMs = 5000)
        val ended = WorkoutAutoEnd.evaluate(w, nowMs = 5000 + timeoutMs * 3)!!
        assertEquals(5000, ended.endedAtMs)
    }

    @Test
    fun `有酸素開始は経過時間に関係なく終了させる`() {
        val w = inProgress(startedAtMs = 1000, lastInputAtMs = 5000)
        val ended = WorkoutAutoEnd.evaluate(w, nowMs = 6000, cardioStartedAtMs = 5500)!!
        assertEquals(WorkoutEndReason.CARDIO, ended.endReason)
        assertEquals(5000, ended.endedAtMs)
    }

    @Test
    fun `workout 開始前の有酸素開始時刻は過去セッションの残骸として無視する`() {
        val w = inProgress(startedAtMs = 1000, lastInputAtMs = 5000)
        assertNull(WorkoutAutoEnd.evaluate(w, nowMs = 6000, cardioStartedAtMs = 999))
    }

    @Test
    fun `終了済み workout には何もしない`() {
        val w = inProgress().finished(nowMs = 6000)
        assertNull(WorkoutAutoEnd.evaluate(w, nowMs = 6000 + timeoutMs * 2, cardioStartedAtMs = 7000))
    }
}
