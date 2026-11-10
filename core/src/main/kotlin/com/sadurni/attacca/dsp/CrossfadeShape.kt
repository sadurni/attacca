package com.sadurni.attacca.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin

/**
 * How the two tracks of a crossfade change level, as fractions of the blend (0 = its start,
 * 1 = its end). The ending track stays whole until [outFrom] and is gone at [outTo]; the next
 * track starts at once (every output frame is one of its frames, so it can never wait) and is
 * whole from [inTo] on. Both move on equal-power curves.
 */
data class CrossfadeShape(val outFrom: Float, val outTo: Float, val inTo: Float) {

    /** The ending track keeps its level to the end of the blend. */
    val keepsEnding: Boolean get() = outFrom >= 1f

    fun outGain(t: Double): Double = when {
        t <= outFrom -> 1.0
        t >= outTo -> 0.0
        else -> cos((t - outFrom) / (outTo - outFrom) * (PI / 2))
    }

    fun inGain(t: Double): Double = when {
        t >= inTo -> 1.0
        else -> sin(t / inTo * (PI / 2))
    }

    fun encode(): FloatArray = floatArrayOf(outFrom, outTo, inTo)

    companion object {
        /** Both tracks over the whole blend. */
        val CROSSFADE = CrossfadeShape(0f, 1f, 1f)

        /** The ending track plays out whole while the next one rises under it. */
        val MIX = CrossfadeShape(1f, 1f, 1f)

        /** Both tracks as they are: each one's own fade does the work. */
        val OVERLAY = CrossfadeShape(1f, 1f, 0f)

        /** The ending track fades over the blend; the next one is already whole. */
        val FADE_OUT_ONLY = CrossfadeShape(0f, 1f, 0f)

        /** A skip by hand: the next track comes up quickly while the left one fades away. */
        val SKIP = CrossfadeShape(0f, 1f, 0.4f)

        /** Valid values in order; anything out of range is brought into it. */
        fun of(outFrom: Float, outTo: Float, inTo: Float): CrossfadeShape {
            val from = outFrom.coerceIn(0f, 1f)
            return CrossfadeShape(from, outTo.coerceIn(from, 1f), inTo.coerceIn(0f, 1f))
        }

        fun decode(values: FloatArray?): CrossfadeShape? =
            values?.takeIf { it.size == 3 }?.let { of(it[0], it[1], it[2]) }
    }
}

/** The shape chosen in the settings. */
enum class CrossfadeStyle { CROSSFADE, MIX, AUTOMATIC, CUSTOM }

/** One transition: how long the blend lasts and how the levels move in it. */
data class CrossfadePlan(val lengthMs: Int, val shape: CrossfadeShape)

/**
 * Decides each transition for [CrossfadeStyle.AUTOMATIC] from the loudness of the tracks' ends
 * (their waveforms: level per bin across the whole file, the loudest bin being 1).
 *
 * - An ending that already fades out by itself is not faded again (it would vanish early): it
 *   plays out whole and the blend lasts as long as its own fade, up to the length set.
 * - An ending that stays loud to the last moment (a final chord, a hard stop) keeps its end too,
 *   with a short blend so the next track does not pile up on it. But a file that ends in the
 *   middle of its sound (a live track cut before the applause dies, a track of an album that runs
 *   on into the next, a mix) has no end of its own: kept whole it would stop dead, so it is faded.
 * - A next track that starts softly by itself is not faded in.
 * - Otherwise both fade over the length set, as a plain crossfade.
 */
object CrossfadePlanner {

    /** How far below its usual level an ending must fall to count as a fade-out (dB). */
    const val FADE_DROP_DB = 15.0

    /** Within this of its usual level at the very end, an ending is a hard one (dB). */
    const val HARD_END_DB = 6.0

    /** A start this far below the track's level a little later is a soft start (dB). */
    const val SOFT_START_DB = 12.0

    const val MIN_FADE_MS = 1_500L
    const val SHORT_BLEND_MS = 2_000

    /** Shortest blend for a fade-out the track makes itself. */
    const val MIN_NATURAL_MS = 2_000

    /**
     * Less than this between the end of the sound and the end of the file, the file stops in the
     * middle of its sound (a real hard ending leaves at least its decay or a little silence).
     */
    const val CUT_OFF_MS = 200L

    /** Measured stretch before the blend that tells the track's usual level. */
    private const val BODY_MS = 6_000L
    private const val EDGE_MS = 1_000L

