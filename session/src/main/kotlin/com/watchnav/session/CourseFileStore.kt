package com.watchnav.session

import android.content.Context
import com.watchnav.core.Course
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
)

class CourseFileStore(context: Context) {
    private val courseDirectory = File(context.filesDir, "courses")
    private val activeFile = File(courseDirectory, "active.gpx")
    private val libraryDirectory = File(courseDirectory, "library")

    fun import(input: InputStream, fallbackName: String = "Imported course"): StoredCourse =
        save(readBounded(input), fallbackName)

    fun importKml(input: InputStream, fallbackName: String = "Imported KML course"): StoredCourse {
        val bytes = readBounded(input)
        require(bytes.isNotEmpty()) { "KML file is empty" }
        val course = bytes.inputStream().use { KmlParser().parse(it, fallbackName) }
        return saveCourse(course)
    }

    fun save(bytes: ByteArray, fallbackName: String = "Imported course"): StoredCourse {
        require(bytes.isNotEmpty()) { "GPX file is empty" }
        require(bytes.size <= MAXIMUM_GPX_BYTES) { "GPX is larger than 16 MB" }
        val course = bytes.inputStream().use { GpxParser().parse(it, fallbackName).course }
        courseDirectory.mkdirs()
        check(courseDirectory.isDirectory) { "Unable to create course storage" }
        val temporary = File(courseDirectory, "active.gpx.tmp")
        temporary.outputStream().use { it.write(bytes) }
        if (!temporary.renameTo(activeFile)) {
            temporary.copyTo(activeFile, overwrite = true)
            check(temporary.delete()) { "Unable to finish saving GPX" }
        }
        saveToLibrary(bytes, course)
        return StoredCourse(course, activeFile)
    }

    fun saveCourse(course: Course): StoredCourse {
        val output = ByteArrayOutputStream()
        GpxWriter.write(course, output)
        return save(output.toByteArray(), course.name)
    }

    fun load(): StoredCourse? {
        if (!activeFile.isFile) return null
        val course = activeFile.inputStream().buffered().use { GpxParser().parse(it).course }
        return StoredCourse(course, activeFile)
    }

    fun activeFile(): File? = activeFile.takeIf(File::isFile)

    fun listLibrary(): List<CourseLibraryEntry> {
        if (!libraryDirectory.isDirectory) return emptyList()
        return libraryDirectory.listFiles { file -> file.extension.equals("gpx", ignoreCase = true) }
            ?.mapNotNull { file ->
                runCatching {
                    val course = file.inputStream().buffered().use { GpxParser().parse(it).course }
                    CourseLibraryEntry(file.nameWithoutExtension, course.name, course.points.size, file)
                }.getOrNull()
            }
            ?.sortedBy(CourseLibraryEntry::name)
            ?: emptyList()
    }

    fun activate(entry: CourseLibraryEntry): StoredCourse =
        entry.file.inputStream().buffered().use { import(it, entry.name) }

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

    private fun saveToLibrary(bytes: ByteArray, course: Course) {
        libraryDirectory.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .take(6).joinToString("") { "%02x".format(it) }
        val safeName = course.name.replace(Regex("[^A-Za-z0-9._-]+"), "-")
            .trim('-').take(40).ifBlank { "course" }
        val libraryFile = File(libraryDirectory, "$safeName-$digest.gpx")
        if (!libraryFile.exists()) libraryFile.outputStream().use { it.write(bytes) }
    }

    companion object {
        const val MAXIMUM_GPX_BYTES = 16 * 1024 * 1024
    }
}
