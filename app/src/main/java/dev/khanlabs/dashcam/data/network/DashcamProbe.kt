package dev.khanlabs.dashcam.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ProbeResult {
    data object NotOnDashcamNetwork : ProbeResult
    data class Reachable(val gatewayIp: String) : ProbeResult
    data class Unreachable(val gatewayIp: String, val reason: String) : ProbeResult
}

/**
 * A real (not mock) reachability check against the dashcam's own busybox
 * httpd on port 8080. DashcamManifestClient now handles real
 * enumeration/download/status via cgi-bin/browse and cgi-bin/status --
 * this stays a lightweight, separate "is the file server up at all" probe.
 */
@Singleton
class DashcamProbe @Inject constructor() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    suspend fun probe(wifiState: WifiState): ProbeResult {
        if (!wifiState.isDashcamNetwork) return ProbeResult.NotOnDashcamNetwork
        val gateway = wifiState.gatewayIp ?: return ProbeResult.Unreachable("unknown", "No gateway IP")

        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url("http://$gateway:8080/").build()
                client.newCall(request).execute().use {
                    // Any real HTTP response -- even a 404 -- proves the file
                    // server is up and reachable. busybox httpd 404s on the
                    // bare webroot by design (no autoindex/index.html); that's
                    // not the same thing as unreachable, and requiring a 2xx
                    // here made this probe fail on real hardware every time
                    // (confirmed against a real dashcam hotspot).
                    ProbeResult.Reachable(gateway)
                }
            } catch (e: IOException) {
                ProbeResult.Unreachable(gateway, e.message ?: "Connection failed")
            }
        }
    }
}
