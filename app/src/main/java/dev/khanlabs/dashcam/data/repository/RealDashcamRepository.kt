package dev.khanlabs.dashcam.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.khanlabs.dashcam.data.local.SyncedFileDao
import dev.khanlabs.dashcam.data.model.CameraIndex
import dev.khanlabs.dashcam.data.model.DeviceStatus
import dev.khanlabs.dashcam.data.model.GpsLockState
import dev.khanlabs.dashcam.data.network.DashcamManifestClient
import dev.khanlabs.dashcam.data.network.DeviceStats
import dev.khanlabs.dashcam.data.network.LteStatus
import dev.khanlabs.dashcam.data.network.StatusPayload
import dev.khanlabs.dashcam.data.network.WifiConnectionObserver
import dev.khanlabs.dashcam.data.network.WifiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val KB_PER_GB = 1024.0 * 1024.0
private const val BYTES_PER_GB = 1024.0 * 1024.0 * 1024.0

// User-requested "real time -- if signals change we should be able to see
// it" (LTE stats screen): a plain poll, not the one-shot-per-network-event
// pattern observeDeviceStatus uses, since storage/uptime don't need
// second-by-second freshness but signal/registration state does. 2.5s
// balances "feels live" against hammering dumpsys on the dashcam's own
// SDM450 every single frame.
private const val LTE_POLL_INTERVAL_MS = 2_500L

// Same "real time" rationale as LTE_POLL_INTERVAL_MS, slightly longer:
// cgi-bin/device-stats runs `top -n 1 -b` (walks every process on the
// device) plus several thermal-zone reads per request -- confirmed
// on-device this session that a single lte-status round trip already takes
// ~2-2.5s on this unit's embedded httpd, and device-stats does more work
// per request than that, so polling this fast would just queue requests
// behind each other rather than actually deliver fresher data.
private const val DEVICE_STATS_POLL_INTERVAL_MS = 3_500L

/**
 * Real (not mock) device status, read from the dashcam's `cgi-bin/status`
 * endpoint. Re-fetches whenever the WiFi state changes, same one-shot-per-
 * network-event pattern as RealVideoRepository/RealTripRepository (no
 * periodic polling while connected -- storage/uptime don't need
 * second-by-second freshness for this screen).
 *
 * newClipCount/newTrackCount/readyToSyncGb (A3, BACKLOG_2026-07-13.md) are
 * computed by diffing the dashcam's live listing (clips via
 * [VideoRepository.observeClips], gpx tracks via a direct manifest listing,
 * same as [SyncEngine]) against [SyncedFileDao]'s known-synced filenames --
 * unioned with what's already present in [SyncStorage]'s local directory, so
 * files downloaded before A3 existed (or outside of a normal sync pass)
 * don't show up as a false "still new" backlog. gpsLock stays Searching
 * until the device's GPS-track source is resolved (see backlog item F2) --
 * gps_fix is always false on hardware without that source.
 */
