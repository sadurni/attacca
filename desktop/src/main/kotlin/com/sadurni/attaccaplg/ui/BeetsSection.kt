package com.sadurni.attaccaplg.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.sadurni.attaccaplg.beets.Beets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** beets alongside Attacca Desktop: import albums with it, run its commands, give it Attacca's measures. */
@Composable
fun BeetsSection(state: AppState) {
    Section("beets") {
        var command by remember { mutableStateOf(state.prefs["beets_command"] ?: "") }
        var output by remember { mutableStateOf("") }
        var args by remember { mutableStateOf("stats") }
        LaunchedEffect(Unit) { if (!state.beetsLooked) state.findBeets() }
        Hint(tr(
            "beets organises a music collection (tags from MusicBrainz, renaming, ReplayGain…). Attacca Desktop can import albums with it, run its commands and write what it measures into beets' library.",
            "beets organiza una colección de música (etiquetas de MusicBrainz, renombrado, ReplayGain…). Attacca Desktop puede incorporar álbumes con él, ejecutar sus órdenes y escribir en su biblioteca lo que mide.",
        ))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(command, { command = it }, Modifier.width(560.dp), singleLine = true,
                label = { Text(tr("The beet command (empty: the one on the PATH)", "La orden beet (vacío: la del PATH)")) })
            OutlinedButton(onClick = {
                state.prefs["beets_command"] = command
                state.findBeets()
            }) { Text(tr("Look for it", "Buscarla")) }
        }
        val s = state.beets
        if (s == null) {
            Text(if (state.beetsLooked) tr("beets not found.", "beets no encontrado.") else tr("Looking…", "Buscando…"))
            return@Section
        }
        Text(tr("beets: ", "beets: ") + s.command + "  ·  " + tr("library ", "biblioteca ") + (s.library?.path ?: "?") +
            "  ·  " + tr("music ", "música ") + (s.directory?.path ?: "?"))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = {
                Dialogs.folder(tr("Albums to import with beets", "Álbumes para incorporar con beets"), state.prefs["beets_import_dir"])?.let { folder ->
                    state.prefs["beets_import_dir"] = folder.path
                    if (!Beets.importInTerminal(s.command, listOf(folder))) state.message = tr("No terminal to run beets in.", "No hay terminal en la que ejecutar beets.")
                    else state.message = tr("beets is importing in its window; scan the music folder when it is done.", "beets está incorporando en su ventana; escanea la carpeta de música cuando acabe.")
                }
            }) { Text(tr("Import albums with beets…", "Incorporar álbumes con beets…")) }
            s.directory?.takeIf { it.isDirectory }?.let { dir ->
                OutlinedButton(enabled = state.busy == null, onClick = { state.scan(dir) }) { Text(tr("Scan beets' music folder", "Escanear la carpeta de música de beets")) }
            }
            s.library?.takeIf { it.isFile }?.let { db ->
                OutlinedButton(enabled = state.busy == null && state.library != null, onClick = {
                    val library = state.library ?: return@OutlinedButton
                    state.run(tr("Writing to beets", "Escribiendo en beets")) {
                        val n = withContext(Dispatchers.IO) { Beets.writeAttributes(db, library) }
                        state.message = tr(
                            "$n tracks of beets got Attacca's mood, family, BPM, energy and positivity (attacca_…).",
                            "$n pistas de beets tienen ya el estado de ánimo, la familia, los BPM, la energía y la positividad de Attacca (attacca_…).",
                        )
                    }
                }) { Text(tr("Give beets Attacca's measures", "Pasar a beets lo que mide Attacca")) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(args, { args = it }, Modifier.width(560.dp), singleLine = true,
                label = { Text(tr("A beets command (e.g. update, replaygain, ls attacca_mood:calm)", "Una orden de beets (p. ej. update, replaygain, ls attacca_mood:calm)")) })
            OutlinedButton(enabled = state.busy == null, onClick = {
                state.run("beet $args") {
                    output = withContext(Dispatchers.IO) { Beets.run(s.command, args.split(' ').filter { it.isNotBlank() }) } ?: tr("It did not run.", "No se ejecutó.")
                }
            }) { Text(tr("Run", "Ejecutar")) }
        }
        if (output.isNotBlank()) {
            Text(output.take(20_000), Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                style = TextStyle(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Hint(tr(
            "Queries by number need the types in beets' config, e.g. types: {attacca_bpm: int, attacca_energy: int, attacca_positivity: int} (types plugin). Close beets before writing to its library.",
            "Las búsquedas por número necesitan los tipos en la configuración de beets, p. ej. types: {attacca_bpm: int, attacca_energy: int, attacca_positivity: int} (plugin types). Cierra beets antes de escribir en su biblioteca.",
        ))
    }
}
