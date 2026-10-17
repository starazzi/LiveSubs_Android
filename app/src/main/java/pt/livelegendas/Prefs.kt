package pt.livelegendas

import android.content.Context

class Prefs(ctx: Context) {
    val sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var src: String
        get() = sp.getString("src", "ko")!!
        set(v) = sp.edit().putString("src", v).apply()
    var tgt: String
        get() = sp.getString("tgt", null) ?: Langs.systemCode()
        set(v) = sp.edit().putString("tgt", v).apply()
    var engine: String
        get() = sp.getString("engine", "whisper_small")!!
        set(v) = sp.edit().putString("engine", v).apply()
    var onlineTr: Boolean
        get() = sp.getBoolean("onlineTr", true)
        set(v) = sp.edit().putBoolean("onlineTr", v).apply()
    var showOrig: Boolean
        get() = sp.getBoolean("orig", true)
        set(v) = sp.edit().putBoolean("orig", v).apply()
    var textColor: Int
        get() = sp.getInt("textColor", 0xFFFFFFFF.toInt())
        set(v) = sp.edit().putInt("textColor", v).apply()
    var bgColor: Int
        get() = sp.getInt("bgColor", 0xFF000000.toInt())
        set(v) = sp.edit().putInt("bgColor", v).apply()
    var textSize: Int
        get() = sp.getInt("textSize", 18)
        set(v) = sp.edit().putInt("textSize", v).apply()
    var bgAlpha: Int
        get() = sp.getInt("bgAlpha", 70)
        set(v) = sp.edit().putInt("bgAlpha", v).apply()

    /** Cor de fundo com a opacidade escolhida. */
    val bgArgb: Int get() = ((bgAlpha * 255 / 100) shl 24) or (bgColor and 0xFFFFFF)
}
