package com.sadurni.attacca.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Keeps the output at one sample rate ([OUTPUT_RATE]) and in stereo, whatever the tracks are.
 *
 * A track at another rate, or in mono, makes DefaultAudioSink build a new AudioTrack: it lets the
 * old one play out and only then starts the new one, and the music stops for a moment (heard as a
 * short cut at the joint, in the middle of a crossfade). With the output always the same, the
 * AudioTrack stays. Tracks already at that rate in stereo pass untouched (the stage is off).
 *
 * The rate is changed by a windowed-sinc filter (Kaiser window), with one row of coefficients per
 * phase of the ratio between the rates. Gapless track changes in the same format keep the filter's
 * state, as [FloatAudioProcessor] does; at a change of format what the filter still holds goes out
 * first, so nothing of the ending track is lost.
 */
class OutputFormatProcessor(private val engine: DspEngine) : BaseAudioProcessor() {

    /** Format announced for the next flush (the sink configures before draining). */
    private var nextRate = 0
    private var nextChannels = 0

    /** Generation of the track whose buffers are coming in; taken from its first buffer. */
    private var streamGeneration = -1
    private var awaitingFirstInput = true

    /** The filter's state is carried over a track change instead of being reset. */
    private var carrying = false
    private var endOfStreamSeen = false

    /** What the filter still holds must go out (end of stream, no track in the same format next). */
    private var endPending = false

    private var outChannels = 2

    /** The input rate per output rate, reduced: each output frame moves [num] / [den] input frames. */
    private var num = 1
    private var den = 1

    /** Coefficients: [rows] phases × [taps]. Empty when only the channels change. */
    private var table = FloatArray(0)
    private var rows = 1
    private var taps = 0
    private var half = 0

    /** Input frames (interleaved, already in [outChannels]); [pos] is where the next output sits. */
    private var buf = FloatArray(0)
    private var bufFrames = 0
    private var pos = 0
    private var phase = 0

