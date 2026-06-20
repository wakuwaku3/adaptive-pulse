package io.github.wakuwaku3.adaptivepulse.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import io.github.wakuwaku3.adaptivepulse.mobile.ui.dashboard.DashboardComputed
import io.github.wakuwaku3.adaptivepulse.mobile.ui.dashboard.TodayCard
import io.github.wakuwaku3.adaptivepulse.mobile.ui.dashboard.TrendChart
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val dateFormat = DateTimeFormatter.ofPattern("yyyy/M/d HH:mm")

/** 履歴 1 件 + 同期状態 */
data class HistoryItem(val record: SessionRecord, val pending: Boolean)

/**
 * 主画面。Today カード + 7-day trend + Sessions 一覧を縦に並べる。
 * UI ルール (`.claude/rules/ui.md`) で主画面 1 枚 + overflow menu サブ画面なので、ダッシュボードは
 * ここに統合し、詳細はサブ画面から開く。
 */
@Composable
fun HistoryScreen(
    items: List<HistoryItem>?,
    statusLine: String?,
    today: DashboardComputed?,
    recentDays: List<DashboardComputed>,
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
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
    ) {
        statusLine?.let {
            item {
                Text(it, color = MobileColors.TextDim, style = MaterialTheme.typography.bodySmall)
            }
        }
        item { TodayCard(today = today) }
        item { TrendChart(rows = recentDays) }
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
                // 高強度所要時間の平均: 同負荷で伸びる = 体力向上のシグナル
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
