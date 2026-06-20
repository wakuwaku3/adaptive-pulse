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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.wakuwaku3.adaptivepulse.mobile.ui.MobileColors

/**
 * その日の常時把握したい指標だけを表示する集約カード。
 *  - 体組成 (体重 / 体脂肪 / 歩数)
 *  - カロリー収支 (TDEE / intake / **deficit**)
 *  - タンパク質 (g/kg を併記。減量中の LBM 維持の主要レバー)
 *
 * 睡眠 / HRV / RHR / BMR / SpO2 等は下のチャートグリッドで把握する。
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                MetricCell("Weight", today.weightKg?.let { "%.1f kg".format(it) } ?: "—")
                MetricCell(
                    "BMI",
                    today.bmi?.let { "%.1f".format(it) } ?: "—",
                    sub = today.bmi?.let { Bmi.categoryOf(it) },
                )
                MetricCell("Steps", today.steps?.toString() ?: "—")
            }
            DeficitRow(today)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val proteinAccent =
                    if ((today.proteinPerKg ?: 0.0) >= 1.6) MobileColors.Recover else MobileColors.Done
                MetricCell(
                    "Protein",
                    today.proteinG?.let { "%.0f g".format(it) } ?: "—",
                    sub = today.proteinPerKg?.let { "%.2f g/kg".format(it) },
                    accent = proteinAccent,
                )
            }
        }
    }
}

@Composable
private fun DeficitRow(today: DashboardComputed) {
    val deficit = today.deficitKcal
    val accent = when {
        deficit == null -> MobileColors.TextDim
        deficit >= 200.0 -> MobileColors.Recover
        deficit >= 0.0 -> MobileColors.Done
        else -> MobileColors.High
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        MetricCell("TDEE", today.tdeeKcal?.let { "%.0f kcal".format(it) } ?: "—")
        MetricCell("Intake", today.intakeKcal?.let { "%.0f kcal".format(it) } ?: "—")
        Column {
            Text("Deficit", style = MaterialTheme.typography.labelSmall, color = MobileColors.TextDim)
            Text(
                deficit?.let { "%+,.0f kcal".format(-it) } ?: "—",
                style = MaterialTheme.typography.titleMedium,
                color = accent,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun MetricCell(
    label: String,
    value: String,
    sub: String? = null,
    accent: androidx.compose.ui.graphics.Color? = null,
) {
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
