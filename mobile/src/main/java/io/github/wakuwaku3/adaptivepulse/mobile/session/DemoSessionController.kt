package io.github.wakuwaku3.adaptivepulse.mobile.session

import io.github.wakuwaku3.adaptivepulse.core.SessionConfig
import io.github.wakuwaku3.adaptivepulse.core.sync.LivePhase
import io.github.wakuwaku3.adaptivepulse.core.sync.SessionLiveSnapshot
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * エミュレータで Active セッション画面のデザインを詰めるためのデモモード
 * (本物の watch が要らない見た目の確認用)。`BuildConfig.DEBUG` でのみ起動できる。
 *
 * 動き: WARM-UP → HIGH → RECOVERY を回り、最終サイクル完走で DONE → また WARM-UP で連続ループ。
 * target SPM は config の設定値 (targetCadenceHigh / targetCadenceRecovery) で固定。
 */
object DemoSessionController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active

    /**
     * HR の指数追従 gain (1 tick = 500ms ごとに残差をこの割合だけ詰める)。
     * 高強度フェーズの所要時間が pace-metric note の sweet spot (45〜90 秒, Buchheit & Laursen 2013)
     * 中央付近に着地するように調整: 0.015 で HIGH 約 65 秒、RECOVERY 約 50 秒、WARM-UP 約 70 秒。
     */
    private const val HR_APPROACH_GAIN = 0.015
    private const val HR_HIGH_OVERSHOOT = 6.0
    private const val HR_RECOVERY_OVERSHOOT = 10.0
    private const val HR_WARMUP_OVERSHOOT = 5.0

    // 内部状態 (start() でリセットされる)
    private var startedAtMs = 0L
    private var phaseStartMs = 0L
    private var cycleStartMs = 0L
    private var phase = LivePhase.WARM_UP
    private var bpmF = 105.0
    private var bpm = 105
    private var upperBpm = 0
    private var lowerBpm = 0
    private var targetCadenceHigh = 0
    private var targetCadenceRecovery = 0
    private var cycle = 0
    private var finalCycle = 7

    fun start() {
        if (_active.value) return
        val now = System.currentTimeMillis()
        startedAtMs = now
        phaseStartMs = now
        cycleStartMs = now
        phase = LivePhase.WARM_UP
        val config = SessionConfig()
        upperBpm = config.upperBpm
        lowerBpm = config.lowerBpm
        targetCadenceHigh = config.targetCadenceHigh
        targetCadenceRecovery = config.targetCadenceRecovery
        bpmF = 105.0
        bpm = 105
        cycle = 0
        _active.value = true
        push()
        job = scope.launch {
            while (isActive) {
                delay(500)
                tick()
                push()
            }
        }
    }

    /**
     * stop: watch 側と揃え、DONE フェーズに遷移して live snapshot は維持する。
     * 完了確認は [done] で行う (FB 2026-06-24)。
     */
    fun stop() {
        if (phase == LivePhase.DONE) return
        advanceTo(LivePhase.DONE)
        push()
    }

    /** Done 確認: live snapshot を消して dashboard に戻す */
    fun done() {
        job?.cancel()
        job = null
        _active.value = false
        LiveSessionStore.clear()
    }

    fun adjustThreshold(delta: Int) {
        when (phase) {
            LivePhase.RECOVERY ->
                lowerBpm = (lowerBpm + delta).coerceIn(100, upperBpm - 5)
            else ->
                upperBpm = (upperBpm + delta).coerceIn(lowerBpm + 5, 200)
        }
        push()
    }

    private fun tick() {
        when (phase) {
            LivePhase.WARM_UP -> {
                approachBpm(lowerBpm + HR_WARMUP_OVERSHOOT)
                if (bpm > lowerBpm) advanceTo(LivePhase.HIGH)
            }
            LivePhase.HIGH -> {
                approachBpm(upperBpm + HR_HIGH_OVERSHOOT)
                if (bpm > upperBpm) {
                    cycle++
                    cycleStartMs = phaseStartMs
                    if (cycle >= finalCycle) {
                        advanceTo(LivePhase.DONE)
                    } else {
                        advanceTo(LivePhase.RECOVERY)
                    }
                }
            }
            LivePhase.RECOVERY -> {
                approachBpm(lowerBpm - HR_RECOVERY_OVERSHOOT)
                if (bpm < lowerBpm) {
                    cycleStartMs = System.currentTimeMillis()
                    advanceTo(LivePhase.HIGH)
                }
            }
            LivePhase.DONE -> {
                // DONE は Done が押されるまで動かない (本体 watch の挙動と揃える)。
                // デモを次セッションでリスタートしたい場合は dashboard に戻ってから「Show demo session」を押し直す
            }
            LivePhase.TIMED -> {
                // デモは心拍トリガー型のみを回す (時間制の見た目確認は実機/エミュレータの watch 経由)
            }
        }
    }

    private fun approachBpm(target: Double) {
        bpmF += (target - bpmF) * HR_APPROACH_GAIN
        bpm = bpmF.roundToInt()
    }

    private fun advanceTo(next: LivePhase) {
        phase = next
        phaseStartMs = System.currentTimeMillis()
    }

    private fun push() {
        val now = System.currentTimeMillis()
        val elapsedSec = (now - startedAtMs) / 1000.0
        val phaseElapsedSec = (now - phaseStartMs) / 1000.0
        val cycleElapsedSec = (now - cycleStartMs) / 1000.0
        LiveSessionStore.update(
            SessionLiveSnapshot(
                schema = 5,
                updatedAtMs = now,
                phase = phase,
                bpm = if (phase == LivePhase.DONE) null else bpm,
                currentCycle = cycle,
                finalCycle = finalCycle,
                elapsedSec = elapsedSec,
                cycleElapsedSec = cycleElapsedSec,
                phaseElapsedSec = phaseElapsedSec,
                upperBpm = upperBpm,
                lowerBpm = lowerBpm,
                calories = elapsedSec * 0.4,
                targetCadenceHigh = targetCadenceHigh,
                targetCadenceRecovery = targetCadenceRecovery,
            ),
        )
    }
}
