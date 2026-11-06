package com.sadurni.attaccaplg.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.sadurni.attacca.dsp.GraphicEq
import com.sadurni.attacca.dsp.PeqText
import com.sadurni.attacca.settings.ChannelMode
import com.sadurni.attacca.settings.CrossfeedPreset
import com.sadurni.attacca.settings.DspSettings
import com.sadurni.attacca.settings.BassCurve
import com.sadurni.attacca.settings.EqType
import kotlin.math.roundToInt

/**
 * Attacca's sound processing for the output in use (each output keeps its own, as the phone's
 * device profiles): preamp, equalizer, bass, stereo, crossfeed, compressor and limiter.
 */
@Composable
fun SoundPage(state: AppState) {
    val p = state.player
    val s = p.dsp
    fun set(change: (DspSettings) -> DspSettings) = p.updateDsp(change)
    PageFrame(tr("Sound", "Sonido")) {
        ScrollColumn {
            Hint(tr(
                "For the output ${p.device ?: "of the system"} (Settings → Playback): each output keeps its own sound.",
                "Para la salida ${p.device ?: "del sistema"} (Ajustes → Reproducción): cada salida guarda su propio sonido.",
            ))
            Section(tr("Level", "Nivel")) {
                Toggle(tr("Preamp", "Preamplificador"), s.preampEnabled) { on -> set { it.copy(preampEnabled = on) } }
                if (s.preampEnabled) Value(tr("Preamp", "Preamplificador"), s.preampDb, -12f..12f, "dB") { v -> set { it.copy(preampDb = v) } }
            }
            Section(tr("Equalizer", "Ecualizador")) {
                Toggle(tr("Equalizer", "Ecualizador"), s.eqEnabled) { on -> set { it.copy(eqEnabled = on) } }
                Choice(
                    tr("Kind", "Tipo"), s.eqType, EqType.entries,
                    { if (it == EqType.GRAPHIC) tr("Graphic, 10 bands", "Gráfico, 10 bandas") else tr("Parametric (correction files)", "Paramétrico (archivos de corrección)") },
                    { type -> set { it.copy(eqType = type) } },
                )
                if (s.eqType == EqType.GRAPHIC) {
                    GraphicEq.BAND_FREQUENCIES.forEachIndexed { i, hz ->
                        val label = if (hz >= 1000) "${hz / 1000} kHz" else "$hz Hz"
                        Value(label, s.eqGains.getOrElse(i) { 0f }, -12f..12f, "dB") { v ->
                            set { it.copy(eqGains = it.eqGains.toMutableList().also { g -> g[i] = v }) }
                        }
                    }
                    OutlinedButton(onClick = { set { it.copy(eqGains = List(DspSettings.EQ_BANDS) { 0f }) } }) { Text(tr("All bands to 0", "Todas las bandas a 0")) }
                } else {
                    var text by remember(s.peqBands, s.peqPreampDb) { mutableStateOf(PeqText.format(s.peqPreampDb, s.peqBands)) }
                    OutlinedTextField(
                        text, { text = it }, Modifier.fillMaxWidth().height(220.dp),
                        label = { Text(tr("Bands (preamp and filters, as correction files give them)", "Bandas (preamplificador y filtros, como los dan los archivos de corrección)")) },
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace),
                    )
                    OutlinedButton(onClick = {
                        val parsed = PeqText.parse(text)
                        set { it.copy(peqBands = parsed.bands, peqPreampDb = parsed.preampDb ?: it.peqPreampDb) }
                    }) { Text(tr("Use these bands", "Usar estas bandas")) }
                }
                Toggle(tr("Lower the level by the highest boost (no clipping)", "Bajar el nivel lo que suba la banda más alta (sin saturar)"), s.eqAutoPrecut) { on -> set { it.copy(eqAutoPrecut = on) } }
            }
            Section(tr("Bass", "Graves")) {
                Toggle(tr("Bass boost", "Refuerzo de graves"), s.bassEnabled) { on -> set { it.copy(bassEnabled = on) } }
                if (s.bassEnabled) {
                    Choice(
                        tr("Curve", "Curva"), s.bassCurve, BassCurve.entries,
                        {
                            if (it == BassCurve.GENTLE) tr("Gentle (from the low mids down, steady)", "Suave (desde los medios-graves, constante)")
                            else tr("Deep (deep bass only, backs off when loud)", "Profunda (solo graves profundos, cede cuando suben)")
                        },
                        { curve -> set { it.copy(bassCurve = curve) } },
                    )
                    if (s.bassCurve == BassCurve.GENTLE) {
                        Value(tr("Half the boost at", "Mitad del refuerzo en"), s.bassGentleFreqHz.toFloat(), 60f..400f, "Hz", whole = true) { v -> set { it.copy(bassGentleFreqHz = v.roundToInt()) } }
                        Value(tr("Boost", "Refuerzo"), s.bassMaxGainDb, 0f..15f, "dB") { v -> set { it.copy(bassMaxGainDb = v) } }
                    } else {
                        Value(tr("Up to", "Hasta"), s.bassFreqHz.toFloat(), 40f..200f, "Hz", whole = true) { v -> set { it.copy(bassFreqHz = v.roundToInt()) } }
                        Value(tr("Most boost", "Refuerzo máximo"), s.bassMaxGainDb, 0f..15f, "dB") { v -> set { it.copy(bassMaxGainDb = v) } }
                        Value(tr("Bass level it may reach", "Nivel de graves que puede alcanzar"), s.bassCeilingDb, -24f..0f, "dB") { v -> set { it.copy(bassCeilingDb = v) } }
                    }
                }
                Toggle(tr("Virtual bass (harmonics for small speakers)", "Graves virtuales (armónicos para altavoces pequeños)"), s.virtualBassEnabled) { on -> set { it.copy(virtualBassEnabled = on) } }
                if (s.virtualBassEnabled) {
                    Value(tr("Amount", "Cantidad"), s.virtualBassAmount.toFloat(), 0f..100f, "%", whole = true) { v -> set { it.copy(virtualBassAmount = v.roundToInt()) } }
                    Value(tr("Below", "Por debajo de"), s.virtualBassCutoffHz.toFloat(), 40f..200f, "Hz", whole = true) { v -> set { it.copy(virtualBassCutoffHz = v.roundToInt()) } }
                }
            }
            Section(tr("Stereo", "Estéreo")) {
                Toggle(tr("Stereo width", "Anchura estéreo"), s.widthEnabled) { on -> set { it.copy(widthEnabled = on) } }
                if (s.widthEnabled) {
                    Value(tr("Width", "Anchura"), s.widthPercent.toFloat(), 0f..200f, "%", whole = true) { v -> set { it.copy(widthPercent = v.roundToInt()) } }
                    Toggle(tr("Keep the bass centred", "Mantener los graves en el centro"), s.widthBassSafe) { on -> set { it.copy(widthBassSafe = on) } }
                }
                Value(tr("Balance", "Balance"), s.balance * 100f, -100f..100f, "", whole = true) { v -> set { it.copy(balance = v / 100f) } }
                Choice(
                    tr("Channels", "Canales"), s.channelMode, ChannelMode.entries,
                    {
                        when (it) {
                            ChannelMode.STEREO -> tr("Stereo", "Estéreo")
                            ChannelMode.MONO -> "Mono"
                            ChannelMode.LEFT -> tr("Left on both", "Izquierdo en los dos")
                            ChannelMode.RIGHT -> tr("Right on both", "Derecho en los dos")
                            ChannelMode.SWAP -> tr("Swapped", "Intercambiados")
                            ChannelMode.KARAOKE -> tr("Karaoke (centre out)", "Karaoke (sin el centro)")
                        }
                    },
                    { mode -> set { it.copy(channelMode = mode) } },
                )
            }
            Section(tr("Crossfeed (headphones)", "Crossfeed (auriculares)")) {
                Toggle(tr("Crossfeed", "Crossfeed"), s.crossfeedEnabled) { on -> set { it.copy(crossfeedEnabled = on) } }
                if (s.crossfeedEnabled) {
                    Choice(
                        tr("Kind", "Tipo"), s.crossfeedPreset, CrossfeedPreset.entries,
                        {
                            when (it) {
                                CrossfeedPreset.DEFAULT -> tr("Strong: Bauer (700 Hz, 4.5 dB)", "Fuerte: Bauer (700 Hz, 4,5 dB)")
                                CrossfeedPreset.CHU_MOY -> tr("Medium: Chu Moy (700 Hz, 6 dB)", "Media: Chu Moy (700 Hz, 6 dB)")
                                CrossfeedPreset.JAN_MEIER -> tr("Light: Jan Meier (650 Hz, 9.5 dB)", "Suave: Jan Meier (650 Hz, 9,5 dB)")
                                CrossfeedPreset.SPEAKERS -> tr("Virtual speakers (out of the head)", "Altavoces virtuales (fuera de la cabeza)")
                                CrossfeedPreset.CUSTOM -> tr("Custom", "Personalizado")
                            }
                        },
                        { preset -> set { it.copy(crossfeedPreset = preset) } },
                    )
                    if (s.crossfeedPreset == CrossfeedPreset.CUSTOM) {
                        Value(tr("Cutoff", "Corte"), s.crossfeedCustomCutoffHz.toFloat(), 300f..2000f, "Hz", whole = true) { v -> set { it.copy(crossfeedCustomCutoffHz = v.roundToInt()) } }
                        Value(tr("Feed level", "Nivel de mezcla"), s.crossfeedCustomFeedDb, 1f..15f, "dB") { v -> set { it.copy(crossfeedCustomFeedDb = v) } }
                    }
                }
            }
            Section(tr("Compressor", "Compresor")) {
                Toggle(tr("Compressor", "Compresor"), s.compressorEnabled) { on -> set { it.copy(compressorEnabled = on) } }
                if (s.compressorEnabled) {
                    Value(tr("Threshold", "Umbral"), s.compressorThresholdDb, -48f..0f, "dB") { v -> set { it.copy(compressorThresholdDb = v) } }
                    Value(tr("Ratio", "Proporción"), s.compressorRatio, 1f..20f, ": 1") { v -> set { it.copy(compressorRatio = v) } }
                    Value(tr("Knee", "Rodilla"), s.compressorKneeDb, 0f..24f, "dB") { v -> set { it.copy(compressorKneeDb = v) } }
                    Value(tr("Attack", "Ataque"), s.compressorAttackMs, 0.1f..100f, "ms") { v -> set { it.copy(compressorAttackMs = v) } }
                    Value(tr("Release", "Relajación"), s.compressorReleaseMs, 10f..1000f, "ms", whole = true) { v -> set { it.copy(compressorReleaseMs = v) } }
                    Toggle(tr("Makeup gain worked out", "Ganancia de compensación calculada"), s.compressorAutoMakeup) { on -> set { it.copy(compressorAutoMakeup = on) } }
                    if (!s.compressorAutoMakeup) Value(tr("Makeup gain", "Compensación"), s.compressorMakeupDb, 0f..24f, "dB") { v -> set { it.copy(compressorMakeupDb = v) } }
                }
            }
            Section(tr("Limiter", "Limitador")) {
                Toggle(tr("Limiter (never clips)", "Limitador (nunca satura)"), s.limiterEnabled) { on -> set { it.copy(limiterEnabled = on) } }
                if (s.limiterEnabled) Value(tr("Ceiling", "Techo"), s.limiterCeilingDb, -6f..0f, "dB") { v -> set { it.copy(limiterCeilingDb = v) } }
                OutlinedButton(onClick = { set { DspSettings() } }) { Text(tr("Everything as it comes", "Todo como viene")) }
            }
        }
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(on, onChange)
        Text("  $label")
    }
}

/** A labelled slider with its value. */
@Composable
private fun Value(label: String, value: Float, range: ClosedFloatingPointRange<Float>, unit: String, whole: Boolean = false, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(260.dp))
        Slider(value.coerceIn(range), { onChange(if (whole) it.roundToInt().toFloat() else (it * 10).roundToInt() / 10f) }, Modifier.width(320.dp), valueRange = range)
        Text("  " + (if (whole) value.roundToInt().toString() else "%.1f".format(value)) + " " + unit)
    }
}
