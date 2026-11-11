package com.sadurni.attacca.world

import com.sadurni.attacca.diorama.Dog

/** How one of the travellers looks: they change now and then (relays), each its own. */
class Person(val coat: Int, val trousers: Int, val skin: Int, val hair: Int, val hat: Int?, val longHair: Boolean, val pack: Int) {
    companion object {
        private val SKINS = intArrayOf(0xFFF1C9A5.toInt(), 0xFFE0AC86.toInt(), 0xFFC68A62.toInt(), 0xFF9C6644.toInt(), 0xFF6E4630.toInt())
        private val HAIRS = intArrayOf(0xFF2B1D16.toInt(), 0xFF4A3020.toInt(), 0xFF8A5A30.toInt(), 0xFFD8B070.toInt(), 0xFF1A1A1E.toInt(), 0xFFB04A28.toInt())
        private val TROUSERS = intArrayOf(0xFF2F3440.toInt(), 0xFF3B4A6B.toInt(), 0xFF5A4A3A.toInt(), 0xFF4A5A3A.toInt(), 0xFF2A2A2E.toInt())
        private val COATS = intArrayOf(0xFFD8452E.toInt(), 0xFF2E7DD8.toInt(), 0xFFE8B030.toInt(), 0xFF3AA860.toInt(), 0xFF8E44AD.toInt(), 0xFFEE7A30.toInt(), 0xFF1F9AA8.toInt())
        private val HATS = intArrayOf(0xFF6B4A2E.toInt(), 0xFF2E2E36.toInt(), 0xFFE8E0D0.toInt(), 0xFFB03A3A.toInt())

        /** The [k]th traveller of a song (k = 0 the first): the first in the song's colour, the others in their own. */
        fun of(seed: Int, k: Int, accent: Int): Person {
            fun pick(n: Int, salt: Int) = Math.floorMod((seed * 31 + k * 977 + salt) * 1103515245 + 12345 ushr 7, n)
            val coat = if (k == 0) accent or (0xFF shl 24) else COATS[pick(COATS.size, 1)]
            val hat = if (pick(3, 2) == 0) HATS[pick(HATS.size, 3)] else null
            return Person(coat, TROUSERS[pick(TROUSERS.size, 4)], SKINS[pick(SKINS.size, 5)], HAIRS[pick(HAIRS.size, 6)], hat, pick(3, 7) == 0, 0xFF6B4A2E.toInt())
        }
    }
}

/** The figures' bones, by name (indices into their rigs). */
object Human {
    const val BODY = 0
    const val THIGH_L = 1
    const val SHIN_L = 2
    const val THIGH_R = 3
    const val SHIN_R = 4
    const val ARM_L = 5
    const val FOREARM_L = 6
    const val ARM_R = 7
    const val FOREARM_R = 8

    /** Height of the hips (where the figure sits on a seat). */
    const val HIP = 0.92f

