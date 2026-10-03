package app.fittrack.domain

import app.fittrack.data.AppData
import app.fittrack.data.Exercise
import app.fittrack.data.LoadType
import app.fittrack.data.OneRmFormula
import app.fittrack.data.Workout
import app.fittrack.data.WorkoutExercise
import app.fittrack.data.WorkoutSet
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** Reine Rechen- und Formatierfunktionen (ohne Android), damit sie testbar sind. */
object Calc {

    // ---------- 1RM ----------

    /** Geschätztes 1-Wiederholungs-Maximum. */
    fun oneRm(loadKg: Double, reps: Int, formula: OneRmFormula): Double? {
        if (reps <= 0 || loadKg <= 0.0) return null
        if (reps == 1) return loadKg
        return when (formula) {
            OneRmFormula.EPLEY -> loadKg * (1.0 + reps / 30.0)
            OneRmFormula.BRZYCKI -> if (reps >= 37) null else loadKg * 36.0 / (37 - reps)
        }
    }

    /** Tatsächlich bewegte Last eines Satzes (Körpergewicht wird eingerechnet). */
    fun effectiveLoad(loadType: LoadType, set: WorkoutSet, bodyWeightKg: Double?): Double? = when (loadType) {
        LoadType.WEIGHTED -> set.weightKg
        LoadType.BODYWEIGHT -> bodyWeightKg
        LoadType.BODYWEIGHT_PLUS -> bodyWeightKg?.plus(set.weightKg)
    }

    /** Körpergewicht zum Datum: letzte Messung an/vor dem Datum, sonst die früheste Messung. */
    fun bodyWeightAt(data: AppData, date: String): Double? {
        if (data.bodyWeights.isEmpty()) return null
        val sorted = data.bodyWeights.sortedWith(compareBy({ it.date }, { it.time }))
        return (sorted.lastOrNull { it.date <= date } ?: sorted.first()).kg
    }

    data class SetResult(val set: WorkoutSet, val loadKg: Double?, val e1rm: Double?)

    data class EntryStats(
        val doneSets: List<SetResult>,
        val bestE1rm: Double?,
        val bestSet: SetResult?,
        val volumeKg: Double,
        val totalReps: Int,
        val maxReps: Int,
        val bodyWeightKg: Double?,
    )

    fun entryStats(data: AppData, workout: Workout, entry: WorkoutExercise): EntryStats {
        val ex = data.exercises.find { it.id == entry.exerciseId }
        val loadType = ex?.loadType ?: LoadType.WEIGHTED
        val bw = if (loadType == LoadType.WEIGHTED) null else bodyWeightAt(data, workout.date)
        val formula = data.settings.oneRmFormula
        val results = entry.sets.filter { it.done && it.reps > 0 }.map { s ->
            val load = effectiveLoad(loadType, s, bw)
            SetResult(s, load, load?.let { oneRm(it, s.reps, formula) })
        }
        val best = results.filter { it.e1rm != null }.maxByOrNull { it.e1rm!! }
        return EntryStats(
            doneSets = results,
            bestE1rm = best?.e1rm,
            bestSet = best,
            volumeKg = results.sumOf { (it.loadKg ?: 0.0) * it.set.reps },
            totalReps = results.sumOf { it.set.reps },
            maxReps = results.maxOfOrNull { it.set.reps } ?: 0,
            bodyWeightKg = bw,
        )
    }

    /** Eine vergangene Einheit einer Übung. */
    data class HistoryItem(val workout: Workout, val entry: WorkoutExercise, val stats: EntryStats)

    fun workoutSortKey(w: Workout) = w.date + "T" + w.startTime

    /** Alle Einheiten einer Übung mit mindestens einem erledigten Satz, neueste zuerst. */
    fun history(data: AppData, exerciseId: String): List<HistoryItem> =
        data.workouts.sortedByDescending { workoutSortKey(it) }.flatMap { w ->
            w.exercises.filter { it.exerciseId == exerciseId }.map { e -> HistoryItem(w, e, entryStats(data, w, e)) }
        }.filter { it.stats.doneSets.isNotEmpty() }

    /** Letzte Einheit dieser Übung vor dem gegebenen Training (für die Spalte "Vorher"). */
    fun previous(data: AppData, current: Workout, exerciseId: String): HistoryItem? {
        val key = workoutSortKey(current)
        return history(data, exerciseId).firstOrNull { it.workout.id != current.id && workoutSortKey(it.workout) < key }
            ?: history(data, exerciseId).firstOrNull { it.workout.id != current.id && workoutSortKey(it.workout) == key }
    }

    // ---------- Zeiten ----------

    val DATE: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val DATE_DE = DateTimeFormatter.ofPattern("EEE, dd.MM.yyyy", Locale.GERMAN)
    private val DATE_SHORT = DateTimeFormatter.ofPattern("dd.MM.", Locale.GERMAN)

