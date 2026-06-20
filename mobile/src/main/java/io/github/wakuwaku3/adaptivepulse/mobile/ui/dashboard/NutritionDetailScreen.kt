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

/** Asken など Nutrition writer の細目を 7 日分並べる。PFC + 細目 + 摂取カロリー */
@Composable
fun NutritionDetailScreen(recent: List<DailySnapshotEntity>) {
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
                    if (snap.intakeKcal == null) {
                        Text("No food logged", color = MobileColors.TextDim)
                    } else {
                        KeyValueRow("Intake", "%.0f kcal".format(snap.intakeKcal))
                        KeyValueRow("Protein", snap.proteinG?.let { "%.1f g".format(it) } ?: "—")
                        KeyValueRow("Fat", snap.fatG?.let { "%.1f g".format(it) } ?: "—")
                        KeyValueRow("Carbs", snap.carbsG?.let { "%.1f g".format(it) } ?: "—")
                        KeyValueRow("Fiber", snap.fiberG?.let { "%.1f g".format(it) } ?: "—")
                        KeyValueRow("Sugar", snap.sugarG?.let { "%.1f g".format(it) } ?: "—")
                        KeyValueRow("Sodium", snap.sodiumMg?.let { "%.0f mg".format(it) } ?: "—")
                    }
                }
            }
        }
    }
}
