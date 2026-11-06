package com.sadurni.attacca.ui.settings

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import com.sadurni.attacca.ui.common.tipAnchor
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.rounded.MoreVert
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import com.sadurni.attacca.ui.common.Icon
import com.sadurni.attacca.ui.common.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.palette.graphics.Palette
import com.sadurni.attacca.R
import com.sadurni.attacca.app
import com.sadurni.attacca.settings.AppSettings
import com.sadurni.attacca.settings.PlayerBackground
import com.sadurni.attacca.settings.ThemeFont
import com.sadurni.attacca.settings.ThemeMode
import com.sadurni.attacca.settings.ThemeSpec
import com.sadurni.attacca.settings.ThemeStore
import com.sadurni.attacca.ui.common.ScreenHeader
import com.sadurni.attacca.ui.theme.AttaccaShapes
import com.sadurni.attacca.ui.theme.AttaccaTheme
import com.sadurni.attacca.ui.theme.AttaccaType
import com.sadurni.attacca.ui.theme.LocalAccent
import com.sadurni.attacca.ui.theme.LocalAttaccaColors
import com.sadurni.attacca.ui.theme.ThemeRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * The name shown for a theme: the built-in ones, the gallery's and untouched copies of these in
 * the app's language (a copy keeps its number: "Autumn 2").
 */
@Composable
internal fun themeName(spec: ThemeSpec): String = themeName(LocalContext.current, spec)

internal fun themeName(context: android.content.Context, spec: ThemeSpec): String {
    val res = when (spec.id) {
        ThemeSpec.SYSTEM.id -> R.string.theme_system
        ThemeSpec.SYSTEM_DARK.id -> R.string.theme_system_dark
        ThemeSpec.WHITE.id -> R.string.theme_white
        ThemeSpec.DARK.id -> R.string.theme_dark
        ThemeSpec.BLACK.id -> R.string.theme_black
        else -> null
    }
    if (res != null) return context.getString(res)
    val gallery = spec.untouchedOrigin?.let { GALLERY_NAMES[it] } ?: return spec.name
    val number = if (spec.original) "" else Regex(""" \d+$""").find(spec.name)?.value.orEmpty()
    return context.getString(gallery) + number
}

/** Names of the gallery themes by asset name. */
private val GALLERY_NAMES = mapOf(
    "Aurora" to R.string.theme_name_aurora,
    "Autumn" to R.string.theme_name_autumn,
    "Estelar" to R.string.theme_name_starry,
    "Maritime" to R.string.theme_name_maritime,
    "Mars" to R.string.theme_name_mars,
    "Minicadena" to R.string.theme_name_minisystem,
    "Prado" to R.string.theme_name_meadow,
    "Rays" to R.string.theme_name_rays,
    "Vinilo" to R.string.theme_name_vinyl,
    "Wheat" to R.string.theme_name_wheat,
)

/**
 * Settings → Appearance → Themes: the phone's choice, the built-in themes and the user's, with
 * buttons to edit a theme of one's own or to start one from any theme.
 */
