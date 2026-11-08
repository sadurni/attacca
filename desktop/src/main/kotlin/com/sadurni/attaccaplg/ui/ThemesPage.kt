package com.sadurni.attaccaplg.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sadurni.attacca.settings.PlayerBackground
import com.sadurni.attacca.settings.ThemeFont
import com.sadurni.attacca.settings.ThemeImage
import com.sadurni.attacca.settings.ThemePalette
import com.sadurni.attacca.settings.ThemeSection
import com.sadurni.attacca.settings.ThemeSpec
import java.io.File
import kotlin.math.roundToInt

/**
 * Themes: the built-in ones, the phone's gallery and the desktop's own, made or edited here for
 * the phone and for the desktop (the same format), with previews of both at several sizes.
 */
@Composable
fun ThemesPage(state: AppState) {
    Themes.revision
    var selectedId by remember { mutableStateOf(state.theme) }
    val selected = Themes.of(selectedId)
    Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        ThemeList(state, selected) { selectedId = it }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            val scroll = rememberScrollState()
            Column(Modifier.fillMaxSize().padding(end = 14.dp).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                key(selected.id) { ThemeEditor(state, selected) { selectedId = it } }
            }
            PageScrollbar(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
        ThemePreviews(selected, Modifier.weight(1.1f).fillMaxHeight())
    }
}

