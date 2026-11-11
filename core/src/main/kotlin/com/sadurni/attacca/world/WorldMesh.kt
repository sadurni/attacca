package com.sadurni.attacca.world

import com.sadurni.attacca.diorama.Dog
import com.sadurni.attacca.diorama.SongLook
import kotlin.math.sqrt

/** Triangles ready for the screen: x, y, z, r, g, b, nx, ny, nz for each corner ([STRIDE] floats). */
class Mesh(val data: FloatArray, val vertices: Int) {
    companion object {
        const val STRIDE = 9
    }
}

/** A growing float array. */
class FloatList(capacity: Int = 4096) {
    var data = FloatArray(capacity)
    var size = 0

    fun add(v: Float) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }

    fun mesh() = Mesh(data.copyOf(size), size / Mesh.STRIDE)
}

private fun FloatList.vertex(x: Float, y: Float, z: Float, c: Int, nx: Float, ny: Float, nz: Float, light: Float = 1f) {
    add(x); add(y); add(z)
    // A glowing colour (see [GLOW]) is kept 2 above the others, so the screen leaves it unlit.
    val g = if ((c and 0xFF000000.toInt()) == GLOW) 2f else 0f
    add(((c shr 16) and 0xFF) / 255f * light + g)
    add(((c shr 8) and 0xFF) / 255f * light + g)
    add((c and 0xFF) / 255f * light + g)
    add(nx); add(ny); add(nz)
}

/** A flat quad of one colour facing (nx, ny, nz), corners a b c d in turn. */
private fun FloatList.face(
    ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float,
    cx: Float, cy: Float, cz: Float, dx: Float, dy: Float, dz: Float,
    c: Int, nx: Float, ny: Float, nz: Float,
) {
    vertex(ax, ay, az, c, nx, ny, nz); vertex(bx, by, bz, c, nx, ny, nz); vertex(cx, cy, cz, c, nx, ny, nz)
    vertex(ax, ay, az, c, nx, ny, nz); vertex(cx, cy, cz, c, nx, ny, nz); vertex(dx, dy, dz, c, nx, ny, nz)
}

/** A box of one colour from (x0, y0, z0) to (x1, y1, z1). */
fun FloatList.box(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, c: Int) {
    face(x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, c, 0f, 1f, 0f)
    face(x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, c, 0f, -1f, 0f)
    face(x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, c, 1f, 0f, 0f)
    face(x0, y0, z1, x0, y1, z1, x0, y1, z0, x0, y0, z0, c, -1f, 0f, 0f)
    face(x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, c, 0f, 0f, 1f)
    face(x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, c, 0f, 0f, -1f)
}

/** A triangle of one colour, lit as its face (normal from its corners). */
fun FloatList.tri(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, cx: Float, cy: Float, cz: Float, c: Int) {
    val ux = bx - ax; val uy = by - ay; val uz = bz - az
    val vx = cx - ax; val vy = cy - ay; val vz = cz - az
    var nx = uy * vz - uz * vy
    var ny = uz * vx - ux * vz
    var nz = ux * vy - uy * vx
    val l = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f)
    nx /= l; ny /= l; nz /= l
    vertex(ax, ay, az, c, nx, ny, nz); vertex(bx, by, bz, c, nx, ny, nz); vertex(cx, cy, cz, c, nx, ny, nz)
}

/** A gable roof over the box (x0, z0)–(x1, z1) from eaves at [y0] to the ridge at [y1], the ridge along z. */
fun FloatList.gable(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, c: Int) {
    val mx = (x0 + x1) / 2
    val hw = (x1 - x0) / 2
    val rise = y1 - y0
    val l = sqrt(hw * hw + rise * rise)
    // The two slopes.
    face(x0, y0, z0, x0, y0, z1, mx, y1, z1, mx, y1, z0, c, -rise / l, hw / l, 0f)
    face(mx, y1, z0, mx, y1, z1, x1, y0, z1, x1, y0, z0, c, rise / l, hw / l, 0f)
    // The two gable ends (triangles).
    vertex(x0, y0, z1, c, 0f, 0f, 1f); vertex(x1, y0, z1, c, 0f, 0f, 1f); vertex(mx, y1, z1, c, 0f, 0f, 1f)
    vertex(x1, y0, z0, c, 0f, 0f, -1f); vertex(x0, y0, z0, c, 0f, 0f, -1f); vertex(mx, y1, z0, c, 0f, 0f, -1f)
}

