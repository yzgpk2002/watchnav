package com.watchnav.mobile.data

import android.content.Context
import com.watchnav.sync.protocol.DeviceRole
import com.watchnav.sync.protocol.SyncEntityType
import com.watchnav.sync.protocol.SyncRecord
import com.watchnav.sync.transport.SyncRecordStore

class MobileSyncRepository(context: Context) : SyncRecordStore(
    context,
    "watchnav-companion.db",
) {
    fun upsertLocal(
        type: SyncEntityType,
        id: String,
        contentType: String,
        payload: ByteArray,
        deleted: Boolean = false,
    ): SyncRecord = upsertLocal(type, id, DeviceRole.PHONE, contentType, payload, deleted)
}
