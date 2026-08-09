package com.watchnav.core.gpx

import com.watchnav.core.Course
import java.io.OutputStream

object GpxWriter {
    fun write(course: Course, output: OutputStream) {
        output.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            writer.appendLine("<gpx version=\"1.1\" creator=\"WatchNav\" xmlns=\"http://www.topografix.com/GPX/1/1\">")
            writer.appendLine("  <trk>")
            writer.append("    <name>").append(xml(course.name)).appendLine("</name>")
            writer.appendLine("    <trkseg>")
            course.points.forEachIndexed { index, point ->
                if (index in course.breakBeforePointIndices) {
                    writer.appendLine("    </trkseg>")
                    writer.appendLine("    <trkseg>")
                }
                writer.append("      <trkpt lat=\"")
                    .append(point.position.latitude.toString())
                    .append("\" lon=\"")
                    .append(point.position.longitude.toString())
                    .appendLine("\">")
                point.position.elevationMeters?.let {
                    writer.append("        <ele>").append(it.toString()).appendLine("</ele>")
                }
                point.time?.let {
                    writer.append("        <time>").append(it.toString()).appendLine("</time>")
                }
                writer.appendLine("      </trkpt>")
            }
            writer.appendLine("    </trkseg>")
            writer.appendLine("  </trk>")
            writer.appendLine("</gpx>")
        }
    }

    private fun xml(value: String): String = buildString(value.length) {
        value.forEach { character ->
            when (character) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '\"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(character)
            }
        }
    }
}
