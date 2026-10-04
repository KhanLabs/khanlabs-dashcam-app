package dev.khanlabs.dashcam.data.parser

import android.util.Xml
import dev.khanlabs.dashcam.data.model.GpsPoint
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.time.Instant

/**
 * Streams `<trkpt>` elements out of a GPX 1.1 file with [XmlPullParser]
 * rather than loading the whole file as DOM, per the spec's technical
 * note (section 5 of the companion app spec) -- multi-day tracks can be large.
 */
object GpxParser {

    fun parse(input: InputStream): List<GpsPoint> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        val points = mutableListOf<GpsPoint>()
        var lat: Double? = null
        var lon: Double? = null
        var readingTime = false
        var timeText: String? = null

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "trkpt" -> {
                        lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                        lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                    }
                    "time" -> readingTime = true
                }
                XmlPullParser.TEXT -> if (readingTime) timeText = parser.text
                XmlPullParser.END_TAG -> when (parser.name) {
                    "time" -> readingTime = false
                    "trkpt" -> {
                        val latVal = lat
                        val lonVal = lon
                        val time = timeText?.let { runCatching { Instant.parse(it) }.getOrNull() }
                        if (latVal != null && lonVal != null && time != null) {
                            points.add(GpsPoint(latVal, lonVal, time))
                        }
                        lat = null; lon = null; timeText = null
                    }
                }
            }
            eventType = parser.next()
        }
        return points
    }
}
