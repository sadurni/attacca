package com.sadurni.attacca.library

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

/**
 * Tags needed for sorting and grouping, kept between sessions: reading them from thousands of
 * files takes minutes, from here a moment. An entry is stale when the file's size or
 * modification time changed.
 */
class TagDb(context: Context) : SQLiteOpenHelper(context, "tags.db", null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE tags (
                path TEXT PRIMARY KEY,
                size INTEGER NOT NULL,
                mtime INTEGER NOT NULL,
                title TEXT NOT NULL,
                artist TEXT NOT NULL,
                album TEXT NOT NULL,
                disc INTEGER NOT NULL,
                track INTEGER NOT NULL,
                year INTEGER NOT NULL,
                duration_ms INTEGER NOT NULL,
                album_artist TEXT NOT NULL,
                genre TEXT NOT NULL
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // A cache: rebuild rather than migrate.
        db.execSQL("DROP TABLE IF EXISTS tags")
        onCreate(db)
    }

    override fun onOpen(db: SQLiteDatabase) {
        // Added later without a new VERSION (that would drop the cache).
        if (!db.isReadOnly) {
            db.execSQL("CREATE TABLE IF NOT EXISTS missing (path TEXT PRIMARY KEY)")
            db.execSQL("CREATE TABLE IF NOT EXISTS fixes (name TEXT PRIMARY KEY)")
            // The files already checked for damage, with their size and date then (fine or not).
            db.execSQL("CREATE TABLE IF NOT EXISTS damage_checked (path TEXT PRIMARY KEY, size INTEGER NOT NULL, mtime INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS damaged (path TEXT PRIMARY KEY, size INTEGER NOT NULL, mtime INTEGER NOT NULL, kind INTEGER NOT NULL, amount INTEGER NOT NULL, at INTEGER NOT NULL)")
            repairTextOnce(db)
            rereadYearsOnce(db)
        }
    }

    /**
     * Rows read before the year came from the tag itself mostly have none (or a whole date): they
     * are marked as changed once, so the next library scan reads those files again. Music keeps
     * showing them meanwhile.
     */
    private fun rereadYearsOnce(db: SQLiteDatabase) {
        val done = db.rawQuery("SELECT 1 FROM fixes WHERE name = ?", arrayOf(YEAR_FIX)).use { it.moveToFirst() }
        if (done) return
        db.beginTransaction()
        try {
            db.execSQL("UPDATE tags SET mtime = 0 WHERE year <= 0 OR year > 9999")
            db.execSQL("INSERT OR REPLACE INTO fixes (name) VALUES (?)", arrayOf(YEAR_FIX))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Rows read before [TagText.repair] existed keep doubly decoded text: mend them once, without a new scan. */
    private fun repairTextOnce(db: SQLiteDatabase) {
        val done = db.rawQuery("SELECT 1 FROM fixes WHERE name = ?", arrayOf(TEXT_FIX)).use { it.moveToFirst() }
        if (done) return
        val columns = listOf("title", "artist", "album", "album_artist", "genre")
        val changed = HashMap<String, ContentValues>()
        db.rawQuery(
            "SELECT path, ${columns.joinToString()} FROM tags WHERE (${columns.joinToString(" || ")}) GLOB '*[^ -~]*'",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                val values = ContentValues()
                columns.forEachIndexed { i, column ->
                    val text = c.getString(i + 1)
                    val repaired = TagText.repair(text)
                    if (repaired != text) values.put(column, repaired)
                }
                if (values.size() > 0) changed[c.getString(0)] = values
            }
        }
        db.beginTransaction()
        try {
            for ((path, values) in changed) db.update("tags", values, "path = ?", arrayOf(path))
            db.execSQL("INSERT OR REPLACE INTO fixes (name) VALUES (?)", arrayOf(TEXT_FIX))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Valid entries among [paths]. */
    fun getAll(paths: Collection<String>): Map<String, SortTags> {
        val result = HashMap<String, SortTags>()
        // SQLite limits the number of parameters per statement.
        for (chunk in paths.chunked(CHUNK)) {
            val placeholders = chunk.joinToString(",") { "?" }
            readableDatabase.rawQuery(
                "SELECT path, size, mtime, title, artist, album, disc, track, year, duration_ms, album_artist, genre FROM tags WHERE path IN ($placeholders)",
                chunk.toTypedArray(),
            ).use { c ->
                while (c.moveToNext()) {
                    val path = c.getString(0)
                    val file = File(path)
                    if (!com.sadurni.attacca.library.FileStamp.unchanged(c.getLong(1), c.getLong(2), file)) continue
                    result[path] = SortTags(c.getString(3), c.getString(4), c.getString(5), c.getInt(6), c.getInt(7), c.getInt(8), c.getLong(9), c.getString(10), c.getString(11))
                }
            }
        }
        return result
    }

    /** Files whose artist or album artist is [artist] (any case). */
    fun pathsOfArtist(artist: String): List<String> = paths(
        "SELECT path FROM tags WHERE (artist = ? COLLATE NOCASE OR album_artist = ? COLLATE NOCASE) AND $PRESENT",
        arrayOf(artist, artist),
    )

    /** Files of the album [album] by [artist] (artist or album artist, any case). */
    fun pathsOfAlbum(artist: String, album: String): List<String> = paths(
        "SELECT path FROM tags WHERE album = ? COLLATE NOCASE AND (artist = ? COLLATE NOCASE OR album_artist = ? COLLATE NOCASE) AND $PRESENT",
        arrayOf(album, artist, artist),
    )

    /** Whether the one-off fix [name] was done (see the fixes table). */
    fun fixDone(name: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM fixes WHERE name = ?", arrayOf(name)).use { it.moveToFirst() }

    fun markFixDone(name: String) {
        writableDatabase.execSQL("INSERT OR REPLACE INTO fixes (name) VALUES (?)", arrayOf(name))
    }

    /** The files checked for damage: path → (size, mtime) when checked. */
    fun damageChecked(): Map<String, LongArray> {
        val result = HashMap<String, LongArray>()
        readableDatabase.rawQuery("SELECT path, size, mtime FROM damage_checked", null).use { c ->
            while (c.moveToNext()) result[c.getString(0)] = longArrayOf(c.getLong(1), c.getLong(2))
        }
        return result
    }

    fun putDamageChecked(path: String, size: Long, mtime: Long) {
        writableDatabase.insertWithOnConflict("damage_checked", null, ContentValues().apply {
            put("path", path); put("size", size); put("mtime", mtime)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Forgets which files were checked (all are checked again). */
    fun clearDamageChecked() {
        writableDatabase.delete("damage_checked", null, null)
    }

    /** The damaged tracks found so far: path → (size, mtime, kind, amount, when). */
    fun damaged(): Map<String, LongArray> {
        val result = HashMap<String, LongArray>()
        readableDatabase.rawQuery("SELECT path, size, mtime, kind, amount, at FROM damaged", null).use { c ->
            while (c.moveToNext()) result[c.getString(0)] = longArrayOf(c.getLong(1), c.getLong(2), c.getLong(3), c.getLong(4), c.getLong(5))
        }
        return result
    }

    fun putDamaged(path: String, size: Long, mtime: Long, kind: Int, amount: Int, at: Long) {
        writableDatabase.insertWithOnConflict("damaged", null, ContentValues().apply {
            put("path", path); put("size", size); put("mtime", mtime); put("kind", kind); put("amount", amount); put("at", at)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun forgetDamaged(path: String) {
        writableDatabase.delete("damaged", "path = ?", arrayOf(path))
    }

    /** How many tracks the scans know under [folder] (with its subfolders). */
    fun countUnder(folder: String): Int {
        val prefix = folder.trimEnd('/') + "/"
        return readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM tags WHERE substr(path, 1, ?) = ? AND $PRESENT",
            arrayOf(prefix.length.toString(), prefix),
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    /** The library's figures for the files under [folder] (what the last scans left here). */
    fun stats(folder: String): LibraryStats {
        val counter = LibraryStats.Counter()
        var missing = 0
        val prefix = folder.trimEnd('/') + "/"
        readableDatabase.rawQuery(
            "SELECT path, size, artist, album, album_artist, genre, year, duration_ms FROM tags WHERE substr(path, 1, ?) = ?",
            arrayOf(prefix.length.toString(), prefix),
        ).use { c ->
            while (c.moveToNext()) {
                // Files deleted (or changed) since they were read do not count; the deleted ones are missing.
                val file = File(c.getString(0))
                if (!file.exists()) {
                    missing++
                    continue
                }
                if (file.length() != c.getLong(1)) continue
                counter.add(c.getString(0), c.getLong(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getInt(6), c.getLong(7))
            }
        }
        return counter.result().copy(missing = missing)
    }

    /**
     * Every track under [folder] as the last scans left it (no file is checked: the scan keeps
     * this up to date), for the music database view.
     */
    fun tracks(folder: String): List<MusicTrack> {
        val prefix = folder.trimEnd('/') + "/"
        val result = ArrayList<MusicTrack>()
        readableDatabase.rawQuery(
            "SELECT path, mtime, title, artist, album, disc, track, year, duration_ms, album_artist, genre FROM tags WHERE substr(path, 1, ?) = ? AND $PRESENT",
            arrayOf(prefix.length.toString(), prefix),
        ).use { c ->
            while (c.moveToNext()) {
                // Left out of the library, though scanned before (see IgnoredPaths).
                if (IgnoredPaths.isIgnored(c.getString(0))) continue
                result.add(
                    MusicTrack(
                        c.getString(0),
                        SortTags(c.getString(2), c.getString(3), c.getString(4), c.getInt(5), c.getInt(6), c.getInt(7), c.getLong(8), c.getString(9), c.getString(10)),
                        c.getLong(1),
                    ),
                )
            }
        }
        return result
    }

    private fun paths(sql: String, args: Array<String>): List<String> =
        readableDatabase.rawQuery(sql, args).use { c ->
            val result = ArrayList<String>()
            while (c.moveToNext()) result.add(c.getString(0))
            result.sortedWith(compareBy(NaturalOrder) { File(it) })
        }

    /**
     * Marks the files under [folder] that are gone as missing: those the scan did not find
     * ([found]) and that do not exist (a folder that could not be read is not taken as gone).
     * Missing files stay known (a restored backup from a device with more music, a card taken
     * out) but are left out of Music and of the lists made from the tags; they come back when
     * the files are there again. Returns how many are missing.
     */
    fun markMissing(folder: String, found: Set<String>): Int {
        val prefix = folder.trimEnd('/') + "/"
        val args = arrayOf(prefix.length.toString(), prefix)
        val gone = readableDatabase.rawQuery("SELECT path FROM tags WHERE substr(path, 1, ?) = ?", args).use { c ->
            buildList { while (c.moveToNext()) c.getString(0).takeIf { it !in found && !File(it).exists() }?.let(::add) }
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("missing", "substr(path, 1, ?) = ?", args)
            for (path in gone) db.insertWithOnConflict("missing", null, ContentValues().apply { put("path", path) }, SQLiteDatabase.CONFLICT_IGNORE)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return gone.size
    }

    /** Forgets the missing files under [folder] for good (deleted on purpose). Returns how many. */
    fun forgetMissing(folder: String): Int {
        val prefix = folder.trimEnd('/') + "/"
        val gone = readableDatabase.rawQuery("SELECT path FROM tags WHERE substr(path, 1, ?) = ?", arrayOf(prefix.length.toString(), prefix)).use { c ->
            buildList { while (c.moveToNext()) c.getString(0).takeIf { !File(it).exists() }?.let(::add) }
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (path in gone) {
                db.delete("tags", "path = ?", arrayOf(path))
                db.delete("missing", "path = ?", arrayOf(path))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return gone.size
    }

    fun putAll(entries: Map<String, SortTags>) {
        if (entries.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for ((path, tags) in entries) {
                val file = File(path)
                // Tags of a file that is not there (any more) would hide it among the present ones.
                if (!file.exists()) continue
                db.insertWithOnConflict(
                    "tags",
                    null,
                    ContentValues().apply {
                        put("path", path)
                        put("size", file.length())
                        put("mtime", file.lastModified())
                        put("title", tags.title)
                        put("artist", tags.artist)
                        put("album", tags.album)
                        put("disc", tags.disc)
                        put("track", tags.track)
                        put("year", tags.year)
                        put("duration_ms", tags.durationMs)
                        put("album_artist", tags.albumArtist)
                        put("genre", tags.genre)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private companion object {
        const val VERSION = 3

        /** SQL condition: the row's file is not missing (see [markMissing]). */
        const val PRESENT = "path NOT IN (SELECT path FROM missing)"
        const val CHUNK = 500
        const val TEXT_FIX = "tag_text_1"
        const val YEAR_FIX = "tag_year_1"
    }
}
