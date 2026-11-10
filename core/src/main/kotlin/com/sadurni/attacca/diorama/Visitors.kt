package com.sadurni.attacca.diorama

import com.sadurni.attacca.diorama.Diorama.Companion.hash
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// What passes by now and then (in the sky and on the ground), the things that stand in each land,
// and the track's own record with its cover.

private enum class Sky { UFO, SHOOTING_STARS, COMET, ASTRONAUT, BALLOON, BUTTERFLIES, GEESE, HELICOPTER, BLIMP, DRONES, FIREWORKS, FLYING_CAR, BATS, DRAGON, GHOST, SEAGULLS }

private enum class Runner { RABBIT, DEER, DOG, CRAB, MOTORBIKE, WOLF, ROVER, SEAL }

private const val SKY_PERIOD = 11_000L

/** Sky visitors that are creatures: they fly the traveller's way. */
private val FORWARD = setOf(Sky.GEESE, Sky.BATS, Sky.DRAGON, Sky.SEAGULLS, Sky.BUTTERFLIES, Sky.GHOST)
private const val GROUND_PERIOD = 14_000L

/** Something crossing the sky now and then, chosen by the world (and the night). */
internal fun Scene.skyVisitor() {
    // In the neon city strong beats set off fireworks of their own.
    if (look.world == World.NEON) beatFirework()
    val e = (now / SKY_PERIOD).toInt()
    val u = (now % SKY_PERIOD) / SKY_PERIOD.toFloat()
    if (hash(e, 201) < 0.22f - 0.12f * look.energy) return // a quiet while
    val night = look.night > 0.5f
    val kinds = when (look.world) {
        World.SPACE -> arrayOf(Sky.UFO, Sky.SHOOTING_STARS, Sky.COMET, Sky.ASTRONAUT)
        World.MEADOW -> if (night) arrayOf(Sky.SHOOTING_STARS, Sky.GEESE) else arrayOf(Sky.BALLOON, Sky.BUTTERFLIES, Sky.GEESE)
        World.CITY -> if (night) arrayOf(Sky.HELICOPTER, Sky.SHOOTING_STARS) else arrayOf(Sky.HELICOPTER, Sky.BLIMP, Sky.BALLOON)
        World.NEON -> arrayOf(Sky.DRONES, Sky.FIREWORKS, Sky.FLYING_CAR)
        World.CASTLE -> arrayOf(Sky.BATS, Sky.DRAGON, Sky.GHOST)
        World.COAST -> if (night) arrayOf(Sky.SHOOTING_STARS) else arrayOf(Sky.SEAGULLS, Sky.BALLOON, Sky.BUTTERFLIES)
        World.ICE -> arrayOf(Sky.SHOOTING_STARS, Sky.GEESE)
    }
    val kind = kinds[(hash(e, 202) * kinds.size).toInt().coerceAtMost(kinds.size - 1)]
    val y = h * (0.1f + 0.22f * hash(e, 203)) - live.low * h * 0.02f
    // Creatures fly the traveller's way; machines either way.
    val ltr = kind in FORWARD || hash(e, 204) < 0.5f
    // Across the screen, left to right or right to left.
    val x = if (ltr) -w * 0.15f + u * w * 1.3f else w * 1.15f - u * w * 1.3f
    val dir = if (ltr) 1f else -1f
    val s = unit
    // Nothing pops in or out: each one fades in at the start of its visit and out at the end.
    val faded = out.fade
    out.fade = faded * minOf(1f, u / 0.05f, (1f - u) / 0.05f).coerceIn(0f, 1f)
    when (kind) {
        Sky.UFO -> {
            val wob = sin(now / 300.0).toFloat() * s * 0.01f
            val cx = x
            val cy = y + wob + sin(u * 6.0).toFloat() * s * 0.03f
            val r = s * 0.06f
            // A beam down, pulsing with the sound.
            if (sin(u * PI * 3) > 0.2) {
                out.polygon(Colors.alpha(0xFFB9FF8A.toInt(), 0.12f + 0.25f * live.level))
                out.point(cx - r * 0.3f, cy); out.point(cx + r * 0.3f, cy); out.point(cx + r * 1.2f, nearBase); out.point(cx - r * 1.2f, nearBase)
            }
            out.circle(Colors.alpha(0xFFBFE8FF.toInt(), 0.8f), cx, cy - r * 0.25f, r * 0.45f)
            out.polygon(0xFFA6ADB8.toInt())
            out.point(cx - r, cy); out.point(cx - r * 0.5f, cy - r * 0.25f); out.point(cx + r * 0.5f, cy - r * 0.25f); out.point(cx + r, cy); out.point(cx + r * 0.5f, cy + r * 0.2f); out.point(cx - r * 0.5f, cy + r * 0.2f)
            for (k in 0 until 4) {
                val on = ((now / 150) + k) % 4 == 0L
                out.circle(if (on) p.accent else 0xFFFFF3B0.toInt(), cx - r * 0.6f + k * r * 0.4f, cy + r * 0.03f, r * 0.08f)
            }
        }
        Sky.SHOOTING_STARS -> for (k in 0 until 3) {
            val start = k * 0.3f
            val v = (u - start) / 0.12f
            if (v !in 0f..1f) continue
            val sx = w * (0.15f + 0.7f * hash(e * 7 + k, 205))
            val sy = h * (0.05f + 0.2f * hash(e * 7 + k, 206))
            val hx = sx + dir * v * s * 0.4f
            val hy = sy + v * s * 0.2f
            val fade = sin(v * PI).toFloat()
            out.line(Colors.alpha(0xFFFFFFFF.toInt(), 0.6f * fade), s * 0.004f, hx - dir * s * 0.12f, hy - s * 0.06f, hx, hy)
            out.circle(Colors.alpha(0xFFFFFFFF.toInt(), fade), hx, hy, s * 0.006f)
        }
        Sky.COMET -> {
            val cx = x
            val cy = y + u * h * 0.05f
            for (k in 1..6) out.circle(Colors.alpha(p.accent, 0.18f - k * 0.025f), cx - dir * k * s * 0.03f, cy - k * s * 0.006f, s * (0.018f - k * 0.002f))
            out.circle(0xFFFFFFFF.toInt(), cx, cy, s * 0.012f)
        }
        Sky.ASTRONAUT -> {
            val cx = x
            val cy = y + sin(now / 900.0).toFloat() * s * 0.02f
            val a = (now / 3000.0).toFloat()
            out.turn(a, cx, cy)
            val r = s * 0.025f
            val suit = 0xFFF2F2F2.toInt()
            out.line(suit, r * 0.7f, cx - r * 0.5f, cy + r, cx - r * 0.7f, cy + r * 2.2f)
            out.line(suit, r * 0.7f, cx + r * 0.5f, cy + r, cx + r * 0.9f, cy + r * 2f)
            out.line(suit, r * 0.6f, cx - r, cy - r * 0.2f, cx - r * 1.8f, cy - r * 0.8f)
            out.line(suit, r * 0.6f, cx + r, cy - r * 0.2f, cx + r * 1.8f, cy + r * 0.3f)
            out.rect(suit, cx - r, cy - r, cx + r, cy + r * 1.2f)
            out.circle(suit, cx, cy - r * 1.5f, r * 0.9f)
            out.circle(Colors.mix(0xFF203050.toInt(), p.accent, 0.3f), cx + r * 0.2f, cy - r * 1.5f, r * 0.55f)
            out.straight()
        }
        Sky.BALLOON -> {
            val cx = w * (0.2f + 0.6f * hash(e, 207)) + sin(now / 2000.0).toFloat() * s * 0.03f
            // Rising from behind the hills.
            val cy = h * 0.8f - u * h * 0.78f
            val r = s * 0.05f
            val c = if (hash(e, 208) < 0.5f) p.accent else Colors.mix(0xFFE04848.toInt(), p.accent, 0.3f)
            out.circle(c, cx, cy, r)
            out.polygon(c); out.point(cx - r * 0.85f, cy + r * 0.45f); out.point(cx, cy + r * 1.35f); out.point(cx + r * 0.85f, cy + r * 0.45f)
            out.rect(Colors.lighter(c, 0.4f), cx - r * 0.15f, cy - r, cx + r * 0.15f, cy + r * 1.2f)
            out.line(0xFF5A4030.toInt(), r * 0.05f, cx - r * 0.3f, cy + r * 1.25f, cx - r * 0.25f, cy + r * 1.6f)
            out.line(0xFF5A4030.toInt(), r * 0.05f, cx + r * 0.3f, cy + r * 1.25f, cx + r * 0.25f, cy + r * 1.6f)
            out.rect(0xFF7A5030.toInt(), cx - r * 0.3f, cy + r * 1.6f, cx + r * 0.3f, cy + r * 1.95f)
        }
        Sky.BUTTERFLIES -> for (k in 0 until 3) {
            val bx = x + k * s * 0.05f
            val by = nearBase - nearAmp - h * 0.08f + sin(now / 400.0 + k * 2).toFloat() * h * 0.03f - k * h * 0.02f
            val flap = abs(cos((beat * 2f + k * 0.3f) * PI)).toFloat()
            val c = if (k % 2 == 0) p.accent else Colors.lighter(p.accent, 0.5f)
            val r = s * 0.012f
            out.circle(c, bx - r * flap, by, r)
            out.circle(c, bx + r * flap, by, r)
            out.line(0xFF222222.toInt(), r * 0.3f, bx, by - r * 0.6f, bx, by + r * 0.6f)
        }
        Sky.GEESE -> {
            val c = Colors.darker(p.far, 0.55f)
            val g = s * 0.014f
            for (k in 0 until 7) {
                val row = (k + 1) / 2
                val side = if (k % 2 == 0) 1f else -1f
                flockOne(x - dir * row * g * 3f, y + side * row * g * 2f, c, g, row)
            }
        }
        Sky.HELICOPTER -> {
            val cx = x
            val cy = y + sin(now / 700.0).toFloat() * s * 0.01f
            val r = s * 0.03f
            val c = Colors.darker(p.far, 0.5f)
            out.circle(c, cx, cy, r)
            out.line(c, r * 0.35f, cx - dir * r, cy - r * 0.1f, cx - dir * r * 2.6f, cy - r * 0.4f)
            val blade = r * 2.4f * abs(cos(now / 30.0)).toFloat()
            out.line(c, r * 0.12f, cx - blade, cy - r * 1.15f, cx + blade, cy - r * 1.15f)
            out.line(c, r * 0.15f, cx, cy - r, cx, cy - r * 1.15f)
            out.line(c, r * 0.12f, cx - r * 0.8f, cy + r * 1.2f, cx + r * 0.8f, cy + r * 1.2f)
            if ((now / 400) % 2 == 0L) out.circle(0xFFFF4040.toInt(), cx - dir * r * 2.6f, cy - r * 0.4f, r * 0.15f)
        }
        Sky.BLIMP -> {
            val cx = x
            val r = s * 0.05f
            val c = Colors.lighter(p.far, 0.5f)
            out.circle(c, cx - r, y, r * 0.9f)
            out.circle(c, cx + r, y, r * 0.9f)
            out.rect(c, cx - r, y - r * 0.9f, cx + r, y + r * 0.9f)
            out.polygon(Colors.darker(c, 0.2f)); out.point(cx - dir * r * 1.7f, y); out.point(cx - dir * r * 2.4f, y - r * 0.8f); out.point(cx - dir * r * 2.3f, y)
            out.rect(Colors.alpha(p.accent, 0.8f), cx - r * 0.9f, y - r * 0.25f, cx + r * 0.9f, y + r * 0.25f)
            out.rect(Colors.darker(c, 0.35f), cx - r * 0.3f, y + r * 0.85f, cx + r * 0.3f, y + r * 1.15f)
        }
        Sky.DRONES -> for (k in 0 until 5) {
            val dx = x + k * s * 0.06f * dir
            val dy = y + sin(now / 500.0 + k).toFloat() * s * 0.02f + k * s * 0.015f
            val on = ((now / 120) + k) % 3 != 0L
            val c = if (k % 2 == 0) p.accent else p.light
            out.circle(Colors.alpha(c, 0.25f * (0.5f + live.high)), dx, dy, s * 0.016f)
            if (on) out.circle(c, dx, dy, s * 0.006f)
        }
        Sky.FIREWORKS -> for (k in 0 until 3) {
            val start = k * 0.28f
            val v = (u - start) / 0.25f
            if (v !in 0f..1f) continue
            val fx = w * (0.15f + 0.7f * hash(e * 5 + k, 209))
            val fy = h * (0.12f + 0.2f * hash(e * 5 + k, 210))
            val c = if (k % 2 == 0) p.accent else p.light
            if (v < 0.3f) {
                // Rising.
                val ry = farBase - (farBase - fy) * (v / 0.3f)
                out.circle(Colors.alpha(p.light, 0.8f), fx, ry, s * 0.004f)
            } else {
                val b = (v - 0.3f) / 0.7f
                val rad = s * 0.1f * (0.4f + 0.6f * b) * (1f + 0.4f * live.level)
                val alpha = 1f - b
                for (m in 0 until 12) {
                    val a = m * PI / 6
                    val ex = fx + (cos(a) * rad).toFloat()
                    val ey = fy + (sin(a) * rad).toFloat() + b * b * s * 0.03f
                    out.line(Colors.alpha(c, alpha * 0.6f), s * 0.003f, fx + (ex - fx) * 0.6f, fy + (ey - fy) * 0.6f, ex, ey)
                    out.circle(Colors.alpha(c, alpha), ex, ey, s * 0.005f)
                }
            }
        }
        Sky.FLYING_CAR -> {
            val cx = x
            val cy = y + h * 0.1f + sin(now / 800.0).toFloat() * s * 0.01f
            val r = s * 0.03f
            out.line(Colors.alpha(p.accent, 0.35f), r * 0.4f, cx - dir * r * 6f, cy + r * 0.2f, cx - dir * r, cy + r * 0.2f)
            out.rect(Colors.mix(0xFF2A2840.toInt(), p.accent, 0.3f), cx - r * 1.2f, cy - r * 0.3f, cx + r * 1.2f, cy + r * 0.4f)
            out.rect(Colors.alpha(p.light, 0.8f), cx - r * 0.6f, cy - r * 0.75f, cx + r * 0.6f, cy - r * 0.3f)
            out.circle(p.light, cx + dir * r * 1.15f, cy, r * 0.15f)
        }
        Sky.BATS -> for (k in 0 until 6) {
            val bx = x + k * s * 0.035f * dir + sin(now / 200.0 + k * 1.7).toFloat() * s * 0.01f
            val by = y + sin(now / 330.0 + k * 2.3).toFloat() * s * 0.03f + (k % 3) * s * 0.02f
            val flap = cos((beat * 2f + k * 0.2f) * 2 * PI).toFloat()
            val r = s * 0.012f
            val c = 0xFF0E0B12.toInt()
            out.polygon(c)
            out.point(bx - r * 1.6f, by - r * flap); out.point(bx - r * 0.6f, by + r * 0.2f); out.point(bx, by - r * 0.3f); out.point(bx + r * 0.6f, by + r * 0.2f); out.point(bx + r * 1.6f, by - r * flap); out.point(bx, by + r * 0.5f)
        }
        Sky.DRAGON -> {
            val cx = x
            val cy = y + sin(now / 900.0).toFloat() * s * 0.02f
            val r = s * 0.05f
            val c = Colors.darker(p.far, 0.6f)
            val flap = sin(now / 260.0).toFloat()
            out.polyline(c, r * 0.25f)
            for (k in 0..6) out.point(cx - dir * (r * 0.8f + k * r * 0.35f), cy + sin(now / 300.0 + k * 0.8).toFloat() * r * 0.15f + k * r * 0.03f)
            out.polygon(c); out.point(cx - r * 0.8f, cy); out.point(cx, cy - r * 0.25f); out.point(cx + r * 0.8f, cy); out.point(cx, cy + r * 0.2f)
            out.polygon(c); out.point(cx + dir * r * 0.7f, cy - r * 0.1f); out.point(cx + dir * r * 1.2f, cy - r * 0.25f); out.point(cx + dir * r * 1.15f, cy + r * 0.05f)
            out.polygon(c); out.point(cx - r * 0.3f, cy - r * 0.1f); out.point(cx - dir * r * 0.4f, cy - r * (0.2f + 1.1f * flap)); out.point(cx + dir * r * 0.4f, cy - r * (0.1f + 0.9f * flap)); out.point(cx + r * 0.3f, cy - r * 0.1f)
            // A breath of fire on a strong beat.
            if (now - live.onsetAtMs < 400) {
                out.polygon(Colors.alpha(p.accent, 0.85f))
                out.point(cx + dir * r * 1.2f, cy - r * 0.1f); out.point(cx + dir * r * 2.4f, cy - r * 0.35f); out.point(cx + dir * r * 2.4f, cy + r * 0.25f)
            }
        }
        Sky.GHOST -> {
            val cx = x
            val cy = midBase - midAmp + sin(now / 700.0).toFloat() * s * 0.03f
            val r = s * 0.035f
            val c = Colors.alpha(0xFFEFF3FF.toInt(), 0.55f)
            out.circle(c, cx, cy, r)
            out.polygon(c)
            out.point(cx - r, cy); out.point(cx + r, cy)
            for (k in 0..4) out.point(cx + r - k * r * 0.5f, cy + r * 1.6f + (if (k % 2 == 0) 0f else -r * 0.3f) + sin(now / 200.0 + k).toFloat() * r * 0.1f)
            out.circle(0xFF101018.toInt(), cx - r * 0.35f, cy - r * 0.1f, r * 0.14f)
            out.circle(0xFF101018.toInt(), cx + r * 0.35f, cy - r * 0.1f, r * 0.14f)
        }
        Sky.SEAGULLS -> {
            val c = 0xFFF6F6F2.toInt()
            for (k in 0 until 3) flockOne(x + k * s * 0.07f * dir, y + sin(now / 800.0 + k).toFloat() * s * 0.03f + k * s * 0.02f, c, s * 0.02f, k)
        }
    }
    out.fade = faded
}

