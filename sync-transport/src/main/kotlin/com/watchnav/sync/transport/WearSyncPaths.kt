package com.watchnav.sync.transport

object WearSyncPaths {
    const val ROOT = "/watchnav/v1"
    const val HELLO_PREFIX = "$ROOT/hello/"
    const val INVENTORY_PREFIX = "$ROOT/inventory/"
    const val RECORD_PREFIX = "$ROOT/record/"
    const val TRANSFER_MANIFEST_PREFIX = "$ROOT/transfer-manifest/"
    const val TRANSFER_CHECKPOINT_PREFIX = "$ROOT/transfer-checkpoint/"
    const val MESSAGE_SYNC_REQUEST = "$ROOT/message/sync-request"
    const val MESSAGE_TRANSFER_OFFER = "$ROOT/message/transfer-offer"
    const val MESSAGE_TRANSFER_CHECKPOINT = "$ROOT/message/transfer-checkpoint"
    const val CHANNEL_PREFIX = "$ROOT/channel/"

    fun hello(deviceId: String) = "$HELLO_PREFIX${safe(deviceId)}"
    fun inventory(deviceId: String, cursor: Long) = "$INVENTORY_PREFIX${safe(deviceId)}/$cursor"
    fun record(type: String, id: String) = "$RECORD_PREFIX${safe(type)}/${safe(id)}"
    fun transferManifest(transferId: String) = "$TRANSFER_MANIFEST_PREFIX${safe(transferId)}"
    fun transferCheckpoint(transferId: String) = "$TRANSFER_CHECKPOINT_PREFIX${safe(transferId)}"
    fun channel(transferId: String, offset: Long) = "$CHANNEL_PREFIX${safe(transferId)}/$offset"

    fun parseChannel(path: String): Pair<String, Long>? {
        if (!path.startsWith(CHANNEL_PREFIX)) return null
        val parts = path.removePrefix(CHANNEL_PREFIX).split('/')
        if (parts.size != 2) return null
        val id = parts[0].takeIf { it.matches(SAFE) } ?: return null
        val offset = parts[1].toLongOrNull()?.takeIf { it >= 0 } ?: return null
        return id to offset
    }

    private fun safe(value: String): String {
        require(value.matches(SAFE)) { "Unsafe Data Layer path segment" }
        return value
    }

    private val SAFE = Regex("[A-Za-z0-9._-]{1,160}")
}
