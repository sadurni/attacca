package com.sadurni.attaccaplg.generate

import com.sadurni.attacca.chain.Candidate
import com.sadurni.attacca.chain.ChainEngine
import com.sadurni.attacca.chain.ChainFilters
import com.sadurni.attacca.chain.ChainTrack
import com.sadurni.attacca.chain.ClassificationStore
import com.sadurni.attacca.chain.Moods
import com.sadurni.attacca.chain.ListGenerator
import com.sadurni.attacca.chain.Trait
import com.sadurni.attacca.library.PathMatcher
import com.sadurni.attacca.chain.TrackEdges
import com.sadurni.attaccaplg.library.LibraryDb
import com.sadurni.attaccaplg.library.StoredTrack
import java.io.File
import kotlin.random.Random

/** A named list of tracks (library paths), ready to be written. */
class Playlist(val name: String, val paths: List<String>)

/**
 * The measured library, classified (moods and sound families, as in Attacca) and ready to make
 * lists from: Attacca's own list generator for automatic and seeded lists, plus lists by filters,
 * by template and reordered playlists.
 */
class Library(db: LibraryDb) {

    /** The music folder; null when imported without the music (lists then use paths within it). */
    val root: File? = db.musicRoot
    val all: List<StoredTrack> = db.all()
    val byPath: Map<String, StoredTrack> = all.associateBy { it.path }

    /** Tracks with features: the ones lists are made of. */
    private val measured = all.filter { it.features != null }
    private val index = measured.withIndex().associate { (i, t) -> t.path to i }

    val generator: ListGenerator = run {
        val candidates = measured.map { t ->
            Candidate(
                id = t.path,
                artist = t.tags.artist,
                genre = t.tags.genre,
                year = t.tags.year ?: 0,
                features = t.features!!,
                edges = TrackEdges(t.head, t.tail),
                durationMs = t.durationMs,
                sound = t.sound,
            )
        }
        // The classification kept beside the library serves while its tracks and measures are the same.
        val ids = candidates.map { it.id }
        val store = File(db.file.path + ".classes")
        val key = ClassificationStore.key(ids, candidates.map { it.features }, candidates.map { ClassificationStore.soundHash(it.sound?.toBytes()) })
        val kept = ClassificationStore.read(store, key)?.takeIf { it.first == ids }?.second
        val classification = kept ?: Moods.classify(candidates.map { it.features }, sounds = candidates.map { it.sound }).also {
            runCatching { ClassificationStore.write(store, key, ids, it) }
        }
        ListGenerator(candidates, classification)
    }

    val matcher = PathMatcher(all.map { it.path })

    /** Tracks whose path, artist, title or album contain every word of [text]. */
    fun find(text: String): List<StoredTrack> {
        val words = text.lowercase().split(' ').filter { it.isNotBlank() }
        return all.filter { t ->
            val hay = listOf(t.path, t.tags.artist, t.tags.title, t.tags.album).joinToString(" ").lowercase()
            words.all { it in hay }
        }
    }

    /** The one track [text] means (a library path, or the best of [find]), or null. */
    fun pick(text: String): StoredTrack? =
        byPath[text] ?: matcher.match(text)?.let(byPath::get) ?: find(text).firstOrNull { it.features != null }

    fun indexOf(path: String): Int? = index[path]

    fun moodOf(path: String) = index[path]?.let { generator.moodOf(it) }

    fun familyOf(path: String) = index[path]?.let { generator.familyOf(it) }

    /** A sound family's name in words, e.g. "fast-bright-vocal". */
    fun familyName(family: Int): String =
        com.sadurni.attacca.library.PlayerDbFormat.familyName(generator.classification.familyNames, family)

    // ---------- Lists ----------

    /** One chained list of the tracks passing [filters], from [start] (else a random one). */
    fun filtered(name: String, filters: ChainFilters, spacing: Int, count: Int?, start: String?, random: Random): Playlist? {
        val members = measured.indices.filter { generator.passes(it, filters) }
        if (members.isEmpty()) return null
        val first = start?.let(::indexOf)?.takeIf { it in members } ?: members[random.nextInt(members.size)]
        return Playlist(name, chain(members, first, spacing).let { if (count != null) it.take(count) else it })
    }

