package com.sadurni.attacca.playback

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import com.sadurni.attacca.AttaccaApp
import com.sadurni.attacca.analysis.CrossfadeRule
import com.sadurni.attacca.chain.ChainAnalyzer
import com.sadurni.attacca.chain.ChainEngine
import com.sadurni.attacca.chain.ChainTrack
import com.sadurni.attacca.library.AlbumShuffle
import com.sadurni.attacca.library.ListSource
import com.sadurni.attacca.library.SortField
import com.sadurni.attacca.library.TrackSort
import com.sadurni.attacca.library.UserLists
import com.sadurni.attacca.library.Lyrics
import com.sadurni.attacca.library.LyricsLoader
import com.sadurni.attacca.settings.ListSettings
import com.sadurni.attacca.settings.ListSettingsStore
import com.sadurni.attacca.settings.ShuffleMode
import com.sadurni.attacca.settings.SleepChoice
import com.sadurni.attacca.settings.TrimSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.ZonedDateTime
import kotlin.random.Random

/** Sleep timer: either at a moment ([endsAtElapsedMs], [endsAtWallMs]) or at the end of the current track. */
/** Where a sleep timer that waits for an end pauses. */
enum class SleepEnd { TRACK, ALBUM, ARTIST, LIST }

data class SleepTimer(val endsAtElapsedMs: Long?, val endOf: SleepEnd?, val endsAtWallMs: Long? = null)

/** A-B repeat points within the current track (player positions). */
data class AbRepeat(val aMs: Long? = null, val bMs: Long? = null)

/**
 * Playback features beyond the standard player API, living in the service next to the player.
 * The UI (same process) reaches it through [AttaccaApp.extras].
 */
@OptIn(UnstableApi::class)
private const val HEARD_STALE_NS = 2_000_000_000L

private const val EXTEND_COUNT = 10
private const val EXTEND_MIN = 3
private const val EXTEND_FRESH_MS = 86_400_000L

