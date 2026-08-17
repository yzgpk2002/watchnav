package com.watchnav.mobile.speech

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.watchnav.core.GeoMath
import com.watchnav.mobile.data.MobileFishingEntry
import com.watchnav.mobile.data.MobileFishingLogStore
import com.watchnav.mobile.data.WaypointProfileStore
import com.watchnav.mobile.sync.CompanionStateBus
import com.watchnav.session.SavedWaypoint
import com.watchnav.session.WaypointStore
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

sealed interface FishingTranscriptionResult {
    data class Success(val entryId: String, val text: String, val waypointId: String?) : FishingTranscriptionResult
    data class Failure(val entryId: String, val message: String) : FishingTranscriptionResult
}

/**
 * Process-wide, sequential SenseVoiceSmall recognizer for WAV files received from the watch.
 * Recognition runs completely on the phone, retains every original recording, and never
 * overwrites manual waypoint notes.
 */
object FishingAudioTranscriptionManager {
    private data class Request(
        val entryId: String,
        val automatic: Boolean,
        val callback: ((FishingTranscriptionResult) -> Unit)?,
    )

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "WatchNavAudioTranscription").apply { isDaemon = true }
    }
    private val queue = ArrayDeque<Request>()
    private val queuedIds = mutableSetOf<String>()
    private var active: Request? = null

    fun hasPending(context: Context): Boolean = MobileFishingLogStore(context.applicationContext)
        .pendingTranscriptions().isNotEmpty()

    fun enqueuePending(context: Context) {
        val app = context.applicationContext
        linkCompletedTranscripts(app)
        MobileFishingLogStore(app).pendingTranscriptions().forEach { entry ->
            enqueue(app, Request(entry.id, automatic = true, callback = null))
        }
    }

    fun transcribeNow(
        context: Context,
        entryId: String,
        callback: (FishingTranscriptionResult) -> Unit,
    ) = enqueue(context.applicationContext, Request(entryId, automatic = false, callback))

    /** Re-links completed transcripts when waypoint and audio records arrive in either order. */
    fun linkCompletedTranscripts(context: Context) {
        val app = context.applicationContext
        val logs = MobileFishingLogStore(app).list().filter { it.transcript.isNotBlank() }
        val marks = WaypointStore(app).list()
        val profiles = WaypointProfileStore(app)
        logs.forEach { entry ->
            FishingWaypointMatcher.match(entry, marks)?.let { mark ->
                profiles.saveAudioTranscript(mark.id, entry.id, entry.transcript, entry.createdAtMillis)
            }
        }
    }

    private fun enqueue(context: Context, request: Request) {
        main.post {
            if (request.entryId in queuedIds || active?.entryId == request.entryId) {
                if (!request.automatic) request.callback?.invoke(
                    FishingTranscriptionResult.Failure(request.entryId, "该录音正在识别"),
                )
                return@post
            }
            queuedIds += request.entryId
            if (request.automatic) queue.addLast(request) else queue.addFirst(request)
            drain(context)
        }
    }

    private fun drain(context: Context) {
        if (active != null) return
        val request = queue.removeFirstOrNull() ?: run {
            main.postDelayed(
                { if (active == null && queue.isEmpty()) worker.execute { SenseVoiceFishingTranscriber.release() } },
                MODEL_IDLE_RELEASE_MILLIS,
            )
            return
        }
        queuedIds -= request.entryId
        val store = MobileFishingLogStore(context)
        val entry = store.find(request.entryId)
        val wav = entry?.let(store::audioFile)
        if (entry == null || wav == null) {
            fail(context, request, "找不到原始录音", countAttempt = false)
            return
        }
        active = request
        store.markTranscriptionStarted(request.entryId)
        worker.execute {
            val result = runCatching { SenseVoiceFishingTranscriber.transcribe(context, wav) }
            main.post {
                result.onSuccess { text ->
                    if (text.isNotBlank()) succeedActive(context, text)
                    else failActive(context, "SenseVoice 未检测到清晰语音")
                }.onFailure { error -> failActive(context, error.message ?: "SenseVoice 离线模型不可用") }
            }
        }
    }

    private fun succeedActive(context: Context, text: String) {
        val request = active ?: return
        val cleanText = normalizeTranscript(text)
        val store = MobileFishingLogStore(context)
        val updated = store.saveAutomaticTranscript(request.entryId, cleanText, TRANSCRIPTION_ENGINE)
        val mark = updated?.let { FishingWaypointMatcher.match(it, WaypointStore(context).list()) }
        if (updated != null && mark != null) {
            WaypointProfileStore(context).saveAudioTranscript(
                mark.id,
                updated.id,
                updated.transcript,
                updated.createdAtMillis,
            )
        }
        finishActive()
        request.callback?.invoke(FishingTranscriptionResult.Success(request.entryId, cleanText, mark?.id))
        CompanionStateBus.emit()
        drain(context)
    }

    private fun failActive(context: Context, message: String) {
        val request = active ?: return
        MobileFishingLogStore(context).markTranscriptionFailed(request.entryId, message)
        finishActive()
        request.callback?.invoke(FishingTranscriptionResult.Failure(request.entryId, message))
        CompanionStateBus.emit()
        drain(context)
    }

    private fun fail(context: Context, request: Request, message: String, countAttempt: Boolean) {
        if (countAttempt) MobileFishingLogStore(context).markTranscriptionFailed(request.entryId, message)
        request.callback?.invoke(FishingTranscriptionResult.Failure(request.entryId, message))
        drain(context)
    }

    private fun finishActive() {
        active = null
    }
}