/** The land's meshes: a smooth surface, a chunk of [CHUNK] rows at a time across the whole width, with its water. */
object LandMesher {
    const val CHUNK = 16

    /** Distance between the surface's points. */
    private const val STEP = 1f

    /** Chunk [index] of [land]; [step] the distance between its points (wider for far chunks on the desktop). */
    fun chunk(land: WorldLand, index: Int, step: Float = STEP, minProp: Float = 0f): Mesh {
        val STEP = step
        val w = WorldLand.HALF_WIDTH
        val z0 = index * CHUNK
        val nx = ((2 * w) / STEP).toInt() + 1
        val nz = (CHUNK / STEP).toInt() + 1
        // Heights with a border of one point for the slopes.
        val gw = nx + 2
        val gh = nz + 2
        val h = FloatArray(gw * gh)
        for (j in 0 until gh) for (i in 0 until gw) {
            h[j * gw + i] = land.heightAt(-w + (i - 1) * STEP, z0 + (j - 1) * STEP)
        }
        fun at(i: Int, j: Int) = h[(j + 1) * gw + (i + 1)]
        // Each point: position, colour and the slope's normal (from its neighbours).
        val px = FloatArray(nx * nz)
        val py = FloatArray(nx * nz)
        val pz = FloatArray(nx * nz)
        val col = IntArray(nx * nz)
        val nrm = FloatArray(nx * nz * 3)
        for (j in 0 until nz) for (i in 0 until nx) {
            val k = j * nx + i
            val x = -w + i * STEP
            val z = z0 + j * STEP
            val y = at(i, j)
            var ax = -(at(i + 1, j) - at(i - 1, j)) / (2 * STEP)
            var ay = 1f
            var az = -(at(i, j + 1) - at(i, j - 1)) / (2 * STEP)
            val l = sqrt(ax * ax + ay * ay + az * az)
            ax /= l; ay /= l; az /= l
            px[k] = x; py[k] = y; pz[k] = z
            nrm[k * 3] = ax; nrm[k * 3 + 1] = ay; nrm[k * 3 + 2] = az
            col[k] = land.colorAt(x, z, y, ay)
        }
        val out = FloatList(nx * nz * 6 * Mesh.STRIDE + 64)
        fun put(k: Int) = out.vertex(px[k], py[k], pz[k], col[k], nrm[k * 3], nrm[k * 3 + 1], nrm[k * 3 + 2])
        for (j in 0 until nz - 1) for (i in 0 until nx - 1) {
            val a = j * nx + i
            val b = a + 1
            val c = a + nx
            val d = c + 1
            // Split each cell along the diagonal that keeps the surface smoother.
            if (abs(py[a] - py[d]) < abs(py[b] - py[c])) {
                put(a); put(c); put(d); put(a); put(d); put(b)
            } else {
                put(a); put(c); put(b); put(b); put(c); put(d)
            }
        }
        // What stands on the land there (trees, rocks, buildings…).
        Props.place(land, z0, z0 + CHUNK, out, minSize = minProp)
        // The places' stone and wood (a bridge, a tunnel, a station's platform and track…).
        if (land.places.isNotEmpty()) Places.bake(land, land.places, z0, z0 + CHUNK, out)
        // The water's surface over the whole chunk, under the land where the land is higher.
        val wy = WorldLand.WATER - 0.1f
        val x0 = -w.toFloat()
        val x1 = w.toFloat()
        val zb = z0.toFloat()
        val ze = (z0 + CHUNK).toFloat()
        // In cells of two, so it can move with waves; the normal's height 2 tells the screen it is water.
        val wc = land.waterColorAt(zb)
        var wz = zb
        while (wz < ze) {
            var wx = x0
            while (wx < x1) {
                // Only where the land is low enough for water to show.
                if (land.heightAt(wx + 1f, wz + 1f) < WorldLand.WATER + 1.5f) {
                    out.face(wx, wy, wz, wx, wy, wz + 2f, wx + 2f, wy, wz + 2f, wx + 2f, wy, wz, if (wz < 60f) land.waterColorAt(wz) else wc, 0f, 2f, 0f)
                }
                wx += 2f
            }
            wz += 2f
        }
        return out.mesh()
    }

    private fun abs(v: Float) = if (v < 0) -v else v
}

