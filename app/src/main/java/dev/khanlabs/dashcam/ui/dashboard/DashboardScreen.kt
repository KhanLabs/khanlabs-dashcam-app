package dev.khanlabs.dashcam.ui.dashboard

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.SignalWifi4Bar
import androidx.compose.material.icons.filled.SignalWifiOff
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.khanlabs.dashcam.data.model.DeviceStatus
import dev.khanlabs.dashcam.data.model.GpsLockState
import dev.khanlabs.dashcam.data.network.ProbeResult
import dev.khanlabs.dashcam.data.network.WifiState
import dev.khanlabs.dashcam.data.repository.SyncProgress
import dev.khanlabs.dashcam.data.repository.SyncStopReason
import dev.khanlabs.dashcam.ui.theme.ElectricCyan
import dev.khanlabs.dashcam.ui.theme.SurfaceDark
import dev.khanlabs.dashcam.ui.theme.TelemetryFontFamily
import dev.khanlabs.dashcam.ui.theme.TextSecondary
import dev.khanlabs.dashcam.ui.theme.WarningAmber
import kotlin.math.roundToInt

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel = hiltViewModel(),
    onOpenLte: () -> Unit = {},
    onOpenDeviceStats: () -> Unit = {},
    onOpenLiveView: () -> Unit = {},
    onOpenVolume: () -> Unit = {}
) {
    val status by viewModel.deviceStatus.collectAsState()
    val wifiState by viewModel.wifiState.collectAsState()
    val syncStatus by viewModel.syncStatus.collectAsState()
    val lastSuccessfulSyncAt by viewModel.lastSuccessfulSyncAt.collectAsState()
    val keepScreenOnDuringSync by viewModel.keepScreenOnDuringSync.collectAsState()
    val context = LocalContext.current
    val syncing = syncStatus is SyncStatus.Probing || syncStatus is SyncStatus.Syncing

    // Real "Keep screen on during sync" setting: only forces the screen on
    // for the sync's actual duration, and always releases on dispose so
    // navigating away mid-sync can't leave the screen stuck always-on.
    val view = LocalView.current
    DisposableEffect(syncing, keepScreenOnDuringSync) {
        view.keepScreenOn = syncing && keepScreenOnDuringSync
        onDispose { view.keepScreenOn = false }
    }
    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasLocationPermission = granted
        if (granted) viewModel.onWifiPermissionGranted()
    }

    // Covers granting the permission from system Settings while this screen
    // was paused (in-app grant is handled by the launcher callback above).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val granted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                if (granted != hasLocationPermission) {
                    hasLocationPermission = granted
                    if (granted) viewModel.onWifiPermissionGranted()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        TopBar(
            wifiState = wifiState,
            hasLocationPermission = hasLocationPermission,
            onRequestPermission = { permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }
        )
        Spacer(modifier = Modifier.height(20.dp))
        status?.let {
            SyncStatusRow(
                status = it,
                syncStatus = syncStatus,
                onSyncClick = viewModel::onSyncClick,
                onCancelSyncClick = viewModel::onCancelSyncClick
            )
        }
        Spacer(modifier = Modifier.height(20.dp))
        status?.let {
            SectionLabel("Device")
            Spacer(modifier = Modifier.height(10.dp))
            DeviceStatsGrid(it, wifiState, lastSuccessfulSyncAt)
            Spacer(modifier = Modifier.height(12.dp))
            CellularEntryCard(onClick = onOpenLte)
            Spacer(modifier = Modifier.height(12.dp))
            PerformanceEntryCard(onClick = onOpenDeviceStats)
            Spacer(modifier = Modifier.height(12.dp))
            LiveViewEntryCard(onClick = onOpenLiveView)
            Spacer(modifier = Modifier.height(12.dp))
            VolumeEntryCard(onClick = onOpenVolume)
            Spacer(modifier = Modifier.height(24.dp))
            SectionLabel("Footage on dashcam")
            Spacer(modifier = Modifier.height(10.dp))
            FootageStatsGrid(it)
        }
        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun SectionLabel(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.bodyMedium,
        color = TextSecondary
    )
}

