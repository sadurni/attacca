package com.sadurni.attaccaplg.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sadurni.attacca.chain.ChainFilters
import com.sadurni.attacca.chain.Mood
import com.sadurni.attacca.chain.VoiceFilter
import com.sadurni.attacca.library.InclusionMode
import com.sadurni.attaccaplg.DEFAULT_COUNT
import com.sadurni.attaccaplg.DEFAULT_MINUTES_MAX
import com.sadurni.attaccaplg.DEFAULT_MINUTES_MIN
import com.sadurni.attaccaplg.export.PathMapping
import com.sadurni.attaccaplg.export.PlaylistFormat
import com.sadurni.attaccaplg.export.PlaylistWriter
import com.sadurni.attaccaplg.generate.Inclusions
import com.sadurni.attaccaplg.generate.Library
import com.sadurni.attaccaplg.generate.Playlist
import java.io.File
import kotlin.random.Random

enum class ListKind { FILTERS, TEMPLATE, AUTO, SEED, MORE, REORDER }

/** A list made, with what the window shows of it. */
class MadeList(val playlist: Playlist, val links: Double, val durationMs: Long)

/** The Lists page's choices and results (kept while moving between pages). */
class ListsForm(private val prefs: Prefs) {
    var kind by mutableStateOf(ListKind.FILTERS)

    // Filters.
    var moods by mutableStateOf(emptySet<Mood>())
    var families by mutableStateOf(emptySet<Int>())
    var artist by mutableStateOf("")
    var genre by mutableStateOf("")
    var yearFrom by mutableStateOf("")
    var yearTo by mutableStateOf("")
    var bpmMin by mutableStateOf("")
    var bpmMax by mutableStateOf("")
    var minutesMin by mutableStateOf(DEFAULT_MINUTES_MIN.toString())
    var minutesMax by mutableStateOf(DEFAULT_MINUTES_MAX.toString())
    var energy by mutableStateOf(0f..100f)
    var positivity by mutableStateOf(0f..100f)
    var voice by mutableStateOf(VoiceFilter.ANY)
    var onlyIncluded by mutableStateOf(false)

    // Per kind.
    var name by mutableStateOf("")
    var count by mutableStateOf(DEFAULT_COUNT.toString())
    var start by mutableStateOf<String?>(null)
    var template by mutableStateOf("{mood}_{family}")
    var minTracks by mutableStateOf("8")
    var seed by mutableStateOf<String?>(null)
    var seedCount by mutableStateOf(DEFAULT_COUNT.toString())
    var sameMood by mutableStateOf(true)
    var sameFamily by mutableStateOf(false)
    var playlist by mutableStateOf<File?>(null)
    var moreKind by mutableStateOf(Library.MoreKind.RISE_AND_FALL)
    var second by mutableStateOf<String?>(null)
    var minutes by mutableStateOf("60")
    var likeList by mutableStateOf<File?>(null)

    // Output (remembered).
    var format by mutableStateOf(PlaylistFormat.entries.firstOrNull { it.name == prefs["format"] } ?: PlaylistFormat.M3U8)
    var outDir by mutableStateOf(prefs["out_dir"] ?: File(System.getProperty("user.home"), "Music/Playlists").path)
    var pathTo by mutableStateOf(prefs["path_to"].orEmpty())
    var spacing by mutableStateOf(prefs["spacing"]?.toIntOrNull() ?: 4)

    // Results.
    var made by mutableStateOf<List<MadeList>>(emptyList())
    var note by mutableStateOf<String?>(null)
    var saved by mutableStateOf<File?>(null)
    var expanded by mutableStateOf<String?>(null)

    fun remember() {
        prefs["format"] = format.name
        prefs["out_dir"] = outDir
        prefs["path_to"] = pathTo
        prefs["spacing"] = spacing.toString()
    }

    fun libraryChanged(library: Library?) {
        val families = library?.generator?.classification?.familyNames?.size ?: 0
        this.families = this.families.filter { it < families }.toSet()
        if (library == null) made = emptyList()
    }

    fun filtersInUse(): Boolean = moods.isNotEmpty() || families.isNotEmpty() || artist.isNotBlank() || genre.isNotBlank() ||
        yearFrom.isNotBlank() || yearTo.isNotBlank() || bpmMin.isNotBlank() || bpmMax.isNotBlank() ||
        minutesMin != DEFAULT_MINUTES_MIN.toString() || minutesMax != DEFAULT_MINUTES_MAX.toString() ||
        energy != 0f..100f || positivity != 0f..100f || voice != VoiceFilter.ANY || onlyIncluded

    fun clearFilters() {
        moods = emptySet()
        families = emptySet()
        artist = ""
        genre = ""
        yearFrom = ""
        yearTo = ""
        bpmMin = ""
        bpmMax = ""
        minutesMin = DEFAULT_MINUTES_MIN.toString()
        minutesMax = DEFAULT_MINUTES_MAX.toString()
        energy = 0f..100f
        positivity = 0f..100f
        voice = VoiceFilter.ANY
        onlyIncluded = false
    }

    private fun filters(library: Library, inclusionList: String?, inclusionMode: InclusionMode): ChainFilters {
        fun range(r: ClosedFloatingPointRange<Float>, a: Boolean) =
            (if (a) r.start else r.endInclusive).takeIf { r != 0f..100f }?.div(100f)
        return ChainFilters(
            moods = moods.takeIf { it.isNotEmpty() },
            families = families.takeIf { it.isNotEmpty() },
            artist = artist.trim().ifEmpty { null },
            genre = genre.trim().ifEmpty { null },
            yearFrom = yearFrom.trim().toIntOrNull(),
            yearTo = yearTo.trim().toIntOrNull(),
            bpmMin = bpmMin.trim().toIntOrNull(),
            bpmMax = bpmMax.trim().toIntOrNull(),
            durationMinS = minutesMin.trim().toIntOrNull()?.takeIf { it > 0 }?.times(60),
            durationMaxS = minutesMax.trim().toIntOrNull()?.times(60),
            energyMin = range(energy, true),
            energyMax = range(energy, false),
            positivityMin = range(positivity, true),
            positivityMax = range(positivity, false),
            voice = voice,
            inclusion = if (onlyIncluded && inclusionList != null) {
                Inclusions.inclusion(File(inclusionList), inclusionMode, library)
            } else {
                null
            },
        )
    }

