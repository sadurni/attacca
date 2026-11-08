package com.sadurni.attacca.ui.lists

import androidx.compose.material.icons.rounded.Rule
import kotlinx.coroutines.launch

import com.sadurni.attacca.ui.theme.AttaccaType
import com.sadurni.attacca.ui.common.rowMark
import com.sadurni.attacca.ui.common.tipAnchor
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.HourglassBottom
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.IndeterminateCheckBox
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.AutoAwesomeMosaic
import androidx.compose.material.icons.rounded.Save
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sadurni.attacca.R
import com.sadurni.attacca.app
import com.sadurni.attacca.library.Bookmark
import com.sadurni.attacca.library.ListOrder
import com.sadurni.attacca.library.ListSource
import com.sadurni.attacca.library.UriPaths
import com.sadurni.attacca.library.UserLists
import com.sadurni.attacca.ui.PlayerViewModel
import com.sadurni.attacca.ui.common.PickerMemory
import com.sadurni.attacca.ui.common.ScreenHeader
import com.sadurni.attacca.ui.common.SearchField
import com.sadurni.attacca.ui.common.SelectionHeader
import com.sadurni.attacca.ui.common.formatTime
import com.sadurni.attacca.ui.common.matchesQuery
import com.sadurni.attacca.ui.settings.Hint
import com.sadurni.attacca.ui.settings.NameDialog
import com.sadurni.attacca.ui.settings.SectionTitle
import com.sadurni.attacca.ui.theme.LocalAccent
import com.sadurni.attacca.ui.theme.LocalAttaccaColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/** The groups of lists that can be selected and put in order. */
private enum class ListGroup { MINE, GENERATED, OPENED }

/**
 * Favorites, the user's own lists, playlists opened before and recently played lists. A tap
 * shows the list (on the track where it was left); playing starts from there. A long press
 * selects lists (my lists, generated, opened) to delete them; the handles put them in order.
 */
