package com.watchnav.sync.transport

import android.content.Context
import android.util.Log
import com.watchnav.sync.protocol.SyncRecord
import com.watchnav.sync.protocol.SyncWireCodec
import com.watchnav.sync.protocol.TransferManifest
import com.watchnav.sync.protocol.DeviceHello
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class LanSyncSnapshot(
    val hello: DeviceHello,
    val records: List<SyncRecord>,
    val artifacts: List<Pair<TransferManifest, File>>,
)

data class LanSyncResult(
    val peerAddress: String,
    val recordsSent: Int,
    val recordsReceived: Int,
    val filesSent: Int,
    val filesReceived: Int,
)

/** Application-specific merge/install hooks shared by the phone and watch endpoints. */
interface LanSyncPeer {
    fun snapshot(): LanSyncSnapshot
    fun receiveHello(value: DeviceHello)
    fun receiveRecord(value: SyncRecord)
    fun alreadyHas(manifest: TransferManifest): Boolean
    fun receiveArtifact(value: ReceivedTransfer)
}

/**
 * One-shot local-network fallback for devices whose Google Wear Data Layer is unavailable.
 *
 * The watch only advertises while the user has explicitly opened the receive window. Discovery
 * returns a random 128-bit session token, so an unrelated LAN client cannot upload to a dormant
 * watch. The TCP exchange is resumable and hashes every file before installation.
 */
class LanSyncServer(
    context: Context,
    private val peer: LanSyncPeer,
    private val onFinished: (Result<LanSyncResult>) -> Unit,
) : Closeable {
    private val app = context.applicationContext
    private val running = AtomicBoolean(false)
    private val executor = Executors.newFixedThreadPool(2)
    private val token = ByteArray(16).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
    private var udp: DatagramSocket? = null
    private var tcp: ServerSocket? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val server = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(0))
            soTimeout = WINDOW_MILLIS
        }
        tcp = server
        executor.execute { discoveryLoop(server.localPort) }
        executor.execute {
            val result = runCatching {
                server.accept().use { socket ->
                    socket.soTimeout = TRANSFER_TIMEOUT_MILLIS
                    val input = DataInputStream(BufferedInputStream(socket.getInputStream(), BUFFER_BYTES))
                    val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), BUFFER_BYTES))
                    require(input.readInt() == MAGIC && input.readInt() == VERSION) { "Invalid local sync client" }
                    require(input.readUTF() == token) { "Invalid local sync token" }
                    output.writeInt(MAGIC); output.writeInt(VERSION); output.flush()
                    val received = receiveSnapshot(input, output, peer, app, socket.inetAddress.hostAddress.orEmpty())
                    val sent = sendSnapshot(output, input, peer.snapshot())
                    LanSyncResult(
                        socket.inetAddress.hostAddress.orEmpty(),
                        sent.first, received.first, sent.second, received.second,
                    )
                }
            }
            close()
            onFinished(result)
        }
    }

    private fun discoveryLoop(tcpPort: Int) {
        runCatching {
            DatagramSocket(null).also { udp = it }.apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(DISCOVERY_PORT))
                soTimeout = 1_000
            }.use { socket ->
                val buffer = ByteArray(512)
                while (running.get()) {
                    try {
                        val request = DatagramPacket(buffer, buffer.size)
                        socket.receive(request)
                        val text = String(request.data, request.offset, request.length, Charsets.US_ASCII)
                        if (!text.startsWith(DISCOVERY_REQUEST)) continue
                        val nonce = text.substringAfter(':', "").take(80)
                        val reply = "$DISCOVERY_RESPONSE:$tcpPort:$token:$nonce".toByteArray(Charsets.US_ASCII)
                        socket.send(DatagramPacket(reply, reply.size, request.address, request.port))
                    } catch (_: SocketTimeoutException) {
                        // Re-check the receive-window lifetime without polling the radio.
                    }
                }
            }
        }.onFailure { if (running.get()) Log.w(TAG, "LAN discovery stopped", it) }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        runCatching { udp?.close() }
        runCatching { tcp?.close() }
        executor.shutdownNow()
    }
}

