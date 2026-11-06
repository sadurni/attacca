package com.sadurni.attaccaplg.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sadurni.attacca.settings.AppIcon
import com.sadurni.attacca.settings.BlockType
import com.sadurni.attacca.settings.BoxLine
import com.sadurni.attacca.settings.InfoTemplate
import com.sadurni.attacca.settings.LineKind
import com.sadurni.attacca.settings.PlayerAction
import com.sadurni.attacca.settings.PlayerBackground
import com.sadurni.attacca.settings.PlayerBlock
import com.sadurni.attacca.settings.PlayerLayout
import com.sadurni.attacca.settings.TextSize
import com.sadurni.attacca.settings.ThemeFont
import com.sadurni.attacca.settings.ThemeImage
import com.sadurni.attacca.settings.ThemeSection
import com.sadurni.attacca.settings.ThemeSpec

/** A screen to preview the theme on: its size in pixels and its density (dots per inch). */
private class Screen(val name: () -> String, val width: Int, val height: Int, val dpi: Int, val phone: Boolean) {
    /** Its size as the app lays it out (density-independent units). */
    fun dp(landscape: Boolean): Pair<Float, Float> {
        val w = width * 160f / dpi
        val h = height * 160f / dpi
        return if (landscape) h to w else w to h
    }
}

private val SCREENS = listOf(
    Screen({ tr("Phone 720 × 1600, 280 dpi", "Teléfono 720 × 1600, 280 ppp") }, 720, 1600, 280, true),
    Screen({ tr("Phone 1080 × 2400, 420 dpi", "Teléfono 1080 × 2400, 420 ppp") }, 1080, 2400, 420, true),
    Screen({ tr("Small phone 720 × 1280, 320 dpi", "Teléfono pequeño 720 × 1280, 320 ppp") }, 720, 1280, 320, true),
    Screen({ tr("Old phone 480 × 800, 240 dpi", "Teléfono antiguo 480 × 800, 240 ppp") }, 480, 800, 240, true),
    Screen({ tr("Large phone 1440 × 3200, 560 dpi", "Teléfono grande 1440 × 3200, 560 ppp") }, 1440, 3200, 560, true),
    Screen({ tr("Tablet 1600 × 2560, 320 dpi", "Tableta 1600 × 2560, 320 ppp") }, 1600, 2560, 320, true),
    Screen({ tr("Desktop window 1240 × 820", "Ventana de escritorio 1240 × 820") }, 1240, 820, 160, false),
    Screen({ tr("Desktop window 1920 × 1080", "Ventana de escritorio 1920 × 1080") }, 1920, 1080, 160, false),
    Screen({ tr("Desktop window 1024 × 700", "Ventana de escritorio 1024 × 700") }, 1024, 700, 160, false),
    Screen({ tr("Desktop window 2560 × 1440, 150 %", "Ventana de escritorio 2560 × 1440, 150 %") }, 2560, 1440, 240, false),
)

private enum class PhoneScreen { PLAYER, FOLDERS, LISTS }

/** How the theme looks on the phone and on the desktop, at several sizes, upright or in landscape, light or dark. */
@Composable
fun ThemePreviews(theme: Theme, modifier: Modifier = Modifier) {
    Themes.revision
    var screen by remember { mutableStateOf(SCREENS.first()) }
    var landscape by remember { mutableStateOf(false) }
    var phoneScreen by remember { mutableStateOf(PhoneScreen.PLAYER) }
    var useAlternate by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr("Preview", "Vista previa"), style = MaterialTheme.typography.titleMedium)
        Choice("", screen, SCREENS, { it.name() }, { screen = it })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (screen.phone) {
                Choice("", landscape, listOf(false, true), { if (it) tr("Landscape", "Horizontal") else tr("Upright", "Vertical") }, { landscape = it })
                Choice("", phoneScreen, PhoneScreen.entries, {
                    when (it) {
                        PhoneScreen.PLAYER -> tr("Now playing", "Reproduciendo")
                        PhoneScreen.FOLDERS -> tr("Folders", "Carpetas")
                        PhoneScreen.LISTS -> tr("Lists", "Listas")
                    }
                }, { phoneScreen = it })
            }
            val alt = theme.spec.alternate
            if (alt != null) Choice("", useAlternate, listOf(false, true), {
                val dark = if (it) alt.dark else theme.spec.dark
                if (dark) tr("Dark", "Oscura") else tr("Light", "Clara")
            }, { useAlternate = it })
        }
        val spec = if (useAlternate && theme.spec.alternate != null) theme.spec.swapped() else theme.spec
        ScreenPreview(theme, spec, screen, landscape, phoneScreen, Modifier.fillMaxWidth().weight(1f))
        Hint(tr(
            "The phone's screens are drawn here as they are laid out there (sizes, colours, pictures, typefaces, corners): close to the phone, not the phone itself.",
            "Las pantallas del teléfono se dibujan aquí como allí se disponen (tamaños, colores, imágenes, letras, esquinas): parecidas a las del teléfono, no las mismas.",
        ))
    }
}

