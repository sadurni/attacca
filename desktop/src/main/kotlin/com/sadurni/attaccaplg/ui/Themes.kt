package com.sadurni.attaccaplg.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import com.sadurni.attacca.settings.ThemeImage
import com.sadurni.attacca.settings.ThemePalette
import com.sadurni.attacca.settings.ThemeSection
import com.sadurni.attacca.settings.ThemeSpec
import org.json.JSONObject
import androidx.compose.ui.unit.dp
import java.io.File

/** A theme's colours, as the phone's themes have them. */
data class Palette(
    val dark: Boolean,
    val background: Long,
    val surface: Long,
    val text: Long,
    val textSecondary: Long,
    val accent: Long,
    val danger: Long = 0xFFE05555,
) {
    companion object {
        fun of(p: ThemePalette) = Palette(p.dark, p.background, p.surface, p.text, p.textSecondary, p.accent, p.danger)
    }
}

/**
 * A theme: the phone's own format ([spec], shared through core), with where its pictures and fonts
 * come from. The desktop shows its colours and background pictures; the rest is the phone's, kept
 * whole so a theme edited here goes back to the phone as it was.
 */
class Theme(
    val id: String,
    val name: String,
    val spec: ThemeSpec,
    /** A picture's (or font's) bytes by its file name (null: missing). */
    val picture: (String) -> ByteArray? = { null },
    /** The theme's file, for the user's own themes (null: built in or of the gallery). */
    val file: File? = null,
) {
    val palette: Palette get() = Palette.of(spec.palette)
    val alternate: Palette? get() = spec.alternate?.let(Palette::of)

    /** The accent taken from the playing track's cover (as the phone does). */
    val coverAccent: Boolean get() = spec.coverAccent

    /** Background pictures by the phone's screen names (PLAYER, FOLDERS, LISTS, SOUND, SETTINGS, OTHERS…). */
    val backgrounds: Map<String, ThemeImage> get() = spec.backgrounds.mapKeys { it.key.name }

    /** The picture for a screen: its own, or the one for the others (never for the player, as on the phone). */
    fun background(section: String) = ThemeSection.entries.firstOrNull { it.name == section }?.let(spec::backgroundOf)

    /** The colours to show with the system [systemDark] or light: as the phone picks them. */
    fun palette(systemDark: Boolean) = Palette.of(spec.forSystem(systemDark).palette)
}

/** The themes, the same as the phone's: White, Dark, Black, System (white by day, black by night), the gallery's. */
object Themes {
    val builtIns: List<Theme>
        get() = listOf(
            Theme("system", tr("The system's (white, black)", "El del sistema (blanco, negro)"), ThemeSpec.SYSTEM),
            Theme("system_dark", tr("The system's (white, dark)", "El del sistema (blanco, oscuro)"), ThemeSpec.SYSTEM_DARK),
            Theme("white", tr("White", "Blanco"), ThemeSpec.WHITE),
            Theme("dark", tr("Dark", "Oscuro"), ThemeSpec.DARK),
            Theme("black", tr("Black", "Negro"), ThemeSpec.BLACK),
        )

    /** The phone's gallery of themes, packed from the app's own theme files. */
    val gallery: List<Theme> by lazy {
        val index = Themes::class.java.getResource("/themes/index.txt")?.readText().orEmpty()
        index.trim().split(' ').filter { it.endsWith(".json") }.mapNotNull { name ->
            val text = Themes::class.java.getResource("/themes/$name")?.readText() ?: return@mapNotNull null
            val theme = name.removeSuffix(".json")
            fromJson(ThemeSpec.GALLERY + theme, text) { file -> Themes::class.java.getResource("/themes/$theme/$file")?.readBytes() }
        }
    }

    private val files = HashMap<String, Theme?>()

    /** The theme [id] names ("system", "white", "gallery:Aurora", "file:C:\…\Mine.json"…); the system's when unknown. */
    fun of(id: String): Theme {
        val legacy = if (id == "light") "white" else id
        builtIns.firstOrNull { it.id == legacy }?.let { return it }
        gallery.firstOrNull { it.id == legacy }?.let { return it }
        if (legacy.startsWith("file:")) {
            files.getOrPut(legacy) { runCatching { fromFile(File(legacy.removePrefix("file:"))) }.getOrNull() }
                ?.let { return it }
        }
        return builtIns.first()
    }

    /** Forgets what was read of a theme file (it was saved again). */
    fun forget(file: File) {
        files.remove("file:" + file.path)
        pictures.keys.removeAll { it.startsWith("file:" + file.path + "|") }
    }

    /** Goes up whenever a theme of the desktop's own changes: what shows a theme reads it to follow. */
    var revision by androidx.compose.runtime.mutableIntStateOf(0)

