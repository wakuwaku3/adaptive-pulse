package io.github.wakuwaku3.adaptivepulse.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.github.wakuwaku3.adaptivepulse.core.strength.Gym
import io.github.wakuwaku3.adaptivepulse.core.strength.Prefill
import io.github.wakuwaku3.adaptivepulse.core.strength.Training
import io.github.wakuwaku3.adaptivepulse.core.strength.TrainingSet
import io.github.wakuwaku3.adaptivepulse.core.strength.WorkoutEntry
import io.github.wakuwaku3.adaptivepulse.core.strength.WorkoutRecord
import io.github.wakuwaku3.adaptivepulse.core.strength.pendingTrainingCount
import io.github.wakuwaku3.adaptivepulse.core.strength.prefillFor
import io.github.wakuwaku3.adaptivepulse.mobile.strength.WorkoutActions
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

/**
 * 筋トレ記録 (Workout) 画面。ジムの順路どおりに登録したトレーニングを上から
 * 埋めていく使い方を想定し、1 画面 + ダイアログで完結させる。
 */
@Composable
fun WorkoutScreen(actions: WorkoutActions) {
    val scope = rememberCoroutineScope()
    val catalog by actions.catalog.collectAsState(initial = null)
    val workout by actions.activeWorkout.collectAsState()

    LaunchedEffect(Unit) { actions.openScreen() }

    val gyms = catalog?.gyms.orEmpty()
    val gym = gyms.firstOrNull { it.id == catalog?.lastGymId } ?: gyms.firstOrNull()

    if (gym == null) {
        FirstGymForm(onCreate = { name, onDuplicate ->
            scope.launch { if (!actions.addGym(name)) onDuplicate() }
        })
        return
    }

    var addSetFor by remember { mutableStateOf<Training?>(null) }
    var editSetTarget by remember { mutableStateOf<Pair<Training, Int>?>(null) }
    var addTrainingOpen by remember { mutableStateOf(false) }
    var renameTrainingFor by remember { mutableStateOf<Training?>(null) }
    var newGymOpen by remember { mutableStateOf(false) }
    var renameGymOpen by remember { mutableStateOf(false) }
    var hiddenExpanded by remember { mutableStateOf(false) }

    val visibleTrainings = gym.trainings.filterNot { it.hidden }
    val hiddenTrainings = gym.trainings.filter { it.hidden }
    val pending = pendingTrainingCount(workout, visibleTrainings)

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            GymHeader(
                gym = gym,
                gyms = gyms,
                onSelect = { scope.launch { actions.selectGym(it.id) } },
                onNewGym = { newGymOpen = true },
                onRenameGym = { renameGymOpen = true },
            )
        }
        item { WorkoutStatusLine(workout) }
        items(visibleTrainings, key = { it.id }) { training ->
            TrainingRow(
                training = training,
                entry = workout?.entries?.firstOrNull { it.trainingId == training.id },
                onAddSet = { addSetFor = training },
                onEditSet = { index -> editSetTarget = training to index },
                onSkip = { skipped -> scope.launch { actions.setSkipped(gym, training, skipped) } },
                onRename = { renameTrainingFor = training },
                onHide = { scope.launch { actions.setTrainingHidden(gym.id, training.id, true) } },
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f))
                TextButton(onClick = { addTrainingOpen = true }) { Text("+ Add training") }
            }
        }
        if (hiddenTrainings.isNotEmpty()) {
            item {
                TextButton(onClick = { hiddenExpanded = !hiddenExpanded }) {
                    Text(
                        text = "${if (hiddenExpanded) "▾" else "▸"} HIDDEN (${hiddenTrainings.size})",
                        color = MobileColors.TextDim,
                    )
                }
            }
            if (hiddenExpanded) {
                items(hiddenTrainings, key = { "hidden-${it.id}" }) { training ->
                    Card {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                training.name,
                                color = MobileColors.TextDim,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = {
                                scope.launch { actions.setTrainingHidden(gym.id, training.id, false) }
                            }) { Text("Show") }
                        }
                    }
                }
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 入力漏れの気づきを与えるだけで Finish は止めない (追加合意 3)
                Text(
                    text = if (workout != null && pending > 0) "$pending not logged" else "",
                    color = MobileColors.TextDim,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    enabled = workout != null,
                    onClick = { scope.launch { actions.finish() } },
                ) { Text("Finish") }
            }
        }
    }

    addSetFor?.let { training ->
        SetDialog(
            title = training.name,
            prefill = prefillFor(workout, training.id, training),
            onDismiss = { addSetFor = null },
            onSave = { weightKg, reps ->
                addSetFor = null
                scope.launch { actions.addSet(gym, training, weightKg, reps) }
            },
        )
    }

    editSetTarget?.let { (training, index) ->
        // 対象セットが消えていたら (削除直後の stale index) 何も出さない。
        // コンポジション中の state 書き戻しを避けるため null 化はここでしない
        val set = workout?.entries?.firstOrNull { it.trainingId == training.id }?.sets?.getOrNull(index)
        if (set != null) {
            SetDialog(
                title = "${training.name} · set ${index + 1}",
                prefill = Prefill(weightKg = set.weightKg, reps = set.reps),
                onDismiss = { editSetTarget = null },
                onSave = { weightKg, reps ->
                    editSetTarget = null
                    scope.launch { actions.updateSet(training.id, index, weightKg, reps) }
                },
                onDelete = {
                    editSetTarget = null
                    scope.launch { actions.removeSet(training.id, index) }
                },
            )
        }
    }

    if (addTrainingOpen) {
        NameDialog(
            title = "New training",
            initial = "",
            onDismiss = { addTrainingOpen = false },
            onSave = { name, onDuplicate ->
                scope.launch {
                    if (actions.addTraining(gym.id, name)) addTrainingOpen = false else onDuplicate()
                }
            },
        )
    }

    renameTrainingFor?.let { training ->
        NameDialog(
            title = "Rename training",
            initial = training.name,
            onDismiss = { renameTrainingFor = null },
            onSave = { name, onDuplicate ->
                scope.launch {
                    if (actions.renameTraining(gym.id, training.id, name)) renameTrainingFor = null else onDuplicate()
                }
            },
        )
    }

    if (newGymOpen) {
        NameDialog(
            title = "New gym",
            initial = "",
            onDismiss = { newGymOpen = false },
            onSave = { name, onDuplicate ->
                scope.launch { if (actions.addGym(name)) newGymOpen = false else onDuplicate() }
            },
        )
    }

    if (renameGymOpen) {
        NameDialog(
            title = "Rename gym",
            initial = gym.name,
            onDismiss = { renameGymOpen = false },
            onSave = { name, onDuplicate ->
                scope.launch { if (actions.renameGym(gym.id, name)) renameGymOpen = false else onDuplicate() }
            },
        )
    }
}

