package com.sadurni.attacca.ui.settings

import com.sadurni.attacca.ui.theme.AttaccaType
import com.sadurni.attacca.ui.common.CreateDocumentAt
import com.sadurni.attacca.ui.common.OpenDocumentAt
import com.sadurni.attacca.ui.common.PickerMemory
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.rememberCoroutineScope
import com.sadurni.attacca.settings.Backup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sadurni.attacca.library.UriPaths
import com.sadurni.attacca.ui.theme.LocalAttaccaColors
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sadurni.attacca.R
import com.sadurni.attacca.app
import com.sadurni.attacca.settings.ThemeMode
import com.sadurni.attacca.settings.WaveformMode
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Close
import com.sadurni.attacca.ui.common.Icon
import com.sadurni.attacca.ui.common.IconButton
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Rule
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Swipe
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.SwapHoriz
import kotlin.math.roundToInt
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Search
import androidx.compose.ui.graphics.vector.ImageVector

private enum class SettingsPage { PLAYBACK, CROSSFADE, SILENCE, LISTENING, APPEARANCE, GESTURES, LIBRARY, CHAINING, INCLUSION, BACKGROUND, LANGUAGE, BACKUP }

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    openGestures: Boolean = false,
    openAppearance: Boolean = false,
    onEditTheme: (String) -> Unit = {},
    onThemeGallery: () -> Unit = {},
) {
    val settings = LocalContext.current.app.settings
    var guide by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    if (guide) {
        SetupGuide(onDone = { guide = false })
        return
    }
    var query by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    CategoryScreen(
        title = stringResource(R.string.settings_title),
        pages = SettingsPage.entries.toTypedArray(),
        initialPage = when {
            openGestures -> SettingsPage.GESTURES
            openAppearance -> SettingsPage.APPEARANCE
            else -> null
        },
        pageTitle = { stringResource(it.titleRes) },
        onBack = onBack,
        menu = { open ->
            androidx.compose.material3.OutlinedTextField(
                query,
                { query = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.settings_search)) },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cancel)) }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (query.isNotBlank()) {
                SettingsSearchResults(query) { open(it) }
            } else {
                CategoryRow(Icons.Rounded.AutoAwesome, stringResource(R.string.setup_title), stringResource(R.string.setup_entry_summary)) { guide = true }
                // The check-up: what keeps things from being at their best, and the guides by ear.
                var checkup by remember { mutableStateOf(false) }
                CategoryRow(Icons.Rounded.Checklist, stringResource(R.string.checkup_title), stringResource(R.string.checkup_summary)) { checkup = true }
                if (checkup) Checkup { checkup = false }
                SectionTitle(stringResource(R.string.settings_essentials))
                EssentialsSection(settings) { open(it) }
                for (group in SettingsGroup.entries) {
                    SectionTitle(stringResource(group.titleRes))
                    for (page in group.pages) {
                        CategoryRow(page.icon, stringResource(page.titleRes), pageSummary(page)) { open(page) }
                    }
                }
            }
        },
    ) { page ->
        when (page) {
            SettingsPage.PLAYBACK -> PlaybackPage(settings)
            SettingsPage.CROSSFADE -> CrossfadePage(settings)
            SettingsPage.SILENCE -> SilencePage(settings)
            SettingsPage.LISTENING -> {
                val playback by settings.playback.collectAsStateWithLifecycle()
                ListeningSection(settings, playback)
            }
            SettingsPage.INCLUSION -> InclusionSection(settings)
            SettingsPage.BACKGROUND -> BackgroundPage(settings)
            SettingsPage.APPEARANCE -> AppearancePage(settings, onEditTheme, onThemeGallery)
            SettingsPage.GESTURES -> GestureSettings(settings)
            SettingsPage.LIBRARY -> MusicFolderSection(settings)
            SettingsPage.CHAINING -> ChainingPage(settings)
            SettingsPage.LANGUAGE -> LanguagePage()
            SettingsPage.BACKUP -> BackupSection()
        }
    }
}

/** The settings pages under the theme they belong to, in the order the menu shows them. */
private enum class SettingsGroup(val titleRes: Int, val pages: List<SettingsPage>) {
    PLAY(R.string.settings_group_playback, listOf(SettingsPage.PLAYBACK, SettingsPage.CROSSFADE, SettingsPage.SILENCE)),
    LISTS(R.string.settings_group_lists, listOf(SettingsPage.CHAINING, SettingsPage.INCLUSION)),
    LIBRARY(R.string.settings_group_library, listOf(SettingsPage.LIBRARY, SettingsPage.LISTENING, SettingsPage.BACKUP)),
    LOOK(R.string.settings_group_look, listOf(SettingsPage.APPEARANCE, SettingsPage.GESTURES, SettingsPage.LANGUAGE)),
    ADVANCED(R.string.settings_group_advanced, listOf(SettingsPage.BACKGROUND)),
}

/** What each page holds, by the names of its settings, for the search. */
private val SettingsPage.searchTerms: List<Int>
    get() = when (this) {
        SettingsPage.PLAYBACK -> listOf(
            R.string.section_fades, R.string.fade_duration, R.string.sleep_fade, R.string.section_rewind, R.string.rewind_after_pause,
            R.string.rewind_seconds, R.string.endless_title, R.string.endless_switch, R.string.section_folder_change,
            R.string.section_other_sounds, R.string.focus_duck_title, R.string.focus_resume, R.string.go_to_end, R.string.to_end_seconds,
            R.string.section_album_shuffle, R.string.albums_by_tag, R.string.albums_by_folder,
        )
        SettingsPage.CROSSFADE -> listOf(
            R.string.crossfade_duration, R.string.crossfade_skip_album, R.string.crossfade_only_shuffle, R.string.crossfade_style,
            R.string.crossfade_style_auto, R.string.crossfade_style_mix, R.string.skip_crossfade,
        )
        SettingsPage.SILENCE -> listOf(
            R.string.silence_enabled, R.string.silence_start, R.string.silence_end, R.string.silence_gapless,
            R.string.silence_threshold, R.string.silence_min, R.string.silence_margin,
        )
        SettingsPage.LISTENING -> listOf(R.string.listening_tracks_played, R.string.listening_plays, R.string.listen_log, R.string.listening_clear)
        SettingsPage.INCLUSION -> listOf(R.string.inclusion_list, R.string.inclusion_allow, R.string.inclusion_block, R.string.lists_only_included, R.string.inclusion_manage)
        SettingsPage.BACKGROUND -> listOf(
            R.string.section_car, R.string.car_buttons, R.string.section_when_closed, R.string.resume_when_closed,
            R.string.section_performance, R.string.audio_buffer, R.string.efficient_scheduling,
        )
        SettingsPage.APPEARANCE -> listOf(
            R.string.dynamic_accent, R.string.blurred_background, R.string.cover_behind_screens, R.string.section_player_screen,
            R.string.remaining_time, R.string.widget_section, R.string.section_tips, R.string.tips_show,
            R.string.section_keep_screen_on, R.string.section_waveform,
        )
        SettingsPage.GESTURES -> emptyList()
        SettingsPage.LIBRARY -> listOf(R.string.music_folder, R.string.library_scan, R.string.library_stats, R.string.ignored_title, R.string.damaged_title)
        SettingsPage.CHAINING -> listOf(
            R.string.chain_analysis, R.string.chain_auto, R.string.chain_analyze_now, R.string.chain_silences, R.string.chain_measure_silences,
            R.string.chain_measure_whole, R.string.chain_edge_seconds, R.string.chain_threads, R.string.chain_keep_screen_on,
            R.string.chain_reanalyze, R.string.chain_order, R.string.chain_save_made, R.string.chain_quick_not_played,
            R.string.chain_artist_spacing, R.string.random_list, R.string.random_ask_mood, R.string.random_shake,
        )
        SettingsPage.LANGUAGE -> emptyList()
        SettingsPage.BACKUP -> listOf(
            R.string.backup_create, R.string.backup_restore, R.string.auto_backup_title, R.string.exchange_section,
            R.string.exchange_export, R.string.exchange_import, R.string.player_db_export,
        )
    }

private val accents = Regex("\\p{Mn}+")

/** Lowercase and without accents, so "musica" finds "Música". */
private fun searchable(text: String): String =
    accents.replace(java.text.Normalizer.normalize(text.lowercase(), java.text.Normalizer.Form.NFD), "")

/** The pages whose title or settings match [query], each with the settings that do. */
@Composable
private fun SettingsSearchResults(query: String, open: (SettingsPage) -> Unit) {
    val q = searchable(query.trim())
    var any = false
    for (group in SettingsGroup.entries) for (page in group.pages) {
        val title = stringResource(page.titleRes)
        val hits = page.searchTerms.map { stringResource(it) }.filter { searchable(it).contains(q) }
        if (hits.isEmpty() && !searchable(title).contains(q)) continue
        any = true
        CategoryRow(page.icon, title, hits.take(3).joinToString(" · ")) { open(page) }
    }
    if (!any) Hint(stringResource(R.string.search_nothing))
}

/**
 * The few settings that matter most, at hand: the music folder, the analysis in the background
 * and how tracks follow each other.
 */
