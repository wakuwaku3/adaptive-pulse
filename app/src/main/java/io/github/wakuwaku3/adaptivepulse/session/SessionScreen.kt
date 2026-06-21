package io.github.wakuwaku3.adaptivepulse.session

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import io.github.wakuwaku3.adaptivepulse.core.Phase
import io.github.wakuwaku3.adaptivepulse.ui.IconActionButton
import io.github.wakuwaku3.adaptivepulse.ui.KeepScreenOn
import io.github.wakuwaku3.adaptivepulse.ui.appVersionName
import io.github.wakuwaku3.adaptivepulse.ui.theme.APColors
import kotlin.time.Duration

@Composable
fun SessionScreen(
    state: SessionUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenSettings: () -> Unit,
    onAdjustThreshold: (Int) -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            SessionUiState.Idle -> IdleScreen(onStart = onStart, onOpenSettings = onOpenSettings)
            is SessionUiState.Running -> RunningScreen(state, onStop = onStop, onAdjustThreshold = onAdjustThreshold)
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
private fun RunningScreen(
    state: SessionUiState.Running,
    onStop: () -> Unit,
    onAdjustThreshold: (Int) -> Unit,
) {
    // 画面オフで Health Services のサンプリングが落ちる挙動を避けるため、
    // セッション中は強制的に画面 ON を維持する (実機 FB)
    KeepScreenOn()

    val (label, color) = when {
        // 下限閾値を超えるまでは計測対象外のウォームアップ区間 (画面が嘘をつかない)
        state.isWarmingUp -> "WARM-UP" to APColors.WarmUp
        state.phase == Phase.HIGH_INTENSITY -> "HIGH" to APColors.High
        state.phase == Phase.RECOVERY -> "RECOVER" to APColors.Recover
        else -> "DONE" to APColors.Done
    }
    // ring 進捗: currentCycle = 完走 (上限到達による回復遷移) 済みのサイクル数。
    // 回復中はその cycle の前半 (高強度) が終わって後半に居ると見なし 0.5 引く
    val ringProgress =
        (state.currentCycle - if (state.phase == Phase.RECOVERY) 0.5f else 0f) /
            state.finalCycle.toFloat()

    CycleRing(progress = ringProgress, color = color)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(0.dp),
        modifier = Modifier.rotaryThresholdAdjust(onAdjustThreshold),
    ) {
        Text(text = label, color = color, style = MaterialTheme.typography.title3)
        Row(verticalAlignment = Alignment.Bottom) {
            // ボタン行追加でラウンド枠を超えていたので display1 (40sp) → display2 (34sp) に縮小。
            // 数字は依然として画面内で最大サイズ (要件「心拍数字は最大サイズ」を満たす)
            Text(
                text = state.bpm?.toString() ?: "--",
                color = color,
                style = MaterialTheme.typography.display2,
            )
            Text(
                text = "bpm",
                color = APColors.TextDim,
                style = MaterialTheme.typography.caption2,
                modifier = Modifier.padding(start = 3.dp, bottom = 6.dp),
            )
        }
        // ▲/▼ + 残サイクルを 1 行にまとめ、両端に閾値と同色の裸グリフ ± を置く。
        // チップを外し、テキストの一部のように見える「触れる文字」として扱う
        val activeUpper = state.phase == Phase.HIGH_INTENSITY
        val thresholdColor = if (activeUpper) APColors.High else APColors.Recover
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NudgeGlyph(glyph = "−", color = thresholdColor, onClick = { onAdjustThreshold(-1) })
            Text(
                text = "▲${state.upperBpm} ▼${state.lowerBpm} · ${state.currentCycle}/${state.finalCycle}",
                color = thresholdColor,
                style = MaterialTheme.typography.caption2,
            )
            NudgeGlyph(glyph = "+", color = thresholdColor, onClick = { onAdjustThreshold(+1) })
        }
        // T = total / C = current cycle / P = current phase の経過時間
        Text(
            text = "T ${format(state.elapsed)} · C ${format(state.cycleElapsed)} · P ${format(state.phaseElapsed)}",
            color = APColors.TextDim,
            style = MaterialTheme.typography.caption2,
        )
        // kcal と cadence (SPM) は同じ caption2 行にまとめる (watch 画面は縦の余白がシビアなので追加行を避ける)
        val extras = buildList {
            state.calories?.let { add("${it.toInt()} kcal") }
            state.currentCadenceSpm?.let { add("${it.toInt()} spm") }
        }
        if (extras.isNotEmpty()) {
            Text(
                text = extras.joinToString(" · "),
                color = APColors.TextDim,
                style = MaterialTheme.typography.caption2,
            )
        }
        Box(modifier = Modifier.padding(top = 6.dp)) {
            StopButton(onClick = onStop)
        }
    }
}

/** 閾値テキストの両脇に置く、ボタン感を抑えた裸の ± グリフ */
@Composable
private fun NudgeGlyph(glyph: String, color: Color, onClick: () -> Unit) {
    Text(
        text = glyph,
        color = color,
        fontSize = 18.sp,
        modifier = Modifier
            .clip(CircleShape)
            .clickable(
                // ripple は重い印象を生むので消す
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            // tap 領域確保 (見た目より広く取る)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** Stop: 塗り円ではなく、ヘアライン外周 + 小さな実塗り正方形 (designed-stop) */
@Composable
private fun StopButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .border(width = 1.dp, color = APColors.TextDim, shape = CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(APColors.TextDim),
        )
    }
}

/**
 * クラウン回転で閾値を ±1 する modifier。
 * Wear OS のクラウンは 1 detent で数十 px 分の verticalScrollPixels を出すので、
 * 蓄積して [PIXELS_PER_STEP] ごとに 1 ステップ発火する (高速回転時に取りこぼさない)。
 */
@Composable
private fun Modifier.rotaryThresholdAdjust(onStep: (Int) -> Unit): Modifier {
    val focusRequester = remember { FocusRequester() }
    var accum by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    return this
        .focusRequester(focusRequester)
        .focusable()
        .onRotaryScrollEvent { event ->
            accum += event.verticalScrollPixels
            while (accum >= PIXELS_PER_STEP) {
                onStep(+1)
                accum -= PIXELS_PER_STEP
            }
            while (accum <= -PIXELS_PER_STEP) {
                onStep(-1)
                accum += PIXELS_PER_STEP
            }
            true
        }
}

private const val PIXELS_PER_STEP = 48f

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
