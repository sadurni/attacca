package com.sadurni.attacca.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.sadurni.attacca.AttaccaApp
import com.sadurni.attacca.R
import com.sadurni.attacca.library.AudioFiles
import com.sadurni.attacca.library.IncludedFavorites
import com.sadurni.attacca.library.ListLoader
import com.sadurni.attacca.library.ListSource
import com.sadurni.attacca.library.MediaItems
import com.sadurni.attacca.library.TrackSort
import com.sadurni.attacca.library.UserLists
import java.io.File

/**
 * What a car (Android Auto) or another media browser sees of Attacca: the current list, the
 * user's lists, the generated ones, the lists played lately and the music folder, each down to
 * its tracks.
 *
 * Ids: `root`; sections `queue`, `mine`, `generated`, `recent`, `folders`; a playlist `L|path`
 * (browsable, and playable as a whole); a folder `D|path` (its subfolders, playlists and tracks;
 * playable with its subfolders); the current list's entry `Q|index`; a track of a list or of a
 * folder `T|L|path|index` / `T|D|path|index`.
 */
class CarLibrary(private val app: AttaccaApp) {

    /** What to play for a chosen item: the whole list it belongs to, from that item. */
    class Choice(val name: String, val source: ListSource?, val items: List<MediaItem>, val startIndex: Int)

    fun root(): MediaItem = browsable(ROOT, app.getString(R.string.app_name), MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)

