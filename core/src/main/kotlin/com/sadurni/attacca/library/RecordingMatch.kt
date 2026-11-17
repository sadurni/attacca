package com.sadurni.attacca.library

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Whether two files hold the same recording, by their sound: the level of half a minute from the
 * middle of one, followed every 20 ms, against the other's at every offset within a few seconds.
 * The same recording (another edition, a remaster, a compilation) follows it closely (measured up
 * to 1.0); a live version, even of the same length, does not (at most 0.35). Between them a few
 * remasters (0.55 … 0.69) and versions sharing the backing track (a radio session, a remix: around
 * 0.6) cannot be told apart: [SAME] keeps those apart, so a list may hold a copy too many, never
 * leave out another version.
 */
object RecordingMatch {

    /** Level values per second. */
    const val PER_SECOND = 50

    /** Seconds compared. */
    const val WINDOW_S = 30

    /** How far the other may be shifted (an edition with more silence at its start), seconds. */
    const val SEARCH_S = 6

    /** Seconds to decode of each: the window and the search on both sides. */
    const val SPAN_S = WINDOW_S + 2 * SEARCH_S

    /** From this likeness on, the same recording. */
    const val SAME = 0.70f

    /** Where to start decoding a track of [durationMs] (its middle, as far as it goes). */
    fun startMs(durationMs: Long): Long = max(0L, durationMs / 2 - SPAN_S * 1000L / 2)

    /** The level (log of the mean square) of [frames] interleaved frames from [from], every 1/[PER_SECOND] s. */
    fun envelope(samples: FloatArray, from: Int, frames: Int, channels: Int, rate: Int): FloatArray {
        val hop = max(1, rate / PER_SECOND)
        val count = frames / hop
        return FloatArray(count) { k ->
            var sum = 0.0
            val base = from + k * hop
            for (f in base until base + hop) {
                var m = 0.0
                for (c in 0 until channels) m += samples[f * channels + c]
                m /= channels
                sum += m * m
            }
            ln(sqrt(sum / hop) + 1e-6).toFloat()
        }
    }

    /** How closely [b] follows [a]'s middle window at its best offset (−1 … 1); NaN when either is too short. */
    fun likeness(a: FloatArray, b: FloatArray): Float {
        val n = WINDOW_S * PER_SECOND
        val s = SEARCH_S * PER_SECOND
        if (a.size < s + n || b.size < n) return Float.NaN
        val ref = standard(a, s, n) ?: return Float.NaN
        var best = -1.0
        var lag = 0
        while (lag + n <= b.size && lag <= 2 * s) {
            val z = standard(b, lag, n)
            if (z != null) {
                var c = 0.0
                for (i in 0 until n) c += ref[i] * z[i]
                best = max(best, c / n)
            }
            lag++
        }
        return best.toFloat()
    }

    private fun standard(x: FloatArray, from: Int, n: Int): DoubleArray? {
        var mean = 0.0
        for (i in from until from + n) mean += x[i]
        mean /= n
        var v = 0.0
        for (i in from until from + n) v += (x[i] - mean) * (x[i] - mean)
        val sd = sqrt(v / n)
        if (sd < 1e-6) return null
        return DoubleArray(n) { (x[from + it] - mean) / sd }
    }
}
