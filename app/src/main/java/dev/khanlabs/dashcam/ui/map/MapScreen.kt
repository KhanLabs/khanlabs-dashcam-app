package dev.khanlabs.dashcam.ui.map

import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import dev.khanlabs.dashcam.data.model.GpsPoint
import dev.khanlabs.dashcam.data.model.GpsTrack
import dev.khanlabs.dashcam.data.model.TripStats
import dev.khanlabs.dashcam.data.model.speedAt
import dev.khanlabs.dashcam.data.model.toUtcClockString
import dev.khanlabs.dashcam.ui.theme.ElectricCyan
import dev.khanlabs.dashcam.ui.theme.SurfaceDark
import dev.khanlabs.dashcam.ui.theme.TelemetryFontFamily
import dev.khanlabs.dashcam.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.tileprovider.tilesource.TileSourcePolicyException
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import android.graphics.Color as AndroidColor

/** CartoDB's free dark basemap -- matches the OLED brand theme; default OSM Mapnik tiles are light-only. */
private val DarkMatterTileSource = XYTileSource(
    "CartoDBDarkMatter", 0, 20, 256, ".png",
    arrayOf(
        "https://a.basemaps.cartocdn.com/dark_all/",
        "https://b.basemaps.cartocdn.com/dark_all/",
        "https://c.basemaps.cartocdn.com/dark_all/"
    )
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(viewModel: MapViewModel = hiltViewModel()) {
    val tracks by viewModel.tracks.collectAsState()
    val selectedTrack by viewModel.selectedTrack.collectAsState()
    val stats by viewModel.stats.collectAsState()
    val replayIndex by viewModel.replayIndex.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    var tappedPoint by remember { mutableStateOf<GpsPoint?>(null) }
    val sheetState = rememberBottomSheetScaffoldState()
    val mapViewHolder = remember { mutableStateOf<MapView?>(null) }

    if (tracks.isEmpty()) {
        NoTripsEmptyState()
        return
    }

    BottomSheetScaffold(
        scaffoldState = sheetState,
        sheetPeekHeight = 260.dp,
        sheetContainerColor = SurfaceDark,
        sheetContent = {
            TripBottomSheetContent(
                tracks = tracks,
                onDateSelected = viewModel::selectDate,
                stats = stats,
                track = selectedTrack,
                replayIndex = replayIndex,
                isPlaying = isPlaying,
                onReplayIndexChange = viewModel::setReplayIndex,
                onTogglePlay = viewModel::togglePlay,
                tappedPoint = tappedPoint,
                mapView = mapViewHolder.value
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            TrackMapView(
                track = selectedTrack,
                replayIndex = replayIndex,
                onPolylineTap = { point -> tappedPoint = point },
                mapViewHolder = mapViewHolder
            )
        }
    }
}

/**
 * Shown instead of the map when there are no GPS tracks at all. Without
 * this, [TrackMapView] used to render anyway with no [MapView.setCenter]
 * call ever made -- defaulting to OSMDroid's (0,0) origin ("Null Island",
 * open ocean) at zoom 14.5, a dark, empty-looking tile that read as a
 * broken/blank map rather than "no trips yet." Confirmed both states live:
 * this empty state with no local `.gpx` files, and a real rendered track
 * (CartoDB tiles + polyline + markers, correctly centered) after planting a
 * synthetic GPX file -- see IMPLEMENTATION_NOTES.md.
 */
@Composable
private fun NoTripsEmptyState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
    ) {
        Text(text = "No GPS trips yet", style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Map,
                contentDescription = null,
                tint = ElectricCyan,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "This dashcam doesn't have GPS track logging set up yet.",
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

/** Pure bookkeeping for [TrackMapView]'s `update` lambda -- deliberately
 *  NOT Compose [androidx.compose.runtime.State]/`mutableStateOf`, since
 *  nothing needs to observe these fields for recomposition; they're just
 *  memory for the imperative AndroidView bridge, persisted across
 *  recompositions via `remember { MapOverlayState() }`. */
private class MapOverlayState {
    var track: GpsTrack? = null
    var replayMarker: Marker? = null
    var geoPoints: List<GeoPoint> = emptyList()
}

@Composable
private fun TrackMapView(
    track: GpsTrack?,
    replayIndex: Int,
    onPolylineTap: (GpsPoint) -> Unit,
    mapViewHolder: MutableState<MapView?>
) {
    DisposableEffect(Unit) {
        onDispose { mapViewHolder.value?.onDetach() }
    }

    val overlayState = remember { MapOverlayState() }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            MapView(ctx).apply {
                setTileSource(DarkMatterTileSource)
                setMultiTouchControls(true)
                controller.setZoom(14.5)
                mapViewHolder.value = this
            }
        },
        update = { mapView ->
            if (track == null || track.points.isEmpty()) {
                mapView.overlays.clear()
                overlayState.track = null
                overlayState.replayMarker = null
                mapView.invalidate()
                return@AndroidView
            }

            // Polyline + start/end markers are static for a given track --
            // only rebuild them when the track itself changed, not on every
            // replayIndex tick (every 500ms during playback, previously
            // reallocating the whole overlay set + drawables that often).
            if (overlayState.track != track) {
                mapView.overlays.clear()
                val geoPoints = track.points.map { GeoPoint(it.lat, it.lon) }
                overlayState.geoPoints = geoPoints

                val polyline = Polyline().apply {
                    setPoints(geoPoints)
                    outlinePaint.color = AndroidColor.CYAN
                    outlinePaint.strokeWidth = 10f
                    setOnClickListener { _, _, eventPos ->
                        val nearest = track.points.indices.minByOrNull { i ->
                            val dLat = geoPoints[i].latitude - eventPos.latitude
                            val dLon = geoPoints[i].longitude - eventPos.longitude
                            dLat * dLat + dLon * dLon
                        }
                        nearest?.let { onPolylineTap(track.points[it]) }
                        true
                    }
                }
                mapView.overlays.add(polyline)

                mapView.overlays.add(
                    Marker(mapView).apply {
                        position = geoPoints.first()
                        icon = dotDrawable(AndroidColor.GREEN)
                        title = "Start"
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    }
                )
                mapView.overlays.add(
                    Marker(mapView).apply {
                        position = geoPoints.last()
                        icon = dotDrawable(AndroidColor.RED)
                        title = "End"
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    }
                )

                val replayMarker = Marker(mapView).apply {
                    icon = dotDrawable(AndroidColor.parseColor("#00E5FF"))
                    title = "Replay position"
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                }
                mapView.overlays.add(replayMarker)
                overlayState.replayMarker = replayMarker
                overlayState.track = track
            }

            val geoPoints = overlayState.geoPoints
            val safeIndex = replayIndex.coerceIn(0, geoPoints.size - 1)
            val position = geoPoints[safeIndex]
            overlayState.replayMarker?.position = position
            mapView.controller.setCenter(position)
            mapView.invalidate()
        }
    )
}

private fun dotDrawable(color: Int): Drawable {
    return GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(3, AndroidColor.WHITE)
        setSize(36, 36)
    }
}