/** One screen of the preview: the theme as it lays out there, shrunk to fit [modifier]. */
@Composable
private fun ScreenPreview(theme: Theme, spec: ThemeSpec, screen: Screen, landscape: Boolean, phoneScreen: PhoneScreen, modifier: Modifier) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.TopCenter) {
        val (w, h) = screen.dp(landscape && screen.phone)
        val outer = LocalDensity.current
        val frame = if (screen.phone) 10f else 0f
        // Shrunk to fit the space here; inside, the app's own measures.
        val scale = minOf(maxWidth.value / (w + 2 * frame), maxHeight.value / (h + 2 * frame), 1.5f)
        Box(
            Modifier.requiredSize(((w + 2 * frame) * scale).dp, ((h + 2 * frame) * scale).dp)
                .clip(RoundedCornerShape((if (screen.phone) 24f else 6f).times(scale).dp))
                .background(Color(0xFF111111)).padding((frame * scale).dp),
        ) {
            CompositionLocalProvider(LocalDensity provides Density(outer.density * scale, if (screen.phone) spec.textScale else 1f)) {
                Box(Modifier.requiredSize(w.dp, h.dp).clip(RoundedCornerShape(if (screen.phone) 14.dp else 0.dp))) {
                    if (screen.phone) PhoneMock(theme, spec, phoneScreen, landscape) else DesktopMock(theme, spec)
                }
            }
        }
    }
}

/** Development: the phone's Now playing on every screen, upright and in landscape. */
@Composable
internal fun PhoneScreensForShots(theme: Theme, landscape: Boolean) {
    Row(Modifier.fillMaxSize().background(Color.Gray).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (screen in SCREENS.filter { it.phone }) ScreenPreview(theme, theme.spec, screen, landscape, PhoneScreen.PLAYER, Modifier.weight(1f).fillMaxHeight())
    }
}

/** The colours and measures a preview draws with. */
private class Look(val spec: ThemeSpec, val theme: Theme) {
    val bg = Color(spec.background)
    val surface = Color(spec.surface)
    val text = Color(spec.text)
    val text2 = Color(spec.textSecondary)
    val accent = Color(spec.accent)
    val corner = (16 * spec.corners).dp
    val small = (10 * spec.corners).dp
}

@Composable
private fun fontFamily(theme: Theme, spec: ThemeSpec, title: Boolean): FontFamily {
    val own = (if (title) spec.titleFont ?: spec.textFont else spec.textFont)
    val family = remember(theme.id, own, Themes.revision) {
        own?.let { name ->
            theme.picture(name)?.let { bytes ->
                runCatching { FontFamily(androidx.compose.ui.text.platform.Font(theme.id + "|" + name, bytes)) }.getOrNull()
            }
        }
    }
    return family ?: when (spec.font) {
        ThemeFont.SYSTEM -> FontFamily.Default
        ThemeFont.SERIF -> FontFamily.Serif
        ThemeFont.MONOSPACE -> FontFamily.Monospace
        ThemeFont.CURSIVE -> FontFamily.Cursive
    }
}

@Composable
private fun picture(theme: Theme, file: String?): ImageBitmap? {
    if (file == null) return null
    return produceState<ImageBitmap?>(null, theme.id, file, Themes.revision) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Themes.picture(theme, file) }
    }.value
}

