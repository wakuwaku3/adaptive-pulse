package io.github.wakuwaku3.adaptivepulse.session

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import io.github.wakuwaku3.adaptivepulse.core.Phase
import io.github.wakuwaku3.adaptivepulse.ui.IconActionButton
import io.github.wakuwaku3.adaptivepulse.ui.appVersionName
import io.github.wakuwaku3.adaptivepulse.ui.theme.APColors
import kotlin.time.Duration

@Composable
fun SessionScreen(
    state: SessionUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            SessionUiState.Idle -> IdleScreen(onStart = onStart, onOpenSettings = onOpenSettings)
            is SessionUiState.Running -> RunningScreen(state, onStop = onStop)
            is SessionUiState.Finished -> FinishedScreen(state, onReset = onStop)
        }
    }
}

@Composable
private fun IdleScreen(onStart: () -> Unit, onOpenSettings: () -> Unit) {
    CycleRing(progress = 0f, color = APColors.High)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = "ADAPTIVE",
            color = APColors.TextDim,
            style = MaterialTheme.typography.title3,
        )
        Text(
            text = "PULSE",
            color = APColors.Text,
            style = MaterialTheme.typography.title1,
        )
        // sideload 後にどの release が入っているかすぐ確認できるよう、Idle 画面に versionName を出す
        Text(
            text = "v${appVersionName()}",
            color = APColors.TextDim,
            style = MaterialTheme.typography.caption2,
        )
        // 操作はテキストラベルではなく記号アイコンの小円ボタンで表現 (ユーザ FB 2026-06-11)
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            IconActionButton(glyph = "▶", tint = Color.Black, background = APColors.High, onClick = onStart)
            IconActionButton(glyph = "⚙", tint = APColors.TextDim, background = APColors.StopChip, onClick = onOpenSettings)
        }
    }
}

@Composable
private fun RunningScreen(state: SessionUiState.Running, onStop: () -> Unit) {
    val (label, color) = when {
        // 下限閾値を超えるまでは計測対象外のウォームアップ区間 (画面が嘘をつかない)
        state.isWarmingUp -> "WARM-UP" to APColors.WarmUp
        state.phase == Phase.HIGH_INTENSITY -> "HIGH" to APColors.High
        state.phase == Phase.RECOVERY -> "RECOVER" to APColors.Recover
        else -> "DONE" to APColors.Done
    }
    // リングは完了サイクル数を示す。回復まで到達したサイクルは半分進んだ扱い
    val ringProgress =
        (state.currentCycle - 1 + if (state.phase == Phase.RECOVERY) 0.5f else 0f) /
            state.finalCycle

    CycleRing(progress = ringProgress, color = color)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        Text(text = label, color = color, style = MaterialTheme.typography.title3)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = state.bpm?.toString() ?: "--",
                color = color,
                style = MaterialTheme.typography.display1,
            )
            Text(
                text = "bpm",
                color = APColors.TextDim,
                style = MaterialTheme.typography.caption1,
                modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
            )
        }
        Text(
            text = buildString {
                append("CYCLE ${state.currentCycle}/${state.finalCycle} · ${format(state.elapsed)}")
                state.calories?.let { append(" · ${it.toInt()} kcal") }
            },
            color = APColors.TextDim,
            style = MaterialTheme.typography.caption1,
        )
        Box(modifier = Modifier.padding(top = 4.dp)) {
            IconActionButton(
                glyph = "■",
                tint = APColors.Text,
                background = APColors.StopChip,
                onClick = onStop,
            )
        }
    }
}

@Composable
private fun FinishedScreen(state: SessionUiState.Finished, onReset: () -> Unit) {
    CycleRing(progress = 1f, color = APColors.Done)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "DONE",
            color = APColors.Done,
            style = MaterialTheme.typography.title1,
        )
        Text(
            text = "${state.cycles} CYCLES · ${format(state.elapsed)}",
            color = APColors.Text,
            style = MaterialTheme.typography.body1,
            textAlign = TextAlign.Center,
        )
        val summary = listOfNotNull(
            state.calories?.let { "${it.toInt()} kcal" },
            state.zoneRatio?.let { "ZONE ${(it * 100).toInt()}%" },
        )
        if (summary.isNotEmpty()) {
            Text(
                text = summary.joinToString(" · "),
                color = APColors.TextDim,
                style = MaterialTheme.typography.caption1,
            )
        }
        Box(modifier = Modifier.padding(top = 6.dp)) {
            IconActionButton(
                glyph = "✓",
                tint = Color.Black,
                background = APColors.Done,
                onClick = onReset,
            )
        }
    }
}

/** 丸画面の縁に沿ってサイクル進捗を描くリング */
@Composable
private fun CycleRing(progress: Float, color: Color) {
    Canvas(modifier = Modifier.fillMaxSize().padding(4.dp)) {
        val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
        drawArc(
            color = APColors.RingTrack,
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            style = stroke,
        )
        if (progress > 0f) {
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 360f * progress.coerceIn(0f, 1f),
                useCenter = false,
                style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round),
            )
        }
    }
}

private fun format(elapsed: Duration): String =
    elapsed.toComponents { minutes, seconds, _ -> "%d:%02d".format(minutes, seconds) }
