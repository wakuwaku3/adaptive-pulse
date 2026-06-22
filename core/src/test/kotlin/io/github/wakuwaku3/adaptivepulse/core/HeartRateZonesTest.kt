package io.github.wakuwaku3.adaptivepulse.core

import kotlin.test.Test
import kotlin.test.assertEquals

class HeartRateZonesTest {

    @Test
    fun `Tanaka 式 - 年齢から最大心拍を推定し四捨五入で返す`() {
        assertEquals(194, HeartRateZones.tanakaMaxHr(20))  // 208 - 14 = 194
        assertEquals(181, HeartRateZones.tanakaMaxHr(39))  // 208 - 27.3 = 180.7 → 181
        assertEquals(173, HeartRateZones.tanakaMaxHr(50))  // 208 - 35 = 173
    }

    @Test
    fun `Karvonen 式 - 安静時心拍 + 強度×心拍予備能 を四捨五入で返す`() {
        // age=39, restHR=60 → maxHR=181, HRR=121
        // 77% HRR: 60 + 0.77×121 = 60 + 93.17 = 153.17 → 153
        // 60% HRR: 60 + 0.60×121 = 60 + 72.60 = 132.60 → 133
        assertEquals(153, HeartRateZones.karvonen(39, 60, 0.77))
        assertEquals(133, HeartRateZones.karvonen(39, 60, 0.60))
    }

    @Test
    fun `デフォルト強度 - 上限 077 (HR-anchored で届く閾値), 下限 060 (HRR の sweet spot)`() {
        assertEquals(0.77, HeartRateZones.DEFAULT_UPPER_INTENSITY)
        assertEquals(0.60, HeartRateZones.DEFAULT_LOWER_INTENSITY)
    }

    @Test
    fun `デフォルト導出 - 上限と下限の helper が定義済み強度を使う`() {
        assertEquals(HeartRateZones.karvonen(39, 60, HeartRateZones.DEFAULT_UPPER_INTENSITY),
            HeartRateZones.defaultUpperBpm(39, 60))
        assertEquals(HeartRateZones.karvonen(39, 60, HeartRateZones.DEFAULT_LOWER_INTENSITY),
            HeartRateZones.defaultLowerBpm(39, 60))
    }

    @Test
    fun `Karvonen は安静時心拍の差を反映する (同年齢で restHR が違うとゾーンも変わる)`() {
        // %HRmax (単純割合) と違い、Karvonen は安静時心拍を底上げに使うため、
        // 同じ %HRR でも restHR ごとに目標 bpm が変わる。これが Karvonen 採用の動機。
        val withLowRest = HeartRateZones.karvonen(39, 50, 0.77)
        val withHighRest = HeartRateZones.karvonen(39, 70, 0.77)
        kotlin.test.assertNotEquals(withLowRest, withHighRest)
    }

    @Test
    fun `SessionConfig のデフォルト upperBpm lowerBpm が Karvonen 由来になる`() {
        val config = SessionConfig()
        assertEquals(HeartRateZones.defaultUpperBpm(config.ageYears, config.restingBpm), config.upperBpm)
        assertEquals(HeartRateZones.defaultLowerBpm(config.ageYears, config.restingBpm), config.lowerBpm)
    }
}
