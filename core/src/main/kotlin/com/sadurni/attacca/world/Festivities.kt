package com.sadurni.attacca.world

import com.sadurni.attacca.diorama.World
import com.sadurni.attacca.festive.Festivity
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin

/**
 * The festive days on the land, as O'rama has them in front: the Three Kings on their camels by the
 * way (their star is in the sky layer), oil lamps along the way for Diwali (and the Hindu new
 * year's show), lanterns hung by the way for Eid and the Lunar New Year. The Santa hats are the
 * cast's, the snow the screen's weather.
 */
class Festivities(private val land: WorldLand) {

    /** Lamps or lanterns, baked in stretches of rows, kept while the festivity lasts. */
    private val stretches = HashMap<Int, Rig?>()
    private var bakedFor: Any? = null
    private val still = Pose(1)

    private val camel by lazy { Mounts.camel() }
    private val camelPoses = Array(3) { camel.first.pose() }
    private val kings by lazy {
        val gold = 0xFFFFC94A.toInt()
        listOf(
            Human.rig(Person(0xFF8E24AA.toInt(), 0xFF4A2A6A.toInt(), 0xFFF1C9A5.toInt(), 0xFFEDEDED.toInt(), null, false, 0xFFFFC94A.toInt()), crown = gold),
            Human.rig(Person(0xFF2E7D32.toInt(), 0xFF1B4A20.toInt(), 0xFFD9A47A.toInt(), 0xFF6A3E1E.toInt(), null, false, 0xFFE0E0E0.toInt()), crown = 0xFFE0E0E0.toInt()),
            Human.rig(Person(0xFFC62828.toInt(), 0xFF6A1B1B.toInt(), 0xFF7A4A2E.toInt(), 0xFF1A1414.toInt(), null, false, 0xFFFFC94A.toInt()), crown = gold),
        )
    }
    private val kingPoses = Array(3) { Pose(9) }

    /**
     * Adds this frame's festive things to [out] around the traveller at row [tz]: [festivity] (null:
     * none) with the Kings ([kings]); [vikram] while the Hindu new year's show goes on (its lamps).
     */
    fun frame(tz: Float, nowMs: Long, festivity: Festivity?, kings: Boolean, vikram: Boolean, out: MutableList<Draw>) {
        val lamps = when {
            vikram || festivity == Festivity.DIWALI -> LAMPS
            festivity == Festivity.EID -> EID
            festivity == Festivity.LUNAR_NEW_YEAR -> LUNAR
            else -> null
        }
        if (bakedFor != lamps) {
            stretches.clear()
            bakedFor = lamps
        }
        if (lamps != null && land.look.world != World.SPACE) {
            val first = floor((tz - 30f) / STRETCH).toInt()
            val last = floor((tz + WorldRendererLimits.FAR) / STRETCH).toInt()
            for (k in first..last) {
                stretches.getOrPut(k) { bake(k, lamps) }?.let { out += Draw(it, Mat4.identity(), still) }
            }
            stretches.keys.removeAll { it < first - 1 }
        }
        if (kings && (festivity == Festivity.CHRISTMAS || festivity == Festivity.ORTHODOX_CHRISTMAS)) kingsBy(tz, nowMs, out)
    }

    /** Whether rows near [z] are open ground by the way (no tunnel, bridge, station…, no water beside it). */
    private fun open(z: Float, side: Float, across: Float): Boolean {
        if (z < 4f || z > land.endZ) return false
        for (p in land.places) if (z > p.z0 - 4f && z < p.z1 + 4f) return false
        val x = Places.xAt(land, z, side * across)
        val y = land.heightAt(x, z)
        return y > WorldLand.WATER + 0.25f && abs(y - land.pathAt(z)) < 1.1f
    }

