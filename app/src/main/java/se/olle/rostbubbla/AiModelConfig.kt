package se.olle.rostbubbla

object AiModelConfig {
  const val PREF_GEMINI_MODEL = "gemini_model"
  const val GEMINI_2_5_FLASH = "gemini-2.5-flash"
  const val GEMINI_3_1_FLASH_LITE = "gemini-3.1-flash-lite"
  const val GEMINI_3_5_FLASH = "gemini-3.5-flash"

  const val PREF_API_PROVIDER = "api_provider"
  const val API_PROVIDER_GOOGLE = "google"
  const val API_PROVIDER_OPENROUTER = "openrouter"

  val geminiModels = listOf(
    GEMINI_2_5_FLASH,
    GEMINI_3_1_FLASH_LITE,
    GEMINI_3_5_FLASH
  )

  fun normalizeGeminiModel(model: String?): String =
    if (model in geminiModels) model.orEmpty() else GEMINI_2_5_FLASH

  fun geminiModelLabel(model: String): String = when (normalizeGeminiModel(model)) {
    GEMINI_3_5_FLASH -> "Gemini 3.5 Flash"
    GEMINI_3_1_FLASH_LITE -> "Gemini 3.1 Flash-Lite"
    else -> "Gemini 2.5 Flash"
  }

  fun mapToOpenRouterModel(model: String): String = when (normalizeGeminiModel(model)) {
    GEMINI_3_5_FLASH -> "google/gemini-3.5-flash"
    GEMINI_3_1_FLASH_LITE -> "google/gemini-3.1-flash-lite"
    else -> "google/gemini-2.5-flash"
  }
}
