package com.sadurni.attacca.diorama

import com.sadurni.attacca.diorama.Diorama.Companion.hash
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// The lands of the worlds added later (desert, the great wall, India's jungle, the Amazon, the
// canyon, Japan, the bottom of the sea, the volcano): their far, middle and near layers, made of
// the song's wave as the others are, with what stands in each.

/** Draws [world]'s land, if it is one of these; false for the others. */
internal fun Scene.newLand(world: World): Boolean {
    when (world) {
        World.DESERT -> { dunesFar(); pyramidsFar(); dunesMid(); caravan(); sandNear() }
        World.WALL -> { mistyPeaks(); wallRidge(); grassNear(lanterns = true) }
        World.JUNGLE -> { softFar(); jungleMid(); jungleNear() }
        World.AMAZON -> { softFar(); amazonTrees(); riverNear(); macaws() }
        World.CANYON -> { mesasFar(); buttesMid(); desertNear() }
        World.JAPAN -> { fujiFar(); cherryHills(); grassNear(lanterns = false); torii(); petals() }
        World.REEF -> { lightRays(); reefFar(); coralMid(); fish(); seaFloor(); bubbles() }
        World.VOLCANO -> { volcanoFar(); lavaMid(); ashNear() }
        else -> return false
    }
    return true
}

// ---------- Shared pieces ----------

/** A layer's outline from [y] across the screen, filled down to the bottom. */
private inline fun Scene.fill(color: Int, y: (Float) -> Float) {
    out.polygon(color)
    out.point(0f, bottom)
    var x = 0f
    while (x <= w + step) {
        out.point(x, y(x))
        x += step
    }
    out.point(w, bottom)
}

private fun Scene.groundFill(color: Int) = fill(color) { nearY(it) }

private fun Scene.palm(x: Float, y: Float, s: Float, seed: Int) {
    val trunk = Colors.darker(0xFF8A6A44.toInt(), 0.1f + 0.4f * look.night)
    out.polyline(trunk, s * 0.08f)
    out.point(x, y); out.point(x + s * 0.12f, y - s * 0.5f); out.point(x + s * 0.28f, y - s)
    val leaf = Colors.mix(0xFF3E8A3A.toInt(), p.mid, 0.3f)
    val sway = sin(now / 800.0 + seed).toFloat() * s * 0.05f * (0.5f + live.level)
    for (k in 0 until 6) {
        val a = -PI * (k / 5.0)
        val ex = x + s * 0.28f + (s * 0.5f * cos(a)).toFloat() + sway
        val ey = y - s + (s * 0.2f * -sin(a)).toFloat() + s * 0.18f
        out.polyline(leaf, s * 0.07f)
        out.point(x + s * 0.28f, y - s); out.point((x + s * 0.28f + ex) / 2f, y - s - s * 0.12f); out.point(ex, ey)
    }
}

/** A camel in a few strokes (the caravans far off, and the one ridden). */
internal fun Scene.camelShape(x: Float, ground: Float, s: Float, phase: Float, color: Int, dir: Float = 1f) {
    val lw = s * 0.09f
    val bodyY = ground - s * 0.75f
    for (k in 0 until 4) {
        val lx = x + dir * s * (if (k < 2) 0.32f else -0.32f) + (k % 2) * dir * s * 0.06f
        val f = (phase + k * 0.25f) * 2 * PI
        val sw = (sin(f) * s * 0.12f).toFloat()
        out.polyline(color, lw)
        out.point(lx, bodyY); out.point(lx + dir * sw * 0.6f, bodyY + s * 0.38f); out.point(lx + dir * sw, ground)
    }
    out.circle(color, x - dir * s * 0.1f, bodyY - s * 0.05f, s * 0.36f)
    out.circle(color, x - dir * s * 0.12f, bodyY - s * 0.32f, s * 0.2f) // the hump
    out.circle(color, x + dir * s * 0.25f, bodyY - s * 0.02f, s * 0.26f)
    // The long neck down and up, the head.
    val nod = sin(phase * 2 * PI).toFloat() * s * 0.03f
    out.polyline(color, lw * 1.6f)
    out.point(x + dir * s * 0.42f, bodyY); out.point(x + dir * s * 0.66f, bodyY - s * 0.1f + nod); out.point(x + dir * s * 0.72f, bodyY - s * 0.45f + nod)
    out.circle(color, x + dir * s * 0.8f, bodyY - s * 0.48f + nod, s * 0.11f)
    out.polyline(color, lw * 0.6f)
    out.point(x - dir * s * 0.42f, bodyY - s * 0.05f); out.point(x - dir * s * 0.5f, bodyY + s * 0.2f)
}

// ---------- Desert ----------

private fun Scene.dunesFar() = fill(p.far) { farBase + h * 0.04f - heightAt(it, Scene.ZOOM_FAR, 60f) * farAmp * 0.55f }

private fun Scene.pyramidsFar() {
    slotsMs(Scene.ZOOM_FAR, 80_000.0) { i, x0, x1 ->
        if (hash(i, 801) > 0.55f) return@slotsMs
        val cx = x0 + (x1 - x0) * hash(i, 802)
        val s = h * (0.12f + 0.08f * hash(i, 803))
        // Standing on the dunes all along its base: its foot where the sand is lowest under it
        // (a foot taken only at its middle left a corner in the air over a dip).
        var foot = 0f
        for (k in 0..8) {
            val x = cx - s + 2 * s * k / 8f
            foot = maxOf(foot, farBase + h * 0.04f - heightAt(x, Scene.ZOOM_FAR, 60f) * farAmp * 0.55f)
        }
        foot += h * 0.01f
        val lit = Colors.lighter(p.far, 0.18f)
        val shade = Colors.darker(p.far, 0.22f)
        out.polygon(lit)
        out.point(cx - s, foot); out.point(cx, foot - s * 0.9f); out.point(cx + s * 0.15f, foot)
        out.polygon(shade)
        out.point(cx + s * 0.15f, foot); out.point(cx, foot - s * 0.9f); out.point(cx + s, foot)
    }
}

