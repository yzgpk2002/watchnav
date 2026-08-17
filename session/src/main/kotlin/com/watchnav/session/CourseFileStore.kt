package com.watchnav.session

import android.content.Context
import com.watchnav.core.Course
import com.watchnav.core.CoordinateConverter
import com.watchnav.core.CoordinateSystem
import com.watchnav.core.GeoMath
import com.watchnav.core.GeoPoint
import com.watchnav.core.gpx.GpxParser
import com.watchnav.core.gpx.GpxWriter
import com.watchnav.core.gpx.KmlParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

data class StoredCourse(
    val course: Course,
    val file: File,
)

data class CourseLibraryEntry(
    val id: String,
    val name: String,
    val pointCount: Int,
    val file: File,
    val startPosition: GeoPoint? = null,
)

enum class CourseLibraryOrigin { IMPORTED, EXCELLENT }

class CourseFileStore(context: Context) {
    private val courseDirectory = File(context.filesDir, "courses")
    private val activeFile = File(courseDirectory, "active.gpx")
    private val activeOriginFile = File(courseDirectory, "active.origin")
    private val libraryDirectory = File(courseDirectory, "library")

    fun import(
        input: InputStream,
        fallbackName: String = "Imported course",
        coordinateSystem: CoordinateSystem = CoordinateSystem.WGS84,
    ): StoredCourse {
        val bytes = readBounded(input)
        val parsed = bytes.inputStream().use { GpxParser().parse(it, fallbackName).course }
        return saveCourse(CoordinateConverter.courseToWgs84(parsed, coordinateSystem), CourseLibraryOrigin.IMPORTED)
    }

    fun importKml(
        input: InputStream,
        fallbackName: String = "Imported KML course",
        coordinateSystem: CoordinateSystem = CoordinateSystem.WGS84,
    ): StoredCourse {
        val bytes = readBounded(input)
        require(bytes.isNotEmpty()) { "KML file is empty" }
        val course = bytes.inputStream().use { KmlParser().parse(it, fallbackName) }
        return saveCourse(CoordinateConverter.courseToWgs84(course, coordinateSystem), CourseLibraryOrigin.IMPORTED)
    }

    fun save(bytes: ByteArray, fallbackName: String = "Imported course"): StoredCourse {
        return save(bytes, fallbackName, CourseLibraryOrigin.IMPORTED)
    }

    /** Adds a synchronised route to the picker without replacing the route being navigated. */
    fun saveToLibraryOnly(bytes: ByteArray, fallbackName: String = "Imported course"): Course {
        require(bytes.isNotEmpty()) { "GPX file is empty" }
        require(bytes.size <= MAXIMUM_GPX_BYTES) { "GPX is larger than 16 MB" }
        val course = bytes.inputStream().use { GpxParser().parse(it, fallbackName).course }
        requireUsableCourse(course)
        saveToLibrary(bytes, course, CourseLibraryOrigin.IMPORTED)
        return course
    }

    private fun writeActive(bytes: ByteArray, libraryOrigin: CourseLibraryOrigin?) {
        courseDirectory.mkdirs()
        check(courseDirectory.isDirectory) { "Unable to create course storage" }
        val temporary = File(courseDirectory, "active.gpx.tmp")
        temporary.outputStream().use { it.write(bytes) }
        if (!temporary.renameTo(activeFile)) {
            temporary.copyTo(activeFile, overwrite = true)
            check(temporary.delete()) { "Unable to finish saving GPX" }
        }
        activeOriginFile.writeText(libraryOrigin?.name ?: "AUTO")
    }

    fun saveCourse(course: Course, libraryOrigin: CourseLibraryOrigin? = null): StoredCourse {
        requireUsableCourse(course)
        val output = ByteArrayOutputStream()
        GpxWriter.write(course, output)
        return save(output.toByteArray(), course.name, libraryOrigin)
    }

