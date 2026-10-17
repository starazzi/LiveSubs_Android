package pt.livelegendas

import java.text.Collator
import java.util.Locale

/** code = código ML Kit (também usado para o nome da língua); vosk = modelo de voz gratuito (null = sem modelo offline). */
data class Lang(val code: String, val vosk: String?) {
    val mlKit: String get() = code
}

object Langs {
    // Todas as línguas para onde o ML Kit traduz, offline e grátis (59)
    private val mlKitCodes = listOf(
        "af", "sq", "ar", "be", "bn", "bg", "ca", "zh", "hr", "cs", "da", "nl", "en", "eo", "et", "fi",
        "fr", "gl", "ka", "de", "el", "gu", "ht", "he", "hi", "hu", "is", "id", "ga", "it", "ja", "kn",
        "ko", "lv", "lt", "mk", "mr", "ms", "mt", "no", "fa", "pl", "pt", "ro", "ru", "sk", "sl", "es",
        "sw", "sv", "tl", "ta", "te", "th", "tr", "uk", "ur", "vi", "cy"
    )

    // Línguas que a app consegue OUVIR: as que têm modelo de voz Vosk gratuito (e que o ML Kit sabe traduzir)
    private val voskModels = mapOf(
        "en" to "vosk-model-small-en-us-0.15",
        "zh" to "vosk-model-small-cn-0.22",
        "ru" to "vosk-model-small-ru-0.22",
        "fr" to "vosk-model-small-fr-0.22",
        "de" to "vosk-model-small-de-0.15",
        "es" to "vosk-model-small-es-0.42",
        "pt" to "vosk-model-small-pt-0.3",
        "tr" to "vosk-model-small-tr-0.3",
        "vi" to "vosk-model-small-vn-0.4",
        "it" to "vosk-model-small-it-0.22",
        "nl" to "vosk-model-small-nl-0.22",
        "ca" to "vosk-model-small-ca-0.4",
        "ar" to "vosk-model-ar-mgb2-0.4",
        "fa" to "vosk-model-small-fa-0.42",
        "uk" to "vosk-model-small-uk-v3-nano",
        "sv" to "vosk-model-small-sv-rhasspy-0.15",
        "ja" to "vosk-model-small-ja-0.22",
        "eo" to "vosk-model-small-eo-0.42",
        "hi" to "vosk-model-small-hi-0.22",
        "cs" to "vosk-model-small-cs-0.4-rhasspy",
        "pl" to "vosk-model-small-pl-0.22",
        "ko" to "vosk-model-small-ko-0.22",
        "gu" to "vosk-model-small-gu-0.42",
        "te" to "vosk-model-small-te-0.42",
        "ka" to "vosk-model-small-ka-0.42",
    )

    val all: List<Lang> = mlKitCodes.map { Lang(it, voskModels[it]) }
    val sources: List<Lang> get() = all.filter { it.vosk != null }

    // O Whisper percebe 99 línguas: todas as do tradutor, exceto estas duas
    private val noWhisper = setOf("eo", "ga")

    /** Línguas que se podem ouvir com o motor escolhido ("vosk", "whisper_small", "whisper_base"). */
    fun sourcesFor(engine: String): List<Lang> =
        if (engine == "vosk") sources else all.filter { it.code !in noWhisper }

    fun byCode(code: String): Lang = all.firstOrNull { it.code == code } ?: all.first { it.code == "en" }

    /** Língua do telemóvel se for suportada, senão inglês. */
    fun systemCode(): String {
        val c = when (val l = Locale.getDefault().language) {
            "iw" -> "he"; "in" -> "id"; "nb", "nn" -> "no"; "fil" -> "tl"; else -> l
        }
        return if (mlKitCodes.contains(c)) c else "en"
    }

    private fun cap(s: String, loc: Locale) = s.replaceFirstChar { it.titlecase(loc) }

    /** Nome da língua [code] escrito na língua [uiCode], com o nome nativo entre parênteses. Ex.: Coreano (한국어) */
    fun nameIn(code: String, uiCode: String): String {
        val ui = Locale(uiCode)
        val own = Locale(code)
        val local = cap(own.getDisplayLanguage(ui), ui)
        val native = cap(own.getDisplayLanguage(own), own)
        return if (local.equals(native, ignoreCase = true)) local else "$local ($native)"
    }

    /** Lista ordenada alfabeticamente pelo nome escrito na língua [uiCode]. */
    fun sorted(list: List<Lang>, uiCode: String): List<Pair<Lang, String>> {
        val col = Collator.getInstance(Locale(uiCode))
        return list.map { it to nameIn(it.code, uiCode) }.sortedWith { a, b -> col.compare(a.second, b.second) }
    }
}