class PlaybackExtras(
    private val app: AttaccaApp,
    private val player: ExoPlayer,
    private val fading: FadingPlayer,
    private val scope: CoroutineScope,
) {
    private val listStore = ListSettingsStore(app)
    private val handler = Handler(Looper.getMainLooper())

    private val _listSettings = MutableStateFlow(ListSettings())
    val listSettings: StateFlow<ListSettings> = _listSettings.asStateFlow()

    /** Global silence settings with the current list's override applied. */
    val effectiveTrim: StateFlow<TrimSettings> =
        combine(app.settings.trim, _listSettings) { global, list ->
            list.trimEnabled?.let { global.copy(enabled = it) } ?: global
        }.stateIn(scope, SharingStarted.Eagerly, app.settings.trim.value)

    private val _sleepTimer = MutableStateFlow<SleepTimer?>(null)
    val sleepTimer: StateFlow<SleepTimer?> = _sleepTimer.asStateFlow()
    private var sleepJob: Job? = null

    private val _abRepeat = MutableStateFlow(AbRepeat())
    val abRepeat: StateFlow<AbRepeat> = _abRepeat.asStateFlow()

    private val _speed = MutableStateFlow(1f)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    /** Pitch shift in semitones, apart from the speed. */
    private val _pitch = MutableStateFlow(0f)
    val pitch: StateFlow<Float> = _pitch.asStateFlow()

    /** The track the sleep timer stops after (end of track, album, artist or list). */
    private val _stopAfter = MutableStateFlow<String?>(null)

    /** Crossfade for the current list: its own length or the global one; none when sped up or pitched. */
    val crossfadeRule: StateFlow<CrossfadeRule> =
        combine(app.settings.playback, _listSettings, _speed, _stopAfter, _pitch) { global, list, speed, stopAfter, pitch ->
            CrossfadeRule(
                lengthMs = if (speed == 1f && pitch == 0f) list.crossfadeMs ?: global.crossfadeMs else 0,
                skipSameAlbum = global.crossfadeSkipSameAlbum,
                style = global.crossfadeStyle,
                custom = global.crossfadeCustom,
                onlyShuffle = global.crossfadeOnlyShuffle,
                noBlendAfter = stopAfter,
            )
        }.stateIn(scope, SharingStarted.Eagerly, CrossfadeRule())

    private val _lyrics = MutableStateFlow<Lyrics?>(null)
    val lyrics: StateFlow<Lyrics?> = _lyrics.asStateFlow()

    /** Set while we change shuffle ourselves, so the listener does not record it twice. */
    private var applyingShuffle = false

    /** List whose items the current shuffle order was built for. */
    private var shuffledForKey: String? = null

    /** Runs only while an A-B loop is set, so normal playback has no polling on the main thread. */
    private val abWatcher = object : Runnable {
        override fun run() {
            val ab = _abRepeat.value
            val a = ab.aMs ?: return
            val b = ab.bMs ?: return
            if (player.currentPosition >= b) player.seekTo(a)
            handler.postDelayed(this, AB_INTERVAL_MS)
        }
    }

    private val listener = object : Player.Listener {
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            if (applyingShuffle) return
            // Toggled from outside (notification, car): plain track shuffle on/off.
            val mode = if (shuffleModeEnabled) ShuffleMode.TRACKS else ShuffleMode.OFF
            if (mode != _listSettings.value.shuffle) updateListSettings { it.copy(shuffle = mode) }
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            if (repeatMode != _listSettings.value.repeatMode) updateListSettings { it.copy(repeatMode = repeatMode) }
            updateStopPoint()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            _abRepeat.value = AbRepeat()
            updateStopPoint()
            extendIfEnding()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) continueWithFolder()
        }

        override fun onTracksChanged(tracks: Tracks) = loadLyrics(tracks)

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            // A new list's items arrive after its source was set: build its shuffle order now.
            // Edits within the same list (removals, silence re-clips) keep the current order.
            val key = currentKey()
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED && key != shuffledForKey && !timeline.isEmpty) {
                shuffledForKey = key
                applyShuffle(_listSettings.value.shuffle)
            }
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) scheduleUserListSave()
            updateStopPoint()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && _sleepTimer.value?.endOf != null) {
                cancelSleepTimer()
            }
        }
    }

    /**
     * The playing track's position as heard right now (ms from its clip start), from the audio
     * output itself; the session's position is only updated now and then and corrects itself
     * backwards. Null when not playing or not known. Main thread.
     */
    fun heardPositionMs(mediaId: String?): Long? {
        if (mediaId == null || !player.isPlaying) return null
        val heard = app.dspEngine.heard ?: return null
        val now = System.nanoTime()
        if (now - heard.atNanos > HEARD_STALE_NS) return null
        val timeline = player.currentTimeline
        val periodIndex = timeline.getIndexOfPeriod(heard.period)
        if (periodIndex == androidx.media3.common.C.INDEX_UNSET) return null
        val windowIndex = timeline.getPeriod(periodIndex, androidx.media3.common.Timeline.Period()).windowIndex
        if (windowIndex != player.currentMediaItemIndex) return null
        val item = player.getMediaItemAt(windowIndex)
        if (item.mediaId != mediaId) return null
        val clipStart = com.sadurni.attacca.library.MediaItems.clipStartMs(item) ?: 0L
        return (heard.fileUsAt(now) / 1000 - clipStart).coerceAtLeast(0L)
    }

    fun start() {
        player.addListener(listener)
        AlbumShuffle.byFolder = app.settings.playback.value.albumsByFolder
        scope.launch {
            // Albums by folder or by tag: a list in album shuffle takes the new grouping at once.
            app.settings.playback.map { it.albumsByFolder }.distinctUntilChanged().collect { byFolder ->
                val changed = AlbumShuffle.byFolder != byFolder
                AlbumShuffle.byFolder = byFolder
                if (changed && _listSettings.value.shuffle == ShuffleMode.ALBUMS) applyShuffle(ShuffleMode.ALBUMS)
            }
        }
        scope.launch {
            _abRepeat.collect {
                handler.removeCallbacks(abWatcher)
                if (it.aMs != null && it.bMs != null) handler.post(abWatcher)
            }
        }
        scope.launch {
            var lastKey: String? = null
            app.currentListSource.collect { source ->
                val key = source?.key ?: ListSettingsStore.NO_SOURCE_KEY
                if (key == lastKey) return@collect
                lastKey = key
                // Another list: the last change was made to the one before.
                if (_undo.value?.listKey != key) _undo.value = null
                val settings = listStore.get(key)
                _listSettings.value = settings
                applyListSettings(settings, reshuffle = false)
            }
        }
    }

    fun stop() {
        player.removeListener(listener)
        handler.removeCallbacksAndMessages(null)
        sleepJob?.cancel()
    }

    // region List settings and shuffle

    fun updateListSettings(transform: (ListSettings) -> ListSettings) {
        val previous = _listSettings.value
        val updated = transform(previous)
        _listSettings.value = updated
        listStore.put(currentKey(), updated)
        applyListSettings(updated, reshuffle = updated.shuffle != previous.shuffle)
    }

    private fun currentKey(): String = app.currentListSource.value?.key ?: ListSettingsStore.NO_SOURCE_KEY

    fun setShuffleMode(mode: ShuffleMode) = updateListSettings { it.copy(shuffle = mode) }

    /** Reorders the list itself by album blocks (permanent, unlike the album shuffle mode). */
    fun shuffleAlbumsInPlace() {
        val count = player.mediaItemCount
        if (count < 2) return
        val ids = List(count) { player.getMediaItemAt(it).mediaId }
        scope.launch {
            // Albums come from the tags; reading them the first time may take a while.
            _sorting.value = true
            val order = try {
                withContext(com.sadurni.attacca.Background.dispatcher) { com.sadurni.attacca.chain.ListOrders.albumsShuffled(ids) }
            } finally {
                _sorting.value = false
            }
            if (order == null || List(player.mediaItemCount) { player.getMediaItemAt(it).mediaId } != ids) return@launch
            val before = snapshot(ListChange.SHUFFLE_ALBUMS)
            applyOrder(order)
            // An in-place reorder makes shuffle modes pointless; play the new order straight,
            // shown by album (or by folder, as the albums were taken).
            updateListSettings { it.copy(shuffle = ShuffleMode.OFF, groupBy = com.sadurni.attacca.chain.ListOrders.albumGrouping()) }
            _undo.value = before
        }
    }

    private val _sorting = MutableStateFlow(false)

    /** True while a sort is reading tags (the first time, on a long list, it takes a while). */
    val sorting: StateFlow<Boolean> = _sorting.asStateFlow()

    /** Sorts the list in place by [field]; reading tags may take a moment on long lists. */
    fun sortQueue(field: SortField, descending: Boolean) {
        val ids = List(player.mediaItemCount) { player.getMediaItemAt(it).mediaId }
        if (ids.size < 2) return
        scope.launch {
            _sorting.value = true
            val order = try {
                withContext(com.sadurni.attacca.Background.dispatcher) { com.sadurni.attacca.chain.ListOrders.sorted(ids, field, descending) }
            } finally {
                _sorting.value = false
            }
            // The list may have changed while tags were read.
            if (order == null || List(player.mediaItemCount) { player.getMediaItemAt(it).mediaId } != ids) return@launch
            val before = snapshot(ListChange.SORT)
            applyOrder(order)
            updateListSettings { it.copy(groupBy = field.takeIf(TrackSort::groups)) }
            _undo.value = before
        }
    }

    private val _chaining = MutableStateFlow<ChainAnalyzer.Progress?>(null)

    /** While the list is being chained: tracks analysed so far out of all. */
    val chaining: StateFlow<ChainAnalyzer.Progress?> = _chaining.asStateFlow()
    private var chainJob: Job? = null

    /**
     * Reorders the list so each track leads into the next, starting with the playing one. Tracks
     * never analysed are analysed first, in the background: the app stays usable meanwhile.
     */
    /** Whether the player holds [count] items and plays [firstId]. Main thread. */
    fun holds(firstId: String, count: Int): Boolean =
        player.mediaItemCount == count && player.currentMediaItem?.mediaId == firstId

    fun chainQueue() {
        val ids = List(player.mediaItemCount) { player.getMediaItemAt(it).mediaId }
        if (ids.size < 2 || chainJob?.isActive == true) return
        val first = player.currentMediaItemIndex.coerceIn(ids.indices)
        chainJob = scope.launch {
            _chaining.value = ChainAnalyzer.Progress(0, ids.size)
            val order = try {
                withContext(com.sadurni.attacca.Background.dispatcher) {
                    val timer = ChainAnalyzer.ProgressTimer()
                    com.sadurni.attacca.chain.ListOrders.chained(app, ids, first) { done -> _chaining.value = timer.of(done, ids.size) }
                }
            } finally {
                _chaining.value = null
            }
            // The list may have changed while it was analysed.
            if (order == null || List(player.mediaItemCount) { player.getMediaItemAt(it).mediaId } != ids) return@launch
            val before = snapshot(ListChange.CHAIN)
            applyOrder(order)
            updateListSettings { it.copy(shuffle = ShuffleMode.OFF, groupBy = null) }
            _undo.value = before
        }
    }

    /**
     * Reorders the list by whole albums, each album's last track leading into the next album's
     * first, starting with the playing album from the playing track (its earlier tracks close the
     * list); albums of the same artist kept apart.
     */
    fun chainAlbumsInPlace() {
        val ids = List(player.mediaItemCount) { player.getMediaItemAt(it).mediaId }
        if (ids.size < 2 || chainJob?.isActive == true) return
        val current = player.currentMediaItemIndex.coerceIn(ids.indices)
        chainJob = scope.launch {
            _chaining.value = ChainAnalyzer.Progress(0, ids.size)
            val order = try {
                withContext(com.sadurni.attacca.Background.dispatcher) { com.sadurni.attacca.chain.ListOrders.albumsChained(app, ids, current) }
            } finally {
                _chaining.value = null
            }
            if (order == null || List(player.mediaItemCount) { player.getMediaItemAt(it).mediaId } != ids) return@launch
            val before = snapshot(ListChange.CHAIN_ALBUMS)
            applyOrder(order)
            updateListSettings { it.copy(shuffle = ShuffleMode.OFF, groupBy = com.sadurni.attacca.chain.ListOrders.albumGrouping()) }
            _undo.value = before
        }
    }

    fun cancelChaining() {
        chainJob?.cancel()
    }

    /**
     * Puts the items in [order] (indices into the current list) without interrupting the playing
     * track: everything but it is removed and added back around it in the new order. Four list
     * operations whatever the length, where moving items one by one would mean thousands.
     */
    private fun applyOrder(order: List<Int>) {
        val count = player.mediaItemCount
        if (order.size != count || count < 2) return
        val items = List(count) { player.getMediaItemAt(it) }
        val current = player.currentMediaItemIndex
        val position = order.indexOf(current)
        if (position < 0) return
        if (current + 1 < count) player.removeMediaItems(current + 1, count)
        if (current > 0) player.removeMediaItems(0, current)
        player.addMediaItems(0, order.subList(0, position).map(items::get))
        player.addMediaItems(order.subList(position + 1, count).map(items::get))
        // Additions land at random places in a shuffle order; rebuild it.
        if (player.shuffleModeEnabled) applyShuffle(_listSettings.value.shuffle)
    }


    // region Undo of list changes

    private val _undo = MutableStateFlow<ListSnapshot?>(null)

    /**
     * The list as it was before the last change made to it as a whole (chain, sort, group, keep
     * only included…), while it can still be brought back: same list open.
     */
    val undo: StateFlow<ListSnapshot?> = _undo.asStateFlow()

    private fun snapshot(change: ListChange): ListSnapshot =
        ListSnapshot(change, currentKey(), List(player.mediaItemCount) { player.getMediaItemAt(it) }, _listSettings.value)

    /** Keeps the list as it is now so [change], about to be made elsewhere, can be undone. Main thread. */
    fun rememberForUndo(change: ListChange) {
        _undo.value = snapshot(change)
    }

    /** Changes only the grouping, undoably. */
    fun setGroupBy(field: SortField?) {
        if (field == _listSettings.value.groupBy) return
        val before = snapshot(ListChange.GROUP)
        updateListSettings { it.copy(groupBy = field) }
        _undo.value = before
    }

    fun forgetUndo() {
        _undo.value = null
    }

    /**
     * Brings the list back as it was before [ListSnapshot.change], its settings too, without
     * interrupting the playing track (it keeps playing at its place in the old order). False when
     * another list is open now.
     */
    fun undoListChange(): Boolean {
        val snapshot = _undo.value ?: return false
        _undo.value = null
        if (snapshot.listKey != currentKey()) return false
        val target = snapshot.items
        val currentId = player.currentMediaItem?.mediaId
        val position = target.indexOfFirst { it.mediaId == currentId }
        val count = player.mediaItemCount
        val current = player.currentMediaItemIndex
        if (position < 0 || count == 0) {
            // The playing track was not in the old list: start that list from its beginning.
            player.setMediaItems(target, 0, 0)
        } else {
            if (current + 1 < count) player.removeMediaItems(current + 1, count)
            if (current > 0) player.removeMediaItems(0, current)
            player.addMediaItems(0, target.subList(0, position))
            player.addMediaItems(target.subList(position + 1, target.size))
        }
        updateListSettings { snapshot.settings }
        if (player.shuffleModeEnabled) applyShuffle(_listSettings.value.shuffle)
        return true
    }

    // endregion

    private fun applyListSettings(settings: ListSettings, reshuffle: Boolean) {
        if (player.repeatMode != settings.repeatMode) player.repeatMode = settings.repeatMode
        if (reshuffle) applyShuffle(settings.shuffle)
        app.dspEngine.replayGainModeOverride = settings.replayGainMode
        // Album gain stays meaningful when whole albums play in order.
        app.dspEngine.shuffleEnabled = settings.shuffle == ShuffleMode.TRACKS
    }

    private var albumShuffleJob: Job? = null

    /** Albums by their tags in random order, starting with the playing album. */
    private fun setAlbumShuffleOrder(ids: List<String>) {
        val order = AlbumShuffle.gatheredOrderStartingWith(ids, player.currentMediaItemIndex)
        player.setShuffleOrder(DefaultShuffleOrder(order, Random.nextLong()))
    }

    private fun applyShuffle(mode: ShuffleMode) {
        applyingShuffle = true
        try {
            when (mode) {
                ShuffleMode.OFF -> player.shuffleModeEnabled = false
                ShuffleMode.TRACKS -> {
                    if (!player.shuffleModeEnabled) {
                        player.setShuffleOrder(DefaultShuffleOrder(player.mediaItemCount, Random.nextLong()))
                    }
                    player.shuffleModeEnabled = true
                }
                ShuffleMode.ALBUMS -> {
                    val ids = List(player.mediaItemCount) { player.getMediaItemAt(it).mediaId }
                    // Right away with the tags known (folders otherwise), again once all are read.
                    setAlbumShuffleOrder(ids)
                    player.shuffleModeEnabled = true
                    albumShuffleJob?.cancel()
                    if (ids.any { TrackSort.cachedTags(it) == null }) {
                        albumShuffleJob = scope.launch {
                            withContext(com.sadurni.attacca.Background.dispatcher) { TrackSort.warm(ids) }
                            val unchanged = List(player.mediaItemCount) { player.getMediaItemAt(it).mediaId } == ids
                            if (unchanged && _listSettings.value.shuffle == ShuffleMode.ALBUMS) {
                                applyingShuffle = true
                                try {
                                    setAlbumShuffleOrder(ids)
                                } finally {
                                    applyingShuffle = false
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            applyingShuffle = false
        }
    }

    // endregion

    // region Saving edits of the user's own lists

    private val saveUserList = Runnable {
        val source = app.currentListSource.value as? ListSource.Playlist ?: return@Runnable
        val file = java.io.File(source.path)
        if (!UserLists.isUserList(file) || player.mediaItemCount == 0) return@Runnable
        // A smart list is made from its rules: what is done to it while playing is not kept.
        if (com.sadurni.attacca.library.SmartRules.rulesFileOf(file).isFile) return@Runnable
        // Tracks added because the list was ending (endless) are not part of it.
        val items = List(player.mediaItemCount) { player.getMediaItemAt(it) }.filterNot(com.sadurni.attacca.library.MediaItems::isExtension)
        scope.launch(Dispatchers.IO) { runCatching { UserLists.save(file, items) } }
    }

    /** Reorders, removals and sorts of a list from Music/Attacca are written back to its file. */
    private fun scheduleUserListSave() {
        handler.removeCallbacks(saveUserList)
        handler.postDelayed(saveUserList, USER_LIST_SAVE_DELAY_MS)
    }

    // endregion

    // region Sleep timer

    fun startSleepTimer(minutes: Int) {
        rememberSleepChoice(SleepChoice.Minutes(minutes))
        startSleepTimerFor(minutes * 60_000L)
    }

    /** Pauses at the next [hour]:[minute] (today, or tomorrow if that time has passed). */
    fun startSleepTimerAt(hour: Int, minute: Int) {
        rememberSleepChoice(SleepChoice.At(hour, minute))
        val now = ZonedDateTime.now()
        var target = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!target.isAfter(now)) target = target.plusDays(1)
        startSleepTimerFor(Duration.between(now, target).toMillis())
    }

    private fun rememberSleepChoice(choice: SleepChoice) {
        app.settings.updatePlayback { it.copy(lastSleep = choice) }
    }

    private fun startSleepTimerFor(durationMs: Long) {
        cancelSleepTimer()
        val endsAt = SystemClock.elapsedRealtime() + durationMs
        _sleepTimer.value = SleepTimer(endsAt, endOf = null, endsAtWallMs = System.currentTimeMillis() + durationMs)
        sleepJob = scope.launch {
            val fadeMs = app.settings.playback.value.sleepFadeMs
            delay((endsAt - SystemClock.elapsedRealtime() - fadeMs).coerceAtLeast(0))
            fading.fadeOutAndPause(fadeMs)
            delay(fadeMs.toLong())
            _sleepTimer.value = null
        }
    }

    /** Pauses at the end of the playing track, its album, its artist or the list. */
    fun sleepAtEndOf(end: SleepEnd) {
        rememberSleepChoice(
            when (end) {
                SleepEnd.TRACK -> SleepChoice.EndOfTrack
                SleepEnd.ALBUM -> SleepChoice.EndOfAlbum
                SleepEnd.ARTIST -> SleepChoice.EndOfArtist
                SleepEnd.LIST -> SleepChoice.EndOfList
            },
        )
        cancelSleepTimer()
        _sleepTimer.value = SleepTimer(null, endOf = end)
        updateStopPoint()
    }

    fun cancelSleepTimer() {
        sleepJob?.cancel()
        sleepJob = null
        player.pauseAtEndOfMediaItems = false
        _sleepTimer.value = null
        _stopAfter.value = null
    }

    /**
     * For a timer waiting for an end: pause at the end of the playing track only when it is the
     * last of its album / artist / the list in play order, and tell the crossfade which track
     * stops (it plays out whole).
     */
    private fun updateStopPoint() {
        val end = _sleepTimer.value?.endOf
        val timeline = player.currentTimeline
        if (end == null || timeline.isEmpty) {
            if (end == null) _stopAfter.value = null
            return
        }
        val shuffle = player.shuffleModeEnabled
        val repeat = if (player.repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_ALL else player.repeatMode
        fun group(index: Int): String {
            val id = player.getMediaItemAt(index).mediaId
            return when (end) {
                SleepEnd.TRACK -> index.toString()
                SleepEnd.ALBUM -> AlbumShuffle.tagAlbumKey(id)
                SleepEnd.ARTIST -> TrackSort.cachedTags(id)?.let { it.albumArtist.ifBlank { it.artist } }?.lowercase().orEmpty()
                SleepEnd.LIST -> ""
            }
        }
        val first = timeline.getFirstWindowIndex(shuffle)
        fun isLast(index: Int): Boolean {
            val next = timeline.getNextWindowIndex(index, repeat, shuffle)
            return next == C.INDEX_UNSET || next == first || group(next) != group(index)
        }
        var last = player.currentMediaItemIndex
        var steps = 0
        while (!isLast(last) && steps++ < player.mediaItemCount) {
            last = timeline.getNextWindowIndex(last, repeat, shuffle)
        }
        player.pauseAtEndOfMediaItems = last == player.currentMediaItemIndex
        _stopAfter.value = player.getMediaItemAt(last).mediaId
    }

    private var extendJob: Job? = null

    /**
     * Endless lists: when the last track of the list starts, similar tracks chained from it are
     * added after it (not saved into the list's file), so playback goes on. Not in shuffle or with
     * repeat, nor for a folder that goes on with other folders.
     */
    private fun extendIfEnding() {
        val endless = _listSettings.value.endless ?: app.settings.playback.value.endless
        if (!endless || extendJob?.isActive == true) return
        if (player.shuffleModeEnabled || player.repeatMode != Player.REPEAT_MODE_OFF) return
        if (app.currentListSource.value is com.sadurni.attacca.library.ListSource.Folder &&
            app.settings.playback.value.folderChange != com.sadurni.attacca.settings.FolderChange.OFF
        ) return
        val timeline = player.currentTimeline
        if (timeline.isEmpty || timeline.getNextWindowIndex(player.currentMediaItemIndex, Player.REPEAT_MODE_OFF, false) != C.INDEX_UNSET) return
        val seed = player.currentMediaItem?.mediaId?.takeIf { !it.contains("://") } ?: return
        val inList = List(player.mediaItemCount) { player.getMediaItemAt(it).mediaId }.toHashSet()
        extendJob = scope.launch {
            val added = withContext(com.sadurni.attacca.Background.dispatcher) {
                val collection = runCatching { app.chainLists.collection(extra = seed) }.getOrNull() ?: return@withContext emptyList()
                val g = collection.generator
                val index = g.indexOf(seed).takeIf { it >= 0 } ?: return@withContext emptyList()
                // Not what the list has, nor what was heard today.
                val excluded = inList + app.listening.playedSince(System.currentTimeMillis() - EXTEND_FRESH_MS)
                val filters = com.sadurni.attacca.chain.ChainFilters(
                    excluded = excluded,
                    inclusion = app.settings.chain.value.takeIf { it.listsOnlyIncluded }?.inclusion(),
                )
                val spacing = app.settings.chain.value.artistSpacing
                // Same mood and sound family first; wider when that leaves too few.
                val tries = listOf(true to true, false to true, false to false)
                tries.firstNotNullOfOrNull { (mood, family) ->
                    g.fromSeed(index, filters, mood, family, EXTEND_COUNT + 1, spacing)?.drop(1)?.takeIf { it.size >= EXTEND_MIN }
                }.orEmpty()
            }
            if (added.isEmpty() || player.currentMediaItem?.mediaId != seed) return@launch
            val items = withContext(com.sadurni.attacca.Background.dispatcher) {
                com.sadurni.attacca.library.MediaItems.withTags(com.sadurni.attacca.library.MediaItems.fromFiles(added.map { java.io.File(it) }))
                    .map(com.sadurni.attacca.library.MediaItems::asExtension)
            }
            player.addMediaItems(items)
            android.util.Log.d("Endless", "added ${items.size} tracks chained from ${java.io.File(seed).name}")
        }
    }

    /** A folder list ended: on with the next folder (or a random one) of the music folder, as set. */
    private fun continueWithFolder() {
        val change = app.settings.playback.value.folderChange
        val source = app.currentListSource.value as? com.sadurni.attacca.library.ListSource.Folder ?: return
        val root = app.settings.musicFolder ?: return
        if (change == com.sadurni.attacca.settings.FolderChange.OFF || player.repeatMode != Player.REPEAT_MODE_OFF) return
        scope.launch {
            val next = withContext(com.sadurni.attacca.Background.dispatcher) {
                val tracks = com.sadurni.attacca.library.AudioFiles.collectAudio(java.io.File(root), recent = true).map { it.absolutePath }
                val folders = com.sadurni.attacca.library.FolderOrder.folders(root, tracks)
                    .filter { !source.recursive || !it.startsWith(source.path.trimEnd('/') + "/") }
                when (change) {
                    com.sadurni.attacca.settings.FolderChange.NEXT -> com.sadurni.attacca.library.FolderOrder.next(folders, source.path)
                    else -> folders.filter { it != source.path.trimEnd('/') }.randomOrNull()
                }?.let { folder ->
                    val list = com.sadurni.attacca.library.ListSource.Folder(folder, recursive = false)
                    list to com.sadurni.attacca.library.MediaItems.withTags(com.sadurni.attacca.library.ListLoader.load(list))
                }
            } ?: return@launch
            val (list, items) = next
            if (items.isEmpty() || player.playbackState != Player.STATE_ENDED) return@launch
            app.setCurrentList(list.name, list)
            player.setMediaItems(items)
            player.prepare()
            player.play()
        }
    }

    /** Plays the files [ids] in this order as a new unsaved list named [name] (from the service: a shake). */
    fun playNewList(name: String, ids: List<String>) {
        scope.launch {
            val items = withContext(com.sadurni.attacca.Background.dispatcher) {
                com.sadurni.attacca.library.MediaItems.withTags(com.sadurni.attacca.library.MediaItems.fromFiles(ids.map { java.io.File(it) }))
            }
            if (items.isEmpty()) return@launch
            app.ungroupUnsavedList()
            app.setCurrentList(name, null)
            player.setMediaItems(items)
            player.prepare()
            player.play()
        }
    }

    // endregion

    // region A-B repeat and speed

    /** First call sets A, second sets B (and starts looping), third clears. */
    fun toggleAbRepeat() {
        val ab = _abRepeat.value
        val position = player.currentPosition
        _abRepeat.value = when {
            ab.aMs == null -> AbRepeat(aMs = position)
            ab.bMs == null && position > ab.aMs + MIN_AB_MS -> ab.copy(bMs = position)
            else -> AbRepeat()
        }
    }

    fun setSpeed(speed: Float) {
        player.playbackParameters = androidx.media3.common.PlaybackParameters(speed, semitonesToPitch(_pitch.value))
        _speed.value = speed
    }

    /** Shifts the pitch by [semitones] without changing the speed. */
    fun setPitch(semitones: Float) {
        player.playbackParameters = androidx.media3.common.PlaybackParameters(_speed.value, semitonesToPitch(semitones))
        _pitch.value = semitones
    }

    private fun semitonesToPitch(semitones: Float): Float = Math.pow(2.0, semitones / 12.0).toFloat()

    // endregion

    private fun loadLyrics(tracks: Tracks) {
        val mediaId = player.currentMediaItem?.mediaId ?: run {
            _lyrics.value = null
            return
        }
        val metadata = ArrayList<Metadata>()
        for (group in tracks.groups) {
            for (i in 0 until group.length) group.getTrackFormat(i).metadata?.let(metadata::add)
        }
        scope.launch {
            val lyrics = withContext(Dispatchers.IO) { LyricsLoader.load(mediaId, metadata) }
            if (player.currentMediaItem?.mediaId == mediaId) _lyrics.value = lyrics
        }
    }

    private companion object {
        const val USER_LIST_SAVE_DELAY_MS = 1_500L
        const val AB_INTERVAL_MS = 50L
        const val MIN_AB_MS = 500L
    }
}

/** A change made to the current list as a whole, which can be undone (see [PlaybackExtras.undo]). */
enum class ListChange { CHAIN, CHAIN_ALBUMS, SORT, SHUFFLE_ALBUMS, GROUP, KEEP_INCLUDED, REMOVE, MOVE, ADD }

/** The current list before a [change]: its items and settings, and which list it was. */
class ListSnapshot(
    val change: ListChange,
    val listKey: String,
    val items: List<MediaItem>,
    val settings: ListSettings,
) {
    /** When the change was made (elapsed realtime): its message shows only right after it. */
    val at: Long = SystemClock.elapsedRealtime()
}
