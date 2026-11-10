package com.sadurni.attaccaplg.ui

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sadurni.attacca.chain.ChainTaste
import com.sadurni.attacca.chain.EdgeFeatures
import com.sadurni.attacca.chain.TasteGroup
import com.sadurni.attacca.dsp.CrossfadeStyle
import com.sadurni.attacca.tuning.JoinChoice
import com.sadurni.attacca.tuning.Joint
import com.sadurni.attacca.tuning.Joints
import com.sadurni.attacca.tuning.TasteAnswer
import com.sadurni.attacca.tuning.TasteRound
import com.sadurni.attacca.tuning.TasteTrack
import com.sadurni.attacca.tuning.TasteTuning
import com.sadurni.attaccaplg.audio.AudioOut
import com.sadurni.attaccaplg.library.StoredTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/** The chaining weights kept in the settings, put in use (at start and when the guide saves). */
object DeskTaste {
    const val KEY = "chain_taste"

    fun load(prefs: Prefs) {
        EdgeFeatures.taste = ChainTaste.decode(prefs[KEY])
    }

    fun save(prefs: Prefs, taste: ChainTaste) {
        val t = taste.normalized()
        EdgeFeatures.taste = t
        prefs[KEY] = if (t.isDefault) null else t.encode()
    }
}

/** A group's name. */
internal fun deskGroupName(g: TasteGroup): String = when (g) {
    TasteGroup.TEMPO -> tr("Beat", "Ritmo")
    TasteGroup.SOUND -> tr("Sound", "Sonido")
    TasteGroup.ENERGY -> tr("Energy", "Energía")
    TasteGroup.KEY -> tr("Key and notes", "Tonalidad y notas")
}

/** "Beat 33 % · Sound 45 % · …" */
internal fun deskTasteLine(t: ChainTaste): String =
    TasteGroup.entries.joinToString("  ·  ") { "${deskGroupName(it)} ${(t.share(it) * 100).roundToInt()} %" }

private class DeskTasteSample(val round: TasteRound, val from: String, val first: String, val second: String, val firstJoint: Joint, val secondJoint: Joint)

/** The chaining guide's questions from the library's analysed tracks. */
private class DeskTasteSamples(all: List<StoredTrack>, private val join: JoinChoice) {
    private val tracks = all.filter { it.durationMs > 50_000 && File(it.path).isFile && (it.head?.timbre != null || it.tail?.timbre != null) }
    private val taste = tracks.mapIndexed { i, t -> TasteTrack(i, t.head, t.tail, t.tags.artist, File(t.path).parent.orEmpty()) }
    private val lock = Mutex()
    private val used = mutableSetOf<Int>()
    private fun title(t: StoredTrack) = listOf(t.tags.artist, t.tags.title.ifBlank { File(t.path).nameWithoutExtension }).filter { it.isNotBlank() }.joinToString(" — ")

    suspend fun next(group: TasteGroup, weights: ChainTaste): DeskTasteSample? = lock.withLock {
        withContext(Dispatchers.IO) {
            repeat(4) {
                val round = TasteTuning.pick(taste, group, weights, avoid = used) ?: return@withContext null
                used += round.from.id
                val a = tracks[round.from.id]
                val b = tracks[round.first.id]
                val c = tracks[round.second.id]
                val tail = decodeStretch(File(a.path), (a.durationMs - Joints.TAIL_SECONDS * 1000L).coerceAtLeast(0), null)
                val h1 = decodeStretch(File(b.path), 0, Joints.HEAD_SECONDS * 1000L)
                val h2 = decodeStretch(File(c.path), 0, Joints.HEAD_SECONDS * 1000L)
                if (tail != null && h1 != null && h2 != null && tail.frames > AudioOut.RATE * 10) {
                    return@withContext DeskTasteSample(round, title(a), title(b), title(c), Joints.render(tail, h1, join), Joints.render(tail, h2, join))
                }
            }
            null
        }
    }
}

/**
 * The chaining guide in Attacca Desktop: a song's end heard going into two songs that could follow
 * it; what is kept moves the chaining weights (saved for lists and the players' libraries).
 */
