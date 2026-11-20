package com.musha.delfina

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import java.io.File
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
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
    private var ttsReady = false
    private var activePlayer: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.setSpeechRate(0.94f)
                tts?.setPitch(1.04f)
                tts?.language = Locale.forLanguageTag("en-IN")
            }
        }
        setContent { MaterialTheme { DelfinaScreen(onSpeak = { text, endpoint, key -> speak(text, endpoint, key) }) } }
    }

    private fun speak(text: String, chatEndpoint: String, apiKey: String) {
        if (text.isBlank()) return
        lifecycleScope.launch {
            val audio = if (chatEndpoint.isNotBlank() && apiKey.isNotBlank()) {
                withContext(Dispatchers.IO) { OnlineSpeech.synthesize(chatEndpoint, apiKey, text) }
            } else null
            if (audio != null) {
                tts?.stop()
                activePlayer?.release()
                val audioFile = File(cacheDir, "delfina_reply.mp3")
                audioFile.writeBytes(audio)
                val player = MediaPlayer()
                activePlayer = player
                player.setDataSource(audioFile.absolutePath)
                player.setOnPreparedListener { it.start() }
                player.setOnCompletionListener { it.release(); if (activePlayer === it) activePlayer = null }
                player.setOnErrorListener { mp, _, _ ->
                    mp.release()
                    if (activePlayer === mp) activePlayer = null
                    speakLocal(text)
                    true
                }
                player.prepareAsync()
            } else {
                speakLocal(text)
            }
        }
    }

    private fun speakLocal(text: String) {
        val engine = tts ?: return
        if (!ttsReady || text.isBlank()) return
        val locale = localeForText(text)
        val matching = engine.voices.orEmpty().filter { it.locale.language == locale.language }
        val selected = matching.sortedWith(
            compareByDescending<Voice> { !it.isNetworkConnectionRequired }
                .thenByDescending { it.quality }
                .thenByDescending { it.name.contains("female", ignoreCase = true) ||
                    it.name.contains("woman", ignoreCase = true) }
        ).firstOrNull()
        if (selected != null) engine.voice = selected else engine.setLanguage(locale)
        engine.setSpeechRate(0.94f)
        engine.setPitch(1.04f)
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "delfina_reply")
    }

    override fun onDestroy() {
        activePlayer?.release()
        activePlayer = null
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}

/** Infer a speech locale from the dominant writing system in the reply. */
private fun localeForText(text: String): Locale {
    val letters = text.codePoints().toArray().filter { Character.isLetter(it) }
    if (letters.isEmpty()) return Locale.forLanguageTag("en-IN")
    val block = letters.groupingBy { Character.UnicodeBlock.of(it) }
        .eachCount().maxByOrNull { it.value }?.key
    val tag = when (block) {
        Character.UnicodeBlock.DEVANAGARI -> "hi-IN"
        Character.UnicodeBlock.ARABIC -> "ur-PK"
        Character.UnicodeBlock.BENGALI -> "bn-IN"
        Character.UnicodeBlock.TAMIL -> "ta-IN"
        Character.UnicodeBlock.TELUGU -> "te-IN"
        Character.UnicodeBlock.KANNADA -> "kn-IN"
        Character.UnicodeBlock.MALAYALAM -> "ml-IN"
        Character.UnicodeBlock.GUJARATI -> "gu-IN"
        Character.UnicodeBlock.GURMUKHI -> "pa-IN"
        Character.UnicodeBlock.THAI -> "th-TH"
        Character.UnicodeBlock.HANGUL_SYLLABLES, Character.UnicodeBlock.HANGUL_JAMO -> "ko-KR"
        Character.UnicodeBlock.HIRAGANA, Character.UnicodeBlock.KATAKANA -> "ja-JP"
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS -> "zh-CN"
        Character.UnicodeBlock.CYRILLIC -> "ru-RU"
        else -> "en-IN"
    }
    return Locale.forLanguageTag(tag)
}

private data class ChatMessage(val text: String, val fromUser: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DelfinaScreen(onSpeak: (String, String, String) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("delfina", android.content.Context.MODE_PRIVATE) }
    var endpoint by remember { mutableStateOf(prefs.getString("endpoint", "") ?: "") }
    var model by remember { mutableStateOf(prefs.getString("model", "gpt-4o-mini") ?: "gpt-4o-mini") }
    var apiKey by remember { mutableStateOf(prefs.getString("api_key", "") ?: "") }
    var showSettings by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Voice follows reply language · offline voice when installed") }
    val messages = remember { mutableStateListOf(ChatMessage("Hi, I'm Delfina. I'll speak in the language I reply in. For the most natural voice, install high-quality voices for your languages in your phone's Text-to-speech settings.", false)) }
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
            status = if (onlineReply != null) "Online AI · language-matched voice" else "Offline fallback · language-matched system voice"
            onSpeak(reply, endpoint.trim(), apiKey.trim())
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
                    Text("Chat endpoint powers online answers. If it supports OpenAI audio/speech, Delfina also tries a natural neural voice (Shimmer); otherwise she automatically uses your phone voice offline.")
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
            "You are Delfina, a warm, young-sounding, emotionally perceptive assistant. Write like a natural conversational companion, with short spoken sentences, gentle warmth, varied rhythm, and the same language and script the user uses. Match the user's language and code-switch naturally. Avoid robotic introductions and repetitive greetings. Never claim to be human or sentient."))
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
