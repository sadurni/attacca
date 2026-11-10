package com.sadurni.attaccaplg.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sadurni.attacca.dsp.CrossfadeStyle
import com.sadurni.attacca.tuning.JoinAnswers
import com.sadurni.attacca.tuning.JoinStep
import com.sadurni.attacca.tuning.Joints
import com.sadurni.attacca.tuning.LevelAnswers
import com.sadurni.attacca.tuning.LevelStep
import com.sadurni.attacca.tuning.LevelTrack
import com.sadurni.attacca.tuning.LevelTuning
import com.sadurni.attacca.tuning.PairKind
import com.sadurni.attacca.tuning.Pcm
import com.sadurni.attacca.tuning.TransitionTuning
import com.sadurni.attacca.tuning.TuneLoop
import com.sadurni.attaccaplg.audio.AudioOut
import com.sadurni.attaccaplg.audio.Decoder
import com.sadurni.attaccaplg.library.StoredTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** The guides' own output: [loop] to the output in use, on a thread of its own. */
internal class DeskLoopPlayer(private val device: String?) {
    val loop = TuneLoop(AudioOut.RATE)

    @Volatile
    var running = false

    @Volatile
    var playing = true

    fun start() {
        running = true
        Thread({
            val out = runCatching { AudioOut(device) }.getOrNull() ?: return@Thread
            val buffer = FloatArray(1024 * 2)
            try {
                while (running) {
                    if (playing) loop.read(buffer, 1024) else java.util.Arrays.fill(buffer, 0f)
                    out.write(buffer, 1024, 1f)
                }
                out.flush()
            } finally {
                out.close()
            }
        }, "Attacca guide").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
    }
}

/** A stretch of [file] from [fromMs] to [toMs] (null: to its end), as stereo at the output's rate. */
internal fun decodeStretch(file: File, fromMs: Long, toMs: Long?): Pcm? {
    var rate = 0
    var channels = 2
    var frame = 0L
    var from = 0L
    var to = Long.MAX_VALUE
    var out = FloatArray(0)
    var count = 0
    Decoder.decode(file, { f ->
        rate = f.sampleRate; channels = f.channels
        frame = 0; count = 0
        from = fromMs * rate / 1000
        to = toMs?.let { it * rate / 1000 } ?: Long.MAX_VALUE
        out = FloatArray(((minOf(to, from + 60L * rate) - from).coerceAtLeast(rate.toLong())).toInt() * channels)
    }) { samples, frames ->
        for (f in 0 until frames) {
            if (frame in from until to) {
                if ((count + 1) * channels > out.size) out = out.copyOf(out.size * 2)
                System.arraycopy(samples, f * channels, out, count * channels, channels)
                count++
            }
            frame++
        }
    } ?: return null
    if (count == 0) return null
    return Pcm.of(out, channels, count, rate, AudioOut.RATE)
}

/** The guides' tracks from the library: pairs for the transitions, loud and quiet ones for the volume. */
private class DeskPlaySamples(all: List<StoredTrack>) {
    private val tracks = all.filter { it.durationMs > 50_000 && File(it.path).isFile }
    private val lock = Mutex()
    private fun folder(t: StoredTrack) = File(t.path).parent.orEmpty()
    private fun title(t: StoredTrack) = listOf(t.tags.artist, t.tags.title.ifBlank { File(t.path).nameWithoutExtension }).filter { it.isNotBlank() }.joinToString(" — ")

    suspend fun pair(kind: PairKind): Triple<Pcm, Pcm, Pair<String, String>>? = lock.withLock {
        withContext(Dispatchers.IO) {
            if (tracks.size < 2) return@withContext null
            repeat(5) {
                val a: StoredTrack
                val b: StoredTrack
                when (kind) {
                    PairKind.SILENCE -> {
                        a = tracks.filter { it.durationMs - it.audioEndMs >= 1_500 }.randomOrNull() ?: tracks.random()
                        b = tracks.filter { folder(it) != folder(a) }.randomOrNull() ?: tracks.random()
                    }
                    PairKind.ALBUM -> {
                        val pairs = tracks.groupBy { folder(it) }.values.filter { it.size >= 2 }
                            .flatMap { al -> al.sortedWith(compareBy({ it.tags.disc ?: 1 }, { it.tags.track ?: 0 }, { it.path })).zipWithNext() }
                        val (x, y) = pairs.randomOrNull() ?: return@withContext null
                        a = x; b = y
                    }
                    PairKind.DIFFERENT -> {
                        a = tracks.random()
                        b = tracks.filter { folder(it) != folder(a) }.randomOrNull() ?: tracks.random()
                    }
                }
                val tail = decodeStretch(File(a.path), (a.durationMs - Joints.TAIL_SECONDS * 1000L).coerceAtLeast(0), null)
                val head = decodeStretch(File(b.path), 0, Joints.HEAD_SECONDS * 1000L)
                if (tail != null && head != null && tail.frames > AudioOut.RATE * 10) return@withContext Triple(tail, head, title(a) to title(b))
            }
            null
        }
    }

