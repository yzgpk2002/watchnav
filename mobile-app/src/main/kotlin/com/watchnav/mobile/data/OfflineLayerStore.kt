package com.watchnav.mobile.data

import android.content.Context
import com.watchnav.mapsforge.MapsforgeMapRepository
import com.watchnav.mapsforge.VectorMapLayerKind
import com.watchnav.sync.protocol.Hashing
import com.watchnav.sync.protocol.TransferKind
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream

enum class OfflineLayerKind(val transferKind: TransferKind, val extensions: Set<String>) {
    BASE_MAP(TransferKind.MAPSFORGE, setOf("map")),
    CONTOUR(TransferKind.CONTOUR_MAP, setOf("map")),
    HISTORICAL_IMAGERY(TransferKind.HISTORICAL_RASTER, setOf("mbtiles", "tif", "tiff", "geotiff")),
    ENC_CHART(TransferKind.ENC_CHART, setOf("000", "s57", "gpkg", "mbtiles")),
    BATHYMETRY(TransferKind.BATHYMETRY, setOf("mbtiles", "tif", "tiff", "gpkg")),
    WATER_BOUNDARY(TransferKind.WATER_BOUNDARY, setOf("geojson", "json", "gpkg", "pbf")),
    PUBLIC_POI(TransferKind.POI_INDEX, setOf("geojson", "json", "pbf")),
}

data class OfflineLayer(
    val id: String,
    val name: String,
    val kind: OfflineLayerKind,
    val file: File,
    val bytes: Long,
    val sha256: String,
    val importedAtMillis: Long,
    val enabled: Boolean,
    val sourceDate: String?,
    val attribution: String,
    val license: String,
)

class OfflineLayerStore(context: Context) {
    private val applicationContext = context.applicationContext
    private val root = File(context.filesDir, "offline-layers")
    private val catalog = File(root, "catalog.json")
    private val vectorMaps = MapsforgeMapRepository(applicationContext)

