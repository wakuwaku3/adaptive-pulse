package io.github.wakuwaku3.adaptivepulse.mobile.ui

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.wakuwaku3.adaptivepulse.core.SessionSuggestion
import io.github.wakuwaku3.adaptivepulse.core.SuggestionKind
import io.github.wakuwaku3.adaptivepulse.core.sync.LivePhase
import io.github.wakuwaku3.adaptivepulse.core.sync.SessionLiveSnapshot
import kotlin.math.cos
import kotlin.math.sin

/**
 * watch から流れてくるライブ状態を表示し、操作も受ける phone 画面。
 * 機材コンソール上に置いて視野の端で読む想定。タップ操作は閾値 ± と停止/Done のみ。
 *
 * デザイン上の意図:
 * - HR (♥) を中央最大サイズで表示
 * - 回転体 (PaceEllipse) は active phase の target SPM (設定値) で回す。実測はしない
 * - DONE では楕円アニメを停止 (運動が終わっているのに回転が続くと違和感)
 * - DONE 中はボタンを ✓ (Done) にして、押すまで dashboard に戻さない (FB 2026-06-24)
 * - engine の行動提案 (ペース緩める / 中断) があれば日本語の banner で見せる。文言のみ日本語 (例外)
 */
@Composable
fun ActiveSessionScreen(
    snapshot: SessionLiveSnapshot,
    onAdjustThreshold: (Int) -> Unit,
    onStop: () -> Unit,
    onDone: () -> Unit,
) {
    val phaseColor = colorFor(snapshot.phase)
    val isDone = snapshot.phase == LivePhase.DONE
    Scaffold(containerColor = Color.Black) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                // 上下のシステム外の余白。下側は親指/戻る操作の干渉を避けて多めに
                .padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            PhaseBadge(snapshot.phase, phaseColor)
            snapshot.suggestion?.let { SuggestionBanner(it) }
            HeartRate(snapshot, phaseColor)
            CycleAndTimers(snapshot)
            Spacer(modifier = Modifier.height(2.dp))
            if (!isDone) {
                ThresholdControl(snapshot, phaseColor, onAdjustThreshold)
            }
            PaceDisplay(snapshot, phaseColor)
            snapshot.calories?.let {
                Text(
                    text = "${it.toInt()} kcal",
                    color = MobileColors.TextDim,
                    fontSize = 18.sp,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            if (isDone) DoneButton(phaseColor, onDone) else StopButton(onStop)
        }
    }
}

/**
 * 提案 banner: kind に応じた severity 色で枠を塗り、タイトル + 理由を日本語で出す。
 * UI 全体は英語だが、判断を促す説明は誤読しないよう日本語で出す (FB 2026-06-24)。
 */
@Composable
private fun SuggestionBanner(suggestion: SessionSuggestion) {
    val tint = when (suggestion.kind) {
        SuggestionKind.EASE_PACE -> MobileColors.Recover
        SuggestionKind.CONSIDER_STOP -> MobileColors.High
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(tint.copy(alpha = 0.12f))
            .border(width = 1.dp, color = tint.copy(alpha = 0.6f), shape = RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = suggestion.title,
            color = tint,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = suggestion.reason,
            color = MobileColors.Text,
            fontSize = 14.sp,
        )
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
            .padding(horizontal = 22.dp, vertical = 6.dp),
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
private fun HeartRate(snapshot: SessionLiveSnapshot, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Favorite,
                contentDescription = "bpm",
                tint = color,
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = snapshot.bpm?.toString() ?: "--",
                color = color,
                fontSize = 84.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(text = "bpm", color = MobileColors.TextDim, fontSize = 14.sp)
    }
}

@Composable
private fun CycleAndTimers(snapshot: SessionLiveSnapshot) {
    Text(
        text = "${snapshot.currentCycle}/${snapshot.finalCycle}  cycles",
        color = MobileColors.TextDim,
        fontSize = 18.sp,
    )
    Text(
        text = "T ${formatSeconds(snapshot.elapsedSec)}  ·  " +
            "C ${formatSeconds(snapshot.cycleElapsedSec)}  ·  " +
            "P ${formatSeconds(snapshot.phaseElapsedSec)}",
        color = MobileColors.TextDim,
        fontSize = 16.sp,
        textAlign = TextAlign.Center,
    )
}

/** bpm 閾値: ▲upper / ▼lower 両表示 + ± を左右に配置 + 単位 "bpm" */
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
                fontSize = 22.sp,
                fontWeight = if (activeUpper) FontWeight.Bold else FontWeight.Normal,
            )
            Text(
                text = "▼${snapshot.lowerBpm}",
                color = if (!activeUpper) activeColor else MobileColors.TextDim,
                fontSize = 22.sp,
                fontWeight = if (!activeUpper) FontWeight.Bold else FontWeight.Normal,
            )
            Text(text = "bpm", color = MobileColors.TextDim, fontSize = 13.sp)
        }
        NudgeButton(glyph = "+", color = activeColor, onClick = { onAdjust(+1) })
    }
}