private fun Scene.dunesMid() {
    fill(p.mid) { midBase + h * 0.02f - heightAt(it, Scene.ZOOM_MID, 40f) * midAmp * 0.6f }
    // An oasis now and then: water and palms.
    slotsMs(Scene.ZOOM_MID, 50_000.0) { i, x0, x1 ->
        if (hash(i, 811) > 0.45f) return@slotsMs
        val cx = x0 + (x1 - x0) * hash(i, 812)
        val y = midBase + h * 0.02f - heightAt(cx, Scene.ZOOM_MID, 40f) * midAmp * 0.6f + h * 0.01f
        val water = Colors.mix(0xFF3FA0D0.toInt(), p.skyTop, 0.3f)
        out.circle(water, cx - h * 0.03f, y, h * 0.022f)
        out.circle(water, cx + h * 0.03f, y, h * 0.022f)
        out.rect(water, cx - h * 0.03f, y - h * 0.022f, cx + h * 0.03f, y + h * 0.022f)
        for (k in 0..2) palm(cx + (k - 1) * h * 0.05f, y - h * 0.005f, h * (0.07f + 0.02f * k), i * 3 + k)
    }
}

private fun Scene.caravan() {
    slotsMs(Scene.ZOOM_MID, 45_000.0) { i, x0, x1 ->
        if (hash(i, 821) > 0.6f) return@slotsMs
        val cx = x0 + (x1 - x0) * 0.5f + ((now / 40.0) % 2000.0).toFloat() * 0f
        val s = h * 0.035f
        val color = Colors.darker(p.mid, 0.55f)
        val phase = ((now / 900.0) % 1.0).toFloat()
        for (k in 0 until 3) {
            val x = cx + k * s * 2.4f
            val g = midBase + h * 0.02f - heightAt(x, Scene.ZOOM_MID, 40f) * midAmp * 0.6f + s * 0.1f
            camelShape(x, g, s, phase + k * 0.3f, color)
        }
    }
}

private fun Scene.sandNear() {
    groundFill(p.near)
    val ripple = Colors.alpha(Colors.darker(p.near, 0.12f), 0.6f)
    slots(1f, h * 0.05f) { i, x0, _ ->
        val y = nearY(x0) + h * (0.02f + 0.03f * hash(i, 831))
        out.line(ripple, h * 0.003f, x0, y, x0 + h * 0.03f, y - h * 0.004f)
        if (dense(i, 832, 0.06f, x0, 1f)) {
            // A dry tuft or a bleached rock.
            val g = nearY(x0)
            out.circle(Colors.darker(p.near, 0.2f), x0, g, h * 0.012f)
        }
    }
}

// ---------- The great wall ----------

private fun Scene.mistyPeaks() {
    fill(Colors.mix(p.far, p.skyBottom, 0.35f)) { x ->
        val v = heightAt(x, Scene.ZOOM_FAR * 1.3f, 20f)
        farBase - v * farAmp * 1.1f + (if ((x / (h * 0.06f)).toInt() % 2 == 0) h * 0.015f else 0f)
    }
}

private fun Scene.wallRidge() {
    fun ridge(x: Float) = midBase - heightAt(x, Scene.ZOOM_MID, 22f) * midAmp * 1.15f
    fill(p.mid) { ridge(it) }
    // The wall along the ridge: a thick band with crenellations, towers every so often.
    val stone = Colors.mix(0xFFB8A888.toInt(), p.mid, 0.25f)
    val dark = Colors.darker(stone, 0.3f)
    out.polyline(stone, h * 0.018f)
    var x = 0f
    while (x <= w + step) {
        out.point(x, ridge(x) - h * 0.012f)
        x += step
    }
    x = 0f
    var k = 0
    while (x <= w) {
        if (k % 2 == 0) out.rect(stone, x, ridge(x) - h * 0.03f, x + step * 0.9f, ridge(x) - h * 0.018f)
        x += step * 1.2f
        k++
    }
    slots(Scene.ZOOM_MID, h * 0.16f) { i, x0, x1 ->
        val cx = (x0 + x1) / 2f
        val y = ridge(cx) - h * 0.012f
        val tw = h * 0.035f
        out.rect(stone, cx - tw, y - h * 0.06f, cx + tw, y + h * 0.01f)
        out.rect(dark, cx - tw * 0.3f, y - h * 0.035f, cx + tw * 0.3f, y - h * 0.015f)
        out.polygon(Colors.mix(0xFF8A2A22.toInt(), p.mid, 0.2f))
        out.point(cx - tw * 1.3f, y - h * 0.06f); out.point(cx, y - h * 0.09f); out.point(cx + tw * 1.3f, y - h * 0.06f)
        if (hash(i, 841) < 0.25f) pagoda(cx + (x1 - x0) * 0.5f, footMid(cx + (x1 - x0) * 0.5f, h * 0.02f), h * 0.07f)
    }
}

private fun Scene.pagoda(cx: Float, ground: Float, s: Float) {
    val wall = Colors.mix(0xFFC0392B.toInt(), p.mid, 0.2f)
    val roof = Colors.darker(p.mid, 0.5f)
    var y = ground
    var wdt = s * 0.5f
    for (k in 0 until 3) {
        out.rect(wall, cx - wdt * 0.7f, y - s * 0.3f, cx + wdt * 0.7f, y)
        out.polygon(roof)
        out.point(cx - wdt * 1.25f, y - s * 0.26f); out.point(cx - wdt * 0.5f, y - s * 0.42f); out.point(cx + wdt * 0.5f, y - s * 0.42f); out.point(cx + wdt * 1.25f, y - s * 0.26f)
        y -= s * 0.42f
        wdt *= 0.78f
    }
    out.line(roof, s * 0.04f, cx, y, cx, y - s * 0.2f)
}

private fun Scene.grassNear(lanterns: Boolean) {
    groundFill(p.near)
    val blade = Colors.lighter(p.near, 0.15f)
    val sway = sin(beat * 2 * PI).toFloat() * h * 0.004f * (0.5f + live.level)
    slots(1f, h * 0.022f) { i, x0, _ ->
        val y = nearY(x0)
        out.line(blade, h * 0.003f, x0, y + 1f, x0 + sway, y - h * 0.016f * (0.5f + hash(i, 851)))
        if (lanterns && i % 17 == 3 && hash(i, 852) < 0.6f) {
            // A red lantern on a post, glowing with the sound.
            out.line(Colors.darker(p.near, 0.5f), h * 0.004f, x0, y, x0, y - h * 0.08f)
            val glow = if (look.night > 0.3f) 0.35f else 0.15f
            out.circle(Colors.alpha(0xFFFF5A3A.toInt(), glow + 0.2f * kick), x0, y - h * 0.08f, h * 0.025f)
            out.circle(0xFFD8322A.toInt(), x0, y - h * 0.08f, h * 0.013f)
        }
    }
}

