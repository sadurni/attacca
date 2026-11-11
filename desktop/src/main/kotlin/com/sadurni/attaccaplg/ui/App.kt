package com.sadurni.attaccaplg.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.delay
import javax.swing.UIManager

/** The desktop app: one window with the library, its tracks, lists, the exchange with Attacca and settings. */
fun main(args: Array<String>) {
    // With a command (`AttaccaDesktop scan …`), the packaged app works as the command line: its own Java
    // has no `java` to run the command line's scripts with.
    if (args.isNotEmpty()) {
        com.sadurni.attaccaplg.main(args)
        return
    }
    // What goes wrong is kept in error.log beside the library (the window has no console).
    Thread.setDefaultUncaughtExceptionHandler { _, e -> logError(e) }
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }.onFailure(::logError)
    val prefs = Prefs()
    Lang.apply(prefs["language"] ?: "system")
    DeskTaste.load(prefs)
    val state = AppState(prefs)
    application {
        val window = rememberWindowState(
            size = DpSize(prefs["width"]?.toFloatOrNull()?.dp ?: 1240.dp, prefs["height"]?.toFloatOrNull()?.dp ?: 820.dp),
            position = WindowPosition(Alignment.Center),
            placement = if (prefs["maximized"] == "true") WindowPlacement.Maximized else WindowPlacement.Floating,
        )
        var visible by remember { mutableStateOf(true) }
        // O'rama over the whole screen, back to how the window was when it ends.
        var placementBefore by remember { mutableStateOf(window.placement) }
        val full = state.oramaFull && (state.page == Page.ORAMA || state.page == Page.WORLD)
        LaunchedEffect(full) {
            if (full) {
                placementBefore = window.placement
                window.placement = WindowPlacement.Fullscreen
            } else if (window.placement == WindowPlacement.Fullscreen) {
                window.placement = placementBefore
            }
        }
        val p = state.player
        fun quit() {
            MediaKeys.stop()
            state.close()
            exitApplication()
        }
        LaunchedEffect(Unit) {
            if (prefs["media_keys"] != "false") {
                MediaKeys.start { action ->
                    when (action) {
                        "toggle" -> p.toggle()
                        "next" -> p.next()
                        "previous" -> p.previous()
                        "stop" -> if (p.playing) p.toggle()
                    }
                }
            }
        }
        // In the system tray: the transport, the windows and quitting.
        Tray(
            icon = painterResource("icon.png"),
            tooltip = p.current?.let { listOf(it.title, it.artist).filter(String::isNotBlank).joinToString(" · ") } ?: "Attacca Desktop",
            onAction = { visible = true },
            menu = {
                Item(if (p.playing) tr("Pause", "Pausa") else tr("Play", "Reproducir"), onClick = { p.toggle() })
                Item(tr("Next", "Siguiente"), onClick = { p.next() })
                Item(tr("Previous", "Anterior"), onClick = { p.previous() })
                Separator()
                Item(tr("Mini player", "Minirreproductor"), onClick = { state.mini = !state.mini })
                Item(tr("Show the window", "Mostrar la ventana"), onClick = { visible = true })
                Item(tr("Quit", "Salir"), onClick = { quit() })
            },
        )
        Window(
            onCloseRequest = {
                prefs["maximized"] = (window.placement == WindowPlacement.Maximized).toString()
                if (window.placement == WindowPlacement.Floating) {
                    prefs["width"] = window.size.width.value.toString()
                    prefs["height"] = window.size.height.value.toString()
                }
                // Playing on in the tray, when that is what the settings say.
                if (prefs["keep_in_tray"] == "true" && p.playing) visible = false else quit()
            },
            visible = visible,
            state = window,
            title = "Attacca Desktop",
            icon = painterResource("icon.png"),
            onPreviewKeyEvent = { e -> shortcut(e, state) },
        ) {
            AppTheme(state.theme, p.current?.path, state.coverAccent) { AppWindow(state) }
        }
        if (state.mini) {
            Window(
                onCloseRequest = { state.mini = false },
                state = rememberWindowState(size = DpSize(420.dp, 124.dp), position = WindowPosition(Alignment.BottomEnd)),
                title = "Attacca",
                icon = painterResource("icon.png"),
                alwaysOnTop = true,
                resizable = false,
                onPreviewKeyEvent = { e -> shortcut(e, state) },
            ) {
                AppTheme(state.theme, p.current?.path, state.coverAccent) { MiniPlayer(state) }
            }
        }
    }
}

