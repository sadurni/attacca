package com.sadurni.attacca.library

import android.media.MediaMetadataRetriever
import java.io.File
import java.util.concurrent.ConcurrentHashMap

enum class SortField(val needsTags: Boolean) {
    TITLE(true), ARTIST(true), ALBUM(true), TRACK_NUMBER(true), YEAR(true),
    FILE_NAME(false), PATH(false), DATE_MODIFIED(false), DURATION(true),
}

/** The tags list sorting needs, read straight from the file (works before a track was played). */
data class SortTags(
    val title: String,
    val artist: String,
    val album: String,
    val disc: Int,
    val track: Int,
    val year: Int,
    val durationMs: Long,
    val albumArtist: String = "",
    val genre: String = "",
)

object TrackSort {

    private val cache = ConcurrentHashMap<String, SortTags>()

    /** Persistent cache; set at app start. */
    @Volatile
    var db: TagDb? = null

    fun tags(mediaId: String): SortTags = cache.getOrPut(mediaId) { read(mediaId) }

    /** Tags already known in memory, without touching any file. */
    fun cachedTags(mediaId: String): SortTags? = cache[mediaId]

    /**
     * Makes the tags of [mediaIds] available: from memory, then from the database in one query,
     * reading only files never seen (or changed) and storing them. Blocking; call off the main
     * thread. [onProgress] gets how many are ready so far.
     */
    fun warm(mediaIds: List<String>, onProgress: (Int) -> Unit = {}) {
        val missing = mediaIds.filter { !cache.containsKey(it) }.distinct()
        if (missing.isEmpty()) return
        db?.let { db -> runCatching { cache.putAll(db.getAll(missing.filter { !it.contains("://") })) } }
        val toRead = missing.filter { !cache.containsKey(it) }
        val batch = HashMap<String, SortTags>()
        for ((i, id) in toRead.withIndex()) {
            // Another warm (the library scan, a list) may have read it meanwhile.
            if (cache.containsKey(id)) continue
            val tags = read(id)
            cache[id] = tags
            if (!id.contains("://")) batch[id] = tags
            if (batch.size >= WRITE_BATCH) {
                runCatching { db?.putAll(batch) }
                batch.clear()
                onProgress(mediaIds.size - toRead.size + i + 1)
            }
        }
        runCatching { db?.putAll(batch) }
    }

    /**
     * Makes known what the tag database already holds for [mediaIds], without reading any file.
     * Returns whether anything new became known. Blocking; call off the main thread.
     */
    fun warmFromDb(mediaIds: List<String>): Boolean {
        val missing = mediaIds.filter { !cache.containsKey(it) && !it.contains("://") }.distinct()
        if (missing.isEmpty()) return false
        val found = db?.let { db -> runCatching { db.getAll(missing) }.getOrNull() }.orEmpty()
        cache.putAll(found)
        return found.isNotEmpty()
    }

    /** Tracks already read once for a missing length in this run (a file without one is not read again). */
    private val lengthTried = ConcurrentHashMap.newKeySet<String>()

    /**
     * Reads, from the files themselves, the tags of those [mediaIds] whose length is still unknown:
     * never scanned (outside the music folder, not scanned yet) or stored without one. Stores what
     * it reads, so each file is read once. [onBatch] is called every [batch] files read and at the
     * end. Returns whether anything was read. Blocking and slow on long lists: call it on a
     * background thread, after [warmFromDb]. One call at a time: a second one waits and then finds
     * the lengths the first one read.
     */
    @Synchronized
    fun fillMissingLengths(mediaIds: List<String>, batch: Int = 50, onBatch: () -> Unit = {}): Boolean {
        val missing = mediaIds.distinct().filter { id ->
            !id.contains("://") && (cache[id]?.durationMs ?: 0L) <= 0L && lengthTried.add(id)
        }
        if (missing.isEmpty()) return false
        val toStore = HashMap<String, SortTags>()
        for ((i, id) in missing.withIndex()) {
            val tags = read(id)
            val known = cache[id]
            // Keep what was known (an imported library's tags) and add the length.
            val merged = if (known != null && tags.durationMs > 0) known.copy(durationMs = tags.durationMs) else known ?: tags
            cache[id] = merged
            if (merged.durationMs > 0 || known == null) toStore[id] = merged
            if ((i + 1) % batch == 0) {
                runCatching { db?.putAll(toStore) }
                toStore.clear()
                onBatch()
            }
        }
        runCatching { db?.putAll(toStore) }
        onBatch()
        return true
    }

    /** Whether a list sorted by [field] reads naturally in groups (albums, artists…). */
    fun groups(field: SortField): Boolean = field in GROUPING_FIELDS

