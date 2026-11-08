package com.sadurni.attacca.ui.music

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.AddToQueue
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Mood
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.sadurni.attacca.ui.common.Icon
import com.sadurni.attacca.ui.common.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sadurni.attacca.R
import com.sadurni.attacca.app
import com.sadurni.attacca.chain.Mood
import com.sadurni.attacca.library.MusicAlbum
import com.sadurni.attacca.library.MusicArtist
import com.sadurni.attacca.library.MusicLibrary
import com.sadurni.attacca.library.MusicTrack
import com.sadurni.attacca.settings.RowAction
import com.sadurni.attacca.ui.browser.BrowserAction
import com.sadurni.attacca.ui.browser.inclusionMenuLabel
import com.sadurni.attacca.ui.common.FastScrollBox
import com.sadurni.attacca.ui.common.ScreenHeader
import com.sadurni.attacca.ui.common.SearchField
import com.sadurni.attacca.ui.common.SelectionHeader
import com.sadurni.attacca.ui.common.TrackRow
import com.sadurni.attacca.ui.common.TrackThumbnail
import com.sadurni.attacca.ui.common.formatTotalTime
import com.sadurni.attacca.ui.common.matchesQuery
import com.sadurni.attacca.ui.common.tipAnchor
import com.sadurni.attacca.ui.player.AddToListDialog
import com.sadurni.attacca.ui.settings.Hint
import com.sadurni.attacca.ui.settings.SectionTitle
import com.sadurni.attacca.ui.theme.AttaccaType
import com.sadurni.attacca.ui.theme.LocalAccent
import com.sadurni.attacca.ui.theme.LocalAttaccaColors
import java.io.File

/** A place in the music view; the screen keeps a stack of them, Back goes down it. */
sealed interface MusicPlace {
    data object Home : MusicPlace
    data object Artists : MusicPlace
    data object Albums : MusicPlace
    data object Compilations : MusicPlace
    data object Tracks : MusicPlace
    data object Genres : MusicPlace
    data object Decades : MusicPlace
    data object Recent : MusicPlace
    data object Moods : MusicPlace
    data object Families : MusicPlace
    data class Artist(val name: String) : MusicPlace
    data class Album(val key: String) : MusicPlace
    data class Genre(val name: String) : MusicPlace
    data class Decade(val decade: Int) : MusicPlace
    data class OfMood(val mood: Mood) : MusicPlace
    data class OfFamily(val family: Int) : MusicPlace
}

/** Where "Go to album" / "Go to artist" opens the music view: the track and which of the two. */
data class MusicTarget(val path: String, val artist: Boolean)

/** The places to show for [target]: home, then the artist, then (for an album) the album. */
fun placesFor(library: MusicLibrary, target: MusicTarget): List<MusicPlace> {
    val album = library.albumOf(target.path)
    val artist = library.artistOf(target.path)
    return buildList {
        add(MusicPlace.Home)
        if (artist != null) add(MusicPlace.Artist(artist.name)) else if (album != null && album.compilation) add(MusicPlace.Compilations)
        if (!target.artist && album != null) add(MusicPlace.Album(album.key))
    }
}

private const val RECENT_ALBUMS = 100
private const val SEARCH_RESULTS = 50

/** Longest list played as it is from a tapped track; longer ones play the track's album. */
private const val MAX_PLAYED_LIST = 5000

