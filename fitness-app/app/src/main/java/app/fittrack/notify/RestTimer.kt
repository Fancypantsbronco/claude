package app.fittrack.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Pausen-Timer zwischen den Sätzen. Die Restzeit wird in der App angezeigt; das Ende
 * meldet ein exakter Alarm per Benachrichtigung (funktioniert auch bei gesperrtem Bildschirm).
 */
object RestTimer {

    data class State(val endAt: Long, val totalSec: Int, val label: String)

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state

    fun start(ctx: Context, seconds: Int, label: String) {
        if (seconds <= 0) return
        val s = State(System.currentTimeMillis() + seconds * 1000L, seconds, label)
        _state.value = s
        Notifications.cancel(ctx, Notifications.ID_REST)
        schedule(ctx, s)
    }

    fun adjust(ctx: Context, deltaSec: Int) {
        val s = _state.value ?: return
        val newEnd = s.endAt + deltaSec * 1000L
        if (newEnd <= System.currentTimeMillis()) {
            skip(ctx); return
        }
        val n = s.copy(endAt = newEnd, totalSec = (s.totalSec + deltaSec).coerceAtLeast(1))
        _state.value = n
        schedule(ctx, n)
    }

    fun skip(ctx: Context) {
        _state.value = null
        alarmManager(ctx).cancel(pendingIntent(ctx, ""))
    }

    /** Wird von der Oberfläche aufgerufen, sobald die Zeit abgelaufen ist. */
    fun clearIfExpired() {
        val s = _state.value ?: return
        if (s.endAt <= System.currentTimeMillis()) _state.value = null
    }

    private fun alarmManager(ctx: Context) = ctx.getSystemService(AlarmManager::class.java)

    private fun pendingIntent(ctx: Context, label: String): PendingIntent {
        val i = Intent(ctx, RestTimerReceiver::class.java).putExtra("label", label)
        return PendingIntent.getBroadcast(ctx, 1, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun schedule(ctx: Context, s: State) {
        val am = alarmManager(ctx)
        val pi = pendingIntent(ctx, s.label)
        val exactAllowed = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        try {
            if (exactAllowed) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, s.endAt, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, s.endAt, pi)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, s.endAt, pi)
        }
    }
}

class RestTimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val label = intent.getStringExtra("label").orEmpty()
        RestTimer.clearIfExpired()
        Notifications.show(
            context, Notifications.ID_REST, Notifications.CH_REST,
            "Pause vorbei",
            if (label.isBlank()) "Weiter mit dem nächsten Satz!" else "Nächster Satz: $label",
        )
    }
}
