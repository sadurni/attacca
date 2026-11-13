package com.sadurni.attacca.ui.world

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.material.icons.rounded.Fullscreen
import android.opengl.GLSurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.VerticalSplit
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Landscape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sadurni.attacca.R
import com.sadurni.attacca.app
import com.sadurni.attacca.diorama.Diorama
import com.sadurni.attacca.diorama.SongWave
import com.sadurni.attacca.ui.PlayerViewModel
import com.sadurni.attacca.world.WorldCamera
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * Attacca World: the song as a land in blocks that the traveller and the four dogs go through,
 * seen from behind. The land rises as loud as the music is; the camera follows the music on its
 * own, and a finger (turn) or two (closer or further, higher or lower) move it for a while.
 */
@Composable
fun WorldScreen(viewModel: PlayerViewModel, onDismiss: () -> Unit, onOpenOrama: () -> Unit = {}, onOpenQueue: () -> Unit) {
    // Back over the whole screen brings the controls back first.
    var full by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    // While it is open the strip in Now playing rests.
    DisposableEffect(Unit) {
        com.sadurni.attacca.ui.orama.OramaShared.worldOpen.value = true
        onDispose { com.sadurni.attacca.ui.orama.OramaShared.worldOpen.value = false }
    }
    Dialog(onDismissRequest = { if (full) full = false else onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        DisposableEffect(view) {
            view.keepScreenOn = true
            onDispose { view.keepScreenOn = false }
        }
        // Over the whole screen the system bars hide too (a swipe from the edge shows them for a moment), as in O'rama.
        val window = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
        DisposableEffect(window, full) {
            val controller = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
            if (full) {
                controller?.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            } else {
                controller?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            }
            onDispose { }
        }
        WorldContent(viewModel, full, { full = it }, onDismiss, onOpenOrama, onOpenQueue)
    }
}

@Composable
private fun WorldContent(viewModel: PlayerViewModel, full: Boolean, onFull: (Boolean) -> Unit, onDismiss: () -> Unit, onOpenOrama: () -> Unit, onOpenQueue: () -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val now by viewModel.nowPlaying.collectAsStateWithLifecycle()
    val position by viewModel.positionMs.collectAsStateWithLifecycle()
    val analyses by app.analysisResults.collectAsStateWithLifecycle()
    val cover by viewModel.cover.collectAsStateWithLifecycle()
    val id = now.mediaId
    val coverBitmap = cover.bitmap.takeIf { cover.mediaId == id }
    // The song's look, known for this very song (not the one before's while it is worked out).
    // (Its own small cover, the same as for the song coming next: no colours changing as it comes.)
    val lookFor by produceState<Pair<String?, com.sadurni.attacca.diorama.SongLook?>>(null to null, id) {
        value = id to com.sadurni.attacca.ui.orama.songLook(context, id)
    }
    val look = lookFor.second.takeIf { lookFor.first == id }
    val analysis = id?.let { analyses[it] }
    val clipEnd = now.clipEndMs ?: analysis?.durationMs ?: now.durationMs
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    val nextId = when {
        queue.isEmpty() || id == null -> null
        now.repeatMode == androidx.media3.common.Player.REPEAT_MODE_ONE -> id
        now.index + 1 in queue.indices -> queue[now.index + 1].mediaId
        now.repeatMode == androidx.media3.common.Player.REPEAT_MODE_ALL -> queue.first().mediaId
        else -> null
    }
    val nextAnalysis = nextId?.let { analyses[it] }
    val moodLine by produceState<String?>(null, id) {
        value = withContext(Dispatchers.IO) { com.sadurni.attacca.ui.orama.moodLineOf(context, id) }
    }
    // The cover, small, by the title.
    val coverImage by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, coverBitmap) {
        value = coverBitmap?.let { b ->
            withContext(Dispatchers.Default) { runCatching { android.graphics.Bitmap.createScaledBitmap(b, 160, 160, true).asImageBitmap() }.getOrNull() }
        }
    }
    // 30 or 60 frames a second (the same choice as O'rama's).
    val prefs = remember { context.getSharedPreferences("orama", android.content.Context.MODE_PRIVATE) }
    var fps60 by remember { mutableStateOf(prefs.getBoolean("fps60", false)) }
    val feed = rememberWorldFeed(viewModel)
    // Behind the traveller, or through its eyes when chosen in Settings (the eye button changes it for the moment).
    val camera = remember { WorldCamera().also { it.firstPerson = context.app.settings.ui.value.worldEyes } }
    val chosen by com.sadurni.attacca.ui.orama.OramaShared.chosenWorld
    var manual by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            manual = camera.manual
            delay(300)
        }
    }

    val split by com.sadurni.attacca.ui.orama.OramaShared.split
    val songProgress = (position.toFloat() / (clipEnd - now.clipStartMs).coerceAtLeast(1L)).coerceIn(0f, 1f)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
      // Split: Attacca World above (or on the right), O'rama below (or on the left).
      androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val wideScreen = maxWidth > maxHeight
        val scene: @Composable (Modifier) -> Unit = { m -> Box(m) { WorldScene(feed, camera, fps60, full, onFull) } }
        if (!split) {
            scene(Modifier.fillMaxSize())
        } else {
            // Upright World above, O'rama below; wide World on the left, O'rama on the right.
            // World's land first; O'rama over it, reaching a little into World's half and fading
            // out towards it, so the two landscapes melt into each other where they meet (no band).
            // (Only see-through there: World's land, a surface under the window, shows through.)
            val half = if (wideScreen) Modifier.fillMaxHeight().fillMaxWidth(0.5f) else Modifier.fillMaxWidth().fillMaxHeight(0.5f)
            scene(half.align(if (wideScreen) Alignment.CenterStart else Alignment.TopCenter))
            val melt = 56.dp
            val oramaPart = if (wideScreen) Modifier.fillMaxHeight().width(maxWidth / 2 + melt) else Modifier.fillMaxWidth().height(maxHeight / 2 + melt)
            Box(
                oramaPart.align(if (wideScreen) Alignment.CenterEnd else Alignment.BottomCenter).clipToBounds()
                    .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        val edge = melt.toPx()
                        val fade = if (wideScreen) {
                            androidx.compose.ui.graphics.Brush.horizontalGradient(0f to Color.Transparent, edge / size.width to Color.Black, 1f to Color.Black)
                        } else {
                            androidx.compose.ui.graphics.Brush.verticalGradient(0f to Color.Transparent, edge / size.height to Color.Black, 1f to Color.Black)
                        }
                        drawRect(fade, blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
                    },
            ) {
                com.sadurni.attacca.ui.orama.OramaScene(viewModel, full, onFull)
            }
        }
      }
        if (!full) {
            WorldOverlay(viewModel, now, position, analysis, clipEnd, nextAnalysis, id, coverImage, moodLine, chosen, manual, camera, fps60, { fps60 = it; prefs.edit().putBoolean("fps60", it).apply() }, onFull, onDismiss, onOpenOrama, onOpenQueue, split)
        }
        // Over the whole screen, unsplit: the same band along the bottom, filling as the song goes on;
        // split, a thin line in the accent colour along the very bottom.
        if (full && !split) com.sadurni.attacca.ui.orama.ProgressBand(songProgress, vertical = false, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(26.dp))
        if (full && split) com.sadurni.attacca.ui.orama.ProgressLine(songProgress, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp))
        // Over the whole screen with nothing else on it: the song's name as it starts, fading in and out (one, in the middle, when split).
        com.sadurni.attacca.ui.orama.SongIntro(id, now.title, now.artist, full, now.album)
    }
}

