package io.github.wakuwaku3.adaptivepulse.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
    fun `最大基準時間ガード - 初回が長すぎたら 2 サイクル目を基準にする (心拍 onset 反応で長くなったケース)`() {
        // maxBaseline = 150 秒 (デフォルト)。初回が 160 秒 → 範囲外 → 2 サイクル目で確定
        val engine = IntervalEngine(config.copy(targetCycles = 7))
        run(
            engine,
            listOf(
                0 to 100, // セッション開始 (ウォームアップ)
                10 to 141, // 計測開始
                170 to 156, // 上限到達 = 160 秒 (> maxBaseline 150 秒) → 採用しない
                230 to 139, // サイクル1 完了
                240 to 141,
                300 to 156, // サイクル2: 60 秒 → これを基準にする
            ),
        )
        assertEquals(60.seconds, engine.baseline)
        // 採用された場合: 160×0.5=80 秒閾値 → サイクル2 (60秒) が EASE_PACE 擬陽性を起こすが、
        // 不採用なら 60×0.5=30 秒閾値 → 60 秒は閾値超え → 提案出ない
        assertNull(engine.latestSuggestion)
    }

    @Test
    fun `最大基準時間ガード - 境界値 (= maxBaseline ちょうど) は採用する`() {
        val engine = IntervalEngine(config.copy(maxBaseline = 90.seconds))
        run(
            engine,
            listOf(
                0 to 141, // 計測開始
                90 to 156, // 上限到達 = 90 秒 = maxBaseline ちょうど → 採用
            ),
        )
        assertEquals(90.seconds, engine.baseline)
    }

    @Test
    fun `高強度短縮 - 基準の半分以下で上限到達したら EASE_PACE 提案を出し、auto-brake はしない`() {
        val engine = IntervalEngine(config.copy(targetCycles = 7))
        val events = run(
            engine,
            listOf(
                0 to 141,   // 計測開始
                60 to 156,  // 基準 = 60 秒
                120 to 139, // サイクル1 完了
                130 to 141,
                155 to 156, // サイクル2: 25 秒 ≤ 60×0.5 → 提案を出すだけ
            ),
        )
        // auto-brake 廃止後は通常の EnterRecovery イベント。FatigueBrake は発火しない
        assertEquals(
            listOf(
                60 to SessionEvent.EnterRecovery,
                120 to SessionEvent.EnterHighIntensity,
                155 to SessionEvent.EnterRecovery,
            ),
            events,
        )
        assertEquals(7, engine.finalCycle) // finalCycle を勝手に縮めない
        kotlin.test.assertFalse(engine.fatigueBrakeFired) // 自動ブレーキフラグは立たない
        val suggestion = engine.latestSuggestion!!
        assertEquals(SuggestionKind.EASE_PACE, suggestion.kind)
    }

    @Test
    fun `高強度短縮 - 境界値ちょうど (基準×係数) でも提案を出す`() {
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
        // 130 秒に下限超過 → 160 秒に上限到達 = 30 秒 = 60×0.5 ちょうど → 提案を出すだけ
        assertEquals(SessionEvent.EnterRecovery, engine.onHeartRate(156, 160.seconds))
        assertEquals(SuggestionKind.EASE_PACE, engine.latestSuggestion?.kind)
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
        listOf(120, 140, 150, 155, 160).forEach { metrics.onHeartRate(it, inMeasurement = true) } // 帯内は 140/150/155
        assertEquals(0.6, metrics.zoneRatio!!, 1e-9)
        assertEquals(160, metrics.maxBpm)
        assertEquals(145, metrics.avgBpm)
    }

    @Test
    fun `ゾーン滞在率 - inMeasurement=false のサンプルは算入しない (ウォームアップ除外)`() {
        val metrics = SessionMetrics(config)
        // ウォームアップ中の低心拍は捨てる
        listOf(80, 100, 120).forEach { metrics.onHeartRate(it, inMeasurement = false) }
        assertNull(metrics.zoneRatio)
        assertNull(metrics.avgBpm)
        assertNull(metrics.maxBpm)
        // 計測開始後だけ算入される
        listOf(141, 150, 156).forEach { metrics.onHeartRate(it, inMeasurement = true) }
        assertEquals(149, metrics.avgBpm) // (141+150+156)/3 = 149
        assertEquals(156, metrics.maxBpm)
    }

    @Test
    fun `per-cycle 高強度所要時間が履歴用に残り、短縮時は EASE_PACE 提案が出ている`() {
        val engine = IntervalEngine(config.copy(targetCycles = 7))
        run(
            engine,
            listOf(
                0 to 141, 60 to 156, // サイクル1: 60 秒 (基準)
                120 to 139, 130 to 141, 155 to 156, // サイクル2: 25 秒 → 提案を出すだけ
            ),
        )
        assertEquals(listOf(60.seconds, 25.seconds), engine.highDurations)
        // auto-brake は無し、提案は残っている
        kotlin.test.assertFalse(engine.fatigueBrakeFired)
        assertEquals(SuggestionKind.EASE_PACE, engine.latestSuggestion?.kind)
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
    fun `currentCycle - 提案 (EASE_PACE) が出てもセッションは続き、サイクルは加算される`() {
        val engine = IntervalEngine(config.copy(targetCycles = 7))
        run(
            engine,
            listOf(
                0 to 141, 60 to 156, 120 to 139, // サイクル1
                130 to 141, 155 to 156,          // サイクル2 高強度 25 秒 → EASE_PACE 提案を出すだけ
                215 to 139,                       // サイクル2 回復完了 → サイクル 3 へ (提案はクリア)
            ),
        )
        assertEquals(2, engine.currentCycle)
        // 自動ブレーキ廃止により finalCycle は元のまま
        assertEquals(7, engine.finalCycle)
        // 提案は次サイクル開始時にクリアされる
        assertNull(engine.latestSuggestion)
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
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            SessionConfig(minBaseline = 60.seconds, maxBaseline = 45.seconds)
        }
    }

    @Test
    fun `cycleStartedAt - ウォームアップ中は null、計測開始時点でセットされ、サイクル間で null に戻る`() {
        val engine = IntervalEngine(config)
        kotlin.test.assertNull(engine.cycleStartedAt) // セッション開始直後 (ウォームアップ中)
        engine.onHeartRate(120, 10.seconds)
        kotlin.test.assertNull(engine.cycleStartedAt) // まだ下限未満
        engine.onHeartRate(141, 30.seconds) // 下限超過 = 計測開始
        assertEquals(30.seconds, engine.cycleStartedAt)
        engine.onHeartRate(156, 90.seconds) // 上限到達 → 回復へ
        assertEquals(30.seconds, engine.cycleStartedAt) // 回復中も値は残る (UI 表示用)
        engine.onHeartRate(139, 150.seconds) // 回復完了 → サイクル2 高強度開始
        kotlin.test.assertNull(engine.cycleStartedAt) // サイクル間移行で null
        engine.onHeartRate(141, 160.seconds) // 下限再超過 = サイクル2 計測開始
        assertEquals(160.seconds, engine.cycleStartedAt)
    }

    @Test
    fun `回復遅延 - 回復中に基準の係数倍を超えた瞬間に CONSIDER_STOP 提案を出し、次サイクル開始でクリアする`() {
        val engine = IntervalEngine(config.copy(targetCycles = 7))
        run(
            engine,
            listOf(
                0 to 141,
                60 to 156,   // 高強度1 60 秒 (基準)
                120 to 139,  // 回復1 60 秒 → recoveryBaseline = 60 秒
                130 to 141,
                190 to 156,  // 高強度2 → 回復開始
            ),
        )
        assertEquals(60.seconds, engine.recoveryBaseline)
        // 回復中の閾値未満では提案は出ない
        engine.onTimePassed(279.seconds)
        assertNull(engine.latestSuggestion)
        // 60×1.5=90 秒に到達した瞬間に CONSIDER_STOP を出す
        engine.onTimePassed(280.seconds)
        assertEquals(SuggestionKind.CONSIDER_STOP, engine.latestSuggestion?.kind)
        // 下限を割って次サイクルが始まったら提案はクリアされる
        assertEquals(SessionEvent.EnterHighIntensity, engine.onHeartRate(139, 290.seconds))
        assertNull(engine.latestSuggestion)
        kotlin.test.assertFalse(engine.fatigueBrakeFired)
        assertEquals(2, engine.currentCycle)
        assertEquals(7, engine.finalCycle) // 自動短縮しない
    }

    @Test
    fun `提案クリア - 回復中に出した EASE_PACE は次サイクル開始時にクリアされる`() {
        val engine = IntervalEngine(config.copy(targetCycles = 7))
        run(
            engine,
            listOf(
                0 to 141, 60 to 156, 120 to 139,    // サイクル1: 基準確定
                130 to 141, 155 to 156,             // サイクル2 高強度 25 秒 → EASE_PACE 提案
            ),
        )
        assertEquals(SuggestionKind.EASE_PACE, engine.latestSuggestion?.kind)
        assertEquals(Phase.RECOVERY, engine.phase)
        // 回復完了 (= 次サイクル開始) で提案はクリア
        assertEquals(SessionEvent.EnterHighIntensity, engine.onHeartRate(139, 215.seconds))
        assertNull(engine.latestSuggestion)
    }

    @Test
    fun `回復遅延 - 基準内なら提案は出ない`() {
        val engine = IntervalEngine(config.copy(targetCycles = 7))
        run(
            engine,
            listOf(
                0 to 141, 60 to 156, 120 to 139,    // サイクル1: 回復 60 秒 (基準)
                130 to 141, 190 to 156, 279 to 139, // サイクル2: 回復 89 秒 (< 60×1.5=90)
            ),
        )
        kotlin.test.assertFalse(engine.fatigueBrakeFired)
        kotlin.test.assertNull(engine.latestSuggestion)
        assertEquals(Phase.HIGH_INTENSITY, engine.phase)
        assertEquals(listOf(60.seconds, 89.seconds), engine.recoveryDurations)
    }

    @Test
    fun `回復遅延 - targetCycles=1 では提案も出ない (基準を設定して終了)`() {
        val engine = IntervalEngine(config.copy(targetCycles = 1))
        run(engine, listOf(0 to 141, 60 to 156, 120 to 139))
        assertEquals(Phase.FINISHED, engine.phase)
        kotlin.test.assertFalse(engine.fatigueBrakeFired)
        kotlin.test.assertNull(engine.latestSuggestion)
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

    // ----- 目標 cadence (UI tempo のためだけの設定値) -----

    @Test
    fun `activeTargetCadence - フェーズに応じて high と recovery を返す`() {
        val engine = IntervalEngine(config.copy(targetCadenceHigh = 140, targetCadenceRecovery = 80))
        // 高強度フェーズ
        assertEquals(140, engine.activeTargetCadence())
        // 回復フェーズへ
        run(engine, listOf(0 to 141, 60 to 156))
        assertEquals(Phase.RECOVERY, engine.phase)
        assertEquals(80, engine.activeTargetCadence())
    }

    // ----- 上限到達後の上限保持 (auto-decay 廃止後) -----

    @Test
    fun `上限到達で回復に入っても upperBpm は不変 (auto-decay 廃止)`() {
        val engine = IntervalEngine(config.copy(targetCycles = 5))
        assertEquals(155, engine.upperBpm)
        run(engine, listOf(0 to 141, 60 to 156))
        // 上限 155 のまま
        assertEquals(155, engine.upperBpm)
        run(engine, listOf(120 to 139, 130 to 141, 200 to 156))
        assertEquals(155, engine.upperBpm)
    }

}
