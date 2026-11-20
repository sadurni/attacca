package com.sadurni.attacca.art

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A small picture: [w] × [h] ARGB pixels (a song's cover, for Art-tacca to paint). */
class ArtCover(val w: Int, val h: Int, val argb: IntArray) {
    init {
        require(w > 0 && h > 0 && argb.size >= w * h)
    }

    /** The mean colour round its edge (0 … 1 per channel). */
    fun edgeColour(): FloatArray {
        val sum = FloatArray(3)
        var n = 0
        for (y in 0 until h) for (x in 0 until w) {
            if (x != 0 && y != 0 && x != w - 1 && y != h - 1) continue
            val p = argb[y * w + x]
            sum[0] += ((p shr 16) and 0xff) / 255f
            sum[1] += ((p shr 8) and 0xff) / 255f
            sum[2] += (p and 0xff) / 255f
            n++
        }
        return FloatArray(3) { sum[it] / n }
    }

    companion object {
        /**
         * For a song without a cover: a record, its grooves catching the light, its label in the song's
         * colours, now and then half out of its sleeve.
         */
        fun record(song: ArtSong): ArtCover {
            val n = 96
            val rnd = java.util.Random(song.seed xor 0x5EC0DL)
            val p = song.palette
            val paper = floatArrayOf(0.96f, 0.95f, 0.93f)
            val sleeve = rnd.nextFloat() < 0.5f
            val sleeveColour = p.second
            // The record's middle (moved right when its sleeve shows on the left), and its label.
            val cx = if (sleeve) n * 0.6f else n * 0.5f
            val cy = n * 0.5f
            val radius = n * (if (sleeve) 0.4f else 0.46f)
            val label = p.main
            val ring = p.accent
            val shine = rnd.nextFloat() * 3.1416f
            val grooves = FloatArray(8) { 0.42f + rnd.nextFloat() * 0.52f }
            val px = IntArray(n * n) { i ->
                val x = i % n + 0.5f
                val y = i / n + 0.5f
                val d = kotlin.math.hypot(x - cx, y - cy) / radius
                val c: FloatArray = when {
                    d < 0.035f -> paper
                    d < 0.36f -> if (d > 0.31f) ring else label
                    d < 1f -> {
                        // Black vinyl, the gaps between the songs a little lighter, a sheen across it.
                        val a = atan2(y - cy, x - cx)
                        val sheen = 0.13f * kotlin.math.max(0f, cos(2f * (a - shine))).let { it * it * it }
                        val gap = if (grooves.any { abs(d - it) < 0.012f }) 0.07f else 0f
                        val g = 0.07f + sheen + gap + (if ((d * 60f).toInt() % 2 == 0) 0.015f else 0f)
                        floatArrayOf(g, g, g * 1.05f)
                    }
                    sleeve && x > n * 0.06f && x < n * 0.62f && y > n * 0.08f && y < n * 0.92f -> sleeveColour
                    else -> paper
                }
                (0xff shl 24) or ((c[0] * 255).toInt().coerceIn(0, 255) shl 16) or ((c[1] * 255).toInt().coerceIn(0, 255) shl 8) or (c[2] * 255).toInt().coerceIn(0, 255)
            }
            return ArtCover(n, n, px)
        }
    }
}

/** What Art-tacca paints: freely from the song, or the song's cover. */
enum class ArtSubject {
    FREE,
    COVER;

    companion object {
        fun of(name: String?): ArtSubject = entries.firstOrNull { it.name == name } ?: FREE
    }
}

/**
 * The painter's eye: a rough copy of the canvas, kept as each mark is laid, and (painting a cover)
 * the picture it should come to, laid out in the cover's own shape in the middle of the canvas.
 * From them: where the painting is furthest from the cover, the pigment wanted there and the way the
 * cover's edges run.
 */
internal class ArtGuide {
    var cols = 0
        private set
    var rows = 0
        private set

    /** Canvas pixels per cell. */
    private var cell = 1f
    private var width = 1f
    private var height = 1f

    /** The canvas as it looks, red, green and blue per cell. */
    private var model = FloatArray(0)

    /** The cover, sharp (0), a little blurred (1) and much (2), over the same cells; null painting freely. */
    private var levels: Array<FloatArray>? = null

    /** Each cell's edge direction (along the edge) and how strong an edge it is (0 … 1). */
    private var along = FloatArray(0)
    private var edge = FloatArray(0)

    /** The cover's place on the canvas. */
    var left = 0f
        private set
    var top = 0f
        private set
    var right = 0f
        private set
    var bottom = 0f
        private set

    val painting: Boolean get() = levels != null

    fun copy(): Triple<Int, Int, FloatArray> = Triple(cols, rows, model.copyOf())

    /** A canvas of [w] × [h] in [ground]; [keep]: what is on it stays (laid out again on the new size). */
    fun reset(w: Float, h: Float, ground: FloatArray, keep: Boolean) {
        val old = model
        val oc = cols
        val or = rows
        layout(w, h)
        model = FloatArray(cols * rows * 3)
        if (keep && old.isNotEmpty()) resampleFrom(old, oc, or)
        else for (i in 0 until cols * rows) {
            model[i * 3] = ground[0]
            model[i * 3 + 1] = ground[1]
            model[i * 3 + 2] = ground[2]
        }
    }

    /** The canvas becomes [w] × [h]: everything on it, and the cover's place, stretched with it. */
    fun resize(w: Float, h: Float) {
        if (model.isEmpty()) return
        val sx = w / width
        val sy = h / height
        val old = model
        val oc = cols
        val or = rows
        layout(w, h)
        model = FloatArray(cols * rows * 3)
        resampleFrom(old, oc, or)
        pictureOf?.let { setCover(it, left * sx, top * sy, right * sx, bottom * sy) }
    }

    private var pictureOf: ArtCover? = null

    /** Painting freely: no cover. */
    fun clearCover() {
        levels = null
        pictureOf = null
    }

    /** The cover to paint, in its own shape as large as fits (a little margin round it). */
    fun setCover(cover: ArtCover) {
        val k = min(width * FILL / cover.w, height * FILL / cover.h)
        val cw = cover.w * k
        val ch = cover.h * k
        setCover(cover, (width - cw) / 2, (height - ch) / 2, (width + cw) / 2, (height + ch) / 2)
    }

    private fun setCover(cover: ArtCover, l: Float, t: Float, r: Float, b: Float) {
        pictureOf = cover
        left = l
        top = t
        right = r
        bottom = b
        val n = cols * rows
        val sharp = FloatArray(n * 3)
        for (j in 0 until rows) for (i in 0 until cols) {
            val x = (i + 0.5f) * cell
            val y = (j + 0.5f) * cell
            // (Outside the cover: its nearest edge, drawn out, for the loose strokes round it.)
            val u = ((x - l) / (r - l)).coerceIn(0f, 1f) * cover.w - 0.5f
            val v = ((y - t) / (b - t)).coerceIn(0f, 1f) * cover.h - 0.5f
            sample(cover, u, v, sharp, (j * cols + i) * 3)
        }
        val soft = blurred(sharp, max(1, (cols / 64f).toInt() + 1))
        val softer = blurred(sharp, max(3, cols / 20))
        levels = arrayOf(sharp, soft, softer)
        edges(soft)
    }

    // ---------- Reading the canvas and the cover ----------

    private fun index(x: Float, y: Float): Int {
        val i = (x / cell).toInt().coerceIn(0, cols - 1)
        val j = (y / cell).toInt().coerceIn(0, rows - 1)
        return j * cols + i
    }

    fun inside(x: Float, y: Float): Boolean = x >= left && x <= right && y >= top && y <= bottom

    /** How far the canvas is from the cover round ([x], [y]), about [r] across, as [blend] can mend it. */
    fun error(x: Float, y: Float, r: Float, blend: Int): Float {
        val lv = levels ?: return 0f
        val t = lv[levelFor(r)]
        var e = 0f
        val d = r * 0.6f
        for (k in 0 until 5) {
            val px = x + if (k == 1) d else if (k == 2) -d else 0f
            val py = y + if (k == 3) d else if (k == 4) -d else 0f
            val i = index(px, py) * 3
            e += when (blend) {
                MULTIPLY -> max(0f, model[i] - t[i]) + max(0f, model[i + 1] - t[i + 1]) + max(0f, model[i + 2] - t[i + 2]) +
                    0.15f * (abs(model[i] - t[i]) + abs(model[i + 1] - t[i + 1]) + abs(model[i + 2] - t[i + 2]))
                DARKEN -> max(0f, lum(model, i) - lum(t, i)) * 3f
                else -> abs(model[i] - t[i]) + abs(model[i + 1] - t[i + 1]) + abs(model[i + 2] - t[i + 2])
            }
        }
        return e / 5f * if (inside(x, y)) 1f else OUTSIDE
    }

    /** The cover's colour round ([x], [y]), about [r] across. */
    fun wanted(x: Float, y: Float, r: Float): FloatArray {
        val lv = levels ?: return floatArrayOf(1f, 1f, 1f)
        val t = lv[levelFor(r)]
        val i = index(x, y) * 3
        return floatArrayOf(t[i], t[i + 1], t[i + 2])
    }

    /** How light the canvas is at ([x], [y]), 0 … 1. */
    fun lightness(x: Float, y: Float): Float {
        if (model.isEmpty()) return 1f
        return lum(model, index(x, y) * 3)
    }

    /**
     * The pigment (and how strong) a mark of [blend] laid at ([x], [y]), [rx] × [ry] turned by
     * [angle], should have to bring the canvas towards the cover: for pigments that multiply, what
     * lets through as much as is wanted, leaning to the lighter parts under a big mark (light first,
     * the dark comes later); laid over, the cover's colour there.
     */
    fun pigment(x: Float, y: Float, rx: Float, ry: Float, angle: Float, alpha: Float, blend: Int, grey: Boolean): Pair<FloatArray, Float> {
        val lv = levels ?: return floatArrayOf(1f, 1f, 1f) to alpha
        val r = max(rx, ry)
        val t = lv[levelFor(r)]
        val c = cos(angle)
        val s = sin(angle)
        val mean = FloatArray(3)
        val light = FloatArray(3) { 0f }
        var need = 0f
        val n = 5
        for (k in 0 until n) {
            val u = if (k == 1) 0.6f else if (k == 2) -0.6f else 0f
            val v = if (k == 3) 0.6f else if (k == 4) -0.6f else 0f
            val px = x + u * rx * c - v * ry * s
            val py = y + u * rx * s + v * ry * c
            val i = index(px, py) * 3
            for (ch in 0 until 3) {
                val want = t[i + ch]
                val v1 = if (blend == MULTIPLY) (want / max(model[i + ch], 0.04f)).coerceIn(0f, 1f) else want
                mean[ch] += v1 / n
                light[ch] = max(light[ch], v1)
            }
            if (blend == MULTIPLY) need = max(need, 1f - lum(t, i) / max(lum(model, i), 0.04f))
        }
        if (blend != MULTIPLY) {
            val out = if (grey) greyed(mean) else mean
            return out to alpha
        }
        // A big mark leans to the lightest it covers; a small one goes for what is wanted.
        val big = ((r / cell - 2f) / 10f).coerceIn(0f, 1f) * 0.6f
        val ratio = FloatArray(3) { mean[it] + (light[it] - mean[it]) * big }
        // As strong as needed to get there (a little more than asked, never opaque).
        val a = max(alpha, min(MAX_ALPHA, need * 1.1f)).coerceAtMost(max(alpha, MAX_ALPHA))
        val pig = FloatArray(3) { ((ratio[it] - (1f - a)) / a).coerceIn(0f, 1f) }
        return (if (grey) greyed(pig) else pig) to a
    }

    /** The way the cover's edges run at ([x], [y]) and how strong an edge is there (0 … 1). */
    fun alongAt(x: Float, y: Float): Pair<Float, Float> {
        if (levels == null) return 0f to 0f
        val i = index(x, y)
        return along[i] to edge[i]
    }

    /** Of [tries] places on the cover, the one furthest from it (at the scale of [r]). */
    fun hotSpot(rnd: java.util.Random, r: Float, blend: Int, tries: Int = 24): Pair<Float, Float>? {
        if (levels == null) return null
        var best = -1f
        var bx = 0f
        var by = 0f
        repeat(tries) {
            val x = left + rnd.nextFloat() * (right - left)
            val y = top + rnd.nextFloat() * (bottom - top)
            val e = error(x, y, r, blend)
            if (e > best) {
                best = e
                bx = x
                by = y
            }
        }
        return bx to by
    }

    /** Where the cover draws the eye: its strongest contrast, away from its very edge. */
    fun focus(): Pair<Float, Float>? {
        val lv = levels ?: return null
        var best = -1f
        var bi = 0
        var bj = 0
        for (j in 0 until rows) for (i in 0 until cols) {
            val x = (i + 0.5f) * cell
            val y = (j + 0.5f) * cell
            val fx = (x - left) / (right - left)
            val fy = (y - top) / (bottom - top)
            if (fx < 0.15f || fx > 0.85f || fy < 0.15f || fy > 0.85f) continue
            val k = (j * cols + i) * 3
            val c = abs(lum(lv[1], k) - lum(lv[2], k)) + 0.3f * edge[j * cols + i]
            if (c > best) {
                best = c
                bi = i
                bj = j
            }
        }
        return if (best < 0f) null else (bi + 0.5f) * cell to (bj + 0.5f) * cell
    }

    // ---------- Laying marks on the copy ----------

    /** [m] laid on the copy of the canvas, roughly as it shows on the real one. */
    fun lay(m: ArtMark) {
        if (model.isEmpty()) return
        val blend = blendOf(m.kind)
        if (m.kind == ArtMark.VEIL) {
            for (i in 0 until cols * rows) for (ch in 0 until 3) {
                val k = i * 3 + ch
                model[k] = model[k] * (1f - m.alpha) + colourOf(m, ch) * m.alpha
            }
            return
        }
        val strength = (m.alpha * coverage(m.kind)).coerceIn(0f, 1f)
        // A wash spreads over a little less than its size.
        val rx = if (m.kind == ArtMark.GLAZE) m.rx * 0.75f else m.rx
        val ry = if (m.kind == ArtMark.GLAZE) m.ry * 0.75f else m.ry
        val c = cos(m.angle)
        val s = sin(m.angle)
        val ext = max(rx, ry)
        val i0 = ((m.x - ext) / cell).toInt().coerceIn(0, cols - 1)
        val i1 = ((m.x + ext) / cell).toInt().coerceIn(0, cols - 1)
        val j0 = ((m.y - ext) / cell).toInt().coerceIn(0, rows - 1)
        val j1 = ((m.y + ext) / cell).toInt().coerceIn(0, rows - 1)
        // Thinner (or smaller) than a cell: it covers that share of it.
        val thin = min(1f, 2f * ry / cell) * min(1f, 2f * rx / cell)
        var any = false
        for (j in j0..j1) for (i in i0..i1) {
            val dx = (i + 0.5f) * cell - m.x
            val dy = (j + 0.5f) * cell - m.y
            val u = (dx * c + dy * s) / max(rx, 0.01f)
            val v = (-dx * s + dy * c) / max(ry, 0.01f)
            val d = u * u + v * v
            if (d > 1f) continue
            any = true
            put(j * cols + i, m, strength * thin * (if (d > 0.7f) (1f - d) / 0.3f else 1f), blend)
        }
        if (!any) {
            // Smaller than a cell and between their middles: its share of the one it is in.
            val share = (3.1416f * rx * ry / (cell * cell)).coerceAtMost(1f)
            put(index(m.x, m.y), m, strength * share, blend)
        }
    }

    private fun put(i: Int, m: ArtMark, a: Float, blend: Int) {
        if (a <= 0f) return
        for (ch in 0 until 3) {
            val k = i * 3 + ch
            val col = colourOf(m, ch)
            model[k] = when (blend) {
                MULTIPLY -> model[k] * (1f - a + a * col)
                DARKEN -> min(model[k], 1f - a + a * col)
                else -> model[k] * (1f - a) + col * a
            }
        }
    }

    private fun colourOf(m: ArtMark, ch: Int) = when (ch) {
        0 -> m.r
        1 -> m.g
        else -> m.b
    }

    // ---------- Inner workings ----------

    private fun layout(w: Float, h: Float) {
        width = max(1f, w)
        height = max(1f, h)
        cell = max(width, height) / CELLS
        cols = max(1, ceil(width / cell).toInt())
        rows = max(1, ceil(height / cell).toInt())
    }

    private fun resampleFrom(old: FloatArray, oc: Int, or: Int) {
        for (j in 0 until rows) for (i in 0 until cols) {
            val oi = (i * oc / cols).coerceIn(0, oc - 1)
            val oj = (j * or / rows).coerceIn(0, or - 1)
            for (ch in 0 until 3) model[(j * cols + i) * 3 + ch] = old[(oj * oc + oi) * 3 + ch]
        }
    }

    private fun levelFor(r: Float): Int = when {
        r < cell * 2.5f -> 0
        r < cell * 8f -> 1
        else -> 2
    }

    private fun sample(cover: ArtCover, u: Float, v: Float, out: FloatArray, at: Int) {
        val x0 = u.toInt().coerceIn(0, cover.w - 1)
        val y0 = v.toInt().coerceIn(0, cover.h - 1)
        val x1 = min(cover.w - 1, x0 + 1)
        val y1 = min(cover.h - 1, y0 + 1)
        val fx = (u - x0).coerceIn(0f, 1f)
        val fy = (v - y0).coerceIn(0f, 1f)
        for (ch in 0 until 3) {
            val sh = 16 - ch * 8
            fun p(x: Int, y: Int) = ((cover.argb[y * cover.w + x] shr sh) and 0xff) / 255f
            val a = p(x0, y0) + (p(x1, y0) - p(x0, y0)) * fx
            val b = p(x0, y1) + (p(x1, y1) - p(x0, y1)) * fx
            out[at + ch] = a + (b - a) * fy
        }
    }

    /** A box blur of [src] (three channels per cell), [r] cells each way. */
    private fun blurred(src: FloatArray, r: Int, channels: Int = 3): FloatArray {
        val tmp = FloatArray(src.size)
        val out = FloatArray(src.size)
        for (j in 0 until rows) for (i in 0 until cols) for (ch in 0 until channels) {
            var sum = 0f
            var n = 0
            for (k in max(0, i - r)..min(cols - 1, i + r)) {
                sum += src[(j * cols + k) * channels + ch]
                n++
            }
            tmp[(j * cols + i) * channels + ch] = sum / n
        }
        for (j in 0 until rows) for (i in 0 until cols) for (ch in 0 until channels) {
            var sum = 0f
            var n = 0
            for (k in max(0, j - r)..min(rows - 1, j + r)) {
                sum += tmp[(k * cols + i) * channels + ch]
                n++
            }
            out[(j * cols + i) * channels + ch] = sum / n
        }
        return out
    }

    /** The edges' directions: the lightness' slopes, smoothed so that strokes follow forms. */
    private fun edges(img: FloatArray) {
        val n = cols * rows
        val tensor = FloatArray(n * 3)
        fun l(i: Int, j: Int) = lum(img, (j.coerceIn(0, rows - 1) * cols + i.coerceIn(0, cols - 1)) * 3)
        for (j in 0 until rows) for (i in 0 until cols) {
            val gx = (l(i + 1, j - 1) + 2 * l(i + 1, j) + l(i + 1, j + 1)) - (l(i - 1, j - 1) + 2 * l(i - 1, j) + l(i - 1, j + 1))
            val gy = (l(i - 1, j + 1) + 2 * l(i, j + 1) + l(i + 1, j + 1)) - (l(i - 1, j - 1) + 2 * l(i, j - 1) + l(i + 1, j - 1))
            val k = (j * cols + i) * 3
            tensor[k] = gx * gx
            tensor[k + 1] = gx * gy
            tensor[k + 2] = gy * gy
        }
        val t = blurred(tensor, 2)
        along = FloatArray(n)
        edge = FloatArray(n)
        var top = 1e-6f
        for (i in 0 until n) {
            val xx = t[i * 3]
            val xy = t[i * 3 + 1]
            val yy = t[i * 3 + 2]
            // The slope's way, turned a quarter: along the edge.
            along[i] = 0.5f * atan2(2f * xy, xx - yy) + 1.5708f
            edge[i] = sqrt(xx + yy)
            top = max(top, edge[i])
        }
        val scale = 1f / (top * 0.35f)
        for (i in 0 until n) edge[i] = (edge[i] * scale).coerceIn(0f, 1f)
    }

    companion object {
        const val MULTIPLY = 0
        const val DARKEN = 1
        const val OVER = 2

        /** Cells along the canvas' long side. */
        private const val CELLS = 128

        /** How much of the canvas the cover takes, the shorter way. */
        private const val FILL = 0.94f

        /** How much the canvas round the cover counts (loose strokes there, not a copy). */
        private const val OUTSIDE = 0.18f

        private const val MAX_ALPHA = 0.8f

        fun blendOf(kind: Int): Int = when (kind) {
            ArtMark.VEIL, ArtMark.OIL, ArtMark.DOT -> OVER
            ArtMark.INK, ArtMark.LINE, ArtMark.PEN -> DARKEN
            else -> MULTIPLY
        }

        /** How much of its strength a mark of [kind] leaves on average where it lies (its texture, its gaps). */
        private fun coverage(kind: Int): Float = when (kind) {
            ArtMark.BLOOM -> 1f
            ArtMark.DROP -> 0.9f
            ArtMark.DAB -> 0.85f
            ArtMark.LINE -> 0.6f
            ArtMark.GLAZE -> 0.7f
            ArtMark.INK -> 1f
            ArtMark.OIL -> 0.85f
            ArtMark.DOT -> 0.9f
            ArtMark.PEN -> 0.9f
            ArtMark.PENCIL -> 0.6f
            ArtMark.SEAL -> 0.5f
            else -> 1f
        }

        private fun lum(a: FloatArray, i: Int) = 0.3f * a[i] + 0.59f * a[i + 1] + 0.11f * a[i + 2]

        private fun greyed(c: FloatArray): FloatArray {
            val l = 0.3f * c[0] + 0.59f * c[1] + 0.11f * c[2]
            return FloatArray(3) { l + (c[it] - l) * 0.15f }
        }
    }
}
