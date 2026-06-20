package io.github.wakuwaku3.adaptivepulse.mobile.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeomSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.wakuwaku3.adaptivepulse.core.sync.SessionRecord
import io.github.wakuwaku3.adaptivepulse.mobile.store.HeartRateSampleEntity
import io.github.wakuwaku3.adaptivepulse.mobile.ui.MobileColors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * ダッシュボード用ミニチャート群。共通仕様:
 *  - Y 軸/X 軸ラベルとタイトル付きの 1 カード
 *  - 適正帯 (Band) を背景に色塗りして「自分が今どこにいるか」を可視化
 *  - 点をタップすると下部に該当日付 + 値を表示
 *  - 凡例・現在カテゴリを上部にチップで併記
 */

private val ChartHeight = 110.dp
private val DefaultYAxisWidth = 30.dp
private val WideYAxisWidth = 60.dp

private data class Scale(val min: Double, val max: Double) {
    val range: Double get() = (max - min).coerceAtLeast(1e-6)
    fun y(h: Float, v: Double): Float = (h * ((max - v) / range)).toFloat()
}

private fun expandScale(values: List<Double>, padRatio: Double = 0.08): Scale {
    val mn = values.min()
    val mx = values.max()
    val pad = ((mx - mn) * padRatio).coerceAtLeast(0.5)
    return Scale(mn - pad, mx + pad)
}

private fun expandWithBands(values: List<Double>, bands: List<Band>, padRatio: Double = 0.08): Scale {
    val mn = values.min()
    val mx = values.max()
    // 自分がいるバンドの境界が見える程度に拡張する
    val bandsTouched = bands.filter { it.from <= mx && it.to >= mn }
    val targetMin = (bandsTouched.minOfOrNull { it.from } ?: mn)
    val targetMax = (bandsTouched.maxOfOrNull { it.to } ?: mx)
    // データを優先しつつ、隣接バンドの境界が 1 本は見えるようにする
    val mnSeen = minOf(mn, targetMin).coerceAtLeast(mn - (mx - mn) * 2)
    val mxSeen = maxOf(mx, targetMax).coerceAtMost(mx + (mx - mn) * 2)
    val pad = ((mxSeen - mnSeen) * padRatio).coerceAtLeast(0.5)
    return Scale(mnSeen - pad, mxSeen + pad)
}

private fun symmetricScale(values: List<Double>, floor: Double = 500.0): Scale {
    val absMax = values.map { abs(it) }.max().coerceAtLeast(floor)
    return Scale(-absMax, absMax)
}

