package com.sadurni.attacca.world

import com.sadurni.attacca.diorama.Diorama
import com.sadurni.attacca.diorama.World
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * What lives about the way, as in O'rama: birds going over in flocks (gulls, geese, bats,
 * parrots, vultures; fish under the sea, a flying saucer in space), balloons drifting far off,
 * animals grazing in the fields, other dogs running by, smoke from chimneys, divers swimming past
 * under the sea and little men from Mars peeping out in space.
 */
class Life(private val land: WorldLand, private val seed: Int) {

    private fun hash(i: Int, salt: Int) = Diorama.hash(i * 7919 + seed, salt)

    // ---- Rigs ----

    private class Flyer(val rig: Rig, val wings: Boolean, val size: Float)

    private val flyers = HashMap<World, Flyer>()

    private fun flyerOf(w: World): Flyer = flyers.getOrPut(w) {
        val (body, wing) = when (w) {
            World.COAST -> 0xFFF4F4F2.toInt() to 0xFFB8BEC8.toInt()
            World.CASTLE, World.VOLCANO -> 0xFF1E1A22.toInt() to 0xFF2A2430.toInt()
            World.JUNGLE, World.AMAZON -> 0xFFD8322A.toInt() to 0xFF3A9A4A.toInt()
            World.DESERT, World.CANYON -> 0xFF4A3A30.toInt() to 0xFF3A2E26.toInt()
            World.CITY -> 0xFF8A8C94.toInt() to 0xFF6E707A.toInt()
            World.ICE -> 0xFFF4F8FC.toInt() to 0xFFD8E2EC.toInt()
            else -> 0xFF7A6A58.toInt() to 0xFF5A4E44.toInt()
        }
        when (w) {
            World.REEF -> Flyer(fish(), false, 1f)
            World.SPACE -> Flyer(saucer(), false, 1f)
            World.NEON -> Flyer(drone(), false, 1f)
            else -> {
                val b = RigBuilder()
                b.bone(-1, 0f, 0f, 0f) {
                    blob(-0.16f, -0.12f, -0.45f, 0.16f, 0.14f, 0.4f, body)
                    blob(-0.1f, -0.02f, 0.3f, 0.1f, 0.16f, 0.55f, body)
                    blob(-0.03f, 0.02f, 0.52f, 0.03f, 0.08f, 0.66f, 0xFFE8A030.toInt())
                }
                for (s in intArrayOf(-1, 1)) b.bone(0, s * 0.12f, 0.04f, 0f) { blob(if (s < 0) -0.95f else 0.1f, 0f, -0.2f, if (s < 0) -0.1f else 0.95f, 0.06f, 0.22f, wing) }
                Flyer(b.rig(), true, if (w == World.DESERT || w == World.CANYON) 1.6f else 1f)
            }
        }
    }

    private fun fish(): Rig {
        val b = RigBuilder()
        val c = intArrayOf(0xFFFFB84D.toInt(), 0xFFFF6F61.toInt(), 0xFF4FD1C5.toInt())[Math.floorMod(seed, 3)]
        b.bone(-1, 0f, 0f, 0f) {
            blob(-0.12f, -0.2f, -0.35f, 0.12f, 0.2f, 0.35f, c)
            blob(-0.04f, 0.04f, 0.22f, 0.04f, 0.1f, 0.28f, 0xFF1A1A20.toInt())
        }
        b.bone(0, 0f, 0f, -0.32f) { blob(-0.03f, -0.18f, -0.62f, 0.03f, 0.18f, -0.3f, c) }
        return b.rig()
    }

    private fun saucer(): Rig = RigBuilder().apply {
        bone(-1, 0f, 0f, 0f) {
            blob(-1.6f, -0.25f, -1.6f, 1.6f, 0.25f, 1.6f, 0xFFB8BCC8.toInt())
            blob(-0.7f, 0.1f, -0.7f, 0.7f, 0.8f, 0.7f, 0xFF8EF0FF.toInt())
            for (k in 0 until 8) {
                val a = k * 0.785f
                blob(cos(a) * 1.4f - 0.1f, -0.15f, sin(a) * 1.4f - 0.1f, cos(a) * 1.4f + 0.1f, 0.05f, sin(a) * 1.4f + 0.1f, Props.glow(if (k % 2 == 0) 0xFF5CFFB0.toInt() else 0xFFFFE08A.toInt()))
            }
        }
    }.rig()

