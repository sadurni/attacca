package com.sadurni.attacca.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * Brick-wall peak limiter with look-ahead.
 *
 * Pipeline per frame: required gain (ceiling / peak) → sliding minimum over the look-ahead window
 * → instant attack / exponential release → moving average over the same window. Delaying the
 * audio by window-1 frames guarantees the averaged gain has fully reached the required reduction
 * when the peak comes out, so the output never exceeds the ceiling, while the averaging turns the
 * gain changes into smooth ramps instead of clicks.
 *
 * The peak is the true peak: besides the samples, the detector looks at three points between each
 * pair of them (4× oversampling with a short windowed sinc), where the waveform the converter
 * rebuilds can rise above both samples. The audio waits for the interpolator too, so each gain still
 * lines up with the sample it was worked out for.
 */
class LookaheadLimiter(
    sampleRate: Int,
    private val channels: Int,
    lookaheadMs: Float = 3f,
    releaseMs: Float = 80f,
) {
    private val window = maxOf(2, (sampleRate * lookaheadMs / 1000f).toInt())

    /** Audio delay introduced by the limiter. */
    val latencyFrames: Int = window - 1 + TRUE_PEAK_DELAY

    /** The last [TAPS] samples of each channel, for the true-peak detector. */
    private val history = FloatArray(TAPS * channels)
    private var historyPos = 0

    private val releaseCoef = 1f - exp(-1f / (sampleRate * releaseMs / 1000f))

    private val delay = FloatArray(latencyFrames * channels)
    private var delayPos = 0

    // Sliding minimum as a monotonic deque over a ring of (value, frame index).
    private val dequeValues = FloatArray(window)
    private val dequeIndex = LongArray(window)
    private var dequeHead = 0
    private var dequeSize = 0
    private var frameIndex = 0L

    private var releasedGain = 1f

    private val averageRing = FloatArray(window)
    private var averagePos = 0
    private var averageSum = window.toDouble()

    init {
        averageRing.fill(1f)
    }

    fun process(buffer: FloatArray, frames: Int, enabled: Boolean, ceiling: Float) {
        var base = 0
        for (f in 0 until frames) {
            val peak = truePeak(buffer, base)
            val required = if (enabled && peak > ceiling) ceiling / peak else 1f

            val minimum = pushAndGetMinimum(required)
            releasedGain = if (minimum < releasedGain) minimum else releasedGain + (minimum - releasedGain) * releaseCoef

            averageSum += releasedGain - averageRing[averagePos]
            averageRing[averagePos] = releasedGain
            averagePos = if (averagePos + 1 == window) 0 else averagePos + 1
            val gain = (averageSum / window).toFloat()

            val d = delayPos * channels
            for (ch in 0 until channels) {
                val delayed = delay[d + ch]
                delay[d + ch] = buffer[base + ch]
                var out = delayed * gain
                if (enabled) {
                    // Float rounding safety; the gain math already keeps |out| <= ceiling.
                    if (out > ceiling) out = ceiling else if (out < -ceiling) out = -ceiling
                }
                buffer[base + ch] = out
            }
            delayPos = if (delayPos + 1 == latencyFrames) 0 else delayPos + 1
            base += channels
        }
    }

    /**
     * Pushes the frame at [base] into the history and returns the highest level around the frame
     * [TRUE_PEAK_DELAY] earlier: that sample and the three points between it and the next one.
     */
    private fun truePeak(buffer: FloatArray, base: Int): Float {
        for (ch in 0 until channels) history[historyPos * channels + ch] = buffer[base + ch]
        historyPos = if (historyPos + 1 == TAPS) 0 else historyPos + 1
        var peak = 0f
        for (ch in 0 until channels) {
            // historyPos is now the oldest sample; tap j is the j-th oldest.
            val sample = abs(history[((historyPos + TRUE_PEAK_DELAY - 1) % TAPS) * channels + ch])
            if (sample > peak) peak = sample
            for (p in 0 until PHASES) {
                var sum = 0f
                var h = historyPos
                val c = p * TAPS
                for (j in 0 until TAPS) {
                    sum += COEFFICIENTS[c + j] * history[h * channels + ch]
                    h = if (h + 1 == TAPS) 0 else h + 1
                }
                val a = abs(sum)
                if (a > peak) peak = a
            }
        }
        return peak
    }

    private fun pushAndGetMinimum(value: Float): Float {
        val index = frameIndex++
        // Drop values that left the window (before pushing, so the ring never overflows).
        while (dequeSize > 0 && dequeIndex[dequeHead] <= index - window) {
            dequeHead = if (dequeHead + 1 == window) 0 else dequeHead + 1
            dequeSize--
        }
        // Drop values that can no longer be the minimum.
        while (dequeSize > 0 && dequeValues[wrap(dequeHead + dequeSize - 1)] >= value) {
            dequeSize--
        }
        val tail = wrap(dequeHead + dequeSize)
        dequeValues[tail] = value
        dequeIndex[tail] = index
        dequeSize++
        return dequeValues[dequeHead]
    }

    /** Index into the deque ring for 0 ≤ [i] < 2·window, without a division per frame. */
    private fun wrap(i: Int): Int = if (i >= window) i - window else i

    fun clear() {
        delay.fill(0f)
        history.fill(0f)
        historyPos = 0
        delayPos = 0
        dequeHead = 0
        dequeSize = 0
        frameIndex = 0L
        releasedGain = 1f
        averageRing.fill(1f)
        averagePos = 0
        averageSum = window.toDouble()
    }

    companion object {
        /** Taps of the interpolator: four samples each side of the points between two samples. */
        private const val TAPS = 8

        /** The points at 1/4, 1/2 and 3/4 of the way between two samples. */
        private const val PHASES = 3

        /** Frames the detector lags behind: the interpolated span sits in the middle of the taps. */
        private const val TRUE_PEAK_DELAY = TAPS / 2

        /** Hann-windowed sinc per phase, normalized so a steady level stays the same. */
        private val COEFFICIENTS = FloatArray(PHASES * TAPS).also { c ->
            for (p in 0 until PHASES) {
                val t = (p + 1) / (PHASES + 1.0)
                var sum = 0.0
                val row = DoubleArray(TAPS) { j ->
                    // Distance from tap j to the point between taps TAPS/2-1 and TAPS/2.
                    val x = (TAPS / 2 - 1 + t) - j
                    val sinc = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)
                    val w = 0.5 * (1 + cos(PI * x / (TAPS / 2)))
                    (sinc * w).also { sum += it }
                }
                for (j in 0 until TAPS) c[p * TAPS + j] = (row[j] / sum).toFloat()
            }
        }
    }
}
