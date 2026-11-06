package com.sadurni.attacca.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XspfTest {

    /** Paths as on the phone (tests may run on Windows, where they gain a drive and backslashes). */
    private fun norm(path: String) = path.replace('\\', '/').removePrefix("C:")

    @Test
    fun writesAndReadsBack() {
        val entries = listOf(
            PlaylistEntry("/storage/emulated/0/Music/Señor & Co/01 «uno» #1.flac", title = "Uno <live>"),
            PlaylistEntry("/storage/emulated/0/Music/B/02 two.mp3"),
        )
        val text = PlaylistWriter.format(entries, PlaylistFormat.XSPF, baseDir = null)
        assertTrue(text, text.contains("file:///storage/emulated/0/Music/Se%C3%B1or%20%26%20Co/"))
        val back = XspfParser.parse(text, baseDir = null)
        assertEquals(entries.map { it.location }, back.map { norm(it.location) })
        assertEquals("Uno <live>", back[0].title)
    }

    @Test
    fun readsRelativeLocations() {
        val text = """<?xml version="1.0"?><playlist xmlns="http://xspf.org/ns/0/"><trackList>
            <track><location>Album/01%20a.flac</location><title>A &amp; B</title></track>
            </trackList></playlist>"""
        val back = XspfParser.parse(text, java.io.File("/storage/emulated/0/Music"))
        assertEquals("/storage/emulated/0/Music/Album/01 a.flac", norm(back.single().location))
        assertEquals("A & B", back.single().title)
    }

    @Test
    fun writesPls() {
        val text = PlaylistWriter.format(listOf(PlaylistEntry("/m/a.mp3", title = "A")), PlaylistFormat.PLS, baseDir = null)
        assertEquals("[playlist]\nFile1=/m/a.mp3\nTitle1=A\nNumberOfEntries=1\nVersion=2\n", text)
        assertEquals("/m/a.mp3", norm(PlsParser.parse(text, null).single().location))
    }
}