class LanSyncClient(
    context: Context,
    private val peer: LanSyncPeer,
) {
    private val app = context.applicationContext

    fun sync(): LanSyncResult {
        val endpoint = discover()
        Socket().use { socket ->
            socket.connect(InetSocketAddress(endpoint.address, endpoint.port), CONNECT_TIMEOUT_MILLIS)
            socket.soTimeout = TRANSFER_TIMEOUT_MILLIS
            val input = DataInputStream(BufferedInputStream(socket.getInputStream(), BUFFER_BYTES))
            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), BUFFER_BYTES))
            output.writeInt(MAGIC); output.writeInt(VERSION); output.writeUTF(endpoint.token); output.flush()
            require(input.readInt() == MAGIC && input.readInt() == VERSION) { "Invalid watch response" }
            val sent = sendSnapshot(output, input, peer.snapshot())
            val received = receiveSnapshot(input, output, peer, app, endpoint.address.hostAddress.orEmpty())
            return LanSyncResult(
                endpoint.address.hostAddress.orEmpty(),
                sent.first, received.first, sent.second, received.second,
            )
        }
    }

    private fun discover(): Endpoint {
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = DISCOVERY_TIMEOUT_MILLIS
            val nonce = UUID.randomUUID().toString()
            val request = "$DISCOVERY_REQUEST:$nonce".toByteArray(Charsets.US_ASCII)
            // Some mesh routers forward unicast between clients but suppress Wi-Fi broadcast
            // between different access points. Probe the local subnet as well; this only runs
            // after an explicit user sync action and keeps Secure Folder usable without GMS.
            (broadcastAddresses() + localSubnetAddresses()).forEach { address ->
                runCatching { socket.send(DatagramPacket(request, request.size, address, DISCOVERY_PORT)) }
            }
            val buffer = ByteArray(512)
            while (true) {
                val reply = DatagramPacket(buffer, buffer.size)
                socket.receive(reply)
                val parts = String(reply.data, reply.offset, reply.length, Charsets.US_ASCII).split(':')
                if (parts.size == 4 && parts[0] == DISCOVERY_RESPONSE && parts[3] == nonce) {
                    return Endpoint(reply.address, parts[1].toInt(), parts[2])
                }
            }
        }
    }

    private fun broadcastAddresses(): Set<InetAddress> = buildSet {
        add(InetAddress.getByName("255.255.255.255"))
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().forEach { network ->
            if (!network.isUp || network.isLoopback) return@forEach
            network.interfaceAddresses.forEach { value ->
                value.broadcast?.takeIf { it is Inet4Address }?.let(::add)
            }
        }
    }

    private fun localSubnetAddresses(): Set<InetAddress> = buildSet {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().forEach { network ->
            if (!network.isUp || network.isLoopback) return@forEach
            network.interfaceAddresses.forEach { value ->
                val address = value.address as? Inet4Address ?: return@forEach
                val originalPrefix = value.networkPrefixLength.toInt()
                if (originalPrefix !in 1..30 || address.isLoopbackAddress) return@forEach
                // Never probe more than one /24 (254 hosts), even on a broad carrier/VPN route.
                val prefix = maxOf(originalPrefix, 24)
                val raw = address.address
                val ip = ((raw[0].toInt() and 0xff) shl 24) or
                    ((raw[1].toInt() and 0xff) shl 16) or
                    ((raw[2].toInt() and 0xff) shl 8) or
                    (raw[3].toInt() and 0xff)
                val hostBits = 32 - prefix
                val mask = -1 shl hostBits
                val networkBase = ip and mask
                val addressCount = 1 shl hostBits
                for (offset in 1 until addressCount - 1) {
                    val candidate = networkBase + offset
                    if (candidate == ip) continue
                    add(InetAddress.getByAddress(byteArrayOf(
                        (candidate ushr 24).toByte(),
                        (candidate ushr 16).toByte(),
                        (candidate ushr 8).toByte(),
                        candidate.toByte(),
                    )))
                }
            }
        }
    }

    private data class Endpoint(val address: InetAddress, val port: Int, val token: String)
}

private fun sendSnapshot(
    output: DataOutputStream,
    input: DataInputStream,
    snapshot: LanSyncSnapshot,
): Pair<Int, Int> {
    SyncWireCodec.encodeHello(snapshot.hello).let { bytes ->
        output.writeByte(FRAME_HELLO); output.writeInt(bytes.size); output.write(bytes); output.flush()
        require(input.readBoolean()) { input.readUTF().ifBlank { "Peer rejected identity" } }
        input.readUTF()
    }
    var recordCount = 0
    snapshot.records.chunked(RECORD_BATCH_SIZE).forEach { batch ->
        output.writeByte(FRAME_RECORD_BATCH)
        output.writeInt(batch.size)
        batch.forEach { record ->
            val bytes = SyncWireCodec.encodeRecord(record)
            output.writeInt(bytes.size)
            output.write(bytes)
        }
        output.flush()
        require(input.readBoolean()) { input.readUTF().ifBlank { "Peer rejected record batch" } }
        input.readUTF()
        recordCount += batch.size
    }
    var fileCount = 0
    snapshot.artifacts.distinctBy { it.first.kind to it.first.sha256 }.forEach { (manifest, file) ->
        if (!file.isFile || file.length() != manifest.totalBytes) return@forEach
        val bytes = SyncWireCodec.encodeTransferManifest(manifest)
        output.writeByte(FRAME_FILE); output.writeInt(bytes.size); output.write(bytes); output.flush()
        val offset = input.readLong()
        require(offset in 0..manifest.totalBytes) { "Invalid resume offset" }
        if (offset < manifest.totalBytes) {
            FileInputStream(file).buffered(BUFFER_BYTES).use { source ->
                var skipped = 0L
                while (skipped < offset) {
                    val count = source.skip(offset - skipped)
                    require(count > 0) { "Unable to resume local transfer" }
                    skipped += count
                }
                source.copyTo(output, BUFFER_BYTES)
            }
            output.flush()
        }
        require(input.readBoolean()) { input.readUTF().ifBlank { "Peer rejected file" } }
        input.readUTF()
        fileCount++
    }
    output.writeByte(FRAME_END); output.flush()
    return recordCount to fileCount
}

