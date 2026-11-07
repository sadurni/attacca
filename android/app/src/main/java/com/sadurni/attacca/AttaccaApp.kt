package com.sadurni.attacca

import com.sadurni.attacca.playback.DeviceVolume
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import android.app.Application
import com.sadurni.attacca.analysis.TrackAnalysis
import com.sadurni.attacca.dsp.DspEngine
import com.sadurni.attacca.library.Bookmarks
import com.sadurni.attacca.library.ListHistory
import com.sadurni.attacca.library.ListSource
import com.sadurni.attacca.playback.OutputDeviceMonitor
import com.sadurni.attacca.playback.PlaybackExtras
import com.sadurni.attacca.playback.QueueStore
import com.sadurni.attacca.playback.ResumeStore
import com.sadurni.attacca.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Process-wide singletons shared by the UI and the playback service. */
class AttaccaApp : Application() {

    lateinit var settings: AppSettings
        private set
    lateinit var dspEngine: DspEngine
        private set
    lateinit var queueStore: QueueStore
        private set
    lateinit var resumeStore: ResumeStore
        private set
    lateinit var outputMonitor: OutputDeviceMonitor
        private set
    lateinit var listHistory: ListHistory
        private set
    lateinit var bookmarks: Bookmarks
    lateinit var libraryScanner: com.sadurni.attacca.library.LibraryScanner
    lateinit var inclusionStore: com.sadurni.attacca.library.InclusionStore
        private set
    lateinit var chainAnalyzer: com.sadurni.attacca.chain.ChainAnalyzer
        private set
    lateinit var chainLists: com.sadurni.attacca.chain.ChainLists
        private set
    private lateinit var workNotifications: WorkNotifications

    /** Set by the playback service while it runs. */
    val extras = MutableStateFlow<PlaybackExtras?>(null)

    /** The app's screen is in view (between the activity's onStart and onStop). */
    @Volatile var uiVisible = false

    /** A random list waits for its mood to be chosen (see [com.sadurni.attacca.chain.RandomList]); the activity asks. */
    val randomMoodRequest = MutableStateFlow(false)

    /** Name of the list being played ("Plst: …" in the player). */
    val currentListName = MutableStateFlow("")

    /** Where the current list came from; null for single files opened from other apps. */
    val currentListSource = MutableStateFlow<ListSource?>(null)

    /** Analysis results by file path, published by the playback service for the waveform. */
    val analysisResults = MutableStateFlow<Map<String, TrackAnalysis>>(emptyMap())

    /** Silences, waveforms and loudness (`analysis.db`): one connection for playback and the folder passes. */
    val analysisDb: com.sadurni.attacca.analysis.AnalysisDb by lazy { com.sadurni.attacca.analysis.AnalysisDb(this) }

    /** The music folder as artists, albums, genres… (see [com.sadurni.attacca.library.MusicIndex]). */
    val music: com.sadurni.attacca.library.MusicIndex by lazy {
        com.sadurni.attacca.library.MusicIndex(this, { com.sadurni.attacca.library.TrackSort.db }, { settings.musicFolder })
    }

    /** Lists made from rules (see [com.sadurni.attacca.library.SmartRules]). */
    val smartLists: com.sadurni.attacca.library.SmartLists by lazy { com.sadurni.attacca.library.SmartLists(this) }

    /** Plays and skips of every track (`stats.db`). */
    val listening: com.sadurni.attacca.library.ListeningStats by lazy { com.sadurni.attacca.library.ListeningStats(this) }

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Makes the automatic backup if it is due (see [com.sadurni.attacca.settings.AutoBackup]). */
    fun backUpIfDue() {
        appScope.launch(Background.dispatcher) { com.sadurni.attacca.settings.AutoBackup.runIfDue(this@AttaccaApp) }
    }

