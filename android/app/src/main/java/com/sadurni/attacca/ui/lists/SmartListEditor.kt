package com.sadurni.attacca.ui.lists

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.sadurni.attacca.ui.common.Icon
import com.sadurni.attacca.ui.common.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.sadurni.attacca.R
import com.sadurni.attacca.app
import com.sadurni.attacca.chain.Mood
import com.sadurni.attacca.library.SmartRules
import com.sadurni.attacca.library.SmartRules.Condition
import com.sadurni.attacca.library.SmartRules.Field
import com.sadurni.attacca.library.SmartRules.Kind
import com.sadurni.attacca.library.SmartRules.Order
import com.sadurni.attacca.library.SmartRules.PlayedOp
import com.sadurni.attacca.library.SmartRules.TextOp
import com.sadurni.attacca.library.UserLists
import com.sadurni.attacca.ui.common.ScreenHeader
import com.sadurni.attacca.ui.settings.Hint
import com.sadurni.attacca.ui.settings.SectionTitle
import com.sadurni.attacca.ui.settings.SegmentedChoice
import com.sadurni.attacca.ui.theme.AttaccaType
import com.sadurni.attacca.ui.theme.LocalAttaccaColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun fieldLabel(field: Field): String = stringResource(
    when (field) {
        Field.ARTIST -> R.string.smart_field_artist
        Field.ALBUM_ARTIST -> R.string.smart_field_album_artist
        Field.ALBUM -> R.string.smart_field_album
        Field.TITLE -> R.string.smart_field_title
        Field.GENRE -> R.string.smart_field_genre
        Field.FOLDER -> R.string.smart_field_folder
        Field.PATH -> R.string.smart_field_path
        Field.YEAR -> R.string.smart_field_year
        Field.LENGTH -> R.string.smart_field_length
        Field.BPM -> R.string.smart_field_bpm
        Field.ENERGY -> R.string.smart_field_energy
        Field.POSITIVITY -> R.string.smart_field_positivity
        Field.MOOD -> R.string.smart_field_mood
        Field.VOICE -> R.string.smart_field_voice
        Field.ADDED -> R.string.smart_field_added
        Field.PLAYED -> R.string.smart_field_played
        Field.SKIPPED -> R.string.smart_field_skipped
        Field.INCLUDED -> R.string.smart_field_included
    },
)

@Composable
private fun orderLabel(order: Order): String = stringResource(
    when (order) {
        Order.AS_FOUND -> R.string.smart_order_found
        Order.RANDOM -> R.string.smart_order_random
        Order.ARTIST -> R.string.smart_order_artist
        Order.ALBUM -> R.string.smart_order_album
        Order.YEAR -> R.string.smart_order_year
        Order.ADDED -> R.string.smart_order_added
        Order.MOST_PLAYED -> R.string.smart_order_most_played
        Order.LAST_PLAYED -> R.string.smart_order_last_played
        Order.CHAINED -> R.string.smart_order_chained
    },
)

/** A fresh condition of [field] with sensible values. */
private fun conditionOf(field: Field): Condition = when (field.kind) {
    Kind.TEXT -> Condition(field, textOp = TextOp.CONTAINS)
    Kind.RANGE -> Condition(field)
    Kind.SET -> Condition(field, values = setOf(Mood.CALM.name.lowercase()))
    Kind.CHOICE -> Condition(field, values = setOf("voice"))
    Kind.DAYS -> Condition(field, number = 30)
    Kind.PLAYED -> Condition(field, playedOp = PlayedOp.NOT_WITHIN, number = 30)
    Kind.COUNT -> Condition(field, number = 3)
    Kind.FLAG -> Condition(field)
}

/**
 * Makes or edits a smart list: its name, its conditions (all must be met), the order and how
 * many tracks at most, with the count of tracks that match as it changes. The rules can also be
 * edited as text. [list] is null for a new one; [onSaved] gets the list file.
 */
