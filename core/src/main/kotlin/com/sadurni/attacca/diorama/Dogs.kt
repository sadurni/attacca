package com.sadurni.attacca.diorama

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// The dogs of O'rama: four of them, always the same ones, after the author's own.

/** The four dogs. */
internal enum class Dog {
    /** Small and golden, a golden retriever the size of a cocker, with a pink nose. */
    GOLDIE,

    /** As big as a shepherd, dark on the back and white below, like a malamute, its tail curled over it. */
    MALAMUTE,

    /** Tall and slender, a big greyhound with something of a podenco: fawn, white chest, big ears, pink nose, a docked tail. */
    GALGO,

    /** Small, a pointer: white with black patches and a black head. */
    BRACO,
}

/** Its shape, in sizes of the traveller ([s]). */
private class DogShape(
    val height: Float, val length: Float, val thick: Float, val neck: Float, val head: Float, val snout: Float,
    val coat: Int, val light: Int, val nose: Int,
)

private val SHAPES = mapOf(
    Dog.GOLDIE to DogShape(0.24f, 0.44f, 0.21f, 0.09f, 0.11f, 0.075f, 0xFFD6A050.toInt(), 0xFFF0CD8C.toInt(), 0xFFE58F98.toInt()),
    Dog.MALAMUTE to DogShape(0.36f, 0.6f, 0.25f, 0.14f, 0.12f, 0.1f, 0xFF2F2D31.toInt(), 0xFFE9DCC4.toInt(), 0xFF1A1818.toInt()),
    Dog.GALGO to DogShape(0.44f, 0.62f, 0.21f, 0.18f, 0.08f, 0.14f, 0xFFC98E52.toInt(), 0xFFF3E8DA.toInt(), 0xFFE0939A.toInt()),
    Dog.BRACO to DogShape(0.24f, 0.42f, 0.16f, 0.11f, 0.09f, 0.09f, 0xFFF4F2EE.toInt(), 0xFF1E1E22.toInt(), 0xFF2A2224.toInt()),
)

/**
 * One of the dogs at [x] with its paws on [foot], facing [dir] (1 the traveller's way), [s] the
 * traveller's size; [phase] 0 … 1 is one stride (a gallop when [run], a trot otherwise).
 */
