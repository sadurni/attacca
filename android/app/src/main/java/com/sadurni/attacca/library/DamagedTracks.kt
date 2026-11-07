package com.sadurni.attacca.library

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Tracks found damaged: on reading their tags, analysing them, playing them or checking them all
 * ([DamageCheck]). Kept in the tag database with the file's size and time, so a file replaced or
 * repaired since does not count any more.
 */
object DamagedTracks {

    /** What was wrong. */
    enum class Kind(val code: Int) {
        /** Its tags or its sound could not be read at all. */
        UNREADABLE(1),

        /** MP3 frames broken by garbage (bursts of noise), as many as [Damage.amount]. */
        BROKEN_FRAMES(2),

        /** Stretches of an MP3 written twice (the music jumps back), [Damage.amount] ms in all. */
        REPEATS(3),

        /** The player stopped on it. */
        PLAY_ERROR(4),

        /** It ends long before its own length says: cut short. */
        TRUNCATED(5),

        /** The player found broken or repeated frames while playing it (how many is told by a check). */
        BAD_FRAMES(6);

        /** Damage that a repair can take out (MP3 frames). */
        val repairable get() = this == BROKEN_FRAMES || this == REPEATS || this == BAD_FRAMES

        companion object {
            fun of(code: Int) = entries.firstOrNull { it.code == code } ?: UNREADABLE
        }
    }

    data class Damage(val kind: Kind, val amount: Int, val size: Long, val mtime: Long, val atMs: Long)

    /** How much of a folder's music is damaged. */
    enum class FolderMark { NONE, PART, ALL }

    private val _all = MutableStateFlow<Map<String, Damage>>(emptyMap())
    val all: StateFlow<Map<String, Damage>> = _all.asStateFlow()

    /** Changes with every change of [all] (for keys of what depends on it). */
    @Volatile
    var version = 0
        private set

    private val lock = Any()
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + com.sadurni.attacca.Background.dispatcher)

    /** Reads what was found before, leaving out files changed since. Off the main thread. */
    fun load() {
        val db = TrackSort.db ?: return
        val rows = runCatching { db.damaged() }.getOrDefault(emptyMap())
        val kept = HashMap<String, Damage>()
        for ((path, r) in rows) {
            val file = File(path)
            if (file.exists() && file.length() == r[0] && file.lastModified() == r[1]) {
                kept[path] = Damage(Kind.of(r[2].toInt()), r[3].toInt(), r[0], r[1], r[4])
            } else {
                runCatching { db.forgetDamaged(path) }
            }
        }
        synchronized(lock) {
            _all.value = kept
            version++
        }
    }

    /**
     * [path] was found damaged ([kind], [amount] of it). What a check tells (with an amount)
     * replaces what playing noticed; the rest is kept as first found.
     */
    fun report(path: String, kind: Kind, amount: Int = 0) {
        if (path.contains("://")) return
        val file = File(path)
        if (!file.isFile) return
        val known = _all.value[path]
        if (known != null && known.size == file.length() && known.mtime == file.lastModified() &&
            (known.kind == kind && known.amount >= amount || kind == Kind.BAD_FRAMES || kind == Kind.PLAY_ERROR && known.kind != Kind.PLAY_ERROR)
        ) return
        val damage = Damage(kind, amount, file.length(), file.lastModified(), System.currentTimeMillis())
        synchronized(lock) {
            _all.value = _all.value + (path to damage)
            version++
        }
        scope.launch { runCatching { TrackSort.db?.putDamaged(path, damage.size, damage.mtime, kind.code, amount, damage.atMs) } }
    }

    /** [path] is fine (checked again, repaired or deleted). */
    fun forget(path: String) {
        if (path !in _all.value) return
        synchronized(lock) {
            _all.value = _all.value - path
            version++
        }
        scope.launch { runCatching { TrackSort.db?.forgetDamaged(path) } }
    }

    fun isDamaged(path: String): Boolean = path in _all.value

    /** The damaged tracks under [folder], newest first. */
    fun under(folder: String?): List<Pair<String, Damage>> {
        val prefix = folder?.trimEnd('/')?.plus("/")
        return _all.value.entries.filter { prefix == null || it.key.startsWith(prefix) }
            .map { it.key to it.value }.sortedByDescending { it.second.atMs }
    }

    /** How much of [folder]'s music (with its subfolders) is damaged. Reads the tag database: off the main thread. */
    fun folderMark(folder: String): FolderMark {
        val prefix = folder.trimEnd('/') + "/"
        val damaged = _all.value.keys.count { it.startsWith(prefix) }
        if (damaged == 0) return FolderMark.NONE
        val total = runCatching { TrackSort.db?.countUnder(folder) }.getOrNull() ?: 0
        return if (total in 1..damaged) FolderMark.ALL else FolderMark.PART
    }
}
