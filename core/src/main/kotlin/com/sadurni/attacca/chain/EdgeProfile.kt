package com.sadurni.attacca.chain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * One edge of a track (its first or last seconds of sound) in blocks of [blockMs], so the part
 * that is compared can follow the crossfade: any stretch of whole blocks is summed into an
 * [EdgeDescriptor] when chaining, from the same silence bounds playback uses. Also the edge's
 * tempo and how clear its beat is, over the whole profile.
 *
 * Each block holds sums, so stretches add up: chroma shares (12), band levels in dB (16), power,
 * frames, rises (flux), frames with a rise, and the timbre coefficients ([Cepstrum]) and their
 * squares.
 */
class EdgeProfile(
    /** Where block 0 starts in the file, ms. */
    val startMs: Long,
    val blockMs: Int,
    val blocks: Array<FloatArray>,
    val tempoBpm: Float,
    val pulse: Float,
) {
    val endMs: Long get() = startMs + blocks.size.toLong() * blockMs

    /** The blocks within [fromMs]…[toMs] (file times) summed up; null when none has sound. */
    fun window(fromMs: Long, toMs: Long): EdgeDescriptor? {
        val first = ((fromMs - startMs).toDouble() / blockMs).roundToInt().coerceIn(0, blocks.size)
        val last = ((toMs - startMs).toDouble() / blockMs).roundToInt().coerceIn(first, blocks.size)
        if (last <= first) return null
        val sum = FloatArray(BLOCK_SIZE)
        for (b in first until last) for (i in 0 until BLOCK_SIZE) sum[i] += blocks[b][i]
        val frames = sum[FRAMES]
        if (frames <= 0f) return null
        val chromaTotal = (0 until PITCH).sumOf { sum[CHROMA + it].toDouble() }
        val bands = DoubleArray(BANDS) { sum[BAND + it].toDouble() / frames }
        val bandMean = bands.average()
        return EdgeDescriptor(
            chroma = FloatArray(PITCH) { if (chromaTotal > 0) (sum[CHROMA + it] / chromaTotal).toFloat() else 1f / PITCH },
            bands = FloatArray(BANDS) { (bands[it] - bandMean).toFloat() },
            loudnessDb = (10 * log10(sum[POWER].toDouble() / frames + 1e-20)).toFloat(),
            flux = if (sum[FLUX_FRAMES] > 0) sum[FLUX] / sum[FLUX_FRAMES] else 0f,
        )
    }

    /** Mean and spread of the timbre coefficients over [fromMs]…[toMs]; null when silent. */
    fun timbre(fromMs: Long, toMs: Long): Pair<FloatArray, FloatArray>? {
        val first = ((fromMs - startMs).toDouble() / blockMs).roundToInt().coerceIn(0, blocks.size)
        val last = ((toMs - startMs).toDouble() / blockMs).roundToInt().coerceIn(first, blocks.size)
        var frames = 0f
        val sum = FloatArray(Cepstrum.COEFFICIENTS)
        val squares = FloatArray(Cepstrum.COEFFICIENTS)
        for (b in first until last) {
            frames += blocks[b][FRAMES]
            for (i in sum.indices) {
                sum[i] += blocks[b][CEPS + i]
                squares[i] += blocks[b][CEPS_SQ + i]
            }
        }
        if (frames <= 0f) return null
        val mean = FloatArray(sum.size) { sum[it] / frames }
        val spread = FloatArray(sum.size) { sqrt((squares[it] / frames - mean[it] * mean[it]).coerceAtLeast(0f)) }
        return mean to spread
    }

    /** The timbre coefficients of every block summed: frames, sums and sums of squares; null when silent. */
    fun timbreSums(): Triple<Float, FloatArray, FloatArray>? {
        var frames = 0f
        val sum = FloatArray(Cepstrum.COEFFICIENTS)
        val squares = FloatArray(Cepstrum.COEFFICIENTS)
        for (block in blocks) {
            frames += block[FRAMES]
            for (i in sum.indices) {
                sum[i] += block[CEPS + i]
                squares[i] += block[CEPS_SQ + i]
            }
        }
        return if (frames > 0f) Triple(frames, sum, squares) else null
    }

    /** Level of one block (dBFS); null when silent. */
    fun blockDb(index: Int): Float? {
        val b = blocks.getOrNull(index) ?: return null
        if (b[FRAMES] <= 0f) return null
        return (10 * log10(b[POWER].toDouble() / b[FRAMES] + 1e-20)).toFloat()
    }

    fun toArray(): FloatArray = floatArrayOf(startMs.toFloat(), blockMs.toFloat(), blocks.size.toFloat(), tempoBpm, pulse) +
        blocks.fold(FloatArray(0)) { acc, b -> acc + b }

    companion object {
        private const val PITCH = EdgeFeatures.PITCH_CLASSES
        private const val BANDS = EdgeFeatures.BANDS
        private const val CHROMA = 0
        private const val BAND = CHROMA + PITCH
        private const val POWER = BAND + BANDS
        private const val FRAMES = POWER + 1
        private const val FLUX = FRAMES + 1
        private const val FLUX_FRAMES = FLUX + 1
        private const val CEPS = FLUX_FRAMES + 1
        private const val CEPS_SQ = CEPS + Cepstrum.COEFFICIENTS
        const val BLOCK_SIZE = CEPS_SQ + Cepstrum.COEFFICIENTS

        const val DEFAULT_BLOCK_MS = 1_000
        /** Seconds of sound each profile covers: the longest crossfade or comparison. */
        const val PROFILE_SECONDS = 20

        private const val SILENT_FRAME_DB = -60.0

        fun fromArray(v: FloatArray, offset: Int = 0): EdgeProfile {
            val count = v[offset + 2].toInt()
            return EdgeProfile(
                startMs = v[offset].toLong(),
                blockMs = v[offset + 1].toInt(),
                blocks = Array(count) { b -> v.copyOfRange(offset + 5 + b * BLOCK_SIZE, offset + 5 + (b + 1) * BLOCK_SIZE) },
                tempoBpm = v[offset + 3],
                pulse = v[offset + 4],
            )
        }

        /** Floats [toArray] takes for a profile of [count] blocks. */
        fun arraySize(count: Int) = 5 + count * BLOCK_SIZE

        /**
         * Measures mono [samples] between [from] and [to] (indices) in blocks of [blockMs];
         * [fromMs] is where [from] lies in the file. Null when it is too short.
         */
        fun measure(samples: FloatArray, from: Int, to: Int, sampleRate: Int, fromMs: Long, blockMs: Int = DEFAULT_BLOCK_MS): EdgeProfile? {
            val n = EdgeFeatures.frameSize(sampleRate)
            val hop = n / 2
            if (to - from < n || sampleRate <= 0) return null
            val blockFrames = sampleRate.toLong() * blockMs / 1000
            val count = ((to - from + blockFrames - 1) / blockFrames).toInt()
            val blocks = Array(count) { FloatArray(BLOCK_SIZE) }
            val fft = Fft(n)
            val window = FloatArray(n) { (0.5 - 0.5 * cos(2 * PI * it / (n - 1))).toFloat() }
            val pitchClass = IntArray(n / 2 + 1) { k ->
                val hz = k.toDouble() * sampleRate / n
                if (hz < 65.0 || hz > 2_100.0) -1 else Math.floorMod((12 * log2(hz / 261.6256)).roundToInt(), PITCH)
            }
            val band = EdgeFeatures.bandOfBins(n, sampleRate)
            val cepstrum = Cepstrum(n, sampleRate)
            val power = DoubleArray(n / 2 + 1)
            val re = FloatArray(n)
            val im = FloatArray(n)
            val bandEnergy = DoubleArray(BANDS)
            var previousDb: DoubleArray? = null
            var start = from
            while (start + n <= to) {
                // A frame belongs to the block its middle falls in.
                val block = blocks[((start + n / 2 - from) / blockFrames).toInt().coerceIn(0, count - 1)]
                var square = 0.0
                for (i in 0 until n) {
                    val s = samples[start + i]
                    square += s * s
                    re[i] = s * window[i]
                    im[i] = 0f
                }
                start += hop
                val framePower = square / n
                if (10 * log10(framePower + 1e-20) < SILENT_FRAME_DB) {
                    previousDb = null
                    continue
                }
                fft.transform(re, im)
                bandEnergy.fill(0.0)
                val frameChroma = DoubleArray(PITCH)
                for (k in 1..n / 2) {
                    val p = re[k].toDouble() * re[k] + im[k].toDouble() * im[k]
                    power[k] = p
                    val pc = pitchClass[k]
                    if (pc >= 0) frameChroma[pc] += sqrt(p)
                    val b = band[k]
                    if (b >= 0) bandEnergy[b] += p
                }
                val chromaTotal = frameChroma.sum()
                if (chromaTotal > 0) for (i in 0 until PITCH) block[CHROMA + i] += (frameChroma[i] / chromaTotal).toFloat()
                val db = DoubleArray(BANDS) { 10 * log10(bandEnergy[it] + 1e-12) }
                for (i in 0 until BANDS) block[BAND + i] += db[i].toFloat()
                previousDb?.let { prev ->
                    var rise = 0.0
                    for (i in 0 until BANDS) rise += (db[i] - prev[i]).coerceAtLeast(0.0)
                    block[FLUX] += (rise / BANDS).toFloat()
                    block[FLUX_FRAMES] += 1f
                }
                previousDb = db
                val coefficients = cepstrum.of(power)
                for (i in coefficients.indices) {
                    block[CEPS + i] += coefficients[i]
                    block[CEPS_SQ + i] += coefficients[i] * coefficients[i]
                }
                block[POWER] += framePower.toFloat()
                block[FRAMES] += 1f
            }
            val stretch = samples.copyOfRange(from, to)
            val onsets = TrackFeatures.onsetPass(stretch, stretch.size, sampleRate, n / 4)
            val (bpm, pulse) = TrackFeatures.tempo(onsets.flux, onsets.framesPerSecond)
            return EdgeProfile(fromMs, blockMs, blocks, bpm, pulse)
        }
    }
}