@Composable
fun MiniChartCard(
    title: String,
    yLabels: List<String>,
    xLabels: List<String>,
    modifier: Modifier = Modifier,
    description: String? = null,
    categoryLabel: String? = null,
    categoryColor: Color = MobileColors.TextDim,
    legend: (@Composable () -> Unit)? = null,
    yAxisWide: Boolean = false,
    pointCount: Int = 0,
    pointAt: ((Int) -> String)? = null,
    drawContent: DrawScope.() -> Unit,
) {
    val yWidth = if (yAxisWide) WideYAxisWidth else DefaultYAxisWidth
    var tappedIndex by remember(pointCount) { mutableStateOf<Int?>(null) }
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.labelMedium, color = MobileColors.TextDim, maxLines = 1)
                    if (description != null) {
                        Text(description, style = MaterialTheme.typography.labelSmall, color = MobileColors.TextDim, maxLines = 1)
                    }
                }
                if (categoryLabel != null) {
                    Text(categoryLabel, color = categoryColor, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
            legend?.invoke()
            Row(modifier = Modifier.fillMaxWidth()) {
                YAxisLabels(yLabels, yWidth)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(ChartHeight)
                        .pointerInput(pointCount) {
                            if (pointCount <= 0 || pointAt == null) return@pointerInput
                            detectTapGestures { offset ->
                                val w = size.width.toFloat()
                                val stepX = if (pointCount > 1) w / (pointCount - 1) else 0f
                                val idx = if (stepX > 0f) (offset.x / stepX).roundToInt() else 0
                                tappedIndex = idx.coerceIn(0, pointCount - 1)
                            }
                        },
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) { drawContent() }
                }
            }
            XAxisLabels(xLabels, yWidth)
            if (tappedIndex != null && pointAt != null) {
                val txt = pointAt(tappedIndex!!)
                Text(
                    txt,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
private fun YAxisLabels(labels: List<String>, width: Dp) {
    Column(
        modifier = Modifier.width(width).height(ChartHeight),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.End,
    ) {
        labels.forEach { l ->
            Text(l, style = MaterialTheme.typography.labelSmall, color = MobileColors.TextDim, maxLines = 1)
        }
    }
}

@Composable
private fun XAxisLabels(labels: List<String>, yWidth: Dp) {
    Row(modifier = Modifier.fillMaxWidth().padding(start = yWidth + 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        labels.forEach { l ->
            Text(l, style = MaterialTheme.typography.labelSmall, color = MobileColors.TextDim)
        }
    }
}

@Composable
private fun LegendChip(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("●", color = color, style = MaterialTheme.typography.labelSmall)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MobileColors.TextDim)
    }
}

// MARK: --- 描画ユーティリティ

private fun DrawScope.drawBands(bands: List<Band>, scale: Scale) {
    val h = size.height
    val w = size.width
    bands.forEach { b ->
        // バンドの上下を絞り込み (scale 範囲外なら描画しない)
        val top = b.to.coerceAtMost(scale.max)
        val bottom = b.from.coerceAtLeast(scale.min)
        if (top <= bottom) return@forEach
        val yTop = scale.y(h, top)
        val yBottom = scale.y(h, bottom)
        drawRect(
            color = b.color,
            topLeft = Offset(0f, yTop),
            size = GeomSize(w, yBottom - yTop),
        )
    }
}

private fun DrawScope.drawZeroLine(scale: Scale) {
    if (0.0 !in scale.min..scale.max) return
    val y = scale.y(size.height, 0.0)
    drawLine(
        color = MobileColors.TextDim.copy(alpha = 0.55f),
        start = Offset(0f, y),
        end = Offset(size.width, y),
        strokeWidth = 1f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
    )
}

private fun DrawScope.drawDeficitArea(values: List<Double?>, scale: Scale) {
    val w = size.width
    val h = size.height
    if (values.isEmpty()) return
    val zeroY = scale.y(h, 0.0)
    val stepX = if (values.size > 1) w / (values.size - 1) else 0f
    // 各セグメントについて (i, i+1) の四辺形を 0 line と線の間で塗る。
    // 0 を跨ぐ場合は補間して上下に分割
    for (i in 0 until values.size - 1) {
        val a = values[i] ?: continue
        val b = values[i + 1] ?: continue
        val xa = i * stepX
        val xb = (i + 1) * stepX
        val ya = scale.y(h, a)
        val yb = scale.y(h, b)
        val crossesZero = (a > 0) != (b > 0)
        if (!crossesZero) {
            val color = if (a >= 0) MobileColors.Recover.copy(alpha = 0.30f)
            else MobileColors.High.copy(alpha = 0.30f)
            val path = Path().apply {
                moveTo(xa, ya)
                lineTo(xb, yb)
                lineTo(xb, zeroY)
                lineTo(xa, zeroY)
                close()
            }
            drawPath(path = path, color = color)
        } else {
            // 0 を跨ぐ x を線形補間
            val t = (-a) / (b - a) // a + t * (b-a) = 0
            val xCross = xa + (t * (xb - xa)).toFloat()
            // a の側
            val colorA = if (a >= 0) MobileColors.Recover.copy(alpha = 0.30f)
            else MobileColors.High.copy(alpha = 0.30f)
            val pathA = Path().apply {
                moveTo(xa, ya)
                lineTo(xCross, zeroY)
                lineTo(xa, zeroY)
                close()
            }
            drawPath(path = pathA, color = colorA)
            val colorB = if (b >= 0) MobileColors.Recover.copy(alpha = 0.30f)
            else MobileColors.High.copy(alpha = 0.30f)
            val pathB = Path().apply {
                moveTo(xCross, zeroY)
                lineTo(xb, yb)
                lineTo(xb, zeroY)
                close()
            }
            drawPath(path = pathB, color = colorB)
        }
    }
}

private fun DrawScope.drawLineChart(
    values: List<Double?>,
    scale: Scale,
    color: Color,
    pointRadius: Float = 3f,
    selected: Int? = null,
) {
    val w = size.width
    val h = size.height
    val stepX = if (values.size > 1) w / (values.size - 1) else 0f
    val path = Path()
    var started = false
    values.forEachIndexed { i, v ->
        if (v == null) return@forEachIndexed
        val x = i * stepX
        val y = scale.y(h, v)
        if (!started) {
            path.moveTo(x, y)
            started = true
        } else path.lineTo(x, y)
        drawCircle(color = color, radius = pointRadius, center = Offset(x, y))
        if (i == selected) {
            drawCircle(color = MaterialColors.Selected, radius = pointRadius + 3f, center = Offset(x, y), style = Stroke(width = 1.5f))
        }
    }
    if (started) drawPath(path = path, color = color, style = Stroke(width = 2f))
}

private object MaterialColors {
    val Selected = Color(0xFFFFFFFF)
}

private fun DrawScope.drawBars(
    values: List<Double?>,
    scale: Scale,
    color: Color,
    selected: Int? = null,
) {
    val w = size.width
    val h = size.height
    if (values.isEmpty()) return
    val slot = w / values.size
    val barWidth = slot * 0.55f
    values.forEachIndexed { i, v ->
        if (v == null) return@forEachIndexed
        val centerX = slot * (i + 0.5f)
        val topY = scale.y(h, v)
        val baseY = h
        drawRect(
            color = color,
            topLeft = Offset(centerX - barWidth / 2, minOf(topY, baseY)),
            size = GeomSize(barWidth, abs(baseY - topY)),
        )
        if (i == selected) {
            drawRect(
                color = MaterialColors.Selected,
                topLeft = Offset(centerX - barWidth / 2, topY),
                size = GeomSize(barWidth, baseY - topY),
                style = Stroke(width = 1.5f),
            )
        }
    }
}

private fun List<DashboardComputed>.firstAndLastDateLabels(): List<String> {
    if (isEmpty()) return listOf("—", "—")
    val sorted = sortedBy { it.date }
    return listOf(sorted.first().date.takeLast(5), sorted.last().date.takeLast(5))
}

// MARK: --- 個別チャート

@Composable
fun WeightChart(rows: List<DashboardComputed>, modifier: Modifier = Modifier) {
    val values = rows.map { it.weightKg }
    val nonNull = values.filterNotNull()
    val scale = if (nonNull.isNotEmpty()) expandScale(nonNull, 0.10) else Scale(60.0, 100.0)
    val current = nonNull.lastOrNull()
    MiniChartCard(
        title = "WEIGHT (kg)",
        yLabels = listOf(
            "%.1f".format(scale.max),
            "%.1f".format((scale.min + scale.max) / 2),
            "%.1f".format(scale.min),
        ),
        xLabels = rows.firstAndLastDateLabels(),
        modifier = modifier,
        categoryLabel = current?.let { "%.1f kg".format(it) },
        pointCount = rows.size,
        pointAt = { i -> rows.getOrNull(i)?.let { "${it.date}: ${it.weightKg?.let { v -> "%.2f kg".format(v) } ?: "—"}" } ?: "" },
        drawContent = { drawLineChart(values, scale, MobileColors.Done) },
    )
}

@Composable
fun BmiChart(rows: List<DashboardComputed>, modifier: Modifier = Modifier) {
    val values = rows.map { it.bmi }
    val nonNull = values.filterNotNull()
    val scale = if (nonNull.isNotEmpty()) expandWithBands(nonNull, Bmi.bands, 0.12) else Scale(18.0, 35.0)
    val current = nonNull.lastOrNull()
    val cat = current?.let { Bmi.categoryOf(it) }
    MiniChartCard(
        title = "BMI",
        description = "kg ÷ (身長 m)²",
        yLabels = listOf(
            "%.1f".format(scale.max),
            "%.1f".format((scale.min + scale.max) / 2),
            "%.1f".format(scale.min),
        ),
        xLabels = rows.firstAndLastDateLabels(),
        modifier = modifier,
        categoryLabel = cat,
        categoryColor = bandLabelColor(cat, Bmi.bands),
        pointCount = rows.size,
        pointAt = { i ->
            rows.getOrNull(i)?.let { "${it.date}: BMI ${it.bmi?.let { v -> "%.1f".format(v) } ?: "—"} (${it.bmi?.let { v -> Bmi.categoryOf(v) } ?: "—"})" } ?: ""
        },
        drawContent = {
            drawBands(Bmi.bands, scale)
            drawLineChart(values, scale, MobileColors.Done)
        },
    )
}

@Composable
fun DeficitChart(rows: List<DashboardComputed>, modifier: Modifier = Modifier) {
    // チャート上は「赤字 (kcal/日)」表示にするため deficit の符号を反転
    val values = rows.map { it.deficitKcal?.let { d -> -d } }
    val nonNull = values.filterNotNull()
    val scale = if (nonNull.isNotEmpty()) symmetricScale(nonNull) else Scale(-500.0, 500.0)
    val recentAvg = nonNull.takeLast(7).average().takeIf { !it.isNaN() }
    val cat = recentAvg?.let { Deficit.categoryOf(it) }
    MiniChartCard(
        title = "DEFICIT (kcal/日)",
        description = "上=赤字 (痩せ方向) / 下=サープラス",
        yLabels = listOf(
            "%+.0f".format(scale.max),
            "0",
            "%+.0f".format(scale.min),
        ),
        xLabels = rows.firstAndLastDateLabels(),
        modifier = modifier,
        categoryLabel = cat,
        categoryColor = bandLabelColor(cat, Deficit.bands),
        pointCount = rows.size,
        pointAt = { i ->
            rows.getOrNull(i)?.let {
                val d = it.deficitKcal?.let { v -> -v }
                "${it.date}: ${d?.let { dd -> "%+.0f kcal".format(dd) } ?: "—"}"
            } ?: ""
        },
        drawContent = {
            drawDeficitArea(values, scale)
            drawZeroLine(scale)
            drawLineChart(values, scale, MobileColors.Recover.copy(alpha = 0.9f))
        },
    )
}

@Composable
fun TdeeIntakeChart(rows: List<DashboardComputed>, modifier: Modifier = Modifier) {
    val tdee = rows.map { it.tdeeKcal }
    val intake = rows.map { it.intakeKcal }
    val all = (tdee + intake).filterNotNull()
    val scale = if (all.isNotEmpty()) Scale(0.0, all.max() * 1.1) else Scale(0.0, 3000.0)
    MiniChartCard(
        title = "TDEE vs INTAKE (kcal)",
        description = "消費 vs 摂取",
        yLabels = listOf(
            "%.0fk".format(scale.max / 1000),
            "%.0fk".format(scale.max / 2000),
            "0",
        ),
        xLabels = rows.firstAndLastDateLabels(),
        modifier = modifier,
        legend = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LegendChip("TDEE", MobileColors.Recover)
                LegendChip("Intake", MobileColors.High)
            }
        },
        pointCount = rows.size,
        pointAt = { i ->
            rows.getOrNull(i)?.let {
                "${it.date}: TDEE ${it.tdeeKcal?.toInt() ?: "—"}, intake ${it.intakeKcal?.toInt() ?: "—"}"
            } ?: ""
        },
        drawContent = {
            drawLineChart(tdee, scale, MobileColors.Recover)
            drawLineChart(intake, scale, MobileColors.High)
        },
    )
}

