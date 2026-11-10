package com.sadurni.attacca.tuning

import com.sadurni.attacca.chain.TrackFeatures
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * A stretch of music ready to loop in the sound guide: interleaved stereo floats at [sampleRate],
 * brought to one level so every sample sounds as loud as the others, its ends faded.
 */
class Fragment(
    val samples: FloatArray,
    val sampleRate: Int,
    /** What it is ("Artist — Title"), or null for the guide's own groove. */
    val title: String?,
    /** Room under full scale at its loudest (dB), for the bass boost's precut. */
    val headroomDb: Float,
) {
    val frames: Int get() = samples.size / 2
}

/**
 * Finding the stretch of the library each step of the sound guide is best heard on: tracks with
 * plenty of what the step changes ([FragmentKind]), and in them the twelve seconds that have most of
 * it.
 */
object Fragments {

    /** Length of a fragment (seconds). */
    const val SECONDS = 12

    /** How much of a track is decoded to look for its best fragment (seconds), and from where (share of its length). */
    const val SEARCH_SECONDS = 36
    const val SEARCH_FROM = 0.3

    /** The level fragments are brought to (RMS, dBFS). */
    private const val TARGET_RMS_DB = -20.0

    /**
     * The order to try tracks in for [kind], best first, from their analysis features (tracks
     * without them go last, as they come). [features] is null for a track not analysed.
     */
    fun <T> order(kind: FragmentKind, tracks: List<T>, features: (T) -> TrackFeatures?): List<T> {
        val score: (TrackFeatures) -> Float = when (kind) {
            FragmentKind.BASS -> { f -> f.bassDb - f.loudnessDb }
            FragmentKind.BRIGHT -> { f -> f.brightness - 0.2f * f.noisiness }
            FragmentKind.DYNAMIC -> { f -> f.dynamicsDb }
            // Width is not in the features: louder, busier tracks first, the measure decides.
            FragmentKind.WIDE -> { f -> f.percussiveness * 0.2f - kotlin.math.abs(f.loudnessDb + 14f) * 0.05f }
            FragmentKind.ANY -> { f -> -kotlin.math.abs(f.loudnessDb + 12f) + f.voice * 2f }
        }
        val (known, unknown) = tracks.partition { features(it) != null }
        return known.sortedByDescending { score(features(it)!!) } + unknown
    }

    /** The best start (frame) of a fragment in [samples] for [kind] and how good it is (higher is better). */
    class Window(val startFrame: Int, val score: Double)

    /**
     * The [SECONDS] of [samples] (interleaved, [channels]) that have most of what [kind] wants, or
     * null when the audio is too short or nearly silent.
     */
    fun bestWindow(samples: FloatArray, channels: Int, rate: Int, kind: FragmentKind): Window? {
        val frames = samples.size / channels
        val block = rate / 4
        val blocks = frames / block
        val span = SECONDS * 4
        if (blocks < span) return null
        val total = DoubleArray(blocks)
        val low = DoubleArray(blocks)
        val high = DoubleArray(blocks)
        val side = DoubleArray(blocks)
        val lowCoef = 1 - exp(-2 * PI * 150.0 / rate)
        var lp = 0.0
        var lastMid = 0.0
        for (b in 0 until blocks) {
            var t = 0.0; var lo = 0.0; var hi = 0.0; var sd = 0.0
            for (i in b * block until (b + 1) * block) {
                val l = samples[i * channels].toDouble()
                val r = if (channels > 1) samples[i * channels + 1].toDouble() else l
                val mid = (l + r) / 2
                val s = (l - r) / 2
                lp += lowCoef * (mid - lp)
                val diff = mid - lastMid
                lastMid = mid
                t += mid * mid; lo += lp * lp; hi += diff * diff; sd += s * s
            }
            total[b] = t / block; low[b] = lo / block; high[b] = hi / block; side[b] = sd / block
        }
        var best: Window? = null
        for (start in 0..blocks - span) {
            var t = 0.0; var lo = 0.0; var hi = 0.0; var sd = 0.0
            val dbs = DoubleArray(span)
            for (k in 0 until span) {
                val b = start + k
                t += total[b]; lo += low[b]; hi += high[b]; sd += side[b]
                dbs[k] = 10 * log10(total[b] + 1e-12)
            }
            val rmsDb = 10 * log10(t / span + 1e-12)
            // Nearly silent stretches are no use for any step.
            if (rmsDb < -45) continue
            val score = when (kind) {
                FragmentKind.BASS -> 10 * log10((lo + 1e-12) / (t + 1e-12))
                FragmentKind.BRIGHT -> 10 * log10((hi + 1e-12) / (t + 1e-12))
                FragmentKind.WIDE -> 10 * log10((sd + 1e-12) / (t + 1e-12))
                FragmentKind.DYNAMIC -> {
                    val mean = dbs.average()
                    sqrt(dbs.sumOf { (it - mean) * (it - mean) } / span)
                }
                // Steady and full: the loudest stretch.
                FragmentKind.ANY -> rmsDb
            }
            if (best == null || score > best.score) best = Window(start * block, score)
        }
        return best
    }

