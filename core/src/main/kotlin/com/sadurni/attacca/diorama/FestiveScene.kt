package com.sadurni.attacca.diorama

import com.sadurni.attacca.diorama.Diorama.Companion.hash
import com.sadurni.attacca.festive.Festivity
import com.sadurni.attacca.festive.NewYear
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * A new year's show going on: which new year, how long since midnight (ms), and the greeting as
 * dots (x, y pairs in 0 … 1 of its box, the text drawn and sampled by the app, any script), which
 * lights in the sky (drones) gather into.
 */
class Celebration(val kind: NewYear, val elapsedMs: Long, val dots: FloatArray?)

/** How long the show lasts, and how long the lights take to gather into the greeting. */
private const val SHOW_MS = 5 * 60_000L
private const val GATHER_MS = 14_000L

// ---------- The festive look (some days a year) ----------

/** The festive things in the sky: a sleigh crossing it, the Kings' star, sky lanterns, the crescent, a dragon, cranes. */
internal fun Scene.festiveSky() {
    when (festive ?: return) {
        Festivity.CHRISTMAS, Festivity.ORTHODOX_CHRISTMAS -> {
            if (kings) {
                kingsStar()
            } else {
                sleigh()
            }
        }
        Festivity.DIWALI -> {
            skyLanterns(0xFFFFA640.toInt())
            smallFireworks(0.5f)
        }
        Festivity.EID -> crescent()
        Festivity.LUNAR_NEW_YEAR -> {
            dragon(28_000L, 0.18f)
            skyLanterns(0xFFE53935.toInt())
        }
        Festivity.SHOGATSU -> {
            risingSun()
            cranes()
        }
    }
}

/** The festive things in front: snow, the Kings on their camels, oil lamps along the way, lanterns hung across. */
internal fun Scene.festiveFront() {
    when (festive ?: return) {
        Festivity.CHRISTMAS, Festivity.ORTHODOX_CHRISTMAS -> {
            if (kings) kingsOnCamels()
            if (look.world != World.SPACE) snow()
        }
        Festivity.DIWALI -> diyas()
        Festivity.EID -> hungLanterns(0xFFD4AF37.toInt(), 0xFF2E8B7A.toInt())
        Festivity.LUNAR_NEW_YEAR -> hungLanterns(0xFFE53935.toInt(), 0xFFFFC94A.toInt())
        Festivity.SHOGATSU -> Unit
    }
}

/** Whether the traveller and its companions wear a Santa hat. */
internal val Scene.festiveHat: Boolean
    get() = festive == Festivity.CHRISTMAS || festive == Festivity.ORTHODOX_CHRISTMAS

/** A Santa hat on a head at [x], [y] of radius [r], its tip falling back. */
internal fun Scene.santaHat(x: Float, y: Float, r: Float) {
    val sway = sin(now / 260.0).toFloat() * r * 0.15f
    out.polygon(0xFFD32F2F.toInt())
    out.point(x - r * 1.0f, y - r * 0.35f); out.point(x + r * 0.95f, y - r * 0.45f)
    out.point(x - r * 0.4f, y - r * 1.55f); out.point(x - r * 1.5f + sway, y - r * 1.05f)
    out.circle(0xFFFFFFFF.toInt(), x - r * 1.55f + sway, y - r * 1.0f, r * 0.32f)
    out.line(0xFFFFFFFF.toInt(), r * 0.45f, x - r * 1.05f, y - r * 0.38f, x + r * 1.0f, y - r * 0.48f)
}

private fun Scene.snow() {
    val count = (70 * (0.6f + 0.6f * look.energy)).toInt()
    for (i in 0 until count) {
        val fall = 9000.0 + hash(i, 701) * 7000
        val v = ((now + hash(i, 702) * fall) % fall / fall).toFloat()
        val x = (hash(i, 703) * w + sin(now / 1500.0 + i).toFloat() * unit * 0.02f + w) % w
        val y = v * (h + unit * 0.05f) - unit * 0.02f
        val r = unit * (0.002f + 0.004f * hash(i, 704))
        out.circle(Colors.alpha(0xFFFFFFFF.toInt(), 0.55f + 0.4f * hash(i, 705)), x, y, r)
    }
}