/** A burst over the neon city on every other strong beat. */
private fun Scene.beatFirework() {
    val age = now - live.onsetAtMs
    if (age !in 0..700 || moves.onsets % 2 != 0) return
    val b = age / 700f
    val k = moves.onsets
    val fx = w * (0.1f + 0.8f * hash(k, 211))
    val fy = h * (0.08f + 0.2f * hash(k, 212))
    val c = if (k % 4 == 0) p.accent else p.light
    val rad = unit * 0.07f * (0.3f + 0.7f * b)
    for (m in 0 until 10) {
        val a = m * PI / 5 + k
        out.circle(Colors.alpha(c, 1f - b), fx + (cos(a) * rad).toFloat(), fy + (sin(a) * rad).toFloat() + b * b * unit * 0.02f, unit * 0.005f)
    }
}

/** One bird of a flock: wings on the beat, a hop on strong beats. */
private fun Scene.flockOne(x: Float, y: Float, color: Int, s: Float, seed: Int = (x / 7f).toInt()) =
    bird(x, y - hop(seed) * s * 2.5f, color, s, seed)

/** Something running or hopping across the near ground now and then. */
internal fun Scene.groundVisitor(world: World) {
    val e = (now / GROUND_PERIOD).toInt()
    val u = (now % GROUND_PERIOD) / GROUND_PERIOD.toFloat()
    if (hash(e, 221) < 0.35f - 0.15f * look.energy) return
    val kinds = when (world) {
        World.MEADOW -> arrayOf(Runner.RABBIT, Runner.RABBIT, Runner.DEER, Runner.DOG)
        World.CITY -> arrayOf(Runner.DOG, Runner.DOG)
        World.NEON -> arrayOf(Runner.MOTORBIKE)
        World.CASTLE -> arrayOf(Runner.WOLF)
        World.COAST -> arrayOf(Runner.CRAB, Runner.DOG)
        World.SPACE -> arrayOf(Runner.ROVER)
        World.ICE -> arrayOf(Runner.SEAL)
    }
    val kind = kinds[(hash(e, 222) * kinds.size).toInt().coerceAtMost(kinds.size - 1)]
    val s = h * 0.035f
    // Overtaking the traveller from behind, or coming the other way.
    // Animals and riders go the traveller's way, overtaking it; the crab walks back.
    val ahead = kind != Runner.CRAB
    val x = if (ahead) -w * 0.1f + u * w * 1.25f else w * 1.1f - u * w * 1.25f
    val dir = if (ahead) 1f else -1f
    val vid = Scene.id(Scene.GROUND_VISITOR, e)
    touchable(vid, x, footNear(x, s * 0.5f) - s * 0.6f, s * 1.2f)
    // Touched: a high jump with a somersault (a seal dives and pops up again).
    val jump = bounce(poked(vid), 900)
    val foot = footNear(x, s * 0.5f) - (jump?.get(0) ?: 0f) * s * 2.2f
    if (jump != null && kind != Runner.SEAL) out.turn(dir * jump[1], x, foot - s * 0.5f)
    val c = Colors.darker(Colors.mix(p.near, p.mid, 0.3f), 0.55f)
    val stride = sin(now / 70.0).toFloat()
    when (kind) {
        Runner.RABBIT -> {
            if (out.fade >= 0.5f) {
                rabbitX = x
                rabbitRun = e
            }
            val hop = abs(sin(now / 160.0)).toFloat() * s * 0.8f
            val y = foot - hop
            val fur = Colors.mix(0xFFB9A58A.toInt(), c, 0.3f)
            out.circle(fur, x, y - s * 0.35f, s * 0.35f)
            out.circle(fur, x + dir * s * 0.35f, y - s * 0.65f, s * 0.2f)
            out.line(fur, s * 0.1f, x + dir * s * 0.3f, y - s * 0.8f, x + dir * s * 0.25f, y - s * 1.2f)
            out.line(fur, s * 0.1f, x + dir * s * 0.4f, y - s * 0.8f, x + dir * s * 0.45f, y - s * 1.15f)
            out.circle(0xFFFFFFFF.toInt(), x - dir * s * 0.35f, y - s * 0.4f, s * 0.12f)
        }
        Runner.DEER -> {
            val leap = abs(sin(now / 230.0)).toFloat() * s * 1.1f
            val y = foot - leap
            val fur = Colors.mix(0xFF9A6A40.toInt(), c, 0.3f)
            val legs = s * 0.9f
            for ((lx, sg) in listOf(-0.5f to 1f, 0.5f to -1f)) out.line(fur, s * 0.1f, x + lx * s, y - legs, x + lx * s + sg * stride * s * 0.3f, y)
            out.rect(fur, x - s * 0.7f, y - legs - s * 0.4f, x + s * 0.6f, y - legs)
            out.line(fur, s * 0.18f, x + dir * s * 0.5f, y - legs - s * 0.3f, x + dir * s * 0.75f, y - legs - s * 0.9f)
            out.circle(fur, x + dir * s * 0.85f, y - legs - s * 0.95f, s * 0.15f)
            out.line(fur, s * 0.05f, x + dir * s * 0.75f, y - legs - s * 1.05f, x + dir * s * 0.65f, y - legs - s * 1.4f)
        }
        // Some other dog (the four of the traveller's own never come from behind).
        Runner.DOG -> genericDog(x, foot, s, dir, stride, e)
        Runner.WOLF -> {
            val fur = Colors.mix(0xFF4A4A55.toInt(), c, 0.4f)
            val bob = abs(stride) * s * 0.08f
            val y = foot - s * 0.45f - bob
            for ((lx, sg) in listOf(-0.45f to 1f, -0.3f to -1f, 0.3f to -1f, 0.45f to 1f)) out.line(fur, s * 0.1f, x + lx * s, y, x + lx * s + sg * stride * s * 0.25f, foot)
            out.rect(fur, x - s * 0.55f, y - s * 0.25f, x + s * 0.5f, y + s * 0.05f)
            out.circle(fur, x + dir * s * 0.6f, y - s * 0.35f, s * 0.2f)
            out.polygon(fur); out.point(x + dir * s * 0.55f, y - s * 0.5f); out.point(x + dir * s * 0.6f, y - s * 0.75f); out.point(x + dir * s * 0.7f, y - s * 0.5f)
            out.line(fur, s * 0.08f, x - dir * s * 0.55f, y - s * 0.15f, x - dir * s * 0.8f, y - s * (0.4f + 0.15f * stride))
            if (kind == Runner.WOLF && look.night > 0.5f) out.circle(Colors.alpha(p.accent, 0.9f), x + dir * s * 0.66f, y - s * 0.38f, s * 0.04f)
        }
        Runner.CRAB -> {
            val y = foot - s * 0.25f + abs(stride) * s * 0.03f
            val red = Colors.mix(0xFFE0503A.toInt(), p.accent, 0.2f)
            for (k in -2..2) out.line(red, s * 0.06f, x + k * s * 0.12f, y, x + k * s * 0.2f + stride * s * 0.06f, foot)
            out.circle(red, x, y, s * 0.3f)
            out.circle(red, x - s * 0.4f, y - s * 0.3f, s * 0.12f + abs(stride) * s * 0.03f)
            out.circle(red, x + s * 0.4f, y - s * 0.3f, s * 0.12f + abs(stride) * s * 0.03f)
        }
        Runner.MOTORBIKE -> {
            val r = s * 0.3f
            val y = foot - r
            out.line(Colors.alpha(p.accent, 0.5f), r * 0.5f, x - dir * s * 3f, y - r * 0.2f, x - dir * r, y - r * 0.2f)
            out.ring(c, x - r * 1.3f, y, r, r * 0.2f)
            out.ring(c, x + r * 1.3f, y, r, r * 0.2f)
            out.polygon(Colors.mix(c, p.accent, 0.4f)); out.point(x - r * 1.3f, y); out.point(x - r * 0.3f, y - r * 1.1f); out.point(x + r * 1.1f, y - r * 1.1f); out.point(x + r * 1.3f, y)
            out.line(c, r * 0.35f, x, y - r * 1.1f, x + dir * r * 0.4f, y - r * 2.3f)
            out.circle(c, x + dir * r * 0.5f, y - r * 2.6f, r * 0.35f)
            out.circle(p.light, x + dir * r * 1.5f, y - r * 0.8f, r * 0.15f)
        }
        Runner.ROVER -> {
            val r = s * 0.18f
            val y = foot - r
            for (k in -1..1) out.circle(0xFF303038.toInt(), x + k * r * 2.2f, y, r)
            out.rect(0xFFD8D8DE.toInt(), x - r * 3f, y - r * 2.2f, x + r * 3f, y - r * 0.8f)
            out.line(0xFFD8D8DE.toInt(), r * 0.25f, x + r * 2f, y - r * 2.2f, x + r * 2f, y - r * 4f)
            out.circle(p.accent, x + r * 2f, y - r * 4f, r * 0.4f * (0.8f + 0.4f * live.high))
            out.rect(Colors.mix(0xFF3050A0.toInt(), p.accent, 0.2f), x - r * 2.6f, y - r * 2.8f, x - r * 0.4f, y - r * 2.2f)
        }
        Runner.SEAL -> {
            val water = seaY(x)
            val up = sin(u * PI * 4).toFloat()
            if (up <= 0f) return
            val y = water - up * s * 0.6f
            val fur = 0xFF5A6470.toInt()
            out.circle(fur, x, y, s * 0.3f)
            out.circle(fur, x + dir * s * 0.25f, y - s * 0.25f, s * 0.2f)
            out.circle(0xFF101010.toInt(), x + dir * s * 0.35f, y - s * 0.3f, s * 0.04f)
            out.rect(Colors.alpha(p.near, 0.9f), x - s * 0.5f, water, x + s * 0.5f, water + s * 0.3f)
        }
    }
    out.straight()
}

