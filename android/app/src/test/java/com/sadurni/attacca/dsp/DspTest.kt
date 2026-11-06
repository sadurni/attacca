package com.sadurni.attacca.dsp

import com.sadurni.attacca.settings.DspSettings
import com.sadurni.attacca.settings.ReplayGainMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

class Bs2bTest {

    @Test
    fun monoDcPassesAtUnityGain() {
        val bs2b = Bs2b().apply { setParameters(44100, 700, 4.5f) }
        repeat(44100) { bs2b.processFrame(0.5f, 0.5f) }
        assertEquals(0.5f, bs2b.outLeft, 1e-3f)
        assertEquals(0.5f, bs2b.outRight, 1e-3f)
    }

    @Test
    fun hardPannedBassLeaksIntoOtherChannel() {
        val bs2b = Bs2b().apply { setParameters(44100, 700, 4.5f) }
        var rightEnergy = 0.0
        for (i in 0 until 44100) {
            val bass = sin(2 * PI * 100 * i / 44100).toFloat()
            bs2b.processFrame(bass, 0f)
            if (i > 4410) rightEnergy += bs2b.outRight * bs2b.outRight
        }
        assertTrue("crossfed bass expected on the right channel", rightEnergy > 100)
    }
}

class LookaheadLimiterTest {

    @Test
    fun neverExceedsCeiling() {
        val channels = 2
        val limiter = LookaheadLimiter(44100, channels)
        val ceiling = 0.9f
        val random = Random(42)
        repeat(50) {
            val frames = 512
            val buffer = FloatArray(frames * channels) { (random.nextFloat() * 2 - 1) * 3f }
            limiter.process(buffer, frames, enabled = true, ceiling = ceiling)
            buffer.forEach { assertTrue("sample $it above ceiling", abs(it) <= ceiling + 1e-6f) }
        }
    }

    @Test
    fun quietSignalPassesUnchangedButDelayed() {
        val limiter = LookaheadLimiter(44100, 1)
        val frames = 1000
        val input = FloatArray(frames) { sin(2 * PI * 440 * it / 44100).toFloat() * 0.5f }
        val buffer = input.copyOf()
        limiter.process(buffer, frames, enabled = true, ceiling = 0.97f)
        val delay = limiter.latencyFrames
        for (i in delay until frames) {
            assertEquals(input[i - delay], buffer[i], 1e-6f)
        }
    }

    @Test
    fun singlePeakIsReducedToCeiling() {
        val limiter = LookaheadLimiter(44100, 1)
        val frames = 2000
        val buffer = FloatArray(frames) { if (it == 500) 2f else 0.1f }
        limiter.process(buffer, frames, enabled = true, ceiling = 1f)
        val peakOut = buffer[500 + limiter.latencyFrames]
        assertEquals(1f, peakOut, 1e-4f)
    }

    @Test
    fun peaksBetweenSamplesAreLimited() {
        // A sine at a quarter of the sample rate, 45° off: every sample is at 0.707 of the wave's
        // peak, so the samples stay under the ceiling while the wave between them goes over it.
        val limiter = LookaheadLimiter(44100, 1)
        val frames = 8000
        val buffer = FloatArray(frames) { sin(PI / 2 * it + PI / 4).toFloat() }
        limiter.process(buffer, frames, enabled = true, ceiling = 0.9f)
        val samplePeak = (4000 until frames).maxOf { abs(buffer[it]) }
        val wavePeak = samplePeak / sin(PI / 4).toFloat()
        assertTrue("wave peak $wavePeak above the ceiling", wavePeak <= 0.9f * 1.035f)
        assertTrue("wave peak $wavePeak limited too much", wavePeak >= 0.9f * 0.95f)
    }
}

class ReplayGainTest {

    @Test
    fun parsesTagFormats() {
        val info = ReplayGainInfo.fromTags(
            mapOf(
                "REPLAYGAIN_TRACK_GAIN" to "-6.48 dB",
                "REPLAYGAIN_TRACK_PEAK" to "0.988",
                "REPLAYGAIN_ALBUM_GAIN" to "−7,5 dB",
            ),
        )
        assertEquals(-6.48f, info.trackGainDb!!, 1e-4f)
        assertEquals(0.988f, info.trackPeak!!, 1e-4f)
        assertEquals(-7.5f, info.albumGainDb!!, 1e-4f)
        assertNull(info.albumPeak)
    }

    @Test
    fun parsesOpusR128() {
        // -512 / 256 = -2 dB relative to -23 LUFS = +3 dB relative to ReplayGain's -18 LUFS.
        val info = ReplayGainInfo.fromTags(mapOf("R128_TRACK_GAIN" to "-512"))
        assertEquals(3f, info.trackGainDb!!, 1e-4f)
    }

    @Test
    fun autoModeUsesAlbumInOrderAndTrackWhenShuffling() {
        val info = ReplayGainInfo(trackGainDb = -3f, albumGainDb = -5f)
        val settings = DspSettings(replayGainMode = ReplayGainMode.AUTO, replayGainPreventClipping = false)
        assertEquals(-5f, ReplayGain.resolveGainDb(info, settings, shuffle = false), 1e-4f)
        assertEquals(-3f, ReplayGain.resolveGainDb(info, settings, shuffle = true), 1e-4f)
    }

    @Test
    fun peakPreventsClipping() {
        // +6 dB on a track peaking at 0.8 would clip; limit to -20*log10(0.8) = 1.94 dB.
        val info = ReplayGainInfo(trackGainDb = 6f, trackPeak = 0.8f)
        val settings = DspSettings(replayGainMode = ReplayGainMode.TRACK)
        assertEquals(1.938f, ReplayGain.resolveGainDb(info, settings, shuffle = false), 1e-3f)
    }

    @Test
    fun untaggedFilesUseFallbackGain() {
        val settings = DspSettings(replayGainMode = ReplayGainMode.TRACK, untaggedGainDb = -4f)
        assertEquals(-4f, ReplayGain.resolveGainDb(ReplayGainInfo.NONE, settings, shuffle = false), 1e-4f)
    }

    @Test
    fun offModeIgnoresTags() {
        val settings = DspSettings(replayGainMode = ReplayGainMode.OFF)
        assertEquals(0f, ReplayGain.resolveGainDb(ReplayGainInfo(trackGainDb = -8f), settings, false), 1e-4f)
    }
}
