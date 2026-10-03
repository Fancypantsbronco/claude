package app.fittrack.domain

import app.fittrack.data.AppData
import app.fittrack.data.Backup
import app.fittrack.data.LoadType
import app.fittrack.data.OneRmFormula
import app.fittrack.data.RunSource
import app.fittrack.data.TrackPoint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Exporte für KIs (Text und JSON, mit Klarnamen und berechneten Kennzahlen)
 * sowie das Backup zum Wiederherstellen.
 */
object Export {

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }
    private val pretty = Json(json) { prettyPrint = true }

    /** Nur Daten ab diesem Datum (yyyy-MM-dd); null = alles. */
    private fun AppData.since(from: String?): AppData = if (from == null) this else copy(
        workouts = workouts.filter { it.date >= from },
        runs = runs.filter { it.date >= from },
        bodyWeights = bodyWeights.filter { it.date >= from },
        activities = activities.filter { it.date >= from },
    )

    private fun formulaText(f: OneRmFormula) = when (f) {
        OneRmFormula.EPLEY -> "Epley: Last × (1 + Wdh/30)"
        OneRmFormula.BRZYCKI -> "Brzycki: Last × 36 / (37 − Wdh)"
    }

    private fun feelingText(f: Int?) = when (f) {
        1 -> "sehr schlecht"; 2 -> "schlecht"; 3 -> "okay"; 4 -> "gut"; 5 -> "super"; else -> null
    }

    // ------------------------------------------------------------------ Text

    fun text(all: AppData, from: String? = null, createdAt: String): String {
        val data = all.since(from)
        val sb = StringBuilder()
        fun line(s: String = "") = sb.append(s).append('\n')
        val n = { v: Double -> Calc.num(v, 2, comma = false) }

        line("FITNESS-EXPORT (FitTrack) – erstellt $createdAt")
        line("Zeitraum: " + (from?.let { "ab $it" } ?: "alle Daten"))
        line("Einheiten: Gewicht in kg, Distanz in km, Pace in min:s pro km, Zeiten im 24-h-Format.")
        line("e1RM = geschätztes 1-Wiederholungs-Maximum (${formulaText(all.settings.oneRmFormula)}).")
        line("Bei Körpergewichtsübungen ist die Last = Körpergewicht (+ Zusatzgewicht). KG = Körpergewicht.")
        line("Gezählt werden nur als erledigt abgehakte Sätze.")
        line()

        val runKm = data.runs.sumOf { it.distanceM } / 1000
        val lastBw = all.bodyWeights.maxWithOrNull(compareBy({ it.date }, { it.time }))
        line("=== ÜBERSICHT ===")
        line("Krafttrainings: ${data.workouts.size}")
        line("Läufe: ${data.runs.size} (gesamt ${Calc.num(runKm, 1, false)} km)")
        line("Bonus-Aktivitäten: ${data.activities.size}")
        line("Körpergewicht aktuell: " + (lastBw?.let { "${n(it.kg)} kg (${it.date})" } ?: "keine Messung"))
        line()

        line("=== ÜBUNGSDATENBANK ===")
        all.exercises.sortedBy { it.name.lowercase() }.forEach { e ->
            val extra = buildList {
                if (e.muscleGroup.isNotBlank()) add(e.muscleGroup)
                add(Calc.loadTypeLabel(e.loadType))
                add("Pause ${e.defaultRestSec} s")
                add("Standard ${e.defaultSets}×${e.defaultReps}")
            }.joinToString(", ")
            line("- ${e.name} ($extra)" + if (e.notes.isNotBlank()) " – Notiz: ${e.notes.oneLine()}" else "")
        }
        line()

        line("=== KRAFTTRAINING ===")
        if (data.workouts.isEmpty()) line("(keine Einträge)")
        data.workouts.sortedBy { Calc.workoutSortKey(it) }.forEach { w ->
            val dur = Calc.minutesBetween(w.startTime, w.endTime)
            line("## ${w.date} ${w.startTime}–${w.endTime ?: "läuft"}" +
                (dur?.let { " ($it min)" } ?: "") + " – ${w.title}")
            if (w.notes.isNotBlank()) line("Notiz: ${w.notes.oneLine()}")
            w.exercises.forEach { e ->
                val ex = Calc.exercise(all, e.exerciseId)
                val type = ex?.loadType ?: LoadType.WEIGHTED
                val st = Calc.entryStats(all, w, e)
                val head = StringBuilder("* ${Calc.exerciseName(all, e.exerciseId)} [Pause ${e.restSec} s]")
                if (type != LoadType.WEIGHTED) head.append(" [KG ${st.bodyWeightKg?.let { n(it) + " kg" } ?: "unbekannt"}]")
                st.bestE1rm?.let { head.append(" – bestes e1RM ${n(it)} kg") }
                if (st.volumeKg > 0) head.append(", Volumen ${Calc.num(st.volumeKg, 0, false)} kg")
                line(head.toString())
                val sets = st.doneSets.mapIndexed { i, r ->
                    "${i + 1}) " + Calc.setShort(type, r.set).replace(',', '.') +
                        (r.e1rm?.let { " (e1RM ${n(it)})" } ?: "")
                }
                line("  " + if (sets.isEmpty()) "keine erledigten Sätze" else sets.joinToString(" | "))
                if (e.notes.isNotBlank()) line("  Notiz: ${e.notes.oneLine()}")
            }
            line()
        }

        line("=== FORTSCHRITT PRO ÜBUNG (bestes e1RM je Einheit, chronologisch) ===")
        all.exercises.sortedBy { it.name.lowercase() }.forEach { ex ->
            val hist = Calc.history(all, ex.id).filter { from == null || it.workout.date >= from }.reversed()
            if (hist.isEmpty()) return@forEach
            val withRm = hist.filter { it.stats.bestE1rm != null }
            if (withRm.isNotEmpty()) {
                val first = withRm.first().stats.bestE1rm!!
                val last = withRm.last().stats.bestE1rm!!
                val best = withRm.maxBy { it.stats.bestE1rm!! }
                val diff = last - first
                val pct = if (first > 0) diff / first * 100 else 0.0
                line("${ex.name}: ${n(first)} → ${n(last)} kg (${if (diff >= 0) "+" else ""}${n(diff)} kg, " +
                    "${if (pct >= 0) "+" else ""}${Calc.num(pct, 1, false)} %), Bestwert ${n(best.stats.bestE1rm!!)} kg am ${best.workout.date}, ${hist.size} Einheiten")
                line("  Verlauf: " + withRm.joinToString("; ") { "${it.workout.date} ${n(it.stats.bestE1rm!!)}" })
            } else {
                line("${ex.name}: max. Wiederholungen je Einheit: " + hist.joinToString("; ") { "${it.workout.date} ${it.stats.maxReps}" })
            }
        }
        line()

        line("=== LAUFTRAINING ===")
        if (data.runs.isEmpty()) line("(keine Einträge)")
        data.runs.sortedBy { it.date + it.startTime }.forEach { r ->
            val pace = Calc.paceSecPerKm(r.durationSec.toDouble(), r.distanceM)
            line("## ${r.date} ${r.startTime}–${r.endTime ?: "?"} – ${Calc.km(r.distanceM, false)} km in ${Calc.duration(r.durationSec)}" +
                " – Ø Pace ${Calc.pace(pace)} /km – Ø ${Calc.num(Calc.speedKmh(r.durationSec.toDouble(), r.distanceM), 1, false)} km/h" +
                (if (r.elevationGainM > 0) " – ${Calc.num(r.elevationGainM, 0, false)} Hm" else "") +
                (if (r.source == RunSource.GPS) " – GPS" else " – manuell"))
            if (r.splits.isNotEmpty()) {
                line("Km-Splits: " + r.splits.joinToString(" | ") { s ->
                    val p = Calc.pace(Calc.paceSecPerKm(s.durationSec, s.distanceM))
                    if (s.distanceM < 999) "${s.km} (${Calc.num(s.distanceM, 0, false)} m): $p" else "${s.km}: $p"
                })
            }
            feelingText(r.feeling)?.let { line("Gefühl: $it (${r.feeling}/5)") }
            if (r.notes.isNotBlank()) line("Notiz: ${r.notes.oneLine()}")
            line()
        }

        line("=== KÖRPERGEWICHT ===")
        if (data.bodyWeights.isEmpty()) line("(keine Einträge)")
        data.bodyWeights.sortedWith(compareBy({ it.date }, { it.time })).forEach { b ->
            line("${b.date} ${b.time}: ${n(b.kg)} kg" + if (b.note.isNotBlank()) " – ${b.note.oneLine()}" else "")
        }
        line()

        line("=== BONUS-AKTIVITÄTEN (außerhalb von Gym und Laufen) ===")
        if (data.activities.isEmpty()) line("(keine Einträge)")
        data.activities.sortedBy { it.date + it.startTime }.forEach { a ->
            val dur = Calc.minutesBetween(a.startTime, a.endTime)
            line("${a.date} ${a.startTime}" + (a.endTime?.let { "–$it" } ?: "") + (dur?.let { " ($it min)" } ?: "") +
                " ${a.type}" + (a.amount?.let { " – ${n(it)} ${a.unit}" } ?: "") +
                if (a.notes.isNotBlank()) " – ${a.notes.oneLine()}" else "")
        }
        return sb.toString()
    }

    private fun String.oneLine() = replace("\r", "").replace('\n', ' ').trim()

    // ------------------------------------------------------------------ JSON für KIs

    fun aiJson(all: AppData, from: String? = null, createdAt: String): String {
        val data = all.since(from)
        val r2 = { v: Double -> Math.round(v * 100) / 100.0 }
        val obj = buildJsonObject {
            putJsonObject("meta") {
                put("app", "FitTrack")
                put("createdAt", createdAt)
                put("range", from?.let { "ab $it" } ?: "alle Daten")
                put("units", "Gewicht kg, Distanz km/m, Dauer Sekunden bzw. Minuten, Pace min:s pro km")
                put("e1rmFormula", formulaText(all.settings.oneRmFormula))
                put("note", "Nur erledigte Sätze sind enthalten. Bei Körpergewichtsübungen: effectiveLoadKg = Körpergewicht + Zusatzgewicht.")
            }
            putJsonArray("exercises") {
                all.exercises.sortedBy { it.name.lowercase() }.forEach { e ->
                    addJsonObject {
                        put("name", e.name)
                        put("muscleGroup", e.muscleGroup)
                        put("loadType", Calc.loadTypeLabel(e.loadType))
                        put("defaultRestSec", e.defaultRestSec)
                        put("defaultSets", e.defaultSets)
                        put("defaultReps", e.defaultReps)
                        if (e.notes.isNotBlank()) put("notes", e.notes)
                    }
                }
            }
            putJsonArray("strengthWorkouts") {
                data.workouts.sortedBy { Calc.workoutSortKey(it) }.forEach { w ->
                    addJsonObject {
                        put("date", w.date)
                        put("start", w.startTime)
                        put("end", w.endTime)
                        put("durationMin", Calc.minutesBetween(w.startTime, w.endTime))
                        put("title", w.title)
                        if (w.notes.isNotBlank()) put("notes", w.notes)
                        putJsonArray("exercises") {
                            w.exercises.forEach { e ->
                                val type = Calc.exercise(all, e.exerciseId)?.loadType ?: LoadType.WEIGHTED
                                val st = Calc.entryStats(all, w, e)
                                addJsonObject {
                                    put("name", Calc.exerciseName(all, e.exerciseId))
                                    put("loadType", Calc.loadTypeLabel(type))
                                    put("restSec", e.restSec)
                                    st.bodyWeightKg?.let { put("bodyWeightKg", r2(it)) }
                                    putJsonArray("sets") {
                                        st.doneSets.forEachIndexed { i, s ->
                                            addJsonObject {
                                                put("set", i + 1)
                                                put("reps", s.set.reps)
                                                when (type) {
                                                    LoadType.WEIGHTED -> put("weightKg", r2(s.set.weightKg))
                                                    LoadType.BODYWEIGHT -> {}
                                                    LoadType.BODYWEIGHT_PLUS -> put("extraWeightKg", r2(s.set.weightKg))
                                                }
                                                s.loadKg?.let { put("effectiveLoadKg", r2(it)) }
                                                s.e1rm?.let { put("e1rmKg", r2(it)) }
                                            }
                                        }
                                    }
                                    st.bestE1rm?.let { put("bestE1rmKg", r2(it)) }
                                    put("volumeKg", r2(st.volumeKg))
                                    if (e.notes.isNotBlank()) put("notes", e.notes)
                                }
                            }
                        }
                    }
                }
            }
            putJsonArray("exerciseProgress") {
                all.exercises.sortedBy { it.name.lowercase() }.forEach { ex ->
                    val hist = Calc.history(all, ex.id).filter { from == null || it.workout.date >= from }.reversed()
                    if (hist.isEmpty()) return@forEach
                    addJsonObject {
                        put("name", ex.name)
                        put("sessions", hist.size)
                        hist.mapNotNull { it.stats.bestE1rm }.maxOrNull()?.let { put("bestE1rmKg", r2(it)) }
                        putJsonArray("history") {
                            hist.forEach { h ->
                                addJsonObject {
                                    put("date", h.workout.date)
                                    h.stats.bestE1rm?.let { put("bestE1rmKg", r2(it)) }
                                    put("maxReps", h.stats.maxReps)
                                    put("totalReps", h.stats.totalReps)
                                }
                            }
                        }
                    }
                }
            }
            putJsonArray("runs") {
                data.runs.sortedBy { it.date + it.startTime }.forEach { r ->
                    addJsonObject {
                        put("date", r.date)
                        put("start", r.startTime)
                        put("end", r.endTime)
                        put("distanceKm", r2(r.distanceM / 1000))
                        put("durationSec", r.durationSec)
                        put("duration", Calc.duration(r.durationSec))
                        put("avgPacePerKm", Calc.pace(Calc.paceSecPerKm(r.durationSec.toDouble(), r.distanceM)))
                        put("avgSpeedKmh", r2(Calc.speedKmh(r.durationSec.toDouble(), r.distanceM)))
                        put("elevationGainM", Math.round(r.elevationGainM))
                        put("source", if (r.source == RunSource.GPS) "GPS" else "manuell")
                        putJsonArray("kmSplits") {
                            r.splits.forEach { s ->
                                addJsonObject {
                                    put("km", s.km)
                                    put("distanceM", Math.round(s.distanceM))
                                    put("timeSec", r2(s.durationSec))
                                    put("pacePerKm", Calc.pace(Calc.paceSecPerKm(s.durationSec, s.distanceM)))
                                }
                            }
                        }
                        feelingText(r.feeling)?.let { put("feeling", "$it (${r.feeling}/5)") }
                        if (r.notes.isNotBlank()) put("notes", r.notes)
                    }
                }
            }
            putJsonArray("bodyWeight") {
                data.bodyWeights.sortedWith(compareBy({ it.date }, { it.time })).forEach { b ->
                    addJsonObject {
                        put("date", b.date)
                        put("time", b.time)
                        put("kg", r2(b.kg))
                        if (b.note.isNotBlank()) put("note", b.note)
                    }
                }
            }
            putJsonArray("bonusActivities") {
                data.activities.sortedBy { it.date + it.startTime }.forEach { a ->
                    addJsonObject {
                        put("date", a.date)
                        put("type", a.type)
                        put("start", a.startTime)
                        put("end", a.endTime)
                        put("durationMin", Calc.minutesBetween(a.startTime, a.endTime))
                        a.amount?.let { put("amount", r2(it)); put("unit", a.unit) }
                        if (a.notes.isNotBlank()) put("notes", a.notes)
                    }
                }
            }
        }
        return pretty.encodeToString(JsonObject.serializer(), obj)
    }

    // ------------------------------------------------------------------ Backup

    fun backup(data: AppData, tracks: Map<String, List<TrackPoint>>, createdAt: String): String =
        json.encodeToString(Backup.serializer(), Backup(exportedAt = createdAt, data = data, tracks = tracks))

    /** Liest ein Backup; akzeptiert auch eine reine AppData-Datei. */
    fun parseBackup(text: String): Backup {
        val element = json.parseToJsonElement(text)
        return if (element is JsonObject && "data" in element) json.decodeFromJsonElement(Backup.serializer(), element)
        else Backup(data = json.decodeFromJsonElement(AppData.serializer(), element))
    }
}