    fun rig(p: Person, helmet: Boolean = false): Rig {
        val r = RigBuilder()
        r.bone(-1, 0f, HIP, 0f) {
            // Body, head and hair, maybe a hat; a pack on the back.
            blob(-0.25f, 0.86f, -0.15f, 0.25f, 1.46f, 0.15f, p.coat)
            blob(-0.1f, 1.4f, -0.08f, 0.1f, 1.52f, 0.08f, p.skin)
            blob(-0.16f, 1.48f, -0.16f, 0.16f, 1.8f, 0.17f, p.skin)
            blob(-0.17f, 1.62f, -0.19f, 0.17f, 1.84f, 0.13f, p.hair)
            if (p.longHair) blob(-0.15f, 1.38f, -0.2f, 0.15f, 1.72f, -0.06f, p.hair)
            blob(-0.035f, 1.66f, 0.14f, 0.035f, 1.72f, 0.18f, 0xFF201818.toInt())
            p.hat?.let { blob(-0.2f, 1.76f, -0.2f, 0.2f, 1.9f, 0.2f, it); blob(-0.28f, 1.75f, -0.28f, 0.28f, 1.79f, 0.28f, it) }
            blob(-0.18f, 0.98f, -0.3f, 0.18f, 1.36f, -0.12f, p.pack)
        }
        for (side in intArrayOf(-1, 1)) {
            val x = side * 0.12f
            val thigh = r.bone(Human.BODY, x, 0.9f, 0f) { tube(x, 0.9f, 0f, x, 0.52f, 0f, 0.1f, p.trousers) }
            r.bone(thigh, x, 0.5f, 0f) {
                tube(x, 0.5f, 0f, x, 0.1f, 0f, 0.085f, p.trousers)
                blob(x - 0.08f, 0f, -0.08f, x + 0.08f, 0.1f, 0.16f, 0xFF2A2020.toInt())
            }
        }
        for (side in intArrayOf(-1, 1)) {
            val x = side * 0.31f
            val arm = r.bone(Human.BODY, x, 1.4f, 0f) { tube(x, 1.4f, 0f, x + side * 0.02f, 1.13f, 0f, 0.075f, p.coat) }
            r.bone(arm, x + side * 0.02f, 1.13f, 0f) {
                tube(x + side * 0.02f, 1.13f, 0f, x + side * 0.02f, 0.9f, 0f, 0.065f, p.coat)
                blob(x + side * 0.02f - 0.06f, 0.8f, -0.05f, x + side * 0.02f + 0.06f, 0.92f, 0.06f, p.skin)
            }
        }
        if (helmet) {
            // A diving helmet under the sea: a brass collar and a glass bubble round the head.
            r.bone(Human.BODY, 0f, HIP, 0f) { blob(-0.26f, 1.36f, -0.24f, 0.26f, 1.5f, 0.24f, 0xFFC8A040.toInt()) }
            r.bone(Human.BODY, 0f, HIP, 0f, alpha = 0.3f) { blob(-0.3f, 1.4f, -0.3f, 0.3f, 2.02f, 0.3f, 0xFFCFF4FF.toInt(), 16, 10) }
        }
        return r.rig()
    }
}

/** A four-legged figure's bones: body (with head) 0, then each leg's upper and lower part (front left, front right, hind left, hind right), then the tail. */
object Quad {
    const val BODY = 0
    fun upper(leg: Int) = 1 + leg * 2
    fun lower(leg: Int) = 2 + leg * 2
    const val TAIL = 9

    /** Height of the back (where a dog sits up, or a rider sits on a horse). */
    class Shape(val h: Float, val l: Float, val t: Float, val coat: Int, val light: Int, val nose: Int, val legs: Int, val ears: Float, val tail: Int)

    fun dog(dog: Dog, helmet: Boolean = false): Pair<Rig, Shape> {
        val k = when (dog) {
            Dog.GOLDIE -> Shape(0.5f, 0.82f, 0.34f, 0xFFD6A050.toInt(), 0xFFF0CD8C.toInt(), 0xFFE58F98.toInt(), 0xFFD6A050.toInt(), 0.12f, 0xFFE0B060.toInt())
            Dog.MALAMUTE -> Shape(0.72f, 1.1f, 0.44f, 0xFF2F2D31.toInt(), 0xFFE9DCC4.toInt(), 0xFF1A1818.toInt(), 0xFFE9DCC4.toInt(), 0.13f, 0xFFE9DCC4.toInt())
            Dog.GALGO -> Shape(0.84f, 1.14f, 0.3f, 0xFFC98E52.toInt(), 0xFFF3E8DA.toInt(), 0xFFE0939A.toInt(), 0xFFC98E52.toInt(), 0.19f, 0xFFC98E52.toInt())
            Dog.BRACO -> Shape(0.5f, 0.8f, 0.28f, 0xFFF4F2EE.toInt(), 0xFF1E1E22.toInt(), 0xFF2A2224.toInt(), 0xFFF4F2EE.toInt(), 0.13f, 0xFFF4F2EE.toInt())
        }
        return quad(k, dog, helmet) to k
    }

