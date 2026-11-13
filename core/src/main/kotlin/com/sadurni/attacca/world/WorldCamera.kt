package com.sadurni.attacca.world

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

/**
 * The camera behind the traveller. On its own it follows the music: high and far in quiet
 * stretches, low and close in loud ones, and it goes round the traveller at the song's peak. A
 * finger turns it (sideways and up or down), two fingers bring it closer or further and raise or
 * lower it; some seconds after the last touch it goes back on its own.
 */
class WorldCamera {
    // Where it is now (smoothed).
    var yaw = 0f
        private set
    var pitch = 0.5f
        private set
    var distance = 16f
        private set
    var lift = 0f
        private set

    // What the listener added by hand.
    private var userYaw = 0f
    private var userPitch = 0f
    private var userZoom = 1f
    private var userLift = 0f
    private var touchedAt = -1L
    private var orbit = 0f
    private var orbitSpeed = 0f
    private var eyeYSmooth = Float.NaN
    private var lookUp = 0f
    private var bump = 0f
    private var crest = 0f

    // Now and then it goes along beside the traveller, and it turns to look at something passed.
    private var sideYaw = 0f
    private var sideTarget = 0f
    private var sideUntil = 0L
    private var nextSide = 40_000L
    private var focusYaw = 0f
    private var focusLift = 0f
    private var focusLastMs = -60_000L
    private var focusing: FloatArray? = null
    private var inTunnel = 0f
    private var pull = 1f

    /** Seconds after the last touch before it goes back on its own. */
    private val idleMs = 8_000L

    /**
     * Through the traveller's own eyes: steady (no going round, no rising and falling), looking
     * along the way ahead and turning, slowly, to what is worth a look as it comes; at the song's
     * peak towards what it shows (up into the sky, or the desert's pyramid).
     */
    var firstPerson = false

    // First person: the eye (smoothed: no bobbing), where it looks, and the glances about.
    private val fpEye = floatArrayOf(Float.NaN, 0f, 0f)
    private var fpYaw = Float.NaN
    private var fpPitch = 0f
    private var fpGlance = 0f
    private var fpGlanceUp = 0f
    private var fpGlanceUntil = 0L
    private var fpNextGlance = 20_000L
    private var fpTurn = 0f
    private var fpLift = 0f
    private var fpSlope = 0f
    private var fpDog = -1
    private var fpLean = 0f

    /** In first person, how far the eyes are turned from straight along the way (radians, as [yaw]); 0 otherwise. */
    var skyTurn = 0f
        private set

    val manual: Boolean get() = userYaw != 0f || userPitch != 0f || userZoom != 1f || userLift != 0f

    @Synchronized
    fun drag(dx: Float, dy: Float, nowMs: Long) {
        userYaw -= dx * 0.008f
        userPitch = (userPitch + dy * 0.006f).coerceIn(-1.2f, 1.2f)
        touchedAt = nowMs
    }

    @Synchronized
    fun zoom(factor: Float, nowMs: Long) {
        userZoom = (userZoom / factor).coerceIn(0.35f, 3f)
        touchedAt = nowMs
    }

    @Synchronized
    fun raise(dy: Float, nowMs: Long) {
        userLift = (userLift - dy * 0.03f).coerceIn(-6f, 30f)
        touchedAt = nowMs
    }

    /** Back on its own now. */
    @Synchronized
    fun reset() {
        touchedAt = -1L - idleMs
    }

    // The eye and what it looks at, after [update].
    var eyeX = 0f; private set
    var eyeY = 0f; private set
    var eyeZ = 0f; private set
    var atX = 0f; private set
    var atY = 0f; private set
    var atZ = 0f; private set