@Composable
private fun TopBar(
    wifiState: WifiState,
    hasLocationPermission: Boolean,
    onRequestPermission: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "KL",
            color = ElectricCyan,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(modifier = Modifier.padding(start = 8.dp))
        Column {
            Text(text = "KhanLabs Dashcam", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (wifiState.isDashcamNetwork) Color(0xFF2ECC71) else TextSecondary)
                )
                Spacer(modifier = Modifier.padding(start = 6.dp))
                Text(
                    text = when {
                        !hasLocationPermission -> "Grant location permission to detect network"
                        wifiState.isDashcamNetwork -> "Connected to ${wifiState.ssid}"
                        wifiState.ssid != null -> "On \"${wifiState.ssid}\" (not a dashcam)"
                        else -> "Not connected to WiFi"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    fontFamily = TelemetryFontFamily
                )
            }
            if (!hasLocationPermission) {
                TextButton(onClick = onRequestPermission) {
                    Text("Grant permission", color = ElectricCyan)
                }
            }
        }
    }
}

/** Replaces the old giant circular "SYNC" hero button now that
 *  auto-sync-on-WiFi-join handles the common case silently in the
 *  background -- a full-width tap target no longer earns the space a manual
 *  action needed when it was the primary way to get footage onto the phone.
 *  This keeps exactly the same information and the same real per-clip
 *  progress panel/cancel action, just as a slim row instead of a 140dp
 *  hero, with an explicit "Sync now" button for the rare case someone wants
 *  to force it (e.g. right after auto-sync already ran and finished, or
 *  before auto-sync has had a chance to fire yet). */
@Composable
private fun SyncStatusRow(
    status: DeviceStatus,
    syncStatus: SyncStatus,
    onSyncClick: () -> Unit,
    onCancelSyncClick: () -> Unit
) {
    val syncing = syncStatus is SyncStatus.Probing || syncStatus is SyncStatus.Syncing
    val progress = (syncStatus as? SyncStatus.Syncing)?.progress

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(SurfaceDark)
                .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Sync,
                contentDescription = null,
                tint = ElectricCyan,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.padding(start = 10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    // isStatsLoading: the real counts require a full
                    // directory walk of the dashcam's SD card that's been
                    // measured taking 20-50+ seconds (DeviceStatus.
                    // isStatsLoading doc). Showing "0 New Clips" during that
                    // window would read as "nothing to sync" on a dashcam
                    // that may be full of unsynced footage -- a confident
                    // lie, not a neutral placeholder.
                    text = if (status.isStatsLoading) {
                        "Checking dashcam..."
                    } else {
                        "${status.newClipCount} New Clips • ${status.newTrackCount} New Track • " +
                            "%.1f GB Ready".format(status.readyToSyncGb)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = TelemetryFontFamily
                )
                SyncResultText(syncStatus, modifier = Modifier.padding(top = 2.dp))
            }
            if (!syncing) {
                TextButton(onClick = onSyncClick) {
                    Text("Sync now", color = ElectricCyan)
                }
            }
        }
        if (progress != null) {
            Spacer(modifier = Modifier.height(8.dp))
            SyncProgressPanel(progress, onCancelSyncClick)
        } else if (syncing) {
            // Probing state: no per-file progress yet (that only exists
            // once SyncEngine.syncNow() actually starts transferring), but
            // the row should still show *something* is happening rather
            // than sitting static while the connection check runs.
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = ElectricCyan,
                trackColor = ElectricCyan.copy(alpha = 0.15f)
            )
        }
    }
}

/** Real per-clip transfer feedback while [SyncEngine.syncNow] is running --
 *  which file, at what speed, how much of the whole sync is left, and a
 *  real way to stop it. Replaces the old static "Downloading new clips and
 *  tracks..." line, which gave no indication anything was actually
 *  happening during a real multi-minute sync. */
