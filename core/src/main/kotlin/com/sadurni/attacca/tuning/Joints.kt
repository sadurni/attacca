package com.sadurni.attacca.tuning

import com.sadurni.attacca.dsp.CrossfadePlan
import com.sadurni.attacca.dsp.CrossfadePlanner
import com.sadurni.attacca.dsp.CrossfadeShape
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/** Decoded audio: interleaved stereo floats at [rate]. */
class Pcm(val samples: FloatArray, val rate: Int) {
    val frames: Int get() = samples.size / 2

    companion object {
        /** [samples] of [channels] channels as stereo (the first two, or one on both sides). */
        fun stereo(samples: FloatArray, channels: Int, frames: Int, rate: Int): Pcm {
            val out = FloatArray(frames * 2)
            for (f in 0 until frames) {
                val l = samples[f * channels]
                out[f * 2] = l
                out[f * 2 + 1] = if (channels > 1) samples[f * channels + 1] else l
            }
            return Pcm(out, rate)
        }

        /** [frames] of [samples] ([channels] channels at [rate]) as stereo at [outRate]. */
        fun of(samples: FloatArray, channels: Int, frames: Int, rate: Int, outRate: Int): Pcm {
            val p = stereo(samples, channels, frames, rate)
            return if (rate == outRate) p else Pcm(Fragments.resample(p.samples, rate, outRate), outRate)
        }
    }
}

/** How the transitions guide joins two tracks: as the player would. */
data class JoinChoice(
    /** Blend length (0: one after the other, gapless). */
    val lengthMs: Int,
    /** True: the shape from the tracks' ends ([CrossfadePlanner]); false: a plain crossfade. */
    val automatic: Boolean,
    /** The silence after the first track's sound and before the second's left out. */
    val trim: Boolean,
)

/** A joint ready to loop, and where in it the change happens (frames), for the guide to show. */
class Joint(val samples: FloatArray, val rate: Int, val changeFrom: Int, val changeTo: Int) {
    val frames: Int get() = samples.size / 2
}

/**
 * The transitions guide's samples: the end of one real track joined to the start of the next
 * as the player would join them (blend length and shape, silences left out or kept), so a choice
 * is heard before it is made.
 */
object Joints {

    /** How much of the end of the first track and the start of the second is decoded (seconds). */
    const val TAIL_SECONDS = 26
    const val HEAD_SECONDS = 20

    /** What is heard before the change and after it (seconds). */
    private const val BEFORE_S = 7
    private const val AFTER_S = 8

    /** Silence threshold (dBFS) and the margin kept by the player's trims. */
    private const val SILENCE_DB = -60.0
    private const val MARGIN_MS = 100

    /** The last frame with sound of [pcm] (+ the trims' margin), or its end. */
    fun soundEnd(pcm: Pcm): Int {
        val threshold = Math.pow(10.0, SILENCE_DB / 20).toFloat()
        var f = pcm.frames - 1
        while (f > 0 && kotlin.math.abs(pcm.samples[f * 2]) < threshold && kotlin.math.abs(pcm.samples[f * 2 + 1]) < threshold) f--
        return minOf(pcm.frames, f + 1 + MARGIN_MS * pcm.rate / 1000)
    }

    /** The first frame with sound of [pcm] (− the trims' margin). */
    fun soundStart(pcm: Pcm): Int {
        val threshold = Math.pow(10.0, SILENCE_DB / 20).toFloat()
        var f = 0
        while (f < pcm.frames - 1 && kotlin.math.abs(pcm.samples[f * 2]) < threshold && kotlin.math.abs(pcm.samples[f * 2 + 1]) < threshold) f++
        return maxOf(0, f - MARGIN_MS * pcm.rate / 1000)
    }

    /** Silence after the sound of [tail] (the end of a file) in ms. */
    fun silenceAfterMs(tail: Pcm): Long = (tail.frames - soundEnd(tail)).toLong() * 1000 / tail.rate

    /** Silence before the sound of [head] (the start of a file) in ms. */
    fun silenceBeforeMs(head: Pcm): Long = soundStart(head).toLong() * 1000 / head.rate

    /** RMS level (dB) of [pcm] where it has sound: what both tracks are brought to the same level by. */
    fun levelDb(pcm: Pcm, from: Int = 0, to: Int = pcm.frames): Double {
        var sum = 0.0
        var n = 0
        for (f in from until to) {
            val l = pcm.samples[f * 2].toDouble()
            val r = pcm.samples[f * 2 + 1].toDouble()
            sum += (l * l + r * r) / 2
            n++
        }
        return 10 * log10(sum / max(1, n) + 1e-12)
    }

