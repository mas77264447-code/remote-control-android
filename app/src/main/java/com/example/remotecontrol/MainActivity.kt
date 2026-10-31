package com.example.remotecontrol

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
    val deviceId = remember { Random.nextInt(100000, 999999).toString() }
    var status by remember { mutableStateOf("جارٍ الاتصال...") }

    remember(deviceId) {
        connectToServer(deviceId) { newStatus -> status = newStatus }
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
        Button(onClick = { /* لاحقاً: بدء المشاركة */ }) {
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