@Singleton
class RealDashcamRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wifiConnectionObserver: WifiConnectionObserver,
    private val manifestClient: DashcamManifestClient,
    private val videoRepository: VideoRepository,
    private val syncedFileDao: SyncedFileDao
) : DashcamRepository {

    private val gpxDirPath = "gpx/files/GPSLogger"

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeDeviceStatus(): Flow<DeviceStatus> =
        wifiConnectionObserver.observeWifiState()
            // rssiDbm changes constantly (this device lives in a moving car),
            // and WifiState's default equality includes it -- without this,
            // every signal-strength tick would flatMapLatest-cancel and
            // restart the in-flight cgi-bin/status fetch, which could starve
            // it into never completing. Only re-fetch on an actual network
            // identity change.
            .distinctUntilChangedBy { Triple(it.ssid, it.isDashcamNetwork, it.gatewayIp) }
            .flatMapLatest { wifiState ->
            flow {
                val gateway = wifiState.gatewayIp
                if (wifiState.isDashcamNetwork && gateway != null) {
                    // Emit an immediate placeholder rather than letting the
                    // Dashboard sit blank for the full duration of the walk
                    // below (measured 20-50+ seconds on real hardware with a
                    // large recordings folder -- see DeviceStatus.isStatsLoading).
                    // isStatsLoading = true, NOT confident zeros: a fresh
                    // dashcam full of unsynced footage showing "0 new clips"
                    // while this is still loading would be actively
                    // misleading, not just incomplete.
                    emit(toDeviceStatus(wifiState, null, ClipStats.EMPTY, isStatsLoading = true))
                    // fetchStatus is fast (a small single-object endpoint,
                    // not the directory walk below) but was previously only
                    // shown after ALSO waiting on computeClipStats -- emit it
                    // the moment it resolves instead of holding it hostage to
                    // the slow call, so Storage/GPS/Uptime fill in promptly
                    // instead of sitting on the same "0.0 / 0 GB" / "0m"
                    // placeholder for the full walk's duration.
                    val payload = manifestClient.fetchStatus(gateway)
                    emit(toDeviceStatus(wifiState, payload, ClipStats.EMPTY, isStatsLoading = true))
                    val stats = computeClipStats(gateway)
                    emit(toDeviceStatus(wifiState, payload, stats))
                } else {
                    emit(toDeviceStatus(wifiState, null, ClipStats.EMPTY))
                }
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeLteStatus(): Flow<LteStatus?> =
        wifiConnectionObserver.observeWifiState()
            // Same rationale as observeDeviceStatus: don't restart the poll
            // loop on every RSSI tick, only on a real network identity change.
            .distinctUntilChangedBy { Triple(it.ssid, it.isDashcamNetwork, it.gatewayIp) }
            .flatMapLatest { wifiState ->
                val gateway = wifiState.gatewayIp
                if (wifiState.isDashcamNetwork && gateway != null) {
                    flow {
                        while (true) {
                            emit(manifestClient.fetchLteStatus(gateway))
                            delay(LTE_POLL_INTERVAL_MS)
                        }
                    }
                } else {
                    flowOf(null)
                }
            }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeDeviceStats(): Flow<DeviceStats?> =
        wifiConnectionObserver.observeWifiState()
            .distinctUntilChangedBy { Triple(it.ssid, it.isDashcamNetwork, it.gatewayIp) }
            .flatMapLatest { wifiState ->
                val gateway = wifiState.gatewayIp
                if (wifiState.isDashcamNetwork && gateway != null) {
                    flow {
                        while (true) {
                            emit(manifestClient.fetchDeviceStats(gateway))
                            delay(DEVICE_STATS_POLL_INTERVAL_MS)
                        }
                    }
                } else {
                    flowOf(null)
                }
            }

    /** newClips/newTracks/bytes back the Dashboard's "ready to sync" figure
     *  (A3, BACKLOG_2026-07-13.md); roadClips/cabinClips/dualCamEvents are
     *  the full on-device breakdown (all clips currently listed, not just
     *  new ones) backing the richer stat cards added in the Gallery/Dashboard
     *  UX pass -- both computed from the same single [VideoRepository]
     *  listing so there's only one place that walks the clip list per
     *  WiFi-state re-emission. */
    private data class ClipStats(
        val newClips: Int,
        val newTracks: Int,
        val bytes: Long,
        val roadClips: Int,
        val cabinClips: Int,
        val dualCamEvents: Int
    ) {
        companion object {
            val EMPTY = ClipStats(0, 0, 0L, 0, 0, 0)
        }
    }

    /**
     * A8 (BACKLOG_2026-07-13.md): guarded because, unlike manifestClient's
     * own calls (which already degrade to empty/null internally), nothing
     * upstream of this function has an exception boundary of its own -- a
     * Room/SQLite hiccup or an unexpected parsing failure would otherwise
     * propagate out of this flow{} builder and stop observeDeviceStatus()
     * from ever emitting again, leaving the Dashboard permanently blank in a
     * way indistinguishable from a slow real load. Catches Exception, not
     * Throwable/via runCatching -- must rethrow CancellationException so
     * flatMapLatest can still cancel this work when the WiFi state changes
     * again; swallowing it here would fight structured concurrency instead
     * of just handling a real failure.
     */
    private suspend fun computeClipStats(gatewayIp: String): ClipStats = try {
        val alreadySynced = knownSyncedFilenames()
        // fetchRemoteClips, NOT observeClips().first(): the latter would now
        // grab observeClips()'s fast local-first emission (see
        // RealVideoRepository/VideoRepository.fetchRemoteClips doc) and make
        // every clip look "already synced" -- new/road/cabin counts need the
        // true on-device listing.
        val allClips = videoRepository.fetchRemoteClips(gatewayIp)
        val newClips = allClips.filterNot { it.filename in alreadySynced }

        val gpxEntries = manifestClient.list(gatewayIp, gpxDirPath)
            .filter { !it.isDirectory && it.name.endsWith(".gpx") }
        val newTracks = gpxEntries.filterNot { it.name in alreadySynced }

        val bytes = newClips.sumOf { it.sizeBytes } + newTracks.sumOf { it.sizeBytes }

        val dualCamEvents = allClips.groupBy { it.epochSeconds }
            .count { (_, group) ->
                group.any { it.cameraIndex == CameraIndex.ROAD } &&
                    group.any { it.cameraIndex == CameraIndex.CABIN }
            }

        ClipStats(
            newClips = newClips.size,
            newTracks = newTracks.size,
            bytes = bytes,
            roadClips = allClips.count { it.cameraIndex == CameraIndex.ROAD },
            cabinClips = allClips.count { it.cameraIndex == CameraIndex.CABIN },
            dualCamEvents = dualCamEvents
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ClipStats.EMPTY
    }

    private suspend fun knownSyncedFilenames(): Set<String> = withContext(Dispatchers.IO) {
        val fromDb = syncedFileDao.allFilenames()
        val fromDisk = SyncStorage.downloadDir(context).listFiles()?.map { it.name } ?: emptyList()
        (fromDb + fromDisk).toHashSet()
    }

    private fun toDeviceStatus(
        wifiState: WifiState,
        payload: StatusPayload?,
        stats: ClipStats,
        isStatsLoading: Boolean = false
    ): DeviceStatus = DeviceStatus(
        ssid = wifiState.ssid ?: "",
        isConnected = wifiState.isDashcamNetwork,
        storageUsedGb = (payload?.storageUsedKb ?: 0L) / KB_PER_GB,
        storageTotalGb = (payload?.storageTotalKb ?: 0L) / KB_PER_GB,
        gpsLock = if (payload?.gpsFix == true) {
            GpsLockState.Active(satelliteCount = payload.gpsSatellites)
        } else {
            GpsLockState.Searching
        },
        newClipCount = stats.newClips,
        newTrackCount = stats.newTracks,
        readyToSyncGb = stats.bytes / BYTES_PER_GB,
        uptimeSeconds = payload?.uptimeSeconds ?: 0L,
        roadClipCount = stats.roadClips,
        cabinClipCount = stats.cabinClips,
        dualCamEventCount = stats.dualCamEvents,
        isStatsLoading = isStatsLoading
    )
}
