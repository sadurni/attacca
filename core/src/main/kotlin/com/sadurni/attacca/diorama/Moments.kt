package com.sadurni.attacca.diorama

import com.sadurni.attacca.diorama.Diorama.Companion.hash
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// What makes a track's way less even: its peak moment, places to pass (bridges, tunnels, a
// village, a big wheel…), things rushing by in front, someone keeping the traveller company for a
// while, and the end of the level (a sign with the next track, a finish arch).

// ---------- The peak ----------

/** How far into the track's loudest moment the traveller is (0 none … 1 in it). */
internal fun Scene.peakStrength(): Float {
    val at = lane.peakMs
    if (at < 0) return 0f
    val d = posMs - at
    return when {
        d < -3000 -> 0f
        d < 0 -> (1 + d / 3000).toFloat()
        d < 9000 -> 1f
        d < 13_000 -> (1 - (d - 9000) / 4000).toFloat()
        else -> 0f
    }
}

/** At the track's peak something big in the sky: a rainbow, a shower of stars, a red moon, fireworks. */
internal fun Scene.peakMoment() {
    val f = peak
    if (f <= 0f) return
    val night = look.night > 0.5f
    val from = out.size
    when {
        newPeak(look.world, f) -> {}
        look.world == World.NEON || (look.world == World.CITY && night) -> bigFireworks(f)
        look.world == World.CASTLE -> bloodMoon(f)
        look.world == World.ICE -> northernLights(f)
        look.world == World.SPACE || night -> starShower(f)
        else -> rainbow(f)
    }
    // Upright the very top of the sky is under the title and the buttons: what starts up there
    // (fireworks, shooting stars) comes down into the sky that is free (a strip is placed as a whole elsewhere).
    val high = look.world == World.NEON || (look.world == World.CITY && night) || (look.world != World.CASTLE && look.world != World.ICE && (look.world == World.SPACE || night))
    if (high && h > w * 1.3f && stripTop <= 0f) out.mapYWhole(from, out.size, h * 0.08f, 1f)
}

private val RAINBOW = intArrayOf(0xFFE8443A.toInt(), 0xFFF29A38.toInt(), 0xFFF5D547.toInt(), 0xFF5DBB63.toInt(), 0xFF3F8FD9.toInt(), 0xFF7A5BC7.toInt())

private fun Scene.rainbow(f: Float) {
    val cx = w * 0.55f
    // As wide as the screen allows (upright, its top would otherwise be the only part in sight,
    // behind the land), standing on the land.
    val r0 = min(max(w, h) * 0.5f, w * 0.62f)
    val cy = min(nearBase + h * 0.05f, h * 0.1f + r0 * 0.85f)
    val band = unit * 0.028f
    // A soft light behind the bows.
    out.polyline(Colors.alpha(0xFFFFFFFF.toInt(), 0.16f * f), band * 9f)
    for (m in 0..40) {
        val a = PI + PI * m / 40
        val r = r0 - band * 2.5f
        out.point(cx + (cos(a) * r).toFloat(), cy + (sin(a) * r * 0.85f).toFloat())
    }
    for ((k, c) in RAINBOW.withIndex()) {
        val r = r0 - k * band
        out.polyline(Colors.alpha(c, (0.62f + 0.2f * live.level) * f), band)
        for (m in 0..40) {
            val a = PI + PI * m / 40
            out.point(cx + (cos(a) * r).toFloat(), cy + (sin(a) * r * 0.85f).toFloat())
        }
    }
}

private fun Scene.starShower(f: Float) {
    for (k in 0 until 24) {
        val cycle = 1600L
        val t = now + k * 133L
        val e = (t / cycle).toInt() * 31 + k
        val v = (t % cycle) / cycle.toFloat()
        if (v > 0.5f) continue
        val u = v / 0.5f
        val sx = w * (0.05f + 0.9f * hash(e, 451))
        val sy = h * (0.02f + 0.3f * hash(e, 452))
        val hx = sx + u * unit * 0.35f
        val hy = sy + u * unit * 0.18f
        val a = sin(u * PI).toFloat() * f
        out.line(Colors.alpha(Colors.mix(0xFFFFFFFF.toInt(), p.accent, 0.25f), 0.35f * a), unit * 0.012f, hx - unit * 0.14f, hy - unit * 0.07f, hx, hy)
        out.line(Colors.alpha(0xFFFFFFFF.toInt(), 0.85f * a), unit * 0.005f, hx - unit * 0.12f, hy - unit * 0.06f, hx, hy)
        out.circle(Colors.alpha(0xFFFFFFFF.toInt(), 0.3f * a), hx, hy, unit * 0.02f)
        out.circle(Colors.alpha(0xFFFFFFFF.toInt(), a), hx, hy, unit * 0.008f)
    }
}

/**
 * The peak over the ice: the northern lights filling the sky, curtains of green and violet
 * light hanging and waving, brighter as the music swells.
 */
private fun Scene.northernLights(f: Float) {
    val greens = intArrayOf(0xFF5CFFB0.toInt(), 0xFF3CE6A0.toInt(), 0xFF8AFFD0.toInt())
    val curtains = 5
    val step = w / 150f
    for (cIdx in 0 until curtains) {
        val top = h * (0.04f + 0.06f * hash(cIdx, 471))
        val depth = h * (0.16f + 0.10f * hash(cIdx, 472)) * (1f + 0.25f * live.level)
        val phase = cIdx * 1.7 + now / (2400.0 + cIdx * 600)
        val green = greens[cIdx % greens.size]
        val violet = Colors.mix(0xFFB06CFF.toInt(), p.accent, 0.3f)
        // Hanging rays: thin bands from a waving top edge, green below fading up into violet.
        var x = -step
        var k = 0
        while (x < w + step) {
            val wave = sin(x / (w * 0.17f) + phase).toFloat() * h * 0.045f + sin(x / (w * 0.07f) + phase * 1.6).toFloat() * h * 0.012f
            val y0 = top + wave
            val shimmer = 0.5f + 0.5f * sin(now / 300.0 + k * 0.35 + cIdx).toFloat()
            val a = f * (0.07f + 0.12f * live.level + 0.06f * kick) * shimmer
            val len = depth * (0.7f + 0.3f * sin(k * 0.45 + phase).toFloat())
            out.gradient(Colors.alpha(violet, 0f), Colors.alpha(green, a * 1.4f), x, y0 - len * 0.25f, x + step * 0.9f, y0 + len * 0.6f)
            out.gradient(Colors.alpha(green, a * 1.4f), Colors.alpha(green, 0f), x, y0 + len * 0.6f, x + step * 0.9f, y0 + len)
            x += step
            k++
        }
    }
    // A bright fold along the lowest curtain.
    out.polyline(Colors.alpha(0xFFB8FFE0.toInt(), 0.45f * f * (0.7f + 0.3f * live.level)), unit * 0.006f)
    var x = -step
    while (x < w + step) {
        out.point(x, h * 0.2f + sin(x / (w * 0.17f) + now / 2400.0).toFloat() * h * 0.045f + h * 0.12f)
        x += step * 2
    }
}

private fun Scene.bloodMoon(f: Float) {
    val x = w * 0.5f
    val y = h * (0.42f - 0.18f * f)
    val r = unit * 0.15f
    val red = Colors.mix(0xFFB8322A.toInt(), p.accent, 0.2f)
    out.circle(Colors.alpha(red, 0.10f * f * (1f + kick)), x, y, r * (2.6f + 0.4f * kick))
    out.circle(Colors.alpha(red, 0.22f * f * (1f + kick)), x, y, r * 1.7f)
    out.circle(Colors.alpha(Colors.lighter(red, 0.25f), f), x, y, r)
    out.circle(Colors.alpha(Colors.darker(red, 0.25f), 0.6f * f), x - r * 0.3f, y - r * 0.2f, r * 0.25f)
    out.circle(Colors.alpha(Colors.darker(red, 0.25f), 0.6f * f), x + r * 0.35f, y + r * 0.3f, r * 0.18f)
    // Bats going round it.
    for (k in 0 until 7) {
        val a = now / 900.0 + k * 0.9
        val bx = x + (cos(a) * r * 1.5f).toFloat()
        val by = y + (sin(a) * r * 0.7f).toFloat()
        val flap = cos((beat * 2f + k * 0.2f) * 2 * PI).toFloat()
        val b = unit * 0.012f
        out.polygon(Colors.alpha(0xFF0E0B12.toInt(), f))
        out.point(bx - b * 1.6f, by - b * flap); out.point(bx, by - b * 0.3f); out.point(bx + b * 1.6f, by - b * flap); out.point(bx, by + b * 0.5f)
    }
}

private fun Scene.bigFireworks(f: Float) {
    for (k in 0 until 5) {
        val cycle = 1300L
        val t = now + k * 260L
        val e = (t / cycle).toInt() * 13 + k
        val b = (t % cycle) / cycle.toFloat()
        val fx = w * (0.1f + 0.8f * hash(e, 461))
        val fy = h * (0.08f + 0.25f * hash(e, 462))
        val c = when (k % 3) {
            0 -> p.accent
            1 -> p.light
            else -> Colors.fromHsl(255, hash(e, 463) * 360f, 0.8f, 0.6f)
        }
        val rad = unit * 0.16f * (0.3f + 0.7f * b) * (1f + 0.3f * live.level)
        // The flash of the burst.
        if (b < 0.25f) out.circle(Colors.alpha(Colors.lighter(c, 0.5f), (0.25f - b) * 1.6f * f), fx, fy, rad * 1.1f)
        for (m in 0 until 24) {
            val a = m * PI / 12
            val ex = fx + (cos(a) * rad).toFloat()
            val ey = fy + (sin(a) * rad).toFloat() + b * b * unit * 0.04f
            out.line(Colors.alpha(c, (1f - b) * 0.75f * f), unit * 0.005f, fx + (ex - fx) * 0.5f, fy + (ey - fy) * 0.5f, ex, ey)
            out.circle(Colors.alpha(c, (1f - b) * 0.35f * f), ex, ey, unit * 0.014f)
            out.circle(Colors.alpha(Colors.lighter(c, 0.4f), (1f - b) * f), ex, ey, unit * 0.007f)
        }
    }
}

