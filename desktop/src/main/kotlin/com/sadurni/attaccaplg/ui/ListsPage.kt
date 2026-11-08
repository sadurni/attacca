package com.sadurni.attaccaplg.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
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
import com.sadurni.attacca.chain.VoiceFilter
import com.sadurni.attaccaplg.export.PlaylistFormat
import com.sadurni.attaccaplg.generate.Library
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ListsPage(state: AppState) {
    PageFrame(tr("Lists", "Listas")) {
        val library = state.library
        if (library == null) {
            Text(if (state.loading) tr("Loading…", "Cargando…") else tr("The library is empty: scan a music folder first.", "La biblioteca está vacía: escanea antes una carpeta de música."))
            return@PageFrame
        }
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(state.listsView == ListsView.MAKE, { state.listsView = ListsView.MAKE }, label = { Text(tr("Make lists", "Hacer listas")) })
            FilterChip(state.listsView == ListsView.SAVED, { state.listsView = ListsView.SAVED; state.player.lists.refresh() }, label = {
                Text(tr("Kept lists", "Listas guardadas") + " (${state.player.lists.entries.size})")
            })
        }
        if (state.listsView == ListsView.SAVED) {
            SavedListsView(state)
            return@Column
        }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            val scroll = rememberScrollState()
            Box(Modifier.weight(1.1f).fillMaxHeight()) {
                Column(Modifier.fillMaxWidth().padding(end = 12.dp).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    KindSection(state, library)
                    if (state.lists.kind != ListKind.REORDER) FiltersSection(state, library)
                    OutputSection(state)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(enabled = state.busy == null, onClick = {
                            state.run(tr("Making the lists", "Haciendo las listas")) {
                                withContext(Dispatchers.Default) { state.lists.make(library, state.inclusionList, state.inclusionMode) }
                                // Kept among the made lists, as Attacca keeps its generated ones.
                                library.root?.let { root -> withContext(Dispatchers.IO) { state.player.lists.saveMade(state.lists.made, root) } }
                            }
                        }) { Text(tr("Make", "Hacer")) }
                        Hint(tr(
                            "Shown on the right and kept in Kept lists; Save also writes them in the folder above, for other players.",
                            "Se ven a la derecha y se guardan en Listas guardadas; Guardar las escribe además en la carpeta de arriba, para otros reproductores.",
                        ))
                    }
                    Spacer(Modifier.padding(4.dp))
                }
                PageScrollbar(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
            Box(Modifier.weight(1f).fillMaxHeight()) { ResultsPanel(state, library) }
        }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun KindSection(state: AppState, library: Library) {
    val form = state.lists
    Section(tr("What list", "Qué lista")) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (kind in ListKind.entries) {
                FilterChip(selected = form.kind == kind, onClick = { form.kind = kind }, label = {
                    Text(
                        when (kind) {
                            ListKind.FILTERS -> tr("By filters", "Por filtros")
                            ListKind.TEMPLATE -> tr("Template", "Plantilla")
                            ListKind.AUTO -> tr("Automatic", "Automáticas")
                            ListKind.SEED -> tr("From a track", "Desde una pista")
                            ListKind.MORE -> tr("Other lists", "Otras listas")
                            ListKind.REORDER -> tr("Reorder", "Reordenar")
                        },
                        maxLines = 1,
                    )
                })
            }
        }
        when (form.kind) {
            ListKind.FILTERS -> {
                Hint(tr("One list of the tracks passing the filters, each end chained into a similar start.", "Una lista con las pistas que pasan los filtros, cada final encadenado con un principio parecido."))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(form.name, { form.name = it }, Modifier.weight(1f), singleLine = true, label = { Text(tr("Name (optional)", "Nombre (opcional)")) })
                    NumberField(tr("Tracks (empty: all)", "Pistas (vacío: todas)"), form.count) { form.count = it }
                }
                TrackPicker(library, tr("Start with (else at random)", "Empezar por (si no, al azar)"), form.start) { form.start = it }
            }
            ListKind.TEMPLATE -> {
                Hint(tr("One list per value of the template, among the tracks passing the filters (groups of 2 or more, up to 200 lists).", "Una lista por cada valor de la plantilla, entre las pistas que pasan los filtros (grupos de 2 o más, hasta 200 listas)."))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(form.template, { form.template = it }, Modifier.weight(1f), singleLine = true, label = { Text(tr("Template", "Plantilla")) })
                    NumberField(tr("Tracks (empty: all)", "Pistas (vacío: todas)"), form.count) { form.count = it }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (field in Library.TEMPLATE_FIELDS) {
                        AssistChip(onClick = { form.template += (if (form.template.isEmpty() || form.template.endsWith("_")) "" else "_") + "{$field}" }, label = { Text("{$field}") })
                    }
                }
            }
            ListKind.AUTO -> {
                Hint(tr("One list per mood and sound family, as Attacca's generated lists.", "Una lista por estado de ánimo y familia de sonido, como las listas generadas de Attacca."))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NumberField(tr("Tracks (empty: all)", "Pistas (vacío: todas)"), form.count) { form.count = it }
                    NumberField(tr("Fewest tracks", "Mínimo de pistas"), form.minTracks) { form.minTracks = it }
                }
            }
            ListKind.SEED -> {
                Hint(tr("The tracks closest to the one chosen, chained from it.", "Las pistas más parecidas a la elegida, encadenadas desde ella."))
                TrackPicker(library, tr("Track", "Pista"), form.seed) { form.seed = it; form.fitted = null }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(tr("Tracks", "Pistas"), form.seedCount) { form.seedCount = it }
                    Checkbox(form.sameMood, { form.sameMood = it })
                    Text(tr("Same mood", "Mismo ánimo"))
                    Checkbox(form.sameFamily, { form.sameFamily = it })
                    Text(tr("Same sound family", "Misma familia"))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(enabled = form.seed != null, onClick = {
                        form.fitToSeed(library, state.inclusionList, state.inclusionMode)
                    }) { Text(tr("Fit the filters to the track", "Ajustar los filtros a la pista")) }
                    val available = form.fitted
                    val asked = form.seedCount.toIntOrNull() ?: com.sadurni.attaccaplg.DEFAULT_COUNT
                    Hint(
                        when {
                            available == null -> tr(
                                "Years, BPM, energy, positivity and voice around the track's, leaving 10 times the tracks asked for (at least twice).",
                                "Años, BPM, energía, positividad y voz alrededor de los de la pista, dejando 10 veces las pistas pedidas (como mínimo el doble).",
                            )
                            available >= asked * 2 -> tr("$available tracks to choose from.", "$available pistas entre las que elegir.")
                            else -> tr(
                                "Only $available tracks to choose from, even with the filters wide open: try without same mood or family.",
                                "Solo $available pistas entre las que elegir, aun con los filtros abiertos del todo: prueba sin mismo ánimo o familia.",
                            )
                        },
                        Modifier.weight(1f),
                    )
                }
            }
            ListKind.MORE -> {
                Hint(tr(
                    "Other kinds of list, as Attacca's, all chained end into start: energy curves, a sampler of the sound families, from a track (its opposite, a set time, whole albums like its album, a bridge to another) or like a playlist.",
                    "Otros tipos de lista, como los de Attacca, todos encadenados final con principio: curvas de energía, un muestrario de las familias de sonido, desde una pista (lo contrario, para un tiempo, álbumes enteros como su álbum, un puente a otra) o como una lista.",
                ))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (k in Library.MoreKind.entries) {
                        FilterChip(selected = form.moreKind == k, onClick = { form.moreKind = k }, label = { Text(tr(k.en, k.es), maxLines = 1) })
                    }
                }
                NumberField(tr("Tracks", "Pistas"), form.count) { form.count = it }
                when (form.moreKind) {
                    Library.MoreKind.LIKE -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(form.likeList?.path ?: tr("No list chosen", "Sin lista elegida"), Modifier.weight(1f))
                        OutlinedButton(onClick = {
                            Dialogs.open(tr("Playlist", "Lista"), form.likeList?.path ?: state.prefs["reorder_dir"], "*.m3u8;*.m3u;*.pls;*.cue;*.xspf")?.let {
                                form.likeList = it
                                state.prefs["reorder_dir"] = it.parent
                            }
                        }) { Text(tr("Choose…", "Elegir…")) }
                    }
                    Library.MoreKind.SAMPLER -> Unit
                    else -> {
                        val label = if (form.moreKind.needsTrack) tr("Track", "Pista") else tr("Start with (else at random)", "Empezar por (si no, al azar)")
                        TrackPicker(library, label, form.seed) { form.seed = it; form.fitted = null }
                    }
                }
                if (form.moreKind == Library.MoreKind.BRIDGE) TrackPicker(library, tr("To", "Hasta"), form.second) { form.second = it }
                if (form.moreKind == Library.MoreKind.TIME) NumberField(tr("Minutes", "Minutos"), form.minutes) { form.minutes = it }
            }
            ListKind.REORDER -> {
                Hint(tr("A playlist (M3U, M3U8, PLS, CUE or XSPF, from here, the phone or another program) in chained order.", "Una lista (M3U, M3U8, PLS, CUE o XSPF, de aquí, del móvil o de otro programa) en orden encadenado."))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(form.playlist?.path ?: tr("No list chosen", "Sin lista elegida"), Modifier.weight(1f))
                    OutlinedButton(onClick = {
                        Dialogs.open(tr("Playlist", "Lista"), form.playlist?.path ?: state.prefs["reorder_dir"], "*.m3u8;*.m3u;*.pls;*.cue;*.xspf")?.let {
                            form.playlist = it
                            state.prefs["reorder_dir"] = it.parent
                        }
                    }) { Text(tr("Choose…", "Elegir…")) }
                }
                OutlinedTextField(form.name, { form.name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(tr("Name (else the list's, _chained)", "Nombre (si no, el de la lista, _chained)")) })
                TrackPicker(library, tr("Start with (else its first track)", "Empezar por (si no, su primera pista)"), form.start) { form.start = it }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FiltersSection(state: AppState, library: Library) {
    val form = state.lists
    Section(tr("Filters", "Filtros")) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (mood in Mood.entries) {
                FilterChip(
                    selected = mood in form.moods,
                    onClick = { form.moods = if (mood in form.moods) form.moods - mood else form.moods + mood },
                    label = { Text(moodName(mood)) },
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (family in library.generator.classification.familyNames.indices) {
                FilterChip(
                    selected = family in form.families,
                    onClick = { form.families = if (family in form.families) form.families - family else form.families + family },
                    label = { Text("${family + 1}. ${familyLabel(library, family)}") },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(form.artist, { form.artist = it }, Modifier.weight(1f), singleLine = true, label = { Text(tr("Artist", "Artista")) })
            OutlinedTextField(form.genre, { form.genre = it }, Modifier.weight(1f), singleLine = true, label = { Text(tr("Genre (tag)", "Género (etiqueta)")) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NumberField(tr("Year from", "Año desde"), form.yearFrom, Modifier.weight(1f)) { form.yearFrom = it }
            NumberField(tr("to", "hasta"), form.yearTo, Modifier.weight(1f)) { form.yearTo = it }
            NumberField(tr("BPM from", "BPM desde"), form.bpmMin, Modifier.weight(1f)) { form.bpmMin = it }
            NumberField(tr("to", "hasta"), form.bpmMax, Modifier.weight(1f)) { form.bpmMax = it }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NumberField(tr("Minutes from", "Minutos desde"), form.minutesMin, Modifier.weight(1f)) { form.minutesMin = it }
            NumberField(tr("to", "hasta"), form.minutesMax, Modifier.weight(1f)) { form.minutesMax = it }
            Spacer(Modifier.weight(2f))
        }
        Percent(tr("Energy", "Energía"), form.energy) { form.energy = it }
        Percent(tr("Positivity", "Positividad"), form.positivity) { form.positivity = it }
        Hint(tr("Energy and positivity in percent of this library (0: the lowest track, 100: the highest).", "Energía y positividad en porcentaje de esta biblioteca (0: la pista más baja, 100: la más alta)."))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Choice(
                tr("Voice", "Voz"), form.voice, VoiceFilter.entries,
                { when (it) { VoiceFilter.ANY -> tr("Any", "Cualquiera"); VoiceFilter.VOICE -> tr("With voice", "Con voz"); VoiceFilter.INSTRUMENTAL -> tr("Instrumental", "Instrumental") } },
                { form.voice = it },
            )
            Spacer(Modifier.width(24.dp))
            Checkbox(form.onlyIncluded, { form.onlyIncluded = it }, enabled = state.inclusionList != null)
            Text(tr("Only included", "Solo incluidas"), color = if (state.inclusionList != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            if (form.filtersInUse()) TextButton(onClick = form::clearFilters) { Text(tr("Clear filters", "Quitar filtros")) }
        }
    }
}

@Composable
private fun Percent(label: String, value: ClosedFloatingPointRange<Float>, onChange: (ClosedFloatingPointRange<Float>) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(110.dp))
        RangeSlider(value, { r -> onChange(Math.round(r.start / 5) * 5f..Math.round(r.endInclusive / 5) * 5f) }, Modifier.weight(1f), valueRange = 0f..100f)
        Text("${value.start.toInt()}–${value.endInclusive.toInt()} %", Modifier.width(90.dp).padding(start = 12.dp))
    }
}

@Composable
private fun NumberField(label: String, value: String, modifier: Modifier = Modifier.width(140.dp), onChange: (String) -> Unit) {
    OutlinedTextField(
        value, { v -> onChange(v.filter { it.isDigit() }.take(5)) }, modifier,
        singleLine = true, label = { Text(label, maxLines = 1) },
    )
}

/** Picks one track by searching (artist, title, album, folder). */
@Composable
private fun TrackPicker(library: Library, label: String, value: String?, onPick: (String?) -> Unit) {
    val picked = value?.let(library.byPath::get)
    if (picked != null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label + ":")
            InputChip(
                selected = true, onClick = { onPick(null) },
                label = { Text("${picked.tags.artist} — ${picked.tags.title.ifBlank { File(picked.path).nameWithoutExtension }}  ✕", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
        return
    }
    var query by remember { mutableStateOf("") }
    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(label) }, placeholder = { Text(tr("Search…", "Buscar…")) })
    if (query.length >= 2) {
        val found = remember(library, query) { library.find(query).filter { it.features != null }.take(30) }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
            if (found.isEmpty()) Hint(tr("Nothing found.", "No hay nada."), Modifier.padding(12.dp))
            LazyColumn(Modifier.heightIn(max = 220.dp)) {
                items(found, key = { it.path }) { t ->
                    Column(Modifier.fillMaxWidth().clickable { onPick(t.path); query = "" }.padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Text("${t.tags.artist} — ${t.tags.title.ifBlank { File(t.path).nameWithoutExtension }}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Hint(t.path)
                    }
                }
            }
        }
    }
}

@Composable
private fun OutputSection(state: AppState) {
    val form = state.lists
    Section(tr("Saving", "Al guardar")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr("Folder", "Carpeta"), Modifier.width(150.dp))
            Text(form.outDir, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            OutlinedButton(onClick = { Dialogs.folder(tr("Folder for the lists", "Carpeta para las listas"), form.outDir)?.let { form.outDir = it.path; form.remember() } }) {
                Text(tr("Choose…", "Elegir…"))
            }
        }
        Choice(
            tr("Format", "Formato"), form.format, PlaylistFormat.entries,
            {
                when (it) {
                    PlaylistFormat.M3U8 -> tr("M3U8 (Attacca, VLC… skip the silence)", "M3U8 (Attacca, VLC… saltan el silencio)")
                    PlaylistFormat.XSPF -> tr("XSPF (AIMP, VLC… skip the silence)", "XSPF (AIMP, VLC… saltan el silencio)")
                    PlaylistFormat.ROCKBOX -> tr("M3U for Rockbox", "M3U para Rockbox")
                }
            },
            { form.format = it; form.remember() },
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("Music on the player at", "Música en el reproductor en"), Modifier.width(150.dp))
            OutlinedTextField(
                form.pathTo, { form.pathTo = it; form.remember() }, Modifier.weight(1f), singleLine = true,
                placeholder = { Text(tr("Empty: this PC's paths", "Vacío: las rutas de este PC")) },
            )
            Choice("", "", PATH_PRESETS, { it.ifEmpty { "…" } }, { form.pathTo = it; form.remember() })
        }
        Hint(tr("Where the music folder is on the device that plays the lists: the lists' paths are written from there.", "Dónde está la carpeta de música en el aparato que reproduce las listas: las rutas de las listas se escriben desde ahí."))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Same artist again after", "Mismo artista otra vez tras"), Modifier.width(150.dp))
            Choice("", form.spacing, (0..10).toList(), { tr("$it tracks", "$it pistas") }, { form.spacing = it; form.remember() })
        }
    }
}

