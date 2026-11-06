package com.sadurni.attacca.analysis

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Where the sound of a file starts and ends, as [AudioAnalyzer] finds it (the first and last
 * [TrackAnalysis.WINDOW_MS] window with a sample above the threshold in any channel), decoding
 * only the beginning until the sound starts and the last seconds: no waveform, no loudness, a
 * fraction of the time of a whole decode.
 */
object SilenceScanner {

    /** Where the audible part of a file is. */
    class Bounds(val durationMs: Long, val audioStartMs: Long, val audioEndMs: Long)

    private const val TIMEOUT_US = 10_000L

    /** How much of the end is decoded, widened while no sound is found in it (the last: the whole file). */
    private val TAIL_SPANS_US = longArrayOf(15_000_000L, 60_000_000L, Long.MAX_VALUE)

    /** Decoded before a part read from the middle and not looked at: the decoder settles meanwhile. */
    private const val PRE_ROLL_US = 200_000L

    /** Returns null when the file cannot be decoded. Blocking; call off the main thread. */
    fun scan(file: File, thresholdDb: Float, isCancelled: () -> Boolean = { false }): Bounds? {
        val threshold = dbToLinear(thresholdDb)
        // The start: decoded from the beginning (as the whole analysis does) until the first sound.
        val head = decode(file, 0, threshold, stopAtSound = true, isCancelled) ?: return null
        if (head.firstSound < 0) {
            // Silent all through (the head went to the end): nothing to trim safely.
            val durationMs = head.endFrame * 1000 / head.sampleRate
            return Bounds(durationMs, 0, durationMs)
        }
        val windowFrames = (head.sampleRate * TrackAnalysis.WINDOW_MS / 1000).coerceAtLeast(1)
        val startMs = head.firstSound / windowFrames * TrackAnalysis.WINDOW_MS
        val durationUs = durationUs(file)
        for (span in TAIL_SPANS_US) {
            val from = if (durationUs == null || span == Long.MAX_VALUE || span >= durationUs) 0L else durationUs - span
            val tail = if (from == 0L) decode(file, 0, threshold, stopAtSound = false, isCancelled) ?: return null
            else decode(file, from, threshold, stopAtSound = false, isCancelled, framesOffset = head.offset) ?: return null
            if (tail.lastSound >= 0 || from == 0L) {
                val last = if (tail.lastSound >= 0) tail.lastSound else head.firstSound
                val durationMs = tail.endFrame * 1000 / tail.sampleRate
                val endMs = minOf(durationMs, (last / windowFrames + 1) * TrackAnalysis.WINDOW_MS)
                return Bounds(durationMs, startMs, endMs)
            }
        }
        return null
    }

    /**
     * What a decode found, in frames counted from the start of the file as a whole decode counts
     * them: the first and last frame above the threshold (-1: none) and where the decode ended.
     * [offset]: frames counted minus frames by the decoder's own times (some decoders drop the
     * encoder's delay), measured from the start to place parts read from the middle.
     */
    private class Found(val firstSound: Long, val lastSound: Long, val endFrame: Long, val sampleRate: Int, val offset: Long)

    private fun durationUs(file: File): Long? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            val track = audioTrack(extractor) ?: return null
            extractor.getTrackFormat(track).takeIf { it.containsKey(MediaFormat.KEY_DURATION) }?.getLong(MediaFormat.KEY_DURATION)
        } catch (e: Exception) {
            null
        } finally {
            extractor.release()
        }
    }

    private fun audioTrack(extractor: MediaExtractor): Int? = (0 until extractor.trackCount).firstOrNull {
        extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
    }

    /**
     * Decodes from [fromUs] (the beginning when 0) to the end, or until the first sound with
     * [stopAtSound]. From the middle, frames are placed by the decoder's times plus [framesOffset];
     * in compressed formats the packets are walked from the start to get there (see below).
     */
    private fun decode(
        file: File,
        fromUs: Long,
        threshold: Float,
        stopAtSound: Boolean,
        isCancelled: () -> Boolean,
        framesOffset: Long = 0,
    ): Found? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val trackIndex = audioTrack(extractor) ?: return null
            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return null
            val fromMiddle = fromUs > 0
            if (fromMiddle) {
                val target = (fromUs - PRE_ROLL_US).coerceAtLeast(0)
                // Only plain PCM is placed exactly by a seek; in compressed formats the times after a
                // seek are estimated (a VBR MP3 by a second or more, Ogg by a window or two), while
                // walking the packets from the start (no decoding) counts them exactly.
                if (mime != MediaFormat.MIMETYPE_AUDIO_RAW) {
                    var frames = 0
                    while (extractor.sampleTime in 0 until target) {
                        if (!extractor.advance()) break
                        if (++frames % 1000 == 0 && isCancelled()) return null
                    }
                } else {
                    extractor.seekTo(target, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                }
            }

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var floats = FloatArray(0)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var counted = 0L // frames decoded so far (from the beginning: the whole decode's count)
            var first = -1L
            var last = -1L
            var end = 0L
            var offset = 0L

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
                    }
                    outIndex >= 0 -> {
                        val buffer = codec.getOutputBuffer(outIndex)!!.order(ByteOrder.nativeOrder())
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val isFloat = encoding == AudioFormat.ENCODING_PCM_FLOAT
                        val samples = if (isFloat) buffer.remaining() / 4 else buffer.remaining() / 2
                        val frames = samples / channels
                        // Where this buffer's first frame is in the whole file's count.
                        val byTime = info.presentationTimeUs * sampleRate / 1_000_000
                        val base = if (fromMiddle) byTime + framesOffset else counted
                        if (!fromMiddle && frames > 0) offset = counted - byTime
                        val looked = !fromMiddle || info.presentationTimeUs >= fromUs
                        if (frames > 0 && looked) {
                            if (floats.size < samples) floats = FloatArray(samples)
                            if (isFloat) {
                                buffer.asFloatBuffer().get(floats, 0, samples)
                            } else {
                                val shorts = buffer.asShortBuffer()
                                for (s in 0 until samples) floats[s] = shorts.get() / 32768f
                            }
                            var s = 0
                            for (f in 0 until frames) {
                                var audible = false
                                for (c in 0 until channels) if (abs(floats[s++]) > threshold) audible = true
                                if (audible) {
                                    if (first < 0) first = base + f
                                    last = base + f
                                }
                            }
                        }
                        counted += frames
                        // The end-of-stream buffer may come empty and with time 0: it moves nothing.
                        if (frames > 0) end = base + frames
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        // Half a second at least: the offset between count and times has settled by then.
                        if (stopAtSound && first >= 0 && counted >= sampleRate / 2) outputDone = true
                    }
                }
            }
            if (counted == 0L || sampleRate <= 0) return null
            return Found(first, last, end, sampleRate, offset)
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }
}
