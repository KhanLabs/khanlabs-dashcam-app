package dev.khanlabs.dashcam.data.model

import java.time.LocalDate
import java.time.LocalTime

enum class CameraIndex(val label: String) {
    ROAD("Road Cam"),
    CABIN("Cabin Cam")
}

/**
 * One parsed recording. `recordedDate`/`recordedTime`/`epochSeconds` all come
 * straight from the filename, which means they inherit the dashcam's own
 * system-clock bug (see IMPLEMENTATION_NOTES.md) -- that's exactly why
 * [isTimestampTrustworthy] exists instead of the UI trusting these blindly.
 */
data class VideoClip(
    val imei: String,
    val cameraIndex: CameraIndex,
    val recordedDate: LocalDate,
    val recordedTime: LocalTime,
    val epochSeconds: Long,
    val filename: String,
    val durationSeconds: Int,
    val sizeBytes: Long,
    /** Playable/downloadable HTTP URL on the dashcam's own file server, when
     *  this clip came from a real listing. Null for bundled mock/demo clips,
     *  which fall back to a local raw resource instead. */
    val sourceUrl: String? = null
) {
    /** The dashcam has no RTC battery; a boot-default date means the clock was never corrected by GPS. */
    val isTimestampTrustworthy: Boolean get() = recordedDate.year >= 2024
}
