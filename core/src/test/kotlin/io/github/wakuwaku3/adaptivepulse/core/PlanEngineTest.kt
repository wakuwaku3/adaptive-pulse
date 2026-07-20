package io.github.wakuwaku3.adaptivepulse.core

import io.github.wakuwaku3.adaptivepulse.core.menu.Menu
import io.github.wakuwaku3.adaptivepulse.core.menu.MenuKind
import io.github.wakuwaku3.adaptivepulse.core.menu.PlannedSegment
import io.github.wakuwaku3.adaptivepulse.core.menu.SessionPlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class PlanEngineTest {

    private val baseConfig = SessionConfig(upperBpm = 155, lowerBpm = 140)

    private val warmup = Menu(
        id = "warmup",
        name = "Warm-up",
        kind = MenuKind.Timed(upperBpm = 116, lowerBpm = null, minutes = 5),
    )

    private val interval = Menu(
        id = "hiit",
        name = "hiit",
        kind = MenuKind.Interval(upperBpm = 155, lowerBpm = 140, cycles = 2),
    )

    private val walking = Menu(
        id = "walk",
        name = "Walking",
        kind = MenuKind.Timed(upperBpm = 135, lowerBpm = 115, minutes = 20),
    )

    private fun plan(vararg segments: PlannedSegment) = SessionPlan(
        name = "Test",
        programId = "test-program",
        segments = segments.toList(),
    )

    @Test
    fun `メニュー単体プランは既存セッションと同じ形で終わる`() {
        val engine = PlanEngine(plan(PlannedSegment(interval, 1)), baseConfig)
        assertNull(engine.onHeartRate(141, 10.seconds))
        assertEquals(SessionEvent.EnterRecovery, engine.onHeartRate(156, 60.seconds))
        assertEquals(SessionEvent.SessionFinished, engine.onHeartRate(139, 120.seconds))
        assertTrue(engine.finished)
        assertEquals(1, engine.cycles)
    }

    @Test
    fun `セグメントの継ぎ目で EnterNextMenu が 1 回発火し、次メニューに進む`() {
        val engine = PlanEngine(
            plan(PlannedSegment(warmup, 5), PlannedSegment(interval, 1)),
            baseConfig,
        )
        assertEquals(0, engine.segmentIndex)
        // ウォームアップ 5 分経過 → 継ぎ目
        assertEquals(SessionEvent.EnterNextMenu, engine.onTimePassed(5.minutes))
        assertEquals(1, engine.segmentIndex)
        assertFalse(engine.finished)
        // 継ぎ目以後は interval エンジンが動く
        assertEquals(SessionEvent.EnterRecovery, engine.onHeartRate(156, 5.minutes + 90.seconds))
    }

    @Test
    fun `内部エンジンにはセグメント相対時間が渡る (後続セグメントの時間制が誤発火しない)`() {
        val engine = PlanEngine(
            plan(PlannedSegment(interval, 1), PlannedSegment(walking, 20)),
            baseConfig,
        )
        // interval をセッション開始 6 分の時点で終える
        engine.onHeartRate(141, 1.minutes)
        engine.onHeartRate(156, 3.minutes)
        assertEquals(SessionEvent.EnterNextMenu, engine.onHeartRate(139, 6.minutes))
        // walking はここから 20 分。セッション絶対時間 (6 分) がそのまま渡ると残りが縮んでしまう
        assertNull(engine.onTimePassed(25.minutes))
        assertFalse(engine.finished)
        assertEquals(SessionEvent.SessionFinished, engine.onTimePassed(26.minutes))
        assertTrue(engine.finished)
    }

    @Test
    fun `interval のタイムアウトはセグメント相対で判定される`() {
        val engine = PlanEngine(
            plan(PlannedSegment(warmup, 5), PlannedSegment(interval, 2)),
            baseConfig,
        )
        assertEquals(SessionEvent.EnterNextMenu, engine.onTimePassed(5.minutes))
        // 高強度タイムアウト 4 分はセグメント開始 (5 分) 起点。8 分時点ではまだ 3 分
        assertNull(engine.onTimePassed(8.minutes))
        assertFalse(engine.finished)
        // 9 分超でタイムアウト → セーフティ停止はプログラム全体を終える
        assertEquals(SessionEvent.SessionFinished, engine.onTimePassed(9.minutes + 1.seconds))
        assertTrue(engine.finished)
        assertTrue(engine.fatigueBrakeFired)
    }

    @Test
    fun `本数の合計と予定本数がプラン全体で集計される`() {
        val engine = PlanEngine(
            plan(
                PlannedSegment(interval, 2),
                PlannedSegment(walking, 20),
                PlannedSegment(interval, 1),
            ),
            baseConfig,
        )
        assertEquals(3, engine.plannedCycles)

        // interval 1 本目
        engine.onHeartRate(141, 10.seconds)
        engine.onHeartRate(156, 60.seconds)
        engine.onHeartRate(139, 120.seconds)
        // 2 本目 → 継ぎ目
        engine.onHeartRate(141, 130.seconds)
        engine.onHeartRate(156, 180.seconds)
        assertEquals(SessionEvent.EnterNextMenu, engine.onHeartRate(139, 240.seconds))
        assertEquals(2, engine.cycles)

        // walking 20 分 → 継ぎ目
        assertEquals(SessionEvent.EnterNextMenu, engine.onTimePassed(240.seconds + 20.minutes))

        // 最後の interval 1 本
        val end = 240.seconds + 20.minutes
        engine.onHeartRate(141, end + 10.seconds)
        engine.onHeartRate(156, end + 60.seconds)
        assertEquals(SessionEvent.SessionFinished, engine.onHeartRate(139, end + 120.seconds))
        assertEquals(3, engine.cycles)
        assertEquals(3, engine.segmentResults(end + 120.seconds).size)
    }

    @Test
    fun `手動停止相当の segmentResults は進行中セグメントの部分実績を含む`() {
        val engine = PlanEngine(
            plan(PlannedSegment(interval, 2), PlannedSegment(walking, 20)),
            baseConfig,
        )
        engine.onHeartRate(141, 10.seconds)
        engine.onHeartRate(156, 60.seconds) // 1 本完了、2 本目回復中
        val results = engine.segmentResults(90.seconds)
        assertEquals(1, results.size)
        assertEquals(1, results.first().completedCycles)
        assertEquals("hiit", results.first().segment.menu.id)
    }

    @Test
    fun `時間制セグメント中の閾値ナッジは何もしない`() {
        val engine = PlanEngine(plan(PlannedSegment(walking, 20)), baseConfig)
        assertEquals(135, engine.adjustActiveThreshold(+5))
        assertEquals(135, engine.activeUpperBpm)
    }

    @Test
    fun `帯の公開値 - 時間制は帯、interval はナッジ後の閾値を返す`() {
        val engine = PlanEngine(
            plan(PlannedSegment(warmup, 5), PlannedSegment(interval, 1)),
            baseConfig,
        )
        assertEquals(116, engine.activeUpperBpm)
        assertNull(engine.activeLowerBpm)
        assertTrue(engine.inMeasurement) // 時間制は全サンプル計測

        engine.onTimePassed(5.minutes)
        assertEquals(155, engine.activeUpperBpm)
        assertEquals(140, engine.activeLowerBpm)
        assertFalse(engine.inMeasurement) // interval 開始直後はウォームアップ除外
        engine.adjustActiveThreshold(-3)
        assertEquals(152, engine.activeUpperBpm)
    }

    @Test
    fun `時間制の帯逸脱イベントがそのまま通る`() {
        val engine = PlanEngine(plan(PlannedSegment(walking, 20)), baseConfig)
        assertEquals(SessionEvent.AboveBand, engine.onHeartRate(140, 10.seconds))
        assertNull(engine.onHeartRate(140, 20.seconds))
        assertNull(engine.onHeartRate(120, 30.seconds))
        assertEquals(SessionEvent.BelowBand, engine.onHeartRate(110, 40.seconds))
    }

    @Test
    fun `cycleElapsed と phaseElapsed - 時間制はセグメント経過そのもの`() {
        val engine = PlanEngine(
            plan(PlannedSegment(warmup, 5), PlannedSegment(interval, 1)),
            baseConfig,
        )
        assertEquals(3.minutes, engine.cycleElapsed(3.minutes))
        assertEquals(3.minutes, engine.phaseElapsed(3.minutes))

        engine.onTimePassed(5.minutes)
        // interval セグメント: サイクル計測開始前は 0
        assertEquals(kotlin.time.Duration.ZERO, engine.cycleElapsed(5.minutes + 10.seconds))
        engine.onHeartRate(141, 5.minutes + 30.seconds)
        assertEquals(20.seconds, engine.cycleElapsed(5.minutes + 50.seconds))
        assertEquals(50.seconds, engine.phaseElapsed(5.minutes + 50.seconds))
    }
}
