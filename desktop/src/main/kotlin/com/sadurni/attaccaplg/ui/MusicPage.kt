package com.sadurni.attaccaplg.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sadurni.attaccaplg.generate.Library
import com.sadurni.attaccaplg.library.StoredTrack
import java.io.File

/** How the music is browsed. */
enum class MusicView { ALBUMS, ARTISTS, GENRES, DECADES, MOODS, FAVORITES }

/** One album: the tracks of a folder with the same album tag, in disc and track order. */
class AlbumEntry(val key: String, val title: String, val artist: String, val year: Int?, val tracks: List<StoredTrack>)

private fun albumsOf(library: Library): List<AlbumEntry> =
    library.all.groupBy { File(it.path).parent.orEmpty() + "|" + it.tags.album }
        .map { (key, tracks) ->
            val sorted = tracks.sortedWith(compareBy<StoredTrack>({ it.tags.disc ?: 1 }, { it.tags.track ?: 9999 }, { it.path }))
            val first = sorted.first()
            val artist = first.tags.albumArtist.ifBlank { sorted.map { it.tags.artist }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key.orEmpty() }
            AlbumEntry(key, first.tags.album.ifBlank { File(first.path).parentFile?.name.orEmpty() }, artist, sorted.mapNotNull { it.tags.year }.minOrNull(), sorted)
        }
        .sortedWith(compareBy<AlbumEntry, String>(String.CASE_INSENSITIVE_ORDER) { it.artist }.thenBy { it.year ?: 0 }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.title })

/** The music by album (with covers), artist, genre, decade, mood and favourites. */
@Composable
fun MusicPage(state: AppState) {
    PageFrame(tr("Music", "Música")) {
        val library = state.library
        if (library == null) {
            Text(if (state.loading) tr("Loading…", "Cargando…") else tr("The library is empty: scan a music folder first.", "La biblioteca está vacía: escanea antes una carpeta de música."))
            return@PageFrame
        }
        setMusicRootForCovers(library.root)
        val albums = remember(library) { albumsOf(library) }
        var view by remember { mutableStateOf(MusicView.ALBUMS) }
        var album by remember { mutableStateOf<AlbumEntry?>(null) }
        var group by remember { mutableStateOf<String?>(null) }
        var query by remember { mutableStateOf("") }
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (v in MusicView.entries) {
                    FilterChip(view == v, {
                        view = v
                        album = null
                        group = null
                    }, label = {
                        Text(when (v) {
                            MusicView.ALBUMS -> tr("Albums", "Álbumes")
                            MusicView.ARTISTS -> tr("Artists", "Artistas")
                            MusicView.GENRES -> tr("Genres", "Géneros")
                            MusicView.DECADES -> tr("Decades", "Décadas")
                            MusicView.MOODS -> tr("Moods", "Estados de ánimo")
                            MusicView.FAVORITES -> tr("Favourites", "Favoritos")
                        })
                    })
                }
                Spacer(Modifier.width(12.dp))
                OutlinedTextField(query, { query = it }, Modifier.width(300.dp), singleLine = true, label = { Text(tr("Search", "Buscar")) })
            }
            Spacer(Modifier.height(10.dp))
            val words = query.lowercase().split(' ').filter { it.isNotBlank() }
            fun matches(vararg texts: String) = words.isEmpty() || words.all { w -> texts.any { it.lowercase().contains(w) } }
            val open = album
            when {
                open != null -> AlbumDetail(state, open) { album = null }
                group != null -> {
                    val tracks = groupTracks(state, library, view, group!!).filter { matches(it.tags.title, it.tags.artist, it.tags.album) }
                    TrackListView(state, group!!, tracks) { group = null }
                }
                view == MusicView.ALBUMS -> AlbumGrid(state, albums.filter { matches(it.title, it.artist) }) { album = it }
                view == MusicView.FAVORITES -> {
                    val favs = state.player.favorites.mapNotNull { path -> library.root?.let { r -> runCatching { File(path).relativeTo(r).invariantSeparatorsPath }.getOrNull() }?.let(library.byPath::get) }
                    TrackListView(state, tr("Favourites", "Favoritos"), favs.filter { matches(it.tags.title, it.tags.artist, it.tags.album) }, null)
                }
                view == MusicView.ARTISTS && words.isEmpty() -> GroupList(groupsOf(library, view)) { group = it }
                view == MusicView.ARTISTS -> AlbumGrid(state, albums.filter { matches(it.artist) }) { album = it }
                else -> GroupList(groupsOf(library, view).filter { matches(it.first) }) { group = it }
            }
        }
    }
}

private fun keyOf(library: Library, view: MusicView, t: StoredTrack): String? = when (view) {
    MusicView.ARTISTS -> t.tags.albumArtist.ifBlank { t.tags.artist }.ifBlank { null }
    MusicView.GENRES -> t.tags.genre.ifBlank { null }
    MusicView.DECADES -> t.tags.year?.let { "${it / 10 * 10}s" }
    MusicView.MOODS -> library.moodOf(t.path)?.let(::moodName)
    else -> null
}

private fun groupsOf(library: Library, view: MusicView): List<Pair<String, Int>> =
    library.all.mapNotNull { keyOf(library, view, it) }.groupingBy { it }.eachCount().toList()
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.first })