internal fun Scene.dog(dog: Dog, x: Float, foot: Float, s: Float, dir: Float, phase: Float, run: Boolean) {
    val k = SHAPES.getValue(dog)
    val h = k.height * s
    val len = k.length * s
    val t = k.thick * s
    val swing = sin(phase * 2 * PI).toFloat()
    // Running: the back rises and falls; the body stretches out.
    val bob = if (run) abs(cos(phase * 2 * PI)).toFloat() * h * 0.08f else abs(swing) * h * 0.03f
    val yc = foot - h - bob
    val darkLegs = when (dog) {
        Dog.GOLDIE -> k.coat
        Dog.MALAMUTE -> k.light
        Dog.GALGO, Dog.BRACO -> k.coat
    }
    val far = Colors.darker(darkLegs, 0.18f)
    val legW = t * (if (dog == Dog.GALGO) 0.26f else 0.32f)
    // Legs: front ones bend back at the elbow, hind ones forward at the hock.
    fun leg(front: Boolean, near: Boolean, offset: Float) {
        val lx = x + dir * len * (if (front) 0.36f else -0.36f)
        val f = (phase + offset) * 2 * PI
        val reach = (if (run) 0.45f else 0.25f) * h
        val px = lx + dir * sin(f).toFloat() * reach
        val lift = (cos(f).toFloat().coerceAtLeast(0f)) * h * (if (run) 0.25f else 0.12f)
        val py = foot - lift
        val topY = yc + t * 0.2f
        val mx = (lx + px) / 2f + dir * (if (front) -1f else 1f) * h * 0.1f
        val my = (topY + py) / 2f
        val c = if (near) darkLegs else far
        out.polyline(c, legW)
        out.point(lx, topY); out.point(mx, my); out.point(px, py)
        // The podenco's legs are its coat's colour, its feet white.
        val paw = if (dog == Dog.GALGO) (if (near) k.light else Colors.darker(k.light, 0.15f)) else c
        if (dog == Dog.GALGO) out.line(paw, legW * 1.1f, px + (mx - px) * 0.18f, py + (my - py) * 0.18f, px, py)
        out.line(paw, legW * 1.05f, px, py, px + dir * legW * 0.9f, py)
    }
    leg(front = true, near = false, offset = if (run) 0.12f else 0.5f)
    leg(front = false, near = false, offset = if (run) 0.62f else 0f)
    // The tail, behind the body.
    val tailX = x - dir * len * 0.48f
    val tailY = yc - t * 0.25f
    val wag = sin(now / 110.0).toFloat()
    when (dog) {
        Dog.GOLDIE -> {
            // A feathered fan rising up and back from the rump.
            val wg = wag * t * 0.15f
            out.polygon(k.light)
            out.point(tailX + dir * t * 0.05f, tailY - t * 0.25f)
            out.point(tailX - dir * t * 0.2f, tailY - t * 1.15f - wg)
            out.point(tailX - dir * t * 0.65f, tailY - t * 1.3f - wg)
            out.point(tailX - dir * t * 1.05f, tailY - t * 0.75f - wg * 0.5f)
            out.point(tailX - dir * t * 0.2f, tailY + t * 0.1f)
            out.polygon(k.coat)
            out.point(tailX, tailY - t * 0.2f)
            out.point(tailX - dir * t * 0.25f, tailY - t * 0.95f - wg)
            out.point(tailX - dir * t * 0.6f, tailY - t * 1.05f - wg)
            out.point(tailX - dir * t * 0.8f, tailY - t * 0.65f - wg * 0.5f)
            out.point(tailX - dir * t * 0.15f, tailY + t * 0.05f)
        }
        Dog.MALAMUTE -> {
            // A bushy plume: up from the rump and forward over the back like a hook, its tip held up
            // short of the back; dark with a lighter underside, thickest in the middle.
            val pts = 11
            val cx = tailX + dir * t * 0.35f
            val cy = tailY - t * 0.45f + wag * t * 0.04f
            val r = t * 0.55f
            val sx = FloatArray(pts)
            val sy = FloatArray(pts)
            val wd = FloatArray(pts)
            for (m in 0 until pts) {
                val u = m / (pts - 1f)
                val th = (PI + 0.9) - (PI + 0.6) * u
                sx[m] = cx + dir * (cos(th) * r).toFloat()
                sy[m] = cy - (sin(th) * r).toFloat()
                wd[m] = t * (0.2f + 0.3f * sin(PI * (0.1 + 0.75 * u)).toFloat())
            }
            fun plume(color: Int, scale: Float) {
                out.polygon(color)
                // Outer edge then inner edge, each point pushed away from or towards the centre.
                for (m in 0 until pts) {
                    val dx = sx[m] - cx
                    val dy = sy[m] - cy
                    val d = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
                    out.point(sx[m] + dx / d * wd[m] * scale, sy[m] + dy / d * wd[m] * scale)
                }
                for (m in pts - 1 downTo 0) {
                    val dx = sx[m] - cx
                    val dy = sy[m] - cy
                    val d = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
                    out.point(sx[m] - dx / d * wd[m] * scale * 0.7f, sy[m] - dy / d * wd[m] * scale * 0.7f)
                }
            }
            plume(Colors.mix(k.coat, k.light, 0.6f), 0.62f)
            plume(k.coat, 0.5f)
        }
        Dog.GALGO -> {
            // Docked: a short stump (it came like that).
            out.line(k.coat, t * 0.22f, tailX, tailY, tailX - dir * t * 0.35f, tailY - t * (0.08f + 0.04f * wag))
        }
        Dog.BRACO -> {
            // Out behind, then rolled up in a curl.
            val cx = tailX - dir * t * 0.75f
            val cy = tailY - t * (0.4f + 0.05f * wag)
            out.polyline(k.coat, t * 0.16f)
            out.point(tailX, tailY)
            for (m in 0..16) {
                val u = m / 16f
                val th = -PI / 2 - 1.9 * PI * u
                val r = t * (0.36f - 0.22f * u)
                out.point(cx + dir * (cos(th) * r).toFloat(), cy - (sin(th) * r).toFloat())
            }
        }
    }
    // The body: a rounded trunk; the greyhound with a deep chest and a tucked-in belly.
    val bx0 = x - dir * len * 0.5f
    val bx1 = x + dir * len * 0.5f
    if (dog == Dog.GALGO) {
        out.polygon(k.coat)
        out.point(bx0, yc - t * 0.45f); out.point(bx1, yc - t * 0.55f); out.point(bx1 + dir * t * 0.25f, yc + t * 0.15f)
        out.point(x + dir * len * 0.25f, yc + t * 0.85f); out.point(x - dir * len * 0.05f, yc + t * 0.55f); out.point(bx0, yc + t * 0.15f)
        out.circle(k.coat, bx0, yc - t * 0.15f, t * 0.32f)
        // White chest.
        out.polygon(k.light)
        out.point(bx1 + dir * t * 0.25f, yc + t * 0.1f); out.point(x + dir * len * 0.25f, yc + t * 0.85f); out.point(x + dir * len * 0.3f, yc + t * 0.2f)
    } else {
        out.rect(k.coat, minOf(bx0, bx1), yc - t * 0.5f, maxOf(bx0, bx1), yc + t * 0.5f)
        out.circle(k.coat, bx0, yc, t * 0.5f)
        out.circle(k.coat, bx1, yc, t * 0.5f)
        when (dog) {
            Dog.MALAMUTE -> {
                // White below.
                out.polygon(k.light)
                out.point(bx0, yc + t * 0.1f); out.point(bx1 + dir * t * 0.45f, yc - t * 0.05f); out.point(bx1, yc + t * 0.5f); out.point(bx0, yc + t * 0.5f)
                out.circle(Colors.mix(k.coat, k.light, 0.35f), x, yc - t * 0.15f, t * 0.22f)
            }
            Dog.GOLDIE -> {
                // A fringe of longer, lighter hair under the belly and on the chest.
                for (m in 0..4) {
                    val fx = bx0 + (bx1 - bx0) * (0.15f + m * 0.18f)
                    out.polygon(k.light)
                    out.point(fx - dir * t * 0.15f, yc + t * 0.4f); out.point(fx, yc + t * 0.75f); out.point(fx + dir * t * 0.15f, yc + t * 0.4f)
                }
            }
            Dog.BRACO -> {
                // A black saddle along the back, patches and ticking on white.
                out.polygon(k.light)
                out.point(x - dir * len * 0.4f, yc - t * 0.5f); out.point(x + dir * len * 0.28f, yc - t * 0.5f)
                out.point(x + dir * len * 0.22f, yc - t * 0.02f); out.point(x - dir * len * 0.05f, yc + t * 0.08f); out.point(x - dir * len * 0.38f, yc - t * 0.05f)
                out.circle(k.light, x - dir * len * 0.15f, yc - t * 0.15f, t * 0.32f)
                out.circle(k.light, bx0, yc - t * 0.1f, t * 0.28f)
                for (m in 0 until 5) out.circle(k.light, x + dir * len * (0.1f + 0.07f * m), yc + t * (0.1f - 0.08f * (m % 2)), t * 0.05f)
            }
            Dog.GALGO -> {}
        }
    }
    leg(front = true, near = true, offset = 0f)
    leg(front = false, near = true, offset = if (run) 0.5f else 0.5f)
    // Neck and head.
    val nx = bx1
    val ny = yc - t * 0.3f
    val hx = x + dir * (len * 0.5f + k.neck * s * 0.55f)
    val hy = yc - t * 0.3f - k.neck * s * 0.75f + (if (run) swing * h * 0.03f else 0f)
    out.line(k.coat, t * (if (dog == Dog.GALGO) 0.5f else 0.7f), nx, ny, hx, hy)
    if (dog == Dog.MALAMUTE) out.line(k.light, t * 0.35f, nx + dir * t * 0.1f, ny + t * 0.15f, hx, hy + k.head * s * 0.6f)
    if (dog == Dog.BRACO) out.line(k.light, t * 0.6f, nx, ny, hx, hy)
    dogHead(dog, k, hx, hy, s, dir, phase)
}

