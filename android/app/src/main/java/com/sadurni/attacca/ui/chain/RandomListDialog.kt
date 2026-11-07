package com.sadurni.attacca.ui.chain

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sadurni.attacca.R
import com.sadurni.attacca.app
import com.sadurni.attacca.chain.Mood
import com.sadurni.attacca.ui.settings.RadioRow
import com.sadurni.attacca.ui.theme.AttaccaType
import com.sadurni.attacca.ui.theme.LocalAttaccaColors

/**
 * The mood of a random list's first track: any or one of the moods, the last one chosen
 * selected. "Don't ask again" turns the question off (Settings → Chaining turns it back on).
 */
@Composable
fun RandomListDialog(onStart: (Mood?, Boolean, Int) -> Unit, onDismiss: () -> Unit) {
    val app = LocalContext.current.app
    val settings = app.settings
    var mood by remember { mutableStateOf(settings.chain.value.randomMood) }
    var onlyIncluded by remember { mutableStateOf(app.chainLists.onlyIncludedByDefault()) }
    var count by remember { mutableStateOf(app.chainLists.listCount.toString()) }
    var dontAsk by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.random_list)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OnlyIncludedSwitch(onlyIncluded) { onlyIncluded = it }
                TrackCountField(count, { count = it })
                Text(stringResource(R.string.random_list_question), fontSize = AttaccaType.body)
                RadioRow(stringResource(R.string.random_list_any), mood == null) { mood = null }
                for (m in Mood.entries) RadioRow(moodLabel(m), mood == m) { mood = m }
                Row(
                    Modifier.fillMaxWidth().clickable { dontAsk = !dontAsk }.padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(dontAsk, onCheckedChange = { dontAsk = it })
                    Text(stringResource(R.string.random_list_dont_ask), fontSize = AttaccaType.secondary, color = LocalAttaccaColors.current.textGrayed)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (dontAsk) settings.updateChain { it.copy(randomAskMood = false) }
                onDismiss()
                onStart(mood, onlyIncluded, listCountOf(count, app.chainLists.listCount))
            }) { Text(stringResource(R.string.random_list_go)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** "Only included" for the list about to be made, starting from Settings → Inclusion list; off without a list. */
@Composable
internal fun OnlyIncludedSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val chain by LocalContext.current.app.settings.chain.collectAsStateWithLifecycle()
    val hasList = chain.inclusion() != null
    InclusionSetPicker()
    com.sadurni.attacca.ui.settings.SwitchRow(stringResource(R.string.only_included), checked && hasList, onChange, enabled = hasList)
}
