package com.example.remotecontrol

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject

class ScreenShareService : Service() {

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            pendingAcceptTo = null
            WebRtcManager.stopScreen()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val mode = intent?.getStringExtra(EXTRA_MODE) ?: MODE_SCREEN

        if (mode == MODE_CAMERA &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
        ) {
            report("إذن الكاميرا غير ممنوح، اضغط الزر بعد منحه")
            fail()
            return START_NOT_STICKY
        }

        try {
            startForegroundFor(mode)
        } catch (e: Exception) {
            report("تعذّر بدء الخدمة: ${e.javaClass.simpleName}")
            fail()
            return START_NOT_STICKY
        }

        // النتيجة تأتي بعد أن يبدأ المصدر فعلاً، بدون تجميد الواجهة
        val onResult: (Boolean) -> Unit = { ok ->
            if (ok) {
                report(if (mode == MODE_CAMERA) "الكاميرا بدأت بنجاح" else "الشاشة تُلتقط للبث")
                sendPendingAccept()
            } else {
                fail()
            }
        }

        if (mode == MODE_CAMERA) {
            val front = intent?.getBooleanExtra(EXTRA_FRONT, true) ?: true
            WebRtcManager.startCamera(this, front, onResult)
        } else {
            val data: Intent? = intent?.let {
                if (Build.VERSION.SDK_INT >= 33) {
                    it.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    it.getParcelableExtra(EXTRA_DATA)
                }
            }
            if (data == null) {
                report("لم تصل بيانات إذن الشاشة")
                fail()
            } else {
                WebRtcManager.startScreen(this, data, onResult)
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundFor(mode: String) {
        val notification = buildNotification(mode)
        if (Build.VERSION.SDK_INT >= 29) {
            val type = if (mode == MODE_CAMERA) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun sendPendingAccept() {
        val to = pendingAcceptTo ?: return
        pendingAcceptTo = null
        val sender = WebRtcManager.sender ?: return
        Handler(Looper.getMainLooper()).post {
            sender(to, JSONObject().put("kind", "call_accept"))
        }
    }

    private fun fail() {
        pendingAcceptTo = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun report(msg: String) {
        Handler(Looper.getMainLooper()).post { WebRtcManager.onState?.invoke(msg) }
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

        @Volatile
        var pendingAcceptTo: String? = null
    }
}
