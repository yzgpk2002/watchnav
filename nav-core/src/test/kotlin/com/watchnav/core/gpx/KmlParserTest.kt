package com.watchnav.core.gpx

import kotlin.test.Test
import kotlin.test.assertEquals

class KmlParserTest {
    @Test
    fun parsesLineStringCoordinatesAndBreaks() {
        val kml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <kml xmlns="http://www.opengis.net/kml/2.2">
              <Document><name>Trail day</name>
                <Placemark><name>Race course</name><LineString><coordinates>
                  120.1,30.1,10 120.2,30.2,20
                </coordinates></LineString></Placemark>
                <Placemark><LineString><coordinates>120.3,30.3,30</coordinates></LineString></Placemark>
              </Document>
            </kml>
        """.trimIndent()

        val course = KmlParser().parse(kml.byteInputStream())

        assertEquals("Race course", course.name)
        assertEquals(3, course.points.size)
        assertEquals(30.1, course.points.first().position.latitude)
        assertEquals(120.1, course.points.first().position.longitude)
        assertEquals(10.0, course.points.first().position.elevationMeters)
        assertEquals(setOf(2), course.breakBeforePointIndices)
    }

    @Test
    fun parsesGxTrackWithTimestamps() {
        val kml = """
            <kml xmlns="http://www.opengis.net/kml/2.2" xmlns:gx="http://www.google.com/kml/ext/2.2">
              <Placemark><name>Timed trail</name><gx:Track>
                <when>2026-08-08T01:00:00Z</when><when>2026-08-08T01:01:00Z</when>
                <gx:coord>120.1 30.1 10</gx:coord><gx:coord>120.2 30.2 20</gx:coord>
              </gx:Track></Placemark>
            </kml>
        """.trimIndent()

        val course = KmlParser().parse(kml.byteInputStream())

        assertEquals(2, course.points.size)
        assertEquals("2026-08-08T01:01:00Z", course.points.last().time.toString())
    }
}
