package se.olle.rostbubbla.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import android.media.AudioManager
import android.media.ToneGenerator
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import se.olle.rostbubbla.ACTIONS
import se.olle.rostbubbla.openai.AudioRecorder
import se.olle.rostbubbla.openai.OpenAIRealtimeClient
import kotlin.math.*

class OpenAIRecordingActivity : ComponentActivity() {
    private lateinit var openAIClient: OpenAIRealtimeClient
    private lateinit var audioRecorder: AudioRecorder
    private var apiKey: String = ""
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        apiKey = intent.getStringExtra("api_key") ?: ""
        if (apiKey.isBlank()) {
            Toast.makeText(this@OpenAIRecordingActivity, "OpenAI API key missing", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        
        openAIClient = OpenAIRealtimeClient()
        audioRecorder = AudioRecorder()
        
        // Läs in ljudinställningar
        val prefs = getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
        val gainModeStr = prefs.getString("gain_mode", "Off") ?: "Off"
        val manualGainDb = prefs.getInt("manual_gain_db", 0)
        val carMode = prefs.getBoolean("car_mode_bt_auto", false)

        // Enkel BT-heuristik: om car mode och BT aktivt -> tvinga Auto mic gain
        val audioMgr = getSystemService(android.media.AudioManager::class.java)
        val btActive = try { audioMgr?.isBluetoothScoOn == true || audioMgr?.isBluetoothA2dpOn == true } catch (_: Throwable) { false }
        audioRecorder.setMicProfile(AudioRecorder.MicProfile.RECOGNITION)
        Log.d("OpenAIRecordingActivity", "Using default AudioSource (VOICE_RECOGNITION)")
        val gm = when (gainModeStr) {
            "Auto" -> AudioRecorder.GainMode.AUTO
            "Manual" -> AudioRecorder.GainMode.MANUAL
            else -> AudioRecorder.GainMode.OFF
        }
        val effectiveGainMode = if (carMode && btActive) AudioRecorder.GainMode.AUTO else gm
        audioRecorder.setGainConfig(effectiveGainMode, manualGainDb)

        setContent {
            MaterialTheme {
                OpenAIRecordingUI()
            }
        }
        
        // Start recording immediately
        startRecording()
    }
    
    private fun startRecording() {
        lifecycleScope.launch {
            try {
                openAIClient.connect(apiKey)
                // Vänta tills CONNECTED eller ERROR en gång
                val status = withTimeout(10000) { // 10s
                    openAIClient.connectionStatus.first { s ->
                        s == OpenAIRealtimeClient.ConnectionStatus.CONNECTED ||
                        s == OpenAIRealtimeClient.ConnectionStatus.ERROR
                    }
                }
                when (status) {
                    OpenAIRealtimeClient.ConnectionStatus.CONNECTED -> {
                        audioRecorder.startRecording()
                        openAIClient.startRecording()
                        // Streama ljud kontinuerligt
                        launch {
                            audioRecorder.audioData.collect { audioData ->
                                openAIClient.appendAudio(audioData)
                            }
                        }
                        // Lyssna på ljudfel
                        launch {
                            audioRecorder.error.collect { error ->
                                Toast.makeText(this@OpenAIRecordingActivity, "Audio error: $error", Toast.LENGTH_SHORT).show()
                                finish()
                            }
                        }
                    }
                    else -> {
                        Toast.makeText(this@OpenAIRecordingActivity, "Connection failed", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                Toast.makeText(this@OpenAIRecordingActivity, "Connection timeout", Toast.LENGTH_SHORT).show()
                finish()
            } catch (e: Exception) {
                Toast.makeText(this@OpenAIRecordingActivity, "Failed to start: ${e.message}", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
    
    private fun stopRecording() {
        audioRecorder.stopRecording()
        openAIClient.stopRecording()
        
                // Vänta på första icke-tomma sluttranskript (replay=1 gör att vi får senaste om det redan finns)
                lifecycleScope.launch {
                    try {
                        val transcript = withTimeout(20000) { // 20s timeout
                            openAIClient.finalTranscript.first { it.isNotEmpty() }
                        }
                        Log.d("OpenAIRecordingActivity", "Received final transcript: $transcript")
                        val intent = Intent(ACTIONS.ACTION_STT_RESULT).apply {
                            putExtra(ACTIONS.EXTRA_STT_TEXT, transcript)
                            `package` = packageName
                        }
                        sendBroadcast(intent)
                        finish()
                    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                        Log.e("OpenAIRecordingActivity", "Transcription timeout")
                        Toast.makeText(this@OpenAIRecordingActivity, "Transcription timeout", Toast.LENGTH_SHORT).show()
                        finish()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        // Ignorera avbruten jobb när aktiviteten stängs normalt
                    } catch (e: Exception) {
                        Log.e("OpenAIRecordingActivity", "No transcription received: ${e.message}")
                        Toast.makeText(this@OpenAIRecordingActivity, "No transcription received: ${e.message}", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        audioRecorder.cleanup()
        openAIClient.cleanup()
    }
    
    @Composable
    fun OpenAIRecordingUI() {
        var isRecording by remember { mutableStateOf(true) }
        var partialText by remember { mutableStateOf("") }
        var audioLevel by remember { mutableStateOf(0f) }
        var connectionStatus by remember { mutableStateOf(OpenAIRealtimeClient.ConnectionStatus.CONNECTING) }
        val context = LocalContext.current

        // Beep when transitioning from CONNECTING -> CONNECTED
        val toneGen = remember { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80) }
        DisposableEffect(Unit) {
            onDispose { try { toneGen.release() } catch (_: Throwable) {} }
        }
        var prevStatus by remember { mutableStateOf(connectionStatus) }
        LaunchedEffect(connectionStatus) {
            if (prevStatus == OpenAIRealtimeClient.ConnectionStatus.CONNECTING &&
                connectionStatus == OpenAIRealtimeClient.ConnectionStatus.CONNECTED) {
                try { toneGen.startTone(ToneGenerator.TONE_PROP_ACK, 120) } catch (_: Throwable) {}
            }
            prevStatus = connectionStatus
        }
        
        // Collect events from OpenAI client
        LaunchedEffect(Unit) {
            launch {
                openAIClient.partialTranscript.collect { text ->
                    partialText += text
                }
            }
            
            launch {
                openAIClient.audioLevel.collect { level ->
                    audioLevel = level
                }
            }
            
            launch {
                openAIClient.connectionStatus.collect { status ->
                    connectionStatus = status
                }
            }
            
            launch {
                openAIClient.error.collect { error ->
                    Toast.makeText(this@OpenAIRecordingActivity, error, Toast.LENGTH_SHORT).show()
                }
            }
        }
        
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.8f)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // Status text (English)
                Text(
                    text = when (connectionStatus) {
                        OpenAIRealtimeClient.ConnectionStatus.CONNECTING -> "Connecting..."
                        OpenAIRealtimeClient.ConnectionStatus.CONNECTED -> "Recording..."
                        OpenAIRealtimeClient.ConnectionStatus.ERROR -> "Error"
                        else -> "Disconnected"
                    },
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium
                )

                if (connectionStatus == OpenAIRealtimeClient.ConnectionStatus.CONNECTING) {
                    CircularProgressIndicator(color = Color.White)
                }
                
                // Audio visualization
                AudioVisualization(audioLevel = audioLevel)
                
                // Partial transcript
                if (partialText.isNotEmpty()) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth(0.8f)
                            .padding(horizontal = 16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color.White.copy(alpha = 0.9f)
                        )
                    ) {
                        Text(
                            text = partialText,
                            modifier = Modifier.padding(16.dp),
                            color = Color.Black,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                
                // Stop button with visible pulsing animation when recording
                val pulseScale by rememberInfiniteTransition(label = "pulse").animateFloat(
                    initialValue = 1.0f,
                    targetValue = 1.3f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(800, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "pulseScaleAnim"
                )

                var isPressed by remember { mutableStateOf(false) }
                
                Box(contentAlignment = Alignment.Center) {
                    // Multiple pulsing rings when recording
                    if (connectionStatus == OpenAIRealtimeClient.ConnectionStatus.CONNECTED) {
                        repeat(3) { index ->
                            val delay = index * 200
                            val ringScale by rememberInfiniteTransition(label = "ring$index").animateFloat(
                                initialValue = 0.8f,
                                targetValue = 1.4f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(1200, delayMillis = delay, easing = FastOutSlowInEasing),
                                    repeatMode = RepeatMode.Restart
                                ),
                                label = "ringScale$index"
                            )
                            Box(
                                modifier = Modifier
                                    .size(120.dp)
                                    .clip(CircleShape)
                                    .background(Color.Red.copy(alpha = 0.15f - index * 0.05f))
                                    .graphicsLayer { 
                                        scaleX = ringScale
                                        scaleY = ringScale
                                        alpha = if (ringScale > 1.2f) 0f else 1f
                                    }
                            )
                        }
                    }
                    
                    Button(
                        onClick = {
                            isPressed = true
                            isRecording = false
                            stopRecording()
                        },
                        modifier = Modifier
                            .size(104.dp)
                            .clip(CircleShape)
                            .graphicsLayer { 
                                scaleX = if (isPressed) 0.95f else 1f
                                scaleY = if (isPressed) 0.95f else 1f
                            },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isPressed) Color.Red.copy(alpha = 0.8f) else Color.Red
                        ),
                        enabled = !isPressed
                    ) {
                        Text(
                            text = if (isPressed) "..." else "STOP",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
    
    @Composable
    fun AudioVisualization(audioLevel: Float) {
        val animatedLevel by animateFloatAsState(
            targetValue = audioLevel,
            animationSpec = tween(100),
            label = "audioLevel"
        )
        
        Canvas(
            modifier = Modifier.size(200.dp)
        ) {
            drawAudioWaves(animatedLevel)
        }
    }
    
    private fun DrawScope.drawAudioWaves(level: Float) {
        val centerX = size.width / 2
        val centerY = size.height / 2
        val maxRadius = minOf(centerX, centerY) * 0.8f
        
        // Draw multiple concentric circles with varying opacity based on audio level
        for (i in 1..5) {
            val radius = (maxRadius * i / 5) * (0.3f + level * 0.7f)
            val alpha = (0.1f + level * 0.4f) * (1f - i * 0.15f)
            
            drawCircle(
                color = Color.White.copy(alpha = alpha),
                radius = radius,
                center = Offset(centerX, centerY),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx())
            )
        }
        
        // Draw center circle
        drawCircle(
            color = Color.White.copy(alpha = 0.6f + level * 0.4f),
            radius = 20.dp.toPx(),
            center = Offset(centerX, centerY)
        )
    }
}
