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
    fun `discovery が未開始なら observe しても currentTier は null (= warm-up 中)`() {
        val lock = TieredCadenceLock(discoveryWindow = 15.seconds)
        // 装着〜start タップで歩行が拾われるが startDiscovery 前 → tier 確定させない
        lock.observe(CadenceTier.STEPS_PER_MINUTE, 1.seconds)
        lock.observe(CadenceTier.STEPS_PER_MINUTE, 4.seconds)
        assertNull(lock.currentTier(5.seconds))
        assertNull(lock.lockedTier)
    }

    @Test
    fun `discovery 中は best available tier が勝つ (upgrade 可)`() {
        val lock = TieredCadenceLock(discoveryWindow = 15.seconds)
        lock.startDiscovery(0.seconds)
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
    fun `discovery 窓は startDiscovery を基点に測る`() {
        val lock = TieredCadenceLock(discoveryWindow = 15.seconds)
        // 装着直後に歩行で tier 1 が拾われる
        lock.observe(CadenceTier.STEPS_PER_MINUTE, 1.seconds)
        // 走り始めは 20 秒後
        lock.startDiscovery(20.seconds)
        // 20s で全 tier stale (tier 1 freshness 5s, 過去 emit 1s から 19s 経過) → null
        // observe を更新すれば fresh になる
        lock.observe(CadenceTier.STEPS_PER_MINUTE, 22.seconds)
        assertEquals(CadenceTier.STEPS_PER_MINUTE, lock.currentTier(22.seconds))
        assertNull(lock.lockedTier)

        // 35s 時点 (= startDiscovery + 15s 経過) で確定
        lock.observe(CadenceTier.STEPS_PER_MINUTE, 35.seconds)
        assertEquals(CadenceTier.STEPS_PER_MINUTE, lock.currentTier(35.seconds))
        assertEquals(CadenceTier.STEPS_PER_MINUTE, lock.lockedTier)
    }

    @Test
    fun `locked tier sticks even if higher tier fires later`() {
        val lock = TieredCadenceLock(discoveryWindow = 10.seconds)
        lock.startDiscovery(0.seconds)
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
        lock.startDiscovery(0.seconds)
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
        lock.startDiscovery(0.seconds)
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

    @Test
    fun `startDiscovery の重複呼び出しは無視される`() {
        val lock = TieredCadenceLock(discoveryWindow = 10.seconds)
        lock.startDiscovery(0.seconds)
        lock.observe(CadenceTier.STEPS_PER_MINUTE, 1.seconds)
        // 5 秒後に再度 startDiscovery されても基点は 0s のまま
        lock.startDiscovery(5.seconds)
        lock.observe(CadenceTier.STEPS_PER_MINUTE, 11.seconds)
        // 11s = 最初の startDiscovery から 11s 経過 (>= 10s) → lock 成立
        assertEquals(CadenceTier.STEPS_PER_MINUTE, lock.currentTier(11.seconds))
        assertEquals(CadenceTier.STEPS_PER_MINUTE, lock.lockedTier)
    }

    @Test
    fun `デフォルトの discovery 窓は 30 秒`() {
        val lock = TieredCadenceLock()
        lock.startDiscovery(0.seconds)
        lock.observe(CadenceTier.STEPS_PER_MINUTE, 1.seconds)
        // 29s では確定しない
        lock.observe(CadenceTier.STEPS_PER_MINUTE, 29.seconds)
        assertEquals(CadenceTier.STEPS_PER_MINUTE, lock.currentTier(29.seconds))
        assertNull(lock.lockedTier)
        // 30s でロック確定
        lock.observe(CadenceTier.STEPS_PER_MINUTE, 30.seconds)
        assertEquals(CadenceTier.STEPS_PER_MINUTE, lock.currentTier(30.seconds))
        assertEquals(CadenceTier.STEPS_PER_MINUTE, lock.lockedTier)
    }
}
