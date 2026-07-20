package io.github.wakuwaku3.adaptivepulse.core.menu

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class DurationEstimateTest {

    private val walking = Menu("w", "Walking", MenuKind.Timed(136, 116, 30))
    private val interval = Menu("i", "4x4", MenuKind.Interval(163, 127, 4))

    @Test
    fun `時間制は分数そのまま`() {
        assertEquals(30.minutes, DurationEstimate.ofSegment(walking, 30))
        assertEquals(20.minutes, DurationEstimate.ofSegment(walking, 20))
    }

    @Test
    fun `心拍トリガー型は帯幅から上り+下りを本数分見積もる`() {
        // 帯幅 36bpm: 上り 36*4.5=162 秒、下り 60 + (36-27)/10*60 = 114 秒 → 276 秒/本
        assertEquals((276 * 4).seconds, DurationEstimate.ofSegment(interval, 4))
    }

    @Test
    fun `狭い帯 (27bpm 以下) の下りは 1 分以内で線形`() {
        val narrow = Menu("n", "Narrow", MenuKind.Interval(145, 127, 5))
        // 帯幅 18bpm: 上り 81 秒、下り 60*18/27 = 40 秒 → 121 秒/本
        assertEquals((121 * 5).seconds, DurationEstimate.ofSegment(narrow, 5))
    }

    @Test
    fun `プラン合計はセグメントの和`() {
        val plan = SessionPlan(
            name = "Test",
            programId = null,
            segments = listOf(
                PlannedSegment(walking, 5),
                PlannedSegment(interval, 2),
            ),
        )
        assertEquals(5.minutes + (276 * 2).seconds, DurationEstimate.ofPlan(plan))
    }
}