    /** A level per [binMs] of [pcm] (the loudest bin 1), as the player's waveforms. */
    fun waveform(pcm: Pcm, binMs: Int = 100): FloatArray {
        val bin = max(1, pcm.rate * binMs / 1000)
        val n = max(1, pcm.frames / bin)
        val out = FloatArray(n)
        for (b in 0 until n) {
            var sum = 0.0
            for (f in b * bin until minOf(pcm.frames, (b + 1) * bin)) {
                val l = pcm.samples[f * 2].toDouble()
                sum += l * l
            }
            out[b] = sqrt(sum / bin).toFloat()
        }
        val top = out.maxOrNull()?.takeIf { it > 0f } ?: 1f
        for (b in out.indices) out[b] /= top
        return out
    }

    /** The plan the player would make joining [tail] to [head] with [choice]. */
    fun plan(tail: Pcm, head: Pcm, choice: JoinChoice): CrossfadePlan? {
        if (choice.lengthMs <= 0) return null
        if (!choice.automatic) return CrossfadePlan(choice.lengthMs, CrossfadeShape.CROSSFADE)
        val tailMs = tail.frames.toLong() * 1000 / tail.rate
        val headMs = head.frames.toLong() * 1000 / head.rate
        val end = if (choice.trim) soundEnd(tail) else tail.frames
        val start = if (choice.trim) soundStart(head) else 0
        return CrossfadePlanner.plan(
            choice.lengthMs,
            waveform(tail), tailMs, end.toLong() * 1000 / tail.rate,
            waveform(head), headMs, start.toLong() * 1000 / head.rate,
        )
    }

    /**
     * [tail] (the end of a file) joined to [head] (the start of the next) with [choice]: a few
     * seconds before the change, the change, a few after; both at one level ([tail] and [head]
     * of the same rate). Each track is brought to −20 dB where it has sound, as levelled tracks are.
     */
    fun render(tail: Pcm, head: Pcm, choice: JoinChoice): Joint {
        val rate = tail.rate
        val end = if (choice.trim) soundEnd(tail) else tail.frames
        val start = if (choice.trim) soundStart(head) else 0
        val plan = plan(tail, head, choice)
        val blend = plan?.let { minOf(it.lengthMs.toLong() * rate / 1000, end.toLong(), (head.frames - start).toLong()).toInt() } ?: 0
        val shape = plan?.shape ?: CrossfadeShape.CROSSFADE
        // (Each track's level over what was decoded of it: a fade at its end is not lifted.)
        val gainA = gainTo(levelDb(tail, 0, maxOf(1, end - blend)))
        val gainB = gainTo(levelDb(head, start, head.frames))
        val from = maxOf(0, end - blend - BEFORE_S * rate)
        val after = minOf(head.frames - start - blend, AFTER_S * rate).coerceAtLeast(0)
        val total = (end - blend - from) + blend + after
        val out = FloatArray(total * 2)
        var o = 0
        for (f in from until end - blend) {
            out[o * 2] = tail.samples[f * 2] * gainA
            out[o * 2 + 1] = tail.samples[f * 2 + 1] * gainA
            o++
        }
        val changeFrom = o
        for (k in 0 until blend) {
            val t = (k + 0.5) / blend
            val ga = (shape.outGain(t) * gainA).toFloat()
            val gb = (shape.inGain(t) * gainB).toFloat()
            val a = (end - blend + k) * 2
            val b = (start + k) * 2
            out[o * 2] = tail.samples[a] * ga + head.samples[b] * gb
            out[o * 2 + 1] = tail.samples[a + 1] * ga + head.samples[b + 1] * gb
            o++
        }
        val changeTo = maxOf(o, changeFrom + rate / 2)
        for (k in 0 until after) {
            val b = (start + blend + k) * 2
            out[o * 2] = head.samples[b] * gainB
            out[o * 2 + 1] = head.samples[b + 1] * gainB
            o++
        }
        // The loop's own ends, so going round does not click.
        val fade = minOf(rate / 20, total / 4)
        for (i in 0 until fade) {
            val g = i.toFloat() / fade
            out[i * 2] *= g; out[i * 2 + 1] *= g
            out[(total - 1 - i) * 2] *= g; out[(total - 1 - i) * 2 + 1] *= g
        }
        return Joint(out, rate, changeFrom, minOf(changeTo, total))
    }

    private fun gainTo(levelDb: Double): Float {
        if (levelDb < -70) return 1f
        return Math.pow(10.0, ((-20.0 - levelDb).coerceIn(-24.0, 18.0)) / 20).toFloat()
    }
}
