package dev.khanlabs.dashcam.ui.lte

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.khanlabs.dashcam.data.network.LteSettings
import dev.khanlabs.dashcam.ui.theme.ElectricCyan
import dev.khanlabs.dashcam.ui.theme.SurfaceDark
import dev.khanlabs.dashcam.ui.theme.TelemetryFontFamily
import dev.khanlabs.dashcam.ui.theme.TextSecondary
import dev.khanlabs.dashcam.ui.theme.WarningAmber

@Composable
fun LteSettingsScreen(onBack: () -> Unit, viewModel: LteViewModel = hiltViewModel()) {
    val wifiState by viewModel.wifiState.collectAsState()
    val settings by viewModel.lteSettings.collectAsState()
    val actionState by viewModel.actionState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(text = "Cellular Settings", style = MaterialTheme.typography.titleMedium)
        }

        ActionStateBanner(actionState, onDismiss = viewModel::clearActionState)

        when {
            !wifiState.isDashcamNetwork -> Text(
                text = "Not connected to the dashcam's hotspot.",
                color = TextSecondary,
                modifier = Modifier.padding(20.dp)
            )
            settings == null -> Text(
                text = "Loading current settings...",
                color = TextSecondary,
                modifier = Modifier.padding(20.dp)
            )
            else -> LteSettingsContent(settings!!, viewModel)
        }
    }
}

@Composable
private fun ActionStateBanner(state: LteActionState, onDismiss: () -> Unit) {
    val (text, color) = when (state) {
        LteActionState.Idle -> return
        is LteActionState.InProgress -> "${state.action}: applying..." to TextSecondary
        is LteActionState.Success -> "${state.action}: done" to ElectricCyan
        is LteActionState.Failed -> "${state.action}: ${state.error}" to WarningAmber
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = text, color = color, style = MaterialTheme.typography.labelSmall, fontFamily = TelemetryFontFamily)
        if (state !is LteActionState.InProgress) {
            TextButton(onClick = onDismiss) { Text("Dismiss", color = TextSecondary) }
        }
    }
}

