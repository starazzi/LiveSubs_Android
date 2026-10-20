package pt.livelegendas

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions

class MainActivity : AppCompatActivity() {
    private lateinit var p: Prefs
    private lateinit var spTgt: Spinner
    private lateinit var spSrc: Spinner
    private lateinit var switchOrig: MaterialSwitch
    private lateinit var spEngine: Spinner
    private lateinit var switchOnline: MaterialSwitch
    private var modelOpts: List<Langs.ModelOpt> = emptyList()
    private lateinit var preview: TextView
    private lateinit var rowText: LinearLayout
    private lateinit var rowBg: LinearLayout
    private lateinit var seekSize: SeekBar
    private lateinit var seekAlpha: SeekBar
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private var busy = false
    private var tgtList: List<Lang> = emptyList()
    private var srcList: List<Lang> = emptyList()

    private val textColors = intArrayOf(
        0xFFFFFFFF.toInt(), 0xFFFFEB3B.toInt(), 0xFF00E5FF.toInt(), 0xFF69F0AE.toInt(),
        0xFFFF9800.toInt(), 0xFFFF80AB.toInt(), 0xFF000000.toInt()
    )
    private val bgColors = intArrayOf(
        0xFF000000.toInt(), 0xFF303030.toInt(), 0xFF0D1B4C.toInt(), 0xFF4A0D0D.toInt(),
        0xFF0D3B1E.toInt(), 0xFF2E1A5C.toInt(), 0xFFFFFFFF.toInt()
    )

