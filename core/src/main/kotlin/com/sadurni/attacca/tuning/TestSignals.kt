package com.sadurni.attacca.tuning

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tanh

/**
 * The sounds the sound guide makes itself, as interleaved stereo floats ready to loop: no audio
 * from anywhere else. Each one fades in and out at its ends so the loop never clicks.
 */
object TestSignals {

    /** Level of the test tones: well under full scale, so nothing starts loud. */
    private const val TONE_LEVEL = 0.25f

    // ---- Left, centre, right ----

    /** Length of each part of [sides] (seconds): a sound and the pause after it. */
    const val SIDE_SECONDS = 1.2

    /**
     * A soft rushing sound (pink noise, bandwidth of a voice) on the left, in the centre and on the
     * right in turn, with a pause after each: [sidesPart] says which one is heard.
     */
    fun sides(rate: Int): FloatArray {
        val part = (SIDE_SECONDS * rate).toInt()
        val sound = (0.8 * rate).toInt()
        val out = FloatArray(part * 3 * 2)
        val noise = PinkNoise(7)
        val band = Band(rate, 300.0, 4000.0)
        for (p in 0 until 3) {
            val (l, r) = when (p) { 0 -> 1f to 0f; 1 -> 0.707f to 0.707f; else -> 0f to 1f }
            for (i in 0 until sound) {
                val v = (band.process(noise.next()) * 0.6 * envelope(i, sound, rate * 0.03)).toFloat()
                val at = (p * part + i) * 2
                out[at] = v * l
                out[at + 1] = v * r
            }
        }
        return out
    }

    /** Which part of [sides] is heard at [frame]: 0 left, 1 centre, 2 right, -1 a pause. */
    fun sidesPart(frame: Int, rate: Int): Int {
        val part = (SIDE_SECONDS * rate).toInt()
        val p = (frame / part) % 3
        return if (frame % part < (0.8 * rate).toInt()) p else -1
    }

    // ---- Tones ----

    /** One tone of [hz]: 1.6 s of it and a short pause, the same on both sides. */
    fun tone(rate: Int, hz: Int): FloatArray {
        val sound = (1.6 * rate).toInt()
        val total = (2.1 * rate).toInt()
        val out = FloatArray(total * 2)
        for (i in 0 until sound) {
            val v = (sin(2 * PI * hz * i / rate) * TONE_LEVEL * envelope(i, sound, rate * 0.08)).toFloat()
            out[i * 2] = v
            out[i * 2 + 1] = v
        }
        return out
    }

    // ---- Bass line ----

    /** The notes of [bassLine] (Hz), from the highest down: E3 to C1. */
    val BASS_NOTES = doubleArrayOf(164.8, 130.8, 110.0, 87.3, 73.4, 61.7, 49.0, 41.2, 32.7)

    /** Length of each note of [bassLine] (seconds). */
    const val NOTE_SECONDS = 1.3

    /**
     * A bass line going down a note at a time, plucked (each note fades as a string would), pure
     * tones so no harmonic gives away a note the device cannot play: [bassLineNote] says which is
     * heard.
     */
    fun bassLine(rate: Int): FloatArray {
        val note = (NOTE_SECONDS * rate).toInt()
        val out = FloatArray(note * BASS_NOTES.size * 2)
        for ((n, hz) in BASS_NOTES.withIndex()) {
            val sound = (note * 0.85).toInt()
            for (i in 0 until sound) {
                val t = i.toDouble() / rate
                val pluck = (1 - exp(-t * 200)) * exp(-t * 1.6)
                val v = (sin(2 * PI * hz * t) * TONE_LEVEL * 1.3 * pluck * envelope(i, sound, rate * 0.05)).toFloat()
                out[(n * note + i) * 2] = v
                out[(n * note + i) * 2 + 1] = v
            }
        }
        return out
    }

    /** The note of [bassLine] heard at [frame]. */
    fun bassLineNote(frame: Int, rate: Int): Int =
        (frame / (NOTE_SECONDS * rate).toInt()) % BASS_NOTES.size

    // ---- A groove, when the library has no sample to give ----

