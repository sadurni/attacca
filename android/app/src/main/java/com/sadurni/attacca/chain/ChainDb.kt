package com.sadurni.attacca.chain

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Cache of the edge descriptions used for chaining, keyed by path and invalidated when the file's
 * size or modification time changes. Files that failed to decode are remembered as well. Separate
 * from the silence analysis, so changing the silence settings keeps it.
 */
class ChainDb(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {

    init {
        // Several files are analysed at once: lookups need not wait for each other's writes.
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        createEdges(db)
        createFeatures(db)
    }

    private fun createEdges(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE edges (
                path TEXT PRIMARY KEY,
                size INTEGER NOT NULL,
                mtime INTEGER NOT NULL,
                edge_seconds INTEGER NOT NULL,
                failed INTEGER NOT NULL,
                head BLOB,
                tail BLOB
            )
            """.trimIndent(),
        )
    }

    override fun onOpen(db: SQLiteDatabase) {
        // Added later without a new VERSION (that would drop the edges and features).
        if (!db.isReadOnly) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS sound (
                    path TEXT PRIMARY KEY,
                    size INTEGER NOT NULL,
                    mtime INTEGER NOT NULL,
                    failed INTEGER NOT NULL,
                    data BLOB
                )
                """.trimIndent(),
            )
        }
    }

    /**
     * For each of [files] with a sound profile, a number that changes whenever its profile is made
     * again (its row), without reading the profiles themselves.
     */
    fun soundStamps(files: List<File>): Map<String, Long> {
        val result = HashMap<String, Long>()
        for (chunk in files.chunked(CHUNK)) {
            val byPath = chunk.associateBy { it.absolutePath }
            readableDatabase.rawQuery(
                "SELECT path, size, mtime, failed, rowid FROM sound WHERE path IN (${chunk.joinToString(",") { "?" }})",
                byPath.keys.toTypedArray(),
            ).use { c ->
                while (c.moveToNext()) {
                    val file = byPath[c.getString(0)] ?: continue
                    if (!com.sadurni.attacca.library.FileStamp.unchanged(c.getLong(1), c.getLong(2), file)) continue
                    if (c.getInt(3) == 0) result[file.absolutePath] = c.getLong(4) or (1L shl 62)
                }
            }
        }
        return result
    }

    /** Known sound profiles of [files], like [getFeatures]. */
    fun getSounds(files: List<File>): Map<String, SoundProfile?> {
        val result = HashMap<String, SoundProfile?>()
        for (chunk in files.chunked(CHUNK)) {
            val byPath = chunk.associateBy { it.absolutePath }
            readableDatabase.rawQuery(
                "SELECT path, size, mtime, failed, data FROM sound WHERE path IN (${chunk.joinToString(",") { "?" }})",
                byPath.keys.toTypedArray(),
            ).use { c ->
                while (c.moveToNext()) {
                    val file = byPath[c.getString(0)] ?: continue
                    if (!com.sadurni.attacca.library.FileStamp.unchanged(c.getLong(1), c.getLong(2), file)) continue
                    result[file.absolutePath] = if (c.getInt(3) != 0) null else c.getBlob(4)?.let(SoundProfile::fromBytes)
                }
            }
        }
        return result
    }

    fun putSound(file: File, sound: SoundProfile?) {
        val values = ContentValues().apply {
            put("path", file.absolutePath)
            put("size", file.length())
            put("mtime", file.lastModified())
            put("failed", if (sound == null) 1 else 0)
            put("data", sound?.toBytes())
        }
        writableDatabase.insertWithOnConflict("sound", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun createFeatures(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE features (
                path TEXT PRIMARY KEY,
                size INTEGER NOT NULL,
                mtime INTEGER NOT NULL,
                failed INTEGER NOT NULL,
                data BLOB
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion in 2..3) {
            // Version 3 added the features; version 4 the timbre and tempo of the edges, so only
            // the edges are described again (the features and sound profiles stay valid).
            if (oldVersion == 2) createFeatures(db)
            db.execSQL("DROP TABLE IF EXISTS edges")
            createEdges(db)
            return
        }
        // A cache: rebuild rather than migrate (a new version means descriptions changed).
        db.execSQL("DROP TABLE IF EXISTS edges")
        db.execSQL("DROP TABLE IF EXISTS features")
        onCreate(db)
    }

    /**
     * Known whole-track features of [files], like [getAll]: a file maps to its features, or to
     * null when it failed; files never seen or changed since are absent.
     */
    fun getFeatures(files: List<File>): Map<String, TrackFeatures?> {
        val result = HashMap<String, TrackFeatures?>()
        for (chunk in files.chunked(CHUNK)) {
            val byPath = chunk.associateBy { it.absolutePath }
            readableDatabase.rawQuery(
                "SELECT path, size, mtime, failed, data FROM features WHERE path IN (${chunk.joinToString(",") { "?" }})",
                byPath.keys.toTypedArray(),
            ).use { c ->
                while (c.moveToNext()) {
                    val file = byPath[c.getString(0)] ?: continue
                    if (!com.sadurni.attacca.library.FileStamp.unchanged(c.getLong(1), c.getLong(2), file)) continue
                    val values = c.getBlob(4)?.let(::floats)
                    result[file.absolutePath] =
                        if (c.getInt(3) != 0 || values?.size != TrackFeatures.SIZE) null else TrackFeatures.fromArray(values)
                }
            }
        }
        return result
    }

    fun putFeatures(file: File, features: TrackFeatures?) {
        val values = ContentValues().apply {
            put("path", file.absolutePath)
            put("size", file.length())
            put("mtime", file.lastModified())
            put("failed", if (features == null) 1 else 0)
            put("data", features?.let { bytes(it.toArray()) })
        }
        writableDatabase.insertWithOnConflict("features", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /**
     * Known results for [files] with edges of [edgeSeconds] (a few queries for any number). A
     * file maps to its edges, or to null when it failed to decode; files never seen, changed
     * since or described with another edge length are absent.
     */
    fun getAll(files: List<File>, edgeSeconds: Int): Map<String, TrackEdges?> {
        val result = HashMap<String, TrackEdges?>()
        for (chunk in files.chunked(CHUNK)) {
            val byPath = chunk.associateBy { it.absolutePath }
            readableDatabase.rawQuery(
                "SELECT path, size, mtime, failed, head, tail, edge_seconds FROM edges WHERE path IN (${chunk.joinToString(",") { "?" }})",
                byPath.keys.toTypedArray(),
            ).use { c ->
                while (c.moveToNext()) {
                    val file = byPath[c.getString(0)] ?: continue
                    if (!com.sadurni.attacca.library.FileStamp.unchanged(c.getLong(1), c.getLong(2), file)) continue
                    if (c.getInt(6) != edgeSeconds) continue
                    result[file.absolutePath] = if (c.getInt(3) != 0) null else TrackEdges(decode(c.getBlob(4)), decode(c.getBlob(5)))
                }
            }
        }
        return result
    }

    /** Forgets every result, so all files are analysed again. */
    fun clear() {
        writableDatabase.delete("edges", null, null)
        writableDatabase.delete("features", null, null)
        writableDatabase.delete("sound", null, null)
    }

    fun put(file: File, edges: TrackEdges?, edgeSeconds: Int) {
        val values = ContentValues().apply {
            put("path", file.absolutePath)
            put("size", file.length())
            put("mtime", file.lastModified())
            put("edge_seconds", edgeSeconds)
            put("failed", if (edges == null) 1 else 0)
            put("head", edges?.head?.let(::encode))
            put("tail", edges?.tail?.let(::encode))
        }
        writableDatabase.insertWithOnConflict("edges", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun encode(d: EdgeDescriptor): ByteArray = bytes(d.toArray())

    private fun decode(blob: ByteArray?): EdgeDescriptor? {
        if (blob == null || blob.size != EdgeDescriptor.SIZE * 4) return null
        return EdgeDescriptor.fromArray(floats(blob))
    }

    private fun bytes(values: FloatArray): ByteArray {
        val bytes = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        bytes.asFloatBuffer().put(values)
        return bytes.array()
    }

    private fun floats(blob: ByteArray): FloatArray {
        val values = FloatArray(blob.size / 4)
        ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(values)
        return values
    }

    companion object {
        const val NAME = "chain.db"
        private const val VERSION = 4
        private const val CHUNK = 500
    }
}
