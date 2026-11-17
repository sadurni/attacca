package com.sadurni.attacca.library

import org.junit.Assert.assertEquals
import org.junit.Test

class SameSongTest {

    private fun t(id: String, title: String, artist: String, album: String, albumArtist: String, ms: Long) =
        SameSong.Track(id, title, artist, album, albumArtist, ms)

    private val sizes = HashMap<String, Long>()
    private fun size(id: String) = sizes[id] ?: 0L

    @Test
    fun theSameRecordingOnACompilationGoesKeepingTheBestCopy() {
        val album = t("/m/Queen/1975-Night/11-Bohemian Rhapsody.mp3", "Bohemian Rhapsody", "Queen", "A Night at the Opera", "Queen", 355_000)
        val hits = t("/m/Queen/1981-Greatest Hits/01-Bohemian Rhapsody.mp3", "Bohemian Rhapsody (Remastered 2011)", "Queen", "Greatest Hits", "Queen", 356_500)
        val various = t("/m/_COMP/Rock 70s/05-Bohemian Rhapsody.flac", "Bohemian Rhapsody", "Queen", "Rock of the 70s", "Various Artists", 354_200)
        sizes[album.id] = 355L * 320 / 8 * 1000 // 320 kbit/s
        sizes[hits.id] = 356L * 192 / 8 * 1000
        sizes[various.id] = 354L * 900 / 8 * 1000 // lossless
        assertEquals(setOf(album.id, hits.id), SameSong.others(listOf(album, hits, various), sizeOf = ::size))
        // Without the lossless copy, the higher bit rate.
        assertEquals(setOf(hits.id), SameSong.others(listOf(album, hits), sizeOf = ::size))
        // The track a list starts from always stays and stands for the others.
        assertEquals(setOf(album.id, various.id), SameSong.others(listOf(album, hits, various), keep = setOf(hits.id), sizeOf = ::size))
    }

    @Test
    fun otherVersionsAreOtherSongs() {
        val studio = t("/m/a/01.mp3", "Love Song", "Rollins Band", "Weight", "Rollins Band", 240_000)
        val live = t("/m/b/01.mp3", "Love Song (Live)", "Rollins Band", "Weight", "Rollins Band", 241_000)
        val liveAlbum = t("/m/c/01.mp3", "Love Song", "Rollins Band", "Live in London", "Rollins Band", 240_500)
        val acoustic = t("/m/d/01.mp3", "Love Song - Acoustic Version", "Rollins Band", "Singles", "Rollins Band", 239_000)
        val longer = t("/m/e/01.mp3", "Love Song", "Rollins Band", "Singles", "Rollins Band", 251_000)
        // Each live one is apart from the studio one (but the same live recording twice is one).
        assertEquals(emptySet<String>(), SameSong.others(listOf(studio, live, acoustic, longer), sizeOf = { 1L }))
        assertEquals(emptySet<String>(), SameSong.others(listOf(studio, liveAlbum, acoustic, longer), sizeOf = { 1L }))
        assertEquals(1, SameSong.others(listOf(live, liveAlbum), sizeOf = { 1L }).size)
    }

    @Test
    fun namesAreComparedAsSpoken() {
        val a = t("/m/x/1.mp3", "Can’t Slow Down", "The Joe Satriani Band", "Album", "The Joe Satriani Band", 290_000)
        val b = t("/m/y/2.mp3", "Can't Slow Down [2019 Remaster]", "Joe Satriani Band feat. Someone", "Best of", "Various", 290_400)
        assertEquals(SameSong.keyOf(a), SameSong.keyOf(b))
        assertEquals(1, SameSong.others(listOf(a, b), sizeOf = { 1L }).size)
    }
}
