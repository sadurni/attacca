package com.sadurni.attacca.ui.player

import com.sadurni.attacca.ui.theme.AttaccaType
import android.os.SystemClock
import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.ui.platform.LocalContext
import com.sadurni.attacca.settings.SleepChoice
import java.time.LocalTime
import java.util.Calendar
import java.util.Date
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import com.sadurni.attacca.ui.common.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import com.sadurni.attacca.ui.theme.LocalAccent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sadurni.attacca.R
import com.sadurni.attacca.library.UserLists
import com.sadurni.attacca.playback.SleepTimer
import com.sadurni.attacca.settings.ListSettings
import com.sadurni.attacca.settings.ReplayGainMode
import com.sadurni.attacca.settings.ShuffleMode
import com.sadurni.attacca.ui.common.formatTime
import com.sadurni.attacca.ui.settings.NameDialog
import com.sadurni.attacca.ui.settings.RadioRow
import com.sadurni.attacca.ui.settings.SectionTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

private val SLEEP_PRESETS = listOf(15, 30, 45, 60, 90)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerDialog(
    timer: SleepTimer?,
    lastChoice: SleepChoice,
    onStart: (minutes: Int) -> Unit,
    onStartAt: (hour: Int, minute: Int) -> Unit,
    onEndOf: (com.sadurni.attacca.playback.SleepEnd) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val lastMinutes = (lastChoice as? SleepChoice.Minutes)?.minutes
    val customLast = lastMinutes != null && lastMinutes !in SLEEP_PRESETS
    var minutes by remember { mutableFloatStateOf(if (customLast) lastMinutes!!.toFloat() else 30f) }
    var pickingTime by remember { mutableStateOf(false) }
    val lastAt = lastChoice as? SleepChoice.At

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_sleep_timer)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (timer != null) {
                    var remaining by remember { mutableStateOf("") }
                    LaunchedEffect(timer) {
                        while (true) {
                            remaining = timer.endsAtElapsedMs?.let { formatTime(it - SystemClock.elapsedRealtime()) }.orEmpty()
                            delay(1000)
                        }
                    }
                    val endsAt = timer.endsAtWallMs?.let { DateFormat.getTimeFormat(context).format(Date(it)) }
                    Text(
                        when {
                            timer.endOf == com.sadurni.attacca.playback.SleepEnd.TRACK -> stringResource(R.string.sleep_active_end_of_track)
                            timer.endOf == com.sadurni.attacca.playback.SleepEnd.ALBUM -> stringResource(R.string.sleep_active_end_of_album)
                            timer.endOf == com.sadurni.attacca.playback.SleepEnd.ARTIST -> stringResource(R.string.sleep_active_end_of_artist)
                            timer.endOf == com.sadurni.attacca.playback.SleepEnd.LIST -> stringResource(R.string.sleep_active_end_of_list)
                            endsAt != null -> stringResource(R.string.sleep_active_at, endsAt, remaining)
                            else -> stringResource(R.string.sleep_active_remaining, remaining)
                        },
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
                for (preset in SLEEP_PRESETS) {
                    SleepOption(stringResource(R.string.sleep_minutes, preset), lastMinutes == preset) { onStart(preset) }
                }
                SleepOption(stringResource(R.string.sleep_end_of_track), lastChoice == SleepChoice.EndOfTrack) { onEndOf(com.sadurni.attacca.playback.SleepEnd.TRACK) }
                SleepOption(stringResource(R.string.sleep_end_of_album), lastChoice == SleepChoice.EndOfAlbum) { onEndOf(com.sadurni.attacca.playback.SleepEnd.ALBUM) }
                SleepOption(stringResource(R.string.sleep_end_of_artist), lastChoice == SleepChoice.EndOfArtist) { onEndOf(com.sadurni.attacca.playback.SleepEnd.ARTIST) }
                SleepOption(stringResource(R.string.sleep_end_of_list), lastChoice == SleepChoice.EndOfList) { onEndOf(com.sadurni.attacca.playback.SleepEnd.LIST) }
                SleepOption(
                    if (lastAt != null) stringResource(R.string.sleep_at_time, clockText(context, lastAt.hour, lastAt.minute))
                    else stringResource(R.string.sleep_at_time_pick),
                    lastAt != null,
                ) { pickingTime = true }
                Text(
                    stringResource(R.string.sleep_custom, minutes.roundToInt()),
                    color = if (customLast) LocalAccent.current else Color.Unspecified,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Slider(value = minutes, onValueChange = { minutes = it }, valueRange = 5f..240f, steps = 46)
            }
        },
        confirmButton = {
            TextButton(onClick = { onStart(minutes.roundToInt()) }) { Text(stringResource(R.string.sleep_start)) }
        },
        dismissButton = {
            if (timer != null) {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.sleep_cancel)) }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )

    if (pickingTime) {
        val initial = lastAt ?: LocalTime.now().plusHours(1).let { SleepChoice.At(it.hour, 0) }
        val state = rememberTimePickerState(initial.hour, initial.minute, DateFormat.is24HourFormat(context))
        AlertDialog(
            onDismissRequest = { pickingTime = false },
            title = { Text(stringResource(R.string.sleep_pick_time)) },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    pickingTime = false
                    onStartAt(state.hour, state.minute)
                }) { Text(stringResource(R.string.sleep_start)) }
            },
            dismissButton = {
                TextButton(onClick = { pickingTime = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/** One option of the sleep timer; [last] marks the one used last time. */
@Composable
private fun SleepOption(label: String, last: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            fontSize = AttaccaType.heading,
            color = if (last) LocalAccent.current else Color.Unspecified,
            modifier = Modifier.weight(1f),
        )
        if (last) Icon(Icons.Rounded.Check, contentDescription = stringResource(R.string.sleep_last_used), tint = LocalAccent.current)
    }
}

private fun clockText(context: Context, hour: Int, minute: Int): String {
    val calendar = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
    }
    return DateFormat.getTimeFormat(context).format(calendar.time)
}