    /** How many tracks the fitted filters left to choose from (shown until the filters change). */
    var fitted by mutableStateOf<Int?>(null)

    /**
     * Sets year, BPM, energy, positivity and voice around the seed's own, as tight as they can be
     * while leaving 10 times the tracks asked for (at least twice); the rest stays as chosen.
     */
    fun fitToSeed(library: Library, inclusionList: String?, inclusionMode: InclusionMode) {
        val index = seed?.let(library::indexOf) ?: return
        val count = seedCount.trim().toIntOrNull() ?: DEFAULT_COUNT
        val fit = library.generator.fitToSeed(index, filters(library, inclusionList, inclusionMode), sameMood, sameFamily, count)
        val f = fit.filters
        yearFrom = f.yearFrom?.toString().orEmpty()
        yearTo = f.yearTo?.toString().orEmpty()
        bpmMin = f.bpmMin?.toString().orEmpty()
        bpmMax = f.bpmMax?.toString().orEmpty()
        energy = Math.round((f.energyMin ?: 0f) * 100).toFloat()..Math.round((f.energyMax ?: 1f) * 100).toFloat()
        positivity = Math.round((f.positivityMin ?: 0f) * 100).toFloat()..Math.round((f.positivityMax ?: 1f) * 100).toFloat()
        voice = f.voice
        fitted = fit.available
    }

    /** Makes the lists the form asks for (not written yet). Blocking. */
    fun make(library: Library, inclusionList: String?, inclusionMode: InclusionMode) {
        val filters = filters(library, inclusionList, inclusionMode)
        val count = count.trim().toIntOrNull()?.takeIf { it > 0 }
        var note: String? = null
        val lists: List<Playlist> = when (kind) {
            ListKind.FILTERS -> {
                val title = name.trim().ifEmpty {
                    listOfNotNull(
                        filters.moods?.joinToString("-") { it.name.lowercase() },
                        filters.families?.joinToString("-") { library.familyName(it) },
                        filters.genre, filters.artist,
                    ).joinToString("_").ifBlank { "playlist" }
                }
                listOfNotNull(library.filtered(title, filters, spacing, count, start, Random.Default))
            }
            ListKind.TEMPLATE -> library.byTemplate(template, filters, spacing, count, Random.Default)
            ListKind.AUTO -> library.automatic(filters, spacing, count, minTracks.trim().toIntOrNull() ?: 8)
            ListKind.SEED -> {
                val track = seed?.let(library.byPath::get) ?: error(tr("choose a track", "elige una pista"))
                listOfNotNull(library.fromSeed(track, filters, sameMood, sameFamily, seedCount.trim().toIntOrNull() ?: DEFAULT_COUNT, spacing))
            }
            ListKind.MORE -> {
                val track = seed?.let(library.byPath::get)
                if (moreKind.needsTrack && track == null) error(tr("choose a track", "elige una pista"))
                val other = second?.let(library.byPath::get)
                if (moreKind == Library.MoreKind.BRIDGE && other == null) error(tr("choose where the bridge goes", "elige adónde va el puente"))
                val list = likeList?.let { f -> Inclusions.readPlaylist(f).map { it.first } }
                if (moreKind == Library.MoreKind.LIKE && list == null) error(tr("choose a playlist", "elige una lista"))
                listOfNotNull(
                    library.more(moreKind, track, other, minutes.trim().toIntOrNull() ?: 60, list, filters, count ?: DEFAULT_COUNT, spacing),
                )
            }
            ListKind.REORDER -> {
                val file = playlist ?: error(tr("choose a playlist", "elige una lista"))
                val result = library.reorder(name.trim().ifEmpty { file.nameWithoutExtension + "_chained" }, Inclusions.readPlaylist(file), start, spacing)
                note = tr(
                    "${result.playlist.paths.size} tracks placed, ${result.unmatched.size} not in the library, ${result.duplicates} repeated.",
                    "${result.playlist.paths.size} pistas colocadas, ${result.unmatched.size} que no están en la biblioteca, ${result.duplicates} repetidas.",
                ) + result.unmatched.take(5).joinToString("") { "\n  " + it }
                listOf(result.playlist)
            }
        }.filter { it.paths.isNotEmpty() }
        if (lists.isEmpty() && note == null) note = tr("No tracks pass those filters.", "Ninguna pista pasa esos filtros.")
        made = lists.map { list ->
            MadeList(list, library.links(list), list.paths.sumOf { p -> library.byPath[p]?.let { it.audioEndMs - it.audioStartMs }?.takeIf { it > 0 } ?: library.byPath[p]?.durationMs ?: 0 })
        }
        this.note = note
        saved = null
        expanded = made.singleOrNull()?.playlist?.name
    }

    /** Writes the lists made to the output folder. Returns how many. Blocking. */
    fun save(library: Library): Int {
        remember()
        val folder = File(outDir)
        val mapping = PathMapping(library.root, pathTo.trim().ifEmpty { null })
        for (m in made) PlaylistWriter.write(m.playlist, library.byPath, format, mapping, folder)
        saved = folder
        return made.size
    }
}
