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
