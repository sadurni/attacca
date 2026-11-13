package com.sadurni.attacca.chain

import com.sadurni.attacca.AttaccaApp
import com.sadurni.attacca.Background
import com.sadurni.attacca.library.AudioFiles
import com.sadurni.attacca.library.M3uParser
import com.sadurni.attacca.library.PlaylistEntry
import com.sadurni.attacca.library.TrackSort
import com.sadurni.attacca.library.UserLists
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Lists generated from the analysed music folder: one per mood and sound family (saved in
 * [generatedDirectory]) or the tracks closest to a seed track. Work runs in the background.
 */
class ChainLists(private val app: AttaccaApp, private val scope: CoroutineScope) {

    /** The analysed part of the music folder, classified. */
    class Collection(val generator: ListGenerator, val analysed: Int, val total: Int)

    private val prefs = app.getSharedPreferences("chain_lists", android.content.Context.MODE_PRIVATE)

    /** Tracks per list as last asked in the dialogs (the quick list from a track takes it too). */
    var listCount: Int
        get() = prefs.getInt("count", DEFAULT_COUNT)
        set(value) {
            if (value > 0) prefs.edit().putInt("count", value).apply()
        }

    private val _working = MutableStateFlow(false)

    /** True while lists are being built. */
    val working: StateFlow<Boolean> = _working.asStateFlow()

    /** What is being made right now (shown while it takes): whether it takes only the included tracks. */
    class Making(val onlyIncluded: Boolean)

    private val _making = MutableStateFlow<Making?>(null)
    val making: StateFlow<Making?> = _making.asStateFlow()

    private fun started(onlyIncluded: Boolean) {
        _making.value = Making(onlyIncluded)
        _working.value = true
    }

    private fun finished() {
        _working.value = false
        _making.value = null
    }

    /** Whether lists take only the included tracks unless chosen otherwise (Settings → Inclusion list). */
    fun onlyIncludedByDefault(): Boolean = app.settings.chain.value.let { it.listsOnlyIncluded && it.inclusion() != null }

    private fun inclusionIf(onlyIncluded: Boolean) = if (onlyIncluded) app.settings.chain.value.inclusion() else null

    /** The inclusion a list made now with [onlyIncluded] is made with (the set in use, or every track). */
    private fun inclusionNow(onlyIncluded: Boolean): com.sadurni.attacca.settings.ListInclusion {
        val chain = app.settings.chain.value
        return if (onlyIncluded && chain.inclusion() != null) {
            com.sadurni.attacca.settings.ListInclusion(false, chain.activeSet, chain.inclusionList, chain.inclusionMode)
        } else {
            com.sadurni.attacca.settings.ListInclusion(true)
        }
    }

    /**
     * [id] as the music folder's collection holds it: the same file reached by another path (a
     * file opened from another app, as /sdcard/…) is the one already there, so a list from it
     * never has it twice. Blocking (may make the collection).
     */
    suspend fun libraryId(id: String): String {
        val aliases = com.sadurni.attacca.library.Inclusion.aliases(id)
        if (aliases.isEmpty()) return id
        val g = runCatching { collection() }.getOrNull()?.generator ?: return id
        if (g.indexOf(id) >= 0) return id
        return aliases.firstOrNull { g.indexOf(it) >= 0 } ?: id
    }

    /** [ids] with each of [asked] back as it was asked (the track playing keeps its id) where the library knows it by another path. */
    private fun asAsked(ids: List<String>?, asked: List<String>, known: List<String>): List<String>? {
        if (ids == null) return null
        val back = HashMap<String, String>()
        for (i in asked.indices) if (known[i] != asked[i]) back[known[i]] = asked[i]
        return if (back.isEmpty()) ids else ids.map { back[it] ?: it }
    }

    /** The last list made (its tracks) and the inclusion it was made with. */
    @Volatile private var lastMade: Pair<List<String>, com.sadurni.attacca.settings.ListInclusion>? = null

    /** The inclusion the list of [ids] was made with, when it is the last one made here; null otherwise. */
    fun inclusionOfMade(ids: List<String>): com.sadurni.attacca.settings.ListInclusion? = lastMade?.takeIf { it.first == ids }?.second