@Composable
private fun EssentialsSection(settings: com.sadurni.attacca.settings.AppSettings, open: (SettingsPage) -> Unit) {
    val context = LocalContext.current
    val playback by settings.playback.collectAsStateWithLifecycle()
    val chain by settings.chain.collectAsStateWithLifecycle()
    val trim by settings.trim.collectAsStateWithLifecycle()
    val folder = settings.musicFolder
    CategoryRow(Icons.Rounded.LibraryMusic, stringResource(R.string.music_folder), folder ?: stringResource(R.string.music_folder_none)) { open(SettingsPage.LIBRARY) }
    SwitchRow(stringResource(R.string.chain_auto), chain.autoAnalyze, onChange = { on ->
        settings.updateChain { it.copy(autoAnalyze = on) }
        if (on && folder != null) context.app.chainAnalyzer.analyzeLibrary(java.io.File(folder))
    }, enabled = folder != null)
    Text(stringResource(R.string.settings_transitions), fontSize = AttaccaType.body, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
    val current = when {
        playback.crossfadeMs == 0 -> 0
        playback.crossfadeStyle == com.sadurni.attacca.dsp.CrossfadeStyle.AUTOMATIC -> 1
        else -> 2
    }
    SegmentedChoice(
        listOf(
            1 to stringResource(R.string.crossfade_style_auto),
            2 to stringResource(R.string.crossfade_style_crossfade),
            0 to stringResource(R.string.settings_transition_none),
        ),
        current,
    ) { v ->
        settings.updatePlayback {
            when (v) {
                0 -> it.copy(crossfadeMs = 0)
                1 -> it.copy(crossfadeMs = if (it.crossfadeMs > 0) it.crossfadeMs else 6_000, crossfadeStyle = com.sadurni.attacca.dsp.CrossfadeStyle.AUTOMATIC)
                else -> it.copy(
                    crossfadeMs = if (it.crossfadeMs > 0) it.crossfadeMs else 5_000,
                    crossfadeStyle = if (it.crossfadeStyle == com.sadurni.attacca.dsp.CrossfadeStyle.AUTOMATIC) com.sadurni.attacca.dsp.CrossfadeStyle.CROSSFADE else it.crossfadeStyle,
                )
            }
        }
    }
    SwitchRow(stringResource(R.string.crossfade_skip_album), playback.crossfadeSkipSameAlbum, { v ->
        settings.updatePlayback { it.copy(crossfadeSkipSameAlbum = v) }
    }, enabled = playback.crossfadeMs > 0)
    SwitchRow(stringResource(R.string.silence_enabled), trim.enabled, { v -> settings.updateTrim { it.copy(enabled = v) } })
    Hint(stringResource(R.string.settings_essentials_hint))
}

private val SettingsPage.titleRes: Int
    get() = when (this) {
        SettingsPage.PLAYBACK -> R.string.section_playback
        SettingsPage.CROSSFADE -> R.string.crossfade
        SettingsPage.SILENCE -> R.string.section_silence
        SettingsPage.LISTENING -> R.string.section_listening
        SettingsPage.INCLUSION -> R.string.inclusion_title
        SettingsPage.BACKGROUND -> R.string.section_background
        SettingsPage.APPEARANCE -> R.string.section_appearance
        SettingsPage.GESTURES -> R.string.section_gestures_page
        SettingsPage.LIBRARY -> R.string.section_library
        SettingsPage.CHAINING -> R.string.section_chaining
        SettingsPage.LANGUAGE -> R.string.section_language
        SettingsPage.BACKUP -> R.string.section_backup
    }

private val SettingsPage.icon: ImageVector
    get() = when (this) {
        SettingsPage.PLAYBACK -> Icons.Rounded.PlayCircle
        SettingsPage.CROSSFADE -> Icons.Rounded.Tune
        SettingsPage.SILENCE -> Icons.Rounded.ContentCut
        SettingsPage.LISTENING -> Icons.Rounded.BarChart
        SettingsPage.INCLUSION -> Icons.Rounded.Rule
        SettingsPage.BACKGROUND -> Icons.Rounded.DirectionsCar
        SettingsPage.APPEARANCE -> Icons.Rounded.Palette
        SettingsPage.GESTURES -> Icons.Rounded.Swipe
        SettingsPage.LIBRARY -> Icons.Rounded.LibraryMusic
        SettingsPage.CHAINING -> Icons.Rounded.Link
        SettingsPage.LANGUAGE -> Icons.Rounded.Language
        SettingsPage.BACKUP -> Icons.Rounded.Backup
    }

@Composable
private fun pageSummary(page: SettingsPage): String {
    val settings = LocalContext.current.app.settings
    return when (page) {
        SettingsPage.PLAYBACK -> stringResource(R.string.settings_playback_summary)
        SettingsPage.CROSSFADE -> {
            val playback by settings.playback.collectAsStateWithLifecycle()
            if (playback.crossfadeMs > 0) "%.1f s".format(playback.crossfadeMs / 1000f) else stringResource(R.string.off)
        }
        SettingsPage.LISTENING -> stringResource(R.string.settings_listening_summary)
        SettingsPage.INCLUSION -> {
            val chain by settings.chain.collectAsStateWithLifecycle()
            chain.inclusionList?.let { java.io.File(it).nameWithoutExtension } ?: stringResource(R.string.off)
        }
        SettingsPage.BACKGROUND -> stringResource(R.string.settings_background_summary)
        SettingsPage.SILENCE -> {
            val trim by settings.trim.collectAsStateWithLifecycle()
            if (trim.enabled) stringResource(R.string.settings_silence_summary, trim.thresholdDb.toInt()) else stringResource(R.string.off)
        }
        SettingsPage.APPEARANCE -> stringResource(R.string.settings_appearance_summary)
        SettingsPage.GESTURES -> stringResource(R.string.settings_gestures_rows_summary)
        SettingsPage.LIBRARY -> settings.musicFolder ?: stringResource(R.string.music_folder_none)
        SettingsPage.CHAINING -> {
            val chain by settings.chain.collectAsStateWithLifecycle()
            stringResource(if (chain.autoAnalyze) R.string.chain_summary_auto else R.string.chain_summary_on_demand)
        }
        SettingsPage.LANGUAGE -> languageSummary()
        SettingsPage.BACKUP -> stringResource(R.string.settings_backup_summary)
    }
}

/** How tracks blend into each other, also when skipping by hand. */
@Composable
private fun CrossfadePage(settings: com.sadurni.attacca.settings.AppSettings) {
    val playback by settings.playback.collectAsStateWithLifecycle()
    var joinGuide by remember { mutableStateOf(false) }
    CategoryRow(Icons.Rounded.SwapHoriz, stringResource(R.string.join_guide_open), stringResource(R.string.join_guide_hint)) { joinGuide = true }
    if (joinGuide) TransitionsGuide { joinGuide = false }
    val off = stringResource(R.string.off)
    ValueSlider(
        label = stringResource(R.string.crossfade_duration),
        value = playback.crossfadeMs / 1000f,
        range = 0f..12f,
        step = 0.5f,
        format = { if (it == 0f) off else "%.1f s".format(it) },
        onChange = { v -> settings.updatePlayback { it.copy(crossfadeMs = (v * 1000).toInt()) } },
    )
    SwitchRow(stringResource(R.string.crossfade_skip_album), playback.crossfadeSkipSameAlbum, { v ->
        settings.updatePlayback { it.copy(crossfadeSkipSameAlbum = v) }
    })
    Hint(stringResource(R.string.crossfade_hint))
    SwitchRow(stringResource(R.string.crossfade_only_shuffle), playback.crossfadeOnlyShuffle, { v ->
        settings.updatePlayback { it.copy(crossfadeOnlyShuffle = v) }
    })
    Hint(stringResource(R.string.crossfade_only_shuffle_hint))
    CrossfadeStyleSection(settings, playback)
    ValueSlider(
        label = stringResource(R.string.skip_crossfade),
        value = playback.skipCrossfadeMs / 1000f,
        range = 0f..6f,
        step = 0.5f,
        format = { if (it == 0f) off else "%.1f s".format(it) },
        onChange = { v -> settings.updatePlayback { it.copy(skipCrossfadeMs = (v * 1000).toInt()) } },
    )
    Hint(stringResource(R.string.skip_crossfade_hint))
}

/** Fades, going back after a pause, the end of a list and how albums are taken. */
@Composable
private fun PlaybackPage(settings: com.sadurni.attacca.settings.AppSettings) {
    val playback by settings.playback.collectAsStateWithLifecycle()
    val off = stringResource(R.string.off)
    SectionTitle(stringResource(R.string.section_fades))
    ValueSlider(
        label = stringResource(R.string.fade_duration),
        value = playback.fadeMs.toFloat(),
        range = 0f..1000f,
        step = 50f,
        format = { if (it == 0f) off else "${it.toInt()} ms" },
        onChange = { v -> settings.updatePlayback { it.copy(fadeMs = v.toInt()) } },
    )
    Hint(stringResource(R.string.fade_hint))
    ValueSlider(
        label = stringResource(R.string.sleep_fade),
        value = playback.sleepFadeMs / 1000f,
        range = 0f..30f,
        step = 1f,
        format = { "${it.toInt()} s" },
        onChange = { v -> settings.updatePlayback { it.copy(sleepFadeMs = (v * 1000).toInt()) } },
    )

    SectionTitle(stringResource(R.string.section_rewind))
    ValueSlider(
        label = stringResource(R.string.rewind_after_pause),
        value = playback.rewindAfterPauseS.toFloat(),
        range = 0f..600f,
        step = 15f,
        format = { s ->
            when {
                s == 0f -> off
                s < 60f -> "${s.toInt()} s"
                else -> "%d:%02d min".format(s.toInt() / 60, s.toInt() % 60)
            }
        },
        onChange = { v -> settings.updatePlayback { it.copy(rewindAfterPauseS = v.toInt()) } },
    )
    ValueSlider(
        label = stringResource(R.string.rewind_seconds),
        value = playback.rewindSeconds.toFloat(),
        range = 1f..60f,
        step = 1f,
        format = { "${it.toInt()} s" },
        onChange = { v -> settings.updatePlayback { it.copy(rewindSeconds = v.toInt()) } },
        enabled = playback.rewindAfterPauseS > 0,
    )
    Hint(stringResource(R.string.rewind_hint))

    SectionTitle(stringResource(R.string.endless_title))
    SwitchRow(stringResource(R.string.endless_switch), playback.endless, { v -> settings.updatePlayback { it.copy(endless = v) } })
    Hint(stringResource(R.string.endless_hint))

    SectionTitle(stringResource(R.string.section_folder_change))
    RadioRow(stringResource(R.string.folder_change_off), playback.folderChange == com.sadurni.attacca.settings.FolderChange.OFF) {
        settings.updatePlayback { it.copy(folderChange = com.sadurni.attacca.settings.FolderChange.OFF) }
    }
    RadioRow(stringResource(R.string.folder_change_next), playback.folderChange == com.sadurni.attacca.settings.FolderChange.NEXT) {
        settings.updatePlayback { it.copy(folderChange = com.sadurni.attacca.settings.FolderChange.NEXT) }
    }
    RadioRow(stringResource(R.string.folder_change_random), playback.folderChange == com.sadurni.attacca.settings.FolderChange.RANDOM) {
        settings.updatePlayback { it.copy(folderChange = com.sadurni.attacca.settings.FolderChange.RANDOM) }
    }
    Hint(stringResource(R.string.folder_change_hint))

    SectionTitle(stringResource(R.string.section_other_sounds))
    Text(
        stringResource(R.string.focus_duck_title),
        fontSize = AttaccaType.secondary,
        modifier = Modifier.padding(start = 16.dp, top = 4.dp),
    )
    for ((mode, label) in listOf(
        com.sadurni.attacca.settings.FocusDuck.LOWER to R.string.focus_duck_lower,
        com.sadurni.attacca.settings.FocusDuck.PAUSE to R.string.focus_duck_pause,
        com.sadurni.attacca.settings.FocusDuck.IGNORE to R.string.focus_duck_ignore,
    )) {
        RadioRow(stringResource(label), playback.focusDuck == mode) { settings.updatePlayback { it.copy(focusDuck = mode) } }
    }
    SwitchRow(stringResource(R.string.focus_resume), playback.focusResume, { v -> settings.updatePlayback { it.copy(focusResume = v) } })
    Hint(stringResource(R.string.focus_hint))

    SectionTitle(stringResource(R.string.go_to_end))
    ValueSlider(
        label = stringResource(R.string.to_end_seconds),
        value = playback.toEndSeconds.toFloat(),
        range = 5f..60f,
        step = 5f,
        format = { "${it.toInt()} s" },
        onChange = { v -> settings.updatePlayback { it.copy(toEndSeconds = v.toInt()) } },
    )
    Hint(stringResource(R.string.to_end_hint))

    SectionTitle(stringResource(R.string.section_album_shuffle))
    RadioRow(stringResource(R.string.albums_by_tag), !playback.albumsByFolder) {
        settings.updatePlayback { it.copy(albumsByFolder = false) }
    }
    RadioRow(stringResource(R.string.albums_by_folder), playback.albumsByFolder) {
        settings.updatePlayback { it.copy(albumsByFolder = true) }
    }
    Hint(stringResource(R.string.albums_by_folder_hint))
}

/** The car's buttons, playing with Attacca closed, and how the audio is kept running. */
@Composable
private fun BackgroundPage(settings: com.sadurni.attacca.settings.AppSettings) {
    val context = LocalContext.current
    val playback by settings.playback.collectAsStateWithLifecycle()
    SectionTitle(stringResource(R.string.section_car))
    SwitchRow(stringResource(R.string.car_buttons), playback.carButtons, { v ->
        settings.updatePlayback { it.copy(carButtons = v) }
    })
    Hint(stringResource(R.string.car_buttons_hint))

    SectionTitle(stringResource(R.string.section_when_closed))
    SwitchRow(stringResource(R.string.resume_when_closed), playback.resumeWhenClosed, { v ->
        settings.updatePlayback { it.copy(resumeWhenClosed = v) }
        com.sadurni.attacca.playback.setResumptionEnabled(context, v)
    })
    Hint(stringResource(R.string.resume_when_closed_hint))

    PerformanceSection(settings, playback)
}

/** How the levels of the two tracks move in a crossfade. */
@Composable
private fun CrossfadeStyleSection(settings: com.sadurni.attacca.settings.AppSettings, playback: com.sadurni.attacca.settings.PlaybackSettings) {
    Text(
        stringResource(R.string.crossfade_style),
        fontSize = AttaccaType.body,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
    )
    val styles = listOf(
        com.sadurni.attacca.dsp.CrossfadeStyle.CROSSFADE to (R.string.crossfade_style_crossfade to R.string.crossfade_style_crossfade_hint),
        com.sadurni.attacca.dsp.CrossfadeStyle.MIX to (R.string.crossfade_style_mix to R.string.crossfade_style_mix_hint),
        com.sadurni.attacca.dsp.CrossfadeStyle.AUTOMATIC to (R.string.crossfade_style_auto to R.string.crossfade_style_auto_hint),
        com.sadurni.attacca.dsp.CrossfadeStyle.CUSTOM to (R.string.crossfade_style_custom to R.string.crossfade_style_custom_hint),
    )
    for ((style, texts) in styles) {
        RadioRow(stringResource(texts.first), playback.crossfadeStyle == style) {
            settings.updatePlayback { it.copy(crossfadeStyle = style) }
        }
    }
    styles.firstOrNull { it.first == playback.crossfadeStyle }?.let { Hint(stringResource(it.second.second)) }
    if (playback.crossfadeStyle == com.sadurni.attacca.dsp.CrossfadeStyle.CUSTOM) {
        val shape = playback.crossfadeCustom
        var out by remember(shape) { mutableStateOf(shape.outFrom..shape.outTo) }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            Row {
                Text(stringResource(R.string.crossfade_custom_out), fontSize = AttaccaType.body, modifier = Modifier.weight(1f))
                Text("${(out.start * 100).toInt()}–${(out.endInclusive * 100).toInt()} %", fontSize = AttaccaType.body)
            }
            androidx.compose.material3.RangeSlider(
                value = out,
                onValueChange = { out = it },
                onValueChangeFinished = {
                    settings.updatePlayback { it.copy(crossfadeCustom = com.sadurni.attacca.dsp.CrossfadeShape.of(out.start, out.endInclusive, shape.inTo)) }
                },
                valueRange = 0f..1f,
                steps = 19,
            )
        }
        val noFade = stringResource(R.string.crossfade_custom_in_none)
        ValueSlider(
            label = stringResource(R.string.crossfade_custom_in),
            value = shape.inTo,
            range = 0f..1f,
            step = 0.05f,
            format = { if (it == 0f) noFade else "${(it * 100).toInt()} %" },
            onChange = { v -> settings.updatePlayback { it.copy(crossfadeCustom = com.sadurni.attacca.dsp.CrossfadeShape.of(shape.outFrom, shape.outTo, v)) } },
        )
        Hint(stringResource(R.string.crossfade_custom_values_hint))
    }
}

