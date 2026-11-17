package com.sadurni.attacca.settings

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.sadurni.attacca.library.UserLists
import java.io.File

/**
 * A backup restored on another phone: its caches (tags, analysis, chaining, listening) and lists
 * name the tracks by the paths of the phone it was made on, whose music folder (a memory card is
 * named after the card) is not here. The folder is chosen again, and everything kept for a file
 * under the old folder moves to the same file under the new one, when it is there with the same
 * size (its time is taken as it is here: a copied file has another), so nothing is scanned again.
 */
object PathRemap {

    private const val KEY = "remap_from"
    private const val MUSIC_FOLDER = "music_folder"

    /**
     * Right after a restore (before the settings are read): when the backup's music folder is not
     * on this phone, it is kept to move from, the folder is unset and the welcome shows again, to
     * choose this phone's.
     */
    fun noteRestored(context: Context) {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val old = prefs.getString(MUSIC_FOLDER, null) ?: return
        if (File(old).isDirectory) return
        prefs.edit().putString(KEY, old).remove(MUSIC_FOLDER).commit()
        context.getSharedPreferences("welcome", Context.MODE_PRIVATE).edit().putBoolean("done", false).commit()
    }

    /** The music folder of a restored backup still to move from, if any. */
    fun pending(context: Context): String? = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString(KEY, null)

    /**
     * Moves what is kept for the files under the pending old folder to [newRoot]. Returns how many
     * tracks were found here. Blocking: off the main thread, before the music folder is scanned.
     */
    fun run(context: Context, newRoot: String): Int {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val old = prefs.getString(KEY, null)?.trimEnd('/') ?: return 0
        val root = newRoot.trimEnd('/')
        val found = HashSet<String>()
        if (old != root) {
            val dbDir = File(context.applicationInfo.dataDir, "databases")
            dbDir.listFiles { f -> f.isFile && f.name.endsWith(".db") }?.forEach { db ->
                runCatching { moveIn(db, old, root, found) }
            }
            runCatching { moveLists(UserLists.directory, old, root) }
        }
        prefs.edit().remove(KEY).apply()
        return found.size
    }

    /** One database: every table with a path column, its rows under [old] moved to [root]. */
    private fun moveIn(file: File, old: String, root: String, found: MutableSet<String>) {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0)) }
            }
            db.beginTransaction()
            try {
                for (table in tables) {
                    val columns = db.rawQuery("PRAGMA table_info(\"$table\")", null).use { c ->
                        buildSet { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) }
                    }
                    if ("path" !in columns) continue
                    val sized = "size" in columns
                    val timed = "mtime" in columns
                    val rows = db.rawQuery(
                        "SELECT path${if (sized) ", size" else ""} FROM \"$table\" WHERE substr(path, 1, ?) = ?",
                        arrayOf((old.length + 1).toString(), "$old/"),
                    ).use { c ->
                        buildList { while (c.moveToNext()) add(c.getString(0) to (if (sized) c.getLong(1) else -1L)) }
                    }
                    for ((path, size) in rows) {
                        val moved = File(root + path.substring(old.length))
                        if (!moved.isFile || (sized && moved.length() != size)) continue
                        val set = if (timed) "path = ?, mtime = ?" else "path = ?"
                        val args: Array<Any> = if (timed) arrayOf(moved.path, moved.lastModified(), path) else arrayOf(moved.path, path)
                        db.execSQL("UPDATE OR REPLACE \"$table\" SET $set WHERE path = ?", args)
                        found += moved.path
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    /** The user's lists (and the made ones below them): their tracks under [old] moved to [root]. */
    private fun moveLists(dir: File, old: String, root: String) {
        dir.listFiles()?.forEach { f ->
            if (f.isDirectory) {
                moveLists(f, old, root)
            } else if (f.extension.lowercase() in setOf("m3u8", "m3u")) {
                val text = f.readText()
                if (!text.contains("$old/")) return@forEach
                f.writeText(text.lines().joinToString("\n") { line -> if (line.startsWith("$old/")) root + line.substring(old.length) else line })
            }
        }
    }
}
