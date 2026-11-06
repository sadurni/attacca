package com.sadurni.attacca.chain

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** A track of the collection with what list generation needs. */
class Candidate(
    val id: String,
    val artist: String,
    /** Genre from the tags (not from the sound). */
    val genre: String,
    val year: Int,
    val features: TrackFeatures,
    val edges: TrackEdges?,
    /** Length in milliseconds; 0 when unknown (passes any duration filter). */
    val durationMs: Long = 0,
    /** What it sounds like for families and look-alikes; null when not measured (see [SoundProfile]). */
    val sound: SoundProfile? = null,
)

enum class VoiceFilter { ANY, VOICE, INSTRUMENTAL }

/** Filters for generated lists; null means "any". Energy and positivity are percentiles 0 … 1. */
data class ChainFilters(
    val moods: Set<Mood>? = null,
    val families: Set<Int>? = null,
    val artist: String? = null,
    val genre: String? = null,
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
    val bpmMin: Int? = null,
    val bpmMax: Int? = null,
    /** Track length range, seconds. */
    val durationMinS: Int? = null,
    val durationMaxS: Int? = null,
    val energyMin: Float? = null,
    val energyMax: Float? = null,
    val positivityMin: Float? = null,
    val positivityMax: Float? = null,
    val voice: VoiceFilter = VoiceFilter.ANY,
    /** Only tracks the inclusion list allows ("only included"); null takes every track. */
    val inclusion: com.sadurni.attacca.library.Inclusion? = null,
    /** Tracks left out whatever else they match (e.g. those played lately); null leaves none out. */
    val excluded: Set<String>? = null,
)

/** A generated list: its mood and family and the chained track ids. */
class GeneratedList(val mood: Mood, val family: Int, val ids: List<String>)

/**
 * Builds chained lists from a classified collection: one per mood and sound family, or the
 * tracks closest to a seed track.
 */
