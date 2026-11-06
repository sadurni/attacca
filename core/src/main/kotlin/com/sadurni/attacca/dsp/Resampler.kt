package com.sadurni.attacca.dsp

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Changes interleaved float audio from [inRate] to [outRate], streaming: blocks go in as they are
 * decoded and what can be computed comes out. A windowed-sinc filter (Kaiser window) with one row
 * of coefficients per phase of the ratio between the rates (the nearest of [MAX_PHASES] for
 * unusual ratios); below the lower Nyquist frequency of the two, about 85 dB of rejection. At the
 * edges the music is mirrored instead of meeting silence, so neither the start nor the end dips.
 * With equal rates it only passes the frames through.
 */
class Resampler(val inRate: Int, val outRate: Int, val channels: Int) {

    private val num: Int
    private val den: Int
    private val rows: Int
    private val taps: Int
    private val half: Int
    private val table: FloatArray

    private var buf = FloatArray(0)
    private var bufFrames = 0
    private var pos = 0
    private var phase = 0
    private var padPending = true
    private var out = FloatArray(0)

    init {
        if (inRate == outRate) {
            num = 1; den = 1; rows = 1; taps = 0; half = 0; table = FloatArray(0)
        } else {
            val g = gcd(inRate, outRate)
            num = inRate / g
            den = outRate / g
            rows = minOf(den, MAX_PHASES)
            val scale = minOf(1.0, outRate.toDouble() / inRate)
            val cutoff = CUTOFF * scale
            val reach = ZERO_CROSSINGS / scale
            half = ceil(reach).toInt()
            taps = 2 * half
            table = FloatArray(rows * taps)
            val i0Beta = besselI0(KAISER_BETA)
            val row = DoubleArray(taps)
            for (j in 0 until rows) {
                val frac = j.toDouble() / rows
                var sum = 0.0
                for (k in 0 until taps) {
                    val d = (k - half + 1) - frac
                    val x = d / reach
                    val window = if (x <= -1.0 || x >= 1.0) 0.0 else besselI0(KAISER_BETA * sqrt(1 - x * x)) / i0Beta
                    val arg = PI * cutoff * d
                    row[k] = cutoff * (if (arg == 0.0) 1.0 else sin(arg) / arg) * window
                    sum += row[k]
                }
                for (k in 0 until taps) table[j * taps + k] = (row[k] / sum).toFloat()
            }
        }
        reset()
    }

    /** Forgets what it holds (after a seek). */
    fun reset() {
        bufFrames = maxOf(0, half - 1)
        ensure(bufFrames + 4096)
        java.util.Arrays.fill(buf, 0, bufFrames * channels, 0f)
        pos = bufFrames
        phase = 0
        padPending = taps > 0
    }

    /** Takes [frames] interleaved frames of [src]; [sink] gets what comes out (array, frames). */
    fun process(src: FloatArray, frames: Int, sink: (FloatArray, Int) -> Unit) {
        if (taps == 0) {
            sink(src, frames)
            return
        }
        ensure(bufFrames + frames)
        System.arraycopy(src, 0, buf, bufFrames * channels, frames * channels)
        bufFrames += frames
        if (padPending) {
            padPending = false
            reflect(pos - 1, pos, -1)
        }
        emit(bufFrames, sink)
    }

    /** The end of the stream: what the filter still holds, with the music mirrored past the end. */
    fun finish(sink: (FloatArray, Int) -> Unit) {
        if (taps == 0 || pos >= bufFrames) return
        val end = bufFrames
        ensure(end + half)
        reflect(end, end - 1, 1)
        bufFrames = end + half
        emit(bufFrames, sink)
    }

    private fun emit(available: Int, sink: (FloatArray, Int) -> Unit) {
        val c = channels
        val maxOut = ((available - pos).toLong() * den / num + 2).toInt().coerceAtLeast(0) * c
        if (out.size < maxOut) out = FloatArray(maxOut)
        var o = 0
        while (pos + half < available) {
            val row = (if (den <= MAX_PHASES) phase else (phase.toLong() * rows / den).toInt()) * taps
            val first = (pos - half + 1) * c
            for (ch in 0 until c) {
                var s = 0f
                var i = first + ch
                for (k in 0 until taps) {
                    s += buf[i] * table[row + k]
                    i += c
                }
                out[o++] = s
            }
            phase += num
            pos += phase / den
            phase %= den
        }
        if (o > 0) sink(out, o / c)
        val drop = pos - maxOf(0, half - 1)
        if (drop > 0) {
            System.arraycopy(buf, drop * c, buf, 0, (bufFrames - drop) * c)
            bufFrames -= drop
            pos -= drop
        }
    }

    private fun reflect(first: Int, pivot: Int, direction: Int) {
        val c = channels
        for (k in 0 until half) {
            val target = first + k * direction
            if (target < 0) break
            val source = (pivot - (k + 1) * direction).coerceIn(0, bufFrames - 1)
            for (ch in 0 until c) buf[target * c + ch] = 2 * buf[pivot * c + ch] - buf[source * c + ch]
        }
    }

    private fun ensure(frames: Int) {
        if (buf.size < frames * channels) buf = buf.copyOf(maxOf(frames, bufFrames * 2) * channels)
    }

    private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

    private fun besselI0(x: Double): Double {
        var sum = 1.0
        var term = 1.0
        var k = 1
        while (term > 1e-12 * sum) {
            val h = x / (2 * k)
            term *= h * h
            sum += term
            k++
        }
        return sum
    }

    private companion object {
        const val ZERO_CROSSINGS = 32.0
        const val CUTOFF = 0.92
        const val KAISER_BETA = 8.5
        const val MAX_PHASES = 1024
    }
}