@Composable
fun SpeedDialog(speed: Float, onChange: (Float) -> Unit, onDismiss: () -> Unit, pitch: Float = 0f, onPitch: (Float) -> Unit = {}) {
    var value by remember { mutableFloatStateOf(speed) }
    var semitones by remember { mutableFloatStateOf(pitch) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.speed_title)) },
        text = {
            Column {
                Text("%.2f×".format(value), fontSize = AttaccaType.title)
                Slider(
                    value = value,
                    onValueChange = { value = (it * 20).roundToInt() / 20f },
                    onValueChangeFinished = { onChange(value) },
                    valueRange = 0.5f..2f,
                    steps = 29,
                )
                Text(stringResource(R.string.pitch_title), fontSize = AttaccaType.secondary)
                Text(
                    if (semitones == 0f) stringResource(R.string.pitch_none) else stringResource(R.string.pitch_semitones, "%+.1f".format(semitones)),
                    fontSize = AttaccaType.title,
                )
                Slider(
                    value = semitones,
                    onValueChange = { semitones = (it * 2).roundToInt() / 2f },
                    onValueChangeFinished = { onPitch(semitones) },
                    valueRange = -6f..6f,
                    steps = 23,
                )
                Text(stringResource(R.string.pitch_hint), fontSize = AttaccaType.secondary)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
        dismissButton = {
            TextButton(onClick = {
                value = 1f
                semitones = 0f
                onChange(1f)
                onPitch(0f)
            }) { Text(stringResource(R.string.speed_reset)) }
        },
    )
}

