package com.sadurni.attacca.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessingPipeline
import androidx.media3.common.audio.AudioProcessor
import com.google.common.collect.ImmutableList
import com.sadurni.attacca.settings.DspSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/** Every track leaves the chain at one rate, in stereo, without losing or adding time. */
class OutputFormatTest {

    private fun sine(frames: Int, channels: Int, rate: Int, hz: Double): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(frames * channels * 4).order(ByteOrder.nativeOrder())
        for (f in 0 until frames) {
            val x = (0.5 * sin(2 * PI * hz * f / rate)).toFloat()
            repeat(channels) { buffer.putFloat(x) }
        }
        buffer.flip()
        return buffer
    }

    /** Runs [input] through the stage at [rate]/[channels] and returns the output (interleaved). */
    private fun run(input: ByteBuffer, rate: Int, channels: Int): Pair<AudioProcessor.AudioFormat, FloatArray> {
        val pipeline = AudioProcessingPipeline(ImmutableList.of(OutputFormatProcessor(DspEngine(DspSettings()))))
        val format = pipeline.configure(AudioProcessor.AudioFormat(rate, channels, C.ENCODING_PCM_FLOAT))
        pipeline.flush()
        val out = ArrayList<Float>()
        fun collect() {
            val buffer = pipeline.output.order(ByteOrder.nativeOrder())
            while (buffer.hasRemaining()) out.add(buffer.float)
        }
        var guard = 0
        while (input.hasRemaining()) {
            pipeline.queueInput(input)
            collect()
            assertTrue(++guard < 10_000)
        }
        pipeline.queueEndOfStream()
        guard = 0
        while (!pipeline.isEnded) {
            collect()
            assertTrue(++guard < 10_000)
        }
        collect()
        return format to out.toFloatArray()
    }

    /** Level of [hz] in the left channel of [x] (amplitude of a sine). */
    private fun level(x: FloatArray, channels: Int, rate: Int, hz: Double, from: Int, to: Int): Double {
        var re = 0.0
        var im = 0.0
        for (f in from until to) {
            val v = x[f * channels].toDouble()
            re += v * kotlin.math.cos(2 * PI * hz * f / rate)
            im += v * sin(2 * PI * hz * f / rate)
        }
        return 2 * sqrt(re * re + im * im) / (to - from)
    }

    private fun check(rate: Int, hz: Double) {
        val frames = rate * 2
        val (format, out) = run(sine(frames, 2, rate, hz), rate, 2)
        assertEquals(OutputFormatProcessor.OUTPUT_RATE, format.sampleRate)
        assertEquals(2, format.channelCount)
        val outFrames = out.size / 2
        val expected = frames.toLong() * OutputFormatProcessor.OUTPUT_RATE / rate
        assertTrue("$rate: $outFrames frames, $expected expected", abs(outFrames - expected) <= 2)
        val r = OutputFormatProcessor.OUTPUT_RATE
        val from = r / 4
        val to = outFrames - r / 4
        assertEquals("$rate: level of the tone", 0.5, level(out, 2, r, hz, from, to), 0.005)
        // Nothing else: what is left after removing the tone is far below it.
        var residual = 0.0
        var reference = 0.0
        for (f in from until to) {
            val ideal = 0.5 * sin(2 * PI * hz * f / r)
            // The filter is symmetric: no delay to account for.
            residual += (out[f * 2] - ideal).let { it * it }
            reference += ideal * ideal
        }
        assertTrue("$rate: residual ${residual / reference}", residual / reference < 1e-6)
    }

    /** The first and last milliseconds keep their level: no dip where the format changes. */
    @Test
    fun edgesDoNotDip() {
        val rate = 22_050
        val hz = 200.0
        val (_, out) = run(sine(rate, 2, rate, hz), rate, 2)
        val r = OutputFormatProcessor.OUTPUT_RATE
        val frames = out.size / 2
        val edge = r / 200 // 5 ms
        var worst = 0.0
        for (f in (0 until edge) + (frames - edge until frames)) {
            val ideal = 0.5 * sin(2 * PI * hz * f / r)
            worst = maxOf(worst, abs(out[f * 2] - ideal))
        }
        assertTrue("worst error at the edges $worst", worst < 0.01)
    }

    @Test
    fun upsamples() = check(22_050, 1_000.0)

    @Test
    fun downsamples() = check(48_000, 1_000.0)

    @Test
    fun hiRes() = check(96_000, 5_000.0)

    @Test
    fun oddRate() = check(32_000, 440.0)

    @Test
    fun monoBecomesStereo() {
        val (format, out) = run(sine(1000, 1, 44_100, 440.0), 44_100, 1)
        assertEquals(2, format.channelCount)
        assertEquals(2000, out.size)
        for (f in 0 until 1000) assertEquals(out[2 * f], out[2 * f + 1])
    }

    @Test
    fun stereoAtTheOutputRateIsUntouched() {
        val processor = OutputFormatProcessor(DspEngine(DspSettings()))
        processor.configure(AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_FLOAT))
        assertFalse(processor.isActive)
    }
}