/**
 * The music folder as a music database: artists, albums, compilations, tracks, genres, decades,
 * recently added, and by mood and sound family (from the analysis). Built from the tags of the
 * library scan; works like Folders: tap to open or play from there, long press to select tracks,
 * ⋮ for the rest.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MusicScreen(
    places: SnapshotStateList<MusicPlace>,
    target: MusicTarget?,
    onTargetShown: () -> Unit,
    onBack: () -> Unit,
    onAction: (BrowserAction) -> Unit,
    onGoToFolder: (String) -> Unit,
    /** The track playing, marked in the track lists. */
    playingId: String? = null,
) {
    val context = LocalContext.current
    val app = context.app
    val scan by app.libraryScanner.last.collectAsStateWithLifecycle()
    val scanning by app.libraryScanner.progress.collectAsStateWithLifecycle()
    val playback by app.settings.playback.collectAsStateWithLifecycle()
    val byTrackArtist by app.music.byTrackArtist.collectAsStateWithLifecycle()
    val grid by app.music.grid.collectAsStateWithLifecycle()
    val albumsByYear by app.music.byYear.collectAsStateWithLifecycle()
    val artistGrid by app.music.artistGrid.collectAsStateWithLifecycle()
    val artistByYear by app.music.artistByYear.collectAsStateWithLifecycle()
    val listening by app.listening.version.collectAsStateWithLifecycle()
    val library by produceState<MusicLibrary?>(null, scan?.finishedAt, playback.albumsByFolder, byTrackArtist) {
        value = app.music.library(scan?.finishedAt ?: 0L, playback.albumsByFolder)
    }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(library) { if (library != null) loaded = true }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var selection by remember { mutableStateOf<List<String>?>(null) }
    // How deep the user was when the selection started: places opened since then keep it, and Back
    // leaves them before it clears the selection (mark a track, search, open an album, mark another).
    var selectionDepth by remember { mutableStateOf(1) }
    var addToList by remember { mutableStateOf<List<String>?>(null) }
    // Shuffling tracks of several albums: by album or by track, as the user picks.
    var shuffleAsk by remember { mutableStateOf<Pair<String, List<List<String>>>?>(null) }
    // Then, by tracks: truly at random or chained from a random track.
    var trackShuffleAsk by remember { mutableStateOf<Pair<String, List<String>>?>(null) }
    // Or by albums: at random or chained (albums as wholes) from a random album.
    var albumShuffleAsk by remember { mutableStateOf<Pair<String, List<List<String>>>?>(null) }
    var chainingAlbums by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // The track a "Go to album" came for: the album opens on it, marked.
    var focus by remember { mutableStateOf<String?>(null) }
    // A "Go to album / artist" arrives: show it.
    LaunchedEffect(target, library) {
        val lib = library ?: return@LaunchedEffect
        val t = target ?: return@LaunchedEffect
        places.clear()
        places.addAll(placesFor(lib, t))
        focus = t.path.takeIf { !t.artist }
        onTargetShown()
    }
    if (places.isEmpty()) places.add(MusicPlace.Home)
    val place = places.last()

    fun open(p: MusicPlace) {
        places.add(p)
        searching = false
        query = ""
    }
    BackHandler(enabled = selection != null || searching || places.size > 1) {
        when {
            selection != null && searching -> {
                searching = false
                query = ""
            }
            selection != null && places.size > selectionDepth -> places.removeAt(places.lastIndex)
            selection != null -> selection = null
            searching -> {
                searching = false
                query = ""
            }
            else -> places.removeAt(places.lastIndex)
        }
    }

    val files = { paths: List<String> -> paths.map(::File) }

    /**
     * Albums in random order with each artist's albums spread over the whole list, as Reorder by
     * random albums does (compilations and albums without artist stand on their own).
     */
    fun spreadAlbums(albums: List<List<String>>): List<List<String>> {
        val lib = library ?: return albums.shuffled()
        val artists = albums.map { paths ->
            val album = paths.firstOrNull()?.let(lib::albumOf)
            album?.artist?.lowercase() ?: "album:" + (album?.key ?: paths.firstOrNull().orEmpty())
        }
        return com.sadurni.attacca.library.AlbumShuffle.spread(artists).map { albums[it] }
    }
    /** Several albums, each in one block (an artist, albums shuffled or chained): the list is shown by album. */
    fun wholeAlbums(paths: List<String>): Boolean {
        val lib = library ?: return false
        val blocks = ArrayList<String>()
        for (p in paths) {
            val key = lib.albumOf(p)?.key ?: return false
            if (blocks.lastOrNull() != key) blocks.add(key)
        }
        return blocks.size > 1 && blocks.size == blocks.toSet().size
    }
    fun play(name: String, paths: List<String>, start: Int = 0) =
        onAction(BrowserAction.PlayTracks(name, files(paths), start, byAlbums = wholeAlbums(paths)))

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        val lib = library
        val selected = selection
        if (selected != null) {
            SelectionHeader(count = selected.size, onClose = { selection = null }, onSelectAll = null) {
                // Searching keeps the selection: mark a track, look for another, mark it too.
                if (!searching) {
                    IconButton(onClick = { searching = true }) { Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.search)) }
                }
                IconButton(onClick = {
                    play(context.getString(R.string.selection_name), selected)
                    selection = null
                }, enabled = selected.isNotEmpty()) { Icon(Icons.Rounded.PlayArrow, stringResource(R.string.play_selection)) }
                IconButton(onClick = {
                    onAction(BrowserAction.AddToQueue(files(selected)))
                    selection = null
                }, enabled = selected.isNotEmpty()) { Icon(Icons.Rounded.AddToQueue, stringResource(R.string.add_to_queue)) }
                IconButton(onClick = { addToList = selected }, enabled = selected.isNotEmpty()) {
                    Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, stringResource(R.string.add_to_list))
                }
                IconButton(onClick = {
                    com.sadurni.attacca.ui.common.ShareTracks.share(context, selected)
                    selection = null
                }, enabled = selected.isNotEmpty()) { Icon(Icons.Rounded.Share, stringResource(R.string.share)) }
                if (selected.size == 2) {
                    IconButton(onClick = {
                        onAction(BrowserAction.Bridge(selected[0], selected[1]))
                        selection = null
                    }) { Icon(Icons.Rounded.Route, stringResource(R.string.list_bridge_action)) }
                }
                val single = selected.singleOrNull()
                if (single != null) {
                    IconButton(onClick = {
                        onAction(BrowserAction.SeedList(File(single)))
                        selection = null
                    }) { Icon(Icons.Rounded.AutoAwesome, stringResource(R.string.seed_list_title)) }
                }
            }
        } else {
            ScreenHeader(
                title = lib?.let { titleOf(place, it) } ?: stringResource(R.string.nav_music),
                onBack = { if (places.size > 1) places.removeAt(places.lastIndex) else onBack() },
                root = places.size == 1,
            ) {
                IconButton(onClick = {
                    searching = !searching
                    if (!searching) query = ""
                }) { Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.search)) }
                // Only what changes the view on screen: how artists are made where artists show,
                // the album view and order where albums do (an artist's page keeps its own).
                val showsArtists = place == MusicPlace.Home || place == MusicPlace.Artists || place is MusicPlace.Artist
                val albumView = place == MusicPlace.Albums || place == MusicPlace.Compilations || place == MusicPlace.Recent ||
                    place is MusicPlace.Genre || place is MusicPlace.Decade
                val artistView = place is MusicPlace.Artist
                // Recently added keeps its own order (newest first).
                val sortable = albumView && place != MusicPlace.Recent
                if (showsArtists || albumView) {
                    var menuOpen by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menuOpen = true }, modifier = Modifier.tipAnchor("music.menu")) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.music_options))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (showsArtists) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(if (byTrackArtist) R.string.music_artists_by_album else R.string.music_artists_by_track)) },
                                    onClick = {
                                        menuOpen = false
                                        app.music.setByTrackArtist(!byTrackArtist)
                                    },
                                )
                            }
                            if (albumView || artistView) {
                                val shownGrid = if (artistView) artistGrid else grid
                                DropdownMenuItem(
                                    text = { Text(stringResource(if (shownGrid) R.string.music_albums_list else R.string.music_albums_grid)) },
                                    onClick = {
                                        menuOpen = false
                                        if (artistView) app.music.setArtistGrid(!artistGrid) else app.music.setGrid(!grid)
                                    },
                                )
                            }
                            if (sortable || artistView) {
                                val shownByYear = if (artistView) artistByYear else albumsByYear
                                DropdownMenuItem(
                                    text = { Text(stringResource(if (shownByYear) R.string.music_albums_by_title else R.string.music_albums_by_year)) },
                                    onClick = {
                                        menuOpen = false
                                        if (artistView) app.music.setArtistByYear(!artistByYear) else app.music.setByYear(!albumsByYear)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        if (searching) {
            SearchField(query, { query = it }, onClose = {
                searching = false
                query = ""
            })
        }
        when {
            app.settings.musicFolder == null -> {
                Hint(stringResource(R.string.music_no_folder))
                return@Column
            }
            lib == null && !loaded || scanning != null && (lib == null || lib.tracks.isEmpty()) -> {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                scanning?.let { Hint(stringResource(R.string.music_scanning, it.done, it.total)) }
                return@Column
            }
            lib == null || lib.tracks.isEmpty() -> {
                Hint(stringResource(R.string.music_not_scanned))
                Button(onClick = { app.settings.musicFolder?.let { app.libraryScanner.scan(File(it)) } }, modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.music_scan_now))
                }
                return@Column
            }
        }
        val l = lib!!
        // Listening counts change as tracks play; the rows read them again.
        listening.let { }
        val actions = MusicActions(
            play = ::play,
            shuffle = { name, paths ->
                // Each album's tracks in album order, the albums in the order they come.
                val groups = LinkedHashMap<String, MutableList<String>>()
                for (p in paths) groups.getOrPut(l.albumOf(p)?.key ?: p) { ArrayList() }.add(p)
                val albums = groups.map { (key, own) ->
                    val order = l.albums.firstOrNull { it.key == key }?.tracks?.map { it.path }
                    if (order != null) order.filter { it in own.toSet() } else own
                }
                if (albums.size > 1) shuffleAsk = name to albums else play(name, paths.shuffled(), 0)
            },
            open = ::open,
            onAction = onAction,
            onGoToFolder = onGoToFolder,
            selection = selection,
            playingId = playingId,
            focus = focus,
            toggle = { path ->
                val current = selection ?: emptyList<String>().also { selectionDepth = places.size }
                selection = if (path in current) current - path else current + path
            },
        )
        MusicPlaceContent(place, l, query, grid, albumsByYear, artistGrid, artistByYear, actions)
    }

    shuffleAsk?.let { (name, albums) ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { shuffleAsk = null },
            title = { Text(stringResource(R.string.music_shuffle_how)) },
            text = { Text(stringResource(R.string.music_shuffle_how_hint)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    shuffleAsk = null
                    albumShuffleAsk = name to albums
                }) { Text(stringResource(R.string.music_shuffle_albums)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = {
                    shuffleAsk = null
                    trackShuffleAsk = name to albums.flatten()
                }) { Text(stringResource(R.string.music_shuffle_tracks)) }
            },
        )
    }
    albumShuffleAsk?.let { (name, albums) ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { if (!chainingAlbums) albumShuffleAsk = null },
            title = { Text(stringResource(R.string.music_shuffle_albums_how)) },
            text = {
                Column {
                    Text(stringResource(R.string.music_shuffle_albums_how_hint))
                    if (chainingAlbums) Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("  " + stringResource(R.string.chain_building), fontSize = AttaccaType.secondary)
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(enabled = !chainingAlbums, onClick = {
                    chainingAlbums = true
                    scope.launch {
                        val lib = library
                        val artists = albums.map { paths -> paths.firstOrNull()?.let { lib?.albumOf(it)?.artist }.orEmpty() }
                        val order = runCatching { app.chainLists.chainAlbums(albums, artists, albums.indices.random()) }.getOrNull()
                        chainingAlbums = false
                        albumShuffleAsk = null
                        val ordered = order?.map { albums[it] } ?: spreadAlbums(albums)
                        play(name, ordered.flatten().take(MAX_PLAYED_LIST), 0)
                    }
                }) { Text(stringResource(R.string.music_shuffle_chained)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(enabled = !chainingAlbums, onClick = {
                    albumShuffleAsk = null
                    play(name, spreadAlbums(albums).flatten().take(MAX_PLAYED_LIST), 0)
                }) { Text(stringResource(R.string.music_shuffle_random)) }
            },
        )
    }

    trackShuffleAsk?.let { (name, paths) ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { trackShuffleAsk = null },
            title = { Text(stringResource(R.string.music_shuffle_tracks_how)) },
            text = { Text(stringResource(R.string.music_shuffle_tracks_how_hint)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    trackShuffleAsk = null
                    val shuffled = paths.shuffled().take(MAX_PLAYED_LIST)
                    onAction(BrowserAction.PlayTracks(name, shuffled.map(::File), 0, chain = true))
                }) { Text(stringResource(R.string.music_shuffle_chained)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = {
                    trackShuffleAsk = null
                    play(name, paths.shuffled().take(MAX_PLAYED_LIST), 0)
                }) { Text(stringResource(R.string.music_shuffle_random)) }
            },
        )
    }

    addToList?.let { paths ->
        AddToListDialog(
            onPick = { list ->
                onAction(BrowserAction.AddToList(paths.map(::File), list))
                addToList = null
                selection = null
            },
            onDismiss = { addToList = null },
        )
    }
}

