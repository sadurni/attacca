package com.sadurni.attacca.ui.settings

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sadurni.attacca.R
import com.sadurni.attacca.app
import com.sadurni.attacca.dsp.TunePlayer
import com.sadurni.attacca.dsp.TuneSamples
import com.sadurni.attacca.settings.DspSettings
import com.sadurni.attacca.settings.OutputType
import com.sadurni.attacca.tuning.Fragment
import com.sadurni.attacca.tuning.FragmentKind
import com.sadurni.attacca.tuning.SoundTuning
import com.sadurni.attacca.tuning.TestSignals
import com.sadurni.attacca.tuning.TuneAnswers
import com.sadurni.attacca.tuning.TuneDevice
import com.sadurni.attacca.tuning.TuneStep
import com.sadurni.attacca.ui.theme.AttaccaType
import com.sadurni.attacca.ui.theme.LocalAccent
import com.sadurni.attacca.ui.theme.LocalAttaccaColors
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** The kind of device an output most likely is, to start the guide from. */
fun guessTuneDevice(type: OutputType?): TuneDevice = when (type) {
    OutputType.SPEAKER -> TuneDevice.PHONE_SPEAKER
    OutputType.BLUETOOTH -> TuneDevice.EARBUDS
    else -> TuneDevice.HEADPHONES
}

/** How the bass reach step is being answered. */
private enum class ReachMode { TONES, LINE }

/**
 * The sound guide: step by step, each choice heard on a sample of the user's own music (or a sound
 * of the guide's own), A/B/C, on whatever the phone plays to now; at the end everything chosen
 * together against the sound before, and saved as the device's own profile (or into the profile in
 * use, for the phone's speaker or an unknown output).
 */
@Composable
fun SoundGuide(onSaved: () -> Unit = {}, onDone: () -> Unit) {
    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            SoundGuideContent(onSaved, onDone)
        }
    }
}

