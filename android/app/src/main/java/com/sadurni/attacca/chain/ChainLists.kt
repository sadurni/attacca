package com.sadurni.attacca.chain

import com.sadurni.attacca.AttaccaApp
import com.sadurni.attacca.Background
import com.sadurni.attacca.library.AudioFiles
import com.sadurni.attacca.library.M3uParser
import com.sadurni.attacca.library.PlaylistEntry
import com.sadurni.attacca.library.TrackSort
import com.sadurni.attacca.library.UserLists
import com.sadurni.attacca.settings.ListChoices
import com.sadurni.attacca.settings.LiveChoice
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlin.math.abs

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

    /** The inclusion a list made now with [onlyIncluded] and [live] is made with (the set in use, or every track). */
    private fun inclusionNow(onlyIncluded: Boolean, choices: ListChoices): com.sadurni.attacca.settings.ListInclusion {
        val chain = app.settings.chain.value
        return if (onlyIncluded && chain.inclusion() != null) {
            com.sadurni.attacca.settings.ListInclusion(false, chain.activeSet, chain.inclusionList, chain.inclusionMode, choices.live, choices.profile)
        } else {
            com.sadurni.attacca.settings.ListInclusion(true, live = choices.live, profile = choices.profile)
        }
    }

    /** What lists are made with unless chosen otherwise when making one: live tracks as set, the listener's own taste. */
    fun choicesByDefault(): ListChoices = ListChoices(liveByDefault())

    /** The chaining weights of a list made with [made] (its profile), for its endless tracks; null: the listener's own taste. */
    fun tasteFor(made: com.sadurni.attacca.settings.ListInclusion?): com.sadurni.attacca.chain.ChainTaste? = app.settings.tasteOf(made?.profile)

    /** Live tracks in the lists made unless chosen otherwise when making one (Settings → Chaining). */
    fun liveByDefault(): LiveChoice = app.settings.chain.value.liveChoice

    /** The live tracks choice of a list made with [made] (for its endless tracks); the default when not known. */
    fun liveFor(made: com.sadurni.attacca.settings.ListInclusion?): LiveChoice = made?.live ?: liveByDefault()

    /**
     * The tracks among [ids] that [live] leaves out: the live ones, or all but those; never [keep]
     * (the track a list starts from). Empty for any.
     */
    fun liveLeftOut(ids: List<String>, live: LiveChoice, keep: kotlin.collections.Collection<String> = emptyList()): Set<String> {
        if (live == LiveChoice.ANY) return emptySet()
        val liveIds = step("live") { com.sadurni.attacca.library.LiveTracks.live(ids) }
        val out = if (live == LiveChoice.NONE) liveIds.toHashSet() else ids.filterTo(HashSet()) { it !in liveIds }
        out.removeAll(keep.toSet())
        return out
    }

    /** [filters] also leaving out the tracks of [g] that [live] leaves out (see [liveLeftOut]). */
    private fun withLive(filters: ChainFilters, g: ListGenerator, live: LiveChoice, keep: kotlin.collections.Collection<String> = emptyList()): ChainFilters {
        val out = liveLeftOut(g.candidates.map { it.id }, live, keep)
        return if (out.isEmpty()) filters else filters.copy(excluded = filters.excluded.orEmpty() + out)
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
        times?.let { t ->
            com.sadurni.attacca.ExitLog.note(app, "List stopped by hand after ${(System.nanoTime() - makingSince) / 1_000_000} ms, during ${inStep ?: "no timed step"}${synchronized(t) { t.toString() }}")
        }
        // Work already under way (decoding a track) only stops at its next check: the notice goes
        // now, and a new list may start meanwhile.
        job = null
        current++
        finished()
    }

    /** Which list job is the current one: a cancelled one finishing late changes nothing. */
    private var current = 0

    /** How long each step of the list being made took (only while logs are kept, for reports). */
    @Volatile private var times: StringBuilder? = null

    /** The step under way (for the log of a list stopped while it is made). */
    @Volatile private var inStep: String? = null
    @Volatile private var makingSince = 0L

    /** [step], its time also noted in [prep] (the making of the collection) when kept. */
    private inline fun <R> stepIn(prep: StringBuilder?, name: String, block: () -> R): R {
        val start = System.nanoTime()
        val result = step(name, block)
        prep?.append(", ")?.append(name)?.append(' ')?.append((System.nanoTime() - start) / 1_000_000)?.append(" ms")
        return result
    }

    /** [block], its time noted as [name] in the list's times when they are kept. */
    private inline fun <R> step(name: String, block: () -> R): R {
        val t = times ?: return block()
        val start = System.nanoTime()
        inStep = name
        val result = block()
        inStep = null
        synchronized(t) { t.append(", ").append(name).append(' ').append((System.nanoTime() - start) / 1_000_000).append(" ms") }
        return result
    }

    /**
     * Runs [work] (given a check for cancellation) on the background dispatcher as the one list
     * job, then [onDone] with its result: null when it failed, never called when cancelled.
     * Nothing while another list is being made.
     */

    private fun <T> making(onlyIncluded: Boolean, choices: ListChoices, onDone: (T?) -> Unit, what: String = "list", work: suspend (isCancelled: () -> Boolean) -> T?) {
        if (job?.isActive == true) {
            com.sadurni.attacca.ExitLog.note(app, "List ($what) not made: another one still under way")
            return
        }
        val mine = ++current
        val took = if (com.sadurni.attacca.Logs.on) StringBuilder() else null
        times = took
        val started = System.nanoTime()
        makingSince = started
        inStep = null
        val madeWith = inclusionNow(onlyIncluded, choices)
        // The list's own chaining profile, while it is made (the listener's taste otherwise).
        val taste = com.sadurni.attacca.chain.EdgeFeatures.listTaste.asContextElement(app.settings.tasteOf(choices.profile))
        job = scope.launch {
            started(onlyIncluded)
            val result = try {
                withContext(Background.dispatcher + taste) {
                    val context = coroutineContext
                    work { !context.isActive }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                return@launch
            } catch (e: Exception) {
                null
            } finally {
                if (mine == current) finished()
                if (took != null) {
                    if (times === took) times = null
                    com.sadurni.attacca.ExitLog.note(app, "List ($what)${if (mine == current) "" else ", stopped"}: ${(System.nanoTime() - started) / 1_000_000} ms$took")
                }
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
        if (extras.isNotEmpty()) step("seed analysed") { app.chainAnalyzer.profilesFor(extras) }
        // The damaged tracks found before, read first: else the collection made at start would be
        // made again as soon as they are read.
        if (app.settings.chain.value.skipDamaged) com.sadurni.attacca.library.DamagedTracks.ensureLoaded()
        val key = "${folder?.absolutePath}|${app.chainAnalyzer.last.value?.finishedAt}|${app.settings.chain.value.edgeSeconds}|${com.sadurni.attacca.library.IgnoredPaths.all.value.hashCode()}|${com.sadurni.attacca.library.MoodCorrections.version.value}|${if (app.settings.chain.value.skipDamaged) com.sadurni.attacca.library.DamagedTracks.all.value.keys.hashCode() else -1}|${com.sadurni.attacca.library.SameSongs.version}"
        // The collection of the music folder serves any seed already in it; only a seed outside it
        // (or analysed just now) needs one of its own.
        cached?.let { (k, c) -> if (k == key && extras.all { c.generator.indexOf(it) >= 0 }) return@withContext c }
        val ownKey = if (extras.isEmpty()) key else "$key|${extras.joinToString("|")}"
        withSeed?.let { (k, c) -> if (k == ownKey) return@withContext c }
        // One made at a time: a list asked for while the collection is being made ahead (or for a
        // list stopped meanwhile) waits for it and takes it, instead of making a second one.
        val askedAt = System.nanoTime()
        building.withLock {
        ((System.nanoTime() - askedAt) / 1_000_000).let { waited -> if (waited >= 100) step("waited for the library $waited ms") {} }
        cached?.let { (k, c) -> if (k == key && extras.all { c.generator.indexOf(it) >= 0 }) return@withContext c }
        withSeed?.let { (k, c) -> if (k == ownKey) return@withContext c }
        // How long making it takes, noted on its own (also when made ahead, for no list), and why.
        val prep = if (com.sadurni.attacca.Logs.on) StringBuilder() else null
        if (prep != null) {
            val names = listOf("folder", "analysis", "edge length", "ignored", "moods given", "damaged", "copies")
            val before = lastKey?.split('|')
            val now = key.split('|')
            prep.append(", because: ").append(
                if (before == null) "first" else names.indices.filter { before.getOrNull(it) != now.getOrNull(it) }.joinToString("+") { names[it] }.ifEmpty { "with a seed" },
            )
        }
        if (extras.isEmpty()) lastKey = key
        val prepStart = System.nanoTime()
        // The files of the last scan of the music folder, however long ago (walking thousands of
        // files again takes long): only tracks a scan found are analysed anyway.
        // A collection of a big music folder takes a lot of memory: the old ones go before a new
        // one is made, never two or three at once.
        if (cached?.first != key) cached = null
        withSeed = null
        val ids = stepIn(prep, "collection: files") { (folder?.let { f -> AudioFiles.collectAudio(f, recent = true, anyAge = true).map { it.absolutePath } }.orEmpty() + extras).distinct() }
        // The sound profiles are read only when the classification kept from last time does not serve.
        val profiles = stepIn(prep, "profiles") { app.chainAnalyzer.knownProfiles(ids, withSounds = false) }
        stepIn(prep, "tags") { TrackSort.warm(ids) }
        val skipDamaged = app.settings.chain.value.skipDamaged
        val analysed = stepIn(prep, "analysed") {
            ids.mapNotNull { id ->
                // Damaged tracks stay out (unless one is the track a list is made from).
                if (skipDamaged && id !in extras && com.sadurni.attacca.library.DamagedTracks.isDamaged(id)) return@mapNotNull null
                val features = profiles.features[id] ?: return@mapNotNull null
                val tags = TrackSort.tags(id)
                Candidate(id, tags.artist, tags.genre, tags.year, features, profiles.edges[id], tags.durationMs)
            }
        }
        // The same recording on several albums (a compilation, a remaster): only its best copy
        // takes part, so no list has it twice (the track a list starts from always stays).
        val copies = stepIn(prep, "copies") { com.sadurni.attacca.library.SameSongs.others(analysed.map { it.id }, keep = extras.toSet()) }
        // Live tracks are left out (or kept alone) by each list, as chosen when it is made.
        val candidates = if (copies.isEmpty()) analysed else analysed.filter { it.id !in copies }
        // The sound profiles only make the families and likeness vectors: the collection kept
        // afterwards does not hold them (thousands of them are many megabytes).
        // The moods the user gave: a track's own spreads to the tracks that sound like it.
        val corrections = HashMap<Int, com.sadurni.attacca.chain.Mood>()
        val own = HashSet<Int>()
        if (prep != null) stepIn(prep, "${candidates.size} kept") {}
        for ((i, c) in candidates.withIndex()) {
            val tags = TrackSort.tags(c.id)
            val (mood, scope) = com.sadurni.attacca.library.MoodCorrections.moodFor(c.id, tags.artist, tags.album) ?: continue
            corrections[i] = mood
            if (scope == com.sadurni.attacca.library.MoodCorrections.Scope.TRACK) own.add(i)
        }
        val candidateIds = candidates.map { it.id }
        val stamps = stepIn(prep, "stamps") { app.chainAnalyzer.soundStamps(candidateIds) }
        val storeKey = com.sadurni.attacca.chain.ClassificationStore.key(
            candidateIds, candidates.map { it.features }, candidateIds.map { stamps[it] ?: 0L }, corrections, own,
        )
        val fromStore = if (extras.isEmpty()) stepIn(prep, "moods given, kept classification read") {
            com.sadurni.attacca.chain.ClassificationStore.read(storeFile, storeKey)?.takeIf { it.first == candidateIds }?.second
        } else null
        val classification = fromStore?.also { stepIn(prep, "classification kept") {} } ?: stepIn(prep, "classified again") {
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
            if (prep != null) com.sadurni.attacca.ExitLog.note(app, "Library prepared: ${(System.nanoTime() - prepStart) / 1_000_000} ms, ${candidates.size} tracks$prep")
        }
        }
    }

    private val building = kotlinx.coroutines.sync.Mutex()

    /** Live tracks being told ahead (see [warm]). */
    private val tellingLive = java.util.concurrent.atomic.AtomicBoolean(false)

    /** The key of the last collection of the music folder made (to tell in the log what changed). */
    @Volatile private var lastKey: String? = null

    /**
     * Makes the collection ready ahead (after a scan or an analysis pass), so the first list from
     * a track does not wait for it. Quietly does nothing without analysed tracks.
     */
    fun warm() {
        scope.launch {
            val made = runCatching { collection() }.getOrNull()
            // Lists that leave out live tracks (or keep only them) find them told already.
            if (made != null && liveByDefault() != LiveChoice.ANY && tellingLive.compareAndSet(false, true)) {
                // One at a time: a second one finds what the first told.
                try { runCatching {
                    withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val start = System.nanoTime()
                        val live = com.sadurni.attacca.library.LiveTracks.live(made.generator.candidates.map { it.id })
                        if (com.sadurni.attacca.Logs.on) com.sadurni.attacca.ExitLog.note(app, "Live tracks told ahead: ${live.size} in ${(System.nanoTime() - start) / 1_000_000} ms")
                    }
                } } finally { tellingLive.set(false) }
            }
            // The copies of one song the tags propose, compared by their sound (slow, each pair
            // once): those found the same leave the lists made from then on.
            val folder = app.settings.musicFolder?.let(::File) ?: return@launch
            val found = runCatching {
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.sadurni.attacca.library.SameSongs.verify(AudioFiles.collectAudio(folder, recent = true).map { it.absolutePath })
                }
            }.getOrDefault(false)
            if (found) runCatching { collection() }
        }
    }

    /**
     * Replaces the general lists: one chained list per mood and family passing [filters],
     * named by [name]. [onDone] gets how many lists were written (null if it failed).
     */
    fun generateGeneral(
        filters: ChainFilters,
        count: Int?,
        name: (GeneratedList, Collection) -> String,
        choices: ListChoices = choicesByDefault(),
        onDone: (Int?) -> Unit,
    ) {
        val madeWith = inclusionNow(filters.inclusion != null, choices)
        making(filters.inclusion != null, choices, onDone) { isCancelled ->
            val collection = collection() ?: return@making null
            val lists = collection.generator.general(withLive(filters, collection.generator, choices.live), app.settings.chain.value.artistSpacing, count, isCancelled = isCancelled)
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
        choices: ListChoices = choicesByDefault(),
        onDone: (List<String>?) -> Unit,
    ) {
        making(filters.inclusion != null, choices, onDone) { isCancelled ->
            val known = libraryId(seed)
            val collection = collection(extra = known) ?: return@making null
            val index = collection.generator.indexOf(known)
            if (index < 0) return@making null
            asAsked(collection.generator.fromSeed(index, withLive(filters, collection.generator, choices.live, listOf(known)), sameMood, sameFamily, count, app.settings.chain.value.artistSpacing, isCancelled), listOf(seed), listOf(known))
        }
    }

    /**
     * A list from [seed] without asking: same mood and sound family, the filters fitted around it
     * (see [ListGenerator.fitToSeed]), widened (any mood, then any family) while that leaves fewer
     * than half the tracks asked for. [onDone] gets the ids, or null when the seed could not be analysed.
     */
    fun quickFromSeed(seed: String, count: Int, onlyIncluded: Boolean = onlyIncludedByDefault(), choices: ListChoices = choicesByDefault(), onDone: (List<String>?) -> Unit) {
        making(onlyIncluded, choices, onDone, "quick") { isCancelled ->
            val known = libraryId(seed)
            val collection = collection(extra = known) ?: return@making null
            val index = collection.generator.indexOf(known)
            if (index < 0) return@making null
            val liveOut = liveLeftOut(collection.generator.candidates.map { it.id }, choices.live, listOf(known))
            asAsked(step("chain") { quickIds(collection.generator, index, count, inclusion = inclusionIf(onlyIncluded), liveOut = liveOut, isCancelled = isCancelled) }, listOf(seed), listOf(known))
        }
    }

    /** Tracks played lately, which quick and random lists leave out when set to (Settings → Chaining); null: none. */
    private fun playedLately(): Set<String>? {
        val days = app.settings.chain.value.quickNotPlayedDays.takeIf { it > 0 } ?: return null
        return step("played lately") { runCatching { app.listening.playedSince(System.currentTimeMillis() - days * 86_400_000L) }.getOrNull() }
    }

    /** The quick list from track [index] of [g] (see [quickFromSeed]). */
    private fun quickIds(
        g: ListGenerator,
        index: Int,
        count: Int,
        played: Set<String>? = playedLately(),
        inclusion: com.sadurni.attacca.library.Inclusion? = null,
        liveOut: Set<String> = emptySet(),
        isCancelled: () -> Boolean = { false },
    ): List<String> {
        val excluded = if (liveOut.isEmpty()) played else played.orEmpty() + liveOut
        val base = ChainFilters(durationMinS = QUICK_MIN_SECONDS, durationMaxS = QUICK_MAX_SECONDS, excluded = excluded, inclusion = inclusion)
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
        choices: ListChoices = choicesByDefault(),
        onDone: (Pair<String, List<String>>?) -> Unit,
    ) {
        making(onlyIncluded, choices, onDone, "random") { isCancelled ->
            val g = collection()?.generator ?: return@making null
            val played = playedLately()
            val inclusion = inclusionIf(onlyIncluded)
            val liveOut = liveLeftOut(g.candidates.map { it.id }, choices.live)
            // Not a track played lately either, when set to (unless all of them were).
            val avoided = avoid?.let { libraryId(it) }
            val all = g.candidates.indices.filter {
                g.candidates[it].id != avoided && g.candidates[it].id !in liveOut && (inclusion == null || inclusion.includes(g.candidates[it].id))
            }
                .let { all -> if (played == null) all else all.filter { g.candidates[it].id !in played }.ifEmpty { all } }
            val pool = all.filter { mood == null || g.classification.hasMood(it, setOf(mood)) }.ifEmpty { all }
            // A file still there (only those tried are looked at).
            val index = pool.shuffled().firstOrNull { File(g.candidates[it].id).isFile } ?: return@making null
            g.candidates[index].id to step("chain") { quickIds(g, index, count, played, inclusion, liveOut, isCancelled) }
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
        choices: ListChoices = choicesByDefault(),
        onDone: (List<String>?) -> Unit,
    ) {
        making(filters.inclusion != null, choices, onDone) { isCancelled ->
            val collection = collection() ?: return@making null
            val known = seeds.map { libraryId(it) }
            val indices = collection.generator.indicesOf(known)
            if (indices.isEmpty()) return@making null
            asAsked(collection.generator.fromSeeds(indices, withLive(filters, collection.generator, choices.live, known), sameMood, sameFamily, count, app.settings.chain.value.artistSpacing, isCancelled), seeds, known)
        }
    }

    /** The other kinds of list (see [ListGenerator]'s "more kinds of list"). */
    enum class Kind {
        RISE_AND_FALL, WARM_UP, WIND_DOWN, REDISCOVER, SAMPLER, CONTRAST, FOR_TIME, ALBUMS_LIKE, BRIDGE,

        /** Energy along a curve drawn by hand ([MakeExtra.curve]). */
        DRAWN,

        /** Two people's tastes: two inclusion sets ([MakeExtra.first], [MakeExtra.second]) in turn. */
        FOR_TWO,

        /** Another list on the way of the seeds (the queue): same mood and energy, other tracks. */
        VARIANT,

        /** What is usually heard at this time of day, on days like today. */
        THIS_HOUR,

        /** Winding down for the minutes given (the volume going down, the player stopping at its end). */
        SLEEP,

        /** Rising for the minutes given (to wake up to, the volume going up). */
        WAKE,
    }

    /** What some kinds need besides seeds and a number: the drawn curve, the two sets of a list for two. */
    data class MakeExtra(val curve: List<Float>? = null, val first: String? = null, val second: String? = null) : java.io.Serializable

    /**
     * A list to wake up to of [minutes] (see [Kind.WAKE]), made quietly with the default choices:
     * the alarm's list, made ahead. Null when nothing is analysed.
     */
    suspend fun wakeList(minutes: Int): List<String>? = withContext(Background.dispatcher) {
        val choices = choicesByDefault()
        val g = collection()?.generator ?: return@withContext null
        val liveOut = liveLeftOut(g.candidates.map { it.id }, choices.live)
        withContext(EdgeFeatures.listTaste.asContextElement(app.settings.tasteOf(choices.profile))) {
            build(g, Kind.WAKE, emptyList(), minutes, 0, inclusionIf(onlyIncludedByDefault()), liveOut, MakeExtra()) { false }
        }
    }

    /** The inclusion the alarm's list is made with (for the tracks it adds when endless). */
    fun wakeInclusion(): com.sadurni.attacca.settings.ListInclusion = inclusionNow(onlyIncludedByDefault(), choicesByDefault())

    /** The inclusion of the set [id] (the one in use as it stands now); null: every track. */
    private fun inclusionOfSet(id: String?): com.sadurni.attacca.library.Inclusion? {
        val c = app.settings.chain.value
        if (id == null) return null
        if (id == c.activeSet) return c.inclusion()
        val set = c.inclusionSets.firstOrNull { it.id == id } ?: return null
        return com.sadurni.attacca.library.Inclusion.of(set.list, set.mode)
    }

    /**
     * Tracks heard around this time on days like today; before plays were kept with their time (or
     * when they are few), those last played near it. Only tracks of [g], by its own ids: one it
     * does not hold (another copy of a song, a damaged track, one outside the music folder) is
     * left out.
     */
    private fun heardAroundNow(g: ListGenerator): List<String> {
        fun inCollection(id: String): String? =
            id.takeIf { g.indexOf(it) >= 0 } ?: com.sadurni.attacca.library.Inclusion.aliases(id).firstOrNull { g.indexOf(it) >= 0 }
        val now = System.currentTimeMillis()
        val kept = runCatching { app.listening.playedAroundNow(now) }.getOrDefault(emptyList()).mapNotNull(::inCollection).distinct()
        step("${kept.size} at this hour") {}
        if (kept.size >= THIS_HOUR_MIN_SEEDS) return kept
        val c = java.util.Calendar.getInstance()
        fun minute(ms: Long): Int {
            c.timeInMillis = ms
            return c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE)
        }
        val nowMinute = minute(now)
        val near = app.listening.snapshot().entries
            .filter { it.value.lastPlayedMs > 0 && abs(minute(it.value.lastPlayedMs) - nowMinute).let { d -> minOf(d, 24 * 60 - d) } <= THIS_HOUR_WINDOW_MINUTES }
            .sortedByDescending { it.value.plays }
            .mapNotNull { inCollection(it.key) }
        return (kept + near).distinct().take(THIS_HOUR_MAX_SEEDS)
    }

    /**
     * A list of [kind]: [seeds] are the track it starts from (contrast, for a time, energy curves
     * when given), the album it is like (albums like) or the two ends (bridge); [value] is the
     * minutes (for a time) or the days not played (rediscover, 0: never played). Tracks played
     * lately are left out as for quick lists. [onDone] gets the ids, or null when it failed.
     */
    fun make(
        kind: Kind,
        seeds: List<String>,
        value: Int,
        count: Int,
        onlyIncluded: Boolean = onlyIncludedByDefault(),
        choices: ListChoices = choicesByDefault(),
        extra: MakeExtra = MakeExtra(),
        onDone: (List<String>?) -> Unit,
    ) {
        making(onlyIncluded, choices, onDone, kind.name.lowercase()) { isCancelled ->
            if (kind == Kind.THIS_HOUR) {
                // The tracks heard at this hour only guide the list: those the collection does not
                // hold are left out, never a collection made again around one (slow with a big library).
                val collection = collection() ?: return@making null
                val heard = step("heard") { heardAroundNow(collection.generator) }
                step("${heard.size} heard") {}
                val liveOut = liveLeftOut(collection.generator.candidates.map { it.id }, choices.live)
                return@making step("chain") { build(collection.generator, kind, heard, value, count, inclusionIf(onlyIncluded), liveOut, extra, isCancelled) }
            }
            val asked = seeds
            val known = asked.map { libraryId(it) }
            val collection = collection(extras = if (kind == Kind.BRIDGE || asked.size == 1) known else emptyList()) ?: return@making null
            val liveOut = liveLeftOut(collection.generator.candidates.map { it.id }, choices.live, if (kind == Kind.VARIANT) emptyList() else known)
            asAsked(step("chain") { build(collection.generator, kind, known, value, count, inclusionIf(onlyIncluded), liveOut, extra, isCancelled) }, asked, known)
        }
    }

    private suspend fun build(
        g: ListGenerator,
        kind: Kind,
        seeds: List<String>,
        value: Int,
        count: Int,
        inclusion: com.sadurni.attacca.library.Inclusion?,
        liveOut: Set<String>,
        extra: MakeExtra,
        isCancelled: () -> Boolean,
    ): List<String>? {
        val spacing = app.settings.chain.value.artistSpacing
        val played = playedLately()
        val base = ChainFilters(excluded = if (liveOut.isEmpty()) played else played.orEmpty() + liveOut, inclusion = inclusion)
        val first = seeds.firstOrNull()?.let(g::indexOf)?.takeIf { it >= 0 }
        return when (kind) {
            Kind.RISE_AND_FALL -> g.energyCurve(ListGenerator.EnergyShape.RISE_AND_FALL, first, base, count, spacing, isCancelled = isCancelled)
            Kind.WARM_UP -> g.energyCurve(ListGenerator.EnergyShape.WARM_UP, first, base, count, spacing, isCancelled = isCancelled)
            Kind.WIND_DOWN -> g.energyCurve(ListGenerator.EnergyShape.WIND_DOWN, first, base, count, spacing, isCancelled = isCancelled)
            Kind.SAMPLER -> g.sampler(base, count, spacing, isCancelled)
            Kind.CONTRAST -> first?.let { g.contrast(it, base, count, spacing, isCancelled) }
            Kind.FOR_TIME -> first?.let { g.forTime(it, base, value.coerceAtLeast(1), spacing, isCancelled) }
            Kind.BRIDGE -> {
                // Two ends: a bridge; more: a trip with stops, in their order.
                val stops = seeds.map(g::indexOf)
                val ends = ChainFilters(inclusion = inclusion, excluded = liveOut.ifEmpty { null })
                when {
                    stops.size < 2 || stops.any { it < 0 } -> null
                    stops.size == 2 -> g.bridge(stops[0], stops[1], ends, count, spacing, isCancelled)
                    else -> g.trip(stops, ends, count, spacing, isCancelled)
                }
            }
            Kind.DRAWN -> {
                val curve = extra.curve?.takeIf { it.size >= 2 } ?: return null
                g.energyCurve(curveOf(curve), first, base, count, spacing, isCancelled = isCancelled)
            }
            Kind.FOR_TWO -> {
                // Each side its own set; the set in use (if any) does not narrow it further.
                val a = inclusionOfSet(extra.first)
                val b = inclusionOfSet(extra.second)
                val ids = g.candidates.map { it.id }
                g.forTwo(
                    { a == null || a.includes(ids[it]) },
                    { b == null || b.includes(ids[it]) },
                    base.copy(inclusion = null), count, spacing, isCancelled = isCancelled,
                )
            }
            Kind.VARIANT -> g.variant(seeds, base, spacing, isCancelled)
            Kind.THIS_HOUR -> {
                // The tracks heard at this time are the seeds; heard lately or not, the list is like them.
                val heard = g.indicesOf(seeds)
                if (heard.isEmpty()) null else g.fromSeeds(heard, ChainFilters(excluded = liveOut.ifEmpty { null }, inclusion = inclusion), false, false, count, spacing, isCancelled)
            }
            Kind.SLEEP, Kind.WAKE -> {
                val minutes = value.coerceAtLeast(MIN_SLEEP_MINUTES)
                // Gentler than the named curves: nothing loud to fall asleep to, nothing loud to wake up to.
                val target: (Float) -> Float = if (kind == Kind.SLEEP) { p -> 0.6f - 0.5f * p } else { p -> 0.1f + 0.7f * p }
                g.energyCurve(target, first, base, g.tracksFor(minutes), spacing, isCancelled = isCancelled)?.let { g.takeFor(it, minutes) }
            }
            Kind.REDISCOVER -> {
                val favorites = com.sadurni.attacca.library.ListLoader.load(
                    com.sadurni.attacca.library.ListSource.Playlist(UserLists.favorites.absolutePath),
                ).map { it.mediaId }
                val heard = if (value > 0) app.listening.playedSince(System.currentTimeMillis() - value * 86_400_000L) else app.listening.everPlayed()
                g.fromSeeds(g.indicesOf(favorites), ChainFilters(excluded = heard + favorites + liveOut, inclusion = inclusion), false, false, count, spacing, isCancelled)
            }
            Kind.ALBUMS_LIKE -> albumsLike(g, seeds, base, count, isCancelled)
        }
    }

    /** The energy a drawn [curve] (its points evenly along the list, each 0 … 1) asks for at [p] of the list. */
    private fun curveOf(curve: List<Float>): (Float) -> Float = { p ->
        val x = p.coerceIn(0f, 1f) * (curve.size - 1)
        val i = x.toInt().coerceAtMost(curve.size - 2)
        val t = x - i
        (curve[i] * (1 - t) + curve[i + 1] * t).coerceIn(0f, 1f)
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
        const val THIS_HOUR_MIN_SEEDS = 5
        const val THIS_HOUR_MAX_SEEDS = 50
        const val THIS_HOUR_WINDOW_MINUTES = 90
        const val MIN_SLEEP_MINUTES = 5
    }

    private fun sanitize(name: String): String =
        name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").ifEmpty { "List" }
}
