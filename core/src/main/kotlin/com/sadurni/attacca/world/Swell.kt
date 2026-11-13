package com.sadurni.attacca.world

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * How the beat lifts the land (as the screens draw it): the rings running out over it and the
 * swell near the traveller. The figures on the land (the traveller, the vehicle, the dogs, the
 * people at relays) are lifted as much, so they ride the wave instead of sinking into it.
 */
object Swell {
    /** The rings' swell at ([x], [z]); [rings] holds (x, z, seconds since, strength) for each. */
    fun rings(rings: FloatArray, x: Float, z: Float): Float {
        var s = 0f
        var k = 0
        while (k + 3 < rings.size) {
            val strength = rings[k + 3]
            val t = rings[k + 2]
            if (strength > 0f && t < 3f) {
                val dx = x - rings[k]
                val dz = z - rings[k + 1]
                val front = sqrt(dx * dx + dz * dz) - t * 22f
                if (abs(front) <= 8f) s += strength * exp(-front * front / 6f) * exp(-t * 1.4f)
            }
            k += 4
        }
        return s
    }

    /** How far up the land of height [y] at ([x], [z]) is drawn, with the beat's [pulse] near the traveller at row [tz]. */
    fun lift(rings: FloatArray, x: Float, z: Float, y: Float, pulse: Float, tz: Float): Float {
        var up = rings(rings, x, z) * 0.55f
        if (y > 3f) {
            val dz = (z - tz) / 26f
            up += y * pulse.coerceAtMost(1.5f) * 0.07f * exp(-dz * dz)
        }
        return up
    }

    /**
     * Lifts the figures in [draws] from [from] on standing on the land (not on a bridge or the
     * water), and the traveller's [eye] (x, y, z, known) with them.
     */
    fun liftFigures(land: WorldLand, draws: MutableList<Draw>, from: Int, rings: FloatArray, pulse: Float, tz: Float, eye: FloatArray?) {
        fun upAt(x: Float, z: Float, y: Float): Float {
            val g = land.heightAt(x, z)
            if (g <= WorldLand.WATER + 0.05f || y - g > 3.5f || y < g - 1f) return 0f
            return lift(rings, x, z, g, pulse, tz)
        }
        for (i in from until draws.size) {
            val d = draws[i]
            val b = d.base
            val up = upAt(b[12], b[14], b[13])
            if (up < 0.002f) continue
            val m = b.copyOf()
            m[13] += up
            draws[i] = Draw(d.rig, m, d.pose, d.alpha)
        }
        if (eye != null && eye[3] > 0f) eye[1] += upAt(eye[0], eye[2], eye[1] - 1.6f)
    }
}
