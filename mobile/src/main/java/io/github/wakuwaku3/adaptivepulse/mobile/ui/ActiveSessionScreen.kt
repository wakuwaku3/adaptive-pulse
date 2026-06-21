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
import androidx.compose.foundation.layout.width
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
import io.github.wakuwaku3.adaptivepulse.core.sync.LivePhase
import io.github.wakuwaku3.adaptivepulse.core.sync.SessionLiveSnapshot
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * watch から流れてくるライブ状態を表示し、操作も受ける phone 画面。
 * 機材コンソール上に置いて視野の端で読む想定。タップ操作は ± 系と停止のみ。
 *
 * デザイン上の意図:
 * - HR (♥) と SPM (run glyph) は同じサイズで横並び。どちらも "now" を表す値
 * - ペース楕円の tempo は **現在値 + nudge** にして target を上限/下限として clamp する。
 *   こうすると「徐々に加速 / 徐々に減速」が視覚的に伝わる (target そのものをぶつけると階段状になる)
 * - DONE では楕円アニメを停止 (運動が終わっているのに回転が続くと違和感)
 * - 閾値 / ペース target の調整は同じ形式 (▲/▼ 両表示 + 単位 + 左右 ±)
 */
@Composable
fun ActiveSessionScreen(
    snapshot: SessionLiveSnapshot,
    onAdjustThreshold: (Int) -> Unit,
    onAdjustTargetSpm: (Int) -> Unit,
    onStop: () -> Unit,
) {
    val phaseColor = colorFor(snapshot.phase)
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
            NowValues(snapshot, phaseColor)
            CycleAndTimers(snapshot)
            Spacer(modifier = Modifier.height(2.dp))
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

/** HR と現在 SPM を同じサイズで横並びに置く ("now" として等価) */
@Composable
private fun NowValues(snapshot: SessionLiveSnapshot, color: Color) {
    val currentSpm = snapshot.currentCadenceSpm?.roundToInt()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        NowMetric(
            iconContent = {
                Icon(
                    imageVector = Icons.Filled.Favorite,
                    contentDescription = "bpm",
                    tint = color,
                    modifier = Modifier.size(26.dp),
                )
            },
            value = snapshot.bpm?.toString() ?: "--",
            unit = "bpm",
            color = color,
        )
        Box(modifier = Modifier.width(1.dp).height(80.dp).background(MobileColors.TextDim.copy(alpha = 0.4f)))
        NowMetric(
            iconContent = { RunGlyph(color = color, sizeDp = 26) },
            value = currentSpm?.toString() ?: "--",
            unit = "spm",
            color = color,
        )
    }
}

@Composable
private fun NowMetric(
    iconContent: @Composable () -> Unit,
    value: String,
    unit: String,
    color: Color,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            iconContent()
            Spacer(Modifier.width(6.dp))
            Text(
                text = value,
                color = color,
                fontSize = 64.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(text = unit, color = MobileColors.TextDim, fontSize = 13.sp)
    }
}

/**
 * 走者の stick figure (自前 Canvas、26dp 程度で読めるよう単純化)。
 * 頭・前傾姿勢の胴・前方に出た腕と後ろに引いた腕・前後にずれた脚 で運動感を出す。
 * material-icons-extended の DirectionsRun を引き込むと dex 上限を超えるため自前描画。
 */