@Composable
private fun SoundGuideContent(onSaved: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val settings = context.app.settings
    val store = settings.profiles
    val device by store.currentDevice.collectAsStateWithLifecycle()
    val active by store.active.collectAsStateWithLifecycle()
    val accent = LocalAccent.current
    val grayed = LocalAttaccaColors.current.textGrayed

    // The sound before the guide, compared at the end and the base of every choice.
    val before = remember { active.settings }
    var answers by remember { mutableStateOf(TuneAnswers(device = guessTuneDevice(device?.type))) }
    var index by remember { mutableIntStateOf(0) }
    val steps = SoundTuning.steps(answers.device)
    val step = steps[index.coerceIn(0, steps.size - 1)]
    var compare by remember { mutableIntStateOf(1) }
    var reachMode by remember { mutableStateOf(ReachMode.TONES) }
    var reachTone by remember { mutableIntStateOf(0) }
    var reachDone by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }

    val player = remember { TunePlayer(context) }
    DisposableEffect(Unit) {
        player.start()
        onDispose { player.stop() }
    }
    LaunchedEffect(paused) { player.playing = !paused }

    // The samples, found in the background as each kind is first needed.
    val samples = remember { TuneSamples(context, settings.musicFolder) }
    val found = remember { mutableStateMapOf<FragmentKind, Fragment>() }
    var refresh by remember { mutableIntStateOf(0) }
    val kind = SoundTuning.fragmentKind(step)
    LaunchedEffect(kind, refresh) {
        if (kind != null && (found[kind] == null || refresh > 0)) {
            val another = refresh > 0
            val f = samples.next(kind)
            // One found ahead meanwhile is kept, unless another was asked for.
            if (another || found[kind] == null) found[kind] = f
            refresh = 0
        }
    }
    val fragment = kind?.let { found[it] }
    // The samples of the steps ahead, found while this one is heard.
    LaunchedEffect(answers.device) {
        for (k in steps.mapNotNull { SoundTuning.fragmentKind(it) }.distinct()) {
            if (found[k] == null) samples.next(k).let { if (found[k] == null) found[k] = it }
        }
    }

    // What is heard: the step's sound through the settings being tried.
    LaunchedEffect(step, fragment, reachMode, reachTone, reachDone) {
        val loop = player.loop
        when (step) {
            TuneStep.SIDES -> loop.play(TestSignals.sides(TunePlayer.RATE))
            TuneStep.BASS_REACH -> loop.play(
                when {
                    reachDone -> null
                    reachMode == ReachMode.LINE -> TestSignals.bassLine(TunePlayer.RATE)
                    else -> TestSignals.tone(TunePlayer.RATE, SoundTuning.REACH_TONES[reachTone.coerceAtMost(SoundTuning.REACH_TONES.size - 1)])
                },
            )
            else -> if (fragment != null) loop.play(fragment) else loop.play(null)
        }
    }
    val heard: DspSettings = when (step) {
        TuneStep.DEVICE -> before
        TuneStep.BASS_REACH -> DspSettings().withoutEffects().copy(limiterEnabled = true)
        TuneStep.COMPARE -> if (compare == 0) before else SoundTuning.settings(before, answers)
        else -> SoundTuning.settings(before, answers, step)
    }
    LaunchedEffect(heard) { player.loop.settings = heard }

    // Where the sample is, for the left/centre/right lights and the bass line's notes.
    var position by remember { mutableIntStateOf(0) }
    LaunchedEffect(step) {
        while (step == TuneStep.SIDES || step == TuneStep.BASS_REACH) {
            position = player.loop.position
            delay(50)
        }
    }

    // Saving asks where: the profile in use, replaced, or a new one (named after the device).
    var saving by remember { mutableStateOf(false) }
    fun save() {
        saving = true
    }
    fun saveAs(newName: String?) {
        val result = SoundTuning.settings(before, answers)
        val d = device
        val message = if (newName == null) {
            store.setProfileSettings(active.id, result)
            context.getString(R.string.guide_saved_profile, active.name)
        } else {
            // A new profile for the device in use (one of that name already there is replaced).
            if (d != null) store.applySettings(result, newName, d.key, null) else store.createProfile(newName, result)
            context.getString(R.string.guide_saved_profile, newName)
        }
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        onSaved()
        onDone()
    }
    if (saving) {
        val d = device
        val suggested = d?.takeIf { it.type != OutputType.SPEAKER }?.name ?: context.getString(deviceNameRes(answers.device))
        SaveProfileDialog(active.name, suggested, onSave = { saveAs(it) }, onDismiss = { saving = false })
    }

    fun go(to: Int) {
        index = to.coerceIn(0, steps.size - 1)
        paused = false
    }
    BackHandler { if (index > 0) go(index - 1) else onDone() }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDone) { Icon(Icons.Rounded.Close, stringResource(R.string.cancel)) }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.guide_title), fontSize = AttaccaType.heading, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.guide_step, index + 1, steps.size), fontSize = AttaccaType.secondary, color = grayed)
            }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.Center) {
            for (i in steps.indices) {
                Box(
                    Modifier.padding(horizontal = 4.dp).size(if (i == index) 10.dp else 8.dp)
                        .background(if (i <= index) accent else grayed.copy(alpha = 0.4f), CircleShape),
                )
            }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
                StepTitle(stringResource(step.titleRes), stringResource(step.introRes))
                if (step == TuneStep.DEVICE) Hint(stringResource(R.string.guide_volume))
                // The sample: what it is, another one, pause.
                if (kind != null) {
                    SampleRow(fragment, paused, onPause = { paused = !paused }, onAnother = { refresh++ })
                }
                when (step) {
                    TuneStep.SIDES -> SidesLights(TestSignals.sidesPart(position, TunePlayer.RATE))
                    TuneStep.BASS_REACH -> ReachStep(
                        mode = reachMode,
                        onMode = { reachMode = it; reachTone = 0; reachDone = false },
                        tone = reachTone,
                        note = TestSignals.bassLineNote(position, TunePlayer.RATE),
                        done = reachDone,
                        reach = answers.bassReachHz,
                        onTone = { heardIt ->
                            if (heardIt) {
                                answers = answers.copy(bassReachHz = SoundTuning.REACH_TONES[reachTone], bass = -1)
                                if (reachTone >= SoundTuning.REACH_TONES.size - 1) reachDone = true else reachTone++
                            } else {
                                if (reachTone == 0) answers = answers.copy(bassReachHz = 180, bass = -1)
                                reachDone = true
                            }
                        },
                        onLineStop = { note ->
                            val hz = if (note == 0) 180 else TestSignals.BASS_NOTES[note - 1].roundToInt()
                            answers = answers.copy(bassReachHz = hz, bass = -1)
                            reachDone = true
                        },
                        onLineAll = {
                            answers = answers.copy(bassReachHz = TestSignals.BASS_NOTES.last().roundToInt(), bass = -1)
                            reachDone = true
                        },
                        onAgain = { reachTone = 0; reachDone = false },
                    )
                    else -> Unit
                }
                val options = SoundTuning.options(step, answers)
                val chosen = if (step == TuneStep.COMPARE) compare else SoundTuning.chosen(step, answers)
                for ((i, option) in options.withIndex()) {
                    OptionCard(
                        letter = ('A' + i).toString(),
                        title = stringResource(optionTitle(option.id)),
                        hint = optionHint(option.id)?.let { stringResource(it) },
                        suggested = option.suggested && step != TuneStep.COMPARE,
                        selected = i == chosen,
                    ) {
                        if (step == TuneStep.COMPARE) compare = i else answers = SoundTuning.choose(step, answers, i)
                        paused = false
                    }
                }
                if (step == TuneStep.SIDES) {
                    Text(
                        stringResource(R.string.guide_balance) + "  " + formatBalance(answers.balance),
                        fontSize = AttaccaType.body,
                        modifier = Modifier.padding(horizontal = 16.dp).padding(top = 12.dp),
                    )
                    Slider(
                        value = answers.balance,
                        onValueChange = { answers = answers.copy(balance = (it * 50).roundToInt() / 50f) },
                        valueRange = -0.3f..0.3f,
                        colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent),
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                if (step == TuneStep.COMPARE) Hint(stringResource(R.string.guide_compare_hint))
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (index > 0) TextButton(onClick = { go(index - 1) }) { Text(stringResource(R.string.setup_back)) }
            Spacer(Modifier.weight(1f))
            val last = index == steps.size - 1
            Button(
                onClick = { if (last) save() else go(index + 1) },
                colors = ButtonDefaults.buttonColors(containerColor = accent),
            ) {
                Text(stringResource(if (last) R.string.guide_save else R.string.setup_next))
            }
        }
    }
}