@Composable
fun ThemesSection(settings: AppSettings, onEdit: (String) -> Unit, onGallery: () -> Unit = {}) {
    val context = LocalContext.current
    val ui by settings.ui.collectAsStateWithLifecycle()
    val version = ThemeRuntime.version
    val themes by produceState(ThemeSpec.BUILT_INS, version) { value = withContext(Dispatchers.IO) { ThemeStore.all() } }
    val scope = rememberCoroutineScope()
    val copyName = stringResource(R.string.theme_copy_name)
    fun startFrom(spec: ThemeSpec, name: String) {
        scope.launch {
            val copy = withContext(Dispatchers.IO) { ThemeStore.duplicate(spec, copyName.format(name)) }
            ThemeRuntime.version++
            onEdit(copy.id)
        }
    }
    SectionTitle(stringResource(R.string.section_theme))
    fun isSelected(spec: ThemeSpec) = if (spec.builtIn) {
        ui.themeId == null && ui.themeMode.name == spec.id.removePrefix(ThemeSpec.BUILTIN).uppercase()
    } else {
        ui.themeId == spec.id
    }
    // The built-in themes with both versions; a single one only while it is the one in use.
    for (spec in themes.filter { !it.builtIn || it in ThemeSpec.OFFERED || isSelected(it) }) {
        val name = themeName(spec)
        val selected = isSelected(spec)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                RadioRow(name, selected) {
                    if (spec.builtIn) {
                        val mode = ThemeMode.valueOf(spec.id.removePrefix(ThemeSpec.BUILTIN).uppercase())
                        settings.updateUi { it.copy(themeId = null, themeMode = mode) }
                    } else {
                        settings.updateUi { it.copy(themeId = spec.id) }
                    }
                }
            }
            ThemeSwatches(spec)
            IconButton(onClick = { startFrom(spec, name) }) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = stringResource(R.string.theme_duplicate), tint = LocalAttaccaColors.current.textGrayed)
            }
            if (!spec.builtIn) {
                IconButton(onClick = { onEdit(spec.id) }) {
                    Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.theme_edit), tint = LocalAttaccaColors.current.textGrayed)
                }
            }
            ThemeMoreMenu(spec, name, canDelete = !spec.builtIn && !selected)
        }
    }
    Hint(stringResource(R.string.themes_hint))

    // The themes that come with the app, and themes from a zip.
    val imported = stringResource(R.string.theme_imported)
    val importFailed = stringResource(R.string.theme_import_failed)
    val import = rememberLauncherForActivityResult(com.sadurni.attacca.ui.common.OpenDocumentAt()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        com.sadurni.attacca.ui.common.PickerMemory.put(context, com.sadurni.attacca.ui.common.PickerMemory.THEME_ZIP, uri)
        scope.launch {
            val spec = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use(ThemeStore::importZip) }.getOrNull()
            }
            ThemeRuntime.version++
            android.widget.Toast.makeText(context, spec?.let { imported.format(it.name) } ?: importFailed, android.widget.Toast.LENGTH_LONG).show()
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = onGallery) { Text(stringResource(R.string.theme_gallery_open)) }
        TextButton(onClick = {
            import.launch(
                arrayOf("application/zip", "application/x-zip-compressed", "application/json", "application/octet-stream", "*/*") to
                    com.sadurni.attacca.ui.common.PickerMemory.get(context, com.sadurni.attacca.ui.common.PickerMemory.THEME_ZIP),
            )
        }) { Text(stringResource(R.string.theme_import)) }
    }
}

/** ⋮ of a theme: save it as a zip (any theme), delete it (the user's, when not in use). */
@Composable
internal fun ThemeMoreMenu(spec: ThemeSpec, name: String, canDelete: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val exported = stringResource(R.string.theme_exported)
    val failed = stringResource(R.string.exchange_failed)
    val export = rememberLauncherForActivityResult(com.sadurni.attacca.ui.common.CreateDocumentAt("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        com.sadurni.attacca.ui.common.PickerMemory.put(context, com.sadurni.attacca.ui.common.PickerMemory.THEME_ZIP, uri)
        scope.launch {
            val done = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri, "wt")?.use { ThemeStore.exportZip(spec, it) } != null }.getOrDefault(false)
            }
            android.widget.Toast.makeText(context, if (done) exported else failed, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.theme_more), tint = LocalAttaccaColors.current.textGrayed)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.theme_export)) }, onClick = {
                open = false
                export.launch("$name.zip" to com.sadurni.attacca.ui.common.PickerMemory.get(context, com.sadurni.attacca.ui.common.PickerMemory.THEME_ZIP))
            })
            if (canDelete) {
                DropdownMenuItem(text = { Text(stringResource(R.string.theme_delete)) }, onClick = {
                    open = false
                    confirmDelete = true
                })
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            text = { Text(stringResource(R.string.theme_delete_confirm, name)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        withContext(Dispatchers.IO) { ThemeStore.delete(spec) }
                        ThemeRuntime.version++
                    }
                }) { Text(stringResource(R.string.theme_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** A theme at a glance: its background, text and accent (both versions, when it has them). */
@Composable
internal fun ThemeSwatches(spec: ThemeSpec) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Light first.
        val versions = listOfNotNull(spec, spec.alternate?.let { spec.withPalette(it) }).sortedBy { it.dark }
        for (version in versions) Swatch(version)
    }
}

@Composable
private fun Swatch(spec: ThemeSpec) {
    Row(
        Modifier
            .padding(end = 4.dp)
            .clip(AttaccaShapes.small)
            .border(1.dp, LocalAttaccaColors.current.textGrayed.copy(alpha = 0.3f), AttaccaShapes.small)
            .background(Color(spec.background))
            .padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(Color(spec.text)))
        Spacer(Modifier.width(4.dp))
        Box(Modifier.size(12.dp).clip(CircleShape).background(Color(spec.accent)))
    }
}