    private fun save(bytes: ByteArray, fallbackName: String, libraryOrigin: CourseLibraryOrigin?): StoredCourse {
        require(bytes.isNotEmpty()) { "GPX file is empty" }
        require(bytes.size <= MAXIMUM_GPX_BYTES) { "GPX is larger than 16 MB" }
        val course = bytes.inputStream().use { GpxParser().parse(it, fallbackName).course }
        requireUsableCourse(course)
        writeActive(bytes, libraryOrigin)
        if (libraryOrigin != null) saveToLibrary(bytes, course, libraryOrigin)
        return StoredCourse(course, activeFile)
    }

    fun load(): StoredCourse? {
        if (!activeFile.isFile) return null
        return runCatching {
            val course = activeFile.inputStream().buffered().use { GpxParser().parse(it).course }
            requireUsableCourse(course)
            StoredCourse(course, activeFile)
        }.getOrElse {
            quarantineInvalidActiveCourse()
            null
        }
    }

    fun activeFile(): File? = activeFile.takeIf(File::isFile)

    fun activeLibraryOrigin(): CourseLibraryOrigin? = activeOriginFile.takeIf(File::isFile)
        ?.readText()
        ?.trim()
        ?.takeIf { it != "AUTO" }
        ?.let { runCatching { CourseLibraryOrigin.valueOf(it) }.getOrNull() }

    fun listLibrary(): List<CourseLibraryEntry> {
        if (!libraryDirectory.isDirectory) return emptyList()
        pruneHiddenGeneratedCourses()
        return libraryDirectory.listFiles { file -> file.extension.equals("gpx", ignoreCase = true) }
            ?.sortedByDescending(File::lastModified)
            ?.mapNotNull { file ->
                runCatching {
                    // The route picker only needs a label and an optional start point. Parsing every
                    // complete GPX here used to build all CoursePoint objects twice (once here and
                    // once again for distance sorting), which could block a watch for many minutes.
                    // Read the small header instead and defer the full parse until the route is used.
                    val metadata = readLibraryMetadata(file)
                    if (!isVisibleLibraryCourse(file, metadata.name)) return@runCatching null
                    CourseLibraryEntry(
                        id = file.nameWithoutExtension,
                        name = metadata.name,
                        pointCount = metadata.pointCount,
                        file = file,
                        startPosition = metadata.startPosition,
                    )
                }.getOrNull()
            }
            ?.distinctBy { normalizedLibraryName(it.name) }
            ?.sortedBy(CourseLibraryEntry::name)
            ?: emptyList()
    }

    fun activate(entry: CourseLibraryEntry): StoredCourse =
        save(entry.file.readBytes(), entry.name, libraryOrigin(entry.file) ?: CourseLibraryOrigin.IMPORTED)

    fun read(entry: CourseLibraryEntry): Course =
        entry.file.inputStream().buffered().use { GpxParser().parse(it, entry.name).course }

    @Synchronized
    fun rename(entry: CourseLibraryEntry, newName: String): CourseLibraryEntry {
        val normalized = newName.trim().take(80)
        require(normalized.isNotBlank()) { "Route name is empty" }
        val renamed = read(entry).copy(name = normalized)
        val origin = libraryOrigin(entry.file) ?: CourseLibraryOrigin.IMPORTED
        val output = ByteArrayOutputStream()
        GpxWriter.write(renamed, output)
        val bytes = output.toByteArray()
        deleteLibraryFiles(entry)
        saveToLibrary(bytes, renamed, origin)
        val replacement = listLibrary().firstOrNull { it.name == normalized }
            ?: error("Unable to find renamed route")
        if (activeFile.isFile && runCatching { load()?.course?.name == entry.name }.getOrDefault(false)) {
            writeActive(bytes, origin)
        }
        return replacement
    }

    @Synchronized
    fun delete(entry: CourseLibraryEntry) {
        val normalizedName = normalizedLibraryName(entry.name)
        libraryDirectory.listFiles { file -> file.extension.equals("gpx", ignoreCase = true) }
            ?.filter { file ->
                runCatching { normalizedLibraryName(readLibraryMetadata(file).name) == normalizedName }
                    .getOrDefault(file == entry.file)
            }
            ?.forEach { file ->
                deleteLibraryFiles(
                    CourseLibraryEntry(file.nameWithoutExtension, entry.name, UNKNOWN_POINT_COUNT, file),
                )
            }
        if (activeFile.isFile && runCatching {
                normalizedLibraryName(load()?.course?.name.orEmpty()) == normalizedName
            }.getOrDefault(false)
        ) {
            activeFile.delete()
            activeOriginFile.delete()
        }
    }

