
package se.olle.rostbubbla.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import se.olle.rostbubbla.data.AppDb
import se.olle.rostbubbla.data.Prompt
import se.olle.rostbubbla.data.MemoryItem
import se.olle.rostbubbla.net.*
import se.olle.rostbubbla.speech.SpeechRepo
import se.olle.rostbubbla.R
import se.olle.rostbubbla.AiModelConfig
import java.util.regex.Pattern
import se.olle.rostbubbla.debug.DebugLogger

data class MailResult(
  val emailTo: String?,
  val subject: String?,
  val body: String?,
  val rawResponse: String
)

class MainViewModel(app: Application): AndroidViewModel(app) {
  private val db = AppDb.get(app)
  private val dao = db.promptDao()
  private val memoryDao = db.memoryDao()
  private val speech = SpeechRepo(app)

  var rawText: String = ""
    private set

  private val retrofit = Retrofit.Builder()
    .baseUrl("https://generativelanguage.googleapis.com/")
    .client(
      OkHttpClient.Builder()
        .addInterceptor(
          HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
        )
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS) // 60 sekunder för Google Search + Thinking
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS) // 30 sekunder för anslutning
        .build()
    )
    .addConverterFactory(
      MoshiConverterFactory.create(
        Moshi.Builder()
          .add(KotlinJsonAdapterFactory())
          .build()
      )
    )
    .build()
  private val gemini = retrofit.create(GeminiApi::class.java)

  private val openRouterRetrofit = Retrofit.Builder()
    .baseUrl("https://openrouter.ai/api/v1/")
    .client(
      OkHttpClient.Builder()
        .addInterceptor(
          HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
        )
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    )
    .addConverterFactory(
      MoshiConverterFactory.create(
        Moshi.Builder()
          .add(KotlinJsonAdapterFactory())
          .build()
      )
    )
    .build()
  private val openRouter = openRouterRetrofit.create(OpenRouterApi::class.java)

  private fun selectedGeminiModel(): String {
    val appContext = getApplication<Application>()
    val prefs = appContext.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
    return AiModelConfig.normalizeGeminiModel(
      prefs.getString(AiModelConfig.PREF_GEMINI_MODEL, AiModelConfig.GEMINI_2_5_FLASH)
    )
  }

  suspend fun capture(maxSegments: Int = 3): String {
    rawText = ""
    repeat(maxSegments) { idx ->
      val seg = speech.listenOnce() ?: ""
      if (seg.isNotBlank()) rawText = (rawText + " " + seg).trim()
      val more = userWantsMore(idx, maxSegments) // dummy; UI should supply
      if (!more) return rawText
    }
    return rawText
  }

  // Hooked up from UI
  var moreSegmentsDecider: ((Int, Int) -> Boolean)? = null
  private fun userWantsMore(idx: Int, max: Int) = moreSegmentsDecider?.invoke(idx, max) ?: false

  suspend fun prompts(): List<Prompt> = withContext(Dispatchers.IO) { dao.all() }

  suspend fun addPrompt(title: String, system: String, vehikel: String?): Long =
    withContext(Dispatchers.IO) { dao.insert(Prompt(title = title, systemText = system, vehikel = vehikel)) }

  suspend fun addPromptExtended(
    title: String,
    system: String,
    vehikel: String?,
    useSearch: Boolean,
    thinkingBudget: Int?,
    thinkingEnabled: Boolean,
    useOpenAI: Boolean = false,
    isMailPrompt: Boolean = false,
    useMemoryList: Boolean = false,
    sendWebhook: Boolean = false,
    webhookToken: String? = null,
    webhookUrl: String? = null,
    webhookRawOnly: Boolean = false
  ): Long =
    withContext(Dispatchers.IO) {
      dao.insert(
        Prompt(
          title = title,
          systemText = system,
          vehikel = vehikel,
          useGoogleSearch = useSearch,
          thinkingBudget = thinkingBudget,
          thinkingEnabled = thinkingEnabled,
          useOpenAI = useOpenAI,
          isMailPrompt = isMailPrompt,
          useMemoryList = useMemoryList,
          sendWebhook = sendWebhook,
          webhookToken = webhookToken,
          webhookUrl = webhookUrl,
          webhookRawOnly = webhookRawOnly
        )
      )
    }

  suspend fun updatePrompt(p: Prompt) =
    withContext(Dispatchers.IO) { dao.update(p) }

  suspend fun deletePrompt(p: Prompt) =
    withContext(Dispatchers.IO) { dao.delete(p) }

  // Memory methods
  suspend fun memoryItems(): List<MemoryItem> = withContext(Dispatchers.IO) {
    val items = memoryDao.getAllMemoryItems().first()
    android.util.Log.d("MainViewModel", "Loaded ${items.size} memory items")
    items
  }

  suspend fun addMemoryItem(title: String, content: String): Long =
    withContext(Dispatchers.IO) { memoryDao.insertMemoryItem(MemoryItem(title = title, content = content)) }

  suspend fun deleteMemoryItem(item: MemoryItem) =
    withContext(Dispatchers.IO) { memoryDao.deleteMemoryItem(item) }

  suspend fun updateMemoryItem(item: MemoryItem) =
    withContext(Dispatchers.IO) { memoryDao.insertMemoryItem(item) } // insert with REPLACE strategy acts as upsert

  // Upsert: om titel finns, uppdatera; annars skapa
  suspend fun upsertPromptByTitle(title: String, system: String, vehikel: String?) {
    withContext(Dispatchers.IO) {
      val existing = dao.byTitle(title)
      if (existing == null) {
        dao.insert(Prompt(title = title, systemText = system, vehikel = vehikel))
      } else {
        dao.update(existing.copy(systemText = system, vehikel = vehikel))
      }
    }
  }

  suspend fun callGemini(p: Prompt, apiKey: String, onRetry: ((Int) -> Unit)? = null): String {
    val system = buildString {
      if (!p.vehikel.isNullOrBlank()) appendLine(p.vehikel)
      append(p.systemText)
    }
    
    val appContext = getApplication<Application>()
    val prefs = appContext.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
    val apiProvider = prefs.getString(AiModelConfig.PREF_API_PROVIDER, AiModelConfig.API_PROVIDER_GOOGLE) ?: AiModelConfig.API_PROVIDER_GOOGLE
    val model = selectedGeminiModel()
    val isGemini35 = model == AiModelConfig.GEMINI_3_5_FLASH

    // 1. Google Gemini Config
    val req = GenerateContentRequest(
      systemInstruction = SystemInstruction(parts = listOf(Part(system))),
      contents = listOf(Content(role = "user", parts = listOf(Part(rawText)))),
      generationConfig = GenerationConfig(
        temperature = if (isGemini35) null else 0.3,
        thinkingConfig = if (isGemini35) {
          if (p.thinkingEnabled) ThinkingConfig(thinkingLevel = "HIGH") else ThinkingConfig(thinkingLevel = "MINIMAL")
        } else {
          if (p.thinkingEnabled) ThinkingConfig(thinkingBudget = null) else ThinkingConfig(thinkingBudget = 0)
        }
      ),
      tools = if (p.useGoogleSearch) listOf(Tool(googleSearch = GoogleSearch())) else null
    )

    // 2. OpenRouter Config
    val openRouterModel = AiModelConfig.mapToOpenRouterModel(model)
    val messagesList = buildList {
      if (system.isNotBlank()) {
        add(OpenRouterMessage(role = "system", content = system))
      }
      add(OpenRouterMessage(role = "user", content = rawText))
    }
    val openRouterTemp = if (isGemini35) null else 0.3
    val openRouterReasoningObj = when {
      isGemini35 -> {
        if (p.thinkingEnabled) OpenRouterReasoning(effort = "high") else OpenRouterReasoning(effort = "minimal")
      }
      model == AiModelConfig.GEMINI_2_5_FLASH -> {
        if (p.thinkingEnabled) null else OpenRouterReasoning(max_tokens = 0)
      }
      else -> null
    }
    val openRouterReq = OpenRouterChatRequest(
      model = openRouterModel,
      messages = messagesList,
      temperature = openRouterTemp,
      reasoning = openRouterReasoningObj
    )

    return withContext(Dispatchers.IO) {
      var lastError: Throwable? = null
      repeat(3) { attempt ->
        try {
          val txt = if (apiProvider == AiModelConfig.API_PROVIDER_OPENROUTER) {
            val authHeader = "Bearer $apiKey"
            val resp = openRouter.chatCompletions(authHeader, openRouterReq)
            resp.choices.firstOrNull()?.message?.content.orEmpty()
          } else {
            val resp = gemini.generateContent(model, apiKey, req)
            resp.candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text.orEmpty()
          }
          if (txt.isNotBlank()) return@withContext txt
        } catch (t: Throwable) {
          lastError = t
          DebugLogger.log(appContext, "MainViewModel", "API Error (Attempt ${attempt+1})", t)
          // If model rejects Search Grounding (400 INVALID_ARGUMENT), stop retrying immediately
          val msg = t.message.orEmpty()
          if (msg.contains("Search Grounding is not supported", ignoreCase = true)) {
            return@withContext appContext.getString(R.string.error_search_grounding_not_supported)
          }
        }
        if (attempt < 2) {
          try { onRetry?.invoke(attempt + 1) } catch (_: Throwable) {}
        }
        // small backoff
        try { kotlinx.coroutines.delay(300L * (attempt + 1)) } catch (_: Throwable) {}
      }
      lastError?.let {
        val prefix = appContext.getString(R.string.ai_request_error_prefix)
        val detail = it.message ?: it::class.java.simpleName
        "$prefix $detail"
      } ?: ""
    }
  }

  suspend fun callGeminiForMail(p: Prompt, apiKey: String, rawText: String, onRetry: ((Int) -> Unit)? = null): MailResult {
    val appContext = getApplication<Application>()
    val prefs = appContext.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
    val apiProvider = prefs.getString(AiModelConfig.PREF_API_PROVIDER, AiModelConfig.API_PROVIDER_GOOGLE) ?: AiModelConfig.API_PROVIDER_GOOGLE
    val model = selectedGeminiModel()
    val isGemini35 = model == AiModelConfig.GEMINI_3_5_FLASH

    val system = buildString {
      // Dold systemdel för mail-prompt - nu lokaliserad
      appendLine(appContext.getString(R.string.mail_system_prompt_intro))
      appendLine()
      appendLine(appContext.getString(R.string.mail_system_prompt_step1))
      appendLine(appContext.getString(R.string.mail_system_prompt_step2))
      appendLine()
      appendLine(appContext.getString(R.string.mail_system_prompt_format))
      appendLine(appContext.getString(R.string.mail_system_prompt_email_to))
      appendLine(appContext.getString(R.string.mail_system_prompt_subject))
      appendLine(appContext.getString(R.string.mail_system_prompt_body))
      appendLine()
      appendLine(appContext.getString(R.string.mail_system_prompt_instructions))
      appendLine()
      if (!p.vehikel.isNullOrBlank()) appendLine(p.vehikel)
      append(p.systemText)
    }
    
    // 1. Google Gemini Config
    val req = GenerateContentRequest(
      systemInstruction = SystemInstruction(parts = listOf(Part(system))),
      contents = listOf(Content(role = "user", parts = listOf(Part(rawText)))),
      generationConfig = GenerationConfig(
        temperature = if (isGemini35) null else 0.3,
        thinkingConfig = if (isGemini35) {
          ThinkingConfig(thinkingLevel = "HIGH") // Alltid thinking för mail
        } else {
          ThinkingConfig(thinkingBudget = null) // Alltid thinking för mail
        }
      ),
      tools = listOf(Tool(googleSearch = GoogleSearch())) // Alltid grounding för mail
    )
    
    // 2. OpenRouter Config
    val openRouterModel = AiModelConfig.mapToOpenRouterModel(model)
    val messagesList = buildList {
      if (system.isNotBlank()) {
        add(OpenRouterMessage(role = "system", content = system))
      }
      add(OpenRouterMessage(role = "user", content = rawText))
    }
    val openRouterTemp = if (isGemini35) null else 0.3
    val openRouterReasoningObj = when {
      isGemini35 -> {
        OpenRouterReasoning(effort = "high") // Alltid thinking för mail
      }
      model == AiModelConfig.GEMINI_2_5_FLASH -> {
        null // Alltid thinking för mail
      }
      else -> null
    }
    val openRouterReq = OpenRouterChatRequest(
      model = openRouterModel,
      messages = messagesList,
      temperature = openRouterTemp,
      reasoning = openRouterReasoningObj
    )

    val response = withContext(Dispatchers.IO) {
      var lastError: Throwable? = null
      repeat(3) { attempt ->
        try {
          val txt = if (apiProvider == AiModelConfig.API_PROVIDER_OPENROUTER) {
            val authHeader = "Bearer $apiKey"
            val resp = openRouter.chatCompletions(authHeader, openRouterReq)
            resp.choices.firstOrNull()?.message?.content.orEmpty()
          } else {
            val resp = gemini.generateContent(model, apiKey, req)
            resp.candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text.orEmpty()
          }
          if (txt.isNotBlank()) return@withContext txt
        } catch (t: Throwable) {
          lastError = t
          val msg = t.message.orEmpty()
          if (msg.contains("Search Grounding is not supported", ignoreCase = true)) {
            return@withContext appContext.getString(R.string.error_search_grounding_not_supported)
          }
        }
        if (attempt < 2) {
          try { onRetry?.invoke(attempt + 1) } catch (_: Throwable) {}
        }
        try { kotlinx.coroutines.delay(300L * (attempt + 1)) } catch (_: Throwable) {}
      }
      lastError?.let {
        val prefix = appContext.getString(R.string.ai_request_error_prefix)
        val detail = it.message ?: it::class.java.simpleName
        "$prefix $detail"
      } ?: ""
    }
    
    return parseMailResponse(response)
  }

  suspend fun sendWebhookIfEnabled(prompt: Prompt?, text: String): WebhookResult? {
    if (prompt == null || !prompt.sendWebhook) return null
    val appContext = getApplication<Application>()
    val token = prompt.webhookToken?.takeIf { it.isNotBlank() }
      ?: return WebhookResult(false, appContext.getString(R.string.webhook_error_missing_token))
    val url = prompt.webhookUrl?.takeIf { it.isNotBlank() }
      ?: return WebhookResult(false, "No webhook URL provided")

    return runCatching { WebhookClient.sendActivity(text, token, url) }
      .getOrElse { t ->
        WebhookResult(false, t.message ?: t::class.java.simpleName)
      }
  }

  private fun parseMailResponse(response: String): MailResult {
    try {
      // Försök hitta strukturerat format
      val emailToPattern = Pattern.compile("EMAIL_TO:\\s*([^\\n\\r]+)", Pattern.CASE_INSENSITIVE)
      val subjectPattern = Pattern.compile("SUBJECT:\\s*([^\\n\\r]+)", Pattern.CASE_INSENSITIVE)
      val bodyPattern = Pattern.compile("BODY:\\s*([\\s\\S]*)", Pattern.CASE_INSENSITIVE)
      
      val emailToMatcher = emailToPattern.matcher(response)
      val subjectMatcher = subjectPattern.matcher(response)
      val bodyMatcher = bodyPattern.matcher(response)
      
      val emailTo = if (emailToMatcher.find()) emailToMatcher.group(1)?.trim() else null
      val subject = if (subjectMatcher.find()) subjectMatcher.group(1)?.trim() else null
      val body = if (bodyMatcher.find()) bodyMatcher.group(1)?.trim() else null
      
      // Om vi fick alla fält, returnera dem
      if (!emailTo.isNullOrBlank() && !subject.isNullOrBlank() && !body.isNullOrBlank()) {
        return MailResult(emailTo, subject, body, response)
      }
      
      // Fallback: försök hitta email med regex
      val emailPattern = Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Z|a-z]{2,}\\b")
      val emailMatcher = emailPattern.matcher(response)
      val foundEmail = if (emailMatcher.find()) emailMatcher.group() else null
      
      if (!foundEmail.isNullOrBlank()) {
        // Använd hela responsen som body om vi hittade en email
        return MailResult(foundEmail, "AI Generated Email", response, response)
      }
      
      // Sista fallback: returnera tomma fält
      return MailResult(null, null, null, response)
      
    } catch (e: Exception) {
      return MailResult(null, null, null, response)
    }
  }
}
