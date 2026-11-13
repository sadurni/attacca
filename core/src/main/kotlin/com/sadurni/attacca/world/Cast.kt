package com.sadurni.attacca.world

import com.sadurni.attacca.diorama.Dog
import com.sadurni.attacca.diorama.Traveller
import com.sadurni.attacca.diorama.World
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** One figure to draw this frame: its rig, where it is ([base]) and how it is posed. */
class Draw(val rig: Rig, val base: FloatArray, val pose: Pose, val alpha: Float = 1f)

/** A move of the traveller, as in O'rama. */
enum class Move(val ms: Long) {
    NONE(0), HOP(380), JUMP(560), DOUBLE(800), FLIP(820), CARTWHEEL(950), SPIN(800), ARMS(1500), WHEELIE(1100), REAR(1000), ROLL(1000), LOOP(1500),
}

/**
 * Who goes through one song's land and how: the traveller (people who change at relays), on foot
 * or on the song's vehicle, and the four dogs. At the song's start the vehicle waits a little
 * ahead (a horse, a bike, a car, a ship on the ground, an iceberg at the shore): the traveller
 * walks up and gets on, and the dogs too where there is room (car, ship, iceberg); on foot,
 * someone waits there and takes over. Further on, now and then, someone else waits by the way
 * for a relay. Logs, rocks or cones across the way are jumped; strong beats bring moves.
 */
class Cast(private val land: WorldLand, val kind: Traveller, private val firstPerson: Int, private val seed: Int, private val arrives: Boolean = false) {

    companion object {
        /** Where the vehicle (or the first relay) waits from the song's start, in rows. */
        const val START = 14f

        /** How long getting on or off takes, in rows. */
        private const val BOARD = 5f

        /** Rows before the song's end where the traveller gets off (when the next song goes on otherwise). */
        const val DISMOUNT = 14f

        private const val COMPANY = 0
        private const val AHEAD = 1
        private const val STOP = 2
        private const val SIDE = 3
        private const val BOLT = 4
        private const val RUSH = 5

        /** Each dog's place about the traveller (sideways, ahead or behind), in pairs. */
        private val PLACES = floatArrayOf(-0.95f, -1.7f, 1.05f, -2.7f, -0.75f, -3.9f, 0.95f, 1.5f)

        /** How far ahead the dogs go when seen through the traveller's eyes (rows). */
        private const val DOGS_LEAD = 7f

        /** How close two dogs come at most (rows): they never go through one another. */
        private const val DOG_ROOM = 0.8f

        /** Rows the people at a relay walk onto and off the way. */
        private const val RELAY_WALK = 4f

        /** Rows between relays along a song. */
        private const val RELAY_EVERY = 380f
    }

    private val accent = land.look.palette.accent

    /** Where the vehicle (or the first relay) waits: see [WorldLand.startZ]. */
    private val start = land.startZ
    private val onFoot = kind == Traveller.WALKER || kind == Traveller.RUNNER
    private val dogsAboard = kind == Traveller.CAR || kind == Traveller.SHIP || kind == Traveller.ICEBERG || kind == Traveller.CANOE || kind == Traveller.SUBMARINE
    private val floats = kind == Traveller.ICEBERG || kind == Traveller.CANOE

    /**
     * The relays along the song (rows): the song's start on foot, then every so often, each where
     * the one waiting can stand by the way on firm ground (moved on a little to the next such row,
     * or none): never in the water, on a bridge or in a tunnel. None aboard what floats (the
     * iceberg, a canoe, the ship, the submarine): nobody waits in the water.
     */
    val relays: List<Float> = if (kind == Traveller.SUBMARINE || kind == Traveller.ICEBERG || kind == Traveller.CANOE || kind == Traveller.SHIP) emptyList() else buildList {
        if (onFoot) add(start)
        var z = start + 170f
        val end = land.zAt((land.wave?.endMs ?: 0L).toDouble()) - 70f
        while (z < end) {
            var r = z
            while (r < z + 80f && r < end && !firmForRelay(r)) r += 2f
            if (r < end && firmForRelay(r)) add(r)
            z += RELAY_EVERY
        }
    }

    /** Whether at row [z] the way and both its sides are dry, firm ground (no place there, no water, no drop). */
    private fun firmForRelay(z: Float): Boolean {
        for (p in land.places) if (z > p.z0 - 8f && z < p.z1 + 8f) return false
        val path = land.pathAt(z)
        if (path < WorldLand.WATER + 0.3f) return false
        val h = land.headingAt(z)
        val cx = land.centerAt(z)
        for (dz in floatArrayOf(-RELAY_WALK, 0f, RELAY_WALK)) for (side in floatArrayOf(-1.9f, 1.9f)) {
            val x = cx + side * cos(h)
            val y = land.heightAt(x, z + dz - side * sin(h))
            if (y < WorldLand.WATER + 0.25f || abs(y - path) > 1.0f) return false
        }
        return true
    }

    /** Where something lies across the way (rows), and what: where the song strikes harder. */
    val obstacles: List<Float> = run {
        if (kind == Traveller.SHIP || kind == Traveller.ICEBERG || kind == Traveller.CANOE || kind == Traveller.SUBMARINE ||
            land.look.world == World.SPACE || land.look.world == World.ICE || land.look.world == World.AMAZON || land.look.world == World.REEF
        ) return@run emptyList()
        val end = land.zAt((land.wave?.endMs ?: 0L).toDouble()).toInt() - 20
        val out = ArrayList<Float>()
        var last = -1000f
        var z = (start + 22).toInt()
        while (z < end) {
            val rise = land.intensity(z + 2) - land.intensity(z - 6)
            if (rise > 0.22f && z - last > 44 && relays.none { abs(it - z) < 16 }) {
                out += z.toFloat()
                last = z.toFloat()
            }
            z++
        }
        out
    }

    val obstacleMesh: Mesh? = if (obstacles.isEmpty()) null else when (land.look.world) {
        World.CITY, World.NEON -> Obstacles.cones()
        World.CASTLE -> Obstacles.rocks()
        else -> if (seed % 2 == 0) Obstacles.log() else Obstacles.rocks()
    }

    /** Christmas days: the traveller, the people and the dogs wear Santa hats. */
    var santa = false
        set(v) {
            if (field != v) {
                field = v
                peopleRigs.clear()
                travellerRigs.clear()
                dogsNow = null
            }
        }

    private val peopleRigs = HashMap<Int, Rig>()
    /** Under the sea everyone wears a diving helmet. */
    // (And in space a glass bowl too: everyone and the dogs, as in O'rama.)
    private val underSea = (land.look.world == World.REEF && kind != Traveller.SUBMARINE) || land.look.world == World.SPACE
    private fun personRig(k: Int) = peopleRigs.getOrPut(k) { Human.rig(Person.of(7, k, accent), underSea, santa) }

    /** Whoever is the traveller now wears the song's accent colour (shirt and hat), as in O'rama. */
    private val travellerRigs = HashMap<Int, Rig>()
    private fun travellerRig(k: Int) = travellerRigs.getOrPut(k) {
        val p = Person.of(7, k, accent)
        val a = accent or (0xFF shl 24)
        Human.rig(Person(a, p.trousers, p.skin, p.hair, p.hat?.let { scale(a, 0.72f) }, p.longHair, p.pack), underSea, santa)
    }

    private var dogsNow: List<Pair<Rig, Quad.Shape>>? = null
    private val dogs: List<Pair<Rig, Quad.Shape>> get() = dogsNow ?: Dog.entries.map { Quad.dog(it, underSea, santa, leds) }.also { dogsNow = it }

    /** In the dark the dogs' collars light up (little LEDs). */
    private var leds = false
    // What is ridden: a horse, a camel or an elephant (with the horse's bones).
    private val horse = when (kind) {
        Traveller.RIDER -> Quad.horse()
        Traveller.CAMEL -> Mounts.camel()
        Traveller.ELEPHANT -> Mounts.elephant(accent)
        else -> null
    }
    private val vehicle: Vehicles.Built? = when (kind) {
        Traveller.CYCLIST -> Vehicles.bike(accent or (0xFF shl 24))
        Traveller.CAR -> Vehicles.car(accent or (0xFF shl 24))
        Traveller.SHIP -> Vehicles.ship(accent or (0xFF shl 24))
        Traveller.ICEBERG -> Vehicles.iceberg()
        Traveller.CANOE -> Vehicles.canoe(accent or (0xFF shl 24))
        Traveller.SUBMARINE -> Vehicles.submarine(accent or (0xFF shl 24))
        else -> null
    }

    /** Rigs of the obstacles, the same one each. */
    private val obstacleRig = obstacleMesh?.let { Rig(listOf(Bone(it, -1, 0f, 0f, 0f))) }

