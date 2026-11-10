package com.sadurni.attacca.tuning

import kotlin.math.log10

/** A step of the transitions guide, each heard on a pair of real tracks. */
enum class JoinStep {
    /** Two different songs: one after the other, or blended, and how. */
    BLEND,

    /** A song with silence at its end (or the next with silence at its start): kept or left out. */
    SILENCE,

    /** Two tracks in a row of one album: always one after the other, or blended as the rest. */
    ALBUM,
}

/** What kind of pair each step wants. */
enum class PairKind { DIFFERENT, SILENCE, ALBUM }

/** The choices of the transitions guide; each is the index of the option chosen in its step. */
data class JoinAnswers(
    /** 0 gapless, 1 automatic, 2 crossfade of 4 s, 3 of 8 s. */
    val blend: Int = 1,
    /** 0 left out, 1 kept. */
    val silence: Int = 0,
    /** 0 the album one after the other, 1 blended as the rest. */
    val album: Int = 0,
    /** The blend length for "automatic" (what the settings have, or 6 s). */
    val automaticMs: Int = 6_000,
)

/**
 * The transitions guide: how one track goes into the next, heard on real pairs of the library.
 * Each choice becomes the player's own settings (blend length and style, silences, albums).
 */
object TransitionTuning {

    val BLEND_OPTIONS = listOf("join_gapless", "join_automatic", "join_crossfade_4", "join_crossfade_8")
    val SILENCE_OPTIONS = listOf("silence_trim", "silence_keep")
    val ALBUM_OPTIONS = listOf("album_gapless", "album_blend")

    /** The steps: the album one only when tracks are blended at all, and only where the player has that choice. */
    fun steps(a: JoinAnswers, albumStep: Boolean = true): List<JoinStep> = buildList {
        add(JoinStep.BLEND)
        add(JoinStep.SILENCE)
        if (albumStep && a.blend != 0) add(JoinStep.ALBUM)
    }

    fun pairKind(step: JoinStep): PairKind = when (step) {
        JoinStep.BLEND -> PairKind.DIFFERENT
        JoinStep.SILENCE -> PairKind.SILENCE
        JoinStep.ALBUM -> PairKind.ALBUM
    }

    fun options(step: JoinStep): List<String> = when (step) {
        JoinStep.BLEND -> BLEND_OPTIONS
        JoinStep.SILENCE -> SILENCE_OPTIONS
        JoinStep.ALBUM -> ALBUM_OPTIONS
    }

    fun chosen(step: JoinStep, a: JoinAnswers): Int = when (step) {
        JoinStep.BLEND -> a.blend
        JoinStep.SILENCE -> a.silence
        JoinStep.ALBUM -> a.album
    }

    fun choose(step: JoinStep, a: JoinAnswers, index: Int): JoinAnswers = when (step) {
        JoinStep.BLEND -> a.copy(blend = index)
        JoinStep.SILENCE -> a.copy(silence = index)
        JoinStep.ALBUM -> a.copy(album = index)
    }

    /** The blend length chosen (0: gapless). */
    fun lengthMs(a: JoinAnswers): Int = when (a.blend) {
        0 -> 0
        1 -> a.automaticMs
        2 -> 4_000
        else -> 8_000
    }

    /** How the pair of [step] is joined with [a] (an album pair in order stays gapless unless blended as the rest). */
    fun join(step: JoinStep, a: JoinAnswers): JoinChoice {
        val length = if (step == JoinStep.ALBUM && a.album == 0) 0 else lengthMs(a)
        return JoinChoice(length, a.blend == 1, a.silence == 0)
    }
}

/** A step of the volume guide. */
enum class LevelStep {
    /** A loud track and a quiet one of other albums, one after the other: as they are, or evened out. */
    BETWEEN,

    /** A quiet and a loud track of one album: the album as recorded, or each song the same. */
    WITHIN,

    /** How loud everything is brought to. */
    OVERALL,
}

/** The choices of the volume guide. */
data class LevelAnswers(
    /** 0 as the files are, 1 evened out. */
    val between: Int = 1,
    /** 0 the album as recorded, 1 each song the same. */
    val within: Int = 0,
    /** 0 standard, 1 +3 dB, 2 +6 dB. */
    val overall: Int = 0,
)

/** A track of the volume guide's samples: its stretch, its loudness (LUFS) and its album's. */
class LevelTrack(val pcm: Pcm, val title: String?, val lufs: Float, val albumLufs: Float?)