    /**
     * Moves on by [dtMs] following the traveller at ([tx], [ty], [tz]); [intensity] how loud it is
     * there (0 … 1); [peak] 0 … 1 near the song's peak; [ground] the land's height under a point.
     */
    @Synchronized
    fun update(
        dtMs: Long, nowMs: Long, tx: Float, ty: Float, tz: Float, heading: Float, intensity: Float, peak: Float,
        ground: (Float, Float) -> Float, open: Float = 1f, things: (Float, Float) -> Float = { _, _ -> Float.NEGATIVE_INFINITY },
        /** Something worth turning to as it is passed (x, y, z), or null. */
        sight: FloatArray? = null,
        /** 1 while the traveller goes through a tunnel: close behind, low, straight. */
        tunnel: Float = 0f,
        /** How high the eye may be at a point (a tunnel's roof). */
        ceiling: (Float, Float) -> Float = { _, _ -> Float.POSITIVE_INFINITY },
        /** The x of the way's middle at a row: in a tunnel the eye stays on the way (not in its walls). */
        wayX: ((Float) -> Float)? = null,
        /** The traveller's eyes (x, y, z, 1 when known), for [firstPerson]. */
        travellerEye: FloatArray? = null,
        /** What the song's peak shows on the land (x, y, z), or null: then the sky. */
        peakSight: FloatArray? = null,
        /** Something of the peak's show moving over the sky (the dragon), as a turn from straight ahead, or null. */
        skyThing: Float? = null,
        /** The dogs aboard with the traveller ((x, y, z) each), [nearDogCount] of them. */
        nearDogs: FloatArray? = null,
        nearDogCount: Int = 0,
        /** A board ahead in sight (the next song's sign, a cover), for the eyes to read as it comes. */
        board: FloatArray? = null,
    ) {
        if (firstPerson) {
            throughEyes(dtMs, nowMs, tx, ty, tz, heading, peak, ground, sight, tunnel, ceiling, wayX, travellerEye, peakSight, skyThing, nearDogs, nearDogCount, board)
            return
        }
        skyTurn = 0f
        fpYaw = Float.NaN
        fpEye[0] = Float.NaN
        // Into a tunnel quickly (the camera must be in it behind the traveller), out of it slowly.
        val kt = (1 - exp(-dtMs / (if (tunnel > inTunnel) 150.0 else 700.0))).toFloat()
        inTunnel += (tunnel - inTunnel) * kt
        // Along beside the traveller for a while, on the side where the land is lower (open land, not at the peak).
        if (nowMs > nextSide && sideTarget == 0f && peak < 0.05f && tunnel < 0.5f && open > 0.45f) {
            val left = ground(tx - kotlin.math.cos(heading) * 12f, tz + kotlin.math.sin(heading) * 12f)
            val right = ground(tx + kotlin.math.cos(heading) * 12f, tz - kotlin.math.sin(heading) * 12f)
            // Not into buildings or trees: the side with room, if either has.
            val leftFree = things(tx - kotlin.math.cos(heading) * 12f, tz + kotlin.math.sin(heading) * 12f) < ty + 2f
            val rightFree = things(tx + kotlin.math.cos(heading) * 12f, tz - kotlin.math.sin(heading) * 12f) < ty + 2f
            sideTarget = when {
                leftFree && (left < right || !rightFree) -> -1.15f
                rightFree -> 1.15f
                else -> 0f
            }
            if (sideTarget == 0f) nextSide = nowMs + 15_000L
            sideUntil = nowMs + 9_000L + (Math.random() * 6_000).toLong()
        }
        if (sideTarget != 0f && (nowMs > sideUntil || peak > 0.05f || tunnel > 0.5f)) {
            sideTarget = 0f
            nextSide = nowMs + 35_000L + (Math.random() * 40_000).toLong()
        }
        sideYaw += (sideTarget - sideYaw) * (1 - exp(-dtMs / 2200.0)).toFloat()
        // Turning to something big as it is passed, then back (not too often).
        if (focusing == null && sight != null && nowMs - focusLastMs > 25_000L && peak < 0.05f && tunnel < 0.5f && sideTarget == 0f) focusing = sight
        val f = focusing
        var wantFocus = 0f
        var wantLift = 0f
        if (f != null) {
            if (sight == null || kotlin.math.abs(sight[2] - f[2]) > 1f || f[2] < tz - 8f) {
                focusing = null
                focusLastMs = nowMs
            } else {
                val toward = kotlin.math.atan2(f[0] - tx, f[2] - tz)
                var d = toward - heading
                while (d > PI) d -= (2 * PI).toFloat()
                while (d < -PI) d += (2 * PI).toFloat()
                // Partly towards it: the traveller stays in sight.
                wantFocus = (d * 0.55f).coerceIn(-1.25f, 1.25f)
                wantLift = ((f[1] - ty) * 0.3f).coerceIn(0f, 4f)
            }
        }
        val kf = (1 - exp(-dtMs / 1600.0)).toFloat()
        focusYaw += (wantFocus - focusYaw) * kf
        focusLift += (wantLift - focusLift) * kf
        // What it would do on its own.
        // On its own it rises and falls slowly along the way, closer and further; at the peak it
        // comes down near the ground and looks up, so the sky's show is in sight.
        val drift = (sin(nowMs / 21000.0) * 0.14 + sin(nowMs / 8300.0) * 0.07).toFloat()
        // At the peak it goes round from further off where the land is open, close in a narrow valley.
        val peakDistance = 8f + 15f * open
        var autoDistance = (24f - 9f * intensity + 4f * sin(nowMs / 17000.0).toFloat()) * (1 - peak) + peakDistance * peak
        // Beside the traveller a little closer; in a tunnel close behind.
        autoDistance *= 1f - 0.2f * kotlin.math.abs(sideYaw)
        autoDistance += (4.6f - autoDistance) * inTunnel
        // Low enough that the sky with the sun (or the moon) is in sight.
        var autoPitch = (0.26f - 0.1f * intensity + drift * 0.6f) * (1 - peak) + (0.04f + 0.08f * (1 - open)) * peak
        autoPitch += (0.12f - autoPitch) * inTunnel
        // At the peak it goes round, slowly and gently (speeding up and slowing down softly);
        // after it, on to the nearest whole turn (behind again).
        val spin = peak > 0.05f && inTunnel < 0.5f
        orbitSpeed += ((if (spin) (2 * PI / ORBIT_MS * peak).toFloat() else 0f) - orbitSpeed) * (1 - exp(-dtMs / 1800.0)).toFloat()
        orbit += orbitSpeed * dtMs
        if (!spin) orbit += ((Math.round(orbit / (2 * PI)) * 2 * PI).toFloat() - orbit) * (1 - exp(-dtMs / 2600.0)).toFloat()
        // Behind the traveller as the way turns (a little late, as a camera held by hand).
        val autoYaw = heading + (sin(nowMs / 9000.0) * 0.18).toFloat() * (1 - inTunnel) + orbit + (sideYaw + focusYaw) * (1 - inTunnel)
        // A while after a touch, the hand's part fades away.
        if (touchedAt >= 0 && nowMs - touchedAt > idleMs || touchedAt < -1) {
            val k = (1 - exp(-dtMs / 900.0)).toFloat()
            userYaw -= userYaw * k
            userPitch -= userPitch * k
            userZoom += (1f - userZoom) * k
            userLift -= userLift * k
            if (kotlin.math.abs(userYaw) < 0.002f && kotlin.math.abs(userPitch) < 0.002f && kotlin.math.abs(userZoom - 1) < 0.002f && kotlin.math.abs(userLift) < 0.02f) {
                userYaw = 0f; userPitch = 0f; userZoom = 1f; userLift = 0f
            }
        }
        val k = (1 - exp(-dtMs / 1400.0)).toFloat()
        lookUp += (peak - lookUp) * k
        yaw += (autoYaw - yaw) * k
        pitch += (autoPitch - pitch) * k
        distance += (autoDistance - distance) * k
        val y = yaw + userYaw
        val p = (pitch + userPitch).coerceIn(0.04f, 1.35f)
        val d = distance * userZoom
        // Something tall where the eye would be (a tower, a house): closer to the traveller rather than over it.
        var want = 1f
        for (f in floatArrayOf(1f, 0.75f, 0.55f, 0.4f, 0.28f)) {
            want = f
            val hh = d * cos(p) * f
            val ey = ty + 1.4f + d * f * sin(p) + userLift
            // Clear at the eye and along the line to the traveller (a tree, a house in between).
            var clear = true
            for (u in floatArrayOf(0f, 0.3f, 0.6f)) {
                val top = things(tx - sin(y) * hh * (1 - u), tz - cos(y) * hh * (1 - u))
                if (top > ey + (ty + 1.2f - ey) * u - 0.4f) { clear = false; break }
            }
            if (clear) break
        }
        // (Going round, it comes closer and goes back gently too.)
        val swirl = (orbitSpeed / (2 * PI / ORBIT_MS).toFloat()).coerceIn(0f, 1f)
        pull += (want - pull) * (1 - exp(-dtMs / (if (want < pull) 180.0 + 500.0 * swirl else 1400.0))).toFloat()
        val horizontal = d * cos(p) * pull
        atX = tx + sin(y) * 3f
        atY = ty + 1.4f + 4.5f * lookUp + focusLift
        atZ = tz + cos(y) * 3f
        eyeX = tx - sin(y) * horizontal
        eyeZ = tz - cos(y) * horizontal
        eyeY = ty + 1.4f + d * pull * sin(p) + userLift
        if (wayX != null && inTunnel > 0.01f) {
            // In a tunnel, on the way straight behind the traveller, looking along it, whatever its bends.
            eyeZ += (tz - horizontal - eyeZ) * inTunnel
            eyeX += (wayX(eyeZ) - eyeX) * inTunnel
            atZ += (tz + 3f - atZ) * inTunnel
            atX += (wayX(atZ) - atX) * inTunnel
        }
        // Never inside the land.
        eyeY = max(eyeY, ground(eyeX, eyeZ) + 1.5f)
        // Over a rise between the eye and the traveller (a crest of the way, a hill's shoulder):
        // up as long as it is there, so the traveller stays in sight.
        var need = 0f
        if (inTunnel < 0.5f) {
            val ex = eyeX
            val ez = eyeZ
            val ey = eyeY
            val gy = ty + 1.0f
            for (k in 1..7) {
                val u = k / 8f
                val px = ex + (tx - ex) * u
                val pz = ez + (tz - ez) * u
                val over = ground(px, pz) + 0.6f - (ey + (gy - ey) * u)
                if (over > 0f) need = max(need, over / (1 - u))
            }
        }
        crest += (need - crest) * (1 - exp(-dtMs / (if (need > crest) 260.0 else 1600.0))).toFloat()
        eyeY += crest
        // Nor inside a house or a tree: over it, rising quickly and coming down slowly.
        val over = (things(eyeX, eyeZ) + 1f - eyeY).coerceAtLeast(0f)
        bump += (over - bump) * (1 - exp(-dtMs / (if (over > bump) 110.0 else 900.0))).toFloat()
        eyeY += bump
        // Going round, its height follows the land softly (no sudden ups and downs that make one dizzy).
        if (eyeYSmooth.isNaN()) eyeYSmooth = eyeY
        eyeYSmooth += (eyeY - eyeYSmooth) * (1 - exp(-dtMs / (if (eyeY > eyeYSmooth) 420.0 else 1100.0))).toFloat()
        eyeY += (eyeYSmooth - eyeY) * swirl
        // Under a tunnel's roof (or the water's surface), but never under the ground.
        eyeY = max(kotlin.math.min(eyeY, ceiling(eyeX, eyeZ)), ground(eyeX, eyeZ) + 1.0f)
        lift = userLift
    }