/** Plays and skips counted per track, and the listening log for scrobbling. */
@Composable
private fun ListeningSection(settings: com.sadurni.attacca.settings.AppSettings, playback: com.sadurni.attacca.settings.PlaybackSettings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listening = context.app.listening
    val version by listening.version.collectAsStateWithLifecycle()
    var logVersion by remember { mutableStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    val figures by androidx.compose.runtime.produceState<Triple<Int, Int, Int>?>(null, version, logVersion) {
        value = withContext(com.sadurni.attacca.Background.dispatcher) {
            runCatching {
                val all = listening.snapshot().values
                Triple(all.count { it.plays > 0 }, all.sumOf { it.plays }, com.sadurni.attacca.library.ListenLog.entries())
            }.getOrNull()
        }
    }

    figures?.let { (tracks, plays, _) ->
        StatRow(stringResource(R.string.listening_tracks_played), "%,d".format(tracks))
        StatRow(stringResource(R.string.listening_plays), "%,d".format(plays))
    }
    Hint(stringResource(R.string.listening_hint))
    SwitchRow(stringResource(R.string.listen_log), playback.listenLog, { v ->
        settings.updatePlayback { it.copy(listenLog = v) }
    })
    Hint(stringResource(R.string.listen_log_hint, com.sadurni.attacca.library.ListenLog.file.absolutePath))
    val entries = figures?.third ?: 0
    if (entries > 0) {
        StatRow(stringResource(R.string.listen_log_entries), "%,d".format(entries))
        Row(
            Modifier.fillMaxWidth().clickable {
                scope.launch {
                    val moved = withContext(com.sadurni.attacca.Background.dispatcher) { runCatching { com.sadurni.attacca.library.ListenLog.startNew() }.getOrNull() }
                    logVersion++
                    if (moved != null) Toast.makeText(context, context.getString(R.string.listen_log_new_done, moved.name), Toast.LENGTH_LONG).show()
                }
            }.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            ActionText(stringResource(R.string.listen_log_new))
        }
        Hint(stringResource(R.string.listen_log_new_hint))
    }
    if ((figures?.first ?: 0) > 0) {
        Row(
            Modifier.fillMaxWidth().clickable { confirmClear = true }.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            ActionText(stringResource(R.string.listening_clear))
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.listening_clear)) },
            text = { Text(stringResource(R.string.listening_clear_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch(com.sadurni.attacca.Background.dispatcher) { runCatching { listening.clear() } }
                }) { Text(stringResource(R.string.listening_clear_yes)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Output buffer (manual or learned from dropouts) and the playback thread's scheduling. */
@Composable
private fun PerformanceSection(
    settings: com.sadurni.attacca.settings.AppSettings,
    playback: com.sadurni.attacca.settings.PlaybackSettings,
) {
    val tuning by settings.audioTuning.collectAsStateWithLifecycle()
    val seconds = { ms: Int -> "%.1f s".format(ms / 1000f) }
    SectionTitle(stringResource(R.string.section_performance))
    val autoLabel = stringResource(R.string.audio_buffer_auto, seconds(tuning.learnedBufferMs))
    ValueSlider(
        label = stringResource(R.string.audio_buffer),
        value = playback.audioBufferMs / 1000f,
        range = 0f..com.sadurni.attacca.settings.AudioTuning.MAX_BUFFER_MS / 1000f,
        step = com.sadurni.attacca.settings.AudioTuning.MIN_BUFFER_MS / 1000f,
        format = { if (it == 0f) autoLabel else seconds((it * 1000).toInt()) },
        onChange = { v -> settings.updatePlayback { it.copy(audioBufferMs = (v * 1000).toInt()) } },
    )
    Hint(stringResource(R.string.audio_buffer_hint))
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (tuning.dropouts == 0) stringResource(R.string.audio_dropouts_none)
            else stringResource(R.string.audio_dropouts, tuning.dropouts),
            fontSize = AttaccaType.body,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = settings::resetAudioTuning,
            enabled = tuning != com.sadurni.attacca.settings.AudioTuning(),
        ) { Text(stringResource(R.string.audio_tuning_reset)) }
    }
    SwitchRow(stringResource(R.string.efficient_scheduling), playback.efficientScheduling, { v ->
        settings.updatePlayback { it.copy(efficientScheduling = v) }
    })
    Hint(stringResource(R.string.efficient_scheduling_hint))

    SystemEffectsSection(settings)
}

/** Which silences are cut, then how they are detected. */
@Composable
private fun SilencePage(settings: com.sadurni.attacca.settings.AppSettings) {
    val trim by settings.trim.collectAsStateWithLifecycle()
    SwitchRow(stringResource(R.string.silence_enabled), trim.enabled, { v ->
        settings.updateTrim { it.copy(enabled = v) }
    })
    Hint(stringResource(R.string.silence_hint))
    SwitchRow(stringResource(R.string.silence_start), trim.trimStart, { v ->
        settings.updateTrim { it.copy(trimStart = v) }
    }, enabled = trim.enabled)
    SwitchRow(stringResource(R.string.silence_end), trim.trimEnd, { v ->
        settings.updateTrim { it.copy(trimEnd = v) }
    }, enabled = trim.enabled)
    SwitchRow(stringResource(R.string.silence_gapless), trim.respectGaplessAlbums, { v ->
        settings.updateTrim { it.copy(respectGaplessAlbums = v) }
    }, enabled = trim.enabled)
    Hint(stringResource(R.string.silence_gapless_hint))

    SectionTitle(stringResource(R.string.section_detection))
    ValueSlider(
        label = stringResource(R.string.silence_threshold),
        value = trim.thresholdDb,
        range = -80f..-30f,
        step = 1f,
        format = { "%.0f dB".format(it) },
        onChange = { v -> settings.updateTrim { it.copy(thresholdDb = v) } },
        enabled = trim.enabled,
    )
    ValueSlider(
        label = stringResource(R.string.silence_min),
        value = trim.minSilenceMs.toFloat(),
        range = 100f..3000f,
        step = 100f,
        format = { "%.1f s".format(it / 1000f) },
        onChange = { v -> settings.updateTrim { it.copy(minSilenceMs = v.toLong()) } },
        enabled = trim.enabled,
    )
    ValueSlider(
        label = stringResource(R.string.silence_margin),
        value = trim.marginMs.toFloat(),
        range = 0f..1000f,
        step = 50f,
        format = { "${it.toInt()} ms" },
        onChange = { v -> settings.updateTrim { it.copy(marginMs = v.toLong()) } },
        enabled = trim.enabled,
    )
}

/**
 * The library's analysis in one file (`.attlib`), to and from Attacca Desktop on the PC or another
 * phone: what one side measured, the other need not measure again.
 */
@Composable
private fun LibraryExchangeSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val failed = stringResource(R.string.exchange_failed)
    val exported = stringResource(R.string.exchange_exported)
    val imported = stringResource(R.string.exchange_imported)
    fun run(block: () -> String) {
        working = true
        scope.launch {
            result = withContext(Dispatchers.IO) {
                runCatching(block).getOrElse { e ->
                    // The cause in a word, to tell a full memory from a folder that cannot be written.
                    android.util.Log.w("LibraryExchange", "failed", e)
                    "$failed (${e.javaClass.simpleName})"
                }
            }
            working = false
        }
    }
    val create = rememberLauncherForActivityResult(CreateDocumentAt(com.sadurni.attacca.library.LibraryExchange.MIME)) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        PickerMemory.put(context, PickerMemory.BACKUP, uri)
        run { com.sadurni.attacca.library.LibraryTransfer.export(context, uri)?.let { exported.format(it) } ?: failed }
    }
    val open = rememberLauncherForActivityResult(OpenDocumentAt()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        PickerMemory.put(context, PickerMemory.BACKUP, uri)
        run {
            com.sadurni.attacca.library.LibraryTransfer.import(context, uri)?.let { r ->
                imported.format(r.matched, r.total, r.edges, r.features, r.analysis)
            } ?: failed
        }
    }
    SectionTitle(stringResource(R.string.exchange_section))
    Row(
        Modifier.fillMaxWidth().clickable(enabled = !working) {
            val date = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.ROOT).format(java.util.Date())
            create.launch("attacca-library-$date.${com.sadurni.attacca.library.LibraryExchange.EXTENSION}" to PickerMemory.get(context, PickerMemory.BACKUP))
        }.padding(horizontal = 16.dp, vertical = 12.dp),
    ) { ActionText(stringResource(R.string.exchange_export), enabled = !working) }
    Row(
        Modifier.fillMaxWidth().clickable(enabled = !working) {
            open.launch(arrayOf("application/x-sqlite3", "application/octet-stream", "application/vnd.sqlite3", "*/*") to PickerMemory.get(context, PickerMemory.BACKUP))
        }.padding(horizontal = 16.dp, vertical = 12.dp),
    ) { ActionText(stringResource(R.string.exchange_import), enabled = !working) }
    Hint(stringResource(R.string.exchange_hint))

    // The library for players: the Rockbox plugin and the web list generator read it.
    val prefs = remember { context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE) }
    var playerBase by remember { mutableStateOf(prefs.getString("player_base", null) ?: "/<microSD1>/Music") }
    var askBase by remember { mutableStateOf(false) }
    val noAnalysis = stringResource(R.string.player_db_none)
    val notWritten = stringResource(R.string.player_db_not_written)
    // The files exported to before (a player, a card…), each with its music folder, newest first.
    var places by remember { mutableStateOf(PlayerPlaces.read(prefs)) }
    fun exportTo(targets: List<PlayerPlaces.Place>) {
        places = PlayerPlaces.remember(prefs, targets)
        working = true
        scope.launch {
            val lines = ArrayList<String>()
            for (t in targets) {
                lines += try {
                    com.sadurni.attacca.library.LibraryTransfer.exportForPlayers(context, t.uri, t.base)?.let { exported.format(it) } ?: noAnalysis
                } catch (e: Exception) {
                    notWritten.format(PlayerPlaces.label(t.uri))
                }
            }
            result = lines.joinToString("\n")
            working = false
        }
    }
    val createPlayerDb = rememberLauncherForActivityResult(CreateDocumentAt("application/octet-stream")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        PickerMemory.put(context, PickerMemory.PLAYER_DB, uri)
        // Kept, to write the same file again later without choosing it.
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        exportTo(listOf(PlayerPlaces.Place(uri, playerBase.trim())))
    }
    Row(
        Modifier.fillMaxWidth().clickable(enabled = !working) { askBase = true }.padding(horizontal = 16.dp, vertical = 12.dp),
    ) { ActionText(stringResource(R.string.player_db_export), enabled = !working) }
    Hint(stringResource(R.string.player_db_hint))
    if (askBase) {
        AlertDialog(
            onDismissRequest = { askBase = false },
            title = { Text(stringResource(R.string.player_db_export)) },
            text = {
                Column {
                    androidx.compose.material3.OutlinedTextField(
                        playerBase,
                        { playerBase = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.player_db_base)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Hint(stringResource(R.string.player_db_base_hint))
                    if (places.isNotEmpty()) {
                        Text(stringResource(R.string.player_db_before), fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
                        for (place in places) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f).clickable { playerBase = place.base }.padding(vertical = 6.dp)) {
                                    Text(PlayerPlaces.label(place.uri), maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                    Hint(place.base)
                                }
                                TextButton(onClick = { askBase = false; exportTo(listOf(place)) }) { Text(stringResource(R.string.player_db_again)) }
                                IconButton(onClick = { places = PlayerPlaces.forget(prefs, place) }) {
                                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.player_db_forget))
                                }
                            }
                        }
                        if (places.size > 1) TextButton(onClick = { askBase = false; exportTo(places) }) { Text(stringResource(R.string.player_db_all)) }
                        Hint(stringResource(R.string.player_db_places_hint))
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = playerBase.isNotBlank(), onClick = {
                    askBase = false
                    prefs.edit().putString("player_base", playerBase.trim()).apply()
                    createPlayerDb.launch("attaccaplg.${com.sadurni.attacca.library.PlayerDbFormat.EXTENSION}" to PickerMemory.get(context, PickerMemory.PLAYER_DB))
                }) { Text(stringResource(R.string.player_db_save)) }
            },
            dismissButton = { TextButton(onClick = { askBase = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (working) {
        AlertDialog(
            onDismissRequest = {},
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.CircularProgressIndicator(Modifier.size(24.dp))
                    Text(stringResource(R.string.exchange_working), modifier = Modifier.padding(start = 16.dp))
                }
            },
            confirmButton = {},
        )
    }
    result?.let { message ->
        AlertDialog(
            onDismissRequest = { result = null },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { result = null }) { Text(stringResource(R.string.ok)) } },
        )
    }
}