/** A theme's background picture, softened with its background colour, over the whole box. */
@Composable
private fun BackgroundPicture(look: Look, image: ThemeImage?, wide: Boolean, modifier: Modifier = Modifier.fillMaxSize()) {
    image ?: return
    val bitmap = picture(look.theme, image.fileFor(look.spec.dark, wide)) ?: return
    Image(bitmap, null, modifier, contentScale = ContentScale.Crop)
    val dim = image.dimFor(look.spec.dark)
    if (dim > 0) Box(modifier.background(look.bg.copy(alpha = dim / 100f)))
}

/** The sample cover: a picture of colours, as a track's art would be. */
@Composable
private fun SampleCover(modifier: Modifier, look: Look) {
    Box(
        modifier.background(Brush.linearGradient(listOf(Color(0xFF3A6EA5), Color(0xFFE0A458), Color(0xFFB4436C)))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(PlayerIcons.LibraryMusic, null, Modifier.size(48.dp), tint = Color.White.copy(alpha = 0.6f))
    }
}

/** An icon of the app, the theme's own picture when it has one. */
@Composable
private fun AppGlyph(look: Look, icon: AppIcon, fallback: ImageVector, size: Dp, tint: Color = look.text2) {
    val own = look.spec.icons[icon]
    val bitmap = own?.let { picture(look.theme, it.fileFor(look.spec.dark)) }
    if (bitmap != null) {
        Image(bitmap, null, Modifier.size(size), colorFilter = if (own.tint) ColorFilter.tint(tint) else null)
    } else {
        Icon(fallback, null, Modifier.size(size), tint = tint)
    }
}

private val SAMPLE = mapOf(
    "title" to "Morning Light", "artist" to "The Example Band", "album" to "Open Windows", "albumartist" to "The Example Band",
    "track" to "3", "year" to "2019", "genre" to "Indie", "format" to "FLAC", "codec" to "FLAC", "bitrate" to "912 kbps",
    "samplerate" to "44.1 kHz", "bits" to "16 bit", "channels" to "2", "duration" to "4:12", "list" to "Sunday",
    "position" to "3", "count" to "24", "next" to "Slow River", "nextartist" to "Northern Lines", "loudness" to "-9.8",
    "bpm" to "112", "mood" to "calm", "plays" to "7",
)

private fun lineText(kind: LineKind, template: String): String = when (kind) {
    LineKind.TITLE -> SAMPLE.getValue("title")
    LineKind.ARTIST -> SAMPLE.getValue("artist")
    LineKind.ALBUM -> SAMPLE.getValue("album")
    LineKind.LIST_NAME -> SAMPLE.getValue("list")
    LineKind.LIST_DETAILS -> "3 / 24 · 1 h 38 min"
    LineKind.NEXT -> "▶ " + SAMPLE.getValue("next") + " · " + SAMPLE.getValue("nextartist")
    LineKind.TEMPLATE -> InfoTemplate.render(template) { SAMPLE[it] }
}

private fun textSize(size: TextSize) = when (size) {
    TextSize.SMALL -> 13.sp
    TextSize.MEDIUM -> 16.sp
    TextSize.LARGE -> 21.sp
    TextSize.HUGE -> 28.sp
}

@Composable
private fun PhoneMock(theme: Theme, spec: ThemeSpec, screen: PhoneScreen, landscape: Boolean) {
    val look = Look(spec, theme)
    val textFamily = fontFamily(theme, spec, title = false)
    val titleFamily = fontFamily(theme, spec, title = true)
    Box(Modifier.fillMaxSize().background(look.bg)) {
        when (screen) {
            PhoneScreen.PLAYER -> {
                when (spec.playerBackground) {
                    PlayerBackground.PLAIN -> Unit
                    PlayerBackground.GRADIENT -> Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(look.accent.copy(alpha = 0.55f), look.bg))))
                    PlayerBackground.BLURRED_COVER -> Box(
                        Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xFF3A6EA5), Color(0xFFB4436C), look.bg)))
                            .background(look.bg.copy(alpha = 0.45f)),
                    )
                }
                BackgroundPicture(look, spec.backgroundOf(ThemeSection.PLAYER), landscape)
                CompositionLocalProvider(androidx.compose.material3.LocalTextStyle provides TextStyle(fontFamily = textFamily, color = look.text)) {
                    PlayerMock(Parts(look, titleFamily, compact = landscape), spec, landscape)
                }
            }
            PhoneScreen.FOLDERS, PhoneScreen.LISTS -> {
                val folders = screen == PhoneScreen.FOLDERS
                BackgroundPicture(look, spec.backgroundOf(if (folders) ThemeSection.FOLDERS else ThemeSection.LISTS), landscape)
                CompositionLocalProvider(androidx.compose.material3.LocalTextStyle provides TextStyle(fontFamily = textFamily, color = look.text)) {
                    Column(Modifier.fillMaxSize()) {
                        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(NavIcons.Lists, null, tint = look.text)
                            Spacer(Modifier.width(16.dp))
                            Text(if (folders) tr("Music", "Música") else tr("Lists", "Listas"), fontFamily = titleFamily, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Icon(PlayerIcons.Search, null, tint = look.text)
                        }
                        val names = if (folders) listOf("Abbey Road", "Blue Train", "Kind of Blue", "Mezzanine", "OK Computer", "Remain in Light", "The Köln Concert", "Vespertine")
                        else listOf(tr("Favorites", "Favoritos"), "Sunday", tr("Running", "Correr"), tr("Calm evening", "Tarde tranquila"), "Jazz", tr("New this month", "Nuevo este mes"))
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            names.forEachIndexed { i, name ->
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(look.small)).background(if (i == 1) look.accent.copy(alpha = 0.18f) else Color.Transparent).padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(look.small)).background(look.surface), contentAlignment = Alignment.Center) {
                                        AppGlyph(look, if (folders) AppIcon.FOLDER else AppIcon.PLAYLIST, if (folders) PlayerIcons.Folder else NavIcons.Lists, 26.dp)
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(name, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(if (folders) "${8 + i} " + tr("tracks", "pistas") else "${12 + 3 * i} " + tr("tracks", "pistas"), fontSize = 13.sp, color = look.text2)
                                    }
                                }
                            }
                        }
                        MiniPlayer(look)
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniPlayer(look: Look) {
    Row(Modifier.fillMaxWidth().background(look.surface).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        SampleCover(Modifier.size(44.dp).clip(RoundedCornerShape(look.small)), look)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(SAMPLE.getValue("title"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(SAMPLE.getValue("artist"), fontSize = 13.sp, color = look.text2, maxLines = 1)
        }
        Icon(PlayerIcons.SkipPrevious, null, tint = look.text)
        Icon(PlayerIcons.PlayArrow, null, Modifier.padding(horizontal = 12.dp), tint = look.accent)
        Icon(PlayerIcons.SkipNext, null, tint = look.text)
    }
}

/** What every part of the mock Now playing draws with. */
private class Parts(val look: Look, val titleFamily: FontFamily, val compact: Boolean)

/**
 * Now playing laid out by the phone's own rules: the room under the status bar and over the
 * navigation bar; upright, the cover and empty space share what the other parts leave, and when
 * even a small cover does not fit the whole column is drawn smaller; in landscape the theme's own
 * layout, or the cover and the list at the left (42 %) and the rest at the right.
 */
@Composable
private fun PlayerMock(parts: Parts, spec: ThemeSpec, landscape: Boolean) {
    val modifier = Modifier.fillMaxSize().padding(top = 24.dp, bottom = 16.dp).padding(horizontal = 16.dp, vertical = 8.dp)
    val layout = spec.layout
    val own = spec.landscape.takeIf { landscape }
    val side = layout.landscapeSide
    if (own != null) {
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) { Blocks(own.blocks, parts) }
    } else if (landscape && side.any { it.type == BlockType.COVER }) {
        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(0.42f).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) { Blocks(side, parts) }
            Spacer(Modifier.width(16.dp))
            FitOrScroll(Modifier.weight(0.58f).fillMaxSize()) { Blocks(layout.blocks - side.toSet(), parts, bounded = false) }
        }
    } else {
        KeepCover(modifier) { fill -> Blocks(layout.blocks, parts, fillHeight = fill) }
    }
}

