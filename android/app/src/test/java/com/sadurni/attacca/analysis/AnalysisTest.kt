package com.sadurni.attacca.analysis

import com.sadurni.attacca.settings.TrimSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackAnalysisTest {

    /** 10 ms windows: [silentHead] silent, [loud] loud, [silentTail] silent. */
    private fun analysis(silentHead: Int, loud: Int, silentTail: Int): TrackAnalysis {
        val count = silentHead + loud + silentTail
        val peaks = FloatArray(count) { i -> if (i in silentHead until silentHead + loud) 0.5f else 0.0001f }
        val squares = FloatArray(count) { peaks[it] * peaks[it] }
        return TrackAnalysis.fromWindows(peaks, squares, count, count * 10L, -60f)
    }

    @Test
    fun findsAudibleBounds() {
        val a = analysis(silentHead = 150, loud = 1000, silentTail = 230)
        assertEquals(1500L, a.audioStartMs)
        assertEquals(11_500L, a.audioEndMs)
        assertEquals(1500L, a.leadingSilenceMs)
        assertEquals(2300L, a.trailingSilenceMs)
    }

    @Test
    fun silentFileIsNotTrimmed() {
        val a = analysis(silentHead = 100, loud = 0, silentTail = 0)
        assertEquals(0L, a.audioStartMs)
        assertEquals(1000L, a.audioEndMs)
    }

    @Test
    fun waveformIsNormalized() {
        val a = analysis(silentHead = 100, loud = 500, silentTail = 100)
        assertEquals(TrackAnalysis.WAVEFORM_BINS, a.waveform.size)
        assertEquals(1f, a.waveform.max(), 1e-6f)
        assertTrue(a.waveform.first() < 0.01f)
    }
}

class SilenceTrimmerTest {

    private val settings = TrimSettings(minSilenceMs = 500, marginMs = 100)

    private fun track(album: String, lead: Long, trail: Long, duration: Long = 200_000) =
        TrimCandidate(
            album,
            TrackAnalysis(duration, lead, duration - trail, FloatArray(0), -60f),
        )

    @Test
    fun trimsBothEndsKeepingMargin() {
        val queue = listOf(track("a", lead = 2000, trail = 3000))
        val trim = SilenceTrimmer.trimFor(0, queue, settings)
        assertEquals(1900L, trim.startMs)
        assertEquals(197_100L, trim.endMs)
    }

    @Test
    fun shortSilencesAreKept() {
        val trim = SilenceTrimmer.trimFor(0, listOf(track("a", lead = 300, trail = 400)), settings)
        assertNull(trim.startMs)
        assertNull(trim.endMs)
    }

    @Test
    fun keepsPauseWhenSameAlbumFlowsAcross() {
        // Track 1 ends in a 2 s pause, track 2 starts right away: the pause is intentional.
        val queue = listOf(track("album", lead = 0, trail = 2000), track("album", lead = 0, trail = 0))
        assertNull(SilenceTrimmer.trimFor(0, queue, settings).endMs)
        // Different albums: trim it.
        val mixed = listOf(track("one", lead = 0, trail = 2000), track("two", lead = 0, trail = 0))
        assertEquals(198_100L, SilenceTrimmer.trimFor(0, mixed, settings).endMs)
        // Option off: trim it too.
        val off = settings.copy(respectGaplessAlbums = false)
        assertEquals(198_100L, SilenceTrimmer.trimFor(0, queue, off).endMs)
    }

    @Test
    fun trimsWhenBothSidesHaveSilence() {
        val queue = listOf(track("album", lead = 0, trail = 2000), track("album", lead = 1500, trail = 0))
        assertEquals(198_100L, SilenceTrimmer.trimFor(0, queue, settings).endMs)
        assertEquals(1400L, SilenceTrimmer.trimFor(1, queue, settings).startMs)
    }

    @Test
    fun disabledOrUnanalyzedMeansNoTrim() {
        val queue = listOf(track("a", lead = 2000, trail = 3000), TrimCandidate("a", null))
        assertTrue(SilenceTrimmer.trimFor(0, queue, settings.copy(enabled = false)).isNone)
        assertTrue(SilenceTrimmer.trimFor(1, queue, settings).isNone)
    }

    @Test
    fun respectsStartAndEndToggles() {
        val queue = listOf(track("a", lead = 2000, trail = 3000))
        val onlyEnd = SilenceTrimmer.trimFor(0, queue, settings.copy(trimStart = false))
        assertNull(onlyEnd.startMs)
        assertEquals(197_100L, onlyEnd.endMs)
    }
}
