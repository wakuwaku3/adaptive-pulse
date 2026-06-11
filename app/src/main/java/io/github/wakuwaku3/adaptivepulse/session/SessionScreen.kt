package io.github.wakuwaku3.adaptivepulse.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import io.github.wakuwaku3.adaptivepulse.core.Phase
import kotlin.time.Duration

private val HighIntensityColor = Color(0xFFFF5252)
private val RecoveryColor = Color(0xFF69F0AE)

@Composable
fun SessionScreen(viewModel: SessionViewModel) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        when (val state = viewModel.uiState) {
            SessionUiState.Idle -> IdleScreen(onStart = viewModel::start)
            is SessionUiState.Running -> RunningScreen(state, onStop = { viewModel.stop() })
            is SessionUiState.Finished -> FinishedScreen(state, onReset = { viewModel.stop() })
        }
    }
}

@Composable
private fun IdleScreen(onStart: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = "AdaptivePulse", style = MaterialTheme.typography.title2)
        Chip(
            onClick = onStart,
            label = { Text("開始") },
            colors = ChipDefaults.primaryChipColors(),
        )
    }
}

@Composable
private fun RunningScreen(state: SessionUiState.Running, onStop: () -> Unit) {
    val (phaseLabel, phaseColor) = when (state.phase) {
        Phase.HIGH_INTENSITY -> "高強度" to HighIntensityColor
        Phase.RECOVERY -> "回復" to RecoveryColor
        Phase.FINISHED -> "終了" to MaterialTheme.colors.onBackground
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text = phaseLabel, color = phaseColor, style = MaterialTheme.typography.title2)
        Text(
            text = state.bpm?.toString() ?: "--",
            color = phaseColor,
            style = MaterialTheme.typography.display1,
        )
        Text(
            text = "サイクル ${state.currentCycle}/${state.finalCycle}  ${format(state.elapsed)}",
            style = MaterialTheme.typography.caption1,
        )
        CompactChip(
            onClick = onStop,
            label = { Text("停止") },
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun FinishedScreen(state: SessionUiState.Finished, onReset: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = "おつかれさま", style = MaterialTheme.typography.title2)
        Text(
            text = "${state.cycles} サイクル  ${format(state.elapsed)}",
            style = MaterialTheme.typography.body1,
        )
        Chip(
            onClick = onReset,
            label = { Text("OK") },
            colors = ChipDefaults.secondaryChipColors(),
        )
    }
}

private fun format(elapsed: Duration): String =
    elapsed.toComponents { minutes, seconds, _ -> "%d:%02d".format(minutes, seconds) }
