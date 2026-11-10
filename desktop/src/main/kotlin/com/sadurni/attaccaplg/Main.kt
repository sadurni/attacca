package com.sadurni.attaccaplg

import com.sadurni.attacca.chain.Mood
import com.sadurni.attacca.chain.Moods
import com.sadurni.attaccaplg.analysis.TrackAnalyzer
import com.sadurni.attaccaplg.audio.Decoder
import com.sadurni.attaccaplg.library.LibraryDb
import com.sadurni.attaccaplg.library.Scanner
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.system.exitProcess

private const val USAGE = """Attacca Desktop - chained lists from the analysed music

  attacca-desktop scan <music folder> [--db FILE] [--jobs N]
      Reads and measures the music folder (only what changed since the last scan).
  attacca-desktop stats [--db FILE]
      The library: tracks, what could not be measured, moods and sound families.
  attacca-desktop analyze <audio file>
      Everything measured on one file, as JSON.
  attacca-desktop edges [SECONDS]
      The edge length measured for chaining (4-20 s, default 8, as Attacca's setting);
      changing it measures every track again on the next scan.
  attacca-desktop player-db FILE.bpcr2 --path-to BASE [--spacing N]
      The library for the Rockbox plugin and the web generator; BASE: the music folder as the
      player sees it (e.g. /<microSD1>/Music).
  attacca-desktop silences [--jobs N]
      Measures only the silences of tracks without them (brought from a phone that had not
      measured them); their edges and features stay.
  attacca-desktop export FILE.attlib
      The library for Attacca (or another Attacca Desktop): tags, analysis and the inclusion list.
  attacca-desktop import FILE.attlib [<music folder>]
      Takes what Attacca (or another Attacca Desktop) measured, for the tracks of the music folder
      (same path and size); a scan afterwards measures only the rest.
  attacca-desktop import FILE.attlib --no-music
      The library becomes the file's, without the music on this PC: lists, reorder and the
      exports for players and Attacca work (lists without --path-to hold the paths within the
      music folder). A scan of the music folder later keeps what was measured (same path and size).
      An empty library with no music folder imports this way by itself.

  --db FILE   library file (default: %APPDATA%/AttaccaPLG/library.db)
"""

fun main(args: Array<String>) {
    // The chaining weights chosen in the window (chaining guide) count for the command line too.
    runCatching { com.sadurni.attaccaplg.ui.DeskTaste.load(com.sadurni.attaccaplg.ui.Prefs()) }
    val options = Options(args.toList())
    when (options.positional.firstOrNull()) {
        "scan" -> scan(options)
        "stats" -> stats(options)
        "analyze" -> analyze(options)
        "compare-attacca" -> compareAttacca(options)
        "edges" -> edges(options)
        "silences" -> silences(options)
        "player-db" -> playerDb(options)
        "export" -> exportLibrary(options)
        "import" -> importLibrary(options)
        else -> if (!runGenerate(options.positional.firstOrNull().orEmpty(), options)) {
            println(USAGE + GENERATE_USAGE)
            exitProcess(if (args.isEmpty()) 0 else 1)
        }
    }
}

/** `command arg… --name value… --flag…`. */
internal class Options(args: List<String>) {
    val positional = mutableListOf<String>()
    val named = mutableMapOf<String, String>()
    private val flags = mutableSetOf<String>()

    fun flag(name: String) = name in flags

    init {
        var i = 0
        while (i < args.size) {
            val a = args[i]
            if (a.startsWith("--") && a.removePrefix("--") in FLAGS) {
                flags += a.removePrefix("--")
                i++
            } else if (a.startsWith("--") && i + 1 < args.size) {
                named[a.removePrefix("--")] = args[i + 1]
                i += 2
            } else {
                positional += a
                i++
            }
        }
    }

    companion object {
        /** Options without a value. */
        val FLAGS = setOf("included", "same-mood", "same-family", "fit", "no-music")
    }

    val db: File
        get() = named["db"]?.let(::File) ?: LibraryDb.DEFAULT
}

