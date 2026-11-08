package com.sadurni.attacca.ui.common

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sadurni.attacca.Background
import com.sadurni.attacca.R
import com.sadurni.attacca.library.DamageCheck
import com.sadurni.attacca.library.DamagedTracks
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Repairs the damaged MP3s among [paths]: a track asked for by itself, the damaged tracks under a
 * folder, those of a list or a group. Asks first what becomes of the originals (kept beside as
 * ".bk" or deleted), then repairs them one by one; each file written again is told on
 * [DamagedTracks.repaired] (the player reads it again where it plays).
 *
 * A track asked for by itself is repaired when it is known damaged, when it was repaired before
 * (the repair starts again from its ".bk") or when a check finds damage now.
 */
@Composable
fun RepairDialog(paths: List<String>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var targets by remember(paths) { mutableStateOf<List<String>?>(null) }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    // When much was lost, the result stays on screen until read (a toast would cut it short).
    var result by remember { mutableStateOf<String?>(null) }
    result?.let { text ->
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.damaged_repair)) },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
        )
        return
    }

    LaunchedEffect(paths) {
        val found = withContext(Background.dispatcher) { repairTargets(paths) }
        if (found.isEmpty()) {
            Toast.makeText(context, if (paths.size == 1 && File(paths[0]).isFile) R.string.damaged_nothing else R.string.damaged_repair_none, Toast.LENGTH_SHORT).show()
            onDismiss()
        } else {
            targets = found
        }
    }

    val list = targets ?: return
    val running = progress
    if (running != null) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.damaged_repair)) },
            text = {
                Column {
                    Text(stringResource(R.string.damaged_repairing, running.first, running.second))
                    LinearProgressIndicator(
                        progress = { running.first.toFloat() / running.second.coerceAtLeast(1) },
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                }
            },
            confirmButton = {},
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.damaged_repair)) },
        text = {
            Text(
                if (list.size == 1) stringResource(R.string.damaged_repair_ask, File(list[0]).name)
                else stringResource(R.string.damaged_repair_ask_many, list.size),
            )
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                for (keep in listOf(true, false)) {
                    TextButton(onClick = {
                        progress = 0 to list.size
                        scope.launch {
                            var done = 0
                            var failed = 0
                            var lostMs = 0
                            var muchLost = false
                            for ((k, path) in list.withIndex()) {
                                progress = k to list.size
                                val result = withContext(Background.dispatcher) {
                                    runCatching { com.sadurni.attacca.playback.Mp3Damage.repairFile(File(path), keep) }.getOrNull()
                                }
                                if (result == null) {
                                    failed++
                                } else {
                                    done++
                                    lostMs += result.cutMs
                                    muchLost = muchLost || result.muchLost
                                    DamagedTracks.forget(path)
                                    DamagedTracks.repaired.tryEmit(path)
                                }
                            }
                            val text = when {
                                done == 0 -> context.getString(R.string.damaged_repair_failed)
                                list.size == 1 -> context.getString(R.string.damaged_repaired_lost, "%.1f".format(lostMs / 1000f))
                                else -> context.getString(R.string.damaged_repaired_many, done, failed, "%.1f".format(lostMs / 1000f))
                            }
                            if (muchLost) {
                                progress = null
                                result = text + "\n\n" + context.getString(R.string.damaged_much_lost)
                            } else {
                                Toast.makeText(context, text, Toast.LENGTH_LONG).show()
                                onDismiss()
                            }
                        }
                    }) { Text(stringResource(if (keep) R.string.damaged_keep_copy else R.string.damaged_delete_original)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

/** The MP3s among [paths] a repair can help (see [RepairDialog]). Blocking. */
private fun repairTargets(paths: List<String>): List<String> {
    val damaged = DamagedTracks.all.value
    fun repairable(path: String) = path.endsWith(".mp3", ignoreCase = true) && damaged[path]?.kind?.repairable == true
    val single = paths.singleOrNull()?.let(::File)
    if (single != null && single.isFile) {
        if (!single.name.endsWith(".mp3", ignoreCase = true)) return emptyList()
        if (repairable(single.path) || File(single.path + ".bk").isFile) return listOf(single.path)
        val found = runCatching { DamageCheck.check(single) }.getOrNull()
        if (found != null && found.first.repairable) {
            DamagedTracks.report(single.path, found.first, found.second)
            return listOf(single.path)
        }
        return emptyList()
    }
    val out = LinkedHashSet<String>()
    for (path in paths) {
        val f = File(path)
        if (f.isDirectory) {
            DamagedTracks.under(f.path).map { it.first }.filter(::repairable).sorted().forEach { out += it }
        } else if (repairable(path)) {
            out += path
        }
    }
    return out.toList()
}
