package com.sadurni.attacca.world

import com.sadurni.attacca.diorama.World
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.floor

/** A picture on a board in the land: the song's cover ([COVER]) or the sign with the next song ([NEXT]); corners as [Places.corners] gives them. */
class Billboard(val corners: FloatArray, val kind: Int) {
    companion object {
        const val COVER = 0
        const val NEXT = 1
    }
}

/**
 * Everything about the way besides the land and the travellers, the same for every screen that
 * draws Attacca World: the places' moving parts, what lives about, the boards with the cover and
 * the next song's sign, the desert's peak pyramid; and what the camera and the light want to know.
 */
class Extras(private val land: WorldLand, seed: Int) {

    private val life = Life(land, seed)
    private val festivities = Festivities(land)

    /** The festive days' look (null: none), with the Three Kings; [vikram]: the Hindu new year's show going on. */
    var festivity: com.sadurni.attacca.festive.Festivity? = null
    var kings = false
    var vikram = false
    private val smoke = ArrayList<Float>()

    /** The boards in sight this frame (the screen draws their pictures). */
    val billboards = ArrayList<Billboard>()

    private var pyramidRise = 0f
    private val pyramidPose = Props.peakPyramid.pose()

    /** Rows between boards with the cover. */
    private val coverEvery = 240f

    private val frameRig: Rig by lazy {
        val wood = if (land.look.world == World.CITY || land.look.world == World.NEON || land.look.world == World.SPACE) 0xFF3A3A44.toInt() else 0xFF6B4A2E.toInt()
        RigBuilder().apply {
            bone(-1, 0f, 0f, 0f) {
                for (x in floatArrayOf(-1.2f, 1.2f)) tube(x, -0.4f, -0.05f, x, 1.6f, -0.05f, 0.09f, wood)
                box(-1.7f, 1.45f, -0.12f, 1.7f, 4.75f, 0.05f, wood)
            }
        }.rig()
    }

    private val signRig: Rig by lazy {
        RigBuilder().apply {
            bone(-1, 0f, 0f, 0f) {
                tube(0f, -0.4f, -0.08f, 0f, 3.1f, -0.08f, 0.09f, 0xFF5A3A22.toInt())
                box(-1.75f, 1.45f, -0.12f, 1.75f, 2.95f, 0.04f, 0xFF8A5A34.toInt())
            }
        }.rig()
    }

    /** Where each board with the cover stands, as (z, side); none in the places or at the start and end. */
    private val covers: List<Pair<Float, Float>> = run {
        val out = ArrayList<Pair<Float, Float>>()
        // (Without the song's length, none: the land goes on without an end.)
        if (land.endZ == Float.MAX_VALUE) return@run out
        var z = 70f
        var k = 0
        while (z < land.endZ - WorldLand.ARRIVE - 20f) {
            val side = if (k % 2 == 0) 1f else -1f
            val x = Places.xAt(land, z, side * 5.2f)
            if (z > land.contEnd && Places.clearAt(land, land.places, x, z, side * 5.2f) && dry(z, side * 5.2f)) out += z to side
            z += coverEvery
            k++
        }
        out
    }

    /** Whether a board at row [z], [across] from the way's middle, stands on dry ground (under the sea all is water). */
    private fun dry(z: Float, across: Float): Boolean {
        if (land.lookAt(z).world == World.REEF) return true
        for (d in floatArrayOf(-1.6f, 0f, 1.6f)) {
            val x = Places.xAt(land, z + d, across)
            if (land.heightAt(x, z + d) < WorldLand.WATER + 0.3f) return false
        }
        return true
    }

    /** Where the sign with the next song stands (row, side): before the end, on dry ground by the way. */
    private val signAt: Pair<Float, Float> = run {
        val base = land.endZ - WorldLand.ARRIVE / 2 - 8f
        if (land.endZ == Float.MAX_VALUE) return@run base to 1f
        var d = 0f
        while (d <= 60f) {
            for (side in floatArrayOf(1f, -1f)) {
                val z = base - d
                if (dry(z, side * 3.1f) && Places.clearAt(land, land.places, Places.xAt(land, z, side * 3.1f), z, side * 3.1f)) return@run z to side
            }
            d += 2f
        }
        base to 1f
    }
    private val signZ = signAt.first

