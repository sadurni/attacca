package com.sadurni.attaccaplg

import com.sadurni.attaccaplg.export.PathMapping
import com.sadurni.attaccaplg.export.PlaylistFormat
import com.sadurni.attaccaplg.export.PlaylistWriter
import com.sadurni.attaccaplg.generate.Inclusions
import com.sadurni.attacca.library.PathMatcher
import com.sadurni.attaccaplg.library.StoredTrack
import com.sadurni.attaccaplg.library.Tags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GeneratorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val library = listOf(
        "Massive Attack/1991-Blue Lines/01-Safe From Harm.mp3",
        "Massive Attack/1998-Mezzanine/01-Angel.mp3",
        "Other/1998-Mezzanine/01-Angel.mp3",
        "Fear Factory/1998-Obsolete/01-Shock.ogg",
    )

    @Test
    fun pathsFromOtherDevicesFindTheirTrack() {
        val m = PathMatcher(library)
        assertEquals(library[0], m.match("/storage/emulated/0/Music/Massive Attack/1991-Blue Lines/01-Safe From Harm.mp3"))
        assertEquals(library[0], m.match("D:\\Musica\\Massive Attack\\1991-Blue Lines\\01-Safe From Harm.mp3"))
        assertEquals(library[3], m.match("file:///home/x/Music/Fear%20Factory/1998-Obsolete/01-Shock.ogg"))
        assertEquals(library[3], m.match("fear factory/1998-obsolete/01-shock.ogg"))
        // The folder above tells two "Angel" apart; with nothing to tell them apart, no guess.
        assertEquals(library[1], m.match("/x/Massive Attack/1998-Mezzanine/01-Angel.mp3"))
        assertNull(m.match("/x/01-Angel.mp3"))
        assertNull(m.match("/x/Nobody/missing.mp3"))
    }

    private fun track(path: String, durationMs: Long, start: Long, end: Long) = StoredTrack(
        path, 0, 0, Tags(title = "Title & <1>", artist = "Artist"), durationMs, start, end, -14f, 0.9f, null, null, null, null,
    )

    @Test
    fun playlistsSkipTheSilenceAsBeetsPlaylistDid() {
        val tracks = listOf(track("A/one.mp3", 200_000, 1_500, 198_000), track("A/two.mp3", 100_000, 50, 99_960))
        val m3u8 = PlaylistWriter.content(tracks, PlaylistFormat.M3U8, PathMapping(File("C:/Music"), "/storage/emulated/0/Music"))
        assertEquals(
            listOf(
                "#EXTM3U",
                "#EXTINF:200,Artist - Title & <1>",
                "#EXTVLCOPT:start-time=1.5",
                "#EXTVLCOPT:stop-time=198",
                "/storage/emulated/0/Music/A/one.mp3",
                "#EXTINF:100,Artist - Title & <1>",
                "/storage/emulated/0/Music/A/two.mp3",
            ),
            m3u8.trim().lines(),
        )
        val rockbox = PlaylistWriter.content(tracks, PlaylistFormat.ROCKBOX, PathMapping(null, "/<microSD1>/Music"))
        assertFalse("EXTVLCOPT" in rockbox)
        assertTrue("/<microSD1>/Music/A/one.mp3" in rockbox)
        val windows = PathMapping(null, "D:\\Musica").location("A/one.mp3")
        assertEquals("D:\\Musica\\A\\one.mp3", windows)
        val xspf = PlaylistWriter.content(tracks, PlaylistFormat.XSPF, PathMapping(null, "D:\\Musica"))
        assertTrue(xspf, "<location>file:///D:/Musica/A/one.mp3</location>" in xspf)
        assertTrue("<title>Title &amp; &lt;1&gt;</title>" in xspf)
        assertTrue("<start>1.5</start>" in xspf && "<duration>196.5</duration>" in xspf)
        assertEquals("seed_Soy_Manuela_.m3u8", PlaylistWriter.fileName("seed_Soy Manuela?", PlaylistFormat.M3U8))
    }

    @Test
    fun beetsIncludeValues() {
        for (yes in listOf("1", "true", "Sí", "yes", "on", "2.5")) assertTrue(yes, Inclusions.truthy(yes))
        for (no in listOf("0", "false", "no", "", null, "maybe")) assertFalse("$no", Inclusions.truthy(no))
    }

    @Test
    fun playlistsAreReadInEveryFormat() {
        val xspf = tmp.newFile("l.xspf")
        xspf.writeText(
            """<?xml version="1.0" encoding="UTF-8"?><playlist version="1" xmlns="http://xspf.org/ns/0/"><trackList>
               <track><location>file:///D:/Music/A/one.mp3</location><creator>Artist</creator><title>One</title></track>
               </trackList></playlist>""",
        )
        assertEquals(listOf("file:///D:/Music/A/one.mp3" to "Artist - One"), Inclusions.readPlaylist(xspf))
        val m3u = tmp.newFile("l.m3u8")
        m3u.writeText("#EXTM3U\n#EXTINF:10,Artist - One\n/storage/emulated/0/Music/A/one.mp3\n")
        val entries = Inclusions.readPlaylist(m3u)
        assertEquals(1, entries.size)
        assertEquals("A/one.mp3", PathMatcher(listOf("A/one.mp3")).match(entries[0].first))
    }
}
