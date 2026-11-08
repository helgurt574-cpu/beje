package com.example.offlinedikte

import androidx.annotation.StringRes

/**
 * Konuşma tanıma dilleri. Her dilin modeli Google Play'den isteğe bağlı indirilen ayrı bir
 * asset pack (`packName`) olarak gelir ya da kullanıcı tarafından bir klasörden içe aktarılır.
 */
enum class SpeechModelProfile(
    val code: String,
    val folderName: String,
    val packName: String,
    val approxSizeMb: Int,
    @StringRes val labelRes: Int,
    val isLarge: Boolean = false
) {
    TURKISH("tr", "tr", "model_tr", 57, R.string.lang_tr),
    FARSI("fa", "fa", "model_fa", 97, R.string.lang_fa),
    ARABIC("ar", "ar", "model_ar", 666, R.string.lang_ar, isLarge = true);

    fun normalize(text: String): String {
        return when (this) {
            TURKISH -> text
                .replace(Regex("\\bic\\b"), "üç")
                .replace(Regex("\\bIc\\b"), "Üç")
                .trim()
            ARABIC -> text
                .replace('ی', 'ي')
                .replace('ک', 'ك')
                .replace(Regex("[\\u064B-\\u065F\\u0670\\u06D6-\\u06ED]"), "")
                .replace('ـ', ' ')
                .replace(Regex("\\s+"), " ")
                .trim()
            FARSI -> text
                .replace('ي', 'ی')
                .replace('ك', 'ک')
                .replace(Regex("\\s+"), " ")
                .trim()
        }
    }

    companion object {
        fun fromCode(code: String?): SpeechModelProfile? = entries.firstOrNull { it.code == code }

        fun fromPackName(packName: String): SpeechModelProfile? =
            entries.firstOrNull { it.packName == packName }
    }
}
