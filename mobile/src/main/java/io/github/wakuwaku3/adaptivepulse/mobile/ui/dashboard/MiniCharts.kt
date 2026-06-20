package io.github.wakuwaku3.adaptivepulse.mobile.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeomSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.github.wakuwaku3.adaptivepulse.core.sync.SessionRecord
import io.github.wakuwaku3.adaptivepulse.mobile.store.HeartRateSampleEntity
import io.github.wakuwaku3.adaptivepulse.mobile.ui.MobileColors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * ダッシュボード用ミニチャート群。共通仕様:
 *  - タイトル英語、補足は ⓘ アイコンタップで Popup (フローティング、レイアウト不変)
 *  - チャート右上は **今日の数値** を表示 (カテゴリ文字は出さない。帯背景で良し悪しを示す)
 *  - 点タップ → その点に近接した floating tooltip で「日付: 値」表示
 *  - 単線チャートには適正帯または基準線を必ず置く (今が良いか悪いかが一目でわかる)
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
    val bandsTouched = bands.filter { it.from <= mx && it.to >= mn }
    val targetMin = bandsTouched.minOfOrNull { it.from } ?: mn
    val targetMax = bandsTouched.maxOfOrNull { it.to } ?: mx
    val mnSeen = minOf(mn, targetMin).coerceAtLeast(mn - (mx - mn) * 2)
    val mxSeen = maxOf(mx, targetMax).coerceAtMost(mx + (mx - mn) * 2)
    val pad = ((mxSeen - mnSeen) * padRatio).coerceAtLeast(0.5)
    return Scale(mnSeen - pad, mxSeen + pad)
}

private fun symmetricScale(values: List<Double>, floor: Double = 500.0): Scale {
    val absMax = values.map { abs(it) }.max().coerceAtLeast(floor)
    return Scale(-absMax, absMax)
}

fun interface PointXResolver {
    fun x(index: Int, canvasWidth: Float): Float
}

