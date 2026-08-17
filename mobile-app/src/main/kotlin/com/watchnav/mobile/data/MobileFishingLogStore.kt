package com.watchnav.mobile.data

import android.content.Context
import com.watchnav.core.GeoPoint
import com.watchnav.sync.protocol.SyncRecord
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream

data class MobileFishingEntry(
    val id: String,
    val transcript: String,
    val species: String?,
    val lengthCm: Int?,
    val point: GeoPoint?,
    val pressureHpa: Float?,
    val createdAtMillis: Long,
    val audioFileName: String?,
    val photos: List<String>,
    val transcriptionAttempts: Int = 0,
    val lastTranscriptionAttemptMillis: Long = 0L,
    val transcriptionError: String? = null,
    val transcriptionEngine: String? = null,
)

class MobileFishingLogStore(context: Context) {
    private val root = File(context.filesDir, "fishing")
    private val catalog = File(root, "mobile-log.json")
    private val audioDirectory = File(root, "audio")
    private val photoDirectory = File(root, "photos")

    @Synchronized
    fun list(): List<MobileFishingEntry> = runCatching {
        if (!catalog.isFile) return@runCatching emptyList()
        val array = JSONArray(catalog.readText())
        (0 until array.length()).map { index -> decode(array.getJSONObject(index)) }
            .sortedByDescending(MobileFishingEntry::createdAtMillis)
    }.getOrDefault(emptyList())

    @Synchronized
    fun find(id: String): MobileFishingEntry? = list().firstOrNull { it.id == id }

    @Synchronized
    fun mergeWatchRecord(record: SyncRecord): MobileFishingEntry {
        val remote = decode(JSONObject(record.payload.toString(Charsets.UTF_8)))
        val local = find(remote.id)
        val merged = remote.copy(
            transcript = local?.transcript?.takeIf(String::isNotBlank) ?: remote.transcript,
            species = local?.species ?: remote.species,
            lengthCm = local?.lengthCm ?: remote.lengthCm,
            audioFileName = local?.audioFileName ?: remote.audioFileName,
            photos = local?.photos.orEmpty(),
            transcriptionAttempts = local?.transcriptionAttempts ?: remote.transcriptionAttempts,
            lastTranscriptionAttemptMillis = local?.lastTranscriptionAttemptMillis
                ?: remote.lastTranscriptionAttemptMillis,
            transcriptionError = local?.transcriptionError ?: remote.transcriptionError,
            transcriptionEngine = local?.transcriptionEngine ?: remote.transcriptionEngine,
        )
        upsert(merged)
        return merged
    }

    @Synchronized
    fun updateText(id: String, transcript: String, species: String?, lengthCm: Int?): MobileFishingEntry? {
        val current = find(id) ?: return null
        return current.copy(
            transcript = transcript.take(1_000),
            species = species?.trim()?.take(80)?.takeIf(String::isNotBlank),
            lengthCm = lengthCm?.takeIf { it in 0..500 },
            transcriptionError = null,
            transcriptionEngine = current.transcriptionEngine ?: MANUAL_TRANSCRIPTION_ENGINE,
        ).also(::upsert)
    }

    @Synchronized
    fun saveAutomaticTranscript(id: String, transcript: String, engine: String): MobileFishingEntry? {
        val current = find(id) ?: return null
        return current.copy(
            transcript = transcript.take(1_000),
            transcriptionError = null,
            transcriptionEngine = engine.take(80),
        ).also(::upsert)
    }

    @Synchronized
    fun markTranscriptionStarted(id: String, nowMillis: Long = System.currentTimeMillis()): MobileFishingEntry? {
        val current = find(id) ?: return null
        return current.copy(
            transcriptionAttempts = current.transcriptionAttempts + 1,
            lastTranscriptionAttemptMillis = nowMillis,
            transcriptionError = null,
        ).also(::upsert)
    }

    @Synchronized
    fun markTranscriptionFailed(id: String, message: String): MobileFishingEntry? {
        val current = find(id) ?: return null
        return current.copy(transcriptionError = message.take(240)).also(::upsert)
    }

    /**
     * Returns audio entries eligible for automatic phone-side transcription. Failed recognition
     * backs off so opening a panel cannot repeatedly wake the recognition service or network.
     */
    @Synchronized
    fun pendingTranscriptions(nowMillis: Long = System.currentTimeMillis()): List<MobileFishingEntry> =
        list().filter { entry ->
            entry.transcript.isBlank() && audioFile(entry) != null && entry.transcriptionAttempts < MAX_AUTO_ATTEMPTS &&
                nowMillis - entry.lastTranscriptionAttemptMillis >= retryDelayMillis(entry.transcriptionAttempts)
        }.sortedBy(MobileFishingEntry::createdAtMillis)

    @Synchronized
    fun storeAudio(id: String, source: File): File {
        require(source.isFile && source.length() > 44)
        audioDirectory.mkdirs()
        val target = File(audioDirectory, "${id.safe()}.wav")
        source.copyTo(target, overwrite = true)
        val current = find(id)
        upsert(
            current?.copy(audioFileName = target.name) ?: MobileFishingEntry(
                id = id,
                transcript = "",
                species = null,
                lengthCm = null,
                point = null,
                pressureHpa = null,
                createdAtMillis = System.currentTimeMillis(),
                audioFileName = target.name,
                photos = emptyList(),
                transcriptionAttempts = 0,
                lastTranscriptionAttemptMillis = 0L,
                transcriptionError = null,
                transcriptionEngine = null,
            ),
        )
        return target
    }

