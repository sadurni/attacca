package com.sadurni.attacca.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenCounterTest {

    private val played = ArrayList<Listen>()
    private val skipped = ArrayList<Listen>()
    private val counter = ListenCounter({ played.add(it) }, { skipped.add(it) })

    @Test
    fun countsAtHalfTheTrack() {
        counter.start("a", now = 0, wallNow = 1_000, playing = true)
        counter.setDuration(200_000)
        assertEquals(100_000L, counter.dueInMs(0))
        counter.check(99_999)
        assertTrue(played.isEmpty())
        counter.check(100_000)
        assertEquals(listOf(Listen("a", 1_000, 200_000)), played)
        // Once only.
        counter.check(150_000)
        counter.leave(160_000, byUser = true)
        assertEquals(1, played.size)
        assertTrue(skipped.isEmpty())
    }

    @Test
    fun longTracksCountAtFourMinutes() {
        counter.start("a", 0, 0, playing = true)
        counter.setDuration(20 * 60_000L)
        assertEquals(ListenCounter.PLAYED_CAP_MS, counter.dueInMs(0))
    }

    @Test
    fun pausesDoNotCount() {
        counter.start("a", 0, 0, playing = true)
        counter.setDuration(100_000)
        counter.setPlaying(false, 30_000, 30_000)
        assertNull(counter.dueInMs(80_000))
        counter.check(80_000)
        assertTrue(played.isEmpty())
        counter.setPlaying(true, 80_000, 80_000)
        assertEquals(20_000L, counter.dueInMs(80_000))
        counter.check(100_000)
        assertEquals(1, played.size)
    }

    @Test
    fun speedCounts() {
        counter.start("a", 0, 0, playing = true)
        counter.setDuration(100_000)
        counter.setSpeed(2f, 0)
        assertEquals(25_000L, counter.dueInMs(0))
    }

    @Test
    fun leavingEarlyByUserIsASkip() {
        counter.start("a", 0, 0, playing = true)
        counter.setDuration(100_000)
        counter.leave(10_000, byUser = true)
        assertEquals(listOf("a"), skipped.map { it.mediaId })
        assertTrue(played.isEmpty())
    }

    @Test
    fun passingThroughIsNotASkip() {
        counter.start("a", 0, 0, playing = true)
        counter.setDuration(100_000)
        counter.leave(300, byUser = true)
        assertTrue(skipped.isEmpty())
    }

    @Test
    fun endingByItselfIsNoSkip() {
        counter.start("a", 0, 0, playing = true)
        counter.setDuration(100_000)
        counter.leave(10_000, byUser = false)
        assertTrue(skipped.isEmpty())
    }

    @Test
    fun loadedPausedStartsWhenItSounds() {
        counter.start("a", 0, 5_000, playing = false)
        counter.setDuration(100_000)
        assertNull(counter.dueInMs(0))
        counter.setPlaying(true, 60_000, 65_000)
        counter.check(110_000)
        assertEquals(listOf(Listen("a", 65_000, 100_000)), played)
    }
}
