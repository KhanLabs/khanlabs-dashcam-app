package dev.khanlabs.dashcam

import android.app.Application
import android.content.Context
import dagger.hilt.android.HiltAndroidApp
import dev.khanlabs.dashcam.data.repository.AutoSyncCoordinator
import org.osmdroid.config.Configuration
import javax.inject.Inject

@HiltAndroidApp
class KhanLabsDashcamApp : Application() {
    // Field injection, not constructor injection: the OS constructs
    // Application before Hilt's component exists, so this is populated by
    // Hilt's generated Application.attachBaseContext hook, not by us.
    @Inject
    lateinit var autoSyncCoordinator: AutoSyncCoordinator

    override fun onCreate() {
        super.onCreate()
        // osmdroid requires a distinct user agent or tile servers will reject requests.
        val prefs = getSharedPreferences("${packageName}_osmdroid", Context.MODE_PRIVATE)
        Configuration.getInstance().load(this, prefs)
        Configuration.getInstance().userAgentValue = packageName

        autoSyncCoordinator.start()
    }
}
