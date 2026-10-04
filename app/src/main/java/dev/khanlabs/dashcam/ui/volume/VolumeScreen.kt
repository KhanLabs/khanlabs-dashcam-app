package dev.khanlabs.dashcam.ui.volume

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.khanlabs.dashcam.data.network.VolumeStatus
import dev.khanlabs.dashcam.ui.theme.ElectricCyan
import dev.khanlabs.dashcam.ui.theme.SurfaceDark
import dev.khanlabs.dashcam.ui.theme.TelemetryFontFamily
import dev.khanlabs.dashcam.ui.theme.TextPrimary
import dev.khanlabs.dashcam.ui.theme.TextSecondary
import dev.khanlabs.dashcam.ui.theme.WarningAmber

@Composable
fun VolumeScreen(
    onBack: () -> Unit,
    viewModel: VolumeViewModel = hiltViewModel()
) {
    val wifiState by viewModel.wifiState.collectAsStateWithLifecycle()
    val volume by viewModel.volume.collectAsStateWithLifecycle()
    val actionState by viewModel.actionState.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = "Speaker Volume",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
        }

        when {
            !wifiState.isDashcamNetwork -> EmptyState(
                message = "Not connected to the dashcam's hotspot.\nJoin its Dash-* WiFi network to adjust the speaker volume."
            )
            volume == null -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = ElectricCyan)
            }
            else -> VolumeControl(
                status = volume!!,
                actionState = actionState,
                onIncrement = viewModel::increment,
                onDecrement = viewModel::decrement
            )
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
private fun VolumeControl(
    status: VolumeStatus,
    actionState: VolumeActionState,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val atMax = status.volume >= status.max
        val atMin = status.volume <= status.min

        BigArrowButton(
            icon = Icons.Filled.KeyboardArrowUp,
            contentDescription = "Increase volume",
            enabled = !atMax,
            onClick = onIncrement
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "${status.volume}",
            fontFamily = TelemetryFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 96.sp,
            color = TextPrimary
        )
        // Deliberately not labelled "dB" -- this device's speaker mixer
        // control (RX3 Digital Volume) has no calibrated acoustic dB scale
        // exposed by the driver (confirmed via `tinymix -a` on-device), so
        // showing "dB" here would be a fabricated unit, not a measured one.
        // This is the same 0-124 gain level referenced throughout this
        // project's tuning notes (was 124, dialed back to 90).
        Text(
            text = "GAIN LEVEL (${status.min}–${status.max})",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            fontFamily = TelemetryFontFamily
        )

        Spacer(modifier = Modifier.height(16.dp))

        BigArrowButton(
            icon = Icons.Filled.KeyboardArrowDown,
            contentDescription = "Decrease volume",
            enabled = !atMin,
            onClick = onDecrement
        )

        Spacer(modifier = Modifier.height(24.dp))

        StatusLine(actionState)
    }
}

@Composable
private fun StatusLine(actionState: VolumeActionState) {
    val (text, color, icon) = when (actionState) {
        VolumeActionState.Idle -> return
        VolumeActionState.Applying -> Triple("Applying...", TextSecondary, null)
        is VolumeActionState.Failed -> Triple(actionState.error, WarningAmber, Icons.Filled.VolumeOff)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        icon?.let {
            Icon(it, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(text = text, color = color, style = MaterialTheme.typography.labelSmall, fontFamily = TelemetryFontFamily)
    }
}

@Composable
private fun BigArrowButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(96.dp)
            .clip(CircleShape)
            .background(if (enabled) SurfaceDark else SurfaceDark.copy(alpha = 0.4f))
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) ElectricCyan else TextSecondary,
            modifier = Modifier.size(56.dp)
        )
    }
}
