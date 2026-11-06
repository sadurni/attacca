package com.sadurni.attaccaplg.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sadurni.attacca.chain.Mood
import com.sadurni.attacca.library.InclusionMode
import com.sadurni.attaccaplg.audio.Decoder
import com.sadurni.attaccaplg.generate.Inclusions
import com.sadurni.attaccaplg.generate.Library
import com.sadurni.attaccaplg.library.StoredTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

fun moodName(mood: Mood): String = when (mood) {
    Mood.ENERGETIC -> tr("Energetic", "Enérgico")
    Mood.CHEERFUL -> tr("Cheerful", "Alegre")
    Mood.CALM -> tr("Calm", "Tranquilo")
    Mood.MELANCHOLIC -> tr("Melancholic", "Melancólico")
    Mood.TENSE -> tr("Tense", "Tenso")
}

/** A sound family in words, in the window's language (the list file names stay in English). */
fun familyLabel(library: Library, family: Int): String {
    val traits = library.generator.classification.familyNames.getOrNull(family)?.traits ?: return "${family + 1}"
    return traits.joinToString(", ") { (t, high) -> traitWord(t, high) }
}

private fun traitWord(t: com.sadurni.attacca.chain.Trait, high: Boolean): String {
    val en = Library.traitWord(t, high)
    return tr(en, when (en) {
        "fast" -> "rápido"
        "slow" -> "lento"
        "bright" -> "brillante"
        "dark" -> "oscuro"
        "percussive" -> "percusivo"
        "smooth" -> "suave"
        "bassy" -> "con graves"
        "light" -> "ligero"
        "distorted" -> "distorsionado"
        "clean" -> "limpio"
        "dynamic" -> "dinámico"
        "compressed" -> "comprimido"
        "vocal" -> "con voz"
        "instrumental" -> "instrumental"
        "loud" -> "fuerte"
        "quiet" -> "flojo"
        else -> en
    })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LibraryPage(state: AppState) {
    PageFrame(tr("Library", "Biblioteca")) {
        val scroll = rememberScrollState()
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().padding(end = 12.dp).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                FolderSection(state)
                val library = state.library
                if (library != null) {
                    SummarySection(state, library)
                    ClassesSection(state, library)
                }
                InclusionSection(state)
                androidx.compose.foundation.layout.Spacer(Modifier.padding(4.dp))
            }
            PageScrollbar(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

@Composable
private fun FolderSection(state: AppState) {
    Section(tr("Music folder", "Carpeta de música")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                state.musicRoot ?: if (state.library != null) {
                    tr(
                        "None: the library was imported without its music. Choose the music folder to scan it; what was measured is kept.",
                        "Ninguna: la biblioteca se importó sin su música. Elige la carpeta de música para escanearla; se conserva lo medido.",
                    )
                } else {
                    tr("None chosen yet", "Sin elegir todavía")
                },
                Modifier.weight(1f),
                fontWeight = FontWeight.Medium,
            )
            OutlinedButton(enabled = state.busy == null, onClick = {
                val folder = Dialogs.folder(tr("Music folder", "Carpeta de música"), state.musicRoot) ?: return@OutlinedButton
                val other = state.library != null && state.musicRoot != null && File(state.musicRoot!!).absoluteFile != folder.absoluteFile
                val sure = !other || javax.swing.JOptionPane.showConfirmDialog(
                    null,
                    tr(
                        "The library holds the tracks of ${state.musicRoot}. Scanning another folder forgets them (and measures the new one). Go on?",
                        "La biblioteca tiene las pistas de ${state.musicRoot}. Escanear otra carpeta las olvida (y mide la nueva). ¿Seguir?",
                    ),
                    "Attacca Desktop", javax.swing.JOptionPane.OK_CANCEL_OPTION,
                ) == javax.swing.JOptionPane.OK_OPTION
                if (sure) state.scan(folder)
            }) { Text(tr("Choose…", "Elegir…")) }
            val scan = state.scan
            if (scan != null) {
                Button(onClick = state::stopScan) { Text(tr("Stop", "Parar")) }
            } else {
                Button(enabled = state.busy == null && state.musicRoot != null, onClick = {
                    state.musicRoot?.let(::File)?.takeIf { it.isDirectory }?.let(state::scan)
                        ?: run { state.message = tr("The music folder is not there.", "La carpeta de música no está.") }
                }) { Text(tr("Scan", "Escanear")) }
            }
        }
        val scan = state.scan
        if (scan != null) {
            if (scan.todo == 0) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Hint(tr("Looking for changes…", "Buscando cambios…"))
            } else {
                LinearProgressIndicator(progress = { scan.done.toFloat() / scan.todo }, modifier = Modifier.fillMaxWidth())
                val elapsed = (System.currentTimeMillis() - scan.started).coerceAtLeast(1)
                val rate = scan.done * 1000.0 / elapsed
                val left = if (rate > 0) ((scan.todo - scan.done) / rate * 1000).toLong() else 0
                Hint(
                    "${scan.done} / ${scan.todo}  ·  ${num(rate)} " + tr("per second", "por segundo") + "  ·  " +
                        tr("left ", "quedan ") + duration(left) + "\n" + scan.path,
                )
            }
        }
        state.lastScan?.let { r ->
            Hint(
                tr(
                    "Last scan: ${r.total} tracks, ${r.analysed} measured, ${r.failed} failed, ${r.unchanged} unchanged, ${r.removed} removed (${num(r.millis / 1000.0)} s).",
                    "Último escaneo: ${r.total} pistas, ${r.analysed} medidas, ${r.failed} con fallos, ${r.unchanged} sin cambios, ${r.removed} quitadas (${num(r.millis / 1000.0)} s).",
                ),
            )
        }
        Hint(
            tr(
                "Scanning reads the tags and measures each new or changed track (the edges that chain lists, sound features, loudness and the silence at the start and end); unchanged tracks are not measured again. A library measured by Attacca on the phone can be brought in from the Attacca page instead.",
                "Escanear lee las etiquetas y mide cada pista nueva o cambiada (los bordes que encadenan las listas, los rasgos del sonido, la sonoridad y el silencio del principio y el final); las que no cambian no se vuelven a medir. También se puede traer la biblioteca que Attacca midió en el móvil, desde la página Attacca.",
            ),
        )
        if (state.library == null && !state.loading && state.scan == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Is the collection already measured by Attacca on the phone?", "¿La colección ya la ha medido Attacca en el móvil?"), Modifier.weight(1f))
                TextButton(onClick = { state.page = Page.EXCHANGE }) { Text(tr("Bring it from Attacca", "Traerla de Attacca")) }
            }
        }
        if (Decoder.ffmpeg == null) {
            Hint(
                tr(
                    "MP3, Ogg Vorbis, FLAC, WAV and AIFF are measured; for AAC, M4A, WMA, Opus and others, install ffmpeg (it is used when it is on the PATH).",
                    "Se miden MP3, Ogg Vorbis, FLAC, WAV y AIFF; para AAC, M4A, WMA, Opus y otros, instala ffmpeg (se usa si está en el PATH).",
                ),
            )
        }
    }
}

