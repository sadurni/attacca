package com.sadurni.attaccaplg.analysis

import com.sadurni.attacca.analysis.LoudnessMeter
import com.sadurni.attacca.analysis.TrackAnalysis
import com.sadurni.attacca.chain.EdgeDescriptor
import com.sadurni.attacca.chain.EdgeFeatures
import com.sadurni.attacca.chain.TrackFeatures
import com.sadurni.attaccaplg.audio.Decoder
import java.io.File
import kotlin.math.abs

/** Everything measured on one file, as Attacca measures it on the phone. */
class AnalyzedTrack(
    val head: EdgeDescriptor?,
    val tail: EdgeDescriptor?,
    val features: TrackFeatures?,
    /** Duration, where the sound starts and ends, loudness and peak (EBU R128). */
    val analysis: TrackAnalysis,
    /** What it sounds like for families and look-alikes (Attacca's sound profile). */
    val sound: com.sadurni.attacca.chain.SoundProfile? = null,
)

/**
 * Decodes a file once and measures it: the descriptors of its first and last seconds of sound
 * (for chaining), its features (30 s from the middle), where the sound starts and ends, and its
 * loudness. The edges and the middle are taken with the same rules as Attacca's EdgeDecoder and
 * AudioAnalyzer, so PC and phone describe a track alike.
 */
object TrackAnalyzer {

    /** Anything quieter counts as silence when looking for the first and last sound (as Attacca's EdgeDecoder). */
    private const val SILENCE = 0.003f

    /** Searched at the end beyond the edge: room for a short silence or fade… */
    private const val TAIL_MARGIN_US = 4_000_000L

    /** …or, when that was not enough, this much (long silence, hidden track). */
    private const val LONG_MARGIN_US = 22_000_000L

    /** Silence threshold for the bounds of the sound (Attacca's default trim threshold). */
    const val THRESHOLD_DB = -60f

    /** Null when the file cannot be decoded. Blocking. */
    fun analyze(file: File, edgeSeconds: Int = EdgeFeatures.DEFAULT_EDGE_SECONDS): AnalyzedTrack? {
        val collector = Collector()
        val format = Decoder.decode(file, collector::start, collector::add) ?: return null
        if (collector.count == 0) return null
        val rate = format.sampleRate
        val mono = collector.mono
        val count = collector.count
        val durationUs = count * 1_000_000L / rate

        val edgeUs = edgeSeconds * 1_000_000L
        val tailUs = edgeUs + TAIL_MARGIN_US
        val longUs = edgeUs + LONG_MARGIN_US
        fun frames(us: Long) = (us * rate / 1_000_000).toInt()
        val head: EdgeDescriptor?
        val tail: EdgeDescriptor?
        if (durationUs <= 2 * tailUs) {
            head = describeHead(mono, 0, count, frames(edgeUs), rate)
            tail = describeTail(mono, 0, count, frames(edgeUs), rate)
        } else {
            // The phone decodes the start only up to the edge after the first sound (within the
            // long margin) and the end only its last seconds, more when the sound stops earlier.
            head = describeHead(mono, 0, minOf(count, frames(longUs)), frames(edgeUs), rate)
            var from = count - frames(tailUs)
            if (!hasFullTail(mono, from, count, frames(edgeUs)) && durationUs > longUs) from = count - frames(longUs)
            tail = describeTail(mono, from.coerceAtLeast(0), count, frames(edgeUs), rate)
        }

        val span = TrackFeatures.SPAN_SECONDS * rate
        val features = if (count <= span) {
            TrackFeatures.measure(mono, count, rate)
        } else {
            val from = (count - span) / 2
            TrackFeatures.measure(mono.copyOfRange(from, from + span), span, rate)
        }

        // As Attacca's EdgeDecoder.decodeSound: windows spread over stretches at a quarter, half and
        // three quarters, or over the start of a short file.
        val window = com.sadurni.attacca.chain.SoundProfile.windowSamples(rate)
        val windows = com.sadurni.attacca.chain.SoundProfile.windowStarts(count.toDouble() / rate).map { at ->
            val from = (at * rate).toInt().coerceIn(0, count)
            mono.copyOfRange(from, minOf(from + window, count))
        }
        val sound = runCatching { com.sadurni.attacca.chain.SoundProfile.measure(windows, rate, soundNet) }.getOrNull()

        collector.windows.flush()
        val analysis = TrackAnalysis.fromWindows(
            collector.windows.peaks, collector.windows.meanSquares, collector.windows.count, durationUs / 1000, THRESHOLD_DB,
        ).withLoudness(collector.meter?.integratedLufs(), collector.meter?.samplePeak ?: 0f)
        return AnalyzedTrack(head, tail, features, analysis, sound)
    }

