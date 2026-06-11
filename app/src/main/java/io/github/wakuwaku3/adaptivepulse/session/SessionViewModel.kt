package io.github.wakuwaku3.adaptivepulse.session

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.wakuwaku3.adaptivepulse.core.IntervalEngine
import io.github.wakuwaku3.adaptivepulse.core.Phase
import io.github.wakuwaku3.adaptivepulse.core.SessionConfig
import io.github.wakuwaku3.adaptivepulse.core.SessionEvent
import io.github.wakuwaku3.adaptivepulse.hr.HeartRateSource
import io.github.wakuwaku3.adaptivepulse.hr.SyntheticHeartRateSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface SessionUiState {
    data object Idle : SessionUiState

    data class Running(
        val bpm: Int?,
        val phase: Phase,
        val currentCycle: Int,
        val finalCycle: Int,
        val elapsed: Duration,
    ) : SessionUiState

    data class Finished(
        val cycles: Int,
        val elapsed: Duration,
    ) : SessionUiState
}

/**
 * IntervalEngine と心拍ソースをつなぐ薄い glue。ロジックの正しさは core の
 * 単体テストが担保し、この層はエミュレータで動作確認する。
 * 画面オフ中の計測継続 (Foreground Service 化) は実機投入前に行う。
 */
class SessionViewModel(
    private val onSessionEvent: (SessionEvent) -> Unit,
    private val config: SessionConfig = SessionConfig(),
    private val sourceFactory: (phaseProvider: () -> Phase) -> HeartRateSource =
        { SyntheticHeartRateSource(it) },
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : ViewModel() {

    var uiState: SessionUiState by mutableStateOf(SessionUiState.Idle)
        private set

    private var job: Job? = null
    private var lastBpm: Int? = null

    fun start() {
        if (job != null) return
        val engine = IntervalEngine(config)
        val mark = timeSource.markNow()
        lastBpm = null
        update(engine, event = null, elapsed = Duration.ZERO)

        job = viewModelScope.launch {
            // 心拍サンプルが途絶えてもタイムアウト遷移が動くよう、tick を並走させる
            launch {
                while (isActive && engine.phase != Phase.FINISHED) {
                    delay(1.seconds)
                    update(engine, engine.onTimePassed(mark.elapsedNow()), mark.elapsedNow())
                }
            }
            sourceFactory { engine.phase }.heartRates().collect { bpm ->
                lastBpm = bpm
                update(engine, engine.onHeartRate(bpm, mark.elapsedNow()), mark.elapsedNow())
                if (engine.phase == Phase.FINISHED) stop(keepResult = true)
            }
        }
    }

    fun stop(keepResult: Boolean = false) {
        job?.cancel()
        job = null
        if (!keepResult) uiState = SessionUiState.Idle
    }

    private fun update(engine: IntervalEngine, event: SessionEvent?, elapsed: Duration) {
        event?.let(onSessionEvent)
        uiState = if (engine.phase == Phase.FINISHED) {
            SessionUiState.Finished(cycles = engine.currentCycle, elapsed = elapsed)
        } else {
            SessionUiState.Running(
                bpm = lastBpm,
                phase = engine.phase,
                currentCycle = engine.currentCycle,
                finalCycle = engine.finalCycle,
                elapsed = elapsed,
            )
        }
    }

    companion object {
        fun factory(
            vibrator: SessionVibrator,
            sourceFactory: (phaseProvider: () -> Phase) -> HeartRateSource,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SessionViewModel(
                    onSessionEvent = vibrator::vibrate,
                    sourceFactory = sourceFactory,
                )
            }
        }
    }
}
