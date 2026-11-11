package com.sadurni.attacca.world

import com.sadurni.attacca.diorama.World
import kotlin.math.PI
import kotlin.math.abs
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
            if (z > WorldLand.CONT + WorldLand.CONT_BLEND && Places.clearAt(land, land.places, x, z, side * 5.2f)) out += z to side
            z += coverEvery
            k++
        }
        out
    }

    /** Where the sign with the next song stands. */
    private val signZ = land.endZ - WorldLand.ARRIVE / 2 - 8f

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
            val x = Places.xAt(land, signZ, 3.1f)
            val base = Mat4.model(x, land.heightAt(x, signZ), signZ, land.headingAt(signZ) + PI.toFloat() - 0.45f)
            out += Draw(signRig, base, signRig.pose())
            billboards += Billboard(Places.corners(base, -1.62f, 1.55f, 1.62f, 2.85f, 0.06f), Billboard.NEXT)
        }
        // The desert's peak: a great pyramid rises from the sand beside the way, ahead, and stays
        // where it is (seen as the camera comes round to it, not carried along in the sky).
        if (land.look.world == World.DESERT && peakZ != null) {
            pyramidRise = maxOf(pyramidRise, peak)
            if (pyramidRise > 0.01f) {
                val pz = peakZ + 34f
                val side = if ((land.centerAt(pz) - land.centerAt(pz - 20f)) > 0) -1f else 1f
                val px = land.centerAt(pz) + side * 30f
                val ground = minOf(land.heightAt(px - 12f, pz), land.heightAt(px + 12f, pz), land.heightAt(px, pz - 12f), land.heightAt(px, pz + 12f))
                val rise = pyramidRise * pyramidRise * (3 - 2 * pyramidRise)
                out += Draw(Props.peakPyramid, Mat4.model(px, ground - 0.5f - (1 - rise) * 40f, pz, 0.6f, s = 4.2f), pyramidPose)
            }
        }
    }

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
        val p = Places.tunnelAt(land.places, z) ?: return Float.POSITIVE_INFINITY
        if (abs(land.acrossAt(x, z)) > Places.TUNNEL_R + 0.5f) return Float.POSITIVE_INFINITY
        return land.pathAt(z) + Places.TUNNEL_R - 0.6f
    }

    /** The top of a place's solid part near ([x], [z]) (for the camera), or minus infinity. */
    fun topNear(x: Float, z: Float): Float = Places.topNear(land, land.places, x, z)

    /** Something to turn to as it is passed (a big wheel, a rocket, a train, a pyramid…), or null. */
    fun sight(tz: Float): FloatArray? = Places.sight(land, land.places, tz) ?: Props.sight(land, tz)

    /** Whether it is dark at row [z] for a night of [night] (0 … 1): a light comes out. */
    fun darkAt(z: Float, night: Float): Boolean = night > 0.55f || land.lookAt(z).world == World.VOLCANO

    companion object {
        /** Rows from the song's start to row index, for the chunks (kept here for both screens). */
        fun chunkOf(z: Float) = floor(z / LandMesher.CHUNK).toInt()
    }
}
