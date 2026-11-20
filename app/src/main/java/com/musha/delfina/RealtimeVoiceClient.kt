package com.musha.delfina

import android.content.Context
import android.media.AudioManager
import org.json.JSONObject
import org.webrtc.*
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout

/**
 * Direct Android WebRTC connection to OpenAI Realtime.
 * The permanent OpenAI API key stays on the Vercel server.
 */
internal class RealtimeVoiceClient(
    private val context: Context,
    private val backendUrl: String,
    private val accessCode: String,
    private val listener: Listener
) {
    interface Listener {
        fun onStatus(message: String)
        fun onEvent(event: JSONObject)
        fun onFailure(message: String)
    }

    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var localAudioTrack: AudioTrack? = null
    private var audioSource: AudioSource? = null
    private var channel: DataChannel? = null
    private var audioManager: AudioManager? = null
    private var previousAudioMode = AudioManager.MODE_NORMAL
    private var previousSpeaker = false

    suspend fun connect() {
        listener.onStatus("Connecting securely…")
        val token = withContext(Dispatchers.IO) { fetchEphemeralToken() }
        initializeWebRtc()
        val rtcFactory = requireNotNull(factory)
        val config = PeerConnection.RTCConfiguration(
            listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        ).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peer = rtcFactory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                if (state == PeerConnection.IceConnectionState.CONNECTED ||
                    state == PeerConnection.IceConnectionState.COMPLETED) {
                    listener.onStatus("Connected · Delfina is listening")
                } else if (state == PeerConnection.IceConnectionState.FAILED ||
                    state == PeerConnection.IceConnectionState.DISCONNECTED) {
                    listener.onStatus("Voice connection interrupted")
                }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onIceCandidate(candidate: IceCandidate) {}
            override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(dataChannel: DataChannel) { attachDataChannel(dataChannel) }
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<MediaStream>) {}
        }) ?: error("Could not create WebRTC peer connection")

        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        }
        audioSource = rtcFactory.createAudioSource(constraints)
        localAudioTrack = rtcFactory.createAudioTrack("delfina-microphone", audioSource)
        localAudioTrack?.setEnabled(true)
        peer?.addTrack(localAudioTrack, listOf("delfina-stream"))

        val init = DataChannel.Init().apply { ordered = true }
        channel = peer?.createDataChannel("oai-events", init)
        channel?.let { attachDataChannel(it) }

        val offer = createOffer()
        setLocalDescription(offer)
        awaitIceGathering()
        val localSdp = peer?.localDescription?.description ?: error("WebRTC did not produce an SDP offer")
        val answerSdp = withContext(Dispatchers.IO) { exchangeSdp(localSdp, token) }
        setRemoteDescription(SessionDescription(SessionDescription.Type.ANSWER, answerSdp))

        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        previousAudioMode = audioManager?.mode ?: AudioManager.MODE_NORMAL
        previousSpeaker = audioManager?.isSpeakerphoneOn ?: false
        audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager?.isSpeakerphoneOn = true
        listener.onStatus("Connecting audio…")
    }

    private fun initializeWebRtc() {
        if (factory != null) return
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions()
        )
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(JavaAudioDeviceModule.builder(context.applicationContext).createAudioDeviceModule())
            .createPeerConnectionFactory()
    }

    private fun attachDataChannel(dataChannel: DataChannel) {
        dataChannel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {
                if (dataChannel.state() == DataChannel.State.OPEN) {
                    listener.onStatus("Connected · speak naturally")
                }
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                if (buffer.binary) return
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }
                    .getOrNull()?.let(listener::onEvent)
            }
        })
    }

    private suspend fun createOffer(): SessionDescription =
        suspendCancellableCoroutine { continuation ->
            val constraints = MediaConstraints()
            peer?.createOffer(object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription) {
                    if (continuation.isActive) continuation.resume(description)
                }
                override fun onSetSuccess() {}
                override fun onCreateFailure(error: String) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException(error))
                }
                override fun onSetFailure(error: String) {}
            }, constraints) ?: continuation.resumeWithException(IllegalStateException("Peer connection is unavailable"))
        }

    private suspend fun setLocalDescription(description: SessionDescription) =
        suspendCancellableCoroutine<Unit> { continuation ->
            peer?.setLocalDescription(object : SdpObserver {
                override fun onSetSuccess() { if (continuation.isActive) continuation.resume(Unit) }
                override fun onSetFailure(error: String) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException(error))
                }
                override fun onCreateSuccess(description: SessionDescription) {}
                override fun onCreateFailure(error: String) {}
            }, description) ?: continuation.resumeWithException(IllegalStateException("Peer connection is unavailable"))
        }

    private suspend fun awaitIceGathering() = withTimeout(15000) {
        if (peer?.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE) return@withTimeout
        suspendCancellableCoroutine<Unit> { continuation ->
            val target = peer ?: run {
                continuation.resumeWithException(IllegalStateException("Peer connection closed"))
                return@suspendCancellableCoroutine
            }
            val observer = object : PeerConnection.Observer {
                override fun onSignalingChange(state: PeerConnection.SignalingState) {}
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                    if (state == PeerConnection.IceGatheringState.COMPLETE && continuation.isActive) {
                        continuation.resume(Unit)
                    }
                }
                override fun onIceCandidate(candidate: IceCandidate) {}
                override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {}
                override fun onAddStream(stream: MediaStream) {}
                override fun onRemoveStream(stream: MediaStream) {}
                override fun onDataChannel(dataChannel: DataChannel) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<MediaStream>) {}
            }
            // ICE gathering is also polled because the primary observer owns the peer lifecycle.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (continuation.isActive && System.nanoTime() < deadline) {
                if (target.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE) {
                    continuation.resume(Unit)
                    break
                }
                Thread.sleep(100)
            }
            if (continuation.isActive) continuation.resumeWithException(IllegalStateException("ICE gathering timed out"))
        }
    }

    private fun fetchEphemeralToken(): String {
        val endpoint = backendUrl.trimEnd('/') + "/api/realtime-token"
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 20000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $accessCode")
            setRequestProperty("Content-Type", "application/json")
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        if (code !in 200..299) {
            val message = runCatching { JSONObject(body).optString("message").ifBlank { JSONObject(body).optString("error") } }.getOrDefault(body)
            error("Credential server HTTP $code: $message")
        }
        return JSONObject(body).optString("client_secret").takeIf { it.isNotBlank() }
            ?: error("Credential server did not return a client secret")
    }

    private fun exchangeSdp(offer: String, token: String): String {
        val connection = (URL("https://api.openai.com/v1/realtime/calls").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20000
            readTimeout = 30000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/sdp")
            setRequestProperty("Accept", "application/sdp")
        }
        connection.outputStream.use { it.write(offer.toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        if (code !in 200..299) error("OpenAI Realtime HTTP $code: ${body.take(300)}")
        return body
    }

    private suspend fun setRemoteDescription(description: SessionDescription) =
        suspendCancellableCoroutine<Unit> { continuation ->
            peer?.setRemoteDescription(object : SdpObserver {
                override fun onSetSuccess() { if (continuation.isActive) continuation.resume(Unit) }
                override fun onSetFailure(error: String) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException(error))
                }
                override fun onCreateSuccess(description: SessionDescription) {}
                override fun onCreateFailure(error: String) {}
            }, description) ?: continuation.resumeWithException(IllegalStateException("Peer connection is unavailable"))
        }

    fun disconnect() {
        runCatching { channel?.close() }
        channel = null
        runCatching { localAudioTrack?.setEnabled(false); localAudioTrack?.dispose() }
        localAudioTrack = null
        runCatching { audioSource?.dispose() }
        audioSource = null
        runCatching { peer?.close(); peer?.dispose() }
        peer = null
        runCatching { factory?.dispose() }
        factory = null
        audioManager?.mode = previousAudioMode
        audioManager?.isSpeakerphoneOn = previousSpeaker
        audioManager = null
    }
}