    fun horse(): Pair<Rig, Shape> {
        val k = Shape(1.45f, 2.0f, 0.62f, 0xFF7A4A2A.toInt(), 0xFF3A2418.toInt(), 0xFF2A1A12.toInt(), 0xFF5A3420.toInt(), 0.16f, 0xFF2A1A12.toInt())
        return quad(k, null) to k
    }

    /** A dog that is not one of the four (in its own coat), for those met along the way. */
    fun strayDog(coat: Int, light: Int, size: Float): Pair<Rig, Shape> {
        val k = Shape(0.6f * size, 0.95f * size, 0.34f * size, coat, light, 0xFF2A2224.toInt(), coat, 0.13f * size, coat)
        return quad(k, null, horseShape = false) to k
    }

    private fun quad(k: Shape, dog: Dog?, helmet: Boolean = false, horseShape: Boolean = dog == null): Rig {
        val r = RigBuilder()
        val legH = k.h * 0.52f
        val half = k.t / 2
        val horse = horseShape
        val head = if (dog == Dog.BRACO) k.light else k.coat
        r.bone(-1, 0f, legH, 0f) {
            blob(-half, legH - 0.04f, -k.l / 2, half, k.h, k.l / 2, k.coat)
            when (dog) {
                Dog.MALAMUTE -> blob(-half * 0.92f, legH - 0.06f, -k.l / 2 + 0.06f, half * 0.92f, legH + k.h * 0.18f, k.l / 2 - 0.04f, k.light)
                Dog.GALGO, Dog.GOLDIE -> blob(-half * 0.7f, legH, k.l / 2 - 0.28f, half * 0.7f, k.h - 0.08f, k.l / 2 + 0.03f, k.light)
                Dog.BRACO -> {
                    blob(-half - 0.01f, k.h - 0.2f, -0.12f, half + 0.01f, k.h + 0.01f, 0.14f, k.light)
                    blob(-half - 0.01f, k.h - 0.16f, -k.l / 2 + 0.06f, half * 0.4f, k.h + 0.01f, -k.l / 2 + 0.26f, k.light)
                }
                null -> {}
            }
            // Neck and head, snout, ears.
            val hz = k.l / 2
            val neckTop = if (horse) k.h + 0.55f else k.h + 0.12f
            val headZ = if (horse) hz + 0.25f else hz + 0.05f
            tube(0f, k.h - 0.1f, hz - 0.1f, 0f, neckTop, headZ, if (horse) 0.17f else half * 0.55f, k.coat)
            if (horse) {
                tube(0f, k.h - 0.05f, hz - 0.35f, 0f, neckTop + 0.08f, headZ - 0.05f, 0.07f, k.light) // mane
                blob(-0.15f, neckTop - 0.15f, headZ - 0.15f, 0.15f, neckTop + 0.15f, headZ + 0.2f, k.coat)
                blob(-0.11f, neckTop - 0.32f, headZ + 0.1f, 0.11f, neckTop - 0.02f, headZ + 0.62f, k.coat)
                blob(-0.06f, neckTop - 0.3f, headZ + 0.55f, 0.06f, neckTop - 0.18f, headZ + 0.66f, k.nose)
                for (s in intArrayOf(-1, 1)) blob(s * 0.1f - 0.03f, neckTop + 0.08f, headZ - 0.05f, s * 0.1f + 0.03f, neckTop + 0.3f, headZ + 0.03f, k.coat)
                // Saddle.
                blob(-half - 0.03f, k.h - 0.08f, -0.35f, half + 0.03f, k.h + 0.1f, 0.25f, 0xFF4A2A18.toInt())
            } else {
                blob(-half * 0.85f, neckTop - 0.12f, headZ - 0.06f, half * 0.85f, neckTop + 0.18f, headZ + 0.24f, head)
                blob(-half * 0.45f, neckTop - 0.1f, headZ + 0.18f, half * 0.45f, neckTop + 0.04f, headZ + 0.4f, head)
                blob(-0.045f, neckTop - 0.03f, headZ + 0.38f, 0.045f, neckTop + 0.04f, headZ + 0.44f, k.nose)
                for (s in intArrayOf(-1, 1)) {
                    blob(s * half * 0.6f - 0.05f, neckTop + 0.1f, headZ, s * half * 0.6f + 0.05f, neckTop + 0.14f + k.ears, headZ + 0.08f,
                        if (dog == Dog.BRACO) k.light else k.coat)
                }
            }
        }
        val fz = k.l / 2 - (if (horse) 0.22f else 0.12f)
        val bz = -k.l / 2 + (if (horse) 0.22f else 0.12f)
        val legR = if (horse) 0.08f else 0.055f
        for ((leg, z) in listOf(0 to fz, 1 to fz, 2 to bz, 3 to bz)) {
            val x = if (leg % 2 == 0) -half + legR * 1.2f else half - legR * 1.2f
            val upper = r.bone(Quad.BODY, x, legH + 0.04f, z) { tube(x, legH + 0.04f, z, x, legH * 0.5f, z, legR * 1.15f, k.legs) }
            r.bone(upper, x, legH * 0.5f, z) {
                tube(x, legH * 0.5f, z, x, 0.05f, z, legR, k.legs)
                blob(x - legR * 1.2f, 0f, z - legR, x + legR * 1.2f, legR * 1.6f, z + legR * 2.2f, if (horse) 0xFF2A1A12.toInt() else k.legs)
            }
        }
        r.bone(Quad.BODY, 0f, k.h - 0.06f, -k.l / 2) {
            val tz = -k.l / 2
            when {
                horse -> tube(0f, k.h - 0.06f, tz, 0f, k.h - 0.85f, tz - 0.3f, 0.09f, k.tail)
                dog == Dog.MALAMUTE -> blob(-0.09f, k.h - 0.05f, tz - 0.14f, 0.09f, k.h + 0.3f, tz + 0.08f, k.tail)
                dog == Dog.GALGO -> tube(0f, k.h - 0.06f, tz, 0f, k.h - 0.12f, tz - 0.14f, 0.04f, k.tail)
                else -> tube(0f, k.h - 0.06f, tz, 0f, k.h - 0.18f, tz - 0.3f, 0.05f, k.tail)
            }
        }
        if (helmet) {
            // The dogs' diving helmets: a collar and a glass bubble round the head.
            val hz = k.l / 2 + 0.05f
            val hy = k.h + 0.12f
            val rr = maxOf(k.t * 0.75f, 0.3f)
            r.bone(Quad.BODY, 0f, legH, 0f) { blob(-rr * 0.8f, hy - rr * 0.95f, hz - rr * 0.5f, rr * 0.8f, hy - rr * 0.6f, hz + rr * 0.3f, 0xFFC8A040.toInt()) }
            r.bone(Quad.BODY, 0f, legH, 0f, alpha = 0.3f) { blob(-rr, hy - rr, hz - rr * 0.6f, rr, hy + rr, hz + rr * 1.3f, 0xFFCFF4FF.toInt(), 16, 10) }
        }
        return r.rig()
    }
}