    /** The children of [parentId], all of them (the caller pages them). Blocking. */
    fun children(parentId: String, player: Player?): List<MediaItem> = when {
        parentId == ROOT -> listOfNotNull(
            browsable(QUEUE, app.getString(R.string.nav_queue), MediaMetadata.MEDIA_TYPE_PLAYLIST).takeIf { (player?.mediaItemCount ?: 0) > 0 },
            browsable(MINE, app.getString(R.string.lists_mine), MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
            browsable(GENERATED, app.getString(R.string.lists_generated), MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
            browsable(RECENT, app.getString(R.string.lists_recent), MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
            app.settings.musicFolder?.takeIf { File(it).isDirectory }?.let {
                browsable(folderId(File(it)), app.getString(R.string.nav_folders), MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
            },
        )
        parentId == QUEUE -> player?.let { p ->
            List(p.mediaItemCount) { i ->
                val item = p.getMediaItemAt(i)
                playable("Q|$i", MediaItems.displayTitle(item), subtitleOf(item.mediaId))
            }
        }.orEmpty()
        parentId == MINE -> (listOf(UserLists.favorites, IncludedFavorites.file).filter { it.exists() } + UserLists.all().filter { it != UserLists.favorites })
            .map {
                listItem(it, when (it) {
                    UserLists.favorites -> app.getString(R.string.favorites)
                    IncludedFavorites.file -> app.getString(R.string.favorites_included)
                    else -> it.nameWithoutExtension
                })
            }
        parentId == GENERATED -> app.chainLists.generated().map { listItem(it, it.nameWithoutExtension) }
        parentId == RECENT -> app.listHistory.all().mapNotNull { source ->
            when (source) {
                is ListSource.Playlist -> File(source.path).takeIf { it.isFile }?.let { listItem(it, source.name) }
                is ListSource.Folder -> File(source.path).takeIf { it.isDirectory }?.let { folderItem(it) }
            }
        }
        parentId.startsWith("L|") -> {
            val path = parentId.substring(2)
            tracks(ListLoader.load(ListSource.Playlist(path))) { i -> "T|L|$path|$i" }
        }
        parentId.startsWith("D|") -> {
            val folder = File(parentId.substring(2))
            val entries = AudioFiles.listFolder(folder)
            val audio = entries.filter { it.isFile && AudioFiles.isAudio(it) }
            entries.filter { it.isDirectory }.map(::folderItem) +
                entries.filter { it.isFile && AudioFiles.isPlaylist(it) }.map { listItem(it, it.nameWithoutExtension) } +
                tracks(MediaItems.fromFiles(audio)) { i -> "T|D|${folder.path}|$i" }
        }
        else -> emptyList()
    }

    /** The list behind a chosen [mediaId], starting at it; null when it is not one of ours. Blocking. */
    fun choose(mediaId: String, player: Player?): Choice? = when {
        mediaId.startsWith("Q|") -> player?.let { p ->
            val index = mediaId.substring(2).toIntOrNull()?.takeIf { it in 0 until p.mediaItemCount } ?: return null
            Choice(app.currentListName.value, app.currentListSource.value, List(p.mediaItemCount) { p.getMediaItemAt(it) }, index)
        }
        mediaId.startsWith("L|") -> {
            val source = ListSource.Playlist(mediaId.substring(2))
            withResumePoint(source)
        }
        mediaId.startsWith("D|") -> {
            val source = ListSource.Folder(mediaId.substring(2), recursive = true)
            withResumePoint(source)
        }
        mediaId.startsWith("T|L|") || mediaId.startsWith("T|D|") -> {
            val rest = mediaId.substring(4)
            val path = rest.substringBeforeLast('|')
            val index = rest.substringAfterLast('|').toIntOrNull() ?: return null
            val source = if (mediaId[2] == 'L') ListSource.Playlist(path) else ListSource.Folder(path, recursive = false)
            val items = ListLoader.load(source)
            if (index !in items.indices) null else Choice(source.name, source, items, index)
        }
        else -> null
    }

    private fun withResumePoint(source: ListSource): Choice? {
        val items = ListLoader.load(source)
        if (items.isEmpty()) return null
        val point = app.resumeStore.get(source)
        return Choice(source.name, source, items, app.resumeStore.startIndex(point, items.map { it.mediaId }))
    }

    private fun tracks(items: List<MediaItem>, id: (Int) -> String): List<MediaItem> {
        // Titles and artists from the tag cache when known (one query), else the file name.
        TrackSort.warmFromDb(items.map { it.mediaId })
        return items.mapIndexed { i, item -> playable(id(i), MediaItems.displayTitle(item), subtitleOf(item.mediaId)) }
    }

    private fun subtitleOf(mediaId: String): String? = TrackSort.cachedTags(mediaId)?.let { tags ->
        listOf(tags.artist, tags.album).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { null }
    } ?: File(mediaId).parentFile?.name

    private fun listItem(file: File, title: String): MediaItem =
        item("L|${file.path}", title, null, MediaMetadata.MEDIA_TYPE_PLAYLIST, browsable = true, playable = true)

    private fun folderItem(folder: File): MediaItem =
        item(folderId(folder), folder.name, null, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, browsable = true, playable = true)

    private fun folderId(folder: File) = "D|${folder.path}"

    private fun browsable(id: String, title: String, type: Int) = item(id, title, null, type, browsable = true, playable = false)

    private fun playable(id: String, title: String, subtitle: String?) =
        item(id, title, subtitle, MediaMetadata.MEDIA_TYPE_MUSIC, browsable = false, playable = true)

    private fun item(id: String, title: String, subtitle: String?, type: Int, browsable: Boolean, playable: Boolean): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setArtist(subtitle)
                    .setIsBrowsable(browsable)
                    .setIsPlayable(playable)
                    .setMediaType(type)
                    .build(),
            )
            .build()

    companion object {
        const val ROOT = "root"
        private const val QUEUE = "queue"
        private const val MINE = "mine"
        private const val GENERATED = "generated"
        private const val RECENT = "recent"

        /** Whether [mediaId] is a browse id of this library (not a file path). */
        fun isOurs(mediaId: String): Boolean =
            mediaId.startsWith("Q|") || mediaId.startsWith("L|") || mediaId.startsWith("D|") || mediaId.startsWith("T|")
    }
}
