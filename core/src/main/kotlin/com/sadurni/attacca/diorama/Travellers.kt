package com.sadurni.attacca.diorama

import com.sadurni.attacca.diorama.Diorama.Companion.hash
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// The traveller and its moves: hops, jumps, flips, cartwheels, wheelies… chosen by the beats.

/** On a beat: maybe a move, bigger and bolder the louder and livelier the music. */
internal fun Scene.onBeat(song: SongLook) {
    if (!playing || now < moves.trickEndMs + (1700 - 1300 * song.energy).toLong()) return
    val r = hash(moves.onsets, 301)
    if (r < 0.25f - 0.15f * song.energy) return // not every beat
    val big = (live.level > 0.75f || live.low > 0.8f) && song.energy > 0.4f
    val q = hash(moves.onsets, 302)
    val trick = when (song.traveller) {
        Traveller.RUNNER -> when {
            big && q < 0.3f -> Trick.FLIP
            big && q < 0.5f -> Trick.CARTWHEEL
            q < 0.7f -> Trick.JUMP
            q < 0.85f -> Trick.DOUBLE
            else -> Trick.HOP
        }
        Traveller.WALKER -> when {
            big && q < 0.15f -> Trick.CARTWHEEL
            q < 0.45f -> Trick.HOP
            q < 0.7f -> Trick.ARMS
            else -> Trick.DOUBLE
        }
        Traveller.RIDER -> if (big && q < 0.45f) Trick.REAR else Trick.JUMP
        Traveller.CYCLIST -> when {
            big && q < 0.2f -> Trick.FLIP
            q < 0.55f -> Trick.WHEELIE
            else -> Trick.HOP
        }
        Traveller.CAR -> when {
            big && q < 0.12f -> Trick.ROLL
            q < 0.5f -> Trick.WHEELIE
            else -> Trick.HOP
        }
        Traveller.ICEBERG -> when {
            big && q < 0.15f -> Trick.FLIP
            q < 0.6f -> Trick.ARMS
            else -> Trick.HOP
        }
        Traveller.SHIP -> if (big && q < 0.4f) Trick.LOOP else Trick.HOP
    }
    start(trick)
}

/** The traveller was touched: its boldest move, at once. */
internal fun Scene.touchedMove(song: SongLook) {
    if (now < moves.trickEndMs) return
    start(
        when (song.traveller) {
            Traveller.RUNNER -> Trick.FLIP
            Traveller.WALKER, Traveller.ICEBERG -> Trick.CARTWHEEL
            Traveller.RIDER -> Trick.REAR
            Traveller.CYCLIST, Traveller.CAR -> if (hash(moves.onsets, 303) < 0.5f) Trick.WHEELIE else Trick.FLIP
            Traveller.SHIP -> Trick.LOOP
        },
    )
}

/** Quiet music gives few beats: now and then a calm move anyway. */
internal fun Scene.idleMove(song: SongLook) {
    if (!playing || now - moves.trickEndMs < 9000) return
    start(if (song.traveller == Traveller.SHIP || song.traveller == Traveller.CAR) Trick.HOP else Trick.ARMS)
}

private fun Scene.start(trick: Trick) {
    moves.trick = trick
    moves.trickAtMs = now
    moves.trickEndMs = now + trick.ms
}

/** The current move's progress (0 … 1), or -1 when none. */
private fun Scene.moveAt(): Float {
    if (moves.trick == Trick.NONE || now >= moves.trickEndMs || now < moves.trickAtMs) return -1f
    return (now - moves.trickAtMs) / moves.trick.ms.toFloat()
}

private fun ease(u: Float) = u * u * (3f - 2f * u)

/** A move's pose: how high (in sizes), turned how much (radians, clockwise), arms up (0 … 1). */
private class Pose(var lift: Float = 0f, var angle: Float = 0f, var arms: Float = 0f)

/** A move's pose, lifted over what rises on the way ([Scene.jumpLift]). */
private fun Scene.pose(backwards: Boolean = false): Pose =
    trickPose(backwards).also { it.lift += jumpLift(travellerX) / min(h * 0.11f, w * 0.12f) }

private fun Scene.trickPose(backwards: Boolean): Pose {
    val u = moveAt()
    val pose = Pose()
    if (u < 0f) return pose
    val arc = sin(PI * u).toFloat()
    val turnDir = if (backwards) -1f else 1f
    when (moves.trick) {
        Trick.HOP -> pose.lift = arc * 0.22f
        Trick.JUMP -> { pose.lift = arc * 0.5f; pose.arms = arc * 0.6f }
        Trick.DOUBLE -> pose.lift = abs(sin(2 * PI * u)).toFloat() * 0.35f
        Trick.FLIP -> { pose.lift = arc * 0.8f; pose.angle = turnDir * 2f * PI.toFloat() * ease(u) }
        Trick.CARTWHEEL -> { pose.lift = arc * 0.25f; pose.angle = 2f * PI.toFloat() * u; pose.arms = 1f }
        Trick.ARMS -> pose.arms = sqrt(arc)
        Trick.WHEELIE -> pose.angle = -0.42f * sqrt(arc)
        Trick.REAR -> pose.angle = -0.38f * sqrt(arc)
        Trick.ROLL -> { pose.lift = arc * 1.1f; pose.angle = 2f * PI.toFloat() * ease(u) }
        Trick.LOOP -> { pose.lift = (1f - cos(2 * PI * u).toFloat()) * 0.9f; pose.angle = -2f * PI.toFloat() * ease(u) }
        Trick.NONE -> {}
    }
    return pose
}

