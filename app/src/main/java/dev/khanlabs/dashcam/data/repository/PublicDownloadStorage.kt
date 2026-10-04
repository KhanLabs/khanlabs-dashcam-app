package dev.khanlabs.dashcam.data.repository

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import java.io.File

/**
 * Opt-in second copy of a synced file, published to the public Downloads
 * collection (MediaStore.Downloads) so it survives app uninstall and shows
 * up in Files/Downloads apps -- the "public (non-app-private) download
 * destination" that was deliberately left unbuilt when the old "Download
 * location" setting was removed (see AppSettings's doc comment).
 *
 * Deliberately additive, not a replacement for [SyncStorage]'s app-private
 * directory: RealVideoRepository/RealTripRepository/Gallery/Map/Player all
 * read from the private copy, and rebuilding them around MediaStore queries
 * (content:// listing, thumbnails, ExoPlayer playback from a public
 * collection) is a much bigger, untested change than "also save a copy
 * somewhere public" actually calls for. The cost is real: a file synced with
 * this on exists twice on disk. That trade is made explicit in the Settings
 * subtitle, not hidden.
 *
 * Gated to API 29+ (MediaStore.Downloads doesn't exist before Q) rather than
 * also supporting the API 26-28 legacy WRITE_EXTERNAL_STORAGE + directory +
 * MediaScannerConnection path: minSdk is 26, but every real device this
 * project tests against (OnePlus 8T) is well past 29, and an untested legacy
 * storage path is exactly the kind of surface this codebase avoids shipping
 * (see SyncEngine's own doc comment on the same tradeoff for WorkManager).
 * [isSupported] lets the Settings screen hide the toggle below API 29
 * instead of showing a control that would silently no-op.
 */
object PublicDownloadStorage {
    private const val RELATIVE_DIR = "Download/KhanLabsDashcam"

    val isSupported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /**
     * Copies [source] into the public Downloads collection under a
     * KhanLabsDashcam subfolder as [displayName]. Any prior entry with the
     * same name is deleted first, so re-syncing a file (e.g. after Clear
     * cache wipes the private copy but MediaStore still has last week's
     * public one) replaces it instead of MediaStore auto-renaming the new
     * one to "clip.mp4 (1)". Returns true on success; false (including on
     * any I/O failure) leaves the private copy as the only one -- this is
     * a best-effort extra, never load-bearing for SyncEngine's own summary.
     */
    fun publish(context: Context, source: File, displayName: String): Boolean {
        if (!isSupported) return false
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI

        resolver.delete(
            collection,
            "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?",
            arrayOf(displayName, "$RELATIVE_DIR/")
        )

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE_DIR)
            put(MediaStore.Downloads.MIME_TYPE, mimeTypeFor(displayName))
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val itemUri = resolver.insert(collection, values) ?: return false

        return try {
            val copied = resolver.openOutputStream(itemUri)?.use { out ->
                source.inputStream().use { it.copyTo(out) }
                true
            } ?: false
            if (!copied) {
                resolver.delete(itemUri, null, null)
                return false
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(itemUri, values, null, null)
            true
        } catch (e: Exception) {
            resolver.delete(itemUri, null, null)
            false
        }
    }

    private fun mimeTypeFor(name: String): String = when {
        name.endsWith(".mp4", ignoreCase = true) -> "video/mp4"
        name.endsWith(".gpx", ignoreCase = true) -> "application/gpx+xml"
        else -> "application/octet-stream"
    }
}
