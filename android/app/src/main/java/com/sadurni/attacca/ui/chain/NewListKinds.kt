package com.sadurni.attacca.ui.chain

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sadurni.attacca.R
import com.sadurni.attacca.app
import com.sadurni.attacca.chain.WakeAlarm
import com.sadurni.attacca.ui.theme.AttaccaType
import com.sadurni.attacca.ui.theme.LocalAttaccaColors

/** Points of a drawn energy curve, evenly along the list. */
private const val CURVE_POINTS = 24

/** A curve to start drawing from: up to a peak two thirds in, then down. */
private fun startingCurve(): List<Float> = List(CURVE_POINTS) { i ->
    val p = i / (CURVE_POINTS - 1f)
    if (p < 0.65f) 0.2f + 0.7f * (p / 0.65f) else 0.9f - 0.6f * ((p - 0.65f) / 0.35f)
}

/**
 * The energy of a list drawn with a finger: left the start of the list, right its end, up more
 * energy. [onMake] gets the curve (each point 0 … 1).
 */
@Composable
fun DrawCurveDialog(onDismiss: () -> Unit, onMake: (List<Float>) -> Unit) {
    var curve by rememberSaveable { mutableStateOf(startingCurve()) }
    val colors = LocalAttaccaColors.current
    val accent = com.sadurni.attacca.ui.theme.LocalAccent.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.list_kind_drawn)) },
        text = {
            Column {
                Text(stringResource(R.string.draw_curve_hint), fontSize = AttaccaType.secondary, color = colors.textGrayed)
                Text(stringResource(R.string.draw_curve_high), fontSize = AttaccaType.caption, color = colors.textGrayed, modifier = Modifier.padding(top = 8.dp))
                // Where the finger was last, so a fast stroke fills the points it jumped over.
                var last by rememberSaveable { mutableStateOf(-1) }
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .pointerInput(Unit) {
                            fun put(at: Offset, joinFromLast: Boolean) {
                                val i = (at.x / size.width * (CURVE_POINTS - 1)).let { kotlin.math.round(it).toInt() }.coerceIn(0, CURVE_POINTS - 1)
                                val v = (1f - at.y / size.height).coerceIn(0f, 1f)
                                val next = curve.toMutableList()
                                val from = if (joinFromLast && last >= 0) last else i
                                val range = if (from <= i) from..i else i..from
                                for (j in range) {
                                    val t = if (from == i) 1f else (j - from).toFloat() / (i - from)
                                    next[j] = if (j == i || from == i) v else next[from] + (v - next[from]) * t
                                }
                                curve = next
                                last = i
                            }
                            detectDragGestures(
                                onDragStart = { put(it, false) },
                                onDragEnd = { last = -1 },
                                onDrag = { change, _ -> put(change.position, true) },
                            )
                        }
                        .pointerInput(Unit) {
                            detectTapGestures { at ->
                                val i = (at.x / size.width * (CURVE_POINTS - 1)).let { kotlin.math.round(it).toInt() }.coerceIn(0, CURVE_POINTS - 1)
                                curve = curve.toMutableList().also { it[i] = (1f - at.y / size.height).coerceIn(0f, 1f) }
                            }
                        },
                ) {
                    val w = size.width
                    val h = size.height
                    for (k in 1..3) drawLine(colors.textGrayed.copy(alpha = 0.25f), Offset(0f, h * k / 4), Offset(w, h * k / 4))
                    val path = Path()
                    curve.forEachIndexed { i, v ->
                        val x = w * i / (CURVE_POINTS - 1)
                        val y = h * (1f - v)
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, accent, style = Stroke(width = 3.dp.toPx()))
                    curve.forEachIndexed { i, v -> drawCircle(accent, 3.dp.toPx(), Offset(w * i / (CURVE_POINTS - 1), h * (1f - v))) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.draw_curve_low), fontSize = AttaccaType.caption, color = colors.textGrayed)
                    TextButton(onClick = { curve = startingCurve() }) { Text(stringResource(R.string.draw_curve_reset)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onMake(curve) }) { Text(stringResource(R.string.list_options_make)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * A list for two: each person's tracks are one of the inclusion sets (or every track); the list
 * takes one of each in turn and favours tracks both like. [onMake] gets the two sets' ids (null:
 * every track).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ForTwoDialog(onDismiss: () -> Unit, onMake: (String?, String?) -> Unit) {
    val app = LocalContext.current.app
    val chain by app.settings.chain.collectAsStateWithLifecycle()
    val sets = listOf<Pair<String?, String>>(null to stringResource(R.string.list_two_everything)) + chain.inclusionSets.map { it.id to it.name }
    var first by rememberSaveable { mutableStateOf(chain.activeSet ?: chain.inclusionSets.firstOrNull()?.id) }
    var second by rememberSaveable { mutableStateOf(chain.inclusionSets.firstOrNull { it.id != first }?.id) }
    @Composable
    fun Side(label: String, chosen: String?, onChoose: (String?) -> Unit) {
        Text(label, fontSize = AttaccaType.secondary, modifier = Modifier.padding(top = 8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((id, name) in sets) FilterChip(selected = chosen == id, onClick = { onChoose(id) }, label = { Text(name) })
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.more_lists_for_two)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.list_two_hint), fontSize = AttaccaType.secondary, color = LocalAttaccaColors.current.textGrayed)
                Side(stringResource(R.string.list_two_first), first) { first = it }
                Side(stringResource(R.string.list_two_second), second) { second = it }
            }
        },
        confirmButton = { TextButton(enabled = first != second, onClick = { onMake(first, second) }) { Text(stringResource(R.string.list_options_make)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * The list to wake up to: at a set time (once or every day) the phone's alarm clock opens Attacca,
 * which makes a list rising in energy for the minutes set and plays it with the volume coming up.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WakeDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val wake by WakeAlarm.state(context).collectAsStateWithLifecycle()
    var on by rememberSaveable { mutableStateOf(true) }
    var minutes by rememberSaveable { mutableStateOf(wake.minutes.toString()) }
    var daily by rememberSaveable { mutableStateOf(wake.daily) }
    val time = rememberTimePickerState(wake.hour, wake.minute, android.text.format.DateFormat.is24HourFormat(context))
    val colors = LocalAttaccaColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.more_lists_wake)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.wake_hint), fontSize = AttaccaType.secondary, color = colors.textGrayed)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                    Text(stringResource(R.string.wake_on), fontSize = AttaccaType.body, modifier = Modifier.weight(1f))
                    Switch(checked = on, onCheckedChange = { on = it })
                }
                TimeInput(state = time)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.more_lists_minutes), fontSize = AttaccaType.secondary, modifier = Modifier.padding(end = 8.dp))
                    OutlinedTextField(
                        value = minutes,
                        onValueChange = { text -> minutes = text.filter(Char::isDigit).take(3) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(96.dp),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = daily, onCheckedChange = { daily = it })
                    Text(stringResource(R.string.wake_daily), fontSize = AttaccaType.body)
                }
                if (!WakeAlarm.canBeExact(context) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Text(stringResource(R.string.wake_exact_hint), fontSize = AttaccaType.secondary, color = colors.textGrayed, modifier = Modifier.padding(top = 8.dp))
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, android.net.Uri.parse("package:${context.packageName}")),
                            )
                        }
                    }) { Text(stringResource(R.string.wake_exact_allow)) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                WakeAlarm.set(context, WakeAlarm.Wake(on, time.hour, time.minute, minutes.toIntOrNull()?.coerceIn(5, 240) ?: 30, daily))
                onDismiss()
            }) { Text(stringResource(R.string.smart_save)) }
        },
        dismissButton = {
            if (wake.on) {
                TextButton(onClick = {
                    WakeAlarm.set(context, wake.copy(on = false))
                    onDismiss()
                }) { Text(stringResource(R.string.wake_off)) }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

/** "Wakes you at 7:30" for the list of kinds, or null when the alarm is off. */
@Composable
fun wakeSummary(): String? {
    val context = LocalContext.current
    val wake by WakeAlarm.state(context).collectAsStateWithLifecycle()
    val at = WakeAlarm.nextAt(wake) ?: return null
    return stringResource(R.string.wake_set_for, android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(at)))
}
