package dev.khanlabs.dashcam.data.parser

import dev.khanlabs.dashcam.data.model.CameraIndex
import dev.khanlabs.dashcam.data.model.VideoClip
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalTime

/**
 * Parses the dashcam's fixed recording filename convention:
 * `{imei}_{cameraIndex}_{yyyy-MM-dd}_{HH-mm-ss}_{unixEpoch}.mp4`
 * e.g. `351234567890123_0_2009-01-01_03-00-35_1230760835.mp4`.
 */
object VideoFilenameParser {

    private val PATTERN =
        Regex("""^(\d{15})_(\d)_(\d{4}-\d{2}-\d{2})_(\d{2}-\d{2}-\d{2})_(\d+)\.mp4$""")

    fun parse(filename: String, durationSeconds: Int, sizeBytes: Long): VideoClip? {
        val match = PATTERN.matchEntire(filename) ?: return null
        val (imei, camIndex, datePart, timePart, epoch) = match.destructured
        val cameraIndex = when (camIndex) {
            "0" -> CameraIndex.ROAD
            "1" -> CameraIndex.CABIN
            else -> return null
        }
        // The regex only validates digit count, not calendar validity -- a
        // corrupted or malformed filename (e.g. month 13) matches the regex
        // but throws DateTimeException from LocalDate/LocalTime.parse. This
        // used to propagate uncaught through the mapNotNull{} at every call
        // site, aborting the whole clip list instead of skipping one entry.
        return try {
            VideoClip(
                imei = imei,
                cameraIndex = cameraIndex,
                recordedDate = LocalDate.parse(datePart),
                recordedTime = LocalTime.parse(timePart.replace('-', ':')),
                epochSeconds = epoch.toLong(),
                filename = filename,
                durationSeconds = durationSeconds,
                sizeBytes = sizeBytes
            )
        } catch (e: DateTimeException) {
            null
        }
    }
}