internal fun Scene.traveller(song: SongLook) {
    val s = min(h * 0.11f, w * 0.12f)
    val beats = posMs / 60_000.0 * song.bpm
    val phase = (beats - floor(beats)).toFloat()
    val moving = playing
    val x = travellerX
    val tunnel = tunnelAt(x)
    // A torch from the first bracket to the last; inside, how far (for the car's lights coming on).
    val carrying = tunnel != null && x >= tunnel[0] + torchInset && x <= tunnel[1] - torchInset
    val into = tunnel?.let { min(x - it[0], it[1] - x) } ?: -1f
    val color = figureColor(tunnel != null)
    val dark = tunnel != null || luma(backdrop(tunnel != null)) < 0.3f || look.night > 0.55f
    // Its own light where it is dark: a soft halo, or the torch's warm one.
    val kind = who(song)
    moves.headStyle = headStyleOf(song.facts?.seed ?: 0, kind.ordinal)
    touchable(Scene.TRAVELLER, x, if (kind == Traveller.SHIP) h * 0.5f - heightAt(x, 1f, 40f) * h * 0.18f else nearY(x) - s * 0.5f, s * 0.8f)
    val person = kind == Traveller.RUNNER || kind == Traveller.WALKER || kind == Traveller.RIDER
    if (carrying && person) {
        val flicker = 0.9f + 0.1f * sin(now / 53.0).toFloat()
        out.circle(Colors.alpha(0xFFFFB050.toInt(), 0.10f), x + s * 0.2f, nearY(x) - s * 0.9f, s * 1.9f * flicker)
        out.circle(Colors.alpha(0xFFFFC870.toInt(), 0.12f), x + s * 0.2f, nearY(x) - s * 0.9f, s * 1.1f * flicker)
    } else if (dark && kind != Traveller.SHIP) {
        out.circle(Colors.alpha(p.light, 0.05f), x, nearY(x) - s * 0.55f, s * 1.15f)
        out.circle(Colors.alpha(p.light, 0.05f), x, nearY(x) - s * 0.55f, s * 0.75f)
    }
    // Loud parts: a run; quiet ones: a walk.
    val loud = heightAt(x, 1f, 60f) > 0.45f || live.level > 0.7f
    // Paused: no one freezes; they wait, shifting a little.
    val idle = 0.25f + 0.05f * sin(now / 900.0).toFloat()
    val walkPhase = if (moving) phase / 2f + (if (beats.toLong() % 2 == 1L) 0.5f else 0f) else idle
    when (kind) {
        Traveller.RUNNER -> {
            val pz = pose()
            turned(pz.angle, x, nearY(x) - pz.lift * s - s * 0.5f) {
                if (loud) figure(x, s, if (moving) phase else idle, color, run = true, arms = pz.arms, lift = pz.lift * s, holdsTorch = carrying, still = !moving)
                else figure(x, s, walkPhase, color, run = false, arms = pz.arms, lift = pz.lift * s, holdsTorch = carrying, still = !moving)
            }
        }
        Traveller.WALKER -> {
            val pz = pose()
            turned(pz.angle, x, nearY(x) - pz.lift * s - s * 0.5f) {
                figure(x, s * 0.9f, walkPhase, color, run = false, arms = pz.arms, lift = pz.lift * s, holdsTorch = carrying, still = !moving)
            }
        }
        Traveller.RIDER -> {
            val pz = pose()
            val tilt = slope(x - s * 0.4f, x + s * 0.35f)
            val ground = (nearY(x - s * 0.4f) + nearY(x + s * 0.35f)) / 2f - pz.lift * s
            tilted(tilt, x, ground, pz.angle, x - s * 0.4f, ground) { rider(x, ground, s, if (moving) phase else 0.03f * sin(now / 800.0).toFloat(), color, carrying) }
        }
        Traveller.CYCLIST -> {
            val pz = pose(backwards = true)
            val tilt = slope(x - s * 0.3f, x + s * 0.32f)
            val ground = (nearY(x - s * 0.3f) + nearY(x + s * 0.32f)) / 2f - pz.lift * s
            val pivotX = if (moves.trick == Trick.WHEELIE) x - s * 0.3f else x
            val pivotY = if (moves.trick == Trick.WHEELIE) ground else ground - s * 0.4f
            tilted(tilt, x, ground, pz.angle, pivotX, pivotY) { cyclist(x, ground, s, if (moving) phase else 0.06f * sin(now / 1300.0).toFloat(), color, lamp = tunnel != null || look.night > 0.4f) }
        }
        Traveller.CAR -> {
            val pz = pose()
            val tilt = slope(x - s * 0.32f, x + s * 0.32f)
            val ground = (nearY(x - s * 0.32f) + nearY(x + s * 0.32f)) / 2f - pz.lift * s
            val wheelie = moves.trick == Trick.WHEELIE
            // In a tunnel its lights come on one after another, from the roof down; at night the headlights.
            val lights = if (tunnel != null) (into / (s * 0.22f)).toInt().coerceIn(0, 4) else if (look.night > 0.3f || dark) 1 else 0
            tilted(tilt, x, ground, pz.angle, if (wheelie) x - s * 0.32f else x, if (wheelie) ground else ground - s * 0.3f) { car(x, ground, s, color, lights, tunnel != null) }
        }
        Traveller.ICEBERG -> icebergRider(x, s, color)
        Traveller.SHIP -> ship(x, s)
    }
}

/** How steep the ground is between [x0] and [x1] (radians, clockwise: downhill ahead is positive), kept within reason. */
private fun Scene.slope(x0: Float, x1: Float): Float =
    kotlin.math.atan2(nearY(x1) - nearY(x0), x1 - x0).coerceIn(-0.5f, 0.5f)

/**
 * Draws turned by the slope [tilt] around [x], [ground] (a vehicle on a hill) and then by a move's
 * [angle] around [px], [py], as one turn.
 */
