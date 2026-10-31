package com.example.remotecontrol

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import kotlin.random.Random

const val SERVER_URL = "wss://signaling-server-xp4s.onrender.com"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HomeScreen()
                }
            }
        }
    }
}

@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val deviceId = remember { Random.nextInt(100000, 999999).toString() }
    var status by remember { mutableStateOf("جارٍ الاتصال...") }
    var sharing by remember { mutableStateOf(false) }
    var captureInfo by remember { mutableStateOf("لم تُلتقط الشاشة بعد") }

    remember(deviceId) {
        connectToServer(deviceId) { newStatus -> status = newStatus }
    }

    DisposableEffect(Unit) {
        ScreenShareService.onFrameCaptured = { message -> captureInfo = message }
        onDispose { ScreenShareService.onFrameCaptured = null }
    }

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val intent = Intent(context, ScreenShareService::class.java).apply {
                putExtra(ScreenShareService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenShareService.EXTRA_DATA, result.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            sharing = true
        }
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // نطلب إذن التقاط الشاشة سواء وافق المستخدم على الإشعارات أم لا
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "معرّف هذا الجهاز: $deviceId")
        Text(text = status)
        Text(text = if (sharing) "الشاشة مُشارَكة الآن" else "الشاشة غير مُشارَكة")
        Text(text = captureInfo)
        Button(onClick = {
            val needsNotificationPermission = Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED

            if (needsNotificationPermission) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                val manager = context.getSystemService(MediaProjectionManager::class.java)
                projectionLauncher.launch(manager.createScreenCaptureIntent())
            }
        }) {
            Text("مشاركة الشاشة")
        }
        Button(onClick = { /* لاحقاً: الاتصال بجهاز */ }) {
            Text("الاتصال بجهاز")
        }
    }
}

fun connectToServer(deviceId: String, onStatus: (String) -> Unit): WebSocketClient {
    val client = object : WebSocketClient(URI(SERVER_URL)) {
        override fun onOpen(handshake: ServerHandshake?) {
            send("""{"type":"register","id":"$deviceId"}""")
            onStatus("متصل بالخادم")
        }

        override fun onMessage(message: String?) {}

        override fun onClose(code: Int, reason: String?, remote: Boolean) {
            onStatus("انقطع الاتصال")
        }

        override fun onError(ex: Exception?) {
            onStatus("خطأ في الاتصال")
        }
    }
    client.connect()
    return client
}
