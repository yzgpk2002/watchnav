package com.watchnav.app

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class RecordedFishingAudio(val id: String, val file: File, val durationMillis: Long)

/** Records uncompressed 16 kHz mono PCM in a standard WAV container for phone-side recognition. */
class FishingAudioRecorder(context: Context) {
    private val directory = File(context.filesDir, "fishing/audio")
    private val recording = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var worker: Thread? = null
    private var currentId: String? = null
    private var currentFile: File? = null
    private var startedAtMillis = 0L

    val isRecording: Boolean get() = recording.get()

    @SuppressLint("MissingPermission")
    fun start(): String {
        check(recording.compareAndSet(false, true)) { "Recording is already active" }
        directory.mkdirs()
        val id = UUID.randomUUID().toString()
        val file = File(directory, "$id.wav")
        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING).coerceAtLeast(4096)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            CHANNEL,
            ENCODING,
            minimum * 2,
        )
        check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Microphone is unavailable" }
        currentId = id
        currentFile = file
        startedAtMillis = System.currentTimeMillis()
        audioRecord = recorder
        worker = Thread({ writeWav(recorder, file, minimum) }, "WatchNavFishingAudio").apply { start() }
        return id
    }

    fun stop(): RecordedFishingAudio? {
        if (!recording.compareAndSet(true, false)) return null
        runCatching { audioRecord?.stop() }
        worker?.join(2_000)
        audioRecord?.release()
        audioRecord = null
        worker = null
        val file = currentFile
        val id = currentId
        currentFile = null
        currentId = null
        if (file == null || id == null || file.length() <= WAV_HEADER_BYTES.toLong()) {
            file?.delete()
            return null
        }
        return RecordedFishingAudio(id, file, System.currentTimeMillis() - startedAtMillis)
    }

    private fun writeWav(recorder: AudioRecord, file: File, bufferSize: Int) {
        RandomAccessFile(file, "rw").use { output ->
            output.setLength(0)
            output.write(ByteArray(WAV_HEADER_BYTES))
            val buffer = ByteArray(bufferSize)
            recorder.startRecording()
            var bytes = 0L
            while (recording.get() && bytes < MAX_AUDIO_BYTES) {
                val count = recorder.read(buffer, 0, buffer.size)
                if (count > 0) {
                    output.write(buffer, 0, count)
                    bytes += count
                }
            }
            recording.set(false)
            writeHeader(output, bytes)
        }
    }

    private fun writeHeader(output: RandomAccessFile, pcmBytes: Long) {
        val byteRate = SAMPLE_RATE * CHANNEL_COUNT * BITS_PER_SAMPLE / 8
        output.seek(0)
        output.writeBytes("RIFF")
        output.writeLittleEndianInt((pcmBytes + 36).toInt())
        output.writeBytes("WAVEfmt ")
        output.writeLittleEndianInt(16)
        output.writeLittleEndianShort(1)
        output.writeLittleEndianShort(CHANNEL_COUNT)
        output.writeLittleEndianInt(SAMPLE_RATE)
        output.writeLittleEndianInt(byteRate)
        output.writeLittleEndianShort(CHANNEL_COUNT * BITS_PER_SAMPLE / 8)
        output.writeLittleEndianShort(BITS_PER_SAMPLE)
        output.writeBytes("data")
        output.writeLittleEndianInt(pcmBytes.toInt())
    }

    private fun RandomAccessFile.writeLittleEndianInt(value: Int) {
        write(byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte()))
    }

    private fun RandomAccessFile.writeLittleEndianShort(value: Int) {
        write(byteArrayOf(value.toByte(), (value shr 8).toByte()))
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val CHANNEL_COUNT = 1
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val BITS_PER_SAMPLE = 16
        const val WAV_HEADER_BYTES = 44
        const val MAX_AUDIO_BYTES = 16L * 1024 * 1024
    }
}
