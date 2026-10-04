package dev.khanlabs.dashcam.data.repository

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Single source of truth for where SyncEngine writes downloaded clips/
 * tracks, so RealVideoRepository/RealTripRepository look in the same place
 * things were written to. App-private storage only -- a public-folder
 * destination would need real MediaStore integration, never built (the
 * "Download location" setting that used to imply otherwise was removed).
 */
object SyncStorage {
    fun downloadDir(context: Context): File =
        context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: File(context.filesDir, "recordings")
}
