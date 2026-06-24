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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.wakuwaku3.adaptivepulse.mobile.ui.MobileColors

/**
 * その日の常時把握したい指標だけを表示する集約カード。
 * 各値は band 判定に応じて緑 (良好) / 黄 (中立) / 赤 (注意) に着色する。
 *
 * [rows] は期間内の日次データ。Weight / BMI に移動平均を `sub` として併記するために使う。
 */
@Composable
fun TodayCard(today: DashboardComputed?, rows: List<DashboardComputed> = emptyList()) {
    val maWindow = pickMaWindow(rows.size)
    val showMa = rows.size >= 5
    val weightMa = if (showMa) movingAverage(rows.map { it.weightKg }, maWindow).filterNotNull().lastOrNull() else null
    val bmiMa = if (showMa) movingAverage(rows.map { it.bmi }, maWindow).filterNotNull().lastOrNull() else null
    // raw 値 (Weight kg) と派生値 (MA kg) を同じ band 色で着色するため、kg を BMI に換算
    val heightCm = rows.firstNotNullOfOrNull { it.heightCm } ?: today?.heightCm
    val weightMaBmi = if (heightCm != null && heightCm > 0 && weightMa != null) {
        val m = heightCm / 100.0
        weightMa / (m * m)
    } else null
    val weightMaColor = bandStateColor(weightMaBmi, Bmi.bands)
    val bmiMaColor = bandStateColor(bmiMa, Bmi.bands)
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
            // 体組成 (Steps はチャート側にまかせる)。値表記は内数 (raw(MA) 単位):
            //   ラベル "Weight(MA d3)" / 値 "91.4(91.5) kg"
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                BodyMetricCell(
                    label = "Weight" + if (weightMa != null) " (MA d$maWindow)" else "",
                    rawValue = today.weightKg,
                    maValue = weightMa,
                    unit = "kg",
                    decimals = 1,
                    rawColor = bandStateColor(today.bmi, Bmi.bands),
                    maColor = weightMaColor,
                )
                BodyMetricCell(
                    label = "BMI" + if (bmiMa != null) " (MA d$maWindow)" else "",
                    rawValue = today.bmi,
                    maValue = bmiMa,
                    unit = "",
                    decimals = 1,
                    rawColor = bandStateColor(today.bmi, Bmi.bands),
                    maColor = bmiMaColor,
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
    val extraSub = today.exerciseExtraKcal
        ?.takeIf { it > 0 }
        ?.let { "+exercise %.0f".format(it) }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        MetricCell(
            "TDEE",
            today.tdeeKcal?.let { "%.0f kcal".format(it) } ?: "—",
            sub = extraSub,
        )
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
private fun MetricCell(
    label: String,
    value: String,
    sub: String? = null,
    accent: Color? = null,
    subAccent: Color? = null,
) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MobileColors.TextDim)
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            color = accent ?: MaterialTheme.colorScheme.onSurface,
        )
        if (sub != null) {
            Text(
                sub,
                style = MaterialTheme.typography.labelSmall,
                color = subAccent ?: MobileColors.TextDim,
            )
        }
    }
}

/**
 * 体組成セル: raw(MA) 単位 の内数表記。raw と MA はそれぞれ band 色で着色する。
 * 例: `91.4(91.5) kg`、`29.8(29.9)`
 */
@Composable
private fun BodyMetricCell(
    label: String,
    rawValue: Double?,
    maValue: Double?,
    unit: String,
    decimals: Int,
    rawColor: Color,
    maColor: Color,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val valueText = buildAnnotatedString {
        if (rawValue != null) {
            withStyle(SpanStyle(color = rawColor)) {
                append("%.${decimals}f".format(rawValue))
            }
        } else {
            withStyle(SpanStyle(color = onSurface)) { append("—") }
        }
        if (maValue != null) {
            withStyle(SpanStyle(color = onSurface)) { append(" (") }
            withStyle(SpanStyle(color = maColor)) {
                append("%.${decimals}f".format(maValue))
            }
            withStyle(SpanStyle(color = onSurface)) { append(")") }
        }
        if (unit.isNotEmpty()) {
            withStyle(SpanStyle(color = onSurface)) { append(" $unit") }
        }
    }
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MobileColors.TextDim)
        Text(valueText, style = MaterialTheme.typography.titleMedium)
    }
}