    fun today(): String = LocalDate.now().format(DATE)
    fun nowTime(): String = LocalTime.now().format(TIME)

    fun parseDate(s: String): LocalDate? = runCatching { LocalDate.parse(s, DATE) }.getOrNull()
    fun parseTime(s: String?): LocalTime? = s?.let { runCatching { LocalTime.parse(it, TIME) }.getOrNull() }

    fun dateDe(s: String): String = parseDate(s)?.format(DATE_DE) ?: s
    fun dateShort(s: String): String = parseDate(s)?.format(DATE_SHORT) ?: s

    /** Minuten zwischen zwei Uhrzeiten; geht über Mitternacht. */
    fun minutesBetween(start: String, end: String?): Long? {
        val a = parseTime(start) ?: return null
        val b = parseTime(end) ?: return null
        var m = (b.toSecondOfDay() - a.toSecondOfDay()) / 60L
        if (m < 0) m += 24 * 60
        return m
    }

    fun addSeconds(time: String, seconds: Long): String =
        (parseTime(time) ?: LocalTime.MIDNIGHT).plusSeconds(seconds).format(TIME)

    fun daysSince(date: String): Long? =
        parseDate(date)?.let { java.time.temporal.ChronoUnit.DAYS.between(it, LocalDate.now()) }

    fun relativeDay(date: String): String = when (val d = daysSince(date)) {
        null -> date
        0L -> "heute"
        1L -> "gestern"
        else -> if (d > 0) "vor $d Tagen" else dateShort(date)
    }

    /** Montag der Woche des Datums. */
    fun weekStart(date: LocalDate = LocalDate.now()): LocalDate = date.minusDays((date.dayOfWeek.value - 1).toLong())

    // ---------- Formatierung ----------

    /** 82.5 -> "82,5"; 80.0 -> "80". */
    fun num(v: Double, decimals: Int = 1, comma: Boolean = true): String {
        val factor = Math.pow(10.0, decimals.toDouble())
        val r = (v * factor).roundToLong() / factor
        val s = if (abs(r - r.roundToLong()) < 1e-9) r.roundToLong().toString()
        else String.format(Locale.US, "%.${decimals}f", r).trimEnd('0').trimEnd('.')
        return if (comma) s.replace('.', ',') else s
    }

    fun kg(v: Double, comma: Boolean = true) = num(v, 2, comma) + " kg"

    /** Eingabe-Text für Zahlenfelder: 0 wird als leeres Feld angezeigt. */
    fun numInput(v: Double): String = if (v == 0.0) "" else num(v, 2, comma = true)

    fun parseNum(s: String): Double? = s.trim().replace(',', '.').let { if (it.isEmpty()) 0.0 else it.toDoubleOrNull() }

    /** Sekunden -> "1:05:30" bzw. "27:30". */
    fun duration(totalSec: Long): String {
        val s = if (totalSec < 0) 0 else totalSec
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.US, "%d:%02d", m, sec)
    }

    fun minutesText(min: Long): String = if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min"

    /** Pace in Sekunden pro km. */
    fun paceSecPerKm(durationSec: Double, distanceM: Double): Double? =
        if (distanceM < 1.0 || durationSec <= 0) null else durationSec / (distanceM / 1000.0)

    /** "5:17" (min:s pro km). */
    fun pace(secPerKm: Double?): String {
        if (secPerKm == null || secPerKm.isNaN() || secPerKm.isInfinite() || secPerKm > 3600) return "–:––"
        val total = secPerKm.roundToLong()
        return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
    }

    fun speedKmh(durationSec: Double, distanceM: Double): Double =
        if (durationSec <= 0) 0.0 else distanceM / 1000.0 / (durationSec / 3600.0)

    fun km(distanceM: Double, comma: Boolean = true): String = num(distanceM / 1000.0, 2, comma)

    /** Kurztext eines Satzes, z. B. "80×8", "KG×10", "KG+10×6". */
    fun setShort(loadType: LoadType, set: WorkoutSet): String = when (loadType) {
        LoadType.WEIGHTED -> "${num(set.weightKg, 2)}×${set.reps}"
        LoadType.BODYWEIGHT -> "KG×${set.reps}"
        LoadType.BODYWEIGHT_PLUS -> if (set.weightKg > 0) "KG+${num(set.weightKg, 2)}×${set.reps}" else "KG×${set.reps}"
    }

    fun loadTypeLabel(t: LoadType) = when (t) {
        LoadType.WEIGHTED -> "Gewicht"
        LoadType.BODYWEIGHT -> "Körpergewicht"
        LoadType.BODYWEIGHT_PLUS -> "Körpergewicht + Zusatz"
    }

    fun exerciseName(data: AppData, id: String): String =
        data.exercises.find { it.id == id }?.name ?: "(gelöschte Übung)"

    fun exercise(data: AppData, id: String): Exercise? = data.exercises.find { it.id == id }
}