/** A dog's head at [hx], [hy] facing [dir]: muzzle, nose, eye, markings and ears. */
private fun Scene.dogHead(dog: Dog, k: DogShape, hx: Float, hy: Float, s: Float, dir: Float, phase: Float) {
    val headColor = if (dog == Dog.BRACO) k.light else k.coat
    val hr = k.head * s
    out.circle(headColor, hx, hy, hr)
    // The muzzle, and the nose at its tip.
    val sl = k.snout * s
    val muzzle = when (dog) {
        Dog.MALAMUTE -> Colors.mix(k.coat, 0xFFB98A5E.toInt(), 0.35f)
        Dog.BRACO -> k.coat
        else -> headColor
    }
    out.polygon(muzzle)
    out.point(hx + dir * hr * 0.3f, hy - hr * 0.55f); out.point(hx + dir * (hr + sl), hy - hr * 0.05f); out.point(hx + dir * (hr + sl * 0.95f), hy + hr * 0.35f); out.point(hx, hy + hr * 0.75f)
    out.circle(k.nose, hx + dir * (hr + sl * 0.98f), hy + hr * 0.05f, hr * 0.22f)
    when (dog) {
        Dog.MALAMUTE -> {
            // Tan cheeks and brows under the dark cap.
            val tan = 0xFFB98A5E.toInt()
            out.circle(tan, hx + dir * hr * 0.15f, hy + hr * 0.35f, hr * 0.55f)
            out.circle(tan, hx + dir * hr * 0.45f, hy - hr * 0.35f, hr * 0.16f)
        }
        Dog.GALGO -> {
            // The white blaze up the face.
            out.line(k.light, hr * 0.28f, hx + dir * hr * 0.2f, hy - hr * 0.6f, hx + dir * (hr + sl * 0.6f), hy - hr * 0.15f)
        }
        Dog.BRACO -> out.line(k.coat, hr * 0.3f, hx + dir * hr * 0.1f, hy - hr * 0.7f, hx + dir * (hr + sl * 0.5f), hy - hr * 0.15f)
        Dog.GOLDIE -> {}
    }
    out.circle(0xFF15120F.toInt(), hx + dir * hr * 0.35f, hy - hr * 0.2f, hr * 0.12f)
    // Hanging ears over the side of the head, swinging a little.
    val flop = sin(phase * 4 * PI).toFloat() * hr * 0.12f
    when (dog) {
        Dog.GOLDIE -> {
            // Long and feathered.
            out.polygon(Colors.darker(k.coat, 0.12f))
            out.point(hx - dir * hr * 0.05f, hy - hr * 0.75f); out.point(hx - dir * hr * 0.75f, hy - hr * 0.35f)
            out.point(hx - dir * (hr * 0.85f + flop), hy + hr * 0.9f); out.point(hx - dir * (hr * 0.45f + flop), hy + hr * 1.35f); out.point(hx - dir * hr * 0.1f, hy + hr * 0.6f)
        }
        Dog.BRACO -> {
            out.polygon(k.light)
            out.point(hx - dir * hr * 0.05f, hy - hr * 0.7f); out.point(hx - dir * hr * 0.7f, hy - hr * 0.3f)
            out.point(hx - dir * (hr * 0.65f + flop), hy + hr * 0.95f); out.point(hx - dir * hr * 0.15f, hy + hr * 0.7f)
        }
        else -> {}
    }
    // Standing ears.
    if (dog == Dog.MALAMUTE || dog == Dog.GALGO) {
        val big = if (dog == Dog.GALGO) 1.35f else 0.95f
        out.polygon(k.coat)
        out.point(hx - dir * hr * 0.55f, hy - hr * 0.5f); out.point(hx - dir * hr * (0.45f + 0.15f * big), hy - hr * (1.1f + 0.8f * big)); out.point(hx + dir * hr * 0.15f, hy - hr * 0.7f)
        if (dog == Dog.GALGO) {
            out.polygon(k.light)
            out.point(hx - dir * hr * 0.4f, hy - hr * 0.6f); out.point(hx - dir * hr * 0.5f, hy - hr * 1.8f); out.point(hx, hy - hr * 0.75f)
        }
    }
}

