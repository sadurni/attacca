package com.sadurni.attacca.world

import com.sadurni.attacca.diorama.Diorama
import com.sadurni.attacca.diorama.LANDMARK_EVERY_MS
import com.sadurni.attacca.diorama.Landmark
import com.sadurni.attacca.diorama.Traveller
import com.sadurni.attacca.diorama.World
import com.sadurni.attacca.diorama.landmarksOf
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** The places along the way, as O'rama's: what each is. */
enum class PlaceKind { BRIDGE, OVERPASS, TUNNEL, VILLAGE, STATION, WHEEL, SCREEN, RUINS, ARCH, BASE, ROCKET }

/**
 * A place along the way, from row [z0] to [z1] (a bridge over a river, a tunnel through a hill, a
 * station with its train, a village, a big wheel, ruins, an arch…), on the way's [side] (1 right,
 * -1 left) when it stands beside it.
 */
class Place(val kind: PlaceKind, val z0: Float, val z1: Float, val side: Float, val world: World, val seed: Int) {
    val mid: Float get() = (z0 + z1) / 2

    /** The level the way keeps over it (a bridge's deck). */
    var level = 0f
        internal set

    @Volatile internal var mesh: Mesh? = null

    /** Things the camera keeps clear of: x, z, reach, top (world). */
    internal var solids = FloatArray(0)
}

/**
 * The places of one song's land, at the same moments as O'rama's (its slots on the land's own
 * timeline, the same kinds for each world): what they do to the land, their stone and wood baked
 * into the land's chunks, and what moves on them (a big wheel turning, a train, a rocket going up).
 */
object Places {

    /** Rows a train's track runs past its station each way. */
    const val RUN = 46f

    /** A tunnel's inner radius, and a bridge's river's half width (rows). */
    const val TUNNEL_R = 3.0f
    const val RIVER = 6f

    /** The places of [land], with its song starting [originMs] into the land's own timeline (as O'rama's). */
    fun of(land: WorldLand, originMs: Double): List<Place> {
        val wave = land.wave ?: return emptyList()
        val out = ArrayList<Place>()
        val every = LANDMARK_EVERY_MS
        val endZ = land.zAt(wave.endMs.toDouble())
        val first = floor(originMs / every).toLong()
        val last = floor((originMs + wave.lengthMs) / every).toLong()
        var i = first
        while (i <= last) {
            val k = i.toInt()
            if (Diorama.hash(k, 501) <= 0.8f) {
                val atMs = i * every + 0.3 * every - originMs
                val z0 = (atMs / 1000.0 * WorldLand.BLOCKS_PER_SECOND).toFloat()
                val world = land.lookAt(z0).world
                val kinds = landmarksOf(world)
                val landmark = kinds[(Diorama.hash(k, 502) * kinds.size).toInt().coerceAtMost(kinds.size - 1)]
                val len = 1f + 0.5f * Diorama.hash(k, 503)
                val side = if (Diorama.hash(k, 504) < 0.5f) -1f else 1f
                val floats = land.look.traveller.let { it == Traveller.SHIP || it == Traveller.SUBMARINE || it == Traveller.CANOE || it == Traveller.ICEBERG }
                val water = land.rawPathAt(z0 + 10f) < WorldLand.WATER + 0.3f
                val kind = when (landmark) {
                    Landmark.BRIDGE -> if (water || floats) PlaceKind.OVERPASS else PlaceKind.BRIDGE
                    Landmark.TUNNEL -> if (water || floats) PlaceKind.OVERPASS else PlaceKind.TUNNEL
                    Landmark.VILLAGE -> PlaceKind.VILLAGE
                    Landmark.STATION -> if (water || floats) PlaceKind.OVERPASS else PlaceKind.STATION
                    Landmark.WHEEL -> PlaceKind.WHEEL
                    Landmark.SCREEN -> PlaceKind.SCREEN
                    Landmark.RUINS -> PlaceKind.RUINS
                    Landmark.ARCH -> PlaceKind.ARCH
                    Landmark.BASE -> PlaceKind.BASE
                    Landmark.ROCKET -> PlaceKind.ROCKET
                }
                val rows = when (kind) {
                    PlaceKind.BRIDGE -> 26f
                    PlaceKind.OVERPASS -> 6f
                    PlaceKind.TUNNEL -> 20f + 14f * len
                    PlaceKind.VILLAGE -> 40f + 20f * len
                    PlaceKind.STATION -> 24f
                    PlaceKind.RUINS -> 26f * len
                    PlaceKind.BASE -> 22f
                    else -> 8f
                }
                // Not where the land still goes on from the last song, nor near the end (the sign, the next world).
                val reach = if (kind == PlaceKind.STATION) RUN else 6f
                if (z0 - reach > WorldLand.CONT + WorldLand.CONT_BLEND + 6 && z0 + rows + reach < endZ - WorldLand.ARRIVE - 10) {
                    out += Place(kind, z0, z0 + rows, side, world, k)
                }
            }
            i++
        }
        for (p in out) {
            if (p.kind == PlaceKind.BRIDGE) {
                var s = 0f
                for (j in 0..8) s += land.rawPathAt(p.z0 + (p.z1 - p.z0) * j / 8f)
                // High enough over the river for its arches.
                p.level = max(s / 9f, WorldLand.WATER + 2.4f)
            }
        }
        return out
    }

    // ---- What a place does to the land ----

    /** The way's height at [z] given its own [raw] height: level over a bridge. */
    fun path(places: List<Place>, z: Float, raw: Float): Float {
        for (p in places) {
            if (p.kind != PlaceKind.BRIDGE || z < p.z0 - 7f || z > p.z1 + 7f) continue
            val k = smoothstep(p.z0 - 7f, p.z0, z) * (1f - smoothstep(p.z1, p.z1 + 7f, z))
            return raw + (p.level - raw) * k
        }
        return raw
    }

