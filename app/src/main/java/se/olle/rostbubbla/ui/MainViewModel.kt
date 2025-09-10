
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
          HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY }
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

  suspend fun callGemini(p: Prompt, apiKey: String): String {
    return try {
      val system = buildString {
        if (!p.vehikel.isNullOrBlank()) appendLine(p.vehikel)
        append(p.systemText)
      }
      val req = GenerateContentRequest(
        systemInstruction = SystemInstruction(parts = listOf(Part(system))),
        contents = listOf(Content(role = "user", parts = listOf(Part(rawText)))),
        generationConfig = GenerationConfig(
          temperature = 0.3,
          thinkingConfig = ThinkingConfig(thinkingBudget = 0)
        )
      )
      val resp = withContext(Dispatchers.IO) {
        gemini.generateContent("gemini-2.5-flash", apiKey, req)
      }
      resp.candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text?.ifBlank { "(Tomt svar)" }.orEmpty()
    } catch (t: Throwable) {
      "Fel vid AI-anrop: ${t.message}"
    }
  }
}
