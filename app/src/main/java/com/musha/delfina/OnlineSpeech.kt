package com.musha.delfina

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Optional OpenAI-compatible speech synthesis. A null result means the provider
 * does not support this endpoint or the request failed; callers should use local TTS.
 */
internal object OnlineSpeech {
    fun synthesize(chatEndpoint: String, configuredSpeechEndpoint: String, apiKey: String, text: String): ByteArray? {
        val derivedEndpoint = chatEndpoint.substringBefore("?")
            .replace(Regex("/chat/completions/?$"), "/audio/speech")
        val speechEndpoint = configuredSpeechEndpoint.ifBlank { derivedEndpoint }
        if (speechEndpoint.isBlank() || (configuredSpeechEndpoint.isBlank() && derivedEndpoint == chatEndpoint.substringBefore("?"))) return null

        return try {
            val connection = (URL(speechEndpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10000
                readTimeout = 45000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
            }
            val body = JSONObject()
                .put("model", "gpt-4o-mini-tts")
                .put("voice", "shimmer")
                .put("input", text.take(3500))
                .put("instructions", "Speak in a warm, gentle, youthful feminine voice. Sound natural, friendly, expressive and emotionally attentive. Match the language and pronunciation of the input text.")
                .put("response_format", "mp3")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val bytes = if (code in 200..299) connection.inputStream.use { it.readBytes() } else null
            connection.disconnect()
            bytes?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }
}
