package com.watchnav.mapsforge

import android.content.Context
import android.os.Looper
import com.watchnav.core.Course
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

enum class VectorMapLayerKind { BASE, CONTOUR }

data class VectorMapLayer(
    val id: String,
    val name: String,
    val kind: VectorMapLayerKind,
    val file: File,
    val sha256: String,
    val bytes: Long,
    val importedAtMillis: Long,
)

data class OfflineMapStack(
    val base: VectorMapLayer?,
    val contour: VectorMapLayer?,
) {
    val files: List<File> = listOfNotNull(base?.file, contour?.file)
        .distinctBy { it.absolutePath }
    val cacheIdentity: String = buildString {
        append("theme-").append(THEME_VERSION)
        append("-base-").append(base?.sha256?.take(16) ?: "none")
        append("-contour-").append(contour?.sha256?.take(16) ?: "none")
    }
    val isEmpty: Boolean get() = files.isEmpty()

    private companion object { const val THEME_VERSION = 2 }
}

/**
 * Content-addressed Mapsforge layer repository shared by phone and watch.
 *
 * Legacy active.map/rollback.map files are migrated once. New transfers are registered as
 * immutable layers and do not alter the selected map unless the caller explicitly requests it.
 */
class MapsforgeMapRepository(context: Context) {
    private val applicationContext = context.applicationContext
    private val mapDirectory = File(applicationContext.filesDir, "maps")
    private val layerDirectory = File(mapDirectory, "layers")
    private val catalog = File(mapDirectory, "layer-catalog.json")
    private val legacyActiveMap = File(mapDirectory, "active.map")
    private val legacyRollbackMap = File(mapDirectory, "rollback.map")
    private val preferences = applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    @Volatile private var migrationChecked = false

    fun import(input: InputStream): File {
        mapDirectory.mkdirs()
        val temporary = File(mapDirectory, "import-${System.currentTimeMillis()}.part.map")
        input.buffered().use { source -> temporary.outputStream().buffered().use(source::copyTo) }
        return try {
            registerVerified(temporary, VectorMapLayerKind.BASE, "Imported offline map", activate = true).file
        } finally {
            temporary.delete()
        }
    }

    fun installVerified(source: File): File =
        registerVerified(source, VectorMapLayerKind.BASE, source.nameWithoutExtension, activate = true).file

    fun installVerified(source: File, kind: VectorMapLayerKind, displayName: String = source.nameWithoutExtension): File =
        registerVerified(source, kind, displayName, activate = true).file

    @Synchronized
    fun registerVerified(
        source: File,
        kind: VectorMapLayerKind,
        displayName: String,
        activate: Boolean = false,
    ): VectorMapLayer {
        ensureLegacyMigrated()
        readCatalog().firstOrNull {
            it.kind == kind && runCatching { it.file.canonicalFile == source.canonicalFile }.getOrDefault(false)
        }?.let { existing ->
            if (activate) {
                preferences.edit().putString(selectionKey(kind), existing.id).also { editor ->
                    if (kind == VectorMapLayerKind.CONTOUR) editor.putBoolean(KEY_CONTOUR_ENABLED, true)
                }.apply()
            }
            return existing
        }
        return registerVerifiedInternal(source, kind, displayName, activate)
    }

    fun layers(): List<VectorMapLayer> {
        ensureLegacyMigrated()
        return readCatalog().ifEmpty(::legacyLayers)
    }

    @Synchronized
    fun select(kind: VectorMapLayerKind, id: String?) {
        ensureLegacyMigrated()
        if (id != null) require(readCatalog().ifEmpty(::legacyLayers).any { it.id == id && it.kind == kind }) {
            "Unknown $kind layer: $id"
        }
        preferences.edit().putString(selectionKey(kind), id).apply()
    }

    fun setContourEnabled(enabled: Boolean) {
        ensureLegacyMigrated()
        preferences.edit().putBoolean(KEY_CONTOUR_ENABLED, enabled).apply()
    }

    fun isContourEnabled(): Boolean {
        ensureLegacyMigrated()
        return preferences.getBoolean(KEY_CONTOUR_ENABLED, selected(VectorMapLayerKind.CONTOUR) != null)
    }

    fun selected(kind: VectorMapLayerKind): VectorMapLayer? {
        ensureLegacyMigrated()
        val catalogValues = readCatalog()
        val values = catalogValues.ifEmpty(::legacyLayers).filter { it.kind == kind }
        val selectedId = preferences.getString(selectionKey(kind), null)
        return values.firstOrNull { it.id == selectedId }
            // A catalog can survive an interrupted transfer while its selected id is absent or
            // points at a file that has since been repaired. Never turn that recoverable state
            // into an empty home map: select the newest usable layer of the requested kind.
            ?: values.maxByOrNull(VectorMapLayer::importedAtMillis)
    }