@Composable
fun ListsScreen(
    viewModel: PlayerViewModel,
    onBack: () -> Unit,
    onBrowseFolder: () -> Unit,
    onView: (ListSource) -> Unit,
    onPlayed: () -> Unit = {},
    /** Opens the smart list editor on a list, or on a new one (null). */
    onEditSmart: (File?) -> Unit = {},
) {
    val context = LocalContext.current
    val app = context.app
    val listOrder = remember { ListOrder(context) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var userLists by remember { mutableStateOf<List<File>>(emptyList()) }
    var includedFavorites by remember { mutableStateOf(false) }
    var recent by remember { mutableStateOf<List<ListSource>>(emptyList()) }
    var opened by remember { mutableStateOf<List<File>>(emptyList()) }
    var generated by remember { mutableStateOf<List<File>>(emptyList()) }
    var generating by remember { mutableStateOf(false) }
    var byListening by remember { mutableStateOf<com.sadurni.attacca.library.ListeningList?>(null) }
    val building by app.chainLists.working.collectAsStateWithLifecycle()
    var saving by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var selection by remember { mutableStateOf<Set<Pair<ListGroup, String>>?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<File?>(null) }
    val savedMessage = stringResource(R.string.list_saved)
    var listsFolder by remember { mutableStateOf(app.settings.listsFolder) }
    // The tab last open comes back with the screen.
    val viewPrefs = remember { context.getSharedPreferences(LISTS_VIEW_PREFS, android.content.Context.MODE_PRIVATE) }
    var tab by remember { mutableStateOf(ListsTab.entries.getOrElse(viewPrefs.getInt(KEY_TAB, 0)) { ListsTab.MINE }) }
    LaunchedEffect(tab) { viewPrefs.edit().putInt(KEY_TAB, tab.ordinal).apply() }

    // Manual order by the drag handles, inside one group.
    var dragGroup by remember { mutableStateOf<ListGroup?>(null) }
    var dragIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var rowHeight by remember { mutableFloatStateOf(1f) }

    // Back where the lists browser was left, if that is still inside the lists folder.
    val browseFolder = { path: String ->
        val last = app.settings.lastListsFolder
        val inside = last != null && (last == path || last.startsWith(path.trimEnd('/') + "/")) && File(last).isDirectory
        if (!inside) app.settings.lastListsFolder = path
        onBrowseFolder()
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { PickerMemory.put(context, PickerMemory.LISTS_FOLDER, it) }
        val path = uri?.let { UriPaths.treePath(it) }
        if (path != null) {
            app.settings.listsFolder = path
            listsFolder = path
            app.settings.lastListsFolder = path
            onBrowseFolder()
        } else if (uri != null) {
            Toast.makeText(context, R.string.music_folder_unsupported, Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(refresh) {
        // Its row shows at once; the list itself is brought up to date after the others are shown.
        includedFavorites = app.settings.chain.value.inclusionList != null
        userLists = withContext(Dispatchers.IO) {
            listOrder.sorted(ListGroup.MINE.name, UserLists.all().filter { it != UserLists.favorites })
        }
        recent = app.listHistory.all()
        opened = withContext(Dispatchers.IO) {
            listOrder.sorted(ListGroup.OPENED.name, app.listHistory.playlists().filter { it.exists() })
        }
        generated = withContext(Dispatchers.IO) { listOrder.sorted(ListGroup.GENERATED.name, app.chainLists.generated()) }
        // Favorites with only the included tracks (none without an inclusion list).
        includedFavorites = withContext(Dispatchers.IO) { syncIncludedFavorites(app) }
    }

    // Another inclusion list put in use (the picker above, Now playing's menu…): Favorites with only
    // the included tracks made again for it, or gone with Everything.
    val chainNow by app.settings.chain.collectAsStateWithLifecycle()
    LaunchedEffect(chainNow.inclusionList, chainNow.inclusionMode) {
        includedFavorites = withContext(Dispatchers.IO) { syncIncludedFavorites(app) }
    }

    fun filesOf(group: ListGroup) = when (group) {
        ListGroup.MINE -> userLists
        ListGroup.GENERATED -> generated
        ListGroup.OPENED -> opened
    }

    fun setFiles(group: ListGroup, files: List<File>) {
        when (group) {
            ListGroup.MINE -> userLists = files
            ListGroup.GENERATED -> generated = files
            ListGroup.OPENED -> opened = files
        }
        listOrder.save(group.name, files)
    }

    fun toggle(group: ListGroup, file: File) {
        val key = group to file.absolutePath
        val current = selection ?: emptySet()
        selection = if (key in current) current - key else current + key
    }

    fun finishDrag() {
        val group = dragGroup
        val files = group?.let(::filesOf)
        if (group != null && files != null && dragIndex in files.indices) {
            val target = (dragIndex + (dragOffset / rowHeight).roundToInt()).coerceIn(0, files.lastIndex)
            if (target != dragIndex) {
                val reordered = files.toMutableList()
                reordered.add(target, reordered.removeAt(dragIndex))
                setFiles(group, reordered)
            }
        }
        dragGroup = null
        dragIndex = -1
        dragOffset = 0f
    }

    BackHandler(enabled = selection != null) { selection = null }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        val selected = selection
        if (selected != null) {
            val selectable = ListGroup.entries.flatMap { g -> filesOf(g).map { g to it.absolutePath } }
            SelectionHeader(
                count = selected.size,
                onClose = { selection = null },
                onSelectAll = { selection = if (selected.size == selectable.size) emptySet() else selectable.toSet() },
            ) {}
            // The labelled actions on their own row, so the count above keeps its room.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // One saved list selected (own or generated; opened lists belong to other folders).
                val single = selected.singleOrNull()?.takeIf { it.first != ListGroup.OPENED }
                if (single != null) {
                    TextButton(onClick = { renaming = File(single.second) }) {
                        Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.padding(end = 4.dp).size(20.dp))
                        Text(stringResource(R.string.list_rename))
                    }
                }
                TextButton(onClick = { confirmDelete = true }, enabled = selected.isNotEmpty()) {
                    Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.padding(end = 4.dp).size(20.dp))
                    Text(stringResource(R.string.lists_delete_all_selected))
                }
            }
        } else {
            ScreenHeader(stringResource(R.string.nav_lists), onBack) {
                IconButton(onClick = {
                    searching = !searching
                    if (!searching) query = ""
                }) { Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.search)) }
                val listBusy by viewModel.listBusy.collectAsStateWithLifecycle()
                var sets by remember { mutableStateOf(false) }
                IconButton(onClick = { sets = true }, modifier = Modifier.tipAnchor("lists.sets")) {
                    Icon(androidx.compose.material.icons.Icons.Rounded.Rule, contentDescription = stringResource(R.string.inclusion_sets_title))
                }
                if (sets) com.sadurni.attacca.ui.chain.InclusionSetDialog { sets = false }
                IconButton(
                    onClick = { viewModel.requestRandomList() },
                    enabled = !listBusy,
                    modifier = Modifier.tipAnchor("lists.random"),
                    // Its mood, tracks and inclusion asked first, for this list only.
                    onLongClick = { context.app.randomMoodRequest.value = true },
                ) {
                    Icon(Icons.Rounded.Casino, contentDescription = stringResource(R.string.random_list))
                }
                IconButton(onClick = { viewModel.moreListsOpen.value = true }, enabled = !listBusy, modifier = Modifier.tipAnchor("lists.more")) {
                    Icon(Icons.Rounded.AutoAwesomeMosaic, contentDescription = stringResource(R.string.more_lists_title))
                }
                IconButton(onClick = { saving = true }) {
                    Icon(Icons.Rounded.Save, contentDescription = stringResource(R.string.list_save_current))
                }
            }
        }
        if (searching) {
            SearchField(query, { query = it }, onClose = {
                searching = false
                query = ""
            })
        }
        // The inclusion set the lists keep to, in sight and changed with a tap (nothing without sets).
        if (selection == null) com.sadurni.attacca.ui.chain.InclusionSetPicker()
        // Order by dragging is only meaningful on the whole group.
        val canReorder = query.isBlank() && selection == null
        val mine = userLists.filter { matchesQuery(query, it.nameWithoutExtension) }
        val smartLabel = stringResource(R.string.smart_label)
        val smartPaths = remember(userLists) { userLists.filter { app.smartLists.isSmart(it) }.map { it.absolutePath }.toSet() }
        val openedShown = opened.filter { matchesQuery(query, it.nameWithoutExtension, it.absolutePath) }
        val recentShown = recent.filter { matchesQuery(query, it.name) }
        val generatedShown = generated.filter { matchesQuery(query, it.nameWithoutExtension) }

        @Composable
        fun GroupTitle(text: String, group: ListGroup, files: List<File>) {
            val inSelection = selection
            if (inSelection == null || files.isEmpty()) {
                SectionTitle(text)
                return
            }
            val keys = files.map { group to it.absolutePath }
            val count = keys.count { it in inSelection }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { SectionTitle(text) }
                IconButton(
                    onClick = { selection = if (count == keys.size) inSelection - keys.toSet() else inSelection + keys },
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    Icon(
                        when (count) {
                            0 -> Icons.Rounded.CheckBoxOutlineBlank
                            keys.size -> Icons.Rounded.CheckBox
                            else -> Icons.Rounded.IndeterminateCheckBox
                        },
                        contentDescription = stringResource(if (count == keys.size) R.string.lists_select_none else R.string.select_all),
                        tint = LocalAccent.current,
                    )
                }
            }
        }

        @Composable
        fun GroupRow(group: ListGroup, index: Int, file: File, subtitle: String?) {
            val dragging = dragGroup == group && dragIndex == index
            SelectableListRow(
                tipTarget = index == 0,
                title = file.nameWithoutExtension,
                subtitle = subtitle,
                listFile = file,
                selected = selection?.contains(group to file.absolutePath),
                dragging = dragging,
                onClick = {
                    when {
                        selection != null -> toggle(group, file)
                        // A smart list is made again first, so it shows what its rules give now.
                        app.smartLists.isSmart(file) -> scope.launch {
                            runCatching { app.smartLists.update(file) }
                            onView(ListSource.Playlist(file.absolutePath))
                        }
                        else -> onView(ListSource.Playlist(file.absolutePath))
                    }
                },
                onLongClick = { toggle(group, file) },
                handle = if (canReorder) {
                    Modifier.pointerInput(group, file.absolutePath) {
                        detectDragGestures(
                            onDragStart = {
                                dragGroup = group
                                dragIndex = index
                                dragOffset = 0f
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                dragOffset += amount.y
                            },
                            onDragEnd = { finishDrag() },
                            onDragCancel = {
                                dragGroup = null
                                dragIndex = -1
                                dragOffset = 0f
                            },
                        )
                    }
                } else {
                    null
                },
                modifier = Modifier
                    .zIndex(if (dragging) 1f else 0f)
                    // Drawn moved, not laid out moved: the handle's touch positions must not move
                    // with it, or the drag would only count part of the finger's travel.
                    .drawWithContent { translate(top = if (dragging) dragOffset else 0f) { this@drawWithContent.drawContent() } }
                    .onSizeChanged { rowHeight = it.height.toFloat().coerceAtLeast(1f) },
            )
        }

        // Favorites and the lists folder stay above the tabs: always at hand.
        if (query.isBlank()) {
            ListRow(Icons.Rounded.Favorite, stringResource(R.string.favorites), null, onClick = {
                onView(ListSource.Playlist(UserLists.favorites.absolutePath))
            })
            if (includedFavorites) {
                ListRow(Icons.Rounded.Favorite, stringResource(R.string.favorites_included), stringResource(R.string.favorites_included_hint), onClick = {
                    scope.launch {
                        // Up to date at the moment it opens.
                        withContext(Dispatchers.IO) { syncIncludedFavorites(app) }
                        onView(ListSource.Playlist(com.sadurni.attacca.library.IncludedFavorites.file.absolutePath))
                    }
                })
            }
            val folder = listsFolder
            ListRow(
                Icons.Rounded.FolderOpen,
                stringResource(R.string.lists_folder),
                folder ?: stringResource(R.string.lists_folder_choose),
                onClick = {
                    if (folder != null && File(folder).isDirectory) browseFolder(folder) else pickFolder.launch(PickerMemory.get(context, PickerMemory.LISTS_FOLDER))
                },
                menuLabel = stringResource(R.string.lists_folder_change),
                menuIcon = Icons.Rounded.Edit,
                onMenu = { pickFolder.launch(PickerMemory.get(context, PickerMemory.LISTS_FOLDER)) },
            )
        }
        val accent = LocalAccent.current
        // Each tab as wide as its name (long ones in some languages scroll rather than cut).
        androidx.compose.material3.ScrollableTabRow(
            selectedTabIndex = tab.ordinal,
            edgePadding = 0.dp,
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            contentColor = accent,
            indicator = { positions ->
                with(androidx.compose.material3.TabRowDefaults) {
                    SecondaryIndicator(Modifier.tabIndicatorOffset(positions[tab.ordinal]), color = accent)
                }
            },
            modifier = Modifier.tipAnchor("lists.tabs"),
        ) {
            ListsTab.entries.forEach { t ->
                androidx.compose.material3.Tab(
                    selected = tab == t,
                    onClick = { tab = t },
                    selectedContentColor = accent,
                    unselectedContentColor = com.sadurni.attacca.ui.theme.LocalAttaccaColors.current.textGrayed,
                    text = { Text(stringResource(t.label), maxLines = 1) },
                )
            }
        }
        // A group's title only while selecting: it holds that group's select-all box.
        @Composable
        fun TitleWhileSelecting(text: String, group: ListGroup, files: List<File>) {
            if (selection != null) GroupTitle(text, group, files)
        }
        LazyColumn(Modifier.fillMaxSize()) {
            if (tab == ListsTab.MINE) {
            item {
                TitleWhileSelecting(stringResource(R.string.lists_mine), ListGroup.MINE, mine)
                if (mine.isEmpty()) Hint(stringResource(if (query.isBlank()) R.string.lists_mine_empty else R.string.search_nothing))
            }
            if (query.isBlank()) item {
                ListRow(
                    Icons.Rounded.FilterAlt,
                    stringResource(R.string.smart_new),
                    stringResource(R.string.smart_new_summary),
                    onClick = { if (selection == null) onEditSmart(null) },
                )
            }
            itemsIndexed(mine, key = { _, file -> file.absolutePath }) { index, file ->
                GroupRow(ListGroup.MINE, index, file, if (file.absolutePath in smartPaths) smartLabel else null)
            }
            }
            if (tab == ListsTab.GENERATED) {
            item { TitleWhileSelecting(stringResource(R.string.lists_generated), ListGroup.GENERATED, generatedShown) }
            if (query.isBlank()) item {
                ListRow(
                    Icons.Rounded.AutoAwesome,
                    stringResource(R.string.generate_title),
                    stringResource(if (building) R.string.chain_building else R.string.generate_summary),
                    onClick = { if (!building && selection == null) generating = true },
                )
            }
            itemsIndexed(generatedShown, key = { _, file -> "generated:" + file.absolutePath }) { index, file ->
                GroupRow(ListGroup.GENERATED, index, file, null)
            }
            if (query.isNotBlank() && generatedShown.isEmpty()) item { Hint(stringResource(R.string.search_nothing)) }
            }
            if (tab == ListsTab.LISTENING) {
                items(com.sadurni.attacca.library.ListeningList.entries.toList(), key = { "listening:" + it.name }) { kind ->
                    ListRow(
                        when (kind) {
                            com.sadurni.attacca.library.ListeningList.MOST_PLAYED -> Icons.Rounded.Repeat
                            com.sadurni.attacca.library.ListeningList.RECENTLY_PLAYED -> Icons.Rounded.History
                            com.sadurni.attacca.library.ListeningList.FORGOTTEN -> Icons.Rounded.HourglassBottom
                            com.sadurni.attacca.library.ListeningList.NEVER_PLAYED -> Icons.Rounded.Explore
                            com.sadurni.attacca.library.ListeningList.MOST_SKIPPED -> Icons.Rounded.SkipNext
                        },
                        kind.label(),
                        kind.summary(),
                        onClick = { if (selection == null) byListening = kind },
                    )
                }
            }
            if (tab == ListsTab.OPENED) {
            if (openedShown.isEmpty() && recentShown.isEmpty()) item {
                Hint(stringResource(if (query.isBlank()) R.string.lists_opened_empty else R.string.search_nothing))
            }
            if (openedShown.isNotEmpty()) item { GroupTitle(stringResource(R.string.lists_opened), ListGroup.OPENED, openedShown) }
            itemsIndexed(openedShown, key = { _, file -> "opened:" + file.absolutePath }) { index, file ->
                GroupRow(ListGroup.OPENED, index, file, file.absolutePath)
            }
            if (recentShown.isNotEmpty()) item { SectionTitle(stringResource(R.string.lists_recent)) }
            items(recentShown, key = { it.key }) { source ->
                ListRow(
                    if (source is ListSource.Folder) Icons.Rounded.Folder else Icons.Rounded.History,
                    source.name,
                    when (source) {
                        is ListSource.Folder -> source.path
                        is ListSource.Playlist -> source.path
                    },
                    onClick = { onView(source) },
                    menuLabel = stringResource(R.string.list_forget),
                    onMenu = {
                        app.listHistory.remove(source)
                        refresh++
                    },
                )
            }
            }
        }
    }

    byListening?.let { kind ->
        ListeningListDialog(kind, viewModel, onDismiss = { byListening = null }, onPlayed = onPlayed)
    }

    if (generating) {
        com.sadurni.attacca.ui.chain.GenerateListsDialog(onDismiss = { generating = false }, onGenerated = { refresh++ })
    }

    val toDelete = selection
    if (confirmDelete && toDelete != null) {
        val files = toDelete.count { it.first != ListGroup.OPENED }
        val forgotten = toDelete.size - files
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.lists_delete_selected)) },
            text = {
                Column {
                    if (files > 0) Text(pluralStringResource(R.plurals.lists_delete_files, files, files))
                    if (forgotten > 0) Text(pluralStringResource(R.plurals.lists_forget_opened, forgotten, forgotten))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    selection = null
                    for ((group, path) in toDelete) {
                        if (group == ListGroup.OPENED) app.listHistory.forgetPlaylist(File(path)) else app.deleteList(File(path))
                    }
                    refresh++
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    renaming?.let { file ->
        val taken = stringResource(R.string.list_rename_taken)
        NameDialog(
            label = stringResource(R.string.list_name),
            title = stringResource(R.string.list_rename),
            initial = file.nameWithoutExtension,
            onConfirm = { name ->
                renaming = null
                selection = null
                if (name.isNotBlank() && name != file.nameWithoutExtension) {
                    if (app.renameList(file, name) == null) Toast.makeText(context, taken.format(name), Toast.LENGTH_SHORT).show()
                    refresh++
                }
            },
            onDismiss = { renaming = null },
        )
    }

    if (saving) {
        NameDialog(
            label = stringResource(R.string.list_name),
            title = stringResource(R.string.list_save_current),
            initial = app.currentListName.value,
            onConfirm = { name ->
                saving = false
                viewModel.saveQueueAsList(name) { file ->
                    Toast.makeText(context, savedMessage.format(file.nameWithoutExtension), Toast.LENGTH_SHORT).show()
                    refresh++
                }
            },
            onDismiss = { saving = false },
        )
    }
}

@Composable
fun BookmarksScreen(viewModel: PlayerViewModel, onBack: () -> Unit, onOpened: () -> Unit) {
    val app = LocalContext.current.app
    var bookmarks by remember { mutableStateOf(app.bookmarks.all()) }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        ScreenHeader(stringResource(R.string.action_bookmarks), onBack)
        if (bookmarks.isEmpty()) Hint(stringResource(R.string.bookmarks_empty))
        LazyColumn(Modifier.fillMaxSize()) {
            items(bookmarks, key = Bookmark::id) { bookmark ->
                ListRow(
                    Icons.Rounded.Bookmark,
                    bookmark.title,
                    formatTime(bookmark.positionMs) + " · " + File(bookmark.mediaId).parentFile?.name.orEmpty(),
                    onClick = {
                        viewModel.openBookmark(bookmark)
                        onOpened()
                    },
                    menuLabel = stringResource(R.string.bookmark_delete),
                    onMenu = {
                        app.bookmarks.remove(bookmark)
                        bookmarks = app.bookmarks.all()
                    },
                )
            }
        }
    }
}

