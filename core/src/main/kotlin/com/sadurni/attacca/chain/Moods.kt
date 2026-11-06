package com.sadurni.attacca.chain

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/** Five broad moods, placed by energy (calm … intense) and positivity (dark … bright). */
enum class Mood { ENERGETIC, CHEERFUL, CALM, MELANCHOLIC, TENSE }

/** One trait that sets a sound family apart, high or low (e.g. fast / slow). */
enum class Trait { TEMPO, BRIGHTNESS, PERCUSSION, BASS, DISTORTION, DYNAMICS, VOICE, LOUDNESS }

/** A sound family's name: its most distinctive traits, each high (true) or low. */
data class FamilyName(val traits: List<Pair<Trait, Boolean>>)

/**
 * Moods, energy, positivity and sound families of a collection. Everything is relative to the
 * collection itself (a track is "fast" or "bright" compared with the rest), so it adapts to
 * whatever music it is given; values are by track, in the order of the input.
 */
class Classification(
    /** Each track's mood: the one it is nearest to. */
    val moods: List<Mood>,
    /**
     * A second mood for a track that sits between two (null when one is clear): "between Calm and
     * Melancholic". It belongs to both when filtering by mood.
     */
    val secondMoods: List<Mood?>,
    /** Energy as a percentile in the collection (0 … 1). */
    val energy: FloatArray,
    /** Positivity (minor, dark … major, bright) as a percentile (0 … 1). */
    val positivity: FloatArray,
    /** Voice as a percentile (0 … 1); rough. */
    val voice: FloatArray,
    /** Sound family of each track, an index into [familyNames]. */
    val families: IntArray,
    val familyNames: List<FamilyName>,
    /** Standardised description of each track, for distances between tracks. */
    val vectors: Array<FloatArray>,
) {
    /** Whether track [i]'s mood, or its second one, is among [moods]. */
    fun hasMood(i: Int, wanted: Set<Mood>): Boolean = moods[i] in wanted || secondMoods[i]?.let { it in wanted } == true

    fun distance(a: Int, b: Int): Float {
        val va = vectors[a]
        val vb = vectors[b]
        var s = 0f
        for (i in va.indices) {
            val d = va[i] - vb[i]
            s += d * d
        }
        return sqrt(s)
    }
}

object Moods {

    private val FAMILY_TRAITS = listOf(
        Trait.TEMPO, Trait.BRIGHTNESS, Trait.PERCUSSION, Trait.BASS,
        Trait.DISTORTION, Trait.DYNAMICS, Trait.VOICE, Trait.LOUDNESS,
    )

    /** Mood prototypes on (energy, positivity), both standardised (placed on the reference songs). */
    private val PROTOTYPES = mapOf(
        Mood.ENERGETIC to (1.0f to 0.7f),
        Mood.CHEERFUL to (0.0f to 1.0f),
        Mood.CALM to (-1.0f to 0.2f),
        Mood.MELANCHOLIC to (-0.8f to -0.8f),
        Mood.TENSE to (0.9f to -0.4f),
    )

    /** Two moods this close (in standard deviations on the two scales) make a track "between" them. */
    private const val DOUBT_MARGIN = 0.25f

    /** A correction reaches the tracks this many typical nearest-neighbour distances away. */
    private const val PROPAGATION_RADIUS = 1.0f
    private const val NEAREST_SAMPLE = 300

    // Weights of the mood scales, on standard scores. With a sound profile, from the sound network's
    // mood classes; fitted on 135 reference songs whose energy and positivity are clear by ear
    // (leave-one-artist-out: energy 0.89 right, positivity 0.69, against 0.72 and 0.47 before; the
    // mood fits 68 % of them against 44 %; adding the track's own measures made both worse). Without
    // one, from the track's own measures.
    private const val E_EXCITING = 0.3f
    private const val E_ANGRY = 0.25f
    private const val E_SAD = 0.25f
    private const val E_TENDER = 0.25f
    private const val E_HAPPY = 0.05f
    private const val V_HAPPY = 0.3f
    private const val V_ANGRY = 0.25f
    private const val V_SAD = 0.25f
    private const val V_EXCITING = 0.15f
    private const val OWN_E_NOISE = 0.35f
    private const val OWN_E_BRIGHT = 0.35f
    private const val OWN_E_BASS = 0.1f
    private const val OWN_E_DYNAMICS = 0.1f
    private const val OWN_V_PULSE = 0.3f
    private const val OWN_V_VOICE = 0.3f
    private const val OWN_V_PERCUSSION = 0.25f
    private const val OWN_V_BRIGHT = 0.15f

