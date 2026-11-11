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
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        DisposableEffect(view) {
            view.keepScreenOn = true
            onDispose { view.keepScreenOn = false }
        }
        // Over the whole screen the system bars hide too (a swipe from the edge shows them for a moment), as in O'rama.
        var full by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
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
    val look by produceState<com.sadurni.attacca.diorama.SongLook?>(null, id, coverBitmap) {
        value = withContext(Dispatchers.IO) { com.sadurni.attacca.ui.orama.lookOf(context, id, coverBitmap) }
    }
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
    val feed = remember { WorldFeed() }
    if (com.sadurni.attacca.BuildConfig.DEBUG) {
        // Testing: adb can set a traveller (orama prefs, "world_traveller") to see each vehicle.
        val forced = context.getSharedPreferences("orama", android.content.Context.MODE_PRIVATE).getString("world_traveller", null)
        feed.forceTraveller = com.sadurni.attacca.diorama.Traveller.entries.firstOrNull { it.name == forced }
        feed.forcePeak = "peak" in (context.getSharedPreferences("orama", android.content.Context.MODE_PRIVATE).getString("world_weather", null) ?: "")
    }
    val camera = remember { WorldCamera() }
    // A world chosen by hand (null: the song's own), kept while Attacca World is open.
    val chosen by com.sadurni.attacca.ui.orama.OramaShared.chosenWorld
    // The next song's look (without its cover: its world and traveller are what matter here).
    val nextLookRaw by produceState<com.sadurni.attacca.diorama.SongLook?>(null, nextId) {
        value = withContext(Dispatchers.IO) { nextId?.let { com.sadurni.attacca.ui.orama.lookOf(context, it, null) } }
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
    LaunchedEffect(id, look, analysis, clipEnd, chosen, nextLookRaw) {
        val wave = analysis?.let { a -> SongWave(a.waveform, a.durationMs, now.clipStartMs, clipEnd) }
        feed.wave = wave
        feed.peakMs = if (id != null && wave != null) Diorama.Song(id, look ?: Diorama.NEUTRAL, wave).peakMs else -1.0
        val chosenWorld = com.sadurni.attacca.diorama.World.entries.firstOrNull { it.name == chosen }
        feed.look = look?.let outer@{ l0 ->
            // The same look as O'rama's for this song (its own, or another after one alike).
            val l = com.sadurni.attacca.ui.orama.OramaShared.diorama.trackLookFor(id)?.takeIf { chosenWorld == null || it.world == chosenWorld }
                ?: Diorama.following(chosenWorld?.let { com.sadurni.attacca.ui.orama.OramaShared.shown(l0) } ?: l0, seq.prevLook)
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
        feed.sectioned = chosen == null
        feed.originMs = com.sadurni.attacca.ui.orama.OramaShared.diorama.originFor(id) ?: seq.origin
        feed.nextLook = nextLookRaw?.let { n -> Diorama.following(chosenWorld?.let { com.sadurni.attacca.diorama.Looks.inWorld(n, it) } ?: n, feed.look) }
        feed.songId = id
    }
    LaunchedEffect(position, now.isPlaying) {
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
    var manual by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            manual = camera.manual
            delay(300)
        }
    }

    val split by com.sadurni.attacca.ui.orama.OramaShared.split
    Box(Modifier.fillMaxSize().background(Color.Black)) {
      // Split: Attacca World above (or on the right), O'rama below (or on the left).
      androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val wideScreen = maxWidth > maxHeight
        val scene: @Composable (Modifier) -> Unit = { m -> Box(m) { WorldScene(feed, camera, fps60, full, onFull) } }
        if (!split) {
            scene(Modifier.fillMaxSize())
        } else {
            // O'rama first, World's land after it: the land's surface shows through a hole in
            // what is drawn before it, so nothing opaque may be drawn over its half afterwards.
            // Upright World above, O'rama below; wide World on the left, O'rama on the right.
            val half = if (wideScreen) Modifier.fillMaxHeight().fillMaxWidth(0.5f) else Modifier.fillMaxWidth().fillMaxHeight(0.5f)
            Box(half.align(if (wideScreen) Alignment.CenterEnd else Alignment.BottomCenter).clipToBounds()) {
                com.sadurni.attacca.ui.orama.OramaScene(viewModel)
            }
            scene(half.align(if (wideScreen) Alignment.CenterStart else Alignment.TopCenter))
            // Where the two meet: a soft shadow rather than a hard edge (see-through, so World's land still shows).
            val fade = listOf(Color.Transparent, Color.Black.copy(alpha = 0.5f), Color.Black.copy(alpha = 0.62f), Color.Black.copy(alpha = 0.5f), Color.Transparent)
            if (wideScreen) {
                Box(Modifier.fillMaxHeight().width(30.dp).align(Alignment.Center).background(androidx.compose.ui.graphics.Brush.horizontalGradient(fade)))
            } else {
                Box(Modifier.fillMaxWidth().height(30.dp).align(Alignment.Center).background(androidx.compose.ui.graphics.Brush.verticalGradient(fade)))
            }
        }
      }
        if (!full) {
            WorldOverlay(viewModel, now, position, analysis, clipEnd, nextAnalysis, id, coverImage, moodLine, chosen, manual, camera, fps60, { fps60 = it; prefs.edit().putBoolean("fps60", it).apply() }, onFull, onDismiss, onOpenOrama, onOpenQueue, split)
        }
        // Over the whole screen with nothing else on it: the song's name as it starts, fading in and out (one, in the middle, when split).
        com.sadurni.attacca.ui.orama.SongIntro(id, now.title, now.artist, full)
    }
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
private fun WorldScene(feed: WorldFeed, camera: WorldCamera, fps60: Boolean, full: Boolean, onFull: (Boolean) -> Unit) {
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
                        if (down.size == 1) {
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
                        // A tap on the traveller: a jump, a spin, a flip…; elsewhere over the whole screen, the rest back.
                        val dx = first.position.x - feed.travellerX
                        val dy = first.position.y - feed.travellerY
                        val reach = minOf(size.width, size.height) * 0.12f
                        if (feed.travellerX >= 0f && dx * dx + dy * dy < reach * reach) feed.tapMove = true
                        else if (full) onFull(false)
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
    // Split, everything smaller: more of both landscapes shows.
    val coverSize = if (split) 38.dp else 52.dp
    val titleSize = if (split) 16.sp else 20.sp
    val bySize = if (split) 12.sp else 14.sp
    val button = if (split) Modifier.size(40.dp) else Modifier
    Box(Modifier.fillMaxSize()) {
        run {
            val shadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(1f, 2f), 6f)
            val panel = com.sadurni.attacca.ui.orama.PANEL
            Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).padding(start = if (split) 10.dp else 16.dp, top = if (split) 4.dp else 8.dp), verticalAlignment = Alignment.Top) {
                // The title with the cover (a tap goes back to Now playing), as in O'rama.
                Box(Modifier.weight(1f).padding(top = 4.dp, end = 8.dp)) {
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
                                val worldName = com.sadurni.attacca.diorama.World.entries.firstOrNull { it.name == chosen }?.let { stringResource(com.sadurni.attacca.ui.orama.worldNameRes(it)) }
                                Text(
                                    stringResource(R.string.world_name) + (worldName?.let { " · $it" } ?: ""),
                                    style = TextStyle(color = Color.White.copy(alpha = 0.72f), fontSize = 11.sp, shadow = shadow),
                                )
                                Text(now.title, style = TextStyle(color = Color.White, fontSize = titleSize, fontWeight = FontWeight.SemiBold, shadow = shadow), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val by = listOfNotNull(now.artist?.takeIf { it.isNotBlank() }, now.album?.takeIf { it.isNotBlank() && !split }).joinToString(" · ")
                                if (by.isNotEmpty()) Text(by, style = TextStyle(color = Color.White.copy(alpha = 0.85f), fontSize = bySize, shadow = shadow), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (!split) moodLine?.let { Text(it, style = TextStyle(color = Color.White.copy(alpha = 0.72f), fontSize = 12.sp, shadow = shadow), maxLines = 1, overflow = TextOverflow.Ellipsis) }
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
                val wide = maxWidth > maxHeight || split
                val barModifier = if (split) {
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