/** What Attacca World's land is drawn from (the song, its look, where it is, what is heard), kept up to date. */
@Composable
internal fun rememberWorldFeed(viewModel: PlayerViewModel): WorldFeed {
    val context = LocalContext.current
    val app = context.app
    val now by viewModel.nowPlaying.collectAsStateWithLifecycle()
    val position by viewModel.positionMs.collectAsStateWithLifecycle()
    val analyses by app.analysisResults.collectAsStateWithLifecycle()
    val cover by viewModel.cover.collectAsStateWithLifecycle()
    val id = now.mediaId
    val coverBitmap = cover.bitmap.takeIf { cover.mediaId == id }
    // The song's look, known for this very song (not the one before's while it is worked out).
    // (Its own small cover, the same as for the song coming next: no colours changing as it comes.)
    val lookFor by produceState<Pair<String?, com.sadurni.attacca.diorama.SongLook?>>(null to null, id) {
        value = id to com.sadurni.attacca.ui.orama.songLook(context, id)
    }
    val look = lookFor.second.takeIf { lookFor.first == id }
    val analysis = id?.let { analyses[it] }
    val clipEnd = now.clipEndMs ?: analysis?.durationMs ?: now.durationMs
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    val nextId = when {
        queue.isEmpty() || id == null -> null
        now.repeatMode == androidx.media3.common.Player.REPEAT_MODE_ONE -> id
        now.index + 1 in queue.indices -> queue[now.index + 1].mediaId
        now.repeatMode == androidx.media3.common.Player.REPEAT_MODE_ALL -> queue.first().mediaId
        else -> null
    }
    val feed = remember { WorldFeed() }
    // The festive days' look, as O'rama has it (on or off from O'rama's own button).
    val festiveOn by com.sadurni.attacca.festive.FestiveState.on.collectAsStateWithLifecycle()
    val festivePeriod by com.sadurni.attacca.festive.FestiveState.period.collectAsStateWithLifecycle()
    val newYear by com.sadurni.attacca.festive.FestiveState.show.collectAsStateWithLifecycle()
    feed.festive = if (festiveOn) festivePeriod?.festivity else null
    feed.kings = festiveOn && festivePeriod?.kings == true
    feed.vikram = newYear?.newYear == com.sadurni.attacca.festive.NewYear.VIKRAM
    if (com.sadurni.attacca.BuildConfig.DEBUG) {
        // Testing: adb can set a festive look (orama prefs, "world_festive": CHRISTMAS, CHRISTMAS_KINGS, DIWALI…).
        context.getSharedPreferences("orama", android.content.Context.MODE_PRIVATE).getString("world_festive", null)?.let { f ->
            feed.festive = com.sadurni.attacca.festive.Festivity.entries.firstOrNull { it.name == f.removeSuffix("_KINGS") }
            feed.kings = f.endsWith("_KINGS")
        }
        // And the sun as a record ("world_weather" with "record").
        if ("record" in (context.getSharedPreferences("orama", android.content.Context.MODE_PRIVATE).getString("world_weather", null) ?: "")) feed.recordOf = id

    }
    if (com.sadurni.attacca.BuildConfig.DEBUG) {
        // Testing: adb can set a traveller (orama prefs, "world_traveller") to see each vehicle.
        val forced = context.getSharedPreferences("orama", android.content.Context.MODE_PRIVATE).getString("world_traveller", null)
        feed.forceTraveller = com.sadurni.attacca.diorama.Traveller.entries.firstOrNull { it.name == forced }
        feed.forcePeak = "peak" in (context.getSharedPreferences("orama", android.content.Context.MODE_PRIVATE).getString("world_weather", null) ?: "")
    }
    // A world chosen by hand (null: the song's own), kept while Attacca World is open.
    val chosen by com.sadurni.attacca.ui.orama.OramaShared.chosenWorld
    // The next song's look (without its cover: its world and traveller are what matter here).
    val nextLookRaw by produceState<com.sadurni.attacca.diorama.SongLook?>(null, nextId) {
        value = nextId?.let { com.sadurni.attacca.ui.orama.songLook(context, it) }
    }
    // Song after song: the look shown before (two alike in a row, the second in another look, as
    // in O'rama) and where each song starts on the land's own timeline (O'rama's places fall there).
    val seq = remember { Sequence() }
    if (id != seq.id) {
        seq.prevLook = seq.shown
        seq.origin += seq.length
        seq.id = id
    }
    seq.length = ((analysis?.let { clipEnd - now.clipStartMs }) ?: 0L).toDouble().coerceAtLeast(0.0)
    val nextPlace = when {
        nextId == null || now.count <= 0 -> null
        now.repeatMode == androidx.media3.common.Player.REPEAT_MODE_ONE -> "${now.index + 1}/${now.count}"
        now.index + 1 < now.count -> "${now.index + 2}/${now.count}"
        else -> "1/${now.count}"
    }
    LaunchedEffect(nextLookRaw, now.nextTitle, nextPlace, coverBitmap, chosen) {
        feed.nextTitle = now.nextTitle
        feed.nextPlace = nextPlace
        feed.cover = coverBitmap
    }

    // The song for the renderer.
    LaunchedEffect(id, look, analysis, clipEnd, chosen, nextLookRaw, nextId?.let { analyses[it] }) {
        // Until the song's own look is known the land goes on as it was (never a world in between).
        if (look == null && id != null) return@LaunchedEffect
        val wave = analysis?.let { a -> SongWave(a.waveform, a.durationMs, now.clipStartMs, clipEnd) }
        val chosenWorld = com.sadurni.attacca.diorama.World.entries.firstOrNull { it.name == chosen }
        val newLook = look?.let outer@{ l0 ->
            // The same look as O'rama's for this song (its own, or another after one alike).
            // O'rama's own decision for this song (the same world in both).
            val l = if (chosenWorld == null) com.sadurni.attacca.ui.orama.OramaShared.diorama.lookForTrack(id, l0)
            else com.sadurni.attacca.ui.orama.OramaShared.diorama.trackLookFor(id)?.takeIf { it.world == chosenWorld }
                ?: Diorama.following(com.sadurni.attacca.ui.orama.OramaShared.shown(l0), seq.prevLook)
            seq.shown = l
            if (chosenWorld != null || !com.sadurni.attacca.BuildConfig.DEBUG) return@outer l
            // Testing: a world and weather to see (orama prefs "world_world", "world_weather": rain, snow, storm, night).
            val prefs = context.getSharedPreferences("orama", android.content.Context.MODE_PRIVATE)
            val w = com.sadurni.attacca.diorama.World.entries.firstOrNull { it.name == prefs.getString("world_world", null) } ?: return@outer l
            val weather = prefs.getString("world_weather", "") ?: ""
            com.sadurni.attacca.diorama.SongLook(
                w, feed.forceTraveller ?: l.traveller, l.palette,
                if ("night" in weather) 0.05f else l.sun, if ("cloud" in weather || "storm" in weather) 0.8f else l.clouds,
                if ("rain" in weather) 0.9f else 0f, if ("storm" in weather) 0.9f else 0f, if ("snow" in weather) 0.9f else 0f,
                l.bpm, l.pulse, l.energy,
            )
        }
        // What is seen coming at the end: the next song just as it will start (its first stretch's world).
        val nextWave = nextId?.let { analyses[it] }?.let { a -> SongWave(a.waveform, a.durationMs, 0L, a.durationMs) }
        val newNext = nextLookRaw?.let { n -> Diorama.startOf(Diorama.following(chosenWorld?.let { com.sadurni.attacca.diorama.Looks.inWorld(n, it) } ?: n, newLook), if (chosenWorld == null) nextWave else null) }
        val newOrigin = com.sadurni.attacca.ui.orama.OramaShared.diorama.originFor(id) ?: seq.origin
        // All of the song at once for the renderer (it reads them together).
        synchronized(feed) {
            feed.wave = wave
            feed.peakMs = if (id != null && wave != null) Diorama.Song(id, look ?: Diorama.NEUTRAL, wave).peakMs else -1.0
            feed.look = newLook
            feed.sectioned = chosen == null
            feed.originMs = newOrigin
            feed.nextLook = newNext
            feed.songId = id
        }
    }
    val positionItem by viewModel.positionItem.collectAsStateWithLifecycle()
    LaunchedEffect(position, now.isPlaying, positionItem) {
        // (The place and the song it belongs to: the player may give the next song's place a
        // moment before the song shown changes.)
        feed.positionFor = positionItem
        feed.positionMs = position
        feed.positionAtNs = System.nanoTime()
        feed.playing = now.isPlaying
    }
    // What is heard now, for the beat.
    LaunchedEffect(Unit) {
        val raw = FloatArray(3)
        while (true) {
            val heard = app.dspEngine.heardLevels(raw)
            if (heard) System.arraycopy(raw, 0, feed.levels, 0, 3)
            feed.heard = heard
            delay(16)
        }
    }
    return feed
}