/** The themes to choose from, the one in use marked. */
@Composable
private fun ThemeList(state: AppState, selected: Theme, onSelect: (String) -> Unit) {
    Column(Modifier.width(250.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr("Themes", "Temas"), style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                Themes.copy(selected, tr("My theme", "Mi tema"))?.let { onSelect(it.id) }
            }) { Text(tr("New", "Nuevo")) }
            OutlinedButton(onClick = {
                Dialogs.open(tr("A theme exported from Attacca", "Un tema exportado de Attacca"), state.prefs["theme_dir"], "*.zip;*.json")?.let { file ->
                    state.prefs["theme_dir"] = file.parent
                    val json = Themes.import(file)
                    if (json == null) state.message = tr("That is not a theme.", "Eso no es un tema.")
                    else { Themes.revision++; onSelect("file:" + json.path) }
                }
            }) { Text(tr("Open…", "Abrir…")) }
        }
        Hint(tr("New makes a copy of the theme selected.", "Nuevo hace una copia del tema seleccionado."))
        val own = Themes.own()
        val groups = listOf(
            tr("Yours", "Tuyos") to own,
            tr("The phone's gallery", "Galería del teléfono") to listOfNotNull(FestiveDesk.offered()) + Themes.gallery,
            tr("Built in", "Incluidos") to Themes.builtIns,
        )
        val list = rememberLazyListState()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize().padding(end = 14.dp), state = list) {
                for ((title, themes) in groups) {
                    if (themes.isEmpty()) continue
                    item { Text(title, Modifier.padding(top = 10.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
                    items(themes, key = { it.id }) { theme ->
                        ThemeRow(theme, theme.id == selected.id, theme.id == state.theme) { onSelect(theme.id) }
                    }
                }
            }
            PageScrollbar(list, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

@Composable
private fun ThemeRow(theme: Theme, selected: Boolean, inUse: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val p = theme.spec.palette
        Swatches(listOf(p.background, p.surface, p.accent) + listOfNotNull(theme.spec.alternate?.background))
        Text(theme.name, Modifier.weight(1f), maxLines = 2)
        if (inUse) Text("●", color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun Swatches(colors: List<Long>) {
    Row(horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
        for (c in colors) Box(Modifier.size(16.dp).clip(CircleShape).background(Color(c)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape))
    }
}

/** Everything a theme is, to change (an own theme; the others are copied first). */
@Composable
private fun ThemeEditor(state: AppState, theme: Theme, onSelect: (String) -> Unit) {
    val spec = theme.spec
    Text(theme.name, style = MaterialTheme.typography.headlineSmall)
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val inUse = state.theme == theme.id
        OutlinedButton(enabled = !inUse, onClick = { FestiveDesk.themeChangedByHand(); state.theme = theme.id; state.prefs["theme"] = theme.id }) {
            Text(if (inUse) tr("In use", "En uso") else tr("Use it here", "Usarlo aquí"))
        }
        OutlinedButton(onClick = {
            Dialogs.save(tr("The theme for the phone", "El tema para el teléfono"), state.prefs["theme_dir"], theme.name + ".zip")?.let { out ->
                state.prefs["theme_dir"] = out.parent
                val zip = if (out.extension.lowercase() == "zip") out else File(out.path + ".zip")
                runCatching { Themes.exportZip(theme, zip) }
                    .onSuccess { state.message = tr("Saved %s: open it on the phone in Settings → Appearance → Import a theme.", "Guardado %s: ábrelo en el teléfono en Ajustes → Apariencia → Importar un tema.").format(zip.name) }
                    .onFailure { state.message = tr("Could not save it.", "No se pudo guardar.") }
            }
        }) { Text(tr("Export for the phone…", "Exportar para el teléfono…")) }
        if (theme.file != null) TextButton(onClick = {
            if (state.theme == theme.id) { state.theme = "system"; state.prefs["theme"] = "system" }
            Themes.delete(theme)
            onSelect(state.theme)
        }) { Text(tr("Delete", "Borrar"), color = MaterialTheme.colorScheme.error) }
    }
    if (theme.file == null) {
        Section(tr("To change it", "Para cambiarlo")) {
            Hint(tr(
                "The built-in themes and the gallery's stay as they come: make a copy of your own to change it.",
                "Los temas incluidos y los de la galería se quedan como vienen: haz una copia tuya para cambiarlo.",
            ))
            OutlinedButton(onClick = { Themes.copy(theme, theme.name + tr(" (mine)", " (mío)"))?.let { onSelect(it.id) } }) {
                Text(tr("Make a copy to change", "Hacer una copia para cambiarla"))
            }
        }
        return
    }
    fun change(f: (ThemeSpec) -> ThemeSpec) {
        Themes.save(theme, f(Themes.of(theme.id).spec))
    }

    Section(tr("Name", "Nombre")) {
        var name by remember(theme.id) { mutableStateOf(spec.name) }
        OutlinedTextField(name, { v -> name = v; if (v.isNotBlank()) change { it.copy(name = v.trim()) } }, singleLine = true, modifier = Modifier.fillMaxWidth())
    }
    ColorsSection(spec, ::change)
    Section(tr("Shapes and text", "Formas y letra")) {
        SliderRow(tr("Corners", "Esquinas"), spec.corners, ThemeSpec.MIN_CORNERS..ThemeSpec.MAX_CORNERS, { "%.2f".format(it) }) { v -> change { it.copy(corners = v) } }
        Hint(tr("1 as designed; less squarer, more rounder.", "1 como está diseñado; menos más cuadradas, más más redondas."))
        SliderRow(tr("Text size", "Tamaño de letra"), spec.textScale, ThemeSpec.MIN_TEXT..ThemeSpec.MAX_TEXT, { "%.0f %%".format(it * 100) }) { v -> change { it.copy(textScale = v) } }
        Choice(tr("Typeface", "Tipo de letra"), spec.font, ThemeFont.entries, ::fontName, { f -> change { it.copy(font = f) } })
        FontRow(state, theme, tr("Own typeface for text", "Letra propia para el texto"), spec.textFont) { f -> change { it.copy(textFont = f) } }
        FontRow(state, theme, tr("Own typeface for titles", "Letra propia para los títulos"), spec.titleFont) { f -> change { it.copy(titleFont = f) } }
        Hint(tr("Own typefaces are TTF or OTF files; the phone uses them, the previews show them.", "Las letras propias son archivos TTF u OTF; las usa el teléfono y las muestran las vistas previas."))
    }
    Section(tr("Now playing", "Reproduciendo")) {
        Choice(tr("Behind it", "Detrás"), spec.playerBackground, PlayerBackground.entries, ::playerBackgroundName, { b -> change { it.copy(playerBackground = b) } })
        Hint(tr("A picture for Now playing (below) goes over this.", "Una imagen para Reproduciendo (abajo) va por encima de esto."))
    }
    BackgroundsSection(state, theme, ::change)
    LayoutSection(theme, ::change)
    IconsSection(state, theme, ::change)
}

private fun fontName(f: ThemeFont) = when (f) {
    ThemeFont.SYSTEM -> tr("The system's", "La del sistema")
    ThemeFont.SERIF -> tr("Serif", "Con remates")
    ThemeFont.MONOSPACE -> tr("Monospace", "Monoespaciada")
    ThemeFont.CURSIVE -> tr("Handwriting", "Manuscrita")
}

private fun playerBackgroundName(b: PlayerBackground) = when (b) {
    PlayerBackground.PLAIN -> tr("The background colour", "El color de fondo")
    PlayerBackground.GRADIENT -> tr("A gradient from the accent", "Un degradado del acento")
    PlayerBackground.BLURRED_COVER -> tr("The cover, blurred", "La carátula, difuminada")
}

/** The colours of both versions (light and dark), each with its picker. */
@Composable
private fun ColorsSection(spec: ThemeSpec, change: ((ThemeSpec) -> ThemeSpec) -> Unit) {
    Section(tr("Colours", "Colores")) {
        // Which version is being edited: the main one or the alternate.
        var editAlternate by remember(spec.alternate == null) { mutableStateOf(false) }
        val alternate = spec.alternate
        val palette = if (editAlternate && alternate != null) alternate else spec.palette
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (alternate != null) {
                val names = listOf(false, true)
                Choice(tr("Version", "Versión"), editAlternate, names, { alt ->
                    val p = if (alt) alternate else spec.palette
                    if (p.dark) tr("Dark", "Oscura") else tr("Light", "Clara")
                }, { editAlternate = it })
                TextButton(onClick = { editAlternate = false; change { it.copy(alternate = null) } }) {
                    Text(tr("Keep one version", "Dejar una versión"))
                }
            } else {
                Text(if (spec.dark) tr("Dark only", "Solo oscura") else tr("Light only", "Solo clara"), Modifier.weight(1f))
                OutlinedButton(onClick = { change { it.withBothVersions() } }) {
                    Text(if (spec.dark) tr("Add a light version", "Añadir versión clara") else tr("Add a dark version", "Añadir versión oscura"))
                }
            }
        }
        Hint(tr(
            "With both versions, the phone and the desktop take the one the system is set to.",
            "Con las dos versiones, el teléfono y el escritorio usan la que tenga el sistema.",
        ))
        fun set(p: ThemePalette) = change { s -> if (editAlternate && s.alternate != null) s.copy(alternate = p) else s.withPalette(p).copy(alternate = s.alternate) }
        ColorRow(tr("Background", "Fondo"), palette.background) { set(palette.copy(background = it)) }
        ColorRow(tr("Boxes", "Cajas"), palette.surface) { set(palette.copy(surface = it)) }
        ColorRow(tr("Text", "Texto"), palette.text) { set(palette.copy(text = it)) }
        ColorRow(tr("Secondary text", "Texto secundario"), palette.textSecondary) { set(palette.copy(textSecondary = it)) }
        ColorRow(tr("Accent", "Acento"), palette.accent) { set(palette.copy(accent = it)) }
        ColorRow(tr("Left out (lists)", "Excluidos (listas)"), palette.excluded) { set(palette.copy(excluded = it)) }
        ColorRow(tr("Taking away", "Quitar"), palette.danger) { set(palette.copy(danger = it)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(spec.coverAccent, { on -> change { it.copy(coverAccent = on) } })
            Text("  " + tr("The accent from the playing track's cover", "El acento de la carátula del tema que suena"))
        }
    }
}

/** A colour: its swatch and code, and a picker (hue, saturation, brightness, opacity) when opened. */
@Composable
private fun ColorRow(label: String, color: Long, onChange: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, Modifier.weight(1f))
        // The swatch opens the picker.
        Box(
            Modifier.size(32.dp).clip(RoundedCornerShape(6.dp)).background(Color(color))
                .border(if (open) 2.dp else 1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp)).clickable { open = !open },
        )
        var text by remember(color) { mutableStateOf(ThemeSpec.hex(color)) }
        OutlinedTextField(text, { v ->
            text = v
            val c = ThemeSpec.parseColor(v, -1L)
            if (c != -1L) onChange(c)
        }, singleLine = true, modifier = Modifier.width(140.dp))
    }
    if (open) ColorPicker(color, onChange)
}

@Composable
private fun ColorPicker(color: Long, onChange: (Long) -> Unit) {
    val a = ((color shr 24) and 0xFF).toInt()
    val hsb = java.awt.Color.RGBtoHSB(((color shr 16) and 0xFF).toInt(), ((color shr 8) and 0xFF).toInt(), (color and 0xFF).toInt(), null)
    // Kept while dragging: a grey has no hue of its own to come back to.
    var h by remember { mutableStateOf(hsb[0]) }
    var s by remember { mutableStateOf(hsb[1]) }
    var b by remember { mutableStateOf(hsb[2]) }
    var alpha by remember { mutableStateOf(a / 255f) }
    fun emit() {
        val rgb = java.awt.Color.HSBtoRGB(h, s, b).toLong() and 0xFFFFFF
        onChange(((alpha * 255).roundToInt().toLong() shl 24) or rgb)
    }
    Column(Modifier.padding(start = 24.dp).border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(10.dp)).padding(10.dp)) {
        SliderRow(tr("Hue", "Tono"), h, 0f..1f, { "${(it * 360).roundToInt()}°" }) { h = it; emit() }
        SliderRow(tr("Saturation", "Saturación"), s, 0f..1f, { "${(it * 100).roundToInt()} %" }) { s = it; emit() }
        SliderRow(tr("Brightness", "Brillo"), b, 0f..1f, { "${(it * 100).roundToInt()} %" }) { b = it; emit() }
        SliderRow(tr("Opacity", "Opacidad"), alpha, 0f..1f, { "${(it * 100).roundToInt()} %" }) { alpha = it; emit() }
    }
}

@Composable
internal fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, format: (Float) -> String, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, Modifier.width(130.dp))
        Slider(value.coerceIn(range), onChange, valueRange = range, modifier = Modifier.weight(1f))
        Text(format(value), Modifier.width(56.dp))
    }
}

