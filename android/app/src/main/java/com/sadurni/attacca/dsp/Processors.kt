package com.sadurni.attacca.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import com.sadurni.attacca.settings.CrossfeedPreset
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * First stage of the chain: converts 16-bit or float PCM to float and applies ReplayGain + preamp.
 *
 * Gain changes are ramped across a buffer so switching tracks or moving the preamp slider never
 * clicks.
 */
class GainProcessor(private val engine: DspEngine) : BaseAudioProcessor() {

    private var currentGain = 1f
    private var inputEncoding = C.ENCODING_INVALID
    private var scratch = FloatArray(0)
    private var shorts = ShortArray(0)

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        val encoding = inputAudioFormat.encoding
        if (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        inputEncoding = encoding
        return AudioFormat(inputAudioFormat.sampleRate, inputAudioFormat.channelCount, C.ENCODING_PCM_FLOAT)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        val bytesPerSample = if (inputEncoding == C.ENCODING_PCM_16BIT) 2 else 4
        val sampleCount = input.remaining() / bytesPerSample
        if (sampleCount == 0) return
        val channels = inputAudioFormat.channelCount
        val frameCount = sampleCount / channels

        // A pending profile switch: fade this buffer out, then switch; the next buffer ramps up
        // from silence with the new settings.
        val switching = engine.pendingSettings != null
        val target = if (switching) 0f else dbToLinear(engine.currentGainDb())
        val start = currentGain
        val step = if (frameCount > 0) (target - start) / frameCount else 0f

        // Whole buffers in and out: element-wise buffer access is slow on the audio thread.
        val samples = frameCount * channels
        if (scratch.size < samples) scratch = FloatArray(samples)
        val data = scratch
        var gain = start
        var i = 0
        if (inputEncoding == C.ENCODING_PCM_16BIT) {
            if (shorts.size < samples) shorts = ShortArray(samples)
            input.asShortBuffer().get(shorts, 0, samples)
            // Conversion to float and gain in one pass.
            for (frame in 0 until frameCount) {
                gain += step
                val scale = gain * (1f / 32768f)
                for (ch in 0 until channels) {
                    data[i] = shorts[i] * scale
                    i++
                }
            }
        } else {
            input.asFloatBuffer().get(data, 0, samples)
            for (frame in 0 until frameCount) {
                gain += step
                for (ch in 0 until channels) {
                    data[i] *= gain
                    i++
                }
            }
        }
        val output = replaceOutputBuffer(samples * 4)
        output.asFloatBuffer().put(data, 0, samples)
        output.position(samples * 4)
        // Drop any trailing partial frame.
        input.position(input.limit())
        currentGain = target
        output.flip()
        if (switching) engine.commitPendingSettings()
    }

    override fun onQueueEndOfStream() {
        // The crossfade stage scales the ending track's pre-decoded tail by the same gain.
        engine.endedStreamGain = currentGain
    }

    override fun onReset() {
        currentGain = 1f
        inputEncoding = C.ENCODING_INVALID
    }
}

/**
 * Base class for stages that take and produce interleaved float PCM.
 *
 * Gapless track changes: DefaultAudioSink drains the chain to end of stream and flushes it at
 * every new track (more than once), even when the format stays the same. Resetting filters or
 * flushing a look-ahead delay there would break the waveform at the joint (a click, and the delay
 * would play as a few milliseconds of silence), so when the renderer has announced a new track in
 * the same format the stage keeps its state and its held frames, and the music flows on. A flush
 * without an end of stream (a seek), a format change or the end of the list resets as before.
 */
abstract class FloatAudioProcessor(private val engine: DspEngine) : BaseAudioProcessor() {

    private var scratch = FloatArray(0)

    private var configuredRate = 0
    private var configuredChannels = 0

    /** Format announced for the next flush (the sink configures before draining). */
    private var nextRate = 0
    private var nextChannels = 0

    /** Generation of the track whose buffers are coming in; taken from its first buffer. */
    private var streamGeneration = -1
    private var awaitingFirstInput = true

    /** The state is carried over a track change instead of being reset. */
    private var carrying = false

