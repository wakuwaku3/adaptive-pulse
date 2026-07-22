package io.github.wakuwaku3.adaptivepulse.mobile.ui

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * TopAppBar の overflow menu (⋮)。どの画面からでも同じ項目に届くよう、
 * 項目は画面種別で出し分けず 1 箇所に固定する。例外は現在画面への
 * 自己リンクのみで、呼び出し側が null を渡して消す (FB 2026-07-23)。
 */
@Composable
fun OverflowMenu(
    exportEnabled: Boolean,
    onOpenSettings: (() -> Unit)?,
    onExport: () -> Unit,
    onReseedDemo: (() -> Unit)?,
    onSignOut: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Text("⋮", style = MaterialTheme.typography.headlineMedium)
    }
    DropdownMenu(
        expanded = open,
        onDismissRequest = { open = false },
    ) {
        onOpenSettings?.let { openSettings ->
            DropdownMenuItem(
                text = { Text("Settings") },
                onClick = { open = false; openSettings() },
            )
        }
        DropdownMenuItem(
            text = { Text("Export 30 days") },
            enabled = exportEnabled,
            onClick = { open = false; onExport() },
        )
        onReseedDemo?.let { reseed ->
            DropdownMenuItem(
                text = { Text("Re-seed demo data") },
                onClick = { open = false; reseed() },
            )
        }
        DropdownMenuItem(
            text = { Text("Sign out") },
            onClick = { open = false; onSignOut() },
        )
    }
}
