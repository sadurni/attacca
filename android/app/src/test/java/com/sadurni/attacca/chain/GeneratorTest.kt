package com.sadurni.attacca.chain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class GeneratorTest {

    private val random = Random(11)

    /** Fast, loud, bright and major, or slow, quiet, dark and minor, with some spread. */
    private fun features(lively: Boolean): TrackFeatures {
        fun j(scale: Float) = (random.nextFloat() - 0.5f) * scale
        return if (lively) {
            TrackFeatures(-10f + j(2f), 4f + j(1f), 11.5f + j(0.3f), -12f + j(2f), 0.4f + j(0.1f), 3f + j(0.5f), 140f + j(10f), 0.6f, 0.3f + j(0.1f), 0.7f, 0.3f)
        } else {
            TrackFeatures(-24f + j(2f), 9f + j(1f), 9.5f + j(0.3f), -6f + j(2f), 0.1f + j(0.05f), 0.8f + j(0.3f), 75f + j(8f), 0.3f, -0.3f + j(0.1f), 0.6f, 0.1f)
        }
    }

    private fun edges(hz: Float) = TrackEdges(
        EdgeDescriptor(FloatArray(12) { if (it == 0) 1f else 0f }, FloatArray(16) { hz }, -12f, 1f),
        EdgeDescriptor(FloatArray(12) { if (it == 0) 1f else 0f }, FloatArray(16) { hz }, -12f, 1f),
    )

    private fun collection(perKind: Int): List<Candidate> = List(perKind * 2) { i ->
        val lively = i % 2 == 0
        Candidate(
            id = "t$i",
            artist = "artist${i % 7}",
            genre = if (lively) "Rock" else "Jazz",
            year = 1970 + i % 40,
            features = features(lively),
            edges = edges(i % 5f),
        )
    }

    @Test
    fun separatesClearlyDifferentMusic() {
        val c = collection(40)
        val k = Moods.classify(c.map { it.features })
        val livelyFamilies = c.indices.filter { it % 2 == 0 }.map { k.families[it] }.toSet()
        val calmFamilies = c.indices.filter { it % 2 == 1 }.map { k.families[it] }.toSet()
        assertTrue("no family mixes both kinds", livelyFamilies.intersect(calmFamilies).isEmpty())
        val livelyEnergy = c.indices.filter { it % 2 == 0 }.map { k.energy[it] }.average()
        val calmEnergy = c.indices.filter { it % 2 == 1 }.map { k.energy[it] }.average()
        assertTrue(livelyEnergy > 0.7 && calmEnergy < 0.3)
        assertTrue(c.indices.filter { it % 2 == 1 }.all { k.moods[it] in setOf(Mood.CALM, Mood.MELANCHOLIC) })
        assertTrue(c.indices.filter { it % 2 == 0 }.all { k.moods[it] in setOf(Mood.ENERGETIC, Mood.CHEERFUL, Mood.TENSE) })
    }

    @Test
    fun familiesAreNamedByTheirTraits() {
        val c = collection(40)
        val k = Moods.classify(c.map { it.features })
        val fast = k.familyNames[k.families[0]].traits
        assertTrue(fast.isNotEmpty())
        assertTrue(fast.all { (trait, high) -> trait != Trait.TEMPO || high })
    }

    @Test
    fun seedListStartsWithTheSeedStaysInItsGroupAndHasTheCountAsked() {
        val c = collection(100)
        val g = ListGenerator(c)
        val seed = 4
        val list = g.fromSeed(seed, ChainFilters(), sameMood = true, sameFamily = true, count = 30, artistSpacing = 3)!!
        // The same mood: its main one, or its second one for a track between two.
        val group = c.indices.count { g.classification.hasMood(it, setOf(g.moodOf(seed))) && g.familyOf(it) == g.familyOf(seed) }
        assertEquals(minOf(30, group), list.size)
        val small = g.fromSeed(seed, ChainFilters(), sameMood = true, sameFamily = true, count = 10, artistSpacing = 3)!!
        assertEquals(10, small.size)
        assertEquals("t4", list.first())
        assertEquals(list.size, list.toSet().size)
        for (id in list) {
            val i = g.indexOf(id)
            assertTrue(g.classification.hasMood(i, setOf(g.moodOf(seed))))
            assertEquals(g.familyOf(seed), g.familyOf(i))
        }
    }

    @Test
    fun seedListKeepsArtistsApartWhenOneArtistIsClosest() {
        // Most tracks like the seed are by its own artist: the list takes few enough of them to
        // keep the spacing.
        val c = List(60) { i ->
            Candidate(
                id = "t$i",
                artist = if (i < 40) "solo" else "other$i",
                genre = "Rock",
                year = 1990,
                features = features(lively = true),
                edges = edges(i % 5f),
            )
        }
        val g = ListGenerator(c)
        val spacing = 3
        val list = g.fromSeed(0, ChainFilters(), sameMood = false, sameFamily = false, count = 20, artistSpacing = spacing)!!
        assertEquals(20, list.size)
        val artists = list.map { c[g.indexOf(it)].artist }
        for (i in artists.indices) for (j in i + 1..minOf(i + spacing, artists.size - 1)) {
            assertTrue("$artists", artists[i] != artists[j])
        }
    }

    @Test
    fun marginIsTenPercent() {
        assertEquals(15, ListGenerator.marginFor(150))
        assertEquals(1, ListGenerator.marginFor(5))
        assertEquals(10, ListGenerator.marginFor(100))
    }

    @Test
    fun filtersApply() {
        val c = collection(50)
        val g = ListGenerator(c)
        val list = g.fromSeed(
            0,
            ChainFilters(yearFrom = 1980, yearTo = 1990, genre = "rock", bpmMin = 120),
            sameMood = false, sameFamily = false, count = 100, artistSpacing = 0,
        )!!
        for (id in list.drop(1)) {
            val t = c[g.indexOf(id)]
            assertTrue(t.year in 1980..1990 && t.genre == "Rock" && t.features.tempoBpm >= 120)
        }
    }

    @Test
    fun durationRangeKeepsTracksOfUnknownLength() {
        val c = collection(20).mapIndexed { i, t ->
            Candidate(t.id, t.artist, t.genre, t.year, t.features, t.edges, durationMs = listOf(0L, 30_000L, 180_000L, 1_500_000L)[i % 4])
        }
        val g = ListGenerator(c)
        val f = ChainFilters(durationMinS = 60, durationMaxS = 1200)
        val kept = c.indices.filter { g.passes(it, f) }.map { c[it].durationMs }.toSet()
        assertEquals(setOf(0L, 180_000L), kept)
    }

    @Test
    fun generalListsCoverEachGroupOnce() {
        val c = collection(40)
        val g = ListGenerator(c)
        val lists = g.general(ChainFilters(), artistSpacing = 2)!!
        assertTrue(lists.size >= 2)
        val all = lists.flatMap { it.ids }
        assertEquals(all.size, all.toSet().size)
        for (l in lists) assertTrue(l.ids.all { g.moodOf(g.indexOf(it)) == l.mood && g.familyOf(g.indexOf(it)) == l.family })
        assertTrue(g.general(ChainFilters(), artistSpacing = 2, count = 5)!!.all { it.ids.size <= 5 })
    }

    @Test
    fun percentilesAndStandardScores() {
        assertEquals(listOf(0f, 1f, 0.5f), Moods.percentiles(floatArrayOf(1f, 9f, 5f)).toList())
        val z = Moods.standardise(floatArrayOf(1f, 2f, 3f, 4f, 100f))
        assertEquals(0f, z[2], 1e-6f)
        assertEquals(3f, z[4], 0f)
    }

    @Test
    fun fitToSeedIsAsTightAsItCanBeWithRoomToChoose() {
        val g = ListGenerator(collection(300))
        val seed = 10
        // Plenty of tracks: ten times the count, the seed inside its own filters.
        val fit = g.fitToSeed(seed, ChainFilters(), sameMood = false, sameFamily = false, count = 20)
        assertTrue(fit.available >= 20 * ListGenerator.IDEAL_FACTOR)
        assertTrue(g.passes(seed, fit.filters))
        val f = fit.filters
        assertTrue((f.yearFrom ?: 0) <= 1980 && (f.yearTo ?: 9999) >= 1980)
        assertTrue(f.bpmMin != null || f.energyMin != null || f.positivityMin != null)
        assertEquals(fit.available, (0 until 600).count { it != seed && g.passes(it, f) })
        // Fewer: at least twice the count when ten times cannot be had.
        val more = g.fitToSeed(seed, ChainFilters(), sameMood = false, sameFamily = false, count = 100)
        assertTrue(more.available >= 200)
        // Impossible: the widest, with the user's own filters still applied.
        val all = g.fitToSeed(seed, ChainFilters(genre = "Rock"), sameMood = false, sameFamily = false, count = 400)
        assertEquals(299, all.available)
        assertEquals("Rock", all.filters.genre)
        assertEquals(null, all.filters.yearFrom)
    }
}