    private fun drone(): Rig = RigBuilder().apply {
        bone(-1, 0f, 0f, 0f) {
            box(-0.25f, -0.08f, -0.25f, 0.25f, 0.08f, 0.25f, 0xFF2A2A34.toInt())
            for (s in floatArrayOf(-1f, 1f)) for (t in floatArrayOf(-1f, 1f)) blob(s * 0.45f - 0.18f, 0.05f, t * 0.45f - 0.18f, s * 0.45f + 0.18f, 0.1f, t * 0.45f + 0.18f, 0xFF3A3A44.toInt())
            blob(-0.08f, -0.15f, -0.08f, 0.08f, -0.05f, 0.08f, Props.glow(0xFFE8389E.toInt()))
        }
    }.rig()

    private val balloonRigs = HashMap<Int, Rig>()

    private fun balloon(k: Int, blimp: Boolean): Rig = balloonRigs.getOrPut(k * 2 + if (blimp) 1 else 0) {
        val colours = intArrayOf(0xFFE8443A.toInt(), 0xFFF5D547.toInt(), 0xFF3F8FD9.toInt(), 0xFF5DBB63.toInt(), 0xFFEE7A30.toInt(), 0xFF8E44AD.toInt())
        val a = colours[Math.floorMod(k + seed, colours.size)]
        val c = colours[Math.floorMod(k + seed + 2, colours.size)]
        RigBuilder().apply {
            bone(-1, 0f, 0f, 0f) {
                if (blimp) {
                    blob(-1.6f, -1.4f, -6f, 1.6f, 1.6f, 6f, 0xFFD8DCE4.toInt())
                    blob(-1.62f, -0.3f, -5f, 1.62f, 0.4f, 5f, if (land.look.world == World.NEON) Props.glow(0xFF46EAF2.toInt()) else a)
                    box(-0.5f, -2f, -1.4f, 0.5f, -1.3f, 1.4f, 0xFF3A3A44.toInt())
                    for (s in floatArrayOf(-1f, 1f)) box(s * 0.05f - 0.05f + s * 0.8f, -0.1f, -6.6f, s * 0.05f + 0.05f + s * 1.8f, 0.1f, -5f, 0xFF8A8C94.toInt())
                } else {
                    for (q in 0 until 8) {
                        val ang = q * 0.785f
                        blob(cos(ang) * 1.2f - 1.4f, 1.2f, sin(ang) * 1.2f - 1.4f, cos(ang) * 1.2f + 1.4f, 5.4f, sin(ang) * 1.2f + 1.4f, if (q % 2 == 0) a else c)
                    }
                    blob(-1.5f, 0.4f, -1.5f, 1.5f, 2.4f, 1.5f, a)
                    for (s in floatArrayOf(-0.4f, 0.4f)) for (t in floatArrayOf(-0.4f, 0.4f)) tube(s * 2f, 0.8f, t * 2f, s, -1.3f, t, 0.02f, 0xFF4A3A30.toInt())
                    box(-0.5f, -1.9f, -0.5f, 0.5f, -1.2f, 0.5f, 0xFF8A5A34.toInt())
                }
            }
        }.rig()
    }

    private val jelly: Rig by lazy {
        RigBuilder().apply {
            bone(-1, 0f, 0f, 0f, alpha = 0.5f) { blob(-0.9f, -0.2f, -0.9f, 0.9f, 0.9f, 0.9f, Props.glow(0xFFFF9FD0.toInt())) }
            bone(-1, 0f, 0f, 0f) {
                for (k in 0 until 6) {
                    val a = k * 1.047f
                    tube(cos(a) * 0.5f, 0f, sin(a) * 0.5f, cos(a) * 0.6f, -1.8f, sin(a) * 0.6f, 0.03f, Props.glow(0xFFFFC8E8.toInt()))
                }
            }
        }.rig()
    }

    /** A grazing animal of [w]: the same bones as the dogs (body, four legs in two parts, tail). */
    private class Beast(val rig: Rig, val legH: Float, val biped: Boolean)

    private val beasts = HashMap<World, Beast?>()

