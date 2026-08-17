package com.watchnav.mobile.data

import android.content.Context
import java.io.File
import java.io.InputStream

class WaypointAttachmentStore(context: Context) {
    private val root = File(context.filesDir, "waypoint-photos")

    fun attach(waypointId: String, input: InputStream, extension: String): File {
        val directory = File(root, waypointId.safe()).apply { mkdirs() }
        val suffix = extension.lowercase().takeIf { it in setOf("jpg", "jpeg", "png", "webp") } ?: "jpg"
        return File(directory, "${System.currentTimeMillis()}.$suffix").also { target ->
            input.use { source -> target.outputStream().buffered().use(source::copyTo) }
        }
    }

    fun photos(waypointId: String): List<File> = File(root, waypointId.safe())
        .listFiles()?.filter(File::isFile)?.sortedByDescending(File::lastModified).orEmpty()

    fun delete(waypointId: String) {
        File(root, waypointId.safe()).deleteRecursively()
    }

    private fun String.safe() = replace(Regex("[^A-Za-z0-9._-]+"), "-").take(120)
}
