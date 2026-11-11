package com.sadurni.attacca.world

import com.sadurni.attacca.diorama.World
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * What stands on each world's land: trees, palms, rocks, buildings, towers, crystals… placed
 * about the way (never on it, never in the water), more or fewer by how high the land is there,
 * and baked into the land's chunks. Colours above 1 glow (they are not lit, see the renderer).
 */
/** The alpha byte that marks a glowing colour. */
const val GLOW = 0xFE000000.toInt()

object Props {
    /** A colour that glows: kept apart from lit colours by adding 2. */
    fun glow(c: Int): Int = (c and 0xFFFFFF) or GLOW

    private class Kind(val mesh: Mesh, val minScale: Float, val maxScale: Float, val house: Boolean, val sways: Boolean = false) {
        /** How far out it reaches about its foot, and how high, at scale 1. */
        val radius: Float
        val height: Float

        init {
            var r = 0f
            var h = 0f
            var i = 0
            while (i < mesh.data.size) {
                r = max(r, kotlin.math.hypot(mesh.data[i], mesh.data[i + 2]))
                h = max(h, mesh.data[i + 1])
                i += Mesh.STRIDE
            }
            radius = r
            height = h
        }
    }

    private fun mesh(build: FloatList.() -> Unit) = FloatList(2048).apply(build).mesh()

    private val cache = HashMap<String, Kind>()

    // Made on the land's worker thread and asked by the camera's: one at a time.
    private fun kind(name: String, minScale: Float = 0.8f, maxScale: Float = 1.3f, build: FloatList.() -> Unit) =
        synchronized(cache) { cache.getOrPut(name) { Kind(mesh(build), minScale, maxScale, name.startsWith("house"), SWAYING.any { name.startsWith(it) }) } }

    /** The things that sway with the music (plants), as O'rama's trees do. */
    private val SWAYING = listOf("broadleaf", "pine", "bush", "flowers", "palm", "giant", "fern", "cherry", "bamboo", "seaweed", "coral", "anemone", "dead", "charred")

    /**
     * How a swaying point is marked for the screens: its normal shortened to 0.5 at the plant's
     * foot down to 0.2 at 7 high (lit the same once made unit again; land and water keep 1 and 2).
     */
    fun swayOf(normalLength: Float): Float = if (normalLength > 0.15f && normalLength < 0.55f) ((0.5f - normalLength) / 0.3f).coerceIn(0f, 1f) else -1f

    // ---- The things ----

    private fun broadleaf(c: Int) = kind("broadleaf$c") {
        tube(0f, 0f, 0f, 0f, 1.6f, 0f, 0.16f, 0xFF6B4A2E.toInt())
        blob(-1.1f, 1.3f, -1.0f, 1.0f, 3.1f, 1.1f, c)
        blob(-0.4f, 2.2f, -0.6f, 1.2f, 3.5f, 0.8f, shade(c, 1.12f))
        blob(-1.2f, 1.9f, -0.2f, 0.1f, 3.0f, 1.0f, shade(c, 0.9f))
    }

    private fun pine(c: Int, snow: Boolean) = kind("pine$c$snow") {
        tube(0f, 0f, 0f, 0f, 1.0f, 0f, 0.13f, 0xFF5A3E28.toInt())
        for ((k, y) in floatArrayOf(0.8f, 1.7f, 2.5f, 3.2f).withIndex()) {
            val r = 1.2f - k * 0.26f
            blob(-r, y, -r, r, y + 1.0f, r, if (snow && k == 3) 0xFFF4F8FC.toInt() else c)
            if (snow) blob(-r * 0.8f, y + 0.75f, -r * 0.8f, r * 0.8f, y + 0.95f, r * 0.8f, 0xFFF4F8FC.toInt())
        }
    }

    private fun bush(c: Int) = kind("bush$c", 0.6f, 1.2f) {
        blob(-0.7f, -0.1f, -0.6f, 0.6f, 0.8f, 0.6f, c)
        blob(0.0f, -0.1f, -0.4f, 0.9f, 0.65f, 0.5f, shade(c, 1.1f))
    }

    private fun rock(c: Int) = kind("rock$c", 0.5f, 1.6f) {
        blob(-0.8f, -0.3f, -0.6f, 0.7f, 0.8f, 0.7f, c)
        blob(0.2f, -0.3f, -0.3f, 1.1f, 0.5f, 0.5f, shade(c, 0.88f))
    }

    private fun flowers(c: Int) = kind("flowers$c", 0.8f, 1.2f) {
        for (k in 0 until 7) {
            val a = k * 2.4f
            val x = cos(a) * (0.2f + k * 0.08f)
            val z = sin(a) * (0.2f + k * 0.08f)
            tube(x, 0f, z, x, 0.3f, z, 0.025f, 0xFF4E9A3A.toInt())
            blob(x - 0.08f, 0.28f, z - 0.08f, x + 0.08f, 0.42f, z + 0.08f, if (k % 2 == 0) c else 0xFFFFF2B0.toInt())
        }
    }