/** Which dog goes by for a number ([seed]). */
internal fun dogOf(seed: Int): Dog = Dog.entries[(Diorama.hash(seed, 731) * Dog.entries.size).toInt().coerceAtMost(Dog.entries.size - 1)]

/** Some dog that is not one of the four: a plain one in one of a few coats, [s] small. */
internal fun Scene.genericDog(x: Float, foot: Float, s: Float, dir: Float, stride: Float, seed: Int) {
    val coats = intArrayOf(0xFFC08A50.toInt(), 0xFF5A4A40.toInt(), 0xFF8A8A92.toInt(), 0xFFE8E0D0.toInt(), 0xFF2A2626.toInt())
    val fur = Colors.mix(coats[(Diorama.hash(seed, 732) * coats.size).toInt().coerceAtMost(coats.size - 1)], p.near, 0.15f)
    val bob = abs(stride) * s * 0.08f
    val y = foot - s * 0.45f - bob
    for ((lx, sg) in listOf(-0.45f to 1f, -0.3f to -1f, 0.3f to -1f, 0.45f to 1f)) out.line(fur, s * 0.1f, x + lx * s, y, x + lx * s + sg * stride * s * 0.25f, foot)
    out.rect(fur, x - s * 0.55f, y - s * 0.25f, x + s * 0.5f, y + s * 0.05f)
    out.circle(fur, x + dir * s * 0.6f, y - s * 0.35f, s * 0.2f)
    out.polygon(fur); out.point(x + dir * s * 0.55f, y - s * 0.5f); out.point(x + dir * s * 0.6f, y - s * 0.75f); out.point(x + dir * s * 0.7f, y - s * 0.5f)
    out.line(fur, s * 0.08f, x - dir * s * 0.55f, y - s * 0.15f, x - dir * s * 0.8f, y - s * (0.4f + 0.15f * stride))
}