/** Key of an edge from its chroma: 0–11 major (C…B), 12–23 minor, and how clear it is (0 … 1). */
object Keys {

    fun estimate(chroma: FloatArray): Pair<Int, Float> {
        var best = 0
        var bestR = -1.0
        for (tonic in 0 until 12) {
            val rotated = DoubleArray(12) { chroma[(it + tonic) % 12].toDouble() }
            val major = pearson(rotated, TrackFeatures.MAJOR)
            val minor = pearson(rotated, TrackFeatures.MINOR)
            if (major > bestR) {
                bestR = major
                best = tonic
            }
            if (minor > bestR) {
                bestR = minor
                best = 12 + tonic
            }
        }
        return best to bestR.coerceAtLeast(0.0).toFloat()
    }

    /**
     * How well two keys go one after the other (1 same … 0 far apart), as in harmonic mixing:
     * same key, relative major/minor, a fifth up or down, parallel major/minor.
     */
    fun compatibility(a: Int, b: Int): Float {
        // Position on the circle of fifths, minor keys at their relative major's place.
        fun fifths(key: Int): Int = if (key < 12) (key * 7) % 12 else (((key - 12 + 3) % 12) * 7) % 12
        val minorA = a >= 12
        val minorB = b >= 12
        val steps = min(Math.floorMod(fifths(a) - fifths(b), 12), Math.floorMod(fifths(b) - fifths(a), 12))
        return when {
            a == b -> 1f
            steps == 0 -> 0.9f // relative major/minor
            steps == 1 && minorA == minorB -> 0.8f
            a % 12 == b % 12 -> 0.6f // parallel
            steps == 1 -> 0.6f
            steps == 2 -> 0.4f
            else -> 0.15f
        }
    }

