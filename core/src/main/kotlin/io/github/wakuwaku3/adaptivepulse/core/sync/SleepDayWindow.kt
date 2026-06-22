package io.github.wakuwaku3.adaptivepulse.core.sync

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 「日 D の睡眠 = [D 18:00, (D+1) 18:00) に入眠した SleepSession の合計」として暦日にひも付ける.
 *
 * 暦日 00:00 でカットすると、深夜 1:00 などに入眠した日が「翌日の睡眠」扱いになり
 * 主観と食い違う。早寝も深夜入眠も同じ「夜」として 1 日にまとめるため、夕方 18:00 で日付境界を切る.
 *
 * 18:00 より早い時刻に入った仮眠 (例: 17:30 就寝) は前日の睡眠扱いになるが、本格睡眠開始が
 * 18:00 より早いケースは想定外として許容する (必要になったら境界を Settings に出す).
 */
object SleepDayWindow {
    val CUTOFF: LocalTime = LocalTime.of(18, 0)

    fun startOf(date: LocalDate, zone: ZoneId): ZonedDateTime =
        date.atTime(CUTOFF).atZone(zone)

    fun endOf(date: LocalDate, zone: ZoneId): ZonedDateTime =
        date.plusDays(1).atTime(CUTOFF).atZone(zone)

    /** 与えられた入眠時刻が「どの暦日の睡眠か」を返す. */
    fun ownerDate(startTime: ZonedDateTime): LocalDate =
        if (startTime.toLocalTime().isBefore(CUTOFF)) {
            startTime.toLocalDate().minusDays(1)
        } else {
            startTime.toLocalDate()
        }
}