/** What each of the four is doing. */
internal object DogState {
    const val AWAY = 0
    const val COMING = 1
    const val WITH = 2
    const val LEAVING = 3
    const val CHASING = 4
}

/** Worlds the dogs go on foot through. */
private val DOG_WORLDS = setOf(World.MEADOW, World.CITY, World.COAST, World.CASTLE, World.NEON)

/**
 * Whether dog [k] wants to be with the traveller at [t] on the land's own timeline (it runs on
 * from track to track): each its own comings and goings.
 */
private fun dogWants(k: Int, t: Double): Boolean {
    val period = 52_000.0 + 9_000.0 * k
    val shifted = t + Diorama.hash(k, 741) * period
    val n = kotlin.math.floor(shifted / period).toInt()
    val at = shifted - n * period
    return at < period * 0.72 && Diorama.hash(n * 4 + k, 742) < 0.75f
}

/**
 * The traveller's four dogs, each only once and the same from track to track. Each comes and goes
 * on its own: it is found waiting or sniffing ahead (it never catches up from behind) and joins the
 * traveller as it reaches it, keeps it company at its own place (the greyhound in front, the others
 * behind), and in time runs off ahead, to be found ahead again later. When a rabbit runs by, the
 * podenco always goes after it (now and then another one too), on and away ahead.
 */