    private fun palm() = kind("palm", 0.9f, 1.3f) {
        var x = 0f
        var y = 0f
        for (k in 0 until 6) {
            val nx = x + 0.12f * k / 5f
            val ny = y + 0.75f
            tube(x, y, 0f, nx, ny, 0f, 0.14f - k * 0.01f, 0xFF8A6A44.toInt())
            x = nx; y = ny
        }
        for (k in 0 until 6) {
            val a = k * (2 * Math.PI / 6).toFloat()
            blob(x + cos(a) * 0.3f - 0.9f * abs(cos(a)) - 0.2f, y - 0.5f, sin(a) * 0.3f - 0.25f - 0.6f * abs(sin(a)),
                x + cos(a) * 1.4f + 0.2f, y + 0.2f, sin(a) * 1.4f + 0.25f, 0xFF4FA04A.toInt())
        }
        blob(x - 0.25f, y - 0.3f, -0.25f, x + 0.25f, y + 0.1f, 0.25f, 0xFF7A5A32.toInt())
    }

    private fun deadTree() = kind("dead", 0.9f, 1.4f) {
        val c = 0xFF4A3E36.toInt()
        tube(0f, 0f, 0f, 0.1f, 2.4f, 0f, 0.15f, c)
        tube(0.05f, 1.4f, 0f, 0.9f, 2.4f, 0.2f, 0.07f, c)
        tube(0.08f, 1.8f, 0f, -0.7f, 2.7f, -0.2f, 0.06f, c)
        tube(0.1f, 2.3f, 0f, 0.4f, 3.2f, 0.3f, 0.05f, c)
    }

    private fun tower(stone: Int, roof: Int) = kind("tower$stone", 1.0f, 1.4f) {
        tube(0f, 0f, 0f, 0f, 5.5f, 0f, 0.9f, stone)
        blob(-1.25f, 5.0f, -1.25f, 1.25f, 5.7f, 1.25f, shade(stone, 0.9f))
        for (k in 0 until 6) {
            val a = k * (Math.PI / 3).toFloat()
            blob(cos(a) * 1.0f - 0.22f, 5.6f, sin(a) * 1.0f - 0.22f, cos(a) * 1.0f + 0.22f, 6.1f, sin(a) * 1.0f + 0.22f, stone)
        }
        blob(-0.25f, 2.6f, 0.75f, 0.25f, 3.3f, 0.95f, glow(0xFFFFD27A.toInt()))
        tube(0f, 6.0f, 0f, 0f, 7.6f, 0f, 0.05f, 0xFF3A3030.toInt())
        blob(0f, 7.0f, -0.05f, 0.7f, 7.5f, 0.05f, roof)
    }

    private fun building(c: Int, floors: Int) = kind("building$c$floors", 1.0f, 1.0f) {
        val h = floors * 1.4f
        box(-1.8f, 0f, -1.8f, 1.8f, h, 1.8f, c)
        box(-1.9f, h, -1.9f, 1.9f, h + 0.25f, 1.9f, shade(c, 0.8f))
        for (f in 0 until floors) {
            val y = 0.6f + f * 1.4f
            for (k in -1..1) {
                val lit = (f * 7 + k * 3 + floors) % 4 == 0
                val w = if (lit) glow(0xFFFFE6A0.toInt()) else 0xFF7A90A8.toInt()
                box(k * 1.0f - 0.3f, y, 1.8f, k * 1.0f + 0.3f, y + 0.6f, 1.86f, w)
                box(k * 1.0f - 0.3f, y, -1.86f, k * 1.0f + 0.3f, y + 0.6f, -1.8f, w)
                box(1.8f, y, k * 1.0f - 0.3f, 1.86f, y + 0.6f, k * 1.0f + 0.3f, w)
                box(-1.86f, y, k * 1.0f - 0.3f, -1.8f, y + 0.6f, k * 1.0f + 0.3f, w)
            }
        }
    }

    private fun lamp(light: Int) = kind("lamp$light", 1.0f, 1.0f) {
        tube(0f, 0f, 0f, 0f, 2.6f, 0f, 0.06f, 0xFF2E2E36.toInt())
        tube(0f, 2.6f, 0f, 0.5f, 2.75f, 0f, 0.05f, 0xFF2E2E36.toInt())
        blob(0.35f, 2.5f, -0.15f, 0.7f, 2.72f, 0.15f, glow(light))
    }

    private fun neonTower(a: Int, b: Int) = kind("neon$a$b", 0.8f, 1.6f) {
        box(-1.1f, 0f, -1.1f, 1.1f, 9f, 1.1f, 0xFF1E1A2E.toInt())
        for (k in 0 until 6) {
            val y = 1.2f + k * 1.3f
            box(-1.16f, y, -1.16f, 1.16f, y + 0.18f, 1.16f, glow(if (k % 2 == 0) a else b))
        }
        tube(0f, 9f, 0f, 0f, 10.5f, 0f, 0.05f, 0xFF3A3450.toInt())
        blob(-0.15f, 10.4f, -0.15f, 0.15f, 10.7f, 0.15f, glow(0xFFFF3050.toInt()))
    }

    private fun iceSpike() = kind("icespike", 0.7f, 1.8f) {
        blob(-0.6f, -0.2f, -0.6f, 0.6f, 2.8f, 0.6f, 0xFFDCEEFA.toInt())
        blob(0.2f, -0.2f, -0.3f, 0.9f, 1.6f, 0.4f, 0xFFC4E2F4.toInt())
    }

    private fun crystal(c: Int) = kind("crystal$c", 0.6f, 1.6f) {
        blob(-0.3f, -0.1f, -0.3f, 0.3f, 2.2f, 0.3f, glow(c))
        blob(0.1f, -0.1f, -0.2f, 0.55f, 1.4f, 0.25f, glow(shade(c, 0.8f)))
        blob(-0.6f, -0.1f, 0.0f, -0.2f, 1.0f, 0.35f, glow(shade(c, 1.15f)))
    }