private fun scan(options: Options) {
    val root = options.positional.getOrNull(1)?.let(::File)?.takeIf { it.isDirectory } ?: run {
        System.err.println("scan: a music folder is needed")
        exitProcess(1)
    }
    val jobs = options.named["jobs"]?.toIntOrNull() ?: Scanner.defaultJobs()
    if (Decoder.ffmpeg == null) println("ffmpeg not found: AAC, WMA, Opus… will be listed but not measured.")
    LibraryDb(options.db).use { db ->
        val scanner = Scanner(db, jobs)
        val started = System.currentTimeMillis()
        var lastShown = 0L
        val result = scanner.scan(root) { done, todo, path ->
            val now = System.currentTimeMillis()
            if (now - lastShown > 1000 || done == todo) {
                lastShown = now
                val rate = done * 1000.0 / (now - started).coerceAtLeast(1)
                val left = if (rate > 0) ((todo - done) / rate).toLong() else 0
                print("\r%d/%d  %.1f/s  %d:%02d left  %s".format(done, todo, rate, left / 60, left % 60, path.takeLast(60).padEnd(60)))
            }
        }
        println()
        println(
            "%d tracks: %d measured, %d failed, %d unchanged, %d removed (%.1f s, %d jobs)".format(
                result.total, result.analysed, result.failed, result.unchanged, result.removed, result.millis / 1000.0, jobs,
            ),
        )
        println("Library: ${options.db.absolutePath}")
    }
}

private fun stats(options: Options) {
    LibraryDb(options.db).use { db ->
        val tracks = db.all()
        val measured = tracks.filter { it.features != null }
        println("Music folder: ${db.musicRoot ?: "none (imported without the music)"}")
        println("${tracks.size} tracks, ${measured.size} with features, ${tracks.count { it.head != null && it.tail != null }} with both edges")
        tracks.filter { it.error != null }.groupBy { it.error }.forEach { (error, list) ->
            println("  ${list.size} × $error   (e.g. ${list.first().path})")
        }
        if (measured.size < 2) return
        val classes = Moods.classify(measured.map { it.features!! }, sounds = measured.map { it.sound })
        println()
        println("Moods:")
        for (mood in Mood.entries) println("  %-12s %d".format(mood.name.lowercase(), classes.moods.count { it == mood }))
        println("  (between two: %d)".format(classes.secondMoods.count { it != null }))
        println("Sound families:")
        classes.familyNames.forEachIndexed { i, name ->
            val traits = name.traits.joinToString(", ") { (t, high) -> (if (high) "+" else "-") + t.name.lowercase() }
            println("  %d. %-50s %d".format(i + 1, traits, classes.families.count { it == i }))
        }
    }
}

private fun analyze(options: Options) {
    val file = options.positional.getOrNull(1)?.let(::File)?.takeIf { it.isFile } ?: run {
        System.err.println("analyze: an audio file is needed")
        exitProcess(1)
    }
    val track = TrackAnalyzer.analyze(file) ?: run {
        System.err.println("analyze: could not decode ${file.path}")
        exitProcess(2)
    }
    fun arr(values: FloatArray?) = values?.let { JSONArray(it.map { v -> "%.4f".format(java.util.Locale.ROOT, v).toDouble() }) }
    val a = track.analysis
    println(
        JSONObject()
            .put("file", file.path)
            .put("durationMs", a.durationMs)
            .put("audioStartMs", a.audioStartMs)
            .put("audioEndMs", a.audioEndMs)
            .put("lufs", a.loudnessLufs)
            .put("peak", a.samplePeak)
            .put("head", arr(track.head?.toArray()))
            .put("tail", arr(track.tail?.toArray()))
            .put("features", arr(track.features?.toArray()))
            .toString(2),
    )
}

/**
 * Development check: Attacca's own measures (its chain.db from the phone, music folder at
 * /storage/emulated/0/Music) against this library's, track by track.
 */