    /**
     * What a list's endless tracks may be: those of the inclusion it was made with ([made]: its
     * set as it is now, or its list if the set is gone), or as for any list (the one in use,
     * when lists take only included tracks) when that is not known or no longer there.
     */
    fun inclusionFor(made: com.sadurni.attacca.settings.ListInclusion?): com.sadurni.attacca.library.Inclusion? {
        val chain = app.settings.chain.value
        val current = chain.takeIf { it.listsOnlyIncluded }?.inclusion()
        if (made == null) return current
        if (made.all) return null
        val set = made.set?.let { id -> chain.inclusionSets.firstOrNull { it.id == id } }
        return (if (set != null) com.sadurni.attacca.library.Inclusion.of(set.list, set.mode) else null)
            ?: com.sadurni.attacca.library.Inclusion.of(made.list, made.mode)
            ?: current
    }

    private var job: Job? = null

    /**
     * Stops the list being made: its result is dropped and its [onDone] never called (the notice
     * goes away). Nothing when no list is being made.
     */
    fun cancel() {
        val running = job ?: return
        running.cancel()
        // Work already under way (decoding a track) only stops at its next check: the notice goes
        // now, and a new list may start meanwhile.
        job = null
        current++
        finished()
    }

    /** Which list job is the current one: a cancelled one finishing late changes nothing. */
    private var current = 0