/** 初回登録。ジムが 1 つもない状態は名称入力だけの画面にする (要件 2) */
@Composable
private fun FirstGymForm(onCreate: (String, onDuplicate: () -> Unit) -> Unit) {
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Register your gym to start logging.", color = MobileColors.TextDim)
        OutlinedTextField(
            value = name,
            onValueChange = { name = it; error = null },
            label = { Text("Gym name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let { Text(it, color = MobileColors.High) }
        Button(
            enabled = name.isNotBlank(),
            onClick = { onCreate(name) { error = "Invalid or duplicate name" } },
        ) { Text("Create gym") }
    }
}

@Composable
private fun GymHeader(
    gym: Gym,
    gyms: List<Gym>,
    onSelect: (Gym) -> Unit,
    onNewGym: () -> Unit,
    onRenameGym: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = gym.name,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        Box {
            TextButton(onClick = { open = true }) {
                Text("⌄", style = MaterialTheme.typography.titleLarge, color = MobileColors.TextDim)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                gyms.filterNot { it.id == gym.id }.forEach { other ->
                    DropdownMenuItem(
                        text = { Text(other.name) },
                        onClick = { open = false; onSelect(other) },
                    )
                }
                DropdownMenuItem(text = { Text("+ New gym") }, onClick = { open = false; onNewGym() })
                DropdownMenuItem(text = { Text("Rename gym") }, onClick = { open = false; onRenameGym() })
            }
        }
    }
}

/** 常設スロット (ui.md): workout の有無で下のリストが動かないよう高さを固定する */
@Composable
private fun WorkoutStatusLine(workout: WorkoutRecord?) {
    val text = workout?.let {
        val started = Instant.ofEpochMilli(it.startedAtMs).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("HH:mm"))
        val sets = it.entries.sumOf { e -> e.sets.size }
        "In progress · started $started · $sets sets"
    } ?: "No workout yet — add a set to start"
    Box(modifier = Modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.CenterStart) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (workout != null) MobileColors.Recover else MobileColors.TextDim,
        )
    }
}

