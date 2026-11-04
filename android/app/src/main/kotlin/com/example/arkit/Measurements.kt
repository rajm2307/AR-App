package com.example.arkit

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

const val BLUE = 0xFF2196F3.toInt()
const val ORANGE = 0xFFFFA000.toInt()

/** Drawing primitives (world coordinates, meters). The overlay just projects and draws them. */
class Seg(val a: FloatArray, val b: FloatArray, val color: Int)
class Poly(val pts: List<FloatArray>, val color: Int)
class Lbl(val pos: FloatArray, val text: String)

/** Everything one frame needs: geometry for the overlay + text/flags for Flutter. */
class Built(
    val dots: List<FloatArray> = emptyList(),
    val segs: List<Seg> = emptyList(),
    val polys: List<Poly> = emptyList(),
    val labels: List<Lbl> = emptyList(),
    val summary: String? = null,
    val hint: String = "",
    val canAdd: Boolean = false,
    val canFinish: Boolean = false,
)

/** Unit formatting (cm-based metric or inches). */
class Fmt(private val inch: Boolean) {
    private fun f(fmt: String, vararg a: Any) = String.format(Locale.US, fmt, *a)
    fun len(m: Float) = if (inch) f("%.2f in", m * 39.3701f) else f("%.1f cm", m * 100f)
    fun area(m2: Float) = if (inch) f("%.1f in²", m2 * 1550.0031f) else f("%.1f cm²", m2 * 10000f)
    fun vol(m3: Float) =
        if (inch) f("%.1f in³", m3 * 61023.74f) else f("%.2f L (%.0f cm³)", m3 * 1000f, m3 * 1_000_000f)
}

// ---- small vector helpers ----------------------------------------------------
private fun sub(a: FloatArray, b: FloatArray) = floatArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])
private fun add(a: FloatArray, b: FloatArray) = floatArrayOf(a[0] + b[0], a[1] + b[1], a[2] + b[2])
private fun scale(a: FloatArray, s: Float) = floatArrayOf(a[0] * s, a[1] * s, a[2] * s)
private fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(
    a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]
)
private fun len(a: FloatArray) = sqrt(dot(a, a))
private fun mid(a: FloatArray, b: FloatArray) = scale(add(a, b), 0.5f)
private fun dist(a: FloatArray, b: FloatArray) = len(sub(a, b))
private fun flat(p: FloatArray, y: Float) = floatArrayOf(p[0], y, p[2])

/**
 * Height of a vertical line standing on [corner]: the point of that line closest to the
 * camera ray ([camPos] + s * [camFwd]). Returns null when the ray is almost vertical.
 */
fun heightFromRay(corner: FloatArray, camPos: FloatArray, camFwd: FloatArray): Float? {
    val u1 = floatArrayOf(0f, 1f, 0f)
    val w0 = sub(corner, camPos)
    val b = dot(u1, camFwd)
    val d = dot(u1, w0)
    val e = dot(camFwd, w0)
    val denom = 1f - b * b
    if (denom < 0.02f) return null
    return min(5f, max(0f, (b * e - d) / denom))
}

private class Rect(val c: List<FloatArray>, val l: Float, val w: Float)

/** Rectangle on the horizontal plane: edge p0->p1 plus a width given by point [r]. */
private fun rect(p0: FloatArray, p1: FloatArray, r: FloatArray): Rect? {
    val u0 = sub(p1, p0)
    val l = len(u0)
    if (l < 1e-3f) return null
    val u = scale(u0, 1f / l)
    val v = floatArrayOf(u[2], 0f, -u[0]) // horizontal, perpendicular to the edge
    val w = dot(sub(r, p0), v)
    val off = scale(v, w)
    return Rect(listOf(p0, p1, add(p1, off), add(p0, off)), l, abs(w))
}

private fun polygonArea(p: List<FloatArray>): Float {
    var sx = 0f; var sy = 0f; var sz = 0f
    val o = p[0]
    for (i in 1 until p.size - 1) {
        val c = cross(sub(p[i], o), sub(p[i + 1], o))
        sx += c[0]; sy += c[1]; sz += c[2]
    }
    return 0.5f * sqrt(sx * sx + sy * sy + sz * sz)
}

object Builder {

    // ---- distance (2 points) -------------------------------------------------
    fun twoPoint(pts: List<FloatArray>, reticle: FloatArray?, f: Fmt): Built {
        val a = pts.getOrNull(0)
        val b = pts.getOrNull(1) ?: if (pts.size == 1) reticle else null
        val has = a != null && b != null
        return Built(
            dots = pts,
            segs = if (has) listOf(Seg(a!!, b!!, BLUE)) else emptyList(),
            labels = if (has) listOf(Lbl(mid(a!!, b!!), f.len(dist(a, b)))) else emptyList(),
            summary = if (has) f.len(dist(a!!, b!!)) else null,
            hint = when {
                pts.isEmpty() -> if (reticle != null) "Tap + to place the first point" else "Looking for a surface…"
                pts.size == 1 -> if (reticle != null) "Tap + to place the second point" else "Aim at a surface"
                else -> "Tap + to start a new measurement"
            },
            canAdd = reticle != null,
        )
    }