    /**
     * One list per value of [template] (`{mood}_{family}`, `{genre}`, `{decade}`…) among the tracks
     * passing [filters]: groups of at least two, at most [MAX_TEMPLATE_LISTS] lists, each chained
     * from a random track.
     */
    fun byTemplate(template: String, filters: ChainFilters, spacing: Int, count: Int?, random: Random): List<Playlist> {
        val fields = Regex("\\{([^}]+)\\}").findAll(template).map { it.groupValues[1].trim().lowercase() }.toList()
        val unknown = fields.filter { it !in TEMPLATE_FIELDS }
        require(unknown.isEmpty()) { "unknown template fields: $unknown (known: ${TEMPLATE_FIELDS.joinToString()})" }
        val groups = measured.indices.filter { generator.passes(it, filters) }.groupBy { i ->
            var name = template
            for (f in fields) name = name.replace(Regex("\\{\\s*$f\\s*\\}", RegexOption.IGNORE_CASE), field(measured[i], i, f).ifBlank { "NA" })
            name
        }.filterValues { it.size >= 2 }
        return groups.entries.sortedBy { it.key.lowercase() }.take(MAX_TEMPLATE_LISTS).map { (name, members) ->
            val first = members[random.nextInt(members.size)]
            Playlist(name, chain(members, first, spacing).let { if (count != null) it.take(count) else it })
        }
    }

    /** One list per mood and sound family, as Attacca's generated lists. */
    fun automatic(filters: ChainFilters, spacing: Int, count: Int?, minTracks: Int): List<Playlist> =
        generator.general(filters, spacing, count, minTracks).orEmpty().map {
            Playlist("${it.mood.name.lowercase()}_${familyName(it.family)}", it.ids)
        }

    /** The [count] tracks closest to [seed], chained from it. */
    fun fromSeed(seed: StoredTrack, filters: ChainFilters, sameMood: Boolean, sameFamily: Boolean, count: Int, spacing: Int): Playlist? {
        val i = indexOf(seed.path) ?: return null
        val ids = generator.fromSeed(i, filters, sameMood, sameFamily, count, spacing) ?: return null
        return Playlist("seed_${seed.tags.title.ifBlank { File(seed.path).nameWithoutExtension }}", ids)
    }

    /** The other kinds of list, as Attacca's "Other lists" (and a list like a whole playlist). */
    enum class MoreKind(val cli: String, val en: String, val es: String) {
        RISE_AND_FALL("rise-fall", "Energy up and down", "Energía que sube y baja"),
        WARM_UP("warm-up", "Warming up", "Para ir subiendo"),
        WIND_DOWN("wind-down", "Winding down", "Para ir bajando"),
        SAMPLER("sampler", "Sampler of sound families", "Muestrario de familias de sonido"),
        OPPOSITE("opposite", "The opposite of a track", "Lo contrario de una pista"),
        TIME("time", "For a set time", "Para un tiempo"),
        ALBUMS("albums", "Albums like a track's", "Álbumes como el de una pista"),
        BRIDGE("bridge", "Bridge from one track to another", "Puente de una pista a otra"),
        LIKE("like", "Like a playlist (without its tracks)", "Como una lista (sin sus pistas)"),
        ;

        /** Whether it starts from a track. */
        val needsTrack: Boolean get() = this in setOf(OPPOSITE, TIME, ALBUMS, BRIDGE)
    }

    /**
     * A list of [kind]: from [seed] (energy curves may start there), to [second] (bridge), lasting
     * [minutes] (for a set time), like the tracks of [playlist] (like). Null when what it needs is
     * missing or nothing passes.
     */
    fun more(
        kind: MoreKind,
        seed: StoredTrack?,
        second: StoredTrack?,
        minutes: Int,
        playlist: List<String>?,
        filters: ChainFilters,
        count: Int,
        spacing: Int,
    ): Playlist? {
        val g = generator
        val s = seed?.let { indexOf(it.path) }
        fun title(t: StoredTrack?) = t?.let { it.tags.title.ifBlank { File(it.path).nameWithoutExtension } }.orEmpty()
        val ids: List<String>? = when (kind) {
            MoreKind.RISE_AND_FALL -> g.energyCurve(ListGenerator.EnergyShape.RISE_AND_FALL, s, filters, count, spacing)
            MoreKind.WARM_UP -> g.energyCurve(ListGenerator.EnergyShape.WARM_UP, s, filters, count, spacing)
            MoreKind.WIND_DOWN -> g.energyCurve(ListGenerator.EnergyShape.WIND_DOWN, s, filters, count, spacing)
            MoreKind.SAMPLER -> g.sampler(filters, count, spacing)
            MoreKind.OPPOSITE -> s?.let { g.contrast(it, filters, count, spacing) }
            MoreKind.TIME -> s?.let { g.forTime(it, filters, minutes.coerceAtLeast(1), spacing) }
            MoreKind.ALBUMS -> s?.let { i ->
                val folder = seed.path.substringBeforeLast('/', "")
                val album = measured.indices.filter { measured[it].path.substringBeforeLast('/', "") == folder }.ifEmpty { listOf(i) }
                g.albumsLike(
                    album, filters, count, spacing,
                    trackOrder = compareBy({ measured[it].tags.disc ?: 0 }, { measured[it].tags.track ?: 0 }, { measured[it].path }),
                    albumArtist = { measured[it].tags.albumArtist.ifBlank { measured[it].tags.artist } },
                )
            }
            MoreKind.BRIDGE -> {
                val t = second?.let { indexOf(it.path) }
                if (s == null || t == null) null else g.bridge(s, t, filters, count, spacing)
            }
            MoreKind.LIKE -> playlist?.mapNotNull { matcher.match(it) ?: it.takeIf(byPath::containsKey) }
                ?.let { paths -> g.fromSeeds(g.indicesOf(paths), filters, false, false, count, spacing) }
        }
        if (ids.isNullOrEmpty()) return null
        val name = when (kind) {
            MoreKind.OPPOSITE -> "unlike_${title(seed)}"
            MoreKind.TIME -> "${minutes}min_like_${title(seed)}"
            MoreKind.ALBUMS -> "albums_like_${seed?.tags?.album?.ifBlank { null } ?: title(seed)}"
            MoreKind.BRIDGE -> "from_${title(seed)}_to_${title(second)}"
            MoreKind.LIKE -> "like_list"
            else -> kind.cli
        }
        return Playlist(name, ids)
    }

