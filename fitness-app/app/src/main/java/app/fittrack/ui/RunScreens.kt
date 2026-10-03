package app.fittrack.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.fittrack.FitApp
import app.fittrack.data.Run
import app.fittrack.data.RunSource
import app.fittrack.data.newId
import app.fittrack.domain.Calc
import app.fittrack.domain.Track
import app.fittrack.run.RunService
import app.fittrack.run.RunTracker
import kotlinx.coroutines.delay
import java.time.LocalDate

// ------------------------------------------------------------------ Liste

@Composable
fun RunListScreen(nav: NavHostController) {
    val data by FitApp.repo.data.collectAsStateWithLifecycle()
    val live by RunTracker.live.collectAsStateWithLifecycle()
    var manual by remember { mutableStateOf(false) }
    val runs = data.runs.sortedByDescending { it.date + it.startTime }
    val week = Calc.weekStart().format(Calc.DATE)
    val month = LocalDate.now().withDayOfMonth(1).format(Calc.DATE)

    ScreenScaffold(
        title = "Laufen",
        actions = { TextButton(onClick = { manual = true }) { Icon(Icons.Filled.Add, null); Text("Manuell") } },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text(if (live.active) "Lauf öffnen" else "Lauf starten") },
                icon = { Icon(Icons.Filled.PlayArrow, null) },
                onClick = { nav.navigate("liverun") },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        StatBox("Diese Woche", Calc.km(data.runs.filter { it.date >= week }.sumOf { it.distanceM }) + " km")
                        StatBox("Dieser Monat", Calc.km(data.runs.filter { it.date >= month }.sumOf { it.distanceM }) + " km")
                        StatBox("Gesamt", Calc.num(data.runs.sumOf { it.distanceM } / 1000, 0) + " km")
                    }
                }
            }
            if (runs.isEmpty()) item {
                EmptyHint("Noch keine Läufe. „Lauf starten“ zeichnet per GPS Strecke, Gesamt-Pace und die Pace jedes Kilometers auf – " +
                    "auch bei ausgeschaltetem Bildschirm. Laufband-Läufe kannst du oben über „Manuell“ eintragen.")
            }
            items(runs, key = { it.id }) { r -> RunCard(r) { nav.navigate("rundetail/${r.id}") } }
        }
    }
    if (manual) RunEditDialog(null, onDismiss = { manual = false })
}

