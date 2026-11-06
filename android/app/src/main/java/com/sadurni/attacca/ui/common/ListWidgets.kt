package com.sadurni.attacca.ui.common

import com.sadurni.attacca.ui.theme.AttaccaShapes
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.sadurni.attacca.R
import com.sadurni.attacca.ui.theme.LocalAccent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * A list with a fast-scroll thumb on the right edge: it shows while scrolling and can be dragged
 * to cross thousands of tracks at once.
 */
@Composable
fun FastScrollBox(state: LazyListState, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier) {
        content()
        FastScrollbar(state, Modifier.align(Alignment.CenterEnd))
    }
}

@Composable
private fun FastScrollbar(state: LazyListState, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var trackHeight by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    val thumbHeight = with(density) { THUMB_HEIGHT.toPx() }

    val total = state.layoutInfo.totalItemsCount
    val shown = state.layoutInfo.visibleItemsInfo.size
    val scrollable = total > shown && total > MIN_ITEMS

    LaunchedEffect(state.isScrollInProgress, dragging) {
        if (state.isScrollInProgress || dragging) {
            visible = true
        } else {
            delay(HIDE_DELAY_MS)
            visible = false
        }
    }
    if (!scrollable) return

    val fraction = (state.firstVisibleItemIndex.toFloat() / (total - shown).coerceAtLeast(1)).coerceIn(0f, 1f)

    fun scrollTo(y: Float) {
        val f = ((y - thumbHeight / 2) / (trackHeight - thumbHeight).coerceAtLeast(1f)).coerceIn(0f, 1f)
        scope.launch { state.scrollToItem((f * (total - shown)).roundToInt().coerceIn(0, total - 1)) }
    }

    Box(
        modifier
            .fillMaxHeight()
            .width(TOUCH_WIDTH)
            .onSizeChanged { trackHeight = it.height.toFloat() }
            // Only grabbable while the thumb shows, so taps on row buttons at the edge still work.
            .then(
                if (visible) {
                    Modifier.pointerInput(total, shown) {
                        detectVerticalDragGestures(
                            onDragStart = {
                                dragging = true
                                scrollTo(it.y)
                            },
                            onDragEnd = { dragging = false },
                            onDragCancel = { dragging = false },
                            onVerticalDrag = { change, _ ->
                                change.consume()
                                scrollTo(change.position.y)
                            },
                        )
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        // Always a thin line showing where the list is; wide and grabbable while scrolling.
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(0, (fraction * (trackHeight - thumbHeight)).roundToInt()) }
                .padding(end = 3.dp)
                .width(
                    when {
                        dragging -> 10.dp
                        visible -> 6.dp
                        else -> 3.dp
                    },
                )
                .height(THUMB_HEIGHT)
                .background(
                    LocalAccent.current.copy(
                        alpha = when {
                            dragging -> 1f
                            visible -> 0.7f
                            else -> 0.35f
                        },
                    ),
                    AttaccaShapes.small,
                ),
        )
    }
}

/** Search box shown under a screen header; focuses itself when it appears. */
@Composable
fun SearchField(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        placeholder = { Text(stringResource(R.string.search)) },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        trailingIcon = {
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cancel)) }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).focusRequester(focus),
    )
}

/** Whether [text] matches every word of [query], ignoring case. */
fun matchesQuery(query: String, vararg text: String): Boolean {
    val words = query.trim().split(' ').filter { it.isNotEmpty() }
    return words.all { word -> text.any { it.contains(word, ignoreCase = true) } }
}

private val THUMB_HEIGHT = 56.dp
private val TOUCH_WIDTH = 28.dp
private const val HIDE_DELAY_MS = 1500L
private const val MIN_ITEMS = 30

/** Exports tracks as an M3U8 file the user places (name without extension, tracks). */
val LocalListExporter = androidx.compose.runtime.staticCompositionLocalOf<(String, List<androidx.media3.common.MediaItem>) -> Unit> {
    { _, _ -> }
}

/** A line under a list: position, number of tracks, total time and size. */
@androidx.compose.runtime.Composable
fun ListFooter(position: Int?, summary: com.sadurni.attacca.library.ListSummary?) {
    if (summary == null) return
    val colors = com.sadurni.attacca.ui.theme.LocalAttaccaColors.current
    val parts = buildList {
        add(if (position != null && position >= 0) "${position + 1} / ${summary.count}" else "${summary.count}")
        add(summary.durationMs?.let(::formatTotalTime) ?: "…")
        add(formatSize(summary.bytes))
    }
    Text(
        parts.joinToString("  ·  "),
        fontSize = androidx.compose.ui.unit.TextUnit(12f, androidx.compose.ui.unit.TextUnitType.Sp),
        color = colors.textGrayed,
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

/** "3 h 05 min", "42 min". */
fun formatTotalTime(ms: Long): String {
    val minutes = (ms + 30_000) / 60_000
    return if (minutes >= 60) "%d h %02d min".format(minutes / 60, minutes % 60) else "$minutes min"
}

/** "12.4 GB", "830 MB". */
fun formatSize(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024) "%.1f GB".format(mb / 1024) else "%.0f MB".format(mb)
}
