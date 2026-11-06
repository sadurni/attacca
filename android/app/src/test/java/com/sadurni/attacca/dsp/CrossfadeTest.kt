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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class CrossfadeTest {

    private val rate = 44100
    private val bufferFrames = 1024
    private val periodA = "period A"

    /**
     * Track A: a rising tone, so no stretch of it repeats and a misplaced tail would show. Its
     * first frames up to the clip are played, the rest is the tail.
     */
    private fun trackA(frame: Int): Float {
        val t = frame.toDouble() / rate
        return (0.3 * sin(2 * PI * (150 * t + 100 * t * t))).toFloat()
    }

    private fun stereo(frames: Int, sample: (Int) -> Float): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(frames * 8).order(ByteOrder.nativeOrder())
        for (f in 0 until frames) {
            val x = sample(f)
            buffer.putFloat(x)
            buffer.putFloat(x)
        }
        buffer.flip()
        return buffer
    }

    /**
     * Tail decoded ahead: from [lead] frames before the clip end to the end of track A. With
     * [misalignment] the decoder's idea of the clip end is off by that many frames.
     */
    private fun tail(clipFrames: Int, totalFrames: Int, lead: Int, misalignment: Int = 0): CrossfadeTail {
        val first = clipFrames - lead
        val frames = totalFrames - first
        val samples = FloatArray(frames * 2)
        for (f in 0 until frames) {
            samples[2 * f] = trackA(first + f)
            samples[2 * f + 1] = trackA(first + f)
        }
        return CrossfadeTail(samples, frames, 2, rate, clipEndFrame = lead + misalignment)
    }

    private class Run(val output: FloatArray)

    /**
     * Plays the clipped track A, then track B (constant [levelB]) like the renderer and the sink
     * do: the new track is announced, then the sink drains and flushes [reconfigurations] times.
     */
    private fun play(
        engine: DspEngine,
        clipFrames: Int,
        tail: CrossfadeTail?,
        bFrames: Int = rate,
        levelB: Float = 0.2f,
        reconfigurations: Int = 2,
        endOfList: Boolean = false,
        gainA: Float = 1f,
        decoderPadding: Int = 0,
    ): Run {
        val crossfade = CrossfadeProcessor(engine)
        val pipeline = AudioProcessingPipeline(ImmutableList.of<AudioProcessor>(crossfade))
        pipeline.configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_FLOAT))
        pipeline.flush()
        val out = ArrayList<Float>()

        fun drain() {
            val o = pipeline.output
            while (o.hasRemaining()) {
                out.add(o.getFloat())
                o.getFloat() // right channel
            }
        }

        fun feed(frames: Int, sample: (Int) -> Float) {
            var played = 0
            while (played < frames) {
                val n = minOf(bufferFrames, frames - played)
                val start = played
                val input = stereo(n) { sample(start + it) }
                while (input.hasRemaining()) {
                    pipeline.queueInput(input)
                    drain()
                }
                played += n
            }
        }

        // Track A.
        engine.streamGeneration++
        engine.periodOfGeneration[engine.streamGeneration] = periodA
        tail?.let { engine.tails[periodA] = it }
        engine.endedStreamGain = gainA
        feed(clipFrames) { trackA(it) * gainA }
        feed(decoderPadding) { 0f }

        if (endOfList) {
            pipeline.queueEndOfStream()
            var guard = 0
            while (!pipeline.isEnded) {
                drain()
                assertTrue(++guard < 1000)
            }
            return Run(out.toFloatArray())
        }

        // Track B.
        engine.streamGeneration++
        repeat(reconfigurations) {
            pipeline.queueEndOfStream()
            while (!pipeline.isEnded) drain()
            pipeline.configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_FLOAT))
            pipeline.flush()
        }
        feed(bFrames) { levelB }
        drain()
        return Run(out.toFloatArray())
    }

    @Test
    fun blendsThePreDecodedTailIntoTheNextTrack() {
        for (reconfigurations in 1..3) {
            val clip = rate // 1 s played, then a 0.3 s crossfade.
            val fade = rate * 3 / 10
            val engine = DspEngine(DspSettings())
            val run = play(engine, clip, tail(clip, clip + fade, lead = rate / 2), reconfigurations = reconfigurations)
            val out = run.output
            // Nothing is held back or skipped: A's played part, then exactly B's frames.
            assertEquals(clip + rate, out.size)
            // Seamless continuation of A into its tail, then into B.
            for (i in 1 until out.size) assertTrue("jump at $i", abs(out[i] - out[i - 1]) < 0.05f)
            // Mid-blend: tail · cos(π/4) + B · sin(π/4).
            val mid = clip + fade / 2
            assertEquals(trackA(mid) * 0.7071f + 0.2f * 0.7071f, out[mid], 0.01f)
            // After the blend: B alone.
            assertEquals(0.2f, out[clip + fade + 10], 1e-6f)
        }
    }

    @Test
    fun linesUpAMisalignedTail() {
        val clip = rate
        val fade = rate * 3 / 10
        for (misalignment in listOf(-700, -37, 0, 1, 529, 1500)) {
            val engine = DspEngine(DspSettings())
            val run = play(engine, clip, tail(clip, clip + fade, lead = rate, misalignment = misalignment))
            // A misplaced tail would jump at the start of the blend.
            for (i in clip - 5 until clip + 50) {
                assertTrue("misalignment $misalignment: jump at $i", abs(run.output[i] - run.output[i - 1]) < 0.05f)
            }
        }
    }

    @Test
    fun linesUpATailOffByMoreThanASecond() {
        // After a seek in a VBR MP3 the player's clip end can be a second or two off.
        val clip = 4 * rate
        val fade = 2 * rate
        for (misalignment in listOf(-80_000, -50_000, 50_000, 80_000)) {
            val engine = DspEngine(DspSettings())
            val run = play(engine, clip, tail(clip, clip + fade, lead = 3 * rate, misalignment = misalignment))
            for (i in clip - 5 until clip + 50) {
                assertTrue("misalignment $misalignment: jump at $i", abs(run.output[i] - run.output[i - 1]) < 0.05f)
            }
        }
    }

    @Test
    fun aRepeatedLoopIsMatchedNearestToTheExpectedPlace() {
        // Half a second of (dull) noise repeated note for note: every repetition matches perfectly.
        val random = java.util.Random(7)
        val noise = FloatArray(rate / 2) { (random.nextFloat() - 0.5f) * 0.6f }
        val loop = FloatArray(noise.size) { i -> (0 until 32).sumOf { noise[(i + it) % noise.size].toDouble() }.toFloat() / 8 }
        val tail = FloatArray(6 * rate) { loop[it % loop.size] }
        val history = FloatArray(4096) { loop[(3 * rate - 4096 + it) % loop.size] }
        // Played up to 3 s; the decoder thinks it is 0.1 s later.
        val start = TailAligner.align(history, history.size, tail, tail.size, expected = 3 * rate + rate / 10, searchFrames = 3 * rate)
        assertEquals(3 * rate, start)
    }

    @Test
    fun dropsDecoderPaddingBeforeTheTail() {
        // The platform MP3 decoder ends every stream with 529 zero frames.
        val clip = rate
        val fade = rate * 3 / 10
        val run = play(DspEngine(DspSettings()), clip, tail(clip, clip + fade, lead = rate / 2), decoderPadding = 529)
        assertEquals(clip + rate, run.output.size)
        for (i in 1 until run.output.size) assertTrue("jump at $i", abs(run.output[i] - run.output[i - 1]) < 0.05f)
    }

    @Test
    fun dropsDecoderPaddingBetweenGaplessTracks() {
        val run = play(DspEngine(DspSettings()), rate, tail = null, decoderPadding = 529)
        assertEquals(2 * rate, run.output.size)
        assertEquals(0.2f, run.output[rate], 1e-6f)
    }

    @Test
    fun keepsTrailingSilenceAtTheEndOfTheList() {
        val run = play(DspEngine(DspSettings()), rate, tail = null, decoderPadding = 529, endOfList = true)
        assertEquals(rate + 529, run.output.size)
        assertEquals(0f, run.output[rate + 100], 0f)
    }

    @Test
    fun scalesTheTailByTheEndingTracksGain() {
        val clip = rate
        val fade = rate * 3 / 10
        val run = play(DspEngine(DspSettings()), clip, tail(clip, clip + fade, lead = rate / 2), gainA = 0.5f)
        for (i in clip - 5 until clip + 50) assertTrue("jump at $i", abs(run.output[i] - run.output[i - 1]) < 0.05f)
    }

    @Test
    fun withoutTailTheTracksJoinGapless() {
        val run = play(DspEngine(DspSettings()), rate, tail = null)
        assertEquals(2 * rate, run.output.size)
        assertEquals(0.2f, run.output[rate], 1e-6f)
    }

    @Test
    fun tailIsPlayedOutAtTheEndOfTheList() {
        val clip = rate
        val fade = rate * 3 / 10
        val run = play(DspEngine(DspSettings()), clip, tail(clip, clip + fade, lead = rate / 2), endOfList = true)
        assertEquals(clip + fade, run.output.size)
        for (i in 1 until run.output.size) assertTrue("jump at $i", abs(run.output[i] - run.output[i - 1]) < 0.05f)
    }

    @Test
    fun convertsTheTailWhenTheNextTrackHasAnotherRate() {
        val tail = FloatArray(4410 * 2) { 1f }
        val converted = PcmConvert.convert(tail, 4410, 2, 44100, 1, 48000)
        assertEquals(4800, converted.size)
        assertTrue(converted.all { abs(it - 1f) < 1e-6f })
    }

    @Test
    fun mixKeepsTheEndingTrackWhole() {
        val clip = rate
        val fade = rate * 3 / 10
        val engine = DspEngine(DspSettings())
        val t = tail(clip, clip + fade, lead = rate / 2).apply { shape = CrossfadeShape.MIX }
        val out = play(engine, clip, t).output
        assertEquals(clip + rate, out.size)
        for (i in 1 until out.size) assertTrue("jump at $i", abs(out[i] - out[i - 1]) < 0.05f)
        val mid = clip + fade / 2
        // A at full level, B halfway up its curve.
        assertEquals(trackA(mid) + 0.2f * 0.7071f, out[mid], 0.01f)
        // Near the end, before the short ramp to silence: still A whole.
        val late = clip + fade - 600
        assertEquals(trackA(late) + 0.2f * CrossfadeShape.MIX.inGain((fade - 600).toDouble() / fade).toFloat(), out[late], 0.01f)
    }

    @Test
    fun customShapeMovesEachTrackInItsOwnPart() {
        val clip = rate
        val fade = rate
        val engine = DspEngine(DspSettings())
        val t = tail(clip, clip + fade, lead = rate / 2).apply { shape = CrossfadeShape(0.5f, 1f, 0.5f) }
        val out = play(engine, clip, t, bFrames = 2 * rate).output
        // A quarter in: A still whole, B halfway up.
        val quarter = clip + fade / 4
        assertEquals(trackA(quarter) + 0.2f * 0.7071f, out[quarter], 0.01f)
        // Three quarters in: A halfway down, B whole.
        val threeQuarters = clip + fade * 3 / 4
        assertEquals(trackA(threeQuarters) * 0.7071f + 0.2f, out[threeQuarters], 0.01f)
    }

    @Test
    fun aSkipByHandBlendsWhereTheMusicWasIntoTheNewTrack() {
        val engine = DspEngine(DspSettings())
        val crossfade = CrossfadeProcessor(engine)
        val pipeline = AudioProcessingPipeline(ImmutableList.of<AudioProcessor>(crossfade))
        pipeline.configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_FLOAT))
        pipeline.flush()
        val out = ArrayList<Float>()
        fun drain() {
            val o = pipeline.output
            while (o.hasRemaining()) {
                out.add(o.getFloat())
                o.getFloat()
            }
        }
        fun feed(frames: Int, sample: (Int) -> Float) {
            val input = stereo(frames, sample)
            while (input.hasRemaining()) {
                pipeline.queueInput(input)
                drain()
            }
        }
        engine.streamGeneration++
        feed(rate) { trackA(it) }
        // Where the music was: A from 2 s on (the skip came at 2 s, the decoded part starts 0.1 s earlier).
        val lead = rate / 10
        val length = rate / 2
        val samples = FloatArray((lead + length + rate / 5) * 2) { trackA(2 * rate - lead + it / 2) }
        val skipTail = CrossfadeTail(samples, samples.size / 2, 2, rate, clipEndFrame = lead)
        engine.skipTail = SkipTail(skipTail, lead, 0.5f, CrossfadeShape.SKIP, frames = length)
        out.clear()
        // The seek: a flush without an end of stream, then the new track.
        engine.streamGeneration++
        pipeline.flush()
        feed(rate) { 0.2f }
        drain()
        assertEquals(rate, out.size)
        // The blend starts with A whole (at its gain) and B silent.
        assertEquals(trackA(2 * rate) * 0.5f, out[0], 0.01f)
        val i = length / 5 // 20 %: B halfway up (it is whole at 40 %).
        assertEquals(trackA(2 * rate + i) * 0.5f * CrossfadeShape.SKIP.outGain(0.2).toFloat() + 0.2f * 0.7071f, out[i], 0.01f)
        assertEquals(0.2f, out[length + 10], 1e-6f)
        assertTrue(engine.skipTail == null)
    }

    @Test
    fun aStaleSkipTailIsNotUsed() {
        val engine = DspEngine(DspSettings())
        val samples = FloatArray(2000) { 0.5f }
        engine.skipTail = SkipTail(CrossfadeTail(samples, 1000, 2, rate, 0), 0, 1f, CrossfadeShape.SKIP, createdAtNanos = System.nanoTime() - 5_000_000_000L)
        assertTrue(engine.takeSkipTail() == null)
    }
}