    suspend fun levelPair(): Pair<LevelTrack, LevelTrack>? = lock.withLock {
        withContext(Dispatchers.IO) {
            val known = tracks.filter { it.lufs != null }.sortedBy { it.lufs!! }
            val (loud, quiet) = if (known.size >= 4) {
                val q = known.take(5).random()
                val l = known.takeLast(5).filter { folder(it) != folder(q) }.randomOrNull() ?: known.last()
                l to q
            } else {
                val some = tracks.shuffled().take(2).takeIf { it.size == 2 } ?: return@withContext null
                some[0] to some[1]
            }
            fun level(t: StoredTrack): LevelTrack? {
                val from = (t.durationMs * 0.35).toLong()
                val pcm = decodeStretch(File(t.path), from, from + LevelTuning.SECONDS * 1000L + 500) ?: return null
                return LevelTrack(pcm, title(t), t.lufs ?: LevelTuning.estimateLufs(pcm), null)
            }
            val a = level(loud) ?: return@withContext null
            val b = level(quiet) ?: return@withContext null
            a to b
        }
    }
}

/** A guide's frame in the window. */
@Composable
internal fun DeskGuideFrame(title: String, index: Int, count: Int, stepTitle: String, intro: String, onClose: () -> Unit, onBack: () -> Unit, onNext: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val grayed = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
    PageFrame(title) {
        ScrollColumn {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                Text(tr("Step ${index + 1} of $count", "Paso ${index + 1} de $count"), color = grayed)
                Text(stepTitle, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                Text(intro, modifier = Modifier.padding(vertical = 6.dp))
                content()
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onClose) { Text(tr("Cancel", "Cancelar")) }
                    if (index > 0) TextButton(onClick = onBack) { Text(tr("Back", "Atrás")) }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = onNext) { Text(if (index == count - 1) tr("Save", "Guardar") else tr("Next", "Siguiente")) }
                }
            }
        }
    }
}

@Composable
internal fun DeskChoice(letter: String, title: String, hint: String, selected: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val grayed = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .border(if (selected) 2.dp else 1.dp, if (selected) accent else grayed.copy(alpha = 0.35f), shape)
            .background(if (selected) accent.copy(alpha = 0.12f) else Color.Transparent, shape)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(30.dp).background(if (selected) accent else grayed.copy(alpha = 0.25f), CircleShape), contentAlignment = Alignment.Center) {
            Text(letter, fontWeight = FontWeight.Bold, color = if (selected) Color.White else MaterialTheme.colorScheme.onBackground)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(hint, color = grayed, fontSize = 13.sp)
        }
    }
}

@Composable
private fun DeskPairRow(first: String?, second: String?, nowSecond: Boolean, paused: Boolean, onPause: () -> Unit, onAnother: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val grayed = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        if (first == null) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(tr("Finding tracks in your music…", "Buscando pistas en tu música…"), color = grayed, modifier = Modifier.weight(1f))
        } else {
            Column(Modifier.weight(1f)) {
                Text("1 · $first", color = if (!nowSecond) accent else grayed, fontWeight = if (!nowSecond) FontWeight.SemiBold else FontWeight.Normal)
                Text("2 · ${second.orEmpty()}", color = if (nowSecond) accent else grayed, fontWeight = if (nowSecond) FontWeight.SemiBold else FontWeight.Normal)
            }
            TextButton(onClick = onAnother) { Text(tr("Other tracks", "Otras pistas")) }
            TextButton(onClick = onPause) { Text(if (paused) tr("Play", "Reproducir") else tr("Pause", "Pausa")) }
        }
    }
}