/** What rows do: play, open a place, the browser's actions, go to a folder, select tracks. */
private class MusicActions(
    val play: (name: String, paths: List<String>, start: Int) -> Unit,
    /** Shuffles: tracks of one album at once; of several, asking whether by album or by track. */
    val shuffle: (name: String, paths: List<String>) -> Unit,
    val open: (MusicPlace) -> Unit,
    val onAction: (BrowserAction) -> Unit,
    val onGoToFolder: (String) -> Unit,
    val selection: List<String>?,
    val toggle: (String) -> Unit,
    val playingId: String? = null,
    /** The track a "Go to album" came for: scrolled to and marked in its album. */
    val focus: String? = null,
)

@Composable
private fun titleOf(place: MusicPlace, lib: MusicLibrary): String {
    val context = LocalContext.current
    return when (place) {
        MusicPlace.Home -> stringResource(R.string.nav_music)
        MusicPlace.Artists -> stringResource(R.string.music_artists)
        MusicPlace.Albums -> stringResource(R.string.music_albums)
        MusicPlace.Compilations -> stringResource(R.string.music_compilations)
        MusicPlace.Tracks -> stringResource(R.string.music_tracks)
        MusicPlace.Genres -> stringResource(R.string.music_genres)
        MusicPlace.Decades -> stringResource(R.string.music_decades)
        MusicPlace.Recent -> stringResource(R.string.music_recent)
        MusicPlace.Moods -> stringResource(R.string.music_moods)
        MusicPlace.Families -> stringResource(R.string.music_families)
        is MusicPlace.Artist -> place.name
        is MusicPlace.Album -> lib.albums.firstOrNull { it.key == place.key }?.title.orEmpty()
        is MusicPlace.Genre -> place.name.ifEmpty { stringResource(R.string.music_no_genre) }
        is MusicPlace.Decade -> decadeLabel(place.decade)
        is MusicPlace.OfMood -> context.getString(com.sadurni.attacca.ui.chain.moodRes(place.mood))
        is MusicPlace.OfFamily -> stringResource(R.string.music_families)
    }
}

