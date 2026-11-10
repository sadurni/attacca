package com.sadurni.attacca.ui.settings

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sadurni.attacca.R
import com.sadurni.attacca.app
import com.sadurni.attacca.dsp.CrossfadeStyle
import com.sadurni.attacca.dsp.LevelPair
import com.sadurni.attacca.dsp.PlaySamples
import com.sadurni.attacca.dsp.TrackPair
import com.sadurni.attacca.dsp.TunePlayer
import com.sadurni.attacca.settings.ReplayGainMode
import com.sadurni.attacca.tuning.JoinAnswers
import com.sadurni.attacca.tuning.JoinStep
import com.sadurni.attacca.tuning.Joints
import com.sadurni.attacca.tuning.LevelAnswers
import com.sadurni.attacca.tuning.LevelStep
import com.sadurni.attacca.tuning.LevelTuning
import com.sadurni.attacca.tuning.PairKind
import com.sadurni.attacca.tuning.TransitionTuning
import com.sadurni.attacca.ui.theme.AttaccaType
import com.sadurni.attacca.ui.theme.LocalAccent
import com.sadurni.attacca.ui.theme.LocalAttaccaColors
import kotlinx.coroutines.delay

/** A guide's frame: full screen, its steps as dots, the step's title and text, back and next or save. */
@Composable
internal fun GuideFrame(
    title: String,
    index: Int,
    count: Int,
    stepTitle: String,
    intro: String,
    onClose: () -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val accent = LocalAccent.current
    val grayed = LocalAttaccaColors.current.textGrayed
    BackHandler { if (index > 0) onBack() else onClose() }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, stringResource(R.string.cancel)) }
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = AttaccaType.heading, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.guide_step, index + 1, count), fontSize = AttaccaType.secondary, color = grayed)
            }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.Center) {
            for (i in 0 until count) {
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
                StepTitle(stepTitle, intro)
                content()
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (index > 0) TextButton(onClick = onBack) { Text(stringResource(R.string.setup_back)) }
            Spacer(Modifier.weight(1f))
            Button(onClick = onNext, colors = ButtonDefaults.buttonColors(containerColor = accent)) {
                Text(stringResource(if (index == count - 1) R.string.guide_save else R.string.setup_next))
            }
        }
    }
}

/** What is heard: the two tracks, another pair, pause. */
@Composable
private fun PairRow(first: String?, second: String?, loading: Boolean, nowSecond: Boolean, paused: Boolean, onPause: () -> Unit, onAnother: () -> Unit) {
    val grayed = LocalAttaccaColors.current.textGrayed
    val accent = LocalAccent.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (loading || first == null) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = accent)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.guide_pair_loading), fontSize = AttaccaType.secondary, color = grayed, modifier = Modifier.weight(1f))
        } else {
            Column(Modifier.weight(1f)) {
                Text("1 · $first", fontSize = AttaccaType.secondary, color = if (!nowSecond) accent else grayed, fontWeight = if (!nowSecond) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
                Text("2 · ${second.orEmpty()}", fontSize = AttaccaType.secondary, color = if (nowSecond) accent else grayed, fontWeight = if (nowSecond) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
            }
            IconButton(onClick = onAnother) { Icon(Icons.Rounded.Shuffle, stringResource(R.string.guide_another_pair)) }
            IconButton(onClick = onPause) {
                Icon(if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, stringResource(if (paused) R.string.guide_play else R.string.guide_pause))
            }
        }
    }
}

/** Where the sample is: a bar with the change marked. */
@Composable
internal fun ChangeBar(position: Int, frames: Int, changeFrom: Int, changeTo: Int) {
    val accent = LocalAccent.current
    val grayed = LocalAttaccaColors.current.textGrayed
    Canvas(Modifier.fillMaxWidth().height(22.dp).padding(horizontal = 16.dp, vertical = 6.dp)) {
        if (frames <= 0) return@Canvas
        val r = CornerRadius(size.height / 2)
        drawRoundRect(grayed.copy(alpha = 0.2f), cornerRadius = r)
        val x0 = size.width * changeFrom / frames
        val x1 = size.width * changeTo / frames
        drawRoundRect(accent.copy(alpha = 0.35f), Offset(x0, 0f), Size((x1 - x0).coerceAtLeast(4f), size.height), r)
        val x = size.width * position / frames
        drawCircle(accent, size.height * 0.75f, Offset(x, size.height / 2))
    }
}

