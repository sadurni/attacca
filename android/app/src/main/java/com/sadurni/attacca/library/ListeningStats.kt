package com.sadurni.attacca.library

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/** How one track has been listened to. Times are wall-clock milliseconds; 0 = never. */
data class TrackListening(
    val plays: Int = 0,
    val skips: Int = 0,
    val firstPlayedMs: Long = 0,
    val lastPlayedMs: Long = 0,
    val lastSkippedMs: Long = 0,
)

/**
 * Listening statistics by file path (`stats.db`): plays, skips and when. Unlike the tag and
 * analysis caches this is the user's own history, so it is never dropped to be rebuilt. All rows
 * are also kept in memory (one per track ever heard), so lookups and list building never wait on
 * the database; writes go through to it at once.
 */
class ListeningStats(context: Context) : SQLiteOpenHelper(context, "stats.db", null, VERSION) {

    private val all = ConcurrentHashMap<String, TrackListening>()

    @Volatile
    private var loaded = false

    private val _version = MutableStateFlow(0)

    /** Increments after every change. */
    val version: StateFlow<Int> = _version.asStateFlow()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE listening (
                path TEXT PRIMARY KEY,
                plays INTEGER NOT NULL,
                skips INTEGER NOT NULL,
                first_played INTEGER NOT NULL,
                last_played INTEGER NOT NULL,
                last_skipped INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Nothing yet: a future version must migrate, never drop.
    }

    /** Reads the database into memory once. Blocking; the first call should be off the main thread. */
    fun load() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            readableDatabase.rawQuery(
                "SELECT path, plays, skips, first_played, last_played, last_skipped FROM listening",
                null,
            ).use { c ->
                while (c.moveToNext()) {
                    all[c.getString(0)] = TrackListening(c.getInt(1), c.getInt(2), c.getLong(3), c.getLong(4), c.getLong(5))
                }
            }
            loaded = true
        }
    }

    operator fun get(path: String): TrackListening? {
        load()
        return all[path]
    }

    /** Every track heard at least once (played or skipped). */
    fun snapshot(): Map<String, TrackListening> {
        load()
        return HashMap(all)
    }

    /** Tracks played since [sinceMs]. */
    fun playedSince(sinceMs: Long): Set<String> {
        load()
        return all.filterValues { it.lastPlayedMs >= sinceMs }.keys
    }

    /** Tracks played at least once. */
    fun everPlayed(): Set<String> {
        load()
        return all.filterValues { it.plays > 0 }.keys
    }

    /** Blocking. */
    fun recordPlay(path: String, atMs: Long) = update(path) {
        it.copy(plays = it.plays + 1, firstPlayedMs = if (it.firstPlayedMs == 0L) atMs else it.firstPlayedMs, lastPlayedMs = atMs)
    }

    /** Blocking. */
    fun recordSkip(path: String, atMs: Long) = update(path) { it.copy(skips = it.skips + 1, lastSkippedMs = atMs) }

    /** Forgets everything. Blocking. */
    fun clear() {
        load()
        synchronized(this) {
            writableDatabase.delete("listening", null, null)
            all.clear()
        }
        _version.value++
    }

    private fun update(path: String, change: (TrackListening) -> TrackListening) {
        load()
        synchronized(this) {
            val updated = change(all[path] ?: TrackListening())
            all[path] = updated
            writableDatabase.insertWithOnConflict(
                "listening",
                null,
                ContentValues().apply {
                    put("path", path)
                    put("plays", updated.plays)
                    put("skips", updated.skips)
                    put("first_played", updated.firstPlayedMs)
                    put("last_played", updated.lastPlayedMs)
                    put("last_skipped", updated.lastSkippedMs)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        }
        _version.value++
    }

    private companion object {
        const val VERSION = 1
    }
}