@Composable
private fun SummarySection(state: AppState, library: Library) {
    val counts = remember(library) {
        val all = library.all
        listOf(
            all.size,
            all.count { it.features != null },
            all.count { it.head != null && it.tail != null },
            // Silences may come without the waveform (measured at the ends only, on the phone).
            all.count { it.error == null && it.audioEndMs <= 0 },
            all.count { it.error != null },
        )
    }
    var showFailed by remember { mutableStateOf(false) }
    Section(tr("Tracks", "Pistas")) {
        Text(
            tr(
                "${counts[0]} tracks: ${counts[1]} with sound features, ${counts[2]} with both edges.",
                "${counts[0]} pistas: ${counts[1]} con rasgos del sonido, ${counts[2]} con los dos bordes.",
            ),
        )
        if (counts[3] > 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Hint(
                    tr(
                        "${counts[3]} without their silences (brought from the phone): their lists do not skip the silence at the edges. “Measure them” finds only those, keeping the rest.",
                        "${counts[3]} sin sus silencios (traídas del móvil): sus listas no saltan el silencio de los extremos. «Medirlas» busca solo eso y conserva lo demás.",
                    ),
                    Modifier.weight(1f),
                )
                TextButton(enabled = state.busy == null && state.musicRoot != null, onClick = {
                    state.musicRoot?.let(::File)?.takeIf { it.isDirectory }?.let(state::measureSilences)
                }) { Text(tr("Measure them", "Medirlas")) }
            }
        }
        if (counts[4] > 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("${counts[4]} could not be measured.", "${counts[4]} no se han podido medir."), Modifier.weight(1f))
                TextButton(onClick = { showFailed = !showFailed }) { Text(if (showFailed) tr("Hide", "Ocultar") else tr("Show", "Ver")) }
            }
            if (showFailed) {
                library.all.filter { it.error != null }.groupBy { it.error }.forEach { (error, list) ->
                    Hint("${list.size} × $error")
                    list.take(5).forEach { Hint("    " + it.path) }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClassesSection(state: AppState, library: Library) {
    val (moods, families) = remember(library) {
        val n = library.generator.classification.moods.size
        val m = Mood.entries.associateWith { mood -> library.generator.classification.moods.count { it == mood } }
        val f = library.generator.classification.familyNames.indices.associateWith { i -> (0 until n).count { library.generator.familyOf(it) == i } }
        m to f
    }
    fun listOf(apply: ListsForm.() -> Unit) {
        state.lists.clearFilters()
        state.lists.kind = ListKind.FILTERS
        state.lists.name = ""
        state.lists.apply()
        state.page = Page.LISTS
    }
    Section(tr("Moods and sound families", "Estados de ánimo y familias de sonido")) {
        Hint(
            tr(
                "Measured over this collection, as Attacca does on the phone. Click one to make a list of it.",
                "Calculados sobre esta colección, como hace Attacca en el móvil. Pulsa uno para hacer una lista con él.",
            ),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((mood, count) in moods) {
                AssistChip(onClick = { listOf { this.moods = setOf(mood) } }, label = { Text("${moodName(mood)}  $count") })
            }
        }
        HorizontalDivider()
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for ((family, count) in families) {
                Row(
                    Modifier.fillMaxWidth().clickable { listOf { this.families = setOf(family) } }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${family + 1}.", Modifier.width(32.dp), color = MaterialTheme.colorScheme.primary)
                    Text(familyLabel(library, family), Modifier.weight(1f))
                    Text("$count")
                }
            }
        }
    }
}

@Composable
private fun InclusionSection(state: AppState) {
    Section(tr("Which tracks lists may use", "Qué pistas pueden usar las listas")) {
        Hint(
            tr(
                "An inclusion list (M3U8, e.g. Attacca's, or Attacca's rules file .rules.txt, worked out again each time): lists made with \"Only included\" take only the tracks it allows (or all but the ones it holds).",
                "Una lista de inclusión (M3U8, p. ej. la de Attacca, o el archivo de reglas de Attacca .rules.txt, que se vuelve a calcular cada vez): las listas hechas con «Solo incluidas» toman solo las pistas que permite (o todas menos las que tiene).",
            ),
        )
        val list = state.inclusionList
        Text(list ?: tr("No list: every track.", "Sin lista: todas las pistas."), fontWeight = FontWeight.Medium)
        if (list != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = state.inclusionMode == InclusionMode.ALLOW, onClick = { state.setInclusion(File(list), InclusionMode.ALLOW) })
                Text(tr("Only the tracks in the list", "Solo las pistas de la lista"))
                RadioButton(selected = state.inclusionMode == InclusionMode.BLOCK, onClick = { state.setInclusion(File(list), InclusionMode.BLOCK) })
                Text(tr("Every track but those in the list", "Todas menos las de la lista"))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                Dialogs.open(tr("Inclusion list", "Lista de inclusión"), list ?: state.musicRoot, "*.m3u8;*.m3u;*.txt")?.let { state.setInclusion(it, state.inclusionMode) }
            }) { Text(tr("Choose list…", "Elegir lista…")) }
            if (list != null) {
                OutlinedButton(onClick = { Dialogs.show(File(list).parentFile) }) { Text(tr("Show", "Mostrar")) }
                TextButton(onClick = { state.setInclusion(null, state.inclusionMode) }) { Text(tr("Every track", "Todas las pistas")) }
            }
            OutlinedButton(enabled = state.library != null && state.busy == null, onClick = {
                val beets = Dialogs.open(tr("beets library", "Biblioteca de beets"), null, "*.db;*.blb") ?: return@OutlinedButton
                val library = state.library ?: return@OutlinedButton
                state.run(tr("Reading beets", "Leyendo beets")) {
                    val result = withContext(Dispatchers.IO) { Inclusions.importBeets(beets, library) }
                    val out = File(state.db.file.absoluteFile.parentFile, "inclusion.m3u8")
                    withContext(Dispatchers.IO) { Inclusions.writeList(result.included, out) }
                    state.setInclusion(out, InclusionMode.ALLOW)
                    state.message = tr(
                        "${result.included.size} included, ${result.excluded} left out, ${result.unmatched} not in this library.",
                        "${result.included.size} incluidas, ${result.excluded} excluidas, ${result.unmatched} que no están en esta biblioteca.",
                    )
                }
            }) { Text(tr("From beets' marks…", "De las marcas de beets…")) }
        }
    }
}

