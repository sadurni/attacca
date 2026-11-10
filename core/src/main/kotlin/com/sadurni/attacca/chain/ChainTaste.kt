package com.sadurni.attacca.chain

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * How much each part of a link counts when chaining (see [EdgeFeatures.similarity]): the tempo,
 * the timbre, the spectrum's shape, the activity, the key and the notes. The defaults were learnt
 * from music cut where it flows on; the chaining guide moves them to the listener's taste. The
 * weights are relative (any scale); [normalized] makes them add up to one.
 */
data class ChainTaste(
    val tempo: Float = DEFAULT_TEMPO,
    val timbre: Float = DEFAULT_TIMBRE,
    val bands: Float = DEFAULT_BANDS,
    val activity: Float = DEFAULT_ACTIVITY,
    val key: Float = DEFAULT_KEY,
    val harmony: Float = DEFAULT_HARMONY,
) {
    /** In the order of [EdgeFeatures.parts]. */
    fun toArray(): FloatArray = floatArrayOf(tempo, timbre, bands, activity, key, harmony)

    fun normalized(): ChainTaste {
        val sum = toArray().sum()
        return if (sum <= 0f) ChainTaste() else of(toArray().map { it / sum }.toFloatArray())
    }

    /** The score of a link from its [parts] (each 0 … 1). */
    fun score(parts: FloatArray): Float {
        val w = toArray()
        var s = 0f
        var total = 0f
        for (i in w.indices) {
            s += w[i] * parts[i]
            total += w[i]
        }
        return if (total > 0f) s / total else 0f
    }

    /** The weight of [group] (its parts together), of the whole. */
    fun share(group: TasteGroup): Float {
        val n = normalized().toArray()
        return group.parts.sumOf { n[it].toDouble() }.toFloat()
    }

    val isDefault: Boolean
        get() = normalized().toArray().zip(DEFAULT.toArray()).all { (a, b) -> abs(a - b) < 0.005f }

    /** "0.330,0.290,…" for settings files. */
    fun encode(): String = normalized().toArray().joinToString(",") { ((it * 1000).roundToInt() / 1000f).toString() }

    /** As the integer weights of the players (thousandths, adding up to about 1000). */
    fun thousandths(): IntArray = normalized().toArray().map { (it * 1000).roundToInt().coerceIn(0, 1000) }.toIntArray()

    companion object {
        const val DEFAULT_TEMPO = 0.33f
        const val DEFAULT_TIMBRE = 0.29f
        const val DEFAULT_BANDS = 0.16f
        const val DEFAULT_ACTIVITY = 0.12f
        const val DEFAULT_KEY = 0.05f
        const val DEFAULT_HARMONY = 0.04f
        const val PARTS = 6

        val DEFAULT = ChainTaste()

        fun of(w: FloatArray): ChainTaste = ChainTaste(w[0], w[1], w[2], w[3], w[4], w[5])

        /** Back from [encode]; the default for anything else. */
        fun decode(text: String?): ChainTaste {
            val values = text?.split(',')?.mapNotNull { it.trim().toFloatOrNull() } ?: return DEFAULT
            if (values.size != PARTS || values.any { it < 0f || it.isNaN() } || values.sum() <= 0f) return DEFAULT
            return of(values.toFloatArray()).normalized()
        }
    }
}

/** What a listener hears of a link, each made of parts of [ChainTaste]. */
enum class TasteGroup(val parts: IntArray) {
    /** The beat: tempo (half or double as equal). */
    TEMPO(intArrayOf(0)),

    /** The sound: timbre and the spectrum's shape. */
    SOUND(intArrayOf(1, 2)),

    /** How much is going on: the activity. */
    ENERGY(intArrayOf(3)),

    /** The key and the notes. */
    KEY(intArrayOf(4, 5)),
}
