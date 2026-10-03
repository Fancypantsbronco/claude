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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import app.fittrack.data.BodyWeight
import app.fittrack.data.newId
import app.fittrack.domain.Calc

@Composable
fun BodyScreen(nav: NavHostController, openAdd: Boolean = false) {
    val data by FitApp.repo.data.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf(if (openAdd) "new" else null) }
    val list = data.bodyWeights.sortedWith(compareByDescending<BodyWeight> { it.date }.thenByDescending { it.time })
    val chrono = list.reversed().takeLast(60)

    ScreenScaffold(
        title = "Körpergewicht",
        floatingActionButton = {
            ExtendedFloatingActionButton(text = { Text("Wiegen") }, icon = { Icon(Icons.Filled.Add, null) }, onClick = { editing = "new" })
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        val last = list.firstOrNull()
                        if (last == null) {
                            Text("Noch keine Messung. Dein Körpergewicht wird auch für das 1RM von Körpergewichtsübungen " +
                                "(z. B. Klimmzüge, Dips) verwendet. Die App erinnert dich wöchentlich ans Wiegen.")
                        } else {
                            val monthAgo = java.time.LocalDate.now().minusDays(30).format(Calc.DATE)
                            val ref = list.firstOrNull { it.date <= monthAgo } ?: list.last()
                            val diff = last.kg - ref.kg
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                StatBox("Aktuell", Calc.kg(last.kg), big = true)
                                StatBox("seit ${Calc.dateShort(ref.date)}", (if (diff >= 0) "+" else "") + Calc.kg(diff))
                            }
                            Text(
                                "Letzte Messung ${Calc.relativeDay(last.date)} um ${last.time}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            Spacer(Modifier.height(12.dp))
                            LineChart(chrono.map { it.kg }, labels = chrono.map { Calc.dateShort(it.date) })
                        }
                    }
                }
            }
            items(list, key = { it.id }) { b ->
                Column(Modifier.fillMaxWidth().clickable { editing = b.id }.padding(vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${Calc.dateDe(b.date)}  ${b.time}", modifier = Modifier.weight(1f))
                        Text(Calc.kg(b.kg), fontWeight = FontWeight.SemiBold)
                    }
                    if (b.note.isNotBlank()) Text(b.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
            }
        }
    }
    editing?.let { id ->
        BodyWeightDialog(data.bodyWeights.find { it.id == id }, lastKg = list.firstOrNull()?.kg, onDismiss = { editing = null })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BodyWeightDialog(existing: BodyWeight?, lastKg: Double?, onDismiss: () -> Unit) {
    var date by remember { mutableStateOf(existing?.date ?: Calc.today()) }
    var time by remember { mutableStateOf(existing?.time ?: Calc.nowTime()) }
    var kg by remember { mutableStateOf(existing?.kg ?: lastKg ?: 0.0) }
    var note by remember { mutableStateOf(existing?.note ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Gewicht eintragen" else "Messung bearbeiten") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DateChip(date, { date = it })
                    TimeChip("Uhrzeit", time, { if (it != null) time = it })
                }
                NumberField(kg, { kg = it }, Modifier.fillMaxWidth(), label = "Gewicht (kg)", textStyle = MaterialTheme.typography.headlineSmall)
                SyncedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = "Notiz (optional)", singleLine = true)
            }
        },
        confirmButton = {
            TextButton(enabled = kg > 0, onClick = {
                FitApp.repo.upsertBodyWeight(BodyWeight(existing?.id ?: newId(), date, time, kg, note.trim()))
                onDismiss()
            }) { Text("Speichern") }
        },
        dismissButton = {
            Row {
                if (existing != null) TextButton(onClick = { FitApp.repo.deleteBodyWeight(existing.id); onDismiss() }) { Text("Löschen") }
                TextButton(onClick = onDismiss) { Text("Abbrechen") }
            }
        },
    )
}