@Composable
private fun TrainingRow(
    training: Training,
    entry: WorkoutEntry?,
    onAddSet: () -> Unit,
    onEditSet: (Int) -> Unit,
    onSkip: (Boolean) -> Unit,
    onRename: () -> Unit,
    onHide: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val skipped = entry?.skipped == true
    val sets = entry?.sets.orEmpty()
    val titleColor = when {
        skipped -> MobileColors.TextDim
        sets.isNotEmpty() -> MobileColors.Recover
        else -> MobileColors.Text
    }
    Card {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = training.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = titleColor,
                    textDecoration = if (skipped) TextDecoration.LineThrough else null,
                )
                TrainingSummary(training, sets, skipped, onEditSet)
            }
            TextButton(onClick = onAddSet) {
                Text("+", style = MaterialTheme.typography.titleLarge, color = MobileColors.TextDim)
            }
            Box {
                TextButton(onClick = { menuOpen = true }) {
                    Text("⋮", style = MaterialTheme.typography.titleLarge, color = MobileColors.TextDim)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    // ドメイン語彙が要る操作なのでグリフでなくテキスト (ui.md 例外規定)
                    if (skipped) {
                        DropdownMenuItem(
                            text = { Text("Undo not done") },
                            onClick = { menuOpen = false; onSkip(false) },
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text("Mark not done") },
                            onClick = { menuOpen = false; onSkip(true) },
                        )
                    }
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menuOpen = false; onRename() })
                    DropdownMenuItem(text = { Text("Hide") }, onClick = { menuOpen = false; onHide() })
                }
            }
        }
    }
}

@Composable
private fun TrainingSummary(
    training: Training,
    sets: List<TrainingSet>,
    skipped: Boolean,
    onEditSet: (Int) -> Unit,
) {
    if (sets.isNotEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            sets.forEachIndexed { index, set ->
                TextButton(
                    onClick = { onEditSet(index) },
                    contentPadding = PaddingValues(horizontal = 4.dp),
                ) {
                    Text(
                        text = set.label(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MobileColors.Recover,
                    )
                }
            }
        }
        return
    }
    val text = when {
        skipped -> "not done today"
        training.lastReps != null -> "last: ${lastLabel(training)}"
        else -> "no records yet"
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = MobileColors.TextDim)
}

private fun TrainingSet.label(): String =
    weightKg?.let { "${it.formatKg()}×$reps" } ?: "—×$reps"

private fun lastLabel(training: Training): String =
    training.lastWeightKg?.let { "${it.formatKg()}kg × ${training.lastReps}" }
        ?: "${training.lastReps} reps"

/** 60.0 → "60"、42.5 → "42.5" (ジムのプレート表記に合わせて無駄な小数を出さない) */
private fun Double.formatKg(): String =
    if (this == toLong().toDouble()) toLong().toString() else toString()

/** セットの追加/編集。weight 空欄 = 自重・ストレッチ等の負荷なし */
@Composable
private fun SetDialog(
    title: String,
    prefill: Prefill?,
    onDismiss: () -> Unit,
    onSave: (weightKg: Double?, reps: Int) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var weight by remember { mutableStateOf(prefill?.weightKg?.formatKg() ?: "") }
    var reps by remember { mutableStateOf(prefill?.reps?.toString() ?: "") }
    val parsedWeight = weight.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
    val weightValid = weight.trim().isEmpty() || (parsedWeight != null && parsedWeight > 0)
    val parsedReps = reps.trim().toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = weight,
                    onValueChange = { weight = it },
                    label = { Text("Weight kg (blank = none)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedTextField(
                    value = reps,
                    onValueChange = { reps = it },
                    label = { Text("Reps") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = weightValid && parsedReps != null && parsedReps > 0,
                onClick = { onSave(parsedWeight, parsedReps ?: return@TextButton) },
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                onDelete?.let { TextButton(onClick = it) { Text("Delete", color = MobileColors.High) } }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** ジム/トレーニングの名前入力。重複はダイアログ内のインラインエラーで返す */
@Composable
private fun NameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onSave: (name: String, onDuplicate: () -> Unit) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    label = { Text("Name") },
                    singleLine = true,
                )
                error?.let { Text(it, color = MobileColors.High) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onSave(name) { error = "Name already exists" } },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