    /**
     * [outWaveform]/[outDurationMs]/[outEndMs]: the ending track and where its sound ends;
     * [inWaveform]/[inDurationMs]/[inStartMs]: the next one and where its sound starts (an empty
     * waveform when not known yet). [lengthMs] is the length set.
     */
    fun plan(
        lengthMs: Int,
        outWaveform: FloatArray,
        outDurationMs: Long,
        outEndMs: Long,
        inWaveform: FloatArray,
        inDurationMs: Long,
        inStartMs: Long,
    ): CrossfadePlan {
        val ending = ending(outWaveform, outDurationMs, outEndMs, lengthMs)
        val softStart = softStart(inWaveform, inDurationMs, inStartMs, lengthMs)
        return when (ending) {
            is Ending.Fades -> {
                val length = ending.fadeMs.toInt().coerceIn(minOf(MIN_NATURAL_MS, lengthMs), lengthMs)
                CrossfadePlan(length, if (softStart) CrossfadeShape.OVERLAY else CrossfadeShape.MIX)
            }
            Ending.Hard -> CrossfadePlan(minOf(lengthMs, SHORT_BLEND_MS), if (softStart) CrossfadeShape.OVERLAY else CrossfadeShape.MIX)
            Ending.Other -> CrossfadePlan(lengthMs, if (softStart) CrossfadeShape.FADE_OUT_ONLY else CrossfadeShape.CROSSFADE)
        }
    }

    sealed interface Ending {
        /** Falls by itself over its last [fadeMs]. */
        data class Fades(val fadeMs: Long) : Ending
        data object Hard : Ending
        data object Other : Ending
    }

    fun ending(waveform: FloatArray, durationMs: Long, endMs: Long, lengthMs: Int): Ending {
        if (waveform.isEmpty() || durationMs <= 0) return Ending.Other
        val bodyTo = endMs - lengthMs
        val bodyFrom = bodyTo - BODY_MS
        if (bodyFrom < 0) return Ending.Other
        val body = median(levels(waveform, durationMs, bodyFrom, bodyTo)) ?: return Ending.Other
        if (body < QUIET_DB) return Ending.Other
        val last = levels(waveform, durationMs, endMs - EDGE_MS, endMs).maxOrNull() ?: return Ending.Other
        // Loud to the very end of the file: cut off, not ended (see CUT_OFF_MS).
        if (last >= body - HARD_END_DB) return if (durationMs - endMs < CUT_OFF_MS) Ending.Other else Ending.Hard
        if (last > body - FADE_DROP_DB) return Ending.Other
        // Where the fall begins: the last moment still near the usual level.
        val step = binMs(waveform, durationMs)
        var t = endMs - EDGE_MS
        while (t > bodyFrom && level(waveform, durationMs, t) < body - 3.0) t -= step
        val fadeMs = endMs - t
        return if (fadeMs >= MIN_FADE_MS) Ending.Fades(fadeMs) else Ending.Other
    }

    fun softStart(waveform: FloatArray, durationMs: Long, startMs: Long, lengthMs: Int): Boolean {
        if (waveform.isEmpty() || durationMs <= 0) return false
        val first = levels(waveform, durationMs, startMs, startMs + EDGE_MS).maxOrNull() ?: return false
        val body = median(levels(waveform, durationMs, startMs + lengthMs, startMs + lengthMs + BODY_MS)) ?: return false
        return body >= QUIET_DB && first <= body - SOFT_START_DB
    }

    private const val QUIET_DB = -40.0

    private fun binMs(waveform: FloatArray, durationMs: Long): Long = max(1L, durationMs / waveform.size)

    private fun level(waveform: FloatArray, durationMs: Long, ms: Long): Double {
        val bin = (ms * waveform.size / durationMs).toInt().coerceIn(0, waveform.lastIndex)
        return 20 * log10(max(waveform[bin].toDouble(), 1e-5))
    }

    /** Levels (dB) of the bins covering [fromMs, toMs). */
    private fun levels(waveform: FloatArray, durationMs: Long, fromMs: Long, toMs: Long): List<Double> {
        val from = (fromMs.coerceAtLeast(0) * waveform.size / durationMs).toInt().coerceIn(0, waveform.lastIndex)
        val to = (toMs.coerceAtMost(durationMs) * waveform.size / durationMs).toInt().coerceIn(from + 1, waveform.size)
        return (from until to).map { 20 * log10(max(waveform[it].toDouble(), 1e-5)) }
    }

    private fun median(values: List<Double>): Double? = values.sorted().takeIf { it.isNotEmpty() }?.let { it[it.size / 2] }
}
