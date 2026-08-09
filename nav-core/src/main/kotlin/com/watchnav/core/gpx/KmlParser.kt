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

class KmlParser(private val maximumCoursePoints: Int = 200_000) {
    fun parse(input: InputStream, fallbackName: String = "Imported KML course"): Course {
        val points = ArrayList<CoursePoint>()
        val breaks = LinkedHashSet<Int>()
        val gxTimes = ArrayList<Instant?>()
        var gxTimeIndex = 0
        var documentName: String? = null
        var placemarkName: String? = null
        var courseName: String? = null
        var insideDocument = false
        var insidePlacemark = false
        var insideLineString = false
        var insideGxTrack = false
        var lineHasPoints = false
        val text = StringBuilder()

        fun addPoint(longitude: Double, latitude: Double, elevation: Double?, time: Instant? = null) {
            require(latitude in -90.0..90.0 && longitude in -180.0..180.0) {
                "KML contains an invalid coordinate"
            }
            if (!lineHasPoints && points.isNotEmpty()) breaks += points.size
            points += CoursePoint(GeoPoint(latitude, longitude, elevation), time)
            lineHasPoints = true
            check(points.size <= maximumCoursePoints) { "KML exceeds $maximumCoursePoints course points" }
            if (courseName == null) courseName = placemarkName
        }

        val handler = object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                text.setLength(0)
                when (elementName(localName, qName)) {
                    "document" -> insideDocument = true
                    "placemark" -> {
                        insidePlacemark = true
                        placemarkName = null
                    }
                    "linestring" -> {
                        insideLineString = true
                        lineHasPoints = false
                    }
                    "track" -> {
                        insideGxTrack = true
                        lineHasPoints = false
                        gxTimes.clear()
                        gxTimeIndex = 0
                    }
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                text.append(ch, start, length)
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                val value = text.toString().trim()
                when (elementName(localName, qName)) {
                    "name" -> when {
                        insidePlacemark && placemarkName == null -> placemarkName = value.ifBlank { null }
                        insideDocument && documentName == null -> documentName = value.ifBlank { null }
                    }
                    "coordinates" -> if (insideLineString) {
                        value.split(Regex("\\s+")).filter(String::isNotBlank).forEach { tuple ->
                            val coordinate = tuple.split(',')
                            require(coordinate.size >= 2) { "KML contains a malformed coordinate" }
                            addPoint(
                                longitude = coordinate[0].toDouble(),
                                latitude = coordinate[1].toDouble(),
                                elevation = coordinate.getOrNull(2)?.toDoubleOrNull(),
                            )
                        }
                    }
                    "when" -> if (insideGxTrack) gxTimes += value.toInstantOrNull()
                    "coord" -> if (insideGxTrack) {
                        val coordinate = value.split(Regex("\\s+")).filter(String::isNotBlank)
                        require(coordinate.size >= 2) { "KML contains a malformed gx:coord" }
                        addPoint(
                            longitude = coordinate[0].toDouble(),
                            latitude = coordinate[1].toDouble(),
                            elevation = coordinate.getOrNull(2)?.toDoubleOrNull(),
                            time = gxTimes.getOrNull(gxTimeIndex++),
                        )
                    }
                    "linestring" -> insideLineString = false
                    "track" -> insideGxTrack = false
                    "placemark" -> insidePlacemark = false
                    "document" -> insideDocument = false
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

        require(points.size >= 2) { "KML contains no navigable LineString or gx:Track" }
        return Course(
            name = courseName ?: documentName ?: fallbackName,
            points = points,
            breakBeforePointIndices = breaks,
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
}
