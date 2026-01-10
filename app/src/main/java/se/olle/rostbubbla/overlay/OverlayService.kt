
package se.olle.rostbubbla.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import se.olle.rostbubbla.debug.DebugLogger
import android.os.IBinder
import android.view.*
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.EditText
import android.widget.TextView
import android.widget.Button
import android.widget.PopupWindow
import androidx.core.app.NotificationCompat
import se.olle.rostbubbla.ACTIONS
import se.olle.rostbubbla.R
import se.olle.rostbubbla.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import se.olle.rostbubbla.speech.SpeechRepo
import android.widget.Toast
import se.olle.rostbubbla.data.AppDb
import se.olle.rostbubbla.data.Prompt
import se.olle.rostbubbla.data.MemoryItem
import se.olle.rostbubbla.net.*
import retrofit2.Retrofit
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.converter.moshi.MoshiConverterFactory
import com.squareup.moshi.Moshi
import se.olle.rostbubbla.ui.MainViewModel
import se.olle.rostbubbla.ui.MailResult
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import android.content.ClipboardManager
import android.content.ClipData
import android.net.Uri
import android.os.Handler
import android.os.Looper
import kotlin.coroutines.resume

sealed class PromptAction {
  data class UsePrompt(val prompt: Prompt) : PromptAction()
  object SaveToMemory : PromptAction()
}

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
          .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS) // 60 sekunder för Google Search + Thinking
          .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS) // 30 sekunder för anslutning
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
      menu.add(0, 0, 0, getString(R.string.overlay_menu_settings))
      menu.add(0, 1, 1, getString(R.string.overlay_menu_close))
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
      nm.createNotificationChannel(NotificationChannel(chId, getString(R.string.overlay_channel_name), NotificationManager.IMPORTANCE_LOW))
    }
    return NotificationCompat.Builder(this, chId)
      // Use a non-adaptive drawable as small icon for notifications
      .setSmallIcon(R.drawable.ic_launcher_foreground)
      .setContentTitle(getString(R.string.overlay_notification_title))
      .setOngoing(true)
      .build()
  }

  private fun updateNotificationStatus(text: String) {
    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val chId = "overlay"
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      nm.createNotificationChannel(NotificationChannel(chId, getString(R.string.overlay_channel_name), NotificationManager.IMPORTANCE_LOW))
    }
    val n = NotificationCompat.Builder(this, chId)
      .setSmallIcon(R.drawable.ic_launcher_foreground)
      .setContentTitle(getString(R.string.overlay_notification_title))
      .setContentText(text)
      .setOngoing(true)
      .build()
    nm.notify(1, n)
  }

  private suspend fun runHeadlessFlow(anchor: View) {
    DebugLogger.log(this, "Overlay", "runHeadlessFlow started")
    val apiKey = getSharedPreferences("settings", MODE_PRIVATE).getString("gemini_api_key", "").orEmpty()
    if (apiKey.isBlank()) {
      Toast.makeText(this@OverlayService, getString(R.string.overlay_toast_missing_gemini_key), Toast.LENGTH_SHORT).show(); return
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
          Toast.makeText(this@OverlayService, getString(R.string.overlay_toast_missing_openai_key), Toast.LENGTH_SHORT).show()
          null
        } else {
          listenViaUi()
        }
      }
    }.orEmpty()
    DebugLogger.log(this, "Overlay", "STT Result: '${raw.take(50)}...' (length=${raw.length})")
    if (raw.isBlank()) { 
      if (globalUseOpenAI) {
        Toast.makeText(this@OverlayService, getString(R.string.overlay_toast_openai_failed), Toast.LENGTH_SHORT).show()
      } else {
        Toast.makeText(this@OverlayService, getString(R.string.toast_no_voice_captured), Toast.LENGTH_SHORT).show()
      }
      return 
    }
  // 2) Choose prompt (popup menu) or auto-prompt
    val prompts = withContext(Dispatchers.IO) { dao.all() }
    if (prompts.isEmpty()) { Toast.makeText(this@OverlayService, getString(R.string.overlay_toast_no_prompts), Toast.LENGTH_SHORT).show(); return }
    val prefs = getSharedPreferences("settings", MODE_PRIVATE)
    val autoEnabled = prefs.getBoolean("auto_prompt_enabled", false)
    val autoTitle = prefs.getString("auto_prompt_title", null)
    val picked: Prompt = if (autoEnabled && !autoTitle.isNullOrBlank()) {
      prompts.firstOrNull { it.title == autoTitle } ?: prompts.first()
    } else {
      // Delay to let window focus stabilize (fixes OnePlus/OxygenOS popup dismissal)
      delay(200)
      DebugLogger.log(this, "Overlay", "Showing PopupMenu after 200ms delay")
      val action = CompletableDeferred<PromptAction?>().also { def ->
        val menu = PopupMenu(this, anchor)
        // Add "Spara till minnet" as first option
        menu.menu.add(0, -1, 0, getString(R.string.memory_save_to_memory))
        // Add separator
        menu.menu.add(0, -2, 1, "---")
        // Add regular prompts
        prompts.forEachIndexed { idx, p -> menu.menu.add(0, idx, idx + 2, p.title) }
        menu.setOnMenuItemClickListener { item ->
          when (item.itemId) {
            -1 -> def.complete(PromptAction.SaveToMemory)
            -2 -> {} // separator, do nothing
            else -> def.complete(PromptAction.UsePrompt(prompts[item.itemId]))
          }
          true
        }
        menu.setOnDismissListener { 
          val selectionMade = def.isCompleted
          DebugLogger.log(this, "Overlay", "PopupMenu dismissed (selection made: $selectionMade)")
          if (!selectionMade) def.complete(null) 
        }
        menu.show()
      }.await()

      when (action) {
        is PromptAction.UsePrompt -> action.prompt
        is PromptAction.SaveToMemory -> {
          // Handle memory save action
          scope.launch { handleSaveToMemory(raw) }
          return
        }
        null -> return
      }
    }
    DebugLogger.log(this, "Overlay", "Prompt selected: ${picked.title}")
    // 3) Gemini eller rå webhook
    if (picked.sendWebhook && picked.webhookRawOnly) {
      val reply = raw
      updateNotificationStatus(getString(R.string.overlay_status_done))
      val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
      cm.setPrimaryClip(ClipData.newPlainText("AI", reply))
      triggerWebhookIfEnabled(picked, reply)
      val autoPaste = getSharedPreferences("settings", MODE_PRIVATE).getBoolean("auto_paste", false)
      if (autoPaste) {
        se.olle.rostbubbla.access.PasteAccessibilityService.instance?.pasteText(reply)
      }
      return
    }
    // 3) Gemini
    val reply = if (picked.isMailPrompt) {
      // Använd specialiserad mail-metod
      withContext(Dispatchers.Main) {
        val flags = buildList {
          add(getString(R.string.overlay_flag_thinking))
          add(getString(R.string.overlay_flag_google_search))
        }.joinToString(" + ")
        val label = getString(R.string.overlay_status_working_flags, flags)
        Toast.makeText(this@OverlayService, label, Toast.LENGTH_SHORT).show()
        updateNotificationStatus(label)
      }
      
      // Skapa MainViewModel instans för mail-funktionalitet
      val viewModel = MainViewModel(application)
      val mailResult = withContext(Dispatchers.IO) {
        viewModel.callGeminiForMail(picked, apiKey, raw) { attempt ->
          // Använd Handler för att köra på UI-thread från Service
          android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(this@OverlayService, getString(R.string.status_retrying, attempt), Toast.LENGTH_SHORT).show()
          }
        }
      }
      
      // Hantera mail-resultat direkt
      if (!mailResult.emailTo.isNullOrBlank()) {
        // Öppna mail-klient
        if (openEmailClient(mailResult.emailTo, mailResult.subject ?: "", mailResult.body ?: "")) {
          return // Lyckades öppna mail-klient
        } else {
          // Fallback till clipboard
          Toast.makeText(this@OverlayService, getString(R.string.mail_prompt_error_no_client), Toast.LENGTH_SHORT).show()
        }
      } else {
        // Ingen e-postadress hittades
        Toast.makeText(this@OverlayService, getString(R.string.mail_prompt_error_no_email), Toast.LENGTH_SHORT).show()
      }
      
      // Använd raw response som fallback
      mailResult.rawResponse
    } else {
      // Vanlig Gemini-anrop för icke-mail prompts
      val memoryItems = if (picked.useMemoryList) {
        val items = withContext(Dispatchers.IO) { AppDb.get(this@OverlayService).memoryDao().getAllMemoryItems().first() }
        // Logga för debugging
        withContext(Dispatchers.Main) {
          android.util.Log.d("OverlayService", "Memory items loaded: ${items.size} for prompt: ${picked.title}")
        }
        items
      } else {
        emptyList()
      }

      val system = buildString {
        append(picked.systemText)

        if (memoryItems.isNotEmpty()) {
          append("\n\n")
          append("Du får också en lista med användarens sparade resurser (länkar och textsnuttar).\n")
          append("Om användaren ber om t.ex. 'min bokningslänk' eller något som matchar en rubrik i listan,\n")
          append("välja det mest relevanta itemet och använd dess innehåll i ditt svar.\n\n")
          append("[USER MEMORY ITEMS]\n\n")
          memoryItems.forEach { item ->
            append("1. Titel: ${item.title}\n")
            append("   Innehåll: ${item.content}\n\n")
          }
        }
      }
      val req = GenerateContentRequest(
        systemInstruction = SystemInstruction(parts = listOf(Part(system))),
        contents = listOf(Content(role = "user", parts = listOf(Part(raw)))),
        generationConfig = GenerationConfig(temperature = 0.3, thinkingConfig = if (picked.thinkingEnabled) ThinkingConfig(thinkingBudget = null) else ThinkingConfig(thinkingBudget = 0)),
        tools = if (picked.useGoogleSearch) listOf(Tool(googleSearch = GoogleSearch())) else null
      )
      withContext(Dispatchers.Main) {
        val flags = buildList {
          if (picked.thinkingEnabled) add(getString(R.string.overlay_flag_thinking))
          if (picked.useGoogleSearch) add(getString(R.string.overlay_flag_google_search))
        }.joinToString(" + ")
        val label = if (flags.isBlank()) getString(R.string.status_working) else getString(R.string.overlay_status_working_flags, flags)
        Toast.makeText(this@OverlayService, label, Toast.LENGTH_SHORT).show()
        updateNotificationStatus(label)
      }
      withContext(Dispatchers.IO) {
        var text = ""
        var stop = false
        repeat(3) { attempt ->
          if (stop) return@withContext text
          try {
            text = gemini.generateContent("gemini-2.5-flash", apiKey, req)
              .candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text.orEmpty()
        if (text.isNotBlank()) {
            DebugLogger.log(this@OverlayService, "Overlay", "AI Response received: ${text.take(50)}...")
            return@withContext text
        }
          } catch (t: Throwable) {
            val msg = t.message ?: ""
            if (msg.contains("Search Grounding is not supported", ignoreCase = true)) {
              withContext(Dispatchers.Main) { Toast.makeText(this@OverlayService, getString(R.string.overlay_error_search_grounding), Toast.LENGTH_SHORT).show() }
              stop = true
              return@repeat
            }
          }
          withContext(Dispatchers.Main) {
            Toast.makeText(this@OverlayService, getString(R.string.status_retrying, attempt + 1), Toast.LENGTH_SHORT).show()
          }
          try { kotlinx.coroutines.delay(300L * (attempt + 1)) } catch (_: Throwable) {}
        }
        text
      }
    }
    if (reply.isBlank()) { 
      DebugLogger.log(this, "Overlay", "AI reply is blank/failed")
      Toast.makeText(this@OverlayService, getString(R.string.toast_ai_response_empty), Toast.LENGTH_SHORT).show()
      return 
    }
    DebugLogger.log(this, "Overlay", "Flow finishing. Auto-paste check.")
    updateNotificationStatus(getString(R.string.overlay_status_done))
    
    // Standard clipboard-hantering (för icke-mail prompts eller fallback)
    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("AI", reply))
    triggerWebhookIfEnabled(picked, reply)
    val autoPaste = getSharedPreferences("settings", MODE_PRIVATE).getBoolean("auto_paste", false)
    DebugLogger.log(this, "Overlay", "auto_paste setting is: $autoPaste")
    if (autoPaste) {
      val instance = se.olle.rostbubbla.access.PasteAccessibilityService.instance
      DebugLogger.log(this, "Overlay", "AccessibilityService instance is null: ${instance == null}")
      val ok = instance?.pasteText(reply) ?: false
      DebugLogger.log(this, "Overlay", "Auto-paste result: $ok")
      if (!ok) {
        // Let the system's own clipboard notification suffice; no extra toast
      }
    } else {
      // No extra toast; system shows clipboard notification
    }
  }

  private fun triggerWebhookIfEnabled(prompt: Prompt, reply: String) {
    val token = prompt.webhookToken?.takeIf { prompt.sendWebhook && it.isNotBlank() } ?: return
    val url = prompt.webhookUrl?.takeIf { it.isNotBlank() } ?: return
    DebugLogger.log(this, "Overlay", "Triggering webhook to $url")
    scope.launch {
      val result = runCatching { WebhookClient.sendActivity(reply, token, url) }
        .getOrElse { t -> 
            DebugLogger.log(this@OverlayService, "Overlay", "Webhook failed", t)
            WebhookResult(false, t.message ?: t::class.java.simpleName) 
        }
      
      DebugLogger.log(this@OverlayService, "Overlay", "Webhook result: ok=${result.ok}")

      withContext(Dispatchers.Main) {
        val summary = result.summary.ifBlank { getString(R.string.overlay_status_done) }
        val message = if (result.ok) {
          getString(R.string.webhook_toast_success, summary)
        } else {
          getString(R.string.webhook_toast_error, summary)
        }
        Toast.makeText(this@OverlayService, message, Toast.LENGTH_LONG).show()
        updateNotificationStatus(message)
      }
    }
  }

  private suspend fun handleSaveToMemory(transcription: String) {
    val apiKey = getSharedPreferences("settings", MODE_PRIVATE).getString("gemini_api_key", "").orEmpty()
    if (apiKey.isBlank()) {
      Toast.makeText(this@OverlayService, getString(R.string.overlay_toast_missing_gemini_key), Toast.LENGTH_SHORT).show()
      return
    }

    // Get content from clipboard
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clipData = clipboard.primaryClip
    val content = if (clipData != null && clipData.itemCount > 0) {
      clipData.getItemAt(0).text?.toString()
    } else null

    if (content.isNullOrBlank()) {
      Toast.makeText(this@OverlayService, "Inget innehåll i klippbordet", Toast.LENGTH_SHORT).show()
      return
    }

    withContext(Dispatchers.Main) {
      Toast.makeText(this@OverlayService, "Skapar titel...", Toast.LENGTH_SHORT).show()
    }

    // Generate title using AI
    val titleRequest = GenerateContentRequest(
      systemInstruction = SystemInstruction(parts = listOf(Part("Du är en assistent som skapar korta, beskrivande titlar för sparade resurser. Användarens text beskriver vad ett nytt minne ska kallas. Skapa en kort rubrik som ligger nära användarens formulering, max 10-12 ord. Svara endast i enkel Markdown, t.ex. '## Min bokningslänk för AI-föreläsningar'. Skriv inga länkar och ingen annan text."))),
      contents = listOf(Content(role = "user", parts = listOf(Part(transcription)))),
      generationConfig = GenerationConfig(temperature = 0.3)
    )

    val rawTitle = withContext(Dispatchers.IO) {
      var title = ""
      repeat(3) { attempt ->
        if (title.isNotBlank()) return@withContext title
        try {
          title = gemini.generateContent("gemini-2.5-flash", apiKey, titleRequest)
            .candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text.orEmpty()
            .trim()
          if (title.isNotBlank()) return@withContext title
        } catch (t: Throwable) {
          // Continue to retry
        }
        try { kotlinx.coroutines.delay(300L * (attempt + 1)) } catch (_: Throwable) {}
      }
      title
    }

    if (rawTitle.isBlank()) {
      Toast.makeText(this@OverlayService, "Kunde inte skapa titel", Toast.LENGTH_SHORT).show()
      return
    }

    // Extract title from markdown (remove ## if present)
    val generatedTitle = rawTitle.removePrefix("##").trim()
    android.util.Log.d("OverlayService", "Generated title: $generatedTitle, content length: ${content.length}")

    // Show confirmation dialog
    val confirmedTitle = showMemoryConfirmationDialog(generatedTitle, content)
    android.util.Log.d("OverlayService", "Confirmed title: $confirmedTitle")
    if (confirmedTitle.isNullOrBlank()) {
      // User cancelled
      android.util.Log.d("OverlayService", "User cancelled or title is blank")
      return
    }

    // Save to database
    try {
      val memoryItem = MemoryItem(title = confirmedTitle, content = content)
      android.util.Log.d("OverlayService", "Attempting to save memory: title=$confirmedTitle, content=${content.take(50)}...")
      val savedId = withContext(Dispatchers.IO) {
        val db = AppDb.get(this@OverlayService)
        val memoryDao = db.memoryDao()
        android.util.Log.d("OverlayService", "Database instance: $db, MemoryDao: $memoryDao")
        val id = memoryDao.insertMemoryItem(memoryItem)
        android.util.Log.d("OverlayService", "Memory saved with ID: $id, title: $confirmedTitle")
        
        // Verify it was saved
        val allItems = memoryDao.getAllMemoryItems().first()
        android.util.Log.d("OverlayService", "Total memory items in database: ${allItems.size}")
        id
      }

      withContext(Dispatchers.Main) {
        Toast.makeText(this@OverlayService, "Sparat: $confirmedTitle", Toast.LENGTH_SHORT).show()
      }
    } catch (e: Exception) {
      android.util.Log.e("OverlayService", "Error saving memory", e)
      withContext(Dispatchers.Main) {
        Toast.makeText(this@OverlayService, "Fel vid sparning: ${e.message}", Toast.LENGTH_LONG).show()
      }
    }
  }

  private suspend fun showMemoryConfirmationDialog(generatedTitle: String, content: String): String? {
    return suspendCancellableCoroutine { cont ->
      var isResumed = false
      var buttonClicked = false
      val lock = Any()
      
      fun resumeOnce(value: String?) {
        synchronized(lock) {
          if (!isResumed && !cont.isCompleted) {
            isResumed = true
            cont.resume(value)
          }
        }
      }

      // Create a simple popup window for confirmation
      val popupView = LayoutInflater.from(this).inflate(R.layout.memory_confirmation_popup, null)
      val titleEdit = popupView.findViewById<EditText>(R.id.memory_title_edit)
      val contentText = popupView.findViewById<TextView>(R.id.memory_content_text)
      val saveButton = popupView.findViewById<Button>(R.id.memory_save_button)
      val cancelButton = popupView.findViewById<Button>(R.id.memory_cancel_button)

      titleEdit.setText(generatedTitle)
      contentText.text = content

      val popupWindow = PopupWindow(
        popupView,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        true
      ).apply {
        setOnDismissListener {
          // Only resume with null if a button wasn't clicked (i.e., user dismissed by clicking outside or back button)
          synchronized(lock) {
            if (!buttonClicked && !isResumed && !cont.isCompleted) {
              resumeOnce(null)
            }
          }
        }
      }

      // Set up button listeners
      saveButton.setOnClickListener {
        synchronized(lock) {
          buttonClicked = true
        }
        val title = titleEdit.text.toString().trim()
        if (title.isNotBlank()) {
          // Resume first, then dismiss to avoid race condition
          resumeOnce(title)
          // Use post to ensure resume happens before dismiss
          Handler(Looper.getMainLooper()).post {
            try {
              popupWindow.dismiss()
            } catch (_: Throwable) {}
          }
        } else {
          // Title is blank, show error and don't dismiss
          synchronized(lock) {
            buttonClicked = false // Reset flag so user can try again
          }
          Toast.makeText(this@OverlayService, "Titel kan inte vara tom", Toast.LENGTH_SHORT).show()
        }
      }

      cancelButton.setOnClickListener {
        synchronized(lock) {
          buttonClicked = true
        }
        resumeOnce(null)
        Handler(Looper.getMainLooper()).post {
          try {
            popupWindow.dismiss()
          } catch (_: Throwable) {}
        }
      }

      // Show popup on main thread
      Handler(Looper.getMainLooper()).post {
        try {
          popupWindow.showAtLocation(bubble, Gravity.CENTER, 0, 0)
        } catch (t: Throwable) {
          resumeOnce(null)
        }
      }

      cont.invokeOnCancellation {
        Handler(Looper.getMainLooper()).post {
          try { popupWindow.dismiss() } catch (_: Throwable) {}
        }
      }
    }
  }

  companion object {
    var memoryConfirmationCallback: ((String?) -> Unit)? = null
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

  private fun openEmailClient(to: String, subject: String, body: String): Boolean {
    return try {
      val intent = Intent(Intent.ACTION_SENDTO).apply {
        data = Uri.parse("mailto:")
        putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, body)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      startActivity(intent)
      true
    } catch (e: Exception) {
      false
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
        DebugLogger.log(v.context, "Touch", "ACTION_DOWN")
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
        DebugLogger.log(v.context, "Touch", "ACTION_UP (hasMoved=$hasMoved)")
        // If we dragged, consume UP so click doesn't trigger
        return hasMoved
      }
    }
    return false
  }
}