    /** Saves [spec] as the own theme [theme]; returns it as read back. */
    fun save(theme: Theme, spec: ThemeSpec): Theme {
        val file = theme.file ?: return theme
        file.writeText(spec.toJson().toString(2))
        forget(file)
        revision++
        return of(theme.id)
    }

    /** A folder for a new own theme named [name] (another when taken). */
    private fun newFolder(name: String, home: File): File {
        val base = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "Theme" }
        var dir = File(folder(home), base)
        var n = 2
        while (dir.exists()) dir = File(folder(home), "$base ${n++}")
        return dir.apply { mkdirs() }
    }

    /** A copy of [theme] (built in, of the gallery or own) among the own ones, named [name], its pictures and fonts with it. */
    fun copy(theme: Theme, name: String, home: File = com.sadurni.attaccaplg.library.LibraryDb.HOME): Theme? = runCatching {
        val dir = newFolder(name, home)
        for (f in theme.spec.imageFiles) theme.picture(f)?.let { File(dir, f).writeBytes(it) }
        val origin = theme.id.takeIf { it.startsWith(ThemeSpec.GALLERY) }?.removePrefix(ThemeSpec.GALLERY)
        val spec = theme.spec.copy(name = name, origin = origin ?: theme.spec.origin, originPrint = null)
        val json = File(dir, dir.name + ".json")
        json.writeText(spec.toJson().toString(2))
        revision++
        of("file:" + json.path)
    }.getOrNull()

    /** Takes an own theme away, with its folder. */
    fun delete(theme: Theme) {
        val file = theme.file ?: return
        file.parentFile.deleteRecursively()
        forget(file)
        revision++
    }

    /** Copies a picture or font into the own [theme]'s folder; returns its name there. */
    fun addFile(theme: Theme, source: File): String? = runCatching {
        val dir = theme.file?.parentFile ?: return null
        val base = source.nameWithoutExtension.replace(Regex("[^A-Za-z0-9._ -]"), "_").ifEmpty { "picture" }
        val ext = source.extension.lowercase()
        var name = "$base.$ext"
        var n = 2
        while (File(dir, name).exists() && !File(dir, name).readBytes().contentEquals(source.readBytes())) name = "$base-${n++}.$ext"
        source.copyTo(File(dir, name), overwrite = true)
        forget(theme.file)
        name
    }.getOrNull()

    /** The theme as the phone imports it: a zip with its JSON and its pictures and fonts. */
    fun exportZip(theme: Theme, out: File) {
        val safe = theme.name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifEmpty { "Theme" }
        java.util.zip.ZipOutputStream(out.outputStream().buffered()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("$safe.json"))
            zip.write(theme.spec.toJson().toString(2).toByteArray())
            zip.closeEntry()
            for (f in theme.spec.imageFiles) {
                val bytes = theme.picture(f) ?: continue
                zip.putNextEntry(java.util.zip.ZipEntry("$safe/$f"))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    /** The folder of the desktop's own themes (each in a folder of its own, its pictures beside it). */
    fun folder(home: File = com.sadurni.attaccaplg.library.LibraryDb.HOME) = File(home, "themes")

    /** Every theme of the desktop's own (made here or opened from the phone). */
    fun own(home: File = com.sadurni.attaccaplg.library.LibraryDb.HOME): List<Theme> =
        folder(home).listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name.lowercase() }.mapNotNull { dir ->
            dir.listFiles { f -> f.extension.equals("json", true) }?.firstOrNull()?.let { runCatching { of("file:" + it.path) }.getOrNull()?.takeIf { t -> t.file != null } }
        }

    /**
     * A theme file: its pictures next to it, or in a folder of its name (as the phone's themes
     * folder and its exported zips have them).
     */
    fun fromFile(json: File): Theme? = fromJson("file:" + json.path, json.readText(), json) { file ->
        val name = json.name.removeSuffix(".json")
        listOf(File(json.parentFile, file), File(json.parentFile, "$name/$file")).firstOrNull { it.isFile }?.readBytes()
    }

    /**
     * A theme zip exported from the phone (or a theme file), the zip copied among the desktop's
     * own themes; returns the theme's file. Null when there is no theme in it.
     */
    fun import(source: File, home: File = com.sadurni.attaccaplg.library.LibraryDb.HOME): File? = runCatching {
        if (source.extension.lowercase() != "zip") return@runCatching source.takeIf { fromFile(it) != null }
        val dir = File(folder(home), source.nameWithoutExtension.replace(Regex("[\\\\/:*?\"<>|]"), "_"))
        dir.mkdirs()
        var json: File? = null
        java.util.zip.ZipInputStream(source.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name.replace('\\', '/').substringAfterLast('/')
                // Plain names only; nothing outside the theme's folder.
                if (entry.isDirectory || name.isEmpty() || name.startsWith(".") || entry.name.contains("..")) continue
                val file = File(dir, name)
                file.outputStream().use { zip.copyTo(it) }
                if (name.endsWith(".json", ignoreCase = true) && json == null) json = file
            }
        }
        json?.takeIf { fromFile(it) != null }
    }.getOrNull()

    /** A theme file as the phone writes it (all of it; the desktop shows its colours and pictures). */
    fun fromJson(id: String, text: String, file: File? = null, picture: (String) -> ByteArray? = { null }): Theme? = runCatching {
        val spec = ThemeSpec.fromJson(id, JSONObject(text))
        val name = if (spec.name == id) id.substringAfterLast(File.separatorChar).removeSuffix(".json") else spec.name
        Theme(id, name, spec, picture, file)
    }.getOrNull()

    private val pictures = java.util.Collections.synchronizedMap(HashMap<String, androidx.compose.ui.graphics.ImageBitmap?>())

    /** A theme's picture, decoded once. Blocking. */
    fun picture(theme: Theme, file: String): androidx.compose.ui.graphics.ImageBitmap? = pictures.getOrPut(theme.id + "|" + file) {
        theme.picture(file)?.let { bytes ->
            runCatching { org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
        }
    }

    /** The window's colours from a theme's. */
    fun scheme(p: Palette): ColorScheme {
        val bg = Color(p.background)
        val surface = Color(p.surface)
        val text = Color(p.text)
        val accent = Color(p.accent)
        val onAccent = if (accent.luminance() > 0.5f) Color.Black else Color.White
        val base = if (p.dark) darkColorScheme() else lightColorScheme()
        return base.copy(
            primary = accent,
            onPrimary = onAccent,
            primaryContainer = lerp(bg, accent, 0.3f),
            onPrimaryContainer = text,
            secondary = text,
            onSecondary = bg,
            secondaryContainer = lerp(bg, accent, 0.3f),
            onSecondaryContainer = text,
            tertiary = accent,
            background = bg,
            onBackground = text,
            surface = bg,
            onSurface = text,
            surfaceVariant = surface,
            onSurfaceVariant = Color(p.textSecondary),
            surfaceContainerLowest = bg,
            surfaceContainerLow = lerp(bg, surface, 0.6f),
            surfaceContainer = surface,
            surfaceContainerHigh = lerp(surface, text, 0.05f),
            surfaceContainerHighest = lerp(surface, text, 0.1f),
            inverseSurface = text,
            inverseOnSurface = bg,
            outline = lerp(bg, text, 0.4f),
            outlineVariant = lerp(bg, text, 0.3f),
            error = Color(p.danger),
        )
    }
}

