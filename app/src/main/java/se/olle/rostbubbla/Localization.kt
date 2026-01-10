package se.olle.rostbubbla

import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * Supported application languages. The `localeTag` maps to the locale list
 * that should be applied through AppCompat.
 */
enum class AppLanguage(
  @StringRes val labelRes: Int,
  val prefValue: String,
  private val localeTag: String?
) {
  SYSTEM(R.string.language_option_system, "system", null),
  ENGLISH(R.string.language_option_english, "en", "en"),
  SWEDISH(R.string.language_option_swedish, "sv", "sv");

  companion object {
    fun fromPref(value: String?): AppLanguage =
      entries.firstOrNull { it.prefValue == value } ?: SYSTEM
  }

  fun localeList(): LocaleListCompat =
    localeTag?.let { LocaleListCompat.forLanguageTags(it) } ?: LocaleListCompat.getEmptyLocaleList()
}

const val PREF_APP_LANGUAGE = "app_language"

fun applyAppLanguage(language: AppLanguage) {
  AppCompatDelegate.setApplicationLocales(language.localeList())
}
