package app.fittrack.notify

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.fittrack.FitApp
import app.fittrack.data.Settings
import app.fittrack.domain.Calc
import app.fittrack.domain.Suggestions
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * Erinnerungen: wöchentlich ans Wiegen, und abends eine Ermutigung zu Bonus-Aktivitäten,
 * wenn das Wochenziel noch offen ist. Jede Erinnerung plant nach dem Auslösen die nächste.
 */
object Reminders {
    private const val WEIGHT = "weight_reminder"
    private const val BONUS = "bonus_reminder"

    fun sync(ctx: Context, s: Settings) {
        val wm = WorkManager.getInstance(ctx)
        if (s.weightReminder) {
            val at = nextWeekly(s.weightReminderDay, Calc.parseTime(s.weightReminderTime) ?: LocalTime.of(9, 0))
            enqueue(ctx, WEIGHT, at, WeightReminderWorker::class.java)
        } else wm.cancelUniqueWork(WEIGHT)
        if (s.bonusReminder) {
            val at = nextDaily(Calc.parseTime(s.bonusReminderTime) ?: LocalTime.of(18, 0))
            enqueue(ctx, BONUS, at, BonusReminderWorker::class.java)
        } else wm.cancelUniqueWork(BONUS)
    }

    fun nextWeekly(isoDay: Int, time: LocalTime, now: LocalDateTime = LocalDateTime.now()): LocalDateTime {
        var d = now.toLocalDate()
        while (d.dayOfWeek.value != isoDay) d = d.plusDays(1)
        var at = LocalDateTime.of(d, time)
        if (!at.isAfter(now)) at = at.plusWeeks(1)
        return at
    }

    fun nextDaily(time: LocalTime, now: LocalDateTime = LocalDateTime.now()): LocalDateTime {
        var at = LocalDateTime.of(now.toLocalDate(), time)
        if (!at.isAfter(now)) at = at.plusDays(1)
        return at
    }

    private fun enqueue(ctx: Context, name: String, at: LocalDateTime, cls: Class<out CoroutineWorker>) {
        val delay = Duration.between(LocalDateTime.now(), at).toMillis().coerceAtLeast(0)
        val request = androidx.work.OneTimeWorkRequest.Builder(cls)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, request)
    }
}

class WeightReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val data = FitApp.repo.current
        val last = data.bodyWeights.maxOfOrNull { it.date }
        val days = last?.let { Calc.daysSince(it) }
        if (days == null || days >= 1) {
            Notifications.show(
                applicationContext, Notifications.ID_WEIGHT, Notifications.CH_REMIND,
                "Zeit fürs Wiegen ⚖️",
                if (last == null) "Trag dein Körpergewicht ein – es fließt auch ins 1RM deiner Körpergewichtsübungen ein."
                else "Letzte Messung ${Calc.relativeDay(last)}. Kurz auf die Waage?",
                route = "body?add=true",
            )
        }
        Reminders.sync(applicationContext, data.settings)
        return Result.success()
    }
}

class BonusReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val data = FitApp.repo.current
        val weekStart = Calc.weekStart().format(Calc.DATE)
        val thisWeek = data.activities.count { it.date >= weekStart }
        val lastDate = data.activities.maxOfOrNull { it.date }
        val daysSince = lastDate?.let { Calc.daysSince(it) } ?: 99
        val goal = data.settings.bonusWeeklyGoal
        if (thisWeek < goal && daysSince >= 2) {
            val s = Suggestions.forDay(LocalDate.now())
            Notifications.show(
                applicationContext, Notifications.ID_BONUS, Notifications.CH_REMIND,
                "Kleine Extra-Einheit? 💪",
                "$s ($thisWeek von $goal Bonus-Aktivitäten diese Woche)",
                route = "extras",
            )
        }
        Reminders.sync(applicationContext, data.settings)
        return Result.success()
    }
}
