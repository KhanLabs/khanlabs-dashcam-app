package dev.khanlabs.dashcam.ui.lte

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.khanlabs.dashcam.data.network.ApnConfig
import dev.khanlabs.dashcam.data.network.DashcamManifestClient
import dev.khanlabs.dashcam.data.network.LteActionResult
import dev.khanlabs.dashcam.data.network.LteSettings
import dev.khanlabs.dashcam.data.network.LteStatus
import dev.khanlabs.dashcam.data.network.WifiConnectionObserver
import dev.khanlabs.dashcam.data.network.WifiState
import dev.khanlabs.dashcam.data.repository.DashcamRepository
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

/** A safe, identifier-like character set matching cgi-bin/lte-settings'
 *  own `is_safe_token` allowlist exactly -- checked client-side too so a
 *  bad character shows an inline error instead of a round trip just to
 *  learn the server rejected it. */
private val SAFE_TOKEN_REGEX = Regex("^[A-Za-z0-9._@-]*$")
fun isSafeSettingsToken(value: String): Boolean = SAFE_TOKEN_REGEX.matches(value)

/** Tracks the outcome of the single most recent write so the screen can
 *  show "applying...", then a result, without blocking navigation --
 *  [LteViewModel.refreshSettings] already re-pulls [LteViewModel.lteSettings]
 *  on success, so callers don't need to read a value out of this beyond
 *  showing it. */
sealed interface LteActionState {
    data object Idle : LteActionState
    data class InProgress(val action: String) : LteActionState
    data class Success(val action: String) : LteActionState
    data class Failed(val action: String, val error: String) : LteActionState
}

