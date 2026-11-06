package com.sadurni.attaccaplg.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sadurni.attacca.library.LibraryExchange
import com.sadurni.attaccaplg.audio.Decoder
import com.sadurni.attaccaplg.exchange.Exchange
import com.sadurni.attaccaplg.library.Scanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun ExchangePage(state: AppState) {
    PageFrame(tr("Library shared with Attacca", "Biblioteca compartida con Attacca")) {
        ScrollColumn {
            Hint(
                tr(
                    "Attacca (on the phone) and Attacca Desktop measure the music the same way, so a collection needs measuring only once: export the library where it was measured and import it on the other side (Attacca: Settings → Backup). Tracks are matched by their path within the music folder and their size; the rest is measured by the next scan.",
                    "Attacca (en el móvil) y Attacca Desktop miden la música igual, así que una colección solo hace falta medirla una vez: exporta la biblioteca donde se midió e impórtala en el otro lado (Attacca: Ajustes → Copia de seguridad). Las pistas se emparejan por su ruta dentro de la carpeta de música y su tamaño; el resto lo mide el siguiente escaneo.",
                ),
            )
            Section(tr("Import from Attacca", "Importar de Attacca")) {
                Hint(
                    tr(
                        "Takes the tags, the edges that chain lists, the sound features, loudness and silence analysis and the inclusion list, for the tracks of this PC's music folder.",
                        "Toma las etiquetas, los bordes que encadenan las listas, los rasgos del sonido, el análisis de sonoridad y silencio y la lista de inclusión, para las pistas de la carpeta de música de este PC.",
                    ),
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(state.musicRoot ?: tr("Choose the music folder first (Library page).", "Elige antes la carpeta de música (página Biblioteca)."), Modifier.weight(1f))
                    Button(enabled = state.busy == null && state.musicRoot != null, onClick = {
                        val file = Dialogs.open(tr("Library file", "Fichero de biblioteca"), state.prefs["exchange_dir"], "*.${LibraryExchange.EXTENSION}") ?: return@Button
                        state.prefs["exchange_dir"] = file.parent
                        val root = File(state.musicRoot!!)
                        state.run(tr("Importing", "Importando")) {
                            val r = withContext(Dispatchers.IO) { Exchange.import(file, root, state.db) }
                            state.reload()
                            state.message = tr(
                                "${r.total} tracks in the file: ${r.matched} taken (${r.edges} with edges, ${r.features} with features, ${r.analysis} with the silence analysis), ${r.differentSize} with another size here, ${r.total - r.matched - r.differentSize} not in this folder; ${r.inclusion} in the inclusion list.",
                                "${r.total} pistas en el fichero: ${r.matched} tomadas (${r.edges} con bordes, ${r.features} con rasgos, ${r.analysis} con el análisis de silencio), ${r.differentSize} con otro tamaño aquí, ${r.total - r.matched - r.differentSize} que no están en esta carpeta; ${r.inclusion} en la lista de inclusión.",
                            )
                        }
                    }) { Text(tr("Import…", "Importar…")) }
                }
            }
            WithoutMusicSection(state)
            PlayerSection(state)
            Section(tr("Export for Attacca", "Exportar para Attacca")) {
                Hint(
                    tr(
                        "Writes everything measured here to a .attlib file; copy it to the phone and import it in Attacca.",
                        "Escribe todo lo medido aquí en un fichero .attlib; cópialo al móvil e impórtalo en Attacca.",
                    ),
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(state.library?.let { tr("${it.all.size} tracks", "${it.all.size} pistas") } ?: tr("The library is empty.", "La biblioteca está vacía."), Modifier.weight(1f))
                    Button(enabled = state.busy == null && state.library != null, onClick = {
                        val name = "attaccaplg-library-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "." + LibraryExchange.EXTENSION
                        val file = Dialogs.save(tr("Library file", "Fichero de biblioteca"), state.prefs["exchange_dir"], name) ?: return@Button
                        val target = if (file.extension == LibraryExchange.EXTENSION) file else File(file.path + "." + LibraryExchange.EXTENSION)
                        state.prefs["exchange_dir"] = target.parent
                        state.run(tr("Exporting", "Exportando")) {
                            val n = withContext(Dispatchers.IO) { Exchange.export(state.db, target) }
                            state.message = tr("$n tracks written to ${target.path}", "$n pistas escritas en ${target.path}")
                        }
                    }) { Text(tr("Export…", "Exportar…")) }
                }
            }
        }
    }
}

