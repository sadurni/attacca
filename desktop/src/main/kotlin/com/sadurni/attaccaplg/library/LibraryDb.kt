package com.sadurni.attaccaplg.library

import com.sadurni.attacca.chain.EdgeDescriptor
import com.sadurni.attacca.chain.TrackFeatures
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/** One track of the library as stored: its file, tags and analysis (null parts: not measured). */
class StoredTrack(
    /** Path relative to the music folder, with '/' separators. */
    val path: String,
    val size: Long,
    val mtime: Long,
    val tags: Tags,
    val durationMs: Long,
    val audioStartMs: Long,
    val audioEndMs: Long,
    val lufs: Float?,
    val peak: Float,
    val head: EdgeDescriptor?,
    val tail: EdgeDescriptor?,
    val features: TrackFeatures?,
    /** Why it could not be analysed, when it could not. */
    val error: String?,
    /** Loudness along the track, the loudest bin 1 (as Attacca's waveform). */
    val waveform: FloatArray? = null,
    /** What it sounds like for families and look-alikes (Attacca's sound profile). */
    val sound: com.sadurni.attacca.chain.SoundProfile? = null,
)

/**
 * The library cache: a SQLite file with every track's tags and analysis, so a new scan only
 * measures what changed (by path, size and date).
 */
class LibraryDb(val file: File) : AutoCloseable {

    private val connection: Connection

    init {
        file.absoluteFile.parentFile?.mkdirs()
        connection = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
        connection.createStatement().use { s ->
            s.execute("PRAGMA journal_mode=WAL")
            s.execute(
                """CREATE TABLE IF NOT EXISTS tracks (
                    path TEXT PRIMARY KEY, size INTEGER, mtime INTEGER, version INTEGER,
                    title TEXT, artist TEXT, album TEXT, album_artist TEXT, genre TEXT, year INTEGER, track INTEGER,
                    duration_ms INTEGER, audio_start_ms INTEGER, audio_end_ms INTEGER, lufs REAL, peak REAL,
                    head BLOB, tail BLOB, features BLOB, error TEXT)""",
            )
            s.execute("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT)")
            // Tracks found damaged (path within the music folder), with the file's size and date then.
            s.execute("CREATE TABLE IF NOT EXISTS damaged (path TEXT PRIMARY KEY, size INTEGER, mtime INTEGER, kind INTEGER, amount INTEGER)")
            // The files already checked for damage (fine or not), with their size and date then.
            s.execute("CREATE TABLE IF NOT EXISTS damage_checked (path TEXT PRIMARY KEY, size INTEGER, mtime INTEGER)")
            // Added later: older libraries get the columns (their tracks are measured again).
            val columns = s.executeQuery("PRAGMA table_info(tracks)").use { rs -> buildSet { while (rs.next()) add(rs.getString("name")) } }
            if ("waveform" !in columns) s.execute("ALTER TABLE tracks ADD COLUMN waveform BLOB")
            if ("disc" !in columns) s.execute("ALTER TABLE tracks ADD COLUMN disc INTEGER")
            if ("sound" !in columns) s.execute("ALTER TABLE tracks ADD COLUMN sound BLOB")
        }
        connection.autoCommit = false
    }

    fun meta(key: String): String? = connection.prepareStatement("SELECT value FROM meta WHERE key = ?").use { st ->
        st.setString(1, key)
        st.executeQuery().use { if (it.next()) it.getString(1) else null }
    }

    fun clearMeta(key: String) {
        connection.prepareStatement("DELETE FROM meta WHERE key = ?").use { st ->
            st.setString(1, key)
            st.executeUpdate()
        }
        connection.commit()
    }

    /** The music folder, or null for a library imported without its music (see [Exchange.import]). */
    val musicRoot: java.io.File? get() = meta("music_root")?.takeIf { it.isNotBlank() }?.let { java.io.File(it) }

    /** Sets the date of a stored track (a track imported without its music, found later by a scan). */
    @Synchronized
    fun setMtime(path: String, mtime: Long) {
        connection.prepareStatement("UPDATE tracks SET mtime = ? WHERE path = ?").use { st ->
            st.setLong(1, mtime)
            st.setString(2, path)
            st.executeUpdate()
        }
    }