/** A figure in parts that swing as it goes: legs, tail. */
class Figure(val parts: List<Part>) {
    /** One piece: its mesh, the point it swings about (y, z) and how far it swings (radians) and when. */
    class Part(val mesh: Mesh, val pivotY: Float, val pivotZ: Float, val swing: Float, val phase: Float)
}

/**
 * A rounded shape filling the box from (x0, y0, z0) to (x1, y1, z1): an ellipsoid a little larger
 * than the box fits, so parts that touch run into each other; smooth normals.
 */
fun FloatList.blob(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, c: Int, slices: Int = 12, stacks: Int = 8) {
    val cx = (x0 + x1) / 2
    val cy = (y0 + y1) / 2
    val cz = (z0 + z1) / 2
    val rx = (x1 - x0) / 2 * 1.12f
    val ry = (y1 - y0) / 2 * 1.08f
    val rz = (z1 - z0) / 2 * 1.12f
    fun point(st: Int, sl: Int): FloatArray {
        val v = Math.PI * st / stacks
        val u = 2 * Math.PI * sl / slices
        val sx = (kotlin.math.sin(v) * kotlin.math.cos(u)).toFloat()
        val sy = kotlin.math.cos(v).toFloat()
        val sz = (kotlin.math.sin(v) * kotlin.math.sin(u)).toFloat()
        // The normal of an ellipsoid: the sphere's, divided by the radii.
        var nx = sx / rx
        var ny = sy / ry
        var nz = sz / rz
        val l = sqrt(nx * nx + ny * ny + nz * nz)
        nx /= l; ny /= l; nz /= l
        return floatArrayOf(cx + sx * rx, cy + sy * ry, cz + sz * rz, nx, ny, nz)
    }
    for (st in 0 until stacks) for (sl in 0 until slices) {
        val a = point(st, sl)
        val b = point(st + 1, sl)
        val d = point(st, sl + 1)
        val e = point(st + 1, sl + 1)
        for (p in listOf(a, b, e, a, e, d)) vertex(p[0], p[1], p[2], c, p[3], p[4], p[5])
    }
}

/** The figures of Attacca World, made of rounded shapes. */
object Figures {
    private fun part(swing: Float = 0f, phase: Float = 0f, pivotY: Float = 0f, pivotZ: Float = 0f, build: FloatList.() -> Unit) =
        Figure.Part(FloatList(2048).apply(build).mesh(), pivotY, pivotZ, swing, phase)

    /** The traveller on foot, in the song's colours: about 1.8 units tall, facing +z. */
    fun walker(look: SongLook): Figure {
        val coat = look.palette.accent or (0xFF shl 24)
        val trousers = 0xFF2F3440.toInt()
        val skin = 0xFFE6B48C.toInt()
        val hair = 0xFF3A2A20.toInt()
        val boots = 0xFF2A2020.toInt()
        val body = part {
            blob(-0.28f, 0.85f, -0.16f, 0.28f, 1.42f, 0.16f, coat)
            blob(-0.2f, 1.42f, -0.18f, 0.2f, 1.8f, 0.2f, skin)
            blob(-0.22f, 1.68f, -0.22f, 0.22f, 1.86f, 0.2f, hair)
            blob(-0.22f, 1.5f, -0.24f, 0.22f, 1.72f, -0.16f, hair)
            blob(-0.1f, 1.58f, 0.2f, -0.04f, 1.64f, 0.22f, 0xFF201818.toInt())
            blob(0.04f, 1.58f, 0.2f, 0.1f, 1.64f, 0.22f, 0xFF201818.toInt())
            // A small pack on the back.
            blob(-0.2f, 0.95f, -0.32f, 0.2f, 1.35f, -0.16f, 0xFF6B4A2E.toInt())
        }
        fun leg(x: Float, phase: Float) = part(0.55f, phase, 0.85f, 0f) {
            blob(x - 0.11f, 0.1f, -0.1f, x + 0.11f, 0.86f, 0.1f, trousers)
            blob(x - 0.12f, 0f, -0.12f, x + 0.12f, 0.14f, 0.16f, boots)
        }
        fun arm(x: Float, phase: Float) = part(0.45f, phase, 1.38f, 0f) {
            blob(x - 0.09f, 0.88f, -0.09f, x + 0.09f, 1.4f, 0.09f, coat)
            blob(x - 0.08f, 0.8f, -0.08f, x + 0.08f, 0.9f, 0.08f, skin)
        }
        return Figure(listOf(body, leg(-0.13f, 0f), leg(0.13f, 0.5f), arm(-0.37f, 0.5f), arm(0.37f, 0f)))
    }

