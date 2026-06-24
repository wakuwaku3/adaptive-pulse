package io.github.wakuwaku3.adaptivepulse.mobile.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.unit.sp
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
import androidx.compose.ui.text.style.TextOverflow
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
 * ダッシュボード用ミニチャート群。共通ルール:
 *  - **色**: 第 1 軸 = 緑 (Recover, 主)、第 2 軸 = 赤 (High, 比較)、第 3 軸 = 黄 (Done, 補助)。
 *    どのチャートも線・棒の基本色は緑に統一する (band 背景で良し悪しを示す)。
 *  - **単位**: タイトルには付けない、右上の現在値バッジに単位を載せる。Y 軸ラベルは数値のみ。
 *  - **ⓘ**: 全チャートに必ず表示 (説明はタップで Popup フローティング)。
 *  - **基準**: 単線チャートには適正帯か参照線を必ず置く (今が良いか悪いかが一目でわかる)。
 *  - **タップ詳細**: 点タップで該当日の値を floating tooltip で表示 (レイアウト不変、右端クランプ)。
 *  - **凡例 value 色**: 同じ band を共有する系列 (raw と移動平均、TDEE と Intake 等) は同一の
 *    `bandStateColor` ロジックで色付けする。raw だけ band 色、MA は TextDim、のような不揃いは避ける。
 */

private val ChartHeight = 110.dp
private val DefaultYAxisWidth = 30.dp

// 共通カラーパレット (順序がそのまま優先度)
private val PrimaryColor = MobileColors.Recover
private val SecondaryColor = MobileColors.High
private val TertiaryColor = MobileColors.Done

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

/**
 * 凡例 1 件 = ドット色 + ラベル + 現在値。色は線/棒の色と一致させ、値色は band 状態色を載せる。
 * Card の右上の value バッジを廃止して、グラフごとの「最新の数値」をここに 1 か所だけ表示する。
 */
data class LegendItem(
    val label: String,
    val value: String,
    val dotColor: Color,
    val valueColor: Color = MobileColors.TextDim,
)

@Composable
fun MiniChartCard(
    title: String,
    info: String,
    legend: List<LegendItem>,
    yLabels: List<String>,
    xLabels: List<String>,
    modifier: Modifier = Modifier,
    pointCount: Int = 0,
    pointAt: ((Int) -> String)? = null,
    pointXResolver: PointXResolver = PointXResolver { i, w ->
        if (pointCount > 1) i * w / (pointCount - 1) else 0f
    },
    drawContent: DrawScope.() -> Unit,
) {
    var tappedIndex by remember(pointCount) { mutableStateOf<Int?>(null) }
    var showInfo by remember { mutableStateOf(false) }
    var canvasW by remember { mutableStateOf(0f) }
    var tooltipW by remember { mutableStateOf(0) }
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ChartHeader(
                title = title,
                info = info,
                showInfo = showInfo,
                onToggleInfo = { showInfo = !showInfo },
                onDismissInfo = { showInfo = false },
            )
            LegendRow(legend)
            Row(modifier = Modifier.fillMaxWidth()) {
                YAxisLabels(yLabels)
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
            XAxisLabels(xLabels)
        }
    }
}

