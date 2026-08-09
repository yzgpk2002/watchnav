package com.watchnav.core.fit

import com.watchnav.core.Course
import com.watchnav.core.CoursePoint
import com.watchnav.core.GeoPoint
import java.io.ByteArrayOutputStream
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FitWriterTest {
    @Test
    fun writesFitHeaderAndRecords() {
        val course = Course(
            "Activity",
            listOf(
                CoursePoint(GeoPoint(31.0, 121.0, 10.0), Instant.parse("2026-08-08T01:00:00Z")),
                CoursePoint(GeoPoint(31.001, 121.001, 20.0), Instant.parse("2026-08-08T01:01:00Z")),
            ),
        )
        val output = ByteArrayOutputStream()

        FitWriter.write(course, output)
        val bytes = output.toByteArray()

        assertEquals(14, bytes[0].toInt())
        assertEquals(".FIT", bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII))
        assertTrue(bytes.size > 60)
    }
}
