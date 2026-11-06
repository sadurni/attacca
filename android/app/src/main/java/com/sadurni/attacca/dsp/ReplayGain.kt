// The deprecated flac.VorbisComment base class also matches its vorbis.VorbisComment subclass.
@file:Suppress("DEPRECATION")

package com.sadurni.attacca.dsp

import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.extractor.metadata.flac.VorbisComment
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import com.sadurni.attacca.settings.DspSettings
import com.sadurni.attacca.settings.ReplayGainMode
import kotlin.math.log10
import kotlin.math.min

/** ReplayGain values read from a track's tags. Gains in dB, peaks as linear amplitude. */
data class ReplayGainInfo(
    val trackGainDb: Float? = null,
    val trackPeak: Float? = null,
    val albumGainDb: Float? = null,
    val albumPeak: Float? = null,
) {
    val isEmpty: Boolean get() = trackGainDb == null && albumGainDb == null

    companion object {
        val NONE = ReplayGainInfo()

        /** Opus R128 gains are relative to -23 LUFS; ReplayGain 2.0 targets -18 LUFS. */
        private const val R128_TO_REPLAYGAIN_DB = 5f

        fun fromFormats(formats: Array<out Format>): ReplayGainInfo =
            formats.asSequence()
                .map { fromMetadata(it.metadata) }
                .firstOrNull { !it.isEmpty } ?: NONE

        fun fromMetadata(metadata: Metadata?): ReplayGainInfo {
            if (metadata == null) return NONE
            val tags = HashMap<String, String>()
            for (i in 0 until metadata.length()) {
                when (val entry = metadata.get(i)) {
                    // ID3v2 TXXX frames (MP3, AIFF, WAV).
                    is TextInformationFrame ->
                        if (entry.id == "TXXX" && entry.description != null && entry.values.isNotEmpty()) {
                            tags[entry.description!!.uppercase()] = entry.values[0]
                        }
                    // Vorbis comments (FLAC, Ogg Vorbis, Opus).
                    is VorbisComment -> tags[entry.key.uppercase()] = entry.value
                    // MP4 freeform "----:com.apple.iTunes:replaygain_*" atoms.
                    is InternalFrame -> tags[entry.description.uppercase()] = entry.text
                }
            }
            return fromTags(tags)
        }

        /** Builds the info from a map of upper-cased tag names to raw values. */
        fun fromTags(tags: Map<String, String>): ReplayGainInfo {
            var trackGain = tags["REPLAYGAIN_TRACK_GAIN"]?.let(::parseGainDb)?.takeIf(::plausibleGain)
            var albumGain = tags["REPLAYGAIN_ALBUM_GAIN"]?.let(::parseGainDb)?.takeIf(::plausibleGain)
            if (trackGain == null) {
                trackGain = tags["R128_TRACK_GAIN"]?.let(::parseR128)?.takeIf(::plausibleGain)
            }
            if (albumGain == null) {
                albumGain = tags["R128_ALBUM_GAIN"]?.let(::parseR128)?.takeIf(::plausibleGain)
            }
            return ReplayGainInfo(
                trackGainDb = trackGain,
                trackPeak = tags["REPLAYGAIN_TRACK_PEAK"]?.let(::parseNumber)?.takeIf(::plausiblePeak),
                albumGainDb = albumGain,
                albumPeak = tags["REPLAYGAIN_ALBUM_PEAK"]?.let(::parseNumber)?.takeIf(::plausiblePeak),
            )
        }

        /**
         * Peaks are amplitudes around 1 (a little over after lossy encoding). Some taggers write
         * them on another scale (e.g. 1126107 for 1.07): taken as a peak, that turned the track
         * down by over 100 dB to keep it from clipping. Such a peak counts as unknown.
         */
        private const val MAX_PEAK = 4f

        /** Gains beyond this are a broken tag, not a level: ignored. */
        private const val MAX_GAIN_DB = 50f

        private fun plausiblePeak(peak: Float): Boolean = peak > 0f && peak <= MAX_PEAK

        private fun plausibleGain(db: Float): Boolean = kotlin.math.abs(db) <= MAX_GAIN_DB

        /** Parses values like "-6.48 dB", "+2.1dB" or "−3,5 dB". */
        internal fun parseGainDb(raw: String): Float? =
            parseNumber(raw.replace("db", "", ignoreCase = true))

        private fun parseNumber(raw: String): Float? =
            raw.trim()
                .replace('−', '-')
                .replace(',', '.')
                .removePrefix("+")
                .trim()
                .toFloatOrNull()
                ?.takeIf { it.isFinite() }

        /** R128 gains are Q7.8 fixed point integers. */
        private fun parseR128(raw: String): Float? =
            raw.trim().toIntOrNull()?.let { it / 256f + R128_TO_REPLAYGAIN_DB }
    }
}

object ReplayGain {

    /**
     * Returns the gain in dB to apply to a track.
     *
     * @param shuffle whether playback order is shuffled; in [ReplayGainMode.AUTO] album gain is used
     *   only when playing in order, since album gain is meaningless for mixed tracks.
     */
    /** The peak that goes with the gain [resolveGainDb] picks (null when unknown). */
    fun resolvePeak(
        info: ReplayGainInfo,
        settings: DspSettings,
        shuffle: Boolean,
        mode: ReplayGainMode = settings.replayGainMode,
    ): Float? {
        val useAlbum = when (mode) {
            ReplayGainMode.OFF -> false
            ReplayGainMode.TRACK -> false
            ReplayGainMode.ALBUM -> true
            ReplayGainMode.AUTO -> !shuffle
        }
        return when {
            useAlbum && info.albumGainDb != null -> info.albumPeak
            info.trackGainDb != null -> info.trackPeak
            else -> info.albumPeak ?: info.trackPeak
        }
    }

    fun resolveGainDb(
        info: ReplayGainInfo,
        settings: DspSettings,
        shuffle: Boolean,
        mode: ReplayGainMode = settings.replayGainMode,
    ): Float {
        val useAlbum = when (mode) {
            ReplayGainMode.OFF -> return 0f
            ReplayGainMode.TRACK -> false
            ReplayGainMode.ALBUM -> true
            ReplayGainMode.AUTO -> !shuffle
        }
        val gain: Float?
        val peak: Float?
        if (useAlbum && info.albumGainDb != null) {
            gain = info.albumGainDb
            peak = info.albumPeak
        } else if (info.trackGainDb != null) {
            gain = info.trackGainDb
            peak = info.trackPeak
        } else {
            gain = info.albumGainDb
            peak = info.albumPeak
        }
        if (gain == null) return settings.untaggedGainDb

        var db = gain + settings.replayGainPreampDb
        if (settings.replayGainPreventClipping && peak != null && peak > 0f) {
            db = min(db, -20f * log10(peak))
        }
        return db
    }
}