/** Mounts with the horse's bones (body, four legs in two parts, tail): the camel and the elephant. */
object Mounts {
    /** A camel: long legs, a hump, a long neck curving up; the rider sits on the hump. */
    fun camel(): Pair<Rig, Quad.Shape> {
        val coat = 0xFFC8A06A.toInt()
        val dark = 0xFF9A7448.toInt()
        val k = Quad.Shape(1.75f, 1.9f, 0.62f, coat, dark, 0xFF3A2A20.toInt(), coat, 0.1f, dark)
        val r = RigBuilder()
        val legH = 1.15f
        r.bone(-1, 0f, legH, 0f) {
            blob(-0.36f, legH - 0.05f, -0.9f, 0.36f, 1.75f, 0.9f, coat)
            blob(-0.3f, 1.55f, -0.45f, 0.3f, 2.25f, 0.35f, coat) // the hump
            blob(-0.36f, 2.05f, -0.42f, 0.36f, 2.15f, 0.32f, 0xFFB03A2E.toInt()) // a saddle cloth
            tube(0f, 1.55f, 0.8f, 0f, 1.45f, 1.35f, 0.17f, coat)
            tube(0f, 1.45f, 1.35f, 0f, 2.3f, 1.55f, 0.15f, coat)
            blob(-0.15f, 2.2f, 1.4f, 0.15f, 2.5f, 1.95f, coat)
            blob(-0.04f, 2.3f, 1.92f, 0.04f, 2.36f, 1.98f, 0xFF3A2A20.toInt())
            for (s in intArrayOf(-1, 1)) blob(s * 0.12f - 0.03f, 2.45f, 1.45f, s * 0.12f + 0.03f, 2.58f, 1.52f, dark)
        }
        legs(r, legH, 0.22f, 0.7f, -0.7f, 0.075f, coat, dark)
        r.bone(Quad.BODY, 0f, 1.6f, -0.9f) { tube(0f, 1.6f, -0.9f, 0f, 1.05f, -1.05f, 0.05f, dark) }
        return r.rig() to k
    }