    // Poses kept between frames (one per figure).
    private val poses = HashMap<Any, Pose>()
    private fun poseOf(key: Any, rig: Rig): Pose {
        // (A key may come to another rig, another animal across a change of world: a pose of its own size then.)
        val p = run { poses[key]?.takeIf { it.pitch.size == rig.bones.size } ?: rig.pose().also { poses[key] = it } }
        p.reset()
        return p
    }

    // The current move.
    private var move = Move.NONE
    private var moveAt = 0L

    /** Number of relays passed at [z]: who the traveller is there. */
    fun personAt(z: Float) = firstPerson + relays.count { it <= z }

    /** The people met so far up to the end of this song (for the next song's first). */
    fun lastPerson(): Int = firstPerson + relays.size

    /**
     * A strong beat (strength 0 … ~1.3) at [nowMs] with the song [intensity]: maybe a move, as
     * O'rama picks them; [q] is a number 0 … 1 to choose by.
     */
    fun beat(strength: Float, intensity: Float, nowMs: Long, tz: Float, q: Float) {
        if (nowMs - moveAt < move.ms + 3500) return
        if (!free(tz)) return
        val big = strength > 0.9f && intensity > 0.55f
        if (!big && q > 0.45f) return
        start(big, q, nowMs)
    }

    /** A tap on the traveller: a move now (a big one now and then), unless one is going on or it is getting on or off. */
    fun tap(nowMs: Long, tz: Float, q: Float): Boolean {
        if (nowMs - moveAt < move.ms + 150 || !free(tz)) return false
        start(q < 0.5f, q * 2f % 1f, nowMs)
        return true
    }

    private fun free(tz: Float) =
        relays.none { tz in it - 4f..it + BOARD + 4f } && (tz >= start + BOARD + 2 || arrives) && obstacles.none { tz in it - 6f..it + 4f } &&
            !(leaves && tz > land.endZ - DISMOUNT - 4f)

    private fun start(big: Boolean, q: Float, nowMs: Long) {
        move = when (kind) {
            Traveller.RUNNER -> when { big && q < 0.25f -> Move.FLIP; big && q < 0.4f -> Move.CARTWHEEL; q < 0.3f -> Move.SPIN; q < 0.6f -> Move.JUMP; else -> Move.DOUBLE }
            Traveller.WALKER -> when { big && q < 0.15f -> Move.CARTWHEEL; q < 0.35f -> Move.HOP; q < 0.65f -> Move.ARMS; else -> Move.SPIN }
            Traveller.RIDER -> if (big && q < 0.45f) Move.REAR else Move.JUMP
            Traveller.CYCLIST -> when { big && q < 0.2f -> Move.FLIP; q < 0.55f -> Move.WHEELIE; else -> Move.HOP }
            Traveller.CAR -> when { big && q < 0.15f -> Move.ROLL; q < 0.5f -> Move.WHEELIE; else -> Move.HOP }
            Traveller.ICEBERG -> when { big && q < 0.2f -> Move.FLIP; q < 0.6f -> Move.ARMS; else -> Move.SPIN }
            Traveller.SHIP, Traveller.SUBMARINE -> if (big && q < 0.45f) Move.LOOP else Move.ROLL
            Traveller.CAMEL -> if (big && q < 0.35f) Move.REAR else Move.JUMP
            Traveller.ELEPHANT -> if (big && q < 0.45f) Move.REAR else Move.ARMS
            Traveller.CANOE -> if (q < 0.5f) Move.ARMS else Move.HOP
        }
        moveAt = nowMs
    }

    /**
     * The top of a person standing at a relay within [margin] of ([x], [z]), or minus infinity:
     * the camera passes over them, not through them.
     */
    fun topNear(x: Float, z: Float, margin: Float = 1.4f): Float {
        var top = Float.NEGATIVE_INFINITY
        for ((i, r) in relays.withIndex()) {
            if (kotlin.math.abs(r - z) > 4f) continue
            val heading = land.headingAt(r)
            val side = if (i % 2 == 0) 1.9f else -1.9f
            val sx = land.centerAt(r) + side * cos(heading)
            val sz = r - side * sin(heading)
            if (kotlin.math.hypot(x - sx, z - sz) < margin) top = max(top, land.heightAt(sx, sz) + 2.1f)
        }
        return top
    }

    // ---- The four dogs' own minds: each goes its way for a while, as in O'rama ----

    private class Pup(val homeX: Float, val homeZ: Float) {
        var ox = homeX
        var oz = homeZ
        var tx = homeX
        var tz = homeZ
        var mode = COMPANY
        var until = 0L
        var phase = 0f
        var yaw = 0f
        var speed = 0f
        var lateral = 0f
        /** The heading it goes by (eased: a sharp turn is rounded, not snapped to). */
        var heading = Float.NaN
        /** Where it is drawn about the traveller: after its place, never faster than a dog runs (no leaps from one spot to another). */
        var sx = Float.NaN
        var sz = Float.NaN
    }

    /** [now] turned towards [want] by [k] (0 … 1) the short way round; [want] itself at first. */
    private fun turnTo(now: Float, want: Float, k: Float): Float {
        if (now.isNaN()) return want
        var d = want - now
        while (d > PI) d -= (2 * PI).toFloat()
        while (d < -PI) d += (2 * PI).toFloat()
        return now + d * k
    }

    // The traveller's and the vehicle's headings, eased the same way.
    private var walkHeading = Float.NaN
    private var rideHeading = Float.NaN
    private var neckYaw = 0f

    private val pups = List(4) { i -> Pup(PLACES[i * 2], PLACES[i * 2 + 1]) }

    /**
     * Seen through the traveller's own eyes: the traveller is not drawn (nor the head torch), the
     * dogs go on ahead, in sight, and [eye] is where the traveller's eyes are.
     */
    var throughEyes = false

    /**
     * The traveller's eyes (x, y, z) after [frame], 1 in [3] when known (0 inside the submarine),
     * and in [4] where the mount or the vehicle points (NaN on foot).
     */
    val eye = FloatArray(5)

    /** The dogs aboard with the traveller after [frame] ((x, y, z) of each head), [dogsNearCount] of them. */
    val dogsNear = FloatArray(12)
    var dogsNearCount = 0
        private set

    /** How far ahead the dogs go now (eased towards [DOGS_LEAD] in first person, 0 otherwise). */
    private var lead = 0f

    /** When this cast was first shown, and how far its waiting vehicle has come in (0 … 1). */
    private var shownMs = Long.MIN_VALUE
    private var appear = 1f

    /** Shown from [nowMs] on (its first frame may have been made aside, before it was shown). */
    fun shownFrom(nowMs: Long) {
        shownMs = nowMs
    }

    /** The dogs go on where and as they were with [old] (a new song, or the same one's land again): no jump. */
    fun carryDogsFrom(old: Cast?) {
        if (old == null || old.dogsAboard || dogsAboard) return
        for (i in pups.indices) {
            val a = old.pups[i]
            val b = pups[i]
            b.ox = a.ox; b.oz = a.oz; b.tx = a.tx; b.tz = a.tz
            b.mode = a.mode; b.until = a.until; b.phase = a.phase; b.yaw = a.yaw; b.speed = a.speed; b.lateral = a.lateral
            b.sx = a.sx; b.sz = a.sz; b.heading = a.heading
        }
    }
    private val rnd = java.util.Random(seed * 31L + 7)
    private var lastMs = -1L

    /** A new whim for [p]: keep company, run ahead, stop to sniff, go off to a side, or bolt far ahead and wait. */
    private fun choose(p: Pup, nowMs: Long) {
        val r = rnd.nextFloat()
        fun span(a: Int, b: Int) = nowMs + a + rnd.nextInt(b - a)
        when {
            r < 0.42f -> { p.mode = COMPANY; p.tx = p.homeX + (rnd.nextFloat() - 0.5f) * 0.8f; p.tz = p.homeZ + (rnd.nextFloat() - 0.5f) * 1.4f; p.until = span(5000, 12000) }
            r < 0.57f -> { p.mode = AHEAD; p.tz = 4f + rnd.nextFloat() * 5f; p.tx = (if (rnd.nextBoolean()) 1f else -1f) * (0.6f + rnd.nextFloat() * 1.3f); p.until = span(4000, 7000) }
            r < 0.72f -> { p.mode = STOP; p.tz = -(3f + rnd.nextFloat() * 4f); p.tx = p.ox; p.until = span(8000, 10000) }
            r < 0.86f -> { p.mode = SIDE; p.tx = (if (p.homeX > 0) 1f else -1f) * (2.6f + rnd.nextFloat() * 1.6f); p.tz = p.homeZ + rnd.nextFloat(); p.until = span(3500, 6000) }
            else -> { p.mode = BOLT; p.tz = 12f + rnd.nextFloat() * 6f; p.tx = (if (rnd.nextBoolean()) 1f else -1f) * (0.5f + rnd.nextFloat() * 2f); p.until = span(15000, 20000) }
        }
    }

