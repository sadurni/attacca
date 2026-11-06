package com.sadurni.attacca.ui.chain

import com.sadurni.attacca.ui.theme.AttaccaType
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sadurni.attacca.R
import com.sadurni.attacca.app
import com.sadurni.attacca.library.InclusionMode
import com.sadurni.attacca.library.InclusionRules
import com.sadurni.attacca.library.InclusionRules.Kind
import com.sadurni.attacca.library.TrackSort
import com.sadurni.attacca.ui.settings.Hint
import com.sadurni.attacca.ui.settings.RadioRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Adds to or takes out of the inclusion list (allow or block list) a track [path], or what it
 * belongs to: its album, its artist or its folder with the subfolders. For a folder, the folder.
 */
@Composable
fun InclusionDialog(path: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val chain by app.settings.chain.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val isFolder = remember(path) { File(path).isDirectory }
    var artist by remember { mutableStateOf<String?>(null) }
    var album by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(path) {
        if (isFolder) return@LaunchedEffect
        val tags = withContext(Dispatchers.IO) { runCatching { TrackSort.tags(path) }.getOrNull() }
        artist = tags?.let { it.albumArtist.ifBlank { it.artist } }?.takeIf { it.isNotBlank() }
        album = tags?.album?.takeIf { it.isNotBlank() }
    }
    val choices = buildList {
        if (isFolder) {
            add(Kind.FOLDER to path)
        } else {
            add(Kind.TRACK to path)
            album?.let { a -> artist?.let { add(Kind.ALBUM to it + InclusionRules.ALBUM_SEPARATOR + a) } }
            artist?.let { add(Kind.ARTIST to it) }
            File(path).parent?.let { add(Kind.FOLDER to it) }
        }
    }
    var chosen by remember { mutableStateOf(0) }
    var working by remember { mutableStateOf(false) }
    val allow = chain.inclusionMode == InclusionMode.ALLOW

    fun apply(add: Boolean) {
        val (kind, value) = choices.getOrNull(chosen) ?: return
        working = true
        scope.launch {
            val count = runCatching { app.inclusionStore.change(InclusionRules.Rule(true, kind, value), add) }.getOrNull()
            working = false
            Toast.makeText(
                context,
                if (count == null) context.getString(R.string.inclusion_failed) else context.resources.getQuantityString(R.plurals.inclusion_count, count, count),
                Toast.LENGTH_SHORT,
            ).show()
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (allow) R.string.inclusion_dialog_allow else R.string.inclusion_dialog_block)) },
        text = {
            Column {
                for ((i, choice) in choices.withIndex()) {
                    val (kind, value) = choice
                    val label = when (kind) {
                        Kind.TRACK -> stringResource(R.string.inclusion_this_track, File(value).nameWithoutExtension)
                        Kind.ALBUM -> stringResource(R.string.inclusion_album, value.substringAfter(InclusionRules.ALBUM_SEPARATOR))
                        Kind.ARTIST -> stringResource(R.string.inclusion_artist, value)
                        Kind.FOLDER -> stringResource(R.string.inclusion_folder, File(value).name)
                    }
                    RadioRow(label, chosen == i) { chosen = i }
                }
                if (chain.inclusionList == null) Hint(stringResource(R.string.inclusion_will_create))
                Text(stringResource(R.string.inclusion_dialog_hint), fontSize = AttaccaType.caption)
            }
        },
        confirmButton = {
            TextButton(onClick = { apply(true) }, enabled = !working) { Text(stringResource(R.string.inclusion_add)) }
        },
        dismissButton = {
            TextButton(onClick = { apply(false) }, enabled = !working && chain.inclusionList != null) {
                Text(stringResource(R.string.inclusion_remove))
            }
        },
    )
}