/** The window's shortcuts: Ctrl+Space play/pause, Ctrl+→ / ← next / previous, Ctrl+↑ / ↓ volume, Page Up / Down. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
private fun shortcut(e: androidx.compose.ui.input.key.KeyEvent, state: AppState): Boolean {
    if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return false
    // Esc ends O'rama over the whole screen; F11 starts or ends it.
    if ((state.page == Page.ORAMA || state.page == Page.WORLD) && (e.key == androidx.compose.ui.input.key.Key.F11 || (state.oramaFull && e.key == androidx.compose.ui.input.key.Key.Escape))) {
        state.oramaFull = e.key == androidx.compose.ui.input.key.Key.F11 && !state.oramaFull
        return true
    }
    // Page Up / Page Down move the page's list; Ctrl+Home / Ctrl+End to its start or end.
    when (e.key) {
        androidx.compose.ui.input.key.Key.PageUp, androidx.compose.ui.input.key.Key.PageDown -> return PageKeys.handle(e.key)
        androidx.compose.ui.input.key.Key.MoveHome, androidx.compose.ui.input.key.Key.MoveEnd -> if (e.isCtrlPressed) return PageKeys.handle(e.key)
        else -> {}
    }
    if (!e.isCtrlPressed) return false
    val p = state.player
    when (e.key) {
        androidx.compose.ui.input.key.Key.Spacebar -> p.toggle()
        androidx.compose.ui.input.key.Key.DirectionRight -> p.next()
        androidx.compose.ui.input.key.Key.DirectionLeft -> p.previous()
        androidx.compose.ui.input.key.Key.DirectionUp -> p.update { it.copy(volume = (it.volume + 0.05f).coerceAtMost(1f)) }
        androidx.compose.ui.input.key.Key.DirectionDown -> p.update { it.copy(volume = (it.volume - 0.05f).coerceAtLeast(0f)) }
        else -> return false
    }
    return true
}

private fun logError(e: Throwable) {
    runCatching {
        val log = java.io.File(com.sadurni.attaccaplg.library.LibraryDb.HOME, "error.log")
        log.parentFile.mkdirs()
        log.appendText("${java.time.LocalDateTime.now()}  ${e.stackTraceToString()}\n")
    }
    e.printStackTrace()
}

@Composable
internal fun AppWindow(state: AppState) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val full = state.oramaFull && (state.page == Page.ORAMA || state.page == Page.WORLD)
        Row(Modifier.fillMaxSize()) {
            if (!full) NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                Spacer(Modifier.height(8.dp))
                Rail(state, Page.PLAYING, PlayerIcons.PlayCircle, tr("Playing", "Reproduciendo"))
                Rail(state, Page.ORAMA, PlayerIcons.Landscape, "O'rama")
                Rail(state, Page.WORLD, PlayerIcons.Cube, "World")
                Rail(state, Page.MUSIC, PlayerIcons.LibraryMusic, tr("Music", "Música"))
                Rail(state, Page.COVERS, PlayerIcons.Album, tr("Covers", "Portadas"))
                Rail(state, Page.LIBRARY, NavIcons.Library, tr("Library", "Biblioteca"))
                Rail(state, Page.TRACKS, NavIcons.Tracks, tr("Tracks", "Pistas"))
                Rail(state, Page.LISTS, NavIcons.Lists, tr("Lists", "Listas"))
                Rail(state, Page.SOUND, PlayerIcons.GraphicEq, tr("Sound", "Sonido"))
                Rail(state, Page.EXCHANGE, NavIcons.Exchange, "Attacca")
                Rail(state, Page.THEMES, PlayerIcons.Palette, tr("Themes", "Temas"))
                Spacer(Modifier.weight(1f))
                Rail(state, Page.SETTINGS, NavIcons.Settings, tr("Settings", "Ajustes"))
                Spacer(Modifier.height(8.dp))
            }
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    ThemeBackground(state.theme, state.page)
                    when (state.page) {
                        Page.PLAYING -> PlayingPage(state)
                        Page.ORAMA -> OramaPage(state)
                        Page.WORLD -> com.sadurni.attaccaplg.ui.world.WorldPage(state)
                        Page.MUSIC -> MusicPage(state)
                        Page.COVERS -> CoversPage(state)
                        Page.LIBRARY -> LibraryPage(state)
                        Page.TRACKS -> TracksPage(state)
                        Page.LISTS -> ListsPage(state)
                        Page.SOUND -> SoundPage(state)
                        Page.EXCHANGE -> ExchangePage(state)
                        Page.THEMES -> ThemesPage(state)
                        Page.SETTINGS -> SettingsPage(state)
                    }
                }
                if (!full) StatusBar(state)
                // O'rama and Attacca World have their own small player.
                if (state.page != Page.ORAMA && state.page != Page.WORLD) PlayerBar(state)
                MenuDialogs(state)
            }
        }
    }
}

@Composable
private fun Rail(state: AppState, page: Page, icon: ImageVector, label: String) {
    NavigationRailItem(
        selected = state.page == page,
        onClick = { state.page = page },
        icon = { Icon(icon, contentDescription = null) },
        label = { Text(label) },
    )
}

/** What is going on (at the bottom), and messages. */
@Composable
private fun StatusBar(state: AppState) {
    val message = state.message
    LaunchedEffect(message) {
        if (message != null) {
            delay(8000)
            if (state.message == message) state.message = null
        }
    }
    if (message != null) {
        Snackbar(
            modifier = Modifier.padding(12.dp),
            action = { TextButton(onClick = { state.message = null }) { Text("OK") } },
        ) { Text(message) }
    } else if (state.busy != null && state.page != Page.LIBRARY) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text("  " + state.busy + "…", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** A page's frame: title and content, scrolling or not. */
@Composable
fun PageFrame(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 20.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxHeight()) { content() }
    }
}