    final override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        // Applied at the flush: buffers of the current format may still be draining.
        nextRate = inputAudioFormat.sampleRate
        nextChannels = inputAudioFormat.channelCount
        return inputAudioFormat
    }

    final override fun queueInput(inputBuffer: ByteBuffer) {
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        val channels = inputAudioFormat.channelCount
        val frames = input.remaining() / (4 * channels)
        if (frames == 0) {
            input.position(input.limit())
            return
        }
        if (awaitingFirstInput) {
            // The renderer announces a track before handing over its first buffer.
            awaitingFirstInput = false
            streamGeneration = engine.streamGeneration
            carrying = false
        }
        val samples = frames * channels
        if (scratch.size < samples) scratch = FloatArray(samples)
        input.asFloatBuffer().get(scratch, 0, samples)
        input.position(input.limit())

        process(scratch, frames, channels)

        val output = replaceOutputBuffer(samples * 4)
        output.asFloatBuffer().put(scratch, 0, samples)
        output.position(samples * 4)
        output.flip()
    }

    private var tailPending = false
    private var endOfStreamSeen = false

    final override fun onQueueEndOfStream() {
        // Media3's pipeline signals end of stream again on every pass while draining; the tail
        // must go out only once or the stage never ends.
        if (endOfStreamSeen) return
        endOfStreamSeen = true
        // A new track announced (or, with nothing since the last flush, still the same change),
        // in the same format: keep going instead of ending.
        val trackChange = if (awaitingFirstInput) carrying else engine.streamGeneration != streamGeneration
        carrying = trackChange && nextRate == configuredRate && nextChannels == configuredChannels
        tailPending = !carrying && tailFrameCount() > 0
    }

    override fun getOutput(): ByteBuffer {
        val pending = super.getOutput()
        if (pending.hasRemaining() || !tailPending) return pending
        // Flush what is still held internally once everything else has been consumed.
        tailPending = false
        val channels = inputAudioFormat.channelCount
        val tailFrames = tailFrameCount()
        val samples = tailFrames * channels
        val tail = FloatArray(samples)
        process(tail, tailFrames, channels)
        val output = replaceOutputBuffer(samples * 4)
        output.asFloatBuffer().put(tail, 0, samples)
        output.position(samples * 4)
        output.flip()
        return super.getOutput()
    }

    override fun isEnded(): Boolean = super.isEnded() && !tailPending

    final override fun onFlush() {
        val keep = endOfStreamSeen && carrying
        tailPending = false
        endOfStreamSeen = false
        awaitingFirstInput = true
        if (nextRate != configuredRate || nextChannels != configuredChannels) {
            configuredRate = nextRate
            configuredChannels = nextChannels
            configure(nextRate, nextChannels)
            resetState()
            carrying = false
        } else if (!keep) {
            carrying = false
            resetState()
        }
    }

    final override fun onReset() {
        tailPending = false
        endOfStreamSeen = false
        awaitingFirstInput = true
        carrying = false
        configuredRate = 0
        configuredChannels = 0
        streamGeneration = -1
        resetState()
        scratch = FloatArray(0)
    }

    protected abstract fun configure(sampleRate: Int, channels: Int)

    /** Processes [frames] interleaved frames in place. */
    protected abstract fun process(buffer: FloatArray, frames: Int, channels: Int)

    protected abstract fun resetState()

    /** Frames still held internally (e.g. a look-ahead delay) to flush at end of stream. */
    protected open fun tailFrameCount(): Int = 0
}


class CrossfeedProcessor(private val engine: DspEngine) : FloatAudioProcessor(engine) {

    private val bs2b = Bs2b()
    private var speakers: SpeakerVirtualizer? = null
    private var usingSpeakers = false
    private var sampleRate = 0
    private var configuredCutoff = -1
    private var configuredFeed = Float.NaN

    /** Wet/dry mix, ramped so toggling crossfeed never clicks. */
    private var mix = 0f

    override fun configure(sampleRate: Int, channels: Int) {
        this.sampleRate = sampleRate
        configuredCutoff = -1
        speakers = if (channels == 2) SpeakerVirtualizer(sampleRate) else null
    }

    override fun process(buffer: FloatArray, frames: Int, channels: Int) {
        val s = engine.settings
        val targetMix = if (s.crossfeedEnabled && channels == 2) 1f else 0f
        if (targetMix == 0f && mix == 0f) {
            resetState()
            return
        }
        val speakersWanted = s.crossfeedPreset == CrossfeedPreset.SPEAKERS
        if (speakersWanted != usingSpeakers) {
            usingSpeakers = speakersWanted
            resetState()
        }
        val speakers = speakers
        val filter: StereoCrossfeed = if (usingSpeakers && speakers != null) {
            speakers
        } else {
            if (s.crossfeedCutoffHz != configuredCutoff || s.crossfeedFeedDb != configuredFeed) {
                configuredCutoff = s.crossfeedCutoffHz
                configuredFeed = s.crossfeedFeedDb
                bs2b.setParameters(sampleRate, configuredCutoff, configuredFeed)
            }
            bs2b
        }
        val step = 1f / maxOf(1, sampleRate / 20) // 50 ms
        var i = 0
        for (frame in 0 until frames) {
            if (mix < targetMix) mix = minOf(targetMix, mix + step)
            else if (mix > targetMix) mix = maxOf(targetMix, mix - step)
            val l = buffer[i]
            val r = buffer[i + 1]
            filter.processFrame(l, r)
            buffer[i] = l + (filter.outLeft - l) * mix
            buffer[i + 1] = r + (filter.outRight - r) * mix
            i += 2
        }
    }

