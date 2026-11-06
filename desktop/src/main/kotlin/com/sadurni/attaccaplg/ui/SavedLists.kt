package com.sadurni.attaccaplg.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sadurni.attaccaplg.library.LibraryDb
import org.json.JSONObject
import java.io.File

/**
 * The desktop's own lists, kept as Attacca keeps them: the lists made in Lists saved on their own
 * (Generated; a new one replaces the one of the same name), the user's own (Mine: the queue saved,
 * tracks added from anywhere), and where each was left (track and time), to go on from there.
 * M3U8 files with full paths, in %APPDATA%/AttaccaPLG/Lists.
 */
class SavedLists(val home: File = File(LibraryDb.HOME, "Lists")) {
    val generated = File(home, "Generated")
    val mine = File(home, "Mine")

    /** The favourites, beside the lists' folder (see [FAVORITES]). */
    val favoritesFile: File get() = File(home.parentFile ?: home, "Favorites.m3u8")

    /** Where a list was left: the track (its full path) and the time in it. */
    class Position(val path: String, val ms: Long)

    class Entry(val file: File, val generated: Boolean, val paths: List<String>, val position: Position?, val favorites: Boolean = false) {
        val name: String get() = if (favorites) tr("Favourites", "Favoritos") else file.nameWithoutExtension
    }

    var entries by mutableStateOf<List<Entry>>(emptyList())
        private set

    private val positionsFile = File(home, "positions.json")
    private val positions: JSONObject = runCatching { JSONObject(positionsFile.readText()) }.getOrDefault(JSONObject())

    init {
        refresh()
    }

    /** Favourites first (always there, maybe empty), the user's own lists by name, then the made ones, newest first. */
    fun refresh() {
        fun of(dir: File) = dir.listFiles { f -> f.isFile && f.extension.equals("m3u8", ignoreCase = true) }.orEmpty().toList()
        val favorites = Entry(favoritesFile, false, if (favoritesFile.isFile) read(favoritesFile) else emptyList(), positionOf(favoritesFile), favorites = true)
        entries = listOf(favorites) + of(mine).sortedBy { it.name.lowercase() }.map { Entry(it, false, read(it), positionOf(it)) } +
            of(generated).sortedByDescending { it.lastModified() }.map { Entry(it, true, read(it), positionOf(it)) }
    }

    /** A list's tracks (full paths). */
    fun read(file: File): List<String> = runCatching {
        file.readLines().map { it.trim().removePrefix("﻿") }.filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line -> File(line).let { if (it.isAbsolute) it else File(file.parentFile, line) }.path }
    }.getOrDefault(emptyList())

    private fun write(file: File, paths: List<String>) {
        file.parentFile.mkdirs()
        file.writeText("#EXTM3U\n" + paths.joinToString("") { "$it\n" })
    }

    /** The lists made in Lists (library paths within [root]), kept among the made ones. */
    fun saveMade(made: List<MadeList>, root: File) {
        for (m in made) {
            val file = File(generated, safe(m.playlist.name) + ".m3u8")
            write(file, m.playlist.paths.map { File(root, it).path })
            forget(file)
        }
        refresh()
    }

    /** A new list of the user's own (a free name like "Name 2" when taken). */
    fun saveAs(name: String, paths: List<String>): File {
        val base = safe(name)
        var file = File(mine, "$base.m3u8")
        var n = 2
        while (file.exists()) file = File(mine, "$base ${n++}.m3u8")
        write(file, paths)
        refresh()
        return file
    }

    fun addTo(file: File, paths: List<String>) {
        write(file, read(file) + paths)
        refresh()
    }

    /** Renames a list; false when the name is taken. */
    fun rename(entry: Entry, name: String): Boolean {
        val target = File(entry.file.parentFile, safe(name) + ".m3u8")
        if (target.exists() || !entry.file.renameTo(target)) return false
        positions.optJSONObject(key(entry.file))?.let { positions.put(key(target), it) }
        forget(entry.file)
        refresh()
        return true
    }

    fun delete(entry: Entry) {
        entry.file.delete()
        forget(entry.file)
        refresh()
    }

    fun positionOf(file: File): Position? = positions.optJSONObject(key(file))?.let { Position(it.optString("path"), it.optLong("ms")) }

    /** Keeps where [file] was left. */
    fun remember(file: File, path: String, ms: Long) {
        val old = positions.optJSONObject(key(file))
        if (old != null && old.optString("path") == path && kotlin.math.abs(old.optLong("ms") - ms) < 1000) return
        positions.put(key(file), JSONObject().put("path", path).put("ms", ms))
        savePositions()
        entries = entries.map { if (it.file == file) Entry(it.file, it.generated, it.paths, Position(path, ms), it.favorites) else it }
    }

    private fun forget(file: File) {
        if (positions.remove(key(file)) != null) savePositions()
    }

    private fun savePositions() {
        runCatching {
            home.mkdirs()
            positionsFile.writeText(positions.toString())
        }
    }

    private fun key(file: File) = file.parentFile.name + "/" + file.name

    companion object {
        /** The favourite tracks, beside the library (as the phone keeps its Favorites.m3u8 among its lists). */
        val FAVORITES: File get() = File(LibraryDb.HOME, "Favorites.m3u8")

        fun safe(name: String) = name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").ifEmpty { "List" }
    }
}