    /** Moves [p] on by [dt] s with the traveller going [v] rows a second. */
    private fun think(p: Pup, dt: Float, v: Float, nowMs: Long) {
        if (nowMs >= p.until && p.mode != RUSH) choose(p, nowMs)
        var stepZ: Float
        var stepX: Float
        if (p.mode == STOP) {
            // Standing still where it is (sniffing): the traveller goes on; once far enough behind, it rushes back.
            stepZ = -v * dt
            stepX = 0f
            if (p.oz + stepZ < p.tz) { p.mode = RUSH; p.tx = p.homeX; p.tz = p.homeZ; p.until = nowMs + 8000 }
        } else {
            val fast = p.mode == RUSH || p.mode == BOLT
            val maxZ = if (fast) 7.5f else 3.2f
            val dz = p.tz - p.oz
            stepZ = (dz * min(1f, dt * 1.6f)).coerceIn(-maxZ * dt, maxZ * dt)
            // Never backwards along the way: at worst it stands.
            stepZ = max(stepZ, -v * dt)
            val dx = p.tx - p.ox
            stepX = (dx * min(1f, dt * 1.8f)).coerceIn(-2.2f * dt, 2.2f * dt)
            if (abs(dz) < 0.4f && abs(dx) < 0.3f) {
                when (p.mode) {
                    RUSH -> { p.mode = COMPANY; p.until = nowMs + 4000 + rnd.nextInt(5000) }
                    // Far ahead: it waits there, watching, until the traveller comes by.
                    BOLT -> { p.mode = STOP; p.tz = -(2f + rnd.nextFloat() * 3f); p.until = nowMs + 30000 }
                }
            }
        }
        p.oz += stepZ
        p.ox += stepX
        // Not under the traveller's feet.
        if (abs(p.oz) < 1.0f && abs(p.ox) < 0.7f) p.ox = if (p.ox >= 0f) 0.7f else -0.7f
        p.ox = p.ox.coerceIn(-4.6f, 4.6f)
        p.speed = if (dt > 0f) v + stepZ / dt else 0f
        p.lateral = if (dt > 0f) stepX / dt else 0f
        val target = kotlin.math.atan2(p.lateral, max(p.speed, 0.6f))
        p.yaw += (target - p.yaw) * min(1f, dt * 5f)
    }

    /** How far from the way's middle a dog may go at row [z] towards [ox]'s side: not off a bridge, into the water or lava, nor down a drop. */
    private fun safeReach(z: Float, ox: Float): Float {
        val want = kotlin.math.abs(ox)
        if (pierAt(z) > Float.NEGATIVE_INFINITY) return min(want, 0.6f)
        if (want < 0.5f) return want
        for (p in land.places) {
            if (p.kind == PlaceKind.BRIDGE && z > p.mid - Places.RIVER - 6f && z < p.mid + Places.RIVER + 6f) return 1.15f
            if (p.kind == PlaceKind.TUNNEL && z > p.z0 - 1f && z < p.z1 + 1f) return 1.8f
        }
        if (land.look.world == World.REEF) return want
        val h = land.headingAt(z)
        val cx = land.centerAt(z)
        val path = land.pathAt(z)
        val side = if (ox >= 0f) 1f else -1f
        var d = 0.4f
        while (d <= want) {
            val y = land.heightAt(cx + side * d * cos(h), z - side * d * sin(h))
            if (y < WorldLand.WATER + 0.15f || kotlin.math.abs(y - path) > 1.4f) return (d - 0.45f).coerceAtLeast(0.5f)
            d += 0.4f
        }
        return want
    }

    // ---- Piers and stairs: getting on and off coherently ----

    /** Boats and the submarine are boarded from a pier at the shore. */
    private val floatsBoard = kind == Traveller.CANOE || kind == Traveller.ICEBERG || kind == Traveller.SUBMARINE

    /** The piers (from, to rows): at the start (unless aboard already) and where they get off. */
    private val piers: List<FloatArray> by lazy {
        buildList {
            if (floatsBoard && !arrives) add(floatArrayOf(start - 8f, start + 0.6f))
            if (floatsBoard && leaves) add(floatArrayOf(offZ - 0.6f, offZ + 8f))
        }
    }

    /** The pier's deck height at row [z], or minus infinity where there is none. */
    private fun pierAt(z: Float): Float = if (piers.any { z >= it[0] && z <= it[1] }) WorldLand.WATER + 0.45f else Float.NEGATIVE_INFINITY

    /** The piers (and the ship's stairs) as one figure, made once. */
    private val supports: Rig? by lazy {
        val out = FloatList(8192)
        val wood = 0xFF8A5A34.toInt()
        val dark = 0xFF5A3A22.toInt()
        for (p in piers) {
            var z = p[0]
            var k = 0
            while (z < p[1]) {
                val zc = z + 0.5f
                Props.bakeMesh(FloatList(512).apply {
                    box(-0.95f, -0.12f, -0.5f, 0.95f, 0f, 0.46f, if (k % 2 == 0) wood else Props.shadeOf(wood, 0.9f))
                    if (k % 3 == 0) for (s in floatArrayOf(-0.85f, 0.85f)) tube(s, -4.5f, 0f, s, 0.35f, 0f, 0.09f, dark)
                }.mesh(), land.centerAt(zc), WorldLand.WATER + 0.45f, zc, land.headingAt(zc), 1f, out)
                z += 1f
                k++
            }
        }
        if (kind == Traveller.SHIP) {
            // Stairs up to the ship's deck beside it, where they get on (and off at the end).
            val at = buildList { if (!arrives) add(start); if (leaves) add(offZ) }
            for (z in at) {
                val h = land.headingAt(z)
                val cx = land.centerAt(z)
                Props.bakeMesh(FloatList(1024).apply {
                    for (i in 0 until 4) box(-1.2f - 0.45f * (4 - i), -0.4f, -0.55f, -1.2f - 0.45f * (3 - i), 0.26f * (i + 1), 0.55f, if (i % 2 == 0) wood else dark)
                    tube(-3.0f, 0.4f, 0.55f, -1.2f, 1.4f, 0.55f, 0.04f, dark)
                }.mesh(), cx, land.heightAt(cx, z), z, h, 1f, out)
            }
        }
        if (out.size == 0) null else Rig(listOf(Bone(out.mesh(), -1, 0f, 0f, 0f)))
    }

    private val supportPose = Pose(1)

    // ---- Light in the dark ----

    /**
     * The light the traveller carries this frame: x, y, z, then where it points (x, y, z), then
     * what it is (0 none, 1 a beam: a head torch, headlights, the submarine's lamp; 2 a flaming torch all round).
     */
    val lamp = FloatArray(7)

    private val headLamp: Rig by lazy {
        RigBuilder().apply { bone(-1, 0f, 0f, 0f) { blob(-0.07f, -0.05f, -0.03f, 0.07f, 0.05f, 0.05f, Props.glow(0xFFFFF6D0.toInt())) } }.rig()
    }

    /** The beam: a pale see-through cone along +z. */
    private val beam: Rig by lazy {
        RigBuilder().apply {
            bone(-1, 0f, 0f, 0f, alpha = 0.09f) {
                val n = 14
                val len = 7f
                val r = 1.9f
                for (k in 0 until n) {
                    val a0 = (2 * PI * k / n).toFloat()
                    val a1 = (2 * PI * (k + 1) / n).toFloat()
                    tri(0f, 0f, 0f, cos(a0) * r, sin(a0) * r, len, cos(a1) * r, sin(a1) * r, len, Props.glow(0xFFFFF0C0.toInt()))
                }
            }
        }.rig()
    }

    private val torch: Rig by lazy {
        RigBuilder().apply {
            bone(-1, 0f, 0f, 0f) {
                tube(0f, -0.35f, 0f, 0f, 0.1f, 0f, 0.035f, 0xFF5A3A22.toInt())
                blob(-0.09f, 0.08f, -0.09f, 0.09f, 0.34f, 0.09f, Props.glow(0xFFFF9A2A.toInt()))
                blob(-0.05f, 0.12f, -0.05f, 0.05f, 0.42f, 0.05f, Props.glow(0xFFFFE08A.toInt()))
            }
        }.rig()
    }

    /** The vehicle as it was left at the end (for the next song to show where it stays). */
    private var parkedBase: FloatArray? = null
    private var parkedRig: Rig? = null
    private var parkedPose: Pose? = null

    /** The vehicle left behind at this song's end, drawn moved [dz] rows (into the next song's land). */
    fun parked(dz: Float, out: MutableList<Draw>) {
        val base = parkedBase ?: return
        val m = base.copyOf()
        m[14] += dz
        out += Draw(parkedRig ?: return, m, parkedPose ?: return)
    }

