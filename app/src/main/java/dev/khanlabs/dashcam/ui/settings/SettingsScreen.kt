package dev.khanlabs.dashcam.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.khanlabs.dashcam.BuildConfig
import dev.khanlabs.dashcam.data.model.SyncScope
import dev.khanlabs.dashcam.data.network.WifiState
import dev.khanlabs.dashcam.data.repository.PublicDownloadStorage
import dev.khanlabs.dashcam.ui.theme.ElectricCyan
import dev.khanlabs.dashcam.ui.theme.KhanLabsOrange
import dev.khanlabs.dashcam.ui.theme.SurfaceDark
import dev.khanlabs.dashcam.ui.theme.TelemetryFontFamily
import dev.khanlabs.dashcam.ui.theme.TextSecondary

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsState()
    val wifiState by viewModel.wifiState.collectAsState()
    val keepAwakeEnabled by viewModel.keepAwakeEnabled.collectAsState()
    val keepAwakeError by viewModel.keepAwakeError.collectAsState()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        SectionHeader("Connection")
        ConnectionInfoCard(wifiState)

        Spacer(modifier = Modifier.height(24.dp))
        SectionHeader("Sync Settings")
        SettingToggleRow(
            title = "Auto-sync on WiFi connect",
            subtitle = "Syncs automatically when this phone joins the dashcam's hotspot. " +
                "Best-effort: only while the app hasn't been force-stopped or killed.",
            checked = settings.autoSyncOnWifi,
            onCheckedChange = viewModel::setAutoSyncOnWifi
        )
        SettingChoiceRow(
            title = "What to sync",
            subtitle = "Limits which camera's footage counts toward sync",
            options = SyncScope.entries,
            selected = settings.syncScope,
            labelFor = { it.label },
            onSelect = viewModel::setSyncScope
        )
        SettingToggleRow(
            title = "Keep screen on during sync",
            subtitle = "Prevents the phone from sleeping mid-transfer",
            checked = settings.keepScreenOnDuringSync,
            onCheckedChange = viewModel::setKeepScreenOnDuringSync
        )
        SettingToggleRow(
            title = "Notify on new clips",
            subtitle = "Coming soon -- no notification permission/channel wired up yet",
            checked = false,
            enabled = false,
            onCheckedChange = {}
        )

        Spacer(modifier = Modifier.height(24.dp))
        SectionHeader("Storage Settings")
        Text(
            text = "Download location",
            style = MaterialTheme.typography.bodyLarge
        )
        Text(
            text = "This app's private storage (cleared on uninstall).",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary
        )
        SettingToggleRow(
            title = "Also save to Downloads folder",
            subtitle = if (PublicDownloadStorage.isSupported) {
                "Extra copy in Downloads/KhanLabsDashcam -- visible in Files, survives " +
                    "uninstall. Uses roughly double the storage."
            } else {
                "Needs Android 10 or newer"
            },
            checked = settings.saveToPublicDownloads,
            enabled = PublicDownloadStorage.isSupported,
            onCheckedChange = viewModel::setSaveToPublicDownloads
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(text = "Downloaded cache", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "${formatBytes(settings.cachedDownloadsBytes)} on this phone",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
            val cacheClearable = settings.cachedDownloadsBytes > 0
            TextButton(
                onClick = viewModel::clearDownloadCache,
                enabled = cacheClearable
            ) {
                Text("Clear", color = if (cacheClearable) ElectricCyan else TextSecondary)
            }
        }
        HorizontalDivider(color = SurfaceDark)

        Spacer(modifier = Modifier.height(24.dp))
        SectionHeader("Manage Dashcam")
        Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
            Column(modifier = Modifier.padding(16.dp)) {
                val isPaired = settings.pairedDashcamSsid != null
                ConnectionInfoRow("Paired dashcam", settings.pairedDashcamSsid ?: "None yet")
                Spacer(modifier = Modifier.height(12.dp))
                TextButton(
                    onClick = viewModel::forgetDashcam,
                    enabled = isPaired
                ) {
                    Text(
                        "Forget this dashcam",
                        color = if (isPaired) MaterialTheme.colorScheme.error else TextSecondary
                    )
                }
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                SettingToggleRow(
                    title = "Keep dashcam awake (debugging)",
                    subtitle = if (wifiState.isDashcamNetwork) {
                        "Stops the dashcam from sleeping after 15s idle, which normally " +
                            "drops its hotspot and pauses recording. For bench-testing only " +
                            "-- leave this off for normal driving."
                    } else {
                        "Connect to the dashcam's hotspot to use this"
                    },
                    checked = keepAwakeEnabled ?: false,
                    enabled = wifiState.isDashcamNetwork && keepAwakeEnabled != null,
                    onCheckedChange = viewModel::setKeepAwake
                )
                if (keepAwakeError != null) {
                    Text(
                        text = keepAwakeError ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        SectionHeader("About")
        Text(
            text = "KhanLabs Dashcam v${BuildConfig.VERSION_NAME}",
            fontFamily = TelemetryFontFamily,
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "khanlabs.dev",
            color = ElectricCyan,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.clickable {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://khanlabs.dev")))
            }
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "E-WASTE REPURPOSED",
            color = KhanLabsOrange,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(KhanLabsOrange.copy(alpha = 0.15f))
                .padding(horizontal = 10.dp, vertical = 6.dp)
        )
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun ConnectionInfoCard(wifiState: WifiState) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
        Column(modifier = Modifier.padding(16.dp)) {
            ConnectionInfoRow("Network", wifiState.ssid ?: "Not connected")
            ConnectionInfoRow(
                "Dashcam detected",
                if (wifiState.isDashcamNetwork) "Yes" else "No"
            )
            ConnectionInfoRow("Gateway", wifiState.gatewayIp ?: "--")
            ConnectionInfoRow("Signal", wifiState.rssiDbm?.let { "$it dBm" } ?: "--")
        }
    }
}

@Composable
private fun ConnectionInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        Text(text = value, style = MaterialTheme.typography.bodyMedium, fontFamily = TelemetryFontFamily)
    }
}

