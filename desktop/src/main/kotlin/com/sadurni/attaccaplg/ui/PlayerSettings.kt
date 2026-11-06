package com.sadurni.attaccaplg.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sadurni.attacca.dsp.CrossfadeStyle
import com.sadurni.attaccaplg.audio.AudioOut
import kotlin.math.roundToInt

/** The player's settings: output, crossfade, silences and loudness. */
@Composable
fun PlaybackSection(state: AppState) {
    val p = state.player
    val s = p.settings
    Section(tr("Playback", "Reproducción")) {
        val devices = remember { listOf<String?>(null) + AudioOut.devices() }
        Choice(
            tr("Output", "Salida"), p.device, devices,
            { it ?: tr("The system's", "La del sistema") },
            p::changeDevice,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Crossfade", "Fundido") + ": " + if (s.crossfadeMs == 0) tr("off", "no") else "${s.crossfadeMs / 1000} s", Modifier.width(180.dp))
            Slider(
                s.crossfadeMs / 1000f, { v -> p.update { it.copy(crossfadeMs = v.roundToInt() * 1000) } },
                Modifier.width(280.dp), valueRange = 0f..20f, steps = 19,
            )
        }
        Choice(
            tr("How tracks blend", "Cómo se funden"), s.crossfadeStyle,
            listOf(CrossfadeStyle.AUTOMATIC, CrossfadeStyle.CROSSFADE, CrossfadeStyle.MIX),
            {
                when (it) {
                    CrossfadeStyle.AUTOMATIC -> tr("Automatic (by how each track ends and starts)", "Automático (según cómo acaba y empieza cada tema)")
                    CrossfadeStyle.MIX -> tr("Mix (the ending track plays out whole)", "Mezcla (el que acaba suena entero)")
                    else -> tr("Crossfade (both fade)", "Fundido (los dos)")
                }
            },
            { style -> p.update { it.copy(crossfadeStyle = style) } },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(s.trimSilences, { on -> p.update { it.copy(trimSilences = on) } })
            Text("  " + tr("Leave out the silences before and after each track", "Saltar los silencios de antes y después de cada tema"))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(s.levelLoudness, { on -> p.update { it.copy(levelLoudness = on) } })
            Text("  " + tr("Same loudness for every track (from the measured LUFS)", "El mismo volumen para todos los temas (por los LUFS medidos)"))
        }
        var keepInTray by remember { mutableStateOf(state.prefs["keep_in_tray"] == "true") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(keepInTray, { on -> keepInTray = on; state.prefs["keep_in_tray"] = on.toString() })
            Text("  " + tr("Closing the window while music plays keeps it playing in the system tray", "Al cerrar la ventana mientras suena, sigue sonando en la bandeja del sistema"))
        }
        var mediaKeys by remember { mutableStateOf(state.prefs["media_keys"] != "false") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(mediaKeys, { on ->
                mediaKeys = on
                state.prefs["media_keys"] = on.toString()
                if (!on) MediaKeys.stop() else MediaKeys.start { a -> when (a) { "toggle" -> p.toggle(); "next" -> p.next(); "previous" -> p.previous(); "stop" -> if (p.playing) p.toggle() } }
            })
            Text("  " + tr("The keyboard's media keys control the player, also with the window in the background", "Las teclas multimedia del teclado manejan el reproductor, también con la ventana detrás"))
        }
        var perOutput by remember { mutableStateOf(state.prefs["device_lists"] != "false") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(perOutput, { on -> perOutput = on; state.prefs["device_lists"] = on.toString() })
            Text("  " + tr("Each output keeps its own list, where it was left (as each Bluetooth device in Attacca)", "Cada salida guarda su propia lista, por donde se dejó (como cada dispositivo Bluetooth en Attacca)"))
        }
        if (perOutput) {
            var switchOn by remember { mutableStateOf(state.prefs["device_switch"] != "false") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(switchOn, { on -> switchOn = on; state.prefs["device_switch"] = on.toString() })
                Text("  " + tr("When an output with its own list is connected (headphones, a USB or Bluetooth device), play there", "Al conectar una salida con lista propia (auriculares, un aparato USB o Bluetooth), sonar en ella"))
            }
            var autoplay by remember { mutableStateOf(state.prefs["device_list_play"] == "true") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(autoplay, { on -> autoplay = on; state.prefs["device_list_play"] = on.toString() })
                Text("  " + tr("Start playing the output's list as soon as it is loaded", "Empezar a sonar la lista de la salida en cuanto se carga"))
            }
        }
        Hint(tr(
            "Shortcuts in the window: Ctrl+Space play / pause, Ctrl+→ / ← next / previous, Ctrl+↑ / ↓ volume. The mini player is in the player bar and the tray icon's menu.",
            "Atajos en la ventana: Ctrl+Espacio reproducir / pausa, Ctrl+→ / ← siguiente / anterior, Ctrl+↑ / ↓ volumen. El minirreproductor está en la barra del reproductor y en el menú del icono de la bandeja.",
        ))
        Hint(tr(
            "Tracks of one album played in order follow on without a blend or a pause. The silences and loudness are those measured by the scan.",
            "Los temas de un álbum en orden siguen sin fundido ni pausa. Los silencios y el volumen son los que mide el escaneo.",
        ))
    }
}