/** The transitions guide in Attacca Desktop: blends and silences heard on real pairs; saved as the player's settings. */
@Composable
fun DeskTransitionsGuide(state: AppState, onDone: () -> Unit) {
    val p = state.player
    val s = p.settings
    var answers by remember {
        mutableStateOf(
            JoinAnswers(
                blend = when {
                    s.crossfadeMs == 0 -> 0
                    s.crossfadeStyle == CrossfadeStyle.AUTOMATIC -> 1
                    s.crossfadeMs <= 5_000 -> 2
                    else -> 3
                },
                silence = if (s.trimSilences) 0 else 1,
                automaticMs = s.crossfadeMs.takeIf { it > 0 } ?: 6_000,
            ),
        )
    }
    var index by remember { mutableIntStateOf(0) }
    val steps = TransitionTuning.steps(answers, albumStep = false)
    val step = steps[index.coerceIn(0, steps.size - 1)]
    var paused by remember { mutableStateOf(false) }
    val player = remember { DeskLoopPlayer(p.device) }
    DisposableEffect(Unit) {
        if (p.playing) p.toggle()
        player.loop.settings = p.dsp
        player.start()
        onDispose { player.stop() }
    }
    LaunchedEffect(paused) { player.playing = !paused }
    val samples = remember { DeskPlaySamples(state.library?.all.orEmpty()) }
    val pairs = remember { mutableStateMapOf<PairKind, Triple<Pcm, Pcm, Pair<String, String>>>() }
    var refresh by remember { mutableIntStateOf(0) }
    val kind = TransitionTuning.pairKind(step)
    LaunchedEffect(kind, refresh) {
        if (pairs[kind] == null || refresh > 0) {
            samples.pair(kind)?.let { pairs[kind] = it }
            refresh = 0
        }
    }
    val pair = pairs[kind]
    val joint = remember(pair, answers, step) { pair?.let { Joints.render(it.first, it.second, TransitionTuning.join(step, answers)) } }
    LaunchedEffect(joint) { joint?.let { player.loop.playFrom(it.samples, it.changeFrom - 4 * AudioOut.RATE) } ?: player.loop.play(null) }
    var position by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { position = player.loop.position; delay(50) } }
    DeskGuideFrame(
        tr("Transitions guide", "Guía de transiciones"), index, steps.size,
        if (step == JoinStep.BLEND) tr("From one song to the next", "De una canción a la siguiente") else tr("Silence at the end", "Silencio al final"),
        if (step == JoinStep.BLEND) tr("Two different songs, the end of one and the start of the other. The bar shows where the change is.", "Dos canciones distintas, el final de una y el principio de la otra. La barra marca dónde está el cambio.")
        else tr("A song with silence at its end (or the next with silence at its start): left out, or kept as recorded.", "Una canción con silencio al final (o la siguiente con silencio al principio): quitado, o como se grabó."),
        onClose = onDone, onBack = { index--; paused = false },
        onNext = {
            if (index == steps.size - 1) {
                p.update {
                    it.copy(
                        crossfadeMs = TransitionTuning.lengthMs(answers),
                        crossfadeStyle = when (answers.blend) { 1 -> CrossfadeStyle.AUTOMATIC; 2, 3 -> CrossfadeStyle.CROSSFADE; else -> it.crossfadeStyle },
                        trimSilences = answers.silence == 0,
                    )
                }
                onDone()
            } else { index++; paused = false }
        },
    ) {
        DeskPairRow(pair?.third?.first, pair?.third?.second, joint != null && position >= joint.changeTo, paused, { paused = !paused }, { refresh++ })
        joint?.let { j ->
            val accent = MaterialTheme.colorScheme.primary
            Canvas(Modifier.fillMaxWidth().height(16.dp).padding(vertical = 4.dp)) {
                val r = CornerRadius(size.height / 2)
                drawRoundRect(accent.copy(alpha = 0.15f), cornerRadius = r)
                val x0 = size.width * j.changeFrom / j.frames
                val x1 = size.width * j.changeTo / j.frames
                drawRoundRect(accent.copy(alpha = 0.4f), Offset(x0, 0f), Size((x1 - x0).coerceAtLeast(4f), size.height), r)
                drawCircle(accent, size.height * 0.7f, Offset(size.width * position / j.frames, size.height / 2))
            }
        }
        val chosen = TransitionTuning.chosen(step, answers)
        for ((i, id) in TransitionTuning.options(step).withIndex()) {
            val (t, h) = deskOptionText(id)
            DeskChoice(('A' + i).toString(), t, h, i == chosen) { answers = TransitionTuning.choose(step, answers, i); paused = false }
        }
    }
}

