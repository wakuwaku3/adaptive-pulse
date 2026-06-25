package io.github.wakuwaku3.adaptivepulse.session

import io.github.wakuwaku3.adaptivepulse.core.IntervalEngine
import io.github.wakuwaku3.adaptivepulse.core.Phase
import io.github.wakuwaku3.adaptivepulse.core.SessionConfig
import io.github.wakuwaku3.adaptivepulse.core.SessionEvent
import io.github.wakuwaku3.adaptivepulse.core.SessionMetrics
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
)

/**
 * IntervalEngine とデータソースをつなぐ実行ループ。
 */
class SessionRunner(
    private val config: SessionConfig,
    private val sourceFactory: (phase: () -> Phase) -> ExerciseSource,
    private val onSessionEvent: (SessionEvent) -> Unit,
    private val onState: (SessionUiState) -> Unit,
    timeSource: TimeSource = TimeSource.Monotonic,
) {
    private val engine = IntervalEngine(config = config)
    private val metrics = SessionMetrics(config)
    private val mark = timeSource.markNow()
    private var lastBpm: Int? = null
    private var calories: Double? = null

    /** 現フェーズの閾値を delta だけ動かす (UI のクラウン回転から呼ぶ) */
    fun adjustActiveThreshold(delta: Int) {
        engine.adjustActiveThreshold(delta)
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

        sourceFactory { engine.phase }.samples().first { sample ->
            lastBpm = sample.bpm
            sample.totalCalories?.let { calories = it }
            // engine を先に進めてから metrics に渡すことで、下限上向き超過の「境界サンプル」も
            // (= isWarmingUp が false に倒れた直後の状態で) 算入する
            val event = engine.onHeartRate(sample.bpm, mark.elapsedNow())
            metrics.onHeartRate(sample.bpm, inMeasurement = !engine.isWarmingUp)
            update(event)
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
    )

    /** Finished 状態に着地させる時、最後に出ていた提案を一緒に表示するため取り出す */
    fun snapshotSuggestion() = engine.latestSuggestion

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
                    suggestion = engine.latestSuggestion,
                )
            } else {
                SessionUiState.Running(
                    bpm = lastBpm,
                    phase = engine.phase,
                    isWarmingUp = engine.isWarmingUp,
                    currentCycle = engine.currentCycle,
                    finalCycle = engine.finalCycle,
                    elapsed = elapsed,
                    cycleElapsed = engine.cycleStartedAt?.let { elapsed - it } ?: Duration.ZERO,
                    phaseElapsed = elapsed - engine.phaseStartedAt,
                    calories = calories,
                    upperBpm = engine.upperBpm,
                    lowerBpm = engine.lowerBpm,
                    targetCadenceHigh = config.targetCadenceHigh,
                    targetCadenceRecovery = config.targetCadenceRecovery,
                    suggestion = engine.latestSuggestion,
                )
            },
        )
    }
}