/** Theme and colors, then the player screen. */
@Composable
private fun AppearancePage(settings: com.sadurni.attacca.settings.AppSettings, onEditTheme: (String) -> Unit, onThemeGallery: () -> Unit) {
    val ui by settings.ui.collectAsStateWithLifecycle()
    ThemesSection(settings, onEditTheme, onThemeGallery)
    SwitchRow(stringResource(R.string.dynamic_accent), ui.dynamicAccent, { v ->
        settings.updateUi { it.copy(dynamicAccent = v) }
    })
    SwitchRow(stringResource(R.string.blurred_background), ui.blurredBackground, { v ->
        settings.updateUi { it.copy(blurredBackground = v) }
    })
    SwitchRow(stringResource(R.string.cover_behind_screens), ui.coverBehindScreens, { v ->
        settings.updateUi { it.copy(coverBehindScreens = v) }
    })
    Hint(stringResource(R.string.cover_behind_screens_hint))

    SectionTitle(stringResource(R.string.section_player_screen))
    SwitchRow(stringResource(R.string.remaining_time), ui.showRemainingTime, { v ->
        settings.updateUi { it.copy(showRemainingTime = v) }
    })
    SwitchRow(stringResource(R.string.over_lock_screen), ui.overLockScreen, { v ->
        settings.updateUi { it.copy(overLockScreen = v) }
    })
    Hint(stringResource(R.string.over_lock_screen_hint))
    Text(stringResource(R.string.lock_wallpaper), modifier = Modifier.padding(start = 16.dp, top = 12.dp))
    for ((mode, label) in listOf(
        com.sadurni.attacca.playback.LockWallpaper.Mode.OFF to R.string.lock_wallpaper_off,
        com.sadurni.attacca.playback.LockWallpaper.Mode.COVER to R.string.lock_wallpaper_cover,
        com.sadurni.attacca.playback.LockWallpaper.Mode.ORAMA to R.string.lock_wallpaper_orama,
    )) {
        RadioRow(stringResource(label), ui.lockWallpaper == mode) { settings.updateUi { it.copy(lockWallpaper = mode) } }
    }
    Hint(stringResource(R.string.lock_wallpaper_hint))

    // The "Now playing" widget, placed by the launcher when it supports pinning.
    val widgetContext = LocalContext.current
    val widgets = remember { android.appwidget.AppWidgetManager.getInstance(widgetContext) }
    if (widgets.isRequestPinAppWidgetSupported) {
        SectionTitle(stringResource(R.string.widget_section))
        TextButton(
            onClick = {
                widgets.requestPinAppWidget(
                    android.content.ComponentName(widgetContext, com.sadurni.attacca.playback.NowPlayingWidget::class.java),
                    null,
                    null,
                )
            },
            modifier = Modifier.padding(horizontal = 8.dp),
        ) { Text(stringResource(R.string.widget_add)) }
        Hint(stringResource(R.string.widget_description))
        // Made big, the cover or O'rama's landscape.
        Text(stringResource(R.string.widget_big), modifier = Modifier.padding(start = 16.dp, top = 8.dp))
        for ((big, label) in listOf(
            com.sadurni.attacca.playback.NowPlayingWidget.Big.COVER to R.string.widget_big_cover,
            com.sadurni.attacca.playback.NowPlayingWidget.Big.ORAMA to R.string.widget_big_orama,
        )) {
            RadioRow(stringResource(label), ui.widgetBig == big) {
                settings.updateUi { it.copy(widgetBig = big) }
                com.sadurni.attacca.playback.NowPlayingWidget.setBig(widgetContext, big)
            }
        }
    }

    SectionTitle(stringResource(R.string.section_tips))
    SwitchRow(stringResource(R.string.tips_show), ui.showTips, { v ->
        settings.updateUi { it.copy(showTips = v) }
    })
    Hint(stringResource(R.string.tips_show_hint))
    val tipsContext = LocalContext.current
    TextButton(
        onClick = {
            com.sadurni.attacca.ui.common.TipsStore.resetAll(tipsContext)
            settings.updateUi { it.copy(showTips = true) }
            Toast.makeText(tipsContext, R.string.tips_reset_done, Toast.LENGTH_SHORT).show()
        },
        modifier = Modifier.padding(horizontal = 8.dp),
    ) { Text(stringResource(R.string.tips_reset)) }

    SectionTitle(stringResource(R.string.section_keep_screen_on))
    val keepOptions = listOf(
        com.sadurni.attacca.settings.KeepScreenOn.OFF to R.string.keep_screen_off,
        com.sadurni.attacca.settings.KeepScreenOn.PLAYER to R.string.keep_screen_player,
        com.sadurni.attacca.settings.KeepScreenOn.ALWAYS to R.string.keep_screen_always,
    )
    for ((mode, label) in keepOptions) {
        RadioRow(stringResource(label), ui.keepScreenOn == mode) {
            settings.updateUi { it.copy(keepScreenOn = mode) }
        }
    }
    Hint(stringResource(R.string.keep_screen_hint))

    SectionTitle(stringResource(R.string.section_waveform))
    val waveformModes = listOf(
        WaveformMode.TOP_HALF to R.string.waveform_top_half,
        WaveformMode.MIRRORED to R.string.waveform_mirrored,
        WaveformMode.OFF to R.string.waveform_off,
    )
    for ((mode, label) in waveformModes) {
        RadioRow(stringResource(label), ui.waveformMode == mode) {
            settings.updateUi { it.copy(waveformMode = mode) }
        }
    }
    // O'rama in the waveform's place; the waveform keeps its own look, small between the times.
    SwitchRow(stringResource(R.string.waveform_orama), ui.oramaStrip, { on -> settings.updateUi { it.copy(oramaStrip = on) } })
}