    /** The first person's eye: see [firstPerson]. */
    private fun throughEyes(
        dtMs: Long, nowMs: Long, tx: Float, ty: Float, tz: Float, heading: Float, peak: Float,
        ground: (Float, Float) -> Float, sight: FloatArray?, tunnel: Float, ceiling: (Float, Float) -> Float,
        wayX: ((Float) -> Float)?, travellerEye: FloatArray?, peakSight: FloatArray?,
        skyThing: Float?, nearDogs: FloatArray?, nearDogCount: Int, board: FloatArray?,
    ) {
        fun ease(ms: Double) = (1 - exp(-dtMs / ms)).toFloat()
        fun wrap(a: Float): Float {
            var d = a
            while (d > PI) d -= (2 * PI).toFloat()
            while (d < -PI) d += (2 * PI).toFloat()
            return d
        }
        val kt = ease(if (tunnel > inTunnel) 150.0 else 700.0)
        inTunnel += (tunnel - inTunnel) * kt
        // The eye: the traveller's own (or about where it would be), along quickly, up and down
        // slowly (the steps and the bumps of the way are not felt).
        val known = travellerEye != null && travellerEye[3] > 0f
        val wx = if (known) travellerEye!![0] else tx
        val wy = if (known) travellerEye!![1] else ty + 1.6f
        val wz = if (known) travellerEye!![2] else tz
        if (fpEye[0].isNaN() || kotlin.math.abs(wz - fpEye[2]) > 6f) {
            fpEye[0] = wx; fpEye[1] = wy; fpEye[2] = wz
        }
        // (Along exactly: any lag would leave the eye behind the seat, among the dogs in the back.)
        fpEye[0] = wx
        fpEye[2] = wz
        // (Mounted or in a vehicle, up and down with it at once: the head, the wheel or the bars
        // ahead stay where they are before the eyes, never hitting them going up a slope.)
        val mounted = known && !travellerEye!![4].isNaN()
        if (mounted) fpEye[1] = wy
        else fpEye[1] += (wy - fpEye[1]) * ease(if (wy > fpEye[1]) 320.0 else 520.0)
        // Along the way some rows ahead, so a bend is seen coming and the look turns into it softly.
        val ahead = 9f
        // (Mounted or in a vehicle: where it points, more closely.)
        val mount = travellerEye?.getOrNull(4)?.takeIf { known && !it.isNaN() }
        val wayYaw = mount ?: if (wayX != null) kotlin.math.atan2(wayX(tz + ahead) - wayX(tz), ahead) else heading
        if (fpYaw.isNaN()) fpYaw = wayYaw
        fpYaw += wrap(wayYaw - fpYaw) * ease(if (mount != null) 500.0 else 1200.0)
        // Something worth a look as it comes (a castle, a geyser, a herd): turned to, the whole way, slowly.
        val calm = tunnel < 0.5f && peak < 0.05f
        if (focusing == null && sight != null && nowMs - focusLastMs > 10_000L && calm) focusing = sight
        val f = focusing
        var wantTurn = 0f
        var wantLift = 0f
        if (f != null) {
            if (sight == null || kotlin.math.abs(sight[2] - f[2]) > 1f || f[2] < tz - 4f || !calm) {
                focusing = null
                focusLastMs = nowMs
            } else {
                val toward = kotlin.math.atan2(f[0] - fpEye[0], f[2] - fpEye[2])
                wantTurn = wrap(toward - fpYaw).coerceIn(-1.5f, 1.5f)
                val flat = kotlin.math.hypot(f[0] - fpEye[0], f[2] - fpEye[2]).coerceAtLeast(1f)
                wantLift = kotlin.math.atan((f[1] - fpEye[1]) / flat).coerceIn(-0.3f, 0.45f)
            }
        }
        // Nothing calling: now and then a look about, to one side and the other, a little up.
        if (f == null && calm && nowMs > fpNextGlance) {
            val side = if (Math.random() < 0.5) -1f else 1f
            fpGlance = side * (0.45f + Math.random().toFloat() * 0.45f)
            fpGlanceUp = Math.random().toFloat() * 0.12f
            // In a vehicle (or on the iceberg), as often as not to one of the dogs aboard.
            fpDog = if (nearDogs != null && nearDogCount > 0 && Math.random() < 0.6) (Math.random() * nearDogCount).toInt().coerceAtMost(nearDogCount - 1) else -1
            fpGlanceUntil = nowMs + 3_500L + (Math.random() * 3_000).toLong()
            fpNextGlance = fpGlanceUntil + 12_000L + (Math.random() * 16_000).toLong()
        }
        if (nowMs > fpGlanceUntil || f != null || !calm) {
            fpGlance = 0f
            fpGlanceUp = 0f
            fpDog = -1
        }
        if (f == null) {
            wantTurn = fpGlance
            wantLift = fpGlanceUp
            if (fpDog >= 0 && nearDogs != null && fpDog < nearDogCount) {
                // Down at the dog (it may sit behind: then over the shoulder).
                val dx = nearDogs[fpDog * 3] - fpEye[0]
                val dy = nearDogs[fpDog * 3 + 1] - 0.25f - (fpEye[1] + 0.3f)
                val dz = nearDogs[fpDog * 3 + 2] - fpEye[2]
                wantTurn = wrap(kotlin.math.atan2(dx, dz) - fpYaw).coerceIn(-2.4f, 2.4f)
                wantLift = kotlin.math.atan2(dy, kotlin.math.hypot(dx, dz).coerceAtLeast(0.3f)).coerceIn(-0.55f, 0.2f) - (fpPitch + fpSlope)
            }
        }
        // A board coming (the next song's sign, a cover): read as it comes, before anything else.
        if (board != null && calm) {
            val bx = board[0] - fpEye[0]
            val bz = board[2] - fpEye[2]
            wantTurn = wrap(kotlin.math.atan2(bx, bz) - fpYaw).coerceIn(-1.2f, 1.2f)
            wantLift = kotlin.math.atan2(board[1] - fpEye[1], kotlin.math.hypot(bx, bz).coerceAtLeast(1f)).coerceIn(-0.2f, 0.4f) - fpSlope
            fpDog = -1
        }
        // At the peak: towards what it shows on the land, or up into the sky's show, looking slowly about it.
        if (peak > 0.05f && tunnel < 0.5f) {
            val ps = peakSight
            if (ps != null) {
                wantTurn = wrap(kotlin.math.atan2(ps[0] - fpEye[0], ps[2] - fpEye[2]) - fpYaw).coerceIn(-1.6f, 1.6f) * peak
                val flat = kotlin.math.hypot(ps[0] - fpEye[0], ps[2] - fpEye[2]).coerceAtLeast(1f)
                wantLift = kotlin.math.atan((ps[1] - fpEye[1]) / flat).coerceIn(0f, 0.5f) * peak
            } else if (skyThing != null) {
                // Following what flies over the sky (the dragon), as far as the eyes turn.
                wantTurn = (skyThing * 0.85f).coerceIn(-1.1f, 1.1f) * peak
                wantLift = 0.3f * peak
            } else {
                wantTurn = (sin(nowMs / 7000.0) * 0.45).toFloat() * peak
                wantLift = 0.38f * peak
            }
        }
        // In a tunnel: straight along it.
        wantTurn *= 1 - inTunnel
        wantLift *= 1 - inTunnel
        val kf = ease(if (skyThing != null && peak > 0.05f) 650.0 else if (fpDog >= 0) 1200.0 else 2000.0)
        fpTurn += (wantTurn - fpTurn) * kf
        fpLift += (wantLift - fpLift) * kf
        // A little down along the way (it and the dogs ahead in sight), up at the peak.
        fpPitch += (-0.09f - fpPitch) * ease(1500.0)
        // Up a rise the eyes go up with the way (down one, a little down).
        val wantSlope = if (wayX != null) {
            val a = tz + 1f
            val b = tz + 9f
            val rise = (ground(wayX(b), b) - ground(wayX(a), a)) / 8f
            val s = kotlin.math.atan(rise)
            (if (s > 0f) s * 0.85f else s * 0.5f).coerceIn(-0.3f, 0.5f)
        } else 0f
        fpSlope += (wantSlope - fpSlope) * ease(1100.0)
        // A finger turns the look (as it turns the camera behind); it goes back on its own after a while.
        if (touchedAt >= 0 && nowMs - touchedAt > idleMs || touchedAt < -1) {
            val k = ease(900.0)
            userYaw -= userYaw * k
            userPitch -= userPitch * k
            if (kotlin.math.abs(userYaw) < 0.002f && kotlin.math.abs(userPitch) < 0.002f) { userYaw = 0f; userPitch = 0f }
            userZoom = 1f; userLift = 0f
        }
        val y = fpYaw + fpTurn + userYaw
        val p = (fpPitch + fpSlope + fpLift - userPitch * 0.6f).coerceIn(-0.9f, 1.0f)
        skyTurn = fpTurn + userYaw
        eyeX = fpEye[0]; eyeY = fpEye[1]; eyeZ = fpEye[2]
        // Looking at a dog aboard, the head rises a little: seen whole, not in the face.
        fpLean += ((if (fpDog >= 0) 1f else 0f) - fpLean) * ease(900.0)
        if (fpLean > 0.001f) {
            eyeY += 0.35f * fpLean
        }
        if (wayX != null && inTunnel > 0.01f) eyeX += (wayX(eyeZ) - eyeX) * inTunnel * 0.5f
        eyeY = max(kotlin.math.min(eyeY, ceiling(eyeX, eyeZ) + 0.6f), ground(eyeX, eyeZ) + 0.5f)
        atX = eyeX + sin(y) * cos(p) * 10f
        atY = eyeY + sin(p) * 10f
        atZ = eyeZ + cos(y) * cos(p) * 10f
        yaw = y; pitch = p; distance = 0f; lift = 0f
    }

    private companion object {
        /** One whole turn round the traveller at the song's peak (ms). */
        const val ORBIT_MS = 12000.0
    }
}
