package io.github.wakuwaku3.adaptivepulse.mobile.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.github.wakuwaku3.adaptivepulse.core.strength.ProgressMetric
import io.github.wakuwaku3.adaptivepulse.core.strength.ProgressPoint
import io.github.wakuwaku3.adaptivepulse.core.strength.TrainingProgress
import io.github.wakuwaku3.adaptivepulse.core.strength.WorkoutRecord
import io.github.wakuwaku3.adaptivepulse.core.strength.trainingProgress
import io.github.wakuwaku3.adaptivepulse.mobile.strength.WorkoutActions
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * 種目別の成長ダッシュボード (Workout Progress)。1 種目 = 1 行のスモールマルチプルにし、
 * y 軸は行ごとに独立させる (種目間で負荷スケールが違いすぎるため 1 枚の共有グラフにしない)。
 * 表示はジム単一選択に束縛し、行は LazyColumn で遅延コンポーズするので
 * 種目数が増えても描画コストはビューポート分で頭打ちになる。
 */
@Composable
fun WorkoutProgressScreen(actions: WorkoutActions) {
    val catalog by actions.catalog.collectAsState(initial = null)
    var workouts by remember { mutableStateOf<List<WorkoutRecord>?>(null) }
    LaunchedEffect(Unit) { workouts = actions.history() }

    val gyms = catalog?.gyms.orEmpty()
    var selectedGymId by remember { mutableStateOf<String?>(null) }
    val gym = gyms.firstOrNull { it.id == (selectedGymId ?: catalog?.lastGymId) }
        ?: gyms.firstOrNull()

    if (gym == null) {
        CenteredNote("No gym registered yet — log a workout first.")
        return
    }
    val loaded = workouts
    val progress = remember(gym, loaded) { trainingProgress(gym, loaded.orEmpty()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            ProgressHeader(
                gymName = gym.name,
                otherGyms = gyms.filterNot { it.id == gym.id }.map { it.id to it.name },
                onSelectGym = { selectedGymId = it },
            )
        }
        if (loaded != null && progress.isEmpty()) {
            item { CenteredNote("No sets logged at this gym yet.") }
        }
        items(progress, key = { it.trainingId }) { ProgressRow(it) }
    }
}

@Composable
private fun CenteredNote(text: String) {
    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MobileColors.TextDim)
    }
}

@Composable
private fun ProgressHeader(
    gymName: String,
    otherGyms: List<Pair<String, String>>,
    onSelectGym: (String) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = gymName,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f, fill = false),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box {
            TextButton(onClick = { menuOpen = true }, enabled = otherGyms.isNotEmpty()) {
                Text("⌄", style = MaterialTheme.typography.titleLarge, color = MobileColors.TextDim)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                otherGyms.forEach { (id, name) ->
                    DropdownMenuItem(text = { Text(name) }, onClick = { menuOpen = false; onSelectGym(id) })
                }
            }
        }
        Box(modifier = Modifier.weight(1f))
        Box(modifier = Modifier.pointerInput(Unit) { detectTapGestures { showInfo = !showInfo } }) {
            Text("ⓘ", color = MobileColors.TextDim)
            if (showInfo) {
                Popup(
                    alignment = Alignment.TopEnd,
                    offset = IntOffset(0, 48),
                    onDismissRequest = { showInfo = false },
                    properties = PopupProperties(focusable = true),
                ) {
                    Card {
                        Text(
                            "種目ごとの伸びを 1 workout = 1 点で表示。負荷あり種目は推定 1RM " +
                                "(Epley 1985: 重量 × (1 + reps/30)) のセッション内最高値。" +
                                "10 reps 以下のセットを優先し (推定式の妥当性範囲、LeSuer 1997)、" +
                                "無い日は全セットからの参考値 (~)。負荷なし種目は最高 reps。" +
                                "y 軸は種目ごとに独立。",
                            modifier = Modifier.padding(10.dp).widthIn(max = 240.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

private val SparklineHeight = 40.dp

@Composable
private fun ProgressRow(progress: TrainingProgress) {
    val points = progress.points
    val latest = points.last()
    val previous = points.getOrNull(points.size - 2)
    val delta = previous?.let { latest.value - it.value }
    var tappedIndex by remember(points.size) { mutableStateOf<Int?>(null) }
    var canvasW by remember { mutableStateOf(0f) }
    var tooltipW by remember { mutableStateOf(0) }
    Card {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = progress.trainingName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${points.size}× · ${latest.atMs.toDateLabel()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MobileColors.TextDim,
                )
            }
            Box(
                modifier = Modifier
                    .width(120.dp)
                    .height(SparklineHeight)
                    .pointerInput(points.size) {
                        detectTapGestures { offset ->
                            val stepX = if (points.size > 1) size.width.toFloat() / (points.size - 1) else 0f
                            val idx = if (stepX > 0f) (offset.x / stepX).roundToInt() else 0
                            tappedIndex = idx.coerceIn(0, points.size - 1)
                        }
                    },
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    canvasW = size.width
                    drawSparkline(points.map { it.value })
                }
                tappedIndex?.let { ti ->
                    val point = points[ti]
                    val stepX = if (points.size > 1) canvasW / (points.size - 1) else 0f
                    val tipLeft = (ti * stepX - tooltipW / 2f)
                        .coerceIn(0f, (canvasW - tooltipW).coerceAtLeast(0f))
                    Box(
                        modifier = Modifier
                            .offset { IntOffset(tipLeft.toInt(), 0) }
                            .onSizeChanged { tooltipW = it.width }
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xE0000000))
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                            .pointerInput(Unit) { detectTapGestures { tappedIndex = null } },
                    ) {
                        Text(
                            "${point.atMs.toDateLabel()}: ${point.format(progress.metric)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            maxLines = 1,
                        )
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = latest.format(progress.metric),
                    style = MaterialTheme.typography.titleSmall,
                    textAlign = TextAlign.End,
                )
                Text(
                    text = delta?.let { "%+.1f".format(it).removeSuffix(".0") } ?: "–",
                    fontSize = 11.sp,
                    color = when {
                        delta == null -> MobileColors.TextDim
                        delta > 0 -> MobileColors.Recover
                        delta < 0 -> MobileColors.High
                        else -> MobileColors.TextDim
                    },
                )
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSparkline(values: List<Double>) {
    val color = MobileColors.Recover
    if (values.size == 1) {
        drawCircle(color = color, radius = 4f, center = Offset(size.width / 2, size.height / 2))
        return
    }
    val min = values.min()
    val max = values.max()
    val pad = ((max - min) * 0.15).coerceAtLeast(0.5)
    val lo = min - pad
    val range = (max + pad) - lo
    val stepX = size.width / (values.size - 1)
    val path = Path()
    values.forEachIndexed { i, v ->
        val x = i * stepX
        val y = (size.height * (1 - (v - lo) / range)).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path = path, color = color, style = Stroke(width = 2f))
    val lastY = (size.height * (1 - (values.last() - lo) / range)).toFloat()
    drawCircle(color = color, radius = 3.5f, center = Offset(size.width, lastY))
}

private fun ProgressPoint.format(metric: ProgressMetric): String = when (metric) {
    ProgressMetric.E1RM -> "${if (estimateOnly) "~" else ""}${"%.1f".format(value).removeSuffix(".0")} kg"
    ProgressMetric.REPS -> "${value.toInt()} reps"
}

private fun Long.toDateLabel(): String =
    Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()
        .format(DateTimeFormatter.ofPattern("MM-dd"))
