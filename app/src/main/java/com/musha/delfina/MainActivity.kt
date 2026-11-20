package com.musha.delfina

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private var voiceClient: RealtimeVoiceClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                DelfinaScreen(
                    onConnect = { backend, code, status, event, failure ->
                        connectVoice(backend, code, status, event, failure)
                    },
                    onDisconnect = { disconnectVoice() }
                )
            }
        }
    }

    private fun connectVoice(
        backend: String,
        code: String,
        onStatus: (String) -> Unit,
        onEvent: (JSONObject) -> Unit,
        onFailure: (String) -> Unit
    ) {
        if (voiceClient != null) return
        val client = RealtimeVoiceClient(this, backend, code, object : RealtimeVoiceClient.Listener {
            override fun onStatus(message: String) = runOnUiThread { onStatus(message) }
            override fun onEvent(event: JSONObject) = runOnUiThread { onEvent(event) }
            override fun onFailure(message: String) = runOnUiThread { onFailure(message) }
        })
        voiceClient = client
        lifecycleScope.launch {
            try {
                client.connect()
            } catch (e: Exception) {
                runOnUiThread {
                    onFailure(e.message ?: "Could not connect to Realtime.")
                    disconnectVoice()
                }
            }
        }
    }

    private fun disconnectVoice() {
        voiceClient?.disconnect()
        voiceClient = null
    }

    override fun onDestroy() {
        disconnectVoice()
        super.onDestroy()
    }
}

private data class VoiceLine(val text: String, val fromUser: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DelfinaScreen(
    onConnect: (String, String, (String) -> Unit, (JSONObject) -> Unit, (String) -> Unit) -> Unit,
    onDisconnect: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("delfina", android.content.Context.MODE_PRIVATE) }
    var backend by remember { mutableStateOf(prefs.getString("realtime_backend", "https://delfina-9sjk5h6cx-inkbymuntaha.vercel.app") ?: "") }
    var accessCode by remember { mutableStateOf(prefs.getString("realtime_access_code", "") ?: "") }
    var showSettings by remember { mutableStateOf(false) }
    var connected by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Online voice · tap the microphone to connect") }
    val lines = remember { mutableStateListOf<VoiceLine>() }

    fun handleEvent(event: JSONObject) {
        val type = event.optString("type")
        val text = when (type) {
            "input_audio_transcription.completed" -> event.optString("transcript")
            "response.output_audio_transcript.done" -> event.optString("transcript")
            "response.output_text.done" -> event.optJSONObject("text")?.optString("text").orEmpty()
            else -> ""
        }.trim()
        if (text.isNotBlank()) {
            val fromUser = type == "input_audio_transcription.completed"
            lines.add(VoiceLine(text, fromUser))
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            busy = true
            status = "Starting secure voice session…"
            onConnect(
                backend.trim(), accessCode.trim(),
                { message -> status = message; if (message.startsWith("Connected")) { connected = true; busy = false } },
                { event -> handleEvent(event) },
                { message -> status = message; connected = false; busy = false }
            )
        } else status = "Microphone permission is required for voice chat."
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Delfina", fontWeight = FontWeight.Bold)
                        Text(status, style = MaterialTheme.typography.labelSmall, maxLines = 2)
                    }
                },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(24.dp))
            Surface(
                modifier = Modifier.size(190.dp),
                shape = CircleShape,
                color = if (connected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(88.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(if (connected) "I'm listening" else "Talk to Delfina", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text("Live online voice · natural language matching", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = {
                    if (connected || busy) {
                        onDisconnect()
                        connected = false
                        busy = false
                        status = "Voice session ended"
                    } else if (backend.isBlank() || accessCode.isBlank()) {
                        showSettings = true
                    } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        busy = true
                        status = "Starting secure voice session…"
                        onConnect(
                            backend.trim(), accessCode.trim(),
                            { message -> status = message; if (message.startsWith("Connected")) { connected = true; busy = false } },
                            { event -> handleEvent(event) },
                            { message -> status = message; connected = false; busy = false }
                        )
                    } else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                },
                enabled = !busy || connected,
                modifier = Modifier.fillMaxWidth().height(54.dp)
            ) {
                if (connected || busy) Icon(Icons.Default.Stop, contentDescription = null)
                else Icon(Icons.Default.Mic, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (connected || busy) "End voice session" else "Start talking")
            }
            if (busy) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(20.dp))
            Text("Conversation", style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.Start))
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(lines) { line ->
                    Surface(
                        color = if (line.fromUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(if (line.fromUser) "You" else "Delfina", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            Text(line.text)
                        }
                    }
                }
            }
        }
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("Online voice settings") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Use your deployed Vercel backend URL and the private access code you created in Vercel. Your OpenAI API key stays on the server.")
                    OutlinedTextField(backend, { backend = it }, label = { Text("Vercel backend URL") }, singleLine = true)
                    OutlinedTextField(
                        accessCode, { accessCode = it },
                        label = { Text("Delfina access code") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    prefs.edit()
                        .putString("realtime_backend", backend.trim())
                        .putString("realtime_access_code", accessCode.trim())
                        .apply()
                    showSettings = false
                    status = "Settings saved · ready to connect"
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showSettings = false }) { Text("Cancel") } }
        )
    }
}