// ---------- India's jungle ----------

private fun Scene.softFar() = fill(p.far) { farBase - heightAt(it, Scene.ZOOM_FAR, 40f) * farAmp * 0.8f }

private fun Scene.jungleMid() {
    fun top(x: Float) = midY(x)
    fill(p.mid) { top(it) }
    val dark = Colors.darker(p.mid, 0.25f)
    val light = Colors.lighter(p.mid, 0.12f)
    slots(Scene.ZOOM_MID, h * 0.035f) { i, x0, x1 ->
        val cx = (x0 + x1) / 2f
        val y = top(cx)
        out.circle(if (i % 2 == 0) dark else light, cx, y - h * 0.01f, h * (0.028f + 0.016f * hash(i, 861)))
    }
    // A temple rising out of the trees now and then; an elephant at their foot.
    slotsMs(Scene.ZOOM_MID, 55_000.0) { i, x0, x1 ->
        val cx = x0 + (x1 - x0) * hash(i, 862)
        if (hash(i, 863) < 0.55f) temple(cx, footMid(cx, h * 0.03f) + h * 0.01f, h * 0.13f)
        else if (hash(i, 864) < 0.5f) elephantShape(cx, footMid(cx, h * 0.02f), h * 0.03f, ((now / 1300.0) % 1.0).toFloat(), Colors.darker(p.mid, 0.5f), null)
    }
}

/** A temple: a stepped tower of warm stone with a pointed top, its doorway lit at night. */
private fun Scene.temple(cx: Float, ground: Float, s: Float) {
    val stone = Colors.mix(0xFFC8955A.toInt(), p.mid, 0.25f)
    val shade = Colors.darker(stone, 0.25f)
    var y = ground
    var half = s * 0.42f
    for (k in 0 until 5) {
        val th = s * (0.2f - k * 0.02f)
        out.rect(if (k % 2 == 0) stone else shade, cx - half, y - th, cx + half, y)
        y -= th
        half *= 0.8f
    }
    out.polygon(stone)
    out.point(cx - half, y); out.point(cx, y - s * 0.28f); out.point(cx + half, y)
    out.circle(Colors.mix(stone, p.accent, 0.5f), cx, y - s * 0.3f, s * 0.035f)
    val door = if (look.night > 0.4f) p.light else Colors.darker(stone, 0.6f)
    out.rect(door, cx - s * 0.06f, ground - s * 0.15f, cx + s * 0.06f, ground)
}

internal fun Scene.elephantShape(x: Float, ground: Float, s: Float, phase: Float, color: Int, rider: Int?) {
    val lw = s * 0.22f
    val bodyY = ground - s * 0.78f
    for (k in 0 until 4) {
        val lx = x + s * (if (k < 2) 0.38f else -0.36f) + (k % 2) * s * 0.08f
        val sw = (sin((phase + k * 0.25f) * 2 * PI) * s * 0.07f).toFloat()
        out.line(Colors.darker(color, if (k % 2 == 0) 0.15f else 0f), lw, lx, bodyY + s * 0.1f, lx + sw, ground)
    }
    out.circle(color, x, bodyY, s * 0.5f)
    out.rect(color, x - s * 0.5f, bodyY - s * 0.3f, x + s * 0.5f, bodyY + s * 0.3f)
    out.circle(color, x + s * 0.6f, bodyY - s * 0.2f, s * 0.3f)
    out.circle(Colors.darker(color, 0.1f), x + s * 0.45f, bodyY - s * 0.18f, s * 0.22f) // ear
    // The trunk, swinging.
    val sw = sin(phase * 2 * PI).toFloat() * s * 0.12f
    out.polyline(color, s * 0.12f)
    out.point(x + s * 0.82f, bodyY - s * 0.15f); out.point(x + s * 0.95f + sw * 0.5f, bodyY + s * 0.2f); out.point(x + s * 0.9f + sw, bodyY + s * 0.55f)
    out.line(0xFFF4EEDC.toInt(), s * 0.05f, x + s * 0.8f, bodyY + s * 0.0f, x + s * 0.95f, bodyY + s * 0.12f)
    out.line(color, s * 0.05f, x - s * 0.55f, bodyY - s * 0.1f, x - s * 0.65f, bodyY + s * 0.3f)
    // A cloth on its back.
    out.rect(rider ?: Colors.mix(p.accent, 0xFFD8322A.toInt(), 0.5f), x - s * 0.3f, bodyY - s * 0.48f, x + s * 0.25f, bodyY - s * 0.2f)
}

private fun Scene.jungleNear() {
    groundFill(p.near)
    val fern = Colors.lighter(p.near, 0.15f)
    slots(1f, h * 0.035f) { i, x0, _ ->
        val y = nearY(x0)
        val s = h * 0.03f * (0.6f + hash(i, 871))
        for (k in -2..2) out.line(fern, h * 0.004f, x0, y + 1f, x0 + k * s * 0.35f, y - s * (1f - abs(k) * 0.2f))
        if (i % 23 == 7 && hash(i, 872) < 0.5f) peacock(x0, y, h * 0.03f)
    }
}

private fun Scene.peacock(x: Float, ground: Float, s: Float) {
    val open = (0.5f + 0.5f * sin(now / 1500.0).toFloat()).coerceAtLeast(kick)
    val fan = Colors.mix(0xFF1E8C6E.toInt(), p.near, 0.2f)
    for (k in 0 until 9) {
        val a = PI * (0.15 + 0.7 * k / 8.0)
        val r = s * (0.6f + 0.9f * open)
        val ex = x - s * 0.2f + (cos(a) * r).toFloat() * -1f
        val ey = ground - s * 0.6f - (sin(a) * r).toFloat()
        out.line(fan, s * 0.08f, x - s * 0.2f, ground - s * 0.6f, ex, ey)
        out.circle(0xFF2A5FB0.toInt(), ex, ey, s * 0.08f)
    }
    out.circle(0xFF1F5FA8.toInt(), x, ground - s * 0.55f, s * 0.22f)
    out.line(0xFF1F5FA8.toInt(), s * 0.1f, x + s * 0.1f, ground - s * 0.6f, x + s * 0.2f, ground - s * 1.0f)
    out.circle(0xFF1F5FA8.toInt(), x + s * 0.22f, ground - s * 1.02f, s * 0.08f)
    out.line(0xFF6B4A2E.toInt(), s * 0.04f, x, ground - s * 0.35f, x, ground)
}

