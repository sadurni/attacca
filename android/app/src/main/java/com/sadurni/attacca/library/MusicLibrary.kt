package com.sadurni.attacca.library

import java.io.File
import java.text.Collator
import java.util.Locale

/** A track of the music folder with the tags the library scan keeps. */
class MusicTrack(val path: String, val tags: SortTags, /** File modification time (ms). */ val modifiedMs: Long) {
    val title: String get() = tags.title.ifBlank { File(path).nameWithoutExtension }
    val folder: String get() = File(path).parent.orEmpty()
}

/**
 * An album: its tracks in disc and track order. [artist] is the album artist, or the one artist
 * of all its tracks; null when several artists share it with no album artist ([compilation]) or
 * when no track names one.
 */
class MusicAlbum(val key: String, val title: String, val artist: String?, val tracks: List<MusicTrack>, val compilation: Boolean = false) {
    val year: Int = tracks.mapNotNull { it.tags.year.takeIf { y -> y > 0 } }.minOrNull() ?: 0
    val genre: String = mostCommon(tracks.map { it.tags.genre })
    val durationMs: Long = tracks.sumOf { it.tags.durationMs }
    val folder: String = tracks.firstOrNull()?.folder.orEmpty()

    /** The newest file of the album (for "recently added"). */
    val addedMs: Long = tracks.maxOfOrNull { it.modifiedMs } ?: 0
}

/** An artist and the albums with their tracks; [tracks] counts only this artist's own. */
class MusicArtist(val name: String, val albums: List<MusicAlbum>, val tracks: List<MusicTrack>) {
    val durationMs: Long = tracks.sumOf { it.tags.durationMs }
}

/** A genre, a decade… and the albums in it. */
class MusicGroup(val name: String, val albums: List<MusicAlbum>) {
    val trackCount: Int = albums.sumOf { it.tracks.size }
}

/**
 * The music folder seen as a music database, from the tags of the library scan (no file is read).
 *
 * Albums go by album artist and album tag (all the discs of a set together), or by folder with
 * [byFolder] (each disc apart), as album shuffle does. An album without album artist whose tracks
 * have several artists is one album per folder, a compilation. Artists are the albums' artists
 * (compilations under no artist), or with [byTrackArtist] every track's own artist.
 */
