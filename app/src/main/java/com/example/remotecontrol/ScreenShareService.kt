package com.example.remotecontrol

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class ScreenShareService : Service() {

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            WebRtcManager.stopScreen()
            stopSelf()
            return START_NOT_STICKY
        }

        val mode = intent?.getStringExtra(EXTRA_MODE) ?: MODE_SCREEN
        startForeground(NOTIFICATION_ID, buildNotification(mode))

        if (mode == MODE_CAMERA) {
            val front = intent?.getBooleanExtra(EXTRA_FRONT, true) ?: true
            if (!WebRtcManager.startCamera(this, front)) stopSelf()
        } else {
            val data: Intent? = intent?.let {
                if (Build.VERSION.SDK_INT >= 33) {
                    it.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    it.getParcelableExtra(EXTRA_DATA)
                }
            }
            if (data != null) {
                WebRtcManager.startScreen(this, data)
            } else {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        WebRtcManager.stopScreen()
        super.onDestroy()
    }

    private fun buildNotification(mode: String): Notification {
        val title = if (mode == MODE_CAMERA) "الكاميرا تعمل" else "المشاركة نشطة"
        val text = if (mode == MODE_CAMERA) "جهازك يبث من الكاميرا الآن." else "جهازك يُشارَك الآن."
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "مشاركة الشاشة والكاميرا",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "screen_share"
        const val NOTIFICATION_ID = 1
        const val EXTRA_MODE = "mode"
        const val EXTRA_FRONT = "front"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "com.example.remotecontrol.STOP"
        const val MODE_SCREEN = "screen"
        const val MODE_CAMERA = "camera"
    }
}
