package com.sadurni.attaccaplg.ui

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.sadurni.attacca.chain.EdgeDescriptor
import com.sadurni.attacca.chain.Mood
import com.sadurni.attacca.chain.TrackFeatures
import com.sadurni.attacca.library.LibraryExchange
import com.sadurni.attaccaplg.library.LibraryDb
import com.sadurni.attaccaplg.library.Scanner
import com.sadurni.attaccaplg.library.StoredTrack
import com.sadurni.attaccaplg.library.Tags
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.sql.DriverManager
import javax.swing.SwingUtilities

/**
 * Development: draws the window's pages to PNG files without a screen, to look at them.
 * `snapshots LIBRARY OUT [en|es] [light|dark]`, or `--from-attlib FILE.attlib LIBRARY` to make a
 * library straight from an exchange file (its music need not be on this PC).
 */
fun main(args: Array<String>) {
    if (args.firstOrNull() == "--from-attlib") return fromAttlib(File(args[1]), File(args[2]))
    val db = File(args[0])
    val out = File(args[1]).apply { mkdirs() }
    Lang.apply(args.getOrElse(2) { "en" })
    val temp = File.createTempFile("prefs", ".properties").apply { deleteOnExit() }
    val state = AppState(Prefs(temp), db)
    state.theme = args.getOrElse(3) { "dark" }
    while (state.loading) Thread.sleep(50)
    val library = state.library
    Thread.sleep(1500)
    println("queue left last time, back: ${state.player.queue.size} tracks")
    fun shot(name: String, height: Int = 820, act: (ImageComposeScene) -> Unit = {}) {
        SwingUtilities.invokeAndWait { }
        Thread.sleep(200)
        val scene = ImageComposeScene(1240, height, Density(1f)) { AppTheme(state.theme, state.player.current?.path, state.coverAccent) { AppWindow(state) } }
        scene.render(0)
        act(scene)
        scene.render(500_000_000)
        val image = scene.render(1_000_000_000)
        File(out, "$name.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
        println("$name.png")
    }
    if (library != null) {
        // A queue, not playing (nothing sounds while drawing).
        state.player.cuePaths(library.all.filter { it.path.startsWith("Queen/1978-Jazz") }.ifEmpty { library.all.take(12) }.map { it.path }, 2)
        Thread.sleep(600)
        state.page = Page.PLAYING
        Thread.sleep(1500) // covers load in the background
        shot("0-playing")
        state.page = Page.ORAMA
        Thread.sleep(800)
        shot("0-orama")
        run {
            val scene = ImageComposeScene(420, 124, Density(1f)) { AppTheme(state.theme, state.player.current?.path, state.coverAccent) { MiniPlayer(state) } }
            scene.render(0)
            Thread.sleep(800)
            val image = scene.render(1_000_000_000)
            File(out, "0-mini.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            scene.close()
            println("0-mini.png")
        }
        println("media keys: " + MediaKeys.start { })
        MediaKeys.stop()
        state.page = Page.MUSIC
        shot("0-music")
        Thread.sleep(2500)
        shot("0-music")
        state.page = Page.SETTINGS
        shot("0-settings")
        state.player.updateDsp { it.copy(eqEnabled = true, eqGains = listOf(4f, 3f, 1f, 0f, -1f, 0f, 1f, 2f, 3f, 1f), crossfeedEnabled = true, bassEnabled = true) }
        state.page = Page.SOUND
        shot("0-sound", 2000)
        state.page = Page.THEMES
        shot("0-themes", 1000)
        state.theme = "gallery:Aurora"
        shot("0-themes-aurora", 1000)
        // An own copy, being edited (in the snapshots' own data folder).
        Themes.own().filter { it.name == "Mine" }.forEach(Themes::delete)
        Themes.copy(Themes.of("gallery:Aurora"), "Mine")?.let { state.theme = it.id }
        shot("0-themes-edit", 4400)
        state.theme = args.getOrElse(3) { "dark" }
    }
    state.page = Page.LIBRARY
    shot("1-library")
    state.page = Page.TRACKS
    shot("2-tracks")
    // Page Down twice (the window passes it to the page's list).
    shot("2c-tracks-page-down") {
        PageKeys.handle(androidx.compose.ui.input.key.Key.PageDown)
        it.render(100_000_000)
        PageKeys.handle(androidx.compose.ui.input.key.Key.PageDown)
    }
    state.trackSelected = library?.all?.firstOrNull { it.features != null }?.path
    shot("2b-tracks-chosen")
    state.page = Page.LISTS
    shot("3-lists-empty")
    if (library != null) {
        state.lists.moods = setOf(Mood.CALM)
        state.lists.make(library, state.inclusionList, state.inclusionMode)
        shot("4-lists-filters")
        state.lists.clearFilters()
        state.lists.kind = ListKind.AUTO
        state.lists.make(library, state.inclusionList, state.inclusionMode)
        state.lists.expanded = null
        shot("5-lists-auto")
        state.lists.kind = ListKind.SEED
        state.lists.seed = library.all.first { it.features != null }.path
        state.lists.make(library, state.inclusionList, state.inclusionMode)
        shot("6-lists-seed")
        state.lists.fitToSeed(library, state.inclusionList, state.inclusionMode)
        state.lists.make(library, state.inclusionList, state.inclusionMode)
        shot("6b-lists-seed-fitted")
    }
    if (library != null) {
        // The right-click menu of a track, its information, the lists kept.
        state.page = Page.TRACKS
        state.trackSelected = null
        shot("10-track-menu") { scene ->
            val at = androidx.compose.ui.geometry.Offset(420f, 250f)
            val right = androidx.compose.ui.input.pointer.PointerButtons(isSecondaryPressed = true)
            @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
            scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Move, at)
            @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
            scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Press, at, buttons = right,
                button = androidx.compose.ui.input.pointer.PointerButton.Secondary)
            @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
            scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Release, at,
                button = androidx.compose.ui.input.pointer.PointerButton.Secondary)
        }
        // A join into another album: the end trimmed and the blend show in the wave.
        val a = library.all.first { it.features != null && it.audioEndMs in 1 until it.durationMs - 1500 }
        val other = library.all.first { it.tags.album != a.tags.album && it.features != null }
        state.player.cuePaths(listOf(a.path, other.path), 0)
        Thread.sleep(800)
        state.page = Page.PLAYING
        shot("0b-playing-cut")
        state.info = library.all.first { it.features != null }
        shot("11-track-info")
        state.info = null
        state.lists.kind = ListKind.AUTO
        state.lists.make(library, state.inclusionList, state.inclusionMode)
        state.player.lists.saveMade(state.lists.made, library.root!!)
        state.player.saveQueueAs("Jazz evening")
        state.player.lists.entries.firstOrNull()?.let { e -> state.player.lists.remember(e.file, e.paths.last(), 83_000) }
        state.page = Page.LISTS
        state.listsView = ListsView.SAVED
        shot("12-kept-lists")
        state.listsView = ListsView.MAKE
        state.addToList = listOf(File(library.root, library.all.first().path).path)
        shot("13-add-to-list")
        state.addToList = null
        // Nothing queued: the list played last, to go on with it.
        state.player.lists.entries.firstOrNull()?.let { e -> state.prefs["last_list"] = e.file.path }
        state.player.clear()
        state.page = Page.PLAYING
        shot("14-go-on")
    }
    state.page = Page.EXCHANGE
    shot("7-exchange")
    state.page = Page.SETTINGS
    shot("8-settings")
    Thread.sleep(3000) // beets is looked for in the background
    shot("9-settings-whole", 3200)
    // A queue left for the next run, which must find it back.
    library?.let { lib -> state.player.cuePaths(lib.all.take(5).map { it.path }, 2) }
    Thread.sleep(500)
    state.close()
    System.exit(0)
}