@Composable
fun StepsChart(rows: List<DashboardComputed>, modifier: Modifier = Modifier) {
    val values = rows.map { it.steps?.toDouble() }
    val nonNull = values.filterNotNull()
    val scale = if (nonNull.isNotEmpty()) Scale(0.0, (nonNull.max() * 1.15).coerceAtLeast(10_000.0)) else Scale(0.0, 12_000.0)
    val current = nonNull.lastOrNull()
    val cat = current?.let { Steps.categoryOf(it) }
    MiniChartCard(
        title = "STEPS (歩数)",
        yLabels = listOf(
            "%.0fk".format(scale.max / 1000),
            "%.0fk".format(scale.max / 2000),
            "0",
        ),
        xLabels = rows.firstAndLastDateLabels(),
        modifier = modifier,
        categoryLabel = cat,
        categoryColor = bandLabelColor(cat, Steps.bands),
        pointCount = rows.size,
        pointAt = { i -> rows.getOrNull(i)?.let { "${it.date}: ${it.steps ?: "—"} 歩" } ?: "" },
        drawContent = {
            drawBands(Steps.bands, scale)
            drawBars(values, scale, MobileColors.Done)
        },
    )
}

@Composable
fun ProteinChart(rows: List<DashboardComputed>, modifier: Modifier = Modifier) {
    val values = rows.map { it.proteinPerKg }
    val nonNull = values.filterNotNull()
    val scale = if (nonNull.isNotEmpty()) Scale(0.0, (nonNull.max() + 0.4).coerceAtLeast(2.5)) else Scale(0.0, 2.5)
    val current = nonNull.lastOrNull()
    val cat = current?.let { Protein.categoryOf(it) }
    MiniChartCard(
        title = "PROTEIN (g/kg)",
        description = "減量中 1.6+ 推奨",
        yLabels = listOf(
            "%.1f".format(scale.max),
            "%.1f".format((scale.min + scale.max) / 2),
            "0",
        ),
        xLabels = rows.firstAndLastDateLabels(),
        modifier = modifier,
        categoryLabel = cat,
        categoryColor = bandLabelColor(cat, Protein.bands),
        pointCount = rows.size,
        pointAt = { i ->
            rows.getOrNull(i)?.let {
                "${it.date}: ${it.proteinG?.let { v -> "%.0f g".format(v) } ?: "—"} (${it.proteinPerKg?.let { v -> "%.2f g/kg".format(v) } ?: "—"})"
            } ?: ""
        },
        drawContent = {
            drawBands(Protein.bands, scale)
            drawLineChart(values, scale, MobileColors.Done)
        },
    )
}

