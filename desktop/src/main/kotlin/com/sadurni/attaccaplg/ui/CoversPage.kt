package com.sadurni.attaccaplg.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sadurni.attaccaplg.library.CoverSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Where an album's cover comes from. */
private enum class CoverSource { OWN, FOUND, NONE }

/** The folder an album's tracks are in, on this computer. */
private fun folderOf(album: AlbumEntry): File? = album.tracks.firstOrNull()?.let(::albumFile)?.let { File(it).parentFile }

/**
 * Every album's cover at a glance: those without one can be looked for online all at once, any
 * one can be looked for again. What is found is kept by Attacca Desktop; it can also be written
 * into the album's folder as cover.jpg (the phone and other players then see it too).
 */
@Composable
fun CoversPage(state: AppState) {
    PageFrame(tr("Covers", "Portadas")) {
        val library = state.library
        if (library == null) {
            Text(if (state.loading) tr("Loading…", "Cargando…") else tr("The library is empty: scan a music folder first.", "La biblioteca está vacía: escanea antes una carpeta de música."))
            return@PageFrame
        }
        setMusicRootForCovers(library.root)
        if (library.root == null) {
            Hint(tr("The library was imported without its music: covers need the music folder.", "La biblioteca se importó sin su música: las portadas necesitan la carpeta de música."))
            return@PageFrame
        }
        val albums = remember(library) { albumsOf(library) }
        val sources = remember(library) { mutableStateMapOf<String, CoverSource>() }
        LaunchedEffect(library, Covers.version) {
            withContext(Dispatchers.IO) {
                for (a in albums) {
                    val folder = folderOf(a) ?: continue
                    val track = a.tracks.firstOrNull()?.let(::albumFile)?.let(::File) ?: continue
                    sources[a.key] = when {
                        Covers.hasOwn(folder, track) -> CoverSource.OWN
                        CoverSearch.cached(folder) != null -> CoverSource.FOUND
                        else -> CoverSource.NONE
                    }
                }
            }
        }
        var onlyMissing by remember { mutableStateOf(false) }
        var open by remember { mutableStateOf<AlbumEntry?>(null) }
        val scope = rememberCoroutineScope()
        var job by remember { mutableStateOf<Job?>(null) }
        var done by remember { mutableStateOf(0) }
        var todo by remember { mutableStateOf(0) }
        var foundNow by remember { mutableStateOf<List<Pair<File, ByteArray>>>(emptyList()) }
        var askSave by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }

        val missing = albums.count { sources[it.key] == CoverSource.NONE }
        val found = albums.count { sources[it.key] == CoverSource.FOUND }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                tr("${albums.size} albums · $missing without a cover · $found found online", "${albums.size} álbumes · $missing sin portada · $found encontradas en internet"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (job?.isActive == true) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(tr("Looking: $done / $todo", "Buscando: $done / $todo"))
                TextButton(onClick = { job?.cancel() }) { Text(tr("Stop", "Parar")) }
            } else {
                Button(enabled = missing > 0, onClick = {
                    val queue = albums.filter { sources[it.key] == CoverSource.NONE }
                    todo = queue.size
                    done = 0
                    error = null
                    job = scope.launch {
                        val got = ArrayList<Pair<File, ByteArray>>()
                        for (a in queue) {
                            if (!isActive) break
                            val folder = folderOf(a)
                            if (folder != null) {
                                try {
                                    val bytes = withContext(Dispatchers.IO) {
                                        // The first of the matching releases the archive has a front cover of.
                                        CoverSearch.search(a.artist, a.title, limit = 5).firstNotNullOfOrNull { runCatching { CoverSearch.download(it.imageUrl) }.getOrNull() }
                                    }
                                    if (bytes != null) {
                                        withContext(Dispatchers.IO) { CoverSearch.keep(folder, bytes) }
                                        got += folder to bytes
                                        Covers.changed(folder)
                                        sources[a.key] = CoverSource.FOUND
                                    }
                                } catch (e: Exception) {
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    error = tr("The search could not reach the internet.", "La búsqueda no ha podido llegar a internet.")
                                    break
                                }
                            }
                            done++
                        }
                        foundNow = got
                        if (got.isNotEmpty()) askSave = true
                    }
                }) { Text(tr("Find the missing covers", "Buscar las portadas que faltan")) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { onlyMissing = !onlyMissing }) {
                Checkbox(onlyMissing, { onlyMissing = it })
                Text(tr("Only those without a cover", "Solo las que no tienen portada"))
            }
        }
        error?.let { Hint(it) }
        Hint(tr(
            "Covers are looked for in an open music database by album and artist. What is found is kept by Attacca Desktop; you decide whether it is also saved as cover.jpg in the album's folder. Click an album to choose another cover.",
            "Las portadas se buscan en una base de datos musical abierta por álbum y artista. Lo que se encuentra lo guarda Attacca Desktop; tú decides si además se guarda como cover.jpg en la carpeta del álbum. Haz clic en un álbum para elegir otra portada.",
        ))
        Spacer(Modifier.height(8.dp))
        val shown = if (onlyMissing) albums.filter { sources[it.key] != CoverSource.OWN && sources[it.key] != CoverSource.FOUND } else albums
        val gridState = rememberLazyGridState()
        Box(Modifier.fillMaxSize()) {
            LazyVerticalGrid(GridCells.Adaptive(150.dp), Modifier.fillMaxSize().padding(end = 12.dp), gridState,
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(shown, key = { it.key }) { a ->
                    Column(Modifier.clickable { open = a }) {
                        Box {
                            CoverBox(coverOf(a.tracks.firstOrNull()?.let(::albumFile)), 142)
                            val badge = when (sources[a.key]) {
                                CoverSource.FOUND -> tr("Found online", "De internet")
                                CoverSource.NONE -> tr("No cover", "Sin portada")
                                else -> null
                            }
                            badge?.let {
                                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                        }
                        Text(a.title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(142.dp))
                        Text(a.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(142.dp))
                    }
                }
            }
            PageScrollbar(gridState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
        open?.let { a -> CoverDialog(a) { open = null } }
        if (askSave) {
            AlertDialog(
                onDismissRequest = { askSave = false },
                title = { Text(tr("Save the covers in the folders?", "¿Guardar las portadas en las carpetas?")) },
                text = { Text(tr(
                    "${foundNow.size} covers were found. Attacca Desktop keeps them already; saved as cover.jpg in each album's folder, the phone and other players see them too.",
                    "Se han encontrado ${foundNow.size} portadas. Attacca Desktop ya las guarda; guardadas como cover.jpg en la carpeta de cada álbum, también las ven el móvil y otros reproductores.",
                )) },
                confirmButton = {
                    Button(onClick = {
                        askSave = false
                        val list = foundNow
                        scope.launch(Dispatchers.IO) {
                            for ((folder, bytes) in list) if (CoverSearch.saveToFolder(folder, bytes) != null) Covers.changed(folder)
                        }
                    }) { Text(tr("Save in the folders", "Guardar en las carpetas")) }
                },
                dismissButton = { TextButton(onClick = { askSave = false }) { Text(tr("Only in Attacca Desktop", "Solo en Attacca Desktop")) } },
            )
        }
    }
}

/**
 * Looks for [album]'s cover online: the releases that match, to pick one; the one picked is kept
 * and, if wanted, saved as cover.jpg in the album's folder (replacing the picture there).
 */
@Composable
fun CoverDialog(album: AlbumEntry, onDismiss: () -> Unit) {
    val folder = remember(album) { folderOf(album) }
    var artist by remember { mutableStateOf(album.artist) }
    var title by remember { mutableStateOf(album.title) }
    var searching by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<CoverSearch.Candidate>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf<ByteArray?>(null) }
    val thumbs = remember { mutableStateMapOf<String, ImageBitmap?>() }
    val scope = rememberCoroutineScope()
    fun search() {
        searching = true
        error = null
        scope.launch {
            results = try {
                withContext(Dispatchers.IO) { CoverSearch.search(artist, title) }
            } catch (e: Exception) {
                error = tr("The search could not reach the internet.", "La búsqueda no ha podido llegar a internet.")
                null
            }
            searching = false
            results?.forEach { c ->
                launch(Dispatchers.IO) {
                    thumbs[c.thumbUrl] = runCatching { CoverSearch.download(c.thumbUrl)?.let { org.jetbrains.skia.Image.makeFromEncoded(it).toComposeImageBitmap() } }.getOrNull()
                }
            }
        }
    }
    LaunchedEffect(album) { search() }
    val hasPicture = remember(folder) { folder?.listFiles()?.any { it.isFile && it.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp") } == true }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Cover of ${album.title}", "Portada de ${album.title}")) },
        text = {
            Column(Modifier.width(620.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(title, { title = it }, label = { Text(tr("Album", "Álbum")) }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(artist, { artist = it }, label = { Text(tr("Artist", "Artista")) }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = ::search, enabled = !searching) { Text(tr("Search", "Buscar")) }
                }
                when {
                    searching -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(tr("Searching…", "Buscando…"))
                    }
                    error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                    results?.isEmpty() == true -> Text(tr("Nothing found: try other words for the album or the artist.", "No se ha encontrado nada: prueba con otras palabras para el álbum o el artista."))
                }
                LazyVerticalGrid(GridCells.Adaptive(140.dp), Modifier.heightIn(max = 420.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Releases whose cover could not be fetched (the archive has none) are left out.
                    items(results.orEmpty().filter { !(thumbs.containsKey(it.thumbUrl) && thumbs[it.thumbUrl] == null) }, key = { it.imageUrl }) { c ->
                        Column(Modifier.clickable {
                            scope.launch {
                                val bytes = withContext(Dispatchers.IO) { runCatching { CoverSearch.download(c.imageUrl) }.getOrNull() }
                                if (bytes == null || folder == null) {
                                    error = tr("That cover could not be downloaded.", "No se ha podido descargar esa portada.")
                                } else {
                                    withContext(Dispatchers.IO) { CoverSearch.keep(folder, bytes) }
                                    Covers.changed(folder)
                                    picked = bytes
                                }
                            }
                        }) {
                            val image = thumbs[c.thumbUrl]
                            Box(Modifier.size(132.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant), contentAlignment = Alignment.Center) {
                                if (image != null) Image(image, null, Modifier.fillMaxSize()) else CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            }
                            Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(132.dp))
                            Text(listOfNotNull(c.artist.ifBlank { null }, c.year, c.country).joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(132.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Close", "Cerrar")) } },
    )
    picked?.let { bytes ->
        AlertDialog(
            onDismissRequest = { picked = null; onDismiss() },
            title = { Text(tr("Save it in the album's folder?", "¿Guardarla en la carpeta del álbum?")) },
            text = { Text(
                if (hasPicture) tr(
                    "Attacca Desktop keeps it already. Saved as cover.jpg in the album's folder (it takes the place of the picture there), the phone and other players see it too.",
                    "Attacca Desktop ya la guarda. Guardada como cover.jpg en la carpeta del álbum (en lugar de la imagen que hay), también la ven el móvil y otros reproductores.",
                ) else tr(
                    "Attacca Desktop keeps it already. Saved as cover.jpg in the album's folder, the phone and other players see it too.",
                    "Attacca Desktop ya la guarda. Guardada como cover.jpg en la carpeta del álbum, también la ven el móvil y otros reproductores.",
                ),
            ) },
            confirmButton = {
                Button(onClick = {
                    folder?.let { f ->
                        CoverSearch.saveToFolder(f, bytes)
                        Covers.changed(f)
                    }
                    picked = null
                    onDismiss()
                }) { Text(tr("Save as cover.jpg", "Guardar como cover.jpg")) }
            },
            dismissButton = { TextButton(onClick = { picked = null; onDismiss() }) { Text(tr("Only in Attacca Desktop", "Solo en Attacca Desktop")) } },
        )
    }
}