internal fun Scene.ourDogs() {
    // Drawn by one land only while two fade into each other: the one more in sight.
    if (out.fade < 0.5f) return
    val t = lane.originMs + posMs
    if (lane.look.world !in DOG_WORLDS || lane.look.traveller == Traveller.SHIP || lane.look.traveller == Traveller.ICEBERG) {
        moves.dogState.fill(DogState.AWAY)
        moves.dogLastMs = t
        return
    }
    // A jump in time (a seek, another list): everyone starts again from away.
    if (!moves.dogLastMs.isNaN() && abs(t - moves.dogLastMs) > 5000) moves.dogState.fill(DogState.AWAY)
    moves.dogLastMs = t
    val s = min(h * 0.11f, w * 0.12f)
    val run = playing && heightAt(travellerX, 1f, 60f) > 0.45f
    // Upright there is little room behind the traveller: the ones behind close up, as a pack,
    // so the last one still shows.
    val behind = ((travellerX - s * 0.45f) / (s * 3.2f)).coerceIn(0.55f, 1f)
    for ((k, dog) in Dog.entries.withIndex()) {
        val spot = when (dog) {
            Dog.GALGO -> travellerX + s * 1.6f
            Dog.MALAMUTE -> travellerX - s * 1.3f * behind
            Dog.GOLDIE -> travellerX - s * 2.3f * behind
            Dog.BRACO -> travellerX - s * 3.2f * behind
        } + sin(now / 1700.0 + k).toFloat() * s * 0.12f
        val wants = dogWants(k, t)
        var x = spot
        // After a rabbit, the podenco always; the others now and then.
        val chases = !rabbitX.isNaN() && (dog == Dog.GALGO || Diorama.hash(rabbitRun * 4 + k, 745) < 0.4f)
        when (moves.dogState[k]) {
            DogState.AWAY -> {
                if (!wants) continue
                // Found waiting just past the right edge, on its piece of ground.
                moves.dogState[k] = DogState.COMING
                moves.dogMark[k] = lane.originMs + msAt(w * 1.06f, 1f)
                x = w * 1.06f
            }
            DogState.COMING -> {
                x = xAt(moves.dogMark[k] - lane.originMs, 1f)
                if (x <= spot) {
                    moves.dogState[k] = DogState.WITH
                    x = spot
                }
            }
            DogState.WITH -> if (!wants) {
                moves.dogState[k] = DogState.LEAVING
                moves.dogMark[k] = t
            }
            DogState.LEAVING -> {
                val u = ((t - moves.dogMark[k]) / 2500.0).toFloat()
                x = spot + u * u * w * 1.2f
                if (x > w * 1.1f) {
                    moves.dogState[k] = DogState.AWAY
                    continue
                }
            }
            DogState.CHASING -> {
                val rx = rabbitX
                if (rx.isNaN() || rx - s * (0.7f + 0.8f * k) > w * 1.1f) {
                    moves.dogState[k] = DogState.AWAY
                    continue
                }
                x = max(spot, rx - s * (0.7f + 0.8f * k))
            }
        }
        // The rabbit reaches it: off after it.
        if (chases && moves.dogState[k] == DogState.WITH && rabbitX > x - s * 0.3f) moves.dogState[k] = DogState.CHASING
        val state = moves.dogState[k]
        val did = Scene.id(Scene.MATE, 900 + k)
        touchable(did, x, nearY(x) - s * 0.3f, s * 0.5f)
        val flip = bounce(poked(did), 800)
        val foot = nearY(x) - (flip?.get(0) ?: 0f) * s * 0.9f
        if (flip != null) out.turn(flip[1], x, foot - s * 0.2f)
        when (state) {
            // Waiting there: standing still, its tail going.
            DogState.COMING -> dog(dog, x, foot, s, 1f, 0.25f + 0.04f * sin(now / 700.0 + k).toFloat(), run = false)
            DogState.LEAVING, DogState.CHASING -> dog(dog, x, foot, s, 1f, ((now + k * 97L) % 380) / 380f, run = true)
            // Paused, they wait: shifting a little where they stand.
            else -> dog(dog, x, foot, s, 1f, if (!playing) 0.25f + 0.05f * sin(now / 650.0 + k * 1.7).toFloat() else if (run) ((now + k * 97L) % 420) / 420f else (beat * 2f + k * 0.25f) % 1f, run)
        }
        out.straight()
    }
}

