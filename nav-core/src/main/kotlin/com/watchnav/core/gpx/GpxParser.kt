package com.watchnav.core.gpx

import com.watchnav.core.Course
import com.watchnav.core.CourseAddress
import com.watchnav.core.CourseLink
import com.watchnav.core.CoursePoint
import com.watchnav.core.CoursePointMetrics
import com.watchnav.core.CourseSemantics
import com.watchnav.core.CourseStyle
import com.watchnav.core.GeoPoint
import java.io.InputStream
import java.io.StringReader
import java.time.Instant
import java.util.Locale
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler

data class GpxWaypoint(val point: CoursePoint)

data class GpxDocument(
    val course: Course,
    val waypoints: List<GpxWaypoint>,
)

/** Streaming, allow-listed GPX parser suitable for the memory limits of a Wear OS process. */
class GpxParser(
    private val maximumCoursePoints: Int = 200_000,
    private val maximumWaypoints: Int = 10_000,
) {
    fun parse(input: InputStream, fallbackName: String = "Imported course"): GpxDocument {
        val trackPoints = ArrayList<CoursePoint>()
        val routePoints = ArrayList<CoursePoint>()
        val waypoints = ArrayList<GpxWaypoint>()
        val trackBreaks = LinkedHashSet<Int>()
        var pendingTrackBreak = false
        var courseName: String? = null
        var insideTrack = false
        var insideRoute = false
        var point: PointBuilder? = null
        var currentLink: LinkBuilder? = null
        var courseColor: String? = null
        var courseWidth: String? = null
        var courseTranslucent = false
        val text = StringBuilder()

        val handler = object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
                text.setLength(0)
                when (val element = elementName(localName, qName)) {
                    "trk" -> insideTrack = true
                    "rte" -> insideRoute = true
                    "trkseg" -> if (trackPoints.isNotEmpty()) pendingTrackBreak = true
                    "trkpt", "rtept", "wpt" -> point = PointBuilder(
                        element = element,
                        latitude = attributes.getValue("lat")?.toDoubleOrNull(),
                        longitude = attributes.getValue("lon")?.toDoubleOrNull(),
                    )
                    "link" -> if (point != null) {
                        currentLink = attributes.getValue("href")?.take(MAX_LINK_CHARS)?.let(::LinkBuilder)
                    }
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                val available = MAX_ELEMENT_TEXT_CHARS - text.length
                if (available > 0) text.append(ch, start, minOf(length, available))
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                val element = elementName(localName, qName)
                val value = text.toString().trim().ifBlank { null }
                val builder = point
                when (element) {
                    "name" -> if (builder != null) builder.name = value else if (
                        (insideTrack || insideRoute) && courseName == null
                    ) courseName = value
                    "type" -> if (currentLink != null) currentLink?.mimeType = value else builder?.type = value
                    "sym" -> builder?.symbol = value
                    "desc" -> builder?.description = value
                    "cmt" -> builder?.comment = value
                    "ele" -> builder?.elevation = value?.toDoubleOrNull()
                    "time" -> builder?.time = value?.toInstantOrNull()
                    "text" -> currentLink?.text = value
                    "link" -> {
                        currentLink?.build()?.let { link -> builder?.links?.takeIf { it.size < MAX_LINKS_PER_POINT }?.add(link) }
                        currentLink = null
                    }
                    "displaycolor" -> builder?.displayColor = value
                    "streetaddress", "street" -> builder?.street = value
                    "city" -> builder?.city = value
                    "state" -> builder?.state = value
                    "postalcode", "postcode" -> builder?.postalCode = value
                    "country" -> builder?.country = value
                    "phonenumber", "phone" -> builder?.phone = value
                    "atemp", "airtemp", "air_temperature", "temperature" -> builder?.airTemperature = value?.toDoubleOrNull()
                    "wtemp", "watertemp", "water_temperature" -> builder?.waterTemperature = value?.toDoubleOrNull()
                    "depth" -> builder?.depth = value?.toDoubleOrNull()
                    "barometricaltitude", "barometric_altitude", "baroaltitude" -> builder?.barometricAltitude = value?.toDoubleOrNull()
                    "hr", "heartrate", "heart_rate" -> builder?.heartRate = value?.toDoubleOrNull()
                    "cad", "cadence" -> builder?.cadence = value?.toDoubleOrNull()
                    "power", "watts" -> builder?.power = value?.toDoubleOrNull()
                    "speed" -> builder?.speed = value?.toDoubleOrNull()
                    "course", "heading" -> builder?.courseDegrees = value?.toDoubleOrNull()
                    "route_information", "surface" -> builder?.surface = value
                    "max_speed", "maxspeed" -> builder?.maximumSpeed = value?.toDoubleOrNull()
                    "color" -> if (builder != null) builder.displayColor = value else courseColor = value
                    "width" -> if (builder == null) courseWidth = value
                    "translucent_line_colors", "translucent" -> if (builder == null) {
                        courseTranslucent = value.equals("yes", true) || value.equals("true", true) || value == "1"
                    }
                    "trkpt", "rtept", "wpt" -> {
                        val completed = requireNotNull(builder).build()
                        when (builder.element) {
                            "trkpt" -> {
                                if (pendingTrackBreak) {
                                    trackBreaks += trackPoints.size
                                    pendingTrackBreak = false
                                }
                                trackPoints += completed
                            }
                            "rtept" -> routePoints += completed
                            else -> {
                                check(waypoints.size < maximumWaypoints) { "GPX exceeds $maximumWaypoints waypoints" }
                                waypoints += GpxWaypoint(completed)
                            }
                        }
                        check(trackPoints.size + routePoints.size <= maximumCoursePoints) {
                            "GPX exceeds $maximumCoursePoints course points"
                        }
                        point = null
                        currentLink = null
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
                landmarks = if (trackPoints.isNotEmpty() || routePoints.isNotEmpty()) waypoints.map(GpxWaypoint::point) else emptyList(),
                style = CourseStyle(courseColor, courseWidth, courseTranslucent),
            ),
            waypoints = waypoints,
        )
    }

    private class PointBuilder(
        val element: String,
        val latitude: Double?,
        val longitude: Double?,
    ) {
        var elevation: Double? = null
        var time: Instant? = null
        var name: String? = null
        var type: String? = null
        var symbol: String? = null
        var description: String? = null
        var comment: String? = null
        val links = ArrayList<CourseLink>()
        var street: String? = null
        var city: String? = null
        var state: String? = null
        var postalCode: String? = null
        var country: String? = null
        var phone: String? = null
        var displayColor: String? = null
        var airTemperature: Double? = null
        var waterTemperature: Double? = null
        var depth: Double? = null
        var barometricAltitude: Double? = null
        var heartRate: Double? = null
        var cadence: Double? = null
        var power: Double? = null
        var speed: Double? = null
        var courseDegrees: Double? = null
        var surface: String? = null
        var maximumSpeed: Double? = null

        fun build(): CoursePoint {
            val lat = requireNotNull(latitude) { "GPX point is missing latitude" }
            val lon = requireNotNull(longitude) { "GPX point is missing longitude" }
            val role = CourseSemantics.role(element, type, symbol)
            val address = CourseAddress(street, city, state, postalCode, country).takeUnless(CourseAddress::isEmpty)
            return CoursePoint(
                position = GeoPoint(lat, lon, elevation),
                time = time,
                name = name?.take(MAX_POINT_TEXT_CHARS),
                type = type?.take(MAX_POINT_TEXT_CHARS),
                symbol = symbol?.take(MAX_POINT_TEXT_CHARS),
                description = description?.take(MAX_DESCRIPTION_CHARS),
                comment = comment?.take(MAX_DESCRIPTION_CHARS),
                role = role,
                category = CourseSemantics.category(type, symbol, name),
                maneuver = CourseSemantics.maneuver(symbol) ?: CourseSemantics.maneuver(type),
                links = links.toList(),
                address = address,
                phoneNumber = phone?.take(MAX_POINT_TEXT_CHARS),
                displayColor = displayColor?.take(MAX_POINT_TEXT_CHARS),
                metrics = CoursePointMetrics(
                    airTemperatureCelsius = airTemperature,
                    waterTemperatureCelsius = waterTemperature,
                    depthMeters = depth,
                    barometricAltitudeMeters = barometricAltitude,
                    heartRateBpm = heartRate,
                    cadenceRpm = cadence,
                    powerWatts = power,
                    speedMetersPerSecond = speed,
                    courseDegrees = courseDegrees,
                    surface = surface?.take(MAX_POINT_TEXT_CHARS),
                    maximumSpeed = maximumSpeed,
                ),
            )
        }
    }

    private class LinkBuilder(val href: String) {
        var text: String? = null
        var mimeType: String? = null
        fun build() = CourseLink(href, text?.take(MAX_POINT_TEXT_CHARS), mimeType?.take(MAX_POINT_TEXT_CHARS))
    }

    private fun String.toInstantOrNull(): Instant? = runCatching { Instant.parse(this) }.getOrNull()
    private fun elementName(localName: String?, qName: String?): String =
        localName.orEmpty().ifBlank { qName.orEmpty().substringAfter(':') }.lowercase(Locale.ROOT)

    private fun SAXParserFactory.disableExternalXmlEntities() {
        sequenceOf(
            "http://apache.org/xml/features/disallow-doctype-decl",
            "http://xml.org/sax/features/external-general-entities",
            "http://xml.org/sax/features/external-parameter-entities",
            "http://apache.org/xml/features/nonvalidating/load-external-dtd",
        ).forEach { feature -> runCatching { setFeature(feature, feature.endsWith("disallow-doctype-decl")) } }
    }

    private companion object {
        const val MAX_ELEMENT_TEXT_CHARS = 4_096
        const val MAX_POINT_TEXT_CHARS = 256
        const val MAX_DESCRIPTION_CHARS = 1_024
        const val MAX_LINK_CHARS = 2_048
        const val MAX_LINKS_PER_POINT = 8
    }
}