/** An own typeface file of the theme: chosen, or none. */
@Composable
private fun FontRow(state: AppState, theme: Theme, label: String, file: String?, onChange: (String?) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(label)
            Text(file ?: tr("none", "ninguna"), maxLines = 1, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedButton(onClick = {
            pickFile(state, tr("A typeface (TTF or OTF)", "Un tipo de letra (TTF u OTF)"), "*.ttf;*.otf")?.let { f -> Themes.addFile(theme, f)?.let(onChange) }
        }) { Text(tr("Choose…", "Elegir…")) }
        if (file != null) TextButton(onClick = { onChange(null) }) { Text(tr("Remove", "Quitar")) }
    }
}

internal fun pickFile(state: AppState, title: String, pattern: String): File? =
    Dialogs.open(title, state.prefs["theme_pictures"], pattern)?.also { state.prefs["theme_pictures"] = it.parent }

private const val PICTURES = "*.jpg;*.jpeg;*.png;*.webp"

/** The background picture of each screen, with its versions and softening. */
@Composable
private fun BackgroundsSection(state: AppState, theme: Theme, change: ((ThemeSpec) -> ThemeSpec) -> Unit) {
    Section(tr("Background pictures", "Imágenes de fondo")) {
        Hint(tr(
            "Each screen of the phone can have its picture; Others goes to every screen without one (not Now playing). On the desktop: Playing, Music (folders), Lists, Sound, Settings, the rest Others. Softening lays the background colour over the picture so text reads well.",
            "Cada pantalla del teléfono puede tener su imagen; Otras va en las pantallas sin la suya (no en Reproduciendo). En el escritorio: Reproduciendo, Música (carpetas), Listas, Sonido, Ajustes y el resto Otras. Suavizar pone el color de fondo sobre la imagen para que se lea bien el texto.",
        ))
        val spec = theme.spec
        val both = spec.alternate != null
        for (section in ThemeSection.entries) {
            val image = spec.backgrounds[section]
            fun set(i: ThemeImage?) = change { s -> s.copy(backgrounds = if (i == null) s.backgrounds - section else s.backgrounds + (section to i)) }
            BackgroundRow(state, theme, sectionName(section), image, both || !spec.dark, spec.dark || both, ::set)
        }
    }
}

