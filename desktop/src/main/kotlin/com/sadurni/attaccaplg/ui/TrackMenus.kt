package com.sadurni.attaccaplg.ui

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sadurni.attaccaplg.generate.Library
import com.sadurni.attaccaplg.library.StoredTrack
import java.io.File
import java.util.Locale

/** A name asked for (a list's), then [onName] with it. */
class NameRequest(val title: String, val initial: String, val onName: (String) -> Unit)

// ---------- Right-click menus ----------

/** The right-click menu of a library track: playing it, lists from it, its information. */
@Composable
fun TrackMenuArea(state: AppState, track: StoredTrack, extra: List<ContextMenuItem> = emptyList(), content: @Composable () -> Unit) {
    ContextMenuArea(items = { trackItems(state, track) + extra }, content = content)
}

/** The right-click menu of an album or any group of library tracks. */
@Composable
fun TracksMenuArea(state: AppState, tracks: List<StoredTrack>, content: @Composable () -> Unit) {
    ContextMenuArea(items = { groupItems(state, tracks) }, content = content)
}

/** The right-click menu of a queue item (in the library or not). */
@Composable
fun QueueMenuArea(state: AppState, i: Int, content: @Composable () -> Unit) {
    val p = state.player
    ContextMenuArea(items = {
        val item = p.queue.getOrNull(i) ?: return@ContextMenuArea emptyList()
        val own = listOf(
            ContextMenuItem(tr("Play it", "Reproducirla")) { p.jumpTo(i) },
            ContextMenuItem(tr("Take it out of the queue", "Quitarla de la cola")) { p.remove(i) },
            ContextMenuItem(tr("Save the queue as a list…", "Guardar la cola como lista…")) { askQueueName(state) },
        )
        val t = p.trackOf(item)
        if (t != null) {
            own + trackItems(state, t, playing = false)
        } else {
            own + listOf(
                ContextMenuItem(tr("Add to a list…", "Añadir a una lista…")) { state.addToList = listOf(item.path) },
                ContextMenuItem(tr("Open its folder", "Abrir su carpeta")) { File(item.path).parentFile?.let(Dialogs::show) },
            )
        }
    }, content = content)
}

fun askQueueName(state: AppState) {
    state.askName = NameRequest(tr("Save the queue as a list", "Guardar la cola como lista"), state.player.current?.album.orEmpty()) { name ->
        state.player.saveQueueAs(name)?.let { state.message = tr("Saved in your lists: ${it.nameWithoutExtension}", "Guardada en tus listas: ${it.nameWithoutExtension}") }
    }
}

private fun full(state: AppState, t: StoredTrack): String? = state.library?.root?.let { File(it, t.path).path }

private fun trackItems(state: AppState, t: StoredTrack, playing: Boolean = true): List<ContextMenuItem> {
    val p = state.player
    val items = ArrayList<ContextMenuItem>()
    if (playing) {
        items += ContextMenuItem(tr("Play", "Reproducir")) { p.playPaths(listOf(t.path)) }
        items += ContextMenuItem(tr("Play next", "Reproducir después")) { p.enqueue(listOf(t.path), next = true) }
        items += ContextMenuItem(tr("Add to the queue", "Añadir a la cola")) { p.enqueue(listOf(t.path), next = false) }
    }
    items += ContextMenuItem(tr("Add to a list…", "Añadir a una lista…")) { state.addToList = listOfNotNull(full(state, t)) }
    full(state, t)?.let { path ->
        items += ContextMenuItem(if (p.isFavorite(path)) tr("Take out of Favourites", "Quitar de Favoritos") else tr("Add to Favourites", "Añadir a Favoritos")) {
            p.toggleFavorite(path)
        }
    }
    if (t.features != null) {
        items += ContextMenuItem(tr("List from this track", "Lista desde esta pista")) {
            state.lists.kind = ListKind.SEED
            state.lists.seed = t.path
            state.listsView = ListsView.MAKE
            state.page = Page.LISTS
        }
        items += ContextMenuItem(tr("Bridge from this track…", "Camino desde esta pista…")) { bridge(state, from = t.path) }
        items += ContextMenuItem(tr("Bridge to this track…", "Camino hasta esta pista…")) { bridge(state, to = t.path) }
    }
    if (t.path.endsWith(".mp3", ignoreCase = true)) {
        items += ContextMenuItem(tr("Repair", "Reparar")) { state.askRepair(listOf(t.path)) }
    }
    items += ContextMenuItem(tr("Information", "Información")) { state.info = t }
    items += ContextMenuItem(tr("Open its folder", "Abrir su carpeta")) { full(state, t)?.let { File(it).parentFile?.let(Dialogs::show) } }
    return items
}

