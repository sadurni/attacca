package com.sadurni.attacca.analysis

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Cache of analysis results keyed by path, invalidated when the file's size or modification time
 * changes, or when the silence threshold is different. A file that failed to decode is remembered
 * too, so it is not retried on every play.
 */
class AnalysisDb(context: Context) : SQLiteOpenHelper(context, "analysis.db", null, VERSION) {

    sealed interface Lookup {
        data class Hit(val analysis: TrackAnalysis) : Lookup
        data object Failed : Lookup
        data object Miss : Lookup
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE analysis (
                path TEXT PRIMARY KEY,
                size INTEGER NOT NULL,
                mtime INTEGER NOT NULL,
                threshold REAL NOT NULL,
                failed INTEGER NOT NULL,
                duration_ms INTEGER NOT NULL,
                start_ms INTEGER NOT NULL,
                end_ms INTEGER NOT NULL,
                waveform BLOB,
                loudness REAL,
                peak REAL NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        createSilences(db)
    }

    /**
     * Silence bounds alone ([SilenceScanner], from the ends of a file), for tracks not decoded
     * whole yet. Created when missing, without a new version: a new version would rebuild the
     * whole cache.
     */
    private fun createSilences(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS silences (
                path TEXT PRIMARY KEY,
                size INTEGER NOT NULL,
                mtime INTEGER NOT NULL,
                threshold REAL NOT NULL,
                failed INTEGER NOT NULL,
                duration_ms INTEGER NOT NULL,
                start_ms INTEGER NOT NULL,
                end_ms INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        if (!db.isReadOnly) createSilences(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // A cache: rebuild rather than migrate.
        db.execSQL("DROP TABLE IF EXISTS analysis")
        onCreate(db)
    }

    fun get(file: File, thresholdDb: Float): Lookup {
        readableDatabase.query(
            "analysis",
            arrayOf("size", "mtime", "threshold", "failed", "duration_ms", "start_ms", "end_ms", "waveform", "loudness", "peak"),
            "path = ?",
            arrayOf(file.absolutePath),
            null, null, null,
        ).use { c ->
            if (!c.moveToFirst()) return Lookup.Miss
            if (!com.sadurni.attacca.library.FileStamp.unchanged(c.getLong(0), c.getLong(1), file)) return Lookup.Miss
            if (c.getInt(3) != 0) return Lookup.Failed
            if (c.getFloat(2) != thresholdDb) return Lookup.Miss
            val blob = c.getBlob(7) ?: return Lookup.Miss
            val waveform = FloatArray(blob.size / 4)
            ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(waveform)
            return Lookup.Hit(
                TrackAnalysis(
                    durationMs = c.getLong(4),
                    audioStartMs = c.getLong(5),
                    audioEndMs = c.getLong(6),
                    waveform = waveform,
                    thresholdDb = thresholdDb,
                    loudnessLufs = if (c.isNull(8)) null else c.getFloat(8),
                    samplePeak = c.getFloat(9),
                ),
            )
        }
    }

    /**
     * [get] for many files in a few queries; files missing from the result are misses. Long lists
     * would otherwise mean one query per track at every start.
     */
    fun getAll(files: List<File>, thresholdDb: Float): Map<String, Lookup> {
        val result = HashMap<String, Lookup>()
        for (chunk in files.chunked(CHUNK)) {
            val byPath = chunk.associateBy { it.absolutePath }
            readableDatabase.rawQuery(
                "SELECT path, size, mtime, threshold, failed, duration_ms, start_ms, end_ms, waveform, loudness, peak FROM analysis WHERE path IN (${chunk.joinToString(",") { "?" }})",
                byPath.keys.toTypedArray(),
            ).use { c ->
                while (c.moveToNext()) {
                    val file = byPath[c.getString(0)] ?: continue
                    if (!com.sadurni.attacca.library.FileStamp.unchanged(c.getLong(1), c.getLong(2), file)) continue
                    if (c.getInt(4) != 0) {
                        result[file.absolutePath] = Lookup.Failed
                        continue
                    }
                    if (c.getFloat(3) != thresholdDb) continue
                    val blob = c.getBlob(8) ?: continue
                    val waveform = FloatArray(blob.size / 4)
                    ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(waveform)
                    result[file.absolutePath] = Lookup.Hit(
                        TrackAnalysis(
                            c.getLong(5), c.getLong(6), c.getLong(7), waveform, thresholdDb,
                            loudnessLufs = if (c.isNull(9)) null else c.getFloat(9),
                            samplePeak = c.getFloat(10),
                        ),
                    )
                }
            }
        }
        return result
    }

    fun put(file: File, analysis: TrackAnalysis?, thresholdDb: Float) {
        val values = ContentValues().apply {
            put("path", file.absolutePath)
            put("size", file.length())
            put("mtime", file.lastModified())
            put("threshold", thresholdDb)
            put("failed", if (analysis == null) 1 else 0)
            put("duration_ms", analysis?.durationMs ?: 0L)
            put("start_ms", analysis?.audioStartMs ?: 0L)
            put("end_ms", analysis?.audioEndMs ?: 0L)
            analysis?.loudnessLufs?.let { put("loudness", it) }
            put("peak", analysis?.samplePeak ?: 0f)
            if (analysis != null) {
                val bytes = ByteBuffer.allocate(analysis.waveform.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                bytes.asFloatBuffer().put(analysis.waveform)
                put("waveform", bytes.array())
            }
        }
        writableDatabase.insertWithOnConflict("analysis", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /**
     * Silence bounds of [files] with [thresholdDb]: from the whole analysis when there is one, else
     * from [SilenceScanner]'s. A null value: the file could not be decoded. Files missing from the
     * result have neither.
     */
    fun getBounds(files: List<File>, thresholdDb: Float): Map<String, SilenceScanner.Bounds?> {
        val result = HashMap<String, SilenceScanner.Bounds?>()
        for (chunk in files.chunked(CHUNK)) {
            val byPath = chunk.associateBy { it.absolutePath }
            for (table in listOf("silences", "analysis")) {
                readableDatabase.rawQuery(
                    "SELECT path, size, mtime, threshold, failed, duration_ms, start_ms, end_ms FROM $table WHERE path IN (${chunk.joinToString(",") { "?" }})",
                    byPath.keys.toTypedArray(),
                ).use { c ->
                    while (c.moveToNext()) {
                        val file = byPath[c.getString(0)] ?: continue
                        if (!com.sadurni.attacca.library.FileStamp.unchanged(c.getLong(1), c.getLong(2), file)) continue
                        if (c.getInt(4) != 0) {
                            // A whole analysis that failed counts; a failed scan of the ends does not hide a whole one.
                            if (table == "analysis" || file.absolutePath !in result) result[file.absolutePath] = null
                            continue
                        }
                        if (c.getFloat(3) != thresholdDb) continue
                        // The whole analysis (read second) wins.
                        result[file.absolutePath] = SilenceScanner.Bounds(c.getLong(5), c.getLong(6), c.getLong(7))
                    }
                }
            }
        }
        return result
    }

    fun putBounds(file: File, bounds: SilenceScanner.Bounds?, thresholdDb: Float) {
        val values = ContentValues().apply {
            put("path", file.absolutePath)
            put("size", file.length())
            put("mtime", file.lastModified())
            put("threshold", thresholdDb)
            put("failed", if (bounds == null) 1 else 0)
            put("duration_ms", bounds?.durationMs ?: 0L)
            put("start_ms", bounds?.audioStartMs ?: 0L)
            put("end_ms", bounds?.audioEndMs ?: 0L)
        }
        writableDatabase.insertWithOnConflict("silences", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private companion object {
        // 2: loudness and peak (a cache: older entries are measured again as they come up).
        const val VERSION = 2
        const val CHUNK = 500
    }
}
