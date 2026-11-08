package com.sadurni.attacca.ui.queue

import com.sadurni.attacca.ui.theme.AttaccaType
import com.sadurni.attacca.ui.common.ListFooter
import android.widget.Toast
import androidx.compose.ui.res.pluralStringResource
import java.io.File
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.sadurni.attacca.ui.QueueEntry
import com.sadurni.attacca.ui.common.FastScrollBox
import com.sadurni.attacca.ui.common.LocalListExporter
import com.sadurni.attacca.ui.common.SearchField
import com.sadurni.attacca.ui.common.matchesQuery
import com.sadurni.attacca.ui.theme.LocalAccent
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.sadurni.attacca.ui.common.Icon
import com.sadurni.attacca.ui.common.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sadurni.attacca.R
import com.sadurni.attacca.library.SortField
import com.sadurni.attacca.library.UserLists
import com.sadurni.attacca.ui.PlayerViewModel
import com.sadurni.attacca.ui.common.ScreenHeader
import com.sadurni.attacca.ui.common.SelectionHeader
import com.sadurni.attacca.ui.common.TrackRow
import com.sadurni.attacca.ui.common.tipAnchor
import com.sadurni.attacca.ui.player.AddToListDialog
import com.sadurni.attacca.ui.player.ListSettingsDialog
import com.sadurni.attacca.ui.player.ScrollHintColumn
import com.sadurni.attacca.ui.settings.NameDialog
import com.sadurni.attacca.ui.settings.Hint
import com.sadurni.attacca.ui.settings.RadioRow
import com.sadurni.attacca.ui.settings.SwitchRow
import com.sadurni.attacca.ui.theme.LocalAttaccaColors
import kotlin.math.roundToInt

private sealed interface QueueDialog {
    data object Save : QueueDialog
    data object Settings : QueueDialog
    data object Sort : QueueDialog
    data object Group : QueueDialog
    data class AddToList(val indices: List<Int>) : QueueDialog
}

/** A row of the playing list: a group header or a track. */
private sealed interface QueueRow {
    val key: String

    /** Entries [start] until [end] (exclusive) share [name]. */
    data class Header(val name: String, val start: Int, val end: Int, val collapsed: Boolean) : QueueRow {
        override val key get() = "g$start"
    }

    data class Track(val entry: QueueEntry) : QueueRow {
        override val key get() = "t${entry.index}"
    }
}

/** Consecutive runs of the same group key, each with its header. */
private fun buildRows(queue: List<QueueEntry>, keys: List<String>?, collapsed: Set<String>, allCollapsed: Boolean): List<QueueRow> {
    if (keys == null || keys.size != queue.size) return queue.map { QueueRow.Track(it) }
    val rows = ArrayList<QueueRow>(queue.size + queue.size / 8)
    var i = 0
    while (i < queue.size) {
        var j = i + 1
        while (j < queue.size && keys[j] == keys[i]) j++
        val isCollapsed = allCollapsed || keys[i] in collapsed
        rows.add(QueueRow.Header(keys[i], i, j, isCollapsed))
        if (!isCollapsed) for (k in i until j) rows.add(QueueRow.Track(queue[k]))
        i = j
    }
    return rows
}

