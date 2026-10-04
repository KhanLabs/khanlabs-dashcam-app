package dev.khanlabs.dashcam.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.khanlabs.dashcam.data.model.AppSettings
import dev.khanlabs.dashcam.data.model.SyncScope
import dev.khanlabs.dashcam.data.network.DashcamManifestClient
import dev.khanlabs.dashcam.data.network.WifiConnectionObserver
import dev.khanlabs.dashcam.data.network.WifiState
import dev.khanlabs.dashcam.data.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val manifestClient: DashcamManifestClient,
    wifiConnectionObserver: WifiConnectionObserver
) : ViewModel() {

    val settings: StateFlow<AppSettings> = repository.observeSettings()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val wifiState: StateFlow<WifiState> = wifiConnectionObserver.observeWifiState()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            WifiState(ssid = null, isDashcamNetwork = false, gatewayIp = null)
        )

    // One-shot fetch, not a poll -- null while unknown/not connected, same
    // pattern as VolumeViewModel's volume StateFlow.
    private val _keepAwakeEnabled = MutableStateFlow<Boolean?>(null)
    val keepAwakeEnabled: StateFlow<Boolean?> = _keepAwakeEnabled

    private val _keepAwakeError = MutableStateFlow<String?>(null)
    val keepAwakeError: StateFlow<String?> = _keepAwakeError

    init {
        // Remember whichever Dash-* network we actually connect to, so
        // Settings can show "Paired dashcam" even when currently offline.
        viewModelScope.launch {
            wifiState
                // Same rationale as RealDashcamRepository/RealVideoRepository/
                // RealTripRepository's identical guard: rssiDbm changes
                // constantly while connected and is part of WifiState's
                // default equality, so without this every signal-strength
                // tick would trigger a redundant setPairedDashcamSsid()
                // write (and, via observeSettings()'s downloadDirSizeBytes()
                // re-list, a full download-directory walk). Only act on an
                // actual network identity change.
                .distinctUntilChangedBy { Triple(it.ssid, it.isDashcamNetwork, it.gatewayIp) }
                .collect { state ->
                    if (state.isDashcamNetwork && state.ssid != null) {
                        repository.setPairedDashcamSsid(state.ssid)
                    }
                }
        }

        viewModelScope.launch {
            wifiState
                .map { it.gatewayIp to it.isDashcamNetwork }
                .distinctUntilChanged()
                .collectLatest { (gatewayIp, isDashcamNetwork) ->
                    if (isDashcamNetwork && gatewayIp != null) {
                        _keepAwakeEnabled.value = manifestClient.fetchKeepAwake(gatewayIp)
                    } else {
                        _keepAwakeEnabled.value = null
                    }
                }
        }
    }

    fun setKeepAwake(enabled: Boolean) {
        val gatewayIp = wifiState.value.gatewayIp ?: return
        val previous = _keepAwakeEnabled.value
        // Optimistic update, same reasoning as VolumeViewModel: cgi-bin/
        // keepawake applies immediately, so there's no real lag worth
        // hiding behind a delay/reconcile step.
        _keepAwakeEnabled.value = enabled
        _keepAwakeError.value = null
        viewModelScope.launch {
            val result = manifestClient.setKeepAwake(gatewayIp, enabled)
            if (!result.ok) {
                _keepAwakeEnabled.value = previous
                _keepAwakeError.value = result.error ?: "Failed to set keep-awake"
            }
        }
    }

    fun setAutoSyncOnWifi(enabled: Boolean) {
        viewModelScope.launch { repository.setAutoSyncOnWifi(enabled) }
    }

    fun setNotifyOnNewClips(enabled: Boolean) {
        viewModelScope.launch { repository.setNotifyOnNewClips(enabled) }
    }

    fun setKeepScreenOnDuringSync(enabled: Boolean) {
        viewModelScope.launch { repository.setKeepScreenOnDuringSync(enabled) }
    }

    fun setSyncScope(scope: SyncScope) {
        viewModelScope.launch { repository.setSyncScope(scope) }
    }

    fun clearDownloadCache() {
        viewModelScope.launch { repository.clearDownloadCache() }
    }

    fun forgetDashcam() {
        viewModelScope.launch { repository.setPairedDashcamSsid(null) }
    }

    fun setSaveToPublicDownloads(enabled: Boolean) {
        viewModelScope.launch { repository.setSaveToPublicDownloads(enabled) }
    }
}
