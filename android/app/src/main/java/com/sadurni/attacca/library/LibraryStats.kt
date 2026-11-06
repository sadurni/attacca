package com.sadurni.attacca.library

/** Figures of the music folder: how many tracks, artists, albums, genres, formats and so on. */
data class LibraryStats(
    val tracks: Int,
    val bytes: Long,
    val durationMs: Long,
    val artists: Int,
    val albumArtists: Int,
    val albums: Int,
    val genres: Int,
    val firstYear: Int?,
    val lastYear: Int?,
    /** Tracks with neither title nor artist in their tags. */
    val untagged: Int,
    /** File types (extension in capitals) with their number of tracks, most used first. */
    val formats: List<Pair<String, Int>>,
    /** Tracks the scans knew that are not on this device now (see [TagDb.markMissing]). */
    val missing: Int = 0,
) {
    /** Gathers the figures one track at a time. */
    class Counter {
        private var tracks = 0
        private var bytes = 0L
        private var durationMs = 0L
        private var untagged = 0
        private var firstYear: Int? = null
        private var lastYear: Int? = null
        private val artists = HashSet<String>()
        private val albumArtists = HashSet<String>()
        private val albums = HashSet<String>()
        private val genres = HashSet<String>()
        private val formats = HashMap<String, Int>()

        fun add(path: String, size: Long, artist: String, album: String, albumArtist: String, genre: String, year: Int, durationMs: Long) {
            tracks++
            bytes += size
            this.durationMs += durationMs
            val artistKey = artist.trim().lowercase()
            val albumArtistKey = albumArtist.trim().lowercase()
            if (artistKey.isNotEmpty()) artists.add(artistKey)
            if (albumArtistKey.isNotEmpty()) albumArtists.add(albumArtistKey)
            // An album is its name under its album artist (or artist): two "Greatest Hits" differ.
            if (album.isNotBlank()) albums.add(albumArtistKey.ifEmpty { artistKey } + "\u0000" + album.trim().lowercase())
            // Several genres in one tag ("Rock; Pop") count each.
            genre.split(';', '/', ',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.forEach(genres::add)
            if (year > 0) {
                firstYear = minOf(firstYear ?: year, year)
                lastYear = maxOf(lastYear ?: year, year)
            }
            if (artistKey.isEmpty() && album.isBlank()) untagged++
            val ext = path.substringAfterLast('/').substringAfterLast('.', "").uppercase().ifEmpty { "?" }
            formats[ext] = (formats[ext] ?: 0) + 1
        }

        fun result() = LibraryStats(
            tracks, bytes, durationMs, artists.size, albumArtists.size, albums.size, genres.size,
            firstYear, lastYear, untagged,
            formats.entries.sortedByDescending { it.value }.map { it.key to it.value },
        )
    }
}
