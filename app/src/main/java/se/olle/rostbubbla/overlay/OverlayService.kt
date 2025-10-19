
package se.olle.rostbubbla.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.*
import android.widget.ImageView
import android.widget.PopupMenu
import androidx.core.app.NotificationCompat
import se.olle.rostbubbla.ACTIONS
import se.olle.rostbubbla.R
import se.olle.rostbubbla.ui.MainActivity
import kotlinx.coroutines.*
import se.olle.rostbubbla.speech.SpeechRepo
import android.widget.Toast
import se.olle.rostbubbla.data.AppDb
import se.olle.rostbubbla.data.Prompt
import se.olle.rostbubbla.net.*
import retrofit2.Retrofit
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.converter.moshi.MoshiConverterFactory
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import android.content.ClipboardManager
import android.content.ClipData
import kotlin.coroutines.resume

class OverlayService : Service() {
  private lateinit var wm: WindowManager
  private var bubble: View? = null
  private lateinit var params: WindowManager.LayoutParams
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private val speech by lazy { SpeechRepo(this) }
  private val dao by lazy { AppDb.get(this).promptDao() }
  private val gemini by lazy {
    val retrofit = Retrofit.Builder()
      .baseUrl("https://generativelanguage.googleapis.com/")
      .client(
        OkHttpClient.Builder()
          .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
          .build()
      )
      .addConverterFactory(
        MoshiConverterFactory.create(
          Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        )
      )
      .build()
    retrofit.create(GeminiApi::class.java)
  }

  override fun onCreate() {
    super.onCreate()
    startForeground(1, buildNotification())

    wm = getSystemService(WINDOW_SERVICE) as WindowManager
    params = WindowManager.LayoutParams(
      WindowManager.LayoutParams.WRAP_CONTENT,
      WindowManager.LayoutParams.WRAP_CONTENT,
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
      else
        WindowManager.LayoutParams.TYPE_PHONE,
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
              WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
              WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
      PixelFormat.TRANSLUCENT
    ).apply {
      gravity = Gravity.TOP or Gravity.START
      x = 40; y = 300
    }

    bubble = LayoutInflater.from(this).inflate(R.layout.view_mic_bubble, null).apply {
      setOnTouchListener(DragTouchListener(wm, params))
      setOnClickListener {
        scope.launch { runHeadlessFlow(this@apply) }
      }
      setOnLongClickListener {
        showMenu(this); true
      }
    }
    try {
      wm.addView(bubble, params)
    } catch (t: Throwable) {
      // If something is already there, try to clean up and exit
      try { bubble?.let { wm.removeViewImmediate(it) } } catch (_: Throwable) {}
      bubble = null
      stopSelf()
    }
  }

  override fun onBind(intent: Intent?): IBinder? = null

  private fun hide() {
    try { bubble?.let { wm.removeView(it) } } catch (_: Throwable) {}
    bubble = null
    stopSelf()
  }

  private fun showMenu(anchor: View) {
    PopupMenu(this, anchor).apply {
      menu.add(0, 0, 0, "Settings")
      menu.add(0, 1, 1, "Close Bubble")
      setOnMenuItemClickListener { item ->
        when (item.itemId) {
          0 -> { 
            val i = Intent(this@OverlayService, MainActivity::class.java)
            i.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(i)
            true 
          }
          1 -> { hide(); true }
          else -> false
        }
      }
      show()
    }
  }

  override fun onDestroy() {
    // Ensure cleanup if system kills the service
    try { bubble?.let { wm.removeViewImmediate(it) } } catch (_: Throwable) {}
    bubble = null
    scope.cancel()
    super.onDestroy()
  }

