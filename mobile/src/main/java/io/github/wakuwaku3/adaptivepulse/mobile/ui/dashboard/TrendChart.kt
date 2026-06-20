package io.github.wakuwaku3.adaptivepulse.mobile.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import io.github.wakuwaku3.adaptivepulse.mobile.ui.MobileColors

/**
 * 過去 N 日 (デフォルト 7) の deficit と体重のトレンドを 1 枚に重ねる簡易スパークライン。
 * 軽量 Canvas 実装 (依存ライブラリを追加しない)。
 */
@Composable
fun TrendChart(rows: List<DashboardComputed>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("7-DAY TREND", style = MaterialTheme.typography.labelMedium, color = MobileColors.TextDim)
            if (rows.isEmpty()) {
                Text("No data yet", color = MobileColors.TextDim)
                return@Column
            }
            // 過去日順 (古い → 新しい) に揃える
            val ordered = rows.sortedBy { it.date }
            ChartLegend()
            Sparkline(
                values = ordered.map { it.deficitKcal },
                color = MobileColors.Recover,
                zeroLine = true,
            )
            Sparkline(
                values = ordered.map { it.weightKg },
                color = MobileColors.Done,
                zeroLine = false,
            )
            DateAxis(rows = ordered)
        }
    }
}

@Composable
private fun ChartLegend() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LegendDot("Deficit (kcal)", MobileColors.Recover)
        LegendDot("Weight (kg)", MobileColors.Done)
    }
}

@Composable
private fun LegendDot(label: String, color: Color) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(modifier = Modifier.height(8.dp).padding(0.dp)) {
            Canvas(modifier = Modifier.fillMaxWidth().height(8.dp)) {
                drawCircle(color = color, radius = 4f, center = Offset(4f, size.height / 2))
            }
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = MobileColors.TextDim)
    }
}

@Composable
private fun Sparkline(values: List<Double?>, color: Color, zeroLine: Boolean) {
    val nonNull = values.mapNotNull { it }
    if (nonNull.isEmpty()) {
        Box(modifier = Modifier.fillMaxWidth().height(60.dp))
        return
    }
    // deficit は中心に 0 を取りたいので両側にパディング、weight は最小〜最大の幅で表示
    val (yMin, yMax) = if (zeroLine) {
        val absMax = nonNull.map { kotlin.math.abs(it) }.max().coerceAtLeast(500.0)
        -absMax to absMax
    } else {
        val mn = nonNull.min()
        val mx = nonNull.max()
        val pad = ((mx - mn) * 0.1).coerceAtLeast(0.2)
        (mn - pad) to (mx + pad)
    }
    Canvas(
        modifier = Modifier.fillMaxWidth().height(60.dp),
    ) {
        val w = size.width
        val h = size.height
        if (zeroLine) {
            val zeroY = h * ((yMax - 0.0) / (yMax - yMin)).toFloat()
            drawLine(
                color = MobileColors.TextDim.copy(alpha = 0.4f),
                start = Offset(0f, zeroY),
                end = Offset(w, zeroY),
                strokeWidth = 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
            )
        }
        val stepX = if (values.size > 1) w / (values.size - 1) else 0f
        val path = Path()
        var started = false
        values.forEachIndexed { i, v ->
            if (v == null) return@forEachIndexed
            val x = i * stepX
            val y = h * ((yMax - v) / (yMax - yMin)).toFloat()
            if (!started) {
                path.moveTo(x, y)
                started = true
            } else path.lineTo(x, y)
            drawCircle(color = color, radius = 3f, center = Offset(x, y))
        }
        if (started) drawPath(path = path, color = color, style = Stroke(width = 2f))
    }
}

@Composable
private fun DateAxis(rows: List<DashboardComputed>) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        rows.forEach { row ->
            // YYYY-MM-DD の末尾 (MM-DD) だけ短く出す
            val short = row.date.takeLast(5)
            Text(short, style = MaterialTheme.typography.labelSmall, color = MobileColors.TextDim)
        }
    }
}