@Composable
internal fun GuideDialog(onDone: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
    }
}

/**
 * The transitions guide: how one track goes into the next, heard on real pairs of the library
 * (two different songs, one with silence at its end, two in a row of an album) and saved as the
 * player's settings.
 */
@Composable
fun TransitionsGuide(onDone: () -> Unit) = GuideDialog(onDone) {
    val context = LocalContext.current
    val settings = context.app.settings
    val playback by settings.playback.collectAsStateWithLifecycle()
    val trim by settings.trim.collectAsStateWithLifecycle()
    val active by settings.profiles.active.collectAsStateWithLifecycle()
    var answers by remember {
        mutableStateOf(
            JoinAnswers(
                blend = when {
                    playback.crossfadeMs == 0 -> 0
                    playback.crossfadeStyle == CrossfadeStyle.AUTOMATIC -> 1
                    playback.crossfadeMs <= 5_000 -> 2
                    else -> 3
                },
                silence = if (trim.enabled) 0 else 1,
                album = if (playback.crossfadeSkipSameAlbum) 0 else 1,
                automaticMs = playback.crossfadeMs.takeIf { it > 0 } ?: 6_000,
            ),
        )
    }
    var index by remember { mutableIntStateOf(0) }
    val steps = TransitionTuning.steps(answers)
    val step = steps[index.coerceIn(0, steps.size - 1)]
    var paused by remember { mutableStateOf(false) }
    val player = remember { TunePlayer(context) }
    DisposableEffect(Unit) {
        player.start()
        onDispose { player.stop() }
    }
    LaunchedEffect(paused) { player.playing = !paused }
    LaunchedEffect(active) { player.loop.settings = active.settings }

    val samples = remember { PlaySamples(context, settings.musicFolder, trim.thresholdDb) }
    val pairs = remember { mutableStateMapOf<PairKind, TrackPair>() }
    var missing by remember { mutableStateOf(setOf<PairKind>()) }
    var refresh by remember { mutableIntStateOf(0) }
    val kind = TransitionTuning.pairKind(step)
    LaunchedEffect(kind, refresh) {
        if (pairs[kind] == null || refresh > 0) {
            val p = samples.pair(kind)
            if (p != null) pairs[kind] = p else missing = missing + kind
            refresh = 0
        }
    }
    LaunchedEffect(Unit) {
        for (k in PairKind.entries) if (pairs[k] == null) samples.pair(k)?.let { if (pairs[k] == null) pairs[k] = it }
    }
    val pair = pairs[kind]
    val joint = remember(pair, answers, step) { pair?.let { Joints.render(it.first, it.second, TransitionTuning.join(step, answers)) } }
    LaunchedEffect(joint) {
        // Each choice heard from a little before the change.
        joint?.let { player.loop.playFrom(it.samples, it.changeFrom - 4 * TunePlayer.RATE) } ?: player.loop.play(null)
    }
    var position by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            position = player.loop.position
            delay(50)
        }
    }

    fun save() {
        settings.updatePlayback {
            val length = TransitionTuning.lengthMs(answers)
            it.copy(
                crossfadeMs = length,
                crossfadeStyle = when (answers.blend) {
                    1 -> CrossfadeStyle.AUTOMATIC
                    2, 3 -> CrossfadeStyle.CROSSFADE
                    else -> it.crossfadeStyle
                },
                crossfadeSkipSameAlbum = answers.album == 0,
            )
        }
        settings.updateTrim { it.copy(enabled = answers.silence == 0) }
        Toast.makeText(context, context.getString(R.string.guide_saved_settings), Toast.LENGTH_LONG).show()
        onDone()
    }

    GuideFrame(
        title = stringResource(R.string.join_guide_title),
        index = index,
        count = steps.size,
        stepTitle = stringResource(
            when (step) { JoinStep.BLEND -> R.string.join_blend_title; JoinStep.SILENCE -> R.string.join_silence_title; JoinStep.ALBUM -> R.string.join_album_title },
        ),
        intro = stringResource(
            when (step) { JoinStep.BLEND -> R.string.join_blend_intro; JoinStep.SILENCE -> R.string.join_silence_intro; JoinStep.ALBUM -> R.string.join_album_intro },
        ),
        onClose = onDone,
        onBack = { index--; paused = false },
        onNext = { if (index == steps.size - 1) save() else { index++; paused = false } },
    ) {
        if (index == 0) Hint(stringResource(R.string.guide_volume_pairs))
        if (kind in missing && pair == null) {
            Hint(stringResource(R.string.guide_no_pairs))
        } else {
            PairRow(pair?.firstTitle, pair?.secondTitle, pair == null, joint != null && position >= joint.changeTo, paused, { paused = !paused }, { refresh++ })
            joint?.let { ChangeBar(position, it.frames, it.changeFrom, it.changeTo) }
            if (pair != null && !pair.fits) {
                Hint(stringResource(if (kind == PairKind.SILENCE) R.string.join_silence_none else R.string.guide_pair_unlike))
            }
        }
        val chosen = TransitionTuning.chosen(step, answers)
        for ((i, id) in TransitionTuning.options(step).withIndex()) {
            OptionCard(('A' + i).toString(), stringResource(playOptionTitle(id)), stringResource(playOptionHint(id)), false, i == chosen) {
                answers = TransitionTuning.choose(step, answers, i)
                paused = false
            }
        }
    }
}

