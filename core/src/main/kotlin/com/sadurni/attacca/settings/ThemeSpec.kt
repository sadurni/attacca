package com.sadurni.attacca.settings

import org.json.JSONObject

/*
 * The theme format, shared by the phone and Attacca Desktop (which edits themes for both): a theme
 * is a JSON file with its pictures and fonts beside it (see the phone's ThemeStore, and the
 * desktop's Themes). Pure Kotlin: no Android, no desktop.
 */

/** The typeface of a theme (families every phone has). */
enum class ThemeFont { SYSTEM, SERIF, MONOSPACE, CURSIVE }

/** What Now playing shows behind its content. */
enum class PlayerBackground { PLAIN, GRADIENT, BLURRED_COVER }

/** The colors of one version (light or dark) of a theme. */
data class ThemePalette(
    val dark: Boolean,
    val background: Long,
    val surface: Long,
    val text: Long,
    val textSecondary: Long,
    val accent: Long,
    val excluded: Long,
    val danger: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("dark", dark)
        .put("background", ThemeSpec.hex(background))
        .put("surface", ThemeSpec.hex(surface))
        .put("text", ThemeSpec.hex(text))
        .put("textSecondary", ThemeSpec.hex(textSecondary))
        .put("accent", ThemeSpec.hex(accent))
        .put("excluded", ThemeSpec.hex(excluded))
        .put("danger", ThemeSpec.hex(danger))

    companion object {
        fun fromJson(o: JSONObject): ThemePalette {
            val dark = o.optBoolean("dark", true)
            val base = if (dark) ThemeSpec.BLACK else ThemeSpec.WHITE
            fun color(key: String, default: Long) = ThemeSpec.parseColor(o.optString(key, ""), default)
            return ThemePalette(
                dark,
                color("background", base.background),
                color("surface", base.surface),
                color("text", base.text),
                color("textSecondary", base.textSecondary),
                color("accent", base.accent),
                color("excluded", base.excluded),
                color("danger", base.danger),
            )
        }
    }
}

/** The icons of the app a theme can replace (the folder one, for instance, wherever it shows). */
enum class AppIcon { FOLDER, PLAYLIST, TRACK, NO_COVER, PLAYER, LISTS, BOOKMARKS, SOUND, DEVICES, SETTINGS }

/** The screens a theme can give a background image ([OTHERS]: every screen without its own, not Now playing). */
enum class ThemeSection { PLAYER, FOLDERS, LISTS, QUEUE, BOOKMARKS, SOUND, DEVICES, SETTINGS, OTHERS }

/**
 * A picture of a theme: a file in the theme's images folder ([ThemeStore.imagesDir]). [dim] (%)
 * softens it with the theme's background color so text reads well on it; [tint] draws an icon in
 * the theme's colors (its shape only). A theme with both versions can show another picture with
 * its light colors ([fileLight]: a sunny day for a starry night, say).
 */
data class ThemeImage(
    /** The picture (with dark colors, when [fileLight] is set). */
    val file: String,
    /** Softening with dark colors (the only one of a dark theme). */
    val dim: Int = 0,
    val tint: Boolean = false,
    /** Softening with light colors: a picture often needs more to read dark text on it. */
    val dimLight: Int = dim,
    /** The picture with light colors; null: the same as with dark ones. */
    val fileLight: String? = null,
    /** The picture in landscape (with dark colors when [fileWideLight] is set); null: the same as upright. */
    val fileWide: String? = null,
    /** The picture in landscape with light colors; null: [fileWide]. */
    val fileWideLight: String? = null,
) {
    /** The softening for colors that are [dark] or light. */
    fun dimFor(dark: Boolean) = if (dark) dim else dimLight

    fun withDim(dark: Boolean, value: Int) = if (dark) copy(dim = value) else copy(dimLight = value)

    /** The picture for colors that are [dark] or light, upright or in landscape ([wide]). */
    fun fileFor(dark: Boolean, wide: Boolean = false): String {
        val upright = if (dark) file else fileLight ?: file
        if (!wide || fileWide == null) return upright
        return if (dark) fileWide else fileWideLight ?: fileWide
    }

    /** Every picture of it. */
    val files: List<String> get() = listOfNotNull(file, fileLight, fileWide, fileWideLight)

    fun toJson(): JSONObject = JSONObject().put("file", file).put("dim", dim).put("dimLight", dimLight).put("tint", tint)
        .also { o -> fileLight?.let { o.put("fileLight", it) } }
        .also { o -> fileWide?.let { o.put("fileWide", it) } }
        .also { o -> fileWideLight?.let { o.put("fileWideLight", it) } }

    companion object {
        const val MAX_DIM = 90

        /** A plain name in the theme's folder, nothing else. */
        private fun plain(name: String) = name.isNotBlank() && !name.contains('/') && !name.contains('\\') && !name.contains("..")

        fun fromJson(o: JSONObject?): ThemeImage? {
            val file = o?.optString("file").orEmpty()
            if (!plain(file)) return null
            val dim = o!!.optInt("dim", 0).coerceIn(0, MAX_DIM)
            return ThemeImage(
                file, dim, o.optBoolean("tint", false), o.optInt("dimLight", dim).coerceIn(0, MAX_DIM),
                o.optString("fileLight").takeIf(::plain),
                o.optString("fileWide").takeIf(::plain),
                o.optString("fileWideLight").takeIf(::plain),
            )
        }
    }
}

