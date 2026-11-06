package com.sadurni.attaccaplg.exchange

import com.sadurni.attacca.library.TagText
import com.sadurni.attacca.chain.EdgeDescriptor
import com.sadurni.attacca.chain.TrackFeatures
import com.sadurni.attacca.library.InclusionMode
import com.sadurni.attacca.library.LibraryExchange
import com.sadurni.attacca.library.PathMatcher
import com.sadurni.attaccaplg.analysis.TrackAnalyzer
import com.sadurni.attaccaplg.generate.Inclusions
import com.sadurni.attaccaplg.library.LibraryDb
import com.sadurni.attaccaplg.library.Scanner
import com.sadurni.attaccaplg.library.StoredTrack
import com.sadurni.attaccaplg.library.Tags
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Types

/** The library exchange file (see [LibraryExchange]) as Attacca Desktop writes and reads it. */
object Exchange {

    /** Writes every measured track of [db] (and the inclusion list, if set) to [file]. Returns the rows written. */
    fun export(db: LibraryDb, file: File): Int {
        file.delete()
        file.absoluteFile.parentFile?.mkdirs()
        val tracks = db.all().filter { it.error == null }
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { c ->
            c.autoCommit = false
            c.createStatement().use { s -> LibraryExchange.SCHEMA.forEach(s::execute) }
            meta(c, "format", LibraryExchange.FORMAT.toString())
            meta(c, "app", "Attacca Desktop")
            meta(c, "created", System.currentTimeMillis().toString())
            meta(c, "edge_seconds", db.edgeSeconds.toString())
            meta(c, "threshold_db", TrackAnalyzer.THRESHOLD_DB.toString())
            c.prepareStatement("INSERT INTO tracks (${LibraryExchange.COLUMNS}) VALUES (${List(22) { "?" }.joinToString()})").use { st ->
                for (t in tracks) {
                    var i = 1
                    st.setString(i++, t.path)
                    st.setLong(i++, t.size)
                    st.setLong(i++, t.durationMs)
                    st.setString(i++, t.tags.title)
                    st.setString(i++, t.tags.artist)
                    st.setString(i++, t.tags.album)
                    st.setString(i++, t.tags.albumArtist)
                    st.setString(i++, t.tags.genre)
                    st.setObject(i++, t.tags.year)
                    st.setObject(i++, t.tags.track)
                    st.setObject(i++, t.tags.disc)
                    val analysed = t.waveform != null
                    // Silences also without the waveform (brought from a phone that measured only them).
                    val bounded = analysed || t.audioEndMs > 0
                    st.setObject(i++, if (bounded) TrackAnalyzer.THRESHOLD_DB.toDouble() else null)
                    st.setObject(i++, if (bounded) t.audioStartMs else null)
                    st.setObject(i++, if (bounded) t.audioEndMs else null)
                    st.setObject(i++, t.lufs?.toDouble())
                    st.setObject(i++, if (analysed) t.peak.toDouble() else null)
                    st.setBytes(i++, t.waveform?.let(LibraryExchange::bytes))
                    st.setObject(i++, if (t.head != null || t.tail != null) db.edgeSeconds else null)
                    st.setBytes(i++, t.head?.toArray()?.let(LibraryExchange::bytes))
                    st.setBytes(i++, t.tail?.toArray()?.let(LibraryExchange::bytes))
                    st.setBytes(i++, t.features?.toArray()?.let(LibraryExchange::bytes))
                    st.setBytes(i, t.sound?.toBytes())
                    st.addBatch()
                }
                st.executeBatch()
            }
            // The inclusion list, as library paths.
            db.meta("inclusion_list")?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isFile }?.let { list ->
                val matcher = PathMatcher(tracks.map { it.path })
                val paths = Inclusions.readPlaylist(list).mapNotNull { (location, _) -> matcher.match(location) }.distinct()
                meta(c, "inclusion_mode", db.meta("inclusion_mode") ?: InclusionMode.ALLOW.name)
                c.prepareStatement("INSERT OR IGNORE INTO inclusion (path) VALUES (?)").use { st ->
                    for (p in paths) {
                        st.setString(1, p)
                        st.addBatch()
                    }
                    st.executeBatch()
                }
            }
            c.commit()
        }
        return tracks.size
    }

    private fun meta(c: Connection, key: String, value: String) {
        c.prepareStatement("INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)").use { st ->
            st.setString(1, key)
            st.setString(2, value)
            st.executeUpdate()
        }
    }

    /**
     * Takes the tracks of [file] that are in the music folder [root] (same path, same size) into
     * [db], as if measured here: a scan afterwards leaves them alone. Tracks measured with another
     * edge length or silence threshold keep only what still applies; a library with nothing in it
     * yet takes the file's edge length. The inclusion list, when the
     * file has one, is written beside the library and used by --included.
     */
    fun import(file: File, root: File?, db: LibraryDb): LibraryExchange.ImportResult {
        // Without the music on this PC ([root] null): the library becomes the file's, as it comes. Lists,
        // reordering and the exports for players and for Attacca work; a scan of the music folder later
        // keeps what was measured for the tracks with the same path and size.
        val withoutMusic = root == null
        if (withoutMusic) {
            db.remove(db.stamps().keys)
            db.commit()
            db.clearMeta("music_root")
        }
        val local = root?.let { Scanner(db).audioFiles(it) }.orEmpty()
        val matcher = PathMatcher(local.keys)
        var total = 0
        var matched = 0
        var differentSize = 0
        var edges = 0
        var features = 0
        var sounds = 0
        var analysis = 0
        var inclusion = 0
        root?.let { db.setMeta("music_root", it.absolutePath) }
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { c ->
            val metas = c.createStatement().executeQuery("SELECT key, value FROM meta").use { rs ->
                buildMap { while (rs.next()) put(rs.getString(1), rs.getString(2)) }
            }
            val format = metas["format"]?.toIntOrNull() ?: 0
            require(format <= LibraryExchange.FORMAT) { "the file is from a newer version" }
            // A library with nothing measured yet takes the file's edge length (else its edges would be left out).
            val fileEdges = metas["edge_seconds"]?.toIntOrNull()
            if (fileEdges != null && fileEdges in 4..20 && db.stamps().isEmpty()) db.edgeSeconds = fileEdges
            val edgeSeconds = db.edgeSeconds
            c.createStatement().executeQuery("SELECT ${LibraryExchange.columns(format)} FROM tracks").use { rs ->
                while (rs.next()) {
                    total++
                    val path = if (withoutMusic) {
                        PathMatcher.components(rs.getString("path")).joinToString("/").takeIf { it.isNotEmpty() } ?: continue
                    } else {
                        matcher.match(rs.getString("path")) ?: continue
                    }
                    val localFile = local[path]
                    if (localFile != null && localFile.length() != rs.getLong("size")) {
                        differentSize++
                        continue
                    }
                    // Size and date of the file here; without it, the size it had there and no date.
                    val size = localFile?.length() ?: rs.getLong("size")
                    val mtime = localFile?.lastModified() ?: 0L
                    matched++
                    fun int(name: String) = rs.getInt(name).takeUnless { rs.wasNull() }
                    fun long(name: String) = rs.getLong(name).takeUnless { rs.wasNull() }
                    fun float(name: String) = rs.getDouble(name).takeUnless { rs.wasNull() }?.toFloat()
                    fun blob(name: String) = LibraryExchange.floats(rs.getBytes(name))
                    val sameEdges = int("edge_seconds") == edgeSeconds
                    val sameThreshold = float("threshold") == TrackAnalyzer.THRESHOLD_DB
                    val head = blob("head")?.takeIf { sameEdges && it.size == EdgeDescriptor.SIZE }?.let { EdgeDescriptor.fromArray(it) }
                    val tail = blob("tail")?.takeIf { sameEdges && it.size == EdgeDescriptor.SIZE }?.let { EdgeDescriptor.fromArray(it) }
                    val trackFeatures = blob("features")?.takeIf { it.size == TrackFeatures.SIZE }?.let(TrackFeatures::fromArray)
                    if (head != null || tail != null) edges++
                    if (trackFeatures != null) features++
                    val sound = if (format >= 2) rs.getBytes("sound")?.let(com.sadurni.attacca.chain.SoundProfile::fromBytes) else null
                    if (sound != null) sounds++
                    val endMs = long("end_ms")?.takeIf { sameThreshold && it > 0 }
                    if (endMs != null) analysis++
                    // Edges and features are what lists need: with them the track counts as measured here.
                    // The silence analysis (often missing from a phone, which measures it on playing) is
                    // measured later only if asked for (LibraryDb.remeasureWithoutAnalysis).
                    val complete = (head != null || tail != null) && trackFeatures != null && sound != null
                    db.put(
                        StoredTrack(
                            path = path,
                            size = size,
                            mtime = mtime,
                            // Text a phone's reader decoded twice ("Dâ€™Amour") comes back right.
                            tags = Tags(
                                title = TagText.repair(rs.getString("title").orEmpty()).ifEmpty { File(path).nameWithoutExtension },
                                artist = TagText.repair(rs.getString("artist").orEmpty()),
                                album = TagText.repair(rs.getString("album").orEmpty()),
                                albumArtist = TagText.repair(rs.getString("album_artist").orEmpty()),
                                genre = TagText.repair(rs.getString("genre").orEmpty()),
                                year = int("year")?.takeIf { it > 0 },
                                track = int("track")?.takeIf { it > 0 },
                                disc = int("disc")?.takeIf { it > 0 },
                            ),
                            durationMs = long("duration_ms") ?: 0,
                            // Silences with this threshold only (with or without the waveform).
                            audioStartMs = long("start_ms")?.takeIf { endMs != null } ?: 0,
                            audioEndMs = endMs ?: 0,
                            lufs = float("lufs"),
                            peak = float("peak") ?: 0f,
                            head = head,
                            tail = tail,
                            features = trackFeatures,
                            error = null,
                            waveform = blob("waveform")?.takeIf { sameThreshold },
                            sound = sound,
                        ),
                        version = if (complete) Scanner.VERSION else 0,
                    )
                }
            }
            val included = c.createStatement().executeQuery("SELECT path FROM inclusion").use { rs ->
                buildList { while (rs.next()) matcher.match(rs.getString(1))?.let(::add) }
            }
            if (included.isNotEmpty()) {
                inclusion = included.size
                val list = File(db.file.absoluteFile.parentFile, "inclusion.m3u8")
                Inclusions.writeList(included.distinct(), list)
                db.setMeta("inclusion_list", list.absolutePath)
                db.setMeta("inclusion_mode", metas["inclusion_mode"] ?: InclusionMode.ALLOW.name)
            }
        }
        db.commit()
        return LibraryExchange.ImportResult(total, matched, differentSize, edges, features, analysis, matched, inclusion, sounds)
    }
}