    /** Whether the traveller gets off before this song's end (the next one goes on otherwise). */
    val leaves: Boolean get() = !onFoot && land.leavesTo != null && land.leavesTo != kind && land.endZ < Float.MAX_VALUE

    private val offZ: Float get() = land.endZ - DISMOUNT

    /** Whether the traveller is under the water now (in the submarine, gone down): the camera stays under too. */
    var underwater = false
        private set

    /** Each dog's face, to look out of the submarine's windows. */
    private val faces: List<Rig> by lazy {
        Dog.entries.map { d ->
            val k = Quad.dog(d).second
            val head = if (d == Dog.BRACO) k.light else k.coat
            RigBuilder().apply {
                bone(-1, 0f, 0f, 0f) {
                    blob(-0.15f, -0.13f, -0.12f, 0.15f, 0.15f, 0.1f, head)
                    blob(-0.075f, -0.1f, 0.05f, 0.075f, 0.01f, 0.2f, if (d == Dog.MALAMUTE) k.light else head)
                    blob(-0.035f, -0.05f, 0.18f, 0.035f, 0.0f, 0.22f, k.nose)
                    for (s in floatArrayOf(-1f, 1f)) {
                        blob(s * 0.065f - 0.025f, 0.03f, 0.07f, s * 0.065f + 0.025f, 0.08f, 0.11f, 0xFF141414.toInt())
                        blob(s * 0.12f - 0.035f, 0.08f, -0.04f, s * 0.12f + 0.035f, 0.1f + k.ears * 1.1f, 0.03f, if (d == Dog.BRACO) k.light else k.coat)
                    }
                }
            }.rig()
        }
    }

    /** Which window each dog looks out of (0 … 5, -1 none), changed now and then. */
    private val window = intArrayOf(0, 3, 4, -1)
    private var shuffleAt = 0L

    private fun shuffleWindows(nowMs: Long) {
        if (nowMs < shuffleAt) return
        shuffleAt = nowMs + 2500 + rnd.nextInt(4000)
        val free = (0 until 6).shuffled(rnd).toMutableList()
        for (i in window.indices) window[i] = if (rnd.nextFloat() < 0.8f) free.removeAt(0) else -1
    }

    /** Inside the submarine: once aboard, everyone is in and the hatch shut. */
    private val inside = kind == Traveller.SUBMARINE

