package com.sadurni.attacca.analysis

import android.media.AudioFormat
import android.util.Log
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.sadurni.attacca.dsp.CrossfadeTail
import com.sadurni.attacca.playback.PlayerMp3Reader
import java.io.File
import java.nio.ByteOrder

/** Decodes the end of a crossfaded track ahead of time (see [CrossfadeTail]). */
object TailDecoder {

    private const val TAG = "Crossfade"
    private const val TIMEOUT_US = 10_000L

    /**
     * Audio decoded before the clip end, so the tail can be matched against what was played. It
     * also lets the decoder settle after seeking, which is not always sample-exact.
     */
    const val LEAD_US = 4_000_000L

    /** Frames read before the wanted start when reaching it frame by frame (decoder warm-up). */
    private const val MP3_PRE_ROLL_US = 200_000L

    /**
     * Decodes [file] from [leadUs] before [clipEndUs] up to [clipEndUs] + [lengthUs] (or the end
     * of the file). Returns null when the file cannot be decoded. Blocking; call off the main
     * thread.
     */
    fun decode(file: File, clipEndUs: Long, lengthUs: Long, isCancelled: () -> Boolean = { false }, leadUs: Long = LEAD_US): CrossfadeTail? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var closeAlso: AutoCloseable? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return null

            val fromUs = (clipEndUs - leadUs).coerceAtLeast(0)
            val toUs = clipEndUs + lengthUs
            val mp3 = mime == MediaFormat.MIMETYPE_AUDIO_MPEG
            // MP3 frames come from the player's own reader, which leaves out broken frames and
            // stretches repeated by damage (see RepeatSkippingExtractor): the same frames at the
            // same times as what was played, even in a badly damaged file.
            val reader = if (mp3) PlayerMp3Reader(file) else null
            closeAlso = reader
            var next: Pair<ByteArray, Long>? = null
            if (reader != null) {
                // In a VBR MP3 a seek is estimated from the header's table and the times after it
                // can be off by a second or more; the player reads from the start and counts
                // exactly. Walking the frames from the start (no decoding) gives the same times.
                var walked = 0
                while (true) {
                    next = reader.next() ?: break
                    if (next.second >= fromUs - MP3_PRE_ROLL_US) break
                    if (++walked % 1000 == 0 && isCancelled()) return null
                }
            } else {
                extractor.seekTo(fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            // The file's own format. A damaged MP3 holds garbage the decoder takes for frames of
            // another kind (mono, 32 or 48 kHz) and changes its output for a moment: the tail stays
            // in the file's format (a mono frame goes to both sides; the rate is the file's).
            val sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var decodedRate = sampleRate
            val channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var decoded = channels
            var encoding = AudioFormat.ENCODING_PCM_16BIT

            var samples = FloatArray(0)
            var count = 0 // samples written
            var firstFrameUs = -1L
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (isCancelled()) return null
                // Every free input buffer at once: one per round, each round waiting on the output,
                // made short packets (Vorbis, Opus) take seconds.
                while (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(0)
                    if (inIndex < 0) break
                    val buffer = codec.getInputBuffer(inIndex)!!
                    if (reader != null) {
                        val frame = next ?: reader.next()
                        next = null
                        if (frame == null || frame.second > toUs) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            buffer.clear()
                            buffer.put(frame.first)
                            codec.queueInputBuffer(inIndex, 0, frame.first.size, frame.second, 0)
                        }
                        continue
                    }
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0 || extractor.sampleTime > toUs) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = codec.outputFormat
                        decodedRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        decoded = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            encoding = format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                    }
                    outIndex >= 0 -> {
                        val buffer = codec.getOutputBuffer(outIndex)!!.order(ByteOrder.nativeOrder())
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val bufferEndsAfterStart = info.size > 0 &&
                            info.presentationTimeUs + bufferDurationUs(info.size, decoded, decodedRate, encoding) > fromUs
                        if (bufferEndsAfterStart && info.presentationTimeUs <= toUs && decoded > 0) {
                            if (firstFrameUs < 0) firstFrameUs = info.presentationTimeUs
                            val inSamples = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) info.size / 4 else info.size / 2
                            val frames = inSamples / decoded
                            val n = frames * channels
                            if (samples.size < count + n) {
                                val expected = ((lengthUs + leadUs) * sampleRate / 1_000_000 * channels).toInt()
                                samples = samples.copyOf(maxOf(count + n, samples.size * 2, expected))
                            }
                            val floats = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) buffer.asFloatBuffer() else null
                            val shorts = if (floats == null) buffer.asShortBuffer() else null
                            fun sample(i: Int) = floats?.get(i) ?: (shorts!!.get(i) / 32768f)
                            if (decoded == channels) {
                                for (i in 0 until n) samples[count + i] = sample(i)
                            } else {
                                for (f in 0 until frames) for (c in 0 until channels) {
                                    samples[count + f * channels + c] = sample(f * decoded + minOf(c, decoded - 1))
                                }
                            }
                            count += n
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            if (count == 0 || firstFrameUs < 0 || channels <= 0) {
                Log.w(TAG, "nothing decoded after ${fromUs / 1000} ms in ${file.name}")
                return null
            }
            val frames = count / channels
            val clipEndFrame = ((clipEndUs - firstFrameUs) * sampleRate / 1_000_000).toInt()
            if (clipEndFrame <= 0 || clipEndFrame >= frames) {
                Log.w(TAG, "clip end outside the decoded part of ${file.name}: first frame at ${firstFrameUs / 1000} ms, $frames frames")
                return null
            }
            return CrossfadeTail(samples.copyOf(frames * channels), frames, channels, sampleRate, clipEndFrame)
        } catch (e: Exception) {
            Log.w(TAG, "decoding the end of ${file.name} failed", e)
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { closeAlso?.close() }
            extractor.release()
        }
    }

    private fun bufferDurationUs(bytes: Int, channels: Int, sampleRate: Int, encoding: Int): Long {
        val bytesPerSample = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
        return bytes.toLong() / (bytesPerSample * channels) * 1_000_000 / sampleRate
    }
}
