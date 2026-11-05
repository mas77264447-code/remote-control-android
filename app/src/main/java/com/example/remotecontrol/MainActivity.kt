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
import androidx.activity.result.ActivityResultLauncher
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.SurfaceViewRenderer
import java.net.URI

const val SERVER_URL = "wss://signaling-server-xp4s.onrender.com"

const val SRC_SCREEN = "screen"
const val SRC_FRONT = "front"
const val SRC_BACK = "back"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Session.init(this)
        TrustedDevices.init(this)
        WebRtcManager.init(this)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot()
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

private fun startSourceNow(
    context: Context,
    src: String,
    setSharing: (Boolean) -> Unit,
    setCaptureInfo: (String) -> Unit,
    projectionLauncher: ActivityResultLauncher<Intent>,
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

// الواجهة الرئيسية: تختار بين شاشة الدخول والتطبيق حسب الجلسة
@Composable
fun AppRoot() {
    var username by remember { mutableStateOf(Session.username()) }
    val link = remember { ServerLink() }

    DisposableEffect(Unit) {
        link.start()
        onDispose { link.stop() }
    }

    if (username == null) {
        LoginScreen(
            link = link,
            onLoggedIn = { name ->
                Session.save(name)
                username = name
            }
        )
    } else {
        HomeScreen(
            username = username!!,
            link = link,
            onLogout = {
                Session.clear()
                link.logout()
                username = null
            }
        )
    }
}

@Composable
fun LoginScreen(link: ServerLink, onLoggedIn: (String) -> Unit) {
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("أدخل اسم المستخدم وكلمة المرور") }

    link.onLoginResult = { ok, id, error ->
        if (ok && id != null) {
            onLoggedIn(id)
        } else {
            message = error ?: "فشل الدخول"
        }
    }
    link.onSignupResult = { ok, error ->
        message = if (ok) "تم إنشاء الحساب، اضغط دخول" else (error ?: "فشل إنشاء الحساب")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = message)
        OutlinedTextField(
            value = user,
            onValueChange = { user = it.filter { c -> c.isLetterOrDigit() || c == '_' }.take(20) },
            label = { Text("اسم المستخدم") },
            singleLine = true,
            modifier = Modifier.padding(top = 12.dp)
        )
        OutlinedTextField(
            value = pass,
            onValueChange = { pass = it },
            label = { Text("كلمة المرور") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.padding(top = 8.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(onClick = {
                if (user.length < 3 || pass.length < 6) {
                    message = "الاسم 3 أحرف على الأقل، وكلمة المرور 6 على الأقل"
                    return@Button
                }
                link.signup(user, pass)
                message = "جارٍ إنشاء الحساب..."
            }) {
                Text("إنشاء حساب")
            }
            Button(onClick = {
                if (user.isEmpty() || pass.isEmpty()) {
                    message = "أدخل الاسم وكلمة المرور"
                    return@Button
                }
                link.login(user, pass)
                message = "جارٍ الدخول..."
            }) {
                Text("دخول")
            }
        }
    }
}

@Composable
fun HomeScreen(username: String, link: ServerLink, onLogout: () -> Unit) {
    val context = LocalContext.current
    var status by remember { mutableStateOf("متصل باسم $username") }
    var sharing by remember { mutableStateOf(false) }
    var captureInfo by remember { mutableStateOf("لم يبدأ البث بعد") }
    var targetId by remember { mutableStateOf("") }
    var rtcState by remember { mutableStateOf("لم تبدأ بعد") }
    var received by remember { mutableStateOf("لا رسائل بعد") }
    var grants by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var inCall by remember { mutableStateOf(false) }
    var fullScreen by remember { mutableStateOf(false) }

    var incomingFrom by remember { mutableStateOf<String?>(null) }
    var incomingSource by remember { mutableStateOf(SRC_SCREEN) }
    var pendingSource by remember { mutableStateOf("") }

    link.onStatus = { status = it }
    link.onGrants = { grants = it }
    link.onIncoming = { from, src ->
        incomingFrom = from
        incomingSource = src
    }
    link.onCallResult = { from, accepted ->
        status = if (accepted) "وافق الجهاز $from على الاتصال" else "رفض الجهاز $from الاتصال"
        if (accepted) {
            inCall = true
            fullScreen = true
        }
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
        Text(text = "اسم المستخدم (معرّفك): $username")
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
            Text(text = "يطلب $from: ${sourceLabel(incomingSource)}")
            Button(onClick = {
                val src = incomingSource
                incomingFrom = null
                link.send(from, "call_accept")
                status = "وافقت على طلب $from"
                requestAndStart(src)
                inCall = true
                fullScreen = true
            }) {
                Text("موافقة لمرة واحدة")
            }
            Button(onClick = {
                val src = incomingSource
                link.grant(from, src)
                incomingFrom = null
                link.send(from, "call_accept")
                status = "حُفظ إذن ${sourceLabel(src)} للمستخدم $from"
                requestAndStart(src)
                inCall = true
                fullScreen = true
            }) {
                Text("موافقة دائمة لهذا المصدر")
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
            Text(text = "اختر المصدر الذي تشاركه", modifier = Modifier.padding(top = 8.dp))
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
            onValueChange = { input -> targetId = input.filter { it.isDigit() || it.isLetter() || it == '_' }.take(20) },
            label = { Text("اسم المستخدم الآخر") },
            singleLine = true,
            modifier = Modifier.padding(top = 8.dp)
        )

        Text(text = "اطلب من المستخدم الآخر:", modifier = Modifier.padding(top = 8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(onClick = { link.requestCall(targetId, SRC_FRONT) { status = it } }) {
                Text("طلب أمامية")
            }
            Button(onClick = { link.requestCall(targetId, SRC_BACK) { status = it } }) {
                Text("طلب خلفية")
            }
            Button(onClick = { link.requestCall(targetId, SRC_SCREEN) { status = it } }) {
                Text("طلب شاشة")
            }
        }

        Text(text = "الأذونات المحفوظة", modifier = Modifier.padding(top = 12.dp))
        if (grants.isEmpty()) {
            Text(text = "لا توجد أذونات محفوظة")
        } else {
            grants.forEach { (id, src) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "$id: ${sourceLabel(src)}")
                    Button(onClick = { link.revoke(id, src) }) {
                        Text("حذف")
                    }
                }
            }
            Button(onClick = { link.revokeAll() }) {
                Text("حذف الكل")
            }
        }

        Button(onClick = onLogout, modifier = Modifier.padding(top = 12.dp)) {
            Text("تسجيل الخروج")
        }
    }
}

