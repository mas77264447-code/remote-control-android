package com.example.remotecontrol

import android.Manifest
import android.app.Activity
import android.content.Context
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
import kotlin.random.Random

const val SERVER_URL = "wss://signaling-server-xp4s.onrender.com"

const val SRC_SCREEN = "screen"
const val SRC_FRONT = "front"
const val SRC_BACK = "back"

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

private fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun needsNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT >= 33 && !hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)

private fun startCaptureService(context: Context, intent: Intent) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
    } else {
        context.startService(intent)
    }
}

private fun sourceLabel(src: String): String = when (src) {
    SRC_FRONT -> "كاميرا أمامية"
    SRC_BACK -> "كاميرا خلفية"
    else -> "مشاركة الشاشة"
}

@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val deviceId = remember { TrustedDevices.selfId() }
    var status by remember { mutableStateOf("جارٍ الاتصال...") }
    var sharing by remember { mutableStateOf(false) }
    var captureInfo by remember { mutableStateOf("لم يبدأ البث بعد") }
    var targetId by remember { mutableStateOf("") }
    var rtcState by remember { mutableStateOf("لم تبدأ بعد") }
    var received by remember { mutableStateOf("لا رسائل بعد") }
    var trusted by remember { mutableStateOf(TrustedDevices.all().toList().sorted()) }
    var inCall by remember { mutableStateOf(false) }
    var fullScreen by remember { mutableStateOf(false) }

    var incomingFrom by remember { mutableStateOf<String?>(null) }
    var incomingSource by remember { mutableStateOf(SRC_SCREEN) }

    var pendingSource by remember { mutableStateOf("") }

    val link = remember(deviceId) {
        ServerLink(
            deviceId = deviceId,
            onStatus = { status = it },
            onIncoming = { from, src ->
                incomingFrom = from
                incomingSource = src
            },
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

    // المشغّلات تُعرَّف أولاً لأن الدوال أدناه تستخدمها
    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            val intent = Intent(context, ScreenShareService::class.java).apply {
                putExtra(ScreenShareService.EXTRA_MODE, ScreenShareService.MODE_SCREEN)
                putExtra(ScreenShareService.EXTRA_DATA, data)
            }
            startCaptureService(context, intent)
            sharing = true
            captureInfo = "الشاشة تُلتقط للبث"
        } else {
            status = "لم يُمنح إذن التقاط الشاشة"
        }
        pendingSource = ""
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val src = pendingSource
        pendingSource = ""
        if (granted && src.isNotEmpty()) {
            startSourceNow(context, src, { sharing = it }, { captureInfo = it }, projectionLauncher, { pendingSource = it })
        } else {
            status = "لم يُمنح إذن الكاميرا"
        }
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        val src = pendingSource
        pendingSource = ""
        if (src.isNotEmpty()) {
            startSourceNow(context, src, { sharing = it }, { captureInfo = it }, projectionLauncher, { pendingSource = it })
        }
    }

    fun requestAndStart(src: String) {
        if (needsNotificationPermission(context)) {
            pendingSource = src
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        if (src != SRC_SCREEN && !hasPermission(context, Manifest.permission.CAMERA)) {
            pendingSource = src
            cameraLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        startSourceNow(context, src, { sharing = it }, { captureInfo = it }, projectionLauncher, { pendingSource = it })
    }

    if (fullScreen) {
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
        Text(text = if (sharing) "البث نشط الآن" else "البث متوقف")
        Text(text = captureInfo)
        Text(text = "حالة WebRTC: $rtcState")
        Text(text = "رسالة مستلمة: $received")

        if (inCall) {
            Button(onClick = { fullScreen = true }, modifier = Modifier.padding(top = 8.dp)) {
                Text("عرض الشاشة كاملة")
            }
        }

        incomingFrom?.let { from ->
            Text(text = "يطلب الجهاز $from: ${sourceLabel(incomingSource)}")
            Button(onClick = {
                val src = incomingSource
                incomingFrom = null
                link.send(from, "call_accept")
                status = "وافقت على طلب $from"
                requestAndStart(src)
                inCall = true
                fullScreen = true
            }) {
                Text("موافقة وبدء")
            }
            Button(onClick = {
                link.send(from, "call_reject")
                incomingFrom = null
                status = "رفضت طلب $from"
            }) {
                Text("رفض")
            }
        }

        if (!sharing) {
            Text(
                text = "اختر المصدر الذي تشاركه",
                modifier = Modifier.padding(top = 8.dp)
            )
            Button(onClick = { requestAndStart(SRC_SCREEN) }) {
                Text("مشاركة الشاشة")
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(onClick = { requestAndStart(SRC_FRONT) }) {
                    Text("كاميرا أمامية")
                }
                Button(onClick = { requestAndStart(SRC_BACK) }) {
                    Text("كاميرا خلفية")
                }
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
                Text("إيقاف البث")
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

        Text(text = "اطلب من الجهاز الآخر:", modifier = Modifier.padding(top = 8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(onClick = { requestCall(link, deviceId, targetId, SRC_FRONT) { status = it } }) {
                Text("طلب أمامية")
            }
            Button(onClick = { requestCall(link, deviceId, targetId, SRC_BACK) { status = it } }) {
                Text("طلب خلفية")
            }
            Button(onClick = { requestCall(link, deviceId, targetId, SRC_SCREEN) { status = it } }) {
                Text("طلب شاشة")
            }
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

// يبدأ المصدر على هذا الجهاز بعد أن تكون الأذونات جاهزة
private fun startSourceNow(
    context: Context,
    src: String,
    setSharing: (Boolean) -> Unit,
    setCaptureInfo: (String) -> Unit,
    projectionLauncher: androidx.activity.result.ActivityResultLauncher<Intent>,
    setPending: (String) -> Unit
) {
    if (src == SRC_SCREEN) {
        setPending(SRC_SCREEN)
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    } else {
        val front = src == SRC_FRONT
        val intent = Intent(context, ScreenShareService::class.java).apply {
            putExtra(ScreenShareService.EXTRA_MODE, ScreenShareService.MODE_CAMERA)
            putExtra(ScreenShareService.EXTRA_FRONT, front)
        }
        startCaptureService(context, intent)
        setSharing(true)
        setCaptureInfo(if (front) "الكاميرا الأمامية تعمل" else "الكاميرا الخلفية تعمل")
    }
}

fun requestCall(
    link: ServerLink,
    deviceId: String,
    targetRaw: String,
    src: String,
    onStatus: (String) -> Unit
) {
    val target = targetRaw.trim()
    if (target.isEmpty()) {
        onStatus("أدخل معرّف الجهاز الآخر أولاً")
        return
    }
    if (target == deviceId) {
        onStatus("لا يمكن الاتصال بنفس الجهاز")
        return
    }
    if (!link.isConnected()) {
        onStatus("غير متصل بالخادم")
        return
    }
    link.sendRequest(target, src)
    onStatus("أُرسل طلب ${sourceLabel(src)} إلى $target")
}

class ServerLink(
    private val deviceId: String,
    private val onStatus: (String) -> Unit,
    private val onIncoming: (String, String) -> Unit,
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

    fun sendRequest(to: String, src: String) {
        sendRaw(to, JSONObject().put("kind", "call_request").put("src", src))
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
                val src = data.optString("src", SRC_SCREEN)
                main.post { onIncoming(from, src) }
            }
            "call_accept" -> {
                WebRtcManager.startAsCaller(from)
                main.post { onCallResult(from, true) }
            }
            "call_reject" -> main.post { onCallResult(from, false) }
        }
    }
}