    fun activeStack(): OfflineMapStack {
        val base = selected(VectorMapLayerKind.BASE)
        val contour = selected(VectorMapLayerKind.CONTOUR).takeIf { isContourEnabled() }
        return OfflineMapStack(base, contour)
    }

    /** Compatibility API for callers that only understand one Mapsforge file. */
    fun activeMap(): File? = activeStack().base?.file ?: activeStack().contour?.file

    /** Compatibility API: returns the selected contour or another non-active vector layer. */
    fun rollbackMap(): File? {
        val stack = activeStack()
        return stack.contour?.file ?: layers().firstOrNull { it.file != stack.base?.file }?.file
    }

    fun activeMapCovers(course: Course, marginDegrees: Double = 0.01): Boolean {
        if (course.points.isEmpty()) return false
        val south = course.points.minOf { it.position.latitude } - marginDegrees
        val north = course.points.maxOf { it.position.latitude } + marginDegrees
        val west = course.points.minOf { it.position.longitude } - marginDegrees
        val east = course.points.maxOf { it.position.longitude } + marginDegrees
        return activeStack().files.any { file ->
            runCatching {
                MapsforgeMapSource(file).use { source ->
                    source.dataStore.boundingBox()?.let { bounds ->
                        bounds.contains(south, west) && bounds.contains(north, east)
                    } ?: false
                }
            }.getOrDefault(false)
        }
    }

    fun rollback(): File? {
        val current = activeStack().base
        val replacement = layers().filter { it.kind == VectorMapLayerKind.BASE && it.id != current?.id }
            .maxByOrNull(VectorMapLayer::importedAtMillis) ?: return null
        select(VectorMapLayerKind.BASE, replacement.id)
        return replacement.file
    }

    @Synchronized
    private fun ensureLegacyMigrated() {
        if (migrationChecked) return
        if (readCatalog().isNotEmpty()) return
        val candidates = listOf(legacyActiveMap, legacyRollbackMap).filter(File::isFile)
        if (candidates.isEmpty()) return
        // Never hash/copy a several-hundred-megabyte legacy map on the UI thread. The old files
        // remain directly readable until the sync/download worker performs the one-time migration.
        if (Looper.myLooper() == Looper.getMainLooper()) return
        migrationChecked = true

        val configured = applicationContext.getSharedPreferences("map_installation", Context.MODE_PRIVATE)
            .getString("active_map_kind", null)
        val activeKind = if (configured == "CONTOUR_MAP" ||
            (legacyActiveMap.length() >= LEGACY_CONTOUR_MINIMUM_BYTES &&
                legacyActiveMap.length() > legacyRollbackMap.length().coerceAtLeast(1L) * 4)
        ) VectorMapLayerKind.CONTOUR else VectorMapLayerKind.BASE

        val migrated = ArrayList<VectorMapLayer>()
        runCatching {
            if (legacyActiveMap.isFile) {
                migrated += registerVerifiedInternal(legacyActiveMap, activeKind, "Migrated active map", activate = true)
            }
            if (legacyRollbackMap.isFile) {
                val rollbackKind = if (activeKind == VectorMapLayerKind.BASE) VectorMapLayerKind.CONTOUR else VectorMapLayerKind.BASE
                migrated += registerVerifiedInternal(legacyRollbackMap, rollbackKind, "Migrated alternate map", activate = false)
            }
            val base = migrated.firstOrNull { it.kind == VectorMapLayerKind.BASE }
            val contour = migrated.firstOrNull { it.kind == VectorMapLayerKind.CONTOUR }
            if (base != null) preferences.edit().putString(KEY_SELECTED_BASE, base.id).apply()
            if (contour != null) preferences.edit()
                .putString(KEY_SELECTED_CONTOUR, contour.id)
                .putBoolean(KEY_CONTOUR_ENABLED, activeKind == VectorMapLayerKind.CONTOUR || base != null)
                .apply()
            legacyActiveMap.delete()
            legacyRollbackMap.delete()
        }.onFailure {
            migrationChecked = false
        }
    }

    private fun legacyLayers(): List<VectorMapLayer> {
        val activeKind = legacyActiveKind()
        return buildList {
            if (legacyActiveMap.isFile) add(legacyLayer(legacyActiveMap, activeKind, "Legacy active map"))
            if (legacyRollbackMap.isFile) add(legacyLayer(
                legacyRollbackMap,
                if (activeKind == VectorMapLayerKind.BASE) VectorMapLayerKind.CONTOUR else VectorMapLayerKind.BASE,
                "Legacy alternate map",
            ))
        }
    }

