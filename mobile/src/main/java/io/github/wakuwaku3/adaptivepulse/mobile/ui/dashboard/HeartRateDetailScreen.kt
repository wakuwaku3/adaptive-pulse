package io.github.wakuwaku3.adaptivepulse.mobile.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import io.github.wakuwaku3.adaptivepulse.mobile.store.HeartRateSampleEntity
import io.github.wakuwaku3.adaptivepulse.mobile.ui.MobileColors

/**
 * 当日 (+昨日) の HR 時系列をシンプルな折れ線で描く。ソース別の色分けは飽和すると読めないので、
 * 凡例は出さず全サンプルを 1 本にプロットする (avg/min/max は数値で別途出す)。
 */
@Composable
fun HeartRateDetailScreen(samples: List<HeartRateSampleEntity>, today: DashboardComputed?) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("HEART RATE TODAY", style = MaterialTheme.typography.labelMedium, color = MobileColors.TextDim)
                    val avg = today?.let {
                        // DashboardComputed には avg が無いので、samples から計算
                        if (samples.isEmpty()) null else samples.map { it.bpm }.average().toInt()
                    }
                    val min = samples.minOfOrNull { it.bpm }
                    val max = samples.maxOfOrNull { it.bpm }
                    KeyValueRow("Avg", avg?.let { "$it bpm" } ?: "—")
                    KeyValueRow("Min", min?.let { "$it bpm" } ?: "—")
                    KeyValueRow("Max", max?.let { "$it bpm" } ?: "—")
                    KeyValueRow("Resting (HC)", today?.restingHrBpm?.let { "$it bpm" } ?: "—")
                    KeyValueRow("HRV (RMSSD)", today?.hrvMs?.let { "%.0f ms".format(it) } ?: "—")
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("TIMELINE", style = MaterialTheme.typography.labelMedium, color = MobileColors.TextDim)
                    HrLineChart(samples)
                }
            }
        }
        // ソース別のサンプル数 (どの writer が書いてるかの把握用)
        item {
            val bySource = samples.groupBy { it.sourcePackage }.mapValues { it.value.size }
            if (bySource.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("SAMPLES BY SOURCE", style = MaterialTheme.typography.labelMedium, color = MobileColors.TextDim)
                        bySource.entries.sortedByDescending { it.value }.forEach { (pkg, count) ->
                            KeyValueRow(prettyPackage(pkg), "$count samples")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HrLineChart(samples: List<HeartRateSampleEntity>) {
    if (samples.isEmpty()) {
        Text("No samples in last 2 days", color = MobileColors.TextDim)
        return
    }
    val sorted = samples.sortedBy { it.timestampMs }
    val xMin = sorted.first().timestampMs
    val xMax = sorted.last().timestampMs.coerceAtLeast(xMin + 1)
    val yMin = (sorted.minOf { it.bpm } - 5).coerceAtLeast(40)
    val yMax = (sorted.maxOf { it.bpm } + 5).coerceAtMost(220)
    Canvas(modifier = Modifier.fillMaxWidth().height(180.dp)) {
        val w = size.width
        val h = size.height
        val path = Path()
        var started = false
        sorted.forEach { sample ->
            val x = w * ((sample.timestampMs - xMin).toFloat() / (xMax - xMin).toFloat())
            val y = h * (1f - (sample.bpm - yMin).toFloat() / (yMax - yMin).toFloat())
            if (!started) {
                path.moveTo(x, y)
                started = true
            } else path.lineTo(x, y)
        }
        drawPath(path = path, color = MobileColors.High, style = Stroke(width = 2f))
        // 上限/下限の参考線
        listOf(yMin, (yMin + yMax) / 2, yMax).forEach { bpm ->
            val y = h * (1f - (bpm - yMin).toFloat() / (yMax - yMin).toFloat())
            drawLine(
                color = MobileColors.TextDim.copy(alpha = 0.2f),
                start = Offset(0f, y),
                end = Offset(w, y),
                strokeWidth = 0.5f,
            )
        }
    }
}
