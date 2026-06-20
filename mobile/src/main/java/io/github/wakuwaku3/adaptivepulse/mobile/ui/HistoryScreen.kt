package io.github.wakuwaku3.adaptivepulse.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.wakuwaku3.adaptivepulse.core.sync.SessionRecord
import io.github.wakuwaku3.adaptivepulse.mobile.store.HeartRateSampleEntity
import io.github.wakuwaku3.adaptivepulse.mobile.ui.dashboard.BmiChart
import io.github.wakuwaku3.adaptivepulse.mobile.ui.dashboard.DashboardComputed
import io.github.wakuwaku3.adaptivepulse.mobile.ui.dashboard.DeficitChart
import io.github.wakuwaku3.adaptivepulse.mobile.ui.dashboard.HeartRate24hChart
import io.github.wakuwaku3.adaptivepulse.mobile.ui.dashboard.HrvChart
import io.github.wakuwaku3.adaptivepulse.mobile.ui.dashboard.ProteinChart
import io.github.wakuwaku3.adaptivepulse.mobile.ui.dashboard.RestingHrChart
import io.github.wakuwaku3.adaptivepulse.mobile.ui.dashboard.SessionHighDurationChart
import io.github.wakuwaku3.adaptivepulse.mobile.ui.dashboard.SessionMaxBpmChart
import io.github.wakuwaku3.adaptivepulse.mobile.ui.dashboard.SessionZoneRatioChart
import io.github.wakuwaku3.adaptivepulse.mobile.ui.dashboard.SleepChart
import io.github.wakuwaku3.adaptivepulse.mobile.ui.dashboard.Spo2Chart
import io.github.wakuwaku3.adaptivepulse.mobile.ui.dashboard.StepsChart
import io.github.wakuwaku3.adaptivepulse.mobile.ui.dashboard.TdeeIntakeChart
import io.github.wakuwaku3.adaptivepulse.mobile.ui.dashboard.TodayCard
import io.github.wakuwaku3.adaptivepulse.mobile.ui.dashboard.WeightChart
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val dateFormat = DateTimeFormatter.ofPattern("yyyy/M/d HH:mm")

/** 履歴 1 件 + 同期状態 */
data class HistoryItem(val record: SessionRecord, val pending: Boolean)

/**
 * 主画面 = 唯一の画面。スクロール 1 本に Today カード + 2 列ミニチャートグリッド + Sessions を並べる。
 * 詳細用のサブ画面は持たず、全部このスクロール上で把握できる粒度に倒す。
 */
@Composable
fun HistoryScreen(
    items: List<HistoryItem>?,
    statusLine: String?,
    today: DashboardComputed?,
    recentDays: List<DashboardComputed>,
    hrSamples: List<HeartRateSampleEntity>,
) {
    if (items == null) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
    ) {
        statusLine?.let {
            item {
                Text(it, color = MobileColors.TextDim, style = MaterialTheme.typography.bodySmall)
            }
        }
        item { TodayCard(today = today) }

        chartGrid(recentDays, hrSamples, items.map { it.record })

        item {
            Text(
                "SESSIONS",
                style = MaterialTheme.typography.labelMedium,
                color = MobileColors.TextDim,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (items.isEmpty()) {
            item {
                Text(
                    "No sessions yet. Finish a workout on the watch!",
                    color = MobileColors.TextDim,
                )
            }
        }
        items(items, key = { it.record.id }) { item -> SessionCard(item) }
    }
}

/**
 * 2 列で並べるミニチャート。1 行 = 横並び 2 枚。種別はゴールから近い順に上から:
 *  1. 体重・BMI (減量フェーズ進捗)
 *  2. deficit・TDEE vs intake (今のカロリー収支)
 *  3. 歩数・タンパク質 (行動指標)
 *  4. 睡眠・HRV (回復)
 *  5. RHR・SpO2 (コンディション)
 *  6. HR 24h (詳細)
 *  7. セッション高強度区間秒数・最大心拍 (トレーニング品質)
 *  8. セッションゾーン滞在率 (品質)
 */
private fun LazyListScope.chartGrid(
    rows: List<DashboardComputed>,
    hrSamples: List<HeartRateSampleEntity>,
    sessions: List<io.github.wakuwaku3.adaptivepulse.core.sync.SessionRecord>,
) {
    item { ChartRow({ WeightChart(rows, it) }, { BmiChart(rows, it) }) }
    item { ChartRow({ DeficitChart(rows, it) }, { TdeeIntakeChart(rows, it) }) }
    item { ChartRow({ StepsChart(rows, it) }, { ProteinChart(rows, it) }) }
    item { ChartRow({ SleepChart(rows, it) }, { HrvChart(rows, it) }) }
    item { ChartRow({ RestingHrChart(rows, it) }, { Spo2Chart(rows, it) }) }
    item {
        Text(
            "TRAINING",
            style = MaterialTheme.typography.labelMedium,
            color = MobileColors.TextDim,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    item { ChartRow({ SessionHighDurationChart(sessions, it) }, { SessionMaxBpmChart(sessions, it) }) }
    item { ChartRow({ SessionZoneRatioChart(sessions, it) }, { HeartRate24hChart(hrSamples, it) }) }
}

/**
 * 2 列レイアウトの薄いヘルパー。各セルに `Modifier.fillMaxWidth()` を渡し、
 * 親 Row 側で weight(1f) を割って幅を半々にする。
 */
@Composable
private fun ChartRow(left: @Composable (Modifier) -> Unit, right: @Composable (Modifier) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) { left(Modifier.fillMaxWidth()) }
        Column(modifier = Modifier.weight(1f)) { right(Modifier.fillMaxWidth()) }
    }
}

@Composable
private fun SessionCard(item: HistoryItem) {
    val r = item.record
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(
                    Instant.ofEpochMilli(r.startedAtMs)
                        .atZone(ZoneId.systemDefault()).format(dateFormat),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (item.pending) {
                    Text("NOT SYNCED", color = MobileColors.Done, style = MaterialTheme.typography.labelSmall)
                }
            }
            Text(
                buildString {
                    append("${r.cycles}/${r.plannedCycles} cycles · ${formatDuration(r.durationSec)}")
                    r.calories?.let { append(" · ${it.toInt()} kcal") }
                    r.zoneRatio?.let { append(" · zone ${(it * 100).toInt()}%") }
                },
                color = MobileColors.Recover,
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (r.highDurationsSec.isNotEmpty()) {
                    Text(
                        "high avg ${formatDuration(r.highDurationsSec.average().toLong())}",
                        color = MobileColors.TextDim,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                r.avgBpm?.let {
                    Text(
                        "avg $it bpm",
                        color = MobileColors.TextDim,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (r.fatigueBrake) {
                    Text(
                        "FATIGUE BRAKE",
                        color = MobileColors.High,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

private fun formatDuration(totalSecs: Long): String =
    "%d:%02d".format(totalSecs / 60, totalSecs % 60)
