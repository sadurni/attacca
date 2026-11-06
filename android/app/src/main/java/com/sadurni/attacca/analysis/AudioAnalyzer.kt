package com.sadurni.attacca.analysis

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Decodes a whole file once with the platform decoder and collects, per 10 ms window, the peak
 * and mean-square level. Silence bounds and the waveform are both derived from that single pass.
 */
object AudioAnalyzer {

    private const val TIMEOUT_US = 10_000L

    /** Returns null when the file cannot be decoded. Blocking; call off the main thread. */
    fun analyze(file: File, thresholdDb: Float, isCancelled: () -> Boolean = { false }): TrackAnalysis? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return null

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT

            val windows = WindowAccumulator(sampleRate * TrackAnalysis.WINDOW_MS / 1000)
            // Loudness from the same decoded samples (EBU R128).
            var meter = LoudnessMeter(sampleRate, channels)
            var floats = FloatArray(0)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var totalFrames = 0L

            while (!outputDone) {
                if (isCancelled()) return null
                // Every input buffer the decoder takes, then its output: waiting for one with each
                // packet left the decoder idle most of the time.
                var fed = false
                while (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(0)
                    if (inIndex < 0) break
                    fed = true
                    val buffer = codec.getInputBuffer(inIndex)!!
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, if (fed) 0 else TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = codec.outputFormat
                        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            encoding = format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                        windows.setWindowFrames(sampleRate * TrackAnalysis.WINDOW_MS / 1000)
                        if (totalFrames == 0L) meter = LoudnessMeter(sampleRate, channels)
                    }
                    outIndex >= 0 -> {
                        val buffer = codec.getOutputBuffer(outIndex)!!.order(ByteOrder.nativeOrder())
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        // Whole buffers as floats: for the windows and for the loudness meter.
                        val samples = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) buffer.remaining() / 4 else buffer.remaining() / 2
                        if (floats.size < samples) floats = FloatArray(samples)
                        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                            buffer.asFloatBuffer().get(floats, 0, samples)
                        } else {
                            val shorts = buffer.asShortBuffer()
                            for (s in 0 until samples) floats[s] = shorts.get() / 32768f
                        }
                        val frames = samples / channels
                        var s = 0
                        for (f in 0 until frames) {
                            var peak = 0f
                            for (c in 0 until channels) peak = maxOf(peak, abs(floats[s++]))
                            windows.add(peak)
                        }
                        meter.process(floats, frames)
                        totalFrames += frames
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            windows.flush()
            if (totalFrames == 0L || sampleRate <= 0) return null
            val durationMs = totalFrames * 1000 / sampleRate
            return TrackAnalysis.fromWindows(windows.peaks, windows.meanSquares, windows.count, durationMs, thresholdDb)
                .withLoudness(meter.integratedLufs(), meter.samplePeak)
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    /** Collects per-window peak and mean square from per-frame peaks. */
    private class WindowAccumulator(private var windowFrames: Int) {
        var peaks = FloatArray(4096)
        var meanSquares = FloatArray(4096)
        var count = 0
        private var framesInWindow = 0
        private var windowPeak = 0f
        private var windowSum = 0.0

        fun setWindowFrames(frames: Int) {
            windowFrames = maxOf(1, frames)
        }

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
