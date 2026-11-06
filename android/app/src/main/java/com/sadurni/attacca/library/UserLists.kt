package com.sadurni.attacca.library

import android.content.Context
import android.os.Environment
import androidx.media3.common.MediaItem
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The user's own lists, stored as plain M3U8 files in Music/Attacca so they are usable by other
 * players and survive reinstalls. Favorites is one more list.
 */
object UserLists {

    const val FAVORITES_NAME = "Favorites"

    val directory: File
        get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Attacca")

    val favorites: File get() = File(directory, "$FAVORITES_NAME.m3u8")

    /** The lists of the folder, without the one kept from Favorites ([IncludedFavorites]): that one is not added to by hand. */
    fun all(): List<File> =
        directory.listFiles { f -> f.isFile && f.extension.equals("m3u8", true) && f.name != IncludedFavorites.file.name }
            ?.sortedWith(NaturalOrder)
            .orEmpty()

    fun fileFor(name: String): File = File(directory, sanitize(name) + ".m3u8")

    /** A file for a new list named [name] that is not taken: "name", else "name (2)", "name (3)"… */
    fun freeFileFor(name: String): File {
        var file = fileFor(name)
        var n = 2
        while (file.exists()) file = fileFor("$name (${n++})")
        return file
    }

    /** The file [list] would have with the name [name], in the same folder. */
    fun renamedFile(list: File, name: String): File = File(list.parentFile, sanitize(name) + ".m3u8")

    /**
     * Writes [items] to [file]. Entries of the file whose track is not on this device (left out
     * when the list was loaded) are kept, each after the track it followed: the same lists can be
     * used on a device holding only part of the music.
     */
    fun save(file: File, items: List<MediaItem>) {
        file.parentFile?.mkdirs()
        M3uParser.write(file, keepingMissing(if (file.isFile) M3uParser.parse(file) else emptyList(), items.map(::toEntry)))
    }

    /** [entries] with the entries of [old] that are missing here put back after the entry they followed. */
    internal fun keepingMissing(old: List<PlaylistEntry>, entries: List<PlaylistEntry>): List<PlaylistEntry> {
        val present = entries.mapTo(HashSet()) { it.location }
        val after = HashMap<String?, MutableList<PlaylistEntry>>()
        var anchor: String? = null
        for (entry in old) {
            when {
                entry.location in present -> anchor = entry.location
                !entry.location.contains("://") && !File(entry.location).exists() -> after.getOrPut(anchor) { ArrayList() }.add(entry)
            }
        }
        if (after.isEmpty()) return entries
        val result = ArrayList<PlaylistEntry>(entries.size + after.values.sumOf { it.size })
        after.remove(null)?.let(result::addAll)
        for (entry in entries) {
            result.add(entry)
            after.remove(entry.location)?.let(result::addAll)
        }
        return result
    }

    /** M3U8 text of [items] for exporting; see [M3uParser.format] for [baseDir]. */
    fun exportText(items: List<MediaItem>, baseDir: File?, format: PlaylistFormat = PlaylistFormat.M3U8): String =
        PlaylistWriter.format(items.map(::toEntry), format, baseDir)

    /** Adds items that are not in the list yet; returns how many were added. */
    fun append(file: File, items: List<MediaItem>): Int {
        val existing = if (file.exists()) M3uParser.parse(file) else emptyList()
        val known = existing.mapTo(HashSet()) { it.location }
        val added = items.filter { known.add(it.mediaId) }.map(::toEntry)
        if (added.isEmpty()) return 0
        file.parentFile?.mkdirs()
        M3uParser.write(file, existing + added)
        return added.size
    }

    /** Whether [file] is one of the user's own lists (edits to it are saved back). */
    fun isUserList(file: File): Boolean = file.parentFile?.canonicalPath == directory.canonicalPath

    fun remove(file: File, mediaId: String) {
        if (!file.exists()) return
        M3uParser.write(file, M3uParser.parse(file).filter { it.location != mediaId })
    }

    fun contains(file: File, mediaId: String): Boolean =
        file.exists() && M3uParser.parse(file).any { it.location == mediaId }

    private fun toEntry(item: MediaItem) = PlaylistEntry(
        location = item.mediaId,
        title = item.mediaMetadata.extras?.getString(MediaItems.EXTRA_PLAYLIST_TITLE),
        startMs = MediaItems.explicitClipStartMs(item),
        endMs = MediaItems.explicitClipEndMs(item),
    )

    private fun sanitize(name: String): String =
        name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").ifEmpty { "List" }
}

/**
 * Lists the user has touched: recently played sources (newest first, capped) and every playlist
 * file opened at least once, so playlists opened from other apps or the folder browser stay
 * available in Lists.
 */
