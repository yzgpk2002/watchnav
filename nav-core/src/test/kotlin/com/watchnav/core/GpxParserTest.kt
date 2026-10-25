package com.watchnav.core

import com.watchnav.core.gpx.GpxParser
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GpxParserTest {
    @Test
    fun parsesTrackSegmentsElevationTimeAndWaypoint() {
        val xml = """
            <gpx version="1.1" creator="watchnav">
              <wpt lat="31.0" lon="121.0"><name>Water</name></wpt>
              <trk><name>Ridge route</name>
                <trkseg>
                  <trkpt lat="31.0000" lon="121.0000"><ele>12.5</ele><time>2026-01-01T00:00:00Z</time></trkpt>
                  <trkpt lat="31.0010" lon="121.0000"/>
                </trkseg>
                <trkseg>
                  <trkpt lat="31.0020" lon="121.0000"/>
                  <trkpt lat="31.0030" lon="121.0000"/>
                </trkseg>
              </trk>
            </gpx>
        """.trimIndent()

        val document = GpxParser().parse(ByteArrayInputStream(xml.toByteArray()))

        assertEquals("Ridge route", document.course.name)
        assertEquals(4, document.course.points.size)
        assertEquals(setOf(2), document.course.breakBeforePointIndices)
        assertEquals(12.5, document.course.points.first().position.elevationMeters)
        assertNotNull(document.course.points.first().time)
        assertEquals("Water", document.waypoints.single().point.name)
    }

    @Test
    fun rejectsCoordinateOutsideWorldBounds() {
        val xml = """<gpx><rte><rtept lat="95" lon="0"/><rtept lat="0" lon="0"/></rte></gpx>"""
        assertFailsWith<IllegalArgumentException> {
            GpxParser().parse(ByteArrayInputStream(xml.toByteArray()))
        }
    }

    @Test
    fun rejectsDocumentTypeAndExternalEntityInput() {
        val xml = """
            <!DOCTYPE gpx [<!ENTITY external SYSTEM "file:///not-readable">]>
            <gpx><rte><name>&external;</name><rtept lat="31" lon="121"/><rtept lat="31.1" lon="121"/></rte></gpx>
        """.trimIndent()

        assertFailsWith<Exception> {
            GpxParser().parse(ByteArrayInputStream(xml.toByteArray()))
        }
    }

    @Test
    fun parsesNavigationPoiBusinessSensorAndStyleExtensions() {
        val xml = """
            <gpx version="1.1" xmlns:gpxx="http://www.garmin.com/xmlschemas/GpxExtensions/v3"
                 xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v2"
                 xmlns:osmand="https://osmand.net">
              <wpt lat="31.001" lon="121.001"><name>山泉</name><sym>Drinking Water</sym><type>via</type>
                <link href="https://example.test/water"><text>现场照片</text><type>image/jpeg</type></link>
                <extensions><gpxx:WaypointExtension><gpxx:DisplayColor>Blue</gpxx:DisplayColor>
                  <gpxx:Address><gpxx:StreetAddress>山路1号</gpxx:StreetAddress><gpxx:City>杭州</gpxx:City></gpxx:Address>
                  <gpxx:PhoneNumber>12345</gpxx:PhoneNumber></gpxx:WaypointExtension></extensions>
              </wpt>
              <trk><name>语义路线</name><extensions><osmand:color>#FF00FF</osmand:color><osmand:width>bold</osmand:width></extensions><trkseg>
                <trkpt lat="31" lon="121"/>
                <trkpt lat="31.001" lon="121"><name>北门</name><sym>left_fork</sym><type>via</type>
                  <extensions><gpxtpx:TrackPointExtension><gpxtpx:atemp>22.5</gpxtpx:atemp><gpxtpx:hr>108</gpxtpx:hr>
                    <gpxtpx:speed>1.2</gpxtpx:speed><gpxtpx:course>350</gpxtpx:course></gpxtpx:TrackPointExtension>
                    <route_information>unpaved</route_information><max_speed>6</max_speed></extensions>
                </trkpt>
                <trkpt lat="31.002" lon="121"/>
              </trkseg></trk>
            </gpx>
        """.trimIndent()

        val document = GpxParser().parse(ByteArrayInputStream(xml.toByteArray()))
        val waypoint = document.course.landmarks.single()
        val turn = document.course.points[1]

        assertEquals(CoursePointCategory.WATER, waypoint.category)
        assertEquals(CoursePointRole.VIA, waypoint.role)
        assertEquals("杭州", waypoint.address?.city)
        assertEquals("12345", waypoint.phoneNumber)
        assertEquals("image/jpeg", waypoint.links.single().mimeType)
        assertEquals(ManeuverKind.SLIGHT_LEFT, turn.maneuver)
        assertEquals(22.5, turn.metrics.airTemperatureCelsius)
        assertEquals(108.0, turn.metrics.heartRateBpm)
        assertEquals("unpaved", turn.metrics.surface)
        assertEquals("#FF00FF", document.course.style.color)
        assertTrue(document.course.style.width == "bold")
    }
}
