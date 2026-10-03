package app.fittrack.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.fittrack.FitApp
import app.fittrack.data.AppData
import app.fittrack.data.Exercise
import app.fittrack.data.LoadType
import app.fittrack.data.MUSCLE_GROUPS
import app.fittrack.data.Workout
import app.fittrack.data.newId
import app.fittrack.domain.Calc

/** Legt ein neues Training mit aktuellem Datum und Startzeit an und liefert die ID. */
fun startNewWorkout(): String {
    val w = Workout(id = newId(), title = "Training", date = Calc.today(), startTime = Calc.nowTime())
    FitApp.repo.upsertWorkout(w)
    return w.id
}

@Composable
fun GymScreen(nav: NavHostController) {
    val data by FitApp.repo.data.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    ScreenScaffold(
        title = "Gym",
        floatingActionButton = {
            if (tab == 0) ExtendedFloatingActionButton(
                text = { Text("Training starten") },
                icon = { Icon(Icons.Filled.Add, null) },
                onClick = { nav.navigate("workout/${startNewWorkout()}") },
            ) else ExtendedFloatingActionButton(
                text = { Text("Neue Übung") },
                icon = { Icon(Icons.Filled.Add, null) },
                onClick = { nav.navigate("exercise/new") },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Trainings") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Übungen") })
            }
            if (tab == 0) WorkoutList(data, nav) else ExerciseLibrary(data, nav)
        }
    }
}

@Composable
private fun WorkoutList(data: AppData, nav: NavHostController) {
    val workouts = data.workouts.sortedByDescending { Calc.workoutSortKey(it) }
    LazyColumn(
        contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (workouts.isEmpty()) item {
            EmptyHint("Noch keine Trainings. Starte dein erstes Training mit dem Knopf unten rechts – " +
                "Datum und Startzeit werden automatisch gesetzt und lassen sich ändern.")
        }
        items(workouts, key = { it.id }) { w -> WorkoutCard(data, w) { nav.navigate("workout/${w.id}") } }
    }
}