/** What World keeps from song to song: the look shown and where each song starts on the land's own timeline. */
private class Sequence {
    var id: String? = null
    var shown: com.sadurni.attacca.diorama.SongLook? = null
    var prevLook: com.sadurni.attacca.diorama.SongLook? = null
    var origin = 0.0
    var length = 0.0
}

/** Attacca World's land: the GL view and the camera's gestures. */
@Composable
private fun WorldScene(feed: WorldFeed, camera: WorldCamera, fps60: Boolean, full: Boolean, onFull: (Boolean) -> Unit, onTap: (() -> Unit)? = null) {
    Box(Modifier.fillMaxSize()) {
        var surface by remember { mutableStateOf<GLSurfaceView?>(null) }
        AndroidView(
            factory = { c ->
                GLSurfaceView(c).apply {
                    setEGLContextClientVersion(3)
                    setEGLConfigChooser(8, 8, 8, 8, 16, 0)
                    setRenderer(WorldRenderer(feed, camera))
                    renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                    surface = this
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        DisposableEffect(surface) {
            surface?.onResume()
            onDispose { surface?.onPause() }
        }
        // The festive sky over it (not in Now playing's strip).
        if (onTap == null) FestiveSky(feed)
        LaunchedEffect(surface, fps60) {
            val step = if (fps60) 16_000_000L else 32_000_000L
            var last = 0L
            while (true) {
                androidx.compose.runtime.withFrameNanos { t ->
                    if (t - last >= step - 4_000_000L) {
                        surface?.requestRender()
                        last = t
                    }
                }
            }
        }
        // Gestures: one finger turns the camera, two bring it closer or further and raise or lower it.
        val sunTaps = remember { LongArray(5) { Long.MIN_VALUE / 2 } }
        // The gestures stay for the view's life: they read the whole screen as it is now.
        val fullNow by androidx.compose.runtime.rememberUpdatedState(full)
        val onFullNow by androidx.compose.runtime.rememberUpdatedState(onFull)
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false)
                    var moved = 0f
                    var lastSpread = -1f
                    var lastY = Float.NaN
                    while (true) {
                        val event = awaitPointerEvent()
                        val down = event.changes.filter { it.pressed }
                        if (down.isEmpty()) break
                        val t = System.nanoTime() / 1_000_000L
                        if (onTap != null) {
                            // A strip: no camera by hand.
                            moved += down.sumOf { (kotlin.math.abs(it.positionChange().x) + kotlin.math.abs(it.positionChange().y)).toDouble() }.toFloat()
                        } else if (down.size == 1) {
                            val d = down[0].positionChange()
                            moved += kotlin.math.abs(d.x) + kotlin.math.abs(d.y)
                            camera.drag(d.x, d.y, t)
                            lastSpread = -1f
                            lastY = Float.NaN
                        } else {
                            val a = down[0].position
                            val b = down[1].position
                            val spread = sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y))
                            val y = (a.y + b.y) / 2
                            if (lastSpread > 0f) camera.zoom(spread / lastSpread, t)
                            if (!lastY.isNaN()) camera.raise(y - lastY, t)
                            lastSpread = spread
                            lastY = y
                        }
                        down.forEach { it.consume() }
                    }
                    if (moved < 12f) {
                        // A tap on the traveller: a jump, a spin, a flip…; on the sun, five quick ones
                        // turn it into a record; elsewhere, the whole screen (and over it, the song's title;
                        // again while it shows, the rest back).
                        val dx = first.position.x - feed.travellerX
                        val dy = first.position.y - feed.travellerY
                        val reach = minOf(size.width, size.height) * (if (fullNow) 0.06f else 0.12f)
                        val sx = first.position.x - feed.sunX
                        val sy = first.position.y - feed.sunY
                        if (onTap != null) onTap()
                        else if (feed.travellerX >= 0f && dx * dx + dy * dy < reach * reach) feed.tapMove = true
                        else if (feed.sunX >= 0f && sx * sx + sy * sy < feed.sunR * feed.sunR) {
                            val t = System.currentTimeMillis()
                            for (k in 0 until sunTaps.size - 1) sunTaps[k] = sunTaps[k + 1]
                            sunTaps[sunTaps.size - 1] = t
                            if (t - sunTaps[0] < 4000) feed.recordOf = feed.songId
                        } else com.sadurni.attacca.ui.orama.OramaShared.tapLandscape(fullNow, onFullNow)
                    }
                }
            },
        )
    }
}