@Composable
private fun SyncProgressPanel(progress: SyncProgress, onCancelSyncClick: () -> Unit) {
    val fileFraction = if (progress.currentFileBytesTotal > 0) {
        (progress.currentFileBytesDone.toFloat() / progress.currentFileBytesTotal.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = progress.currentClipLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = TelemetryFontFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${progress.currentFileIndex} / ${progress.totalFiles}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    fontFamily = TelemetryFontFamily
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { fileFraction },
                color = ElectricCyan,
                trackColor = ElectricCyan.copy(alpha = 0.15f),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formatSpeed(progress.speedBytesPerSec),
                    style = MaterialTheme.typography.labelSmall,
                    color = ElectricCyan,
                    fontFamily = TelemetryFontFamily
                )
                Text(
                    text = "${formatBytes(progress.overallBytesDone)} / ${formatBytes(progress.overallBytesTotal)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    fontFamily = TelemetryFontFamily
                )
                IconButton(onClick = onCancelSyncClick, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Cancel sync",
                        tint = WarningAmber,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024) "%.1f GB".format(mb / 1024) else "%.0f MB".format(mb)
}

private fun formatSpeed(bytesPerSec: Long): String {
    if (bytesPerSec <= 0) return "-- MB/s"
    val mbPerSec = bytesPerSec / (1024.0 * 1024.0)
    return if (mbPerSec >= 1) "%.1f MB/s".format(mbPerSec) else "%.0f KB/s".format(bytesPerSec / 1024.0)
}

@Composable
private fun SyncResultText(syncStatus: SyncStatus, modifier: Modifier = Modifier) {
    if (syncStatus is SyncStatus.Idle) return
    val (text, color) = when (syncStatus) {
        SyncStatus.Idle -> "" to TextSecondary
        SyncStatus.Probing -> "Checking connection to the dashcam..." to TextSecondary
        is SyncStatus.Syncing -> "Starting sync..." to TextSecondary
        is SyncStatus.Done -> when (val result = syncStatus.result) {
            ProbeResult.NotOnDashcamNetwork ->
                "Not on a Dash-* network — join the dashcam's hotspot first" to WarningAmber
            is ProbeResult.Reachable -> {
                val summary = syncStatus.downloadSummary
                when {
                    summary == null ->
                        "Reachable at ${result.gatewayIp}:8080 (${syncStatus.at.toSimpleString()}) — sync failed" to WarningAmber
                    summary.stopReason == SyncStopReason.ALREADY_SYNCING ->
                        "Already syncing in the background — try again in a moment" to TextSecondary
                    summary.stopReason == SyncStopReason.NETWORK_CHANGED ->
                        "Stopped — left the dashcam's network (${syncStatus.at.toSimpleString()}) — " +
                            "${summary.downloadedClips} clips, ${summary.downloadedTracks} tracks downloaded before stopping" to WarningAmber
                    summary.stopReason == SyncStopReason.USER_CANCELLED ->
                        "Cancelled (${syncStatus.at.toSimpleString()}) — ${summary.downloadedClips} clips, " +
                            "${summary.downloadedTracks} tracks downloaded before stopping" to WarningAmber
                    summary.failedCount > 0 ->
                        "Synced (${syncStatus.at.toSimpleString()}) — ${summary.downloadedClips} clips, " +
                            "${summary.downloadedTracks} tracks, ${summary.failedCount} failed" to WarningAmber
                    summary.downloadedClips == 0 && summary.downloadedTracks == 0 ->
                        "Up to date (${syncStatus.at.toSimpleString()})" to ElectricCyan
                    else ->
                        "Synced (${syncStatus.at.toSimpleString()}) — ${summary.downloadedClips} clips, " +
                            "${summary.downloadedTracks} tracks" to ElectricCyan
                }
            }
            is ProbeResult.Unreachable ->
                "Couldn't reach ${result.gatewayIp}:8080 — ${result.reason}" to WarningAmber
        }
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        fontFamily = TelemetryFontFamily,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.fillMaxWidth()
    )
}

private fun java.time.LocalTime.toSimpleString(): String =
    "%02d:%02d:%02d".format(hour, minute, second)

@Composable
private fun DeviceStatsGrid(
    status: DeviceStatus,
    wifiState: WifiState,
    lastSuccessfulSyncAt: java.time.LocalTime?
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatCard(modifier = Modifier.weight(1f).fillMaxHeight(), label = "Storage") {
                // storageTotalGb is 0 when off-network (no device data yet) --
                // guard against NaN, which a real endpoint can produce but the
                // old fixture data never could.
                val fraction = if (status.storageTotalGb > 0.0) {
                    (status.storageUsedGb / status.storageTotalGb).toFloat()
                } else {
                    0f
                }
                Text(
                    text = "%.1f / %d GB".format(status.storageUsedGb, status.storageTotalGb.roundToInt()),
                    fontFamily = TelemetryFontFamily,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp)),
                    color = ElectricCyan,
                    trackColor = SurfaceDark
                )
            }
            StatCard(modifier = Modifier.weight(1f).fillMaxHeight(), label = "GPS Lock") {
                val active = status.gpsLock is GpsLockState.Active
                Icon(
                    imageVector = if (active) Icons.Filled.GpsFixed else Icons.Filled.GpsNotFixed,
                    contentDescription = null,
                    tint = if (active) ElectricCyan else WarningAmber
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = when (val lock = status.gpsLock) {
                        is GpsLockState.Active -> lock.satelliteCount?.let { "Active ($it Sats)" } ?: "Active"
                        GpsLockState.Searching -> "Searching..."
                    },
                    fontFamily = TelemetryFontFamily,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatCard(modifier = Modifier.weight(1f).fillMaxHeight(), label = "Last Sync") {
                Icon(imageVector = Icons.Filled.History, contentDescription = null, tint = ElectricCyan)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = lastSuccessfulSyncAt?.toSimpleString() ?: "Never",
                    fontFamily = TelemetryFontFamily,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            StatCard(modifier = Modifier.weight(1f).fillMaxHeight(), label = "Signal") {
                // rssiDbm is the phone's current WiFi radio signal, whatever
                // network that is -- gating on isDashcamNetwork keeps this
                // card honest as "signal to the dashcam," not "signal to
                // whatever WiFi the phone happens to be on right now" (found
                // live on the emulator: showed "-50 dBm Excellent" while
                // simultaneously reading "Not connected" and SSID "--").
                val rssi = wifiState.rssiDbm.takeIf { wifiState.isDashcamNetwork }
                Icon(
                    imageVector = if (rssi != null) Icons.Filled.SignalWifi4Bar else Icons.Filled.SignalWifiOff,
                    contentDescription = null,
                    tint = if (rssi != null) ElectricCyan else TextSecondary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = rssi?.let { "$it dBm (${signalQuality(it)})" } ?: "--",
                    fontFamily = TelemetryFontFamily,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatCard(modifier = Modifier.weight(1f).fillMaxHeight(), label = "Dashcam Uptime") {
                Icon(imageVector = Icons.Filled.Timer, contentDescription = null, tint = ElectricCyan)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (status.isConnected) formatUptime(status.uptimeSeconds) else "--",
                    fontFamily = TelemetryFontFamily,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            StatCard(modifier = Modifier.weight(1f).fillMaxHeight(), label = "SSID") {
                Icon(
                    imageVector = if (status.isConnected) Icons.Filled.SignalWifi4Bar else Icons.Filled.SignalWifiOff,
                    contentDescription = null,
                    tint = if (status.isConnected) ElectricCyan else TextSecondary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = status.ssid.ifBlank { "--" },
                    fontFamily = TelemetryFontFamily,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1
                )
            }
        }
    }
}

/** Entry point to the live LTE/cellular diagnostics screen (SIM, PLMN,
 *  mode, signal, APN, IMEI). Deliberately no live preview here -- that
 *  screen already runs its own 2.5s poll loop while it's on-screen; mirroring
 *  a summary of it here would mean two independent pollers hitting
 *  cgi-bin/lte-status whenever both are visible, for a value the user would
 *  see in full one tap later anyway. */
@Composable
private fun CellularEntryCard(onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(imageVector = Icons.Filled.CellTower, contentDescription = null, tint = ElectricCyan)
            Spacer(modifier = Modifier.padding(start = 10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Cellular", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "SIM · Signal · APN · IMEI — live stats",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
            Icon(imageVector = Icons.Filled.ChevronRight, contentDescription = null, tint = TextSecondary)
        }
    }
}

/** Entry point to the live CPU/thermal/memory diagnostics screen. Same
 *  no-live-preview rationale as [CellularEntryCard]: that screen runs its
 *  own poll loop while on-screen, so mirroring a summary here would mean a
 *  second independent poller hitting cgi-bin/device-stats whenever both are
 *  visible, for numbers the user sees in full one tap later anyway. */
@Composable
private fun PerformanceEntryCard(onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(imageVector = Icons.Filled.Speed, contentDescription = null, tint = ElectricCyan)
            Spacer(modifier = Modifier.padding(start = 10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Device Performance", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "CPU · Clock · Memory · Temps — live stats",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
            Icon(imageVector = Icons.Filled.ChevronRight, contentDescription = null, tint = TextSecondary)
        }
    }
}

/** Entry point to real live video from the dashcam's cameras over its own
 *  hotspot -- unlike the Cellular/Performance entry cards, this genuinely
 *  can't show a live preview here: streaming ties up LiveStreamServer's
 *  single client slot and forces one specific camera's encoder on
 *  dashcam-side, so it only makes sense while the destination screen is
 *  actually the thing driving it, not duplicated behind a dashboard tile. */
@Composable
private fun LiveViewEntryCard(onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(imageVector = Icons.Filled.Videocam, contentDescription = null, tint = ElectricCyan)
            Spacer(modifier = Modifier.padding(start = 10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Live View", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "Road · Cabin — real-time video over the hotspot",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
            Icon(imageVector = Icons.Filled.ChevronRight, contentDescription = null, tint = TextSecondary)
        }
    }
}

/** Entry point to the speaker volume control (big number, up/down arrows).
 *  No live preview -- the current value only matters while adjusting it,
 *  same reasoning as skipping a preview on the Cellular/Performance cards. */
@Composable
private fun VolumeEntryCard(onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(imageVector = Icons.Filled.VolumeUp, contentDescription = null, tint = ElectricCyan)
            Spacer(modifier = Modifier.padding(start = 10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Speaker Volume", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "Adjust the dashcam's beep/alert volume",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
            Icon(imageVector = Icons.Filled.ChevronRight, contentDescription = null, tint = TextSecondary)
        }
    }
}

@Composable
private fun FootageStatsGrid(status: DeviceStatus) {
    // Same rationale as the Hero section's "Checking dashcam..." text: these
    // counts come from the same slow directory walk (DeviceStatus.isStatsLoading),
    // so "0 clips" here during that window would be just as misleading --
    // "--" matches this screen's existing not-yet-known convention (Signal,
    // SSID, Uptime cards above).
    fun statText(value: Int): String = if (status.isStatsLoading) "--" else "$value"

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatCard(modifier = Modifier.weight(1f).fillMaxHeight(), label = "Road Cam") {
                Icon(imageVector = Icons.Filled.Videocam, contentDescription = null, tint = ElectricCyan)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (status.isStatsLoading) "--" else "${status.roadClipCount} clips",
                    fontFamily = TelemetryFontFamily,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            StatCard(modifier = Modifier.weight(1f).fillMaxHeight(), label = "Cabin Cam") {
                Icon(imageVector = Icons.Filled.Videocam, contentDescription = null, tint = ElectricCyan)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (status.isStatsLoading) "--" else "${status.cabinClipCount} clips",
                    fontFamily = TelemetryFontFamily,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatCard(modifier = Modifier.weight(1f).fillMaxHeight(), label = "Dual-Cam Events") {
                Icon(imageVector = Icons.Filled.ViewColumn, contentDescription = null, tint = ElectricCyan)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = statText(status.dualCamEventCount),
                    fontFamily = TelemetryFontFamily,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            StatCard(modifier = Modifier.weight(1f).fillMaxHeight(), label = "Total Clips") {
                Icon(imageVector = Icons.Filled.Videocam, contentDescription = null, tint = ElectricCyan)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = statText(status.roadClipCount + status.cabinClipCount),
                    fontFamily = TelemetryFontFamily,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

private fun signalQuality(rssiDbm: Int): String = when {
    rssiDbm >= -50 -> "Excellent"
    rssiDbm >= -60 -> "Good"
    rssiDbm >= -70 -> "Fair"
    else -> "Weak"
}

/** status.uptimeSeconds is 0 both "genuinely just booted" and "not
 *  connected/no payload yet" -- callers gate display on [DeviceStatus.isConnected]
 *  so this only ever renders a real value. */
private fun formatUptime(totalSeconds: Long): String {
    val days = totalSeconds / 86_400
    val hours = (totalSeconds % 86_400) / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${minutes}m"
        else -> "${minutes}m"
    }
}

@Composable
private fun StatCard(
    modifier: Modifier = Modifier,
    label: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = SurfaceDark)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}