// ---------- Places along the way ----------

internal enum class Landmark { BRIDGE, TUNNEL, VILLAGE, STATION, WHEEL, SCREEN, RUINS, ARCH, BASE, ROCKET }

/** Music between two places (on the near land). */
internal const val LANDMARK_EVERY_MS = 45_000.0

internal fun landmarksOf(world: World): Array<Landmark> = when (world) {
    World.MEADOW -> arrayOf(Landmark.BRIDGE, Landmark.TUNNEL, Landmark.VILLAGE)
    World.CITY -> arrayOf(Landmark.BRIDGE, Landmark.TUNNEL, Landmark.STATION)
    World.NEON -> arrayOf(Landmark.WHEEL, Landmark.SCREEN, Landmark.TUNNEL)
    World.CASTLE -> arrayOf(Landmark.RUINS, Landmark.BRIDGE, Landmark.TUNNEL)
    World.COAST -> arrayOf(Landmark.WHEEL, Landmark.BRIDGE, Landmark.VILLAGE)
    World.ICE -> arrayOf(Landmark.ARCH)
    World.SPACE -> arrayOf(Landmark.BASE, Landmark.ROCKET)
    World.DESERT -> arrayOf(Landmark.RUINS, Landmark.ARCH)
    World.WALL -> arrayOf(Landmark.BRIDGE, Landmark.TUNNEL, Landmark.VILLAGE)
    World.JUNGLE -> arrayOf(Landmark.RUINS, Landmark.BRIDGE)
    World.AMAZON -> arrayOf(Landmark.BRIDGE)
    World.CANYON -> arrayOf(Landmark.BRIDGE, Landmark.TUNNEL, Landmark.STATION)
    World.JAPAN -> arrayOf(Landmark.BRIDGE, Landmark.VILLAGE, Landmark.TUNNEL)
    World.REEF -> arrayOf(Landmark.ARCH)
    World.VOLCANO -> arrayOf(Landmark.BRIDGE, Landmark.TUNNEL, Landmark.RUINS)
}

/** Every place on screen: its kind, number and where it begins and ends on the near land. */
private inline fun Scene.landmarks(each: (Landmark, Int, Float, Float) -> Unit) {
    val kinds = landmarksOf(lane.look.world)
    slotsMs(1f, LANDMARK_EVERY_MS) { i, x0, x1 ->
        if (hash(i, 501) > 0.8f) return@slotsMs
        val kind = kinds[(hash(i, 502) * kinds.size).toInt().coerceAtMost(kinds.size - 1)]
        val a = x0 + (x1 - x0) * 0.3f
        val len = unit * (0.7f + 0.5f * hash(i, 503)) * (if (kind == Landmark.TUNNEL || kind == Landmark.BRIDGE) 1.3f else if (kind == Landmark.VILLAGE) 1.8f else 1f)
        if (a + len > -unit && a < w + unit) each(kind, i, a, a + len)
    }
}

internal val Scene.travellerSize get() = min(h * 0.11f, w * 0.12f)

/** The places' parts behind the traveller. */
internal fun Scene.landmarksBack() = landmarks { kind, i, a, b ->
    when (kind) {
        Landmark.BRIDGE -> bridge(a, b)
        Landmark.TUNNEL -> tunnelInside(a, b)
        Landmark.STATION -> station(a, b)
        Landmark.WHEEL -> bigWheel((a + b) / 2f)
        Landmark.SCREEN -> bigScreen((a + b) / 2f)
        Landmark.VILLAGE -> if (crossesVillage(i)) village(a, b, i, row = -1)
        else -> {}
    }
}

/**
 * The places' parts in front of the traveller: what stands on its way hides it as it goes by
 * (houses, ruins, domes, a tunnel's rock, the wheel's legs); what it goes over (a bridge) does not.
 */
internal fun Scene.landmarksFront() = landmarks { kind, i, a, b ->
    when (kind) {
        Landmark.TUNNEL -> tunnelRock(a, b)
        Landmark.ARCH -> iceArch(a, b)
        Landmark.VILLAGE -> village(a, b, i, row = if (crossesVillage(i)) 1 else 0)
        Landmark.RUINS -> ruins(a, b)
        Landmark.BASE -> moonBase(a, b)
        Landmark.ROCKET -> rocket((a + b) / 2f)
        Landmark.WHEEL -> wheelLegs((a + b) / 2f)
        Landmark.STATION -> stationFront(a, b)
        else -> {}
    }
}

/** The kinds of level stretch ([Scene.flatKindAt]). */
internal const val FLAT_BRIDGE = 0
internal const val FLAT_STATION = 1

/**
 * Where the way has to stay level: over a bridge, and along a station with its train's track.
 * Each as [Scene.flats] holds it, at the smoothed ground's mean height there, with a short slope
 * up or down at each end.
 */
internal fun Scene.flatSpans(): FloatArray {
    val list = ArrayList<Float>()
    landmarks { kind, _, a, b ->
        val span = when (kind) {
            Landmark.BRIDGE -> floatArrayOf(a, b, FLAT_BRIDGE.toFloat())
            Landmark.STATION -> floatArrayOf(a - trainRun, b + trainRun, FLAT_STATION.toFloat())
            else -> null
        }
        if (span != null) {
            var sum = 0f
            for (k in 0..8) sum += nearShown(span[0] + (span[1] - span[0]) * k / 8f)
            val ramp = unit * 0.14f
            list += listOf(span[0] - ramp, span[0], span[1], span[1] + ramp, sum / 9f, span[2])
        }
    }
    return list.toFloatArray()
}

/**
 * What stands on a level way or a road where the music rises: crates, barrels, benches, logs and
 * rocks on a bridge or a platform, ramps and cones on a road. The traveller jumps them.
 */
internal fun Scene.wayObstacles() {
    val ts = travellerSize
    val world = lane.look.world
    wayObstacleSlots { i, x, height ->
        val y = nearY(x)
        val pick = hash(i, 721)
        val flat = flatKindAt(x)
        when {
            flat == FLAT_STATION -> if (pick < 0.5f) suitcases(x, y, height, i) else bench(x, y, height)
            flat < 0 && (world == World.CITY || world == World.NEON) -> if (pick < 0.55f) ramp(x, y, height, world == World.NEON) else cones(x, y, height, i)
            flat == FLAT_BRIDGE && (world == World.CITY || world == World.NEON) -> if (pick < 0.5f) crates(x, y, height, i) else bench(x, y, height)
            world == World.CASTLE -> if (pick < 0.5f) barrels(x, y, height, i) else bricks(x, y, height, i)
            pick < 0.3f -> crates(x, y, height, i)
            pick < 0.55f -> barrels(x, y, height, i)
            pick < 0.75f -> logs(x, y, height)
            else -> rocks(x, y, height, i)
        }
    }
}

/** Crates stacked as high as [height], the top one a little askew. */
private fun Scene.crates(x: Float, y: Float, height: Float, seed: Int) {
    val wood = Colors.mix(0xFFB08850.toInt(), p.near, 0.25f)
    val n = max(1, (height / (travellerSize * 0.28f)).toInt().coerceAtMost(3))
    val c = height / n
    for (k in 0 until n) {
        val off = (hash(seed + k, 722) - 0.5f) * c * 0.3f
        val x0 = x - c * 0.55f + off
        val y1 = y - k * c
        out.rect(Colors.darker(wood, 0.35f), x0, y1 - c, x0 + c * 1.1f, y1)
        out.rect(wood, x0 + c * 0.08f, y1 - c * 0.92f, x0 + c * 1.02f, y1 - c * 0.08f)
        out.line(Colors.darker(wood, 0.35f), c * 0.08f, x0 + c * 0.1f, y1 - c * 0.1f, x0 + c, y1 - c * 0.9f)
    }
}

/** Barrels, one or a stack of three, as high as [height]. */
private fun Scene.barrels(x: Float, y: Float, height: Float, seed: Int) {
    val wood = Colors.mix(0xFF8A5A30.toInt(), p.near, 0.2f)
    val hoop = 0xFF4A4A50.toInt()
    fun barrel(cx: Float, by: Float, bh: Float) {
        val bw = bh * 0.72f
        out.rect(wood, cx - bw / 2f, by - bh, cx + bw / 2f, by)
        out.rect(Colors.lighter(wood, 0.15f), cx - bw * 0.15f, by - bh, cx + bw * 0.1f, by)
        for (t in floatArrayOf(0.18f, 0.82f)) out.line(hoop, bh * 0.06f, cx - bw / 2f, by - bh * t, cx + bw / 2f, by - bh * t)
    }
    if (height > travellerSize * 0.45f && hash(seed, 723) < 0.6f) {
        val bh = height / 2f
        barrel(x - bh * 0.4f, y, bh); barrel(x + bh * 0.4f, y, bh); barrel(x, y - bh, bh)
    } else {
        barrel(x, y, height)
    }
}

/** Logs piled crosswise, ends towards us. */
private fun Scene.logs(x: Float, y: Float, height: Float) {
    val bark = Colors.mix(0xFF6E4A2A.toInt(), p.near, 0.2f)
    val ring = 0xFFD8B47A.toInt()
    val r = height / 3.4f
    val rows = intArrayOf(3, 2, 1)
    for ((row, n) in rows.withIndex()) {
        for (k in 0 until n) {
            val cx = x + (k - (n - 1) / 2f) * r * 2f
            val cy = y - r - row * r * 1.7f
            out.circle(bark, cx, cy, r)
            out.circle(ring, cx, cy, r * 0.72f)
            out.ring(Colors.darker(ring, 0.25f), cx, cy, r * 0.4f, r * 0.08f)
        }
    }
}

