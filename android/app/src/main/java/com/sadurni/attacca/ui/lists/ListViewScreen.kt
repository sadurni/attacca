package com.sadurni.attacca.ui.lists

import kotlinx.coroutines.launch

import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Rule
import androidx.compose.foundation.layout.fillMaxWidth
import kotlinx.coroutines.flow.flowOn
import androidx.compose.runtime.produceState
import com.sadurni.attacca.library.ListSummary
import com.sadurni.attacca.library.ListStats
import com.sadurni.attacca.ui.common.ListFooter
import com.sadurni.attacca.ui.common.LocalListExporter
import com.sadurni.attacca.ui.common.matchesQuery
import com.sadurni.attacca.ui.common.SearchField
import com.sadurni.attacca.ui.common.FastScrollBox
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.AddToQueue
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sadurni.attacca.ui.common.tipAnchor
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.rounded.AutoAwesome
import com.sadurni.attacca.ui.common.Icon
import com.sadurni.attacca.ui.common.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.media3.common.MediaItem
import com.sadurni.attacca.R
import com.sadurni.attacca.app
import com.sadurni.attacca.library.ListLoader
import com.sadurni.attacca.library.ListSource
import com.sadurni.attacca.library.MediaItems
import com.sadurni.attacca.library.UserLists
import com.sadurni.attacca.ui.PlayerViewModel
import com.sadurni.attacca.ui.common.ScreenHeader
import com.sadurni.attacca.ui.common.SelectionHeader
import com.sadurni.attacca.ui.common.TrackRow
import com.sadurni.attacca.ui.player.AddToListDialog
import com.sadurni.attacca.ui.settings.Hint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A list that is not playing. It opens on the track where it was left; tapping a track starts
 * the list there (continuing that track's position when it is the remembered one).
 */
@Composable
fun ListViewScreen(
    viewModel: PlayerViewModel,
    source: ListSource,
    onBack: () -> Unit,
    onToPlayer: () -> Unit,
    onEditSmart: (java.io.File) -> Unit = {},
) {
    val context = LocalContext.current
    var items by remember(source.key) { mutableStateOf<List<MediaItem>?>(null) }
    var resumeIndex by remember(source.key) { mutableStateOf(-1) }
    var selection by remember(source.key) { mutableStateOf<Set<Int>?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var addToList by remember { mutableStateOf<List<Int>?>(null) }
    var tools by remember { mutableStateOf(false) }
    var sorting by remember { mutableStateOf(false) }
    var grouping by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    /** While the list is being reordered (chaining analyses tracks first): how far it got. */
    var busy by remember { mutableStateOf<com.sadurni.attacca.chain.ChainAnalyzer.Progress?>(null) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val addedMessage = stringResource(R.string.added_count)
    val exporter = LocalListExporter.current
    val summary by produceState<ListSummary?>(null, items) {
        val ids = items?.map { it.mediaId } ?: return@produceState
        ListStats.summary(ids).flowOn(com.sadurni.attacca.Background.dispatcher).collect { value = it }
    }

    var tagsKnown by remember { mutableIntStateOf(0) }
    val smartFile = (source as? ListSource.Playlist)?.let { java.io.File(it.path) }?.takeIf { context.app.smartLists.isSmart(it) }
    var made by remember { mutableIntStateOf(0) }
    var revealed by remember { mutableIntStateOf(-1) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(source.key, made) {
        val loaded = withContext(Dispatchers.IO) { ListLoader.load(source) }
        val point = context.app.resumeStore.get(source)
        resumeIndex = if (point != null && loaded.isNotEmpty()) context.app.resumeStore.startIndex(point, loaded.map { it.mediaId }) else -1
        items = loaded
        // Opened on a track (Favorites from the player): that one is shown.
        val reveal = viewModel.revealInList?.let { id -> loaded.indexOfFirst { it.mediaId == id } } ?: -1
        viewModel.revealInList = null
        revealed = reveal
        if (reveal >= 0) listState.scrollToItem((reveal - 3).coerceAtLeast(0)) else if (resumeIndex > 3) listState.scrollToItem(resumeIndex - 3)
        // Artists come from the tag database; once known, the rows show them.
        if (withContext(com.sadurni.attacca.Background.dispatcher) { com.sadurni.attacca.library.TrackSort.warmFromDb(loaded.map { it.mediaId }) }) {
            tagsKnown++
        }
        // Lengths (and tags) the database does not have yet are read from the files, a few at a time.
        withContext(com.sadurni.attacca.Background.dispatcher) {
            com.sadurni.attacca.library.TrackSort.fillMissingLengths(loaded.map { it.mediaId }) { tagsKnown++ }
        }
        tagsKnown++
    }

    // The list changed without playing it: its order is written to the file when it is one of
    // the user's own lists, else saved as a new one; grouping and settings stay with the list.
    val own = (source as? ListSource.Playlist)?.let { File(it.path) }?.takeIf { UserLists.isUserList(it) && smartFile == null }
    val store = remember { com.sadurni.attacca.settings.ListSettingsStore(context) }
    val playingSource by context.app.currentListSource.collectAsStateWithLifecycle()
    var listSettings by remember(source.key) { mutableStateOf(store.get(source.key)) }
    val savedAs = stringResource(R.string.list_saved_as)
    val cannot = stringResource(R.string.list_reorder_failed)

    fun changeSettings(key: String, transform: (com.sadurni.attacca.settings.ListSettings) -> com.sadurni.attacca.settings.ListSettings) {
        // The playing list keeps its settings in the player, which saves them.
        if (playingSource?.key == key) viewModel.extras?.updateListSettings(transform) else store.put(key, transform(store.get(key)))
        if (key == source.key) listSettings = transform(listSettings)
    }

    // A change to one of the user's own lists can be undone from the message it leaves: the file
    // and the list's settings as they were come back.
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    val undoLabel = stringResource(R.string.undo)
    // The last change also stays in the ⋮ menu while the list is open.
    var lastChange by remember(source.key) { mutableStateOf<Pair<Int, suspend () -> Unit>?>(null) }
    fun undoLast() {
        val (_, restore) = lastChange ?: return
        lastChange = null
        scope.launch { restore() }
    }
    fun offerUndo(label: Int, file: File?, bytes: ByteArray?, settingsBefore: com.sadurni.attacca.settings.ListSettings) {
        val key = source.key
        val restore: suspend () -> Unit = {
            if (file != null && bytes != null) withContext(Dispatchers.IO) { file.writeBytes(bytes) }
            changeSettings(key) { settingsBefore }
            made++
        }
        lastChange = label to restore
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(context.getString(label), undoLabel, withDismissAction = true, duration = androidx.compose.material3.SnackbarDuration.Long)
            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed && lastChange?.second === restore) undoLast()
        }
    }

    fun reorder(
        change: com.sadurni.attacca.playback.ListChange,
        settings: (com.sadurni.attacca.settings.ListSettings) -> com.sadurni.attacca.settings.ListSettings,
        make: suspend (List<String>, (Int) -> Unit) -> List<Int>?,
    ) {
        val list = items ?: return
        if (busy != null || list.size < 2) return
        busy = com.sadurni.attacca.chain.ChainAnalyzer.Progress(0, list.size)
        scope.launch {
            val timer = com.sadurni.attacca.chain.ChainAnalyzer.ProgressTimer()
            val order = try {
                withContext(com.sadurni.attacca.Background.dispatcher) {
                    make(list.map { it.mediaId }) { done -> busy = timer.of(done, list.size) }
                }
            } catch (e: Exception) {
                null
            } finally {
                busy = null
            }
            if (order == null || order.size != list.size) {
                Toast.makeText(context, cannot, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val file = own ?: UserLists.freeFileFor(source.name)
            val before = if (own != null) withContext(Dispatchers.IO) { runCatching { own.readBytes() }.getOrNull() } else null
            val settingsBefore = listSettings
            withContext(Dispatchers.IO) { UserLists.save(file, order.map(list::get)) }
            changeSettings(ListSource.Playlist(file.absolutePath).key, settings)
            if (own != null) {
                made++
                offerUndo(com.sadurni.attacca.ui.queue.listChangeLabel(change), own, before, settingsBefore)
            } else Toast.makeText(context, savedAs.format(file.nameWithoutExtension), Toast.LENGTH_SHORT).show()
        }
    }

    fun play(index: Int) {
        val list = items ?: return
        viewModel.playFromList(source, list, index)
        onToPlayer()
    }

    fun addToQueue(indices: Collection<Int>) {
        val list = items ?: return
        viewModel.addToQueue(indices.sorted().map(list::get), source.name) { count ->
            Toast.makeText(context, context.getString(R.string.added_to_queue, count), Toast.LENGTH_SHORT).show()
        }
    }

    val nextMessage = stringResource(R.string.row_plays_next)
    fun rowAction(action: com.sadurni.attacca.settings.RowAction, index: Int) {
        val list = items ?: return
        when (action) {
            com.sadurni.attacca.settings.RowAction.PLAY_NEXT -> viewModel.insertNext(listOf(list[index]), source.name) {
                Toast.makeText(context, nextMessage, Toast.LENGTH_SHORT).show()
            }
            com.sadurni.attacca.settings.RowAction.ADD_TO_QUEUE -> addToQueue(listOf(index))
            com.sadurni.attacca.settings.RowAction.ADD_TO_FAVORITES -> viewModel.addItemsToList(UserLists.favorites, listOf(list[index])) { count ->
                Toast.makeText(context, addedMessage.format(count, context.getString(R.string.favorites)), Toast.LENGTH_SHORT).show()
            }
            else -> Unit
        }
    }

    fun addTo(file: File, indices: Collection<Int>) {
        val list = items ?: return
        viewModel.addItemsToList(file, indices.sorted().map(list::get)) { count ->
            Toast.makeText(context, addedMessage.format(count, file.nameWithoutExtension.let {
                if (file == UserLists.favorites) context.getString(R.string.favorites) else it
            }), Toast.LENGTH_SHORT).show()
        }
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        val selected = selection
        val list = items
        if (selected != null && list != null) {
            SelectionHeader(
                count = selected.size,
                onClose = { selection = null },
                onSelectAll = { selection = if (selected.size == list.size) emptySet() else list.indices.toSet() },
            ) {
                IconButton(onClick = {
                    addTo(UserLists.favorites, selected)
                    selection = null
                }, enabled = selected.isNotEmpty()) { Icon(Icons.Rounded.FavoriteBorder, stringResource(R.string.add_to_favorites)) }
                IconButton(onClick = {
                    addToQueue(selected)
                    selection = null
                }, enabled = selected.isNotEmpty()) { Icon(Icons.Rounded.AddToQueue, stringResource(R.string.add_to_queue)) }
                IconButton(onClick = { addToList = selected.toList() }, enabled = selected.isNotEmpty()) {
                    Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, stringResource(R.string.add_to_list))
                }
                IconButton(onClick = {
                    com.sadurni.attacca.ui.common.ShareTracks.share(context, selected.sorted().map { list[it].mediaId })
                    selection = null
                }, enabled = selected.isNotEmpty()) { Icon(Icons.Rounded.Share, stringResource(R.string.share)) }
                val pair = selected.sorted().mapNotNull { list.getOrNull(it)?.mediaId }.takeIf { it.size == 2 && it.none { id -> id.contains("://") } }
                if (pair != null) {
                    IconButton(onClick = {
                        viewModel.requestBridge(pair[0], pair[1])
                        selection = null
                    }) { Icon(Icons.Rounded.Route, stringResource(R.string.list_bridge_action)) }
                }
                val single = selected.singleOrNull()?.let { list.getOrNull(it)?.mediaId }?.takeIf { !it.contains("://") }
                if (single != null) {
                    IconButton(onClick = {
                        viewModel.requestSeedList(single)
                        selection = null
                    }) { Icon(Icons.Rounded.AutoAwesome, stringResource(R.string.seed_list_title)) }
                    IconButton(onClick = {
                        viewModel.requestInclusion(single)
                        selection = null
                    }) { Icon(Icons.Rounded.Rule, com.sadurni.attacca.ui.browser.inclusionMenuLabel()) }
                }
            }
        } else {
            ScreenHeader(source.name, onBack) {
                IconButton(onClick = {
                    searching = !searching
                    if (!searching) query = ""
                }) { Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.search)) }
                IconButton(onClick = { play(resumeIndex.coerceAtLeast(0)) }, enabled = !list.isNullOrEmpty()) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = stringResource(R.string.list_continue))
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.more_options)) }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (smartFile != null) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.smart_edit)) }, onClick = {
                                menuOpen = false
                                onEditSmart(smartFile)
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.smart_make_again)) }, onClick = {
                                menuOpen = false
                                scope.launch {
                                    runCatching { context.app.smartLists.update(smartFile) }
                                    made++
                                }
                            })
                        }
                        lastChange?.let { (label, _) ->
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.undo_change, stringResource(label))) },
                                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    undoLast()
                                },
                            )
                        }
                        DropdownMenuItem(text = { Text(stringResource(R.string.select)) }, onClick = {
                            menuOpen = false
                            selection = emptySet()
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.export_m3u8)) }, onClick = {
                            menuOpen = false
                            list?.let { exporter(source.name, it) }
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.list_like_this_list)) }, onClick = {
                            menuOpen = false
                            list?.let { all -> viewModel.requestGroupSeedList(source.name, all.map { it.mediaId }) }
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.add_all_to_queue)) }, onClick = {
                            menuOpen = false
                            list?.let { addToQueue(it.indices.toList()) }
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.add_all_to_favorites)) }, onClick = {
                            menuOpen = false
                            list?.let { addTo(UserLists.favorites, it.indices.toList()) }
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.damaged_repair_all)) }, onClick = {
                            menuOpen = false
                            list?.let { all -> viewModel.requestRepair(all.map { it.mediaId }) }
                        })
                    }
                }
            }
        }
        // With one track selected the strip stays: chaining starts from that track (else from the
        // track the list was left at, else the first).
        if ((selected == null || selected.size == 1) && !list.isNullOrEmpty()) {
            val app = context.app
            val several = list.size > 1 && busy == null
            val start = selected?.singleOrNull() ?: resumeIndex.takeIf { it in list.indices } ?: 0
            com.sadurni.attacca.ui.common.ActionStrip(
                listOf(
                    com.sadurni.attacca.ui.common.StripAction(Icons.Rounded.Link, stringResource(R.string.strip_chain), enabled = several) {
                        selection = null
                        reorder(com.sadurni.attacca.playback.ListChange.CHAIN, { it.copy(shuffle = com.sadurni.attacca.settings.ShuffleMode.OFF, groupBy = null) }) { ids, progress ->
                            com.sadurni.attacca.chain.ListOrders.chained(app, ids, start, progress)
                        }
                    },
                    com.sadurni.attacca.ui.common.StripAction(Icons.Rounded.Album, stringResource(R.string.strip_chain_albums), enabled = several) {
                        selection = null
                        reorder(com.sadurni.attacca.playback.ListChange.CHAIN_ALBUMS, { it.copy(shuffle = com.sadurni.attacca.settings.ShuffleMode.OFF, groupBy = com.sadurni.attacca.chain.ListOrders.albumGrouping()) }) { ids, _ ->
                            com.sadurni.attacca.chain.ListOrders.albumsChained(app, ids, start)
                        }
                    },
                    com.sadurni.attacca.ui.common.StripAction(Icons.Rounded.Shuffle, stringResource(R.string.strip_shuffle_albums), enabled = several) {
                        reorder(com.sadurni.attacca.playback.ListChange.SHUFFLE_ALBUMS, { it.copy(shuffle = com.sadurni.attacca.settings.ShuffleMode.OFF, groupBy = com.sadurni.attacca.chain.ListOrders.albumGrouping()) }) { ids, _ ->
                            com.sadurni.attacca.chain.ListOrders.albumsShuffled(ids)
                        }
                    },
                    com.sadurni.attacca.ui.common.StripAction(Icons.Rounded.ViewAgenda, stringResource(R.string.strip_group)) { grouping = true },
                    com.sadurni.attacca.ui.common.StripAction(Icons.AutoMirrored.Rounded.Sort, stringResource(R.string.strip_sort), enabled = several) { sorting = true },
                    com.sadurni.attacca.ui.common.StripAction(Icons.Rounded.Tune, stringResource(R.string.strip_settings)) { settingsOpen = true },
                    com.sadurni.attacca.ui.common.StripAction(Icons.Rounded.Build, stringResource(R.string.strip_tools)) { tools = true },
                ),
                Modifier.tipAnchor("listview.strip"),
            )
            busy?.let { p ->
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { if (p.total > 0) p.done.toFloat() / p.total else 0f },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
        if (searching) {
            SearchField(query, { query = it }, onClose = {
                searching = false
                query = ""
            })
        }
        when {
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> Hint(stringResource(R.string.browser_playlist_empty))
            else -> FastScrollBox(listState, Modifier.fillMaxWidth().weight(1f)) { LazyColumn(Modifier.fillMaxSize(), state = listState) {
                val shown = if (query.isBlank()) list.indices.toList() else list.indices.filter { i ->
                    matchesQuery(query, MediaItems.displayTitle(list[i]), list[i].mediaId)
                }
                items(shown, key = { it }) { index ->
                    val item = list[index]
                    val inSelection = selection
                    com.sadurni.attacca.ui.common.SwipeRow(itemKey = item.mediaId, queue = false, enabled = inSelection == null, onAction = { rowAction(it, index) }) {
                    TrackRow(
                        number = index + 1,
                        mediaId = item.mediaId,
                        title = MediaItems.displayTitle(item),
                        subtitle = tagsKnown.let { com.sadurni.attacca.ui.common.trackSubtitle(item.mediaId) },
                        durationMs = tagsKnown.let { com.sadurni.attacca.ui.common.trackDurationMs(item.mediaId) },
                        highlighted = index == resumeIndex || index == revealed,
                        selected = inSelection?.contains(index),
                        onClick = {
                            if (inSelection != null) {
                                selection = if (index in inSelection) inSelection - index else inSelection + index
                            } else {
                                play(index)
                            }
                        },
                        onLongClick = { selection = (inSelection ?: emptySet()) + index },
                    )
                    }
                }
            }
            }
        }
        if (!items.isNullOrEmpty()) ListFooter(resumeIndex, summary)
    }

    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(bottom = 32.dp), contentAlignment = Alignment.BottomCenter) {
        androidx.compose.material3.SnackbarHost(snackbar)
    }

    val loaded = items
    if (tools && loaded != null) {
        val own = (source as? ListSource.Playlist)?.let { File(it.path) }?.takeIf { UserLists.isUserList(it) && smartFile == null }
        val before = remember { own?.let { runCatching { it.readBytes() }.getOrNull() } }
        val settingsBefore = listSettings
        ListToolsDialog(source, loaded, own, onChanged = {
            made++
            if (own != null) offerUndo(R.string.change_tools, own, before, settingsBefore)
        }, onDismiss = { tools = false })
    }

    if (sorting) {
        com.sadurni.attacca.ui.queue.SortDialog(
            onSort = { field, descending ->
                sorting = false
                reorder(com.sadurni.attacca.playback.ListChange.SORT, { it.copy(groupBy = field.takeIf(com.sadurni.attacca.library.TrackSort::groups)) }) { ids, _ ->
                    com.sadurni.attacca.chain.ListOrders.sorted(ids, field, descending)
                }
            },
            onDismiss = { sorting = false },
        )
    }
    if (grouping) {
        com.sadurni.attacca.ui.queue.GroupByDialog(listSettings.groupBy, onPick = { field ->
            grouping = false
            if (field != listSettings.groupBy) {
                val settingsBefore = listSettings
                changeSettings(source.key) { it.copy(groupBy = field) }
                offerUndo(R.string.change_group, null, null, settingsBefore)
            }
        }, onDismiss = { grouping = false })
    }
    if (settingsOpen) {
        com.sadurni.attacca.ui.player.ListSettingsDialog(listSettings, { transform -> changeSettings(source.key, transform) }) { settingsOpen = false }
    }

    addToList?.let { indices ->
        AddToListDialog(
            onPick = { file ->
                addTo(file, indices)
                addToList = null
                selection = null
            },
            onDismiss = { addToList = null },
        )
    }
}
