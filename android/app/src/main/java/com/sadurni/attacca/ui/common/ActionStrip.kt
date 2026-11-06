package com.sadurni.attacca.ui.common

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.sadurni.attacca.ui.theme.LocalAccent
import com.sadurni.attacca.ui.theme.LocalAttaccaColors

/** One direct action of an [ActionStrip]. */
class StripAction(val icon: ImageVector, val label: String, val enabled: Boolean = true, val onClick: () -> Unit)

/**
 * The actions used most on a list, under its title (what used to hide in the ⋮ menu): a row of
 * icons in the accent colour, each one saying what it does on a long press; the row scrolls
 * sideways when they do not fit.
 */
@Composable
fun ActionStrip(actions: List<StripAction>, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    val grayed = LocalAttaccaColors.current.textGrayed
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (action in actions) {
            IconButton(onClick = action.onClick, enabled = action.enabled, label = action.label) {
                Icon(action.icon, contentDescription = action.label, tint = if (action.enabled) accent else grayed)
            }
        }
    }
}
