package com.sadurni.attacca.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, SYSTEM_DARK, WHITE, DARK, BLACK }

enum class WaveformMode { TOP_HALF, MIRRORED, OFF }

/** When the screen is kept on while music plays. */
enum class KeepScreenOn { OFF, PLAYER, ALWAYS }

/** Skipping of leading/trailing silence (phase 2). */
data class TrimSettings(
    val enabled: Boolean = true,
    val trimStart: Boolean = true,
    val trimEnd: Boolean = true,
    /** Anything quieter than this counts as silence. */
    val thresholdDb: Float = -60f,
    /** Shorter silences are left alone. */
    val minSilenceMs: Long = 500,
    /** Silence kept before the first / after the last audible sound. */
    val marginMs: Long = 100,
    val respectGaplessAlbums: Boolean = true,
)

data class UiSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Accent color taken from the album art. */
    val dynamicAccent: Boolean = true,
    /** Blurred album art behind the player. */
    val blurredBackground: Boolean = false,
    /** Blurred album art behind the other screens too (over their own pictures). */
    val coverBehindScreens: Boolean = false,
    val showRemainingTime: Boolean = true,
    val waveformMode: WaveformMode = WaveformMode.TOP_HALF,
    /** A strip of O'rama in the waveform's place in Now playing, the waveform (as [waveformMode] says) then small between the times. */
    val oramaStrip: Boolean = false,
    val keepScreenOn: KeepScreenOn = KeepScreenOn.OFF,
    /** Tips over each screen the first time it opens after installing a new version. */
    val showTips: Boolean = true,
    /** The user's theme in use (see [ThemeStore]), or null for [themeMode]'s built-in one. */
    val themeId: String? = null,
    /** While music plays, Attacca (Now playing, O'rama…) shows over the lock screen when it was open as the screen went off. */
    val overLockScreen: Boolean = false,
    /** The lock screen's picture from the track playing (its cover, or O'rama's landscape), or left alone. */
    val lockWallpaper: com.sadurni.attacca.playback.LockWallpaper.Mode = com.sadurni.attacca.playback.LockWallpaper.Mode.OFF,
    /** What the home-screen widget shows when made big: the cover or O'rama's landscape. */
    val widgetBig: com.sadurni.attacca.playback.NowPlayingWidget.Big = com.sadurni.attacca.playback.NowPlayingWidget.Big.ORAMA,
)

/** Ordering lists so each track leads into the next (see [com.sadurni.attacca.chain.ChainEngine]). */
/** Longest "not played lately" for quick and random lists, in days. */
const val MAX_NOT_PLAYED_DAYS = 60

/** The movement that counts as shaking the phone (see [com.sadurni.attacca.playback.ShakeDetector]). */
enum class ShakeKind { SIDEWAYS, BACK_AND_FORTH, ANY }

