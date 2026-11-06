package com.sadurni.attacca.ui.common

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Where an [Icon] inside an [IconButton] tells the button what it stands for, so the button can
 * show it on a long press without every caller repeating the text.
 */
private val LocalIconLabel = compositionLocalOf<((String) -> Unit)?> { null }

/** The material icon, telling an enclosing [IconButton] its description. */
@Composable
fun Icon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    val report = LocalIconLabel.current
    if (report != null && contentDescription != null) SideEffect { report(contentDescription) }
    androidx.compose.material3.Icon(imageVector, contentDescription, modifier, tint)
}

/**
 * The material icon button with a name: a long press shows what it does ([label], or the
 * description of the [Icon] inside), or does [onLongClick] when given.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: String? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    if (onLongClick != null) {
        // Same size and ripple as the material button, with a long press of its own.
        androidx.compose.foundation.layout.Box(
            modifier
                .minimumInteractiveComponentSize()
                .size(40.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .combinedClickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick, onLongClick = onLongClick),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            CompositionLocalProvider(LocalContentColor provides if (enabled) LocalContentColor.current else LocalContentColor.current.copy(alpha = 0.38f)) {
                content()
            }
        }
        return
    }
    var reported by remember { mutableStateOf<String?>(null) }
    val text = label ?: reported
    val button = @Composable {
        CompositionLocalProvider(LocalIconLabel provides { reported = it }) {
            androidx.compose.material3.IconButton(onClick = onClick, enabled = enabled, content = content)
        }
    }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { if (text != null) PlainTooltip { Text(text) } },
        state = rememberTooltipState(),
        modifier = modifier,
        enableUserInput = text != null,
    ) { button() }
}

/** The name of a control shown above it, for buttons that handle their own long press. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Labelled(
    label: String?,
    modifier: Modifier = Modifier,
    state: androidx.compose.material3.TooltipState = rememberTooltipState(),
    content: @Composable () -> Unit,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { if (label != null) PlainTooltip { Text(label) } },
        state = state,
        modifier = modifier,
        enableUserInput = false,
    ) { content() }
}