/** Whether one of the four is with the traveller (or about to be) now. */
internal fun Scene.dogsAround() = moves.dogState.any { it == DogState.WITH || it == DogState.COMING }

/**
 * One of the four sitting on [ground] at [x], looking towards [dir]: haunches down, front legs
 * straight, head up; in space with a round glass helmet.
 */
internal fun Scene.sittingDog(dog: Dog, x: Float, ground: Float, s: Float, dir: Float, helmet: Boolean) {
    val k = SHAPES.getValue(dog)
    val h = k.height * s
    val len = k.length * s
    val t = k.thick * s
    val legs = when (dog) {
        Dog.MALAMUTE -> k.light
        else -> k.coat
    }
    val hipX = x - dir * len * 0.18f
    val hipY = ground - t * 0.55f
    val chestX = x + dir * len * 0.14f
    val chestY = ground - h * 1.05f
    // The tail on the ground behind (a stump for the podenco).
    val wag = sin(now / 300.0 + dog.ordinal).toFloat() * t * 0.1f
    when (dog) {
        Dog.GALGO -> out.line(k.coat, t * 0.22f, hipX - dir * t * 0.5f, hipY + t * 0.2f, hipX - dir * t * 0.8f, ground - t * 0.12f)
        Dog.MALAMUTE -> {
            out.polyline(k.coat, t * 0.45f)
            out.point(hipX - dir * t * 0.4f, hipY + t * 0.1f); out.point(hipX - dir * t * 1.1f, ground - t * 0.3f + wag); out.point(hipX - dir * t * 1.2f, ground - t * 0.7f + wag)
        }
        else -> {
            out.polyline(if (dog == Dog.GOLDIE) k.light else k.coat, t * (if (dog == Dog.GOLDIE) 0.35f else 0.16f))
            out.point(hipX - dir * t * 0.4f, hipY + t * 0.2f); out.point(hipX - dir * t * 1.3f, ground - t * 0.1f + wag)
        }
    }
    // Far front leg, haunch, body up to the chest, near front leg.
    val far = Colors.darker(legs, 0.18f)
    val legW = t * 0.3f
    out.line(far, legW, chestX - dir * t * 0.15f, chestY + t * 0.3f, chestX - dir * t * 0.05f, ground)
    out.circle(k.coat, hipX, hipY, t * 0.62f)
    out.line(k.coat, t * 1.0f, hipX, hipY - t * 0.1f, chestX, chestY + t * 0.2f)
    when (dog) {
        Dog.MALAMUTE -> out.line(k.light, t * 0.5f, chestX + dir * t * 0.15f, chestY + t * 0.1f, chestX + dir * t * 0.05f, chestY + t * 0.75f)
        Dog.GALGO -> out.line(k.light, t * 0.45f, chestX + dir * t * 0.2f, chestY + t * 0.15f, chestX + dir * t * 0.12f, chestY + t * 0.7f)
        Dog.BRACO -> {
            out.circle(k.light, hipX - dir * t * 0.1f, hipY - t * 0.2f, t * 0.35f)
            out.circle(k.light, (hipX + chestX) / 2f, (hipY + chestY) / 2f, t * 0.22f)
        }
        Dog.GOLDIE -> {}
    }
    // The hind paw ahead of the haunch.
    out.line(legs, legW, hipX + dir * t * 0.2f, ground - t * 0.05f, hipX + dir * t * 0.6f, ground - t * 0.05f)
    val paw = if (dog == Dog.GALGO) k.light else legs
    out.line(legs, legW, chestX + dir * t * 0.05f, chestY + t * 0.3f, chestX + dir * t * 0.12f, ground - t * 0.1f)
    out.line(paw, legW * 1.1f, chestX + dir * t * 0.12f, ground - t * 0.12f, chestX + dir * t * 0.3f, ground - t * 0.02f)
    // Neck and head, looking up a little.
    val hx = chestX + dir * k.neck * s * 0.35f
    val hy = chestY - k.neck * s * 0.85f - t * 0.1f
    out.line(k.coat, t * 0.65f, chestX, chestY + t * 0.1f, hx, hy)
    if (dog == Dog.BRACO) out.line(k.light, t * 0.55f, chestX, chestY + t * 0.1f, hx, hy)
    dogHead(dog, k, hx, hy, s, dir, 0.25f)
    if (helmet) {
        // A fishbowl: glass, a rim, a shine, a collar.
        val r = k.head * s * 2.1f + k.snout * s * 0.5f
        val cx = hx + dir * k.snout * s * 0.4f
        out.circle(Colors.alpha(0xFFDDEEFF.toInt(), 0.22f), cx, hy, r)
        out.ring(Colors.alpha(0xFFFFFFFF.toInt(), 0.75f), cx, hy, r, r * 0.07f)
        out.circle(Colors.alpha(0xFFFFFFFF.toInt(), 0.6f), cx - dir * r * 0.45f, hy - r * 0.5f, r * 0.13f)
        out.rect(0xFFB8BCC8.toInt(), cx - r * 0.55f, hy + r * 0.85f, cx + r * 0.55f, hy + r * 1.05f)
    }
}

