package io.github.wakuwaku3.adaptivepulse.session

import io.github.wakuwaku3.adaptivepulse.core.IntervalEngine
import io.github.wakuwaku3.adaptivepulse.core.Phase
import io.github.wakuwaku3.adaptivepulse.core.SessionConfig
import io.github.wakuwaku3.adaptivepulse.core.SessionEvent
import io.github.wakuwaku3.adaptivepulse.core.SessionMetrics
import io.github.wakuwaku3.adaptivepulse.core.SessionPhaseSnapshot
import io.github.wakuwaku3.adaptivepulse.core.cadence.CadenceTier
import io.github.wakuwaku3.adaptivepulse.core.cadence.RollingMedian
import io.github.wakuwaku3.adaptivepulse.hr.ExerciseSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 完走 / 部分終了したセッションの結果 (履歴 SessionRecord の材料) */
data class SessionResult(
    val cycles: Int,
    val elapsed: Duration,
    val calories: Double?,
    val zoneRatio: Double?,
    val highDurations: List<Duration>,
    val recoveryDurations: List<Duration>,
    val fatigueBrake: Boolean,
    val avgBpm: Int?,
    val maxBpm: Int?,
    val config: SessionConfig,
    /** セッション最終時点の高強度 target cadence。次セッションに持ち越す */
    val finalTargetCadenceHigh: Double,
    /** セッション最終時点の回復 target cadence */
    val finalTargetCadenceRecovery: Double,
    /**
     * セッション中に SPM 計測で確定した tier。warm-up + discovery 窓を抜けないまま
     * 終わったセッションでは null。後追いで「どのロジックで測ったセッションか」を
     * 評価するため履歴に残す (FB 2026-06-22)。
     */
    val lockedCadenceTier: CadenceTier? = null,
)

/**
 * IntervalEngine とデータソースをつなぐ実行ループ。
 *
 * pace-metric Phase B の制御ループは engine 側に持ち、Runner は cadence 観測値の
 * 平滑化 (RollingMedian) と target 値の UI 反映だけを担当する。
 * 初期 target cadence は呼び出し側 (前回最終値 or seed) から注入する。
 */
class SessionRunner(
    private val config: SessionConfig,
    private val sourceFactory: (sessionPhase: () -> SessionPhaseSnapshot) -> ExerciseSource,
    private val onSessionEvent: (SessionEvent) -> Unit,
    private val onState: (SessionUiState) -> Unit,
    initialTargetCadenceHigh: Double = config.seedTargetCadenceHigh,
    initialTargetCadenceRecovery: Double = config.seedTargetCadenceRecovery,
    timeSource: TimeSource = TimeSource.Monotonic,
) {
    private val engine = IntervalEngine(
        config = config,
        initialTargetCadenceHigh = initialTargetCadenceHigh,
        initialTargetCadenceRecovery = initialTargetCadenceRecovery,
    )
    private val metrics = SessionMetrics(config)
    private val mark = timeSource.markNow()
    private var lastBpm: Int? = null
    private var calories: Double? = null
    // データソースが確定したロジック (lock tier)。null = warm-up/discovery 終了前
    private var lockedCadenceTier: CadenceTier? = null
    // 瞬時値はノイジーなので 5 秒窓の median を掛ける (pace-metric note の方針)
    private val cadenceSpmWindow = RollingMedian(window = 5.seconds)

    /** 現フェーズの閾値を delta だけ動かす (UI のクラウン回転から呼ぶ) */
    fun adjustActiveThreshold(delta: Int) {
        engine.adjustActiveThreshold(delta)
        update(event = null)
    }

    /**
     * 現フェーズの目標 cadence を delta だけ動かす (phone の ± ボタンから呼ぶ)。
     * 次 cycle 完了時に engine の制御ループが上書きしうる。
     */
    fun adjustActiveTargetCadence(delta: Double) {
        engine.adjustActiveTargetCadence(delta)
        update(event = null)
    }

    /** セッション完走で結果を返す。キャンセル時は [snapshot] で部分結果を取れる */
    suspend fun run(): SessionResult = coroutineScope {
        update(event = null)

        val ticker = launch {
            while (isActive) {
                delay(1.seconds)
                update(engine.onTimePassed(mark.elapsedNow()))
            }
        }

        sourceFactory { SessionPhaseSnapshot(engine.phase, engine.isWarmingUp) }.samples().first { sample ->
            lastBpm = sample.bpm
            sample.totalCalories?.let { calories = it }
            sample.stepsPerMinute?.let {
                cadenceSpmWindow.add(mark.elapsedNow(), it)
                // engine の制御ループ anchor 用に実測 cadence を流す (pace-metric Q2 修正)
                engine.onCadenceSample(it)
            }
            // データソース側で確定した tier を覚えておく。tier は一度確定したら変わらない (lock)
            sample.cadenceTier?.let { lockedCadenceTier = it }
            metrics.onHeartRate(sample.bpm)
            update(engine.onHeartRate(sample.bpm, mark.elapsedNow()))
            engine.phase == Phase.FINISHED
        }
        ticker.cancel()

        snapshot()
    }

    fun snapshot(): SessionResult = SessionResult(
        cycles = engine.currentCycle,
        elapsed = mark.elapsedNow(),
        calories = calories,
        zoneRatio = metrics.zoneRatio,
        highDurations = engine.highDurations,
        recoveryDurations = engine.recoveryDurations,
        fatigueBrake = engine.fatigueBrakeFired,
        avgBpm = metrics.avgBpm,
        maxBpm = metrics.maxBpm,
        config = config,
        finalTargetCadenceHigh = engine.targetCadenceHigh,
        finalTargetCadenceRecovery = engine.targetCadenceRecovery,
        lockedCadenceTier = lockedCadenceTier,
    )

    private fun update(event: SessionEvent?) {
        event?.let(onSessionEvent)
        val elapsed = mark.elapsedNow()
        onState(
            if (engine.phase == Phase.FINISHED) {
                SessionUiState.Finished(
                    cycles = engine.currentCycle,
                    elapsed = elapsed,
                    calories = calories,
                    zoneRatio = metrics.zoneRatio,
                )
            } else {
                SessionUiState.Running(
                    bpm = lastBpm,
                    phase = engine.phase,
                    isWarmingUp = engine.isWarmingUp,
                    currentCycle = engine.currentCycle,
                    finalCycle = engine.finalCycle,
                    elapsed = elapsed,
                    cycleElapsed = elapsed - engine.cycleStartedAt,
                    phaseElapsed = elapsed - engine.phaseStartedAt,
                    calories = calories,
                    upperBpm = engine.upperBpm,
                    lowerBpm = engine.lowerBpm,
                    currentCadenceSpm = cadenceSpmWindow.median(elapsed),
                    targetCadenceHigh = engine.targetCadenceHigh,
                    targetCadenceRecovery = engine.targetCadenceRecovery,
                )
            },
        )
    }
}