    private fun antenna() = kind("antenna", 1.0f, 1.2f) {
        tube(0f, 0f, 0f, 0f, 3.2f, 0f, 0.07f, 0xFFB8B8C4.toInt())
        blob(-0.8f, 3.0f, -0.1f, 0.8f, 3.5f, 0.3f, 0xFFD0D0DA.toInt())
        blob(-0.08f, 3.5f, -0.08f, 0.08f, 3.66f, 0.08f, glow(0xFFFF5050.toInt()))
    }

    /** A house: walls, a gable roof, a door, windows (one lit), a chimney. */
    private fun house(wall: Int, roof: Int, snowy: Boolean = false) = kind("house$wall$roof$snowy", 0.9f, 1.25f) {
        box(-1.4f, 0f, -1.9f, 1.4f, 2.0f, 1.9f, wall)
        val r = if (snowy) 0xFFF2F6FA.toInt() else roof
        gable(-1.65f, 1.95f, -2.15f, 1.65f, 3.4f, 2.15f, r)
        box(-0.35f, 0f, 1.9f, 0.35f, 1.25f, 1.96f, 0xFF6B4A2E.toInt())
        box(0.65f, 0.95f, 1.9f, 1.15f, 1.45f, 1.96f, glow(0xFFFFD98A.toInt()))
        box(-1.15f, 0.95f, 1.9f, -0.65f, 1.45f, 1.96f, 0xFF7A90A8.toInt())
        box(1.4f, 0.95f, -0.9f, 1.46f, 1.45f, -0.3f, 0xFF7A90A8.toInt())
        box(-1.46f, 0.95f, 0.3f, -1.4f, 1.45f, 0.9f, 0xFF7A90A8.toInt())
        box(0.6f, 2.6f, -1.0f, 1.0f, 3.7f, -0.6f, 0xFF8A5A44.toInt())
    }

    /** A house fitting [w] (stone in the castles' land, a cabin on the ice, bright by the sea). */
    private fun houseOf(w: World, k: Float): Kind? = when (w) {
        World.MEADOW -> house(listOf(0xFFF2EAD8.toInt(), 0xFFEADCC0.toInt(), 0xFFE8E2D6.toInt())[(k * 97).toInt() % 3], listOf(0xFFB0442E.toInt(), 0xFF8A5236.toInt(), 0xFF5A4E48.toInt())[(k * 53).toInt() % 3])
        World.COAST -> house(listOf(0xFFF8F6F0.toInt(), 0xFFF2D7A0.toInt(), 0xFFB8DCEC.toInt(), 0xFFF0C0B0.toInt())[(k * 97).toInt() % 4], listOf(0xFF2E7DB0.toInt(), 0xFFC0503A.toInt())[(k * 53).toInt() % 2])
        World.CASTLE -> house(0xFF7E7668.toInt(), 0xFF4A3E3A.toInt())
        World.ICE -> house(0xFF8A5E3E.toInt(), 0xFF5A3E2E.toInt(), snowy = true)
        World.WALL, World.JAPAN -> house(0xFFE8E0D0.toInt(), 0xFF3A3A40.toInt())
        World.JUNGLE -> house(0xFFE8C090.toInt(), 0xFF8A5236.toInt())
        World.DESERT -> house(0xFFE8D0A0.toInt(), 0xFFD0B080.toInt())
        else -> null
    }

    // ---- The newer worlds' things ----

    private fun pyramid() = kind("pyramid", 1.0f, 1.6f) {
        val c = 0xFFE0B878.toInt()
        val d = 0xFFC49A5E.toInt()
        val s = 6f
        val t = 7f
        tri(-s, 0f, s, s, 0f, s, 0f, t, 0f, c)
        tri(s, 0f, s, s, 0f, -s, 0f, t, 0f, d)
        tri(s, 0f, -s, -s, 0f, -s, 0f, t, 0f, c)
        tri(-s, 0f, -s, -s, 0f, s, 0f, t, 0f, d)
        blob(-0.3f, t - 0.4f, -0.3f, 0.3f, t + 0.2f, 0.3f, glow(0xFFFFE6A0.toInt()))
    }

    private fun watchtower() = kind("watchtower", 0.9f, 1.2f) {
        val stone = 0xFFB8A888.toInt()
        box(-1.6f, -1f, -1.6f, 1.6f, 4.2f, 1.6f, stone)
        for (k in 0 until 4) {
            val a = k * 1.5708f
            val cx = kotlin.math.cos(a) * 1.3f
            val cz = kotlin.math.sin(a) * 1.3f
            box(cx - 0.35f, 4.2f, cz - 0.35f, cx + 0.35f, 4.8f, cz + 0.35f, stone)
        }
        // A stretch of wall each way.
        box(-1.1f, -1f, 1.6f, 1.1f, 2.6f, 6f, stone)
        box(-1.1f, -1f, -6f, 1.1f, 2.6f, -1.6f, stone)
        for (z in floatArrayOf(2.2f, 3.4f, 4.6f, -2.2f, -3.4f, -4.6f)) box(-1.1f, 2.6f, z - 0.3f, 1.1f, 3.1f, z + 0.3f, stone)
        gable(-1.9f, 4.8f, -1.9f, 1.9f, 6.0f, 1.9f, 0xFFA8322A.toInt())
    }