    override fun resetState() {
        bs2b.clear()
        speakers?.clear()
    }
}

/**
 * EQ (with its precut), dynamic bass, channels, stereo width and balance.
 *
 * Every setting moves smoothly when it changes, so dragging a slider or switching a mode never
 * clicks: EQ bands step by at most [EQ_STEP_DB] per buffer (and fade out when the EQ is switched
 * off); width, channels, balance and precut are followed frame by frame across each buffer.
 */
class EffectsProcessor(private val engine: DspEngine) : FloatAudioProcessor(engine) {

    private var eq: GraphicEq? = null
    private var bass: DynamicBass? = null
    private var width: StereoWidth? = null
    private var bassActive = false
    private var virtualBass: VirtualBass? = null
    private var peq: ParametricEq? = null

    /** Highest boost of the parametric bands, worked out once per set of bands. */
    private var peqBoostFor: List<PeqBand>? = null
    private var peqBoost = 0f

    /** EQ band gains being applied, moving towards the setting. */
    private val eqNow = FloatArray(com.sadurni.attacca.settings.DspSettings.EQ_BANDS)

    /** Width (1 = unchanged) being applied. */
    private var widthNow = 1.0

    /** Channel matrix (left from left, left from right, right from left, right from right) with balance and precut. */
    private val matrixNow = floatArrayOf(1f, 0f, 0f, 1f)

    override fun configure(sampleRate: Int, channels: Int) {
        eq = GraphicEq(sampleRate, channels)
        bass = DynamicBass(sampleRate, channels)
        width = if (channels == 2) StereoWidth(sampleRate) else null
        virtualBass = VirtualBass(sampleRate)
        peq = ParametricEq(sampleRate, channels)
    }

    override fun process(buffer: FloatArray, frames: Int, channels: Int) {
        val s = engine.settings
        processEq(buffer, frames, s)
        virtualBass?.let { vb ->
            val target = if (s.virtualBassEnabled) s.virtualBassAmount / 100.0 else 0.0
            if (target > 0 || vb.isActive) vb.process(buffer, frames, channels, s.virtualBassCutoffHz, target)
        }
        // Keep running briefly after being switched off so the boost fades instead of clicking.
        if (s.bassEnabled || bassActive) {
            bass?.process(buffer, frames, DynamicBass.freqHz(s), if (s.bassEnabled) s.bassMaxGainDb else 0f, s.bassCeilingDb,
                s.bassCurve == com.sadurni.attacca.settings.BassCurve.GENTLE)
            bassActive = s.bassEnabled || bass?.isBoosting == true
        }
        val targetWidth = if (s.widthEnabled) s.widthPercent / 100.0 else 1.0
        if (channels == 2 && (targetWidth != 1.0 || widthNow != 1.0)) {
            width?.process(buffer, frames, widthNow, targetWidth, s.widthBassSafe)
        }
        widthNow = targetWidth
        applyMatrix(buffer, frames, channels, targetMatrix(s, channels))
    }

    private fun processEq(buffer: FloatArray, frames: Int, s: com.sadurni.attacca.settings.DspSettings) {
        val parametric = s.eqType == com.sadurni.attacca.settings.EqType.PARAMETRIC
        peq?.let { p ->
            val on = s.eqEnabled && parametric
            if (on || p.isActive) p.process(buffer, frames, s.peqBands, on)
        }
        val eq = eq ?: return
        var active = false
        for (b in eqNow.indices) {
            val target = if (s.eqEnabled && !parametric) s.eqGains.getOrElse(b) { 0f } else 0f
            val delta = target - eqNow[b]
            eqNow[b] = if (kotlin.math.abs(delta) <= EQ_STEP_DB) target else eqNow[b] + kotlin.math.sign(delta) * EQ_STEP_DB
            if (eqNow[b] != 0f) active = true
        }
        if (!active) return
        eq.setGains(eqNow.toList())
        eq.process(buffer, frames)
    }