// ---------- The Amazon ----------

private fun Scene.amazonTrees() {
    fill(p.mid) { midBase + h * 0.04f - heightAt(it, Scene.ZOOM_MID, 30f) * midAmp * 0.5f }
    val trunk = Colors.darker(p.mid, 0.45f)
    val crown = Colors.darker(p.mid, 0.15f)
    val vine = Colors.alpha(Colors.lighter(p.mid, 0.2f), 0.8f)
    slots(Scene.ZOOM_MID, h * 0.07f) { i, x0, x1 ->
        val cx = x0 + (x1 - x0) * hash(i, 881)
        val g = midBase + h * 0.04f - heightAt(cx, Scene.ZOOM_MID, 30f) * midAmp * 0.5f
        val tall = h * (0.28f + 0.2f * hash(i, 882))
        out.line(trunk, h * 0.012f, cx, g, cx, g - tall)
        out.circle(crown, cx, g - tall, h * 0.06f)
        out.circle(Colors.lighter(crown, 0.12f), cx - h * 0.03f, g - tall + h * 0.01f, h * 0.045f)
        out.circle(crown, cx + h * 0.04f, g - tall + h * 0.02f, h * 0.04f)
        if (hash(i, 883) < 0.6f) {
            val sway = sin(now / 1400.0 + i).toFloat() * h * 0.01f
            out.polyline(vine, h * 0.003f)
            out.point(cx, g - tall + h * 0.03f); out.point(cx + h * 0.03f + sway, g - tall * 0.6f); out.point(cx + h * 0.015f + sway * 1.5f, g - tall * 0.3f)
        }
    }
    // Mist low among the trunks.
    out.rect(Colors.alpha(p.skyBottom, 0.25f), 0f, midBase - h * 0.05f, w, midBase + h * 0.04f)
}

/** The river the canoe goes down: the song's swell, small waves. */
private fun Scene.riverNear() {
    val water = Colors.mix(0xFF4E7A5A.toInt(), p.skyBottom, 0.25f)
    fill(water) { seaY(it) }
    val glint = Colors.alpha(Colors.lighter(water, 0.4f), 0.5f)
    slots(1f, h * 0.05f) { _, x0, _ ->
        out.line(glint, h * 0.003f, x0, seaY(x0) + h * 0.006f, x0 + h * 0.025f, seaY(x0 + h * 0.025f) + h * 0.006f)
    }
}

private fun Scene.macaws() {
    if (look.night > 0.4f) return
    slotsMs(1f, 30_000.0) { i, x0, x1 ->
        if (hash(i, 891) > 0.5f) return@slotsMs
        val t = ((now / 6000.0 + hash(i, 892)) % 1.0).toFloat()
        val x = x0 + (x1 - x0) * t
        val y = h * (0.25f + 0.15f * hash(i, 893)) + sin(now / 400.0 + i).toFloat() * h * 0.01f
        val flap = sin(now / 90.0 + i).toFloat()
        val c = if (i % 2 == 0) 0xFFE8382A.toInt() else 0xFF2E7DD8.toInt()
        val s = h * 0.016f
        out.line(c, s * 0.5f, x - s * 1.2f, y + s * 0.2f, x + s, y)
        out.line(Colors.mix(c, 0xFFF5D547.toInt(), 0.5f), s * 0.35f, x, y, x - s * 0.6f, y - s * flap)
        out.line(c, s * 0.3f, x - s * 1.2f, y + s * 0.2f, x - s * 2.2f, y + s * 0.6f)
    }
}

// ---------- The canyon ----------

private fun Scene.mesasFar() {
    slots(Scene.ZOOM_FAR, h * 0.22f) { i, x0, x1 ->
        val v = heightAt((x0 + x1) / 2f, Scene.ZOOM_FAR, 30f)
        val top = farBase + h * 0.04f - (0.3f + 0.7f * v) * farAmp * (0.6f + 0.4f * hash(i, 901))
        val inset = (x1 - x0) * 0.12f
        out.polygon(p.far)
        out.point(x0, bottom); out.point(x0 + inset, top); out.point(x1 - inset, top); out.point(x1, bottom)
        out.line(Colors.lighter(p.far, 0.12f), h * 0.004f, x0 + inset * 0.6f, top + h * 0.04f, x1 - inset * 0.6f, top + h * 0.04f)
    }
}

private fun Scene.buttesMid() {
    fill(p.mid) { midBase + h * 0.03f - heightAt(it, Scene.ZOOM_MID, 30f) * midAmp * 0.5f }
    slotsMs(Scene.ZOOM_MID, 40_000.0) { i, x0, x1 ->
        val cx = x0 + (x1 - x0) * hash(i, 911)
        val g = midBase + h * 0.03f - heightAt(cx, Scene.ZOOM_MID, 30f) * midAmp * 0.5f
        if (hash(i, 912) < 0.6f) {
            val s = h * (0.12f + 0.1f * hash(i, 913))
            out.polygon(Colors.darker(p.mid, 0.1f))
            out.point(cx - s * 0.5f, g + h * 0.01f); out.point(cx - s * 0.35f, g - s); out.point(cx + s * 0.35f, g - s); out.point(cx + s * 0.5f, g + h * 0.01f)
            out.line(Colors.lighter(p.mid, 0.15f), h * 0.004f, cx - s * 0.42f, g - s * 0.55f, cx + s * 0.42f, g - s * 0.55f)
        } else {
            steamTrain(cx, g, h * 0.03f, i)
        }
    }
}

private fun Scene.steamTrain(x: Float, ground: Float, s: Float, seed: Int) {
    val body = Colors.darker(p.mid, 0.6f)
    for (k in 0 until 4) {
        val cx = x - k * s * 2.6f
        out.rect(body, cx - s * 1.1f, ground - s * 1.1f, cx + s * 1.1f, ground - s * 0.2f)
        out.circle(body, cx - s * 0.6f, ground - s * 0.15f, s * 0.25f)
        out.circle(body, cx + s * 0.6f, ground - s * 0.15f, s * 0.25f)
    }
    out.rect(body, x + s * 0.6f, ground - s * 1.8f, x + s * 0.95f, ground - s * 1.1f)
    for (k in 0 until 4) {
        val age = ((now / 700.0 + k * 0.25 + seed) % 1.0).toFloat()
        out.circle(Colors.alpha(0xFFEEEEEE.toInt(), 0.5f * (1 - age)), x + s * 0.8f - age * s * 2f, ground - s * 2f - age * s * 2f, s * (0.3f + age * 0.6f))
    }
}

