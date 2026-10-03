package app.fittrack.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.fittrack.FitApp
import app.fittrack.data.AppData
import app.fittrack.data.Exercise
import app.fittrack.data.LoadType
import app.fittrack.data.Workout
import app.fittrack.data.WorkoutExercise
import app.fittrack.data.WorkoutSet
import app.fittrack.data.newId
import app.fittrack.domain.Calc
import app.fittrack.notify.RestTimer
import kotlinx.coroutines.delay
import java.time.LocalDateTime

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorkoutScreen(nav: NavHostController, id: String) {
    val data by FitApp.repo.data.collectAsStateWithLifecycle()
    val w = data.workouts.find { it.id == id }
    if (w == null) {
        LaunchedEffect(Unit) { nav.popBackStack() }
        return
    }
    val ctx = LocalContext.current
    val rest by RestTimer.state.collectAsStateWithLifecycle()
    var showPicker by remember { mutableStateOf(false) }
    var finishDialog by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    fun upd(f: (Workout) -> Workout) = FitApp.repo.updateWorkout(id, f)
    fun updEntry(entryId: String, f: (WorkoutExercise) -> WorkoutExercise) =
        upd { it.copy(exercises = it.exercises.map { e -> if (e.id == entryId) f(e) else e }) }

    fun finish(markAll: Boolean?) {
        upd { wk ->
            val ex = when (markAll) {
                true -> wk.exercises.map { e -> e.copy(sets = e.sets.map { s -> if (s.reps > 0) s.copy(done = true) else s }) }
                false -> wk.exercises.map { e -> e.copy(sets = e.sets.filter { it.done }) }
                null -> wk.exercises
            }
            val end = if (wk.date == Calc.today()) Calc.nowTime() else (wk.endTime ?: Calc.addSeconds(wk.startTime, 3600))
            wk.copy(exercises = ex, endTime = end)
        }
        RestTimer.skip(ctx)
    }

    fun addExercise(ex: Exercise) {
        val prev = Calc.previous(FitApp.repo.current, w, ex.id)
        val prevSets = prev?.entry?.sets?.filter { it.done }.orEmpty()
        val sets = if (prevSets.isNotEmpty()) prevSets.map { it.copy(id = newId(), done = false) }
        else List(ex.defaultSets.coerceAtLeast(1)) { WorkoutSet(newId(), ex.defaultReps, ex.defaultWeightKg) }
        upd { it.copy(exercises = it.exercises + WorkoutExercise(newId(), ex.id, ex.defaultRestSec, "", sets)) }
    }

    ScreenScaffold(
        title = w.title.ifBlank { "Training" },
        onBack = { nav.popBackStack() },
        actions = {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Menü") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Als neues Training wiederholen") }, onClick = {
                    menu = false
                    val copy = Workout(
                        id = newId(), title = w.title, date = Calc.today(), startTime = Calc.nowTime(),
                        exercises = w.exercises.map { e ->
                            e.copy(id = newId(), notes = "", sets = e.sets.map { s -> s.copy(id = newId(), done = false) })
                        },
                    )
                    FitApp.repo.upsertWorkout(copy)
                    nav.navigate("workout/${copy.id}") { popUpTo("workout/{id}") { inclusive = true } }
                })
                DropdownMenuItem(text = { Text("Training löschen") }, onClick = { menu = false; confirmDelete = true })
            }
        },
        bottomBar = { rest?.let { RestTimerBar(it) } },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding),
            contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 40.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            block("header") {
                SyncedTextField(w.title, { t -> upd { it.copy(title = t) } }, Modifier.fillMaxWidth(), label = "Titel", singleLine = true)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DateChip(w.date, { d -> upd { it.copy(date = d) } })
                    TimeChip("Start", w.startTime, { t -> if (t != null) upd { it.copy(startTime = t) } })
                    TimeChip("Ende", w.endTime, { t -> upd { it.copy(endTime = t) } }, allowClear = true)
                }
                if (w.endTime == null) {
                    if (w.date == Calc.today()) ElapsedText(w.startTime)
                    Button(onClick = {
                        if (w.exercises.any { e -> e.sets.any { !it.done } }) finishDialog = true else finish(null)
                    }, modifier = Modifier.fillMaxWidth()) { Text("Training beenden") }
                } else {
                    Calc.minutesBetween(w.startTime, w.endTime)?.let {
                        Text("Dauer: ${Calc.minutesText(it)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(6.dp))
                SyncedTextField(w.notes, { t -> upd { it.copy(notes = t) } }, Modifier.fillMaxWidth(), label = "Notizen zum Training")
            }
            items(w.exercises, key = { it.id }) { entry ->
                val idx = w.exercises.indexOf(entry)
                ExerciseEntryCard(
                    data = data, workout = w, entry = entry,
                    canMoveUp = idx > 0, canMoveDown = idx < w.exercises.size - 1,
                    onUpdate = { f -> updEntry(entry.id, f) },
                    onMove = { delta ->
                        upd { wk ->
                            val list = wk.exercises.toMutableList()
                            val i = list.indexOfFirst { it.id == entry.id }
                            val j = i + delta
                            if (i >= 0 && j in list.indices) { val t = list[i]; list[i] = list[j]; list[j] = t }
                            wk.copy(exercises = list)
                        }
                    },
                    onRemove = { upd { wk -> wk.copy(exercises = wk.exercises.filterNot { it.id == entry.id }) } },
                    onHistory = { nav.navigate("history/${entry.exerciseId}") },
                    onSetDone = { setIndex -> startRest(ctx, data, entry, setIndex) },
                )
            }
            item {
                FilledTonalButton(onClick = { showPicker = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Add, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Übung hinzufügen")
                }
            }
        }
    }

    if (showPicker) ExercisePickerDialog(data, onPick = { addExercise(it); showPicker = false }, onDismiss = { showPicker = false })
    if (finishDialog) {
        val open = w.exercises.sumOf { e -> e.sets.count { !it.done } }
        AlertDialog(
            onDismissRequest = { finishDialog = false },
            title = { Text("Training beenden") },
            text = { Text("$open Satz/Sätze sind nicht abgehakt. Nur abgehakte Sätze zählen für Verlauf und 1RM.") },
            confirmButton = { TextButton(onClick = { finish(true); finishDialog = false }) { Text("Alle abhaken") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { finish(false); finishDialog = false }) { Text("Offene löschen") }
                    TextButton(onClick = { finishDialog = false }) { Text("Zurück") }
                }
            },
        )
    }
    if (confirmDelete) ConfirmDialog(
        title = "Training löschen?", text = "Das Training vom ${Calc.dateDe(w.date)} wird endgültig gelöscht.",
        onConfirm = { FitApp.repo.deleteWorkout(id); nav.popBackStack() }, onDismiss = { confirmDelete = false },
    )
}