/**
 * An .attlib taken as it is, without its music on this PC: lists, reordering and the exports work.
 * It replaces the library (asked first when there is one).
 */
@Composable
private fun WithoutMusicSection(state: AppState) {
    var pending by remember { mutableStateOf<File?>(null) }
    fun take(file: File) = state.run(tr("Importing", "Importando")) {
        val r = withContext(Dispatchers.IO) { Exchange.import(file, null, state.db) }
        state.reload()
        state.message = tr(
            "${r.matched} tracks taken without their music (${r.edges} with edges, ${r.features} with features, ${r.analysis} with silences); ${r.inclusion} in the inclusion list.",
            "${r.matched} pistas tomadas sin su música (${r.edges} con bordes, ${r.features} con rasgos, ${r.analysis} con silencios); ${r.inclusion} en la lista de inclusión.",
        )
    }
    Section(tr("Import without the music", "Importar sin la música")) {
        Hint(
            tr(
                "For a library measured elsewhere whose music is not on this PC: lists, reordering and the exports for players and for Attacca work all the same (lists without a player path hold the paths within the music folder). Only scanning needs the music: choosing the music folder later keeps what was measured for the tracks with the same path and size.",
                "Para una biblioteca medida en otro sitio cuya música no está en este PC: las listas, reordenar y las exportaciones para reproductores y para Attacca funcionan igual (las listas sin ruta del reproductor llevan las rutas dentro de la carpeta de música). Solo escanear necesita la música: elegir después la carpeta de música conserva lo medido de las pistas con la misma ruta y tamaño.",
            ),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                if (state.library != null && state.musicRoot == null) tr("This library is without its music.", "Esta biblioteca está sin su música.")
                else tr("It replaces the current library.", "Sustituye a la biblioteca actual."),
                Modifier.weight(1f),
            )
            Button(enabled = state.busy == null, onClick = {
                val file = Dialogs.open(tr("Library file", "Fichero de biblioteca"), state.prefs["exchange_dir"], "*.${LibraryExchange.EXTENSION}") ?: return@Button
                state.prefs["exchange_dir"] = file.parent
                if (state.library != null) pending = file else take(file)
            }) { Text(tr("Import without the music…", "Importar sin la música…")) }
        }
    }
    pending?.let { file ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(tr("Replace the library?", "¿Sustituir la biblioteca?")) },
            text = {
                Text(
                    tr(
                        "The current library (${state.library?.all?.size ?: 0} tracks) is replaced by the one in ${file.name}, without the music folder.",
                        "La biblioteca actual (${state.library?.all?.size ?: 0} pistas) se sustituye por la de ${file.name}, sin carpeta de música.",
                    ),
                )
            },
            confirmButton = { TextButton(onClick = { pending = null; take(file) }) { Text(tr("Replace", "Sustituir")) } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(tr("Cancel", "Cancelar")) } },
        )
    }
}

/** A place the library for players was exported to: the file and the music folder as the player sees it. */
private class PlayerExport(val file: File, val base: String)

private const val MAX_PLAYER_EXPORTS = 8

private fun playerExports(state: AppState): List<PlayerExport> = runCatching {
    val array = org.json.JSONArray(state.prefs["player_exports"] ?: "[]")
    (0 until array.length()).map { array.getJSONObject(it) }.map { PlayerExport(File(it.getString("file")), it.getString("base")) }
}.getOrDefault(emptyList())

private fun savePlayerExports(state: AppState, list: List<PlayerExport>) {
    state.prefs["player_exports"] = org.json.JSONArray(list.map { org.json.JSONObject().put("file", it.file.path).put("base", it.base) }).toString()
}

