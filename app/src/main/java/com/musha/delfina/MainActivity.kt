package com.musha.delfina

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var tts: TextToSpeech? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault()
        }
        setContent { MaterialTheme { DelfinaScreen(onSpeak = { speak(it) }) } }
    }

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "delfina_reply")
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}

private data class ChatMessage(val text: String, val fromUser: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DelfinaScreen(onSpeak: (String) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("delfina", ComponentActivity.MODE_PRIVATE) }
    var endpoint by remember { mutableStateOf(prefs.getString("endpoint", "") ?: "") }
    var model by remember { mutableStateOf(prefs.getString("model", "gpt-4o-mini") ?: "gpt-4o-mini") }
    var apiKey by remember { mutableStateOf(prefs.getString("api_key", "") ?: "") }
    var showSettings by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Offline-ready · online mode is optional") }
    val messages = remember { mutableStateListOf(ChatMessage("Hi, I'm Delfina. You can talk or type to me. I'll use the online brain when configured, and switch to simple offline replies when it isn't available.", false)) }
    val scope = rememberCoroutineScope()

    val speechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spoken.isNullOrBlank()) input = spoken
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchSpeech(speechLauncher, context)
        else status = "Microphone permission was not granted."
    }

    fun sendMessage() {
        val prompt = input.trim()
        if (prompt.isEmpty() || busy) return
        messages.add(ChatMessage(prompt, true))
        input = ""
        busy = true
        scope.launch {
            val onlineReply = if (endpoint.isNotBlank() && apiKey.isNotBlank()) {
                withContext(Dispatchers.IO) { requestOnline(endpoint, model, apiKey, messages.dropLast(1).takeLast(12), prompt) }
            } else null
            val reply = onlineReply ?: offlineReply(prompt)
            messages.add(ChatMessage(reply, false))
            status = if (onlineReply != null) "Online · connected" else "Offline mode · local fallback"
            onSpeak(reply)
            busy = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Column { Text("Delfina", fontWeight = FontWeight.Bold); Text(status, style = MaterialTheme.typography.labelSmall) } },
                actions = { TextButton(onClick = { showSettings = true }) { Text("Settings") } }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            launchSpeech(speechLauncher, context)
                        } else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }) { Icon(Icons.Default.Mic, contentDescription = "Speak") }
                    OutlinedTextField(
                        value = input, onValueChange = { input = it },
                        modifier = Modifier.weight(1f), placeholder = { Text("Message Delfina…") },
                        maxLines = 4
                    )
                    IconButton(onClick = { sendMessage() }, enabled = !busy && input.isNotBlank()) {
                        Icon(Icons.Default.Send, contentDescription = "Send")
                    }
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background)) {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(messages) { message ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.fromUser) Arrangement.End else Arrangement.Start) {
                        Surface(
                            color = if (message.fromUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier.widthIn(max = 320.dp)
                        ) {
                            Text(message.text, Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                }
                if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }
        }
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("Online brain settings") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Optional OpenAI-compatible chat-completions API. Leave blank to use offline fallback.")
                    OutlinedTextField(endpoint, { endpoint = it }, label = { Text("API endpoint URL") }, singleLine = true)
                    OutlinedTextField(model, { model = it }, label = { Text("Model") }, singleLine = true)
                    OutlinedTextField(apiKey, { apiKey = it }, label = { Text("API key") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    prefs.edit().putString("endpoint", endpoint.trim()).putString("model", model.trim())
                        .putString("api_key", apiKey.trim()).apply()
                    showSettings = false
                    status = if (endpoint.isNotBlank() && apiKey.isNotBlank()) "Online configured · fallback enabled" else "Offline-ready · online mode is optional"
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showSettings = false }) { Text("Cancel") } }
        )
    }
}

private fun launchSpeech(
    launcher: androidx.activity.result.ActivityResultLauncher<Intent>,
    context: android.content.Context
) {
    if (!SpeechRecognizer.isRecognitionAvailable(context)) return
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    }
    launcher.launch(intent)
}

private fun requestOnline(
    endpoint: String,
    model: String,
    key: String,
    history: List<ChatMessage>,
    prompt: String
): String? {
    return try {
        val url = URL(endpoint)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10000
            readTimeout = 20000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $key")
        }
        val allMessages = JSONArray()
        allMessages.put(JSONObject().put("role", "system").put("content",
            "You are Delfina, a warm, perceptive phone companion. Speak naturally and concisely. Mirror the user's language. Never claim to be human or sentient."))
        history.forEach { allMessages.put(JSONObject().put("role", if (it.fromUser) "user" else "assistant").put("content", it.text)) }
        allMessages.put(JSONObject().put("role", "user").put("content", prompt))
        val body = JSONObject().put("model", model).put("messages", allMessages).put("temperature", 0.7)
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val response = stream.bufferedReader().use { it.readText() }
        connection.disconnect()
        if (code !in 200..299) null else JSONObject(response).getJSONArray("choices")
            .getJSONObject(0).getJSONObject("message").getString("content").trim().takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }
}

private fun offlineReply(text: String): String {
    val q = text.lowercase(Locale.ROOT)
    return when {
        q.matches(Regex(".*\\b(hi|hello|hey|namaste|नमस्ते)\\b.*")) -> "Hey! I'm here with you. What's on your mind?"
        q.contains("time") -> "I can help with that, but clock access is coming in a later update."
        q.contains("your name") || q.contains("who are you") -> "I'm Delfina, your on-device voice companion."
        q.contains("thank") -> "You're very welcome. I'm glad I could help."
        q.contains("bye") -> "See you soon. I'll be right here when you need me."
        else -> "I'm offline right now, so my full language model isn't available yet. I can still handle simple greetings and basic commands. Connect an online endpoint in Settings for richer replies."
    }
}
