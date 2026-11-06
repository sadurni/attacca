package com.sadurni.attacca.dsp

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The end of a track, decoded ahead of time so it can be blended into the next one.
 *
 * A crossfaded track is clipped where the blend starts; the player never decodes what follows
 * the clip. That part is decoded in the background instead, so at the transition the next track
 * can start at once instead of waiting for seconds of audio to be read first.
 */
class CrossfadeTail(
    /** Interleaved float PCM as decoded, before ReplayGain. */
    val samples: FloatArray,
    val frames: Int,
    val channels: Int,
    val sampleRate: Int,
    /** Frame of [samples] where the clip ends, i.e. the first frame the player does not play. */
    val clipEndFrame: Int,
) {
    /** How the levels move in the blend; set where the tail is made, before it is published. */
    @Volatile
    var shape: CrossfadeShape = CrossfadeShape.CROSSFADE

    /** The file it is the end of (for the transition log). */
    @Volatile
    var source: String? = null

    /**
     * The channels averaged, to line the tail up with what was played: made where the tail is
     * made (in the background), so the start of a blend does little on the audio thread.
     */
    val mono: FloatArray = FloatArray(frames).also { mono ->
        for (f in 0 until frames) {
            var sum = 0f
            for (ch in 0 until channels) sum += samples[f * channels + ch]
            mono[f] = sum / channels
        }
    }
}

/**
 * Where the music was when the user skipped to another track: [tail] from [startFrame] on is
 * blended into the track skipped to, scaled by the [gain] the left track had.
 */
class SkipTail(
    val tail: CrossfadeTail,
    val startFrame: Int,
    val gain: Float,
    val shape: CrossfadeShape,
    /** Frames of [tail] to blend from [startFrame]. */
    val frames: Int = tail.frames - startFrame,
    val createdAtNanos: Long = System.nanoTime(),
)

/** Lines up a [CrossfadeTail] with what the player actually played. */
object TailAligner {

    private const val DECIMATION = 8
    private const val MIN_COARSE_CORRELATION = 0.7
    private const val MIN_FINE_CORRELATION = 0.9
    private const val CANDIDATE_MARGIN = 0.07
    private const val MAX_CANDIDATES = 8

    /** Frames searched around each coarse peak: decimation blurs where a fast tone peaks. */
    private const val FINE_RADIUS = 16 * DECIMATION

    /** Fine matches this close to the best one are as good; the one nearest [expected] wins. */
    private const val NEAR_TIE = 0.002
    private const val SAME_PLACE_FRAMES = 2048

    /** Where the tail goes on ([frame]) and how well it matched ([score]; [NO_MATCH] when the expected place was used). */
    class Match(val frame: Int, val score: Double)

    const val NO_MATCH = -1.0

    fun align(history: FloatArray, historyFrames: Int, tail: FloatArray, tailFrames: Int, expected: Int, searchFrames: Int): Int =
        match(history, historyFrames, tail, tailFrames, expected, searchFrames).frame

