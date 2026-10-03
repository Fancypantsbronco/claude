package app.fittrack.data

import kotlinx.serialization.Serializable
import java.util.UUID

/** Wie das Gewicht einer Übung gezählt wird. */
@Serializable
enum class LoadType { WEIGHTED, BODYWEIGHT, BODYWEIGHT_PLUS }

@Serializable
data class Exercise(
    val id: String,
    val name: String,
    val muscleGroup: String = "",
    val loadType: LoadType = LoadType.WEIGHTED,
    val defaultRestSec: Int = 90,
    val defaultSets: Int = 3,
    val defaultReps: Int = 10,
    val defaultWeightKg: Double = 0.0,
    val notes: String = "",
)

/** Ein Satz. Bei BODYWEIGHT_PLUS ist weightKg das Zusatzgewicht, bei BODYWEIGHT wird es ignoriert. */
@Serializable
data class WorkoutSet(
    val id: String,
    val reps: Int = 0,
    val weightKg: Double = 0.0,
    val done: Boolean = false,
)

@Serializable
data class WorkoutExercise(
    val id: String,
    val exerciseId: String,
    val restSec: Int = 90,
    val notes: String = "",
    val sets: List<WorkoutSet> = emptyList(),
)

@Serializable
data class Workout(
    val id: String,
    val title: String = "Training",
    /** yyyy-MM-dd */
    val date: String,
    /** HH:mm */
    val startTime: String,
    /** HH:mm, null solange das Training läuft */
    val endTime: String? = null,
    val notes: String = "",
    val exercises: List<WorkoutExercise> = emptyList(),
)

/** Ein Kilometer-Abschnitt. Der letzte Abschnitt kann kürzer als 1000 m sein. */
@Serializable
data class Split(val km: Int, val distanceM: Double, val durationSec: Double)

@Serializable
enum class RunSource { GPS, MANUAL }

@Serializable
data class Run(
    val id: String,
    val date: String,
    val startTime: String,
    val endTime: String? = null,
    /** Bewegungszeit ohne Pausen */
    val durationSec: Long = 0,
    val distanceM: Double = 0.0,
    val elevationGainM: Double = 0.0,
    val splits: List<Split> = emptyList(),
    val source: RunSource = RunSource.MANUAL,
    /** 1 (schlecht) bis 5 (super) */
    val feeling: Int? = null,
    val notes: String = "",
)

/** GPS-Punkt; seg wechselt nach jeder Pause, damit Pausen nicht als Strecke zählen. */
@Serializable
data class TrackPoint(
    val t: Long,
    val lat: Double,
    val lon: Double,
    val alt: Double? = null,
    val seg: Int = 0,
)

@Serializable
data class BodyWeight(
    val id: String,
    val date: String,
    val time: String,
    val kg: Double,
    val note: String = "",
)

/** Bonus-Aktivität außerhalb von Gym und Laufen (Liegestütze, Schwimmen, ...). */
@Serializable
data class Activity(
    val id: String,
    val type: String,
    val date: String,
    val startTime: String,
    val endTime: String? = null,
    val amount: Double? = null,
    val unit: String = "",
    val notes: String = "",
)

@Serializable
enum class OneRmFormula { EPLEY, BRZYCKI }

@Serializable
data class Settings(
    val oneRmFormula: OneRmFormula = OneRmFormula.EPLEY,
    val weightReminder: Boolean = true,
    /** ISO-Wochentag: 1 = Montag ... 7 = Sonntag */
    val weightReminderDay: Int = 7,
    val weightReminderTime: String = "09:00",
    val bonusReminder: Boolean = true,
    val bonusReminderTime: String = "18:00",
    val bonusWeeklyGoal: Int = 2,
    val voiceFeedback: Boolean = true,
    val activityTypes: List<String> = DEFAULT_ACTIVITY_TYPES,
)

@Serializable
data class AppData(
    val version: Int = 1,
    val exercises: List<Exercise> = emptyList(),
    val workouts: List<Workout> = emptyList(),
    val runs: List<Run> = emptyList(),
    val bodyWeights: List<BodyWeight> = emptyList(),
    val activities: List<Activity> = emptyList(),
    val settings: Settings = Settings(),
)

/** Backup-Datei: alle Daten plus (optional) die GPS-Strecken. */
@Serializable
data class Backup(
    val app: String = "FitTrack",
    val exportedAt: String = "",
    val data: AppData = AppData(),
    val tracks: Map<String, List<TrackPoint>> = emptyMap(),
)

val DEFAULT_ACTIVITY_TYPES = listOf(
    "Liegestütze", "Klimmzüge", "Schwimmen", "Radfahren", "Wandern",
    "Yoga", "Dehnen", "Plank", "Seilspringen", "Treppensteigen",
)

val ACTIVITY_UNITS = listOf("Wdh", "min", "km", "m", "s")

fun defaultUnitFor(type: String): String = when (type.lowercase()) {
    "liegestütze", "klimmzüge", "dips", "kniebeugen", "sit-ups", "burpees" -> "Wdh"
    "schwimmen" -> "m"
    "radfahren", "wandern", "spazieren" -> "km"
    "plank" -> "s"
    else -> "min"
}

val MUSCLE_GROUPS = listOf("Brust", "Rücken", "Beine", "Schultern", "Bizeps", "Trizeps", "Bauch", "Ganzkörper")

fun newId(): String = UUID.randomUUID().toString()

/** Sinnvolle Start-Übungen, per Knopfdruck in die Datenbank übernehmbar. */
fun starterExercises(): List<Exercise> = listOf(
    Exercise(newId(), "Bankdrücken", "Brust", LoadType.WEIGHTED, 120, 3, 8, 60.0),
    Exercise(newId(), "Schrägbankdrücken (Kurzhantel)", "Brust", LoadType.WEIGHTED, 90, 3, 10, 22.0),
    Exercise(newId(), "Dips", "Trizeps", LoadType.BODYWEIGHT_PLUS, 120, 3, 10),
    Exercise(newId(), "Liegestütze", "Brust", LoadType.BODYWEIGHT, 60, 3, 15),
    Exercise(newId(), "Klimmzüge", "Rücken", LoadType.BODYWEIGHT_PLUS, 120, 3, 8),
    Exercise(newId(), "Langhantelrudern", "Rücken", LoadType.WEIGHTED, 120, 3, 8, 50.0),
    Exercise(newId(), "Latzug", "Rücken", LoadType.WEIGHTED, 90, 3, 10, 50.0),
    Exercise(newId(), "Kniebeugen", "Beine", LoadType.WEIGHTED, 180, 3, 6, 80.0),
    Exercise(newId(), "Kreuzheben", "Rücken", LoadType.WEIGHTED, 180, 3, 5, 100.0),
    Exercise(newId(), "Beinpresse", "Beine", LoadType.WEIGHTED, 120, 3, 10, 120.0),
    Exercise(newId(), "Schulterdrücken", "Schultern", LoadType.WEIGHTED, 120, 3, 8, 40.0),
    Exercise(newId(), "Seitheben", "Schultern", LoadType.WEIGHTED, 60, 3, 12, 8.0),
    Exercise(newId(), "Bizepscurls", "Bizeps", LoadType.WEIGHTED, 60, 3, 10, 12.0),
    Exercise(newId(), "Trizepsdrücken am Kabel", "Trizeps", LoadType.WEIGHTED, 60, 3, 12, 25.0),
    Exercise(newId(), "Hängendes Beinheben", "Bauch", LoadType.BODYWEIGHT, 60, 3, 12),
)
