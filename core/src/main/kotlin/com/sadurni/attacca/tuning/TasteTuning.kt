package com.sadurni.attacca.tuning

import com.sadurni.attacca.chain.ChainTaste
import com.sadurni.attacca.chain.EdgeDescriptor
import com.sadurni.attacca.chain.EdgeFeatures
import com.sadurni.attacca.chain.TasteGroup
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.random.Random

/** A track of the library as the chaining guide sees it: how it starts and ends, who and which album. */
class TasteTrack(val id: Int, val head: EdgeDescriptor?, val tail: EdgeDescriptor?, val artist: String, val album: String)

/**
 * One question of the chaining guide: after the end of [from], which start follows better,
 * [first] or [second]? They were picked to differ in [group] one way and in the rest the other.
 */
class TasteRound(
    val group: TasteGroup,
    val from: TasteTrack,
    val first: TasteTrack,
    val second: TasteTrack,
    val firstParts: FloatArray,
    val secondParts: FloatArray,
)

/** An answer: which of a round's two starts followed better ([FIRST], [SECOND] or [ALIKE]). */
class TasteAnswer(val firstParts: FloatArray, val secondParts: FloatArray, val choice: Int) {
    companion object {
        const val FIRST = 0
        const val SECOND = 1
        const val ALIKE = -1
    }
}

/**
 * The chaining guide: songs of the library heard one into another, two possible next songs each
 * time. One of them matches the end better in one thing (the beat, the sound, the energy, the key)
 * and the other in the rest; what the listener picks moves the weights of [ChainTaste].
 */
object TasteTuning {
    /** Questions in a full run: each group twice. */
    const val ROUNDS = 8

    /** Answers needed before the weights may be saved. */
    const val MIN_ANSWERS = 4

    /** How many possible next songs are compared for each question. */
    private const val CANDIDATES = 400

    /** A pair is a question only when its starts differ this much in the group and the other way in the rest. */
    private const val GROUP_GAP = 0.15f
    private const val REST_GAP = 0.04f

    /** How sharply a difference in score decides a choice (a 0.1 better link is chosen ~4 in 5). */
    private const val SHARPNESS = 14.0

    /** How strongly the weights stay near where they were (each answer pulls them away). */
    private const val PRIOR = 0.3

    /** A group's weight moves at most this many times up or down in one run. */
    private const val MAX_STEP = 3.0

    fun group(round: Int): TasteGroup = TasteGroup.entries[round % TasteGroup.entries.size]

    /** The mean of [group]'s parts. */
    private fun inGroup(parts: FloatArray, group: TasteGroup): Float = group.parts.map { parts[it] }.average().toFloat()

    /** The score of the parts outside [group], weighted as [taste] weighs them. */
    private fun rest(parts: FloatArray, group: TasteGroup, taste: ChainTaste): Float {
        val w = taste.toArray()
        var s = 0f
        var total = 0f
        for (i in w.indices) if (i !in group.parts) {
            s += w[i] * parts[i]
            total += w[i]
        }
        return if (total > 0f) s / total else 0f
    }

    /**
     * A question about [group] from [tracks] (those with edges measured with timbre), or null when
     * the library has no pair that tells it apart. Next songs are of another album and, when there
     * are, of another artist than the song they follow.
     */
    fun pick(tracks: List<TasteTrack>, group: TasteGroup, taste: ChainTaste, random: Random = Random.Default, avoid: Set<Int> = emptySet()): TasteRound? {
        val ends = tracks.filter { it.tail?.timbre != null && it.id !in avoid }
        val starts = tracks.filter { it.head?.timbre != null }
        if (ends.isEmpty() || starts.size < 3) return null
        repeat(12) {
            val from = ends.random(random)
            val others = starts.filter { it.id != from.id && (from.album.isBlank() || it.album != from.album) }
            val pool = (others.filter { !it.artist.equals(from.artist, true) }.takeIf { it.size >= 20 } ?: others)
                .shuffled(random).take(CANDIDATES)
            if (pool.size < 2) return@repeat
            val scored = pool.mapNotNull { t ->
                val parts = EdgeFeatures.parts(from.tail!!, t.head!!) ?: return@mapNotNull null
                Triple(t, parts, inGroup(parts, group) - rest(parts, group, taste))
            }
            if (scored.size < 2) return@repeat
            val first = scored.maxBy { it.third }
            val second = scored.minBy { it.third }
            val groupGap = inGroup(first.second, group) - inGroup(second.second, group)
            val restGap = rest(second.second, group, taste) - rest(first.second, group, taste)
            if (first.first !== second.first && groupGap >= GROUP_GAP && restGap >= REST_GAP) {
                // Which comes first is left to chance, so the order tells nothing.
                return if (random.nextBoolean()) TasteRound(group, from, first.first, second.first, first.second, second.second)
                else TasteRound(group, from, second.first, first.first, second.second, first.second)
            }
        }
        return null
    }

    /**
     * The weights after [answers], starting from [base]: each group's weight scaled so that the
     * chosen starts score better than the others (a choice model with a pull back to [base]).
     */
    fun learn(base: ChainTaste, answers: List<TasteAnswer>): ChainTaste {
        val decided = answers.filter { it.choice != TasteAnswer.ALIKE }
        if (decided.isEmpty()) return base.normalized()
        val w0 = base.normalized().toArray()
        val groups = TasteGroup.entries
        val groupOf = IntArray(ChainTaste.PARTS) { i -> groups.indexOfFirst { i in it.parts } }
        val u = DoubleArray(groups.size)
        fun weights(u: DoubleArray) = DoubleArray(w0.size) { i -> w0[i] * exp(u[groupOf[i]]) }
        fun objective(u: DoubleArray): Double {
            val w = weights(u)
            val total = w.sum()
            var sum = 0.0
            for (a in decided) {
                var d = 0.0
                for (i in w.indices) d += w[i] * (a.firstParts[i] - a.secondParts[i])
                d /= total
                val sign = if (a.choice == TasteAnswer.FIRST) 1.0 else -1.0
                sum += -ln(1.0 + exp(-SHARPNESS * sign * d))
            }
            return sum - PRIOR * u.sumOf { it * it }
        }
        val limit = ln(MAX_STEP)
        val h = 1e-4
        var step = 0.5
        var best = objective(u)
        repeat(400) {
            val grad = DoubleArray(u.size) { g ->
                val up = u.copyOf().also { it[g] += h }
                val down = u.copyOf().also { it[g] -= h }
                (objective(up) - objective(down)) / (2 * h)
            }
            val next = DoubleArray(u.size) { g -> (u[g] + step * grad[g]).coerceIn(-limit, limit) }
            val value = objective(next)
            if (value > best) {
                best = value
                next.copyInto(u)
                step *= 1.1
            } else {
                step *= 0.5
                if (step < 1e-6) return@repeat
            }
        }
        val w = weights(u)
        return ChainTaste.of(FloatArray(w.size) { (w[it] / w.sum()).toFloat() })
    }

    /** How far [a] and [b] are apart (the largest change of a group's share). */
    fun change(a: ChainTaste, b: ChainTaste): Float = TasteGroup.entries.maxOf { abs(a.share(it) - b.share(it)) }
}
