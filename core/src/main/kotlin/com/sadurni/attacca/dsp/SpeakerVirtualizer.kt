package com.sadurni.attacca.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * Virtual stereo speakers for headphones, a stronger alternative to [Bs2b].
 *
 * Each channel reaches both ears as it would from a speaker 30° off centre, following the
 * spherical-head model of Brown & Duda: a head-shadow filter per ear (the far ear loses the
 * highs) and the interaural delay (the far ear hears it later). A few faint early reflections
 * then move the image out of the head, as a room would.
 */
class SpeakerVirtualizer(private val sampleRate: Int) : StereoCrossfeed {

    private val nearLeft = HeadShadow(sampleRate, alpha(NEAR_EAR_DEGREES))
    private val nearRight = HeadShadow(sampleRate, alpha(NEAR_EAR_DEGREES))
    private val farLeft = HeadShadow(sampleRate, alpha(FAR_EAR_DEGREES))
    private val farRight = HeadShadow(sampleRate, alpha(FAR_EAR_DEGREES))

    /** Delay line for both sources (left, right interleaved); long enough for the reflections. */
    private val delaySize = (sampleRate * MAX_DELAY_MS / 1000).toInt() + 2
    private val farDelay = FloatArray(delaySize * 2)
    private val roomDelay = FloatArray(delaySize * 2)
    private var writePos = 0

    private val itd = Tap(ITD_SECONDS.toFloat() * sampleRate, FAR_GAIN, cross = true)

    // Reflections are a little duller than the direct sound.
    private val roomCoef = exp(-2.0 * PI * ROOM_LOWPASS_HZ / sampleRate).toFloat()
    private var roomLeft = 0f
    private var roomRight = 0f

    // Arrays rather than lists: no iterator per frame on the audio thread.
    private val leftTaps = REFLECTIONS.map { (ms, gain, cross) -> Tap(ms * sampleRate / 1000f, gain, cross) }.toTypedArray()
    private val rightTaps = REFLECTIONS.map { (ms, gain, cross) -> Tap((ms + RIGHT_EAR_OFFSET_MS) * sampleRate / 1000f, gain, cross) }.toTypedArray()

    override var outLeft = 0f
        private set
    override var outRight = 0f
        private set

    /** A fixed fractional delay, split once into whole frames and the interpolation fraction. */
    private class Tap(delayFrames: Float, val gain: Float, val cross: Boolean) {
        val frames = delayFrames.toInt()
        val frac = delayFrames - frames
    }

    override fun processFrame(left: Float, right: Float) {
        val inLeft = left + ANTI_DENORMAL_F
        val inRight = right + ANTI_DENORMAL_F
        // Head shadow for the far ear, delayed by the interaural time difference.
        farDelay[writePos * 2] = farLeft.process(inLeft)
        farDelay[writePos * 2 + 1] = farRight.process(inRight)
        roomLeft = inLeft + roomCoef * (roomLeft - inLeft)
        roomRight = inRight + roomCoef * (roomRight - inRight)
        roomDelay[writePos * 2] = roomLeft
        roomDelay[writePos * 2 + 1] = roomRight

        // Left ear: the left speaker directly, the right speaker around the head.
        var l = nearLeft.process(inLeft) + read(farDelay, itd, 1)
        var r = nearRight.process(inRight) + read(farDelay, itd, 0)
        for (tap in leftTaps) l += read(roomDelay, tap, if (tap.cross) 1 else 0)
        for (tap in rightTaps) r += read(roomDelay, tap, if (tap.cross) 0 else 1)

        outLeft = l * OUTPUT_GAIN
        outRight = r * OUTPUT_GAIN
        writePos = if (writePos + 1 == delaySize) 0 else writePos + 1
    }

    /**
     * Reads [channel] [tap] frames back, scaled by its gain. Reading backwards in time, the
     * fraction leans towards the older frame.
     */
    private fun read(line: FloatArray, tap: Tap, channel: Int): Float {
        var i = writePos - tap.frames
        if (i < 0) i += delaySize
        var j = i - 1
        if (j < 0) j += delaySize
        val a = line[i * 2 + channel]
        val b = line[j * 2 + channel]
        return (a + (b - a) * tap.frac) * tap.gain
    }

    override fun clear() {
        nearLeft.clear(); nearRight.clear(); farLeft.clear(); farRight.clear()
        farDelay.fill(0f)
        roomDelay.fill(0f)
        roomLeft = 0f
        roomRight = 0f
        writePos = 0
    }

    /**
     * First-order head-shadow filter (Brown & Duda): unity at low frequencies, [alpha] times the
     * level at high frequencies, turning over around c / (2·head radius).
     */
    private class HeadShadow(sampleRate: Int, alpha: Double) {
        private val b0: Float
        private val b1: Float
        private val a1: Float
        private var x1 = 0f
        private var y1 = 0f

        init {
            val w0 = SPEED_OF_SOUND / HEAD_RADIUS
            val k = 2.0 * sampleRate / (2 * w0) // bilinear transform of s / (2·w0)
            val a0 = 1 + k
            b0 = ((1 + alpha * k) / a0).toFloat()
            b1 = ((1 - alpha * k) / a0).toFloat()
            a1 = ((1 - k) / a0).toFloat()
        }

        fun process(x: Float): Float {
            val y = b0 * x + b1 * x1 - a1 * y1
            x1 = x
            y1 = y
            return y
        }

        fun clear() {
            x1 = 0f
            y1 = 0f
        }
    }

    companion object {
        private const val SPEED_OF_SOUND = 343.0
        private const val HEAD_RADIUS = 0.0875

        /** Speakers at ±30°: 60° from the near ear's axis, 120° from the far one's. */
        private const val NEAR_EAR_DEGREES = 60.0
        private const val FAR_EAR_DEGREES = 120.0

        /** Woodworth's interaural time difference for a source 30° off centre. */
        private val ITD_SECONDS = HEAD_RADIUS / SPEED_OF_SOUND * (sin(PI / 6) + PI / 6)

        /** Bass reaches the far ear almost as loud; slightly less keeps the image wide. */
        private const val FAR_GAIN = 0.8f

        /** Keeps centred material at its original level. */
        private const val OUTPUT_GAIN = 1f / (1f + FAR_GAIN)

        private const val ROOM_LOWPASS_HZ = 5000.0
        private const val MAX_DELAY_MS = 25f
        private const val RIGHT_EAR_OFFSET_MS = 0.6f

        /** Early reflections: delay (ms), gain, whether it comes from the other speaker. */
        private val REFLECTIONS = listOf(
            Triple(5.1f, 0.16f, false),
            Triple(8.7f, 0.12f, true),
            Triple(13.3f, 0.09f, false),
            Triple(19.1f, 0.06f, true),
        )

        /** Head-shadow high-frequency gain for a source [degrees] away from the ear's axis. */
        private fun alpha(degrees: Double): Double = 1.05 + 0.95 * cos(degrees / 150.0 * PI)
    }
}