/**
 * Where the four do not go on foot (space, the cold sea), now and then they sit together by the
 * way and watch the traveller go by: on the moon in glass helmets, on a floe of ice.
 */
internal fun Scene.dogsWatching() {
    if (out.fade < 0.5f) return
    val world = lane.look.world
    if (world != World.SPACE && world != World.ICE) return
    val s = min(h * 0.11f, w * 0.12f)
    slotsMs(1f, 50_000.0) { i, x0, x1 ->
        if (Diorama.hash(i, 750) > 0.55f) return@slotsMs
        val cx = x0 + (x1 - x0) * (0.3f + 0.4f * Diorama.hash(i, 751))
        val gap = s * 0.72f
        val order = arrayOf(Dog.GALGO, Dog.MALAMUTE, Dog.GOLDIE, Dog.BRACO)
        val span = gap * (order.size - 1)
        if (cx + span < -w * 0.1f || cx - span > w * 1.1f) return@slotsMs
        // On the moon's ground, or on a floe of ice rocking on the sea.
        val floe = world == World.ICE
        val rock = if (floe) sin(now / 1200.0 + i).toFloat() * s * 0.03f else 0f
        val seaLevel = nearBase - heightAt(cx, 1f, 60f) * nearAmp * 0.6f
        if (floe) {
            val ice = Colors.mix(p.mid, 0xFFFFFFFF.toInt(), 0.35f)
            val top = seaLevel - s * 0.12f + rock
            out.polygon(ice)
            out.point(cx - span / 2f - s * 0.7f, seaLevel + s * 0.05f + rock); out.point(cx - span / 2f - s * 0.45f, top)
            out.point(cx + span / 2f + s * 0.5f, top - s * 0.02f); out.point(cx + span / 2f + s * 0.75f, seaLevel + s * 0.05f + rock)
        }
        for ((k, dog) in order.withIndex()) {
            val dx = cx - span / 2f + k * gap
            val ground = if (floe) seaLevel - s * 0.12f + rock else footNear(dx, s * 0.15f)
            val did = Scene.id(Scene.MATE, 950 + i * 4 + k)
            touchable(did, dx, ground - s * 0.35f, s * 0.4f)
            val jump = bounce(poked(did), 700)
            // Looking at the traveller: towards it, then after it once it has gone by.
            val dir = if (dx > travellerX) -1f else 1f
            sittingDog(dog, dx, ground - (jump?.get(0) ?: 0f) * s * 0.6f, s, dir, helmet = world == World.SPACE)
        }
        if (floe) out.rect(Colors.alpha(p.near, 0.85f), cx - span / 2f - s * 0.8f, seaLevel + s * 0.03f + rock, cx + span / 2f + s * 0.85f, seaLevel + s * 0.14f + rock)
    }
}
