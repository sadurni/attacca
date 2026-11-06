package com.sadurni.attacca.ui.chain

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sadurni.attacca.R
import com.sadurni.attacca.app
import com.sadurni.attacca.library.TrackSort
import com.sadurni.attacca.ui.common.Icon
import com.sadurni.attacca.ui.common.IconButton
import com.sadurni.attacca.ui.theme.AttaccaType
import com.sadurni.attacca.ui.theme.LocalAttaccaColors
import java.io.File

/** Most tracks a made list can be asked for. */
const val MAX_LIST_COUNT = 10_000

/** The usual sizes, a tap away wherever the tracks of a list are asked. */
private val COUNT_CHOICES = listOf(50, 100, 150, 300, 500)

/** The tracks asked for in [text], or [fallback] when it is not a number; at least 2. */
fun listCountOf(text: String, fallback: Int): Int = text.toIntOrNull()?.coerceIn(2, MAX_LIST_COUNT) ?: fallback

/** One chip per usual size; the one matching [value] is on. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CountChips(value: String, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (n in COUNT_CHOICES) {
            FilterChip(selected = value == n.toString(), onClick = { onPick(n.toString()) }, label = { Text("$n") })
        }
    }
}

/**
 * "Tracks per list", a number field and the usual sizes as chips. In Settings it is the default;
 * where a list is made, the tracks of that list only.
 */
@Composable
fun TrackCountField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.tracks_per_list), fontSize = AttaccaType.body, modifier = Modifier.weight(1f).padding(end = 8.dp))
            OutlinedTextField(
                value = value,
                onValueChange = { text -> onChange(text.filter(Char::isDigit).take(5)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(96.dp),
            )
        }
        CountChips(value, onChange)
    }
}

/**
 * The options of one list about to be made, starting from the defaults (Settings → Chaining and
 * Inclusion list) and never changing them: its tracks and whether it takes only the included ones.
 * [header] shows what the list is (a bridge's two ends…); [onMake] gets the tracks and the choice.
 */
@Composable
fun ListOptionsDialog(
    title: String,
    onDismiss: () -> Unit,
    onMake: (count: Int, onlyIncluded: Boolean) -> Unit,
    header: @Composable () -> Unit = {},
) {
    val lists = LocalContext.current.app.chainLists
    var count by rememberSaveable { mutableStateOf(lists.listCount.toString()) }
    var onlyIncluded by rememberSaveable { mutableStateOf(lists.onlyIncludedByDefault()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                header()
                TrackCountField(count, { count = it })
                OnlyIncludedSwitch(onlyIncluded) { onlyIncluded = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onMake(listCountOf(count, lists.listCount), onlyIncluded)
            }) { Text(stringResource(R.string.list_options_make)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** The bridge's options: from which track to which (swappable), its tracks and inclusion. */
@Composable
fun BridgeDialog(from: String, to: String, onDismiss: () -> Unit, onMake: (from: String, to: String, count: Int, onlyIncluded: Boolean) -> Unit) {
    var ends by rememberSaveable { mutableStateOf(listOf(from, to)) }
    ListOptionsDialog(
        title = stringResource(R.string.list_bridge_action),
        onDismiss = onDismiss,
        onMake = { count, onlyIncluded -> onMake(ends[0], ends[1], count, onlyIncluded) },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Column(Modifier.weight(1f)) {
                BridgeEnd(stringResource(R.string.bridge_from), ends[0])
                BridgeEnd(stringResource(R.string.bridge_to), ends[1])
            }
            IconButton(onClick = { ends = ends.reversed() }) {
                Icon(Icons.Rounded.SwapVert, stringResource(R.string.bridge_swap))
            }
        }
    }
}

@Composable
private fun BridgeEnd(label: String, id: String) {
    val tags = TrackSort.cachedTags(id)
    val title = tags?.title?.ifBlank { null } ?: File(id).nameWithoutExtension
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, fontSize = AttaccaType.caption, color = LocalAttaccaColors.current.textGrayed)
        Text(title, fontSize = AttaccaType.body, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        tags?.artist?.ifBlank { null }?.let {
            Text(it, fontSize = AttaccaType.secondary, color = LocalAttaccaColors.current.textGrayed, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
