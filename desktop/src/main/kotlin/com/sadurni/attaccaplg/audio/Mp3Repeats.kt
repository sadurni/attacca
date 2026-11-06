package com.sadurni.attaccaplg.audio

import java.io.ByteArrayInputStream
import java.io.File
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem

/**
 * Leaves out stretches of an MP3 that repeat, byte for byte, frames heard just before.
 *
 * Some damaged files (a faulty copy or download) hold, every few seconds, half a second of frames
 * written twice in a row: played as they are, the music jumps back and repeats itself. Real music
 * never encodes to the same frames twice, so a run of [MIN_RUN] frames each equal to the frame
 * [MIN_RUN]..[WINDOW] frames earlier is such a copy and is dropped; the music then goes on exactly
 * where the first copy ended. Silence and dither can repeat in short cycles, so the run's frames
 * must all differ from each other and carry sound. (The phone app does the same while it plays.)
 *
 * Such files also hold bits of broken frames between the good ones and frames whose data is
 * garbage, which made the Java decoder stop halfway through the song: those bytes go, and the
 * frames are decoded one by one, a frame that cannot be decoded giving its length of silence.
 */
object Mp3Repeats {
    private const val MIN_RUN = 8
    private const val WINDOW = 128
    private const val LOUD_GAIN = 140
    private val BITRATES = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
    private val RATES = intArrayOf(44100, 48000, 32000)

    /** [file] for the Java decoders; an MP3 without its repeated stretches and broken frames. */
    fun audioStream(file: File): AudioInputStream {
        if (file.extension.lowercase() == "mp3") {
            cleaned(file.readBytes())?.let { return FrameByFrame(it).audioStream() }
        }
        return AudioSystem.getAudioInputStream(file)
    }

    /**
     * The file's bytes without repeated frames and without bytes between frames (tags before the
     * first frame and after the last one stay), or null when there is nothing to take out or the
     * file is not made of MPEG-1 Layer III frames.
     */
    fun cleaned(data: ByteArray): ByteArray? {
        val starts = ArrayList<Int>()
        val sizes = ArrayList<Int>()
        var i = if (data.size > 10 && data[0] == 'I'.code.toByte() && data[1] == 'D'.code.toByte() && data[2] == '3'.code.toByte()) {
            10 + ((data[6].toInt() and 0x7f) shl 21) + ((data[7].toInt() and 0x7f) shl 14) +
                ((data[8].toInt() and 0x7f) shl 7) + (data[9].toInt() and 0x7f)
        } else {
            0
        }
        while (i + 4 <= data.size) {
            val size = frameSize(data, i)
            // A frame is followed by another one (or the end of the file).
            if (size > 0 && (i + size + 4 > data.size || frameSize(data, i + size) > 0)) {
                starts += i
                sizes += size
                i += size
            } else {
                i++
            }
        }
        val n = starts.size
        if (n == 0) return null
        val framed = sizes.sum().toLong()
        val span = starts[n - 1] + sizes[n - 1] - starts[0]
        if (framed < span * 0.9) return null
        val hashes = IntArray(n) { java.util.Arrays.hashCode(data.copyOfRange(starts[it], starts[it] + sizes[it])) }
        fun same(a: Int, b: Int): Boolean =
            hashes[a] == hashes[b] && sizes[a] == sizes[b] &&
                java.util.Arrays.equals(data, starts[a], starts[a] + sizes[a], data, starts[b], starts[b] + sizes[b])

        val dropped = BooleanArray(n)
        var any = false
        var k = 0
        while (k < n) {
            var run = 0
            for (back in MIN_RUN..minOf(WINDOW, k)) {
                if (!same(k, k - back)) continue
                var length = 0
                while (k + length < n && same(k + length, k + length - back)) length++
                if (length < MIN_RUN) continue
                val first = k until k + MIN_RUN
                val different = first.all { a -> first.none { b -> b < a && same(a, b) } }
                if (different && first.any { loudestGain(data, starts[it]) >= LOUD_GAIN }) {
                    run = length
                    break
                }
            }
            if (run > 0) {
                for (j in k until k + run) dropped[j] = true
                any = true
                k += run
            } else {
                k++
            }
        }
        if (!any && framed.toInt() == span) return null
        val out = java.io.ByteArrayOutputStream(data.size)
        out.write(data, 0, starts[0])
        for (f in 0 until n) if (!dropped[f]) out.write(data, starts[f], sizes[f])
        val end = starts[n - 1] + sizes[n - 1]
        out.write(data, end, data.size - end)
        return out.toByteArray()
    }