/** The volume guide in Attacca Desktop: the loudest and quietest songs one after the other, as they are or evened out. */
@Composable
fun DeskLevelGuide(state: AppState, onDone: () -> Unit) {
    val p = state.player
    var answers by remember { mutableStateOf(LevelAnswers(between = if (p.settings.levelLoudness) 1 else 0)) }
    var paused by remember { mutableStateOf(false) }
    val player = remember { DeskLoopPlayer(p.device) }
    DisposableEffect(Unit) {
        if (p.playing) p.toggle()
        player.loop.settings = p.dsp
        player.start()
        onDispose { player.stop() }
    }
    LaunchedEffect(paused) { player.playing = !paused }
    val samples = remember { DeskPlaySamples(state.library?.all.orEmpty()) }
    var pair by remember { mutableStateOf<Pair<LevelTrack, LevelTrack>?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh) { samples.levelPair()?.let { pair = it } }
    val sample = remember(pair, answers) { pair?.let { LevelTuning.render(it.first, it.second, answers, LevelStep.BETWEEN) } }
    var lastPair by remember { mutableStateOf<Pair<LevelTrack, LevelTrack>?>(null) }
    LaunchedEffect(sample) {
        val s = sample ?: return@LaunchedEffect
        if (lastPair === pair) player.loop.replace(s.samples) else player.loop.play(s.samples)
        lastPair = pair
    }
    var position by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { position = player.loop.position; delay(50) } }
    DeskGuideFrame(
        tr("Volume guide", "Guía de volumen"), 0, 1,
        tr("Loud and quiet songs", "Canciones fuertes y suaves"),
        tr("The loudest and the quietest songs of your music, one after the other: as the files are, or brought to one level.", "Las canciones más fuertes y más suaves de tu música, una tras otra: como están los archivos, o llevadas a un mismo nivel."),
        onClose = onDone, onBack = {},
        onNext = { p.update { it.copy(levelLoudness = answers.between == 1) }; onDone() },
    ) {
        DeskPairRow(pair?.first?.title, pair?.second?.title, sample != null && position >= sample.secondFrom, paused, { paused = !paused }, { refresh++ })
        for ((i, id) in LevelTuning.options(LevelStep.BETWEEN).withIndex()) {
            val (t, h) = deskOptionText(id)
            DeskChoice(('A' + i).toString(), t, h, i == answers.between) { answers = LevelTuning.choose(LevelStep.BETWEEN, answers, i); paused = false }
        }
    }
}

private fun deskOptionText(id: String): Pair<String, String> = when (id) {
    "join_gapless" -> tr("One after the other", "Una tras otra") to tr("No blend: each song to its end, the next straight after.", "Sin fundido: cada canción hasta su final y la siguiente justo después.")
    "join_automatic" -> tr("Automatic blend", "Fundido automático") to tr("Each change made to fit how the songs end and start.", "Cada cambio a la medida de cómo acaban y empiezan las canciones.")
    "join_crossfade_4" -> tr("Crossfade, 4 s", "Fundido, 4 s") to tr("A short blend: both songs fade over 4 seconds.", "Un fundido corto: las dos canciones se funden en 4 segundos.")
    "join_crossfade_8" -> tr("Crossfade, 8 s", "Fundido, 8 s") to tr("A long blend: both songs fade over 8 seconds.", "Un fundido largo: las dos canciones se funden en 8 segundos.")
    "silence_trim" -> tr("Leave the silence out", "Quitar el silencio") to tr("The next song comes in where the sound ends.", "La siguiente canción entra donde acaba el sonido.")
    "silence_keep" -> tr("Keep it", "Dejarlo") to tr("Every song as recorded, silences included.", "Cada canción como se grabó, con sus silencios.")
    "level_off" -> tr("As the files are", "Como están los archivos") to tr("Each song at the level it was mastered.", "Cada canción al nivel con que se masterizó.")
    else -> tr("Evened out", "Igualadas") to tr("Songs brought to one level, so there are no jumps.", "Las canciones llevadas a un mismo nivel, sin saltos.")
}

/**
 * The check-up in Attacca Desktop: tracks not analysed, without loudness, damaged, outputs without
 * a sound of their own; and the guides by ear.
 */
