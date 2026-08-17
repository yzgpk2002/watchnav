package com.watchnav.mapsforge

import org.mapsforge.map.datastore.MapDataStore
import org.mapsforge.map.datastore.MultiMapDataStore
import org.mapsforge.map.reader.MapFile
import java.io.Closeable
import java.io.File

class MapsforgeMapSource(files: List<File>) : Closeable {
    val dataStore: MapDataStore
    val boundingBox get() = dataStore.boundingBox()

    init {
        val distinct = files.distinctBy(File::getAbsolutePath)
        require(distinct.isNotEmpty()) { "At least one Mapsforge map is required" }
        distinct.forEach { file ->
            require(file.isFile) { "Map file does not exist: ${file.absolutePath}" }
            require(file.extension.equals("map", ignoreCase = true)) { "Expected a Mapsforge .map file" }
        }
        dataStore = if (distinct.size == 1) {
            MapFile(distinct.single())
        } else {
            MultiMapDataStore(MultiMapDataStore.DataPolicy.DEDUPLICATE).apply {
                distinct.forEachIndexed { index, file ->
                    addMapDataStore(MapFile(file), index == 0, index == 0)
                }
            }
        }
    }

    constructor(file: File) : this(listOf(file))

    override fun close() {
        dataStore.close()
    }
}

object MapsforgeEngineInfo {
    const val VERSION = "0.25.0"
}
