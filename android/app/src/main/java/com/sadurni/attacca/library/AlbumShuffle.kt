package com.sadurni.attacca.library

import java.io.File
import kotlin.random.Random

/**
 * "Shuffle by album": albums in random order, tracks of each album in their original order.
 *
 * Tracks are grouped by album key — consecutive runs of the same key, so an album split across a
 * list stays as separate blocks rather than being merged.
 */
object AlbumShuffle {

    /** Album key of a queue entry: its folder. */
    fun albumKey(mediaId: String): String = File(mediaId).parent.orEmpty()

    /**
     * Whether [next] is the track that follows [current] on its album, so the two play on as the
     * album does: same folder and, by their tags, the next track number of the disc (or the first
     * of the next disc), else the next audio file of the folder by name. Tracks of one album that
     * a generated or shuffled list puts together are not. May list the folder: off the main thread.
     */
    fun followsOnAlbum(current: String, next: String): Boolean {
        if (albumKey(current) != albumKey(next)) return false
        val a = TrackSort.cachedTags(current)
        val b = TrackSort.cachedTags(next)
        if (a != null && b != null && a.track > 0 && b.track > 0) {
            return (b.disc == a.disc && b.track == a.track + 1) || (b.disc == a.disc + 1 && b.track == 1)
        }
        val names = File(current).parentFile?.listFiles()
            ?.filter { it.isFile && AudioFiles.isAudio(it) }
            ?.map { it.name }
            ?.sortedWith(String.CASE_INSENSITIVE_ORDER)
            ?: return false
        val at = names.indexOf(File(current).name)
        return at >= 0 && names.getOrNull(at + 1) == File(next).name
    }

    /** Play order (indices into [keys]) with albums shuffled. */
    fun order(keys: List<String>, random: Random = Random.Default): IntArray {
        val groups = ArrayList<IntRange>()
        var start = 0
        for (i in 1..keys.size) {
            if (i == keys.size || keys[i] != keys[start]) {
                groups.add(start until i)
                start = i
            }
        }
        return groups.shuffled(random).flatMap { it.toList() }.toIntArray()
    }

    /** Albums are folders (each disc of a set apart) rather than album tags; set from the settings. */
    @Volatile
    var byFolder = false

    /**
     * The album a track belongs to for album shuffling (each folder with [byFolder]): its album tag (with the album artist, so
     * two "Greatest Hits" stay apart), or its folder when untagged. Uses tags already loaded
     * (see [TrackSort.warm]).
     */
    fun tagAlbumKey(mediaId: String): String {
        if (byFolder) return albumKey(mediaId)
        val tags = TrackSort.cachedTags(mediaId)
        return if (tags != null && tags.album.isNotEmpty()) "${tags.albumArtist.lowercase()}|${tags.album.lowercase()}" else albumKey(mediaId)
    }

    /**
     * Who an album is by, to keep albums of the same artist apart: the album artist, else the
     * track artist when all tracks share it; an album of several artists stands on its own.
     * Untagged albums count by the folder above the album folder (Artist/Album layouts).
     */
    private fun albumArtistKey(albumKey: String, tracks: List<String>): String {
        val tags = tracks.map { TrackSort.cachedTags(it) }
        tags.firstNotNullOfOrNull { it?.albumArtist?.trim()?.takeIf(String::isNotEmpty) }?.let { return it.lowercase() }
        val artists = tags.mapNotNull { it?.artist?.trim()?.lowercase()?.takeIf(String::isNotEmpty) }.distinct()
        return when {
            artists.size == 1 -> artists[0]
            artists.size > 1 -> "album:$albumKey"
            else -> "folder:" + (File(tracks[0]).parentFile?.parent ?: albumKey)
        }
    }

    /**
     * Every album gathered in one block wherever its tracks are in the list, tracks in album
     * order (disc, track, file name), albums in random order with the albums of each artist
     * spread over the whole list (see [spread]). Warm the tags first.
     */
    fun gatheredOrder(mediaIds: List<String>, random: Random = Random.Default): List<Int> =
        gathered(mediaIds, firstAlbumOf = -1, random).flatten()

    /**
     * [gatheredOrder] with the album of [firstIndex] (playing) first, starting at that track;
     * the tracks of that album before it close the order.
     */
    fun gatheredOrderStartingWith(mediaIds: List<String>, firstIndex: Int, random: Random = Random.Default): IntArray {
        if (firstIndex !in mediaIds.indices) return gatheredOrder(mediaIds, random).toIntArray()
        val albums = gathered(mediaIds, firstAlbumOf = firstIndex, random)
        val album = albums[0]
        val position = album.indexOf(firstIndex)
        return (album.drop(position) + albums.drop(1).flatten() + album.take(position)).toIntArray()
    }

    /** The albums of a list: each its track indices in album order, and who each album is by. Warm the tags first. */
    class Albums(val keys: List<String>, val tracks: List<List<Int>>, val artists: List<String>)