private fun receiveSnapshot(
    input: DataInputStream,
    output: DataOutputStream,
    peer: LanSyncPeer,
    context: Context,
    sourceId: String,
): Pair<Int, Int> {
    val incoming = IncomingTransferStore(context)
    var records = 0
    var files = 0
    while (true) when (input.readUnsignedByte()) {
        FRAME_END -> return records to files
        FRAME_HELLO -> {
            val length = input.readInt()
            val result = runCatching {
                require(length in 1..MAX_WIRE_BYTES)
                peer.receiveHello(SyncWireCodec.decodeHello(ByteArray(length).also(input::readFully)))
            }
            output.writeBoolean(result.isSuccess)
            output.writeUTF(result.exceptionOrNull()?.message.orEmpty().take(512))
            output.flush()
        }
        FRAME_RECORD -> {
            val length = input.readInt()
            val result = runCatching {
                require(length in 1..MAX_WIRE_BYTES)
                val record = SyncWireCodec.decodeRecord(ByteArray(length).also(input::readFully))
                runCatching { peer.receiveRecord(record) }
                    .onFailure { Log.w(TAG, "Ignored invalid sync record ${record.type}/${record.id}", it) }
            }
            output.writeBoolean(result.isSuccess); output.writeUTF(result.exceptionOrNull()?.message.orEmpty().take(512)); output.flush()
            if (result.isSuccess) records++
        }
        FRAME_RECORD_BATCH -> {
            val count = input.readInt()
            val result = runCatching {
                require(count in 1..RECORD_BATCH_SIZE)
                repeat(count) {
                    val length = input.readInt()
                    require(length in 1..MAX_WIRE_BYTES)
                    val record = SyncWireCodec.decodeRecord(ByteArray(length).also(input::readFully))
                    runCatching { peer.receiveRecord(record) }
                        .onFailure { Log.w(TAG, "Ignored invalid sync record ${record.type}/${record.id}", it) }
                }
            }
            output.writeBoolean(result.isSuccess)
            output.writeUTF(result.exceptionOrNull()?.message.orEmpty().take(512))
            output.flush()
            if (result.isSuccess) records += count
        }
        FRAME_FILE -> {
            val length = input.readInt()
            require(length in 1..MAX_WIRE_BYTES)
            val manifest = SyncWireCodec.decodeTransferManifest(ByteArray(length).also(input::readFully))
            val alreadyPresent = peer.alreadyHas(manifest)
            val offset = if (alreadyPresent) manifest.totalBytes else incoming.saveManifest(manifest)
            output.writeLong(offset); output.flush()
            val result = if (alreadyPresent) Result.success(Unit) else incoming
                .receiveLan(sourceId, manifest, input, offset)
                .map { received -> requireNotNull(received).also(peer::receiveArtifact); Unit }
            output.writeBoolean(result.isSuccess); output.writeUTF(result.exceptionOrNull()?.message.orEmpty().take(512)); output.flush()
            if (result.isSuccess) files++
        }
        else -> error("Unknown local sync frame")
    }
}

private const val MAGIC = 0x574E4C41 // WNLA
private const val VERSION = 2
private const val FRAME_END = 0
private const val FRAME_RECORD = 1
private const val FRAME_FILE = 2
private const val FRAME_HELLO = 3
private const val FRAME_RECORD_BATCH = 4
private const val DISCOVERY_PORT = 39876
private const val DISCOVERY_REQUEST = "WATCHNAV_DISCOVER_V1"
private const val DISCOVERY_RESPONSE = "WATCHNAV_WATCH_V1"
private const val DISCOVERY_TIMEOUT_MILLIS = 3_000
private const val CONNECT_TIMEOUT_MILLIS = 5_000
private const val TRANSFER_TIMEOUT_MILLIS = 15 * 60 * 1_000
private const val WINDOW_MILLIS = 3 * 60 * 1_000
private const val BUFFER_BYTES = 64 * 1024
private const val MAX_WIRE_BYTES = 128 * 1024
private const val RECORD_BATCH_SIZE = 200
private const val TAG = "WatchNavLanSync"
