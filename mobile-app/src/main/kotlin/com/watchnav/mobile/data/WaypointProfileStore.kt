package com.watchnav.mobile.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class WaypointAudioTranscript(
    val audioId: String,
    val text: String,
    val createdAtMillis: Long,
)

data class WaypointProfile(
    val waypointId: String,
    val notes: String,
    val audioTranscripts: List<WaypointAudioTranscript> = emptyList(),
) {
    val displayedNotes: String
        get() = buildList {
            notes.trim().takeIf(String::isNotBlank)?.let(::add)
            audioTranscripts.sortedBy(WaypointAudioTranscript::createdAtMillis).forEach { transcript ->
                add("录音转写：${transcript.text.trim()}")
            }
        }.joinToString("\n")
}

class WaypointProfileStore(context: Context) {
    private val file = File(context.filesDir, "waypoint-profiles/catalog.json")

    @Synchronized
    fun find(id: String): WaypointProfile = list().firstOrNull { it.waypointId == id }
        ?: WaypointProfile(id, "", emptyList())

    @Synchronized
    fun save(id: String, notes: String) {
        val current = find(id)
        saveAll(list().filterNot { it.waypointId == id } + current.copy(notes = notes.take(4_000)))
    }

    /** Adds or replaces one recording's transcript without overwriting the user's manual notes. */
    @Synchronized
    fun saveAudioTranscript(id: String, audioId: String, text: String, createdAtMillis: Long) {
        val clean = text.trim().take(1_000)
        if (clean.isBlank()) return
        val current = find(id)
        val transcript = WaypointAudioTranscript(audioId, clean, createdAtMillis)
        val updated = current.copy(
            audioTranscripts = (current.audioTranscripts.filterNot { it.audioId == audioId } + transcript)
                .sortedBy(WaypointAudioTranscript::createdAtMillis),
        )
        saveAll(list().filterNot { it.waypointId == id } + updated)
    }

    @Synchronized
    fun delete(id: String) = saveAll(list().filterNot { it.waypointId == id })

    private fun list(): List<WaypointProfile> = runCatching {
        if (!file.isFile) return@runCatching emptyList()
        val array = JSONArray(file.readText())
        (0 until array.length()).map { index ->
            val value = array.getJSONObject(index)
            WaypointProfile(
                value.getString("id"),
                value.optString("notes"),
                value.optJSONArray("audioTranscripts")?.let { transcripts ->
                    (0 until transcripts.length()).mapNotNull { transcriptIndex ->
                        val transcript = transcripts.optJSONObject(transcriptIndex) ?: return@mapNotNull null
                        val audioId = transcript.optString("audioId")
                        val text = transcript.optString("text")
                        if (audioId.isBlank() || text.isBlank()) null else WaypointAudioTranscript(
                            audioId,
                            text,
                            transcript.optLong("createdAtMillis", 0L),
                        )
                    }
                }.orEmpty(),
            )
        }
    }.getOrDefault(emptyList())

    private fun saveAll(values: List<WaypointProfile>) {
        file.parentFile?.mkdirs()
        val json = JSONArray(values.map { profile ->
            JSONObject()
                .put("id", profile.waypointId)
                .put("notes", profile.notes)
                .put("audioTranscripts", JSONArray(profile.audioTranscripts.map { transcript ->
                    JSONObject()
                        .put("audioId", transcript.audioId)
                        .put("text", transcript.text)
                        .put("createdAtMillis", transcript.createdAtMillis)
                }))
        })
        val temporary = File(file.parentFile, file.name + ".tmp").apply { writeText(json.toString()) }
        if (!temporary.renameTo(file)) {
            temporary.copyTo(file, overwrite = true)
            check(temporary.delete())
        }
    }
}