private fun Scene.desertNear() {
    groundFill(p.near)
    val cactus = Colors.mix(0xFF4E8A4A.toInt(), p.near, 0.25f)
    slots(1f, h * 0.06f) { i, x0, _ ->
        val y = nearY(x0)
        if (dense(i, 921, 0.18f, x0, 1f)) {
            val s = h * (0.05f + 0.05f * hash(i, 922))
            out.line(cactus, s * 0.22f, x0, y, x0, y - s)
            out.polyline(cactus, s * 0.15f)
            out.point(x0, y - s * 0.45f); out.point(x0 - s * 0.3f, y - s * 0.45f); out.point(x0 - s * 0.3f, y - s * 0.75f)
            out.polyline(cactus, s * 0.15f)
            out.point(x0, y - s * 0.6f); out.point(x0 + s * 0.28f, y - s * 0.6f); out.point(x0 + s * 0.28f, y - s * 0.9f)
        }
    }
    // A tumbleweed rolling along with the wind.
    val t = ((now / 7000.0) % 1.0).toFloat()
    val tx = w * (1.1f - t * 1.3f)
    val r = h * 0.02f
    val ty = nearY(tx) - r - abs(sin(t * 40f)) * h * 0.02f
    val weed = Colors.alpha(0xFFB89A6A.toInt(), 0.9f)
    out.ring(weed, tx, ty, r, h * 0.003f)
    for (k in 0 until 4) {
        val a = k * 0.8f + t * 30f
        out.line(weed, h * 0.002f, tx - cos(a) * r, ty - sin(a) * r, tx + cos(a) * r, ty + sin(a) * r)
    }
}

// ---------- Japan ----------

private fun Scene.fujiFar() {
    fill(Colors.mix(p.far, p.skyBottom, 0.3f)) { farBase + h * 0.04f - heightAt(it, Scene.ZOOM_FAR, 60f) * farAmp * 0.35f }
    slotsMs(Scene.ZOOM_FAR, 160_000.0) { i, x0, x1 ->
        val cx = x0 + (x1 - x0) * (0.3f + 0.4f * hash(i, 931))
        val foot = farBase + h * 0.06f
        val s = h * 0.32f
        out.polygon(p.far)
        out.point(cx - s * 1.1f, foot); out.point(cx - s * 0.12f, foot - s * 0.62f); out.point(cx + s * 0.12f, foot - s * 0.62f); out.point(cx + s * 1.1f, foot)
        out.polygon(0xFFF6F8FC.toInt())
        out.point(cx - s * 0.3f, foot - s * 0.45f); out.point(cx - s * 0.12f, foot - s * 0.62f); out.point(cx + s * 0.12f, foot - s * 0.62f)
        out.point(cx + s * 0.3f, foot - s * 0.45f); out.point(cx + s * 0.15f, foot - s * 0.4f); out.point(cx, foot - s * 0.47f); out.point(cx - s * 0.15f, foot - s * 0.4f)
    }
}

private fun Scene.cherryHills() {
    fill(p.mid) { midY(it) }
    val pink = Colors.mix(0xFFF4A7C0.toInt(), p.mid, 0.1f)
    slots(Scene.ZOOM_MID, h * 0.07f) { i, x0, x1 ->
        val cx = x0 + (x1 - x0) * hash(i, 941)
        if (!dense(i, 942, 0.5f, cx, Scene.ZOOM_MID)) {
            if (hash(i, 943) < 0.12f) pagoda(cx, footMid(cx, h * 0.02f), h * 0.07f)
            return@slots
        }
        val y = midY(cx)
        val s = h * 0.045f * (0.7f + 0.6f * hash(i, 944))
        out.line(Colors.darker(p.mid, 0.5f), s * 0.12f, cx, y, cx, y - s * 0.7f)
        out.circle(pink, cx, y - s * 0.95f, s * 0.45f)
        out.circle(Colors.lighter(pink, 0.15f), cx - s * 0.2f, y - s * 1.05f, s * 0.28f)
    }
}

private fun Scene.torii() {
    val red = Colors.mix(0xFFD8322A.toInt(), p.near, 0.15f)
    slotsMs(1f, 35_000.0) { i, x0, x1 ->
        if (hash(i, 951) > 0.5f) return@slotsMs
        val cx = x0 + (x1 - x0) * 0.6f
        val s = min(h * 0.11f, w * 0.12f) * 2.2f
        val l = cx - s * 0.45f
        val r = cx + s * 0.45f
        val gl = footNear(l, s * 0.03f)
        val gr = footNear(r, s * 0.03f)
        val top = min(gl, gr) - s
        out.line(red, s * 0.07f, l, gl, l, top)
        out.line(red, s * 0.07f, r, gr, r, top)
        out.line(red, s * 0.07f, l - s * 0.12f, top + s * 0.17f, r + s * 0.12f, top + s * 0.17f)
        out.polygon(Colors.darker(red, 0.4f))
        out.point(l - s * 0.22f, top - s * 0.02f); out.point(r + s * 0.22f, top - s * 0.02f); out.point(r + s * 0.16f, top + s * 0.06f); out.point(l - s * 0.16f, top + s * 0.06f)
    }
}

private fun Scene.petals() {
    val pink = Colors.alpha(0xFFF7B6CA.toInt(), 0.85f)
    for (k in 0 until 18) {
        val t = ((now / 9000.0 + hash(k, 961)) % 1.0).toFloat()
        val x = w * ((hash(k, 962) + 1f - t) % 1f)
        val y = bottom * t + sin(now / 700.0 + k).toFloat() * h * 0.02f
        out.circle(pink, x, y, h * 0.004f)
    }
}

// ---------- The bottom of the sea ----------

private fun Scene.lightRays() {
    for (k in 0 until 5) {
        val x = w * (0.1f + 0.2f * k) + sin(now / 3000.0 + k).toFloat() * w * 0.03f
        out.polygon(Colors.alpha(p.light, 0.07f))
        out.point(x - w * 0.02f, 0f); out.point(x + w * 0.03f, 0f); out.point(x + w * 0.12f, bottom * 0.8f); out.point(x - w * 0.02f, bottom * 0.8f)
    }
}

