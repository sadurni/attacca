package com.sadurni.attacca.ui.lists

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.sadurni.attacca.Background
import com.sadurni.attacca.R
import com.sadurni.attacca.app
import com.sadurni.attacca.library.AudioFiles
import com.sadurni.attacca.library.ListeningList
import com.sadurni.attacca.library.M3uParser
import com.sadurni.attacca.library.PlaylistEntry
import com.sadurni.attacca.library.UserLists
import com.sadurni.attacca.ui.PlayerViewModel
import com.sadurni.attacca.ui.settings.Hint
import com.sadurni.attacca.ui.theme.AttaccaType
import kotlinx.coroutines.withContext
import java.io.File

/** Name of a list by listening, as shown and as saved. */
@Composable
fun ListeningList.label(): String = stringResource(
    when (this) {
        ListeningList.MOST_PLAYED -> R.string.listening_most_played
        ListeningList.RECENTLY_PLAYED -> R.string.listening_recent
        ListeningList.FORGOTTEN -> R.string.listening_forgotten
        ListeningList.NEVER_PLAYED -> R.string.listening_never
        ListeningList.MOST_SKIPPED -> R.string.listening_skipped
    },
)

@Composable
fun ListeningList.summary(): String = stringResource(
    when (this) {
        ListeningList.MOST_PLAYED -> R.string.listening_most_played_summary
        ListeningList.RECENTLY_PLAYED -> R.string.listening_recent_summary
        ListeningList.FORGOTTEN -> R.string.listening_forgotten_summary
        ListeningList.NEVER_PLAYED -> R.string.listening_never_summary
        ListeningList.MOST_SKIPPED -> R.string.listening_skipped_summary
    },
)

/** Builds a list by listening with the number of tracks asked for, then plays or saves it. */
@Composable
fun ListeningListDialog(kind: ListeningList, viewModel: PlayerViewModel, onDismiss: () -> Unit, onPlayed: () -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val name = kind.label()
    var count by remember { mutableStateOf("150") }
    var loading by remember { mutableStateOf(true) }
    var ids by remember { mutableStateOf<List<String>>(emptyList()) }
    val wanted = (count.toIntOrNull() ?: 150).coerceIn(1, 10_000)

    LaunchedEffect(kind, wanted) {
        loading = true
        ids = withContext(Background.dispatcher) {
            val folder = app.settings.musicFolder?.let(::File)
            kind.tracks(
                app.listening.snapshot(),
                library = { folder?.let { f -> AudioFiles.collectAudio(f, recent = true).map { it.absolutePath } }.orEmpty() },
                count = wanted,
            )
        }
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(name) },
        text = {
            Column {
                Hint(kind.summary())
                OutlinedTextField(
                    value = count,
                    onValueChange = { count = it.filter(Char::isDigit) },
                    label = { Text(stringResource(R.string.filter_count), fontSize = AttaccaType.caption) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
                if (loading) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                        CircularProgressIndicator(Modifier.width(24.dp))
                    }
                } else {
                    Text(
                        if (ids.isEmpty()) stringResource(R.string.listening_empty) else stringResource(R.string.listening_ready, ids.size),
                        fontSize = AttaccaType.body,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !loading && ids.isNotEmpty(), onClick = {
                viewModel.playIds(name, ids)
                onDismiss()
                onPlayed()
            }) { Text(stringResource(R.string.seed_play)) }
        },
        dismissButton = {
            TextButton(enabled = !loading && ids.isNotEmpty(), onClick = {
                val file = UserLists.fileFor(name)
                runCatching {
                    file.parentFile?.mkdirs()
                    M3uParser.write(file, ids.map { PlaylistEntry(it) })
                }
                Toast.makeText(context, context.getString(R.string.list_saved).format(file.nameWithoutExtension), Toast.LENGTH_SHORT).show()
                onDismiss()
            }) { Text(stringResource(R.string.seed_save)) }
        },
    )
}
