package com.sadurni.attacca.dsp

import com.sadurni.attacca.settings.BassCurve
import com.sadurni.attacca.settings.DspSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GentleBassTest {
    private val rate = 44100

    @Test
    fun gentleShelfRisesGraduallyLikeAToneControl() {
        val shelf = Biquad(1).apply { setGentleLowShelf(rate, 200.0, 8.0) }
        assertEquals(8.0, shelf.magnitudeDb(20.0, rate), 0.3)
        assertEquals(4.0, shelf.magnitudeDb(200.0, rate), 0.2) // half the boost at the midpoint
        assertEquals(2.5, shelf.magnitudeDb(300.0, rate), 0.3)
        assertTrue(shelf.magnitudeDb(2000.0, rate) < 0.3)
        assertTrue(shelf.magnitudeDb(10000.0, rate) in -0.05..0.1)
    }

    @Test
    fun precutOnlyWhatTheTrackLacks() {
        val s = DspSettings(bassEnabled = true, bassCurve = BassCurve.GENTLE, bassMaxGainDb = 8f)
        assertEquals(6f, DynamicBass.precutDb(s, 0f), 1e-4f)       // a track at full scale (2 dB allowance)
        assertEquals(1f, DynamicBass.precutDb(s, 5f), 1e-4f)       // ReplayGain left 5 dB
        assertEquals(0f, DynamicBass.precutDb(s, 7f), 1e-4f)       // room enough
        assertEquals(6f, DynamicBass.precutDb(s, -2f), 1e-4f)      // over full scale: still only the boost
        assertEquals(0f, DynamicBass.precutDb(s.copy(bassCurve = BassCurve.DEEP), 0f), 1e-4f)
        assertEquals(0f, DynamicBass.precutDb(s.copy(bassEnabled = false), 0f), 1e-4f)
    }

    @Test
    fun gentleBoostIsSteadyEvenWithLoudBass() {
        // A loud 60 Hz tone: the deep curve backs off under its ceiling, the gentle one does not.
        fun outputDb(gentle: Boolean): Double {
            val frames = rate * 2
            val buffer = FloatArray(frames * 2) { i -> (0.9 * kotlin.math.sin(2 * Math.PI * 60 * (i / 2) / rate)).toFloat() }
            DynamicBass(rate, 2).process(buffer, frames, 200, 6f, -6f, gentle)
            var peak = 0f
            for (i in frames until frames * 2) peak = maxOf(peak, kotlin.math.abs(buffer[i]))
            return 20 * kotlin.math.log10(peak / 0.9)
        }
        assertTrue(outputDb(gentle = true) > 4.5)
        assertTrue(outputDb(gentle = false) < 1.0)
    }
}