    /** The land's height at ([x], [z]), [across] from the way's middle (path [p]), given its own [h]. */
    fun height(land: WorldLand, places: List<Place>, x: Float, z: Float, across: Float, p: Float, h: Float): Float {
        var out = h
        for (pl in places) {
            if (z < pl.z0 - RUN - 8f || z > pl.z1 + RUN + 8f) continue
            val ax = abs(across)
            when (pl.kind) {
                PlaceKind.BRIDGE -> {
                    // A river across the land under the bridge.
                    val d = 1f - smoothstep(RIVER, RIVER + 4f, abs(z - pl.mid))
                    if (d > 0f) out += (WorldLand.WATER - 2.4f - out) * d * smoothstep(WorldLand.PATH_HALF, WorldLand.PATH_HALF + 1.2f, ax)
                }
                PlaceKind.TUNNEL -> {
                    // A hill over the way, the tunnel through it.
                    val k = smoothstep(pl.z0 - 8f, pl.z0 - 0.5f, z) * (1f - smoothstep(pl.z1 + 0.5f, pl.z1 + 8f, z))
                    if (k > 0f) {
                        // Level inside and under the shell's wall (the land's points are a row apart: a slope
                        // up from the floor would show inside), the hill rising past it.
                        val wall = TUNNEL_R + 1.2f
                        if (ax < wall && z > pl.z0 - 0.5f && z < pl.z1 + 0.5f) out = p + (out - p) * (1f - k)
                        else if (ax >= wall - 0.4f) out = max(out, p + k * (TUNNEL_R + 1.1f + min(8f, (ax - wall) * 0.75f)))
                    }
                }
                PlaceKind.STATION -> {
                    // Level ground between the way and the track.
                    val s = across * pl.side
                    if (s > 1.2f) {
                        val k = smoothstep(pl.z0 - RUN - 8f, pl.z0 - RUN, z) * (1f - smoothstep(pl.z1 + RUN, pl.z1 + RUN + 8f, z)) * (1f - smoothstep(11f, 15f, s))
                        out += (p - out) * k
                    }
                }
                PlaceKind.VILLAGE -> {
                    val k = smoothstep(pl.z0 - 8f, pl.z0, z) * (1f - smoothstep(pl.z1, pl.z1 + 8f, z)) * (1f - smoothstep(16f, 24f, ax)) * 0.75f
                    out += (p + 0.2f - out) * k
                }
                PlaceKind.WHEEL, PlaceKind.BASE, PlaceKind.ROCKET, PlaceKind.SCREEN -> {
                    val (sx, sz) = spot(land, pl)
                    val r = padRadius(pl.kind)
                    val d = kotlin.math.hypot(x - sx, z - sz)
                    if (d < r + 5f) {
                        val k = 1f - smoothstep(r, r + 5f, d)
                        out += (p + 0.3f - out) * k
                    }
                }
                else -> {}
            }
        }
        return out
    }

    /** Whether nothing else should stand at ([x], [z]) (the places keep their ground clear). */
    fun clearAt(land: WorldLand, places: List<Place>, x: Float, z: Float, across: Float): Boolean {
        for (pl in places) {
            if (z < pl.z0 - RUN - 4f || z > pl.z1 + RUN + 4f) continue
            val ax = abs(across)
            val inside = z > pl.z0 - 6f && z < pl.z1 + 6f
            when (pl.kind) {
                PlaceKind.BRIDGE -> if (abs(z - pl.mid) < RIVER + 5f && ax < 9f) return false
                PlaceKind.OVERPASS -> if (inside && ax < 20f) return false
                PlaceKind.TUNNEL -> if (z > pl.z0 - 9f && z < pl.z1 + 9f && ax < 16f) return false
                PlaceKind.STATION -> if (across * pl.side in -3f..14f && z > pl.z0 - RUN - 2f && z < pl.z1 + RUN + 2f) return false
                PlaceKind.RUINS, PlaceKind.ARCH -> if (inside && ax < 11f) return false
                PlaceKind.WHEEL, PlaceKind.BASE, PlaceKind.ROCKET, PlaceKind.SCREEN -> {
                    val (sx, sz) = spot(land, pl)
                    if (kotlin.math.hypot(x - sx, z - sz) < padRadius(pl.kind) + 4f) return false
                }
                PlaceKind.VILLAGE -> {}
            }
        }
        return true
    }

    /** Whether row [z] is in a village (more houses, both sides of the way). */
    fun villageAt(places: List<Place>, z: Float): Boolean = places.any { it.kind == PlaceKind.VILLAGE && z in it.z0..it.z1 }

    /** The tunnel row [z] is in, if any. */
    fun tunnelAt(places: List<Place>, z: Float): Place? = places.firstOrNull { it.kind == PlaceKind.TUNNEL && z > it.z0 - 0.5f && z < it.z1 + 0.5f }

    private fun padRadius(k: PlaceKind) = when (k) {
        PlaceKind.WHEEL -> 7f
        PlaceKind.BASE -> 10f
        PlaceKind.ROCKET -> 6f
        else -> 3f
    }

    /** Where a place standing beside the way stands (x, z). */
    fun spot(land: WorldLand, p: Place): Pair<Float, Float> {
        val dist = when (p.kind) {
            PlaceKind.WHEEL -> 18f
            PlaceKind.BASE -> 16f
            PlaceKind.ROCKET -> 17f
            PlaceKind.SCREEN -> 11f
            else -> 0f
        }
        return xAt(land, p.mid, p.side * dist) to p.mid
    }

    /** The x of the point [across] from the way's middle at row [z]. */
    fun xAt(land: WorldLand, z: Float, across: Float): Float = land.centerAt(z) + across / cos(land.headingAt(z))

    // ---- Stone and wood, baked into the land's chunks ----