/** Santa's sleigh with its reindeer, across the sky every half minute, sparkles behind. */
private fun Scene.sleigh() {
    val cycle = 30_000L
    val run = 9_000L
    val t = now % cycle
    if (t > run) return
    val u = t / run.toFloat()
    val s = unit * 0.035f
    val x = -unit * 0.4f + u * (w + unit * 0.8f)
    val y = h * (0.16f + 0.05f * sin(u * PI * 2).toFloat())
    // The trail of sparkles.
    for (k in 0 until 14) {
        val back = k * s * 0.9f
        val twinkle = hash((now / 90).toInt() + k, 711)
        out.circle(Colors.alpha(0xFFFFE9A8.toInt(), (1f - k / 14f) * 0.8f * twinkle), x - s * 2.4f - back, y + s * 0.2f + sin(k * 0.9 + now / 200.0).toFloat() * s * 0.25f, s * 0.12f)
    }
    // The sleigh, Santa in it.
    out.polygon(0xFFC62828.toInt())
    out.point(x - s * 2f, y - s * 0.2f); out.point(x - s * 0.6f, y - s * 0.2f); out.point(x - s * 0.4f, y + s * 0.5f); out.point(x - s * 2.1f, y + s * 0.5f)
    out.line(0xFFFFD54F.toInt(), s * 0.12f, x - s * 2.3f, y + s * 0.7f, x - s * 0.2f, y + s * 0.7f)
    out.circle(0xFFC62828.toInt(), x - s * 1.4f, y - s * 0.55f, s * 0.42f)
    out.circle(0xFFFFE0C0.toInt(), x - s * 1.25f, y - s * 1.0f, s * 0.24f)
    out.circle(0xFFFFFFFF.toInt(), x - s * 1.18f, y - s * 0.82f, s * 0.2f)
    santaHat(x - s * 1.25f, y - s * 1.02f, s * 0.24f)
    // Four reindeer ahead, galloping.
    for (k in 0 until 4) {
        val rx = x + s * (0.7f + k * 1.15f)
        val gallop = sin((now / 140.0 + k * 0.8)).toFloat()
        val ry = y - s * 0.15f + gallop * s * 0.12f
        out.line(0x99FFFFFF.toInt(), s * 0.05f, x - s * 0.5f, y, rx - s * 0.3f, ry)
        out.circle(0xFF8D5A3B.toInt(), rx, ry, s * 0.32f)
        out.circle(0xFF8D5A3B.toInt(), rx + s * 0.38f, ry - s * 0.3f, s * 0.17f)
        out.line(0xFF8D5A3B.toInt(), s * 0.09f, rx - s * 0.2f, ry + s * 0.2f, rx - s * 0.3f - gallop * s * 0.15f, ry + s * 0.6f)
        out.line(0xFF8D5A3B.toInt(), s * 0.09f, rx + s * 0.2f, ry + s * 0.2f, rx + s * 0.3f + gallop * s * 0.15f, ry + s * 0.6f)
        out.line(0xFFEAD7B5.toInt(), s * 0.05f, rx + s * 0.38f, ry - s * 0.45f, rx + s * 0.25f, ry - s * 0.75f)
        out.line(0xFFEAD7B5.toInt(), s * 0.05f, rx + s * 0.45f, ry - s * 0.45f, rx + s * 0.62f, ry - s * 0.72f)
        if (k == 3) out.circle(0xFFFF3B30.toInt(), rx + s * 0.55f, ry - s * 0.28f, s * 0.07f)
    }
}

/** The star the Kings follow: big, with its tail, high over the way ahead. */
private fun Scene.kingsStar() {
    val x = w * 0.78f
    val y = h * 0.14f
    val r = unit * 0.022f * (1f + 0.15f * kick)
    for (k in 0 until 12) {
        val back = k / 12f
        out.circle(Colors.alpha(0xFFFFF3C4.toInt(), 0.18f * (1f - back)), x - back * unit * 0.32f, y - back * unit * 0.06f, r * (1.4f - back))
    }
    out.circle(Colors.alpha(0xFFFFF3C4.toInt(), 0.25f), x, y, r * 2.4f)
    out.polygon(0xFFFFF3C4.toInt())
    for (m in 0 until 8) {
        val a = m * PI / 4 - PI / 2
        val rr = if (m % 2 == 0) r * 1.8f else r * 0.5f
        out.point(x + (cos(a) * rr).toFloat(), y + (sin(a) * rr).toFloat())
    }
}