/** The least height the phone keeps for the cover before drawing the column smaller. */
private val MIN_COVER = 180.dp

/** Below this the phone scrolls a side that does not fit instead of drawing it smaller still. */
private const val MIN_FIT_SCALE = 0.6f

/** The phone's buttons: the play button and the others. */
private val PLAY_SIZE = 64.dp
private val TOOL_SIZE = 48.dp

/** As the phone's: the column at full height, or drawn smaller around a cover of [MIN_COVER]. */
@Composable
private fun KeepCover(modifier: Modifier, content: @Composable ColumnScope.(fill: Dp?) -> Unit) {
    androidx.compose.ui.layout.SubcomposeLayout(modifier.then(Modifier.clipToBounds())) { constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val unbounded = androidx.compose.ui.unit.Constraints.Infinity
        val least = subcompose("least") { Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) { content(MIN_COVER) } }
            .map { it.measure(androidx.compose.ui.unit.Constraints(width, width, 0, unbounded)) }
        val tall = least.maxOfOrNull { it.height } ?: 0
        if (tall <= height) {
            val p = subcompose("normal") { Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) { content(null) } }
                .map { it.measure(androidx.compose.ui.unit.Constraints(width, width, height, height)) }
            return@SubcomposeLayout layout(width, height) { p.forEach { it.placeRelative(0, 0) } }
        }
        val scale = (height.toFloat() / tall).coerceAtLeast(0.5f)
        val wide = (width / scale).toInt()
        val p = subcompose("scaled") { Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) { content(MIN_COVER) } }
            .map { it.measure(androidx.compose.ui.unit.Constraints(wide, wide, 0, unbounded)) }
        layout(width, height) {
            p.forEach { it.placeWithLayer((width - wide) / 2, (height - it.height) / 2) { scaleX = scale; scaleY = scale } }
        }
    }
}