/** The library for the Rockbox plugin and the web generator (.bpcr2). */
@Composable
private fun PlayerSection(state: AppState) {
    var base by remember { mutableStateOf(state.prefs["player_base"] ?: "/<microSD1>/Music") }
    // The places exported to before (players, cards, a folder for the web), newest first.
    var exports by remember { mutableStateOf(playerExports(state)) }
    fun export(targets: List<PlayerExport>) {
        val library = state.library ?: return
        // The newest go first among those kept.
        exports = (targets + exports.filter { e -> targets.none { it.file.path.equals(e.file.path, ignoreCase = true) } }).take(MAX_PLAYER_EXPORTS)
        savePlayerExports(state, exports)
        state.run(tr("Exporting", "Exportando")) {
            val done = withContext(Dispatchers.IO) {
                val inclusion = state.inclusionList?.let { com.sadurni.attaccaplg.generate.Inclusions.inclusion(File(it), state.inclusionMode, library) }
                targets.map { t ->
                    t to runCatching {
                        t.file.parentFile?.mkdirs()
                        com.sadurni.attaccaplg.export.PlayerDb.write(library, inclusion, t.base, state.lists.spacing, state.edgeSeconds, t.file)
                    }
                }
            }
            state.message = done.joinToString("\n") { (t, n) ->
                n.fold(
                    { tr("$it tracks written to ${t.file.path}", "$it pistas escritas en ${t.file.path}") },
                    { tr("Could not write ${t.file.path} (is the player connected?)", "No se pudo escribir ${t.file.path} (¿está conectado el reproductor?)") },
                )
            }
        }
    }
    Section(tr("For Rockbox and the web generator", "Para Rockbox y el generador web")) {
        Hint(
            tr(
                "The Rockbox plugin and the web generator make chained lists on their own from this file (attaccaplg.bpcr2), with the same rules. Copy it to the root of the player, or open it in the web generator. The inclusion list goes in it as it is now.",
                "El plugin de Rockbox y el generador web hacen listas encadenadas por su cuenta a partir de este fichero (attaccaplg.bpcr2), con las mismas reglas. Cópialo a la raíz del reproductor, o ábrelo en el generador web. La lista de inclusión va dentro tal como está ahora.",
            ),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            androidx.compose.material3.OutlinedTextField(
                base, { base = it; state.prefs["player_base"] = it }, Modifier.weight(1f), singleLine = true,
                label = { Text(tr("Music folder on the player", "Carpeta de música en el reproductor")) },
            )
            Button(enabled = state.busy == null && state.library != null && base.isNotBlank(), onClick = {
                val file = Dialogs.save(tr("Library for players", "Biblioteca para reproductores"), state.prefs["player_dir"], "attaccaplg.bpcr2") ?: return@Button
                val target = if (file.extension == "bpcr2") file else File(file.path + ".bpcr2")
                state.prefs["player_dir"] = target.parent
                export(listOf(PlayerExport(target, base)))
            }) { Text(tr("Export…", "Exportar…")) }
        }
        Hint(tr("E.g. /<microSD1>/Music on a player, /<HDD0>/Music on its internal storage.", "P. ej. /<microSD1>/Music en un reproductor, /<HDD0>/Music en su memoria interna."))
        if (exports.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(tr("Exported before", "Exportado antes"), fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (exports.size > 1) OutlinedButton(
                    enabled = state.busy == null && state.library != null,
                    onClick = { export(exports.filter { it.file.parentFile?.isDirectory == true }) },
                ) { Text(tr("Export to all connected", "Exportar a todos los conectados")) }
            }
            for (e in exports) {
                val connected = e.file.parentFile?.isDirectory == true
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(e.file.path, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Hint(tr("Music on the player: ", "Música en el reproductor: ") + e.base + if (connected) "" else tr("  ·  not connected", "  ·  no conectado"))
                    }
                    TextButton(onClick = { base = e.base; state.prefs["player_base"] = e.base }) { Text(tr("Use its folder", "Usar su carpeta")) }
                    Button(enabled = state.busy == null && state.library != null && connected, onClick = { export(listOf(e)) }) { Text(tr("Export", "Exportar")) }
                    TextButton(onClick = { exports = exports - e; savePlayerExports(state, exports) }) { Text(tr("Forget", "Olvidar")) }
                }
            }
        }
    }
}

