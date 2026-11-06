package com.sadurni.attacca.diorama

/**
 * The long stretches of a track that sound far quieter or louder than the rest of it (a ballad
 * that turns into hard rock and back, say): there the land changes its world for a while.
 * [modeAt] gives -1 (a quiet stretch), +1 (a loud one) or 0 (as the track is).
 */
class Sections private constructor(private val modes: ByteArray) {

    fun modeAt(ms: Double): Int {
        val i = (ms / STEP_MS).toInt()
        return if (i in modes.indices) modes[i].toInt() else 0
    }

    /** How many stretches differ from the track (for tests and tuning). */
    val changes: Int get() = (1 until modes.size).count { modes[it] != modes[it - 1] }

    companion object {
        const val STEP_MS = 1000L

        /** A stretch must last this long to count. */
        const val MIN_MS = 25_000L

        /** A break this short between two stretches alike joins them. */
        const val GAP_MS = 12_000L

        /** How far apart (in the waveform's 0 … 1) the track's quiet and loud parts must be. */
        const val CONTRAST = 0.42f

        /** The stretches of [wave]; null when the track is even enough to keep one world. */
        fun of(wave: SongWave): Sections? {
            val len = wave.lengthMs
            if (len < 3 * MIN_MS) return null
            val n = (len / STEP_MS).toInt()
            val level = FloatArray(n) { wave.mean(wave.startMs + it * STEP_MS + STEP_MS / 2.0, 12_000.0) }
            val sorted = level.copyOf().also { it.sort() }
            val p10 = sorted[(n * 0.10).toInt()]
            val p90 = sorted[(n * 0.90).toInt().coerceAtMost(n - 1)]
            if (p90 - p10 < CONTRAST) return null
            val range = p90 - p10
            val modes = ByteArray(n) {
                when {
                    level[it] < p10 + range * 0.25f -> -1
                    level[it] > p90 - range * 0.25f -> 1
                    else -> 0
                }
            }
            // A short break between two stretches alike is part of them.
            val gapSteps = (GAP_MS / STEP_MS).toInt()
            var g = 0
            while (g < n) {
                var j = g
                while (j < n && modes[j] == modes[g]) j++
                if (modes[g] == 0.toByte() && g > 0 && j < n && j - g <= gapSteps && modes[g - 1] == modes[j]) for (k in g until j) modes[k] = modes[j]
                g = j
            }
            // Only long stretches count; short ones are the track as it is.
            val minSteps = (MIN_MS / STEP_MS).toInt()
            var i = 0
            while (i < n) {
                var j = i
                while (j < n && modes[j] == modes[i]) j++
                if (modes[i] != 0.toByte() && j - i < minSteps) for (k in i until j) modes[k] = 0
                i = j
            }
            // A track mostly "different" is not: that is how it is.
            for (m in byteArrayOf(-1, 1)) if (modes.count { it == m } > n * 0.6) for (k in 0 until n) if (modes[k] == m) modes[k] = 0
            return if (modes.any { it != 0.toByte() }) Sections(modes) else null
        }
    }
}
