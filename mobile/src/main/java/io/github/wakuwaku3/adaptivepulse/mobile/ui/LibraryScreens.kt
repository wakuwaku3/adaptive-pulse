package io.github.wakuwaku3.adaptivepulse.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.wakuwaku3.adaptivepulse.core.menu.DurationEstimate
import io.github.wakuwaku3.adaptivepulse.core.menu.LibraryDocument
import io.github.wakuwaku3.adaptivepulse.core.menu.Menu
import io.github.wakuwaku3.adaptivepulse.core.menu.MenuKind
import io.github.wakuwaku3.adaptivepulse.core.menu.Program
import io.github.wakuwaku3.adaptivepulse.core.menu.ProgramEntry
import io.github.wakuwaku3.adaptivepulse.core.menu.defaultAmount
import kotlin.time.Duration

/**
 * メニュー/プログラムの一覧・作成・編集 (phone 専用。要件: watch は選ぶだけ)。
 * プリセットは編集不可で「複製してカスタム化」だけできる。
 */
@Composable
fun LibraryScreen(
    library: LibraryDocument,
    presetMenus: List<Menu>,
    presetPrograms: List<Program>,
    onEditMenu: (Menu) -> Unit,
    onDuplicateMenu: (Menu) -> Unit,
    onDeleteMenu: (Menu) -> Unit,
    onCreateMenu: () -> Unit,
    onEditProgram: (Program) -> Unit,
    onDeleteProgram: (Program) -> Unit,
    onCreateProgram: () -> Unit,
) {
    // プリセットプログラムも hiit を参照するため、参照中メニューの削除は構造的に禁止する
    val referencedMenuIds = (library.programs + presetPrograms)
        .flatMap { it.entries }.map { it.menuId }.toSet()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { SectionHeader("MENUS", onAdd = onCreateMenu) }
        items(library.menus, key = { "m-${it.id}" }) { menu ->
            LibraryRow(
                title = menu.name,
                summary = menu.kind.summary(),
                onClick = { onEditMenu(menu) },
                actions = {
                    RowGlyph("⧉") { onDuplicateMenu(menu) }
                    if (menu.id !in referencedMenuIds) {
                        RowGlyph("✕") { onDeleteMenu(menu) }
                    }
                },
            )
        }
        items(presetMenus, key = { "pm-${it.id}" }) { menu ->
            LibraryRow(
                title = "${menu.name} (preset)",
                summary = menu.kind.summary(),
                onClick = null,
                actions = { RowGlyph("⧉") { onDuplicateMenu(menu) } },
            )
        }

        item { SectionHeader("PROGRAMS", onAdd = onCreateProgram) }
        items(library.programs, key = { "p-${it.id}" }) { program ->
            LibraryRow(
                title = program.name,
                summary = program.summary(library, presetMenus),
                onClick = { onEditProgram(program) },
                actions = { RowGlyph("✕") { onDeleteProgram(program) } },
            )
        }
        items(presetPrograms, key = { "pp-${it.id}" }) { program ->
            LibraryRow(
                title = "${program.name} (preset)",
                summary = program.summary(library, presetMenus),
                onClick = null,
                actions = {},
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String, onAdd: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MobileColors.TextDim,
            modifier = Modifier.weight(1f),
        )
        RowGlyph("+", onClick = onAdd)
    }
}

@Composable
private fun LibraryRow(
    title: String,
    summary: String,
    onClick: (() -> Unit)?,
    actions: @Composable () -> Unit,
) {
    Card(onClick = onClick ?: {}, enabled = onClick != null) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = MobileColors.Text)
                Text(summary, style = MaterialTheme.typography.bodySmall, color = MobileColors.TextDim)
            }
            actions()
        }
    }
}

@Composable
private fun RowGlyph(glyph: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(glyph, style = MaterialTheme.typography.titleLarge, color = MobileColors.TextDim)
    }
}

/**
 * メニュー編集。型 (心拍トリガー型 / 時間制) は新規作成時のみ選べる
 * (既存の型変更は別メニューを作る操作として扱う方が事故が少ない)。
 */
