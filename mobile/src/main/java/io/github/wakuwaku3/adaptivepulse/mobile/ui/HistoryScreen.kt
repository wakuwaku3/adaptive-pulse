package io.github.wakuwaku3.adaptivepulse.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
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
import io.github.wakuwaku3.adaptivepulse.mobile.ui.dashboard.CarbsChart
import io.github.wakuwaku3.adaptivepulse.mobile.ui.dashboard.DashboardComputed
import io.github.wakuwaku3.adaptivepulse.mobile.ui.dashboard.DeficitChart
import io.github.wakuwaku3.adaptivepulse.mobile.ui.dashboard.FatChart
import io.github.wakuwaku3.adaptivepulse.mobile.ui.dashboard.HeartRate24hChart
import io.github.wakuwaku3.adaptivepulse.mobile.ui.dashboard.HrvChart
import io.github.wakuwaku3.adaptivepulse.mobile.ui.dashboard.Period
import io.github.wakuwaku3.adaptivepulse.mobile.ui.dashboard.PeriodSelector
import io.github.wakuwaku3.adaptivepulse.mobile.ui.dashboard.ProteinChart
import io.github.wakuwaku3.adaptivepulse.mobile.ui.dashboard.RestingHrChart
import io.github.wakuwaku3.adaptivepulse.mobile.ui.dashboard.SessionAvgBpmChart
import io.github.wakuwaku3.adaptivepulse.mobile.ui.dashboard.SessionHighDurationChart
import io.github.wakuwaku3.adaptivepulse.mobile.ui.dashboard.SessionMaxBpmChart
import io.github.wakuwaku3.adaptivepulse.mobile.ui.dashboard.SessionZoneRatioChart
import io.github.wakuwaku3.adaptivepulse.mobile.ui.dashboard.SleepChart
import io.github.wakuwaku3.adaptivepulse.mobile.ui.dashboard.Spo2Chart
import io.github.wakuwaku3.adaptivepulse.mobile.ui.dashboard.StepsChart
import io.github.wakuwaku3.adaptivepulse.mobile.ui.dashboard.TdeeIntakeChart
import io.github.wakuwaku3.adaptivepulse.mobile.ui.dashboard.TodayCard
import io.github.wakuwaku3.adaptivepulse.mobile.ui.dashboard.WeightChart

/** 履歴 1 件 + 同期状態 (statusLine の "n local sessions" 表示用に保持) */
data class HistoryItem(val record: SessionRecord, val pending: Boolean)

/**
 * 主画面 = 唯一の画面。スクロール 1 本に Today カード + 2 列ミニチャートグリッドを並べる。
 * セッション履歴はカードリストではなく TRAINING セクションのグラフで把握する
 * (個別 1 件の情報よりトレンドが重要)。
 */
@Composable
fun HistoryScreen(
    items: List<HistoryItem>?,
    statusLine: String?,
    today: DashboardComputed?,
    recentDays: List<DashboardComputed>,
    hrSamples: List<HeartRateSampleEntity>,
    upperBpm: Int,
    lowerBpm: Int,
    period: Period,
    onPeriodChange: (Period) -> Unit,
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
        item { PeriodSelector(current = period, onChange = onPeriodChange) }

        // セッションも period に追従させる: 期間内に開始したものだけ通す
        val sessions = run {
            val sinceMs = System.currentTimeMillis() - period.days.toLong() * 86_400_000L
            items.map { it.record }.filter { it.startedAtMs >= sinceMs }
        }
        chartGrid(recentDays, hrSamples, sessions, upperBpm, lowerBpm)
    }
}

@Composable
private fun SectionHeader(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = MobileColors.TextDim,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/**
 * 2 列で並べるミニチャート。1 行 = 横並び 2 枚。並び順:
 *  1. BODY: 体重・BMI (減量フェーズの全体進捗)
 *  2. CALORIES: deficit・TDEE vs intake / Protein・Fat / Carbs
 *     (P/F/C はカロリー構成要素なので CALORIES に集約)
 *  3. RECOVERY: Sleep・HRV / RHR・SpO2
 *  4. TRAINING: Steps・HR 24h (日次系) / 高強度区間・ゾーン滞在率 / avg HR・max HR (セッション系)
 */
private fun LazyListScope.chartGrid(
    rows: List<DashboardComputed>,
    hrSamples: List<HeartRateSampleEntity>,
    sessions: List<SessionRecord>,
    upperBpm: Int,
    lowerBpm: Int,
) {
    item { SectionHeader("BODY") }
    item { ChartRow({ WeightChart(rows, it) }, { BmiChart(rows, it) }) }

    item { SectionHeader("CALORIES") }
    item { ChartRow({ DeficitChart(rows, it) }, { TdeeIntakeChart(rows, it) }) }
    item { ChartRow({ ProteinChart(rows, it) }, { FatChart(rows, it) }) }
    item { ChartRow({ CarbsChart(rows, it) }) }

    item { SectionHeader("RECOVERY") }
    item { ChartRow({ SleepChart(rows, it) }, { HrvChart(rows, it) }) }
    item { ChartRow({ RestingHrChart(rows, it) }, { Spo2Chart(rows, it) }) }

    item { SectionHeader("TRAINING") }
    item { ChartRow({ StepsChart(rows, it) }, { HeartRate24hChart(hrSamples, upperBpm, lowerBpm, it) }) }
    item { ChartRow({ SessionHighDurationChart(sessions, it) }, { SessionZoneRatioChart(sessions, it) }) }
    item {
        ChartRow(
            { SessionAvgBpmChart(sessions, upperBpm, lowerBpm, it) },
            { SessionMaxBpmChart(sessions, upperBpm, lowerBpm, it) },
        )
    }
}

@Composable
private fun ChartRow(
    left: @Composable (Modifier) -> Unit,
    right: (@Composable (Modifier) -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) { left(Modifier.fillMaxWidth()) }
        Column(modifier = Modifier.weight(1f)) { right?.invoke(Modifier.fillMaxWidth()) }
    }
}