@Composable
fun MiniChartCard(
    title: String,
    yLabels: List<String>,
    xLabels: List<String>,
    modifier: Modifier = Modifier,
    info: String? = null,
    valueLabel: String? = null,
    valueColor: Color = MobileColors.TextDim,
    legend: (@Composable () -> Unit)? = null,
    yAxisWide: Boolean = false,
    pointCount: Int = 0,
    pointAt: ((Int) -> String)? = null,
    pointXResolver: PointXResolver = PointXResolver { i, w ->
        if (pointCount > 1) i * w / (pointCount - 1) else 0f
    },
    drawContent: DrawScope.() -> Unit,
) {
    val yWidth = if (yAxisWide) WideYAxisWidth else DefaultYAxisWidth
    var tappedIndex by remember(pointCount) { mutableStateOf<Int?>(null) }
    var showInfo by remember { mutableStateOf(false) }
    var canvasW by remember { mutableStateOf(0f) }
    var tooltipW by remember { mutableStateOf(0) }
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(title, style = MaterialTheme.typography.labelMedium, color = MobileColors.TextDim, maxLines = 1)
                    if (info != null) {
                        Box(
                            modifier = Modifier.pointerInput(Unit) {
                                detectTapGestures { showInfo = !showInfo }
                            },
                        ) {
                            Text(
                                "ⓘ",
                                color = MobileColors.TextDim,
                                style = MaterialTheme.typography.labelMedium,
                            )
                            if (showInfo) {
                                Popup(
                                    alignment = Alignment.TopStart,
                                    offset = IntOffset(0, 40),
                                    onDismissRequest = { showInfo = false },
                                    properties = PopupProperties(focusable = true),
                                ) {
                                    Card {
                                        Text(
                                            info,
                                            modifier = Modifier
                                                .padding(10.dp)
                                                .widthIn(max = 220.dp),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (valueLabel != null) {
                    Text(
                        valueLabel,
                        color = valueColor,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                    )
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
                            detectTapGestures(
                                onTap = { offset ->
                                    val w = size.width.toFloat()
                                    canvasW = w
                                    val stepX = if (pointCount > 1) w / (pointCount - 1) else 0f
                                    val idx = if (stepX > 0f) (offset.x / stepX).roundToInt() else 0
                                    tappedIndex = idx.coerceIn(0, pointCount - 1)
                                },
                            )
                        },
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        canvasW = size.width
                        drawContent()
                    }
                    val ti = tappedIndex
                    if (ti != null && pointAt != null) {
                        val tipCenter = pointXResolver.x(ti, canvasW).coerceIn(0f, canvasW)
                        val tipLeftRaw = tipCenter - tooltipW / 2f
                        val tipLeft = tipLeftRaw.coerceIn(0f, (canvasW - tooltipW).coerceAtLeast(0f))
                        Box(
                            modifier = Modifier
                                .offset { IntOffset(tipLeft.toInt(), 0) }
                                .onSizeChanged { tooltipW = it.width }
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xE0000000))
                                .padding(horizontal = 6.dp, vertical = 3.dp)
                                .pointerInput(Unit) {
                                    detectTapGestures { tappedIndex = null }
                                },
                        ) {
                            Text(
                                pointAt(ti),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                maxLines = 2,
                            )
                        }
                    }
                }
            }
            XAxisLabels(xLabels, yWidth)
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
        val top = b.to.coerceAtMost(scale.max)
        val bottom = b.from.coerceAtLeast(scale.min)
        if (top <= bottom) return@forEach
        val yTop = scale.y(h, top)
        val yBottom = scale.y(h, bottom)
        drawRect(color = b.color, topLeft = Offset(0f, yTop), size = GeomSize(w, yBottom - yTop))
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

/** 「目標値」を破線で 1 本引く。今が良いか悪いかをチャートを見るだけで判断するための基準線 */
private fun DrawScope.drawReferenceLine(value: Double, scale: Scale, color: Color, dash: Boolean = true) {
    if (value !in scale.min..scale.max) return
    val y = scale.y(size.height, value)
    drawLine(
        color = color,
        start = Offset(0f, y),
        end = Offset(size.width, y),
        strokeWidth = 1.2f,
        pathEffect = if (dash) PathEffect.dashPathEffect(floatArrayOf(8f, 6f)) else null,
    )
}

private fun DrawScope.drawDeficitArea(values: List<Double?>, scale: Scale) {
    val w = size.width
    val h = size.height
    if (values.isEmpty()) return
    val zeroY = scale.y(h, 0.0)
    val stepX = if (values.size > 1) w / (values.size - 1) else 0f
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
            drawPath(
                path = Path().apply { moveTo(xa, ya); lineTo(xb, yb); lineTo(xb, zeroY); lineTo(xa, zeroY); close() },
                color = color,
            )
        } else {
            val t = (-a) / (b - a)
            val xCross = xa + (t * (xb - xa)).toFloat()
            val colorA = if (a >= 0) MobileColors.Recover.copy(alpha = 0.30f) else MobileColors.High.copy(alpha = 0.30f)
            drawPath(
                path = Path().apply { moveTo(xa, ya); lineTo(xCross, zeroY); lineTo(xa, zeroY); close() },
                color = colorA,
            )
            val colorB = if (b >= 0) MobileColors.Recover.copy(alpha = 0.30f) else MobileColors.High.copy(alpha = 0.30f)
            drawPath(
                path = Path().apply { moveTo(xCross, zeroY); lineTo(xb, yb); lineTo(xb, zeroY); close() },
                color = colorB,
            )
        }
    }
}

private fun DrawScope.drawLineChart(values: List<Double?>, scale: Scale, color: Color, pointRadius: Float = 3f) {
    val w = size.width
    val h = size.height
    val stepX = if (values.size > 1) w / (values.size - 1) else 0f
    val path = Path()
    var started = false
    values.forEachIndexed { i, v ->
        if (v == null) return@forEachIndexed
        val x = i * stepX
        val y = scale.y(h, v)
        if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
        drawCircle(color = color, radius = pointRadius, center = Offset(x, y))
    }
    if (started) drawPath(path = path, color = color, style = Stroke(width = 2f))
}

private fun DrawScope.drawBars(values: List<Double?>, scale: Scale, color: Color) {
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
    }
}

private fun List<DashboardComputed>.firstAndLastDateLabels(): List<String> {
    if (isEmpty()) return listOf("—", "—")
    val sorted = sortedBy { it.date }
    return listOf(sorted.first().date.takeLast(5), sorted.last().date.takeLast(5))
}

// MARK: --- 体組成