    /** An elephant: a big round body, thick legs, the trunk swinging, a bright cloth on its back. */
    fun elephant(accent: Int): Pair<Rig, Quad.Shape> {
        val skin = 0xFF8A8A94.toInt()
        val dark = 0xFF6E6E78.toInt()
        val k = Quad.Shape(2.4f, 2.4f, 1.3f, skin, dark, dark, skin, 0.2f, dark)
        val r = RigBuilder()
        val legH = 1.1f
        r.bone(-1, 0f, legH, 0f) {
            blob(-0.75f, legH - 0.1f, -1.15f, 0.75f, 2.5f, 1.05f, skin)
            blob(-0.6f, 1.75f, 0.85f, 0.6f, 2.75f, 1.65f, skin) // head
            for (s in intArrayOf(-1, 1)) blob(s * 0.55f - 0.12f, 1.65f, 0.95f, s * 0.55f + 0.12f, 2.65f, 1.35f, dark) // ears
            tube(0f, 1.95f, 1.6f, 0f, 1.3f, 1.95f, 0.17f, skin)
            tube(0f, 1.3f, 1.95f, 0f, 0.55f, 2.05f, 0.12f, skin)
            for (s in intArrayOf(-1, 1)) tube(s * 0.25f, 1.85f, 1.55f, s * 0.3f, 1.55f, 2.0f, 0.06f, 0xFFF4EEDC.toInt()) // tusks
            blob(-0.8f, 2.35f, -0.6f, 0.8f, 2.6f, 0.55f, accent or (0xFF shl 24)) // the cloth
            blob(-0.5f, 2.55f, -0.35f, 0.5f, 2.75f, 0.3f, 0xFFF5C542.toInt()) // the seat
        }
        legs(r, legH, 0.55f, 0.8f, -0.8f, 0.24f, skin, dark)
        r.bone(Quad.BODY, 0f, 2.0f, -1.15f) { tube(0f, 2.0f, -1.15f, 0f, 1.2f, -1.35f, 0.05f, dark) }
        return r.rig() to k
    }

    private fun legs(r: RigBuilder, legH: Float, half: Float, fz: Float, bz: Float, rad: Float, c: Int, foot: Int) {
        for ((leg, z) in listOf(0 to fz, 1 to fz, 2 to bz, 3 to bz)) {
            val x = if (leg % 2 == 0) -half + rad else half - rad
            val upper = r.bone(Quad.BODY, x, legH, z) { tube(x, legH, z, x, legH * 0.5f, z, rad * 1.1f, c) }
            r.bone(upper, x, legH * 0.5f, z) {
                tube(x, legH * 0.5f, z, x, 0.06f, z, rad, c)
                blob(x - rad * 1.2f, 0f, z - rad * 1.2f, x + rad * 1.2f, rad * 0.9f, z + rad * 1.4f, foot)
            }
        }
    }
}

