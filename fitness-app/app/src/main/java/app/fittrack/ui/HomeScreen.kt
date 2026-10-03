package app.fittrack.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.fittrack.FitApp
import app.fittrack.domain.Calc
import app.fittrack.goTab
import app.fittrack.run.RunTracker
import java.time.LocalTime

@Composable
fun HomeScreen(nav: NavHostController) {
    val data by FitApp.repo.data.collectAsStateWithLifecycle()
    val live by RunTracker.live.collectAsStateWithLifecycle()
    var addWeight by remember { mutableStateOf(false) }
    var activityDraft by remember { mutableStateOf<ActivityDraft?>(null) }

    val weekStart = Calc.weekStart().format(Calc.DATE)
    val gymWeek = data.workouts.count { it.date >= weekStart }
    val runWeek = data.runs.filter { it.date >= weekStart }
    val bonusWeek = data.activities.count { it.date >= weekStart }
    val running = data.workouts.filter { it.endTime == null }.maxByOrNull { Calc.workoutSortKey(it) }
    val lastWeight = data.bodyWeights.maxWithOrNull(compareBy({ it.date }, { it.time }))
    val weightDue = lastWeight == null || (Calc.daysSince(lastWeight.date) ?: 0) >= 7
    val hour = LocalTime.now().hour
    val greeting = when (hour) { in 5..10 -> "Guten Morgen"; in 11..17 -> "Hallo"; else -> "Guten Abend" }

    ScreenScaffold(
        title = "FitTrack",
        actions = {
            IconButton(onClick = { nav.navigate("settings") }) { Icon(Icons.Filled.Share, "Export") }
            IconButton(onClick = { nav.navigate("settings") }) { Icon(Icons.Filled.Settings, "Einstellungen") }
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("$greeting! 👋", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            if (live.active) item {
                HighlightCard("Lauf läuft · ${Calc.km(live.distanceM)} km", "Tippen, um die Aufzeichnung zu öffnen") { nav.navigate("liverun") }
            }
            if (running != null) item {
                HighlightCard("Training läuft: ${running.title}", "Seit ${running.startTime} · tippen zum Fortsetzen") { nav.navigate("workout/${running.id}") }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Diese Woche", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            StatBox("Gym", "$gymWeek")
                            StatBox("Lauf-km", Calc.km(runWeek.sumOf { it.distanceM }))
                            StatBox("Bonus", "$bonusWeek")
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickAction(Icons.Filled.FitnessCenter, "Training", Modifier.weight(1f)) {
                        nav.navigate("workout/${running?.id ?: startNewWorkout()}")
                    }
                    QuickAction(Icons.Filled.DirectionsRun, "Laufen", Modifier.weight(1f)) { nav.navigate("liverun") }
                    QuickAction(Icons.Filled.MonitorWeight, "Wiegen", Modifier.weight(1f)) { addWeight = true }
                }
            }
            if (weightDue) item {
                Card(Modifier.fillMaxWidth().clickable { addWeight = true }) {
                    Column(Modifier.padding(16.dp)) {
                        Text("⚖️ Zeit fürs Wiegen", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (lastWeight == null) "Noch keine Messung – tippe zum Eintragen."
                            else "Letzte Messung ${Calc.relativeDay(lastWeight.date)}: ${Calc.kg(lastWeight.kg)}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item { BonusGoalCard(data) { activityDraft = it } }
            item { SectionTitle("Zuletzt") }
            val recent = buildList {
                data.workouts.forEach { add(Triple(Calc.workoutSortKey(it), "🏋️ ${it.title} · ${it.exercises.size} Übungen", "workout/${it.id}")) }
                data.runs.forEach { add(Triple(it.date + "T" + it.startTime, "🏃 ${Calc.km(it.distanceM)} km · Ø ${Calc.pace(Calc.paceSecPerKm(it.durationSec.toDouble(), it.distanceM))} /km", "rundetail/${it.id}")) }
                data.activities.forEach { add(Triple(it.date + "T" + it.startTime, "⭐ ${it.type}" + (it.amount?.let { a -> " · ${Calc.num(a)} ${it.unit}" } ?: ""), "extras")) }
            }.sortedByDescending { it.first }.take(6)
            if (recent.isEmpty()) item { EmptyHint("Hier erscheinen deine letzten Trainings, Läufe und Bonus-Aktivitäten.") }
            recent.forEachIndexed { i, (key, text, route) ->
                item(key = "recent_$i") {
                    Row(
                        Modifier.fillMaxWidth().clickable { if (route == "extras") nav.goTab("extras") else nav.navigate(route) }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(text, modifier = Modifier.weight(1f))
                        Text(Calc.relativeDay(key.substringBefore('T')), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
    if (addWeight) BodyWeightDialog(null, lastKg = lastWeight?.kg, onDismiss = { addWeight = false })
    activityDraft?.let { ActivityDialog(it, data.settings.activityTypes, onDismiss = { activityDraft = null }) }
}

@Composable
private fun HighlightCard(title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun QuickAction(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = modifier.height(72.dp), contentPadding = PaddingValues(4.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null)
            Spacer(Modifier.width(4.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}