/** A heap of rocks. */
private fun Scene.rocks(x: Float, y: Float, height: Float, seed: Int) {
    val stone = Colors.mix(0xFF8A8A84.toInt(), p.near, 0.35f)
    out.polygon(Colors.darker(stone, 0.15f))
    out.point(x - height * 0.9f, y + 2f); out.point(x - height * 0.7f, y - height * 0.5f); out.point(x - height * 0.2f, y - height)
    out.point(x + height * (0.25f + 0.2f * hash(seed, 724)), y - height * 0.85f); out.point(x + height * 0.8f, y - height * 0.35f); out.point(x + height * 0.95f, y + 2f)
    out.polygon(Colors.lighter(stone, 0.12f))
    out.point(x - height * 0.45f, y - height * 0.55f); out.point(x - height * 0.18f, y - height * 0.92f); out.point(x + height * 0.15f, y - height * 0.7f)
}

/** Bricks stacked in courses, as for a wall being built. */
private fun Scene.bricks(x: Float, y: Float, height: Float, seed: Int) {
    val brick = Colors.mix(0xFFA0503A.toInt(), p.near, 0.25f)
    val bh = travellerSize * 0.09f
    val courses = max(1, (height / bh).toInt())
    for (c in 0 until courses) {
        val n = 3 - c * 3 / max(courses, 1)
        val bw = bh * 2.1f
        val off = if (c % 2 == 0) 0f else bw * 0.5f
        for (k in 0 until max(1, n)) {
            val x0 = x - bw * 1.5f + off + k * bw
            out.rect(Colors.darker(brick, 0.3f), x0, y - (c + 1) * bh, x0 + bw, y - c * bh)
            out.rect(if (hash(seed + c * 5 + k, 725) < 0.5f) brick else Colors.lighter(brick, 0.1f), x0 + bh * 0.08f, y - (c + 1) * bh + bh * 0.1f, x0 + bw - bh * 0.08f, y - c * bh - bh * 0.1f)
        }
    }
}

/** A bench, with a bag left on it when it stands high. */
private fun Scene.bench(x: Float, y: Float, height: Float) {
    val wood = Colors.mix(0xFF9A6A3A.toInt(), p.near, 0.2f)
    val iron = 0xFF3A3A40.toInt()
    val seat = y - height * 0.6f
    val half = height * 0.9f
    out.line(iron, height * 0.07f, x - half * 0.8f, y, x - half * 0.8f, seat)
    out.line(iron, height * 0.07f, x + half * 0.8f, y, x + half * 0.8f, seat)
    out.rect(wood, x - half, seat - height * 0.1f, x + half, seat)
    out.rect(wood, x - half, y - height, x + half, y - height * 0.86f)
    out.line(iron, height * 0.05f, x - half * 0.8f, seat, x - half * 0.8f, y - height * 0.86f)
    out.line(iron, height * 0.05f, x + half * 0.8f, seat, x + half * 0.8f, y - height * 0.86f)
}

/** Suitcases left on the platform, stacked. */
private fun Scene.suitcases(x: Float, y: Float, height: Float, seed: Int) {
    val n = if (height > travellerSize * 0.4f) 2 else 1
    val c = height / n
    for (k in 0 until n) {
        val col = Colors.mix(if (hash(seed + k, 726) < 0.5f) 0xFF4A6A9A.toInt() else 0xFF9A4A4A.toInt(), p.accent, 0.15f)
        val bw = c * (1.5f - k * 0.25f)
        val y1 = y - k * c
        out.rect(col, x - bw / 2f, y1 - c, x + bw / 2f, y1)
        out.rect(Colors.darker(col, 0.3f), x - bw / 2f, y1 - c * 0.55f, x + bw / 2f, y1 - c * 0.45f)
        out.line(Colors.darker(col, 0.4f), c * 0.08f, x - c * 0.2f, y1 - c, x - c * 0.2f, y1 - c * 1.18f)
        out.line(Colors.darker(col, 0.4f), c * 0.08f, x - c * 0.2f, y1 - c * 1.18f, x + c * 0.2f, y1 - c * 1.18f)
        out.line(Colors.darker(col, 0.4f), c * 0.08f, x + c * 0.2f, y1 - c * 1.18f, x + c * 0.2f, y1 - c)
    }
}

/** A ramp on the road, rising the way the traveller goes. */
private fun Scene.ramp(x: Float, y: Float, height: Float, neon: Boolean) {
    val len = height * 2.4f
    val face = if (neon) Colors.mix(p.accent, 0xFF202030.toInt(), 0.5f) else Colors.mix(0xFF8A7A60.toInt(), p.near, 0.3f)
    out.polygon(face)
    out.point(x - len * 0.75f, y + 1f); out.point(x + len * 0.25f, y - height); out.point(x + len * 0.25f, y + 1f)
    // Yellow and black stripes on its high end.
    val stripe = if (neon) p.accent else 0xFFE8C840.toInt()
    out.rect(Colors.darker(face, 0.3f), x + len * 0.25f - height * 0.08f, y - height, x + len * 0.25f, y)
    for (k in 0 until 3) out.line(stripe, height * 0.06f, x + len * 0.25f - height * 0.04f, y - height * (0.15f + k * 0.3f), x + len * 0.25f - height * 0.04f, y - height * (0.28f + k * 0.3f))
    out.line(Colors.lighter(face, 0.25f), height * 0.05f, x - len * 0.75f, y, x + len * 0.25f, y - height)
}

/** Traffic cones, a row or a barrier on two of them. */
private fun Scene.cones(x: Float, y: Float, height: Float, seed: Int) {
    val orange = 0xFFF07A2A.toInt()
    fun cone(cx: Float, ch: Float) {
        out.polygon(orange)
        out.point(cx - ch * 0.32f, y); out.point(cx - ch * 0.07f, y - ch); out.point(cx + ch * 0.07f, y - ch); out.point(cx + ch * 0.32f, y)
        out.rect(0xFFF4F4F4.toInt(), cx - ch * 0.2f, y - ch * 0.55f, cx + ch * 0.2f, y - ch * 0.4f)
        out.rect(Colors.darker(orange, 0.3f), cx - ch * 0.4f, y - ch * 0.06f, cx + ch * 0.4f, y)
    }
    if (hash(seed, 727) < 0.5f) {
        val ch = min(height, travellerSize * 0.4f)
        for (k in -1..1) cone(x + k * ch * 0.8f, ch)
    } else {
        // A striped barrier on two cones.
        val ch = height * 0.75f
        cone(x - height * 0.7f, ch); cone(x + height * 0.7f, ch)
        out.rect(0xFFF4F4F4.toInt(), x - height, y - height, x + height, y - height * 0.78f)
        for (k in 0 until 4) {
            val sx = x - height + k * height * 0.5f
            out.polygon(0xFFD03030.toInt())
            out.point(sx, y - height * 0.78f); out.point(sx + height * 0.18f, y - height); out.point(sx + height * 0.32f, y - height); out.point(sx + height * 0.14f, y - height * 0.78f)
        }
    }
}


/**
 * The tunnel the traveller at [x] is in, as its two mouths (x), or null. Torches hang just inside
 * each mouth: the traveller takes the first and leaves it at the other end.
 */
internal fun Scene.tunnelAt(x: Float): FloatArray? {
    var found: FloatArray? = null
    landmarks { kind, _, a, b -> if (kind == Landmark.TUNNEL && x >= a && x <= b) found = floatArrayOf(a, b) }
    return found
}

/** Where in a tunnel its torches hang, from each mouth. */
internal val Scene.torchInset get() = h * 0.045f

/** A river under the way and a stone bridge over it. */
private fun Scene.bridge(a: Float, b: Float) {
    val water = Colors.mix(p.skyBottom, p.mid, 0.35f)
    val stone = Colors.mix(Colors.lighter(p.near, 0.2f), 0xFF8A847A.toInt(), 0.5f)
    out.polygon(water)
    var x = a
    while (x <= b) {
        out.point(x, nearY(x) + h * 0.03f)
        x += step
    }
    out.point(b, nearY(b) + h * 0.03f); out.point(b, bottom); out.point(a, bottom)
    // Ripples drifting.
    for (k in 0 until 5) {
        val y = nearY((a + b) / 2f) + h * (0.06f + k * 0.035f)
        if (y > bottom) break
        val off = ((now / 30f + k * 40f) % (h * 0.1f))
        var rx = a + off
        while (rx < b - h * 0.04f) {
            out.line(Colors.alpha(Colors.lighter(water, 0.5f), 0.5f), unit * 0.003f, rx, y, rx + h * 0.03f, y)
            rx += h * 0.1f
        }
    }
    // Piers, the deck and its railing.
    for (k in 0..3) {
        val px = a + (b - a) * k / 3f
        out.rect(stone, px - h * 0.012f, nearY(px) + h * 0.01f, px + h * 0.012f, bottom)
    }
    out.polyline(stone, h * 0.03f)
    x = a
    while (x <= b) {
        out.point(x, nearY(x) + h * 0.012f)
        x += step
    }
    out.point(b, nearY(b) + h * 0.012f)
    out.polyline(Colors.darker(stone, 0.3f), h * 0.004f)
    x = a
    while (x <= b) {
        out.point(x, nearY(x) - h * 0.035f)
        x += step
    }
    out.point(b, nearY(b) - h * 0.035f)
    x = a
    while (x <= b) {
        out.line(Colors.darker(stone, 0.3f), h * 0.003f, x, nearY(x), x, nearY(x) - h * 0.035f)
        x += h * 0.04f
    }
}

private fun Scene.ceiling(x: Float) = nearY(x) - travellerSize * 1.9f

/** Inside a tunnel: dark, with lamps going by. */
private fun Scene.tunnelInside(a: Float, b: Float) {
    out.polygon(Colors.darker(p.near, 0.75f))
    var x = a
    while (x <= b) {
        out.point(x, ceiling(x))
        x += step
    }
    out.point(b, ceiling(b)); out.point(b, nearY(b) + 2f)
    x = b
    while (x >= a) {
        out.point(x, nearY(x) + 2f)
        x -= step
    }
    out.point(a, nearY(a) + 2f)
    x = a + h * 0.05f
    while (x < b - h * 0.02f) {
        out.circle(Colors.alpha(0xFFFFD27A.toInt(), 0.12f + 0.15f * kick), x, ceiling(x) + h * 0.012f, h * 0.014f)
        out.circle(Colors.alpha(0xFFFFE3A0.toInt(), 0.6f), x, ceiling(x) + h * 0.012f, h * 0.004f)
        x += h * 0.16f
    }
    // A torch on the wall by each mouth: the first until the traveller takes it, the other once it is left there.
    val inA = a + torchInset
    val inB = b - torchInset
    wallTorch(inA, travellerX < inA)
    wallTorch(inB, travellerX > inB)
}

