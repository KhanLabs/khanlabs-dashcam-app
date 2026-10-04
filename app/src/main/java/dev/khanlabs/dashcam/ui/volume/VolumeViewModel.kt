package dev.khanlabs.dashcam.ui.volume

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.khanlabs.dashcam.data.network.DashcamManifestClient
import dev.khanlabs.dashcam.data.network.VolumeStatus
import dev.khanlabs.dashcam.data.network.WifiConnectionObserver
import dev.khanlabs.dashcam.data.network.WifiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface VolumeActionState {
    data object Idle : VolumeActionState
    data object Applying : VolumeActionState
    data class Failed(val error: String) : VolumeActionState
}

@HiltViewModel
class VolumeViewModel @Inject constructor(
    private val wifiConnectionObserver: WifiConnectionObserver,
    private val manifestClient: DashcamManifestClient
) : ViewModel() {

    val wifiState: StateFlow<WifiState> = wifiConnectionObserver.observeWifiState()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            WifiState(ssid = null, isDashcamNetwork = false, gatewayIp = null)
        )

    // One-shot fetch, not a poll -- like LteViewModel's lteSettings, this
    // only changes in response to a write this screen itself makes.
    private val _volume = MutableStateFlow<VolumeStatus?>(null)
    val volume: StateFlow<VolumeStatus?> = _volume

    private val _actionState = MutableStateFlow<VolumeActionState>(VolumeActionState.Idle)
    val actionState: StateFlow<VolumeActionState> = _actionState

    init {
        viewModelScope.launch {
            wifiState
                .map { it.gatewayIp to it.isDashcamNetwork }
                .distinctUntilChanged()
                .collectLatest { (gatewayIp, isDashcamNetwork) ->
                    if (isDashcamNetwork && gatewayIp != null) {
                        while (!fetchAndStore(gatewayIp)) {
                            delay(FETCH_RETRY_INTERVAL_MS)
                        }
                    } else {
                        _volume.value = null
                    }
                }
        }
    }

    private suspend fun fetchAndStore(gatewayIp: String): Boolean {
        val result = manifestClient.fetchVolume(gatewayIp)
        _volume.value = result
        return result != null
    }

    fun increment() = adjust(STEP)
    fun decrement() = adjust(-STEP)

    private fun adjust(delta: Int) {
        val current = _volume.value ?: return
        val gatewayIp = wifiState.value.gatewayIp ?: return
        val target = (current.volume + delta).coerceIn(current.min, current.max)
        if (target == current.volume) return

        // Optimistic update with no reconcile delay -- unlike LteViewModel's
        // settings writes, cgi-bin/volume pokes tinymix directly on a
        // successful POST, so there's no real lag between "wrote" and
        // "device applied it" worth hiding behind a delay.
        _volume.value = current.copy(volume = target)
        _actionState.value = VolumeActionState.Applying
        viewModelScope.launch {
            val result = manifestClient.setVolume(gatewayIp, target)
            _actionState.value = if (result.ok) {
                VolumeActionState.Idle
            } else {
                // Revert to the device's real value on failure so the
                // displayed number never claims a setting that didn't stick.
                fetchAndStore(gatewayIp)
                VolumeActionState.Failed(result.error ?: "Failed to set volume")
            }
        }
    }

    private companion object {
        /** Matches cgi-bin/volume's own dsrange granularity closely enough
         *  to reach either end (0-124) in a reasonable number of taps
         *  without needing a slider or a long-press-repeat gesture. */
        const val STEP = 5
        const val FETCH_RETRY_INTERVAL_MS = 4_000L
    }
}