    /** What [reorder] made of a playlist: the new order and the entries it could not place. */
    class Reordered(val playlist: Playlist, val unmatched: List<String>, val duplicates: Int)

    /**
     * The tracks of a playlist in chained order, from [start] (else its first track). Entries are
     * matched to the library by path, else by "Artist - Title".
     */
    fun reorder(name: String, entries: List<Pair<String, String?>>, start: String?, spacing: Int): Reordered {
        val matched = LinkedHashSet<String>()
        val unmatched = ArrayList<String>()
        var duplicates = 0
        for ((location, title) in entries) {
            val path = matcher.match(location) ?: title?.let(::byArtistTitle)
            when {
                path == null -> unmatched += location
                !matched.add(path) -> duplicates++
            }
        }
        val members = matched.toList()
        if (members.isEmpty()) return Reordered(Playlist(name, emptyList()), unmatched, duplicates)
        val first = start?.let { s -> pick(s)?.path }?.takeIf { it in matched } ?: members.first()
        val tracks = members.map { p -> byPath.getValue(p).let { ChainTrack(it.head, it.tail, it.tags.artist) } }
        val order = ChainEngine.order(tracks, members.indexOf(first), spacing).orEmpty()
        return Reordered(Playlist(name, order.map { members[it] }), unmatched, duplicates)
    }

    /** The mean score of the list's links (how alike each end is to the next start), 0 … 1. */
    fun links(list: Playlist): Double {
        val tracks = list.paths.mapNotNull(byPath::get)
        val scores = tracks.zipWithNext().mapNotNull { (a, b) ->
            val end = a.tail ?: return@mapNotNull null
            val start = b.head ?: return@mapNotNull null
            com.sadurni.attacca.chain.EdgeFeatures.similarity(end, start).toDouble()
        }
        return if (scores.isEmpty()) 0.0 else scores.average()
    }

    private fun byArtistTitle(text: String): String? {
        val key = text.trim().lowercase()
        return all.firstOrNull { "${it.tags.artist} - ${it.tags.title}".lowercase() == key }?.path
    }

    private fun chain(members: List<Int>, first: Int, spacing: Int): List<String> {
        val tracks = members.map { i -> measured[i].let { ChainTrack(it.head, it.tail, it.tags.artist) } }
        val order = ChainEngine.order(tracks, members.indexOf(first), spacing).orEmpty()
        return order.map { measured[members[it]].path }
    }

    private fun field(t: StoredTrack, i: Int, name: String): String = when (name) {
        "mood" -> generator.moodOf(i).name.lowercase()
        "family" -> familyName(generator.familyOf(i))
        "genre" -> t.tags.genre
        "artist" -> t.tags.artist
        "albumartist" -> t.tags.albumArtist.ifBlank { t.tags.artist }
        "album" -> t.tags.album
        "year" -> t.tags.year?.toString().orEmpty()
        "decade" -> t.tags.year?.let { "${it / 10 * 10}s" }.orEmpty()
        "folder" -> File(t.path).parentFile?.name.orEmpty()
        else -> ""
    }

    companion object {
        const val MAX_TEMPLATE_LISTS = 200
        val TEMPLATE_FIELDS = listOf("mood", "family", "genre", "artist", "albumartist", "album", "year", "decade", "folder")

        fun traitWord(t: Trait, high: Boolean): String = com.sadurni.attacca.library.PlayerDbFormat.traitWord(t, high)
    }
}