    /** A trait must stand out this much (standard deviations) to name a family. */
    private const val NAMING_Z = 0.35f
    private const val MAX_NAME_TRAITS = 3
    private const val TRACKS_PER_FAMILY = 30
    private const val MAX_FAMILIES = 8

    /** Directions of the sound network's embedding kept for families and likeness. */
    const val SOUND_COMPONENTS = 32
    private const val TIMBRE_WEIGHT = 0.7f
    private const val MAX_SOUND_FAMILIES = 16

    /**
     * Moods, energy and positivity from [features] and [sounds]; sound families and the vectors
     * for how alike tracks are from [sounds] when most tracks have one (see [SoundProfile]), else
     * from the features' traits (as before sound profiles, and for a collection measured without
     * them). [corrections]: the moods the user gave (by track index), spreading to the tracks that
     * sound like those of [spreadFrom].
     */
    fun classify(
        features: List<TrackFeatures>,
        maxFamilies: Int = MAX_FAMILIES,
        sounds: List<SoundProfile?>? = null,
        corrections: Map<Int, Mood> = emptyMap(),
        spreadFrom: Set<Int> = corrections.keys,
    ): Classification {
        val n = features.size
        fun trait(f: TrackFeatures, t: Trait): Float = when (t) {
            Trait.TEMPO -> ln(f.tempoBpm.coerceAtLeast(30f))
            Trait.BRIGHTNESS -> f.brightness
            Trait.PERCUSSION -> f.percussiveness
            Trait.BASS -> f.bassDb
            Trait.DISTORTION -> f.noisiness
            Trait.DYNAMICS -> f.dynamicsDb
            Trait.VOICE -> f.voice
            Trait.LOUDNESS -> f.loudnessDb
        }
        val z = FAMILY_TRAITS.map { t -> standardise(FloatArray(n) { trait(features[it], t) }) }
        fun zOf(t: Trait, i: Int) = z[FAMILY_TRAITS.indexOf(t)][i]
        val mood = moodScale(features, sounds, ::zOf)
        val energyRaw = mood.energy
        val positivityRaw = mood.positivity

        val familyVectors = Array(n) { i -> FloatArray(FAMILY_TRAITS.size) { z[it][i] } }
        val withSound = sounds?.count { it != null } ?: 0
        if (sounds != null && withSound >= 4 && withSound * 2 >= n) {
            val (assignment, vectors) = soundFamilies(sounds, familyVectors)
            val (moods, secondMoods) = correct(mood.moods, mood.secondMoods, vectors, corrections, spreadFrom)
            return Classification(
                moods = moods,
                secondMoods = secondMoods,
                energy = percentiles(energyRaw),
                positivity = percentiles(positivityRaw),
                voice = percentiles(FloatArray(n) { features[it].voice }),
                families = assignment,
                familyNames = nameFamilies(traitCentroids(familyVectors, assignment)),
                vectors = vectors,
            )
        }
        val k = when {
            n < 4 -> 1
            else -> (n / TRACKS_PER_FAMILY).coerceIn(2, maxFamilies.coerceAtLeast(2))
        }
        val (assignment, centroids) = kMeans(familyVectors, k)
        val names = nameFamilies(centroids)
        val vectors = Array(n) { i -> familyVectors[i] + floatArrayOf(mood.energyZ[i], mood.positivityZ[i]) }
        val (moods, secondMoods) = correct(mood.moods, mood.secondMoods, vectors, corrections, spreadFrom)

        return Classification(
            moods = moods,
            secondMoods = secondMoods,
            energy = percentiles(energyRaw),
            positivity = percentiles(positivityRaw),
            voice = percentiles(FloatArray(n) { features[it].voice }),
            families = assignment,
            familyNames = names,
            vectors = vectors,
        )
    }

    /** Where each track sits on the two mood scales, and the moods that follow. */
    private class MoodScale(
        val energy: FloatArray,
        val positivity: FloatArray,
        val energyZ: FloatArray,
        val positivityZ: FloatArray,
        val moods: List<Mood>,
        val secondMoods: List<Mood?>,
    )

