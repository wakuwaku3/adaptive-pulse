package io.github.wakuwaku3.adaptivepulse.mobile.session

import io.github.wakuwaku3.adaptivepulse.core.SessionConfig
import io.github.wakuwaku3.adaptivepulse.core.sync.LivePhase
import io.github.wakuwaku3.adaptivepulse.core.sync.SessionLiveSnapshot
import kotlin.math.roundToInt
import kotlin.random.Random
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
 * ペースは pace-metric Phase B 仕様で**動的に追従する target cadence** を実装する:
 *   - Day-1 seed (130/65) から開始
 *   - cycle 完了毎に観測 duration と target 窓を比較し、target を ±k×(deviation) 調整
 *   - 手動 ± も許容 (次 cycle の自動制御で上書きされうる)
 */
object DemoSessionController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active

    private const val SPM_APPROACH_GAIN = 0.12

    /**
     * HR の指数追従 gain (1 tick = 500ms ごとに残差をこの割合だけ詰める)。
     * 高強度フェーズの所要時間が pace-metric note の sweet spot (45〜90 秒, Buchheit & Laursen 2013)
     * 中央付近に着地するように調整: 0.015 で HIGH 約 65 秒、RECOVERY 約 50 秒、WARM-UP 約 70 秒。
     * これにより demo 実行中に target cadence が制御ループで動かない「sweet spot 維持」が見える。
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
    private var targetCadenceHigh = 0.0
    private var targetCadenceRecovery = 0.0
    private var currentSpmBase = 0.0
    private var cycle = 0
    private var finalCycle = 7

    // 制御ループ用パラメタ (config から引く)
    private lateinit var config: SessionConfig

    // pace-metric Q2: phase 毎の実測 cadence サンプル。cycle 完了時 median を anchor として使う
    private val highCadenceSamples = mutableListOf<Double>()
    private val recoveryCadenceSamples = mutableListOf<Double>()

    fun start() {
        if (_active.value) return
        val now = System.currentTimeMillis()
        startedAtMs = now
        phaseStartMs = now
        cycleStartMs = now
        phase = LivePhase.WARM_UP
        config = SessionConfig()
        upperBpm = config.upperBpm
        lowerBpm = config.lowerBpm
        targetCadenceHigh = config.seedTargetCadenceHigh
        targetCadenceRecovery = config.seedTargetCadenceRecovery
        bpmF = 105.0
        bpm = 105
        cycle = 0
        currentSpmBase = targetCadenceRecovery
        highCadenceSamples.clear()
        recoveryCadenceSamples.clear()
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

    fun stop() {
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

    /** 手動 ±: 次 cycle 完了時に制御ループが上書きしうる (= 「次サイクルまでの暫定指示」) */
    fun adjustTargetCadence(delta: Double) {
        when (phase) {
            LivePhase.RECOVERY ->
                targetCadenceRecovery = (targetCadenceRecovery + delta)
                    .coerceIn(30.0, targetCadenceHigh - 10.0)
            else ->
                targetCadenceHigh = (targetCadenceHigh + delta)
                    .coerceIn(targetCadenceRecovery + 10.0, 220.0)
        }
        push()
    }

    private fun tick() {
        when (phase) {
            LivePhase.WARM_UP -> {
                approachBpm(lowerBpm + HR_WARMUP_OVERSHOOT)
                approachSpm(targetCadenceRecovery)
                if (bpm > lowerBpm) advanceTo(LivePhase.HIGH)
            }
            LivePhase.HIGH -> {
                approachBpm(upperBpm + HR_HIGH_OVERSHOOT)
                approachSpm(targetCadenceHigh)
                // 実測 cadence (currentSpmBase) を anchor 計算用に蓄積
                highCadenceSamples += currentSpmBase
                if (bpm > upperBpm) {
                    val observedSec = (System.currentTimeMillis() - phaseStartMs) / 1000.0
                    updateTargetCadenceHigh(observedSec)
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
                approachSpm(targetCadenceRecovery)
                recoveryCadenceSamples += currentSpmBase
                if (bpm < lowerBpm) {
                    val observedSec = (System.currentTimeMillis() - phaseStartMs) / 1000.0
                    updateTargetCadenceRecovery(observedSec)
                    cycleStartMs = System.currentTimeMillis()
                    advanceTo(LivePhase.HIGH)
                }
            }
            LivePhase.DONE -> {
                val phaseElapsedMs = System.currentTimeMillis() - phaseStartMs
                if (phaseElapsedMs >= 5_000) {
                    startedAtMs = System.currentTimeMillis()
                    cycleStartMs = startedAtMs
                    cycle = 0
                    bpmF = 105.0
                    bpm = 105
                    // 次デモループでも持ち越し挙動を見たいので target はリセットしない
                    currentSpmBase = targetCadenceRecovery
                    advanceTo(LivePhase.WARM_UP)
                }
            }
        }
    }

    private fun updateTargetCadenceHigh(observedSec: Double) {
        val dMinSec = config.cadenceTargetHighDurationMin.inWholeMilliseconds / 1000.0
        val dMaxSec = config.cadenceTargetHighDurationMax.inWholeMilliseconds / 1000.0
        val k = config.cadenceControlGain
        // pace-metric Q2: 旧 target ではなく実測 cadence の median を anchor に使う
        val anchor = highCadenceSamples.medianOrNull() ?: targetCadenceHigh
        val correction = when {
            observedSec < dMinSec -> -k * (dMinSec - observedSec)
            observedSec > dMaxSec -> +k * (observedSec - dMaxSec)
            else -> 0.0
        }
        targetCadenceHigh = (anchor + correction)
            .coerceIn(targetCadenceRecovery + 10.0, 220.0)
        highCadenceSamples.clear()
    }

    private fun updateTargetCadenceRecovery(observedSec: Double) {
        val dMinSec = config.cadenceTargetRecoveryDurationMin.inWholeMilliseconds / 1000.0
        val dMaxSec = config.cadenceTargetRecoveryDurationMax.inWholeMilliseconds / 1000.0
        val k = config.cadenceControlGain
        val anchor = recoveryCadenceSamples.medianOrNull() ?: targetCadenceRecovery
        val correction = when {
            observedSec < dMinSec -> -k * (dMinSec - observedSec)
            observedSec > dMaxSec -> +k * (observedSec - dMaxSec)
            else -> 0.0
        }
        targetCadenceRecovery = (anchor + correction)
            .coerceIn(30.0, targetCadenceHigh - 10.0)
        recoveryCadenceSamples.clear()
    }

    private fun List<Double>.medianOrNull(): Double? {
        if (isEmpty()) return null
        val sorted = sorted()
        val n = size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
    }

    private fun approachBpm(target: Double) {
        bpmF += (target - bpmF) * HR_APPROACH_GAIN
        bpm = bpmF.roundToInt()
    }

    private fun approachSpm(target: Double) {
        currentSpmBase += (target - currentSpmBase) * SPM_APPROACH_GAIN
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
        val noisySpm = currentSpmBase + Random.nextDouble(-2.0, 2.0)
        LiveSessionStore.update(
            SessionLiveSnapshot(
                schema = 4,
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
                currentCadenceSpm = noisySpm,
                targetCadenceHigh = targetCadenceHigh,
                targetCadenceRecovery = targetCadenceRecovery,
            ),
        )
    }
}
