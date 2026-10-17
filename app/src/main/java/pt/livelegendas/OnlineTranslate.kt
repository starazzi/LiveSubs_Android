package pt.livelegendas

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Tradução online gratuita, sem chave (endpoint web do Google Translate). É não oficial e pode
 * deixar de funcionar ou ser limitado: nesse caso devolve null e a app usa o ML Kit offline.
 */
object OnlineTranslate {
    private fun code(c: String) = when (c) { "zh" -> "zh-CN"; "he" -> "iw"; else -> c }

    fun translate(text: String, from: String, to: String): String? {
        return try {
            val url = URL(
                "https://translate.googleapis.com/translate_a/single?client=gtx&dt=t&sl=${code(from)}&tl=${code(to)}&q=" +
                    URLEncoder.encode(text, "UTF-8")
            )
            val c = url.openConnection() as HttpURLConnection
            c.connectTimeout = 3000
            c.readTimeout = 4000
            c.setRequestProperty("User-Agent", "Mozilla/5.0")
            if (c.responseCode != 200) return null
            val arr = JSONArray(c.inputStream.bufferedReader().use { it.readText() }).getJSONArray(0)
            val sb = StringBuilder()
            for (i in 0 until arr.length()) sb.append(arr.getJSONArray(i).optString(0))
            sb.toString().trim().ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }
}