    /**
     * The figures for this frame: the traveller at row [tz] (height and heading from the land),
     * [beats] the song's beats so far (fractional, for the steps), [playing] whether it moves;
     * [dark] (night, a volcano) and [tunnel] bring out a light.
     */
    fun frame(tz: Float, beats: Double, playing: Boolean, nowMs: Long, out: MutableList<Draw>, dark: Boolean = false, tunnel: Boolean = false) {
        if (shownMs == Long.MIN_VALUE) shownMs = nowMs
        // A song's vehicle waiting by the way comes in softly (not out of nowhere as the song changes).
        appear = if (arrives) 1f else ((nowMs - shownMs) / 1400f).coerceIn(0f, 1f)
        if ((dark || tunnel) != leds) {
            leds = dark || tunnel
            dogsNow = null
        }
        val go = if (playing) 1f else 0f
        val t = nowMs / 1000.0
        val dt = if (lastMs < 0) 0f else ((nowMs - lastMs).coerceIn(0, 100)) / 1000f
        lastMs = nowMs
        lamp.fill(0f)
        // The current move, 0 … 1 through it.
        val mv = if (move != Move.NONE && nowMs - moveAt < move.ms) move else Move.NONE
        val mu = if (mv == Move.NONE) 0f else (nowMs - moveAt).toFloat() / mv.ms

        supports?.let { out += Draw(it, Mat4.identity(), supportPose) }
        // Obstacles in sight.
        obstacleRig?.let { rig ->
            for (z in obstacles) if (z > tz - 30 && z < tz + WorldRendererLimits.FAR) {
                out += Draw(rig, Mat4.model(land.centerAt(z), land.pathAt(z) - 0.05f, z, land.headingAt(z)), poseOf("o$z", rig))
            }
        }
        // Who waits at relays ahead, and who was left behind at those passed (waving).
        for ((i, r) in relays.withIndex()) {
            if (r < tz - 40 || r > tz + WorldRendererLimits.FAR) continue
            val k = firstPerson + i
            val heading = land.headingAt(r)
            val side = if (i % 2 == 0) 1.9f else -1.9f
            val sx = land.centerAt(r) + side * cos(heading)
            val sz = r - side * sin(heading)
            val sy = land.heightAt(sx, sz)
            val cx = land.centerAt(r)
            val cy = land.heightAt(cx, r)
            if (tz < r) {
                // The next one waits, turned towards the way, waving when the traveller is near;
                // then walks onto the way to meet the traveller there.
                val rig = personRig(k + 1)
                val p = poseOf("wait$k", rig)
                val u = (1f - (r - tz) / RELAY_WALK).coerceIn(0f, 1f)
                if (u <= 0f) {
                    if (r - tz < 30) wave(p, t, side > 0)
                    out += Draw(rig, Mat4.model(sx, sy, sz, heading + if (side > 0) -1.2f else 1.2f), p)
                } else {
                    gait(p, (t * 1.6).toFloat(), false, 1f)
                    val x = sx + (cx - sx) * u
                    val z = sz + (r - sz) * u
                    out += Draw(rig, Mat4.model(x, sy + (cy - sy) * u + 0.04f, z, heading + (if (side > 0) -1f else 1f) * (1.4f - 1.4f * u * u)), p)
                }
            } else if (onFoot || r != start) {
                // The one before walks off to where the other waited, and stays there waving goodbye.
                val rig = personRig(k)
                val p = poseOf("left$k", rig)
                val u = ((tz - r) / RELAY_WALK).coerceIn(0f, 1f)
                if (u >= 1f) {
                    wave(p, t + i, side > 0)
                    out += Draw(rig, Mat4.model(sx, sy, sz, heading + if (side > 0) -1.9f else 1.9f), p)
                } else {
                    gait(p, (t * 1.6).toFloat(), false, 1f)
                    out += Draw(rig, Mat4.model(cx + (sx - cx) * u, cy + (sy - cy) * u + 0.04f, r + (sz - r) * u, heading + (if (side > 0) -1f else 1f) * 1.3f * u), p)
                }
            }
        }

        val person = personAt(tz)
        // Jumping an obstacle: an arc over it.
        val over = obstacles.firstOrNull { tz in it - 1.6f..it + 1.6f }?.let { o -> sin(((tz - (o - 1.6f)) / 3.2f) * PI).toFloat() } ?: 0f
        val startAboard = arrives && !onFoot
        val gotOff = leaves && tz >= offZ + BOARD
        val walking = onFoot || (!startAboard && tz < start + BOARD) || gotOff
        // Where the traveller is along the way (waiting by the vehicle before getting on).
        // (At a pier the traveller stops at its end, behind the boat.)
        val walkerZ = if (onFoot || gotOff) tz else min(tz, if (floatsBoard) start - 1.5f else start)
        var personBase: FloatArray? = null
        var personPose: Pose? = null
        var personRigNow: Rig? = null

        // On foot: before the song's vehicle is reached, after getting off it, or all along.
        if (walking) {
            val walkerX = land.centerAt(walkerZ)
            val run = kind == Traveller.RUNNER
            val rig = travellerRig(person)
            val p = poseOf("traveller", rig)
            val phase = (beats / 2).toFloat() * go
            gait(p, phase, run, go)
            var y = max(land.heightAt(walkerX, walkerZ) + 0.04f, pierAt(walkerZ) + 0.02f) + abs(sin(phase * 2 * PI.toFloat())) * 0.05f * go
            walkHeading = turnTo(walkHeading, land.softHeadingAt(walkerZ), min(1f, dt * 4f))
            var yaw = walkHeading
            if (onFoot || gotOff) {
                y += over * 1.0f
                if (over > 0f) tuck(p, over)
                y += moveOnFoot(p, mv, mu)
                if (mv == Move.SPIN) yaw += mu * 2 * PI.toFloat()
            }
            if (onFoot || gotOff || tz < start) {
                // Leaning forward up a slope, back down one.
                val base = Mat4.model(walkerX, y, walkerZ, yaw, kotlin.math.atan(slope(walkerZ)) * 0.45f)
                personBase = base; personPose = p; personRigNow = rig
                if (!throughEyes) out += Draw(rig, base, p)
            }
        }

        // The vehicle: waiting at start (unless it came along from the song before), going on with
        // the traveller, then left where they get off before the end.
        val vz = when {
            !startAboard && tz < start -> start
            leaves && tz > offZ -> offZ
            else -> tz
        }
        val vx = land.centerAt(vz)
        rideHeading = turnTo(rideHeading, land.softHeadingAt(vz), min(1f, dt * 4f))
        val vh = rideHeading
        val moving = if ((!startAboard && tz < start) || (leaves && tz > offZ)) 0f else go
        val boarded = if (onFoot || startAboard) 1f else ((tz - start) / BOARD).coerceIn(0f, 1f)
        // Getting off: 0 still aboard … 1 down on the ground.
        val off = if (leaves) ((tz - offZ) / BOARD).coerceIn(0f, 1f) else 0f
        var vehicleBody: FloatArray? = null
        var riderBase: FloatArray? = null
        val dogSpots = ArrayList<FloatArray>()
        if (horse != null) {
            val (rig, shape) = horse
            val p = poseOf("horse", rig)
            val phase = (beats / 2).toFloat()
            if (kind == Traveller.ELEPHANT) trot(p, phase * 2f, moving * 0.6f, false) else gallop(p, phase, moving, true)
            val r = rest(vx, vz, vh, if (kind == Traveller.ELEPHANT) 1.0f else 0.8f)
            var y = r[0] + over * 1.2f * moving
            if (over > 0f && moving > 0f) for (leg in 0..3) { p.pitch[Quad.upper(leg)] += if (leg < 2) -0.9f * over else 0.8f * over; p.pitch[Quad.lower(leg)] += if (leg < 2) 1.2f * over else -0.9f * over }
            if (mv == Move.REAR && !gotOff) {
                val a = sin(mu * PI).toFloat()
                p.pitch[Quad.BODY] = -0.75f * a
                p.pitch[Quad.upper(0)] -= 1.2f * a; p.pitch[Quad.upper(1)] -= 1.0f * a
                p.pitch[Quad.lower(0)] += 1.4f * a; p.pitch[Quad.lower(1)] += 1.4f * a
                y += 0.15f * a
            }
            if (mv == Move.JUMP && !gotOff) y += sin(mu * PI).toFloat() * 1.1f
            if (kind == Traveller.ELEPHANT) p.pitch[Quad.BODY] += sin(t * 2.0).toFloat() * 0.02f
            p.yaw[Quad.TAIL] = sin(t * 3.0).toFloat() * 0.25f
            if ((kind == Traveller.RIDER || kind == Traveller.CAMEL) && rig.bones.size > Quad.NECK) {
                // The head turns into a bend before the body does, and now and then to look aside.
                var bend = land.softHeadingAt(vz + 6f) - vh
                while (bend > PI) bend -= (2 * PI).toFloat()
                while (bend < -PI) bend += (2 * PI).toFloat()
                val g = sin(t * 0.23 + seed).toFloat()
                val aside = if (g > 0.8f) (g - 0.8f) / 0.2f * 0.4f * (if (sin(t * 0.041 + seed * 0.7) > 0) 1f else -1f) else 0f
                val want = (bend * 1.6f).coerceIn(-0.55f, 0.55f) + aside
                neckYaw += (want - neckYaw) * min(1f, dt * 2.5f)
                p.yaw[Quad.NECK] = neckYaw
            }
            val base = Mat4.model(vx, y, vz, vh, r[1])
            out += Draw(rig, base, p, if (tz < start) appear else 1f)
            if (leaves && tz > offZ) { parkedBase = base; parkedRig = rig; parkedPose = p }
            val m = Array(rig.bones.size) { FloatArray(16) }
            rig.matrices(base, p, m)
            vehicleBody = m[0]
            val seat = when (kind) {
                Traveller.CAMEL -> floatArrayOf(2.12f, -0.05f)
                Traveller.ELEPHANT -> floatArrayOf(2.72f, -0.05f)
                else -> floatArrayOf(shape.h + 0.1f, -0.05f)
            }
            riderBase = Mat4.mul(m[0], Mat4.model(0f, seat[0] - Human.HIP, seat[1]))
        } else if (vehicle != null) {
            val rig = vehicle.rig
            val p = poseOf("vehicle", rig)
            var y = when (kind) {
                Traveller.ICEBERG, Traveller.CANOE -> max(WorldLand.WATER - 0.12f, land.pathAt(vz) - 0.3f) + sin(t * 1.3).toFloat() * 0.06f
                // Floating at the surface by the shore, diving down to near the sea's floor, and up again.
                Traveller.SUBMARINE -> {
                    val dive = land.subDive(vz)
                    underwater = dive > 0.35f && boarded >= 1f && off <= 0f
                    val surface = WorldLand.WATER - 1.3f + sin(t * 1.1).toFloat() * 0.08f
                    surface + (land.pathAt(vz) + 3.2f + sin(t * 1.1).toFloat() * 0.15f - surface) * dive
                }
                Traveller.SHIP -> {
                    val up = if (startAboard) 1f else ((tz - start) / 30f).coerceIn(0f, 1f)
                    // Settling down onto the ground before it is left.
                    val down = if (leaves) ((tz - (offZ - 16f)) / 16f).coerceIn(0f, 1f) else 0f
                    land.pathAt(vz) + 0.15f + 2.6f * up * (1 - down) + sin(t * 1.7).toFloat() * 0.12f * boarded * (1 - down)
                }
                else -> 0f
            }
            // Wheels on the ground at both ends, tilted with the slope.
            val r = rest(vx, vz, vh, if (kind == Traveller.CAR) 0.85f else 0.58f)
            if (kind == Traveller.CAR || kind == Traveller.CYCLIST) y = r[0]
            val tilt = if (kind == Traveller.CAR || kind == Traveller.CYCLIST) r[1] else if (kind == Traveller.SHIP) -kotlin.math.atan(slope(vz)) * 0.5f else 0f
            // Wheels turn with the way gone.
            val dist = max(0f, min(tz, if (leaves) offZ else tz) - start)
            for (w in vehicle.wheels) {
                if (kind == Traveller.SUBMARINE) p.roll[w] = t.toFloat() * 9f * moving
                else p.pitch[w] = dist / (if (kind == Traveller.CAR) 0.32f else 0.36f)
            }
            // (Seen through the traveller's eyes, no tricks: they would make one dizzy.)
            if (!(leaves && tz > offZ) && !throughEyes) when (mv) {
                Move.WHEELIE -> p.pitch[0] = -0.45f * sin(mu * PI).toFloat()
                Move.HOP -> y += sin(mu * PI).toFloat() * 0.7f
                Move.FLIP -> { p.pitch[0] = -mu * 2 * PI.toFloat(); y += sin(mu * PI).toFloat() * 1.6f }
                Move.ROLL -> { p.roll[0] = mu * 2 * PI.toFloat(); y += sin(mu * PI).toFloat() * 1.2f }
                Move.LOOP -> { p.pitch[0] = -mu * 2 * PI.toFloat(); y += sin(mu * PI).toFloat() * 3f }
                Move.SPIN -> p.yaw[0] = mu * 2 * PI.toFloat()
                else -> {}
            }
            if (kind == Traveller.SHIP || kind == Traveller.SUBMARINE) p.roll[0] += sin(t * 0.9).toFloat() * 0.05f + (land.headingAt(vz + 6) - vh) * 1.5f * moving
            if (kind == Traveller.SUBMARINE) {
                // Nose down going down, up coming up.
                val dz = land.subDive(vz + 3f) - land.subDive(vz - 3f)
                p.pitch[0] += dz * 1.6f
            }
            if (kind == Traveller.CANOE) p.roll[0] += sin(t * 1.7).toFloat() * 0.04f
            y += over * 0.9f * moving
            if (kind == Traveller.CYCLIST && over > 0f) p.pitch[0] -= 0.25f * over
            val base = Mat4.model(vx, y, vz, vh, tilt)
            out += Draw(rig, base, p, if (tz < start) appear else 1f)
            if (leaves && tz > offZ) { parkedBase = base; parkedRig = rig; parkedPose = p }
            val m = Array(rig.bones.size) { FloatArray(16) }
            rig.matrices(base, p, m)
            vehicleBody = m[0]
            riderBase = Mat4.mul(m[0], Mat4.model(0f, vehicle.seatY, vehicle.seatZ))
            for (s in vehicle.dogSpots) dogSpots += s
            // Its lamp: the car's and the bike's headlights in the dark, the submarine's always under the sea.
            val lit = when (kind) {
                Traveller.SUBMARINE -> land.subDive(vz) > 0.25f || dark
                Traveller.CAR, Traveller.CYCLIST, Traveller.SHIP -> dark || tunnel
                else -> false
            }
            if (lit && boarded >= 1f && !(leaves && tz > offZ + BOARD)) {
                val ly = when (kind) { Traveller.SUBMARINE -> 1.0f; Traveller.CAR -> 0.5f; Traveller.CYCLIST -> 1.05f; else -> 0.7f }
                val lz = when (kind) { Traveller.SUBMARINE -> 2.2f; Traveller.CAR -> 1.45f; Traveller.CYCLIST -> 0.6f; else -> 2.2f }
                val down = if (kind == Traveller.SUBMARINE) 0.5f else 0.18f
                setLamp(m[0], 0f, ly, lz, 0f, -down, 1f, 1f)
                out += Draw(beam, Mat4.mul(m[0], Mat4.mul(Mat4.model(0f, ly, lz), Mat4.model(0f, 0f, 0f, 0f, kotlin.math.atan(down)))), poseOf("beam", beam))
            }
        }

        // The rider, once on: getting on with a hop from the ground beside it, off with a hop
        // forward onto the way. Into the submarine: in through the hatch, and out again.
        val rb = riderBase
        if (!onFoot && rb != null && (startAboard || tz >= start) && !gotOff) {
            val rig = travellerRig(person)
            val p = poseOf("rider", rig)
            when (kind) {
                Traveller.CYCLIST -> pedal(p, (beats / 2).toFloat() * moving)
                Traveller.RIDER, Traveller.CAMEL, Traveller.ELEPHANT -> ride(p, t)
                Traveller.CANOE -> paddle(p, (beats / 2).toFloat() * moving)
                Traveller.ICEBERG -> { stand(p, t); moveOnFoot(p, mv, mu) }
                else -> sit(p)
            }
            if (mv == Move.ARMS && off <= 0f) arms(p, mu)
            var base: FloatArray = rb
            if (boarded < 1f) {
                // From the ground at the way's side up into the seat, in an arc.
                val hop = sin(boarded * PI).toFloat() * 0.7f
                val k = 1 - boarded
                base = if (floatsBoard) {
                    // From the end of the pier behind it, a step down into it.
                    Mat4.mul(Mat4.model(-k * 1.5f * sin(vh), hop + k * (pierAt(start - 1.5f) - rb[13]), -k * 1.5f * cos(vh)), rb)
                } else Mat4.mul(Mat4.model(-k * 1.1f * cos(vh), hop, k * 1.1f * sin(vh)), rb)
            }
            if (off > 0f) {
                // Down onto the way ahead, in an arc.
                val gz = offZ + BOARD
                val gx = land.centerAt(gz)
                val ground = Mat4.model(gx, max(land.heightAt(gx, gz) + 0.04f, pierAt(gz) + 0.02f), gz, land.headingAt(gz))
                base = lerpBase(rb, ground, off)
                base[13] += sin(off * PI).toFloat() * 0.8f
            }
            // In the submarine they are inside once aboard (seen only climbing in and out).
            if (!inside || boarded < 1f || off > 0f) {
                if (!throughEyes) out += Draw(rig, base, p)
                personBase = base; personPose = p; personRigNow = rig
            }
        }

        // The dogs: around the traveller, or aboard (car, ship, iceberg…) once the traveller is.
        val v = if (playing) WorldLand.BLOCKS_PER_SECOND else 0f
        val anchor = if (walking) walkerZ else tz
        // Seen through the traveller's eyes, the dogs go on ahead (in sight), coming back softly after.
        lead += ((if (throughEyes) DOGS_LEAD else 0f) - lead) * min(1f, dt * 0.6f)
        dogsNearCount = 0
        for ((i, pair) in dogs.withIndex()) {
            val (rig, _) = pair
            val p = poseOf("dog$i", rig)
            p.yaw[Quad.TAIL] = sin(t * 9.0 + i).toFloat() * 0.45f
            val pup = pups[i]
            val wasAboard = dogsAboard && vehicleBody != null && i < dogSpots.size && (startAboard || tz >= start)
            val jumpedOff = leaves && tz >= offZ + i * 0.6f + BOARD
            val aboard = wasAboard && !jumpedOff
            // Waiting by the vehicle before getting on, the traveller stands: so do they, more or less.
            if (!aboard) {
                think(pup, dt, if (walking && tz >= start && !onFoot && !gotOff) 0f else v, nowMs)
                // Never off a bridge, into water or lava, or down a drop: as far out as is safe there.
                val reach = safeReach(anchor + pup.oz + lead, pup.ox)
                if (kotlin.math.abs(pup.ox) > reach) pup.ox = kotlin.math.sign(pup.ox) * reach
                if (kotlin.math.abs(pup.tx) > reach) pup.tx = kotlin.math.sign(pup.tx) * reach
                // Not through one another: each keeps a little room from the others.
                for (o in pups) {
                    if (o === pup) continue
                    val dx = pup.ox - o.ox
                    val dz = pup.oz - o.oz
                    val d = kotlin.math.hypot(dx, dz)
                    if (d >= DOG_ROOM) continue
                    val push = (DOG_ROOM - d) * min(1f, dt * 5f)
                    if (d < 1e-3f) pup.ox += if (i % 2 == 0) push else -push
                    else { pup.ox += dx / d * push; pup.oz += dz / d * push }
                }
                pup.ox = pup.ox.coerceIn(-4.6f, 4.6f)
            }
            // Drawn where it is, at a dog's pace at most: whatever sends it elsewhere (kept off the
            // traveller's feet, back from the water's edge…), it runs there, never leaps.
            if (pup.sx.isNaN() || aboard) {
                pup.sx = pup.ox
                pup.sz = pup.oz
            } else {
                val dx = pup.ox - pup.sx
                val dz = pup.oz - pup.sz
                val d = kotlin.math.hypot(dx, dz)
                val most = 9f * dt
                if (d <= most) { pup.sx = pup.ox; pup.sz = pup.oz } else { pup.sx += dx / d * most; pup.sz += dz / d * most }
            }
            val along = anchor + pup.sz + (if (aboard) 0f else lead)
            // Its heading eased, and its place square to the way (not only across in x): in a
            // sharp turn they round it at their side, never bunched on the line or swung about.
            pup.heading = turnTo(pup.heading, land.softHeadingAt(along), min(1f, dt * 4f))
            val gh = pup.heading
            val gx = land.centerAt(along) + pup.sx * cos(gh)
            val gz = along - pup.sx * sin(gh)
            val gr = rest(gx, gz, gh, 0.4f)
            var gy = max(gr[0] - 0.04f, pierAt(gz) + 0.02f)
            if (aboard) {
                // (Seen from the driver's seat, the dogs in front sit at the back instead: the way in sight.)
                val s = dogSpots[i].let {
                    if (!throughEyes || kind != Traveller.CAR) it
                    else if (it[2] > -0.3f) floatArrayOf(it[0] * 0.8f, it[1] + 0.1f, -1.25f)
                    else floatArrayOf(it[0], it[1], -0.95f)
                }
                sitDog(p)
                var base = Mat4.mul(vehicleBody!!, Mat4.model(s[0], s[1], s[2], (i - 1.5f) * 0.4f))
                val b = if (startAboard) 1f else ((tz - start - i * 0.6f) / BOARD).coerceIn(0f, 1f)
                val o = if (leaves) ((tz - offZ - i * 0.6f) / BOARD).coerceIn(0f, 1f) else 0f
                if (b < 1f) {
                    // Jumping in, one after another.
                    val hop = sin(b * PI).toFloat() * 0.9f
                    val ground = Mat4.model(gx, gy + hop, gz, gh)
                    base = if (b <= 0f) ground else lerpBase(ground, base, b)
                    if (b <= 0f) trot(p, (beats).toFloat() + i * 0.25f, go, false)
                } else if (o > 0f) {
                    // And out again, one after another.
                    val ground = Mat4.model(gx, gy, gz, gh)
                    base = lerpBase(base, ground, o)
                    base[13] += sin(o * PI).toFloat() * 0.9f
                }
                if (!inside || b < 1f || o > 0f) {
                    out += Draw(rig, base, p)
                    if (b >= 1f && o <= 0f && dogsNearCount < 4) {
                        dogsNear[dogsNearCount * 3] = base[12] + base[8] * 0.4f
                        dogsNear[dogsNearCount * 3 + 1] = base[13] + 0.55f
                        dogsNear[dogsNearCount * 3 + 2] = base[14] + base[10] * 0.4f
                        dogsNearCount++
                    }
                } else {
                    // Inside the submarine: its face at a window, looking out.
                    shuffleWindows(nowMs)
                    val w = window[i]
                    if (w >= 0) {
                        val side = if (w < 3) 1f else -1f
                        val wz = floatArrayOf(-0.9f, 0.1f, 1.1f)[w % 3]
                        val look = sin(t * 1.3 + i * 2.1).toFloat() * 0.35f
                        val face = Mat4.mul(vehicleBody!!, Mat4.model(side * 1.02f, 1.0f + sin(t * 2.0 + i).toFloat() * 0.02f, wz, side * PI.toFloat() / 2 + look, s = 0.9f))
                        out += Draw(faces[i], face, poseOf("face$i", faces[i]))
                    }
                }
            } else {
                // Its own pace: standing (sniffing), trotting, galloping.
                val s = abs(pup.speed)
                if (s < 0.4f) {
                    if (pup.mode == STOP) p.pitch[Quad.BODY] = 0.16f
                } else if (s < 5f) {
                    pup.phase += s * dt * 0.55f
                    trot(p, pup.phase, min(1f, s / 2f), s > 3.5f)
                } else {
                    pup.phase += s * dt * 0.3f
                    gallop(p, pup.phase, 1f, false)
                }
                // Their own jump over what lies across.
                val o = obstacles.firstOrNull { gz in it - 1.2f..it + 1.2f }
                if (o != null) {
                    val u = sin(((gz - (o - 1.2f)) / 2.4f) * PI).toFloat()
                    gy += u * 0.75f
                    for (leg in 0..3) p.pitch[Quad.upper(leg)] = if (leg < 2) -0.9f * u else 0.9f * u
                }
                // A hop when the traveller makes a move.
                if (mv != Move.NONE) gy += abs(sin(mu * PI * 2)).toFloat() * 0.25f
                out += Draw(rig, Mat4.model(gx, gy, gz, gh + pup.yaw, gr[1]), p)
            }
        }

        // A light on the traveller in the dark: a torch in the hand in a tunnel, a head torch otherwise.
        val pb = personBase
        val pp = personPose
        val pr = personRigNow
        // The traveller's eyes (a little before the face), for a camera that sees through them.
        eye[3] = 0f
        eye[4] = Float.NaN
        if (pb != null && pp != null && pr != null) {
            val m = Array(pr.bones.size) { FloatArray(16) }
            pr.matrices(pb, pp, m)
            val b = m[Human.BODY]
            eye[0] = b[4] * 1.66f + b[8] * 0.3f + b[12]
            eye[1] = b[5] * 1.66f + b[9] * 0.3f + b[13]
            eye[2] = b[6] * 1.66f + b[10] * 0.3f + b[14]
            eye[3] = 1f
        }
        // At the wheel: the driver's seat, the wheel and the bonnet in sight before the eyes.
        val car = vehicleBody
        if (kind == Traveller.CYCLIST && car != null && pb != null && !walking) {
            // On a bike: over the saddle, the bars a strip low in sight (from the bike itself: the
            // rider's lean forward would put the eyes over them).
            eye[0] = car[4] * 1.45f + car[8] * -0.2f + car[12]
            eye[1] = car[5] * 1.45f + car[9] * -0.2f + car[13]
            eye[2] = car[6] * 1.45f + car[10] * -0.2f + car[14]
            eye[3] = 1f
        }
        if (kind == Traveller.CAR && car != null && pb != null && !walking) {
            // (In the seat, leaning with the car up and down a slope: the wheel stays where it is
            // before the eyes. Its tricks are left out when seen from here.)
            eye[0] = car[4] * 1.55f + car[8] * -0.35f + car[12]
            eye[1] = car[5] * 1.55f + car[9] * -0.35f + car[13]
            eye[2] = car[6] * 1.55f + car[10] * -0.35f + car[14]
            eye[3] = 1f
        }
        // Mounted or in a vehicle, the eyes look where it points (the head, the wheel, the bow ahead).
        val ridden = vehicleBody
        if (ridden != null && eye[3] > 0f && !walking) eye[4] = kotlin.math.atan2(ridden[8], ridden[10])
        if (lamp[6] == 0f && pb != null && pp != null && pr != null && (dark || tunnel)) {
            val m = Array(pr.bones.size) { FloatArray(16) }
            if (tunnel && (walking || kind == Traveller.RIDER || kind == Traveller.CAMEL || kind == Traveller.ELEPHANT)) {
                // The right arm up and forward, holding the torch.
                pp.pitch[Human.ARM_R] = -1.0f
                pp.pitch[Human.FOREARM_R] = -0.7f
                pr.matrices(pb, pp, m)
                val h = m[Human.FOREARM_R]
                val hx = h[0] * 0.35f + h[4] * 0.86f + h[8] * 0.06f + h[12]
                val hy = h[1] * 0.35f + h[5] * 0.86f + h[9] * 0.06f + h[13]
                val hz = h[2] * 0.35f + h[6] * 0.86f + h[10] * 0.06f + h[14]
                out += Draw(torch, Mat4.model(hx, hy, hz, 0f), poseOf("torch", torch))
                lamp[0] = hx; lamp[1] = hy + 0.3f; lamp[2] = hz
                lamp[3] = 0f; lamp[4] = -1f; lamp[5] = 0f
                lamp[6] = 2f
            } else {
                pr.matrices(pb, pp, m)
                val b = m[Human.BODY]
                setLamp(b, 0f, 1.72f, 0.2f, 0f, -0.3f, 1f, 1f)
                if (!throughEyes) {
                    out += Draw(headLamp, Mat4.mul(b, Mat4.model(0f, 1.72f, 0.19f)), poseOf("headlamp", headLamp))
                    out += Draw(beam, Mat4.mul(b, Mat4.mul(Mat4.model(0f, 1.72f, 0.22f), Mat4.model(0f, 0f, 0f, 0f, 0.29f))), poseOf("beam", beam))
                }
            }
        }
    }

