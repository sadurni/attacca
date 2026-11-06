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

enum class Page { PLAYING, ORAMA, MUSIC, LIBRARY, TRACKS, LISTS, SOUND, EXCHANGE, THEMES, SETTINGS }

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
            inclusionList = db.meta("inclusion_list")?.takeIf { it.isNotBlank() }
            inclusionMode = modeOf(db.meta("inclusion_mode"))
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