    /** Adds the triangles of [land]'s places whose middle is in rows [z0, z1) to [out]. */
    fun bake(land: WorldLand, places: List<Place>, z0: Int, z1: Int, out: FloatList) {
        for (p in places) {
            if (p.z1 + RUN + 20f < z0 || p.z0 - RUN - 20f > z1) continue
            val m = meshOf(land, p)
            val d = m.data
            val tri = Mesh.STRIDE * 3
            var i = 0
            while (i < d.size) {
                val cz = (d[i + 2] + d[i + Mesh.STRIDE + 2] + d[i + 2 * Mesh.STRIDE + 2]) / 3f
                if (cz >= z0 && cz < z1) for (j in 0 until tri) out.add(d[i + j])
                i += tri
            }
        }
    }

    private fun meshOf(land: WorldLand, p: Place): Mesh {
        p.mesh?.let { return it }
        synchronized(p) {
            p.mesh?.let { return it }
            val out = FloatList(16384)
            val solids = ArrayList<Float>()
            when (p.kind) {
                PlaceKind.BRIDGE -> bridge(land, p, out)
                PlaceKind.OVERPASS -> overpass(land, p, out, solids)
                PlaceKind.TUNNEL -> tunnel(land, p, out)
                PlaceKind.STATION -> station(land, p, out, solids)
                PlaceKind.SCREEN -> screen(land, p, out, solids)
                PlaceKind.RUINS -> ruins(land, p, out, solids)
                PlaceKind.ARCH -> arch(land, p, out, solids)
                PlaceKind.BASE -> base(land, p, out, solids)
                PlaceKind.ROCKET -> pad(land, p, out, solids)
                PlaceKind.WHEEL -> {
                    val (sx, sz) = spot(land, p)
                    solids += listOf(sx, sz, 4f, land.pathAt(sz) + 16f)
                }
                PlaceKind.VILLAGE -> {}
            }
            p.solids = solids.toFloatArray()
            return out.mesh().also { p.mesh = it }
        }
    }

    /** Builds [build] in a frame of its own and adds it moved to (x, y, z), turned [yaw]. */
    private fun FloatList.at(x: Float, y: Float, z: Float, yaw: Float, build: FloatList.() -> Unit) {
        val m = FloatList(2048).apply(build).mesh()
        Props.bakeMesh(m, x, y, z, yaw, 1f, this)
    }

    private fun FloatList.quad(
        ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float,
        cx: Float, cy: Float, cz: Float, dx: Float, dy: Float, dz: Float, c: Int,
    ) {
        tri(ax, ay, az, bx, by, bz, cx, cy, cz, c)
        tri(ax, ay, az, cx, cy, cz, dx, dy, dz, c)
    }

    private fun stoneOf(w: World): Int = when (w) {
        World.CITY -> 0xFFA4A6AE.toInt()
        World.NEON -> 0xFF3A3450.toInt()
        World.CASTLE -> 0xFF77708A.toInt()
        World.VOLCANO -> 0xFF4A3E3C.toInt()
        World.JAPAN -> 0xFFC8322A.toInt()
        World.JUNGLE, World.AMAZON, World.CANYON -> 0xFF8A5A34.toInt()
        World.DESERT -> 0xFFD8B880.toInt()
        World.WALL -> 0xFFB8A888.toInt()
        World.ICE -> 0xFFCFE6F4.toInt()
        else -> 0xFFB8AFA0.toInt()
    }

    private fun bridge(land: WorldLand, p: Place, out: FloatList) {
        val stone = stoneOf(p.world)
        val deck = if (p.world == World.JAPAN) 0xFF8A5A34.toInt() else Props.shadeOf(stone, 0.9f)
        val rail = if (p.world == World.NEON) Props.glow(0xFF46EAF2.toInt()) else stone
        val from = p.mid - RIVER - 5f
        val to = p.mid + RIVER + 5f
        var z = from
        while (z < to) {
            val zc = z + 1f
            val h = land.headingAt(zc)
            val cx = land.centerAt(zc)
            val y = p.level
            // The opening under each arch (two arches between three piers), or the bank.
            val sideBottom = FloatArray(2)
            for ((k, s) in floatArrayOf(-1f, 1f).withIndex()) {
                val half = (RIVER + 0.3f) / 2
                val inRiver = abs(zc - p.mid) < RIVER + 0.3f
                sideBottom[k] = if (inRiver) {
                    val c = if (zc < p.mid) p.mid - half else p.mid + half
                    val u = ((zc - c) / half).coerceIn(-1f, 1f)
                    WorldLand.WATER - 0.4f + (y - 0.9f - (WorldLand.WATER - 0.4f)) * sqrt(max(0f, 1f - u * u))
                } else {
                    land.heightAt(xAt(land, zc, s * 1.9f), zc) - 0.4f
                }
            }
            out.at(cx, y, zc, h) {
                box(-1.95f, -0.55f, -1.05f, 1.95f, 0.04f, 1.05f, deck)
                box(-1.98f, 0.04f, -1.05f, -1.72f, 0.62f, 1.05f, rail)
                box(1.72f, 0.04f, -1.05f, 1.98f, 0.62f, 1.05f, rail)
                for ((k, s) in floatArrayOf(-1f, 1f).withIndex()) {
                    val b = sideBottom[k] - y
                    if (b < -0.6f) box(s * 1.98f - 0.12f, b, -1.05f, s * 1.98f + 0.12f, -0.5f, 1.05f, stone)
                }
            }
            z += 2f
        }
        // Piers into the water.
        for (pz in floatArrayOf(p.mid - RIVER - 0.3f, p.mid, p.mid + RIVER + 0.3f)) {
            out.at(land.centerAt(pz), p.level, pz, land.headingAt(pz)) {
                box(-1.8f, WorldLand.WATER - 3.2f - p.level, -0.75f, 1.8f, -0.5f, 0.75f, stone)
            }
        }
    }

