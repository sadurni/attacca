package com.sadurni.attaccaplg.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sadurni.attacca.settings.DspSettings
import com.sadurni.attacca.tuning.Fragment
import com.sadurni.attacca.tuning.FragmentKind
import com.sadurni.attacca.tuning.Fragments
import com.sadurni.attacca.tuning.SoundTuning
import com.sadurni.attacca.tuning.TestSignals
import com.sadurni.attacca.tuning.TuneAnswers
import com.sadurni.attacca.tuning.TuneDevice
import com.sadurni.attacca.tuning.TuneLoop
import com.sadurni.attacca.tuning.TuneStep
import com.sadurni.attaccaplg.audio.AudioOut
import com.sadurni.attaccaplg.audio.Decoder
import com.sadurni.attaccaplg.library.StoredTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/** The sound guide's own output: [loop] to the output in use, on a thread of its own. */
private class DeskTunePlayer(private val device: String?) {
    val loop = TuneLoop(AudioOut.RATE)

    @Volatile
    var running = false

    @Volatile
    var playing = true
    private var thread: Thread? = null

    fun start() {
        running = true
        thread = Thread({
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
        }, "Attacca sound guide").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        thread?.join(600)
    }
}

/** The guide's samples from the library: tracks with plenty of what each step changes. */
private class DeskTuneSamples(private val tracks: List<StoredTrack>) {
    private val lock = Mutex()
    private val ordered = HashMap<FragmentKind, List<StoredTrack>>()
    private val next = HashMap<FragmentKind, Int>()

    suspend fun next(kind: FragmentKind): Fragment = lock.withLock {
        withContext(Dispatchers.IO) {
            val list = ordered.getOrPut(kind) {
                val usable = tracks.filter { it.durationMs > 40_000 && File(it.path).isFile }.shuffled()
                val sorted = Fragments.order(kind, usable) { it.features }
                sorted.take(24).shuffled() + sorted.drop(24)
            }
            val tries = if (kind == FragmentKind.ANY) 1 else 2
            var best: Pair<Fragment, Double>? = null
            var looked = 0
            var i = next[kind] ?: 0
            while (looked < tries && i < list.size) {
                val found = runCatching { sample(list[i++], kind) }.getOrNull() ?: continue
                looked++
                if (best == null || found.second > best.second) best = found
            }
            next[kind] = if (i >= list.size) 0 else i
            best?.first ?: Fragments.groove(AudioOut.RATE)
        }
    }

    private fun sample(track: StoredTrack, kind: FragmentKind): Pair<Fragment, Double>? {
        var rate = 0
        var channels = 2
        var skip = 0L
        var want = 0
        var collected = FloatArray(0)
        var count = 0
        Decoder.decode(File(track.path), { f ->
            // (A retry starts again from the beginning.)
            rate = f.sampleRate; channels = f.channels
            skip = (track.durationMs * Fragments.SEARCH_FROM).toLong() * rate / 1000
            want = Fragments.SEARCH_SECONDS * rate
            collected = FloatArray(want * channels)
            count = 0
        }) { samples, frames ->
            for (fr in 0 until frames) {
                if (skip > 0) { skip--; continue }
                if (count >= want) return@decode
                System.arraycopy(samples, fr * channels, collected, count * channels, channels)
                count++
            }
        } ?: return null
        if (count < want / 2) return null
        val pcm = collected.copyOf(count * channels)
        val window = Fragments.bestWindow(pcm, channels, rate, kind) ?: return null
        val title = listOf(track.tags.artist, track.tags.title.ifBlank { File(track.path).nameWithoutExtension })
            .filter { it.isNotBlank() }.joinToString(" — ")
        return Fragments.cut(pcm, channels, rate, window.startFrame, title, AudioOut.RATE) to window.score
    }
}

private enum class DeskReach { TONES, LINE }

/**
 * The sound guide in Attacca Desktop, as on the phone: each step heard A/B/C on a sample of the
 * library (or a sound of the guide's own) through the output in use; saved as that output's sound.
 */