@Composable
private fun <T> SettingChoiceRow(
    title: String,
    subtitle: String,
    options: List<T>,
    selected: T,
    labelFor: (T) -> String,
    onSelect: (T) -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 12.dp)) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
        Text(text = subtitle, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        Spacer(modifier = Modifier.height(10.dp))
        // weight(1f) so the three pills split the row evenly instead of each
        // taking only its own text's width -- without it, "Both cameras" and
        // "Road camera only" claimed their natural width first, leaving
        // "Cabin camera only" too little room and forcing it alone to wrap
        // to 3 lines while its neighbors stayed at 1. height(IntrinsicSize.Min)
        // + fillMaxHeight() then keeps all three the same height even if a
        // future longer label does still wrap.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            options.forEach { option ->
                val isSelected = option == selected
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) ElectricCyan else SurfaceDark)
                        .clickable { onSelect(option) }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = labelFor(option),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isSelected) Color.Black else TextSecondary,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
    HorizontalDivider(color = SurfaceDark)
}

private fun formatBytes(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return when {
        mb >= 1024 -> "%.1f GB".format(mb / 1024)
        // Sub-1MB rounds to "0 MB" under %.0f, which reads as nothing-to-clear
        // even though the Clear button is enabled (cachedDownloadsBytes > 0).
        mb < 1 && bytes > 0 -> "%.0f KB".format(bytes / 1024.0).let { if (it == "0 KB") "<1 KB" else it }
        else -> "%.0f MB".format(mb)
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.bodyMedium,
        color = TextSecondary,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
private fun SettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    val titleColor = if (enabled) Color.Unspecified else TextSecondary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, color = titleColor)
            Text(text = subtitle, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = ElectricCyan,
                checkedTrackColor = ElectricCyan.copy(alpha = 0.4f)
            )
        )
    }
    HorizontalDivider(color = SurfaceDark)
}