    /** The tracks found damaged: path → (size, mtime, kind, amount); see [com.sadurni.attaccaplg.library.Damage]. */
    fun damaged(): Map<String, LongArray> = connection.createStatement().use { s ->
        s.executeQuery("SELECT path, size, mtime, kind, amount FROM damaged").use { rs ->
            buildMap { while (rs.next()) put(rs.getString(1), longArrayOf(rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5))) }
        }
    }

    fun putDamaged(path: String, size: Long, mtime: Long, kind: Int, amount: Int) {
        connection.prepareStatement("INSERT OR REPLACE INTO damaged VALUES (?, ?, ?, ?, ?)").use { st ->
            st.setString(1, path); st.setLong(2, size); st.setLong(3, mtime); st.setInt(4, kind); st.setInt(5, amount)
            st.executeUpdate()
        }
        connection.commit()
    }

    /** The files checked for damage: path → (size, mtime) when checked. */
    fun damageChecked(): Map<String, LongArray> = connection.createStatement().use { s ->
        s.executeQuery("SELECT path, size, mtime FROM damage_checked").use { rs ->
            buildMap { while (rs.next()) put(rs.getString(1), longArrayOf(rs.getLong(2), rs.getLong(3))) }
        }
    }

    fun putDamageChecked(path: String, size: Long, mtime: Long) {
        connection.prepareStatement("INSERT OR REPLACE INTO damage_checked VALUES (?, ?, ?)").use { st ->
            st.setString(1, path); st.setLong(2, size); st.setLong(3, mtime)
            st.executeUpdate()
        }
        connection.commit()
    }

    fun clearDamageChecked() {
        connection.createStatement().use { it.executeUpdate("DELETE FROM damage_checked") }
        connection.commit()
    }

    fun forgetDamaged(path: String) {
        connection.prepareStatement("DELETE FROM damaged WHERE path = ?").use { st ->
            st.setString(1, path)
            st.executeUpdate()
        }
        connection.commit()
    }

    fun setMeta(key: String, value: String) {
        connection.prepareStatement("INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)").use { st ->
            st.setString(1, key)
            st.setString(2, value)
            st.executeUpdate()
        }
        connection.commit()
    }

    /**
     * Seconds of each track's start and end measured for chaining (4–20, as in Attacca's
     * settings). Changing it measures every track again.
     */
    var edgeSeconds: Int
        get() = meta("edge_seconds")?.toIntOrNull() ?: com.sadurni.attacca.chain.EdgeFeatures.DEFAULT_EDGE_SECONDS
        set(value) {
            if (value == edgeSeconds) return
            setMeta("edge_seconds", value.toString())
            connection.createStatement().use { it.executeUpdate("UPDATE tracks SET version = 0") }
            connection.commit()
        }

    /** Tracks without their silences (taken from a phone that had not measured them): the next scan measures them whole. Returns how many. */
    fun remeasureWithoutAnalysis(): Int {
        val n = connection.createStatement().use {
            it.executeUpdate("UPDATE tracks SET version = 0 WHERE (audio_end_ms IS NULL OR audio_end_ms <= 0) AND error IS NULL")
        }
        connection.commit()
        return n
    }

    /** The silences of a stored track (and its length when it had none), leaving the rest as it is. */
    @Synchronized
    fun setSilence(path: String, durationMs: Long, startMs: Long, endMs: Long) {
        connection.prepareStatement(
            "UPDATE tracks SET audio_start_ms = ?, audio_end_ms = ?, " +
                "duration_ms = CASE WHEN duration_ms IS NULL OR duration_ms <= 0 THEN ? ELSE duration_ms END WHERE path = ?",
        ).use { st ->
            st.setLong(1, startMs)
            st.setLong(2, endMs)
            st.setLong(3, durationMs)
            st.setString(4, path)
            st.executeUpdate()
        }
    }

    /** Path → (size, mtime, version) of every stored track. */
    fun stamps(): Map<String, Triple<Long, Long, Int>> = connection.createStatement().use { s ->
        s.executeQuery("SELECT path, size, mtime, version FROM tracks").use { rs ->
            buildMap { while (rs.next()) put(rs.getString(1), Triple(rs.getLong(2), rs.getLong(3), rs.getInt(4))) }
        }
    }

    @Synchronized
    fun put(track: StoredTrack, version: Int) {
        connection.prepareStatement(
            "INSERT OR REPLACE INTO tracks (path, size, mtime, version, title, artist, album, album_artist, genre, year, track, " +
                "duration_ms, audio_start_ms, audio_end_ms, lufs, peak, head, tail, features, error, waveform, disc, sound) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ).use { st ->
            var i = 1
            st.setString(i++, track.path)
            st.setLong(i++, track.size)
            st.setLong(i++, track.mtime)
            st.setInt(i++, version)
            st.setString(i++, track.tags.title)
            st.setString(i++, track.tags.artist)
            st.setString(i++, track.tags.album)
            st.setString(i++, track.tags.albumArtist)
            st.setString(i++, track.tags.genre)
            st.setObject(i++, track.tags.year)
            st.setObject(i++, track.tags.track)
            st.setLong(i++, track.durationMs)
            st.setLong(i++, track.audioStartMs)
            st.setLong(i++, track.audioEndMs)
            st.setObject(i++, track.lufs?.toDouble())
            st.setDouble(i++, track.peak.toDouble())
            st.setBytes(i++, track.head?.toArray()?.let(::bytes))
            st.setBytes(i++, track.tail?.toArray()?.let(::bytes))
            st.setBytes(i++, track.features?.toArray()?.let(::bytes))
            st.setString(i++, track.error)
            st.setBytes(i++, track.waveform?.let(::bytes))
            st.setObject(i++, track.tags.disc)
            st.setBytes(i, track.sound?.toBytes())
            st.executeUpdate()
        }
    }

    @Synchronized
    fun remove(paths: Collection<String>) {
        connection.prepareStatement("DELETE FROM tracks WHERE path = ?").use { st ->
            for (p in paths) {
                st.setString(1, p)
                st.addBatch()
            }
            st.executeBatch()
        }
    }

    @Synchronized
    fun commit() = connection.commit()

    fun all(): List<StoredTrack> = connection.createStatement().use { s ->
        s.executeQuery("SELECT * FROM tracks ORDER BY path").use { rs -> buildList { while (rs.next()) add(read(rs)) } }
    }

    private fun read(rs: ResultSet): StoredTrack {
        fun int(column: String): Int? = rs.getInt(column).takeUnless { rs.wasNull() }
        fun floats(column: String): FloatArray? = rs.getBytes(column)?.let(::floats)
        return StoredTrack(
            path = rs.getString("path"),
            size = rs.getLong("size"),
            mtime = rs.getLong("mtime"),
            tags = Tags(
                title = rs.getString("title").orEmpty(),
                artist = rs.getString("artist").orEmpty(),
                album = rs.getString("album").orEmpty(),
                albumArtist = rs.getString("album_artist").orEmpty(),
                genre = rs.getString("genre").orEmpty(),
                year = int("year"),
                track = int("track"),
                disc = int("disc"),
            ),
            durationMs = rs.getLong("duration_ms"),
            audioStartMs = rs.getLong("audio_start_ms"),
            audioEndMs = rs.getLong("audio_end_ms"),
            lufs = rs.getDouble("lufs").takeUnless { rs.wasNull() }?.toFloat(),
            peak = rs.getDouble("peak").toFloat(),
            head = floats("head")?.let { EdgeDescriptor.fromArray(it) },
            tail = floats("tail")?.let { EdgeDescriptor.fromArray(it) },
            features = floats("features")?.takeIf { it.size == TrackFeatures.SIZE }?.let(TrackFeatures::fromArray),
            error = rs.getString("error"),
            waveform = floats("waveform"),
            sound = rs.getBytes("sound")?.let(com.sadurni.attacca.chain.SoundProfile::fromBytes),
        )
    }

    override fun close() {
        runCatching { connection.commit() }
        connection.close()
    }

    companion object {
        /** Where the app keeps its files: %APPDATA%/AttaccaPLG on Windows, the usual places elsewhere. */
        val HOME: File = run {
            val home = System.getProperty("user.home")
            val os = System.getProperty("os.name").lowercase()
            when {
                // Development: the window's captures and checks keep their files apart.
                System.getProperty("attacca.home") != null -> File(System.getProperty("attacca.home"))
                System.getenv("APPDATA") != null -> File(System.getenv("APPDATA"), "AttaccaPLG")
                "mac" in os -> File(home, "Library/Application Support/AttaccaPLG")
                else -> File(System.getenv("XDG_CONFIG_HOME") ?: "$home/.config", "AttaccaPLG")
            }
        }

        /** The library unless told otherwise. */
        val DEFAULT = File(HOME, "library.db")

        fun bytes(values: FloatArray): ByteArray {
            val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            values.forEach(buffer::putFloat)
            return buffer.array()
        }

        fun floats(bytes: ByteArray): FloatArray {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(bytes.size / 4) { buffer.getFloat() }
        }
    }
}
