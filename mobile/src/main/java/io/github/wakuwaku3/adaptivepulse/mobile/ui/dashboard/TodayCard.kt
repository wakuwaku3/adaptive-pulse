package io.github.wakuwaku3.adaptivepulse.mobile.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.wakuwaku3.adaptivepulse.mobile.ui.MobileColors

/**
 * その日の常時把握したい指標だけを表示する集約カード。
 * 各値は band 判定に応じて緑 (良好) / 黄 (中立) / 赤 (注意) に着色する。
 */
@Composable
fun TodayCard(today: DashboardComputed?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "TODAY · ${today?.date ?: "—"}",
                style = MaterialTheme.typography.labelMedium,
                color = MobileColors.TextDim,
            )
            if (today == null) {
                Text("No data yet", color = MobileColors.TextDim)
                return@Column
            }
            // 体組成 (Steps はチャート側にまかせる)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MetricCell(
                    "Weight",
                    today.weightKg?.let { "%.1f kg".format(it) } ?: "—",
                    accent = bandStateColor(today.bmi, Bmi.bands),
                )
                MetricCell(
                    "BMI",
                    today.bmi?.let { "%.1f".format(it) } ?: "—",
                    accent = bandStateColor(today.bmi, Bmi.bands),
                )
            }
            // カロリー収支
            DeficitRow(today)
            // 栄養素 (P/F/C) — 絶対量 g のみ。色は g/kg ベースの band で判定
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MetricCell(
                    "Protein",
                    today.proteinG?.let { "%.0f g".format(it) } ?: "—",
                    accent = bandStateColor(today.proteinPerKg, Protein.bands),
                )
                MetricCell(
                    "Fat",
                    today.fatG?.let { "%.0f g".format(it) } ?: "—",
                    accent = bandStateColor(today.fatPerKg, Fat.bands),
                )
                MetricCell(
                    "Carbs",
                    today.carbsG?.let { "%.0f g".format(it) } ?: "—",
                    accent = bandStateColor(today.carbsPerKg, Carbs.bands),
                )
            }
        }
    }
}

@Composable
private fun DeficitRow(today: DashboardComputed) {
    val deficit = today.deficitKcal
    val accent = bandStateColor(deficit, Deficit.bands)
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        MetricCell("TDEE", today.tdeeKcal?.let { "%.0f kcal".format(it) } ?: "—")
        MetricCell("Intake", today.intakeKcal?.let { "%.0f kcal".format(it) } ?: "—")
        Column {
            Text("Deficit", style = MaterialTheme.typography.labelSmall, color = MobileColors.TextDim)
            Text(
                deficit?.let { "%+,.0f kcal".format(it) } ?: "—",
                style = MaterialTheme.typography.titleMedium,
                color = accent,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun MetricCell(label: String, value: String, sub: String? = null, accent: Color? = null) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MobileColors.TextDim)
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            color = accent ?: MaterialTheme.colorScheme.onSurface,
        )
        if (sub != null) {
            Text(sub, style = MaterialTheme.typography.labelSmall, color = MobileColors.TextDim)
        }
    }
}
