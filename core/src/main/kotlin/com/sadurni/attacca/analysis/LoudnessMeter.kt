package com.sadurni.attacca.analysis

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.tan

/**
 * Integrated loudness of a whole track (EBU R128 / ITU-R BS.1770-4), and its sample peak.
 *
 * Each channel goes through the K-weighting filter (a high shelf for the head's effect, then a
 * high-pass), the weighted power is summed over channels in 400 ms blocks overlapping by 75 %,
 * and the blocks are gated: those below -70 LUFS are ignored, then those more than 10 LU below
 * the loudness of the rest. Up to 5.1 channels; surround channels (4 and 5) weigh 1.41.
 *
 * Kept as 100 ms sub-blocks, so a 4-minute track costs a few thousand numbers.
 */
class LoudnessMeter(private val sampleRate: Int, private val channels: Int) {

    private val shelf = Array(channels) { Biquad.highShelf(sampleRate) }
    private val highPass = Array(channels) { Biquad.highPass(sampleRate) }
    private val weights = DoubleArray(channels) { if (it == 3 || it == 4) 1.41 else 1.0 }

    private val subBlockFrames = maxOf(1, sampleRate / 10)
    private var subBlockEnergy = 0.0
    private var subBlockFill = 0
    private var subBlocks = DoubleArray(4096)
    private var subBlockCount = 0

    /** Largest absolute sample seen, before any weighting (1.0 = full scale). */
    var samplePeak = 0f
        private set

    /** Adds [frames] interleaved frames of [samples] (-1..1). */
    fun process(samples: FloatArray, frames: Int) {
        var i = 0
        for (f in 0 until frames) {
            var power = 0.0
            for (c in 0 until channels) {
                val x = samples[i++]
                val a = abs(x)
                if (a > samplePeak) samplePeak = a
                val y = highPass[c].process(shelf[c].process(x.toDouble()))
                power += weights[c] * y * y
            }
            subBlockEnergy += power
            if (++subBlockFill == subBlockFrames) closeSubBlock()
        }
    }

    private fun closeSubBlock() {
        if (subBlockCount == subBlocks.size) subBlocks = subBlocks.copyOf(subBlockCount * 2)
        subBlocks[subBlockCount++] = subBlockEnergy / subBlockFrames
        subBlockEnergy = 0.0
        subBlockFill = 0
    }

    /** Integrated loudness in LUFS, or null for a track shorter than one block or all silence. */
    fun integratedLufs(): Float? {
        // Overlapping 400 ms blocks: four consecutive 100 ms sub-blocks each.
        val blockCount = subBlockCount - 3
        if (blockCount <= 0) return null
        val blocks = DoubleArray(blockCount) { b -> (subBlocks[b] + subBlocks[b + 1] + subBlocks[b + 2] + subBlocks[b + 3]) / 4 }
        val absolute = blocks.filter { loudness(it) > ABSOLUTE_GATE_LUFS }
        if (absolute.isEmpty()) return null
        val relativeGate = loudness(absolute.average()) - RELATIVE_GATE_LU
        val gated = absolute.filter { loudness(it) > relativeGate }
        if (gated.isEmpty()) return null
        return loudness(gated.average()).toFloat()
    }

    private fun loudness(meanSquare: Double): Double = -0.691 + 10 * log10(meanSquare.coerceAtLeast(1e-20))

    /** A second-order filter in direct form I, coefficients normalized by a0. */
    private class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        fun process(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = x
            y2 = y1
            // Kept out of denormal numbers (silent channels).
            y1 = if (abs(y) < 1e-30) 0.0 else y
            return y
        }

        companion object {
            // BS.1770 K-weighting, designed for any sample rate (the standard gives 48 kHz values).
            fun highShelf(fs: Int): Biquad {
                val f0 = 1681.974450955533
                val gainDb = 3.999843853973347
                val q = 0.7071752369554196
                val k = tan(PI * f0 / fs)
                val vh = 10.0.pow(gainDb / 20)
                val vb = vh.pow(0.4996667741545416)
                val a0 = 1 + k / q + k * k
                return Biquad(
                    b0 = (vh + vb * k / q + k * k) / a0,
                    b1 = 2 * (k * k - vh) / a0,
                    b2 = (vh - vb * k / q + k * k) / a0,
                    a1 = 2 * (k * k - 1) / a0,
                    a2 = (1 - k / q + k * k) / a0,
                )
            }

            fun highPass(fs: Int): Biquad {
                val f0 = 38.13547087602444
                val q = 0.5003270373238773
                val k = tan(PI * f0 / fs)
                val a0 = 1 + k / q + k * k
                return Biquad(
                    b0 = 1.0,
                    b1 = -2.0,
                    b2 = 1.0,
                    a1 = 2 * (k * k - 1) / a0,
                    a2 = (1 - k / q + k * k) / a0,
                )
            }
        }
    }

    companion object {
        const val ABSOLUTE_GATE_LUFS = -70.0
        const val RELATIVE_GATE_LU = 10.0

        /** ReplayGain 2.0 plays every track at this loudness. */
        const val REFERENCE_LUFS = -18f

        /**
         * The loudness of a whole album from its tracks' (power average weighted by duration): close
         * to measuring the album as one stream.
         */
        fun albumLufs(tracks: List<Pair<Float, Long>>): Float? {
            val total = tracks.sumOf { it.second }
            if (tracks.isEmpty() || total <= 0) return null
            val power = tracks.sumOf { (lufs, ms) -> 10.0.pow(lufs / 10.0) * ms } / total
            return (10 * log10(power)).toFloat()
        }
    }
}