private fun fromAttlib(attlib: File, target: File) {
    target.delete()
    LibraryDb(target).use { db ->
        DriverManager.getConnection("jdbc:sqlite:${attlib.absolutePath}").use { c ->
            val meta = c.createStatement().executeQuery("SELECT key, value FROM meta").use { rs -> buildMap { while (rs.next()) put(rs.getString(1), rs.getString(2)) } }
            meta["edge_seconds"]?.toIntOrNull()?.let { db.edgeSeconds = it }
            db.setMeta("music_root", "M:/Music")
            fun blob(b: ByteArray?) = b?.let(LibraryExchange::floats)
            c.createStatement().executeQuery("SELECT ${LibraryExchange.COLUMNS} FROM tracks").use { rs ->
                var n = 0
                while (rs.next()) {
                    fun int(name: String) = rs.getInt(name).takeUnless { rs.wasNull() }
                    db.put(
                        StoredTrack(
                            path = rs.getString("path"), size = rs.getLong("size"), mtime = 0,
                            tags = Tags(
                                rs.getString("title").orEmpty(), rs.getString("artist").orEmpty(), rs.getString("album").orEmpty(),
                                rs.getString("album_artist").orEmpty(), rs.getString("genre").orEmpty(),
                                int("year")?.takeIf { it > 0 }, int("track")?.takeIf { it > 0 }, int("disc")?.takeIf { it > 0 },
                            ),
                            durationMs = rs.getLong("duration_ms"), audioStartMs = rs.getLong("start_ms"), audioEndMs = rs.getLong("end_ms"),
                            lufs = rs.getDouble("lufs").takeUnless { rs.wasNull() }?.toFloat(), peak = rs.getFloat("peak"),
                            head = blob(rs.getBytes("head"))?.takeIf { it.size == EdgeDescriptor.SIZE }?.let { EdgeDescriptor.fromArray(it) },
                            tail = blob(rs.getBytes("tail"))?.takeIf { it.size == EdgeDescriptor.SIZE }?.let { EdgeDescriptor.fromArray(it) },
                            features = blob(rs.getBytes("features"))?.takeIf { it.size == TrackFeatures.SIZE }?.let(TrackFeatures::fromArray),
                            error = null, waveform = blob(rs.getBytes("waveform")),
                        ),
                        Scanner.VERSION,
                    )
                    if (++n % 2000 == 0) db.commit()
                }
                db.commit()
                println("$n tracks")
            }
        }
    }
}
