package com.sadurni.attacca.ui.settings

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sadurni.attacca.R
import com.sadurni.attacca.app
import com.sadurni.attacca.dsp.CrossfadeStyle
import com.sadurni.attacca.library.UriPaths
import com.sadurni.attacca.settings.ProfilesState
import com.sadurni.attacca.settings.SoundTemplate
import com.sadurni.attacca.ui.common.PickerMemory
import com.sadurni.attacca.ui.theme.AttaccaType
import com.sadurni.attacca.ui.theme.LocalAccent
import com.sadurni.attacca.ui.theme.LocalAttaccaColors

/** The steps of the setup guide, in order. */
enum class SetupStep { FOLDER, ANALYSIS, LISTENING, TRANSITIONS, DONE }

/** How tracks follow each other, as the guide offers it. */
private enum class Transition { GAPLESS, AUTOMATIC, CROSSFADE }

/**
 * A few steps through what matters first: the music folder, the analysis, how the music is
 * listened to (a sound template) and how tracks follow each other. Every choice is applied when
 * going on, and everything stays in Settings, where the guide can be run again.
 */
@Composable
fun SetupGuide(start: SetupStep = SetupStep.FOLDER, onDone: () -> Unit) {
    val context = LocalContext.current
    val settings = context.app.settings
    var step by rememberSaveable { mutableStateOf(start) }
    val accent = LocalAccent.current
    val grayed = LocalAttaccaColors.current.textGrayed

    // Choices kept until going on, so going back changes nothing by itself.
    val playback by settings.playback.collectAsStateWithLifecycle()
    val chain by settings.chain.collectAsStateWithLifecycle()
    val trim by settings.trim.collectAsStateWithLifecycle()
    val device by settings.profiles.currentDevice.collectAsStateWithLifecycle()
    var analyse by rememberSaveable { mutableStateOf(true) }
    var keepScreenOn by rememberSaveable { mutableStateOf(chain.keepScreenOn) }
    // The phone's speaker is rarely how music is mostly heard: nothing chosen for it.
    var template by rememberSaveable {
        mutableStateOf(device?.takeIf { it.type != com.sadurni.attacca.settings.OutputType.SPEAKER }?.let { SoundTemplate.suggestedFor(it.type) })
    }
    var transition by rememberSaveable {
        mutableStateOf(
            when {
                playback.crossfadeMs == 0 -> Transition.AUTOMATIC
                playback.crossfadeStyle == CrossfadeStyle.AUTOMATIC -> Transition.AUTOMATIC
                else -> Transition.CROSSFADE
            },
        )
    }
    var keepAlbums by rememberSaveable { mutableStateOf(playback.crossfadeSkipSameAlbum) }
    var trimSilences by rememberSaveable { mutableStateOf(trim.enabled) }

    fun apply(at: SetupStep) {
        when (at) {
            SetupStep.FOLDER -> Unit
            SetupStep.ANALYSIS -> {
                settings.updateChain { it.copy(autoAnalyze = analyse, keepScreenOn = keepScreenOn) }
                val folder = settings.musicFolder
                // A scan still running starts the analysis by itself when it ends.
                if (analyse && folder != null && context.app.libraryScanner.progress.value == null) {
                    context.app.chainAnalyzer.analyzeLibrary(java.io.File(folder))
                }
            }
            SetupStep.LISTENING -> template?.let { settings.profiles.applyTemplateToProfile(ProfilesState.DEFAULT_PROFILE_ID, it) }
            SetupStep.TRANSITIONS -> {
                settings.updatePlayback {
                    when (transition) {
                        Transition.GAPLESS -> it.copy(crossfadeMs = 0, crossfadeSkipSameAlbum = keepAlbums)
                        Transition.AUTOMATIC -> it.copy(
                            crossfadeMs = if (it.crossfadeMs > 0) it.crossfadeMs else AUTOMATIC_MS,
                            crossfadeStyle = CrossfadeStyle.AUTOMATIC,
                            crossfadeSkipSameAlbum = keepAlbums,
                        )
                        Transition.CROSSFADE -> it.copy(
                            crossfadeMs = if (it.crossfadeMs > 0) it.crossfadeMs else CROSSFADE_MS,
                            crossfadeStyle = CrossfadeStyle.CROSSFADE,
                            crossfadeSkipSameAlbum = keepAlbums,
                        )
                    }
                }
                settings.updateTrim { it.copy(enabled = trimSilences) }
            }
            SetupStep.DONE -> Unit
        }
    }

    val steps = SetupStep.entries
    fun next() {
        apply(step)
        if (step == SetupStep.DONE) onDone() else step = steps[step.ordinal + 1]
    }
    BackHandler { if (step.ordinal > start.ordinal) step = steps[step.ordinal - 1] else onDone() }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.setup_title), fontSize = AttaccaType.heading, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (step != SetupStep.DONE) TextButton(onClick = onDone) { Text(stringResource(R.string.setup_skip), color = grayed) }
        }
        // Where the guide is: one dot per step.
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.Center) {
            for (s in steps) {
                Box(
                    Modifier.padding(horizontal = 4.dp).size(if (s == step) 10.dp else 8.dp)
                        .background(if (s.ordinal <= step.ordinal) accent else grayed.copy(alpha = 0.4f), CircleShape),
                )
            }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
                when (step) {
                    SetupStep.FOLDER -> FolderStep()
                    SetupStep.ANALYSIS -> AnalysisStep(analyse, { analyse = it }, keepScreenOn, { keepScreenOn = it })
                    SetupStep.LISTENING -> ListeningStep(template) { template = it }
                    SetupStep.TRANSITIONS -> TransitionsStep(transition, { transition = it }, keepAlbums, { keepAlbums = it }, trimSilences, { trimSilences = it })
                    SetupStep.DONE -> DoneStep()
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (step.ordinal > start.ordinal) {
                TextButton(onClick = { step = steps[step.ordinal - 1] }) { Text(stringResource(R.string.setup_back)) }
            }
            Spacer(Modifier.weight(1f))
            Button(onClick = ::next, colors = ButtonDefaults.buttonColors(containerColor = accent)) {
                Text(stringResource(if (step == SetupStep.DONE) R.string.setup_finish else R.string.setup_next))
            }
        }
    }
}

