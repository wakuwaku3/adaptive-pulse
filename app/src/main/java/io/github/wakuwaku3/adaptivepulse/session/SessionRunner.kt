package io.github.wakuwaku3.adaptivepulse.session

import io.github.wakuwaku3.adaptivepulse.core.Phase
import io.github.wakuwaku3.adaptivepulse.core.PlanEngine
import io.github.wakuwaku3.adaptivepulse.core.SessionConfig
import io.github.wakuwaku3.adaptivepulse.core.SessionEvent
import io.github.wakuwaku3.adaptivepulse.core.SessionMetrics
import io.github.wakuwaku3.adaptivepulse.core.menu.MenuKind
import io.github.wakuwaku3.adaptivepulse.core.menu.SessionPlan
import io.github.wakuwaku3.adaptivepulse.core.sync.LivePhase
import io.github.wakuwaku3.adaptivepulse.core.sync.SegmentSnapshot
import io.github.wakuwaku3.adaptivepulse.core.sync.SessionPlanSnapshot
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
    val plannedCycles: Int,
    val elapsed: Duration,
    val calories: Double?,
    val zoneRatio: Double?,
    val highDurations: List<Duration>,
    val recoveryDurations: List<Duration>,
    val fatigueBrake: Boolean,
    val avgBpm: Int?,
    val maxBpm: Int?,
    val config: SessionConfig,
    /** 実行したプランとセグメントごとの実績 (プログラム 1 実行 = 1 セッション) */
    val plan: SessionPlanSnapshot,
)

/**
 * PlanEngine とデータソースをつなぐ実行ループ。
 */
class SessionRunner(
    private val plan: SessionPlan,
    private val config: SessionConfig,
    private val sourceFactory: (phase: () -> Phase) -> ExerciseSource,
    private val onSessionEvent: (SessionEvent) -> Unit,
    private val onState: (SessionUiState) -> Unit,
    timeSource: TimeSource = TimeSource.Monotonic,
) {
    private val engine = PlanEngine(plan, config)
    private val metrics = SessionMetrics()
    private val mark = timeSource.markNow()
    private var lastBpm: Int? = null
    private var calories: Double? = null

    /** 現フェーズの閾値を delta だけ動かす (UI のクラウン回転から呼ぶ)。時間制中は no-op */
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

        sourceFactory { engine.sourcePhase }.samples().first { sample ->
            lastBpm = sample.bpm
            sample.totalCalories?.let { calories = it }
            // engine を先に進めてから metrics に渡すことで、下限上向き超過の「境界サンプル」も
            // (= isWarmingUp が false に倒れた直後の状態で) 算入する
            val event = engine.onHeartRate(sample.bpm, mark.elapsedNow())
            metrics.onHeartRate(
                sample.bpm,
                inMeasurement = engine.inMeasurement,
                lowerBpm = engine.activeLowerBpm,
                upperBpm = engine.activeUpperBpm,
            )
            update(event)
            engine.finished
        }
        ticker.cancel()

        snapshot()
    }

    fun snapshot(): SessionResult {
        val elapsed = mark.elapsedNow()
        return SessionResult(
            cycles = engine.cycles,
            plannedCycles = engine.plannedCycles,
            elapsed = elapsed,
            calories = calories,
            zoneRatio = metrics.zoneRatio,
            highDurations = engine.highDurations,
            recoveryDurations = engine.recoveryDurations,
            fatigueBrake = engine.fatigueBrakeFired,
            avgBpm = metrics.avgBpm,
            maxBpm = metrics.maxBpm,
            config = config,
            plan = planSnapshot(elapsed),
        )
    }

    /** Finished 状態に着地させる時、最後に出ていた提案を一緒に表示するため取り出す */
    fun snapshotSuggestion() = engine.latestSuggestion

    private fun planSnapshot(elapsed: Duration): SessionPlanSnapshot = SessionPlanSnapshot(
        programId = plan.programId,
        name = plan.name,
        segments = engine.segmentResults(elapsed).map { result ->
            val menu = result.segment.menu
            val (type, upper, lower) = when (val kind = menu.kind) {
                is MenuKind.Interval -> Triple("interval", kind.upperBpm, kind.lowerBpm)
                is MenuKind.Timed -> Triple("timed", kind.upperBpm, kind.lowerBpm)
            }
            SegmentSnapshot(
                menuId = menu.id,
                menuName = menu.name,
                type = type,
                upperBpm = upper,
                lowerBpm = lower,
                plannedAmount = result.segment.amount,
                completedCycles = result.completedCycles,
                elapsedSec = result.elapsed.inWholeMilliseconds / 1000.0,
                highDurationsSec = result.highDurations.map { it.inWholeMilliseconds / 1000.0 },
                recoveryDurationsSec = result.recoveryDurations.map { it.inWholeMilliseconds / 1000.0 },
            )
        },
    )

    private fun update(event: SessionEvent?) {
        event?.let(onSessionEvent)
        val elapsed = mark.elapsedNow()
        onState(
            if (engine.finished) {
                SessionUiState.Finished(
                    cycles = engine.cycles,
                    elapsed = elapsed,
                    calories = calories,
                    zoneRatio = metrics.zoneRatio,
                    suggestion = engine.latestSuggestion,
                )
            } else {
                val interval = engine.intervalEngine
                SessionUiState.Running(
                    bpm = lastBpm,
                    phase = when {
                        interval == null -> LivePhase.TIMED
                        interval.isWarmingUp -> LivePhase.WARM_UP
                        interval.phase == Phase.RECOVERY -> LivePhase.RECOVERY
                        else -> LivePhase.HIGH
                    },
                    currentCycle = interval?.currentCycle ?: 0,
                    finalCycle = interval?.finalCycle ?: 0,
                    elapsed = elapsed,
                    cycleElapsed = engine.cycleElapsed(elapsed),
                    phaseElapsed = engine.phaseElapsed(elapsed),
                    calories = calories,
                    upperBpm = engine.activeUpperBpm,
                    lowerBpm = engine.activeLowerBpm,
                    targetCadenceHigh = engine.activeCadenceHigh,
                    targetCadenceRecovery = engine.activeCadenceRecovery,
                    suggestion = engine.latestSuggestion,
                    menuName = engine.currentSegment.menu.name,
                    menuIndex = engine.segmentIndex,
                    menuCount = engine.segmentCount,
                    timedTarget = engine.timedEngine?.duration,
                )
            },
        )
    }
}
