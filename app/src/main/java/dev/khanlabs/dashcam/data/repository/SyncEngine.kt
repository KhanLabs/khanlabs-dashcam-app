package dev.khanlabs.dashcam.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.khanlabs.dashcam.data.local.SyncedFileDao
import dev.khanlabs.dashcam.data.local.SyncedFileEntity
import dev.khanlabs.dashcam.data.model.CameraIndex
import dev.khanlabs.dashcam.data.model.SyncScope
import dev.khanlabs.dashcam.data.network.DashcamManifestClient
import dev.khanlabs.dashcam.data.network.WifiConnectionObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalTime
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** Distinguishes *why* a sync stopped early, instead of collapsing
 *  "user cancelled" and "phone left the dashcam's WiFi mid-sync" into one
 *  `cancelled` boolean -- the latter used to report as a plain completed
 *  sync (indistinguishable from downloading everything), silently
 *  understating how much footage is actually backed up.
 *
 *  [ALREADY_SYNCING] (added 2026-07-16 alongside AutoSyncCoordinator): a
 *  second concurrent caller -- the manual SYNC button firing while the
 *  WiFi-join auto-sync is already mid-run, or vice versa -- gets turned
 *  away by [SyncEngine.isSyncing] rather than racing the first run's
 *  writes to the same destination files. A DownloadSummary with this
 *  reason and all-zero counts must never be shown as "up to date": that
 *  would be the exact misreporting class Fix Batch 3 (C2) already killed
 *  for NETWORK_CHANGED/USER_CANCELLED, just via a new door. */
enum class SyncStopReason { COMPLETED, USER_CANCELLED, NETWORK_CHANGED, ALREADY_SYNCING }

data class DownloadSummary(
    val downloadedClips: Int,
    val downloadedTracks: Int,
    val failedCount: Int,
    val stopReason: SyncStopReason = SyncStopReason.COMPLETED
)

/**
 * Real-time state for a single [SyncEngine.syncNow] run, reported via
 * `onProgress` so the UI can show which clip is transferring, at what
 * speed, and overall completion -- not just a static "syncing..." label.
 * [totalFiles]/[totalBytes] cover clips only (the dominant share of a real
 * sync); GPX tracks are small enough not to be worth their own progress
 * accounting.
 */
data class SyncProgress(
    val currentClipLabel: String,
    val currentFileName: String,
    val currentFileIndex: Int,
    val totalFiles: Int,
    val currentFileBytesDone: Long,
    val currentFileBytesTotal: Long,
    val speedBytesPerSec: Long,
    val overallBytesDone: Long,
    val overallBytesTotal: Long
)

/**
 * User-initiated (SYNC button) download of new clips/tracks from the
 * dashcam to local storage. Deliberately NOT WorkManager-backed: this only
 * runs while the app is in the foreground and the user just tapped a
 * button, which a plain suspend function already covers -- WorkManager's
 * real value (surviving process death, running without the app open) only
 * matters for the deferred auto-sync-on-WiFi trigger, not this path. Adding
 * it here would mean wiring @HiltWorker/Configuration.Provider for no
 * benefit, and with no test phone connected this session, that's untested
 * surface not worth taking on for v1 (see BACKLOG_2026-07-13.md item A2).
 *
 * Dedup is still by local filename existence first (A3, BACKLOG_2026-07-13.md)
 * -- if the file is genuinely missing from disk, it's re-downloaded no matter
 * what [SyncedFileDao] says, so a file removed outside the app (or by
 * [SettingsRepository.clearDownloadCache]) is never silently skipped. Every
 * file found to already exist -- freshly downloaded or already on disk from
 * before -- gets a row recorded/backfilled in [SyncedFileDao], which is what
 * lets [RealDashcamRepository] compute real new-clip/new-track/ready-to-sync
 * numbers by diffing the dashcam's live listing against known-synced
 * filenames, without re-deriving that logic here.
 *
 * v1 scope, deliberately narrow (see A2 in the backlog): downloads land in
 * app-private storage first, always -- that's still what
 * RealVideoRepository/RealTripRepository/Gallery/Map/Player read from (see
 * A2b in BACKLOG_2026-07-13.md), unaffected by the setting below. Honors
 * [SyncScope] filtering -- quality/auto-delete settings were removed for the
 * same reason: the device only ever serves one quality (nothing to switch),
 * and it has no delete-capable endpoint (same limitation
 * `RealVideoRepository.removeClip` documents: it can delete the local synced
 * copy, never anything on the dashcam's SD card).
 *
 * [PublicDownloadStorage] (added 2026-07-16) is the MediaStore integration
 * that used to not exist: when [SettingsRepository]'s `saveToPublicDownloads`
 * is on, every freshly-downloaded file (not already-synced ones found via
 * the dedup check above) also gets copied into the public Downloads
 * collection. Deliberately only on the freshly-downloaded path, not the
 * dedup-hit path -- publishing on every dedup hit would re-copy the entire
 * already-synced backlog into MediaStore on every single sync run.
 */
