package com.sadurni.attacca.tuning

import com.sadurni.attacca.dsp.EffectsChain
import com.sadurni.attacca.settings.DspSettings

/**
 * What the sound guide plays: a sample going round and round through Attacca's sound processing
 * with the settings being tried. The settings can change at any moment (the chain moves to them
 * smoothly, so switching A/B does not click); a new sample starts from its beginning. The platform
 * pulls blocks with [read] on its own audio thread.
 */
class TuneLoop(val sampleRate: Int) {

    private class Source(val samples: FloatArray, val headroomDb: Float)

    private val chain = EffectsChain(sampleRate)

    @Volatile
    private var source: Source? = null

    @Volatile
    private var restart = false

    /** The settings heard. */
    @Volatile
    var settings: DspSettings = DspSettings().withoutEffects()

    /** Where in the sample the last block read began (frames), for what the guide shows. */
    @Volatile
    var position: Int = 0
        private set

    /** Lowers everything by this much (linear), to start quietly. */
    @Volatile
    var volume: Float = 1f

    private var at = 0
    private var gain = 0f

    /** Plays [samples] (interleaved stereo at [sampleRate]) from its start; null is silence. */
    fun play(samples: FloatArray?, headroomDb: Float = 6f) {
        source = samples?.takeIf { it.size >= 2 }?.let { Source(it, headroomDb) }
        startAt = 0
        restart = true
    }

    fun play(fragment: Fragment) = play(fragment.samples, fragment.headroomDb)

    /** Plays [samples] from [frame] (a little before what is to be heard). */
    fun playFrom(samples: FloatArray, frame: Int, headroomDb: Float = 6f) {
        source = samples.takeIf { it.size >= 2 }?.let { Source(it, headroomDb) }
        startAt = frame.coerceAtLeast(0)
        restart = true
    }

    @Volatile
    private var startAt = 0

    /** Plays [samples] in place of what plays, from where it is (the same sample changed: A/B without starting again). */
    fun replace(samples: FloatArray, headroomDb: Float = 6f) {
        source = samples.takeIf { it.size >= 2 }?.let { Source(it, headroomDb) }
    }

    /** Fills [out] with [frames] interleaved stereo frames. */
    fun read(out: FloatArray, frames: Int) {
        val src = source
        if (restart) {
            restart = false
            at = startAt
            chain.clear()
        }
        if (src == null) {
            java.util.Arrays.fill(out, 0, frames * 2, 0f)
            position = 0
            return
        }
        val total = src.samples.size / 2
        if (at >= total) at = 0
        position = at
        val target = volume
        for (f in 0 until frames) {
            // The volume moves smoothly too.
            gain += (target - gain) * 0.002f
            out[f * 2] = src.samples[at * 2] * gain
            out[f * 2 + 1] = src.samples[at * 2 + 1] * gain
            at++
            if (at >= total) at = 0
        }
        chain.headroomDb = src.headroomDb
        chain.process(out, frames, settings)
    }
}