@Composable
private fun LteSettingsContent(settings: LteSettings, viewModel: LteViewModel) {
    var showRestartConfirm by remember { mutableStateOf(false) }
    var showNetworkModeConfirm by remember { mutableStateOf(false) }
    var networkModeInput by remember(settings.networkMode1Raw) {
        mutableStateOf(settings.networkMode1Raw ?: "")
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
    ) {
        SectionHeader("Radio")
        SettingToggleRow(
            title = "Mobile Data",
            subtitle = "Dashcam's own cellular data connection",
            checked = settings.mobileData,
            onCheckedChange = viewModel::setMobileData
        )
        SettingToggleRow(
            title = "Data Roaming",
            subtitle = "Allow connecting to a non-home network",
            checked = settings.dataRoaming,
            onCheckedChange = viewModel::setRoaming
        )
        SettingToggleRow(
            title = "Airplane Mode",
            subtitle = "Disables the dashcam's radios -- will also drop this WiFi connection while on",
            checked = settings.airplaneMode,
            onCheckedChange = viewModel::setAirplaneMode
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = { showRestartConfirm = true },
            colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark, contentColor = ElectricCyan),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Restart Radio (force network rescan)")
        }
        Spacer(modifier = Modifier.height(24.dp))

        SectionHeader("APN — ${settings.apn.name ?: "unnamed"} (${settings.apn.mcc ?: "--"}${settings.apn.mnc ?: ""})")
        ApnEditor(settings, onSave = viewModel::setApn)
        SettingToggleRow(
            title = "APN Enabled",
            subtitle = "carrier_enabled flag on this APN row",
            checked = settings.apn.enabled,
            onCheckedChange = { enabled ->
                settings.apn.id?.let { id -> viewModel.setApnEnabled(id, enabled) }
            }
        )
        Spacer(modifier = Modifier.height(24.dp))

        SectionHeader("Network Mode — EXPERIMENTAL")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = WarningAmber, modifier = Modifier.height(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Writing this is not proof the radio retuned -- re-check Signal/Mode on the previous screen after applying.",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Current raw values -- preferred_network_mode: ${settings.networkModeRaw ?: "--"}, preferred_network_mode1: ${settings.networkMode1Raw ?: "--"}",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            fontFamily = TelemetryFontFamily
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = networkModeInput,
            onValueChange = { if (it.all(Char::isDigit)) networkModeInput = it },
            label = { Text("New mode (numeric)") },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            colors = ltePalette(),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = { showNetworkModeConfirm = true },
            enabled = networkModeInput.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark, contentColor = WarningAmber),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Apply Network Mode")
        }
        Spacer(modifier = Modifier.height(32.dp))
    }

    if (showRestartConfirm) {
        AlertDialog(
            onDismissRequest = { showRestartConfirm = false },
            title = { Text("Restart radio?") },
            text = { Text("Briefly toggles airplane mode to force a network rescan. This will also drop the dashcam's WiFi hotspot for a few seconds -- this app may show \"not connected\" until it reconnects.") },
            confirmButton = {
                TextButton(onClick = { showRestartConfirm = false; viewModel.restartRadio() }) {
                    Text("Restart", color = WarningAmber)
                }
            },
            dismissButton = { TextButton(onClick = { showRestartConfirm = false }) { Text("Cancel") } }
        )
    }

    if (showNetworkModeConfirm) {
        AlertDialog(
            onDismissRequest = { showNetworkModeConfirm = false },
            title = { Text("Apply experimental network mode?") },
            text = { Text("Mode $networkModeInput is not verified safe for this hardware -- see the warning above. Continue?") },
            confirmButton = {
                TextButton(onClick = {
                    showNetworkModeConfirm = false
                    networkModeInput.toIntOrNull()?.let(viewModel::setNetworkMode)
                }) {
                    Text("Apply", color = WarningAmber)
                }
            },
            dismissButton = { TextButton(onClick = { showNetworkModeConfirm = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun ApnEditor(
    settings: LteSettings,
    onSave: (id: String, apn: String?, user: String?, password: String?, mmsc: String?, protocol: String?, type: String?) -> Unit
) {
    val apnId = settings.apn.id
    var apnValue by remember(apnId) { mutableStateOf(settings.apn.apn ?: "") }
    var userValue by remember(apnId) { mutableStateOf(settings.apn.user ?: "") }
    var passwordValue by remember(apnId) { mutableStateOf("") }
    var mmscValue by remember(apnId) { mutableStateOf(settings.apn.mmsc ?: "") }
    var protocolValue by remember(apnId) { mutableStateOf(settings.apn.protocol ?: "") }
    var typeValue by remember(apnId) { mutableStateOf(settings.apn.type ?: "") }

    val fields = listOf(apnValue, userValue, passwordValue, mmscValue, protocolValue, typeValue)
    val allValid = fields.all(::isSafeSettingsToken)

    LteTextField("APN", apnValue) { apnValue = it }
    LteTextField("Username", userValue) { userValue = it }
    LteTextField("Password", passwordValue, isPassword = true) { passwordValue = it }
    LteTextField("MMSC", mmscValue) { mmscValue = it }
    LteTextField("Protocol", protocolValue) { protocolValue = it }
    LteTextField("Type", typeValue) { typeValue = it }

    if (!allValid) {
        Text(
            text = "Only letters, numbers, and . _ @ - are allowed in these fields.",
            color = WarningAmber,
            style = MaterialTheme.typography.labelSmall
        )
    }
    Spacer(modifier = Modifier.height(8.dp))
    Button(
        onClick = {
            apnId?.let { id ->
                onSave(
                    id,
                    apnValue.ifBlank { null },
                    userValue.ifBlank { null },
                    passwordValue.ifBlank { null },
                    mmscValue.ifBlank { null },
                    protocolValue.ifBlank { null },
                    typeValue.ifBlank { null }
                )
            }
        },
        enabled = allValid && apnId != null,
        colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = Color.Black),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Save APN Changes")
    }
}

@Composable
private fun LteTextField(
    label: String,
    value: String,
    isPassword: Boolean = false,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        isError = value.isNotEmpty() && !isSafeSettingsToken(value),
        visualTransformation = if (isPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        colors = ltePalette(),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    )
}

@Composable
private fun ltePalette() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = ElectricCyan,
    unfocusedBorderColor = TextSecondary,
    focusedLabelColor = ElectricCyan,
    cursorColor = ElectricCyan
)

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.bodyMedium,
        color = TextSecondary,
        modifier = Modifier.padding(top = 12.dp, bottom = 8.dp)
    )
}

@Composable
private fun SettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(text = subtitle, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = ElectricCyan,
                checkedTrackColor = ElectricCyan.copy(alpha = 0.4f)
            )
        )
    }
    HorizontalDivider(color = SurfaceDark)
}