    private fun pearson(a: DoubleArray, b: DoubleArray): Double {
        val ma = a.average()
        val mb = b.average()
        var num = 0.0
        var da = 0.0
        var db = 0.0
        for (i in a.indices) {
            val x = a[i] - ma
            val y = b[i] - mb
            num += x * y
            da += x * x
            db += y * y
        }
        return if (da <= 0 || db <= 0) 0.0 else num / sqrt(da * db)
    }
}

/**
 * The edge of a track as a link is scored: the compared stretch, its timbre coefficients (mean
 * and spread), its tempo and key, its level relative to the body of the track (as it will sound
 * with levels evened out) and the level of the very end (or start), where the two tracks meet.
 */
class LinkEdge(
    val descriptor: EdgeDescriptor,
    val tempoBpm: Float,
    val pulse: Float,
    val relativeDb: Float,
    val meetingDb: Float,
    val timbreMean: FloatArray,
    val timbreSpread: FloatArray,
) {
    val key: Pair<Int, Float> by lazy { Keys.estimate(descriptor.chroma) }
}

/**
 * How well one track's end leads into another's start (0 … 1): the similarities of the two edges
 * ([similarities], each 0 … 1) weighed by [weights] (which add up to 1). The weights, and the
 * scales of the timbre distances, are learnt from music cut where it flows on (see the chaining
 * bench), so they come from the sound alone.
 */
class LinkModel(val weights: FloatArray, val timbreScale: Float, val textureScale: Float) {

    fun score(end: LinkEdge, start: LinkEdge): Float = score(similarities(end, start, timbreScale, textureScale))

    fun score(s: FloatArray): Float {
        var total = 0f
        for (i in s.indices) total += weights[i] * s[i]
        return total
    }

