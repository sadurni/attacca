package com.sadurni.attacca.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import com.sadurni.attacca.settings.ThemeFont
import com.sadurni.attacca.settings.ThemeSpec

/** The colors of the theme in use (see [ThemeSpec]). */
@Immutable
data class AttaccaColors(
    val background: Color,
    val surface: Color,
    val text: Color,
    val textGrayed: Color,
    val defaultAccent: Color,
    val isDark: Boolean,
    /** Mark of the tracks the inclusion list leaves out. */
    val excluded: Color = Color(0xFFFF9800),
    /** Destructive marks (rules that take out). */
    val danger: Color = Color(0xFFE05555),
) {
    companion object {
        fun of(spec: ThemeSpec) = AttaccaColors(
            background = Color(spec.background),
            surface = Color(spec.surface),
            text = Color(spec.text),
            textGrayed = Color(spec.textSecondary),
            defaultAccent = Color(spec.accent),
            isDark = spec.dark,
            excluded = Color(spec.excluded),
            danger = Color(spec.danger),
        )
    }
}

val BlackColors = AttaccaColors.of(ThemeSpec.BLACK)

val LocalAttaccaColors = staticCompositionLocalOf { BlackColors }

/** Current accent: from the album art when available, else the theme's default. */
val LocalAccent = staticCompositionLocalOf { BlackColors.defaultAccent }

/** The theme's own typeface for titles (screen headers, section titles, the track title), or null. */
val LocalTitleFamily = staticCompositionLocalOf<androidx.compose.ui.text.font.FontFamily?> { null }

/** The theme in use, for what draws differently by theme (the player background). */
val LocalThemeSpec = staticCompositionLocalOf { ThemeSpec.BLACK }

private fun ThemeFont.family(): FontFamily = when (this) {
    ThemeFont.SYSTEM -> FontFamily.Default
    ThemeFont.SERIF -> FontFamily.Serif
    ThemeFont.MONOSPACE -> FontFamily.Monospace
    ThemeFont.CURSIVE -> FontFamily.Cursive
}

/** Material's type scale with titles in [titles] and the rest in [family]. */
private fun typography(family: FontFamily, titles: FontFamily = family): Typography {
    val t = Typography()
    fun TextStyle.f() = copy(fontFamily = family)
    fun TextStyle.h() = copy(fontFamily = titles)
    return Typography(
        displayLarge = t.displayLarge.h(), displayMedium = t.displayMedium.h(), displaySmall = t.displaySmall.h(),
        headlineLarge = t.headlineLarge.h(), headlineMedium = t.headlineMedium.h(), headlineSmall = t.headlineSmall.h(),
        titleLarge = t.titleLarge.h(), titleMedium = t.titleMedium.h(), titleSmall = t.titleSmall.h(),
        bodyLarge = t.bodyLarge.f(), bodyMedium = t.bodyMedium.f(), bodySmall = t.bodySmall.f(),
        labelLarge = t.labelLarge.f(), labelMedium = t.labelMedium.f(), labelSmall = t.labelSmall.f(),
    )
}

/**
 * Applies [spec] to everything inside: colors, the accent ([artworkAccent] when the theme takes
 * it from the cover), corner roundness, text size and typeface.
 */
@Composable
fun AttaccaTheme(
    spec: ThemeSpec,
    artworkAccent: Color?,
    content: @Composable () -> Unit,
) {
    val colors = AttaccaColors.of(spec)
    val accent by animateColorAsState(
        targetValue = artworkAccent?.takeIf { spec.coverAccent } ?: colors.defaultAccent,
        animationSpec = tween(600),
        label = "accent",
    )
    val scheme = if (colors.isDark) {
        darkColorScheme(
            primary = accent,
            onPrimary = Color.White,
            background = colors.background,
            onBackground = colors.text,
            surface = colors.background,
            onSurface = colors.text,
            surfaceVariant = colors.surface,
            onSurfaceVariant = colors.textGrayed,
            surfaceContainer = colors.surface,
            surfaceContainerHigh = colors.surface,
            secondaryContainer = accent.copy(alpha = 0.3f),
        )
    } else {
        lightColorScheme(
            primary = accent,
            onPrimary = Color.White,
            background = colors.background,
            onBackground = colors.text,
            surface = colors.background,
            onSurface = colors.text,
            surfaceVariant = colors.surface,
            onSurfaceVariant = colors.textGrayed,
            surfaceContainer = colors.surface,
            surfaceContainerHigh = colors.surface,
            secondaryContainer = accent.copy(alpha = 0.3f),
        )
    }
    val density = LocalDensity.current
    // The theme's own font files when it has them (titles take the text's when they have none).
    val titleFamily = remember(spec.id, spec.titleFont) { spec.titleFont?.let { ThemeFonts.family(spec.id, it) } }
    val typography = remember(spec.id, spec.font, spec.textFont, titleFamily) {
        val text = spec.textFont?.let { ThemeFonts.family(spec.id, it) } ?: spec.font.family()
        typography(text, titleFamily ?: text)
    }
    MaterialTheme(colorScheme = scheme, typography = typography) {
        // Text and icons take LocalContentColor, which is black unless something provides it;
        // the screens draw straight on the background, so provide the theme's text color here.
        // The theme's text size multiplies the phone's own.
        CompositionLocalProvider(
            LocalAttaccaColors provides colors,
            LocalAccent provides accent,
            LocalContentColor provides colors.text,
            LocalThemeSpec provides spec,
            LocalTitleFamily provides titleFamily,
            LocalCornerScale provides spec.corners,
            LocalDensity provides Density(density.density, density.fontScale * spec.textScale),
            content = content,
        )
    }
}