private inline fun Scene.tilted(tilt: Float, x: Float, ground: Float, angle: Float, px: Float, py: Float, draw: () -> Unit) {
    if (angle == 0f) {
        turned(tilt, x, ground, draw)
        return
    }
    if (tilt == 0f) {
        turned(angle, px, py, draw)
        return
    }
    // The move's pivot as the slope moves it, then the one point both turns leave in place.
    val c1 = cos(tilt)
    val s1 = sin(tilt)
    val qx = x + (px - x) * c1 - (py - ground) * s1
    val qy = ground + (px - x) * s1 + (py - ground) * c1
    val total = tilt + angle
    val ct = cos(total)
    val st = sin(total)
    // v -> R(total)(v - p1) + k, with k = q - R(angle)(q - p1)
    val c2 = cos(angle)
    val s2 = sin(angle)
    val kx = qx - ((qx - x) * c2 - (qy - ground) * s2)
    val ky = qy - ((qx - x) * s2 + (qy - ground) * c2)
    // Fixed point f: (I - R) f = k - R p1
    val bx = kx - (x * ct - ground * st)
    val by = ky - (x * st + ground * ct)
    val a = 1f - ct
    val det = a * a + st * st
    if (det < 1e-6f) {
        turned(total, x, ground, draw)
        return
    }
    val fx = (a * bx - st * by) / det
    val fy = (st * bx + a * by) / det
    turned(total, fx, fy, draw)
}

/** What lies behind the traveller: the middle land and the sky, or a tunnel's dark. */
private fun Scene.backdrop(inTunnel: Boolean): Int =
    if (inTunnel) Colors.darker(p.near, 0.75f) else Colors.mix(Colors.mix(p.mid, p.skyBottom, 0.35f), p.near, 0.25f)

private fun luma(c: Int) = Colors.luma(c)

/**
 * The traveller's colour: the scene's accent, made light or dark enough to stand out from what is
 * behind it; it eases into a new one as the land behind changes (into a tunnel, say).
 */
private fun Scene.figureColor(inTunnel: Boolean): Int {
    val behind = luma(backdrop(inTunnel))
    val hsl = Colors.hsl(p.accent)
    val lighter = behind < 0.5f
    var l = hsl[2]
    var c = Colors.fromHsl(255, hsl[0], min(hsl[1], 0.8f), l)
    var k = 0
    while (kotlin.math.abs(luma(c) - behind) < 0.36f && k < 20) {
        l = (l + if (lighter) 0.04f else -0.04f).coerceIn(0.04f, 0.96f)
        c = Colors.fromHsl(255, hsl[0], min(hsl[1], 0.8f), l)
        k++
    }
    val shown = moves.figureColor
    moves.figureColor = if (shown == 0) c else Colors.mix(shown, c, 1f - kotlin.math.exp(-dt / 350f))
    return moves.figureColor
}

/** The far side's limbs: a shade towards the middle, so the two legs and arms read apart. */
internal fun farShade(color: Int) = if (luma(color) > 0.5f) Colors.darker(color, 0.22f) else Colors.lighter(color, 0.22f)

/**
 * The joint between two bones [l1] and [l2] long reaching from [ax], [ay] to [bx], [by]: on the
 * side ahead ([ahead]: a knee) or behind and below (an elbow), into [out] (x, y).
 */
private fun joint(ax: Float, ay: Float, bx: Float, by: Float, l1: Float, l2: Float, ahead: Boolean, out: FloatArray) {
    var dx = bx - ax
    var dy = by - ay
    var d = sqrt(dx * dx + dy * dy)
    if (d > l1 + l2 - 0.001f) {
        val k = (l1 + l2 - 0.001f) / d
        dx *= k
        dy *= k
        d = l1 + l2 - 0.001f
    }
    if (d < 0.001f) {
        out[0] = ax
        out[1] = ay + l1
        return
    }
    val a = (l1 * l1 - l2 * l2 + d * d) / (2f * d)
    val hgt = sqrt((l1 * l1 - a * a).coerceAtLeast(0f))
    val mx = ax + dx * a / d
    val my = ay + dy * a / d
    // The two possible joints; a knee goes ahead (+x), an elbow back.
    val px = -dy / d * hgt
    val py = dx / d * hgt
    val firstAhead = mx + px > mx - px
    val pick = if (ahead) firstAhead else !firstAhead
    out[0] = if (pick) mx + px else mx - px
    out[1] = if (pick) my + py else my - py
}

private val bend = FloatArray(2)

/**
 * A person walking or running: [phase] 0 … 1 is one stride of both legs, each with thigh and shin
 * swinging and the knee bending as the leg comes forward; the arms swing against the legs with the
 * elbows bent (well bent when running). [arms] 0 … 1 raises both arms; [holdsTorch]: one held up ahead.
 * Each foot finds the ground under it ([lift] above it in a jump).
 */