  private fun buildNotification(): Notification {
    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val chId = "overlay"
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      nm.createNotificationChannel(NotificationChannel(chId, "Overlay", NotificationManager.IMPORTANCE_LOW))
    }
    return NotificationCompat.Builder(this, chId)
      // Use a non-adaptive drawable as small icon for notifications
      .setSmallIcon(R.drawable.ic_launcher_foreground)
      .setContentTitle("Microphone Bubble Active")
      .setOngoing(true)
      .build()
  }

  private fun updateNotificationStatus(text: String) {
    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val chId = "overlay"
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      nm.createNotificationChannel(NotificationChannel(chId, "Overlay", NotificationManager.IMPORTANCE_LOW))
    }
    val n = NotificationCompat.Builder(this, chId)
      .setSmallIcon(R.drawable.ic_launcher_foreground)
      .setContentTitle("Microphone Bubble Active")
      .setContentText(text)
      .setOngoing(true)
      .build()
    nm.notify(1, n)
  }

  private suspend fun runHeadlessFlow(anchor: View) {
    val apiKey = getSharedPreferences("settings", MODE_PRIVATE).getString("gemini_api_key", "").orEmpty()
    if (apiKey.isBlank()) {
      Toast.makeText(this@OverlayService, "Fill in API key first", Toast.LENGTH_SHORT).show(); return
    }
    
    // Check if OpenAI transcription is enabled globally
    val globalUseOpenAI = getSharedPreferences("settings", MODE_PRIVATE).getBoolean("use_openai_transcription", false)
    val openAIKey = getSharedPreferences("settings", MODE_PRIVATE).getString("openai_api_key", "").orEmpty()
    
    // 1) Listen via OpenAI or Google UI and wait for result
    val raw = withContext(Dispatchers.Main) { 
      if (globalUseOpenAI && openAIKey.isNotBlank()) {
        listenViaOpenAI()
      } else {
        if (globalUseOpenAI && openAIKey.isBlank()) {
          Toast.makeText(this@OverlayService, "OpenAI API key required for OpenAI transcription", Toast.LENGTH_SHORT).show()
          null
        } else {
          listenViaUi()
        }
      }
    }.orEmpty()
    if (raw.isBlank()) { 
      if (globalUseOpenAI) {
        Toast.makeText(this@OverlayService, "OpenAI transcription failed", Toast.LENGTH_SHORT).show()
      } else {
        Toast.makeText(this@OverlayService, "No voice captured", Toast.LENGTH_SHORT).show()
      }
      return 
    }
  // 2) Choose prompt (popup menu) or auto-prompt
    val prompts = withContext(Dispatchers.IO) { dao.all() }
    if (prompts.isEmpty()) { Toast.makeText(this@OverlayService, "No prompts", Toast.LENGTH_SHORT).show(); return }
    val prefs = getSharedPreferences("settings", MODE_PRIVATE)
    val autoEnabled = prefs.getBoolean("auto_prompt_enabled", false)
    val autoTitle = prefs.getString("auto_prompt_title", null)
    val picked: Prompt = if (autoEnabled && !autoTitle.isNullOrBlank()) {
      prompts.firstOrNull { it.title == autoTitle } ?: prompts.first()
    } else {
      CompletableDeferred<Prompt?>().also { def ->
        val menu = PopupMenu(this, anchor)
        prompts.forEachIndexed { idx, p -> menu.menu.add(0, idx, idx, p.title) }
        menu.setOnMenuItemClickListener { item ->
          def.complete(prompts[item.itemId]); true
        }
        menu.setOnDismissListener { if (!def.isCompleted) def.complete(null) }
        menu.show()
      }.await() ?: return
    }
    // 3) Gemini
    val system = picked.systemText
    val req = GenerateContentRequest(
      systemInstruction = SystemInstruction(parts = listOf(Part(system))),
      contents = listOf(Content(role = "user", parts = listOf(Part(raw)))),
      generationConfig = GenerationConfig(temperature = 0.3, thinkingConfig = if (picked.thinkingEnabled) ThinkingConfig(thinkingBudget = null) else ThinkingConfig(thinkingBudget = 0)),
      tools = if (picked.useGoogleSearch) listOf(Tool(googleSearch = GoogleSearch())) else null
    )
    withContext(Dispatchers.Main) {
      val flags = buildList {
        if (picked.thinkingEnabled) add("Thinking")
        if (picked.useGoogleSearch) add("Google Search")
      }.joinToString(" + ")
      val label = if (flags.isBlank()) "Working…" else "Working… ($flags)"
      Toast.makeText(this@OverlayService, label, Toast.LENGTH_SHORT).show()
      updateNotificationStatus(label)
    }
    val reply = withContext(Dispatchers.IO) {
      var text = ""
      var stop = false
      repeat(3) { attempt ->
        if (stop) return@withContext text
        try {
          text = gemini.generateContent("gemini-2.5-flash", apiKey, req)
            .candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text.orEmpty()
          if (text.isNotBlank()) return@withContext text
        } catch (t: Throwable) {
          val msg = t.message ?: ""
          if (msg.contains("Search Grounding is not supported", ignoreCase = true)) {
            withContext(Dispatchers.Main) { Toast.makeText(this@OverlayService, "Search Grounding not supported", Toast.LENGTH_SHORT).show() }
            stop = true
            return@repeat
          }
        }
        withContext(Dispatchers.Main) {
          Toast.makeText(this@OverlayService, "Retrying… (${attempt + 1})", Toast.LENGTH_SHORT).show()
        }
        try { kotlinx.coroutines.delay(300L * (attempt + 1)) } catch (_: Throwable) {}
      }
      text
    }
    if (reply.isBlank()) { Toast.makeText(this@OverlayService, "AI response empty", Toast.LENGTH_SHORT).show(); return }
    updateNotificationStatus("Done")
    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("AI", reply))
    val autoPaste = getSharedPreferences("settings", MODE_PRIVATE).getBoolean("auto_paste", false)
    if (autoPaste) {
      val ok = se.olle.rostbubbla.access.PasteAccessibilityService.instance?.pasteText(reply) ?: false
      if (!ok) {
        // Let the system's own clipboard notification suffice; no extra toast
      }
    } else {
      // No extra toast; system shows clipboard notification
    }
  }

  private suspend fun listenViaOpenAI(): String? = suspendCancellableCoroutine { cont ->
    val apiKey = getSharedPreferences("settings", MODE_PRIVATE).getString("openai_api_key", "").orEmpty()
    if (apiKey.isBlank()) {
      if (!cont.isCompleted) cont.resume(null)
      return@suspendCancellableCoroutine
    }
    
    val filter = android.content.IntentFilter(se.olle.rostbubbla.ACTIONS.ACTION_STT_RESULT)
    val receiver = object : android.content.BroadcastReceiver() {
      override fun onReceive(context: Context, intent: Intent) {
        try { unregisterReceiver(this) } catch (_: Throwable) {}
        val text = intent.getStringExtra(se.olle.rostbubbla.ACTIONS.EXTRA_STT_TEXT)
        if (!cont.isCompleted) cont.resume(text)
      }
    }
    registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    val i = Intent(this, se.olle.rostbubbla.ui.OpenAIRecordingActivity::class.java).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      putExtra("api_key", apiKey)
    }
    try {
      startActivity(i)
    } catch (t: Throwable) {
      try { unregisterReceiver(receiver) } catch (_: Throwable) {}
      if (!cont.isCompleted) cont.resume(null)
    }
    cont.invokeOnCancellation {
      try { unregisterReceiver(receiver) } catch (_: Throwable) {}
    }
  }

  private suspend fun listenViaUi(language: String = "sv-SE"): String? = suspendCancellableCoroutine { cont ->
    val filter = android.content.IntentFilter(se.olle.rostbubbla.ACTIONS.ACTION_STT_RESULT)
    val receiver = object : android.content.BroadcastReceiver() {
      override fun onReceive(context: Context, intent: Intent) {
        try { unregisterReceiver(this) } catch (_: Throwable) {}
        val text = intent.getStringExtra(se.olle.rostbubbla.ACTIONS.EXTRA_STT_TEXT)
        if (!cont.isCompleted) cont.resume(text)
      }
    }
    registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    val i = Intent(this, se.olle.rostbubbla.ui.SpeechUiActivity::class.java).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      putExtra("lang", language)
    }
    try {
      startActivity(i)
    } catch (t: Throwable) {
      try { unregisterReceiver(receiver) } catch (_: Throwable) {}
      if (!cont.isCompleted) cont.resume(null)
    }
    cont.invokeOnCancellation {
      try { unregisterReceiver(receiver) } catch (_: Throwable) {}
    }
  }
}

class DragTouchListener(
  private val wm: WindowManager,
  private val params: WindowManager.LayoutParams
) : View.OnTouchListener {
  private var downX = 0f
  private var downY = 0f
  private var startX = 0
  private var startY = 0
  private var hasMoved = false
  private var touchSlop = -1
  override fun onTouch(v: View, event: MotionEvent): Boolean {
    when (event.action) {
      MotionEvent.ACTION_DOWN -> {
        if (touchSlop < 0) {
          touchSlop = ViewConfiguration.get(v.context).scaledTouchSlop
        }
        downX = event.rawX; downY = event.rawY
        startX = params.x; startY = params.y
        hasMoved = false
        return false
      }
      MotionEvent.ACTION_MOVE -> {
        val dx = (event.rawX - downX).toInt()
        val dy = (event.rawY - downY).toInt()
        if (!hasMoved && (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)) {
          hasMoved = true
        }
        if (hasMoved) {
          params.x = startX + dx
          params.y = startY + dy
          wm.updateViewLayout(v, params)
          return true
        }
        return false
      }
      MotionEvent.ACTION_UP -> {
        // If we dragged, consume UP so click doesn't trigger
        return hasMoved
      }
    }
    return false
  }
}