/** As the phone's: a column centred in the height, drawn smaller when taller, cut below [MIN_FIT_SCALE]. */
@Composable
private fun FitOrScroll(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    androidx.compose.ui.layout.SubcomposeLayout(modifier.then(Modifier.clipToBounds())) { constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val unbounded = androidx.compose.ui.unit.Constraints.Infinity
        fun column(slot: String) = subcompose(slot) { Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, content = content) }
        val natural = column("natural").map { it.measure(androidx.compose.ui.unit.Constraints(width, width, 0, unbounded)) }
        val tall = natural.maxOfOrNull { it.height } ?: 0
        if (height == unbounded || tall <= height) {
            val h = if (height == unbounded) tall else height
            return@SubcomposeLayout layout(width, h) { natural.forEach { it.placeRelative(0, (h - it.height) / 2) } }
        }
        val fit = height.toFloat() / tall
        val scale = fit.coerceAtLeast(MIN_FIT_SCALE)
        val wide = (width / scale).toInt()
        val placed = column("scaled").map { it.measure(androidx.compose.ui.unit.Constraints(wide, wide, 0, unbounded)) }
        layout(width, height) {
            // On the phone this scrolls when too tall; here it shows its top.
            placed.forEach {
                val y = if (fit < MIN_FIT_SCALE) ((it.height * scale - it.height) / 2).toInt() else (height - it.height) / 2
                it.placeWithLayer((width - wide) / 2, y) { scaleX = scale; scaleY = scale }
            }
        }
    }
}

/** As the phone's: the gap after each part. */
private fun gapAfter(type: BlockType, compact: Boolean) = when (type) {
    BlockType.COVER -> 8.dp
    BlockType.TITLE -> if (compact) 4.dp else 6.dp
    BlockType.LIST -> if (compact) 4.dp else 10.dp
    BlockType.PROGRESS -> if (compact) 0.dp else 8.dp
    BlockType.BUTTONS -> if (compact) 4.dp else 12.dp
    BlockType.TEXT, BlockType.BOX -> 6.dp
    BlockType.SPACE -> 0.dp
}

/** Parts that take the room left over: the cover, empty space and a box holding the cover. */
private fun grows(block: PlayerBlock) = when (block.type) {
    BlockType.COVER, BlockType.SPACE -> true
    BlockType.BOX -> block.flatten().any { it.type == BlockType.COVER }
    else -> false
}

