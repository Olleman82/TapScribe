package se.olle.rostbubbla.openai

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

class OpenAIRealtimeClient {
    private var webSocket: WebSocket? = null
    private var client: OkHttpClient? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var hasUncommittedAudio: Boolean = false
    @Volatile private var rateLimitedUntilMs: Long = 0
    @Volatile private var lastRateLogMs: Long = 0
    @Volatile private var allowSendingAudio: Boolean = true
    @Volatile private var txLastLogMs: Long = 0
    @Volatile private var txBytesSinceLastLog: Long = 0
    @Volatile private var sessionReady: Boolean = false
    @Volatile private var serverVadEnabled: Boolean = true
    @Volatile private var pendingStart: Boolean = false
    
    // Event flows
    private val _partialTranscript = MutableSharedFlow<String>()
    val partialTranscript: SharedFlow<String> = _partialTranscript.asSharedFlow()
    
    private val _finalTranscript = MutableSharedFlow<String>(replay = 1)
    val finalTranscript: SharedFlow<String> = _finalTranscript.asSharedFlow()
    
    private val _error = MutableSharedFlow<String>()
    val error: SharedFlow<String> = _error.asSharedFlow()
    
    private val _audioLevel = MutableSharedFlow<Float>()
    val audioLevel: SharedFlow<Float> = _audioLevel.asSharedFlow()
    
    private val _connectionStatus = MutableSharedFlow<ConnectionStatus>()
    val connectionStatus: SharedFlow<ConnectionStatus> = _connectionStatus.asSharedFlow()
    
    private var isConnected = false
    private var isRecording = false
    private val sampleRate = 24000
    private var totalSamplesAppended: Long = 0
    
    enum class ConnectionStatus {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR
    }
    
