package io.github.wakuwaku3.adaptivepulse.session

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import io.github.wakuwaku3.adaptivepulse.core.menu.DurationEstimate
import io.github.wakuwaku3.adaptivepulse.core.menu.LibraryDocument
import io.github.wakuwaku3.adaptivepulse.core.menu.LibrarySelection
import io.github.wakuwaku3.adaptivepulse.core.menu.Menu
import io.github.wakuwaku3.adaptivepulse.core.menu.MenuKind
import io.github.wakuwaku3.adaptivepulse.core.menu.Program
import io.github.wakuwaku3.adaptivepulse.core.menu.SelectionKind
import io.github.wakuwaku3.adaptivepulse.core.menu.SessionPlanner
import io.github.wakuwaku3.adaptivepulse.ui.IconActionButton
import io.github.wakuwaku3.adaptivepulse.ui.theme.APColors

/**
 * 開始画面から開く「何をやるか」の選択リスト。メニューとプログラムを 1 つのリストに出し、
 * 選んだものが次回の初期表示になる (デフォルト = 最後に使ったもの)。
 * 作成・編集は phone 専用なので、ここは選ぶだけ。
 */
@Composable
fun StartPickerScreen(
    library: LibraryDocument,
    presetMenus: List<Menu>,
    presetPrograms: List<Program>,
    onSelect: (LibrarySelection) -> Unit,
    onBack: () -> Unit,
) {
    val menus = library.menus + presetMenus
    val programs = library.programs + presetPrograms
    val selected = library.selection

    ScalingLazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Text(
                text = "MENUS",
                color = APColors.TextDim,
                style = MaterialTheme.typography.title3,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        items(menus) { menu ->
            PickerChip(
                name = menu.name,
                summary = menu.kind.summary(),
                selected = selected.kind == SelectionKind.MENU && selected.id == menu.id,
                onClick = { onSelect(LibrarySelection(SelectionKind.MENU, menu.id)) },
            )
        }
        item {
            Text(
                text = "PROGRAMS",
                color = APColors.TextDim,
                style = MaterialTheme.typography.title3,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        items(programs) { program ->
            PickerChip(
                name = program.name,
                summary = program.summary(library, presetMenus),
                selected = selected.kind == SelectionKind.PROGRAM && selected.id == program.id,
                onClick = { onSelect(LibrarySelection(SelectionKind.PROGRAM, program.id)) },
            )
        }
        item {
            IconActionButton(
                glyph = "←",
                tint = APColors.TextDim,
                background = APColors.StopChip,
                onClick = onBack,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun PickerChip(name: String, summary: String, selected: Boolean, onClick: () -> Unit) {
    Chip(
        onClick = onClick,
        label = {
            Text(
                text = if (selected) "● $name" else name,
                style = MaterialTheme.typography.button,
                color = if (selected) APColors.High else APColors.Text,
            )
        },
        secondaryLabel = {
            Text(
                text = summary,
                color = APColors.TextDim,
                style = MaterialTheme.typography.caption1,
            )
        },
        colors = ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun MenuKind.summary(): String = when (this) {
    is MenuKind.Interval -> "▲$upperBpm ▼$lowerBpm × $cycles"
    is MenuKind.Timed ->
        listOfNotNull("▲$upperBpm", lowerBpm?.let { "▼$it" }).joinToString(" ") + " · $minutes MIN"
}

private fun Program.summary(library: LibraryDocument, presetMenus: List<Menu>): String {
    val plan = SessionPlanner.resolve(
        LibrarySelection(SelectionKind.PROGRAM, id),
        library,
        presetMenus,
    ) ?: return "${entries.size} MENUS"
    val minutes = DurationEstimate.ofPlan(plan).inWholeMinutes
    return "${entries.size} MENUS · ~$minutes MIN"
}
