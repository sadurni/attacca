package com.sadurni.attaccaplg.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File

/** The playing track: its cover, what it is, what comes next, and its waveform to move around in. */
@Composable
fun NowPlayingHeader(state: AppState) {
    val p = state.player
    val item = p.current ?: return
    val track = p.trackOf(item)
    val library = state.library
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        CoverBox(coverOf(item.path, Covers.LARGE), 220)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(item.artist, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.album, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            val facts = buildList {
                track?.tags?.year?.let { add(it.toString()) }
                track?.tags?.genre?.takeIf { it.isNotBlank() }?.let(::add)
                if (track != null && library != null) {
                    library.moodOf(track.path)?.let { add(moodName(it)) }
                    library.familyOf(track.path)?.let { add(tr("family ", "familia ") + (it + 1) + " (" + library.familyName(it) + ")") }
                }
                track?.features?.tempoBpm?.let { add("%.0f BPM".format(it)) }
                track?.lufs?.let { add("%.1f LUFS".format(it)) }
            }
            if (facts.isNotEmpty()) Text(facts.joinToString("  ·  "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            p.queue.getOrNull(p.index + 1)?.let { next ->
                Text(tr("Next: ", "Siguiente: ") + listOf(next.title, next.artist).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(10.dp))
            // The waveform, or a strip of O'rama with the waveform small under it (right click to change).
            val strip = state.prefs["playing_strip"] == "orama"
            androidx.compose.foundation.ContextMenuArea(items = {
                listOf(
                    androidx.compose.foundation.ContextMenuItem(
                        if (strip) tr("Show the waveform here", "Mostrar aquí la forma de onda") else tr("Show O'rama here", "Mostrar aquí O'rama"),
                    ) { state.prefs["playing_strip"] = if (strip) "wave" else "orama"; state.playingStrip++ },
                )
            }) {
                Column {
                    state.playingStrip
                    // The small round button in the corner: the landscape for O'rama, the bars for the waveform.
                    val switch: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {
                        androidx.compose.foundation.layout.Box(
                            Modifier.align(Alignment.TopEnd).padding(4.dp).size(28.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(Color.Black.copy(alpha = 0.35f))
                                .clickable { state.prefs["playing_strip"] = if (strip) "wave" else "orama"; state.playingStrip++ },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                if (strip) PlayerIcons.GraphicEq else PlayerIcons.Landscape,
                                if (strip) tr("Show the waveform here", "Mostrar aquí la forma de onda") else tr("Show O'rama here", "Mostrar aquí O'rama"),
                                tint = Color.White,
                                modifier = Modifier.size(17.dp),
                            )
                        }
                    }
                    if (strip) {
                        androidx.compose.foundation.layout.Box {
                            OramaStrip(state, Modifier.fillMaxWidth().aspectRatio(4.4f).clip(RoundedCornerShape(10.dp)))
                            switch()
                        }
                        Spacer(Modifier.height(6.dp))
                        Waveform(item.waveform, p.positionMs, p.durationMs, Modifier.fillMaxWidth().height(32.dp), p.cutStartMs, p.cutEndMs, p.blendMs) { p.seek(it) }
                    } else {
                        androidx.compose.foundation.layout.Box {
                            Waveform(item.waveform, p.positionMs, p.durationMs, Modifier.fillMaxWidth().height(72.dp), p.cutStartMs, p.cutEndMs, p.blendMs) { p.seek(it) }
                            switch()
                        }
                    }
                }
            }
        }
    }
}

/** A cover, or a plain disc when there is none. */
@Composable
fun CoverBox(image: ImageBitmap?, size: Int) {
    val shape = RoundedCornerShape(10.dp)
    Box(Modifier.size(size.dp).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        if (image != null) {
            Image(image, null, Modifier.size(size.dp), contentScale = ContentScale.Crop)
        } else {
            Icon(PlayerIcons.Album, null, Modifier.size((size / 2.5).dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The track's loudness along it (bars), what has played in the accent colour; a click or a drag seeks. */
@Composable
fun Waveform(
    wave: FloatArray?,
    positionMs: Long,
    durationMs: Long,
    modifier: Modifier,
    cutStartMs: Long = 0,
    cutEndMs: Long = 0,
    blendMs: Int = 0,
    onSeek: (Long) -> Unit,
) {
    val played = MaterialTheme.colorScheme.primary
    val rest = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    // What is left out (the silences before and after the sound) shows faint; the blend into the
    // next track, lighter, from a mark where it begins.
    val left = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f)
    val mark = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
    val length = durationMs.coerceAtLeast(1)
    Canvas(
        modifier
            .pointerInput(length) { detectTapGestures { onSeek((it.x / size.width * length).toLong().coerceIn(0, length)) } }
            .pointerInput(length) {
                detectDragGestures { change, _ -> onSeek((change.position.x / size.width * length).toLong().coerceIn(0, length)) }
            },
    ) {
        val bars = (size.width / 4f).toInt().coerceAtLeast(1)
        val values = wave?.takeIf { it.isNotEmpty() }
        val split = positionMs.toFloat() / length * size.width
        fun xOf(ms: Long) = ms.toFloat() / length * size.width
        val from = xOf(cutStartMs)
        val to = if (cutEndMs > cutStartMs) xOf(cutEndMs) else size.width
        val blendFrom = if (blendMs > 0 && cutEndMs > cutStartMs) xOf((cutEndMs - blendMs).coerceAtLeast(cutStartMs)) else size.width
        for (b in 0 until bars) {
            val v = values?.let { it[(b.toLong() * it.size / bars).toInt().coerceIn(0, it.lastIndex)] } ?: 0.15f
            val h = (v.coerceIn(0.04f, 1f) * size.height)
            val x = b * 4f
            val color = when {
                x + 2.6f < from || x > to -> left
                x >= blendFrom -> (if (x < split) played else rest).copy(alpha = (if (x < split) played else rest).alpha * 0.55f)
                x < split -> played
                else -> rest
            }
            drawRect(color, Offset(x, (size.height - h) / 2), Size(2.6f, h))
        }
        if (from > 1f) drawRect(mark, Offset(from - 1f, 0f), Size(1.5f, size.height))
        if (to < size.width - 1f) drawRect(mark, Offset(to, 0f), Size(1.5f, size.height))
        if (blendFrom < to - 2f) drawRect(mark.copy(alpha = 0.35f), Offset(blendFrom, size.height * 0.15f), Size(1f, size.height * 0.7f))
    }
}

/** Opening files of this PC: audio files and playlists, or a whole folder. */
@Composable
fun OpenButtons(state: AppState) {
    var start by remember { mutableStateOf(state.prefs["open_dir"] ?: state.musicRoot) }
    OutlinedButton(onClick = {
        val files = Dialogs.openMany(tr("Open", "Abrir"), start, "*.mp3;*.ogg;*.opus;*.flac;*.wav;*.m4a;*.aac;*.wma;*.m3u8;*.m3u;*.pls;*.xspf;*.cue")
        if (files.isNotEmpty()) {
            start = files.first().parent
            state.prefs["open_dir"] = start ?: ""
            state.player.playFiles(files)
        }
    }) { Text(tr("Open files or a list…", "Abrir archivos o una lista…")) }
    OutlinedButton(onClick = {
        Dialogs.folder(tr("Play a folder", "Reproducir una carpeta"), start)?.let {
            start = it.path
            state.prefs["open_dir"] = it.path
            state.player.playFiles(listOf(it))
        }
    }) { Text(tr("Play a folder…", "Reproducir una carpeta…")) }
}

/** The sleep timer's menu (in the player bar). */
@Composable
fun SleepMenu(p: PlayerState, open: Boolean, onClose: () -> Unit) {
    DropdownMenu(open, onClose) {
        for (minutes in listOf(15, 30, 45, 60, 90, 120)) {
            DropdownMenuItem(text = { Text(tr("In $minutes minutes", "Dentro de $minutes minutos")) }, onClick = {
                p.sleepAt(Sleep.At(System.currentTimeMillis() + minutes * 60_000L))
                onClose()
            })
        }
        DropdownMenuItem(text = { Text(tr("At the end of this track", "Al acabar este tema")) }, onClick = { p.sleepAt(Sleep.EndOfTrack); onClose() })
        DropdownMenuItem(text = { Text(tr("At the end of the album", "Al acabar el álbum")) }, onClick = { p.sleepAt(Sleep.EndOfAlbum); onClose() })
        if (p.sleep != null) DropdownMenuItem(text = { Text(tr("Cancel the timer", "Quitar el temporizador")) }, onClick = { p.sleepAt(null); onClose() })
    }
}

/** What the sleep timer says, for its button's hint. */
fun sleepText(sleep: Sleep?): String = when (sleep) {
    is Sleep.At -> tr("Stops at ", "Para a las ") + java.time.LocalTime.ofInstant(java.time.Instant.ofEpochMilli(sleep.epochMs), java.time.ZoneId.systemDefault()).withNano(0).withSecond(0)
    Sleep.EndOfTrack -> tr("Stops at the end of this track", "Para al acabar este tema")
    Sleep.EndOfAlbum -> tr("Stops at the end of the album", "Para al acabar el álbum")
    null -> tr("Sleep timer", "Temporizador")
}

/** A track's file name without folders, for tracks without a title. */
fun fileTitle(path: String) = File(path).nameWithoutExtension