/** An iron bracket on the tunnel's wall, with its torch when [lit]. */
private fun Scene.wallTorch(x: Float, lit: Boolean) {
    val s = travellerSize
    val y = nearY(x) - s * 1.05f
    val iron = 0xFF3A3530.toInt()
    out.line(iron, s * 0.05f, x - s * 0.08f, y + s * 0.12f, x, y + s * 0.1f)
    out.line(iron, s * 0.05f, x, y + s * 0.1f, x, y + s * 0.02f)
    if (lit) torch(x, y + s * 0.18f, s, 0f)
}

/** A torch held at its foot [x], [y], tilted [lean] (radians): its stick, a flame and its glow. */
internal fun Scene.torch(x: Float, y: Float, s: Float, lean: Float) {
    val tx = x + sin(lean) * s * 0.32f
    val ty = y - cos(lean) * s * 0.32f
    out.line(0xFF6B4A2E.toInt(), s * 0.06f, x, y, tx, ty)
    val flicker = 0.85f + 0.15f * sin(now / 47.0).toFloat() * sin(now / 31.0 + 1.3).toFloat() + 0.1f * live.level
    out.circle(Colors.alpha(0xFFFFB347.toInt(), 0.16f), tx, ty - s * 0.08f, s * 0.7f * flicker)
    out.circle(Colors.alpha(0xFFFF8A2A.toInt(), 0.9f), tx, ty - s * 0.07f, s * 0.1f * flicker)
    out.circle(0xFFFFE08A.toInt(), tx, ty - s * 0.05f, s * 0.055f * flicker)
}

/** The tunnel's hill, over the traveller, with its two mouths. */
private fun Scene.tunnelRock(a: Float, b: Float) {
    val rock = Colors.darker(Colors.mix(p.mid, p.near, 0.5f), 0.15f)
    val portal = h * 0.035f
    val top = travellerSize * 1.9f + h * 0.05f
    out.polygon(rock)
    out.point(a - portal, nearY(a - portal) + 2f)
    var x = a - portal
    val len = b - a + portal * 2
    while (x <= b + portal) {
        out.point(x, nearY(x) - top - h * 0.1f * sin(PI * (x - a + portal) / len).toFloat())
        x += step
    }
    out.point(b + portal, nearY(b + portal) + 2f)
    out.point(b, nearY(b) + 2f)
    x = b
    while (x >= a) {
        out.point(x, ceiling(x))
        x -= step
    }
    out.point(a, ceiling(a)); out.point(a, nearY(a) + 2f)
    // The mouths' stones.
    val edge = Colors.lighter(rock, 0.18f)
    out.line(edge, h * 0.012f, a, nearY(a), a, ceiling(a))
    out.line(edge, h * 0.012f, b, nearY(b), b, ceiling(b))
    if (lane.look.world == World.MEADOW) {
        // Grass and a tree on top.
        val mid = (a + b) / 2f
        val y = nearY(mid) - top - h * 0.1f
        out.line(Colors.darker(p.mid, 0.5f), h * 0.008f, mid, y, mid, y - h * 0.05f)
        out.circle(Colors.darker(p.mid, 0.2f), mid, y - h * 0.065f, h * 0.03f)
    }
}

/** Whether the way goes through the middle of village [i] (houses behind and in front) or behind one row. */
private fun crossesVillage(i: Int) = hash(i, 512) < 0.5f

/**
 * A few houses and a church, in the traveller's own scale: walls a little taller than it, roofs
 * above, with room between them where it shows again. [row] 0: one row the traveller goes behind;
 * when the way crosses the village, -1 the row beyond the way (a little smaller: farther)
 * and 1 the row this side of it (lower, between the houses beyond).
 */
private fun Scene.village(a: Float, b: Float, seed: Int, row: Int = 0) {
    val ts = travellerSize
    val n = max(2, ((b - a) / (ts * 4.2f)).toInt())
    for (k in 0 until n) {
        // The near row stands between the far one's houses, one fewer.
        if (row == 1 && k == n - 1) break
        val x = a + (b - a) * (k + if (row == 1) 1f else 0.5f) / n
        val scale = when (row) {
            -1 -> 0.78f
            1 -> 1.08f
            else -> 1f
        }
        val s = ts * (1.75f + 0.4f * hash(seed + k + row * 31, 511)) * scale
        // Standing on the lowest ground under the whole house, a little sunk in: never in the air.
        var ground = nearY(x)
        for (m in -4..4) ground = max(ground, nearY(x + s * 0.65f * m / 4f))
        val sink = s * 0.04f + if (row == 1) ts * 0.3f else 0f
        if (row <= 0 && k == n / 2) church(x, ground + sink, s * 1.15f)
        else house(x, ground + sink, s, false, Scene.id(Scene.VILLAGE_HOUSE, seed * 16 + k * 2 + (row + 1)))
        // The back row stands behind the way the traveller goes along: the ground in front covers its foot.
        if (row == -1) groundStrip(x - s * 0.75f, x + s * 0.75f)
    }
}

private fun Scene.church(x: Float, ground: Float, s: Float) {
    val wall = Colors.lighter(p.mid, 0.5f)
    out.rect(wall, x - s * 0.5f, ground - s * 0.7f, x + s * 0.3f, ground)
    out.rect(wall, x + s * 0.3f, ground - s * 1.5f, x + s * 0.65f, ground)
    out.polygon(Colors.darker(wall, 0.35f))
    out.point(x + s * 0.25f, ground - s * 1.5f); out.point(x + s * 0.475f, ground - s * 2.1f); out.point(x + s * 0.7f, ground - s * 1.5f)
    out.polygon(Colors.mix(0xFFB04A3A.toInt(), p.accent, 0.2f))
    out.point(x - s * 0.6f, ground - s * 0.7f); out.point(x - s * 0.1f, ground - s * 1.05f); out.point(x + s * 0.35f, ground - s * 0.7f)
    // The bell swings with the beat.
    val swing = sin(beat * 2 * PI).toFloat() * s * 0.04f
    out.circle(0xFFD9B44A.toInt(), x + s * 0.475f + swing, ground - s * 1.25f, s * 0.07f)
}

/** How far the train's track runs beyond the platform on each side. */
private val Scene.trainRun get() = h * 0.46f

/**
 * A station, in depth as the villages are: the track and the train beyond the platform (higher
 * on the screen, a little smaller: farther), the platform the traveller walks along, its roof
 * on posts at the back; the posts and the platform's edge on this side stand in front of the
 * traveller ([stationFront]).
 */
private fun Scene.station(a: Float, b: Float) {
    val ts = travellerSize
    val ground = footNear((a + b) / 2f, (b - a) / 2f)
    val grey = Colors.mix(p.mid, 0xFF9A9A9A.toInt(), 0.4f)
    val car = h * 0.085f
    val cars = 4
    val trainLen = cars * car * 1.05f + car * 0.4f
    // The track runs on past both edges of the screen: the train comes in from the left and
    // goes out on the right, never appearing out of nowhere.
    val from = minOf(a - trainRun, -car)
    val to = maxOf(b + trainRun, w + car)
    // The track: on the far side of the platform.
    val rail = ground - ts * 0.42f
    val ballast = Colors.darker(Colors.mix(p.near, 0xFF7A6A58.toInt(), 0.4f), 0.2f)
    out.rect(ballast, from, rail + h * 0.002f, to, rail + h * 0.012f)
    var sx = from
    while (sx < to) {
        out.rect(0xFF5A4636.toInt(), sx, rail - h * 0.002f, sx + h * 0.01f, rail + h * 0.005f)
        sx += h * 0.026f
    }
    out.line(0xFF9AA0A8.toInt(), h * 0.0035f, from, rail - h * 0.002f, to, rail - h * 0.002f)
    // The train crosses the screen from left to right, then a pause before the next one.
    val speed = h * 0.0006f
    val cycle = w + trainLen * 2f + speed * 3_000f
    val tx = -trainLen - car * 0.4f + (now * speed) % cycle
    val body = Colors.mix(p.accent, 0xFFC03A2E.toInt(), 0.4f)
    for (k in 0 until cars) {
        val x0 = tx + k * car * 1.05f
        out.rect(body, x0, rail - car * 0.6f, x0 + car, rail - car * 0.08f)
        for (wdw in 0 until 3) out.rect(Colors.lighter(p.skyBottom, 0.3f), x0 + car * (0.1f + wdw * 0.3f), rail - car * 0.5f, x0 + car * (0.3f + wdw * 0.3f), rail - car * 0.32f)
        for (wx in floatArrayOf(x0 + car * 0.2f, x0 + car * 0.8f)) out.circle(0xFF2A2A30.toInt(), wx, rail - car * 0.06f, car * 0.06f)
    }
    out.polygon(body)
    val nose = tx + cars * car * 1.05f
    out.point(nose, rail - car * 0.6f); out.point(nose + car * 0.4f, rail - car * 0.2f); out.point(nose + car * 0.4f, rail - car * 0.08f); out.point(nose, rail - car * 0.08f)
    // The platform's floor, from the track's edge to the way.
    out.rect(grey, a, rail + h * 0.012f, b, ground + h * 0.004f)
    out.rect(Colors.lighter(grey, 0.25f), a, rail + h * 0.012f, b, rail + h * 0.018f)
    // The roof on its back posts.
    val roof = ground - ts * 1.75f
    for (k in 0..4) {
        val px = a + (b - a) * k / 4f
        out.line(Colors.darker(grey, 0.35f), h * 0.005f, px, rail + h * 0.012f, px, roof)
    }
    out.polygon(Colors.darker(grey, 0.2f))
    out.point(a - h * 0.02f, roof); out.point(b + h * 0.02f, roof); out.point(b, roof - h * 0.025f); out.point(a, roof - h * 0.025f)
}