@Composable
fun DeskSoundGuide(state: AppState, onDone: () -> Unit) {
    val p = state.player
    val accent = MaterialTheme.colorScheme.primary
    val grayed = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
    val before = remember { p.dsp }
    var answers by remember { mutableStateOf(TuneAnswers(device = TuneDevice.HEADPHONES)) }
    var index by remember { mutableIntStateOf(0) }
    val steps = SoundTuning.steps(answers.device)
    val step = steps[index.coerceIn(0, steps.size - 1)]
    var compare by remember { mutableIntStateOf(1) }
    var reachMode by remember { mutableStateOf(DeskReach.TONES) }
    var reachTone by remember { mutableIntStateOf(0) }
    var reachDone by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }

    val player = remember { DeskTunePlayer(p.device) }
    DisposableEffect(Unit) {
        // The music pauses while the guide plays.
        if (p.playing) p.toggle()
        player.start()
        onDispose { player.stop() }
    }
    LaunchedEffect(paused) { player.playing = !paused }

    val samples = remember { DeskTuneSamples(state.library?.all.orEmpty()) }
    val found = remember { mutableStateMapOf<FragmentKind, Fragment>() }
    var refresh by remember { mutableIntStateOf(0) }
    val kind = SoundTuning.fragmentKind(step)
    LaunchedEffect(kind, refresh) {
        if (kind != null && (found[kind] == null || refresh > 0)) {
            val another = refresh > 0
            val f = samples.next(kind)
            if (another || found[kind] == null) found[kind] = f
            refresh = 0
        }
    }
    LaunchedEffect(answers.device) {
        for (k in steps.mapNotNull { SoundTuning.fragmentKind(it) }.distinct()) {
            if (found[k] == null) samples.next(k).let { if (found[k] == null) found[k] = it }
        }
    }
    val fragment = kind?.let { found[it] }
    LaunchedEffect(step, fragment, reachMode, reachTone, reachDone) {
        val loop = player.loop
        when (step) {
            TuneStep.SIDES -> loop.play(TestSignals.sides(AudioOut.RATE))
            TuneStep.BASS_REACH -> loop.play(
                when {
                    reachDone -> null
                    reachMode == DeskReach.LINE -> TestSignals.bassLine(AudioOut.RATE)
                    else -> TestSignals.tone(AudioOut.RATE, SoundTuning.REACH_TONES[reachTone.coerceAtMost(SoundTuning.REACH_TONES.size - 1)])
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
    var position by remember { mutableIntStateOf(0) }
    LaunchedEffect(step) {
        while (step == TuneStep.SIDES || step == TuneStep.BASS_REACH) {
            position = player.loop.position
            delay(50)
        }
    }

    fun go(to: Int) {
        index = to.coerceIn(0, steps.size - 1)
        paused = false
    }

    PageFrame(tr("Sound guide", "Guía de sonido")) {
        ScrollColumn {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                Text(
                    tr("Step ${index + 1} of ${steps.size}", "Paso ${index + 1} de ${steps.size}") + " · " +
                        (p.device ?: tr("the system's output", "la salida del sistema")),
                    color = grayed,
                )
                Text(stepTitle(step), fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                Text(stepIntro(step), modifier = Modifier.padding(vertical = 6.dp))
                if (step == TuneStep.DEVICE) Hint(tr(
                    "Start with the volume low and raise it to where you usually listen. Each choice is heard on a sample: click A, B or C to compare them.",
                    "Empieza con el volumen bajo y súbelo hasta donde sueles escuchar. Cada opción se oye sobre una muestra: pulsa A, B o C para compararlas.",
                ))
                if (kind != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                        if (fragment == null) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(tr("Finding a sample in your music…", "Buscando una muestra en tu música…"), color = grayed, modifier = Modifier.weight(1f))
                        } else {
                            Text(
                                fragment.title?.let { tr("Sample: $it", "Muestra: $it") } ?: tr("Sample: a groove made by Attacca", "Muestra: un ritmo hecho por Attacca"),
                                color = grayed, modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { refresh++ }) { Text(tr("Another sample", "Otra muestra")) }
                            TextButton(onClick = { paused = !paused }) { Text(if (paused) tr("Play", "Reproducir") else tr("Pause", "Pausa")) }
                        }
                    }
                }
                when (step) {
                    TuneStep.SIDES -> {
                        val part = TestSignals.sidesPart(position, AudioOut.RATE)
                        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                            for ((i, label) in listOf(tr("Left", "Izquierda"), tr("Centre", "Centro"), tr("Right", "Derecha")).withIndex()) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(Modifier.size(24.dp).background(if (part == i) accent else grayed.copy(alpha = 0.3f), CircleShape))
                                    Text(label, modifier = Modifier.padding(top = 4.dp))
                                }
                            }
                        }
                    }
                    TuneStep.BASS_REACH -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                            for (m in DeskReach.entries) {
                                val on = m == reachMode
                                OutlinedButton(onClick = { reachMode = m; reachTone = 0; reachDone = false }) {
                                    Text(if (m == DeskReach.TONES) tr("Tones", "Tonos") else tr("Bass line", "Línea de bajo"), color = if (on) accent else MaterialTheme.colorScheme.onBackground,
                                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
                                }
                            }
                        }
                        val reach = answers.bassReachHz
                        if (reachDone) {
                            Text(
                                if (reach == null || reach >= 180) tr("It plays hardly any deep bass.", "Casi no da graves profundos.")
                                else tr("It goes down to about $reach Hz.", "Llega hasta unos $reach Hz."),
                                fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 8.dp),
                            )
                            Hint(tr("The next step offers bass to suit it.", "El paso siguiente ofrece graves a su medida."))
                            OutlinedButton(onClick = { reachTone = 0; reachDone = false }) { Text(tr("Again", "Otra vez")) }
                        } else if (reachMode == DeskReach.TONES) {
                            Text("${SoundTuning.REACH_TONES[reachTone.coerceAtMost(SoundTuning.REACH_TONES.size - 1)]} Hz", fontSize = 32.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(onClick = {
                                    answers = answers.copy(bassReachHz = SoundTuning.REACH_TONES[reachTone], bass = -1)
                                    if (reachTone >= SoundTuning.REACH_TONES.size - 1) reachDone = true else reachTone++
                                }) { Text(tr("I hear it", "Lo oigo")) }
                                OutlinedButton(onClick = {
                                    if (reachTone == 0) answers = answers.copy(bassReachHz = 180, bass = -1)
                                    reachDone = true
                                }) { Text(tr("I don't hear it", "No lo oigo")) }
                            }
                        } else {
                            val note = TestSignals.bassLineNote(position, AudioOut.RATE)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                                for ((i, hz) in TestSignals.BASS_NOTES.withIndex()) {
                                    val on = i == note
                                    Text(
                                        "${hz.roundToInt()} Hz",
                                        color = if (on) Color.White else grayed,
                                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                                        modifier = Modifier.background(if (on) accent else grayed.copy(alpha = 0.15f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
                                    )
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(onClick = {
                                    val hz = if (note == 0) 180 else TestSignals.BASS_NOTES[note - 1].roundToInt()
                                    answers = answers.copy(bassReachHz = hz, bass = -1)
                                    reachDone = true
                                }) { Text(tr("Now I don't hear it", "Ahora no lo oigo")) }
                                OutlinedButton(onClick = {
                                    answers = answers.copy(bassReachHz = TestSignals.BASS_NOTES.last().roundToInt(), bass = -1)
                                    reachDone = true
                                }) { Text(tr("I hear them all", "Los oigo todos")) }
                            }
                        }
                    }
                    else -> Unit
                }
                val options = SoundTuning.options(step, answers)
                val chosen = if (step == TuneStep.COMPARE) compare else SoundTuning.chosen(step, answers)
                for ((i, option) in options.withIndex()) {
                    val selected = i == chosen
                    val shape = RoundedCornerShape(12.dp)
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            .border(if (selected) 2.dp else 1.dp, if (selected) accent else grayed.copy(alpha = 0.35f), shape)
                            .background(if (selected) accent.copy(alpha = 0.12f) else Color.Transparent, shape)
                            .clickable {
                                if (step == TuneStep.COMPARE) compare = i else answers = SoundTuning.choose(step, answers, i)
                                paused = false
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(30.dp).background(if (selected) accent else grayed.copy(alpha = 0.25f), CircleShape), contentAlignment = Alignment.Center) {
                            Text(('A' + i).toString(), fontWeight = FontWeight.Bold, color = if (selected) Color.White else MaterialTheme.colorScheme.onBackground)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(optionText(option.id).first, fontWeight = FontWeight.SemiBold)
                                if (option.suggested && step != TuneStep.COMPARE) {
                                    Spacer(Modifier.width(8.dp))
                                    Text(tr("Suggested", "Sugerida"), color = accent, fontSize = 12.sp,
                                        modifier = Modifier.border(1.dp, accent, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
                                }
                            }
                            optionText(option.id).second?.let { Text(it, color = grayed, fontSize = 13.sp) }
                        }
                    }
                }
                if (step == TuneStep.SIDES) {
                    Text(tr("Balance", "Balance") + "  " + (answers.balance * 100).roundToInt(), modifier = Modifier.padding(top = 8.dp))
                    Slider(answers.balance, { answers = answers.copy(balance = (it * 50).roundToInt() / 50f) }, valueRange = -0.3f..0.3f)
                }
                if (step == TuneStep.COMPARE) Hint(tr(
                    "Save to keep them. Everything stays editable in Sound, and the guide can be run again.",
                    "Guarda para quedártelas. Todo sigue editable en Sonido y la guía se puede repetir.",
                ))
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDone) { Text(tr("Cancel", "Cancelar")) }
                    if (index > 0) TextButton(onClick = { go(index - 1) }) { Text(tr("Back", "Atrás")) }
                    Spacer(Modifier.weight(1f))
                    val last = index == steps.size - 1
                    Button(onClick = {
                        if (last) {
                            val result = SoundTuning.settings(before, answers)
                            p.updateDsp { result }
                            onDone()
                        } else {
                            go(index + 1)
                        }
                    }) { Text(if (last) tr("Save", "Guardar") else tr("Next", "Siguiente")) }
                }
            }
        }
    }
}