    private fun pagoda3() = kind("pagoda3", 0.9f, 1.3f) {
        var y = 0f
        var r = 1.6f
        for (k in 0 until 4) {
            box(-r * 0.7f, y, -r * 0.7f, r * 0.7f, y + 1.2f, r * 0.7f, 0xFFC0392B.toInt())
            gable(-r * 1.15f, y + 1.15f, -r * 1.15f, r * 1.15f, y + 1.75f, r * 1.15f, 0xFF3A3A40.toInt())
            y += 1.6f
            r *= 0.8f
        }
        tube(0f, y, 0f, 0f, y + 1.2f, 0f, 0.06f, 0xFF3A3A40.toInt())
    }

    private fun redLantern() = kind("lantern", 1.0f, 1.0f) {
        tube(0f, 0f, 0f, 0f, 2.2f, 0f, 0.05f, 0xFF3A2A20.toInt())
        tube(0f, 2.2f, 0f, 0.4f, 2.25f, 0f, 0.04f, 0xFF3A2A20.toInt())
        blob(0.2f, 1.75f, -0.2f, 0.6f, 2.2f, 0.2f, glow(0xFFFF5A3A.toInt()))
    }

    private fun templeTower() = kind("temple", 0.9f, 1.3f) {
        val stone = 0xFFC8955A.toInt()
        val shade = 0xFFA87A48.toInt()
        var y = 0f
        var r = 2.6f
        for (k in 0 until 6) {
            box(-r, y, -r, r, y + 0.9f, r, if (k % 2 == 0) stone else shade)
            y += 0.9f
            r *= 0.8f
        }
        blob(-r, y - 0.2f, -r, r, y + 1.6f, r, stone)
        blob(-0.25f, y + 1.4f, -0.25f, 0.25f, y + 2.0f, 0.25f, glow(0xFFFFC46A.toInt()))
        box(-0.5f, 0f, 2.6f, 0.5f, 1.4f, 2.66f, glow(0xFFFFB04A.toInt()))
    }

    private fun giantTree() = kind("giant", 0.9f, 1.4f) {
        tube(0f, -0.5f, 0f, 0.2f, 8f, 0.1f, 0.4f, 0xFF5A4030.toInt())
        for (k in 0 until 4) {
            val a = k * 1.57f
            tube(0f, 0.6f, 0f, kotlin.math.cos(a) * 1.0f, -0.3f, kotlin.math.sin(a) * 1.0f, 0.18f, 0xFF5A4030.toInt())
        }
        blob(-3f, 7f, -2.6f, 3f, 10f, 2.6f, 0xFF2A6A2E.toInt())
        blob(-1.6f, 8.6f, -1.8f, 2.6f, 11f, 1.6f, 0xFF3A7A34.toInt())
        tube(1.5f, 7.5f, 0.5f, 1.8f, 3f, 0.8f, 0.04f, 0xFF4E8A3A.toInt())
        tube(-1.8f, 7.6f, -0.4f, -2.0f, 4f, -0.6f, 0.04f, 0xFF4E8A3A.toInt())
    }

    private fun fern() = kind("fern", 0.6f, 1.3f) {
        for (k in 0 until 6) {
            val a = k * 1.05f
            blob(kotlin.math.cos(a) * 0.5f - 0.35f, 0f, kotlin.math.sin(a) * 0.5f - 0.15f, kotlin.math.cos(a) * 0.5f + 0.35f, 0.5f, kotlin.math.sin(a) * 0.5f + 0.15f, 0xFF4E9A3A.toInt())
        }
    }

    private fun saguaro() = kind("saguaro", 0.8f, 1.5f) {
        val c = 0xFF4E8A4A.toInt()
        tube(0f, 0f, 0f, 0f, 3.6f, 0f, 0.28f, c)
        tube(0f, 1.4f, 0f, -0.9f, 1.4f, 0f, 0.2f, c)
        tube(-0.9f, 1.4f, 0f, -0.9f, 2.6f, 0f, 0.2f, c)
        tube(0f, 2.0f, 0f, 0.8f, 2.0f, 0f, 0.2f, c)
        tube(0.8f, 2.0f, 0f, 0.8f, 3.0f, 0f, 0.2f, c)
    }

    private fun mesa() = kind("mesa", 1.0f, 1.8f) {
        val a = 0xFFC4673E.toInt()
        val b = 0xFFD88A58.toInt()
        box(-5f, -1f, -3.5f, 5f, 2f, 3.5f, a)
        box(-4.6f, 2f, -3.2f, 4.6f, 4f, 3.2f, b)
        box(-4.2f, 4f, -2.9f, 4.2f, 6.5f, 2.9f, a)
        box(-4.3f, 6.5f, -3.0f, 4.3f, 6.8f, 3.0f, b)
    }

    private fun tumbleweed() = kind("tumble", 0.6f, 1.0f) {
        ring(0f, 0.6f, 0f, 0.55f, 0.08f, 0xFFB89A6A.toInt())
        blob(-0.45f, 0.15f, -0.45f, 0.45f, 1.05f, 0.45f, 0xFFA8885A.toInt())
    }

    private fun cherry() = kind("cherry", 0.8f, 1.3f) {
        tube(0f, 0f, 0f, 0.2f, 1.8f, 0f, 0.15f, 0xFF4A3430.toInt())
        tube(0.2f, 1.6f, 0f, -0.7f, 2.4f, 0.2f, 0.08f, 0xFF4A3430.toInt())
        blob(-1.4f, 1.8f, -1.1f, 1.2f, 3.4f, 1.2f, 0xFFF4A7C0.toInt())
        blob(-0.6f, 2.6f, -0.9f, 1.4f, 3.8f, 0.8f, 0xFFF8C0D2.toInt())
        blob(-1.6f, 2.2f, -0.2f, -0.2f, 3.2f, 1.0f, 0xFFEE96B4.toInt())
    }

