package dev.khanlabs.dashcam.data.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class ManifestEntry(
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val mtimeEpochSeconds: Long
)

/**
 * Mirrors `cgi-bin/status`'s deliberately narrow contract: ssid/isConnected
 * are known client-side already (see WifiConnectionObserver), and
 * newClipCount/newTrackCount/readyToSyncGb are local sync-state concepts the
 * app computes itself -- this only carries facts the device itself reports.
 * gpsSatellites is null rather than a fabricated number: the device's fix
 * signal is a file-write recency heuristic, not a real satellite read.
 */
data class StatusPayload(
    val storageUsedKb: Long,
    val storageTotalKb: Long,
    val gpsFix: Boolean,
    val gpsSatellites: Int?,
    val uptimeSeconds: Long
)

/**
 * Real-time cellular/LTE diagnostics read from the dashcam's own modem via
 * `cgi-bin/lte-status` (device-scripts/webroot-cgi-bin/lte-status). Every
 * nullable field here means the value is genuinely unknown on the device
 * right now (no registration, no signal reading, etc.), not a parsing
 * failure -- the endpoint itself already converts Android's own "no
 * reading" sentinels (asu 99, RSRP 2147483647) to JSON null, so this class
 * doesn't need its own placeholder logic layered on top of that.
 */
data class LteStatus(
    val simState: String?,
    val simOperatorNumeric: String?,
    val simOperatorAlpha: String?,
    val simCountryIso: String?,
    val imei: String?,
    val basebandVersion: String?,
    val networkOperatorNumeric: String?,
    val networkOperatorAlpha: String?,
    val isRoaming: Boolean,
    val voiceRegState: String?,
    val dataRegState: String?,
    val dataRadioTech: String?,
    val signalAsu: Int?,
    val signalDbm: Int?,
    val lteRsrpDbm: Int?,
    val lteRsrqDb: Int?,
    val apnName: String?,
    val apnDisplayName: String?
)

/**
 * Editable APN row -- mirrors cgi-bin/lte-settings' "apn" object. [id] is
 * the row's real content-provider _id, required to target any [set_apn]
 * writeback; every other field is nullable the same way [LteStatus]'s
 * fields are (this device's SIM has none of these set right now on some
 * fields, e.g. user/mmsc).
 */
data class ApnConfig(
    val id: String?,
    val name: String?,
    val apn: String?,
    val mcc: String?,
    val mnc: String?,
    val user: String?,
    val type: String?,
    val mmsc: String?,
    val protocol: String?,
    val authtype: String?,
    val enabled: Boolean
)

/**
 * Editable telephony/radio state, read from cgi-bin/lte-settings' GET
 * action. [networkMode1Raw] is the one this repository's writes actually
 * target -- see [DashcamManifestClient.setNetworkMode]'s doc comment for why
 * [networkModeRaw] (no subscription suffix) is reported but not written.
 */
data class LteSettings(
    val apn: ApnConfig,
    val networkModeRaw: String?,
    val networkMode1Raw: String?,
    val dataRoaming: Boolean,
    val mobileData: Boolean,
    val airplaneMode: Boolean
)

/** Outcome of a single cgi-bin/lte-settings write. [error] is the server's
 *  own validation message (e.g. "apn contains invalid characters") when
 *  [ok] is false due to a rejected request, not a network failure. */
data class LteActionResult(val ok: Boolean, val error: String? = null)

/**
 * Speaker volume, from cgi-bin/volume. [volume] is RX3 Digital Volume's own
 * abstract 0-124 gain step (dashcam's speaker mixer control), NOT a
 * calibrated acoustic dB reading -- that control has no TLV dB scale
 * exposed (confirmed via `tinymix -a 32` on-device: only a dsrange, no dB
 * info). [min]/[max] mirror the endpoint's own validated range.
 */
data class VolumeStatus(val volume: Int, val min: Int, val max: Int)

