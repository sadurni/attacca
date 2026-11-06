package com.sadurni.attacca.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AlbumFollowTest {

    @Test
    fun untaggedTracksFollowByFileName() {
        val album = Files.createTempDirectory("album").toFile()
        val other = Files.createTempDirectory("other").toFile()
        try {
            val (one, two, three) = listOf("01 One.mp3", "02 Two.mp3", "03 Three.mp3").map { File(album, it).apply { writeText("x") } }
            File(album, "cover.jpg").writeText("x")
            val elsewhere = File(other, "04 Four.mp3").apply { writeText("x") }
            assertTrue(AlbumShuffle.followsOnAlbum(one.path, two.path))
            assertTrue(AlbumShuffle.followsOnAlbum(two.path, three.path))
            // Brought together by a list, not played on as the album.
            assertFalse(AlbumShuffle.followsOnAlbum(one.path, three.path))
            assertFalse(AlbumShuffle.followsOnAlbum(three.path, one.path))
            assertFalse(AlbumShuffle.followsOnAlbum(three.path, elsewhere.path))
        } finally {
            album.deleteRecursively()
            other.deleteRecursively()
        }
    }
}