    /** The lamp at local (x, y, z) of [m], pointing along local (dx, dy, dz). */
    private fun setLamp(m: FloatArray, x: Float, y: Float, z: Float, dx: Float, dy: Float, dz: Float, kind: Float) {
        lamp[0] = m[0] * x + m[4] * y + m[8] * z + m[12]
        lamp[1] = m[1] * x + m[5] * y + m[9] * z + m[13]
        lamp[2] = m[2] * x + m[6] * y + m[10] * z + m[14]
        var ax = m[0] * dx + m[4] * dy + m[8] * dz
        var ay = m[1] * dx + m[5] * dy + m[9] * dz
        var az = m[2] * dx + m[6] * dy + m[10] * dz
        val l = kotlin.math.sqrt(ax * ax + ay * ay + az * az).coerceAtLeast(1e-4f)
        ax /= l; ay /= l; az /= l
        lamp[3] = ax; lamp[4] = ay; lamp[5] = az
        lamp[6] = kind
    }

    /** Height and tilt for something [half] long each way from (x, z) along [heading], resting on both ends (never sunk). */
    private fun rest(x: Float, z: Float, heading: Float, half: Float): FloatArray {
        val dx = sin(heading) * half
        val dz = cos(heading) * half
        val front = land.heightAt(x + dx, z + dz)
        val back = land.heightAt(x - dx, z - dz)
        // The drawn land is straight between its points: a little above the true height keeps
        // wheels and paws on it in hollows too.
        val y = max((front + back) / 2, land.heightAt(x, z)) + 0.06f
        return floatArrayOf(y, -kotlin.math.atan2(front - back, 2 * half))
    }

