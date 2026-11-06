package com.sadurni.attacca.library

import com.sadurni.attacca.chain.Mood
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * The moods the user gave by hand: to a track, to a whole album or to an artist. Kept as plain
 * text in Music/Attacca/moods.txt (one per line: scope, key, mood, separated by tabs), so it
 * survives a reinstall and can be read or edited outside the app.
 *
 * A track's own mood comes first, then its album's, then its artist's. Only a track's own mood
 * reaches the tracks that sound most like it (see [com.sadurni.attacca.chain.Moods.classify]).
 */
object MoodCorrections {

    enum class Scope { TRACK, ALBUM, ARTIST }

    /** What a correction applies to: the track's path, "artist / album" or the artist. */
    data class Entry(val scope: Scope, val key: String, val mood: Mood)

    private val file: File get() = File(UserLists.directory, "moods.txt")

    private var entries: Map<Pair<Scope, String>, Mood>? = null

    private val _version = MutableStateFlow(0)

    /** Changes with every correction (collections made before are out of date). */
    val version: StateFlow<Int> = _version

    @Synchronized
    private fun loaded(): Map<Pair<Scope, String>, Mood> = entries ?: read().also { entries = it }

    private fun read(): Map<Pair<Scope, String>, Mood> = runCatching {
        if (!file.isFile) return emptyMap()
        file.readLines().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size != 3) return@mapNotNull null
            val scope = Scope.entries.firstOrNull { it.name.equals(parts[0], ignoreCase = true) } ?: return@mapNotNull null
            val mood = Mood.entries.firstOrNull { it.name.equals(parts[2], ignoreCase = true) } ?: return@mapNotNull null
            (scope to parts[1]) to mood
        }.toMap()
    }.getOrDefault(emptyMap())

    fun albumKey(artist: String, album: String): String = "$artist / $album"

    /** The mood given for [scope]/[key], if any. */
    fun get(scope: Scope, key: String): Mood? = loaded()[scope to key]

    /** The user's mood for a track: its own, else its album's, else its artist's; with where it comes from. */
    fun moodFor(path: String, artist: String, album: String): Pair<Mood, Scope>? {
        val all = loaded()
        if (all.isEmpty()) return null
        all[Scope.TRACK to path]?.let { return it to Scope.TRACK }
        if (album.isNotBlank()) all[Scope.ALBUM to albumKey(artist, album)]?.let { return it to Scope.ALBUM }
        if (artist.isNotBlank()) all[Scope.ARTIST to artist]?.let { return it to Scope.ARTIST }
        return null
    }

    /** Gives [mood] to [scope]/[key], or takes the given one away with null. */
    @Synchronized
    fun set(scope: Scope, key: String, mood: Mood?) {
        val updated = loaded().toMutableMap()
        if (mood == null) updated.remove(scope to key) else updated[scope to key] = mood
        entries = updated
        runCatching {
            file.parentFile?.mkdirs()
            val text = updated.entries.sortedWith(compareBy({ it.key.first.ordinal }, { it.key.second }))
                .joinToString("") { "${it.key.first.name.lowercase()}\t${it.key.second}\t${it.value.name.lowercase()}\n" }
            if (updated.isEmpty()) file.delete() else file.writeText(text)
        }
        _version.value++
    }

    /** Every correction, in order. */
    fun all(): List<Entry> = loaded().map { Entry(it.key.first, it.key.second, it.value) }
}