@Composable
fun WeightChart(rows: List<DashboardComputed>, modifier: Modifier = Modifier) {
    val values = rows.map { it.weightKg }
    val nonNull = values.filterNotNull()
    val heightCm = rows.firstNotNullOfOrNull { it.heightCm }
    val bands = weightBandsForHeight(heightCm)
    val scale = when {
        nonNull.isEmpty() -> Scale(60.0, 100.0)
        bands.isNotEmpty() -> expandWithBands(nonNull, bands, 0.08)
        else -> expandScale(nonNull, 0.10)
    }
    val current = nonNull.lastOrNull()
    val today = rows.lastOrNull()
    MiniChartCard(
        title = "Weight (kg)",
        info = "身長 ${heightCm?.let { "%.0f cm".format(it) } ?: "—"} の BMI 基準で帯を表示。" +
            "緑 = BMI 18.5-25 (普通) / 黄 = 25-30 (過体重) / 赤 = 30+ (肥満)",
        yLabels = listOf(
            "%.1f".format(scale.max),
            "%.1f".format((scale.min + scale.max) / 2),
            "%.1f".format(scale.min),
        ),
        xLabels = rows.firstAndLastDateLabels(),
        modifier = modifier,
        valueLabel = current?.let { "%.1f kg".format(it) },
        valueColor = bandStateColor(today?.bmi, Bmi.bands),
        pointCount = rows.size,
        pointAt = { i ->
            rows.getOrNull(i)?.let { "${it.date}: ${it.weightKg?.let { v -> "%.2f".format(v) } ?: "—"} kg" } ?: ""
        },
        drawContent = {
            drawBands(bands, scale)
            drawLineChart(values, scale, MobileColors.Done)
        },
    )
}

@Composable
fun BmiChart(rows: List<DashboardComputed>, modifier: Modifier = Modifier) {
    val values = rows.map { it.bmi }
    val nonNull = values.filterNotNull()
    val scale = if (nonNull.isNotEmpty()) expandWithBands(nonNull, Bmi.bands, 0.12) else Scale(18.0, 35.0)
    val today = rows.lastOrNull()
    MiniChartCard(
        title = "BMI",
        info = "体重 ÷ (身長 m)²。普通 18.5-25 / 過体重 25-30 / 肥満 1 度 30-35 / 肥満 2 度 35+",
        yLabels = listOf(
            "%.1f".format(scale.max),
            "%.1f".format((scale.min + scale.max) / 2),
            "%.1f".format(scale.min),
        ),
        xLabels = rows.firstAndLastDateLabels(),
        modifier = modifier,
        valueLabel = today?.bmi?.let { "%.1f".format(it) },
        valueColor = bandStateColor(today?.bmi, Bmi.bands),
        pointCount = rows.size,
        pointAt = { i ->
            rows.getOrNull(i)?.let { "${it.date}: ${it.bmi?.let { v -> "%.1f".format(v) } ?: "—"}" } ?: ""
        },
        drawContent = {
            drawBands(Bmi.bands, scale)
            drawLineChart(values, scale, MobileColors.Done)
        },
    )
}

// MARK: --- カロリー収支

@Composable
fun DeficitChart(rows: List<DashboardComputed>, modifier: Modifier = Modifier) {
    val values = rows.map { it.deficitKcal }
    val nonNull = values.filterNotNull()
    val scale = if (nonNull.isNotEmpty()) symmetricScale(nonNull) else Scale(-500.0, 500.0)
    val today = rows.lastOrNull()
    MiniChartCard(
        title = "Deficit (kcal/day)",
        info = "TDEE − Intake。プラス = 赤字 (痩せ方向、緑エリア)、マイナス = サープラス (赤エリア)。" +
            "減量中は +500 kcal/日が目安 (破線が目標)",
        yLabels = listOf(
            "%+.0f".format(scale.max),
            "0",
            "%+.0f".format(scale.min),
        ),
        xLabels = rows.firstAndLastDateLabels(),
        modifier = modifier,
        valueLabel = today?.deficitKcal?.let { "%+,.0f kcal".format(it) },
        valueColor = bandStateColor(today?.deficitKcal, Deficit.bands),
        pointCount = rows.size,
        pointAt = { i ->
            rows.getOrNull(i)?.let { "${it.date}: ${it.deficitKcal?.let { d -> "%+.0f kcal".format(d) } ?: "—"}" } ?: ""
        },
        drawContent = {
            drawDeficitArea(values, scale)
            drawZeroLine(scale)
            drawReferenceLine(500.0, scale, MobileColors.Recover.copy(alpha = 0.5f))
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
    val today = rows.lastOrNull()
    MiniChartCard(
        title = "TDEE vs Intake",
        info = "TDEE = 1 日の総消費カロリー、Intake = 1 日の摂取カロリー。" +
            "Intake < TDEE になっていれば deficit が出ている",
        yLabels = listOf(
            "%.0fk".format(scale.max / 1000),
            "%.0fk".format(scale.max / 2000),
            "0",
        ),
        xLabels = rows.firstAndLastDateLabels(),
        modifier = modifier,
        valueLabel = today?.let {
            val t = it.tdeeKcal?.toInt() ?: 0
            val i = it.intakeKcal?.toInt() ?: 0
            "$i / $t"
        },
        legend = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LegendChip("TDEE", MobileColors.Recover)
                LegendChip("Intake", MobileColors.High)
            }
        },
        pointCount = rows.size,
        pointAt = { i ->
            rows.getOrNull(i)?.let {
                "${it.date}: TDEE ${it.tdeeKcal?.toInt() ?: "—"} / Intake ${it.intakeKcal?.toInt() ?: "—"}"
            } ?: ""
        },
        drawContent = {
            drawLineChart(tdee, scale, MobileColors.Recover)
            drawLineChart(intake, scale, MobileColors.High)
        },
    )
}

