package com.sadurni.attacca.library

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class RecordingMatchTest {

    private val rate = 8_000

    /** A "song": notes of changing loudness, [seed] choosing their order. */
    private fun song(seconds: Int, seed: Int, delaySamples: Int = 0, gain: Float = 1f): FloatArray {
        val r = java.util.Random(seed.toLong())
        val notes = IntArray(seconds * 4) { r.nextInt(8) }
        return FloatArray(seconds * rate) { i ->
            val j = i - delaySamples
            if (j < 0) 0f else {
                val note = notes[(j / (rate / 4)) % notes.size]
                val within = (j % (rate / 4)).toFloat() / (rate / 4)
                gain * (0.1f + note * 0.1f) * (1f - within) * sin(2 * PI * 220 * j / rate).toFloat()
            }
        }
    }

    private fun levels(x: FloatArray) = RecordingMatch.envelope(x, 0, x.size, 1, rate)

    @Test
    fun theSameRecordingShiftedAndLouderMatches() {
        val a = levels(song(RecordingMatch.SPAN_S, 1))
        val b = levels(song(RecordingMatch.SPAN_S, 1, delaySamples = rate * 2, gain = 1.6f))
        assertTrue(RecordingMatch.likeness(a, b) >= 0.95f)
    }

    @Test
    fun anotherRecordingDoesNot() {
        val a = levels(song(RecordingMatch.SPAN_S, 1))
        val b = levels(song(RecordingMatch.SPAN_S, 2))
        assertTrue(RecordingMatch.likeness(a, b) < RecordingMatch.SAME)
    }
}
