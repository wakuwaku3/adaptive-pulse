package io.github.wakuwaku3.adaptivepulse.mobile.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.wakuwaku3.adaptivepulse.core.sync.LivePhase
import io.github.wakuwaku3.adaptivepulse.core.sync.SessionLiveSnapshot
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * watch から流れてくるライブ状態を表示し、watch でできる操作も受ける画面。
 * 機材コンソール上に phone を置く想定で、心拍数字を最大サイズ、その下に
 * 閾値・ペース調整・停止を縦に並べる。
 *
 * 操作は MessageClient で watch に送り、結果は watch → live snapshot で
 * 反映される (ローカルで先回りで描かない = watch が真実の単一ソース)。
 */
@Composable
fun ActiveSessionScreen(
    snapshot: SessionLiveSnapshot,
    onAdjustThreshold: (Int) -> Unit,
    onAdjustTargetSpm: (Int) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val phaseColor = colorFor(snapshot.phase)
    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        // CycleRing を画面全体の枠として描く (watch と同じ視覚言語)
        CycleRing(progress = ringProgressFor(snapshot), color = phaseColor)
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PhaseBadge(snapshot.phase, phaseColor)
            HrDisplay(snapshot.bpm, phaseColor)
            CycleAndTimers(snapshot)
            Spacer(modifier = Modifier.height(4.dp))
            ThresholdControl(snapshot, phaseColor, onAdjustThreshold)
            PaceControl(snapshot, phaseColor, onAdjustTargetSpm)
            snapshot.calories?.let {
                Text(
                    text = "${it.toInt()} kcal",
                    color = MobileColors.TextDim,
                    fontSize = 18.sp,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            StopButton(onStop)
        }
    }
}

@Composable
private fun PhaseBadge(phase: LivePhase, color: Color) {
    val label = when (phase) {
        LivePhase.WARM_UP -> "WARM-UP"
        LivePhase.HIGH -> "HIGH"
        LivePhase.RECOVERY -> "RECOVER"
        LivePhase.DONE -> "DONE"
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.18f))
            .padding(horizontal = 20.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            color = color,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 4.sp,
        )
    }
}

@Composable
private fun HrDisplay(bpm: Int?, color: Color) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = bpm?.toString() ?: "--",
            color = color,
            fontSize = 160.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "bpm",
            color = MobileColors.TextDim,
            fontSize = 22.sp,
            modifier = Modifier.padding(start = 6.dp, bottom = 32.dp),
        )
    }
}

@Composable
private fun CycleAndTimers(snapshot: SessionLiveSnapshot) {
    Text(
        text = "${snapshot.currentCycle}/${snapshot.finalCycle}  cycles",
        color = MobileColors.TextDim,
        fontSize = 22.sp,
    )
    Text(
        text = "T ${formatSeconds(snapshot.elapsedSec)}  ·  " +
            "C ${formatSeconds(snapshot.cycleElapsedSec)}  ·  " +
            "P ${formatSeconds(snapshot.phaseElapsedSec)}",
        color = MobileColors.TextDim,
        fontSize = 18.sp,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun ThresholdControl(
    snapshot: SessionLiveSnapshot,
    activeColor: Color,
    onAdjust: (Int) -> Unit,
) {
    val activeUpper = snapshot.phase == LivePhase.HIGH || snapshot.phase == LivePhase.WARM_UP
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        NudgeButton(glyph = "−", color = activeColor, onClick = { onAdjust(-1) })
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "▲${snapshot.upperBpm}",
                color = if (activeUpper) activeColor else MobileColors.TextDim,
                fontSize = 24.sp,
                fontWeight = if (activeUpper) FontWeight.Bold else FontWeight.Normal,
            )
            Text(
                text = "▼${snapshot.lowerBpm}",
                color = if (!activeUpper) activeColor else MobileColors.TextDim,
                fontSize = 24.sp,
                fontWeight = if (!activeUpper) FontWeight.Bold else FontWeight.Normal,
            )
        }
        NudgeButton(glyph = "+", color = activeColor, onClick = { onAdjust(+1) })
    }
}