private fun Scene.reefFar() = fill(p.far) { farBase + h * 0.06f - heightAt(it, Scene.ZOOM_FAR, 30f) * farAmp * 0.6f }

private fun Scene.coralMid() {
    fill(p.mid) { midY(it) }
    val colours = intArrayOf(0xFFFF6F61.toInt(), 0xFFFFB84D.toInt(), 0xFFB565D9.toInt(), 0xFF4FD1C5.toInt(), 0xFFFF8FB1.toInt())
    slots(Scene.ZOOM_MID, h * 0.05f) { i, x0, x1 ->
        val cx = x0 + (x1 - x0) * hash(i, 971)
        if (!dense(i, 972, 0.6f, cx, Scene.ZOOM_MID)) return@slots
        val y = midY(cx)
        val c = Colors.mix(colours[i.mod(colours.size)], p.mid, 0.2f)
        val s = h * 0.05f * (0.6f + 0.8f * hash(i, 973))
        if (hash(i, 974) < 0.5f) {
            // Branching coral.
            out.line(c, s * 0.12f, cx, y, cx, y - s)
            out.line(c, s * 0.1f, cx, y - s * 0.4f, cx - s * 0.35f, y - s * 0.8f)
            out.line(c, s * 0.1f, cx, y - s * 0.55f, cx + s * 0.35f, y - s * 0.95f)
        } else {
            // A brain coral or an anemone, swaying.
            out.circle(c, cx, y - s * 0.2f, s * 0.35f)
            val sway = sin(now / 600.0 + i).toFloat() * s * 0.08f
            for (k in -2..2) out.line(Colors.lighter(c, 0.2f), s * 0.05f, cx + k * s * 0.12f, y - s * 0.4f, cx + k * s * 0.18f + sway, y - s * 0.75f)
        }
    }
}

private fun Scene.fish() {
    slotsMs(1f, 22_000.0) { i, x0, x1 ->
        if (hash(i, 981) > 0.7f) return@slotsMs
        val t = ((now / 12000.0 + hash(i, 982)) % 1.0).toFloat()
        val cx = x1 - (x1 - x0 + w) * t
        val cy = h * (0.3f + 0.3f * hash(i, 983))
        val c = intArrayOf(0xFFFFC94D.toInt(), 0xFF5ED3F3.toInt(), 0xFFFF8A5B.toInt())[i.mod(3)]
        val s = h * 0.012f
        for (k in 0 until 7) {
            val fx = cx + (k % 4) * s * 3f + sin(now / 500.0 + k).toFloat() * s
            val fy = cy + (k / 4) * s * 2.5f + (k % 2) * s
            out.circle(c, fx, fy, s * 0.7f)
            out.polygon(c)
            out.point(fx + s * 0.6f, fy); out.point(fx + s * 1.4f, fy - s * 0.6f); out.point(fx + s * 1.4f, fy + s * 0.6f)
        }
    }
}

private fun Scene.seaFloor() {
    groundFill(p.near)
    val weed = Colors.mix(0xFF3E9A5A.toInt(), p.near, 0.2f)
    slots(1f, h * 0.04f) { i, x0, _ ->
        if (!dense(i, 991, 0.35f, x0, 1f)) return@slots
        val y = nearY(x0)
        val s = h * (0.05f + 0.06f * hash(i, 992))
        val sway = sin(now / 900.0 + i).toFloat() * s * 0.25f
        out.polyline(weed, h * 0.006f)
        out.point(x0, y); out.point(x0 + sway * 0.5f, y - s * 0.5f); out.point(x0 + sway, y - s)
    }
}

private fun Scene.bubbles() {
    val c = Colors.alpha(0xFFE8F8FF.toInt(), 0.6f)
    for (k in 0 until 14) {
        val t = ((now / 5000.0 + hash(k, 995)) % 1.0).toFloat()
        val x = w * hash(k, 996) + sin(now / 400.0 + k).toFloat() * h * 0.01f
        val y = bottom * (1f - t)
        out.ring(c, x, y, h * (0.004f + 0.004f * hash(k, 997)), h * 0.0015f)
    }
}

// ---------- The volcano ----------

private fun Scene.volcanoFar() {
    fill(Colors.mix(p.far, p.skyBottom, 0.2f)) { farBase - heightAt(it, Scene.ZOOM_FAR, 30f) * farAmp * 0.7f }
    slotsMs(Scene.ZOOM_FAR, 140_000.0) { i, x0, x1 ->
        val cx = x0 + (x1 - x0) * (0.3f + 0.4f * hash(i, 1001))
        val foot = farBase + h * 0.06f
        val s = h * 0.34f
        out.polygon(p.far)
        out.point(cx - s, foot); out.point(cx - s * 0.18f, foot - s * 0.7f); out.point(cx + s * 0.18f, foot - s * 0.7f); out.point(cx + s, foot)
        // The crater's glow, brighter with the sound; smoke rising.
        out.circle(Colors.alpha(0xFFFF6A1A.toInt(), 0.35f + 0.3f * live.level + 0.3f * peak), cx, foot - s * 0.7f, s * 0.2f)
        for (k in 0 until 5) {
            val age = ((now / 2600.0 + k * 0.2) % 1.0).toFloat()
            out.circle(Colors.alpha(0xFF5A5058.toInt(), 0.45f * (1 - age)), cx + age * s * 0.4f, foot - s * 0.75f - age * s * 0.9f, s * (0.08f + age * 0.25f))
        }
    }
}

