package com.sadurni.attacca.art

import com.sadurni.attacca.chain.Fft
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Which notes sound now (the 12 pitch classes, C, C#, … B), for Art-tacca's colours to follow the
 * chords. The audio side only keeps a quarter-rate mono copy of what goes out ([push], cheap); the
 * notes are worked out when asked ([at]), from the 0.37 s up to that moment.
 */
class NoteMeter {
    private val lock = Any()
    private val ring = FloatArray(RING)
    private var written = 0L
    private var rate = 0
    private var acc = 0f
    private var accN = 0
    private var lastUs = Long.MIN_VALUE
    private var lastGivenUs = Long.MIN_VALUE
    private var nextUs = Long.MIN_VALUE

    private val fft = Fft(SIZE)
    private val re = FloatArray(SIZE)
    private val im = FloatArray(SIZE)
    private val window = FloatArray(SIZE) { (0.5 - 0.5 * cos(2 * PI * it / (SIZE - 1))).toFloat() }

    /** Adds [frames] frames of interleaved [samples] heard from [timeUs] on (or [LevelMeterUnknown] to carry on). */
    fun push(samples: FloatArray, frames: Int, channels: Int, sampleRate: Int, timeUs: Long) {
        if (frames <= 0 || channels <= 0 || sampleRate <= 0) return
        synchronized(lock) {
            if (sampleRate != rate) {
                rate = sampleRate
                written = 0
                accN = 0
                acc = 0f
            }
            if (timeUs != LevelMeterUnknown && timeUs != lastGivenUs) {
                nextUs = timeUs
                lastGivenUs = timeUs
            }
            if (nextUs == Long.MIN_VALUE) return
            for (f in 0 until frames) {
                var x = 0f
                val base = f * channels
                for (c in 0 until channels) x += samples[base + c]
                acc += x / channels
                if (++accN == DECIMATE) {
                    ring[(written % RING).toInt()] = acc / DECIMATE
                    written++
                    acc = 0f
                    accN = 0
                }
            }
            nextUs += frames * 1_000_000L / sampleRate
            lastUs = nextUs
        }
    }

    fun clear() = synchronized(lock) {
        written = 0
        accN = 0
        nextUs = Long.MIN_VALUE
        lastGivenUs = Long.MIN_VALUE
        lastUs = Long.MIN_VALUE
    }

    /** The share of each pitch class heard up to the player's time [timeUs], into [out] (12); false when unknown or silent. */
    fun at(timeUs: Long, out: FloatArray): Boolean {
        val local = FloatArray(SIZE)
        val r: Int
        synchronized(lock) {
            r = rate
            if (r <= 0 || lastUs == Long.MIN_VALUE || written < SIZE) return false
            val rateLow = r / DECIMATE.toDouble()
            val back = ((lastUs - timeUs) / 1_000_000.0 * rateLow).roundToInt().coerceAtLeast(0)
            val end = written - back
            if (end < SIZE || written - end > RING - SIZE) return false
            for (i in 0 until SIZE) local[i] = ring[((end - SIZE + i) % RING).toInt()]
        }
        var energy = 0f
        for (i in 0 until SIZE) {
            re[i] = local[i] * window[i]
            im[i] = 0f
            energy += local[i] * local[i]
        }
        if (energy / SIZE < 1e-6f) return false
        fft.transform(re, im)
        out.fill(0f)
        val hzPerBin = r / DECIMATE.toDouble() / SIZE
        for (k in 1 until SIZE / 2) {
            val hz = k * hzPerBin
            if (hz < LOW_HZ || hz > HIGH_HZ) continue
            val midi = 69 + 12 * ln(hz / 440.0) / ln(2.0)
            val pc = ((midi.roundToInt() % 12) + 12) % 12
            out[pc] += sqrt(re[k] * re[k] + im[k] * im[k])
        }
        val total = out.sum()
        if (total <= 0f) return false
        for (i in 0 until 12) out[i] /= total
        return true
    }

    companion object {
        private const val DECIMATE = 4
        private const val SIZE = 4096
        private const val RING = 16384
        private const val LOW_HZ = 65.0
        private const val HIGH_HZ = 2100.0

        /** The same "carry on" time as the level meter's. */
        const val LevelMeterUnknown = Long.MIN_VALUE
    }
}
