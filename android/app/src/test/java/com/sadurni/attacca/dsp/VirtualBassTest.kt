package com.sadurni.attacca.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class VirtualBassTest {

    private val rate = 44100

    private fun tone(freq: Double, frames: Int) = FloatArray(frames * 2) { (0.3 * sin(2 * PI * freq * (it / 2) / rate)).toFloat() }

    /** Amplitude of [freq] in the left channel over the last second. */
    private fun level(buffer: FloatArray, freq: Double): Double {
        val frames = buffer.size / 2
        val from = frames - rate
        var re = 0.0
        var im = 0.0
        for (f in from until frames) {
            val x = buffer[2 * f].toDouble()
            re += x * cos(2 * PI * freq * f / rate)
            im += x * sin(2 * PI * freq * f / rate)
        }
        return 2 * sqrt(re * re + im * im) / rate
    }

    private fun run(amount: Double): FloatArray {
        val buffer = tone(50.0, 2 * rate)
        val vb = VirtualBass(rate)
        var f = 0
        while (f < 2 * rate) {
            val n = minOf(1024, 2 * rate - f)
            val part = buffer.copyOfRange(2 * f, 2 * (f + n))
            vb.process(part, n, 2, 100, amount)
            part.copyInto(buffer, 2 * f)
            f += n
        }
        return buffer
    }

    @Test
    fun addsHarmonicsOfTheBass() {
        val out = run(1.0)
        val fundamental = level(out, 50.0)
        val second = level(out, 100.0)
        val fourth = level(out, 200.0)
        // The bass itself is untouched; its harmonics are clearly there (mostly those above the
        // cutoff, which a small speaker can play).
        assertEquals(0.3, fundamental, 0.02)
        assertTrue("100 Hz at $second", second > 0.02)
        assertTrue("200 Hz at $fourth", fourth > 0.02)
    }

    @Test
    fun offAddsNothing() {
        val out = run(0.0)
        assertTrue(level(out, 100.0) < 1e-4)
    }
}
