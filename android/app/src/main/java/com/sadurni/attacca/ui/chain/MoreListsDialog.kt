package com.sadurni.attacca.ui.chain

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.sadurni.attacca.R
import com.sadurni.attacca.app
import com.sadurni.attacca.chain.ChainLists.Kind
import com.sadurni.attacca.ui.PlayerViewModel
import com.sadurni.attacca.ui.theme.AttaccaType
import com.sadurni.attacca.ui.theme.LocalAttaccaColors

/**
 * Other kinds of list, made and played at once: energy curves, look-alikes of the favorites not
 * heard lately, a sampler of the sound families, and, from the playing track, its opposite, a
 * list for a set time and whole albums like its album.
 */
@Composable
fun MoreListsDialog(viewModel: PlayerViewModel, onDismiss: () -> Unit) {
    var minutes by rememberSaveable { mutableStateOf("60") }
    var days by rememberSaveable { mutableStateOf("90") }
    val app = androidx.compose.ui.platform.LocalContext.current.app
    var onlyIncluded by rememberSaveable { mutableStateOf(app.chainLists.onlyIncludedByDefault()) }
    var count by rememberSaveable { mutableStateOf(app.chainLists.listCount.toString()) }
    fun make(kind: Kind, value: Int = 0) {
        onDismiss()
        viewModel.makeList(kind, value, onlyIncluded = onlyIncluded, count = listCountOf(count, app.chainLists.listCount))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.more_lists_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OnlyIncludedSwitch(onlyIncluded) { onlyIncluded = it }
                TrackCountField(count, { count = it })
                Section(stringResource(R.string.more_lists_any))
                Choice(R.string.list_kind_rise_fall, R.string.more_lists_rise_fall_hint) { make(Kind.RISE_AND_FALL) }
                Choice(R.string.list_kind_warm_up, R.string.more_lists_warm_up_hint) { make(Kind.WARM_UP) }
                Choice(R.string.list_kind_wind_down, R.string.more_lists_wind_down_hint) { make(Kind.WIND_DOWN) }
                Choice(R.string.list_kind_sampler, R.string.more_lists_sampler_hint) { make(Kind.SAMPLER) }
                Choice(R.string.list_kind_rediscover, R.string.more_lists_rediscover_hint) { make(Kind.REDISCOVER, days.toIntOrNull() ?: 0) }
                NumberField(stringResource(R.string.more_lists_days), days) { days = it }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Section(stringResource(R.string.more_lists_from_track))
                Choice(R.string.more_lists_contrast, R.string.more_lists_contrast_hint) { make(Kind.CONTRAST) }
                Choice(R.string.more_lists_albums, R.string.more_lists_albums_hint) { make(Kind.ALBUMS_LIKE) }
                Choice(R.string.more_lists_time, R.string.more_lists_time_hint) { make(Kind.FOR_TIME, minutes.toIntOrNull()?.coerceIn(1, 1440) ?: 60) }
                NumberField(stringResource(R.string.more_lists_minutes), minutes) { minutes = it }
                Text(
                    stringResource(R.string.more_lists_bridge_hint),
                    fontSize = AttaccaType.secondary,
                    color = LocalAttaccaColors.current.textGrayed,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun Section(text: String) {
    Text(text, fontSize = AttaccaType.secondary, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 4.dp))
}

@Composable
private fun Choice(title: Int, hint: Int, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp)) {
        Text(stringResource(title), fontSize = AttaccaType.body)
        Text(stringResource(hint), fontSize = AttaccaType.secondary, color = LocalAttaccaColors.current.textGrayed)
    }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp)) {
        Text(label, fontSize = AttaccaType.secondary, modifier = Modifier.padding(end = 8.dp))
        OutlinedTextField(
            value = value,
            onValueChange = { text -> onChange(text.filter(Char::isDigit).take(4)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(96.dp),
        )
    }
}