/** Base folder of the music, which makes exported lists portable between devices. */
@Composable
private fun MusicFolderSection(settings: com.sadurni.attacca.settings.AppSettings) {
    val context = LocalContext.current
    val scanner = context.app.libraryScanner
    val progress by scanner.progress.collectAsStateWithLifecycle()
    val last by scanner.last.collectAsStateWithLifecycle()
    var folder by remember { mutableStateOf(settings.musicFolder) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { PickerMemory.put(context, PickerMemory.MUSIC_FOLDER, it) }
        val path = uri?.let { UriPaths.treePath(it) }
        if (path != null) {
            settings.musicFolder = path
            folder = path
            scanner.scan(java.io.File(path))
        } else if (uri != null) {
            Toast.makeText(context, R.string.music_folder_unsupported, Toast.LENGTH_SHORT).show()
        }
    }
    Row(
        Modifier.fillMaxWidth().clickable { pick.launch(PickerMemory.get(context, PickerMemory.MUSIC_FOLDER)) }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.music_folder), fontSize = AttaccaType.body)
            Text(
                folder ?: stringResource(R.string.music_folder_none),
                fontSize = AttaccaType.secondary,
                color = LocalAttaccaColors.current.textGrayed,
            )
        }
        if (folder != null) {
            TextButton(onClick = {
                settings.musicFolder = null
                folder = null
            }) { Text(stringResource(R.string.music_folder_clear)) }
        }
    }
    Hint(stringResource(R.string.music_folder_hint))
    val current = folder
    if (current != null) {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = progress == null) { scanner.scan(java.io.File(current)) }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                ActionText(stringResource(R.string.library_scan), enabled = progress == null)
                val p = progress
                val status = when {
                    p != null && p.total == 0 -> stringResource(R.string.library_scan_finding)
                    p != null -> com.sadurni.attacca.ui.common.analysisProgressText(R.string.library_scan_progress, p.done, p.total, p.remainingMs)
                    last != null -> stringResource(
                        R.string.library_scan_done,
                        last!!.tracks,
                        android.text.format.DateFormat.getDateFormat(context).format(java.util.Date(last!!.finishedAt)) + " " +
                            android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(last!!.finishedAt)),
                    )
                    else -> stringResource(R.string.library_scan_never)
                }
                Text(status, fontSize = AttaccaType.secondary, color = LocalAttaccaColors.current.textGrayed)
            }
        }
        progress?.let { p ->
            if (p.total > 0) {
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { p.done.toFloat() / p.total },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
            }
        }
        Hint(stringResource(R.string.library_scan_hint))
        LibraryStatsSection(current, last)
        DamagedSection(current)
    }
    IgnoredSection()
}

/** How often a backup is made by itself, and how many are kept. */
@Composable
private fun AutoBackupSection() {
    val context = LocalContext.current
    val state by com.sadurni.attacca.settings.AutoBackup.state.collectAsStateWithLifecycle()
    val pickFolder = rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { PickerMemory.put(context, PickerMemory.BACKUP_FOLDER, it) }
        val path = uri?.let { com.sadurni.attacca.library.UriPaths.treePath(it) }
        if (path != null) com.sadurni.attacca.settings.AutoBackup.setFolder(context, path)
        else if (uri != null) Toast.makeText(context, R.string.music_folder_unsupported, Toast.LENGTH_SHORT).show()
    }
    SectionTitle(stringResource(R.string.auto_backup_title))
    for ((every, label) in listOf(
        com.sadurni.attacca.settings.BackupEvery.OFF to R.string.off,
        com.sadurni.attacca.settings.BackupEvery.DAILY to R.string.auto_backup_daily,
        com.sadurni.attacca.settings.BackupEvery.WEEKLY to R.string.auto_backup_weekly,
        com.sadurni.attacca.settings.BackupEvery.MONTHLY to R.string.auto_backup_monthly,
    )) {
        RadioRow(stringResource(label), state.every == every) {
            com.sadurni.attacca.settings.AutoBackup.set(context, every = every)
            if (every != com.sadurni.attacca.settings.BackupEvery.OFF) context.app.backUpIfDue()
        }
    }
    if (state.every != com.sadurni.attacca.settings.BackupEvery.OFF) {
        ValueSlider(
            label = stringResource(R.string.auto_backup_keep),
            value = state.keep.toFloat(),
            range = 1f..20f,
            step = 1f,
            format = { "${it.toInt()}" },
            onChange = { v -> com.sadurni.attacca.settings.AutoBackup.set(context, keep = v.toInt()) },
        )
        val last = state.lastMs.takeIf { it > 0 }?.let {
            android.text.format.DateFormat.getMediumDateFormat(context).format(java.util.Date(it)) + " " +
                android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(it))
        }
        Row(
            Modifier.fillMaxWidth().clickable {
                pickFolder.launch(PickerMemory.get(context, PickerMemory.BACKUP_FOLDER))
            }.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.auto_backup_folder), fontSize = AttaccaType.body)
                Text(com.sadurni.attacca.settings.AutoBackup.directory.absolutePath, fontSize = AttaccaType.secondary, color = LocalAttaccaColors.current.textGrayed)
            }
            if (state.folder != null) {
                TextButton(onClick = { com.sadurni.attacca.settings.AutoBackup.setFolder(context, null) }) {
                    Text(stringResource(R.string.auto_backup_folder_default))
                }
            }
        }
        Hint(last?.let { stringResource(R.string.auto_backup_last, it) } ?: stringResource(R.string.auto_backup_never))
    }
    Hint(stringResource(R.string.auto_backup_hint))
}

