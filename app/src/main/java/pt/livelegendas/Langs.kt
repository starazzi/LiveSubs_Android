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

    // Modelos Vosk por língua (nome, MB). Só os que funcionam em telemóveis: até 150 MB.
    // (Os modelos grandes de 300 MB a 2 GB precisam de vários GB de RAM e não correm no Android.)
    private val voskCatalog = mapOf(
        "en" to listOf("vosk-model-small-en-us-0.15" to 40, "vosk-model-en-us-0.22-lgraph" to 128),
        "zh" to listOf("vosk-model-small-cn-0.22" to 42),
        "ru" to listOf("vosk-model-small-ru-0.22" to 45),
        "fr" to listOf("vosk-model-small-fr-0.22" to 41),
        "de" to listOf("vosk-model-small-de-0.15" to 45),
        "es" to listOf("vosk-model-small-es-0.42" to 39),
        "pt" to listOf("vosk-model-small-pt-0.3" to 31),
        "tr" to listOf("vosk-model-small-tr-0.3" to 35),
        "vi" to listOf("vosk-model-small-vn-0.4" to 32, "vosk-model-vn-0.4" to 78),
        "it" to listOf("vosk-model-small-it-0.22" to 48),
        "nl" to listOf("vosk-model-small-nl-0.22" to 39),
        "ca" to listOf("vosk-model-small-ca-0.4" to 42),
        "fa" to listOf("vosk-model-small-fa-0.42" to 53),
        "uk" to listOf("vosk-model-small-uk-v3-nano" to 73, "vosk-model-small-uk-v3-small" to 133),
        "ja" to listOf("vosk-model-small-ja-0.22" to 48),
        "eo" to listOf("vosk-model-small-eo-0.42" to 42),
        "hi" to listOf("vosk-model-small-hi-0.22" to 42),
        "cs" to listOf("vosk-model-small-cs-0.4-rhasspy" to 44),
        "pl" to listOf("vosk-model-small-pl-0.22" to 50),
        "ko" to listOf("vosk-model-small-ko-0.22" to 82),
        "gu" to listOf("vosk-model-small-gu-0.42" to 100),
        "te" to listOf("vosk-model-small-te-0.42" to 58),
        "ka" to listOf("vosk-model-small-ka-0.42" to 45),
    )

    val all: List<Lang> = mlKitCodes.map { Lang(it, voskCatalog[it]?.firstOrNull()?.first) }
    val sources: List<Lang> get() = all.filter { it.vosk != null }

    // O Whisper percebe 99 línguas: todas as do tradutor, exceto estas duas
    private val noWhisper = setOf("eo", "ga")

    /** Línguas que se podem ouvir com o motor escolhido ("vosk", "whisper_small", "whisper_base"). */
    fun sourcesFor(engine: String): List<Lang> =
        if (engine == "vosk") sources else all.filter { it.code !in noWhisper }

    /** Línguas que se podem ouvir: as que têm pelo menos um modelo (Vosk ou Whisper). */
    fun sourcesAll(): List<Lang> = all.filter { it.code !in noWhisper || voskCatalog.containsKey(it.code) }

    /** Modelo de voz: id = nome do modelo Vosk, ou whisper_tiny / whisper_base / whisper_small. */
    data class ModelOpt(val id: String, val mb: Int)

    /** Todos os modelos que o utilizador pode escolher para esta língua, do mais leve ao mais preciso. */
    fun optionsFor(code: String): List<ModelOpt> {
        val out = ArrayList<ModelOpt>()
        voskCatalog[code]?.forEach { out += ModelOpt(it.first, it.second) }
        if (code !in noWhisper) {
            out += ModelOpt("whisper_tiny", 31)
            out += ModelOpt("whisper_base", 60)
            out += ModelOpt("whisper_small", 190)
        }
        return out
    }

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
