package com.watchnav.core.fit

import com.watchnav.core.Course
import com.watchnav.core.GeoMath
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant

object FitWriter {
    fun write(course: Course, output: OutputStream) {
        val data = ByteArrayOutputStream()
        writeFileId(data, course.points.firstOrNull()?.time ?: Instant.now())
        writeRecordDefinition(data)
        var distanceMeters = 0.0
        course.points.forEachIndexed { index, point ->
            if (index > 0 && index !in course.breakBeforePointIndices) {
                distanceMeters += GeoMath.distanceMeters(
                    course.points[index - 1].position,
                    point.position,
                )
            }
            writeRecord(data, point.position.latitude, point.position.longitude,
                point.position.elevationMeters, point.time, distanceMeters)
        }
        val payload = data.toByteArray()
        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(HEADER_SIZE.toByte())
            put(PROTOCOL_VERSION.toByte())
            putShort(PROFILE_VERSION.toShort())
            putInt(payload.size)
            put(".FIT".toByteArray(Charsets.US_ASCII))
            putShort(crc(array().copyOf(12)).toShort())
        }.array()
        output.write(header)
        output.write(payload)
        output.write(littleEndianShort(crc(header + payload)))
    }

    private fun writeFileId(output: OutputStream, createdAt: Instant) {
        output.write(byteArrayOf(0x40, 0x00, 0x00))
        output.write(littleEndianShort(0))
        output.write(4)
        field(output, 0, 1, BASE_TYPE_ENUM)
        field(output, 1, 2, BASE_TYPE_UINT16)
        field(output, 2, 2, BASE_TYPE_UINT16)
        field(output, 4, 4, BASE_TYPE_UINT32)
        output.write(0x00)
        output.write(FILE_TYPE_ACTIVITY)
        output.write(littleEndianShort(MANUFACTURER_DEVELOPMENT))
        output.write(littleEndianShort(PRODUCT_WATCHNAV))
        output.write(littleEndianInt(fitTimestamp(createdAt)))
    }

    private fun writeRecordDefinition(output: OutputStream) {
        output.write(byteArrayOf(0x41, 0x00, 0x00))
        output.write(littleEndianShort(20))
        output.write(5)
        field(output, 253, 4, BASE_TYPE_UINT32)
        field(output, 0, 4, BASE_TYPE_SINT32)
        field(output, 1, 4, BASE_TYPE_SINT32)
        field(output, 2, 2, BASE_TYPE_UINT16)
        field(output, 5, 4, BASE_TYPE_UINT32)
    }

    private fun writeRecord(
        output: OutputStream,
        latitude: Double,
        longitude: Double,
        elevationMeters: Double?,
        time: Instant?,
        distanceMeters: Double,
    ) {
        output.write(0x01)
        output.write(littleEndianInt(time?.let(::fitTimestamp) ?: UINT32_INVALID))
        output.write(littleEndianInt(degreesToSemicircles(latitude)))
        output.write(littleEndianInt(degreesToSemicircles(longitude)))
        val altitude = elevationMeters?.let { ((it + 500.0) * 5.0).toInt().coerceIn(0, 65_534) }
            ?: UINT16_INVALID
        output.write(littleEndianShort(altitude))
        output.write(littleEndianInt((distanceMeters * 100.0).toLong().coerceAtMost(0xfffffffeL).toInt()))
    }

    private fun field(output: OutputStream, number: Int, size: Int, baseType: Int) {
        output.write(byteArrayOf(number.toByte(), size.toByte(), baseType.toByte()))
    }

    private fun fitTimestamp(instant: Instant): Int =
        (instant.epochSecond - FIT_EPOCH_UNIX_SECONDS).coerceAtLeast(0).toInt()

    private fun degreesToSemicircles(value: Double): Int =
        (value * SEMICIRCLES_PER_DEGREE).toLong().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

    private fun littleEndianShort(value: Int): ByteArray = byteArrayOf(value.toByte(), (value ushr 8).toByte())

    private fun littleEndianInt(value: Int): ByteArray = byteArrayOf(
        value.toByte(),
        (value ushr 8).toByte(),
        (value ushr 16).toByte(),
        (value ushr 24).toByte(),
    )

    private fun crc(bytes: ByteArray): Int {
        var crc = 0
        bytes.forEach { byte ->
            var value = byte.toInt() and 0xff
            repeat(2) {
                val nibble = crc xor value
                crc = (crc ushr 4) xor CRC_TABLE[nibble and 0x0f]
                value = value ushr 4
            }
        }
        return crc and 0xffff
    }

    private const val HEADER_SIZE = 14
    private const val PROTOCOL_VERSION = 0x20
    private const val PROFILE_VERSION = 2100
    private const val FILE_TYPE_ACTIVITY = 4
    private const val MANUFACTURER_DEVELOPMENT = 255
    private const val PRODUCT_WATCHNAV = 1
    private const val BASE_TYPE_ENUM = 0x00
    private const val BASE_TYPE_SINT32 = 0x85
    private const val BASE_TYPE_UINT16 = 0x84
    private const val BASE_TYPE_UINT32 = 0x86
    private const val UINT16_INVALID = 0xffff
    private const val UINT32_INVALID = -1
    private const val FIT_EPOCH_UNIX_SECONDS = 631_065_600L
    private const val SEMICIRCLES_PER_DEGREE = 11_930_464.711111111
    private val CRC_TABLE = intArrayOf(
        0x0000, 0xCC01, 0xD801, 0x1400, 0xF001, 0x3C00, 0x2800, 0xE401,
        0xA001, 0x6C00, 0x7800, 0xB401, 0x5000, 0x9C01, 0x8801, 0x4400,
    )
}
