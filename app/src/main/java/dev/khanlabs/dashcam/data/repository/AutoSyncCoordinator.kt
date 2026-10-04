package dev.khanlabs.dashcam.data.repository

import dev.khanlabs.dashcam.data.network.DashcamManifestClient
import dev.khanlabs.dashcam.data.network.WifiConnectionObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wires the existing (previously unused) [dev.khanlabs.dashcam.data.model.AppSettings.autoSyncOnWifi]
 * setting to a real trigger. Started once from KhanLabsDashcamApp.onCreate()
 * -- lives at Application scope rather than a ViewModel because it needs to
 * keep reacting regardless of which screen, if any, is currently visible.
 *
 * Best-effort, deliberately not WorkManager-backed: this only reacts while
 * the app's process is alive. The NetworkCallback underneath
 * [WifiConnectionObserver] is a process-level OS registration, not tied to
 * any Activity, so on this project's own test device (OnePlus 8T, Android
 * 14 / OxygenOS) it fires reliably as long as the app hasn't been
 * force-stopped or killed by background memory pressure -- and does NOT
 * fire if it has. That's a real gap, not a hidden one: a trigger that
 * survives a full process kill needs a foreground service (persistent
 * notification) or a periodic WorkManager job, neither of which this adds.
 * The narrower scope was deliberate rather than shipping an untested
 * foreground-service permission/lifecycle surface for v1 -- see A2 in
 * BACKLOG_2026-07-13.md.
 *
 * Shares [SyncEngine.syncNow] with the manual SYNC button; a concurrent
 * call from either side is turned away honestly via
 * [dev.khanlabs.dashcam.data.repository.SyncStopReason.ALREADY_SYNCING]
 * rather than racing writes.
 *
 * Also pushes the phone's clock to the dashcam on every join (see
 * [DashcamManifestClient.setTime]'s doc comment for why: no RTC battery,
 * no working NITZ/GPS time source of its own). This reaction is
 * deliberately independent of the [dev.khanlabs.dashcam.data.model.AppSettings.autoSyncOnWifi]
 * gate below -- that setting only controls whether clips/tracks get
 * auto-downloaded (a legitimate opt-out, since it also spends storage and
 * possibly mobile data), and must never be able to suppress the clock fix
 * too. Fired on its own coroutine rather than awaited inline, same
 * reasoning as everything else in this class being best-effort -- a slow
 * or failed time push must never delay or block the actual clip sync.
 */
@Singleton
class AutoSyncCoordinator @Inject constructor(
    private val wifiConnectionObserver: WifiConnectionObserver,
    private val settingsRepository: SettingsRepository,
    private val syncEngine: SyncEngine,
    private val manifestClient: DashcamManifestClient
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun start() {
        // Unconditional: push the phone's clock to the dashcam on every
        // hotspot join, regardless of the autoSyncOnWifi setting. Runs for
        // the lifetime of the app process -- not gated on any setting.
        scope.launch {
            var lastTimePushGatewayIp: String? = null
            wifiConnectionObserver.observeWifiState().collect { state ->
                val gatewayIp = state.gatewayIp
                if (state.isDashcamNetwork && gatewayIp != null) {
                    // Only fire once per gateway per join -- reset to null
                    // below on disconnect so leaving and rejoining the same
                    // dashcam later re-triggers.
                    if (gatewayIp != lastTimePushGatewayIp) {
                        lastTimePushGatewayIp = gatewayIp
                        scope.launch {
                            manifestClient.setTime(gatewayIp, System.currentTimeMillis() / 1000)
                        }
                    }
                } else {
                    lastTimePushGatewayIp = null
                }
            }
        }

        // Conditional: clip/track sync, gated on the user's autoSyncOnWifi
        // preference (defaults to false -- opt-in).
        scope.launch {
            settingsRepository.observeSettings()
                .map { it.autoSyncOnWifi }
                .distinctUntilChanged()
                .collectLatest { enabled ->
                    // collectLatest cancels this block (and its WiFi
                    // registration below) the moment the setting flips off,
                    // or restarts it if it flips back on -- no NetworkCallback
                    // stays registered while the feature is disabled.
                    if (!enabled) return@collectLatest
                    var lastSyncedGatewayIp: String? = null
                    wifiConnectionObserver.observeWifiState().collect { state ->
                        val gatewayIp = state.gatewayIp
                        if (state.isDashcamNetwork && gatewayIp != null) {
                            // Only fire once per gateway per join -- reset to
                            // null below on disconnect so leaving and
                            // rejoining the same dashcam later re-triggers.
                            if (gatewayIp != lastSyncedGatewayIp) {
                                lastSyncedGatewayIp = gatewayIp
                                syncEngine.syncNow(gatewayIp)
                            }
                        } else {
                            lastSyncedGatewayIp = null
                        }
                    }
                }
        }
    }
}