/**
 * A theme: every color and measure the app's look is made of. The built-in White, Dark and Black
 * themes are fixed; the user's own are JSON files in the themes folder ([ThemeStore]), so they can
 * be copied, shared and backed up like lists.
 *
 * Colors are ARGB.
 */
data class ThemeSpec(
    /** `builtin:white|dark|black`, or the file name (without extension) of a user's theme. */
    val id: String,
    val name: String,
    /** A dark theme (light text on a dark background): system bars and the cover accent follow it. */
    val dark: Boolean,
    val background: Long,
    val surface: Long,
    val text: Long,
    val textSecondary: Long,
    /** Accent when the cover gives none, or always with [coverAccent] off. */
    val accent: Long,
    /** Accent taken from the cover of the playing track. */
    val coverAccent: Boolean = true,
    /** Mark of the tracks the inclusion list leaves out. */
    val excluded: Long = 0xFFFF9800,
    /** Destructive marks (rules that take out). */
    val danger: Long = 0xFFE05555,
    /** Corner roundness, 1 = as designed (0.5 squarer … 1.5 rounder). */
    val corners: Float = 1f,
    /** Text size, 1 = as designed (on top of the phone's own setting). */
    val textScale: Float = 1f,
    val font: ThemeFont = ThemeFont.SYSTEM,
    /** Own typeface for the text (a TTF/OTF file in the theme's folder); null: [font]. */
    val textFont: String? = null,
    /** Own typeface for titles; null: the text's. */
    val titleFont: String? = null,
    val playerBackground: PlayerBackground = PlayerBackground.PLAIN,
    /** The parts of Now playing, top to bottom. */
    val layout: PlayerLayout = PlayerLayout.DEFAULT,
    /** Background images by screen. */
    val backgrounds: Map<ThemeSection, ThemeImage> = emptyMap(),
    /** Own icons for parts of the app. */
    val icons: Map<AppIcon, ThemeImage> = emptyMap(),
    /** Now playing in landscape: null lays out [layout] by itself (cover and list box left, the rest right). */
    val landscape: PlayerLayout? = null,
    /**
     * The colors of the other version (light when this is dark, or dark): with both, the theme takes
     * the one the phone is set to ([forSystem]). Everything else is shared.
     */
    val alternate: ThemePalette? = null,
    /** The gallery theme this one was copied from (its asset name), see [ThemeStore.adopt]. */
    val origin: String? = null,
    /** [contentPrint] when copied: while it still matches, the copy is the gallery theme untouched. */
    val originPrint: String? = null,
) {
    /** A fingerprint of everything the theme is, name included (not where it came from). */
    fun contentPrint(): String {
        val json = toJson().apply { remove("origin"); remove("originPrint") }.toString()
        return java.security.MessageDigest.getInstance("SHA-256").digest(json.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /** The gallery theme this is, as it comes or as an unedited copy (asset name); null otherwise. */
    val untouchedOrigin: String? by lazy {
        if (original) id.removePrefix(GALLERY) else origin?.takeIf { originPrint == contentPrint() }
    }

    /** The colors in use. */
    val palette: ThemePalette
        get() = ThemePalette(dark, background, surface, text, textSecondary, accent, excluded, danger)

    /** This theme with the colors of [p]. */
    fun withPalette(p: ThemePalette): ThemeSpec = copy(
        dark = p.dark, background = p.background, surface = p.surface, text = p.text,
        textSecondary = p.textSecondary, accent = p.accent, excluded = p.excluded, danger = p.danger,
    )

    /** The other version in use, this one kept as the other. */
    fun swapped(): ThemeSpec = alternate?.let { withPalette(it).copy(alternate = palette) } ?: this

    /** The version for a phone in dark mode or not (themes with one version are as they are). */
    fun forSystem(systemDark: Boolean): ThemeSpec =
        if (alternate != null && dark != systemDark && alternate.dark == systemDark) swapped() else this

    /** With both versions: the light one made from this one (or the dark one), same hues. */
    fun withBothVersions(): ThemeSpec = if (alternate != null) this else copy(alternate = converted(!dark).palette)

    /** The background image of [section]: its own, else the one for the other screens (not for Now playing). */
    fun backgroundOf(section: ThemeSection): ThemeImage? =
        backgrounds[section] ?: backgrounds[ThemeSection.OTHERS].takeIf { section != ThemeSection.PLAYER }

    /** Every image file the theme uses. */
    val imageFiles: Set<String>
        get() = (backgrounds.values.flatMap { it.files } + icons.values.flatMap { it.files } + listOfNotNull(textFont, titleFont) +
            (layout.allBlocks + landscape?.allBlocks.orEmpty()).flatMap { b -> b.icons.values.flatMap { it.files } + b.image?.files.orEmpty() }).toSet()

    val builtIn: Boolean get() = id.startsWith(BUILTIN)

    /** One of the themes that come with the app, as it comes (see [ThemeStore.gallery]): used through a copy. */
    val original: Boolean get() = id.startsWith(GALLERY)

    /**
     * The same theme turned [toDark] (or light): background, boxes and texts take the opposite
     * lightness with the same hue, saturation and opacity; the accent and the marks stay.
     */
    fun converted(toDark: Boolean): ThemeSpec {
        if (toDark == dark) return this
        return copy(
            dark = toDark,
            background = invertLightness(background),
            surface = invertLightness(surface),
            text = invertLightness(text),
            textSecondary = invertLightness(textSecondary),
        )
    }

    fun toJson(): JSONObject = JSONObject()
        .put("format", 1)
        .put("name", name)
        .put("dark", dark)
        .put("background", hex(background))
        .put("surface", hex(surface))
        .put("text", hex(text))
        .put("textSecondary", hex(textSecondary))
        .put("accent", hex(accent))
        .put("coverAccent", coverAccent)
        .put("excluded", hex(excluded))
        .put("danger", hex(danger))
        .put("corners", corners.toDouble())
        .put("textScale", textScale.toDouble())
        .put("font", font.name)
        .put("textFont", textFont)
        .put("titleFont", titleFont)
        .put("playerBackground", playerBackground.name)
        .put("playerLayout", layout.toJson())
        .also { o -> landscape?.let { o.put("playerLayoutLandscape", it.toJson()) } }
        .also { o -> if (icons.isNotEmpty()) o.put("icons", JSONObject().also { i -> icons.forEach { (k, v) -> i.put(k.name, v.toJson()) } }) }
        .put("backgrounds", JSONObject().also { o -> backgrounds.forEach { (k, v) -> o.put(k.name, v.toJson()) } })
        .also { o -> alternate?.let { o.put("alternate", it.toJson()) } }
        .also { o -> origin?.let { o.put("origin", it).put("originPrint", originPrint) } }

    companion object {
        const val BUILTIN = "builtin:"

        /** Id prefix of the themes that come with the app, as they come. */
        const val GALLERY = "gallery:"

        val WHITE = ThemeSpec(
            "${BUILTIN}white", "White", dark = false,
            background = 0xFFFFFFFF, surface = 0xFFF1F3F4, text = 0xFF1D2F3C, textSecondary = 0xB31D2F3C, accent = 0xFFFF3B30,
        )
        val DARK = ThemeSpec(
            "${BUILTIN}dark", "Dark", dark = true,
            background = 0xFF202124, surface = 0xFF303134, text = 0xFFFFFFFF, textSecondary = 0xA6FFFFFF, accent = 0xFFFF453A,
        )
        val BLACK = ThemeSpec(
            "${BUILTIN}black", "Black", dark = true,
            background = 0xFF000000, surface = 0xFF1E1E1E, text = 0xFFFFFFFF, textSecondary = 0xA6FFFFFF, accent = 0xFFFF453A,
        )
        /**
         * The usual layout with the track's mood and sound family in small under the album, in the
         * title box (left out while they are not known).
         */
        val MOOD_LAYOUT = PlayerLayout(
            PlayerLayout.DEFAULT.blocks.map { b ->
                if (b.type == BlockType.TITLE) b.copy(lines = BoxLine.TITLE_BOX + BoxLine(LineKind.TEMPLATE, "[{mood}][ · {family}]", TextSize.SMALL)) else b
            },
        )

        /** White by day, Black by night: as the phone. */
        val SYSTEM = WHITE.copy(id = "${BUILTIN}system", name = "System", alternate = BLACK.palette, layout = MOOD_LAYOUT)

        /** White by day, Dark (gray) by night. */
        val SYSTEM_DARK = WHITE.copy(id = "${BUILTIN}system_dark", name = "System (Dark)", alternate = DARK.palette, layout = MOOD_LAYOUT)
        val BUILT_INS = listOf(SYSTEM, SYSTEM_DARK, WHITE, DARK, BLACK)

        /** The built-in themes offered in the list: both versions each (the single ones stay for who had them chosen). */
        val OFFERED = listOf(SYSTEM, SYSTEM_DARK)

        fun hex(color: Long): String = "#%08X".format(color and 0xFFFFFFFFL)

        /** [color] with its HSL lightness turned around (black ↔ white, dark navy ↔ pale blue). */
        fun invertLightness(color: Long): Long {
            val a = (color shr 24) and 0xFF
            val r = ((color shr 16) and 0xFF) / 255.0
            val g = ((color shr 8) and 0xFF) / 255.0
            val b = (color and 0xFF) / 255.0
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val l = (max + min) / 2
            val d = max - min
            val s = if (d == 0.0) 0.0 else d / (1 - kotlin.math.abs(2 * l - 1))
            val h = when {
                d == 0.0 -> 0.0
                max == r -> 60 * (((g - b) / d).mod(6.0))
                max == g -> 60 * ((b - r) / d + 2)
                else -> 60 * ((r - g) / d + 4)
            }
            // Back from HSL with the lightness inverted.
            val l2 = 1 - l
            val c = (1 - kotlin.math.abs(2 * l2 - 1)) * s
            val x = c * (1 - kotlin.math.abs((h / 60).mod(2.0) - 1))
            val m = l2 - c / 2
            val (r1, g1, b1) = when {
                h < 60 -> Triple(c, x, 0.0)
                h < 120 -> Triple(x, c, 0.0)
                h < 180 -> Triple(0.0, c, x)
                h < 240 -> Triple(0.0, x, c)
                h < 300 -> Triple(x, 0.0, c)
                else -> Triple(c, 0.0, x)
            }
            fun channel(v: Double) = (((v + m) * 255) + 0.5).toLong().coerceIn(0, 255)
            return (a shl 24) or (channel(r1) shl 16) or (channel(g1) shl 8) or channel(b1)
        }

        fun parseColor(raw: String?, default: Long): Long {
            val digits = raw?.trim()?.removePrefix("#") ?: return default
            val value = digits.toLongOrNull(16) ?: return default
            return when (digits.length) {
                6 -> 0xFF000000 or value
                8 -> value
                else -> default
            }
        }

        /** A theme from its file; missing values come from the built-in theme of the same kind. */
        fun fromJson(id: String, json: JSONObject): ThemeSpec {
            val dark = json.optBoolean("dark", true)
            val base = if (dark) BLACK else WHITE
            fun color(key: String, default: Long) = parseColor(json.optString(key, ""), default)
            return ThemeSpec(
                id = id,
                name = json.optString("name").ifBlank { id },
                dark = dark,
                background = color("background", base.background),
                surface = color("surface", base.surface),
                text = color("text", base.text),
                textSecondary = color("textSecondary", base.textSecondary),
                accent = color("accent", base.accent),
                coverAccent = json.optBoolean("coverAccent", true),
                excluded = color("excluded", base.excluded),
                danger = color("danger", base.danger),
                corners = json.optDouble("corners", 1.0).toFloat().coerceIn(MIN_CORNERS, MAX_CORNERS),
                textScale = json.optDouble("textScale", 1.0).toFloat().coerceIn(MIN_TEXT, MAX_TEXT),
                font = ThemeFont.entries.firstOrNull { it.name == json.optString("font") } ?: ThemeFont.SYSTEM,
                textFont = json.optString("textFont").takeIf { it.isNotEmpty() },
                titleFont = json.optString("titleFont").takeIf { it.isNotEmpty() },
                playerBackground = PlayerBackground.entries.firstOrNull { it.name == json.optString("playerBackground") } ?: PlayerBackground.PLAIN,
                layout = PlayerLayout.fromJson(json.optJSONArray("playerLayout")),
                icons = json.optJSONObject("icons")?.let { o ->
                    AppIcon.entries.mapNotNull { s -> ThemeImage.fromJson(o.optJSONObject(s.name))?.let { s to it } }.toMap()
                }.orEmpty(),
                landscape = json.optJSONArray("playerLayoutLandscape")?.takeIf { it.length() > 0 }?.let(PlayerLayout::fromJson),
                // A second version of the other kind only (two of the same would never show).
                alternate = json.optJSONObject("alternate")?.let(ThemePalette::fromJson)?.takeIf { it.dark != dark },
                origin = json.optString("origin").takeIf { it.isNotEmpty() },
                originPrint = json.optString("originPrint").takeIf { it.isNotEmpty() },
                backgrounds = json.optJSONObject("backgrounds")?.let { o ->
                    ThemeSection.entries.mapNotNull { s -> ThemeImage.fromJson(o.optJSONObject(s.name))?.let { s to it } }.toMap()
                }.orEmpty(),
            )
        }

        const val MIN_CORNERS = 0.25f
        const val MAX_CORNERS = 1.75f
        const val MIN_TEXT = 0.85f
        const val MAX_TEXT = 1.3f
    }
}