@Composable
private fun TripBottomSheetContent(
    tracks: List<GpsTrack>,
    onDateSelected: (Int) -> Unit,
    stats: TripStats?,
    track: GpsTrack?,
    replayIndex: Int,
    isPlaying: Boolean,
    onReplayIndexChange: (Int) -> Unit,
    onTogglePlay: () -> Unit,
    tappedPoint: GpsPoint?,
    mapView: MapView?
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Box {
            OutlinedButton(onClick = { menuExpanded = true }) {
                Text(text = track?.date?.toString() ?: "No trips", fontFamily = TelemetryFontFamily)
                Icon(Icons.Filled.ArrowDropDown, contentDescription = "Select date")
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                tracks.forEachIndexed { index, t ->
                    DropdownMenuItem(
                        text = { Text(t.date.toString()) },
                        onClick = {
                            onDateSelected(index)
                            menuExpanded = false
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (stats != null) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatItem("Distance", "%.1f km".format(stats.distanceKm))
                StatItem("Duration", formatTripDuration(stats.durationSeconds))
                StatItem("Max Speed", "%.0f km/h".format(stats.maxSpeedKmh))
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        if (track != null && track.points.isNotEmpty()) {
            Slider(
                value = replayIndex.toFloat(),
                onValueChange = { onReplayIndexChange(it.toInt()) },
                valueRange = 0f..(track.points.size - 1).coerceAtLeast(1).toFloat()
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onTogglePlay) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = "Replay trip",
                        tint = ElectricCyan
                    )
                }
                val point = track.points.getOrNull(replayIndex)
                if (point != null) {
                    Text(
                        text = "${point.timeUtc.toUtcClockString()} UTC  •  " +
                            "${"%.0f".format(track.speedAt(replayIndex))} km/h",
                        fontFamily = TelemetryFontFamily,
                        color = TextSecondary
                    )
                }
            }
        }

        tappedPoint?.let { point ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Pin: ${point.timeUtc.toUtcClockString()} UTC  •  " +
                    "${"%.4f".format(point.lat)}, ${"%.4f".format(point.lon)}",
                fontFamily = TelemetryFontFamily,
                color = ElectricCyan
            )
        }

        OfflineMapControl(mapView = mapView, track = track)
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        Text(text = value, fontFamily = TelemetryFontFamily, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun formatTripDuration(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

/** Covers the app's default 14.5 zoom plus room to zoom in on review, without
 *  the tile count exploding on a long trip -- see IMPLEMENTATION_NOTES.md. */
private const val OFFLINE_MIN_ZOOM = 13
private const val OFFLINE_MAX_ZOOM = 17

/** Rough, clearly-labeled estimate only -- osmdroid doesn't report real tile
 *  byte sizes up front; this is just enough to warn before a big download. */
private const val ESTIMATED_KB_PER_TILE = 20
private const val LARGE_AREA_TILE_WARNING_THRESHOLD = 3000

private sealed interface OfflineDownloadState {
    data object Idle : OfflineDownloadState
    data object Estimating : OfflineDownloadState
    data class Confirm(val tileCount: Int) : OfflineDownloadState
    data class Downloading(val done: Int, val total: Int) : OfflineDownloadState
    data object Done : OfflineDownloadState
    data class Failed(val message: String) : OfflineDownloadState
}

/**
 * Lets the user pre-fetch this trip's route tiles for offline viewing.
 * osmdroid already caches every tile it renders to disk automatically
 * (see KhanLabsDashcamApp's Configuration.getInstance().load(...)) -- this
 * button just proactively fetches a route's tiles ahead of time instead of
 * relying on having viewed them live. Downloads over whatever internet
 * connection the phone currently has (CartoDB's CDN, not the dashcam's
 * hotspot, which has no internet uplink) -- copy below says so explicitly.
 */
@Composable
private fun OfflineMapControl(mapView: MapView?, track: GpsTrack?) {
    var state by remember(track?.date) { mutableStateOf<OfflineDownloadState>(OfflineDownloadState.Idle) }
    var activeTask by remember(track?.date) { mutableStateOf<CacheManager.CacheManagerTask?>(null) }
    val scope = rememberCoroutineScope()

    // A running download is a real AsyncTask, not tied to this composable's
    // lifecycle or coroutine scope -- without this it keeps hitting CartoDB
    // (and stays un-cancelable, since the Cancel button is gone) after
    // navigating away from Map or switching the selected trip date.
    DisposableEffect(track?.date) {
        onDispose { activeTask?.cancel(true) }
    }

    if (mapView == null || track == null || track.points.isEmpty()) return

    fun openCacheManager(): CacheManager? = try {
        CacheManager(mapView)
    } catch (e: TileSourcePolicyException) {
        state = OfflineDownloadState.Failed("This map style doesn't allow offline downloads.")
        null
    }

    Spacer(modifier = Modifier.height(12.dp))

    when (val s = state) {
        is OfflineDownloadState.Idle -> {
            OutlinedButton(onClick = {
                state = OfflineDownloadState.Estimating
                scope.launch(Dispatchers.IO) {
                    val manager = openCacheManager() ?: return@launch
                    val points = ArrayList(track.points.map { GeoPoint(it.lat, it.lon) })
                    val count = manager.possibleTilesCovered(points, OFFLINE_MIN_ZOOM, OFFLINE_MAX_ZOOM)
                    state = OfflineDownloadState.Confirm(count)
                }
            }) {
                Icon(Icons.Filled.CloudDownload, contentDescription = null, tint = ElectricCyan)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Download offline map")
            }
        }

        is OfflineDownloadState.Estimating -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = ElectricCyan)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Checking map area...", color = TextSecondary)
            }
        }

        is OfflineDownloadState.Confirm -> {
            val estMb = (s.tileCount * ESTIMATED_KB_PER_TILE) / 1024.0
            AlertDialog(
                onDismissRequest = { state = OfflineDownloadState.Idle },
                title = { Text("Download offline map?") },
                text = {
                    Text(
                        "Uses your phone's internet connection (not the dashcam) to fetch " +
                            "~${s.tileCount} map tiles (about ${"%.0f".format(estMb.coerceAtLeast(1.0))} MB). " +
                            "They'll stay available offline on this phone afterward." +
                            if (s.tileCount > LARGE_AREA_TILE_WARNING_THRESHOLD) {
                                " This route covers a large area, so it may take a while."
                            } else ""
                    )
                },
                confirmButton = {
                    OutlinedButton(onClick = {
                        val manager = openCacheManager() ?: return@OutlinedButton
                        val points = ArrayList(track.points.map { GeoPoint(it.lat, it.lon) })
                        state = OfflineDownloadState.Downloading(0, s.tileCount)
                        activeTask = manager.downloadAreaAsyncNoUI(
                            mapView.context,
                            points,
                            OFFLINE_MIN_ZOOM,
                            OFFLINE_MAX_ZOOM,
                            object : CacheManager.CacheManagerCallback {
                                override fun onTaskComplete() {
                                    state = OfflineDownloadState.Done
                                    activeTask = null
                                }

                                override fun updateProgress(progress: Int, currentZoomLevel: Int, zoomMin: Int, zoomMax: Int) {
                                    val total = (state as? OfflineDownloadState.Downloading)?.total ?: s.tileCount
                                    state = OfflineDownloadState.Downloading(progress, total)
                                }

                                override fun downloadStarted() = Unit

                                override fun setPossibleTilesInArea(total: Int) {
                                    state = OfflineDownloadState.Downloading(0, total)
                                }

                                override fun onTaskFailed(errors: Int) {
                                    state = OfflineDownloadState.Failed("Download stopped after $errors tile error(s).")
                                    activeTask = null
                                }
                            }
                        )
                    }) { Text("Download") }
                },
                dismissButton = {
                    OutlinedButton(onClick = { state = OfflineDownloadState.Idle }) { Text("Cancel") }
                }
            )
        }

        is OfflineDownloadState.Downloading -> {
            Column {
                Text("Downloading offline map... ${s.done}/${s.total}", color = TextSecondary)
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { if (s.total > 0) s.done.toFloat() / s.total else 0f },
                    modifier = Modifier.fillMaxWidth(),
                    color = ElectricCyan
                )
                OutlinedButton(onClick = {
                    activeTask?.cancel(true)
                    activeTask = null
                    state = OfflineDownloadState.Idle
                }) { Text("Cancel download") }
            }
        }

        is OfflineDownloadState.Done -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = ElectricCyan)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Available offline", color = TextSecondary)
            }
        }

        is OfflineDownloadState.Failed -> {
            Column {
                Text(s.message, color = TextSecondary)
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedButton(onClick = { state = OfflineDownloadState.Idle }) { Text("Try again") }
            }
        }
    }
}
