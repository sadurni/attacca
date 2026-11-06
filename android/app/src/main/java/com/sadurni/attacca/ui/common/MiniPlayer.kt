package com.sadurni.attacca.ui.common

import com.sadurni.attacca.ui.theme.AttaccaType
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sadurni.attacca.R
import com.sadurni.attacca.ui.PlayerViewModel
import com.sadurni.attacca.ui.theme.LocalAccent
import com.sadurni.attacca.ui.theme.LocalAttaccaColors

/**
 * The playing track at the bottom of every screen but the player: cover, title, artist and
 * album, previous / play-pause / next and a thin progress line. Tapping it opens the player.
 */
@Composable
fun MiniPlayer(viewModel: PlayerViewModel, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val now by viewModel.nowPlaying.collectAsStateWithLifecycle()
    val position by viewModel.positionMs.collectAsStateWithLifecycle()
    val mediaId = now.mediaId ?: return
    val colors = LocalAttaccaColors.current
    val accent = LocalAccent.current
    Column(modifier.fillMaxWidth().background(colors.surface).windowInsetsPadding(WindowInsets.navigationBars)) {
        val progress = if (now.durationMs > 0) (position.toFloat() / now.durationMs).coerceIn(0f, 1f) else 0f
        Box(Modifier.fillMaxWidth().height(2.dp).background(accent.copy(alpha = 0.2f))) {
            Box(Modifier.fillMaxWidth(progress).height(2.dp).background(accent))
        }
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TrackThumbnail(mediaId, size = 46.dp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(now.title, fontSize = AttaccaType.body, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val details = listOfNotNull(now.artist, now.album).filter { it.isNotBlank() }.joinToString(" · ")
                if (details.isNotEmpty()) {
                    Text(details, fontSize = AttaccaType.secondary, color = colors.textGrayed, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            IconButton(onClick = viewModel::previous) {
                Icon(Icons.Rounded.SkipPrevious, contentDescription = stringResource(R.string.action_previous))
            }
            IconButton(onClick = viewModel::playPause) {
                Icon(
                    if (now.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = stringResource(if (now.isPlaying) R.string.action_pause else R.string.action_play),
                    tint = accent,
                )
            }
            IconButton(onClick = viewModel::next) {
                Icon(Icons.Rounded.SkipNext, contentDescription = stringResource(R.string.action_next))
            }
        }
    }
}
