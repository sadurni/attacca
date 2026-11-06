package com.sadurni.attacca.ui.common

import com.sadurni.attacca.ui.theme.AttaccaType
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sadurni.attacca.R

fun formatTime(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0) + 500) / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

/** "Attacca: what the word means in music", in italics: under the name wherever the app presents itself. */
@Composable
fun AppTagline(modifier: Modifier = Modifier, fontSize: androidx.compose.ui.unit.TextUnit = com.sadurni.attacca.ui.theme.AttaccaType.secondary, centered: Boolean = false) {
    Text(
        stringResource(R.string.app_tagline),
        fontSize = fontSize,
        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
        color = com.sadurni.attacca.ui.theme.LocalAttaccaColors.current.textGrayed,
        textAlign = if (centered) androidx.compose.ui.text.style.TextAlign.Center else null,
        modifier = modifier,
    )
}

/** The app's motto, as a quote: what it offers in one line, wherever it presents itself. */
@Composable
fun AppSlogan(modifier: Modifier = Modifier, centered: Boolean = false) {
    Text(
        stringResource(R.string.app_slogan),
        fontSize = com.sadurni.attacca.ui.theme.AttaccaType.body,
        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        color = com.sadurni.attacca.ui.theme.LocalAccent.current,
        textAlign = if (centered) androidx.compose.ui.text.style.TextAlign.Center else null,
        modifier = modifier,
    )
}

/**
 * Opens the side menu; provided around the main sections (the ones in the side menu), whose top
 * level shows ☰ instead of ← in its header. Back still works through the system gesture/button.
 */
val LocalSectionMenu = androidx.compose.runtime.staticCompositionLocalOf<(() -> Unit)?> { null }

/**
 * The header of a screen: ← (back) or, at the top level of a main section, ☰ (side menu); the
 * title and the [actions]. [root] is false inside a section (a settings page, a subfolder…).
 */
@Composable
fun ScreenHeader(title: String, onBack: () -> Unit, root: Boolean = true, actions: @Composable () -> Unit = {}) {
    val menu = LocalSectionMenu.current
    Row(
        Modifier.fillMaxWidth().padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (root && menu != null) {
            IconButton(onClick = menu) {
                Icon(Icons.Rounded.Menu, contentDescription = stringResource(R.string.action_navigator))
            }
        } else {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
            }
        }
        Text(
            title,
            fontSize = AttaccaType.title,
            fontFamily = com.sadurni.attacca.ui.theme.LocalTitleFamily.current,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        actions()
    }
}