    /** The lamps (or lanterns) of stretch [k], as one figure. */
    private fun bake(k: Int, kind: Int): Rig? {
        val out = FloatList(16384)
        var z = k * STRETCH
        val step = if (kind == LAMPS) 2.5f else 9f
        z = (floor(z / step) + 1) * step
        var n = 0
        while (z < (k + 1) * STRETCH) {
            for (side in floatArrayOf(-1f, 1f)) {
                if (kind == LAMPS) {
                    // A little clay lamp at the way's edge, its flame flickering.
                    val across = WorldLand.PATH_HALF + 0.35f
                    if (!open(z, side, across)) continue
                    val x = Places.xAt(land, z, side * across)
                    val y = land.heightAt(x, z)
                    out.blob(x - 0.16f, y - 0.03f, z - 0.16f, x + 0.16f, y + 0.12f, z + 0.16f, 0xFFB5652E.toInt(), 10, 5)
                    out.blob(x - 0.06f, y + 0.08f, z - 0.06f, x + 0.06f, y + 0.24f, z + 0.06f, Props.glow(0xFFFF9A2A.toInt()), 8, 5)
                    out.blob(x - 0.035f, y + 0.12f, z - 0.035f, x + 0.035f, y + 0.3f, z + 0.035f, Props.glow(0xFFFFE08A.toInt()), 8, 5)
                } else {
                    // A post by the way with an arm over it, a glowing lantern hanging.
                    val across = WorldLand.PATH_HALF + 0.7f
                    if (!open(z + side * 2f, side, across)) continue
                    val zz = z + side * 2f
                    val h = land.headingAt(zz)
                    val (body, trim) = if (kind == EID) (if (n % 2 == 0) 0xFFD4AF37.toInt() else 0xFF2E8B7A.toInt()) to 0xFFD4AF37.toInt()
                    else 0xFFE53935.toInt() to 0xFFFFC94A.toInt()
                    Props.bakeMesh(FloatList(1024).apply {
                        tube(0f, 0f, 0f, 0f, 3.1f, 0f, 0.06f, 0xFF4A3A2E.toInt())
                        tube(0f, 3.0f, 0f, -side * 0.9f, 3.0f, 0f, 0.035f, 0xFF4A3A2E.toInt())
                        tube(-side * 0.9f, 3.0f, 0f, -side * 0.9f, 2.75f, 0f, 0.012f, 0xFF2A2A2A.toInt())
                        if (kind == EID) {
                            // A tall lantern with a pointed top.
                            box(-side * 0.9f - 0.13f, 2.2f, -0.13f, -side * 0.9f + 0.13f, 2.62f, 0.13f, Props.glow(body))
                            tri(-side * 0.9f - 0.16f, 2.62f, -0.16f, -side * 0.9f + 0.16f, 2.62f, 0.16f, -side * 0.9f, 2.82f, 0f, trim)
                            tri(-side * 0.9f + 0.16f, 2.62f, -0.16f, -side * 0.9f - 0.16f, 2.62f, 0.16f, -side * 0.9f, 2.82f, 0f, trim)
                            box(-side * 0.9f - 0.15f, 2.16f, -0.15f, -side * 0.9f + 0.15f, 2.22f, 0.15f, trim)
                        } else {
                            // A round red lantern with golden caps and a tassel.
                            blob(-side * 0.9f - 0.26f, 2.22f, -0.26f, -side * 0.9f + 0.26f, 2.72f, 0.26f, Props.glow(body), 12, 8)
                            box(-side * 0.9f - 0.1f, 2.7f, -0.1f, -side * 0.9f + 0.1f, 2.78f, 0.1f, trim)
                            box(-side * 0.9f - 0.1f, 2.16f, -0.1f, -side * 0.9f + 0.1f, 2.24f, 0.1f, trim)
                            tube(-side * 0.9f, 2.16f, 0f, -side * 0.9f, 1.92f, 0f, 0.025f, trim)
                        }
                    }.mesh(), Places.xAt(land, zz, side * across), land.heightAt(Places.xAt(land, zz, side * across), zz), zz, h, 1f, out)
                }
            }
            z += step
            n++
        }
        return if (out.size == 0) null else Rig(listOf(Bone(out.mesh(), -1, 0f, 0f, 0f)))
    }

    /** The Three Kings on their camels by the way now and then, coming along it slowly. */
    private fun kingsBy(tz: Float, nowMs: Long, out: MutableList<Draw>) {
        val t = nowMs / 1000.0
        // A group every so many rows, on alternate sides.
        val first = floor((tz - 20f) / KINGS_EVERY).toInt()
        val last = floor((tz + WorldRendererLimits.FAR) / KINGS_EVERY).toInt()
        for (g in first..last) {
            if (g < 0) continue
            val side = if (g % 2 == 0) 1f else -1f
            val z0 = g * KINGS_EVERY + 40f
            for (i in 0 until 3) {
                val z = z0 + i * 3.4f
                val across = WorldLand.PATH_HALF + 2.6f + (i % 2) * 0.8f
                if (!open(z, side, across)) continue
                val x = Places.xAt(land, z, side * across)
                val y = land.heightAt(x, z)
                val h = land.headingAt(z)
                // The camels stand, swaying their necks; the kings sit up, a hand raised now and then.
                val cp = camelPoses[i]
                cp.pitch[Quad.BODY] = sin(t * 1.3 + i).toFloat() * 0.03f
                cp.yaw[Quad.TAIL] = sin(t * 2.0 + i).toFloat() * 0.4f
                // Turned to the traveller coming along the way.
                val base = Mat4.model(x, y, z, h + kotlin.math.PI.toFloat() - side * 0.45f)
                out += Draw(camel.first, base, cp)
                val kp = kingPoses[i]
                kp.pitch[Human.THIGH_L] = -0.95f; kp.pitch[Human.THIGH_R] = -0.95f
                kp.roll[Human.THIGH_L] = -0.45f; kp.roll[Human.THIGH_R] = 0.45f
                kp.pitch[Human.SHIN_L] = 1.05f; kp.pitch[Human.SHIN_R] = 1.05f
                kp.pitch[Human.ARM_L] = -0.8f; kp.pitch[Human.FOREARM_L] = -0.9f
                val wave = ((t * 0.4 + i * 0.37) % 1.0) < 0.25
                kp.pitch[Human.ARM_R] = if (wave) -2.6f else -0.8f
                kp.roll[Human.ARM_R] = if (wave) -0.3f * sin(t * 9).toFloat() else 0f
                kp.pitch[Human.FOREARM_R] = if (wave) -0.4f else -0.9f
                kp.pitch[Human.BODY] = 0.08f + sin(t * 1.3 + i).toFloat() * 0.03f
                val m = Array(camel.first.bones.size) { FloatArray(16) }
                camel.first.matrices(base, cp, m)
                out += Draw(kings[i], Mat4.mul(m[0], Mat4.model(0f, 2.12f - Human.HIP, -0.05f)), kp)
            }
        }
    }

    companion object {
        private const val STRETCH = 30f
        private const val KINGS_EVERY = 170f
        private const val LAMPS = 1
        private const val EID = 2
        private const val LUNAR = 3
    }
}