/** As the phone's: parts one under the other, the cover and empty space sharing the room left. */
@Composable
private fun ColumnScope.Blocks(blocks: List<PlayerBlock>, parts: Parts, nested: Boolean = false, bounded: Boolean = !nested, fillHeight: Dp? = null) {
    if (fillHeight != null && bounded) {
        blocks.forEachIndexed { i, block ->
            val fill = grows(block)
            val height = if (block.type == BlockType.SPACE) 0.dp else fillHeight
            Part(block, parts, if (fill) Modifier.height(height) else Modifier, nested, bounded = fill)
            if (i < blocks.lastIndex) Spacer(Modifier.height(gapAfter(block.type, parts.compact)))
        }
        return
    }
    val grows = bounded && blocks.any(::grows)
    if (bounded && !grows) Spacer(Modifier.weight(1f))
    blocks.forEachIndexed { i, block ->
        val fill = bounded && grows(block)
        Part(block, parts, if (fill) Modifier.weight(1f) else Modifier, nested, bounded = fill)
        if (i < blocks.lastIndex) Spacer(Modifier.height(gapAfter(block.type, parts.compact)))
    }
    if (bounded && !grows) Spacer(Modifier.weight(1f))
}

/** One part of Now playing, as the theme's layout has it. */
@Composable
private fun Part(block: PlayerBlock, parts: Parts, modifier: Modifier, nested: Boolean, bounded: Boolean = false) {
    val look = parts.look
    val compact = parts.compact
    when (block.type) {
        BlockType.COVER -> BoxWithConstraints(
            modifier.fillMaxWidth()
                .then(if (nested && !bounded) Modifier.aspectRatio(1f) else Modifier)
                .padding(horizontal = if (compact || nested) 4.dp else 12.dp, vertical = if (nested) 0.dp else 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Square, as large as fits.
            SampleCover(Modifier.size(minOf(maxWidth, maxHeight)).clip(RoundedCornerShape(look.corner)), look)
        }
        BlockType.BOX -> Container(block, parts, modifier, bounded)
        BlockType.SPACE -> Spacer(modifier.then(if (nested) Modifier.size(12.dp) else Modifier))
        BlockType.PROGRESS -> Progress(look, if (compact || nested) 56.dp else 72.dp)
        BlockType.BUTTONS -> ButtonRow(look, block)
        else -> Column(Modifier.fillMaxWidth()) { Block(look, block, parts.titleFamily) }
    }
}

/** As the phone's: a box of parts one under the other or side by side, in a frame or not. */
@Composable
private fun Container(block: PlayerBlock, parts: Parts, modifier: Modifier, bounded: Boolean) {
    val look = parts.look
    val framed = block.framed
    val shaped = framed || block.image != null
    Box(
        modifier.fillMaxWidth()
            .then(if (shaped) Modifier.clip(RoundedCornerShape(look.corner)) else Modifier)
            .then(if (framed) Modifier.background(look.surface.copy(alpha = 0.92f)) else Modifier),
    ) {
        block.image?.let { BackgroundPicture(look, it, false, Modifier.matchParentSize()) }
        val frame = if (shaped) Modifier.padding(horizontal = 10.dp, vertical = 6.dp) else Modifier
        val fill = if (bounded) Modifier.fillMaxSize() else Modifier
        if (block.horizontal) {
            val children = block.children
            val auto = children.count { it.width == 0 }
            val share = if (auto == 0) 0f else ((100 - children.sumOf { it.width }).coerceAtLeast(auto * 5).toFloat() / auto)
            Row(fill.fillMaxWidth().then(frame), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                for (child in children) {
                    val column = Modifier.weight((if (child.width > 0) child.width.toFloat() else share).coerceAtLeast(1f))
                    val tall = bounded && grows(child)
                    if (bounded && !tall) {
                        FitOrScroll(column.fillMaxHeight()) { Part(child, parts, Modifier, nested = true) }
                    } else {
                        Column(
                            column.then(if (bounded) Modifier.fillMaxHeight() else Modifier),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) { Part(child, parts, if (tall) Modifier.weight(1f) else Modifier, nested = true, bounded = tall) }
                    }
                }
            }
        } else {
            Column(fill.fillMaxWidth().then(frame), horizontalAlignment = Alignment.CenterHorizontally) {
                Blocks(block.children, parts, nested = true, bounded = bounded)
            }
        }
    }
}

/** The wave to seek in, as tall as the phone's, and the times under it. */
@Composable
private fun Progress(look: Look, height: Dp) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(height), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for (i in 0 until 48) {
                val h = 0.25f + 0.75f * (kotlin.math.sin(i * 0.7f) * kotlin.math.sin(i * 0.23f + 1f)).let { kotlin.math.abs(it) }
                Box(Modifier.weight(1f).fillMaxHeight(h).background(if (i < 18) look.accent else look.text.copy(alpha = 0.55f)))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text("1:34", fontSize = 13.sp, color = look.text2)
            Spacer(Modifier.weight(1f))
            Text("4:12", fontSize = 13.sp, color = look.text2)
        }
    }
}