    private fun beastOf(w: World): Beast? = beasts.getOrPut(w) {
        when (w) {
            World.MEADOW -> quadBeast(0.9f, 1.15f, 0.62f, 0xFFF2EEE4.toInt(), 0xFF2A2626.toInt(), fluffy = true)
            World.CASTLE, World.JAPAN -> quadBeast(1.25f, 1.25f, 0.42f, 0xFFA87048.toInt(), 0xFFE8D8C0.toInt(), antlers = true)
            World.WALL -> Beast(Quad.horse().first, 1.45f * 0.52f, false)
            World.CANYON -> quadBeast(1.5f, 1.8f, 0.9f, 0xFF4A3426.toInt(), 0xFF2A1E18.toInt(), hump = true)
            World.DESERT -> Beast(Mounts.camel().first, 1.15f, false)
            World.JUNGLE -> Beast(Mounts.elephant(0xFFB03A3A.toInt()).first, 1.1f, false)
            World.ICE -> penguin()
            else -> null
        }
    }

    private fun quadBeast(h: Float, l: Float, t: Float, coat: Int, dark: Int, fluffy: Boolean = false, antlers: Boolean = false, hump: Boolean = false): Beast {
        val b = RigBuilder()
        val legH = h * 0.5f
        val half = t / 2
        b.bone(-1, 0f, legH, 0f) {
            if (fluffy) {
                // One round woolly body, a few tufts on it.
                blob(-half - 0.08f, legH - 0.1f, -l / 2 - 0.05f, half + 0.08f, h + 0.08f, l / 2 + 0.05f, coat)
                for (k in 0 until 4) {
                    val z = -l / 3 + l * k / 4.5f
                    blob(-half * 0.7f, h - 0.12f, z - 0.22f, half * 0.7f, h + 0.16f, z + 0.22f, coat)
                }
            } else blob(-half, legH - 0.05f, -l / 2, half, h, l / 2, coat)
            if (hump) blob(-half * 0.9f, h - 0.3f, l / 2 - 0.9f, half * 0.9f, h + 0.35f, l / 2 - 0.1f, dark)
            val hz = l / 2
            blob(-half * 0.6f, h - 0.35f, hz - 0.1f, half * 0.6f, h + 0.05f, hz + 0.45f, if (fluffy) dark else coat)
            blob(-0.04f, h - 0.2f, hz + 0.42f, 0.04f, h - 0.12f, hz + 0.48f, 0xFF1A1414.toInt())
            if (antlers) for (s in intArrayOf(-1, 1)) {
                tube(s * 0.1f, h + 0.0f, hz + 0.05f, s * 0.3f, h + 0.55f, hz - 0.05f, 0.025f, 0xFF6E5A44.toInt())
                tube(s * 0.22f, h + 0.3f, hz, s * 0.35f, h + 0.45f, hz + 0.15f, 0.02f, 0xFF6E5A44.toInt())
            }
        }
        val fz = l / 2 - 0.15f
        val bz = -l / 2 + 0.15f
        for ((leg, z) in listOf(0 to fz, 1 to fz, 2 to bz, 3 to bz)) {
            val x = if (leg % 2 == 0) -half + 0.08f else half - 0.08f
            val upper = b.bone(Quad.BODY, x, legH, z) { tube(x, legH, z, x, legH * 0.5f, z, 0.065f, if (fluffy) dark else coat) }
            b.bone(upper, x, legH * 0.5f, z) { tube(x, legH * 0.5f, z, x, 0.04f, z, 0.055f, dark) }
        }
        b.bone(Quad.BODY, 0f, h - 0.1f, -l / 2) { tube(0f, h - 0.1f, -l / 2, 0f, h - 0.4f, -l / 2 - 0.15f, 0.04f, coat) }
        return Beast(b.rig(), legH, false)
    }

    private fun penguin(): Beast {
        val b = RigBuilder()
        b.bone(-1, 0f, 0f, 0f) {
            blob(-0.28f, 0f, -0.25f, 0.28f, 0.95f, 0.25f, 0xFF1E1E26.toInt())
            blob(-0.2f, 0.05f, 0.05f, 0.2f, 0.8f, 0.27f, 0xFFF4F4F2.toInt())
            blob(-0.18f, 0.85f, -0.18f, 0.18f, 1.2f, 0.18f, 0xFF1E1E26.toInt())
            blob(-0.04f, 0.98f, 0.15f, 0.04f, 1.04f, 0.3f, 0xFFF0A030.toInt())
            for (s in floatArrayOf(-0.12f, 0.12f)) blob(s - 0.08f, -0.02f, 0f, s + 0.08f, 0.04f, 0.2f, 0xFFF0A030.toInt())
        }
        for (s in intArrayOf(-1, 1)) b.bone(0, s * 0.27f, 0.75f, 0f) { blob(if (s < 0) -0.34f else 0.22f, 0.3f, -0.1f, if (s < 0) -0.22f else 0.34f, 0.78f, 0.1f, 0xFF1E1E26.toInt()) }
        return Beast(b.rig(), 0f, true)
    }

