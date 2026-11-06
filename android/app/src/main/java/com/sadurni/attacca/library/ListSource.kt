package com.sadurni.attacca.library

import org.json.JSONObject
import java.io.File

/** Where a list came from, so it can be reloaded later (resume per list, per device). */
sealed interface ListSource {
    val name: String

    /** Stable identity used to remember the resume point of each list. */
    val key: String

    data class Folder(val path: String, val recursive: Boolean) : ListSource {
        override val name: String get() = File(path).name
        override val key: String get() = "folder:$recursive:$path"
    }

    data class Playlist(val path: String) : ListSource {
        override val name: String get() = File(path).nameWithoutExtension
        override val key: String get() = "playlist:$path"
    }

    fun toJson(): JSONObject = when (this) {
        is Folder -> JSONObject().put("type", "folder").put("path", path).put("recursive", recursive)
        is Playlist -> JSONObject().put("type", "playlist").put("path", path)
    }

    companion object {
        fun fromJson(json: JSONObject?): ListSource? = when (json?.optString("type")) {
            "folder" -> Folder(json.getString("path"), json.optBoolean("recursive"))
            "playlist" -> Playlist(json.getString("path"))
            else -> null
        }
    }
}

/** Builds the queue of a [ListSource]. Blocking file access; call off the main thread. */
object ListLoader {

    fun load(source: ListSource): List<androidx.media3.common.MediaItem> = when (source) {
        is ListSource.Folder -> {
            val folder = File(source.path)
            val files = if (source.recursive) {
                AudioFiles.collectAudio(folder)
            } else {
                AudioFiles.listFolder(folder).filter { it.isFile && AudioFiles.isAudio(it) }
            }
            MediaItems.fromFiles(files)
        }
        // A list deleted or unreadable is an empty one: whoever kept it (a device, the recent
        // lists, a queue saved with it) must not bring the app down.
        is ListSource.Playlist -> MediaItems.fromPlaylist(
            runCatching { PlaylistFiles.parse(File(source.path)) }.getOrDefault(emptyList()).filter { entry ->
                entry.location.contains("://") || File(entry.location).exists()
            },
        )
    }
    /**
     * Tracks of a mixed pick, in its order: folders with their subfolders, playlists with their
     * entries, audio files as they are.
     */
    fun loadFiles(files: List<File>): List<androidx.media3.common.MediaItem> {
        val result = ArrayList<androidx.media3.common.MediaItem>()
        val audio = ArrayList<File>()
        fun flushAudio() {
            if (audio.isNotEmpty()) result.addAll(MediaItems.fromFiles(audio.toList()))
            audio.clear()
        }
        for (file in files) {
            when {
                file.isDirectory -> {
                    flushAudio()
                    result.addAll(load(ListSource.Folder(file.path, recursive = true)))
                }
                AudioFiles.isPlaylist(file) -> {
                    flushAudio()
                    result.addAll(load(ListSource.Playlist(file.path)))
                }
                AudioFiles.isAudio(file) -> audio.add(file)
            }
        }
        flushAudio()
        return result
    }
}
