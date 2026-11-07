package com.sadurni.attacca.ui

import com.sadurni.attacca.ui.theme.AttaccaType
import com.sadurni.attacca.ui.theme.AttaccaShapes
import com.sadurni.attacca.ui.common.PickerMemory
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.navigationBars
import com.sadurni.attacca.ui.common.MiniPlayer
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import com.sadurni.attacca.library.UserLists
import com.sadurni.attacca.ui.common.LocalListExporter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import com.sadurni.attacca.ui.common.Icon
import com.sadurni.attacca.ui.common.IconButton
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import com.sadurni.attacca.BuildConfig
import com.sadurni.attacca.ui.settings.AboutScreen
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.lifecycleScope
import com.sadurni.attacca.R
import com.sadurni.attacca.playback.setResumptionEnabled
import com.sadurni.attacca.app
import com.sadurni.attacca.library.AudioFiles
import com.sadurni.attacca.library.ListLoader
import com.sadurni.attacca.library.ListSource
import com.sadurni.attacca.library.MediaItems
import com.sadurni.attacca.library.UriPaths
import com.sadurni.attacca.ui.browser.BrowserAction
import com.sadurni.attacca.ui.browser.BrowserScreen
import com.sadurni.attacca.ui.lists.BookmarksScreen
import com.sadurni.attacca.ui.lists.ListViewScreen
import com.sadurni.attacca.ui.lists.ListsScreen
import com.sadurni.attacca.ui.lists.TagEditorScreen
import com.sadurni.attacca.ui.player.TrackInfoScreen
import com.sadurni.attacca.ui.player.PlayerScreen
import com.sadurni.attacca.ui.queue.QueueScreen
import com.sadurni.attacca.ui.settings.DevicesScreen
import com.sadurni.attacca.ui.settings.SettingsScreen
import com.sadurni.attacca.ui.settings.SoundScreen
import com.sadurni.attacca.ui.theme.LocalAccent
import com.sadurni.attacca.ui.theme.LocalAttaccaColors
import com.sadurni.attacca.ui.theme.AttaccaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Screens, with their depth: going deeper slides in from the right, going back from the left. */
/** Lists played last, shown at the bottom of the side menu. */
private const val RECENT_IN_MENU = 5

private enum class NavDirection { FORWARD, BACK, SWITCH }

private val SECTIONS = setOf(Screen.BROWSER, Screen.MUSIC, Screen.LISTS, Screen.QUEUE, Screen.BOOKMARKS, Screen.SOUND, Screen.DEVICES, Screen.SETTINGS)

private val HISTORY_SAVER = androidx.compose.runtime.saveable.listSaver<List<Screen>, String>(
    save = { list -> list.map { it.name } },
    restore = { names -> names.map(Screen::valueOf) },
)

private enum class Screen(val depth: Int) {
    PLAYER(0), BROWSER(1), MUSIC(1), LISTS(1), BOOKMARKS(1), QUEUE(1), SOUND(1), DEVICES(1), SETTINGS(1), ABOUT(1), HELP(1), LIST_VIEW(2), CHANGELOG(2), SMART_EDITOR(2), INFO(2), THEME_EDITOR(2), THEME_GALLERY(2), TAGS(3),
}

/** Which background image of the theme a screen shows; none for Now playing (it has its own) and the theme editor. */
private val Screen.section: com.sadurni.attacca.settings.ThemeSection?
    get() = when (this) {
        Screen.PLAYER, Screen.THEME_EDITOR -> null
        Screen.BROWSER, Screen.MUSIC -> com.sadurni.attacca.settings.ThemeSection.FOLDERS
        Screen.LISTS, Screen.LIST_VIEW, Screen.SMART_EDITOR -> com.sadurni.attacca.settings.ThemeSection.LISTS
        Screen.QUEUE -> com.sadurni.attacca.settings.ThemeSection.QUEUE
        Screen.BOOKMARKS -> com.sadurni.attacca.settings.ThemeSection.BOOKMARKS
        Screen.SOUND -> com.sadurni.attacca.settings.ThemeSection.SOUND
        Screen.DEVICES -> com.sadurni.attacca.settings.ThemeSection.DEVICES
        Screen.SETTINGS, Screen.ABOUT, Screen.CHANGELOG, Screen.HELP, Screen.THEME_GALLERY -> com.sadurni.attacca.settings.ThemeSection.SETTINGS
        Screen.INFO, Screen.TAGS -> com.sadurni.attacca.settings.ThemeSection.OTHERS
    }

/** The tips shown over each screen (see [com.sadurni.attacca.ui.common.TipsOverlay]). */
private val Screen.tips: com.sadurni.attacca.ui.common.TipsScreen?
    get() = when (this) {
        Screen.PLAYER -> com.sadurni.attacca.ui.common.TipsScreen.PLAYER
        Screen.LISTS -> com.sadurni.attacca.ui.common.TipsScreen.LISTS
        Screen.QUEUE -> com.sadurni.attacca.ui.common.TipsScreen.QUEUE
        Screen.BROWSER -> com.sadurni.attacca.ui.common.TipsScreen.BROWSER
        Screen.MUSIC -> com.sadurni.attacca.ui.common.TipsScreen.MUSIC
        Screen.SOUND -> com.sadurni.attacca.ui.common.TipsScreen.SOUND
        Screen.SETTINGS -> com.sadurni.attacca.ui.common.TipsScreen.SETTINGS
        Screen.DEVICES -> com.sadurni.attacca.ui.common.TipsScreen.DEVICES
        Screen.THEME_EDITOR -> com.sadurni.attacca.ui.common.TipsScreen.THEME_EDITOR
        else -> null
    }

class MainActivity : AppCompatActivity() {

