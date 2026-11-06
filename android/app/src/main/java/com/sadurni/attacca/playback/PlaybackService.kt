package com.sadurni.attacca.playback

import com.google.common.collect.ImmutableList
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.LibraryResult
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.Context
import android.content.ComponentName
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import android.os.Bundle
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.sadurni.attacca.R
import com.sadurni.attacca.analysis.AnalysisManager
import com.sadurni.attacca.app
import com.sadurni.attacca.library.ListLoader
import com.sadurni.attacca.library.ListSource
import com.sadurni.attacca.library.MediaItems
import com.sadurni.attacca.settings.OutputDevice
import com.sadurni.attacca.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Playback service. A [MediaLibraryService] rather than a plain session service so the system can
 * offer playback resumption: "play" from headphones or a car with the app closed restarts it, and
 * [MediaLibrarySession.Callback.onPlaybackResumption] decides what to play.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {

    private var session: MediaLibrarySession? = null
    private lateinit var player: ExoPlayer
    private lateinit var analysis: AnalysisManager
    private lateinit var tails: CrossfadeTails
    private lateinit var listeningTracker: ListeningTracker
    private lateinit var focusKeeper: AudioFocusKeeper
    private lateinit var shake: ShakeDetector
    private var closing = false

    /** Set when the user closed everything; a restart by the system then stays closed. */
    private var closedByUser: Boolean
        get() = getSharedPreferences("service", MODE_PRIVATE).getBoolean("closed_by_user", false)
        set(value) = getSharedPreferences("service", MODE_PRIVATE).edit().putBoolean("closed_by_user", value).commit().let { }
    private lateinit var fading: FadingPlayer
    private lateinit var extras: PlaybackExtras
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())

    /** Tracks that could not be played one after another (see onPlayerError), so a list of only missing files ends. */
    private var failedInARow = 0

    /** A track's storage is being waited for (see onPlayerError). */
    private var waitingStorage = false

    /** The list the player's items belong to (resume points are saved under it). */
    private var itemsSource: ListSource? = null

    /**
     * The current list changed and its items have not reached the player yet: until they do,
     * the player still holds the previous list and nothing is saved.
     */
    private var awaitingItems = false
    private var pendingSource: ListSource? = null

    /** A device's list is being loaded on connection; its rule must not change meanwhile. */
    private var switchingList = false

    /** When the current output device became current. */
    private var deviceSince = 0L

    private val periodicSave = object : Runnable {
        override fun run() {
            saveQueue()
            handler.postDelayed(this, SAVE_INTERVAL_MS)
        }
    }

    /** Keeps other apps' effects off Attacca's audio session (see [SystemEffects]). */
    private val effectGuard = SessionEffectGuard()

    override fun onCreate() {
        super.onCreate()
        // No library scan here: the system also creates this service by itself (at boot, for the
        // resume card, when a device connects) and then freezes the process, which left the scan's
        // notification stuck. It starts with the app or once something plays.
        val engine = app.dspEngine
        com.sadurni.attacca.dsp.TransitionLog.file = java.io.File(com.sadurni.attacca.library.UserLists.directory, "attacca-transitions.txt")

        player = ExoPlayer.Builder(this, AttaccaRenderersFactory(this, engine) { app.settings.audioBufferMs })
            .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(this, RepeatSkippingExtractorsFactory()))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                // Audio focus as set by the user (AudioFocusKeeper), not the player's own.
                /* handleAudioFocus= */ false,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .experimentalSetDynamicSchedulingEnabled(app.settings.playback.value.efficientScheduling)
            .build()

        if (app.settings.playback.value.guardEffects) {
            effectGuard.attach(player.audioSessionId)
            player.addListener(object : Player.Listener {
                override fun onAudioSessionIdChanged(audioSessionId: Int) = effectGuard.attach(audioSessionId)
            })
        }

        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioUnderrun(
                eventTime: AnalyticsListener.EventTime,
                bufferSize: Int,
                bufferSizeMs: Long,
                elapsedSinceLastFeedMs: Long,
            ) {
                if (!player.isPlaying) return
                // The output also runs dry at the very end of the list; that is no dropout.
                if (!player.hasNextMediaItem() && player.duration - player.currentPosition < END_OF_LIST_MARGIN_MS) return
                android.util.Log.w(TAG, "dropout: $bufferSizeMs ms buffer, fed $elapsedSinceLastFeedMs ms ago")
                com.sadurni.attacca.dsp.TransitionLog.note("DROPOUT  at ${player.currentPosition / 1000} s of “${com.sadurni.attacca.dsp.TransitionLog.nameOf(player.currentMediaItem?.mediaId)}”: $bufferSizeMs ms buffer, fed $elapsedSinceLastFeedMs ms ago")
                app.settings.recordDropout()
            }
        })

        player.addListener(object : Player.Listener {
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = saveQueue()

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                engine.isPlaying = isPlaying
                updateShake()
                // Playing keeps the service in the foreground, so a scan can run without being frozen.
                if (isPlaying) app.scheduleStartupScan()
                if (isPlaying) app.backUpIfDue()
                NowPlayingWidget.updatePlaying(this@PlaybackService, isPlaying)
                if (isPlaying) closedByUser = false
                handler.removeCallbacks(periodicSave)
                if (isPlaying) handler.postDelayed(periodicSave, SAVE_INTERVAL_MS) else saveQueue()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                saveQueue()
                refreshFavorite()
            }

            override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) = updateWidget()

            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                if (reason != Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) return
                if (awaitingItems || pendingSource != null) {
                    // The new list's items have arrived.
                    awaitingItems = false
                    handler.removeCallbacks(stopAwaitingItems)
                    itemsSource = pendingSource
                    pendingSource = null
                }
                saveQueue()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) saveQueue()
                if (playbackState == Player.STATE_READY) failedInARow = 0
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                // A file that is missing or cannot be read (a list from another device, a deleted
                // file): on to the next one rather than stopping. Output errors are not the file's.
                android.util.Log.w(TAG, "cannot play ${player.currentMediaItem?.mediaId}: ${error.errorCodeName}")
                if (error.errorCode !in SKIPPABLE_ERRORS) return
                // Its storage is not there (yet): wait for it rather than skip the whole list.
                val id = player.currentMediaItem?.mediaId
                if (id != null && com.sadurni.attacca.library.AudioFiles.onAbsentStorage(id)) {
                    if (waitingStorage) return
                    waitingStorage = true
                    val play = player.playWhenReady
                    scope.launch {
                        com.sadurni.attacca.library.AudioFiles.awaitStorage(listOf(id))
                        waitingStorage = false
                        if (player.currentMediaItem?.mediaId == id && player.playerError != null) {
                            player.prepare()
                            player.playWhenReady = play
                        }
                    }
                    return
                }
                if (!player.hasNextMediaItem() || failedInARow >= player.mediaItemCount) {
                    failedInARow = 0
                    return
                }
                failedInARow++
                val play = player.playWhenReady
                player.seekToNextMediaItem()
                player.prepare()
                player.playWhenReady = play
            }
        })

        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            player.addAnalyticsListener(androidx.media3.exoplayer.util.EventLogger())
        }

        fading = FadingPlayer(
            player,
            fadeMs = { app.settings.playback.value.fadeMs },
            rewind = { app.settings.playback.value.let { it.rewindAfterPauseS to it.rewindSeconds } },
            pauseStore = getSharedPreferences("service", MODE_PRIVATE),
            // The blend's audio is decoded at normal speed and pitch: none when changed.
            skipCrossfade = SkipCrossfade(player, engine, scope) {
                if (player.playbackParameters.speed == 1f && player.playbackParameters.pitch == 1f) app.settings.playback.value.skipCrossfadeMs else 0
            },
        )
        extras = PlaybackExtras(app, player, fading, scope)
        extras.start()
        focusKeeper = AudioFocusKeeper(this, player, { app.settings.playback.value.focusDuck }, { app.settings.playback.value.focusResume })
        focusKeeper.start()
        app.extras.value = extras
        // Shaking the phone while music plays starts a random list.
        shake = ShakeDetector(this) {
            com.sadurni.attacca.chain.RandomList.request(app, player.currentMediaItem?.mediaId, extras::playNewList)
        }
        scope.launch { app.settings.chain.collect { updateShake() } }

        analysis = AnalysisManager(player, app.analysisDb, extras.effectiveTrim, extras.crossfadeRule, scope, app.analysisResults, engine)
        analysis.start()
        tails = CrossfadeTails(player, app.dspEngine, scope)
        tails.start()
        listeningTracker = ListeningTracker(player, handler, { recordListen(it, played = true) }, { recordListen(it, played = false) })
        listeningTracker.start()

        app.beforeListChange = ::onListChanging
        app.onListAdopted = { source ->
            // Only once the items are the list's own; a list change in progress decides itself.
            if (!awaitingItems) {
                itemsSource = source
                saveQueue()
            }
        }
        app.onListDropped = { source ->
            // The deleted list must not come back as the device's list or with a resume point.
            if (itemsSource?.key == source.key) {
                itemsSource = null
                saveQueue()
            }
        }
        restoreQueue()

        scope.launch {
            app.outputMonitor.connected.collect(::onDeviceConnected)
        }
        scope.launch {
            var lastKey: String? = null
            app.outputMonitor.current.collect { device ->
                if (device?.key != lastKey) deviceSince = SystemClock.elapsedRealtime()
                lastKey = device?.key
            }
        }
        scope.launch { app.shutdownRequests.collect { closeCompletely() } }

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val closeButton = CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setCustomIconResId(R.drawable.ic_close)
            .setDisplayName(getString(R.string.action_close))
            .setSessionCommand(CLOSE_COMMAND)
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build()
        this.closeButton = closeButton
        session = MediaLibrarySession.Builder(this, fading, SessionCallback())
            .setSessionActivity(openApp)
            .setMediaButtonPreferences(mediaButtons())
            .build()
        // The favorite button shows whether the track is one; the buttons follow the setting.
        refreshFavorite()
        scope.launch { app.favoritesChanged.collect { refreshFavorite() } }
        scope.launch {
            app.settings.playback.collect { if (it.carButtons != shownCarButtons) updateMediaButtons() }
        }

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply {
                setSmallIcon(R.drawable.ic_notification)
            },
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    /** A track counted as played or skipped: into the statistics and, if wanted, the listening log. */
    private fun recordListen(listen: Listen, played: Boolean) {
        if (listen.mediaId.contains("://")) return
        val log = app.settings.playback.value.listenLog
        // The app's scope: a write started while closing must still finish.
        app.appScope.launch(com.sadurni.attacca.Background.dispatcher) {
            runCatching {
                if (played) app.listening.recordPlay(listen.mediaId, listen.startedAtMs) else app.listening.recordSkip(listen.mediaId, System.currentTimeMillis())
            }.onFailure { android.util.Log.w(TAG, "listening statistics", it) }
            if (log) {
                runCatching { com.sadurni.attacca.library.ListenLog.append(listen, played, com.sadurni.attacca.BuildConfig.VERSION_NAME) }
                    .onFailure { android.util.Log.w(TAG, "listening log", it) }
            }
        }
    }

    /** The home-screen widget: the playing track's title, artist and cover. */
    private fun updateWidget() {
        val item = player.currentMediaItem ?: return
        val metadata = player.mediaMetadata
        val title = MediaItems.displayTitle(item, metadata)
        val subtitle = listOfNotNull(metadata.artist, metadata.albumTitle).map { it.toString() }.filter { it.isNotEmpty() }.joinToString(" · ")
        val playing = player.isPlaying
        val data = metadata.artworkData
        val file = metadata.artworkUri?.takeIf { it.scheme == "file" }?.path?.let { java.io.File(it) }
        scope.launch(com.sadurni.attacca.Background.dispatcher) {
            val art = com.sadurni.attacca.ui.theme.ArtworkLoader.decode(data, file, maxSize = WIDGET_ART_PX)
            NowPlayingWidget.update(this@PlaybackService, title, subtitle, playing, art)
        }
    }

    /** Listens for a shake only while music plays and shaking is on. */
    private fun updateShake() {
        if (!::shake.isInitialized) return
        val chain = app.settings.chain.value
        if (chain.randomShake && player.isPlaying) shake.start(chain.shakeKind, chain.shakeStrength) else shake.stop()
    }

    override fun onDestroy() {
        NowPlayingWidget.updatePlaying(this, false)
        effectGuard.release()
        if (::focusKeeper.isInitialized) focusKeeper.stop()
        if (::shake.isInitialized) shake.stop()
        handler.removeCallbacks(periodicSave)
        handler.removeCallbacks(stopAwaitingItems)
        // When closing, all this already happened before the player was released.
        if (!closing) {
            saveQueue()
            listeningTracker.stop()
            analysis.stop()
            tails.stop()
            extras.stop()
        }
        app.extras.value = null
        app.beforeListChange = null
        app.onListAdopted = null
        app.onListDropped = null
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
        if (closing) {
            getSystemService(android.app.NotificationManager::class.java).cancelAll()
            // Give the activity a moment to finish, then end the process.
            Handler(Looper.getMainLooper()).postDelayed({
                android.os.Process.killProcess(android.os.Process.myPid())
            }, CLOSE_DELAY_MS)
        }
    }

    /**
     * Opening the player: the list the current output device continues (the phone speaker when
     * nothing is connected), if it has one; otherwise the saved queue. Not played automatically.
     */
    private fun restoreQueue() {
        val saved = app.queueStore.load()?.takeIf { it.locations.isNotEmpty() }
        // Right after the phone restarts the system may start the player before the memory card
        // with the music is mounted: lists loaded then had no covers and would not play until the
        // app was killed. Wait for it first (the music folder and the saved track).
        val needed = listOfNotNull(app.settings.musicFolder?.let { "$it/." }, saved?.locations?.getOrNull(saved.index))
        if (needed.any(com.sadurni.attacca.library.AudioFiles::onAbsentStorage)) {
            scope.launch {
                val waited = com.sadurni.attacca.library.AudioFiles.awaitStorage(needed)
                com.sadurni.attacca.ExitLog.note(this@PlaybackService, "music storage not ready at start: waited ${waited / 1000} s")
                if (player.mediaItemCount == 0) restoreQueue(saved)
            }
            return
        }
        restoreQueue(saved)
    }

    private fun restoreQueue(found: SavedQueue?) {
        app.outputMonitor.refresh()
        var saved = found
        // Lists deleted while something still referred to them: those references go now.
        val rule = app.settings.profiles.state.value.rule(app.outputMonitor.current.value?.key)
        if (listOfNotNull(rule?.resumeList, saved?.source).distinctBy { it.key }.count(::forgetIfDeleted) > 0) {
            saved = app.queueStore.load()
        }
        val deviceList = app.settings.profiles.state.value.rule(app.outputMonitor.current.value?.key)?.resumeList
        if (deviceList != null && deviceList.key != saved?.source?.key) {
            app.setCurrentList(deviceList.name, deviceList)
            scope.launch {
                val loaded = loadWithResumePoint(deviceList)
                // A controller may have started something else meanwhile.
                if (player.mediaItemCount > 0 || app.currentListSource.value?.key != deviceList.key) return@launch
                if (loaded == null) {
                    // The device's list is gone or empty: keep the saved queue.
                    saved?.let(::restoreSaved)
                    return@launch
                }
                player.setMediaItems(loaded.mediaItems, loaded.startIndex, loaded.startPositionMs)
                player.prepare()
            }
            return
        }
        restoreSaved(saved ?: return)
    }

    private fun restoreSaved(saved: SavedQueue) {
        app.setCurrentList(saved.listName, saved.source)
        scope.launch {
            val items = withContext(Dispatchers.IO) { analysis.withCachedTrims(saved.toMediaItems()) }
            // A controller may have started something else meanwhile.
            if (player.mediaItemCount > 0) return@launch
            // Shuffle and repeat come from the list's own settings (PlaybackExtras).
            player.setMediaItems(items, saved.index.coerceIn(0, saved.locations.lastIndex), saved.positionMs)
            player.prepare()
        }
    }

    private fun saveQueue() {
        // Between a list change and its items reaching the player, the player's items do not
        // belong to the current list.
        if (awaitingItems) return
        runCatching {
            app.queueStore.save(player, app.currentListName.value, app.currentListSource.value)
            saveResumePoint()
            rememberListForDevice()
        }
    }

    /** Where the playing list was left: track and position, or its start once finished. */
    private fun saveResumePoint() {
        val source = itemsSource ?: return
        val item = player.currentMediaItem ?: return
        val point = if (player.playbackState == Player.STATE_ENDED) {
            ResumePoint(player.getMediaItemAt(0).mediaId, 0, 0L)
        } else {
            ResumePoint(item.mediaId, player.currentMediaItemIndex, player.currentPosition)
        }
        app.resumeStore.put(source, point)
    }

    /**
     * Called synchronously before the current list changes, before the new items are sent to
     * the player: the outgoing list is saved at its exact track and position.
     */
    private fun onListChanging(next: ListSource?) {
        if (!awaitingItems && player.mediaItemCount > 0) {
            runCatching { saveResumePoint() }
        }
        awaitingItems = true
        pendingSource = next
        handler.removeCallbacks(stopAwaitingItems)
        handler.postDelayed(stopAwaitingItems, AWAIT_ITEMS_MS)
    }

    /** Safety net if the new items never come: saving resumes, without a list to resume. */
    private val stopAwaitingItems = Runnable {
        if (awaitingItems) {
            awaitingItems = false
            itemsSource = null
        }
    }

    /**
     * The list to continue on a device follows the last list played on it (when the device's
     * rule allows it), the phone speaker included. A device that just became current waits a
     * moment, so its own list can be loaded first on connection.
     */
    private fun rememberListForDevice() {
        if (!player.isPlaying || switchingList) return
        val source = itemsSource ?: return
        val device = app.outputMonitor.current.value ?: return
        if (SystemClock.elapsedRealtime() - deviceSince < DEVICE_SETTLE_MS) return
        val profiles = app.settings.profiles
        val rule = profiles.state.value.rule(device.key) ?: return
        if (!rule.followLastList || rule.resumeList?.key == source.key) return
        if (source is ListSource.Playlist && !java.io.File(source.path).isFile) return
        profiles.setResumeList(device.key, source, rule.autoPlay)
    }

    /** A device with a "continue this list" rule was connected while the app is running. */
    private fun onDeviceConnected(device: OutputDevice) {
        val rule = app.settings.profiles.state.value.rule(device.key) ?: return
        val list = rule.resumeList ?: return
        switchingList = true
        scope.launch {
            try {
                continueList(list, rule.autoPlay)
            } finally {
                switchingList = false
            }
        }
    }

    private suspend fun continueList(source: ListSource, play: Boolean) {
        if (app.currentListSource.value?.key != source.key) {
            saveQueue()
            val loaded = loadWithResumePoint(source) ?: return
            app.setCurrentList(source.name, source)
            player.setMediaItems(loaded.mediaItems, loaded.startIndex, loaded.startPositionMs)
            player.prepare()
        }
        if (play) player.play()
    }

    /**
     * A saved list whose file is gone (deleted before deleting dropped what referred to it, or
     * from another app): every reference to it is dropped, the saved queue keeps its tracks as an
     * unsaved list. Only with the list's folder there, so a memory card not mounted yet is not
     * taken for a deletion. True when it was dropped.
     */
    private fun forgetIfDeleted(source: ListSource): Boolean {
        val file = (source as? ListSource.Playlist)?.let { java.io.File(it.path) } ?: return false
        if (file.exists() || file.parentFile?.isDirectory != true) return false
        app.forgetList(source)
        return true
    }

    private suspend fun loadWithResumePoint(source: ListSource): MediaItemsWithStartPosition? {
        val items = withContext(Dispatchers.IO) { analysis.withCachedTrims(ListLoader.load(source)) }
        if (items.isEmpty()) {
            forgetIfDeleted(source)
            return null
        }
        val point = app.resumeStore.get(source)
        val index = app.resumeStore.startIndex(point, items.map { it.mediaId })
        val position = if (point != null && items[index].mediaId == point.mediaId) point.positionMs else 0L
        return MediaItemsWithStartPosition(items, index, position)
    }

    private lateinit var closeButton: CommandButton

    /** Whether the playing track is in the favorites (for the favorite button). */
    private var favorite = false

    /**
     * The phone's media controls show only two extra buttons: favorite (with the setting on) and
     * close, which must stay in sight to close the app from there; the group buttons are for the car.
     */
    private fun phoneButtons(): List<CommandButton> =
        if (app.settings.playback.value.carButtons) listOf(favoriteButton(), closeButton) else listOf(closeButton)

    private fun favoriteButton(): CommandButton =
        CommandButton.Builder(if (favorite) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
            .setDisplayName(getString(R.string.action_favorite))
            .setSessionCommand(FAVORITE_COMMAND)
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build()

    /**
     * The extra buttons of the session for the car and other controllers: previous/next group and
     * favorite when the setting is on, and always the close button.
     */
    private fun mediaButtons(): List<CommandButton> {
        if (!app.settings.playback.value.carButtons) return listOf(closeButton)
        val previousGroup = CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setCustomIconResId(R.drawable.ic_group_previous)
            .setDisplayName(getString(R.string.action_previous_group))
            .setSessionCommand(PREVIOUS_GROUP_COMMAND)
            .setSlots(CommandButton.SLOT_BACK_SECONDARY, CommandButton.SLOT_OVERFLOW)
            .build()
        val nextGroup = CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setCustomIconResId(R.drawable.ic_group_next)
            .setDisplayName(getString(R.string.action_next_group))
            .setSessionCommand(NEXT_GROUP_COMMAND)
            .setSlots(CommandButton.SLOT_FORWARD_SECONDARY, CommandButton.SLOT_OVERFLOW)
            .build()
        return listOf(previousGroup, nextGroup, favoriteButton(), closeButton)
    }

    /** The setting the buttons were last made with. */
    private var shownCarButtons: Boolean? = null

    private fun updateMediaButtons() {
        shownCarButtons = app.settings.playback.value.carButtons
        val s = session ?: return
        s.setMediaButtonPreferences(mediaButtons())
        s.mediaNotificationControllerInfo?.let { s.setMediaButtonPreferences(it, phoneButtons()) }
    }

    /** Reads whether the playing track is a favorite, then shows it on the button. */
    private fun refreshFavorite() {
        val mediaId = player.currentMediaItem?.mediaId
        scope.launch {
            val isFavorite = mediaId != null &&
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.sadurni.attacca.library.UserLists.contains(com.sadurni.attacca.library.UserLists.favorites, mediaId)
                }
            if (isFavorite != favorite || mediaId == player.currentMediaItem?.mediaId) {
                favorite = isFavorite
                updateMediaButtons()
            }
        }
    }

    private fun toggleFavorite() {
        val item = player.currentMediaItem ?: return
        val wasFavorite = favorite
        favorite = !wasFavorite
        updateMediaButtons()
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            if (wasFavorite) {
                com.sadurni.attacca.library.UserLists.remove(com.sadurni.attacca.library.UserLists.favorites, item.mediaId)
            } else {
                com.sadurni.attacca.library.UserLists.append(com.sadurni.attacca.library.UserLists.favorites, listOf(item))
            }
            app.favoritesChanged.tryEmit(Unit)
        }
    }

    /**
     * Previous/next group from the car: the groups (the list's grouping, else the folder) are read
     * off the main thread, then the player moves.
     */
    private fun skipGroup(forward: Boolean) {
        val ids = List(player.mediaItemCount) { player.getMediaItemAt(it).mediaId }
        if (ids.isEmpty()) return
        val field = extras.listSettings.value.groupBy
        scope.launch {
            val groups = kotlinx.coroutines.withContext(com.sadurni.attacca.Background.dispatcher) {
                if (field != null) {
                    com.sadurni.attacca.library.TrackSort.warm(ids)
                    ids.map { com.sadurni.attacca.library.TrackSort.groupKey(it, field) }
                } else {
                    ids.map(com.sadurni.attacca.library.AlbumShuffle::albumKey)
                }
            }
            if (player.mediaItemCount != groups.size) return@launch
            val target = if (forward) {
                GroupSkip.next(player) { groups[it] }
            } else {
                GroupSkip.previous(player, { groups[it] }, GROUP_RESTART_MS)
            }
            target?.let { fading.seekTo(it, 0) }
        }
    }

    /**
     * The notification's close button: saves the queue, stops playback, closes the app's screens
     * and ends the process, so nothing of Attacca stays running.
     */
    private fun closeCompletely() {
        handler.removeCallbacks(periodicSave)
        saveQueue()
        // Without the media button receiver the system offers no resume card either; it comes
        // back when the app is opened or plays again.
        setResumptionEnabled(this, false)
        closedByUser = true
        player.pause()
        listeningTracker.stop()
        app.requestClose()
        closing = true
        // With the session gone, Media3 has nothing to show and cannot bring the service back
        // to the foreground while it stops.
        analysis.stop()
        tails.stop()
        extras.stop()
        session?.run {
            player.release()
            release()
        }
        session = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        // The process ends in onDestroy: killed while the service still runs, the system would
        // treat it as a crash and restart the service, notification included.
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A restart by the system after the user closed the app: stay closed.
        if (intent == null && closedByUser) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private inner class SessionCallback : MediaLibrarySession.Callback {

        private val car by lazy { CarLibrary(app) }

        // A car (Android Auto) or another media browser: the lists and the music folder.
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(LibraryResult.ofItem(car.root(), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            fun paged(all: List<MediaItem>): LibraryResult<ImmutableList<MediaItem>> {
                val from = (page.toLong() * pageSize).coerceAtMost(all.size.toLong()).toInt()
                val to = (from.toLong() + pageSize).coerceAtMost(all.size.toLong()).toInt()
                return LibraryResult.ofItemList(ImmutableList.copyOf(all.subList(from, to)), params)
            }
            // The current list is read from the player, on this thread; the rest reads files.
            if (parentId == CarLibrary.ROOT || parentId == "queue") {
                return Futures.immediateFuture(paged(car.children(parentId, player)))
            }
            return scope.future(Dispatchers.IO) { paged(car.children(parentId, null)) }
        }

        /** A list, folder or track chosen in the car: its whole list, from it. */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaItemsWithStartPosition> {
            val id = mediaItems.singleOrNull()?.mediaId
            if (id == null || !CarLibrary.isOurs(id)) {
                return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
            }
            val queue = id.startsWith("Q|")
            return scope.future {
                val choice = if (queue) {
                    car.choose(id, player)
                } else {
                    withContext(Dispatchers.IO) { car.choose(id, null) }
                } ?: throw UnsupportedOperationException("Nothing to play for $id")
                val items = withContext(Dispatchers.IO) { analysis.withCachedTrims(choice.items) }
                // A list change, as from the app: the outgoing list keeps its place.
                if (!queue) app.setCurrentList(choice.name, choice.source)
                val point = choice.source?.takeIf { !id.startsWith("T|") && !queue }?.let { app.resumeStore.get(it) }
                val position = if (point != null && items.getOrNull(choice.startIndex)?.mediaId == point.mediaId) point.positionMs else 0L
                MediaItemsWithStartPosition(items, choice.startIndex, position)
            }
        }

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult =
            MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                        .add(CLOSE_COMMAND)
                        .add(PREVIOUS_GROUP_COMMAND)
                        .add(NEXT_GROUP_COMMAND)
                        .add(FAVORITE_COMMAND)
                        .build(),
                )
                .apply { if (session.isMediaNotificationController(controller)) setMediaButtonPreferences(phoneButtons()) }
                .build()

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                ACTION_CLOSE -> closeCompletely()
                ACTION_PREVIOUS_GROUP -> skipGroup(forward = false)
                ACTION_NEXT_GROUP -> skipGroup(forward = true)
                ACTION_FAVORITE -> toggleFavorite()
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        /** Items from controllers lose their URI; rebuild them from the media ID (the path). */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> =
            scope.future(Dispatchers.IO) {
                val rebuilt = mediaItems.map { item ->
                    if (item.localConfiguration != null) {
                        item
                    } else {
                        MediaItems.fromLocation(
                            item.mediaId,
                            item.mediaMetadata.extras?.getString(MediaItems.EXTRA_PLAYLIST_TITLE),
                            MediaItems.explicitClipStartMs(item),
                            MediaItems.explicitClipEndMs(item),
                        )
                    }
                }
                analysis.withCachedTrims(rebuilt).toMutableList()
            }

        /**
         * "Play" from a headset or car with the app closed: the list assigned to the connected
         * device if it has one, otherwise the saved queue.
         */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaItemsWithStartPosition> {
            app.outputMonitor.refresh()
            val device = app.outputMonitor.current.value
            val deviceList = app.settings.profiles.state.value.rule(device?.key)?.resumeList
            return scope.future {
                // The device's list, unless it is gone or empty: then the saved queue.
                deviceList?.let { list ->
                    loadWithResumePoint(list)?.let { loaded ->
                        app.setCurrentList(list.name, list)
                        return@future loaded
                    }
                }
                val saved = app.queueStore.load() ?: throw UnsupportedOperationException("Nothing to resume")
                app.setCurrentList(saved.listName, saved.source)
                val items = withContext(Dispatchers.IO) { analysis.withCachedTrims(saved.toMediaItems()) }
                if (items.isEmpty()) throw UnsupportedOperationException("Nothing to resume")
                MediaItemsWithStartPosition(items, saved.index.coerceIn(0, items.lastIndex), saved.positionMs)
            }
        }
    }

    internal companion object {
        const val SAVE_INTERVAL_MS = 10_000L
        const val WIDGET_ART_PX = 300
        const val END_OF_LIST_MARGIN_MS = 3_000L

        /** Errors of the file itself (reading, its container, its decoder), skipped over. */
        val SKIPPABLE_ERRORS = 2000..4999
        const val TAG = "Playback"
        const val CLOSE_DELAY_MS = 400L

        /** Longer than routing takes to settle after a device connects. */
        const val DEVICE_SETTLE_MS = 5_000L
        const val AWAIT_ITEMS_MS = 20_000L

        const val ACTION_CLOSE = "com.sadurni.attacca.CLOSE"
        val CLOSE_COMMAND = SessionCommand(ACTION_CLOSE, Bundle.EMPTY)
        const val ACTION_PREVIOUS_GROUP = "com.sadurni.attacca.PREVIOUS_GROUP"
        val PREVIOUS_GROUP_COMMAND = SessionCommand(ACTION_PREVIOUS_GROUP, Bundle.EMPTY)
        const val ACTION_NEXT_GROUP = "com.sadurni.attacca.NEXT_GROUP"
        val NEXT_GROUP_COMMAND = SessionCommand(ACTION_NEXT_GROUP, Bundle.EMPTY)
        const val ACTION_FAVORITE = "com.sadurni.attacca.FAVORITE"
        val FAVORITE_COMMAND = SessionCommand(ACTION_FAVORITE, Bundle.EMPTY)

        /** As the app's button: within this of a group's start, "previous" goes to the group before. */
        const val GROUP_RESTART_MS = 3_000L
    }
}

/**
 * Playback resumption (headset buttons, the system's resume card) depends on the media button
 * receiver being enabled when the session is created: the system checks it as soon as playback
 * starts, so it follows the "resume when closed" setting from app start.
 */
fun setResumptionEnabled(context: Context, enabled: Boolean) {
    val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_DEFAULT else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    val component = ComponentName(context, androidx.media3.session.MediaButtonReceiver::class.java)
    if (context.packageManager.getComponentEnabledSetting(component) == state) return
    context.packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
}