// ---------- Tracks ----------

@Composable
fun TracksPage(state: AppState) {
    PageFrame(tr("Tracks", "Pistas")) {
        val library = state.library
        if (library == null) {
            Text(if (state.loading) tr("Loading…", "Cargando…") else tr("The library is empty: scan a music folder first.", "La biblioteca está vacía: escanea antes una carpeta de música."))
            return@PageFrame
        }
        var query by remember { mutableStateOf("") }
        var selected by state::trackSelected
        var sort by remember { mutableStateOf(0) } // column; negative: descending
        val hay = remember(library) { library.all.map { t -> listOf(t.path, t.tags.artist, t.tags.title, t.tags.album).joinToString(" ").lowercase() } }
        val shown = remember(library, query) {
            val words = query.lowercase().split(' ').filter { it.isNotBlank() }
            if (words.isEmpty()) library.all else library.all.filterIndexed { i, _ -> words.all { it in hay[i] } }
        }
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(query, { query = it }, Modifier.width(420.dp), singleLine = true, label = { Text(tr("Search: artist, title, album, folder", "Buscar: artista, título, álbum, carpeta")) })
                Text(tr("${shown.size} tracks", "${shown.size} pistas"))
            }
            // The chosen track's actions in a row of their own, so none is squeezed on a narrow window.
            val pick = selected?.let(library.byPath::get)
            if (pick != null) androidx.compose.foundation.layout.Spacer(Modifier.padding(4.dp))
            Row(
                Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (pick != null) {
                    // From the track chosen on, as the list shows them.
                    Button(onClick = {
                        val paths = sortTracks(library, shown, sort).map { it.path }
                        state.player.playPaths(paths, paths.indexOf(pick.path).coerceAtLeast(0))
                    }) { Text(tr("Play", "Reproducir")) }
                    OutlinedButton(onClick = { state.player.enqueue(listOf(pick.path), next = true) }) { Text(tr("Play next", "Reproducir después")) }
                    OutlinedButton(onClick = { state.player.enqueue(listOf(pick.path), next = false) }) { Text(tr("Add to the queue", "Añadir a la cola")) }
                }
                if (pick != null && pick.features != null) {
                    Button(onClick = {
                        state.lists.kind = ListKind.SEED
                        state.lists.seed = pick.path
                        state.page = Page.LISTS
                    }) { Text(tr("List from this track", "Lista desde esta pista")) }
                }
            }
            androidx.compose.foundation.layout.Spacer(Modifier.padding(6.dp))
            val sorted = remember(shown, sort) { sortTracks(library, shown, sort) }
            TrackHeader(sort) { column -> sort = if (sort == column) -column else column }
            val listState = rememberLazyListState()
            Box(Modifier.fillMaxSize()) {
                LazyColumn(Modifier.fillMaxSize().padding(end = 12.dp), state = listState) {
                    items(sorted, key = { it.path }) { t ->
                        TrackMenuArea(state, t) {
                            TrackRow(library, t, t.path == selected, onDoubleClick = {
                                val paths = sorted.map { it.path }
                                state.player.playPaths(paths, paths.indexOf(t.path).coerceAtLeast(0))
                            }) { selected = if (selected == t.path) null else t.path }
                        }
                    }
                }
                PageScrollbar(listState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        }
    }
}