@Composable
fun RunCard(r: Run, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${Calc.km(r.distanceM)} km", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(feelingEmoji(r.feeling), style = MaterialTheme.typography.titleLarge)
            }
            Text(
                "${Calc.dateDe(r.date)} · ${r.startTime} · ${Calc.duration(r.durationSec)} · Ø ${Calc.pace(Calc.paceSecPerKm(r.durationSec.toDouble(), r.distanceM))} /km" +
                    if (r.source == RunSource.MANUAL) " · manuell" else "",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

fun feelingEmoji(f: Int?) = when (f) { 1 -> "😫"; 2 -> "😕"; 3 -> "😐"; 4 -> "🙂"; 5 -> "😄"; else -> "" }

// ------------------------------------------------------------------ Live-Aufzeichnung

@Composable
fun LiveRunScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val live by RunTracker.live.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    var confirmStop by remember { mutableStateOf(false) }
    var denied by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { while (true) { now = android.os.SystemClock.elapsedRealtime(); delay(500) } }
    LaunchedEffect(live.lastSavedRunId) {
        live.lastSavedRunId?.let { id ->
            RunTracker.consumeSavedRun()
            nav.navigate("rundetail/$id") { popUpTo("liverun") { inclusive = true } }
        }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.ACCESS_FINE_LOCATION] == true) RunService.send(ctx, RunService.ACTION_START) else denied = true
    }
    fun start() {
        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (granted) RunService.send(ctx, RunService.ACTION_START)
        else launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    val activeSec = live.activeMs(now) / 1000
    ScreenScaffold(title = "Lauf", onBack = { nav.popBackStack() }) { padding ->
        Column(Modifier.padding(padding).fillMaxHeight()) {
            Box(Modifier.fillMaxWidth().height(260.dp)) {
                if (live.points.isNotEmpty()) RouteMap(live.points, Modifier.fillMaxWidth().height(260.dp), follow = true)
                else Box(Modifier.fillMaxWidth().height(260.dp).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                    Text(
                        if (live.active) "Warte auf GPS-Signal …" else "Die Karte erscheint, sobald die Aufzeichnung läuft.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(Modifier.weight(1f).padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    StatBox("Dauer", Calc.duration(activeSec), big = true)
                    StatBox("km", Calc.km(live.distanceM), big = true)
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    StatBox("Ø Pace /km", Calc.pace(Calc.paceSecPerKm(activeSec.toDouble(), live.distanceM)))
                    StatBox("Aktuelle Pace", Calc.pace(live.currentPace))
                    StatBox("GPS", live.accuracy?.let { "±${it.toInt()} m" } ?: "–")
                }
                Spacer(Modifier.height(12.dp))
                if (live.splits.isNotEmpty()) {
                    Text("Kilometer", style = MaterialTheme.typography.titleSmall)
                    LazyColumn(Modifier.weight(1f)) {
                        items(live.splits.reversed()) { s ->
                            KeyValueRow("km ${s.km}", Calc.pace(Calc.paceSecPerKm(s.durationSec, s.distanceM)) + " /km")
                        }
                    }
                } else Spacer(Modifier.weight(1f))
                if (denied) Text(
                    "Ohne Standort-Berechtigung kann die Strecke nicht aufgezeichnet werden. Bitte in den App-Einstellungen erlauben.",
                    color = MaterialTheme.colorScheme.error,
                )
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (live.status) {
                        RunTracker.Status.IDLE -> Button(onClick = { start() }, modifier = Modifier.weight(1f).height(64.dp)) {
                            Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("Start", style = MaterialTheme.typography.titleLarge)
                        }
                        RunTracker.Status.RUNNING -> {
                            OutlinedButton(onClick = { RunService.send(ctx, RunService.ACTION_PAUSE) }, modifier = Modifier.weight(1f).height(64.dp)) {
                                Icon(Icons.Filled.Pause, null); Spacer(Modifier.width(8.dp)); Text("Pause")
                            }
                            StopButton(Modifier.weight(1f)) { confirmStop = true }
                        }
                        RunTracker.Status.PAUSED -> {
                            Button(onClick = { RunService.send(ctx, RunService.ACTION_RESUME) }, modifier = Modifier.weight(1f).height(64.dp)) {
                                Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("Weiter")
                            }
                            StopButton(Modifier.weight(1f)) { confirmStop = true }
                        }
                    }
                }
            }
        }
    }
    if (confirmStop) ConfirmDialog(
        title = "Lauf beenden?", text = "Der Lauf wird gespeichert (${Calc.km(live.distanceM)} km, ${Calc.duration(activeSec)}).",
        confirm = "Beenden & speichern",
        onConfirm = { RunService.send(ctx, RunService.ACTION_STOP) }, onDismiss = { confirmStop = false },
    )
}

@Composable
private fun StopButton(modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick, modifier = modifier.height(64.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
    ) { Icon(Icons.Filled.Stop, null); Spacer(Modifier.width(8.dp)); Text("Beenden") }
}

