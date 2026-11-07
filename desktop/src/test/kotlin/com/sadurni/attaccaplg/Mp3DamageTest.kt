package com.sadurni.attaccaplg

import com.sadurni.attacca.playback.Mp3Damage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The damage check and repair shared with the phone ([Mp3Damage]), on made-up frames. */
class Mp3DamageTest {
    /** A 192 kbit/s, 44.1 kHz joint-stereo frame (626 bytes) with its own content and a loud gain. */
    private fun frame(seed: Int): ByteArray {
        val f = Random(seed).nextBytes(626)
        f[0] = 0xff.toByte(); f[1] = 0xfb.toByte(); f[2] = 0xb0.toByte(); f[3] = 0x44
        var pos = 9 + 3 + 8
        repeat(4) {
            for (b in 0 until 8) {
                val bit = pos + 21 + b
                val i = 4 + bit / 8
                val mask = 1 shl (7 - bit % 8)
                f[i] = if ((170 ushr (7 - b)) and 1 == 1) (f[i].toInt() or mask).toByte() else (f[i].toInt() and mask.inv()).toByte()
            }
            pos += 59
        }
        return f
    }

    private fun join(parts: List<ByteArray>) = parts.fold(ByteArray(0)) { a, b -> a + b }

    /** Bytes that are no frame (no 0xFF in them, so nothing looks like a frame's start). */
    private fun garbage(n: Int) = ByteArray(n) { (it % 200 + 1).toByte() }

    private val music = (0 until 80).map { frame(it) }

    @Test
    fun aCleanFileIsFine() {
        assertNull(Mp3Damage.check(join(music)))
    }

    @Test
    fun aTagAfterTheMusicIsNoDamage() {
        assertNull(Mp3Damage.check(join(music) + "APETAGEX".toByteArray() + ByteArray(40)))
    }

    @Test
    fun aSingleBrokenFrameIsNoDamage() {
        val parts = music.subList(0, 40) + listOf(garbage(300)) + music.subList(40, 80)
        assertNull(Mp3Damage.check(join(parts)))
    }

    @Test
    fun brokenFramesInsideAreFoundAndRepaired() {
        val parts = ArrayList<ByteArray>()
        for ((i, f) in music.withIndex()) {
            parts += f
            if (i in setOf(15, 30, 45, 60)) parts += garbage(200)
        }
        val damaged = join(parts)
        val found = Mp3Damage.check(damaged)
        assertTrue(found is Mp3Damage.Damage.Broken && found.frames >= Mp3Damage.MIN_BROKEN)
        val fixed = Mp3Damage.repaired(damaged)!!
        assertNull(Mp3Damage.check(fixed))
        // The four frames followed by garbage go; the rest stays.
        assertEquals(76 * 626, fixed.size)
    }

    @Test
    fun aStretchWrittenTwiceIsFound() {
        val parts = music.subList(0, 50) + music.subList(30, 50) + music.subList(50, 80)
        assertTrue(Mp3Damage.check(join(parts)) is Mp3Damage.Damage.Repeats)
        assertNull(Mp3Damage.check(Mp3Damage.repaired(join(parts))!!))
    }
}
