package io.github.wakuwaku3.adaptivepulse.core.cadence

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class AccelerometerCadenceEstimatorTest {

    @Test
    fun `empty sample set returns null`() {
        val e = AccelerometerCadenceEstimator()
        assertNull(e.spm(0.seconds))
    }

    @Test
    fun `flat signal yields null`() {
        val e = AccelerometerCadenceEstimator()
        for (i in 0 until 100) {
            e.add((i * 20).milliseconds, 0.0)
        }
        assertNull(e.spm(2.seconds))
    }

    @Test
    fun `detects 2_5Hz arm swing as 150 SPM`() {
        val e = AccelerometerCadenceEstimator()
        // 2.5 Hz の正弦波 = 高強度ペース域 (150 SPM)
        feed(e, freqHz = 2.5, amplitude = 5.0, durationMs = 5_000, sampleMs = 20)
        val spm = e.spm(5.seconds)
        assertNotNull(spm)
        assertTrue(spm in 140.0..160.0, "expected ~150 SPM, got $spm")
    }

    @Test
    fun `detects 1_2Hz recovery as ~72 SPM`() {
        val e = AccelerometerCadenceEstimator()
        feed(e, freqHz = 1.2, amplitude = 3.0, durationMs = 5_000, sampleMs = 20)
        val spm = e.spm(5.seconds)
        assertNotNull(spm)
        assertTrue(spm in 65.0..80.0, "expected ~72 SPM, got $spm")
    }

    @Test
    fun `noisy sub-threshold signal does not yield spurious peaks`() {
        val e = AccelerometerCadenceEstimator(noiseFloor = 0.5)
        // amplitude=0.1 はノイズフロア以下 → 検出しない
        feed(e, freqHz = 2.5, amplitude = 0.1, durationMs = 5_000, sampleMs = 20)
        assertNull(e.spm(5.seconds))
    }

    private fun feed(
        estimator: AccelerometerCadenceEstimator,
        freqHz: Double,
        amplitude: Double,
        durationMs: Int,
        sampleMs: Int,
    ) {
        val steps = durationMs / sampleMs
        for (i in 0..steps) {
            val tMs = (i * sampleMs).toLong()
            val tSec = tMs / 1000.0
            val v = amplitude * sin(2.0 * PI * freqHz * tSec)
            estimator.add(at = tMs.milliseconds, value = v)
        }
    }
}