@Composable
fun DeskCheckup(state: AppState, onGuide: (String) -> Unit, onDone: () -> Unit) {
    val all = state.library?.all.orEmpty()
    val notAnalysed = all.count { it.features == null && it.error == null }
    val noLoudness = all.count { it.lufs == null && it.error == null }
    val damaged = state.damaged.size
    val outputs = remember { AudioOut.devices() }
    val untuned = outputs.filter { state.prefs["sound.$it"] == null }
    PageFrame(tr("Check-up", "Revisión")) {
        ScrollColumn {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                Hint(tr("What keeps Attacca from sounding and working at its best, and how to fix each thing.", "Lo que impide que Attacca suene y funcione lo mejor posible, y cómo arreglar cada cosa."))
                DeskCheck(all.isNotEmpty(), tr("Library", "Biblioteca"),
                    if (all.isEmpty()) tr("No library yet: choose the music folder in Library.", "Aún no hay biblioteca: elige la carpeta de música en Biblioteca.")
                    else tr("${all.size} tracks.", "${all.size} pistas."))
                DeskCheck(notAnalysed == 0, tr("Analysis", "Análisis"),
                    if (notAnalysed == 0) tr("Every track analysed.", "Todas las pistas analizadas.")
                    else tr("$notAnalysed tracks not analysed: lists and moods leave them out or guess. Library → Scan analyses them.", "$notAnalysed pistas sin analizar: las listas y los estados de ánimo las dejan fuera o adivinan. Biblioteca → Escanear las analiza."),
                    tr("Go to Library", "Ir a Biblioteca").takeIf { notAnalysed > 0 }?.let { it to { state.page = Page.LIBRARY } })
                DeskCheck(noLoudness == 0, tr("Loudness", "Sonoridad"),
                    if (noLoudness == 0) tr("Every track has its loudness measured.", "Todas las pistas tienen su sonoridad medida.")
                    else tr("$noLoudness tracks without measured loudness: they play at their own level. Library → Scan measures them.", "$noLoudness pistas sin sonoridad medida: suenan a su propio nivel. Biblioteca → Escanear las mide."),
                    tr("Go to Library", "Ir a Biblioteca").takeIf { noLoudness > 0 }?.let { it to { state.page = Page.LIBRARY } })
                DeskCheck(damaged == 0, tr("Damaged tracks", "Pistas dañadas"),
                    if (damaged == 0) tr("None found.", "No se ha encontrado ninguna.") else tr("$damaged damaged tracks: Library shows them and repairs them.", "$damaged pistas dañadas: Biblioteca las muestra y las repara."),
                    tr("Go to Library", "Ir a Biblioteca").takeIf { damaged > 0 }?.let { it to { state.page = Page.LIBRARY } })
                DeskCheck(untuned.isEmpty(), tr("Outputs", "Salidas"),
                    if (untuned.isEmpty()) tr("Every output has a sound of its own.", "Cada salida tiene su propio sonido.")
                    else tr("Without a sound of their own: ${untuned.joinToString()}. Choose one in Settings → Playback and run the sound guide.", "Sin sonido propio: ${untuned.joinToString()}. Elige una en Ajustes → Reproducción y usa la guía de sonido."))
                Section(tr("Fine tuning by ear", "Ajuste fino de oído")) {
                    OutlinedButton(onClick = { onGuide("sound") }) { Text(tr("Tune the sound step by step", "Ajustar el sonido paso a paso")) }
                    OutlinedButton(onClick = { onGuide("join") }) { Text(tr("Tune the transitions by ear", "Ajustar las transiciones de oído")) }
                    OutlinedButton(onClick = { onGuide("level") }) { Text(tr("Even out the volume by ear", "Igualar el volumen de oído")) }
                    OutlinedButton(onClick = { onGuide("taste") }) { Text(tr("Chaining to your taste", "Encadenado a tu gusto")) }
                }
                TextButton(onClick = onDone) { Text(tr("Close", "Cerrar")) }
            }
        }
    }
}

@Composable
private fun DeskCheck(ok: Boolean, title: String, detail: String, action: Pair<String, () -> Unit>? = null) {
    val grayed = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 2.dp).size(22.dp).background(if (ok) Color(0xFF3E9E5A) else Color(0xFFD9A21B), CircleShape), contentAlignment = Alignment.Center) {
            Text(if (ok) "✓" else "!", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(detail, color = grayed)
            action?.let { (label, run) -> OutlinedButton(onClick = run, modifier = Modifier.padding(top = 4.dp)) { Text(label) } }
        }
    }
}
