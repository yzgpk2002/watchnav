package com.watchnav.core.gpx

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GpxWaypointCourseTest {
    @Test
    fun usesWaypointsAsCourseWhenNoTrackOrRouteExists() {
        val gpx = """
            <gpx xmlns="http://www.topografix.com/GPX/1/1" version="1.1">
              <wpt lat="30.1" lon="120.1"><ele>10</ele></wpt>
              <wpt lat="30.2" lon="120.2"><ele>20</ele></wpt>
            </gpx>
        """.trimIndent()

        val document = GpxParser().parse(gpx.byteInputStream(), "Downloaded sample")

        assertEquals("Downloaded sample", document.course.name)
        assertEquals(2, document.course.points.size)
    }

    @Test
    fun preservesNamedWaypointsAlongsideATrack() {
        val gpx = """
            <gpx xmlns="http://www.topografix.com/GPX/1/1" version="1.1">
              <wpt lat="30.15" lon="120.15"><name>补给站</name><type>aid_station</type></wpt>
              <trk><name>Race</name><trkseg>
                <trkpt lat="30.1" lon="120.1" />
                <trkpt lat="30.2" lon="120.2" />
              </trkseg></trk>
            </gpx>
        """.trimIndent()

        val parsed = GpxParser().parse(gpx.byteInputStream())
        val output = java.io.ByteArrayOutputStream()
        GpxWriter.write(parsed.course, output)
        val reparsed = GpxParser().parse(output.toByteArray().inputStream())

        assertEquals("补给站", reparsed.course.landmarks.single().name)
        assertEquals("aid_station", reparsed.course.landmarks.single().type)
        assertTrue(output.toString(Charsets.UTF_8).contains("<wpt"))
    }
}