// MARK: --- 栄養素 (P / F / C)

@Composable
fun ProteinChart(rows: List<DashboardComputed>, modifier: Modifier = Modifier) {
    val values = rows.map { it.proteinPerKg }
    val nonNull = values.filterNotNull()
    val scale = if (nonNull.isNotEmpty()) Scale(0.0, (nonNull.max() + 0.4).coerceAtLeast(2.5)) else Scale(0.0, 2.5)
    val today = rows.lastOrNull()
    MiniChartCard(
        title = "Protein (g/kg)",
        info = "体重あたりタンパク質摂取。減量中は 1.6+ g/kg が筋量維持の目安",
        yLabels = listOf(
            "%.1f".format(scale.max),
            "%.1f".format((scale.min + scale.max) / 2),
            "0",
        ),
        xLabels = rows.firstAndLastDateLabels(),
        modifier = modifier,
        valueLabel = today?.let {
            val g = it.proteinG?.let { v -> "%.0f g".format(v) } ?: "—"
            val pkg = it.proteinPerKg?.let { v -> " (%.2f)".format(v) } ?: ""
            g + pkg
        },
        valueColor = bandStateColor(today?.proteinPerKg, Protein.bands),
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
fun FatChart(rows: List<DashboardComputed>, modifier: Modifier = Modifier) {
    val values = rows.map { it.fatPerKg }
    val nonNull = values.filterNotNull()
    val scale = if (nonNull.isNotEmpty()) Scale(0.0, (nonNull.max() + 0.3).coerceAtLeast(2.0)) else Scale(0.0, 2.0)
    val today = rows.lastOrNull()
    MiniChartCard(
        title = "Fat (g/kg)",
        info = "体重あたり脂質摂取。ホルモン維持の最低 0.8 g/kg、適正は 0.8-1.5、高過ぎはカロリー過多に",
        yLabels = listOf(
            "%.1f".format(scale.max),
            "%.1f".format((scale.min + scale.max) / 2),
            "0",
        ),
        xLabels = rows.firstAndLastDateLabels(),
        modifier = modifier,
        valueLabel = today?.let {
            val g = it.fatG?.let { v -> "%.0f g".format(v) } ?: "—"
            val pkg = it.fatPerKg?.let { v -> " (%.2f)".format(v) } ?: ""
            g + pkg
        },
        valueColor = bandStateColor(today?.fatPerKg, Fat.bands),
        pointCount = rows.size,
        pointAt = { i ->
            rows.getOrNull(i)?.let {
                "${it.date}: ${it.fatG?.let { v -> "%.0f g".format(v) } ?: "—"} (${it.fatPerKg?.let { v -> "%.2f g/kg".format(v) } ?: "—"})"
            } ?: ""
        },
        drawContent = {
            drawBands(Fat.bands, scale)
            drawLineChart(values, scale, MobileColors.Done)
        },
    )
}

@Composable
fun CarbsChart(rows: List<DashboardComputed>, modifier: Modifier = Modifier) {
    val values = rows.map { it.carbsPerKg }
    val nonNull = values.filterNotNull()
    val scale = if (nonNull.isNotEmpty()) Scale(0.0, (nonNull.max() + 0.5).coerceAtLeast(5.0)) else Scale(0.0, 5.0)
    val today = rows.lastOrNull()
    MiniChartCard(
        title = "Carbs (g/kg)",
        info = "体重あたり炭水化物。減量中は 2-4 g/kg がふつう。極端な低糖質は意図的ならよし",
        yLabels = listOf(
            "%.1f".format(scale.max),
            "%.1f".format((scale.min + scale.max) / 2),
            "0",
        ),
        xLabels = rows.firstAndLastDateLabels(),
        modifier = modifier,
        valueLabel = today?.let {
            val g = it.carbsG?.let { v -> "%.0f g".format(v) } ?: "—"
            val pkg = it.carbsPerKg?.let { v -> " (%.2f)".format(v) } ?: ""
            g + pkg
        },
        valueColor = bandStateColor(today?.carbsPerKg, Carbs.bands),
        pointCount = rows.size,
        pointAt = { i ->
            rows.getOrNull(i)?.let {
                "${it.date}: ${it.carbsG?.let { v -> "%.0f g".format(v) } ?: "—"} (${it.carbsPerKg?.let { v -> "%.2f g/kg".format(v) } ?: "—"})"
            } ?: ""
        },
        drawContent = {
            drawBands(Carbs.bands, scale)
            drawLineChart(values, scale, MobileColors.Done)
        },
    )
}

// MARK: --- 行動・回復・コンディション

@Composable
fun StepsChart(rows: List<DashboardComputed>, modifier: Modifier = Modifier) {
    val values = rows.map { it.steps?.toDouble() }
    val nonNull = values.filterNotNull()
    val scale = if (nonNull.isNotEmpty()) Scale(0.0, (nonNull.max() * 1.15).coerceAtLeast(10_000.0)) else Scale(0.0, 12_000.0)
    val today = rows.lastOrNull()
    MiniChartCard(
        title = "Steps",
        info = "1 日の歩数。座位中心 <5k / 低活動 5-7.5k / ふつう 7.5-10k / 活動的 10k+",
        yLabels = listOf(
            "%.0fk".format(scale.max / 1000),
            "%.0fk".format(scale.max / 2000),
            "0",
        ),
        xLabels = rows.firstAndLastDateLabels(),
        modifier = modifier,
        valueLabel = today?.steps?.let { "%,d".format(it) },
        valueColor = bandStateColor(today?.steps?.toDouble(), Steps.bands),
        pointCount = rows.size,
        pointAt = { i -> rows.getOrNull(i)?.let { "${it.date}: ${it.steps ?: "—"} 歩" } ?: "" },
        drawContent = {
            drawBands(Steps.bands, scale)
            drawBars(values, scale, MobileColors.Done)
        },
    )
}

@Composable
fun SleepChart(rows: List<DashboardComputed>, modifier: Modifier = Modifier) {
    val durations = rows.map { it.sleepHours }
    val nonNull = durations.filterNotNull()
    val scale = if (nonNull.isNotEmpty()) Scale(0.0, (nonNull.max() + 1).coerceAtLeast(10.0)) else Scale(0.0, 10.0)
    val today = rows.lastOrNull()
    MiniChartCard(
        title = "Sleep (hours)",
        info = "NIH 成人推奨 7-9h。不足 <6h / やや短い 6-7h / 適正 7-9h / 過剰 9h+",
        yLabels = listOf(
            "%.0fh".format(scale.max),
            "%.0fh".format((scale.min + scale.max) / 2),
            "0h",
        ),
        xLabels = rows.firstAndLastDateLabels(),
        modifier = modifier,
        valueLabel = today?.sleepHours?.let { "%.1f h".format(it) },
        valueColor = bandStateColor(today?.sleepHours, Sleep.bands),
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
    val avg = nonNull.average().takeIf { !it.isNaN() }
    val scale = if (nonNull.isNotEmpty()) expandScale(nonNull, 0.20) else Scale(20.0, 60.0)
    val today = rows.lastOrNull()
    val stateColor = when {
        today?.hrvMs == null || avg == null -> MobileColors.TextDim
        today.hrvMs >= avg -> MobileColors.Recover
        today.hrvMs >= avg * 0.85 -> MobileColors.Done
        else -> MobileColors.High
    }
    MiniChartCard(
        title = "HRV (ms)",
        info = "心拍変動 (Heart Rate Variability)。自律神経の状態を反映、高いほど回復済み。" +
            "破線は 7 日平均 = 自分の baseline。これより下が続けば疲労蓄積のサイン",
        yLabels = listOf(
            "%.0f".format(scale.max),
            "%.0f".format((scale.min + scale.max) / 2),
            "%.0f".format(scale.min),
        ),
        xLabels = rows.firstAndLastDateLabels(),
        modifier = modifier,
        valueLabel = today?.hrvMs?.let { "%.0f ms".format(it) },
        valueColor = stateColor,
        pointCount = rows.size,
        pointAt = { i -> rows.getOrNull(i)?.let { "${it.date}: ${it.hrvMs?.let { v -> "%.0f ms".format(v) } ?: "—"}" } ?: "" },
        drawContent = {
            avg?.let { drawReferenceLine(it, scale, MobileColors.TextDim) }
            drawLineChart(values, scale, MobileColors.Recover)
        },
    )
}

@Composable
fun RestingHrChart(rows: List<DashboardComputed>, modifier: Modifier = Modifier) {
    val values = rows.map { it.restingHrBpm?.toDouble() }
    val nonNull = values.filterNotNull()
    val scale = if (nonNull.isNotEmpty()) expandWithBands(nonNull, RestingHr.bands, 0.15) else Scale(50.0, 80.0)
    val today = rows.lastOrNull()
    MiniChartCard(
        title = "Resting HR (bpm)",
        info = "安静時心拍。心臓の効率を反映、低いほど健康。" +
            "運動者並み <60 / 良好 60-70 / 普通 70-80 / やや高 80-90 / 高 90+",
        yLabels = listOf(
            "%.0f".format(scale.max),
            "%.0f".format((scale.min + scale.max) / 2),
            "%.0f".format(scale.min),
        ),
        xLabels = rows.firstAndLastDateLabels(),
        modifier = modifier,
        valueLabel = today?.restingHrBpm?.let { "$it bpm" },
        valueColor = bandStateColor(today?.restingHrBpm?.toDouble(), RestingHr.bands),
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
    val today = rows.lastOrNull()
    MiniChartCard(
        title = "SpO2 (%)",
        info = "血中酸素飽和度。95% 以上が正常、90-95% は注意、90% 未満は低酸素",
        yLabels = listOf(
            "%.1f".format(scale.max),
            "%.1f".format((scale.min + scale.max) / 2),
            "%.1f".format(scale.min),
        ),
        xLabels = rows.firstAndLastDateLabels(),
        modifier = modifier,
        valueLabel = today?.spo2AvgPct?.let { "%.1f%%".format(it) },
        valueColor = bandStateColor(today?.spo2AvgPct, Spo2.bands),
        pointCount = rows.size,
        pointAt = { i -> rows.getOrNull(i)?.let { "${it.date}: ${it.spo2AvgPct?.let { v -> "%.1f%%".format(v) } ?: "—"}" } ?: "" },
        drawContent = {
            drawBands(Spo2.bands, scale)
            drawLineChart(values, scale, MobileColors.High)
        },
    )
}

// MARK: --- セッションチャート (本アプリの HIIT 履歴)

private const val HighPhaseTargetSec = 30.0
private const val ZoneTimeTargetPct = 60.0

@Composable
fun SessionHighDurationChart(sessions: List<SessionRecord>, modifier: Modifier = Modifier) {
    val ordered = sessions.sortedBy { it.startedAtMs }
    val values = ordered.map { s -> s.highDurationsSec.takeIf { it.isNotEmpty() }?.average() }
    val nonNull = values.filterNotNull()
    val scale = if (nonNull.isNotEmpty()) Scale(0.0, (nonNull.max() + 15).coerceAtLeast(60.0)) else Scale(0.0, 60.0)
    val latest = nonNull.lastOrNull()
    val stateColor = when {
        latest == null -> MobileColors.TextDim
        latest >= HighPhaseTargetSec -> MobileColors.Recover
        latest >= HighPhaseTargetSec * 0.7 -> MobileColors.Done
        else -> MobileColors.High
    }
    MiniChartCard(
        title = "High-phase duration (s)",
        info = "1 サイクル中の高強度区間秒数 (下限→上限) の平均。" +
            "同じ負荷でも有酸素能力が上がると延びる = 体力向上のシグナル。" +
            "破線は目安 30 秒 (Norwegian 4×4 帯への近接度)",
        yLabels = listOf(
            "%.0fs".format(scale.max),
            "%.0fs".format((scale.min + scale.max) / 2),
            "0s",
        ),
        xLabels = ordered.sessionFirstLastDates(),
        modifier = modifier,
        valueLabel = latest?.let { "%.0f s".format(it) },
        valueColor = stateColor,
        pointCount = ordered.size,
        pointAt = { i ->
            ordered.getOrNull(i)?.let { s ->
                val dt = java.time.Instant.ofEpochMilli(s.startedAtMs).atZone(java.time.ZoneId.systemDefault())
                val avgHigh = s.highDurationsSec.takeIf { it.isNotEmpty() }?.average()
                "${dt.toLocalDate()}: ${avgHigh?.let { v -> "%.1f s".format(v) } ?: "—"}"
            } ?: ""
        },
        drawContent = {
            drawReferenceLine(HighPhaseTargetSec, scale, MobileColors.Recover.copy(alpha = 0.6f))
            drawLineChart(values, scale, MobileColors.Recover)
        },
    )
}

@Composable
fun SessionZoneRatioChart(sessions: List<SessionRecord>, modifier: Modifier = Modifier) {
    val ordered = sessions.sortedBy { it.startedAtMs }
    val values = ordered.map { it.zoneRatio?.times(100.0) }
    val scale = Scale(0.0, 100.0)
    val latest = values.filterNotNull().lastOrNull()
    val stateColor = when {
        latest == null -> MobileColors.TextDim
        latest >= ZoneTimeTargetPct -> MobileColors.Recover
        latest >= ZoneTimeTargetPct * 0.7 -> MobileColors.Done
        else -> MobileColors.High
    }
    MiniChartCard(
        title = "Zone time (%)",
        info = "セッション中、心拍が上限-下限の目標帯に入っていた時間の割合。" +
            "60% 以上なら HIIT の質が高い (破線が 60% 目標)",
        yLabels = listOf("100", "50", "0"),
        xLabels = ordered.sessionFirstLastDates(),
        modifier = modifier,
        valueLabel = latest?.let { "%.0f%%".format(it) },
        valueColor = stateColor,
        pointCount = ordered.size,
        pointAt = { i ->
            ordered.getOrNull(i)?.let { s ->
                val dt = java.time.Instant.ofEpochMilli(s.startedAtMs).atZone(java.time.ZoneId.systemDefault())
                "${dt.toLocalDate()}: ${s.zoneRatio?.let { v -> "%.0f%%".format(v * 100) } ?: "—"}"
            } ?: ""
        },
        drawContent = {
            drawReferenceLine(ZoneTimeTargetPct, scale, MobileColors.Recover.copy(alpha = 0.6f))
            drawLineChart(values, scale, MobileColors.Done)
        },
    )
}

@Composable
fun SessionMaxBpmChart(
    sessions: List<SessionRecord>,
    upperBpm: Int,
    lowerBpm: Int,
    modifier: Modifier = Modifier,
) {
    val ordered = sessions.sortedBy { it.startedAtMs }
    val values = ordered.map { it.maxBpm?.toDouble() }
    val nonNull = values.filterNotNull()
    val zones = hrZonesFor(upperBpm, lowerBpm)
    val scale = if (nonNull.isNotEmpty()) expandWithBands(nonNull, zones, 0.08) else Scale(100.0, 200.0)
    val latest = nonNull.lastOrNull()
    MiniChartCard(
        title = "Session max HR (bpm)",
        info = "セッション中の最大心拍。設定の upper ($upperBpm) / lower ($lowerBpm) を境に" +
            "低/中/高強度の帯で表示。HIIT は高強度に届くのが望ましい",
        yLabels = listOf(
            "%.0f".format(scale.max),
            "%.0f".format((scale.min + scale.max) / 2),
            "%.0f".format(scale.min),
        ),
        xLabels = ordered.sessionFirstLastDates(),
        modifier = modifier,
        valueLabel = latest?.let { "%.0f bpm".format(it) },
        valueColor = hrZoneColor(latest?.let { hrCategoryFor(it, upperBpm, lowerBpm) }),
        pointCount = ordered.size,
        pointAt = { i ->
            ordered.getOrNull(i)?.let { s ->
                val dt = java.time.Instant.ofEpochMilli(s.startedAtMs).atZone(java.time.ZoneId.systemDefault())
                "${dt.toLocalDate()}: ${s.maxBpm ?: "—"} bpm"
            } ?: ""
        },
        drawContent = {
            drawBands(zones, scale)
            drawLineChart(values, scale, MobileColors.High)
        },
    )
}

@Composable
fun SessionAvgBpmChart(
    sessions: List<SessionRecord>,
    upperBpm: Int,
    lowerBpm: Int,
    modifier: Modifier = Modifier,
) {
    val ordered = sessions.sortedBy { it.startedAtMs }
    val values = ordered.map { it.avgBpm?.toDouble() }
    val nonNull = values.filterNotNull()
    val zones = hrZonesFor(upperBpm, lowerBpm)
    val scale = if (nonNull.isNotEmpty()) expandWithBands(nonNull, zones, 0.08) else Scale(80.0, 180.0)
    val latest = nonNull.lastOrNull()
    MiniChartCard(
        title = "Session avg HR (bpm)",
        info = "セッション中の平均心拍。中強度 (lower〜upper) に乗っているのが理想",
        yLabels = listOf(
            "%.0f".format(scale.max),
            "%.0f".format((scale.min + scale.max) / 2),
            "%.0f".format(scale.min),
        ),
        xLabels = ordered.sessionFirstLastDates(),
        modifier = modifier,
        valueLabel = latest?.let { "%.0f bpm".format(it) },
        valueColor = hrZoneColor(latest?.let { hrCategoryFor(it, upperBpm, lowerBpm) }),
        pointCount = ordered.size,
        pointAt = { i ->
            ordered.getOrNull(i)?.let { s ->
                val dt = java.time.Instant.ofEpochMilli(s.startedAtMs).atZone(java.time.ZoneId.systemDefault())
                "${dt.toLocalDate()}: ${s.avgBpm ?: "—"} bpm"
            } ?: ""
        },
        drawContent = {
            drawBands(zones, scale)
            drawLineChart(values, scale, MobileColors.High)
        },
    )
}

@Composable
fun HeartRate24hChart(
    samples: List<HeartRateSampleEntity>,
    upperBpm: Int,
    lowerBpm: Int,
    modifier: Modifier = Modifier,
) {
    val bpms = samples.map { it.bpm.toDouble() }
    val zones = hrZonesFor(upperBpm, lowerBpm)
    val scale = if (bpms.isNotEmpty()) {
        Scale(40.0, (bpms.max() + 10).coerceAtLeast(upperBpm + 15.0))
    } else Scale(40.0, 180.0)
    val sorted = samples.sortedBy { it.timestampMs }
    val tMin = sorted.firstOrNull()?.timestampMs ?: 0L
    val tMax = sorted.lastOrNull()?.timestampMs?.coerceAtLeast(tMin + 1) ?: 1L
    val latest = sorted.lastOrNull()
    MiniChartCard(
        title = "Heart rate today (bpm)",
        info = "今日と昨日の心拍 (5 分粒度)。設定の upper ($upperBpm) / lower ($lowerBpm) を境に" +
            "低/中/高強度の帯。朝の HIIT で高強度に届いていれば良いシグナル",
        yLabels = listOf(
            "%.0f".format(scale.max),
            "%.0f".format((scale.min + scale.max) / 2),
            "%.0f".format(scale.min),
        ),
        xLabels = if (sorted.isEmpty()) listOf("—") else listOf("0h", "12h", "24h"),
        modifier = modifier,
        valueLabel = latest?.let { "${it.bpm} bpm" },
        valueColor = hrZoneColor(latest?.let { hrCategoryFor(it.bpm.toDouble(), upperBpm, lowerBpm) }),
        pointCount = sorted.size,
        pointAt = { i ->
            sorted.getOrNull(i)?.let {
                val instant = java.time.Instant.ofEpochMilli(it.timestampMs).atZone(java.time.ZoneId.systemDefault())
                "${instant.toLocalTime().withSecond(0).withNano(0)}: ${it.bpm} bpm"
            } ?: ""
        },
        pointXResolver = PointXResolver { i, w ->
            val s = sorted.getOrNull(i)
            if (s == null) 0f
            else w * ((s.timestampMs - tMin).toFloat() / (tMax - tMin).coerceAtLeast(1L))
        },
        drawContent = {
            drawBands(zones, scale)
            if (sorted.isEmpty()) return@MiniChartCard
            val w = size.width
            val h = size.height
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

private fun List<SessionRecord>.sessionFirstLastDates(): List<String> {
    if (isEmpty()) return listOf("—", "—")
    val sorted = sortedBy { it.startedAtMs }
    fun fmt(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString().takeLast(5)
    return listOf(fmt(sorted.first().startedAtMs), fmt(sorted.last().startedAtMs))
}

private fun hrZoneColor(label: String?): Color = when (label) {
    "高強度" -> MobileColors.High
    "中強度" -> MobileColors.Recover
    "低強度" -> MobileColors.Done
    else -> MobileColors.TextDim
}

