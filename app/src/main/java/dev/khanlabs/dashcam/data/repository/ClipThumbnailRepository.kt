package dev.khanlabs.dashcam.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.khanlabs.dashcam.data.model.VideoClip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val MAX_THUMBNAIL_WIDTH_PX = 320
private const val FRAME_TIME_MICROS = 1_000_000L // 1s in -- skips a possible black/blank first frame

/**
 * Extracts a single frame per clip on demand (as Gallery rows scroll into
 * view, never a bulk pre-generate pass -- the dashcam's own hotspot is slow
 * and shared with whatever else is syncing) and caches it to disk keyed by
 * filename, since filenames are already unique per clip (see
 * VideoFilenameParser's doc comment) and re-extracting on every recomposition
 * would mean one MediaMetadataRetriever pass per scroll for remote clips.
 * Cache lives under [Context.getCacheDir] (not files/external storage) --
 * regenerable, fine for the system to reclaim under storage pressure.
 */
@Singleton
class ClipThumbnailRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val cacheDir: File by lazy {
        File(context.cacheDir, "clip_thumbnails").apply { mkdirs() }
    }

    /** One [Mutex] per filename so two overlapping requests for the same
     *  clip (e.g. a Gallery row scrolled out and back before its first
     *  extraction finished) await one extraction instead of racing two
     *  writers against the same destination file -- the previous direct-
     *  write version could truncate/corrupt the cached JPEG that way, and
     *  since the cache check was existence-only, a corrupted file then
     *  failed to decode forever with no recovery path. Grows one small
     *  entry per distinct filename ever requested; negligible even for a
     *  600+ clip gallery. */
    private val locks = ConcurrentHashMap<String, Mutex>()

    /** Local (`file://`) clips are a cheap on-disk read; remote
     *  (`http://gateway:8080/...`) clips cost one HTTP range-style fetch
     *  over the dashcam's hotspot -- both go through the same
     *  [MediaMetadataRetriever] path, which handles either URI scheme. */
    suspend fun getThumbnail(clip: VideoClip): File? = withContext(Dispatchers.IO) {
        val cached = File(cacheDir, "${clip.filename}.jpg")
        if (cached.exists()) return@withContext cached

        locks.getOrPut(clip.filename) { Mutex() }.withLock {
            // Re-check inside the lock: another caller may have finished
            // the extraction while this one was waiting.
            if (cached.exists()) return@withLock cached
            generateThumbnail(clip, cached)
        }
    }

    /** Writes to a `.part` sibling and renames only on success -- same
     *  atomic-write pattern as [DashcamManifestClient.downloadToFile] --
     *  so a cancelled coroutine or a mid-write failure can never leave a
     *  truncated file sitting at the real cache path. */
    private fun generateThumbnail(clip: VideoClip, cached: File): File? {
        val sourceUrl = clip.sourceUrl ?: return null
        val retriever = MediaMetadataRetriever()
        val tempFile = File(cacheDir, "${clip.filename}.jpg.part")
        return try {
            if (sourceUrl.startsWith("http")) {
                retriever.setDataSource(sourceUrl, emptyMap())
            } else {
                retriever.setDataSource(context, Uri.parse(sourceUrl))
            }
            val frame = retriever.getFrameAtTime(FRAME_TIME_MICROS, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(0)
                ?: return null

            val scaled = downscale(frame)
            FileOutputStream(tempFile).use { out -> scaled.compress(Bitmap.CompressFormat.JPEG, 82, out) }
            if (tempFile.renameTo(cached)) cached else null
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
            tempFile.delete() // no-op once already renamed away; cleans up a failed partial write
        }
    }

    private fun downscale(bitmap: Bitmap): Bitmap {
        if (bitmap.width <= MAX_THUMBNAIL_WIDTH_PX) return bitmap
        val ratio = MAX_THUMBNAIL_WIDTH_PX.toFloat() / bitmap.width
        return Bitmap.createScaledBitmap(bitmap, MAX_THUMBNAIL_WIDTH_PX, (bitmap.height * ratio).toInt(), true)
    }

    /** Called when a clip is removed -- this cache had no eviction of any
     *  kind before, so every clip ever scrolled past left its thumbnail
     *  behind forever even after the clip itself was gone. */
    suspend fun deleteThumbnail(filename: String) = withContext(Dispatchers.IO) {
        File(cacheDir, "$filename.jpg").delete()
        locks.remove(filename)
    }

    /** Called from Settings' "Clear cache" -- previously only cleared
     *  [SyncStorage]'s download dir, leaving this cache untouched. */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        cacheDir.listFiles()?.forEach { it.delete() }
        locks.clear()
    }
}
