package com.sadurni.attacca.library

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.sadurni.attacca.analysis.AnalysisDb
import com.sadurni.attacca.analysis.TrackAnalysis
import com.sadurni.attacca.app
import com.sadurni.attacca.chain.EdgeDescriptor
import com.sadurni.attacca.chain.ListGenerator
import com.sadurni.attacca.chain.TrackEdges
import com.sadurni.attacca.chain.TrackFeatures
import java.io.File

/**
 * The library exchange file ([LibraryExchange], shared with Attacca Desktop) as Attacca writes and
 * reads it: what the app knows about the music folder's tracks (tags, pre-play analysis,
 * chaining edges and features, the inclusion list), so a collection measured on the PC need not
 * be measured again on the phone, and the other way round. Blocking; call off the main thread.
 */
object LibraryTransfer {

    /** Tracks read from the databases at a time while exporting. */
    private const val EXPORT_BLOCK = 500

    /** Writes what is known about the music folder's tracks to [uri]. Returns the tracks written (null: no music folder). */
    fun export(context: Context, uri: Uri): Int? {
        val app = context.app
        val root = app.settings.musicFolder?.let(::File)?.takeIf { it.isDirectory } ?: return null
        val files = AudioFiles.collectAudio(root, recent = true, withIgnored = true)
        val chain = app.settings.chain.value
        val threshold = app.settings.trim.value.thresholdDb
        val temp = File(context.cacheDir, "export.${LibraryExchange.EXTENSION}").apply { delete() }
        var written = 0
        SQLiteDatabase.openOrCreateDatabase(temp, null).use { db ->
            LibraryExchange.SCHEMA.forEach(db::execSQL)
            fun meta(key: String, value: String) = db.insert("meta", null, ContentValues().apply {
                put("key", key)
                put("value", value)
            })
            meta("format", LibraryExchange.FORMAT.toString())
            meta("app", "Attacca")
            meta("created", System.currentTimeMillis().toString())
            meta("edge_seconds", chain.edgeSeconds.toString())
            meta("threshold_db", threshold.toString())
            db.beginTransaction()
            try {
                // A block of tracks at a time: all the sound profiles and waveforms of a big
                // collection at once do not fit in the app's memory.
                for (block in files.chunked(EXPORT_BLOCK)) {
                    val tags = TrackSort.db?.getAll(block.map { it.absolutePath }).orEmpty()
                    val edges = app.chainAnalyzer.db.getAll(block, chain.edgeSeconds)
                    val features = app.chainAnalyzer.db.getFeatures(block)
                    val sounds = app.chainAnalyzer.db.getSounds(block)
                    val analyses = app.analysisDb.getAll(block, threshold)
                    // Silences also of tracks only scanned at their ends (no waveform, no loudness).
                    val bounds = app.analysisDb.getBounds(block, threshold)
                    for (file in block) {
                        val path = LibraryExchange.relative(root.path, file.absolutePath) ?: continue
                        val tag = tags[file.absolutePath]
                        val edge = edges[file.absolutePath]
                        val feature = features[file.absolutePath]
                        val sound = sounds[file.absolutePath]
                        val analysis = (analyses[file.absolutePath] as? AnalysisDb.Lookup.Hit)?.analysis
                        val silence = bounds[file.absolutePath]
                        if (tag == null && edge == null && feature == null && analysis == null && silence == null) continue
                        db.insert("tracks", null, ContentValues().apply {
                            put("path", path)
                            put("size", file.length())
                            put("duration_ms", analysis?.durationMs ?: silence?.durationMs ?: tag?.durationMs)
                            tag?.let {
                                put("title", it.title)
                                put("artist", it.artist)
                                put("album", it.album)
                                put("album_artist", it.albumArtist)
                                put("genre", it.genre)
                                put("year", it.year)
                                put("track", it.track)
                                put("disc", it.disc)
                            }
                            analysis?.let {
                                put("threshold", it.thresholdDb)
                                put("start_ms", it.audioStartMs)
                                put("end_ms", it.audioEndMs)
                                it.loudnessLufs?.let { l -> put("lufs", l) }
                                put("peak", it.samplePeak)
                                put("waveform", LibraryExchange.bytes(it.waveform))
                            } ?: silence?.let {
                                put("threshold", threshold)
                                put("start_ms", it.audioStartMs)
                                put("end_ms", it.audioEndMs)
                            }
                            if (edge != null) {
                                put("edge_seconds", chain.edgeSeconds)
                                edge.head?.let { put("head", LibraryExchange.bytes(it.toArray())) }
                                edge.tail?.let { put("tail", LibraryExchange.bytes(it.toArray())) }
                            }
                            feature?.let { put("features", LibraryExchange.bytes(it.toArray())) }
                            sound?.let { put("sound", it.toBytes()) }
                        })
                        written++
                    }
                }
                // The inclusion list, by paths relative to the music folder.
                chain.inclusionList?.let(::File)?.takeIf { it.isFile }?.let { list ->
                    meta("inclusion_mode", chain.inclusionMode.name)
                    for (entry in PlaylistFiles.parse(list)) {
                        val path = LibraryExchange.relative(root.path, Inclusion.normalize(entry.location)) ?: continue
                        db.insertWithOnConflict("inclusion", null, ContentValues().apply { put("path", path) }, SQLiteDatabase.CONFLICT_IGNORE)
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        context.contentResolver.openOutputStream(uri, "wt")?.use { out -> temp.inputStream().use { it.copyTo(out) } }
        temp.delete()
        return written
    }

    /**
     * Takes from the file at [uri] what applies to the music folder's tracks (same path, same
     * size) into the app's caches, as if measured here. Edges measured with another edge length,
     * or analyses with another silence threshold, are left out (they would be measured again
     * anyway). The inclusion list is taken only when none is set here. Null: no music folder.
     */
    fun import(context: Context, uri: Uri): LibraryExchange.ImportResult? {
        val app = context.app
        val root = app.settings.musicFolder?.let(::File)?.takeIf { it.isDirectory } ?: return null
        val temp = File(context.cacheDir, "import.${LibraryExchange.EXTENSION}").apply { delete() }
        context.contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().use { input.copyTo(it) } } ?: return null
        val local = AudioFiles.collectAudio(root, recent = true, withIgnored = true).associateBy { LibraryExchange.relative(root.path, it.absolutePath).orEmpty() }
        val matcher = PathMatcher(local.keys)
        val chain = app.settings.chain.value
        val threshold = app.settings.trim.value.thresholdDb
        var total = 0
        var matched = 0
        var differentSize = 0
        var edges = 0
        var features = 0
        var sounds = 0
        var analyses = 0
        var tags = 0
        var inclusion = 0
        val tagRows = HashMap<String, SortTags>()
        try {
            SQLiteDatabase.openDatabase(temp.path, null, SQLiteDatabase.OPEN_READONLY).use { src ->
                val meta = src.rawQuery("SELECT key, value FROM meta", null).use { c ->
                    buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) }
                }
                val format = meta["format"]?.toIntOrNull() ?: 0
                if (format > LibraryExchange.FORMAT) error("from a newer version")
                val chainDb = app.chainAnalyzer.db.writableDatabase
                app.analysisDb.let { analysisDb ->
                    chainDb.beginTransaction()
                    analysisDb.writableDatabase.beginTransaction()
                    try {
                        src.rawQuery("SELECT ${LibraryExchange.columns(format)} FROM tracks", null).use { c ->
                            fun col(name: String) = c.getColumnIndexOrThrow(name)
                            fun int(name: String) = col(name).takeUnless(c::isNull)?.let(c::getInt)
                            fun long(name: String) = col(name).takeUnless(c::isNull)?.let(c::getLong)
                            fun float(name: String) = col(name).takeUnless(c::isNull)?.let(c::getFloat)
                            fun string(name: String) = col(name).takeUnless(c::isNull)?.let(c::getString)
                            fun blob(name: String) = col(name).takeUnless(c::isNull)?.let(c::getBlob)?.let(LibraryExchange::floats)
                            while (c.moveToNext()) {
                                total++
                                val path = matcher.match(c.getString(col("path"))) ?: continue
                                val file = local.getValue(path)
                                if (file.length() != c.getLong(col("size"))) {
                                    differentSize++
                                    continue
                                }
                                matched++
                                string("title")?.let { title ->
                                    tagRows[file.absolutePath] = SortTags(
                                        title = title,
                                        artist = string("artist").orEmpty(),
                                        album = string("album").orEmpty(),
                                        disc = int("disc") ?: 0,
                                        track = int("track") ?: 0,
                                        year = int("year") ?: 0,
                                        durationMs = long("duration_ms") ?: 0,
                                        albumArtist = string("album_artist").orEmpty(),
                                        genre = string("genre").orEmpty(),
                                    )
                                    tags++
                                }
                                if (int("edge_seconds") == chain.edgeSeconds) {
                                    val head = blob("head")?.takeIf { it.size == EdgeDescriptor.SIZE }?.let { EdgeDescriptor.fromArray(it) }
                                    val tail = blob("tail")?.takeIf { it.size == EdgeDescriptor.SIZE }?.let { EdgeDescriptor.fromArray(it) }
                                    if (head != null || tail != null) {
                                        app.chainAnalyzer.db.put(file, TrackEdges(head, tail), chain.edgeSeconds)
                                        edges++
                                    }
                                }
                                blob("features")?.takeIf { it.size == TrackFeatures.SIZE }?.let {
                                    app.chainAnalyzer.db.putFeatures(file, TrackFeatures.fromArray(it))
                                    features++
                                }
                                if (format >= 2) {
                                    c.getBlob(col("sound"))?.let(com.sadurni.attacca.chain.SoundProfile::fromBytes)?.let {
                                        app.chainAnalyzer.db.putSound(file, it)
                                        sounds++
                                    }
                                }
                                val waveform = blob("waveform")
                                val start = long("start_ms")
                                val end = long("end_ms")
                                if (float("threshold") == threshold && waveform != null && start != null && end != null) {
                                    val analysis = TrackAnalysis(long("duration_ms") ?: 0, start, end, waveform, threshold, float("lufs"), float("peak") ?: 0f)
                                    analysisDb.put(file, analysis, threshold)
                                    analyses++
                                } else if (float("threshold") == threshold && start != null && end != null && end > start) {
                                    // Silences measured at the ends only: kept as such.
                                    analysisDb.putBounds(file, com.sadurni.attacca.analysis.SilenceScanner.Bounds(long("duration_ms") ?: 0, start, end), threshold)
                                    analyses++
                                }
                            }
                        }
                        chainDb.setTransactionSuccessful()
                        analysisDb.writableDatabase.setTransactionSuccessful()
                    } finally {
                        chainDb.endTransaction()
                        analysisDb.writableDatabase.endTransaction()
                    }
                }
                TrackSort.db?.putAll(tagRows)
                // The inclusion list, unless one is set here already.
                if (chain.inclusionList == null) {
                    val paths = src.rawQuery("SELECT path FROM inclusion", null).use { c ->
                        buildList { while (c.moveToNext()) matcher.match(c.getString(0))?.let { add(local.getValue(it).absolutePath) } }
                    }
                    if (paths.isNotEmpty()) {
                        inclusion = paths.size
                        val list = File(UserLists.directory, "Inclusion.m3u8")
                        list.parentFile?.mkdirs()
                        list.writeText((listOf("#EXTM3U") + paths.distinct()).joinToString("\n") + "\n")
                        val mode = runCatching { InclusionMode.valueOf(meta["inclusion_mode"] ?: "") }.getOrDefault(InclusionMode.ALLOW)
                        app.settings.updateChain { it.copy(inclusionList = list.absolutePath, inclusionMode = mode) }
                    }
                }
            }
        } finally {
            temp.delete()
        }
        return LibraryExchange.ImportResult(total, matched, differentSize, edges, features, analyses, tags, inclusion, sounds)
    }

    /**
     * Writes the library for players ([PlayerDbFormat], `.bpcr2`) to [uri]: the analysed tracks of
     * the music folder, classified as for the generated lists, with paths for a device whose music
     * folder is [base]. Returns the tracks written (null: no music folder or nothing analysed).
     * Runs in the background (reading the analysis, writing the file), never on the caller's thread.
     */
    suspend fun exportForPlayers(context: Context, uri: Uri, base: String): Int? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { writeForPlayers(context, uri, base) }

    private suspend fun writeForPlayers(context: Context, uri: Uri, base: String): Int? {
        val app = context.app
        val root = app.settings.musicFolder?.let(::File)?.takeIf { it.isDirectory } ?: return null
        val collection = app.chainLists.collection() ?: return null
        val chain = app.settings.chain.value
        val inclusion = chain.inclusion()
        // Every inclusion set goes with the library, for the player to choose from.
        val sets = chain.inclusionSets.take(InclusionSet.MAX_FOR_PLAYERS)
        val setFilters = sets.map { set ->
            Inclusion.of(set.list, set.mode) ?: Inclusion(set.mode) { emptyList() }
        }
        val all = collection.generator.candidates
        val inFolder = all.filter { LibraryExchange.relative(root.path, it.id) != null }
        if (inFolder.isEmpty()) return null
        // Track i of the file is track i of the classification.
        val classification = if (inFolder.size == all.size) collection.generator.classification else ListGenerator(inFolder).classification
        // How each track starts and ends, for the player's automatic crossfade: from its waveform
        // and silences, judged for the crossfade length set here (else the usual 10 s).
        val threshold = app.settings.trim.value.thresholdDb
        val blendMs = app.settings.playback.value.crossfadeMs.takeIf { it > 0 } ?: PlayerDbFormat.REFERENCE_BLEND_MS
        val ends = HashMap<String, PlayerDbFormat.Ends>()
        for (block in inFolder.map { File(it.id) }.chunked(EXPORT_BLOCK)) {
            val analyses = app.analysisDb.getAll(block, threshold)
            val bounds = app.analysisDb.getBounds(block, threshold)
            for (file in block) {
                val analysis = (analyses[file.absolutePath] as? AnalysisDb.Lookup.Hit)?.analysis
                val end = analysis?.let { PlayerDbFormat.Ends.of(it.waveform, it.durationMs, it.audioStartMs, it.audioEndMs, blendMs) }
                    ?: bounds[file.absolutePath]?.let { PlayerDbFormat.Ends.of(null, it.durationMs, it.audioStartMs, it.audioEndMs, blendMs) }
                if (end != null && end.soundEndMs > end.soundStartMs) ends[file.absolutePath] = end
            }
        }
        val tracks = inFolder.map { c ->
            val tags = TrackSort.tags(c.id)
            PlayerDbFormat.Track(
                path = LibraryExchange.relative(root.path, c.id)!!,
                title = tags.title,
                album = tags.album,
                artist = c.artist,
                genre = c.genre,
                durationMs = c.durationMs,
                year = c.year,
                features = c.features,
                head = c.edges?.head,
                tail = c.edges?.tail,
                included = inclusion?.includes(c.id) ?: true,
                ends = ends[c.id],
                sets = setFilters.foldIndexed(0) { k, bits, f -> if (f.includes(c.id)) bits or (1 shl k) else bits },
            )
        }
        val temp = File(context.cacheDir, "export.${PlayerDbFormat.EXTENSION}").apply { delete() }
        try {
            val written = PlayerDbFormat.write(tracks, classification, base, chain.artistSpacing, chain.edgeSeconds, temp, sets.map { it.name }, "Attacca ${com.sadurni.attacca.BuildConfig.VERSION_NAME}")
            context.contentResolver.openOutputStream(uri, "wt")?.use { out -> temp.inputStream().use { it.copyTo(out) } } ?: return null
            return written
        } finally {
            temp.delete()
        }
    }
}
