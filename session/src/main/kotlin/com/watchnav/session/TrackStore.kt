package com.watchnav.session

import android.content.Context
import com.watchnav.core.Course
import com.watchnav.core.CoursePoint
import com.watchnav.core.GeoMath
import com.watchnav.core.LocationFix
import com.watchnav.core.gpx.GpxWriter
import com.watchnav.core.fit.FitWriter
import java.io.File
import java.io.OutputStream
import java.time.Instant

class TrackStore(context: Context) {
    private val activityDirectory = File(context.filesDir, "activities")
    private val trackFile = File(activityDirectory, "latest-track.csv")
    private val points = ArrayList<CoursePoint>()
    private val pendingLines = ArrayList<String>()

    init {
        loadExisting()
    }

    @Synchronized
    fun startNew() {
        points.clear()
        pendingLines.clear()
        activityDirectory.mkdirs()
        trackFile.writeText(HEADER)
    }

    @Synchronized
    fun record(fix: LocationFix) {
        if (points.size >= MAXIMUM_POINTS) return
        val point = CoursePoint(
            position = fix.position,
            time = Instant.ofEpochMilli(fix.timestampMillis),
        )
        val previous = points.lastOrNull()
        if (previous != null) {
            val distance = GeoMath.distanceMeters(previous.position, point.position)
            val elapsed = fix.timestampMillis - (previous.time?.toEpochMilli() ?: 0L)
            if (distance < MINIMUM_DISTANCE_METERS && elapsed < MAXIMUM_INTERVAL_MILLIS) return
        }
        points += point
        pendingLines += encode(point)
        if (pendingLines.size >= FLUSH_POINT_COUNT) flush()
    }

    @Synchronized
    fun flush() {
        if (pendingLines.isEmpty()) return
        activityDirectory.mkdirs()
        if (!trackFile.exists()) trackFile.writeText(HEADER)
        trackFile.appendText(pendingLines.joinToString(separator = "", postfix = ""))
        pendingLines.clear()
    }

    @Synchronized
    fun course(
        name: String = "Recorded activity",
        reverse: Boolean = false,
        simplifyToleranceMeters: Double = 0.0,
    ): Course? {
        if (points.size < 2) return null
        val source = Course(name, points.toList())
        val retained = if (simplifyToleranceMeters > 0.0) {
            com.watchnav.core.CourseRenderSimplifier.retainedPointIndices(source, simplifyToleranceMeters)
                .map(points::get)
        } else {
            points.toList()
        }
        return Course(name, if (reverse) retained.asReversed() else retained)
    }

    @Synchronized
    fun exportGpx(output: OutputStream) {
        val course = course() ?: error("No recorded activity to export")
        GpxWriter.write(course, output)
    }

    @Synchronized
    fun exportFit(output: OutputStream) {
        val course = course() ?: error("No recorded activity to export")
        FitWriter.write(course, output)
    }

    @Synchronized
    fun pointCount(): Int = points.size

    @Synchronized
    fun recentPositions(maximumPoints: Int = 80): List<com.watchnav.core.GeoPoint> = points
        .takeLast(maximumPoints.coerceAtLeast(2))
        .map(CoursePoint::position)

    @Synchronized
    fun courseFrom(pointIndex: Int, name: String): Course? {
        val retained = points.drop(pointIndex.coerceIn(0, points.size))
        return retained.takeIf { it.size >= 2 }?.let { Course(name, it) }
    }

    private fun loadExisting() {
        if (!trackFile.isFile) return
        trackFile.useLines { lines ->
            lines.drop(1).take(MAXIMUM_POINTS).mapNotNull(::decode).forEach(points::add)
        }
    }

    private fun encode(point: CoursePoint): String = buildString {
        append(point.position.latitude).append(',')
        append(point.position.longitude).append(',')
        append(point.position.elevationMeters ?: "").append(',')
        append(point.time?.toEpochMilli() ?: 0L).append('\n')
    }

    private fun decode(line: String): CoursePoint? = runCatching {
        val columns = line.split(',')
        if (columns.size != 4) return null
        CoursePoint(
            position = com.watchnav.core.GeoPoint(
                latitude = columns[0].toDouble(),
                longitude = columns[1].toDouble(),
                elevationMeters = columns[2].takeIf(String::isNotEmpty)?.toDouble(),
            ),
            time = columns[3].toLong().takeIf { it > 0 }?.let(Instant::ofEpochMilli),
        )
    }.getOrNull()

    private companion object {
        const val HEADER = "latitude,longitude,elevation,time\n"
        const val MAXIMUM_POINTS = 100_000
        const val MINIMUM_DISTANCE_METERS = 5.0
        const val MAXIMUM_INTERVAL_MILLIS = 5_000L
        const val FLUSH_POINT_COUNT = 10
    }
}