private fun bridge(state: AppState, from: String? = null, to: String? = null) {
    val form = state.lists
    form.kind = ListKind.MORE
    form.moreKind = Library.MoreKind.BRIDGE
    from?.let { form.seed = it }
    to?.let { form.second = it }
    state.listsView = ListsView.MAKE
    state.page = Page.LISTS
    state.message = tr("Choose the other end of the bridge, then Make.", "Elige el otro extremo del camino y pulsa Hacer.")
}

private fun groupItems(state: AppState, tracks: List<StoredTrack>): List<ContextMenuItem> {
    val p = state.player
    val paths = tracks.map { it.path }
    return listOf(
        ContextMenuItem(tr("Play", "Reproducir")) { p.playPaths(paths) },
        ContextMenuItem(tr("Shuffle", "Aleatorio")) { p.playPaths(paths.shuffled()) },
        ContextMenuItem(tr("Play next", "Reproducir después")) { p.enqueue(paths, next = true) },
        ContextMenuItem(tr("Add to the queue", "Añadir a la cola")) { p.enqueue(paths, next = false) },
        ContextMenuItem(tr("Add to a list…", "Añadir a una lista…")) { state.addToList = tracks.mapNotNull { full(state, it) } },
        ContextMenuItem(tr("Open its folder", "Abrir su carpeta")) { tracks.firstOrNull()?.let { full(state, it) }?.let { File(it).parentFile?.let(Dialogs::show) } },
        ContextMenuItem(tr("Repair damaged tracks", "Reparar las pistas dañadas")) { state.askRepair(paths) },
    )
}

// ---------- Dialogs ----------

/** What becomes of the originals of the tracks to repair: kept beside as .bk, or deleted. */
@Composable
fun RepairDialog(state: AppState, paths: List<String>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Repair", "Reparar")) },
        text = {
            Text(
                if (paths.size == 1) tr(
                    "${File(paths[0]).name} is written again without its broken and repeated pieces. What should happen to the original file?",
                    "${File(paths[0]).name} se escribe de nuevo sin sus trozos rotos y repetidos. ¿Qué hacemos con el archivo original?",
                ) else tr(
                    "${paths.size} damaged tracks are written again without their broken and repeated pieces. What should happen to the original files?",
                    "Se escriben de nuevo ${paths.size} pistas dañadas sin sus trozos rotos y repetidos. ¿Qué hacemos con los archivos originales?",
                ),
            )
        },
        confirmButton = {
            Row {
                TextButton(onClick = { onDismiss(); state.repairMany(paths, true) }) { Text(tr("Keep it beside, ending in .bk", "Guardarlo al lado, acabado en .bk")) }
                TextButton(onClick = { onDismiss(); state.repairMany(paths, false) }) { Text(tr("Delete it", "Borrarlo")) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel", "Cancelar")) } },
    )
}

/** The dialogs the menus open (a name, a list to add to, a track's information). */
@Composable
fun MenuDialogs(state: AppState) {
    state.askName?.let { request -> NameDialog(request) { state.askName = null } }
    state.repairAsk?.let { paths -> RepairDialog(state, paths) { state.repairAsk = null } }
    state.repairing?.let { (done, total) ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(tr("Repair", "Reparar")) },
            text = { Text(tr("Repairing: $done / $total", "Reparando: $done / $total")) },
            confirmButton = {},
        )
    }
    state.addToList?.let { paths -> AddToListDialog(state, paths) { state.addToList = null } }
    state.info?.let { t -> state.library?.let { TrackInfoDialog(it, t) { state.info = null } } }
}

@Composable
private fun NameDialog(request: NameRequest, onClose: () -> Unit) {
    var name by remember { mutableStateOf(request.initial) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(request.title) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(tr("Name", "Nombre")) }) },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                request.onName(name.trim())
                onClose()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(tr("Cancel", "Cancelar")) } },
    )
}