    private fun legacyActiveKind(): VectorMapLayerKind {
        val configured = applicationContext.getSharedPreferences("map_installation", Context.MODE_PRIVATE)
            .getString("active_map_kind", null)
        return if (configured == "CONTOUR_MAP" ||
            (legacyActiveMap.length() >= LEGACY_CONTOUR_MINIMUM_BYTES &&
                legacyActiveMap.length() > legacyRollbackMap.length().coerceAtLeast(1L) * 4)
        ) VectorMapLayerKind.CONTOUR else VectorMapLayerKind.BASE
    }

    private fun legacyLayer(file: File, kind: VectorMapLayerKind, name: String) = VectorMapLayer(
        id = "legacy-${kind.name.lowercase()}-${file.length()}-${file.lastModified()}",
        name = name,
        kind = kind,
        file = file,
        sha256 = "legacy-${file.length()}-${file.lastModified()}",
        bytes = file.length(),
        importedAtMillis = file.lastModified(),
    )

    private fun registerVerifiedInternal(
        source: File,
        kind: VectorMapLayerKind,
        displayName: String,
        activate: Boolean,
    ): VectorMapLayer {
        require(source.isFile && source.length() in 1..MAXIMUM_MAP_BYTES) { "Map artifact is empty or too large" }
        MapsforgeMapSource(source).use { map ->
            require(map.dataStore.boundingBox() != null) { "Map has no geographic bounds" }
        }
        layerDirectory.mkdirs()
        val hash = sha256(source)
        val id = "${kind.name.lowercase()}-${hash.take(24)}"
        val target = File(layerDirectory, "$id.map")
        if (!target.isFile || target.length() != source.length()) {
            val temporary = File(layerDirectory, "$id.map.part")
            source.copyTo(temporary, overwrite = true)
            require(sha256(temporary) == hash) { "Map copy verification failed" }
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                check(temporary.delete()) { "Unable to finalize map layer" }
            }
        }
        val existing = readCatalog().firstOrNull { it.id == id }
        val layer = VectorMapLayer(
            id = id,
            name = displayName.trim().take(120).ifBlank { source.nameWithoutExtension },
            kind = kind,
            file = target,
            sha256 = hash,
            bytes = target.length(),
            importedAtMillis = existing?.importedAtMillis ?: System.currentTimeMillis(),
        )
        writeCatalog(readCatalog().filterNot { it.id == id } + layer)
        if (activate) {
            preferences.edit().putString(selectionKey(kind), id).also { editor ->
                if (kind == VectorMapLayerKind.CONTOUR) editor.putBoolean(KEY_CONTOUR_ENABLED, true)
            }.apply()
        }
        return layer
    }

    private fun readCatalog(): List<VectorMapLayer> = runCatching {
        if (!catalog.isFile) return@runCatching emptyList()
        val values = JSONArray(catalog.readText())
        (0 until values.length()).mapNotNull { index ->
            val json = values.getJSONObject(index)
            val file = File(layerDirectory, json.getString("fileName"))
            if (!file.isFile) null else VectorMapLayer(
                id = json.getString("id"),
                name = json.getString("name"),
                kind = VectorMapLayerKind.valueOf(json.getString("kind")),
                file = file,
                sha256 = json.getString("sha256"),
                bytes = file.length(),
                importedAtMillis = json.getLong("importedAtMillis"),
            )
        }
    }.getOrDefault(emptyList())

    private fun writeCatalog(values: List<VectorMapLayer>) {
        mapDirectory.mkdirs()
        val json = JSONArray(values.sortedBy(VectorMapLayer::importedAtMillis).map { layer -> JSONObject().apply {
            put("id", layer.id)
            put("name", layer.name)
            put("kind", layer.kind.name)
            put("fileName", layer.file.name)
            put("sha256", layer.sha256)
            put("bytes", layer.bytes)
            put("importedAtMillis", layer.importedAtMillis)
        } })
        val temporary = File(mapDirectory, catalog.name + ".tmp").apply { writeText(json.toString()) }
        if (!temporary.renameTo(catalog)) {
            temporary.copyTo(catalog, overwrite = true)
            check(temporary.delete()) { "Unable to save map catalog" }
        }
    }

    private fun selectionKey(kind: VectorMapLayerKind) =
        if (kind == VectorMapLayerKind.BASE) KEY_SELECTED_BASE else KEY_SELECTED_CONTOUR

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(HASH_BUFFER_BYTES).use { input ->
            val buffer = ByteArray(HASH_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val MAXIMUM_MAP_BYTES = 2_000_000_000L
        const val HASH_BUFFER_BYTES = 256 * 1024
        const val LEGACY_CONTOUR_MINIMUM_BYTES = 100L * 1024 * 1024
        const val PREFERENCES = "mapsforge_layer_selection_v2"
        const val KEY_SELECTED_BASE = "selected_base"
        const val KEY_SELECTED_CONTOUR = "selected_contour"
        const val KEY_CONTOUR_ENABLED = "contour_enabled"
    }
}