/** The three Kings on their camels, following the traveller along the way, towards the star. */
private fun Scene.kingsOnCamels() {
    val s = unit * 0.026f
    val robes = intArrayOf(0xFF7B1FA2.toInt(), 0xFF1565C0.toInt(), 0xFF2E7D32.toInt())
    for (k in 0 until 3) {
        val x = travellerX - travellerSize * 1.4f - k * s * 3.4f
        if (x < -s * 3f) continue
        val ground = nearY(x)
        val step = sin(now / 300.0 + k).toFloat()
        val camel = 0xFFC8A36A.toInt()
        out.circle(camel, x, ground - s * 1.25f, s * 0.75f)
        out.circle(camel, x - s * 0.2f, ground - s * 1.8f, s * 0.35f)
        out.line(camel, s * 0.3f, x + s * 0.6f, ground - s * 1.25f, x + s * 1.1f, ground - s * 2.05f)
        out.circle(camel, x + s * 1.2f, ground - s * 2.1f, s * 0.22f)
        for (leg in 0 until 2) {
            val lx = x + (leg - 0.5f) * s * 0.9f
            out.line(camel, s * 0.16f, lx, ground - s * 0.75f, lx + step * s * 0.15f * (if (leg == 0) 1 else -1), ground)
        }
        out.circle(robes[k], x - s * 0.15f, ground - s * 2.35f, s * 0.38f)
        out.circle(0xFFFFE0C0.toInt(), x - s * 0.1f, ground - s * 2.9f, s * 0.2f)
        out.polygon(0xFFFFD54F.toInt())
        out.point(x - s * 0.3f, ground - s * 3.05f); out.point(x + s * 0.1f, ground - s * 3.05f); out.point(x - s * 0.1f, ground - s * 3.4f)
    }
}

/** Sky lanterns rising slowly, swaying, glowing. */
private fun Scene.skyLanterns(color: Int) {
    for (i in 0 until 9) {
        val rise = 26_000.0 + hash(i, 721) * 14_000
        val v = ((now + hash(i, 722) * rise) % rise / rise).toFloat()
        val x = w * (0.08f + 0.84f * hash(i, 723)) + sin(now / 2000.0 + i).toFloat() * unit * 0.02f
        val y = h * (0.75f - v * 0.75f)
        val s = unit * (0.012f + 0.01f * hash(i, 724))
        val a = if (v < 0.1f) v * 10f else if (v > 0.85f) (1f - v) / 0.15f else 1f
        out.circle(Colors.alpha(color, 0.18f * a), x, y, s * 2.2f)
        out.polygon(Colors.alpha(color, 0.95f * a))
        out.point(x - s * 0.6f, y - s); out.point(x + s * 0.6f, y - s); out.point(x + s * 0.45f, y + s); out.point(x - s * 0.45f, y + s)
        out.circle(Colors.alpha(0xFFFFF3C4.toInt(), 0.9f * a), x, y + s * 0.6f, s * 0.25f)
    }
}

/** A few small fireworks now and then. */
private fun Scene.smallFireworks(f: Float) {
    for (k in 0 until 2) {
        val cycle = 2600L
        val t = now + k * 1300L
        val e = (t / cycle).toInt() * 7 + k
        val b = (t % cycle) / cycle.toFloat()
        if (b > 0.6f) continue
        val u = b / 0.6f
        val fx = w * (0.15f + 0.7f * hash(e, 731))
        val fy = h * (0.1f + 0.25f * hash(e, 732))
        val c = Colors.fromHsl(255, hash(e, 733) * 360f, 0.85f, 0.62f)
        val rad = unit * 0.07f * u
        for (m in 0 until 12) {
            val a = m * PI / 6
            out.circle(Colors.alpha(c, (1f - u) * f), fx + (cos(a) * rad).toFloat(), fy + (sin(a) * rad).toFloat() + u * u * unit * 0.02f, unit * 0.004f)
        }
    }
}

/** The crescent moon and its star. */
private fun Scene.crescent() {
    val x = w * 0.72f
    val y = h * 0.17f
    val r = unit * 0.05f
    out.circle(Colors.alpha(0xFFFFF3C4.toInt(), 0.12f), x, y, r * 2f)
    out.circle(0xFFFFF3C4.toInt(), x, y, r)
    out.circle(Colors.mix(p.skyTop, p.skyBottom, 0.25f), x + r * 0.42f, y - r * 0.18f, r * 0.86f)
    val sx = x + r * 0.9f
    val sy = y - r * 0.2f
    val sr = r * 0.32f
    out.polygon(0xFFFFF3C4.toInt())
    for (m in 0 until 10) {
        val a = m * PI / 5 - PI / 2
        val rr = if (m % 2 == 0) sr else sr * 0.42f
        out.point(sx + (cos(a) * rr).toFloat(), sy + (sin(a) * rr).toFloat())
    }
}

