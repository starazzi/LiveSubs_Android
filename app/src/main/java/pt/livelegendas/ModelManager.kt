package pt.livelegendas

import android.content.Context
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** Descarrega (uma só vez) os modelos de voz gratuitos para o armazenamento da app. */
object ModelManager {
    private fun root(ctx: Context) = File(ctx.filesDir, "models").apply { mkdirs() }
    fun modelDir(ctx: Context, name: String) = File(root(ctx), name)
    private fun isInstalled(ctx: Context, name: String) =
        File(root(ctx), "$name.ok").exists() && modelDir(ctx, name).isDirectory

    private fun whisperName(engine: String) = when (engine) {
        "whisper_tiny" -> "ggml-tiny-q5_1.bin"
        "whisper_base" -> "ggml-base-q5_1.bin"
        else -> "ggml-small-q5_1.bin"
    }
    fun whisperFile(ctx: Context, engine: String) = File(root(ctx), whisperName(engine))

    /** Descarrega o que o motor de voz escolhido precisa. onProgress(%) — -1 = a descomprimir. */
    @Throws(Exception::class)
    fun ensureFor(ctx: Context, modelId: String, onProgress: (Int) -> Unit) {
        if (modelId.startsWith("whisper_")) ensureWhisper(ctx, modelId, onProgress)
        else ensure(ctx, modelId, onProgress)
    }

    @Throws(Exception::class)
    private fun download(url: String, dest: File, onProgress: (Int) -> Unit) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000; readTimeout = 30000; instanceFollowRedirects = true
            setRequestProperty("User-Agent", "LiveSubs")
        }
        try {
            conn.connect()
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            var done = 0L
            conn.inputStream.use { input ->
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var last = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val p = (done * 100 / total).toInt()
                            if (p != last) { last = p; onProgress(p) }
                        }
                    }
                }
            }
            if (total > 0 && done != total) throw IOException("Download incompleto")
        } finally {
            conn.disconnect()
        }
    }

    @Throws(Exception::class)
    fun ensureWhisper(ctx: Context, engine: String, onProgress: (Int) -> Unit) {
        val f = whisperFile(ctx, engine)
        val ok = File(root(ctx), f.name + ".ok")
        if (f.exists() && ok.exists()) return
        val tmp = File(root(ctx), f.name + ".part")
        download("https://huggingface.co/ggerganov/whisper.cpp/resolve/main/${f.name}", tmp, onProgress)
        f.delete()
        if (!tmp.renameTo(f)) throw IOException("Não foi possível guardar o modelo")
        ok.writeText("ok")
    }

    @Throws(Exception::class)
    fun ensure(ctx: Context, name: String, onProgress: (Int) -> Unit) {
        if (isInstalled(ctx, name)) return
        val root = root(ctx)
        val tmp = File(root, "$name.zip.part")
        download("https://alphacephei.com/vosk/models/$name.zip", tmp, onProgress)
        onProgress(-1)
        modelDir(ctx, name).deleteRecursively()
        val rootPath = root.canonicalPath + File.separator
        ZipInputStream(BufferedInputStream(FileInputStream(tmp))).use { zin ->
            var e = zin.nextEntry
            while (e != null) {
                val f = File(root, e.name)
                if (!f.canonicalPath.startsWith(rootPath)) throw IOException("Zip inválido")
                if (e.isDirectory) f.mkdirs() else {
                    f.parentFile?.mkdirs()
                    FileOutputStream(f).use { zin.copyTo(it) }
                }
                e = zin.nextEntry
            }
        }
        tmp.delete()
        if (!modelDir(ctx, name).isDirectory) throw IOException("Modelo não encontrado após descompressão")
        File(root, "$name.ok").writeText("ok")
    }
}