@Composable
private fun decadeLabel(decade: Int): String = if (decade == 0) stringResource(R.string.music_no_year) else "${decade}s"

@Composable
private fun MusicPlaceContent(
    place: MusicPlace,
    lib: MusicLibrary,
    query: String,
    grid: Boolean,
    byYear: Boolean,
    artistGrid: Boolean,
    artistByYear: Boolean,
    a: MusicActions,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(place, byYear, artistByYear) { listState.scrollToItem(0) }
    fun albumsOf(list: List<MusicAlbum>) = list.filter { matchesQuery(query, it.title, it.artist ?: "") }
    // By year on request, except where the order is the point (recently added) or already by year (an artist).
    fun sortedAlbums(list: List<MusicAlbum>) = albumsOf(list).let { if (byYear) it.sortedWith(lib.byYear) else it }
    when (place) {
        MusicPlace.Home -> if (query.isBlank()) HomeList(lib, a) else SearchResults(lib, query, a)
        MusicPlace.Artists -> RowList(listState) {
            items(lib.artists.filter { matchesQuery(query, it.name) }, key = { "artist:" + it.name }) { ArtistRow(it, a) }
        }
        MusicPlace.Albums -> AlbumList(sortedAlbums(lib.albums), grid, listState, a)
        MusicPlace.Compilations -> AlbumList(sortedAlbums(lib.compilations), grid, listState, a)
        MusicPlace.Recent -> AlbumList(albumsOf(lib.recentlyAdded(RECENT_ALBUMS)), grid, listState, a)
        MusicPlace.Tracks -> {
            val shown = lib.tracks.filter { matchesQuery(query, it.title, it.tags.artist, it.tags.album) }
            TrackList(stringResource(R.string.music_tracks), shown, listState, a, lib)
        }
        MusicPlace.Genres -> RowList(listState) {
            items(lib.genres().filter { matchesQuery(query, it.name) }, key = { "genre:" + it.name }) { g ->
                GroupRow(Icons.Rounded.Category, g.name.ifEmpty { stringResource(R.string.music_no_genre) }, g.albums.size, g.trackCount) {
                    a.open(MusicPlace.Genre(g.name))
                }
            }
        }
        MusicPlace.Decades -> RowList(listState) {
            items(lib.decades(), key = { "decade:" + it.first }) { (decade, albums) ->
                GroupRow(Icons.Rounded.CalendarMonth, decadeLabel(decade), albums.size, albums.sumOf { it.tracks.size }) {
                    a.open(MusicPlace.Decade(decade))
                }
            }
        }
        is MusicPlace.Genre -> AlbumList(sortedAlbums(lib.genres().firstOrNull { it.name == place.name }?.albums.orEmpty()), grid, listState, a, playAs = titleOf(place, lib))
        is MusicPlace.Decade -> AlbumList(sortedAlbums(lib.decades().firstOrNull { it.first == place.decade }?.second.orEmpty()), grid, listState, a, playAs = titleOf(place, lib))
        is MusicPlace.Artist -> {
            val artist = lib.artists.firstOrNull { it.name == place.name }
            if (artist == null) {
                Hint(stringResource(R.string.search_nothing))
            } else {
                // Its own view and order: by year (as it comes) or by title.
                val own = albumsOf(artist.albums).let { if (artistByYear) it else it.sortedWith(lib.byTitle) }
                ArtistPage(artist, own, artistGrid, listState, a)
            }
        }
        is MusicPlace.Album -> {
            val album = lib.albums.firstOrNull { it.key == place.key }
            if (album == null) Hint(stringResource(R.string.search_nothing)) else AlbumPage(album, query, listState, a)
        }
        MusicPlace.Moods, MusicPlace.Families, is MusicPlace.OfMood, is MusicPlace.OfFamily -> ClassifiedContent(place, lib, query, listState, a)
    }
}