    /** Attacca's sound network, read once from the program's files; shared by the analysis threads. */
    private val soundNet: com.sadurni.attacca.chain.SoundNet by lazy {
        TrackAnalyzer::class.java.getResourceAsStream("/soundnet.bin")!!.use { com.sadurni.attacca.chain.SoundNet(it) }
    }

    private fun hasFullTail(mono: FloatArray, from: Int, to: Int, edgeFrames: Int): Boolean {
        val end = lastSound(mono, from, to) ?: return false
        return end - from >= edgeFrames
    }

    private fun lastSound(mono: FloatArray, from: Int, to: Int): Int? =
        (to - 1 downTo from).firstOrNull { abs(mono[it]) > SILENCE }?.plus(1)

    private fun describeHead(mono: FloatArray, from: Int, to: Int, edgeFrames: Int, rate: Int): EdgeDescriptor? {
        val start = (from until to).firstOrNull { abs(mono[it]) > SILENCE } ?: return null
        val end = minOf(to, start + edgeFrames)
        return EdgeFeatures.describe(mono, start, end, rate)
    }

    private fun describeTail(mono: FloatArray, from: Int, to: Int, edgeFrames: Int, rate: Int): EdgeDescriptor? {
        val end = lastSound(mono, from, to) ?: return null
        val start = maxOf(from, end - edgeFrames)
        return EdgeFeatures.describe(mono, start, end, rate)
    }

    /** Gathers, from the decoded blocks: the mono mix, 10 ms windows and the loudness meter. */
    private class Collector {
        var mono = FloatArray(1 shl 20)
        var count = 0
        var channels = 1
        var meter: LoudnessMeter? = null
        var windows = Windows(441)

        fun start(format: com.sadurni.attaccaplg.audio.PcmFormat) {
            // A retry with another decoder starts over.
            channels = format.channels
            count = 0
            meter = LoudnessMeter(format.sampleRate, format.channels)
            windows = Windows(format.sampleRate * TrackAnalysis.WINDOW_MS / 1000)
        }

        fun add(samples: FloatArray, frames: Int) {
            if (mono.size < count + frames) mono = mono.copyOf(maxOf(count + frames, mono.size * 2))
            val scale = 1f / channels
            var s = 0
            for (f in 0 until frames) {
                var sum = 0f
                var peak = 0f
                for (c in 0 until channels) {
                    val v = samples[s++]
                    sum += v
                    val a = abs(v)
                    if (a > peak) peak = a
                }
                mono[count + f] = sum * scale
                windows.add(peak)
            }
            meter?.process(samples, frames)
            count += frames
        }
    }

    /** Per-window peak and mean square from per-frame peaks (as Attacca's AudioAnalyzer). */
    private class Windows(windowFrames: Int) {
        private val windowFrames = maxOf(1, windowFrames)
        var peaks = FloatArray(4096)
        var meanSquares = FloatArray(4096)
        var count = 0
        private var framesInWindow = 0
        private var windowPeak = 0f
        private var windowSum = 0.0

        fun add(framePeak: Float) {
            if (framePeak > windowPeak) windowPeak = framePeak
            windowSum += framePeak * framePeak
            if (++framesInWindow >= windowFrames) flush()
        }

        fun flush() {
            if (framesInWindow == 0) return
            if (count == peaks.size) {
                peaks = peaks.copyOf(count * 2)
                meanSquares = meanSquares.copyOf(count * 2)
            }
            peaks[count] = windowPeak
            meanSquares[count] = (windowSum / framesInWindow).toFloat()
            count++
            framesInWindow = 0
            windowPeak = 0f
            windowSum = 0.0
        }
    }
}
