package com.watchnav.app.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.watchnav.app.MainActivity
import com.watchnav.app.R
import com.watchnav.sync.transport.LanSyncServer

/** Three-minute, user-initiated Wi-Fi receive window; it never polls while navigation is idle. */
class WatchLanSyncService : Service() {
    private var coordinator: WatchSyncCoordinator? = null
    private var server: LanSyncServer? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.lan_sync_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val launch = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(getString(R.string.lan_sync_waiting))
                .setContentText(getString(R.string.lan_sync_same_wifi))
                .setContentIntent(launch)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build(),
        )
        val owner = WatchSyncCoordinator(this).also { coordinator = it }
        server = LanSyncServer(this, owner.lanPeer()) { result ->
            result.onSuccess { Log.i(TAG, "LAN sync complete: $it") }
                .onFailure { Log.w(TAG, "LAN sync window closed", it) }
            stopSelf()
        }.also(LanSyncServer::start)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        server?.close()
        coordinator?.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "watchnav_lan_sync"
        private const val NOTIFICATION_ID = 1120
        private const val TAG = "WatchNavLanSync"

        fun openReceiveWindow(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, WatchLanSyncService::class.java))
        }
    }
}
