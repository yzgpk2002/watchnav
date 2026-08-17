package com.watchnav.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoordinateConverterTest {
    @Test
    fun `GCJ inverse recovers WGS coordinate`() {
        val wgs = GeoPoint(39.908823, 116.397470)
        val gcj = CoordinateConverter.wgs84ToGcj02(wgs)
        val recovered = CoordinateConverter.toWgs84(gcj, CoordinateSystem.GCJ02)
        assertTrue(GeoMath.distanceMeters(wgs, recovered) < 0.1)
    }

    @Test
    fun `coordinates outside China stay unchanged`() {
        val paris = GeoPoint(48.8566, 2.3522, 31.0)
        assertEquals(paris, CoordinateConverter.toWgs84(paris, CoordinateSystem.GCJ02))
    }

    @Test
    fun `course conversion includes landmarks and elevations`() {
        val source = GeoPoint(30.25, 120.15, 88.0)
        val gcj = CoordinateConverter.wgs84ToGcj02(source)
        val course = Course(
            "test",
            listOf(CoursePoint(gcj), CoursePoint(gcj.copy(latitude = gcj.latitude + 0.001))),
            landmarks = listOf(CoursePoint(gcj, name = "note")),
        )
        val converted = CoordinateConverter.courseToWgs84(course, CoordinateSystem.GCJ02)
        assertTrue(GeoMath.distanceMeters(source, converted.points.first().position) < 0.1)
        assertEquals(88.0, converted.landmarks.first().position.elevationMeters)
    }
}