    /**
     * Energy and positivity of each track, and its mood: the nearest of [PROTOTYPES], with the
     * second nearest too when the two are about as near ([DOUBT_MARGIN]).
     *
     * Both come from the sound network's mood classes ([MoodHead]): energy is exciting and angry
     * music against sad, tender and merely happy music; positivity is happy and exciting music
     * against angry and sad music. Never how loud a track was mastered, nor minor or major (which says
     * little about real music). A track without a sound profile (or without the network) goes by
     * its own measures: roughness, brightness and bass for energy; pulse, voice and brightness
     * against percussion for positivity.
     */
    private fun moodScale(features: List<TrackFeatures>, sounds: List<SoundProfile?>?, zOf: (Trait, Int) -> Float): MoodScale {
        val n = features.size
        val head = if (sounds != null && sounds.any { it != null }) MoodHead.shared() else null
        val logits = Array(MoodHead.CLASSES.size) { FloatArray(n) }
        val known = BooleanArray(n)
        if (head != null) {
            for (i in 0 until n) {
                val sound = sounds!![i] ?: continue
                val l = head.logits(sound.embedding)
                for (c in l.indices) logits[c][i] = l[c]
                known[i] = true
            }
        }
        // Standard scores over the tracks that have them; the others sit in the middle (0).
        val net = Array(logits.size) { c ->
            val values = FloatArray(known.count { it })
            var j = 0
            for (i in 0 until n) if (known[i]) values[j++] = logits[c][i]
            val scores = standardise(values)
            j = 0
            FloatArray(n) { i -> if (known[i]) scores[j++] else 0f }
        }
        val pulseZ = standardise(FloatArray(n) { features[it].pulseClarity })
        val energyRaw = FloatArray(n) { i ->
            if (known[i]) {
                E_EXCITING * net[MoodHead.EXCITING][i] - E_SAD * net[MoodHead.SAD][i] - E_TENDER * net[MoodHead.TENDER][i] -
                    E_HAPPY * net[MoodHead.HAPPY][i] + E_ANGRY * net[MoodHead.ANGRY][i]
            } else {
                OWN_E_NOISE * zOf(Trait.DISTORTION, i) + OWN_E_BRIGHT * zOf(Trait.BRIGHTNESS, i) + OWN_E_BASS * zOf(Trait.BASS, i) -
                    OWN_E_DYNAMICS * zOf(Trait.DYNAMICS, i)
            }
        }
        val positivityRaw = FloatArray(n) { i ->
            if (known[i]) {
                V_HAPPY * net[MoodHead.HAPPY][i] - V_ANGRY * net[MoodHead.ANGRY][i] - V_SAD * net[MoodHead.SAD][i] +
                    V_EXCITING * net[MoodHead.EXCITING][i]
            } else {
                OWN_V_PULSE * pulseZ[i] + OWN_V_VOICE * zOf(Trait.VOICE, i) - OWN_V_PERCUSSION * zOf(Trait.PERCUSSION, i) +
                    OWN_V_BRIGHT * zOf(Trait.BRIGHTNESS, i)
            }
        }
        val energyZ = standardise(energyRaw)
        val positivityZ = standardise(positivityRaw)
        val moods = ArrayList<Mood>(n)
        val second = ArrayList<Mood?>(n)
        for (i in 0 until n) {
            val ranked = PROTOTYPES.entries.map { (mood, p) ->
                val de = energyZ[i] - p.first
                val dp = positivityZ[i] - p.second
                mood to sqrt(de * de + dp * dp)
            }.sortedBy { it.second }
            moods.add(ranked[0].first)
            second.add(ranked[1].first.takeIf { ranked[1].second - ranked[0].second < DOUBT_MARGIN })
        }
        return MoodScale(energyRaw, positivityRaw, energyZ, positivityZ, moods, second)
    }