@Composable
private fun RunGlyph(color: Color, sizeDp: Int) {
    Canvas(modifier = Modifier.size(sizeDp.dp)) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.10f
        // 頭 (右上)
        drawCircle(
            color = color,
            radius = w * 0.12f,
            center = Offset(w * 0.62f, h * 0.16f),
        )
        // 胴 (前傾)
        drawLine(
            color = color,
            start = Offset(w * 0.55f, h * 0.30f),
            end = Offset(w * 0.42f, h * 0.58f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        // 前腕 (前方上に)
        drawLine(
            color = color,
            start = Offset(w * 0.52f, h * 0.38f),
            end = Offset(w * 0.78f, h * 0.32f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        // 後腕 (後ろ下に)
        drawLine(
            color = color,
            start = Offset(w * 0.50f, h * 0.42f),
            end = Offset(w * 0.22f, h * 0.50f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        // 前脚 (蹴り出し)
        drawLine(
            color = color,
            start = Offset(w * 0.42f, h * 0.58f),
            end = Offset(w * 0.72f, h * 0.82f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        // 後脚 (後ろにつま先)
        drawLine(
            color = color,
            start = Offset(w * 0.42f, h * 0.58f),
            end = Offset(w * 0.18f, h * 0.90f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
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
    TwoLimitsControl(
        upperGlyph = "▲",
        upperValue = snapshot.upperBpm,
        lowerGlyph = "▼",
        lowerValue = snapshot.lowerBpm,
        unit = "bpm",
        activeUpper = activeUpper,
        activeColor = activeColor,
        onAdjust = onAdjust,
    )
}

/**
 * ペース target: ▲high / ▼recovery 両表示 + ± を左右に配置 + 単位 "spm"。
 * pace-metric note の新仕様: target は cycle 毎に制御ループで動的に変わる **動的目標**。
 * 拍動円の tempo は active target そのもので回す (`current ± nudge` 案は廃止)。
 * 色は `current vs active target` のズレで within / 速すぎ / 遅すぎ を判定。
 */
@Composable
private fun PaceControl(
    snapshot: SessionLiveSnapshot,
    activeColor: Color,
    onAdjust: (Int) -> Unit,
) {
    val activeUpper = snapshot.phase == LivePhase.HIGH || snapshot.phase == LivePhase.WARM_UP
    val currentSpm = snapshot.currentCadenceSpm?.roundToInt()
    val activeTarget = if (activeUpper) snapshot.targetCadenceHigh else snapshot.targetCadenceRecovery
    val ellipseSpm = if (snapshot.phase == LivePhase.DONE) 0 else activeTarget.roundToInt()
    val syncColor = ellipseColor(snapshot, currentSpm, activeTarget, activeColor)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        PaceEllipse(spm = ellipseSpm, color = syncColor)
        TwoLimitsControl(
            upperGlyph = "▲",
            upperValue = snapshot.targetCadenceHigh.roundToInt(),
            lowerGlyph = "▼",
            lowerValue = snapshot.targetCadenceRecovery.roundToInt(),
            unit = "spm",
            activeUpper = activeUpper,
            activeColor = activeColor,
            onAdjust = onAdjust,
        )
    }
}

@Composable
private fun TwoLimitsControl(
    upperGlyph: String,
    upperValue: Int,
    lowerGlyph: String,
    lowerValue: Int,
    unit: String,
    activeUpper: Boolean,
    activeColor: Color,
    onAdjust: (Int) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        NudgeButton(glyph = "−", color = activeColor, onClick = { onAdjust(-1) })
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "$upperGlyph$upperValue",
                color = if (activeUpper) activeColor else MobileColors.TextDim,
                fontSize = 22.sp,
                fontWeight = if (activeUpper) FontWeight.Bold else FontWeight.Normal,
            )
            Text(
                text = "$lowerGlyph$lowerValue",
                color = if (!activeUpper) activeColor else MobileColors.TextDim,
                fontSize = 22.sp,
                fontWeight = if (!activeUpper) FontWeight.Bold else FontWeight.Normal,
            )
            Text(
                text = unit,
                color = MobileColors.TextDim,
                fontSize = 13.sp,
            )
        }
        NudgeButton(glyph = "+", color = activeColor, onClick = { onAdjust(+1) })
    }
}

/** within / 速すぎ / 遅すぎ の色判定。active target と current のズレで決める */
private fun ellipseColor(
    snapshot: SessionLiveSnapshot,
    currentSpm: Int?,
    activeTarget: Double,
    activeColor: Color,
): Color {
    if (snapshot.phase == LivePhase.DONE || currentSpm == null) return MobileColors.TextDim
    val diff = currentSpm - activeTarget
    return when {
        abs(diff) <= PACE_WITHIN_BAND -> activeColor
        diff > 0 -> MobileColors.High // 速すぎ
        else -> Color(0xFF54C7EC) // 遅すぎ
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

/** active target との差がこの SPM 以内ならニュートラル色 */
private const val PACE_WITHIN_BAND = 3
