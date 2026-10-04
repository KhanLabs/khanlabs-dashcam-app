package dev.khanlabs.dashcam.data.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One GPX `<trkpt>`. `timeUtc` is GPS-derived and, unlike video filename
 * timestamps, is always trustworthy (see IMPLEMENTATION_NOTES.md /
 * project spec section 5) -- it never depends on the dashcam's own clock.
 */
data class GpsPoint(
    val lat: Double,
    val lon: Double,
    val timeUtc: Instant
)

data class GpsTrack(
    val date: LocalDate,
    val points: List<GpsPoint>
) {
    init {
        require(points.isNotEmpty()) { "A track needs at least one point" }
    }
}

data class TripStats(
    val distanceKm: Double,
    val durationSeconds: Long,
    val maxSpeedKmh: Double
)

/** Great-circle distance between two points, in kilometers. */
private fun haversineKm(a: GpsPoint, b: GpsPoint): Double {
    val earthRadiusKm = 6371.0
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val lat1 = Math.toRadians(a.lat)
    val lat2 = Math.toRadians(b.lat)
    val h = sin(dLat / 2).let { it * it } +
        cos(lat1) * cos(lat2) * sin(dLon / 2).let { it * it }
    return earthRadiusKm * 2 * atan2(sqrt(h), sqrt(1 - h))
}

/** Instantaneous speed (km/h) approaching point `index`, or 0 for the first point. */
fun GpsTrack.speedAt(index: Int): Double {
    if (index <= 0 || index >= points.size) return 0.0
    val prev = points[index - 1]
    val curr = points[index]
    val hours = (curr.timeUtc.epochSecond - prev.timeUtc.epochSecond) / 3600.0
    if (hours <= 0.0) return 0.0
    return haversineKm(prev, curr) / hours
}

fun GpsTrack.computeStats(): TripStats {
    var distance = 0.0
    var maxSpeed = 0.0
    for (i in points.indices) {
        if (i > 0) distance += haversineKm(points[i - 1], points[i])
        maxSpeed = maxOf(maxSpeed, speedAt(i))
    }
    val duration = points.last().timeUtc.epochSecond - points.first().timeUtc.epochSecond
    return TripStats(distanceKm = distance, durationSeconds = duration, maxSpeedKmh = maxSpeed)
}

fun Instant.toUtcClockString(): String =
    atZone(ZoneOffset.UTC).toLocalTime().toString()