private fun Scene.figure(x: Float, s: Float, phase: Float, color: Int, run: Boolean, arms: Float = 0f, lift: Float = 0f, holdsTorch: Boolean = false, still: Boolean = false) {
    val thigh = s * 0.25f
    val shin = s * 0.25f
    val swingAmp = if (still) 0.08f else if (run) 0.62f else 0.38f
    val kneeAmp = if (still) 0.05f else if (run) 1.5f else 0.7f
    // Each leg's angles (from straight down, forward positive) and its foot's place from the hip.
    val thighA = FloatArray(2)
    val shinA = FloatArray(2)
    val footDx = FloatArray(2)
    val footDy = FloatArray(2)
    for (k in 0..1) {
        val f = (phase + k * 0.5f) * 2 * PI
        thighA[k] = (swingAmp * sin(f)).toFloat()
        // The knee bends while the leg swings forward, more when running (the heel kicks up).
        val swingForward = cos(f).toFloat().coerceAtLeast(0f)
        shinA[k] = thighA[k] - (0.08f + kneeAmp * swingForward * swingForward)
        footDx[k] = sin(thighA[k]) * thigh + sin(shinA[k]) * shin
        footDy[k] = cos(thighA[k]) * thigh + cos(shinA[k]) * shin
    }
    // The hip as high as the lower foot allows on the ground under each foot.
    var hipY = Float.MAX_VALUE
    for (k in 0..1) hipY = min(hipY, nearY(x + footDx[k]) - footDy[k])
    // Running: in the air between steps for a moment.
    val air = if (run && !still) kotlin.math.abs(sin(phase * 4 * PI)).toFloat() * s * 0.04f * (0.5f + live.level)
        else if (still) sin(now / 1300.0).toFloat() * s * 0.012f else 0f
    hipY -= lift + air
    val hipX = x
    // Leaning forward running; a little more uphill.
    val uphill = (nearY(x) - nearY(x + s * 0.4f)) / (s * 0.4f)
    val lean = (if (run) 0.22f else 0.06f) + uphill.coerceIn(-0.3f, 0.5f) * 0.3f + (if (still) sin(now / 1700.0).toFloat() * 0.05f else 0f)
    val torso = s * 0.36f
    val shX = hipX + sin(lean) * torso
    val shY = hipY - cos(lean) * torso
    val lw = s * 0.085f
    val far = farShade(color)
    // Far side first (leg 1, arm 0), then the near side over it.
    fun leg(k: Int, c: Int) {
        val kx = hipX + sin(thighA[k]) * thigh
        val ky = hipY + cos(thighA[k]) * thigh
        val fx = hipX + footDx[k]
        val fy = hipY + footDy[k]
        out.polyline(c, lw)
        out.point(hipX, hipY); out.point(kx, ky); out.point(fx, fy)
        // The foot, pointing ahead along the shin's turn.
        out.line(c, lw * 0.8f, fx, fy, fx + s * 0.08f * cos(shinA[k] * 0.5f), fy + s * 0.01f)
    }
    fun arm(k: Int, c: Int) {
        val upper = s * 0.19f
        val fore = s * 0.18f
        // Swinging against the leg of the same side.
        val legSwing = thighA[1 - k]
        var upperA = -legSwing * (if (run) 1.1f else 0.9f)
        var elbowBend = if (run) 1.45f else 0.35f + 0.25f * kotlin.math.abs(sin(legSwing))
        // Up in the air: straight up, a little apart.
        val upA = PI.toFloat() * (if (k == 0) 0.88f else 1.1f)
        upperA += (upA - upperA) * arms
        elbowBend += (0.15f - elbowBend) * arms
        if (holdsTorch && k == 0) {
            // Holding a torch up ahead.
            upperA = 1.25f
            elbowBend = 0.9f
        }
        val ex = shX + sin(upperA) * upper
        val ey = shY + cos(upperA) * upper
        val foreA = upperA + elbowBend
        val hx = ex + sin(foreA) * fore
        val hy = ey + cos(foreA) * fore
        out.polyline(c, lw * 0.82f)
        out.point(shX, shY); out.point(ex, ey); out.point(hx, hy)
        out.circle(c, hx, hy, lw * 0.55f)
        if (holdsTorch && k == 0) torch(hx, hy + s * 0.04f, s * 0.9f, 0.15f)
    }
    leg(1, far)
    arm(1, far)
    out.line(color, lw * 1.35f, hipX, hipY, shX, shY)
    leg(0, color)
    // The head on its neck, nodding a little with the stride.
    val nod = if (still) sin(now / 1100.0).toFloat() * s * 0.012f else sin(phase * 4 * PI).toFloat() * s * 0.01f
    head(shX + sin(lean) * s * 0.16f, shY - cos(lean) * s * 0.16f + nod, s * 0.12f, color)
    arm(0, color)
}

private fun Scene.rider(x: Float, ground: Float, s: Float, phase: Float, color: Int, torch: Boolean) {
    val gallop = sin(phase * 2 * PI).toFloat()
    val lift = kotlin.math.abs(gallop) * s * 0.06f * (0.6f + live.level)
    val bodyY = ground - s * 0.62f - lift
    val bx0 = x - s * 0.45f
    val bx1 = x + s * 0.4f
    val lw = s * 0.09f
    val far = farShade(color)
    // Legs in a gallop: each with its upper leg and cannon; the front knees fold back, the hocks forward.
    val upper = s * 0.24f
    val lower = s * 0.26f
    for ((k, leg) in listOf(Triple(bx0 + s * 0.2f, 0.1f, false), Triple(bx1 - s * 0.03f, 0.6f, true), Triple(bx0 + s * 0.08f, 0f, false), Triple(bx1 - s * 0.15f, 0.5f, true)).withIndex()) {
        val (lx, offset, front) = leg
        val f = (phase + offset) * 2 * PI
        val a = (0.5f * sin(f)).toFloat()
        val fold = (0.9f * cos(f).toFloat().coerceAtLeast(0f))
        val kx = lx + sin(a) * upper
        val ky = bodyY + s * 0.05f + cos(a) * upper
        val lowA = if (front) a - fold else a + fold * 0.7f
        val hx = kx + sin(lowA) * lower
        val hy = (ky + cos(lowA) * lower).coerceAtMost(ground + 1f)
        val c = if (k < 2) far else color
        out.polyline(c, lw)
        out.point(lx, bodyY + s * 0.05f); out.point(kx, ky); out.point(hx, hy)
        out.circle(c, hx, hy, lw * 0.6f)
    }
    out.rect(color, bx0, bodyY - s * 0.14f, bx1, bodyY + s * 0.12f)
    out.circle(color, bx0, bodyY - s * 0.01f, s * 0.13f)
    out.circle(color, bx1, bodyY - s * 0.01f, s * 0.13f)
    // Neck and head, nodding with the gallop.
    val nod = gallop * s * 0.03f
    out.polygon(color)
    out.point(bx1 - s * 0.05f, bodyY - s * 0.1f); out.point(bx1 + s * 0.2f, bodyY - s * 0.48f + nod); out.point(bx1 + s * 0.33f, bodyY - s * 0.4f + nod); out.point(bx1 + s * 0.12f, bodyY + s * 0.05f)
    out.polygon(color)
    out.point(bx1 + s * 0.18f, bodyY - s * 0.5f + nod); out.point(bx1 + s * 0.45f, bodyY - s * 0.32f + nod); out.point(bx1 + s * 0.4f, bodyY - s * 0.24f + nod); out.point(bx1 + s * 0.25f, bodyY - s * 0.36f + nod)
    // Tail.
    out.polyline(color, lw * 0.7f)
    out.point(bx0 - s * 0.08f, bodyY - s * 0.08f); out.point(bx0 - s * 0.2f, bodyY + s * 0.02f); out.point(bx0 - s * 0.25f, bodyY + s * 0.12f + gallop * s * 0.05f)
    // The rider: thigh along the horse, knee bent, foot in the stirrup; arm to the reins or a torch up.
    val seatX = x - s * 0.05f
    val seatY = bodyY - s * 0.16f
    out.polyline(color, lw * 0.8f)
    out.point(seatX, seatY); out.point(seatX + s * 0.16f, seatY + s * 0.12f); out.point(seatX + s * 0.08f, seatY + s * 0.32f)
    val shX = seatX + s * 0.08f
    val shY = seatY - s * 0.38f
    out.line(color, lw, seatX, seatY, shX, shY)
    head(shX + s * 0.02f, shY - s * 0.12f, s * 0.1f, color)
    if (torch) {
        out.polyline(color, lw * 0.7f)
        out.point(shX, shY + s * 0.05f); out.point(shX + s * 0.14f, shY - s * 0.02f); out.point(shX + s * 0.24f, shY - s * 0.14f)
        torch(shX + s * 0.24f, shY - s * 0.1f, s * 0.9f, 0.15f)
    } else {
        joint(shX, shY + s * 0.05f, bx1 + s * 0.12f, bodyY - s * 0.12f, s * 0.17f, s * 0.17f, ahead = false, out = bend)
        out.polyline(color, lw * 0.7f)
        out.point(shX, shY + s * 0.05f); out.point(bend[0], bend[1]); out.point(bx1 + s * 0.12f, bodyY - s * 0.12f)
        // The reins.
        out.line(Colors.alpha(far, 0.8f), s * 0.02f, bx1 + s * 0.12f, bodyY - s * 0.12f, bx1 + s * 0.36f, bodyY - s * 0.33f + nod)
    }
    // A banner flapping with the music.
    out.line(color, lw * 0.5f, seatX - s * 0.05f, seatY, seatX - s * 0.05f, seatY - s * 0.75f)
    out.polygon(Colors.alpha(p.accent, 0.9f))
    out.point(seatX - s * 0.05f, seatY - s * 0.75f); out.point(seatX - s * 0.35f, seatY - s * (0.7f - 0.05f * gallop)); out.point(seatX - s * 0.05f, seatY - s * 0.6f)
}

