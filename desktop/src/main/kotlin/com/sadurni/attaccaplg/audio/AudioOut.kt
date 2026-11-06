package com.sadurni.attaccaplg.audio

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlin.random.Random

/** Where the player's sound goes: stereo floats at [AudioOut.RATE]. */
interface SoundSink : AutoCloseable {
    /** Frames handed over but not heard yet. */
    val pending: Long

    /** Writes [frames] stereo frames of [samples] at [volume] (0 … 1); blocks while it is full. */
    fun write(samples: FloatArray, frames: Int, volume: Float)

    /** Stops at once, dropping what has not been heard. */
    fun flush()
    fun pause()
    fun resume()

    /** Lets what was written play out. */
    fun drain()
}

/**
 * The sound card: stereo floats at [RATE] go out as 16-bit samples (the format every system's
 * Java sound takes), with a little triangular noise (dither) so quiet passages keep their detail
 * instead of turning into steps. [device]: the name of an output (see [devices]), or null for the
 * system's default.
 */
class AudioOut(val device: String?) : SoundSink {

    private val format = AudioFormat(RATE.toFloat(), 16, 2, true, false)
    private val line: SourceDataLine = open()
    private var bytes = ByteArray(0)
    private val random = Random(1)

    /** Frames written since it opened (or the last [flush]). */
    private var written = 0L

    private fun open(): SourceDataLine {
        val mixer = device?.let { name -> AudioSystem.getMixerInfo().firstOrNull { it.name == name } }
        val line = if (mixer != null) {
            runCatching { AudioSystem.getSourceDataLine(format, mixer) }.getOrNull() ?: AudioSystem.getSourceDataLine(format)
        } else {
            AudioSystem.getSourceDataLine(format)
        }
        line.open(format, RATE * 4 * BUFFER_MS / 1000)
        line.start()
        return line
    }

    override val pending: Long get() = written - line.longFramePosition

    /** Writes [frames] stereo frames of [samples] at [volume] (0 … 1); blocks while the card's buffer is full. */
    override fun write(samples: FloatArray, frames: Int, volume: Float) {
        val n = frames * 2
        if (bytes.size < n * 2) bytes = ByteArray(n * 2)
        for (i in 0 until n) {
            val dither = (random.nextFloat() - random.nextFloat()) / 32768f
            val v = ((samples[i] * volume + dither) * 32767f).coerceIn(-32768f, 32767f).toInt()
            bytes[2 * i] = v.toByte()
            bytes[2 * i + 1] = (v shr 8).toByte()
        }
        line.write(bytes, 0, n * 2)
        written += frames
    }

    override fun flush() {
        line.stop()
        line.flush()
        written = line.longFramePosition
        line.start()
    }

    override fun pause() = line.stop()

    override fun resume() = line.start()

    override fun drain() = line.drain()

    override fun close() {
        runCatching { line.stop() }
        runCatching { line.close() }
    }

    companion object {
        const val RATE = 44_100
        private const val BUFFER_MS = 250

        /** The outputs that take 16-bit stereo at [RATE], by name (the system's default is not listed). */
        fun devices(): List<String> {
            val format = AudioFormat(RATE.toFloat(), 16, 2, true, false)
            val info = javax.sound.sampled.DataLine.Info(SourceDataLine::class.java, format)
            return AudioSystem.getMixerInfo().filter { mixer ->
                runCatching { AudioSystem.getMixer(mixer).isLineSupported(info) }.getOrDefault(false) &&
                    !mixer.name.startsWith("Port ")
            }.map { it.name }.distinct()
        }
    }
}
