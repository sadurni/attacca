package com.sadurni.attaccaplg

import com.sadurni.attacca.chain.ChainFilters
import com.sadurni.attacca.chain.Mood
import com.sadurni.attacca.chain.VoiceFilter
import com.sadurni.attacca.library.InclusionMode
import com.sadurni.attaccaplg.export.PathMapping
import com.sadurni.attaccaplg.export.PlaylistFormat
import com.sadurni.attaccaplg.export.PlaylistWriter
import com.sadurni.attaccaplg.generate.Inclusions
import com.sadurni.attaccaplg.generate.Library
import com.sadurni.attaccaplg.generate.Playlist
import com.sadurni.attaccaplg.library.LibraryDb
import java.io.File
import kotlin.random.Random
import kotlin.system.exitProcess

internal const val GENERATE_USAGE = """
Lists (written to --out, default ./playlists):
  attacca-desktop generate [filters] [--count N] [--start TRACK] [--name NAME]
      One chained list of the tracks passing the filters.
  attacca-desktop template PATTERN [filters] [--count N]
      One list per value of PATTERN, e.g. "{mood}_{family}", "{genre}", "{decade}_{mood}".
      Fields: mood, family, genre, artist, albumartist, album, year, decade, folder.
  attacca-desktop auto [filters] [--count N] [--min N]
      One list per mood and sound family, as Attacca's generated lists.
  attacca-desktop seed TRACK [filters] [--count N] [--same-mood] [--same-family] [--fit]
      The tracks closest to TRACK, chained from it. --fit sets years, BPM, energy, positivity and
      voice around the track's, leaving 10 times the tracks asked for (at least twice).
  attacca-desktop reorder PLAYLIST [--start TRACK] [--name NAME]
      A playlist (m3u, m3u8, pls, cue, xspf) in chained order.
  attacca-desktop more KIND [TRACK] [--to TRACK] [--total N] [--list PLAYLIST] [filters] [--count N]
      Other lists, as Attacca's: rise-fall, warm-up, wind-down (energy curves; TRACK optional,
      where they start), sampler (the most typical tracks of every sound family), opposite TRACK,
      time TRACK --total N (about N minutes in all like TRACK), albums TRACK (whole albums like its
      album), bridge TRACK --to TRACK (a way from one to the other), like --list PLAYLIST (tracks
      like the playlist's, without them). All chained end into start.
  attacca-desktop find TEXT
      Tracks whose path, artist, title or album hold every word of TEXT (to pick seeds).

Which tracks lists may use:
  attacca-desktop inclusion LIST [--mode allow|block]   use an M3U8 list (Attacca's, or one made here) or
                                                   Attacca's rules file (.rules.txt, worked out each time)
  attacca-desktop inclusion none                        every track
  attacca-desktop import-beets-include BEETS.db         make the list from beets' "include" marks

Filters: --mood calm,tense  --family 1,3  --artist TEXT  --genre TEXT  --year 1990-1999
         --bpm 90-130  --energy 50-100  --positivity 0-40 (percent of the library)
         --minutes 1-20 (track length; that is the default, --minutes any takes every length)
Lists:   --count N (tracks per list, default 150; --count all: every track that passes)
         --voice voice|instrumental  --included (only what the inclusion list allows)
Output:  --out DIR  --format m3u8|xspf|rockbox  --path-to BASE (where the music is on the
         device: /storage/emulated/0/Music, /<microSD1>/Music, D:\Music…; default: the PC's paths)
         --spacing N (tracks before an artist repeats, default 4)  --random-seed N
"""

internal fun runGenerate(command: String, options: Options): Boolean {
    when (command) {
        "generate", "template", "auto", "seed", "reorder", "more", "find", "inclusion", "import-beets-include" -> Unit
        else -> return false
    }
    LibraryDb(options.db).use { db ->
        val library = Library(db)
        if (library.all.isEmpty()) fail("the library is empty: scan a music folder first")
        when (command) {
            "find" -> find(library, options)
            "inclusion" -> setInclusion(db, options)
            "import-beets-include" -> importBeets(db, library, options)
            else -> makeLists(command, db, library, options)
        }
    }
    return true
}

/** Tracks per list unless asked otherwise (as Attacca). */
internal const val DEFAULT_COUNT = 150

/** Default track length range of lists, minutes (as Attacca's). */
internal const val DEFAULT_MINUTES_MIN = 1
internal const val DEFAULT_MINUTES_MAX = 20

private fun fail(message: String): Nothing {
    System.err.println(message)
    exitProcess(1)
}

private fun find(library: Library, options: Options) {
    val text = options.positional.drop(1).joinToString(" ").ifBlank { fail("find: some text is needed") }
    val found = library.find(text)
    for (t in found.take(50)) {
        val mood = library.moodOf(t.path)?.name?.lowercase() ?: "-"
        val family = library.familyOf(t.path)?.let { "${it + 1}" } ?: "-"
        println("%-12s %-3s %s".format(mood, family, t.path))
    }
    if (found.size > 50) println("… ${found.size - 50} more")
}