// ------------------------------------------------------------------ Detail

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RunDetailScreen(nav: NavHostController, id: String) {
    val data by FitApp.repo.data.collectAsStateWithLifecycle()
    val r = data.runs.find { it.id == id }
    if (r == null) {
        LaunchedEffect(Unit) { nav.popBackStack() }
        return
    }
    val track = remember(id) { FitApp.repo.loadTrack(id) }
    var edit by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val avgPace = Calc.paceSecPerKm(r.durationSec.toDouble(), r.distanceM)

    ScreenScaffold(
        title = "Lauf · ${Calc.dateShort(r.date)}",
        onBack = { nav.popBackStack() },
        actions = {
            IconButton(onClick = { edit = true }) { Icon(Icons.Filled.Edit, "Bearbeiten") }
            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Löschen") }
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (track.size >= 2) item { RouteMap(track, Modifier.fillMaxWidth().height(280.dp)) }
            block {
                Column(Modifier.padding(16.dp)) {
                    Text("${Calc.dateDe(r.date)} · ${r.startTime}–${r.endTime ?: "?"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        StatBox("km", Calc.km(r.distanceM), big = true)
                        StatBox("Dauer", Calc.duration(r.durationSec), big = true)
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        StatBox("Ø Pace /km", Calc.pace(avgPace))
                        StatBox("Ø km/h", Calc.num(Calc.speedKmh(r.durationSec.toDouble(), r.distanceM), 1))
                        StatBox("Höhenmeter", Calc.num(r.elevationGainM, 0))
                    }
                    if (r.source == RunSource.MANUAL) Text(
                        "Manuell eingetragen – die Kilometer-Splits sind gleichmäßig verteilt.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            if (r.splits.isNotEmpty()) block {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    SectionTitle("Pace pro Kilometer")
                    val paces = r.splits.map { Calc.paceSecPerKm(it.durationSec, it.distanceM) ?: 0.0 }
                    val fastest = paces.filter { it > 0 }.minOrNull() ?: 1.0
                    val slowest = paces.maxOrNull() ?: 1.0
                    r.splits.forEachIndexed { i, s ->
                        val p = paces[i]
                        // schnellster km = voller Balken
                        val frac = if (slowest - fastest < 1) 1f else (0.35f + 0.65f * ((slowest - p) / (slowest - fastest)).toFloat())
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (s.distanceM < 999) "${Calc.num(s.distanceM / 1000, 2)}" else "${s.km}",
                                Modifier.width(44.dp), fontWeight = FontWeight.Medium,
                            )
                            Box(Modifier.weight(1f).height(22.dp)) {
                                Box(
                                    Modifier.fillMaxWidth(frac).height(22.dp).background(
                                        if (p == fastest) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
                                        RoundedCornerShape(6.dp),
                                    )
                                )
                            }
                            Text(Calc.pace(p), Modifier.width(56.dp).padding(start = 8.dp), fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
            block {
                Column(Modifier.padding(16.dp)) {
                    SectionTitle("Wie hat es sich angefühlt?")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (1..5).forEach { f ->
                            FilterChip(
                                selected = r.feeling == f,
                                onClick = { FitApp.repo.upsertRun(r.copy(feeling = if (r.feeling == f) null else f)) },
                                label = { Text(feelingEmoji(f), style = MaterialTheme.typography.titleLarge) },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    SyncedTextField(
                        r.notes, { t -> FitApp.repo.data.value.runs.find { it.id == id }?.let { FitApp.repo.upsertRun(it.copy(notes = t)) } },
                        Modifier.fillMaxWidth(), label = "Notizen (Strecke, Wetter, Schuhe …)", minLines = 3,
                    )
                }
            }
        }
    }
    if (edit) RunEditDialog(r, onDismiss = { edit = false })
    if (confirmDelete) ConfirmDialog(
        title = "Lauf löschen?", text = "Der Lauf vom ${Calc.dateDe(r.date)} wird inklusive GPS-Strecke gelöscht.",
        onConfirm = { FitApp.repo.deleteRun(id); nav.popBackStack() }, onDismiss = { confirmDelete = false },
    )
}

/** Manuellen Lauf anlegen (run == null) oder Datum/Zeit/Distanz/Dauer korrigieren. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RunEditDialog(run: Run?, onDismiss: () -> Unit) {
    var date by remember { mutableStateOf(run?.date ?: Calc.today()) }
    var start by remember { mutableStateOf(run?.startTime ?: Calc.nowTime()) }
    var km by remember { mutableStateOf((run?.distanceM ?: 0.0) / 1000) }
    val d = run?.durationSec ?: 0L
    var h by remember { mutableStateOf((d / 3600).toDouble()) }
    var m by remember { mutableStateOf(((d % 3600) / 60).toDouble()) }
    var s by remember { mutableStateOf((d % 60).toDouble()) }
    val dur = (h * 3600 + m * 60 + s).toLong()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (run == null) "Lauf eintragen" else "Lauf bearbeiten") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DateChip(date, { date = it })
                    TimeChip("Start", start, { if (it != null) start = it })
                }
                NumberField(km, { km = it }, Modifier.fillMaxWidth(), label = "Distanz (km)")
                Text("Dauer (ohne Pausen)", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NumberField(h, { h = it }, Modifier.weight(1f), label = "h", decimals = false)
                    NumberField(m, { m = it }, Modifier.weight(1f), label = "min", decimals = false)
                    NumberField(s, { s = it }, Modifier.weight(1f), label = "s", decimals = false)
                }
                Text(
                    "Ø Pace: ${Calc.pace(Calc.paceSecPerKm(dur.toDouble(), km * 1000))} /km",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = km > 0 && dur > 0, onClick = {
                val distM = km * 1000
                val base = run ?: Run(id = newId(), date = date, startTime = start, source = RunSource.MANUAL)
                val changedMetrics = run == null || kotlin.math.abs(run.distanceM - distM) > 1 || run.durationSec != dur
                FitApp.repo.upsertRun(
                    base.copy(
                        date = date, startTime = start, endTime = Calc.addSeconds(start, dur),
                        distanceM = distM, durationSec = dur,
                        splits = if (changedMetrics) Track.evenSplits(distM, dur) else base.splits,
                    )
                )
                onDismiss()
            }) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