    /** The group a track belongs to when grouped by [field]; tags must be warm. */
    fun groupKey(mediaId: String, field: SortField): String {
        val folder = File(mediaId).parentFile?.name.orEmpty()
        return when (field) {
            SortField.ARTIST -> tags(mediaId).artist
            SortField.ALBUM -> tags(mediaId).album.ifEmpty { folder }
            SortField.YEAR -> tags(mediaId).year.takeIf { it > 0 }?.toString().orEmpty()
            SortField.PATH -> File(mediaId).parent.orEmpty()
            else -> ""
        }
    }

    private val GROUPING_FIELDS = setOf(SortField.ARTIST, SortField.ALBUM, SortField.YEAR, SortField.PATH)
    private const val WRITE_BATCH = 100

    private fun read(mediaId: String): SortTags {
        val fileName = File(mediaId).nameWithoutExtension
        if (mediaId.contains("://")) return SortTags(fileName, "", "", 0, 0, 0, 0)
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(mediaId)
            fun get(key: Int) = TagText.repair(retriever.extractMetadata(key).orEmpty())
            SortTags(
                title = get(MediaMetadataRetriever.METADATA_KEY_TITLE).ifEmpty { fileName },
                artist = get(MediaMetadataRetriever.METADATA_KEY_ARTIST).ifEmpty { get(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST) },
                album = get(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                disc = leadingNumber(get(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)),
                track = leadingNumber(get(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)),
                year = leadingNumber(get(MediaMetadataRetriever.METADATA_KEY_YEAR).ifEmpty { get(MediaMetadataRetriever.METADATA_KEY_DATE) })
                    .takeIf { it in 1..9999 } ?: yearFromTag(mediaId),
                durationMs = get(MediaMetadataRetriever.METADATA_KEY_DURATION).toLongOrNull() ?: 0,
                albumArtist = get(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                genre = get(MediaMetadataRetriever.METADATA_KEY_GENRE),
            )
        } catch (e: Exception) {
            // A file whose tags and length cannot be read at all is damaged (or no music).
            DamagedTracks.report(mediaId, DamagedTracks.Kind.UNREADABLE)
            SortTags(fileName, "", "", 0, 0, 0, 0)
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * The year read from the tag itself: Android's reader leaves it out of most MP3s (it knows
     * only the older year field, not the date ID3v2.4 keeps it in) and gives a whole date
     * ("19830101T…") for others. 0 when there is none.
     */
    private fun yearFromTag(mediaId: String): Int =
        runCatching { leadingNumber(TagEditor.read(File(mediaId)).year) }.getOrDefault(0).takeIf { it in 1..9999 } ?: 0

    /** "3/12" → 3, "1972-06-06" → 1972. */
    internal fun leadingNumber(value: String): Int = value.trim().takeWhile { it.isDigit() }.toIntOrNull() ?: 0

    /**
     * New order (indices into [mediaIds]) sorted by [field]. Stable, so equal keys keep their
     * current order; ties on album/artist fall back to disc and track number.
     */
    fun order(mediaIds: List<String>, field: SortField, descending: Boolean): List<Int> {
        if (field.needsTags) warm(mediaIds)
        val text = Comparator<String> { a, b -> NaturalOrder.compareNatural(a, b) }
        val byAlbumOrder = compareBy<SortTags>({ it.disc }, { it.track })
        val comparator: Comparator<Int> = when (field) {
            SortField.TITLE -> compareBy(text) { tags(mediaIds[it]).title }
            SortField.ARTIST -> Comparator { a, b ->
                val ta = tags(mediaIds[a])
                val tb = tags(mediaIds[b])
                text.compare(ta.artist, tb.artist).takeIf { it != 0 }
                    ?: text.compare(ta.album, tb.album).takeIf { it != 0 }
                    ?: byAlbumOrder.compare(ta, tb)
            }
            SortField.ALBUM -> Comparator { a, b ->
                val ta = tags(mediaIds[a])
                val tb = tags(mediaIds[b])
                text.compare(ta.album, tb.album).takeIf { it != 0 } ?: byAlbumOrder.compare(ta, tb)
            }
            SortField.TRACK_NUMBER -> Comparator { a, b -> byAlbumOrder.compare(tags(mediaIds[a]), tags(mediaIds[b])) }
            SortField.YEAR -> compareBy { tags(mediaIds[it]).year }
            SortField.FILE_NAME -> compareBy(text) { File(mediaIds[it]).name }
            SortField.PATH -> compareBy(text) { mediaIds[it] }
            SortField.DATE_MODIFIED -> compareBy { File(mediaIds[it]).lastModified() }
            SortField.DURATION -> compareBy { tags(mediaIds[it]).durationMs }
        }
        val sorted = mediaIds.indices.sortedWith(comparator)
        return if (descending) sorted.reversed() else sorted
    }
}
