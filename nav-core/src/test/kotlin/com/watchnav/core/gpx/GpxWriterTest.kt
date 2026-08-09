package com.watchnav.core.gpx

import com.watchnav.core.Course
import com.watchnav.core.CoursePoint
import com.watchnav.core.GeoPoint
import java.io.ByteArrayOutputStream
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class GpxWriterTest {
    @Test
    fun writtenCourseCanBeParsedAgain() {
        val course = Course(
            name = "Ridge & summit",
            points = listOf(
                CoursePoint(GeoPoint(31.0, 121.0, 12.0), Instant.parse("2026-08-08T01:00:00Z")),
                CoursePoint(GeoPoint(31.1, 121.1, 34.0), Instant.parse("2026-08-08T01:05:00Z")),
                CoursePoint(GeoPoint(31.2, 121.2, 20.0), Instant.parse("2026-08-08T01:10:00Z")),
            ),
            breakBeforePointIndices = setOf(2),
        )
        val output = ByteArrayOutputStream()

        GpxWriter.write(course, output)
        val parsed = GpxParser().parse(output.toByteArray().inputStream()).course

        assertEquals(course.name, parsed.name)
        assertEquals(course.points.map { it.position }, parsed.points.map { it.position })
        assertEquals(course.points.map { it.time }, parsed.points.map { it.time })
        assertEquals(course.breakBeforePointIndices, parsed.breakBeforePointIndices)
    }
}