/** Startet den Pausen-Timer nach einem abgehakten Satz und beschreibt den nächsten Satz. */
private fun startRest(ctx: Context, data: AppData, entry: WorkoutExercise, setIndex: Int) {
    val ex = Calc.exercise(data, entry.exerciseId)
    val name = ex?.name ?: "Übung"
    val next = entry.sets.withIndex().firstOrNull { it.index > setIndex && !it.value.done }
    val label = if (next != null) "$name – Satz ${next.index + 1}: ${Calc.setShort(ex?.loadType ?: LoadType.WEIGHTED, next.value)}" else name
    RestTimer.start(ctx, entry.restSec, label)
}

@Composable
private fun ElapsedText(startTime: String) {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) { while (true) { now = LocalDateTime.now(); delay(1000) } }
    val start = Calc.parseTime(startTime) ?: return
    var sec = (now.toLocalTime().toSecondOfDay() - start.toSecondOfDay()).toLong()
    if (sec < 0) sec += 24 * 3600
    Text(
        "Läuft seit ${Calc.duration(sec)}",
        style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 6.dp),
    )
}

@Composable
private fun ExerciseEntryCard(
    data: AppData,
    workout: Workout,
    entry: WorkoutExercise,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onUpdate: ((WorkoutExercise) -> WorkoutExercise) -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
    onHistory: () -> Unit,
    onSetDone: (Int) -> Unit,
) {
    val ex = Calc.exercise(data, entry.exerciseId)
    val type = ex?.loadType ?: LoadType.WEIGHTED
    val prev = remember(data, workout.id, workout.date, workout.startTime, entry.exerciseId) {
        Calc.previous(data, workout, entry.exerciseId)
    }
    val stats = Calc.entryStats(data, workout, entry)
    var menu by remember { mutableStateOf(false) }
    var restDialog by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable(onClick = onHistory)) {
                    Text(
                        ex?.name ?: "(gelöschte Übung)", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        listOf(ex?.muscleGroup.orEmpty(), Calc.loadTypeLabel(type)).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Optionen") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Verlauf anzeigen") }, onClick = { menu = false; onHistory() })
                        if (canMoveUp) DropdownMenuItem(text = { Text("Nach oben") }, onClick = { menu = false; onMove(-1) })
                        if (canMoveDown) DropdownMenuItem(text = { Text("Nach unten") }, onClick = { menu = false; onMove(1) })
                        DropdownMenuItem(text = { Text("Aus Training entfernen") }, onClick = { menu = false; confirmRemove = true })
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistChip(
                    onClick = { restDialog = true },
                    label = { Text("Pause ${Calc.duration(entry.restSec.toLong())}") },
                    leadingIcon = { Icon(Icons.Filled.Timer, null, Modifier.size(18.dp)) },
                )
                Spacer(Modifier.width(8.dp))
                if (prev != null) Text(
                    "Letztes Mal (${Calc.relativeDay(prev.workout.date)}): " +
                        prev.stats.doneSets.joinToString(", ") { Calc.setShort(type, it.set) },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
            if (ex != null && ex.notes.isNotBlank()) Text(
                "📌 ${ex.notes}", style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            SyncedTextField(
                entry.notes, { t -> onUpdate { it.copy(notes = t) } }, Modifier.fillMaxWidth(),
                placeholder = "Notiz zu dieser Übung",
            )
            Spacer(Modifier.height(8.dp))
            // Kopfzeile
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                HeaderCell("Satz", Modifier.width(36.dp))
                HeaderCell("Vorher", Modifier.weight(1f))
                HeaderCell(
                    when (type) { LoadType.WEIGHTED -> "kg"; LoadType.BODYWEIGHT -> ""; LoadType.BODYWEIGHT_PLUS -> "+kg" },
                    Modifier.width(72.dp),
                )
                Spacer(Modifier.width(6.dp))
                HeaderCell("Wdh", Modifier.width(56.dp))
                Spacer(Modifier.width(88.dp))
            }
            entry.sets.forEachIndexed { i, s ->
                val prevSet = prev?.stats?.doneSets?.getOrNull(i)?.set
                SetRow(
                    index = i, set = s, type = type,
                    previous = prevSet?.let { Calc.setShort(type, it) } ?: "–",
                    onChange = { ns -> onUpdate { e -> e.copy(sets = e.sets.map { if (it.id == s.id) ns else it }) } },
                    onToggle = {
                        val nowDone = !s.done
                        onUpdate { e -> e.copy(sets = e.sets.map { if (it.id == s.id) it.copy(done = nowDone) else it }) }
                        if (nowDone) onSetDone(i)
                    },
                    onDelete = { onUpdate { e -> e.copy(sets = e.sets.filterNot { it.id == s.id }) } },
                )
            }
            TextButton(onClick = {
                onUpdate { e ->
                    val last = e.sets.lastOrNull()
                    val ns = last?.copy(id = newId(), done = false)
                        ?: WorkoutSet(newId(), ex?.defaultReps ?: 10, ex?.defaultWeightKg ?: 0.0)
                    e.copy(sets = e.sets + ns)
                }
            }) { Icon(Icons.Filled.Add, null); Spacer(Modifier.width(4.dp)); Text("Satz hinzufügen") }
            HorizontalDivider()
            Spacer(Modifier.height(6.dp))
            val prevBest = prev?.stats?.bestE1rm
            val text = when {
                stats.bestE1rm != null -> {
                    val today = stats.bestE1rm
                    "e1RM heute: ${Calc.num(today)} kg" + (prevBest?.let { p ->
                        val d = today - p
                        val arrow = if (d > 0.05) "▲" else if (d < -0.05) "▼" else "="
                        "  $arrow ${if (d >= 0) "+" else ""}${Calc.num(d)} kg ggü. letztem Mal (${Calc.num(p)} kg)"
                    } ?: "")
                }
                type != LoadType.WEIGHTED && stats.bodyWeightKg == null ->
                    "Trage dein Körpergewicht ein, damit das 1RM berechnet werden kann."
                prevBest != null -> "e1RM letztes Mal: ${Calc.num(prevBest)} kg – hake Sätze ab für den Vergleich."
                else -> "Hake erledigte Sätze ab – daraus wird dein e1RM berechnet."
            }
            Text(text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            if (type != LoadType.WEIGHTED && stats.bodyWeightKg != null) Text(
                "Körpergewicht für die Berechnung: ${Calc.kg(stats.bodyWeightKg)}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (restDialog) RestDialog(
        initial = entry.restSec,
        exerciseDefault = ex?.defaultRestSec,
        onSave = { sec, asDefault ->
            onUpdate { it.copy(restSec = sec) }
            if (asDefault && ex != null) FitApp.repo.upsertExercise(ex.copy(defaultRestSec = sec))
        },
        onDismiss = { restDialog = false },
    )
    if (confirmRemove) ConfirmDialog(
        title = "Übung entfernen?", text = "„${ex?.name ?: "Übung"}“ mit allen Sätzen aus diesem Training entfernen?",
        confirm = "Entfernen", onConfirm = onRemove, onDismiss = { confirmRemove = false },
    )
}

@Composable
private fun HeaderCell(text: String, modifier: Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SetRow(
    index: Int,
    set: WorkoutSet,
    type: LoadType,
    previous: String,
    onChange: (WorkoutSet) -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    val bg = if (set.done) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f) else androidx.compose.ui.graphics.Color.Transparent
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp).background(bg, RoundedCornerShape(8.dp)).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${index + 1}", Modifier.width(36.dp).padding(start = 8.dp), fontWeight = FontWeight.Bold)
        Text(previous, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (type == LoadType.BODYWEIGHT) {
            Text("KG", Modifier.width(72.dp), style = MaterialTheme.typography.bodyMedium)
        } else {
            CompactNumberField(set.weightKg, { onChange(set.copy(weightKg = it)) }, Modifier.width(72.dp), placeholder = if (type == LoadType.WEIGHTED) "kg" else "+0")
        }
        Spacer(Modifier.width(6.dp))
        CompactNumberField(set.reps.toDouble(), { onChange(set.copy(reps = it.toInt())) }, Modifier.width(56.dp), placeholder = "0", decimals = false)
        IconButton(
            onClick = onToggle,
            colors = if (set.done) IconButtonDefaults.filledIconButtonColors() else IconButtonDefaults.iconButtonColors(),
            modifier = Modifier.padding(start = 4.dp).size(44.dp),
        ) { Icon(Icons.Filled.Check, if (set.done) "Erledigt" else "Abhaken") }
        IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Filled.Close, "Satz löschen", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RestDialog(initial: Int, exerciseDefault: Int?, onSave: (Int, Boolean) -> Unit, onDismiss: () -> Unit) {
    var sec by remember { mutableStateOf(initial) }
    var asDefault by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pausen-Timer") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { sec = (sec - 15).coerceAtLeast(0) }) { Text("−15") }
                    Text(Calc.duration(sec.toLong()), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 16.dp))
                    OutlinedButton(onClick = { sec += 15 }) { Text("+15") }
                }
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0, 45, 60, 90, 120, 180, 240).forEach { s ->
                        FilterChip(selected = sec == s, onClick = { sec = s }, label = { Text(if (s == 0) "aus" else Calc.duration(s.toLong())) })
                    }
                }
                if (exerciseDefault != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = asDefault, onCheckedChange = { asDefault = it })
                    Text("Als Standard der Übung speichern (bisher ${Calc.duration(exerciseDefault.toLong())})")
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(sec, asDefault); onDismiss() }) { Text("Übernehmen") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@Composable
fun RestTimerBar(state: RestTimer.State) {
    val ctx = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state) {
        while (true) {
            now = System.currentTimeMillis()
            if (now >= state.endAt) { RestTimer.clearIfExpired(); break }
            delay(200)
        }
    }
    val remaining = ((state.endAt - now) / 1000.0).coerceAtLeast(0.0)
    Surface(tonalElevation = 6.dp, color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            LinearProgressIndicator(
                progress = { (remaining / state.totalSec).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Pause " + Calc.duration(kotlin.math.ceil(remaining).toLong()), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(state.label, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
                TextButton(onClick = { RestTimer.adjust(ctx, -15) }) { Text("−15") }
                TextButton(onClick = { RestTimer.adjust(ctx, 15) }) { Text("+15") }
                TextButton(onClick = { RestTimer.skip(ctx) }) { Text("Skip") }
            }
        }
    }
}

