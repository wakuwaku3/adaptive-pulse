package io.github.wakuwaku3.adaptivepulse.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class TimedEngineTest {

    private fun engine(upper: Int = 135, lower: Int? = 115) =
        TimedEngine(upperBpm = upper, lowerBpm = lower, duration = 30.minutes)

    @Test
    fun `帯内にいる間は何も発火しない`() {
        val e = engine()
        assertNull(e.onHeartRate(120, 10.seconds))
        assertNull(e.onHeartRate(135, 20.seconds)) // 境界は帯内
        assertNull(e.onHeartRate(115, 30.seconds))
    }

    @Test
    fun `帯上逸脱は 1 回の逸脱につき 1 回だけ発火し、復帰で再武装する`() {
        val e = engine()
        assertEquals(SessionEvent.AboveBand, e.onHeartRate(140, 10.seconds))
        assertNull(e.onHeartRate(145, 11.seconds)) // 出っぱなしでは再発火しない
        assertNull(e.onHeartRate(150, 12.seconds))
        assertNull(e.onHeartRate(130, 20.seconds)) // 帯内復帰 = 再武装
        assertEquals(SessionEvent.AboveBand, e.onHeartRate(136, 30.seconds)) // 再逸脱で再度発火
    }

    @Test
    fun `帯下逸脱も対称に 1 逸脱 1 回で、復帰で再武装する`() {
        val e = engine()
        assertEquals(SessionEvent.BelowBand, e.onHeartRate(110, 10.seconds))
        assertNull(e.onHeartRate(105, 11.seconds))
        assertNull(e.onHeartRate(120, 20.seconds))
        assertEquals(SessionEvent.BelowBand, e.onHeartRate(114, 30.seconds))
    }

    @Test
    fun `上逸脱から下逸脱へ直行しても両方 1 回ずつ発火する`() {
        val e = engine()
        assertEquals(SessionEvent.AboveBand, e.onHeartRate(140, 10.seconds))
        assertEquals(SessionEvent.BelowBand, e.onHeartRate(110, 20.seconds))
        assertEquals(SessionEvent.AboveBand, e.onHeartRate(140, 30.seconds))
    }

    @Test
    fun `下限なし (ウォームアップ型) は低心拍で発火しない`() {
        val e = engine(upper = 116, lower = null)
        assertNull(e.onHeartRate(70, 10.seconds))
        assertNull(e.onHeartRate(90, 20.seconds))
        assertEquals(SessionEvent.AboveBand, e.onHeartRate(120, 30.seconds))
    }

    @Test
    fun `時間経過で終了し、以後は何も発火しない`() {
        val e = engine()
        assertFalse(e.finished)
        assertNull(e.onTimePassed(29.minutes))
        assertEquals(SessionEvent.SessionFinished, e.onTimePassed(30.minutes))
        assertTrue(e.finished)
        assertNull(e.onTimePassed(31.minutes))
        assertNull(e.onHeartRate(150, 31.minutes))
    }

    @Test
    fun `心拍サンプル経由でも時間経過で終了する`() {
        val e = engine()
        assertEquals(SessionEvent.SessionFinished, e.onHeartRate(120, 30.minutes + 1.seconds))
        assertTrue(e.finished)
    }

    @Test
    fun `remaining は残り時間を返し、超過後は 0 に張り付く`() {
        val e = engine()
        assertEquals(20.minutes, e.remaining(10.minutes))
        assertEquals(kotlin.time.Duration.ZERO, e.remaining(31.minutes))
    }
}