/** Lanterns hung on a string across the top of the picture, swinging a little with the beat. */
private fun Scene.hungLanterns(color: Int, trim: Int) {
    val n = 7
    val sag = h * 0.05f
    out.polyline(Colors.alpha(0xFF3E2723.toInt(), 0.8f), unit * 0.003f)
    for (m in 0..20) {
        val u = m / 20f
        out.point(u * w, h * 0.02f + sin(u * PI).toFloat() * sag)
    }
    for (k in 0 until n) {
        val u = (k + 0.5f) / n
        val x = u * w
        val top = h * 0.02f + sin(u * PI).toFloat() * sag
        val swing = sin(now / 700.0 + k).toFloat() * unit * 0.004f + kick * unit * 0.003f
        val s = unit * 0.022f
        val cx = x + swing
        val cy = top + s * 1.4f
        out.line(Colors.alpha(0xFF3E2723.toInt(), 0.8f), unit * 0.002f, x, top, cx, cy - s)
        out.circle(Colors.alpha(color, 0.2f), cx, cy, s * 1.9f)
        out.circle(color, cx, cy, s)
        out.rect(trim, cx - s * 0.5f, cy - s * 1.1f, cx + s * 0.5f, cy - s * 0.85f)
        out.rect(trim, cx - s * 0.5f, cy + s * 0.85f, cx + s * 0.5f, cy + s * 1.1f)
        out.line(trim, unit * 0.002f, cx, cy + s * 1.1f, cx, cy + s * 1.6f)
    }
}

/** Little oil lamps along the way, their flames flickering. */
private fun Scene.diyas() {
    val gap = unit * 0.12f
    val offset = ((posMs / msPerPx) % gap).toFloat()
    var x = -offset
    var i = ((posMs / msPerPx) / gap).toInt()
    while (x < w + gap) {
        val ground = nearY(x)
        val s = unit * 0.012f
        out.circle(Colors.alpha(0xFFFFB74D.toInt(), 0.18f), x, ground - s * 1.6f, s * 3f)
        out.polygon(0xFFB5541E.toInt())
        out.point(x - s * 1.2f, ground - s * 0.6f); out.point(x + s * 1.2f, ground - s * 0.6f); out.point(x + s * 0.6f, ground); out.point(x - s * 0.6f, ground)
        val flicker = 0.8f + 0.2f * sin(now / 70.0 + i * 1.3).toFloat()
        out.polygon(0xFFFFC107.toInt())
        out.point(x - s * 0.35f, ground - s * 0.7f); out.point(x + s * 0.35f, ground - s * 0.7f); out.point(x + s * 0.05f, ground - s * (1.9f * flicker))
        x += gap
        i++
    }
}

/** A long dragon flying across the sky every [cycle] ms, its body waving. */
private fun Scene.dragon(cycle: Long, height: Float, f: Float = 1f) {
    val run = 11_000L
    val t = now % cycle
    if (t > run) return
    val u = t / run.toFloat()
    val s = unit * 0.022f
    val headX = -unit * 0.3f + u * (w + unit * 1.0f)
    val baseY = h * height
    val segments = 18
    for (k in segments downTo 0) {
        val x = headX - k * s * 1.25f
        val y = baseY + sin(now / 260.0 - k * 0.55).toFloat() * s * 1.6f
        val c = if (k % 2 == 0) 0xFFE53935.toInt() else 0xFFFFC94A.toInt()
        out.circle(Colors.alpha(c, f), x, y, s * (if (k == 0) 1.2f else 1f - k * 0.025f))
        if (k % 3 == 1) out.line(Colors.alpha(0xFFFFC94A.toInt(), f), s * 0.18f, x, y - s * 0.8f, x - s * 0.3f, y - s * 1.4f)
    }
    val hy = baseY + sin(now / 260.0).toFloat() * s * 1.6f
    out.circle(Colors.alpha(0xFFFFFFFF.toInt(), f), headX + s * 0.4f, hy - s * 0.35f, s * 0.22f)
    out.line(Colors.alpha(0xFFFFC94A.toInt(), f), s * 0.12f, headX + s * 0.9f, hy + s * 0.2f, headX + s * 1.9f, hy + s * 0.6f)
    out.line(Colors.alpha(0xFFFFC94A.toInt(), f), s * 0.12f, headX + s * 0.9f, hy, headX + s * 2.0f, hy - s * 0.3f)
}

