package io.github.wakuwaku3.adaptivepulse.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Stepper
import androidx.wear.compose.material.Text
import io.github.wakuwaku3.adaptivepulse.core.SessionConfig
import io.github.wakuwaku3.adaptivepulse.ui.theme.APColors

@Composable
fun SettingsScreen(config: SessionConfig, onSelect: (SettingItem) -> Unit) {
    ScalingLazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Text(
                text = "SETTINGS",
                color = APColors.TextDim,
                style = MaterialTheme.typography.title3,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        items(SettingItem.entries) { item ->
            Chip(
                onClick = { onSelect(item) },
                label = { Text(item.title, style = MaterialTheme.typography.button) },
                secondaryLabel = {
                    Text(
                        text = item.format(item.read(config)),
                        color = APColors.Recover,
                        style = MaterialTheme.typography.caption1,
                    )
                },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
fun SettingEditorScreen(
    item: SettingItem,
    config: SessionConfig,
    onChange: (SessionConfig) -> Unit,
) {
    val value = item.read(config)
    Stepper(
        value = value,
        onValueChange = { onChange(item.write(config, it)) },
        valueProgression = item.progression(config),
        decreaseIcon = { Text("−", style = MaterialTheme.typography.title1) },
        increaseIcon = { Text("+", style = MaterialTheme.typography.title1) },
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = item.title,
                color = APColors.TextDim,
                style = MaterialTheme.typography.title3,
            )
            Text(
                text = item.format(value),
                color = APColors.Text,
                style = MaterialTheme.typography.title1,
            )
        }
    }
}
