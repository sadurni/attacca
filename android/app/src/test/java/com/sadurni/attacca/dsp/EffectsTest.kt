package com.sadurni.attacca.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

private const val RATE = 44100

/** Interleaved stereo sine, same signal on both channels unless [rightGain] differs. */
private fun sine(freq: Double, amplitude: Double, frames: Int, rightGain: Double = 1.0): FloatArray {
    val out = FloatArray(frames * 2)
    for (i in 0 until frames) {
        val v = amplitude * sin(2 * PI * freq * i / RATE)
        out[2 * i] = v.toFloat()
        out[2 * i + 1] = (v * rightGain).toFloat()
    }
    return out
}

/** RMS of one channel over the second half (after filters settle). */
private fun rms(buffer: FloatArray, channel: Int): Double {
    val frames = buffer.size / 2
    var sum = 0.0
    for (i in frames / 2 until frames) sum += buffer[2 * i + channel] * buffer[2 * i + channel].toDouble()
    return sqrt(sum / (frames - frames / 2))
}

private fun db(ratio: Double) = 20 * log10(ratio)

class GraphicEqTest {

    @Test
    fun flatEqIsTransparent() {
        val input = sine(1000.0, 0.5, RATE)
        val buffer = input.copyOf()
        GraphicEq(RATE, 2).apply { setGains(List(10) { 0f }) }.process(buffer, RATE)
        assertTrue(buffer.contentEquals(input))
    }

    @Test
    fun bandBoostRaisesItsFrequency() {
        val buffer = sine(1000.0, 0.1, RATE)
        val before = rms(buffer, 0)
        GraphicEq(RATE, 2).apply { setGains(List(10) { if (it == 5) 6f else 0f }) }.process(buffer, RATE)
        assertEquals(6.0, db(rms(buffer, 0) / before), 0.3)
    }
}

class DynamicBassTest {

    /** Boost of a [freq] Hz sine at [amplitude] after one second, measured over the last half. */
    private fun boostDb(amplitude: Double, freq: Double = 40.0): Double {
        val buffer = sine(freq, amplitude, RATE)
        val before = rms(buffer, 0)
        DynamicBass(RATE, 2).process(buffer, RATE, freqHz = 100, maxGainDb = 6f, ceilingDb = -6f)
        return db(rms(buffer, 0) / before)
    }

    @Test
    fun quietBassGetsFullBoost() {
        assertEquals(6.0, boostDb(0.01), 0.7)
    }

    @Test
    fun typicalBassStillGetsFullBoost() {
        // -12 dBFS bass has 6 dB of room under the -6 dB ceiling.
        assertEquals(6.0, boostDb(0.25), 0.8)
    }

    @Test
    fun loudBassStaysUnderTheCeiling() {
        // -6 dBFS bass is already at the ceiling: no boost.
        assertEquals(0.0, boostDb(0.5), 0.8)
        // -9 dBFS gets about the 3 dB left.
        assertEquals(3.0, boostDb(0.355), 1.0)
    }

    @Test
    fun highFrequenciesAreUntouched() {
        assertEquals(0.0, boostDb(0.01, freq = 5000.0), 0.3)
    }
}

class SpeakerVirtualizerTest {

    private fun ears(freq: Double, left: Double, right: Double): Pair<Double, Double> {
        val v = SpeakerVirtualizer(RATE)
        var l = 0.0
        var r = 0.0
        for (i in 0 until RATE) {
            val x = sin(2 * PI * freq * i / RATE)
            v.processFrame((x * left).toFloat(), (x * right).toFloat())
            if (i >= RATE / 2) {
                l += v.outLeft * v.outLeft
                r += v.outRight * v.outRight
            }
        }
        return sqrt(l / (RATE / 2)) to sqrt(r / (RATE / 2))
    }

    @Test
    fun hardPannedBassReachesBothEars() {
        val (l, r) = ears(100.0, 1.0, 0.0)
        // Like a real speaker: the far ear hears the bass almost as loud.
        assertTrue("far ear ${db(r / l)} dB", db(r / l) > -4)
    }

    @Test
    fun headShadowsTheHighsAtTheFarEar() {
        val (l, r) = ears(6000.0, 1.0, 0.0)
        assertTrue("far ear ${db(r / l)} dB", db(r / l) < -8)
    }