private fun Scene.lavaMid() {
    fill(p.mid) { midY(it) }
    val lava = 0xFFFF7A1A.toInt()
    slots(Scene.ZOOM_MID, h * 0.12f) { i, x0, x1 ->
        if (hash(i, 1011) > 0.45f) return@slots
        val cx = x0 + (x1 - x0) * hash(i, 1012)
        val y = midY(cx)
        val pulse = 0.7f + 0.3f * sin(now / 300.0 + i).toFloat()
        out.polyline(Colors.alpha(lava, 0.8f * pulse), h * 0.008f)
        out.point(cx, y); out.point(cx + h * 0.02f, y + h * 0.05f); out.point(cx - h * 0.01f, y + h * 0.1f); out.point(cx + h * 0.015f, bottom)
    }
    // Now and then a geyser of lava: a glow at its foot, then burning drops shooting up and falling.
    slots(Scene.ZOOM_MID, h * 0.55f) { i, x0, x1 ->
        if (hash(i, 1013) > 0.5f) return@slots
        val cx = x0 + (x1 - x0) * (0.2f + 0.6f * hash(i, 1014))
        val y = midY(cx)
        val period = 7000.0 + 5000.0 * hash(i, 1015)
        val t = (((now + hash(i, 1016) * period) % period) / 2600.0).toFloat()
        if (t > 1f) return@slots
        val rise = h * (0.16f + 0.1f * hash(i, 1017))
        out.circle(Colors.alpha(0xFFFF8A2A.toInt(), 0.35f * sin(t * PI).toFloat()), cx, y, h * 0.03f)
        for (k in 0 until 18) {
            val a = ((t * 2.2f + k / 18f) % 1f)
            val spread = (hash(k + i * 31, 1018) - 0.5f) * h * 0.06f
            val px = cx + spread * a
            val py = y - rise * (4f * a * (1f - a)) * (0.7f + 0.3f * hash(k, 1019))
            val c = if (k % 3 == 0) 0xFFFFE08A.toInt() else 0xFFFF6A1A.toInt()
            out.circle(Colors.alpha(c, 0.9f * (1f - a * 0.6f) * sin(t * PI).toFloat().coerceAtLeast(0f)), px, py, h * (0.006f + 0.004f * (1f - a)))
        }
    }
}

private fun Scene.ashNear() {
    groundFill(p.near)
    val crack = 0xFFFF6A1A.toInt()
    slots(1f, h * 0.05f) { i, x0, _ ->
        val y = nearY(x0) + h * 0.02f
        if (dense(i, 1021, 0.4f, x0, 1f)) {
            out.line(Colors.alpha(crack, 0.5f + 0.4f * kick), h * 0.003f, x0, y, x0 + h * 0.025f, y + h * 0.01f)
        }
    }
    // Embers floating up.
    for (k in 0 until 12) {
        val t = ((now / 4000.0 + hash(k, 1022)) % 1.0).toFloat()
        out.circle(Colors.alpha(0xFFFFB04A.toInt(), 0.8f * (1 - t)), w * hash(k, 1023) + sin(now / 500.0 + k).toFloat() * h * 0.01f, bottom * (1f - t * 0.7f), h * 0.003f)
    }
}

// ---------- The peaks ----------

/** These worlds' peak moments; false for the others. */
internal fun Scene.newPeak(world: World, f: Float): Boolean {
    when (world) {
        World.DESERT -> goldenPyramid(f)
        World.WALL -> dragon(f)
        World.JUNGLE -> festivalLights(f)
        World.AMAZON -> waterfallRainbow(f)
        World.CANYON -> eagleSunset(f)
        World.JAPAN -> hanabi(f)
        World.REEF -> whale(f)
        World.VOLCANO -> eruption(f)
        else -> return false
    }
    return true
}

private fun Scene.goldenPyramid(f: Float) {
    val cx = w * 0.62f
    val foot = farBase + h * 0.04f
    val s = h * 0.34f * (0.6f + 0.4f * f)
    val rise = (1f - f) * s * 0.6f
    out.circle(Colors.alpha(0xFFFFD27A.toInt(), 0.35f * f), cx, foot - s * 0.9f + rise, s * 0.9f)
    out.polygon(Colors.alpha(0xFFF0C25A.toInt(), f))
    out.point(cx - s, foot + rise); out.point(cx, foot - s * 0.85f + rise); out.point(cx + s * 0.15f, foot + rise)
    out.polygon(Colors.alpha(0xFFC08A30.toInt(), f))
    out.point(cx + s * 0.15f, foot + rise); out.point(cx, foot - s * 0.85f + rise); out.point(cx + s, foot + rise)
    out.circle(Colors.alpha(0xFFFFF6C0.toInt(), f * (0.6f + 0.4f * kick)), cx, foot - s * 0.85f + rise, s * 0.05f)
}

private fun Scene.dragon(f: Float) {
    val red = 0xFFD8322A.toInt()
    val gold = 0xFFF5C542.toInt()
    val t = now / 1000.0
    val n = 22
    val headX = w * (1.1f - ((t / 9.0) % 1.0).toFloat() * 1.4f)
    for (k in n - 1 downTo 0) {
        val x = headX + k * h * 0.035f
        val y = h * 0.28f + sin(t * 2.2 + k * 0.45).toFloat() * h * 0.06f
        val r = h * 0.03f * (1f - k / (n * 1.4f))
        out.circle(Colors.alpha(if (k % 3 == 0) gold else red, f), x, y, r)
        if (k % 4 == 1) out.line(Colors.alpha(gold, f), h * 0.004f, x, y - r, x + h * 0.01f, y - r * 1.8f)
    }
    val hy = h * 0.28f + sin(t * 2.2).toFloat() * h * 0.06f
    out.circle(Colors.alpha(red, f), headX - h * 0.02f, hy, h * 0.04f)
    out.line(Colors.alpha(gold, f), h * 0.004f, headX - h * 0.05f, hy - h * 0.02f, headX - h * 0.09f, hy - h * 0.05f)
    out.line(Colors.alpha(gold, f), h * 0.004f, headX - h * 0.05f, hy + h * 0.01f, headX - h * 0.09f, hy + h * 0.04f)
    out.circle(Colors.alpha(0xFFFFF6C0.toInt(), f), headX - h * 0.035f, hy - h * 0.012f, h * 0.007f)
    // Lanterns rising.
    for (k in 0 until 10) {
        val a = ((t / 7.0 + hash(k, 1031)) % 1.0).toFloat()
        out.circle(Colors.alpha(0xFFFF8A3A.toInt(), f * 0.9f * (1 - a)), w * hash(k, 1032), bottom * (0.8f - a * 0.7f), h * 0.008f)
    }
}