/**
 * ペース表示: active phase の target SPM (設定値) で回転体を回す。
 * 計測値は使わない (実測 SPM は精度が不安定なため機能から外した)。
 * 設定変更は Settings 画面から行う。
 */
@Composable
private fun PaceDisplay(snapshot: SessionLiveSnapshot, activeColor: Color) {
    val activeUpper = snapshot.phase == LivePhase.HIGH || snapshot.phase == LivePhase.WARM_UP
    val activeTarget = if (activeUpper) snapshot.targetCadenceHigh else snapshot.targetCadenceRecovery
    val ellipseSpm = if (snapshot.phase == LivePhase.DONE) 0 else activeTarget
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        PaceEllipse(spm = ellipseSpm, color = activeColor)
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "▲${snapshot.targetCadenceHigh}",
                color = if (activeUpper) activeColor else MobileColors.TextDim,
                fontSize = 18.sp,
                fontWeight = if (activeUpper) FontWeight.Bold else FontWeight.Normal,
            )
            Text(
                text = "▼${snapshot.targetCadenceRecovery}",
                color = if (!activeUpper) activeColor else MobileColors.TextDim,
                fontSize = 18.sp,
                fontWeight = if (!activeUpper) FontWeight.Bold else FontWeight.Normal,
            )
            Text(text = "spm", color = MobileColors.TextDim, fontSize = 12.sp)
        }
    }
}

@Composable
private fun PaceEllipse(spm: Int, color: Color) {
    // `rememberInfiniteTransition` ベースだと animationSpec (= periodMs) が変わるたびに
    // 内部の LaunchedEffect が再起動し angle が initialValue=0° にリセットされる。
    // 結果として SPM が連続的に変わる demo / 実セッションでは 0° 近傍で停滞してしまう。
    // そのため frame ベースに自前で角速度を積分する: 1 周 = 2 step → spm × 3 deg/sec
    val spmState by rememberUpdatedState(spm)
    var angle by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var lastNanos = -1L
        while (true) {
            withFrameNanos { now ->
                if (lastNanos >= 0L) {
                    val s = spmState
                    if (s > 0) {
                        val dtSec = (now - lastNanos) / 1_000_000_000.0
                        val deltaDeg = (s * 3.0 * dtSec).toFloat()
                        angle = (angle + deltaDeg).mod(360f)
                    }
                }
                lastNanos = now
            }
        }
    }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp)
            .padding(horizontal = 12.dp),
    ) {
        val cx = size.width / 2
        val cy = size.height / 2
        val dotRadiusPx = 12.dp.toPx()
        val rx = (size.width / 2) - dotRadiusPx
        val ry = (size.height / 2) - dotRadiusPx
        drawOval(
            color = color.copy(alpha = 0.25f),
            topLeft = Offset(cx - rx, cy - ry),
            size = Size(rx * 2, ry * 2),
            style = Stroke(width = 2.dp.toPx()),
        )
        // 2 ドット (左右の足。180° 位相差)。DONE では片足ずつ左右に置いた静止ポーズ
        if (spmState > 0) {
            listOf(0f, 180f).forEach { offsetDeg ->
                val rad = ((angle + offsetDeg).toDouble() * Math.PI / 180.0)
                val x = cx + rx * cos(rad).toFloat()
                val y = cy + ry * sin(rad).toFloat()
                drawCircle(color = color, radius = dotRadiusPx, center = Offset(x, y))
            }
        } else {
            drawCircle(color = color, radius = dotRadiusPx, center = Offset(cx - rx, cy))
            drawCircle(color = color, radius = dotRadiusPx, center = Offset(cx + rx, cy))
        }
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

/** DONE 確認ボタン: ✓ グリフ + フェーズ色塗りで「完了」を明示する */
@Composable
private fun DoneButton(color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(color)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "✓", color = Color.Black, fontSize = 36.sp, fontWeight = FontWeight.Bold)
    }
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