/** A big red sun low over the land. */
private fun Scene.risingSun() {
    val x = w * 0.62f
    val y = h * 0.34f
    val r = unit * 0.1f
    out.circle(Colors.alpha(0xFFD7263D.toInt(), 0.15f), x, y, r * 1.5f)
    out.circle(0xFFD7263D.toInt(), x, y, r)
}

/** Cranes flying across in a line, their wings beating. */
private fun Scene.cranes() {
    val cycle = 24_000L
    val run = 14_000L
    val t = now % cycle
    if (t > run) return
    val u = t / run.toFloat()
    for (k in 0 until 5) {
        val x = w + unit * 0.2f - u * (w + unit * 0.8f) + k * unit * 0.07f
        val y = h * (0.2f + k * 0.025f)
        val s = unit * 0.02f
        val flap = sin(now / 180.0 + k).toFloat()
        out.polyline(0xFFFFFFFF.toInt(), s * 0.18f)
        out.point(x - s * 1.6f, y - flap * s); out.point(x, y); out.point(x + s * 1.6f, y - flap * s)
        out.line(0xFFFFFFFF.toInt(), s * 0.22f, x - s * 0.9f, y + s * 0.05f, x + s * 0.9f, y + s * 0.05f)
        out.circle(0xFFD7263D.toInt(), x - s * 1.0f, y - s * 0.05f, s * 0.15f)
    }
}

// ---------- The new year's show ----------

/** The show at midnight: big fireworks, the new year's own things, and lights gathering into the greeting. */
internal fun Scene.celebration() {
    val c = show ?: return
    val t = c.elapsedMs.coerceAtLeast(0)
    if (t > SHOW_MS) return
    val f = when {
        t < 2000 -> t / 2000f
        t > SHOW_MS - 8000 -> (SHOW_MS - t) / 8000f
        else -> 1f
    }
    // A darker sky for the lights to shine.
    out.rect(Colors.alpha(0xFF05060C.toInt(), 0.45f * f), 0f, 0f, w, h)
    val colors = when (c.kind) {
        NewYear.LUNAR -> intArrayOf(0xFFE53935.toInt(), 0xFFFFC94A.toInt(), 0xFFFF7043.toInt())
        NewYear.HIJRI -> intArrayOf(0xFFD4AF37.toInt(), 0xFFFFF3C4.toInt(), 0xFF4DB6AC.toInt())
        NewYear.YENNAYER -> intArrayOf(0xFF1E88E5.toInt(), 0xFF43A047.toInt(), 0xFFFDD835.toInt())
        NewYear.ENKUTATASH -> intArrayOf(0xFFFDD835.toInt(), 0xFF43A047.toInt(), 0xFFE53935.toInt())
        NewYear.VIKRAM -> intArrayOf(0xFFFF9F1C.toInt(), 0xFFFFD54F.toInt(), 0xFFE040FB.toInt())
        NewYear.GREGORIAN -> intArrayOf(0xFFFFD54F.toInt(), 0xFF64B5F6.toInt(), 0xFFF06292.toInt())
    }
    showFireworks(f, colors)
    when (c.kind) {
        NewYear.LUNAR -> {
            dragon(16_000L, 0.55f, f)
            skyLanterns(0xFFE53935.toInt())
        }
        NewYear.HIJRI -> crescent()
        NewYear.ENKUTATASH -> flowersFalling(f)
        NewYear.VIKRAM -> diyas()
        else -> Unit
    }
    c.dots?.let { drones(it, t, f, colors) }
}