/** The station's near side: the platform's edge and its front posts, in front of the traveller. */
private fun Scene.stationFront(a: Float, b: Float) {
    val ts = travellerSize
    val ground = footNear((a + b) / 2f, (b - a) / 2f)
    val grey = Colors.mix(p.mid, 0xFF9A9A9A.toInt(), 0.4f)
    out.rect(Colors.darker(grey, 0.15f), a, ground, b, ground + h * 0.02f)
    out.rect(0xFFE8C840.toInt(), a, ground + h * 0.002f, b, ground + h * 0.006f)
    val roof = ground - ts * 1.75f
    for (k in 0..2) {
        val px = a + (b - a) * (k + 0.5f) / 3f
        out.line(Colors.darker(grey, 0.2f), h * 0.007f, px, ground + h * 0.02f, px, roof + ts * 0.15f)
    }
    out.rect(Colors.darker(grey, 0.1f), a - h * 0.02f, roof + ts * 0.1f, b + h * 0.02f, roof + ts * 0.16f)
}

/**
 * A big wheel turning slowly, a little quicker while the music is loud (smoothed: never a jerk),
 * its cabins lit on the beats. Its legs stand in front ([wheelLegs]).
 */
private fun Scene.bigWheel(cx: Float) {
    val r = h * 0.17f
    val ground = footNear(cx, r * 0.5f)
    val hubY = ground - r * 1.15f
    val frame = Colors.mix(p.light, p.mid, 0.4f)
    out.ring(frame, cx, hubY, r, h * 0.005f)
    val turn = moves.spinMs / 7000.0
    for (k in 0 until 10) {
        val a = turn + k * PI / 5
        val ex = cx + (cos(a) * r).toFloat()
        val ey = hubY + (sin(a) * r).toFloat()
        out.line(Colors.alpha(frame, 0.7f), h * 0.002f, cx, hubY, ex, ey)
        val lit = (k + moves.onsets) % 3 == 0
        val c = if (k % 2 == 0) p.accent else p.light
        out.rect(if (lit) c else Colors.darker(c, 0.35f), ex - h * 0.012f, ey, ex + h * 0.012f, ey + h * 0.022f)
    }
    out.circle(frame, cx, hubY, h * 0.012f)
}

private fun Scene.wheelLegs(cx: Float) {
    val r = h * 0.17f
    val ground = footNear(cx, r * 0.5f)
    val hubY = ground - r * 1.15f
    val frame = Colors.mix(p.light, p.mid, 0.4f)
    out.line(frame, h * 0.006f, cx, hubY, cx - r * 0.55f, ground)
    out.line(frame, h * 0.006f, cx, hubY, cx + r * 0.55f, ground)
    out.circle(frame, cx, hubY, h * 0.009f)
}

/** A giant screen showing the cover, its frame flashing on strong beats. */
private fun Scene.bigScreen(cx: Float) {
    val side = h * 0.22f
    val ground = footNear(cx, side * 0.35f)
    val top = ground - h * 0.06f - side
    val frame = Colors.darker(p.mid, 0.3f)
    out.line(frame, h * 0.008f, cx - side * 0.3f, ground, cx - side * 0.3f, top + side)
    out.line(frame, h * 0.008f, cx + side * 0.3f, ground, cx + side * 0.3f, top + side)
    out.rect(Colors.alpha(p.accent, 0.2f + 0.6f * kick), cx - side * 0.6f, top - side * 0.1f, cx + side * 0.6f, top + side * 1.1f)
    out.rect(frame, cx - side * 0.55f, top - side * 0.05f, cx + side * 0.55f, top + side * 1.05f)
    out.cover(p.accent, cx, top + side * 0.5f, side * (0.98f + 0.04f * kick))
}

/** The ruins of a church: broken arches. */
private fun Scene.ruins(a: Float, b: Float) {
    val stone = Colors.lighter(p.mid, 0.15f)
    val ground = footNear((a + b) / 2f, (b - a) / 2f)
    val n = 4
    val bay = (b - a) / n
    for (k in 0..n) {
        val x = a + k * bay
        val tall = h * (0.12f + 0.08f * hash(k + a.toInt() / 97, 521)) * (if (k == n) 0.5f else 1f)
        out.rect(stone, x - h * 0.012f, ground - tall, x + h * 0.012f, ground + 2f)
        if (k < n && hash(k, 522) < 0.7f) {
            out.polyline(stone, h * 0.012f)
            for (m in 0..10) {
                val ang = PI + PI * m / 10
                out.point(x + bay / 2f + (cos(ang) * bay / 2f).toFloat(), ground - h * 0.12f + (sin(ang) * bay * 0.6f).toFloat())
            }
        }
    }
    // A rose window, glowing with the sound.
    out.ring(stone, a + bay * 1.5f, ground - h * 0.22f, h * 0.035f, h * 0.008f)
    out.circle(Colors.alpha(p.accent, 0.3f + 0.4f * live.level), a + bay * 1.5f, ground - h * 0.22f, h * 0.03f)
}

/** A great arch of ice over the sea; the traveller drifts under it. */
private fun Scene.iceArch(a: Float, b: Float) {
    val ice = Colors.alpha(Colors.mix(p.mid, 0xFFFFFFFF.toInt(), 0.25f), 0.95f)
    val water = nearBase
    val top = water - travellerSize * 2.4f
    val thick = h * 0.05f
    out.polygon(ice)
    out.point(a - thick, water + h * 0.02f)
    for (m in 0..16) {
        val t = m / 16f
        out.point(a - thick + (b - a + thick * 2) * t, top - thick - sin(PI * t).toFloat() * h * 0.08f)
    }
    out.point(b + thick, water + h * 0.02f)
    out.point(b, water + h * 0.02f)
    for (m in 16 downTo 0) {
        val t = m / 16f
        out.point(a + (b - a) * t, top - sin(PI * t).toFloat() * h * 0.07f + (1f - sin(PI * t).toFloat()) * h * 0.04f)
    }
    out.point(a, water + h * 0.02f)
    // Icicles dripping light.
    for (k in 1 until 6) {
        val x = a + (b - a) * k / 6f
        val y = top - sin(PI * k / 6f).toFloat() * h * 0.07f
        out.polygon(ice)
        out.point(x - h * 0.006f, y); out.point(x, y + h * (0.02f + 0.02f * hash(k, 531))); out.point(x + h * 0.006f, y)
    }
}

/** Domes of a moon base with lit windows and an aerial. */
private fun Scene.moonBase(a: Float, b: Float) {
    val shell = 0xFFD8D8E0.toInt()
    for (k in 0 until 3) {
        val x = a + (b - a) * (0.2f + 0.3f * k)
        val r = h * (0.05f + 0.025f * k % 2)
        val g = footNear(x, r)
        out.polygon(shell)
        for (m in 0..12) {
            val ang = PI + PI * m / 12
            out.point(x + (cos(ang) * r).toFloat(), g + (sin(ang) * r).toFloat())
        }
        out.rect(Colors.alpha(p.accent, 0.6f + 0.4f * kick), x - r * 0.15f, g - r * 0.5f, x + r * 0.15f, g - r * 0.3f)
    }
    val ax = b - (b - a) * 0.05f
    val ag = nearY(ax)
    out.line(shell, h * 0.004f, ax, ag, ax, ag - h * 0.12f)
    out.ring(shell, ax, ag - h * 0.12f, h * 0.02f, h * 0.003f)
    if ((now / 400) % 2 == 0L) out.circle(0xFFFF4040.toInt(), ax, ag - h * 0.12f, h * 0.006f)
}

/** A rocket on its pad; it lifts off as the traveller goes by. */
private fun Scene.rocket(cx: Float) {
    val g = footNear(cx, h * 0.03f)
    val s = h * 0.13f
    val gone = (travellerX + w * 0.1f - cx).coerceAtLeast(0f)
    val lift = gone * gone / (w * 0.4f)
    val y = g - lift
    out.line(0xFF8A8A9A.toInt(), h * 0.005f, cx - s * 0.35f, g, cx - s * 0.35f, g - s * 0.9f)
    if (lift > 1f) {
        val flame = s * (0.4f + 0.3f * live.level) * (0.85f + 0.15f * sin(now / 40.0).toFloat())
        out.polygon(Colors.alpha(p.accent, 0.9f))
        out.point(cx - s * 0.1f, y); out.point(cx, y + flame); out.point(cx + s * 0.1f, y)
        for (k in 0 until 4) out.circle(Colors.alpha(0xFFE8E4DC.toInt(), 0.5f), cx + (k - 1.5f) * s * 0.15f, g - s * 0.05f, s * (0.12f + 0.05f * k % 2))
    }
    val body = 0xFFEDEDF2.toInt()
    out.rect(body, cx - s * 0.12f, y - s, cx + s * 0.12f, y)
    out.polygon(Colors.mix(0xFFD04040.toInt(), p.accent, 0.3f))
    out.point(cx - s * 0.12f, y - s); out.point(cx, y - s * 1.3f); out.point(cx + s * 0.12f, y - s)
    out.polygon(Colors.mix(0xFFD04040.toInt(), p.accent, 0.3f))
    out.point(cx - s * 0.12f, y - s * 0.25f); out.point(cx - s * 0.25f, y); out.point(cx - s * 0.12f, y)
    out.polygon(Colors.mix(0xFFD04040.toInt(), p.accent, 0.3f))
    out.point(cx + s * 0.12f, y - s * 0.25f); out.point(cx + s * 0.25f, y); out.point(cx + s * 0.12f, y)
    out.circle(Colors.mix(0xFF3A6EA5.toInt(), p.accent, 0.2f), cx, y - s * 0.7f, s * 0.06f)
}

// ---------- In front ----------