    private var outScratch = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) throw UnhandledAudioFormatException(inputAudioFormat)
        nextRate = inputAudioFormat.sampleRate
        nextChannels = inputAudioFormat.channelCount
        if (inputAudioFormat.sampleRate == OUTPUT_RATE && inputAudioFormat.channelCount >= 2) return AudioFormat.NOT_SET
        return AudioFormat(OUTPUT_RATE, maxOf(2, inputAudioFormat.channelCount), C.ENCODING_PCM_FLOAT)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        val inChannels = inputAudioFormat.channelCount
        val frames = input.remaining() / (4 * inChannels)
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
        val floats = input.asFloatBuffer()
        input.position(input.limit())
        ensureBuffer(bufFrames + frames)
        val c = outChannels
        var at = bufFrames * c
        if (inChannels == 1) {
            for (f in 0 until frames) {
                val x = floats.get(f)
                buf[at++] = x
                buf[at++] = x
            }
        } else {
            floats.get(buf, at, frames * c)
        }
        bufFrames += frames
        if (padPending) {
            // Before the first frame, the music mirrored (silence there would dip its start).
            padPending = false
            reflect(pos - 1, pos, -1)
        }
        emit(bufFrames)
    }

    /**
     * Fills [half] frames from [first] going in [direction] with the music mirrored around frame
     * [pivot] (odd reflection: it goes on smoothly from there).
     */
    private fun reflect(first: Int, pivot: Int, direction: Int) {
        val c = outChannels
        for (k in 0 until half) {
            val target = first + k * direction
            if (target < 0) break
            val source = (pivot - (k + 1) * direction).coerceIn(0, bufFrames - 1)
            for (ch in 0 until c) buf[target * c + ch] = 2 * buf[pivot * c + ch] - buf[source * c + ch]
        }
    }

    /** Produces every output frame whose filter lies before [available] input frames. */
    private fun emit(available: Int) {
        val c = outChannels
        if (table.isEmpty()) {
            // Same rate: only the channels change.
            val samples = (available - pos) * c
            putOutput(buf, pos * c, samples)
            pos = available
            compact()
            return
        }
        val maxOut = ((available - pos).toLong() * den / num + 2).toInt().coerceAtLeast(0) * c
        if (outScratch.size < maxOut) outScratch = FloatArray(maxOut)
        val out = outScratch
        var o = 0
        val t = taps
        while (pos + half < available) {
            val row = (if (den <= MAX_PHASES) phase else (phase.toLong() * rows / den).toInt()) * t
            val first = (pos - half + 1) * c
            if (c == 2) {
                var l = 0f
                var r = 0f
                var i = first
                for (k in 0 until t) {
                    val h = table[row + k]
                    l += buf[i] * h
                    r += buf[i + 1] * h
                    i += 2
                }
                out[o++] = l
                out[o++] = r
            } else {
                for (ch in 0 until c) {
                    var sum = 0f
                    var i = first + ch
                    for (k in 0 until t) {
                        sum += buf[i] * table[row + k]
                        i += c
                    }
                    out[o++] = sum
                }
            }
            phase += num
            pos += phase / den
            phase %= den
        }
        putOutput(out, 0, o)
        compact()
    }

    private fun putOutput(from: FloatArray, offset: Int, samples: Int) {
        val output = replaceOutputBuffer(samples * 4)
        output.asFloatBuffer().put(from, offset, samples)
        output.position(samples * 4)
        output.flip()
    }

    /** Drops the input frames no output needs any more. */
    private fun compact() {
        val drop = pos - maxOf(0, half - 1)
        if (drop <= 0) return
        val c = outChannels
        System.arraycopy(buf, drop * c, buf, 0, (bufFrames - drop) * c)
        bufFrames -= drop
        pos -= drop
    }

    private fun ensureBuffer(frames: Int) {
        if (buf.size < frames * outChannels) buf = buf.copyOf(maxOf(frames, bufFrames * 2) * outChannels)
    }

    override fun onQueueEndOfStream() {
        // The pipeline repeats this call while draining; act once.
        if (endOfStreamSeen) return
        endOfStreamSeen = true
        // A new track announced in the same format: the filter goes on with it.
        val trackChange = if (awaitingFirstInput) carrying else engine.streamGeneration != streamGeneration
        carrying = trackChange && nextRate == inputAudioFormat.sampleRate && nextChannels == inputAudioFormat.channelCount
        endPending = !carrying && table.isNotEmpty() && pos < bufFrames
    }

    override fun getOutput(): ByteBuffer {
        val pending = super.getOutput()
        if (pending.hasRemaining() || !endPending) return pending
        // The last input frames, with the music mirrored after them for the filter to reach past
        // the end (silence there would dip the last milliseconds).
        endPending = false
        val end = bufFrames
        ensureBuffer(end + half)
        reflect(end, end - 1, 1)
        bufFrames = end + half
        // Every output at a position before the end now has its whole filter in the buffer.
        emit(bufFrames)
        return super.getOutput()
    }

    override fun isEnded(): Boolean = super.isEnded() && !endPending

    override fun onFlush() {
        val keep = endOfStreamSeen && carrying
        endOfStreamSeen = false
        endPending = false
        awaitingFirstInput = true
        if (!keep || inputAudioFormat.sampleRate != rateOfTable || inputAudioFormat.channelCount != channelsOfTable) setUp()
    }

    override fun onReset() {
        endOfStreamSeen = false
        endPending = false
        awaitingFirstInput = true
        carrying = false
        streamGeneration = -1
        rateOfTable = 0
        channelsOfTable = 0
        table = FloatArray(0)
        buf = FloatArray(0)
        bufFrames = 0
        outScratch = FloatArray(0)
    }

    private var rateOfTable = 0
    private var channelsOfTable = 0

    /** Fresh filter for the current input format. */
    private fun setUp() {
        carrying = false
        val rate = inputAudioFormat.sampleRate
        val channels = inputAudioFormat.channelCount
        if (rate <= 0 || channels <= 0) return
        outChannels = maxOf(2, channels)
        if (rate != rateOfTable) {
            rateOfTable = rate
            buildTable(rate)
        }
        channelsOfTable = channels
        // Room before the first frame, so the first output can sit on it; filled when it comes.
        bufFrames = maxOf(0, half - 1)
        ensureBuffer(bufFrames + 4096)
        java.util.Arrays.fill(buf, 0, bufFrames * outChannels, 0f)
        pos = bufFrames
        phase = 0
        padPending = table.isNotEmpty()
    }

    private var padPending = false

    private fun buildTable(rate: Int) {
        if (rate == OUTPUT_RATE) {
            table = FloatArray(0)
            num = 1
            den = 1
            half = 0
            taps = 0
            return
        }
        val g = gcd(rate, OUTPUT_RATE)
        num = rate / g
        den = OUTPUT_RATE / g
        rows = minOf(den, MAX_PHASES)
        // Below the lower of the two Nyquist frequencies; going down, the filter widens with the ratio.
        val scale = minOf(1.0, OUTPUT_RATE.toDouble() / rate)
        val cutoff = CUTOFF * scale
        val reach = ZERO_CROSSINGS / scale
        half = ceil(reach).toInt()
        taps = 2 * half
        table = FloatArray(rows * taps)
        val i0Beta = besselI0(KAISER_BETA)
        for (j in 0 until rows) {
            val frac = j.toDouble() / rows
            var sum = 0.0
            val row = DoubleArray(taps)
            for (k in 0 until taps) {
                val d = (k - half + 1) - frac
                val x = d / reach
                val window = if (x <= -1.0 || x >= 1.0) 0.0 else besselI0(KAISER_BETA * sqrt(1 - x * x)) / i0Beta
                val arg = PI * cutoff * d
                val sinc = if (arg == 0.0) 1.0 else sin(arg) / arg
                row[k] = cutoff * sinc * window
                sum += row[k]
            }
            // Exactly unity gain at every phase.
            for (k in 0 until taps) table[j * taps + k] = (row[k] / sum).toFloat()
        }
    }

    private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

    private fun besselI0(x: Double): Double {
        var sum = 1.0
        var term = 1.0
        var k = 1
        while (term > 1e-12 * sum) {
            val h = x / (2 * k)
            term *= h * h
            sum += term
            k++
        }
        return sum
    }

    companion object {
        /** The rate every track leaves the chain at: the one most music is recorded at. */
        const val OUTPUT_RATE = 44_100

        /** Filter length on each side, in zero crossings of the sinc. */
        private const val ZERO_CROSSINGS = 32.0

        /** Fraction of the lower Nyquist frequency the filter passes. */
        private const val CUTOFF = 0.92

        /** About 85 dB of rejection. */
        private const val KAISER_BETA = 8.5

        /** Phases kept for ratios that would need more (unusual rates): the nearest one is used. */
        private const val MAX_PHASES = 1024
    }
}
