package com.sadurni.attacca.ui.settings

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sadurni.attacca.R
import com.sadurni.attacca.app
import com.sadurni.attacca.chain.ChainTaste
import com.sadurni.attacca.chain.TasteGroup
import com.sadurni.attacca.dsp.CrossfadeStyle
import com.sadurni.attacca.dsp.TasteSample
import com.sadurni.attacca.dsp.TasteSamples
import com.sadurni.attacca.dsp.TunePlayer
import com.sadurni.attacca.tuning.JoinChoice
import com.sadurni.attacca.tuning.TasteAnswer
import com.sadurni.attacca.tuning.TasteTuning
import com.sadurni.attacca.ui.theme.AttaccaType
import com.sadurni.attacca.ui.theme.LocalAccent
import com.sadurni.attacca.ui.theme.LocalAttaccaColors
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** The name of a group of the chaining weights. */
internal fun tasteGroupRes(g: TasteGroup): Int = when (g) {
    TasteGroup.TEMPO -> R.string.taste_group_tempo
    TasteGroup.SOUND -> R.string.taste_group_sound
    TasteGroup.ENERGY -> R.string.taste_group_energy
    TasteGroup.KEY -> R.string.taste_group_key
}

/**
 * The chaining guide: the end of a song of the library heard going into two songs that could
 * follow it, again and again; what the listener keeps moves how much chaining weighs the beat, the
 * sound, the energy and the key ([ChainTaste]), saved for every list made from then on.
 */
