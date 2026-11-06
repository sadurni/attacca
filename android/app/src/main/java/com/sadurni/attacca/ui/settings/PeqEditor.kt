package com.sadurni.attacca.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import com.sadurni.attacca.ui.common.Icon
import com.sadurni.attacca.ui.common.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.sadurni.attacca.R
import com.sadurni.attacca.dsp.ParametricEq
import com.sadurni.attacca.dsp.PeqBand
import com.sadurni.attacca.dsp.PeqText
import com.sadurni.attacca.settings.DspSettings
import com.sadurni.attacca.ui.common.CreateDocumentAt
import com.sadurni.attacca.ui.common.OpenDocumentAt
import com.sadurni.attacca.ui.common.PickerMemory
import com.sadurni.attacca.ui.theme.AttaccaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/** Frequency on a log scale: 0 … 1 for 20 Hz … 20 kHz. */
private fun freqOf(t: Float): Float = 20f * 1000f.pow(t)
private fun tOfFreq(f: Float): Float = (ln(f.coerceIn(20f, 20000f) / 20f) / ln(1000f))
private fun qOf(t: Float): Float = 0.1f * 100f.pow(t)
private fun tOfQ(q: Float): Float = (ln(q.coerceIn(0.1f, 10f) / 0.1f) / ln(100f))

private fun freqText(f: Float): String = if (f >= 1000f) "%.2f kHz".format(f / 1000f) else "${f.roundToInt()} Hz"

/**
 * The parametric EQ of a profile: its preamp and up to ten bands, each peaking or shelving with
 * its frequency, gain and Q. A headphone correction can be read from a file or pasted as text, and
 * the bands saved as such a text.
 */
@Composable
fun ParametricEqSection(dsp: DspSettings, update: ((DspSettings) -> DspSettings) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pasting by remember { mutableStateOf(false) }

    fun apply(text: String) {
        val parsed = PeqText.parse(text)
        if (parsed.bands.isEmpty()) {
            Toast.makeText(context, R.string.peq_import_none, Toast.LENGTH_LONG).show()
            return
        }
        update { it.copy(peqBands = parsed.bands, peqPreampDb = parsed.preampDb ?: it.peqPreampDb) }
        Toast.makeText(context, context.getString(R.string.peq_imported, parsed.bands.size), Toast.LENGTH_SHORT).show()
    }

    val open = rememberLauncherForActivityResult(OpenDocumentAt()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        PickerMemory.put(context, PickerMemory.PEQ_FILE, uri)
        scope.launch {
            val text = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() }.getOrNull() }
            if (text != null) apply(text)
        }
    }
    val save = rememberLauncherForActivityResult(CreateDocumentAt("text/plain")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        PickerMemory.put(context, PickerMemory.PEQ_FILE, uri)
        val text = PeqText.format(dsp.peqPreampDb, dsp.peqBands)
        scope.launch(Dispatchers.IO) { runCatching { context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } } }
    }

    ValueSlider(stringResource(R.string.peq_preamp), dsp.peqPreampDb, -20f..6f, 0.1f, { "%.1f dB".format(it) },
        { v -> update { it.copy(peqPreampDb = v) } })
    dsp.peqBands.forEachIndexed { i, band ->
        BandCard(
            band,
            onChange = { new -> update { s -> s.copy(peqBands = s.peqBands.toMutableList().also { it[i] = new }) } },
            onRemove = { update { s -> s.copy(peqBands = s.peqBands.filterIndexed { j, _ -> j != i }) } },
        )
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (dsp.peqBands.size < ParametricEq.MAX_BANDS) {
            OutlinedButton(onClick = { update { it.copy(peqBands = it.peqBands + PeqBand()) } }) {
                Icon(Icons.Rounded.Add, null)
                Text(stringResource(R.string.peq_add_band), modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { open.launch(arrayOf("text/plain", "application/octet-stream", "*/*") to PickerMemory.get(context, PickerMemory.PEQ_FILE)) }) {
            Text(stringResource(R.string.peq_import))
        }
        OutlinedButton(onClick = { pasting = true }) { Text(stringResource(R.string.peq_paste)) }
        if (dsp.peqBands.isNotEmpty()) {
            OutlinedButton(onClick = { save.launch("Parametric EQ.txt" to PickerMemory.get(context, PickerMemory.PEQ_FILE)) }) {
                Text(stringResource(R.string.peq_export))
            }
        }
    }
    Hint(stringResource(R.string.peq_hint))

    if (pasting) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pasting = false },
            title = { Text(stringResource(R.string.peq_paste)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("Preamp: -6.0 dB\nFilter 1: ON PK Fc 100 Hz Gain -3.0 dB Q 1.00", fontFamily = FontFamily.Monospace, fontSize = AttaccaType.caption) },
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = AttaccaType.caption),
                    modifier = Modifier.fillMaxWidth().height(260.dp),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pasting = false
                    apply(text)
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { pasting = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun BandCard(band: PeqBand, onChange: (PeqBand) -> Unit, onRemove: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(Modifier.padding(vertical = 4.dp)) {
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = band.enabled, onCheckedChange = { onChange(band.copy(enabled = it)) })
                Text(
                    "${freqText(band.freqHz)}  ${"%+.1f dB".format(band.gainDb)}  Q ${"%.2f".format(band.q)}",
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onRemove) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.peq_remove_band)) }
            }
            SegmentedChoice(
                listOf(
                    PeqBand.Type.LOW_SHELF to stringResource(R.string.peq_low_shelf),
                    PeqBand.Type.PEAK to stringResource(R.string.peq_peak),
                    PeqBand.Type.HIGH_SHELF to stringResource(R.string.peq_high_shelf),
                ),
                band.type,
            ) { onChange(band.copy(type = it)) }
            ValueSlider(stringResource(R.string.peq_freq), tOfFreq(band.freqHz), 0f..1f, 0.002f, { freqText(freqOf(it)) },
                { v -> onChange(band.copy(freqHz = freqOf(v))) })
            ValueSlider(stringResource(R.string.peq_gain), band.gainDb, -15f..15f, 0.1f, { "%+.1f dB".format(it) },
                { v -> onChange(band.copy(gainDb = v)) })
            ValueSlider(stringResource(R.string.peq_q), tOfQ(band.q), 0f..1f, 0.005f, { "%.2f".format(qOf(it)) },
                { v -> onChange(band.copy(q = qOf(v))) })
        }
    }
}