/** The title with the cover, the side buttons and the small player, over the whole screen (split or not). */
@Composable
private fun WorldOverlay(
    viewModel: PlayerViewModel,
    now: com.sadurni.attacca.ui.NowPlaying,
    position: Long,
    analysis: com.sadurni.attacca.analysis.TrackAnalysis?,
    clipEnd: Long,
    nextAnalysis: com.sadurni.attacca.analysis.TrackAnalysis?,
    id: String?,
    coverImage: androidx.compose.ui.graphics.ImageBitmap?,
    moodLine: String?,
    chosen: String?,
    manual: Boolean,
    camera: WorldCamera,
    fps60: Boolean,
    setFps60: (Boolean) -> Unit,
    onFull: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onOpenOrama: () -> Unit,
    onOpenQueue: () -> Unit,
    split: Boolean = false,
) {
    // Through the traveller's own eyes (a first try).
    var eyes by remember { mutableStateOf(camera.firstPerson) }
    // Split, everything smaller: more of both landscapes shows.
    val coverSize = if (split) 38.dp else 52.dp
    val titleSize = if (split) 16.sp else 20.sp
    val bySize = if (split) 12.sp else 14.sp
    val button = if (split) Modifier.size(40.dp) else Modifier
    // Split side by side (wide): the title in the middle at the top, the small player in the middle
    // at the bottom, stretched (two lines) across both halves.
    val wideSplit = split && androidx.compose.ui.platform.LocalConfiguration.current.let { it.screenWidthDp > it.screenHeightDp }
    Box(Modifier.fillMaxSize()) {
        run {
            val shadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(1f, 2f), 6f)
            val panel = com.sadurni.attacca.ui.orama.PANEL
            Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).padding(start = if (split) 10.dp else 16.dp, top = if (split) 4.dp else 8.dp), verticalAlignment = Alignment.Top) {
                // The title with the cover (a tap goes back to Now playing), as in O'rama.
                Box(Modifier.weight(1f).padding(top = 4.dp, end = 8.dp, start = if (wideSplit) 48.dp else 0.dp), contentAlignment = if (wideSplit) Alignment.TopCenter else Alignment.TopStart) {
                    if (id != null) {
                        Row(
                            Modifier.clip(RoundedCornerShape(16.dp)).background(panel).clickable(onClick = onDismiss).padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            coverImage?.let {
                                androidx.compose.foundation.Image(
                                    it, contentDescription = null,
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                    modifier = Modifier.padding(end = 10.dp).size(coverSize).clip(RoundedCornerShape(8.dp)),
                                )
                            }
                            Column(Modifier.padding(end = 4.dp)) {
                                // As in O'rama: title; artist · album; mood · family · chosen world.
                                val worldName = com.sadurni.attacca.diorama.World.entries.firstOrNull { it.name == chosen }?.let { stringResource(com.sadurni.attacca.ui.orama.worldNameRes(it)) }
                                Text(now.title, style = TextStyle(color = Color.White, fontSize = titleSize, fontWeight = FontWeight.SemiBold, shadow = shadow), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val by = listOfNotNull(now.artist?.takeIf { it.isNotBlank() }, now.album?.takeIf { it.isNotBlank() }).joinToString(" · ")
                                if (by.isNotEmpty()) Text(by, style = TextStyle(color = Color.White.copy(alpha = 0.85f), fontSize = bySize, shadow = shadow), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                listOfNotNull(moodLine, worldName).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
                                    Text(it, style = TextStyle(color = Color.White.copy(alpha = 0.72f), fontSize = if (split) 11.sp else 12.sp, shadow = shadow), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
                // One under the other, half tucked away to the right; a tap brings it out and it
                // goes back a few seconds after the last use (as in O'rama).
                var open by remember { mutableStateOf(false) }
                var usedAt by remember { mutableLongStateOf(0L) }
                LaunchedEffect(open, usedAt) {
                    if (open) {
                        delay(4000)
                        open = false
                    }
                }
                var panelWidth by remember { mutableIntStateOf(0) }
                val tucked by animateFloatAsState(if (open) 0f else 0.5f, label = "tucked")
                fun used() {
                    usedAt = System.nanoTime()
                }
                Box(Modifier.padding(top = 4.dp).offset { IntOffset((panelWidth * tucked).toInt(), 0) }.onSizeChanged { panelWidth = it.width }) {
                    Column(Modifier.padding(end = 8.dp).background(panel, RoundedCornerShape(22.dp)).padding(vertical = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(modifier = button, onClick = onDismiss) { Icon(Icons.Rounded.Close, stringResource(R.string.cover_close), tint = Color.White) }
                        IconButton(modifier = button, onClick = { used(); onFull(true) }) {
                            Icon(androidx.compose.material.icons.Icons.Rounded.Fullscreen, stringResource(R.string.orama_full_screen), tint = Color.White)
                        }
                        // The world: the song's, or each of the others in turn.
                        IconButton(modifier = button, onClick = { used(); com.sadurni.attacca.ui.orama.OramaShared.nextWorld() }) {
                            Icon(androidx.compose.material.icons.Icons.Rounded.Public, stringResource(R.string.world_choose), tint = Color.White)
                        }
                        // A world is chosen: back to each song's own (as the camera's button, only while needed).
                        if (com.sadurni.attacca.ui.orama.OramaShared.chosenWorld.value != null) {
                            IconButton(modifier = button, onClick = { used(); com.sadurni.attacca.ui.orama.OramaShared.chosenWorld.value = null }) {
                                Icon(androidx.compose.material.icons.Icons.Rounded.MusicNote, stringResource(R.string.world_song), tint = Color.White)
                            }
                        }
                        // Back to O'rama (the same song, the same world).
                        IconButton(modifier = button, onClick = { com.sadurni.attacca.ui.orama.OramaShared.split.value = false; onOpenOrama() }) {
                            Icon(androidx.compose.material.icons.Icons.Rounded.Landscape, stringResource(R.string.orama_name), tint = Color.White)
                        }
                        // O'rama and Attacca World at once, the screen split in two.
                        IconButton(modifier = button, onClick = { used(); com.sadurni.attacca.ui.orama.OramaShared.split.value = !com.sadurni.attacca.ui.orama.OramaShared.split.value }) {
                            Icon(androidx.compose.material.icons.Icons.Rounded.VerticalSplit, stringResource(R.string.world_split), tint = Color.White)
                        }
                        // Through the traveller's own eyes, or the camera behind.
                        IconButton(modifier = button, onClick = { used(); eyes = !eyes; camera.firstPerson = eyes }) {
                            Icon(if (eyes) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, stringResource(R.string.world_first_person), tint = Color.White)
                        }
                        if (manual) {
                            IconButton(modifier = button, onClick = { used(); camera.reset() }) { Icon(Icons.Rounded.Videocam, stringResource(R.string.world_camera_auto), tint = Color.White) }
                        }
                        androidx.compose.material3.TextButton(onClick = {
                            used()
                            setFps60(!fps60)
                        }) {
                            Text(stringResource(R.string.orama_fps, if (fps60) 60 else 30), color = Color.White, fontSize = 12.sp)
                        }
                    }
                    if (!open) {
                        Box(Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures { open = true; used() } })
                    }
                }
            }
            // The small player floats: across the bottom when upright; when wide, a compact block in the corner.
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                val wide = (maxWidth > maxHeight || split) && !wideSplit
                val barModifier = if (wideSplit) {
                    Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(bottom = 12.dp).fillMaxWidth(0.62f)
                } else if (split) {
                    Modifier.align(Alignment.BottomEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(end = if (maxWidth > maxHeight) 64.dp else 10.dp, bottom = 8.dp).width(236.dp)
                } else if (wide) {
                    Modifier.align(Alignment.BottomEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(end = 76.dp, bottom = 14.dp).width(264.dp)
                } else {
                    Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 20.dp, vertical = 14.dp).fillMaxWidth()
                }
                com.sadurni.attacca.ui.orama.OramaBar(viewModel, now.isPlaying, position, analysis, now.clipStartMs, clipEnd, nextAnalysis, now.nextTitle, onOpenQueue, wide, barModifier)
            }
        }
    }
}


/**
 * Attacca World in a strip (Now playing): the same land as the whole one, drawn small; a tap opens
 * it. It rests while the whole World is open.
 */
@Composable
internal fun WorldStrip(viewModel: PlayerViewModel, modifier: Modifier, onOpen: () -> Unit) {
    val feed = rememberWorldFeed(viewModel)
    val camera = remember { WorldCamera() }
    val worldOpen by com.sadurni.attacca.ui.orama.OramaShared.worldOpen
    Box(modifier.background(Color.Black)) {
        if (!worldOpen) WorldScene(feed, camera, false, false, {}, onTap = onOpen)
    }
}

/** O'rama's festive sky (and the new year's show with its greeting) laid over Attacca World. */
@Composable
private fun FestiveSky(feed: WorldFeed) {
    val on by com.sadurni.attacca.festive.FestiveState.on.collectAsStateWithLifecycle()
    val period by com.sadurni.attacca.festive.FestiveState.period.collectAsStateWithLifecycle()
    val newYear by com.sadurni.attacca.festive.FestiveState.show.collectAsStateWithLifecycle()
    val dots by produceState<FloatArray?>(null, newYear) {
        value = newYear?.let { s -> withContext(Dispatchers.Default) { com.sadurni.attacca.festive.GreetingDots.of(com.sadurni.attacca.festive.FestiveState.greeting(s)) } }
    }
    val festive = feed.festive ?: if (on) period?.festivity else null
    if (festive == null && newYear == null) return
    val layer = remember { com.sadurni.attacca.diorama.FestiveLayer() }
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) androidx.compose.runtime.withFrameMillis { frame = it } }
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
        if (frame < 0L) return@Canvas
        val celebration = newYear?.let { s ->
            com.sadurni.attacca.diorama.Celebration(s.newYear, java.time.Duration.between(s.startedAt, java.time.LocalDateTime.now()).toMillis(), dots)
        }
        if (layer.frame(size.width, size.height, feed.look ?: com.sadurni.attacca.diorama.Diorama.NEUTRAL, System.currentTimeMillis(), festive, feed.kings, celebration)) {
            drawIntoCanvas { com.sadurni.attacca.playback.OramaStill.paint(it.nativeCanvas, layer.list, null) }
        }
    }
}