/** Sort columns: 1 title, 2 artist, 3 album, 4 year, 5 mood, 6 family, 7 BPM, 8 LUFS, 9 time; 0 by folder. */
private fun sortTracks(library: Library, tracks: List<StoredTrack>, sort: Int): List<StoredTrack> {
    val by: Comparator<StoredTrack> = when (kotlin.math.abs(sort)) {
        1 -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.tags.title }
        2 -> compareBy<StoredTrack, String>(String.CASE_INSENSITIVE_ORDER) { it.tags.artist }.thenBy { it.path }
        3 -> compareBy<StoredTrack, String>(String.CASE_INSENSITIVE_ORDER) { it.tags.album }.thenBy { it.path }
        4 -> compareBy<StoredTrack> { it.tags.year ?: 0 }.thenBy { it.path }
        5 -> compareBy<StoredTrack> { library.moodOf(it.path)?.ordinal ?: 99 }.thenBy { it.path }
        6 -> compareBy<StoredTrack> { library.familyOf(it.path) ?: 99 }.thenBy { it.path }
        7 -> compareBy { it.features?.tempoBpm ?: 0f }
        8 -> compareBy { it.lufs ?: -99f }
        9 -> compareBy { it.durationMs }
        else -> return tracks
    }
    return tracks.sortedWith(if (sort < 0) by.reversed() else by)
}