    /**
     * Returns the frame of [tail] (mono) that follows the last played frame.
     *
     * The background decoder and the player do not agree to the sample on where the clip ends
     * (encoder delay, inexact seeking), and any mismatch would be heard as a skip or a repeat. So
     * the last played frames ([history], mono, oldest first) are searched for in the tail within
     * [searchFrames] of [expected]: first on a decimated signal, then sample by sample. When the
     * audio gives nothing to match (silence), [expected] is used.
     */
    fun match(history: FloatArray, historyFrames: Int, tail: FloatArray, tailFrames: Int, expected: Int, searchFrames: Int): Match {
        val unmatched = Match(expected, NO_MATCH)
        val n = historyFrames / DECIMATION
        if (n < 64) return unmatched
        val h = decimate(history, historyFrames - n * DECIMATION, n)
        val m = tailFrames / DECIMATION
        val t = decimate(tail, 0, m)

        // Prefix sums of squares for the energy of every tail window.
        val energy = DoubleArray(m + 1)
        for (i in 0 until m) energy[i + 1] = energy[i] + t[i] * t[i]
        var historyEnergy = 0.0
        for (x in h) historyEnergy += x * x
        if (historyEnergy < 1e-9) return unmatched

        val from = maxOf(n, (expected - searchFrames) / DECIMATION)
        val to = minOf(m, (expected + searchFrames) / DECIMATION)
        if (from > to) return unmatched
        val scores = DoubleArray(to - from + 1) { -1.0 }
        var bestScore = -1.0
        for (end in from..to) {
            val e = energy[end] - energy[end - n]
            if (e < 1e-9) continue
            var dot = 0.0
            val offset = end - n
            for (i in 0 until n) dot += h[i] * t[offset + i]
            val score = dot / sqrt(historyEnergy * e)
            scores[end - from] = score
            if (score > bestScore) bestScore = score
        }
        if (bestScore < MIN_COARSE_CORRELATION) return unmatched

        // Repetitive audio (a held note) matches almost as well one period away, so the best
        // few coarse peaks are all refined at full rate.
        val peaks = (from..to)
            .filter { end ->
                val score = scores[end - from]
                score >= bestScore - CANDIDATE_MARGIN &&
                    score >= scores.getOrElse(end - from - 1) { -1.0 } &&
                    score >= scores.getOrElse(end - from + 1) { -1.0 }
            }
        // The best peaks, plus the good ones nearest to the expected place (a loop matches as well
        // a bar away, and decimation lowers some of its repetitions a little).
        val candidates = (
            peaks.sortedByDescending { scores[it - from] }.take(MAX_CANDIDATES) +
                peaks.sortedBy { abs(it * DECIMATION - expected) }.take(MAX_CANDIDATES)
            ).distinct()

        var hEnergy = 0.0
        for (i in 0 until historyFrames) hEnergy += history[i] * history[i]
        // The best frame around each coarse candidate.
        val fine = ArrayList<Pair<Int, Double>>()
        for (coarse in candidates) {
            var peak = -1
            var peakScore = MIN_FINE_CORRELATION
            for (k in coarse * DECIMATION - FINE_RADIUS..coarse * DECIMATION + FINE_RADIUS) {
                if (k - historyFrames < 0 || k > tailFrames) continue
                var dot = 0.0
                var e = 0.0
                for (i in 0 until historyFrames) {
                    val y = tail[k - historyFrames + i]
                    dot += history[i] * y
                    e += y * y
                }
                if (e < 1e-9) continue
                val score = dot / sqrt(hEnergy * e)
                if (score > peakScore) {
                    peakScore = score
                    peak = k
                }
            }
            if (peak >= 0) fine.add(peak to peakScore)
        }
        val bestFine = fine.maxOfOrNull { it.second } ?: return unmatched
        // A loop repeated note for note matches as well a bar away: of the equally good places,
        // the nearest to the expected one. Around it, the best frame (a held tone matches almost
        // as well one period away, which only the exact score tells apart).
        val chosen = fine.filter { it.second >= bestFine - NEAR_TIE }.minBy { abs(it.first - expected) }.first
        val best = fine.filter { abs(it.first - chosen) < SAME_PLACE_FRAMES }.maxBy { it.second }
        return Match(best.first, best.second)
    }

    private fun decimate(x: FloatArray, start: Int, count: Int): FloatArray {
        val out = FloatArray(count)
        for (i in 0 until count) {
            var sum = 0f
            val base = start + i * DECIMATION
            for (j in 0 until DECIMATION) sum += x[base + j]
            out[i] = sum / DECIMATION
        }
        return out
    }
}

/** Format conversion for the rare blend between tracks of different sample rate or layout. */
object PcmConvert {

    /** Converts interleaved [frames] to [toChannels] at [toRate] (linear interpolation). */
    fun convert(src: FloatArray, frames: Int, fromChannels: Int, fromRate: Int, toChannels: Int, toRate: Int): FloatArray {
        val mapped = if (fromChannels == toChannels) src else remap(src, frames, fromChannels, toChannels)
        if (fromRate == toRate) return if (mapped === src) src.copyOf(frames * toChannels) else mapped
        val outFrames = (frames.toLong() * toRate / fromRate).toInt()
        val out = FloatArray(outFrames * toChannels)
        val step = fromRate.toDouble() / toRate
        for (f in 0 until outFrames) {
            val pos = f * step
            val i = pos.toInt()
            val frac = (pos - i).toFloat()
            val j = minOf(i + 1, frames - 1)
            for (ch in 0 until toChannels) {
                val a = mapped[i * toChannels + ch]
                val b = mapped[j * toChannels + ch]
                out[f * toChannels + ch] = a + (b - a) * frac
            }
        }
        return out
    }

    private fun remap(src: FloatArray, frames: Int, from: Int, to: Int): FloatArray {
        val out = FloatArray(frames * to)
        for (f in 0 until frames) {
            if (from == 1) {
                for (ch in 0 until to) out[f * to + ch] = src[f]
            } else if (to == 1) {
                var sum = 0f
                for (ch in 0 until from) sum += src[f * from + ch]
                out[f] = sum / from
            } else {
                for (ch in 0 until minOf(from, to)) out[f * to + ch] = src[f * from + ch]
            }
        }
        return out
    }
}
