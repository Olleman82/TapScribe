package se.olle.rostbubbla.openai

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.media.audiofx.AcousticEchoCanceler
import kotlin.math.sqrt
import kotlin.math.pow

class AudioRecorder {
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    // Audio configuration for OpenAI Realtime API (requires >= 24 kHz)
    private val sampleRate = 24000 // 24 kHz
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
    
    // Mic profile & gain modes
    enum class MicProfile { RECOGNITION }
    enum class GainMode { OFF, AUTO, MANUAL }

    @Volatile private var micProfile: MicProfile = MicProfile.RECOGNITION
    @Volatile private var gainMode: GainMode = GainMode.OFF
    @Volatile private var manualGainDb: Int = 0 // 0..12
    @Volatile private var targetRms: Float = 0.18f // 0..1
    @Volatile private var currentGain: Float = 1.0f
    private val maxGainLin: Float = dbToLin(12.0)

    fun setMicProfile(profile: MicProfile) {
        micProfile = profile
    }

    fun setGainConfig(mode: GainMode, manualDb: Int = 0, target: Float = 0.18f) {
        gainMode = mode
        manualGainDb = manualDb.coerceIn(0, 12)
        targetRms = target.coerceIn(0.05f, 0.30f)
    }

    private fun dbToLin(db: Double): Float = 10.0.pow(db / 20.0).toFloat()

    private fun rmsPcm16(data: ByteArray): Float {
        var sum = 0.0
        var n = 0
        var i = 0
        while (i + 1 < data.size) {
            val s = (data[i].toInt() and 0xFF) or ((data[i + 1].toInt() and 0xFF) shl 8)
            val v = if (s > 32767) s - 65536 else s
            sum += (v * v).toDouble()
            n++
            i += 2
        }
        if (n == 0) return 0f
        val rms = sqrt(sum / n)
        return (rms / 32768.0).toFloat().coerceIn(0f, 1f)
    }

    private fun applyGainPcm16InPlace(data: ByteArray, gain: Float) {
        var i = 0
        while (i + 1 < data.size) {
            val s = (data[i].toInt() and 0xFF) or ((data[i + 1].toInt() and 0xFF) shl 8)
            var v = if (s > 32767) s - 65536 else s
            v = (v * gain).toInt().coerceIn(-32768, 32767)
            data[i] = (v and 0xFF).toByte()
            data[i + 1] = ((v ushr 8) and 0xFF).toByte()
            i += 2
        }
    }

    // Event flows
    private val _audioData = MutableSharedFlow<ByteArray>()
    val audioData: SharedFlow<ByteArray> = _audioData.asSharedFlow()
    
    private val _error = MutableSharedFlow<String>()
    val error: SharedFlow<String> = _error.asSharedFlow()
    
    fun startRecording() {
        if (isRecording) {
            Log.w("AudioRecorder", "Already recording")
            return
        }
        
        try {
            val minBuf = bufferSize
            val source = MediaRecorder.AudioSource.VOICE_RECOGNITION
            Log.d("AudioRecorder", "Using AudioSource: ${if (source == MediaRecorder.AudioSource.VOICE_RECOGNITION) "VOICE_RECOGNITION" else "VOICE_COMMUNICATION"} (profile: $micProfile)")
            audioRecord = AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(audioFormat)
                        .setChannelMask(channelConfig)
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 4)
                .build()
            
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e("AudioRecorder", "Failed to initialize AudioRecord")
                scope.launch {
                    _error.emit("Failed to initialize audio recording")
                }
                return
            }

            // Enable platform DSP where available
            val sessionId = audioRecord?.audioSessionId ?: -1
            if (sessionId > 0) {
                val agc = try { AutomaticGainControl.create(sessionId) } catch (_: Throwable) { null }
                val ns = try { NoiseSuppressor.create(sessionId) } catch (_: Throwable) { null }
                val aec: AcousticEchoCanceler? = null // inte använd när vi alltid kör VOICE_RECOGNITION

                try { agc?.setEnabled(true) } catch (_: Throwable) {}
                try { ns?.setEnabled(true) } catch (_: Throwable) {}
                try { aec?.setEnabled(true) } catch (_: Throwable) {}

                val agcOn = try { agc?.getEnabled() } catch (_: Throwable) { null }
                val nsOn = try { ns?.getEnabled() } catch (_: Throwable) { null }
                val aecOn = try { aec?.getEnabled() } catch (_: Throwable) { null }

                Log.d("AudioRecorder", "SessionId: $sessionId, AGC: $agcOn, NS: $nsOn, AEC: $aecOn")
            }
            