/** As the phone's: the buttons shrink on a narrow screen instead of running off the edge. */
@Composable
private fun ButtonRow(look: Look, block: PlayerBlock) {
    val actions = block.actions
    val hasPlay = PlayerAction.PLAY_PAUSE in actions
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val needed = TOOL_SIZE * (actions.size - if (hasPlay) 1 else 0) + if (hasPlay) PLAY_SIZE else 0.dp
        val scale = if (needed.value > 0) (maxWidth / needed).coerceAtMost(1f) else 1f
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = if (actions.size >= 5) Arrangement.SpaceBetween else Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (action in actions) ActionButton(look, action, block.icons[action], scale)
        }
    }
}

/** A title, list or information part. */
@Composable
private fun ColumnScope.Block(look: Look, block: PlayerBlock, titleFamily: FontFamily) {
    when (block.type) {
        BlockType.TITLE, BlockType.LIST -> Framed(look, block) {
            for (line in block.lines) {
                val main = line.kind == LineKind.TITLE || line.kind == LineKind.LIST_NAME
                Text(
                    lineText(line.kind, line.template), fontSize = textSize(line.size), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontFamily = if (main) titleFamily else null, fontWeight = if (main) FontWeight.Bold else FontWeight.Normal,
                    color = if (main || line.kind == LineKind.ARTIST) look.text else look.text2,
                    modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
        BlockType.TEXT -> {
            val text = InfoTemplate.render(block.template) { SAMPLE[it] }
            if (block.boxed) Framed(look, block.copy(framed = true)) { Text(text, fontSize = textSize(block.size), color = look.text2) }
            else Text(text, fontSize = textSize(block.size), color = look.text2, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        else -> Unit
    }
}

/** A part in its frame (the box colour, the theme's corners), or just its contents. */
@Composable
private fun Framed(look: Look, block: PlayerBlock, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(look.corner)
    val framed = block.framed
    Box(Modifier.fillMaxWidth().clip(if (framed) shape else RoundedCornerShape(0.dp)).then(if (framed) Modifier.background(look.surface.copy(alpha = 0.92f)) else Modifier)) {
        // The picture takes the box's size, not the other way round.
        if (framed) block.image?.let { BackgroundPicture(look, it, false, Modifier.matchParentSize()) }
        Column(Modifier.fillMaxWidth().padding(if (framed) 12.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
    }
}

@Composable
private fun RowScope.ActionButton(look: Look, action: PlayerAction, own: ThemeImage?, scale: Float) {
    val bitmap = own?.let { picture(look.theme, it.fileFor(look.spec.dark)) }
    val play = action == PlayerAction.PLAY_PAUSE
    Box(
        Modifier.size((if (play) PLAY_SIZE else TOOL_SIZE) * scale).clip(RoundedCornerShape(if (play) look.corner * scale else look.small))
            .then(if (play) Modifier.background(look.accent) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        val tint = if (play) (if (look.accent.luminance() > 0.5f) Color.Black else Color.White) else look.text
        if (bitmap != null) Image(bitmap, null, Modifier.size(28.dp * scale), colorFilter = if (own.tint) ColorFilter.tint(tint) else null)
        else Icon(actionIcon(action), null, Modifier.size((if (play) 34.dp else 26.dp) * scale), tint = tint)
    }
}

private fun Color.luminance() = 0.2126f * red + 0.7152f * green + 0.0722f * blue

private fun actionIcon(a: PlayerAction): ImageVector = when (a) {
    PlayerAction.PLAY_PAUSE -> PlayerIcons.PlayArrow
    PlayerAction.PREVIOUS -> PlayerIcons.SkipPrevious
    PlayerAction.NEXT -> PlayerIcons.SkipNext
    PlayerAction.SHUFFLE -> PlayerIcons.Shuffle
    PlayerAction.REPEAT -> PlayerIcons.Repeat
    PlayerAction.FAVORITE -> PlayerIcons.Favorite
    PlayerAction.SOUND -> PlayerIcons.GraphicEq
    PlayerAction.ORAMA -> PlayerIcons.Landscape
    PlayerAction.QUEUE, PlayerAction.LISTS, PlayerAction.QUICK_LIST, PlayerAction.RANDOM_LIST -> NavIcons.Lists
    PlayerAction.FOLDER, PlayerAction.NAVIGATOR -> PlayerIcons.Folder
    else -> PlayerIcons.MoreHoriz
}

/** The desktop window in the theme: the side bar, a page with its picture and the player bar. */
@Composable
private fun DesktopMock(theme: Theme, spec: ThemeSpec) {
    val palette = Palette.of(spec.palette)
    androidx.compose.material3.MaterialTheme(colorScheme = Themes.scheme(palette)) {
        val look = Look(spec, theme)
        val c = MaterialTheme.colorScheme
        Column(Modifier.fillMaxSize().background(c.background)) {
            Row(Modifier.weight(1f)) {
                Column(Modifier.width(80.dp).fillMaxHeight().background(c.surfaceContainerLow).padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val items = listOf(PlayerIcons.PlayCircle, PlayerIcons.LibraryMusic, NavIcons.Library, NavIcons.Tracks, NavIcons.Lists, PlayerIcons.GraphicEq, NavIcons.Exchange)
                    items.forEachIndexed { i, icon ->
                        Box(
                            Modifier.padding(vertical = 8.dp).size(56.dp, 32.dp).clip(RoundedCornerShape(16.dp))
                                .background(if (i == 0) c.secondaryContainer else Color.Transparent),
                            contentAlignment = Alignment.Center,
                        ) { Icon(icon, null, tint = c.onSurface) }
                    }
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    BackgroundPicture(look, spec.backgroundOf(ThemeSection.PLAYER), wide = true)
                    Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        SampleCover(Modifier.fillMaxHeight(0.7f).aspectRatio(1f).clip(RoundedCornerShape(12.dp)), look)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(SAMPLE.getValue("title"), style = MaterialTheme.typography.headlineMedium, color = c.onBackground)
                            Text(SAMPLE.getValue("artist") + " · " + SAMPLE.getValue("album"), color = c.onSurfaceVariant)
                            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surfaceContainer).padding(12.dp)) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    for (name in listOf("Slow River", "Northern Lines", "Paper Boats", "Glass Garden")) Text(name, color = c.onSurface)
                                }
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().height(64.dp).background(c.surfaceContainerLow).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                SampleCover(Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)), look)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.width(180.dp)) {
                    Text(SAMPLE.getValue("title"), color = c.onSurface, maxLines = 1)
                    Text(SAMPLE.getValue("artist"), color = c.onSurfaceVariant, fontSize = 12.sp, maxLines = 1)
                }
                Icon(PlayerIcons.SkipPrevious, null, tint = c.onSurface)
                Box(Modifier.padding(horizontal = 10.dp).size(40.dp).clip(CircleShape).background(c.primary), contentAlignment = Alignment.Center) {
                    Icon(PlayerIcons.PlayArrow, null, tint = c.onPrimary)
                }
                Icon(PlayerIcons.SkipNext, null, tint = c.onSurface)
                Spacer(Modifier.width(16.dp))
                Box(Modifier.weight(1f).height(4.dp).clip(CircleShape).background(lerp(c.surfaceContainerLow, c.primary, 0.35f))) {
                    Box(Modifier.fillMaxWidth(0.38f).fillMaxHeight().background(c.primary))
                }
            }
        }
    }
}