private fun compareAttacca(options: Options) {
    val phoneDb = options.positional.getOrNull(1)?.let(::File)?.takeIf { it.isFile } ?: run {
        System.err.println("compare-attacca: Attacca's chain.db is needed")
        exitProcess(1)
    }
    val phoneRoot = options.named["phone-root"] ?: "/storage/emulated/0/Music/"
    val pc = LibraryDb(options.db).use { it.all() }.associateBy { it.path }
    fun floats(b: ByteArray?) = b?.let(LibraryDb::floats)
    val phoneEdges = mutableMapOf<String, Pair<FloatArray?, FloatArray?>>()
    val phoneFeatures = mutableMapOf<String, FloatArray?>()
    java.sql.DriverManager.getConnection("jdbc:sqlite:${phoneDb.absolutePath}").use { c ->
        c.createStatement().executeQuery("SELECT path, head, tail FROM edges WHERE failed = 0").use { rs ->
            while (rs.next()) phoneEdges[rs.getString(1).removePrefix(phoneRoot)] = floats(rs.getBytes(2)) to floats(rs.getBytes(3))
        }
        c.createStatement().executeQuery("SELECT path, data FROM features WHERE failed = 0").use { rs ->
            while (rs.next()) phoneFeatures[rs.getString(1).removePrefix(phoneRoot)] = floats(rs.getBytes(2))
        }
    }
    val sim = com.sadurni.attacca.chain.EdgeFeatures::similarity
    val desc = { a: FloatArray -> com.sadurni.attacca.chain.EdgeDescriptor.fromArray(a) }
    var same = 0.0
    var sameN = 0
    var recognised = 0
    val pcTails = pc.values.mapNotNull { t -> t.tail?.let { t.path to it } }
    val pcHeads = pc.values.mapNotNull { t -> t.head?.let { t.path to it } }
    for ((path, edges) in phoneEdges) {
        val mine = pc[path] ?: continue
        for ((phone, own, all) in listOf(Triple(edges.first, mine.head, pcHeads), Triple(edges.second, mine.tail, pcTails))) {
            if (phone == null || own == null) continue
            val p = desc(phone)
            same += sim(p, own)
            sameN++
            if (all.maxByOrNull { sim(p, it.second) }?.first == path) recognised++
        }
    }
    // Between different tracks, for scale.
    val random = java.util.Random(1)
    var other = 0.0
    val pairs = 2000
    repeat(pairs) {
        val a = pcTails[random.nextInt(pcTails.size)].second
        val b = pcHeads[random.nextInt(pcHeads.size)].second
        other += sim(a, b)
    }
    println("Tracks in both: ${phoneEdges.keys.count { it in pc }} of ${phoneEdges.size} measured on the phone")
    println("Edge similarity, phone vs PC, same edge: %.4f (n = %d)".format(same / sameN.coerceAtLeast(1), sameN))
    println("Edge similarity between different tracks:  %.4f (mean of %d pairs)".format(other / pairs, pairs))
    println("Phone edge recognised among all PC edges: %d of %d".format(recognised, sameN))
    val names = listOf("loudness", "dynamics", "brightness", "bass", "noisiness", "percussiveness", "tempo", "pulse", "mode", "keyClarity", "voice")
    val diffs = Array(names.size) { mutableListOf<Double>() }
    val spread = Array(names.size) { mutableListOf<Double>() }
    for ((path, phone) in phoneFeatures) {
        val own = pc[path]?.features?.toArray() ?: continue
        phone ?: continue
        for (i in names.indices) {
            diffs[i] += kotlin.math.abs((phone[i] - own[i]).toDouble())
            spread[i] += own[i].toDouble()
        }
    }
    println("Features, mean |phone - PC| (and the spread of the value across the library):")
    for (i in names.indices) {
        val values = spread[i]
        if (values.isEmpty()) continue
        val mean = values.average()
        val sd = kotlin.math.sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
        println("  %-15s %.4f   (sd %.4f)".format(names[i], diffs[i].average(), sd))
    }
}

private fun edges(options: Options) {
    LibraryDb(options.db).use { db ->
        val value = options.positional.getOrNull(1)
        if (value == null) {
            println("Edges: ${db.edgeSeconds} s")
            return
        }
        val seconds = value.toIntOrNull()?.takeIf { it in 4..20 } ?: run {
            System.err.println("edges: 4 to 20 seconds")
            exitProcess(1)
        }
        val before = db.edgeSeconds
        db.edgeSeconds = seconds
        println(if (seconds == before) "Edges: $seconds s (unchanged)" else "Edges: $seconds s; the next scan measures every track again.")
    }
}