    private val viewModel: PlayerViewModel by viewModels()
    private var hasStorageAccess by mutableStateOf(false)

    /** Screen to show next, requested from outside compose (e.g. a list opened with the app). */
    private var requestedScreen by mutableStateOf<Screen?>(null)

    private val requestStorage =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            refreshStorageAccess()
            // Android no longer shows the dialog once refused: the app's settings instead.
            if (!granted && !shouldShowRequestPermissionRationale(Manifest.permission.READ_EXTERNAL_STORAGE)) {
                com.sadurni.attacca.ui.common.AppPermissions.openAppSettings(this)
            }
        }
    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Tracks waiting for the user to pick where to export them, and in which format. */
    private var pendingExport: List<androidx.media3.common.MediaItem>? = null
    private var pendingFormat = com.sadurni.attacca.library.PlaylistFormat.M3U8

    /** A list waiting for the user to choose its format (shown as a dialog). */
    private val exportAsk = androidx.compose.runtime.mutableStateOf<Pair<String, List<androidx.media3.common.MediaItem>>?>(null)

    private val exportLaunchers = com.sadurni.attacca.library.PlaylistFormat.entries.associateWith { format ->
        registerForActivityResult(com.sadurni.attacca.ui.common.CreateDocumentAt(format.mimeType)) { uri ->
            uri?.let { PickerMemory.put(this, PickerMemory.EXPORT_LIST, it) }
            val items = pendingExport
            pendingExport = null
            if (uri != null && items != null) writeExport(uri, items, format)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        refreshStorageAccess()
        app.scheduleStartupScan()
        setResumptionEnabled(this, app.settings.playback.value.resumeWhenClosed)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (savedInstanceState == null) handleViewIntent(intent)

        setContent {
            val settings = app.settings
            val ui by settings.ui.collectAsStateWithLifecycle()
            val accentDark by viewModel.artworkAccentDark.collectAsStateWithLifecycle()
            val accentLight by viewModel.artworkAccentLight.collectAsStateWithLifecycle()
            // The user's theme when one is chosen, else White / Dark / Black (or the phone's choice).
            val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
            val themeVersion = com.sadurni.attacca.ui.theme.ThemeRuntime.version
            val chosen = androidx.compose.runtime.remember(ui.themeId, ui.themeMode, systemDark, themeVersion) {
                com.sadurni.attacca.settings.ThemeStore.find(ui.themeId) ?: when (ui.themeMode) {
                    com.sadurni.attacca.settings.ThemeMode.WHITE -> com.sadurni.attacca.settings.ThemeSpec.WHITE
                    com.sadurni.attacca.settings.ThemeMode.DARK -> com.sadurni.attacca.settings.ThemeSpec.DARK
                    com.sadurni.attacca.settings.ThemeMode.BLACK -> com.sadurni.attacca.settings.ThemeSpec.BLACK
                    com.sadurni.attacca.settings.ThemeMode.SYSTEM -> com.sadurni.attacca.settings.ThemeSpec.SYSTEM
                    com.sadurni.attacca.settings.ThemeMode.SYSTEM_DARK -> com.sadurni.attacca.settings.ThemeSpec.SYSTEM_DARK
                }
            }
            // Built-in themes follow the "accent from the cover" switch; the user's themes say it themselves
            // (and, with both versions, take the light or dark one as the phone).
            val spec = (if (chosen.builtIn) chosen.copy(coverAccent = ui.dynamicAccent) else chosen).forSystem(systemDark)
            val darkTheme = spec.dark
            val artworkAccent = if (darkTheme) accentDark else accentLight
            // System bar icons follow the app's theme, not the phone's (dark theme on a light phone).
            androidx.compose.runtime.LaunchedEffect(darkTheme) {
                val transparent = android.graphics.Color.TRANSPARENT
                val style = if (darkTheme) {
                    androidx.activity.SystemBarStyle.dark(transparent)
                } else {
                    androidx.activity.SystemBarStyle.light(transparent, transparent)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }

            // Copies of the themes that come with the app, installed by earlier versions and never
            // edited, give way to the gallery (once the music folder can be written).
            androidx.compose.runtime.LaunchedEffect(hasStorageAccess) {
                if (hasStorageAccess) {
                    val changed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.sadurni.attacca.settings.ThemeStore.tidySamples(this@MainActivity, this@MainActivity.app.settings.ui.value.themeId)
                    }
                    if (changed) com.sadurni.attacca.ui.theme.ThemeRuntime.version++
                }
            }
            AttaccaTheme(spec = spec, artworkAccent = artworkAccent) {
                Box(Modifier.fillMaxSize().background(LocalAttaccaColors.current.background)) {
                    // First start: the music folder or a backup, before anything else.
                    var welcome by remember { mutableStateOf(com.sadurni.attacca.ui.settings.needsWelcome(this@MainActivity)) }
                    if (hasStorageAccess && welcome) {
                        com.sadurni.attacca.ui.settings.WelcomeScreen(onDone = { welcome = false })
                    } else if (hasStorageAccess) {
                        AppContent(ui)
                    } else {
                        PermissionScreen(onGrant = ::requestStorageAccess)
                    }
                }
            }
        }

        // Closed from the notification: leave no screen behind.
        lifecycleScope.launch { app.closeRequests.collect { finishAndRemoveTask() } }

        // Returning from the "All files access" system screen.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) { refreshStorageAccess() }
        }
    }

    override fun onStart() {
        super.onStart()
        app.uiVisible = true
    }

    override fun onStop() {
        app.uiVisible = false
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleViewIntent(intent)
    }

    @Composable
    private fun AppContent(ui: com.sadurni.attacca.settings.UiSettings) {
        // Where the user has been: Back goes to the previous screen; Now playing is home.
        var history by rememberSaveable(stateSaver = HISTORY_SAVER) { mutableStateOf(listOf(Screen.PLAYER)) }
        var direction by remember { mutableStateOf(NavDirection.FORWARD) }
        val screen = history.last()
        // Going to a screen already in the history goes back to it (no loops); home clears it.
        fun navigate(target: Screen, fromMenu: Boolean = false) {
            if (target == screen) return
            val at = history.indexOf(target)
            direction = when {
                fromMenu -> NavDirection.SWITCH
                at >= 0 -> NavDirection.BACK
                else -> NavDirection.FORWARD
            }
            history = when {
                target == Screen.PLAYER -> listOf(Screen.PLAYER)
                at >= 0 -> history.take(at + 1)
                else -> history + target
            }
        }
        fun goBack() {
            if (history.size < 2) return
            direction = NavDirection.BACK
            history = history.dropLast(1)
        }
        // This screen gives way to another at the same place in the history.
        fun replaceWith(target: Screen) {
            direction = NavDirection.SWITCH
            history = history.dropLast(1).filter { it != target } + target
        }
        // A device used for the first time: a sound template for it.
        com.sadurni.attacca.ui.settings.NewDeviceTemplatePrompt()
        var tagTarget by rememberSaveable { mutableStateOf<String?>(null) }
        var infoTarget by rememberSaveable { mutableStateOf<String?>(null) }
        // Settings opened on the page of the cover gestures (long press on the cover).
        var settingsOnGestures by rememberSaveable { mutableStateOf(false) }
        // Back from the theme editor to the Appearance page; the theme being edited.
        var settingsOnAppearance by rememberSaveable { mutableStateOf(false) }
        var editedTheme by rememberSaveable { mutableStateOf<String?>(null) }
        // The folder browser opened from Lists keeps its own last folder; a track to show in its folder.
        var browserForLists by rememberSaveable { mutableStateOf(false) }
        var browserReveal by rememberSaveable { mutableStateOf<String?>(null) }
        // The smart list being edited ("" for a new one).
        var smartTarget by rememberSaveable { mutableStateOf<String?>(null) }
        // Where Music was left (its own stack of places), and a "Go to album / artist" to show.
        val musicPlaces = remember { androidx.compose.runtime.mutableStateListOf<com.sadurni.attacca.ui.music.MusicPlace>() }
        var musicTarget by remember { mutableStateOf<com.sadurni.attacca.ui.music.MusicTarget?>(null) }
        val goToMusic: (String, Boolean) -> Unit = { path, artist ->
            musicTarget = com.sadurni.attacca.ui.music.MusicTarget(path, artist)
            navigate(Screen.MUSIC)
        }
        val drawer = rememberDrawerState(DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        val editTags: (String) -> Unit = { mediaId ->
            tagTarget = mediaId
            navigate(Screen.TAGS)
        }
        val viewedList by viewModel.viewedList.collectAsStateWithLifecycle()
        val nowPlaying by viewModel.nowPlaying.collectAsStateWithLifecycle()
        requestedScreen?.let {
            navigate(it)
            requestedScreen = null
        }
        val chain by app.settings.chain.collectAsStateWithLifecycle()
        val scanning by app.libraryScanner.progress.collectAsStateWithLifecycle()
        val analysing by app.chainAnalyzer.libraryProgress.collectAsStateWithLifecycle()
        val keepScreenOn = nowPlaying.isPlaying && when (ui.keepScreenOn) {
            com.sadurni.attacca.settings.KeepScreenOn.OFF -> false
            com.sadurni.attacca.settings.KeepScreenOn.PLAYER -> screen == Screen.PLAYER
            com.sadurni.attacca.settings.KeepScreenOn.ALWAYS -> true
        } ||
            // With the screen off the phone slows down and the folder's analysis takes far longer.
            (chain.keepScreenOn && (scanning != null || analysing != null))
        val view = androidx.compose.ui.platform.LocalView.current
        androidx.compose.runtime.DisposableEffect(keepScreenOn) {
            view.keepScreenOn = keepScreenOn
            onDispose { view.keepScreenOn = false }
        }
        BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
        BackHandler(enabled = history.size > 1 && !drawer.isOpen) { goBack() }
        // On the first screen back sends the app away like on the others (no system preview of
        // going home, which pulls the screen aside under the finger), and the player stays open.
        val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
        BackHandler(enabled = history.size <= 1 && !drawer.isOpen) { activity?.moveTaskToBack(true) }
        val openMenu: () -> Unit = { scope.launch { drawer.open() } }
        val toPlayer = { navigate(Screen.PLAYER) }
        val back = { goBack() }
        // The list last looked at: another list, or the playing one.
        val toLastList = { navigate(if (viewedList != null) Screen.LIST_VIEW else Screen.QUEUE) }
        val colors = LocalAttaccaColors.current

        com.sadurni.attacca.ui.common.ProvideTipAnchors {
        CompositionLocalProvider(LocalListExporter provides ::exportItems) {
                ExportFormatDialog()
            ModalNavigationDrawer(
                drawerState = drawer,
                // Opened by buttons and swipes of the screens themselves; dragged closed.
                gesturesEnabled = drawer.isOpen,
                scrimColor = Color.Black.copy(alpha = 0.45f),
                drawerContent = {
                    ModalDrawerSheet(
                        drawerContainerColor = colors.surface.copy(alpha = 0.97f),
                        drawerContentColor = LocalContentColor.current,
                        modifier = Modifier.widthIn(max = 300.dp),
                    ) {
                        // Read again each time the menu opens.
                        val recent = remember(drawer.targetValue) { app.listHistory.all().take(RECENT_IN_MENU) }
                        NavigatorMenu(
                            current = screen,
                            recent = recent,
                            onOpenList = { source ->
                                // As from Lists: shown with the track where it was left.
                                viewModel.viewList(source)
                                navigate(if (viewModel.viewedList.value != null) Screen.LIST_VIEW else Screen.QUEUE, fromMenu = true)
                                scope.launch { drawer.close() }
                            },
                        ) { target ->
                            if (target == Screen.BROWSER) {
                                browserForLists = false
                                browserReveal = null
                            }
                            if (target == Screen.SETTINGS) {
                                settingsOnGestures = false
                                settingsOnAppearance = false
                            }
                            navigate(target, fromMenu = true)
                            scope.launch { drawer.close() }
                        }
                    }
                },
            ) {
              Column(Modifier.fillMaxSize().background(colors.background)) {
                val showMini = screen != Screen.PLAYER && nowPlaying.mediaId != null
                AnimatedContent(
                    targetState = screen,
                    // Above the mini player the screens need no room for the navigation bar.
                    modifier = Modifier
                        .weight(1f)
                        .then(if (showMini) Modifier.consumeWindowInsets(WindowInsets.navigationBars) else Modifier),
                    // Forward comes in from the right, back leaves to the right, the side menu fades.
                    transitionSpec = {
                        when (direction) {
                            NavDirection.FORWARD -> (slideInHorizontally { it } + fadeIn()) togetherWith
                                (slideOutHorizontally { -it / 4 } + fadeOut())
                            NavDirection.BACK -> (slideInHorizontally { -it / 4 } + fadeIn()) togetherWith
                                (slideOutHorizontally { it } + fadeOut())
                            NavDirection.SWITCH -> fadeIn() togetherWith fadeOut()
                        }
                    },
                    label = "screen",
                ) { target ->
                    // The main sections (those of the side menu) open it from their header.
                    val section = target in SECTIONS && !(target == Screen.BROWSER && browserForLists)
                    Box(Modifier.fillMaxSize().background(colors.background)) {
                      // The theme's picture for this screen (Now playing draws its own).
                      target.section?.let { com.sadurni.attacca.ui.theme.ThemeBackground(com.sadurni.attacca.ui.theme.LocalThemeSpec.current.backgroundOf(it)) }
                      // Or the playing track's cover, blurred, when asked for every screen.
                      if (ui.coverBehindScreens && target.section != null) {
                          val cover by viewModel.artwork.collectAsStateWithLifecycle()
                          com.sadurni.attacca.ui.theme.CoverBackdrop(cover, com.sadurni.attacca.ui.theme.SCREEN_COVER)
                      }
                      CompositionLocalProvider(com.sadurni.attacca.ui.common.LocalSectionMenu provides if (section) openMenu else null) {
                        when (target) {
                            Screen.PLAYER -> PlayerScreen(
                                viewModel = viewModel,
                                uiSettings = ui,
                                onToggleRemainingTime = { app.settings.updateUi { it.copy(showRemainingTime = !it.showRemainingTime) } },
                                onOpenNavigator = openMenu,
                                onOpenSound = { navigate(Screen.SOUND) },
                                onOpenQueue = {
                                    viewModel.viewList(null)
                                    navigate(Screen.QUEUE)
                                },
                                onSwipeLeft = toLastList,
                                onSwipeRight = openMenu,
                                onOpenGestures = {
                                    settingsOnGestures = true
                                    navigate(Screen.SETTINGS)
                                },
                                onOpenBookmarks = { navigate(Screen.BOOKMARKS) },
                                onOpenLists = { navigate(Screen.LISTS) },
                                onEditTags = editTags,
                                onShowInfo = { mediaId ->
                                    infoTarget = mediaId
                                    navigate(Screen.INFO)
                                },
                                onGoToFolder = { mediaId ->
                                    browserForLists = false
                                    browserReveal = mediaId
                                    navigate(Screen.BROWSER)
                                },
                                onGoToMusic = goToMusic,
                                onOpenList = { source, track ->
                                    viewModel.revealInList = track
                                    viewModel.viewList(source)
                                    navigate(if (viewModel.viewedList.value != null) Screen.LIST_VIEW else Screen.QUEUE)
                                },
                                recentLists = { app.listHistory.all().take(RECENT_IN_MENU) },
                            )
                            Screen.MUSIC -> com.sadurni.attacca.ui.music.MusicScreen(
                                playingId = viewModel.nowPlaying.collectAsStateWithLifecycle().value.mediaId,
                                places = musicPlaces,
                                target = musicTarget,
                                onTargetShown = { musicTarget = null },
                                onBack = back,
                                onAction = { action ->
                                    when (action) {
                                        is BrowserAction.PlayTracks, is BrowserAction.PlayFiles, is BrowserAction.PlayFolder -> {
                                            play(action)
                                            navigate(Screen.PLAYER)
                                        }
                                        is BrowserAction.QuickList -> {
                                            viewModel.quickSeedList(action.file.absolutePath)
                                            navigate(Screen.PLAYER)
                                        }
                                        else -> play(action)
                                    }
                                },
                                onGoToFolder = { mediaId ->
                                    browserForLists = false
                                    browserReveal = mediaId
                                    navigate(Screen.BROWSER)
                                },
                            )
                            Screen.BROWSER -> androidx.compose.runtime.key(browserForLists, browserReveal) { BrowserScreen(
                                forLists = browserForLists,
                                reveal = browserReveal?.let(::File),
                                onBack = back,
                                onAction = { action ->
                                    when (action) {
                                        is BrowserAction.OpenPlaylist -> {
                                            viewModel.viewList(ListSource.Playlist(action.file.absolutePath))
                                            navigate(if (viewModel.viewedList.value != null) Screen.LIST_VIEW else Screen.QUEUE)
                                        }
                                        is BrowserAction.PlayFolder, is BrowserAction.PlayFiles, is BrowserAction.PlayTracks -> {
                                            play(action)
                                            navigate(Screen.PLAYER)
                                        }
                                        is BrowserAction.AddToQueue, is BrowserAction.AddToList,
                                        is BrowserAction.PlayNext, is BrowserAction.AddToFavorites -> play(action)
                                        is BrowserAction.SeedList -> viewModel.requestSeedList(action.file.absolutePath)
                                        is BrowserAction.SeedGroup -> viewModel.requestGroupSeedList(action.name, action.paths)
                                        is BrowserAction.QuickList -> {
                                            viewModel.quickSeedList(action.file.absolutePath)
                                            navigate(Screen.PLAYER)
                                        }
                                        is BrowserAction.Bridge -> viewModel.requestBridge(action.from, action.to)
                                        is BrowserAction.Inclusion -> viewModel.requestInclusion(action.file.absolutePath)
                                    }
                                },
                            ) }
                            Screen.LISTS -> ListsScreen(viewModel, onBack = back, onBrowseFolder = {
                                browserForLists = true
                                browserReveal = null
                                navigate(Screen.BROWSER)
                            }, onView = { source ->
                                viewModel.viewList(source)
                                navigate(if (viewModel.viewedList.value != null) Screen.LIST_VIEW else Screen.QUEUE)
                            }, onPlayed = toPlayer, onEditSmart = { file ->
                                smartTarget = file?.absolutePath.orEmpty()
                                navigate(Screen.SMART_EDITOR)
                            })
                            Screen.SMART_EDITOR -> smartTarget?.let { target ->
                                com.sadurni.attacca.ui.lists.SmartListEditor(
                                    list = target.takeIf { it.isNotEmpty() }?.let(::File),
                                    onBack = back,
                                    onSaved = { file ->
                                        viewModel.viewList(ListSource.Playlist(file.absolutePath))
                                        replaceWith(Screen.LIST_VIEW)
                                    },
                                )
                            }
                            Screen.LIST_VIEW -> {
                                val source = viewedList
                                if (source != null) {
                                    ListViewScreen(viewModel, source, onBack = back, onToPlayer = toPlayer, onEditSmart = { file ->
                                        smartTarget = file.absolutePath
                                        navigate(Screen.SMART_EDITOR)
                                    })
                                } else if (screen == Screen.LIST_VIEW) {
                                    // The viewed list is the playing one now. Only when this is the
                                    // screen being shown, not one animating away.
                                    LaunchedEffect(Unit) { replaceWith(Screen.QUEUE) }
                                }
                            }
                            Screen.BOOKMARKS -> BookmarksScreen(viewModel, onBack = back, onOpened = toPlayer)
                            Screen.QUEUE -> QueueScreen(viewModel, onBack = back, onEditTags = editTags)
                            Screen.INFO -> infoTarget?.let {
                                TrackInfoScreen(viewModel, it, onBack = back, onEditTags = editTags)
                            }
                            Screen.TAGS -> tagTarget?.let { TagEditorScreen(it, onBack = back) }
                            Screen.SOUND -> SoundScreen(onBack = back)
                            Screen.DEVICES -> DevicesScreen(onBack = back)
                            Screen.SETTINGS -> androidx.compose.runtime.key(settingsOnGestures, settingsOnAppearance) {
                                SettingsScreen(
                                    onBack = back,
                                    openGestures = settingsOnGestures,
                                    openAppearance = settingsOnAppearance,
                                    onEditTheme = { id ->
                                        editedTheme = id
                                        settingsOnAppearance = true
                                        navigate(Screen.THEME_EDITOR)
                                    },
                                    onThemeGallery = {
                                        settingsOnAppearance = true
                                        navigate(Screen.THEME_GALLERY)
                                    },
                                )
                            }
                            Screen.THEME_EDITOR -> editedTheme?.let { id ->
                                com.sadurni.attacca.ui.settings.ThemeEditorScreen(themeId = id, viewModel = viewModel, onBack = back, onOpen = { editedTheme = it })
                            }
                            Screen.ABOUT -> AboutScreen(onBack = back, onOpenChangelog = { navigate(Screen.CHANGELOG) })
                            Screen.CHANGELOG -> com.sadurni.attacca.ui.settings.ChangelogScreen(onBack = back)
                            Screen.HELP -> com.sadurni.attacca.ui.settings.HelpScreen(onBack = back, onThemeGallery = { navigate(Screen.THEME_GALLERY) })
                            Screen.THEME_GALLERY -> com.sadurni.attacca.ui.settings.ThemeGalleryScreen(viewModel, onBack = back, onEdit = { id ->
                                editedTheme = id
                                navigate(Screen.THEME_EDITOR)
                            })
                        }
                      }
                        // Only over the screen being shown, not one animating away.
                        if (target == screen) target.tips?.let { com.sadurni.attacca.ui.common.TipsOverlay(it) }
                    }
                }
                AnimatedVisibility(showMini, enter = expandVertically(), exit = shrinkVertically()) {
                    MiniPlayer(viewModel, onOpen = toPlayer)
                }
                val inclusion by viewModel.inclusionRequest.collectAsStateWithLifecycle()
                inclusion?.let { path ->
                    com.sadurni.attacca.ui.chain.InclusionDialog(path, onDismiss = { viewModel.inclusionRequest.value = null })
                }
                com.sadurni.attacca.ui.settings.EffectAppsWarning()
                val seed by viewModel.seedRequest.collectAsStateWithLifecycle()
                seed?.let { id ->
                    com.sadurni.attacca.ui.chain.SeedListDialog(viewModel, listOf(id), null, onDismiss = { viewModel.seedRequest.value = null }, onPlayed = toPlayer)
                }
                val randomMood by app.randomMoodRequest.collectAsStateWithLifecycle()
                if (randomMood) {
                    com.sadurni.attacca.ui.chain.RandomListDialog(
                        onStart = { mood, onlyIncluded, count -> viewModel.randomList(mood, onlyIncluded, count) },
                        onDismiss = { app.randomMoodRequest.value = false },
                    )
                }
                com.sadurni.attacca.ui.chain.ListMakingBadge(viewModel)
                val moreLists by viewModel.moreListsOpen.collectAsStateWithLifecycle()
                if (moreLists) com.sadurni.attacca.ui.chain.MoreListsDialog(viewModel) { viewModel.moreListsOpen.value = false }
                val options by viewModel.optionsRequest.collectAsStateWithLifecycle()
                when (val request = options) {
                    is PlayerViewModel.OptionsRequest.Quick -> com.sadurni.attacca.ui.chain.ListOptionsDialog(
                        title = stringResource(R.string.quick_list),
                        onDismiss = { viewModel.optionsRequest.value = null },
                        onMake = { count, onlyIncluded ->
                            viewModel.quickSeedList(request.mediaId, count, onlyIncluded, withOptions = true)
                            toPlayer()
                        },
                    )
                    is PlayerViewModel.OptionsRequest.Bridge -> com.sadurni.attacca.ui.chain.BridgeDialog(
                        request.from, request.to,
                        onDismiss = { viewModel.optionsRequest.value = null },
                        onMake = { from, to, count, onlyIncluded ->
                            viewModel.makeList(com.sadurni.attacca.chain.ChainLists.Kind.BRIDGE, seeds = listOf(from, to), onlyIncluded = onlyIncluded, count = count)
                            toPlayer()
                        },
                    )
                    null -> Unit
                }
                val groupSeed by viewModel.groupSeedRequest.collectAsStateWithLifecycle()
                groupSeed?.let { (name, ids) ->
                    com.sadurni.attacca.ui.chain.SeedListDialog(viewModel, ids, name, onDismiss = { viewModel.groupSeedRequest.value = null }, onPlayed = toPlayer)
                }
              }
            }
        }
        }
    }

    /** The side menu: every section of the app, then the lists played last. */
    @Composable
    private fun NavigatorMenu(
        current: Screen,
        recent: List<ListSource>,
        onOpenList: (ListSource) -> Unit,
        onSelect: (Screen) -> Unit,
    ) {
        Column(
            Modifier
                .fillMaxHeight()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.app_name),
                    fontSize = AttaccaType.title,
                    fontFamily = com.sadurni.attacca.ui.theme.LocalTitleFamily.current,
                    fontWeight = FontWeight.Bold,
                    color = LocalAccent.current,
                )
                Text(
                    BuildConfig.VERSION_NAME,
                    fontSize = AttaccaType.secondary,
                    color = LocalContentColor.current.copy(alpha = 0.6f),
                    modifier = Modifier.padding(start = 8.dp).weight(1f),
                )
                com.sadurni.attacca.ui.settings.LanguageButton()
                IconButton(onClick = { onSelect(Screen.HELP) }) {
                    Icon(
                        Icons.AutoMirrored.Outlined.HelpOutline,
                        contentDescription = stringResource(R.string.help_title),
                        tint = if (current == Screen.HELP) LocalAccent.current else LocalContentColor.current.copy(alpha = 0.7f),
                    )
                }
                IconButton(onClick = { onSelect(Screen.ABOUT) }) {
                    Icon(
                        Icons.Outlined.Info,
                        contentDescription = stringResource(R.string.about_open),
                        tint = if (current == Screen.ABOUT) LocalAccent.current else LocalContentColor.current.copy(alpha = 0.7f),
                    )
                }
            }
            NavigatorItem(Icons.Rounded.PlayCircle, stringResource(R.string.nav_player), current == Screen.PLAYER) { onSelect(Screen.PLAYER) }
            NavigatorItem(Icons.Rounded.Folder, stringResource(R.string.nav_folders), current == Screen.BROWSER) { onSelect(Screen.BROWSER) }
            NavigatorItem(Icons.Rounded.Album, stringResource(R.string.nav_music), current == Screen.MUSIC) { onSelect(Screen.MUSIC) }
            NavigatorItem(Icons.Rounded.LibraryMusic, stringResource(R.string.nav_lists), current == Screen.LISTS || current == Screen.LIST_VIEW) { onSelect(Screen.LISTS) }
            NavigatorItem(Icons.AutoMirrored.Rounded.QueueMusic, stringResource(R.string.nav_queue), current == Screen.QUEUE) {
                viewModel.viewList(null)
                onSelect(Screen.QUEUE)
            }
            NavigatorItem(Icons.Rounded.Bookmarks, stringResource(R.string.action_bookmarks), current == Screen.BOOKMARKS) { onSelect(Screen.BOOKMARKS) }
            NavigatorItem(Icons.Rounded.GraphicEq, stringResource(R.string.nav_sound), current == Screen.SOUND) { onSelect(Screen.SOUND) }
            NavigatorItem(Icons.Rounded.Headphones, stringResource(R.string.nav_devices), current == Screen.DEVICES) { onSelect(Screen.DEVICES) }
            NavigatorItem(Icons.Rounded.Settings, stringResource(R.string.nav_settings), current == Screen.SETTINGS) { onSelect(Screen.SETTINGS) }
            if (recent.isNotEmpty()) {
                HorizontalDivider(
                    Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    color = LocalContentColor.current.copy(alpha = 0.2f),
                )
                Text(
                    stringResource(R.string.nav_recent_lists),
                    fontSize = AttaccaType.secondary,
                    color = LocalContentColor.current.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
                val playingKey by app.currentListSource.collectAsStateWithLifecycle()
                for (source in recent) {
                    RecentListItem(source, playing = source.key == playingKey?.key) { onOpenList(source) }
                }
            }
        }
    }

    @Composable
    private fun RecentListItem(source: ListSource, playing: Boolean, onClick: () -> Unit) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .clip(AttaccaShapes.large)
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            com.sadurni.attacca.ui.theme.ThemedIcon(
                if (source is ListSource.Folder) Icons.Rounded.Folder else Icons.AutoMirrored.Rounded.QueueMusic,
                contentDescription = null,
                // The accent marks only what is active: the list playing.
                tint = if (playing) LocalAccent.current else LocalAttaccaColors.current.textGrayed,
                modifier = Modifier.size(20.dp),
            )
            Text(
                source.name,
                fontSize = AttaccaType.body,
                fontWeight = if (playing) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 24.dp),
            )
        }
    }

    @Composable
    private fun NavigatorItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
        val accent = LocalAccent.current
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .background(if (selected) accent.copy(alpha = 0.18f) else Color.Transparent, AttaccaShapes.large)
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            com.sadurni.attacca.ui.theme.ThemedIcon(icon, contentDescription = null, tint = if (selected) accent else LocalAttaccaColors.current.textGrayed)
            Text(
                label,
                fontSize = AttaccaType.heading,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.padding(start = 20.dp),
            )
        }
    }

    /** Asks in which format and where to save [items], then writes them. */
    private fun exportItems(name: String, items: List<androidx.media3.common.MediaItem>) {
        if (items.isNotEmpty()) exportAsk.value = name to items
    }

    private fun exportAs(name: String, items: List<androidx.media3.common.MediaItem>, format: com.sadurni.attacca.library.PlaylistFormat) {
        pendingExport = items
        pendingFormat = format
        // In the lists folder when one is set, where the other lists are; else where it was last.
        val listsFolder = app.settings.listsFolder?.takeIf { File(it).isDirectory }?.let(PickerMemory::folderUri)
        exportLaunchers.getValue(format).launch("$name.${format.extension}" to (listsFolder ?: PickerMemory.get(this, PickerMemory.EXPORT_LIST)))
    }

    /** Which format to save a list in. */
    @Composable
    private fun ExportFormatDialog() {
        val ask = exportAsk.value ?: return
        val name = ask.first
        val items = ask.second
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { exportAsk.value = null },
            title = { Text(stringResource(R.string.export_format_title)) },
            text = {
                Column {
                    for ((format, label) in listOf(
                        com.sadurni.attacca.library.PlaylistFormat.M3U8 to R.string.export_format_m3u8,
                        com.sadurni.attacca.library.PlaylistFormat.PLS to R.string.export_format_pls,
                        com.sadurni.attacca.library.PlaylistFormat.XSPF to R.string.export_format_xspf,
                    )) {
                        Text(
                            stringResource(label),
                            modifier = Modifier.fillMaxWidth().clickable {
                                exportAsk.value = null
                                exportAs(name, items, format)
                            }.padding(vertical = 12.dp),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { androidx.compose.material3.TextButton(onClick = { exportAsk.value = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    private fun writeExport(uri: Uri, items: List<androidx.media3.common.MediaItem>, format: com.sadurni.attacca.library.PlaylistFormat) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    // Where the file really is decides how paths are written: relative when it is
                    // on the same storage as the music.
                    val baseDir = UriPaths.resolve(this@MainActivity, uri)?.parentFile
                    val text = UserLists.exportText(items, baseDir, format)
                    contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } != null
                }.getOrDefault(false)
            }
            Toast.makeText(
                this@MainActivity,
                if (ok) getString(R.string.export_done, items.size) else getString(R.string.export_failed),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    @Composable
    private fun PermissionScreen(onGrant: () -> Unit) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.permission_title), fontSize = AttaccaType.title, fontFamily = com.sadurni.attacca.ui.theme.LocalTitleFamily.current, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.permission_text),
                fontSize = AttaccaType.body,
                modifier = Modifier.padding(vertical = 16.dp),
            )
            Button(onClick = onGrant) { Text(stringResource(R.string.permission_grant)) }
        }
    }

    private fun refreshStorageAccess() {
        hasStorageAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName"),
            )
            runCatching { startActivity(intent) }.onFailure {
                com.sadurni.attacca.ui.common.AppPermissions.openOrAppSettings(this, Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            requestStorage.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun play(action: BrowserAction) {
        lifecycleScope.launch {
            when (action) {
                is BrowserAction.PlayFolder -> {
                    val source = ListSource.Folder(action.folder.absolutePath, action.recursive)
                    val items = withContext(Dispatchers.IO) { ListLoader.load(source) }
                    if (items.isEmpty()) {
                        Toast.makeText(this@MainActivity, R.string.browser_no_audio, Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    val start = action.startFile?.let { file -> items.indexOfFirst { it.mediaId == file.absolutePath } } ?: 0
                    viewModel.playItems(source.name, source, items, start.coerceAtLeast(0))
                }
                is BrowserAction.OpenPlaylist -> openPlaylist(action.file)
                is BrowserAction.PlayTracks -> {
                    val items = withContext(Dispatchers.IO) { com.sadurni.attacca.library.MediaItems.fromFiles(action.files) }
                    if (items.isEmpty()) return@launch
                    viewModel.playItems(action.name, null, items, action.start.coerceIn(0, items.lastIndex))
                    // Chained from the first track (a random one when shuffling), once the player has the list.
                    val first = items[action.start.coerceIn(0, items.lastIndex)].mediaId
                    if (action.chain) viewModel.chainWhenLoaded(first, items.size) else viewModel.groupWhenLoaded(first, items.size, action.byAlbums)
                }
                is BrowserAction.PlayFiles -> {
                    val items = withContext(Dispatchers.IO) { ListLoader.loadFiles(action.files) }
                    if (items.isEmpty()) {
                        Toast.makeText(this@MainActivity, R.string.browser_no_audio, Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    val name = action.files.singleOrNull()?.nameWithoutExtension ?: getString(R.string.selection_name)
                    viewModel.playItems(name, null, items)
                }
                is BrowserAction.AddToQueue -> {
                    val items = withContext(Dispatchers.IO) { ListLoader.loadFiles(action.files) }
                    val name = action.files.singleOrNull()?.nameWithoutExtension ?: getString(R.string.selection_name)
                    viewModel.addToQueue(items, name) { count ->
                        Toast.makeText(this@MainActivity, getString(R.string.added_to_queue, count), Toast.LENGTH_SHORT).show()
                    }
                }
                is BrowserAction.PlayNext -> {
                    val items = withContext(Dispatchers.IO) { ListLoader.loadFiles(action.files) }
                    val name = action.files.singleOrNull()?.nameWithoutExtension ?: getString(R.string.selection_name)
                    viewModel.insertNext(items, name) {
                        Toast.makeText(this@MainActivity, R.string.row_plays_next, Toast.LENGTH_SHORT).show()
                    }
                }
                is BrowserAction.AddToFavorites -> {
                    val items = withContext(Dispatchers.IO) { ListLoader.loadFiles(action.files) }
                    viewModel.addItemsToList(com.sadurni.attacca.library.UserLists.favorites, items) { count ->
                        Toast.makeText(this@MainActivity, getString(R.string.added_count, count, getString(R.string.favorites)), Toast.LENGTH_SHORT).show()
                    }
                }
                is BrowserAction.SeedList -> viewModel.requestSeedList(action.file.absolutePath)
                is BrowserAction.SeedGroup -> viewModel.requestGroupSeedList(action.name, action.paths)
                is BrowserAction.QuickList -> viewModel.quickSeedList(action.file.absolutePath)
                is BrowserAction.Bridge -> viewModel.requestBridge(action.from, action.to)
                is BrowserAction.Inclusion -> viewModel.requestInclusion(action.file.absolutePath)
                is BrowserAction.AddToList -> {
                    val items = withContext(Dispatchers.IO) { ListLoader.loadFiles(action.files) }
                    viewModel.addItemsToList(action.list, items) { count ->
                        val target = if (action.list == UserLists.favorites) getString(R.string.favorites) else action.list.nameWithoutExtension
                        Toast.makeText(this@MainActivity, getString(R.string.added_count).format(count, target), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    /** Opens a playlist where it was left last time. */
    private suspend fun openPlaylist(file: File) {
        val source = ListSource.Playlist(file.absolutePath)
        val items = withContext(Dispatchers.IO) { ListLoader.load(source) }
        if (items.isEmpty()) {
            Toast.makeText(this, R.string.browser_playlist_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val point = app.resumeStore.get(source)
        val index = app.resumeStore.startIndex(point, items.map { it.mediaId })
        val position = if (point != null && items[index].mediaId == point.mediaId) point.positionMs else 0L
        viewModel.playItems(source.name, source, items, index, position)
    }

    /**
     * "Open with Attacca" from a file manager. Most hand over content:// URIs; they are resolved
     * to the real file so playlists keep their relative paths and stay listed in Lists. A
     * playlist is shown (not played) like any list; an audio file plays with its folder.
     * Web pages share the file instead (SEND), with the URI in EXTRA_STREAM.
     */
    private fun handleViewIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action == com.sadurni.attacca.playback.DeviceConnectReceiver.ACTION_PLAY) {
            // A device set to start playing connected: the player opens on its list.
            androidx.core.app.NotificationManagerCompat.from(this).cancel(com.sadurni.attacca.playback.DeviceConnectReceiver.NOTIFICATION_ID)
            viewModel.playWhenLoaded()
            requestedScreen = Screen.PLAYER
            return
        }
        val uri = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> sharedStream(intent)
            else -> null
        } ?: return
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) { UriPaths.resolve(this@MainActivity, uri) }
            val name = file?.name ?: UriPaths.displayName(this@MainActivity, uri).orEmpty()
            val isPlaylist = AudioFiles.isPlaylist(File(name)) ||
                intent.type?.let { it.contains("mpegurl") || it.contains("scpls") || it.contains("cue") } == true
            when {
                isPlaylist -> {
                    val playlist = file ?: withContext(Dispatchers.IO) { UriPaths.importCopy(this@MainActivity, uri) }
                    if (playlist == null) {
                        Toast.makeText(this@MainActivity, R.string.browser_playlist_empty, Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    viewModel.viewList(ListSource.Playlist(playlist.absolutePath))
                    requestedScreen = if (viewModel.viewedList.value != null) Screen.LIST_VIEW else Screen.QUEUE
                }
                file?.parentFile != null -> {
                    play(BrowserAction.PlayFolder(file.parentFile!!, file, recursive = false))
                    requestedScreen = Screen.PLAYER
                }
                else -> {
                    viewModel.playItems(name, null, listOf(MediaItems.fromLocation(uri.toString())))
                    requestedScreen = Screen.PLAYER
                }
            }
        }
    }

    /** The shared file; with SEND_MULTIPLE only the first one is opened. */
    private fun sharedStream(intent: Intent): Uri? {
        val extra = if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            }?.firstOrNull()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        return extra ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
    }
}