/** A list of a group: selectable with a long press, with a handle to drag it to its place. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SelectableListRow(
    title: String,
    subtitle: String?,
    selected: Boolean?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    handle: Modifier?,
    modifier: Modifier = Modifier,
    tipTarget: Boolean = false,
    dragging: Boolean = false,
    /** The list's file, for the damaged-track mark. */
    listFile: java.io.File? = null,
) {
    val accent = LocalAccent.current
    val colors = LocalAttaccaColors.current
    Row(
        modifier
            .tipAnchor("lists.row", enabled = tipTarget)
            .fillMaxWidth()
            // See-through like the other rows (the theme's background picture shows); only the row
            // being dragged gets a fill, so the rows it passes over do not show through it.
            .then(if (dragging) Modifier.background(colors.surface.copy(alpha = 0.92f)) else Modifier)
            .rowMark(selected = selected == true)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        com.sadurni.attacca.ui.theme.ThemedIcon(
            when (selected) {
                true -> Icons.Rounded.CheckCircle
                false -> Icons.Rounded.RadioButtonUnchecked
                null -> Icons.AutoMirrored.Rounded.QueueMusic
            },
            contentDescription = null,
            // Accent only for a selected list; neutral otherwise.
            tint = if (selected == true) accent else colors.textGrayed,
            modifier = Modifier.size(24.dp),
        )
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (listFile != null) {
                    val damaged by com.sadurni.attacca.library.DamagedTracks.all.collectAsStateWithLifecycle()
                    val paths by androidx.compose.runtime.produceState(emptyList<String>(), listFile, damaged.isEmpty()) {
                        if (damaged.isNotEmpty()) value = kotlinx.coroutines.withContext(com.sadurni.attacca.Background.dispatcher) {
                            runCatching { com.sadurni.attacca.library.M3uParser.parse(listFile).map { it.location } }.getOrDefault(emptyList())
                        }
                    }
                    com.sadurni.attacca.ui.common.GroupDamagedMark(paths)
                }
                Text(title, fontSize = AttaccaType.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (subtitle != null) {
                Text(subtitle, fontSize = AttaccaType.caption, color = colors.textGrayed, maxLines = 1, overflow = TextOverflow.StartEllipsis)
            }
        }
        if (handle != null) {
            Icon(
                Icons.Rounded.DragHandle,
                contentDescription = stringResource(R.string.reorder),
                tint = colors.textGrayed,
                modifier = Modifier.tipAnchor("lists.handle", enabled = tipTarget).padding(horizontal = 12.dp).then(handle),
            )
        }
    }
}