/** Pure matching policy kept separate from Android recognizer state for deterministic tests. */
internal object FishingWaypointMatcher {
    fun match(entry: MobileFishingEntry, marks: List<SavedWaypoint>): SavedWaypoint? {
        marks.firstOrNull { it.id == entry.id }?.let { return it }
        val point = entry.point ?: return null
        return marks.asSequence()
            .filterNot { it.id.startsWith("osm-") }
            .filter { kotlin.math.abs(entry.createdAtMillis - it.createdAtMillis) <= MATCH_TIME_MILLIS }
            .map { it to GeoMath.distanceMeters(point, it.point) }
            .filter { (_, distance) -> distance <= MATCH_DISTANCE_METERS }
            .minByOrNull { (_, distance) -> distance }
            ?.first
    }

    private const val MATCH_TIME_MILLIS = 5 * 60_000L
    private const val MATCH_DISTANCE_METERS = 25.0
}

/** Uses the same SenseVoiceSmall int8 model family as the installed 古诗摇一摇 app. */
private object SenseVoiceFishingTranscriber {
    private var recognizer: OfflineRecognizer? = null
    private var verifiedModelPath: String? = null

    @Synchronized
    fun transcribe(context: Context, wav: File): String {
        val modelDirectory = ensureModel(context)
        val activeRecognizer = recognizer ?: createRecognizer(modelDirectory).also { recognizer = it }
        val waveform = readPcm16MonoWav(wav)
        check(waveform.size >= SAMPLE_RATE / 2) { "录音太短，请说完一句后再停止" }
        val stream = activeRecognizer.createStream()
        return try {
            stream.acceptWaveform(waveform, SAMPLE_RATE)
            activeRecognizer.decode(stream)
            normalizeTranscript(activeRecognizer.getResult(stream).text)
        } finally {
            stream.release()
        }
    }

    @Synchronized
    fun release() {
        recognizer?.release()
        recognizer = null
        System.gc()
    }

    private fun createRecognizer(directory: File): OfflineRecognizer {
        val senseVoice = OfflineSenseVoiceModelConfig().apply {
            model = File(directory, MODEL_FILE).absolutePath
            language = "zh"
            useInverseTextNormalization = true
        }
        val modelConfig = OfflineModelConfig().apply {
            this.senseVoice = senseVoice
            tokens = File(directory, TOKENS_FILE).absolutePath
            numThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
            provider = "cpu"
        }
        return OfflineRecognizer(null, OfflineRecognizerConfig().apply { this.modelConfig = modelConfig })
    }

    private fun ensureModel(context: Context): File {
        val target = File(context.filesDir, MODEL_DIRECTORY).apply { mkdirs() }
        val model = File(target, MODEL_FILE)
        val tokens = File(target, TOKENS_FILE)
        if (verifiedModelPath == target.absolutePath && model.hasMinimumSize(MIN_MODEL_BYTES) &&
            tokens.hasMinimumSize(MIN_TOKENS_BYTES)) return target
        if (!model.isVerified(MIN_MODEL_BYTES, MODEL_SHA256)) {
            downloadFile(MODEL_URL, model, MIN_MODEL_BYTES, MODEL_SHA256)
        }
        if (!tokens.isVerified(MIN_TOKENS_BYTES, TOKENS_SHA256)) {
            downloadFile(TOKENS_URL, tokens, MIN_TOKENS_BYTES, TOKENS_SHA256)
        }
        check(model.isVerified(MIN_MODEL_BYTES, MODEL_SHA256) &&
            tokens.isVerified(MIN_TOKENS_BYTES, TOKENS_SHA256)) { "SenseVoiceSmall int8 模型不完整" }
        verifiedModelPath = target.absolutePath
        return target
    }

