package dev.khanlabs.dashcam.ui.liveview

import android.view.Surface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.khanlabs.dashcam.data.network.DashcamManifestClient
import dev.khanlabs.dashcam.data.network.LiveStreamPlayer
import dev.khanlabs.dashcam.data.network.LiveStreamState
import dev.khanlabs.dashcam.data.network.WifiConnectionObserver
import dev.khanlabs.dashcam.data.network.WifiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class LiveViewViewModel @Inject constructor(
    private val manifestClient: DashcamManifestClient,
    wifiConnectionObserver: WifiConnectionObserver
) : ViewModel() {

    val wifiState: StateFlow<WifiState> = wifiConnectionObserver.observeWifiState()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            WifiState(ssid = null, isDashcamNetwork = false, gatewayIp = null)
        )

    private val _selectedCamera = MutableStateFlow(0)
    val selectedCamera: StateFlow<Int> = _selectedCamera

    private val _streamState = MutableStateFlow<LiveStreamState>(LiveStreamState.Stopped)
    val streamState: StateFlow<LiveStreamState> = _streamState

    private var player: LiveStreamPlayer? = null

    // The camera currently enabled dashcam-side, if any -- distinct from
    // [_selectedCamera] (what the UI shows as picked) since a switch is a
    // multi-step async sequence and these can disagree mid-switch.
    private var activeCamera: Int? = null
    private var switchJob: Job? = null

    /** Starts (or switches to) streaming [camera] on [surface]. Always
     *  disables whatever camera is currently enabled dashcam-side, and
     *  waits for that to complete, before enabling the new one --
     *  LiveStreamServer has a single global frame queue fed by one shared
     *  encoder-callback tap (see the firmware's own doc comment on this),
     *  so two cameras enabled at once would interleave both cameras' NALs
     *  into one corrupted stream. Sequencing this within one coroutine
     *  (rather than two independent fire-and-forget calls) is what
     *  actually guarantees the ordering. */
    fun setCamera(camera: Int, surface: Surface) {
        if (activeCamera == camera && player != null) {
            _selectedCamera.value = camera
            return
        }
        _selectedCamera.value = camera
        switchJob?.cancel()
        switchJob = viewModelScope.launch {
            val gatewayIp = wifiState.value.gatewayIp ?: run {
                _streamState.value = LiveStreamState.Failed("Not connected to the dashcam")
                return@launch
            }

            // player.stop() synchronously closes the socket and joins the
            // decode thread (up to 2s) -- viewModelScope defaults to
            // Dispatchers.Main.immediate, so running that inline here would
            // freeze the UI for up to 2s on every camera-tab switch. Off-load
            // just the blocking call to Dispatchers.IO; the field is nulled
            // out beforehand on Main so nothing else in this coroutine can
            // observe a half-stopped player.
            val playerToStop = player
            player = null
            withContext(Dispatchers.IO) { playerToStop?.stop() }

            val disableResult = activeCamera?.let { manifestClient.setLiveVideo(gatewayIp, it, false) }
            activeCamera = null
            if (disableResult != null && !disableResult.ok) {
                // Per this class's doc comment above: proceeding to enable
                // the new camera without confirming the old one is disabled
                // can leave two cameras enabled dashcam-side at once,
                // interleaving both cameras' NALs into one corrupted stream.
                _streamState.value = LiveStreamState.Failed(disableResult.error ?: "Couldn't disable previous camera")
                return@launch
            }

            // A later switch may have superseded this one while the disable
            // call above was in flight.
            if (_selectedCamera.value != camera) return@launch

            val enableResult = manifestClient.setLiveVideo(gatewayIp, camera, true)
            if (!enableResult.ok) {
                _streamState.value = LiveStreamState.Failed(enableResult.error ?: "Couldn't enable live video")
                return@launch
            }
            activeCamera = camera

            val newPlayer = LiveStreamPlayer { state -> _streamState.value = state }
            player = newPlayer
            newPlayer.start(gatewayIp, LiveStreamPlayer.PORT, surface)
        }
    }

    /** Stops streaming and best-effort disables the active camera
     *  dashcam-side. The disable call is deliberately launched on a scope
     *  detached from [viewModelScope] rather than awaited -- this is called
     *  from [onCleared], by which point viewModelScope is already
     *  cancelling, so anything launched on it here would never actually
     *  run. The socket close (inside player.stop(), synchronous) is what
     *  actually matters for releasing LiveStreamServer's single client
     *  slot; the CGI disable call just saves the dashcam some idle encoder
     *  work until the next enable. */
    fun stop() {
        switchJob?.cancel()
        player?.stop()
        player = null
        val gatewayIp = wifiState.value.gatewayIp
        val camera = activeCamera
        activeCamera = null
        _streamState.value = LiveStreamState.Stopped
        if (gatewayIp != null && camera != null) {
            CoroutineScope(Dispatchers.IO).launch {
                manifestClient.setLiveVideo(gatewayIp, camera, false)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stop()
    }
}