class MusicLibrary(
    tracks: List<MusicTrack>,
    val byFolder: Boolean = false,
    val byTrackArtist: Boolean = false,
) {
    private val collator: Collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.PRIMARY }
    private val byName = Comparator<String> { a, b -> collator.compare(sortName(a), sortName(b)) }

    val tracks: List<MusicTrack> = tracks.sortedWith(compareBy(byName) { it.title })

    val albums: List<MusicAlbum>

    val artists: List<MusicArtist>

    private val albumOfPath: Map<String, MusicAlbum>

    init {
        val groups = LinkedHashMap<String, MutableList<MusicTrack>>()
        for (t in tracks) groups.getOrPut(albumKey(t)) { ArrayList() }.add(t)
        albums = groups.map { (key, members) ->
            val ordered = members.sortedWith(
                compareBy<MusicTrack> { it.tags.disc }.thenBy { it.tags.track }.thenBy { it.folder }
                    .thenComparator { a, b -> NaturalOrder.compareNatural(File(a.path).name, File(b.path).name) },
            )
            val first = ordered.first()
            val albumArtist = ordered.map { it.tags.albumArtist }.firstOrNull { it.isNotBlank() }
            val artists = ordered.map { it.tags.artist }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }
            val artist = albumArtist ?: artists.singleOrNull()
            MusicAlbum(key, first.tags.album.ifBlank { File(first.folder).name }, artist, ordered, compilation = albumArtist == null && artists.size > 1)
        }.sortedWith(compareBy(byName) { it.title })
        albumOfPath = HashMap<String, MusicAlbum>().also { map -> for (a in albums) for (t in a.tracks) map[t.path] = a }

        artists = if (byTrackArtist) {
            val byArtist = LinkedHashMap<String, MutableList<MusicTrack>>()
            val names = HashMap<String, String>()
            for (t in tracks) {
                val name = t.tags.artist.ifBlank { t.tags.albumArtist }
                if (name.isBlank()) continue
                val key = name.lowercase()
                names.putIfAbsent(key, name)
                byArtist.getOrPut(key) { ArrayList() }.add(t)
            }
            byArtist.map { (key, own) ->
                MusicArtist(names.getValue(key), own.mapNotNull { albumOfPath[it.path] }.distinct().sortedWith(byYear), own)
            }
        } else {
            albums.filter { it.artist != null }.groupBy { it.artist!!.lowercase() }.map { (_, own) ->
                MusicArtist(own.first().artist!!, own.sortedWith(byYear), own.flatMap { it.tracks })
            }
        }.sortedWith(compareBy(byName) { it.name })
    }

    /** Albums by title (as the album lists). */
    val byTitle: Comparator<MusicAlbum> get() = compareBy(byName) { it.title }

    /** Albums by year, then title. */
    val byYear: Comparator<MusicAlbum> get() =
        compareBy<MusicAlbum> { if (it.year == 0) Int.MAX_VALUE else it.year }.thenComparator { a, b -> byName.compare(a.title, b.title) }

    fun albumOf(path: String): MusicAlbum? = albumOfPath[path]

    /** The artist [path] is listed under (its album's, or its own with [byTrackArtist]). */
    fun artistOf(path: String): MusicArtist? {
        val name = if (byTrackArtist) {
            tracks.firstOrNull { it.path == path }?.tags?.let { it.artist.ifBlank { it.albumArtist } }
        } else {
            albumOf(path)?.artist
        } ?: return null
        return artists.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }

    /** Compilations: albums of several artists without album artist. */
    val compilations: List<MusicAlbum> get() = albums.filter { it.compilation }

    /** Genres of the albums (an album goes under its most common genre); "" = no genre. */
    fun genres(): List<MusicGroup> =
        albums.groupBy { it.genre.trim().lowercase() }.map { (_, own) -> MusicGroup(own.first().genre.trim(), own.sortedWith(byArtistThenYear)) }
            .sortedWith(compareBy<MusicGroup> { it.name.isEmpty() }.thenComparator { a, b -> byName.compare(a.name, b.name) })

    /** Decades of the albums, newest first; 0 = no year. */
    fun decades(): List<Pair<Int, List<MusicAlbum>>> =
        albums.groupBy { if (it.year > 0) it.year / 10 * 10 else 0 }
            .map { (decade, own) -> decade to own.sortedWith(byYearThenArtist) }
            .sortedWith(compareBy<Pair<Int, List<MusicAlbum>>> { it.first == 0 }.thenByDescending { it.first })

    /** Albums whose newest file is newest first. */
    fun recentlyAdded(count: Int): List<MusicAlbum> = albums.sortedByDescending { it.addedMs }.take(count)

    private val byArtistThenYear: Comparator<MusicAlbum> get() =
        Comparator<MusicAlbum> { a, b -> byName.compare(a.artist ?: "", b.artist ?: "") }.then(byYear)

    private val byYearThenArtist: Comparator<MusicAlbum> get() =
        compareBy<MusicAlbum> { it.year }.thenComparator { a, b -> byName.compare(a.artist ?: "", b.artist ?: "") }

    private fun albumKey(t: MusicTrack): String {
        if (byFolder) return "folder:" + t.folder
        val album = t.tags.album.trim().lowercase()
        if (album.isEmpty()) return "folder:" + t.folder
        val albumArtist = t.tags.albumArtist.trim().lowercase()
        // Without album artist, tracks of one folder and one album tag are one album whatever
        // their artists (a compilation).
        return if (albumArtist.isNotEmpty()) "album:$albumArtist\u0000$album" else "album:\u0000$album\u0000${t.folder}"
    }

    companion object {
        /** "The Beatles" sorts as "Beatles". */
        fun sortName(name: String): String = name.trim().removePrefix("The ").removePrefix("the ")
    }
}

private fun mostCommon(values: List<String>): String =
    values.filter { it.isNotBlank() }.groupingBy { it.trim() }.eachCount().maxByOrNull { it.value }?.key.orEmpty()
