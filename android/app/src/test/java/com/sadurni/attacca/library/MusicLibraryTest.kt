package com.sadurni.attacca.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MusicLibraryTest {

    private fun track(path: String, title: String, artist: String, album: String, disc: Int = 1, no: Int = 1, year: Int = 0, albumArtist: String = "", genre: String = "") =
        MusicTrack(path, SortTags(title, artist, album, disc, no, year, 180_000, albumArtist, genre), 0)

    private val tracks = listOf(
        // A double album in two folders, with album artist.
        track("/m/Queen/Live/CD2/01.mp3", "Two-one", "Queen", "Live", disc = 2, no = 1, year = 1986, albumArtist = "Queen", genre = "Rock"),
        track("/m/Queen/Live/CD1/01.mp3", "One-one", "Queen", "Live", disc = 1, no = 1, year = 1986, albumArtist = "Queen", genre = "Rock"),
        track("/m/Queen/Live/CD1/02.mp3", "One-two", "Queen", "Live", disc = 1, no = 2, year = 1986, albumArtist = "Queen", genre = "Rock"),
        // A compilation without album artist.
        track("/m/Hits/01.mp3", "Song A", "Alpha", "Hits", no = 1, year = 2001, genre = "Pop"),
        track("/m/Hits/02.mp3", "Song B", "Beta", "Hits", no = 2, year = 2001, genre = "Pop"),
        // An album without album tag.
        track("/m/Loose/x.mp3", "X", "The Band", "", year = 1975),
    )

    @Test
    fun albumsKeepTheDiscsOfASetTogetherInOrder() {
        val lib = MusicLibrary(tracks)
        val live = lib.albums.single { it.title == "Live" }
        assertEquals(listOf("One-one", "One-two", "Two-one"), live.tracks.map { it.title })
        assertEquals("Queen", live.artist)
        assertEquals(1986, live.year)
        assertEquals("Rock", live.genre)
    }

    @Test
    fun byFolderEachDiscIsAnAlbum() {
        val lib = MusicLibrary(tracks, byFolder = true)
        assertEquals(2, lib.albums.count { it.title == "Live" })
    }

    @Test
    fun aCompilationHasNoArtistAndIsNotUnderTheArtists() {
        val lib = MusicLibrary(tracks)
        val hits = lib.albums.single { it.title == "Hits" }
        assertNull(hits.artist)
        assertEquals(listOf(hits), lib.compilations)
        // "The Band" sorts as "Band"; the album without tag is named after its folder.
        assertEquals(listOf("The Band", "Queen"), lib.artists.map { it.name })
        assertEquals("Loose", lib.albums.single { it.artist == "The Band" }.title)
    }

    @Test
    fun byTrackArtistTheCompilationsArtistsAppear() {
        val lib = MusicLibrary(tracks, byTrackArtist = true)
        assertEquals(listOf("Alpha", "The Band", "Beta", "Queen"), lib.artists.map { it.name })
        assertEquals(1, lib.artists.single { it.name == "Alpha" }.tracks.size)
        assertEquals("Hits", lib.artistOf("/m/Hits/02.mp3")?.albums?.single()?.title)
    }

    @Test
    fun genresAndDecades() {
        val lib = MusicLibrary(tracks)
        assertEquals(listOf("Pop", "Rock", ""), lib.genres().map { it.name })
        assertEquals(listOf(2000, 1980, 1970), lib.decades().map { it.first })
    }
}