@Composable
private fun RowList(state: androidx.compose.foundation.lazy.LazyListState, content: LazyListScope.() -> Unit) {
    FastScrollBox(state, Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = state, content = content)
    }
}

@Composable
private fun HomeList(lib: MusicLibrary, a: MusicActions) {
    val state = rememberLazyListState()
    RowList(state) {
        item { HomeRow(Icons.Rounded.Person, stringResource(R.string.music_artists), lib.artists.size, first = true) { a.open(MusicPlace.Artists) } }
        item { HomeRow(Icons.Rounded.Album, stringResource(R.string.music_albums), lib.albums.size) { a.open(MusicPlace.Albums) } }
        val compilations = lib.compilations.size
        if (compilations > 0) item { HomeRow(Icons.Rounded.Groups, stringResource(R.string.music_compilations), compilations) { a.open(MusicPlace.Compilations) } }
        item { HomeRow(Icons.Rounded.MusicNote, stringResource(R.string.music_tracks), lib.tracks.size) { a.open(MusicPlace.Tracks) } }
        item { HomeRow(Icons.Rounded.Category, stringResource(R.string.music_genres), null) { a.open(MusicPlace.Genres) } }
        item { HomeRow(Icons.Rounded.CalendarMonth, stringResource(R.string.music_decades), null) { a.open(MusicPlace.Decades) } }
        item { HomeRow(Icons.Rounded.Update, stringResource(R.string.music_recent), null) { a.open(MusicPlace.Recent) } }
        item { SectionTitle(stringResource(R.string.music_by_sound)) }
        item { HomeRow(Icons.Rounded.Mood, stringResource(R.string.music_moods), null) { a.open(MusicPlace.Moods) } }
        item { HomeRow(Icons.Rounded.Waves, stringResource(R.string.music_families), null) { a.open(MusicPlace.Families) } }
        item { Hint(stringResource(R.string.music_home_hint)) }
    }
}

@Composable
private fun SearchResults(lib: MusicLibrary, query: String, a: MusicActions) {
    val state = rememberLazyListState()
    val artists = lib.artists.filter { matchesQuery(query, it.name) }.take(SEARCH_RESULTS)
    val albums = lib.albums.filter { matchesQuery(query, it.title) }.take(SEARCH_RESULTS)
    // Tracks by their title, artist or album, so "artist title" finds one.
    val tracks = lib.tracks.filter { matchesQuery(query, it.title, it.tags.artist, it.tags.album) }.take(SEARCH_RESULTS)
    if (artists.isEmpty() && albums.isEmpty() && tracks.isEmpty()) {
        Hint(stringResource(R.string.search_nothing))
        return
    }
    RowList(state) {
        if (artists.isNotEmpty()) item { SectionTitle(stringResource(R.string.music_artists)) }
        items(artists, key = { "artist:" + it.name }) { ArtistRow(it, a) }
        if (albums.isNotEmpty()) item { SectionTitle(stringResource(R.string.music_albums)) }
        items(albums, key = { "album:" + it.key }) { AlbumRow(it, a) }
        if (tracks.isNotEmpty()) item { SectionTitle(stringResource(R.string.music_tracks)) }
        items(tracks, key = { "track:" + it.path }) { t ->
            val album = lib.albumOf(t.path)
            MusicTrackRow(t, 0, a, numbered = false) {
                // A track found: its album, from it.
                if (album != null) a.play(album.title, album.tracks.map { it.path }, album.tracks.indexOf(t).coerceAtLeast(0))
                else a.play(t.title, listOf(t.path), 0)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeRow(icon: ImageVector, title: String, count: Int?, first: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().tipAnchor("music.row", enabled = first).combinedClickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        com.sadurni.attacca.ui.theme.ThemedIcon(icon, null, LocalAttaccaColors.current.textGrayed, modifier = Modifier.size(24.dp))
        Text(title, fontSize = AttaccaType.body, modifier = Modifier.weight(1f).padding(start = 16.dp))
        if (count != null) Text("%,d".format(count), fontSize = AttaccaType.secondary, color = LocalAttaccaColors.current.textGrayed)
    }
}

/** Counts as small icons and numbers: albums, tracks, total time. */
@Composable
private fun Counts(albums: Int?, tracks: Int?, durationMs: Long?, extra: String? = null) {
    val colors = LocalAttaccaColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        @Composable
        fun item(icon: ImageVector, text: String) {
            Icon(icon, null, tint = colors.textGrayed, modifier = Modifier.size(14.dp))
            Text(text, fontSize = AttaccaType.secondary, color = colors.textGrayed, modifier = Modifier.padding(end = 6.dp))
        }
        if (extra != null) Text(extra, fontSize = AttaccaType.secondary, color = colors.textGrayed, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 6.dp).weight(1f, fill = false))
        if (albums != null) item(Icons.Rounded.Album, "$albums")
        if (tracks != null) item(Icons.Rounded.MusicNote, "$tracks")
        if (durationMs != null && durationMs > 0) item(Icons.Rounded.Schedule, formatTotalTime(durationMs))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupRow(icon: ImageVector, title: String, albums: Int, tracks: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        com.sadurni.attacca.ui.theme.ThemedIcon(icon, null, LocalAttaccaColors.current.textGrayed, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(title, fontSize = AttaccaType.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Counts(albums, tracks, null)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArtistRow(artist: MusicArtist, a: MusicActions) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = { a.open(MusicPlace.Artist(artist.name)) }).padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        artist.albums.firstOrNull()?.tracks?.firstOrNull()?.let { TrackThumbnail(it.path) }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                com.sadurni.attacca.ui.common.GroupDamagedMark(remember(artist) { artist.tracks.map { it.path } })
                Text(artist.name, fontSize = AttaccaType.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Counts(artist.albums.size, artist.tracks.size, artist.durationMs)
        }
        Box {
            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.more_options)) }
            GroupMenu(menuOpen, { menuOpen = false }, artist.name, artist.tracks.map { it.path }, a, folder = null)
        }
    }
}

