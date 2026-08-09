package com.watchnav.mapsforge

import android.content.Context
import java.io.File
import java.io.InputStream

class MapsforgeMapRepository(context: Context) {
    private val mapDirectory = File(context.filesDir, "maps")
    private val activeMap = File(mapDirectory, "active.map")
    private val rollbackMap = File(mapDirectory, "rollback.map")

    fun import(input: InputStream): File {
        mapDirectory.mkdirs()
        check(mapDirectory.isDirectory) { "Unable to create map storage" }
        val temporary = File(mapDirectory, "active.map.tmp")
        temporary.outputStream().buffered().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= MAXIMUM_MAP_BYTES) { "Map is larger than 2 GB" }
                output.write(buffer, 0, count)
            }
        }
        require(temporary.length() > 0) { "Map file is empty" }
        MapsforgeMapSource(temporary).use { source ->
            require(source.dataStore.boundingBox() != null) { "Map has no geographic bounds" }
        }
        if (!temporary.renameTo(activeMap)) {
            temporary.copyTo(activeMap, overwrite = true)
            check(temporary.delete()) { "Unable to finish saving map" }
        }
        return activeMap
    }

    fun activeMap(): File? = activeMap.takeIf(File::isFile)

    fun installVerified(source: File): File {
        require(source.isFile && source.length() > 0) { "Map artifact is empty" }
        MapsforgeMapSource(source).use { map ->
            require(map.dataStore.boundingBox() != null) { "Map has no geographic bounds" }
        }
        mapDirectory.mkdirs()
        if (activeMap.isFile) activeMap.copyTo(rollbackMap, overwrite = true)
        source.copyTo(activeMap, overwrite = true)
        return activeMap
    }

    fun rollback(): File? {
        if (!rollbackMap.isFile) return null
        rollbackMap.copyTo(activeMap, overwrite = true)
        return activeMap
    }

    companion object {
        private const val MAXIMUM_MAP_BYTES = 2_000_000_000L
    }
}
