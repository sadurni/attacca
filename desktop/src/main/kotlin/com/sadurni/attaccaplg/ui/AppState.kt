package com.sadurni.attaccaplg.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sadurni.attacca.library.InclusionMode
import com.sadurni.attaccaplg.generate.Library
import com.sadurni.attaccaplg.library.LibraryDb
import com.sadurni.attaccaplg.library.ScanResult
import com.sadurni.attaccaplg.library.Scanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class Page { PLAYING, ORAMA, MUSIC, COVERS, LIBRARY, TRACKS, LISTS, SOUND, EXCHANGE, THEMES, SETTINGS }

/** The Lists page: making lists, or the lists kept (Attacca's way). */
enum class ListsView { MAKE, SAVED }

/** A scan going on: how far it is. */
class ScanProgress(val done: Int, val todo: Int, val path: String, val started: Long)

/**
 * Everything the window shows and does: the library file, the library made from it (classified,
 * ready for lists), the work going on. One piece of work at a time ([busy]); the rest waits.
 */
class AppState(val prefs: Prefs, dbFile: File = LibraryDb.DEFAULT) {

    val db = LibraryDb(dbFile)

    /** O'rama over the whole screen: no window frame, menu, titles or small player. */
    var oramaFull by mutableStateOf(false)

    /** The page shown: Playing from the start, once there is a library (Library to make one). */
    var page by mutableStateOf(if (runCatching { db.stamps().isNotEmpty() }.getOrDefault(false)) Page.PLAYING else Page.LIBRARY)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)


    /** The theme's id (see [Themes.of]). */
    var theme by mutableStateOf(prefs["theme"] ?: "system")

    /** The track chosen in Tracks (its path). */
    var trackSelected by mutableStateOf<String?>(null)

    var listsView by mutableStateOf(ListsView.MAKE)

    /** Dialogs opened from the right-click menus: a name, tracks to add to a list (full paths), a track's information. */
    var askName by mutableStateOf<NameRequest?>(null)
    var addToList by mutableStateOf<List<String>?>(null)
    var info by mutableStateOf<com.sadurni.attaccaplg.library.StoredTrack?>(null)

    /** The built-in themes take their accent from the playing track's cover (the others say so themselves). */
    var coverAccent by mutableStateOf(prefs["cover_accent"] != "false")

    /** The library, classified; null while loading or when empty. */
    var library by mutableStateOf<Library?>(null)
        private set
    var loading by mutableStateOf(true)
        private set

    var musicRoot by mutableStateOf(db.musicRoot?.path)
        private set
    var inclusionList by mutableStateOf(db.meta("inclusion_list")?.takeIf { it.isNotBlank() })
        private set
    var inclusionMode by mutableStateOf(modeOf(db.meta("inclusion_mode")))
        private set

    /** The inclusion lists kept by name, and the one in use (null: none, every track), as on the phone. */
    var inclusionSets by mutableStateOf(com.sadurni.attacca.library.InclusionSet.listFromJson(db.meta("inclusion_sets")))
        private set
    var activeSet by mutableStateOf(db.meta("inclusion_active")?.takeIf { it.isNotBlank() })
        private set
    var edgeSeconds by mutableStateOf(db.edgeSeconds)
        private set

    /** What is being done (blocks other work), or null. */
    var busy by mutableStateOf<String?>(null)
        private set
    var scan by mutableStateOf<ScanProgress?>(null)
        private set
    var lastScan by mutableStateOf<ScanResult?>(null)
        private set
    @Volatile private var cancelScan = false

    /** A message for the bottom of the window. */
    var message by mutableStateOf<String?>(null)

    /** The tracks found damaged (path within the music folder → what), and the check under way (done, total). */
    var damaged by mutableStateOf<Map<String, com.sadurni.attaccaplg.library.Damage.Found>>(emptyMap())
        private set
    var damageCheck by mutableStateOf<Pair<Int, Int>?>(null)
        private set
    /** About how long the check still takes (ms), once it can be told. */
    var damageRemainingMs by mutableStateOf<Long?>(null)
        private set
    @Volatile private var cancelDamage = false
    var skipDamaged by mutableStateOf(db.meta("skip_damaged") != "false")
        private set

    fun changeSkipDamaged(on: Boolean) {
        db.setMeta("skip_damaged", on.toString())
        skipDamaged = on
        reload()
    }

    /**
     * Checks the MP3s of the library for damage, several at once: those never checked or changed
     * since ([all]: every one again, so a check stopped halfway goes on where it was otherwise).
     * The lists follow when done.
     */
    fun checkDamage(all: Boolean = false) {
        val root = db.musicRoot ?: return
        if (damageCheck != null) return
        if (all) synchronized(db) { runCatching { db.clearDamageChecked() } }
        val checked = synchronized(db) { runCatching { db.damageChecked() }.getOrDefault(emptyMap()) }
        val tracks = library?.all?.filter { t ->
            val f = File(root, t.path)
            val c = checked[t.path]
            c == null || c[0] != f.length() || c[1] != f.lastModified()
        } ?: return
        val started = System.currentTimeMillis()
        damageRemainingMs = null
        cancelDamage = false
        damageCheck = 0 to tracks.size
        scope.launch {
            val done = java.util.concurrent.atomic.AtomicInteger()
            withContext(Dispatchers.IO) {
                val pool = java.util.concurrent.Executors.newFixedThreadPool((Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1))
                try {
                    tracks.map { t ->
                        pool.submit {
                            if (cancelDamage) return@submit
                            val file = File(root, t.path)
                            val result = runCatching { com.sadurni.attaccaplg.library.Damage.check(file) }.getOrNull()
                            synchronized(db) {
                                if (result == null) runCatching { db.forgetDamaged(t.path) }
                                else runCatching { db.putDamaged(t.path, file.length(), file.lastModified(), result.first, result.second) }
                                runCatching { db.putDamageChecked(t.path, file.length(), file.lastModified()) }
                            }
                            val n = done.incrementAndGet()
                            if (n % 25 == 0) {
                                val elapsed = System.currentTimeMillis() - started
                                // After a few seconds the pace so far tells the time left.
                                val left = if (elapsed > 5_000) elapsed * (tracks.size - n) / n else null
                                scope.launch { damageCheck = n to tracks.size; damageRemainingMs = left }
                            }
                        }
                    }.forEach { it.get() }
                } finally {
                    pool.shutdown()
                }
            }
            damageCheck = null
            reload()
        }
    }

    fun stopDamageCheck() {
        cancelDamage = true
    }

    /** Checks [path] again: forgotten when fine. */
    fun recheckDamaged(path: String) {
        val root = db.musicRoot ?: return
        scope.launch {
            val file = File(root, path)
            val result = withContext(Dispatchers.IO) { runCatching { com.sadurni.attaccaplg.library.Damage.check(file) }.getOrNull() }
            synchronized(db) {
                if (result == null) db.forgetDamaged(path) else db.putDamaged(path, file.length(), file.lastModified(), result.first, result.second)
            }
            message = if (result == null) tr("It is fine now", "Ahora está bien") else tr("Still damaged", "Sigue dañada")
            reload()
        }
    }

    fun forgetDamaged(path: String) {
        synchronized(db) { db.forgetDamaged(path) }
        reload()
    }

    /** Repairs the MP3 [path] (the original kept beside it as .bk, or deleted); it is measured again by the next scan. */
    fun repairDamaged(path: String, keepOriginal: Boolean) {
        val root = db.musicRoot ?: return
        scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { com.sadurni.attacca.playback.Mp3Damage.repairFile(File(root, path), keepOriginal) }.getOrDefault(false) }
            if (ok) synchronized(db) { db.forgetDamaged(path) }
            message = if (ok) tr("Repaired: scan the library to measure it again.", "Reparada: escanea la biblioteca para medirla de nuevo.")
            else tr("It could not be repaired", "No se ha podido reparar")
            reload()
        }
    }

    val lists = ListsForm(prefs)

    /** The mini player window is open. */
    var mini by mutableStateOf(false)

    /** The player: its queue, what plays and its settings. */
    val player = PlayerState(prefs) { library }

    init {
        reload()
    }

    /** Runs [work] off the window's thread, one at a time; failures become a message. */
    /** beets, when it is on this PC (null: not found); looked for when Settings first shows it. */
    var beets by mutableStateOf<com.sadurni.attaccaplg.beets.Beets.Setup?>(null)
    var beetsLooked by mutableStateOf(false)
    private var beetsLooking = false

    fun findBeets() {
        if (beetsLooking) return
        beetsLooking = true
        beetsLooked = false
        scope.launch {
            beets = kotlinx.coroutines.withContext(Dispatchers.IO) { com.sadurni.attaccaplg.beets.Beets.find(prefs["beets_command"]) }
            beetsLooked = true
            beetsLooking = false
        }
    }

    fun run(label: String, work: suspend () -> Unit) {
        if (busy != null) return
        busy = label
        scope.launch {
            try {
                work()
            } catch (e: Throwable) {
                message = tr("Failed: ", "Ha fallado: ") + (e.message ?: e.javaClass.simpleName)
            } finally {
                busy = null
            }
        }
    }

    /** Reads the library again (after a scan, an import…). */
    fun reload() {
        loading = true
        scope.launch {
            val made = withContext(Dispatchers.IO) {
                runCatching { if (db.stamps().isEmpty()) null else Library(db).takeIf { it.all.isNotEmpty() } }.getOrNull()
            }
            library = made
            musicRoot = db.musicRoot?.path
            damaged = withContext(Dispatchers.IO) { synchronized(db) { com.sadurni.attaccaplg.library.Damage.read(db, db.musicRoot) } }
            inclusionList = db.meta("inclusion_list")?.takeIf { it.isNotBlank() }
            inclusionMode = modeOf(db.meta("inclusion_mode"))
            inclusionSets = com.sadurni.attacca.library.InclusionSet.listFromJson(db.meta("inclusion_sets"))
            activeSet = db.meta("inclusion_active")?.takeIf { it.isNotBlank() }
            keepSetsInStep()
            edgeSeconds = db.edgeSeconds
            lists.libraryChanged(made)
            loading = false
        }
    }

    fun scan(root: File) = run(tr("Scanning", "Escaneando")) {
        cancelScan = false
        val started = System.currentTimeMillis()
        var shown = 0L
        scan = ScanProgress(0, 0, "", started)
        val jobs = prefs["jobs"]?.toIntOrNull() ?: Scanner.defaultJobs()
        val result = withContext(Dispatchers.IO) {
            Scanner(db, jobs).scan(root, cancelled = { cancelScan }) { done, todo, path ->
                val now = System.currentTimeMillis()
                if (now - shown > 150 || done == todo) {
                    shown = now
                    scan = ScanProgress(done, todo, path, started)
                }
            }
        }
        scan = null
        lastScan = result
        reload()
    }

    /** Measures only the silences the tracks lack (see [Scanner.measureSilences]), with the scan's progress. */
    fun measureSilences(root: File) = run(tr("Measuring silences", "Midiendo silencios")) {
        cancelScan = false
        val started = System.currentTimeMillis()
        var shown = 0L
        scan = ScanProgress(0, 0, "", started)
        val jobs = prefs["jobs"]?.toIntOrNull() ?: Scanner.defaultJobs()
        val n = withContext(Dispatchers.IO) {
            Scanner(db, jobs).measureSilences(root, cancelled = { cancelScan }) { done, todo, path ->
                val now = System.currentTimeMillis()
                if (now - shown > 150 || done == todo) {
                    shown = now
                    scan = ScanProgress(done, todo, path, started)
                }
            }
        }
        scan = null
        message = tr("Silences measured for $n tracks.", "Silencios medidos de $n pistas.")
        reload()
    }

    fun stopScan() {
        cancelScan = true
    }

    fun setInclusion(list: File?, mode: InclusionMode) {
        db.setMeta("inclusion_list", list?.absolutePath ?: "")
        db.setMeta("inclusion_mode", mode.name)
        inclusionList = list?.absolutePath
        inclusionMode = mode
        lists.onlyIncluded = lists.onlyIncluded && list != null
        keepSetsInStep()
    }

    /**
     * The set in use follows the list and mode; a list chosen with none in use (or kept from
     * before sets existed) becomes a set named after its file.
     */
    private fun keepSetsInStep() {
        var sets = inclusionSets
        var active = activeSet?.takeIf { id -> sets.any { it.id == id } }
        val list = inclusionList
        if (active == null && list != null) {
            val set = com.sadurni.attacca.library.InclusionSet(java.util.UUID.randomUUID().toString(), uniqueSetName(File(list).nameWithoutExtension.ifBlank { "List" }), list, inclusionMode)
            sets = sets + set
            active = set.id
        }
        if (active != null) sets = sets.map { if (it.id == active) it.copy(list = list, mode = inclusionMode) else it }
        saveSets(sets, active)
    }

    private fun saveSets(sets: List<com.sadurni.attacca.library.InclusionSet>, active: String?) {
        inclusionSets = sets
        activeSet = active
        db.setMeta("inclusion_sets", com.sadurni.attacca.library.InclusionSet.listToJson(sets))
        db.setMeta("inclusion_active", active ?: "")
    }

    /** Puts the set [id] in use (null: none, every track). */
    fun selectSet(id: String?) {
        val set = inclusionSets.firstOrNull { it.id == id }
        activeSet = set?.id
        db.setMeta("inclusion_active", set?.id ?: "")
        setInclusion(set?.list?.let(::File), set?.mode ?: inclusionMode)
        lists.onlyIncluded = set != null
    }

    fun createSet(name: String) {
        val set = com.sadurni.attacca.library.InclusionSet(java.util.UUID.randomUUID().toString(), uniqueSetName(name.trim().ifBlank { "List" }), null, InclusionMode.BLOCK)
        saveSets(inclusionSets + set, activeSet)
        selectSet(set.id)
    }

    /** A copy of the set [id] named [name] (its list file copied, rules too), put in use to be changed. */
    fun duplicateSet(id: String, name: String) {
        val set = inclusionSets.firstOrNull { it.id == id } ?: return
        val newName = uniqueSetName(name.trim().ifBlank { set.name })
        val copy = set.list?.let(::File)?.takeIf { it.isFile }?.let { list ->
            val base = list.name.substringBefore('.')
            val ext = list.name.substring(base.length)
            var target = File(list.parentFile, newName.replace(Regex("[\\\\/:*?\"<>|]"), "_") + ext)
            var n = 2
            while (target.exists()) target = File(list.parentFile, newName.replace(Regex("[\\\\/:*?\"<>|]"), "_") + " ${n++}" + ext)
            runCatching { list.copyTo(target) }.getOrNull()
        }
        val made = com.sadurni.attacca.library.InclusionSet(java.util.UUID.randomUUID().toString(), newName, copy?.absolutePath, set.mode)
        saveSets(inclusionSets + made, activeSet)
        selectSet(made.id)
    }

    fun renameSet(id: String, name: String) {
        val others = inclusionSets.filter { it.id != id }
        saveSets(inclusionSets.map { if (it.id == id) it.copy(name = uniqueSetName(name.trim().ifBlank { it.name }, others)) else it }, activeSet)
    }

    fun deleteSet(id: String) {
        if (activeSet == id) selectSet(null)
        saveSets(inclusionSets.filter { it.id != id }, activeSet)
    }

    private fun uniqueSetName(name: String, among: List<com.sadurni.attacca.library.InclusionSet> = inclusionSets): String {
        val taken = among.map { it.name.lowercase() }.toSet()
        if (name.lowercase() !in taken) return name
        var n = 2
        while ("$name $n".lowercase() in taken) n++
        return "$name $n"
    }

    fun changeEdgeSeconds(seconds: Int) {
        db.edgeSeconds = seconds
        edgeSeconds = db.edgeSeconds
    }

    fun close() {
        player.close()
        cancelScan = true
        db.close()
    }

    private fun modeOf(name: String?) = runCatching { InclusionMode.valueOf(name ?: "") }.getOrDefault(InclusionMode.ALLOW)
}
