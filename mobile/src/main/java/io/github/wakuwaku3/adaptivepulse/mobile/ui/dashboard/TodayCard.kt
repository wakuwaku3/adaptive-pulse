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
 * その日の状況を 1 枚で見せる集約カード。Google Health の代替として deficit を主役にする。
 *
 * 表示優先度 (上から):
 *  1. 体重 / 体脂肪
 *  2. TDEE / intake / **deficit (主役)**
 *  3. PFC
 *  4. 睡眠 / HRV / RHR
 *  5. 歩数 / SpO2
 */
@Composable
fun TodayCard(today: DashboardComputed?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
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

            // 1. 体重 / 体脂肪
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                MetricCell("Weight", today.weightKg?.let { "%.1f kg".format(it) } ?: "—")
                MetricCell("Body fat", today.bodyFatPct?.let { "%.1f%%".format(it) } ?: "—")
                MetricCell("Steps", today.steps?.toString() ?: "—")
            }

            // 2. TDEE / intake / deficit (主役)
            DeficitRow(today)

            // 3. PFC
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                MetricCell(
                    "Protein",
                    today.proteinG?.let { "%.0f g".format(it) } ?: "—",
                    sub = today.proteinPerKg?.let { "%.2f g/kg".format(it) },
                    accent = if ((today.proteinPerKg ?: 0.0) >= 1.6) MobileColors.Recover else MobileColors.Done,
                )
                MetricCell("Fat", today.fatG?.let { "%.0f g".format(it) } ?: "—")
                MetricCell("Carbs", today.carbsG?.let { "%.0f g".format(it) } ?: "—")
            }

            // 4. 睡眠 / HRV / RHR
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                MetricCell(
                    "Sleep",
                    today.sleepHours?.let { "%.1f h".format(it) } ?: "—",
                    sub = today.sleepDeepMin?.let { deep -> "deep ${deep}m" },
                )
                MetricCell("HRV", today.hrvMs?.let { "%.0f ms".format(it) } ?: "—")
                MetricCell("Resting HR", today.restingHrBpm?.let { "$it bpm" } ?: "—")
            }

            // 5. 補助 (BMR推定 / SpO2)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                MetricCell("BMR est.", today.bmrEstKcal?.let { "%.0f kcal".format(it) } ?: "—")
                MetricCell("SpO2", today.spo2AvgPct?.let { "%.1f%%".format(it) } ?: "—")
                MetricCell("", "")
            }
        }
    }
}

@Composable
private fun DeficitRow(today: DashboardComputed) {
    val deficit = today.deficitKcal
    val accent = when {
        deficit == null -> MobileColors.TextDim
        deficit >= 200.0 -> MobileColors.Recover // しっかり deficit = 良
        deficit >= 0.0 -> MobileColors.Done // 微 deficit = ぼちぼち
        else -> MobileColors.High // surplus = 食べすぎ
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        MetricCell("TDEE", today.tdeeKcal?.let { "%.0f kcal".format(it) } ?: "—")
        MetricCell("Intake", today.intakeKcal?.let { "%.0f kcal".format(it) } ?: "—")
        Column(modifier = Modifier.padding(start = 0.dp)) {
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
private fun MetricCell(label: String, value: String, sub: String? = null, accent: androidx.compose.ui.graphics.Color? = null) {
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