    /**
     * The user's own moods: each corrected track ([corrections], by index) takes its mood, clear;
     * the tracks that sound most like one of [spreadFrom] ([vectors] nearer than
     * [PROPAGATION_RADIUS] times the usual distance to a nearest neighbour) take it too, keeping
     * their own as the second mood.
     */
    private fun correct(
        moods: List<Mood>,
        second: List<Mood?>,
        vectors: Array<FloatArray>,
        corrections: Map<Int, Mood>,
        spreadFrom: Set<Int>,
    ): Pair<List<Mood>, List<Mood?>> {
        if (corrections.isEmpty()) return moods to second
        val n = moods.size
        val outMoods = moods.toMutableList()
        val outSecond = second.toMutableList()
        val corrected = corrections.keys.filter { it in 0 until n }
        val spreading = spreadFrom.filter { it in 0 until n && it in corrections }
        val radius = if (spreading.isEmpty()) 0f else typicalNearest(vectors) * PROPAGATION_RADIUS
        if (radius > 0f) {
            for (i in 0 until n) {
                if (i in corrections) continue
                var best = -1
                var bestDistance = radius * radius
                for (c in spreading) {
                    val d = squared(vectors[i], vectors[c])
                    if (d < bestDistance) {
                        bestDistance = d
                        best = c
                    }
                }
                if (best < 0) continue
                val mood = corrections.getValue(best)
                if (mood != outMoods[i]) {
                    outSecond[i] = outMoods[i]
                    outMoods[i] = mood
                }
            }
        }
        for (c in corrected) {
            outMoods[c] = corrections.getValue(c)
            outSecond[c] = null
        }
        return outMoods to outSecond
    }

    /** The median distance from a track to its nearest neighbour, over a sample of tracks. */
    private fun typicalNearest(vectors: Array<FloatArray>): Float {
        val n = vectors.size
        if (n < 2) return 0f
        val random = Random(11)
        val sample = if (n <= NEAREST_SAMPLE) (0 until n).toList() else List(NEAREST_SAMPLE) { random.nextInt(n) }
        val nearest = sample.map { i ->
            var best = Float.MAX_VALUE
            for (j in 0 until n) if (j != i) best = minOf(best, squared(vectors[i], vectors[j]))
            sqrt(best)
        }.sorted()
        return nearest[nearest.size / 2]
    }

    /**
     * Families and similarity vectors from the sound profiles: the network's embedding brought
     * down to its main [SOUND_COMPONENTS] directions (scaled to unit spread on average) next to
     * the timbre (robust standard scores, weighed [TIMBRE_WEIGHT]), grouped by k-means into more
     * families the larger the collection. A track without a profile joins the family whose
     * traits ([traits]) are nearest and takes that family's centre as its vector.
     */
    private fun soundFamilies(sounds: List<SoundProfile?>, traits: Array<FloatArray>): Pair<IntArray, Array<FloatArray>> {
        val n = sounds.size
        val known = sounds.indices.filter { sounds[it] != null }
        // The embeddings are made as they are read: never all of them at once.
        val embeddings = object : AbstractList<FloatArray>() {
            override val size get() = known.size
            override fun get(index: Int) = sounds[known[index]]!!.embedding
        }
        val principal = Principal.fit(embeddings, SOUND_COMPONENTS)
        val projected = known.map { principal.project(sounds[it]!!.embedding) }
        val spread = FloatArray(SOUND_COMPONENTS) { c -> sqrt(projected.map { it[c] * it[c] }.average()).toFloat() }.average().toFloat().coerceAtLeast(1e-6f)
        val timbre = Array(SoundProfile.TIMBRE_SIZE) { d -> standardise(FloatArray(known.size) { sounds[known[it]]!!.timbre[d] }) }
        val knownVectors = Array(known.size) { j ->
            FloatArray(SOUND_COMPONENTS) { projected[j][it] / spread } + FloatArray(SoundProfile.TIMBRE_SIZE) { timbre[it][j] * TIMBRE_WEIGHT }
        }
        val k = sqrt(known.size / 8.0).roundToInt().coerceIn(if (known.size < 4) 1 else 2, MAX_SOUND_FAMILIES)
        val (knownFamilies, centroids) = kMeans(knownVectors, k)
        val families = IntArray(n)
        val vectors = arrayOfNulls<FloatArray>(n)
        for ((j, i) in known.withIndex()) {
            families[i] = knownFamilies[j]
            vectors[i] = knownVectors[j]
        }
        val unknown = sounds.indices.filter { sounds[it] == null }
        if (unknown.isNotEmpty()) {
            val traitCentres = traitCentroids(Array(known.size) { traits[known[it]] }, knownFamilies)
            for (i in unknown) {
                val family = traitCentres.indices.minBy { squared(traits[i], traitCentres[it]) }
                families[i] = family
                vectors[i] = centroids[family].copyOf()
            }
        }
        return families to Array(n) { vectors[it]!! }
    }