@Singleton
class SyncEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val videoRepository: VideoRepository,
    private val manifestClient: DashcamManifestClient,
    private val settingsRepository: SettingsRepository,
    private val wifiConnectionObserver: WifiConnectionObserver,
    private val syncedFileDao: SyncedFileDao
) {
    private val gpxDirPath = "gpx/files/GPSLogger"

    private val downloadDir: File
        get() = SyncStorage.downloadDir(context)

    // Guards against two concurrent syncNow runs (manual SYNC button +
    // AutoSyncCoordinator's WiFi-join trigger) racing writes to the same
    // destination files -- see SyncStopReason.ALREADY_SYNCING's doc comment.
    // A plain AtomicBoolean, not a Mutex: a second caller should be turned
    // away immediately with an honest "already syncing" result, not queued
    // to silently run again once the first finishes.
    private val isSyncing = AtomicBoolean(false)

    private val _lastSyncCompletedAt = MutableStateFlow<LocalTime?>(null)

    /** Updated at the end of every real (non-[SyncStopReason.ALREADY_SYNCING])
     *  syncNow run, regardless of caller -- lets DashboardViewModel reflect a
     *  background-triggered sync's completion even though it didn't call
     *  syncNow itself. */
    val lastSyncCompletedAt: StateFlow<LocalTime?> = _lastSyncCompletedAt.asStateFlow()

    /**
     * A2c (BACKLOG_2026-07-13.md): a watcher collects [WifiConnectionObserver]
     * alongside the download loops and flips [networkChanged] the moment the
     * phone leaves this gateway -- checked between every single download so
     * the loop bails instead of grinding through the rest of a large sync
     * failing each file against a now-unreachable IP. Confirmed live: this is
     * exactly what happened when the test phone organically roamed off the
     * dashcam's hotspot mid-sync during hardware verification.
     *
     * This only treats the symptom (stop promptly once the network is gone).
     * The deeper, complementary fix -- holding/pinning the dashcam's network
     * for the sync's duration so Android doesn't roam away in the first place
     * -- is a separate, unbuilt design question (ConnectivityManager
     * NetworkRequest territory); see the A2c note in the backlog.
     */
    suspend fun syncNow(
        gatewayIp: String,
        onProgress: (SyncProgress) -> Unit = {},
        isCancelled: () -> Boolean = { false }
    ): DownloadSummary {
        if (!isSyncing.compareAndSet(false, true)) {
            return DownloadSummary(0, 0, 0, SyncStopReason.ALREADY_SYNCING)
        }
        try {
            return syncNowLocked(gatewayIp, onProgress, isCancelled)
        } finally {
            isSyncing.set(false)
        }
    }

    private suspend fun syncNowLocked(
        gatewayIp: String,
        onProgress: (SyncProgress) -> Unit,
        isCancelled: () -> Boolean
    ): DownloadSummary = coroutineScope {
        val currentSettings = settingsRepository.observeSettings().first()
        val scope = currentSettings.syncScope
        val publishPublicCopy = currentSettings.saveToPublicDownloads && PublicDownloadStorage.isSupported
        var downloadedClips = 0
        var downloadedTracks = 0
        var failed = 0

        val networkChanged = AtomicBoolean(false)
        val watcherJob = launch {
            wifiConnectionObserver.observeWifiState().collect { state ->
                if (!state.isDashcamNetwork || state.gatewayIp != gatewayIp) {
                    networkChanged.set(true)
                }
            }
        }
        fun stopped() = networkChanged.get() || isCancelled()

        try {
            // fetchRemoteClips, NOT observeClips().first(): see
            // VideoRepository.fetchRemoteClips's doc -- observeClips() now
            // emits already-synced local clips first (for Gallery/Player),
            // and .first() would grab that instead of the true on-device
            // listing, making SyncEngine think everything is already synced.
            val clips = videoRepository.fetchRemoteClips(gatewayIp).filter { clip ->
                when (scope) {
                    SyncScope.BOTH_CAMERAS -> true
                    SyncScope.ROAD_ONLY -> clip.cameraIndex == CameraIndex.ROAD
                    SyncScope.CABIN_ONLY -> clip.cameraIndex == CameraIndex.CABIN
                }
            }
            val totalBytes = clips.sumOf { it.sizeBytes }
            var overallBytesDone = 0L

            for ((index, clip) in clips.withIndex()) {
                if (stopped()) break
                val sourceUrl = clip.sourceUrl ?: continue
                val destination = File(downloadDir, clip.filename)
                if (destination.exists()) {
                    syncedFileDao.insert(SyncedFileEntity(clip.filename, clip.sizeBytes, destination.lastModified()))
                    overallBytesDone += clip.sizeBytes
                    continue
                }
                var lastEmitBytes = 0L
                var lastEmitAt = System.currentTimeMillis()
                val success = manifestClient.downloadToFile(sourceUrl, destination) { bytesDone, bytesTotal ->
                    val now = System.currentTimeMillis()
                    val elapsedSec = (now - lastEmitAt) / 1000.0
                    val speed = if (elapsedSec > 0) ((bytesDone - lastEmitBytes) / elapsedSec).toLong() else 0L
                    onProgress(
                        SyncProgress(
                            currentClipLabel = "${clip.cameraIndex.label} • ${clip.recordedTime}",
                            currentFileName = clip.filename,
                            currentFileIndex = index + 1,
                            totalFiles = clips.size,
                            currentFileBytesDone = bytesDone,
                            currentFileBytesTotal = bytesTotal.takeIf { it > 0 } ?: clip.sizeBytes,
                            speedBytesPerSec = speed,
                            overallBytesDone = overallBytesDone + bytesDone,
                            overallBytesTotal = totalBytes
                        )
                    )
                    lastEmitBytes = bytesDone
                    lastEmitAt = now
                }
                if (success) {
                    syncedFileDao.insert(SyncedFileEntity(clip.filename, clip.sizeBytes, System.currentTimeMillis()))
                    if (publishPublicCopy) {
                        withContext(Dispatchers.IO) {
                            PublicDownloadStorage.publish(context, destination, clip.filename)
                        }
                    }
                    downloadedClips++
                } else {
                    failed++
                }
                overallBytesDone += clip.sizeBytes
            }

            if (!stopped()) {
                val gpxEntries = manifestClient.list(gatewayIp, gpxDirPath)
                    .filter { !it.isDirectory && it.name.endsWith(".gpx") }
                for (entry in gpxEntries) {
                    if (stopped()) break
                    val destination = File(downloadDir, entry.name)
                    if (destination.exists()) {
                        syncedFileDao.insert(SyncedFileEntity(entry.name, entry.sizeBytes, destination.lastModified()))
                        continue
                    }
                    val url = "http://$gatewayIp:8080/$gpxDirPath/${entry.name}"
                    if (manifestClient.downloadToFile(url, destination)) {
                        syncedFileDao.insert(SyncedFileEntity(entry.name, entry.sizeBytes, System.currentTimeMillis()))
                        if (publishPublicCopy) {
                            withContext(Dispatchers.IO) {
                                PublicDownloadStorage.publish(context, destination, entry.name)
                            }
                        }
                        downloadedTracks++
                    } else {
                        failed++
                    }
                }
            }
        } finally {
            watcherJob.cancel()
        }

        val stopReason = when {
            networkChanged.get() -> SyncStopReason.NETWORK_CHANGED
            isCancelled() -> SyncStopReason.USER_CANCELLED
            else -> SyncStopReason.COMPLETED
        }
        _lastSyncCompletedAt.value = LocalTime.now()
        DownloadSummary(downloadedClips, downloadedTracks, failed, stopReason)
    }
}
