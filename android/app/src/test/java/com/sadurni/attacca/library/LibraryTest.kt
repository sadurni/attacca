package com.sadurni.attacca.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class M3uParserTest {

    private val base = File("/music/lists")

    @Test
    fun resolvesRelativeAbsoluteAndUrlEntries() {
        val text = """
            #EXTM3U
            #EXTINF:278,David Bowie - Moonage Daydream
            ../Bowie/Ziggy/03 Moonage Daydream.flac
            /storage/emulated/0/Music/song.mp3
            http://radio.example/stream
        """.trimIndent()
        val entries = M3uParser.parse(text, base)
        assertEquals(3, entries.size)
        assertEquals(File("/music/Bowie/Ziggy/03 Moonage Daydream.flac").canonicalPath, entries[0].location)
        assertEquals("David Bowie - Moonage Daydream", entries[0].title)
        assertEquals(File("/storage/emulated/0/Music/song.mp3").canonicalPath, entries[1].location)
        assertNull(entries[1].title)
        assertEquals("http://radio.example/stream", entries[2].location)
    }

    @Test
    fun readsVlcTrimPoints() {
        val text = """
            #EXTM3U
            #EXTINF:200,A - B
            #EXTVLCOPT:start-time=1.25
            #EXTVLCOPT:stop-time=198.5
            /music/a.flac
            /music/b.flac
        """.trimIndent()
        val entries = M3uParser.parse(text, base)
        assertEquals(1250L, entries[0].startMs)
        assertEquals(198_500L, entries[0].endMs)
        assertNull(entries[1].startMs)
        assertNull(entries[1].endMs)
    }

    @Test
    fun handlesWindowsPaths() {
        val entries = M3uParser.parse("..\\Album\\01.mp3\r\nD:\\Music\\x.mp3\r\n", base)
        assertEquals(File("/music/Album/01.mp3").canonicalPath, entries[0].location)
        assertEquals(File("/music/lists/Music/x.mp3").canonicalPath, entries[1].location)
    }

    @Test
    fun decodesAnsiPlaylists() {
        val bytes = "Canción.mp3".toByteArray(Charsets.ISO_8859_1)
        assertEquals("Canción.mp3", M3uParser.decode(bytes))
        assertEquals("Canción.mp3", M3uParser.decode("Canción.mp3".toByteArray(Charsets.UTF_8)))
    }
}

class NaturalOrderTest {

    @Test
    fun numbersSortNumerically() {
        val names = listOf("Track 10.mp3", "track 2.mp3", "Track 1.mp3", "Bonus.mp3")
        val sorted = names.sortedWith { a, b -> NaturalOrder.compareNatural(a, b) }
        assertEquals(listOf("Bonus.mp3", "Track 1.mp3", "track 2.mp3", "Track 10.mp3"), sorted)
    }
}
