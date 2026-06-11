package io.github.wakuwaku3.adaptivepulse.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class IntervalEngineTest {

    // テストの見通しを優先し、サイクル数と時間を小さくした設定を基本にする
    private val config = SessionConfig(
        upperBpm = 155,
        lowerBpm = 140,
        targetCycles = 3,
        fatigueRatio = 0.5,
        minBaseline = 45.seconds,
        highPhaseTimeout = 4.minutes,
        recoveryTimeout = 3.minutes,
    )

    /** (経過秒, bpm) の列を流し、発火したイベントを (経過秒, イベント) で集める */
    private fun run(engine: IntervalEngine, samples: List<Pair<Int, Int>>): List<Pair<Int, SessionEvent>> =
        samples.mapNotNull { (sec, bpm) ->
            engine.onHeartRate(bpm, sec.seconds)?.let { sec to it }
        }

    /** fromSec から 1 秒刻みで一定 bpm を流すサンプル列 */
    private fun hold(fromSec: Int, toSec: Int, bpm: Int): List<Pair<Int, Int>> =
        (fromSec..toSec).map { it to bpm }

    @Test
    fun `通常フロー - 閾値到達で交互に遷移し目標サイクルの回復完了で終了する`() {
        val engine = IntervalEngine(config)
        val events = run(
            engine,
            listOf(
                0 to 120,   // ウォームアップ
                30 to 141,  // 下限上向き超過 → 計測開始
                90 to 156,  // 上限超過 → 回復へ (サイクル1 高強度 60 秒)
                150 to 139, // 下限下回り → 高強度へ (サイクル1 完了)
                160 to 141,
                220 to 156, // → 回復へ
                280 to 139, // サイクル2 完了
                290 to 141,
                350 to 156, // → 回復へ
                410 to 139, // サイクル3 (最終) 完了 → 終了
            ),
        )
        assertEquals(
            listOf(
                90 to SessionEvent.EnterRecovery,
                150 to SessionEvent.EnterHighIntensity,
                220 to SessionEvent.EnterRecovery,
                280 to SessionEvent.EnterHighIntensity,
                350 to SessionEvent.EnterRecovery,
                410 to SessionEvent.SessionFinished,
            ),
            events,
        )
        assertEquals(Phase.FINISHED, engine.phase)
        assertEquals(60.seconds, engine.baseline)
    }

    @Test
    fun `ヒステリシス - 閾値間 (140-155) の心拍ではどちらのフェーズでも遷移しない`() {
        val engine = IntervalEngine(config)
        assertNull(engine.onHeartRate(150, 0.seconds))
        assertNull(engine.onHeartRate(155, 1.seconds)) // 「超えたら」なので 155 ちょうどは遷移しない
        assertEquals(SessionEvent.EnterRecovery, engine.onHeartRate(156, 2.seconds))
        assertNull(engine.onHeartRate(150, 3.seconds))
        assertNull(engine.onHeartRate(140, 4.seconds)) // 「下回ったら」なので 140 ちょうどは遷移しない
        assertEquals(SessionEvent.EnterHighIntensity, engine.onHeartRate(139, 5.seconds))
    }

    @Test
    fun `ウォームアップ除外 - 基準時間は下限上向き超過から上限到達までで測る`() {
        val engine = IntervalEngine(config)
        run(
            engine,
            listOf(
                0 to 100,   // セッション開始。ここからではなく
                120 to 141, // ここから測る
                180 to 156,
            ),
        )
        assertEquals(60.seconds, engine.baseline)
    }

    @Test
    fun `最低基準時間ガード - 初回が短すぎたら 2 サイクル目を基準にする`() {
        val engine = IntervalEngine(config)
        run(
            engine,
            listOf(
                0 to 150,  // 筋トレ直後: 開始時点で既に下限超え
                10 to 156, // 10 秒で上限到達 (< minBaseline 45 秒) → 基準にしない
                60 to 139, // サイクル1 完了
                70 to 141,
                130 to 156, // サイクル2: 60 秒 → これを基準にする
            ),
        )
        assertEquals(60.seconds, engine.baseline)
        // 短すぎた初回が基準になっていれば 2 サイクル目 (60 秒 > 10×0.5) は疲労にならないが、
        // それ以前に誤った基準で以降のサイクルが歪むのを防いでいる
        assertEquals(config.targetCycles, engine.finalCycle)
    }

    @Test
    fun `疲労ブレーキ - 基準の半分以下で上限到達したら現サイクルを最終化し回復完了で終了する`() {
        val engine = IntervalEngine(config.copy(targetCycles = 7))
        val events = run(
            engine,
            listOf(
                0 to 141,   // 計測開始
                60 to 156,  // 基準 = 60 秒
                120 to 139, // サイクル1 完了
                130 to 141,
                155 to 156, // サイクル2: 25 秒 ≤ 60×0.5 → 疲労
                215 to 139, // サイクル2 (最終化済み) の回復完了 → 終了
            ),
        )
        assertEquals(
            listOf(
                60 to SessionEvent.EnterRecovery,
                120 to SessionEvent.EnterHighIntensity,
                155 to SessionEvent.FatigueBrake,
                215 to SessionEvent.SessionFinished,
            ),
            events,
        )
        assertEquals(2, engine.finalCycle)
    }

    @Test
    fun `疲労ブレーキ - 境界値ちょうど (基準×係数) でも発動する`() {
        val engine = IntervalEngine(config.copy(targetCycles = 7))
        run(
            engine,
            listOf(
                0 to 141,
                60 to 156,  // 基準 = 60 秒
                120 to 139,
                130 to 141,
            ),
        )
        // 130 秒に下限超過 → 160 秒に上限到達 = 30 秒 = 60×0.5 ちょうど
        assertEquals(SessionEvent.FatigueBrake, engine.onHeartRate(156, 160.seconds))
    }

    @Test
    fun `高強度タイムアウト - 上限未到達のまま上限時間を超えたら強制的に回復へ`() {
        val engine = IntervalEngine(config)
        val events = run(
            engine,
            hold(0, 245, 150), // 140-155 の間に張り付いたまま 4 分超過
        )
        assertEquals(listOf(241 to SessionEvent.PhaseTimeout), events)
        assertEquals(Phase.RECOVERY, engine.phase)
        // タイムアウトしたサイクルは所要時間を測れないので基準にしない
        assertNull(engine.baseline)
    }

    @Test
    fun `回復タイムアウト - 下限まで下がらないまま上限時間を超えたら強制的に高強度へ`() {
        val engine = IntervalEngine(config)
        val events = run(
            engine,
            listOf(0 to 141, 60 to 156) + hold(61, 245, 148), // 回復で 148 に張り付き 3 分超過
        )
        assertEquals(
            listOf(
                60 to SessionEvent.EnterRecovery,
                241 to SessionEvent.PhaseTimeout,
            ),
            events,
        )
        assertEquals(Phase.HIGH_INTENSITY, engine.phase)
        assertEquals(2, engine.currentCycle) // 強制遷移でもサイクルは完了扱い
    }

    @Test
    fun `回復タイムアウト - 最終サイクルなら強制完了でセッション終了する`() {
        val engine = IntervalEngine(config.copy(targetCycles = 1))
        val events = run(
            engine,
            listOf(0 to 141, 60 to 156) + hold(61, 245, 148),
        )
        assertEquals(
            listOf(
                60 to SessionEvent.EnterRecovery,
                241 to SessionEvent.SessionFinished,
            ),
            events,
        )
        assertEquals(Phase.FINISHED, engine.phase)
    }

    @Test
    fun `心拍サンプルが途絶えても onTimePassed でタイムアウト遷移が動く`() {
        val engine = IntervalEngine(config)
        assertNull(engine.onTimePassed(240.seconds))
        assertEquals(SessionEvent.PhaseTimeout, engine.onTimePassed(241.seconds))
        assertEquals(Phase.RECOVERY, engine.phase)
    }

    @Test
    fun `終了後のサンプルは無視する`() {
        val engine = IntervalEngine(config.copy(targetCycles = 1))
        run(engine, listOf(0 to 141, 60 to 156, 120 to 139))
        assertEquals(Phase.FINISHED, engine.phase)
        assertNull(engine.onHeartRate(156, 130.seconds))
        assertNull(engine.onTimePassed(600.seconds))
    }

    @Test
    fun `設定の不変条件 - 閾値の逆転や不正な係数を弾く`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> { SessionConfig(upperBpm = 140, lowerBpm = 155) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { SessionConfig(targetCycles = 0) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { SessionConfig(fatigueRatio = 1.0) }
    }
}