// ---------- Things that stand in each land (more where the music is louder) ----------

internal fun Scene.cityThings() {
    slots(1f, h * 0.11f) { i, x0, x1 ->
        val cx = (x0 + x1) / 2f
        if (!dense(i, 231, 0.3f, cx, 1f)) return@slots
        val y = nearY(cx)
        when ((hash(i, 232) * 4).toInt()) {
            0 -> { // a tree on the pavement
                out.line(Colors.darker(p.near, 0.4f), h * 0.006f, cx, y, cx, y - h * 0.05f)
                out.circle(Colors.mix(0xFF4F8A4A.toInt(), p.mid, 0.3f), cx, y - h * 0.065f, h * 0.025f)
            }
            1 -> { // a bench
                val c = Colors.darker(p.near, 0.45f)
                val y = footNear(cx, h * 0.02f)
                out.rect(c, cx - h * 0.025f, y - h * 0.018f, cx + h * 0.025f, y - h * 0.013f)
                out.rect(c, cx - h * 0.025f, y - h * 0.03f, cx + h * 0.025f, y - h * 0.026f)
                out.line(c, h * 0.003f, cx - h * 0.02f, y - h * 0.018f, cx - h * 0.02f, y)
                out.line(c, h * 0.003f, cx + h * 0.02f, y - h * 0.018f, cx + h * 0.02f, y)
            }
            else -> pigeons(cx, y, h * 0.012f, i)
        }
    }
}

