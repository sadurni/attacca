package com.sadurni.attaccaplg.audio

import java.io.File
import java.io.InputStream
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem

/**
 * A file decoded as it plays, from a point in it: blocks of interleaved floats (−1 … 1) on
 * request, with the same decoders as [Decoder] (Java for MP3, Ogg Vorbis, FLAC, WAV and AIFF;
 * ffmpeg for the rest when installed). Close it when done.
 */
class PcmSource private constructor(
    val format: PcmFormat,
    private val input: InputStream,
    private val process: Process?,
    private val closeAlso: AutoCloseable?,
) : AutoCloseable {

    private val frameBytes = 2 * format.channels
    private var bytes = ByteArray(frameBytes * 4096)
    private var pending = 0
    private var ended = false

    /** Reads up to [maxFrames] frames into [dst]; 0 at the end of the file. */
    fun read(dst: FloatArray, maxFrames: Int): Int {
        if (ended) return 0
        val want = maxFrames * frameBytes
        if (bytes.size < want) bytes = bytes.copyOf(want)
        while (pending < frameBytes) {
            val n = input.read(bytes, pending, want - pending)
            if (n < 0) {
                ended = true
                return 0
            }
            pending += n
        }
        val frames = minOf(pending / frameBytes, maxFrames)
        val samples = frames * format.channels
        for (i in 0 until samples) {
            val lo = bytes[2 * i].toInt() and 0xFF
            val hi = bytes[2 * i + 1].toInt()
            dst[i] = ((hi shl 8) or lo).toShort() / 32768f
        }
        val used = frames * frameBytes
        System.arraycopy(bytes, used, bytes, 0, pending - used)
        pending -= used
        return frames
    }

    override fun close() {
        runCatching { input.close() }
        runCatching { closeAlso?.close() }
        process?.destroy()
    }

    companion object {
        private val JAVA_FORMATS = setOf("mp3", "ogg", "oga", "flac", "wav", "aif", "aiff")
        private const val FFMPEG_RATE = 44_100

        /** Opens [file] at [startMs]; null when it cannot be decoded. */
        fun open(file: File, startMs: Long = 0): PcmSource? {
            if (file.extension.lowercase() in JAVA_FORMATS) {
                runCatching { openJava(file, startMs) }.getOrNull()?.let { return it }
            }
            val ffmpeg = Decoder.ffmpeg ?: return null
            return runCatching { openFfmpeg(ffmpeg, file, startMs) }.getOrNull()
        }

        private fun openJava(file: File, startMs: Long): PcmSource? {
            val source = Mp3Repeats.audioStream(file)
            try {
                val base = source.format
                val channels = base.channels.takeIf { it > 0 } ?: return null.also { source.close() }
                val rate = base.sampleRate.takeIf { it > 0 }?.toInt() ?: return null.also { source.close() }
                val target = AudioFormat(AudioFormat.Encoding.PCM_SIGNED, rate.toFloat(), 16, channels, channels * 2, rate.toFloat(), false)
                val pcm = if (base.encoding == AudioFormat.Encoding.PCM_SIGNED && base.sampleSizeInBits == 16 && !base.isBigEndian) {
                    source
                } else {
                    AudioSystem.getAudioInputStream(target, source)
                }
                // Up to the start by decoding (the Java decoders cannot jump).
                var skip = startMs * rate / 1000 * channels * 2
                val scratch = ByteArray(65536)
                while (skip > 0) {
                    val n = pcm.read(scratch, 0, minOf(scratch.size.toLong(), skip).toInt())
                    if (n < 0) break
                    skip -= n
                }
                return PcmSource(PcmFormat(rate, channels), pcm, null, source)
            } catch (e: Throwable) {
                source.close()
                throw e
            }
        }

        private fun openFfmpeg(ffmpeg: String, file: File, startMs: Long): PcmSource {
            val process = ProcessBuilder(
                ffmpeg, "-v", "error", "-nostdin", "-ss", "%.3f".format(java.util.Locale.ROOT, startMs / 1000.0),
                "-i", file.absolutePath, "-vn", "-f", "s16le", "-acodec", "pcm_s16le", "-ar", FFMPEG_RATE.toString(), "-ac", "2", "-",
            ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            return PcmSource(PcmFormat(FFMPEG_RATE, 2), process.inputStream.buffered(), process, null)
        }
    }
}
