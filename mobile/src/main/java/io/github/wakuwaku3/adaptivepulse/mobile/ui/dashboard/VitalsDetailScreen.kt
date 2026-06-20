package io.github.wakuwaku3.adaptivepulse.mobile.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.wakuwaku3.adaptivepulse.mobile.store.DailySnapshotEntity
import io.github.wakuwaku3.adaptivepulse.mobile.ui.MobileColors

/** SpO2 / 呼吸数 / 皮膚温の推移。Pixel Watch が書いている範囲を 7 日ぶん見る */
@Composable
fun VitalsDetailScreen(recent: List<DailySnapshotEntity>) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
    ) {
        if (recent.isEmpty()) {
            item { Text("No data yet", color = MobileColors.TextDim) }
        }
        items(recent, key = { it.date }) { snap ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(snap.date, style = MaterialTheme.typography.titleSmall)
                    val hasAny = listOfNotNull(snap.spo2AvgPct, snap.respiratoryRateAvg, snap.skinTemperatureDeltaC).isNotEmpty()
                    if (!hasAny) {
                        Text("No vitals recorded", color = MobileColors.TextDim)
                    } else {
                        KeyValueRow("SpO2 avg", snap.spo2AvgPct?.let { "%.1f%%".format(it) } ?: "—")
                        KeyValueRow("SpO2 min", snap.spo2MinPct?.let { "%.1f%%".format(it) } ?: "—")
                        KeyValueRow("Respiratory rate", snap.respiratoryRateAvg?.let { "%.1f /min".format(it) } ?: "—")
                        KeyValueRow("Skin temp Δ", snap.skinTemperatureDeltaC?.let { "%+.2f °C".format(it) } ?: "—")
                    }
                }
            }
        }
    }
}