    // ---- area (polygon on any flat surface) ---------------------------------
    fun area(pts: List<FloatArray>, reticle: FloatArray?, closed: Boolean, f: Fmt): Built {
        val poly = if (!closed && reticle != null && pts.isNotEmpty()) pts + listOf(reticle) else pts
        val n = poly.size
        val segs = mutableListOf<Seg>()
        for (i in 0 until n - 1) segs.add(Seg(poly[i], poly[i + 1], ORANGE))
        if (n >= 3) segs.add(Seg(poly[n - 1], poly[0], ORANGE))
        val labels = segs.map { Lbl(mid(it.a, it.b), f.len(dist(it.a, it.b))) }.toMutableList()
        var summary: String? = null
        if (n >= 3) {
            val a = polygonArea(poly)
            summary = f.area(a)
            var cx = 0f; var cy = 0f; var cz = 0f
            poly.forEach { cx += it[0]; cy += it[1]; cz += it[2] }
            labels.add(Lbl(floatArrayOf(cx / n, cy / n, cz / n), "A: " + f.area(a)))
        }
        return Built(
            dots = pts,
            segs = segs,
            polys = if (n >= 3) listOf(Poly(poly, ORANGE)) else emptyList(),
            labels = labels,
            summary = summary,
            hint = when {
                closed -> "Area closed. Tap + to start a new one"
                pts.isEmpty() -> if (reticle != null) "Tap + to place the first corner" else "Looking for a surface…"
                pts.size < 3 -> "Tap + to add more corners"
                else -> "Tap + to add corners, or ✓ when done"
            },
            canAdd = reticle != null,
            canFinish = !closed && pts.size >= 3,
        )
    }

    // ---- volume (box standing on a horizontal surface) ----------------------
    fun volume(
        pts: List<FloatArray>, reticle: FloatArray?,
        camPos: FloatArray, camFwd: FloatArray,
        heightFixed: Float?, f: Fmt,
    ): Built {
        val stage = pts.size + if (heightFixed != null) 1 else 0
        val hints = listOf(
            "Tap + at a base corner",
            "Tap + at the next corner (length)",
            "Move sideways to set the width, then tap +",
            "Raise the crosshair to the top edge, then tap +",
            "Done. Tap + to start a new box",
        )
        val hint = hints[min(stage, 4)]

        if (pts.isEmpty()) {
            return Built(hint = if (reticle == null) "Looking for a surface…" else hints[0], canAdd = reticle != null)
        }
        val p0 = pts[0]
        if (pts.size == 1) {
            val r = reticle?.let { flat(it, p0[1]) }
            return Built(
                dots = pts,
                segs = if (r != null) listOf(Seg(p0, r, BLUE)) else emptyList(),
                labels = if (r != null) listOf(Lbl(mid(p0, r), f.len(dist(p0, r)))) else emptyList(),
                hint = hint, canAdd = r != null,
            )
        }
        val p1 = flat(pts[1], p0[1])
        val third = if (pts.size >= 3) pts[2] else reticle
        if (third == null) {
            return Built(
                dots = pts, segs = listOf(Seg(p0, p1, BLUE)),
                labels = listOf(Lbl(mid(p0, p1), f.len(dist(p0, p1)))), hint = hint,
            )
        }
        val r = rect(p0, p1, flat(third, p0[1])) ?: return Built(dots = pts, hint = hint)

        val withVolume = pts.size >= 3
        val h = if (withVolume) (heightFixed ?: heightFromRay(p0, camPos, camFwd) ?: 0f) else 0f
        val c = r.c
        val segs = mutableListOf<Seg>()
        for (i in 0 until 4) segs.add(Seg(c[i], c[(i + 1) % 4], BLUE))
        val labels = mutableListOf(Lbl(mid(c[0], c[1]), f.len(r.l)), Lbl(mid(c[1], c[2]), f.len(r.w)))
        val polys = mutableListOf(Poly(c, BLUE))
        var summary: String? = null
        if (withVolume && h > 0.005f) {
            val up = floatArrayOf(0f, h, 0f)
            val t = c.map { add(it, up) }
            for (i in 0 until 4) {
                segs.add(Seg(t[i], t[(i + 1) % 4], BLUE))
                segs.add(Seg(c[i], t[i], BLUE))
            }
            labels.add(Lbl(mid(c[0], t[0]), f.len(h)))
            polys.add(Poly(t, BLUE))
            val v = r.l * r.w * h
            summary = "V: " + f.vol(v)
            labels.add(Lbl(mid(t[0], t[2]), summary))
        }
        return Built(
            dots = pts, segs = segs, polys = polys, labels = labels,
            summary = summary, hint = hint, canAdd = true,
        )
    }
}