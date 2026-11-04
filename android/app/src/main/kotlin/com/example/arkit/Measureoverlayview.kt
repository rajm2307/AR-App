package com.example.arkit

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.view.View

class OverlayState(
    val viewProj: FloatArray,
    val tracking: Boolean,
    val hasHit: Boolean,
    val dots: List<FloatArray>,
    val segs: List<Seg>,
    val polys: List<Poly>,
    val labels: List<Lbl>,
)

/** Projects the primitives to the screen and draws crosshair, fills, lines, dots and labels. */
class MeasureOverlayView(context: Context) : View(context) {
    @Volatile private var state: OverlayState? = null

    private val d = resources.displayMetrics.density
    private val path = Path()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 3 * d; strokeCap = Paint.Cap.ROUND
    }
    private val dotFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BLUE }
    private val dotStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 2 * d
    }
    private val reticlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3 * d; strokeCap = Paint.Cap.ROUND
    }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC000000.toInt() }
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textSize = 13 * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }

    init { setWillNotDraw(false) }

    fun update(s: OverlayState) {
        state = s
        postInvalidateOnAnimation()
    }

    override fun onDraw(c: Canvas) {
        val s = state
        val w = width.toFloat()
        val h = height.toFloat()
        drawReticle(c, w / 2, h / 2, s != null && s.tracking && s.hasHit)
        if (s == null || !s.tracking) return

        for (p in s.polys) {
            val pp = p.pts.map { project(s.viewProj, it, w, h) }
            if (pp.size < 3 || pp.any { it == null }) continue
            path.reset()
            path.moveTo(pp[0]!!.x, pp[0]!!.y)
            for (i in 1 until pp.size) path.lineTo(pp[i]!!.x, pp[i]!!.y)
            path.close()
            fillPaint.color = (p.color and 0x00FFFFFF) or 0x40000000
            c.drawPath(path, fillPaint)
        }
        for (sg in s.segs) {
            val a = project(s.viewProj, sg.a, w, h) ?: continue
            val b = project(s.viewProj, sg.b, w, h) ?: continue
            linePaint.color = sg.color
            c.drawLine(a.x, a.y, b.x, b.y, linePaint)
        }
        for (dpt in s.dots) {
            val p = project(s.viewProj, dpt, w, h) ?: continue
            c.drawCircle(p.x, p.y, 7 * d, dotFill)
            c.drawCircle(p.x, p.y, 7 * d, dotStroke)
        }
        for (l in s.labels) {
            val p = project(s.viewProj, l.pos, w, h) ?: continue
            drawLabel(c, l.text, p.x, p.y)
        }
    }

    private fun drawLabel(c: Canvas, text: String, cx: Float, cy: Float) {
        val tw = labelText.measureText(text)
        val padX = 9 * d
        val padY = 5 * d
        val half = labelText.textSize / 2
        val rect = RectF(cx - tw / 2 - padX, cy - half - padY, cx + tw / 2 + padX, cy + half + padY)
        c.drawRoundRect(rect, 12 * d, 12 * d, labelBg)
        c.drawText(text, cx, cy + labelText.textSize / 3, labelText)
    }

    private fun drawReticle(c: Canvas, cx: Float, cy: Float, ok: Boolean) {
        reticlePaint.color = if (ok) 0xFFFFFFFF.toInt() else 0xFFFFB74D.toInt()
        val r = 26 * d
        val l = 10 * d
        for (sx in intArrayOf(-1, 1)) for (sy in intArrayOf(-1, 1)) {
            val x = cx + sx * r
            val y = cy + sy * r
            c.drawLine(x, y, x - sx * l, y, reticlePaint)
            c.drawLine(x, y, x, y - sy * l, reticlePaint)
        }
        c.drawLine(cx - 5 * d, cy, cx + 5 * d, cy, reticlePaint)
        c.drawLine(cx, cy - 5 * d, cx, cy + 5 * d, reticlePaint)
    }

    /** World point -> screen pixels, or null if it is behind the camera. */
    private fun project(vp: FloatArray, p: FloatArray, w: Float, h: Float): PointF? {
        val x = vp[0] * p[0] + vp[4] * p[1] + vp[8] * p[2] + vp[12]
        val y = vp[1] * p[0] + vp[5] * p[1] + vp[9] * p[2] + vp[13]
        val wc = vp[3] * p[0] + vp[7] * p[1] + vp[11] * p[2] + vp[15]
        if (wc <= 0.001f) return null
        return PointF((x / wc * 0.5f + 0.5f) * w, (1f - (y / wc * 0.5f + 0.5f)) * h)
    }
}