    private fun overpass(land: WorldLand, p: Place, out: FloatList, solids: MutableList<Float>) {
        val wood = if (p.world == World.CITY || p.world == World.NEON) 0xFF8A8C94.toInt() else 0xFF8A5A34.toInt()
        val dark = Props.shadeOf(wood, 0.7f)
        val z = p.mid
        val y = WorldLand.WATER + 10.5f
        out.at(land.centerAt(z), y, z, land.headingAt(z)) {
            var x = -17f
            var k = 0
            while (x < 17f) {
                box(x, -0.4f, -1.3f, x + 0.9f, 0f, 1.3f, if (k % 2 == 0) wood else dark)
                x += 1f
                k++
            }
            for (s in floatArrayOf(-1.25f, 1.25f)) tube(-17f, 1.1f, s, 17f, 1.1f, s, 0.06f, dark)
            for (s in floatArrayOf(-17.5f, 17.5f)) {
                for (zz in floatArrayOf(-1.3f, 1.3f)) tube(s, -y + WorldLand.WATER - 3f, zz, s, 2.2f, zz, 0.28f, dark)
            }
        }
        for (s in floatArrayOf(-17.5f, 17.5f)) solids += listOf(xAt(land, z, s), z, 2.5f, y + 2.5f)
    }

    private fun tunnel(land: WorldLand, p: Place, out: FloatList) {
        val rock = Bands.of(p.world).rock
        val inner = 0xFF5A5048.toInt()
        val stone = Props.shadeOf(stoneOf(p.world), 1.05f)
        val r0 = TUNNEL_R
        val r1 = TUNNEL_R + 0.5f
        val steps = 12
        fun ring(z: Float, r: Float, a: Float): FloatArray {
            val h = land.headingAt(z)
            val cx = land.centerAt(z)
            val cy = land.pathAt(z) - 0.3f
            val across = cos(a) * r
            return floatArrayOf(cx + across * cos(h), cy + sin(a) * (r + 0.3f), z - across * sin(h))
        }
        var z = p.z0
        while (z < p.z1) {
            val z2 = min(z + 1f, p.z1)
            for (k in 0 until steps) {
                val a0 = (PI * k / steps).toFloat()
                val a1 = (PI * (k + 1) / steps).toFloat()
                // Inside, seen from within (dark), and the outside's rock.
                val i00 = ring(z, r0, a0); val i01 = ring(z, r0, a1); val i10 = ring(z2, r0, a0); val i11 = ring(z2, r0, a1)
                out.quad(i00[0], i00[1], i00[2], i10[0], i10[1], i10[2], i11[0], i11[1], i11[2], i01[0], i01[1], i01[2], inner)
                val o00 = ring(z, r1, a0); val o01 = ring(z, r1, a1); val o10 = ring(z2, r1, a0); val o11 = ring(z2, r1, a1)
                out.quad(o00[0], o00[1], o00[2], o01[0], o01[1], o01[2], o11[0], o11[1], o11[2], o10[0], o10[1], o10[2], rock)
            }
            z = z2
        }
        // The mouths: a ring of stone at each end.
        for ((end, dir) in listOf(p.z0 to -1f, p.z1 to 1f)) {
            for (k in 0 until steps) {
                val a0 = (PI * k / steps).toFloat()
                val a1 = (PI * (k + 1) / steps).toFloat()
                val a = ring(end, r0 - 0.15f, a0); val b = ring(end, r0 - 0.15f, a1)
                val c = ring(end, r1 + 0.7f, a1); val d = ring(end, r1 + 0.7f, a0)
                if (dir < 0) out.quad(a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2], d[0], d[1], d[2], stone)
                else out.quad(a[0], a[1], a[2], d[0], d[1], d[2], c[0], c[1], c[2], b[0], b[1], b[2], stone)
            }
        }
        // Torches on the walls inside, every few rows on either side.
        var tz = p.z0 + 3f
        var k = 0
        while (tz < p.z1 - 2f) {
            val s = if (k % 2 == 0) 1f else -1f
            val x = xAt(land, tz, s * (r0 - 0.85f))
            val y = land.pathAt(tz) + 1.7f
            out.tube(x, y - 0.3f, tz, x, y, tz, 0.05f, 0xFF3A2A20.toInt())
            out.blob(x - 0.12f, y - 0.02f, tz - 0.12f, x + 0.12f, y + 0.3f, tz + 0.12f, Props.glow(0xFFFFB04A.toInt()))
            tz += 6f
            k++
        }
        // Rock over the top.
        var rz = p.z0 + 1f
        while (rz < p.z1) {
            val x = land.centerAt(rz)
            // On the shell's outside, never hanging into the tunnel.
            val y = land.pathAt(rz) + r1 + 0.3f
            out.blob(x - 2.6f, y - 0.1f, rz - 1.6f, x + 2.6f, y + 0.9f + 0.6f * land.noise(rz * 0.3f, 2f), rz + 1.6f, rock)
            rz += 2.5f
        }
    }

    private fun station(land: WorldLand, p: Place, out: FloatList, solids: MutableList<Float>) {
        val s = p.side
        val stone = 0xFFB8AFA0.toInt()
        // The platform.
        var z = p.z0
        while (z < p.z1) {
            val zc = z + 1f
            out.at(xAt(land, zc, s * 3.1f), land.pathAt(zc), zc, land.headingAt(zc)) {
                box(-1.4f, -0.4f, -1.05f, 1.4f, 0.5f, 1.05f, stone)
                box(-1.45f, 0.5f, -1.05f, 1.45f, 0.56f, 1.05f, 0xFFE8E2D0.toInt())
            }
            z += 2f
        }
        // The station.
        val hz = p.mid
        val wall = if (p.world == World.CANYON) 0xFFC89A6A.toInt() else 0xFFE8DCC4.toInt()
        out.at(xAt(land, hz, s * 6.6f), land.pathAt(hz), hz, land.headingAt(hz)) {
            box(-1.8f, -0.4f, -3.4f, 1.8f, 2.8f, 3.4f, wall)
            gable(-2.1f, 2.75f, -3.7f, 2.1f, 4.1f, 3.7f, 0xFF8A4A36.toInt())
            box(-s * 1.86f - 0.05f, 0.5f, -0.5f, -s * 1.86f + 0.05f, 2.0f, 0.5f, 0xFF6B4A2E.toInt())
            for (wz in floatArrayOf(-2.2f, 2.2f)) box(-s * 1.86f - 0.05f, 1.1f, wz - 0.45f, -s * 1.86f + 0.05f, 2.0f, wz + 0.45f, Props.glow(0xFFFFE0A0.toInt()))
            box(-s * 1.9f - 0.05f, 2.2f, -1.4f, -s * 1.9f + 0.05f, 2.7f, 1.4f, 0xFF2E3A5A.toInt())
        }
        solids += listOf(xAt(land, hz, s * 6.6f), hz, 4f, land.pathAt(hz) + 4.5f)
        // The track: ballast, sleepers and rails, past the platform both ways.
        z = p.z0 - RUN
        while (z < p.z1 + RUN) {
            val zc = z + 0.5f
            out.at(xAt(land, zc, s * 8.6f), land.pathAt(zc), zc, land.headingAt(zc)) {
                box(-1.3f, -0.2f, -0.53f, 1.3f, 0.08f, 0.53f, 0xFF8A8478.toInt())
                box(-1.05f, 0.08f, -0.16f, 1.05f, 0.17f, 0.16f, 0xFF5A4030.toInt())
                for (rx in floatArrayOf(-0.72f, 0.72f)) box(rx - 0.05f, 0.17f, -0.53f, rx + 0.05f, 0.3f, 0.53f, 0xFF6E6E78.toInt())
            }
            z += 1f
        }
    }

    private fun screen(land: WorldLand, p: Place, out: FloatList, solids: MutableList<Float>) {
        val (sx, sz) = spot(land, p)
        val y = land.pathAt(sz)
        out.at(sx, y, sz, screenYaw(land, p)) {
            for (x in floatArrayOf(-3f, 3f)) tube(x, -1f, 0f, x, 3.6f, 0f, 0.22f, 0xFF2E2A3A.toInt())
            box(-4.3f, 3.3f, -0.25f, 4.3f, 8.3f, 0.08f, 0xFF1E1A2E.toInt())
            box(-4.4f, 3.2f, -0.2f, 4.4f, 3.35f, 0.12f, Props.glow(0xFFE8389E.toInt()))
            box(-4.4f, 8.25f, -0.2f, 4.4f, 8.4f, 0.12f, Props.glow(0xFF46EAF2.toInt()))
        }
        solids += listOf(sx, sz, 4.5f, y + 8.5f)
    }

    /** Which way the big screen faces: towards the traveller coming, turned to the way. */
    fun screenYaw(land: WorldLand, p: Place) = land.headingAt(p.mid) + PI.toFloat() - p.side * 0.45f

    /** The big screen's face (four corners, world), where the cover shows. */
    fun screenFace(land: WorldLand, p: Place): FloatArray {
        val (sx, sz) = spot(land, p)
        val base = Mat4.model(sx, land.pathAt(sz), sz, screenYaw(land, p))
        return corners(base, -4.05f, 3.5f, 4.05f, 8.05f, 0.1f)
    }

    /** The corners of the quad from (x0, y0) to (x1, y1) at local z under [base]: bottom left, bottom right, top right, top left. */
    fun corners(base: FloatArray, x0: Float, y0: Float, x1: Float, y1: Float, z: Float): FloatArray {
        val out = FloatArray(12)
        fun put(i: Int, x: Float, y: Float) {
            out[i * 3] = base[0] * x + base[4] * y + base[8] * z + base[12]
            out[i * 3 + 1] = base[1] * x + base[5] * y + base[9] * z + base[13]
            out[i * 3 + 2] = base[2] * x + base[6] * y + base[10] * z + base[14]
        }
        put(0, x0, y0); put(1, x1, y0); put(2, x1, y1); put(3, x0, y1)
        return out
    }

    private fun ruins(land: WorldLand, p: Place, out: FloatList, solids: MutableList<Float>) {
        val stone = when (p.world) {
            World.DESERT -> 0xFFD8B880.toInt()
            World.JUNGLE -> 0xFF8E9A72.toInt()
            World.VOLCANO -> 0xFF4E4444.toInt()
            World.CASTLE -> 0xFF8A8478.toInt()
            else -> 0xFFB8AFA0.toInt()
        }
        val moss = if (p.world == World.JUNGLE) 0xFF4E7A3A.toInt() else Props.shadeOf(stone, 0.85f)
        var z = p.z0
        var k = 0
        while (z < p.z1) {
            for (s in floatArrayOf(-1f, 1f)) {
                val q = Diorama.hash(p.seed * 31 + k * 2 + (if (s > 0) 1 else 0), 611)
                if (q < 0.18f) continue
                val x = xAt(land, z, s * 3.8f)
                val y = land.heightAt(x, z)
                val tall = if (q < 0.45f) 1.2f + q * 2f else 3.6f + q
                out.at(x, y, z, land.headingAt(z)) {
                    box(-0.6f, -0.6f, -0.6f, 0.6f, 0.3f, 0.6f, moss)
                    tube(0f, 0.3f, 0f, 0f, tall, 0f, 0.38f, stone)
                    if (tall > 3f) box(-0.65f, tall, -0.65f, 0.65f, tall + 0.4f, 0.65f, stone)
                }
                solids += listOf(x, z, 0.8f, y + tall + 0.5f)
            }
            z += 5f
            k++
        }
        // A fallen column by the way.
        val fz = p.mid + 2f
        val fx = xAt(land, fz, p.side * 6.5f)
        out.at(fx, land.heightAt(fx, fz) + 0.35f, fz, land.headingAt(fz) + 0.4f) { tube(0f, 0f, -2.2f, 0f, 0f, 2.2f, 0.38f, stone) }
    }

    private fun arch(land: WorldLand, p: Place, out: FloatList, solids: MutableList<Float>) {
        val z = p.mid
        val y = land.pathAt(z)
        val (a, b) = when (p.world) {
            World.ICE -> 0xFFCFE6F4.toInt() to 0xFFB4D6EE.toInt()
            World.REEF -> 0xFFFF8FB1.toInt() to 0xFFFFB84D.toInt()
            else -> 0xFFD49A62.toInt() to 0xFFC4874E.toInt()
        }
        out.at(land.centerAt(z), y, z, land.headingAt(z)) {
            val r = 8.6f
            // Thick at its feet, thinner at the top, smooth: many pieces overlapping.
            for (k in 0..36) {
                val t = (PI * k / 36).toFloat()
                val x = cos(t) * r
                val yy = sin(t) * r * 1.05f
                val w = 1.35f + 0.9f * (1f - sin(t))
                blob(x - w, yy - w, -1.7f, x + w, yy + w, 1.7f, if (k % 6 < 3) a else Props.shadeOf(a, 0.94f))
            }
            for (s in floatArrayOf(-r, r)) blob(s - 2.6f, -5f, -2.6f, s + 2.6f, 1.6f, 2.6f, b)
        }
        for (s in floatArrayOf(-8.6f, 8.6f)) solids += listOf(xAt(land, z, s), z, 2.8f, y + 4f)
    }

    private fun base(land: WorldLand, p: Place, out: FloatList, solids: MutableList<Float>) {
        val (sx, sz) = spot(land, p)
        val y = land.pathAt(sz) + 0.3f
        out.at(sx, y, sz, land.headingAt(sz)) {
            box(-9f, -3f, -7f, 9f, 0.05f, 7f, 0xFF8A8494.toInt())
            blob(-3.4f, -2.4f, -3.4f, 3.4f, 3.2f, 3.4f, 0xFFD8DCE4.toInt())
            blob(-3.45f, 0.6f, -3.45f, 3.45f, 1.0f, 3.45f, Props.glow(0xFF8EF0FF.toInt()))
            blob(3.5f, -1.6f, 1.2f, 7.5f, 2.2f, 5.2f, 0xFFD0D4DC.toInt())
            tube(2.4f, 0.6f, 1.2f, 4.6f, 0.6f, 2.6f, 0.6f, 0xFFB8BCC8.toInt())
            tube(-5f, 0f, -4f, -5f, 5f, -4f, 0.12f, 0xFFB8B8C4.toInt())
            blob(-6.4f, 4.8f, -4.4f, -3.6f, 5.6f, -3.2f, 0xFFE0E0E8.toInt())
            blob(-5.1f, 5.6f, -4.1f, -4.9f, 5.9f, -3.9f, Props.glow(0xFFFF5050.toInt()))
        }
        solids += listOf(sx, sz, 8f, y + 3.4f)
    }

    private fun pad(land: WorldLand, p: Place, out: FloatList, solids: MutableList<Float>) {
        val (sx, sz) = spot(land, p)
        val y = land.pathAt(sz) + 0.3f
        out.at(sx, y, sz, land.headingAt(sz)) {
            box(-4f, -3f, -4f, 4f, 0.3f, 4f, 0xFF6E6878.toInt())
            for (x in floatArrayOf(2.4f, 3.6f)) for (z in floatArrayOf(-0.6f, 0.6f)) tube(x, 0.3f, z, x, 11f, z, 0.12f, 0xFFB03A3A.toInt())
            for (k in 1..5) tube(2.4f, k * 2f, 0f, 3.6f, k * 2f, 0f, 0.08f, 0xFFB03A3A.toInt())
        }
        solids += listOf(sx, sz, 4.5f, y + 12f)
    }

    // ---- What moves ----

    private val wheelRigs = HashMap<World, Rig>()
    private val rocketRig: Rig by lazy {
        val b = RigBuilder()
        b.bone(-1, 0f, 0f, 0f) {
            tube(0f, 0.6f, 0f, 0f, 8.4f, 0f, 0.85f, 0xFFF2F2F6.toInt())
            blob(-0.9f, 7.6f, -0.9f, 0.9f, 10.4f, 0.9f, 0xFFD8322A.toInt())
            for (k in 0 until 4) {
                val a = k * 1.5708f
                val x = cos(a)
                val z = sin(a)
                box(x * 0.7f - 0.12f + (if (abs(x) < 0.5f) 0f else x * 0.4f), 0.2f, z * 0.7f - 0.12f + (if (abs(z) < 0.5f) 0f else z * 0.4f),
                    x * 0.7f + 0.12f + (if (abs(x) < 0.5f) 0f else x * 0.4f), 2.6f, z * 0.7f + 0.12f + (if (abs(z) < 0.5f) 0f else z * 0.4f), 0xFFD8322A.toInt())
            }
            blob(-0.3f, 5.6f, 0.7f, 0.3f, 6.2f, 0.92f, Props.glow(0xFF8EF0FF.toInt()))
        }
        b.rig()
    }

    /** The rocket's flame, once it goes. */
    private val flameRig: Rig by lazy {
        val b = RigBuilder()
        b.bone(-1, 0f, 0f, 0f) {
            blob(-0.65f, -3.4f, -0.65f, 0.65f, 0.5f, 0.65f, Props.glow(0xFFFF8A2A.toInt()))
            blob(-0.4f, -2.2f, -0.4f, 0.4f, 0.5f, 0.4f, Props.glow(0xFFFFE08A.toInt()))
        }
        b.rig()
    }

    private fun wheelRig(w: World): Rig = synchronized(wheelRigs) {
        wheelRigs.getOrPut(w) {
            val b = RigBuilder()
            val steel = if (w == World.NEON) 0xFF3A3450.toInt() else 0xFFE8E4DC.toInt()
            val hub = 8.8f
            val r = 7f
            b.bone(-1, 0f, 0f, 0f) {
                for (s in floatArrayOf(-1f, 1f)) for (z in floatArrayOf(-1.4f, 1.4f)) {
                    tube(s * 3.4f, 0f, z, 0f, hub, z * 0.45f, 0.16f, steel)
                    // Feet into the ground.
                    tube(s * 3.4f, -2f, z, s * 3.4f, 0f, z, 0.2f, steel)
                }
                box(-4.5f, -2.5f, -2f, 4.5f, 0.2f, 2f, 0xFF8A8478.toInt())
            }
            val wheel = b.bone(0, 0f, hub, 0f) {
                blob(-0.5f, hub - 0.5f, -0.9f, 0.5f, hub + 0.5f, 0.9f, steel)
                for (k in 0 until 16) {
                    val a0 = (2 * PI * k / 16).toFloat()
                    val a1 = (2 * PI * (k + 1) / 16).toFloat()
                    for (z in floatArrayOf(-0.5f, 0.5f)) {
                        tube(cos(a0) * r, hub + sin(a0) * r, z, cos(a1) * r, hub + sin(a1) * r, z, 0.09f, steel)
                    }
                    if (k % 2 == 0) for (z in floatArrayOf(-0.5f, 0.5f)) tube(0f, hub, z * 0.6f, cos(a0) * r, hub + sin(a0) * r, z, 0.05f, steel)
                    val light = if (k % 2 == 0) 0xFFFFE08A.toInt() else (if (w == World.NEON) 0xFFE8389E.toInt() else 0xFFFF6A6A.toInt())
                    blob(cos(a0) * r - 0.15f, hub + sin(a0) * r - 0.15f, -0.65f, cos(a0) * r + 0.15f, hub + sin(a0) * r + 0.15f, -0.4f, Props.glow(light))
                }
            }
            val colours = intArrayOf(0xFFE8443A.toInt(), 0xFFF5D547.toInt(), 0xFF3F8FD9.toInt(), 0xFF5DBB63.toInt())
            for (k in 0 until 8) {
                val a = (2 * PI * k / 8).toFloat()
                val px = cos(a) * r
                val py = hub + sin(a) * r
                b.bone(wheel, px, py, 0f) {
                    tube(px, py, 0f, px, py - 0.6f, 0f, 0.04f, steel)
                    blob(px - 0.6f, py - 1.8f, -0.55f, px + 0.6f, py - 0.55f, 0.55f, colours[k % 4])
                }
            }
            b.rig()
        }
    }

    private val trainRigs = HashMap<Boolean, List<Rig>>()

    /** An engine and three cars (a steam engine in the far west). */
    private fun train(steam: Boolean, accent: Int): List<Rig> = synchronized(trainRigs) {
        trainRigs.getOrPut(steam) {
            val engine = RigBuilder().apply {
                bone(-1, 0f, 0f, 0f) {
                    if (steam) {
                        tube(0f, 1.3f, -0.6f, 0f, 1.3f, 1.8f, 0.7f, 0xFF2A2A30.toInt())
                        box(-0.85f, 0.4f, -2f, 0.85f, 2.6f, -0.5f, 0xFF8A2A22.toInt())
                        box(-0.95f, 2.6f, -2.1f, 0.95f, 2.8f, -0.4f, 0xFF2A2A30.toInt())
                        tube(0f, 1.8f, 1.4f, 0f, 2.8f, 1.4f, 0.22f, 0xFF2A2A30.toInt())
                        blob(-0.2f, 1.25f, 2.0f, 0.2f, 1.6f, 2.15f, Props.glow(0xFFFFE6A0.toInt()))
                        box(-0.9f, 0.2f, 1.6f, 0.9f, 0.5f, 2.3f, 0xFF8A2A22.toInt())
                    } else {
                        box(-0.9f, 0.35f, -2f, 0.9f, 2.3f, 1.4f, accent)
                        blob(-0.9f, 0.35f, 0.6f, 0.9f, 2.3f, 2.3f, accent)
                        box(-0.92f, 1.4f, 1.2f, 0.92f, 1.9f, 1.9f, 0xFF2E3A5A.toInt())
                        box(-0.92f, 0.7f, -2f, 0.92f, 0.9f, 1.4f, 0xFFF2F2F6.toInt())
                        blob(-0.25f, 0.7f, 2.2f, 0.25f, 0.95f, 2.32f, Props.glow(0xFFFFF4C8.toInt()))
                    }
                    for (z in floatArrayOf(-1.4f, 0f, 1.3f)) for (x in floatArrayOf(-0.75f, 0.75f)) blob(x - 0.1f, 0f, z - 0.4f, x + 0.1f, 0.8f, z + 0.4f, 0xFF1E1E22.toInt())
                }
            }.rig()
            val car = RigBuilder().apply {
                bone(-1, 0f, 0f, 0f) {
                    val body = if (steam) 0xFF6E3A22.toInt() else 0xFFE8E4DC.toInt()
                    box(-0.85f, 0.35f, -2f, 0.85f, 2.2f, 2f, body)
                    box(-0.9f, 2.2f, -2.05f, 0.9f, 2.4f, 2.05f, Props.shadeOf(body, 0.75f))
                    for (z in floatArrayOf(-1.3f, 0f, 1.3f)) for (x in floatArrayOf(-0.88f, 0.88f)) {
                        box(x - 0.03f, 1.2f, z - 0.4f, x + 0.03f, 1.8f, z + 0.4f, Props.glow(0xFFFFE6A0.toInt()))
                    }
                    for (z in floatArrayOf(-1.4f, 1.4f)) for (x in floatArrayOf(-0.75f, 0.75f)) blob(x - 0.1f, 0f, z - 0.4f, x + 0.1f, 0.8f, z + 0.4f, 0xFF1E1E22.toInt())
                }
            }.rig()
            listOf(engine, car, car, car)
        }
    }

    private val poses = HashMap<Any, Pose>()
    private fun poseOf(key: Any, rig: Rig) = synchronized(poses) { poses.getOrPut(key) { rig.pose() } }.also { it.reset() }

    /** Where the train's engine is (row) with the traveller at [tz]: it comes from behind and overtakes along the track. */
    fun trainHead(p: Place, tz: Float): Float = (p.z0 - RUN) + (tz - (p.z0 - RUN - 14f)) * 2.6f

    /** Rows over which the rocket waits, then goes up. */
    private const val LAUNCH = 30f

    /**
     * What moves on the places in sight with the traveller at [tz]: the big wheel, the train, the
     * rocket; [smoke] gets the puffs (x, y, z, age 0 … 1) of a steam engine or a launch.
     */
    fun frame(land: WorldLand, places: List<Place>, tz: Float, nowMs: Long, accent: Int, out: MutableList<Draw>, smoke: MutableList<Float>) {
        val t = nowMs / 1000.0
        for (p in places) {
            if (p.z1 + RUN < tz - 40f || p.z0 - RUN > tz + WorldRendererLimits.FAR) continue
            when (p.kind) {
                PlaceKind.WHEEL -> {
                    val rig = wheelRig(p.world)
                    val pose = poseOf(p, rig)
                    val turn = (t * 0.22).toFloat() % (2 * PI.toFloat())
                    pose.roll[1] = turn
                    for (k in 2 until rig.bones.size) pose.roll[k] = -turn
                    val (sx, sz) = spot(land, p)
                    out += Draw(rig, Mat4.model(sx, land.pathAt(sz) + 0.3f, sz, land.headingAt(sz)), pose)
                }
                PlaceKind.STATION -> {
                    val head = trainHead(p, tz)
                    val start = p.z0 - RUN + 0.5f
                    val end = p.z1 + RUN - 0.5f
                    val steam = p.world == World.CANYON
                    val rigs = train(steam, accent or (0xFF shl 24))
                    for ((i, rig) in rigs.withIndex()) {
                        val cz = head - 2.2f - i * 4.3f
                        if (cz < start + 2f || cz > end - 2f) continue
                        val h = land.headingAt(cz)
                        out += Draw(rig, Mat4.model(xAt(land, cz, p.side * 8.6f), land.pathAt(cz) + 0.3f, cz, h), poseOf("train${p.seed}_$i", rig))
                    }
                    if (steam && head - 1f > start && head - 1f < end) {
                        // Puffs left behind the chimney as the engine goes.
                        for (k in 0 until 7) {
                            val age = ((t * 1.6 + k / 7.0) % 1.0).toFloat()
                            val pz = head - 0.8f - age * 6f
                            if (pz < start) continue
                            smoke += xAt(land, pz, p.side * 8.6f); smoke += land.pathAt(pz) + 3.4f + age * 3f; smoke += pz; smoke += age
                        }
                    }
                }
                PlaceKind.ROCKET -> {
                    val (sx, sz) = spot(land, p)
                    val ground = land.pathAt(sz) + 0.6f
                    val since = (tz - (p.mid - LAUNCH)) / WorldLand.BLOCKS_PER_SECOND
                    val rise = if (since > 0f) 0.5f * 2.4f * since * since else 0f
                    if (rise > 260f) continue
                    val rig = rocketRig
                    val pose = poseOf(p, rig)
                    out += Draw(rig, Mat4.model(sx, ground + rise, sz, 0f), pose)
                    // The flame only once it goes, flickering.
                    if (since > 0f) out += Draw(flameRig, Mat4.model(sx, ground + rise + 0.4f, sz, (t * 7).toFloat(), s = 0.9f + 0.25f * sin(t * 31).toFloat()), poseOf("flame", flameRig))
                    if (since > -1.5f && since < 6f) {
                        for (k in 0 until 8) {
                            val age = ((t * 1.2 + k / 8.0) % 1.0).toFloat()
                            val a = k * 0.785f
                            smoke += sx + cos(a) * age * 4f; smoke += ground + 0.4f + age * 1.5f; smoke += sz + sin(a) * age * 4f; smoke += age
                        }
                    }
                }
                else -> {}
            }
        }
    }

    /** Whether the rocket's flame shows (it has gone up) with the traveller at [tz]. */
    fun launched(p: Place, tz: Float) = tz > p.mid - LAUNCH

    /** The top of a place's solid parts within [margin] of ([x], [z]), or minus infinity (for the camera). */
    fun topNear(land: WorldLand, places: List<Place>, x: Float, z: Float, margin: Float = 1.2f): Float {
        var top = Float.NEGATIVE_INFINITY
        for (p in places) {
            if (z < p.z0 - RUN - 20f || z > p.z1 + RUN + 20f) continue
            meshOf(land, p)
            val s = p.solids
            var i = 0
            while (i < s.size) {
                if (kotlin.math.hypot(x - s[i], z - s[i + 1]) < s[i + 2] + margin) top = max(top, s[i + 3])
                i += 4
            }
        }
        return top
    }

    /** Something worth turning to as the traveller at [tz] passes it (its middle: x, y, z), if any. */
    fun sight(land: WorldLand, places: List<Place>, tz: Float): FloatArray? {
        for (p in places) {
            if (p.mid < tz - 6f || p.mid > tz + 34f) continue
            when (p.kind) {
                PlaceKind.WHEEL, PlaceKind.BASE, PlaceKind.ROCKET, PlaceKind.SCREEN -> {
                    val (sx, sz) = spot(land, p)
                    val h = when (p.kind) { PlaceKind.WHEEL -> 9f; PlaceKind.ROCKET -> 6f + (if (launched(p, tz)) 10f else 0f); PlaceKind.SCREEN -> 5.5f; else -> 2f }
                    return floatArrayOf(sx, land.pathAt(sz) + h, sz)
                }
                PlaceKind.STATION -> {
                    val head = trainHead(p, tz)
                    if (head > tz - 4f && head < tz + 30f) return floatArrayOf(xAt(land, head - 6f, p.side * 8.6f), land.pathAt(head) + 1.5f, head - 6f)
                }
                else -> {}
            }
        }
        return null
    }

    private fun smoothstep(a: Float, b: Float, x: Float): Float {
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }
}
