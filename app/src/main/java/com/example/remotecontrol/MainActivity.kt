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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import org.webrtc.SurfaceViewRenderer
import java.net.URI

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
    val deviceId = remember { TrustedDevices.selfId() }
    var status by remember { mutableStateOf("جارٍ الاتصال...") }
    var sharing by remember { mutableStateOf(false) }
    var captureInfo by remember { mutableStateOf("الشاشة غير مُشارَكة") }
    var incomingFrom by remember { mutableStateOf<String?>(null) }
    var trusted by remember { mutableStateOf(TrustedDevices.all().toList().sorted()) }
    var targetId by remember { mutableStateOf("") }
    var rtcState by remember { mutableStateOf("لم تبدأ بعد") }
    var received by remember { mutableStateOf("لا رسائل بعد") }
    var inCall by remember { mutableStateOf(false) }
    var fullScreen by remember { mutableStateOf(false) }

    val link = remember(deviceId) {
        ServerLink(
            deviceId = deviceId,
            onStatus = { status = it },
            onIncoming = { from -> incomingFrom = from },
            onCallResult = { from, accepted ->
                status = if (accepted) "وافق الجهاز $from على الاتصال" else "رفض الجهاز $from الاتصال"
                if (accepted) {
                    inCall = true
                    fullScreen = true
                }
            }
        )
    }

    DisposableEffect(link) {
        link.start()
        onDispose { link.stop() }
    }

    DisposableEffect(Unit) {
        WebRtcManager.onState = { rtcState = it }
        WebRtcManager.onText = { received = it }
        WebRtcManager.onScreenStopped = {
            sharing = false
            captureInfo = "توقفت المشاركة"
        }
        onDispose {
            WebRtcManager.onState = null
            WebRtcManager.onText = null
            WebRtcManager.onScreenStopped = null
        }
    }

    val renderer = remember { SurfaceViewRenderer(context) }
    DisposableEffect(renderer) {
        WebRtcManager.attachRenderer(renderer)
        onDispose { WebRtcManager.detachRenderer(renderer) }
    }

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            val intent = Intent(context, ScreenShareService::class.java).apply {
                putExtra(ScreenShareService.EXTRA_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            sharing = true
            captureInfo = "الشاشة تُلتقط للبث"
        }
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    if (fullScreen) {
        // وضع ملء الشاشة: النافذة تُعرض هنا فقط، ولا تظهر في الواجهة الرئيسية
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(8.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Button(onClick = { fullScreen = false }) {
                    Text("رجوع")
                }
                Button(onClick = {
                    WebRtcManager.close()
                    inCall = false
                    fullScreen = false
                    status = "أُنهي الاتصال"
                }) {
                    Text("إنهاء الاتصال")
                }
            }
            AndroidView(
                factory = { renderer },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
            Text(text = "حالة WebRTC: $rtcState", color = Color.White)
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "معرّف هذا الجهاز: $deviceId")
        Text(text = status)
        Text(text = if (sharing) "الشاشة مُشارَكة الآن" else "الشاشة غير مُشارَكة")
        Text(text = captureInfo)
        Text(text = "حالة WebRTC: $rtcState")
        Text(text = "رسالة مستلمة: $received")

        if (inCall) {
            Button(onClick = { fullScreen = true }, modifier = Modifier.padding(top = 8.dp)) {
                Text("عرض الشاشة كاملة")
            }
        }

        incomingFrom?.let { from ->
            Text(text = "طلب اتصال من الجهاز $from")
            Button(onClick = {
                link.send(from, "call_accept")
                incomingFrom = null
                status = "قبلت الاتصال من $from"
                inCall = true
                fullScreen = true
            }) {
                Text("قبول مرة واحدة")
            }
            Button(onClick = {
                TrustedDevices.add(from)
                trusted = TrustedDevices.all().toList().sorted()
                link.send(from, "call_accept")
                incomingFrom = null
                status = "أُضيف $from إلى الأجهزة الموثوقة"
                inCall = true
                fullScreen = true
            }) {
                Text("قبول دائم")
            }
            Button(onClick = {
                link.send(from, "call_reject")
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
            modifier = Modifier.padding(top = 8.dp)
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
            if (!link.isConnected()) {
                status = "غير متصل بالخادم"
                return@Button
            }
            link.send(target, "call_request")
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
            Text(text = "الأجهزة الموثوقة:", modifier = Modifier.padding(top = 8.dp))
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

class ServerLink(
    private val deviceId: String,
    private val onStatus: (String) -> Unit,
    private val onIncoming: (String) -> Unit,
    private val onCallResult: (String, Boolean) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private var socket: WebSocketClient? = null
    private var running = false

    fun start() {
        running = true
        open()
        WebRtcManager.sender = { to, data -> sendRaw(to, data) }
    }

    fun stop() {
        running = false
        socket?.close()
        socket = null
    }

    fun isConnected(): Boolean = socket?.isOpen == true

    fun send(to: String, kind: String) {
        sendRaw(to, JSONObject().put("kind", kind))
    }

    private fun sendRaw(to: String, data: JSONObject) {
        val s = socket ?: return
        if (!s.isOpen) return
        val payload = JSONObject()
            .put("type", "signal")
            .put("to", to)
            .put("data", data)
        s.send(payload.toString())
    }

    private fun open() {
        if (!running) return
        val s = object : WebSocketClient(URI(SERVER_URL)) {
            override fun onOpen(handshake: ServerHandshake?) {
                send(JSONObject().put("type", "register").put("id", deviceId).toString())
                main.post { onStatus("متصل بالخادم") }
            }

            override fun onMessage(message: String?) {
                handleMessage(message)
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                main.post { onStatus("انقطع الاتصال، جارٍ إعادة المحاولة...") }
                scheduleReconnect()
            }

            override fun onError(ex: Exception?) {
                main.post { onStatus("خطأ في الاتصال") }
            }
        }
        socket = s
        try {
            s.connect()
        } catch (e: Exception) {
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (!running) return
        main.postDelayed({ open() }, 3000)
    }

    private fun handleMessage(message: String?) {
        if (message == null) return
        val json = try {
            JSONObject(message)
        } catch (e: Exception) {
            return
        }
        if (json.optString("type") != "signal") return
        val from = json.optString("from")
        val data = json.optJSONObject("data") ?: return
        when (data.optString("kind")) {
            "sdp", "ice" -> WebRtcManager.handleSignal(from, data)
            "call_request" -> {
                if (TrustedDevices.contains(from)) {
                    send(from, "call_accept")
                    main.post { onStatus("قُبل تلقائياً طلب الجهاز الموثوق $from") }
                } else {
                    main.post { onIncoming(from) }
                }
            }
            "call_accept" -> {
                WebRtcManager.startAsCaller(from)
                main.post { onCallResult(from, true) }
            }
            "call_reject" -> main.post { onCallResult(from, false) }
        }
    }
}
