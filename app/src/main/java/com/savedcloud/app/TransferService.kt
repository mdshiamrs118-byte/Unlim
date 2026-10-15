package com.savedcloud.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.savedcloud.app.td.TdRepository
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground service that keeps the process alive while files are uploading or downloading.
 * It stops itself when nothing is left to do.
 */
class TransferService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var idleChecks = 0

    private val tick = object : Runnable {
        override fun run() {
            val active = TdRepository.activeTransfers()
            if (active == 0 && busy.get() == 0) {
                idleChecks++
                if (idleChecks >= 3) {
                    ServiceCompat.stopForeground(this@TransferService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return
                }
            } else {
                idleChecks = 0
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(NOTIFICATION_ID, buildNotification(active))
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "File transfers", NotificationManager.IMPORTANCE_LOW)
        )
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(0),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        handler.removeCallbacks(tick)
        idleChecks = 0
        handler.postDelayed(tick, 1000)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(active: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = if (active > 0) "$active file(s) transferring" else "Preparing files…"
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Saved Cloud")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "transfers"
        private const val NOTIFICATION_ID = 1

        /** Number of files currently being copied/queued by the app (keeps the service alive). */
        val busy = AtomicInteger(0)

        fun start(ctx: Context) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, TransferService::class.java))
            } catch (_: Throwable) {
                // Not allowed to start a foreground service right now; transfers still run while the app is open.
            }
        }
    }
}