/** Pigeons pecking on the ground; they fly up as the traveller comes near. */
private fun Scene.pigeons(cx: Float, ground: Float, s: Float, seed: Int) {
    val c = 0xFF8A8F9A.toInt()
    val near = cx - travellerX
    val flight = if (near < w * 0.12f) ((w * 0.12f - near) / (w * 0.25f)).coerceIn(0f, 1f) else 0f
    for (k in 0 until 3) {
        val bx = cx + k * s * 2.2f + flight * k * s * 4f
        val by = nearY(cx + k * s * 2.2f) - s * 0.5f - flight * h * 0.25f * (1f + k * 0.3f) - hop(seed + k) * s * 1.2f
        if (flight > 0f) {
            val flap = sin(now / 70.0 + k).toFloat()
            out.polyline(c, s * 0.3f)
            out.point(bx - s, by - s * flap); out.point(bx, by); out.point(bx + s, by - s * flap)
        } else {
            val peck = if (sin(now / 300.0 + seed + k * 1.9) > 0.6) s * 0.3f else 0f
            out.circle(c, bx, by, s * 0.5f)
            out.circle(c, bx + s * 0.5f, by - s * 0.45f + peck, s * 0.28f)
        }
    }
}

internal fun Scene.neonThings() {
    slots(1f, h * 0.16f) { i, x0, x1 ->
        val cx = (x0 + x1) / 2f
        if (!dense(i, 241, 0.25f, cx, 1f)) return@slots
        val y = nearY(cx)
        // A palm in neon lines.
        val c = if (hash(i, 242) < 0.5f) p.accent else p.light
        val glow = Colors.alpha(c, 0.5f + 0.5f * live.level)
        val s = h * 0.09f
        out.polyline(glow, h * 0.004f)
        out.point(cx, y); out.point(cx + s * 0.08f, y - s * 0.5f); out.point(cx + s * 0.18f, y - s)
        for (k in 0 until 5) {
            val a = -PI * (0.1 + k * 0.2)
            out.line(glow, h * 0.003f, cx + s * 0.18f, y - s, cx + s * 0.18f + (s * 0.4f * cos(a)).toFloat(), y - s + (s * 0.25f * sin(a)).toFloat() * -1f + s * 0.12f)
        }
    }
}