    private fun toriiGate() = kind("torii", 1.0f, 1.0f) {
        val red = 0xFFD8322A.toInt()
        tube(-1.8f, 0f, 0f, -1.8f, 4.2f, 0f, 0.18f, red)
        tube(1.8f, 0f, 0f, 1.8f, 4.2f, 0f, 0.18f, red)
        box(-2.6f, 3.4f, -0.15f, 2.6f, 3.65f, 0.15f, red)
        box(-3.0f, 4.2f, -0.25f, 3.0f, 4.5f, 0.25f, 0xFF2A2226.toInt())
    }

    private fun bamboo() = kind("bamboo", 0.8f, 1.2f) {
        for (k in 0 until 6) {
            val x = (k % 3 - 1) * 0.35f
            val z = (k / 3 - 0.5f) * 0.4f
            tube(x, 0f, z, x + 0.1f * (k - 2), 4.5f + k * 0.3f, z, 0.07f, 0xFF7AAE4A.toInt())
            blob(x - 0.5f, 3.8f + k * 0.3f, z - 0.25f, x + 0.6f, 4.3f + k * 0.3f, z + 0.25f, 0xFF5E9A3E.toInt())
        }
    }

    private fun stoneLantern() = kind("stonelantern", 1.0f, 1.0f) {
        val c = 0xFF9A9890.toInt()
        blob(-0.4f, 0f, -0.4f, 0.4f, 0.3f, 0.4f, c)
        tube(0f, 0.3f, 0f, 0f, 1.1f, 0f, 0.14f, c)
        box(-0.4f, 1.1f, -0.4f, 0.4f, 1.6f, 0.4f, c)
        box(-0.22f, 1.2f, 0.4f, 0.22f, 1.5f, 0.42f, glow(0xFFFFD98A.toInt()))
        gable(-0.6f, 1.6f, -0.6f, 0.6f, 2.0f, 0.6f, 0xFF7A7870.toInt())
    }

    private fun coral(c: Int) = kind("coral$c", 0.7f, 1.6f) {
        tube(0f, 0f, 0f, 0f, 1.6f, 0f, 0.18f, c)
        tube(0f, 0.7f, 0f, -0.7f, 1.6f, 0.2f, 0.13f, c)
        tube(0f, 0.9f, 0f, 0.7f, 1.9f, -0.2f, 0.13f, c)
        tube(-0.7f, 1.6f, 0.2f, -0.9f, 2.2f, 0.1f, 0.1f, c)
        blob(-0.3f, 1.5f, -0.3f, 0.3f, 2.0f, 0.3f, scale(c, 1.15f))
    }

    private fun anemone(c: Int) = kind("anemone$c", 0.6f, 1.3f) {
        blob(-0.6f, -0.1f, -0.6f, 0.6f, 0.7f, 0.6f, scale(c, 0.8f))
        for (k in 0 until 8) {
            val a = k * 0.785f
            tube(kotlin.math.cos(a) * 0.3f, 0.6f, kotlin.math.sin(a) * 0.3f, kotlin.math.cos(a) * 0.6f, 1.3f, kotlin.math.sin(a) * 0.6f, 0.07f, glow(c))
        }
    }

    private fun seaweed() = kind("seaweed", 0.8f, 1.8f) {
        for (k in 0 until 4) {
            val x = (k - 1.5f) * 0.25f
            tube(x, 0f, 0f, x + 0.2f, 1.2f, 0.1f, 0.06f, 0xFF3E9A5A.toInt())
            tube(x + 0.2f, 1.2f, 0.1f, x - 0.1f, 2.6f, 0f, 0.05f, 0xFF4EAA6A.toInt())
        }
    }

    private fun lavaRock() = kind("lavarock", 0.6f, 1.8f) {
        blob(-1f, -0.3f, -0.8f, 0.9f, 1.0f, 0.8f, 0xFF2A2224.toInt())
        blob(-0.3f, 0.6f, 0.5f, 0.2f, 0.9f, 0.85f, glow(0xFFFF6A1A.toInt()))
        blob(0.5f, 0.2f, -0.4f, 0.95f, 0.5f, 0.1f, glow(0xFFFF8A2A.toInt()))
    }

    private fun charredTree() = kind("charred", 0.9f, 1.4f) {
        val c = 0xFF1E1A1C.toInt()
        tube(0f, 0f, 0f, 0.1f, 2.6f, 0f, 0.14f, c)
        tube(0.05f, 1.5f, 0f, 0.9f, 2.4f, 0.2f, 0.07f, c)
        tube(0.08f, 1.9f, 0f, -0.7f, 2.8f, -0.2f, 0.06f, c)
    }

