package pt.livelegendas

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog

private val RAINBOW = intArrayOf(
    0xFFFF0000.toInt(), 0xFFFFFF00.toInt(), 0xFF00FF00.toInt(),
    0xFF00FFFF.toInt(), 0xFF0000FF.toInt(), 0xFFFF00FF.toInt(), 0xFFFF0000.toInt()
)

/** Botão redondo de arco-íris com um «+»: abre o painel de todas as cores. */
class RainbowDot(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(cx, cy) - 2f
        p.style = Paint.Style.FILL
        p.shader = SweepGradient(cx, cy, RAINBOW, null)
        c.drawCircle(cx, cy, r, p)
        p.shader = null
        p.color = Color.WHITE
        p.style = Paint.Style.STROKE
        p.strokeWidth = r * 0.2f
        p.strokeCap = Paint.Cap.ROUND
        c.drawLine(cx - r * 0.4f, cy, cx + r * 0.4f, cy, p)
        c.drawLine(cx, cy - r * 0.4f, cx, cy + r * 0.4f, p)
    }
}

/** Quadrado de saturação/brilho para o tom (hue) escolhido. */
class SvView(ctx: Context) : View(ctx) {
    var hue = 0f
    var sat = 1f
    var bri = 1f
    var onChange: (() -> Unit)? = null
    private val paint = Paint()
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5f }

    fun color(): Int = Color.HSVToColor(floatArrayOf(hue, sat, bri))

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val pure = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
        val satShader = LinearGradient(0f, 0f, w, 0f, Color.WHITE, pure, Shader.TileMode.CLAMP)
        val briShader = LinearGradient(0f, 0f, 0f, h, Color.WHITE, Color.BLACK, Shader.TileMode.CLAMP)
        paint.shader = ComposeShader(briShader, satShader, PorterDuff.Mode.MULTIPLY)
        c.drawRect(0f, 0f, w, h, paint)
        val x = sat * w
        val y = (1f - bri) * h
        ring.color = Color.BLACK; c.drawCircle(x, y, 18f, ring)
        ring.color = Color.WHITE; c.drawCircle(x, y, 14f, ring)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        sat = (e.x / width).coerceIn(0f, 1f)
        bri = 1f - (e.y / height).coerceIn(0f, 1f)
        invalidate()
        onChange?.invoke()
        return true
    }
}

/** Painel com todas as cores: quadrado + barra de tons + pré-visualização. */
fun showColorDialog(ctx: Context, initial: Int, onPicked: (Int) -> Unit) {
    val d = ctx.resources.displayMetrics.density
    fun px(v: Int) = (v * d).toInt()
    val hsv = FloatArray(3)
    Color.colorToHSV(initial, hsv)

    val sv = SvView(ctx).apply { hue = hsv[0]; sat = hsv[1]; bri = hsv[2] }
    val preview = View(ctx)
    fun refresh() = preview.setBackgroundColor(sv.color())
    sv.onChange = { refresh() }

    val hueBar = SeekBar(ctx).apply {
        max = 360
        progress = hsv[0].toInt()
        background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, RAINBOW)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, v: Int, fromUser: Boolean) {
                sv.hue = v.toFloat(); sv.invalidate(); refresh()
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
    }
    refresh()

    val box = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(24), px(20), px(24), px(8))
        addView(sv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(220)))
        addView(hueBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(36)).apply { topMargin = px(16) })
        addView(preview, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(44)).apply { topMargin = px(16) })
    }
    AlertDialog.Builder(ctx)
        .setView(box)
        .setPositiveButton(android.R.string.ok) { _, _ -> onPicked(sv.color()) }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}