internal fun Scene.meadowThings() {
    slots(1f, h * 0.09f) { i, x0, x1 ->
        val cx = (x0 + x1) / 2f
        if (!dense(i, 251, 0.3f, cx, 1f)) return@slots
        val y = nearY(cx)
        when ((hash(i, 252) * 3).toInt()) {
            0 -> { // a bush
                val c = Colors.darker(p.near, 0.25f)
                out.circle(c, cx, y - h * 0.008f, h * 0.018f)
                out.circle(c, cx + h * 0.018f, y - h * 0.004f, h * 0.014f)
                if (hash(i, 253) < 0.5f) out.circle(Colors.mix(0xFFD03050.toInt(), p.accent, 0.3f), cx + h * 0.005f, y - h * 0.018f, h * 0.004f)
            }
            1 -> { // a stone
                out.circle(Colors.mix(0xFF9A9A92.toInt(), p.near, 0.3f), cx, y, h * 0.012f)
            }
            else -> { // a bit of fence
                val c = Colors.mix(0xFF8A6A48.toInt(), p.near, 0.3f)
                for (k in 0..2) out.line(c, h * 0.004f, cx + k * h * 0.02f, nearY(cx + k * h * 0.02f) + h * 0.004f, cx + k * h * 0.02f, nearY(cx + k * h * 0.02f) - h * 0.028f)
                out.line(c, h * 0.003f, cx, nearY(cx) - h * 0.02f, cx + h * 0.04f, nearY(cx + h * 0.04f) - h * 0.02f)
            }
        }
    }
}

