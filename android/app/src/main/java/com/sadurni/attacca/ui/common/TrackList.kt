package com.sadurni.attacca.ui.common

import com.sadurni.attacca.ui.theme.AttaccaType
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sadurni.attacca.R
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.sadurni.attacca.app
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sadurni.attacca.ui.theme.LocalAccent
import com.sadurni.attacca.ui.theme.LocalAttaccaColors
import kotlin.math.abs

/**
 * Screen-wide horizontal swipe for navigation. It only sees drags no child used (pager, seek bar,
 * cover gestures and list scrolling keep theirs).
 */
fun Modifier.swipeNavigation(onSwipeLeft: (() -> Unit)? = null, onSwipeRight: (() -> Unit)? = null): Modifier =
    pointerInput(onSwipeLeft, onSwipeRight) {
        var total = 0f
        val threshold = 80.dp.toPx()
        detectHorizontalDragGestures(
            onDragStart = { total = 0f },
            onHorizontalDrag = { _, amount -> total += amount },
            onDragEnd = {
                when {
                    abs(total) < threshold -> Unit
                    total < 0 -> onSwipeLeft?.invoke()
                    else -> onSwipeRight?.invoke()
                }
            },
        )
    }

/**
 * One track in a list: position, small cover, title and folder. [selected] non-null shows the
 * selection mark instead of the position.
 */
@OptIn(ExperimentalFoundationApi::class)
/** "Analysing: 12 / 40 · about 3 min left", for background analysis progress. */
@Composable
fun analysisProgressText(label: Int, done: Int, total: Int, remainingMs: Long?): String =
    com.sadurni.attacca.progressText(
        androidx.compose.ui.platform.LocalContext.current.resources, label, done, total, remainingMs, percent = true,
    )

@Composable
fun TrackRow(
    number: Int,
    mediaId: String,
    title: String,
    subtitle: String,
    highlighted: Boolean,
    selected: Boolean?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Length shown at the end of the second line; 0 when unknown (nothing shown). */
    durationMs: Long = 0,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val accent = LocalAccent.current
    val colors = LocalAttaccaColors.current
    val click by rememberUpdatedState(onClick)
    val longClick by rememberUpdatedState(onLongClick)
    Row(
        modifier
            .fillMaxWidth()
            .rowMark(selected = selected == true, marked = highlighted)
            .combinedClickable(onClick = { click() }, onLongClick = { longClick() })
            .padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(36.dp), contentAlignment = Alignment.Center) {
            if (selected != null) {
                Icon(
                    if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (selected) accent else colors.textGrayed,
                )
            } else {
                Text("$number", fontSize = AttaccaType.secondary, color = colors.textGrayed, textAlign = TextAlign.End)
            }
        }
        Box(Modifier.padding(start = 8.dp)) { TrackThumbnail(mediaId) }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ExcludedMark(mediaId)
                Text(
                    title,
                    fontSize = AttaccaType.body,
                    fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(subtitle, fontSize = AttaccaType.secondary, color = colors.textGrayed, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (durationMs > 0) {
                    Text(formatTime(durationMs), fontSize = AttaccaType.caption, color = colors.textGrayed, maxLines = 1, modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
        trailing()
    }
}

/**
 * A mark in front of a track the inclusion list leaves out: not on the allow list, or on the
 * block list. Nothing without an inclusion list.
 */
@Composable
fun ExcludedMark(path: String, modifier: Modifier = Modifier) {
    if (path.contains("://")) return
    val effective by androidx.compose.ui.platform.LocalContext.current.app.inclusionStore.effective
        .collectAsStateWithLifecycle()
    if (effective?.includes(path) != false) return
    // A bare "!" takes only its own width (an icon would keep a 24-unit box around it).
    val description = stringResource(R.string.inclusion_left_out)
    Text(
        "!",
        color = com.sadurni.attacca.ui.theme.LocalAttaccaColors.current.excluded,
        fontSize = AttaccaType.heading,
        fontWeight = FontWeight.Black,
        modifier = modifier
            .padding(end = 3.dp)
            .semantics { contentDescription = description },
    )
}

/**
 * The same mark for a folder: "!" when the inclusion list leaves out all its music (with the
 * subfolders), "◐" when it leaves out part of it.
 */
@Composable
fun FolderExcludedMark(path: String, modifier: Modifier = Modifier) {
    val store = androidx.compose.ui.platform.LocalContext.current.app.inclusionStore
    val effective by store.effective.collectAsStateWithLifecycle()
    val mark by androidx.compose.runtime.produceState(com.sadurni.attacca.library.InclusionStore.FolderMark.NONE, path, effective) {
        value = if (effective == null) com.sadurni.attacca.library.InclusionStore.FolderMark.NONE else store.folderMark(path)
    }
    val (symbol, description) = when (mark) {
        com.sadurni.attacca.library.InclusionStore.FolderMark.NONE -> return
        com.sadurni.attacca.library.InclusionStore.FolderMark.ALL -> "!" to stringResource(R.string.inclusion_left_out_all)
        com.sadurni.attacca.library.InclusionStore.FolderMark.PART -> "◐" to stringResource(R.string.inclusion_left_out_part)
    }
    Text(
        symbol,
        color = com.sadurni.attacca.ui.theme.LocalAttaccaColors.current.excluded,
        fontSize = if (symbol == "!") 17.sp else 16.sp,
        fontWeight = FontWeight.Black,
        modifier = modifier
            .padding(end = 3.dp)
            .semantics { contentDescription = description },
    )
}

/** The legend of the marks above: each symbol in its colour, then what it means. */
@Composable
fun ExcludedLegend() {
    val colors = LocalAttaccaColors.current
    for ((symbol, text) in listOf("!" to R.string.inclusion_legend_out, "◐" to R.string.inclusion_legend_part)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                symbol,
                color = com.sadurni.attacca.ui.theme.LocalAttaccaColors.current.excluded,
                fontSize = if (symbol == "!") 17.sp else 16.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(20.dp),
            )
            Text(stringResource(text), fontSize = AttaccaType.secondary, color = colors.textGrayed)
        }
    }
}


/** Second line of a track row: the artist when its tags are known, and the track's folder. */
/** The length of a track as the tag database knows it, 0 if not known yet (never reads the file). */
fun trackDurationMs(mediaId: String): Long = com.sadurni.attacca.library.TrackSort.cachedTags(mediaId)?.durationMs ?: 0

fun trackSubtitle(mediaId: String): String {
    val folder = java.io.File(mediaId).parentFile?.name.orEmpty()
    val artist = com.sadurni.attacca.library.TrackSort.cachedTags(mediaId)?.artist?.takeIf { it.isNotBlank() }
    return if (artist != null) "$artist · $folder" else folder
}

/** Header shown while selecting tracks: count, select all, actions, close. */
@Composable
fun SelectionHeader(
    count: Int,
    onClose: () -> Unit,
    /** Null where selecting everything makes no sense (no button). */
    onSelectAll: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cancel)) }
        // The count always whole, on one line; the actions take the rest of the row, at its end,
        // and slide sideways when they do not all fit (none is cut off).
        Text(
            stringResource(R.string.selected_count, count),
            fontSize = AttaccaType.heading,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(end = 4.dp),
        )
        androidx.compose.foundation.layout.Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            Row(
                Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onSelectAll != null) IconButton(onClick = onSelectAll) { Icon(Icons.Rounded.SelectAll, contentDescription = stringResource(R.string.select_all)) }
                actions()
            }
        }
    }
}
