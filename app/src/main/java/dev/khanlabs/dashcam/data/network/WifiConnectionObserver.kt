package dev.khanlabs.dashcam.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

data class WifiState(
    val ssid: String?,
    val isDashcamNetwork: Boolean,
    val gatewayIp: String?,
    val rssiDbm: Int? = null
)

/**
 * Real (not mock) WiFi connection state -- unlike device telemetry, this
 * doesn't depend on the dashcam's own manifest endpoint; any Android device
 * can read its own current network. Reading the real SSID requires location
 * permission granted at runtime (an Android platform restriction, not
 * something this app wants -- see the permission-request flow in
 * DashboardScreen).
 *
 * IMPORTANT (found via on-device testing, not documented anywhere obvious):
 * `NetworkCapabilities.transportInfo` (the "modern" way to get WifiInfo from
 * a NetworkCallback) comes back location-REDACTED on at least this real
 * device (OnePlus 8T, Android 14) even with ACCESS_FINE_LOCATION granted --
 * SSID stays "<unknown ssid>" and BSSID masked to 02:00:00:00:00:00. The
 * classic (deprecated) `WifiManager.connectionInfo` does NOT have this
 * problem and returns the real SSID under the exact same permission state.
 * So this uses the deprecated API deliberately -- don't "clean up" this
 * deprecation warning by switching back to transportInfo without retesting
 * on real hardware first.
 *
 * Granting the permission mid-session does NOT by itself trigger a new
 * NetworkCallback event (the network itself hasn't changed), so a stale
 * "no SSID" reading would stick around until the network changed again --
 * also confirmed on-device. [refresh] lets a permission-grant callback force
 * a fresh read.
 */
@Singleton
class WifiConnectionObserver @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val refreshTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    fun refresh() {
        refreshTrigger.tryEmit(Unit)
    }

    fun observeWifiState(): Flow<WifiState> = callbackFlow {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager

        @Suppress("DEPRECATION")
        fun currentState(): WifiState {
            val hasWifiTransport = connectivityManager
                .getNetworkCapabilities(connectivityManager.activeNetwork)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            if (!hasWifiTransport) {
                return WifiState(ssid = null, isDashcamNetwork = false, gatewayIp = null)
            }

            val wifiInfo = wifiManager.connectionInfo
            val rawSsid = wifiInfo?.ssid?.trim('"')
            val ssid = rawSsid.takeUnless { it.isNullOrBlank() || it == WifiManager.UNKNOWN_SSID }
            val isDashcam = ssid?.startsWith("Dash-", ignoreCase = true) == true
            val gateway = if (isDashcam) formatGatewayIp(wifiManager) else null
            val rssi = wifiInfo?.rssi
            return WifiState(ssid = ssid, isDashcamNetwork = isDashcam, gatewayIp = gateway, rssiDbm = rssi)
        }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(currentState())
            }

            override fun onLost(network: Network) {
                trySend(WifiState(ssid = null, isDashcamNetwork = false, gatewayIp = null))
            }
        }

        connectivityManager.registerNetworkCallback(request, callback)
        trySend(currentState())

        val refreshJob = launch {
            refreshTrigger.collect { trySend(currentState()) }
        }

        awaitClose {
            connectivityManager.unregisterNetworkCallback(callback)
            refreshJob.cancel()
        }
    }.distinctUntilChanged()

    private fun formatGatewayIp(wifiManager: WifiManager): String? {
        @Suppress("DEPRECATION")
        val gateway = wifiManager.dhcpInfo?.gateway ?: return null
        if (gateway == 0) return null
        return listOf(
            gateway and 0xff,
            gateway shr 8 and 0xff,
            gateway shr 16 and 0xff,
            gateway shr 24 and 0xff
        ).joinToString(".")
    }
}