@Composable
internal fun SampleRow(fragment: Fragment?, paused: Boolean, onPause: () -> Unit, onAnother: () -> Unit) {
    val grayed = LocalAttaccaColors.current.textGrayed
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (fragment == null) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalAccent.current)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.guide_sample_loading), fontSize = AttaccaType.secondary, color = grayed, modifier = Modifier.weight(1f))
        } else {
            Text(
                fragment.title?.let { stringResource(R.string.guide_sample, it) } ?: stringResource(R.string.guide_sample_own),
                fontSize = AttaccaType.secondary,
                color = grayed,
                maxLines = 2,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onAnother) { Icon(Icons.Rounded.Shuffle, stringResource(R.string.guide_another)) }
            IconButton(onClick = onPause) {
                Icon(if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, stringResource(if (paused) R.string.guide_play else R.string.guide_pause))
            }
        }
    }
}

/** A choice: its letter, what it is, and whether it is the one heard. */
@Composable
internal fun OptionCard(letter: String, title: String, hint: String?, suggested: Boolean, selected: Boolean, onClick: () -> Unit) {
    val accent = LocalAccent.current
    val grayed = LocalAttaccaColors.current.textGrayed
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)
            .border(if (selected) 2.dp else 1.dp, if (selected) accent else grayed.copy(alpha = 0.35f), shape)
            .background(if (selected) accent.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Transparent, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).background(if (selected) accent else grayed.copy(alpha = 0.25f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(letter, fontWeight = FontWeight.Bold, color = if (selected) androidx.compose.ui.graphics.Color.White else MaterialTheme.colorScheme.onBackground)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = AttaccaType.body, fontWeight = FontWeight.SemiBold)
                if (suggested) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.guide_suggested),
                        fontSize = AttaccaType.secondary,
                        color = accent,
                        modifier = Modifier.border(1.dp, accent, RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
            if (hint != null) Text(hint, fontSize = AttaccaType.secondary, color = grayed)
        }
    }
}