@Composable
fun DeskTasteGuide(state: AppState, onDone: () -> Unit) {
    val p = state.player
    val s = p.settings
    val before = remember { EdgeFeatures.taste }
    val player = remember { DeskLoopPlayer(p.device) }
    var paused by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        if (p.playing) p.toggle()
        player.loop.settings = p.dsp
        player.start()
        onDispose { player.stop() }
    }
    LaunchedEffect(paused) { player.playing = !paused }
    val join = remember { JoinChoice(s.crossfadeMs.takeIf { it > 0 } ?: 4_000, s.crossfadeStyle == CrossfadeStyle.AUTOMATIC, s.trimSilences) }
    val samples = remember { DeskTasteSamples(state.library?.all.orEmpty(), join) }
    val rounds = remember { mutableStateMapOf<Int, DeskTasteSample>() }
    var available by remember { mutableIntStateOf(TasteTuning.ROUNDS) }
    LaunchedEffect(Unit) {
        for (i in 0 until TasteTuning.ROUNDS) {
            val r = samples.next(TasteTuning.group(i), before)
            if (r == null) { available = i; break }
            rounds[i] = r
        }
    }
    val answers = remember { mutableStateMapOf<Int, Int>() }
    var index by remember { mutableIntStateOf(0) }
    var heard by remember { mutableIntStateOf(0) }
    val atResult = index >= available
    val sample = if (atResult) null else rounds[index]
    LaunchedEffect(sample, heard) {
        val j = sample?.let { if (heard == 1) it.secondJoint else it.firstJoint }
        j?.let { player.loop.playFrom(it.samples, it.changeFrom - 5 * AudioOut.RATE) } ?: player.loop.play(null)
    }
    var position by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { position = player.loop.position; delay(50) } }
    val given = answers.entries.sortedBy { it.key }.mapNotNull { (i, c) -> rounds[i]?.let { TasteAnswer(it.round.firstParts, it.round.secondParts, c) } }
    val learnt = remember(answers.toMap()) { TasteTuning.learn(before, given) }
    val enough = given.count { it.choice != TasteAnswer.ALIKE } >= TasteTuning.MIN_ANSWERS
    val grayed = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
    val accent = MaterialTheme.colorScheme.primary

    DeskGuideFrame(
        tr("Chaining to your taste", "Encadenado a tu gusto"), index.coerceAtMost(available), available + 1,
        if (atResult) tr("Your taste", "Tu gusto") else tr("Which follows better?", "¿Cuál sigue mejor?"),
        if (atResult) tr(
            "What counts when one song leads into the next, before and after your answers. Lists made from now on follow it; for Rockbox and the web, export the library again.",
            "Lo que cuenta cuando una canción lleva a la siguiente, antes y después de tus respuestas. Las listas que se hagan a partir de ahora lo siguen; para Rockbox y la web, exporta la biblioteca otra vez.",
        ) else tr(
            "The end of a song goes into two songs that could come next. Choose each one to hear it and keep the one that follows better.",
            "El final de una canción entra en dos canciones que podrían venir después. Elige cada una para oírla y quédate con la que sigue mejor.",
        ),
        onClose = onDone,
        onBack = { index--; heard = 0; paused = false },
        onNext = {
            if (atResult) {
                if (enough) DeskTaste.save(state.prefs, learnt)
                onDone()
            } else {
                if (answers[index] == null) answers[index] = TasteAnswer.ALIKE
                index++; heard = 0; paused = false
            }
        },
    ) {
        when {
            available == 0 -> Hint(tr("Too few songs are analysed for chaining. Scan the library first.", "Hay muy pocas canciones analizadas para encadenar. Escanea antes la biblioteca."))
            atResult -> {
                for (g in TasteGroup.entries) {
                    val b = before.share(g)
                    val a = learnt.share(g)
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(deskGroupName(g), fontWeight = FontWeight.SemiBold)
                            Text("${(b * 100).roundToInt()} % → ${(a * 100).roundToInt()} %", color = grayed)
                        }
                        Canvas(Modifier.fillMaxWidth().height(14.dp).padding(vertical = 2.dp)) {
                            val r = CornerRadius(size.height / 2)
                            drawRoundRect(grayed.copy(alpha = 0.15f), cornerRadius = r)
                            drawRoundRect(grayed.copy(alpha = 0.45f), Offset.Zero, Size(size.width * b, size.height / 2), r)
                            drawRoundRect(accent, Offset(0f, size.height / 2), Size(size.width * a, size.height / 2), r)
                        }
                    }
                }
                if (!enough) Hint(tr("Choose a song in at least ${TasteTuning.MIN_ANSWERS} questions to change the weights.", "Elige una canción en al menos ${TasteTuning.MIN_ANSWERS} preguntas para cambiar los pesos."))
                if (!before.isDefault) TextButton(onClick = { DeskTaste.save(state.prefs, ChainTaste.DEFAULT); onDone() }) {
                    Text(tr("Back to Attacca's weights", "Volver a los pesos de Attacca"))
                }
            }
            sample == null -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(tr("Finding tracks in your music…", "Buscando pistas en tu música…"), color = grayed)
            }
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("After ", "Después de ") + sample.from, color = grayed, modifier = Modifier.weight(1f))
                    TextButton(onClick = { paused = !paused }) { Text(if (paused) tr("Play", "Reproducir") else tr("Pause", "Pausa")) }
                }
                val j = if (heard == 1) sample.secondJoint else sample.firstJoint
                Canvas(Modifier.fillMaxWidth().height(16.dp).padding(vertical = 4.dp)) {
                    val r = CornerRadius(size.height / 2)
                    drawRoundRect(accent.copy(alpha = 0.15f), cornerRadius = r)
                    val x0 = size.width * j.changeFrom / j.frames
                    val x1 = size.width * j.changeTo / j.frames
                    drawRoundRect(accent.copy(alpha = 0.4f), Offset(x0, 0f), Size((x1 - x0).coerceAtLeast(4f), size.height), r)
                    drawCircle(accent, size.height * 0.7f, Offset(size.width * position / j.frames, size.height / 2))
                }
                val chosen = answers[index]
                val hint = tr("Choose to hear it", "Elige para oírla")
                DeskChoice("1", sample.first, hint, chosen == TasteAnswer.FIRST || chosen == null && heard == 0) { heard = 0; answers[index] = TasteAnswer.FIRST; paused = false }
                DeskChoice("2", sample.second, hint, chosen == TasteAnswer.SECOND || chosen == null && heard == 1) { heard = 1; answers[index] = TasteAnswer.SECOND; paused = false }
                DeskChoice("=", tr("Both follow as well", "Las dos siguen igual de bien"), tr("Neither is better", "Ninguna es mejor"), chosen == TasteAnswer.ALIKE) { answers[index] = TasteAnswer.ALIKE }
                Hint(tr(
                    "There are no right answers: what you keep is your taste. Next without a choice counts as both alike.",
                    "No hay respuestas correctas: lo que eliges es tu gusto. Siguiente sin elegir cuenta como las dos igual.",
                ))
            }
        }
    }
}
