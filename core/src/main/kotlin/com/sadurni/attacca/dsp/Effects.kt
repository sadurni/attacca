package com.sadurni.attacca.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Second-order IIR filter (RBJ audio EQ cookbook), transposed direct form II, with independent
 * state per channel.
 */
class Biquad(private val channels: Int) {
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    private val z1 = DoubleArray(channels)
    private val z2 = DoubleArray(channels)

    fun setPeaking(sampleRate: Int, freq: Double, q: Double, gainDb: Double) {
        val a = 10.0.pow(gainDb / 40)
        val w = 2 * PI * freq / sampleRate
        val alpha = sin(w) / (2 * q)
        set(1 + alpha * a, -2 * cos(w), 1 - alpha * a, 1 + alpha / a, -2 * cos(w), 1 - alpha / a)
    }

    fun setLowPass(sampleRate: Int, freq: Double, q: Double = SQRT1_2) {
        val w = 2 * PI * freq / sampleRate
        val alpha = sin(w) / (2 * q)
        val c = cos(w)
        set((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + alpha, -2 * c, 1 - alpha)
    }

    // Trigonometry of the last shelf frequency: dynamic bass moves only the gain, many times a second.
    private var shelfW = Double.NaN
    private var shelfCos = 0.0
    private var shelfAlpha = 0.0

    /** Shelf with half of [gainDb] at [freq] and all of it well below (slope 1). */
    fun setLowShelf(sampleRate: Int, freq: Double, gainDb: Double) {
        val a = 10.0.pow(gainDb / 40)
        val w = 2 * PI * freq / sampleRate
        if (w != shelfW) {
            shelfW = w
            shelfCos = cos(w)
            shelfAlpha = sin(w) / 2 * sqrt(2.0)
        }
        val c = shelfCos
        val alpha = shelfAlpha
        val sq = 2 * sqrt(a) * alpha
        set(
            a * ((a + 1) - (a - 1) * c + sq),
            2 * a * ((a - 1) - (a + 1) * c),
            a * ((a + 1) - (a - 1) * c - sq),
            (a + 1) + (a - 1) * c + sq,
            -2 * ((a - 1) + (a + 1) * c),
            (a + 1) + (a - 1) * c - sq,
        )
    }

    /**
     * First-order low shelf with [midFreq] where it gives half of [gainDb]: a gradual rise from
     * the low mids down, as the bass of a tone control.
     */
    fun setGentleLowShelf(sampleRate: Int, midFreq: Double, gainDb: Double) {
        val g = 10.0.pow(gainDb / 40)
        val k = tan(PI * midFreq / sampleRate)
        set(1 + g * k, g * k - 1, 0.0, 1 + k / g, k / g - 1, 0.0)
    }

    fun setHighPass(sampleRate: Int, freq: Double, q: Double = SQRT1_2) {
        val w = 2 * PI * freq / sampleRate
        val alpha = sin(w) / (2 * q)
        val c = cos(w)
        set((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + alpha, -2 * c, 1 - alpha)
    }

    internal fun setCoefficients(nb0: Double, nb1: Double, nb2: Double, na0: Double, na1: Double, na2: Double) =
        set(nb0, nb1, nb2, na0, na1, na2)

    /** Gain of the filter at [freq] (dB). */
    fun magnitudeDb(freq: Double, sampleRate: Int): Double {
        val w = 2 * PI * freq / sampleRate
        val c1 = cos(w)
        val s1 = sin(w)
        val c2 = cos(2 * w)
        val s2 = sin(2 * w)
        val numRe = b0 + b1 * c1 + b2 * c2
        val numIm = -(b1 * s1 + b2 * s2)
        val denRe = 1 + a1 * c1 + a2 * c2
        val denIm = -(a1 * s1 + a2 * s2)
        val mag2 = (numRe * numRe + numIm * numIm) / (denRe * denRe + denIm * denIm)
        return 10 * log10(mag2)
    }

    private fun set(nb0: Double, nb1: Double, nb2: Double, na0: Double, na1: Double, na2: Double) {
        b0 = nb0 / na0
        b1 = nb1 / na0
        b2 = nb2 / na0
        a1 = na1 / na0
        a2 = na2 / na0
    }

    fun process(input: Double, channel: Int): Double {
        val x = input + ANTI_DENORMAL
        val y = b0 * x + z1[channel]
        z1[channel] = b1 * x - a1 * y + z2[channel]
        z2[channel] = b2 * x - a2 * y
        return y
    }

    fun clear() {
        z1.fill(0.0)
        z2.fill(0.0)
    }

    companion object {
        const val SQRT1_2 = 0.7071067811865476
    }
}

/** 10-band graphic equalizer (one-octave peaking bands). */
class GraphicEq(private val sampleRate: Int, private val channels: Int) {
    private val bands = Array(BAND_FREQUENCIES.size) { Biquad(channels) }
    private var active = BooleanArray(BAND_FREQUENCIES.size)
    private var configuredGains: List<Float>? = null

    fun setGains(gains: List<Float>) {
        if (gains == configuredGains) return
        configuredGains = gains
        active = BooleanArray(BAND_FREQUENCIES.size)
        for (i in BAND_FREQUENCIES.indices) {
            val gain = gains.getOrElse(i) { 0f }
            val freq = BAND_FREQUENCIES[i]
            if (gain != 0f && freq < sampleRate * 0.45) {
                bands[i].setPeaking(sampleRate, freq.toDouble(), 1.41, gain.toDouble())
                active[i] = true
            }
        }
    }

    fun process(buffer: FloatArray, frames: Int) {
        for (b in bands.indices) {
            if (!active[b]) continue
            val filter = bands[b]
            var i = 0
            for (f in 0 until frames) {
                for (ch in 0 until channels) {
                    buffer[i] = filter.process(buffer[i].toDouble(), ch).toFloat()
                    i++
                }
            }
        }
    }

    fun clear() = bands.forEach { it.clear() }

    companion object {
        val BAND_FREQUENCIES = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
    }
}

/**
 * Bass boost, in two shapes (see [com.sadurni.attacca.settings.BassCurve]).
 *
 * Gentle: a first-order shelf with a steady boost of [maxGainDb]; the room it needs is made by a
 * fixed lowering of the whole level ([precutDb], applied with the channel matrix), and only as far
 * as the track's own headroom falls short, so the rest of the music keeps its level instead of
 * being pushed down by the limiter at every beat.
 *
 * Deep: a steep low shelf whose gain follows the bass level. While the bass stays under
 * [ceilingDb] even after boosting, the full [maxGainDb] applies; louder bass gets just enough
 * boost to reach the ceiling, and none once it is there.
 */
class DynamicBass(private val sampleRate: Int, private val channels: Int) {
    private val detector = Biquad(channels)
    private val shelf = Biquad(channels)
    private var configuredFreq = -1
    private var configuredGentle = false
    private var envelopeDb = -120.0
    private var gainDb = 0.0
    private var shelfGainDb = Double.NaN
    private var framesToUpdate = 0

    // Per block of UPDATE_FRAMES.
    private val attack = coefficient(5.0)
    private val release = coefficient(300.0)
    private val gainSmoothing = coefficient(30.0)

    private fun coefficient(ms: Double) = exp(-UPDATE_FRAMES / (sampleRate * ms / 1000.0))

    fun process(buffer: FloatArray, frames: Int, freqHz: Int, maxGainDb: Float, ceilingDb: Float, gentle: Boolean = false) {
        if (freqHz != configuredFreq || gentle != configuredGentle) {
            configuredFreq = freqHz
            configuredGentle = gentle
            detector.setLowPass(sampleRate, freqHz.toDouble())
            shelfGainDb = Double.NaN
        }
        var i = 0
        var blockPeak = 0.0
        for (f in 0 until frames) {
            for (ch in 0 until channels) blockPeak = maxOf(blockPeak, abs(detector.process(buffer[i + ch].toDouble(), ch)))

            // Level, target and filter are updated once per block; the filter runs per sample.
            if (--framesToUpdate <= 0) {
                framesToUpdate = UPDATE_FRAMES
                val levelDb = if (blockPeak > 1e-6) 20 * log10(blockPeak) else -120.0
                blockPeak = 0.0
                val coef = if (levelDb > envelopeDb) attack else release
                envelopeDb = coef * envelopeDb + (1 - coef) * levelDb
                val target = if (gentle) maxGainDb.toDouble() else (ceilingDb - envelopeDb).coerceIn(0.0, maxGainDb.toDouble())
                gainDb = gainSmoothing * gainDb + (1 - gainSmoothing) * target
                if (shelfGainDb.isNaN() || abs(gainDb - shelfGainDb) > 0.01) {
                    shelfGainDb = gainDb
                    if (gentle) shelf.setGentleLowShelf(sampleRate, freqHz.toDouble(), gainDb)
                    else shelf.setLowShelf(sampleRate, freqHz.toDouble(), gainDb)
                }
            }
            for (ch in 0 until channels) buffer[i + ch] = shelf.process(buffer[i + ch].toDouble(), ch).toFloat()
            i += channels
        }
    }

    /** Whether a boost is still being applied (it decays smoothly after switching off). */
    val isBoosting: Boolean get() = gainDb > 0.01

    fun clear() {
        detector.clear()
        shelf.clear()
        envelopeDb = -120.0
        gainDb = 0.0
        shelfGainDb = Double.NaN
        framesToUpdate = 0
    }

    companion object {
        private const val UPDATE_FRAMES = 16

        /**
         * How far the gentle boost lowers the level (dB, 0 or more): the boost minus the room the
         * track already leaves under full scale ([headroomDb], after its gain) and minus
         * [PEAK_ALLOWANCE_DB]. Boosting the bass raises the peaks by far less than the boost (on
         * real music 1.5 to 8 dB less), so lowering by the whole of it would push the rest of the
         * music down for nothing; the rare peak that still goes over is caught by the limiter.
         */
        fun precutDb(s: com.sadurni.attacca.settings.DspSettings, headroomDb: Float): Float =
            if (!s.bassEnabled || s.bassCurve != com.sadurni.attacca.settings.BassCurve.GENTLE) 0f
            else (s.bassMaxGainDb - headroomDb.coerceAtLeast(0f) - PEAK_ALLOWANCE_DB).coerceAtLeast(0f)

        const val PEAK_ALLOWANCE_DB = 2f

        /** The frequency of the curve chosen in [s]. */
        fun freqHz(s: com.sadurni.attacca.settings.DspSettings): Int =
            if (s.bassCurve == com.sadurni.attacca.settings.BassCurve.GENTLE) s.bassGentleFreqHz else s.bassFreqHz
    }
}

/**
 * Virtual bass: lets the ear hear bass a small speaker or earbud cannot play. The bass below
 * [cutoffHz] (both channels together) goes through a full-wave rectifier, which makes its even
 * harmonics (twice the frequency and up); those, band-limited to a few times the cutoff, are added
 * to the music. From the harmonics the ear infers the missing fundamental. The harmonics follow
 * the bass level, so it works the same at any volume.
 */
class VirtualBass(private val sampleRate: Int) {
    private val lowA = Biquad(1)
    private val lowB = Biquad(1)
    private val highA = Biquad(1)
    private val highB = Biquad(1)
    private val top = Biquad(1)
    private var configured = -1
    private var amount = 0.0

    /** Whether harmonics are still being added (they fade out after switching off). */
    val isActive: Boolean get() = amount > 1e-4

    fun process(buffer: FloatArray, frames: Int, channels: Int, cutoffHz: Int, targetAmount: Double) {
        if (cutoffHz != configured) {
            configured = cutoffHz
            lowA.setLowPass(sampleRate, cutoffHz.toDouble())
            lowB.setLowPass(sampleRate, cutoffHz.toDouble())
            // Keeps the harmonics, not the rectifier's DC nor the bass itself.
            highA.setHighPass(sampleRate, cutoffHz * 1.5)
            highB.setHighPass(sampleRate, cutoffHz * 1.5)
            top.setLowPass(sampleRate, minOf(cutoffHz * 5.0, sampleRate * 0.4))
        }
        val step = (targetAmount - amount) / frames
        var i = 0
        for (f in 0 until frames) {
            amount += step
            var sum = 0.0
            for (ch in 0 until channels) sum += buffer[i + ch]
            val bass = lowB.process(lowA.process(sum / channels, 0), 0)
            val harmonics = top.process(highB.process(highA.process(kotlin.math.abs(bass), 0), 0), 0)
            val add = (harmonics * amount * HARMONIC_GAIN).toFloat()
            for (ch in 0 until channels) buffer[i + ch] += add
            i += channels
        }
        amount = targetAmount
    }

    fun clear() {
        lowA.clear(); lowB.clear(); highA.clear(); highB.clear(); top.clear()
        amount = 0.0
    }

    private companion object {
        /** Rectified bass carries its second harmonic at about 0.42 of the bass level: brought up near it. */
        const val HARMONIC_GAIN = 2.0
    }
}

/**
 * Mid/side stereo width: 0 % mono, 100 % unchanged, up to 200 % wider. With [bassSafe] the side
 * signal below ~120 Hz is left alone so widening never thins out or smears the bass.
 */
class StereoWidth(sampleRate: Int) {
    // Only the side signal above the crossover is scaled. Subtracting a low-pass instead would
    // leak a lot of bass through the filter's phase shift.
    private val sideHighPass = Biquad(1).apply { setHighPass(sampleRate, 120.0) }

    /** Widens from [from] to [to] (1 = unchanged) across the buffer, so a change never clicks. */
    fun process(buffer: FloatArray, frames: Int, from: Double, to: Double, bassSafe: Boolean) {
        var w = from
        val step = (to - from) / frames
        var i = 0
        for (f in 0 until frames) {
            w += step
            val l = buffer[i].toDouble()
            val r = buffer[i + 1].toDouble()
            val mid = (l + r) / 2
            val side = (l - r) / 2
            val newSide = if (bassSafe) {
                side + (w - 1) * sideHighPass.process(side, 0)
            } else {
                side * w
            }
            buffer[i] = (mid + newSide).toFloat()
            buffer[i + 1] = (mid - newSide).toFloat()
            i += 2
        }
    }

    fun clear() = sideHighPass.clear()
}

/**
 * Feed-forward stereo-linked compressor with soft knee; gain smoothing in the dB domain with
 * separate attack and release (Giannoulis, Massberg & Reiss design).
 */
class Compressor(private val sampleRate: Int, private val channels: Int) {
    private var reductionDb = 0.0
    private var gain = 1.0
    private val attack = Smoothing()
    private val release = Smoothing()

    /** Smoothing coefficient for a time constant, kept while the setting and block size stay. */
    private inner class Smoothing {
        private var ms = Float.NaN
        private var frames = 0
        private var value = 0.0

        fun coefficient(ms: Float, frames: Int): Double {
            if (ms != this.ms || frames != this.frames) {
                this.ms = ms
                this.frames = frames
                value = exp(-frames / (sampleRate * ms / 1000.0))
            }
            return value
        }
    }

    /**
     * The level is measured and the gain computed once per block of [BLOCK_FRAMES] (0.4 ms at
     * 44.1 kHz), far shorter than any attack, with the gain ramped linearly across the block.
     * Logarithms per sample were the most expensive part of the whole chain.
     */
    fun process(
        buffer: FloatArray,
        frames: Int,
        thresholdDb: Float,
        ratio: Float,
        kneeDb: Float,
        attackMs: Float,
        releaseMs: Float,
        makeupDb: Float,
    ) {
        var f = 0
        while (f < frames) {
            val n = minOf(BLOCK_FRAMES, frames - f)
            val start = f * channels
            val end = (f + n) * channels
            var peak = 0f
            for (i in start until end) peak = maxOf(peak, abs(buffer[i]))
            val levelDb = if (peak > 1e-6f) 20 * log10(peak.toDouble()) else -120.0
            val target = staticCurve(levelDb, thresholdDb.toDouble(), ratio.toDouble(), kneeDb.toDouble()) - levelDb
            val coef = if (target < reductionDb) attack.coefficient(attackMs, n) else release.coefficient(releaseMs, n)
            reductionDb = coef * reductionDb + (1 - coef) * target
            val newGain = 10.0.pow((reductionDb + makeupDb) / 20)

            val step = ((newGain - gain) / n).toFloat()
            var g = gain.toFloat()
            var i = start
            for (k in 0 until n) {
                g += step
                for (ch in 0 until channels) buffer[i + ch] *= g
                i += channels
            }
            gain = newGain
            f += n
        }
    }

    fun clear() {
        reductionDb = 0.0
        gain = 1.0
    }

    companion object {
        private const val BLOCK_FRAMES = 16

        /** Output level for an input level, both in dB. */
        fun staticCurve(x: Double, threshold: Double, ratio: Double, knee: Double): Double {
            val over = x - threshold
            return when {
                2 * over < -knee -> x
                knee > 0 && 2 * abs(over) <= knee -> {
                    val t = over + knee / 2
                    x + (1 / ratio - 1) * t * t / (2 * knee)
                }
                else -> threshold + over / ratio
            }
        }
    }
}