    @Test
    fun centredSoundKeepsItsLevel() {
        val (l, r) = ears(200.0, 1.0, 1.0)
        val input = sqrt(0.5)
        assertEquals(0.0, db(l / input), 1.5)
        assertEquals(0.0, db(r / input), 1.5)
    }
}

class StereoWidthTest {

    @Test
    fun zeroWidthIsMono() {
        val buffer = sine(1000.0, 0.5, 4410, rightGain = 0.2)
        StereoWidth(RATE).process(buffer, 4410, 0.0, 0.0, bassSafe = false)
        for (i in 0 until 4410) assertEquals(buffer[2 * i], buffer[2 * i + 1], 1e-6f)
    }

    @Test
    fun fullWidthIsUnchanged() {
        val input = sine(1000.0, 0.5, 4410, rightGain = 0.2)
        val buffer = input.copyOf()
        StereoWidth(RATE).process(buffer, 4410, 1.0, 1.0, bassSafe = false)
        for (i in input.indices) assertEquals(input[i], buffer[i], 1e-6f)
    }

    @Test
    fun bassSafeKeepsLowSideUnwidened() {
        // Pure side signal at 40 Hz: with bass-safe, 200 % width barely changes it.
        val frames = RATE
        val wide = sine(40.0, 0.3, frames, rightGain = -1.0)
        val before = rms(wide, 0)
        StereoWidth(RATE).process(wide, frames, 2.0, 2.0, bassSafe = true)
        assertTrue(db(rms(wide, 0) / before) < 1.5)
    }
}

class CompressorTest {

    @Test
    fun staticCurve() {
        // Below threshold: untouched. Well above: threshold + over / ratio.
        assertEquals(-30.0, Compressor.staticCurve(-30.0, -18.0, 4.0, 6.0), 1e-9)
        assertEquals(-15.0, Compressor.staticCurve(-6.0, -18.0, 4.0, 6.0), 1e-9)
        // Inside the knee: between the two.
        val inKnee = Compressor.staticCurve(-18.0, -18.0, 4.0, 6.0)
        assertTrue(inKnee < -18.0 && inKnee > -18.0 - 1.5)
    }

    @Test
    fun loudSignalIsReduced() {
        val buffer = sine(1000.0, 0.5, RATE) // ~-6 dBFS peak
        val before = rms(buffer, 0)
        Compressor(RATE, 2).process(buffer, RATE, -18f, 4f, 0f, 5f, 100f, 0f)
        // 12 dB over threshold at 4:1 → about 9 dB of reduction.
        assertEquals(-9.0, db(rms(buffer, 0) / before), 1.0)
    }
}

class DenormalTest {

    /** A short tone on the left only, then two seconds of digital silence on both channels. */
    private fun toneThenSilence(): FloatArray {
        val buffer = sine(200.0, 0.5, RATE * 2, rightGain = 0.0)
        java.util.Arrays.fill(buffer, RATE / 5, buffer.size, 0f)
        return buffer
    }

    private fun assertNoDenormals(values: FloatArray) {
        for (v in values) assertTrue("denormal output $v", v == 0f || kotlin.math.abs(v) >= java.lang.Float.MIN_NORMAL)
    }

    @Test
    fun crossfeedNeverDecaysIntoDenormals() {
        for (filter in listOf<StereoCrossfeed>(Bs2b().apply { setParameters(RATE, 700, 4.5f) }, SpeakerVirtualizer(RATE))) {
            val input = toneThenSilence()
            val out = FloatArray(input.size)
            for (f in 0 until input.size / 2) {
                filter.processFrame(input[2 * f], input[2 * f + 1])
                out[2 * f] = filter.outLeft
                out[2 * f + 1] = filter.outRight
            }
            assertNoDenormals(out)
        }
    }

    @Test
    fun filtersNeverDecayIntoDenormals() {
        val buffer = toneThenSilence()
        DynamicBass(RATE, 2).process(buffer, buffer.size / 2, 100, 8f, -6f)
        StereoWidth(RATE).process(buffer, buffer.size / 2, 1.5, 1.5, bassSafe = true)
        assertNoDenormals(buffer)
    }
}
