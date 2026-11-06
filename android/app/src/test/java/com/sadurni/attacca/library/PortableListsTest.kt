package com.sadurni.attacca.library

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PortableListsTest {

    private val root = Files.createTempDirectory("music").toFile()
    private val track = File(root, "Artist/Album/01 Song.flac").apply {
        parentFile!!.mkdirs()
        writeText("x")
    }

    @After
    fun cleanUp() {
        M3uParser.musicFolder = null
        root.deleteRecursively()
    }

    @Test
    fun findsTracksOfAnotherDeviceBelowTheMusicFolder() {
        M3uParser.musicFolder = root
        val text = """
            #EXTM3U
            /storage/1234-ABCD/Musica/Artist/Album/01 Song.flac
            D:\Music\Artist\Album\01 Song.flac
            Artist/Album/01 Song.flac
        """.trimIndent()
        val entries = M3uParser.parse(text, File(root, "Lists"))
        assertEquals(3, entries.size)
        entries.forEach { assertEquals(track.canonicalPath, it.location) }
    }

    @Test
    fun withoutMusicFolderMissingTracksStayAsWritten() {
        val entries = M3uParser.parse("/elsewhere/Artist/Album/01 Song.flac", null)
        assertEquals("/elsewhere/Artist/Album/01 Song.flac", File(entries.single().location).invariantSeparatorsPath.substringAfter(':'))
    }

    @Test
    fun exportsRelativeToTheMusicFolder() {
        M3uParser.musicFolder = File("/storage/emulated/0/Music")
        val text = M3uParser.format(
            listOf(PlaylistEntry("/storage/emulated/0/Music/Artist/Album/01.flac", null, null, null)),
            baseDir = File("/storage/emulated/0/Download"),
        )
        assertEquals("#EXTM3U\nArtist/Album/01.flac\n", text)
    }

    @Test
    fun exportInsideTheMusicFolderIsRelativeToTheList() {
        M3uParser.musicFolder = File("/storage/emulated/0/Music")
        val text = M3uParser.format(
            listOf(PlaylistEntry("/storage/emulated/0/Music/Artist/Album/01.flac", null, null, null)),
            baseDir = File("/storage/emulated/0/Music/Lists"),
        )
        assertEquals("#EXTM3U\n../Artist/Album/01.flac\n", text)
    }

    @Test
    fun ownListsKeepAbsolutePaths() {
        M3uParser.musicFolder = File("/storage/emulated/0/Music")
        val text = M3uParser.format(
            listOf(PlaylistEntry("/storage/emulated/0/Music/Artist/Album/01.flac", null, null, null)),
            portable = false,
        )
        assertEquals("#EXTM3U\n/storage/emulated/0/Music/Artist/Album/01.flac\n", text)
    }
}
