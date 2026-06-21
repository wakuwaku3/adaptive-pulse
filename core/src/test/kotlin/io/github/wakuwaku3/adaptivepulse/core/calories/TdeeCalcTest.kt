package io.github.wakuwaku3.adaptivepulse.core.calories

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TdeeCalcTest {

    private val day = 1_750_000_000_000L
    private fun min(n: Long) = n * 60L * 1000L

    @Test
    fun `basal が null かつ height があれば Mifflin-St Jeor で BMR を補う`() {
        val b = TdeeCalc.compute(
            basalKcal = null,
            weightKg = 91.0,
            heightCm = 175.0,
            ageYears = 39,
            steps = 0,
            hcSessions = emptyList(),
            appSessions = emptyList(),
        )
        // 10*91 + 6.25*175 - 5*39 + 5 = 1813.75
        assertEquals(1813.75, b.bmrKcal!!, 0.01)
        assertEquals(0.0, b.neatKcal!!, 0.01)
        assertEquals(0.0, b.exerciseExtraKcal!!, 0.01)
        assertEquals(1813.75, b.tdeeKcal!!, 0.01)
    }

    @Test
    fun `weight が無いと NEAT も extra も出さず BMR だけ返す`() {
        val b = TdeeCalc.compute(
            basalKcal = 1800.0,
            weightKg = null,
            heightCm = 175.0,
            ageYears = 39,
            steps = 10000,
            hcSessions = listOf(
                TdeeCalc.HcSession(day, day + min(60), ExerciseKind.BIKING_STATIONARY),
            ),
            appSessions = emptyList(),
        )
        assertNull(b.neatKcal)
        assertNull(b.exerciseExtraKcal)
        assertNull(b.tdeeKcal)
    }

    @Test
    fun `NEAT は steps × weight × 0_0005 で出る`() {
        val b = TdeeCalc.compute(
            basalKcal = 1783.0,
            weightKg = 91.0,
            heightCm = 175.0,
            ageYears = 39,
            steps = 10000,
            hcSessions = emptyList(),
            appSessions = emptyList(),
        )
        // 10000 × 91 × 0.0005 = 455
        assertEquals(455.0, b.neatKcal!!, 0.01)
        assertEquals(0.0, b.exerciseExtraKcal!!, 0.01)
        assertEquals(2238.0, b.tdeeKcal!!, 0.01)
    }

    @Test
    fun `自社 HIIT は 8 MET で上乗せ`() {
        val b = TdeeCalc.compute(
            basalKcal = 1783.0,
            weightKg = 91.0,
            heightCm = 175.0,
            ageYears = 39,
            steps = 0,
            hcSessions = emptyList(),
            appSessions = listOf(TdeeCalc.AppSession(startedAtMs = day, durationSec = 1800)), // 30 分
        )
        // (8-1) × 91 × 0.5 = 318.5
        assertEquals(318.5, b.exerciseExtraKcal!!, 0.01)
    }

    @Test
    fun `step covered な type (WALKING) は extra に加算しない`() {
        val b = TdeeCalc.compute(
            basalKcal = 1783.0,
            weightKg = 91.0,
            heightCm = 175.0,
            ageYears = 39,
            steps = 5000,
            hcSessions = listOf(
                TdeeCalc.HcSession(day, day + min(60), ExerciseKind.WALKING),
            ),
            appSessions = emptyList(),
        )
        // 歩数で吸収済 → extra は 0
        assertEquals(0.0, b.exerciseExtraKcal!!, 0.01)
    }

    @Test
    fun `BIKING_STATIONARY は MET 加算される`() {
        val b = TdeeCalc.compute(
            basalKcal = 1783.0,
            weightKg = 91.0,
            heightCm = 175.0,
            ageYears = 39,
            steps = 0,
            hcSessions = listOf(
                TdeeCalc.HcSession(day, day + min(60), ExerciseKind.BIKING_STATIONARY),
            ),
            appSessions = emptyList(),
        )
        // (7-1) × 91 × 1.0 = 546
        assertEquals(546.0, b.exerciseExtraKcal!!, 0.01)
    }

    @Test
    fun `自社 HIIT と時間重複した HC session は skip して二重計上を避ける`() {
        val hiitStart = day + min(60)
        val hiitDuration = 1800L // 30 分
        val app = TdeeCalc.AppSession(hiitStart, hiitDuration)
        // watch が同じ時間帯を ELLIPTICAL として書いた場合
        val hcOverlap = TdeeCalc.HcSession(hiitStart + min(5), hiitStart + min(25), ExerciseKind.ELLIPTICAL)
        // 独立した 1h ハード有酸素 (BIKING_STATIONARY) は別途加算される
        val hcIndependent = TdeeCalc.HcSession(day + min(180), day + min(240), ExerciseKind.BIKING_STATIONARY)

        val b = TdeeCalc.compute(
            basalKcal = 1783.0,
            weightKg = 91.0,
            heightCm = 175.0,
            ageYears = 39,
            steps = 0,
            hcSessions = listOf(hcOverlap, hcIndependent),
            appSessions = listOf(app),
        )
        // 期待: 自社 HIIT 30min × 7 × 91 × 0.5 = 318.5
        //     + HC BIKING_STATIONARY 60min × 6 × 91 × 1.0 = 546.0
        //     合計 864.5。ELLIPTICAL (重複) は除外
        assertEquals(864.5, b.exerciseExtraKcal!!, 0.01)
    }

    @Test
    fun `0619 シナリオ (BMR + 17666 歩 + HIIT 30min + 1h ハード有酸素) が妥当な範囲に入る`() {
        val b = TdeeCalc.compute(
            basalKcal = 1783.0,
            weightKg = 91.4,
            heightCm = 175.0,
            ageYears = 39,
            steps = 17666,
            hcSessions = listOf(
                TdeeCalc.HcSession(day, day + min(60), ExerciseKind.BIKING_STATIONARY),
            ),
            appSessions = listOf(
                TdeeCalc.AppSession(startedAtMs = day + min(120), durationSec = 1800),
            ),
        )
        // ざっくり: BMR 1783 + NEAT 807 + (HIIT 320 + Cardio 548) = 3458 前後
        // watch の 4832 (overcount) より小さく、PAL 1.9 程度 → 17k 歩 + 90 分激しい運動なら妥当
        val tdee = b.tdeeKcal!!
        assertTrue(tdee in 3200.0..3700.0, "tdee = $tdee は妥当範囲 3200-3700 を逸脱")
    }

    @Test
    fun `空の入力でも crash しない`() {
        val b = TdeeCalc.compute(
            basalKcal = null,
            weightKg = null,
            heightCm = null,
            ageYears = 39,
            steps = null,
            hcSessions = emptyList(),
            appSessions = emptyList(),
        )
        assertNull(b.bmrKcal)
        assertNull(b.tdeeKcal)
    }

    @Test
    fun `OTHER は 5 MET 扱い`() {
        val b = TdeeCalc.compute(
            basalKcal = 1783.0,
            weightKg = 91.0,
            heightCm = 175.0,
            ageYears = 39,
            steps = 0,
            hcSessions = listOf(
                TdeeCalc.HcSession(day, day + min(60), ExerciseKind.OTHER),
            ),
            appSessions = emptyList(),
        )
        // (5-1) × 91 × 1 = 364
        assertEquals(364.0, b.exerciseExtraKcal!!, 0.01)
        assertNotNull(b.tdeeKcal)
    }
}
