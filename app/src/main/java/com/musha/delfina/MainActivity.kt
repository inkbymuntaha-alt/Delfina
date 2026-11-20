package com.musha.delfina

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private var voiceClient: RealtimeVoiceClient? = null
    private lateinit var statusView: TextView
    private lateinit var conversationView: TextView
    private lateinit var backendInput: EditText
    private lateinit var accessInput: EditText
    private lateinit var connectButton: Button
    private val prefs by lazy { getSharedPreferences("delfina", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        installCrashCapture()
        super.onCreate(savedInstanceState)
        try {
            buildSimpleScreen()
            val diagnostics = getSharedPreferences("delfina_diagnostics", MODE_PRIVATE)
            diagnostics.getString("last_crash", null)?.let { report ->
                AlertDialog.Builder(this)
                    .setTitle("Delfina startup crash report")
                    .setMessage(report.take(6000))
                    .setPositiveButton("Copy report") { _, _ ->
                        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Delfina crash", report))
                    }
                    .setNegativeButton("Close") { _, _ ->
                        diagnostics.edit().remove("last_crash").apply()
                    }
                    .show()
            }
        } catch (error: Throwable) {
            saveCrash(error)
            throw error
        }
    }

    private fun installCrashCapture() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            saveCrash(error)
            previous?.uncaughtException(thread, error)
        }
    }

    private fun saveCrash(error: Throwable) {
        runCatching {
            getSharedPreferences("delfina_diagnostics", MODE_PRIVATE).edit()
                .putString("last_crash", Log.getStackTraceString(error).take(12000))
                .commit()
        }
    }

    private fun buildSimpleScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 36, 28, 24)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val title = TextView(this).apply {
            text = "Delfina"
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
        }
        root.addView(title, matchWrap())
        statusView = TextView(this).apply {
            text = "Online voice assistant"
            textSize = 16f
            setPadding(0, 12, 0, 18)
        }
        root.addView(statusView, matchWrap())
        backendInput = EditText(this).apply {
            hint = "Vercel backend URL"
            singleLine = true
            setText(prefs.getString("realtime_backend", "https://delfina-six.vercel.app"))
        }
        root.addView(backendInput, matchWrap())
        accessInput = EditText(this).apply {
            hint = "Delfina access code"
            singleLine = true
            setText(prefs.getString("realtime_access_code", ""))
        }
        root.addView(accessInput, matchWrap())
        val save = Button(this).apply {
            text = "Save settings"
            setOnClickListener {
                prefs.edit()
                    .putString("realtime_backend", backendInput.text.toString().trim())
                    .putString("realtime_access_code", accessInput.text.toString().trim())
                    .apply()
                statusView.text = "Settings saved"
            }
        }
        root.addView(save, matchWrap())
        connectButton = Button(this).apply {
            text = "Start talking"
            setOnClickListener { toggleVoice() }
        }
        root.addView(connectButton, matchWrap())
        val conversationTitle = TextView(this).apply {
            text = "Conversation"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 22, 0, 8)
        }
        root.addView(conversationTitle, matchWrap())
        conversationView = TextView(this).apply {
            textSize = 16f
            text = "Your conversation will appear here."
        }
        val scroll = ScrollView(this).apply {
            addView(conversationView)
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun toggleVoice() {
        if (voiceClient != null) {
            disconnectVoice()
            statusView.text = "Voice session ended"
            connectButton.text = "Start talking"
            return
        }
        val backend = backendInput.text.toString().trim()
        val access = accessInput.text.toString().trim()
        if (backend.isBlank() || access.isBlank()) {
            statusView.text = "Enter your backend URL and access code first."
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 101)
            return
        }
        startVoice(backend, access)
    }

    private fun startVoice(backend: String, access: String) {
        connectButton.isEnabled = false
        statusView.text = "Connecting securely…"
        val client = RealtimeVoiceClient(this, backend, access, object : RealtimeVoiceClient.Listener {
            override fun onStatus(message: String) = runOnUiThread {
                statusView.text = message
                if (message.startsWith("Connected")) {
                    connectButton.text = "End voice session"
                    connectButton.isEnabled = true
                }
            }
            override fun onEvent(event: JSONObject) = runOnUiThread { showEvent(event) }
            override fun onFailure(message: String) = runOnUiThread {
                statusView.text = message
                disconnectVoice()
                connectButton.text = "Start talking"
                connectButton.isEnabled = true
            }
        })
        voiceClient = client
        lifecycleScope.launch {
            try {
                client.connect()
            } catch (error: Exception) {
                statusView.text = error.message ?: "Could not connect."
                disconnectVoice()
                connectButton.text = "Start talking"
                connectButton.isEnabled = true
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startVoice(backendInput.text.toString().trim(), accessInput.text.toString().trim())
        } else if (requestCode == 101) {
            statusView.text = "Microphone permission is required."
        }
    }

    private fun showEvent(event: JSONObject) {
        val type = event.optString("type")
        val line = when (type) {
            "input_audio_transcription.completed" -> "You: " + event.optString("transcript")
            "response.output_audio_transcript.done" -> "Delfina: " + event.optString("transcript")
            "response.output_text.done" -> "Delfina: " + event.optJSONObject("text")?.optString("text").orEmpty()
            else -> ""
        }
        if (line.isNotBlank()) {
            val old = conversationView.text.toString()
            conversationView.text = if (old == "Your conversation will appear here.") line else "$old\n\n$line"
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