@Composable
private fun StepTitle(title: String, intro: String) {
    Text(
        title,
        fontSize = AttaccaType.title,
        fontFamily = com.sadurni.attacca.ui.theme.LocalTitleFamily.current,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
    Text(intro, fontSize = AttaccaType.body, modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp))
}

/** The music folder: where it is, how far its scan got, and a way to change it. */
@Composable
private fun FolderStep() {
    val context = LocalContext.current
    val settings = context.app.settings
    val scanner = context.app.libraryScanner
    val progress by scanner.progress.collectAsStateWithLifecycle()
    var folder by rememberSaveable { mutableStateOf(settings.musicFolder) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        PickerMemory.put(context, PickerMemory.MUSIC_FOLDER, uri)
        val path = UriPaths.treePath(uri)
        if (path == null) {
            Toast.makeText(context, R.string.music_folder_unsupported, Toast.LENGTH_SHORT).show()
        } else {
            settings.musicFolder = path
            folder = path
            scanner.scan(java.io.File(path))
        }
    }
    StepTitle(stringResource(R.string.setup_folder_title), stringResource(R.string.setup_folder_intro))
    Row(
        Modifier.fillMaxWidth().clickable { pick.launch(PickerMemory.get(context, PickerMemory.MUSIC_FOLDER)) }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            ActionText(stringResource(if (folder == null) R.string.welcome_choose_folder else R.string.setup_folder_change))
            Text(folder ?: stringResource(R.string.music_folder_none), fontSize = AttaccaType.secondary, color = LocalAttaccaColors.current.textGrayed, modifier = Modifier.padding(top = 6.dp))
        }
    }
    progress?.let { p ->
        Hint(
            if (p.total == 0) stringResource(R.string.library_scan_finding)
            else com.sadurni.attacca.ui.common.analysisProgressText(R.string.library_scan_progress, p.done, p.total, p.remainingMs),
        )
    }
    Hint(stringResource(R.string.setup_folder_hint))
}

