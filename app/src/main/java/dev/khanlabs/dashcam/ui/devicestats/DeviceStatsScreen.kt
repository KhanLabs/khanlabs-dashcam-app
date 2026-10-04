package dev.khanlabs.dashcam.ui.devicestats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.khanlabs.dashcam.data.network.DeviceStats
import dev.khanlabs.dashcam.ui.theme.ElectricCyan
import dev.khanlabs.dashcam.ui.theme.SurfaceDark
import dev.khanlabs.dashcam.ui.theme.TelemetryFontFamily
import dev.khanlabs.dashcam.ui.theme.TextPrimary
import dev.khanlabs.dashcam.ui.theme.TextSecondary
import dev.khanlabs.dashcam.ui.theme.WarningAmber

@Composable
fun DeviceStatsScreen(onBack: () -> Unit, viewModel: DeviceStatsViewModel = hiltViewModel()) {
    val deviceStats by viewModel.deviceStats.collectAsStateWithLifecycle()
    val wifiState by viewModel.wifiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(text = "Device Performance", style = MaterialTheme.typography.titleMedium)
        }

        when {
            !wifiState.isDashcamNetwork -> EmptyState(
                message = "Not connected to the dashcam's hotspot.\nJoin its Dash-* WiFi network to see live performance stats."
            )
            deviceStats == null -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = ElectricCyan)
            }
            else -> DeviceStatsDetail(deviceStats!!)
        }
    }
}

@Composable
private fun EmptyState(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.WifiTethering, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(48.dp))
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
        }
    }
}

@Composable
private fun DeviceStatsDetail(stats: DeviceStats) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        RecordingBanner(stats.isRecording)
        Spacer(modifier = Modifier.height(16.dp))

        DeviceStatCard(label = "CPU", icon = Icons.Filled.Speed) {
            val usedPct = stats.cpuUsedPct
            Text(
                text = usedPct?.let { "$it%" } ?: "--",
                fontFamily = TelemetryFontFamily,
                color = TextPrimary,
                style = MaterialTheme.typography.headlineMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { (usedPct ?: 0) / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp)),
                color = if ((usedPct ?: 0) >= 85) WarningAmber else ElectricCyan,
                trackColor = SurfaceDark
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "User ${stats.cpuUserPct ?: "--"}% · Sys ${stats.cpuSysPct ?: "--"}% · IRQ ${stats.cpuIrqPct ?: "--"}% (of ${stats.cpuCoreCount} cores)",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                fontFamily = TelemetryFontFamily
            )
        }
        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DeviceStatCard(modifier = Modifier.weight(1f).fillMaxHeight(), label = "Clock Speed", icon = Icons.Filled.Speed) {
                val curGhz = stats.cpuFreqKhz.firstOrNull()?.let { it / 1_000_000f }
                val maxGhz = stats.cpuFreqMaxKhz?.let { it / 1_000_000f }
                Text(
                    text = curGhz?.let { "%.2f GHz".format(it) } ?: "--",
                    fontFamily = TelemetryFontFamily,
                    color = TextPrimary,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = maxGhz?.let { "of %.2f GHz max".format(it) } ?: "--",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    fontFamily = TelemetryFontFamily
                )
            }
            DeviceStatCard(modifier = Modifier.weight(1f).fillMaxHeight(), label = "Memory", icon = Icons.Filled.Memory) {
                val usedMb = stats.memUsedKb?.let { it / 1024 }
                val totalMb = stats.memTotalKb?.let { it / 1024 }
                Text(
                    text = if (usedMb != null && totalMb != null) "$usedMb / $totalMb MB" else "--",
                    fontFamily = TelemetryFontFamily,
                    color = TextPrimary,
                    style = MaterialTheme.typography.bodyLarge
                )
                val fraction = if (usedMb != null && totalMb != null && totalMb > 0) usedMb / totalMb.toFloat() else 0f
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                    color = ElectricCyan,
                    trackColor = SurfaceDark
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))

        DeviceStatCard(label = "Temperatures", icon = Icons.Filled.Thermostat) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TempReading("CPU 0", stats.tempCpuCluster0Mc)
                TempReading("CPU 1", stats.tempCpuCluster1Mc)
                TempReading("GPU", stats.tempGpuMc)
                TempReading("PMIC", stats.tempPmicMc)
            }
        }
        Spacer(modifier = Modifier.height(20.dp))
    }
}

@Composable
private fun RecordingBanner(isRecording: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isRecording) ElectricCyan.copy(alpha = 0.15f) else SurfaceDark
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isRecording) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                contentDescription = null,
                tint = if (isRecording) ElectricCyan else TextSecondary
            )
            Spacer(modifier = Modifier.padding(start = 8.dp))
            Text(
                text = if (isRecording) {
                    "Recording -- these numbers reflect active dual-camera load"
                } else {
                    "Parked / idle -- not recording right now, numbers reflect idle load"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (isRecording) ElectricCyan else TextSecondary,
                fontFamily = TelemetryFontFamily
            )
        }
    }
}

@Composable
private fun TempReading(label: String, milliC: Int?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = milliC?.let { "%.0f°".format(it / 1000f) } ?: "--",
            fontFamily = TelemetryFontFamily,
            color = TextPrimary,
            style = MaterialTheme.typography.titleMedium
        )
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
    }
}

@Composable
private fun DeviceStatCard(
    modifier: Modifier = Modifier,
    label: String,
    icon: ImageVector,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.padding(start = 6.dp))
                Text(text = label, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            }
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}