/** Now and then something rushes by close in front: tall grass, a branch, a lamp post, rocks. */
internal fun Scene.foreground() {
    val c = Colors.darker(Colors.mix(p.near, p.mid, 0.2f), 0.7f)
    // They grow from the land's foot (over a floating player, the land is lifted) and reach the bottom.
    slots(0.45f, w * 0.3f) { i, x0, x1 ->
        if (hash(i, 601) > 0.2f + 0.15f * look.energy) return@slots
        val x = x0 + (x1 - x0) * hash(i, 602)
        val tall = h * (0.16f + 0.1f * hash(i, 603))
        when (foregroundLike(lane.look.world)) {
            World.MEADOW, World.COAST -> if (hash(i, 604) < 0.3f && lane.look.world == World.MEADOW) {
                bough(x, c, i)
            } else {
                val sway = sin(beat * 2 * PI + i).toFloat() * unit * 0.01f * (0.5f + live.level)
                for (k in 0 until 7) {
                    val bx = x + (k - 3) * unit * 0.012f
                    out.polyline(c, unit * 0.008f)
                    out.point(bx, bottom + 2f); out.point(bx + (k - 3) * unit * 0.006f + sway * 0.5f, h - tall * 0.5f); out.point(bx + (k - 3) * unit * 0.014f + sway, h - tall * (0.7f + 0.3f * hash(i + k, 605)))
                }
            }
            World.CITY, World.NEON -> {
                // A lamp post going by.
                out.line(c, unit * 0.016f, x, bottom + 2f, x, h - h * 0.75f)
                out.line(c, unit * 0.01f, x, h - h * 0.75f, x + unit * 0.07f, h - h * 0.77f)
                val lamp = if (lane.look.world == World.NEON || look.night > 0.4f) p.light else Colors.lighter(c, 0.3f)
                out.circle(Colors.alpha(lamp, 0.2f + 0.2f * kick), x + unit * 0.07f, h - h * 0.76f, unit * 0.05f)
                out.circle(lamp, x + unit * 0.07f, h - h * 0.76f, unit * 0.014f)
            }
            World.CASTLE -> {
                out.polyline(c, unit * 0.014f)
                out.point(x, bottom + 2f); out.point(x + unit * 0.02f, h - tall * 0.6f); out.point(x - unit * 0.04f, h - tall)
                out.line(c, unit * 0.008f, x + unit * 0.01f, h - tall * 0.4f, x + unit * 0.08f, h - tall * 0.65f)
            }
            World.ICE, World.SPACE -> {
                out.polygon(if (lane.look.world == World.ICE) Colors.alpha(Colors.mix(p.mid, 0xFFFFFFFF.toInt(), 0.2f), 0.9f) else c)
                out.point(x - unit * 0.08f, bottom + 2f); out.point(x - unit * 0.03f, h - tall * 0.6f); out.point(x + unit * 0.01f, h - tall * 0.45f); out.point(x + unit * 0.05f, h - tall * 0.7f); out.point(x + unit * 0.09f, bottom + 2f)
            }
            else -> {}
        }
    }
}

/** A leafy bough reaching down from a tree out of sight, its leaves stirring with the beat. */
private fun Scene.bough(x: Float, c: Int, seed: Int) {
    val u = unit
    val sway = sin(beat * 2 * PI + seed).toFloat() * (0.4f + 0.6f * live.level)
    // The bough: from above the top edge, curving down and out; thick at the base, thin at the tip.
    val bx = FloatArray(6)
    val by = FloatArray(6)
    for (k in 0 until 6) {
        val t = k / 5f
        bx[k] = x - u * 0.22f + t * u * 0.32f + sway * u * 0.006f * t
        by[k] = -u * 0.02f + sin(t * PI * 0.5).toFloat() * h * 0.17f
    }
    for (k in 0 until 5) out.line(c, u * (0.024f - 0.0035f * k), bx[k], by[k], bx[k + 1], by[k + 1])
    val leafColor = Colors.lighter(c, 0.12f)
    // Twigs hanging from it, each with its leaves; leaves along the bough too.
    for (t in intArrayOf(1, 2, 3, 4)) {
        val tx = bx[t]
        val ty = by[t]
        val len = u * (0.05f + 0.02f * hash(seed + t, 606))
        val ex = tx + len * 0.35f + sway * u * 0.01f
        val ey = ty + len
        out.line(c, u * 0.006f, tx, ty, ex, ey)
        for (m in 0..3) {
            val f = 0.3f + m * 0.23f
            val lx = tx + (ex - tx) * f
            val ly = ty + (ey - ty) * f
            val side = if (m % 2 == 0) 1f else -1f
            leaf(leafColor, lx, ly, u * 0.032f, (PI / 2).toFloat() + side * 0.9f + sway * 0.15f)
        }
        leaf(leafColor, ex, ey, u * 0.034f, (PI / 2).toFloat() + sway * 0.2f)
    }
    for (k in 1 until 6) leaf(c, bx[k] - u * 0.01f, by[k] + u * 0.006f, u * 0.03f, (PI / 2).toFloat() + 0.6f + sway * 0.12f)
}

/** A pointed leaf [len] long from its stalk at [x], [y], pointing at [angle] (radians, clockwise from the right). */
private fun Scene.leaf(color: Int, x: Float, y: Float, len: Float, angle: Float) {
    val dx = cos(angle)
    val dy = sin(angle)
    val wide = len * 0.22f
    out.polygon(color)
    out.point(x, y)
    out.point(x + dx * len * 0.45f - dy * wide, y + dy * len * 0.45f + dx * wide)
    out.point(x + dx * len, y + dy * len)
    out.point(x + dx * len * 0.45f + dy * wide, y + dy * len * 0.45f - dx * wide)
}

// ---------- The end of the level ----------

/**
 * A sign with the next track's title (and its place in the list) before the end, and at the end a
 * gate into the next track's world: a walled town's gate, a gate in a fence, a barrier lifting,
 * a neon arch, bunting by the sea, an ice gate, a ring into space; a plain finish arch when the
 * next track is not known.
 */
internal fun Scene.finish() {
    val len = lane.wave?.lengthMs ?: return
    val s = travellerSize
    lane.nextTitle?.let { title ->
        val sx = xAt(len - 7000.0, 1f)
        if (sx > -w * 0.3f && sx < w * 1.3f) {
            val g = nearY(sx)
            val wood = Colors.mix(0xFF8A6440.toInt(), p.near, 0.2f)
            val boardW = s * 2.8f
            val boardH = s * 0.6f
            val top = g - s * 1.8f
            out.line(wood, s * 0.08f, sx, g, sx, top + boardH)
            out.polygon(wood)
            out.point(sx - boardW / 2f, top); out.point(sx + boardW / 2f, top); out.point(sx + boardW / 2f + boardH * 0.4f, top + boardH / 2f); out.point(sx + boardW / 2f, top + boardH); out.point(sx - boardW / 2f, top + boardH)
            val shown = if (title.length > 24) title.take(23) + "…" else title
            out.text(0xFFFFF8EC.toInt(), sx, top + boardH / 2f, boardH * 0.42f, shown)
            lane.nextPlace?.let { place ->
                // A small plate on top: where the next track is in the list.
                val plateW = boardH * (0.5f + 0.22f * place.length)
                out.rect(Colors.darker(wood, 0.3f), sx - plateW / 2f, top - boardH * 0.62f, sx + plateW / 2f, top - boardH * 0.04f)
                out.text(0xFFFFF8EC.toInt(), sx, top - boardH * 0.33f, boardH * 0.36f, place)
            }
        }
    }
    val fx = xAt(len.toDouble(), 1f)
    if (fx < -w * 0.3f || fx > w * 1.3f) return
    when (lane.look.world) {
        // The traveller does not walk there: the gate of its own world.
        World.SPACE -> spaceRing(fx, s)
        World.ICE -> iceGate(fx, s)
        else -> when (lane.nextWorld) {
            World.CASTLE -> wallGate(fx, s)
            World.MEADOW -> fenceGate(fx, s)
            World.CITY -> barrier(fx, s)
            World.NEON -> neonArch(fx, s)
            World.COAST -> bunting(fx, s)
            World.ICE -> iceGate(fx, s)
            World.SPACE -> spaceRing(fx, s)
            World.DESERT, World.CANYON -> fenceGate(fx, s)
            World.WALL, World.VOLCANO -> wallGate(fx, s)
            World.JUNGLE, World.AMAZON, World.JAPAN -> bunting(fx, s)
            World.REEF -> iceGate(fx, s)
            null -> finishArch(fx, s)
        }
    }
}

/** The ground under a gate's post at [x]: the sea's level where the traveller drifts on it. */
private fun Scene.gateFoot(x: Float, s: Float) =
    if (lane.look.world == World.ICE) nearBase - heightAt(x, 1f, 60f) * nearAmp * 0.6f + s * 0.05f else footNear(x, s * 0.12f)

/** The plain finish: two posts and a checked banner. */
private fun Scene.finishArch(fx: Float, s: Float) {
    val post = Colors.darker(p.mid, 0.4f)
    val left = fx - s * 0.85f
    val right = fx + s * 0.85f
    val gl = gateFoot(left, s)
    val gr = gateFoot(right, s)
    val top = min(gl, gr) - s * 2.1f
    out.line(post, s * 0.08f, left, gl, left, top)
    out.line(post, s * 0.08f, right, gr, right, top)
    // The checked banner: few, big squares.
    val cells = 6
    val cw = (right - left) / cells
    out.rect(0xFFF6F6F2.toInt(), left, top, right, top + cw * 2)
    for (row in 0..1) for (k in 0 until cells) {
        if ((k + row) % 2 == 0) out.rect(0xFF15151A.toInt(), left + k * cw, top + row * cw, left + (k + 1) * cw, top + (row + 1) * cw)
    }
    flags(left, right, top, s, post)
}

private fun Scene.flags(left: Float, right: Float, top: Float, s: Float, post: Int) {
    for (fxp in floatArrayOf(left, right)) {
        val wave = sin(beat * 2 * PI).toFloat() * s * 0.03f
        out.line(post, s * 0.03f, fxp, top, fxp, top - s * 0.4f)
        out.polygon(p.accent)
        out.point(fxp, top - s * 0.4f); out.point(fxp + s * 0.3f, top - s * 0.32f + wave); out.point(fxp, top - s * 0.22f)
    }
}