    /** The mean traits of each family's tracks (families numbered from 0). */
    private fun traitCentroids(traits: Array<FloatArray>, families: IntArray): List<FloatArray> {
        val count = (families.maxOrNull() ?: -1) + 1
        return (0 until count).map { f ->
            val members = traits.indices.filter { families[it] == f }.map { traits[it] }
            if (members.isEmpty()) FloatArray(traits.firstOrNull()?.size ?: 0) else mean(members, members[0].size)
        }
    }

    /** Robust standard scores (median and median absolute deviation), clamped to ±3. */
    fun standardise(values: FloatArray): FloatArray {
        if (values.isEmpty()) return values
        val sorted = values.sorted()
        val median = sorted[sorted.size / 2]
        val deviations = values.map { abs(it - median) }.sorted()
        val mad = deviations[deviations.size / 2] * 1.4826f
        val scale = if (mad > 1e-6f) mad else (values.maxOrNull()!! - values.minOrNull()!!).coerceAtLeast(1e-6f)
        return FloatArray(values.size) { ((values[it] - median) / scale).coerceIn(-3f, 3f) }
    }

    fun percentiles(values: FloatArray): FloatArray {
        val n = values.size
        if (n <= 1) return FloatArray(n) { 0.5f }
        val order = values.indices.sortedBy { values[it] }
        val result = FloatArray(n)
        for ((rank, i) in order.withIndex()) result[i] = rank.toFloat() / (n - 1)
        return result
    }

    /** k-means++ with a fixed seed, so the same collection always gives the same families. */
    fun kMeans(points: Array<FloatArray>, k: Int, iterations: Int = 40): Pair<IntArray, List<FloatArray>> {
        val n = points.size
        if (n == 0) return IntArray(0) to emptyList()
        if (k <= 1) return IntArray(n) to listOf(mean(points.toList(), points[0].size))
        val random = Random(7)
        val centroids = ArrayList<FloatArray>()
        centroids.add(points[random.nextInt(n)].copyOf())
        val nearest = FloatArray(n) { Float.MAX_VALUE }
        while (centroids.size < k) {
            val last = centroids.last()
            var total = 0.0
            for (i in 0 until n) {
                nearest[i] = minOf(nearest[i], squared(points[i], last))
                total += nearest[i]
            }
            if (total <= 0) break
            var pick = random.nextDouble() * total
            var chosen = n - 1
            for (i in 0 until n) {
                pick -= nearest[i]
                if (pick <= 0) {
                    chosen = i
                    break
                }
            }
            centroids.add(points[chosen].copyOf())
        }
        val assignment = IntArray(n)
        for (iteration in 0 until iterations) {
            var changed = false
            for (i in 0 until n) {
                val best = centroids.indices.minBy { squared(points[i], centroids[it]) }
                if (best != assignment[i]) {
                    assignment[i] = best
                    changed = true
                }
            }
            for (c in centroids.indices) {
                val members = (0 until n).filter { assignment[it] == c }.map { points[it] }
                if (members.isNotEmpty()) centroids[c] = mean(members, points[0].size)
            }
            if (!changed && iteration > 0) break
        }
        // Drop empty families and renumber the rest.
        val used = assignment.distinct().sorted()
        val remap = used.withIndex().associate { (index, old) -> old to index }
        return IntArray(n) { remap.getValue(assignment[it]) } to used.map { centroids[it] }
    }

    private fun nameFamilies(centroids: List<FloatArray>): List<FamilyName> {
        val names = centroids.map { c ->
            val traits = FAMILY_TRAITS.indices
                .filter { abs(c[it]) >= NAMING_Z }
                .sortedByDescending { abs(c[it]) }
                .take(MAX_NAME_TRAITS)
                .map { FAMILY_TRAITS[it] to (c[it] > 0) }
            FamilyName(traits)
        }
        return names
    }

    private fun mean(points: List<FloatArray>, size: Int): FloatArray {
        val m = FloatArray(size)
        for (p in points) for (i in 0 until size) m[i] += p[i]
        for (i in 0 until size) m[i] /= points.size
        return m
    }

    private fun squared(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) {
            val d = a[i] - b[i]
            s += d * d
        }
        return s
    }
}