@Composable
fun SleepChart(rows: List<DashboardComputed>, modifier: Modifier = Modifier) {
    val durations = rows.map { it.sleepHours }
    val nonNull = durations.filterNotNull()
    val scale = if (nonNull.isNotEmpty()) Scale(0.0, (nonNull.max() + 1).coerceAtLeast(10.0)) else Scale(0.0, 10.0)
    val current = nonNull.lastOrNull()
    val cat = current?.let { Sleep.categoryOf(it) }
    MiniChartCard(
        title = "SLEEP (時間)",
        description = "成人推奨 7-9h",
        yLabels = listOf(
            "%.0fh".format(scale.max),
            "%.0fh".format((scale.min + scale.max) / 2),
            "0h",
        ),
        xLabels = rows.firstAndLastDateLabels(),
        modifier = modifier,
        categoryLabel = cat,
        categoryColor = bandLabelColor(cat, Sleep.bands),
        pointCount = rows.size,
        pointAt = { i -> rows.getOrNull(i)?.let { "${it.date}: ${it.sleepHours?.let { v -> "%.1f h".format(v) } ?: "—"}" } ?: "" },
        drawContent = {
            drawBands(Sleep.bands, scale)
            drawBars(durations, scale, MobileColors.Recover)
        },
    )
}

