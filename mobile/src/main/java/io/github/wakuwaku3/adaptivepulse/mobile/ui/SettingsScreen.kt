package io.github.wakuwaku3.adaptivepulse.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.wakuwaku3.adaptivepulse.core.SessionConfig
import io.github.wakuwaku3.adaptivepulse.core.settings.SettingItem

/**
 * phone 側の設定編集。SessionConfig 系の項目は watch とサーバーへ LWW で伝播する
 * (PhoneSync.updateSettingsEverywhere)。
 * Health Connect 連携トグルは恒久設定ではなく「権限を取りに行く」ボタン的に振る舞う。
 */
@Composable
fun SettingsScreen(
    config: SessionConfig,
    onChange: (SettingItem, Int) -> Unit,
    onHeightChange: (Int?) -> Unit,
    healthConnectConnected: Boolean,
    healthConnectAvailable: Boolean,
    onHealthConnectToggle: (Boolean) -> Unit,
    onHealthConnectResync: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
    ) {
        items(SettingItem.entries) { item ->
            val progression = item.progression(config)
            val value = item.read(config)
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(item.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            item.format(value),
                            color = MobileColors.Recover,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = { onChange(item, (value - progression.step).coerceAtLeast(progression.first)) },
                            enabled = value > progression.first,
                        ) { Text("−", style = MaterialTheme.typography.titleLarge) }
                        TextButton(
                            onClick = { onChange(item, (value + progression.step).coerceAtMost(progression.last)) },
                            enabled = value < progression.last,
                        ) { Text("+", style = MaterialTheme.typography.titleLarge) }
                    }
                }
            }
        }
        item {
            HeightCard(
                heightCm = config.heightCm,
                onChange = onHeightChange,
            )
        }
        item {
            HealthConnectCard(
                available = healthConnectAvailable,
                connected = healthConnectConnected,
                onToggle = onHealthConnectToggle,
                onResync = onHealthConnectResync,
            )
        }
    }
}

/**
 * 身長入力。HC `HeightRecord` が無い環境用の fallback で、空欄 = 未設定 (=> BMI は "—")。
 * 個人値なのでデフォルト埋めはせず、ユーザが直接 cm で入力する。
 */
@Composable
private fun HeightCard(heightCm: Int?, onChange: (Int?) -> Unit) {
    var text by remember(heightCm) { mutableStateOf(heightCm?.toString().orEmpty()) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.padding(end = 12.dp)) {
                Text("HEIGHT", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "Used for BMI when Health Connect has no height",
                    color = MobileColors.TextDim,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OutlinedTextField(
                value = text,
                onValueChange = { raw ->
                    val digits = raw.filter { it.isDigit() }.take(3)
                    text = digits
                    val parsed = digits.toIntOrNull()
                    if (parsed == null) {
                        onChange(null)
                    } else if (parsed in 100..230) {
                        onChange(parsed)
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                suffix = { Text("cm") },
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun HealthConnectCard(
    available: Boolean,
    connected: Boolean,
    onToggle: (Boolean) -> Unit,
    onResync: () -> Unit,
) {
    var confirmResync by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.padding(end = 12.dp)) {
                Text("Health Connect", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = when {
                        !available -> "Not installed on this device"
                        connected -> "Connected · daily metrics readable"
                        else -> "Tap to grant read access"
                    },
                    color = if (connected) MobileColors.Recover else MobileColors.TextDim,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 5 年 backfill を手動再実行する用。Room destructive migration 後など
                // 自動再 backfill が失敗していたケースのエスケープハッチ
                TextButton(
                    onClick = { confirmResync = true },
                    enabled = connected,
                ) { Text("Resync") }
                Switch(
                    checked = connected,
                    enabled = available,
                    onCheckedChange = onToggle,
                )
            }
        }
    }
    if (confirmResync) {
        AlertDialog(
            onDismissRequest = { confirmResync = false },
            title = { Text("Resync 5-year history?") },
            text = {
                Text(
                    "Re-reads the last 5 years of daily metrics from Health Connect " +
                        "into the local store. Runs in the background; takes a few minutes.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmResync = false
                    onResync()
                }) { Text("Resync") }
            },
            dismissButton = {
                TextButton(onClick = { confirmResync = false }) { Text("Cancel") }
            },
        )
    }
}