private fun Scene.cyclist(x: Float, ground: Float, s: Float, phase: Float, color: Int, lamp: Boolean) {
    val r = s * 0.22f
    val back = x - s * 0.3f
    val front = x + s * 0.32f
    val wy = ground - r
    val lw = s * 0.06f
    val far = farShade(color)
    if (lamp) {
        // The lamp's beam ahead.
        out.polygon(Colors.alpha(p.light, 0.22f + 0.1f * live.level))
        out.point(front - s * 0.02f, wy - s * 0.36f); out.point(front + s * 1.5f, wy - s * 0.1f); out.point(front + s * 1.5f, wy + s * 0.25f)
    }
    out.ring(color, back, wy, r, lw * 0.8f)
    out.ring(color, front, wy, r, lw * 0.8f)
    // Spokes turning.
    val turn = phase * 2 * PI * 2
    for (wx in floatArrayOf(back, front)) for (k in 0 until 3) {
        val a = turn + k * PI / 3
        out.line(Colors.alpha(color, 0.5f), lw * 0.25f, wx - (cos(a) * r).toFloat(), wy - (sin(a) * r).toFloat(), wx + (cos(a) * r).toFloat(), wy + (sin(a) * r).toFloat())
    }
    val crankX = x
    val crankY = wy
    val seatX = x - s * 0.12f
    val seatY = wy - s * 0.38f
    val barX = front - s * 0.05f
    val barY = wy - s * 0.42f
    val a = phase * 2 * PI
    // The far leg behind the frame.
    fun leg(side: Int, c: Int) {
        val px = crankX + (cos(a + if (side < 0) PI else 0.0) * s * 0.1f).toFloat()
        val py = crankY + (sin(a + if (side < 0) PI else 0.0) * s * 0.1f).toFloat()
        joint(seatX, seatY - s * 0.04f, px, py, s * 0.24f, s * 0.24f, ahead = true, out = bend)
        out.polyline(c, lw)
        out.point(seatX, seatY - s * 0.04f); out.point(bend[0], bend[1]); out.point(px, py)
        out.line(c, lw * 0.8f, px - s * 0.03f, py, px + s * 0.05f, py)
    }
    leg(-1, far)
    out.polyline(color, lw)
    out.point(back, wy); out.point(crankX, crankY); out.point(seatX, seatY); out.point(back, wy)
    out.polyline(color, lw)
    out.point(crankX, crankY); out.point(barX, barY); out.point(front, wy)
    out.line(color, lw, seatX, seatY, barX - s * 0.05f, barY + s * 0.02f)
    leg(1, color)
    val shX = seatX + s * 0.2f
    val shY = seatY - s * 0.35f
    out.line(color, lw * 1.2f, seatX, seatY - s * 0.04f, shX, shY)
    // The arm to the handlebar, the elbow bent down.
    joint(shX, shY, barX, barY, s * 0.15f, s * 0.15f, ahead = false, out = bend)
    out.polyline(color, lw * 0.9f)
    out.point(shX, shY); out.point(bend[0], bend[1]); out.point(barX, barY)
    head(shX + s * 0.06f, shY - s * 0.13f, s * 0.11f, color)
    if (lamp) {
        out.circle(p.light, barX + s * 0.03f, barY + s * 0.04f, s * 0.035f)
        out.circle(Colors.alpha(p.light, 0.9f), shX + s * 0.1f, shY - s * 0.2f, s * 0.03f)
    }
}

/**
 * A car: [lights] how many of its lights are on (0 none; 1 headlights; in a tunnel they come on
 * from the roof down: roof bar, headlights, fog lights, the glow underneath).
 */
