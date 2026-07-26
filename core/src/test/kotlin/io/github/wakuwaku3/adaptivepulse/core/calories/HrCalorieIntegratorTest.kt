package io.github.wakuwaku3.adaptivepulse.core.calories

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.minutes

class HrCalorieIntegratorTest {

    // Keytel 2005 (男性・VO2max 無し): (-55.0969 + 0.6309*HR + 0.1988*W + 0.2017*A) / 4.184
    private fun rateKcalPerMin(bpm: Double, weightKg: Double = 91.0, ageYears: Int = 39) =
        (-55.0969 + 0.6309 * bpm + 0.1988 * weightKg + 0.2017 * ageYears) / 4.184

    @Test
    fun `一定心拍を 1Hz で 1 分積算すると回帰式の毎分値に一致する`() {
        val integrator = HrCalorieIntegrator(weightKg = 91.0, ageYears = 39)
        (0..60).forEach { sec -> integrator.onSample(sec.seconds, 140) }
        assertEquals(rateKcalPerMin(140.0), integrator.totalKcal, 0.001)
    }

    @Test
    fun `心拍が高いほど同じ時間での積算が大きい (心拍連動)`() {
        val low = HrCalorieIntegrator(weightKg = 91.0, ageYears = 39)
        val high = HrCalorieIntegrator(weightKg = 91.0, ageYears = 39)
        (0..60).forEach { sec ->
            low.onSample(sec.seconds, 110)
            high.onSample(sec.seconds, 165)
        }
        assertEquals(rateKcalPerMin(110.0), low.totalKcal, 0.001)
        assertEquals(rateKcalPerMin(165.0), high.totalKcal, 0.001)
    }

    @Test
    fun `サンプル間は前後心拍の平均で積分する`() {
        val integrator = HrCalorieIntegrator(weightKg = 91.0, ageYears = 39)
        integrator.onSample(0.seconds, 120)
        integrator.onSample(60.seconds, 160)
        // 60 秒ギャップは MAX_SAMPLE_GAP (5 秒) に丸められる
        assertEquals(rateKcalPerMin(140.0) * (5.0 / 60.0), integrator.totalKcal, 0.001)
    }

    @Test
    fun `回帰式が負になる低心拍では 0 として積算する`() {
        val integrator = HrCalorieIntegrator(weightKg = 50.0, ageYears = 20)
        (0..120).forEach { sec -> integrator.onSample(sec.seconds, 50) }
        assertEquals(0.0, integrator.totalKcal, 0.0)
    }

    @Test
    fun `最初のサンプルだけでは積算しない`() {
        val integrator = HrCalorieIntegrator(weightKg = 91.0, ageYears = 39)
        integrator.onSample(3.minutes, 150)
        assertEquals(0.0, integrator.totalKcal, 0.0)
    }
}
