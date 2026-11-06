package com.sadurni.attacca.analysis

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.sadurni.attacca.dsp.CrossfadeTail
import java.io.File
import java.nio.ByteOrder

/**
 * Decodes a file straight on from a point, keeping only the last seconds: run a little ahead of
 * what is heard, it has the next seconds of the track ready the moment they are wanted (a skip
 * blend), with no seeking and no waiting. Sequential decoding also keeps its times exact.
 * Blocking calls except [tail]; one thread decodes, any thread may take a [tail].
 */
class AheadDecoder(val file: File) {

    private val extractor = MediaExtractor()
    private var codec: MediaCodec? = null
    private var sampleRate = 0
    private var channels = 0
    private var encoding = AudioFormat.ENCODING_PCM_16BIT
    private var inputDone = false
    private var outputDone = false

    private val lock = Any()
    private var samples = FloatArray(0)
    private var count = 0

    /** File time of the first frame kept, µs; −1 until something is decoded. */
    private var firstUs = -1L

    /** File time right after the last frame decoded, µs; −1 until something is decoded. */
    val endUs: Long
        get() = synchronized(lock) { endUsLocked() }

    val finished: Boolean get() = outputDone

    /** Whether the audio at [us] is kept or about to be decoded (within [slackUs] past the end). */
    fun covers(us: Long, slackUs: Long): Boolean = synchronized(lock) {
        firstUs in 0..us && (us <= endUsLocked() + slackUs || outputDone)
    }

    private fun endUsLocked(): Long = if (firstUs < 0 || channels <= 0) -1 else firstUs + count / channels * 1_000_000L / sampleRate

    /** Opens the file at [fromUs]; false when it cannot be decoded. */
    fun start(fromUs: Long): Boolean = try {
        extractor.setDataSource(file.absolutePath)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        }
        if (track == null) {
            false
        } else {
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            if (mime == MediaFormat.MIMETYPE_AUDIO_MPEG) {
                // VBR seeks are estimates; walking the frames from the start gives the player's times.
                extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                while (extractor.sampleTime in 0 until fromUs - PRE_ROLL_US) {
                    if (!extractor.advance()) break
                }
            } else {
                extractor.seekTo((fromUs - PRE_ROLL_US).coerceAtLeast(0), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }
            sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(format, null, null, 0)
                start()
            }
            true
        }
    } catch (e: Exception) {
        Log.w(TAG, "cannot decode ahead in ${file.name}", e)
        false
    }

    /** Decodes until [untilUs] is reached or the file ends. */
    fun decodeUntil(untilUs: Long, isCancelled: () -> Boolean) {
        val codec = codec ?: return
        val info = MediaCodec.BufferInfo()
        try {
            while (!outputDone && endUs < untilUs) {
                if (isCancelled()) return
                while (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(0)
                    if (inIndex < 0) break
                    val size = extractor.readSampleData(codec.getInputBuffer(inIndex)!!, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val format = codec.outputFormat
                    synchronized(lock) {
                        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    }
                } else if (outIndex >= 0) {
                    if (info.size > 0) {
                        val buffer = codec.getOutputBuffer(outIndex)!!.order(ByteOrder.nativeOrder())
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        append(buffer, info.presentationTimeUs)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "decoding ahead in ${file.name} failed", e)
            outputDone = true
        }
    }

    private fun append(buffer: java.nio.ByteBuffer, timeUs: Long) = synchronized(lock) {
        val float = encoding == AudioFormat.ENCODING_PCM_FLOAT
        val n = buffer.remaining() / if (float) 4 else 2
        if (firstUs < 0) firstUs = timeUs
        if (samples.size < count + n) samples = samples.copyOf(maxOf(count + n, samples.size * 2))
        if (float) {
            buffer.asFloatBuffer().get(samples, count, n)
        } else {
            val shorts = buffer.asShortBuffer()
            for (i in 0 until n) samples[count + i] = shorts.get() / 32768f
        }
        count += n
    }

    /** Forgets what was decoded before [us]. */
    fun dropBefore(us: Long) = synchronized(lock) {
        if (firstUs < 0 || channels <= 0 || us <= firstUs) return@synchronized
        val frames = ((us - firstUs) * sampleRate / 1_000_000).toInt().coerceAtMost(count / channels)
        val drop = frames * channels
        System.arraycopy(samples, drop, samples, 0, count - drop)
        count -= drop
        firstUs += frames * 1_000_000L / sampleRate
    }

    /**
     * The decoded audio from [atUs] on, at most [lengthUs], as a tail whose clip end is [atUs];
     * null when [atUs] is not decoded yet (or no longer kept).
     */
    fun tail(atUs: Long, lengthUs: Long, leadUs: Long = 0): CrossfadeTail? = synchronized(lock) {
        if (firstUs < 0 || channels <= 0 || atUs < firstUs) return@synchronized null
        val total = count / channels
        val at = ((atUs - firstUs) * sampleRate / 1_000_000).toInt()
        if (at >= total) return@synchronized null
        // Some audio before the clip end: a few frames at least, so the frame at it is inside, or
        // [leadUs] to line the tail up with what was played.
        val from = maxOf(0, at - maxOf(LEAD_FRAMES, (leadUs * sampleRate / 1_000_000).toInt()))
        val frames = minOf(total - from, (at - from) + (lengthUs * sampleRate / 1_000_000).toInt())
        CrossfadeTail(samples.copyOfRange(from * channels, (from + frames) * channels), frames, channels, sampleRate, at - from)
    }

    fun release() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        runCatching { extractor.release() }
    }

    private companion object {
        const val TAG = "Crossfade"
        const val TIMEOUT_US = 10_000L
        const val PRE_ROLL_US = 200_000L
        const val LEAD_FRAMES = 64
    }
}
