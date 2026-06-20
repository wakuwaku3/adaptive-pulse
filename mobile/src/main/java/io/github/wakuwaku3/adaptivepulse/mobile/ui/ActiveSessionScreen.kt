package io.github.wakuwaku3.adaptivepulse.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.wakuwaku3.adaptivepulse.core.sync.LivePhase
import io.github.wakuwaku3.adaptivepulse.core.sync.SessionLiveSnapshot

/**
 * watch から流れてくるライブ状態を視野の端でも読める密度で表示する画面。
 * 機材コンソール上に phone を置く想定で、心拍数字を最大サイズで中央に、
 * 周辺に経過時間・サイクル・閾値・cadence を並べる。タップ操作は受け付けない (watch 側で完結)。
 */
@Composable
fun ActiveSessionScreen(snapshot: SessionLiveSnapshot, modifier: Modifier = Modifier) {
    val color = colorFor(snapshot.phase)
    Box(
        modifier = modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            PhaseBadge(snapshot.phase, color)
            // 心拍数字 = この画面の主役。視野の端で読めるよう超大サイズ
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = snapshot.bpm?.toString() ?: "--",
                    color = color,
                    fontSize = 180.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "bpm",
                    color = MobileColors.TextDim,
                    fontSize = 24.sp,
                    modifier = Modifier.padding(start = 8.dp, bottom = 36.dp),
                )
            }
            CycleAndThresholds(snapshot, color)
            Timers(snapshot)
            Extras(snapshot)
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
            .padding(horizontal = 18.dp, vertical = 6.dp),
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
private fun CycleAndThresholds(snapshot: SessionLiveSnapshot, color: Color) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${snapshot.currentCycle}/${snapshot.finalCycle}",
            color = MobileColors.TextDim,
            fontSize = 28.sp,
        )
        Dot()
        Text(
            text = "▲${snapshot.upperBpm}",
            color = color,
            fontSize = 28.sp,
        )
        Text(
            text = "▼${snapshot.lowerBpm}",
            color = color,
            fontSize = 28.sp,
        )
    }
}

@Composable
private fun Timers(snapshot: SessionLiveSnapshot) {
    Text(
        text = "T ${formatSeconds(snapshot.elapsedSec)}  ·  " +
            "C ${formatSeconds(snapshot.cycleElapsedSec)}  ·  " +
            "P ${formatSeconds(snapshot.phaseElapsedSec)}",
        color = MobileColors.TextDim,
        fontSize = 22.sp,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun Extras(snapshot: SessionLiveSnapshot) {
    val parts = buildList {
        snapshot.calories?.let { add("${it.toInt()} kcal") }
        snapshot.currentRps?.let { add("%.1f Hz · %d spm".format(it, (it * 60).toInt())) }
    }
    if (parts.isNotEmpty()) {
        Text(
            text = parts.joinToString("   ·   "),
            color = MobileColors.TextDim,
            fontSize = 20.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Dot() {
    Box(
        modifier = Modifier
            .size(6.dp)
            .clip(CircleShape)
            .background(MobileColors.TextDim),
    )
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