@Composable
private fun PaceControl(
    snapshot: SessionLiveSnapshot,
    activeColor: Color,
    onAdjust: (Int) -> Unit,
) {
    val targetSpm = snapshot.targetSpm
    val targetHz = targetSpm / 60.0
    val currentSpm = snapshot.currentRps?.let { (it * 60).roundToInt() }
    // 拍動円: target tempo で pulse。色は target からのズレで within / hot / cool
    val diffColor = when {
        currentSpm == null -> MobileColors.TextDim
        abs(currentSpm - targetSpm) <= PACE_WITHIN_BAND -> activeColor
        currentSpm > targetSpm -> MobileColors.High
        else -> Color(0xFF54C7EC)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        NudgeButton(glyph = "−", color = activeColor, onClick = { onAdjust(-1) })
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PulseCircle(hz = targetHz, color = diffColor, sizeDp = 28)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "$targetSpm spm",
                    color = activeColor,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = currentSpm?.let { "now $it spm" } ?: "now —",
                color = MobileColors.TextDim,
                fontSize = 14.sp,
            )
        }
        NudgeButton(glyph = "+", color = activeColor, onClick = { onAdjust(+1) })
    }
}

@Composable
private fun NudgeButton(glyph: String, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.14f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = glyph, color = color, fontSize = 28.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun StopButton(onClick: () -> Unit) {
    // watch と同じデザイン言語: ヘアライン外周円 + 中央に小さな実塗り正方形 (designed-stop)
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .border(width = 1.5.dp, color = MobileColors.TextDim, shape = CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(modifier = Modifier.size(22.dp).background(MobileColors.TextDim))
    }
}

@Composable
private fun PulseCircle(hz: Double, color: Color, sizeDp: Int) {
    if (hz <= 0.0) {
        Box(
            modifier = Modifier.size(sizeDp.dp).clip(CircleShape).background(color),
        )
        return
    }
    val periodMs = (1000.0 / hz).toInt().coerceAtLeast(120)
    val transition = rememberInfiniteTransition(label = "pulse")
    val scale by transition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = periodMs, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse-scale",
    )
    Box(modifier = Modifier.size(sizeDp.dp), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size((sizeDp * scale).dp)
                .clip(CircleShape)
                .background(color),
        )
    }
}

@Composable
private fun CycleRing(progress: Float, color: Color) {
    Canvas(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        val stroke = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
        drawArc(
            color = Color(0xFF20242B),
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            style = stroke,
            topLeft = Offset.Zero,
            size = size,
        )
        if (progress > 0f) {
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 360f * progress.coerceIn(0f, 1f),
                useCenter = false,
                style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round),
                topLeft = Offset.Zero,
                size = size,
            )
        }
    }
}

private fun ringProgressFor(snapshot: SessionLiveSnapshot): Float {
    if (snapshot.finalCycle <= 0) return 0f
    // 回復中は前半 (高強度) が終わって後半に居る = 0.5 引く (watch RunningScreen と同じ式)
    val base = snapshot.currentCycle - if (snapshot.phase == LivePhase.RECOVERY) 0.5f else 0f
    return base / snapshot.finalCycle.toFloat()
}

private fun colorFor(phase: LivePhase): Color = when (phase) {
    LivePhase.WARM_UP -> Color(0xFF54C7EC)
    LivePhase.HIGH -> MobileColors.High
    LivePhase.RECOVERY -> MobileColors.Recover
    LivePhase.DONE -> MobileColors.Done
}

private fun formatSeconds(seconds: Double): String {
    val total = seconds.toInt().coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

/** target との差がこの SPM 以内ならニュートラル色 (within band) と判定 */
private const val PACE_WITHIN_BAND = 3
