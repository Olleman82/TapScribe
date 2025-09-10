
package se.olle.rostbubbla.net

import retrofit2.http.*
import com.squareup.moshi.Json

interface GeminiApi {
  @POST("v1beta/models/{model}:generateContent")
  suspend fun generateContent(
    @Path("model") model: String,
    @Query("key") apiKey: String,
    @Body body: GenerateContentRequest
  ): GenerateContentResponse
}

data class GenerateContentRequest(
  @Json(name = "systemInstruction") val systemInstruction: SystemInstruction? = null,
  @Json(name = "contents") val contents: List<Content>,
  @Json(name = "generationConfig") val generationConfig: GenerationConfig? = null
)
data class SystemInstruction(@Json(name = "parts") val parts: List<Part>)
data class Content(@Json(name = "role") val role: String, @Json(name = "parts") val parts: List<Part>)
data class Part(@Json(name = "text") val text: String)
data class GenerationConfig(
  @Json(name = "temperature") val temperature: Double? = null,
  @Json(name = "thinkingConfig") val thinkingConfig: ThinkingConfig? = null
)
data class ThinkingConfig(
  @Json(name = "thinkingBudget") val thinkingBudget: Int? = null
)

data class GenerateContentResponse(val candidates: List<Candidate>)
data class Candidate(val content: Content)