/** The analysis: what it is for, how long it takes, and whether it runs by itself. */
@Composable
private fun AnalysisStep(analyse: Boolean, onAnalyse: (Boolean) -> Unit, keepScreenOn: Boolean, onKeepScreenOn: (Boolean) -> Unit) {
    StepTitle(stringResource(R.string.setup_analysis_title), stringResource(R.string.setup_analysis_intro))
    SwitchRow(stringResource(R.string.setup_analysis_auto), analyse, onAnalyse)
    Hint(stringResource(R.string.setup_analysis_auto_hint))
    SwitchRow(stringResource(R.string.chain_keep_screen_on), keepScreenOn, onKeepScreenOn, enabled = analyse)
    Hint(stringResource(R.string.chain_keep_screen_on_hint))
}

/** How the music is listened to most of the time: a sound template for the default profile. */
@Composable
private fun ListeningStep(selected: SoundTemplate?, onSelect: (SoundTemplate?) -> Unit) {
    StepTitle(stringResource(R.string.setup_listening_title), stringResource(R.string.setup_listening_intro))
    TemplateChoices(selected, onSelect)
    RadioRow(stringResource(R.string.setup_listening_none), selected == null) { onSelect(null) }
    Hint(stringResource(R.string.setup_listening_hint))
}

/** The templates as choices, each with what it does. */
@Composable
fun TemplateChoices(selected: SoundTemplate?, onSelect: (SoundTemplate) -> Unit) {
    for (t in SoundTemplate.entries) {
        RadioRow(stringResource(t.titleRes), selected == t) { onSelect(t) }
        if (selected == t) Hint(stringResource(t.hintRes))
    }
}

@Composable
private fun TransitionsStep(
    transition: Transition,
    onTransition: (Transition) -> Unit,
    keepAlbums: Boolean,
    onKeepAlbums: (Boolean) -> Unit,
    trimSilences: Boolean,
    onTrimSilences: (Boolean) -> Unit,
) {
    StepTitle(stringResource(R.string.setup_transitions_title), stringResource(R.string.setup_transitions_intro))
    val options = listOf(
        Transition.AUTOMATIC to (R.string.setup_transition_auto to R.string.setup_transition_auto_hint),
        Transition.CROSSFADE to (R.string.setup_transition_crossfade to R.string.setup_transition_crossfade_hint),
        Transition.GAPLESS to (R.string.setup_transition_gapless to R.string.setup_transition_gapless_hint),
    )
    for ((t, texts) in options) {
        RadioRow(stringResource(texts.first), transition == t) { onTransition(t) }
        if (transition == t) Hint(stringResource(texts.second))
    }
    SwitchRow(stringResource(R.string.crossfade_skip_album), keepAlbums, onKeepAlbums, enabled = transition != Transition.GAPLESS)
    Hint(stringResource(R.string.setup_keep_albums_hint))
    SwitchRow(stringResource(R.string.silence_enabled), trimSilences, onTrimSilences)
    Hint(stringResource(R.string.setup_trim_hint))
}

/** Ready: three things worth knowing, and where the rest is. */
@Composable
private fun DoneStep() {
    StepTitle(stringResource(R.string.setup_done_title), stringResource(R.string.setup_done_intro))
    for (tip in listOf(R.string.setup_tip_lists, R.string.setup_tip_sound, R.string.setup_tip_help)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            Text("•", fontSize = AttaccaType.body, color = LocalAccent.current, modifier = Modifier.padding(end = 10.dp))
            Text(stringResource(tip), fontSize = AttaccaType.body)
        }
    }
}

/** Blend lengths the guide sets when there was none yet. */
private const val AUTOMATIC_MS = 6_000
private const val CROSSFADE_MS = 5_000