@Composable
fun HrvChart(rows: List<DashboardComputed>, modifier: Modifier = Modifier) {
    val values = rows.map { it.hrvMs }
    val nonNull = values.filterNotNull()
    val scale = if (nonNull.isNotEmpty()) expandScale(nonNull, 0.20) else Scale(20.0, 60.0)
    val avg = nonNull.average().takeIf { !it.isNaN() }
    MiniChartCard(
        title = "HRV / 心拍変動 (ms)",
        description = "回復・自律神経の指標。高いほど良い",
        yLabels = listOf(
            "%.0f".format(scale.max),
            "%.0f".format((scale.min + scale.max) / 2),
            "%.0f".format(scale.min),
        ),
        xLabels = rows.firstAndLastDateLabels(),
        modifier = modifier,
        categoryLabel = avg?.let { "7日平均 %.0f ms".format(it) },
        pointCount = rows.size,
        pointAt = { i -> rows.getOrNull(i)?.let { "${it.date}: ${it.hrvMs?.let { v -> "%.0f ms".format(v) } ?: "—"}" } ?: "" },
        drawContent = { drawLineChart(values, scale, MobileColors.Recover) },
    )
}

@Composable
fun RestingHrChart(rows: List<DashboardComputed>, modifier: Modifier = Modifier) {
    val values = rows.map { it.restingHrBpm?.toDouble() }
    val nonNull = values.filterNotNull()
    val scale = if (nonNull.isNotEmpty()) expandWithBands(nonNull, RestingHr.bands, 0.15) else Scale(50.0, 80.0)
    val current = nonNull.lastOrNull()
    val cat = current?.let { RestingHr.categoryOf(it) }
    MiniChartCard(
        title = "安静時心拍 (bpm)",
        description = "心臓の効率。低いほど健康",
        yLabels = listOf(
            "%.0f".format(scale.max),
            "%.0f".format((scale.min + scale.max) / 2),
            "%.0f".format(scale.min),
        ),
        xLabels = rows.firstAndLastDateLabels(),
        modifier = modifier,
        categoryLabel = cat,
        categoryColor = bandLabelColor(cat, RestingHr.bands),
        pointCount = rows.size,
        pointAt = { i -> rows.getOrNull(i)?.let { "${it.date}: ${it.restingHrBpm?.let { v -> "$v bpm" } ?: "—"}" } ?: "" },
        drawContent = {
            drawBands(RestingHr.bands, scale)
            drawLineChart(values, scale, MobileColors.High)
        },
    )
}

