package io.github.wakuwaku3.adaptivepulse.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class HrSampleSeriesTest {

    @Test
    fun `サンプルを 1 秒グリッドに並べ、欠落秒は null になる`() {
        val series = HrSampleSeries()
        series.record(0.seconds, 90)
        series.record(1.seconds, 95)
        // 2 秒目はセンサー欠落
        series.record(3.seconds, 105)
        assertEquals(listOf(90, 95, null, 105), series.toBpmBySecond())
    }

    @Test
    fun `同一秒に複数サンプルが来たら最後の値で上書きする`() {
        val series = HrSampleSeries()
        series.record(1_200.milliseconds, 100)
        series.record(1_900.milliseconds, 104)
        assertEquals(listOf(null, 104), series.toBpmBySecond())
    }

    @Test
    fun `サンプルが 1 つも無ければ空リスト`() {
        assertEquals(emptyList(), HrSampleSeries().toBpmBySecond())
    }

    @Test
    fun `開始直後のサンプルだけでも index 0 に入る`() {
        val series = HrSampleSeries()
        series.record(300.milliseconds, 88)
        assertEquals(listOf(88), series.toBpmBySecond())
    }
}
