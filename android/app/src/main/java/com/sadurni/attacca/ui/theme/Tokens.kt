package com.sadurni.attacca.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The text sizes of the app, by role: every text uses one of these. */
object AttaccaType {
    /** Fine print: counters, units, list summaries. */
    val caption = 12.sp

    /** Secondary lines: subtitles, hints, section titles. */
    val secondary = 13.sp

    /** Main text of rows, settings and dialogs. */
    val body = 16.sp

    /** Titles inside a screen: help topics, side menu entries, dialog headings. */
    val heading = 18.sp

    /** Screen titles in the header. */
    val title = 20.sp

    /** Large texts of Now playing (artist, album). */
    val large = 24.sp

    /** The track title in Now playing. */
    val display = 26.sp
}

/** The theme's corner roundness, 1 = as designed (provided by [AttaccaTheme]). */
val LocalCornerScale = androidx.compose.runtime.staticCompositionLocalOf { 1f }

/** Corner radii: small parts, cards and large surfaces, times the roundness of the theme around. */
object AttaccaShapes {
    /** Badges, thumbnails, chips. */
    val small: RoundedCornerShape
        @androidx.compose.runtime.Composable @androidx.compose.runtime.ReadOnlyComposable
        get() = RoundedCornerShape((8 * LocalCornerScale.current).dp)

    /** Cards, boxes, buttons, bubbles. */
    val medium: RoundedCornerShape
        @androidx.compose.runtime.Composable @androidx.compose.runtime.ReadOnlyComposable
        get() = RoundedCornerShape((16 * LocalCornerScale.current).dp)

    /** The cover and other large surfaces. */
    val large: RoundedCornerShape
        @androidx.compose.runtime.Composable @androidx.compose.runtime.ReadOnlyComposable
        get() = RoundedCornerShape((28 * LocalCornerScale.current).dp)
}

/** Bumped when a theme file changes, so the theme in use is read again. */
object ThemeRuntime {
    var version by androidx.compose.runtime.mutableIntStateOf(0)
}
