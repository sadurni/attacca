package com.sadurni.attacca.dsp

import com.sadurni.attacca.settings.ChannelMode
import com.sadurni.attacca.settings.CrossfeedPreset
import com.sadurni.attacca.settings.DspSettings
import com.sadurni.attacca.settings.EqType
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sign

/**
 * Attacca's sound processing on a stream of stereo floats, in the phone's order: preamp, EQ
 * (graphic or parametric, with its precut), virtual and dynamic bass, stereo width, channels and
 * balance, crossfeed (or virtual speakers), compressor and limiter. Every setting moves smoothly
 * when it changes (EQ bands by at most [EQ_STEP_DB] per block, the rest across each block), so
 * dragging a slider never clicks. One chain per stream; [clear] after a jump.
 */
class EffectsChain(private val sampleRate: Int) {

    private val eq = GraphicEq(sampleRate, 2)
    private val peq = ParametricEq(sampleRate, 2)
    private val bass = DynamicBass(sampleRate, 2)
    private val virtualBass = VirtualBass(sampleRate)
    private val width = StereoWidth(sampleRate)
    private val bs2b = Bs2b()
    private val speakers = SpeakerVirtualizer(sampleRate)
    private val compressor = Compressor(sampleRate, 2)
    private val limiter = LookaheadLimiter(sampleRate, 2)

    private val eqNow = FloatArray(DspSettings.EQ_BANDS)
    private var bassActive = false
    private var widthNow = 1.0
    private val matrixNow = floatArrayOf(1f, 0f, 0f, 1f)
    private var preampNow = 1f
    private var crossfeedMix = 0f
    private var usingSpeakers = false
    private var cutoff = -1
    private var feed = Float.NaN
    private var peqBoostFor: List<PeqBand>? = null
    private var peqBoost = 0f

    /**
     * Room the track playing leaves under full scale after its gain (dB); the gentle bass boost
     * lowers the level only by what it lacks (see [DynamicBass.precutDb]).
     */
    var headroomDb = 0f

    /** Frames the limiter holds back (its look-ahead). */
    val latencyFrames: Int get() = limiter.latencyFrames

    /** Processes [frames] interleaved stereo frames of [buffer] in place with [s]. */
    fun process(buffer: FloatArray, frames: Int, s: DspSettings) {
        preamp(buffer, frames, if (s.preampEnabled) dbToLinear(s.preampDb) else 1f)
        processEq(buffer, frames, s)
        val vbTarget = if (s.virtualBassEnabled) s.virtualBassAmount / 100.0 else 0.0
        if (vbTarget > 0 || virtualBass.isActive) virtualBass.process(buffer, frames, 2, s.virtualBassCutoffHz, vbTarget)
        if (s.bassEnabled || bassActive) {
            bass.process(buffer, frames, DynamicBass.freqHz(s), if (s.bassEnabled) s.bassMaxGainDb else 0f, s.bassCeilingDb,
                s.bassCurve == com.sadurni.attacca.settings.BassCurve.GENTLE)
            bassActive = s.bassEnabled || bass.isBoosting
        }
        val targetWidth = if (s.widthEnabled) s.widthPercent / 100.0 else 1.0
        if (targetWidth != 1.0 || widthNow != 1.0) width.process(buffer, frames, widthNow, targetWidth, s.widthBassSafe)
        widthNow = targetWidth
        applyMatrix(buffer, frames, targetMatrix(s))
        crossfeed(buffer, frames, s)
        if (s.compressorEnabled) {
            compressor.process(buffer, frames, s.compressorThresholdDb, s.compressorRatio, s.compressorKneeDb,
                s.compressorAttackMs, s.compressorReleaseMs, s.effectiveMakeupDb)
        } else {
            compressor.clear()
        }
        limiter.process(buffer, frames, s.limiterEnabled, dbToLinear(s.limiterCeilingDb))
    }

    /** Forgets the sound so far (after a seek or a new queue). */
    fun clear() {
        eq.clear(); peq.clear(); bass.clear(); virtualBass.clear(); width.clear()
        bs2b.clear(); speakers.clear(); compressor.clear(); limiter.clear()
        bassActive = false
    }

    private fun preamp(buffer: FloatArray, frames: Int, target: Float) {
        if (target == 1f && preampNow == 1f) return
        val step = (target - preampNow) / frames
        var g = preampNow
        for (f in 0 until frames) {
            g += step
            buffer[2 * f] *= g
            buffer[2 * f + 1] *= g
        }
        preampNow = target
    }

