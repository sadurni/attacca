package com.sadurni.attaccaplg.ui

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import java.io.File

/**
 * The lists kept, as in Attacca: the user's own (the queue saved, tracks added from the menus)
 * and the ones made in Lists; each goes on where it was left.
 */
@Composable
fun SavedListsView(state: AppState) {
    val lists = state.player.lists
    var deleting by remember { mutableStateOf<SavedLists.Entry?>(null) }
    if (lists.entries.none { !it.favorites }) {
        lists.entries.firstOrNull { it.favorites }?.let { SavedRow(state, it) {} }
        Hint(tr(
            "No lists kept yet: the lists made here are kept on their own; save the queue (Playing) or add tracks to a list with the right-click menu.",
            "Aún no hay listas guardadas: las que se hacen aquí se guardan solas; guarda la cola (Reproduciendo) o añade pistas a una lista con el botón derecho.",
        ))
        return
    }
    val listState = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().padding(end = 12.dp), state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val mine = lists.entries.filter { !it.generated && !it.favorites }
            val made = lists.entries.filter { it.generated }
            lists.entries.firstOrNull { it.favorites }?.let { f -> item(key = "favorites") { SavedRow(state, f) {} } }
            if (mine.isNotEmpty()) item { Text(tr("Your lists", "Tus listas"), style = MaterialTheme.typography.titleMedium) }
            items(mine, key = { it.file.path }) { e -> SavedRow(state, e) { deleting = e } }
            if (made.isNotEmpty()) item { Text(tr("Made", "Generadas"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
            items(made, key = { it.file.path }) { e -> SavedRow(state, e) { deleting = e } }
        }
        PageScrollbar(listState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
    deleting?.let { e ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(tr("Delete the list ${e.name}?", "¿Borrar la lista ${e.name}?")) },
            text = { Text(tr("Its file is deleted; the music stays.", "Se borra su archivo; la música se queda.")) },
            confirmButton = { TextButton(onClick = { lists.delete(e); deleting = null }) { Text(tr("Delete", "Borrar")) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(tr("Cancel", "Cancelar")) } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SavedRow(state: AppState, e: SavedLists.Entry, onDelete: () -> Unit) {
    val p = state.player
    val lists = p.lists
    fun rename() {
        state.askName = NameRequest(tr("Rename the list", "Renombrar la lista"), e.name) { name ->
            if (!lists.rename(e, name)) state.message = tr("There is a list with that name already.", "Ya hay una lista con ese nombre.")
        }
    }
    val empty = e.paths.isEmpty()
    val menu = if (e.favorites) listOfNotNull(
        ContextMenuItem(tr("Play from where it was left", "Reproducir desde donde se dejó")) { p.playList(e.file) }.takeIf { !empty },
        ContextMenuItem(tr("Play from the start", "Reproducir desde el principio")) { p.playList(e.file, fromStart = true) }.takeIf { !empty },
        ContextMenuItem(tr("Add the phone's favourites…", "Añadir los favoritos del teléfono…")) { addPhoneFavorites(state) },
        ContextMenuItem(tr("Open its folder", "Abrir su carpeta")) { Dialogs.show(e.file.parentFile) },
    ) else listOf(
        ContextMenuItem(tr("Play from where it was left", "Reproducir desde donde se dejó")) { p.playList(e.file) },
        ContextMenuItem(tr("Play from the start", "Reproducir desde el principio")) { p.playList(e.file, fromStart = true) },
        ContextMenuItem(tr("Rename…", "Renombrar…")) { rename() },
        ContextMenuItem(tr("Delete…", "Borrar…")) { onDelete() },
        ContextMenuItem(tr("Open its folder", "Abrir su carpeta")) { Dialogs.show(e.file.parentFile) },
    )
    ContextMenuArea(items = { menu }) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Row(
                Modifier.fillMaxWidth().combinedClickable(onClick = {}, onDoubleClick = { p.playList(e.file) })
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(e.name, fontWeight = if (p.source == e.file) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Hint(
                        tr("${e.paths.size} tracks", "${e.paths.size} pistas") +
                            (e.position?.let { at ->
                                "  ·  " + tr("left at ", "se dejó en ") + File(at.path).nameWithoutExtension + " (" + duration(at.ms) + ")"
                            } ?: ""),
                    )
                }
                if (e.favorites) Icon(PlayerIcons.Favorite, null, tint = MaterialTheme.colorScheme.primary)
                OutlinedButton(onClick = { p.playList(e.file) }, enabled = !empty) { Text(if (e.position != null) tr("Go on", "Continuar") else tr("Play", "Reproducir")) }
                if (e.position != null) OutlinedButton(onClick = { p.playList(e.file, fromStart = true) }, enabled = !empty) { Text(tr("From the start", "Desde el principio")) }
                if (e.favorites) {
                    TextButton(onClick = { addPhoneFavorites(state) }) { Text(tr("Add the phone's…", "Añadir los del teléfono…")) }
                } else {
                    if (!e.generated) TextButton(onClick = { rename() }) { Text(tr("Rename", "Renombrar")) }
                    TextButton(onClick = onDelete) { Text(tr("Delete", "Borrar")) }
                }
            }
        }
    }
}

/**
 * The phone's Favorites.m3u8 (copied from Music/Attacca on the phone) added to the favourites here:
 * each of its tracks found in the library by its path (the folders below the music folder).
 */
private fun addPhoneFavorites(state: AppState) {
    val file = pickFile(state, tr("The phone's Favorites.m3u8", "El Favorites.m3u8 del teléfono"), "*.m3u8;*.m3u") ?: return
    val library = state.library
    val root = library?.root
    if (library == null || root == null) {
        state.message = tr("Open a library with its music first.", "Abre primero una biblioteca con su música.")
        return
    }
    val lines = state.player.lists.read(file)
    val found = lines.mapNotNull { line -> library.matcher.match(line)?.let { File(root, it).path } }
    val added = state.player.addFavorites(found)
    state.message = tr(
        "$added added to Favourites (${found.size} of ${lines.size} found in the library).",
        "$added añadidas a Favoritos (${found.size} de ${lines.size} encontradas en la biblioteca).",
    )
}