    companion object {
        val NAMES = listOf(
            "harmony", "key", "bands", "timbre", "texture", "level", "meeting", "activity", "tempo",
        )
        val SIZE = NAMES.size

        /**
         * How alike [end] and [start] are, each 1 when the same: chroma (correlation), key
         * (compatibility; 0.5 when the keys are unclear), band shape, timbre coefficients (mean,
         * spread; by the given scales), level, meeting level, activity and tempo (half or double
         * counting as equal; 0.5 when the beats are unclear).
         */
        fun similarities(end: LinkEdge, start: LinkEdge, timbreScale: Float, textureScale: Float): FloatArray {
            val a = end.descriptor
            val b = start.descriptor
            var squares = 0f
            for (i in 0 until EdgeFeatures.BANDS) {
                val d = a.bands[i] - b.bands[i]
                squares += d * d
            }
            val keyClarity = min(end.key.second, start.key.second).coerceIn(0f, 1f)
            val tempo = if (end.tempoBpm <= 0f || start.tempoBpm <= 0f) {
                0.5f
            } else {
                val r = log2(end.tempoBpm / start.tempoBpm)
                val d = minOf(abs(r), abs(r - 1), abs(r + 1))
                0.5f + (exp(-d / 0.04f) - 0.5f) * (min(end.pulse, start.pulse) * 2f).coerceIn(0f, 1f)
            }
            return floatArrayOf(
                (pearson(a.chroma, b.chroma) + 1f) / 2f,
                0.5f + (Keys.compatibility(end.key.first, start.key.first) - 0.5f) * keyClarity,
                exp(-sqrt(squares / EdgeFeatures.BANDS) / 6f),
                exp(-distance(end.timbreMean, start.timbreMean) / timbreScale),
                exp(-distance(end.timbreSpread, start.timbreSpread) / textureScale),
                exp(-abs(end.relativeDb - start.relativeDb) / 8f),
                exp(-abs(end.meetingDb - start.meetingDb) / 10f),
                exp(-abs(ln((a.flux + 0.05f) / (b.flux + 0.05f))) / 0.7f),
                tempo,
            )
        }

        fun distance(a: FloatArray, b: FloatArray): Float {
            var s = 0f
            for (i in a.indices) {
                val d = a[i] - b[i]
                s += d * d
            }
            return sqrt(s)
        }

        private fun pearson(a: FloatArray, b: FloatArray): Float {
            val ma = a.average()
            val mb = b.average()
            var num = 0.0
            var da = 0.0
            var db = 0.0
            for (i in a.indices) {
                val x = a[i] - ma
                val y = b[i] - mb
                num += x * y
                da += x * x
                db += y * y
            }
            return if (da <= 0 || db <= 0) 0f else (num / sqrt(da * db)).toFloat()
        }
    }
}

/**
 * Timbre of a frame as cepstral coefficients over a mel-spaced filter bank (as MFCCs): the
 * spectrum's overall shape, without the level (the first coefficient is left out).
 */
class Cepstrum(n: Int, sampleRate: Int) {
    private val bands = 40
    private val filters: Array<Pair<Int, FloatArray>>
    private val dct = Array(COEFFICIENTS) { c -> FloatArray(bands) { b -> cos(PI * (c + 1) * (b + 0.5) / bands).toFloat() } }

    init {
        fun mel(hz: Double) = 2595 * log10(1 + hz / 700)
        fun hz(mel: Double) = 700 * (Math.pow(10.0, mel / 2595) - 1)
        val high = minOf(16_000.0, sampleRate / 2.0 * 0.95)
        val points = DoubleArray(bands + 2) { hz(mel(40.0) + (mel(high) - mel(40.0)) * it / (bands + 1)) }
        val binHz = sampleRate.toDouble() / n
        filters = Array(bands) { b ->
            val lo = points[b]
            val mid = points[b + 1]
            val hi = points[b + 2]
            val first = (lo / binHz).toInt().coerceAtLeast(1)
            val last = (hi / binHz).toInt().coerceAtMost(n / 2)
            first to FloatArray((last - first + 1).coerceAtLeast(0)) { i ->
                val f = (first + i) * binHz
                (if (f <= mid) (f - lo) / (mid - lo) else (hi - f) / (hi - mid)).coerceIn(0.0, 1.0).toFloat()
            }
        }
    }

    /** Coefficients 1 … [COEFFICIENTS] of a frame's power spectrum (bins 0 … n/2). */
    fun of(power: DoubleArray): FloatArray {
        val logs = FloatArray(bands) { b ->
            val (first, weights) = filters[b]
            var e = 0.0
            for (i in weights.indices) e += power[first + i] * weights[i]
            ln(e + 1e-10).toFloat()
        }
        return FloatArray(COEFFICIENTS) { c ->
            var s = 0f
            for (b in 0 until bands) s += dct[c][b] * logs[b]
            s / bands
        }
    }

    companion object {
        const val COEFFICIENTS = 13
    }
}
