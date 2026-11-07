package com.sadurni.attacca.library

import android.os.Environment
import com.sadurni.attacca.Background
import com.sadurni.attacca.library.InclusionRules.Kind
import com.sadurni.attacca.library.InclusionRules.Rule
import com.sadurni.attacca.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Adds and takes out tracks, folders, albums and artists of the inclusion list. The rules are
 * kept in a text file next to the list ([InclusionRules]) and the list is computed from them.
 * A list without a rules file (made elsewhere) is never rewritten until something is added or
 * taken out here; then its tracks become the first rules.
 */
class InclusionStore(private val settings: AppSettings, private val scope: CoroutineScope) {

    private val mutex = Mutex()

    /** The tracks the list allows or blocks, to mark the tracks left out in lists and folders. */
    class Effective(val mode: InclusionMode, private val paths: Set<String>) {
        fun includes(path: String): Boolean = (Inclusion.normalize(path) in paths) == (mode == InclusionMode.ALLOW)
    }

    private val _effective = MutableStateFlow<Effective?>(null)

    /** The configured list as it is now; null without a list. Follows every change. */
    val effective: StateFlow<Effective?> = _effective.asStateFlow()

    /** How much of a folder's music (with its subfolders) the list leaves out. */
    enum class FolderMark { NONE, PART, ALL }

    /** Folder marks for the current [effective]; walking a big folder takes a moment. */
    private val folderMarks = java.util.concurrent.ConcurrentHashMap<String, FolderMark>()

    /** How much of [folder] the list leaves out; [FolderMark.NONE] without a list. */
    suspend fun folderMark(folder: String): FolderMark {
        val e = _effective.value ?: return FolderMark.NONE
        folderMarks[folder]?.let { return it }
        return withContext(Background.dispatcher) {
            val files = AudioFiles.collectAudio(File(folder), recent = true)
            val out = files.count { !e.includes(it.absolutePath) }
            val mark = when {
                out == 0 -> FolderMark.NONE
                out == files.size -> FolderMark.ALL
                else -> FolderMark.PART
            }
            // Kept only while the list is the one it was worked out for.
            if (_effective.value === e) folderMarks[folder] = mark
            mark
        }
    }

    /** What the list is made of: its rules (empty for a list made elsewhere) and its tracks. */
    data class Overview(val list: File, val hasRules: Boolean, val rules: List<Rule>, val tracks: List<String>)

    private val tags = object : InclusionRules.Tags {
        override fun pathsOfArtist(artist: String) = TrackSort.db?.pathsOfArtist(artist).orEmpty()
        override fun pathsOfAlbum(artist: String, album: String) = TrackSort.db?.pathsOfAlbum(artist, album).orEmpty()
    }

    /** Keeps [effective] up to date with the list and the mode chosen in the settings. */
    fun start() {
        scope.launch {
            settings.chain.map { it.inclusionList to it.inclusionMode }.distinctUntilChanged().collect { reload() }
        }
    }

    private suspend fun reload() {
        val chain = settings.chain.value
        val loaded = withContext(Background.dispatcher) {
            chain.inclusionList?.let(::File)?.takeIf { it.isFile }?.let { list ->
                runCatching {
                    Effective(chain.inclusionMode, PlaylistFiles.parse(list).mapTo(HashSet()) { Inclusion.normalize(it.location) })
                }.getOrNull()
            }
        }
        folderMarks.clear()
        _effective.value = loaded
    }

    /**
     * Adds ([add]) or takes out [target] (kind and value; the include flag is ignored) and
     * rewrites the list. Without a list set, one is created in the app's folder. Returns how many
     * tracks the list has now.
     */
    suspend fun change(target: Rule, add: Boolean, set: String? = settings.chain.value.activeSet): Int = changeAll(listOf(target), add, set)

    /**
     * [change] for several targets at once (one rewrite of the list), in the inclusion set [set]
     * (the one in use unless said otherwise; it stays in use as it was).
     */
    suspend fun changeAll(targets: List<Rule>, add: Boolean, set: String? = settings.chain.value.activeSet): Int = edit {
        val list = ensureList(set)
        save(list, InclusionRules.changeAll(currentRules(list), targets, add, ::compute))
    }

    /** Replaces the rules with [rules] (edited in the app) and computes the list from them. */
    suspend fun saveRules(rules: List<Rule>): Int = edit { save(ensureList(), rules) }

    /** The configured list's rules and tracks; null without a list. */
    suspend fun overview(): Overview? = mutex.withLock {
        withContext(Background.dispatcher) {
            val list = settings.chain.value.inclusionList?.let(::File) ?: return@withContext null
            val rulesFile = InclusionRules.rulesFileOf(list)
            Overview(
                list,
                rulesFile.isFile,
                if (rulesFile.isFile) InclusionRules.parse(rulesFile.readText()) else emptyList(),
                if (list.isFile) PlaylistFiles.parse(list).map { Inclusion.normalize(it.location) }.distinct() else emptyList(),
            )
        }
    }

    /** Computes the list again from its rules (new files in its folders, albums, artists). */
    suspend fun refresh(): Int? = edit {
        val list = settings.chain.value.inclusionList?.let(::File) ?: return@edit null
        val rulesFile = InclusionRules.rulesFileOf(list)
        if (!rulesFile.isFile) return@edit null
        write(list, compute(InclusionRules.parse(rulesFile.readText())))
    }