    /** Channels, balance and the precuts of the EQ and the bass as one 2×2 matrix (a single gain for mono). */
    private fun targetMatrix(s: com.sadurni.attacca.settings.DspSettings, channels: Int): FloatArray {
        val precut = dbToLinear(-DynamicBass.precutDb(s, engine.currentHeadroomDb())) * when {
            !s.eqEnabled -> 1f
            s.eqType == com.sadurni.attacca.settings.EqType.PARAMETRIC -> {
                if (peqBoostFor != s.peqBands) {
                    peqBoostFor = s.peqBands
                    peqBoost = ParametricEq.maxBoostDb(s.peqBands)
                }
                // The preamp set (or brought by a correction), lowered further if the bands would still go over.
                val pre = if (s.eqAutoPrecut) minOf(s.peqPreampDb, -peqBoost) else s.peqPreampDb
                dbToLinear(pre)
            }
            s.eqAutoPrecut -> dbToLinear(-maxOf(0f, s.eqGains.maxOrNull() ?: 0f))
            else -> 1f
        }
        if (channels != 2) return floatArrayOf(precut, 0f, 0f, precut)
        val m = when (s.channelMode) {
            com.sadurni.attacca.settings.ChannelMode.STEREO -> floatArrayOf(1f, 0f, 0f, 1f)
            com.sadurni.attacca.settings.ChannelMode.MONO -> floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f)
            com.sadurni.attacca.settings.ChannelMode.LEFT -> floatArrayOf(1f, 0f, 1f, 0f)
            com.sadurni.attacca.settings.ChannelMode.RIGHT -> floatArrayOf(0f, 1f, 0f, 1f)
            com.sadurni.attacca.settings.ChannelMode.SWAP -> floatArrayOf(0f, 1f, 1f, 0f)
            com.sadurni.attacca.settings.ChannelMode.KARAOKE -> floatArrayOf(0.5f, -0.5f, -0.5f, 0.5f)
        }
        val left = minOf(1f, 1f - s.balance) * precut
        val right = minOf(1f, 1f + s.balance) * precut
        return floatArrayOf(m[0] * left, m[1] * left, m[2] * right, m[3] * right)
    }

    private fun applyMatrix(buffer: FloatArray, frames: Int, channels: Int, target: FloatArray) {
        val identity = target.contentEquals(IDENTITY)
        if (identity && matrixNow.contentEquals(IDENTITY)) return
        val step = FloatArray(4) { (target[it] - matrixNow[it]) / frames }
        val m = matrixNow
        var i = 0
        if (channels == 2) {
            for (f in 0 until frames) {
                for (k in 0 until 4) m[k] += step[k]
                val l = buffer[i]
                val r = buffer[i + 1]
                buffer[i] = m[0] * l + m[1] * r
                buffer[i + 1] = m[2] * l + m[3] * r
                i += 2
            }
        } else {
            for (f in 0 until frames) {
                m[0] += step[0]
                for (ch in 0 until channels) buffer[i + ch] *= m[0]
                i += channels
            }
        }
        target.copyInto(matrixNow)
    }

    override fun resetState() {
        eq?.clear()
        bass?.clear()
        width?.clear()
        virtualBass?.clear()
        peq?.clear()
        bassActive = false
    }

    private companion object {
        const val EQ_STEP_DB = 0.5f
        val IDENTITY = floatArrayOf(1f, 0f, 0f, 1f)
    }
}

class CompressorProcessor(private val engine: DspEngine) : FloatAudioProcessor(engine) {

    private var compressor: Compressor? = null

    override fun configure(sampleRate: Int, channels: Int) {
        compressor = Compressor(sampleRate, channels)
    }

    override fun process(buffer: FloatArray, frames: Int, channels: Int) {
        val s = engine.settings
        if (!s.compressorEnabled) {
            compressor?.clear()
            return
        }
        compressor?.process(
            buffer,
            frames,
            s.compressorThresholdDb,
            s.compressorRatio,
            s.compressorKneeDb,
            s.compressorAttackMs,
            s.compressorReleaseMs,
            s.effectiveMakeupDb,
        )
    }

    override fun resetState() {
        compressor?.clear()
    }
}

class LimiterProcessor(private val engine: DspEngine) : FloatAudioProcessor(engine) {

    private var limiter: LookaheadLimiter? = null
    private var rate = 0

    override fun configure(sampleRate: Int, channels: Int) {
        limiter = LookaheadLimiter(sampleRate, channels)
        rate = sampleRate
        engine.tap.configure(sampleRate, channels)
    }

    override fun process(buffer: FloatArray, frames: Int, channels: Int) {
        val s = engine.settings
        limiter?.process(buffer, frames, s.limiterEnabled, dbToLinear(s.limiterCeilingDb))
        // Last stage: what goes out, kept for a skip by hand, and its levels for O'rama.
        val time = engine.presentationUs
        engine.tap.push(buffer, frames, time)
        engine.meter.push(buffer, frames, channels, rate, if (time == androidx.media3.common.C.TIME_UNSET) com.sadurni.attacca.diorama.LevelMeter.UNKNOWN else time)
    }

    override fun resetState() {
        limiter?.clear()
        engine.tap.clear()
        engine.meter.clear()
    }

    override fun tailFrameCount(): Int = limiter?.latencyFrames ?: 0
}