    override fun onCreate() {
        super.onCreate()
        // Why it closed, when nobody closed it: for the user to send (Music/Attacca/attacca-exits.txt).
        ExitLog.watchFailures(this)
        // The sound network's mood classes, read from its weights when moods are first needed.
        com.sadurni.attacca.chain.MoodHead.source = { assets.open("sound/soundnet.bin") }
        Thread { runCatching { ExitLog.recordPastExits(this) } }.start()
        // Before anything opens the settings or the databases.
        com.sadurni.attacca.settings.Backup.applyPending(this)
        settings = AppSettings(this)
        // Before the playback service creates its session, which reads this.
        com.sadurni.attacca.playback.setResumptionEnabled(this, settings.playback.value.resumeWhenClosed)
        val profiles = settings.profiles
        dspEngine = DspEngine(profiles.active.value.settings)
        queueStore = QueueStore(this)
        resumeStore = ResumeStore(this)
        outputMonitor = OutputDeviceMonitor(this)
        listHistory = ListHistory(this)
        com.sadurni.attacca.library.IgnoredPaths.load(this)
        com.sadurni.attacca.settings.AutoBackup.load(this)
        bookmarks = Bookmarks(this)
        com.sadurni.attacca.library.TrackSort.db = com.sadurni.attacca.library.TagDb(this)
        appScope.launch(Background.dispatcher) {
            runCatching { com.sadurni.attacca.library.DamagedTracks.load() }
            // Marks left by stricter rules are checked again.
            runCatching { com.sadurni.attacca.library.DamageCheck.recheckMarked() }
        }
        com.sadurni.attacca.library.M3uParser.musicFolder = settings.musicFolder?.let { java.io.File(it) }
        libraryScanner = com.sadurni.attacca.library.LibraryScanner(this, appScope)
        chainAnalyzer = com.sadurni.attacca.chain.ChainAnalyzer(
            this,
            appScope,
            edgeSeconds = { settings.chain.value.edgeSeconds },
            threads = { settings.chain.value.analysisThreads },
            analysisDb = { analysisDb },
            silenceThreshold = { settings.trim.value.thresholdDb },
            measureSilences = { settings.chain.value.measureSilences },
            playing = { dspEngine.isPlaying },
        )
        chainLists = com.sadurni.attacca.chain.ChainLists(this, appScope)
        inclusionStore = com.sadurni.attacca.library.InclusionStore(settings, appScope).also { it.start() }
        workNotifications = WorkNotifications(this, appScope).also { it.start() }
        appScope.launch {
            // With background analysis on, each finished scan is followed by a pass that
            // describes the new tracks for chaining (known ones cost a database lookup).
            // The first value is the previous scan; the one started above follows.
            libraryScanner.last.drop(1).collect { result ->
                // Folders, albums and artists of the inclusion rules may have new tracks.
                if (result != null) runCatching { inclusionStore.refresh() }
                // Smart lists take in what the scan found (and drop what is gone).
                if (result != null) runCatching { smartLists.updateAll() }
                // Lists from a track find the collection ready.
                if (result != null) chainLists.warm()
                val folder = settings.musicFolder
                if (result != null && folder != null && settings.chain.value.autoAnalyze) {
                    chainAnalyzer.analyzeLibrary(java.io.File(folder))
                }
            }
        }

        appScope.launch {
            // Each analysis pass changes the collection lists from a track are made from.
            chainAnalyzer.last.drop(1).collect { if (it != null) chainLists.warm() }
        }
        appScope.launch {
            outputMonitor.current.collect { profiles.setCurrentDevice(it) }
        }
        appScope.launch {
            // Each device's fixed volume, when it becomes the output. Bluetooth devices bring
            // back their own volume a moment after connecting, so it is set again shortly after.
            outputMonitor.current
                .map { it?.key }
                .distinctUntilChanged()
                .collectLatest { key ->
                    val rule = profiles.state.value.rule(key) ?: return@collectLatest
                    // The device's inclusion set (with whom the music is heard there).
                    rule.inclusionSet?.let { set ->
                        val id = set.takeIf { it != com.sadurni.attacca.settings.DeviceRule.EVERYTHING && settings.chain.value.inclusionSets.any { s -> s.id == it } }
                        if (settings.chain.value.activeSet != id) com.sadurni.attacca.settings.InclusionSets.select(settings, id)
                    }
                    fun apply() {
                        rule.volumePercent?.let { DeviceVolume.set(this@AttaccaApp, it) }
                        rule.notificationPercent?.let { DeviceVolume.set(this@AttaccaApp, it, android.media.AudioManager.STREAM_NOTIFICATION) }
                    }
                    if (rule.volumePercent == null && rule.notificationPercent == null) return@collectLatest
                    apply()
                    kotlinx.coroutines.delay(DeviceVolume.REAPPLY_DELAY_MS)
                    apply()
                }
        }
        appScope.launch {
            // Fade only when the profile itself changes (device switch), not on every edit.
            var lastProfileId: String? = null
            var lastOff = false
            kotlinx.coroutines.flow.combine(profiles.active, effectsOff) { profile, off -> profile to off }.collect { (profile, off) ->
                val changed = (lastProfileId != null && lastProfileId != profile.id) || off != lastOff
                dspEngine.applySettings(if (off) profile.settings.withoutEffects() else profile.settings, fade = changed)
                lastProfileId = profile.id
                lastOff = off
            }
        }
        outputMonitor.start()
        // Into memory early, so lists and screens that use it never wait.
        appScope.launch(Background.dispatcher) { runCatching { listening.load() } }
    }

