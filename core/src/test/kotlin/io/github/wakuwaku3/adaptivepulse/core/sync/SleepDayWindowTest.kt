package io.github.wakuwaku3.adaptivepulse.core.sync

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class SleepDayWindowTest {

    private val jst: ZoneId = ZoneId.of("Asia/Tokyo")

    @Test
    fun `日 D の窓は当日 18時から翌日 18時まで`() {
        val d = LocalDate.of(2026, 6, 21)
        assertEquals(ZonedDateTime.of(2026, 6, 21, 18, 0, 0, 0, jst), SleepDayWindow.startOf(d, jst))
        assertEquals(ZonedDateTime.of(2026, 6, 22, 18, 0, 0, 0, jst), SleepDayWindow.endOf(d, jst))
    }

    @Test
    fun `23時入眠は当日の睡眠`() {
        val start = ZonedDateTime.of(2026, 6, 21, 23, 0, 0, 0, jst)
        assertEquals(LocalDate.of(2026, 6, 21), SleepDayWindow.ownerDate(start))
    }

    @Test
    fun `深夜 1時入眠は前日の睡眠 - 主訴の bug ケース`() {
        // 6/22 01:00 に入眠 → 暦日では 6/22 だが、ユーザ主観では「6/21 の夜」
        val start = ZonedDateTime.of(2026, 6, 22, 1, 0, 0, 0, jst)
        assertEquals(LocalDate.of(2026, 6, 21), SleepDayWindow.ownerDate(start))
    }

    @Test
    fun `18時00分00秒ちょうどは当日扱い - 境界を inclusive にする`() {
        val start = ZonedDateTime.of(2026, 6, 22, 18, 0, 0, 0, jst)
        assertEquals(LocalDate.of(2026, 6, 22), SleepDayWindow.ownerDate(start))
    }

    @Test
    fun `17時59分59秒は前日扱い`() {
        val start = ZonedDateTime.of(2026, 6, 22, 17, 59, 59, 0, jst)
        assertEquals(LocalDate.of(2026, 6, 21), SleepDayWindow.ownerDate(start))
    }

    @Test
    fun `正午入眠は前日扱い - 夜勤の昼仮眠`() {
        val start = ZonedDateTime.of(2026, 6, 22, 12, 0, 0, 0, jst)
        assertEquals(LocalDate.of(2026, 6, 21), SleepDayWindow.ownerDate(start))
    }
}
