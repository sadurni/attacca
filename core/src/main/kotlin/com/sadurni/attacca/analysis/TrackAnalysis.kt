package com.sadurni.attacca.analysis

import kotlin.math.max
import kotlin.math.sqrt

/** Result of decoding a file once: where the audible part is, its waveform and its loudness. */
class TrackAnalysis(
    val durationMs: Long,
    /** First and last audible instants (above the silence threshold). */
    val audioStartMs: Long,
    val audioEndMs: Long,
    /** Loudness per bin across the whole file, normalized so the loudest bin is 1. */
    val waveform: FloatArray,
    /** Threshold the bounds were computed with; a different setting needs a new analysis. */
    val thresholdDb: Float,
    /** Integrated loudness (EBU R128), null when it could not be measured (silent, too short). */
    val loudnessLufs: Float? = null,
    /** Largest absolute sample, 1.0 = full scale. */
    val samplePeak: Float = 0f,
) {
    /** A copy with the loudness measured in the same decoding. */
    fun withLoudness(lufs: Float?, peak: Float): TrackAnalysis =
        TrackAnalysis(durationMs, audioStartMs, audioEndMs, waveform, thresholdDb, lufs, peak)

    val leadingSilenceMs: Long get() = audioStartMs
    val trailingSilenceMs: Long get() = (durationMs - audioEndMs).coerceAtLeast(0)

    /** Where the music really starts: after a faint lead-in ([FaintLead]) if there is one, else at [audioStartMs]. */
    val musicStartMs: Long get() = FaintLead.endMs(waveform, durationMs, audioStartMs) ?: audioStartMs

    companion object {
        const val WAVEFORM_BINS = 512

        /** Window used for silence detection and waveform accumulation. */
        const val WINDOW_MS = 10

        /**
         * Builds the analysis from per-window peak and mean-square values.
         *
         * @param windowPeaks max |sample| of each [WINDOW_MS] window, 0..1.
         * @param windowMeanSquares mean of squared samples of each window.
         */
        fun fromWindows(
            windowPeaks: FloatArray,
            windowMeanSquares: FloatArray,
            windowCount: Int,
            durationMs: Long,
            thresholdDb: Float,
        ): TrackAnalysis {
            val threshold = dbToLinear(thresholdDb)
            val bounds = SilenceDetector.audibleWindows(windowPeaks, windowCount, threshold)
            val (startMs, endMs) = if (bounds == null) {
                // Entirely silent: nothing to trim safely.
                0L to durationMs
            } else {
                bounds.first.toLong() * WINDOW_MS to
                    minOf(durationMs, (bounds.last + 1).toLong() * WINDOW_MS)
            }
            return TrackAnalysis(
                durationMs = durationMs,
                audioStartMs = startMs,
                audioEndMs = endMs,
                waveform = waveform(windowMeanSquares, windowCount),
                thresholdDb = thresholdDb,
            )
        }

        /** RMS per bin, normalized to the loudest bin. */
        fun waveform(meanSquares: FloatArray, count: Int): FloatArray {
            val bins = FloatArray(WAVEFORM_BINS)
            if (count == 0) return bins
            for (b in 0 until WAVEFORM_BINS) {
                val from = (b.toLong() * count / WAVEFORM_BINS).toInt()
                val to = max(from + 1, ((b + 1).toLong() * count / WAVEFORM_BINS).toInt()).coerceAtMost(count)
                var sum = 0.0
                for (i in from until to) sum += meanSquares[i]
                bins[b] = sqrt(sum / (to - from)).toFloat()
            }
            val loudest = bins.max()
            if (loudest > 0f) for (b in bins.indices) bins[b] /= loudest
            return bins
        }
    }
}

/**
 * A faint lead-in: the first seconds of a track barely there (hiss, a far-off sound rising, far
 * under the track's loudest part) and then the music coming in at once at full strength, as a song
 * entering after a few seconds that can hardly be heard. Such a start counts as silence for the
 * trims and blends. A quiet intro that grows into the song is not one (it is heard, and it does not
 * jump), nor is a lead-in shorter than [MIN_LEAD_MS] or longer than [MAX_LEAD_MS].
 */
object FaintLead {
    /** Under this (of the loudest stretch, ~ −32 dB) a stretch is barely there. */
    const val FAINT = 0.025f

    /** The music coming in reaches this at once (~ −18 dB of the loudest). */
    const val LOUD = 0.12f

    const val MIN_LEAD_MS = 1500L
    const val MAX_LEAD_MS = 30_000L

    /**
     * Where the music comes in after a faint lead-in starting at [soundStartMs], from the track's
     * [waveform] (its loudness in even stretches, the loudest 1), or null when it does not start so.
     */
    fun endMs(waveform: FloatArray?, durationMs: Long, soundStartMs: Long): Long? {
        if (waveform == null || waveform.size < 16 || durationMs <= 0L) return null
        val n = waveform.size
        val binMs = durationMs.toDouble() / n
        var j = (soundStartMs / binMs).toInt().coerceIn(0, n - 1)
        while (j < n && waveform[j] <= FAINT) j++
        if (j >= n) return null
        // At once: the first stretch over faint (or the next, the music coming in within it) is loud.
        if (waveform[j] < LOUD && (j + 1 >= n || waveform[j + 1] < LOUD)) return null
        val end = (j * binMs).toLong()
        return end.takeIf { it - soundStartMs in MIN_LEAD_MS..MAX_LEAD_MS }
    }
}

object SilenceDetector {

    /** Index range of windows from the first to the last one whose peak exceeds [threshold]. */
    fun audibleWindows(peaks: FloatArray, count: Int, threshold: Float): IntRange? {
        var first = -1
        for (i in 0 until count) {
            if (peaks[i] > threshold) {
                first = i
                break
            }
        }
        if (first < 0) return null
        var last = first
        for (i in count - 1 downTo first) {
            if (peaks[i] > threshold) {
                last = i
                break
            }
        }
        return first..last
    }
}

fun dbToLinear(db: Float): Float = Math.pow(10.0, db / 20.0).toFloat()
