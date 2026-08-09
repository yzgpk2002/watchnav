package com.watchnav.core.gpx

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