    fun audioFile(entry: MobileFishingEntry): File? = entry.audioFileName
        ?.let { File(audioDirectory, File(it).name) }
        ?.takeIf(File::isFile)

    @Synchronized
    fun attachPhoto(id: String, input: InputStream, extension: String): MobileFishingEntry? {
        val current = find(id) ?: return null
        photoDirectory.mkdirs()
        val safeExtension = extension.lowercase().takeIf { it in setOf("jpg", "jpeg", "png", "webp") } ?: "jpg"
        val target = File(photoDirectory, "${id.safe()}-${System.currentTimeMillis()}.$safeExtension")
        input.use { source -> target.outputStream().buffered().use(source::copyTo) }
        return current.copy(photos = (current.photos + target.name).distinct()).also(::upsert)
    }

    fun photoFiles(entry: MobileFishingEntry): List<File> = entry.photos
        .map { File(photoDirectory, File(it).name) }.filter(File::isFile)

    fun payload(entry: MobileFishingEntry): ByteArray = encode(entry).toString().toByteArray()

    @Synchronized
    fun delete(id: String) {
        find(id)?.let { entry ->
            audioFile(entry)?.delete()
            photoFiles(entry).forEach(File::delete)
        }
        write(list().filterNot { it.id == id })
    }

    @Synchronized
    private fun upsert(entry: MobileFishingEntry) = write(list().filterNot { it.id == entry.id } + entry)

    private fun decode(json: JSONObject) = MobileFishingEntry(
        id = json.getString("id"),
        transcript = json.optString("transcript"),
        species = json.optString("species").takeIf { it.isNotBlank() && it != "null" },
        lengthCm = json.optInt("lengthCm", -1).takeIf { it >= 0 },
        point = if (!json.isNull("latitude") && !json.isNull("longitude")) GeoPoint(
            json.getDouble("latitude"), json.getDouble("longitude"),
            json.optDouble("elevation").takeIf { !it.isNaN() },
        ) else null,
        pressureHpa = json.optDouble("pressureHpa").takeIf { !it.isNaN() }?.toFloat(),
        createdAtMillis = json.optLong("createdAtMillis", System.currentTimeMillis()),
        audioFileName = json.optString("audioFileName").takeIf { it.isNotBlank() && it != "null" },
        photos = json.optJSONArray("photos")?.let { array ->
            (0 until array.length()).map { array.getString(it) }
        }.orEmpty(),
        transcriptionAttempts = json.optInt("transcriptionAttempts", 0).coerceAtLeast(0),
        lastTranscriptionAttemptMillis = json.optLong("lastTranscriptionAttemptMillis", 0L),
        transcriptionError = json.optString("transcriptionError").takeIf { it.isNotBlank() && it != "null" },
        transcriptionEngine = json.optString("transcriptionEngine").takeIf { it.isNotBlank() && it != "null" },
    )

    private fun encode(entry: MobileFishingEntry) = JSONObject().apply {
        put("id", entry.id); put("transcript", entry.transcript)
        put("species", entry.species ?: JSONObject.NULL); put("lengthCm", entry.lengthCm ?: JSONObject.NULL)
        put("latitude", entry.point?.latitude ?: JSONObject.NULL); put("longitude", entry.point?.longitude ?: JSONObject.NULL)
        put("elevation", entry.point?.elevationMeters ?: JSONObject.NULL); put("pressureHpa", entry.pressureHpa ?: JSONObject.NULL)
        put("createdAtMillis", entry.createdAtMillis); put("audioFileName", entry.audioFileName ?: JSONObject.NULL)
        put("photos", JSONArray(entry.photos))
        put("transcriptionAttempts", entry.transcriptionAttempts)
        put("lastTranscriptionAttemptMillis", entry.lastTranscriptionAttemptMillis)
        put("transcriptionError", entry.transcriptionError ?: JSONObject.NULL)
        put("transcriptionEngine", entry.transcriptionEngine ?: JSONObject.NULL)
    }

    private fun write(values: List<MobileFishingEntry>) {
        root.mkdirs()
        val temporary = File(root, catalog.name + ".tmp").apply {
            writeText(JSONArray(values.sortedBy(MobileFishingEntry::createdAtMillis).map(::encode)).toString())
        }
        if (!temporary.renameTo(catalog)) {
            temporary.copyTo(catalog, overwrite = true)
            check(temporary.delete())
        }
    }

    private fun String.safe() = replace(Regex("[^A-Za-z0-9._-]+"), "-").take(120)

    companion object {
        const val MAX_AUTO_ATTEMPTS = 4
        const val MANUAL_TRANSCRIPTION_ENGINE = "manual"

        fun retryDelayMillis(attempts: Int): Long = when (attempts) {
            0 -> 0L
            1 -> 15 * 60_000L
            2 -> 60 * 60_000L
            else -> 6 * 60 * 60_000L
        }
    }
}