    private fun downloadFile(url: String, target: File, minimumBytes: Long, expectedSha256: String) {
        val partial = File(target.parentFile, target.name + ".part")
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000
        connection.setRequestProperty("User-Agent", "WatchNav-Android")
        val existingBytes = partial.takeIf(File::isFile)?.length()?.coerceAtLeast(0L) ?: 0L
        if (existingBytes > 0L) connection.setRequestProperty("Range", "bytes=$existingBytes-")
        try {
            val response = connection.responseCode
            check(response in 200..299) { "SenseVoice 模型下载失败（$response）" }
            val append = response == HttpURLConnection.HTTP_PARTIAL && existingBytes > 0L
            val expected = connection.contentLengthLong
            FileOutputStream(partial, append).buffered().use { output ->
                connection.inputStream.buffered().use { it.copyTo(output) }
            }
            val expectedTotal = if (append && expected > 0L) existingBytes + expected else expected
            check(partial.length() >= minimumBytes && (expectedTotal <= 0L || partial.length() == expectedTotal)) {
                "SenseVoice 模型下载不完整"
            }
            check(partial.sha256().equals(expectedSha256, ignoreCase = true)) { "SenseVoice 模型校验失败" }
            if (!partial.renameTo(target)) partial.copyTo(target, overwrite = true).also { check(partial.delete()) }
            target.writeVerificationMarker(expectedSha256)
        } finally {
            connection.disconnect()
        }
    }

    private fun readPcm16MonoWav(file: File): FloatArray {
        require(file.isFile && file.length() > 44L) { "录音内容为空" }
        RandomAccessFile(file, "r").use { wav ->
            val header = ByteArray(44)
            wav.readFully(header)
            check(String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(header, 8, 4, Charsets.US_ASCII) == "WAVE") { "录音格式不是 WAV" }
            val channels = littleEndianShort(header, 22)
            val sampleRate = littleEndianInt(header, 24)
            val bits = littleEndianShort(header, 34)
            check(channels == 1 && sampleRate == SAMPLE_RATE && bits == 16) { "录音必须是 16kHz 单声道 PCM" }
            val sampleCount = ((wav.length() - 44L) / 2L).coerceAtMost(MAX_SAMPLES.toLong()).toInt()
            val result = FloatArray(sampleCount)
            for (index in result.indices) {
                val low = wav.read()
                val high = wav.read()
                if (low < 0 || high < 0) return result.copyOf(index)
                result[index] = ((high shl 8) or low).toShort() / 32768.0f
            }
            return result
        }
    }

    private fun littleEndianShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
        littleEndianShort(bytes, offset) or (littleEndianShort(bytes, offset + 2) shl 16)

    private fun File.hasMinimumSize(minimumBytes: Long) = isFile && length() >= minimumBytes

    private fun File.isVerified(minimumBytes: Long, expectedSha256: String): Boolean {
        if (!hasMinimumSize(minimumBytes)) return false
        val marker = File(parentFile, name + ".sha256")
        if (marker.isFile && marker.readText().trim().equals(expectedSha256, ignoreCase = true)) return true
        return runCatching { sha256().equals(expectedSha256, ignoreCase = true) }
            .getOrDefault(false)
            .also { valid -> if (valid) writeVerificationMarker(expectedSha256) }
    }

    private fun File.writeVerificationMarker(sha256: String) {
        File(parentFile, name + ".sha256").writeText(sha256.lowercase())
    }

    private fun File.sha256(): String = inputStream().buffered().use { input ->
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count <= 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private const val SAMPLE_RATE = 16_000
    private const val MAX_SAMPLES = SAMPLE_RATE * 15 * 60
    private const val MODEL_DIRECTORY = "sensevoice-small-int8"
    private const val MODEL_FILE = "model.int8.onnx"
    private const val TOKENS_FILE = "tokens.txt"
    private const val MIN_MODEL_BYTES = 230_686_720L
    private const val MIN_TOKENS_BYTES = 256_000L
    private const val MODEL_SHA256 = "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51"
    private const val TOKENS_SHA256 = "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc"
    private const val MODEL_BASE = "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/2365baeacb507f821a0c8120fcee3d484dba7a07"
    private const val MODEL_URL = "$MODEL_BASE/model.int8.onnx?download=true"
    private const val TOKENS_URL = "$MODEL_BASE/tokens.txt?download=true"
}

internal fun normalizeTranscript(value: String): String {
    var normalized = value
        .replace(Regex("<\\|[^|>]+\\|>"), "")
        .trim()
        .replace(Regex("\\s+"), " ")
    val adjacentHan = Regex("([\\p{IsHan}]) ([\\p{IsHan}])")
    while (adjacentHan.containsMatchIn(normalized)) normalized = normalized.replace(adjacentHan, "$1$2")
    return normalized
}

private const val TRANSCRIPTION_ENGINE = "sensevoice-small-int8-2024-07-17"
private const val MODEL_IDLE_RELEASE_MILLIS = 30_000L