internal fun Scene.beachThings() {
    slots(1f, h * 0.12f) { i, x0, x1 ->
        val cx = (x0 + x1) / 2f
        if (!dense(i, 261, 0.22f, cx, 1f)) return@slots
        val y = nearY(cx)
        if (hash(i, 262) < 0.6f) {
            // A sunshade with a towel.
            val c = if (hash(i, 263) < 0.5f) p.accent else Colors.mix(0xFF3A7BD5.toInt(), p.accent, 0.2f)
            out.line(0xFF6A5A4A.toInt(), h * 0.003f, cx, y + h * 0.004f, cx - h * 0.006f, y - h * 0.06f)
            out.polygon(c); out.point(cx - h * 0.04f, y - h * 0.05f); out.point(cx - h * 0.006f, y - h * 0.075f); out.point(cx + h * 0.03f, y - h * 0.05f)
            out.rect(Colors.lighter(c, 0.3f), cx + h * 0.005f, y - h * 0.003f, cx + h * 0.04f, y + h * 0.003f)
        } else {
            // A beach ball, bouncing with the beat.
            val b = abs(sin(now / 300.0 + i)).toFloat() * h * 0.015f * (0.5f + live.low)
            val r = h * 0.01f
            out.circle(0xFFFFFFFF.toInt(), cx, y - r - b, r)
            out.circle(p.accent, cx - r * 0.35f, y - r - b, r * 0.5f)
        }
    }
}

