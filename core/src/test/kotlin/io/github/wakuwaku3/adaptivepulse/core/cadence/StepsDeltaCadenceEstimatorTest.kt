package io.github.wakuwaku3.adaptivepulse.core.cadence

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class StepsDeltaCadenceEstimatorTest {

    @Test
    fun `single point cannot estimate`() {
        val e = StepsDeltaCadenceEstimator()
        assertNull(e.update(0.seconds, 0))
    }

    @Test
    fun `short span returns null until minSpan reached`() {
        val e = StepsDeltaCadenceEstimator(minSpan = 3.seconds)
        e.update(0.seconds, 0)
        assertNull(e.update(1.seconds, 2))
        assertNull(e.update(2.seconds, 4))
        // 3 秒に到達したら推定が出る (2 step/sec = 120 SPM)
        val spm = e.update(3.seconds, 6)
        assertNotNull(spm)
        assertEquals(120.0, spm, 0.01)
    }

    @Test
    fun `constant cadence over window`() {
        val e = StepsDeltaCadenceEstimator()
        // 2.5 step/sec = 150 SPM
        for (i in 0..10) {
            e.update((i.toLong()).seconds, (i * 25 / 10).toLong())
        }
        val spm = e.spm(10.seconds)
        assertNotNull(spm)
        assertTrue(spm in 145.0..155.0, "got $spm")
    }

    @Test
    fun `cumulative regression resets history`() {
        val e = StepsDeltaCadenceEstimator()
        e.update(0.seconds, 100)
        e.update(3.seconds, 106)
        // 機器再接続等で累積値が後退 → 履歴クリア、推定不可
        assertNull(e.update(4.seconds, 1))
        // 再び増え始めれば minSpan 経過後に推定可能
        assertNull(e.update(5.seconds, 3))
        val spm = e.update(7.seconds, 7)
        assertNotNull(spm)
        // 6 step / 3 sec = 120 SPM
        assertEquals(120.0, spm, 0.01)
    }

    @Test
    fun `out-of-window points are evicted`() {
        val e = StepsDeltaCadenceEstimator(window = 5.seconds, minSpan = 2.seconds)
        e.update(0.seconds, 0)
        e.update(6.seconds, 6)
        e.update(8.seconds, 10)
        // window=5s → 0s の点は落ち、6s と 8s の差で算出 (4 step / 2 sec = 120 SPM)
        val spm = e.spm(8.seconds)
        assertNotNull(spm)
        assertEquals(120.0, spm, 0.01)
    }
}
