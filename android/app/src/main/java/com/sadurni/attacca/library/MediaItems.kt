package com.sadurni.attacca.library

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.sadurni.attacca.analysis.Trim
import java.io.File

/**
 * Builds MediaItems whose media ID is the file path (or URL), so items can be rebuilt from the ID
 * alone (session callbacks, saved queue, playlists).
 */
object MediaItems {

    const val EXTRA_FILE_NAME = "attacca.fileName"
    const val EXTRA_PLAYLIST_TITLE = "attacca.playlistTitle"

    /** Set when the clip comes from the playlist (#EXTVLCOPT) rather than silence trimming. */
    const val EXTRA_EXPLICIT_CLIP = "attacca.explicitClip"

    /** Crossfade length after the clip end (the clip ends where the crossfade starts). */
    const val EXTRA_CROSSFADE_MS = "attacca.crossfadeMs"
    const val EXTRA_CROSSFADE_SHAPE = "attacca.crossfadeShape"

    /** Added after the list's own tracks so it does not end (see endless lists). */
    const val EXTRA_EXTENSION = "attacca.extension"

    fun isExtension(item: MediaItem): Boolean = item.mediaMetadata.extras?.getBoolean(EXTRA_EXTENSION) == true

    fun asExtension(item: MediaItem): MediaItem {
        val extras = Bundle(item.mediaMetadata.extras ?: Bundle()).apply { putBoolean(EXTRA_EXTENSION, true) }
        return item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(extras).build()).build()
    }

    fun fromLocation(
        location: String,
        playlistTitle: String? = null,
        startMs: Long? = null,
        endMs: Long? = null,
    ): MediaItem {
        val isUrl = location.contains("://")
        val uri = if (isUrl) Uri.parse(location) else Uri.fromFile(File(location))
        val fileName = if (isUrl) location.substringAfterLast('/') else File(location).nameWithoutExtension
        val extras = Bundle().apply {
            putString(EXTRA_FILE_NAME, fileName)
            playlistTitle?.let { putString(EXTRA_PLAYLIST_TITLE, it) }
            if (startMs != null || endMs != null) putBoolean(EXTRA_EXPLICIT_CLIP, true)
        }
        // Title/artist are left unset so the file's own tags fill them in once loaded; the item's
        // metadata would otherwise take precedence over the tags.
        val metadata = MediaMetadata.Builder().setExtras(extras)
        if (!isUrl) {
            AudioFiles.cachedFolderCover(File(location).parentFile ?: File("/"))?.let {
                metadata.setArtworkUri(Uri.fromFile(it))
            }
        }
        val builder = MediaItem.Builder()
            .setMediaId(location)
            .setUri(uri)
            .setMediaMetadata(metadata.build())
        if (startMs != null || endMs != null) {
            builder.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(startMs ?: 0L)
                    .setEndPositionMs(endMs ?: C.TIME_END_OF_SOURCE)
                    .build(),
            )
        }
        return builder.build()
    }

    /** Clip points of an item, or null when it plays whole. */
    fun clipStartMs(item: MediaItem): Long? =
        item.clippingConfiguration.startPositionMs.takeIf { it > 0 }

    fun clipEndMs(item: MediaItem): Long? =
        item.clippingConfiguration.endPositionMs.takeIf { it != C.TIME_END_OF_SOURCE }

    fun hasExplicitClip(item: MediaItem): Boolean =
        item.mediaMetadata.extras?.getBoolean(EXTRA_EXPLICIT_CLIP) == true

    /** Playlist clip points to persist; automatic trims are recomputed instead. */
    fun explicitClipStartMs(item: MediaItem): Long? = if (hasExplicitClip(item)) clipStartMs(item) else null

    fun explicitClipEndMs(item: MediaItem): Long? = if (hasExplicitClip(item)) clipEndMs(item) else null

    /** The silence trim currently applied to an item. */
    fun autoTrimOf(item: MediaItem): Trim =
        if (hasExplicitClip(item)) Trim.NONE else Trim(clipStartMs(item), clipEndMs(item), crossfadeMsOf(item), crossfadeShapeOf(item))

    fun crossfadeMsOf(item: MediaItem): Int = item.mediaMetadata.extras?.getInt(EXTRA_CROSSFADE_MS, 0) ?: 0

    fun crossfadeShapeOf(item: MediaItem): com.sadurni.attacca.dsp.CrossfadeShape =
        com.sadurni.attacca.dsp.CrossfadeShape.decode(item.mediaMetadata.extras?.getFloatArray(EXTRA_CROSSFADE_SHAPE))
            ?: com.sadurni.attacca.dsp.CrossfadeShape.CROSSFADE

    /**
     * Title, artist, album and the rest filled in from the tag database, for the tracks it knows:
     * what the media session shows (car stereos, watches, the lock screen) and its list of the
     * tracks come from the items, and a file's own tags reach the player only once it is being
     * decoded, so without this they read "unknown" there. A track without a title tag gets its
     * playlist title or file name. Items with a title already, or not in the database, stay as
     * they are. Blocking (one query for the whole list).
     */
    fun withTags(items: List<MediaItem>): List<MediaItem> {
        val local = items.mapNotNull { it.mediaId.takeIf { id -> !id.contains("://") && it.mediaMetadata.title == null } }
        if (local.isEmpty()) return items
        runCatching { TrackSort.warmFromDb(local) }
        return items.map { item ->
            if (item.mediaMetadata.title != null) return@map item
            val tags = TrackSort.cachedTags(item.mediaId) ?: return@map item
            val m = item.mediaMetadata.buildUpon()
            m.setTitle(tags.title.ifBlank { null } ?: item.mediaMetadata.extras?.getString(EXTRA_PLAYLIST_TITLE) ?: File(item.mediaId).nameWithoutExtension)
            tags.artist.ifBlank { null }?.let(m::setArtist)
            tags.album.ifBlank { null }?.let(m::setAlbumTitle)
            tags.albumArtist.ifBlank { null }?.let(m::setAlbumArtist)
            tags.genre.ifBlank { null }?.let(m::setGenre)
            if (tags.track > 0) m.setTrackNumber(tags.track)
            if (tags.disc > 0) m.setDiscNumber(tags.disc)
            if (tags.year > 0) m.setRecordingYear(tags.year)
            if (tags.durationMs > 0) m.setDurationMs(tags.durationMs)
            item.buildUpon().setMediaMetadata(m.build()).build()
        }
    }

    fun withAutoTrim(item: MediaItem, trim: Trim): MediaItem {
        val extras = Bundle(item.mediaMetadata.extras ?: Bundle()).apply {
            if (trim.crossfadeMs > 0) putInt(EXTRA_CROSSFADE_MS, trim.crossfadeMs) else remove(EXTRA_CROSSFADE_MS)
            if (trim.crossfadeMs > 0 && trim.crossfadeShape != com.sadurni.attacca.dsp.CrossfadeShape.CROSSFADE) {
                putFloatArray(EXTRA_CROSSFADE_SHAPE, trim.crossfadeShape.encode())
            } else {
                remove(EXTRA_CROSSFADE_SHAPE)
            }
        }
        return item.buildUpon()
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(trim.startMs ?: 0L)
                    .setEndPositionMs(trim.endMs ?: C.TIME_END_OF_SOURCE)
                    .build(),
            )
            .setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(extras).build())
            .build()
    }

    fun fromFiles(files: List<File>): List<MediaItem> {
        // Cover lookup once per folder rather than once per file.
        val covers = HashMap<File?, Uri?>()
        return files.map { file ->
            val folder = file.parentFile
            val cover = covers.getOrPut(folder) {
                folder?.let(AudioFiles::cachedFolderCover)?.let { Uri.fromFile(it) }
            }
            val extras = Bundle().apply { putString(EXTRA_FILE_NAME, file.nameWithoutExtension) }
            MediaItem.Builder()
                .setMediaId(file.absolutePath)
                .setUri(Uri.fromFile(file))
                .setMediaMetadata(MediaMetadata.Builder().setExtras(extras).setArtworkUri(cover).build())
                .build()
        }
    }

    fun fromPlaylist(entries: List<PlaylistEntry>): List<MediaItem> =
        entries.map { fromLocation(it.location, it.title, it.startMs, it.endMs) }

    /** Best display title: tag title, then #EXTINF title, then file name. */
    fun displayTitle(item: MediaItem, metadata: MediaMetadata = item.mediaMetadata): String =
        metadata.title?.toString()
            ?: item.mediaMetadata.extras?.getString(EXTRA_PLAYLIST_TITLE)
            ?: item.mediaMetadata.extras?.getString(EXTRA_FILE_NAME)
            ?: item.mediaId.substringAfterLast('/')
}
