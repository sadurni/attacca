package com.sadurni.attacca.chain

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/** Moods between two, the user's own moods and how they spread, and the sound network's mood classes. */
class MoodsTest {

    private val random = Random(5)

    /** Two kinds of music, as in GeneratorTest: lively and calm, with some spread. */
    private fun features(lively: Boolean): TrackFeatures {
        fun j(scale: Float) = (random.nextFloat() - 0.5f) * scale
        return if (lively) {
            TrackFeatures(-10f + j(2f), 4f + j(1f), 11.5f + j(0.3f), -12f + j(2f), 0.4f + j(0.1f), 3f + j(0.5f), 140f + j(10f), 0.6f + j(0.1f), 0.3f, 0.7f, 0.3f + j(0.1f))
        } else {
            TrackFeatures(-24f + j(2f), 9f + j(1f), 9.5f + j(0.3f), -6f + j(2f), 0.1f + j(0.05f), 0.8f + j(0.3f), 75f + j(8f), 0.3f + j(0.1f), -0.3f, 0.6f, 0.1f + j(0.05f))
        }
    }

    private val collection = List(80) { features(it % 2 == 0) }

    @Test
    fun aCorrectedTrackTakesItsMoodClearAndItsLookAlikesFollow() {
        val plain = Moods.classify(collection)
        val calm = collection.indices.filter { it % 2 == 1 }
        val chosen = calm.first()
        val k = Moods.classify(collection, corrections = mapOf(chosen to Mood.CHEERFUL))
        assertEquals(Mood.CHEERFUL, k.moods[chosen])
        assertNull(k.secondMoods[chosen])
        val followed = calm.filter { it != chosen && k.moods[it] == Mood.CHEERFUL }
        assertTrue("some calm look-alikes follow", followed.isNotEmpty())
        assertTrue("not all of them: only the nearest", followed.size < calm.size - 1)
        // Those that follow keep their own mood as the second one.
        for (i in followed) assertEquals(plain.moods[i], k.secondMoods[i])
        // The other kind of music is far away and stays as it was.
        for (i in collection.indices.filter { it % 2 == 0 }) assertEquals(plain.moods[i], k.moods[i])
    }

    @Test
    fun aCorrectionThatDoesNotSpreadChangesOnlyItsTrack() {
        val plain = Moods.classify(collection)
        val chosen = 1
        val k = Moods.classify(collection, corrections = mapOf(chosen to Mood.TENSE), spreadFrom = emptySet())
        assertEquals(Mood.TENSE, k.moods[chosen])
        for (i in collection.indices.filter { it != chosen }) assertEquals(plain.moods[i], k.moods[i])
    }

    @Test
    fun aTrackBetweenTwoMoodsCountsAsEither() {
        val k = Moods.classify(collection)
        val between = collection.indices.firstOrNull { k.secondMoods[it] != null } ?: return
        assertTrue(k.hasMood(between, setOf(k.moods[between])))
        assertTrue(k.hasMood(between, setOf(k.secondMoods[between]!!)))
        val other = Mood.entries.first { it != k.moods[between] && it != k.secondMoods[between] }
        assertTrue(!k.hasMood(between, setOf(other)))
    }

    @Test
    fun theMoodHeadScoresAsTheWholeNetwork() {
        val weights = File("../../core/assets/sound/soundnet.bin")
        val head = weights.inputStream().use { MoodHead.read(it) }
        val net = weights.inputStream().use { SoundNet(it) }
        val embedding = FloatArray(SoundNet.EMBEDDING_SIZE) { random.nextFloat() * 0.5f }
        val all = net.classify(embedding)
        val expected = FloatArray(MoodHead.CLASSES.size) { all[MoodHead.CLASSES[it]] }
        val got = head.logits(embedding).map { MoodHead.probability(it) }.toFloatArray()
        assertArrayEquals(expected, got, 1e-5f)
    }
}