class ListGenerator(
    val candidates: List<Candidate>,
    val classification: Classification = Moods.classify(candidates.map { it.features }, sounds = candidates.map { it.sound }),
) {
    init {
        require(classification.moods.size == candidates.size)
    }

    fun indexOf(id: String): Int = candidates.indexOfFirst { it.id == id }

    /** The indices of those of [ids] that are candidates. */
    fun indicesOf(ids: Collection<String>): List<Int> {
        val wanted = ids.toHashSet()
        return candidates.indices.filter { candidates[it].id in wanted }
    }

    fun moodOf(index: Int): Mood = classification.moods[index]

    /** The second mood of a track between two, else null. */
    fun secondMoodOf(index: Int): Mood? = classification.secondMoods[index]

    fun familyOf(index: Int): Int = classification.families[index]

    /** Whether track [i] passes [f]. */
    fun passes(i: Int, f: ChainFilters): Boolean {
        val c = candidates[i]
        val k = classification
        if (f.moods != null && !k.hasMood(i, f.moods)) return false
        if (f.families != null && k.families[i] !in f.families) return false
        if (!f.artist.isNullOrBlank() && !c.artist.contains(f.artist.trim(), ignoreCase = true)) return false
        if (!f.genre.isNullOrBlank() && !c.genre.contains(f.genre.trim(), ignoreCase = true)) return false
        if (f.yearFrom != null && (c.year == 0 || c.year < f.yearFrom)) return false
        if (f.yearTo != null && (c.year == 0 || c.year > f.yearTo)) return false
        if (f.bpmMin != null && c.features.tempoBpm < f.bpmMin) return false
        if (f.bpmMax != null && c.features.tempoBpm > f.bpmMax) return false
        if (f.durationMinS != null && c.durationMs > 0 && c.durationMs < f.durationMinS * 1000L) return false
        if (f.durationMaxS != null && c.durationMs > 0 && c.durationMs > f.durationMaxS * 1000L) return false
        if (f.energyMin != null && k.energy[i] < f.energyMin) return false
        if (f.energyMax != null && k.energy[i] > f.energyMax) return false
        if (f.positivityMin != null && k.positivity[i] < f.positivityMin) return false
        if (f.positivityMax != null && k.positivity[i] > f.positivityMax) return false
        if (f.inclusion != null && !f.inclusion.includes(c.id)) return false
        if (f.excluded != null && c.id in f.excluded) return false
        when (f.voice) {
            VoiceFilter.ANY -> Unit
            VoiceFilter.VOICE -> if (k.voice[i] < VOICE_PERCENTILE) return false
            VoiceFilter.INSTRUMENTAL -> if (k.voice[i] >= VOICE_PERCENTILE) return false
        }
        return true
    }

    /**
     * One chained list per mood and sound family among the tracks passing [filters], for groups of
     * at least [minTracks]; each starts with its most typical track and is cut to [count] if set.
     */
    fun general(
        filters: ChainFilters,
        artistSpacing: Int,
        count: Int? = null,
        minTracks: Int = MIN_GENERAL_TRACKS,
        isCancelled: () -> Boolean = { false },
    ): List<GeneratedList>? {
        val groups = candidates.indices.filter { passes(it, filters) }
            .groupBy { classification.moods[it] to classification.families[it] }
            .filterValues { it.size >= minTracks }
        val result = ArrayList<GeneratedList>()
        for ((key, members) in groups.entries.sortedWith(compareBy({ it.key.first.ordinal }, { it.key.second }))) {
            val first = mostTypical(members)
            val order = chain(members, first, artistSpacing, isCancelled) ?: return null
            result.add(GeneratedList(key.first, key.second, order.let { if (count != null) it.take(count) else it }))
        }
        return result
    }

    /**
     * The [count] tracks closest to the seed among those passing [filters] (and, if asked, of
     * its mood and family), chained starting with the seed. A margin of 10 % more tracks is
     * chained and then cut, so tracks the artist rule could not place do not pile up at the end.
     */
    fun fromSeed(
        seed: Int,
        filters: ChainFilters,
        sameMood: Boolean,
        sameFamily: Boolean,
        count: Int,
        artistSpacing: Int,
        isCancelled: () -> Boolean = { false },
    ): List<String>? {
        val f = filters.copy(
            moods = if (sameMood) setOf(classification.moods[seed]) else filters.moods,
            families = if (sameFamily) setOf(classification.families[seed]) else filters.families,
        )
        val closest = candidates.indices.filter { it != seed && passes(it, f) }
            .sortedBy { classification.distance(seed, it) }
        val pool = spread(closest, count - 1 + marginFor(count), count, artistSpacing, listOf(seed))
        val order = chain(listOf(seed) + pool, seed, artistSpacing, isCancelled) ?: return null
        return order.take(count)
    }

    /**
     * The [count] tracks closest to a group of [seeds] (an album, an artist), the seeds themselves
     * left out: each track is as close as its nearest seed. With [sameMood] / [sameFamily], only the
     * moods and families the seeds have. Chained from the closest track.
     */
    fun fromSeeds(
        seeds: List<Int>,
        filters: ChainFilters,
        sameMood: Boolean,
        sameFamily: Boolean,
        count: Int,
        artistSpacing: Int,
        isCancelled: () -> Boolean = { false },
    ): List<String>? {
        if (seeds.isEmpty()) return emptyList()
        val f = filters.copy(
            moods = if (sameMood) seeds.mapTo(HashSet()) { classification.moods[it] } else filters.moods,
            families = if (sameFamily) seeds.mapTo(HashSet()) { classification.families[it] } else filters.families,
        )
        val seedSet = seeds.toHashSet()
        val nearest = HashMap<Int, Float>()
        for (i in candidates.indices) {
            if (i in seedSet || !passes(i, f)) continue
            if (isCancelled()) return null
            var best = Float.MAX_VALUE
            for (s in seeds) {
                val d = classification.distance(s, i)
                if (d < best) best = d
            }
            nearest[i] = best
        }
        val pool = spread(nearest.keys.sortedBy { nearest[it] }, count + marginFor(count), count, artistSpacing)
        if (pool.isEmpty()) return emptyList()
        val order = chain(pool, pool.first(), artistSpacing, isCancelled) ?: return null
        return order.take(count)
    }

    /** Filters fitted around a seed: what they are and how many tracks they leave to choose from. */
    class SeedFit(val filters: ChainFilters, val available: Int)

    /**
     * Year, BPM, energy, positivity and voice set around the seed's own (the rest of [base] kept),
     * as tight as they can be while leaving enough tracks to choose from: windows widen step by
     * step and the first step leaving [IDEAL_FACTOR] times [count] is taken; failing that, the
     * first leaving [MIN_FACTOR] times; failing that, the widest. Energy and positivity come in
     * steps of 5 %.
     */
    fun fitToSeed(seed: Int, base: ChainFilters, sameMood: Boolean, sameFamily: Boolean, count: Int): SeedFit {
        val k = classification
        val c = candidates[seed]
        val voice = if (k.voice[seed] >= VOICE_PERCENTILE) VoiceFilter.VOICE else VoiceFilter.INSTRUMENTAL
        val kept = base.copy(
            moods = if (sameMood) setOf(k.moods[seed]) else base.moods,
            families = if (sameFamily) setOf(k.families[seed]) else base.families,
        )
        // A year window leaves out the tracks without a year: used only when most tracks have one.
        val years = candidates.mapNotNull { it.year.takeIf { y -> y > 0 } }
        val fitYears = c.year > 0 && years.size * 2 >= candidates.size
        val tempos = candidates.map { it.features.tempoBpm }
        val (minYear, maxYear) = (years.minOrNull() ?: 0) to (years.maxOrNull() ?: 0)
        val (minTempo, maxTempo) = (tempos.minOrNull() ?: 0f) to (tempos.maxOrNull() ?: 0f)

        fun around(step: Float, voiceFilter: VoiceFilter, withYears: Boolean): ChainFilters {
            val tempo = c.features.tempoBpm
            fun percent(value: Float, low: Boolean): Float? {
                val edge = if (low) floor((value - PERCENT_MARGIN * step) * 20) / 20 else ceil((value + PERCENT_MARGIN * step) * 20) / 20
                return edge.takeIf { if (low) it > 0f else it < 1f }
            }
            // Limits beyond what the collection holds filter nothing: left empty.
            val yearMargin = (YEAR_MARGIN * step).roundToInt()
            return kept.copy(
                yearFrom = (c.year - yearMargin).takeIf { withYears && it > minYear },
                yearTo = (c.year + yearMargin).takeIf { withYears && it < maxYear },
                bpmMin = floor(tempo * (1 - BPM_MARGIN * step)).toInt().takeIf { tempo > 0 && it > minTempo },
                bpmMax = ceil(tempo * (1 + BPM_MARGIN * step)).toInt().takeIf { tempo > 0 && it < maxTempo },
                energyMin = percent(k.energy[seed], true),
                energyMax = percent(k.energy[seed], false),
                positivityMin = percent(k.positivity[seed], true),
                positivityMax = percent(k.positivity[seed], false),
                voice = voiceFilter,
            )
        }
        fun available(f: ChainFilters) = candidates.indices.count { it != seed && passes(it, f) }
        // Tighter to wider (with years, then without); then the widest steps with any voice; last,
        // no limits around the seed at all.
        val tries = FIT_STEPS.map { around(it, voice, fitYears) } +
            (if (fitYears) FIT_STEPS.map { around(it, voice, false) } else emptyList()) +
            FIT_STEPS.takeLast(2).map { around(it, VoiceFilter.ANY, false) } +
            kept.copy(
                yearFrom = null, yearTo = null, bpmMin = null, bpmMax = null, energyMin = null, energyMax = null,
                positivityMin = null, positivityMax = null, voice = VoiceFilter.ANY,
            )
        val counted = tries.map { it to available(it) }
        val chosen = counted.firstOrNull { it.second >= count * IDEAL_FACTOR }
            ?: counted.firstOrNull { it.second >= count * MIN_FACTOR }
            ?: counted.last()
        // Back to the user's own mood and family choices (the switches set those).
        return SeedFit(chosen.first.copy(moods = base.moods, families = base.families), chosen.second)
    }

    /**
     * [take] of [closest] (nearest first) that a list of [count] can chain keeping [artistSpacing]:
     * no artist more often than the spacing allows (the closest tracks are often one album), and
     * tracks with their edges analysed before the rest (those can only go at the end, unchained).
     * Filled with what was left out when the collection has nothing else. [already] are in the list.
     */
    private fun spread(closest: List<Int>, take: Int, count: Int, artistSpacing: Int, already: List<Int> = emptyList()): List<Int> {
        val perArtist = if (artistSpacing <= 0) Int.MAX_VALUE else maxOf(1, (count + artistSpacing) / (artistSpacing + 1))
        val counts = HashMap<String, Int>()
        for (i in already) counts.merge(artistKeyOf(i), 1, Int::plus)
        val chosen = ArrayList<Int>(take)
        val left = ArrayList<Int>()
        for (analysed in listOf(true, false)) {
            for (i in closest) {
                if (chosen.size >= take) break
                if ((candidates[i].edges?.head != null) != analysed) continue
                val key = artistKeyOf(i)
                if (key.isNotEmpty() && (counts[key] ?: 0) >= perArtist) {
                    left.add(i)
                    continue
                }
                counts.merge(key, 1, Int::plus)
                chosen.add(i)
            }
        }
        for (i in left) {
            if (chosen.size >= take) break
            chosen.add(i)
        }
        // Nearest first again, as the rest of the generator expects.
        val rank = HashMap<Int, Int>(closest.size).apply { closest.forEachIndexed { at, i -> put(i, at) } }
        return chosen.sortedBy { rank[it] }
    }

    // region More kinds of list

    /** How the energy of an energy-curve list goes along it. */
    enum class EnergyShape { RISE_AND_FALL, WARM_UP, WIND_DOWN }

    /**
     * A way from [from] to [to]: [count] tracks with both ends, the ones in between those lying
     * nearest the way between the two in likeness, each a step further from the first and nearer
     * the last.
     */
    fun bridge(from: Int, to: Int, filters: ChainFilters, count: Int, artistSpacing: Int, isCancelled: () -> Boolean = { false }): List<String>? {
        val k = classification
        val direct = k.distance(from, to)
        val along = HashMap<Int, Float>()
        val detour = HashMap<Int, Float>()
        for (i in candidates.indices) {
            if (i == from || i == to || !passes(i, filters)) continue
            if (isCancelled()) return null
            val a = k.distance(from, i)
            val b = k.distance(i, to)
            detour[i] = a + b - direct
            along[i] = a / (a + b).coerceAtLeast(1e-6f)
        }
        val pool = spread(detour.keys.sortedBy { detour[it] }, (count - 2).coerceAtLeast(0), count, artistSpacing, listOf(from, to))
        // Along the way, each next track is the best link (end into start) among the few next ones
        // still to place, so it moves forward and chains too.
        val waiting = pool.sortedBy { along[it] }.toMutableList()
        val path = arrayListOf(from)
        while (waiting.isNotEmpty()) {
            if (isCancelled()) return null
            val last = path.last()
            val recent = path.takeLast(artistSpacing.coerceAtLeast(0)).mapTo(HashSet()) { artistKeyOf(it) }
            val window = waiting.take(BRIDGE_WINDOW)
            fun fresh(of: List<Int>) = of.filter { artistKeyOf(it).let { a -> a.isEmpty() || a !in recent } }
            // All of the next few by an artist heard just now: a little further on before giving way.
            val next = fresh(window).ifEmpty { fresh(waiting.take(BRIDGE_WINDOW * 3)).take(1) }.ifEmpty { window }
                .maxBy { link(last, it) }
            waiting.remove(next)
            path.add(next)
        }
        path.add(to)
        return path.map { candidates[it].id }
    }

    /**
     * [count] tracks whose energy follows [shape] (calm to lively and back, rising, or calming
     * down), each one also near the one before in likeness, no artist again within [artistSpacing].
     * Starts at [start] when given, else at a track of the right energy taken at random.
     */
    fun energyCurve(
        shape: EnergyShape,
        start: Int?,
        filters: ChainFilters,
        count: Int,
        artistSpacing: Int,
        random: kotlin.random.Random = kotlin.random.Random,
        isCancelled: () -> Boolean = { false },
    ): List<String>? {
        val k = classification
        val members = candidates.indices.filter { it == start || passes(it, filters) }
        if (members.size < 2) return members.map { candidates[it].id }
        fun target(p: Float): Float = when (shape) {
            EnergyShape.RISE_AND_FALL -> if (p < PEAK_AT) 0.15f + 0.8f * (p / PEAK_AT) else 0.95f - 0.8f * ((p - PEAK_AT) / (1 - PEAK_AT))
            EnergyShape.WARM_UP -> 0.1f + 0.85f * p
            EnergyShape.WIND_DOWN -> 0.9f - 0.85f * p
        }
        // Likeness and energy weighed alike: a typical distance between two tracks counts as much
        // as an energy off by ENERGY_STEP.
        val typical = List(200) { k.distance(members.random(random), members.random(random)) }.sorted()[100].coerceAtLeast(1e-6f)
        val n = minOf(count, members.size)
        val first = start ?: members.sortedBy { abs(k.energy[it] - target(0f)) }.take(20).random(random)
        val path = arrayListOf(first)
        val used = hashSetOf(first)
        while (path.size < n) {
            if (isCancelled()) return null
            val t = target(path.size / (n - 1f))
            val recent = path.takeLast(artistSpacing.coerceAtLeast(0)).mapTo(HashSet()) { artistKeyOf(it) }
            val last = path.last()
            var best = -1
            var bestCost = Float.MAX_VALUE
            for (pass in 0..1) {
                for (i in members) {
                    if (i in used) continue
                    // The artist rule gives way only when nothing else is left.
                    if (pass == 0 && artistKeyOf(i).let { it.isNotEmpty() && it in recent }) continue
                    // Energy on the curve, likeness, and how the last one's end leads into this start.
                    val cost = abs(k.energy[i] - t) / ENERGY_STEP + k.distance(last, i) / typical + (1f - link(last, i)) * LINK_WEIGHT
                    if (cost < bestCost) {
                        bestCost = cost
                        best = i
                    }
                }
                if (best >= 0) break
            }
            if (best < 0) break
            path.add(best)
            used.add(best)
        }
        return path.map { candidates[it].id }
    }

    /**
     * A sampler of the collection: the most typical tracks of every sound family (as many from
     * each as [count] allows, at least one), chained.
     */
    fun sampler(filters: ChainFilters, count: Int, artistSpacing: Int, isCancelled: () -> Boolean = { false }): List<String>? {
        val families = candidates.indices.filter { passes(it, filters) }.groupBy { classification.families[it] }
        if (families.isEmpty()) return emptyList()
        // In turns: the most typical of each family, then the next of each… until [count] (at least one each).
        val queues = families.values.map { byTypicality(it).toMutableList() }
        val pool = ArrayList<Int>()
        while (pool.size < maxOf(count, families.size) && queues.any { it.isNotEmpty() }) {
            for (q in queues) {
                if (pool.size >= maxOf(count, families.size)) break
                q.removeFirstOrNull()?.let(pool::add)
            }
        }
        if (pool.isEmpty()) return emptyList()
        return chain(pool, byTypicality(pool).first(), artistSpacing, isCancelled) ?: return null
    }

    /** [count] tracks as unlike [seed] as the collection has, chained among themselves. */
    fun contrast(seed: Int, filters: ChainFilters, count: Int, artistSpacing: Int, isCancelled: () -> Boolean = { false }): List<String>? {
        val farthest = candidates.indices.filter { it != seed && passes(it, filters) }
            .sortedByDescending { classification.distance(seed, it) }
        val pool = spread(farthest, count + marginFor(count), count, artistSpacing)
        if (pool.isEmpty()) return emptyList()
        val order = chain(pool, pool.first(), artistSpacing, isCancelled) ?: return null
        return order.take(count)
    }

    /**
     * Tracks like [seed], chained from it, lasting about [minutes] in all: cut where the next one
     * would go over, and the time left filled with the closest-fitting track not yet in.
     */
    fun forTime(seed: Int, filters: ChainFilters, minutes: Int, artistSpacing: Int, isCancelled: () -> Boolean = { false }): List<String>? {
        val target = minutes * 60_000L
        val known = candidates.map { it.durationMs }.filter { it > 0 }
        val average = if (known.isEmpty()) DEFAULT_LENGTH_MS else known.average().toLong().coerceAtLeast(30_000L)
        val asked = ((target / average.toFloat()) * TIME_MARGIN).toInt().coerceIn(3, MAX_TIME_TRACKS)
        val ids = fromSeed(seed, filters, sameMood = false, sameFamily = false, count = asked, artistSpacing = artistSpacing, isCancelled = isCancelled)
            ?: return null
        val length = HashMap<String, Long>().apply { for (c in candidates) put(c.id, c.durationMs.takeIf { it > 0 } ?: average) }
        val chosen = ArrayList<String>()
        var total = 0L
        for (id in ids) {
            val d = length[id] ?: average
            // One that would go over is passed by: a shorter one further on may still fit.
            if (total + d > target + TIME_SLACK_MS) continue
            chosen.add(id)
            total += d
            if (total >= target - TIME_SLACK_MS) break
        }
        val gap = target - total
        if (gap > TIME_SLACK_MS) {
            ids.filter { it !in chosen && (length[it] ?: average) <= gap + TIME_SLACK_MS }
                .minByOrNull { abs((length[it] ?: average) - gap) }
                ?.let {
                    chosen.add(it)
                    total += length[it] ?: average
                }
        }
        // Still short: one track (not the first) swapped for a longer one that closes the gap.
        val left = target - total
        if (left > TIME_SLACK_MS) {
            val spare = ids.filter { it !in chosen }
            swap@ for (at in chosen.indices.reversed()) {
                if (at == 0) break
                val wanted = (length[chosen[at]] ?: average) + left
                val other = spare.firstOrNull { abs((length[it] ?: average) - wanted) <= TIME_SLACK_MS } ?: continue@swap
                chosen[at] = other
                break
            }
        }
        return chosen
    }

    /**
     * The [groups] (albums: indices of their tracks) most like the [seeds], closest first: a group
     * is as close as its tracks are on average to their nearest seed.
     */
    fun closestGroups(seeds: List<Int>, groups: List<List<Int>>, isCancelled: () -> Boolean = { false }): List<Int>? {
        if (seeds.isEmpty()) return emptyList()
        val score = FloatArray(groups.size)
        for ((g, members) in groups.withIndex()) {
            if (isCancelled()) return null
            if (members.isEmpty()) {
                score[g] = Float.MAX_VALUE
                continue
            }
            var sum = 0f
            for (i in members) sum += seeds.minOf { classification.distance(it, i) }
            score[g] = sum / members.size
        }
        return groups.indices.sortedBy { score[it] }
    }

    /**
     * Whole albums (each a folder) most like the album [seeds] (its tracks), closest first (see
     * [closestGroups]), until they hold [count] tracks; each album's tracks in [trackOrder] (by path
     * unless given), the albums chained end into start with same-[albumArtist] albums kept apart.
     */
    fun albumsLike(
        seeds: List<Int>,
        filters: ChainFilters,
        count: Int,
        artistSpacing: Int,
        trackOrder: Comparator<Int>? = null,
        albumArtist: (Int) -> String = { candidates[it].artist },
        isCancelled: () -> Boolean = { false },
    ): List<String>? {
        if (seeds.isEmpty()) return null
        fun folder(i: Int): String = candidates[i].id.let { id -> id.substring(0, maxOf(0, maxOf(id.lastIndexOf('/'), id.lastIndexOf('\\')))) }
        val seedFolders = seeds.mapTo(HashSet()) { folder(it) }
        val folders = candidates.indices.filter { passes(it, filters) }.groupBy { folder(it) }
            .filterKeys { it !in seedFolders }.values.toList()
        val ranked = closestGroups(seeds, folders, isCancelled) ?: return null
        val albums = ArrayList<List<Int>>()
        var total = 0
        for (r in ranked) {
            if (total >= count) break
            val album = folders[r].sortedWith(trackOrder ?: compareBy { candidates[it].id })
            albums.add(album)
            total += album.size
        }
        if (albums.isEmpty()) return emptyList()
        val tracks = albums.map { a -> ChainTrack(candidates[a.first()].edges?.head, candidates[a.last()].edges?.tail, albumArtist(a.first())) }
        val order = ChainEngine.order(tracks, 0, artistSpacing, isCancelled) ?: return null
        return order.flatMap { albums[it] }.map { candidates[it].id }
    }

    /** How well track [a]'s end leads into [b]'s start (0 … 1; 0 without analysed edges). */
    private fun link(a: Int, b: Int): Float {
        val x = candidates[a]
        val y = candidates[b]
        return ChainEngine.score(ChainTrack(x.edges?.head, x.edges?.tail, x.artist), ChainTrack(y.edges?.head, y.edges?.tail, y.artist))
    }

    /** [members], most typical (nearest their middle) first. */
    private fun byTypicality(members: List<Int>): List<Int> {
        val size = classification.vectors[members[0]].size
        val centre = FloatArray(size)
        for (m in members) for (i in 0 until size) centre[i] += classification.vectors[m][i] / members.size
        return members.sortedBy { m ->
            var s = 0f
            for (i in 0 until size) {
                val d = classification.vectors[m][i] - centre[i]
                s += d * d
            }
            s
        }
    }

    // endregion

    private fun artistKeyOf(i: Int): String = candidates[i].artist.trim().lowercase()

    private fun chain(members: List<Int>, first: Int, artistSpacing: Int, isCancelled: () -> Boolean): List<String>? {
        val tracks = members.map { i ->
            val c = candidates[i]
            ChainTrack(c.edges?.head, c.edges?.tail, c.artist)
        }
        val order = ChainEngine.order(tracks, members.indexOf(first), artistSpacing, isCancelled) ?: return null
        return order.map { candidates[members[it]].id }
    }

    /** The member closest to the middle of the group. */
    private fun mostTypical(members: List<Int>): Int {
        val size = classification.vectors[members[0]].size
        val centre = FloatArray(size)
        for (m in members) for (i in 0 until size) centre[i] += classification.vectors[m][i] / members.size
        return members.minBy { m ->
            var s = 0f
            for (i in 0 until size) {
                val d = classification.vectors[m][i] - centre[i]
                s += d * d
            }
            s
        }
    }

    companion object {
        const val MIN_GENERAL_TRACKS = 8

        /** Below this voice percentile a track counts as instrumental (rough). */
        const val VOICE_PERCENTILE = 0.3f

        /** Fitting filters to a seed: the tracks to choose from, as times the tracks asked for. */
        const val IDEAL_FACTOR = 10
        const val MIN_FACTOR = 2

        /** The windows around the seed at step 1 (years each way, BPM fraction, energy/positivity). */
        private const val YEAR_MARGIN = 2f
        private const val BPM_MARGIN = 0.04f
        private const val PERCENT_MARGIN = 0.05f
        private val FIT_STEPS = listOf(1f, 1.5f, 2f, 3f, 4f, 6f, 8f, 12f, 20f)

        /** Energy curves: where the rise turns, and the energy step that weighs like a typical distance. */
        private const val PEAK_AT = 0.65f
        private const val ENERGY_STEP = 0.1f

        /** Energy curves: a link from perfect to none weighs like this many energy steps. */
        private const val LINK_WEIGHT = 2f

        /** Bridges: how many of the next tracks along the way are tried for the best link. */
        private const val BRIDGE_WINDOW = 4

        /** Lists for a set time: tracks asked for beyond the estimate, slack at the end, length assumed when unknown. */
        private const val TIME_MARGIN = 1.6f
        private const val TIME_SLACK_MS = 60_000L
        private const val DEFAULT_LENGTH_MS = 240_000L
        private const val MAX_TIME_TRACKS = 2000

        /** 10 % more, at least one. */
        fun marginFor(count: Int): Int = maxOf(1, (count + 9) / 10)
    }
}
