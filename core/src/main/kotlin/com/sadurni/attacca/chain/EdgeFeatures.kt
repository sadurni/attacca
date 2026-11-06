package com.sadurni.attacca.chain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** How a track starts and ends; either may be missing (silent or undecodable edge). */
class TrackEdges(val head: EdgeDescriptor?, val tail: EdgeDescriptor?)

/**
 * What a few seconds at one edge of a track sound like: which notes sound (chroma), the shape of
 * the spectrum, how loud it is and how busy (onsets), its timbre (cepstral coefficients, see
 * [Cepstrum]) and its tempo with how clear the beat is. Chaining compares the end of one track
 * with the start of another through [EdgeFeatures.similarity]. Edges described before the timbre
 * and tempo were measured have none ([timbre] null).
 */
class EdgeDescriptor(
    /** Share of each pitch class (C, C#, … B); sums to 1. */
    val chroma: FloatArray,
    /** Energy per frequency band in dB, relative to the mean of the bands (shape, not level). */
    val bands: FloatArray,
    /** Mean level in dBFS. */
    val loudnessDb: Float,
    /** Mean rise of the band levels between frames, in dB: how much is happening. */
    val flux: Float,
    /** Mean timbre coefficients; null for an edge described before they were. */
    val timbre: FloatArray? = null,
    /** Tempo of the edge, BPM (0: none found). */
    val tempoBpm: Float = 0f,
    /** How clearly the beat repeats (0 … 1). */
    val pulse: Float = 0f,
) {
    fun toArray(): FloatArray = chroma + bands + floatArrayOf(loudnessDb, flux) +
        (timbre?.let { it + floatArrayOf(tempoBpm, pulse) } ?: FloatArray(0))

    /** The key of the edge (0–11 major, 12–23 minor) and how clear it is. */
    val key: Pair<Int, Float> by lazy { Keys.estimate(chroma) }

    companion object {
        /** Values of an edge without timbre and tempo (as first stored). */
        const val BASIC_SIZE = EdgeFeatures.PITCH_CLASSES + EdgeFeatures.BANDS + 2
        const val SIZE = BASIC_SIZE + Cepstrum.COEFFICIENTS + 2

        /** An edge from [values] of [SIZE] (or [BASIC_SIZE], without timbre and tempo). */
        fun fromArray(values: FloatArray, offset: Int = 0): EdgeDescriptor {
            val c = EdgeFeatures.PITCH_CLASSES
            val b = EdgeFeatures.BANDS
            val full = values.size - offset >= SIZE
            val t = offset + BASIC_SIZE
            return EdgeDescriptor(
                chroma = values.copyOfRange(offset, offset + c),
                bands = values.copyOfRange(offset + c, offset + c + b),
                loudnessDb = values[offset + c + b],
                flux = values[offset + c + b + 1],
                timbre = if (full) values.copyOfRange(t, t + Cepstrum.COEFFICIENTS) else null,
                tempoBpm = if (full) values[t + Cepstrum.COEFFICIENTS] else 0f,
                pulse = if (full) values[t + Cepstrum.COEFFICIENTS + 1] else 0f,
            )
        }
    }
}

object EdgeFeatures {

    const val PITCH_CLASSES = 12
    const val BANDS = 16

    /** Length of each edge that is described, unless set otherwise. */
    const val DEFAULT_EDGE_SECONDS = 8
    val EDGE_SECONDS_RANGE = 4..20

    /** Frames quieter than this are left out (fades, gaps). */
    private const val SILENT_FRAME_DB = -60f
    private const val LOW_HZ = 50.0
    private const val HIGH_HZ = 16_000.0
    private const val CHROMA_LOW_HZ = 65.0
    private const val CHROMA_HIGH_HZ = 2_100.0
    private const val MIDDLE_C_HZ = 261.6256

    /** FFT size for about a tenth of a second at [sampleRate]. */
    fun frameSize(sampleRate: Int): Int = Integer.highestOneBit((sampleRate / 10).coerceAtLeast(256))