/** WCAG contrast ratio of two colors (1 to 21). */
private fun contrast(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
}

/** [top] over [bottom], with [top]'s transparency, as the eye sees it. */
private fun over(top: Color, bottom: Color): Color {
    val a = top.alpha
    return Color(top.red * a + bottom.red * (1 - a), top.green * a + bottom.green * (1 - a), top.blue * a + bottom.blue * (1 - a))
}

private enum class ColorSlot { BACKGROUND, SURFACE, TEXT, SECONDARY, ACCENT, EXCLUDED, DANGER }

private fun ThemeSpec.get(slot: ColorSlot): Long = when (slot) {
    ColorSlot.BACKGROUND -> background
    ColorSlot.SURFACE -> surface
    ColorSlot.TEXT -> text
    ColorSlot.SECONDARY -> textSecondary
    ColorSlot.ACCENT -> accent
    ColorSlot.EXCLUDED -> excluded
    ColorSlot.DANGER -> danger
}

private fun ThemeSpec.with(slot: ColorSlot, color: Long): ThemeSpec = when (slot) {
    ColorSlot.BACKGROUND -> copy(background = color)
    ColorSlot.SURFACE -> copy(surface = color)
    ColorSlot.TEXT -> copy(text = color)
    ColorSlot.SECONDARY -> copy(textSecondary = color)
    ColorSlot.ACCENT -> copy(accent = color)
    ColorSlot.EXCLUDED -> copy(excluded = color)
    ColorSlot.DANGER -> copy(danger = color)
}

@Composable
private fun slotName(slot: ColorSlot): String = stringResource(
    when (slot) {
        ColorSlot.BACKGROUND -> R.string.color_background
        ColorSlot.SURFACE -> R.string.color_surface
        ColorSlot.TEXT -> R.string.color_text
        ColorSlot.SECONDARY -> R.string.color_text_secondary
        ColorSlot.ACCENT -> R.string.color_accent
        ColorSlot.EXCLUDED -> R.string.color_excluded
        ColorSlot.DANGER -> R.string.color_danger
    },
)

/**
 * The theme editor: a live preview of Now playing and a list row on top, and every part of the
 * theme below. Changes can be undone; Save writes the theme's file, Use applies it to the app.
 */
