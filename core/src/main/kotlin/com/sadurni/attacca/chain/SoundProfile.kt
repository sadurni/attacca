package com.sadurni.attacca.chain

import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * What a track sounds like for telling families and look-alikes apart: the sound network's
 * embedding ([SoundNet]) averaged over a few windows (one patch of the network each) spread over
 * stretches at a quarter, half and three quarters of the track, and the timbre coefficients
 * ([Cepstrum]: mean and spread) of the same windows. Only the windows need decoding.
 */
class SoundProfile private constructor(
    /** The embedding kept in bytes with their [scale] (it is never negative): a quarter of the memory, and a whole collection's profiles are held at once. */
    private val scale: Float,
    private val codes: ByteArray,
    val timbre: FloatArray,
) {

    constructor(embedding: FloatArray, timbre: FloatArray) : this(scaleOf(embedding), quantize(embedding, scaleOf(embedding)), timbre)

    /** The embedding, scaled back from its bytes (a new array on each read). */
    val embedding: FloatArray get() = FloatArray(codes.size) { (codes[it].toInt() and 0xff) * scale }

    /** Compact: the embedding in bytes with its scale, the timbre in floats. */
    fun toBytes(): ByteArray {
        val out = java.nio.ByteBuffer.allocate(4 + codes.size + timbre.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        out.putFloat(scale)
        out.put(codes)
        for (v in timbre) out.putFloat(v)
        return out.array()
    }

    companion object {
        const val TIMBRE_SIZE = 2 * Cepstrum.COEFFICIENTS
        const val BYTES = 4 + SoundNet.EMBEDDING_SIZE + TIMBRE_SIZE * 4

        /** Seconds of each stretch, and windows taken from each (spread over it). */
        const val EXCERPT_SECONDS = 10
        const val PATCHES_PER_EXCERPT = 4

        /** Where the stretches are, as fractions of the track. */
        val EXCERPTS = doubleArrayOf(0.25, 0.5, 0.75)

        /** Seconds of sound in each window: one patch of the network. */
        const val WINDOW_SECONDS = SoundNet.PATCH_SECONDS

        /** Samples of a window at [rate] (never more than one patch once resampled). */
        fun windowSamples(rate: Int): Int = (WINDOW_SECONDS * rate).toInt()

        /**
         * Where the windows of a track [seconds] long start, in seconds: [PATCHES_PER_EXCERPT]
         * spread over each stretch at [EXCERPTS] or, when the track is short, over its first
         * stretches' worth of sound (one window when shorter than a window).
         */
        fun windowStarts(seconds: Double): DoubleArray {
            fun spread(from: Double, span: Double): List<Double> =
                if (span <= WINDOW_SECONDS) listOf(from)
                else (0 until PATCHES_PER_EXCERPT).map { from + it * (span - WINDOW_SECONDS) / (PATCHES_PER_EXCERPT - 1) }
            return if (seconds <= EXCERPT_SECONDS * (EXCERPTS.size + 1.0)) {
                spread(0.0, minOf(seconds, EXCERPT_SECONDS * EXCERPTS.size.toDouble())).toDoubleArray()
            } else {
                EXCERPTS.flatMap { at -> spread(seconds * at - EXCERPT_SECONDS / 2.0, EXCERPT_SECONDS.toDouble()) }.toDoubleArray()
            }
        }

        fun fromBytes(bytes: ByteArray): SoundProfile? {
            if (bytes.size != BYTES) return null
            val b = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val scale = b.float
            val codes = ByteArray(SoundNet.EMBEDDING_SIZE).also { b.get(it) }
            val timbre = FloatArray(TIMBRE_SIZE) { b.float }
            return SoundProfile(scale, codes, timbre)
        }

        private fun scaleOf(embedding: FloatArray): Float = (embedding.maxOrNull() ?: 0f).coerceAtLeast(1e-9f) / 255f

        private fun quantize(embedding: FloatArray, scale: Float): ByteArray =
            ByteArray(embedding.size) { (embedding[it] / scale).roundToInt().coerceIn(0, 255).toByte() }

        /**
         * Measures the [windows] (mono at [rate], [windowSamples] each, from [windowStarts]) with
         * [net]. Null when none could be measured.
         */
        fun measure(windows: List<FloatArray>, rate: Int, net: SoundNet, isCancelled: () -> Boolean = { false }): SoundProfile? {
            val embeddings = ArrayList<FloatArray>()
            var frames = 0f
            val sum = FloatArray(Cepstrum.COEFFICIENTS)
            val squares = FloatArray(Cepstrum.COEFFICIENTS)
            for (window in windows) {
                if (isCancelled()) return null
                val wave = SoundNet.resample(window, window.size, rate)
                net.run(wave, 1, isCancelled)?.let { embeddings.addAll(it.embeddings) }
                // The timbre of the same window, as sums so the windows add up.
                val profile = EdgeProfile.measure(window, 0, window.size, rate, 0) ?: continue
                profile.timbreSums()?.let { (n, s, q) ->
                    frames += n
                    for (i in sum.indices) {
                        sum[i] += s[i]
                        squares[i] += q[i]
                    }
                }
            }
            if (embeddings.isEmpty() || frames <= 0f) return null
            val embedding = FloatArray(SoundNet.EMBEDDING_SIZE)
            for (e in embeddings) for (i in embedding.indices) embedding[i] += e[i] / embeddings.size
            val mean = FloatArray(sum.size) { sum[it] / frames }
            val spread = FloatArray(sum.size) { sqrt(max(squares[it] / frames - mean[it] * mean[it], 0f)) }
            return SoundProfile(embedding, mean + spread)
        }
    }
}

/**
 * The main directions of a set of vectors (principal components), to bring the sound network's
 * 1024 numbers down to a few that keep most of how tracks differ. Fitted on a sample of at most
 * [SAMPLE] vectors (enough for the directions, and quick), by subspace iteration on their
 * covariance; the same vectors always give the same directions.
 */
class Principal(val mean: FloatArray, val axes: Array<FloatArray>) {

    fun project(v: FloatArray): FloatArray = FloatArray(axes.size) { a ->
        var s = 0f
        val axis = axes[a]
        for (i in v.indices) s += (v[i] - mean[i]) * axis[i]
        s
    }

    companion object {
        private const val SAMPLE = 3000
        private const val ITERATIONS = 12

        fun fit(vectors: List<FloatArray>, components: Int): Principal {
            val dims = vectors.first().size
            val random = java.util.Random(17)
            // By index: a list that makes its vectors when read (see [SoundProfile.embedding]) only
            // makes the sample's.
            val sample = if (vectors.size <= SAMPLE) vectors else vectors.indices.shuffled(random).take(SAMPLE).map { vectors[it] }
            val mean = FloatArray(dims)
            for (v in sample) for (i in 0 until dims) mean[i] += v[i] / sample.size
            // Covariance (upper triangle, then mirrored).
            val cov = Array(dims) { DoubleArray(dims) }
            val centred = DoubleArray(dims)
            for (v in sample) {
                for (i in 0 until dims) centred[i] = (v[i] - mean[i]).toDouble()
                for (i in 0 until dims) {
                    val ci = centred[i]
                    if (ci == 0.0) continue
                    val row = cov[i]
                    for (j in i until dims) row[j] += ci * centred[j]
                }
            }
            for (i in 0 until dims) for (j in 0 until i) cov[i][j] = cov[j][i]
            val k = components.coerceAtMost(dims)
            var basis = Array(k) { DoubleArray(dims) { random.nextGaussian() } }
            orthonormalise(basis)
            repeat(ITERATIONS) {
                basis = Array(k) { a ->
                    val b = basis[a]
                    DoubleArray(dims) { i ->
                        var s = 0.0
                        val row = cov[i]
                        for (j in 0 until dims) s += row[j] * b[j]
                        s
                    }
                }
                orthonormalise(basis)
            }
            return Principal(mean, Array(k) { a -> FloatArray(dims) { basis[a][it].toFloat() } })
        }

        private fun orthonormalise(vectors: Array<DoubleArray>) {
            for (a in vectors.indices) {
                val v = vectors[a]
                for (b in 0 until a) {
                    val u = vectors[b]
                    var dot = 0.0
                    for (i in v.indices) dot += v[i] * u[i]
                    for (i in v.indices) v[i] -= dot * u[i]
                }
                var norm = 0.0
                for (x in v) norm += x * x
                norm = sqrt(norm).coerceAtLeast(1e-12)
                for (i in v.indices) v[i] /= norm
            }
        }
    }
}
