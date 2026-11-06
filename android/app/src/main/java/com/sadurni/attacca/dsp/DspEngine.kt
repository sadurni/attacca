package com.sadurni.attacca.dsp

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessorChain
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import com.sadurni.attacca.settings.DspSettings
import com.sadurni.attacca.settings.ReplayGainMode
import kotlin.math.pow

/**
 * Shared state between the app and the audio thread.
 *
 * Settings and per-track values are published through volatile fields; the processors read them
 * once per buffer, so changes take effect within a few milliseconds without reconfiguring the
 * audio sink (which would break gapless playback).
 */
class DspEngine(initialSettings: DspSettings) {

    @Volatile
    var settings: DspSettings = initialSettings
        private set

    /** Settings waiting for the gain stage to fade out before they take over (profile switch). */
    @Volatile
    internal var pendingSettings: DspSettings? = null

    @Volatile
    var isPlaying: Boolean = false

    /**
     * Publishes new settings. A profile switch can change many stages at once, so with [fade] the
     * output dips to silence for one buffer while the switch happens, avoiding clicks.
     */
    fun applySettings(newSettings: DspSettings, fade: Boolean) {
        if (fade && isPlaying) {
            pendingSettings = newSettings
        } else {
            pendingSettings = null
            settings = newSettings
        }
    }

    internal fun commitPendingSettings() {
        pendingSettings?.let { settings = it }
        pendingSettings = null
    }

    @Volatile
    var currentReplayGain: ReplayGainInfo = ReplayGainInfo.NONE

    /** Whether [currentReplayGain] came from the track's tags (measured gains never replace those). */
    @Volatile
    var currentGainFromTags: Boolean = false

    /**
     * Gains measured by the analysis (EBU R128) for tracks without ReplayGain tags, by playlist
     * period: the renderer takes them when such a track starts.
     */
    val measuredGains = java.util.concurrent.ConcurrentHashMap<Any, ReplayGainInfo>()

    /** True when shuffling individual tracks (album shuffle keeps album gain meaningful). */
    @Volatile
    var shuffleEnabled: Boolean = false

    // Crossfade, written by the renderer (playback thread), the gain stage and the service.

    /** Increments when the first buffer of a new track reaches the audio sink. */
    @Volatile
    var streamGeneration: Int = 0

    /** Playlist period of each recent [streamGeneration]. */
    val periodOfGeneration = java.util.concurrent.ConcurrentHashMap<Int, Any>()

    /** Pre-decoded ends of the crossfaded tracks around the current one, by playlist period. */
    val tails = java.util.concurrent.ConcurrentHashMap<Any, CrossfadeTail>()

    /** The file of each period that should blend into the next one, its end decoded or not yet. */
    val blendingPeriods = java.util.concurrent.ConcurrentHashMap<Any, String>()

    /**
     * Left by a skip by hand right before it seeks; the crossfade stage takes it at the seek's
     * flush. Too old means the skip did not happen and it is dropped.
     */
    @Volatile
    var skipTail: SkipTail? = null

    /** Where the output is, as last asked by the player (see [HeardPosition.fileUsAt]). */
    @Volatile
    var heard: HeardPosition? = null

    /** The last seconds of processed audio on its way out (see [OutputTap]). */
    val tap = OutputTap()

    /** Levels of the sound on its way out, for Attacca O'rama. */
    val meter = com.sadurni.attacca.diorama.LevelMeter()

    /** The levels (level, low, high) of what is heard now, into [into]; false when none are known. */
    fun heardLevels(into: FloatArray): Boolean {
        val h = heard ?: return false
        return meter.at(h.rendererUsAt(System.nanoTime()), into)
    }

    /** The player's time of the decoded buffer being handed to the output now. */
    @Volatile
    var presentationUs: Long = androidx.media3.common.C.TIME_UNSET

    /** A blend after a skip by hand is playing (re-clipping the track then would cut it). */
    @Volatile
    var skipBlending: Boolean = false

    internal fun takeSkipTail(): SkipTail? {
        val skip = skipTail ?: return null
        skipTail = null
        return skip.takeIf { System.nanoTime() - it.createdAtNanos < SKIP_TAIL_TIMEOUT_NS }
    }

