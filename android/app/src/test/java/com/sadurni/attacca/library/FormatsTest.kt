package com.sadurni.attacca.library

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

class CueParserTest {

    private val cue = """
        PERFORMER "David Bowie"
        TITLE "Ziggy Stardust"
        FILE "Ziggy.flac" WAVE
          TRACK 01 AUDIO
            TITLE "Five Years"
            INDEX 00 00:00:00
            INDEX 01 00:00:33
          TRACK 02 AUDIO
            TITLE "Soul Love"
            INDEX 01 04:43:12
          TRACK 03 AUDIO
            TITLE "Moonage Daydream"
            PERFORMER "Bowie & The Spiders"
            INDEX 01 08:17:00
    """.trimIndent()

    @Test
    fun splitsFileIntoClippedTracks() {
        val entries = CueParser.parse(cue, File("/music/ziggy"))
        assertEquals(3, entries.size)
        val file = File("/music/ziggy/Ziggy.flac").canonicalPath
        assertTrue(entries.all { it.location == file })
        assertEquals("David Bowie - Five Years", entries[0].title)
        assertEquals(440L, entries[0].startMs) // 33 frames = 440 ms
        assertEquals(283_160L, entries[0].endMs) // 4:43 + 12/75 s
        assertEquals(283_160L, entries[1].startMs)
        assertEquals(497_000L, entries[1].endMs)
        assertEquals("Bowie & The Spiders - Moonage Daydream", entries[2].title)
        assertNull(entries[2].endMs) // last track plays to the end of the file
    }

    @Test
    fun parsesFrames() {
        assertEquals(61_013L, CueParser.parseTime("01:01:01")) // 1 frame = 13 ms
    }
}

class PlsParserTest {

    @Test
    fun readsNumberedEntries() {
        val text = """
            [playlist]
            File1=a.mp3
            Title1=First
            File2=http://radio.example/stream
            NumberOfEntries=2
        """.trimIndent()
        val entries = PlsParser.parse(text, File("/music"))
        assertEquals(2, entries.size)
        assertEquals(File("/music/a.mp3").canonicalPath, entries[0].location)
        assertEquals("First", entries[0].title)
        assertEquals("http://radio.example/stream", entries[1].location)
    }
}

class LyricsTest {

    @Test
    fun parsesSyncedLrc() {
        val lyrics = Lyrics.parse(
            """
            [ar:David Bowie]
            [00:12.50]Keep your 'lectric eye on me babe
            [00:16.00][01:20.00]Put your ray gun to my head
            """.trimIndent(),
        )!!
        assertTrue(lyrics.synced)
        assertEquals(3, lyrics.lines.size)
        assertEquals(12_500L, lyrics.lines[0].timeMs)
        assertEquals(80_000L, lyrics.lines[2].timeMs)
        assertEquals(-1, lyrics.currentLine(10_000))
        assertEquals(1, lyrics.currentLine(20_000))
    }

    @Test
    fun plainTextIsUnsynced() {
        val lyrics = Lyrics.parse("\nLine one\nLine two\n")!!
        assertFalse(lyrics.synced)
        assertEquals(listOf("Line one", "Line two"), lyrics.lines.map { it.text })
    }
}

class AlbumShuffleTest {

    private val keys = listOf("a", "a", "a", "b", "b", "c", "c", "c", "c")

    @Test
    fun keepsTrackOrderWithinAlbums() {
        repeat(20) { seed ->
            val order = AlbumShuffle.order(keys, Random(seed))
            assertEquals(keys.indices.toSet(), order.toSet())
            // Within each album, indices increase.
            for (album in keys.distinct()) {
                val positions = order.filter { keys[it] == album }
                assertEquals(positions.sorted(), positions)
                // And they are contiguous in the play order.
                val first = order.indexOf(positions.first())
                assertArrayEquals(positions.toIntArray(), order.copyOfRange(first, first + positions.size))
            }
        }
    }

    // Albums scattered across the list (untagged: grouped by folder).
    private val scattered = listOf("/m/B/02.mp3", "/m/A/03.mp3", "/m/C/01.mp3", "/m/A/01.mp3", "/m/B/01.mp3", "/m/A/02.mp3")

    @Test
    fun gathersScatteredAlbumsInTrackOrder() {
        repeat(20) { seed ->
            val order = AlbumShuffle.gatheredOrder(scattered, Random(seed)).map { scattered[it] }
            for (album in listOf("A", "B", "C")) {
                val tracks = order.filter { it.startsWith("/m/$album/") }
                val first = order.indexOf(tracks.first())
                assertEquals(tracks, order.subList(first, first + tracks.size))
                assertEquals(tracks.sorted(), tracks)
            }
        }
    }

    @Test
    fun playingAlbumGoesFirst() {
        // Playing /m/A/02.mp3: the rest of album A first, its earlier track at the end.
        val order = AlbumShuffle.gatheredOrderStartingWith(scattered, firstIndex = 5, random = Random(3)).map { scattered[it] }
        assertEquals(listOf("/m/A/02.mp3", "/m/A/03.mp3"), order.take(2))
        assertEquals("/m/A/01.mp3", order.last())
    }

    /** Smallest distance between two albums of the same artist in [order]. */
    private fun closest(order: List<Int>, artists: List<String>): Int {
        val seen = HashMap<String, Int>()
        var min = Int.MAX_VALUE
        order.forEachIndexed { position, album ->
            seen.put(artists[album], position)?.let { min = minOf(min, position - it) }
        }
        return min
    }

    @Test
    fun spreadKeepsArtistsApartAndEveryAlbum() {
        // 30 albums: one artist with 8, two with 5, the rest single.
        val artists = List(8) { "a" } + List(5) { "b" } + List(5) { "c" } + List(12) { "s$it" }
        repeat(50) { seed ->
            val order = AlbumShuffle.spread(artists, Random(seed))
            assertEquals(artists.indices.toList(), order.sorted())
            // 8 albums in 30: at least 2 others in between.
            assertTrue("seed $seed: ${order.map(artists::get)}", closest(order, artists) >= 3)
        }
    }

    @Test
    fun spreadLeavesNoPileAtTheEnd() {
        // Half the albums by one artist: they must alternate, not bunch up in the last third.
        val artists = List(10) { "a" } + List(10) { "s$it" }
        repeat(50) { seed ->
            val order = AlbumShuffle.spread(artists, Random(seed))
            assertTrue(closest(order, artists) >= 2)
            val lastThird = order.takeLast(7).count { artists[it] == "a" }
            assertTrue("seed $seed: $lastThird of a in the last 7", lastThird <= 4)
        }
    }

    @Test
    fun spreadKeepsTheFirstAlbum() {
        val artists = listOf("a", "a", "a", "b", "b", "c", "d", "e")
        repeat(20) { seed ->
            val order = AlbumShuffle.spread(artists, Random(seed), first = 1)
            assertEquals(1, order[0])
            assertTrue(closest(order, artists) >= 2)
        }
    }
}