@HiltViewModel
class LteViewModel @Inject constructor(
    repository: DashcamRepository,
    private val wifiConnectionObserver: WifiConnectionObserver,
    private val manifestClient: DashcamManifestClient
) : ViewModel() {

    // WhileSubscribed(5_000): repository.observeLteStatus() is a live poll
    // (every 2.5s -- see RealDashcamRepository), so this must stop while the
    // screen isn't on-screen rather than running for the app's whole
    // process lifetime the way AutoSyncCoordinator's WiFi observer does.
    val lteStatus: StateFlow<LteStatus?> = repository.observeLteStatus()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Exposed separately from lteStatus so the screen can tell "not on the
    // dashcam's network" apart from "on it, but the first poll hasn't
    // resolved yet" -- both look identical as a bare null from lteStatus alone.
    val wifiState: StateFlow<WifiState> = wifiConnectionObserver.observeWifiState()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            WifiState(ssid = null, isDashcamNetwork = false, gatewayIp = null)
        )

    // Unlike lteStatus, this is a one-shot fetch, not a poll -- editable
    // settings don't change on their own, only in response to a write this
    // screen itself makes (which calls refreshSettings() again on success).
    private val _lteSettings = MutableStateFlow<LteSettings?>(null)
    val lteSettings: StateFlow<LteSettings?> = _lteSettings

    private val _actionState = MutableStateFlow<LteActionState>(LteActionState.Idle)
    val actionState: StateFlow<LteActionState> = _actionState

    init {
        viewModelScope.launch {
            wifiState
                .map { it.gatewayIp to it.isDashcamNetwork }
                .distinctUntilChanged()
                // collectLatest, not collect: the retry loop below can spin
                // for a while on a stuck fetch, and if gatewayIp/isDashcamNetwork
                // changes mid-retry (WiFi dropped, rejoined a different
                // gateway) we want that in-flight loop cancelled rather than
                // left hammering a stale IP. Both delay() and the fetch's
                // withContext(IO) are cancellation points, so this unwinds
                // cleanly.
                .collectLatest { (gatewayIp, isDashcamNetwork) ->
                    if (isDashcamNetwork && gatewayIp != null) {
                        // lteSettings is a one-shot fetch gated by the
                        // distinctUntilChanged above (deliberately not
                        // re-triggered on every RSSI tick like lteStatus'
                        // poll), but fetchLteSettings() fails soft to null on
                        // any timeout/parse error -- common in practice since
                        // the dashcam's httpd is single-threaded and can be
                        // busy. Without a retry, a single failed fetch would
                        // leave _lteSettings null for this ViewModel's whole
                        // lifetime with nothing left to re-trigger it. Retry
                        // on a short cadence until it succeeds, then stop --
                        // this is not meant to become a continuous poll like
                        // lteStatus, settings just need to load once.
                        while (!fetchAndStoreSettings(gatewayIp)) {
                            delay(SETTINGS_RETRY_INTERVAL_MS)
                        }
                    } else {
                        _lteSettings.value = null
                    }
                }
        }
    }

    fun refreshSettings() {
        val gatewayIp = wifiState.value.gatewayIp ?: return
        viewModelScope.launch { fetchAndStoreSettings(gatewayIp) }
    }

    /** Does the actual fetch-and-store for [refreshSettings] and the init
     *  block's retry loop alike, so both share one code path. Returns
     *  whether it succeeded, so the retry loop knows when to stop. */
    private suspend fun fetchAndStoreSettings(gatewayIp: String): Boolean {
        val result = manifestClient.fetchLteSettings(gatewayIp)
        _lteSettings.value = result
        return result != null
    }

    /**
     * [optimisticUpdate], when given, is applied to [_lteSettings]
     * immediately on a successful write, before the real re-fetch. This
     * unit's embedded httpd takes ~2-2.5s per cgi-bin/lte-settings round
     * trip (confirmed live -- each GET forks a fresh CGI process running
     * several `settings get`/`content query` shell calls in sequence), so
     * without this a switch would sit visually unchanged for multiple
     * seconds after a write that already succeeded, reading as broken.
     * Only used for writes whose effect on this JSON shape is unambiguous
     * (plain toggles, an APN field update) -- deliberately omitted for
     * [setNetworkMode], where whether the write actually changes anything
     * real is the entire open question.
     */
    private fun performAction(
        name: String,
        optimisticUpdate: ((LteSettings) -> LteSettings)? = null,
        action: suspend (gatewayIp: String) -> LteActionResult
    ) {
        val gatewayIp = wifiState.value.gatewayIp
        if (gatewayIp == null) {
            _actionState.value = LteActionState.Failed(name, "Not connected to the dashcam")
            return
        }
        _actionState.value = LteActionState.InProgress(name)
        viewModelScope.launch {
            val result = action(gatewayIp)
            _actionState.value = if (result.ok) {
                optimisticUpdate?.let { update ->
                    _lteSettings.value?.let { current -> _lteSettings.value = update(current) }
                }
                // Still reconcile with the real device state shortly after
                // -- the delay gives the underlying Settings write (which
                // the CGI script's own doc comment notes can lag behind its
                // own "ok" response) a moment to actually land before we
                // overwrite the optimistic value with a fresh read.
                delay(1_500)
                refreshSettings()
                LteActionState.Success(name)
            } else {
                LteActionState.Failed(name, result.error ?: "Failed")
            }
        }
    }

    fun setMobileData(enabled: Boolean) =
        performAction("Mobile data", optimisticUpdate = { it.copy(mobileData = enabled) }) { gatewayIp ->
            manifestClient.setMobileData(gatewayIp, enabled)
        }

    fun setRoaming(enabled: Boolean) =
        performAction("Data roaming", optimisticUpdate = { it.copy(dataRoaming = enabled) }) { gatewayIp ->
            manifestClient.setRoaming(gatewayIp, enabled)
        }

    fun setAirplaneMode(enabled: Boolean) =
        performAction("Airplane mode", optimisticUpdate = { it.copy(airplaneMode = enabled) }) { gatewayIp ->
            manifestClient.setAirplaneMode(gatewayIp, enabled)
        }

    fun restartRadio() =
        performAction("Restart radio") { gatewayIp -> manifestClient.restartRadio(gatewayIp) }

    /** EXPERIMENTAL -- see [DashcamManifestClient.setNetworkMode]'s doc
     *  comment. Deliberately no optimistic update: callers should re-check
     *  [lteStatus]'s dataRadioTech afterward rather than trusting
     *  [LteActionState.Success] alone. */
    fun setNetworkMode(mode: Int) =
        performAction("Network mode") { gatewayIp -> manifestClient.setNetworkMode(gatewayIp, mode) }

    fun setApnEnabled(id: String, enabled: Boolean) =
        performAction(
            "APN enabled",
            optimisticUpdate = { it.copy(apn = it.apn.copy(enabled = enabled)) }
        ) { gatewayIp ->
            manifestClient.setApnEnabled(gatewayIp, id, enabled)
        }

    fun setApn(
        id: String,
        apn: String?,
        user: String?,
        password: String?,
        mmsc: String?,
        protocol: String?,
        type: String?
    ) = performAction(
        "APN",
        optimisticUpdate = { current ->
            current.copy(
                apn = current.apn.copy(
                    apn = apn ?: current.apn.apn,
                    user = user ?: current.apn.user,
                    type = type ?: current.apn.type,
                    mmsc = mmsc ?: current.apn.mmsc,
                    protocol = protocol ?: current.apn.protocol
                )
            )
        }
    ) { gatewayIp ->
        manifestClient.setApn(
            gatewayIp = gatewayIp,
            id = id,
            apn = apn,
            user = user,
            password = password,
            mmsc = mmsc,
            protocol = protocol,
            type = type
        )
    }

    fun clearActionState() {
        _actionState.value = LteActionState.Idle
    }

    private companion object {
        /** How often to retry the one-shot lteSettings fetch after it fails
         *  soft to null, until it succeeds. Short enough that a user
         *  landing on the screen right after a failed fetch doesn't wait
         *  long, but not so short it hammers the dashcam's single-threaded
         *  httpd while it's busy. */
        const val SETTINGS_RETRY_INTERVAL_MS = 4_000L
    }
}
