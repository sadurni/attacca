package com.sadurni.attaccaplg

import com.sadurni.attaccaplg.audio.Mp3Repeats
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class Mp3RepeatsTest {
    /** A 192 kbit/s, 44.1 kHz joint-stereo frame (626 bytes) with its own content and [gain]. */
    private fun frame(seed: Int, gain: Int): ByteArray {
        val f = Random(seed).nextBytes(626)
        f[0] = 0xff.toByte(); f[1] = 0xfb.toByte(); f[2] = 0xb0.toByte(); f[3] = 0x44
        // Side information: every granule's global gain set to [gain], the rest left as it is.
        var pos = 9 + 3 + 8
        repeat(4) {
            for (b in 0 until 8) {
                val bit = pos + 21 + b
                val i = 4 + bit / 8
                val mask = 1 shl (7 - bit % 8)
                f[i] = if ((gain ushr (7 - b)) and 1 == 1) (f[i].toInt() or mask).toByte() else (f[i].toInt() and mask.inv()).toByte()
            }
            pos += 59
        }
        return f
    }

    private fun join(frames: List<ByteArray>) = frames.fold(ByteArray(0)) { a, b -> a + b }

    @Test
    fun dropsAStretchWrittenTwice() {
        val music = (0 until 50).map { frame(it, 170) }
        val damaged = music.subList(0, 30) + music.subList(10, 30) + music.subList(30, 50)
        assertArrayEquals(join(music), Mp3Repeats.cleaned(join(damaged)))
    }

    @Test
    fun keepsRepeatingSilenceAndCleanFiles() {
        val cycle = (0 until 3).map { frame(1000 + it, 170) }
        assertNull(Mp3Repeats.cleaned(join((0 until 60).map { cycle[it % 3] })))
        val quiet = (0 until 30).map { frame(it, 120) }
        assertNull(Mp3Repeats.cleaned(join(quiet + quiet.subList(10, 30))))
        assertNull(Mp3Repeats.cleaned(join((0 until 60).map { frame(it, 170) })))
    }
}