@Composable
fun MenuEditScreen(
    initial: Menu?,
    newId: String,
    onSave: (Menu) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var isTimed by remember { mutableStateOf(initial?.kind is MenuKind.Timed) }

    val initialInterval = initial?.kind as? MenuKind.Interval
    val initialTimed = initial?.kind as? MenuKind.Timed
    var upper by remember { mutableStateOf(initialInterval?.upperBpm ?: initialTimed?.upperBpm ?: 150) }
    var lower by remember { mutableStateOf(initialInterval?.lowerBpm ?: initialTimed?.lowerBpm ?: 120) }
    var hasFloor by remember { mutableStateOf(initialTimed?.lowerBpm != null || initial?.kind !is MenuKind.Timed) }
    var cycles by remember { mutableStateOf(initialInterval?.cycles ?: 5) }
    var minutes by remember { mutableStateOf(initialTimed?.minutes ?: 20) }
    var cadenceHigh by remember { mutableStateOf(initialInterval?.targetCadenceHigh ?: 130) }
    var cadenceRecovery by remember { mutableStateOf(initialInterval?.targetCadenceRecovery ?: 90) }
    var cadence by remember { mutableStateOf(initialTimed?.targetCadence ?: 100) }

    fun buildMenu(): Menu? = runCatching {
        Menu(
            id = initial?.id ?: newId,
            name = name.trim(),
            kind = if (isTimed) {
                MenuKind.Timed(
                    upperBpm = upper,
                    lowerBpm = if (hasFloor) lower else null,
                    minutes = minutes,
                    targetCadence = cadence,
                )
            } else {
                MenuKind.Interval(
                    upperBpm = upper,
                    lowerBpm = lower,
                    cycles = cycles,
                    targetCadenceHigh = cadenceHigh,
                    targetCadenceRecovery = cadenceRecovery,
                )
            },
        )
    }.getOrNull()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
        }
        if (initial == null) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TypeChip("INTERVAL", selected = !isTimed) { isTimed = false }
                    TypeChip("TIMED", selected = isTimed) { isTimed = true }
                }
            }
        }
        item {
            StepperRow("UPPER LIMIT", "$upper bpm", onDec = { upper = (upper - 1).coerceAtLeast(lower + 1) }, onInc = { upper = (upper + 1).coerceAtMost(200) })
        }
        if (isTimed) {
            item {
                StepperRow(
                    label = "LOWER LIMIT",
                    value = if (hasFloor) "$lower bpm" else "none",
                    onDec = { if (hasFloor) lower = (lower - 1).coerceAtLeast(60) else hasFloor = true },
                    onInc = { if (hasFloor) lower = (lower + 1).coerceAtMost(upper - 1) else hasFloor = true },
                    trailing = { RowGlyph(if (hasFloor) "✕" else "+") { hasFloor = !hasFloor } },
                )
            }
            item { StepperRow("MINUTES", "$minutes min", onDec = { minutes = (minutes - 5).coerceAtLeast(5) }, onInc = { minutes = (minutes + 5).coerceAtMost(120) }) }
            item { StepperRow("TARGET SPM", "$cadence spm", onDec = { cadence = (cadence - 5).coerceAtLeast(30) }, onInc = { cadence = (cadence + 5).coerceAtMost(220) }) }
        } else {
            item { StepperRow("LOWER LIMIT", "$lower bpm", onDec = { lower = (lower - 1).coerceAtLeast(60) }, onInc = { lower = (lower + 1).coerceAtMost(upper - 1) }) }
            item { StepperRow("CYCLES", "$cycles", onDec = { cycles = (cycles - 1).coerceAtLeast(1) }, onInc = { cycles = (cycles + 1).coerceAtMost(12) }) }
            item { StepperRow("TARGET SPM (HIGH)", "$cadenceHigh spm", onDec = { cadenceHigh = (cadenceHigh - 5).coerceAtLeast(cadenceRecovery + 5) }, onInc = { cadenceHigh = (cadenceHigh + 5).coerceAtMost(220) }) }
            item { StepperRow("TARGET SPM (RECOVERY)", "$cadenceRecovery spm", onDec = { cadenceRecovery = (cadenceRecovery - 5).coerceAtLeast(30) }, onInc = { cadenceRecovery = (cadenceRecovery + 5).coerceAtMost(cadenceHigh - 5) }) }
        }
        item {
            val menu = buildMenu()
            val estimate = menu?.let { DurationEstimate.ofSegment(it, it.kind.defaultAmount) }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = estimate?.let { "Estimated ~${it.formatMin()}" } ?: "",
                    color = MobileColors.TextDim,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    enabled = name.isNotBlank() && menu != null,
                    onClick = { buildMenu()?.let(onSave) },
                ) { Text("Save") }
            }
        }
    }
}