/**
 * The volume guide: how loud each track plays next to the others, heard on tracks of the library
 * chosen for it, and saved in the profile in use (ReplayGain).
 */
@Composable
fun LevelGuide(onDone: () -> Unit) = GuideDialog(onDone) {
    val context = LocalContext.current
    val settings = context.app.settings
    val store = settings.profiles
    val active by store.active.collectAsStateWithLifecycle()
    val trim by settings.trim.collectAsStateWithLifecycle()
    val before = remember { active.settings }
    var answers by remember {
        mutableStateOf(
            LevelAnswers(
                between = if (before.replayGainMode == ReplayGainMode.OFF) 0 else 1,
                within = if (before.replayGainMode == ReplayGainMode.TRACK) 1 else 0,
                overall = when { before.replayGainPreampDb >= 5f -> 2; before.replayGainPreampDb >= 2f -> 1; else -> 0 },
            ),
        )
    }
    var index by remember { mutableIntStateOf(0) }
    val steps = LevelTuning.steps(answers)
    val step = steps[index.coerceIn(0, steps.size - 1)]
    var paused by remember { mutableStateOf(false) }
    val player = remember { TunePlayer(context) }
    DisposableEffect(Unit) {
        player.start()
        onDispose { player.stop() }
    }
    LaunchedEffect(paused) { player.playing = !paused }
    // The rest of the sound as it is set; the levels are the guide's own.
    LaunchedEffect(Unit) { player.loop.settings = before }

    val samples = remember { PlaySamples(context, settings.musicFolder, trim.thresholdDb) }
    val pairs = remember { mutableStateMapOf<Boolean, LevelPair>() }
    var missing by remember { mutableStateOf(setOf<Boolean>()) }
    var refresh by remember { mutableIntStateOf(0) }
    val sameAlbum = step == LevelStep.WITHIN
    LaunchedEffect(sameAlbum, refresh) {
        if (pairs[sameAlbum] == null || refresh > 0) {
            val p = samples.levelPair(sameAlbum)
            if (p != null) pairs[sameAlbum] = p else missing = missing + sameAlbum
            refresh = 0
        }
    }
    val pair = pairs[sameAlbum]
    val sample = remember(pair, answers, step) { pair?.let { LevelTuning.render(it.first, it.second, answers, step) } }
    var lastPair by remember { mutableStateOf<LevelPair?>(null) }
    LaunchedEffect(sample) {
        val s = sample ?: return@LaunchedEffect player.loop.play(null)
        // The same two tracks at other levels go on from where they are: A/B without starting again.
        if (lastPair === pair) player.loop.replace(s.samples) else player.loop.play(s.samples)
        lastPair = pair
    }
    var position by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            position = player.loop.position
            delay(50)
        }
    }

    fun save() {
        val mode = when {
            answers.between == 0 -> ReplayGainMode.OFF
            answers.within == 1 -> ReplayGainMode.TRACK
            else -> ReplayGainMode.AUTO
        }
        store.updateActiveSettings {
            it.copy(replayGainMode = mode, replayGainPreampDb = LevelTuning.preampDb(answers), replayGainPreventClipping = true, limiterEnabled = true)
        }
        Toast.makeText(context, context.getString(R.string.guide_saved_profile, active.name), Toast.LENGTH_LONG).show()
        onDone()
    }

    GuideFrame(
        title = stringResource(R.string.level_guide_title),
        index = index,
        count = steps.size,
        stepTitle = stringResource(
            when (step) { LevelStep.BETWEEN -> R.string.level_between_title; LevelStep.WITHIN -> R.string.level_within_title; LevelStep.OVERALL -> R.string.level_overall_title },
        ),
        intro = stringResource(
            when (step) { LevelStep.BETWEEN -> R.string.level_between_intro; LevelStep.WITHIN -> R.string.level_within_intro; LevelStep.OVERALL -> R.string.level_overall_intro },
        ),
        onClose = onDone,
        onBack = { index--; paused = false },
        onNext = { if (index == steps.size - 1) save() else { index++; paused = false } },
    ) {
        if (index == 0) Hint(stringResource(R.string.guide_volume_pairs))
        if (sameAlbum in missing && pair == null) {
            Hint(stringResource(R.string.guide_no_pairs))
        } else {
            PairRow(pair?.first?.title, pair?.second?.title, pair == null, sample != null && position >= sample.secondFrom, paused, { paused = !paused }, { refresh++ })
            if (pair != null && !pair.fits) Hint(stringResource(R.string.level_guessed))
        }
        val chosen = LevelTuning.chosen(step, answers)
        for ((i, id) in LevelTuning.options(step).withIndex()) {
            OptionCard(('A' + i).toString(), stringResource(playOptionTitle(id)), stringResource(playOptionHint(id)), false, i == chosen) {
                answers = LevelTuning.choose(step, answers, i)
                paused = false
            }
        }
    }
}

