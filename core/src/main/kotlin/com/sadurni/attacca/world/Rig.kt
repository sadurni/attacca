package com.sadurni.attacca.world

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A figure with joints: each bone a mesh in the figure's own space (as it stands at rest), the
 * bone it hangs from and the point it turns about (hip, knee, shoulder, elbow, a wheel's axle…).
 */
class Bone(val mesh: Mesh, val parent: Int, val px: Float, val py: Float, val pz: Float, val alpha: Float = 1f)

class Rig(val bones: List<Bone>) {
    fun pose() = Pose(bones.size)

    /** Each bone's matrix for [pose] under [base] (the figure's place in the world), into [out]. */
    fun matrices(base: FloatArray, pose: Pose, out: Array<FloatArray>) {
        for ((i, b) in bones.withIndex()) {
            val local = Mat4.about(b.px, b.py, b.pz, pose.pitch[i], pose.yaw[i], pose.roll[i])
            Mat4.mul(if (b.parent < 0) base else out[b.parent], local, out[i])
        }
    }
}

/** How each bone is turned: about the side axis (pitch: positive swings the lower end backwards), the upright one and the forward one. */
class Pose(n: Int) {
    val pitch = FloatArray(n)
    val yaw = FloatArray(n)
    val roll = FloatArray(n)

    fun reset() {
        pitch.fill(0f); yaw.fill(0f); roll.fill(0f)
    }
}

/** Builds bones: parts added to the bone being made. */
class RigBuilder {
    private val bones = ArrayList<Bone>()

    fun bone(parent: Int, px: Float, py: Float, pz: Float, alpha: Float = 1f, build: FloatList.() -> Unit): Int {
        bones += Bone(FloatList(4096).apply(build).mesh(), parent, px, py, pz, alpha)
        return bones.size - 1
    }

    fun rig() = Rig(bones)
}

/** A rounded bar from a to b of radius [r]: a smooth cylinder with round ends. */
fun FloatList.tube(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, r: Float, c: Int) {
    val dx = bx - ax
    val dy = by - ay
    val dz = bz - az
    val len = sqrt(dx * dx + dy * dy + dz * dz)
    // Round ends.
    val e = r / 1.1f // (blob grows its box a little)
    blob(ax - e, ay - e, az - e, ax + e, ay + e, az + e, c, 10, 6)
    blob(bx - e, by - e, bz - e, bx + e, by + e, bz + e, c, 10, 6)
    if (len < 1e-4f) return
    val wx = dx / len
    val wy = dy / len
    val wz = dz / len
    // Two directions square to the bar.
    var ux: Float
    var uy: Float
    var uz: Float
    if (kotlin.math.abs(wy) < 0.9f) { ux = -wz; uy = 0f; uz = wx } else { ux = 0f; uy = wz; uz = -wy }
    val ul = sqrt(ux * ux + uy * uy + uz * uz)
    ux /= ul; uy /= ul; uz /= ul
    val vx = wy * uz - wz * uy
    val vy = wz * ux - wx * uz
    val vz = wx * uy - wy * ux
    val n = 12
    for (k in 0 until n) {
        val a0 = 2 * Math.PI * k / n
        val a1 = 2 * Math.PI * (k + 1) / n
        val c0 = cos(a0).toFloat(); val s0 = sin(a0).toFloat()
        val c1 = cos(a1).toFloat(); val s1 = sin(a1).toFloat()
        val n0x = ux * c0 + vx * s0; val n0y = uy * c0 + vy * s0; val n0z = uz * c0 + vz * s0
        val n1x = ux * c1 + vx * s1; val n1y = uy * c1 + vy * s1; val n1z = uz * c1 + vz * s1
        fun put(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float) {
            add(x); add(y); add(z)
            val g = if ((c and 0xFF000000.toInt()) == GLOW) 2f else 0f
            add(((c shr 16) and 0xFF) / 255f + g); add(((c shr 8) and 0xFF) / 255f + g); add((c and 0xFF) / 255f + g)
            add(nx); add(ny); add(nz)
        }
        put(ax + n0x * r, ay + n0y * r, az + n0z * r, n0x, n0y, n0z)
        put(bx + n0x * r, by + n0y * r, bz + n0z * r, n0x, n0y, n0z)
        put(bx + n1x * r, by + n1y * r, bz + n1z * r, n1x, n1y, n1z)
        put(ax + n0x * r, ay + n0y * r, az + n0z * r, n0x, n0y, n0z)
        put(bx + n1x * r, by + n1y * r, bz + n1z * r, n1x, n1y, n1z)
        put(ax + n1x * r, ay + n1y * r, az + n1z * r, n1x, n1y, n1z)
    }
}

/** A ring (a wheel's tyre) about the x axis at (cx, cy, cz), radius [radius], thickness [r]. */
fun FloatList.ring(cx: Float, cy: Float, cz: Float, radius: Float, r: Float, c: Int) {
    val n = 18
    for (k in 0 until n) {
        val a = 2 * Math.PI * k / n
        val y = cy + (cos(a) * radius).toFloat()
        val z = cz + (sin(a) * radius).toFloat()
        blob(cx - r * 0.8f, y - r, z - r, cx + r * 0.8f, y + r, z + r, c, 8, 5)
    }
}