@Composable
fun SmartListEditor(list: File?, onBack: () -> Unit, onSaved: (File) -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(list?.nameWithoutExtension.orEmpty()) }
    var rules by remember { mutableStateOf<SmartRules.Rules?>(if (list == null) SmartRules.Rules(listOf(Condition(Field.GENRE))) else null) }
    var asText by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var matching by remember { mutableStateOf<Int?>(null) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(list) {
        if (list != null) rules = withContext(com.sadurni.attacca.Background.dispatcher) { app.smartLists.rulesOf(list) } ?: SmartRules.Rules(emptyList())
    }
    // The count of matching tracks, a moment after each change.
    LaunchedEffect(rules, text, asText) {
        val r = (if (asText) SmartRules.parse(text) else rules) ?: return@LaunchedEffect
        matching = null
        delay(400)
        matching = runCatching { app.smartLists.tracksFor(r.copy(order = if (r.order == Order.CHAINED) Order.AS_FOUND else r.order)).size }.getOrNull()
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        ScreenHeader(stringResource(if (list == null) R.string.smart_new else R.string.smart_edit), onBack, root = false) {
            IconButton(onClick = {
                val current = rules
                if (!asText && current != null) text = SmartRules.format(current) else rules = SmartRules.parse(text)
                asText = !asText
            }) { Icon(Icons.Rounded.Code, contentDescription = stringResource(R.string.smart_as_text)) }
            IconButton(enabled = !saving && name.isNotBlank(), onClick = {
                val r = if (asText) SmartRules.parse(text) else rules ?: return@IconButton
                val file = list ?: UserLists.fileFor(name.trim())
                if (list == null && file.exists()) {
                    Toast.makeText(context, R.string.smart_name_taken, Toast.LENGTH_SHORT).show()
                    return@IconButton
                }
                saving = true
                scope.launch {
                    val count = runCatching { app.smartLists.save(file, r) }.getOrNull()
                    saving = false
                    if (count == null) {
                        Toast.makeText(context, R.string.smart_failed, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, context.getString(R.string.smart_saved, count), Toast.LENGTH_SHORT).show()
                        onSaved(file)
                    }
                }
            }) { Icon(Icons.Rounded.Check, contentDescription = stringResource(R.string.smart_save)) }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.list_name)) },
                singleLine = true,
                enabled = list == null,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
            Text(
                when (val m = matching) {
                    null -> "…"
                    else -> stringResource(R.string.smart_matching, m)
                },
                fontSize = AttaccaType.body,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            val r = rules
            if (asText) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = AttaccaType.secondary),
                    modifier = Modifier.fillMaxWidth().height(420.dp),
                )
            } else if (r != null) {
                SectionTitle(stringResource(R.string.smart_conditions))
                Hint(stringResource(R.string.smart_conditions_hint))
                r.conditions.forEachIndexed { i, c ->
                    ConditionCard(
                        c,
                        onChange = { new -> rules = r.copy(conditions = r.conditions.toMutableList().also { it[i] = new }) },
                        onRemove = { rules = r.copy(conditions = r.conditions.filterIndexed { j, _ -> j != i }) },
                    )
                }
                var adding by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { adding = true }, modifier = Modifier.padding(vertical = 8.dp)) {
                        Icon(Icons.Rounded.Add, null)
                        Text(stringResource(R.string.smart_add_condition), modifier = Modifier.padding(start = 4.dp))
                    }
                    DropdownMenu(expanded = adding, onDismissRequest = { adding = false }) {
                        for (field in Field.entries) {
                            DropdownMenuItem(text = { Text(fieldLabel(field)) }, onClick = {
                                adding = false
                                rules = r.copy(conditions = r.conditions + conditionOf(field))
                            })
                        }
                    }
                }
                SectionTitle(stringResource(R.string.smart_order))
                var ordering by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { ordering = true }) { Text(orderLabel(r.order)) }
                    DropdownMenu(expanded = ordering, onDismissRequest = { ordering = false }) {
                        for (order in Order.entries) {
                            DropdownMenuItem(text = { Text(orderLabel(order)) }, onClick = {
                                ordering = false
                                rules = r.copy(order = order)
                            })
                        }
                    }
                }
                OutlinedTextField(
                    value = r.limit?.toString().orEmpty(),
                    onValueChange = { v -> rules = r.copy(limit = v.filter(Char::isDigit).toIntOrNull()?.takeIf { it > 0 }) },
                    label = { Text(stringResource(R.string.smart_limit)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
                Hint(stringResource(R.string.smart_hint))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ConditionCard(c: Condition, onChange: (Condition) -> Unit, onRemove: () -> Unit) {
    val context = LocalContext.current
    OutlinedCard(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(fieldLabel(c.field), fontSize = AttaccaType.body, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.smart_not), fontSize = AttaccaType.secondary, color = LocalAttaccaColors.current.textGrayed)
                Switch(checked = c.negated, onCheckedChange = { onChange(c.copy(negated = it)) }, modifier = Modifier.padding(horizontal = 8.dp))
                IconButton(onClick = onRemove) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.smart_remove)) }
            }
            when (c.field.kind) {
                Kind.TEXT -> {
                    SegmentedChoice(
                        listOf(
                            TextOp.CONTAINS to stringResource(R.string.smart_contains),
                            TextOp.IS to stringResource(R.string.smart_is),
                            TextOp.STARTS to stringResource(R.string.smart_starts),
                        ),
                        c.textOp,
                    ) { onChange(c.copy(textOp = it)) }
                    OutlinedTextField(value = c.text, onValueChange = { onChange(c.copy(text = it)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                Kind.RANGE -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    fun n(v: Double?) = v?.let { if (it == Math.floor(it)) it.toLong().toString() else it.toString() }.orEmpty()
                    OutlinedTextField(
                        value = n(c.min),
                        onValueChange = { v -> onChange(c.copy(min = v.replace(',', '.').toDoubleOrNull())) },
                        label = { Text(stringResource(R.string.smart_from)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = n(c.max),
                        onValueChange = { v -> onChange(c.copy(max = v.replace(',', '.').toDoubleOrNull())) },
                        label = { Text(stringResource(R.string.smart_to)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                }
                Kind.SET -> for (mood in Mood.entries) {
                    val key = mood.name.lowercase()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = key in c.values, onCheckedChange = { on -> onChange(c.copy(values = if (on) c.values + key else c.values - key)) })
                        Text(context.getString(com.sadurni.attacca.ui.chain.moodRes(mood)), fontSize = AttaccaType.body)
                    }
                }
                Kind.CHOICE -> SegmentedChoice(
                    listOf("voice" to stringResource(R.string.filter_voice), "instrumental" to stringResource(R.string.filter_instrumental)),
                    c.values.firstOrNull() ?: "voice",
                ) { onChange(c.copy(values = setOf(it))) }
                Kind.DAYS -> DaysField(c.number, R.string.smart_days) { onChange(c.copy(number = it)) }
                Kind.PLAYED -> {
                    SegmentedChoice(
                        listOf(
                            PlayedOp.NEVER to stringResource(R.string.smart_played_never),
                            PlayedOp.WITHIN to stringResource(R.string.smart_played_within),
                            PlayedOp.NOT_WITHIN to stringResource(R.string.smart_played_not_within),
                            PlayedOp.AT_LEAST to stringResource(R.string.smart_played_at_least),
                        ),
                        c.playedOp,
                    ) { onChange(c.copy(playedOp = it, number = if (c.number <= 0) 30 else c.number)) }
                    when (c.playedOp) {
                        PlayedOp.NEVER -> Unit
                        PlayedOp.AT_LEAST -> DaysField(c.number, R.string.smart_times) { onChange(c.copy(number = it)) }
                        else -> DaysField(c.number, R.string.smart_days) { onChange(c.copy(number = it)) }
                    }
                }
                Kind.COUNT -> DaysField(c.number, R.string.smart_times) { onChange(c.copy(number = it)) }
                Kind.FLAG -> Hint(stringResource(R.string.smart_included_hint))
            }
        }
    }
}

@Composable
private fun DaysField(value: Int, label: Int, onChange: (Int) -> Unit) {
    OutlinedTextField(
        value = if (value > 0) value.toString() else "",
        onValueChange = { v -> onChange(v.filter(Char::isDigit).toIntOrNull() ?: 0) },
        label = { Text(stringResource(label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}