/** A row with an icon, a title, an optional subtitle and an optional long-press action. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    menuLabel: String? = null,
    menuIcon: ImageVector = Icons.Rounded.Delete,
    onMenu: (() -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = { if (onMenu != null) menuOpen = true })
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Neutral icons; the favorites heart keeps its color.
            Icon(
                icon,
                contentDescription = null,
                tint = if (icon == Icons.Rounded.Favorite) LocalAccent.current else LocalAttaccaColors.current.textGrayed,
                modifier = Modifier.size(24.dp),
            )
            Column(Modifier.padding(start = 16.dp)) {
                Text(title, fontSize = AttaccaType.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    // A path is cut at the start (its end matters); a description wraps.
                    val path = subtitle.startsWith("/")
                    Text(
                        subtitle,
                        fontSize = AttaccaType.caption,
                        color = LocalAttaccaColors.current.textGrayed,
                        maxLines = if (path) 1 else 2,
                        overflow = if (path) TextOverflow.StartEllipsis else TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (onMenu != null && menuLabel != null) {
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(menuLabel) },
                    leadingIcon = { Icon(menuIcon, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onMenu()
                    },
                )
            }
        }
    }
}

/** [com.sadurni.attacca.library.IncludedFavorites] up to date; false without an inclusion list. Blocking. */
private fun syncIncludedFavorites(app: com.sadurni.attacca.AttaccaApp): Boolean = runCatching {
    val chain = app.settings.chain.value
    com.sadurni.attacca.library.IncludedFavorites.sync(chain.inclusionList, chain.inclusionMode, java.io.File(app.filesDir, "included_favorites.stamp"))
}.getOrDefault(false)

/** The tabs of the Lists screen (below Favorites and the lists folder). */
private enum class ListsTab(val label: Int) {
    MINE(R.string.lists_tab_mine),
    GENERATED(R.string.lists_tab_generated),
    LISTENING(R.string.lists_tab_listening),
    OPENED(R.string.lists_tab_opened),
}

private const val LISTS_VIEW_PREFS = "lists_view"
private const val KEY_TAB = "tab"