/** Play, play next, add to the current list or another, inclusion and the folder of a group of tracks. */
@Composable
private fun GroupMenu(expanded: Boolean, onDismiss: () -> Unit, name: String, paths: List<String>, a: MusicActions, folder: String?) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        var addToList by remember { mutableStateOf(false) }
        DropdownMenuItem(text = { Text(stringResource(R.string.play_selection)) }, onClick = {
            onDismiss()
            a.play(name, paths, 0)
        })
        DropdownMenuItem(text = { Text(stringResource(R.string.music_play_shuffled)) }, onClick = {
            onDismiss()
            a.shuffle(name, paths)
        })
        DropdownMenuItem(text = { Text(stringResource(R.string.queue_play_next)) }, onClick = {
            onDismiss()
            a.onAction(BrowserAction.PlayNext(paths.map(::File)))
        })
        DropdownMenuItem(text = { Text(stringResource(R.string.add_to_queue)) }, onClick = {
            onDismiss()
            a.onAction(BrowserAction.AddToQueue(paths.map(::File)))
        })
        DropdownMenuItem(text = { Text(stringResource(R.string.add_to_list)) }, onClick = { addToList = true })
        DropdownMenuItem(text = { Text(stringResource(R.string.seed_group_title)) }, onClick = {
            onDismiss()
            a.onAction(BrowserAction.SeedGroup(name, paths))
        })
        paths.firstOrNull()?.let { first ->
            DropdownMenuItem(text = { Text(inclusionMenuLabel()) }, onClick = {
                onDismiss()
                a.onAction(BrowserAction.Inclusion(File(first)))
            })
        }
        DropdownMenuItem(text = { Text(stringResource(R.string.damaged_repair_all)) }, onClick = {
            onDismiss()
            a.onAction(BrowserAction.Repair(paths))
        })
        if (folder != null) {
            DropdownMenuItem(text = { Text(stringResource(R.string.go_to_folder)) }, onClick = {
                onDismiss()
                paths.firstOrNull()?.let(a.onGoToFolder)
            })
        }
        if (addToList) {
            AddToListDialog(
                onPick = { list ->
                    a.onAction(BrowserAction.AddToList(paths.map(::File), list))
                    addToList = false
                    onDismiss()
                },
                onDismiss = { addToList = false },
            )
        }
    }
}

@Composable
private fun AlbumList(albums: List<MusicAlbum>, grid: Boolean, state: androidx.compose.foundation.lazy.LazyListState, a: MusicActions, playAs: String? = null) {
    if (albums.isEmpty()) {
        Hint(stringResource(R.string.search_nothing))
        return
    }
    if (playAs != null) {
        val paths = albums.flatMap { al -> al.tracks.map { it.path } }
        Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            PlayButtons(onPlay = { a.play(playAs, paths.take(MAX_PLAYED_LIST), 0) }, onShuffle = { a.shuffle(playAs, paths) })
        }
    }
    if (grid) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(albums, key = { it.key }) { AlbumCell(it, a) }
        }
    } else {
        RowList(state) { items(albums, key = { "album:" + it.key }) { AlbumRow(it, a) } }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumCell(album: MusicAlbum, a: MusicActions) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(Modifier.combinedClickable(onClick = { a.open(MusicPlace.Album(album.key)) }, onLongClick = { menuOpen = true })) {
        Box {
            Cover(album, Modifier.fillMaxWidth().aspectRatio(1f))
            GroupMenu(menuOpen, { menuOpen = false }, album.title, album.tracks.map { it.path }, a, folder = album.folder)
        }
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            com.sadurni.attacca.ui.common.GroupDamagedMark(remember(album) { album.tracks.map { it.path } })
            Text(album.title, fontSize = AttaccaType.secondary, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            listOfNotNull(albumArtistLabel(album), album.year.takeIf { it > 0 }?.toString()).joinToString(" · "),
            fontSize = AttaccaType.caption,
            color = LocalAttaccaColors.current.textGrayed,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The album's artist, "Various artists" for a compilation, "Unknown artist" without any. */
@Composable
private fun albumArtistLabel(album: MusicAlbum): String =
    album.artist ?: stringResource(if (album.compilation) R.string.music_various else R.string.music_unknown_artist)

/** An album's cover at any size (the folder picture or the first track's own). */
@Composable
private fun Cover(album: MusicAlbum, modifier: Modifier) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
        val first = album.tracks.firstOrNull()?.path ?: return@BoxWithConstraints
        TrackThumbnail(first, size = maxWidth)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumRow(album: MusicAlbum, a: MusicActions) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = { a.open(MusicPlace.Album(album.key)) }).padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        album.tracks.firstOrNull()?.let { TrackThumbnail(it.path, size = 52.dp) }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                com.sadurni.attacca.ui.common.GroupDamagedMark(remember(album) { album.tracks.map { it.path } })
                Text(album.title, fontSize = AttaccaType.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Counts(
                null,
                album.tracks.size,
                album.durationMs,
                extra = listOfNotNull(albumArtistLabel(album), album.year.takeIf { it > 0 }?.toString()).joinToString(" · "),
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.more_options)) }
            GroupMenu(menuOpen, { menuOpen = false }, album.title, album.tracks.map { it.path }, a, folder = album.folder)
        }
    }
}

