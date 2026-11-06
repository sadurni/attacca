package com.sadurni.attacca.chain

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class SoundProfileTest {

    @Test
    fun keptInBytesItWritesAndReadsBackTheSame() {
        val random = Random(3)
        val embedding = FloatArray(SoundNet.EMBEDDING_SIZE) { random.nextFloat() * 2f }
        val timbre = FloatArray(SoundProfile.TIMBRE_SIZE) { random.nextFloat() - 0.5f }
        val bytes = SoundProfile(embedding, timbre).toBytes()
        assertEquals(SoundProfile.BYTES, bytes.size)
        val back = SoundProfile.fromBytes(bytes)!!
        assertArrayEquals(bytes, back.toBytes())
        // Each value within half a step of the scale (255 steps up to the largest).
        val step = embedding.max() / 255f
        for (i in embedding.indices) assertEquals(embedding[i], back.embedding[i], step / 2 + 1e-6f)
        assertArrayEquals(timbre, back.timbre, 0f)
    }
}