    /** One of the four dogs, as in O'rama, facing +z. */
    fun dog(dog: Dog): Figure {
        // height, length, thickness (blocks), coat, light, nose
        data class K(val h: Float, val l: Float, val t: Float, val coat: Int, val light: Int, val nose: Int)
        val k = when (dog) {
            Dog.GOLDIE -> K(0.5f, 0.85f, 0.36f, 0xFFD6A050.toInt(), 0xFFF0CD8C.toInt(), 0xFFE58F98.toInt())
            Dog.MALAMUTE -> K(0.72f, 1.15f, 0.46f, 0xFF2F2D31.toInt(), 0xFFE9DCC4.toInt(), 0xFF1A1818.toInt())
            Dog.GALGO -> K(0.86f, 1.2f, 0.34f, 0xFFC98E52.toInt(), 0xFFF3E8DA.toInt(), 0xFFE0939A.toInt())
            Dog.BRACO -> K(0.5f, 0.82f, 0.3f, 0xFFF4F2EE.toInt(), 0xFF1E1E22.toInt(), 0xFF2A2224.toInt())
        }
        val legH = k.h * 0.5f
        val half = k.t / 2
        val headColour = if (dog == Dog.BRACO) k.light else k.coat
        val body = part {
            // Body; the malamute dark above and white below, the others with a lighter chest.
            blob(-half, legH, -k.l / 2, half, k.h, k.l / 2, k.coat)
            blob(-half * 0.9f, legH - 0.02f, -k.l / 2 + 0.05f, half * 0.9f, legH + 0.1f, k.l / 2 - 0.05f, k.light)
            if (dog == Dog.BRACO) blob(-half - 0.01f, k.h - 0.18f, -0.1f, half + 0.01f, k.h + 0.01f, 0.12f, k.light)
            // Head and snout, ears.
            val hz = k.l / 2
            blob(-half * 0.8f, k.h - 0.05f, hz - 0.08f, half * 0.8f, k.h + 0.26f, hz + 0.22f, headColour)
            blob(-half * 0.45f, k.h - 0.02f, hz + 0.22f, half * 0.45f, k.h + 0.11f, hz + 0.4f, headColour)
            blob(-0.05f, k.h + 0.06f, hz + 0.4f, 0.05f, k.h + 0.12f, hz + 0.43f, k.nose)
            val ear = if (dog == Dog.GALGO) 0.16f else 0.1f
            blob(-half * 0.8f, k.h + 0.2f, hz - 0.02f, -half * 0.4f, k.h + 0.26f + ear, hz + 0.06f, headColour)
            blob(half * 0.4f, k.h + 0.2f, hz - 0.02f, half * 0.8f, k.h + 0.26f + ear, hz + 0.06f, headColour)
        }
        fun leg(x: Float, z: Float, phase: Float) = part(0.6f, phase, legH, z) {
            val c = if (dog == Dog.MALAMUTE) k.light else if (dog == Dog.BRACO) k.coat else k.coat
            blob(x - 0.06f, 0f, z - 0.06f, x + 0.06f, legH + 0.02f, z + 0.06f, c)
        }
        val tail = part(0.35f, 0.25f, k.h - 0.05f, -k.l / 2) {
            when (dog) {
                Dog.MALAMUTE -> blob(-0.08f, k.h - 0.05f, -k.l / 2 - 0.1f, 0.08f, k.h + 0.28f, -k.l / 2 + 0.06f, k.light)
                Dog.GALGO -> blob(-0.04f, k.h - 0.15f, -k.l / 2 - 0.12f, 0.04f, k.h - 0.05f, -k.l / 2, k.coat)
                else -> blob(-0.05f, k.h - 0.12f, -k.l / 2 - 0.3f, 0.05f, k.h - 0.02f, -k.l / 2, k.coat)
            }
        }
        val fz = k.l / 2 - 0.1f
        val bz = -k.l / 2 + 0.1f
        return Figure(listOf(body, leg(-half + 0.06f, fz, 0f), leg(half - 0.06f, fz, 0.5f), leg(-half + 0.06f, bz, 0.5f), leg(half - 0.06f, bz, 0f), tail))
    }
}