data class ChainSettings(
    /** Analyse the whole music folder in the background after each library scan. */
    val autoAnalyze: Boolean = false,
    /** How many following tracks avoid repeating an artist. */
    val artistSpacing: Int = 4,
    /** Seconds at each end of a track that are compared; changing it means analysing again. */
    val edgeSeconds: Int = com.sadurni.attacca.chain.EdgeFeatures.DEFAULT_EDGE_SECONDS,
    /** Files analysed at the same time (1 to one per core). */
    val analysisThreads: Int = DEFAULT_ANALYSIS_THREADS,
    /** The pass over the music folder also measures silences and loudness of tracks still without them. */
    val measureSilences: Boolean = true,
    /** The screen stays on while the music folder is scanned or analysed with the app open (much faster). */
    val keepScreenOn: Boolean = false,
    /** Lists made from tracks (and generated lists) are saved among the user's lists when they play. */
    val saveMadeLists: Boolean = false,
    /** Quick and random lists leave out tracks played in the last this many days; 0: none left out. */
    val quickNotPlayedDays: Int = 0,
    /**
     * Lists Attacca makes (generated, from a track, quick, random, other kinds, endless) take only
     * the tracks the inclusion list allows: the starting choice, which the dialogs can change.
     */
    val listsOnlyIncluded: Boolean = false,
    /** Tracks found damaged stay out of the lists Attacca makes. */
    val skipDamaged: Boolean = true,
    /** M3U8 list deciding which tracks may go into generated lists ("only included"). */
    val inclusionList: String? = null,
    val inclusionMode: com.sadurni.attacca.library.InclusionMode = com.sadurni.attacca.library.InclusionMode.ALLOW,
    /**
     * The inclusion lists kept by name (see [com.sadurni.attacca.library.InclusionSet]); the one
     * in use ([activeSet]) is the one [inclusionList] and [inclusionMode] describe.
     */
    val inclusionSets: List<com.sadurni.attacca.library.InclusionSet> = emptyList(),
    /** The set in use; null: none (everything may be used). */
    val activeSet: String? = null,
    /** A random list asks the mood first (only with the screen on and the app in view). */
    val randomAskMood: Boolean = true,
    /** Mood of the last random list (null: any); used when it is not asked. */
    val randomMood: com.sadurni.attacca.chain.Mood? = null,
    /** Shaking the phone starts a random list. */
    val randomShake: Boolean = false,
    /** How hard the phone must be shaken: 1 light, 2 medium, 3 strong. */
    val shakeStrength: Int = 2,
    /** Which movement is a shake. */
    val shakeKind: ShakeKind = ShakeKind.SIDEWAYS,
) {
    /** The configured inclusion, or null without a list. */
    fun inclusion(): com.sadurni.attacca.library.Inclusion? = com.sadurni.attacca.library.Inclusion.of(inclusionList, inclusionMode)

    companion object {
        /** Half the cores: fast, while leaving room for playback and the screen. */
        val DEFAULT_ANALYSIS_THREADS: Int = (com.sadurni.attacca.Background.maxAnalysisThreads / 2).coerceAtLeast(1)
    }
}

