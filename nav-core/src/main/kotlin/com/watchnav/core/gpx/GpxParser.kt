package com.watchnav.core.gpx

import com.watchnav.core.Course
import com.watchnav.core.CoursePoint
import com.watchnav.core.GeoPoint
import java.io.InputStream
import java.io.StringReader
import java.time.Instant
import java.util.Locale
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler

data class GpxWaypoint(
    val point: CoursePoint,
)

data class GpxDocument(
    val course: Course,
    val waypoints: List<GpxWaypoint>,
)

class GpxParser(private val maximumCoursePoints: Int = 200_000) {
    fun parse(input: InputStream, fallbackName: String = "Imported course"): GpxDocument {
        val trackPoints = ArrayList<CoursePoint>()
        val routePoints = ArrayList<CoursePoint>()
        val waypoints = ArrayList<GpxWaypoint>()
        val trackBreaks = LinkedHashSet<Int>()
        var pendingTrackBreak = false
        var courseName: String? = null
        var insideTrack = false
        var insideRoute = false
        var pointKind: PointKind? = null
        var latitude: Double? = null
        var longitude: Double? = null
        var elevation: Double? = null
        var time: Instant? = null
        var pointName: String? = null
        var pointType: String? = null
        val text = StringBuilder()

        val handler = object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
                text.setLength(0)
                when (elementName(localName, qName)) {
                            "trk" -> insideTrack = true
                            "rte" -> insideRoute = true
                            "trkseg" -> if (trackPoints.isNotEmpty()) pendingTrackBreak = true
                            "trkpt", "rtept", "wpt" -> {
                                pointKind = when (elementName(localName, qName)) {
                                    "trkpt" -> PointKind.TRACK
                                    "rtept" -> PointKind.ROUTE
                                    else -> PointKind.WAYPOINT
                                }
                                latitude = attributes.getValue("lat")?.toDoubleOrNull()
                                longitude = attributes.getValue("lon")?.toDoubleOrNull()
                                elevation = null
                                time = null
                                pointName = null
                                pointType = null
                            }
                        }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                text.append(ch, start, length)
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                val value = text.toString().trim()
                when (elementName(localName, qName)) {
                            "name" -> if (pointKind != null) {
                                pointName = value.ifBlank { null }
                            } else if ((insideTrack || insideRoute) && courseName == null) {
                                courseName = value.ifBlank { null }
                            }
                            "type", "sym" -> if (pointKind != null) pointType = value.ifBlank { null }
                            "ele" -> if (pointKind != null) elevation = value.toDoubleOrNull()
                            "time" -> if (pointKind != null) time = value.toInstantOrNull()
                            "trkpt", "rtept", "wpt" -> {
                                val kind = requireNotNull(pointKind)
                                val lat = requireNotNull(latitude) { "GPX point is missing latitude" }
                                val lon = requireNotNull(longitude) { "GPX point is missing longitude" }
                                val point = CoursePoint(
                                    position = GeoPoint(lat, lon, elevation),
                                    time = time,
                                    name = pointName,
                                    type = pointType,
                                )
                                when (kind) {
                                    PointKind.TRACK -> {
                                        if (pendingTrackBreak) {
                                            trackBreaks += trackPoints.size
                                            pendingTrackBreak = false
                                        }
                                        trackPoints += point
                                    }
                                    PointKind.ROUTE -> routePoints += point
                                    PointKind.WAYPOINT -> waypoints += GpxWaypoint(point)
                                }
                                check(trackPoints.size + routePoints.size <= maximumCoursePoints) {
                                    "GPX exceeds $maximumCoursePoints course points"
                                }
                                pointKind = null
                            }
                            "trk" -> insideTrack = false
                            "rte" -> insideRoute = false
                        }
                text.setLength(0)
            }
        }

        val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
        factory.disableExternalXmlEntities()
        val xmlReader = factory.newSAXParser().xmlReader.apply {
            contentHandler = handler
            entityResolver = org.xml.sax.EntityResolver { _, _ -> InputSource(StringReader("")) }
        }
        input.use { xmlReader.parse(InputSource(it)) }

        val selectedPoints = when {
            trackPoints.isNotEmpty() -> trackPoints
            routePoints.isNotEmpty() -> routePoints
            else -> waypoints.map(GpxWaypoint::point)
        }
        require(selectedPoints.size >= 2) { "GPX contains no navigable track or route" }
        return GpxDocument(
            course = Course(
                name = courseName ?: fallbackName,
                points = selectedPoints,
                breakBeforePointIndices = if (trackPoints.isNotEmpty()) trackBreaks else emptySet(),
            ),
            waypoints = waypoints,
        )
    }

    private fun String.toInstantOrNull(): Instant? = try {
        Instant.parse(this)
    } catch (_: Exception) {
        null
    }

    private fun elementName(localName: String?, qName: String?): String =
        localName.orEmpty().ifBlank { qName.orEmpty().substringAfter(':') }.lowercase(Locale.ROOT)

    private fun SAXParserFactory.disableExternalXmlEntities() {
        sequenceOf(
            "http://apache.org/xml/features/disallow-doctype-decl",
            "http://xml.org/sax/features/external-general-entities",
            "http://xml.org/sax/features/external-parameter-entities",
            "http://apache.org/xml/features/nonvalidating/load-external-dtd",
        ).forEach { feature ->
            runCatching { setFeature(feature, feature.endsWith("disallow-doctype-decl")) }
        }
    }

    private enum class PointKind { TRACK, ROUTE, WAYPOINT }
}