@Composable
fun WorkoutCard(data: AppData, w: Workout, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(w.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (w.endTime == null) Text("läuft", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
            val dur = Calc.minutesBetween(w.startTime, w.endTime)
            Text(
                "${Calc.dateDe(w.date)} · ${w.startTime}–${w.endTime ?: "…"}" + (dur?.let { " · ${Calc.minutesText(it)}" } ?: ""),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (w.exercises.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                w.exercises.forEach { e ->
                    val st = Calc.entryStats(data, w, e)
                    Text(
                        "${st.doneSets.size} × ${Calc.exerciseName(data, e.exerciseId)}" +
                            (st.bestE1rm?.let { "  ·  e1RM ${Calc.num(it)} kg" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun ExerciseLibrary(data: AppData, nav: NavHostController) {
    val groups = data.exercises.sortedBy { it.name.lowercase() }.groupBy { it.muscleGroup.ifBlank { "Sonstige" } }.toSortedMap()
    LazyColumn(contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp)) {
        if (data.exercises.isEmpty()) block {
            EmptyHint("Deine Übungsdatenbank ist leer. Lege Übungen einmal an (Name, Art, Standard-Pause, Sätze/Wiederholungen) " +
                "und füge sie dann in jedes Training ein.")
            Spacer(Modifier.height(12.dp))
            Button(onClick = { FitApp.repo.addStarterExercises() }) { Text("15 Standard-Übungen hinzufügen") }
        }
        groups.forEach { (group, list) ->
            item(key = "g_$group") { SectionTitle(group) }
            items(list, key = { it.id }) { ex ->
                val best = Calc.history(data, ex.id).mapNotNull { it.stats.bestE1rm }.maxOrNull()
                Row(
                    Modifier.fillMaxWidth().clickable { nav.navigate("history/${ex.id}") }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(ex.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text(
                            "${Calc.loadTypeLabel(ex.loadType)} · Pause ${Calc.duration(ex.defaultRestSec.toLong())}" +
                                (best?.let { " · bestes e1RM ${Calc.num(it)} kg" } ?: ""),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { nav.navigate("exercise/${ex.id}") }) { Icon(Icons.Filled.Edit, "Bearbeiten") }
                }
                HorizontalDivider()
            }
        }
    }
}

// ------------------------------------------------------------------ Übung bearbeiten

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ExerciseEditScreen(nav: NavHostController, id: String) {
    val data = FitApp.repo.current
    val existing = data.exercises.find { it.id == id }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var group by remember { mutableStateOf(existing?.muscleGroup ?: "") }
    var type by remember { mutableStateOf(existing?.loadType ?: LoadType.WEIGHTED) }
    var rest by remember { mutableStateOf((existing?.defaultRestSec ?: 90).toDouble()) }
    var sets by remember { mutableStateOf((existing?.defaultSets ?: 3).toDouble()) }
    var reps by remember { mutableStateOf((existing?.defaultReps ?: 10).toDouble()) }
    var weight by remember { mutableStateOf(existing?.defaultWeightKg ?: 0.0) }
    var notes by remember { mutableStateOf(existing?.notes ?: "") }
    var confirmDelete by remember { mutableStateOf(false) }
    val usedIn = data.workouts.count { w -> w.exercises.any { it.exerciseId == id } }

    fun save() {
        if (name.isBlank()) return
        FitApp.repo.upsertExercise(
            Exercise(
                id = existing?.id ?: newId(), name = name.trim(), muscleGroup = group.trim(), loadType = type,
                defaultRestSec = rest.toInt(), defaultSets = sets.toInt().coerceAtLeast(1),
                defaultReps = reps.toInt(), defaultWeightKg = weight, notes = notes.trim(),
            )
        )
        nav.popBackStack()
    }

    ScreenScaffold(
        title = if (existing == null) "Neue Übung" else "Übung bearbeiten",
        onBack = { nav.popBackStack() },
        actions = {
            if (existing != null) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Löschen") }
            IconButton(onClick = { save() }, enabled = name.isNotBlank()) { Icon(Icons.Filled.Check, "Speichern") }
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { SyncedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = "Name", singleLine = true) }
            block {
                SyncedTextField(group, { group = it }, Modifier.fillMaxWidth(), label = "Muskelgruppe", singleLine = true)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MUSCLE_GROUPS.forEach { g -> FilterChip(selected = group == g, onClick = { group = g }, label = { Text(g) }) }
                }
            }
            block {
                SectionTitle("Art")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val options = listOf(LoadType.WEIGHTED to "Gewicht", LoadType.BODYWEIGHT to "Körper", LoadType.BODYWEIGHT_PLUS to "Körper + kg")
                    options.forEachIndexed { i, (t, label) ->
                        SegmentedButton(
                            selected = type == t, onClick = { type = t },
                            shape = SegmentedButtonDefaults.itemShape(i, options.size),
                        ) { Text(label) }
                    }
                }
                Text(
                    when (type) {
                        LoadType.WEIGHTED -> "Hantel/Maschine: du trägst das Gewicht pro Satz ein."
                        LoadType.BODYWEIGHT -> "Nur Körpergewicht. Für das 1RM wird dein zuletzt eingetragenes Körpergewicht verwendet."
                        LoadType.BODYWEIGHT_PLUS -> "Körpergewicht plus Zusatzgewicht (z. B. Gewichtsgürtel). 1RM = (Körpergewicht + Zusatz)."
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            block {
                SectionTitle("Pausen-Timer (Standard)")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NumberField(rest, { rest = it }, Modifier.width(110.dp), label = "Sekunden", decimals = false)
                    Spacer(Modifier.width(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(60, 90, 120, 180).forEach { s ->
                            FilterChip(selected = rest.toInt() == s, onClick = { rest = s.toDouble() }, label = { Text(Calc.duration(s.toLong())) })
                        }
                    }
                }
            }
            block {
                SectionTitle("Standard für neue Trainings")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(sets, { sets = it }, Modifier.weight(1f), label = "Sätze", decimals = false)
                    NumberField(reps, { reps = it }, Modifier.weight(1f), label = "Wdh", decimals = false)
                    if (type != LoadType.BODYWEIGHT) NumberField(
                        weight, { weight = it }, Modifier.weight(1f),
                        label = if (type == LoadType.WEIGHTED) "kg" else "+kg",
                    )
                }
                Text(
                    "Wenn es schon eine frühere Einheit gibt, werden stattdessen deren Sätze vorgeschlagen.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item { SyncedTextField(notes, { notes = it }, Modifier.fillMaxWidth(), label = "Notizen (z. B. Sitzhöhe, Griff)", minLines = 3) }
            item {
                Button(onClick = { save() }, enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Speichern") }
            }
        }
    }
    if (confirmDelete && existing != null) {
        if (usedIn > 0) {
            ConfirmDialog(
                title = "Übung wird verwendet",
                text = "„${existing.name}“ kommt in $usedIn Training(s) vor. Damit Verlauf und Export vollständig bleiben, kann sie nicht gelöscht werden.",
                confirm = "OK", onConfirm = {}, onDismiss = { confirmDelete = false },
            )
        } else {
            ConfirmDialog(
                title = "Übung löschen?", text = "„${existing.name}“ wird aus der Datenbank entfernt.",
                onConfirm = { FitApp.repo.deleteExercise(existing.id); nav.popBackStack() },
                onDismiss = { confirmDelete = false },
            )
        }
    }
}

// ------------------------------------------------------------------ Verlauf einer Übung

@Composable
fun ExerciseHistoryScreen(nav: NavHostController, id: String) {
    val data by FitApp.repo.data.collectAsStateWithLifecycle()
    val ex = data.exercises.find { it.id == id }
    val hist = remember(data, id) { Calc.history(data, id) }
    ScreenScaffold(
        title = ex?.name ?: "Verlauf",
        onBack = { nav.popBackStack() },
        actions = { if (ex != null) IconButton(onClick = { nav.navigate("exercise/$id") }) { Icon(Icons.Filled.Edit, "Bearbeiten") } },
    ) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (ex != null) block {
                Text(
                    "${ex.muscleGroup.ifBlank { "–" }} · ${Calc.loadTypeLabel(ex.loadType)} · Pause ${Calc.duration(ex.defaultRestSec.toLong())}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (ex.notes.isNotBlank()) Text("Notiz: ${ex.notes}", style = MaterialTheme.typography.bodyMedium)
            }
            if (hist.isEmpty()) item { EmptyHint("Noch keine erledigten Sätze mit dieser Übung.") }
            else {
                val chrono = hist.reversed()
                val withRm = chrono.filter { it.stats.bestE1rm != null }
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            if (withRm.isNotEmpty()) {
                                val best = withRm.maxBy { it.stats.bestE1rm!! }
                                val first = withRm.first().stats.bestE1rm!!
                                val last = withRm.last().stats.bestE1rm!!
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                    StatBox("Bestes e1RM", Calc.num(best.stats.bestE1rm!!) + " kg")
                                    StatBox("Letztes e1RM", Calc.num(last) + " kg")
                                    StatBox("Seit Start", (if (last >= first) "+" else "") + Calc.num(last - first) + " kg")
                                }
                                Spacer(Modifier.height(12.dp))
                                Text("e1RM-Verlauf (kg)", style = MaterialTheme.typography.labelLarge)
                                LineChart(
                                    withRm.map { it.stats.bestE1rm!! },
                                    labels = withRm.map { Calc.dateShort(it.workout.date) },
                                )
                            } else {
                                Text("Für ein 1RM bei Körpergewichtsübungen trage bitte dein Körpergewicht ein. Bis dahin: max. Wiederholungen.")
                                LineChart(chrono.map { it.stats.maxReps.toDouble() }, labels = chrono.map { Calc.dateShort(it.workout.date) }) { Calc.num(it, 0) }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text("${hist.size} Einheiten", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item { SectionTitle("Historie") }
                items(hist, key = { it.entry.id }) { h ->
                    Card(Modifier.fillMaxWidth().clickable { nav.navigate("workout/${h.workout.id}") }) {
                        Column(Modifier.padding(12.dp)) {
                            Row {
                                Text(Calc.dateDe(h.workout.date), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                h.stats.bestE1rm?.let { Text("e1RM ${Calc.num(it)} kg", color = MaterialTheme.colorScheme.primary) }
                            }
                            val type = ex?.loadType ?: LoadType.WEIGHTED
                            Text(h.stats.doneSets.joinToString("   ") { Calc.setShort(type, it.set) })
                            if (h.entry.notes.isNotBlank()) Text("Notiz: ${h.entry.notes}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