    /**
     * Describes mono [samples] between [from] and [to] (sample indices). Null when nothing in that
     * stretch is louder than near silence.
     */
    fun describe(samples: FloatArray, from: Int, to: Int, sampleRate: Int): EdgeDescriptor? {
        val n = frameSize(sampleRate)
        val hop = n / 2
        if (to - from < n || sampleRate <= 0) return null
        val fft = Fft(n)
        val window = FloatArray(n) { (0.5 - 0.5 * cos(2 * PI * it / (n - 1))).toFloat() }
        val pitchClass = IntArray(n / 2 + 1) { k ->
            val hz = k.toDouble() * sampleRate / n
            if (hz < CHROMA_LOW_HZ || hz > CHROMA_HIGH_HZ) -1
            else Math.floorMod((12 * log2(hz / MIDDLE_C_HZ)).roundToInt(), PITCH_CLASSES)
        }
        val band = bandOfBins(n, sampleRate)
        val cepstrum = Cepstrum(n, sampleRate)
        val spectrum = DoubleArray(n / 2 + 1)
        val timbre = DoubleArray(Cepstrum.COEFFICIENTS)

        val re = FloatArray(n)
        val im = FloatArray(n)
        val chroma = DoubleArray(PITCH_CLASSES)
        val bandDbSum = DoubleArray(BANDS)
        val bandEnergy = DoubleArray(BANDS)
        var previousDb: DoubleArray? = null
        var fluxSum = 0.0
        var fluxFrames = 0
        var powerSum = 0.0
        var frames = 0

        var start = from
        while (start + n <= to) {
            var square = 0.0
            for (i in 0 until n) {
                val s = samples[start + i]
                square += s * s
                re[i] = s * window[i]
                im[i] = 0f
            }
            val power = square / n
            start += hop
            if (10 * log10(power + 1e-20) < SILENT_FRAME_DB) {
                previousDb = null
                continue
            }
            fft.transform(re, im)
            bandEnergy.fill(0.0)
            val frameChroma = DoubleArray(PITCH_CLASSES)
            for (k in 1..n / 2) {
                val p = re[k].toDouble() * re[k] + im[k].toDouble() * im[k]
                spectrum[k] = p
                val pc = pitchClass[k]
                if (pc >= 0) frameChroma[pc] += sqrt(p)
                val b = band[k]
                if (b >= 0) bandEnergy[b] += p
            }
            val coefficients = cepstrum.of(spectrum)
            for (i in timbre.indices) timbre[i] += coefficients[i]
            val chromaTotal = frameChroma.sum()
            if (chromaTotal > 0) for (i in 0 until PITCH_CLASSES) chroma[i] += frameChroma[i] / chromaTotal
            val db = DoubleArray(BANDS) { 10 * log10(bandEnergy[it] + 1e-12) }
            for (i in 0 until BANDS) bandDbSum[i] += db[i]
            previousDb?.let { prev ->
                var rise = 0.0
                for (i in 0 until BANDS) rise += (db[i] - prev[i]).coerceAtLeast(0.0)
                fluxSum += rise / BANDS
                fluxFrames++
            }
            previousDb = db
            powerSum += power
            frames++
        }
        if (frames == 0) return null

        val chromaTotal = chroma.sum()
        val meanBands = DoubleArray(BANDS) { bandDbSum[it] / frames }
        val bandMean = meanBands.average()
        // The tempo of the stretch, from its onsets (as the track's own tempo).
        val stretch = samples.copyOfRange(from, to)
        val onsets = TrackFeatures.onsetPass(stretch, stretch.size, sampleRate, n / 4)
        val (bpm, pulse) = TrackFeatures.tempo(onsets.flux, onsets.framesPerSecond)
        return EdgeDescriptor(
            chroma = FloatArray(PITCH_CLASSES) { if (chromaTotal > 0) (chroma[it] / chromaTotal).toFloat() else 1f / PITCH_CLASSES },
            bands = FloatArray(BANDS) { (meanBands[it] - bandMean).toFloat() },
            loudnessDb = (10 * log10(powerSum / frames + 1e-20)).toFloat(),
            flux = if (fluxFrames > 0) (fluxSum / fluxFrames).toFloat() else 0f,
            timbre = FloatArray(Cepstrum.COEFFICIENTS) { (timbre[it] / frames).toFloat() },
            tempoBpm = bpm,
            pulse = pulse,
        )
    }

    /** Band index of each FFT bin (log-spaced bands between 50 Hz and 16 kHz), or -1. */
    fun bandOfBins(n: Int, sampleRate: Int): IntArray {
        val high = minOf(HIGH_HZ, sampleRate / 2.0 * 0.95)
        val ratio = (high / LOW_HZ).pow(1.0 / BANDS)
        return IntArray(n / 2 + 1) { k ->
            val hz = k.toDouble() * sampleRate / n
            if (hz < LOW_HZ || hz >= high) -1
            else (ln(hz / LOW_HZ) / ln(ratio)).toInt().coerceIn(0, BANDS - 1)
        }
    }