@Composable
fun Spo2Chart(rows: List<DashboardComputed>, modifier: Modifier = Modifier) {
    val values = rows.map { it.spo2AvgPct }
    val nonNull = values.filterNotNull()
    val scale = if (nonNull.isNotEmpty()) expandWithBands(nonNull, Spo2.bands, 0.10) else Scale(92.0, 100.0)
    val current = nonNull.lastOrNull()
    val cat = current?.let { Spo2.categoryOf(it) }
    MiniChartCard(
        title = "SpO2 / 血中酸素 (%)",
        description = "酸素飽和度。95% 以上が正常",
        yLabels = listOf(
            "%.1f".format(scale.max),
            "%.1f".format((scale.min + scale.max) / 2),
            "%.1f".format(scale.min),
        ),
        xLabels = rows.firstAndLastDateLabels(),
        modifier = modifier,
        categoryLabel = cat,
        categoryColor = bandLabelColor(cat, Spo2.bands),
        pointCount = rows.size,
        pointAt = { i -> rows.getOrNull(i)?.let { "${it.date}: ${it.spo2AvgPct?.let { v -> "%.1f%%".format(v) } ?: "—"}" } ?: "" },
        drawContent = {
            drawBands(Spo2.bands, scale)
            drawLineChart(values, scale, MobileColors.High)
        },
    )
}

