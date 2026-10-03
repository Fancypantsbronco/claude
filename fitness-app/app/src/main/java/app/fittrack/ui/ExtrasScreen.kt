package app.fittrack.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.fittrack.FitApp
import app.fittrack.data.ACTIVITY_UNITS
import app.fittrack.data.Activity
import app.fittrack.data.AppData
import app.fittrack.data.defaultUnitFor
import app.fittrack.data.newId
import app.fittrack.domain.Calc
import app.fittrack.domain.Suggestions
import java.time.LocalDate

/** Vorlage für den Dialog: bestehender Eintrag oder neue Aktivität mit Vorschlagswerten. */
data class ActivityDraft(val existing: Activity? = null, val type: String = "", val amount: Double? = null, val unit: String = "")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExtrasScreen(nav: NavHostController) {
    val data by FitApp.repo.data.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf<ActivityDraft?>(null) }
    val list = data.activities.sortedByDescending { it.date + it.startTime }

    ScreenScaffold(
        title = "Bonus-Aktivitäten",
        floatingActionButton = {
            ExtendedFloatingActionButton(text = { Text("Eintragen") }, icon = { Icon(Icons.Filled.Add, null) }, onClick = { draft = ActivityDraft() })
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { BonusGoalCard(data) { draft = it } }
            block {
                SectionTitle("Schnell eintragen")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    data.settings.activityTypes.forEach { t ->
                        AssistChip(onClick = { draft = ActivityDraft(type = t, unit = defaultUnitFor(t)) }, label = { Text(t) })
                    }
                }
            }
            item { SectionTitle("Verlauf") }
            if (list.isEmpty()) item {
                EmptyHint("Alles, was nicht Gym oder Laufen ist, gehört hierher: Liegestütze zu Hause, Schwimmen, Radfahren, Yoga …")
            }
            items(list, key = { it.id }) { a ->
                Column(Modifier.fillMaxWidth().clickable { draft = ActivityDraft(existing = a) }.padding(vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(a.type, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        a.amount?.let { Text("${Calc.num(it, 2)} ${a.unit}", fontWeight = FontWeight.Medium) }
                    }
                    val dur = Calc.minutesBetween(a.startTime, a.endTime)
                    Text(
                        "${Calc.dateDe(a.date)} · ${a.startTime}" + (a.endTime?.let { "–$it" } ?: "") + (dur?.let { " · $it min" } ?: ""),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (a.notes.isNotBlank()) Text(a.notes, style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
            }
        }
    }
    draft?.let { ActivityDialog(it, data.settings.activityTypes, onDismiss = { draft = null }) }
}

/** Wochenziel + Tagesvorschlag – wird auch auf der Startseite gezeigt. */
@Composable
fun BonusGoalCard(data: AppData, onAdd: (ActivityDraft) -> Unit) {
    val weekStart = Calc.weekStart().format(Calc.DATE)
    val done = data.activities.count { it.date >= weekStart }
    val goal = data.settings.bonusWeeklyGoal.coerceAtLeast(1)
    val idea = Suggestions.ideaForDay(LocalDate.now())
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(16.dp)) {
            Text(
                if (done >= goal) "Wochenziel geschafft! 🎉 $done von $goal" else "Bonus diese Woche: $done von $goal",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(progress = { (done.toFloat() / goal).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            Text("💡 ${idea.text}")
            FilledTonalButton(
                onClick = { onAdd(ActivityDraft(type = idea.type, amount = idea.amount, unit = idea.unit)) },
                modifier = Modifier.padding(top = 8.dp),
            ) { Text("Gemacht – eintragen") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActivityDialog(draft: ActivityDraft, types: List<String>, onDismiss: () -> Unit) {
    val e = draft.existing
    var type by remember { mutableStateOf(e?.type ?: draft.type) }
    var date by remember { mutableStateOf(e?.date ?: Calc.today()) }
    var start by remember { mutableStateOf(e?.startTime ?: Calc.nowTime()) }
    var end by remember { mutableStateOf(e?.endTime) }
    var amount by remember { mutableStateOf(e?.amount ?: draft.amount ?: 0.0) }
    var unit by remember { mutableStateOf(e?.unit ?: draft.unit.ifBlank { defaultUnitFor(draft.type) }) }
    var notes by remember { mutableStateOf(e?.notes ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (e == null) "Bonus-Aktivität" else "Aktivität bearbeiten") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SyncedTextField(type, { type = it }, Modifier.fillMaxWidth(), label = "Was?", singleLine = true)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    types.forEach { t ->
                        FilterChip(selected = type == t, onClick = {
                            type = t
                            if (e == null) unit = defaultUnitFor(t)
                        }, label = { Text(t) })
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DateChip(date, { date = it })
                    TimeChip("Start", start, { if (it != null) start = it })
                    TimeChip("Ende", end, { end = it }, allowClear = true)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NumberField(amount, { amount = it }, Modifier.weight(1f), label = "Menge (optional)")
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ACTIVITY_UNITS.forEach { u -> FilterChip(selected = unit == u, onClick = { unit = u }, label = { Text(u) }) }
                }
                SyncedTextField(notes, { notes = it }, Modifier.fillMaxWidth(), label = "Notiz")
            }
        },
        confirmButton = {
            TextButton(enabled = type.isNotBlank(), onClick = {
                FitApp.repo.upsertActivity(
                    Activity(
                        id = e?.id ?: newId(), type = type.trim(), date = date, startTime = start, endTime = end,
                        amount = amount.takeIf { it > 0 }, unit = unit, notes = notes.trim(),
                    )
                )
                onDismiss()
            }) { Text("Speichern") }
        },
        dismissButton = {
            Row {
                if (e != null) TextButton(onClick = { FitApp.repo.deleteActivity(e.id); onDismiss() }) { Text("Löschen") }
                TextButton(onClick = onDismiss) { Text("Abbrechen") }
            }
        },
    )
}
