package com.sadurni.attacca.ui.lists

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import com.sadurni.attacca.R
import com.sadurni.attacca.app
import com.sadurni.attacca.library.ListLoader
import com.sadurni.attacca.library.ListSource
import com.sadurni.attacca.library.ListTools
import com.sadurni.attacca.library.ListTools.Tool
import com.sadurni.attacca.library.MediaItems
import com.sadurni.attacca.library.UserLists
import com.sadurni.attacca.ui.settings.Hint
import com.sadurni.attacca.ui.settings.NameDialog
import com.sadurni.attacca.ui.settings.RadioRow
import com.sadurni.attacca.ui.theme.AttaccaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Two entries are the same track when they are the same file and, for a part of one, the same part. */
private fun keyOf(item: MediaItem): String = item.mediaId + "@" + (MediaItems.explicitClipStartMs(item) ?: "")

/**
 * The list tools: taking out repeated tracks or those played lately, and combining the list with
 * another one (adding its tracks, keeping only the shared ones, taking its tracks out). The result
 * changes the list itself when it is one of the user's own, or is saved as a new list.
 * [onChanged] runs after the list itself was changed.
 */
@Composable
fun ListToolsDialog(source: ListSource, items: List<MediaItem>, editable: File?, onChanged: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tool by remember { mutableStateOf(Tool.DEDUPE) }
    var days by remember { mutableStateOf("7") }
    var other by remember { mutableStateOf<File?>(null) }
    var others by remember { mutableStateOf<List<File>>(emptyList()) }
    var result by remember { mutableStateOf<List<MediaItem>?>(null) }
    var naming by remember { mutableStateOf(false) }
    val own = (source as? ListSource.Playlist)?.path
    LaunchedEffect(Unit) {
        others = withContext(Dispatchers.IO) {
            (UserLists.all() + context.app.listHistory.playlists()).filter { it.absolutePath != own && it.exists() }.distinctBy { it.absolutePath }
        }
    }
    val repeated = remember(items) { items.size - ListTools.dedupe(items, ::keyOf).size }

    val done = result
    if (done != null && naming) {
        NameDialog(
            title = stringResource(R.string.list_tools_save_new),
            label = stringResource(R.string.list_name),
            initial = source.name,
            onConfirm = { name ->
                val file = UserLists.fileFor(name)
                if (file.exists()) {
                    Toast.makeText(context, R.string.list_tools_exists, Toast.LENGTH_SHORT).show()
                } else {
                    scope.launch {
                        withContext(Dispatchers.IO) { runCatching { UserLists.save(file, done) } }
                        Toast.makeText(context, context.getString(R.string.list_saved).format(file.nameWithoutExtension), Toast.LENGTH_SHORT).show()
                        onDismiss()
                    }
                }
            },
            onDismiss = { naming = false },
        )
        return
    }
    if (done != null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.list_tools)) },
            text = { Text(stringResource(R.string.list_tools_result, done.size, items.size)) },
            confirmButton = {
                if (editable != null) {
                    TextButton(onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { runCatching { UserLists.save(editable, done) } }
                            onChanged()
                            onDismiss()
                        }
                    }) { Text(stringResource(R.string.list_tools_change)) }
                } else {
                    TextButton(onClick = { naming = true }, enabled = done.isNotEmpty()) { Text(stringResource(R.string.list_tools_save_new)) }
                }
            },
            dismissButton = {
                if (editable != null) {
                    TextButton(onClick = { naming = true }, enabled = done.isNotEmpty()) { Text(stringResource(R.string.list_tools_save_new)) }
                } else {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                }
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.list_tools)) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                RadioRow(stringResource(R.string.list_tools_dedupe, repeated), tool == Tool.DEDUPE) { tool = Tool.DEDUPE }
                RadioRow(stringResource(R.string.list_tools_played), tool == Tool.REMOVE_PLAYED) { tool = Tool.REMOVE_PLAYED }
                if (tool == Tool.REMOVE_PLAYED) {
                    OutlinedTextField(
                        value = days,
                        onValueChange = { days = it.filter(Char::isDigit).take(4) },
                        label = { Text(stringResource(R.string.list_tools_days)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )
                }
                RadioRow(stringResource(R.string.list_tools_add), tool == Tool.ADD_OTHER) { tool = Tool.ADD_OTHER }
                RadioRow(stringResource(R.string.list_tools_shared), tool == Tool.KEEP_SHARED) { tool = Tool.KEEP_SHARED }
                RadioRow(stringResource(R.string.list_tools_remove), tool == Tool.REMOVE_OTHER) { tool = Tool.REMOVE_OTHER }
                if (ListTools.needsOther(tool)) {
                    Text(
                        stringResource(R.string.list_tools_other),
                        fontSize = AttaccaType.secondary,
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp),
                    )
                    if (others.isEmpty()) Hint(stringResource(R.string.list_tools_no_other))
                    for (file in others) {
                        RadioRow(
                            if (file == UserLists.favorites) stringResource(R.string.favorites) else file.nameWithoutExtension,
                            other == file,
                        ) { other = file }
                    }
                }
                Hint(stringResource(if (editable != null) R.string.list_tools_hint_own else R.string.list_tools_hint))
            }
        },
        confirmButton = {
            TextButton(
                enabled = !ListTools.needsOther(tool) || other != null,
                onClick = {
                    val chosen = tool
                    scope.launch {
                        result = withContext(Dispatchers.IO) {
                            val second = other?.takeIf { ListTools.needsOther(chosen) }?.let { ListLoader.load(ListSource.Playlist(it.absolutePath)) }.orEmpty()
                            when (chosen) {
                                Tool.DEDUPE -> ListTools.dedupe(items, ::keyOf)
                                Tool.REMOVE_PLAYED -> {
                                    val since = System.currentTimeMillis() - (days.toLongOrNull() ?: 7L) * 86_400_000L
                                    ListTools.removePlayed(items, context.app.listening.playedSince(since)) { it.mediaId }
                                }
                                Tool.ADD_OTHER -> ListTools.add(items, second, ::keyOf)
                                Tool.KEEP_SHARED -> ListTools.keepShared(items, second, ::keyOf)
                                Tool.REMOVE_OTHER -> ListTools.removeOther(items, second, ::keyOf)
                            }
                        }
                    }
                },
            ) { Text(stringResource(R.string.list_tools_apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