private fun Scene.festivalLights(f: Float) {
    val t = now / 1000.0
    // Clouds of coloured powder bursting with the beat, little lamps floating up.
    val colours = intArrayOf(0xFFFF4FA3.toInt(), 0xFFFFC93A.toInt(), 0xFF3AD1FF.toInt(), 0xFF7CFF5A.toInt(), 0xFFB45AFF.toInt())
    for (k in 0 until 6) {
        val age = ((t * 0.7 + k / 6.0) % 1.0).toFloat()
        val x = w * (0.1f + 0.8f * hash(k + (t * 0.7).toInt() * 7, 1041))
        val y = h * (0.25f + 0.25f * hash(k, 1042))
        out.circle(Colors.alpha(colours[k % colours.size], f * 0.5f * (1 - age)), x, y, h * (0.03f + 0.09f * age))
    }
    for (k in 0 until 16) {
        val a = ((t / 8.0 + hash(k, 1043)) % 1.0).toFloat()
        out.circle(Colors.alpha(0xFFFFC46A.toInt(), f * (1 - a)), w * hash(k, 1044), bottom * (0.85f - a * 0.75f), h * 0.006f)
    }
}

private fun Scene.waterfallRainbow(f: Float) {
    val cx = w * 0.7f
    val top = h * 0.2f
    val foot = midBase
    out.rect(Colors.alpha(0xFFE8F4FF.toInt(), 0.75f * f), cx - h * 0.03f, top, cx + h * 0.03f, foot)
    for (k in 0 until 6) {
        val y = top + ((now / 300.0 + k / 6.0) % 1.0).toFloat() * (foot - top)
        out.line(Colors.alpha(0xFFFFFFFF.toInt(), 0.6f * f), h * 0.003f, cx - h * 0.02f, y, cx - h * 0.02f, y + h * 0.03f)
    }
    out.circle(Colors.alpha(0xFFFFFFFF.toInt(), 0.35f * f), cx, foot, h * 0.06f)
    val bands = intArrayOf(0xFFE8443A.toInt(), 0xFFF29A38.toInt(), 0xFFF5D547.toInt(), 0xFF5DBB63.toInt(), 0xFF3F8FD9.toInt(), 0xFF7A5BC7.toInt())
    for ((k, c) in bands.withIndex()) out.ring(Colors.alpha(c, 0.55f * f), cx - h * 0.05f, foot, h * (0.22f - k * 0.012f), h * 0.012f)
}

private fun Scene.eagleSunset(f: Float) {
    out.rect(Colors.alpha(0xFFFF6A2A.toInt(), 0.22f * f), 0f, 0f, w, farBase)
    val t = now / 1000.0
    val x = w * 0.5f + cos(t * 0.5).toFloat() * w * 0.3f
    val y = h * 0.25f + sin(t * 0.5).toFloat() * h * 0.06f
    val s = h * 0.05f
    val flap = sin(t * 3.0).toFloat() * 0.3f
    val c = Colors.alpha(0xFF2A1E1A.toInt(), f)
    out.polygon(c)
    out.point(x - s * 1.5f, y - s * flap); out.point(x, y - s * 0.15f); out.point(x + s * 1.5f, y - s * flap); out.point(x, y + s * 0.2f)
    out.circle(c, x + s * 0.2f, y - s * 0.05f, s * 0.15f)
}

private fun Scene.hanabi(f: Float) {
    val t = now / 1000.0
    for (k in 0 until 3) {
        val slot = (t * 1.2 + k / 3.0).toInt()
        val age = ((t * 1.2 + k / 3.0) % 1.0).toFloat()
        val cx = w * (0.2f + 0.6f * hash(slot * 3 + k, 1051))
        val cy = h * (0.18f + 0.15f * hash(slot * 3 + k, 1052))
        val c = intArrayOf(0xFFFF8FB1.toInt(), 0xFFFFE07A.toInt(), 0xFF9AD7FF.toInt())[k]
        for (r in 0 until 16) {
            val a = r * PI / 8
            val d = h * 0.11f * age
            out.circle(Colors.alpha(c, f * (1 - age)), cx + (cos(a) * d).toFloat(), cy + (sin(a) * d).toFloat() + age * age * h * 0.03f, h * 0.005f)
        }
    }
    petals()
}

private fun Scene.whale(f: Float) {
    val t = now / 1000.0
    val x = w * (1.2f - ((t / 14.0) % 1.0).toFloat() * 1.6f)
    val y = h * 0.3f + sin(t * 0.6).toFloat() * h * 0.03f
    val s = h * 0.12f
    val body = Colors.alpha(0xFF2E4A6E.toInt(), 0.85f * f)
    out.circle(body, x, y, s * 0.5f)
    out.rect(body, x, y - s * 0.4f, x + s * 1.5f, y + s * 0.3f)
    out.polygon(body)
    out.point(x + s * 1.5f, y - s * 0.3f); out.point(x + s * 2.2f, y - s * (0.5f + 0.2f * sin(t * 1.5).toFloat())); out.point(x + s * 2.0f, y); out.point(x + s * 2.2f, y + s * 0.4f); out.point(x + s * 1.5f, y + s * 0.25f)
    out.rect(Colors.alpha(0xFFDDE8F2.toInt(), 0.7f * f), x - s * 0.2f, y + s * 0.1f, x + s * 1.2f, y + s * 0.3f)
    out.circle(Colors.alpha(0xFF101820.toInt(), f), x - s * 0.15f, y - s * 0.1f, s * 0.05f)
    // Its song: rings of light spreading.
    val ring = ((t * 0.8) % 1.0).toFloat()
    out.ring(Colors.alpha(0xFFBFF4FF.toInt(), 0.5f * f * (1 - ring)), x, y, s * (0.6f + ring * 2f), h * 0.004f)
}

private fun Scene.eruption(f: Float) {
    val cx = w * 0.6f
    val top = farBase - h * 0.18f
    out.circle(Colors.alpha(0xFFFF6A1A.toInt(), 0.35f * f), cx, top, h * 0.18f)
    for (k in 0 until 14) {
        val age = ((now / 1600.0 + hash(k, 1061)) % 1.0).toFloat()
        val vx = (hash(k, 1062) - 0.5f) * w * 0.5f
        val x = cx + vx * age
        val y = top - h * 0.35f * age + h * 0.5f * age * age
        out.circle(Colors.alpha(if (k % 2 == 0) 0xFFFFB04A.toInt() else 0xFFFF5A1A.toInt(), f * (1 - age * 0.5f)), x, y, h * 0.008f)
    }
    for (k in 0 until 6) {
        val age = ((now / 3000.0 + k / 6.0) % 1.0).toFloat()
        out.circle(Colors.alpha(0xFF4A4048.toInt(), 0.5f * f * (1 - age)), cx + age * w * 0.1f, top - age * h * 0.3f, h * (0.04f + age * 0.08f))
    }
}