/** Many big fireworks at once, of the show's colours. */
private fun Scene.showFireworks(f: Float, colors: IntArray) {
    for (k in 0 until 9) {
        val cycle = 1700L + (k % 3) * 300L
        val t = now + k * 410L
        val e = (t / cycle).toInt() * 17 + k
        val b = (t % cycle) / cycle.toFloat()
        val fx = w * (0.06f + 0.88f * hash(e, 741))
        val fy = h * (0.08f + 0.5f * hash(e, 742))
        val c = colors[(e and 0x7fffffff) % colors.size]
        val rad = unit * (0.12f + 0.1f * hash(e, 743)) * (0.25f + 0.75f * b)
        val rays = 18 + (k % 3) * 4
        if (b < 0.08f) {
            // The rocket going up.
            out.line(Colors.alpha(0xFFFFF3C4.toInt(), f), unit * 0.003f, fx, fy + unit * 0.3f * (1f - b / 0.08f), fx, fy + unit * 0.33f * (1f - b / 0.08f))
            continue
        }
        for (m in 0 until rays) {
            val a = m * 2 * PI / rays
            val ex = fx + (cos(a) * rad).toFloat()
            val ey = fy + (sin(a) * rad).toFloat() + b * b * unit * 0.05f
            out.line(Colors.alpha(c, (1f - b) * 0.45f * f), unit * 0.003f, fx + (ex - fx) * 0.6f, fy + (ey - fy) * 0.6f, ex, ey)
            out.circle(Colors.alpha(c, (1f - b) * f), ex, ey, unit * 0.005f)
        }
        out.circle(Colors.alpha(0xFFFFFFFF.toInt(), (1f - b) * 0.25f * f), fx, fy, rad * 0.25f)
    }
}

/** Yellow daisies falling (the flowers of the Ethiopian new year). */
private fun Scene.flowersFalling(f: Float) {
    for (i in 0 until 26) {
        val fall = 11_000.0 + hash(i, 751) * 6000
        val v = ((now + hash(i, 752) * fall) % fall / fall).toFloat()
        val x = hash(i, 753) * w + sin(now / 1100.0 + i).toFloat() * unit * 0.03f
        val y = v * h
        val s = unit * 0.008f
        for (m in 0 until 6) {
            val a = m * PI / 3 + now / 900.0
            out.circle(Colors.alpha(0xFFFDD835.toInt(), f), x + (cos(a) * s).toFloat(), y + (sin(a) * s).toFloat(), s * 0.6f)
        }
        out.circle(Colors.alpha(0xFF8D6E63.toInt(), f), x, y, s * 0.5f)
    }
}

/**
 * The lights in the sky (drones): from all over they gather into the greeting's [dots], then hold
 * it, twinkling, their colours going round; at the end they scatter.
 */
private fun Scene.drones(dots: FloatArray, t: Long, f: Float, colors: IntArray) {
    val n = dots.size / 2
    if (n == 0) return
    // The greeting's box keeps the shape it was drawn in (520 × 150).
    val boxH = min(h * 0.3f, w * 0.86f * 150f / 520f)
    val boxW = boxH * 520f / 150f
    val left = (w - boxW) / 2f
    // Upright, below the title box at the top; wide, high in the sky.
    val top = if (h > w) h * 0.2f else h * 0.1f
    val gather = (t / GATHER_MS.toFloat()).coerceIn(0f, 1f)
    val g = gather * gather * (3f - 2f * gather)
    val scatter = if (t > SHOW_MS - 10_000) ((t - (SHOW_MS - 10_000)) / 10_000f).coerceIn(0f, 1f) else 0f
    val r = unit * 0.0045f
    for (k in 0 until n) {
        val tx = left + dots[2 * k] * boxW
        val ty = top + dots[2 * k + 1] * boxH
        val sx = hash(k, 761) * w
        val sy = h * (0.3f + 0.7f * hash(k, 762))
        val wobble = sin(now / 500.0 + k * 0.7).toFloat() * unit * 0.002f
        var x = sx + (tx - sx) * g + wobble
        var y = sy + (ty - sy) * g
        if (scatter > 0f) {
            x += (hash(k, 763) - 0.5f) * w * scatter
            y -= hash(k, 764) * h * 0.5f * scatter
        }
        val wave = ((now / 40 + k * 3) / 60) % colors.size
        val c = colors[wave.toInt()]
        val twinkle = 0.75f + 0.25f * sin(now / 260.0 + k).toFloat()
        val a = f * twinkle * (1f - scatter)
        out.circle(Colors.alpha(c, 0.22f * a), x, y, r * 2.4f)
        out.circle(Colors.alpha(0xFFFFFFFF.toInt(), a), x, y, r)
    }
}