/** Left, centre, right: the one sounding lit. */
@Composable
private fun SidesLights(part: Int) {
    val accent = LocalAccent.current
    val grayed = LocalAttaccaColors.current.textGrayed
    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        for ((i, label) in listOf(R.string.guide_left, R.string.guide_centre, R.string.guide_right).withIndex()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(28.dp).background(if (part == i) accent else grayed.copy(alpha = 0.25f), CircleShape))
                Text(stringResource(label), fontSize = AttaccaType.secondary, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/** How deep the device goes: tones one by one, or a bass line going down. */
@Composable
private fun ReachStep(
    mode: ReachMode,
    onMode: (ReachMode) -> Unit,
    tone: Int,
    note: Int,
    done: Boolean,
    reach: Int?,
    onTone: (Boolean) -> Unit,
    onLineStop: (Int) -> Unit,
    onLineAll: () -> Unit,
    onAgain: () -> Unit,
) {
    val accent = LocalAccent.current
    val grayed = LocalAttaccaColors.current.textGrayed
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (m in ReachMode.entries) {
            val on = m == mode
            OutlinedButton(
                onClick = { onMode(m) },
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = if (on) accent.copy(alpha = 0.15f) else androidx.compose.ui.graphics.Color.Transparent,
                ),
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(if (m == ReachMode.TONES) R.string.guide_reach_tones else R.string.guide_reach_line), color = if (on) accent else MaterialTheme.colorScheme.onBackground)
            }
        }
    }
    if (done) {
        Text(
            if (reach == null || reach >= 180) stringResource(R.string.guide_reach_result_none) else stringResource(R.string.guide_reach_result, reach),
            fontSize = AttaccaType.title,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
        Hint(stringResource(R.string.guide_reach_next))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            OutlinedButton(onClick = onAgain) { Text(stringResource(R.string.guide_reach_again)) }
        }
        return
    }
    when (mode) {
        ReachMode.TONES -> {
            Text(
                "${SoundTuning.REACH_TONES[tone.coerceAtMost(SoundTuning.REACH_TONES.size - 1)]} Hz",
                fontSize = AttaccaType.display * 1.3f,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { onTone(true) }, colors = ButtonDefaults.buttonColors(containerColor = accent), modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.guide_reach_hear))
                }
                OutlinedButton(onClick = { onTone(false) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.guide_reach_not)) }
            }
        }
        ReachMode.LINE -> {
            FlowRow(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for ((i, hz) in TestSignals.BASS_NOTES.withIndex()) {
                    val on = i == note
                    Text(
                        "${hz.roundToInt()} Hz",
                        fontSize = AttaccaType.secondary,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                        color = if (on) androidx.compose.ui.graphics.Color.White else grayed,
                        modifier = Modifier.background(if (on) accent else grayed.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { onLineStop(note) }, colors = ButtonDefaults.buttonColors(containerColor = accent), modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.guide_reach_stopped))
                }
                OutlinedButton(onClick = onLineAll, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.guide_reach_all)) }
            }
        }
    }
}

/** The name of a kind of device, for a new profile's name. */
private fun deviceNameRes(d: TuneDevice): Int = when (d) {
    TuneDevice.HEADPHONES -> R.string.template_headphones
    TuneDevice.EARBUDS -> R.string.template_earbuds
    TuneDevice.PHONE_SPEAKER -> R.string.template_phone_speaker
    TuneDevice.PORTABLE_SPEAKER -> R.string.template_portable_speaker
    TuneDevice.CAR -> R.string.template_car
    TuneDevice.HOME_STEREO -> R.string.template_home_stereo
}

