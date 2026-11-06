package com.sadurni.attacca.dsp

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Crossfade inside the single audio stream.
 *
 * A crossfaded track is clipped where the blend should start, and the rest of it is decoded in
 * the background ahead of time ([CrossfadeTail], published in [DspEngine.tails]). When the
 * clipped track ends, that tail is lined up with the last played frames and blended into the
 * next track as the next track's buffers come in, the levels moving as the tail's
 * [CrossfadeShape] says (equal-power curves). The output never
 * waits for anything, so there is no gap, and every output frame is a frame of the next track,
 * so the player's position needs no correction.
 *
 * Each track keeps its own ReplayGain: the next track gets it from the gain stage before this
 * one, and the tail is scaled by the gain the ending track had.
 *
 * Decoders pad the end of what they decode with silence (the platform MP3 decoder adds 529
 * zero frames at every end of stream). Between a clipped track and its tail, or between two
 * gapless tracks, that padding would be a short dropout, so trailing digital silence is held back
 * briefly and dropped when a tail or another track follows.
 *
 * Track changes: DefaultAudioSink reconfigures at every new track (more than once), draining
 * this processor to end of stream and flushing it. An end of stream right after the renderer
 * announced a new track is a track change and the tail waits over the flushes for the next
 * track's buffers. Any other end of stream (end of the list) plays the tail out as it is. A flush
 * without a preceding end of stream is a seek and drops everything, unless it is a skip by hand
 * that left a tail of where the music was ([DspEngine.skipTail]): that one is blended into the
 * track skipped to.
 */
class CrossfadeProcessor(private val engine: DspEngine) : BaseAudioProcessor() {

    private enum class State { IDLE, WAITING, MIXING, PLAYING_OUT }

    private var channels = 0
    private var sampleRate = 0

    /** Format announced for the next configuration (the sink configures before draining). */
    private var nextChannels = 0
    private var nextSampleRate = 0

    /** Generation of the track whose buffers are coming in; taken from its first buffer. */
    private var streamGeneration = -1
    private var awaitingFirstInput = true

    /** The last played frames of the current track (mono, circular), to align its tail. */
    private val history = FloatArray(HISTORY_FRAMES)
    private var historyPos = 0
    private var historyCount = 0

    private var state = State.IDLE
    /** The tail being blended (read in place from [fadeOffset], scaled by [fadeGain]). */
    private var fade = FloatArray(0)
    private var fadeOffset = 0
    private var fadeGain = 1f
    private var fadeFrames = 0
    private var fadePos = 0
    private var fadeChannels = 0
    private var fadeRate = 0
    private var fadeShape = CrossfadeShape.CROSSFADE

    /** A blend after a skip by hand that has not had any of the new track yet. */
    private var skipArmed = false

    /** The blend is one after a skip by hand. */
    private var skipFade = false

    /** Length of the ramp to silence at the end of every tail. */
    private val endRampFrames: Int get() = maxOf(1, fadeRate / 100)

    /** Zero frames held back at the end of what came in (possible decoder padding). */
    private var heldZeros = 0

    private var endOfStreamSeen = false

