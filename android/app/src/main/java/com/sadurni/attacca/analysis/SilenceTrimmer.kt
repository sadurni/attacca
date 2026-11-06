package com.sadurni.attacca.analysis

import com.sadurni.attacca.settings.TrimSettings

/**
 * Clip to apply to one track; null bounds mean "do not trim that side". With [crossfadeMs], the
 * end is where the crossfade into the next track starts, and that much audio follows it.
 */
data class Trim(
    val startMs: Long?,
    val endMs: Long?,
    val crossfadeMs: Int = 0,
    val crossfadeShape: com.sadurni.attacca.dsp.CrossfadeShape = com.sadurni.attacca.dsp.CrossfadeShape.CROSSFADE,
) {
    val isNone: Boolean get() = startMs == null && endMs == null

    companion object {
        val NONE = Trim(null, null)
    }
}

/** How the transitions of the current list crossfade. */
data class CrossfadeRule(
    val lengthMs: Int = 0,
    val skipSameAlbum: Boolean = true,
    val style: com.sadurni.attacca.dsp.CrossfadeStyle = com.sadurni.attacca.dsp.CrossfadeStyle.CROSSFADE,
    val custom: com.sadurni.attacca.dsp.CrossfadeShape = com.sadurni.attacca.dsp.CrossfadeShape.CROSSFADE,
    val onlyShuffle: Boolean = false,
    /** Playback stops after this track (sleep timer): it plays out whole, with no blend. */
    val noBlendAfter: String? = null,
)

/** A queue entry as seen by the trimming rules. */
data class TrimCandidate(
    /** Tracks in the same folder are treated as the same album. */
    val album: String,
    val analysis: TrackAnalysis?,
    /**
     * Whether this track is the one that follows the previous entry on its album (see
     * [com.sadurni.attacca.library.AlbumShuffle.followsOnAlbum]); null: any two of the same album.
     */
    val followsPrevious: Boolean? = null,
)

/**
 * Decides how much leading/trailing silence to cut from each track.
 *
 * Only silences of at least [TrimSettings.minSilenceMs] are cut, keeping [TrimSettings.marginMs]
 * so attacks and fade-outs are not clipped. With [TrimSettings.respectGaplessAlbums], a silence
 * between two tracks of the same album is left alone when the other side of the transition has
 * no silence: the music flows across the track boundary and the pause is the artist's intent.
 */
object SilenceTrimmer {

    fun trimFor(index: Int, queue: List<TrimCandidate>, settings: TrimSettings): Trim {
        val current = queue.getOrNull(index) ?: return Trim.NONE
        val analysis = current.analysis ?: return Trim.NONE
        if (!settings.enabled) return Trim.NONE

        val previous = queue.getOrNull(index - 1)
        val next = queue.getOrNull(index + 1)

        var start: Long? = null
        if (settings.trimStart && analysis.leadingSilenceMs >= settings.minSilenceMs &&
            !flowsAcross(previous, current, settings, fromPrevious = true)
        ) {
            start = (analysis.audioStartMs - settings.marginMs).coerceAtLeast(0)
        }

        var end: Long? = null
        if (settings.trimEnd && analysis.trailingSilenceMs >= settings.minSilenceMs &&
            !flowsAcross(next, current, settings, fromPrevious = false)
        ) {
            end = (analysis.audioEndMs + settings.marginMs).coerceAtMost(analysis.durationMs)
        }

        // Never leave a degenerate clip.
        if (start != null && end != null && end - start < MIN_PLAYABLE_MS) return Trim.NONE
        return Trim(start, end)
    }

    /**
     * Whether the neighbour of the same album joins [current] without silence on its side, in
     * which case the silence on [current]'s side is kept.
     */
    private fun flowsAcross(
        neighbour: TrimCandidate?,
        current: TrimCandidate,
        settings: TrimSettings,
        fromPrevious: Boolean,
    ): Boolean {
        if (!settings.respectGaplessAlbums || neighbour == null) return false
        // Only an album playing on: two tracks of it a list puts together are apart.
        val follows = if (fromPrevious) current.followsPrevious else neighbour.followsPrevious
        if (follows == false || neighbour.album != current.album) return false
        val other = neighbour.analysis ?: return false
        val neighbourSilence = if (fromPrevious) other.trailingSilenceMs else other.leadingSilenceMs
        return neighbourSilence < settings.minSilenceMs
    }

    private const val MIN_PLAYABLE_MS = 1_000L
}