@Composable
private fun ExercisePickerDialog(data: AppData, onPick: (Exercise) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
            Column(Modifier.padding(16.dp)) {
                Text("Übung hinzufügen", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("Suchen oder neu anlegen") }, singleLine = true)
                val q = query.trim()
                val list = data.exercises
                    .filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) || it.muscleGroup.contains(q, ignoreCase = true) }
                    .sortedBy { it.name.lowercase() }
                LazyColumn(Modifier.weight(1f).padding(top = 8.dp)) {
                    if (q.isNotEmpty() && data.exercises.none { it.name.equals(q, ignoreCase = true) }) item {
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                val ex = Exercise(newId(), q)
                                FitApp.repo.upsertExercise(ex)
                                onPick(ex)
                            }.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Add, null)
                            Spacer(Modifier.width(8.dp))
                            Text("„$q“ als neue Übung anlegen", color = MaterialTheme.colorScheme.primary)
                        }
                        HorizontalDivider()
                    }
                    if (data.exercises.isEmpty() && q.isEmpty()) item {
                        Column {
                            Text("Noch keine Übungen gespeichert. Tippe einen Namen ein oder übernimm die Standard-Übungen.")
                            TextButton(onClick = { FitApp.repo.addStarterExercises() }) { Text("Standard-Übungen hinzufügen") }
                        }
                    }
                    items(list, key = { it.id }) { ex ->
                        Column(Modifier.fillMaxWidth().clickable { onPick(ex) }.padding(vertical = 10.dp)) {
                            Text(ex.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${ex.muscleGroup.ifBlank { "–" }} · ${Calc.loadTypeLabel(ex.loadType)} · Pause ${Calc.duration(ex.defaultRestSec.toLong())}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        HorizontalDivider()
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Schließen") }
            }
        }
    }
}