private fun setInclusion(db: LibraryDb, options: Options) {
    val list = options.positional.getOrNull(1) ?: fail("inclusion: a list file, or none")
    if (list == "none") {
        db.setMeta("inclusion_list", "")
        println("Lists may use every track.")
        return
    }
    val file = File(list).takeIf { it.isFile } ?: fail("inclusion: no file $list")
    val mode = options.named["mode"]?.uppercase()?.let { runCatching { InclusionMode.valueOf(it) }.getOrNull() } ?: InclusionMode.ALLOW
    db.setMeta("inclusion_list", file.absolutePath)
    db.setMeta("inclusion_mode", mode.name)
    println("Inclusion list: ${file.absolutePath} (${mode.name.lowercase()}); --included uses it.")
}

private fun importBeets(db: LibraryDb, library: Library, options: Options) {
    val beets = options.positional.getOrNull(1)?.let(::File)?.takeIf { it.isFile } ?: fail("import-beets-include: the beets library file is needed")
    val result = Inclusions.importBeets(beets, library)
    val out = options.named["out"]?.let(::File) ?: File(options.db.absoluteFile.parentFile, "inclusion.m3u8")
    Inclusions.writeList(result.included, out)
    db.setMeta("inclusion_list", out.absolutePath)
    db.setMeta("inclusion_mode", InclusionMode.ALLOW.name)
    println("${result.included.size} included, ${result.excluded} left out, ${result.unmatched} not in this library.")
    println("Inclusion list: ${out.absolutePath} (allow); --included uses it.")
}

private fun filters(db: LibraryDb, library: Library, options: Options): ChainFilters {
    fun range(name: String): Pair<Float?, Float?> {
        val raw = options.named[name] ?: return null to null
        val parts = raw.split('-', limit = 2)
        return parts[0].toFloatOrNull() to (parts.getOrNull(1) ?: parts[0]).toFloatOrNull()
    }
    val moods = options.named["mood"]?.split(',')?.map { m ->
        Mood.entries.firstOrNull { it.name.equals(m.trim(), ignoreCase = true) } ?: fail("unknown mood $m (${Mood.entries.joinToString { it.name.lowercase() }})")
    }?.toSet()
    val families = options.named["family"]?.split(',')?.map { (it.trim().toIntOrNull() ?: fail("--family takes numbers (see stats)")) - 1 }?.toSet()
    val (yearFrom, yearTo) = range("year")
    val (bpmMin, bpmMax) = range("bpm")
    val (energyMin, energyMax) = range("energy")
    val (posMin, posMax) = range("positivity")
    // Track length: 1 to 20 minutes unless asked otherwise, as in Attacca.
    val (minutesMin, minutesMax) = when (options.named["minutes"]?.lowercase()) {
        null -> DEFAULT_MINUTES_MIN.toFloat() to DEFAULT_MINUTES_MAX.toFloat()
        "any", "all" -> null to null
        else -> range("minutes")
    }
    val inclusion = if (options.flag("included")) {
        val list = db.meta("inclusion_list")?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isFile }
            ?: fail("--included: no inclusion list (see inclusion, import-beets-include)")
        Inclusions.inclusion(list, InclusionMode.valueOf(db.meta("inclusion_mode") ?: "ALLOW"), library)
    } else {
        null
    }
    return ChainFilters(
        moods = moods,
        families = families,
        artist = options.named["artist"],
        genre = options.named["genre"],
        yearFrom = yearFrom?.toInt(),
        yearTo = yearTo?.toInt(),
        bpmMin = bpmMin?.toInt(),
        bpmMax = bpmMax?.toInt(),
        durationMinS = minutesMin?.takeIf { it > 0 }?.let { Math.round(it * 60) },
        durationMaxS = minutesMax?.let { Math.round(it * 60) },
        energyMin = energyMin?.div(100),
        energyMax = energyMax?.div(100),
        positivityMin = posMin?.div(100),
        positivityMax = posMax?.div(100),
        voice = when (options.named["voice"]?.lowercase()) {
            null -> VoiceFilter.ANY
            "voice", "vocal" -> VoiceFilter.VOICE
            "instrumental" -> VoiceFilter.INSTRUMENTAL
            else -> fail("--voice: voice or instrumental")
        },
        inclusion = inclusion,
    )
}

