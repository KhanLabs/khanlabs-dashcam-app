package dev.khanlabs.dashcam.data.repository

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.khanlabs.dashcam.data.local.RemovedClipDao
import dev.khanlabs.dashcam.data.local.RemovedClipEntity
import dev.khanlabs.dashcam.data.model.VideoClip
import dev.khanlabs.dashcam.data.network.DashcamManifestClient
import dev.khanlabs.dashcam.data.network.WifiConnectionObserver
import dev.khanlabs.dashcam.data.parser.VideoFilenameParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real (not mock) clip listing, read from the dashcam's `cgi-bin/browse`
 * endpoint over its own WiFi hotspot. Re-fetches whenever the WiFi state
 * changes (join/leave the dashcam network). When off the dashcam's network
 * entirely, falls back to listing whatever SyncEngine has already
 * downloaded locally (see [SyncStorage]) rather than an empty list -- that
 * fallback is the actual point of syncing (BACKLOG_2026-07-13.md item A2b):
 * footage should still be visible after driving away from the dashcam.
 * Clips already downloaded play from the local copy even while still
 * on-network (faster, one less thing depending on the hotspot's throughput).
 */
@Singleton
class RealVideoRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wifiConnectionObserver: WifiConnectionObserver,
    private val manifestClient: DashcamManifestClient,
    private val removedClipDao: RemovedClipDao,
    private val thumbnailRepository: ClipThumbnailRepository
) : VideoRepository {

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeClips(): Flow<List<VideoClip>> =
        wifiConnectionObserver.observeWifiState()
            // See RealDashcamRepository's identical guard: rssiDbm changes
            // constantly in a moving car and is part of WifiState's default
            // equality, so without this every signal tick would
            // flatMapLatest-cancel and restart the recordings/ listing walk.
            .distinctUntilChangedBy { Triple(it.ssid, it.isDashcamNetwork, it.gatewayIp) }
            .flatMapLatest { wifiState ->
                val gateway = wifiState.gatewayIp
                if (!wifiState.isDashcamNetwork || gateway == null) {
                    flow { emit(safeClips { localClips() }) }
                } else {
                    // Emit already-synced local clips immediately rather than
                    // blocking Gallery/Player's first frame on fetchClips's
                    // full remote directory walk, directly measured at 20-50+
                    // seconds on a large recordings folder (BACKLOG_2026-07-13.md).
                    // The remote listing replaces this once it resolves; worst
                    // case (fetchClips also falls back to localClips) this is
                    // just one redundant, cheap local read.
                    flow {
                        emit(safeClips { localClips() })
                        emit(safeClips { fetchClips(gateway) })
                    }
                }
            }
            .combine(removedClipDao.observeFilenames().map { it.toHashSet() }) { clips, removed ->
                clips.filterNot { it.filename in removed }
            }

    /**
     * Defense-in-depth boundary matching RealDashcamRepository.computeClipStats:
     * fetchClips/localClips already fail soft for known cases (empty
     * listings, DashcamManifestClient's own soft-fail on IOException), but
     * an unexpected exception elsewhere (e.g. a filesystem/Room hiccup)
     * would otherwise propagate out of this flow{} builder and stop
     * observeClips() from ever emitting again -- Gallery/Player would go
     * permanently blank for the rest of that WiFi session instead of just
     * showing an empty/stale list. Rethrows CancellationException so
     * flatMapLatest can still cancel this work on a WiFi-state change.
     */
    private suspend fun safeClips(block: suspend () -> List<VideoClip>): List<VideoClip> = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * The remote listing can legitimately come back empty while still on the
     * dashcam's network: [DashcamManifestClient.list] fails soft (empty list)
     * on any [java.io.IOException], and its 3s connect / 8s read timeouts are
     * real ones to hit when SyncEngine is concurrently streaming a large
     * download through the dashcam's single-threaded busybox httpd -- live-
     * reproduced 2026-07-15 (see BACKLOG_2026-07-13.md B10/IMPLEMENTATION_NOTES.md
     * Section 18 follow-up). Because [observeClips] only re-runs this on a
     * WiFi-state change (`distinctUntilChangedBy`), a single failed listing
     * stays empty for the rest of that network session -- Gallery would show
     * "No footage synced yet" for clips already sitting on disk. Falling back
     * to [localClips] here is the same principle A2b already documents
     * ("footage should still be visible after driving away"): a slow/busy
     * dashcam is no different from an absent one as far as already-downloaded
     * footage is concerned.
     */
    private suspend fun fetchClips(gatewayIp: String): List<VideoClip> {
        val dateFolders = manifestClient.list(gatewayIp, "recordings").filter { it.isDirectory }
        if (dateFolders.isEmpty()) return localClips()

        val parsed = dateFolders.flatMap { folder ->
            manifestClient.list(gatewayIp, "recordings/${folder.name}")
                .filter { !it.isDirectory && it.name.endsWith(".mp4") }
                .mapNotNull { entry ->
                    VideoFilenameParser.parse(entry.name, durationSeconds = 0, sizeBytes = entry.sizeBytes)
                        ?.copy(sourceUrl = "http://$gatewayIp:8080/recordings/${folder.name}/${entry.name}")
                }
        }
        if (parsed.isEmpty()) return localClips()

        return preferLocalCopies(withEstimatedDurations(parsed))
    }

    private suspend fun localClips(): List<VideoClip> = withContext(Dispatchers.IO) {
        val files = SyncStorage.downloadDir(context)
            .listFiles { file -> file.isFile && file.name.endsWith(".mp4") }
            ?: return@withContext emptyList()

        val parsed = files.mapNotNull { file ->
            VideoFilenameParser.parse(file.name, durationSeconds = 0, sizeBytes = file.length())
                ?.copy(sourceUrl = Uri.fromFile(file).toString())
        }
        withEstimatedDurations(parsed)
    }

    private suspend fun preferLocalCopies(clips: List<VideoClip>): List<VideoClip> = withContext(Dispatchers.IO) {
        val downloadDir = SyncStorage.downloadDir(context)
        clips.map { clip ->
            val localFile = File(downloadDir, clip.filename)
            if (localFile.exists()) clip.copy(sourceUrl = Uri.fromFile(localFile).toString()) else clip
        }
    }

    /** Neither the listing endpoint nor local files carry duration. This
     *  dashcam records in roughly fixed-length segments, so the gap to the
     *  next clip on the same camera stream is a real, measured stand-in
     *  instead of showing "0s" for every clip. */
    private fun withEstimatedDurations(clips: List<VideoClip>): List<VideoClip> =
        clips.groupBy { it.cameraIndex }
            .flatMap { (_, camClips) ->
                val sorted = camClips.sortedBy { it.epochSeconds }
                sorted.mapIndexed { index, clip ->
                    val next = sorted.getOrNull(index + 1)
                    val prev = sorted.getOrNull(index - 1)
                    val estimated = next?.let { it.epochSeconds - clip.epochSeconds }
                        ?: prev?.let { clip.epochSeconds - it.epochSeconds }
                        ?: 0L
                    clip.copy(durationSeconds = estimated.coerceAtLeast(0L).toInt())
                }
            }

    /**
     * Deletes the local synced copy if one exists (real disk space
     * reclaimed on the phone -- what the "Remove" action can honestly
     * promise) and records the filename in [RemovedClipDao] so it stays
     * hidden after a restart and SyncEngine (which lists clips through
     * [observeClips] too) doesn't just re-download it on the next SYNC.
     * Does NOT touch the dashcam's SD card -- no write-capable endpoint
     * exists there yet.
     */
    override suspend fun removeClip(filename: String) {
        withContext(Dispatchers.IO) {
            val localFile = File(SyncStorage.downloadDir(context), filename)
            if (localFile.exists()) localFile.delete()
        }
        thumbnailRepository.deleteThumbnail(filename)
        removedClipDao.insert(RemovedClipEntity(filename, System.currentTimeMillis()))
    }

    override suspend fun prepareForShare(clip: VideoClip): File? = withContext(Dispatchers.IO) {
        val local = File(SyncStorage.downloadDir(context), clip.filename)
        if (local.exists()) return@withContext local
        val sourceUrl = clip.sourceUrl ?: return@withContext null
        if (manifestClient.downloadToFile(sourceUrl, local)) local else null
    }

    override suspend fun fetchRemoteClips(gatewayIp: String): List<VideoClip> = fetchClips(gatewayIp)
}
