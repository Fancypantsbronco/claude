package app.fittrack.domain

import java.time.LocalDate

/** Ideen für Bonus-Aktivitäten außerhalb von Gym und Laufen. */
object Suggestions {

    data class Idea(val text: String, val type: String, val amount: Double?, val unit: String)

    val ideas = listOf(
        Idea("Wie wäre es mit 3 × 15 Liegestützen?", "Liegestütze", 45.0, "Wdh"),
        Idea("Ab ins Wasser: 1000 m Schwimmen tun Gelenken und Ausdauer gut.", "Schwimmen", 1000.0, "m"),
        Idea("10 Minuten Dehnen – deine Hüften werden es dir danken.", "Dehnen", 10.0, "min"),
        Idea("3 × 60 Sekunden Plank für eine starke Körpermitte.", "Plank", 180.0, "s"),
        Idea("Eine lockere Radtour statt Auto – zählt auch!", "Radfahren", 10.0, "km"),
        Idea("20 Minuten Yoga zur Regeneration.", "Yoga", 20.0, "min"),
        Idea("5 Minuten Seilspringen bringen den Puls hoch.", "Seilspringen", 5.0, "min"),
        Idea("Spaziergang oder Wanderung am Wochenende?", "Wandern", 5.0, "km"),
        Idea("Treppe statt Aufzug – 10 Etagen heute?", "Treppensteigen", 10.0, "min"),
        Idea("Klimmzug-Challenge: so viele saubere Wiederholungen wie möglich.", "Klimmzüge", null, "Wdh"),
    )

    fun ideaForDay(day: LocalDate): Idea = ideas[(day.toEpochDay() % ideas.size).toInt()]

    fun forDay(day: LocalDate): String = ideaForDay(day).text
}
