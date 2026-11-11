package com.sadurni.attacca.ui.browser

import com.sadurni.attacca.ui.theme.AttaccaType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderSpecial
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.AddToQueue
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Search
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.sadurni.attacca.ui.common.rowMark
import com.sadurni.attacca.ui.common.tipAnchor
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sadurni.attacca.R
import com.sadurni.attacca.app
import com.sadurni.attacca.library.AudioFiles
import com.sadurni.attacca.ui.common.FastScrollBox
import com.sadurni.attacca.ui.common.ScreenHeader
import com.sadurni.attacca.ui.common.SearchField
import com.sadurni.attacca.ui.common.SelectionHeader
import com.sadurni.attacca.ui.common.matchesQuery
import com.sadurni.attacca.ui.player.AddToListDialog
import com.sadurni.attacca.ui.theme.LocalAccent
import com.sadurni.attacca.ui.theme.LocalAttaccaColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/** What the user picked in the browser. */
sealed interface BrowserAction {
    data class PlayFolder(val folder: File, val startFile: File?, val recursive: Boolean) : BrowserAction
    data class OpenPlaylist(val file: File) : BrowserAction

    /** Several folders, playlists and files, played together in this order. */
    data class PlayFiles(val files: List<File>) : BrowserAction

    /** Added at the end of the playing list. */
    data class AddToQueue(val files: List<File>) : BrowserAction

    /** Put right after the playing track. */
    data class PlayNext(val files: List<File>) : BrowserAction

    data class AddToFavorites(val files: List<File>) : BrowserAction

    /** Added to one of the user's lists. */
    data class AddToList(val files: List<File>, val list: File) : BrowserAction

    /** A new chained list of tracks like this one. */
    data class SeedList(val file: File) : BrowserAction

    /** A list from a track made and played at once (see [com.sadurni.attacca.ui.PlayerViewModel.quickSeedList]). */
    data class QuickList(val file: File) : BrowserAction

    /** A list leading from track [from] to track [to] (the bridge), made and played at once. */
    data class Bridge(val from: String, val to: String) : BrowserAction

    /** A list of music like a group of tracks (an album, an artist) named [name]. */
    data class SeedGroup(val name: String, val paths: List<String>) : BrowserAction

    /** Added to or taken out of the inclusion list (the file, its album, artist or folder). */
    data class Inclusion(val file: File) : BrowserAction

    /** The damaged MP3s among these tracks or folders repaired (see [com.sadurni.attacca.ui.common.RepairDialog]). */
    data class Repair(val paths: List<String>) : BrowserAction

    /** These tracks as a new list named [name], from the one at [start]. */
    /** [byAlbums]: several whole albums one after another, shown grouped by album. */
    data class PlayTracks(val name: String, val files: List<File>, val start: Int, val chain: Boolean = false, val byAlbums: Boolean = false) : BrowserAction
}

private const val SEARCH_LIMIT = 300
private const val SEARCH_DELAY_MS = 350L