    /** Size of the MPEG-1 Layer III frame starting at [at], or 0 when there is none. */
    private fun frameSize(data: ByteArray, at: Int): Int {
        if (at + 4 > data.size) return 0
        val h = ((data[at].toInt() and 0xff) shl 24) or ((data[at + 1].toInt() and 0xff) shl 16) or
            ((data[at + 2].toInt() and 0xff) shl 8) or (data[at + 3].toInt() and 0xff)
        if ((h ushr 21) and 0x7ff != 0x7ff || (h ushr 19) and 3 != 3 || (h ushr 17) and 3 != 1) return 0
        val bitrate = (h ushr 12) and 15
        val rate = (h ushr 10) and 3
        if (bitrate == 0 || bitrate == 15 || rate == 3) return 0
        return 144 * BITRATES[bitrate] * 1000 / RATES[rate] + ((h ushr 9) and 1)
    }

    /** Highest global gain among the frame's granules (side information). */
    private fun loudestGain(data: ByteArray, at: Int): Int {
        val mono = (data[at + 3].toInt() ushr 6) and 3 == 3
        val crc = data[at + 1].toInt() and 1 == 0
        val side = at + 4 + if (crc) 2 else 0
        var pos = 9 + (if (mono) 5 else 3) + (if (mono) 4 else 8)
        var loudest = 0
        repeat(if (mono) 2 else 4) {
            var value = 0
            for (b in 0 until 8) {
                val bit = pos + 21 + b
                val index = side + bit / 8
                if (index >= data.size) return 255
                value = (value shl 1) or ((data[index].toInt() ushr (7 - bit % 8)) and 1)
            }
            loudest = maxOf(loudest, value)
            pos += 59
        }
        return loudest
    }
}

/** Decodes MP3 frames one by one into 16-bit PCM, going on past frames that cannot be decoded. */
private class FrameByFrame(data: ByteArray) : java.io.InputStream() {
    private val bitstream = javazoom.jl.decoder.Bitstream(ByteArrayInputStream(data))
    private var decoder = javazoom.jl.decoder.Decoder()
    private val first: javazoom.jl.decoder.Header? = bitstream.readFrame()
    private var header = first
    val rate = first?.frequency() ?: 44100
    val channels = if (first?.mode() == javazoom.jl.decoder.Header.SINGLE_CHANNEL) 1 else 2
    private var pcm = ByteArray(0)
    private var pos = 0
    private var len = 0

    fun audioStream(): AudioInputStream {
        val format = javax.sound.sampled.AudioFormat(rate.toFloat(), 16, channels, true, false)
        return AudioInputStream(this, format, AudioSystem.NOT_SPECIFIED.toLong())
    }

    /** Decodes the next frame into [pcm]; false at the end. */
    private fun next(): Boolean {
        val h = header ?: return false
        val samples = if (h.version() == javazoom.jl.decoder.Header.MPEG1) 1152 else 576
        var out: ShortArray? = null
        var count = 0
        var outChannels = channels
        try {
            val buffer = decoder.decodeFrame(h, bitstream) as javazoom.jl.decoder.SampleBuffer
            out = buffer.buffer
            count = buffer.bufferLength
            outChannels = buffer.channelCount
        } catch (e: Exception) {
            decoder = javazoom.jl.decoder.Decoder() // its state may be broken too
        }
        bitstream.closeFrame()
        val frames = if (out != null) count / outChannels else samples
        val need = frames * channels * 2
        if (pcm.size < need) pcm = ByteArray(need)
        var o = 0
        for (f in 0 until frames) for (c in 0 until channels) {
            val v = if (out == null) 0 else out[f * outChannels + minOf(c, outChannels - 1)].toInt()
            pcm[o++] = v.toByte()
            pcm[o++] = (v shr 8).toByte()
        }
        pos = 0
        len = o
        header = try { bitstream.readFrame() } catch (e: Exception) { null }
        return true
    }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, length: Int): Int {
        while (pos >= len) if (!next()) return -1
        val n = minOf(length, len - pos)
        System.arraycopy(pcm, pos, b, off, n)
        pos += n
        return n
    }

    override fun close() {
        runCatching { bitstream.close() }
    }
}
