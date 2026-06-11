package io.github.wakuwaku3.adaptivepulse.session

import io.github.wakuwaku3.adaptivepulse.core.IntervalEngine
import io.github.wakuwaku3.adaptivepulse.core.Phase
import io.github.wakuwaku3.adaptivepulse.core.SessionConfig
import io.github.wakuwaku3.adaptivepulse.core.SessionEvent
import io.github.wakuwaku3.adaptivepulse.hr.HeartRateSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * IntervalEngine と心拍ソースをつなぐ実行ループ。ホスト (Foreground Service) から
 * 切り離して保持し、ロジックの正しさは core のテスト、この層はエミュレータで確認する。
 */
class SessionRunner(
    private val config: SessionConfig,
    private val sourceFactory: (phaseProvider: () -> Phase) -> HeartRateSource,
    private val onSessionEvent: (SessionEvent) -> Unit,
    private val onState: (SessionUiState) -> Unit,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {

    /** セッション完走で正常リターンする。キャンセルで中断 */
    suspend fun run(): Unit = coroutineScope {
        val engine = IntervalEngine(config)
        val mark = timeSource.markNow()
        var lastBpm: Int? = null

        fun update(event: SessionEvent?, elapsed: Duration) {
            event?.let(onSessionEvent)
            onState(
                if (engine.phase == Phase.FINISHED) {
                    SessionUiState.Finished(cycles = engine.currentCycle, elapsed = elapsed)
                } else {
                    SessionUiState.Running(
                        bpm = lastBpm,
                        phase = engine.phase,
                        currentCycle = engine.currentCycle,
                        finalCycle = engine.finalCycle,
                        elapsed = elapsed,
                    )
                },
            )
        }

        update(event = null, elapsed = Duration.ZERO)

        // 心拍サンプルが途絶えてもタイムアウト遷移が動くよう、tick を並走させる
        val ticker = launch {
            while (isActive) {
                delay(1.seconds)
                update(engine.onTimePassed(mark.elapsedNow()), mark.elapsedNow())
            }
        }

        // first(predicate) は条件成立でアップストリームを閉じる = 終了時にソースも止まる
        sourceFactory { engine.phase }.heartRates().first { bpm ->
            lastBpm = bpm
            update(engine.onHeartRate(bpm, mark.elapsedNow()), mark.elapsedNow())
            engine.phase == Phase.FINISHED
        }
        ticker.cancel()
    }
}