private fun playerDb(options: Options) {
    val file = options.positional.getOrNull(1)?.let(::File) ?: run {
        System.err.println("player-db: a file name is needed (….bpcr2)")
        exitProcess(1)
    }
    val base = options.named["path-to"] ?: run {
        System.err.println("player-db: --path-to is needed (the music folder as the player sees it)")
        exitProcess(1)
    }
    LibraryDb(options.db).use { db ->
        val library = com.sadurni.attaccaplg.generate.Library(db)
        val inclusion = db.meta("inclusion_list")?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isFile }?.let {
            com.sadurni.attaccaplg.generate.Inclusions.inclusion(it, com.sadurni.attacca.library.InclusionMode.valueOf(db.meta("inclusion_mode") ?: "ALLOW"), library)
        }
        val n = com.sadurni.attaccaplg.export.PlayerDb.write(library, inclusion, base, options.named["spacing"]?.toIntOrNull() ?: 4, db.edgeSeconds, file,
            com.sadurni.attaccaplg.generate.Inclusions.sets(db, library))
        println("$n tracks written to ${file.absolutePath} (${file.length() / 1024} KB)")
    }
}

/** Measures only the silences the tracks lack (brought from a phone), keeping their edges and features. */
private fun silences(options: Options) {
    LibraryDb(options.db).use { db ->
        val root = db.musicRoot?.takeIf { it.isDirectory } ?: run {
            System.err.println("silences: the music folder is needed (scan it first)")
            exitProcess(1)
        }
        val started = System.currentTimeMillis()
        var last = 0L
        val n = Scanner(db, options.named["jobs"]?.toIntOrNull() ?: Scanner.defaultJobs()).measureSilences(root) { done, todo, _ ->
            val now = System.currentTimeMillis()
            if (now - last > 2000 || done == todo) {
                last = now
                print("\r$done / $todo")
            }
        }
        println()
        println("Silences measured for $n tracks (%.1f s).".format((System.currentTimeMillis() - started) / 1000.0))
    }
}

private fun exportLibrary(options: Options) {
    val file = options.positional.getOrNull(1)?.let(::File) ?: run {
        System.err.println("export: a file name is needed (….attlib)")
        exitProcess(1)
    }
    val rows = LibraryDb(options.db).use { com.sadurni.attaccaplg.exchange.Exchange.export(it, file) }
    println("$rows tracks written to ${file.absolutePath}")
}

private fun importLibrary(options: Options) {
    val file = options.positional.getOrNull(1)?.let(::File)?.takeIf { it.isFile } ?: run {
        System.err.println("import: an .attlib file is needed")
        exitProcess(1)
    }
    LibraryDb(options.db).use { db ->
        // Without the music: asked for, or nothing to match the file with (no music folder, empty library).
        val noMusic = options.flag("no-music") ||
            (options.positional.getOrNull(2) == null && db.musicRoot == null && db.stamps().isEmpty())
        val root = if (noMusic) null else options.positional.getOrNull(2)?.let(::File) ?: db.musicRoot
        if (!noMusic && (root == null || !root.isDirectory)) {
            System.err.println("import: the music folder is needed (as for scan), or --no-music to take the file as it is")
            exitProcess(1)
        }
        val r = com.sadurni.attaccaplg.exchange.Exchange.import(file, root, db)
        if (root == null) {
            println("${r.matched} tracks taken without their music: lists and exports work; scanning the music folder later keeps what was measured.")
        } else {
            println("${r.total} tracks in the file: ${r.matched} taken, ${r.differentSize} with another size here, ${r.total - r.matched - r.differentSize} not in this folder.")
        }
        println("Taken: ${r.edges} with edges, ${r.features} with features, ${r.analysis} with the pre-play analysis; ${r.inclusion} tracks in the inclusion list.")
    }
}
