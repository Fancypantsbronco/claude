package app.fittrack.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.fittrack.MainActivity
import app.fittrack.R

object Notifications {
    const val CH_REST = "rest_timer"
    const val CH_RUN = "run_tracking"
    const val CH_REMIND = "reminders"

    const val ID_REST = 100
    const val ID_RUN = 200
    const val ID_WEIGHT = 300
    const val ID_BONUS = 301

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_REST, "Pausen-Timer", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Signal, wenn die Satzpause vorbei ist"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 200, 400)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_RUN, "Lauf-Aufzeichnung", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Laufende GPS-Aufzeichnung"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_REMIND, "Erinnerungen", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Wiegen und Bonus-Aktivitäten"
            }
        )
    }

    fun openAppIntent(ctx: Context, route: String? = null, requestCode: Int = 0): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (route != null) putExtra(MainActivity.EXTRA_ROUTE, route)
        }
        return PendingIntent.getActivity(ctx, requestCode, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun canPost(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun show(ctx: Context, id: Int, channel: String, title: String, text: String, route: String? = null) {
        if (!canPost(ctx)) return
        val n = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setPriority(if (channel == CH_REST) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openAppIntent(ctx, route, id))
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(id, n)
        } catch (_: SecurityException) {
        }
    }

    fun cancel(ctx: Context, id: Int) = NotificationManagerCompat.from(ctx).cancel(id)
}