private const val SHOWN_TRACKS = 300

private val PATH_PRESETS = listOf("", "/storage/emulated/0/Music", "/<microSD1>/Music", "/<HDD0>/Music")

@Composable
private fun ResultsPanel(state: AppState, library: Library) {
    val form = state.lists
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                when (form.made.size) {
                    0 -> tr("No lists yet", "Aún no hay listas")
                    1 -> tr("1 list", "1 lista")
                    else -> tr("${form.made.size} lists", "${form.made.size} listas")
                },
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
            )
            if (form.made.isNotEmpty()) {
                // The list open below (or the first one), straight to the player.
                OutlinedButton(onClick = {
                    val made = form.made.firstOrNull { it.playlist.name == form.expanded } ?: form.made.first()
                    state.player.playPaths(made.playlist.paths, continueCurrent = true)
                }) { Text(tr("Play", "Reproducir")) }
                Button(enabled = state.busy == null, onClick = {
                    state.run(tr("Saving", "Guardando")) {
                        val n = withContext(Dispatchers.IO) { form.save(library) }
                        state.message = tr("$n lists saved in ${form.outDir}", "$n listas guardadas en ${form.outDir}")
                    }
                }) { Text(tr("Save", "Guardar")) }
            }
            form.saved?.let { folder -> OutlinedButton(onClick = { Dialogs.show(folder) }) { Text(tr("Open folder", "Abrir carpeta")) } }
        }
        form.note?.let { Hint(it) }
        val listState = rememberLazyListState()
        Box(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize().padding(end = 12.dp), state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(form.made, key = { it.playlist.name }) { made ->
                    MadeCard(state, library, made, form.expanded == made.playlist.name) {
                        form.expanded = if (form.expanded == made.playlist.name) null else made.playlist.name
                    }
                }
            }
            PageScrollbar(listState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

@Composable
private fun MadeCard(state: AppState, library: Library, made: MadeList, expanded: Boolean, onClick: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(made.playlist.name, Modifier.weight(1f), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Hint(
                    tr("${made.playlist.paths.size} tracks", "${made.playlist.paths.size} pistas") + "  ·  " + duration(made.durationMs) +
                        "  ·  " + tr("links ", "enlaces ") + num(made.links, 2),
                )
            }
            if (expanded) {
                Spacer(Modifier.padding(4.dp))
                made.playlist.paths.take(SHOWN_TRACKS).forEachIndexed { i, p ->
                    val t = library.byPath[p] ?: return@forEachIndexed
                    TrackMenuArea(state, t) {
                    Row {
                        Text("${i + 1}.", Modifier.width(36.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "${t.tags.artist} — ${t.tags.title.ifBlank { File(p).nameWithoutExtension }}",
                            Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(library.moodOf(p)?.let(::moodName).orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            if (t.durationMs > 0) duration(t.durationMs) else "",
                            Modifier.width(56.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.End,
                        )
                    }
                    }
                }
                if (made.playlist.paths.size > SHOWN_TRACKS) Hint(tr("… and ${made.playlist.paths.size - SHOWN_TRACKS} more", "… y ${made.playlist.paths.size - SHOWN_TRACKS} más"))
            }
        }
    }
}
