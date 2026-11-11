package com.sadurni.attacca.world

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** 4 × 4 matrices as OpenGL takes them (column after column, 16 floats). */
object Mat4 {
    fun identity(): FloatArray = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }

    fun perspective(fovYRadians: Float, aspect: Float, near: Float, far: Float): FloatArray {
        val f = 1f / tan(fovYRadians / 2f)
        val m = FloatArray(16)
        m[0] = f / aspect
        m[5] = f
        m[10] = (far + near) / (near - far)
        m[11] = -1f
        m[14] = 2f * far * near / (near - far)
        return m
    }

    fun lookAt(ex: Float, ey: Float, ez: Float, cx: Float, cy: Float, cz: Float): FloatArray {
        var fx = cx - ex
        var fy = cy - ey
        var fz = cz - ez
        val fl = sqrt(fx * fx + fy * fy + fz * fz).coerceAtLeast(1e-6f)
        fx /= fl; fy /= fl; fz /= fl
        // side = f × up (up = y)
        var sx = -fz
        var sz = fx
        val sl = sqrt(sx * sx + sz * sz).coerceAtLeast(1e-6f)
        sx /= sl; sz /= sl
        // u = s × f
        val ux = -sz * fy
        val uy = sz * fx - sx * fz
        val uz = sx * fy
        val m = FloatArray(16)
        m[0] = sx; m[4] = 0f; m[8] = sz
        m[1] = ux; m[5] = uy; m[9] = uz
        m[2] = -fx; m[6] = -fy; m[10] = -fz
        m[12] = -(sx * ex + sz * ez)
        m[13] = -(ux * ex + uy * ey + uz * ez)
        m[14] = fx * ex + fy * ey + fz * ez
        m[15] = 1f
        return m
    }

    /** a × b. */
    fun mul(a: FloatArray, b: FloatArray, out: FloatArray = FloatArray(16)): FloatArray {
        for (c in 0 until 4) for (r in 0 until 4) {
            var s = 0f
            for (k in 0 until 4) s += a[k * 4 + r] * b[c * 4 + k]
            out[c * 4 + r] = s
        }
        return out
    }

    /**
     * Turned about the point (px, py, pz): [yaw] about the upright axis, [pitch] about the side
     * one, [roll] about the forward one (in that order: R = Ry · Rx · Rz).
     */
    fun about(px: Float, py: Float, pz: Float, pitch: Float, yaw: Float, roll: Float): FloatArray {
        if (pitch == 0f && yaw == 0f && roll == 0f) return identity()
        val cy = cos(yaw); val sy = sin(yaw)
        val cp = cos(pitch); val sp = sin(pitch)
        val cr = cos(roll); val sr = sin(roll)
        // Rows of Ry · Rx · Rz.
        val r00 = cy * cr + sy * sp * sr
        val r01 = -cy * sr + sy * sp * cr
        val r02 = sy * cp
        val r10 = cp * sr
        val r11 = cp * cr
        val r12 = -sp
        val r20 = -sy * cr + cy * sp * sr
        val r21 = sy * sr + cy * sp * cr
        val r22 = cy * cp
        val m = FloatArray(16)
        m[0] = r00; m[1] = r10; m[2] = r20
        m[4] = r01; m[5] = r11; m[6] = r21
        m[8] = r02; m[9] = r12; m[10] = r22
        m[12] = px - (r00 * px + r01 * py + r02 * pz)
        m[13] = py - (r10 * px + r11 * py + r12 * pz)
        m[14] = pz - (r20 * px + r21 * py + r22 * pz)
        m[15] = 1f
        return m
    }

    /** Moved to (x, y, z), turned [yaw] about the vertical, tilted [pitch] about the side axis, scaled [s]. */
    fun model(x: Float, y: Float, z: Float, yaw: Float = 0f, pitch: Float = 0f, s: Float = 1f): FloatArray {
        val cy = cos(yaw)
        val sy = sin(yaw)
        val cp = cos(pitch)
        val sp = sin(pitch)
        // R = Ry(yaw) · Rx(pitch), then scale and move.
        val m = FloatArray(16)
        m[0] = cy * s; m[1] = 0f; m[2] = -sy * s
        m[4] = sy * sp * s; m[5] = cp * s; m[6] = cy * sp * s
        m[8] = sy * cp * s; m[9] = -sp * s; m[10] = cy * cp * s
        m[12] = x; m[13] = y; m[14] = z; m[15] = 1f
        return m
    }
}