@Composable
private fun ArtistPage(artist: MusicArtist, albums: List<MusicAlbum>, grid: Boolean, state: androidx.compose.foundation.lazy.LazyListState, a: MusicActions) {
    val paths = artist.tracks.map { it.path }
    val header: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Counts(artist.albums.size, artist.tracks.size, artist.durationMs)
            PlayButtons(onPlay = { a.play(artist.name, paths, 0) }, onShuffle = { a.shuffle(artist.name, paths) })
        }
    }
    if (grid) {
        Column(Modifier.fillMaxSize()) {
            header()
            AlbumList(albums, true, state, a)
        }
    } else {
        RowList(state) {
            item { header() }
            items(albums, key = { "album:" + it.key }) { AlbumRow(it, a) }
        }
    }
}

@Composable
private fun PlayButtons(onPlay: () -> Unit, onShuffle: () -> Unit) {
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onPlay) {
            Icon(Icons.Rounded.PlayArrow, null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.play_selection), modifier = Modifier.padding(start = 4.dp))
        }
        OutlinedButton(onClick = onShuffle) {
            Icon(Icons.Rounded.Shuffle, null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.music_shuffle), modifier = Modifier.padding(start = 4.dp))
        }
    }
}

@Composable
private fun AlbumPage(album: MusicAlbum, query: String, state: androidx.compose.foundation.lazy.LazyListState, a: MusicActions) {
    val context = LocalContext.current
    val stats = remember(album) { album.tracks.mapNotNull { context.app.listening[it.path] } }
    val paths = album.tracks.map { it.path }
    val shown = album.tracks.filter { matchesQuery(query, it.title, it.tags.artist) }
    val discs = album.tracks.map { it.tags.disc }.distinct().size > 1
    var menuOpen by remember { mutableStateOf(false) }
    // Opened for one of its tracks: that track a couple of rows below the top (after the place's own scroll to the top).
    LaunchedEffect(album.key, a.focus) {
        val focus = a.focus ?: return@LaunchedEffect
        var row = 1
        var disc = -1
        for (t in shown) {
            if (discs && t.tags.disc != disc) {
                disc = t.tags.disc
                row++
            }
            if (t.path == focus) {
                androidx.compose.runtime.withFrameNanos { }
                state.scrollToItem((row - 2).coerceAtLeast(0))
                return@LaunchedEffect
            }
            row++
        }
    }
    RowList(state) {
        item {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Top) {
                Cover(album, Modifier.size(120.dp))
                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                    Text(album.title, fontSize = AttaccaType.title, fontWeight = FontWeight.Medium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    val artist = album.artist
                    Text(
                        albumArtistLabel(album),
                        fontSize = AttaccaType.body,
                        color = LocalAccent.current,
                        maxLines = 2,
                        modifier = if (artist != null) Modifier.clickable { a.open(MusicPlace.Artist(artist)) } else Modifier,
                    )
                    val details = listOfNotNull(album.year.takeIf { it > 0 }?.toString(), album.genre.ifBlank { null }).joinToString(" · ")
                    if (details.isNotEmpty()) Text(details, fontSize = AttaccaType.secondary, color = LocalAttaccaColors.current.textGrayed)
                    Counts(null, album.tracks.size, album.durationMs)
                    val plays = stats.sumOf { it.plays }
                    if (plays > 0) Text("▶ $plays", fontSize = AttaccaType.secondary, color = LocalAttaccaColors.current.textGrayed)
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.more_options)) }
                    GroupMenu(menuOpen, { menuOpen = false }, album.title, paths, a, folder = album.folder)
                }
            }
            Box(Modifier.padding(horizontal = 16.dp)) {
                PlayButtons(onPlay = { a.play(album.title, paths, 0) }, onShuffle = { a.play(album.title, paths.shuffled(), 0) })
            }
        }
        var lastDisc = -1
        for (t in shown) {
            if (discs && t.tags.disc != lastDisc) {
                lastDisc = t.tags.disc
                val disc = lastDisc
                item(key = "disc:$disc") { SectionTitle(stringResource(R.string.music_disc, disc)) }
            }
            item(key = "track:" + t.path) {
                MusicTrackRow(t, t.tags.track.takeIf { it > 0 } ?: (album.tracks.indexOf(t) + 1), a, numbered = true, showAlbum = false) {
                    a.play(album.title, paths, album.tracks.indexOf(t))
                }
            }
        }
    }
}

@Composable
private fun TrackList(name: String, tracks: List<MusicTrack>, state: androidx.compose.foundation.lazy.LazyListState, a: MusicActions, lib: MusicLibrary?) {
    if (tracks.isEmpty()) {
        Hint(stringResource(R.string.search_nothing))
        return
    }
    val paths = tracks.map { it.path }
    RowList(state) {
        item {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                PlayButtons(onPlay = { a.play(name, paths.take(MAX_PLAYED_LIST), 0) }, onShuffle = { a.shuffle(name, paths) })
            }
        }
        items(tracks, key = { "track:" + it.path }) { t ->
            MusicTrackRow(t, 0, a, numbered = false) {
                val index = tracks.indexOf(t)
                val album = lib?.albumOf(t.path)
                if (tracks.size <= MAX_PLAYED_LIST || album == null) a.play(name, paths, index)
                else a.play(album.title, album.tracks.map { it.path }, album.tracks.indexOf(t).coerceAtLeast(0))
            }
        }
    }
}

