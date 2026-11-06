package com.sadurni.attacca.ui.chain

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sadurni.attacca.R
import com.sadurni.attacca.app
import com.sadurni.attacca.chain.ListGenerator
import com.sadurni.attacca.chain.Mood
import com.sadurni.attacca.library.MoodCorrections
import com.sadurni.attacca.library.MoodCorrections.Scope
import com.sadurni.attacca.library.TrackSort
import com.sadurni.attacca.ui.settings.RadioRow
import com.sadurni.attacca.ui.theme.AttaccaType
import com.sadurni.attacca.ui.theme.LocalAttaccaColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A track's mood as shown: one mood, or "Calm (or Melancholic)" for a track between two. */
fun moodText(context: Context, g: ListGenerator, index: Int): String = moodText(context, g.moodOf(index), g.secondMoodOf(index))

fun moodText(context: Context, mood: com.sadurni.attacca.chain.Mood, second: com.sadurni.attacca.chain.Mood?): String {
    val main = context.getString(moodRes(mood))
    second ?: return main
    return context.getString(R.string.mood_between, main, context.getString(moodRes(second)))
}

/**
 * The mood of [path] by hand: for the track, its whole album or its artist, or back to the
 * measured one (Automatic). A track's own mood also reaches the tracks that sound most like it.
 */
@Composable
fun MoodDialog(path: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val tags = remember(path) { TrackSort.tags(path) }
    val measured by produceState<String?>(null, path) {
        value = withContext(Dispatchers.IO) {
            val g = runCatching { app.chainLists.collection() }.getOrNull()?.generator
            val index = g?.indexOf(path) ?: -1
            if (g == null || index < 0) "" else moodText(context, g, index)
        }
    }
    val current = remember(path) { MoodCorrections.moodFor(path, tags.artist, tags.album) }
    var scope by remember { mutableStateOf(current?.second ?: Scope.TRACK) }
    fun keyOf(s: Scope) = when (s) {
        Scope.TRACK -> path
        Scope.ALBUM -> MoodCorrections.albumKey(tags.artist, tags.album)
        Scope.ARTIST -> tags.artist
    }
    var mood by remember { mutableStateOf(current?.first) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mood_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val shown = measured
                Text(
                    when {
                        shown == null -> stringResource(R.string.mood_measuring)
                        shown.isEmpty() -> stringResource(R.string.mood_not_measured)
                        else -> stringResource(R.string.mood_now, shown)
                    },
                    fontSize = AttaccaType.secondary,
                    color = LocalAttaccaColors.current.textGrayed,
                )
                Text(stringResource(R.string.mood_for), fontSize = AttaccaType.body, modifier = Modifier.padding(top = 8.dp))
                RadioRow(stringResource(R.string.mood_for_track), scope == Scope.TRACK) {
                    scope = Scope.TRACK
                    mood = MoodCorrections.get(scope, keyOf(scope))
                }
                if (tags.album.isNotBlank()) {
                    RadioRow(stringResource(R.string.mood_for_album, tags.album), scope == Scope.ALBUM) {
                        scope = Scope.ALBUM
                        mood = MoodCorrections.get(scope, keyOf(scope))
                    }
                }
                if (tags.artist.isNotBlank()) {
                    RadioRow(stringResource(R.string.mood_for_artist, tags.artist), scope == Scope.ARTIST) {
                        scope = Scope.ARTIST
                        mood = MoodCorrections.get(scope, keyOf(scope))
                    }
                }
                Text(stringResource(R.string.mood_is), fontSize = AttaccaType.body, modifier = Modifier.padding(top = 8.dp))
                RadioRow(stringResource(R.string.mood_automatic), mood == null) { mood = null }
                for (m in Mood.entries) RadioRow(moodLabel(m), mood == m) { mood = m }
                Text(
                    stringResource(R.string.mood_hint),
                    fontSize = AttaccaType.secondary,
                    color = LocalAttaccaColors.current.textGrayed,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                MoodCorrections.set(scope, keyOf(scope), mood)
                app.chainLists.warm()
                onDismiss()
            }) { Text(stringResource(R.string.strip_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