/** Folders and files left out of the library altogether, to bring back. */
@Composable
private fun IgnoredSection() {
    val context = LocalContext.current
    val ignored by com.sadurni.attacca.library.IgnoredPaths.all.collectAsStateWithLifecycle()
    SectionTitle(stringResource(R.string.ignored_title))
    if (ignored.isEmpty()) Hint(stringResource(R.string.ignored_none))
    for (path in ignored) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(path, fontSize = AttaccaType.body, modifier = Modifier.weight(1f))
            IconButton(onClick = { com.sadurni.attacca.library.IgnoredPaths.toggle(context, path) }) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.ignored_stop))
            }
        }
    }
    Hint(stringResource(R.string.ignored_hint))
}

/** Figures of the music folder from what the scans read (counted again after each scan). */
@Composable
private fun LibraryStatsSection(folder: String, last: com.sadurni.attacca.library.LibraryScanner.Result?) {
    var forgotten by remember { mutableStateOf(0) }
    val stats by androidx.compose.runtime.produceState<com.sadurni.attacca.library.LibraryStats?>(null, folder, last, forgotten) {
        value = withContext(com.sadurni.attacca.Background.dispatcher) {
            runCatching { com.sadurni.attacca.library.TrackSort.db?.stats(folder) }.getOrNull()
        }
    }
    SectionTitle(stringResource(R.string.library_stats))
    val s = stats
    if (s == null) {
        Hint("…")
        return
    }
    if (s.tracks == 0) {
        Hint(stringResource(R.string.library_stats_empty))
        return
    }
    val n = { v: Int -> "%,d".format(v) }
    StatRow(stringResource(R.string.library_stats_tracks), n(s.tracks))
    StatRow(stringResource(R.string.library_stats_artists), n(s.artists))
    StatRow(stringResource(R.string.library_stats_album_artists), n(s.albumArtists))
    StatRow(stringResource(R.string.library_stats_albums), n(s.albums))
    StatRow(stringResource(R.string.library_stats_genres), n(s.genres))
    if (s.firstYear != null && s.lastYear != null) {
        StatRow(stringResource(R.string.library_stats_years), if (s.firstYear == s.lastYear) "${s.firstYear}" else "${s.firstYear}–${s.lastYear}")
    }
    StatRow(stringResource(R.string.library_stats_time), com.sadurni.attacca.ui.common.formatTotalTime(s.durationMs))
    StatRow(stringResource(R.string.library_stats_size), com.sadurni.attacca.ui.common.formatSize(s.bytes))
    if (s.untagged > 0) StatRow(stringResource(R.string.library_stats_untagged), n(s.untagged))
    val damaged by com.sadurni.attacca.library.DamagedTracks.all.collectAsStateWithLifecycle()
    val damagedHere = remember(damaged, folder) { com.sadurni.attacca.library.DamagedTracks.under(folder).size }
    if (damagedHere > 0) StatRow(stringResource(R.string.damaged_title), n(damagedHere))
    StatRow(
        stringResource(R.string.library_stats_formats),
        s.formats.joinToString("\n") { (ext, count) -> "$ext  ${n(count)} (${(count * 100 + s.tracks / 2) / s.tracks} %)" },
    )
    if (s.missing > 0) {
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        StatRow(stringResource(R.string.library_stats_missing), n(s.missing))
        Hint(stringResource(R.string.library_stats_missing_hint))
        TextButton(onClick = {
            scope.launch {
                withContext(com.sadurni.attacca.Background.dispatcher) {
                    runCatching { com.sadurni.attacca.library.TrackSort.db?.forgetMissing(folder) }
                }
                forgotten++
            }
        }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.library_stats_forget_missing)) }
    }
    Hint(stringResource(R.string.library_stats_hint))
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp)) {
        Text(label, fontSize = AttaccaType.body, modifier = Modifier.weight(1f))
        Text(value, fontSize = AttaccaType.body, textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

/** A pass over the music folder started by hand: its name, then what it says or how far it got. */
@Composable
private fun FolderPassRow(
    pass: com.sadurni.attacca.chain.ChainAnalyzer.Pass,
    progress: com.sadurni.attacca.chain.ChainAnalyzer.Progress?,
    enabled: Boolean,
    title: Int,
    summary: Int,
    progressText: Int,
    onStart: () -> Unit,
    onCancel: () -> Unit,
) {
    val mine = progress?.takeIf { it.pass == pass }
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = enabled && progress == null, onClick = onStart)
            .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            ActionText(stringResource(title), enabled = enabled && progress == null)
            Text(
                when {
                    mine != null && mine.total == 0 -> stringResource(R.string.library_scan_finding)
                    mine != null -> com.sadurni.attacca.ui.common.analysisProgressText(progressText, mine.done, mine.total, mine.remainingMs)
                    else -> stringResource(summary)
                },
                fontSize = AttaccaType.secondary,
                color = LocalAttaccaColors.current.textGrayed,
            )
        }
        if (mine != null) TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
    }
    if (mine != null && mine.total > 0) {
        androidx.compose.material3.LinearProgressIndicator(
            progress = { mine.done.toFloat() / mine.total },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
    }
}

/**
 * Chaining: whether the music folder is analysed by itself in the background, an analysis on
 * demand, and how far apart tracks by the same artist are kept.
 */