    private var inScratch = FloatArray(0)
    private var outScratch = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) throw UnhandledAudioFormatException(inputAudioFormat)
        nextChannels = inputAudioFormat.channelCount
        nextSampleRate = inputAudioFormat.sampleRate
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        val frames = input.remaining() / (4 * channels)
        if (frames == 0) {
            input.position(input.limit())
            return
        }
        if (awaitingFirstInput) {
            // The renderer announces a track before handing over its first buffer.
            awaitingFirstInput = false
            streamGeneration = engine.streamGeneration
        }

        if (state == State.MIXING && (fadeChannels != channels || fadeRate != sampleRate)) finishFade()
        val mixing = state == State.MIXING

        // Whole buffers in and out: element-wise buffer access is slow on the audio thread.
        val samples = frames * channels
        if (inScratch.size < samples) inScratch = FloatArray(samples)
        input.asFloatBuffer().get(inScratch, 0, samples)
        input.position(input.limit())
        val maxOut = (frames + heldZeros) * channels
        if (outScratch.size < maxOut) outScratch = FloatArray(maxOut)
        val src = inScratch
        val out = outScratch
        var o = 0

        if (mixing) skipArmed = false
        // The curves are computed exactly every GAIN_BLOCK frames and followed linearly in
        // between (no trigonometry per frame).
        var gainOut = 0f
        var gainIn = 1f
        var stepOut = 0f
        var stepIn = 0f
        var blockLeft = 0

        for (f in 0 until frames) {
            val i = f * channels
            var mono = 0f
            var silent = true
            for (ch in 0 until channels) {
                val x = src[i + ch]
                mono += x
                if (x != 0f) silent = false
            }
            history[historyPos] = mono / channels
            historyPos = if (historyPos + 1 == HISTORY_FRAMES) 0 else historyPos + 1
            if (historyCount < HISTORY_FRAMES) historyCount++

            if (!mixing && silent && heldZeros < MAX_HELD_ZEROS) {
                heldZeros++
                continue
            }
            if (heldZeros > 0) {
                java.util.Arrays.fill(out, o, o + heldZeros * channels, 0f)
                o += heldZeros * channels
                heldZeros = 0
            }
            if (mixing && fadePos < fadeFrames) {
                if (blockLeft == 0) {
                    blockLeft = minOf(GAIN_BLOCK, fadeFrames - fadePos)
                    val t0 = fadePos.toDouble() / fadeFrames
                    val t1 = (fadePos + blockLeft).toDouble() / fadeFrames
                    gainOut = fadeShape.outGain(t0).toFloat()
                    gainIn = fadeShape.inGain(t0).toFloat()
                    stepOut = (fadeShape.outGain(t1).toFloat() - gainOut) / blockLeft
                    stepIn = (fadeShape.inGain(t1).toFloat() - gainIn) / blockLeft
                }
                val base = fadeOffset + fadePos * channels
                // The tail's last few milliseconds always go down to silence, even when the
                // shape keeps it whole: its end need not be silent, and a cut would click.
                val left = fadeFrames - fadePos
                val edge = if (left < endRampFrames) left.toFloat() / endRampFrames else 1f
                val tailGain = gainOut * fadeGain * edge
                for (ch in 0 until channels) out[o++] = fade[base + ch] * tailGain + src[i + ch] * gainIn
                fadePos++
                gainOut += stepOut
                gainIn += stepIn
                blockLeft--
            } else {
                for (ch in 0 until channels) out[o++] = src[i + ch]
            }
        }
        val output = replaceOutputBuffer(o * 4)
        output.asFloatBuffer().put(out, 0, o)
        output.position(o * 4)
        if (state == State.MIXING && fadePos >= fadeFrames) finishFade()
        output.flip()
    }

    override fun onQueueEndOfStream() {
        // The pipeline repeats this call while draining; act once.
        if (endOfStreamSeen) return
        endOfStreamSeen = true
        // A blend in progress outlives the extra reconfigurations of a track change.
        if (state != State.IDLE) return
        // Without a new track announced, this is the end of the list.
        val trackChange = !awaitingFirstInput && engine.streamGeneration != streamGeneration
        if (!prepareFade()) {
            // Gapless into the next track: the held zeros are decoder padding, a few milliseconds
            // of dropout in the middle of the music. At the end of the list they go out in
            // getOutput.
            if (trackChange) heldZeros = 0
            return
        }
        heldZeros = 0 // Decoder padding between the track and its tail.
        state = if (trackChange) State.WAITING else State.PLAYING_OUT
    }

    /** Takes the ending track's tail, lines it up and scales it. False when there is none. */
    private fun prepareFade(): Boolean {
        if (awaitingFirstInput) return false
        val started = System.nanoTime()
        val period = engine.periodOfGeneration[streamGeneration] ?: return false.also {
            Log.d(TAG, "track ended: no period for generation $streamGeneration")
        }
        val tail = engine.tails[period] ?: return false.also {
            val expected = engine.blendingPeriods[period]
            Log.w(TAG, "track ended without a tail (${engine.tails.size} tails ready)")
            // A track that should have blended lost its end: heard as a cut.
            if (expected != null) TransitionLog.note("CUT  end of “${TransitionLog.nameOf(expected)}” was not decoded in time: its last seconds are lost")
            else TransitionLog.note("gapless")
        }
        if (tail.channels != channels || tail.sampleRate != sampleRate) {
            Log.w(TAG, "tail format ${tail.channels}ch ${tail.sampleRate} Hz, stream ${channels}ch $sampleRate Hz")
            TransitionLog.note("CUT  end of “${TransitionLog.nameOf(tail.source)}”: decoded as ${tail.channels} ch ${tail.sampleRate} Hz, played as $channels ch $sampleRate Hz")
            return false
        }

        val mono = tail.mono
        // What was played, without the held silence at the end.
        val played = FloatArray(maxOf(0, historyCount - heldZeros))
        val oldest = (historyPos - historyCount + HISTORY_FRAMES) % HISTORY_FRAMES
        for (i in played.indices) played[i] = history[(oldest + i) % HISTORY_FRAMES]
        // The player's clip end can be off by a second or two after a seek in a VBR MP3.
        val match = TailAligner.match(played, played.size, mono, tail.frames, tail.clipEndFrame, ALIGN_SEARCH_SECONDS * sampleRate)
        val start = match.frame
        val frames = tail.frames - start
        if (frames <= 0) {
            TransitionLog.note("CUT  end of “${TransitionLog.nameOf(tail.source)}”: lined up past its end")
            return false
        }

        // Read in place: no copy of seconds of audio on the audio thread.
        fade = tail.samples
        fadeOffset = start * channels
        fadeGain = engine.endedStreamGain
        fadeFrames = frames
        fadePos = 0
        fadeChannels = channels
        fadeRate = sampleRate
        fadeShape = tail.shape
        // How long the audio thread spent here (it must stay well below the audio buffer).
        val preparedMs = (System.nanoTime() - started) / 1_000_000
        Log.d(TAG, "blending ${frames * 1000L / sampleRate} ms of tail, aligned ${start - tail.clipEndFrame} frames from the clip end (match ${match.score}), prepared in $preparedMs ms")
        // An end placed where the decoder expected it, without matching what played, may jump: worth knowing.
        val lined = if (match.score == TailAligner.NO_MATCH) "NOT MATCHED (expected place used, ${played.size} frames heard)"
        else "lined up ${start - tail.clipEndFrame} frames from the expected place, match %.3f".format(java.util.Locale.ROOT, match.score)
        TransitionLog.note("blend  “${TransitionLog.nameOf(tail.source)}” into the next: ${TransitionLog.shapeName(tail.shape)} ${frames * 1000L / sampleRate} ms, $lined, ready in $preparedMs ms")
        return true
    }

    override fun getOutput(): ByteBuffer {
        val pending = super.getOutput()
        if (pending.hasRemaining()) return pending
        if (endOfStreamSeen && heldZeros > 0) {
            // No tail followed: the held silence was part of the track.
            val output = replaceOutputBuffer(heldZeros * channels * 4)
            repeat(heldZeros * channels) { output.putFloat(0f) }
            output.flip()
            heldZeros = 0
            return super.getOutput()
        }
        if (state != State.PLAYING_OUT) return pending
        val samples = (fadeFrames - fadePos) * fadeChannels
        val output = replaceOutputBuffer(samples * 4)
        val from = fadeOffset + fadePos * fadeChannels
        val scaled = FloatArray(samples) { fade[from + it] * fadeGain }
        output.asFloatBuffer().put(scaled, 0, samples)
        output.position(samples * 4)
        output.flip()
        finishFade()
        return super.getOutput()
    }

    override fun isEnded(): Boolean = super.isEnded() && state != State.PLAYING_OUT && heldZeros == 0

    override fun onFlush() {
        // After an end of stream this flush is a sink reconfiguration (it may come more than
        // once per track change); without one it is a seek.
        val reconfiguration = endOfStreamSeen
        channels = nextChannels
        sampleRate = nextSampleRate
        historyPos = 0
        historyCount = 0
        heldZeros = 0
        awaitingFirstInput = true
        when {
            reconfiguration && (state == State.WAITING || state == State.MIXING) -> {
                if (fadeChannels != channels || fadeRate != sampleRate) convertFade()
                state = State.MIXING
            }
            // A skip by hand: the seek, then possibly more flushes before the new track's first
            // buffer (a new format), which must not drop the blend.
            skipArmed && state == State.MIXING -> {
                if (fadeChannels != channels || fadeRate != sampleRate) convertFade()
            }
            else -> {
                finishFade()
                engine.takeSkipTail()?.let(::startSkipFade)
            }
        }
        endOfStreamSeen = false
    }

    /** Blends what was playing when the user skipped into the track skipped to. */
    private fun startSkipFade(skip: SkipTail) {
        val tail = skip.tail
        val frames = minOf(skip.frames, tail.frames - skip.startFrame)
        if (frames <= 0 || skip.startFrame < 0) return
        fade = tail.samples
        fadeOffset = skip.startFrame * tail.channels
        fadeGain = skip.gain
        fadeFrames = frames
        fadePos = 0
        fadeChannels = tail.channels
        fadeRate = tail.sampleRate
        fadeShape = skip.shape
        state = State.MIXING
        skipArmed = true
        skipFade = true
        engine.skipBlending = true
        if (channels > 0 && (fadeChannels != channels || fadeRate != sampleRate)) convertFade()
        Log.d(TAG, "skip by hand: blending ${frames * 1000L / tail.sampleRate} ms of where it was")
        TransitionLog.note("skip by hand: blending ${frames * 1000L / tail.sampleRate} ms of where it was")
    }

    /** The next track has another format: bring what is left of the tail to it. */
    private fun convertFade() {
        val remaining = fadeFrames - fadePos
        val from = fadeOffset + fadePos * fadeChannels
        val rest = FloatArray(remaining * fadeChannels) { fade[from + it] * fadeGain }
        fade = PcmConvert.convert(rest, remaining, fadeChannels, fadeRate, channels, sampleRate)
        fadeOffset = 0
        fadeGain = 1f
        fadeFrames = fade.size / channels
        fadePos = 0
        fadeChannels = channels
        fadeRate = sampleRate
    }

    private fun finishFade() {
        if (skipFade) engine.skipBlending = false
        skipFade = false
        state = State.IDLE
        skipArmed = false
        fadeShape = CrossfadeShape.CROSSFADE
        fade = FloatArray(0)
        fadeOffset = 0
        fadeGain = 1f
        fadeFrames = 0
        fadePos = 0
    }

    override fun onReset() {
        endOfStreamSeen = false
        onFlush()
        streamGeneration = -1
    }

    private companion object {
        const val TAG = "Crossfade"
        const val HISTORY_FRAMES = 4096
        const val ALIGN_SEARCH_SECONDS = 3

        /** Frames between exact computations of the blend's curves. */
        const val GAIN_BLOCK = 32

        /** About 90 ms: covers decoder padding and keeps the output flowing. */
        const val MAX_HELD_ZEROS = 4096
    }
}