    fun connect(apiKey: String, model: String = "gpt-4o-transcribe") {
        if (isConnected) {
            Log.w("OpenAIRealtimeClient", "Already connected")
            return
        }
        
        scope.launch {
            try {
                _connectionStatus.emit(ConnectionStatus.CONNECTING)
                
                client = OkHttpClient.Builder()
                    .readTimeout(0, TimeUnit.MILLISECONDS)
                    .build()
                
                val wsUrl =
                    "wss://api.openai.com/v1/realtime?" +
                    "intent=transcription" +
                    "&input_audio_transcription.model=" + model +
                    "&input_audio_format=pcm16" +
                    "&input_audio_rate=24000"
                Log.d("OpenAIRealtimeClient", "Connecting to: $wsUrl")
                val request = Request.Builder()
                    .url(wsUrl)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("OpenAI-Beta", "realtime=v1")
                    .build()
                
                webSocket = client?.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        Log.d("OpenAIRealtimeClient", "WebSocket connected")
                        isConnected = true
                        scope.launch {
                            _connectionStatus.emit(ConnectionStatus.CONNECTED)
                        }
                        // Transkriptionssession: vänta på transcription_session.created innan update
                        sessionReady = false
                    }
                    
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        Log.d("OpenAIRealtimeClient", "Received: $text")
                        handleMessage(text)
                    }
                    
                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        Log.d("OpenAIRealtimeClient", "Received binary message")
                    }
                    
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        Log.d("OpenAIRealtimeClient", "WebSocket closing: $code $reason")
                        isConnected = false
                        scope.launch {
                            _connectionStatus.emit(ConnectionStatus.DISCONNECTED)
                        }
                    }
                    
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        Log.d("OpenAIRealtimeClient", "WebSocket closed: $code $reason")
                        isConnected = false
                        scope.launch {
                            _connectionStatus.emit(ConnectionStatus.DISCONNECTED)
                        }
                    }
                    
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        Log.e("OpenAIRealtimeClient", "WebSocket error", t)
                        isConnected = false
                        scope.launch {
                            _connectionStatus.emit(ConnectionStatus.ERROR)
                            _error.emit("Connection failed: ${t.message}")
                        }
                    }
                })
                
            } catch (e: Exception) {
                Log.e("OpenAIRealtimeClient", "Failed to connect", e)
                scope.launch {
                    _connectionStatus.emit(ConnectionStatus.ERROR)
                    _error.emit("Failed to connect: ${e.message}")
                }
            }
        }
    }
    
    private fun sendSessionConfig() { /* not used for transcription sessions */ }
    
    private fun handleMessage(message: String) {
        try {
            val json = JSONObject(message)
            val type = json.optString("type")
            
            Log.d("OpenAIRealtimeClient", "Handling message type: $type")
            
            when (type) {
                "session.created" -> {
                    Log.d("OpenAIRealtimeClient", "Session created successfully")
                }
                "session.updated" -> {
                    Log.d("OpenAIRealtimeClient", "Session updated successfully")
                }
                "transcription_session.created" -> {
                    Log.d("OpenAIRealtimeClient", "Transcription session created; sending update for model/lang/VAD")
                    try {
                        val update = JSONObject().apply {
                            put("type", "transcription_session.update")
                            put("session", JSONObject().apply {
                                put("input_audio_transcription", JSONObject().apply {
                                    put("model", "gpt-4o-transcribe")
                                    put("language", "sv")
                                })
                                // Keep server VAD but make it effectively "never auto-stop"
                                // by using very conservative params; we'll commit on STOP.
                                put("turn_detection", JSONObject().apply {
                                    put("type", "server_vad")
                                    put("threshold", 0.9) // hög tröskel för att undvika falska stopp
                                    put("prefix_padding_ms", 300)
                                    put("silence_duration_ms", 10000) // max tillåten enligt API
                                })
                                put("input_audio_format", "pcm16")
                            })
                        }
                        webSocket?.send(update.toString())
                        Log.d("OpenAIRealtimeClient", "Sent transcription_session.update")
                    } catch (e: Exception) {
                        Log.e("OpenAIRealtimeClient", "Failed to send transcription_session.update", e)
                    }
                }
                "transcription_session.updated" -> {
                    Log.d("OpenAIRealtimeClient", "Transcription session updated")
                    sessionReady = true
                    if (pendingStart && !isRecording) {
                        pendingStart = false
                        Log.d("OpenAIRealtimeClient", "Deferred start – starting recording now")
                        startRecording()
                    }
                }
                "input_audio_buffer.committed" -> {
                    Log.d("OpenAIRealtimeClient", "Audio buffer committed, waiting for transcription")
                    hasUncommittedAudio = false
                    allowSendingAudio = false
                }
                "conversation.item.added" -> {
                    Log.d("OpenAIRealtimeClient", "Conversation item added")
                }
                "conversation.item.done" -> {
                    Log.d("OpenAIRealtimeClient", "Conversation item done")
                }
                "conversation.updated" -> {
                    val delta = json.optJSONObject("delta")
                    if (delta != null) {
                        val transcript = delta.optString("transcript", "")
                        if (transcript.isNotEmpty()) {
                            Log.d("OpenAIRealtimeClient", "Partial transcript: $transcript")
                            scope.launch { _partialTranscript.emit(transcript) }
                        }
                    }
                }
                "conversation.item.input_audio_transcription.delta" -> {
                    val d = json.optString("text", json.optString("transcript", ""))
                    if (d.isNotEmpty()) {
                        Log.d("OpenAIRealtimeClient", "Transcription delta: $d")
                        scope.launch { _partialTranscript.emit(d) }
                    }
                }
                "conversation.item.input_audio_transcription.completed" -> {
                    val text = json.optString("text", json.optString("transcript", ""))
                    if (text.isNotEmpty()) {
                        Log.d("OpenAIRealtimeClient", "Final transcript: $text")
                        scope.launch { _finalTranscript.emit(text) }
                    }
                    allowSendingAudio = true
                }
                "conversation.item.input_audio_transcription.failed" -> {
                    val errObj = json.optJSONObject("error")
                    val errMsg = errObj?.optString("message") ?: "Transcription failed"
                    Log.e("OpenAIRealtimeClient", "Transcription failed: $errMsg")
                    if (errMsg.contains("429")) {
                        val now = System.currentTimeMillis()
                        rateLimitedUntilMs = now + 4000
                        if (now - lastRateLogMs > 1000) {
                            Log.w("OpenAIRealtimeClient", "Rate limited. Backing off audio sends for 4s")
                            lastRateLogMs = now
                        }
                    }
                    scope.launch { _error.emit("Transcription failed: $errMsg") }
                    allowSendingAudio = true
                }
                "error" -> {
                    val error = json.optJSONObject("error")?.optString("message") ?: "Unknown error"
                    Log.e("OpenAIRealtimeClient", "Error: $error")
                    scope.launch { _error.emit(error) }
                }
                "input_audio_buffer.speech_started" -> {
                    Log.d("OpenAIRealtimeClient", "speech_started")
                }
                "input_audio_buffer.speech_stopped" -> {
                    Log.d("OpenAIRealtimeClient", "speech_stopped")
                }
                // response.* används inte i transkriptionssession
                // Transcription-session events
                "input_audio_transcription.delta" -> {
                    val d = json.optString("text", json.optString("transcript", ""))
                    if (d.isNotEmpty()) scope.launch { _partialTranscript.emit(d) }
                }
                "input_audio_transcription.completed" -> {
                    val text = json.optString("text", json.optString("transcript", ""))
                    if (text.isNotEmpty()) scope.launch { _finalTranscript.emit(text) }
                }
                "conversation.item.created" -> {
                    Log.d("OpenAIRealtimeClient", "Conversation item created")
                }
                else -> {
                    Log.d("OpenAIRealtimeClient", "Unhandled message type: $type")
                }
            }
        } catch (e: Exception) {
            Log.e("OpenAIRealtimeClient", "Failed to parse message", e)
        }
    }
    
    fun startRecording() {
        if (!isConnected) {
            Log.w("OpenAIRealtimeClient", "Not connected")
            return
        }
        
        if (!sessionReady) {
            pendingStart = true
            Log.w("OpenAIRealtimeClient", "Session not ready yet (waiting for transcription_session.updated)")
            return
        }
        isRecording = true
        totalSamplesAppended = 0
        hasUncommittedAudio = false
        allowSendingAudio = true
        Log.d("OpenAIRealtimeClient", "Started recording")
    }
    
    fun appendAudio(audioData: ByteArray) {
        if (!isConnected || !isRecording || !sessionReady) {
            return
        }
        
        try {
            val now = System.currentTimeMillis()
            if (now < rateLimitedUntilMs) {
                if (now - lastRateLogMs > 1000) {
                    Log.d("OpenAIRealtimeClient", "Skipping audio due to 429 backoff (${(rateLimitedUntilMs - now)}ms left)")
                    lastRateLogMs = now
                }
                return
            }
            if (audioData.isEmpty()) return
            if (!allowSendingAudio) return
            totalSamplesAppended += (audioData.size / 2)
            hasUncommittedAudio = true
            if (totalSamplesAppended % (sampleRate / 2) == 0L) { // ungefär var 0.5s
                Log.d("OpenAIRealtimeClient", "Buffered ~${getBufferedMs()}ms, sending chunk bytes=${audioData.size}")
            }
            val chunkSize = 1920 // ~40ms @ 24kHz PCM16 mono
            var offset = 0
            while (offset < audioData.size) {
                val end = minOf(offset + chunkSize, audioData.size)
                val chunk = if (end - offset == audioData.size) audioData else audioData.copyOfRange(offset, end)
                val base64Audio = android.util.Base64.encodeToString(chunk, android.util.Base64.NO_WRAP)
                val message = JSONObject().apply {
                    put("type", "input_audio_buffer.append")
                    put("audio", base64Audio)
                }
                webSocket?.send(message.toString())
                txBytesSinceLastLog += (end - offset)
                offset = end
            }

            // Per-sekund genomströmning för validering
            val now2 = System.currentTimeMillis()
            if (txLastLogMs == 0L) txLastLogMs = now2
            if (now2 - txLastLogMs >= 1000) {
                val msAudio = (txBytesSinceLastLog / 48.0).toInt() // 48 bytes/ms @ 24kHz PCM16 mono
                Log.d("OpenAIRealtimeClient", "TX ≈${txBytesSinceLastLog} bytes/s (~${msAudio} ms audio)")
                txBytesSinceLastLog = 0
                txLastLogMs = now2
            }
            
            // Calculate audio level for visualization
            val audioLevel = calculateAudioLevel(audioData)
            scope.launch {
                _audioLevel.emit(audioLevel)
            }
            if ((totalSamplesAppended % sampleRate) == 0L) {
                Log.d("OpenAIRealtimeClient", "AudioLevel≈$audioLevel (0..1)")
            }
            
        } catch (e: Exception) {
            Log.e("OpenAIRealtimeClient", "Failed to send audio", e)
        }
    }
    
    private fun calculateAudioLevel(audioData: ByteArray): Float {
        var sum = 0.0
        for (i in audioData.indices step 2) {
            if (i + 1 < audioData.size) {
                val sample = (audioData[i].toInt() and 0xFF) or ((audioData[i + 1].toInt() and 0xFF) shl 8)
                val shortSample = if (sample > 32767) sample - 65536 else sample
                sum += shortSample * shortSample
            }
        }
        val rms = kotlin.math.sqrt(sum / (audioData.size / 2))
        return (rms / 32768.0).toFloat().coerceIn(0f, 1f)
    }
    
    fun stopRecording() {
        if (!isRecording) {
            return
        }
        
        isRecording = false
        Log.d("OpenAIRealtimeClient", "Stopped recording")
        // Manuell commit av buffrad input när användaren trycker stopp
        // Skicka commit oavsett flagga för att säkerställa avslut
        val bufferedMs = getBufferedMs()
        if (hasUncommittedAudio && bufferedMs >= 100) {
            try {
                val commit = JSONObject().apply {
                    put("type", "input_audio_buffer.commit")
                }
                webSocket?.send(commit.toString())
                allowSendingAudio = false
                Log.d("OpenAIRealtimeClient", "Sent input_audio_buffer.commit")
            } catch (e: Exception) {
                Log.e("OpenAIRealtimeClient", "Failed to send input_audio_buffer.commit", e)
            }
        } else {
            Log.w(
                "OpenAIRealtimeClient",
                "Skip commit: bufferedMs=" + bufferedMs + " hasUncommittedAudio=" + hasUncommittedAudio
            )
            allowSendingAudio = false
        }
    }
    
    fun getBufferedMs(): Int {
        return ((totalSamplesAppended * 1000L) / sampleRate).toInt()
    }
    
    fun disconnect() {
        isRecording = false
        isConnected = false
        
        webSocket?.close(1000, "Normal closure")
        webSocket = null
        
        client?.dispatcher?.executorService?.shutdown()
        client = null
        
        scope.launch {
            _connectionStatus.emit(ConnectionStatus.DISCONNECTED)
        }
        
        Log.d("OpenAIRealtimeClient", "Disconnected")
    }
    
    fun cleanup() {
        disconnect()
        scope.cancel()
    }
}