@Composable
fun HeartRate24hChart(samples: List<HeartRateSampleEntity>, modifier: Modifier = Modifier) {
    val bpms = samples.map { it.bpm.toDouble() }
    val scale = if (bpms.isNotEmpty()) Scale(50.0, (bpms.max() + 10).coerceAtLeast(120.0)) else Scale(50.0, 180.0)
    val sorted = samples.sortedBy { it.timestampMs }
    MiniChartCard(
        title = "心拍 (24h, bpm)",
        description = if (bpms.isNotEmpty()) "min ${bpms.min().toInt()} · max ${bpms.max().toInt()}" else null,
        yLabels = listOf(
            "%.0f".format(scale.max),
            "%.0f".format((scale.min + scale.max) / 2),
            "%.0f".format(scale.min),
        ),
        xLabels = if (sorted.isEmpty()) listOf("—") else listOf("0h", "12h", "24h"),
        modifier = modifier,
        pointCount = sorted.size,
        pointAt = { i ->
            sorted.getOrNull(i)?.let {
                val instant = java.time.Instant.ofEpochMilli(it.timestampMs).atZone(java.time.ZoneId.systemDefault())
                "${instant.toLocalTime().withSecond(0).withNano(0)}: ${it.bpm} bpm"
            } ?: ""
        },
        drawContent = {
            if (sorted.isEmpty()) return@MiniChartCard
            val w = size.width
            val h = size.height
            val tMin = sorted.first().timestampMs
            val tMax = sorted.last().timestampMs.coerceAtLeast(tMin + 1)
            val path = Path()
            var started = false
            sorted.forEach { s ->
                val x = w * ((s.timestampMs - tMin).toFloat() / (tMax - tMin))
                val y = scale.y(h, s.bpm.toDouble())
                if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
            }
            drawPath(path = path, color = MobileColors.High, style = Stroke(width = 1.5f))
        },
    )
}

// MARK: --- セッションチャート (本アプリの HIIT 履歴)

/** セッションごとの平均高強度区間秒数 = 体力向上のシグナル */
@Composable
fun SessionHighDurationChart(sessions: List<SessionRecord>, modifier: Modifier = Modifier) {
    val ordered = sessions.sortedBy { it.startedAtMs }
    val values = ordered.map { s ->
        s.highDurationsSec.takeIf { it.isNotEmpty() }?.average()?.toDouble()
    }
    val nonNull = values.filterNotNull()
    val scale = if (nonNull.isNotEmpty()) Scale(0.0, (nonNull.max() + 15).coerceAtLeast(60.0)) else Scale(0.0, 60.0)
    val avg = nonNull.average().takeIf { !it.isNaN() }
    MiniChartCard(
        title = "高強度区間 (秒/サイクル)",
        description = "同負荷で伸びる = 体力向上",
        yLabels = listOf(
            "%.0fs".format(scale.max),
            "%.0fs".format((scale.min + scale.max) / 2),
            "0s",
        ),
        xLabels = ordered.sessionFirstLastDates(),
        modifier = modifier,
        categoryLabel = avg?.let { "平均 %.0f s".format(it) },
        pointCount = ordered.size,
        pointAt = { i ->
            ordered.getOrNull(i)?.let { s ->
                val dt = java.time.Instant.ofEpochMilli(s.startedAtMs).atZone(java.time.ZoneId.systemDefault())
                val avgHigh = s.highDurationsSec.takeIf { it.isNotEmpty() }?.average()
                "${dt.toLocalDate()}: ${avgHigh?.let { v -> "%.1f s".format(v) } ?: "—"}"
            } ?: ""
        },
        drawContent = { drawLineChart(values, scale, MobileColors.Recover) },
    )
}

