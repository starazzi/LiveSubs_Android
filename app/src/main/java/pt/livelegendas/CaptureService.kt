package pt.livelegendas

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Áudio do sistema (AudioPlaybackCapture) -> voz em texto (Whisper ou Vosk, offline)
 * -> tradução (Google online sem chave, com ML Kit offline como reserva) -> legenda flutuante.
 */
class CaptureService : Service() {
    companion object {
        const val ACTION_START = "pt.livelegendas.START"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_SRC = "src"
        const val EXTRA_TGT = "tgt"
        const val EXTRA_ENGINE = "engine"
        @Volatile var running = false
        private const val CHANNEL = "capture"
        private const val SR = 16000
        // frases que o Whisper inventa quando só há silêncio ou música
        private val NOISE = listOf(
            "субтитры сделал", "субтитры создавал", "продолжение следует", "thanks for watching",
            "thank you for watching", "시청해 주셔서", "시청해주셔서", "ご視聴ありがとう", "amara.org",
            "字幕由", "subtítulos realizados por"
        )
    }

    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var wm: WindowManager? = null
    private var overlay: TextView? = null
    private var worker: Thread? = null
    @Volatile private var active = false

    private lateinit var prefs: Prefs
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        main.post { applyStyle(); render(true) }
    }
    private var status = ""
    private val history = ArrayList<Pair<String, String>>()
    private var partialSrc = ""
    private var partialTr = ""
    private val gen = AtomicInteger()
    private var lastSent = ""
    private var lastSentTs = 0L
    private var lastFinal = ""
    @Volatile private var translator: Translator? = null
    private lateinit var srcLang: Lang
    private lateinit var tgtLang: Lang
    private val online = Executors.newSingleThreadExecutor()

    private val clearRunnable = Runnable {
        history.clear(); partialSrc = ""; partialTr = ""; status = ""; render(false)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START || running) return START_NOT_STICKY
        try {
            createChannel()
            val openPi = PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
            )
            val notif = NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(Ui.s(this, R.string.notif_title))
                .setContentText(Ui.s(this, R.string.notif_text))
                .setContentIntent(openPi)
                .setOngoing(true)
                .build()
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

            val code = intent.getIntExtra(EXTRA_CODE, 0)
            @Suppress("DEPRECATION")
            val data: Intent = intent.getParcelableExtra(EXTRA_DATA)!!
            prefs = Prefs(this)
            srcLang = Langs.byCode(intent.getStringExtra(EXTRA_SRC) ?: "ko")
            tgtLang = Langs.byCode(intent.getStringExtra(EXTRA_TGT) ?: "en")
            val engine = intent.getStringExtra(EXTRA_ENGINE) ?: "vosk"

            val mpm = getSystemService(MediaProjectionManager::class.java)
            projection = mpm.getMediaProjection(code, data).also {
                it.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() { main.post { stopSelf() } }
                }, main)
            }
            running = true
            active = true
            setupOverlay()
            worker = Thread { pipeline(engine) }.also { it.start() }
        } catch (e: Throwable) {
            Toast.makeText(this, Ui.s(this, R.string.svc_error, e.message), Toast.LENGTH_LONG).show()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, Ui.s(this, R.string.notif_title), NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    @SuppressLint("ClickableViewAccessibility")
    private fun setupOverlay() {
        val w = getSystemService(WindowManager::class.java)
        wm = w
        val tv = TextView(this).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(10))
            maxLines = 8; visibility = View.GONE
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM; y = dp(96) }
        var startY = 0
        var touchY = 0f
        tv.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> { startY = lp.y; touchY = ev.rawY }
                MotionEvent.ACTION_MOVE -> {
                    lp.y = (startY - (ev.rawY - touchY)).toInt().coerceAtLeast(0)
                    w.updateViewLayout(tv, lp)
                }
            }
            true
        }
        w.addView(tv, lp)
        overlay = tv
        applyStyle()
        prefs.sp.registerOnSharedPreferenceChangeListener(prefListener)
    }

    private fun applyStyle() {
        overlay?.apply {
            setTextColor(prefs.textColor)
            textSize = prefs.textSize.toFloat()
            setBackgroundColor(prefs.bgArgb)
        }
    }

    private fun postStatus(msg: String) { main.post { status = msg; render(true) } }

    private fun render(schedule: Boolean) {
        val tv = overlay ?: return
        val sb = SpannableStringBuilder()
        fun add(src: String, tr: String) {
            if (sb.isNotEmpty()) sb.append("\n")
            if (prefs.showOrig && src.isNotBlank()) {
                val s = sb.length
                sb.append(src).append("\n")
                sb.setSpan(RelativeSizeSpan(0.7f), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(ForegroundColorSpan(0xFFBBBBBB.toInt()), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            sb.append(tr)
        }
        if (history.isEmpty() && partialTr.isBlank() && status.isNotEmpty()) sb.append(status)
        history.forEach { add(it.first, it.second) }
        if (partialTr.isNotBlank()) add(partialSrc, partialTr)
        tv.text = sb
        tv.visibility = if (sb.isEmpty()) View.GONE else View.VISIBLE
        main.removeCallbacks(clearRunnable)
        if (schedule) main.postDelayed(clearRunnable, 8000)
    }

    // ---------- tradução ----------

    /** Traduz com o Google online (se ligado) e cai para o ML Kit offline se falhar. cb corre na thread principal. */
    private fun translate(text: String, cb: (String) -> Unit) {
        val offline = { translator?.translate(text)?.addOnSuccessListener { cb(it) }; Unit }
        if (prefs.onlineTr) {
            online.execute {
                val r = OnlineTranslate.translate(text, srcLang.code, tgtLang.code)
                main.post { if (r != null) cb(r) else offline() }
            }
        } else offline()
    }

    // Vosk: frases a meio (só ML Kit, rápido)
    private fun onPartial(text: String) {
        val now = SystemClock.elapsedRealtime()
        if (text.isBlank() || text == lastSent || now - lastSentTs < 700) return
        lastSent = text; lastSentTs = now
        val g = gen.get()
        translator?.translate(text)?.addOnSuccessListener { tr ->
            if (g == gen.get()) { partialSrc = text; partialTr = tr; render(true) }
        }
    }

    // Frase completa (Vosk ou Whisper)
    private fun onFinal(text: String) {
        if (text.isBlank()) return
        gen.incrementAndGet()
        lastSent = ""
        translate(text) { tr ->
            history.add(text to tr)
            while (history.size > 2) history.removeAt(0)
            partialSrc = ""; partialTr = ""
            render(true)
        }
    }

    // ---------- captura de áudio ----------

    @SuppressLint("MissingPermission")
    private fun buildRecord(): AudioRecord {
        val cfg = AudioPlaybackCaptureConfiguration.Builder(projection!!)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SR).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
        val minBuf = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioRecord.Builder()
            .setAudioFormat(fmt)
            .setBufferSizeInBytes(maxOf(minBuf * 4, SR * 2))
            .setAudioPlaybackCaptureConfig(cfg)
            .build()
    }

    private fun pipeline(engine: String) {
        try {
            postStatus(Ui.s(this, R.string.svc_loading))
            val tr = Translation.getClient(
                TranslatorOptions.Builder().setSourceLanguage(srcLang.mlKit).setTargetLanguage(tgtLang.mlKit).build()
            )
            translator = tr
            Tasks.await(tr.downloadModelIfNeeded(DownloadConditions.Builder().build()))
            if (engine == "vosk") voskLoop() else whisperLoop(engine)
        } catch (e: Throwable) {
            postStatus(Ui.s(this, R.string.svc_error, e.message))
            main.postDelayed({ stopSelf() }, 6000)
        }
    }

    private fun voskLoop() {
        var model: Model? = null
        var rec: Recognizer? = null
        var record: AudioRecord? = null
        try {
            model = Model(ModelManager.modelDir(this, srcLang.vosk!!).absolutePath)
            rec = Recognizer(model, SR.toFloat())
            record = buildRecord()
            record.startRecording()
            postStatus(Ui.s(this, R.string.svc_listening))
            val buf = ShortArray(4000) // 250 ms
            while (active) {
                val n = record.read(buf, 0, buf.size)
                if (n < 0) break
                if (n == 0) continue
                if (rec.acceptWaveForm(buf, n)) onFinal(JSONObject(rec.result).optString("text"))
                else onPartial(JSONObject(rec.partialResult).optString("partial"))
            }
        } finally {
            try { record?.stop() } catch (_: Throwable) {}
            record?.release()
            rec?.close()
            model?.close()
        }
    }

    /** Remove ruído típico do Whisper ([Música], frases inventadas, repetições). */
    private fun clean(raw: String): String {
        var t = raw.trim()
        t = t.replace(Regex("[\\[(（【][^\\])）】]{0,30}[\\])）】]"), "").replace("♪", "").trim()
        if (t.length < 2 || !t.any { it.isLetter() }) return ""
        val low = t.lowercase()
        if (NOISE.any { low.contains(it) } || t == lastFinal) return ""
        lastFinal = t
        return t
    }

    /** Corta a fala em frases (pausa de 0,7 s ou 14 s) e transcreve cada uma com o Whisper. */
    private fun whisperLoop(engine: String) {
        val handle = Whisper.nativeInit(ModelManager.whisperFile(this, engine).absolutePath)
        if (handle == 0L) throw IllegalStateException("Whisper")
        val ex = Executors.newSingleThreadExecutor()
        val pending = AtomicInteger(0)
        var record: AudioRecord? = null
        try {
            record = buildRecord()
            record.startRecording()
            postStatus(Ui.s(this, R.string.svc_listening))
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            val chunk = ShortArray(1600)          // 100 ms
            val seg = ShortArray(SR * 14)         // frase com no máximo 14 s
            val pre = ArrayDeque<ShortArray>()    // 300 ms antes da fala
            var segLen = 0
            var inSpeech = false
            var silence = 0
            var speechChunks = 0

            fun add(a: ShortArray, len: Int) {
                val c = minOf(len, seg.size - segLen)
                System.arraycopy(a, 0, seg, segLen, c)
                segLen += c
            }
            fun flush() {
                if (speechChunks >= 4 && pending.get() < 3) {
                    val len = segLen
                    val f = FloatArray(maxOf(len, 17600)) { if (it < len) seg[it] / 32768f else 0f }
                    pending.incrementAndGet()
                    ex.execute {
                        try {
                            val bytes = Whisper.nativeTranscribe(handle, f, srcLang.code, threads)
                            val text = clean(String(bytes, Charsets.UTF_8))
                            if (text.isNotEmpty()) onFinal(text)
                        } catch (_: Throwable) {
                        } finally {
                            pending.decrementAndGet()
                        }
                    }
                }
                segLen = 0; inSpeech = false; silence = 0; speechChunks = 0
            }

            while (active) {
                val n = record.read(chunk, 0, chunk.size)
                if (n < 0) break
                if (n == 0) continue
                var sum = 0.0
                for (i in 0 until n) { val v = chunk[i] / 32768.0; sum += v * v }
                val speech = Math.sqrt(sum / n) > 0.012
                if (speech) {
                    if (!inSpeech) { inSpeech = true; for (c in pre) add(c, c.size); pre.clear() }
                    add(chunk, n); silence = 0; speechChunks++
                } else if (inSpeech) {
                    add(chunk, n); silence += 100
                    if (silence >= 700) flush()
                } else {
                    pre.addLast(chunk.copyOf(n))
                    if (pre.size > 3) pre.removeFirst()
                }
                if (inSpeech && segLen >= seg.size - chunk.size) flush()
            }
        } finally {
            try { record?.stop() } catch (_: Throwable) {}
            record?.release()
            ex.shutdown()
            // só liberta o modelo se a transcrição em curso já acabou (evita crash)
            if (ex.awaitTermination(20, TimeUnit.SECONDS)) Whisper.nativeFree(handle)
        }
    }

    override fun onDestroy() {
        active = false
        running = false
        main.removeCallbacksAndMessages(null)
        try { projection?.stop() } catch (_: Throwable) {}
        if (::prefs.isInitialized) try { prefs.sp.unregisterOnSharedPreferenceChangeListener(prefListener) } catch (_: Throwable) {}
        online.shutdown()
        translator?.close()
        overlay?.let { try { wm?.removeView(it) } catch (_: Throwable) {} }
        overlay = null
        super.onDestroy()
    }
}