internal fun Scene.spaceThings() {
    slots(1f, h * 0.13f) { i, x0, x1 ->
        val cx = (x0 + x1) / 2f
        if (!dense(i, 271, 0.28f, cx, 1f)) return@slots
        val y = nearY(cx)
        when ((hash(i, 272) * 3).toInt()) {
            0 -> martian(cx, y, h * 0.045f, i)
            1 -> { // crystals glowing with the sound
                val c = Colors.mix(0xFF8AF0FF.toInt(), p.accent, 0.4f)
                for (k in -1..1) {
                    val tall = h * (0.025f + 0.015f * hash(i + k, 273))
                    out.polygon(Colors.alpha(c, 0.6f + 0.4f * live.high))
                    val gy = nearY(cx + k * h * 0.012f)
                    out.point(cx + k * h * 0.012f - h * 0.006f, gy + h * 0.004f); out.point(cx + k * h * 0.014f, gy - tall * (1f + 0.3f * kick)); out.point(cx + k * h * 0.012f + h * 0.006f, gy + h * 0.004f)
                }
            }
            else -> { // a flag
                out.line(0xFFCCCCCC.toInt(), h * 0.003f, cx, y, cx, y - h * 0.06f)
                val wave = sin(now / 250.0).toFloat() * h * 0.004f
                out.polygon(p.accent); out.point(cx, y - h * 0.06f); out.point(cx + h * 0.035f, y - h * 0.055f + wave); out.point(cx, y - h * 0.042f)
            }
        }
    }
}

/** A little green one waving at the traveller, its antenna blinking with the sound. */
private fun Scene.martian(x: Float, groundY: Float, s: Float, seed: Int) {
    val green = 0xFF7BD86A.toInt()
    val ground = groundY - hop(seed) * s * 0.6f
    out.line(green, s * 0.15f, x - s * 0.12f, ground, x - s * 0.1f, ground - s * 0.4f)
    out.line(green, s * 0.15f, x + s * 0.12f, ground, x + s * 0.1f, ground - s * 0.4f)
    out.rect(green, x - s * 0.2f, ground - s * 0.8f, x + s * 0.2f, ground - s * 0.35f)
    out.circle(green, x, ground - s * 1.0f, s * 0.28f)
    out.circle(0xFF101010.toInt(), x - s * 0.1f, ground - s * 1.02f, s * 0.07f)
    out.circle(0xFF101010.toInt(), x + s * 0.1f, ground - s * 1.02f, s * 0.07f)
    out.line(green, s * 0.06f, x, ground - s * 1.25f, x + s * 0.08f, ground - s * 1.5f)
    out.circle(Colors.alpha(p.accent, 0.5f + 0.5f * live.high), x + s * 0.08f, ground - s * 1.52f, s * 0.07f)
    val wave = sin(beat * 2 * PI + seed).toFloat()
    out.line(green, s * 0.1f, x + s * 0.2f, ground - s * 0.7f, x + s * (0.42f + 0.1f * wave), ground - s * 1.05f)
    out.line(green, s * 0.1f, x - s * 0.2f, ground - s * 0.7f, x - s * 0.35f, ground - s * 0.45f)
}

// ---------- The track's record ----------

/** Music time between two of the track's records along the near land (farther layers: as often on screen). */
private const val ALBUM_EVERY_MS = 20_000.0

/**
 * The record: its sleeve (the cover) with the black disc slid [out] of it (0 … 1) to the side
 * [angle] points to.
 */
private fun Scene.album(cx: Float, cy: Float, side: Float, angle: Float, slidBefore: Float, rid: Int = 0) {
    // Touched: the record slides right out, spins, and goes back in.
    if (rid != 0) touchable(rid, cx, cy, side * 0.6f)
    val age = if (rid != 0) poked(rid) else -1
    val out3 = if (age in 0..3000) sin(PI * age / 3000.0).toFloat().coerceAtMost(0.75f) / 0.75f else 0f
    val slid = slidBefore + (1.15f - slidBefore) * out3.coerceIn(0f, 1f)
    if (slid > 0f) {
        val d = side * 0.45f * slid
        val vx = cx + cos(angle) * d
        val vy = cy + sin(angle) * d
        val r = side * 0.48f
        out.circle(0xFF111114.toInt(), vx, vy, r)
        out.ring(0x33FFFFFF, vx, vy, r * 0.8f, side * 0.006f)
        out.ring(0x22FFFFFF, vx, vy, r * 0.62f, side * 0.006f)
        out.circle(p.accent, vx, vy, r * 0.3f)
        if (out3 > 0f) {
            // Spinning: a glint going round the grooves.
            val a = age / 120.0
            out.circle(0x55FFFFFF, vx + (cos(a) * r * 0.7f).toFloat(), vy + (sin(a) * r * 0.7f).toFloat(), r * 0.07f)
        }
        out.circle(0xFF111114.toInt(), vx, vy, r * 0.05f)
    }
    out.cover(p.accent, cx, cy, side, angle)
    sleeveLabel(cx, cy, side, angle)
}

/** The record's title and artist on a band across the foot of its sleeve, turned with it. */
private fun Scene.sleeveLabel(cx: Float, cy: Float, side: Float, angle: Float) {
    val title = lane.title?.takeIf { it.isNotBlank() } ?: return
    val artist = lane.artist?.takeIf { it.isNotBlank() }
    val line = side * 0.12f
    // About as many letters as fit across the sleeve.
    val fits = (side / (line * 0.58f)).toInt().coerceAtLeast(4)
    fun cut(t: String) = if (t.length > fits) t.take(fits - 1).trimEnd() + "…" else t
    val half = side / 2f
    val band = if (artist != null) line * 2.5f else line * 1.5f
    out.turn(angle, cx, cy)
    out.rect(Colors.alpha(0xFF101014.toInt(), 0.62f), cx - half, cy + half - band, cx + half, cy + half)
    out.straight()
    // The lines' centres, turned with the sleeve; each text turned the same.
    fun at(dy: Float, value: String, height: Float, color: Int) {
        val c = cos(angle)
        val s = sin(angle)
        out.text(color, cx - dy * s, cy + dy * c, height, value, angle)
    }
    if (artist != null) {
        at(half - band + line * 0.75f, cut(title), line, 0xFFFFFFFF.toInt())
        at(half - line * 0.65f, cut(artist), line * 0.85f, 0xFFE6E6E6.toInt())
    } else {
        at(half - band / 2f, cut(title), line, 0xFFFFFFFF.toInt())
    }
}

