package com.sadurni.attacca.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessingPipeline
import androidx.media3.common.audio.AudioProcessor
import com.google.common.collect.ImmutableList
import com.sadurni.attacca.settings.ChannelMode
import com.sadurni.attacca.settings.DspSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class ChannelsTest {

    private val rate = 44100

    /** Runs [frames] of constant (left, right) through the effects stage; returns the last frame out. */
    private fun run(engine: DspEngine, left: Float, right: Float, frames: Int = 4096): Pair<FloatArray, FloatArray> {
        val pipeline = AudioProcessingPipeline(ImmutableList.of<AudioProcessor>(EffectsProcessor(engine)))
        pipeline.configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_FLOAT))
        pipeline.flush()
        val outL = ArrayList<Float>()
        val outR = ArrayList<Float>()
        var fed = 0
        while (fed < frames) {
            val n = minOf(1024, frames - fed)
            val input = ByteBuffer.allocateDirect(n * 8).order(ByteOrder.nativeOrder())
            repeat(n) { input.putFloat(left); input.putFloat(right) }
            input.flip()
            while (input.hasRemaining()) {
                pipeline.queueInput(input)
                val o = pipeline.output
                while (o.hasRemaining()) {
                    outL.add(o.getFloat())
                    outR.add(o.getFloat())
                }
            }
            fed += n
        }
        return outL.toFloatArray() to outR.toFloatArray()
    }

    private fun last(engine: DspEngine, l: Float, r: Float): Pair<Float, Float> {
        val (a, b) = run(engine, l, r)
        return a.last() to b.last()
    }

    @Test
    fun channelModes() {
        fun mode(m: ChannelMode) = last(DspEngine(DspSettings(channelMode = m)), 0.4f, 0.1f)
        assertEquals(0.4f to 0.1f, mode(ChannelMode.STEREO))
        assertEquals(0.25f, mode(ChannelMode.MONO).first, 1e-6f)
        assertEquals(0.25f, mode(ChannelMode.MONO).second, 1e-6f)
        assertEquals(0.4f, mode(ChannelMode.LEFT).second, 1e-6f)
        assertEquals(0.1f, mode(ChannelMode.RIGHT).first, 1e-6f)
        assertEquals(0.1f, mode(ChannelMode.SWAP).first, 1e-6f)
        assertEquals(0.4f, mode(ChannelMode.SWAP).second, 1e-6f)
        // What both share (0.1) is gone.
        val karaoke = last(DspEngine(DspSettings(channelMode = ChannelMode.KARAOKE)), 0.1f, 0.1f)
        assertEquals(0f, karaoke.first, 1e-6f)
    }

    @Test
    fun aModeChangeIsRampedNotJumped() {
        val engine = DspEngine(DspSettings())
        val pipeline = AudioProcessingPipeline(ImmutableList.of<AudioProcessor>(EffectsProcessor(engine)))
        pipeline.configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_FLOAT))
        pipeline.flush()
        fun feed(): FloatArray {
            val input = ByteBuffer.allocateDirect(1024 * 8).order(ByteOrder.nativeOrder())
            repeat(1024) { input.putFloat(0.4f); input.putFloat(0f) }
            input.flip()
            pipeline.queueInput(input)
            val o = pipeline.output
            return FloatArray(o.remaining() / 4) { o.getFloat() }
        }
        feed()
        engine.applySettings(DspSettings(channelMode = ChannelMode.SWAP), fade = false)
        val out = feed()
        // Left goes from 0.4 to 0 across the buffer in small steps.
        for (i in 2 until out.size step 2) assertTrue(abs(out[i] - out[i - 2]) < 0.01f)
        assertEquals(0f, out[out.size - 2], 1e-3f)
    }

    @Test
    fun precutTakesTheHighestBoost() {
        val gains = List(DspSettings.EQ_BANDS) { if (it == 0) 6f else 0f }
        val engine = DspEngine(DspSettings(eqEnabled = true, eqGains = gains, eqAutoPrecut = true))
        // The same signal with and without precut: 6 dB apart, whatever the band does to it.
        val (l, _) = run(engine, 0.1f, 0.1f, frames = 44100)
        val withPrecut = l.last()
        val engine2 = DspEngine(DspSettings(eqEnabled = true, eqGains = gains, eqAutoPrecut = false))
        val (l2, _) = run(engine2, 0.1f, 0.1f, frames = 44100)
        assertEquals(dbToLinear(-6f), withPrecut / l2.last(), 0.01f)
    }

    @Test
    fun autoMakeupIsWhatCompressionTakesAtFullScale() {
        val s = DspSettings(compressorThresholdDb = -18f, compressorRatio = 3f, compressorKneeDb = 0f, compressorAutoMakeup = true)
        assertEquals(12f, s.effectiveMakeupDb, 0.01f)
        assertEquals(0f, s.copy(compressorAutoMakeup = false).effectiveMakeupDb, 0f)
    }
}