    private val strays = List(3) { k ->
        val coats = intArrayOf(0xFF3A2A20.toInt(), 0xFFE8D8B8.toInt(), 0xFF8A6A48.toInt(), 0xFF1E1E22.toInt(), 0xFFB07038.toInt())
        Quad.strayDog(coats[Math.floorMod(seed + k * 2, coats.size)], 0xFFF2EAD8.toInt(), 0.8f + 0.25f * k).first
    }

    private val diverRig: Rig by lazy { Human.rig(Person.of(11, seed, 0xFFFFB84D.toInt()), helmet = true) }

    private val martian: Rig by lazy {
        RigBuilder().apply {
            bone(-1, 0f, 0f, 0f) {
                val g = 0xFF6ED86A.toInt()
                blob(-0.3f, 0f, -0.25f, 0.3f, 0.8f, 0.25f, g)
                blob(-0.38f, 0.7f, -0.34f, 0.38f, 1.35f, 0.34f, g)
                for (s in floatArrayOf(-0.15f, 0.15f)) {
                    blob(s - 0.1f, 1.0f, 0.24f, s + 0.1f, 1.18f, 0.36f, 0xFF101014.toInt())
                    tube(s, 1.3f, 0f, s * 2.2f, 1.75f, 0f, 0.025f, g)
                    blob(s * 2.2f - 0.07f, 1.7f, -0.07f, s * 2.2f + 0.07f, 1.84f, 0.07f, Props.glow(0xFFFFE08A.toInt()))
                }
            }
            for (s in intArrayOf(-1, 1)) bone(0, s * 0.3f, 0.6f, 0f) { tube(s * 0.3f, 0.6f, 0f, s * 0.55f, 0.25f, 0.05f, 0.06f, 0xFF6ED86A.toInt()) }
        }.rig()
    }

    private val puff: Rig by lazy { RigBuilder().apply { bone(-1, 0f, 0f, 0f) { blob(-0.5f, -0.5f, -0.5f, 0.5f, 0.5f, 0.5f, 0xFFE4E4E8.toInt(), 10, 6) } }.rig() }
    private val darkPuff: Rig by lazy { RigBuilder().apply { bone(-1, 0f, 0f, 0f) { blob(-0.5f, -0.5f, -0.5f, 0.5f, 0.5f, 0.5f, 0xFF8A8A90.toInt(), 10, 6) } }.rig() }

    private val poses = HashMap<Any, Pose>()
    private fun poseOf(key: Any, rig: Rig) = poses.getOrPut(key) { rig.pose() }.also { it.reset() }

    // ---- What happens now and then (each kept where it began) ----

    private var flockCycle = -1L
    private var flockZ = 0f
    private var dogCycle = -1L
    private var dogZ = 0f
    private var diverCycle = -1L
    private var diverZ = 0f
    private var martianCycle = -1L
    private var martianZ = 0f

    private val chimneyCache = HashMap<Int, FloatArray>()

    /**
     * Everything alive in sight with the traveller at row [tz] (way height [ty]) at [nowMs];
     * [smoke] holds puffs of the places (x, y, z, age) to show with the chimneys' smoke.
     */
    fun frame(tz: Float, ty: Float, nowMs: Long, playing: Boolean, out: MutableList<Draw>, smoke: List<Float>) {
        val t = nowMs / 1000.0
        val w = land.worldAt(land.centerAt(tz), tz)
        flocks(tz, ty, nowMs, t, w, out)
        balloons(tz, t, out)
        herds(tz, t, out)
        if (w != World.REEF && w != World.SPACE && w != World.ICE && w != World.AMAZON && w != World.VOLCANO) strayDogs(tz, nowMs, t, out)
        if (w == World.REEF) divers(tz, nowMs, t, out)
        if (w == World.SPACE) martians(tz, nowMs, t, out)
        chimneys(tz, t, out)
        var i = 0
        while (i + 3 < smoke.size) {
            puffAt(smoke[i], smoke[i + 1], smoke[i + 2], smoke[i + 3], 1.3f, out)
            i += 4
        }
    }