            audioRecord?.startRecording()
            isRecording = true
            
            Log.d(
                "AudioRecorder",
                "Started recording sessionId=${audioRecord?.audioSessionId} rate=${audioRecord?.sampleRate}Hz bufferSize=${minBuf} source=VOICE_RECOGNITION"
            )
            
            // Start reading audio data in a coroutine
            scope.launch {
                readAudioData()
            }
            
        } catch (e: Exception) {
            Log.e("AudioRecorder", "Failed to start recording", e)
            scope.launch {
                _error.emit("Failed to start recording: ${e.message}")
            }
        }
    }
    
    private suspend fun readAudioData() {
        val minBuf = bufferSize
        val ioBuffer = ByteArray(minBuf * 4)
        val bytesPerSample = 2 // PCM16
        val chunkMs = 40
        val chunkBytes = (sampleRate * chunkMs / 1000) * bytesPerSample // 1920
        var carry = ByteArray(0)
        
        while (isRecording && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            try {
                val bytesRead = audioRecord?.read(ioBuffer, 0, ioBuffer.size) ?: 0
                
                if (bytesRead > 0) {
                    val readData = ioBuffer.copyOfRange(0, bytesRead)
                    val combined = if (carry.isNotEmpty()) carry + readData else readData
                    var off = 0
                    while (combined.size - off >= chunkBytes) {
                        val chunk = combined.copyOfRange(off, off + chunkBytes)
                        off += chunkBytes

                        // Apply mild software gain if configured
                        when (gainMode) {
                            GainMode.OFF -> { /* no-op */ }
                            GainMode.MANUAL -> {
                                val g = dbToLin(manualGainDb.toDouble())
                                applyGainPcm16InPlace(chunk, g)
                            }
                            GainMode.AUTO -> {
                                val eps = 1e-4f
                                val rms = rmsPcm16(chunk)
                                val desired = (targetRms / (rms + eps)).coerceIn(1f, maxGainLin)
                                val alpha = if (desired > currentGain) 0.10f else 0.03f
                                currentGain = (1 - alpha) * currentGain + alpha * desired
                                applyGainPcm16InPlace(chunk, currentGain)
                            }
                        }

                        _audioData.emit(chunk)
                    }
                    carry = if (off < combined.size) combined.copyOfRange(off, combined.size) else ByteArray(0)
                } else if (bytesRead < 0) {
                    // Error reading audio
                    val errorMsg = when (bytesRead) {
                        AudioRecord.ERROR_INVALID_OPERATION -> "Invalid operation"
                        AudioRecord.ERROR_BAD_VALUE -> "Bad value"
                        else -> "Unknown error: $bytesRead"
                    }
                    Log.e("AudioRecorder", "Error reading audio: $errorMsg")
                    scope.launch {
                        _error.emit("Audio read error: $errorMsg")
                    }
                    break
                }
                
                // Ingen extra delay: vi vill sända kontinuerligt ~40ms-chunkar
                
            } catch (e: Exception) {
                Log.e("AudioRecorder", "Exception while reading audio", e)
                scope.launch {
                    _error.emit("Audio read exception: ${e.message}")
                }
                break
            }
        }
    }
    
    fun stopRecording() {
        if (!isRecording) {
            return
        }
        
        isRecording = false
        
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            
            Log.d("AudioRecorder", "Stopped recording")
            
        } catch (e: Exception) {
            Log.e("AudioRecorder", "Error stopping recording", e)
        }
    }
    
    fun cleanup() {
        stopRecording()
        scope.cancel()
    }
    
    fun isRecording(): Boolean = isRecording
}
