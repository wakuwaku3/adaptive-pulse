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
    /** 高強度→回復まで完走したサイクル数。中断時は未完了サイクルを含めない */
    val cycles: Int,
    val elapsed: Duration,
    val calories: Double?,
    val zoneRatio: Double?,
    val highDurations: List<Duration>,
    val fatigueBrake: Boolean,
    val avgBpm: Int?,
    val maxBpm: Int?,
    val config: SessionConfig,
)

/**
 * IntervalEngine とデータソースをつなぐ実行ループ。ホスト (Foreground Service) から
 * 切り離して保持し、ロジックの正しさは core のテスト、この層はエミュレータで確認する。
 *
 * 中断 (手動停止 / 異常終了) でも履歴に残せるよう、現時点の状態を SessionResult に
 * 落とせる [snapshot] を公開する。
 */
class SessionRunner(
    private val config: SessionConfig,
    private val sourceFactory: (phaseProvider: () -> Phase) -> ExerciseSource,
    private val onSessionEvent: (SessionEvent) -> Unit,
    private val onState: (SessionUiState) -> Unit,
    timeSource: TimeSource = TimeSource.Monotonic,
) {
    private val engine = IntervalEngine(config)
    private val metrics = SessionMetrics(config)
    private val mark = timeSource.markNow()
    private var lastBpm: Int? = null
    private var calories: Double? = null

    /** セッション完走で結果を返す。キャンセル時は [snapshot] で部分結果を取れる */
    suspend fun run(): SessionResult = coroutineScope {
        update(event = null)

        // 心拍サンプルが途絶えてもタイムアウト遷移が動くよう、tick を並走させる
        val ticker = launch {
            while (isActive) {
                delay(1.seconds)
                update(engine.onTimePassed(mark.elapsedNow()))
            }
        }

        // first(predicate) は条件成立でアップストリームを閉じる = 終了時にソースも止まる
        sourceFactory { engine.phase }.samples().first { sample ->
            lastBpm = sample.bpm
            sample.totalCalories?.let { calories = it }
            metrics.onHeartRate(sample.bpm)
            update(engine.onHeartRate(sample.bpm, mark.elapsedNow()))
            engine.phase == Phase.FINISHED
        }
        ticker.cancel()

        snapshot()
    }

    fun snapshot(): SessionResult = SessionResult(
        cycles = engine.completedCycles,
        elapsed = mark.elapsedNow(),
        calories = calories,
        zoneRatio = metrics.zoneRatio,
        highDurations = engine.highDurations,
        fatigueBrake = engine.fatigueBrakeFired,
        avgBpm = metrics.avgBpm,
        maxBpm = metrics.maxBpm,
        config = config,
    )

    private fun update(event: SessionEvent?) {
        event?.let(onSessionEvent)
        val elapsed = mark.elapsedNow()
        onState(
            if (engine.phase == Phase.FINISHED) {
                SessionUiState.Finished(
                    cycles = engine.completedCycles,
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
                    calories = calories,
                )
            },
        )
    }
}