// يدير الاتصال بالخادم: الحسابات والأذونات والإشارات
class ServerLink {
    private val main = Handler(Looper.getMainLooper())
    private var socket: WebSocketClient? = null
    private var running = false
    private var currentUser: String? = null
    private var currentPass: String? = null

    var onStatus: (String) -> Unit = {}
    var onGrants: (List<Pair<String, String>>) -> Unit = {}
    var onIncoming: (String, String) -> Unit = { _, _ -> }
    var onCallResult: (String, Boolean) -> Unit = { _, _ -> }
    var onLoginResult: (Boolean, String?, String?) -> Unit = { _, _, _ -> }
    var onSignupResult: (Boolean, String?) -> Unit = { _, _ -> }

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

    fun signup(user: String, pass: String) {
        sendJson(JSONObject().put("type", "signup").put("username", user).put("password", pass))
    }

    fun login(user: String, pass: String) {
        currentUser = user
        currentPass = pass
        if (isConnected()) {
            sendLogin()
        }
    }

    fun logout() {
        currentUser = null
        currentPass = null
    }

    fun grant(trusted: String, src: String) {
        sendJson(JSONObject().put("type", "grant").put("trusted", trusted).put("source", src))
    }

    fun revoke(trusted: String, src: String) {
        sendJson(JSONObject().put("type", "revoke").put("trusted", trusted).put("source", src))
    }

    fun revokeAll() {
        sendJson(JSONObject().put("type", "revoke_all"))
    }

    fun requestCall(targetRaw: String, src: String, onMsg: (String) -> Unit) {
        val target = targetRaw.trim()
        if (target.isEmpty()) {
            onMsg("أدخل اسم المستخدم الآخر أولاً")
            return
        }
        if (target == currentUser) {
            onMsg("لا يمكن الاتصال بنفس الحساب")
            return
        }
        if (!isConnected()) {
            onMsg("غير متصل بالخادم")
            return
        }
        sendRaw(target, JSONObject().put("kind", "call_request").put("src", src))
        onMsg("أُرسل طلب ${sourceLabel(src)} إلى $target")
    }

    fun send(to: String, kind: String) {
        sendRaw(to, JSONObject().put("kind", kind))
    }

    private fun sendLogin() {
        val u = currentUser ?: return
        val p = currentPass ?: return
        sendJson(JSONObject().put("type", "login").put("username", u).put("password", p))
    }

    private fun sendJson(obj: JSONObject) {
        val s = socket ?: return
        if (s.isOpen) s.send(obj.toString())
    }

    private fun sendRaw(to: String, data: JSONObject) {
        sendJson(JSONObject().put("type", "signal").put("to", to).put("data", data))
    }

    private fun open() {
        if (!running) return
        val s = object : WebSocketClient(URI(SERVER_URL)) {
            override fun onOpen(handshake: ServerHandshake?) {
                main.post { onStatus("متصل بالخادم") }
                if (currentUser != null) sendLogin()
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
        when (json.optString("type")) {
            "signup_result" -> {
                val ok = json.optBoolean("ok", false)
                val err = json.optString("error", null)
                main.post { onSignupResult(ok, err) }
            }
            "login_result" -> {
                val ok = json.optBoolean("ok", false)
                val id = if (ok) json.optString("id") else null
                val err = if (!ok) json.optString("error", null) else null
                val list = mutableListOf<Pair<String, String>>()
                if (ok) {
                    val arr: JSONArray = json.optJSONArray("grants") ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        val g = arr.getJSONObject(i)
                        list.add(g.getString("trusted_id") to g.getString("source"))
                    }
                }
                main.post {
                    onGrants(list)
                    onLoginResult(ok, id, err)
                }
            }
            "grants" -> {
                val arr = json.optJSONArray("grants") ?: JSONArray()
                val list = mutableListOf<Pair<String, String>>()
                for (i in 0 until arr.length()) {
                    val g = arr.getJSONObject(i)
                    list.add(g.getString("trusted_id") to g.getString("source"))
                }
                main.post { onGrants(list) }
            }
            "signal" -> {
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
            "error" -> main.post { onStatus(json.optString("message")) }
        }
    }
}