/** A walled town's gate: two towers, the arch between them, the portcullis up, torches and banners. */
private fun Scene.wallGate(fx: Float, s: Float) {
    val stone = Colors.mix(Colors.lighter(p.mid, 0.25f), 0xFF8C8478.toInt(), 0.45f)
    val dark = Colors.darker(stone, 0.3f)
    val left = fx - s * 0.85f
    val right = fx + s * 0.85f
    val gl = gateFoot(left - s * 0.3f, s)
    val gr = gateFoot(right + s * 0.3f, s)
    val top = min(gl, gr) - s * 2.15f
    val tower = s * 0.55f
    // The wall going off on both sides, lower.
    out.rect(dark, left - s * 3f, top + s * 0.5f, left, gl + 2f)
    out.rect(dark, right, top + s * 0.5f, right + s * 3f, gr + 2f)
    for (k in 0 until 6) {
        out.rect(dark, left - s * 3f + k * s * 0.5f, top + s * 0.35f, left - s * 2.75f + k * s * 0.5f, top + s * 0.5f)
        out.rect(dark, right + k * s * 0.5f + s * 0.25f, top + s * 0.35f, right + k * s * 0.5f + s * 0.5f, top + s * 0.5f)
    }
    // The towers and the arch over the way.
    out.rect(stone, left - tower, top - s * 0.7f, left, gl + 2f)
    out.rect(stone, right, top - s * 0.7f, right + tower, gr + 2f)
    out.polygon(stone)
    out.point(left, top - s * 0.45f); out.point(right, top - s * 0.45f); out.point(right, top + s * 0.25f)
    for (m in 0..10) {
        val a = PI * m / 10
        out.point(fx + (cos(a) * (right - left) / 2f).toFloat(), top + s * 0.25f - (sin(a) * s * 0.35f).toFloat())
    }
    out.point(left, top + s * 0.25f)
    // Battlements.
    for (k in 0 until 3) {
        out.rect(stone, left - tower + k * tower * 0.4f, top - s * 0.85f, left - tower + k * tower * 0.4f + tower * 0.22f, top - s * 0.7f)
        out.rect(stone, right + k * tower * 0.4f, top - s * 0.85f, right + k * tower * 0.4f + tower * 0.22f, top - s * 0.7f)
    }
    for (k in 0 until 5) out.rect(stone, left + k * (right - left) / 5f + s * 0.05f, top - s * 0.58f, left + k * (right - left) / 5f + s * 0.22f, top - s * 0.45f)
    // The portcullis, raised: its teeth under the arch.
    val iron = 0xFF2E2A28.toInt()
    out.line(iron, s * 0.04f, left + s * 0.1f, top + s * 0.02f, right - s * 0.1f, top + s * 0.02f)
    var bx = left + s * 0.15f
    while (bx < right - s * 0.1f) {
        out.line(iron, s * 0.035f, bx, top - s * 0.05f, bx, top + s * 0.14f)
        bx += s * 0.22f
    }
    // Banners on the towers and torches by the way in.
    for (tx in floatArrayOf(left - tower / 2f, right + tower / 2f)) {
        val sway = sin(beat * 2 * PI + tx).toFloat() * s * 0.02f
        out.polygon(p.accent)
        out.point(tx - tower * 0.3f, top - s * 0.55f); out.point(tx + tower * 0.3f, top - s * 0.55f)
        out.point(tx + tower * 0.3f + sway, top + s * 0.15f); out.point(tx + sway, top - s * 0.02f); out.point(tx - tower * 0.3f + sway, top + s * 0.15f)
    }
    torch(left - tower * 0.15f, top + s * 0.95f, s * 0.8f, -0.25f)
    torch(right + tower * 0.15f, top + s * 0.95f, s * 0.8f, 0.25f)
}

/** A gate in a wooden fence, swung open towards the traveller. */
private fun Scene.fenceGate(fx: Float, s: Float) {
    val wood = Colors.mix(0xFF8A6A48.toInt(), p.near, 0.25f)
    val left = fx - s * 0.85f
    val right = fx + s * 0.85f
    // The fence on both sides.
    for (side in intArrayOf(-1, 1)) {
        val from = if (side < 0) left - s * 3f else right
        val to = if (side < 0) left else right + s * 3f
        var x = from
        while (x <= to + 1f) {
            val g = nearY(x)
            out.line(wood, s * 0.07f, x, g + s * 0.03f, x, g - s * 0.62f)
            out.polygon(wood); out.point(x - s * 0.035f, g - s * 0.62f); out.point(x, g - s * 0.7f); out.point(x + s * 0.035f, g - s * 0.62f)
            x += s * 0.38f
        }
        for (rail in floatArrayOf(0.2f, 0.48f)) {
            out.polyline(wood, s * 0.05f)
            x = from
            while (x <= to + 1f) {
                out.point(x, nearY(x) - s * rail)
                x += s * 0.19f
            }
        }
    }
    // The gate's posts, taller, with a sign board between them.
    val gl = footNear(left, s * 0.05f)
    val gr = footNear(right, s * 0.05f)
    val top = min(gl, gr) - s * 2.0f
    out.line(Colors.darker(wood, 0.15f), s * 0.11f, left, gl + s * 0.03f, left, top)
    out.line(Colors.darker(wood, 0.15f), s * 0.11f, right, gr + s * 0.03f, right, top)
    out.line(Colors.darker(wood, 0.15f), s * 0.09f, left - s * 0.12f, top + s * 0.05f, right + s * 0.12f, top + s * 0.05f)
    // A garland of flowers on the beam.
    for (k in 0..8) {
        val x = left + (right - left) * k / 8f
        val y = top + s * 0.12f + sin(PI * k / 8).toFloat() * s * 0.12f
        out.circle(if (k % 2 == 0) p.accent else Colors.lighter(p.accent, 0.5f), x, y, s * 0.05f * (1f + 0.3f * kick))
    }
    // The open leaf: turned towards us on its hinge, so it looks narrow.
    val hinge = right
    val leafW = s * 0.5f
    out.polyline(wood, s * 0.05f)
    out.point(hinge, gr - s * 0.12f); out.point(hinge + leafW, gr - s * 0.02f); out.point(hinge + leafW, gr - s * 0.66f); out.point(hinge, gr - s * 0.72f); out.point(hinge, gr - s * 0.12f)
    out.line(wood, s * 0.045f, hinge, gr - s * 0.12f, hinge + leafW, gr - s * 0.66f)
}

/** A barrier across the road that lifts as the traveller comes near, its light turning green. */
private fun Scene.barrier(fx: Float, s: Float) {
    val g = footNear(fx + s * 0.9f, s * 0.2f)
    val booth = Colors.lighter(p.mid, 0.35f)
    // The booth by the road.
    val bx = fx + s * 0.95f
    out.rect(booth, bx, g - s * 1.1f, bx + s * 0.7f, g + 2f)
    out.rect(Colors.darker(booth, 0.35f), bx - s * 0.06f, g - s * 1.2f, bx + s * 0.76f, g - s * 1.08f)
    out.rect(Colors.mix(p.skyBottom, p.light, 0.4f), bx + s * 0.12f, g - s * 0.95f, bx + s * 0.58f, g - s * 0.6f)
    // The arm: down, then up as the traveller comes (turning on its post by the booth).
    val near = ((fx - travellerX) / (s * 2.5f)).coerceIn(0f, 1f)
    val up = 1f - near
    val angle = (PI / 2).toFloat() * (up * up * (3f - 2f * up))
    val px = fx + s * 0.85f
    val py = g - s * 0.55f
    out.rect(Colors.darker(booth, 0.4f), px - s * 0.06f, py, px + s * 0.06f, g + 2f)
    val armLen = s * 2.2f
    out.turn(angle, px, py)
    out.rect(0xFFF4F4F0.toInt(), px - armLen, py - s * 0.05f, px, py + s * 0.05f)
    for (k in 0 until 5) out.rect(0xFFD8342C.toInt(), px - armLen + k * armLen / 5f, py - s * 0.05f, px - armLen + k * armLen / 5f + armLen / 10f, py + s * 0.05f)
    out.straight()
    // The light on top of the booth: red, then green.
    val lightC = if (up > 0.5f) 0xFF3BD16F.toInt() else 0xFFE5443A.toInt()
    out.circle(Colors.alpha(lightC, 0.3f), bx + s * 0.35f, g - s * 1.32f, s * 0.14f)
    out.circle(lightC, bx + s * 0.35f, g - s * 1.32f, s * 0.07f)
}

/** An arch of neon tubes, lights running round it on the beat. */
private fun Scene.neonArch(fx: Float, s: Float) {
    val left = fx - s * 0.95f
    val right = fx + s * 0.95f
    val gl = footNear(left, s * 0.1f)
    val gr = footNear(right, s * 0.1f)
    val base = min(gl, gr)
    val rise = s * 2.3f
    for ((k, c) in listOf(p.accent, p.light).withIndex()) {
        val r = 1f + k * 0.14f
        val glow = Colors.alpha(c, 0.18f + 0.25f * kick)
        val core = Colors.alpha(c, 0.75f + 0.25f * live.level)
        for ((color, width) in listOf(glow to s * 0.16f, core to s * 0.04f)) {
            out.polyline(color, width)
            out.point(fx - (fx - left) * r, gl + 2f)
            for (m in 0..16) {
                val a = PI * m / 16
                out.point(fx - (cos(a) * (fx - left) * r).toFloat(), base - s * 0.6f - (sin(a) * rise * r * 0.75f).toFloat())
            }
            out.point(fx + (right - fx) * r, gr + 2f)
        }
    }
    // Running lights.
    val run = (moves.onsets % 16)
    for (m in 0..16) {
        val a = PI * m / 16
        val on = (m + run) % 4 == 0
        out.circle(if (on) 0xFFFFFFFF.toInt() else Colors.alpha(p.light, 0.4f), fx - (cos(a) * (fx - left) * 1.07f).toFloat(), base - s * 0.6f - (sin(a) * rise * 1.07f * 0.75f).toFloat(), s * (if (on) 0.05f else 0.03f))
    }
}

