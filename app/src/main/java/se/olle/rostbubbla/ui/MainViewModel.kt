
package se.olle.rostbubbla.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import se.olle.rostbubbla.data.AppDb
import se.olle.rostbubbla.data.Prompt
import se.olle.rostbubbla.net.*
import se.olle.rostbubbla.speech.SpeechRepo

class MainViewModel(app: Application): AndroidViewModel(app) {
  private val db = AppDb.get(app)
  private val dao = db.promptDao()
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

  suspend fun addPromptExtended(title: String, system: String, vehikel: String?, useSearch: Boolean, thinkingBudget: Int?, thinkingEnabled: Boolean, useOpenAI: Boolean = false): Long =
    withContext(Dispatchers.IO) { dao.insert(Prompt(title = title, systemText = system, vehikel = vehikel, useGoogleSearch = useSearch, thinkingBudget = thinkingBudget, thinkingEnabled = thinkingEnabled, useOpenAI = useOpenAI)) }

  suspend fun updatePrompt(p: Prompt) =
    withContext(Dispatchers.IO) { dao.update(p) }

  suspend fun deletePrompt(p: Prompt) =
    withContext(Dispatchers.IO) { dao.delete(p) }

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
    val req = GenerateContentRequest(
      systemInstruction = SystemInstruction(parts = listOf(Part(system))),
      contents = listOf(Content(role = "user", parts = listOf(Part(rawText)))),
      generationConfig = GenerationConfig(
        temperature = 0.3,
        thinkingConfig = if (p.thinkingEnabled) ThinkingConfig(thinkingBudget = null) else ThinkingConfig(thinkingBudget = 0)
      ),
      tools = if (p.useGoogleSearch) listOf(Tool(googleSearch = GoogleSearch())) else null
    )
    return withContext(Dispatchers.IO) {
      var lastError: Throwable? = null
      repeat(3) { attempt ->
        try {
          val resp = gemini.generateContent("gemini-2.5-flash", apiKey, req)
          val txt = resp.candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text.orEmpty()
          if (txt.isNotBlank()) return@withContext txt
        } catch (t: Throwable) {
          lastError = t
          // If model rejects Search Grounding (400 INVALID_ARGUMENT), stop retrying immediately
          val msg = t.message ?: ""
          if (msg.contains("Search Grounding is not supported", ignoreCase = true)) {
            return@withContext "AI error: Search Grounding not supported for this model/account"
          }
        }
        if (attempt < 2) {
          try { onRetry?.invoke(attempt + 1) } catch (_: Throwable) {}
        }
        // small backoff
        try { kotlinx.coroutines.delay(300L * (attempt + 1)) } catch (_: Throwable) {}
      }
      lastError?.let { "Fel vid AI-anrop: ${it.message}" } ?: ""
    }
  }
}