/** A sample of the volume guide: two tracks one after the other, and where the second begins. */
class LevelSample(val samples: FloatArray, val rate: Int, val secondFrom: Int)

/**
 * The volume guide: how loud each track plays next to the others, heard on real tracks of the
 * library chosen for it (the loudest and quietest, a quiet and a loud one of one album).
 */
object LevelTuning {

    /** The loudness tracks are brought to (as ReplayGain). */
    const val REFERENCE_LUFS = -18f

    /** Seconds of each track in a sample. */
    const val SECONDS = 8

    /** The steps: the album and the overall level only when tracks are evened out at all (and the player has them). */
    fun steps(a: LevelAnswers, more: Boolean = true): List<LevelStep> = buildList {
        add(LevelStep.BETWEEN)
        if (more && a.between == 1) {
            add(LevelStep.WITHIN)
            add(LevelStep.OVERALL)
        }
    }

    fun options(step: LevelStep): List<String> = when (step) {
        LevelStep.BETWEEN -> listOf("level_off", "level_even")
        LevelStep.WITHIN -> listOf("level_album", "level_track")
        LevelStep.OVERALL -> listOf("level_standard", "level_up3", "level_up6")
    }

    fun chosen(step: LevelStep, a: LevelAnswers): Int = when (step) {
        LevelStep.BETWEEN -> a.between
        LevelStep.WITHIN -> a.within
        LevelStep.OVERALL -> a.overall
    }

    fun choose(step: LevelStep, a: LevelAnswers, index: Int): LevelAnswers = when (step) {
        LevelStep.BETWEEN -> a.copy(between = index)
        LevelStep.WITHIN -> a.copy(within = index)
        LevelStep.OVERALL -> a.copy(overall = index)
    }

    fun preampDb(a: LevelAnswers): Float = when (a.overall) { 1 -> 3f; 2 -> 6f; else -> 0f }

    /**
     * The gain (dB) [track] plays at with [a], as the player levels: nothing when not evened out;
     * else its own loudness or, for an album as recorded, the album's; the overall lift on top.
     */
    fun gainDb(track: LevelTrack, a: LevelAnswers, step: LevelStep): Float {
        val evened = a.between == 1
        if (!evened) return 0f
        // Between albums the album choice changes nothing heard: each album is brought to the level.
        val byAlbum = step != LevelStep.BETWEEN && a.within == 0
        val lufs = if (byAlbum) track.albumLufs ?: track.lufs else track.lufs
        val preamp = if (step == LevelStep.OVERALL) preampDb(a) else 0f
        return (REFERENCE_LUFS - lufs).coerceIn(-18f, 18f) + preamp
    }

    /** [first] then [second], [SECONDS] of each with a short pause, each at its gain with [a]. */
    fun render(first: LevelTrack, second: LevelTrack, a: LevelAnswers, step: LevelStep): LevelSample {
        val rate = first.pcm.rate
        val each = SECONDS * rate
        val pause = rate * 3 / 10
        val out = FloatArray((each * 2 + pause * 2) * 2)
        var o = 0
        fun put(t: LevelTrack) {
            val g = Math.pow(10.0, gainDb(t, a, step) / 20.0).toFloat()
            val n = minOf(each, t.pcm.frames)
            val fade = rate / 20
            for (f in 0 until n) {
                var e = g
                if (f < fade) e *= f.toFloat() / fade
                if (f >= n - fade) e *= (n - 1 - f).toFloat() / fade
                out[(o + f) * 2] = t.pcm.samples[f * 2] * e
                out[(o + f) * 2 + 1] = t.pcm.samples[f * 2 + 1] * e
            }
            o += each + pause
        }
        put(first)
        val second0 = o
        put(second)
        return LevelSample(out, rate, second0)
    }

    /** Loudness (≈ LUFS) of [pcm] from its RMS, for a track whose loudness was never measured. */
    fun estimateLufs(pcm: Pcm): Float = (Joints.levelDb(pcm) + 0.7).toFloat()

    /** The loudness of an album from its tracks' (energy average). */
    fun albumLufs(tracks: List<Float>): Float? {
        if (tracks.isEmpty()) return null
        val mean = tracks.sumOf { Math.pow(10.0, it / 10.0) } / tracks.size
        return (10 * log10(mean)).toFloat()
    }
}
