package com.watchnav.core

import com.watchnav.core.gpx.GpxParser
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

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
}