    /** Linear gain the gain stage applied to the track that just ended. */
    @Volatile
    var endedStreamGain: Float = 1f

    /** Per-list ReplayGain mode; null follows the profile. */
    @Volatile
    var replayGainModeOverride: ReplayGainMode? = null

    /** Total gain in dB for the current track: ReplayGain plus the global preamp. */
    fun currentGainDb(): Float {
        val s = settings
        val rg = ReplayGain.resolveGainDb(
            currentReplayGain,
            s,
            shuffleEnabled,
            replayGainModeOverride ?: s.replayGainMode,
        )
        return rg + if (s.preampEnabled) s.preampDb else 0f
    }

    /**
     * Room the track playing leaves under full scale once its gain is applied (dB): from its
     * ReplayGain peak, or full scale when the peak is unknown.
     */
    fun currentHeadroomDb(): Float {
        val s = settings
        val peak = ReplayGain.resolvePeak(currentReplayGain, s, shuffleEnabled, replayGainModeOverride ?: s.replayGainMode)
        val peakDb = if (peak != null && peak > 0f) 20f * kotlin.math.log10(peak) else 0f
        return -(peakDb + currentGainDb())
    }

    /** A new processor chain for one audio sink. Processors keep per-stream state. */
    fun createProcessorChain(): AudioProcessorChain = Chain(this)

    private class Chain(engine: DspEngine) : AudioProcessorChain {
        private val silenceSkipping = SilenceSkippingAudioProcessor()
        private val sonic = SonicAudioProcessor()

        // Sonic needs 16-bit input, so speed changes run first; the gain stage then converts to
        // float and everything after it, including the AudioTrack, runs in 32-bit float.
        // Order: ReplayGain + preamp → crossfade → one output format → EQ → dynamic bass → width → balance → crossfeed →
        // compressor → limiter.
        private val processors: Array<AudioProcessor> = arrayOf(
            silenceSkipping,
            sonic,
            GainProcessor(engine),
            // After the gain stage, so each track is mixed with its own ReplayGain applied.
            CrossfadeProcessor(engine),
            // From here on one rate and stereo, so a track in another format does not stop the
            // output to build a new AudioTrack.
            OutputFormatProcessor(engine),
            EffectsProcessor(engine),
            CrossfeedProcessor(engine),
            CompressorProcessor(engine),
            LimiterProcessor(engine),
        )

        override fun getAudioProcessors(): Array<AudioProcessor> = processors

        override fun applyPlaybackParameters(playbackParameters: PlaybackParameters): PlaybackParameters {
            sonic.setSpeed(playbackParameters.speed)
            sonic.setPitch(playbackParameters.pitch)
            return playbackParameters
        }

        override fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean): Boolean {
            // ExoPlayer's skip-silence removes silences anywhere in the track; Attacca trims only
            // leading/trailing silence (phase 2), so this is never enabled.
            silenceSkipping.setEnabled(false)
            return false
        }

        override fun getMediaDuration(playoutDuration: Long): Long =
            if (sonic.isActive) sonic.getMediaDuration(playoutDuration) else playoutDuration

        override fun getSkippedOutputFrameCount(): Long = silenceSkipping.skippedFrames
    }
}

internal fun dbToLinear(db: Float): Float = 10f.pow(db / 20f)

private const val SKIP_TAIL_TIMEOUT_NS = 1_500_000_000L

/**
 * The instant of [period] being heard ([periodUs], which for a file is its own time, clipped or
 * not) when the player last asked, at [atNanos]. The player asks often but not continuously, so
 * the moment wanted is worked out from the clock.
 */
class HeardPosition(val period: Any, val periodUs: Long, val atNanos: Long, val speed: Float, val rendererUs: Long = periodUs) {
    /** The player's own time heard at [nanos] (as [OutputTap] keeps it). */
    fun rendererUsAt(nanos: Long): Long = rendererUs + ((nanos - atNanos) / 1000 * speed).toLong()

    fun fileUsAt(nanos: Long): Long = periodUs + ((nanos - atNanos) / 1000 * speed).toLong()
}
