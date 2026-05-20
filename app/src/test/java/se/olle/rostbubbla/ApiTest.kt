package se.olle.rostbubbla

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import se.olle.rostbubbla.net.OpenRouterApi
import se.olle.rostbubbla.net.OpenRouterChatRequest
import se.olle.rostbubbla.net.OpenRouterMessage
import se.olle.rostbubbla.net.OpenRouterReasoning
import java.io.File

class ApiTest {

  @Test
  fun testOpenRouterCall() = runBlocking {
    // 1. Read API key from env.local
    val envFile = File("D:/Appar/Empir_bild/.env.local")
    assertTrue("env.local must exist at D:/Appar/Empir_bild/.env.local", envFile.exists())
    
    val openRouterKey = envFile.readLines()
      .firstOrNull { it.startsWith("OPENROUTER_API_KEY=") }
      ?.substringAfter("OPENROUTER_API_KEY=")
      ?.trim()
      ?.removeSurrounding("\"", "\"")
      ?.removeSurrounding("'", "'")
      
    assertTrue("OPENROUTER_API_KEY must not be blank", !openRouterKey.isNullOrBlank())

    // 2. Initialize Retrofit openRouter client
    val retrofit = Retrofit.Builder()
      .baseUrl("https://openrouter.ai/api/v1/")
      .client(
        OkHttpClient.Builder()
          .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY })
          .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
          .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
          .build()
      )
      .addConverterFactory(
        MoshiConverterFactory.create(
          Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        )
      )
      .build()
    val api = retrofit.create(OpenRouterApi::class.java)

    // 3. Make test request using Gemini 3.5 Flash mapping
    val openRouterModel = AiModelConfig.mapToOpenRouterModel(AiModelConfig.GEMINI_3_5_FLASH)
    val openRouterReq = OpenRouterChatRequest(
      model = openRouterModel,
      messages = listOf(
        OpenRouterMessage(role = "system", content = "Svara endast med ordet 'TapscribeTest'"),
        OpenRouterMessage(role = "user", content = "Hej")
      ),
      temperature = null,
      reasoning = OpenRouterReasoning(effort = "minimal")
    )

    val authHeader = "Bearer $openRouterKey"
    println("Making request to OpenRouter using model: $openRouterModel")
    val resp = api.chatCompletions(authHeader, openRouterReq)
    val responseText = resp.choices.firstOrNull()?.message?.content.orEmpty().trim()
    println("Received response: '$responseText'")
    
    assertTrue("Response must contain 'TapscribeTest'", responseText.contains("TapscribeTest", ignoreCase = true))
  }
}