/** タイトル行: タイトル + ⓘ アイコンだけ。値は LegendRow に分離 */
@Composable
private fun ChartHeader(
    title: String,
    info: String,
    showInfo: Boolean,
    onToggleInfo: () -> Unit,
    onDismissInfo: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            title,
            color = MobileColors.TextDim,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Box(
            modifier = Modifier.pointerInput(Unit) {
                detectTapGestures { onToggleInfo() }
            },
        ) {
            Text("ⓘ", color = MobileColors.TextDim, fontSize = 11.sp)
            if (showInfo) {
                Popup(
                    alignment = Alignment.TopStart,
                    offset = IntOffset(0, 40),
                    onDismissRequest = onDismissInfo,
                    properties = PopupProperties(focusable = true),
                ) {
                    Card {
                        Text(
                            info,
                            modifier = Modifier.padding(10.dp).widthIn(max = 220.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

/**
 * すべてのグラフに 1 行で必ず置く凡例行: `● Label: value [● Label2: value2]`。
 * フォントサイズは 10sp 固定で 2 列レイアウトでも 1 行に収まる粒度に倒す。
 */
@Composable
private fun LegendRow(items: List<LegendItem>) {
    val legendFont = 9.sp
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("●", color = item.dotColor, fontSize = legendFont, maxLines = 1)
                Text("${item.label}:", color = MobileColors.TextDim, fontSize = legendFont, maxLines = 1)
                Text(item.value, color = item.valueColor, fontSize = legendFont, maxLines = 1)
            }
        }
    }
}

@Composable
private fun YAxisLabels(labels: List<String>) {
    Column(
        modifier = Modifier.width(DefaultYAxisWidth).height(ChartHeight),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.End,
    ) {
        labels.forEach { l ->
            Text(l, style = MaterialTheme.typography.labelSmall, color = MobileColors.TextDim, maxLines = 1)
        }
    }
}

@Composable
private fun XAxisLabels(labels: List<String>) {
    Row(modifier = Modifier.fillMaxWidth().padding(start = DefaultYAxisWidth + 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        labels.forEach { l ->
            Text(l, style = MaterialTheme.typography.labelSmall, color = MobileColors.TextDim)
        }
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

private fun DrawScope.drawReferenceLine(value: Double, scale: Scale, color: Color) {
    if (value !in scale.min..scale.max) return
    val y = scale.y(size.height, value)
    drawLine(
        color = color,
        start = Offset(0f, y),
        end = Offset(size.width, y),
        strokeWidth = 1.2f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
    )
}

/**
 * 参照線の「良い側」を薄緑、「悪い側」を薄赤で塗る。
 * - [direction] = AboveIsGood: 上が良い (HRV / High-phase duration / Zone time)
 * - [direction] = BelowIsGood: 下が良い (将来 Resting HR / 体重 / Intake などに使えるが
 *   現状は明示的な band がある指標が多いので使わない)
 */
private enum class GoodDirection { ABOVE, BELOW }

private fun DrawScope.drawGoodZone(refValue: Double, scale: Scale, direction: GoodDirection) {
    if (refValue !in scale.min..scale.max) return
    val y = scale.y(size.height, refValue)
    val good = PrimaryColor.copy(alpha = 0.12f)
    val bad = SecondaryColor.copy(alpha = 0.10f)
    when (direction) {
        GoodDirection.ABOVE -> {
            // 上 = 良い (緑) / 下 = 悪い (赤)
            drawRect(color = good, topLeft = Offset(0f, 0f), size = GeomSize(size.width, y))
            drawRect(color = bad, topLeft = Offset(0f, y), size = GeomSize(size.width, size.height - y))
        }
        GoodDirection.BELOW -> {
            drawRect(color = bad, topLeft = Offset(0f, 0f), size = GeomSize(size.width, y))
            drawRect(color = good, topLeft = Offset(0f, y), size = GeomSize(size.width, size.height - y))
        }
    }
}

/**
 * 0 線の上下を別色で塗り分ける (deficit chart 専用)。
 * 上 (正) = サープラス = 赤、下 (負) = 赤字 = 緑。
 */
private fun DrawScope.drawDeficitArea(values: List<Double?>, scale: Scale) {
    val w = size.width
    val h = size.height
    if (values.isEmpty()) return
    val zeroY = scale.y(h, 0.0)
    val stepX = if (values.size > 1) w / (values.size - 1) else 0f
    fun colorOf(v: Double) =
        if (v >= 0) SecondaryColor.copy(alpha = 0.30f) else PrimaryColor.copy(alpha = 0.30f)
    for (i in 0 until values.size - 1) {
        val a = values[i] ?: continue
        val b = values[i + 1] ?: continue
        val xa = i * stepX
        val xb = (i + 1) * stepX
        val ya = scale.y(h, a)
        val yb = scale.y(h, b)
        val crossesZero = (a > 0) != (b > 0)
        if (!crossesZero) {
            drawPath(
                path = Path().apply { moveTo(xa, ya); lineTo(xb, yb); lineTo(xb, zeroY); lineTo(xa, zeroY); close() },
                color = colorOf(a),
            )
        } else {
            val t = (-a) / (b - a)
            val xCross = xa + (t * (xb - xa)).toFloat()
            drawPath(
                path = Path().apply { moveTo(xa, ya); lineTo(xCross, zeroY); lineTo(xa, zeroY); close() },
                color = colorOf(a),
            )
            drawPath(
                path = Path().apply { moveTo(xCross, zeroY); lineTo(xb, yb); lineTo(xb, zeroY); close() },
                color = colorOf(b),
            )
        }
    }
}

private fun DrawScope.drawLineChart(
    values: List<Double?>,
    scale: Scale,
    color: Color,
    pointRadius: Float = 3f,
    strokeWidth: Float = 2f,
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
        if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
        // 点は線と同色で統一 (棒/線/点で色がブレないようにする)
        if (pointRadius > 0f && stepX > 6f) drawCircle(color = color, radius = pointRadius, center = Offset(x, y))
    }
    if (started) drawPath(path = path, color = color, style = Stroke(width = strokeWidth))
}


private fun DrawScope.drawBars(values: List<Double?>, scale: Scale, color: Color) {
    val w = size.width
    val h = size.height
    if (values.isEmpty()) return
    val slot = w / values.size
    val barWidth = (slot * 0.55f).coerceAtLeast(1f)
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

// MARK: --- 軸ラベル生成 (3 段: top / mid / bottom)

private fun yLabelsFor(scale: Scale, format: (Double) -> String): List<String> = listOf(
    format(scale.max),
    format((scale.min + scale.max) / 2),
    format(scale.min),
)

private fun List<DashboardComputed>.firstAndLastDateLabels(): List<String> {
    if (isEmpty()) return listOf("—", "—")
    val sorted = sortedBy { it.date }
    return listOf(sorted.first().date.takeLast(5), sorted.last().date.takeLast(5))
}

private fun formatPoint(date: String, value: Double?, unit: String, decimals: Int = 1): String {
    val v = value?.let { "%.${decimals}f".format(it) } ?: "—"
    return "$date: $v $unit"
}

// MARK: --- 個別チャート (1 軸 = 緑、単位は valueLabel に置く)

@Composable
fun WeightChart(rows: List<DashboardComputed>, modifier: Modifier = Modifier) {
    val values = rows.map { it.weightKg }
    val nonNull = values.filterNotNull()
    val heightCm = rows.firstNotNullOfOrNull { it.heightCm }
    val bands = weightBandsForHeight(heightCm)
    val maWindow = pickMaWindow(rows.size)
    // データ点が少ない (Day = 3) ときは移動平均が点になるだけで意味がないので出さない
    val showMa = rows.size >= 5 && nonNull.size >= maWindow
    val movingAvg = if (showMa) movingAverage(values, maWindow) else List(rows.size) { null }
    val maNonNull = movingAvg.filterNotNull()
    // scale はデータ実値中心 (band は背景描画で必要に応じて clamp 表示)。
    // expandWithBands を使うと band 全幅 (例: BMI 18.5-35 帯) を抱え込んで縦軸が無駄に広くなる。
    val combined = nonNull + maNonNull
    val scale = if (combined.isEmpty()) Scale(60.0, 100.0) else expandScale(combined, 0.10)
    val today = rows.lastOrNull()
    val latestMa = maNonNull.lastOrNull()
    // raw 値と同じ band 判定 (BMI 帯) で色付けするため、MA kg を BMI に換算する
    val latestMaBmi = if (heightCm != null && heightCm > 0 && latestMa != null) {
        val m = heightCm / 100.0
        latestMa / (m * m)
    } else null
    MiniChartCard(
        title = "Weight",
        info = "身長 ${heightCm?.let { "%.0f cm".format(it) } ?: "—"} の BMI 基準で帯を描画。" +
            "緑 = 普通 (BMI 18.5-25) / 黄 = 過体重 (25-30) / 赤 = 肥満 (30+)。" +
            if (showMa) "赤線 = ${maWindow} 日移動平均" else "",
        legend = listOfNotNull(
            LegendItem(
                "Weight",
                today?.weightKg?.let { "%.1f kg".format(it) } ?: "—",
                PrimaryColor,
                bandStateColor(today?.bmi, Bmi.bands),
            ),
            if (showMa) LegendItem(
                "MA-${maWindow}d",
                latestMa?.let { "%.1f kg".format(it) } ?: "—",
                SecondaryColor,
                bandStateColor(latestMaBmi, Bmi.bands),
            ) else null,
        ),
        yLabels = yLabelsFor(scale) { "%.1f".format(it) },
        xLabels = rows.firstAndLastDateLabels(),
        modifier = modifier,
        pointCount = rows.size,
        pointAt = { i -> rows.getOrNull(i)?.let { formatPoint(it.date, it.weightKg, "kg", 2) } ?: "" },
        drawContent = {
            drawBands(bands, scale)
            drawLineChart(values, scale, PrimaryColor)
            if (showMa) drawLineChart(movingAvg, scale, SecondaryColor, pointRadius = 0f, strokeWidth = 2.5f)
        },
    )
}

@Composable
fun BmiChart(rows: List<DashboardComputed>, modifier: Modifier = Modifier) {
    val values = rows.map { it.bmi }
    val nonNull = values.filterNotNull()
    val maWindow = pickMaWindow(rows.size)
    val showMa = rows.size >= 5 && nonNull.size >= maWindow
    val movingAvg = if (showMa) movingAverage(values, maWindow) else List(rows.size) { null }
    val maNonNull = movingAvg.filterNotNull()
    val combined = nonNull + maNonNull
    val scale = if (combined.isEmpty()) Scale(18.0, 35.0) else expandScale(combined, 0.12)
    val today = rows.lastOrNull()
    val latestMa = maNonNull.lastOrNull()
    MiniChartCard(
        title = "BMI",
        info = "体重 ÷ (身長 m)²。普通 18.5-25 / 過体重 25-30 / 肥満 1 度 30-35 / 肥満 2 度 35+。" +
            if (showMa) "赤線 = ${maWindow} 日移動平均" else "",
        legend = listOfNotNull(
            LegendItem(
                "BMI",
                today?.bmi?.let { "%.1f".format(it) } ?: "—",
                PrimaryColor,
                bandStateColor(today?.bmi, Bmi.bands),
            ),
            if (showMa) LegendItem(
                "MA-${maWindow}d",
                latestMa?.let { "%.1f".format(it) } ?: "—",
                SecondaryColor,
                bandStateColor(latestMa, Bmi.bands),
            ) else null,
        ),
        yLabels = yLabelsFor(scale) { "%.1f".format(it) },
        xLabels = rows.firstAndLastDateLabels(),
        modifier = modifier,
        pointCount = rows.size,
        pointAt = { i -> rows.getOrNull(i)?.let { formatPoint(it.date, it.bmi, "") } ?: "" },
        drawContent = {
            drawBands(Bmi.bands, scale)
            drawLineChart(values, scale, PrimaryColor)
            if (showMa) drawLineChart(movingAvg, scale, SecondaryColor, pointRadius = 0f, strokeWidth = 2.5f)
        },
    )
}

@Composable
fun DeficitChart(rows: List<DashboardComputed>, modifier: Modifier = Modifier) {
    // intake − TDEE: 負 = 赤字 (緑下側), 正 = サープラス (赤上側)
    val values = rows.map { it.deficitKcal }
    val nonNull = values.filterNotNull()
    val scale = if (nonNull.isNotEmpty()) symmetricScale(nonNull) else Scale(-500.0, 500.0)
    val today = rows.lastOrNull()
    MiniChartCard(
        title = "Deficit",
        info = "Intake − TDEE。マイナス (下) = 赤字 (痩せ方向、緑) / プラス (上) = サープラス (赤)。" +
            "減量中は -500 kcal/日が目安 (破線が目標)",
        legend = listOf(
            LegendItem(
                "Deficit",
                today?.deficitKcal?.let { "%+,.0f kcal".format(it) } ?: "—",
                PrimaryColor,
                bandStateColor(today?.deficitKcal, Deficit.bands),
            ),
        ),
        yLabels = yLabelsFor(scale) { v ->
            when {
                v == 0.0 -> "0"
                abs(v) >= 1000 -> "%+.1fk".format(v / 1000)
                else -> "%+.0f".format(v)
            }
        },
        xLabels = rows.firstAndLastDateLabels(),
        modifier = modifier,
        pointCount = rows.size,
        pointAt = { i -> rows.getOrNull(i)?.let { formatPoint(it.date, it.deficitKcal, "kcal", 0) } ?: "" },
        drawContent = {
            drawDeficitArea(values, scale)
            drawZeroLine(scale)
            drawReferenceLine(-500.0, scale, PrimaryColor.copy(alpha = 0.5f))
            drawLineChart(values, scale, PrimaryColor)
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
        info = "TDEE = 消費カロリー (緑)、Intake = 摂取カロリー (赤)。Intake < TDEE で deficit",
        legend = listOf(
            LegendItem(
                "TDEE",
                today?.tdeeKcal?.let { "%,.0f kcal".format(it) } ?: "—",
                PrimaryColor,
            ),
            LegendItem(
                "Intake",
                today?.intakeKcal?.let { "%,.0f kcal".format(it) } ?: "—",
                SecondaryColor,
            ),
        ),
        yLabels = yLabelsFor(scale) { v -> if (v >= 1000) "%.1fk".format(v / 1000) else "%.0f".format(v) },
        xLabels = rows.firstAndLastDateLabels(),
        modifier = modifier,
        pointCount = rows.size,
        pointAt = { i ->
            rows.getOrNull(i)?.let {
                "${it.date}: TDEE ${it.tdeeKcal?.toInt() ?: "—"} / Intake ${it.intakeKcal?.toInt() ?: "—"}"
            } ?: ""
        },
        drawContent = {
            drawLineChart(tdee, scale, PrimaryColor)
            drawLineChart(intake, scale, SecondaryColor)
        },
    )
}

@Composable
fun ProteinChart(rows: List<DashboardComputed>, modifier: Modifier = Modifier) {
    val values = rows.map { it.proteinG }
    val nonNull = values.filterNotNull()
    val weightForBands = rows.lastOrNull()?.weightKg ?: rows.firstNotNullOfOrNull { it.weightKg }
    val bands = nutrientBandsForWeight(Protein.bands, weightForBands)
    val scale = when {
        nonNull.isEmpty() -> Scale(0.0, 200.0)
        bands.isNotEmpty() -> expandWithBands(nonNull, bands, 0.08)
        else -> Scale(0.0, (nonNull.max() + 30).coerceAtLeast(100.0))
    }
    val today = rows.lastOrNull()
    MiniChartCard(
        title = "Protein",
        info = "1 日のタンパク質 (g)。減量中の筋量維持には 1.6 g/kg 以上が目安。" +
            "緑 = 適正帯 (体重から逆算)",
        legend = listOf(
            LegendItem(
                "Protein",
                today?.proteinG?.let { "%.0f g".format(it) } ?: "—",
                PrimaryColor,
                bandStateColor(today?.proteinPerKg, Protein.bands),
            ),
        ),
        yLabels = yLabelsFor(scale) { "%.0f".format(it) },
        xLabels = rows.firstAndLastDateLabels(),
        modifier = modifier,
        pointCount = rows.size,
        pointAt = { i -> rows.getOrNull(i)?.let { formatPoint(it.date, it.proteinG, "g", 0) } ?: "" },
        drawContent = {
            drawBands(bands, scale)
            drawLineChart(values, scale, PrimaryColor)
        },
    )
}

@Composable
fun FatChart(rows: List<DashboardComputed>, modifier: Modifier = Modifier) {
    val values = rows.map { it.fatG }
    val nonNull = values.filterNotNull()
    val weightForBands = rows.lastOrNull()?.weightKg ?: rows.firstNotNullOfOrNull { it.weightKg }
    val bands = nutrientBandsForWeight(Fat.bands, weightForBands)
    val scale = when {
        nonNull.isEmpty() -> Scale(0.0, 150.0)
        bands.isNotEmpty() -> expandWithBands(nonNull, bands, 0.08)
        else -> Scale(0.0, (nonNull.max() + 30).coerceAtLeast(100.0))
    }
    val today = rows.lastOrNull()
    MiniChartCard(
        title = "Fat",
        info = "1 日の脂質 (g)。ホルモン維持の最低 0.8 g/kg、適正は 0.8-1.5 g/kg。" +
            "緑 = 適正帯 (体重から逆算)",
        legend = listOf(
            LegendItem(
                "Fat",
                today?.fatG?.let { "%.0f g".format(it) } ?: "—",
                PrimaryColor,
                bandStateColor(today?.fatPerKg, Fat.bands),
            ),
        ),
        yLabels = yLabelsFor(scale) { "%.0f".format(it) },
        xLabels = rows.firstAndLastDateLabels(),
        modifier = modifier,
        pointCount = rows.size,
        pointAt = { i -> rows.getOrNull(i)?.let { formatPoint(it.date, it.fatG, "g", 0) } ?: "" },
        drawContent = {
            drawBands(bands, scale)
            drawLineChart(values, scale, PrimaryColor)
        },
    )
}

@Composable
fun CarbsChart(rows: List<DashboardComputed>, modifier: Modifier = Modifier) {
    val values = rows.map { it.carbsG }
    val nonNull = values.filterNotNull()
    val weightForBands = rows.lastOrNull()?.weightKg ?: rows.firstNotNullOfOrNull { it.weightKg }
    val bands = nutrientBandsForWeight(Carbs.bands, weightForBands)
    val scale = when {
        nonNull.isEmpty() -> Scale(0.0, 400.0)
        bands.isNotEmpty() -> expandWithBands(nonNull, bands, 0.08)
        else -> Scale(0.0, (nonNull.max() + 50).coerceAtLeast(300.0))
    }
    val today = rows.lastOrNull()
    MiniChartCard(
        title = "Carbs",
        info = "1 日の炭水化物 (g)。減量中は 2-4 g/kg がふつう。極端な低糖質は意図的ならよし",
        legend = listOf(
            LegendItem(
                "Carbs",
                today?.carbsG?.let { "%.0f g".format(it) } ?: "—",
                PrimaryColor,
                bandStateColor(today?.carbsPerKg, Carbs.bands),
            ),
        ),
        yLabels = yLabelsFor(scale) { "%.0f".format(it) },
        xLabels = rows.firstAndLastDateLabels(),
        modifier = modifier,
        pointCount = rows.size,
        pointAt = { i -> rows.getOrNull(i)?.let { formatPoint(it.date, it.carbsG, "g", 0) } ?: "" },
        drawContent = {
            drawBands(bands, scale)
            drawLineChart(values, scale, PrimaryColor)
        },
    )
}

@Composable
fun StepsChart(rows: List<DashboardComputed>, modifier: Modifier = Modifier) {
    val values = rows.map { it.steps?.toDouble() }
    val nonNull = values.filterNotNull()
    val scale = if (nonNull.isNotEmpty()) Scale(0.0, (nonNull.max() * 1.15).coerceAtLeast(10_000.0)) else Scale(0.0, 12_000.0)
    val today = rows.lastOrNull()
    MiniChartCard(
        title = "Steps",
        info = "1 日の歩数。座位中心 <5k / 低活動 5-7.5k / ふつう 7.5-10k / 活動的 10k+",
        legend = listOf(
            LegendItem(
                "Steps",
                today?.steps?.let { "%,d 歩".format(it) } ?: "—",
                PrimaryColor,
                bandStateColor(today?.steps?.toDouble(), Steps.bands),
            ),
        ),
        yLabels = yLabelsFor(scale) { v -> if (v >= 1000) "%.0fk".format(v / 1000) else "%.0f".format(v) },
        xLabels = rows.firstAndLastDateLabels(),
        modifier = modifier,
        pointCount = rows.size,
        pointAt = { i -> rows.getOrNull(i)?.let { "${it.date}: ${it.steps ?: "—"} 歩" } ?: "" },
        drawContent = {
            drawBands(Steps.bands, scale)
            drawBars(values, scale, PrimaryColor)
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
        title = "Sleep",
        info = "1 日の睡眠時間 (h)。NIH 成人推奨 7-9h。不足 <6h / やや短い 6-7h / 適正 7-9h / 過剰 9h+",
        legend = listOf(
            LegendItem(
                "Sleep",
                today?.sleepHours?.let { "%.1f h".format(it) } ?: "—",
                PrimaryColor,
                bandStateColor(today?.sleepHours, Sleep.bands),
            ),
        ),
        yLabels = yLabelsFor(scale) { "%.0f".format(it) },
        xLabels = rows.firstAndLastDateLabels(),
        modifier = modifier,
        pointCount = rows.size,
        pointAt = { i -> rows.getOrNull(i)?.let { formatPoint(it.date, it.sleepHours, "h") } ?: "" },
        drawContent = {
            drawBands(Sleep.bands, scale)
            drawBars(durations, scale, PrimaryColor)
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
        title = "Heart Rate Variability",
        info = "心拍変動 (ms)。自律神経の状態 = 回復度の指標。高いほど良い。" +
            "破線は期間平均 = 自分の baseline。それより上 (緑帯) なら回復済、下 (赤帯) なら疲労蓄積",
        legend = listOf(
            LegendItem(
                "Heart Rate Variability",
                today?.hrvMs?.let { "%.0f ms".format(it) } ?: "—",
                PrimaryColor,
                stateColor,
            ),
        ),
        yLabels = yLabelsFor(scale) { "%.0f".format(it) },
        xLabels = rows.firstAndLastDateLabels(),
        modifier = modifier,
        pointCount = rows.size,
        pointAt = { i -> rows.getOrNull(i)?.let { formatPoint(it.date, it.hrvMs, "ms", 0) } ?: "" },
        drawContent = {
            avg?.let {
                drawGoodZone(it, scale, GoodDirection.ABOVE)
                drawReferenceLine(it, scale, MobileColors.TextDim)
            }
            drawLineChart(values, scale, PrimaryColor)
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
        title = "Resting Heart Rate",
        info = "安静時心拍 (bpm)。心臓の効率を反映、低いほど健康。" +
            "緑 = 運動者並み <60 / 良好 60-70 / 黄 = 普通 70-80 / 赤 = やや高〜高 80+",
        legend = listOf(
            LegendItem(
                "Resting Heart Rate",
                today?.restingHrBpm?.let { "$it bpm" } ?: "—",
                PrimaryColor,
                bandStateColor(today?.restingHrBpm?.toDouble(), RestingHr.bands),
            ),
        ),
        yLabels = yLabelsFor(scale) { "%.0f".format(it) },
        xLabels = rows.firstAndLastDateLabels(),
        modifier = modifier,
        pointCount = rows.size,
        pointAt = { i -> rows.getOrNull(i)?.let { "${it.date}: ${it.restingHrBpm?.let { v -> "$v bpm" } ?: "—"}" } ?: "" },
        drawContent = {
            drawBands(RestingHr.bands, scale)
            drawLineChart(values, scale, PrimaryColor)
        },
    )
}

// MARK: --- セッション (HIIT)

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
        title = "High-phase",
        info = "1 サイクル中の高強度区間秒数 (下限→上限) の平均 (s)。" +
            "同じ負荷でも有酸素能力が上がると延びる = 体力向上のシグナル。" +
            "破線は目安 30 秒 (Norwegian 4×4 帯への近接度)",
        legend = listOf(
            LegendItem(
                "High-phase",
                latest?.let { "%.0f s".format(it) } ?: "—",
                PrimaryColor,
                stateColor,
            ),
        ),
        yLabels = yLabelsFor(scale) { "%.0f".format(it) },
        xLabels = ordered.sessionFirstLastDates(),
        modifier = modifier,
        pointCount = ordered.size,
        pointAt = { i ->
            ordered.getOrNull(i)?.let { s ->
                val dt = java.time.Instant.ofEpochMilli(s.startedAtMs).atZone(java.time.ZoneId.systemDefault())
                val avgHigh = s.highDurationsSec.takeIf { it.isNotEmpty() }?.average()
                "${dt.toLocalDate()}: ${avgHigh?.let { v -> "%.1f s".format(v) } ?: "—"}"
            } ?: ""
        },
        drawContent = {
            drawGoodZone(HighPhaseTargetSec, scale, GoodDirection.ABOVE)
            drawReferenceLine(HighPhaseTargetSec, scale, MobileColors.TextDim)
            drawLineChart(values, scale, PrimaryColor)
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
        title = "Zone time",
        info = "セッション中、心拍が上限-下限の目標帯に入っていた時間の割合 (%)。" +
            "60% 以上なら HIIT の質が高い (破線が 60% 目標)",
        legend = listOf(
            LegendItem(
                "Zone time",
                latest?.let { "%.0f %%".format(it) } ?: "—",
                PrimaryColor,
                stateColor,
            ),
        ),
        yLabels = listOf("100", "50", "0"),
        xLabels = ordered.sessionFirstLastDates(),
        modifier = modifier,
        pointCount = ordered.size,
        pointAt = { i ->
            ordered.getOrNull(i)?.let { s ->
                val dt = java.time.Instant.ofEpochMilli(s.startedAtMs).atZone(java.time.ZoneId.systemDefault())
                "${dt.toLocalDate()}: ${s.zoneRatio?.let { v -> "%.0f%%".format(v * 100) } ?: "—"}"
            } ?: ""
        },
        drawContent = {
            drawGoodZone(ZoneTimeTargetPct, scale, GoodDirection.ABOVE)
            drawReferenceLine(ZoneTimeTargetPct, scale, MobileColors.TextDim)
            drawLineChart(values, scale, PrimaryColor)
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
        title = "Session Max Heart Rate",
        info = "セッション中の最大心拍 (bpm)。設定の upper ($upperBpm) / lower ($lowerBpm) を境に" +
            "低/中/高強度の帯で表示。HIIT は高強度 (赤帯) に届くのが望ましい",
        legend = listOf(
            LegendItem(
                "Max Heart Rate",
                latest?.let { "%.0f bpm".format(it) } ?: "—",
                PrimaryColor,
                hrZoneColor(latest?.let { hrCategoryFor(it, upperBpm, lowerBpm) }),
            ),
        ),
        yLabels = yLabelsFor(scale) { "%.0f".format(it) },
        xLabels = ordered.sessionFirstLastDates(),
        modifier = modifier,
        pointCount = ordered.size,
        pointAt = { i ->
            ordered.getOrNull(i)?.let { s ->
                val dt = java.time.Instant.ofEpochMilli(s.startedAtMs).atZone(java.time.ZoneId.systemDefault())
                "${dt.toLocalDate()}: ${s.maxBpm ?: "—"} bpm"
            } ?: ""
        },
        drawContent = {
            drawBands(zones, scale)
            drawLineChart(values, scale, PrimaryColor)
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
        title = "Session Avg Heart Rate",
        info = "セッション中の平均心拍 (bpm)。中強度 (緑帯) に乗っているのが理想",
        legend = listOf(
            LegendItem(
                "Avg Heart Rate",
                latest?.let { "%.0f bpm".format(it) } ?: "—",
                PrimaryColor,
                hrZoneColor(latest?.let { hrCategoryFor(it, upperBpm, lowerBpm) }),
            ),
        ),
        yLabels = yLabelsFor(scale) { "%.0f".format(it) },
        xLabels = ordered.sessionFirstLastDates(),
        modifier = modifier,
        pointCount = ordered.size,
        pointAt = { i ->
            ordered.getOrNull(i)?.let { s ->
                val dt = java.time.Instant.ofEpochMilli(s.startedAtMs).atZone(java.time.ZoneId.systemDefault())
                "${dt.toLocalDate()}: ${s.avgBpm ?: "—"} bpm"
            } ?: ""
        },
        drawContent = {
            drawBands(zones, scale)
            drawLineChart(values, scale, PrimaryColor)
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
        title = "Heart Rate (Today)",
        info = "今日と昨日の心拍 (bpm, 5 分粒度)。設定の upper ($upperBpm) / lower ($lowerBpm) を境に" +
            "低/中/高強度の帯。朝の HIIT で高強度に届いていれば良いシグナル",
        legend = listOf(
            LegendItem(
                "Heart Rate",
                latest?.let { "${it.bpm} bpm" } ?: "—",
                PrimaryColor,
                hrZoneColor(latest?.let { hrCategoryFor(it.bpm.toDouble(), upperBpm, lowerBpm) }),
            ),
        ),
        yLabels = yLabelsFor(scale) { "%.0f".format(it) },
        xLabels = if (sorted.isEmpty()) listOf("—") else listOf("0h", "12h", "24h"),
        modifier = modifier,
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
            drawPath(path = path, color = PrimaryColor, style = Stroke(width = 1.5f))
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