    /**
     * Every effect of the profile off for a moment, to hear the music without them (the Sound
     * button's long press); not kept when the app closes, so they are never left off by mistake.
     */
    val effectsOff = kotlinx.coroutines.flow.MutableStateFlow(false)

    private val _closeRequests = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emitted when the user closes Attacca from the notification; screens finish themselves. */
    val closeRequests: kotlinx.coroutines.flow.SharedFlow<Unit> = _closeRequests

    /** The favorites list changed (from the app, the car or the notification). */
    val favoritesChanged = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private val _shutdownRequests = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Asks the playback service to close everything, as the notification's close button does. */
    val shutdownRequests: kotlinx.coroutines.flow.SharedFlow<Unit> = _shutdownRequests

    /** Ends the app completely: through the service when it runs, otherwise directly. */
    fun shutdown() {
        if (extras.value != null) {
            _shutdownRequests.tryEmit(Unit)
        } else {
            requestClose()
            workNotifications.cancelAll()
            android.os.Handler(mainLooper).postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 400)
        }
    }

    fun requestClose() {
        _closeRequests.tryEmit(Unit)
    }

    /**
     * Set by the playback service: called right before the current list changes, while the
     * player still holds the outgoing list, so its exact resume point is saved.
     */
    var beforeListChange: ((ListSource?) -> Unit)? = null

    private var startupScanScheduled = false

    /**
     * Picks up new and changed files of the music folder, once per process, when the app is used
     * (its screens open or the player starts): a process woken only to look at a device that
     * connected must not scan, Android freezes it in the background and the scan's notification
     * would stay stuck. Not right away: playing (a device connected) comes first.
     */
    fun scheduleStartupScan() {
        if (startupScanScheduled) return
        startupScanScheduled = true
        appScope.launch {
            kotlinx.coroutines.delay(STARTUP_SCAN_DELAY_MS)
            settings.musicFolder?.let { libraryScanner.scan(java.io.File(it)) }
        }
    }

    /**
     * A new unsaved list (from a track, from Music…) starts ungrouped: unsaved lists share their
     * list settings, and the grouping one of them had (by album) must not carry over. Call before
     * [setCurrentList]; lists made of albums group again once loaded.
     */
    fun ungroupUnsavedList() {
        val store = com.sadurni.attacca.settings.ListSettingsStore(this)
        val key = com.sadurni.attacca.settings.ListSettingsStore.NO_SOURCE_KEY
        val stored = store.get(key)
        if (stored.groupBy != null) store.put(key, stored.copy(groupBy = null))
        // Still on an unsaved list: no change of list will reload its settings, so change them live.
        if (currentListSource.value == null) {
            extras.value?.let { e -> if (e.listSettings.value.groupBy != null) e.updateListSettings { it.copy(groupBy = null) } }
        }
    }

    fun setCurrentList(name: String, source: ListSource?) {
        beforeListChange?.invoke(source)
        currentListName.value = name
        currentListSource.value = source
        source?.let(listHistory::touch)
        (source as? ListSource.Playlist)?.let { listHistory.rememberPlaylist(java.io.File(it.path)) }
    }

    /** Set by the playback service: the playing items now belong to this list (see [adoptCurrentList]). */
    var onListAdopted: ((ListSource) -> Unit)? = null

    /**
     * The playing list, unsaved or opened from elsewhere, was saved as one of the user's lists
     * ([saved]): it becomes that list where it is (same track and position, same list settings),
     * and the unsaved or opened original is no longer listed.
     */
    /**
     * Renames a saved list's file to [name]; everything kept for it follows: its list settings,
     * where it was left, the devices that continue it, its place in Lists and among the recent
     * lists, the inclusion rules next to it, and the playing list if it is this one. Returns the
     * new file, or null when the name is taken or the file could not be renamed.
     */
    fun renameList(file: java.io.File, name: String): java.io.File? {
        val target = com.sadurni.attacca.library.UserLists.renamedFile(file, name)
        if (target.absolutePath == file.absolutePath) return target
        // Only a change of case on a case-insensitive storage may find the "same" file.
        if (target.exists() && !target.name.equals(file.name, ignoreCase = true)) return null
        if (!file.renameTo(target)) return null
        com.sadurni.attacca.library.InclusionRules.rulesFileOf(file).takeIf { it.isFile }?.renameTo(com.sadurni.attacca.library.InclusionRules.rulesFileOf(target))
        val old = ListSource.Playlist(file.absolutePath)
        val renamed = ListSource.Playlist(target.absolutePath)
        val listSettings = com.sadurni.attacca.settings.ListSettingsStore(this)
        listSettings.put(renamed.key, listSettings.get(old.key))
        resumeStore.get(old)?.let { resumeStore.put(renamed, it) }
        val profiles = settings.profiles
        for (device in profiles.state.value.devices) {
            if (device.resumeList?.key == old.key) profiles.setResumeList(device.key, renamed, device.autoPlay)
        }
        listHistory.rename(old, renamed)
        if (file.absolutePath in listHistory.playlists().map { it.absolutePath }) {
            listHistory.forgetPlaylist(file)
            listHistory.rememberPlaylist(target)
        }
        com.sadurni.attacca.library.ListOrder(this).rename(file, target)
        if (settings.chain.value.inclusionList == file.absolutePath) {
            settings.updateChain { it.copy(inclusionList = target.absolutePath) }
        }
        if (currentListSource.value?.key == old.key) {
            currentListName.value = renamed.name
            currentListSource.value = renamed
            onListAdopted?.invoke(renamed)
        }
        // With the player closed, the queue it reopens with.
        if (onListAdopted == null) queueStore.renameSource(old, renamed)
        return target
    }

    /**
     * Deletes a saved list's file and everything kept for it: its list settings, where it was
     * left, the devices that continue it (they continue nothing now), its place among the recent
     * lists, the inclusion rules next to it, and the inclusion setting if it is the inclusion list.
     * The playing list, if it is this one, plays on as an unsaved list. False when the file could
     * not be deleted.
     */
    fun deleteList(file: java.io.File): Boolean {
        if (file.exists() && !file.delete()) return false
        com.sadurni.attacca.library.InclusionRules.rulesFileOf(file).takeIf { it.isFile }?.delete()
        forgetList(ListSource.Playlist(file.absolutePath))
        if (settings.chain.value.inclusionList == file.absolutePath) {
            settings.updateChain { it.copy(inclusionList = null) }
        }
        return true
    }

    /**
     * Drops every reference to [old], a list that no longer exists, also those made through
     * another path to the same file (/sdcard and /storage/emulated/0 are one folder).
     */
    fun forgetList(old: ListSource) {
        val known = listOf(old) + listHistory.all() + listOfNotNull(currentListSource.value, queueStore.load()?.source) +
            settings.profiles.state.value.devices.mapNotNull { it.resumeList }
        known.filter { sameList(it, old) }.distinctBy { it.key }.forEach(::forgetSource)
    }

    private fun sameList(a: ListSource, b: ListSource): Boolean {
        if (a.key == b.key) return true
        if (a !is ListSource.Playlist || b !is ListSource.Playlist) return false
        val canonical = { path: String -> runCatching { java.io.File(path).canonicalPath }.getOrDefault(path) }
        return canonical(a.path) == canonical(b.path)
    }

    private fun forgetSource(old: ListSource) {
        com.sadurni.attacca.settings.ListSettingsStore(this).remove(old.key)
        resumeStore.remove(old)
        val profiles = settings.profiles
        for (device in profiles.state.value.devices) {
            if (device.resumeList?.key == old.key) profiles.setResumeList(device.key, null, false)
        }
        listHistory.remove(old)
        (old as? ListSource.Playlist)?.let { listHistory.forgetPlaylist(java.io.File(it.path)) }
        if (currentListSource.value?.key == old.key) currentListSource.value = null
        onListDropped?.invoke(old)
        // The queue the player reopens with keeps its tracks, as an unsaved list.
        queueStore.forgetSource(old)
    }

    /** Set by the playback service: a list was deleted, the playing items no longer belong to it. */
    var onListDropped: ((ListSource) -> Unit)? = null

    fun adoptCurrentList(saved: ListSource.Playlist) {
        val old = currentListSource.value
        if (old?.key == saved.key) return
        val listSettings = com.sadurni.attacca.settings.ListSettingsStore(this)
        listSettings.put(saved.key, listSettings.get(old?.key ?: com.sadurni.attacca.settings.ListSettingsStore.NO_SOURCE_KEY))
        // Devices that continue the original continue the saved list.
        val profiles = settings.profiles
        for (device in profiles.state.value.devices) {
            if (old != null && device.resumeList?.key == old.key) profiles.setResumeList(device.key, saved, device.autoPlay)
        }
        if (old != null) {
            listHistory.remove(old)
            (old as? ListSource.Playlist)?.let { listHistory.forgetPlaylist(java.io.File(it.path)) }
        }
        currentListName.value = saved.name
        currentListSource.value = saved
        listHistory.touch(saved)
        onListAdopted?.invoke(saved)
    }
}

private const val STARTUP_SCAN_DELAY_MS = 20_000L

val android.content.Context.app: AttaccaApp
    get() = applicationContext as AttaccaApp
