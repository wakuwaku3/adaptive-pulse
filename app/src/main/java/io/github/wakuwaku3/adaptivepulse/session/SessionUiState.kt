package io.github.wakuwaku3.adaptivepulse.session

import io.github.wakuwaku3.adaptivepulse.core.Phase
import kotlin.time.Duration

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