private fun stepTitle(step: TuneStep): String = when (step) {
    TuneStep.DEVICE -> tr("What is it?", "¿Qué es?")
    TuneStep.SIDES -> tr("Left and right", "Izquierda y derecha")
    TuneStep.BASS_REACH -> tr("How deep does it go?", "¿Hasta dónde llegan sus graves?")
    TuneStep.BASS -> tr("Bass", "Graves")
    TuneStep.TREBLE -> tr("Treble", "Agudos")
    TuneStep.CROSSFEED -> tr("In front of you", "Delante de ti")
    TuneStep.WIDTH -> tr("Width", "Amplitud")
    TuneStep.LEVELS -> tr("Quiet and loud", "Suave y fuerte")
    TuneStep.COMPARE -> tr("Before and after", "Antes y después")
}

private fun stepIntro(step: TuneStep): String = when (step) {
    TuneStep.DEVICE -> tr("What the music comes out of now decides the steps and what each one suggests.", "Por dónde sale ahora la música decide los pasos y lo que sugiere cada uno.")
    TuneStep.SIDES -> tr(
        "A soft sound goes left, centre and right. The centre one should sound right in the middle; if not, move the balance. With one earbud, or a single speaker, both sides as one is better.",
        "Un sonido suave pasa por la izquierda, el centro y la derecha. El del centro debe sonar justo en medio; si no, mueve el balance. Con un solo auricular, o un único altavoz, es mejor los dos lados en uno.",
    )
    TuneStep.BASS_REACH -> tr(
        "Deep notes play one after another. Say when you stop hearing them: below that the device plays no bass, and the next step offers what helps.",
        "Suenan notas graves una tras otra. Di cuándo dejas de oírlas: por debajo el aparato no da graves, y el paso siguiente ofrece lo que ayuda.",
    )
    TuneStep.BASS -> tr("The same music with more or less bass. Keep what sounds full without booming.", "La misma música con más o menos graves. Quédate con lo que suene lleno sin retumbar.")
    TuneStep.TREBLE -> tr("Cymbals, breath and air: darker if it sounds harsh, brighter if it sounds dull.", "Platillos, respiración y aire: más oscuro si suena áspero, más brillante si suena apagado.")
    TuneStep.CROSSFEED -> tr(
        "On headphones each side reaches one ear only. A little of each in the other ear puts the music in front of you, as from speakers.",
        "Con auriculares cada lado llega a un solo oído. Un poco de cada uno en el otro oído pone la música delante de ti, como desde altavoces.",
    )
    TuneStep.WIDTH -> tr(
        "How far apart the two sides sound: narrower if the speakers are far apart, wider if they are close together.",
        "Cuánto se separan los dos lados: más estrecho si los altavoces están lejos uno del otro, más amplio si están juntos.",
    )
    TuneStep.LEVELS -> tr(
        "Quiet parts brought closer to loud ones: a help with noise around (the car, the street), not needed in a quiet room.",
        "Las partes suaves más cerca de las fuertes: ayuda con ruido alrededor (el coche, la calle); en una sala tranquila no hace falta.",
    )
    TuneStep.COMPARE -> tr("All your choices together against the sound you had.", "Todas tus elecciones juntas frente al sonido que tenías.")
}

