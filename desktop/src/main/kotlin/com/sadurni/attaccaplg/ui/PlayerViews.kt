package com.sadurni.attaccaplg.ui

import java.io.File
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** The transport at the foot of the window: the playing track, its position and the controls. */
@Composable
fun PlayerBar(state: AppState) {
    val p = state.player
    val item = p.current ?: return
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.combinedClickable(onClick = { state.page = Page.PLAYING })) { CoverBox(coverOf(item.path), 44) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.width(230.dp).combinedClickable(onClick = { state.page = Page.PLAYING })) {
                Text(item.title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOf(item.artist, item.album).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { p.toggleFavorite(item.path) }) {
                val fav = p.isFavorite(item.path)
                Icon(if (fav) PlayerIcons.Favorite else PlayerIcons.FavoriteBorder, tr("Favourite", "Favorito"),
                    tint = if (fav) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = p::changeShuffle) {
                Icon(PlayerIcons.Shuffle, tr("Shuffle", "Aleatorio"), tint = if (p.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = p::previous) { Icon(PlayerIcons.SkipPrevious, tr("Previous", "Anterior")) }
            FilledIconButton(onClick = p::toggle) {
                Icon(if (p.playing) PlayerIcons.Pause else PlayerIcons.PlayArrow, if (p.playing) tr("Pause", "Pausa") else tr("Play", "Reproducir"))
            }
            IconButton(onClick = p::next) { Icon(PlayerIcons.SkipNext, tr("Next", "Siguiente")) }
            IconButton(onClick = p::changeRepeat) {
                Icon(
                    if (p.repeat == Repeat.ONE) PlayerIcons.RepeatOne else PlayerIcons.Repeat,
                    tr("Repeat", "Repetir"),
                    tint = if (p.repeat != Repeat.OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Seek(p, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Icon(PlayerIcons.VolumeUp, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(p.settings.volume, { v -> p.update { it.copy(volume = v) } }, Modifier.width(110.dp))
            var sleepMenu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { sleepMenu = true }) {
                    Icon(PlayerIcons.Bedtime, sleepText(p.sleep), tint = if (p.sleep != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SleepMenu(p, sleepMenu) { sleepMenu = false }
            }
            IconButton(onClick = { state.mini = !state.mini }) { Icon(PlayerIcons.PictureInPicture, tr("Mini player", "Minirreproductor")) }
            IconButton(onClick = { state.page = Page.PLAYING }) { Icon(PlayerIcons.QueueMusic, tr("Queue", "Cola")) }
        }
    }
}

/** The position: a slider that seeks when let go, with the times beside it. */
@Composable
private fun Seek(p: PlayerState, modifier: Modifier) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val length = p.durationMs.coerceAtLeast(1)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        val shown = dragging?.let { (it * length).toLong() } ?: p.positionMs
        Text(duration(shown), style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(44.dp))
        Slider(
            value = dragging ?: (p.positionMs.toFloat() / length).coerceIn(0f, 1f),
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { p.seek((it * length).toLong()) }
                dragging = null
            },
            modifier = Modifier.weight(1f),
        )
        Text(duration(p.durationMs), style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(44.dp).padding(start = 6.dp))
    }
}

/** What is playing and what comes next: the queue, to jump to a track or take one out. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlayingPage(state: AppState) {
    val p = state.player
    PageFrame(tr("Playing", "Reproduciendo")) {
        if (p.queue.isEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(tr(
                    "Nothing to play yet: play from Music, Tracks or Lists, or open files, a playlist or a folder of this PC.",
                    "Aún no hay nada que reproducir: reproduce desde Música, Pistas o Listas, o abre archivos, una lista o una carpeta de este PC.",
                ))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    // The list played last, from where it was left.
                    p.lastList?.let { list ->
                        val at = p.lists.positionOf(list)
                        Button(onClick = { p.playList(list) }) {
                            Text(tr("Go on with the list ", "Continuar la lista ") + "«" + list.nameWithoutExtension + "»" +
                                (at?.let { " (" + File(it.path).nameWithoutExtension + ")" } ?: ""))
                        }
                    }
                    OpenButtons(state)
                }
            }
            return@PageFrame
        }
        Column(Modifier.fillMaxSize()) {
            NowPlayingHeader(state)
            Spacer(Modifier.padding(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(tr("${p.queue.size} tracks", "${p.queue.size} pistas") + "  ·  " + duration(p.queue.sumOf { it.durationMs }))
                OutlinedButton(onClick = p::clear) { Text(tr("Clear the queue", "Vaciar la cola")) }
                OutlinedButton(onClick = { askQueueName(state) }) { Text(tr("Save as a list…", "Guardar como lista…")) }
                OpenButtons(state)
            }
            Spacer(Modifier.padding(6.dp))
            val listState = rememberLazyListState()
            LaunchedEffect(p.index) { if (p.index >= 0) listState.animateScrollToItem((p.index - 3).coerceAtLeast(0)) }
            Box(Modifier.fillMaxSize()) {
                LazyColumn(Modifier.fillMaxSize().padding(end = 12.dp), state = listState) {
                    itemsIndexed(p.queue) { i, item ->
                        val now = i == p.index
                        QueueMenuArea(state, i) {
                        Row(
                            Modifier.fillMaxWidth()
                                .background(if (now) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                                .combinedClickable(onClick = {}, onDoubleClick = { p.jumpTo(i) })
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("${i + 1}", Modifier.width(44.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(item.title, Modifier.weight(3f), fontWeight = if (now) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(item.artist, Modifier.weight(2f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(item.album, Modifier.weight(2f), maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(if (item.durationMs > 0) duration(item.durationMs) else "", Modifier.width(56.dp), style = MaterialTheme.typography.bodySmall)
                            IconButton(onClick = { p.remove(i) }, Modifier.size(28.dp)) { Icon(PlayerIcons.Close, tr("Take out", "Quitar"), Modifier.size(16.dp)) }
                        }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
                }
                PageScrollbar(listState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
            Hint(
                tr("Double-click a track to play it; right-click for more.", "Doble clic en una pista para reproducirla; botón derecho para más.") +
                    (p.source?.let { "  ·  " + tr("Playing the list ", "Sonando la lista ") + it.nameWithoutExtension + tr(" (its place is kept)", " (se guarda por dónde va)") } ?: ""),
            )
        }
    }
}