/** ゾーン滞在率 (下限〜上限の帯にいた時間/総時間) — トレーニング品質指標 */
@Composable
fun SessionZoneRatioChart(sessions: List<SessionRecord>, modifier: Modifier = Modifier) {
    val ordered = sessions.sortedBy { it.startedAtMs }
    val values = ordered.map { it.zoneRatio?.times(100.0) }
    val scale = Scale(0.0, 100.0)
    val avg = values.filterNotNull().average().takeIf { !it.isNaN() }
    MiniChartCard(
        title = "ゾーン滞在率 (%)",
        description = "上限-下限帯にいた時間",
        yLabels = listOf("100", "50", "0"),
        xLabels = ordered.sessionFirstLastDates(),
        modifier = modifier,
        categoryLabel = avg?.let { "平均 %.0f%%".format(it) },
        pointCount = ordered.size,
        pointAt = { i ->
            ordered.getOrNull(i)?.let { s ->
                val dt = java.time.Instant.ofEpochMilli(s.startedAtMs).atZone(java.time.ZoneId.systemDefault())
                "${dt.toLocalDate()}: ${s.zoneRatio?.let { v -> "%.0f%%".format(v * 100) } ?: "—"}"
            } ?: ""
        },
        drawContent = { drawLineChart(values, scale, MobileColors.Done) },
    )
}

/** 各セッションの最大 BPM。HIIT の強度監視 */
@Composable
fun SessionMaxBpmChart(sessions: List<SessionRecord>, modifier: Modifier = Modifier) {
    val ordered = sessions.sortedBy { it.startedAtMs }
    val values = ordered.map { it.maxBpm?.toDouble() }
    val nonNull = values.filterNotNull()
    val scale = if (nonNull.isNotEmpty()) Scale((nonNull.min() - 10).coerceAtLeast(100.0), (nonNull.max() + 10).coerceAtMost(220.0)) else Scale(100.0, 200.0)
    val avg = nonNull.average().takeIf { !it.isNaN() }
    MiniChartCard(
        title = "最大心拍 / セッション (bpm)",
        yLabels = listOf(
            "%.0f".format(scale.max),
            "%.0f".format((scale.min + scale.max) / 2),
            "%.0f".format(scale.min),
        ),
        xLabels = ordered.sessionFirstLastDates(),
        modifier = modifier,
        categoryLabel = avg?.let { "平均 %.0f bpm".format(it) },
        pointCount = ordered.size,
        pointAt = { i ->
            ordered.getOrNull(i)?.let { s ->
                val dt = java.time.Instant.ofEpochMilli(s.startedAtMs).atZone(java.time.ZoneId.systemDefault())
                "${dt.toLocalDate()}: ${s.maxBpm ?: "—"} bpm"
            } ?: ""
        },
        drawContent = { drawLineChart(values, scale, MobileColors.High) },
    )
}

private fun List<SessionRecord>.sessionFirstLastDates(): List<String> {
    if (isEmpty()) return listOf("—", "—")
    val sorted = sortedBy { it.startedAtMs }
    fun fmt(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString().takeLast(5)
    return listOf(fmt(sorted.first().startedAtMs), fmt(sorted.last().startedAtMs))
}

// MARK: --- カテゴリ別ラベル色

private fun bandLabelColor(label: String?, bands: List<Band>): Color {
    if (label == null) return MobileColors.TextDim
    val band = bands.firstOrNull { it.label == label } ?: return MobileColors.TextDim
    return when {
        band.color.alpha < 0.08f -> MobileColors.TextDim
        band.color == MobileColors.Recover.copy(alpha = band.color.alpha) ||
            band.color == MobileColors.Recover.copy(alpha = 0.12f) -> MobileColors.Recover
        band.color == MobileColors.High.copy(alpha = band.color.alpha) ||
            band.color == MobileColors.High.copy(alpha = 0.12f) -> MobileColors.High
        band.color == MobileColors.Done.copy(alpha = band.color.alpha) ||
            band.color == MobileColors.Done.copy(alpha = 0.10f) -> MobileColors.Done
        else -> MobileColors.TextDim
    }
}