    /** The slope along the way at [z] (rise per row). */
    private fun slope(z: Float) = (land.pathAt(z + 1f) - land.pathAt(z - 1f)) / 2f

    private fun lerpBase(a: FloatArray, b: FloatArray, t: Float) = FloatArray(16) { a[it] + (b[it] - a[it]) * t }

    // ---- People ----

    /** Walking or running: thighs swing, knees bend as each leg comes forward, elbows bent, arms swing against the legs. */
    private fun gait(p: Pose, phase: Float, run: Boolean, go: Float) {
        val a = phase * 2 * PI.toFloat()
        val amp = (if (run) 0.75f else 0.45f) * go
        val knee = (if (run) 1.5f else 0.75f) * go
        p.pitch[Human.THIGH_L] = -sin(a) * amp
        p.pitch[Human.THIGH_R] = sin(a) * amp
        p.pitch[Human.SHIN_L] = max(0f, sin(a + 1.4f)) * knee + 0.05f
        p.pitch[Human.SHIN_R] = max(0f, sin(a + 1.4f + PI.toFloat())) * knee + 0.05f
        p.pitch[Human.ARM_L] = sin(a) * amp * 0.9f
        p.pitch[Human.ARM_R] = -sin(a) * amp * 0.9f
        p.pitch[Human.FOREARM_L] = -(if (run) 1.3f else 0.35f) - max(0f, -sin(a)) * 0.3f * go
        p.pitch[Human.FOREARM_R] = -(if (run) 1.3f else 0.35f) - max(0f, sin(a)) * 0.3f * go
        if (run) p.pitch[Human.BODY] = 0.12f * go
        // The body rolls over each foot and turns against the hips; the head nods with the steps.
        p.roll[Human.BODY] = sin(a) * (if (run) 0.06f else 0.045f) * go
        p.yaw[Human.BODY] = sin(a) * (if (run) 0.1f else 0.07f) * go
        p.pitch[Human.BODY] += kotlin.math.abs(sin(a)) * 0.03f * go
        if (go < 0.5f) {
            // Standing: breathing, shifting the weight now and then.
            val t = System.nanoTime() / 1e9
            p.pitch[Human.BODY] += sin(t * 1.4).toFloat() * 0.02f
            p.roll[Human.BODY] += sin(t * 0.37).toFloat() * 0.035f
            p.pitch[Human.ARM_L] += sin(t * 1.4 + 0.5).toFloat() * 0.04f
            p.pitch[Human.ARM_R] += sin(t * 1.4 + 1.1).toFloat() * 0.04f
            p.pitch[Human.FOREARM_L] -= 0.12f
            p.pitch[Human.FOREARM_R] -= 0.12f
        }
    }

