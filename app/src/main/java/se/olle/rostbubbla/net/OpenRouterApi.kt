package se.olle.rostbubbla.net

import retrofit2.http.*
import com.squareup.moshi.Json

interface OpenRouterApi {
  @POST("chat/completions")
  suspend fun chatCompletions(
    @Header("Authorization") authHeader: String,
    @Body body: OpenRouterChatRequest
  ): OpenRouterChatResponse
}

data class OpenRouterChatRequest(
  @Json(name = "model") val model: String,
  @Json(name = "messages") val messages: List<OpenRouterMessage>,
  @Json(name = "temperature") val temperature: Double? = null,
  @Json(name = "reasoning") val reasoning: OpenRouterReasoning? = null
)

data class OpenRouterMessage(
  @Json(name = "role") val role: String,
  @Json(name = "content") val content: String
)

data class OpenRouterReasoning(
  @Json(name = "effort") val effort: String? = null,
  @Json(name = "max_tokens") val max_tokens: Int? = null
)

data class OpenRouterChatResponse(
  @Json(name = "choices") val choices: List<OpenRouterChoice>
)

data class OpenRouterChoice(
  @Json(name = "message") val message: OpenRouterMessage
)
