package com.sadurni.attacca.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessingPipeline
import androidx.media3.common.audio.AudioProcessor
import com.google.common.collect.ImmutableList
import com.sadurni.attacca.settings.DspSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Runs Attacca's processors inside Media3's real pipeline, as DefaultAudioSink does. */
class PipelineTest {

    private fun pipeline(settings: DspSettings): AudioProcessingPipeline {
        val engine = DspEngine(settings)
        val pipeline = AudioProcessingPipeline(
            ImmutableList.of(
                GainProcessor(engine),
                EffectsProcessor(engine),
                CrossfeedProcessor(engine),
                CompressorProcessor(engine),
                LimiterProcessor(engine),
            ),
        )
        pipeline.configure(AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT))
        pipeline.flush()
        return pipeline
    }

    private fun pcm16(frames: Int): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
        repeat(frames * 2) { buffer.putShort((it % 2000 - 1000).toShort()) }
        buffer.flip()
        return buffer
    }

    /** Feeds [frames], signals end of stream and drains; returns output frames. */
    private fun run(settings: DspSettings, frames: Int): Int {
        val pipeline = pipeline(settings)
        val input = pcm16(frames)
        var outputBytes = 0
        var guard = 0
        while (input.hasRemaining()) {
            pipeline.queueInput(input)
            val out = pipeline.output
            outputBytes += out.remaining()
            out.position(out.limit())
            assertTrue("input not consumed", ++guard < 10_000)
        }
        pipeline.queueEndOfStream()
        guard = 0
        while (!pipeline.isEnded) {
            val out = pipeline.output
            outputBytes += out.remaining()
            out.position(out.limit())
            assertTrue("pipeline never ended", ++guard < 10_000)
        }
        return outputBytes / 8 // float stereo
    }

    @Test
    fun drainsToEndWithDefaultSettings() {
        assertEquals(10_000, run(DspSettings(), 10_000) - LimiterLatency.frames)
    }

    @Test
    fun drainsToEndWithEverythingOn() {
        val all = DspSettings(
            preampEnabled = true, preampDb = 3f, eqEnabled = true, eqGains = List(10) { 2f },
            bassEnabled = true, widthEnabled = true, widthPercent = 150, balance = 0.2f,
            crossfeedEnabled = true, compressorEnabled = true,
        )
        assertEquals(10_000, run(all, 10_000) - LimiterLatency.frames)
    }

    /**
     * A tone split into two gapless tracks, through the whole chain with every effect on. Returns
     * the output; with [split] < 0 the tone plays as one track.
     */
    private fun playSplit(split: Int, total: Int, reconfigurations: Int = 2): FloatArray {
        val engine = DspEngine(everything)
        val pipeline = AudioProcessingPipeline(
            ImmutableList.of(
                GainProcessor(engine),
                CrossfadeProcessor(engine),
                EffectsProcessor(engine),
                CrossfeedProcessor(engine),
                CompressorProcessor(engine),
                LimiterProcessor(engine),
            ),
        )
        val format = AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_FLOAT)
        pipeline.configure(format)
        pipeline.flush()
        val out = ArrayList<Float>()
        fun drain() {
            val o = pipeline.output
            while (o.hasRemaining()) out.add(o.getFloat())
        }
        fun feed(from: Int, to: Int) {
            var frame = from
            while (frame < to) {
                val n = minOf(1000, to - frame)
                val buffer = ByteBuffer.allocateDirect(n * 8).order(ByteOrder.nativeOrder())
                for (f in frame until frame + n) {
                    // Left and right differ, so crossfeed and width have work to do.
                    buffer.putFloat((0.5 * kotlin.math.sin(f * 0.0627)).toFloat())
                    buffer.putFloat((0.4 * kotlin.math.sin(f * 0.0311)).toFloat())
                }
                buffer.flip()
                while (buffer.hasRemaining()) {
                    pipeline.queueInput(buffer)
                    drain()
                }
                frame += n
            }
        }
        engine.streamGeneration++
        if (split < 0) {
            feed(0, total)
        } else {
            feed(0, split)
            // The renderer announces the next track, then the sink drains and flushes.
            engine.streamGeneration++
            repeat(reconfigurations) {
                pipeline.queueEndOfStream()
                while (!pipeline.isEnded) drain()
                pipeline.configure(format)
                pipeline.flush()
            }
            feed(split, total)
        }
        drain()
        return out.toFloatArray()
    }

    private val everything = DspSettings(
        preampEnabled = true, preampDb = 3f, eqEnabled = true, eqGains = List(10) { 2f },
        bassEnabled = true, widthEnabled = true, widthPercent = 150, balance = 0.2f,
        crossfeedEnabled = true, compressorEnabled = true,
    )

    @Test
    fun gaplessTracksJoinAsOneStream() {
        val whole = playSplit(split = -1, total = 50_000)
        for (reconfigurations in 1..3) {
            val joined = playSplit(split = 23_457, total = 50_000, reconfigurations = reconfigurations)
            // Same length (no delay flushed as silence) and the same waveform (no filter reset;
            // only rounding from the different buffer splits, below -60 dB).
            assertEquals(whole.size, joined.size)
            for (i in whole.indices) assertEquals("sample $i", whole[i], joined[i], 1e-3f)
        }
    }

    private object LimiterLatency {
        val frames = LookaheadLimiter(44100, 2).latencyFrames
    }
}