private fun Scene.car(x: Float, ground: Float, s: Float, color: Int, lights: Int, tunnel: Boolean) {
    val bounce = live.low * s * 0.04f + (if (!playing) (sin(now / 38.0).toFloat() * 0.5f + 0.5f) * s * 0.008f else 0f)
    val r = s * 0.15f
    val y = ground - r - bounce
    val body = if (look.world == World.NEON) Colors.mix(color, p.accent, 0.25f) else color
    val l = x - s * 0.6f
    val rr = x + s * 0.6f
    val roofOn = tunnel && lights >= 1
    val headOn = if (tunnel) lights >= 2 else lights >= 1
    val fogOn = tunnel && lights >= 3
    val underOn = tunnel && lights >= 4
    if (underOn) out.rect(Colors.alpha(p.accent, 0.35f + 0.25f * live.level), l + s * 0.05f, ground - s * 0.04f, rr - s * 0.05f, ground + s * 0.03f)
    if (headOn) {
        out.polygon(Colors.alpha(p.light, 0.25f + 0.2f * live.level))
        out.point(rr, y - s * 0.12f); out.point(rr + s * 1.6f, y - s * 0.3f); out.point(rr + s * 1.6f, y + s * 0.12f)
    }
    if (fogOn) {
        out.polygon(Colors.alpha(0xFFFFE6A0.toInt(), 0.2f))
        out.point(rr, y - s * 0.02f); out.point(rr + s * 1.1f, y + s * 0.02f); out.point(rr + s * 1.1f, ground)
    }
    if (roofOn) out.circle(Colors.alpha(p.light, 0.12f), x, y - s * 0.5f, s * 0.9f)
    out.rect(body, l, y - s * 0.25f, rr, y)
    // The cabin towards the back: a short boot behind, a long bonnet ahead (it drives to the right),
    // the windscreen sloping forward.
    val b0 = l + s * 0.1f
    val r0 = l + s * 0.22f
    val r1 = rr - s * 0.6f
    val b1 = rr - s * 0.36f
    val mid = (r0 + r1) / 2f
    out.polygon(body)
    out.point(b0, y - s * 0.25f); out.point(r0, y - s * 0.5f); out.point(r1, y - s * 0.5f); out.point(b1, y - s * 0.25f)
    val glass = Colors.alpha(Colors.mix(p.skyBottom, p.light, if (roofOn) 0.8f else 0.4f), 0.8f)
    out.polygon(glass)
    out.point(b0 + s * 0.08f, y - s * 0.27f); out.point(r0 + s * 0.05f, y - s * 0.45f); out.point(mid - s * 0.03f, y - s * 0.45f); out.point(mid - s * 0.03f, y - s * 0.27f)
    out.polygon(glass)
    out.point(mid + s * 0.03f, y - s * 0.27f); out.point(mid + s * 0.03f, y - s * 0.45f); out.point(r1 - s * 0.02f, y - s * 0.45f); out.point(b1 - s * 0.07f, y - s * 0.27f)
    if (roofOn) {
        // A bar of lights on the roof.
        out.rect(Colors.darker(body, 0.3f), mid - s * 0.15f, y - s * 0.56f, mid + s * 0.15f, y - s * 0.5f)
        for (k in 0 until 4) out.circle(p.light, mid - s * 0.11f + k * s * 0.075f, y - s * 0.53f, s * 0.025f)
    }
    out.circle(if (headOn) p.light else Colors.mix(p.light, body, 0.5f), rr - s * 0.03f, y - s * 0.15f, s * 0.035f)
    if (fogOn) out.circle(0xFFFFE6A0.toInt(), rr - s * 0.05f, y - s * 0.04f, s * 0.025f)
    out.circle(0xFFE03030.toInt(), l + s * 0.03f, y - s * 0.15f, s * 0.03f)
    if (look.world == World.NEON) out.line(Colors.alpha(p.accent, 0.4f + 0.6f * live.level), s * 0.03f, l + s * 0.1f, y + s * 0.02f, rr - s * 0.1f, y + s * 0.02f)
    for (wx in floatArrayOf(l + s * 0.28f, rr - s * 0.28f)) {
        out.circle(0xFF111111.toInt(), wx, ground - r - bounce * 0.2f, r)
        out.circle(0xFF9A9A9A.toInt(), wx, ground - r - bounce * 0.2f, r * 0.45f)
    }
}

/**
 * Who crosses now: in some tracks, halfway through, someone else of the world (on foot instead of
 * by bike, say), with a puff of dust as they change.
 */
private fun Scene.who(song: SongLook, puff: Boolean = true): Traveller {
    val wave = lane.wave ?: return song.traveller
    val seed = song.facts?.seed ?: return song.traveller
    val others = Looks.travellersOf(song.world).filter { it != song.traveller }
    if (others.isEmpty() || hash(seed, 9) > 0.5f) return song.traveller
    val half = wave.lengthMs / 2.0
    val away = kotlin.math.abs(posMs - half)
    if (puff && away < 700) {
        // The puff.
        val k = (1f - (away / 700.0).toFloat())
        val s = min(h * 0.11f, w * 0.12f)
        val y = nearY(travellerX) - s * 0.5f
        for (m in 0 until 7) {
            val a = m * PI / 3.5
            out.circle(Colors.alpha(0xFFF2EEE6.toInt(), 0.7f * k), travellerX + (cos(a) * s * 0.6f * (1.6f - k)).toFloat(), y + (sin(a) * s * 0.45f * (1.6f - k)).toFloat(), s * 0.28f * k)
        }
    }
    return if (posMs > half) others[(hash(seed, 10) * others.size).toInt().coerceAtMost(others.size - 1)] else song.traveller
}

private inline fun Scene.turned(angle: Float, px: Float, py: Float, draw: () -> Unit) {
    if (angle != 0f) out.turn(angle, px, py)
    draw()
    if (angle != 0f) out.straight()
}

