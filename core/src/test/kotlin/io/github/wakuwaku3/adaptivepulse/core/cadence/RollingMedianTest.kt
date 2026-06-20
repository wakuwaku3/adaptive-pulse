package io.github.wakuwaku3.adaptivepulse.core.cadence

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class RollingMedianTest {

    @Test
    fun `empty window returns null`() {
        val m = RollingMedian(window = 5.seconds)
        assertNull(m.median(0.seconds))
    }

    @Test
    fun `odd count returns middle`() {
        val m = RollingMedian(window = 5.seconds)
        m.add(0.seconds, 1.0)
        m.add(1.seconds, 5.0)
        m.add(2.seconds, 3.0)
        assertEquals(3.0, m.median(2.seconds))
    }

    @Test
    fun `even count returns average of middles`() {
        val m = RollingMedian(window = 5.seconds)
        m.add(0.seconds, 1.0)
        m.add(1.seconds, 2.0)
        m.add(2.seconds, 3.0)
        m.add(3.seconds, 4.0)
        assertEquals(2.5, m.median(3.seconds))
    }

    @Test
    fun `samples outside window are evicted`() {
        val m = RollingMedian(window = 3.seconds)
        m.add(0.seconds, 100.0) // 古い外れ値: 窓外で落ちる
        m.add(4.seconds, 1.0)
        m.add(5.seconds, 2.0)
        m.add(6.seconds, 3.0)
        // now=6s, window=3s → 4s以前は落ちる (4,5,6 のみ残る)
        assertEquals(2.0, m.median(6.seconds))
    }

    @Test
    fun `noisy samples are smoothed`() {
        val m = RollingMedian(window = 5.seconds)
        // 真値 2.4 にスパイクが乗ったケース
        listOf(2.4, 2.3, 9.9, 2.5, 2.4).forEachIndexed { i, v -> m.add(i.seconds, v) }
        assertEquals(2.4, m.median(4.seconds))
    }
}