    /** What may stand at height fraction [f] (0 low … 1 high) in [w]; [k] picks among them. */
    private fun pick(w: World, f: Float, k: Float, near: Boolean): Kind? = when (w) {
        World.MEADOW -> when {
            near && k < 0.35f -> flowers(0xFFE05A7A.toInt())
            f > 0.62f -> if (k < 0.3f) rock(0xFF9A948C.toInt()) else null
            f > 0.36f -> if (k < 0.75f) pine(0xFF2E6A3A.toInt(), false) else rock(0xFF8E8A84.toInt())
            k < 0.5f -> broadleaf(0xFF4E9A3E.toInt())
            k < 0.75f -> bush(0xFF5AA848.toInt())
            k < 0.85f -> rock(0xFF8E8A84.toInt())
            else -> flowers(0xFFF0C040.toInt())
        }
        World.COAST -> when {
            f > 0.5f -> if (k < 0.4f) rock(0xFFB59A74.toInt()) else null
            f < 0.2f -> if (k < 0.55f) palm() else bush(0xFF6AA850.toInt())
            k < 0.4f -> palm()
            k < 0.7f -> bush(0xFF6AA850.toInt())
            else -> broadleaf(0xFF5CA448.toInt())
        }
        World.CASTLE -> when {
            f > 0.45f && k < 0.07f -> tower(0xFF6E6878.toInt(), 0xFFB03A3A.toInt())
            k < 0.4f -> deadTree()
            k < 0.7f -> rock(0xFF625C68.toInt())
            else -> pine(0xFF2E3E30.toInt(), false)
        }
        World.CITY -> when {
            near && k < 0.5f -> lamp(0xFFFFE6A0.toInt())
            k < 0.45f -> building(listOf(0xFFD8CFC0.toInt(), 0xFFB8C4D0.toInt(), 0xFFC9A88A.toInt())[(k * 100).toInt() % 3], 2 + (k * 20).toInt() % 5)
            k < 0.8f -> broadleaf(0xFF5CA448.toInt())
            else -> bush(0xFF5AA848.toInt())
        }
        World.NEON -> when {
            near && k < 0.5f -> lamp(0xFF46EAF2.toInt())
            k < 0.55f -> neonTower(0xFFE8389E.toInt(), 0xFF46EAF2.toInt())
            k < 0.75f -> neonTower(0xFF9030F0.toInt(), 0xFFF0E040.toInt())
            else -> crystal(0xFFE8389E.toInt())
        }
        World.ICE -> when {
            k < 0.45f -> iceSpike()
            k < 0.8f -> pine(0xFF2E5A4A.toInt(), true)
            else -> rock(0xFFB0C8D8.toInt())
        }
        World.DESERT -> when {
            f > 0.5f && k < 0.06f -> pyramid()
            f < 0.18f && k < 0.5f -> palm()
            k < 0.4f -> rock(0xFFC89A5E.toInt())
            else -> null
        }
        World.WALL -> when {
            f > 0.35f && k < 0.12f -> watchtower()
            k < 0.06f -> pagoda3()
            near && k < 0.4f -> redLantern()
            f > 0.3f -> if (k < 0.6f) pine(0xFF2E5A3A.toInt(), f > 0.7f) else rock(0xFF8C8478.toInt())
            k < 0.55f -> broadleaf(0xFF4E8A3E.toInt())
            else -> bush(0xFF5A9A48.toInt())
        }
        World.JUNGLE -> when {
            k < 0.07f -> templeTower()
            near && k < 0.4f -> fern()
            k < 0.65f -> broadleaf(0xFF2E7A34.toInt())
            k < 0.85f -> fern()
            else -> palm()
        }
        World.AMAZON -> when {
            k < 0.4f -> giantTree()
            k < 0.7f -> fern()
            k < 0.85f -> palm()
            else -> broadleaf(0xFF1E6A2E.toInt())
        }
        World.CANYON -> when {
            f > 0.3f && k < 0.12f -> mesa()
            k < 0.35f -> saguaro()
            k < 0.5f -> tumbleweed()
            k < 0.75f -> rock(0xFFB5583A.toInt())
            else -> null
        }
        World.JAPAN -> when {
            near && k < 0.1f -> toriiGate()
            near && k < 0.25f -> stoneLantern()
            k < 0.05f -> pagoda3()
            k < 0.5f -> cherry()
            k < 0.7f -> bamboo()
            f > 0.5f -> pine(0xFF2E5A3A.toInt(), f > 0.75f)
            else -> bush(0xFF5A9A48.toInt())
        }
        World.REEF -> when {
            k < 0.3f -> coral(listOf(0xFFFF6F61.toInt(), 0xFFFFB84D.toInt(), 0xFFB565D9.toInt(), 0xFFFF8FB1.toInt())[(k * 40).toInt() % 4])
            k < 0.45f -> anemone(listOf(0xFF4FD1C5.toInt(), 0xFFFF8FB1.toInt())[(k * 70).toInt() % 2])
            k < 0.8f -> seaweed()
            else -> rock(0xFF6A8A8A.toInt())
        }
        World.VOLCANO -> when {
            k < 0.45f -> lavaRock()
            k < 0.65f -> charredTree()
            else -> rock(0xFF2E2628.toInt())
        }
        World.SPACE -> when {
            k < 0.4f -> crystal(0xFF8EF0FF.toInt())
            k < 0.55f -> crystal(0xFFC080FF.toInt())
            k < 0.65f -> antenna()
            else -> rock(0xFF7B748E.toInt())
        }
    }

    /** How many things per spot (0 … 1) a world has. */
    private fun density(w: World) = when (w) {
        World.MEADOW -> 0.55f
        World.COAST -> 0.35f
        World.CASTLE -> 0.4f
        World.CITY -> 0.45f
        World.NEON -> 0.4f
        World.ICE -> 0.3f
        World.SPACE -> 0.22f
        World.DESERT -> 0.18f
        World.WALL -> 0.45f
        World.JUNGLE -> 0.75f
        World.AMAZON -> 0.7f
        World.CANYON -> 0.3f
        World.JAPAN -> 0.5f
        World.REEF -> 0.6f
        World.VOLCANO -> 0.3f
    }