/** Someone standing on a small iceberg adrift, rocked by the sea and the sound; jumping and waving now and then. */
private fun Scene.icebergRider(x: Float, s: Float, color: Int) {
    val water = seaY(x)
    val rock = sin(now / 1100.0).toFloat() * s * 0.04f + live.low * s * 0.05f
    val top = water - s * 0.5f + rock
    val ice = Colors.mix(p.mid, 0xFFFFFFFF.toInt(), 0.3f)
    out.polygon(ice)
    // Its foot well under the water line: it floats in the sea, not on it.
    val under = water + s * 0.32f + rock
    out.point(x - s * 1.15f, under)
    out.point(x - s * 1.1f, water + s * 0.05f + rock)
    out.point(x - s * 0.85f, top + s * 0.18f)
    out.point(x - s * 0.5f, top + s * 0.02f)
    out.point(x - s * 0.15f, top)
    out.point(x + s * 0.45f, top + s * 0.04f)
    out.point(x + s * 0.75f, top + s * 0.2f)
    out.point(x + s * 1.05f, water + s * 0.05f + rock)
    out.point(x + s * 1.1f, under)
    out.polygon(Colors.alpha(Colors.darker(ice, 0.2f), 0.7f))
    out.point(x + s * 0.45f, top + s * 0.04f); out.point(x + s * 0.75f, top + s * 0.2f); out.point(x + s * 1.05f, water + s * 0.05f + rock); out.point(x + s * 1.1f, under); out.point(x + s * 0.3f, under)
    // A penguin for company, sometimes.
    if (hash(lane.originMs.toInt(), 311) < 0.5f) penguin(x - s * 0.6f, top + s * 0.12f, s * 0.35f, 7)
    // The sea in front of it, with its waves, over the part under the water.
    out.polygon(Colors.alpha(p.near, 0.92f))
    var wx = x - s * 1.4f
    while (wx <= x + s * 1.4f) {
        out.point(wx, seaY(wx) + s * 0.06f)
        wx += s * 0.1f
    }
    out.point(x + s * 1.4f, water + s * 0.45f); out.point(x - s * 1.4f, water + s * 0.45f)
    out.polyline(Colors.alpha(p.accent, 0.45f), s * 0.025f)
    wx = x - s * 1.2f
    while (wx <= x + s * 1.2f) {
        out.point(wx, seaY(wx) + s * 0.06f)
        wx += s * 0.1f
    }
    // Standing, looking ahead; the arm rises with the music, both on a move.
    val pz = pose()
    val foot = top + s * 0.02f - pz.lift * s
    turned(pz.angle, x, foot - s * 0.45f) {
        val lw = s * 0.08f
        val hipY = foot - s * 0.42f
        val shY = foot - s * 0.8f
        out.line(color, lw, x - s * 0.05f, foot, x, hipY)
        out.line(color, lw, x + s * 0.07f, foot, x, hipY)
        out.line(color, lw * 1.2f, x, hipY, x, shY)
        head(x + s * 0.02f, shY - s * 0.15f, s * 0.12f, color)
        val arm = (0.2f + live.level * 0.8f).coerceAtLeast(pz.arms)
        out.polyline(color, lw * 0.85f)
        out.point(x, shY); out.point(x + s * 0.15f, shY + s * 0.1f - arm * s * 0.25f); out.point(x + s * 0.3f, shY + s * 0.15f - arm * s * 0.5f)
        val other = pz.arms
        out.polyline(color, lw * 0.85f)
        out.point(x, shY); out.point(x - s * 0.1f - other * s * 0.05f, shY + s * 0.15f - other * s * 0.35f); out.point(x - s * 0.1f - other * s * 0.12f, shY + s * 0.3f - other * s * 0.72f)
        // A scarf in the wind.
        out.polyline(Colors.alpha(p.accent, 0.9f), lw * 0.7f)
        out.point(x, shY + s * 0.02f); out.point(x - s * 0.2f, shY + s * 0.05f + sin(now / 300.0).toFloat() * s * 0.03f); out.point(x - s * 0.35f, shY + sin(now / 250.0).toFloat() * s * 0.05f)
    }
}

private fun Scene.ship(x: Float, s: Float, dy: Float = 0f, tricks: Boolean = true) {
    val pz = if (tricks) pose() else Pose()
    val y = h * 0.5f - heightAt(x, 1f, 40f) * h * 0.18f + sin(now / 900.0 + dy).toFloat() * s * 0.08f - pz.lift * s + dy
    turned(pz.angle, x, y) {
        val flame = s * (0.25f + live.level * 0.6f) * (0.85f + 0.15f * sin(now / 40.0).toFloat())
        out.polygon(Colors.alpha(p.accent, 0.9f))
        out.point(x - s * 0.45f, y - s * 0.08f); out.point(x - s * 0.45f - flame, y); out.point(x - s * 0.45f, y + s * 0.08f)
        out.polygon(Colors.alpha(0xFFFFF3B0.toInt(), 0.9f))
        out.point(x - s * 0.45f, y - s * 0.04f); out.point(x - s * 0.45f - flame * 0.5f, y); out.point(x - s * 0.45f, y + s * 0.04f)
        val body = Colors.lighter(p.mid, 0.55f)
        out.polygon(body)
        out.point(x - s * 0.45f, y - s * 0.14f); out.point(x + s * 0.25f, y - s * 0.12f); out.point(x + s * 0.55f, y); out.point(x + s * 0.25f, y + s * 0.12f); out.point(x - s * 0.45f, y + s * 0.14f)
        out.polygon(Colors.darker(body, 0.3f))
        out.point(x - s * 0.3f, y - s * 0.12f); out.point(x - s * 0.15f, y - s * 0.35f); out.point(x + s * 0.02f, y - s * 0.12f)
        out.polygon(Colors.darker(body, 0.3f))
        out.point(x - s * 0.3f, y + s * 0.12f); out.point(x - s * 0.15f, y + s * 0.35f); out.point(x + s * 0.02f, y + s * 0.12f)
        out.circle(Colors.alpha(p.accent, 0.9f), x + s * 0.18f, y - s * 0.02f, s * 0.06f)
    }
}

/** One of five looks for a head, none saying man or woman: by track ([seed]) and traveller ([salt]). */
private fun headStyleOf(seed: Int, salt: Int) = (hash(seed * 7 + salt, 341) * 5f).toInt().coerceAtMost(4)

