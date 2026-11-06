package com.sadurni.attacca.chain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The other kinds of list: bridge, energy curves, sampler, contrast, for a set time, closest albums. */
class MoreListsTest {

    private val random = Random(5)

    /** Tracks along one line of energy (0 calm … 1 lively), each a few minutes long. */
    private fun features(level: Float): TrackFeatures {
        fun j(scale: Float) = (random.nextFloat() - 0.5f) * scale
        return TrackFeatures(
            -24f + 14f * level + j(1f), 9f - 5f * level + j(0.5f), 9.5f + 2f * level, -6f - 6f * level, 0.1f + 0.3f * level,
            0.8f + 2.2f * level + j(0.2f), 75f + 65f * level + j(4f), 0.3f + 0.3f * level, -0.3f + 0.6f * level, 0.65f, 0.1f + 0.2f * level,
        )
    }

    private fun edges() = TrackEdges(
        EdgeDescriptor(FloatArray(12) { if (it == 0) 1f else 0f }, FloatArray(16) { 1f }, -12f, 1f),
        EdgeDescriptor(FloatArray(12) { if (it == 0) 1f else 0f }, FloatArray(16) { 1f }, -12f, 1f),
    )

    private val collection = List(200) { i ->
        Candidate("t$i", "artist${i % 9}", "", 1990, features(i / 199f), edges(), durationMs = 180_000L + (i % 5) * 30_000L)
    }
    private val g = ListGenerator(collection)

    @Test
    fun bridgeGoesFromOneEndToTheOther() {
        val list = g.bridge(0, 199, ChainFilters(), 20, 2)!!
        assertEquals(20, list.size)
        assertEquals("t0", list.first())
        assertEquals("t199", list.last())
        assertEquals(list.size, list.toSet().size)
        // Each step further from the first end.
        val energy = list.map { g.classification.energy[g.indexOf(it)] }
        assertTrue(energy.first() < energy[10] && energy[10] < energy.last())
    }

    @Test
    fun energyCurvesFollowTheirShape() {
        val up = g.energyCurve(ListGenerator.EnergyShape.WARM_UP, null, ChainFilters(), 30, 2, Random(1))!!
        val e = up.map { g.classification.energy[g.indexOf(it)] }
        assertEquals(30, up.size)
        assertEquals(up.size, up.toSet().size)
        assertTrue(e.take(5).average() < 0.35 && e.takeLast(5).average() > 0.65)
        val peak = g.energyCurve(ListGenerator.EnergyShape.RISE_AND_FALL, null, ChainFilters(), 30, 2, Random(2))!!
        val p = peak.map { g.classification.energy[g.indexOf(it)] }
        assertTrue(p.subList(17, 22).average() > p.take(4).average() && p.subList(17, 22).average() > p.takeLast(4).average())
        // No artist twice within the spacing.
        val artists = peak.map { collection[g.indexOf(it)].artist }
        for (i in 2 until artists.size) assertTrue(artists[i] != artists[i - 1] && artists[i] != artists[i - 2])
    }

    @Test
    fun samplerTakesEveryFamily() {
        val list = g.sampler(ChainFilters(), 40, 2)!!
        val families = list.map { g.familyOf(g.indexOf(it)) }.toSet()
        assertEquals(g.classification.families.toSet(), families)
    }

    @Test
    fun contrastTakesTheFarthest() {
        val list = g.contrast(0, ChainFilters(), 20, 2)!!
        assertEquals(20, list.size)
        assertTrue(list.map { g.classification.energy[g.indexOf(it)] }.average() > 0.7)
    }

    @Test
    fun forTimeLastsAboutTheTimeAsked() {
        val list = g.forTime(100, ChainFilters(), 60, 2)!!
        val total = list.sumOf { collection[g.indexOf(it)].durationMs }
        assertTrue("total $total", total in 59 * 60_000L..61 * 60_000L)
        assertEquals("t100", list.first())
    }

    @Test
    fun closestGroupsComeFirst() {
        val groups = listOf((150 until 160).toList(), (10 until 20).toList(), (90 until 100).toList())
        assertEquals(listOf(1, 2, 0), g.closestGroups(listOf(5, 6), groups))
    }
}