internal fun sectionName(s: ThemeSection) = when (s) {
    ThemeSection.PLAYER -> tr("Now playing", "Reproduciendo")
    ThemeSection.FOLDERS -> tr("Folders", "Carpetas")
    ThemeSection.LISTS -> tr("Lists", "Listas")
    ThemeSection.QUEUE -> tr("Queue", "Cola")
    ThemeSection.BOOKMARKS -> tr("Bookmarks", "Marcadores")
    ThemeSection.SOUND -> tr("Sound", "Sonido")
    ThemeSection.DEVICES -> tr("Devices", "Dispositivos")
    ThemeSection.SETTINGS -> tr("Settings", "Ajustes")
    ThemeSection.OTHERS -> tr("Others", "Otras")
}

@Composable
private fun BackgroundRow(
    state: AppState, theme: Theme, label: String, image: ThemeImage?, light: Boolean, dark: Boolean, onChange: (ThemeImage?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    fun choose(title: String, use: (String) -> Unit) {
        pickFile(state, title, PICTURES)?.let { f -> Themes.addFile(theme, f)?.let(use) }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Thumb(theme, image?.file)
        fun pick() = choose(label) { f -> onChange((image ?: ThemeImage(f, dim = 30, dimLight = 50)).copy(file = f)) }
        if (image == null) {
            Text(label, Modifier.weight(1f))
            OutlinedButton(onClick = ::pick) { Text(tr("Picture…", "Imagen…")) }
        } else {
            // With a picture its buttons go under the name, so both fit.
            Column(Modifier.weight(1f)) {
                Text(label, fontWeight = FontWeight.SemiBold)
                Row {
                    TextButton(onClick = ::pick) { Text(tr("Change…", "Cambiar…")) }
                    TextButton(onClick = { open = !open }) { Text(if (open) tr("Less", "Menos") else tr("More", "Más")) }
                    TextButton(onClick = { onChange(null) }) { Text(tr("Remove", "Quitar")) }
                }
            }
        }
    }
    if (image != null && open) Column(Modifier.padding(start = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (dark) SliderRow(tr("Soften (dark)", "Suavizar (oscura)"), image.dim.toFloat(), 0f..ThemeImage.MAX_DIM.toFloat(), { "${it.roundToInt()} %" }) { v ->
            onChange(image.copy(dim = v.roundToInt()))
        }
        if (light) SliderRow(tr("Soften (light)", "Suavizar (clara)"), image.dimLight.toFloat(), 0f..ThemeImage.MAX_DIM.toFloat(), { "${it.roundToInt()} %" }) { v ->
            onChange(image.copy(dimLight = v.roundToInt()))
        }
        @Composable
        fun variant(name: String, file: String?, set: (String?) -> Unit) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Thumb(theme, file)
                Text(name, Modifier.weight(1f))
                OutlinedButton(onClick = { choose(name) { set(it) } }) { Text(if (file == null) tr("Picture…", "Imagen…") else tr("Change…", "Cambiar…")) }
                if (file != null) TextButton(onClick = { set(null) }) { Text(tr("Remove", "Quitar")) }
            }
        }
        if (light && dark) variant(tr("With light colours", "Con colores claros"), image.fileLight) { onChange(image.copy(fileLight = it)) }
        variant(tr("In landscape", "En horizontal"), image.fileWide) { onChange(image.copy(fileWide = it)) }
        if (light && dark && image.fileWide != null) variant(tr("Landscape, light", "Horizontal, clara"), image.fileWideLight) { onChange(image.copy(fileWideLight = it)) }
    }
}

/** A small view of one of the theme's pictures. */
@Composable
internal fun Thumb(theme: Theme, file: String?, size: androidx.compose.ui.unit.Dp = 40.dp) {
    val shape = RoundedCornerShape(6.dp)
    Box(Modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (file == null) return@Box
        val bitmap = androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, theme.id, file, Themes.revision) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Themes.picture(theme, file) }
        }.value ?: return@Box
        androidx.compose.foundation.Image(bitmap, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
    }
}