/** A head of [r] at [x], [y] in the current look ([Moves.headStyle]): bare, ponytail, cap, long hair or headphones. */
private fun Scene.head(x: Float, y: Float, r: Float, color: Int) {
    val trim = farShade(color)
    when (moves.headStyle) {
        1 -> {
            // A ponytail, swinging with the steps.
            val sway = sin(now / 170.0).toFloat() * r * 0.35f
            out.polyline(color, r * 0.5f)
            out.point(x - r * 0.55f, y - r * 0.45f); out.point(x - r * 1.45f, y - r * 0.2f + sway * 0.5f); out.point(x - r * 1.75f, y + r * 0.55f + sway)
        }
        3 -> {
            // Long hair down the back.
            out.polygon(color)
            out.point(x - r * 0.9f, y - r * 0.45f); out.point(x - r * 1.15f, y + r * 1.6f); out.point(x - r * 0.15f, y + r * 1.35f); out.point(x + r * 0.15f, y)
        }
    }
    out.circle(color, x, y, r)
    when (moves.headStyle) {
        2 -> {
            // A cap with its peak ahead.
            out.polygon(trim)
            for (m in 0..8) {
                val a = PI + PI * m / 8
                out.point(x + (cos(a) * r * 1.06f).toFloat(), y - r * 0.12f + (sin(a) * r * 1.0f).toFloat())
            }
            out.line(trim, r * 0.28f, x + r * 0.2f, y - r * 0.2f, x + r * 1.55f, y - r * 0.12f)
        }
        4 -> {
            // Headphones.
            out.polyline(trim, r * 0.28f)
            for (m in 0..8) {
                val a = PI + PI * m / 8
                out.point(x - r * 0.05f + (cos(a) * r * 1.22f).toFloat(), y + (sin(a) * r * 1.22f).toFloat())
            }
            out.circle(trim, x - r * 0.08f, y + r * 0.08f, r * 0.45f)
        }
    }
    if (festiveHat) santaHat(x, y, r)
}

/** Music time of one group's run (a moment of the track where others join the traveller). */
private const val CROWD_MS = 12_000.0

/**
 * At the track's peak and in some of its loud stretches, others of the traveller's kind (runners,
 * cyclists, riders, cars, ships) come in quickly from behind, go along with it for a while, one of
 * them ahead, then fall back and are gone. More of them with more voice in the song.
 */
internal fun Scene.crowd(song: SongLook) {
    val wave = lane.wave ?: return
    val seed = song.facts?.seed ?: 0
    val kind = who(song, puff = false)
    if (kind == Traveller.ICEBERG || moves.figureColor == 0) return
    val slot = floor(posMs / CROWD_MS).toLong()
    val t = posMs - slot * CROWD_MS
    val mid = slot * CROWD_MS + CROWD_MS / 2
    if (slot < 1 || mid > wave.lengthMs - CROWD_MS) return
    val atPeak = lane.peakMs >= 0 && mid in lane.peakMs - 3000.0..lane.peakMs + 12_000.0
    val loud = lane.height(mid, 8000.0) > 0.7f
    if (!atPeak && !(loud && hash(seed + slot.toInt(), 331) < 0.45f)) return
    val voice = song.facts?.voice ?: 0.5f
    val n = (1f + voice * 2.5f + song.energy).toInt().coerceIn(1, 4)
    val s = min(h * 0.11f, w * 0.12f) * 0.94f
    val hsl = Colors.hsl(moves.figureColor)
    val beats = posMs / 60_000.0 * song.bpm
    val own = moves.headStyle
    // Room for each: horses and cars are long.
    val gap = when (kind) {
        Traveller.RIDER, Traveller.CAR -> 1.75f
        Traveller.CYCLIST -> 1.45f
        else -> 1.1f
    }
    for (k in 0 until n) {
        // In quickly at its own moment, along for a while, then falling behind.
        val enter = ((t - 300.0 - k * 650.0) / 1300.0).coerceIn(0.0, 1.0).toFloat()
        val leave = ((t - (CROWD_MS - 3400.0) - k * 350.0) / 2600.0).coerceIn(0.0, 1.0).toFloat()
        if (enter <= 0f || leave >= 1f) continue
        val ahead = k == 0 && n > 1 && hash(seed + slot.toInt(), 332) < 0.6f
        val spot = if (ahead) travellerX + s * (0.4f + gap) else travellerX - s * (0.15f + gap + gap * (if (n > 1 && hash(seed + slot.toInt(), 332) < 0.6f) k - 1 else k))
        val x = -s * 1.5f + (spot + s * 1.5f) * ease(enter) - leave * leave * (spot + s * 2.5f)
        val color = Colors.fromHsl(255, (hsl[0] + 40f * (k + 1)) % 360f, hsl[1].coerceAtLeast(0.3f), hsl[2])
        val b = beats + k * 0.27
        val phase = (b - floor(b)).toFloat()
        moves.headStyle = headStyleOf(seed + k + 1, kind.ordinal)
        when (kind) {
            Traveller.RUNNER, Traveller.WALKER -> figure(x, s, if (playing) phase else 0.25f, color, run = true, still = !playing)
            Traveller.CYCLIST -> {
                val ground = (nearY(x - s * 0.3f) + nearY(x + s * 0.32f)) / 2f
                turned(slope(x - s * 0.3f, x + s * 0.32f), x, ground) { cyclist(x, ground, s, if (playing) phase else 0f, color, lamp = look.night > 0.4f) }
            }
            Traveller.RIDER -> {
                val ground = (nearY(x - s * 0.4f) + nearY(x + s * 0.35f)) / 2f
                turned(slope(x - s * 0.4f, x + s * 0.35f), x, ground) { rider(x, ground, s, if (playing) phase else 0f, color, false) }
            }
            Traveller.CAR -> {
                val ground = (nearY(x - s * 0.32f) + nearY(x + s * 0.32f)) / 2f
                turned(slope(x - s * 0.32f, x + s * 0.32f), x, ground) { car(x, ground, s, color, if (look.night > 0.3f) 1 else 0, false) }
            }
            Traveller.SHIP -> ship(x, s * 0.8f, dy = (if (k % 2 == 0) -1f else 1f) * s * (0.9f + 0.4f * k), tricks = false)
            Traveller.ICEBERG -> {}
        }
    }
    moves.headStyle = own
}
