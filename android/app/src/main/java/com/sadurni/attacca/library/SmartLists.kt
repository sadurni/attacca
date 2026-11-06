package com.sadurni.attacca.library

import com.sadurni.attacca.AttaccaApp
import com.sadurni.attacca.Background
import com.sadurni.attacca.chain.ChainEngine
import com.sadurni.attacca.chain.ChainTrack
import com.sadurni.attacca.chain.ListGenerator
import com.sadurni.attacca.library.SmartRules.Field
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The user's smart lists ([SmartRules]): which lists are smart, and making each one again from
 * its rules over the music folder — tags from the library scan, plays from the listening
 * statistics, mood, tempo, energy, positivity and voice from the analysis (only when a rule
 * needs them), and the inclusion list.
 */
class SmartLists(private val app: AttaccaApp) {

    private val mutex = Mutex()

    fun isSmart(list: File): Boolean = SmartRules.rulesFileOf(list).isFile

    fun rulesOf(list: File): SmartRules.Rules? =
        runCatching { SmartRules.parse(SmartRules.rulesFileOf(list).readText()) }.getOrNull()

    /** The tracks [rules] give now (paths, in order). Blocking work runs in the background. */
    suspend fun tracksFor(rules: SmartRules.Rules): List<String> = withContext(Background.dispatcher) {
        val folder = app.settings.musicFolder ?: return@withContext emptyList()
        val tagged = TrackSort.db?.tracks(folder).orEmpty()
        val stats = app.listening.snapshot()
        val analysisFields = setOf(Field.MOOD, Field.BPM, Field.ENERGY, Field.POSITIVITY, Field.VOICE)
        val needsAnalysis = rules.conditions.any { it.field in analysisFields }
        val classified = if (needsAnalysis) runCatching { app.chainLists.collection() }.getOrNull()?.generator else null
        val indexOf = classified?.candidates?.withIndex()?.associate { (i, c) -> c.id to i }.orEmpty()
        val effective = app.inclusionStore.effective.value
        val tracks = tagged.map { t ->
            val s = stats[t.path]
            val i = indexOf[t.path]
            SmartRules.Track(
                path = t.path,
                tags = t.tags,
                modifiedMs = t.modifiedMs,
                plays = s?.plays ?: 0,
                skips = s?.skips ?: 0,
                lastPlayedMs = s?.lastPlayedMs ?: 0,
                mood = i?.let { classified!!.moodOf(it).name.lowercase() },
                bpm = i?.let { classified!!.candidates[it].features.tempoBpm.toDouble() },
                energy = i?.let { classified!!.classification.energy[it].toDouble() },
                positivity = i?.let { classified!!.classification.positivity[it].toDouble() },
                voice = i?.let { classified!!.classification.voice[it] >= ListGenerator.VOICE_PERCENTILE },
                included = effective?.includes(t.path) ?: true,
            )
        }
        val found = SmartRules.evaluate(rules, tracks, System.currentTimeMillis()).map { it.path }
        if (rules.order == SmartRules.Order.CHAINED && found.size > 1) chained(found) else found
    }

    /** [ids] chained from a random one, as Chain list does (tracks never analysed are analysed first). */
    private suspend fun chained(ids: List<String>): List<String> {
        val edges = app.chainAnalyzer.edgesFor(ids)
        TrackSort.warm(ids)
        val tracks = ids.map { id -> ChainTrack(edges[id]?.head, edges[id]?.tail, TrackSort.tags(id).artist) }
        val order = ChainEngine.order(tracks, ids.indices.random(), app.settings.chain.value.artistSpacing) ?: return ids
        return order.map { ids[it] }
    }

    /** Writes [rules] next to [list] and makes the list from them. Returns how many tracks it has. */
    suspend fun save(list: File, rules: SmartRules.Rules): Int = mutex.withLock {
        withContext(Background.dispatcher) {
            list.parentFile?.mkdirs()
            SmartRules.rulesFileOf(list).writeText(SmartRules.format(rules))
        }
        write(list, rules)
    }

    /** Makes [list] again from its rules; null when it is not a smart list. */
    suspend fun update(list: File): Int? = mutex.withLock {
        val rules = withContext(Background.dispatcher) { rulesOf(list) } ?: return@withLock null
        write(list, rules)
    }

    /** Makes every smart list of the user's again (after a library scan). */
    suspend fun updateAll() {
        val lists = withContext(Background.dispatcher) { UserLists.all().filter(::isSmart) }
        for (list in lists) runCatching { update(list) }
    }

    private suspend fun write(list: File, rules: SmartRules.Rules): Int {
        val ids = tracksFor(rules)
        withContext(Background.dispatcher) { M3uParser.write(list, ids.map { PlaylistEntry(it) }) }
        return ids.size
    }
}