/**
 * The window in the theme [themeId] names (following the system's light or dark when the theme
 * does), its accent from [coverPath]'s cover when the theme takes it from there and [coverAccent].
 */
@Composable
fun AppTheme(themeId: String, coverPath: String? = null, coverAccent: Boolean = true, content: @Composable () -> Unit) {
    Themes.revision // follows edits of the theme in use
    val theme = Themes.of(themeId)
    var palette = theme.palette(isSystemInDarkTheme())
    val cover = if (coverAccent && theme.coverAccent) coverOf(coverPath, 64) else null
    val accent = remember(cover, palette.dark) { cover?.let { CoverAccent.of(it, palette.dark) } }
    if (accent != null) palette = palette.copy(accent = accent)
    // The right-click menus in the theme's light or dark.
    val menus = if (palette.dark) androidx.compose.foundation.DarkDefaultContextMenuRepresentation
    else androidx.compose.foundation.LightDefaultContextMenuRepresentation
    MaterialTheme(colorScheme = Themes.scheme(palette)) {
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.LocalContextMenuRepresentation provides menus, content = content)
    }
}

/**
 * The theme's background picture for [page] (as the phone's screens have them), softened with the
 * background colour; nothing when the theme has none.
 */
@Composable
fun ThemeBackground(themeId: String, page: Page) {
    Themes.revision
    val theme = Themes.of(themeId)
    val section = when (page) {
        Page.PLAYING -> "PLAYER"
        Page.MUSIC -> "FOLDERS"
        Page.LISTS -> "LISTS"
        Page.SOUND -> "SOUND"
        Page.SETTINGS -> "SETTINGS"
        else -> "OTHERS"
    }
    val image = theme.background(section) ?: return
    val bg = MaterialTheme.colorScheme.background
    val dark = bg.luminance() < 0.5f
    androidx.compose.foundation.layout.BoxWithConstraints(androidx.compose.ui.Modifier.fillMaxSize()) {
        val file = image.fileFor(dark, wide = maxWidth > maxHeight)
        val bitmap = androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, theme.id, file, Themes.revision) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Themes.picture(theme, file) }
        }.value ?: return@BoxWithConstraints
        androidx.compose.foundation.Image(
            bitmap, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = androidx.compose.ui.Modifier.fillMaxSize(),
        )
        val dim = image.dimFor(dark)
        if (dim > 0) androidx.compose.foundation.layout.Box(
            androidx.compose.ui.Modifier.fillMaxSize().background(bg.copy(alpha = dim / 100f)),
        )
    }
}

