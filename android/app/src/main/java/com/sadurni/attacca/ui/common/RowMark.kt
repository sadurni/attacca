package com.sadurni.attacca.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.sadurni.attacca.ui.theme.LocalAccent

/**
 * How a row of a list shows it is selected or marked (the playing track, the one gone to): a fill
 * of the accent colour and a solid accent bar on its left edge, which stays visible whatever the
 * accent (a pale or dark cover colour made the fill alone hard to tell). Selected rows are filled
 * more than marked ones. Other rows stay see-through (the theme's background picture shows).
 */
@Composable
fun Modifier.rowMark(selected: Boolean, marked: Boolean = false): Modifier {
    if (!selected && !marked) return this
    val accent = LocalAccent.current
    val fill = accent.copy(alpha = if (selected) SELECTED_ALPHA else MARKED_ALPHA)
    return drawBehind {
        drawRect(fill)
        drawRect(accent, Offset.Zero, Size(BAR.toPx(), size.height))
    }
}

/** Fill of a selected row: clear enough on light and dark themes, text still readable. */
private const val SELECTED_ALPHA = 0.42f
private const val MARKED_ALPHA = 0.26f
private val BAR = 4.dp