/** Vehicles: body 0, then their moving parts (wheels…). Seats in their own space. */
object Vehicles {
    class Built(val rig: Rig, val seatY: Float, val seatZ: Float, val wheels: List<Int>, val dogSpots: List<FloatArray>)

    fun bike(colour: Int): Built {
        val r = RigBuilder()
        val metal = 0xFF2A2A30.toInt()
        val rr = 0.36f
        r.bone(-1, 0f, rr, 0f) {
            tube(0f, rr, -0.55f, 0f, 0.95f, -0.15f, 0.04f, colour)   // seat tube
            tube(0f, 0.95f, -0.15f, 0f, 1.0f, 0.42f, 0.04f, colour)  // top tube
            tube(0f, rr, -0.1f, 0f, 1.0f, 0.42f, 0.045f, colour)     // down tube
            tube(0f, rr, -0.1f, 0f, rr, -0.55f, 0.035f, colour)      // chain stay
            tube(0f, 1.0f, 0.42f, 0f, rr, 0.6f, 0.035f, metal)        // fork
            tube(-0.3f, 1.12f, 0.4f, 0.3f, 1.12f, 0.4f, 0.03f, metal) // bars
            blob(-0.08f, 0.96f, -0.28f, 0.08f, 1.04f, -0.06f, 0xFF1E1E22.toInt()) // saddle
        }
        val wheels = ArrayList<Int>()
        for (z in floatArrayOf(-0.55f, 0.6f)) {
            wheels += r.bone(0, 0f, rr, z) {
                ring(0f, rr, z, rr, 0.045f, 0xFF1E1E22.toInt())
                tube(0f, rr - rr * 0.9f, z, 0f, rr + rr * 0.9f, z, 0.015f, 0xFFB8B8C0.toInt())
                tube(0f, rr, z - rr * 0.9f, 0f, rr, z + rr * 0.9f, 0.015f, 0xFFB8B8C0.toInt())
            }
        }
        return Built(r.rig(), 1.0f - Human.HIP + 0.02f, -0.15f, wheels, emptyList())
    }

    fun car(colour: Int): Built {
        val r = RigBuilder()
        val wr = 0.32f
        r.bone(-1, 0f, 0.5f, 0f) {
            blob(-0.8f, 0.28f, -1.3f, 0.8f, 0.86f, 1.3f, colour)
            blob(-0.72f, 0.5f, 0.55f, 0.72f, 0.92f, 1.35f, colour)
            blob(-0.6f, 0.62f, -0.75f, 0.6f, 0.82f, 0.4f, 0xFF3A3236.toInt())  // seats, open top
            blob(-0.66f, 0.78f, 0.3f, 0.66f, 1.08f, 0.42f, 0xFF9CC8E8.toInt())  // windscreen
            blob(-0.5f, 0.4f, 1.28f, -0.3f, 0.56f, 1.36f, 0xFFFFF4C8.toInt())   // lights
            blob(0.3f, 0.4f, 1.28f, 0.5f, 0.56f, 1.36f, 0xFFFFF4C8.toInt())
        }
        val wheels = ArrayList<Int>()
        for (x in floatArrayOf(-0.78f, 0.78f)) for (z in floatArrayOf(-0.85f, 0.85f)) {
            wheels += r.bone(0, x, wr, z) {
                blob(x - 0.13f, 0f, z - wr, x + 0.13f, wr * 2, z + wr, 0xFF1E1E22.toInt())
                blob(x - 0.15f, wr - 0.1f, z - 0.05f, x + 0.15f, wr + 0.14f, z + 0.18f, 0xFFC0C0C8.toInt())
            }
        }
        return Built(r.rig(), 0.66f - Human.HIP + 0.1f, -0.15f, wheels,
            listOf(floatArrayOf(-0.35f, 0.62f, -0.75f), floatArrayOf(0.35f, 0.62f, -0.75f), floatArrayOf(-0.3f, 0.62f, 0.05f), floatArrayOf(0.4f, 0.62f, 0.05f)))
    }