    /** Adds the things standing on rows [z0, z1) of [land] to [out]. */
    private class Spot(val kind: Kind, val x: Float, val y: Float, val z: Float, val yaw: Float, val s: Float)

    private const val STEP = 5
    private const val HALF = WorldLand.HALF_WIDTH - 6

    /** What stands at the grid point ([gx], [gz]), if anything (the same every time it is asked). */
    private fun spotAt(land: WorldLand, gx: Int, gz: Int): Spot? {
        // Where the land is still the last song's, its things are (see [place]); none while it turns.
        if (land.before != null && gz < WorldLand.CONT + WorldLand.CONT_BLEND) return null
        val w = land.worldAt(gx.toFloat(), gz.toFloat())
        val h = land.noise(gx * 1.37f + 0.5f, gz * 1.13f + 0.5f)
        val x = gx + (land.noise(gx * 3.1f, gz * 2.7f) - 0.5f) * STEP
        val z = gz + (land.noise(gx * 2.3f + 9f, gz * 3.3f) - 0.5f) * STEP
        val signed = land.acrossAt(x, z)
        val across = abs(signed)
        // A village place (as O'rama's): houses close by the way on both sides.
        val inVillage = across in 4.5f..22f && Places.villageAt(land.places, z)
        if (h >= (if (inVillage) 0.8f else density(w))) return null
        if (!Places.clearAt(land, land.places, x, z, signed)) return null
        val y = land.heightAt(x, z)
        // (Under the sea things grow on the sea's floor.)
        if (across <= WorldLand.PATH_HALF + 2.5f || (y <= WorldLand.WATER + 0.25f && w != World.REEF)) return null
        val f = (y / WorldLand.MAX_HEIGHT).coerceAtLeast(0f)
        val k = land.noise(gx * 5.7f + 3f, gz * 4.9f + 1f)
        val near = across < WorldLand.PATH_HALF + 6f
        // Villages here and there along the way; a lone house now and then.
        val village = inVillage || (land.noise(z * 0.011f + 50f, 3.3f) > 0.66f && across in 6f..26f && f < 0.45f)
        val lone = k > 0.94f && f < 0.4f && !near
        val kind = (if ((village && k < (if (inVillage) 0.8f else 0.6f)) || lone) houseOf(w, k) ?: pick(w, f, k, near) else pick(w, f, k, near)) ?: return null
        val s = kind.minScale + (kind.maxScale - kind.minScale) * land.noise(gx * 7.1f, gz * 6.3f)
        // Houses turn their door to the way; the rest any way.
        val yaw = if (kind.house) (if (land.centerAt(z) > x) 1.5708f else -1.5708f) + (k - 0.5f) * 0.5f else k * 6.28f
        // Whatever takes some room (houses, pyramids, mesas) stands on the lowest ground under it,
        // never with a corner in the air; a house a little sunk in, as into a slope.
        val r = kind.radius * s * 0.65f
        val foot = if (r > 0.9f) {
            var low = y
            for (k in 0 until 8) {
                val a = k * 0.785f
                low = minOf(low, land.heightAt(x + cos(a) * r, z + sin(a) * r))
            }
            low
        } else y
        return Spot(kind, x, foot - (if (kind.house) 0.3f else 0.1f), z, yaw, s)
    }

    /** [minSize]: things smaller across than this are left out (far chunks drawn with less detail). */
    fun place(land: WorldLand, z0: Int, z1: Int, out: FloatList, dz: Float = 0f, minSize: Float = 0f) {
        // The first rows of a song that goes on from the last one: that one's things, where they were.
        land.before?.let { b ->
            if (z0 < WorldLand.CONT) {
                val from = kotlin.math.floor(z0 + b.atZ).toInt()
                val to = kotlin.math.ceil(minOf(z1.toFloat(), WorldLand.CONT) + b.atZ).toInt()
                place(b.land, from, to, out, dz - b.atZ, minSize)
            }
        }
        var gz = kotlin.math.floor(z0.toFloat() / STEP).toInt() * STEP
        while (gz < z1) {
            var gx = -HALF
            while (gx <= HALF) {
                spotAt(land, gx, gz)?.let { sp ->
                    if (sp.z >= z0 && sp.z < z1 && sp.kind.radius * sp.s >= minSize) bake(sp.kind.mesh, sp.x, sp.y, sp.z + dz, sp.yaw, sp.s, out, sp.kind.sways)
                }
                gx += STEP
            }
            gz += STEP
        }
    }

    /**
     * The top of whatever stands within [margin] of ([x], [z]) (a house, a tree, a tower), or
     * minus infinity: the camera rises over it rather than going through it.
     */
    fun topNear(land: WorldLand, x: Float, z: Float, margin: Float = 1.2f): Float {
        var top = Float.NEGATIVE_INFINITY
        land.before?.let { b -> if (z < WorldLand.CONT + 14f) top = topNear(b.land, x, z + b.atZ, margin) }
        val reach = 14f
        var gz = kotlin.math.floor((z - reach) / STEP).toInt() * STEP
        while (gz <= z + reach) {
            var gx = -HALF + kotlin.math.floor((x - reach + HALF) / STEP).toInt().coerceAtLeast(0) * STEP
            while (gx <= HALF && gx <= x + reach) {
                spotAt(land, gx, gz)?.let { sp ->
                    if (kotlin.math.hypot(x - sp.x, z - sp.z) < sp.kind.radius * sp.s + margin) top = max(top, sp.y + sp.kind.height * sp.s)
                }
                gx += STEP
            }
            gz += STEP
        }
        return top
    }

