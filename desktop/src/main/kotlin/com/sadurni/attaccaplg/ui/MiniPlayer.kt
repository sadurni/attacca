package com.sadurni.attaccaplg.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** The small window always on top: cover, track, the transport and where it is. */
@Composable
fun MiniPlayer(state: AppState) {
    val p = state.player
    val item = p.current
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxSize().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            CoverBox(coverOf(item?.path), 84)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item?.title ?: tr("Nothing playing", "No suena nada"), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(item?.artist.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = p::previous) { Icon(PlayerIcons.SkipPrevious, tr("Previous", "Anterior")) }
                    FilledIconButton(onClick = p::toggle) {
                        Icon(if (p.playing) PlayerIcons.Pause else PlayerIcons.PlayArrow, if (p.playing) tr("Pause", "Pausa") else tr("Play", "Reproducir"))
                    }
                    IconButton(onClick = p::next) { Icon(PlayerIcons.SkipNext, tr("Next", "Siguiente")) }
                    Text("  " + duration(p.positionMs) + " / " + duration(p.durationMs), style = MaterialTheme.typography.labelSmall)
                }
                LinearProgressIndicator(
                    progress = { (p.positionMs.toFloat() / p.durationMs.coerceAtLeast(1)).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