    /**
     * How well [end] (the last seconds of one track) leads into [start] (the first seconds of
     * another): 1 = same harmony, timbre, level and activity; around 0.3 or less = unrelated.
     */
    fun similarity(end: EdgeDescriptor, start: EdgeDescriptor): Float {
        val endTimbre = end.timbre
        val startTimbre = start.timbre
        if (endTimbre != null && startTimbre != null) return similarityWithTimbre(end, start, endTimbre, startTimbre)
        val harmony = (pearson(end.chroma, start.chroma) + 1f) / 2f
        var squares = 0f
        for (i in 0 until BANDS) {
            val d = end.bands[i] - start.bands[i]
            squares += d * d
        }
        val timbre = exp(-sqrt(squares / BANDS) / TIMBRE_DB)
        val level = exp(-abs(end.loudnessDb - start.loudnessDb) / LEVEL_DB)
        val activity = exp(-abs(ln((end.flux + FLUX_FLOOR) / (start.flux + FLUX_FLOOR))) / ACTIVITY_RATIO)
        return HARMONY_WEIGHT * harmony + TIMBRE_WEIGHT * timbre + LEVEL_WEIGHT * level + ACTIVITY_WEIGHT * activity
    }

    /**
     * The similarity of edges with timbre and tempo. Its parts and their weights were learnt from
     * music cut where it flows on (the chaining bench, on the user's own collection): tempo (half
     * or double counting as equal, as much as the beats are clear) weighs most, then the timbre
     * coefficients, the spectrum's shape, the activity, the key (as clear as it is) and the notes.
     */
    private fun similarityWithTimbre(end: EdgeDescriptor, start: EdgeDescriptor, endTimbre: FloatArray, startTimbre: FloatArray): Float {
        val harmony = (pearson(end.chroma, start.chroma) + 1f) / 2f
        val keyClarity = minOf(end.key.second, start.key.second).coerceIn(0f, 1f)
        val key = 0.5f + (Keys.compatibility(end.key.first, start.key.first) - 0.5f) * keyClarity
        var squares = 0f
        for (i in 0 until BANDS) {
            val d = end.bands[i] - start.bands[i]
            squares += d * d
        }
        val bands = exp(-sqrt(squares / BANDS) / TIMBRE_DB)
        var timbreSquares = 0f
        for (i in endTimbre.indices) {
            val d = endTimbre[i] - startTimbre[i]
            timbreSquares += d * d
        }
        val timbre = exp(-sqrt(timbreSquares) / CEPSTRUM_SCALE)
        val activity = exp(-abs(ln((end.flux + FLUX_FLOOR) / (start.flux + FLUX_FLOOR))) / ACTIVITY_RATIO)
        val tempo = if (end.tempoBpm <= 0f || start.tempoBpm <= 0f) {
            0.5f
        } else {
            val r = ln(end.tempoBpm / start.tempoBpm) / LN2
            val d = minOf(abs(r), abs(r - 1), abs(r + 1))
            0.5f + (exp(-d / TEMPO_OCTAVES) - 0.5f) * (minOf(end.pulse, start.pulse) * 2f).coerceIn(0f, 1f)
        }
        return (W_HARMONY * harmony + W_KEY * key + W_BANDS * bands + W_TIMBRE * timbre + W_ACTIVITY * activity + W_TEMPO * tempo) / W_TOTAL
    }

    private const val W_HARMONY = 0.04f
    private const val W_KEY = 0.05f
    private const val W_BANDS = 0.16f
    private const val W_TIMBRE = 0.29f
    private const val W_ACTIVITY = 0.12f
    private const val W_TEMPO = 0.33f
    private const val W_TOTAL = W_HARMONY + W_KEY + W_BANDS + W_TIMBRE + W_ACTIVITY + W_TEMPO
    /** Typical distance of the timbre coefficients between different songs (learnt). */
    private const val CEPSTRUM_SCALE = 1.11f
    /** A tempo this far apart (in octaves, about 3 %) scores 1/e of a match. */
    private const val TEMPO_OCTAVES = 0.04f
    private const val LN2 = 0.6931472f

    private const val HARMONY_WEIGHT = 0.4f
    private const val TIMBRE_WEIGHT = 0.35f
    private const val LEVEL_WEIGHT = 0.15f
    private const val ACTIVITY_WEIGHT = 0.1f
    private const val TIMBRE_DB = 6f
    private const val LEVEL_DB = 8f
    private const val ACTIVITY_RATIO = 0.7f
    private const val FLUX_FLOOR = 0.05f

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
        if (da <= 0 || db <= 0) return 0f
        return (num / sqrt(da * db)).toFloat()
    }
}
