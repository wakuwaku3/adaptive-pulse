package io.github.wakuwaku3.adaptivepulse.core.cadence

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class TieredCadenceLockTest {

    @Test
    fun `no observations yields null tier`() {
        val lock = TieredCadenceLock()
        assertNull(lock.currentTier(0.seconds))
    }

    @Test
    fun `during discovery best available tier wins (can upgrade)`() {
        val lock = TieredCadenceLock(discoveryWindow = 15.seconds)
        // 加速度先行 → 一旦 T3 でも、後で T1 が出れば T1 に上げる
        lock.observe(CadenceTier.ACCELEROMETER, 2.seconds)
        assertEquals(CadenceTier.ACCELEROMETER, lock.currentTier(2.seconds))

        lock.observe(CadenceTier.STEPS_TOTAL_DELTA, 5.seconds)
        assertEquals(CadenceTier.STEPS_TOTAL_DELTA, lock.currentTier(5.seconds))

        lock.observe(CadenceTier.STEPS_PER_MINUTE, 8.seconds)
        assertEquals(CadenceTier.STEPS_PER_MINUTE, lock.currentTier(8.seconds))
        assertNull(lock.lockedTier)
    }

    @Test
    fun `locks at end of discovery window`() {
        val lock = TieredCadenceLock(discoveryWindow = 15.seconds)
        lock.observe(CadenceTier.STEPS_TOTAL_DELTA, 2.seconds)
        lock.observe(CadenceTier.ACCELEROMETER, 3.seconds)
        // 17s 時点: discovery 経過。T1 は来ていない → T2 (最良 fresh) で lock
        // fresh 維持のため T2 を継続観測
        lock.observe(CadenceTier.STEPS_TOTAL_DELTA, 14.seconds)
        assertEquals(CadenceTier.STEPS_TOTAL_DELTA, lock.currentTier(17.seconds))
        assertEquals(CadenceTier.STEPS_TOTAL_DELTA, lock.lockedTier)
    }

    @Test
    fun `locked tier sticks even if higher tier fires later`() {
        val lock = TieredCadenceLock(discoveryWindow = 10.seconds)
        lock.observe(CadenceTier.STEPS_TOTAL_DELTA, 2.seconds)
        // 12s で T2 lock
        lock.observe(CadenceTier.STEPS_TOTAL_DELTA, 12.seconds)
        assertEquals(CadenceTier.STEPS_TOTAL_DELTA, lock.currentTier(12.seconds))
        // 30s で T1 が出てきても固定された tier は変えない
        lock.observe(CadenceTier.STEPS_PER_MINUTE, 30.seconds)
        lock.observe(CadenceTier.STEPS_TOTAL_DELTA, 30.seconds)
        assertEquals(CadenceTier.STEPS_TOTAL_DELTA, lock.currentTier(30.seconds))
        assertEquals(CadenceTier.STEPS_TOTAL_DELTA, lock.lockedTier)
    }

    @Test
    fun `lock skipped when no tier is fresh at window end`() {
        val lock = TieredCadenceLock(discoveryWindow = 10.seconds)
        lock.observe(CadenceTier.ACCELEROMETER, 1.seconds)
        // 加速度 freshness = 3s. 12s では 11s 古い → 全 tier stale
        assertNull(lock.currentTier(12.seconds))
        assertNull(lock.lockedTier)
        // その後 T3 が再び observe されたら lock 確定 (discovery 経過済みなので即 lock)
        lock.observe(CadenceTier.ACCELEROMETER, 12.seconds)
        assertEquals(CadenceTier.ACCELEROMETER, lock.currentTier(12.seconds))
        assertEquals(CadenceTier.ACCELEROMETER, lock.lockedTier)
    }

    @Test
    fun `locked tier emits null when temporarily stale`() {
        val lock = TieredCadenceLock(discoveryWindow = 5.seconds)
        lock.observe(CadenceTier.STEPS_PER_MINUTE, 1.seconds)
        lock.observe(CadenceTier.STEPS_PER_MINUTE, 6.seconds)
        // 6s 時点で discovery 経過。T1 で lock
        assertEquals(CadenceTier.STEPS_PER_MINUTE, lock.currentTier(6.seconds))
        // 12s 時点: T1 freshness = 5s 超過 → null (lock は維持)
        assertNull(lock.currentTier(12.seconds))
        assertEquals(CadenceTier.STEPS_PER_MINUTE, lock.lockedTier)
        // 再び発火すれば復活
        lock.observe(CadenceTier.STEPS_PER_MINUTE, 13.seconds)
        assertEquals(CadenceTier.STEPS_PER_MINUTE, lock.currentTier(13.seconds))
    }
}