/** Records adrift on the water, bobbing on the swell. */
internal fun Scene.floatingAlbums(waterY: Float, zoom: Float) {
    slotsMs(zoom, ALBUM_EVERY_MS * zoom) { i, x0, x1 ->
        if (hash(i, 281) > 0.85f) return@slotsMs
        val cx = x0 + (x1 - x0) * (0.2f + 0.6f * hash(i, 282))
        val side = h * 0.075f
        val bob = sin(now / 900.0 + i).toFloat()
        val y = waterY + h * 0.02f + bob * h * 0.004f
        val tilt = 0.12f * bob - 0.05f
        album(cx, y - side * 0.3f, side, tilt, 0.5f, Scene.id(Scene.RECORD, i * 8 + 1))
        // The water in front of its lower part.
        out.rect(Colors.alpha(if (lane.look.world == World.ICE) Colors.mix(p.near, p.skyBottom, 0.25f) else p.mid, 0.9f), cx - side * 1.1f, y, cx + side * 1.1f, y + side * 0.35f)
    }
}

/** A child on the hill flying the record as a kite. */
internal fun Scene.kites() {
    slotsMs(Scene.ZOOM_MID, ALBUM_EVERY_MS * Scene.ZOOM_MID) { i, x0, x1 ->
        if (hash(i, 283) > 0.85f) return@slotsMs
        val kx = x0 + (x1 - x0) * (0.2f + 0.6f * hash(i, 284))
        val ground = midY(kx)
        val s = h * 0.03f
        val c = Colors.darker(p.mid, 0.6f)
        out.line(c, s * 0.15f, kx, ground, kx, ground - s * 0.9f)
        out.circle(c, kx, ground - s * 1.15f, s * 0.22f)
        val handX = kx + s * 0.3f
        val handY = ground - s * 1.2f
        out.line(c, s * 0.12f, kx, ground - s * 0.8f, handX, handY)
        val sway = sin(now / 1300.0 + i).toFloat()
        val tx = kx + h * 0.12f + sway * h * 0.02f
        val ty = ground - h * (0.3f + 0.05f * live.level) + cos(now / 1700.0 + i).toFloat() * h * 0.02f
        out.polyline(Colors.alpha(0xFFFFFFFF.toInt(), 0.6f), h * 0.0015f)
        out.point(handX, handY); out.point((handX + tx) / 2f + h * 0.01f, (handY + ty) / 2f + h * 0.03f); out.point(tx, ty)
        val side = h * 0.075f
        // The tail with its bows.
        out.polyline(Colors.alpha(p.accent, 0.8f), h * 0.002f)
        for (k in 0..6) out.point(tx - side * 0.1f - k * side * 0.12f, ty + side * 0.7f + k * side * 0.18f + sin(now / 250.0 + k).toFloat() * side * 0.1f)
        out.cover(p.accent, tx, ty, side, (PI / 4).toFloat() + sway * 0.15f)
    }
}

/** Records half buried in the moon's dust, drawn before the ground covers their lower half. */
internal fun Scene.buriedAlbums() {
    slotsMs(1f, ALBUM_EVERY_MS) { i, x0, x1 ->
        if (hash(i, 285) > 0.85f) return@slotsMs
        val cx = x0 + (x1 - x0) * (0.2f + 0.6f * hash(i, 286))
        val side = h * 0.13f
        val tilt = (hash(i, 287) - 0.5f) * 0.7f
        album(cx, nearY(cx) + side * 0.12f, side, tilt - 0.4f, 0.6f, Scene.id(Scene.RECORD, i * 8 + 2))
    }
}

/** A billboard by the road showing the cover; in the night city a neon frame round it. */
internal fun Scene.billboards(neon: Boolean) {
    slotsMs(1f, ALBUM_EVERY_MS) { i, x0, x1 ->
        if (hash(i, 288) > 0.85f) return@slotsMs
        val cx = x0 + (x1 - x0) * (0.2f + 0.6f * hash(i, 289))
        val side = h * 0.13f
        val ground = footNear(cx, side * 0.3f)
        val top = ground - h * 0.08f - side
        val pole = Colors.darker(p.mid, 0.4f)
        out.line(pole, h * 0.005f, cx - side * 0.3f, ground, cx - side * 0.3f, top + side)
        out.line(pole, h * 0.005f, cx + side * 0.3f, ground, cx + side * 0.3f, top + side)
        if (neon) out.rect(Colors.alpha(p.accent, 0.25f + 0.5f * live.level), cx - side * 0.62f, top - side * 0.12f, cx + side * 0.62f, top + side * 1.12f)
        out.rect(pole, cx - side * 0.55f, top - side * 0.05f, cx + side * 0.55f, top + side * 1.05f)
        album(cx, top + side * 0.5f, side, 0f, 0f, Scene.id(Scene.RECORD, i * 8 + 3))
    }
}

/** A banner with the cover hanging from a pole on the hills. */
internal fun Scene.banners() {
    slotsMs(Scene.ZOOM_MID, ALBUM_EVERY_MS * Scene.ZOOM_MID) { i, x0, x1 ->
        if (hash(i, 290) > 0.85f) return@slotsMs
        val cx = x0 + (x1 - x0) * (0.2f + 0.6f * hash(i, 291))
        val ground = midY(cx)
        val side = h * 0.08f
        val pole = Colors.darker(p.mid, 0.5f)
        out.line(pole, h * 0.004f, cx, ground, cx, ground - side * 2.2f)
        out.line(pole, h * 0.003f, cx - side * 0.1f, ground - side * 2.1f, cx + side * 1.1f, ground - side * 2.1f)
        val swing = sin(now / 1100.0 + i).toFloat() * 0.06f
        out.cover(p.accent, cx + side * 0.55f, ground - side * 1.55f, side, swing)
        sleeveLabel(cx + side * 0.55f, ground - side * 1.55f, side, swing)
        // Torn edge below.
        out.polygon(p.accent)
        out.point(cx + side * 0.05f, ground - side * 1.05f); out.point(cx + side * 1.05f, ground - side * 1.05f); out.point(cx + side * 0.55f, ground - side * 0.8f)
    }
}
