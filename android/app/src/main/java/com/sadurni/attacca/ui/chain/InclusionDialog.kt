package com.sadurni.attacca.ui.chain

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.rounded.ArrowDropDown
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
    // Which inclusion list it goes to: the one in use unless another is chosen. With none in use
    // (Everything) and lists kept, one has to be chosen (or made).
    var target by remember { mutableStateOf(chain.activeSet) }
    var naming by remember { mutableStateOf(false) }
    val targetSet = chain.inclusionSets.firstOrNull { it.id == target }
    val needsChoice = chain.inclusionSets.isNotEmpty() && targetSet == null
    val allow = (targetSet?.mode ?: chain.inclusionMode) == InclusionMode.ALLOW
    val hasList = if (targetSet != null) targetSet.list != null else chain.inclusionList != null

    fun apply(add: Boolean) {
        val (kind, value) = choices.getOrNull(chosen) ?: return
        working = true
        scope.launch {
            val count = runCatching { app.inclusionStore.change(InclusionRules.Rule(true, kind, value), add, target) }.getOrNull()
            working = false
            Toast.makeText(
                context,
                if (count == null) context.getString(R.string.inclusion_failed) else context.resources.getQuantityString(R.plurals.inclusion_count, count, count),
                Toast.LENGTH_SHORT,
            ).show()
            onDismiss()
        }
    }

    if (naming) {
        com.sadurni.attacca.ui.settings.NameDialog(
            title = stringResource(R.string.inclusion_set_new),
            initial = "",
            onConfirm = { name ->
                target = com.sadurni.attacca.settings.InclusionSets.add(app.settings, name)
                naming = false
            },
            onDismiss = { naming = false },
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (allow) R.string.inclusion_dialog_allow else R.string.inclusion_dialog_block)) },
        text = {
            Column {
                if (chain.inclusionSets.isNotEmpty()) {
                    TargetListPicker(targetSet?.name, onPick = { target = it }, onNew = { naming = true })
                    if (needsChoice) Hint(stringResource(R.string.inclusion_choose_list))
                }
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
                if (!hasList && !needsChoice) Hint(stringResource(R.string.inclusion_will_create))
                Text(stringResource(R.string.inclusion_dialog_hint), fontSize = AttaccaType.caption)
            }
        },
        confirmButton = {
            TextButton(onClick = { apply(true) }, enabled = !working && !needsChoice) { Text(stringResource(R.string.inclusion_add)) }
        },
        dismissButton = {
            TextButton(onClick = { apply(false) }, enabled = !working && !needsChoice && hasList) {
                Text(stringResource(R.string.inclusion_remove))
            }
        },
    )
}

/** "List: <name ▾>": which inclusion list the dialog adds to, any of those kept or a new one. */
@Composable
private fun TargetListPicker(current: String?, onPick: (String) -> Unit, onNew: () -> Unit) {
    val chain by LocalContext.current.app.settings.chain.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        androidx.compose.foundation.layout.Row(
            androidx.compose.ui.Modifier.clickable { open = true }.padding(vertical = 6.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.inclusion_target_label))
            Text(
                current ?: stringResource(R.string.inclusion_choose),
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                color = com.sadurni.attacca.ui.theme.LocalAccent.current,
                modifier = androidx.compose.ui.Modifier.padding(start = 6.dp),
            )
            com.sadurni.attacca.ui.common.Icon(androidx.compose.material.icons.Icons.Rounded.ArrowDropDown, contentDescription = null, tint = com.sadurni.attacca.ui.theme.LocalAccent.current)
        }
        androidx.compose.material3.DropdownMenu(open, { open = false }) {
            for (set in chain.inclusionSets) {
                androidx.compose.material3.DropdownMenuItem(text = { Text(set.name) }, onClick = { onPick(set.id); open = false })
            }
            androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(R.string.inclusion_set_new) + "…") }, onClick = { onNew(); open = false })
        }
    }
}
