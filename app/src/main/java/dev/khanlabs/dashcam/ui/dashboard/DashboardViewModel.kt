package dev.khanlabs.dashcam.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.khanlabs.dashcam.data.model.DeviceStatus
import dev.khanlabs.dashcam.data.network.DashcamProbe
import dev.khanlabs.dashcam.data.network.ProbeResult
import dev.khanlabs.dashcam.data.network.WifiConnectionObserver
import dev.khanlabs.dashcam.data.network.WifiState
import dev.khanlabs.dashcam.data.repository.DashcamRepository
import dev.khanlabs.dashcam.data.repository.DownloadSummary
import dev.khanlabs.dashcam.data.repository.SettingsRepository
import dev.khanlabs.dashcam.data.repository.SyncEngine
import dev.khanlabs.dashcam.data.repository.SyncProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

sealed interface SyncStatus {
    data object Idle : SyncStatus
    data object Probing : SyncStatus
    data class Syncing(val progress: SyncProgress? = null) : SyncStatus
    data class Done(val result: ProbeResult, val at: LocalTime, val downloadSummary: DownloadSummary? = null) : SyncStatus
}

@HiltViewModel
class DashboardViewModel @Inject constructor(
    repository: DashcamRepository,
    private val wifiConnectionObserver: WifiConnectionObserver,
    private val dashcamProbe: DashcamProbe,
    private val syncEngine: SyncEngine,
    settingsRepository: SettingsRepository
) : ViewModel() {

    val deviceStatus: StateFlow<DeviceStatus?> = repository.observeDeviceStatus()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Backs the real "Keep screen on during sync" setting -- see
     *  DashboardScreen, which toggles [android.view.View.setKeepScreenOn]
     *  for the sync's actual duration. */
    val keepScreenOnDuringSync: StateFlow<Boolean> = settingsRepository.observeSettings()
        .map { it.keepScreenOnDuringSync }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val wifiState: StateFlow<WifiState> = wifiConnectionObserver.observeWifiState()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            WifiState(ssid = null, isDashcamNetwork = false, gatewayIp = null)
        )

    private val _syncStatus = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val syncStatus: StateFlow<SyncStatus> = _syncStatus

    // Distinct from syncStatus (which reflects the outcome of the LAST
    // attempt, success or failure): this only ever moves forward on an
    // actual successful reachability check, so the Dashboard can show a
    // real "last synced" time instead of the flip-flopping attempt status.
    private val _lastSuccessfulSyncAt = MutableStateFlow<LocalTime?>(null)
    val lastSuccessfulSyncAt: StateFlow<LocalTime?> = _lastSuccessfulSyncAt

    // Cooperative cancel signal, not a coroutine Job.cancel(): syncEngine's
    // download loop checks this between files (same pattern as its existing
    // networkChanged watcher for A2c) so a cancel takes effect promptly
    // without racing CancellationException against this ViewModel still
    // needing to write a final SyncStatus.Done after the loop returns.
    private val cancelRequested = AtomicBoolean(false)

    init {
        // Reflects a background (WiFi-join) sync's completion too, not just
        // this ViewModel's own onSyncClick path -- see SyncEngine's doc
        // comment on lastSyncCompletedAt. Harmless overlap with the manual
        // path's own write below: both just set the same real timestamp.
        viewModelScope.launch {
            syncEngine.lastSyncCompletedAt.collect { at ->
                if (at != null) _lastSuccessfulSyncAt.value = at
            }
        }
    }

    fun onWifiPermissionGranted() {
        wifiConnectionObserver.refresh()
    }

    fun onSyncClick() {
        if (_syncStatus.value is SyncStatus.Probing || _syncStatus.value is SyncStatus.Syncing) return
        cancelRequested.set(false)
        _syncStatus.value = SyncStatus.Probing
        viewModelScope.launch {
            val result = dashcamProbe.probe(wifiState.value)
            if (result !is ProbeResult.Reachable) {
                _syncStatus.value = SyncStatus.Done(result, LocalTime.now())
                return@launch
            }
            _syncStatus.value = SyncStatus.Syncing()
            val summary = try {
                syncEngine.syncNow(
                    result.gatewayIp,
                    onProgress = { progress -> _syncStatus.value = SyncStatus.Syncing(progress) },
                    isCancelled = cancelRequested::get
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            val now = LocalTime.now()
            _syncStatus.value = SyncStatus.Done(result, now, summary)
            // Only a completed sync attempt counts as "successful" -- summary == null
            // means syncEngine itself threw, not just that some files failed.
            if (summary != null) {
                _lastSuccessfulSyncAt.value = now
            }
        }
    }

    fun onCancelSyncClick() {
        cancelRequested.set(true)
    }
}
