package dev.khanlabs.dashcam.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.khanlabs.dashcam.data.model.GpsTrack
import dev.khanlabs.dashcam.data.network.DashcamManifestClient
import dev.khanlabs.dashcam.data.network.WifiConnectionObserver
import dev.khanlabs.dashcam.data.parser.GpxParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real (not mock) GPX track listing, read from the dashcam's
 * `cgi-bin/browse` endpoint. GPSLogger writes one file per day named
 * `YYYYMMDD.gpx` in `gpx/files/GPSLogger` on the device; each is small
 * enough to fetch whole and parse in memory. When off the dashcam's
 * network entirely, falls back to whatever SyncEngine has already
 * downloaded locally (see [SyncStorage]) rather than an empty list --
 * BACKLOG_2026-07-13.md item A2b.
 */
@Singleton
class RealTripRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wifiConnectionObserver: WifiConnectionObserver,
    private val manifestClient: DashcamManifestClient
) : TripRepository {

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeTracks(): Flow<List<GpsTrack>> =
        wifiConnectionObserver.observeWifiState()
            // See RealDashcamRepository's identical guard: rssiDbm changes
            // constantly in a moving car and is part of WifiState's default
            // equality, so without this every signal tick would
            // flatMapLatest-cancel and restart the GPX fetch/parse.
            .distinctUntilChangedBy { Triple(it.ssid, it.isDashcamNetwork, it.gatewayIp) }
            .flatMapLatest { wifiState ->
            val gateway = wifiState.gatewayIp
            if (!wifiState.isDashcamNetwork || gateway == null) {
                flow { emit(safeTracks { localTracks() }) }
            } else {
                // See RealVideoRepository.observeClips()'s identical fix:
                // emit already-synced local tracks immediately rather than
                // blocking the Map's first frame on fetchTracks's remote
                // listing walk.
                flow {
                    emit(safeTracks { localTracks() })
                    emit(safeTracks { fetchTracks(gateway) })
                }
            }
        }

    /** See RealVideoRepository.safeClips's identical comment/reasoning --
     *  same defense-in-depth boundary, same fallback-to-empty-list contract. */
    private suspend fun safeTracks(block: suspend () -> List<GpsTrack>): List<GpsTrack> = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }

    /** See [RealVideoRepository.fetchClips]'s identical comment: the remote
     *  listing fails soft to empty on a timed-out [DashcamManifestClient.list]
     *  call (e.g. while SyncEngine is concurrently busy on the same httpd),
     *  and `observeTracks` only re-runs this on a WiFi-state change, so a
     *  single failed listing would otherwise blank the Map for the rest of
     *  that network session even with tracks already on disk. */
    private suspend fun fetchTracks(gatewayIp: String): List<GpsTrack> {
        val gpxPath = "gpx/files/GPSLogger"
        val files = manifestClient.list(gatewayIp, gpxPath)
            .filter { !it.isDirectory && it.name.endsWith(".gpx") }
        if (files.isEmpty()) return localTracks()

        val fetched = files.mapNotNull { entry ->
            val bytes = manifestClient.fetchBytes(gatewayIp, "$gpxPath/${entry.name}") ?: return@mapNotNull null
            trackFromGpxBytes(entry.name, bytes)
        }.sortedByDescending { it.date }
        return fetched.ifEmpty { localTracks() }
    }

    private suspend fun localTracks(): List<GpsTrack> = withContext(Dispatchers.IO) {
        val files = SyncStorage.downloadDir(context)
            .listFiles { file -> file.isFile && file.name.endsWith(".gpx") }
            ?: return@withContext emptyList()

        files.mapNotNull { file -> trackFromGpxBytes(file.name, file.readBytes()) }
            .sortedByDescending { it.date }
    }

    private fun trackFromGpxBytes(filename: String, bytes: ByteArray): GpsTrack? {
        // GpxParser has no exception boundary of its own -- a truncated GPX
        // file (e.g. the dashcam lost power mid-write) throws
        // XmlPullParserException/IOException here, which used to propagate
        // uncaught through mapNotNull{} at every call site and crash the app
        // on every subsequent launch (localTracks() always re-parses it).
        val points = runCatching {
            ByteArrayInputStream(bytes).use { GpxParser.parse(it) }
        }.getOrNull() ?: return null
        if (points.isEmpty()) return null
        val date = parseGpxFilenameDate(filename)
            ?: points.first().timeUtc.atZone(ZoneOffset.UTC).toLocalDate()
        return GpsTrack(date = date, points = points)
    }

    private fun parseGpxFilenameDate(filename: String): LocalDate? =
        runCatching {
            LocalDate.parse(filename.removeSuffix(".gpx"), DateTimeFormatter.BASIC_ISO_DATE)
        }.getOrNull()
}
