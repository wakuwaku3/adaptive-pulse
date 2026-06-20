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

/** 睡眠ステージの推移を 7 日ぶん並べる。Today カードでは合計だけだが、ここでは内訳を見たい */
@Composable
fun SleepDetailScreen(recent: List<DailySnapshotEntity>) {
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
                    if (snap.sleepDurationMin == null) {
                        Text("No sleep recorded", color = MobileColors.TextDim)
                    } else {
                        KeyValueRow("Total", "%.1f h".format(snap.sleepDurationMin / 60.0))
                        KeyValueRow("Deep", snap.sleepDeepMin?.let { "${it} min" } ?: "—")
                        KeyValueRow("REM", snap.sleepRemMin?.let { "${it} min" } ?: "—")
                        KeyValueRow("Light", snap.sleepLightMin?.let { "${it} min" } ?: "—")
                        KeyValueRow("Awake", snap.sleepAwakeMin?.let { "${it} min" } ?: "—")
                    }
                }
            }
        }
    }
}
