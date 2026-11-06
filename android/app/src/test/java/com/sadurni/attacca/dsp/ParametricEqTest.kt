package com.sadurni.attacca.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParametricEqTest {

    @Test
    fun readsACorrectionText() {
        val text = """
            Preamp: -6.4 dB
            Filter 1: ON LSC Fc 105 Hz Gain 6.0 dB Q 0.70
            Filter 2: ON PK Fc 180 Hz Gain -2.1 dB Q 1.10
            Filter 3: OFF PK Fc 3000 Hz Gain 4 dB Q 2
            Filter 4: ON HSC Fc 10000 Hz Gain 2.0 dB Q 0.70
            something else
        """.trimIndent()
        val p = PeqText.parse(text)
        assertEquals(-6.4f, p.preampDb!!, 1e-4f)
        assertEquals(4, p.bands.size)
        assertEquals(PeqBand(PeqBand.Type.LOW_SHELF, 105f, 6f, 0.7f), p.bands[0])
        assertFalse(p.bands[2].enabled)
        assertEquals(PeqBand.Type.HIGH_SHELF, p.bands[3].type)
        // And back.
        val again = PeqText.parse(PeqText.format(p.preampDb!!, p.bands))
        assertEquals(p.bands, again.bands)
    }

    @Test
    fun bandsHaveTheirGainAtTheirFrequency() {
        assertEquals(4.0, ParametricEq.responseDb(PeqBand(PeqBand.Type.PEAK, 1000f, 4f, 1f), 1000.0, 48000), 0.05)
        // Shelves: full gain well inside, none well outside.
        val low = PeqBand(PeqBand.Type.LOW_SHELF, 200f, 6f, 0.71f)
        assertEquals(6.0, ParametricEq.responseDb(low, 20.0, 48000), 0.2)
        assertEquals(0.0, ParametricEq.responseDb(low, 5000.0, 48000), 0.2)
        val high = PeqBand(PeqBand.Type.HIGH_SHELF, 5000f, -3f, 0.71f)
        assertEquals(-3.0, ParametricEq.responseDb(high, 18000.0, 48000), 0.3)
        assertEquals(0.0, ParametricEq.responseDb(high, 200.0, 48000), 0.2)
    }

    @Test
    fun maxBoostAddsOverlappingBands() {
        val bands = listOf(PeqBand(PeqBand.Type.PEAK, 100f, 3f, 1f), PeqBand(PeqBand.Type.PEAK, 110f, 3f, 1f))
        val boost = ParametricEq.maxBoostDb(bands)
        assertTrue("boost $boost", boost in 5.5f..6.1f)
    }

    @Test
    fun processesAndFadesOut() {
        val eq = ParametricEq(48000, 1)
        val buffer = FloatArray(4800) { kotlin.math.sin(2 * Math.PI * 1000 * it / 48000).toFloat() * 0.25f }
        val band = listOf(PeqBand(PeqBand.Type.PEAK, 1000f, 6f, 1f))
        repeat(20) { eq.process(buffer.copyOf(), 4800, band, on = true) }
        assertTrue(eq.isActive)
        repeat(20) { eq.process(buffer.copyOf(), 4800, band, on = false) }
        assertFalse(eq.isActive)
    }
}