@Composable
fun ThemeEditorScreen(
    themeId: String,
    viewModel: com.sadurni.attacca.ui.PlayerViewModel,
    onBack: () -> Unit,
    /** Opens another theme in the editor (a copy just made). */
    onOpen: (String) -> Unit,
) {
    val context = LocalContext.current
    val settings = context.app.settings
    val ui by settings.ui.collectAsStateWithLifecycle()
    val artwork by viewModel.artwork.collectAsStateWithLifecycle()
    val accentDark by viewModel.artworkAccentDark.collectAsStateWithLifecycle()
    val accentLight by viewModel.artworkAccentLight.collectAsStateWithLifecycle()
    // The preview: the small one (colors) or the whole of Now playing (layout).
    var showPlayer by remember { mutableStateOf(false) }
    // Which layout of Now playing is edited and previewed.
    var editLandscape by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val loaded by produceState<ThemeSpec?>(null, themeId) { value = withContext(Dispatchers.IO) { ThemeStore.find(themeId) } }
    val start = loaded ?: return
    var spec by remember(start) { mutableStateOf(start) }
    var saved by remember(start) { mutableStateOf(start) }
    val history = remember(start) { mutableStateListOf<ThemeSpec>() }
    var picking by remember { mutableStateOf<ColorSlot?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    // Light or dark asked for: whether its colors change too.
    var askKind by remember { mutableStateOf<Boolean?>(null) }
    // With both versions: whether the dark one's colors are being edited.
    var editDark by remember(start) { mutableStateOf(start.dark) }
    val copyName = stringResource(R.string.theme_copy_name)
    val savedMessage = stringResource(R.string.theme_saved)

    fun change(next: ThemeSpec) {
        if (next == spec) return
        history.add(spec)
        if (history.size > UNDO_STEPS) history.removeAt(0)
        spec = next
    }

    fun save(use: Boolean) {
        scope.launch {
            val written = withContext(Dispatchers.IO) { ThemeStore.save(spec) }
            spec = written
            saved = written
            ThemeRuntime.version++
            if (use) settings.updateUi { it.copy(themeId = written.id) }
            android.widget.Toast.makeText(context, savedMessage, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // New pictures go into this theme's folder.
    val editedVersion = (if (spec.alternate != null) editDark else spec.dark) to (spec.alternate != null)
    androidx.compose.runtime.CompositionLocalProvider(LocalEditedThemeId provides spec.id, LocalEditedVersion provides editedVersion) {
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        ScreenHeader(stringResource(R.string.theme_editor_title), onBack, root = false) {
            IconButton(
                onClick = { history.removeLastOrNull()?.let { spec = it } },
                enabled = history.isNotEmpty(),
                modifier = Modifier.tipAnchor("theme.undo"),
            ) {
                Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = stringResource(R.string.undo))
            }
            IconButton(onClick = {
                // A copy with what is being edited, saved, then edited instead.
                scope.launch {
                    val copy = withContext(Dispatchers.IO) { ThemeStore.duplicate(spec, copyName.format(themeName(context, spec))) }
                    ThemeRuntime.version++
                    onOpen(copy.id)
                }
            }) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = stringResource(R.string.theme_duplicate))
            }
            IconButton(onClick = { confirmDelete = true }) {
                Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.theme_delete))
            }
        }
        // With both versions, the one whose colors are edited (and previewed) is the main one of [view].
        val view = if (spec.alternate != null && spec.dark != editDark) spec.swapped() else spec
        fun changeView(next: ThemeSpec) = change(if (next.alternate != null && next.dark != spec.dark) next.swapped() else next)
        // The preview stays in view while the parts below are changed.
        val accent = if (view.dark) accentDark else accentLight
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp).tipAnchor("theme.preview")) {
            val tabs = listOf(false to R.string.preview_colors, true to R.string.preview_player)
            tabs.forEachIndexed { i, (player, label) ->
                SegmentedButton(
                    selected = showPlayer == player,
                    onClick = { showPlayer = player },
                    shape = SegmentedButtonDefaults.itemShape(i, tabs.size),
                    icon = {},
                ) { Text(stringResource(label), fontSize = AttaccaType.secondary) }
            }
        }
        if (showPlayer) {
            AttaccaTheme(spec = view, artworkAccent = accent) {
                com.sadurni.attacca.ui.player.PlayerLayoutPreview(
                    viewModel,
                    ui,
                    Modifier.fillMaxWidth().height(PLAYER_PREVIEW_HEIGHT).padding(horizontal = 16.dp, vertical = 6.dp),
                    landscape = editLandscape,
                )
            }
        } else {
            ThemePreview(view, artwork, accent)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { save(use = false) }, enabled = spec != saved, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.theme_save))
            }
            Button(
                onClick = { save(use = true) },
                colors = ButtonDefaults.buttonColors(containerColor = LocalAccent.current),
                modifier = Modifier.weight(1f).tipAnchor("theme.use"),
            ) { Text(stringResource(R.string.theme_use)) }
        }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
            OutlinedTextField(
                value = spec.name,
                onValueChange = { change(spec.copy(name = it)) },
                label = { Text(stringResource(R.string.theme_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            val both = spec.alternate != null
            // One version (keeping, from both, the one asked for) or both.
            for (toDark in listOf(false, true)) {
                RadioRow(stringResource(if (toDark) R.string.theme_kind_dark else R.string.theme_kind_light), !both && spec.dark == toDark) {
                    when {
                        both -> {
                            change(spec.forSystem(toDark).copy(alternate = null))
                            editDark = toDark
                        }
                        spec.dark != toDark -> askKind = toDark
                    }
                }
            }
            RadioRow(stringResource(R.string.theme_kind_both), both) { if (!both) change(spec.withBothVersions()) }
            Hint(stringResource(if (both) R.string.theme_both_hint else R.string.theme_kind_hint))

            Box(Modifier.tipAnchor("theme.colors")) { SectionTitle(stringResource(R.string.section_colors)) }
            if (both) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    val tabs = listOf(false to R.string.colors_light, true to R.string.colors_dark)
                    tabs.forEachIndexed { i, (dark, label) ->
                        SegmentedButton(
                            selected = view.dark == dark,
                            onClick = { editDark = dark },
                            shape = SegmentedButtonDefaults.itemShape(i, tabs.size),
                            icon = {},
                        ) { Text(stringResource(label), fontSize = AttaccaType.secondary) }
                    }
                }
            }
            for (slot in ColorSlot.entries) {
                Row(
                    Modifier.fillMaxWidth().clickable { picking = slot }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(slotName(slot), fontSize = AttaccaType.body, modifier = Modifier.weight(1f))
                    Text(ThemeSpec.hex(view.get(slot)), fontSize = AttaccaType.secondary, color = LocalAttaccaColors.current.textGrayed)
                    Box(
                        Modifier
                            .padding(start = 12.dp)
                            .size(28.dp)
                            .clip(CircleShape)
                            .border(1.dp, LocalAttaccaColors.current.textGrayed, CircleShape)
                            .background(Color(view.get(slot))),
                    )
                }
            }
            SwitchRow(stringResource(R.string.theme_cover_accent), spec.coverAccent, { change(spec.copy(coverAccent = it)) })
            ContrastWarnings(view)

            SectionTitle(stringResource(R.string.section_shape_text))
            ValueSlider(
                stringResource(R.string.theme_corners), spec.corners, ThemeSpec.MIN_CORNERS..ThemeSpec.MAX_CORNERS, 0.25f,
                { "%d %%".format((it * 100).toInt()) }, { change(spec.copy(corners = it)) },
            )
            ValueSlider(
                stringResource(R.string.theme_text_size), spec.textScale, ThemeSpec.MIN_TEXT..ThemeSpec.MAX_TEXT, 0.05f,
                { "%d %%".format((it * 100 + 0.5f).toInt()) }, { change(spec.copy(textScale = it)) },
            )
            Text(
                stringResource(R.string.theme_font),
                fontSize = AttaccaType.body,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp),
            )
            val fonts = listOf(
                ThemeFont.SYSTEM to R.string.font_system,
                ThemeFont.SERIF to R.string.font_serif,
                ThemeFont.MONOSPACE to R.string.font_mono,
                ThemeFont.CURSIVE to R.string.font_cursive,
            )
            for ((font, label) in fonts) RadioRow(stringResource(label), spec.font == font) { change(spec.copy(font = font)) }
            FontFileRow(stringResource(R.string.theme_font_own_text), spec.id, spec.textFont) { change(spec.copy(textFont = it)) }
            FontFileRow(stringResource(R.string.theme_font_own_titles), spec.id, spec.titleFont) { change(spec.copy(titleFont = it)) }
            Hint(stringResource(R.string.theme_font_hint))

            SectionTitle(stringResource(R.string.theme_player_background))
            val backgrounds = listOf(
                PlayerBackground.PLAIN to R.string.background_plain,
                PlayerBackground.GRADIENT to R.string.background_gradient,
                PlayerBackground.BLURRED_COVER to R.string.background_blurred,
            )
            for ((bg, label) in backgrounds) RadioRow(stringResource(label), spec.playerBackground == bg) { change(spec.copy(playerBackground = bg)) }

            BackgroundsSection(spec) { change(it) }
            AppIconsSection(spec) { change(it) }

            LayoutSection(
                spec,
                viewModel,
                landscape = editLandscape,
                onLandscape = {
                    editLandscape = it
                    showPlayer = true
                },
                onChange = {
                    showPlayer = true
                    change(it)
                },
            )
            Spacer(Modifier.height(24.dp))
        }
    }
    }

    // The version whose colors are edited (the same as in the screen above).
    val pickView = if (spec.alternate != null && spec.dark != editDark) spec.swapped() else spec
    picking?.let { slot ->
        ColorPickerDialog(
            title = slotName(slot),
            initial = Color(pickView.get(slot)),
            artwork = artwork,
            onPick = { color ->
                picking = null
                val next = pickView.with(slot, color.toArgb().toLong() and 0xFFFFFFFFL)
                change(if (next.alternate != null && next.dark != spec.dark) next.swapped() else next)
            },
            onDismiss = { picking = null },
        )
    }
    askKind?.let { toDark ->
        AlertDialog(
            onDismissRequest = { askKind = null },
            text = { Text(stringResource(if (toDark) R.string.convert_to_dark else R.string.convert_to_light)) },
            confirmButton = {
                TextButton(onClick = {
                    askKind = null
                    editDark = toDark
                    change(spec.converted(toDark))
                }) { Text(stringResource(R.string.convert_colors)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    askKind = null
                    editDark = toDark
                    change(spec.copy(dark = toDark))
                }) { Text(stringResource(R.string.keep_colors)) }
            },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            text = { Text(stringResource(R.string.theme_delete_confirm, themeName(spec))) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        withContext(Dispatchers.IO) { ThemeStore.delete(spec) }
                        if (settings.ui.value.themeId == spec.id) settings.updateUi { it.copy(themeId = null) }
                        ThemeRuntime.version++
                        onBack()
                    }
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Warnings when the theme's text would be hard to read on its own backgrounds. */
@Composable
private fun ContrastWarnings(spec: ThemeSpec) {
    val bg = Color(spec.background)
    val checks = listOf(
        Triple(R.string.contrast_text, contrast(Color(spec.text), bg), 4.5f),
        Triple(R.string.contrast_secondary, contrast(over(Color(spec.textSecondary), bg), bg), 3f),
        Triple(R.string.contrast_surface, contrast(Color(spec.text), over(Color(spec.surface), bg)), 4.5f),
        Triple(R.string.contrast_accent, contrast(Color(spec.accent), bg), 3f),
    )
    for ((what, ratio, needed) in checks) {
        if (ratio >= needed) continue
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Warning, contentDescription = null, tint = Color(0xFFFFB300), modifier = Modifier.size(18.dp))
            Text(
                stringResource(R.string.contrast_low, stringResource(what), "%.1f:1".format(ratio)),
                fontSize = AttaccaType.secondary,
                color = LocalAttaccaColors.current.textGrayed,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/** Now playing in small and a row of a list, drawn with [spec] (whatever theme the app uses). */
@Composable
private fun ThemePreview(spec: ThemeSpec, artwork: Bitmap?, artworkAccent: Color?) {
    AttaccaTheme(spec = spec, artworkAccent = artworkAccent) {
        val colors = LocalAttaccaColors.current
        val accent = LocalAccent.current
        val gradient = spec.playerBackground == PlayerBackground.GRADIENT
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clip(AttaccaShapes.medium)
                .border(1.dp, colors.textGrayed.copy(alpha = 0.3f), AttaccaShapes.medium)
                .background(
                    if (gradient) {
                        androidx.compose.ui.graphics.Brush.verticalGradient(listOf(accent.copy(alpha = 0.32f), colors.background))
                    } else {
                        androidx.compose.ui.graphics.SolidColor(colors.background)
                    },
                )
                .padding(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(64.dp).clip(AttaccaShapes.medium).background(colors.surface), contentAlignment = Alignment.Center) {
                    if (artwork != null) {
                        androidx.compose.foundation.Image(
                            artwork.asImageBitmap(),
                            contentDescription = null,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = colors.textGrayed)
                    }
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(stringResource(R.string.preview_title), fontSize = AttaccaType.heading, fontFamily = com.sadurni.attacca.ui.theme.LocalTitleFamily.current, fontWeight = FontWeight.Bold, color = colors.text)
                    Text(stringResource(R.string.preview_artist), fontSize = AttaccaType.secondary, color = colors.textGrayed)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.SkipPrevious, contentDescription = null, tint = colors.text)
                Box(Modifier.size(44.dp).clip(AttaccaShapes.medium).background(accent), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Pause, contentDescription = null, tint = Color.White)
                }
                Icon(Icons.Rounded.SkipNext, contentDescription = null, tint = colors.text)
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp).clip(AttaccaShapes.small).background(colors.surface).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = null, tint = colors.textGrayed, modifier = Modifier.size(20.dp))
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(stringResource(R.string.preview_row), fontSize = AttaccaType.body, color = colors.text)
                    Text(stringResource(R.string.preview_artist), fontSize = AttaccaType.caption, color = colors.textGrayed)
                }
                Text("!", color = colors.excluded, fontSize = AttaccaType.heading, fontWeight = FontWeight.Black)
            }
        }
    }
}

