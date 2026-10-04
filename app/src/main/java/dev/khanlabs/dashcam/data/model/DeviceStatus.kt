package dev.khanlabs.dashcam.data.model

/**
 * Snapshot of the dashcam's own state, read live from the device once
 * connected to its hotspot -- see [dev.khanlabs.dashcam.data.repository.RealDashcamRepository].
 * uptimeSeconds/roadClipCount/cabinClipCount/dualCamEventCount are all real,
 * either read straight off the device or derived from its actual clip
 * listing -- nothing here is fabricated to fill out the Dashboard.
 *
 * [isStatsLoading]: the clip-derived fields (newClipCount/newTrackCount/
 * readyToSyncGb/roadClipCount/cabinClipCount/dualCamEventCount) require a
 * full directory walk of the dashcam's SD card, directly measured taking
 * 20-50+ seconds on a large recordings folder (a slow CGI script on the
 * device itself, not this app -- BACKLOG_2026-07-13.md). Rather than block
 * the Dashboard's first emission on that walk, RealDashcamRepository emits
 * an immediate placeholder with this flag set so the UI shows a real
 * "checking..." state instead of a misleading confident zero, followed by a
 * second emission with real numbers once the walk completes.
 */
data class DeviceStatus(
    val ssid: String,
    val isConnected: Boolean,
    val storageUsedGb: Double,
    val storageTotalGb: Double,
    val gpsLock: GpsLockState,
    val newClipCount: Int,
    val newTrackCount: Int,
    val readyToSyncGb: Double,
    val uptimeSeconds: Long,
    val roadClipCount: Int,
    val cabinClipCount: Int,
    val dualCamEventCount: Int,
    val isStatsLoading: Boolean = false
)

sealed interface GpsLockState {
    // satelliteCount is nullable because the device's status endpoint reports
    // a fix via file-write recency, not a real satellite read -- null means
    // "locked, count unknown" rather than a fabricated number.
    data class Active(val satelliteCount: Int?) : GpsLockState
    data object Searching : GpsLockState
}