/**
 * Live CPU/thermal/memory status from cgi-bin/device-stats.
 * [isRecording] matters more than it looks: this unit sleeps (no camera
 * activity) when parked, and every other field here means something very
 * different at idle vs. mid dual-camera-recording -- confirmed on-device
 * (see BACKLOG_2026-07-13.md item B10's CPU-headroom measurement).
 * [cpuUsedPct]/[cpuCapacityPct] are already normalized: cpuUsedPct is 0-100
 * (divide-by-core-count already done server-side); cpuCapacityPct is the
 * device's fixed total capacity (core_count * 100, always 800 on this
 * 8-core unit) reported for reference, not a live number to chart.
 * [tempCpuCluster0Mc]/etc. are millidegrees Celsius (divide by 1000 for °C).
 */
data class DeviceStats(
    val isRecording: Boolean,
    val cpuUsedPct: Int?,
    val cpuCapacityPct: Int?,
    val cpuUserPct: Int?,
    val cpuNicePct: Int?,
    val cpuSysPct: Int?,
    val cpuIdlePct: Int?,
    val cpuIowPct: Int?,
    val cpuIrqPct: Int?,
    val cpuCoreCount: Int,
    val cpuFreqKhz: List<Int?>,
    val cpuFreqMaxKhz: Int?,
    val memTotalKb: Long?,
    val memUsedKb: Long?,
    val tempCpuCluster0Mc: Int?,
    val tempCpuCluster1Mc: Int?,
    val tempGpuMc: Int?,
    val tempPmicMc: Int?
)

/**
 * Talks to the dashcam's `cgi-bin/browse` read-only directory-listing
 * endpoint (a small CGI script deployed alongside its existing busybox
 * httpd -- see IMPLEMENTATION_NOTES.md). Every method fails soft (empty
 * list / null) instead of throwing: callers already treat "no data yet"
 * as a normal state (offline, wrong network, or this particular dashcam
 * unit not having the endpoint deployed).
 */
