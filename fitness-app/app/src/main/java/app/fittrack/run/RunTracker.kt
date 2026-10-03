package app.fittrack.run

import android.location.Location
import android.os.SystemClock
import app.fittrack.data.Run
import app.fittrack.data.RunSource
import app.fittrack.data.Split
import app.fittrack.data.TrackPoint
import app.fittrack.data.newId
import app.fittrack.domain.Calc
import app.fittrack.domain.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** Zustand der laufenden GPS-Aufzeichnung (lebt im Prozess, gefüttert vom RunService). */
object RunTracker {

    enum class Status { IDLE, RUNNING, PAUSED }

    data class Live(
        val status: Status = Status.IDLE,
        val startWall: Long = 0,
        val activeMsBefore: Long = 0,
        val runningSince: Long? = null,
        val distanceM: Double = 0.0,
        val points: List<TrackPoint> = emptyList(),
        val segment: Int = 0,
        val splits: List<Split> = emptyList(),
        val currentPace: Double? = null,
        val accuracy: Float? = null,
        val lastSavedRunId: String? = null,
    ) {
        fun activeMs(now: Long = SystemClock.elapsedRealtime()): Long =
            activeMsBefore + (runningSince?.let { now - it } ?: 0L)

        val active get() = status != Status.IDLE
    }

    private const val MAX_ACCURACY_M = 30f
    private const val MAX_SPEED_MS = 12.0
    private const val MIN_MOVE_M = 3.0

    private val _live = MutableStateFlow(Live())
    val live: StateFlow<Live> = _live

    /** Wird bei jedem neuen vollen Kilometer aufgerufen (Sprachausgabe). */
    var onSplit: ((Split, Live) -> Unit)? = null

    fun start() {
        _live.value = Live(
            status = Status.RUNNING,
            startWall = System.currentTimeMillis(),
            runningSince = SystemClock.elapsedRealtime(),
        )
    }

    fun pause() = _live.update { l ->
        if (l.status != Status.RUNNING) l
        else l.copy(status = Status.PAUSED, activeMsBefore = l.activeMs(), runningSince = null, currentPace = null)
    }

    fun resume() = _live.update { l ->
        if (l.status != Status.PAUSED) l
        else l.copy(status = Status.RUNNING, runningSince = SystemClock.elapsedRealtime(), segment = l.segment + 1)
    }

    fun onLocation(loc: Location) {
        val l = _live.value
        _live.value = l.copy(accuracy = if (loc.hasAccuracy()) loc.accuracy else null)
        if (l.status != Status.RUNNING) return
        if (loc.hasAccuracy() && loc.accuracy > MAX_ACCURACY_M) return

        val p = TrackPoint(
            t = System.currentTimeMillis(),
            lat = loc.latitude,
            lon = loc.longitude,
            alt = if (loc.hasAltitude()) loc.altitude else null,
            seg = l.segment,
        )
        val last = l.points.lastOrNull()
        var add = 0.0
        if (last != null && last.seg == p.seg) {
            val d = Track.distance(last, p)
            val dt = (p.t - last.t) / 1000.0
            if (d < MIN_MOVE_M) return            // Stillstand / GPS-Zittern
            if (dt > 0 && d / dt > MAX_SPEED_MS) return  // Ausreißer
            add = d
        }
        val points = l.points + p
        val dist = l.distanceM + add
        var splits = l.splits
        var newSplit: Split? = null
        if ((dist / 1000).toInt() > l.splits.count { it.distanceM >= 999.0 }) {
            splits = Track.stats(points).splits.filter { it.distanceM >= 999.0 }
            newSplit = splits.lastOrNull()
        }
        val updated = _live.value.copy(points = points, distanceM = dist, splits = splits, currentPace = currentPace(points))
        _live.value = updated
        if (newSplit != null) onSplit?.invoke(newSplit, updated)
    }

    /** Pace der letzten ~30 Sekunden im aktuellen Segment. */
    private fun currentPace(points: List<TrackPoint>): Double? {
        val last = points.lastOrNull() ?: return null
        var dist = 0.0
        var i = points.size - 1
        while (i > 0) {
            val a = points[i - 1]
            val b = points[i]
            if (a.seg != last.seg || last.t - a.t > 30_000) break
            dist += Track.distance(a, b)
            i--
        }
        val first = points[i]
        val dt = (last.t - first.t) / 1000.0
        return if (dist < 20 || dt < 5) null else dt / (dist / 1000.0)
    }

    /** Beendet die Aufzeichnung und liefert Lauf + Strecke (noch nicht gespeichert). */
    fun finish(): Pair<Run, List<TrackPoint>>? {
        val l = _live.value
        if (!l.active) return null
        val activeSec = l.activeMs() / 1000
        val stats = Track.stats(l.points)
        val zone = ZoneId.systemDefault()
        val start = LocalDateTime.ofInstant(Instant.ofEpochMilli(l.startWall), zone)
        val run = Run(
            id = newId(),
            date = start.toLocalDate().format(Calc.DATE),
            startTime = start.toLocalTime().format(Calc.TIME),
            endTime = Calc.nowTime(),
            durationSec = activeSec,
            distanceM = stats.distanceM,
            elevationGainM = stats.elevationGainM,
            splits = stats.splits,
            source = RunSource.GPS,
        )
        _live.value = Live(lastSavedRunId = run.id)
        return run to l.points
    }

    fun consumeSavedRun() = _live.update { it.copy(lastSavedRunId = null) }
}