class ListHistory(context: Context) {

    private val prefs = context.getSharedPreferences("list_history", Context.MODE_PRIVATE)

    fun all(): List<ListSource> = runCatching {
        val array = JSONArray(prefs.getString(KEY, "[]"))
        List(array.length()) { ListSource.fromJson(array.getJSONObject(it)) }.filterNotNull()
    }.getOrDefault(emptyList())

    fun touch(source: ListSource) {
        val updated = listOf(source) + all().filter { it.key != source.key }
        prefs.edit().putString(KEY, JSONArray(updated.take(MAX).map { it.toJson() }).toString()).apply()
    }

    fun remove(source: ListSource) {
        val updated = all().filter { it.key != source.key }
        prefs.edit().putString(KEY, JSONArray(updated.map { it.toJson() }).toString()).apply()
    }

    fun playlists(): List<File> =
        prefs.getStringSet(KEY_PLAYLISTS, emptySet()).orEmpty().map(::File).sortedWith(NaturalOrder)

    fun rememberPlaylist(file: File) {
        if (UserLists.isUserList(file)) return
        val updated = prefs.getStringSet(KEY_PLAYLISTS, emptySet()).orEmpty() + file.absolutePath
        prefs.edit().putStringSet(KEY_PLAYLISTS, updated).apply()
    }

    fun forgetPlaylist(file: File) {
        val updated = prefs.getStringSet(KEY_PLAYLISTS, emptySet()).orEmpty() - file.absolutePath
        prefs.edit().putStringSet(KEY_PLAYLISTS, updated).apply()
    }

    /** A renamed list keeps its place among the recent ones. */
    fun rename(old: ListSource, renamed: ListSource) {
        val updated = all().map { if (it.key == old.key) renamed else it }
        prefs.edit().putString(KEY, JSONArray(updated.map { it.toJson() }).toString()).apply()
    }

    private companion object {
        const val KEY = "recent"
        const val KEY_PLAYLISTS = "playlists"
        const val MAX = 30
    }
}

data class Bookmark(val mediaId: String, val positionMs: Long, val title: String, val createdAt: Long) {
    val id: String get() = "$mediaId@$positionMs"
}

/** Saved positions inside files (handy for audiobooks and long mixes). */
class Bookmarks(context: Context) {

    private val prefs = context.getSharedPreferences("bookmarks", Context.MODE_PRIVATE)

    fun all(): List<Bookmark> = runCatching {
        val array = JSONArray(prefs.getString(KEY, "[]"))
        List(array.length()) {
            val o = array.getJSONObject(it)
            Bookmark(o.getString("mediaId"), o.getLong("pos"), o.optString("title"), o.optLong("created"))
        }
    }.getOrDefault(emptyList())

    fun add(bookmark: Bookmark) = write(listOf(bookmark) + all().filter { it.id != bookmark.id })

    fun remove(bookmark: Bookmark) = write(all().filter { it.id != bookmark.id })

    private fun write(list: List<Bookmark>) {
        val array = JSONArray(
            list.map {
                JSONObject().put("mediaId", it.mediaId).put("pos", it.positionMs).put("title", it.title).put("created", it.createdAt)
            },
        )
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private companion object {
        const val KEY = "all"
    }
}

/** The order the user gave to the lists of each group in Lists; lists not placed yet go last. */
class ListOrder(context: Context) {

    private val prefs = context.getSharedPreferences("list_order", Context.MODE_PRIVATE)

    fun sorted(group: String, files: List<File>): List<File> {
        val order = runCatching {
            val array = JSONArray(prefs.getString(group, "[]"))
            List(array.length()) { array.getString(it) }
        }.getOrDefault(emptyList())
        val rank = order.withIndex().associate { it.value to it.index }
        val (placed, rest) = files.partition { it.absolutePath in rank }
        return placed.sortedBy { rank[it.absolutePath] } + rest
    }

    fun save(group: String, files: List<File>) {
        prefs.edit().putString(group, JSONArray(files.map { it.absolutePath }).toString()).apply()
    }

    /** A renamed list keeps its place in its group. */
    fun rename(old: File, renamed: File) {
        val edit = prefs.edit()
        for ((group, value) in prefs.all) {
            val array = runCatching { JSONArray(value as? String ?: return@runCatching null) }.getOrNull() ?: continue
            val paths = List(array.length()) { array.getString(it) }
            if (old.absolutePath in paths) {
                edit.putString(group, JSONArray(paths.map { if (it == old.absolutePath) renamed.absolutePath else it }).toString())
            }
        }
        edit.apply()
    }
}
