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
        // 上限到達せず終了 = サイクルカウント加算なし
        assertEquals(0, engine.currentCycle)
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
        // 高強度→回復までは完了したのでカウントは 1 (回復タイムアウトでもこの 1 は残す)
        assertEquals(1, engine.currentCycle)
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
    fun `currentCycle - 0 開始で、上限到達による回復遷移のタイミングで +1 する`() {
        val engine = IntervalEngine(config)
        assertEquals(0, engine.currentCycle)
        run(engine, listOf(0 to 141)) // ウォームアップ
        assertEquals(0, engine.currentCycle)
        run(engine, listOf(60 to 156)) // 高強度1 → 回復遷移
        assertEquals(1, engine.currentCycle)
        run(engine, listOf(120 to 139)) // 回復完了 (次の高強度へ) → カウントは増やさない
        assertEquals(1, engine.currentCycle)
        run(engine, listOf(130 to 141, 190 to 156)) // 高強度2 → 回復遷移
        assertEquals(2, engine.currentCycle)
    }

    @Test
    fun `currentCycle - 疲労ブレーキ発動時もカウントは進める (最終サイクルとして扱う)`() {
        val engine = IntervalEngine(config.copy(targetCycles = 7))
        run(
            engine,
            listOf(
                0 to 141, 60 to 156, 120 to 139, // サイクル1
                130 to 141, 155 to 156,          // サイクル2 高強度 25 秒 → 疲労ブレーキ (currentCycle=2)
                215 to 139,                       // サイクル2 (最終化) 回復完了
            ),
        )
        assertEquals(2, engine.currentCycle)
        assertEquals(2, engine.finalCycle)
    }

    @Test
    fun `閾値の動的調整 - 高強度中は上限を、回復中は下限を動かす`() {
        val engine = IntervalEngine(config)
        assertEquals(155, engine.upperBpm)
        assertEquals(140, engine.lowerBpm)
        // 高強度フェーズ (WARM-UP 含む) は上限が対象
        assertEquals(157, engine.adjustActiveThreshold(+2))
        assertEquals(157, engine.upperBpm)
        assertEquals(140, engine.lowerBpm)
        // 回復に入る
        run(engine, listOf(0 to 141, 60 to 158))
        assertEquals(Phase.RECOVERY, engine.phase)
        // 回復中は下限が対象
        assertEquals(142, engine.adjustActiveThreshold(+2))
        assertEquals(157, engine.upperBpm)
        assertEquals(142, engine.lowerBpm)
    }

    @Test
    fun `閾値の動的調整 - 調整後の閾値で次の遷移が判定される`() {
        val engine = IntervalEngine(config)
        engine.adjustActiveThreshold(-5) // 上限 155 → 150
        val events = run(engine, listOf(0 to 141, 60 to 151))
        assertEquals(listOf(60 to SessionEvent.EnterRecovery), events)
    }

    @Test
    fun `閾値の動的調整 - 最小ギャップ 5bpm を侵さないよう clamp する`() {
        val engine = IntervalEngine(config) // 155/140
        engine.adjustActiveThreshold(-100)
        assertEquals(145, engine.upperBpm) // 下限 140 + ギャップ 5
        // 上限が下がった状態で回復に入って下限を上げる
        run(engine, listOf(0 to 141, 60 to 146))
        assertEquals(Phase.RECOVERY, engine.phase)
        engine.adjustActiveThreshold(+100)
        assertEquals(140, engine.lowerBpm) // 上限 145 - ギャップ 5
    }

    @Test
    fun `閾値の動的調整 - 終了後は無視する`() {
        val engine = IntervalEngine(config.copy(targetCycles = 1))
        run(engine, listOf(0 to 141, 60 to 156, 120 to 139))
        assertEquals(Phase.FINISHED, engine.phase)
        val upperBefore = engine.upperBpm
        val lowerBefore = engine.lowerBpm
        engine.adjustActiveThreshold(+10)
        assertEquals(upperBefore, engine.upperBpm)
        assertEquals(lowerBefore, engine.lowerBpm)
    }

    @Test
    fun `閾値の動的調整 - 永続化しない (config 本体は不変)`() {
        val engine = IntervalEngine(config)
        engine.adjustActiveThreshold(+3)
        assertEquals(158, engine.upperBpm)
        assertEquals(155, config.upperBpm) // 引数の config 自身は触らない
    }

    @Test
    fun `設定の不変条件 - 閾値の逆転や不正な係数を弾く`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> { SessionConfig(upperBpm = 140, lowerBpm = 155) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { SessionConfig(targetCycles = 0) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { SessionConfig(fatigueRatio = 1.0) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { SessionConfig(recoveryFatigueRatio = 1.0) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { SessionConfig(ageYears = 5) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { SessionConfig(restingBpm = 20) }
    }

    @Test
    fun `回復疲労ブレーキ - 回復時間が基準の係数倍を超えたらサイクル完了で終了する`() {
        val engine = IntervalEngine(config.copy(targetCycles = 7))
        val events = run(
            engine,
            listOf(
                0 to 141,
                60 to 156,   // 高強度1 60 秒 (基準)
                120 to 139,  // 回復1 60 秒 (基準)
                130 to 141,
                190 to 156,  // 高強度2 60 秒
                280 to 139,  // 回復2 90 秒 = 60×1.5 → 疲労 → 終了
            ),
        )
        assertEquals(
            listOf(
                60 to SessionEvent.EnterRecovery,
                120 to SessionEvent.EnterHighIntensity,
                190 to SessionEvent.EnterRecovery,
                280 to SessionEvent.SessionFinished,
            ),
            events,
        )
        assertEquals(Phase.FINISHED, engine.phase)
        kotlin.test.assertTrue(engine.fatigueBrakeFired)
        assertEquals(2, engine.currentCycle)
        assertEquals(2, engine.finalCycle)
        assertEquals(60.seconds, engine.recoveryBaseline)
    }

    @Test
    fun `回復疲労ブレーキ - 基準内なら継続する`() {
        val engine = IntervalEngine(config.copy(targetCycles = 7))
        run(
            engine,
            listOf(
                0 to 141, 60 to 156, 120 to 139,    // サイクル1: 回復 60 秒 (基準)
                130 to 141, 190 to 156, 279 to 139, // サイクル2: 回復 89 秒 (< 60×1.5=90)
            ),
        )
        kotlin.test.assertFalse(engine.fatigueBrakeFired)
        assertEquals(Phase.HIGH_INTENSITY, engine.phase)
        assertEquals(listOf(60.seconds, 89.seconds), engine.recoveryDurations)
    }

    @Test
    fun `回復疲労ブレーキ - 高強度疲労で短縮された最終サイクルでは再発動しない`() {
        val engine = IntervalEngine(config.copy(targetCycles = 7))
        val events = run(
            engine,
            listOf(
                0 to 141, 60 to 156, 120 to 139,  // サイクル1: 回復 60 秒 (基準)
                130 to 141,
                155 to 156,                        // サイクル2 高強度 25 秒 → 高強度疲労
                250 to 139,                        // サイクル2 回復 95 秒 (> 60×1.5=90)
            ),
        )
        // 高強度疲労で finalCycle=2 になっているので、回復が基準超えでも二重発動せず終了のみ
        assertEquals(
            listOf(
                60 to SessionEvent.EnterRecovery,
                120 to SessionEvent.EnterHighIntensity,
                155 to SessionEvent.FatigueBrake,
                250 to SessionEvent.SessionFinished,
            ),
            events,
        )
    }

    @Test
    fun `回復疲労ブレーキ - targetCycles=1 では発動しない (基準を設定して終了)`() {
        val engine = IntervalEngine(config.copy(targetCycles = 1))
        run(engine, listOf(0 to 141, 60 to 156, 120 to 139))
        assertEquals(Phase.FINISHED, engine.phase)
        kotlin.test.assertFalse(engine.fatigueBrakeFired)
        assertEquals(60.seconds, engine.recoveryBaseline)
    }

    @Test
    fun `回復基準 - 高強度基準が確定したサイクルの回復だけを基準にする (筋トレ直後ケース)`() {
        val engine = IntervalEngine(config.copy(targetCycles = 7))
        run(
            engine,
            listOf(
                // サイクル1: 開始時点で既に下限超え。高強度 10 秒 (< 45 秒 minBaseline) → 高強度基準は持ち越し
                0 to 150,
                10 to 156,
                40 to 139,   // サイクル1 回復 30 秒
                // サイクル2: 高強度 60 秒 → ここで初めて高強度基準が確定
                50 to 141,
                100 to 156,
                160 to 139,  // サイクル2 回復 60 秒 → recoveryBaseline = 60 秒
            ),
        )
        // 高強度・回復ともサイクル2 を基準にしている (サイクル1 の歪んだ値は採用しない)
        assertEquals(50.seconds, engine.baseline)
        assertEquals(60.seconds, engine.recoveryBaseline)
    }

    @Test
    fun `履歴 - 回復所要時間がサイクルごとに残る`() {
        val engine = IntervalEngine(config)
        run(
            engine,
            listOf(
                0 to 141, 60 to 156, 120 to 139,
                130 to 141, 190 to 156, 240 to 139,
            ),
        )
        assertEquals(listOf(60.seconds, 50.seconds), engine.recoveryDurations)
    }
}
