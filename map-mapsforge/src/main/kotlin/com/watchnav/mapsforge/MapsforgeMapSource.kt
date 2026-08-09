package com.watchnav.mapsforge

import org.mapsforge.map.datastore.MapDataStore
import org.mapsforge.map.reader.MapFile
import java.io.Closeable
import java.io.File

class MapsforgeMapSource(file: File) : Closeable {
    val dataStore: MapDataStore

    init {
        require(file.isFile) { "Map file does not exist: ${file.absolutePath}" }
        require(file.extension.equals("map", ignoreCase = true)) { "Expected a Mapsforge .map file" }
        dataStore = MapFile(file)
    }

    override fun close() {
        dataStore.close()
    }
}

object MapsforgeEngineInfo {
    const val VERSION = "0.25.0"
}
