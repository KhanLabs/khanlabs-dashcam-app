package dev.khanlabs.dashcam.ui.lte

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.SignalCellular4Bar
import androidx.compose.material.icons.filled.SignalCellularOff
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.khanlabs.dashcam.data.network.LteStatus
import dev.khanlabs.dashcam.ui.theme.ElectricCyan
import dev.khanlabs.dashcam.ui.theme.SurfaceDark
import dev.khanlabs.dashcam.ui.theme.TelemetryFontFamily
import dev.khanlabs.dashcam.ui.theme.TextPrimary
import dev.khanlabs.dashcam.ui.theme.TextSecondary
import dev.khanlabs.dashcam.ui.theme.WarningAmber

@Composable
fun LteScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit = {},
    viewModel: LteViewModel = hiltViewModel()
) {
    val lteStatus by viewModel.lteStatus.collectAsStateWithLifecycle()
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
            Text(
                text = "Cellular",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "Cellular settings")
            }
        }

        when {
            // Distinct from "connected but first poll hasn't resolved yet"
            // below -- without this split, both look identical as a bare
            // null from lteStatus and the user has no way to tell "go join
            // the hotspot" apart from "just wait a second."
            !wifiState.isDashcamNetwork -> EmptyState(
                icon = Icons.Filled.WifiTethering,
                message = "Not connected to the dashcam's hotspot.\nJoin its Dash-* WiFi network to see live cellular stats."
            )
            lteStatus == null -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = ElectricCyan)
            }
            else -> LteDetail(lteStatus!!)
        }
    }
}

@Composable
private fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(48.dp))
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
        }
    }
}

/** true when this unit has no live registration to report -- the "why is
 *  everything blank" case this hardware hits on every real test run (see
 *  lte-status's own doc comment). Distinguishing this from a genuinely
 *  broken screen is the whole point of this banner: a silent "--" in every
 *  card would read as the app failing, not the SIM being unregistered. */
private fun LteStatus.hasNoService(): Boolean =
    (dataRegState == null || dataRegState == "OUT_OF_SERVICE") &&
        (voiceRegState == null || voiceRegState == "OUT_OF_SERVICE")

@Composable
private fun LteDetail(status: LteStatus) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        if (status.hasNoService()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = WarningAmber.copy(alpha = 0.15f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.SignalCellularOff, contentDescription = null, tint = WarningAmber)
                    Spacer(modifier = Modifier.padding(start = 8.dp))
                    Text(
                        text = "No service — SIM not registered to a network",
                        style = MaterialTheme.typography.bodyMedium,
                        color = WarningAmber,
                        fontFamily = TelemetryFontFamily
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LteStatCard(modifier = Modifier.weight(1f), label = "SIM", icon = Icons.Filled.SimCard) {
                Value(status.simState ?: "--")
                SubValue(listOfNotNull(status.simOperatorAlpha, status.simCountryIso?.uppercase()).joinToString(" · ").ifBlank { "--" })
            }
            LteStatCard(modifier = Modifier.weight(1f), label = "Mode", icon = Icons.Filled.CellTower) {
                Value(status.dataRadioTech ?: "--")
                SubValue(if (status.isRoaming) "Roaming" else "Home network")
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LteStatCard(
                modifier = Modifier.weight(1f),
                label = "Signal",
                icon = if (status.signalDbm != null) Icons.Filled.SignalCellular4Bar else Icons.Filled.SignalCellularOff
            ) {
                Value(status.signalDbm?.let { "$it dBm" } ?: "--")
                SubValue(
                    buildList {
                        status.signalAsu?.let { add("ASU $it") }
                        status.lteRsrpDbm?.let { add("RSRP $it") }
                        status.lteRsrqDb?.let { add("RSRQ $it") }
                    }.joinToString(" · ").ifBlank { "No reading" }
                )
            }
            LteStatCard(modifier = Modifier.weight(1f), label = "Registration", icon = Icons.Filled.CellTower) {
                Value(status.dataRegState ?: "--")
                SubValue("Voice: ${status.voiceRegState ?: "--"}")
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LteStatCard(modifier = Modifier.weight(1f), label = "PLMN", icon = Icons.Filled.Language) {
                Value(status.networkOperatorNumeric ?: status.simOperatorNumeric ?: "--")
                SubValue(status.networkOperatorAlpha?.let { "Registered: $it" } ?: "Not registered")
            }
            LteStatCard(modifier = Modifier.weight(1f), label = "APN", icon = Icons.Filled.Dns) {
                Value(status.apnName ?: "--")
                SubValue(status.apnDisplayName ?: "--")
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        LteStatCard(modifier = Modifier.fillMaxWidth(), label = "IMEI", icon = Icons.Filled.Fingerprint) {
            Value(status.imei ?: "--")
            SubValue("Baseband: ${status.basebandVersion ?: "--"}")
        }
        Spacer(modifier = Modifier.height(20.dp))
    }
}

@Composable
private fun Value(text: String) {
    Text(
        text = text,
        fontFamily = TelemetryFontFamily,
        color = TextPrimary,
        style = MaterialTheme.typography.bodyLarge
    )
}

@Composable
private fun SubValue(text: String) {
    Text(
        text = text,
        fontFamily = TelemetryFontFamily,
        color = TextSecondary,
        style = MaterialTheme.typography.labelSmall
    )
}

@Composable
private fun LteStatCard(
    modifier: Modifier = Modifier,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier,
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
