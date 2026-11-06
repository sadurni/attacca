package com.sadurni.attacca.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class CrossfadePlannerTest {

    private val duration = 256_000L // 500 ms per bin of 512.
    private val bins = 512

    /** A waveform from a level in dB at each moment (ms). */
    private fun waveform(levelDb: (Long) -> Double) = FloatArray(bins) { b ->
        10.0.pow(levelDb(b * duration / bins) / 20).toFloat()
    }

    private val steady = waveform { -6.0 }

    /** Steady, then down 40 dB over its last 6 s (ends at [duration]). */
    private val fading = waveform { t -> if (t < duration - 6_000) -6.0 else -6.0 - 40.0 * (t - (duration - 6_000)) / 6_000 }

    /** Starts 20 dB down and rises over 4 s. */
    private val softIntro = waveform { t -> if (t > 4_000) -6.0 else -26.0 + 20.0 * t / 4_000 }

    @Test
    fun aFadeOutIsLeftAloneAndTheBlendLastsAsLongAsIt() {
        val plan = CrossfadePlanner.plan(10_000, fading, duration, duration, steady, duration, 0)
        assertEquals(CrossfadeShape.MIX, plan.shape)
        assertTrue("length ${plan.lengthMs}", plan.lengthMs in 4_500..7_000)
    }

    @Test
    fun aFadeLongerThanTheSettingIsCut() {
        val plan = CrossfadePlanner.plan(3_000, fading, duration, duration, steady, duration, 0)
        assertEquals(3_000, plan.lengthMs)
        assertEquals(CrossfadeShape.MIX, plan.shape)
    }

    @Test
    fun aHardEndingKeepsItsLastNoteWithAShortBlend() {
        val plan = CrossfadePlanner.plan(10_000, steady, duration, duration, steady, duration, 0)
        assertEquals(CrossfadePlanner.SHORT_BLEND_MS, plan.lengthMs)
        assertEquals(CrossfadeShape.MIX, plan.shape)
    }

    @Test
    fun aSoftStartIsNotFadedIn() {
        val plan = CrossfadePlanner.plan(10_000, fading, duration, duration, softIntro, duration, 0)
        assertEquals(CrossfadeShape.OVERLAY, plan.shape)
    }

    @Test
    fun anOrdinaryEndingIsACrossfade() {
        // Down 10 dB at the end: neither a fade-out nor a hard end.
        val gentle = waveform { t -> if (t < duration - 3_000) -6.0 else -16.0 }
        val plan = CrossfadePlanner.plan(10_000, gentle, duration, duration, steady, duration, 0)
        assertEquals(CrossfadePlan(10_000, CrossfadeShape.CROSSFADE), plan)
    }

    @Test
    fun withoutWaveformsItIsACrossfade() {
        val plan = CrossfadePlanner.plan(8_000, FloatArray(0), duration, duration, FloatArray(0), 0, 0)
        assertEquals(CrossfadePlan(8_000, CrossfadeShape.CROSSFADE), plan)
    }

    @Test
    fun shapesHaveEqualPowerCurves() {
        val s = CrossfadeShape.CROSSFADE
        assertEquals(1.0, s.outGain(0.5).pow(2) + s.inGain(0.5).pow(2), 1e-9)
        assertEquals(1.0, CrossfadeShape.MIX.outGain(0.99), 0.0)
        assertEquals(1.0, CrossfadeShape.OVERLAY.inGain(0.0), 0.0)
        assertEquals(CrossfadeShape(0.2f, 0.2f, 1f), CrossfadeShape.of(0.2f, 0.1f, 2f))
    }
}
