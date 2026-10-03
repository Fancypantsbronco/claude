package app.fittrack

import app.fittrack.data.AppData
import app.fittrack.data.BodyWeight
import app.fittrack.data.Exercise
import app.fittrack.data.LoadType
import app.fittrack.data.OneRmFormula
import app.fittrack.data.Run
import app.fittrack.data.RunSource
import app.fittrack.data.TrackPoint
import app.fittrack.data.Workout
import app.fittrack.data.WorkoutExercise
import app.fittrack.data.WorkoutSet
import app.fittrack.domain.Calc
import app.fittrack.domain.Export
import app.fittrack.domain.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainTest {

    @Test
    fun oneRmEpley() {
        assertEquals(100.0, Calc.oneRm(100.0, 1, OneRmFormula.EPLEY)!!, 1e-9)
        assertEquals(80.0 * (1 + 8 / 30.0), Calc.oneRm(80.0, 8, OneRmFormula.EPLEY)!!, 1e-9)
        assertEquals(80.0 * 36 / 29, Calc.oneRm(80.0, 8, OneRmFormula.BRZYCKI)!!, 1e-9)
        assertNull(Calc.oneRm(0.0, 8, OneRmFormula.EPLEY))
        assertNull(Calc.oneRm(80.0, 0, OneRmFormula.EPLEY))
    }

    private fun sampleData(): AppData {
        val bench = Exercise("b", "Bankdrücken", "Brust", LoadType.WEIGHTED, 120)
        val pull = Exercise("p", "Klimmzüge", "Rücken", LoadType.BODYWEIGHT_PLUS, 120)
        val w1 = Workout(
            "w1", "Push", "2026-09-01", "18:00", "19:10", exercises = listOf(
                WorkoutExercise("e1", "b", 120, sets = listOf(
                    WorkoutSet("s1", 8, 80.0, true), WorkoutSet("s2", 6, 85.0, true), WorkoutSet("s3", 10, 90.0, false),
                )),
                WorkoutExercise("e2", "p", 120, sets = listOf(WorkoutSet("s4", 6, 10.0, true))),
            )
        )
        val w2 = Workout(
            "w2", "Push", "2026-09-05", "18:00", null, exercises = listOf(
                WorkoutExercise("e3", "b", 120, sets = listOf(WorkoutSet("s5", 8, 82.5, true))),
            )
        )
        return AppData(
            exercises = listOf(bench, pull),
            workouts = listOf(w1, w2),
            bodyWeights = listOf(BodyWeight("bw1", "2026-08-30", "07:00", 80.0), BodyWeight("bw2", "2026-09-04", "07:00", 81.0)),
            runs = listOf(Run("r1", "2026-09-02", "07:00", "07:30", 1650, 5210.0, splits = Track.evenSplits(5210.0, 1650), source = RunSource.MANUAL)),
        )
    }

    @Test
    fun entryStatsCountsOnlyDoneSetsAndUsesBodyWeight() {
        val d = sampleData()
        val w1 = d.workouts[0]
        val bench = Calc.entryStats(d, w1, w1.exercises[0])
        assertEquals(2, bench.doneSets.size)
        // 85 x 6 = 102.0 > 80 x 8 = 101.33
        assertEquals(85.0 * (1 + 6 / 30.0), bench.bestE1rm!!, 1e-9)
        val pull = Calc.entryStats(d, w1, w1.exercises[1])
        assertEquals(80.0, pull.bodyWeightKg!!, 1e-9) // Messung vom 30.08.
        assertEquals(90.0 * 1.2, pull.bestE1rm!!, 1e-9)
    }

    @Test
    fun previousFindsLastSession() {
        val d = sampleData()
        val prev = Calc.previous(d, d.workouts[1], "b")
        assertNotNull(prev)
        assertEquals("w1", prev!!.workout.id)
        assertNull(Calc.previous(d, d.workouts[0], "b"))
        assertEquals(2, Calc.history(d, "b").size)
    }

    @Test
    fun timesAndFormatting() {
        assertEquals(70L, Calc.minutesBetween("18:00", "19:10"))
        assertEquals(30L, Calc.minutesBetween("23:45", "00:15"))
        assertEquals("82,5", Calc.num(82.5))
        assertEquals("80", Calc.num(80.0))
        assertEquals("5:17", Calc.pace(317.4))
        assertEquals("1:05:30", Calc.duration(3930))
        assertEquals("27:30", Calc.duration(1650))
        assertEquals(82.5, Calc.parseNum("82,5")!!, 1e-9)
        assertEquals(0.0, Calc.parseNum("")!!, 1e-9)
        assertEquals("KG+10×6", Calc.setShort(LoadType.BODYWEIGHT_PLUS, WorkoutSet("x", 6, 10.0)))
    }

    @Test
    fun trackSplits() {
        // Gerade Strecke nach Norden: 0.001° Breite ≈ 111.2 m, alle 30 s ein Punkt
        val pts = (0..30).map { TrackPoint(it * 30_000L, 48.0 + it * 0.001, 11.0, 500.0 + if (it % 2 == 0) 0 else 1, 0) }
        val st = Track.stats(pts)
        assertEquals(3335.8, st.distanceM, 5.0)
        assertEquals(4, st.splits.size)
        assertEquals(1000.0, st.splits[0].distanceM, 1e-6)
        // 1000 m / 111.19 m pro 30 s ≈ 269.8 s
        assertEquals(269.8, st.splits[0].durationSec, 1.0)
        assertEquals(st.movingSec, st.splits.sumOf { it.durationSec }, 1e-6)
        assertEquals(0.0, st.elevationGainM, 1e-9) // Rauschen < 3 m
    }

    @Test
    fun pauseSegmentsAreNotCounted() {
        val a = listOf(TrackPoint(0, 48.0, 11.0, seg = 0), TrackPoint(10_000, 48.001, 11.0, seg = 0))
        val b = listOf(TrackPoint(100_000, 48.01, 11.0, seg = 1), TrackPoint(110_000, 48.011, 11.0, seg = 1))
        val st = Track.stats(a + b)
        assertEquals(2 * 111.19, st.distanceM, 1.0)
        assertEquals(20.0, st.movingSec, 1e-9)
    }

    @Test
    fun exportsContainEverything() {
        val d = sampleData()
        val txt = Export.text(d, null, "2026-10-03 20:00")
        assertTrue(txt.contains("Bankdrücken"))
        assertTrue(txt.contains("85×6"))
        assertTrue(txt.contains("KG+10×6"))
        assertTrue(txt.contains("5.21 km"))
        assertTrue(txt.contains("Ø Pace 5:17"))
        val js = Export.aiJson(d, null, "2026-10-03 20:00")
        assertTrue(js.contains("\"bestE1rmKg\""))
        assertTrue(js.contains("\"kmSplits\""))
        val filtered = Export.text(d, "2026-09-03", "x")
        assertTrue(!filtered.contains("## 2026-09-01"))
        val back = Export.parseBackup(Export.backup(d, mapOf("r1" to listOf(TrackPoint(1, 2.0, 3.0))), "x"))
        assertEquals(d, back.data)
        assertEquals(1, back.tracks["r1"]!!.size)
    }
}
