
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
          .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY })
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
      menu.add(0, 1, 0, "Close Bubble")
      setOnMenuItemClickListener { item ->
        when (item.itemId) {
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
      // Använd en icke-adaptiv drawable som small icon för notiser
      .setSmallIcon(R.drawable.ic_launcher_foreground)
      .setContentTitle("Microphone Bubble Active")
      .setOngoing(true)
      .build()
  }

  private suspend fun runHeadlessFlow(anchor: View) {
    val apiKey = getSharedPreferences("settings", MODE_PRIVATE).getString("gemini_api_key", "").orEmpty()
    if (apiKey.isBlank()) {
      Toast.makeText(this, "Fill in API key first", Toast.LENGTH_SHORT).show(); return
    }
    // 1) Lyssna via Google UI (transparent aktivitet) och vänta på resultat
    val raw = withContext(Dispatchers.Main) { listenViaUi() }.orEmpty()
    if (raw.isBlank()) { Toast.makeText(this, "No voice captured", Toast.LENGTH_SHORT).show(); return }
    // 2) Välj prompt (popup meny)
    val prompts = withContext(Dispatchers.IO) { dao.all() }
    if (prompts.isEmpty()) { Toast.makeText(this, "No prompts", Toast.LENGTH_SHORT).show(); return }
    val picked: Prompt = CompletableDeferred<Prompt?>().also { def ->
      val menu = PopupMenu(this, anchor)
      prompts.forEachIndexed { idx, p -> menu.menu.add(0, idx, idx, p.title) }
      menu.setOnMenuItemClickListener { item ->
        def.complete(prompts[item.itemId]); true
      }
      menu.setOnDismissListener { if (!def.isCompleted) def.complete(null) }
      menu.show()
    }.await() ?: return
    // 3) Gemini
    val system = picked.systemText
    val req = GenerateContentRequest(
      systemInstruction = SystemInstruction(parts = listOf(Part(system))),
      contents = listOf(Content(role = "user", parts = listOf(Part(raw)))),
      generationConfig = GenerationConfig(temperature = 0.3, thinkingConfig = ThinkingConfig(thinkingBudget = 0))
    )
    val reply = withContext(Dispatchers.IO) {
      try {
        gemini.generateContent("gemini-2.5-flash", apiKey, req)
          .candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text.orEmpty()
      } catch (t: Throwable) { "" }
    }
    if (reply.isBlank()) { Toast.makeText(this, "AI response empty", Toast.LENGTH_SHORT).show(); return }
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