@Composable
private fun ChainingPage(settings: com.sadurni.attacca.settings.AppSettings) {
    val context = LocalContext.current
    val analyzer = context.app.chainAnalyzer
    val chain by settings.chain.collectAsStateWithLifecycle()
    val progress by analyzer.libraryProgress.collectAsStateWithLifecycle()
    val last by analyzer.last.collectAsStateWithLifecycle()
    val folder = settings.musicFolder
    var pendingEdge by remember { mutableStateOf<Int?>(null) }
    var confirmReanalyze by remember { mutableStateOf(false) }

    SectionTitle(stringResource(R.string.chain_analysis))
    SwitchRow(stringResource(R.string.chain_auto), chain.autoAnalyze, onChange = { on ->
        settings.updateChain { it.copy(autoAnalyze = on) }
        if (on && folder != null) analyzer.analyzeLibrary(java.io.File(folder))
    }, enabled = folder != null)
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = folder != null && progress == null) { folder?.let { analyzer.analyzeLibrary(java.io.File(it)) } }
            .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            ActionText(stringResource(R.string.chain_analyze_now), enabled = folder != null && progress == null)
            val p = progress
            val l = last
            val status = when {
                folder == null -> stringResource(R.string.chain_needs_music_folder)
                p != null && p.total == 0 -> stringResource(R.string.library_scan_finding)
                p != null && p.pass == com.sadurni.attacca.chain.ChainAnalyzer.Pass.SILENCES -> stringResource(R.string.chain_silences_running)
                p != null && p.pass == com.sadurni.attacca.chain.ChainAnalyzer.Pass.WHOLE -> stringResource(R.string.chain_whole_running)
                p != null -> com.sadurni.attacca.ui.common.analysisProgressText(R.string.chain_library_progress, p.done, p.total, p.remainingMs)
                l != null -> stringResource(
                    R.string.chain_library_done,
                    l.analysed,
                    l.total,
                    android.text.format.DateFormat.getDateFormat(context).format(java.util.Date(l.finishedAt)) + " " +
                        android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(l.finishedAt)),
                )
                else -> stringResource(R.string.chain_library_never)
            }
            Text(status, fontSize = AttaccaType.secondary, color = LocalAttaccaColors.current.textGrayed)
        }
        if (progress != null) {
            TextButton(onClick = { analyzer.cancelLibrary() }) { Text(stringResource(R.string.cancel)) }
        }
    }
    progress?.let { p ->
        if (p.total > 0 && p.pass == com.sadurni.attacca.chain.ChainAnalyzer.Pass.CHAIN) {
            androidx.compose.material3.LinearProgressIndicator(
                progress = { p.done.toFloat() / p.total },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }
    }
    SwitchRow(stringResource(R.string.chain_silences), chain.measureSilences, onChange = { on ->
        settings.updateChain { it.copy(measureSilences = on) }
    })
    Hint(stringResource(R.string.chain_silences_hint))
    // The silences of the whole folder at once (fast), and the whole analysis (slow), by hand.
    FolderPassRow(
        com.sadurni.attacca.chain.ChainAnalyzer.Pass.SILENCES, progress, folder != null,
        R.string.chain_measure_silences, R.string.chain_measure_silences_summary, R.string.chain_silence_progress,
        onStart = { folder?.let { analyzer.measureSilencesOf(java.io.File(it)) } }, onCancel = analyzer::cancelLibrary,
    )
    FolderPassRow(
        com.sadurni.attacca.chain.ChainAnalyzer.Pass.WHOLE, progress, folder != null,
        R.string.chain_measure_whole, R.string.chain_measure_whole_summary, R.string.chain_whole_progress,
        onStart = { folder?.let { analyzer.measureWholeOf(java.io.File(it)) } }, onCancel = analyzer::cancelLibrary,
    )
    ValueSlider(
        label = stringResource(R.string.chain_edge_seconds),
        value = chain.edgeSeconds.toFloat(),
        range = com.sadurni.attacca.chain.EdgeFeatures.EDGE_SECONDS_RANGE.first.toFloat()..
            com.sadurni.attacca.chain.EdgeFeatures.EDGE_SECONDS_RANGE.last.toFloat(),
        step = 1f,
        format = { "${it.toInt()} s" },
        onChange = { v -> if (v.toInt() != chain.edgeSeconds) pendingEdge = v.toInt() },
    )
    Hint(stringResource(R.string.chain_edge_hint))
    ValueSlider(
        label = stringResource(R.string.chain_threads),
        value = chain.analysisThreads.toFloat(),
        range = 1f..com.sadurni.attacca.Background.maxAnalysisThreads.toFloat().coerceAtLeast(2f),
        step = 1f,
        format = { it.toInt().toString() },
        onChange = { v -> settings.updateChain { it.copy(analysisThreads = v.toInt()) } },
    )
    Hint(stringResource(R.string.chain_threads_hint, com.sadurni.attacca.Background.maxAnalysisThreads))
    SwitchRow(stringResource(R.string.chain_keep_screen_on), chain.keepScreenOn, onChange = { on ->
        settings.updateChain { it.copy(keepScreenOn = on) }
    })
    Hint(stringResource(R.string.chain_keep_screen_on_hint))
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = folder != null) { confirmReanalyze = true }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column {
            ActionText(stringResource(R.string.chain_reanalyze), enabled = folder != null)
            Text(stringResource(R.string.chain_reanalyze_summary), fontSize = AttaccaType.secondary, color = LocalAttaccaColors.current.textGrayed)
        }
    }
    Hint(stringResource(R.string.chain_analysis_hint))
    if (confirmReanalyze) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmReanalyze = false },
            title = { Text(stringResource(R.string.chain_reanalyze)) },
            text = { Text(stringResource(R.string.chain_reanalyze_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReanalyze = false
                    folder?.let { analyzer.reanalyzeLibrary(java.io.File(it)) }
                }) { Text(stringResource(R.string.chain_reanalyze_go)) }
            },
            dismissButton = { TextButton(onClick = { confirmReanalyze = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    pendingEdge?.let { seconds ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingEdge = null },
            title = { Text(stringResource(R.string.chain_edge_seconds)) },
            text = { Text(stringResource(R.string.chain_edge_confirm, seconds)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingEdge = null
                    settings.updateChain { it.copy(edgeSeconds = seconds) }
                    // Results with the old length no longer count.
                    if (folder != null) analyzer.reanalyzeLibrary(java.io.File(folder)) else analyzer.forgetLastPass()
                }) { Text(stringResource(R.string.chain_edge_change)) }
            },
            dismissButton = { TextButton(onClick = { pendingEdge = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    SectionTitle(stringResource(R.string.chain_order))
    val lists = context.app.chainLists
    var listCount by remember { mutableStateOf(lists.listCount.toString()) }
    com.sadurni.attacca.ui.chain.TrackCountField(listCount, { text ->
        listCount = text
        text.toIntOrNull()?.takeIf { it >= 2 }?.let { lists.listCount = com.sadurni.attacca.ui.chain.listCountOf(text, lists.listCount) }
    }, Modifier.padding(horizontal = 16.dp))
    Hint(stringResource(R.string.tracks_per_list_hint))
    SwitchRow(stringResource(R.string.chain_save_made), chain.saveMadeLists, onChange = { on ->
        settings.updateChain { it.copy(saveMadeLists = on) }
    })
    Hint(stringResource(R.string.chain_save_made_hint))
    val offLabel = stringResource(R.string.off)
    ValueSlider(
        label = stringResource(R.string.chain_quick_not_played),
        value = chain.quickNotPlayedDays.toFloat(),
        range = 0f..com.sadurni.attacca.settings.MAX_NOT_PLAYED_DAYS.toFloat(),
        step = 1f,
        format = { if (it.toInt() == 0) offLabel else it.toInt().toString() },
        onChange = { v -> settings.updateChain { it.copy(quickNotPlayedDays = v.toInt()) } },
    )
    Hint(stringResource(R.string.chain_quick_not_played_hint))
    // The listener's taste: what counts most when one song leads into the next.
    val taste by settings.chainTaste.collectAsStateWithLifecycle()
    var tasteGuide by remember { mutableStateOf(false) }
    CategoryRow(
        Icons.Rounded.SwapHoriz,
        stringResource(R.string.taste_guide_open),
        if (taste.isDefault) stringResource(R.string.taste_guide_hint)
        else stringResource(
            R.string.taste_custom,
            (taste.share(com.sadurni.attacca.chain.TasteGroup.TEMPO) * 100).roundToInt(),
            (taste.share(com.sadurni.attacca.chain.TasteGroup.SOUND) * 100).roundToInt(),
            (taste.share(com.sadurni.attacca.chain.TasteGroup.ENERGY) * 100).roundToInt(),
            (taste.share(com.sadurni.attacca.chain.TasteGroup.KEY) * 100).roundToInt(),
        ),
    ) { tasteGuide = true }
    if (tasteGuide) TasteGuide { tasteGuide = false }
    ValueSlider(
        label = stringResource(R.string.chain_artist_spacing),
        value = chain.artistSpacing.toFloat(),
        range = 0f..10f,
        step = 1f,
        format = { if (it == 0f) "-" else it.toInt().toString() },
        onChange = { v -> settings.updateChain { it.copy(artistSpacing = v.toInt()) } },
    )
    Hint(stringResource(R.string.chain_hint))

    SectionTitle(stringResource(R.string.random_list))
    SwitchRow(stringResource(R.string.random_ask_mood), chain.randomAskMood, onChange = { on ->
        settings.updateChain { it.copy(randomAskMood = on) }
    })
    Hint(stringResource(R.string.random_ask_mood_hint))
    SwitchRow(stringResource(R.string.random_shake), chain.randomShake, onChange = { on ->
        settings.updateChain { it.copy(randomShake = on) }
    })
    if (chain.randomShake) {
        SegmentedChoice(
            listOf(
                com.sadurni.attacca.settings.ShakeKind.SIDEWAYS to stringResource(R.string.random_shake_sideways),
                com.sadurni.attacca.settings.ShakeKind.BACK_AND_FORTH to stringResource(R.string.random_shake_back_forth),
                com.sadurni.attacca.settings.ShakeKind.ANY to stringResource(R.string.random_shake_any),
            ),
            chain.shakeKind,
        ) { v -> settings.updateChain { it.copy(shakeKind = v) } }
        SegmentedChoice(
            listOf(1 to stringResource(R.string.random_shake_light), 2 to stringResource(R.string.random_shake_medium), 3 to stringResource(R.string.random_shake_strong)),
            chain.shakeStrength,
        ) { v -> settings.updateChain { it.copy(shakeStrength = v) } }
    }
    Hint(stringResource(R.string.random_shake_hint))
}

/**
 * The inclusion list: an M3U8 whose tracks are the only ones allowed (allow list) or the ones
 * left out (block list) when "Only included" is on in the generating dialogs.
 */
@Composable
private fun InclusionSection(settings: com.sadurni.attacca.settings.AppSettings) {
    val context = LocalContext.current
    val chain by settings.chain.collectAsStateWithLifecycle()
    InclusionSetsSection(settings)
    val pick = rememberLauncherForActivityResult(OpenDocumentAt()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        PickerMemory.put(context, PickerMemory.INCLUSION_LIST, uri)
        val file = UriPaths.resolve(context, uri)
        if (file != null) {
            settings.updateChain { it.copy(inclusionList = file.absolutePath) }
        } else {
            Toast.makeText(context, R.string.inclusion_unsupported, Toast.LENGTH_SHORT).show()
        }
    }
    Row(
        Modifier.fillMaxWidth().clickable {
            pick.launch(arrayOf("audio/x-mpegurl", "audio/mpegurl", "application/vnd.apple.mpegurl", "application/octet-stream", "text/plain", "*/*") to PickerMemory.get(context, PickerMemory.INCLUSION_LIST))
        }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.inclusion_list), fontSize = AttaccaType.body)
            Text(
                chain.inclusionList ?: stringResource(R.string.inclusion_none),
                fontSize = AttaccaType.secondary,
                color = LocalAttaccaColors.current.textGrayed,
            )
        }
        if (chain.inclusionList != null) {
            TextButton(onClick = { settings.updateChain { it.copy(inclusionList = null) } }) {
                Text(stringResource(R.string.music_folder_clear))
            }
        }
    }
    RadioRow(stringResource(R.string.inclusion_allow), chain.inclusionMode == com.sadurni.attacca.library.InclusionMode.ALLOW) {
        settings.updateChain { it.copy(inclusionMode = com.sadurni.attacca.library.InclusionMode.ALLOW) }
    }
    RadioRow(stringResource(R.string.inclusion_block), chain.inclusionMode == com.sadurni.attacca.library.InclusionMode.BLOCK) {
        settings.updateChain { it.copy(inclusionMode = com.sadurni.attacca.library.InclusionMode.BLOCK) }
    }
    Hint(stringResource(R.string.inclusion_hint))
    SwitchRow(
        stringResource(R.string.lists_only_included),
        chain.listsOnlyIncluded && chain.inclusionList != null,
        { on -> settings.updateChain { it.copy(listsOnlyIncluded = on) } },
        enabled = chain.inclusionList != null,
    )
    Hint(stringResource(R.string.lists_only_included_hint))
    com.sadurni.attacca.ui.common.ExcludedLegend()
    val scope = rememberCoroutineScope()
    // What the rules and the resulting list hold, and the screen to look through and maintain them.
    var changes by remember { mutableStateOf(0) }
    var editing by remember { mutableStateOf(false) }
    val overview by androidx.compose.runtime.produceState<com.sadurni.attacca.library.InclusionStore.Overview?>(null, chain.inclusionList, changes) {
        value = runCatching { context.app.inclusionStore.overview() }.getOrNull()
    }
    if (chain.inclusionList != null) {
        Row(
            Modifier.fillMaxWidth().clickable { editing = true }.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Column {
                ActionText(stringResource(R.string.inclusion_manage))
                val lines = overview?.let { com.sadurni.attacca.ui.chain.inclusionSummary(it) } ?: listOf("…")
                for (line in lines) Text(line, fontSize = AttaccaType.secondary, color = LocalAttaccaColors.current.textGrayed)
            }
        }
    }
    if (editing) {
        com.sadurni.attacca.ui.chain.InclusionEditor(onDismiss = { editing = false; changes++ }, onChanged = { changes++ })
    }
    if (chain.inclusionList != null && context.app.inclusionStore.hasRules()) {
        Row(
            Modifier.fillMaxWidth().clickable {
                scope.launch {
                    val count = runCatching { context.app.inclusionStore.refresh() }.getOrNull()
                    changes++
                    if (count != null) {
                        Toast.makeText(context, context.resources.getQuantityString(R.plurals.inclusion_count, count, count), Toast.LENGTH_SHORT).show()
                    }
                }
            }.padding(horizontal = 16.dp, vertical = 12.dp),
        ) { ActionText(stringResource(R.string.inclusion_refresh)) }
    }
    var confirmReimport by remember { mutableStateOf(false) }
    if (chain.inclusionList != null) {
        Row(
            Modifier.fillMaxWidth().clickable { confirmReimport = true }.padding(horizontal = 16.dp, vertical = 12.dp),
        ) { ActionText(stringResource(R.string.inclusion_reimport)) }
    }
    Hint(stringResource(R.string.inclusion_rules_hint))
    if (confirmReimport) {
        AlertDialog(
            onDismissRequest = { confirmReimport = false },
            title = { Text(stringResource(R.string.inclusion_reimport)) },
            text = { Text(stringResource(R.string.inclusion_reimport_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReimport = false
                    scope.launch {
                        val count = runCatching { context.app.inclusionStore.reimport() }.getOrNull()
                        changes++
                        Toast.makeText(
                            context,
                            if (count == null) context.getString(R.string.inclusion_failed) else context.resources.getQuantityString(R.plurals.inclusion_count, count, count),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }) { Text(stringResource(R.string.inclusion_reimport_apply)) }
            },
            dismissButton = { TextButton(onClick = { confirmReimport = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/**
 * The inclusion sets: which one is in use (or none: everything), and making, renaming and
 * forgetting them. What follows on the page is the set in use.
 */
@Composable
private fun InclusionSetsSection(settings: com.sadurni.attacca.settings.AppSettings) {
    val chain by settings.chain.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<String?>(null) } // "new" | "rename" | "copy"
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val app = LocalContext.current.app
    SectionTitle(stringResource(R.string.inclusion_sets_title))
    RadioRow(stringResource(R.string.inclusion_everything), chain.activeSet == null) { com.sadurni.attacca.settings.InclusionSets.select(settings, null) }
    for (set in chain.inclusionSets) {
        RadioRow(set.name, chain.activeSet == set.id) { com.sadurni.attacca.settings.InclusionSets.select(settings, set.id) }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        TextButton(onClick = { dialog = "new" }) { Text(stringResource(R.string.inclusion_set_new)) }
        if (chain.activeSet != null) {
            TextButton(onClick = { dialog = "rename" }) { Text(stringResource(R.string.profile_rename)) }
            TextButton(onClick = { dialog = "copy" }) { Text(stringResource(R.string.inclusion_set_duplicate)) }
            TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.profile_delete)) }
        }
    }
    Hint(stringResource(R.string.inclusion_sets_hint))
    val activeName = com.sadurni.attacca.settings.InclusionSets.nameOf(chain, chain.activeSet).orEmpty()
    when (dialog) {
        "new" -> NameDialog(stringResource(R.string.inclusion_set_new), "", onConfirm = { name ->
            com.sadurni.attacca.settings.InclusionSets.create(settings, name)
            dialog = null
        }, onDismiss = { dialog = null })
        "copy" -> NameDialog(stringResource(R.string.inclusion_set_duplicate), stringResource(R.string.inclusion_set_copy_name, activeName), onConfirm = { name ->
            val id = chain.activeSet
            dialog = null
            if (id != null) scope.launch { runCatching { app.inclusionStore.duplicate(id, name) } }
        }, onDismiss = { dialog = null })
        "rename" -> NameDialog(stringResource(R.string.profile_rename), activeName, onConfirm = { name ->
            chain.activeSet?.let { com.sadurni.attacca.settings.InclusionSets.rename(settings, it, name) }
            dialog = null
        }, onDismiss = { dialog = null })
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.profile_delete)) },
            text = { Text(stringResource(R.string.inclusion_set_delete_confirm, activeName)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    chain.activeSet?.let { com.sadurni.attacca.settings.InclusionSets.delete(settings, it) }
                }) { Text(stringResource(R.string.profile_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (chain.activeSet != null) SectionTitle(activeName)
}

/** Backup of settings, caches and lists to a zip, and restore from one. */
@Composable
private fun BackupSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmRestore by remember { mutableStateOf<android.net.Uri?>(null) }
    val create = rememberLauncherForActivityResult(CreateDocumentAt("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        PickerMemory.put(context, PickerMemory.BACKUP, uri)
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { Backup.write(context, it) } != null
                }.getOrDefault(false)
            }
            Toast.makeText(context, if (ok) R.string.backup_done else R.string.backup_failed, Toast.LENGTH_SHORT).show()
        }
    }
    val open = rememberLauncherForActivityResult(OpenDocumentAt()) { uri ->
        uri?.let { PickerMemory.put(context, PickerMemory.BACKUP, it) }
        confirmRestore = uri
    }

    Row(
        Modifier.fillMaxWidth().clickable {
            val date = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.ROOT).format(java.util.Date())
            create.launch("attacca-backup-$date.zip" to PickerMemory.get(context, PickerMemory.BACKUP))
        }.padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        ActionText(stringResource(R.string.backup_create))
    }
    Row(
        Modifier.fillMaxWidth().clickable {
            open.launch(arrayOf("application/zip", "application/octet-stream") to PickerMemory.get(context, PickerMemory.BACKUP))
        }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        ActionText(stringResource(R.string.backup_restore))
    }
    Hint(stringResource(R.string.backup_hint))

    AutoBackupSection()

    LibraryExchangeSection()

    confirmRestore?.let { uri ->
        AlertDialog(
            onDismissRequest = { confirmRestore = null },
            title = { Text(stringResource(R.string.backup_restore)) },
            text = { Text(stringResource(R.string.backup_restore_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestore = null
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            runCatching {
                                context.contentResolver.openInputStream(uri)?.let { Backup.stage(context, it) } ?: false
                            }.getOrDefault(false)
                        }
                        if (ok) {
                            Toast.makeText(context, R.string.backup_restore_restart, Toast.LENGTH_LONG).show()
                            context.app.shutdown()
                        } else {
                            Toast.makeText(context, R.string.backup_invalid, Toast.LENGTH_SHORT).show()
                        }
                    }
                }) { Text(stringResource(R.string.backup_restore_apply)) }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/**
 * The files the library for players was exported to (kept with a lasting permission), each with
 * the music folder as that player sees it: exporting again writes them without choosing anything.
 */
private object PlayerPlaces {
    class Place(val uri: android.net.Uri, val base: String)

    private const val KEY = "player_exports"
    private const val MAX = 8

    fun read(prefs: android.content.SharedPreferences): List<Place> = runCatching {
        val array = org.json.JSONArray(prefs.getString(KEY, null) ?: "[]")
        (0 until array.length()).map { array.getJSONObject(it) }.map { Place(android.net.Uri.parse(it.getString("uri")), it.getString("base")) }
    }.getOrDefault(emptyList())

    private fun write(prefs: android.content.SharedPreferences, list: List<Place>) {
        prefs.edit().putString(KEY, org.json.JSONArray(list.map { org.json.JSONObject().put("uri", it.uri.toString()).put("base", it.base) }).toString()).apply()
    }

    /** [used] first, then the others kept before; returns the new list. */
    fun remember(prefs: android.content.SharedPreferences, used: List<Place>): List<Place> {
        val list = (used + read(prefs).filter { old -> used.none { it.uri == old.uri } }).take(MAX)
        write(prefs, list)
        return list
    }

    fun forget(prefs: android.content.SharedPreferences, place: Place): List<Place> {
        val list = read(prefs).filter { it.uri != place.uri }
        write(prefs, list)
        return list
    }

    /** Where the file is, readably: "Card 1A2B-3C4D: Attacca/attaccaplg.bpcr2" from its document id. */
    fun label(uri: android.net.Uri): String {
        val id = runCatching { android.provider.DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return uri.lastPathSegment ?: uri.toString()
        val volume = id.substringBefore(':', "")
        val path = id.substringAfter(':')
        return if (volume.isEmpty()) path else "$volume: $path"
    }
}