@Singleton
class DashcamManifestClient @Inject constructor() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun list(gatewayIp: String, path: String): List<ManifestEntry> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("http://$gatewayIp:8080/cgi-bin/browse/$path")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext emptyList()
                    parseEntries(response.body?.string() ?: return@withContext emptyList())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Broadened from IOException: Request.Builder().url() can throw
                // IllegalArgumentException for a malformed URL, which this
                // class's own "every method fails soft" contract should cover
                // too, not just network-layer failures.
                emptyList()
            }
        }

    /** Small-file fetch (GPX tracks) -- reads the whole body into memory. */
    suspend fun fetchBytes(gatewayIp: String, path: String): ByteArray? =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url("http://$gatewayIp:8080/$path").build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) response.body?.bytes() else null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }

    /**
     * Streams a full URL (as already returned by e.g. [dev.khanlabs.dashcam.data.model.VideoClip.sourceUrl])
     * straight to disk rather than buffering the whole body in memory like
     * [fetchBytes] -- clips can be tens of MB. Writes to a `.part` sibling
     * file and renames on success, so an interrupted download never leaves
     * a corrupt file at [destination].
     *
     * [onProgress] is invoked with (bytes downloaded so far, total bytes --
     * 0 if the response didn't carry a Content-Length) throttled to roughly
     * once per 150ms rather than per read, so a caller updating UI state
     * from this callback doesn't flood it -- see [SyncEngine.syncNow].
     */
    suspend fun downloadToFile(
        url: String,
        destination: File,
        onProgress: (bytesDone: Long, bytesTotal: Long) -> Unit = { _, _ -> }
    ): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext false
                    val body = response.body ?: return@withContext false
                    val totalBytes = body.contentLength()
                    destination.parentFile?.mkdirs()
                    val tempFile = File(destination.parentFile, "${destination.name}.part")
                    body.byteStream().use { input ->
                        FileOutputStream(tempFile).use { output ->
                            val buffer = ByteArray(32 * 1024)
                            var bytesDone = 0L
                            var lastEmitAt = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read == -1) break
                                output.write(buffer, 0, read)
                                bytesDone += read
                                val now = System.currentTimeMillis()
                                if (now - lastEmitAt >= 150) {
                                    onProgress(bytesDone, totalBytes)
                                    lastEmitAt = now
                                }
                            }
                            onProgress(bytesDone, totalBytes)
                        }
                    }
                    tempFile.renameTo(destination)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
        }

    /** Single-object status fetch (storage/GPS/uptime) -- see [StatusPayload]. */
    suspend fun fetchStatus(gatewayIp: String): StatusPayload? =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("http://$gatewayIp:8080/cgi-bin/status")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    parseStatus(response.body?.string() ?: return@withContext null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }

    /** Single-object cellular/LTE diagnostics fetch -- see [LteStatus]. */
    suspend fun fetchLteStatus(gatewayIp: String): LteStatus? =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("http://$gatewayIp:8080/cgi-bin/lte-status")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    parseLteStatus(response.body?.string() ?: return@withContext null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }

    private fun parseLteStatus(json: String): LteStatus? = try {
        val obj = JSONObject(json)
        fun str(key: String): String? =
            if (obj.isNull(key)) null else obj.optString(key).takeIf { it.isNotEmpty() }
        fun int(key: String): Int? = if (obj.isNull(key)) null else obj.optInt(key)
        LteStatus(
            simState = str("sim_state"),
            simOperatorNumeric = str("sim_operator_numeric"),
            simOperatorAlpha = str("sim_operator_alpha"),
            simCountryIso = str("sim_country_iso"),
            imei = str("imei"),
            basebandVersion = str("baseband_version"),
            networkOperatorNumeric = str("network_operator_numeric"),
            networkOperatorAlpha = str("network_operator_alpha"),
            isRoaming = obj.optBoolean("is_roaming", false),
            voiceRegState = str("voice_reg_state"),
            dataRegState = str("data_reg_state"),
            dataRadioTech = str("data_radio_tech"),
            signalAsu = int("signal_asu"),
            signalDbm = int("signal_dbm"),
            lteRsrpDbm = int("lte_rsrp_dbm"),
            lteRsrqDb = int("lte_rsrq_db"),
            apnName = str("apn_name"),
            apnDisplayName = str("apn_display_name")
        )
    } catch (e: Exception) {
        null
    }

    /** Current editable telephony/radio state -- see [LteSettings]. */
    suspend fun fetchLteSettings(gatewayIp: String): LteSettings? =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("http://$gatewayIp:8080/cgi-bin/lte-settings")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    parseLteSettings(response.body?.string() ?: return@withContext null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }

    private fun parseLteSettings(json: String): LteSettings? = try {
        val obj = JSONObject(json)
        val apnObj = obj.getJSONObject("apn")
        fun str(o: JSONObject, key: String): String? =
            if (o.isNull(key)) null else o.optString(key).takeIf { it.isNotEmpty() }
        LteSettings(
            apn = ApnConfig(
                id = str(apnObj, "id"),
                name = str(apnObj, "name"),
                apn = str(apnObj, "apn"),
                mcc = str(apnObj, "mcc"),
                mnc = str(apnObj, "mnc"),
                user = str(apnObj, "user"),
                type = str(apnObj, "type"),
                mmsc = str(apnObj, "mmsc"),
                protocol = str(apnObj, "protocol"),
                authtype = str(apnObj, "authtype"),
                enabled = str(apnObj, "enabled") == "1"
            ),
            networkModeRaw = str(obj, "network_mode_raw"),
            networkMode1Raw = str(obj, "network_mode1_raw"),
            dataRoaming = str(obj, "data_roaming") == "1",
            mobileData = str(obj, "mobile_data") == "1",
            airplaneMode = str(obj, "airplane_mode") == "1"
        )
    } catch (e: Exception) {
        null
    }

    suspend fun setMobileData(gatewayIp: String, enabled: Boolean): LteActionResult =
        postLteSetting(gatewayIp, mapOf("action" to "set_mobile_data", "enabled" to if (enabled) "1" else "0"))

    suspend fun setRoaming(gatewayIp: String, enabled: Boolean): LteActionResult =
        postLteSetting(gatewayIp, mapOf("action" to "set_roaming", "enabled" to if (enabled) "1" else "0"))

    suspend fun setAirplaneMode(gatewayIp: String, enabled: Boolean): LteActionResult =
        postLteSetting(gatewayIp, mapOf("action" to "set_airplane_mode", "enabled" to if (enabled) "1" else "0"))

    /** Airplane-on, brief pause, airplane-off -- a manual re-attach kick.
     *  See cgi-bin/lte-settings' doc comment: this is the highest
     *  diagnostic-value action for this unit's never-registers history. */
    suspend fun restartRadio(gatewayIp: String): LteActionResult =
        postLteSetting(gatewayIp, mapOf("action" to "restart_radio"))

    /** EXPERIMENTAL -- see cgi-bin/lte-settings' doc comment on
     *  preferred_network_mode vs preferred_network_mode1. Writing this
     *  setting is not proof the running radio retuned; re-check
     *  [fetchLteStatus]'s dataRadioTech afterward. */
    suspend fun setNetworkMode(gatewayIp: String, mode: Int): LteActionResult =
        postLteSetting(gatewayIp, mapOf("action" to "set_network_mode", "mode" to mode.toString()))

    suspend fun setApnEnabled(gatewayIp: String, id: String, enabled: Boolean): LteActionResult =
        postLteSetting(gatewayIp, mapOf("action" to "set_apn_enabled", "id" to id, "enabled" to if (enabled) "1" else "0"))

    /** Starts/stops the dashcam's live-video encoder for [camera] (0 = Road,
     *  1 = Cabin -- matches CameraPool.get(int) on the firmware side) and,
     *  when enabling, its LiveStreamServer TCP listener on
     *  [LiveStreamPlayer.PORT]. Only one camera should ever be enabled at a
     *  time -- see cgi-bin/live-video's own doc comment on why a caller must
     *  disable the previous camera before enabling a new one, not run both
     *  concurrently. */
    suspend fun setLiveVideo(gatewayIp: String, camera: Int, enabled: Boolean): LteActionResult =
        withContext(Dispatchers.IO) {
            try {
                val formBody = FormBody.Builder()
                    .add("camera", camera.toString())
                    .add("enable", if (enabled) "1" else "0")
                    .build()
                val request = Request.Builder()
                    .url("http://$gatewayIp:8080/cgi-bin/live-video")
                    .post(formBody)
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext LteActionResult(false, "HTTP ${response.code}")
                    val body = response.body?.string() ?: return@withContext LteActionResult(false, "empty response")
                    val obj = JSONObject(body)
                    LteActionResult(obj.optBoolean("ok", false), obj.optString("error").takeIf { it.isNotEmpty() })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LteActionResult(false, e.message ?: "request failed")
            }
        }

    /**
     * Pushes the phone's current clock to the dashcam via cgi-bin/set-time.
     * This unit has no RTC battery (clock resets to a fixed 2009 default on
     * every boot) and no working time source of its own -- cellular never
     * registers (confirmed dead RF path to the modem) and GPS only fixes
     * outdoors. The phone's clock is the one reliable source in this whole
     * system, so [AutoSyncCoordinator] calls this every time the phone joins
     * the dashcam's hotspot. Unlike most actions here this has no [ok]
     * consumer that reacts to failure -- it's a best-effort background
     * correction, not a user-initiated action with its own UI state.
     */
    suspend fun setTime(gatewayIp: String, epochSeconds: Long): LteActionResult =
        withContext(Dispatchers.IO) {
            try {
                val formBody = FormBody.Builder()
                    .add("epoch", epochSeconds.toString())
                    .build()
                val request = Request.Builder()
                    .url("http://$gatewayIp:8080/cgi-bin/set-time")
                    .post(formBody)
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext LteActionResult(false, "HTTP ${response.code}")
                    val body = response.body?.string() ?: return@withContext LteActionResult(false, "empty response")
                    val obj = JSONObject(body)
                    LteActionResult(obj.optBoolean("ok", false), obj.optString("error").takeIf { it.isNotEmpty() })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LteActionResult(false, e.message ?: "request failed")
            }
        }

    /** Updates (never inserts) the APN row [id] identifies. Every parameter
     *  besides [id] is optional -- only non-null fields are sent, and the
     *  server only rewrites the columns it actually receives. */
    suspend fun setApn(
        gatewayIp: String,
        id: String,
        apn: String? = null,
        user: String? = null,
        password: String? = null,
        mmsc: String? = null,
        protocol: String? = null,
        type: String? = null
    ): LteActionResult {
        val params = mutableMapOf("action" to "set_apn", "id" to id)
        apn?.let { params["apn"] = it }
        user?.let { params["user"] = it }
        password?.let { params["password"] = it }
        mmsc?.let { params["mmsc"] = it }
        protocol?.let { params["protocol"] = it }
        type?.let { params["type"] = it }
        return postLteSetting(gatewayIp, params)
    }

    private suspend fun postLteSetting(gatewayIp: String, params: Map<String, String>): LteActionResult =
        withContext(Dispatchers.IO) {
            try {
                val formBody = FormBody.Builder().apply {
                    params.forEach { (key, value) -> add(key, value) }
                }.build()
                val request = Request.Builder()
                    .url("http://$gatewayIp:8080/cgi-bin/lte-settings")
                    .post(formBody)
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext LteActionResult(false, "HTTP ${response.code}")
                    val body = response.body?.string() ?: return@withContext LteActionResult(false, "empty response")
                    val obj = JSONObject(body)
                    LteActionResult(obj.optBoolean("ok", false), obj.optString("error").takeIf { it.isNotEmpty() })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LteActionResult(false, e.message ?: "request failed")
            }
        }

    /** Current speaker volume + valid range -- see [VolumeStatus]. */
    suspend fun fetchVolume(gatewayIp: String): VolumeStatus? =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("http://$gatewayIp:8080/cgi-bin/volume")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    parseVolumeStatus(response.body?.string() ?: return@withContext null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }

    private fun parseVolumeStatus(json: String): VolumeStatus? = try {
        val obj = JSONObject(json)
        VolumeStatus(
            volume = obj.getInt("volume"),
            min = obj.getInt("min"),
            max = obj.getInt("max")
        )
    } catch (e: Exception) {
        null
    }

    /** Sets the speaker volume. The device applies this immediately (see
     *  cgi-bin/volume's own doc comment) rather than only on its next 2s
     *  enforcement cycle, so callers don't need [LteViewModel]-style
     *  optimistic-update-then-reconcile delays for this one. */
    suspend fun setVolume(gatewayIp: String, value: Int): LteActionResult =
        withContext(Dispatchers.IO) {
            try {
                val formBody = FormBody.Builder().add("value", value.toString()).build()
                val request = Request.Builder()
                    .url("http://$gatewayIp:8080/cgi-bin/volume")
                    .post(formBody)
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext LteActionResult(false, "HTTP ${response.code}")
                    val body = response.body?.string() ?: return@withContext LteActionResult(false, "empty response")
                    val obj = JSONObject(body)
                    LteActionResult(obj.optBoolean("ok", false), obj.optString("error").takeIf { it.isNotEmpty() })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LteActionResult(false, e.message ?: "request failed")
            }
        }

    /** Whether the dashcam's debug "keep awake" override is on -- see
     *  cgi-bin/keepawake. When on, the dashcam never Dozes on its normal
     *  15s screen-idle timeout, so its hotspot/recording won't silently
     *  drop while sitting untouched on a bench. Meant for debugging only,
     *  not normal driving use. */
    suspend fun fetchKeepAwake(gatewayIp: String): Boolean? =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("http://$gatewayIp:8080/cgi-bin/keepawake")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val body = response.body?.string() ?: return@withContext null
                    if (!JSONObject(body).has("enabled")) return@withContext null
                    JSONObject(body).getBoolean("enabled")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }

    /** Sets the dashcam's "keep awake" debug override. Applies immediately
     *  (see cgi-bin/keepawake's own doc comment). */
    suspend fun setKeepAwake(gatewayIp: String, enabled: Boolean): LteActionResult =
        withContext(Dispatchers.IO) {
            try {
                val formBody = FormBody.Builder().add("enabled", enabled.toString()).build()
                val request = Request.Builder()
                    .url("http://$gatewayIp:8080/cgi-bin/keepawake")
                    .post(formBody)
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext LteActionResult(false, "HTTP ${response.code}")
                    val body = response.body?.string() ?: return@withContext LteActionResult(false, "empty response")
                    val obj = JSONObject(body)
                    LteActionResult(obj.optBoolean("ok", false), obj.optString("error").takeIf { it.isNotEmpty() })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LteActionResult(false, e.message ?: "request failed")
            }
        }

    /** Live CPU/thermal/memory snapshot -- see [DeviceStats]. */
    suspend fun fetchDeviceStats(gatewayIp: String): DeviceStats? =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("http://$gatewayIp:8080/cgi-bin/device-stats")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    parseDeviceStats(response.body?.string() ?: return@withContext null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }

    private fun parseDeviceStats(json: String): DeviceStats? = try {
        val obj = JSONObject(json)
        fun int(key: String): Int? = if (obj.isNull(key)) null else obj.optInt(key)
        fun long(key: String): Long? = if (obj.isNull(key)) null else obj.optLong(key)
        val freqArray = obj.optJSONArray("cpu_freq_khz")
        val freqList = if (freqArray != null) {
            (0 until freqArray.length()).map { i ->
                if (freqArray.isNull(i)) null else freqArray.optInt(i)
            }
        } else {
            emptyList()
        }
        DeviceStats(
            isRecording = obj.optBoolean("is_recording", false),
            cpuUsedPct = int("cpu_used_pct"),
            cpuCapacityPct = int("cpu_capacity_pct"),
            cpuUserPct = int("cpu_user_pct"),
            cpuNicePct = int("cpu_nice_pct"),
            cpuSysPct = int("cpu_sys_pct"),
            cpuIdlePct = int("cpu_idle_pct"),
            cpuIowPct = int("cpu_iow_pct"),
            cpuIrqPct = int("cpu_irq_pct"),
            cpuCoreCount = obj.optInt("cpu_core_count", 0),
            cpuFreqKhz = freqList,
            cpuFreqMaxKhz = int("cpu_freq_max_khz"),
            memTotalKb = long("mem_total_kb"),
            memUsedKb = long("mem_used_kb"),
            tempCpuCluster0Mc = int("temp_cpu_cluster0_mc"),
            tempCpuCluster1Mc = int("temp_cpu_cluster1_mc"),
            tempGpuMc = int("temp_gpu_mc"),
            tempPmicMc = int("temp_pmic_mc")
        )
    } catch (e: Exception) {
        null
    }

    private fun parseStatus(json: String): StatusPayload? = try {
        val obj = JSONObject(json)
        StatusPayload(
            storageUsedKb = obj.optLong("storage_used_kb", 0L),
            storageTotalKb = obj.optLong("storage_total_kb", 0L),
            gpsFix = obj.optBoolean("gps_fix", false),
            gpsSatellites = if (obj.isNull("gps_satellites")) null else obj.optInt("gps_satellites"),
            uptimeSeconds = obj.optLong("uptime_seconds", 0L)
        )
    } catch (e: Exception) {
        null
    }

    private fun parseEntries(json: String): List<ManifestEntry> = try {
        val array = JSONArray(json)
        buildList {
            for (i in 0 until array.length()) {
                // One malformed entry (e.g. missing "name") used to throw out
                // of getString() here uncaught by this loop, discarding the
                // whole listing via the outer catch instead of just skipping
                // that one entry -- a single bad row among hundreds shouldn't
                // blank the entire directory walk.
                val entry = runCatching {
                    val obj = array.getJSONObject(i)
                    if (obj.has("error")) return@runCatching null
                    ManifestEntry(
                        name = obj.getString("name"),
                        isDirectory = obj.optString("type") == "dir",
                        sizeBytes = obj.optLong("size", 0L),
                        mtimeEpochSeconds = obj.optLong("mtime", 0L)
                    )
                }.getOrNull()
                if (entry != null) add(entry)
            }
        }
    } catch (e: Exception) {
        emptyList()
    }
}
