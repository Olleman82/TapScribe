package se.olle.rostbubbla.net

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class WebhookRequest(val text: String)
data class WebhookResponsePayload(
  val company: String?,
  val message: String?,
  val success: Boolean? = null,
  val title: String?
)

data class WebhookResult(
  val ok: Boolean,
  val summary: String
)

object WebhookClient {
  private val client = OkHttpClient.Builder()
    .callTimeout(120, TimeUnit.SECONDS) // Webhook kan ta tid (selenium-automation)
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(120, TimeUnit.SECONDS)
    .build()

  private val moshi = Moshi.Builder()
    .add(KotlinJsonAdapterFactory())
    .build()
  private val requestAdapter = moshi.adapter(WebhookRequest::class.java)
  private val responseAdapter = moshi.adapter(WebhookResponsePayload::class.java)

  suspend fun sendActivity(text: String, token: String, url: String): WebhookResult = withContext(Dispatchers.IO) {
    val bodyJson = requestAdapter.toJson(WebhookRequest(text = text))
    val body = bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType())
    val request = Request.Builder()
      .url(url)
      .header("Content-Type", "application/json")
      .header("X-Webhook-Token", token)
      .post(body)
      .build()

    val response = client.newCall(request).execute()
    val raw = response.body?.string().orEmpty()
    val parsed = runCatching { responseAdapter.fromJson(raw) }.getOrNull()
    val ok = response.isSuccessful && (parsed?.success != false)
    val summary = parsed?.let { listOfNotNull(it.title, it.company, it.message).joinToString(" — ") }.orEmpty()

    WebhookResult(
      ok = ok,
      summary = summary.ifBlank { response.message.ifBlank { raw.take(120) } }
    )
  }
}


