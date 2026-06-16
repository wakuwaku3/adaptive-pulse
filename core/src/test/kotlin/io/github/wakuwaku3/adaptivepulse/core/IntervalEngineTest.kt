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
        highPhaseTimeout = 3.minutes,
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
    fun `高強度タイムアウト - 上限未到達のまま上限時間を超えたら疲労ブレーキ扱いで終了する`() {
        val engine = IntervalEngine(config)
        val events = run(
            engine,
            hold(0, 185, 150), // 140-155 の間に張り付いたまま 3 分超過
        )
        assertEquals(listOf(181 to SessionEvent.SessionFinished), events)
        assertEquals(Phase.FINISHED, engine.phase)
        kotlin.test.assertTrue(engine.fatigueBrakeFired)
        assertEquals(0, engine.completedCycles)
        // タイムアウトしたサイクルは所要時間を測れないので基準にしない
        assertNull(engine.baseline)
    }

    @Test
    fun `回復タイムアウト - 下限まで下がらないまま上限時間を超えたら疲労ブレーキ扱いで終了する`() {
        val engine = IntervalEngine(config)
        val events = run(
            engine,
            listOf(0 to 141, 60 to 156) + hold(61, 245, 148), // 回復で 148 に張り付き 3 分超過
        )
        assertEquals(
            listOf(
                60 to SessionEvent.EnterRecovery,
                241 to SessionEvent.SessionFinished,
            ),
            events,
        )
        assertEquals(Phase.FINISHED, engine.phase)
        kotlin.test.assertTrue(engine.fatigueBrakeFired)
        // 回復が途中で打ち切られたのでサイクル1 は完走扱いにしない
        assertEquals(0, engine.completedCycles)
    }

    @Test
    fun `心拍サンプルが途絶えても onTimePassed でタイムアウト終了が動く`() {
        val engine = IntervalEngine(config)
        assertNull(engine.onTimePassed(180.seconds))
        assertEquals(SessionEvent.SessionFinished, engine.onTimePassed(181.seconds))
        assertEquals(Phase.FINISHED, engine.phase)
        kotlin.test.assertTrue(engine.fatigueBrakeFired)
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
    fun `ウォームアップ判定 - 下限閾値を上向きに超えるまでが区間で、2 サイクル目以降は該当しない`() {
        val engine = IntervalEngine(config)
        kotlin.test.assertTrue(engine.isWarmingUp)
        engine.onHeartRate(120, 0.seconds)
        kotlin.test.assertTrue(engine.isWarmingUp)
        engine.onHeartRate(141, 30.seconds) // 計測開始 = ウォームアップ終了
        kotlin.test.assertFalse(engine.isWarmingUp)
        engine.onHeartRate(156, 90.seconds)
        engine.onHeartRate(139, 150.seconds) // サイクル2 高強度 (140 未満から再開)
        kotlin.test.assertFalse(engine.isWarmingUp)
    }

    @Test
    fun `ゾーン滞在率 - 下限〜上限の帯にいたサンプルの割合を返す`() {
        val metrics = SessionMetrics(config)
        assertNull(metrics.zoneRatio)
        listOf(120, 140, 150, 155, 160).forEach(metrics::onHeartRate) // 帯内は 140/150/155
        assertEquals(0.6, metrics.zoneRatio!!, 1e-9)
        assertEquals(160, metrics.maxBpm)
        assertEquals(145, metrics.avgBpm)
    }

    @Test
    fun `per-cycle 高強度所要時間と疲労ブレーキ発動が履歴用に残る`() {
        val engine = IntervalEngine(config.copy(targetCycles = 7))
        run(
            engine,
            listOf(
                0 to 141, 60 to 156, // サイクル1: 60 秒 (基準)
                120 to 139, 130 to 141, 155 to 156, // サイクル2: 25 秒 → 疲労
            ),
        )
        assertEquals(listOf(60.seconds, 25.seconds), engine.highDurations)
        kotlin.test.assertTrue(engine.fatigueBrakeFired)
    }

    @Test
    fun `completedCycles - 高強度→回復まで完走したサイクル数を返し、未完了サイクルは含めない`() {
        val engine = IntervalEngine(config)
        // 初期は 0
        assertEquals(0, engine.completedCycles)
        run(engine, listOf(0 to 141, 60 to 156)) // サイクル1 高強度のみ
        assertEquals(0, engine.completedCycles)
        run(engine, listOf(120 to 139)) // サイクル1 完走
        assertEquals(1, engine.completedCycles)
        run(engine, listOf(130 to 141, 190 to 156, 250 to 139)) // サイクル2 完走
        assertEquals(2, engine.completedCycles)
    }

    @Test
    fun `completedCycles - 疲労ブレーキ発動時は最終サイクルの回復完了までを完走に数える`() {
        val engine = IntervalEngine(config.copy(targetCycles = 7))
        run(
            engine,
            listOf(
                0 to 141, 60 to 156, 120 to 139, // サイクル1
                130 to 141, 155 to 156,          // サイクル2 高強度 25 秒 → 疲労ブレーキ
                215 to 139,                       // サイクル2 (最終化) 回復完了
            ),
        )
        assertEquals(2, engine.completedCycles)
    }

    @Test
    fun `設定の不変条件 - 閾値の逆転や不正な係数を弾く`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> { SessionConfig(upperBpm = 140, lowerBpm = 155) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { SessionConfig(targetCycles = 0) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { SessionConfig(fatigueRatio = 1.0) }
    }
}