/** Where the guide's sound goes: the profile in use, replaced, or a new profile with a name. */
@Composable
private fun SaveProfileDialog(current: String, suggested: String, onSave: (String?) -> Unit, onDismiss: () -> Unit) {
    var asNew by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(suggested) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.guide_save_title)) },
        text = {
            Column {
                RadioRow(stringResource(R.string.guide_save_replace, current), !asNew) { asNew = false }
                RadioRow(stringResource(R.string.guide_save_new), asNew) { asNew = true }
                if (asNew) {
                    androidx.compose.material3.OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.profile_name)) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(if (asNew) name.trim() else null) }, enabled = !asNew || name.isNotBlank()) {
                Text(stringResource(R.string.guide_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private val TuneStep.titleRes: Int
    get() = when (this) {
        TuneStep.DEVICE -> R.string.guide_device_title
        TuneStep.SIDES -> R.string.guide_sides_title
        TuneStep.BASS_REACH -> R.string.guide_reach_title
        TuneStep.BASS -> R.string.guide_bass_title
        TuneStep.TREBLE -> R.string.guide_treble_title
        TuneStep.CROSSFEED -> R.string.guide_crossfeed_title
        TuneStep.WIDTH -> R.string.guide_width_title
        TuneStep.LEVELS -> R.string.guide_levels_title
        TuneStep.COMPARE -> R.string.guide_compare_title
    }

private val TuneStep.introRes: Int
    get() = when (this) {
        TuneStep.DEVICE -> R.string.guide_device_intro
        TuneStep.SIDES -> R.string.guide_sides_intro
        TuneStep.BASS_REACH -> R.string.guide_reach_intro
        TuneStep.BASS -> R.string.guide_bass_intro
        TuneStep.TREBLE -> R.string.guide_treble_intro
        TuneStep.CROSSFEED -> R.string.guide_crossfeed_intro
        TuneStep.WIDTH -> R.string.guide_width_intro
        TuneStep.LEVELS -> R.string.guide_levels_intro
        TuneStep.COMPARE -> R.string.guide_compare_intro
    }

private fun optionTitle(id: String): Int = when (id) {
    "headphones" -> R.string.template_headphones
    "earbuds" -> R.string.template_earbuds
    "phone_speaker" -> R.string.template_phone_speaker
    "portable_speaker" -> R.string.template_portable_speaker
    "car" -> R.string.template_car
    "home_stereo" -> R.string.template_home_stereo
    "stereo" -> R.string.guide_opt_stereo
    "mono" -> R.string.guide_opt_mono
    "bass_none", "treble_same", "width_same", "levels_off" -> R.string.guide_opt_same
    "bass_gentle" -> R.string.guide_opt_bass_gentle
    "bass_more" -> R.string.guide_opt_bass_more
    "bass_virtual_light" -> R.string.guide_opt_bass_virtual_light
    "bass_virtual" -> R.string.guide_opt_bass_virtual
    "treble_darker" -> R.string.guide_opt_treble_darker
    "treble_brighter" -> R.string.guide_opt_treble_brighter
    "crossfeed_off" -> R.string.guide_opt_crossfeed_off
    "crossfeed_light" -> R.string.guide_opt_crossfeed_light
    "crossfeed_strong" -> R.string.guide_opt_crossfeed_strong
    "crossfeed_speakers" -> R.string.guide_opt_crossfeed_speakers
    "width_narrower" -> R.string.guide_opt_width_narrower
    "width_wider" -> R.string.guide_opt_width_wider
    "levels_gentle" -> R.string.guide_opt_levels_gentle
    "levels_even" -> R.string.guide_opt_levels_even
    "before" -> R.string.guide_opt_before
    else -> R.string.guide_opt_after
}

private fun optionHint(id: String): Int? = when (id) {
    "stereo" -> R.string.guide_opt_stereo_hint
    "mono" -> R.string.guide_opt_mono_hint
    "bass_gentle" -> R.string.guide_opt_bass_gentle_hint
    "bass_more" -> R.string.guide_opt_bass_more_hint
    "bass_virtual_light" -> R.string.guide_opt_bass_virtual_light_hint
    "bass_virtual" -> R.string.guide_opt_bass_virtual_hint
    "treble_darker" -> R.string.guide_opt_treble_darker_hint
    "treble_brighter" -> R.string.guide_opt_treble_brighter_hint
    "crossfeed_off" -> R.string.guide_opt_crossfeed_off_hint
    "crossfeed_light" -> R.string.guide_opt_crossfeed_light_hint
    "crossfeed_strong" -> R.string.guide_opt_crossfeed_strong_hint
    "crossfeed_speakers" -> R.string.guide_opt_crossfeed_speakers_hint
    "width_narrower" -> R.string.guide_opt_width_narrower_hint
    "width_wider" -> R.string.guide_opt_width_wider_hint
    "levels_gentle" -> R.string.guide_opt_levels_gentle_hint
    "levels_even" -> R.string.guide_opt_levels_even_hint
    "before" -> R.string.guide_opt_before_hint
    "after" -> R.string.guide_opt_after_hint
    "bass_none", "treble_same", "width_same", "levels_off" -> R.string.guide_opt_same_hint
    else -> null
}
