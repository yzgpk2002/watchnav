package com.watchnav.core.gpx

import com.watchnav.core.Course
import com.watchnav.core.CoursePoint
import com.watchnav.core.ManeuverKind
import java.io.OutputStream

object GpxWriter {
    fun write(course: Course, output: OutputStream) {
        output.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            writer.appendLine(
                "<gpx version=\"1.1\" creator=\"WatchNav\" xmlns=\"http://www.topografix.com/GPX/1/1\" " +
                    "xmlns:gpxx=\"http://www.garmin.com/xmlschemas/GpxExtensions/v3\" " +
                    "xmlns:gpxtpx=\"http://www.garmin.com/xmlschemas/TrackPointExtension/v2\" " +
                    "xmlns:osmand=\"https://osmand.net\" xmlns:watchnav=\"https://watchnav.app/gpx/1\">",
            )
            course.landmarks.forEach { point ->
                writer.append("  <wpt lat=\"")
                    .append(point.position.latitude.toString())
                    .append("\" lon=\"")
                    .append(point.position.longitude.toString())
                    .appendLine("\">")
                point.position.elevationMeters?.let {
                    writer.append("    <ele>").append(it.toString()).appendLine("</ele>")
                }
                writePointDetails(writer, point, "    ")
                writer.appendLine("  </wpt>")
            }
            writer.appendLine("  <trk>")
            writer.append("    <name>").append(xml(course.name)).appendLine("</name>")
            if (course.style.color != null || course.style.width != null || course.style.translucent) {
                writer.appendLine("    <extensions>")
                course.style.color?.let { writer.append("      <osmand:color>").append(xml(it)).appendLine("</osmand:color>") }
                course.style.width?.let { writer.append("      <osmand:width>").append(xml(it)).appendLine("</osmand:width>") }
                if (course.style.translucent) writer.appendLine("      <osmand:translucent_line_colors>yes</osmand:translucent_line_colors>")
                writer.appendLine("    </extensions>")
            }
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
                writePointDetails(writer, point, "        ")
                writer.appendLine("      </trkpt>")
            }
            writer.appendLine("    </trkseg>")
            writer.appendLine("  </trk>")
            writer.appendLine("</gpx>")
        }
    }

    private fun writePointDetails(writer: java.io.Writer, point: CoursePoint, indent: String) {
        point.name?.let { writer.append(indent).append("<name>").append(xml(it)).appendLine("</name>") }
        point.comment?.let { writer.append(indent).append("<cmt>").append(xml(it)).appendLine("</cmt>") }
        point.description?.let { writer.append(indent).append("<desc>").append(xml(it)).appendLine("</desc>") }
        val symbol = point.symbol ?: point.maneuver?.toGpxSymbol()
        symbol?.let { writer.append(indent).append("<sym>").append(xml(it)).appendLine("</sym>") }
        point.type?.let { writer.append(indent).append("<type>").append(xml(it)).appendLine("</type>") }
        point.links.forEach { link ->
            writer.append(indent).append("<link href=\"").append(xml(link.href)).appendLine("\">")
            link.text?.let { writer.append(indent).append("  <text>").append(xml(it)).appendLine("</text>") }
            link.mimeType?.let { writer.append(indent).append("  <type>").append(xml(it)).appendLine("</type>") }
            writer.append(indent).appendLine("</link>")
        }
        val metrics = point.metrics
        val hasTrackMetrics = listOf(
            metrics.airTemperatureCelsius,
            metrics.waterTemperatureCelsius,
            metrics.depthMeters,
            metrics.heartRateBpm,
            metrics.cadenceRpm,
            metrics.powerWatts,
            metrics.speedMetersPerSecond,
            metrics.courseDegrees,
        ).any { it != null }
        val hasGarminDetails = point.displayColor != null || point.address != null || point.phoneNumber != null
        val hasWatchNavDetails = metrics.barometricAltitudeMeters != null || metrics.surface != null || metrics.maximumSpeed != null
        if (!hasTrackMetrics && !hasGarminDetails && !hasWatchNavDetails) return
        writer.append(indent).appendLine("<extensions>")
        if (hasGarminDetails) {
            writer.append(indent).appendLine("  <gpxx:WaypointExtension>")
            point.displayColor?.let { writer.append(indent).append("    <gpxx:DisplayColor>").append(xml(it)).appendLine("</gpxx:DisplayColor>") }
            point.address?.let { address ->
                writer.append(indent).appendLine("    <gpxx:Address>")
                address.street?.let { writer.append(indent).append("      <gpxx:StreetAddress>").append(xml(it)).appendLine("</gpxx:StreetAddress>") }
                address.city?.let { writer.append(indent).append("      <gpxx:City>").append(xml(it)).appendLine("</gpxx:City>") }
                address.state?.let { writer.append(indent).append("      <gpxx:State>").append(xml(it)).appendLine("</gpxx:State>") }
                address.postalCode?.let { writer.append(indent).append("      <gpxx:PostalCode>").append(xml(it)).appendLine("</gpxx:PostalCode>") }
                address.country?.let { writer.append(indent).append("      <gpxx:Country>").append(xml(it)).appendLine("</gpxx:Country>") }
                writer.append(indent).appendLine("    </gpxx:Address>")
            }
            point.phoneNumber?.let { writer.append(indent).append("    <gpxx:PhoneNumber>").append(xml(it)).appendLine("</gpxx:PhoneNumber>") }
            writer.append(indent).appendLine("  </gpxx:WaypointExtension>")
        }
        if (hasTrackMetrics) {
            writer.append(indent).appendLine("  <gpxtpx:TrackPointExtension>")
            metrics.airTemperatureCelsius?.writeTag(writer, indent, "gpxtpx:atemp")
            metrics.waterTemperatureCelsius?.writeTag(writer, indent, "gpxtpx:wtemp")
            metrics.depthMeters?.writeTag(writer, indent, "gpxtpx:depth")
            metrics.heartRateBpm?.writeTag(writer, indent, "gpxtpx:hr")
            metrics.cadenceRpm?.writeTag(writer, indent, "gpxtpx:cad")
            metrics.powerWatts?.writeTag(writer, indent, "gpxtpx:power")
            metrics.speedMetersPerSecond?.writeTag(writer, indent, "gpxtpx:speed")
            metrics.courseDegrees?.writeTag(writer, indent, "gpxtpx:course")
            writer.append(indent).appendLine("  </gpxtpx:TrackPointExtension>")
        }
        metrics.barometricAltitudeMeters?.writeTag(writer, indent, "watchnav:barometric_altitude", 1)
        metrics.surface?.let { writer.append(indent).append("  <watchnav:route_information>").append(xml(it)).appendLine("</watchnav:route_information>") }
        metrics.maximumSpeed?.writeTag(writer, indent, "watchnav:max_speed", 1)
        writer.append(indent).appendLine("</extensions>")
    }

    private fun Double.writeTag(writer: java.io.Writer, indent: String, tag: String, extraIndent: Int = 2) {
        writer.append(indent).append("  ".repeat(extraIndent)).append('<').append(tag).append('>')
            .append(toString()).append("</").append(tag).appendLine(">")
    }

    private fun ManeuverKind.toGpxSymbol(): String? = when (this) {
        ManeuverKind.START -> "straight"
        ManeuverKind.SLIGHT_LEFT -> "keep_left"
        ManeuverKind.LEFT -> "left"
        ManeuverKind.SHARP_LEFT -> "left_sharp"
        ManeuverKind.U_TURN -> "u_turn"
        ManeuverKind.SHARP_RIGHT -> "right_sharp"
        ManeuverKind.RIGHT -> "right"
        ManeuverKind.SLIGHT_RIGHT -> "keep_right"
        ManeuverKind.FINISH -> "finish"
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