@Composable
private fun AddToListDialog(state: AppState, paths: List<String>, onClose: () -> Unit) {
    val lists = state.player.lists
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (paths.size == 1) tr("Add to a list", "Añadir a una lista") else tr("Add ${paths.size} tracks to a list", "Añadir ${paths.size} pistas a una lista")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                    for (entry in lists.entries.filter { !it.generated }) {
                        TextButton(onClick = {
                            lists.addTo(entry.file, paths)
                            state.message = tr("Added to ${entry.name}", "Añadido a ${entry.name}")
                            onClose()
                        }) { Text(entry.name + "  (" + tr("${entry.paths.size} tracks", "${entry.paths.size} pistas") + ")") }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, Modifier.width(240.dp), singleLine = true, label = { Text(tr("A new list", "Una lista nueva")) })
                    OutlinedButton(enabled = name.isNotBlank(), onClick = {
                        val file = lists.saveAs(name, paths)
                        state.message = tr("Added to ${file.nameWithoutExtension}", "Añadido a ${file.nameWithoutExtension}")
                        onClose()
                    }) { Text(tr("Make it", "Crearla")) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(tr("Cancel", "Cancelar")) } },
    )
}

/** Everything known of a track: its tags, its file, and what Attacca measured. */
@Composable
private fun TrackInfoDialog(library: Library, t: StoredTrack, onClose: () -> Unit) {
    val file = library.root?.let { File(it, t.path) }
    val i = library.indexOf(t.path)
    val k = library.generator.classification
    fun pct(v: Float) = "${(v * 100).toInt()} %"
    val rows = listOfNotNull(
        tr("Title", "Título") to t.tags.title,
        tr("Artist", "Artista") to t.tags.artist,
        tr("Album", "Álbum") to t.tags.album,
        (tr("Album artist", "Artista del álbum") to t.tags.albumArtist).takeIf { t.tags.albumArtist.isNotBlank() },
        tr("Genre", "Género") to t.tags.genre,
        tr("Year", "Año") to (t.tags.year?.toString() ?: ""),
        tr("Track", "Pista") to listOfNotNull(t.tags.track?.toString(), t.tags.disc?.let { tr("disc $it", "disco $it") }).joinToString(" · "),
        tr("Length", "Duración") to (if (t.durationMs > 0) duration(t.durationMs) else ""),
        tr("Sound from … to", "Sonido de … a") to (if (t.audioEndMs > t.audioStartMs) duration(t.audioStartMs) + " … " + duration(t.audioEndMs) else ""),
        tr("File", "Archivo") to (file?.path ?: t.path),
        tr("Format", "Formato") to File(t.path).extension.uppercase() + "  ·  " + "%.1f MB".format(Locale.ROOT, t.size / 1_048_576.0),
        tr("Mood", "Estado de ánimo") to (library.moodOf(t.path)?.let { m ->
            moodName(m) + (i?.let { library.generator.secondMoodOf(it) }?.let { " " + tr("(or ${moodName(it)})", "(o ${moodName(it)})") } ?: "")
        } ?: ""),
        tr("Family", "Familia") to (library.familyOf(t.path)?.let { library.familyName(it) } ?: ""),
        "BPM" to (t.features?.tempoBpm?.let { "%.0f".format(Locale.ROOT, it) } ?: ""),
        tr("Energy", "Energía") to (i?.let { pct(k.energy[it]) } ?: ""),
        tr("Positivity", "Positividad") to (i?.let { pct(k.positivity[it]) } ?: ""),
        tr("Loudness", "Volumen") to listOfNotNull(
            t.lufs?.let { "%.1f LUFS".format(Locale.ROOT, it) },
            t.peak.takeIf { it > 0f }?.let { "%.1f dBFS".format(Locale.ROOT, 20 * kotlin.math.log10(it)) + " " + tr("peak", "pico") },
        ).joinToString("  ·  "),
        (tr("Not measured", "Sin medir") to t.error.orEmpty()).takeIf { t.error != null },
    ).filter { it.second.isNotBlank() }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(t.tags.title.ifBlank { File(t.path).nameWithoutExtension }) },
        text = {
            SelectionContainer {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for ((label, value) in rows) {
                        Row(Modifier.fillMaxWidth()) {
                            Text(label, Modifier.width(150.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(value, Modifier.weight(1f))
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                file?.parentFile?.let { folder -> TextButton(onClick = { Dialogs.show(folder) }) { Text(tr("Open its folder", "Abrir su carpeta")) } }
                TextButton(onClick = onClose) { Text("OK") }
            }
        },
    )
}