    /**
     * The list was edited by hand: its rules are thrown away and made again from its tracks, one
     * rule per track. Returns how many tracks it has.
     */
    suspend fun reimport(): Int? = edit {
        val list = settings.chain.value.inclusionList?.let(::File)?.takeIf { it.isFile } ?: return@edit null
        val rules = trackRules(list)
        InclusionRules.rulesFileOf(list).writeText(InclusionRules.format(rules))
        rules.size
    }

    /** Runs a change of the list off the main thread, one at a time, then follows it in [effective]. */
    private suspend fun <T> edit(block: () -> T): T {
        val result = mutex.withLock { withContext(Background.dispatcher) { block() } }
        reload()
        return result
    }

    private fun currentRules(list: File): List<Rule> {
        val rulesFile = InclusionRules.rulesFileOf(list)
        return if (rulesFile.isFile) {
            InclusionRules.parse(rulesFile.readText())
        } else {
            // The list's own tracks become rules, so nothing in it is lost.
            if (list.isFile) trackRules(list) else emptyList()
        }
    }

    private fun save(list: File, rules: List<Rule>): Int {
        list.parentFile?.mkdirs()
        InclusionRules.rulesFileOf(list).writeText(InclusionRules.format(rules))
        return write(list, compute(rules))
    }

    private fun trackRules(list: File): List<Rule> =
        runCatching { PlaylistFiles.parse(list) }.getOrDefault(emptyList()).map { Rule(true, Kind.TRACK, Inclusion.normalize(it.location)) }.distinct()

    /** Whether the configured list is computed from rules. */
    fun hasRules(): Boolean =
        settings.chain.value.inclusionList?.let { InclusionRules.rulesFileOf(File(it)).isFile } == true

    private fun ensureList(setId: String? = settings.chain.value.activeSet): File {
        val chain0 = settings.chain.value
        val set = chain0.inclusionSets.firstOrNull { it.id == setId }
        if (set != null && set.id != chain0.activeSet) {
            set.list?.let { return File(it) }
            val file = newListFile(set.name)
            com.sadurni.attacca.settings.InclusionSets.setList(settings, set.id, file.absolutePath)
            return file
        }
        settings.chain.value.inclusionList?.let { return File(it) }
        val folder = File(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Attacca"), "Inclusion")
        folder.mkdirs()
        // Named after the set in use, so each set has a file of its own.
        val chain = settings.chain.value
        val name = com.sadurni.attacca.settings.InclusionSets.nameOf(chain, chain.activeSet)
            ?.replace(Regex("[\\/:*?\"<>|]"), "_")?.trim()?.ifBlank { null } ?: "Inclusion"
        var list = File(folder, "$name.m3u8")
        var n = 2
        while (list.exists()) list = File(folder, "$name ${n++}.m3u8")
        settings.updateChain { it.copy(inclusionList = list.absolutePath) }
        return list
    }

    /**
     * A copy of the inclusion set [id] named [name] (its list and its rules copied to files of
     * its own, the same mode), put in use to be changed. Returns the new set's id.
     */
    suspend fun duplicate(id: String, name: String): String? = mutex.withLock {
        withContext(Background.dispatcher) {
            val chain = settings.chain.value
            val set = chain.inclusionSets.firstOrNull { it.id == id } ?: return@withContext null
            val newId = com.sadurni.attacca.settings.InclusionSets.add(settings, name)
            val newName = com.sadurni.attacca.settings.InclusionSets.nameOf(settings.chain.value, newId) ?: name
            set.list?.let(::File)?.takeIf { it.isFile }?.let { list ->
                val copy = newListFile(newName)
                list.copyTo(copy)
                InclusionRules.rulesFileOf(list).takeIf { it.isFile }?.copyTo(InclusionRules.rulesFileOf(copy), overwrite = true)
                com.sadurni.attacca.settings.InclusionSets.setList(settings, newId, copy.absolutePath)
            }
            settings.updateChain { c -> c.copy(inclusionSets = c.inclusionSets.map { if (it.id == newId) it.copy(mode = set.mode) else it }) }
            newId
        }
    }.also { newId -> if (newId != null) { com.sadurni.attacca.settings.InclusionSets.select(settings, newId); reload() } }

    /** A list file of its own for the set [name], in Music/Attacca/Inclusion. */
    private fun newListFile(name: String): File {
        val folder = File(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Attacca"), "Inclusion")
        folder.mkdirs()
        val base = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "Inclusion" }
        var file = File(folder, "$base.m3u8")
        var n = 2
        while (file.exists()) file = File(folder, "$base ${n++}.m3u8")
        return file
    }

    private fun compute(rules: List<Rule>): List<String> =
        InclusionRules.compute(rules, tags) { folder -> AudioFiles.collectAudio(File(folder), recent = true).map { it.absolutePath } }

    private fun write(list: File, tracks: List<String>): Int {
        list.parentFile?.mkdirs()
        M3uParser.write(list, tracks.map { PlaylistEntry(it) })
        return tracks.size
    }
}