/** A vivid colour from a cover, kept readable on the theme's background (null for grey covers). */
object CoverAccent {
    fun of(image: androidx.compose.ui.graphics.ImageBitmap, darkBackground: Boolean): Long? {
        val pixels = image.toPixelMap()
        // Hues in 24 bins, each weighed by how vivid its pixels are; the heaviest one wins.
        val weight = FloatArray(24)
        val hue = FloatArray(24)
        val sat = FloatArray(24)
        val light = FloatArray(24)
        var total = 0f
        for (y in 0 until pixels.height step 2) for (x in 0 until pixels.width step 2) {
            val c = pixels[x, y]
            val hsl = hsl(c.red, c.green, c.blue)
            val w = hsl[1] * hsl[1] * (1f - kotlin.math.abs(hsl[2] - 0.5f) * 2f).coerceAtLeast(0f)
            total += 1f
            if (w <= 0f) continue
            val bin = ((hsl[0] / 360f * 24).toInt()).coerceIn(0, 23)
            weight[bin] += w
            hue[bin] += hsl[0] * w
            sat[bin] += hsl[1] * w
            light[bin] += hsl[2] * w
        }
        val best = weight.indices.maxByOrNull { weight[it] } ?: return null
        val w = weight[best]
        if (w <= 0f || w / total < 0.01f) return null
        val s = sat[best] / w
        if (s < 0.15f) return null // grey art: the theme's own accent
        val l = (light[best] / w).let { if (darkBackground) it.coerceIn(0.45f, 0.7f) else it.coerceIn(0.3f, 0.5f) }
        return 0xFF000000 or rgb(hue[best] / w, s, l).toLong()
    }

    private fun hsl(r: Float, g: Float, b: Float): FloatArray {
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val l = (max + min) / 2f
        if (max == min) return floatArrayOf(0f, 0f, l)
        val d = max - min
        val s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
        val h = when (max) {
            r -> ((g - b) / d + (if (g < b) 6f else 0f))
            g -> ((b - r) / d + 2f)
            else -> ((r - g) / d + 4f)
        } * 60f
        return floatArrayOf(h, s, l)
    }

    private fun rgb(h: Float, s: Float, l: Float): Int {
        val c = (1f - kotlin.math.abs(2f * l - 1f)) * s
        val x = c * (1f - kotlin.math.abs((h / 60f) % 2f - 1f))
        val m = l - c / 2f
        val (r, g, b) = when ((h / 60f).toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun ch(v: Float) = ((v + m) * 255f).toInt().coerceIn(0, 255)
        return (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }
}

/** Choosing the theme: the built-in ones, the gallery's, or a theme file exported from the phone. */
@Composable
fun ThemeChoice(state: AppState) {
    fun choose(id: String) {
        state.theme = id
        state.prefs["theme"] = id
    }
    androidx.compose.foundation.layout.Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
    ) {
        val current = Themes.of(state.theme)
        val own = Themes.own()
        val options = Themes.builtIns + Themes.gallery + own + listOfNotNull(current.takeIf { it.id.startsWith("file:") && own.none { o -> o.id == it.id } })
        Choice(tr("Theme", "Tema"), options.first { it.id == current.id }, options, { it.name }, { choose(it.id) })
        androidx.compose.material3.OutlinedButton(onClick = {
            Dialogs.open(tr("A theme exported from Attacca", "Un tema exportado de Attacca"), state.prefs["theme_dir"], "*.zip;*.json")?.let { file ->
                state.prefs["theme_dir"] = file.parent
                val json = Themes.import(file)
                if (json == null) state.message = tr("That is not a theme.", "Eso no es un tema.") else choose("file:" + json.path)
            }
        }) { androidx.compose.material3.Text(tr("Open a theme…", "Abrir un tema…")) }
    }
    if (Themes.of(state.theme).id in Themes.builtIns.map { it.id }) {
        androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            androidx.compose.material3.Switch(state.coverAccent, { on -> state.coverAccent = on; state.prefs["cover_accent"] = on.toString() })
            androidx.compose.material3.Text("  " + tr("The accent colour from the playing track's cover", "El color de acento de la carátula del tema que suena"))
        }
    }
    Hint(tr(
        "The phone's themes, with their colours and background pictures (a theme exported from the phone is a zip). Make and change themes for the phone and the desktop in Themes.",
        "Los temas del teléfono, con sus colores y sus imágenes de fondo (un tema exportado del teléfono es un zip). Crea y cambia temas para el teléfono y el escritorio en Temas.",
    ))
}
