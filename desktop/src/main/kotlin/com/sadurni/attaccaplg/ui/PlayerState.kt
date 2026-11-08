package com.sadurni.attaccaplg.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sadurni.attacca.dsp.CrossfadeStyle
import com.sadurni.attaccaplg.generate.Library
import com.sadurni.attaccaplg.library.LibraryDb
import com.sadurni.attaccaplg.library.StoredTrack
import com.sadurni.attaccaplg.player.PlayItem
import com.sadurni.attaccaplg.player.PlaybackEngine
import com.sadurni.attaccaplg.player.PlaybackSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Repeat: none, the whole queue, or the playing track. */
enum class Repeat { OFF, ALL, ONE }

/** When the sleep timer stops the music: at a time, or at the end of the track or of its album. */
sealed interface Sleep {
    data class At(val epochMs: Long) : Sleep
    data object EndOfTrack : Sleep
    data object EndOfAlbum : Sleep
}

/**
 * The player as the window sees it: the queue, what is playing and where, the transport and its
 * settings. The sound itself is [PlaybackEngine]'s. The queue and where it was are kept in
 * queue.json beside the library, so the player opens where it was left.
 */
class PlayerState(private val prefs: Prefs, private val library: () -> Library?) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    var settings by mutableStateOf(loadSettings())
        private set

    /** Attacca's sound processing, its own for each output (as the phone's device profiles). */
    var dsp by mutableStateOf(loadDsp(prefs["output_device"]?.takeIf { it.isNotBlank() }))
        private set
    private val engine = PlaybackEngine({ settings }, { dsp })

    var queue by mutableStateOf<List<PlayItem>>(emptyList())
        private set
    var index by mutableStateOf(-1)
        private set
    var positionMs by mutableStateOf(0L)
        private set
    var durationMs by mutableStateOf(0L)

    /** The playing track's sound from … to (its silences left out) and its blend into the next, ms. */
    var cutStartMs by mutableStateOf(0L)
    var cutEndMs by mutableStateOf(0L)
    var blendMs by mutableStateOf(0)
        private set
    var playing by mutableStateOf(false)
        private set
    var repeat by mutableStateOf(runCatching { Repeat.valueOf(prefs["repeat"] ?: "") }.getOrDefault(Repeat.OFF))
        private set
    var shuffle by mutableStateOf(prefs["shuffle"] == "true")
        private set
    var device by mutableStateOf(prefs["output_device"]?.takeIf { it.isNotBlank() })
        private set

    /** Favourite tracks (full paths), kept in Favorites.m3u8 beside the library. */
    var favorites by mutableStateOf(loadFavorites())
        private set

    var sleep by mutableStateOf<Sleep?>(null)
        private set

    /** The queue before shuffling, to go back to. */
    /** The lists kept here (Attacca's way: made ones, the user's own, where each was left). */
    val lists = SavedLists()

    /** The saved list playing (its place is kept as it plays); null for anything else. */
    var source by mutableStateOf<File?>(null)

    private var unshuffled: List<PlayItem>? = null
    private var lastChanges = -1
    private var lastSaved = 0L

    /** Nothing is saved until the queue left last time is back (it would be saved over, empty). */
    private var restored = false

    val current: PlayItem? get() = queue.getOrNull(index)

    /** The outputs there were at the last look, to notice one being connected or disconnected. */
    private var outputs: Set<String> = emptySet()

    init {
        engine.repeatMode = repeat.ordinal
        // The output chosen last time, when it is here; the system's meanwhile (it is taken back,
        // with its list, as soon as it is connected).
        outputs = runCatching { com.sadurni.attaccaplg.audio.AudioOut.devices().toSet() }.getOrDefault(emptySet())
        if (device != null && device !in outputs) {
            device = null
            dsp = loadDsp(null)
        }
        engine.setDevice(device)
        restore()
        scope.launch {
            while (true) {
                follow()
                delay(200)
            }
        }
        scope.launch {
            while (true) {
                delay(3000)
                watchOutputs()
            }
        }
    }

    /** Each output keeps its own list (where it was left), as each Bluetooth device in Attacca. */
    val perOutput: Boolean get() = prefs["device_lists"] != "false"

    /**
     * An output connected that had a list of its own: playing goes there with its list; the
     * output in use disconnected: playing stops, on the system's output with its list.
     */
    private suspend fun watchOutputs() {
        val now = kotlinx.coroutines.withContext(Dispatchers.IO) {
            runCatching { com.sadurni.attaccaplg.audio.AudioOut.devices().toSet() }.getOrNull()
        } ?: return
        val appeared = now - outputs
        val gone = outputs - now
        outputs = now
        val current = device
        if (current != null && current in gone) {
            engine.pause()
            changeDevice(null, connected = true)
            return
        }
        if (prefs["device_switch"] == "false" || !perOutput) return
        val back = appeared.firstOrNull { it == prefs["output_device"] || queueFile(it).isFile } ?: return
        if (back != current) changeDevice(back, connected = true)
    }

    /** Takes what the engine is doing into the window's state. */
    private fun follow() {
        val s = engine.snapshot
        val changed = s.trackChanges != lastChanges
        lastChanges = s.trackChanges
        index = s.index
        positionMs = s.positionMs
        durationMs = s.durationMs
        cutStartMs = s.cutStartMs
        cutEndMs = s.cutEndMs
        blendMs = s.blendMs
        val wasPlaying = playing
        playing = s.playing
        when (val timer = sleep) {
            is Sleep.At -> if (System.currentTimeMillis() >= timer.epochMs) {
                sleep = null
                engine.pause()
            }
            // Stopped where the timer said: the timer is done.
            Sleep.EndOfTrack, Sleep.EndOfAlbum -> if (wasPlaying && !s.playing) sleep = null
            null -> {}
        }
        val now = System.currentTimeMillis()
        if (changed || now - lastSaved > 10_000) {
            lastSaved = now
            save()
        }
    }

    /** [path] was written again (repaired): when it plays, it is read again from where it was. */
    fun reloadFile(path: String) {
        val item = current ?: return
        if (File(item.path).path != File(path).path) return
        val wasPlaying = playing
        engine.play(queue, index, positionMs)
        if (!wasPlaying) engine.toggle()
    }

    fun play(items: List<PlayItem>, start: Int = 0, positionMs: Long = 0, source: File? = null) {
        this.source = source
        unshuffled = null
        val list = if (shuffle) shuffled(items, start).also { unshuffled = items } else items
        val at = if (shuffle) 0 else start
        queue = list
        index = at
        engine.play(list, at, positionMs)
        playing = true
        save()
    }

    /** Plays a saved list from where it was left ([fromStart]: from its first track). */
    fun playList(file: File, fromStart: Boolean = false) {
        scope.launch {
            val items = kotlinx.coroutines.withContext(Dispatchers.IO) { itemsOfFiles(listOf(file)) }
            if (items.isEmpty()) return@launch
            val at = if (fromStart) null else lists.positionOf(file)
            val start = at?.let { p -> items.indexOfFirst { it.path == p.path } }?.takeIf { it >= 0 }
            play(items, start ?: 0, if (start != null) at.ms else 0, source = file)
            prefs["last_list"] = file.path
        }
    }

    /** The kept list played last (to go on with it from Playing), while it is still there. */
    val lastList: File? get() = prefs["last_list"]?.let(::File)?.takeIf { it.isFile }

    /** The queue as a list of the user's own. */
    fun saveQueueAs(name: String): File? = queue.takeIf { it.isNotEmpty() }?.let { lists.saveAs(name, it.map { item -> item.path }) }

    /** Puts library tracks in the queue, ready but not playing (the first one at [start]). */
    fun cuePaths(paths: List<String>, start: Int = 0) {
        val items = itemsOf(paths)
        if (items.isEmpty()) return
        source = null
        queue = items
        index = start.coerceIn(0, items.lastIndex)
        engine.play(items, index, autoplay = false)
    }

    /** Plays library tracks by their paths within the music folder. */
    fun playPaths(paths: List<String>, start: Int = 0, continueCurrent: Boolean = false) {
        val items = itemsOf(paths)
        if (items.isEmpty()) return
        val first = (paths.getOrNull(start)?.let { p -> items.indexOfFirst { it.path.endsWith(p.replace('/', File.separatorChar)) } } ?: 0).coerceAtLeast(0)
        // A list made from the playing track: when it starts on it, the track goes on where it is.
        val keep = continueCurrent && playing && current?.path == items[first].path && !shuffle
        play(items, first, if (keep) positionMs else 0L)
    }

    /** Adds library tracks at the end of the queue, or right after the playing one. */
    fun enqueue(paths: List<String>, next: Boolean) {
        val items = itemsOf(paths)
        if (items.isEmpty()) return
        if (queue.isEmpty()) {
            play(items, 0)
            return
        }
        val at = if (next) index + 1 else queue.size
        val updated = queue.toMutableList().apply { addAll(at.coerceIn(0, size), items) }
        queue = updated
        engine.setQueue(updated, index)
        save()
    }

    fun remove(i: Int) {
        if (i !in queue.indices) return
        if (i == index) {
            val updated = queue.toMutableList().apply { removeAt(i) }
            queue = updated
            if (updated.isEmpty()) stop() else engine.play(updated, i.coerceAtMost(updated.lastIndex), autoplay = playing)
        } else {
            val updated = queue.toMutableList().apply { removeAt(i) }
            queue = updated
            val newIndex = if (i < index) index - 1 else index
            index = newIndex
            engine.setQueue(updated, newIndex)
        }
        save()
    }

    fun clear() = stop()

    private fun stop() {
        source = null
        queue = emptyList()
        index = -1
        engine.play(emptyList(), -1, autoplay = false)
        save()
    }

    /** The levels of what is heard now (level, low, high), for Attacca O'rama. */
    fun heardLevels(into: FloatArray): Boolean = engine.heardLevels(into)

    fun toggle() = engine.toggle()
    fun next() = engine.next()
    fun previous() = engine.previous()
    fun jumpTo(i: Int) = engine.jumpTo(i)
    fun seek(ms: Long) {
        positionMs = ms
        engine.seek(ms)
    }

    fun changeRepeat() {
        repeat = Repeat.entries[(repeat.ordinal + 1) % Repeat.entries.size]
        prefs["repeat"] = repeat.name
        engine.repeatMode = repeat.ordinal
        // The next track was planned for the old mode.
        engine.setQueue(queue, index)
    }

    fun changeShuffle() {
        shuffle = !shuffle
        prefs["shuffle"] = shuffle.toString()
        val playingItem = current ?: return
        val updated = if (shuffle) {
            unshuffled = queue
            shuffled(queue, index)
        } else {
            unshuffled?.also { unshuffled = null } ?: queue
        }
        queue = updated
        val newIndex = updated.indexOf(playingItem)
        index = newIndex
        engine.setQueue(updated, newIndex)
        save()
    }

    fun update(change: (PlaybackSettings) -> PlaybackSettings) {
        settings = change(settings)
        val s = settings
        prefs["crossfade_ms"] = s.crossfadeMs.toString()
        prefs["crossfade_style"] = s.crossfadeStyle.name
        prefs["trim_silences"] = s.trimSilences.toString()
        prefs["level_loudness"] = s.levelLoudness.toString()
        prefs["volume"] = s.volume.toString()
    }

    fun updateDsp(change: (com.sadurni.attacca.settings.DspSettings) -> com.sadurni.attacca.settings.DspSettings) {
        dsp = change(dsp)
        prefs[dspKey(device)] = dsp.toJson().toString()
    }

    private fun dspKey(device: String?) = "sound." + (device ?: "default")

    private fun loadDsp(device: String?): com.sadurni.attacca.settings.DspSettings =
        prefs[dspKey(device)]?.let { runCatching { com.sadurni.attacca.settings.DspSettings.fromJson(JSONObject(it)) }.getOrNull() }
            ?: com.sadurni.attacca.settings.DspSettings()

    /**
     * Plays on output [name] (null: the system's). With a list per output, the one left keeps its
     * list where it was and the new one takes up its own (playing on if music was playing, or if the
     * settings say so); [connected]: the output came or went by itself (it is not remembered as
     * chosen when it went).
     */
    fun changeDevice(name: String?, connected: Boolean = false) {
        if (name == device) return
        val wasPlaying = playing
        if (perOutput) save()
        device = name
        dsp = loadDsp(name)
        if (!connected || name != null) prefs["output_device"] = name.orEmpty()
        engine.setDevice(name)
        if (perOutput && queueFile(name).isFile) {
            load(queueFile(name), autoplay = (wasPlaying && !(connected && name == null)) || prefs["device_list_play"] == "true")
        }
    }

    /** Sets (or with null, cancels) the sleep timer. */
    fun sleepAt(timer: Sleep?) {
        sleep = timer
        engine.stopAfter = when (timer) {
            Sleep.EndOfTrack -> index
            Sleep.EndOfAlbum -> albumEnd(index)
            else -> -1
        }
    }

    /** The last track of the run of the album that track [i] is on. */
    private fun albumEnd(i: Int): Int {
        var end = i
        while (end + 1 in queue.indices && PlaybackEngine.followsOnAlbum(queue[end], queue[end + 1])) end++
        return end
    }

    fun isFavorite(path: String?) = path != null && path in favorites

    fun toggleFavorite(path: String) {
        saveFavorites(if (path in favorites) favorites - path else favorites + path)
    }

    /** Adds [paths] to the favourites (those already in stay where they are); how many were new. */
    fun addFavorites(paths: List<String>): Int {
        val before = favorites.size
        saveFavorites(favorites + paths)
        return favorites.size - before
    }

    private fun saveFavorites(new: Set<String>) {
        favorites = new
        runCatching {
            favoritesFile.parentFile.mkdirs()
            favoritesFile.writeText("#EXTM3U\n" + favorites.joinToString("") { "$it\n" })
        }
        lists.refresh()
    }

    private val favoritesFile get() = SavedLists.FAVORITES

    private fun loadFavorites(): Set<String> = runCatching {
        favoritesFile.takeIf { it.isFile }?.readLines()?.filter { it.isNotBlank() && !it.startsWith("#") }?.toCollection(LinkedHashSet())
    }.getOrNull() ?: emptySet()

    /** Plays files of this PC (in the library or not), playlists (M3U, PLS, XSPF, CUE) and folders. */
    fun playFiles(files: List<File>) {
        scope.launch {
            val items = kotlinx.coroutines.withContext(Dispatchers.IO) { itemsOfFiles(files) }
            if (items.isNotEmpty()) play(items, 0)
        }
    }

    private fun itemsOfFiles(files: List<File>): List<PlayItem> {
        val lib = library()
        val root = lib?.root
        val known = if (lib != null && root != null) lib.all.associateBy { File(root, it.path).path } else emptyMap()
        val audio = setOf("mp3", "ogg", "oga", "opus", "flac", "wav", "aif", "aiff", "m4a", "aac", "wma")
        val lists = setOf("m3u", "m3u8", "pls", "xspf", "cue")
        val paths = ArrayList<File>()
        for (f in files) {
            when {
                f.isDirectory -> paths += f.walkTopDown().filter { it.isFile && it.extension.lowercase() in audio }.sortedBy { it.path.lowercase() }
                // M3U as this PC reads it: full paths with their drive ("C:\…"), or relative to the list.
                f.extension.lowercase() in setOf("m3u", "m3u8") -> this.lists.read(f).mapNotNullTo(paths) { p -> File(p).takeIf { it.isFile } }
                f.extension.lowercase() in lists -> runCatching { com.sadurni.attacca.library.PlaylistFiles.parse(f) }.getOrDefault(emptyList())
                    .mapNotNullTo(paths) { e -> File(e.location).takeIf { it.isFile } }
                f.isFile -> paths += f
            }
        }
        return paths.map { f ->
            known[f.path]?.let { itemOf(root!!, it) } ?: run {
                val tags = runCatching { com.sadurni.attaccaplg.library.TagReader.read(f) }.getOrNull()
                PlayItem(f.path, tags?.title?.ifBlank { null } ?: f.nameWithoutExtension, tags?.artist.orEmpty(), tags?.album.orEmpty(), 0,
                    track = tags?.track, disc = tags?.disc)
            }
        }
    }

    /** The library's record of a queue item, when it is in the library. */
    fun trackOf(item: PlayItem?): StoredTrack? {
        val lib = library() ?: return null
        val root = lib.root ?: return null
        val rel = item?.path?.let { runCatching { File(it).relativeTo(root).invariantSeparatorsPath }.getOrNull() } ?: return null
        return lib.byPath[rel]
    }

    fun close() {
        save()
        engine.close()
    }

    /** The playing track first, then the rest at random. */
    private fun shuffled(items: List<PlayItem>, first: Int): List<PlayItem> {
        val head = items.getOrNull(first)
        return listOfNotNull(head) + items.filterIndexed { i, _ -> i != first }.shuffled()
    }

    private fun itemsOf(paths: List<String>): List<PlayItem> {
        val lib = library() ?: return emptyList()
        val root = lib.root ?: return emptyList()
        return paths.mapNotNull { p -> lib.byPath[p]?.let { itemOf(root, it) } }
    }

    private fun loadSettings() = PlaybackSettings(
        crossfadeMs = prefs["crossfade_ms"]?.toIntOrNull() ?: 10_000,
        crossfadeStyle = runCatching { CrossfadeStyle.valueOf(prefs["crossfade_style"] ?: "") }.getOrDefault(CrossfadeStyle.AUTOMATIC),
        trimSilences = prefs["trim_silences"] != "false",
        levelLoudness = prefs["level_loudness"] != "false",
        volume = prefs["volume"]?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f,
    )

    /** Where the queue is kept: one for all, or one per output (the system's in queue.json, as before). */
    private fun queueFile(device: String?): File =
        if (device == null || !perOutput) File(LibraryDb.HOME, "queue.json")
        else File(LibraryDb.HOME, "queues/" + SavedLists.safe(device) + ".json")

    private fun save() {
        if (!restored) return
        runCatching {
            val json = JSONObject()
                .put("paths", JSONArray(queue.map { it.path }))
                .put("index", index)
                .put("position", positionMs)
                .put("unshuffled", unshuffled?.let { list -> JSONArray(list.map { it.path }) } ?: JSONObject.NULL)
                .put("source", source?.path ?: JSONObject.NULL)
            val file = queueFile(device)
            file.parentFile.mkdirs()
            file.writeText(json.toString())
        }
        // The saved list playing keeps its place.
        val list = source
        val item = current
        if (list != null && item != null) runCatching { lists.remember(list, item.path, positionMs) }
    }

    /** The queue as it was left, ready to play (not playing). */
    private fun restore() {
        val own = queueFile(device)
        val file = if (own.isFile) own else File(LibraryDb.HOME, "queue.json")
        load(file, autoplay = perOutput && prefs["device_list_play"] == "true", first = true)
    }

    /** A queue as it was left, from [file]. */
    private fun load(file: File, autoplay: Boolean, first: Boolean = false) {
        scope.launch {
          try {
            // The library comes a moment after the window opens.
            var lib = library()
            var waited = 0
            while (lib == null && waited < 100) {
                delay(100)
                waited++
                lib = library()
            }
            runCatching {
                if (!file.isFile) return@launch
                val json = JSONObject(file.readText())
                val byFile = lib?.root?.let { root -> lib.all.associateBy { File(root, it.path).path } }.orEmpty()
                fun item(path: String) = byFile[path]?.let { itemOf(lib!!.root!!, it) }
                    ?: File(path).takeIf { it.isFile }?.let { PlayItem(it.path, it.nameWithoutExtension, "", "", 0) }
                val paths = json.getJSONArray("paths")
                val items = (0 until paths.length()).mapNotNull { item(paths.getString(it)) }
                if (items.isEmpty()) return@launch
                unshuffled = json.optJSONArray("unshuffled")?.let { a -> (0 until a.length()).mapNotNull { item(a.getString(it)) } }
                val at = json.optInt("index", 0).coerceIn(0, items.lastIndex)
                source = json.optString("source").takeIf { it.isNotEmpty() && json.opt("source") != JSONObject.NULL }?.let(::File)?.takeIf { it.isFile }
                queue = items
                index = at
                engine.play(items, at, json.optLong("position", 0), autoplay = autoplay)
                if (autoplay) playing = true
            }
          } finally {
            if (first) restored = true
          }
        }
    }

    companion object {
        /** A library track as the player takes it. */
        fun itemOf(root: File, t: StoredTrack): PlayItem = PlayItem(
            path = File(root, t.path).path,
            title = t.tags.title.ifBlank { File(t.path).nameWithoutExtension },
            artist = t.tags.artist,
            album = t.tags.album,
            durationMs = t.durationMs,
            soundStartMs = t.audioStartMs.takeIf { t.audioEndMs > t.audioStartMs },
            soundEndMs = t.audioEndMs.takeIf { it > t.audioStartMs },
            lufs = t.lufs,
            peak = t.peak,
            waveform = t.waveform,
            track = t.tags.track,
            disc = t.tags.disc,
        )
    }
}