    fun ship(colour: Int): Built {
        val r = RigBuilder()
        r.bone(-1, 0f, 0.6f, 0f) {
            blob(-0.95f, 0.15f, -1.7f, 0.95f, 0.9f, 1.8f, 0xFFD8DCE4.toInt())
            blob(-0.6f, 0.65f, 1.0f, 0.6f, 0.95f, 2.2f, colour)
            blob(-2.2f, 0.35f, -0.9f, -0.6f, 0.55f, 0.6f, colour)
            blob(0.6f, 0.35f, -0.9f, 2.2f, 0.55f, 0.6f, colour)
            blob(-0.75f, 0.7f, -1.2f, 0.75f, 0.96f, 0.8f, 0xFF3A3A4A.toInt())   // open deck
            blob(-0.45f, 0.25f, -2.0f, 0.45f, 0.7f, -1.5f, 0xFF8EF0FF.toInt())  // engine glow
        }
        return Built(r.rig(), 0.95f - Human.HIP + 0.05f, 0.5f, emptyList(),
            listOf(floatArrayOf(-0.4f, 0.95f, -0.2f), floatArrayOf(0.4f, 0.95f, -0.25f), floatArrayOf(-0.35f, 0.95f, -0.8f), floatArrayOf(0.35f, 0.95f, -0.85f)))
    }

    /** A canoe of wood: the paddler sits in the middle, the dogs fore and aft. */
    fun canoe(colour: Int): Built {
        val r = RigBuilder()
        val wood = 0xFF8A5A34.toInt()
        r.bone(-1, 0f, 0.2f, 0f) {
            blob(-0.55f, -0.2f, -2.4f, 0.55f, 0.42f, 2.4f, wood)
            blob(-0.45f, 0.15f, -2.2f, 0.45f, 0.4f, 2.2f, 0xFF4A3020.toInt())
            blob(-0.58f, 0.3f, -0.6f, 0.58f, 0.42f, 0.6f, colour)
        }
        return Built(r.rig(), 0.25f - Human.HIP, 0f, emptyList(),
            listOf(floatArrayOf(0f, 0.25f, 1.1f), floatArrayOf(0f, 0.25f, -1.1f), floatArrayOf(0f, 0.25f, 1.8f), floatArrayOf(0f, 0.25f, -1.8f)))
    }

    /** A small yellow submarine with a round window and an open hatch up top, its propeller turning. */
    fun submarine(colour: Int): Built {
        val r = RigBuilder()
        val hull = 0xFFF5C542.toInt()
        r.bone(-1, 0f, 0.8f, 0f) {
            blob(-0.95f, 0.1f, -2.0f, 0.95f, 1.6f, 2.1f, hull)
            blob(-0.6f, 1.2f, -0.9f, 0.6f, 2.0f, 0.7f, hull) // the tower, open
            blob(-0.5f, 1.85f, -0.75f, 0.5f, 2.0f, 0.55f, 0xFF3A3A44.toInt())
            // Round windows on both sides (the dogs look out of them from inside).
            for (z in floatArrayOf(-0.9f, 0.1f, 1.1f)) for (s in floatArrayOf(-1f, 1f)) {
                blob(if (s > 0) 0.88f else -1.0f, 0.75f, z - 0.25f, if (s > 0) 1.0f else -0.88f, 1.25f, z + 0.25f, 0xFF7A6A40.toInt())
                blob(if (s > 0) 0.94f else -1.02f, 0.82f, z - 0.17f, if (s > 0) 1.02f else -0.94f, 1.18f, z + 0.17f, Props.glow(0xFF2E6A88.toInt()))
            }
            tube(0.3f, 2.0f, 0.4f, 0.3f, 2.7f, 0.4f, 0.05f, 0xFF7A6A40.toInt()) // periscope
            tube(0.3f, 2.7f, 0.4f, 0.3f, 2.7f, 0.7f, 0.05f, 0xFF7A6A40.toInt())
            blob(-0.08f, 0.6f, -2.3f, 0.08f, 1.1f, -1.9f, 0xFF7A6A40.toInt())
        }
        val prop = r.bone(0, 0f, 0.85f, -2.25f) {
            blob(-0.5f, 0.75f, -2.3f, 0.5f, 0.95f, -2.2f, 0xFF7A6A40.toInt())
            blob(-0.1f, 0.35f, -2.3f, 0.1f, 1.35f, -2.2f, 0xFF7A6A40.toInt())
        }
        return Built(r.rig(), 1.9f - Human.HIP, -0.1f, listOf(prop),
            listOf(floatArrayOf(-0.3f, 1.9f, 0.3f), floatArrayOf(0.3f, 1.9f, 0.3f), floatArrayOf(-0.3f, 1.9f, -0.5f), floatArrayOf(0.3f, 1.9f, -0.5f)))
    }

