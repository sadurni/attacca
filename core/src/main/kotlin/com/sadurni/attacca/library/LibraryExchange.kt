package com.sadurni.attacca.library

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The library exchange file (`.attlib`, shared with Attacca Desktop): what Attacca and Attacca Desktop know
 * about each track, so a collection is measured once, wherever it suits, and the other side takes
 * it as its own. A SQLite file:
 *
 * - `meta`: format, app, edge length, silence threshold, inclusion mode, creation date.
 * - `tracks`: one row per track, by path relative to the music folder ('/' separators) and size;
 *   tags; the pre-play analysis (silences, loudness, peak, waveform); the chaining edges, the
 *   features and the sound profile (format 2 on). Parts that were not measured are null.
 * - `inclusion`: the tracks the inclusion list holds (with `meta.inclusion_mode`: allow or block).
 *
 * Importing matches each row to a local file by path ([PathMatcher]) and takes it only when the
 * sizes are equal (the same file). Pure Kotlin: each side reads and writes it with its own SQLite.
 */
object LibraryExchange {

    const val FORMAT = 2
    const val EXTENSION = "attlib"
    const val MIME = "application/x-sqlite3"

    val SCHEMA = listOf(
        "CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT)",
        """CREATE TABLE tracks (
            path TEXT PRIMARY KEY, size INTEGER NOT NULL, duration_ms INTEGER,
            title TEXT, artist TEXT, album TEXT, album_artist TEXT, genre TEXT, year INTEGER, track INTEGER, disc INTEGER,
            threshold REAL, start_ms INTEGER, end_ms INTEGER, lufs REAL, peak REAL, waveform BLOB,
            edge_seconds INTEGER, head BLOB, tail BLOB, features BLOB, sound BLOB)""",
        "CREATE TABLE inclusion (path TEXT PRIMARY KEY)",
    )

    /** Column order of [Track] rows, for INSERT and SELECT. */
    const val COLUMNS = "path, size, duration_ms, title, artist, album, album_artist, genre, year, track, disc, " +
        "threshold, start_ms, end_ms, lufs, peak, waveform, edge_seconds, head, tail, features, sound"

    /** The columns of a file of [format] (the sound profile came with format 2). */
    fun columns(format: Int): String = if (format >= 2) COLUMNS else COLUMNS.removeSuffix(", sound")

    /** One track of the file. Analysis fields are null when not measured (all of them, or none). */
    class Track(
        val path: String,
        val size: Long,
        val durationMs: Long?,
        val title: String?,
        val artist: String?,
        val album: String?,
        val albumArtist: String?,
        val genre: String?,
        val year: Int?,
        val track: Int?,
        val disc: Int?,
        val thresholdDb: Float?,
        val startMs: Long?,
        val endMs: Long?,
        val lufs: Float?,
        val peak: Float?,
        val waveform: FloatArray?,
        val edgeSeconds: Int?,
        val head: FloatArray?,
        val tail: FloatArray?,
        val features: FloatArray?,
    ) {
        val hasAnalysis: Boolean get() = thresholdDb != null && startMs != null && endMs != null && waveform != null
    }

    /** What an import did. */
    class ImportResult(
        /** Rows in the file. */
        val total: Int,
        /** Rows taken (a local file with the same path and size). */
        val matched: Int,
        /** Rows whose local file has another size (another encoding, edited tags…). */
        val differentSize: Int,
        val edges: Int,
        val features: Int,
        val analysis: Int,
        val tags: Int,
        /** Tracks of the inclusion list found here. */
        val inclusion: Int,
        /** Rows with a sound profile taken. */
        val sounds: Int = 0,
    )

    /** The path of [absolute] relative to [root] ('/' separators), or null when it is not under it. */
    fun relative(root: String, absolute: String): String? {
        val r = root.replace('\\', '/').trimEnd('/') + "/"
        val a = absolute.replace('\\', '/')
        return if (a.startsWith(r, ignoreCase = true)) a.substring(r.length) else null
    }

    fun bytes(values: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach(buffer::putFloat)
        return buffer.array()
    }

    fun floats(bytes: ByteArray?): FloatArray? {
        bytes ?: return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { buffer.getFloat() }
    }
}