private fun groupTracks(state: AppState, library: Library, view: MusicView, key: String): List<StoredTrack> =
    library.all.filter { keyOf(library, view, it) == key }.sortedBy { it.path }

@Composable
private fun GroupList(groups: List<Pair<String, Int>>, onOpen: (String) -> Unit) {
    val listState = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().padding(end = 12.dp), state = listState) {
            items(groups, key = { it.first }) { (name, count) ->
                Row(Modifier.fillMaxWidth().clickable { onOpen(name) }.padding(horizontal = 8.dp, vertical = 8.dp)) {
                    Text(name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(tr("$count tracks", "$count pistas"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
        }
        PageScrollbar(listState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

@Composable
private fun AlbumGrid(state: AppState, albums: List<AlbumEntry>, onOpen: (AlbumEntry) -> Unit) {
    val gridState = rememberLazyGridState()
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(GridCells.Adaptive(168.dp), Modifier.fillMaxSize().padding(end = 12.dp), gridState,
            horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items(albums, key = { it.key }) { a ->
                TracksMenuArea(state, a.tracks) {
                Column(Modifier.clickable { onOpen(a) }) {
                    CoverBox(coverOf(a.tracks.firstOrNull()?.let { t -> albumFile(t) }), 160)
                    Text(a.title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(160.dp))
                    Text(listOfNotNull(a.artist.ifBlank { null }, a.year?.toString()).joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(160.dp))
                }
                }
            }
        }
        PageScrollbar(gridState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

/** The full path of a library track (for covers). */
private var musicRootForCovers: File? = null
private fun albumFile(t: StoredTrack): String? = musicRootForCovers?.let { File(it, t.path).path }

@Composable
private fun AlbumDetail(state: AppState, album: AlbumEntry, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            CoverBox(coverOf(album.tracks.firstOrNull()?.let(::albumFile), Covers.LARGE), 180)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onBack) { Text("← " + tr("Back", "Volver")) }
                Text(album.title, style = MaterialTheme.typography.headlineSmall)
                Text(listOfNotNull(album.artist.ifBlank { null }, album.year?.toString()).joinToString(" · "), style = MaterialTheme.typography.titleMedium)
                Text(tr("${album.tracks.size} tracks", "${album.tracks.size} pistas") + "  ·  " + duration(album.tracks.sumOf { it.durationMs }),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                PlayButtons(state, album.tracks)
            }
        }
        Spacer(Modifier.height(12.dp))
        TrackRows(state, album.tracks, numbered = true)
    }
}

@Composable
private fun TrackListView(state: AppState, title: String, tracks: List<StoredTrack>, onBack: (() -> Unit)?) {
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            onBack?.let { TextButton(onClick = it) { Text("← " + tr("Back", "Volver")) } }
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(tr("${tracks.size} tracks", "${tracks.size} pistas"), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (tracks.isEmpty()) {
            Hint(tr("Nothing here yet.", "Aún no hay nada aquí."))
            return
        }
        PlayButtons(state, tracks)
        Spacer(Modifier.height(8.dp))
        TrackRows(state, tracks, numbered = false)
    }
}

@Composable
private fun PlayButtons(state: AppState, tracks: List<StoredTrack>) {
    val paths = tracks.map { it.path }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { state.player.playPaths(paths) }) { Text(tr("Play", "Reproducir")) }
        OutlinedButton(onClick = { state.player.playPaths(paths.shuffled()) }) { Text(tr("Shuffle", "Aleatorio")) }
        OutlinedButton(onClick = { state.player.enqueue(paths, next = true) }) { Text(tr("Play next", "Reproducir después")) }
        OutlinedButton(onClick = { state.player.enqueue(paths, next = false) }) { Text(tr("Add to the queue", "Añadir a la cola")) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackRows(state: AppState, tracks: List<StoredTrack>, numbered: Boolean) {
    val listState = rememberLazyListState()
    val playing = state.player.current?.path
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().padding(end = 12.dp), state = listState) {
            itemsIndexed(tracks, key = { _, t -> t.path }) { i, t ->
                val now = albumFile(t) == playing
                TrackMenuArea(state, t) {
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (now) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                        .combinedClickable(onClick = {}, onDoubleClick = { state.player.playPaths(tracks.map { it.path }, i) })
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (numbered) (t.tags.track?.toString() ?: "") else "${i + 1}", Modifier.width(36.dp), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(t.tags.title.ifBlank { File(t.path).nameWithoutExtension }, Modifier.weight(3f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontWeight = if (now) FontWeight.SemiBold else FontWeight.Normal)
                    Text(t.tags.artist, Modifier.weight(2f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!numbered) Text(t.tags.album, Modifier.weight(2f), maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (t.durationMs > 0) duration(t.durationMs) else "", Modifier.width(56.dp), style = MaterialTheme.typography.bodySmall)
                }
                }
            }
        }
        PageScrollbar(listState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
    Hint(tr("Double-click a track to play from it; right-click for more.", "Doble clic en una pista para reproducir desde ella; botón derecho para más."))
}

/** Keeps the music folder for the covers of library tracks. */
fun setMusicRootForCovers(root: File?) {
    musicRootForCovers = root
}
