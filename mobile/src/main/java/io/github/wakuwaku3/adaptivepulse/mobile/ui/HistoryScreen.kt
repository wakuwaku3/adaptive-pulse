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
        item { PeriodSelector(current = period, onChange = onPeriodChange) }
        item { TodayCard(today = today) }

        // セッションも period に追従させる: 期間内に開始したものだけ通す
        val sessions = run {
            val sinceMs = System.currentTimeMillis() - period.days.toLong() * 86_400_000L
            items.map { it.record }.filter { it.startedAtMs >= sinceMs }
        }
        chartGrid(recentDays, hrSamples, sessions, upperBpm, lowerBpm)
    }
}

/**
 * 2 列で並べるミニチャート。1 行 = 横並び 2 枚。並び順:
 *  1. 体重・BMI (減量フェーズの全体進捗)
 *  2. deficit・TDEE vs intake (カロリー収支)
 *  3. Protein・Fat (主要栄養素 P / F)
 *  4. Carbs・Steps (栄養素 C と行動量)
 *  5. Sleep・HRV (回復)
 *  6. RHR・SpO2 (コンディション)
 *  7. TRAINING: 高強度区間・ゾーン滞在率・avg/max HR・HR 24h
 */
private fun LazyListScope.chartGrid(
    rows: List<DashboardComputed>,
    hrSamples: List<HeartRateSampleEntity>,
    sessions: List<SessionRecord>,
    upperBpm: Int,
    lowerBpm: Int,
) {
    item { ChartRow({ WeightChart(rows, it) }, { BmiChart(rows, it) }) }
    item { ChartRow({ DeficitChart(rows, it) }, { TdeeIntakeChart(rows, it) }) }
    item { ChartRow({ ProteinChart(rows, it) }, { FatChart(rows, it) }) }
    item { ChartRow({ CarbsChart(rows, it) }, { StepsChart(rows, it) }) }
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
    item { ChartRow({ SessionHighDurationChart(sessions, it) }, { SessionZoneRatioChart(sessions, it) }) }
    item {
        ChartRow(
            { SessionAvgBpmChart(sessions, upperBpm, lowerBpm, it) },
            { SessionMaxBpmChart(sessions, upperBpm, lowerBpm, it) },
        )
    }
    item {
        // HR 24h は時系列なので 2 列の片側だと細すぎる。フルワイドで 1 枚として置く
        HeartRate24hChart(hrSamples, upperBpm, lowerBpm, Modifier.fillMaxWidth())
    }
}

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
