package app.fittrack.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.fittrack.BuildConfig
import app.fittrack.FitApp
import app.fittrack.data.Backup
import app.fittrack.data.OneRmFormula
import app.fittrack.data.Settings
import app.fittrack.domain.Calc
import app.fittrack.domain.Export
import app.fittrack.notify.Reminders
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm")
private val CREATED = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private fun writeExport(ctx: Context, name: String, content: String): Uri {
    val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() }
    val f = File(dir, name)
    f.writeText(content, Charsets.UTF_8)
    return FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
}

private fun share(ctx: Context, name: String, content: String, mime: String) {
    val uri = writeExport(ctx, name, content)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "Mein Training (FitTrack)")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(send, "Export teilen"))
}

private fun copy(ctx: Context, text: String) {
    try {
        val cm = ctx.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("FitTrack-Export", text))
        Toast.makeText(ctx, "In die Zwischenablage kopiert – jetzt in die KI einfügen", Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        Toast.makeText(ctx, "Zu groß für die Zwischenablage – bitte „Teilen“ verwenden", Toast.LENGTH_LONG).show()
    }
}

private fun writeToUri(ctx: Context, uri: Uri, content: String) {
    try {
        ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
        Toast.makeText(ctx, "Gespeichert", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(ctx, "Speichern fehlgeschlagen: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val data by FitApp.repo.data.collectAsStateWithLifecycle()
    val s = data.settings
    var rangeDays by remember { mutableStateOf<Int?>(null) }
    var pendingSave by remember { mutableStateOf<String?>(null) }
    var importCandidate by remember { mutableStateOf<Backup?>(null) }

    fun from(): String? = rangeDays?.let { LocalDate.now().minusDays(it.toLong()).format(Calc.DATE) }
    fun created() = LocalDateTime.now().format(CREATED)
    fun stamp() = LocalDateTime.now().format(STAMP)
    fun text() = Export.text(FitApp.repo.current, from(), created())
    fun aiJson() = Export.aiJson(FitApp.repo.current, from(), created())

    fun updateSettings(f: (Settings) -> Settings) {
        FitApp.repo.updateSettings(f)
        Reminders.sync(ctx, FitApp.repo.current.settings)
    }

    val saveTxt = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val c = pendingSave
        if (uri != null && c != null) writeToUri(ctx, uri, c)
        pendingSave = null
    }
    val saveJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val c = pendingSave
        if (uri != null && c != null) writeToUri(ctx, uri, c)
        pendingSave = null
    }
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val txt = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                importCandidate = Export.parseBackup(txt)
            } catch (e: Exception) {
                Toast.makeText(ctx, "Keine gültige FitTrack-Backup-Datei", Toast.LENGTH_LONG).show()
            }
        }
    }

    ScreenScaffold(title = "Export & Einstellungen", onBack = { nav.popBackStack() }) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionTitle("Export für eine KI")
                        Text(
                            "Dein komplettes Training als Text oder JSON – mit Übungsnamen, Sätzen, e1RM-Verlauf, Läufen mit " +
                                "Kilometer-Pace, Körpergewicht und Bonus-Aktivitäten. Einfach in ChatGPT, Claude & Co. einfügen.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text("Zeitraum", style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf<Pair<Int?, String>>(null to "Alles", 365 to "1 Jahr", 90 to "90 Tage", 30 to "30 Tage").forEach { (d, label) ->
                                FilterChip(selected = rangeDays == d, onClick = { rangeDays = d }, label = { Text(label) })
                            }
                        }
                        Text("Text (.txt)", style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = { copy(ctx, text()) }) { Text("Kopieren") }
                            FilledTonalButton(onClick = { share(ctx, "training_${stamp()}.txt", text(), "text/plain") }) { Text("Teilen") }
                            OutlinedButton(onClick = { pendingSave = text(); saveTxt.launch("training_${stamp()}.txt") }) { Text("Speichern") }
                        }
                        Text("JSON (.json)", style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = { copy(ctx, aiJson()) }) { Text("Kopieren") }
                            FilledTonalButton(onClick = { share(ctx, "training_${stamp()}.json", aiJson(), "application/json") }) { Text("Teilen") }
                            OutlinedButton(onClick = { pendingSave = aiJson(); saveJson.launch("training_${stamp()}.json") }) { Text("Speichern") }
                        }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionTitle("Backup")
                        Text(
                            "Sichert alle Daten inkl. GPS-Strecken, z. B. vor einem Handywechsel. Über „Importieren“ wiederherstellen.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = {
                                pendingSave = Export.backup(FitApp.repo.current, FitApp.repo.allTracks(), created())
                                saveJson.launch("fittrack_backup_${stamp()}.json")
                            }) { Text("Backup speichern") }
                            OutlinedButton(onClick = { openBackup.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) {
                                Text("Importieren")
                            }
                        }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionTitle("Erinnerungen")
                        SwitchRow("Wöchentlich ans Wiegen erinnern", s.weightReminder) { v -> updateSettings { it.copy(weightReminder = v) } }
                        if (s.weightReminder) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So").forEachIndexed { i, d ->
                                    FilterChip(selected = s.weightReminderDay == i + 1, onClick = { updateSettings { it.copy(weightReminderDay = i + 1) } }, label = { Text(d) })
                                }
                            }
                            TimeChip("Uhrzeit", s.weightReminderTime, { t -> if (t != null) updateSettings { it.copy(weightReminderTime = t) } })
                        }
                        SwitchRow("Zu Bonus-Aktivitäten ermutigen", s.bonusReminder) { v -> updateSettings { it.copy(bonusReminder = v) } }
                        if (s.bonusReminder) {
                            Text(
                                "Abends eine Erinnerung, wenn du dein Wochenziel noch nicht erreicht und 2 Tage nichts eingetragen hast.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TimeChip("Uhrzeit", s.bonusReminderTime, { t -> if (t != null) updateSettings { it.copy(bonusReminderTime = t) } })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Bonus-Wochenziel: ${s.bonusWeeklyGoal}", Modifier.weight(1f))
                            TextButton(onClick = { updateSettings { it.copy(bonusWeeklyGoal = (it.bonusWeeklyGoal - 1).coerceAtLeast(1)) } }) { Text("−") }
                            TextButton(onClick = { updateSettings { it.copy(bonusWeeklyGoal = (it.bonusWeeklyGoal + 1).coerceAtMost(14)) } }) { Text("+") }
                        }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionTitle("Training")
                        Text("1RM-Formel", style = MaterialTheme.typography.labelLarge)
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            val opts = listOf(OneRmFormula.EPLEY to "Epley", OneRmFormula.BRZYCKI to "Brzycki")
                            opts.forEachIndexed { i, (f, label) ->
                                SegmentedButton(
                                    selected = s.oneRmFormula == f, onClick = { updateSettings { it.copy(oneRmFormula = f) } },
                                    shape = SegmentedButtonDefaults.itemShape(i, opts.size),
                                ) { Text(label) }
                            }
                        }
                        Text(
                            "Epley: Last × (1 + Wdh/30). Brzycki: Last × 36/(37 − Wdh). Beide schätzen, welches Gewicht du genau einmal schaffen würdest – " +
                                "so sind Einheiten mit unterschiedlichem Gewicht und Wiederholungen vergleichbar.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(onClick = {
                            FitApp.repo.addStarterExercises()
                            Toast.makeText(ctx, "Standard-Übungen ergänzt", Toast.LENGTH_SHORT).show()
                        }) { Text("Standard-Übungen hinzufügen") }
                        SectionTitle("Laufen")
                        SwitchRow("Sprachansage nach jedem Kilometer", s.voiceFeedback) { v -> updateSettings { it.copy(voiceFeedback = v) } }
                    }
                }
            }
            item {
                Text(
                    "FitTrack ${BuildConfig.VERSION_NAME} · Daten bleiben auf deinem Handy · Karten © OpenStreetMap-Mitwirkende",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    importCandidate?.let { b ->
        ConfirmDialog(
            title = "Backup importieren?",
            text = "Gefunden: ${b.data.workouts.size} Trainings, ${b.data.runs.size} Läufe, ${b.data.exercises.size} Übungen, " +
                "${b.data.bodyWeights.size} Messungen, ${b.data.activities.size} Bonus-Aktivitäten. Deine aktuellen Daten werden ersetzt.",
            confirm = "Ersetzen",
            onConfirm = {
                FitApp.repo.replaceAll(b.data, b.tracks)
                Reminders.sync(ctx, b.data.settings)
                Toast.makeText(ctx, "Backup importiert", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { importCandidate = null },
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