private fun makeLists(command: String, db: LibraryDb, library: Library, options: Options) {
    val spacing = options.named["spacing"]?.toIntOrNull() ?: 4
    // 150 tracks unless asked otherwise (--count all: every track that passes).
    val count = options.named["count"].let { if (it.equals("all", ignoreCase = true)) null else it?.toIntOrNull() ?: DEFAULT_COUNT }
    val random = options.named["random-seed"]?.toLongOrNull()?.let { Random(it) } ?: Random.Default
    val format = options.named["format"]?.let { f -> PlaylistFormat.entries.firstOrNull { it.name.equals(f, ignoreCase = true) } ?: fail("--format: m3u8, xspf or rockbox") }
        ?: PlaylistFormat.M3U8
    val mapping = PathMapping(library.root, options.named["path-to"])
    val out = File(options.named["out"] ?: "playlists")
    val filters = filters(db, library, options)
    val start = options.named["start"]?.let { s -> library.pick(s)?.path ?: fail("no track like \"$s\"") }

    val lists: List<Playlist> = when (command) {
        "generate" -> {
            val name = options.named["name"] ?: listOfNotNull(
                filters.moods?.joinToString("-") { it.name.lowercase() },
                filters.families?.joinToString("-") { library.familyName(it) },
                filters.genre, filters.artist,
            ).joinToString("_").ifBlank { "playlist" }
            listOfNotNull(library.filtered(name, filters, spacing, count, start, random))
        }
        "template" -> {
            val pattern = options.positional.getOrNull(1) ?: fail("template: a pattern is needed, e.g. \"{mood}_{family}\"")
            runCatching { library.byTemplate(pattern, filters, spacing, count, random) }.getOrElse { fail(it.message ?: "bad template") }
        }
        "auto" -> library.automatic(filters, spacing, count, options.named["min"]?.toIntOrNull() ?: 8)
        "seed" -> {
            val text = options.positional.drop(1).joinToString(" ").ifBlank { fail("seed: a track is needed (see find)") }
            val seed = library.pick(text) ?: fail("no track like \"$text\"")
            val sameMood = options.flag("same-mood")
            val sameFamily = options.flag("same-family")
            val used = if (options.flag("fit")) {
                val fit = library.generator.fitToSeed(library.indexOf(seed.path)!!, filters, sameMood, sameFamily, count ?: DEFAULT_COUNT)
                val f = fit.filters
                fun pct(v: Float?) = v?.let { "${Math.round(it * 100)}" } ?: "-"
                println(
                    "Fitted: years ${f.yearFrom ?: "-"}-${f.yearTo ?: "-"}, BPM ${f.bpmMin ?: "-"}-${f.bpmMax ?: "-"}, " +
                        "energy ${pct(f.energyMin)}-${pct(f.energyMax)}, positivity ${pct(f.positivityMin)}-${pct(f.positivityMax)}, " +
                        "voice ${f.voice.name.lowercase()}: ${fit.available} tracks to choose from",
                )
                f
            } else {
                filters
            }
            listOfNotNull(library.fromSeed(seed, used, sameMood, sameFamily, count ?: DEFAULT_COUNT, spacing))
        }
        "more" -> {
            val kindName = options.positional.getOrNull(1) ?: fail("more: a kind is needed (${Library.MoreKind.entries.joinToString { it.cli }})")
            val kind = Library.MoreKind.entries.firstOrNull { it.cli == kindName.lowercase() }
                ?: fail("more: unknown kind \"$kindName\" (${Library.MoreKind.entries.joinToString { it.cli }})")
            val text = options.positional.drop(2).joinToString(" ")
            val seed = text.takeIf { it.isNotBlank() }?.let { library.pick(it) ?: fail("no track like \"$it\"") }
            if (kind.needsTrack && seed == null) fail("more ${kind.cli}: a track is needed (see find)")
            val second = options.named["to"]?.let { library.pick(it) ?: fail("no track like \"$it\"") }
            if (kind == Library.MoreKind.BRIDGE && second == null) fail("more bridge: --to TRACK is needed")
            val list = options.named["list"]?.let(::File)?.let { f -> if (f.isFile) Inclusions.readPlaylist(f).map { it.first } else fail("no playlist ${f.path}") }
            if (kind == Library.MoreKind.LIKE && list == null) fail("more like: --list PLAYLIST is needed")
            val minutes = options.named["total"]?.toIntOrNull() ?: 60
            listOfNotNull(library.more(kind, seed, second, minutes, list, filters, count ?: DEFAULT_COUNT, spacing))
        }
        "reorder" -> {
            val file = options.positional.getOrNull(1)?.let(::File)?.takeIf { it.isFile } ?: fail("reorder: a playlist file is needed")
            val result = library.reorder(options.named["name"] ?: (file.nameWithoutExtension + "_chained"), Inclusions.readPlaylist(file), start, spacing)
            println("${result.playlist.paths.size} tracks placed, ${result.unmatched.size} not in the library, ${result.duplicates} repeated.")
            result.unmatched.take(10).forEach { println("  not found: $it") }
            listOf(result.playlist)
        }
        else -> emptyList()
    }
    if (lists.isEmpty() || lists.all { it.paths.isEmpty() }) fail("no tracks pass those filters")
    for (list in lists.filter { it.paths.isNotEmpty() }) {
        val file = PlaylistWriter.write(list, library.byPath, format, mapping, out)
        val links = library.links(list)
        println("%-50s %4d tracks  links %.2f".format(file.name, list.paths.size, links))
    }
    println("Written to ${out.absolutePath}")
}