    private fun normalizedLibraryName(name: String): String =
        name.trim().lowercase().replace(Regex("\\s+"), " ")

    fun exportActive(output: java.io.OutputStream) {
        val file = activeFile().let(::requireNotNull)
        file.inputStream().buffered().use { input -> output.buffered().use(input::copyTo) }
    }

    private fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream(DEFAULT_BUFFER_SIZE)
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= MAXIMUM_GPX_BYTES) { "Track file is larger than 16 MB" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun saveToLibrary(bytes: ByteArray, course: Course, origin: CourseLibraryOrigin) {
        libraryDirectory.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .take(6).joinToString("") { "%02x".format(it) }
        val safeName = course.name.replace(Regex("[^A-Za-z0-9._-]+"), "-")
            .trim('-').take(40).ifBlank { "course" }
        val libraryFile = File(libraryDirectory, "$safeName-$digest.gpx")
        if (!libraryFile.exists()) libraryFile.outputStream().use { it.write(bytes) }
        File(libraryDirectory, libraryFile.nameWithoutExtension + ".origin").writeText(origin.name)
        File(libraryDirectory, libraryFile.nameWithoutExtension + ".info").writeText(
            buildString {
                appendLine(course.points.size)
                appendLine(course.name)
                course.points.firstOrNull()?.position?.let { start ->
                    append(start.latitude).append(',').append(start.longitude)
                }
            },
        )
    }

    private fun isVisibleLibraryCourse(file: File, courseName: String): Boolean {
        // Old builds accidentally persisted automatic rejoin/routing/history tracks in the route
        // library. Never make the picker parse or display those generated files, even if an old
        // sidecar incorrectly marked one as imported.
        val fileName = file.nameWithoutExtension.lowercase()
        if (HIDDEN_LIBRARY_PREFIXES.any(fileName::startsWith)) return false
        val recordedOrigin = libraryOrigin(file)
        if (recordedOrigin != null) return true
        val normalized = courseName.trim().lowercase()
        return HIDDEN_LIBRARY_PREFIXES.none(normalized::startsWith)
    }

    /** Remove route-rejoin and recorded-history files written into the user library by old builds.
     * Activity history remains in activities/archive; only entries that have always been hidden
     * from the imported/excellent route picker are pruned. This also prevents every picker open
     * from scanning tens of megabytes of obsolete generated GPX files. */
    private fun pruneHiddenGeneratedCourses() {
        libraryDirectory.listFiles { file -> file.extension.equals("gpx", ignoreCase = true) }
            ?.filter { file ->
                val fileName = file.nameWithoutExtension.lowercase()
                HIDDEN_LIBRARY_PREFIXES.any(fileName::startsWith) || runCatching {
                    val courseName = readLibraryMetadata(file).name.trim().lowercase()
                    HIDDEN_LIBRARY_PREFIXES.any(courseName::startsWith)
                }.getOrDefault(false)
            }
            ?.forEach { file ->
                deleteLibraryFiles(
                    CourseLibraryEntry(file.nameWithoutExtension, file.nameWithoutExtension, UNKNOWN_POINT_COUNT, file),
                )
            }
    }

    private fun readLibraryMetadata(file: File): LibraryMetadata {
        val infoFile = File(libraryDirectory, file.nameWithoutExtension + ".info")
        if (infoFile.isFile) {
            runCatching {
                val lines = infoFile.readLines()
                val start = lines.getOrNull(2)?.split(',')?.takeIf { it.size == 2 }?.let { values ->
                    GeoPoint(values[0].toDouble(), values[1].toDouble())
                }
                return LibraryMetadata(
                    name = lines.getOrNull(1)?.trim().orEmpty().ifBlank { displayNameFromFile(file) },
                    pointCount = lines.firstOrNull()?.toIntOrNull() ?: UNKNOWN_POINT_COUNT,
                    startPosition = start,
                )
            }
        }
        val header = file.inputStream().buffered().use { input ->
            val bytes = ByteArray(LIBRARY_HEADER_BYTES)
            val count = input.read(bytes)
            if (count <= 0) "" else String(bytes, 0, count, Charsets.UTF_8)
        }
        val name = TRACK_NAME.find(header)?.groupValues?.getOrNull(1)
            ?.trim()?.decodeXml().orEmpty().ifBlank { displayNameFromFile(file) }
        val attributes = FIRST_ROUTE_POINT.find(header)?.groupValues?.getOrNull(1).orEmpty()
        val latitude = LATITUDE_ATTRIBUTE.find(attributes)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        val longitude = LONGITUDE_ATTRIBUTE.find(attributes)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        return LibraryMetadata(
            name = name,
            pointCount = UNKNOWN_POINT_COUNT,
            startPosition = if (latitude != null && longitude != null) GeoPoint(latitude, longitude) else null,
        )
    }

    private fun displayNameFromFile(file: File): String = file.nameWithoutExtension
        .replace(Regex("-[0-9a-fA-F]{12}$"), "")
        .replace('-', ' ')
        .trim()
        .ifBlank { "Route" }

    private fun String.decodeXml(): String = replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")

    private fun libraryOrigin(file: File): CourseLibraryOrigin? =
        File(libraryDirectory, file.nameWithoutExtension + ".origin")
            .takeIf(File::isFile)
            ?.readText()
            ?.trim()
            ?.let { runCatching { CourseLibraryOrigin.valueOf(it) }.getOrNull() }

    private fun deleteLibraryFiles(entry: CourseLibraryEntry) {
        entry.file.takeIf { it.parentFile == libraryDirectory }?.delete()
        File(libraryDirectory, entry.file.nameWithoutExtension + ".origin").delete()
        File(libraryDirectory, entry.file.nameWithoutExtension + ".info").delete()
    }

    private fun requireUsableCourse(course: Course) {
        val usable = course.points.zipWithNext().any { (start, end) ->
            GeoMath.distanceMeters(start.position, end.position) >= MINIMUM_COURSE_SEGMENT_METERS
        }
        require(usable) { "Course has no usable segment" }
    }

    private fun quarantineInvalidActiveCourse() {
        val rejected = File(courseDirectory, "invalid-active-${System.currentTimeMillis()}.gpx")
        if (!activeFile.renameTo(rejected)) activeFile.delete()
        activeOriginFile.delete()
    }

    companion object {
        const val MAXIMUM_GPX_BYTES = 16 * 1024 * 1024
        const val MINIMUM_COURSE_SEGMENT_METERS = 2.0
        const val UNKNOWN_POINT_COUNT = -1
        private const val LIBRARY_HEADER_BYTES = 64 * 1024
        private val TRACK_NAME = Regex(
            "<(?:[A-Za-z0-9_]+:)?trk\\b[^>]*>[\\s\\S]*?<(?:[A-Za-z0-9_]+:)?name\\b[^>]*>([\\s\\S]*?)</(?:[A-Za-z0-9_]+:)?name>",
            RegexOption.IGNORE_CASE,
        )
        private val FIRST_ROUTE_POINT = Regex(
            "<(?:[A-Za-z0-9_]+:)?(?:trkpt|rtept)\\b([^>]*)>",
            RegexOption.IGNORE_CASE,
        )
        private val LATITUDE_ATTRIBUTE = Regex("\\blat\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
        private val LONGITUDE_ATTRIBUTE = Regex("\\blon\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
        private val HIDDEN_LIBRARY_PREFIXES = listOf(
            "rejoin",
            "offline-route",
            "offline route",
            "tracback",
            "recorded-activity",
            "recorded activity",
        )
    }

    private data class LibraryMetadata(
        val name: String,
        val pointCount: Int,
        val startPosition: GeoPoint?,
    )
}
