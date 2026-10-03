package app.fittrack.domain

import app.fittrack.data.Split
import app.fittrack.data.TrackPoint
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Auswertung von GPS-Strecken: Distanz, Kilometer-Splits, Höhenmeter. */
object Track {

    private const val EARTH_RADIUS_M = 6_371_000.0

    fun distance(a: TrackPoint, b: TrackPoint): Double = haversine(a.lat, a.lon, b.lat, b.lon)

    fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(h), sqrt(1 - h))
    }

    data class Stats(
        val distanceM: Double,
        val movingSec: Double,
        val splits: List<Split>,
        val elevationGainM: Double,
    )

    /**
     * Rechnet Distanz und Kilometer-Splits. Zwischen zwei Segmenten (Pause) wird weder
     * Strecke noch Zeit gezählt. Der Zeitpunkt eines vollen Kilometers wird linear interpoliert.
     */
    fun stats(points: List<TrackPoint>): Stats {
        var dist = 0.0
        var time = 0.0
        val splits = mutableListOf<Split>()
        var lastSplitDist = 0.0
        var lastSplitTime = 0.0
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            if (a.seg != b.seg) continue
            val d = distance(a, b)
            val dt = (b.t - a.t) / 1000.0
            if (dt <= 0) continue
            var segDist = d
            var segTime = dt
            // so viele volle Kilometer, wie in diesem Stück überschritten werden
            while (dist + segDist >= (splits.size + 1) * 1000.0) {
                val target = (splits.size + 1) * 1000.0
                val need = target - dist
                val frac = if (segDist > 0) need / segDist else 0.0
                val tAt = time + segTime * frac
                splits += Split(splits.size + 1, target - lastSplitDist, tAt - lastSplitTime)
                lastSplitDist = target
                lastSplitTime = tAt
                dist = target
                time = tAt
                segDist -= need
                segTime -= segTime * frac
            }
            dist += segDist
            time += segTime
        }
        if (dist - lastSplitDist >= 10.0) {
            splits += Split(splits.size + 1, dist - lastSplitDist, time - lastSplitTime)
        }
        return Stats(dist, time, splits, elevationGain(points))
    }

    /** Höhenmeter bergauf mit 3 m Hysterese gegen GPS-Rauschen. */
    fun elevationGain(points: List<TrackPoint>): Double {
        var gain = 0.0
        var anchor: Double? = null
        var seg = -1
        for (p in points) {
            val alt = p.alt ?: continue
            if (p.seg != seg) { seg = p.seg; anchor = alt; continue }
            val a = anchor ?: alt
            when {
                alt - a >= 3.0 -> { gain += alt - a; anchor = alt }
                a - alt >= 3.0 -> anchor = alt
            }
        }
        return gain
    }

    /** Splits für einen manuell eingetragenen Lauf (gleichmäßige Pace). */
    fun evenSplits(distanceM: Double, durationSec: Long): List<Split> {
        if (distanceM < 10 || durationSec <= 0) return emptyList()
        val secPerM = durationSec / distanceM
        val full = (distanceM / 1000).toInt()
        val list = (1..full).map { Split(it, 1000.0, 1000.0 * secPerM) }.toMutableList()
        val rest = distanceM - full * 1000
        if (rest >= 10) list += Split(full + 1, rest, rest * secPerM)
        return list
    }
}