/** プログラム編集: メニューを並べ、配置ごとに量 (本数 or 分数) を上書きする */
@Composable
fun ProgramEditScreen(
    initial: Program?,
    newId: String,
    library: LibraryDocument,
    presetMenus: List<Menu>,
    onSave: (Program) -> Unit,
) {
    val allMenus = library.menus + presetMenus
    fun findMenu(id: String): Menu? = allMenus.firstOrNull { it.id == id }

    var name by remember { mutableStateOf(initial?.name ?: "") }
    var entries by remember { mutableStateOf(initial?.entries ?: emptyList()) }
    var addOpen by remember { mutableStateOf(false) }

    val totalEstimate = entries.mapNotNull { entry ->
        findMenu(entry.menuId)?.let { menu ->
            DurationEstimate.ofSegment(menu, entry.amountOverride ?: menu.kind.defaultAmount)
        }
    }.fold(Duration.ZERO) { acc, d -> acc + d }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
        }
        // 「朝、何分で終わるか」を組みながら見せる (要件)
        item {
            Text(
                text = "Total ~${totalEstimate.formatMin()} · ${entries.size} menus",
                color = MobileColors.TextDim,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        items(entries.indices.toList(), key = { i -> "$i-${entries[i].menuId}" }) { index ->
            val entry = entries[index]
            val menu = findMenu(entry.menuId)
            val amount = entry.amountOverride ?: menu?.kind?.defaultAmount ?: 1
            val unit = if (menu?.kind is MenuKind.Timed) "min" else "cycles"
            Card {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(menu?.name ?: entry.menuId, color = MobileColors.Text)
                        Text("$amount $unit", color = MobileColors.TextDim, style = MaterialTheme.typography.bodySmall)
                    }
                    RowGlyph("−") {
                        entries = entries.replaceAt(index, entry.copy(amountOverride = (amount - 1).coerceAtLeast(1)))
                    }
                    RowGlyph("+") {
                        entries = entries.replaceAt(index, entry.copy(amountOverride = amount + 1))
                    }
                    RowGlyph("▲") { if (index > 0) entries = entries.swap(index, index - 1) }
                    RowGlyph("▼") { if (index < entries.lastIndex) entries = entries.swap(index, index + 1) }
                    RowGlyph("✕") { entries = entries.filterIndexed { i, _ -> i != index } }
                }
            }
        }
        item {
            Box {
                TextButton(onClick = { addOpen = true }) { Text("+ Add menu") }
                DropdownMenu(expanded = addOpen, onDismissRequest = { addOpen = false }) {
                    allMenus.forEach { menu ->
                        DropdownMenuItem(
                            text = { Text("${menu.name} · ${menu.kind.summary()}") },
                            onClick = {
                                addOpen = false
                                entries = entries + ProgramEntry(menu.id)
                            },
                        )
                    }
                }
            }
        }
        item {
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Box(modifier = Modifier.weight(1f)) {}
                TextButton(
                    enabled = name.isNotBlank() && entries.isNotEmpty(),
                    onClick = {
                        onSave(Program(id = initial?.id ?: newId, name = name.trim(), entries = entries))
                    },
                ) { Text("Save") }
            }
        }
    }
}

@Composable
private fun TypeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(
            text = if (selected) "● $label" else label,
            color = if (selected) MobileColors.Recover else MobileColors.TextDim,
        )
    }
}

@Composable
private fun StepperRow(
    label: String,
    value: String,
    onDec: () -> Unit,
    onInc: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Card {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyMedium, color = MobileColors.Text)
                Text(value, style = MaterialTheme.typography.bodySmall, color = MobileColors.Recover)
            }
            RowGlyph("−", onClick = onDec)
            RowGlyph("+", onClick = onInc)
            trailing?.invoke()
        }
    }
}

private fun MenuKind.summary(): String = when (this) {
    is MenuKind.Interval -> "▲$upperBpm ▼$lowerBpm × $cycles"
    is MenuKind.Timed ->
        listOfNotNull("▲$upperBpm", lowerBpm?.let { "▼$it" }).joinToString(" ") + " · $minutes min"
}

private fun Program.summary(library: LibraryDocument, presetMenus: List<Menu>): String {
    val allMenus = library.menus + presetMenus
    val estimate = entries.mapNotNull { entry ->
        allMenus.firstOrNull { it.id == entry.menuId }?.let { menu ->
            DurationEstimate.ofSegment(menu, entry.amountOverride ?: menu.kind.defaultAmount)
        }
    }.fold(Duration.ZERO) { acc, d -> acc + d }
    val names = entries.map { entry -> allMenus.firstOrNull { it.id == entry.menuId }?.name ?: "?" }
    return "${names.joinToString(" → ")} · ~${estimate.formatMin()}"
}

private fun Duration.formatMin(): String = "${inWholeMinutes} min"

private fun List<ProgramEntry>.replaceAt(index: Int, entry: ProgramEntry): List<ProgramEntry> =
    mapIndexed { i, e -> if (i == index) entry else e }

private fun List<ProgramEntry>.swap(a: Int, b: Int): List<ProgramEntry> =
    toMutableList().also { val t = it[a]; it[a] = it[b]; it[b] = t }