    private fun flocks(tz: Float, ty: Float, nowMs: Long, t: Double, w: World, out: MutableList<Draw>) {
        val period = 22_000L
        val cycle = nowMs / period
        val age = (nowMs % period) / 14_000f
        if (cycle != flockCycle) {
            flockCycle = cycle
            flockZ = tz + 40f + 25f * hash(cycle.toInt(), 701)
        }
        if (age >= 1f) return
        val c = cycle.toInt()
        val f = flyerOf(w)
        val dir = if (hash(c, 702) < 0.5f) 1f else -1f
        val n = if (w == World.SPACE) 1 else 5 + (hash(c, 703) * 4).toInt()
        val under = w == World.REEF
        val height = if (under) land.pathAt(flockZ) + 3f + 3f * hash(c, 704) else max(ty, WorldLand.WATER) + 14f + 9f * hash(c, 704)
        val across = (-1f + 2f * age) * 60f * dir
        val cx = Places.xAt(land, flockZ, across)
        val heading = if (dir > 0) PI.toFloat() / 2 else -PI.toFloat() / 2
        for (k in 0 until n) {
            // A V (a school, under the sea), each a little behind and aside of the one before.
            val row = (k + 1) / 2
            val side = if (k % 2 == 0) 1f else -1f
            val back = row * 1.6f * f.size
            val x = cx - dir * back
            val z = flockZ + side * row * 1.4f * f.size + sin(t * 0.7 + k).toFloat() * 0.3f
            val y = height + sin(t * 1.3 + k * 0.7).toFloat() * 0.3f
            val pose = poseOf("fly$k", f.rig)
            if (f.wings) {
                val flap = sin(t * 9.0 + k * 0.6).toFloat() * 0.7f
                pose.roll[1] = flap
                pose.roll[2] = -flap
            } else if (under) pose.yaw[1] = sin(t * 10.0 + k).toFloat() * 0.5f
            out += Draw(f.rig, Mat4.model(x, y, z, heading + (if (w == World.SPACE) (t * 2).toFloat() else 0f), s = f.size), pose)
        }
    }

    private fun balloons(tz: Float, t: Double, out: MutableList<Draw>) {
        val every = 130f
        val first = floor((tz - 30f) / every).toInt()
        val last = floor((tz + WorldRendererLimits.FAR) / every).toInt()
        for (k in first..last) {
            val z0 = k * every + 60f * hash(k, 711)
            val w = land.worldAt(land.centerAt(z0), z0)
            val kind = when (w) {
                World.MEADOW, World.COAST, World.CANYON, World.DESERT, World.JAPAN, World.WALL -> 0
                World.CITY, World.NEON -> 1
                World.REEF -> 2
                else -> continue
            }
            if (hash(k, 712) > 0.75f) continue
            val side = if (hash(k, 713) < 0.5f) -1f else 1f
            val across = side * (24f + 26f * hash(k, 714)) + sin(t / 25.0 + k).toFloat() * 6f
            val z = z0 + sin(t / 31.0 + k * 1.3).toFloat() * 4f
            val x = Places.xAt(land, z, across)
            val ground = land.pathAt(z)
            val y = if (kind == 2) ground + 5f + 3f * hash(k, 715) + sin(t * 0.5 + k).toFloat() * 0.6f
                else max(ground, WorldLand.WATER) + 22f + 10f * hash(k, 715) + sin(t * 0.3 + k).toFloat() * 1.2f
            when (kind) {
                2 -> out += Draw(jelly, Mat4.model(x, y, z, (t * 0.2).toFloat()), poseOf("jelly$k", jelly))
                else -> {
                    val rig = balloon(k, kind == 1)
                    out += Draw(rig, Mat4.model(x, y, z, if (kind == 1) PI.toFloat() / 2 + (t * 0.02).toFloat() else 0f, s = if (kind == 1) 1.4f else 1f), poseOf("balloon$k", rig))
                }
            }
        }
    }

