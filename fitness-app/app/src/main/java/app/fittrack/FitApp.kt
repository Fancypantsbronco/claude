package app.fittrack

import android.app.Application
import app.fittrack.data.Repository
import app.fittrack.notify.Notifications
import app.fittrack.notify.Reminders
import org.osmdroid.config.Configuration
import java.io.File

class FitApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        repo = Repository(this)
        Notifications.createChannels(this)
        Reminders.sync(this, repo.current.settings)

        // OpenStreetMap-Karten: User-Agent ist Pflicht, Kachel-Cache im App-Cache
        val osm = Configuration.getInstance()
        osm.load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        osm.userAgentValue = packageName
        osm.osmdroidBasePath = File(cacheDir, "osmdroid")
        osm.osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
    }

    companion object {
        lateinit var instance: FitApp
            private set
        lateinit var repo: Repository
            private set
    }
}