@Composable
private fun TrackHeader(sort: Int, onSort: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        val style = MaterialTheme.typography.labelMedium

        @Composable
        fun head(text: String, column: Int, modifier: Modifier) {
            val mark = when (sort) {
                column -> " ▲"
                -column -> " ▼"
                else -> ""
            }
            Text(text + mark, modifier.clickable { onSort(column) }, style = style, maxLines = 1)
        }
        head(tr("Title", "Título"), 1, Modifier.weight(3f))
        head(tr("Artist", "Artista"), 2, Modifier.weight(2f))
        head(tr("Album", "Álbum"), 3, Modifier.weight(2f))
        head(tr("Year", "Año"), 4, Modifier.width(48.dp))
        head(tr("Mood", "Ánimo"), 5, Modifier.width(100.dp))
        head(tr("Family", "Familia"), 6, Modifier.width(56.dp))
        head("BPM", 7, Modifier.width(48.dp))
        head("LUFS", 8, Modifier.width(52.dp))
        head(tr("Time", "Duración"), 9, Modifier.width(64.dp))
    }
    HorizontalDivider()
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TrackRow(library: Library, t: StoredTrack, selected: Boolean, onDoubleClick: () -> Unit, onClick: () -> Unit) {
    val background = if (selected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent
    Row(
        Modifier.fillMaxWidth().background(background)
            .combinedClickable(onClick = onClick, onDoubleClick = onDoubleClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val style = MaterialTheme.typography.bodyMedium
        val dim = MaterialTheme.colorScheme.onSurfaceVariant
        Text(t.tags.title.ifBlank { File(t.path).nameWithoutExtension }, Modifier.weight(3f), style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(t.tags.artist, Modifier.weight(2f), style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(t.tags.album, Modifier.weight(2f), style = style, maxLines = 1, overflow = TextOverflow.Ellipsis, color = dim)
        Text(t.tags.year?.toString().orEmpty(), Modifier.width(48.dp), style = style, color = dim)
        Text(library.moodOf(t.path)?.let(::moodName) ?: if (t.error != null) "⚠" else "", Modifier.width(100.dp), style = style)
        Text(library.familyOf(t.path)?.let { "${it + 1}" }.orEmpty(), Modifier.width(56.dp), style = style)
        Text(t.features?.tempoBpm?.let { "%.0f".format(it) }.orEmpty(), Modifier.width(48.dp), style = style, color = dim)
        Text(t.lufs?.let { num(it.toDouble()) }.orEmpty(), Modifier.width(52.dp), style = style, color = dim)
        Text(if (t.durationMs > 0) duration(t.durationMs) else "", Modifier.width(64.dp), style = style, color = dim)
    }
}