/** App settings backed by SharedPreferences, exposed as StateFlows. */
class AppSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** DSP profiles and per-device rules; the active profile drives the sound chain. */
    val profiles = ProfileStore(prefs, legacySettings = ::readDsp)

    private val _trim = MutableStateFlow(readTrim())
    val trim: StateFlow<TrimSettings> = _trim.asStateFlow()

    private val _playback = MutableStateFlow(
        PlaybackSettings(
            fadeMs = prefs.getInt("fade_ms", PlaybackSettings().fadeMs),
            sleepFadeMs = prefs.getInt("sleep_fade_ms", PlaybackSettings().sleepFadeMs),
            crossfadeMs = prefs.getInt("crossfade_ms", PlaybackSettings().crossfadeMs),
            crossfadeSkipSameAlbum = prefs.getBoolean("crossfade_skip_album", PlaybackSettings().crossfadeSkipSameAlbum),
            crossfadeStyle = prefs.getString("crossfade_style", null)
                ?.let { name -> com.sadurni.attacca.dsp.CrossfadeStyle.entries.firstOrNull { it.name == name } }
                ?: PlaybackSettings().crossfadeStyle,
            crossfadeCustom = prefs.getString("crossfade_custom", null)
                ?.split(',')?.mapNotNull { it.toFloatOrNull() }?.toFloatArray()
                ?.let(com.sadurni.attacca.dsp.CrossfadeShape::decode)
                ?: PlaybackSettings().crossfadeCustom,
            crossfadeOnlyShuffle = prefs.getBoolean("crossfade_only_shuffle", PlaybackSettings().crossfadeOnlyShuffle),
            skipCrossfadeMs = prefs.getInt("skip_crossfade_ms", PlaybackSettings().skipCrossfadeMs),
            resumeWhenClosed = prefs.getBoolean("resume_when_closed", PlaybackSettings().resumeWhenClosed),
            lastSleep = SleepChoice.decode(prefs.getString("sleep_last", null)) ?: PlaybackSettings().lastSleep,
            audioBufferMs = prefs.getInt("audio_buffer_ms", PlaybackSettings().audioBufferMs),
            efficientScheduling = prefs.getBoolean("efficient_scheduling", PlaybackSettings().efficientScheduling),
            guardEffects = prefs.getBoolean("guard_effects", PlaybackSettings().guardEffects),
            rewindAfterPauseS = prefs.getInt("rewind_after_pause_s", PlaybackSettings().rewindAfterPauseS),
            rewindSeconds = prefs.getInt("rewind_s", PlaybackSettings().rewindSeconds),
            carButtons = prefs.getBoolean("car_buttons", PlaybackSettings().carButtons),
            albumsByFolder = prefs.getBoolean("albums_by_folder", PlaybackSettings().albumsByFolder),
            listenLog = prefs.getBoolean("listen_log", PlaybackSettings().listenLog),
            folderChange = prefs.getString("folder_change", null)
                ?.let { name -> FolderChange.entries.firstOrNull { it.name == name } } ?: PlaybackSettings().folderChange,
            focusDuck = prefs.getString("focus_duck", null)
                ?.let { name -> FocusDuck.entries.firstOrNull { it.name == name } } ?: PlaybackSettings().focusDuck,
            focusResume = prefs.getBoolean("focus_resume", PlaybackSettings().focusResume),
            toEndSeconds = prefs.getInt("to_end_s", PlaybackSettings().toEndSeconds),
            endless = prefs.getBoolean("endless", PlaybackSettings().endless),
        ),
    )
    val playback: StateFlow<PlaybackSettings> = _playback.asStateFlow()

    fun updatePlayback(transform: (PlaybackSettings) -> PlaybackSettings) {
        val updated = transform(_playback.value)
        _playback.value = updated
        prefs.edit()
            .putInt("fade_ms", updated.fadeMs)
            .putInt("sleep_fade_ms", updated.sleepFadeMs)
            .putInt("crossfade_ms", updated.crossfadeMs)
            .putBoolean("crossfade_skip_album", updated.crossfadeSkipSameAlbum)
            .putString("crossfade_style", updated.crossfadeStyle.name)
            .putString("crossfade_custom", updated.crossfadeCustom.encode().joinToString(",") { it.toString() })
            .putBoolean("crossfade_only_shuffle", updated.crossfadeOnlyShuffle)
            .putInt("skip_crossfade_ms", updated.skipCrossfadeMs)
            .putString("sleep_last", updated.lastSleep.encode())
            .putBoolean("resume_when_closed", updated.resumeWhenClosed)
            .putInt("audio_buffer_ms", updated.audioBufferMs)
            .putBoolean("efficient_scheduling", updated.efficientScheduling)
            .putBoolean("guard_effects", updated.guardEffects)
            .putInt("rewind_after_pause_s", updated.rewindAfterPauseS)
            .putInt("rewind_s", updated.rewindSeconds)
            .putBoolean("car_buttons", updated.carButtons)
            .putBoolean("albums_by_folder", updated.albumsByFolder)
            .putBoolean("listen_log", updated.listenLog)
            .putString("folder_change", updated.folderChange.name)
            .putString("focus_duck", updated.focusDuck.name)
            .putBoolean("focus_resume", updated.focusResume)
            .putInt("to_end_s", updated.toEndSeconds)
            .putBoolean("endless", updated.endless)
            .apply()
    }

    private val _audioTuning = MutableStateFlow(
        AudioTuning(
            learnedBufferMs = prefs.getInt("audio_learned_buffer_ms", AudioTuning().learnedBufferMs),
            dropouts = prefs.getInt("audio_dropouts", 0),
        ),
    )
    val audioTuning: StateFlow<AudioTuning> = _audioTuning.asStateFlow()

    /** Output buffer length the next audio track is created with. Any thread. */
    val audioBufferMs: Int get() = _audioTuning.value.bufferMs(_playback.value)

    /** The output ran dry while playing: count it and, in automatic mode, grow the buffer. */
    fun recordDropout() {
        val auto = _playback.value.audioBufferMs == PlaybackSettings.AUTO_BUFFER
        val current = _audioTuning.value
        saveAudioTuning(if (auto) current.afterDropout() else current.copy(dropouts = current.dropouts + 1))
    }

    /** Back to the starting buffer with no dropouts counted. */
    fun resetAudioTuning() = saveAudioTuning(AudioTuning())

    private fun saveAudioTuning(tuning: AudioTuning) {
        _audioTuning.value = tuning
        prefs.edit()
            .putInt("audio_learned_buffer_ms", tuning.learnedBufferMs)
            .putInt("audio_dropouts", tuning.dropouts)
            .apply()
    }

    private val _gestures = MutableStateFlow(
        CoverGesture.entries.associateWith { gesture ->
            prefs.getString("gesture_${gesture.name}", null)
                ?.let { name -> CoverAction.entries.firstOrNull { it.name == name } }
                ?: CoverGestures.DEFAULTS[gesture] ?: CoverAction.NONE
        },
    )

    /** Action assigned to each gesture on the album cover. */
    val gestures: StateFlow<Map<CoverGesture, CoverAction>> = _gestures.asStateFlow()

    fun setGesture(gesture: CoverGesture, action: CoverAction) {
        _gestures.value = _gestures.value + (gesture to action)
        prefs.edit().putString("gesture_${gesture.name}", action.name).apply()
    }

    private val _rowGestures = MutableStateFlow(
        RowSwipe.entries.associateWith { swipe ->
            prefs.getString("row_gesture_${swipe.name}", null)
                ?.let { name -> RowAction.entries.firstOrNull { it.name == name } }
                ?: RowGestures.DEFAULTS[swipe] ?: RowAction.NONE
        },
    )

    /** Action of each sideways swipe on list rows. */
    val rowGestures: StateFlow<Map<RowSwipe, RowAction>> = _rowGestures.asStateFlow()

    fun setRowGesture(swipe: RowSwipe, action: RowAction) {
        _rowGestures.value = _rowGestures.value + (swipe to action)
        prefs.edit().putString("row_gesture_${swipe.name}", action.name).apply()
    }

    private val _chainTaste = MutableStateFlow(
        com.sadurni.attacca.chain.ChainTaste.decode(prefs.getString("chain_taste", null))
            .also { com.sadurni.attacca.chain.EdgeFeatures.taste = it },
    )

    /** How much chaining weighs each part of a link (the chaining guide's result); in use at once. */
    val chainTaste: StateFlow<com.sadurni.attacca.chain.ChainTaste> = _chainTaste.asStateFlow()

    fun setChainTaste(taste: com.sadurni.attacca.chain.ChainTaste) {
        val t = taste.normalized()
        _chainTaste.value = t
        com.sadurni.attacca.chain.EdgeFeatures.taste = t
        prefs.edit().putString("chain_taste", if (t.isDefault) null else t.encode()).apply()
    }

    private val _chain = MutableStateFlow(
        // A single inclusion list from before sets existed becomes the first set.
        InclusionSets.keepInStep(ChainSettings(
            autoAnalyze = prefs.getBoolean("chain_auto", ChainSettings().autoAnalyze),
            artistSpacing = prefs.getInt("chain_artist_spacing", ChainSettings().artistSpacing),
            edgeSeconds = prefs.getInt("chain_edge_seconds", ChainSettings().edgeSeconds),
            analysisThreads = prefs.getInt("chain_threads", ChainSettings().analysisThreads),
            measureSilences = prefs.getBoolean("chain_silences", ChainSettings().measureSilences),
            keepScreenOn = prefs.getBoolean("chain_keep_screen_on", ChainSettings().keepScreenOn),
            saveMadeLists = prefs.getBoolean("chain_save_made", ChainSettings().saveMadeLists),
            quickNotPlayedDays = prefs.getInt("chain_quick_not_played", 0).coerceIn(0, MAX_NOT_PLAYED_DAYS),
            listsOnlyIncluded = prefs.getBoolean("chain_only_included", false),
            skipDamaged = prefs.getBoolean("chain_skip_damaged", true),
            inclusionList = prefs.getString("inclusion_list", null),
            inclusionMode = runCatching {
                com.sadurni.attacca.library.InclusionMode.valueOf(prefs.getString("inclusion_mode", null)!!)
            }.getOrDefault(com.sadurni.attacca.library.InclusionMode.ALLOW),
            inclusionSets = com.sadurni.attacca.library.InclusionSet.listFromJson(prefs.getString("inclusion_sets", null)),
            activeSet = prefs.getString("inclusion_active", null),
            randomAskMood = prefs.getBoolean("random_ask_mood", ChainSettings().randomAskMood),
            randomMood = com.sadurni.attacca.chain.Mood.entries.firstOrNull { it.name == prefs.getString("random_mood", null) },
            randomShake = prefs.getBoolean("random_shake", ChainSettings().randomShake),
            shakeStrength = prefs.getInt("random_shake_strength", ChainSettings().shakeStrength).coerceIn(1, 3),
            shakeKind = ShakeKind.entries.firstOrNull { it.name == prefs.getString("random_shake_kind", null) } ?: ShakeKind.SIDEWAYS,
        )),
    )
    val chain: StateFlow<ChainSettings> = _chain.asStateFlow()

    fun updateChain(transform: (ChainSettings) -> ChainSettings) {
        val updated = InclusionSets.keepInStep(transform(_chain.value))
        _chain.value = updated
        prefs.edit()
            .putBoolean("chain_auto", updated.autoAnalyze)
            .putInt("chain_artist_spacing", updated.artistSpacing)
            .putInt("chain_edge_seconds", updated.edgeSeconds)
            .putInt("chain_threads", updated.analysisThreads)
            .putBoolean("chain_silences", updated.measureSilences)
            .putBoolean("chain_keep_screen_on", updated.keepScreenOn)
            .putBoolean("chain_save_made", updated.saveMadeLists)
            .putInt("chain_quick_not_played", updated.quickNotPlayedDays)
            .putBoolean("chain_only_included", updated.listsOnlyIncluded)
            .putString("inclusion_sets", com.sadurni.attacca.library.InclusionSet.listToJson(updated.inclusionSets))
            .putString("inclusion_active", updated.activeSet)
            .putBoolean("chain_skip_damaged", updated.skipDamaged)
            .putString("inclusion_list", updated.inclusionList)
            .putString("inclusion_mode", updated.inclusionMode.name)
            .putBoolean("random_ask_mood", updated.randomAskMood)
            .putString("random_mood", updated.randomMood?.name)
            .putBoolean("random_shake", updated.randomShake)
            .putInt("random_shake_strength", updated.shakeStrength)
            .putString("random_shake_kind", updated.shakeKind.name)
            .apply()
    }

    private val _ui = MutableStateFlow(readUi())
    val ui: StateFlow<UiSettings> = _ui.asStateFlow()

    /** Base folder of the music, for portable lists (see [com.sadurni.attacca.library.M3uParser.musicFolder]). */
    var musicFolder: String?
        get() = prefs.getString(KEY_MUSIC_FOLDER, null)
        set(value) {
            prefs.edit().putString(KEY_MUSIC_FOLDER, value).apply()
            com.sadurni.attacca.library.M3uParser.musicFolder = value?.let { java.io.File(it) }
        }

    /** Folder where saved M3U8 lists are kept, opened from the Lists screen. */
    var listsFolder: String?
        get() = prefs.getString(KEY_LISTS_FOLDER, null)
        set(value) = prefs.edit().putString(KEY_LISTS_FOLDER, value).apply()

    var lastBrowsedFolder: String?
        get() = prefs.getString(KEY_LAST_FOLDER, null)
        set(value) = prefs.edit().putString(KEY_LAST_FOLDER, value).apply()

    /** Last folder shown by the browser opened from Lists, kept apart from the music browser's. */
    var lastListsFolder: String?
        get() = prefs.getString(KEY_LAST_LISTS_FOLDER, null)
        set(value) = prefs.edit().putString(KEY_LAST_LISTS_FOLDER, value).apply()

    /** Edits the active profile. */
    fun updateDsp(transform: (DspSettings) -> DspSettings) = profiles.updateActiveSettings(transform)

    fun updateTrim(transform: (TrimSettings) -> TrimSettings) {
        val updated = transform(_trim.value)
        _trim.value = updated
        prefs.edit()
            .putBoolean("trim_on", updated.enabled)
            .putBoolean("trim_start", updated.trimStart)
            .putBoolean("trim_end", updated.trimEnd)
            .putFloat("trim_threshold", updated.thresholdDb)
            .putLong("trim_min", updated.minSilenceMs)
            .putLong("trim_margin", updated.marginMs)
            .putBoolean("trim_gapless", updated.respectGaplessAlbums)
            .apply()
    }

    private fun readTrim(): TrimSettings {
        val d = TrimSettings()
        return TrimSettings(
            enabled = prefs.getBoolean("trim_on", d.enabled),
            trimStart = prefs.getBoolean("trim_start", d.trimStart),
            trimEnd = prefs.getBoolean("trim_end", d.trimEnd),
            thresholdDb = prefs.getFloat("trim_threshold", d.thresholdDb),
            minSilenceMs = prefs.getLong("trim_min", d.minSilenceMs),
            marginMs = prefs.getLong("trim_margin", d.marginMs),
            respectGaplessAlbums = prefs.getBoolean("trim_gapless", d.respectGaplessAlbums),
        )
    }

    fun updateUi(transform: (UiSettings) -> UiSettings) {
        val updated = transform(_ui.value)
        _ui.value = updated
        writeUi(updated)
    }

    /** Phase 1 stored DSP settings as separate keys; they seed the default profile. */
    private fun readDsp(): DspSettings {
        val d = DspSettings()
        return DspSettings(
            replayGainMode = prefs.getEnum("rg_mode", d.replayGainMode),
            replayGainPreampDb = prefs.getFloat("rg_preamp", d.replayGainPreampDb),
            untaggedGainDb = prefs.getFloat("rg_untagged", d.untaggedGainDb),
            replayGainPreventClipping = prefs.getBoolean("rg_prevent_clip", d.replayGainPreventClipping),
            preampEnabled = prefs.getBoolean("preamp_on", d.preampEnabled),
            preampDb = prefs.getFloat("preamp_db", d.preampDb),
            crossfeedEnabled = prefs.getBoolean("cf_on", d.crossfeedEnabled),
            crossfeedPreset = prefs.getEnum("cf_preset", d.crossfeedPreset),
            crossfeedCustomCutoffHz = prefs.getInt("cf_cutoff", d.crossfeedCustomCutoffHz),
            crossfeedCustomFeedDb = prefs.getFloat("cf_feed", d.crossfeedCustomFeedDb),
            limiterEnabled = prefs.getBoolean("lim_on", d.limiterEnabled),
            limiterCeilingDb = prefs.getFloat("lim_ceiling", d.limiterCeilingDb),
        )
    }

    private fun readUi(): UiSettings {
        val d = UiSettings()
        return UiSettings(
            themeMode = prefs.getEnum("theme", d.themeMode),
            dynamicAccent = prefs.getBoolean("dyn_accent", d.dynamicAccent),
            blurredBackground = prefs.getBoolean("blur_bg", d.blurredBackground),
            coverBehindScreens = prefs.getBoolean("cover_screens", d.coverBehindScreens),
            showRemainingTime = prefs.getBoolean("remaining", d.showRemainingTime),
            waveformMode = prefs.getEnum("waveform", d.waveformMode),
            oramaStrip = prefs.getBoolean("orama_strip", d.oramaStrip),
            keepScreenOn = prefs.getEnum("keep_screen_on", d.keepScreenOn),
            showTips = prefs.getBoolean("show_tips", d.showTips),
            themeId = prefs.getString("theme_id", null),
            overLockScreen = prefs.getBoolean("over_lock_screen", d.overLockScreen),
            lockWallpaper = prefs.getEnum("lock_wallpaper", d.lockWallpaper),
            widgetBig = prefs.getEnum("widget_big", d.widgetBig),
        )
    }

    private fun writeUi(s: UiSettings) {
        prefs.edit()
            .putString("theme", s.themeMode.name)
            .putBoolean("dyn_accent", s.dynamicAccent)
            .putBoolean("blur_bg", s.blurredBackground)
            .putBoolean("cover_screens", s.coverBehindScreens)
            .putBoolean("remaining", s.showRemainingTime)
            .putString("waveform", s.waveformMode.name)
            .putBoolean("orama_strip", s.oramaStrip)
            .putString("keep_screen_on", s.keepScreenOn.name)
            .putBoolean("show_tips", s.showTips)
            .putString("theme_id", s.themeId)
            .putBoolean("over_lock_screen", s.overLockScreen)
            .putString("lock_wallpaper", s.lockWallpaper.name)
            .putString("widget_big", s.widgetBig.name)
            .apply()
    }

    private inline fun <reified T : Enum<T>> SharedPreferences.getEnum(key: String, default: T): T =
        getString(key, null)?.let { name -> enumValues<T>().firstOrNull { it.name == name } } ?: default

    private companion object {
        const val KEY_LAST_FOLDER = "last_folder"
        const val KEY_LAST_LISTS_FOLDER = "last_lists_folder"
        const val KEY_MUSIC_FOLDER = "music_folder"
        const val KEY_LISTS_FOLDER = "lists_folder"
    }
}