private fun playOptionTitle(id: String): Int = when (id) {
    "join_gapless" -> R.string.join_opt_gapless
    "join_automatic" -> R.string.join_opt_automatic
    "join_crossfade_4" -> R.string.join_opt_crossfade_4
    "join_crossfade_8" -> R.string.join_opt_crossfade_8
    "silence_trim" -> R.string.join_opt_silence_trim
    "silence_keep" -> R.string.join_opt_silence_keep
    "album_gapless" -> R.string.join_opt_album_gapless
    "album_blend" -> R.string.join_opt_album_blend
    "level_off" -> R.string.level_opt_off
    "level_even" -> R.string.level_opt_even
    "level_album" -> R.string.level_opt_album
    "level_track" -> R.string.level_opt_track
    "level_standard" -> R.string.level_opt_standard
    "level_up3" -> R.string.level_opt_up3
    else -> R.string.level_opt_up6
}

private fun playOptionHint(id: String): Int = when (id) {
    "join_gapless" -> R.string.join_opt_gapless_hint
    "join_automatic" -> R.string.join_opt_automatic_hint
    "join_crossfade_4" -> R.string.join_opt_crossfade_4_hint
    "join_crossfade_8" -> R.string.join_opt_crossfade_8_hint
    "silence_trim" -> R.string.join_opt_silence_trim_hint
    "silence_keep" -> R.string.join_opt_silence_keep_hint
    "album_gapless" -> R.string.join_opt_album_gapless_hint
    "album_blend" -> R.string.join_opt_album_blend_hint
    "level_off" -> R.string.level_opt_off_hint
    "level_even" -> R.string.level_opt_even_hint
    "level_album" -> R.string.level_opt_album_hint
    "level_track" -> R.string.level_opt_track_hint
    "level_standard" -> R.string.level_opt_standard_hint
    "level_up3" -> R.string.level_opt_up3_hint
    else -> R.string.level_opt_up6_hint
}