    private fun processEq(buffer: FloatArray, frames: Int, s: DspSettings) {
        val parametric = s.eqType == EqType.PARAMETRIC
        val peqOn = s.eqEnabled && parametric
        if (peqOn || peq.isActive) peq.process(buffer, frames, s.peqBands, peqOn)
        var active = false
        for (b in eqNow.indices) {
            val target = if (s.eqEnabled && !parametric) s.eqGains.getOrElse(b) { 0f } else 0f
            val delta = target - eqNow[b]
            eqNow[b] = if (abs(delta) <= EQ_STEP_DB) target else eqNow[b] + sign(delta) * EQ_STEP_DB
            if (eqNow[b] != 0f) active = true
        }
        if (!active) return
        eq.setGains(eqNow.toList())
        eq.process(buffer, frames)
    }

    /** Channels, balance and the precuts of the EQ and the bass as one 2×2 matrix. */
    private fun targetMatrix(s: DspSettings): FloatArray {
        val precut = dbToLinear(-DynamicBass.precutDb(s, headroomDb)) * when {
            !s.eqEnabled -> 1f
            s.eqType == EqType.PARAMETRIC -> {
                if (peqBoostFor != s.peqBands) {
                    peqBoostFor = s.peqBands
                    peqBoost = ParametricEq.maxBoostDb(s.peqBands)
                }
                dbToLinear(if (s.eqAutoPrecut) minOf(s.peqPreampDb, -peqBoost) else s.peqPreampDb)
            }
            s.eqAutoPrecut -> dbToLinear(-maxOf(0f, s.eqGains.maxOrNull() ?: 0f))
            else -> 1f
        }
        val m = when (s.channelMode) {
            ChannelMode.STEREO -> floatArrayOf(1f, 0f, 0f, 1f)
            ChannelMode.MONO -> floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f)
            ChannelMode.LEFT -> floatArrayOf(1f, 0f, 1f, 0f)
            ChannelMode.RIGHT -> floatArrayOf(0f, 1f, 0f, 1f)
            ChannelMode.SWAP -> floatArrayOf(0f, 1f, 1f, 0f)
            ChannelMode.KARAOKE -> floatArrayOf(0.5f, -0.5f, -0.5f, 0.5f)
        }
        val left = minOf(1f, 1f - s.balance) * precut
        val right = minOf(1f, 1f + s.balance) * precut
        return floatArrayOf(m[0] * left, m[1] * left, m[2] * right, m[3] * right)
    }

    private fun applyMatrix(buffer: FloatArray, frames: Int, target: FloatArray) {
        if (target.contentEquals(IDENTITY) && matrixNow.contentEquals(IDENTITY)) return
        val step = FloatArray(4) { (target[it] - matrixNow[it]) / frames }
        val m = matrixNow
        var i = 0
        for (f in 0 until frames) {
            for (k in 0 until 4) m[k] += step[k]
            val l = buffer[i]
            val r = buffer[i + 1]
            buffer[i] = m[0] * l + m[1] * r
            buffer[i + 1] = m[2] * l + m[3] * r
            i += 2
        }
        target.copyInto(matrixNow)
    }

    private fun crossfeed(buffer: FloatArray, frames: Int, s: DspSettings) {
        val target = if (s.crossfeedEnabled) 1f else 0f
        if (target == 0f && crossfeedMix == 0f) return
        val wantSpeakers = s.crossfeedPreset == CrossfeedPreset.SPEAKERS
        if (wantSpeakers != usingSpeakers) {
            usingSpeakers = wantSpeakers
            bs2b.clear()
            speakers.clear()
        }
        val filter: StereoCrossfeed = if (usingSpeakers) speakers else {
            if (s.crossfeedCutoffHz != cutoff || s.crossfeedFeedDb != feed) {
                cutoff = s.crossfeedCutoffHz
                feed = s.crossfeedFeedDb
                bs2b.setParameters(sampleRate, cutoff, feed)
            }
            bs2b
        }
        val step = 1f / maxOf(1, sampleRate / 20)
        var i = 0
        for (f in 0 until frames) {
            if (crossfeedMix < target) crossfeedMix = minOf(target, crossfeedMix + step)
            else if (crossfeedMix > target) crossfeedMix = maxOf(target, crossfeedMix - step)
            val l = buffer[i]
            val r = buffer[i + 1]
            filter.processFrame(l, r)
            buffer[i] = l + (filter.outLeft - l) * crossfeedMix
            buffer[i + 1] = r + (filter.outRight - r) * crossfeedMix
            i += 2
        }
    }

    private fun dbToLinear(db: Float): Float = 10f.pow(db / 20f)

    private companion object {
        const val EQ_STEP_DB = 0.5f
        val IDENTITY = floatArrayOf(1f, 0f, 0f, 1f)
    }
}
