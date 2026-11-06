package com.sadurni.attacca.chain

import com.sadurni.attacca.AttaccaApp
import com.sadurni.attacca.library.AlbumShuffle
import com.sadurni.attacca.library.SortField
import com.sadurni.attacca.library.TrackSort

/**
 * New orders for a list of tracks, the same for the playing list and for a saved one that is not
 * playing: chained, chained by albums, albums shuffled, sorted. Each gives indices into the ids,
 * or null when it could not be done. Blocking (they read tags and may analyse tracks); call from
 * a background dispatcher.
 */
object ListOrders {

    /**
     * Each track leading into the next, starting with [first]; tracks never analysed are analysed
     * first ([onProgress] gets how many are ready).
     */
    suspend fun chained(app: AttaccaApp, ids: List<String>, first: Int, onProgress: (Int) -> Unit = {}): List<Int>? {
        if (ids.size < 2) return null
        val edges = app.chainAnalyzer.edgesFor(ids, onProgress)
        TrackSort.warm(ids)
        val tracks = ids.map { id ->
            val e = edges[id]
            ChainTrack(e?.head, e?.tail, TrackSort.tags(id).artist)
        }
        return ChainEngine.order(tracks, first.coerceIn(ids.indices), app.settings.chain.value.artistSpacing)
    }

    /**
     * Whole albums, each album's last track leading into the next album's first, starting with
     * the album of [current] from that track (its earlier tracks close the list); albums of the
     * same artist kept apart.
     */
    suspend fun albumsChained(app: AttaccaApp, ids: List<String>, current: Int): List<Int>? {
        if (ids.size < 2) return null
        TrackSort.warm(ids)
        val albums = AlbumShuffle.albums(ids)
        if (albums.tracks.size < 2) return null
        val at = current.coerceIn(ids.indices)
        val first = albums.tracks.indexOfFirst { at in it }
        val albumOrder = app.chainLists.chainAlbums(albums.tracks.map { t -> t.map(ids::get) }, albums.artists, first) ?: return null
        val playing = albums.tracks[first]
        val position = playing.indexOf(at)
        return playing.drop(position) + albumOrder.filter { it != first }.flatMap { albums.tracks[it] } + playing.take(position)
    }

    /** Albums in random order, each in its own order (see [AlbumShuffle.gatheredOrder]). */
    fun albumsShuffled(ids: List<String>): List<Int>? {
        if (ids.size < 2) return null
        TrackSort.warm(ids)
        return AlbumShuffle.gatheredOrder(ids)
    }

    /** Sorted by [field]. */
    fun sorted(ids: List<String>, field: SortField, descending: Boolean): List<Int>? {
        if (ids.size < 2) return null
        return TrackSort.order(ids, field, descending)
    }

    /** How a list reads in groups after chaining by albums or shuffling albums. */
    fun albumGrouping(): SortField = if (AlbumShuffle.byFolder) SortField.PATH else SortField.ALBUM
}