    @Synchronized
    fun import(
        input: InputStream,
        originalName: String,
        kind: OfflineLayerKind,
        displayName: String = originalName.substringBeforeLast('.'),
        sourceDate: String? = null,
        attribution: String = "User supplied",
        license: String = "User is responsible for source license",
    ): OfflineLayer {
        val extension = originalName.substringAfterLast('.', "bin").lowercase()
        require(extension in kind.extensions) { "File .$extension is not supported for ${kind.name}" }
        if (kind == OfflineLayerKind.BASE_MAP || kind == OfflineLayerKind.CONTOUR) {
            root.mkdirs()
            val staging = File(root, "vector-${System.currentTimeMillis()}.map")
            input.use { source -> staging.outputStream().buffered().use(source::copyTo) }
            return try {
                registerVector(
                    staging,
                    kind,
                    displayName,
                    sourceDate,
                    attribution,
                    license,
                    activate = true,
                )
            } finally {
                staging.delete()
            }
        }
        root.mkdirs()
        val safeBase = displayName.safeName().take(70)
        val staging = File(root, "$safeBase-${System.currentTimeMillis()}.$extension.part")
        var written = 0L
        staging.outputStream().buffered().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                written += count
                require(written <= MAX_LAYER_BYTES) { "Offline layer is larger than 2 GB" }
                output.write(buffer, 0, count)
            }
        }
        require(written > 0) { "Offline layer is empty" }
        val hash = staging.inputStream().buffered().use(Hashing::sha256)
        val id = "${kind.name.lowercase()}-${hash.take(20)}"
        val target = File(root, "$id.$extension")
        if (!staging.renameTo(target)) {
            staging.copyTo(target, overwrite = true)
            check(staging.delete())
        }
        val value = OfflineLayer(
            id, displayName.take(120), kind, target, target.length(), hash,
            System.currentTimeMillis(), true, sourceDate?.take(32), attribution.take(512), license.take(512),
        )
        write(list().filterNot { it.id == id } + value)
        return value
    }

    @Synchronized
    fun register(
        file: File,
        kind: OfflineLayerKind,
        displayName: String,
        attribution: String,
        license: String,
        activate: Boolean = true,
    ): OfflineLayer = if (kind == OfflineLayerKind.BASE_MAP || kind == OfflineLayerKind.CONTOUR) {
        registerVector(file, kind, displayName, null, attribution, license, activate)
    } else {
        file.inputStream().buffered().use { import(it, file.name, kind, displayName, attribution = attribution, license = license) }
    }

    @Synchronized
    fun list(): List<OfflineLayer> {
        val stored = readCatalog()
        val vectorMetadata = stored.filter { it.kind == OfflineLayerKind.BASE_MAP || it.kind == OfflineLayerKind.CONTOUR }
            .associateBy { it.kind to it.sha256 }
        val selectedBase = vectorMaps.selected(VectorMapLayerKind.BASE)?.id
        val selectedContour = vectorMaps.selected(VectorMapLayerKind.CONTOUR)?.id
        val contourEnabled = vectorMaps.isContourEnabled()
        val vectors = vectorMaps.layers().map { layer ->
            val kind = if (layer.kind == VectorMapLayerKind.BASE) OfflineLayerKind.BASE_MAP else OfflineLayerKind.CONTOUR
            val metadata = vectorMetadata[kind to layer.sha256]
            OfflineLayer(
                id = layer.id,
                name = metadata?.name ?: layer.name,
                kind = kind,
                file = layer.file,
                bytes = layer.bytes,
                sha256 = layer.sha256,
                importedAtMillis = layer.importedAtMillis,
                enabled = if (kind == OfflineLayerKind.BASE_MAP) layer.id == selectedBase
                    else contourEnabled && layer.id == selectedContour,
                sourceDate = metadata?.sourceDate,
                attribution = metadata?.attribution.orEmpty(),
                license = metadata?.license.orEmpty(),
            )
        }
        return (stored.filterNot { it.kind == OfflineLayerKind.BASE_MAP || it.kind == OfflineLayerKind.CONTOUR } + vectors)
            .distinctBy { it.kind to it.sha256 }
            .sortedByDescending(OfflineLayer::importedAtMillis)
    }

    private fun readCatalog(): List<OfflineLayer> = runCatching {
        if (!catalog.isFile) return@runCatching emptyList()
        val array = JSONArray(catalog.readText())
        (0 until array.length()).mapNotNull { index ->
            val json = array.getJSONObject(index)
            val file = json.optString("relativePath").takeIf(String::isNotBlank)
                ?.let { File(applicationContext.filesDir, it) }
                ?: File(root, json.getString("fileName"))
            if (!file.isFile) null else OfflineLayer(
                id = json.getString("id"),
                name = json.getString("name"),
                kind = OfflineLayerKind.valueOf(json.getString("kind")),
                file = file,
                bytes = file.length(),
                sha256 = json.getString("sha256"),
                importedAtMillis = json.getLong("importedAtMillis"),
                enabled = json.optBoolean("enabled", true),
                sourceDate = json.optString("sourceDate").takeIf(String::isNotBlank),
                attribution = json.optString("attribution"),
                license = json.optString("license"),
            )
        }
    }.getOrDefault(emptyList())

    @Synchronized
    fun setEnabled(id: String, enabled: Boolean, kindHint: OfflineLayerKind? = null, bytesHint: Long? = null) {
        val layer = list().firstOrNull { it.id == id }
            ?: list().firstOrNull { it.kind == kindHint && it.bytes == bytesHint }
            ?: return
        when (layer.kind) {
            OfflineLayerKind.BASE_MAP -> vectorMaps.select(
                VectorMapLayerKind.BASE,
                layer.id.takeIf { enabled },
            )
            OfflineLayerKind.CONTOUR -> {
                vectorMaps.select(VectorMapLayerKind.CONTOUR, layer.id)
                vectorMaps.setContourEnabled(enabled)
            }
            else -> write(readCatalog().map { if (it.id == id) it.copy(enabled = enabled) else it })
        }
    }

    @Synchronized
    private fun registerVector(
        file: File,
        kind: OfflineLayerKind,
        displayName: String,
        sourceDate: String?,
        attribution: String,
        license: String,
        activate: Boolean,
    ): OfflineLayer {
        val vectorKind = if (kind == OfflineLayerKind.BASE_MAP) VectorMapLayerKind.BASE else VectorMapLayerKind.CONTOUR
        val stored = vectorMaps.registerVerified(file, vectorKind, displayName, activate)
        val value = OfflineLayer(
            stored.id, stored.name, kind, stored.file, stored.bytes, stored.sha256,
            stored.importedAtMillis, activate, sourceDate, attribution.take(512), license.take(512),
        )
        write(readCatalog().filterNot { it.kind == kind && it.sha256 == stored.sha256 } + value)
        return value
    }

    private fun write(values: List<OfflineLayer>) {
        root.mkdirs()
        val array = JSONArray(values.map { value -> JSONObject().apply {
            put("id", value.id); put("name", value.name); put("kind", value.kind.name)
            put("fileName", value.file.name); put("sha256", value.sha256)
            put("relativePath", value.file.relativeToOrNull(applicationContext.filesDir)?.path.orEmpty())
            put("importedAtMillis", value.importedAtMillis); put("enabled", value.enabled)
            put("sourceDate", value.sourceDate.orEmpty()); put("attribution", value.attribution)
            put("license", value.license)
        } })
        val temporary = File(root, "catalog.json.tmp").apply { writeText(array.toString()) }
        if (!temporary.renameTo(catalog)) {
            temporary.copyTo(catalog, overwrite = true)
            check(temporary.delete())
        }
    }

    private fun String.safeName() = replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-').ifBlank { "layer" }

    private companion object { const val MAX_LAYER_BYTES = 2_000_000_000L }
}
