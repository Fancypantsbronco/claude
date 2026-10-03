package app.fittrack.run

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.fittrack.FitApp
import app.fittrack.R
import app.fittrack.domain.Calc
import app.fittrack.notify.Notifications
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.Locale

/** Vordergrund-Dienst: GPS läuft weiter, auch wenn der Bildschirm aus ist. */
class RunService : Service() {

    private lateinit var client: FusedLocationProviderClient
    private val handler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach { RunTracker.onLocation(it) }
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (RunTracker.live.value.active) {
                updateNotification()
                handler.postDelayed(this, 5_000)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        client = LocationServices.getFusedLocationProviderClient(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start()
            ACTION_PAUSE -> { RunTracker.pause(); updateNotification() }
            ACTION_RESUME -> { RunTracker.resume(); updateNotification() }
            ACTION_STOP -> stop()
            else -> if (!RunTracker.live.value.active) stopSelf()
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun start() {
        if (!RunTracker.live.value.active) RunTracker.start()
        ServiceCompat.startForeground(this, Notifications.ID_RUN, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000)
            .setMinUpdateIntervalMillis(1_000)
            .build()
        try {
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            stop(); return
        }
        if (FitApp.repo.current.settings.voiceFeedback) {
            tts = TextToSpeech(this) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) tts?.language = Locale.GERMAN
            }
            RunTracker.onSplit = { split, live -> announce(split.km, live.activeMs() / 1000, split.durationSec) }
        }
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    private fun announce(km: Int, totalSec: Long, splitSec: Double) {
        if (!ttsReady) return
        val text = "Kilometer $km. Zeit ${spoken(totalSec)}. Letzter Kilometer ${spoken(splitSec.toLong())}."
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "km$km")
    }

    private fun spoken(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return buildString {
            if (h > 0) append("$h Stunde${if (h > 1) "n" else ""} ")
            append("$m Minute${if (m != 1L) "n" else ""}")
            if (s > 0) append(" $s")
        }
    }

    private fun stop() {
        client.removeLocationUpdates(callback)
        handler.removeCallbacks(ticker)
        RunTracker.onSplit = null
        RunTracker.finish()?.let { (run, points) ->
            FitApp.repo.upsertRun(run)
            if (points.isNotEmpty()) FitApp.repo.saveTrack(run.id, points)
        }
        tts?.shutdown()
        tts = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        client.removeLocationUpdates(callback)
        handler.removeCallbacks(ticker)
        tts?.shutdown()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val l = RunTracker.live.value
        val paused = l.status == RunTracker.Status.PAUSED
        val text = "${Calc.km(l.distanceM)} km · ${Calc.duration(l.activeMs() / 1000)} · Ø ${Calc.pace(Calc.paceSecPerKm(l.activeMs() / 1000.0, l.distanceM))} /km"
        val toggle = PendingIntent.getService(
            this, 2, Intent(this, RunService::class.java).setAction(if (paused) ACTION_RESUME else ACTION_PAUSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, Notifications.CH_RUN)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (paused) "Lauf pausiert" else "Lauf wird aufgezeichnet")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory("workout")
            .setContentIntent(Notifications.openAppIntent(this, "liverun", 7))
            .addAction(0, if (paused) "Fortsetzen" else "Pause", toggle)
            .build()
    }

    private fun updateNotification() {
        if (!RunTracker.live.value.active || !Notifications.canPost(this)) return
        try {
            NotificationManagerCompat.from(this).notify(Notifications.ID_RUN, buildNotification())
        } catch (_: SecurityException) {
        }
    }

    companion object {
        const val ACTION_START = "start"
        const val ACTION_PAUSE = "pause"
        const val ACTION_RESUME = "resume"
        const val ACTION_STOP = "stop"

        fun send(ctx: Context, action: String) {
            val i = Intent(ctx, RunService::class.java).setAction(action)
            if (action == ACTION_START) ContextCompat.startForegroundService(ctx, i) else ctx.startService(i)
        }
    }
}