/**
 * Folders of every storage. The music browser and the one opened from Lists ([forLists]) each
 * remember the last folder they showed. With [reveal], opens on that file's folder with the file
 * marked and in view, and where it goes is not remembered (the music browser keeps its own place).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BrowserScreen(onBack: () -> Unit, onAction: (BrowserAction) -> Unit, forLists: Boolean = false, reveal: File? = null) {
    val context = LocalContext.current
    val settings = context.app.settings
    val roots = remember { AudioFiles.storageRoots(context) }
    var folder by remember {
        val last = reveal?.parentFile?.absolutePath ?: if (forLists) settings.lastListsFolder else musicStart(settings)
        mutableStateOf(last?.let(::File)?.takeIf { it.isDirectory && it.canRead() })
    }
    var entries by remember { mutableStateOf<List<File>>(emptyList()) }
    var selection by remember { mutableStateOf<List<File>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<File>?>(null) }
    var addToList by remember { mutableStateOf<List<File>?>(null) }
    val listState = rememberLazyListState()
    // Folders shown as a grid of covers (as Music's albums) or as the list: one choice for the
    // first level (the music folder itself, e.g. its artists) and another for the folders inside.
    val gridPrefs = remember { context.getSharedPreferences("browser", android.content.Context.MODE_PRIVATE) }
    var gridTop by remember { mutableStateOf(gridPrefs.getBoolean("grid_top", gridPrefs.getBoolean("grid", false))) }
    var gridInside by remember { mutableStateOf(gridPrefs.getBoolean("grid", false)) }
    val topLevel = folder?.absolutePath?.let { path -> settings.musicFolder?.trimEnd('/') == path.trimEnd('/') } == true
    val grid = if (topLevel) gridTop else gridInside
    // Favorite folders, under the storages at the top.
    var favorites by remember { mutableStateOf(com.sadurni.attacca.library.FavoriteFolders.all(context)) }
    val favoriteFiles = favorites.map(::File).filter { it.isDirectory }
    fun toggleFavorite(dir: File) {
        val now = com.sadurni.attacca.library.FavoriteFolders.toggle(context, dir.absolutePath)
        favorites = com.sadurni.attacca.library.FavoriteFolders.all(context)
        android.widget.Toast.makeText(
            context,
            if (now) R.string.favorite_folder_added else R.string.favorite_folder_removed,
            android.widget.Toast.LENGTH_SHORT,
        ).show()
    }

    // Where each folder was scrolled to, so going back up returns to the same place.
    val positions = remember { HashMap<String, Pair<Int, Int>>() }
    var shownKey by remember { mutableStateOf<String?>(null) }
    var previousFolder by remember { mutableStateOf<File?>(null) }

    LaunchedEffect(folder) {
        when {
            reveal != null -> Unit
            forLists -> settings.lastListsFolder = folder?.absolutePath
            else -> settings.lastBrowsedFolder = folder?.absolutePath
        }
        val key = folder?.absolutePath ?: ""
        // Stop recording under the folder left behind before this one scrolls.
        shownKey = null
        entries = withContext(Dispatchers.IO) { folder?.let(AudioFiles::listFolder) ?: roots }
        // The revealed file a few rows below the top, in its own folder only.
        val revealed = entries.indexOfFirst { it.absolutePath == reveal?.absolutePath }
            .takeIf { it >= 0 && folder?.absolutePath == reveal?.parentFile?.absolutePath }
        // Coming back up from a subfolder never seen scrolled here: that subfolder near the top.
        val cameFrom = entries.indexOfFirst { it.absolutePath == previousFolder?.absolutePath }.takeIf { it >= 0 }
        val saved = positions[key]
        when {
            revealed != null -> listState.scrollToItem((revealed - 2).coerceAtLeast(0))
            saved != null -> listState.scrollToItem(saved.first, saved.second)
            cameFrom != null -> listState.scrollToItem((cameFrom - 2).coerceAtLeast(0))
            else -> listState.scrollToItem(0)
        }
        previousFolder = folder
        shownKey = key
    }

    // Only once the folder's own position is in place (before that the list still shows the
    // folder left behind).
    LaunchedEffect(shownKey) {
        val key = shownKey ?: return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { if (shownKey == key) positions[key] = it }
    }

    // The music folder in memory for searching, built in the background while browsing.
    LaunchedEffect(Unit) {
        settings.musicFolder?.let(::File)?.takeIf { it.isDirectory }?.let { music ->
            withContext(com.sadurni.attacca.Background.folders) { AudioFiles.warmSearchIndex(music) }
        }
    }

    // Search below the current folder (or every storage) as the user types.
    LaunchedEffect(query, folder) {
        found = null
        if (query.trim().length < 2) return@LaunchedEffect
        delay(SEARCH_DELAY_MS)
        val context = coroutineContext
        found = withContext(Dispatchers.IO) {
            AudioFiles.search(folder?.let(::listOf) ?: roots, query, SEARCH_LIMIT) { !context.isActive }
        }
    }

    fun goUp() {
        val current = folder ?: return onBack()
        folder = if (roots.any { it.absolutePath == current.absolutePath }) null else current.parentFile
    }
    BackHandler(enabled = selection != null || searching || folder != null) {
        when {
            // A search started while selecting closes first; the selection stays.
            selection != null && searching -> {
                searching = false
                query = ""
            }
            selection != null -> selection = null
            searching -> {
                searching = false
                query = ""
            }
            else -> goUp()
        }
    }

    val isRoot = folder == null
    val shown: List<File> = when {
        query.isBlank() && isRoot -> entries + favoriteFiles.filter { f -> entries.none { it.absolutePath == f.absolutePath } }
        query.isBlank() -> entries
        else -> {
            val here = entries.filter { matchesQuery(query, it.name) }
            here + (found ?: emptyList()).filter { f -> here.none { it.absolutePath == f.absolutePath } }
        }
    }

    fun toggle(file: File) {
        val current = selection ?: emptyList()
        selection = if (current.any { it.absolutePath == file.absolutePath }) {
            current.filter { it.absolutePath != file.absolutePath }
        } else {
            current + file
        }
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        val selected = selection
        if (selected != null) {
            SelectionHeader(
                count = selected.size,
                onClose = { selection = null },
                onSelectAll = { selection = if (selected.size == shown.size) emptyList() else shown },
            ) {
                // Searching keeps the selection: mark a track, look for another, mark it too.
                if (!searching) {
                    IconButton(onClick = { searching = true }) { Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.search)) }
                }
                IconButton(onClick = {
                    onAction(BrowserAction.PlayFiles(selected))
                    selection = null
                }, enabled = selected.isNotEmpty()) { Icon(Icons.Rounded.PlayArrow, stringResource(R.string.play_selection)) }
                IconButton(onClick = {
                    onAction(BrowserAction.AddToQueue(selected))
                    selection = null
                }, enabled = selected.isNotEmpty()) { Icon(Icons.Rounded.AddToQueue, stringResource(R.string.add_to_queue)) }
                IconButton(onClick = { addToList = selected }, enabled = selected.isNotEmpty()) {
                    Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, stringResource(R.string.add_to_list))
                }
                IconButton(onClick = {
                    com.sadurni.attacca.ui.common.ShareTracks.share(context, selected.filter(AudioFiles::isAudio).map { it.absolutePath })
                    selection = null
                }, enabled = selected.any(AudioFiles::isAudio)) { Icon(Icons.Rounded.Share, stringResource(R.string.share)) }
                val pair = selected.takeIf { it.size == 2 && it.all(AudioFiles::isAudio) }
                if (pair != null) {
                    IconButton(onClick = {
                        onAction(BrowserAction.Bridge(pair[0].absolutePath, pair[1].absolutePath))
                        selection = null
                    }) { Icon(Icons.Rounded.Route, stringResource(R.string.list_bridge_action)) }
                }
                val single = selected.singleOrNull()?.takeIf { AudioFiles.isAudio(it) }
                if (single != null) {
                    IconButton(onClick = {
                        onAction(BrowserAction.SeedList(single))
                        selection = null
                    }) { Icon(Icons.Rounded.AutoAwesome, stringResource(R.string.seed_list_title)) }
                }
            }
        } else {
            ScreenHeader(
                title = folder?.name ?: stringResource(R.string.browser_storage),
                onBack = { goUp() },
                actions = {
                    // Up one folder (Back does the same); ☰ stays for the side menu.
                    if (folder != null) {
                        IconButton(onClick = { goUp() }) {
                            Icon(Icons.Rounded.ArrowUpward, contentDescription = stringResource(R.string.browser_up))
                        }
                    }
                    IconButton(onClick = {
                        searching = !searching
                        if (!searching) query = ""
                    }) { Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.search)) }
                    // Covers in a grid, or the list.
                    if (folder != null) {
                        IconButton(onClick = {
                            if (topLevel) {
                                gridTop = !gridTop
                                gridPrefs.edit().putBoolean("grid_top", gridTop).apply()
                            } else {
                                gridInside = !gridInside
                                gridPrefs.edit().putBoolean("grid", gridInside).apply()
                            }
                        }) {
                            Icon(
                                if (grid) Icons.AutoMirrored.Rounded.ViewList else Icons.Rounded.GridView,
                                contentDescription = stringResource(if (grid) R.string.browser_as_list else R.string.browser_as_grid),
                            )
                        }
                    }
                    // Favorite folders from any folder (music opens inside its own folder, far from the storages).
                    var favoritesOpen by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { favoritesOpen = true }, modifier = Modifier.tipAnchor("browser.favorites")) {
                            Icon(Icons.Rounded.FolderSpecial, contentDescription = stringResource(R.string.favorite_folders))
                        }
                        DropdownMenu(expanded = favoritesOpen, onDismissRequest = { favoritesOpen = false }) {
                            if (favoriteFiles.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.favorite_folders_none), color = LocalAttaccaColors.current.textGrayed) },
                                    onClick = { favoritesOpen = false },
                                )
                            }
                            for (dir in favoriteFiles) {
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(dir.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(
                                                dir.parent.orEmpty(),
                                                fontSize = AttaccaType.caption,
                                                color = LocalAttaccaColors.current.textGrayed,
                                                maxLines = 1,
                                                overflow = TextOverflow.StartEllipsis,
                                            )
                                        }
                                    },
                                    leadingIcon = { Icon(Icons.Rounded.FolderSpecial, contentDescription = null) },
                                    enabled = dir.absolutePath != folder?.absolutePath,
                                    onClick = {
                                        favoritesOpen = false
                                        searching = false
                                        query = ""
                                        folder = dir
                                    },
                                )
                            }
                        }
                    }
                    val current = folder
                    if (current != null) {
                        var menuOpen by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = stringResource(R.string.browser_play_folder))
                            }
                            FolderMenu(
                                expanded = menuOpen,
                                onDismiss = { menuOpen = false },
                                onPlay = { recursive ->
                                    menuOpen = false
                                    onAction(BrowserAction.PlayFolder(current, null, recursive))
                                },
                                onAddToQueue = {
                                    menuOpen = false
                                    onAction(BrowserAction.AddToQueue(listOf(current)))
                                },
                                onInclusion = {
                                    menuOpen = false
                                    onAction(BrowserAction.Inclusion(current))
                                },
                                onRepair = {
                                    menuOpen = false
                                    onAction(BrowserAction.Repair(listOf(current.absolutePath)))
                                },
                                favorite = favorites.contains(current.absolutePath),
                                onFavorite = {
                                    menuOpen = false
                                    toggleFavorite(current)
                                },
                            )
                        }
                    }
                },
            )
        }
        if (searching) {
            SearchField(query, { query = it }, onClose = {
                searching = false
                query = ""
            })
        }
        if (folder != null) {
            Text(
                folder!!.absolutePath,
                fontSize = AttaccaType.caption,
                color = LocalAttaccaColors.current.textGrayed,
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        if (query.trim().length >= 2 && found == null) {
            Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(if (query.isBlank()) R.string.browser_empty else R.string.search_nothing),
                    color = LocalAttaccaColors.current.textGrayed,
                )
            }
            return@Column
        }
        if (grid && !isRoot) {
            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(118.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
            ) {
                // Folders as covers; files (the tracks of an album) as the list, across the whole width.
                items(
                    shown.size,
                    key = { shown[it].absolutePath },
                    span = { androidx.compose.foundation.lazy.grid.GridItemSpan(if (shown[it].isDirectory) 1 else maxLineSpan) },
                ) { index ->
                    val file = shown[index]
                    val inSelection = selection
                    val isSelected = inSelection?.any { it.absolutePath == file.absolutePath }
                    if (!AudioFiles.isFolder(file)) {
                        BrowserRow(
                            file = file,
                            isRoot = false,
                            subtitle = null,
                            selected = isSelected,
                            highlighted = file.absolutePath == reveal?.absolutePath,
                            onClick = {
                                when {
                                    inSelection != null -> toggle(file)
                                    AudioFiles.isPlaylist(file) -> onAction(BrowserAction.OpenPlaylist(file))
                                    else -> onAction(BrowserAction.PlayFolder(file.parentFile!!, file, recursive = false))
                                }
                            },
                            onLongClick = { toggle(file) },
                            onAction = onAction,
                        )
                        return@items
                    }
                    BrowserCell(
                        file = file,
                        selected = isSelected,
                        highlighted = file.absolutePath == reveal?.absolutePath,
                        onClick = {
                            when {
                                inSelection != null -> toggle(file)
                                AudioFiles.isFolder(file) -> {
                                    folder = file
                                    searching = false
                                    query = ""
                                }
                                AudioFiles.isPlaylist(file) -> onAction(BrowserAction.OpenPlaylist(file))
                                else -> onAction(BrowserAction.PlayFolder(file.parentFile!!, file, recursive = false))
                            }
                        },
                        onLongClick = { toggle(file) },
                    )
                }
            }
            return@Column
        }
        FastScrollBox(listState, Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                items(shown, key = { it.absolutePath }) { file ->
                    val inSelection = selection
                    val isSelected = inSelection?.any { it.absolutePath == file.absolutePath }
                    val nested = !isRoot && file.parentFile?.absolutePath != folder?.absolutePath
                    val favoriteRow = isRoot && query.isBlank() && favoriteFiles.any { it.absolutePath == file.absolutePath }
                    com.sadurni.attacca.ui.common.SwipeRow(
                        itemKey = file.absolutePath,
                        queue = false,
                        enabled = inSelection == null && !isRoot,
                        onAction = { action ->
                            when (action) {
                                com.sadurni.attacca.settings.RowAction.PLAY_NEXT -> onAction(BrowserAction.PlayNext(listOf(file)))
                                com.sadurni.attacca.settings.RowAction.ADD_TO_QUEUE -> onAction(BrowserAction.AddToQueue(listOf(file)))
                                com.sadurni.attacca.settings.RowAction.ADD_TO_FAVORITES -> onAction(BrowserAction.AddToFavorites(listOf(file)))
                                else -> Unit
                            }
                        },
                    ) {
                    BrowserRow(
                        // The tips point at the first file (or the first entry without files).
                        tipTarget = file == (shown.firstOrNull { !it.isDirectory } ?: shown.firstOrNull()),
                        file = file,
                        isRoot = isRoot && !favoriteRow,
                        favorite = favorites.contains(file.absolutePath),
                        onFavorite = { toggleFavorite(file) },
                        // Where a search result is, below the folder shown above.
                        subtitle = when {
                            favoriteRow -> file.parentFile?.absolutePath
                            nested -> folder?.let { file.parentFile?.toRelativeString(it) }?.ifEmpty { null }
                            isRoot && query.isNotBlank() -> file.parentFile?.absolutePath
                            else -> null
                        },
                        selected = isSelected,
                        highlighted = file.absolutePath == reveal?.absolutePath,
                        onClick = {
                            when {
                                inSelection != null -> toggle(file)
                                isRoot && query.isBlank() || AudioFiles.isFolder(file) -> {
                                    folder = file
                                    searching = false
                                    query = ""
                                }
                                AudioFiles.isPlaylist(file) -> onAction(BrowserAction.OpenPlaylist(file))
                                else -> onAction(BrowserAction.PlayFolder(file.parentFile!!, file, recursive = false))
                            }
                        },
                        onLongClick = { if (!(isRoot && query.isBlank())) toggle(file) },
                        onAction = onAction,
                    )
                    }
                }
            }
        }
    }

    addToList?.let { files ->
        AddToListDialog(
            onPick = { list ->
                onAction(BrowserAction.AddToList(files, list))
                addToList = null
                selection = null
            },
            onDismiss = { addToList = null },
        )
    }
}

/** A folder or file as a cover in the grid: the picture, its marks and name below; a tap opens or plays it, a long press selects. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BrowserCell(file: File, selected: Boolean?, highlighted: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val accent = LocalAccent.current
    val colors = LocalAttaccaColors.current
    Column(Modifier.rowMark(selected = selected == true, marked = highlighted).combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(1f)) {
            val fallback: @Composable () -> Unit = {
                Box(Modifier.fillMaxSize().background(colors.surface, com.sadurni.attacca.ui.theme.AttaccaShapes.small), contentAlignment = Alignment.Center) {
                    com.sadurni.attacca.ui.theme.ThemedIcon(
                        when {
                            AudioFiles.isFolder(file) -> Icons.Rounded.Folder
                            AudioFiles.isPlaylist(file) -> Icons.AutoMirrored.Rounded.QueueMusic
                            else -> Icons.Rounded.MusicNote
                        },
                        contentDescription = null, tint = colors.textGrayed, modifier = Modifier.size(40.dp),
                    )
                }
            }
            when {
                AudioFiles.isFolder(file) -> com.sadurni.attacca.ui.common.FolderThumbnail(file, maxWidth, fallback)
                AudioFiles.isAudio(file) -> com.sadurni.attacca.ui.common.TrackThumbnail(file.absolutePath, maxWidth)
                else -> fallback()
            }
            if (selected != null) {
                Icon(
                    if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (selected) accent else androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(26.dp),
                )
            }
        }
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (AudioFiles.isFolder(file)) {
                com.sadurni.attacca.ui.common.FolderDamagedMark(file.absolutePath)
                com.sadurni.attacca.ui.common.FolderExcludedMark(file.absolutePath)
            } else if (AudioFiles.isAudio(file)) {
                com.sadurni.attacca.ui.common.DamagedMark(file.absolutePath)
                com.sadurni.attacca.ui.common.ExcludedMark(file.absolutePath)
            }
            Text(file.name, fontSize = AttaccaType.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BrowserRow(
    file: File,
    isRoot: Boolean,
    subtitle: String?,
    selected: Boolean?,
    highlighted: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onAction: (BrowserAction) -> Unit,
    tipTarget: Boolean = false,
    favorite: Boolean = false,
    onFavorite: () -> Unit = {},
) {
    val context = LocalContext.current
    val ignoredList by com.sadurni.attacca.library.IgnoredPaths.all.collectAsStateWithLifecycle()
    val ignored = !isRoot && remember(ignoredList, file.path) { com.sadurni.attacca.library.IgnoredPaths.isIgnored(file.path) }
    fun toggleIgnored() {
        val now = com.sadurni.attacca.library.IgnoredPaths.toggle(context, file.absolutePath)
        android.widget.Toast.makeText(context, if (now) R.string.ignored_added else R.string.ignored_removed, android.widget.Toast.LENGTH_LONG).show()
    }
    val accent = LocalAccent.current
    val colors = LocalAttaccaColors.current
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier
            .tipAnchor("browser.row", enabled = tipTarget)
            .fillMaxWidth()
            .rowMark(selected = selected == true, marked = highlighted)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val icon = when {
            selected == true -> Icons.Rounded.CheckCircle
            selected == false -> Icons.Rounded.RadioButtonUnchecked
            isRoot && subtitle == null -> Icons.Rounded.SdStorage
            AudioFiles.isFolder(file) && favorite -> Icons.Rounded.FolderSpecial
            AudioFiles.isFolder(file) -> Icons.Rounded.Folder
            AudioFiles.isPlaylist(file) -> Icons.AutoMirrored.Rounded.QueueMusic
            else -> Icons.Rounded.MusicNote
        }
        val plainIcon: @Composable () -> Unit = {
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                com.sadurni.attacca.ui.theme.ThemedIcon(
                    icon,
                    contentDescription = null,
                    tint = if (selected == true) accent else colors.textGrayed,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        // The cover of a folder (its picture or its first track's) or a track; icons while selecting.
        when {
            selected != null || (isRoot && subtitle == null) -> plainIcon()
            AudioFiles.isFolder(file) -> com.sadurni.attacca.ui.common.FolderThumbnail(file, 44.dp, plainIcon)
            AudioFiles.isAudio(file) -> com.sadurni.attacca.ui.common.TrackThumbnail(file.absolutePath, 44.dp)
            else -> plainIcon()
        }
        Column(Modifier.weight(1f).padding(start = 12.dp, top = 8.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (AudioFiles.isFolder(file)) {
                    if (!(isRoot && subtitle == null)) {
                        com.sadurni.attacca.ui.common.FolderDamagedMark(file.absolutePath)
                        com.sadurni.attacca.ui.common.FolderExcludedMark(file.absolutePath)
                    }
                } else if (AudioFiles.isAudio(file)) {
                    com.sadurni.attacca.ui.common.DamagedMark(file.absolutePath)
                    com.sadurni.attacca.ui.common.ExcludedMark(file.absolutePath)
                }
                Text(
                    if (isRoot && subtitle == null) file.absolutePath else file.name,
                    fontSize = AttaccaType.body,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (subtitle != null) {
                Text(subtitle, fontSize = AttaccaType.caption, color = colors.textGrayed, maxLines = 1, overflow = TextOverflow.StartEllipsis)
            }
            if (ignored) {
                Text(stringResource(R.string.ignored_mark), fontSize = AttaccaType.caption, color = colors.textGrayed)
            }
        }
        if (selected == null && !(isRoot && subtitle == null)) {
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.tipAnchor("browser.rowMenu", enabled = tipTarget)) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.more_options))
                }
                if (AudioFiles.isFolder(file)) {
                    FolderMenu(
                        expanded = menuOpen,
                        onDismiss = { menuOpen = false },
                        onPlay = { recursive ->
                            menuOpen = false
                            onAction(BrowserAction.PlayFolder(file, null, recursive))
                        },
                        onAddToQueue = {
                            menuOpen = false
                            onAction(BrowserAction.AddToQueue(listOf(file)))
                        },
                        onInclusion = {
                            menuOpen = false
                            onAction(BrowserAction.Inclusion(file))
                        },
                        onRepair = {
                            menuOpen = false
                            onAction(BrowserAction.Repair(listOf(file.absolutePath)))
                        },
                        favorite = favorite,
                        onFavorite = {
                            menuOpen = false
                            onFavorite()
                        },
                        ignored = ignored,
                        onIgnore = {
                            menuOpen = false
                            toggleIgnored()
                        },
                    )
                } else {
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.play_selection)) }, onClick = {
                            menuOpen = false
                            onAction(BrowserAction.PlayFiles(listOf(file)))
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.add_to_queue)) }, onClick = {
                            menuOpen = false
                            onAction(BrowserAction.AddToQueue(listOf(file)))
                        })
                        if (AudioFiles.isAudio(file)) {
                            val shareContext = androidx.compose.ui.platform.LocalContext.current
                            DropdownMenuItem(text = { Text(stringResource(R.string.share)) }, onClick = {
                                menuOpen = false
                                com.sadurni.attacca.ui.common.ShareTracks.share(shareContext, listOf(file.absolutePath))
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.seed_list_title)) }, onClick = {
                                menuOpen = false
                                onAction(BrowserAction.SeedList(file))
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.quick_list)) }, onClick = {
                                menuOpen = false
                                onAction(BrowserAction.QuickList(file))
                            })
                            DropdownMenuItem(text = { Text(inclusionMenuLabel()) }, onClick = {
                                menuOpen = false
                                onAction(BrowserAction.Inclusion(file))
                            })
                            if (file.extension.equals("mp3", ignoreCase = true)) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.damaged_repair)) }, onClick = {
                                    menuOpen = false
                                    onAction(BrowserAction.Repair(listOf(file.absolutePath)))
                                })
                            }
                            DropdownMenuItem(text = { Text(stringResource(if (ignored) R.string.ignored_stop else R.string.ignored_start)) }, onClick = {
                                menuOpen = false
                                toggleIgnored()
                            })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onPlay: (recursive: Boolean) -> Unit,
    onAddToQueue: () -> Unit,
    onInclusion: () -> Unit,
    onRepair: () -> Unit,
    favorite: Boolean,
    onFavorite: () -> Unit,
    ignored: Boolean = false,
    onIgnore: (() -> Unit)? = null,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text(stringResource(R.string.browser_play_folder)) }, onClick = { onPlay(false) })
        DropdownMenuItem(text = { Text(stringResource(R.string.browser_play_folder_recursive)) }, onClick = { onPlay(true) })
        DropdownMenuItem(text = { Text(stringResource(R.string.add_to_queue)) }, onClick = onAddToQueue)
        DropdownMenuItem(text = { Text(inclusionMenuLabel()) }, onClick = onInclusion)
        DropdownMenuItem(text = { Text(stringResource(R.string.damaged_repair_all)) }, onClick = onRepair)
        DropdownMenuItem(
            text = { Text(stringResource(if (favorite) R.string.favorite_folder_remove else R.string.favorite_folder_add)) },
            onClick = onFavorite,
        )
        if (onIgnore != null) {
            DropdownMenuItem(text = { Text(stringResource(if (ignored) R.string.ignored_stop else R.string.ignored_start)) }, onClick = onIgnore)
        }
    }
}

/** "Allow list…" or "Block list…", as the inclusion list is set. */
@Composable
fun inclusionMenuLabel(): String {
    val chain by LocalContext.current.app.settings.chain.collectAsStateWithLifecycle()
    return stringResource(
        if (chain.inclusionMode == com.sadurni.attacca.library.InclusionMode.ALLOW) R.string.inclusion_menu_allow else R.string.inclusion_menu_block,
    )
}

/**
 * Where the music browser opens: back where it was left inside the library folder, or at the
 * library folder itself when it was left outside it (or never opened); anywhere without one.
 */
private fun musicStart(settings: com.sadurni.attacca.settings.AppSettings): String? {
    val library = settings.musicFolder?.takeIf { File(it).isDirectory } ?: return settings.lastBrowsedFolder
    val last = settings.lastBrowsedFolder ?: return library
    val inside = last == library || last.startsWith(library.trimEnd('/') + "/")
    return if (inside) last else library
}