    /**
     * Eight seconds of music made here, for when no track can be used: a kick and a bass, hi-hats on
     * the right, a clap on the left, wide chords; the first half quiet, the second loud, so every
     * step has something to hear.
     */
    fun groove(rate: Int): FloatArray {
        val beat = rate / 2 // 120 bpm
        val total = beat * 16
        val out = FloatArray(total * 2)
        val noise = PinkNoise(11)
        val bassNotes = doubleArrayOf(55.0, 55.0, 65.4, 49.0)
        val chords = arrayOf(doubleArrayOf(220.0, 261.6, 329.6), doubleArrayOf(196.0, 246.9, 293.7),
            doubleArrayOf(174.6, 220.0, 261.6), doubleArrayOf(196.0, 246.9, 293.7))
        val hat = Band(rate, 7000.0, 14000.0)
        val clapBand = Band(rate, 900.0, 5000.0)
        for (i in 0 until total) {
            val b = i / beat
            val inBeat = (i % beat).toDouble() / rate
            val bar = b / 4
            // Quiet first half, loud second half.
            val level = if (b < 8) 0.25 else 1.0
            var l = 0.0
            var r = 0.0
            // Kick on every beat: a falling sine.
            val kf = 50 + 90 * exp(-inBeat * 40)
            val kick = sin(2 * PI * kf * inBeat) * exp(-inBeat * 9) * 0.55
            l += kick; r += kick
            // Bass on the off-beats, a little saturated so it has some body.
            val half = ((i + beat / 2) % beat).toDouble() / rate
            val bn = bassNotes[bar % 4]
            val bass = tanh(sin(2 * PI * bn * (i.toDouble() / rate)) * 1.6) * exp(-half * 4) * 0.3
            l += bass; r += bass
            // Hi-hat on eighths, right.
            val eighth = (i % (beat / 2)).toDouble() / rate
            val n = noise.next()
            val h = hat.process(n) * exp(-eighth * 60) * 0.35
            r += h; l += h * 0.15
            // Clap on beats 2 and 4, left.
            val clap = if (b % 2 == 1) clapBand.process(n) * exp(-inBeat * 25) * 0.6 else { clapBand.process(0f); 0.0 }
            l += clap; r += clap * 0.15
            // Chords, slightly different on each side so they sound wide.
            val t = i.toDouble() / rate
            var cl = 0.0
            var cr = 0.0
            for (f in chords[bar % 4]) {
                cl += triangle(f * t)
                cr += triangle(f * 1.004 * t + 0.25)
            }
            val swell = 0.06 * (0.6 + 0.4 * sin(2 * PI * t / 4))
            l += cl * swell; r += cr * swell
            out[i * 2] = (l * level).toFloat()
            out[i * 2 + 1] = (r * level).toFloat()
        }
        // The loop's ends meet without a click.
        val fade = (rate * 0.01).toInt()
        for (i in 0 until fade) {
            val g = i.toFloat() / fade
            out[i * 2] *= g; out[i * 2 + 1] *= g
            out[(total - 1 - i) * 2] *= g; out[(total - 1 - i) * 2 + 1] *= g
        }
        return out
    }

    private fun triangle(phase: Double): Double {
        val x = phase - kotlin.math.floor(phase)
        return 4 * kotlin.math.abs(x - 0.5) - 1
    }

    /** A raised-cosine rise over the first [fade] frames of [length] and fall over the last. */
    private fun envelope(i: Int, length: Int, fade: Double): Double {
        val f = fade.coerceAtLeast(1.0)
        val rise = (i / f).coerceAtMost(1.0)
        val fall = ((length - 1 - i) / f).coerceAtMost(1.0)
        val g = minOf(rise, fall).coerceAtLeast(0.0)
        return 0.5 - 0.5 * kotlin.math.cos(PI * g)
    }

    /** Pink noise (Paul Kellet's filter on white noise), repeatable from [seed]. */
    private class PinkNoise(seed: Long) {
        private val random = java.util.Random(seed)
        private var b0 = 0.0; private var b1 = 0.0; private var b2 = 0.0
        private var b3 = 0.0; private var b4 = 0.0; private var b5 = 0.0; private var b6 = 0.0

        fun next(): Float {
            val w = random.nextDouble() * 2 - 1
            b0 = 0.99886 * b0 + w * 0.0555179
            b1 = 0.99332 * b1 + w * 0.0750759
            b2 = 0.96900 * b2 + w * 0.1538520
            b3 = 0.86650 * b3 + w * 0.3104856
            b4 = 0.55000 * b4 + w * 0.5329522
            b5 = -0.7616 * b5 - w * 0.0168980
            val pink = b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362
            b6 = w * 0.115926
            return (pink * 0.11).toFloat()
        }
    }

    /** A band of [lowHz] to [highHz]: one-pole high-pass then one-pole low-pass. */
    private class Band(rate: Int, lowHz: Double, highHz: Double) {
        private val hp = exp(-2 * PI * lowHz / rate)
        private val lp = 1 - exp(-2 * PI * highHz.coerceAtMost(rate * 0.45) / rate)
        private var lastIn = 0.0
        private var hpOut = 0.0
        private var lpOut = 0.0

        fun process(x: Float): Double {
            hpOut = hp * (hpOut + x - lastIn)
            lastIn = x.toDouble()
            lpOut += lp * (hpOut - lpOut)
            return lpOut * 1.5
        }
    }
}
