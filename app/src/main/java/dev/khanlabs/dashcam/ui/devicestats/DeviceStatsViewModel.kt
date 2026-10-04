package dev.khanlabs.dashcam.ui.devicestats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.khanlabs.dashcam.data.network.DeviceStats
import dev.khanlabs.dashcam.data.network.WifiConnectionObserver
import dev.khanlabs.dashcam.data.network.WifiState
import dev.khanlabs.dashcam.data.repository.DashcamRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class DeviceStatsViewModel @Inject constructor(
    repository: DashcamRepository,
    wifiConnectionObserver: WifiConnectionObserver
) : ViewModel() {

    // WhileSubscribed(5_000): repository.observeDeviceStats() is a live poll
    // (every 3.5s -- see RealDashcamRepository), so this must stop while the
    // screen isn't on-screen rather than running for the app's whole
    // process lifetime.
    val deviceStats: StateFlow<DeviceStats?> = repository.observeDeviceStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Exposed separately so the screen can tell "not on the dashcam's
    // network" apart from "on it, but the first poll hasn't resolved yet" --
    // both look identical as a bare null from deviceStats alone.
    val wifiState: StateFlow<WifiState> = wifiConnectionObserver.observeWifiState()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            WifiState(ssid = null, isDashcamNetwork = false, gatewayIp = null)
        )
}