    private fun herds(tz: Float, t: Double, out: MutableList<Draw>) {
        val every = 70f
        val first = floor((tz - 30f) / every).toInt()
        val last = floor((tz + 110f) / every).toInt()
        for (k in first..last) {
            if (hash(k, 721) > 0.7f) continue
            val z = k * every + 50f * hash(k, 722)
            if (z < WorldLand.CONT + WorldLand.CONT_BLEND) continue
            val side = if (hash(k, 723) < 0.5f) -1f else 1f
            val across = side * (13f + 15f * hash(k, 724))
            val cx = Places.xAt(land, z, across)
            val w = land.worldAt(cx, z)
            val beast = beastOf(w) ?: continue
            val ground = land.heightAt(cx, z)
            if (ground < WorldLand.WATER + 0.3f) continue
            if (abs(land.heightAt(cx + 2f, z) - land.heightAt(cx - 2f, z)) > 1.6f) continue
            if (!Places.clearAt(land, land.places, cx, z, across)) continue
            val n = if (w == World.JUNGLE || w == World.DESERT) 2 else 3 + (hash(k, 725) * 3).toInt()
            for (i in 0 until n) {
                val a = hash(k * 13 + i, 726) * 6.28f
                val r = 1.5f + 2.5f * hash(k * 13 + i, 727)
                // Wandering slowly round where they graze.
                val drift = (t * 0.03 + i).toFloat()
                val x = cx + cos(a + drift) * r
                val zz = z + sin(a + drift) * r
                val y = land.heightAt(x, zz)
                val pose = poseOf("beast$k-$i", beast.rig)
                val grazing = sin(t * 0.4 + i * 1.9) > -0.2
                val heading = a + drift + PI.toFloat() / 2
                if (beast.biped) {
                    // Penguins waddle.
                    pose.roll[0] = sin(t * 5.0 + i).toFloat() * 0.12f
                    pose.roll[1] = 0.3f + sin(t * 6.0 + i).toFloat() * 0.2f
                    pose.roll[2] = -0.3f - sin(t * 6.0 + i).toFloat() * 0.2f
                } else {
                    // Head down grazing, or a slow step now and then.
                    if (grazing) pose.pitch[Quad.BODY] = 0.12f
                    else {
                        val ph = (t * 1.5 + i).toFloat()
                        for (leg in 0..3) {
                            val off = if (leg == 0 || leg == 3) 0f else PI.toFloat()
                            pose.pitch[Quad.upper(leg)] = -sin(ph * 2 * PI.toFloat() + off) * 0.3f
                        }
                    }
                    if (beast.rig.bones.size > Quad.TAIL) pose.yaw[Quad.TAIL] = sin(t * 2.0 + i).toFloat() * 0.3f
                }
                out += Draw(beast.rig, Mat4.model(x, y + 0.02f, zz, heading, s = if (w == World.JUNGLE) 0.85f else 1f), pose)
            }
        }
    }

    private fun strayDogs(tz: Float, nowMs: Long, t: Double, out: MutableList<Draw>) {
        val period = 31_000L
        val cycle = nowMs / period
        val age = (nowMs % period) / 9_000f
        if (cycle != dogCycle) {
            dogCycle = cycle
            dogZ = tz
        }
        if (age >= 1f || hash(cycle.toInt(), 731) > 0.7f) return
        val c = cycle.toInt()
        val side = if (hash(c, 732) < 0.5f) -1f else 1f
        val rig = strays[Math.floorMod(c, strays.size)]
        // From behind, along the way's side, past the traveller and off ahead.
        val z = dogZ - 10f + age * 9f * 9f
        val across = side * (2.6f + 9f * age * age)
        val h = land.headingAt(z)
        val x = Places.xAt(land, z, across)
        val y = land.heightAt(x, z)
        if (y < WorldLand.WATER) return
        val pose = poseOf("stray", rig)
        val a = (t * 3.2 * 2 * PI).toFloat()
        for (leg in 0..3) {
            val off = if (leg < 2) 0f else 1.9f
            pose.pitch[Quad.upper(leg)] = -sin(a + off + (leg % 2) * 0.35f) * 0.7f
            pose.pitch[Quad.lower(leg)] = max(0f, sin(a + off + 1.4f)) * 1.3f * (if (leg < 2) 1f else -1f)
        }
        pose.yaw[Quad.TAIL] = sin(t * 12).toFloat() * 0.5f
        out += Draw(rig, Mat4.model(x, y, z, h + side * 0.5f * age), pose)
    }