    /**
     * [SECONDS] of [samples] from [startFrame] as a [Fragment]: stereo, brought to one level (its
     * peaks kept under full scale), its ends faded so the loop does not click; at [outRate].
     */
    fun cut(samples: FloatArray, channels: Int, rate: Int, startFrame: Int, title: String?, outRate: Int = rate): Fragment {
        val frames = minOf(SECONDS * rate, samples.size / channels - startFrame).coerceAtLeast(0)
        val out = FloatArray(frames * 2)
        var square = 0.0
        var peak = 0f
        for (f in 0 until frames) {
            val at = (startFrame + f) * channels
            val l = samples[at]
            val r = if (channels > 1) samples[at + 1] else l
            out[f * 2] = l
            out[f * 2 + 1] = r
            square += (l * l + r * r) / 2.0
            peak = maxOf(peak, kotlin.math.abs(l), kotlin.math.abs(r))
        }
        val resampled = if (outRate == rate) out else resample(out, rate, outRate)
        return level(resampled, outRate, title, square / frames.coerceAtLeast(1), peak)
    }

    /** Interleaved stereo [samples] from [inRate] to [outRate]. */
    fun resample(samples: FloatArray, inRate: Int, outRate: Int): FloatArray {
        val r = com.sadurni.attacca.dsp.Resampler(inRate, outRate, 2)
        val out = ArrayList<FloatArray>()
        var size = 0
        val sink: (FloatArray, Int) -> Unit = { buf, frames -> out += buf.copyOf(frames * 2); size += frames * 2 }
        r.process(samples, samples.size / 2, sink)
        r.finish(sink)
        val all = FloatArray(size)
        var at = 0
        for (b in out) { b.copyInto(all, at); at += b.size }
        return all
    }

    /** The guide's own [TestSignals.groove] as a fragment, for when no track can be used. */
    fun groove(rate: Int): Fragment {
        val s = TestSignals.groove(rate)
        var square = 0.0
        var peak = 0f
        for (v in s) { square += v * v; peak = maxOf(peak, kotlin.math.abs(v)) }
        return level(s, rate, null, square / s.size.coerceAtLeast(1), peak)
    }

    private fun level(out: FloatArray, rate: Int, title: String?, meanSquare: Double, peak: Float): Fragment {
        val frames = out.size / 2
        val rmsDb = 10 * log10(meanSquare + 1e-12)
        val peakDb = 20 * log10(peak.toDouble() + 1e-9)
        // To the target level, but never pushing the peaks over -1 dBFS.
        val gainDb = minOf(TARGET_RMS_DB - rmsDb, -1.0 - peakDb)
        val gain = Math.pow(10.0, gainDb / 20).toFloat()
        val fade = (rate * 0.04).toInt().coerceAtMost(frames / 4)
        for (f in 0 until frames) {
            var g = gain
            if (f < fade) g *= f.toFloat() / fade
            if (f >= frames - fade) g *= (frames - 1 - f).toFloat() / fade
            out[f * 2] *= g
            out[f * 2 + 1] *= g
        }
        return Fragment(out, rate, title, (-(peakDb + gainDb)).toFloat())
    }
}
