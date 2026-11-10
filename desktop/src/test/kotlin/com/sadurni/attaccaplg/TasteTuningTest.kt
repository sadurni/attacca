package com.sadurni.attaccaplg

import com.sadurni.attacca.chain.ChainTaste
import com.sadurni.attacca.chain.EdgeDescriptor
import com.sadurni.attacca.chain.EdgeFeatures
import com.sadurni.attacca.chain.TasteGroup
import com.sadurni.attacca.tuning.TasteAnswer
import com.sadurni.attacca.tuning.TasteTrack
import com.sadurni.attacca.tuning.TasteTuning
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TasteTuningTest {

    @AfterTest
    fun reset() {
        EdgeFeatures.taste = ChainTaste.DEFAULT
    }

    private fun edge(r: Random): EdgeDescriptor {
        val chroma = FloatArray(12) { r.nextFloat() }.let { c -> val s = c.sum(); FloatArray(12) { c[it] / s } }
        return EdgeDescriptor(
            chroma = chroma,
            bands = FloatArray(16) { r.nextFloat() * 12f - 6f },
            loudnessDb = -14f - r.nextFloat() * 8f,
            flux = r.nextFloat() * 2f,
            timbre = FloatArray(13) { r.nextFloat() * 1.2f - 0.6f },
            tempoBpm = 70f + r.nextFloat() * 100f,
            pulse = 0.4f + r.nextFloat() * 0.6f,
        )
    }

    private fun library(seed: Int): List<TasteTrack> {
        val r = Random(seed)
        return List(300) { TasteTrack(it, edge(r), edge(r), "artist ${it % 25}", "album ${it / 10}") }
    }

    /** A listener who only hears [group]: picks the start that matches better in it. */
    private fun run(group: TasteGroup, seed: Int): ChainTaste {
        val tracks = library(seed)
        val r = Random(seed)
        val answers = (0 until TasteTuning.ROUNDS).map { i ->
            val round = assertNotNull(TasteTuning.pick(tracks, TasteTuning.group(i), ChainTaste.DEFAULT, r))
            val a = group.parts.sumOf { round.firstParts[it].toDouble() }
            val b = group.parts.sumOf { round.secondParts[it].toDouble() }
            TasteAnswer(round.firstParts, round.secondParts, if (a >= b) TasteAnswer.FIRST else TasteAnswer.SECOND)
        }
        return TasteTuning.learn(ChainTaste.DEFAULT, answers)
    }

    @Test
    fun aListenerOfTheBeatGetsMoreTempo() {
        val learnt = run(TasteGroup.TEMPO, 1)
        assertTrue(learnt.share(TasteGroup.TEMPO) > ChainTaste.DEFAULT.share(TasteGroup.TEMPO) + 0.1f, learnt.toString())
    }

    @Test
    fun aListenerOfTheKeyGetsMoreKey() {
        val learnt = run(TasteGroup.KEY, 2)
        assertTrue(learnt.share(TasteGroup.KEY) > ChainTaste.DEFAULT.share(TasteGroup.KEY) * 1.5f, learnt.toString())
        assertTrue(learnt.share(TasteGroup.TEMPO) < ChainTaste.DEFAULT.share(TasteGroup.TEMPO), learnt.toString())
    }

    @Test
    fun noAnswersKeepTheWeights() {
        val same = TasteTuning.learn(ChainTaste.DEFAULT, emptyList())
        assertTrue(same.isDefault)
        val alike = TasteTuning.learn(ChainTaste.DEFAULT, listOf(TasteAnswer(FloatArray(6), FloatArray(6), TasteAnswer.ALIKE)))
        assertTrue(alike.isDefault)
    }

    @Test
    fun weightsChangeTheScore() {
        val r = Random(5)
        val end = edge(r)
        val start = edge(r)
        val parts = assertNotNull(EdgeFeatures.parts(end, start))
        assertEquals(ChainTaste.DEFAULT.score(parts), EdgeFeatures.similarity(end, start), 1e-5f)
        EdgeFeatures.taste = ChainTaste(1f, 0f, 0f, 0f, 0f, 0f)
        assertEquals(parts[0], EdgeFeatures.similarity(end, start), 1e-5f)
    }

    @Test
    fun encodedAndBack() {
        val t = ChainTaste(0.5f, 0.2f, 0.1f, 0.1f, 0.06f, 0.04f)
        val back = ChainTaste.decode(t.encode())
        for (g in TasteGroup.entries) assertEquals(t.share(g), back.share(g), 0.002f)
        assertTrue(ChainTaste.decode("rubbish").isDefault)
        assertTrue(ChainTaste.decode(null).isDefault)
    }
}
