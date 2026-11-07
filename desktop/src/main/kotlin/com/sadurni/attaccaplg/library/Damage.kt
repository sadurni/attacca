package com.sadurni.attaccaplg.library

import com.sadurni.attacca.playback.Mp3Damage
import java.io.File

/**
 * Damaged tracks, as the phone finds them: MP3s with broken frames inside the music or stretches
 * written twice ([Mp3Damage]), and files the scan could not read. Kept in the library with the
 * file's size and date, so a file replaced or repaired since does not count any more.
 */
object Damage {
    /** What is wrong (the same codes as on the phone). */
    const val UNREADABLE = 1
    const val BROKEN = 2
    const val REPEATS = 3

    /** One damaged track: what and how much, and the file's size and date when found. */
    class Found(val kind: Int, val amount: Int, val size: Long, val mtime: Long)

    /** What is wrong with [file]: (kind, amount), or null when it is fine (or no MP3 the check reads). */
    fun check(file: File): Pair<Int, Int>? {
        if (!file.extension.equals("mp3", ignoreCase = true)) return null
        return when (val d = try { Mp3Damage.check(file.readBytes()) } catch (e: Mp3Damage.NotMp3) { null }) {
            is Mp3Damage.Damage.Broken -> BROKEN to d.frames
            is Mp3Damage.Damage.Repeats -> REPEATS to d.ms
            null -> null
        }
    }

    /** The tracks of [db] found damaged, leaving out (and forgetting) those changed since. */
    fun read(db: LibraryDb, root: File?): Map<String, Found> {
        val result = HashMap<String, Found>()
        for ((path, r) in runCatching { db.damaged() }.getOrDefault(emptyMap())) {
            val file = root?.let { File(it, path) }
            if (file != null && (!file.isFile || file.length() != r[0] || file.lastModified() != r[1])) {
                runCatching { db.forgetDamaged(path) }
                continue
            }
            result[path] = Found(r[2].toInt(), r[3].toInt(), r[0], r[1])
        }
        return result
    }
}
