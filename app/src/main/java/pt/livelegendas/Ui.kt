package pt.livelegendas

import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.Locale

/**
 * Textos da interface. 9 línguas vêm traduzidas à mão (res/values-xx).
 * Para as restantes línguas do ML Kit, os textos em inglês são traduzidos offline
 * na primeira utilização (precisa de internet uma vez) e ficam guardados.
 * Se nada disto for possível, usa inglês.
 */
object Ui {
    private val bundled = setOf("en", "pt", "es", "fr", "ru", "ko", "ja", "zh", "ro")

    private val ids = intArrayOf(
        R.string.desc, R.string.my_lang, R.string.stream_lang, R.string.show_orig, R.string.appearance,
        R.string.preview, R.string.text_color, R.string.text_size, R.string.bg_color, R.string.bg_opacity,
        R.string.start, R.string.stop, R.string.ready, R.string.howto, R.string.st_overlay, R.string.st_audio,
        R.string.st_dl_voice, R.string.st_unzip, R.string.st_dl_tr, R.string.st_tr_fail, R.string.st_voice_fail,
        R.string.st_auth, R.string.st_active, R.string.st_cancel, R.string.st_stopped, R.string.svc_loading,
        R.string.svc_listening, R.string.svc_error, R.string.notif_title, R.string.notif_text,
        R.string.engine_label, R.string.engine_small, R.string.engine_base, R.string.engine_vosk, R.string.online_tr, R.string.model_hint, R.string.engine_tiny
    )

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("ui_mt", Context.MODE_PRIVATE)
    private fun needsMt() = Langs.systemCode() !in bundled

    private fun lookup(ctx: Context, id: Int): String {
        if (needsMt()) {
            val v = prefs(ctx).getString(Langs.systemCode() + ":" + ctx.resources.getResourceEntryName(id), null)
            if (v != null) return v
        }
        return ctx.getString(id)
    }

    /** Texto na língua do telemóvel (ou inglês). Aceita argumentos de formato (%1$s, %1$d). */
    fun s(ctx: Context, id: Int, vararg args: Any?): String {
        val base = lookup(ctx, id)
        if (args.isEmpty()) return base
        return try { String.format(base, *args) } catch (e: Exception) { ctx.getString(id, *args) }
    }

    private fun placeholdersOk(en: String, out: String): Boolean {
        if (!Regex("%\\d\\$[sd]").findAll(en).all { out.contains(it.value) }) return false
        return !en.contains("%%") || out.contains("%%")
    }

    /** Traduz os textos em inglês para a língua do telemóvel, se ainda não estiver feito. */
    fun ensureTranslated(ctx: Context, onDone: () -> Unit) {
        if (!needsMt()) return
        val app = ctx.applicationContext
        val code = Langs.systemCode()
        val p = prefs(app)
        if (p.getBoolean("done:$code", false)) return
        Thread {
            val tr = Translation.getClient(
                TranslatorOptions.Builder().setSourceLanguage("en").setTargetLanguage(code).build()
            )
            try {
                Tasks.await(tr.downloadModelIfNeeded(DownloadConditions.Builder().build()))
                val cfg = Configuration(app.resources.configuration).apply { setLocale(Locale.ENGLISH) }
                val en = app.createConfigurationContext(cfg)
                val ed = p.edit()
                for (id in ids) {
                    val src = en.getString(id)
                    val out = Tasks.await(tr.translate(src))
                    if (out.isNotBlank() && placeholdersOk(src, out)) {
                        ed.putString("$code:" + app.resources.getResourceEntryName(id), out)
                    }
                }
                ed.putBoolean("done:$code", true).apply()
                Handler(Looper.getMainLooper()).post(onDone)
            } catch (e: Exception) {
                // sem internet: fica em inglês e tenta outra vez no próximo arranque
            } finally {
                tr.close()
            }
        }.start()
    }
}
