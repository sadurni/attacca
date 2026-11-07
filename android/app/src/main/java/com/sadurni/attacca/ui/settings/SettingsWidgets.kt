package com.sadurni.attacca.ui.settings

import com.sadurni.attacca.ui.theme.AttaccaType
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sadurni.attacca.ui.theme.LocalAccent
import com.sadurni.attacca.ui.theme.LocalAttaccaColors
import kotlin.math.roundToInt
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import com.sadurni.attacca.ui.common.Icon
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.sadurni.attacca.ui.common.ScreenHeader

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        color = LocalAccent.current,
        fontWeight = FontWeight.Bold,
        fontSize = AttaccaType.body,
        fontFamily = com.sadurni.attacca.ui.theme.LocalTitleFamily.current,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
fun Hint(text: String) {
    if (!LocalFoldedHints.current) {
        Text(
            text,
            fontSize = AttaccaType.secondary,
            color = LocalAttaccaColors.current.textGrayed,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        )
        return
    }
    // One line ending in "…"; a tap shows it whole (and folds it again).
    var open by androidx.compose.runtime.saveable.rememberSaveable(text) { mutableStateOf(false) }
    var cut by remember(text) { mutableStateOf(false) }
    Text(
        text,
        fontSize = AttaccaType.secondary,
        color = LocalAttaccaColors.current.textGrayed,
        maxLines = if (open) Int.MAX_VALUE else 1,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        onTextLayout = { if (!open) cut = it.hasVisualOverflow },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = open || cut) { open = !open }
            .padding(horizontal = 16.dp, vertical = 2.dp),
    )
}

/** Whether [Hint]s show one line that opens on a tap (on settings pages, where they pile up). */
val LocalFoldedHints = androidx.compose.runtime.staticCompositionLocalOf { false }

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = AttaccaType.body, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, fontSize = AttaccaType.body)
    }
}

@Composable
fun <T> SegmentedChoice(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
            ) {
                Text(label)
            }
        }
    }
}

/**
 * Slider that shows its value while dragging and commits it on release, so the audio settings
 * are not rewritten on every pixel of movement.
 */
@Composable
fun ValueSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    format: (Float) -> String,
    onChange: (Float) -> Unit,
    enabled: Boolean = true,
) {
    var dragging by remember { mutableFloatStateOf(Float.NaN) }
    val shown = if (dragging.isNaN()) value else dragging
    val steps = ((range.endInclusive - range.start) / step).roundToInt() - 1
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = AttaccaType.body, modifier = Modifier.weight(1f))
            Text(format(shown), fontSize = AttaccaType.body, style = MaterialTheme.typography.bodyMedium)
        }
        Slider(
            value = shown,
            onValueChange = { dragging = (it / step).roundToInt() * step },
            onValueChangeFinished = {
                if (!dragging.isNaN()) onChange(dragging)
                dragging = Float.NaN
            },
            valueRange = range,
            steps = steps.coerceAtLeast(0),
            enabled = enabled,
            // Fine steps would draw a row of dots; snapping is enough feedback.
            colors = SliderDefaults.colors(
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
                disabledActiveTickColor = Color.Transparent,
                disabledInactiveTickColor = Color.Transparent,
            ),
        )
    }
}

/**
 * The title of a row that does something when tapped (export, scan, analyse…), not a setting:
 * a tonal pill in the accent color, so it reads as a button at a glance; gray while it cannot be
 * done. The whole row stays the touch target.
 */
@Composable
fun ActionText(text: String, enabled: Boolean = true) {
    val color = if (enabled) LocalAccent.current else LocalAttaccaColors.current.textGrayed
    Text(
        text,
        fontSize = AttaccaType.body,
        fontWeight = FontWeight.SemiBold,
        color = color,
        modifier = Modifier
            .background(color.copy(alpha = if (enabled) 0.14f else 0.08f), androidx.compose.foundation.shape.RoundedCornerShape(50))
            .border(1.dp, color.copy(alpha = if (enabled) 0.45f else 0.2f), androidx.compose.foundation.shape.RoundedCornerShape(50))
            .padding(horizontal = 18.dp, vertical = 8.dp),
    )
}

/** A row of a settings menu that opens one category. */
@Composable
fun CategoryRow(icon: ImageVector, title: String, summary: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = LocalAttaccaColors.current.textGrayed)
        Column(Modifier.weight(1f).padding(start = 20.dp)) {
            Text(title, fontSize = AttaccaType.heading)
            if (summary.isNotEmpty()) {
                Text(summary, fontSize = AttaccaType.secondary, color = LocalAttaccaColors.current.textGrayed, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = LocalAttaccaColors.current.textGrayed)
    }
}

/**
 * A settings screen split in categories: the menu of categories, and each category on its own
 * page. Back on a page returns to the menu.
 */
@Composable
fun <T : Enum<T>> CategoryScreen(
    title: String,
    pages: Array<T>,
    pageTitle: @Composable (T) -> String,
    onBack: () -> Unit,
    menu: @Composable ColumnScope.(open: (T) -> Unit) -> Unit,
    initialPage: T? = null,
    page: @Composable ColumnScope.(T) -> Unit,
) {
    var current by rememberSaveable { mutableStateOf(initialPage?.name) }
    // The menu keeps its place while a page is open: back returns to where it was.
    val menuScroll = rememberScrollState()
    val shown = current?.let { name -> pages.firstOrNull { it.name == name } }
    BackHandler(enabled = shown != null) { current = null }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        ScreenHeader(if (shown == null) title else pageTitle(shown), onBack = { if (shown != null) current = null else onBack() }, root = shown == null)
        AnimatedContent(
            targetState = shown,
            transitionSpec = {
                if (targetState != null) {
                    (slideInHorizontally { it } + fadeIn()) togetherWith (slideOutHorizontally { -it / 4 } + fadeOut())
                } else {
                    (slideInHorizontally { -it / 4 } + fadeIn()) togetherWith (slideOutHorizontally { it } + fadeOut())
                }
            },
            label = "settings page",
        ) { target ->
            Column(Modifier.fillMaxSize().verticalScroll(if (target == null) menuScroll else rememberScrollState())) {
                if (target == null) {
                    menu { current = it.name }
                } else {
                    androidx.compose.runtime.CompositionLocalProvider(LocalFoldedHints provides true) { page(target) }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** Names of the enabled items, or "Off" when none is. */
@Composable
fun activeSummary(vararg items: Pair<Boolean, String>): String =
    items.filter { it.first }.joinToString(" · ") { it.second }.ifEmpty { stringResource(com.sadurni.attacca.R.string.off) }