    fun albums(mediaIds: List<String>): Albums {
        val byKey = LinkedHashMap<String, MutableList<Int>>()
        mediaIds.forEachIndexed { i, id -> byKey.getOrPut(tagAlbumKey(id)) { ArrayList() }.add(i) }
        val inAlbum = compareBy<Int>(
            { TrackSort.cachedTags(mediaIds[it])?.disc ?: 0 },
            { TrackSort.cachedTags(mediaIds[it])?.track ?: 0 },
        ).thenComparator { a, b -> NaturalOrder.compareNatural(File(mediaIds[a]).name, File(mediaIds[b]).name) }
        val keys = byKey.keys.toList()
        val albums = byKey.values.map { it.sortedWith(inAlbum) }
        return Albums(keys, albums, keys.mapIndexed { i, key -> albumArtistKey(key, albums[i].map(mediaIds::get)) })
    }

    /** Albums (track indices in album order) in play order; the album of [firstAlbumOf] first if ≥ 0. */
    private fun gathered(mediaIds: List<String>, firstAlbumOf: Int, random: Random): List<List<Int>> {
        val byKey = LinkedHashMap<String, MutableList<Int>>()
        mediaIds.forEachIndexed { i, id -> byKey.getOrPut(tagAlbumKey(id)) { ArrayList() }.add(i) }
        val inAlbum = compareBy<Int>(
            { TrackSort.cachedTags(mediaIds[it])?.disc ?: 0 },
            { TrackSort.cachedTags(mediaIds[it])?.track ?: 0 },
        ).thenComparator { a, b -> NaturalOrder.compareNatural(File(mediaIds[a]).name, File(mediaIds[b]).name) }
        val keys = byKey.keys.toList()
        val albums = byKey.values.map { it.sortedWith(inAlbum) }
        val artists = keys.mapIndexed { i, key -> albumArtistKey(key, albums[i].map(mediaIds::get)) }
        val first = if (firstAlbumOf >= 0) keys.indexOf(tagAlbumKey(mediaIds[firstAlbumOf])) else -1
        return spread(artists, random, first).map(albums::get)
    }

    /**
     * A random order of albums (indices into [artists], each album's artist key) that keeps the
     * albums of each artist as far apart as the list allows, with no pile of leftovers at the end:
     * each artist's albums are placed at evenly spaced random points along the whole list, then
     * any albums still too close to another of the same artist are swapped with one that fits.
     * The album [first], if ≥ 0, stays first.
     */
    fun spread(artists: List<String>, random: Random = Random.Default, first: Int = -1): List<Int> {
        val n = artists.size
        if (n < 2) return artists.indices.toList()
        val points = DoubleArray(n)
        for (albums in artists.indices.groupBy { artists[it] }.values) {
            val k = albums.size
            val offset = random.nextDouble() / k
            albums.shuffled(random).forEachIndexed { j, album ->
                // A little jitter so the spacing does not look regular.
                points[album] = offset + (j + (random.nextDouble() - 0.5) * 0.5) / k
            }
        }
        val most = artists.groupingBy { it }.eachCount().values.max()
        // The widest gap the busiest artist leaves room for, at most 3 albums in between.
        val gap = (1..3).lastOrNull { most * (it + 1) <= n } ?: 1
        // Take albums in the order of their points, skipping any whose artist played within the
        // last [gap] albums; when none fits, the one whose artist played longest ago.
        val pending = artists.indices.sortedBy { points[it] }.toMutableList()
        val order = ArrayList<Int>(n)
        val lastAt = HashMap<String, Int>()
        fun take(album: Int) {
            pending.remove(album)
            lastAt[artists[album]] = order.size
            order.add(album)
        }
        val left = artists.groupingBy { it }.eachCount().toMutableMap()
        if (first in artists.indices) take(first).also { left.merge(artists[first], -1, Int::plus) }
        while (pending.isNotEmpty()) {
            val position = order.size
            fun fits(album: Int) = position - (lastAt[artists[album]] ?: -n) > gap
            // An artist with so many albums left that it needs every gap-th place from now on
            // goes now; otherwise its albums would end up together at the end.
            val slots = pending.size
            val album = pending.firstOrNull { fits(it) && (left.getValue(artists[it]) - 1) * (gap + 1) + 1 >= slots }
                ?: pending.firstOrNull(::fits)
                ?: pending.minBy { lastAt[artists[it]] ?: -n }
            left.merge(artists[album], -1, Int::plus)
            take(album)
        }
        val fixedStart = if (first in artists.indices) 1 else 0
        repeat(2) { separate(order, artists, gap, fixedStart) }
        return order
    }

    /** Swaps albums that have the same artist within [gap] positions with ones that fit. */
    private fun separate(order: MutableList<Int>, artists: List<String>, gap: Int, fixedStart: Int) {
        val n = order.size
        fun clashes(position: Int): Boolean {
            val artist = artists[order[position]]
            for (k in maxOf(0, position - gap)..minOf(n - 1, position + gap)) {
                if (k != position && artists[order[k]] == artist) return true
            }
            return false
        }
        for (i in maxOf(1, fixedStart) until n) {
            if (!clashes(i)) continue
            // Nearest candidate first, after then before.
            for (distance in 1 until n) {
                val swapped = listOf(i + distance, i - distance).firstOrNull { j ->
                    if (j < fixedStart || j >= n) return@firstOrNull false
                    order[i] = order[j].also { order[j] = order[i] }
                    val fits = !clashes(i) && !clashes(j)
                    if (!fits) order[i] = order[j].also { order[j] = order[i] }
                    fits
                }
                if (swapped != null) break
            }
        }
    }
}