@Composable
fun SettingsPage(state: AppState) {
    PageFrame(tr("Settings", "Ajustes")) {
        ScrollColumn {
            Section(tr("Window", "Ventana")) {
                var language by remember { mutableStateOf(state.prefs["language"] ?: "system") }
                Choice(
                    tr("Language", "Idioma"), language, listOf("system") + Lang.CHOICES.keys,
                    { Lang.CHOICES[it] ?: tr("The system's", "El del sistema") },
                    {
                        language = it
                        state.prefs["language"] = it
                        Lang.apply(it)
                    },
                )
                ThemeChoice(state)
            }
            PlaybackSection(state)
            BeetsSection(state)
            EdgesSection(state)
            Section(tr("Measuring", "Medición")) {
                val cores = Runtime.getRuntime().availableProcessors()
                var jobs by remember { mutableStateOf(state.prefs["jobs"]?.toIntOrNull() ?: Scanner.defaultJobs()) }
                Choice(
                    tr("Tracks at a time", "Pistas a la vez"), jobs, (1..cores).toList(), { it.toString() },
                    {
                        jobs = it
                        state.prefs["jobs"] = it.toString()
                    },
                )
                Hint(tr("More is faster but leaves the PC busier ($cores cores here).", "Más es más rápido pero deja el PC más ocupado (aquí hay $cores núcleos)."))
                Text(
                    Decoder.ffmpeg?.let { tr("ffmpeg: $it", "ffmpeg: $it") }
                        ?: tr("ffmpeg not found: AAC, M4A, WMA, Opus… are listed but not measured.", "ffmpeg no encontrado: AAC, M4A, WMA, Opus… se listan pero no se miden."),
                )
            }
            Section(tr("Files", "Ficheros")) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(tr("Library: ", "Biblioteca: ") + state.db.file.absolutePath, Modifier.weight(1f))
                    OutlinedButton(onClick = { Dialogs.show(state.db.file.absoluteFile.parentFile) }) { Text(tr("Show", "Mostrar")) }
                }
                Hint(tr("The command line (attaccaplg) uses the same library.", "La línea de órdenes (attaccaplg) usa la misma biblioteca."))
            }
            Hint("Attacca Desktop " + VERSION)
        }
    }
}

@Composable
private fun EdgesSection(state: AppState) {
    var asked by remember { mutableStateOf<Int?>(null) }
    var value by remember(state.edgeSeconds) { mutableStateOf(state.edgeSeconds.toFloat()) }
    Section(tr("Chaining", "Encadenado")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Edge length", "Longitud de los bordes"), Modifier.width(180.dp))
            Slider(
                value, { value = it }, Modifier.weight(1f), valueRange = 4f..20f, steps = 15,
                // Without the music the edges could not be measured again.
                enabled = state.busy == null && (state.musicRoot != null || state.library == null),
                onValueChangeFinished = { if (value.toInt() != state.edgeSeconds) asked = value.toInt() },
            )
            Text(tr("${value.toInt()} s", "${value.toInt()} s"), Modifier.width(60.dp).padding(start = 12.dp))
        }
        Hint(
            tr(
                "Seconds of each track's start and end that are compared to chain one track into the next (as in Attacca; use the same length on both sides to share the library). Changing it measures every track again.",
                "Segundos del principio y del final de cada pista que se comparan para encadenar una con la siguiente (como en Attacca; usa la misma longitud en los dos lados para compartir la biblioteca). Cambiarla vuelve a medir todas las pistas.",
            ),
        )
    }
    asked?.let { seconds ->
        AlertDialog(
            onDismissRequest = { asked = null; value = state.edgeSeconds.toFloat() },
            title = { Text(tr("Edges of $seconds s?", "¿Bordes de $seconds s?")) },
            text = {
                Text(
                    tr(
                        "Every track will be measured again (the next scan starts now if the music folder is set).",
                        "Se volverán a medir todas las pistas (el siguiente escaneo empieza ahora si la carpeta de música está elegida).",
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    asked = null
                    state.changeEdgeSeconds(seconds)
                    state.musicRoot?.let(::File)?.takeIf { it.isDirectory }?.let {
                        state.page = Page.LIBRARY
                        state.scan(it)
                    }
                }) { Text(tr("Measure again", "Volver a medir")) }
            },
            dismissButton = { TextButton(onClick = { asked = null; value = state.edgeSeconds.toFloat() }) { Text(tr("Cancel", "Cancelar")) } },
        )
    }
}

/** The app's version (from the jar's manifest when packaged). */
val VERSION: String = AppState::class.java.`package`?.implementationVersion ?: "1.0.0"
