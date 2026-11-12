package com.sadurni.attacca.ui

import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import com.sadurni.attacca.library.ListSummary
import com.sadurni.attacca.library.ListStats
import android.app.Application
import android.content.ComponentName
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.sadurni.attacca.app
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import com.sadurni.attacca.library.TrackSort
import com.sadurni.attacca.library.AlbumShuffle
import com.sadurni.attacca.library.Bookmark
import com.sadurni.attacca.library.ListLoader
import com.sadurni.attacca.library.ListSource
import com.sadurni.attacca.library.Lyrics
import com.sadurni.attacca.playback.AbRepeat
import com.sadurni.attacca.playback.PlaybackExtras
import com.sadurni.attacca.playback.SleepTimer
import com.sadurni.attacca.settings.ListSettings
import com.sadurni.attacca.settings.SleepChoice
import com.sadurni.attacca.library.MediaItems
import com.sadurni.attacca.library.SortField
import com.sadurni.attacca.library.UserLists
import com.sadurni.attacca.settings.ShuffleMode
import com.sadurni.attacca.playback.PlaybackService
import com.sadurni.attacca.ui.theme.ArtworkLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

data class NowPlaying(
    val mediaId: String? = null,
    val title: String = "",
    val artist: String? = null,
    val album: String? = null,
    val trackNumber: Int? = null,
    val date: String? = null,
    val genre: String? = null,
    val index: Int = 0,
    val count: Int = 0,
    val durationMs: Long = 0,
    val isPlaying: Boolean = false,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    /** Clip of the current item within its file (silence trim or playlist clip). */
    val clipStartMs: Long = 0,
    val clipEndMs: Long? = null,
    /** The clip comes from the playlist (a CUE track…), not from silence trimming. */
    val explicitClip: Boolean = false,
    /** Crossfade after the clip end: the track is heard that much longer than its clip. */
    val crossfadeMs: Int = 0,
    /** The track that plays next in play order (shuffle and repeat counted); null at the end of the list. */
    val nextTitle: String? = null,
    val nextArtist: String? = null,
)

/** The current file's waveform and which part of the file actually plays. */
class WaveformInfo(
    val waveform: FloatArray,
    val fileDurationMs: Long,
    val clipStartMs: Long,
    val clipEndMs: Long,
)

data class QueueEntry(val index: Int, val title: String, val subtitle: String, val mediaId: String, val durationMs: Long = 0)

