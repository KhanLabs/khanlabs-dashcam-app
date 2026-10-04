package dev.khanlabs.dashcam.data.repository

import dev.khanlabs.dashcam.data.model.DeviceStatus
import dev.khanlabs.dashcam.data.model.GpsLockState
import dev.khanlabs.dashcam.data.network.DeviceStats
import dev.khanlabs.dashcam.data.network.LteStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Fixture data matching the exact shape the real device-side manifest
 * endpoint is expected to serve once it exists. See [DashcamRepository].
 */
class MockDashcamRepository @Inject constructor() : DashcamRepository {

    private val status = MutableStateFlow(
        DeviceStatus(
            ssid = "Dash-0154",
            isConnected = true,
            storageUsedGb = 8.5,
            storageTotalGb = 119.0,
            gpsLock = GpsLockState.Active(satelliteCount = 12),
            newClipCount = 3,
            newTrackCount = 1,
            readyToSyncGb = 2.4,
            uptimeSeconds = 5_430,
            roadClipCount = 142,
            cabinClipCount = 138,
            dualCamEventCount = 130
        )
    )

    override fun observeDeviceStatus(): StateFlow<DeviceStatus> = status

    private val lteStatus = MutableStateFlow(
        LteStatus(
            simState = "LOADED",
            simOperatorNumeric = "00101",
            simOperatorAlpha = "Test Network",
            simCountryIso = "xx",
            imei = "351234567890123",
            basebandVersion = ".TA.3.0.c1-00541-8953_GEN_PACK-1",
            networkOperatorNumeric = "00101",
            networkOperatorAlpha = "Test Network",
            isRoaming = false,
            voiceRegState = "HOME",
            dataRegState = "HOME",
            dataRadioTech = "LTE",
            signalAsu = 22,
            signalDbm = -69,
            lteRsrpDbm = -95,
            lteRsrqDb = -11,
            apnName = "internet",
            apnDisplayName = "Internet"
        )
    )

    override fun observeLteStatus(): StateFlow<LteStatus?> = lteStatus

    private val deviceStats = MutableStateFlow(
        DeviceStats(
            isRecording = true,
            cpuUsedPct = 38,
            cpuCapacityPct = 800,
            cpuUserPct = 156,
            cpuNicePct = 3,
            cpuSysPct = 144,
            cpuIdlePct = 491,
            cpuIowPct = 0,
            cpuIrqPct = 6,
            cpuCoreCount = 8,
            cpuFreqKhz = listOf(1401600, 1401600, 1401600, 1401600, 1401600, 1401600, 1401600, 1401600),
            cpuFreqMaxKhz = 1804800,
            memTotalKb = 1883476,
            memUsedKb = 1864904,
            tempCpuCluster0Mc = 46600,
            tempCpuCluster1Mc = 48200,
            tempGpuMc = 47300,
            tempPmicMc = 48050
        )
    )

    override fun observeDeviceStats(): StateFlow<DeviceStats?> = deviceStats
}
