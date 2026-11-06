package com.sadurni.attacca.chain

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.Closeable
import java.io.File
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Decodes only the beginning and the end of a file (not the whole track) and describes the first
 * and last seconds of actual sound (see [com.sadurni.attacca.settings.ChainSettings.edgeSeconds]), skipping leading and trailing
 * silence.
 */
object EdgeDecoder {

    private const val TIMEOUT_US = 10_000L

    /** Decoded at the end beyond the edge: room for a short silence or fade… */
    private const val TAIL_MARGIN_US = 4_000_000L

    /** …or, when that was not enough, this much (long silence, hidden track). */
    private const val LONG_MARGIN_US = 22_000_000L

    /** Decoded and dropped before a window: a decoder needs a few frames after a seek to sound right. */
    private const val PREROLL_US = 100_000L

    /** How far back, and how many times, a seek is moved when it lands after the start of a window. */
    private const val SEEK_STEP_US = 500_000L
    private const val SEEK_TRIES = 8

    /** Anything quieter counts as silence when looking for where the sound starts and ends. */
    private const val SILENCE = 0.003f // about -50 dBFS

    /**
     * A file open for decoding several stretches of it in turn (its edges, the windows of its
     * sound profile): parsed and given a decoder once, then each stretch is a seek. Not for use
     * by several threads at once.
     */
    class Source private constructor(
        private val extractor: MediaExtractor,
        private val codec: MediaCodec,
        private val inputFormat: MediaFormat,
        /** Length of the file; [Long.MAX_VALUE] when unknown. */
        val durationUs: Long,
    ) : Closeable {

        private var used = false

        // The decoder tells its output format once, with the first stretch: kept for the next ones.
        private var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        private var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        private var encoding = AudioFormat.ENCODING_PCM_16BIT

        override fun close() {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { extractor.release() }
        }

        /**
         * Mono (channel average) audio from [fromUs] (from where the seek lands, unless [exact])
         * to [toUs]; with [soundUs], it stops once that much has been decoded after the first
         * sound. [exact] starts at [fromUs] itself, after decoding a little before it.
         */
        internal fun read(fromUs: Long, toUs: Long, soundUs: Long?, isCancelled: () -> Boolean, exact: Boolean = false): Mono? = try {
            decode(fromUs, toUs, soundUs, isCancelled, exact)
        } catch (e: Exception) {
            null
        }

        private fun decode(fromUs: Long, toUs: Long, soundUs: Long?, isCancelled: () -> Boolean, exact: Boolean): Mono? {
            // The decoder keeps what came before (and is stopped after its end of stream) until flushed.
            if (used) codec.flush()
            used = true
            val seekUs = if (exact) (fromUs - PREROLL_US).coerceAtLeast(0) else fromUs
            if (seekUs > 0 || extractor.sampleTime != 0L) extractor.seekTo(seekUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            if (exact) {
                // Some formats (Ogg) land after the point asked for: further back until before it.
                var back = seekUs
                var tries = 0
                while (extractor.sampleTime > fromUs && back > 0 && tries++ < SEEK_TRIES) {
                    back = (back - SEEK_STEP_US).coerceAtLeast(0)
                    extractor.seekTo(back, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                }
            }

            var mono = FloatArray(0)
            var count = 0
            var firstSound = -1
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            fun wantedAt(rate: Int) = if (exact) ((toUs - fromUs) * rate / 1_000_000).toInt() else Int.MAX_VALUE
            var wanted = wantedAt(sampleRate)

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
                    // [exact] decodes on until the window is whole (it stops at [wanted]).
                    if (size < 0 || (!exact && extractor.sampleTime > toUs)) {
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
                        wanted = wantedAt(sampleRate)
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
                        val frames = info.size / ((if (isFloat) 4 else 2) * channels)
                        // With [exact], what comes before the start is dropped.
                        val skip = if (exact && info.presentationTimeUs < fromUs) {
                            ((fromUs - info.presentationTimeUs) * sampleRate / 1_000_000).toInt().coerceAtMost(frames)
                        } else {
                            0
                        }
                        val kept = minOf(frames - skip, wanted - count)
                        if (kept > 0) {
                            if (mono.size < count + kept) {
                                mono = mono.copyOf(maxOf(count + kept, mono.size * 2, if (exact) wanted else sampleRate * 10))
                            }
                            val scale = 1f / channels
                            if (isFloat) {
                                val raw = FloatArray(frames * channels)
                                buffer.asFloatBuffer().get(raw)
                                for (f in 0 until kept) {
                                    var s = 0f
                                    val at = (skip + f) * channels
                                    for (c in 0 until channels) s += raw[at + c]
                                    mono[count + f] = s * scale
                                }
                            } else {
                                val raw = ShortArray(frames * channels)
                                buffer.asShortBuffer().get(raw)
                                val shortScale = scale / 32768f
                                for (f in 0 until kept) {
                                    var s = 0
                                    val at = (skip + f) * channels
                                    for (c in 0 until channels) s += raw[at + c]
                                    mono[count + f] = s * shortScale
                                }
                            }
                            if (soundUs != null && firstSound < 0) {
                                for (i in count until count + kept) {
                                    if (abs(mono[i]) > SILENCE) {
                                        firstSound = i
                                        break
                                    }
                                }
                            }
                            count += kept
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        if (soundUs != null && firstSound >= 0 && count - firstSound >= soundUs * sampleRate / 1_000_000) outputDone = true
                        if (count >= wanted) outputDone = true
                    }
                }
            }
            if (count == 0 || sampleRate <= 0) return null
            return Mono(mono, count, sampleRate)
        }

        companion object {
            /** [file] ready for decoding; null when it has no audio or cannot be decoded. */
            fun open(file: File): Source? {
                val extractor = MediaExtractor()
                var codec: MediaCodec? = null
                return try {
                    extractor.setDataSource(file.absolutePath)
                    val trackIndex = audioTrack(extractor) ?: throw IllegalStateException("no audio")
                    extractor.selectTrack(trackIndex)
                    val format = extractor.getTrackFormat(trackIndex)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: throw IllegalStateException("no type")
                    codec = MediaCodec.createDecoderByType(mime)
                    codec.configure(format, null, null, 0)
                    codec.start()
                    val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else Long.MAX_VALUE
                    Source(extractor, codec, format, durationUs)
                } catch (e: Exception) {
                    runCatching { codec?.release() }
                    extractor.release()
                    null
                }
            }
        }
    }

    /**
     * Describes the first and last [edgeSeconds] of sound of [source]. Returns null when it cannot
     * be decoded. Blocking; call off the main thread.
     */
    fun decode(source: Source, edgeSeconds: Int, isCancelled: () -> Boolean = { false }): TrackEdges? {
        val durationUs = source.durationUs
        val edgeUs = edgeSeconds * 1_000_000L
        val tailUs = edgeUs + TAIL_MARGIN_US
        val longUs = edgeUs + LONG_MARGIN_US
        if (durationUs <= 2 * tailUs) {
            val all = source.read(0, Long.MAX_VALUE, null, isCancelled) ?: return null
            return TrackEdges(describeHead(all, edgeUs), describeTail(all, edgeUs))
        }
        // Decoding is what costs: the start only until the edge after the first sound, the end
        // only its last seconds unless the sound stops long before the end of the file.
        val head = source.read(0, longUs, edgeUs, isCancelled) ?: return null
        var tail = source.read(durationUs - tailUs, Long.MAX_VALUE, null, isCancelled) ?: return null
        if (!hasFullTail(tail, edgeUs) && durationUs > longUs) {
            tail = source.read(durationUs - longUs, Long.MAX_VALUE, null, isCancelled) ?: return null
        }
        return TrackEdges(describeHead(head, edgeUs), describeTail(tail, edgeUs))
    }

    /**
     * Features of [TrackFeatures.SPAN_SECONDS] from the middle of [source] (the whole file when
     * shorter). Null when it cannot be decoded or is silent. Blocking; call off the main thread.
     */
    fun decodeFeatures(source: Source, isCancelled: () -> Boolean = { false }): TrackFeatures? {
        val durationUs = source.durationUs
        val spanUs = TrackFeatures.SPAN_SECONDS * 1_000_000L
        val audio = if (durationUs == Long.MAX_VALUE || durationUs <= spanUs) {
            source.read(0, Long.MAX_VALUE, null, isCancelled)
        } else {
            val from = (durationUs - spanUs) / 2
            source.read(from, from + spanUs, null, isCancelled)
        } ?: return null
        return TrackFeatures.measure(audio.samples, audio.count, audio.sampleRate)
    }

    /**
     * The sound profile of [source] ([SoundProfile]): only its windows are decoded, spread over
     * stretches at a quarter, half and three quarters (the start of the file, when short), and go
     * through [net]. Null when it cannot be decoded or is silent. Blocking; call off the main thread.
     */
    fun decodeSound(source: Source, net: SoundNet, isCancelled: () -> Boolean = { false }): SoundProfile? {
        val durationUs = source.durationUs
        val seconds = if (durationUs == Long.MAX_VALUE) SoundProfile.EXCERPT_SECONDS * SoundProfile.EXCERPTS.size.toDouble() else durationUs / 1e6
        val windows = ArrayList<Mono>()
        for (at in SoundProfile.windowStarts(seconds)) {
            val fromUs = (at * 1e6).toLong().coerceAtLeast(0)
            windows.add(source.read(fromUs, fromUs + (SoundProfile.WINDOW_SECONDS * 1e6).toLong(), null, isCancelled, exact = true) ?: continue)
            if (isCancelled()) return null
        }
        val rate = windows.firstOrNull()?.sampleRate ?: return null
        val size = SoundProfile.windowSamples(rate)
        return SoundProfile.measure(windows.filter { it.sampleRate == rate }.map { it.samples.copyOf(minOf(it.count, size)) }, rate, net, isCancelled)
    }

    /** Whether the last sound in [audio] comes after a whole edge's worth of decoded audio. */
    private fun hasFullTail(audio: Mono, edgeUs: Long): Boolean {
        val end = lastSound(audio) ?: return false
        return end >= edgeUs * audio.sampleRate / 1_000_000
    }

    private fun lastSound(audio: Mono): Int? =
        (audio.count - 1 downTo 0).firstOrNull { abs(audio.samples[it]) > SILENCE }?.plus(1)

    internal class Mono(val samples: FloatArray, val count: Int, val sampleRate: Int)

    private fun describeHead(audio: Mono, edgeUs: Long): EdgeDescriptor? {
        val start = (0 until audio.count).firstOrNull { abs(audio.samples[it]) > SILENCE } ?: return null
        val end = minOf(audio.count, start + (edgeUs * audio.sampleRate / 1_000_000).toInt())
        return EdgeFeatures.describe(audio.samples, start, end, audio.sampleRate)
    }

    private fun describeTail(audio: Mono, edgeUs: Long): EdgeDescriptor? {
        val end = lastSound(audio) ?: return null
        val start = maxOf(0, end - (edgeUs * audio.sampleRate / 1_000_000).toInt())
        return EdgeFeatures.describe(audio.samples, start, end, audio.sampleRate)
    }

    private fun audioTrack(extractor: MediaExtractor): Int? = (0 until extractor.trackCount).firstOrNull {
        extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
    }
}
