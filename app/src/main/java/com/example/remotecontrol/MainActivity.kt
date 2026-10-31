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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
        TrustedDevices.init(this)
        WebRtcManager.init(this)
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
    var incomingFrom by remember { mutableStateOf<String?>(null) }
    var trusted by remember { mutableStateOf(TrustedDevices.all().toList().sorted()) }
    var targetId by remember { mutableStateOf("") }
    var rtcState by remember { mutableStateOf("لم تبدأ بعد") }
    var received by remember { mutableStateOf("لا رسائل بعد") }

    val client = remember(deviceId) {
        connectToServer(
            deviceId = deviceId,
            onStatus = { status = it },
            onIncoming = { from -> incomingFrom = from },
            onCallResult = { from, accepted ->
                status = if (accepted) "وافق الجهاز $from على الاتصال" else "رفض الجهاز $from الاتصال"
            }
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
        WebRtcManager.onState = { rtcState = it }
        WebRtcManager.onText = { received = it }
        onDispose {
            ScreenShareService.onFrameCaptured = null
            ScreenShareService.onStopped = null
            WebRtcManager.onState = null
            WebRtcManager.onText = null
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
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "معرّف هذا الجهاز: $deviceId")
        Text(text = status)
        Text(text = if (sharing) "الشاشة مُشارَكة الآن" else "الشاشة غير مُشارَكة")
        Text(text = captureInfo)
        Text(text = "حالة WebRTC: $rtcState")
        Text(text = "رسالة مستلمة: $received")

        incomingFrom?.let { from ->
            Text(text = "طلب اتصال من الجهاز $from")
            Button(onClick = {
                sendSignal(client, from, "call_accept")
                incomingFrom = null
                status = "قبلت الاتصال من $from"
            }) {
                Text("قبول مرة واحدة")
            }
            Button(onClick = {
                TrustedDevices.add(from)
                trusted = TrustedDevices.all().toList().sorted()
                sendSignal(client, from, "call_accept")
                incomingFrom = null
                status = "أُضيف $from إلى الأجهزة الموثوقة"
            }) {
                Text("قبول دائم")
            }
            Button(onClick = {
                sendSignal(client, from, "call_reject")
                incomingFrom = null
                status = "رفضت الاتصال من $from"
            }) {
                Text("رفض")
            }
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
            sendSignal(client, target, "call_request")
            status = "أُرسل طلب اتصال إلى $target"
        }) {
            Text("الاتصال بجهاز")
        }

        Button(onClick = {
            if (!WebRtcManager.sendText("مرحباً من $deviceId")) {
                rtcState = "القناة غير مفتوحة بعد"
            }
        }) {
            Text("إرسال رسالة تجريبية")
        }

        if (trusted.isNotEmpty()) {
            Text(text = "الأجهزة الموثوقة:", modifier = Modifier.padding(top = 16.dp))
            trusted.forEach { id ->
                Button(onClick = {
                    TrustedDevices.remove(id)
                    trusted = TrustedDevices.all().toList().sorted()
                }) {
                    Text("حذف $id")
                }
            }
        }
    }
}

fun sendRaw(client: WebSocketClient, to: String, data: JSONObject) {
    if (!client.isOpen) return
    val payload = JSONObject()
        .put("type", "signal")
        .put("to", to)
        .put("data", data)
    client.send(payload.toString())
}

fun sendSignal(client: WebSocketClient, to: String, kind: String) {
    sendRaw(client, to, JSONObject().put("kind", kind))
}

fun connectToServer(
    deviceId: String,
    onStatus: (String) -> Unit,
    onIncoming: (String) -> Unit,
    onCallResult: (String, Boolean) -> Unit
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
                    val data = json.optJSONObject("data") ?: return
                    when (data.optString("kind")) {
                        "sdp", "ice" -> WebRtcManager.handleSignal(from, data)
                        "call_request" -> {
                            if (TrustedDevices.contains(from)) {
                                sendSignal(this, from, "call_accept")
                                mainHandler.post { onStatus("قُبل تلقائياً طلب الجهاز الموثوق $from") }
                            } else {
                                mainHandler.post { onIncoming(from) }
                            }
                        }
                        "call_accept" -> {
                            WebRtcManager.startAsCaller(from)
                            mainHandler.post { onCallResult(from, true) }
                        }
                        "call_reject" -> mainHandler.post { onCallResult(from, false) }
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
    WebRtcManager.sender = { to, data -> sendRaw(client, to, data) }
    return client
}
