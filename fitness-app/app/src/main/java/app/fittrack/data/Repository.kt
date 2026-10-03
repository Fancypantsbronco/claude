package app.fittrack.data

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import app.fittrack.domain.Export
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/**
 * Hält alle Daten im Speicher und schreibt sie bei jeder Änderung als JSON-Datei
 * (atomar) in den App-Speicher. GPS-Strecken liegen getrennt in tracks/<runId>.json.
 */
class Repository(context: Context) {

    private val dir = context.filesDir
    private val file = AtomicFile(File(dir, "fittrack.json"))
    private val trackDir = File(dir, "tracks").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val saveRequests = Channel<Unit>(Channel.CONFLATED)

    private val _data = MutableStateFlow(load())
    val data: StateFlow<AppData> = _data

    init {
        scope.launch {
            for (r in saveRequests) write(_data.value)
        }
    }

    private fun load(): AppData = try {
        if (!file.baseFile.exists()) AppData()
        else Export.json.decodeFromString(AppData.serializer(), String(file.readFully(), Charsets.UTF_8))
    } catch (e: Exception) {
        Log.e("FitTrack", "Daten konnten nicht gelesen werden", e)
        // kaputte Datei nicht überschreiben, sondern beiseitelegen
        runCatching { file.baseFile.copyTo(File(dir, "fittrack-defekt-${System.currentTimeMillis()}.json")) }
        AppData()
    }

    private fun write(d: AppData) {
        val out = file.startWrite()
        try {
            out.write(Export.json.encodeToString(AppData.serializer(), d).toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            Log.e("FitTrack", "Speichern fehlgeschlagen", e)
        }
    }

    val current: AppData get() = _data.value

    fun update(transform: (AppData) -> AppData) {
        _data.update(transform)
        saveRequests.trySend(Unit)
    }

    /** Ersetzt alle Daten (Import). */
    fun replaceAll(data: AppData, tracks: Map<String, List<TrackPoint>>) {
        tracks.forEach { (id, pts) -> saveTrack(id, pts) }
        update { data }
    }

    // ---------- GPS-Strecken ----------

    private val trackSerializer = ListSerializer(TrackPoint.serializer())

    fun saveTrack(runId: String, points: List<TrackPoint>) {
        runCatching {
            val f = AtomicFile(File(trackDir, "$runId.json"))
            val out = f.startWrite()
            out.write(Export.json.encodeToString(trackSerializer, points).toByteArray(Charsets.UTF_8))
            f.finishWrite(out)
        }.onFailure { Log.e("FitTrack", "Strecke nicht gespeichert", it) }
    }

    fun loadTrack(runId: String): List<TrackPoint> = runCatching {
        val f = File(trackDir, "$runId.json")
        if (!f.exists()) emptyList()
        else Export.json.decodeFromString(trackSerializer, f.readText(Charsets.UTF_8))
    }.getOrDefault(emptyList())

    fun deleteTrack(runId: String) {
        File(trackDir, "$runId.json").delete()
    }

    fun allTracks(): Map<String, List<TrackPoint>> =
        current.runs.associate { it.id to loadTrack(it.id) }.filterValues { it.isNotEmpty() }

    // ---------- Gym ----------

    fun upsertWorkout(w: Workout) = update { d ->
        val exists = d.workouts.any { it.id == w.id }
        d.copy(workouts = if (exists) d.workouts.map { if (it.id == w.id) w else it } else d.workouts + w)
    }

    fun updateWorkout(id: String, f: (Workout) -> Workout) = update { d ->
        d.copy(workouts = d.workouts.map { if (it.id == id) f(it) else it })
    }

    fun deleteWorkout(id: String) = update { d -> d.copy(workouts = d.workouts.filterNot { it.id == id }) }

    fun upsertExercise(e: Exercise) = update { d ->
        val exists = d.exercises.any { it.id == e.id }
        d.copy(exercises = if (exists) d.exercises.map { if (it.id == e.id) e else it } else d.exercises + e)
    }

    fun deleteExercise(id: String) = update { d -> d.copy(exercises = d.exercises.filterNot { it.id == id }) }

    fun addStarterExercises() = update { d ->
        val existing = d.exercises.map { it.name.lowercase() }.toSet()
        d.copy(exercises = d.exercises + starterExercises().filter { it.name.lowercase() !in existing })
    }

    // ---------- Laufen ----------

    fun upsertRun(r: Run) = update { d ->
        val exists = d.runs.any { it.id == r.id }
        d.copy(runs = if (exists) d.runs.map { if (it.id == r.id) r else it } else d.runs + r)
    }

    fun deleteRun(id: String) {
        deleteTrack(id)
        update { d -> d.copy(runs = d.runs.filterNot { it.id == id }) }
    }

    // ---------- Körpergewicht ----------

    fun upsertBodyWeight(b: BodyWeight) = update { d ->
        val exists = d.bodyWeights.any { it.id == b.id }
        d.copy(bodyWeights = if (exists) d.bodyWeights.map { if (it.id == b.id) b else it } else d.bodyWeights + b)
    }

    fun deleteBodyWeight(id: String) = update { d -> d.copy(bodyWeights = d.bodyWeights.filterNot { it.id == id }) }

    // ---------- Bonus-Aktivitäten ----------

    fun upsertActivity(a: Activity) = update { d ->
        val exists = d.activities.any { it.id == a.id }
        val types = if (a.type.isNotBlank() && a.type !in d.settings.activityTypes) d.settings.activityTypes + a.type
        else d.settings.activityTypes
        d.copy(
            activities = if (exists) d.activities.map { if (it.id == a.id) a else it } else d.activities + a,
            settings = d.settings.copy(activityTypes = types),
        )
    }

    fun deleteActivity(id: String) = update { d -> d.copy(activities = d.activities.filterNot { it.id == id }) }

    // ---------- Einstellungen ----------

    fun updateSettings(f: (Settings) -> Settings) = update { d -> d.copy(settings = f(d.settings)) }
}
