package com.sadurni.attaccaplg.audio

import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem

/** How decoded audio comes: interleaved samples (−1 … 1) of [channels] channels at [sampleRate]. */
class PcmFormat(val sampleRate: Int, val channels: Int)

/**
 * Decodes whole audio files into floats, in blocks handed to a sink. MP3, Ogg Vorbis, FLAC, WAV
 * and AIFF with the Java decoders; anything else (AAC, WMA, Opus…) with ffmpeg when it is
 * installed.
 */
object Decoder {

    private val JAVA_FORMATS = setOf("mp3", "ogg", "oga", "flac", "wav", "aif", "aiff")

    /** ffmpeg on the PATH (or null): checked once. */
    val ffmpeg: String? by lazy {
        runCatching {
            val process = ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start()
            process.inputStream.readBytes()
            if (process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0) "ffmpeg" else null
        }.getOrNull()
    }

    /** Whether [file] can be decoded here at all (by its extension). */
    fun canDecode(file: File): Boolean = file.extension.lowercase() in JAVA_FORMATS || ffmpeg != null

    /**
     * Decodes [file]: [onFormat] once with the format, then [sink] with each block (interleaved
     * samples, frames in it). Returns the format, or null when it cannot be decoded (a failed
     * Java decoding is retried with ffmpeg, from the start: [onFormat] may then come twice).
     * Blocking.
     */
    fun decode(file: File, onFormat: (PcmFormat) -> Unit, sink: (samples: FloatArray, frames: Int) -> Unit): PcmFormat? {
        if (file.extension.lowercase() in JAVA_FORMATS) {
            runCatching { decodeJava(file, onFormat, sink) }.getOrNull()?.let { return it }
        }
        return ffmpeg?.let { runCatching { decodeFfmpeg(it, file, onFormat, sink) }.getOrNull() }
    }

    private fun decodeJava(file: File, onFormat: (PcmFormat) -> Unit, sink: (FloatArray, Int) -> Unit): PcmFormat? {
        Mp3Repeats.audioStream(file).use { source ->
            val base = source.format
            val channels = base.channels.takeIf { it > 0 } ?: return null
            val rate = base.sampleRate.takeIf { it > 0 }?.toInt() ?: return null
            val target = AudioFormat(AudioFormat.Encoding.PCM_SIGNED, rate.toFloat(), 16, channels, channels * 2, rate.toFloat(), false)
            val pcm = if (base.encoding == AudioFormat.Encoding.PCM_SIGNED && base.sampleSizeInBits == 16 && !base.isBigEndian) {
                source
            } else {
                AudioSystem.getAudioInputStream(target, source)
            }
            val format = PcmFormat(rate, channels)
            onFormat(format)
            return if (readPcm16(pcm, channels, sink) > 0) format else null
        }
    }

    private fun decodeFfmpeg(ffmpeg: String, file: File, onFormat: (PcmFormat) -> Unit, sink: (FloatArray, Int) -> Unit): PcmFormat? {
        val process = ProcessBuilder(
            ffmpeg, "-v", "error", "-nostdin", "-i", file.absolutePath,
            "-vn", "-f", "s16le", "-acodec", "pcm_s16le", "-ar", FFMPEG_RATE.toString(), "-ac", "2", "-",
        ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        onFormat(PcmFormat(FFMPEG_RATE, 2))
        val frames = process.inputStream.use { readPcm16(it, 2, sink) }
        process.waitFor(30, TimeUnit.SECONDS)
        return if (frames > 0) PcmFormat(FFMPEG_RATE, 2) else null
    }

    /** Reads 16-bit little-endian PCM to the end; returns the frames read. */
    private fun readPcm16(input: InputStream, channels: Int, sink: (FloatArray, Int) -> Unit): Long {
        val frameBytes = 2 * channels
        val bytes = ByteArray(frameBytes * 8192)
        val floats = FloatArray(channels * 8192)
        var pending = 0
        var total = 0L
        while (true) {
            val n = input.read(bytes, pending, bytes.size - pending)
            if (n < 0) break
            pending += n
            val frames = pending / frameBytes
            if (frames == 0) continue
            val samples = frames * channels
            for (i in 0 until samples) {
                val lo = bytes[2 * i].toInt() and 0xFF
                val hi = bytes[2 * i + 1].toInt()
                floats[i] = ((hi shl 8) or lo).toShort() / 32768f
            }
            sink(floats, frames)
            total += frames
            val used = frames * frameBytes
            System.arraycopy(bytes, used, bytes, 0, pending - used)
            pending -= used
        }
        return total
    }

    private const val FFMPEG_RATE = 44_100
}