    fun iceberg(): Built {
        val r = RigBuilder()
        r.bone(-1, 0f, 0f, 0f) {
            blob(-1.6f, -0.6f, -2.2f, 1.6f, 0.45f, 2.2f, 0xFFEFF7FC.toInt())
            blob(-1.2f, -0.2f, -1.4f, 0.4f, 0.75f, 0.2f, 0xFFDDEEF8.toInt())
            blob(0.3f, -0.3f, 0.4f, 1.4f, 0.6f, 1.8f, 0xFFE6F2FA.toInt())
            blob(-1.0f, -0.7f, 1.0f, 0.2f, 0.3f, 2.4f, 0xFFCFE6F4.toInt())
        }
        return Built(r.rig(), 0.45f, 0.4f, emptyList(),
            listOf(floatArrayOf(-0.7f, 0.42f, 1.1f), floatArrayOf(0.9f, 0.5f, 1.2f), floatArrayOf(-0.9f, 0.5f, -0.4f), floatArrayOf(0.6f, 0.45f, -1.4f)))
    }
}

/** What may lie across the way to be jumped. */
object Obstacles {
    fun log(): Mesh = FloatList().apply {
        tube(-1.3f, 0.22f, 0f, 1.3f, 0.22f, 0f, 0.22f, 0xFF7A5232.toInt())
        blob(-1.36f, 0.04f, -0.18f, -1.22f, 0.4f, 0.18f, 0xFFC9A06A.toInt())
        blob(1.22f, 0.04f, -0.18f, 1.36f, 0.4f, 0.18f, 0xFFC9A06A.toInt())
    }.mesh()

    fun rocks(): Mesh = FloatList().apply {
        blob(-0.9f, -0.1f, -0.4f, 0.1f, 0.62f, 0.4f, 0xFF8E8A84.toInt())
        blob(-0.1f, -0.1f, -0.35f, 0.8f, 0.48f, 0.35f, 0xFF9E9A92.toInt())
        blob(-1.3f, -0.1f, -0.2f, -0.7f, 0.3f, 0.25f, 0xFF7E7A74.toInt())
    }.mesh()

    fun cones(): Mesh = FloatList().apply {
        for (x in floatArrayOf(-0.8f, 0f, 0.8f)) {
            blob(x - 0.18f, 0f, -0.18f, x + 0.18f, 0.12f, 0.18f, 0xFF2A2A2E.toInt())
            tube(x, 0.1f, 0f, x, 0.55f, 0f, 0.1f, 0xFFF06A20.toInt())
            blob(x - 0.09f, 0.3f, -0.09f, x + 0.09f, 0.38f, 0.09f, 0xFFF8F8F8.toInt())
        }
    }.mesh()
}
