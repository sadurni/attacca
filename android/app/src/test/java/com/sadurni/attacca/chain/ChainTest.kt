package com.sadurni.attacca.chain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

class ChainTest {

    private val rate = 44_100

    /** [seconds] of the given frequencies (Hz) mixed at [gain], with a little noise. */
    private fun tone(seconds: Int, vararg hz: Double, gain: Float = 0.2f, noise: Float = 0.002f, seed: Int = 1): FloatArray {
        val random = Random(seed)
        return FloatArray(seconds * rate) { i ->
            var s = 0.0
            for (f in hz) s += sin(2 * PI * f * i / rate)
            (gain * s / hz.size).toFloat() + noise * (random.nextFloat() - 0.5f)
        }
    }

    private fun describe(samples: FloatArray) = EdgeFeatures.describe(samples, 0, samples.size, rate)!!

    @Test
    fun fftFindsTheFrequencyOfASine() {
        val n = 1024
        val fft = Fft(n)
        val re = FloatArray(n) { sin(2 * PI * 50 * it / n).toFloat() }
        val im = FloatArray(n)
        fft.transform(re, im)
        val peak = (1 until n / 2).maxBy { hypot(re[it], im[it]) }
        assertEquals(50, peak)
        assertEquals(n / 2f, hypot(re[50], im[50]), 1f)
    }

    @Test
    fun chromaFollowsTheNotesPlayed() {
        // A major triad: A, C#, E.
        val d = describe(tone(4, 440.0, 554.37, 659.25))
        val top = d.chroma.indices.sortedByDescending { d.chroma[it] }.take(3).toSet()
        assertEquals(setOf(9, 1, 4), top)
        assertEquals(1f, d.chroma.sum(), 1e-3f)
    }

    @Test
    fun tracksOfOneArtistLeftForTheEndAreSpreadOut() {
        // Alike by closeness of index: the greedy pass runs 0, 1, 2… and is left with only the
        // three tracks of one artist at the end.
        val artists = List(12) { if (it >= 9) "same" else "other$it" }
        val order = ChainEngine.order(12, 0, 2, score = { a, b -> 1f - kotlin.math.abs(a - b) / 12f }, artists = artists)!!
        assertEquals(12, order.toSet().size)
        for (i in order.indices) for (j in maxOf(0, i - 2) until i) {
            assertTrue("$order", artists[order[i]] != artists[order[j]])
        }
    }

    @Test
    fun silenceHasNoDescription() {
        assertNull(EdgeFeatures.describe(FloatArray(rate * 4), 0, rate * 4, rate))
    }

    @Test
    fun similarEdgesScoreHigherThanDifferentOnes() {
        val aMajor = describe(tone(6, 220.0, 277.18, 329.63, seed = 1))
        val aMajorAgain = describe(tone(6, 220.0, 277.18, 329.63, seed = 2))
        val other = describe(tone(6, 233.08, 293.66, 349.23, 1_800.0, gain = 0.6f, noise = 0.1f))
        val same = EdgeFeatures.similarity(aMajor, aMajorAgain)
        val different = EdgeFeatures.similarity(aMajor, other)
        assertTrue("same $same", same > 0.95f)
        assertTrue("different $different vs same $same", different < same - 0.2f)
    }

    @Test
    fun levelDifferenceLowersTheScore() {
        val loud = describe(tone(4, 440.0, gain = 0.5f))
        val quiet = describe(tone(4, 440.0, gain = 0.05f))
        assertTrue(EdgeFeatures.similarity(loud, quiet) < EdgeFeatures.similarity(loud, loud))
    }

    @Test
    fun descriptorRoundTrips() {
        val d = describe(tone(3, 330.0))
        val back = EdgeDescriptor.fromArray(d.toArray())
        assertEquals(EdgeDescriptor.SIZE, d.toArray().size)
        assertEquals(d.loudnessDb, back.loudnessDb, 0f)
        assertEquals(d.chroma.toList(), back.chroma.toList())
    }

    /** A track whose edges are pure tones: chaining should follow matching frequencies. */
    private fun track(startHz: Double, endHz: Double, artist: String) =
        ChainTrack(describe(tone(3, startHz)), describe(tone(3, endHz)), artist)

    @Test
    fun chainFollowsMatchingEdges() {
        val tracks = listOf(
            track(440.0, 523.25, "a"), // 0: ends in C
            track(392.0, 440.0, "b"), // 1: starts G, ends A
            track(523.25, 392.0, "c"), // 2: starts C, ends G
        )
        assertEquals(listOf(0, 2, 1), ChainEngine.order(tracks, first = 0, artistSpacing = 0))
    }

    @Test
    fun chainAvoidsRepeatingAnArtist() {
        val tracks = listOf(
            track(440.0, 523.25, "same"),
            track(523.25, 523.25, "Same "), // best match but same artist (case and spaces ignored)
            track(587.33, 392.0, "other"),
        )
        assertEquals(listOf(0, 2, 1), ChainEngine.order(tracks, first = 0, artistSpacing = 1))
        assertEquals(listOf(0, 1, 2), ChainEngine.order(tracks, first = 0, artistSpacing = 0))
    }

    @Test
    fun unanalysedTracksGoLast() {
        val tracks = listOf(
            ChainTrack(null, null, "x"),
            track(440.0, 440.0, "a"),
            ChainTrack(null, null, "y"),
            track(440.0, 440.0, "b"),
        )
        assertEquals(listOf(1, 3, 0, 2), ChainEngine.order(tracks, first = 1, artistSpacing = 0))
    }
}