/** Group keys of the queue entries, by position, for a list grouped by [field]. */
data class QueueGroups(val field: SortField, val keys: List<String>)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application.app

    private val _nowPlaying = MutableStateFlow(NowPlaying())
    val nowPlaying: StateFlow<NowPlaying> = _nowPlaying.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    /** The track [positionMs] belongs to (it may change a moment before [nowPlaying] does). */
    private val _positionItem = MutableStateFlow<String?>(null)
    val positionItem: StateFlow<String?> = _positionItem.asStateFlow()

    private val _queue = MutableStateFlow<List<QueueEntry>>(emptyList())
    val queue: StateFlow<List<QueueEntry>> = _queue.asStateFlow()

    private val _artwork = MutableStateFlow<Bitmap?>(null)
    val artwork: StateFlow<Bitmap?> = _artwork.asStateFlow()

    /** The cover together with the track it belongs to, published once it is decoded. */
    data class Cover(val mediaId: String?, val bitmap: Bitmap?)

    private val _cover = MutableStateFlow(Cover(null, null))
    val cover: StateFlow<Cover> = _cover.asStateFlow()

    /**
     * When the user last went back a track (button, gesture), so the player can slide the new
     * track in from the left; any other change slides it in from the right.
     */
    @Volatile
    var lastBackwardSkipAt = 0L
        private set

    private fun markBackward() {
        lastBackwardSkipAt = android.os.SystemClock.elapsedRealtime()
    }

    private val _artworkAccentDark = MutableStateFlow<Color?>(null)
    private val _artworkAccentLight = MutableStateFlow<Color?>(null)
    val artworkAccentDark: StateFlow<Color?> = _artworkAccentDark.asStateFlow()
    val artworkAccentLight: StateFlow<Color?> = _artworkAccentLight.asStateFlow()

    val listName: StateFlow<String> = app.currentListName

    val waveform: StateFlow<WaveformInfo?> =
        combine(_nowPlaying, app.analysisResults) { now, results ->
            val analysis = now.mediaId?.let(results::get) ?: return@combine null
            WaveformInfo(
                waveform = analysis.waveform,
                fileDurationMs = analysis.durationMs,
                clipStartMs = now.clipStartMs,
                clipEndMs = now.clipEndMs ?: analysis.durationMs,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // State owned by the playback service (PlaybackExtras), followed across service restarts.
    private fun <T> fromExtras(default: T, select: (PlaybackExtras) -> StateFlow<T>): StateFlow<T> =
        app.extras
            .flatMapLatest { extras -> extras?.let(select) ?: flowOf(default) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, default)

    val listSettings: StateFlow<ListSettings> = fromExtras(ListSettings()) { it.listSettings }

    /** Totals of the playing list for the footer of its screen. */
    val queueSummary: StateFlow<ListSummary?> =
        _queue.map { queue -> queue.map { it.mediaId } }
            .distinctUntilChanged()
            .flatMapLatest { ids -> ListStats.summary(ids).flowOn(com.sadurni.attacca.Background.dispatcher) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** True while the playing list is being sorted (tags are read the first time). */
    val sorting: StateFlow<Boolean> = fromExtras(false) { it.sorting }

    /** While the playing list is being chained: tracks analysed out of all. */
    val chaining: StateFlow<com.sadurni.attacca.chain.ChainAnalyzer.Progress?> = fromExtras(null) { it.chaining }

    /**
     * The group of each queue entry when the list reads in groups (sorted by album, artist…).
     * Tags come from the persistent cache, so this is quick except for never-seen files.
     */
    val queueGroups: StateFlow<QueueGroups?> =
        combine(_queue, listSettings) { queue, settings -> queue.map { it.mediaId } to settings.groupBy }
            .distinctUntilChanged()
            .mapLatest { (ids, field) ->
                if (field == null || ids.isEmpty()) return@mapLatest null
                withContext(com.sadurni.attacca.Background.dispatcher) {
                    TrackSort.warm(ids)
                    QueueGroups(field, ids.map { TrackSort.groupKey(it, field) })
                }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val sleepTimer: StateFlow<SleepTimer?> = fromExtras(null) { it.sleepTimer }

    /** The sleep timer option used last. */
    val lastSleepChoice: SleepChoice get() = app.settings.playback.value.lastSleep
    val abRepeat: StateFlow<AbRepeat> = fromExtras(AbRepeat()) { it.abRepeat }
    val speed: StateFlow<Float> = fromExtras(1f) { it.speed }
    val pitch: StateFlow<Float> = fromExtras(0f) { it.pitch }
    val lyrics: StateFlow<Lyrics?> = fromExtras(null) { it.lyrics }

    val extras: PlaybackExtras? get() = app.extras.value

    private var controller: MediaController? = null
    private val controllerFuture: ListenableFuture<MediaController>
    private var positionJob: Job? = null
    private var artworkJob: Job? = null
    private var artworkKey: Any? = null

    /** A play request made before the controller connected (e.g. "Open with" at startup). */
    private var pendingPlay: (() -> Unit)? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            refresh(jumped = events.containsAny(Player.EVENT_POSITION_DISCONTINUITY, Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_TIMELINE_CHANGED))
            if (events.contains(Player.EVENT_TIMELINE_CHANGED)) refreshQueue()
        }
    }

    init {
        val context = getApplication<Application>()
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(context, token).buildAsync()
        controllerFuture.addListener({
            val c = runCatching { controllerFuture.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(listener)
            refresh()
            refreshQueue()
            pendingPlay?.invoke()
            pendingPlay = null
        }, ContextCompat.getMainExecutor(context))
        // A repaired file is read again where it plays.
        viewModelScope.launch { com.sadurni.attacca.library.DamagedTracks.repaired.collect { reloadFile(it) } }
    }

    override fun onCleared() {
        positionJob?.cancel()
        MediaController.releaseFuture(controllerFuture)
    }

    // region Actions

    fun playItems(
        listName: String,
        source: ListSource?,
        items: List<MediaItem>,
        startIndex: Int = 0,
        startPositionMs: Long = 0L,
        /** A list made from the playing track: when it starts on that track, the track goes on where it is. */
        continueCurrent: Boolean = false,
    ) {
        if (items.isEmpty()) return
        val c = controller ?: run {
            pendingPlay = { playItems(listName, source, items, startIndex, startPositionMs, continueCurrent) }
            return
        }
        if (source == null) app.ungroupUnsavedList()
        app.setCurrentList(listName, source)
        val start = startIndex.coerceIn(0, items.lastIndex)
        val now = c.currentMediaItemIndex
        if (continueCurrent && c.mediaItemCount > 0 && now in 0 until c.mediaItemCount &&
            c.getMediaItemAt(now).mediaId == items[start].mediaId
        ) {
            // The playing track stays as it is (no new start, no gap): the list is put around it.
            if (now + 1 < c.mediaItemCount) c.removeMediaItems(now + 1, c.mediaItemCount)
            if (now > 0) c.removeMediaItems(0, now)
            if (start > 0) c.addMediaItems(0, items.subList(0, start))
            if (start + 1 < items.size) c.addMediaItems(items.subList(start + 1, items.size))
            if (!c.isPlaying) c.play()
            return
        }
        c.setMediaItems(items, start, startPositionMs)
        c.prepare()
        c.play()
    }

    /** O'rama asked to open wherever the app is (the new year's show). */
    val oramaRequest = MutableStateFlow(false)

    /** The show of this new year already opened O'rama (not again if closed). */
    var oramaShown = false

    /** Tracks, folders or a list whose damaged tracks to repair; shown as a dialog ([com.sadurni.attacca.ui.common.RepairDialog]). */
    val repairRequest = MutableStateFlow<List<String>?>(null)

    fun requestRepair(paths: List<String>) {
        val local = paths.filter { !it.contains("://") }
        if (local.isNotEmpty()) repairRequest.value = local
    }

    /**
     * [path] was written again (repaired): where it is in the playing list it is read again, the
     * playing one from where it was, so the old sound held in memory is not heard any more.
     */
    fun reloadFile(path: String) {
        val c = controller ?: return
        val fresh = com.sadurni.attacca.library.MediaItems.fromFiles(listOf(java.io.File(path))).firstOrNull() ?: return
        for (i in c.mediaItemCount - 1 downTo 0) {
            if (c.getMediaItemAt(i).mediaId != path) continue
            if (i == c.currentMediaItemIndex) {
                val position = c.currentPosition
                c.addMediaItem(i + 1, fresh)
                c.seekTo(i + 1, position)
                c.removeMediaItem(i)
            } else {
                c.removeMediaItem(i)
                c.addMediaItem(i, fresh)
            }
        }
    }

    /** Track or folder to add to or take out of the inclusion list; shown as a dialog. */
    val inclusionRequest = MutableStateFlow<String?>(null)

    fun requestInclusion(path: String) {
        if (!path.contains("://")) inclusionRequest.value = path
    }

    /** Track asked to seed a new list ("Create list from this track"); shown as a dialog. */
    val seedRequest = MutableStateFlow<String?>(null)

    fun requestSeedList(mediaId: String) {
        if (!mediaId.contains("://")) seedRequest.value = mediaId
    }

    /** A group of tracks (an album, an artist) asked to seed a new list of music like it: its name and tracks. */
    val groupSeedRequest = MutableStateFlow<Pair<String, List<String>>?>(null)

    fun requestGroupSeedList(name: String, ids: List<String>) {
        val files = ids.filter { !it.contains("://") }
        if (files.isNotEmpty()) groupSeedRequest.value = name to files
    }

    /** A list being made or about to play (its tracks being read, saved): no other is started meanwhile. */
    private val startingList = MutableStateFlow(false)

    /** While a list is being made or started: the buttons that make or change lists wait. */
    val listBusy: StateFlow<Boolean> = combine(app.chainLists.working, startingList) { making, starting -> making || starting }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** The track the playing list was made from at once, and that list's name (a second tap makes the same list). */
    private var quickMade: Pair<String, String>? = null

    /**
     * A list made at once from [mediaId] (same mood and family, filters fitted to it) and played,
     * without the dialog of "Create list from this track". Nothing while another list is being
     * made, nor when the playing list is already the one made from this track.
     */
    fun quickSeedList(
        mediaId: String?,
        count: Int = app.chainLists.listCount,
        onlyIncluded: Boolean = app.chainLists.onlyIncludedByDefault(),
        withOptions: Boolean = false,
    ) {
        val id = mediaId?.takeIf { !it.contains("://") } ?: return
        if (listBusy.value) return
        val context = getApplication<android.app.Application>()
        // Asked again with its own options: a list that may differ from the one playing.
        if (!withOptions && quickMade?.let { (seed, name) -> seed == id && app.currentListName.value == name } == true) {
            android.widget.Toast.makeText(context, com.sadurni.attacca.R.string.quick_list_already, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val title = com.sadurni.attacca.library.TrackSort.cachedTags(id)?.title?.ifBlank { null } ?: java.io.File(id).nameWithoutExtension
        val name = context.getString(com.sadurni.attacca.R.string.seed_list_name, title)
        android.widget.Toast.makeText(context, context.getString(com.sadurni.attacca.R.string.quick_list_making, title), android.widget.Toast.LENGTH_SHORT).show()
        startingList.value = true
        app.chainLists.quickFromSeed(id, count, onlyIncluded) { ids ->
            when {
                ids == null -> android.widget.Toast.makeText(context, com.sadurni.attacca.R.string.seed_not_analysable, android.widget.Toast.LENGTH_SHORT).show()
                ids.size < 2 -> android.widget.Toast.makeText(context, com.sadurni.attacca.R.string.seed_list_empty, android.widget.Toast.LENGTH_SHORT).show()
                else -> {
                    playIds(name, ids) { playedAs -> quickMade = id to playedAs }
                    return@quickFromSeed
                }
            }
            startingList.value = false
        }
    }

    /** Stops the list being made (the X of the notice at the top); nothing plays. */
    fun cancelList() {
        if (app.chainLists.making.value == null) return
        app.chainLists.cancel()
        startingList.value = false
        android.widget.Toast.makeText(getApplication(), com.sadurni.attacca.R.string.list_making_cancelled, android.widget.Toast.LENGTH_SHORT).show()
    }

    /** A list about to be made whose options (tracks, only included) are being chosen. */
    sealed interface OptionsRequest {
        data class Quick(val mediaId: String) : OptionsRequest
        data class Bridge(val from: String, val to: String) : OptionsRequest
    }

    val optionsRequest = MutableStateFlow<OptionsRequest?>(null)

    /** The quick list from [mediaId], with its options asked first. */
    fun requestQuickOptions(mediaId: String?) {
        val id = mediaId?.takeIf { !it.contains("://") } ?: return
        if (!listBusy.value) optionsRequest.value = OptionsRequest.Quick(id)
    }

    /** A bridge from [from] to [to]: its ends (swappable) and options are shown first. */
    fun requestBridge(from: String, to: String) {
        if (listBusy.value || from.contains("://") || to.contains("://")) return
        viewModelScope.launch {
            // The panel shows the two ends by their tags.
            withContext(Dispatchers.IO) { com.sadurni.attacca.library.TrackSort.warm(listOf(from, to)) }
            optionsRequest.value = OptionsRequest.Bridge(from, to)
        }
    }

    /** Whether the "Other lists" dialog is open. */
    val moreListsOpen = MutableStateFlow(false)

    /**
     * A list of one of the other kinds ([com.sadurni.attacca.chain.ChainLists.Kind]) made and
     * played: from the playing track (or [seeds] when given: the bridge's two ends), named after
     * what it is.
     */
    fun makeList(
        kind: com.sadurni.attacca.chain.ChainLists.Kind,
        value: Int = 0,
        seeds: List<String>? = null,
        onlyIncluded: Boolean = app.chainLists.onlyIncludedByDefault(),
        count: Int = app.chainLists.listCount,
    ) {
        if (listBusy.value) return
        val context = getApplication<android.app.Application>()
        val playing = nowPlaying.value.mediaId?.takeIf { !it.contains("://") }
        fun title(id: String) = com.sadurni.attacca.library.TrackSort.cachedTags(id)?.title?.ifBlank { null } ?: java.io.File(id).nameWithoutExtension
        val needsTrack = kind in setOf(com.sadurni.attacca.chain.ChainLists.Kind.CONTRAST, com.sadurni.attacca.chain.ChainLists.Kind.FOR_TIME, com.sadurni.attacca.chain.ChainLists.Kind.ALBUMS_LIKE)
        if (needsTrack && playing == null && seeds == null) {
            android.widget.Toast.makeText(context, com.sadurni.attacca.R.string.list_needs_track, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        startingList.value = true
        viewModelScope.launch {
            val chosen = seeds ?: when (kind) {
                com.sadurni.attacca.chain.ChainLists.Kind.ALBUMS_LIKE -> withContext(Dispatchers.IO) {
                    val db = com.sadurni.attacca.library.TrackSort.db
                    val tags = playing?.let { db?.getAll(listOf(it))?.get(it) }
                    if (db == null || tags == null) listOfNotNull(playing)
                    else db.pathsOfAlbum(tags.albumArtist.ifEmpty { tags.artist }, tags.album).ifEmpty { listOfNotNull(playing) }
                }
                com.sadurni.attacca.chain.ChainLists.Kind.SAMPLER, com.sadurni.attacca.chain.ChainLists.Kind.REDISCOVER -> emptyList()
                else -> listOfNotNull(playing)
            }
            // The names come from the tags (tracks picked in Music or Folders may not be read yet).
            withContext(Dispatchers.IO) { com.sadurni.attacca.library.TrackSort.warm(chosen) }
            val name = when (kind) {
                com.sadurni.attacca.chain.ChainLists.Kind.RISE_AND_FALL -> context.getString(com.sadurni.attacca.R.string.list_kind_rise_fall)
                com.sadurni.attacca.chain.ChainLists.Kind.WARM_UP -> context.getString(com.sadurni.attacca.R.string.list_kind_warm_up)
                com.sadurni.attacca.chain.ChainLists.Kind.WIND_DOWN -> context.getString(com.sadurni.attacca.R.string.list_kind_wind_down)
                com.sadurni.attacca.chain.ChainLists.Kind.REDISCOVER -> context.getString(com.sadurni.attacca.R.string.list_kind_rediscover)
                com.sadurni.attacca.chain.ChainLists.Kind.SAMPLER -> context.getString(com.sadurni.attacca.R.string.list_kind_sampler)
                com.sadurni.attacca.chain.ChainLists.Kind.CONTRAST -> context.getString(com.sadurni.attacca.R.string.list_kind_contrast, title(chosen.first()))
                com.sadurni.attacca.chain.ChainLists.Kind.FOR_TIME -> context.getString(com.sadurni.attacca.R.string.list_kind_time, value, title(chosen.first()))
                com.sadurni.attacca.chain.ChainLists.Kind.ALBUMS_LIKE -> context.getString(
                    com.sadurni.attacca.R.string.list_kind_albums,
                    chosen.firstOrNull()?.let { com.sadurni.attacca.library.TrackSort.cachedTags(it)?.album?.ifBlank { null } } ?: title(chosen.first()),
                )
                com.sadurni.attacca.chain.ChainLists.Kind.BRIDGE -> context.getString(com.sadurni.attacca.R.string.list_kind_bridge, title(chosen.first()), title(chosen.last()))
            }
            app.chainLists.make(kind, chosen, value, count, onlyIncluded) { ids ->
                when {
                    ids == null -> android.widget.Toast.makeText(context, com.sadurni.attacca.R.string.seed_not_analysable, android.widget.Toast.LENGTH_SHORT).show()
                    ids.size < 2 -> android.widget.Toast.makeText(context, com.sadurni.attacca.R.string.seed_list_empty, android.widget.Toast.LENGTH_SHORT).show()
                    else -> {
                        if (kind == com.sadurni.attacca.chain.ChainLists.Kind.ALBUMS_LIKE) groupWhenLoaded(ids.first(), ids.size, byAlbums = true)
                        playIds(name, ids)
                        return@make
                    }
                }
                startingList.value = false
            }
        }
    }

    /** A new list from a track at random (button, cover gesture): asks the mood first when set to. */
    fun requestRandomList() {
        if (listBusy.value) return
        com.sadurni.attacca.chain.RandomList.request(app, nowPlaying.value.mediaId) { name, ids -> playIds(name, ids) }
    }

    /** A new list from a track at random of [mood] (null: any), as chosen in the question. */
    fun randomList(
        mood: com.sadurni.attacca.chain.Mood?,
        onlyIncluded: Boolean = app.chainLists.onlyIncludedByDefault(),
        count: Int = app.chainLists.listCount,
    ) {
        if (listBusy.value) return
        com.sadurni.attacca.chain.RandomList.start(app, mood, nowPlaying.value.mediaId, onlyIncluded, count) { name, ids -> playIds(name, ids) }
    }

    /**
     * Plays the files [ids] in this order as a new list named [name]; saved first among the
     * user's lists when set to (Settings → Chaining), and then played as that list.
     */
    fun playIds(name: String, ids: List<String>, onPlayed: (String) -> Unit = {}) {
        startingList.value = true
        viewModelScope.launch {
            try {
                val items = withContext(Dispatchers.IO) { com.sadurni.attacca.library.MediaItems.fromFiles(ids.map { java.io.File(it) }) }
                val saved = if (items.isNotEmpty() && app.settings.chain.value.saveMadeLists) saveMade(name, items) else null
                if (saved != null) playItems(saved.name, saved, items, continueCurrent = true) else playItems(name, null, items, continueCurrent = true)
                onPlayed(saved?.name ?: name)
            } finally {
                startingList.value = false
            }
        }
    }

    /** [items] saved as a new list of the user's named [name] (or "name (2)"…); null when it could not be written. */
    private suspend fun saveMade(name: String, items: List<MediaItem>): ListSource.Playlist? = withContext(Dispatchers.IO) {
        runCatching {
            val file = UserLists.freeFileFor(name)
            UserLists.save(file, items)
            ListSource.Playlist(file.absolutePath)
        }.getOrNull()
    }

    /**
     * Shows the playing list by album (by its tag or its folder, as albums are taken) when it is
     * made of [byAlbums], else ungrouped, as soon as the player holds it ([count] tracks, on [firstId]).
     */
    fun groupWhenLoaded(firstId: String, count: Int, byAlbums: Boolean) {
        viewModelScope.launch {
            withTimeoutOrNull(LOAD_TIMEOUT_MS) {
                while (true) {
                    val extras = app.extras.value
                    if (extras != null && extras.holds(firstId, count)) {
                        val group = if (!byAlbums) null else if (AlbumShuffle.byFolder) SortField.PATH else SortField.ALBUM
                        extras.updateListSettings { it.copy(groupBy = group) }
                        break
                    }
                    delay(200)
                }
            }
        }
    }

    /** Chains the playing list from its playing track as soon as the player holds it ([count] tracks, on [firstId]). */
    fun chainWhenLoaded(firstId: String, count: Int) {
        viewModelScope.launch {
            withTimeoutOrNull(LOAD_TIMEOUT_MS) {
                while (true) {
                    val extras = app.extras.value
                    if (extras != null && extras.holds(firstId, count)) {
                        extras.chainQueue()
                        break
                    }
                    delay(200)
                }
            }
        }
    }

    /** Plays the list the player opens on as soon as it is loaded (a long list takes a while). */
    fun playWhenLoaded() {
        val c = controller ?: run {
            pendingPlay = ::playWhenLoaded
            return
        }
        viewModelScope.launch {
            withTimeoutOrNull(LOAD_TIMEOUT_MS) {
                while (c.mediaItemCount == 0) delay(200)
            } ?: return@launch
            if (c.isPlaying) return@launch
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    fun playPause() {
        val c = controller ?: return
        if (c.isPlaying) {
            c.pause()
        } else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition()
            c.play()
        }
    }

    fun next() = controller?.seekToNextMediaItem()

    /**
     * To the last seconds of the track (Settings → Playback), to hear its end and the transition:
     * that many seconds before its sound ends, its crossfade included.
     */
    fun skipToEnd() {
        val c = controller ?: return
        val item = c.currentMediaItem ?: return
        val duration = c.duration.takeIf { it != androidx.media3.common.C.TIME_UNSET && it > 0 } ?: return
        val before = app.settings.playback.value.toEndSeconds * 1000L - MediaItems.crossfadeMsOf(item)
        c.seekTo((duration - before.coerceAtLeast(MIN_BEFORE_END_MS)).coerceAtLeast(0L))
    }

    fun previous() {
        val c = controller ?: return
        markBackward()
        // Restart the track first, unless we are at its very beginning.
        if (c.currentPosition > RESTART_THRESHOLD_MS || !c.hasPreviousMediaItem()) c.seekTo(0) else c.seekToPreviousMediaItem()
    }

    /** First track of the next album in play order (shuffle-aware). */
    fun nextGroup() {
        val c = controller ?: return
        com.sadurni.attacca.playback.GroupSkip.next(c) { albumOf(c, it) }?.let { c.seekTo(it, 0) }
    }

    /** Start of the current album, or of the previous one when already at its start. */
    fun previousGroup() {
        val c = controller ?: return
        markBackward()
        c.seekTo(com.sadurni.attacca.playback.GroupSkip.previous(c, { albumOf(c, it) }, RESTART_THRESHOLD_MS), 0)
    }

    /** The group of an entry: the list's grouping when it has one, otherwise its folder. */
    private fun albumOf(c: Player, index: Int): String {
        val groups = queueGroups.value
        if (groups != null && groups.keys.size == c.mediaItemCount) return groups.keys[index]
        return AlbumShuffle.albumKey(c.getMediaItemAt(index).mediaId)
    }

    /**
     * Appends [items] to the playing list (or starts it when nothing is loaded). Reports how many
     * were added.
     */
    fun addToQueue(items: List<MediaItem>, name: String, onDone: (Int) -> Unit = {}) {
        if (items.isEmpty()) return
        val c = controller
        if (c == null || c.mediaItemCount == 0) {
            playItems(name, null, items)
        } else {
            app.extras.value?.rememberForUndo(com.sadurni.attacca.playback.ListChange.ADD)
            c.addMediaItems(items)
        }
        onDone(items.size)
    }

    /** Moves the entries [from] until [to] (exclusive) so they start at [newIndex]. */
    fun moveGroupInQueue(from: Int, to: Int, newIndex: Int) {
        val c = controller ?: return
        if (from < to && to <= c.mediaItemCount && newIndex != from) {
            app.extras.value?.rememberForUndo(com.sadurni.attacca.playback.ListChange.MOVE)
            c.moveMediaItems(from, to, newIndex)
        }
    }

    /** Off, tracks, albums, off. */
    fun cycleShuffle() {
        val extras = app.extras.value ?: return
        val next = when (extras.listSettings.value.shuffle) {
            ShuffleMode.OFF -> ShuffleMode.TRACKS
            ShuffleMode.TRACKS -> ShuffleMode.ALBUMS
            ShuffleMode.ALBUMS -> ShuffleMode.OFF
        }
        extras.setShuffleMode(next)
    }

    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
        _positionItem.value = controller?.currentMediaItem?.mediaId
        _positionMs.value = positionMs
    }

    fun playIndex(index: Int) {
        val c = controller ?: return
        c.seekTo(index, 0)
        c.play()
    }

    fun removeFromQueue(index: Int) {
        val c = controller ?: return
        if (index !in 0 until c.mediaItemCount) return
        app.extras.value?.rememberForUndo(com.sadurni.attacca.playback.ListChange.REMOVE)
        c.removeMediaItem(index)
    }

    /** Removes several entries (indices of the current list), highest first. */
    fun removeFromQueue(indices: Collection<Int>) {
        val c = controller ?: return
        if (indices.isEmpty()) return
        app.extras.value?.rememberForUndo(com.sadurni.attacca.playback.ListChange.REMOVE)
        indices.sortedDescending().forEach { if (it < c.mediaItemCount) c.removeMediaItem(it) }
    }

    /**
     * Leaves in the current list only the tracks the inclusion list allows (the playing one
     * stays, so playback goes on). [onDone] gets how many were removed, or -1 without a list.
     */
    fun keepOnlyIncluded(onDone: (Int) -> Unit) {
        val c = controller ?: return
        val inclusion = app.settings.chain.value.inclusion() ?: return onDone(-1)
        val ids = List(c.mediaItemCount) { c.getMediaItemAt(it).mediaId }
        viewModelScope.launch {
            val excluded = withContext(Dispatchers.IO) { ids.indices.filter { !inclusion.includes(ids[it]) } }
            if (List(c.mediaItemCount) { c.getMediaItemAt(it).mediaId } != ids) return@launch
            val current = c.currentMediaItemIndex
            val remove = excluded.filter { it != current }
            if (remove.isNotEmpty()) app.extras.value?.rememberForUndo(com.sadurni.attacca.playback.ListChange.KEEP_INCLUDED)
            // Runs of neighbours go at once, from the end so indices stay valid.
            var end = remove.size
            while (end > 0) {
                var start = end - 1
                while (start > 0 && remove[start - 1] == remove[start] - 1) start--
                c.removeMediaItems(remove[start], remove[end - 1] + 1)
                end = start
            }
            onDone(remove.size)
        }
    }

    fun moveInQueue(from: Int, to: Int) {
        val c = controller ?: return
        if (from != to && from in 0 until c.mediaItemCount && to in 0 until c.mediaItemCount) {
            app.extras.value?.rememberForUndo(com.sadurni.attacca.playback.ListChange.MOVE)
            c.moveMediaItem(from, to)
        }
    }

    fun sortQueue(field: SortField, descending: Boolean) = app.extras.value?.sortQueue(field, descending)

    fun queueItems(indices: Collection<Int>): List<MediaItem> {
        val c = controller ?: return emptyList()
        return indices.sorted().filter { it < c.mediaItemCount }.map(c::getMediaItemAt)
    }

    /** Puts [items] right after the playing track (or starts them when nothing is loaded). */
    fun insertNext(items: List<MediaItem>, name: String, onDone: (Int) -> Unit = {}) {
        if (items.isEmpty()) return
        val c = controller
        if (c == null || c.mediaItemCount == 0) {
            playItems(name, null, items)
        } else {
            app.extras.value?.rememberForUndo(com.sadurni.attacca.playback.ListChange.ADD)
            c.addMediaItems(c.currentMediaItemIndex + 1, items)
        }
        onDone(items.size)
    }

    /** Removes one entry and returns it, to put it back with [restoreToQueue]. */
    fun takeFromQueue(index: Int): MediaItem? {
        val c = controller ?: return null
        if (index !in 0 until c.mediaItemCount) return null
        val item = c.getMediaItemAt(index)
        app.extras.value?.rememberForUndo(com.sadurni.attacca.playback.ListChange.REMOVE)
        c.removeMediaItem(index)
        return item
    }

    fun restoreToQueue(index: Int, item: MediaItem) {
        val c = controller ?: return
        c.addMediaItem(index.coerceIn(0, c.mediaItemCount), item)
    }

    /** Moves a queue entry right after the playing one. */
    fun playNext(index: Int) {
        val c = controller ?: return
        val current = c.currentMediaItemIndex
        if (index == current) return
        app.extras.value?.rememberForUndo(com.sadurni.attacca.playback.ListChange.MOVE)
        c.moveMediaItem(index, if (index < current) current else current + 1)
    }

    // endregion

    // region Lists, favorites, bookmarks

    /**
     * The list last shown in a list screen: null for the playing list (queue). Swiping left on
     * the player returns to it.
     */
    private val _viewedList = MutableStateFlow<ListSource?>(null)
    val viewedList: StateFlow<ListSource?> = _viewedList.asStateFlow()

    /** A track the next list screen shows and scrolls to (set when opening a list on a track). */
    var revealInList: String? = null

    /**
     * "Create list like this" from the playing track's album, or all its artist's tracks
     * ([byArtist]), as from Music.
     */
    fun requestLikeCurrent(byArtist: Boolean) {
        val id = nowPlaying.value.mediaId?.takeIf { !it.contains("://") } ?: return
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) {
                val db = com.sadurni.attacca.library.TrackSort.db ?: return@withContext null
                val tags = db.getAll(listOf(id))[id] ?: return@withContext null
                val artist = tags.albumArtist.ifEmpty { tags.artist }
                if (byArtist) {
                    val name = tags.artist.ifEmpty { artist }
                    name to db.pathsOfArtist(name)
                } else {
                    tags.album to db.pathsOfAlbum(artist, tags.album)
                }
            } ?: return@launch
            val (name, ids) = found
            if (name.isNotEmpty() && ids.isNotEmpty()) requestGroupSeedList(name, ids)
        }
    }

    fun viewList(source: ListSource?) {
        // Viewing the list that is playing is the queue screen.
        _viewedList.value = source?.takeIf { it.key != app.currentListSource.value?.key }
        if (source is ListSource.Playlist) app.listHistory.rememberPlaylist(File(source.path))
    }

    /** Adds items to a list file, skipping the ones already there; reports how many were added. */
    fun addItemsToList(file: File, items: List<MediaItem>, onDone: (Int) -> Unit = {}) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            val added = withContext(Dispatchers.IO) { UserLists.append(file, items) }
            onDone(added)
            if (file == UserLists.favorites) {
                // The playing track may be among them: refresh the heart.
                favoriteKey = null
                refreshFavorite(controller?.currentMediaItem?.mediaId)
            }
        }
    }

    /** Starts a (non-playing) list at [index]; the remembered track continues where it was left. */
    fun playFromList(source: ListSource, items: List<MediaItem>, index: Int) {
        // A generated list is made again each time: played, it is kept among the user's lists when set to.
        val generated = (source as? ListSource.Playlist)?.let { File(it.path).parentFile?.canonicalPath == app.chainLists.generatedDirectory.canonicalPath } == true
        if (generated && app.settings.chain.value.saveMadeLists && items.isNotEmpty()) {
            viewModelScope.launch {
                val saved = saveMade(source.name, items)
                if (saved != null) playItems(saved.name, saved, items, index) else playItems(source.name, source, items, index)
                _viewedList.value = null
            }
            return
        }
        val point = app.resumeStore.get(source)
        val position = if (point != null && items.getOrNull(index)?.mediaId == point.mediaId) point.positionMs else 0L
        playItems(source.name, source, items, index, position)
        _viewedList.value = null
    }

    private fun queueItems(): List<MediaItem> {
        val c = controller ?: return emptyList()
        return List(c.mediaItemCount) { c.getMediaItemAt(it) }
    }

    /**
     * Saves the playing list as one of the user's lists. An unsaved list (generated, picked
     * files) or a playlist opened from elsewhere then becomes the saved list, where it is.
     */
    fun saveQueueAsList(name: String, onSaved: (File) -> Unit) {
        val items = queueItems()
        if (items.isEmpty()) return
        viewModelScope.launch {
            val file = UserLists.fileFor(name)
            withContext(Dispatchers.IO) { UserLists.save(file, items) }
            val current = app.currentListSource.value
            val unsaved = current == null || current is ListSource.Playlist && !UserLists.isUserList(File(current.path))
            if (unsaved && queueItems().map { it.mediaId } == items.map { it.mediaId }) {
                app.adoptCurrentList(ListSource.Playlist(file.absolutePath))
            }
            onSaved(file)
        }
    }

    fun addToList(file: File, queueIndex: Int? = null) {
        val c = controller ?: return
        val index = queueIndex ?: c.currentMediaItemIndex
        if (index !in 0 until c.mediaItemCount) return
        val item = c.getMediaItemAt(index)
        viewModelScope.launch(Dispatchers.IO) { UserLists.append(file, listOf(item)) }
    }

    private val _isFavorite = MutableStateFlow(false)
    val isFavorite: StateFlow<Boolean> = _isFavorite.asStateFlow()
    private var favoriteKey: String? = null

    fun toggleFavorite() {
        val item = controller?.currentMediaItem ?: return
        val wasFavorite = _isFavorite.value
        _isFavorite.value = !wasFavorite
        viewModelScope.launch(Dispatchers.IO) {
            if (wasFavorite) {
                UserLists.remove(UserLists.favorites, item.mediaId)
            } else {
                UserLists.append(UserLists.favorites, listOf(item))
            }
            app.favoritesChanged.tryEmit(Unit)
        }
    }

    init {
        // Changed from the car or the notification: read again.
        viewModelScope.launch {
            app.favoritesChanged.collect {
                val id = favoriteKey
                favoriteKey = null
                refreshFavorite(id)
            }
        }
    }

    private fun refreshFavorite(mediaId: String?) {
        if (mediaId == favoriteKey) return
        favoriteKey = mediaId
        viewModelScope.launch {
            _isFavorite.value = mediaId != null &&
                withContext(Dispatchers.IO) { UserLists.contains(UserLists.favorites, mediaId) }
        }
    }

    fun addBookmark(): Boolean {
        val c = controller ?: return false
        val item = c.currentMediaItem ?: return false
        // Bookmarks store the file position, independent of any silence trim.
        val position = (MediaItems.clipStartMs(item) ?: 0L) + c.currentPosition
        app.bookmarks.add(
            Bookmark(item.mediaId, position, MediaItems.displayTitle(item, c.mediaMetadata), System.currentTimeMillis()),
        )
        return true
    }

    fun openBookmark(bookmark: Bookmark) {
        val c = controller ?: return
        val index = queueItems().indexOfFirst { it.mediaId == bookmark.mediaId }
        if (index >= 0) {
            val clipStart = MediaItems.clipStartMs(c.getMediaItemAt(index)) ?: 0L
            c.seekTo(index, (bookmark.positionMs - clipStart).coerceAtLeast(0))
            c.play()
            return
        }
        // Not in the current list: play its folder from that file and position.
        val folder = File(bookmark.mediaId).parentFile ?: return
        val source = ListSource.Folder(folder.absolutePath, recursive = false)
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) { ListLoader.load(source) }
            val start = items.indexOfFirst { it.mediaId == bookmark.mediaId }.coerceAtLeast(0)
            playItems(source.name, source, items, start, bookmark.positionMs)
        }
    }

    /** Opens a list where it was left. */
    fun openList(source: ListSource, onEmpty: () -> Unit) {
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) { ListLoader.load(source) }
            if (items.isEmpty()) {
                onEmpty()
                return@launch
            }
            val point = app.resumeStore.get(source)
            val index = app.resumeStore.startIndex(point, items.map { it.mediaId })
            val position = if (point != null && items[index].mediaId == point.mediaId) point.positionMs else 0L
            playItems(source.name, source, items, index, position)
        }
    }

    // endregion

    /** [jumped]: the position may have moved anywhere (a seek, another track); else it only runs on. */
    private fun refresh(jumped: Boolean = true) {
        val c = controller ?: return
        // When the list has played to its end, Now playing is left empty (Play starts it again).
        val ended = c.playbackState == Player.STATE_ENDED
        val item = c.currentMediaItem.takeIf { !ended }
        val metadata = if (ended) androidx.media3.common.MediaMetadata.EMPTY else c.mediaMetadata
        val nextIndex = if (ended) androidx.media3.common.C.INDEX_UNSET else c.nextMediaItemIndex
        val nextItem = nextIndex.takeIf { it != androidx.media3.common.C.INDEX_UNSET && it < c.mediaItemCount }?.let(c::getMediaItemAt)
        _nowPlaying.value = NowPlaying(
            mediaId = item?.mediaId,
            title = item?.let { MediaItems.displayTitle(it, metadata) }.orEmpty(),
            artist = (metadata.artist ?: metadata.albumArtist)?.toString(),
            album = metadata.albumTitle?.toString(),
            trackNumber = metadata.trackNumber,
            date = yearOf(metadata),
            genre = metadata.genre?.toString(),
            index = c.currentMediaItemIndex,
            count = if (ended) 0 else c.mediaItemCount,
            durationMs = if (ended) 0 else c.duration.coerceAtLeast(0),
            isPlaying = c.isPlaying,
            shuffle = c.shuffleModeEnabled,
            repeatMode = c.repeatMode,
            clipStartMs = item?.let(MediaItems::clipStartMs) ?: 0L,
            clipEndMs = item?.let(MediaItems::clipEndMs),
            explicitClip = item?.let(MediaItems::hasExplicitClip) ?: false,
            crossfadeMs = item?.let(MediaItems::crossfadeMsOf) ?: 0,
            nextTitle = nextItem?.let { MediaItems.displayTitle(it) },
            nextArtist = nextItem?.let { next ->
                com.sadurni.attacca.library.TrackSort.cachedTags(next.mediaId)?.artist?.takeIf { it.isNotBlank() }
                    ?: next.mediaMetadata.artist?.toString()
            },
        )
        val raw = if (ended) 0L else positionOf(c)
        val shown = _positionMs.value
        // Other events (play state, metadata…) bring the same small corrections as the ticker.
        _positionItem.value = c.currentMediaItem?.mediaId
        _positionMs.value = if (!jumped && c.currentMediaItemIndex == tickedItem && raw < shown && shown - raw < MAX_HELD_STEP_BACK_MS) shown else raw
        tickedItem = c.currentMediaItemIndex
        updatePositionTicker(c.isPlaying)
        updateArtwork(item, metadata)
        refreshFavorite(item?.mediaId)
    }

    private fun refreshQueue() {
        val c = controller ?: return
        val items = List(c.mediaItemCount) { c.getMediaItemAt(it) }
        _queue.value = items.mapIndexed { i, item ->
            QueueEntry(
                i, MediaItems.displayTitle(item), com.sadurni.attacca.ui.common.trackSubtitle(item.mediaId), item.mediaId,
                com.sadurni.attacca.ui.common.trackDurationMs(item.mediaId).takeIf { it > 0 } ?: (item.mediaMetadata.durationMs ?: 0),
            )
        }
        // Artists come from the tag database; once known, the rows show them.
        val ids = items.map { it.mediaId }
        queueTagsJob?.cancel()
        queueTagsJob = viewModelScope.launch {
            fun showKnown() {
                if (_queue.value.map { it.mediaId } != ids) return
                _queue.value = _queue.value.map {
                    it.copy(
                        subtitle = com.sadurni.attacca.ui.common.trackSubtitle(it.mediaId),
                        durationMs = com.sadurni.attacca.ui.common.trackDurationMs(it.mediaId).takeIf { ms -> ms > 0 } ?: it.durationMs,
                    )
                }
            }
            val found = withContext(com.sadurni.attacca.Background.dispatcher) { com.sadurni.attacca.library.TrackSort.warmFromDb(ids) }
            if (found) showKnown()
            // Lengths (and tags) the database does not have yet are read from the files.
            withContext(com.sadurni.attacca.Background.dispatcher) { com.sadurni.attacca.library.TrackSort.fillMissingLengths(ids, onBatch = ::showKnown) }
            // Another reader (the list's totals) may have read them meanwhile.
            showKnown()
        }
    }

    private var queueTagsJob: Job? = null

    /** Where the playing track is: what the audio output plays now, else the session's position. */
    private fun positionOf(c: Player): Long =
        app.extras.value?.heardPositionMs(c.currentMediaItem?.mediaId) ?: c.currentPosition

    /** The track the position ticker last showed (a different one may start anywhere). */
    private var tickedItem = -1

    private fun updatePositionTicker(playing: Boolean) {
        if (playing && positionJob?.isActive != true) {
            positionJob = viewModelScope.launch {
                while (isActive) {
                    // Screens collect the position only while visible: with the screen off or the
                    // app in the background, wait instead of waking the main thread.
                    _positionMs.subscriptionCount.first { it > 0 }
                    controller?.let { c ->
                        // The controller runs the position on from its last update; when a new one
                        // comes and the audio was a little behind, it steps back a few hundred ms.
                        // While the same track plays on, small steps back wait for it to catch up.
                        val raw = positionOf(c)
                        val item = c.currentMediaItemIndex
                        val shown = _positionMs.value
                        _positionItem.value = c.currentMediaItem?.mediaId
                        _positionMs.value = if (item == tickedItem && raw < shown && shown - raw < MAX_HELD_STEP_BACK_MS) shown else raw
                        tickedItem = item
                    }
                    delay(POSITION_TICK_MS)
                }
            }
        } else if (!playing) {
            positionJob?.cancel()
            positionJob = null
        }
    }

    private fun updateArtwork(item: MediaItem?, metadata: MediaMetadata) {
        val data = metadata.artworkData
        val uri = metadata.artworkUri
        val key = Triple(item?.mediaId, data?.size, uri)
        if (key == artworkKey) return
        artworkKey = key
        artworkJob?.cancel()
        artworkJob = viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                val file = uri?.takeIf { it.scheme == "file" }?.path?.let(::File)
                val bitmap = ArtworkLoader.decode(data, file)
                Triple(
                    bitmap,
                    bitmap?.let { ArtworkLoader.accentFrom(it, darkBackground = true) },
                    bitmap?.let { ArtworkLoader.accentFrom(it, darkBackground = false) },
                )
            }
            _artwork.value = result.first
            _cover.value = Cover(item?.mediaId, result.first)
            _artworkAccentDark.value = result.second
            _artworkAccentLight.value = result.third
        }
    }

    /** The year only, even when the tag holds a whole date. */
    private fun yearOf(m: MediaMetadata): String? = (m.recordingYear ?: m.releaseYear)?.toString()

    private companion object {
        const val POSITION_TICK_MS = 200L

        /** Steps back of the reported position up to this are corrections, not seeks. */
        const val MAX_HELD_STEP_BACK_MS = 1_500L
        const val MIN_BEFORE_END_MS = 1_000L
        const val LOAD_TIMEOUT_MS = 20_000L

        const val RESTART_THRESHOLD_MS = 3_000L
    }
}