    /**
     * Runs [work] (given a check for cancellation) on the background dispatcher as the one list
     * job, then [onDone] with its result: null when it failed, never called when cancelled.
     * Nothing while another list is being made.
     */
    private fun <T> making(onlyIncluded: Boolean, onDone: (T?) -> Unit, work: suspend (isCancelled: () -> Boolean) -> T?) {
        if (job?.isActive == true) return
        val mine = ++current
        val madeWith = inclusionNow(onlyIncluded)
        job = scope.launch {
            started(onlyIncluded)
            val result = try {
                withContext(Background.dispatcher) {
                    val context = coroutineContext
                    work { !context.isActive }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                return@launch
            } catch (e: Exception) {
                null
            } finally {
                if (mine == current) finished()
            }
            if (mine == current) {
                @Suppress("UNCHECKED_CAST")
                val ids = when (result) {
                    is List<*> -> result as List<String>
                    is Pair<*, *> -> result.second as? List<String>
                    else -> null
                }
                if (ids != null) lastMade = ids to madeWith
                onDone(result)
            }
        }
    }
    private val listStore by lazy { com.sadurni.attacca.settings.ListSettingsStore(app) }

    @Volatile private var cached: Pair<String, Collection>? = null
    @Volatile private var withSeed: Pair<String, Collection>? = null

    val generatedDirectory: File get() = File(UserLists.directory, "Generated")

    /** The classification of the music folder kept between runs (see [com.sadurni.attacca.chain.ClassificationStore]). */
    private val storeFile: File get() = File(app.filesDir, "classification.bin")

    /**
     * The classification kept from last time, whatever has changed since: to show a track's mood
     * and family at once, while [collection] is still being made. Null when there is none.
     */
    fun keptClassification(): Pair<Map<String, Int>, com.sadurni.attacca.chain.Classification>? {
        kept?.let { return it }
        val read = com.sadurni.attacca.chain.ClassificationStore.read(storeFile, null) ?: return null
        return (read.first.withIndex().associate { (i, id) -> id to i } to read.second).also { kept = it }
    }

    @Volatile private var kept: Pair<Map<String, Int>, com.sadurni.attacca.chain.Classification>? = null

    fun generated(): List<File> =
        generatedDirectory.listFiles { f -> f.isFile && f.extension.equals("m3u8", true) }
            ?.sortedWith(com.sadurni.attacca.library.NaturalOrder)
            .orEmpty()

    /**
     * The analysed tracks of the music folder (plus [extra], or [extras], analysed now if needed),
     * classified. Null without a music folder and no extra. Only tracks already analysed take part.
     */
    suspend fun collection(extra: String? = null, extras: List<String> = listOfNotNull(extra)): Collection? = withContext(Background.dispatcher) {
        val folder = app.settings.musicFolder?.let(::File)
        if (folder == null && extras.isEmpty()) return@withContext null
        if (extras.isNotEmpty()) app.chainAnalyzer.profilesFor(extras)
        val key = "${folder?.absolutePath}|${app.chainAnalyzer.last.value?.finishedAt}|${app.settings.chain.value.edgeSeconds}|${com.sadurni.attacca.library.IgnoredPaths.version}|${com.sadurni.attacca.library.MoodCorrections.version.value}|${if (app.settings.chain.value.skipDamaged) com.sadurni.attacca.library.DamagedTracks.version else -1}"
        // The collection of the music folder serves any seed already in it; only a seed outside it
        // (or analysed just now) needs one of its own.
        cached?.let { (k, c) -> if (k == key && extras.all { c.generator.indexOf(it) >= 0 }) return@withContext c }
        val ownKey = if (extras.isEmpty()) key else "$key|${extras.joinToString("|")}"
        withSeed?.let { (k, c) -> if (k == ownKey) return@withContext c }
        // A collection of a big music folder takes a lot of memory: the old ones go before a new
        // one is made, never two or three at once.
        if (cached?.first != key) cached = null
        withSeed = null
        val ids = (folder?.let { f -> AudioFiles.collectAudio(f, recent = true).map { it.absolutePath } }.orEmpty() + extras).distinct()
        // The sound profiles are read only when the classification kept from last time does not serve.
        val profiles = app.chainAnalyzer.knownProfiles(ids, withSounds = false)
        TrackSort.warm(ids)
        val skipDamaged = app.settings.chain.value.skipDamaged
        val candidates = ids.mapNotNull { id ->
            // Damaged tracks stay out (unless one is the track a list is made from).
            if (skipDamaged && id !in extras && com.sadurni.attacca.library.DamagedTracks.isDamaged(id)) return@mapNotNull null
            val features = profiles.features[id] ?: return@mapNotNull null
            val tags = TrackSort.tags(id)
            Candidate(id, tags.artist, tags.genre, tags.year, features, profiles.edges[id], tags.durationMs)
        }
        // The sound profiles only make the families and likeness vectors: the collection kept
        // afterwards does not hold them (thousands of them are many megabytes).
        // The moods the user gave: a track's own spreads to the tracks that sound like it.
        val corrections = HashMap<Int, com.sadurni.attacca.chain.Mood>()
        val own = HashSet<Int>()
        for ((i, c) in candidates.withIndex()) {
            val tags = TrackSort.tags(c.id)
            val (mood, scope) = com.sadurni.attacca.library.MoodCorrections.moodFor(c.id, tags.artist, tags.album) ?: continue
            corrections[i] = mood
            if (scope == com.sadurni.attacca.library.MoodCorrections.Scope.TRACK) own.add(i)
        }
        val candidateIds = candidates.map { it.id }
        val stamps = app.chainAnalyzer.soundStamps(candidateIds)
        val storeKey = com.sadurni.attacca.chain.ClassificationStore.key(
            candidateIds, candidates.map { it.features }, candidateIds.map { stamps[it] ?: 0L }, corrections, own,
        )
        val fromStore = if (extras.isEmpty()) {
            com.sadurni.attacca.chain.ClassificationStore.read(storeFile, storeKey)?.takeIf { it.first == candidateIds }?.second
        } else null
        val classification = fromStore ?: run {
            val sounds = app.chainAnalyzer.knownSounds(candidateIds)
            Moods.classify(candidates.map { it.features }, sounds = candidates.map { sounds[it.id] }, corrections = corrections, spreadFrom = own).also {
                if (extras.isEmpty()) {
                    runCatching { com.sadurni.attacca.chain.ClassificationStore.write(storeFile, storeKey, candidateIds, it) }
                    kept = null
                }
            }
        }
        Collection(ListGenerator(candidates, classification), candidates.size, ids.size).also {
            if (extras.isEmpty()) cached = key to it else withSeed = ownKey to it
        }
    }

    /**
     * Makes the collection ready ahead (after a scan or an analysis pass), so the first list from
     * a track does not wait for it. Quietly does nothing without analysed tracks.
     */
    fun warm() {
        scope.launch { runCatching { collection() } }
    }

    /**
     * Replaces the general lists: one chained list per mood and family passing [filters],
     * named by [name]. [onDone] gets how many lists were written (null if it failed).
     */
    fun generateGeneral(
        filters: ChainFilters,
        count: Int?,
        name: (GeneratedList, Collection) -> String,
        onDone: (Int?) -> Unit,
    ) {
        val madeWith = inclusionNow(filters.inclusion != null)
        making(filters.inclusion != null, onDone) { isCancelled ->
            val collection = collection() ?: return@making null
            val lists = collection.generator.general(filters, app.settings.chain.value.artistSpacing, count, isCancelled = isCancelled)
                ?: return@making null
            val dir = generatedDirectory
            dir.mkdirs()
            generated().forEach { it.delete() }
            val used = HashSet<String>()
            for (list in lists) {
                var base = sanitize(name(list, collection))
                var n = 2
                while (!used.add(base.lowercase())) base = "${sanitize(name(list, collection))} ($n)".also { n++ }
                val file = File(dir, "$base.m3u8")
                M3uParser.write(file, list.ids.map { PlaylistEntry(it) })
                // Each list keeps the inclusion it was made with (for its endless tracks).
                val key = com.sadurni.attacca.library.ListSource.Playlist(file.absolutePath).key
                listStore.put(key, listStore.get(key).copy(inclusion = madeWith))
            }
            lists.size
        }
    }

    /**
     * The [count] tracks closest to [seed], chained from it (see [ListGenerator.fromSeed]).
     * [onDone] gets their ids, or null when the seed could not be analysed.
     */
    fun fromSeed(
        seed: String,
        filters: ChainFilters,
        sameMood: Boolean,
        sameFamily: Boolean,
        count: Int,
        onDone: (List<String>?) -> Unit,
    ) {
        making(filters.inclusion != null, onDone) { isCancelled ->
            val known = libraryId(seed)
            val collection = collection(extra = known) ?: return@making null
            val index = collection.generator.indexOf(known)
            if (index < 0) return@making null
            asAsked(collection.generator.fromSeed(index, filters, sameMood, sameFamily, count, app.settings.chain.value.artistSpacing, isCancelled), listOf(seed), listOf(known))
        }
    }

    /**
     * A list from [seed] without asking: same mood and sound family, the filters fitted around it
     * (see [ListGenerator.fitToSeed]), widened (any mood, then any family) while that leaves fewer
     * than half the tracks asked for. [onDone] gets the ids, or null when the seed could not be analysed.
     */
    fun quickFromSeed(seed: String, count: Int, onlyIncluded: Boolean = onlyIncludedByDefault(), onDone: (List<String>?) -> Unit) {
        making(onlyIncluded, onDone) { isCancelled ->
            val known = libraryId(seed)
            val collection = collection(extra = known) ?: return@making null
            val index = collection.generator.indexOf(known)
            if (index < 0) return@making null
            asAsked(quickIds(collection.generator, index, count, inclusion = inclusionIf(onlyIncluded), isCancelled = isCancelled), listOf(seed), listOf(known))
        }
    }

    /** Tracks played lately, which quick and random lists leave out when set to (Settings → Chaining); null: none. */
    private fun playedLately(): Set<String>? {
        val days = app.settings.chain.value.quickNotPlayedDays.takeIf { it > 0 } ?: return null
        return runCatching { app.listening.playedSince(System.currentTimeMillis() - days * 86_400_000L) }.getOrNull()
    }

    /** The quick list from track [index] of [g] (see [quickFromSeed]). */
    private fun quickIds(
        g: ListGenerator,
        index: Int,
        count: Int,
        played: Set<String>? = playedLately(),
        inclusion: com.sadurni.attacca.library.Inclusion? = null,
        isCancelled: () -> Boolean = { false },
    ): List<String> {
        val base = ChainFilters(durationMinS = QUICK_MIN_SECONDS, durationMaxS = QUICK_MAX_SECONDS, excluded = played, inclusion = inclusion)
        val spacing = app.settings.chain.value.artistSpacing
        // Wider until at least half the tracks asked for; else the widest that gave most.
        val enough = maxOf(QUICK_MIN_TRACKS, count / 2)
        var best = emptyList<String>()
        for ((mood, family) in listOf(true to true, false to true, false to false)) {
            val fit = g.fitToSeed(index, base, mood, family, count)
            if (isCancelled()) return emptyList()
            val ids = g.fromSeed(index, fit.filters, mood, family, count, spacing, isCancelled).orEmpty()
            if (ids.size >= enough) return ids
            if (ids.size > best.size) best = ids
        }
        return best
    }

    /**
     * A quick list (see [quickFromSeed]) from a track taken at random among the analysed ones of
     * [mood] (any mood when null, or when none has it), never [avoid] (the playing track).
     * [onDone] gets the seed and the ids, or null when nothing is analysed.
     */
    fun quickRandom(
        mood: Mood?,
        count: Int,
        avoid: String?,
        onlyIncluded: Boolean = onlyIncludedByDefault(),
        onDone: (Pair<String, List<String>>?) -> Unit,
    ) {
        making(onlyIncluded, onDone) { isCancelled ->
            val g = collection()?.generator ?: return@making null
            val played = playedLately()
            val inclusion = inclusionIf(onlyIncluded)
            // Not a track played lately either, when set to (unless all of them were).
            val avoided = avoid?.let { libraryId(it) }
            val all = g.candidates.indices.filter { g.candidates[it].id != avoided && (inclusion == null || inclusion.includes(g.candidates[it].id)) }
                .let { all -> if (played == null) all else all.filter { g.candidates[it].id !in played }.ifEmpty { all } }
            val pool = all.filter { mood == null || g.classification.hasMood(it, setOf(mood)) }.ifEmpty { all }
            // A file still there (only those tried are looked at).
            val index = pool.shuffled().firstOrNull { File(g.candidates[it].id).isFile } ?: return@making null
            g.candidates[index].id to quickIds(g, index, count, played, inclusion, isCancelled)
        }
    }

    /**
     * The [count] tracks closest to the group [seeds] (an album, an artist), chained (see
     * [ListGenerator.fromSeeds]). [onDone] gets their ids, or null when none of the seeds is analysed.
     */
    fun fromSeeds(
        seeds: List<String>,
        filters: ChainFilters,
        sameMood: Boolean,
        sameFamily: Boolean,
        count: Int,
        onDone: (List<String>?) -> Unit,
    ) {
        making(filters.inclusion != null, onDone) { isCancelled ->
            val collection = collection() ?: return@making null
            val known = seeds.map { libraryId(it) }
            val indices = collection.generator.indicesOf(known)
            if (indices.isEmpty()) return@making null
            asAsked(collection.generator.fromSeeds(indices, filters, sameMood, sameFamily, count, app.settings.chain.value.artistSpacing, isCancelled), seeds, known)
        }
    }

    /** The other kinds of list (see [ListGenerator]'s "more kinds of list"). */
    enum class Kind { RISE_AND_FALL, WARM_UP, WIND_DOWN, REDISCOVER, SAMPLER, CONTRAST, FOR_TIME, ALBUMS_LIKE, BRIDGE }

    /**
     * A list of [kind]: [seeds] are the track it starts from (contrast, for a time, energy curves
     * when given), the album it is like (albums like) or the two ends (bridge); [value] is the
     * minutes (for a time) or the days not played (rediscover, 0: never played). Tracks played
     * lately are left out as for quick lists. [onDone] gets the ids, or null when it failed.
     */
    fun make(kind: Kind, seeds: List<String>, value: Int, count: Int, onlyIncluded: Boolean = onlyIncludedByDefault(), onDone: (List<String>?) -> Unit) {
        making(onlyIncluded, onDone) { isCancelled ->
            val known = seeds.map { libraryId(it) }
            val collection = collection(extras = if (kind == Kind.BRIDGE || seeds.size == 1) known else emptyList()) ?: return@making null
            asAsked(build(collection.generator, kind, known, value, count, inclusionIf(onlyIncluded), isCancelled), seeds, known)
        }
    }

    private suspend fun build(
        g: ListGenerator,
        kind: Kind,
        seeds: List<String>,
        value: Int,
        count: Int,
        inclusion: com.sadurni.attacca.library.Inclusion?,
        isCancelled: () -> Boolean,
    ): List<String>? {
        val spacing = app.settings.chain.value.artistSpacing
        val base = ChainFilters(excluded = playedLately(), inclusion = inclusion)
        val first = seeds.firstOrNull()?.let(g::indexOf)?.takeIf { it >= 0 }
        return when (kind) {
            Kind.RISE_AND_FALL -> g.energyCurve(ListGenerator.EnergyShape.RISE_AND_FALL, first, base, count, spacing, isCancelled = isCancelled)
            Kind.WARM_UP -> g.energyCurve(ListGenerator.EnergyShape.WARM_UP, first, base, count, spacing, isCancelled = isCancelled)
            Kind.WIND_DOWN -> g.energyCurve(ListGenerator.EnergyShape.WIND_DOWN, first, base, count, spacing, isCancelled = isCancelled)
            Kind.SAMPLER -> g.sampler(base, count, spacing, isCancelled)
            Kind.CONTRAST -> first?.let { g.contrast(it, base, count, spacing, isCancelled) }
            Kind.FOR_TIME -> first?.let { g.forTime(it, base, value.coerceAtLeast(1), spacing, isCancelled) }
            Kind.BRIDGE -> {
                val to = seeds.getOrNull(1)?.let(g::indexOf)?.takeIf { it >= 0 }
                if (first == null || to == null) null else g.bridge(first, to, ChainFilters(inclusion = inclusion), count, spacing, isCancelled)
            }
            Kind.REDISCOVER -> {
                val favorites = com.sadurni.attacca.library.ListLoader.load(
                    com.sadurni.attacca.library.ListSource.Playlist(UserLists.favorites.absolutePath),
                ).map { it.mediaId }
                val heard = if (value > 0) app.listening.playedSince(System.currentTimeMillis() - value * 86_400_000L) else app.listening.everPlayed()
                g.fromSeeds(g.indicesOf(favorites), ChainFilters(excluded = heard + favorites, inclusion = inclusion), false, false, count, spacing, isCancelled)
            }
            Kind.ALBUMS_LIKE -> albumsLike(g, seeds, base, count, isCancelled)
        }
    }

    /** Whole albums (folders) most like the album [seedAlbum], at least [count] tracks, chained album by album. */
    private fun albumsLike(g: ListGenerator, seedAlbum: List<String>, base: ChainFilters, count: Int, isCancelled: () -> Boolean): List<String>? {
        val seeds = g.indicesOf(seedAlbum)
        if (seeds.isEmpty()) return null
        fun tags(i: Int) = TrackSort.tags(g.candidates[i].id)
        return g.albumsLike(
            seeds, base, count, app.settings.chain.value.artistSpacing,
            trackOrder = compareBy({ tags(it).disc }, { tags(it).track }, { g.candidates[it].id }),
            albumArtist = { i -> tags(i).let { t -> t.albumArtist.ifEmpty { t.artist } } },
            isCancelled = isCancelled,
        )
    }

    /**
     * Albums ([albums], each its tracks in order) chained as wholes: the end of each album's last
     * track leads into the start of the next album's first one, starting with album [first] and
     * keeping albums of the same artist ([artists]) apart. Only the first and last tracks are
     * analysed (when not yet). Null when cancelled.
     */
    suspend fun chainAlbums(albums: List<List<String>>, artists: List<String>, first: Int): List<Int>? = withContext(Background.dispatcher) {
        val ends = albums.flatMap { listOfNotNull(it.firstOrNull(), it.lastOrNull()) }.distinct()
        val edges = app.chainAnalyzer.edgesFor(ends)
        val tracks = albums.mapIndexed { i, album ->
            ChainTrack(album.firstOrNull()?.let { edges[it]?.head }, album.lastOrNull()?.let { edges[it]?.tail }, artists[i])
        }
        ChainEngine.order(tracks, first, app.settings.chain.value.artistSpacing)
    }

    private companion object {
        const val DEFAULT_COUNT = 150
        const val QUICK_MIN_SECONDS = 60
        const val QUICK_MAX_SECONDS = 20 * 60
        const val QUICK_MIN_TRACKS = 10
    }

    private fun sanitize(name: String): String =
        name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").ifEmpty { "List" }
}