    private fun divers(tz: Float, nowMs: Long, t: Double, out: MutableList<Draw>) {
        val period = 26_000L
        val cycle = nowMs / period
        val age = (nowMs % period) / 12_000f
        if (cycle != diverCycle) {
            diverCycle = cycle
            diverZ = tz + 22f + 12f * hash(cycle.toInt(), 741)
        }
        if (age >= 1f) return
        val c = cycle.toInt()
        val dir = if (hash(c, 742) < 0.5f) 1f else -1f
        val across = (-1f + 2f * age) * 22f * dir
        val x = Places.xAt(land, diverZ, across)
        val y = land.pathAt(diverZ) + 4f + 2f * hash(c, 743) + sin(t * 0.8).toFloat() * 0.3f
        val pose = poseOf("diver", diverRig)
        // Lying along the water, legs kicking, arms along the body.
        pose.pitch[Human.BODY] = 1.35f
        val kick = sin(t * 6.0).toFloat() * 0.35f
        pose.pitch[Human.THIGH_L] = kick; pose.pitch[Human.THIGH_R] = -kick
        pose.pitch[Human.ARM_L] = -0.2f; pose.pitch[Human.ARM_R] = -0.2f
        out += Draw(diverRig, Mat4.model(x, y, diverZ, if (dir > 0) PI.toFloat() / 2 else -PI.toFloat() / 2), pose)
        // Bubbles going up.
        for (k in 0 until 4) {
            val a = ((t * 0.9 + k / 4.0) % 1.0).toFloat()
            puffAt(x - dir * 0.2f, y + 1.8f + a * 3f, diverZ, a, 0.25f, out)
        }
    }

    private fun martians(tz: Float, nowMs: Long, t: Double, out: MutableList<Draw>) {
        val period = 20_000L
        val cycle = nowMs / period
        val age = (nowMs % period) / 6_000f
        if (cycle != martianCycle) {
            martianCycle = cycle
            martianZ = tz + 18f + 8f * hash(cycle.toInt(), 751)
        }
        if (age >= 1f) return
        val c = cycle.toInt()
        val side = if (hash(c, 752) < 0.5f) -1f else 1f
        val x = Places.xAt(land, martianZ, side * (4.5f + 2f * hash(c, 753)))
        // Up from behind a rock, a look, a wave, and down again.
        val up = min(1f, min(age * 5f, (1f - age) * 5f))
        val y = land.heightAt(x, martianZ) - 1.5f + 1.5f * up
        val pose = poseOf("martian", martian)
        pose.roll[if (side > 0) 1 else 2] = (if (side > 0) 1f else -1f) * (1.6f + sin(t * 8).toFloat() * 0.4f) * up
        out += Draw(martian, Mat4.model(x, y, martianZ, land.headingAt(martianZ) + PI.toFloat() + side * 0.6f), pose)
    }

    private fun chimneys(tz: Float, t: Double, out: MutableList<Draw>) {
        val first = floor((tz - 12f) / LandMesher.CHUNK).toInt()
        val last = floor((tz + 80f) / LandMesher.CHUNK).toInt()
        var shown = 0
        for (k in first..last) {
            val list = chimneyCache.getOrPut(k) {
                val l = ArrayList<Float>()
                Props.chimneys(land, (k * LandMesher.CHUNK).toFloat(), ((k + 1) * LandMesher.CHUNK).toFloat(), l)
                l.toFloatArray()
            }
            var i = 0
            while (i < list.size && shown < 14) {
                val x = list[i]; val y = list[i + 1]; val z = list[i + 2]
                for (p in 0 until 4) {
                    val age = ((t * 0.22 + p / 4.0 + (x * 0.37f + z * 0.11f)) % 1.0).toFloat()
                    puffAt(x + age * 1.6f, y + age * 3.2f, z + age * 0.6f, age, 0.9f, out)
                }
                shown++
                i += 3
            }
        }
        // Forget chunks left far behind.
        if (chimneyCache.size > 24) chimneyCache.keys.filter { it < first - 2 }.forEach { chimneyCache.remove(it) }
    }

    /** A puff of smoke (or a bubble) at its [age] 0 … 1: growing and fading. */
    private fun puffAt(x: Float, y: Float, z: Float, age: Float, size: Float, out: MutableList<Draw>) {
        val a = 0.55f * (1f - age) * min(1f, age * 6f)
        if (a <= 0.02f) return
        val rig = if (land.look.world == World.VOLCANO) darkPuff else puff
        out += Draw(rig, Mat4.model(x, y, z, 0f, s = size * (0.45f + 1.1f * age)), poseOf("puff", rig), alpha = a)
    }
}