    /** The great pyramid the desert's peak raises beside the way (see the renderer). */
    val peakPyramid: Rig by lazy {
        val b = RigBuilder()
        b.bone(-1, 0f, 0f, 0f) {
            val c = 0xFFE8C27E.toInt()
            val d = 0xFFC79B58.toInt()
            val s = 6f
            val t = 7f
            tri(-s, 0f, s, s, 0f, s, 0f, t, 0f, c)
            tri(s, 0f, s, s, 0f, -s, 0f, t, 0f, d)
            tri(s, 0f, -s, -s, 0f, -s, 0f, t, 0f, c)
            tri(-s, 0f, -s, -s, 0f, s, 0f, t, 0f, d)
            // A golden capstone that glows.
            tri(-0.9f, t - 1f, 0.9f, 0.9f, t - 1f, 0.9f, 0f, t + 0.1f, 0f, glow(0xFFFFE08A.toInt()))
            tri(0.9f, t - 1f, 0.9f, 0.9f, t - 1f, -0.9f, 0f, t + 0.1f, 0f, glow(0xFFFFD060.toInt()))
            tri(0.9f, t - 1f, -0.9f, -0.9f, t - 1f, -0.9f, 0f, t + 0.1f, 0f, glow(0xFFFFE08A.toInt()))
            tri(-0.9f, t - 1f, -0.9f, -0.9f, t - 1f, 0.9f, 0f, t + 0.1f, 0f, glow(0xFFFFD060.toInt()))
        }
        b.rig()
    }

    /** Copies [m] into [out] turned [yaw], scaled [s] and moved to (x, y, z) (for the places' parts). */
    internal fun bakeMesh(m: Mesh, x: Float, y: Float, z: Float, yaw: Float, s: Float, out: FloatList) = bake(m, x, y, z, yaw, s, out)

    /** [c] darker or lighter by [k], keeping its glow. */
    internal fun shadeOf(c: Int, k: Float) = shade(c, k)

    /**
     * The chimneys smoking in rows [z0, z1) (some houses' fires are lit: more in the cold), as
     * x, y, z of each chimney's top into [out].
     */
    fun chimneys(land: WorldLand, z0: Float, z1: Float, out: MutableList<Float>) {
        var gz = kotlin.math.floor(z0 / STEP).toInt() * STEP
        while (gz < z1) {
            var gx = -HALF
            while (gx <= HALF) {
                spotAt(land, gx, gz)?.let { sp ->
                    if (sp.kind.house && sp.z >= z0 && sp.z < z1) {
                        val cold = land.worldAt(sp.x, sp.z).let { it == World.ICE || it == World.CASTLE || it == World.WALL }
                        if (land.noise(sp.x * 0.7f + 13f, sp.z * 0.7f) < (if (cold) 0.75f else 0.5f)) {
                            val c = cos(sp.yaw)
                            val sn = sin(sp.yaw)
                            val px = 0.8f
                            val pz = -0.8f
                            out += sp.x + (px * c + pz * sn) * sp.s
                            out += sp.y + 3.75f * sp.s
                            out += sp.z + (-px * sn + pz * c) * sp.s
                        }
                    }
                }
                gx += STEP
            }
            gz += STEP
        }
    }

    /** Something big standing ahead of row [tz] (a pyramid, a temple, a tower…) to turn to as it is passed: its middle x, y, z. */
    fun sight(land: WorldLand, tz: Float): FloatArray? {
        var gz = kotlin.math.floor((tz + 4f) / STEP).toInt() * STEP
        while (gz < tz + 34f) {
            var gx = -HALF
            while (gx <= HALF) {
                spotAt(land, gx, gz)?.let { sp ->
                    val tall = sp.kind.height * sp.s
                    val across = abs(land.acrossAt(sp.x, sp.z))
                    if ((tall > 5.5f || sp.kind.radius * sp.s > 4f) && across in 7f..42f) return floatArrayOf(sp.x, sp.y + tall * 0.5f, sp.z)
                }
                gx += STEP
            }
            gz += STEP
        }
        return null
    }

    /** Copies [m] into [out] turned [yaw], scaled [s] and moved to (x, y, z); glowing colours marked (+2). */
    private fun bake(m: Mesh, x: Float, y: Float, z: Float, yaw: Float, s: Float, out: FloatList, sways: Boolean = false) {
        val c = cos(yaw)
        val sn = sin(yaw)
        val d = m.data
        var i = 0
        while (i < d.size) {
            val px = d[i]; val py = d[i + 1]; val pz = d[i + 2]
            out.add(x + (px * c + pz * sn) * s); out.add(y + py * s); out.add(z + (-px * sn + pz * c) * s)
            out.add(d[i + 3]); out.add(d[i + 4]); out.add(d[i + 5])
            val nx = d[i + 6]; val nz = d[i + 8]
            // Swaying things: the normal's length tells how high up the plant the point is.
            val k = if (sways) {
                val l = kotlin.math.sqrt(nx * nx + d[i + 7] * d[i + 7] + nz * nz).coerceAtLeast(1e-4f)
                (0.5f - 0.3f * (py * s / 7f).coerceIn(0f, 1f)) / l
            } else 1f
            out.add((nx * c + nz * sn) * k); out.add(d[i + 7] * k); out.add((-nx * sn + nz * c) * k)
            i += Mesh.STRIDE
        }
    }

    private fun shade(c: Int, k: Float) = (scale(c, k) and 0xFFFFFF) or (c and 0xFF000000.toInt())
}
