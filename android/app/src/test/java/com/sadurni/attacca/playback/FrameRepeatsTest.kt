package com.sadurni.attacca.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class FrameRepeatsTest {
    /** A 192 kbit/s, 44.1 kHz frame (626 bytes) with its own content; [mono] for a mono header. */
    private fun frame(seed: Int, gain: Int = 170, mono: Boolean = false): ByteArray {
        val f = Random(seed).nextBytes(626)
        f[0] = 0xff.toByte(); f[1] = 0xfb.toByte(); f[2] = 0xb0.toByte(); f[3] = if (mono) 0xc4.toByte() else 0x44
        // No stray sync words inside, and every granule's global gain set to [gain].
        for (i in 4 until f.size) if (f[i] == 0xff.toByte()) f[i] = 0x7f
        var pos = 9 + (if (mono) 5 else 3) + (if (mono) 4 else 8)
        repeat(if (mono) 2 else 4) {
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

    private fun join(parts: List<ByteArray>) = parts.fold(ByteArray(0)) { a, b -> a + b }

    /** What comes out of [FrameRepeats] for a file, walked as the player reads it. */
    private fun played(data: ByteArray): List<ByteArray> {
        val repeats = FrameRepeats()
        val out = ArrayList<ByteArray>()
        val send: (ByteArray, Long, Int) -> Unit = { b, _, _ -> out += b }
        assertTrue(FrameRepeats.walk(data) { offset, size, timeUs, broken ->
            repeats.take(data.copyOfRange(offset, offset + size), timeUs, 0, send, broken)
        })
        repeats.flush(send)
        return out
    }

    @Test
    fun dropsAStretchWrittenTwice() {
        val music = (0 until 50).map { frame(it) }
        val damaged = music.subList(0, 30) + music.subList(10, 30) + music.subList(30, 50)
        assertEquals(music.map { it.toList() }, played(join(damaged)).map { it.toList() })
    }

    @Test
    fun keepsRepeatingSilence() {
        val cycle = (0 until 3).map { frame(1000 + it, gain = 170) }
        assertEquals(60, played(join((0 until 60).map { cycle[it % 3] })).size)
        val quiet = (0 until 30).map { frame(it, gain = 120) }
        assertEquals(50, played(join(quiet + quiet.subList(10, 30))).size)
    }

    @Test
    fun dropsBrokenFrames() {
        val music = (0 until 40).map { frame(it) }
        // Frame 10 is cut short by garbage (the next frame does not follow it); frame 20 is mono.
        val cut = music[10].copyOf(400) + Random(7).nextBytes(300).map { if (it == 0xff.toByte()) 0 else it }.toByteArray()
        val damaged = music.subList(0, 10) + listOf(cut) + music.subList(11, 20) + listOf(frame(99, mono = true)) + music.subList(20, 40)
        val out = played(join(damaged))
        // The cut frame (its header still reads as one) and the mono one are left out.
        assertEquals(39, out.size)
        assertEquals(music.filterIndexed { i, _ -> i != 10 }.map { it.toList() }, out.map { it.toList() })
    }
}