    /**
     * The figures and boards of this frame with the traveller at row [tz] (way height [ty]);
     * [peak] how far into the song's peak (0 … 1) and [peakZ] where it is (or null); [nextTitle]
     * whether there is a next song to show on the sign.
     */
    fun frame(tz: Float, ty: Float, nowMs: Long, playing: Boolean, peak: Float, peakZ: Float?, accent: Int, hasNext: Boolean, out: MutableList<Draw>) {
        billboards.clear()
        smoke.clear()
        Places.frame(land, land.places, tz, nowMs, accent, out, smoke)
        life.frame(tz, ty, nowMs, playing, out, smoke)
        festivities.frame(tz, nowMs, festivity, kings, vikram, out)
        // The boards with the cover by the way.
        for ((z, side) in covers) {
            if (z < tz - 30f || z > tz + WorldRendererLimits.FAR) continue
            val x = Places.xAt(land, z, side * 5.2f)
            val base = Mat4.model(x, land.heightAt(x, z), z, land.headingAt(z) + PI.toFloat() - side * 0.35f)
            out += Draw(frameRig, base, frameRig.pose())
            billboards += Billboard(Places.corners(base, -1.5f, 1.6f, 1.5f, 4.6f, 0.08f), Billboard.COVER)
        }
        // The big screen in a neon city shows the cover too.
        for (p in land.places) if (p.kind == PlaceKind.SCREEN && p.mid > tz - 30f && p.mid < tz + WorldRendererLimits.FAR) {
            billboards += Billboard(Places.screenFace(land, p), Billboard.COVER)
        }
        // The sign with the next song, before the end.
        if (hasNext && signZ > tz - 20f && signZ < tz + WorldRendererLimits.FAR && land.endZ < Float.MAX_VALUE) {
            val x = Places.xAt(land, signZ, signAt.second * 3.1f)
            val base = Mat4.model(x, land.heightAt(x, signZ), signZ, land.headingAt(signZ) + PI.toFloat() - signAt.second * 0.45f)
            out += Draw(signRig, base, signRig.pose())
            billboards += Billboard(Places.corners(base, -1.62f, 1.55f, 1.62f, 2.85f, 0.06f), Billboard.NEXT)
        }
        // The desert's peak: a great pyramid rises from the sand beside the way, ahead, and stays
        // where it is (seen as the camera comes round to it, not carried along in the sky).
        if (land.look.world == World.DESERT && peakZ != null) {
            pyramidRise = maxOf(pyramidRise, peak)
            if (pyramidRise > 0.01f) {
                val (px, pz) = pyramidSpot(peakZ)
                val ground = minOf(land.heightAt(px - 18f, pz), land.heightAt(px + 18f, pz), land.heightAt(px, pz - 18f), land.heightAt(px, pz + 18f))
                val rise = pyramidRise * pyramidRise * (3 - 2 * pyramidRise)
                out += Draw(Props.peakPyramid, Mat4.model(px, ground - 0.5f - (1 - rise) * 48f, pz, 0.6f, s = 5f), pyramidPose)
            }
        }
    }

    private var pyramidAt: Pair<Float, FloatArray>? = null

    /**
     * Where the desert's peak pyramid stands for a peak at row [peakZ]: well back from the way, in
     * the background, with nothing of its base on the way however it winds; found once and kept,
     * so it stays where it is.
     */
    private fun pyramidSpot(peakZ: Float): FloatArray {
        pyramidAt?.let { if (it.first == peakZ) return it.second }
        val pz = peakZ + 40f
        val turn = if ((land.centerAt(pz) - land.centerAt(pz - 20f)) > 0) -1f else 1f
        // The base is 60 wide (turned): clear of the way within its whole round, with room to spare.
        val reach = 44f
        fun clear(x: Float, z: Float): Boolean {
            for (ring in floatArrayOf(0f, reach * 0.5f, reach)) for (k in 0 until 16) {
                val a = k * 0.3927f
                val qx = x + cos(a) * ring
                val qz = z + sin(a) * ring
                if (abs(land.acrossAt(qx, qz)) < WorldLand.PATH_HALF + 6f) return false
            }
            return true
        }
        var spot: FloatArray? = null
        var d = 100f
        while (spot == null && d <= 175f) {
            for (side in floatArrayOf(turn, -turn)) {
                val x = land.centerAt(pz) + side * d
                if (clear(x, pz)) { spot = floatArrayOf(x, pz); break }
            }
            d += 10f
        }
        val at = spot ?: floatArrayOf(land.centerAt(pz) + turn * 185f, pz)
        pyramidAt = peakZ to at
        return at
    }

