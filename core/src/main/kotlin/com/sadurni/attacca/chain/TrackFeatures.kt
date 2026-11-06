package com.sadurni.attacca.chain

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * What a track sounds like as a whole, measured on a stretch from its middle. Moods and sound
 * families are derived from these values relative to the rest of the collection (see [Moods]).
 */
class TrackFeatures(
    /** Mean level, dBFS. */
    val loudnessDb: Float,
    /** Spread of the level over time, dB: high for dynamic music, low for compressed. */
    val dynamicsDb: Float,
    /** Spectral centroid, log2 of Hz: brighter music is higher. */
    val brightness: Float,
    /** Share of the energy below 150 Hz, dB. */
    val bassDb: Float,
    /** Spectral flatness between 1 and 8 kHz (0 tonal … 1 noise): distortion, cymbals. */
    val noisiness: Float,
    /** Mean rise of band levels between short frames: how percussive. */
    val percussiveness: Float,
    /** Estimated tempo, beats per minute. */
    val tempoBpm: Float,
    /** How clearly the beat repeats (0 … 1). */
    val pulseClarity: Float,
    /** Major minus minor key correlation: positive sounds major, negative minor. */
    val mode: Float,
    /** Best key correlation (0 … 1): how clearly the music sits in one key. */
    val keyClarity: Float,
    /** Share of syllable-rate modulation (3–8 Hz) in the voice band: rough sign of singing. */
    val voice: Float,
) {
    fun toArray(): FloatArray = floatArrayOf(
        loudnessDb, dynamicsDb, brightness, bassDb, noisiness, percussiveness,
        tempoBpm, pulseClarity, mode, keyClarity, voice,
    )

    companion object {
        const val SIZE = 11

        fun fromArray(v: FloatArray) = TrackFeatures(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9], v[10])

        /** Seconds from the middle of the track that are measured. */
        const val SPAN_SECONDS = 30

        private const val SILENT_FRAME_DB = -60.0
        private const val MIN_BPM = 60.0
        private const val MAX_BPM = 200.0

        // Krumhansl-Kessler key profiles, starting at the tonic.
        val MAJOR = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
        val MINOR = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)

        /** Measures mono [samples] (the whole array). Null when it is too short or silent. */
        fun measure(samples: FloatArray, count: Int, sampleRate: Int): TrackFeatures? {
            val n = EdgeFeatures.frameSize(sampleRate)
            if (count < n * 4 || sampleRate <= 0) return null
            val spectral = spectralPass(samples, count, sampleRate, n) ?: return null
            val onsets = onsetPass(samples, count, sampleRate, n / 4)
            val (tempo, clarity) = tempo(onsets.flux, onsets.framesPerSecond)
            val (mode, keyClarity) = key(spectral.chroma)
            return TrackFeatures(
                loudnessDb = spectral.loudnessDb,
                dynamicsDb = spectral.dynamicsDb,
                brightness = spectral.brightness,
                bassDb = spectral.bassDb,
                noisiness = spectral.noisiness,
                percussiveness = onsets.flux.average().toFloat(),
                tempoBpm = tempo,
                pulseClarity = clarity,
                mode = mode,
                keyClarity = keyClarity,
                voice = syllableModulation(onsets.voiceEnvelope, onsets.framesPerSecond),
            )
        }

        private class Spectral(
            val loudnessDb: Float,
            val dynamicsDb: Float,
            val brightness: Float,
            val bassDb: Float,
            val noisiness: Float,
            val chroma: DoubleArray,
        )

        private fun spectralPass(samples: FloatArray, count: Int, rate: Int, n: Int): Spectral? {
            val fft = Fft(n)
            val window = hann(n)
            val re = FloatArray(n)
            val im = FloatArray(n)
            val chroma = DoubleArray(12)
            val pitchClass = IntArray(n / 2 + 1) { k ->
                val hz = k.toDouble() * rate / n
                if (hz < 65 || hz > 2_100) -1 else Math.floorMod((12 * log2(hz / 261.6256)).roundToInt(), 12)
            }
            val dbs = ArrayList<Double>()
            var powerSum = 0.0
            var centroidSum = 0.0
            var bassSum = 0.0
            var flatSum = 0.0
            var frames = 0
            var start = 0
            while (start + n <= count) {
                var square = 0.0
                for (i in 0 until n) {
                    val s = samples[start + i]
                    square += s * s
                    re[i] = s * window[i]
                    im[i] = 0f
                }
                start += n / 2
                val power = square / n
                val db = 10 * log10(power + 1e-20)
                if (db < SILENT_FRAME_DB) continue
                fft.transform(re, im)
                var total = 0.0
                var weighted = 0.0
                var bass = 0.0
                var logSum = 0.0
                var linSum = 0.0
                var flatBins = 0
                for (k in 1..n / 2) {
                    val p = re[k].toDouble() * re[k] + im[k].toDouble() * im[k]
                    val hz = k.toDouble() * rate / n
                    total += p
                    weighted += p * hz
                    if (hz < 150) bass += p
                    if (hz in 1_000.0..8_000.0) {
                        logSum += ln(p + 1e-12)
                        linSum += p + 1e-12
                        flatBins++
                    }
                    val pc = pitchClass[k]
                    if (pc >= 0) chroma[pc] += sqrt(p)
                }
                if (total <= 0) continue
                dbs.add(db)
                powerSum += power
                centroidSum += log2((weighted / total).coerceAtLeast(20.0))
                bassSum += bass / total
                if (flatBins > 0) flatSum += exp(logSum / flatBins) / (linSum / flatBins)
                frames++
            }
            if (frames < 4) return null
            val meanDb = dbs.average()
            val spread = sqrt(dbs.sumOf { (it - meanDb) * (it - meanDb) } / frames)
            return Spectral(
                loudnessDb = (10 * log10(powerSum / frames + 1e-20)).toFloat(),
                dynamicsDb = spread.toFloat(),
                brightness = (centroidSum / frames).toFloat(),
                bassDb = (10 * log10(bassSum / frames + 1e-9)).toFloat(),
                noisiness = (flatSum / frames).toFloat(),
                chroma = chroma,
            )
        }

        class Onsets(val flux: DoubleArray, val voiceEnvelope: DoubleArray, val framesPerSecond: Double)

        /** Short frames: band-level rises (onset strength) and the 1–4 kHz level over time. */
        fun onsetPass(samples: FloatArray, count: Int, rate: Int, m: Int): Onsets {
            val fft = Fft(m)
            val window = hann(m)
            val hop = m / 2
            val re = FloatArray(m)
            val im = FloatArray(m)
            val edges = doubleArrayOf(0.0, 200.0, 800.0, 2_500.0, 6_000.0, rate / 2.0)
            val band = IntArray(m / 2 + 1) { k ->
                val hz = k.toDouble() * rate / m
                (0 until edges.size - 1).firstOrNull { hz >= edges[it] && hz < edges[it + 1] } ?: (edges.size - 2)
            }
            val frames = (count - m) / hop + 1
            val flux = DoubleArray(maxOf(0, frames - 1))
            val voice = DoubleArray(frames)
            var previous: DoubleArray? = null
            val energy = DoubleArray(edges.size - 1)
            for (f in 0 until frames) {
                val start = f * hop
                for (i in 0 until m) {
                    re[i] = samples[start + i] * window[i]
                    im[i] = 0f
                }
                fft.transform(re, im)
                energy.fill(0.0)
                var voiceBand = 0.0
                for (k in 1..m / 2) {
                    val p = re[k].toDouble() * re[k] + im[k].toDouble() * im[k]
                    energy[band[k]] += p
                    val hz = k.toDouble() * rate / m
                    if (hz in 1_000.0..4_000.0) voiceBand += p
                }
                val db = DoubleArray(energy.size) { 10 * log10(energy[it] + 1e-12) }
                previous?.let { prev ->
                    var rise = 0.0
                    for (i in db.indices) rise += (db[i] - prev[i]).coerceAtLeast(0.0)
                    flux[f - 1] = rise / db.size
                }
                previous = db
                voice[f] = 10 * log10(voiceBand + 1e-12)
            }
            return Onsets(flux, voice, rate.toDouble() / hop)
        }

        /** Tempo from the autocorrelation of the onset strength, favouring 80–160 BPM. */
        fun tempo(flux: DoubleArray, fps: Double): Pair<Float, Float> {
            if (flux.size < 64) return 0f to 0f
            val mean = flux.average()
            val x = DoubleArray(flux.size) { flux[it] - mean }
            val zero = x.sumOf { it * it }
            if (zero <= 0) return 0f to 0f
            val minLag = (fps * 60 / MAX_BPM).toInt().coerceAtLeast(1)
            val maxLag = (fps * 60 / MIN_BPM).toInt().coerceAtMost(x.size / 2)
            val ac = DoubleArray(maxLag + 2)
            for (lag in minLag - 1..maxLag + 1) {
                if (lag < 1 || lag >= x.size) continue
                var s = 0.0
                for (i in 0 until x.size - lag) s += x[i] * x[i + lag]
                ac[lag] = s / zero
            }
            var best = -1
            var bestScore = Double.NEGATIVE_INFINITY
            for (lag in minLag..maxLag) {
                val bpm = fps * 60 / lag
                val prior = exp(-0.5 * (log2(bpm / 120) / 0.7).let { it * it })
                val score = ac[lag] * prior
                if (score > bestScore) {
                    bestScore = score
                    best = lag
                }
            }
            if (best < 0) return 0f to 0f
            // Parabolic interpolation around the peak for a finer tempo.
            val a = ac[best - 1]
            val b = ac[best]
            val c = ac[best + 1]
            val shift = if (a - 2 * b + c != 0.0) (0.5 * (a - c) / (a - 2 * b + c)).coerceIn(-0.5, 0.5) else 0.0
            return (fps * 60 / (best + shift)).toFloat() to b.coerceIn(0.0, 1.0).toFloat()
        }

        /** (major − minor best correlation, best correlation) of the chroma with the key profiles. */
        private fun key(chroma: DoubleArray): Pair<Float, Float> {
            if (chroma.sum() <= 0) return 0f to 0f
            var major = -1.0
            var minor = -1.0
            for (tonic in 0 until 12) {
                val rotated = DoubleArray(12) { chroma[(it + tonic) % 12] }
                major = maxOf(major, pearson(rotated, MAJOR))
                minor = maxOf(minor, pearson(rotated, MINOR))
            }
            return (major - minor).toFloat() to maxOf(major, minor).coerceAtLeast(0.0).toFloat()
        }

        /** Share of the 3–8 Hz modulation of [envelope] (dB per frame) within 0.5–20 Hz. */
        private fun syllableModulation(envelope: DoubleArray, fps: Double): Float {
            val size = Integer.highestOneBit(envelope.size)
            if (size < 256) return 0f
            val mean = envelope.take(size).average()
            val re = FloatArray(size) { (envelope[it] - mean).toFloat() }
            val im = FloatArray(size)
            Fft(size).transform(re, im)
            var syllable = 0.0
            var all = 0.0
            for (k in 1 until size / 2) {
                val hz = k * fps / size
                val p = re[k].toDouble() * re[k] + im[k].toDouble() * im[k]
                if (hz in 0.5..20.0) all += p
                if (hz in 3.0..8.0) syllable += p
            }
            return if (all > 0) (syllable / all).toFloat() else 0f
        }

        private fun hann(n: Int) = FloatArray(n) { (0.5 - 0.5 * cos(2 * PI * it / (n - 1))).toFloat() }

        private fun pearson(a: DoubleArray, b: DoubleArray): Double {
            val ma = a.average()
            val mb = b.average()
            var num = 0.0
            var da = 0.0
            var db = 0.0
            for (i in a.indices) {
                num += (a[i] - ma) * (b[i] - mb)
                da += (a[i] - ma) * (a[i] - ma)
                db += (b[i] - mb) * (b[i] - mb)
            }
            return if (da > 0 && db > 0) num / sqrt(da * db) else 0.0
        }
    }
}
