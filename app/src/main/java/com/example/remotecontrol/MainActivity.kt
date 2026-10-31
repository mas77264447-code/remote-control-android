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

    val client