    /** Knees up, for a jump. */
    private fun tuck(p: Pose, u: Float) {
        p.pitch[Human.THIGH_L] = -1.1f * u; p.pitch[Human.THIGH_R] = -1.0f * u
        p.pitch[Human.SHIN_L] = 1.6f * u; p.pitch[Human.SHIN_R] = 1.5f * u
        p.pitch[Human.ARM_L] = -1.2f * u; p.pitch[Human.ARM_R] = -1.2f * u
    }

    /** A move on foot (or on the iceberg); returns the height gained. */
    private fun moveOnFoot(p: Pose, mv: Move, mu: Float): Float {
        val arc = sin(mu * PI).toFloat()
        return when (mv) {
            Move.HOP -> { tuck(p, arc * 0.5f); arc * 0.45f }
            Move.JUMP -> { tuck(p, arc); arc * 1.1f }
            Move.DOUBLE -> { val u = abs(sin(mu * 2 * PI)).toFloat(); tuck(p, u * 0.7f); u * 0.7f }
            Move.FLIP -> { tuck(p, arc); p.pitch[Human.BODY] = mu * 2 * PI.toFloat(); arc * 1.5f }
            Move.CARTWHEEL -> { p.roll[Human.BODY] = mu * 2 * PI.toFloat(); arms(p, 1f); arc * 0.9f }
            Move.SPIN -> { tuck(p, arc * 0.6f); arc * 0.8f }
            Move.ARMS -> { arms(p, mu); 0f }
            else -> 0f
        }
    }

    /** Both arms up, waving with the beat of the move. */
    private fun arms(p: Pose, mu: Float) {
        val up = min(1f, sin(mu * PI).toFloat() * 1.6f)
        p.pitch[Human.ARM_L] = -2.7f * up; p.pitch[Human.ARM_R] = -2.7f * up
        p.roll[Human.ARM_L] = 0.35f * up; p.roll[Human.ARM_R] = -0.35f * up
        p.pitch[Human.FOREARM_L] = -0.3f * up + sin(mu * 12).toFloat() * 0.3f * up
        p.pitch[Human.FOREARM_R] = -0.3f * up - sin(mu * 12).toFloat() * 0.3f * up
    }

    /** Standing by the way, waving one hand. */
    private fun wave(p: Pose, t: Double, right: Boolean) {
        val arm = if (right) Human.ARM_R else Human.ARM_L
        val fore = if (right) Human.FOREARM_R else Human.FOREARM_L
        p.pitch[arm] = -2.6f
        p.roll[arm] = if (right) -0.4f else 0.4f
        p.roll[fore] = sin(t * 7).toFloat() * 0.5f
        p.pitch[fore] = -0.4f
    }

    private fun stand(p: Pose, t: Double) {
        p.pitch[Human.BODY] = sin(t * 1.3).toFloat() * 0.03f
        p.pitch[Human.FOREARM_L] = -0.25f; p.pitch[Human.FOREARM_R] = -0.25f
    }

    /** Seated (car, ship): knees bent, hands forward. */
    private fun sit(p: Pose) {
        p.pitch[Human.THIGH_L] = -1.5f; p.pitch[Human.THIGH_R] = -1.5f
        p.pitch[Human.SHIN_L] = 1.45f; p.pitch[Human.SHIN_R] = 1.45f
        p.pitch[Human.ARM_L] = -1.0f; p.pitch[Human.ARM_R] = -1.0f
        p.pitch[Human.FOREARM_L] = -0.4f; p.pitch[Human.FOREARM_R] = -0.4f
    }

    /** On a bike: pedalling, leaning on the bars. */
    private fun pedal(p: Pose, phase: Float) {
        val a = phase * 2 * PI.toFloat()
        p.pitch[Human.BODY] = 0.35f
        p.pitch[Human.THIGH_L] = -1.3f + sin(a) * 0.4f
        p.pitch[Human.THIGH_R] = -1.3f - sin(a) * 0.4f
        p.pitch[Human.SHIN_L] = 1.3f - sin(a + 0.6f) * 0.45f
        p.pitch[Human.SHIN_R] = 1.3f + sin(a + 0.6f) * 0.45f
        p.pitch[Human.ARM_L] = -1.25f; p.pitch[Human.ARM_R] = -1.25f
        p.pitch[Human.FOREARM_L] = -0.2f; p.pitch[Human.FOREARM_R] = -0.2f
    }

    /** In the canoe: kneeling, the paddle swung from one side to the other on the beat. */
    private fun paddle(p: Pose, phase: Float) {
        sit(p)
        val a = sin(phase * 2 * PI.toFloat())
        p.pitch[Human.ARM_L] = -1.1f + a * 0.6f
        p.pitch[Human.ARM_R] = -1.1f - a * 0.6f
        p.yaw[Human.BODY] = a * 0.3f
    }

    /** On a horse: legs astride, hands on the reins, rocking with it. */
    private fun ride(p: Pose, t: Double) {
        p.pitch[Human.THIGH_L] = -0.95f; p.pitch[Human.THIGH_R] = -0.95f
        p.roll[Human.THIGH_L] = -0.45f; p.roll[Human.THIGH_R] = 0.45f
        p.pitch[Human.SHIN_L] = 1.05f; p.pitch[Human.SHIN_R] = 1.05f
        p.pitch[Human.ARM_L] = -0.8f; p.pitch[Human.ARM_R] = -0.8f
        p.pitch[Human.FOREARM_L] = -0.9f; p.pitch[Human.FOREARM_R] = -0.9f
        p.pitch[Human.BODY] = 0.1f + sin(t * 6).toFloat() * 0.04f
    }

    // ---- Four legs ----

    /** A trot: diagonal legs together, each bending at the knee as it comes forward. */
    private fun trot(p: Pose, phase: Float, go: Float, fast: Boolean) {
        val a = phase * 2 * PI.toFloat()
        val amp = (if (fast) 0.6f else 0.42f) * go
        for (leg in 0..3) {
            val off = if (leg == 0 || leg == 3) 0f else PI.toFloat()
            val s = sin(a + off)
            p.pitch[Quad.upper(leg)] = -s * amp
            val bend = max(0f, sin(a + off + 1.3f)) * amp * 1.8f
            p.pitch[Quad.lower(leg)] = if (leg < 2) bend else -bend
        }
        // The back rolls and dips with the diagonal pairs, the tail swings wider.
        p.roll[Quad.BODY] = sin(a) * 0.05f * go
        p.pitch[Quad.BODY] += sin(a * 2f) * 0.03f * go
        p.yaw[Quad.TAIL] += sin(a) * 0.3f * go
    }

    /** A gallop: front legs together, then the hind ones; the back rocks. */
    private fun gallop(p: Pose, phase: Float, go: Float, horse: Boolean) {
        val a = phase * 2 * PI.toFloat()
        val amp = 0.7f * go
        for (leg in 0..3) {
            val off = if (leg < 2) 0f else 1.9f
            val s = sin(a + off + (leg % 2) * 0.35f)
            p.pitch[Quad.upper(leg)] = -s * amp
            val bend = max(0f, sin(a + off + 1.4f)) * amp * 1.9f
            p.pitch[Quad.lower(leg)] = if (leg < 2) bend else -bend
        }
        p.pitch[Quad.BODY] = sin(a + 0.8f) * 0.06f * go * (if (horse) 1f else 1.5f)
    }

    /** Sitting up aboard: hind legs folded. */
    private fun sitDog(p: Pose) {
        p.pitch[Quad.BODY] = -0.35f
        p.pitch[Quad.upper(2)] = -1.1f; p.pitch[Quad.upper(3)] = -1.1f
        p.pitch[Quad.lower(2)] = 1.9f; p.pitch[Quad.lower(3)] = 1.9f
        p.pitch[Quad.upper(0)] = 0.3f; p.pitch[Quad.upper(1)] = 0.3f
    }

}

/** Shared with the renderer: how far ahead things are drawn. */
object WorldRendererLimits {
    const val FAR = 150f
}