/** Pick one of the user's lists (or create one) to add the track to. */
@Composable
fun AddToListDialog(onPick: (File) -> Unit, onDismiss: () -> Unit) {
    var lists by remember { mutableStateOf<List<File>>(emptyList()) }
    var creating by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { lists = withContext(Dispatchers.IO) { UserLists.all() } }
    if (creating) {
        NameDialog(
            title = stringResource(R.string.list_new),
            label = stringResource(R.string.list_name),
            initial = "",
            onConfirm = { name -> onPick(UserLists.fileFor(name)) },
            onDismiss = { creating = false },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_to_list)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                item {
                    Text(
                        stringResource(R.string.list_new),
                        fontSize = AttaccaType.heading,
                        modifier = Modifier.fillMaxWidth().clickable { creating = true }.padding(vertical = 10.dp),
                    )
                }
                items(lists) { file ->
                    Text(
                        file.nameWithoutExtension,
                        fontSize = AttaccaType.heading,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(file) }.padding(vertical = 10.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Settings the current list keeps for itself. Null choices follow the global setting. */
@Composable
fun ListSettingsDialog(settings: ListSettings, onChange: ((ListSettings) -> ListSettings) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.list_settings)) },
        text = {
            ScrollHintColumn {
                item {
                    SectionTitle(stringResource(R.string.action_shuffle))
                    for ((mode, label) in listOf(
                        ShuffleMode.OFF to R.string.shuffle_off,
                        ShuffleMode.TRACKS to R.string.shuffle_tracks,
                        ShuffleMode.ALBUMS to R.string.shuffle_albums,
                    )) {
                        RadioRow(stringResource(label), settings.shuffle == mode) { onChange { it.copy(shuffle = mode) } }
                    }
                    SectionTitle(stringResource(R.string.crossfade))
                    RadioRow(stringResource(R.string.follow_global), settings.crossfadeMs == null) {
                        onChange { it.copy(crossfadeMs = null) }
                    }
                    RadioRow(stringResource(R.string.off), settings.crossfadeMs == 0) { onChange { it.copy(crossfadeMs = 0) } }
                    for (seconds in listOf(2, 4, 6, 8, 10, 12)) {
                        RadioRow("$seconds s", settings.crossfadeMs == seconds * 1000) {
                            onChange { it.copy(crossfadeMs = seconds * 1000) }
                        }
                    }
                    SectionTitle(stringResource(R.string.section_replaygain))
                    for ((mode, label) in listOf(
                        null to R.string.follow_global,
                        ReplayGainMode.OFF to R.string.rg_off,
                        ReplayGainMode.TRACK to R.string.rg_track,
                        ReplayGainMode.ALBUM to R.string.rg_album,
                        ReplayGainMode.AUTO to R.string.rg_auto,
                    )) {
                        RadioRow(stringResource(label), settings.replayGainMode == mode) { onChange { it.copy(replayGainMode = mode) } }
                    }
                    SectionTitle(stringResource(R.string.endless_title))
                    for ((value, label) in listOf(
                        null to R.string.follow_global,
                        true to R.string.on,
                        false to R.string.off,
                    )) {
                        RadioRow(stringResource(label), settings.endless == value) { onChange { it.copy(endless = value) } }
                    }
                    SectionTitle(stringResource(R.string.section_silence))
                    for ((value, label) in listOf(
                        null to R.string.follow_global,
                        true to R.string.on,
                        false to R.string.off,
                    )) {
                        RadioRow(stringResource(label), settings.trimEnabled == value) { onChange { it.copy(trimEnabled = value) } }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
    )
}

/**
 * A scrolling dialog body that shows, at the bottom, a fade and an arrow while there is more
 * content below, so it is obvious that the dialog scrolls.
 */
@Composable
fun ScrollHintColumn(maxHeight: Dp = 480.dp, content: LazyListScope.() -> Unit) {
    val state = rememberLazyListState()
    Box {
        LazyColumn(Modifier.heightIn(max = maxHeight), state = state, content = content)
        if (state.canScrollForward) {
            val background = MaterialTheme.colorScheme.surfaceContainerHigh
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, background))),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, tint = LocalAccent.current)
            }
        }
    }
}