    /**
     * Whether the camera should follow close behind for a tunnel at the traveller's row [z]: a few
     * rows before its mouth (so it is already close and low as they go in, not hidden by the rock
     * above the mouth) until a few after it comes out (so it leaves the tunnel too before it opens up).
     */
    fun tunnelNear(z: Float): Boolean = land.places.any { it.kind == PlaceKind.TUNNEL && z > it.z0 - 14f && z < it.z1 + 9f }

    /** Whether row [z] is in a tunnel (the traveller lights a torch; the camera comes close). */
    fun tunnelAt(z: Float): Boolean = Places.tunnelAt(land.places, z) != null

    /** How far inside a tunnel row [z] is (0 out … 1 well in): for the dark there. */
    fun tunnelDepth(z: Float): Float {
        val p = Places.tunnelAt(land.places, z) ?: return 0f
        val d = minOf(z - p.z0, p.z1 - z)
        return (d / 5f).coerceIn(0f, 1f)
    }

    /** How high the camera may be at ([x], [z]): under a tunnel's roof, else no limit. */
    fun ceilingAt(x: Float, z: Float): Float {
        var ceiling = Float.POSITIVE_INFINITY
        val across = abs(land.acrossAt(x, z))
        Places.tunnelAt(land.places, z)?.let { if (across <= Places.TUNNEL_R + 0.5f) ceiling = land.pathAt(z) + Places.TUNNEL_R - 0.6f }
        for (p in land.places) {
            val dz = abs(z - p.mid)
            if (dz > 9f) continue
            // Under an arch over the way (or the high bridge over a river), never through it: lower
            // as it comes near, so the eye slips under its curve.
            val under = when (p.kind) {
                PlaceKind.ARCH -> {
                    val r = 8.6f
                    if (across >= r) continue
                    val up = kotlin.math.sqrt(r * r - across * across)
                    land.pathAt(p.mid) + up * 1.05f - (1.35f + 0.9f * (1f - up / r)) - 0.9f
                }
                PlaceKind.OVERPASS -> if (across < 17.5f) WorldLand.WATER + 9.3f else continue
                else -> continue
            }
            ceiling = minOf(ceiling, under + (dz - 2.8f).coerceAtLeast(0f) * 1.6f)
        }
        return ceiling
    }

    /** The top of a place's solid part near ([x], [z]) (for the camera), or minus infinity. */
    fun topNear(x: Float, z: Float): Float {
        var top = Places.topNear(land, land.places, x, z)
        // The boards with the cover and the sign: the camera goes over them, never through.
        for ((bz, side) in covers) {
            if (abs(z - bz) > 3f) continue
            val bx = Places.xAt(land, bz, side * 5.2f)
            if (kotlin.math.hypot(x - bx, z - bz) < 2.6f) top = maxOf(top, land.heightAt(bx, bz) + 5f)
        }
        if (abs(z - signZ) < 3f && land.endZ < Float.MAX_VALUE) {
            val sx = Places.xAt(land, signZ, signAt.second * 3.1f)
            if (kotlin.math.hypot(x - sx, z - signZ) < 2.6f) top = maxOf(top, land.heightAt(sx, signZ) + 3.2f)
        }
        return top
    }

    /** Something to turn to as it is passed (a big wheel, a rocket, a train, a pyramid…), or null. */
    fun sight(tz: Float): FloatArray? = Places.sight(land, land.places, tz) ?: life.sight() ?: Props.sight(land, tz)

    /** Whether it is dark at row [z] for a night of [night] (0 … 1): a light comes out. */
    fun darkAt(z: Float, night: Float): Boolean = night > 0.55f || land.lookAt(z).world == World.VOLCANO

    companion object {
        /** Rows from the song's start to row index, for the chunks (kept here for both screens). */
        fun chunkOf(z: Float) = floor(z / LandMesher.CHUNK).toInt()
    }
}