@Composable
private fun MusicTrackRow(t: MusicTrack, number: Int, a: MusicActions, numbered: Boolean, showAlbum: Boolean = true, onPlay: () -> Unit) {
    val context = LocalContext.current
    val selection = a.selection
    val plays = remember(t.path) { context.app.listening[t.path]?.plays ?: 0 }
    val subtitle = listOfNotNull(
        t.tags.artist.ifBlank { null },
        t.tags.album.ifBlank { null }.takeIf { showAlbum },
        if (plays > 0) "▶ $plays" else null,
    ).joinToString(" · ")
    var menuOpen by remember { mutableStateOf(false) }
    com.sadurni.attacca.ui.common.SwipeRow(
        itemKey = t.path,
        queue = false,
        enabled = selection == null,
        onAction = { action ->
            when (action) {
                RowAction.PLAY_NEXT -> a.onAction(BrowserAction.PlayNext(listOf(File(t.path))))
                RowAction.ADD_TO_QUEUE -> a.onAction(BrowserAction.AddToQueue(listOf(File(t.path))))
                RowAction.ADD_TO_FAVORITES -> a.onAction(BrowserAction.AddToFavorites(listOf(File(t.path))))
                else -> Unit
            }
        },
    ) {
        TrackRow(
            number = if (numbered) number else 0,
            mediaId = t.path,
            title = t.title,
            subtitle = subtitle,
            highlighted = t.path == a.playingId || t.path == a.focus,
            selected = selection?.contains(t.path),
            onClick = { if (selection != null) a.toggle(t.path) else onPlay() },
            onLongClick = { a.toggle(t.path) },
            durationMs = t.tags.durationMs,
        ) {
            if (selection == null) {
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.more_options)) }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.queue_play_next)) }, onClick = {
                            menuOpen = false
                            a.onAction(BrowserAction.PlayNext(listOf(File(t.path))))
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.add_to_queue)) }, onClick = {
                            menuOpen = false
                            a.onAction(BrowserAction.AddToQueue(listOf(File(t.path))))
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.share)) }, onClick = {
                            menuOpen = false
                            com.sadurni.attacca.ui.common.ShareTracks.share(context, listOf(t.path))
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.seed_list_title)) }, onClick = {
                            menuOpen = false
                            a.onAction(BrowserAction.SeedList(File(t.path)))
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.quick_list)) }, onClick = {
                            menuOpen = false
                            a.onAction(BrowserAction.QuickList(File(t.path)))
                        })
                        DropdownMenuItem(text = { Text(inclusionMenuLabel()) }, onClick = {
                            menuOpen = false
                            a.onAction(BrowserAction.Inclusion(File(t.path)))
                        })
                        if (t.path.endsWith(".mp3", ignoreCase = true)) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.damaged_repair)) }, onClick = {
                                menuOpen = false
                                a.onAction(BrowserAction.Repair(listOf(t.path)))
                            })
                        }
                        DropdownMenuItem(text = { Text(stringResource(R.string.go_to_folder)) }, onClick = {
                            menuOpen = false
                            a.onGoToFolder(t.path)
                        })
                    }
                }
            }
        }
    }
}

/** Moods and sound families come from the analysis for chaining; only analysed tracks are in them. */
@Composable
private fun ClassifiedContent(place: MusicPlace, lib: MusicLibrary, query: String, state: androidx.compose.foundation.lazy.LazyListState, a: MusicActions) {
    val context = LocalContext.current
    val collection by produceState<com.sadurni.attacca.chain.ChainLists.Collection?>(null) {
        value = runCatching { context.app.chainLists.collection() }.getOrNull()
    }
    val c = collection
    if (c == null) {
        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        Hint(stringResource(R.string.chain_preparing))
        return
    }
    val g = c.generator
    val byPath = remember(lib) { lib.tracks.associateBy { it.path } }
    fun tracksWhere(test: (Int) -> Boolean): List<MusicTrack> =
        g.candidates.indices.filter(test).mapNotNull { byPath[g.candidates[it].id] }
    when (place) {
        MusicPlace.Moods -> RowList(state) {
            if (c.analysed < c.total) item { Hint(stringResource(R.string.chain_coverage, c.analysed, c.total)) }
            items(Mood.entries.toList(), key = { "mood:" + it.name }) { mood ->
                val count = g.candidates.indices.count { g.classification.hasMood(it, setOf(mood)) }
                GroupRow(Icons.Rounded.Mood, context.getString(com.sadurni.attacca.ui.chain.moodRes(mood)), 0, count) { a.open(MusicPlace.OfMood(mood)) }
            }
        }
        MusicPlace.Families -> RowList(state) {
            if (c.analysed < c.total) item { Hint(stringResource(R.string.chain_coverage, c.analysed, c.total)) }
            items(g.classification.familyNames.indices.toList(), key = { "family:$it" }) { f ->
                val count = g.candidates.indices.count { g.familyOf(it) == f }
                GroupRow(Icons.Rounded.Waves, com.sadurni.attacca.ui.chain.familyLabel(context, g.classification.familyNames[f]), 0, count) { a.open(MusicPlace.OfFamily(f)) }
            }
        }
        is MusicPlace.OfMood -> TrackList(
            context.getString(com.sadurni.attacca.ui.chain.moodRes(place.mood)),
            tracksWhere { g.classification.hasMood(it, setOf(place.mood)) }.filter { matchesQuery(query, it.title, it.tags.artist) },
            state, a, lib,
        )
        is MusicPlace.OfFamily -> TrackList(
            com.sadurni.attacca.ui.chain.familyLabel(context, g.classification.familyNames[place.family]),
            tracksWhere { g.familyOf(it) == place.family }.filter { matchesQuery(query, it.title, it.tags.artist) },
            state, a, lib,
        )
        else -> Unit
    }
}