/** The playing list: play, select, reorder, sort, group, add to favorites or other lists. */
@Composable
fun QueueScreen(viewModel: PlayerViewModel, onBack: () -> Unit, onEditTags: (String) -> Unit) {
    val context = LocalContext.current
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    val now by viewModel.nowPlaying.collectAsStateWithLifecycle()
    val listName by viewModel.listName.collectAsStateWithLifecycle()
    val listSettings by viewModel.listSettings.collectAsStateWithLifecycle()
    val groups by viewModel.queueGroups.collectAsStateWithLifecycle()
    val sorting by viewModel.sorting.collectAsStateWithLifecycle()
    val chaining by viewModel.chaining.collectAsStateWithLifecycle()
    val summary by viewModel.queueSummary.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<QueueDialog?>(null) }
    var selection by remember { mutableStateOf<Set<Int>?>(null) }
    var collapsed by remember { mutableStateOf<Set<String>>(emptySet()) }
    val savedMessage = stringResource(R.string.list_saved)
    val addedMessage = stringResource(R.string.added_count)
    val exporter = LocalListExporter.current

    // Manual reordering by the drag handles. Dragging a group collapses every group meanwhile,
    // so each one is a single row.
    var dragRow by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var rowHeight by remember { mutableFloatStateOf(1f) }
    var draggingGroup by remember { mutableStateOf<QueueRow.Header?>(null) }

    fun added(target: String, count: Int) =
        Toast.makeText(context, addedMessage.format(count, target), Toast.LENGTH_SHORT).show()

    fun addToFavorites(indices: Collection<Int>) =
        viewModel.addItemsToList(UserLists.favorites, viewModel.queueItems(indices)) { added(context.getString(R.string.favorites), it) }

    // A row swiped sideways (Settings → Gestures); a removal can be undone for a few seconds.
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    val snackScope = rememberCoroutineScope()
    val removedMessage = stringResource(R.string.row_removed)
    val undoLabel = stringResource(R.string.undo)
    val nextMessage = stringResource(R.string.row_plays_next)

    // Changes to the whole list (chain, sort, group, removals…) can be undone from the message
    // they leave at the bottom, or from the ⋮ menu while nothing else changed the list.
    val undo = viewModel.extras?.undo?.collectAsStateWithLifecycle()?.value
    fun offerUndo(message: String) {
        snackScope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(message, undoLabel, withDismissAction = true, duration = androidx.compose.material3.SnackbarDuration.Long)
            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) viewModel.extras?.undoListChange()
        }
    }
    LaunchedEffect(undo) {
        val change = undo?.change ?: return@LaunchedEffect
        // Only a change just made: an older one stays in the ⋮ menu.
        if (android.os.SystemClock.elapsedRealtime() - undo.at > 5_000) return@LaunchedEffect
        // Removals tell how many went themselves.
        if (change !in setOf(
                com.sadurni.attacca.playback.ListChange.KEEP_INCLUDED, com.sadurni.attacca.playback.ListChange.REMOVE,
                com.sadurni.attacca.playback.ListChange.MOVE, com.sadurni.attacca.playback.ListChange.ADD,
            )
        ) {
            offerUndo(context.getString(listChangeLabel(change)))
        }
    }
    fun removedMessage(count: Int) = context.resources.getQuantityString(R.plurals.queue_removed_count, count, count)
    fun rowAction(action: com.sadurni.attacca.settings.RowAction, index: Int, title: String) {
        when (action) {
            com.sadurni.attacca.settings.RowAction.PLAY_NEXT -> {
                viewModel.playNext(index)
                Toast.makeText(context, nextMessage, Toast.LENGTH_SHORT).show()
            }
            com.sadurni.attacca.settings.RowAction.ADD_TO_FAVORITES -> addToFavorites(listOf(index))
            com.sadurni.attacca.settings.RowAction.REMOVE -> {
                val item = viewModel.takeFromQueue(index) ?: return
                snackScope.launch {
                    snackbar.currentSnackbarData?.dismiss()
                    val result = snackbar.showSnackbar(removedMessage.format(title), undoLabel, duration = androidx.compose.material3.SnackbarDuration.Short)
                    if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) viewModel.restoreToQueue(index, item)
                }
            }
            else -> Unit
        }
    }

    val groupKeys = groups?.keys?.takeIf { it.size == queue.size }
    val rows = remember(queue, groupKeys, collapsed, draggingGroup != null, query) {
        if (query.isNotBlank()) {
            queue.filter { matchesQuery(query, it.title, it.subtitle) }.map { QueueRow.Track(it) }
        } else {
            buildRows(queue, groupKeys, collapsed, allCollapsed = draggingGroup != null)
        }
    }

    // The list changed (sort, removal…): indices in the selection no longer mean the same.
    LaunchedEffect(queue) { selection = selection?.filter { it < queue.size }?.toSet() }
    LaunchedEffect(queue.size, groupKeys != null) {
        val row = rows.indexOfFirst { it is QueueRow.Track && it.entry.index == now.index }
        if (row >= 0) listState.scrollToItem((row - 3).coerceAtLeast(0))
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        val selected = selection
        if (selected != null) {
            SelectionHeader(
                count = selected.size,
                onClose = { selection = null },
                onSelectAll = { selection = if (selected.size == queue.size) emptySet() else queue.indices.toSet() },
            ) {
                IconButton(onClick = {
                    addToFavorites(selected)
                    selection = null
                }, enabled = selected.isNotEmpty()) { Icon(Icons.Rounded.FavoriteBorder, stringResource(R.string.add_to_favorites)) }
                IconButton(onClick = { dialog = QueueDialog.AddToList(selected.toList()) }, enabled = selected.isNotEmpty()) {
                    Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, stringResource(R.string.add_to_list))
                }
                IconButton(onClick = {
                    com.sadurni.attacca.ui.common.ShareTracks.share(context, selected.sorted().mapNotNull { queue.getOrNull(it)?.mediaId })
                    selection = null
                }, enabled = selected.isNotEmpty()) { Icon(Icons.Rounded.Share, stringResource(R.string.share)) }
                val pair = selected.sorted().mapNotNull { queue.getOrNull(it)?.mediaId }.takeIf { it.size == 2 && it.none { id -> id.contains("://") } }
                if (pair != null) {
                    IconButton(onClick = {
                        viewModel.requestBridge(pair[0], pair[1])
                        selection = null
                    }) { Icon(Icons.Rounded.Route, stringResource(R.string.list_bridge_action)) }
                }
                IconButton(onClick = {
                    viewModel.removeFromQueue(selected)
                    offerUndo(removedMessage(selected.size))
                    selection = null
                }, enabled = selected.isNotEmpty()) { Icon(Icons.Rounded.Delete, stringResource(R.string.queue_remove)) }
            }
        } else {
            ScreenHeader(title = listName.ifEmpty { stringResource(R.string.nav_queue) }, onBack = onBack) {
                IconButton(onClick = {
                    searching = !searching
                    if (!searching) query = ""
                }) {
                    Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.search))
                }
                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.tipAnchor("queue.menu")) { Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.more_options)) }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        undo?.let { last ->
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.undo_change, stringResource(listChangeLabel(last.change)))) },
                                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    viewModel.extras?.undoListChange()
                                },
                            )
                        }
                        if (groupKeys != null) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.group_collapse_all)) }, onClick = {
                                menuOpen = false
                                collapsed = groupKeys.toSet()
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.group_expand_all)) }, onClick = {
                                menuOpen = false
                                collapsed = emptySet()
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.group_none)) }, onClick = {
                                menuOpen = false
                                viewModel.extras?.setGroupBy(null)
                            })
                        }
                        DropdownMenuItem(text = { Text(stringResource(R.string.queue_keep_included)) }, onClick = {
                            menuOpen = false
                            viewModel.keepOnlyIncluded { removed ->
                                when {
                                    removed < 0 -> Toast.makeText(context, context.getString(R.string.only_included_none), Toast.LENGTH_SHORT).show()
                                    removed == 0 -> Toast.makeText(context, removedMessage(0), Toast.LENGTH_SHORT).show()
                                    else -> offerUndo(removedMessage(removed))
                                }
                            }
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.queue_shuffle_albums)) }, onClick = {
                            menuOpen = false
                            viewModel.extras?.shuffleAlbumsInPlace()
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.select)) }, onClick = {
                            menuOpen = false
                            selection = emptySet()
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.add_all_to_favorites)) }, onClick = {
                            menuOpen = false
                            addToFavorites(queue.indices.toList())
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.export_m3u8)) }, onClick = {
                            menuOpen = false
                            exporter(listName.ifEmpty { context.getString(R.string.nav_queue) }, viewModel.queueItems(queue.indices.toList()))
                        })
                    }
                }
            }
        }
        if (selected == null) {
            com.sadurni.attacca.ui.common.ActionStrip(
                listOf(
                    com.sadurni.attacca.ui.common.StripAction(Icons.Rounded.Save, stringResource(R.string.strip_save)) { dialog = QueueDialog.Save },
                    com.sadurni.attacca.ui.common.StripAction(Icons.Rounded.Link, stringResource(R.string.strip_chain), enabled = chaining == null && queue.size > 1) {
                        viewModel.extras?.chainQueue()
                    },
                    com.sadurni.attacca.ui.common.StripAction(Icons.Rounded.Album, stringResource(R.string.strip_chain_albums), enabled = chaining == null && queue.size > 1) {
                        viewModel.extras?.chainAlbumsInPlace()
                    },
                    com.sadurni.attacca.ui.common.StripAction(Icons.Rounded.ViewAgenda, stringResource(R.string.strip_group)) { dialog = QueueDialog.Group },
                    com.sadurni.attacca.ui.common.StripAction(Icons.AutoMirrored.Rounded.Sort, stringResource(R.string.strip_sort), enabled = queue.size > 1) { dialog = QueueDialog.Sort },
                    com.sadurni.attacca.ui.common.StripAction(Icons.Rounded.Tune, stringResource(R.string.strip_settings)) { dialog = QueueDialog.Settings },
                ),
                Modifier.tipAnchor("queue.strip"),
            )
        }
        if (searching) {
            SearchField(query, { query = it }, onClose = {
                searching = false
                query = ""
            })
        }
        if (!sorting && listSettings.groupBy != null && groupKeys == null && queue.isNotEmpty() && query.isBlank()) {
            Text(
                stringResource(R.string.grouping_progress),
                fontSize = AttaccaType.secondary,
                color = LocalAttaccaColors.current.textGrayed,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
        }
        if (sorting) {
            Text(
                stringResource(R.string.sorting_progress),
                fontSize = AttaccaType.secondary,
                color = LocalAttaccaColors.current.textGrayed,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
        }
        chaining?.let { p ->
            // Not modal: the list stays usable and is reordered once the analysis is done.
            Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    com.sadurni.attacca.ui.common.analysisProgressText(R.string.chain_progress, p.done, p.total, p.remainingMs),
                    fontSize = AttaccaType.secondary,
                    color = LocalAttaccaColors.current.textGrayed,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { viewModel.extras?.cancelChaining() }) { Text(stringResource(R.string.cancel)) }
            }
            LinearProgressIndicator(
                progress = { if (p.total > 0) p.done.toFloat() / p.total else 0f },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        if (queue.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.queue_empty), color = LocalAttaccaColors.current.textGrayed)
            }
            return@Column
        }

        fun finishDrag() {
            val from = dragRow
            val group = draggingGroup
            if (from != null && from in rows.indices) {
                val target = (from + (dragOffset / rowHeight).roundToInt()).coerceIn(0, rows.lastIndex)
                if (group != null) {
                    // Every group is one row now: move the block to the target group's place.
                    val headers = rows.filterIsInstance<QueueRow.Header>()
                    val to = headers[target.coerceIn(0, headers.lastIndex)]
                    val newIndex = if (to.start > group.start) to.end - (group.end - group.start) else to.start
                    viewModel.moveGroupInQueue(group.start, group.end, newIndex)
                } else {
                    val moved = (rows[from] as? QueueRow.Track)?.entry?.index
                    val to = when (val row = rows[target]) {
                        is QueueRow.Track -> row.entry.index
                        is QueueRow.Header -> if (target > from) row.start - 1 else row.start
                    }
                    if (moved != null) viewModel.moveInQueue(moved, to.coerceIn(0, queue.lastIndex))
                }
            }
            dragRow = null
            dragOffset = 0f
            draggingGroup = null
        }

        FastScrollBox(listState, Modifier.fillMaxWidth().weight(1f)) {
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                itemsIndexed(rows, key = { _, row -> row.key }) { rowIndex, row ->
                    val dragging = dragRow == rowIndex
                    val rowModifier = Modifier
                        .tipAnchor("queue.row", enabled = row is QueueRow.Track)
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer { translationY = if (dragging) dragOffset else 0f }
                        .onSizeChanged { if (draggingGroup == null && row is QueueRow.Track) rowHeight = it.height.toFloat() }
                    // Reordering by dragging is only meaningful on the full, unfiltered list.
                    val handle: Modifier.(onStart: () -> Unit) -> Modifier = { onStart ->
                        pointerInput(row.key) {
                            detectDragGestures(
                                onDragStart = {
                                    onStart()
                                    dragOffset = 0f
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragOffset += amount.y
                                },
                                onDragEnd = { finishDrag() },
                                onDragCancel = {
                                    dragRow = null
                                    dragOffset = 0f
                                    draggingGroup = null
                                },
                            )
                        }
                    }
                    when (row) {
                        is QueueRow.Header -> GroupHeader(
                            row = row,
                            // Folder groups are keyed by the whole path; its name is enough.
                            displayName = if (groups?.field == SortField.PATH) File(row.name).name else row.name,
                            playing = now.index in row.start until row.end,
                            modifier = rowModifier,
                            onToggle = {
                                collapsed = if (row.collapsed) collapsed - row.name else collapsed + row.name
                            },
                            onPlay = { viewModel.playIndex(row.start) },
                            handle = if (query.isBlank() && selection == null) {
                                Modifier.handle {
                                    // Collapse everything; the dragged header becomes the row at
                                    // its group's position, kept where it is on screen.
                                    val groupIndex = rows.take(rowIndex).count { it is QueueRow.Header }
                                    val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == row.key }
                                    val height = info?.size?.takeIf { it > 0 } ?: 1
                                    val above = ((info?.offset ?: 0) / height).coerceIn(0, groupIndex)
                                    rowHeight = height.toFloat()
                                    draggingGroup = row
                                    dragRow = groupIndex
                                    scope.launch {
                                        listState.scrollToItem(groupIndex - above, (above * height - (info?.offset ?: 0)).coerceAtLeast(0))
                                    }
                                }
                            } else {
                                null
                            },
                        )
                        is QueueRow.Track -> {
                            val entry = row.entry
                            val inSelection = selection
                            var rowMenu by remember { mutableStateOf(false) }
                            Box(rowModifier) {
                              com.sadurni.attacca.ui.common.SwipeRow(
                                  itemKey = entry.mediaId,
                                  queue = true,
                                  enabled = inSelection == null && dragRow == null,
                                  onAction = { rowAction(it, entry.index, entry.title) },
                              ) {
                                TrackRow(
                                    number = entry.index + 1,
                                    mediaId = entry.mediaId,
                                    title = entry.title,
                                    subtitle = entry.subtitle,
                                    durationMs = entry.durationMs,
                                    highlighted = entry.index == now.index,
                                    selected = inSelection?.contains(entry.index),
                                    onClick = {
                                        if (inSelection != null) {
                                            selection = if (entry.index in inSelection) inSelection - entry.index else inSelection + entry.index
                                        } else {
                                            viewModel.playIndex(entry.index)
                                        }
                                    },
                                    onLongClick = { selection = (inSelection ?: emptySet()) + entry.index },
                                ) {
                                    if (inSelection == null) {
                                        IconButton(onClick = { rowMenu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.more_options)) }
                                        if (query.isBlank()) {
                                            Icon(
                                                Icons.Rounded.DragHandle,
                                                contentDescription = stringResource(R.string.reorder),
                                                tint = LocalAttaccaColors.current.textGrayed,
                                                modifier = Modifier.padding(end = 12.dp).handle { dragRow = rowIndex },
                                            )
                                        }
                                    }
                                }
                              }
                                DropdownMenu(expanded = rowMenu, onDismissRequest = { rowMenu = false }) {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.queue_play_next)) }, onClick = {
                                        rowMenu = false
                                        viewModel.playNext(entry.index)
                                    })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.add_to_favorites)) }, onClick = {
                                        rowMenu = false
                                        addToFavorites(listOf(entry.index))
                                    })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.add_to_list)) }, onClick = {
                                        rowMenu = false
                                        dialog = QueueDialog.AddToList(listOf(entry.index))
                                    })
                                    if (!entry.mediaId.contains("://")) {
                                        DropdownMenuItem(text = { Text(com.sadurni.attacca.ui.browser.inclusionMenuLabel()) }, onClick = {
                                            rowMenu = false
                                            viewModel.requestInclusion(entry.mediaId)
                                        })
                                        if (entry.mediaId.endsWith(".mp3", ignoreCase = true)) {
                                            DropdownMenuItem(text = { Text(stringResource(R.string.damaged_repair)) }, onClick = {
                                                rowMenu = false
                                                viewModel.requestRepair(listOf(entry.mediaId))
                                            })
                                        }
                                        DropdownMenuItem(text = { Text(stringResource(R.string.seed_list_title)) }, onClick = {
                                            rowMenu = false
                                            viewModel.requestSeedList(entry.mediaId)
                                        })
                                        DropdownMenuItem(text = { Text(stringResource(R.string.quick_list)) }, onClick = {
                                            rowMenu = false
                                            viewModel.quickSeedList(entry.mediaId)
                                        })
                                        DropdownMenuItem(text = { Text(stringResource(R.string.share)) }, onClick = {
                                            rowMenu = false
                                            com.sadurni.attacca.ui.common.ShareTracks.share(context, listOf(entry.mediaId))
                                        })
                                        DropdownMenuItem(text = { Text(stringResource(R.string.tags_title)) }, onClick = {
                                            rowMenu = false
                                            onEditTags(entry.mediaId)
                                        })
                                    }
                                    DropdownMenuItem(text = { Text(stringResource(R.string.queue_remove)) }, onClick = {
                                        rowMenu = false
                                        viewModel.removeFromQueue(entry.index)
                                        offerUndo(removedMessage(1))
                                    })
                                }
                            }
                        }
                    }
                }
            }
        }
        ListFooter(now.index, summary)
    }

    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(bottom = 32.dp), contentAlignment = Alignment.BottomCenter) {
        androidx.compose.material3.SnackbarHost(snackbar)
    }

    when (val d = dialog) {
        QueueDialog.Save -> NameDialog(
            label = stringResource(R.string.list_name),
            title = stringResource(R.string.list_save_current),
            initial = listName,
            onConfirm = { name ->
                dialog = null
                viewModel.saveQueueAsList(name) { file ->
                    Toast.makeText(context, savedMessage.format(file.nameWithoutExtension), Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { dialog = null },
        )
        QueueDialog.Settings -> ListSettingsDialog(listSettings, { viewModel.extras?.updateListSettings(it) }) { dialog = null }
        QueueDialog.Sort -> SortDialog(
            onSort = { field, descending ->
                dialog = null
                collapsed = emptySet()
                viewModel.sortQueue(field, descending)
            },
            onDismiss = { dialog = null },
        )
        QueueDialog.Group -> GroupByDialog(listSettings.groupBy, onPick = { field ->
            dialog = null
            collapsed = emptySet()
            viewModel.extras?.setGroupBy(field)
        }, onDismiss = { dialog = null })
        is QueueDialog.AddToList -> AddToListDialog(
            onPick = { file ->
                viewModel.addItemsToList(file, viewModel.queueItems(d.indices)) { added(file.nameWithoutExtension, it) }
                dialog = null
                selection = null
            },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

/** Header of a group: fold toggle, name and size, play, drag handle. */
@Composable
private fun GroupHeader(
    row: QueueRow.Header,
    displayName: String,
    playing: Boolean,
    modifier: Modifier,
    onToggle: () -> Unit,
    onPlay: () -> Unit,
    handle: Modifier?,
) {
    val colors = LocalAttaccaColors.current
    val accent = LocalAccent.current
    Row(
        modifier
            .fillMaxWidth()
            .background(colors.surface)
            .clickable(onClick = onToggle)
            .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (row.collapsed) Icons.Rounded.ChevronRight else Icons.Rounded.ExpandMore,
            contentDescription = null,
            tint = accent,
        )
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(
                displayName.ifEmpty { stringResource(R.string.group_unknown) },
                fontSize = AttaccaType.body,
                fontWeight = FontWeight.Bold,
                color = if (playing) accent else Color.Unspecified,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                pluralStringResource(R.plurals.group_tracks, row.end - row.start, row.end - row.start),
                fontSize = AttaccaType.caption,
                color = colors.textGrayed,
            )
        }
        IconButton(onClick = onPlay) { Icon(Icons.Rounded.PlayArrow, contentDescription = stringResource(R.string.group_play)) }
        if (handle != null) {
            Icon(
                Icons.Rounded.DragHandle,
                contentDescription = stringResource(R.string.reorder),
                tint = colors.textGrayed,
                modifier = Modifier.padding(end = 12.dp).then(handle),
            )
        }
    }
}

/** How a list reads in groups (album, artist, year, folder) or none; [current] is checked. */
@Composable
fun GroupByDialog(current: SortField?, onPick: (SortField?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.group_by)) },
        text = {
            Column {
                Hint(stringResource(R.string.group_by_hint))
                val options = listOf(null, SortField.ALBUM, SortField.ARTIST, SortField.YEAR, SortField.PATH)
                for (field in options) {
                    RadioRow(
                        if (field == null) stringResource(R.string.group_by_none) else groupFieldName(field),
                        current == field,
                    ) { onPick(field) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun SortDialog(onSort: (SortField, Boolean) -> Unit, onDismiss: () -> Unit) {
    var field by remember { mutableStateOf(SortField.ARTIST) }
    var descending by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sort_by)) },
        text = {
            ScrollHintColumn(maxHeight = 460.dp) {
                items(SortField.entries) { option ->
                    RadioRow(sortFieldName(option), field == option) { field = option }
                }
                item { SwitchRow(stringResource(R.string.sort_descending), descending, { descending = it }) }
            }
        },
        confirmButton = { TextButton(onClick = { onSort(field, descending) }) { Text(stringResource(R.string.sort_apply)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun sortFieldName(field: SortField): String = stringResource(
    when (field) {
        SortField.TITLE -> R.string.tag_title
        SortField.ARTIST -> R.string.tag_artist
        SortField.ALBUM -> R.string.tag_album
        SortField.TRACK_NUMBER -> R.string.tag_track
        SortField.YEAR -> R.string.tag_year
        SortField.FILE_NAME -> R.string.sort_file_name
        SortField.PATH -> R.string.sort_path
        SortField.DATE_MODIFIED -> R.string.sort_date_modified
        SortField.DURATION -> R.string.sort_duration
    },
)

@Composable
private fun groupFieldName(field: SortField): String =
    if (field == SortField.PATH) stringResource(R.string.group_folder) else sortFieldName(field)

/** What a list change is called in the undo message and menu. */
internal fun listChangeLabel(change: com.sadurni.attacca.playback.ListChange): Int = when (change) {
    com.sadurni.attacca.playback.ListChange.CHAIN -> R.string.change_chain
    com.sadurni.attacca.playback.ListChange.CHAIN_ALBUMS -> R.string.change_chain_albums
    com.sadurni.attacca.playback.ListChange.SORT -> R.string.change_sort
    com.sadurni.attacca.playback.ListChange.SHUFFLE_ALBUMS -> R.string.change_shuffle_albums
    com.sadurni.attacca.playback.ListChange.GROUP -> R.string.change_group
    com.sadurni.attacca.playback.ListChange.KEEP_INCLUDED -> R.string.change_keep_included
    com.sadurni.attacca.playback.ListChange.REMOVE -> R.string.change_remove
    com.sadurni.attacca.playback.ListChange.MOVE -> R.string.change_move
    com.sadurni.attacca.playback.ListChange.ADD -> R.string.change_add
}
