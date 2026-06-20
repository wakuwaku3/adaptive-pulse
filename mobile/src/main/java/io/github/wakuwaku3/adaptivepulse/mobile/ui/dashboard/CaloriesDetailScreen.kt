package io.github.wakuwaku3.adaptivepulse.mobile.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.wakuwaku3.adaptivepulse.mobile.health.HealthDataSource
import io.github.wakuwaku3.adaptivepulse.mobile.store.MetricBySourceEntity
import io.github.wakuwaku3.adaptivepulse.mobile.ui.MobileColors

/**
 * カロリーの内訳をデータソース別に並べる。
 * Google Health UI で「watch 4876 / phone 3168 / Fit 2024」と表示される現象を、本アプリ側で
 * 機械的に見える化するための画面。
 */
@Composable
fun CaloriesDetailScreen(today: DashboardComputed?, breakdown: List<MetricBySourceEntity>) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("AGGREGATE (HC)", style = MaterialTheme.typography.labelMedium, color = MobileColors.TextDim)
                    KeyValueRow("TDEE", today?.tdeeKcal?.let { "%.0f kcal".format(it) } ?: "—")
                    KeyValueRow("Intake", today?.intakeKcal?.let { "%.0f kcal".format(it) } ?: "—")
                    KeyValueRow(
                        "Deficit",
                        today?.deficitKcal?.let { "%+,.0f kcal".format(-it) } ?: "—",
                    )
                    KeyValueRow("BMR est. (Mifflin-St Jeor)", today?.bmrEstKcal?.let { "%.0f kcal".format(it) } ?: "—")
                }
            }
        }
        listOf(
            HealthDataSource.METRIC_TOTAL_KCAL to "Total calories by source",
            HealthDataSource.METRIC_ACTIVE_KCAL to "Active calories by source",
            HealthDataSource.METRIC_BASAL_KCAL to "Basal calories by source",
            HealthDataSource.METRIC_INTAKE_KCAL to "Intake by source",
        ).forEach { (metric, title) ->
            val rows = breakdown.filter { it.metricKey == metric }
            if (rows.isNotEmpty()) {
                item {
                    SourceBreakdownCard(title, rows)
                }
            }
        }
    }
}

@Composable
fun SourceBreakdownCard(title: String, rows: List<MetricBySourceEntity>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MobileColors.TextDim)
            rows.sortedByDescending { it.value }.forEach { row ->
                KeyValueRow(prettyPackage(row.sourcePackage), "%.0f".format(row.value))
            }
        }
    }
}

@Composable
internal fun KeyValueRow(label: String, value: String, accent: androidx.compose.ui.graphics.Color? = null) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MobileColors.TextDim)
        Text(value, color = accent ?: MaterialTheme.colorScheme.onSurface)
    }
}

/** よく出る writer は短い別名に置き換えて読みやすくする */
internal fun prettyPackage(pkg: String): String = when (pkg) {
    "com.google.android.apps.fitness" -> "Fit"
    "com.google.android.apps.healthdata" -> "Health Connect"
    "com.google.android.wearable.healthservices" -> "Watch (Health Services)"
    "com.google.android.apps.wellbeing" -> "Wellbeing"
    "jp.co.asken.asken" -> "Asken"
    else -> pkg
}
