package com.sadurni.attacca.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

class LoudnessMeterTest {

    /** [seconds] of a stereo sine of [hz] at [dbfs] (peak) per channel, with optional silences. */
    private fun measure(rate: Int, hz: Double, dbfs: Double, seconds: Double, silentFrom: Double = -1.0, silentTo: Double = -1.0): LoudnessMeter {
        val meter = LoudnessMeter(rate, 2)
        val amplitude = 10.0.pow(dbfs / 20)
        val frames = (rate * seconds).toInt()
        val chunk = 4096
        val buffer = FloatArray(chunk * 2)
        var done = 0
        while (done < frames) {
            val n = minOf(chunk, frames - done)
            for (f in 0 until n) {
                val t = (done + f).toDouble() / rate
                val silent = t >= silentFrom && t < silentTo
                val x = if (silent) 0f else (amplitude * sin(2 * PI * hz * t)).toFloat()
                buffer[2 * f] = x
                buffer[2 * f + 1] = x
            }
            meter.process(buffer, n)
            done += n
        }
        return meter
    }

    @Test
    fun stereoSineAtMinus23DbfsIsMinus23Lufs() {
        // EBU Tech 3341, case 1: 1 kHz stereo sine at -23 dBFS reads -23.0 LUFS (±0.1).
        for (rate in listOf(44100, 48000, 96000)) {
            val lufs = measure(rate, 997.0, -23.0, 20.0).integratedLufs()!!
            assertEquals("at $rate Hz", -23.0f, lufs, 0.1f)
        }
    }

    @Test
    fun levelChangesShowOneForOne() {
        // Case 2: the same tone at -33 dBFS reads -33 LUFS.
        assertEquals(-33.0f, measure(48000, 997.0, -33.0, 20.0).integratedLufs()!!, 0.1f)
    }

    @Test
    fun silenceIsGatedOut() {
        // Half of the track silent: the absolute gate ignores it and the level stays, but for the
        // few blocks straddling each edge, half silent, which pass the gates (as in BS.1770).
        val lufs = measure(48000, 997.0, -23.0, 20.0, silentFrom = 5.0, silentTo = 15.0).integratedLufs()!!
        assertEquals(-23.0f, lufs, 0.2f)
    }

    @Test
    fun allSilentHasNoLoudness() {
        assertNull(measure(48000, 997.0, -23.0, 5.0, silentFrom = 0.0, silentTo = 5.0).integratedLufs())
    }

    @Test
    fun peakIsTheLargestSample() {
        assertEquals(10.0.pow(-6.0 / 20).toFloat(), measure(48000, 997.0, -6.0, 2.0).samplePeak, 0.001f)
    }

    @Test
    fun albumOfEqualTracksIsTheirLevel() {
        assertEquals(-14f, LoudnessMeter.albumLufs(listOf(-14f to 200_000L, -14f to 100_000L))!!, 0.001f)
        // A long loud track weighs more than a short quiet one.
        val album = LoudnessMeter.albumLufs(listOf(-10f to 300_000L, -20f to 60_000L))!!
        assertEquals(true, album > -11f && album < -10f)
    }
}