@Composable
fun TasteGuide(onDone: () -> Unit) = GuideDialog(onDone) {
    val context = LocalContext.current
    val settings = context.app.settings
    val playback by settings.playback.collectAsStateWithLifecycle()
    val chain by settings.chain.collectAsStateWithLifecycle()
    val trim by settings.trim.collectAsStateWithLifecycle()
    val before = remember { settings.chainTaste.value }
    val player = remember { TunePlayer(context) }
    var paused by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        player.start()
        onDispose { player.stop() }
    }
    LaunchedEffect(paused) { player.playing = !paused }
    // The songs join as the player joins them; without a blend set, a short one.
    val join = remember {
        JoinChoice(
            lengthMs = playback.crossfadeMs.takeIf { it > 0 } ?: 4_000,
            automatic = playback.crossfadeStyle == CrossfadeStyle.AUTOMATIC,
            trim = trim.enabled,
        )
    }
    val samples = remember { TasteSamples(context, settings.musicFolder, chain.edgeSeconds, join) }
    val rounds = remember { mutableStateMapOf<Int, TasteSample>() }
    var available by remember { mutableIntStateOf(TasteTuning.ROUNDS) }
    LaunchedEffect(Unit) {
        // One after another, a little ahead of the listener.
        for (i in 0 until TasteTuning.ROUNDS) {
            val s = samples.next(TasteTuning.group(i), before)
            if (s == null) {
                available = i
                break
            }
            rounds[i] = s
        }
    }
    val answers = remember { mutableStateMapOf<Int, Int>() }
    var index by remember { mutableIntStateOf(0) }
    val count = available + 1
    val atResult = index >= available
    val sample = if (atResult) null else rounds[index]
    var heard by remember { mutableIntStateOf(0) }
    LaunchedEffect(sample, heard) {
        val joint = when {
            sample == null -> null
            heard == 1 -> sample.second
            else -> sample.first
        }
        joint?.let { player.loop.playFrom(it.samples, it.changeFrom - 5 * TunePlayer.RATE) } ?: player.loop.play(null)
    }
    var position by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            position = player.loop.position
            delay(50)
        }
    }
    val given = answers.entries.sortedBy { it.key }.mapNotNull { (i, c) ->
        rounds[i]?.let { TasteAnswer(it.round.firstParts, it.round.secondParts, c) }
    }
    val decided = given.count { it.choice != TasteAnswer.ALIKE }
    val learnt = remember(given.size, answers.values.toList()) { TasteTuning.learn(before, given) }
    val enough = decided >= TasteTuning.MIN_ANSWERS

    fun save() {
        if (enough) {
            settings.setChainTaste(learnt)
            Toast.makeText(context, context.getString(R.string.taste_saved), Toast.LENGTH_LONG).show()
        }
        onDone()
    }

    GuideFrame(
        title = stringResource(R.string.taste_guide_title),
        index = index.coerceAtMost(count - 1),
        count = count,
        stepTitle = stringResource(if (atResult) R.string.taste_result_title else R.string.taste_round_title),
        intro = stringResource(if (atResult) R.string.taste_result_intro else R.string.taste_round_intro),
        onClose = onDone,
        onBack = { index--; heard = 0; paused = false },
        onNext = {
            if (atResult) save() else {
                if (answers[index] == null) answers[index] = TasteAnswer.ALIKE
                index++
                heard = 0
                paused = false
            }
        },
    ) {
        val grayed = LocalAttaccaColors.current.textGrayed
        val accent = LocalAccent.current
        when {
            available == 0 -> Hint(stringResource(R.string.taste_none))
            atResult -> {
                for (g in TasteGroup.entries) ShareRow(stringResource(tasteGroupRes(g)), before.share(g), learnt.share(g))
                if (!enough) Hint(stringResource(R.string.taste_few, TasteTuning.MIN_ANSWERS))
                if (!before.isDefault) {
                    TextButton(
                        onClick = {
                            settings.setChainTaste(ChainTaste.DEFAULT)
                            Toast.makeText(context, context.getString(R.string.taste_reset_done), Toast.LENGTH_LONG).show()
                            onDone()
                        },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) { Text(stringResource(R.string.taste_reset)) }
                }
            }
            sample == null -> Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = accent)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.guide_pair_loading), fontSize = AttaccaType.secondary, color = grayed)
            }
            else -> {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.taste_from, sample.fromTitle), fontSize = AttaccaType.secondary, color = grayed, modifier = Modifier.weight(1f), maxLines = 2)
                    IconButton(onClick = { paused = !paused }) {
                        Icon(if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, stringResource(if (paused) R.string.guide_play else R.string.guide_pause))
                    }
                }
                val joint = if (heard == 1) sample.second else sample.first
                ChangeBar(position, joint.frames, joint.changeFrom, joint.changeTo)
                val chosen = answers[index]
                OptionCard("1", sample.firstTitle, null, false, chosen == TasteAnswer.FIRST || chosen == null && heard == 0) {
                    heard = 0
                    answers[index] = TasteAnswer.FIRST
                    paused = false
                }
                OptionCard("2", sample.secondTitle, null, false, chosen == TasteAnswer.SECOND || chosen == null && heard == 1) {
                    heard = 1
                    answers[index] = TasteAnswer.SECOND
                    paused = false
                }
                OptionCard("=", stringResource(R.string.taste_alike), null, false, chosen == TasteAnswer.ALIKE) {
                    answers[index] = TasteAnswer.ALIKE
                }
                Hint(stringResource(R.string.taste_round_hint))
            }
        }
    }
}

/** A group's share of the weights: before (gray) and after (accent), with the percentages. */
@Composable
private fun ShareRow(name: String, before: Float, after: Float) {
    val accent = LocalAccent.current
    val grayed = LocalAttaccaColors.current.textGrayed
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(name, fontSize = AttaccaType.body, fontWeight = FontWeight.SemiBold)
            Text("${(before * 100).roundToInt()} % → ${(after * 100).roundToInt()} %", fontSize = AttaccaType.secondary, color = grayed)
        }
        Canvas(Modifier.fillMaxWidth().height(16.dp).padding(vertical = 3.dp)) {
            val r = CornerRadius(size.height / 2)
            drawRoundRect(grayed.copy(alpha = 0.15f), cornerRadius = r)
            drawRoundRect(grayed.copy(alpha = 0.45f), Offset.Zero, Size(size.width * before.coerceIn(0f, 1f), size.height / 2), r)
            drawRoundRect(accent, Offset(0f, size.height / 2), Size(size.width * after.coerceIn(0f, 1f), size.height / 2), r)
        }
    }
}
