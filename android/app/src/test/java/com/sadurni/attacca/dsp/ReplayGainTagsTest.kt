package com.sadurni.attacca.dsp

import com.sadurni.attacca.settings.DspSettings
import com.sadurni.attacca.settings.ReplayGainMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Broken ReplayGain tags must never silence a track. */
class ReplayGainTagsTest {

    @Test
    fun peakOnAnotherScaleCountsAsUnknown() {
        // As found in the user's files: the peak written ×2^20.
        val info = ReplayGainInfo.fromTags(
            mapOf("REPLAYGAIN_TRACK_GAIN" to "-3.20 dB", "REPLAYGAIN_TRACK_PEAK" to "1126107.000000"),
        )
        assertNull(info.trackPeak)
        val db = ReplayGain.resolveGainDb(info, DspSettings(replayGainPreventClipping = true), shuffle = true, mode = ReplayGainMode.TRACK)
        assertEquals(-3.2f, db, 1e-4f)
    }

    @Test
    fun usualPeaksStillLimitTheGain() {
        val info = ReplayGainInfo.fromTags(mapOf("REPLAYGAIN_TRACK_GAIN" to "+6 dB", "REPLAYGAIN_TRACK_PEAK" to "1.2"))
        val db = ReplayGain.resolveGainDb(info, DspSettings(replayGainPreventClipping = true), shuffle = true, mode = ReplayGainMode.TRACK)
        assertEquals(-1.584f, db, 1e-3f)
    }

    @Test
    fun absurdGainsAreIgnored() {
        val info = ReplayGainInfo.fromTags(mapOf("REPLAYGAIN_TRACK_GAIN" to "-120 dB", "REPLAYGAIN_ALBUM_GAIN" to "-2 dB"))
        assertNull(info.trackGainDb)
        assertEquals(-2f, info.albumGainDb!!, 1e-4f)
    }
}
