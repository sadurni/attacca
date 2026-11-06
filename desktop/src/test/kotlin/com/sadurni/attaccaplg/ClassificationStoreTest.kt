package com.sadurni.attaccaplg

import com.sadurni.attacca.chain.ClassificationStore
import com.sadurni.attacca.chain.Mood
import com.sadurni.attacca.chain.Moods
import com.sadurni.attacca.chain.TrackFeatures
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class ClassificationStoreTest {
    private fun features(r: Random) = TrackFeatures.fromArray(FloatArray(TrackFeatures.SIZE) { r.nextFloat() * (if (it == 6) 120f else 1f) + (if (it == 6) 60f else 0f) })

    @Test
    fun keptClassificationComesBackTheSame() {
        val r = Random(7)
        val ids = List(40) { "track$it.mp3" }
        val features = ids.map { features(r) }
        val classification = Moods.classify(features)
        val file = File.createTempFile("classes", ".bin").apply { deleteOnExit() }
        val key = ClassificationStore.key(ids, features, ids.map { 0L })
        ClassificationStore.write(file, key, ids, classification)

        val (readIds, read) = ClassificationStore.read(file, key)!!
        assertEquals(ids, readIds)
        assertEquals(classification.moods, read.moods)
        assertEquals(classification.secondMoods, read.secondMoods)
        assertContentEquals(classification.energy, read.energy)
        assertContentEquals(classification.families, read.families)
        assertEquals(classification.familyNames, read.familyNames)
        for (i in ids.indices) assertContentEquals(classification.vectors[i], read.vectors[i])

        // Another measure, a mood given by hand or another sound profile: another key, nothing read.
        val changed = features.toMutableList().also { it[3] = features(r) }
        assertNotEquals(key, ClassificationStore.key(ids, changed, ids.map { 0L }))
        assertNotEquals(key, ClassificationStore.key(ids, features, ids.map { 0L }, mapOf(2 to Mood.CALM)))
        assertNotEquals(key, ClassificationStore.key(ids, features, ids.mapIndexed { i, _ -> if (i == 5) 9L else 0L }))
        assertNull(ClassificationStore.read(file, key + 1))
        // Any key: what was kept, for showing at once.
        assertEquals(ids, ClassificationStore.read(file, null)!!.first)
    }
}