/** The name and the line under it of each choice. */
private fun optionText(id: String): Pair<String, String?> = when (id) {
    "headphones" -> tr("Headphones", "Auriculares") to null
    "earbuds" -> tr("Earbuds", "Auriculares intraurales") to null
    "phone_speaker" -> tr("Laptop speakers", "Altavoces del portátil") to null
    "portable_speaker" -> tr("Portable speaker", "Altavoz portátil") to null
    "car" -> tr("Car", "Coche") to null
    "home_stereo" -> tr("Home stereo", "Equipo de música") to null
    "stereo" -> tr("Stereo", "Estéreo") to tr("Each side as recorded.", "Cada lado como se grabó.")
    "mono" -> tr("Both sides as one", "Los dos lados en uno") to tr("For one earbud, or a single speaker.", "Para un solo auricular, o un único altavoz.")
    "bass_none", "treble_same", "width_same", "levels_off" -> tr("As it is", "Tal cual") to tr("No change.", "Sin cambios.")
    "bass_gentle" -> tr("A little more", "Un poco más") to tr("A gentle lift of the low end.", "Un realce suave de los graves.")
    "bass_more" -> tr("More", "Más") to tr("A stronger lift, for devices with deep bass.", "Un realce mayor, para aparatos con graves profundos.")
    "bass_virtual_light" -> tr("Harmonics, light", "Armónicos, suave") to tr("The bass it cannot play, heard through its harmonics.", "Los graves que no puede dar, oídos a través de sus armónicos.")
    "bass_virtual" -> tr("Harmonics", "Armónicos") to tr("More of the bass it cannot play, heard through its harmonics.", "Más de los graves que no puede dar, oídos a través de sus armónicos.")
    "treble_darker" -> tr("Darker", "Más oscuro") to tr("Softer highs.", "Agudos más suaves.")
    "treble_brighter" -> tr("Brighter", "Más brillante") to tr("Clearer highs.", "Agudos más claros.")
    "crossfeed_off" -> tr("Off", "Desactivado") to tr("Each side in one ear.", "Cada lado en un oído.")
    "crossfeed_light" -> tr("Light", "Ligero") to tr("A little of each side in the other ear.", "Un poco de cada lado en el otro oído.")
    "crossfeed_strong" -> tr("Strong", "Fuerte") to tr("As speakers in front of you.", "Como altavoces delante de ti.")
    "crossfeed_speakers" -> tr("Virtual speakers", "Altavoces virtuales") to tr("Two speakers in a room, modelled.", "Dos altavoces en una sala, simulados.")
    "width_narrower" -> tr("Narrower", "Más estrecho") to tr("The sides closer together.", "Los lados más juntos.")
    "width_wider" -> tr("Wider", "Más amplio") to tr("The sides further apart.", "Los lados más separados.")
    "levels_gentle" -> tr("A little even", "Un poco igualado") to tr("Quiet parts a bit louder.", "Las partes suaves un poco más altas.")
    "levels_even" -> tr("Even", "Igualado") to tr("Quiet and loud close together, for noisy places.", "Suave y fuerte muy cerca, para sitios con ruido.")
    "before" -> tr("Before", "Antes") to tr("The sound you had.", "El sonido que tenías.")
    else -> tr("After", "Después") to tr("Your choices.", "Tus elecciones.")
}