    private val projLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == RESULT_OK && data != null) {
            val i = Intent(this, CaptureService::class.java)
                .setAction(CaptureService.ACTION_START)
                .putExtra(CaptureService.EXTRA_CODE, r.resultCode)
                .putExtra(CaptureService.EXTRA_DATA, data)
                .putExtra(CaptureService.EXTRA_SRC, p.src)
                .putExtra(CaptureService.EXTRA_TGT, p.tgt)
                .putExtra(CaptureService.EXTRA_ENGINE, p.modelFor(p.src))
            ContextCompat.startForegroundService(this, i)
            status.text = Ui.s(this, R.string.st_active)
        } else {
            status.text = Ui.s(this, R.string.st_cancel)
        }
        Handler(Looper.getMainLooper()).postDelayed({ updateButtons() }, 400)
    }

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (granted(Manifest.permission.RECORD_AUDIO)) prepare() else status.text = Ui.s(this, R.string.st_audio)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        p = Prefs(this)
        spTgt = findViewById(R.id.spinnerTgt)
        spSrc = findViewById(R.id.spinnerSrc)
        switchOrig = findViewById(R.id.switchOriginal)
        preview = findViewById(R.id.preview)
        rowText = findViewById(R.id.rowText)
        rowBg = findViewById(R.id.rowBg)
        seekSize = findViewById(R.id.seekSize)
        seekAlpha = findViewById(R.id.seekAlpha)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)
        spEngine = findViewById(R.id.spinnerEngine)
        switchOnline = findViewById(R.id.switchOnline)
        applyTexts()
        Ui.ensureTranslated(this) { applyTexts() }   // línguas sem tradução manual: traduz na 1.ª vez

        // Língua do utilizador (para onde traduzir) e língua falada na live
        // 1.º a língua do telemóvel, depois todas as outras (nomes na língua do telemóvel)
        val sys = Langs.systemCode()
        val tgtItems = listOf(Langs.byCode(sys) to Langs.nameIn(sys, sys)) +
            Langs.sorted(Langs.all.filter { it.code != sys }, sys)
        tgtList = tgtItems.map { it.first }
        spTgt.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, tgtItems.map { it.second })
        spTgt.setSelection(tgtList.indexOfFirst { it.code == p.tgt }.coerceAtLeast(0))
        rebuildSources()
        onPick(spTgt) { pos ->
            val c = tgtList[pos].code
            if (c != p.tgt) { p.tgt = c; rebuildSources() }   // só grava se o utilizador mudar
        }
        onPick(spSrc) { if (it in srcList.indices) { p.src = srcList[it].code; buildModelSpinner() } }

        switchOrig.isChecked = p.showOrig
        switchOrig.setOnCheckedChangeListener { _, c -> p.showOrig = c }
        switchOnline.isChecked = p.onlineTr
        switchOnline.setOnCheckedChangeListener { _, c -> p.onlineTr = c }
        onPick(spEngine) { pos ->
            if (pos in modelOpts.indices) p.setModel(p.src, modelOpts[pos].id)   // escolha guardada por língua
        }

        // Aspeto das legendas
        seekSize.max = 20
        seekSize.progress = p.textSize - 12
        seekAlpha.max = 100
        seekAlpha.progress = p.bgAlpha
        onSeek(seekSize) { p.textSize = it + 12; applyPreview() }
        onSeek(seekAlpha) { p.bgAlpha = it; applyPreview() }
        swatches(rowText, textColors, p.textColor) { p.textColor = it; applyPreview() }
        swatches(rowBg, bgColors, p.bgColor) { p.bgColor = it; applyPreview() }
        applyPreview()

        btnStart.setOnClickListener { startFlow() }
        btnStop.setOnClickListener {
            stopService(Intent(this, CaptureService::class.java))
            status.text = Ui.s(this, R.string.st_stopped)
            Handler(Looper.getMainLooper()).postDelayed({ updateButtons() }, 400)
        }
    }

    override fun onResume() {
        super.onResume()
        updateButtons()
    }

    private var lastReady = ""

    private fun modelLabel(o: Langs.ModelOpt): String = when (o.id) {
        "whisper_tiny" -> Ui.s(this, R.string.engine_tiny)
        "whisper_base" -> Ui.s(this, R.string.engine_base)
        "whisper_small" -> Ui.s(this, R.string.engine_small)
        else -> "Vosk · ${o.id.removePrefix("vosk-model-")} · ${o.mb} MB"
    }

    /** Modelos disponíveis para a língua falada escolhida. */
    private fun buildModelSpinner() {
        modelOpts = Langs.optionsFor(p.src)
        spEngine.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, modelOpts.map { modelLabel(it) })
        spEngine.setSelection(modelOpts.indexOfFirst { it.id == p.modelFor(p.src) }.coerceAtLeast(0))
    }

    /** Aplica todos os textos da interface na língua do telemóvel (ou inglês). */
    private fun applyTexts() {
        fun tv(id: Int, res: Int) { findViewById<TextView>(id).text = Ui.s(this, res) }
        tv(R.id.t_desc, R.string.desc)
        tv(R.id.t_my_lang, R.string.my_lang)
        tv(R.id.t_stream_lang, R.string.stream_lang)
        tv(R.id.t_appearance, R.string.appearance)
        tv(R.id.t_text_color, R.string.text_color)
        tv(R.id.t_text_size, R.string.text_size)
        tv(R.id.t_bg_color, R.string.bg_color)
        tv(R.id.t_bg_opacity, R.string.bg_opacity)
        tv(R.id.t_howto, R.string.howto)
        switchOrig.text = Ui.s(this, R.string.show_orig)
        tv(R.id.t_engine, R.string.engine_label)
        tv(R.id.t_model_hint, R.string.model_hint)
        switchOnline.text = Ui.s(this, R.string.online_tr)
        buildModelSpinner()
        preview.text = Ui.s(this, R.string.preview)
        btnStart.text = Ui.s(this, R.string.start)
        btnStop.text = Ui.s(this, R.string.stop)
        val cur = status.text.toString()
        val ready = Ui.s(this, R.string.ready)
        if (cur.isEmpty() || cur == lastReady || cur == getString(R.string.ready)) status.text = ready
        lastReady = ready
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun onPick(sp: Spinner, f: (Int) -> Unit) {
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(a: AdapterView<*>?, v: View?, pos: Int, id: Long) { f(pos) }
            override fun onNothingSelected(a: AdapterView<*>?) {}
        }
    }

    private fun onSeek(sb: SeekBar, f: (Int) -> Unit) {
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, v: Int, fromUser: Boolean) { f(v) }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
    }

    /** Línguas da live (com modelo de voz gratuito), com os nomes escritos na língua para onde se traduz. */
    private fun rebuildSources() {
        val items = Langs.sorted(Langs.sourcesAll().filter { it.code != p.tgt }, p.tgt)
        srcList = items.map { it.first }
        spSrc.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items.map { it.second })
        spSrc.setSelection(srcList.indexOfFirst { it.code == p.src }.coerceAtLeast(0))
    }

    private fun applyPreview() {
        preview.setTextColor(p.textColor)
        preview.textSize = p.textSize.toFloat()
        preview.setBackgroundColor(p.bgArgb)
    }

    private fun swatches(row: LinearLayout, colors: IntArray, current: Int, pick: (Int) -> Unit) {
        row.removeAllViews()
        fun lp() = LinearLayout.LayoutParams(dp(38), dp(38)).apply { setMargins(0, dp(4), dp(10), dp(4)) }
        fun dot(c: Int) {
            val sel = c == current
            val v = View(this)
            v.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(c)
                setStroke(dp(if (sel) 3 else 1), if (sel) 0xFF5B3CC4.toInt() else 0xFF888888.toInt())
            }
            v.layoutParams = lp()
            v.setOnClickListener { pick(c); swatches(row, colors, c, pick) }
            row.addView(v)
        }
        // 1.ª bolinha: painel com todas as cores
        val custom = RainbowDot(this)
        custom.layoutParams = lp()
        custom.setOnClickListener {
            showColorDialog(this, current) { c -> pick(c); swatches(row, colors, c, pick) }
        }
        row.addView(custom)
        // cor personalizada atual (se não for uma das predefinidas)
        if (colors.none { it == current }) dot(current)
        colors.forEach { dot(it) }
    }

    private fun updateButtons() {
        btnStart.isEnabled = !CaptureService.running && !busy
        btnStop.isEnabled = CaptureService.running
    }

    private fun granted(perm: String) = ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED

    private fun startFlow() {
        if (!Settings.canDrawOverlays(this)) {
            status.text = Ui.s(this, R.string.st_overlay)
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val needed = mutableListOf<String>()
        if (!granted(Manifest.permission.RECORD_AUDIO)) needed += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS))
            needed += Manifest.permission.POST_NOTIFICATIONS
        if (needed.isNotEmpty()) permLauncher.launch(needed.toTypedArray()) else prepare()
    }

    private fun setBusy(b: Boolean) {
        busy = b
        progress.visibility = if (b) View.VISIBLE else View.GONE
        updateButtons()
    }

    /** Descarrega só os pacotes das línguas escolhidas (1ª vez) e pede a captura de áudio. */
    private fun prepare() {
        val src = Langs.byCode(p.src)
        val tgt = Langs.byCode(p.tgt)
        setBusy(true)
        Thread {
            try {
                ModelManager.ensureFor(this, p.modelFor(p.src)) { pct ->
                    runOnUiThread {
                        progress.isIndeterminate = pct < 0
                        if (pct < 0) status.text = Ui.s(this, R.string.st_unzip) else {
                            progress.progress = pct
                            status.text = Ui.s(this, R.string.st_dl_voice, pct)
                        }
                    }
                }
                runOnUiThread {
                    status.text = Ui.s(this, R.string.st_dl_tr)
                    progress.isIndeterminate = true
                }
                val opts = TranslatorOptions.Builder()
                    .setSourceLanguage(src.mlKit).setTargetLanguage(tgt.mlKit).build()
                val tr = Translation.getClient(opts)
                tr.downloadModelIfNeeded(DownloadConditions.Builder().build())
                    .addOnSuccessListener {
                        tr.close()
                        setBusy(false)
                        status.text = Ui.s(this, R.string.st_auth)
                        val mpm = getSystemService(MediaProjectionManager::class.java)
                        projLauncher.launch(mpm.createScreenCaptureIntent())
                    }
                    .addOnFailureListener { e ->
                        tr.close()
                        setBusy(false)
                        status.text = Ui.s(this, R.string.st_tr_fail, e.message)
                    }
            } catch (e: Exception) {
                runOnUiThread {
                    setBusy(false)
                    status.text = Ui.s(this, R.string.st_voice_fail, e.message)
                }
            }
        }.start()
    }
}
