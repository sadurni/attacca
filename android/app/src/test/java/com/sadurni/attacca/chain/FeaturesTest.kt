package com.sadurni.attacca.chain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

class FeaturesTest {

    private val rate = 22_050

    private fun measure(s: FloatArray) = TrackFeatures.measure(s, s.size, rate)!!

    /** Decaying noise bursts every beat at [bpm], over a quiet chord. */
    private fun beat(bpm: Double, seconds: Int = 20): FloatArray {
        val random = Random(3)
        val period = rate * 60 / bpm
        return FloatArray(seconds * rate) { i ->
            val t = (i % period) / rate
            val click = (random.nextFloat() - 0.5f) * 0.8f * exp(-t * 40).toFloat()
            click + 0.05f * sin(2 * PI * 220 * i / rate).toFloat()
        }
    }

    private fun chord(seconds: Int, vararg hz: Double, noise: Float = 0f): FloatArray {
        val random = Random(5)
        return FloatArray(seconds * rate) { i ->
            var s = 0.0
            for (f in hz) s += sin(2 * PI * f * i / rate)
            (0.2 * s / hz.size).toFloat() + noise * (random.nextFloat() - 0.5f)
        }
    }

    @Test
    fun findsTheTempo() {
        for (bpm in listOf(90.0, 120.0, 140.0)) {
            val f = measure(beat(bpm))
            assertEquals("tempo of $bpm", bpm, f.tempoBpm.toDouble(), bpm * 0.04)
            assertTrue(f.pulseClarity > 0.3f)
        }
    }

    @Test
    fun majorAndMinorChordsDiffer() {
        // C major (C E G) and C minor (C Eb G), with their octaves.
        val major = measure(chord(10, 261.63, 329.63, 392.0, 523.25, 659.25))
        val minor = measure(chord(10, 261.63, 311.13, 392.0, 523.25, 622.25))
        assertTrue("major ${major.mode}", major.mode > 0f)
        assertTrue("minor ${minor.mode}", minor.mode < 0f)
    }

    @Test
    fun noiseIsNoisierAndBrighterThanALowTone() {
        val tone = measure(chord(10, 110.0))
        val noisy = measure(chord(10, 110.0, noise = 0.8f))
        assertTrue(noisy.noisiness > tone.noisiness + 0.2f)
        assertTrue(noisy.brightness > tone.brightness + 2f)
        assertTrue(tone.bassDb > noisy.bassDb)
    }

    @Test
    fun beatsArePercussiveAndSteadyTonesAreNot() {
        assertTrue(measure(beat(120.0)).percussiveness > measure(chord(20, 220.0)).percussiveness * 3)
    }

    @Test
    fun silenceIsNotMeasured() {
        assertNull(TrackFeatures.measure(FloatArray(rate * 10), rate * 10, rate))
    }

    @Test
    fun roundTrips() {
        val f = measure(beat(100.0, 10))
        assertEquals(f.toArray().toList(), TrackFeatures.fromArray(f.toArray()).toArray().toList())
        assertEquals(TrackFeatures.SIZE, f.toArray().size)
    }
}
