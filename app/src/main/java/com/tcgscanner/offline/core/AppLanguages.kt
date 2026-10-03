package com.tcgscanner.offline.core

/** The interface languages the app ships. Names are shown in their own language so anyone can find theirs. */
object AppLanguages {
    data class Language(
        /** BCP-47 tag handed to AppCompatDelegate.setApplicationLocales. */
        val tag: String,
        /** Language code used for the res/values-xx folder. */
        val code: String,
        val nativeName: String
    )

    val all: List<Language> = listOf(
        Language("es", "es", "Español"),
        Language("en", "en", "English"),
        Language("fr", "fr", "Français"),
        Language("de", "de", "Deutsch"),
        Language("pt", "pt", "Português"),
        Language("zh-CN", "zh", "中文"),
        Language("ja", "ja", "日本語"),
        Language("ru", "ru", "Русский"),
        Language("hi", "hi", "हिन्दी")
    )

    /** The shipped language that matches a locale's language code ("zh" -> zh-CN), or null (system default / unsupported). */
    fun matching(languageCode: String?): Language? =
        languageCode?.lowercase()?.let { c -> all.firstOrNull { it.code == c } }
}