/** Two posts by the sea with bunting between them and a lifebuoy. */
private fun Scene.bunting(fx: Float, s: Float) {
    val wood = Colors.mix(0xFF9A7A58.toInt(), p.near, 0.2f)
    val left = fx - s * 0.9f
    val right = fx + s * 0.9f
    val gl = footNear(left, s * 0.06f)
    val gr = footNear(right, s * 0.06f)
    val top = min(gl, gr) - s * 2.2f
    out.line(wood, s * 0.1f, left, gl + 2f, left, top)
    out.line(wood, s * 0.1f, right, gr + 2f, right, top)
    // A rope sagging between them, two rows of little flags.
    val colors = intArrayOf(p.accent, 0xFFFFFFFF.toInt(), 0xFF3A7BD5.toInt(), 0xFFF5D547.toInt())
    for (row in 0..1) {
        val y0 = top + row * s * 0.35f
        val sag = s * (0.35f + row * 0.1f)
        out.polyline(0xFF6A5A4A.toInt(), s * 0.02f)
        for (m in 0..12) out.point(left + (right - left) * m / 12f, y0 + sin(PI * m / 12).toFloat() * sag)
        for (m in 0 until 9) {
            val t = (m + 0.5f) / 9f
            val x = left + (right - left) * t
            val y = y0 + sin(PI * t).toFloat() * sag
            val flap = sin(beat * 2 * PI + m).toFloat() * s * 0.03f
            out.polygon(colors[(m + row) % colors.size])
            out.point(x - s * 0.07f, y); out.point(x + s * 0.07f, y); out.point(x + flap, y + s * 0.18f)
        }
    }
    // The lifebuoy on a post.
    out.ring(0xFFF4F4F0.toInt(), left, gl - s * 0.9f, s * 0.2f, s * 0.09f)
    for (k in 0 until 4) {
        val a = k * PI / 2 + PI / 4
        out.circle(0xFFD8342C.toInt(), left + (cos(a) * s * 0.2f).toFloat(), gl - s * 0.9f + (sin(a) * s * 0.2f).toFloat(), s * 0.06f)
    }
}

/** Two pillars of ice with a lintel of icicles, rising out of the water or the snow. */
private fun Scene.iceGate(fx: Float, s: Float) {
    val ice = Colors.alpha(Colors.mix(p.mid, 0xFFFFFFFF.toInt(), 0.35f), 0.95f)
    val shade = Colors.alpha(Colors.mix(p.mid, 0xFF8EB4D0.toInt(), 0.4f), 0.95f)
    val left = fx - s * 1.1f
    val right = fx + s * 1.1f
    val gl = gateFoot(left, s)
    val gr = gateFoot(right, s)
    val top = min(gl, gr) - s * 2.4f
    for ((x, g) in listOf(left to gl, right to gr)) {
        out.polygon(ice)
        out.point(x - s * 0.3f, g + 2f); out.point(x - s * 0.2f, top); out.point(x + s * 0.18f, top - s * 0.1f); out.point(x + s * 0.28f, g + 2f)
        out.polygon(shade)
        out.point(x + s * 0.05f, g + 2f); out.point(x + s * 0.02f, top); out.point(x + s * 0.18f, top - s * 0.1f); out.point(x + s * 0.28f, g + 2f)
    }
    out.polygon(ice)
    out.point(left - s * 0.35f, top + s * 0.05f); out.point(left - s * 0.2f, top - s * 0.4f); out.point(fx, top - s * 0.55f); out.point(right + s * 0.2f, top - s * 0.4f); out.point(right + s * 0.35f, top + s * 0.05f)
    for (k in 1 until 9) {
        val x = left + (right - left) * k / 9f
        val drip = s * (0.12f + 0.15f * hash(k, 541))
        out.polygon(ice)
        out.point(x - s * 0.05f, top); out.point(x, top + drip); out.point(x + s * 0.05f, top)
    }
    // A glint going across with the beat.
    val glint = (beat * (right - left))
    out.circle(Colors.alpha(0xFFFFFFFF.toInt(), 0.5f * (0.4f + live.high)), left + glint, top - s * 0.25f, s * 0.06f)
}

/** A ring standing in space (or on the ground) with light swirling in it: the way through. */
private fun Scene.spaceRing(fx: Float, s: Float) {
    val r = s * 1.25f
    val cy = if (lane.look.world == World.SPACE) h * 0.5f - heightAt(fx, 1f, 40f) * h * 0.18f else footNear(fx, r * 0.6f) - r * 1.05f
    if (lane.look.world != World.SPACE) {
        val g = footNear(fx, r * 0.6f)
        out.polygon(Colors.darker(p.mid, 0.3f))
        out.point(fx - r * 0.7f, g + 2f); out.point(fx - r * 0.4f, cy + r * 0.85f); out.point(fx + r * 0.4f, cy + r * 0.85f); out.point(fx + r * 0.7f, g + 2f)
    }
    val turn = moves.spinMs / 900.0
    for (k in 0 until 3) {
        val a = turn + k * 2.1
        out.circle(Colors.alpha(if (k == 1) p.light else p.accent, 0.12f + 0.12f * live.level), fx + (cos(a) * r * 0.3f).toFloat(), cy + (sin(a) * r * 0.3f).toFloat(), r * 0.75f)
    }
    out.ring(Colors.alpha(p.accent, 0.3f + 0.3f * kick), fx, cy, r, s * 0.3f)
    out.ring(Colors.mix(0xFFB8BCC8.toInt(), p.mid, 0.2f), fx, cy, r, s * 0.14f)
    for (k in 0 until 9) {
        val a = k * 2 * PI / 9 - PI / 2
        val on = (k + moves.onsets) % 3 == 0
        out.circle(if (on) p.light else Colors.darker(p.accent, 0.3f), fx + (cos(a) * r).toFloat(), cy + (sin(a) * r).toFloat(), s * 0.07f)
    }
}

// ---------- Company ----------

private enum class Mate { DOG, BIRD, DRONE }

/**
 * The traveller's own four dogs (see [ourDogs]), and now and then someone else keeping it company
 * for a while: some other dog (not while one of the four is there), a bird, a little drone.
 */
internal fun Scene.companion(song: SongLook) {
    ourDogs()
    dogsWatching()
    val seed = song.facts?.seed ?: 0
    val cycle = 42_000.0
    val shifted = posMs + hash(seed, 701) * cycle
    val round = floor(shifted / cycle).toInt()
    val t = shifted - round * cycle
    val stay = 22_000.0
    if (t > stay || hash(seed + round, 703) > 0.7f) return
    val kinds = when (song.world) {
        World.MEADOW, World.CITY, World.COAST, World.CASTLE -> if (song.traveller == Traveller.CAR) arrayOf(Mate.BIRD) else arrayOf(Mate.DOG, Mate.BIRD, Mate.BIRD)
        World.ICE -> arrayOf(Mate.BIRD)
        World.NEON, World.SPACE -> arrayOf(Mate.DRONE)
        World.DESERT, World.WALL, World.JUNGLE, World.CANYON, World.JAPAN, World.VOLCANO -> arrayOf(Mate.BIRD, Mate.BIRD, Mate.DOG)
        World.AMAZON -> arrayOf(Mate.BIRD)
        // Under the sea no bird keeps the traveller company.
        World.REEF -> return
    }
    val kind = kinds[(hash(seed + round, 702) * kinds.size).toInt().coerceAtMost(kinds.size - 1)]
    val s = travellerSize
    // Comes from behind, stays, then goes off ahead.
    val enter = (t / 2500.0).coerceAtMost(1.0).toFloat()
    val leave = ((t - (stay - 3000)) / 3000.0).coerceAtLeast(0.0).toFloat()
    val home = travellerX - s * 1.25f
    val x = -w * 0.1f + (home + w * 0.1f) * enter + leave * leave * w
    when (kind) {
        Mate.DOG -> {
            if (dogsAround()) return
            val did = Scene.id(Scene.MATE, seed + round)
            touchable(did, x, nearY(x) - s * 0.3f, s * 0.5f)
            // Touched: a somersault.
            val flip = bounce(poked(did), 800)
            val foot = nearY(x) - (flip?.get(0) ?: 0f) * s * 0.9f
            if (flip != null) out.turn(flip[1], x, foot - s * 0.2f)
            genericDog(x, foot, s * 0.35f, 1f, if (playing) sin(beat * 4 * PI).toFloat() else 0.2f * sin(now / 600.0).toFloat(), seed + round)
            out.straight()
        }
        Mate.BIRD -> {
            val bx = x + s * 1.6f + sin(now / 900.0).toFloat() * s * 0.3f
            val by = nearY(travellerX) - s * 2.1f + sin(now / 600.0).toFloat() * s * 0.2f - leave * h * 0.3f
            bird(bx, by, if (song.world == World.CASTLE) 0xFF15121A.toInt() else Colors.darker(p.far, 0.5f), s * 0.18f, 5)
        }
        Mate.DRONE -> {
            val dx = x + s * 1.9f
            val dy = nearY(travellerX) - s * 1.7f + sin(now / 500.0).toFloat() * s * 0.15f
            out.circle(Colors.alpha(p.accent, 0.25f + 0.25f * kick), dx, dy, s * 0.22f)
            out.circle(Colors.mix(0xFFD8D8E0.toInt(), p.accent, 0.2f), dx, dy, s * 0.1f)
            out.line(Colors.mix(0xFFD8D8E0.toInt(), p.accent, 0.2f), s * 0.03f, dx - s * 0.22f, dy - s * 0.08f, dx + s * 0.22f, dy - s * 0.08f)
            if ((now / 300) % 2 == 0L) out.circle(p.light, dx, dy + s * 0.02f, s * 0.035f)
        }
    }
}

/** Which old world's foreground a newer one borrows (grass, lamps, branches, ice…). */
private fun foregroundLike(w: World): World = when (w) {
    World.DESERT, World.CANYON -> World.COAST
    World.WALL, World.JAPAN, World.JUNGLE, World.AMAZON -> World.MEADOW
    World.REEF -> World.ICE
    World.VOLCANO -> World.CASTLE
    else -> w
}
