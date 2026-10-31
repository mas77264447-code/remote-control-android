package com.example.remotecontrol

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
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
    var incoming by remember { mutableStateOf("") }
    var targetId by remember { mutableStateOf("") }

    val client = remember(deviceId) {
        connectToServer(
            deviceId = deviceId,
            onStatus = { status = it },
            onCallRequest = { from -> incoming = "طلب اتصال من الجهاز $from" }
        )
    }

    DisposableEffect(client) {
        onDispose { client.close() }
    }

    DisposableEffect(Unit) {
        ScreenShareService.onFrameCaptured = { message -> captureInfo = message }
        ScreenShareService.onStopped = {
            sharing = false
            captureInfo = "توقفت المشاركة"
        }
        onDispose {
            ScreenShareService.onFrameCaptured = null
            ScreenShareService.onStopped = null
        }
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
        if (incoming.isNotEmpty()) {
            Text(text = incoming)
        }

        if (!sharing) {
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
        } else {
            Button(onClick = {
                val stopIntent = Intent(context, ScreenShareService::class.java).apply {
                    action = ScreenShareService.ACTION_STOP
                }
                context.startService(stopIntent)
                sharing = false
                captureInfo = "توقفت المشاركة"
            }) {
                Text("إيقاف المشاركة")
            }
        }

        OutlinedTextField(
            value = targetId,
            onValueChange = { input -> targetId = input.filter { it.isDigit() }.take(6) },
            label = { Text("معرّف الجهاز الآخر") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.padding(top = 12.dp)
        )

        Button(onClick = {
            val target = targetId.trim()
            if (target.isEmpty()) {
                status = "أدخل معرّف الجهاز الآخر أولاً"
                return@Button
            }
            if (target == deviceId) {
                status = "لا يمكن الاتصال بنفس الجهاز"
                return@Button
            }
            if (!client.isOpen) {
                status = "غير متصل بالخادم"
                return@Button
            }
            val payload = JSONObject()
                .put("type", "signal")
                .put("to", target)
                .put("data", JSONObject().put("kind", "call_request"))
            client.send(payload.toString())
            status = "أُرسل طلب اتصال إلى $target"
        }) {
            Text("الاتصال بجهاز")
        }
    }
}

fun connectToServer(
    deviceId: String,
    onStatus: (String) -> Unit,
    onCallRequest: (String) -> Unit
): WebSocketClient {
    val mainHandler = Handler(Looper.getMainLooper())

    val client = object : WebSocketClient(URI(SERVER_URL)) {
        override fun onOpen(handshake: ServerHandshake?) {
            send(JSONObject().put("type", "register").put("id", deviceId).toString())
            mainHandler.post { onStatus("متصل بالخادم") }
        }

        override fun onMessage(message: String?) {
            if (message == null) return
            val json = try {
                JSONObject(message)
            } catch (e: Exception) {
                return
            }
            when (json.optString("type")) {
                "signal" -> {
                    val from = json.optString("from")
                    val kind = json.optJSONObject("data")?.optString("kind")
                    if (kind == "call_request") {
                        mainHandler.post { onCallRequest(from) }
                    }
                }
                "error" -> mainHandler.post { onStatus(json.optString("message")) }
            }
        }

        override fun onClose(code: Int, reason: String?, remote: Boolean) {
            mainHandler.post { onStatus("انقطع الاتصال") }
        }

        override fun onError(ex: Exception?) {
            mainHandler.post { onStatus("خطأ في الاتصال") }
        }
    }
    client.connect()
    return client
}
