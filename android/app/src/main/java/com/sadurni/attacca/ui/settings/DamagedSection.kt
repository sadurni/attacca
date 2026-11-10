package com.sadurni.attacca.ui.settings

import android.widget.Toast
import androidx.compose.material.icons.rounded.Search
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sadurni.attacca.R
import com.sadurni.attacca.app
import com.sadurni.attacca.library.DamageCheck
import com.sadurni.attacca.library.DamagedTracks
import com.sadurni.attacca.ui.theme.AttaccaType
import com.sadurni.attacca.ui.theme.LocalAttaccaColors
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The damaged tracks of the music folder: how many, a check of every file, the list (to repair
 * or forget each one) and whether they stay out of the lists Attacca makes.
 */
@Composable
fun DamagedSection(folder: String) {
    val context = LocalContext.current
    val settings = context.app.settings
    val chain by settings.chain.collectAsStateWithLifecycle()
    val all by DamagedTracks.all.collectAsStateWithLifecycle()
    val progress by DamageCheck.progress.collectAsStateWithLifecycle()
    var showList by remember { mutableStateOf(false) }
    val count = remember(all, folder) { DamagedTracks.under(folder).size }

    SectionTitle(stringResource(R.string.damaged_title))
    var confirmAll by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(enabled = progress == null) { DamageCheck.checkFolder(File(folder), chain.analysisThreads) }
            .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            ActionText(stringResource(R.string.damaged_check_new), enabled = progress == null)
            val p = progress
            Text(
                if (p != null) com.sadurni.attacca.ui.common.analysisProgressText(R.string.damaged_checking, p.done, p.total, p.remainingMs) +
                    "\n" + stringResource(R.string.damaged_count, p.found)
                else stringResource(R.string.damaged_count, count),
                fontSize = AttaccaType.secondary,
                color = LocalAttaccaColors.current.textGrayed,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (progress != null) TextButton(onClick = DamageCheck::cancel) { Text(stringResource(R.string.cancel)) }
    }
    Row(
        Modifier.fillMaxWidth().clickable(enabled = progress == null) { confirmAll = true }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) { ActionText(stringResource(R.string.damaged_check_all), enabled = progress == null) }
    Hint(stringResource(R.string.damaged_check_new_hint))
    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text(stringResource(R.string.damaged_check_all)) },
            text = { Text(stringResource(R.string.damaged_check_all_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmAll = false
                    DamageCheck.checkFolder(File(folder), chain.analysisThreads, all = true)
                }) { Text(stringResource(R.string.damaged_check_all)) }
            },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    progress?.let { p ->
        if (p.total > 0) androidx.compose.material3.LinearProgressIndicator(progress = { p.done.toFloat() / p.total }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
    }
    if (count > 0) {
        Row(Modifier.fillMaxWidth().clickable { showList = true }.padding(horizontal = 16.dp, vertical = 12.dp)) {
            ActionText(stringResource(R.string.damaged_show))
        }
    }
    SwitchRow(stringResource(R.string.damaged_skip), chain.skipDamaged, { on -> settings.updateChain { it.copy(skipDamaged = on) } })
    Hint(stringResource(R.string.damaged_hint))
    if (showList) DamagedListDialog(folder) { showList = false }
}

/** What is wrong with a track, in words. */
@Composable
fun damageText(d: DamagedTracks.Damage): String = when (d.kind) {
    DamagedTracks.Kind.UNREADABLE -> stringResource(R.string.damaged_unreadable)
    DamagedTracks.Kind.BROKEN_FRAMES -> stringResource(R.string.damaged_broken, d.amount)
    DamagedTracks.Kind.REPEATS -> stringResource(R.string.damaged_repeats, "%.1f".format(d.amount / 1000f))
    DamagedTracks.Kind.PLAY_ERROR -> stringResource(R.string.damaged_play_error)
    DamagedTracks.Kind.TRUNCATED -> stringResource(R.string.damaged_truncated, "%.0f".format(d.amount / 1000f))
    DamagedTracks.Kind.BAD_FRAMES -> stringResource(R.string.damaged_bad_frames)
}

/** The damaged tracks under [folder], each with what is wrong and what can be done. */
@Composable
internal fun DamagedListDialog(folder: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val all by DamagedTracks.all.collectAsStateWithLifecycle()
    val every = remember(all, folder) { DamagedTracks.under(folder) }
    var query by remember { mutableStateOf("") }
    // Each word of the search in the file's path (folder and name: artist, album, title).
    val list = remember(every, query) {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) every else every.filter { (path, _) -> val p = path.removePrefix(folder).lowercase(); words.all { it in p } }
    }
    var repairing by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.damaged_title)) },
        text = {
            if (every.isEmpty()) {
                Text(stringResource(R.string.damaged_none))
            } else Column {
                if (every.size > 3) {
                    androidx.compose.material3.OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.search)) },
                        leadingIcon = { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.Search, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    )
                }
                if (list.isEmpty()) Text(stringResource(R.string.search_nothing))
                LazyColumn(Modifier.heightIn(max = 460.dp)) {
                    items(list, key = { it.first }) { (path, damage) ->
                        val file = File(path)
                        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Text(file.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(file.parentFile?.path?.removePrefix(folder.trimEnd('/'))?.trimStart('/').orEmpty(), fontSize = AttaccaType.caption, color = LocalAttaccaColors.current.textGrayed, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(damageText(damage), fontSize = AttaccaType.secondary, color = com.sadurni.attacca.ui.common.DamagedColor)
                            // The actions wrap onto a second line when they do not fit.
                            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                            androidx.compose.foundation.layout.FlowRow {
                                if (damage.kind.repairable && file.extension.equals("mp3", ignoreCase = true)) {
                                    TextButton(enabled = !busy, onClick = { repairing = path }) { Text(stringResource(R.string.damaged_repair)) }
                                }
                                TextButton(enabled = !busy, onClick = {
                                    busy = true
                                    scope.launch {
                                        val result = withContext(com.sadurni.attacca.Background.dispatcher) { runCatching { DamageCheck.check(file) }.getOrNull() }
                                        if (result == null) DamagedTracks.forget(path) else DamagedTracks.report(path, result.first, result.second)
                                        busy = false
                                        Toast.makeText(context, if (result == null) R.string.damaged_fine else R.string.damaged_still, Toast.LENGTH_SHORT).show()
                                    }
                                }) { Text(stringResource(R.string.damaged_check_again)) }
                                TextButton(enabled = !busy, onClick = { DamagedTracks.forget(path) }) { Text(stringResource(R.string.damaged_forget)) }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
    )
    repairing?.let { path -> com.sadurni.attacca.ui.common.RepairDialog(listOf(path)) { repairing = null } }
}