/**
 * Picks a color: hue, saturation, brightness and opacity, its code, ready-made colors and the
 * colors of the playing cover.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorPickerDialog(title: String, initial: Color, artwork: Bitmap?, onPick: (Color) -> Unit, onDismiss: () -> Unit) {
    val hsv = remember {
        FloatArray(3).also { android.graphics.Color.colorToHSV(initial.toArgb(), it) }
    }
    var hue by remember { mutableStateOf(hsv[0]) }
    var saturation by remember { mutableStateOf(hsv[1]) }
    var brightness by remember { mutableStateOf(hsv[2]) }
    var alpha by remember { mutableStateOf(initial.alpha) }
    val color = Color.hsv(hue, saturation, brightness, alpha)
    var code by remember { mutableStateOf(ThemeSpec.hex(color.toArgb().toLong())) }
    fun set(c: Color) {
        val parts = FloatArray(3)
        android.graphics.Color.colorToHSV(c.toArgb(), parts)
        hue = parts[0]
        saturation = parts[1]
        brightness = parts[2]
        alpha = c.alpha
    }
    LaunchedEffect(hue, saturation, brightness, alpha) { code = ThemeSpec.hex(color.toArgb().toLong() and 0xFFFFFFFFL) }
    val coverColors by produceState(emptyList<Color>(), artwork) {
        value = artwork?.let { bitmap ->
            withContext(Dispatchers.Default) {
                Palette.from(bitmap).maximumColorCount(16).generate().swatches
                    .sortedByDescending { it.population }.take(8).map { Color(it.rgb) }
            }
        }.orEmpty()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Box(Modifier.fillMaxWidth().height(40.dp).clip(AttaccaShapes.small).background(color))
                val hues = remember { List(7) { Color.hsv(it * 60f, 1f, 1f) } }
                HsvSlider(stringResource(R.string.color_hue), hue, 0f..360f, hues) { hue = it }
                HsvSlider(
                    stringResource(R.string.color_saturation), saturation, 0f..1f,
                    listOf(Color.hsv(hue, 0f, brightness), Color.hsv(hue, 1f, brightness)),
                ) { saturation = it }
                HsvSlider(
                    stringResource(R.string.color_brightness), brightness, 0f..1f,
                    listOf(Color.Black, Color.hsv(hue, saturation, 1f)),
                ) { brightness = it }
                val opaque = Color.hsv(hue, saturation, brightness)
                HsvSlider(stringResource(R.string.color_opacity), alpha, 0f..1f, listOf(opaque.copy(alpha = 0f), opaque)) { alpha = it }
                OutlinedTextField(
                    value = code,
                    onValueChange = { typed ->
                        code = typed
                        val parsed = ThemeSpec.parseColor(typed, -1L)
                        if (parsed != -1L) set(Color(parsed))
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
                if (coverColors.isNotEmpty()) {
                    Text(stringResource(R.string.color_from_cover), fontSize = AttaccaType.secondary, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                    Swatches(coverColors) { set(it) }
                }
                Text(stringResource(R.string.color_presets), fontSize = AttaccaType.secondary, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                Swatches(PRESETS.map { Color(it) }) { set(it) }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(color) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Swatches(colors: List<Color>, onPick: (Color) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (c in colors) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .border(1.dp, LocalAttaccaColors.current.textGrayed.copy(alpha = 0.5f), CircleShape)
                    .background(c)
                    .clickable { onPick(c) },
            )
        }
    }
}

@Composable
private fun HsvSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    track: List<Color>,
    onChange: (Float) -> Unit,
) {
    Text(label, fontSize = AttaccaType.secondary, modifier = Modifier.padding(top = 8.dp))
    // The track shows what the slider does: the colors it goes through (on gray, for opacity).
    Box(contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(14.dp)
                .clip(CircleShape)
                .background(Color(0xFF9E9E9E))
                .background(androidx.compose.ui.graphics.Brush.horizontalGradient(track)),
        )
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = LocalAttaccaColors.current.text,
                activeTrackColor = Color.Transparent,
                inactiveTrackColor = Color.Transparent,
            ),
        )
    }
}

private const val UNDO_STEPS = 50

private val PLAYER_PREVIEW_HEIGHT = 320.dp

/** Ready-made colors: neutrals, then the usual hues. */
private val PRESETS = listOf(
    0xFF000000, 0xFF121212, 0xFF202124, 0xFF303134, 0xFF5F6368, 0xFFBDC1C6, 0xFFF1F3F4, 0xFFFFFFFF,
    0xFFFF453A, 0xFFFF9800, 0xFFFFC107, 0xFF4CAF50, 0xFF009688, 0xFF03A9F4, 0xFF3F51B5, 0xFF9C27B0,
    0xFFE91E63, 0xFF795548, 0xFFD4AF37, 0xFF1D2F3C,
)

/** A typeface file of the theme ([value], in its folder): imported from the phone or taken away. */
@Composable
private fun FontFileRow(label: String, themeId: String, value: String?, onChange: (String?) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val failed = stringResource(R.string.theme_font_failed)
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val name = withContext(Dispatchers.IO) { com.sadurni.attacca.ui.theme.ThemeFonts.import(context, uri, themeId) }
            if (name != null) onChange(name) else android.widget.Toast.makeText(context, failed, android.widget.Toast.LENGTH_LONG).show()
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = AttaccaType.body)
            Text(
                value?.let(com.sadurni.attacca.ui.theme.ThemeFonts::label) ?: stringResource(R.string.theme_font_none),
                fontSize = AttaccaType.secondary,
                color = LocalAttaccaColors.current.textGrayed,
            )
        }
        if (value != null) TextButton(onClick = { onChange(null) }) { Text(stringResource(R.string.theme_font_remove)) }
        // Font files come with several types depending on the app that offers them.
        TextButton(onClick = { launcher.launch(arrayOf("font/*", "application/x-font-ttf", "application/x-font-otf", "application/font-sfnt", "application/vnd.ms-opentype", "application/octet-stream")) }) {
            Text(stringResource(R.string.theme_font_import))
        }
    }
}
