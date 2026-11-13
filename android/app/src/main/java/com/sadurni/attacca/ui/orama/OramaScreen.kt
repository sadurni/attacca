package com.sadurni.attacca.ui.orama

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.rounded.Celebration
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.foundation.layout.size
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.VerticalSplit
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.Surface
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.sadurni.attacca.ui.common.formatTime
import com.sadurni.attacca.ui.theme.ArtworkLoader
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.sadurni.attacca.R
import com.sadurni.attacca.analysis.TrackAnalysis
import com.sadurni.attacca.app
import com.sadurni.attacca.diorama.Diorama
import com.sadurni.attacca.diorama.DrawList
import com.sadurni.attacca.diorama.Looks
import com.sadurni.attacca.diorama.SongFacts
import com.sadurni.attacca.diorama.SongLook
import com.sadurni.attacca.diorama.SongWave
import com.sadurni.attacca.ui.PlayerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Attacca O'rama over the whole screen: the playing song as a moving landscape (see [Diorama]),
 * with a small floating player whose bar is the map of this track and the next. Upright or wide;
 * with the whole screen button, the landscape alone (a tap brings the rest back).
 */
/**
 * O'rama in a strip, for Now playing in place of the waveform: the same landscape at the track's
 * pace, as wide as the screen and low, with nothing over it. A tap anywhere opens the whole O'rama.
 */
@Composable
fun OramaStrip(viewModel: PlayerViewModel, modifier: Modifier, onOpen: () -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val now by viewModel.nowPlaying.collectAsStateWithLifecycle()
    val position by viewModel.positionMs.collectAsStateWithLifecycle()
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    val analyses by app.analysisResults.collectAsStateWithLifecycle()
    val cover by viewModel.cover.collectAsStateWithLifecycle()
    val id = now.mediaId
    val nextId = when {
        queue.isEmpty() || id == null -> null
        now.repeatMode == Player.REPEAT_MODE_ONE -> id
        now.index + 1 in queue.indices -> queue[now.index + 1].mediaId
        now.repeatMode == Player.REPEAT_MODE_ALL -> queue.first().mediaId
        else -> null
    }
    val coverBitmap = cover.bitmap.takeIf { cover.mediaId == id }
    val look by produceState<SongLook?>(null, id) {
        value = songLook(context, id)
    }
    val nextLook by produceState<SongLook?>(null, nextId) {
        value = nextId?.let { songLook(context, it) }
    }
    val coverImage by produceState<ImageBitmap?>(null, coverBitmap) {
        value = coverBitmap?.let { b -> withContext(Dispatchers.Default) { runCatching { Bitmap.createScaledBitmap(b, 160, 160, true).asImageBitmap() }.getOrNull() } }
    }
    val festiveOn by com.sadurni.attacca.festive.FestiveState.on.collectAsStateWithLifecycle()
    val festivePeriod by com.sadurni.attacca.festive.FestiveState.period.collectAsStateWithLifecycle()
    // 30 frames a second is plenty for a strip.
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { t ->
                if (t - last >= 28_000_000L) {
                    frame = t
                    last = t
                }
            }
        }
    }
    // The same landscape as the whole O'rama: switching between them goes on where it was.
    val diorama = OramaShared.diorama
    val list = remember { DrawList() }
    val path = remember { Path() }
    val raw = remember { FloatArray(3) }
    val measurer = rememberTextMeasurer()
    val analysis = id?.let { analyses[it] }
    val nextAnalysis = nextId?.let { analyses[it] }
    val clipEnd = now.clipEndMs ?: analysis?.durationMs ?: now.durationMs
    // The landscape is drawn taller than the strip (a strip's own height would make the traveller
    // and the way tiny) and its lower part shown: the way, the traveller and some sky.
    var offset by remember { mutableFloatStateOf(0f) }
    Canvas(
        modifier.clipToBounds().pointerInput(Unit) {
            // A tap anywhere opens the whole O'rama.
            detectTapGestures { onOpen() }
        },
    ) {
        frame
        val t = System.nanoTime() / 1_000_000L
        val song = id?.let {
            Diorama.Song(
                it, look ?: Diorama.NEUTRAL, analysis?.let { a -> SongWave(a.waveform, a.durationMs, now.clipStartMs, clipEnd) },
                now.album?.takeIf { a -> a.isNotBlank() } ?: now.title, now.artist,
            )
        }
        diorama.festive(if (festiveOn) festivePeriod?.festivity else null, festivePeriod?.kings == true)
        diorama.show(song, nextAnalysis?.let(::waveOf), t, now.nextTitle, null, nextLook)
        // While the whole O'rama shows, it moves the landscape on: the strip under it keeps still.
        if (OramaShared.fullOpen) return@Canvas
        val heard = app.dspEngine.heardLevels(raw)
        // At most 1.5 times the strip (as upright), so a wide, low strip in landscape shows as much
        // of the land above and below as upright does instead of a narrower band of it.
        val tall = maxOf(size.height, minOf(size.width / 2.4f, size.height * 1.5f))
        offset = (tall - size.height) * 0.7f
        diorama.draw(list, size.width, tall, position, now.isPlaying, if (heard) raw else null, t, stripTop = offset, stripHeight = size.height)
        translate(top = -offset) { paint(list, path, coverImage, measurer) }
    }
}

/** O'rama's landscape alone (for half of Attacca World's split screen). */
@Composable
internal fun OramaScene(viewModel: PlayerViewModel, full: Boolean = false, onFull: (Boolean) -> Unit = {}) {
    OramaContent(viewModel, full = full, onFull = onFull, onDismiss = {}, onOpenQueue = {}, embedded = true)
}

@Composable
fun OramaScreen(viewModel: PlayerViewModel, onDismiss: () -> Unit, onOpenWorld: (() -> Unit)? = null, onOpenQueue: () -> Unit) {
    // Back over the whole screen brings the controls back first.
    var full by rememberSaveable { mutableStateOf(false) }
    Dialog(onDismissRequest = { if (full) full = false else onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // A moving picture: the screen stays on while it shows.
        val view = LocalView.current
        DisposableEffect(view) {
            view.keepScreenOn = true
            onDispose { view.keepScreenOn = false }
        }
        // Over the whole screen the system bars hide too (a swipe from the edge shows them for a moment).
        val window = (view.parent as? DialogWindowProvider)?.window
        DisposableEffect(window, full) {
            val controller = window?.let { WindowCompat.getInsetsController(it, view) }
            if (full) {
                controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller?.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller?.show(WindowInsetsCompat.Type.systemBars())
            }
            onDispose { }
        }
        OramaContent(viewModel, full, { full = it }, onDismiss, onOpenQueue, onOpenWorld)
    }
}

@Composable
private fun OramaContent(viewModel: PlayerViewModel, full: Boolean, onFull: (Boolean) -> Unit, onDismiss: () -> Unit, onOpenQueue: () -> Unit, onOpenWorld: (() -> Unit)? = null, embedded: Boolean = false) {
    val context = LocalContext.current
    val app = context.app
    val now by viewModel.nowPlaying.collectAsStateWithLifecycle()
    val position by viewModel.positionMs.collectAsStateWithLifecycle()
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    val analyses by app.analysisResults.collectAsStateWithLifecycle()
    val cover by viewModel.cover.collectAsStateWithLifecycle()
    val id = now.mediaId
    val nextId = when {
        queue.isEmpty() || id == null -> null
        now.repeatMode == Player.REPEAT_MODE_ONE -> id
        now.index + 1 in queue.indices -> queue[now.index + 1].mediaId
        now.repeatMode == Player.REPEAT_MODE_ALL -> queue.first().mediaId
        else -> null
    }
    val coverBitmap = cover.bitmap.takeIf { cover.mediaId == id }
    val look by produceState<SongLook?>(null, id) {
        value = songLook(context, id)
    }
    // The next track's look (without its cover: only its world is wanted, for the gate at the end).
    val nextLook by produceState<SongLook?>(null, nextId) {
        value = nextId?.let { songLook(context, it) }
    }
    val moodLine by produceState<String?>(null, id) {
        value = withContext(Dispatchers.IO) { moodLineOf(context, id) }
    }
    // Where the next track is in the list, for the sign before the end.
    val nextPlace = when {
        nextId == null || now.count <= 0 -> null
        now.repeatMode == Player.REPEAT_MODE_ONE -> "${now.index + 1}/${now.count}"
        now.index + 1 < now.count -> "${now.index + 2}/${now.count}"
        else -> "1/${now.count}"
    }
    // The cover as the scene paints it (on the record, a kite, a billboard…): a small copy.
    val coverImage by produceState<ImageBitmap?>(null, coverBitmap) {
        value = coverBitmap?.let { b -> withContext(Dispatchers.Default) { runCatching { Bitmap.createScaledBitmap(b, 160, 160, true).asImageBitmap() }.getOrNull() } }
    }
    val prefs = remember { context.getSharedPreferences("orama", android.content.Context.MODE_PRIVATE) }
    var fps60 by remember { mutableStateOf(prefs.getBoolean("fps60", false)) }
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(fps60) {
        // Paced to 60 or 30 frames a second, whatever the screen's own rate.
        val step = if (fps60) 16_000_000L else 32_000_000L
        var last = 0L
        while (true) {
            withFrameNanos { t ->
                if (t - last >= step - 4_000_000L) {
                    frame = t
                    last = t
                }
            }
        }
    }
    // The same landscape as the strip in Now playing (see [OramaShared]).
    val diorama = OramaShared.diorama
    val chosenWorld by OramaShared.chosenWorld
    androidx.compose.runtime.DisposableEffect(Unit) {
        OramaShared.fullOpen = true
        onDispose { OramaShared.fullOpen = false }
    }
    // Festive days and the new year's show.
    val festiveOn by com.sadurni.attacca.festive.FestiveState.on.collectAsStateWithLifecycle()
    val festivePeriod by com.sadurni.attacca.festive.FestiveState.period.collectAsStateWithLifecycle()
    val newYear by com.sadurni.attacca.festive.FestiveState.show.collectAsStateWithLifecycle()
    val greetingDots by produceState<FloatArray?>(null, newYear) {
        value = newYear?.let { s ->
            withContext(Dispatchers.Default) { com.sadurni.attacca.festive.GreetingDots.of(com.sadurni.attacca.festive.FestiveState.greeting(s)) }
        }
    }
    val list = remember { DrawList() }
    val path = remember { Path() }
    val raw = remember { FloatArray(3) }
    val measurer = rememberTextMeasurer()
    val analysis = id?.let { analyses[it] }
    val nextAnalysis = nextId?.let { analyses[it] }
    val clipEnd = now.clipEndMs ?: analysis?.durationMs ?: now.durationMs
    // Upright, the land rises over the floating player so the traveller stays in sight.
    var barTop by remember { mutableFloatStateOf(-1f) }
    var canvasHeight by remember { mutableFloatStateOf(0f) }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val wide = maxWidth > maxHeight
        // In half of the split screen, upright, World's small player floats over this half's foot.
        val splitLift = with(androidx.compose.ui.platform.LocalDensity.current) { 104.dp.toPx() }
        // Side by side, the stretched player floats across the bottom in the middle: the land over it.
        val wideSplitLift = with(androidx.compose.ui.platform.LocalDensity.current) { 132.dp.toPx() }
        val lift by animateFloatAsState(
            when {
                embedded && !wide -> splitLift
                embedded -> wideSplitLift
                !full && !wide && barTop > 0f -> (canvasHeight - barTop) * 0.85f
                else -> 0f
            },
            label = "lift",
        )
        Canvas(
            Modifier.fillMaxSize().clipToBounds()
                // A tap on something in the landscape makes it react; elsewhere it takes the whole screen
                // (and over it, shows the song's title; again while it shows, the rest back).
                .pointerInput(full) {
                    detectTapGestures { at ->
                        val hit = diorama.tap(list, at.x, at.y, System.nanoTime() / 1_000_000L)
                        if (!hit) OramaShared.tapLandscape(full, onFull)
                    }
                },
        ) {
            frame
            val t = System.nanoTime() / 1_000_000L
            val song = id?.let {
                Diorama.Song(
                    it, OramaShared.shown(look ?: Diorama.NEUTRAL), analysis?.let { a -> SongWave(a.waveform, a.durationMs, now.clipStartMs, clipEnd) },
                    now.album?.takeIf { a -> a.isNotBlank() } ?: now.title, now.artist,
                )
            }
            diorama.festive(if (festiveOn) festivePeriod?.festivity else null, festivePeriod?.kings == true)
            diorama.celebrate(
                newYear?.let { s ->
                    com.sadurni.attacca.diorama.Celebration(
                        s.newYear, java.time.Duration.between(s.startedAt, java.time.LocalDateTime.now()).toMillis(), greetingDots,
                    )
                },
            )
            diorama.show(song, nextAnalysis?.let(::waveOf), t, now.nextTitle, nextPlace, nextLook)
            val heard = app.dspEngine.heardLevels(raw)
            canvasHeight = size.height
            // In half of the split screen the traveller a little further in, clear of the player.
            diorama.draw(list, size.width, size.height, position, now.isPlaying, if (heard) raw else null, t, lift, travellerShare = if (embedded) 0.38f else 0.3f)
            paint(list, path, coverImage, measurer)
        }
        // Shown in half of Attacca World's screen: only the landscape (the title, buttons and player are World's).
        if (!full && !embedded) {
            val shadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(1f, 2f), 6f)
            Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).padding(start = 16.dp, top = 8.dp), verticalAlignment = Alignment.Top) {
                // On a box like the small player's, so they read on any sky.
                Box(Modifier.weight(1f).padding(top = 4.dp, end = 8.dp)) {
                    if (id != null) {
                        // A tap on it goes back to Now playing.
                        Row(
                            Modifier.clip(RoundedCornerShape(16.dp)).background(PANEL).clickable(onClick = onDismiss)
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // The track's cover, small, beside its title.
                            coverImage?.let {
                                androidx.compose.foundation.Image(
                                    it, contentDescription = null,
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                    modifier = Modifier.padding(end = 10.dp).size(52.dp).clip(RoundedCornerShape(8.dp)),
                                )
                            }
                            Column(Modifier.padding(end = 4.dp)) {
                                Text(now.title, style = TextStyle(color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, shadow = shadow), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val by = listOfNotNull(now.artist?.takeIf { it.isNotBlank() }, now.album?.takeIf { it.isNotBlank() }).joinToString(" · ")
                                if (by.isNotEmpty()) Text(by, style = TextStyle(color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp, shadow = shadow), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val chosenName = com.sadurni.attacca.diorama.World.entries.firstOrNull { it.name == chosenWorld }?.let { stringResource(worldNameRes(it)) }
                                listOfNotNull(moodLine, chosenName).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
                                    Text(it, style = TextStyle(color = Color.White.copy(alpha = 0.72f), fontSize = 12.sp, shadow = shadow), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
                // One under the other, so the title has the width: close, whole screen, frame rate.
                // Half tucked away to the right so the sky shows; a tap brings it out, and it goes
                // back a few seconds after the last use.
                var open by remember { mutableStateOf(false) }
                var usedAt by remember { mutableLongStateOf(0L) }
                LaunchedEffect(open, usedAt) {
                    if (open) {
                        kotlinx.coroutines.delay(4000)
                        open = false
                    }
                }
                var panelWidth by remember { mutableIntStateOf(0) }
                val tucked by animateFloatAsState(if (open) 0f else 0.5f, label = "tucked")
                fun used() {
                    usedAt = System.nanoTime()
                }
                Box(
                    Modifier.padding(top = 4.dp)
                        .offset { IntOffset((panelWidth * tucked).toInt(), 0) }
                        .onSizeChanged { panelWidth = it.width },
                ) {
                    Column(Modifier.padding(end = 8.dp).background(PANEL, RoundedCornerShape(22.dp)).padding(vertical = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Rounded.Close, stringResource(R.string.cover_close), tint = Color.White)
                        }
                        IconButton(onClick = { used(); onFull(true) }) {
                            Icon(Icons.Rounded.Fullscreen, stringResource(R.string.orama_full_screen), tint = Color.White)
                        }
                        // Another world: the song's own, or each of the others in turn (the same in Attacca World).
                        IconButton(onClick = { used(); OramaShared.nextWorld() }) {
                            Icon(androidx.compose.material.icons.Icons.Rounded.Public, stringResource(R.string.world_choose), tint = Color.White)
                        }
                        // A world is chosen: back to each song's own (as the camera's button, only while needed).
                        if (OramaShared.chosenWorld.value != null) {
                            IconButton(onClick = { used(); OramaShared.chosenWorld.value = null }) {
                                Icon(androidx.compose.material.icons.Icons.Rounded.MusicNote, stringResource(R.string.world_song), tint = Color.White)
                            }
                        }
                        // The same song as a land to go through, in 3D.
                        onOpenWorld?.let { open3d ->
                            IconButton(onClick = { OramaShared.split.value = false; open3d() }) {
                                Icon(Icons.Rounded.ViewInAr, stringResource(R.string.world_name), tint = Color.White)
                            }
                            // O'rama and Attacca World at once, the screen split in two.
                            IconButton(onClick = { OramaShared.split.value = true; open3d() }) {
                                Icon(androidx.compose.material.icons.Icons.Rounded.VerticalSplit, stringResource(R.string.world_split), tint = Color.White)
                            }
                        }
                        // Only on festive days: their look on or off.
                        festivePeriod?.let { period ->
                            IconButton(onClick = { used(); com.sadurni.attacca.festive.FestiveState.toggle() }) {
                                Icon(
                                    androidx.compose.material.icons.Icons.Rounded.Celebration,
                                    stringResource(if (festiveOn) R.string.festive_turn_off else R.string.festive_turn_on, stringResource(com.sadurni.attacca.festive.FestiveState.nameOf(period.festivity))),
                                    tint = if (festiveOn) Color.White else Color.White.copy(alpha = 0.4f),
                                )
                            }
                        }
                        TextButton(
                            onClick = {
                                used()
                                fps60 = !fps60
                                prefs.edit().putBoolean("fps60", fps60).apply()
                            },
                        ) {
                            Text(stringResource(R.string.orama_fps, if (fps60) 60 else 30), color = Color.White, fontSize = 12.sp)
                        }
                    }
                    // Tucked: a tap anywhere on it only brings it out.
                    if (!open) {
                        Box(
                            Modifier.matchParentSize().pointerInput(Unit) {
                                detectTapGestures {
                                    open = true
                                    used()
                                }
                            },
                        )
                    }
                }
            }
            // The small player floats: across the bottom when upright; when wide, a compact block in
            // the bottom right corner, left of the side buttons (what comes next, the time and map, the buttons).
            val barModifier = if (wide) {
                Modifier.align(Alignment.BottomEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(end = 76.dp, bottom = 14.dp).width(264.dp)
            } else {
                Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 20.dp, vertical = 14.dp).fillMaxWidth()
            }
            OramaBar(viewModel, now.isPlaying, position, analysis, now.clipStartMs, clipEnd, nextAnalysis, now.nextTitle, onOpenQueue, wide, barModifier.onGloballyPositioned { barTop = it.positionInParent().y })
        }
        // Over the whole screen: a soft band along the bottom filling with the accent as the song goes
        // on (only to look at: the controls come back with a tap), and the song's name as it starts.
        if (full && !embedded) ProgressBand((position.toFloat() / (clipEnd - now.clipStartMs).coerceAtLeast(1L)).coerceIn(0f, 1f), vertical = false, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(26.dp))
        if (!embedded) SongIntro(id, now.title, now.artist, full, now.album)
    }
}

/** Under the texts and buttons over the landscape: dark and see-through, like the small player. */
internal val PANEL = Color(0xFF15151A).copy(alpha = 0.55f)

/**
 * The small player: the time, what comes next (a tap opens the list), previous, play / pause,
 * next, and the map of this track and the next to move in; [compact] stacks them in a small block.
 */
@Composable
internal fun OramaBar(
    viewModel: PlayerViewModel,
    playing: Boolean,
    position: Long,
    analysis: TrackAnalysis?,
    clipStart: Long,
    clipEnd: Long,
    next: TrackAnalysis?,
    nextTitle: String?,
    onOpenQueue: () -> Unit,
    compact: Boolean,
    modifier: Modifier,
) {
    val accent = com.sadurni.attacca.ui.theme.LocalAccent.current
    val rest = Color.White.copy(alpha = 0.4f)
    val upcoming = Color.White.copy(alpha = 0.2f)
    val curLen = (clipEnd - clipStart).coerceAtLeast(1)
    val nextLen = next?.let { (it.audioEndMs - it.audioStartMs).coerceAtLeast(1) } ?: 0L
    val share = curLen.toFloat() / (curLen + nextLen)
    val time = @Composable {
        Text(formatTime(position.coerceIn(0, curLen)) + " / " + formatTime(curLen), color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp, maxLines = 1)
    }
    val upNext = @Composable { m: Modifier ->
        if (nextTitle != null) {
            Text(
                stringResource(R.string.orama_up_next, nextTitle),
                color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = m.clip(RoundedCornerShape(8.dp)).clickable(onClick = onOpenQueue).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
    val buttons = @Composable {
        IconButton(onClick = viewModel::previous) { Icon(Icons.Rounded.SkipPrevious, stringResource(R.string.action_previous), tint = Color.White) }
        IconButton(onClick = viewModel::playPause) {
            Icon(
                if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                stringResource(if (playing) R.string.action_pause else R.string.action_play), tint = Color.White,
            )
        }
        IconButton(onClick = viewModel::next) { Icon(Icons.Rounded.SkipNext, stringResource(R.string.action_next), tint = Color.White) }
        val favorite by viewModel.isFavorite.collectAsStateWithLifecycle()
        IconButton(onClick = viewModel::toggleFavorite) {
            Icon(if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, stringResource(R.string.action_favorite), tint = Color.White)
        }
    }
    val map = @Composable { m: Modifier ->
        fun seekAt(x: Float, width: Float, tap: Boolean) {
            val f = x / width
            if (f <= share) viewModel.seekTo((f / share * curLen).toLong().coerceIn(0, curLen))
            else if (tap && next != null) viewModel.next()
        }
        Canvas(
            m.height(40.dp)
                .pointerInput(share, curLen, next) { detectTapGestures { seekAt(it.x, size.width.toFloat(), tap = true) } }
                .pointerInput(share, curLen) { detectHorizontalDragGestures { change, _ -> seekAt(change.position.x, size.width.toFloat(), tap = false) } },
        ) {
            val curW = size.width * share
            val played = curW * (position.toFloat() / curLen).coerceIn(0f, 1f)
            mapBars(analysis?.waveform, analysis?.durationMs ?: 0, clipStart, clipEnd, 0f, curW) { x -> if (x <= played) accent else rest }
            next?.let { n ->
                mapBars(n.waveform, n.durationMs, n.audioStartMs, n.audioEndMs, curW, size.width) { upcoming }
                drawLine(rest, Offset(curW, 0f), Offset(curW, size.height), strokeWidth = 1.dp.toPx())
            }
            drawRoundRect(Color.White, Offset(played - 1.5.dp.toPx(), 0f), Size(3.dp.toPx(), size.height), CornerRadius(2.dp.toPx()))
        }
    }
    Surface(modifier, shape = RoundedCornerShape(22.dp), color = Color(0xFF15151A).copy(alpha = 0.7f), contentColor = Color.White) {
        if (compact) {
            Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                upNext(Modifier.padding(start = 4.dp))
                Row(Modifier.fillMaxWidth().padding(start = 10.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    time()
                    map(Modifier.weight(1f).padding(start = 10.dp, end = 6.dp))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) { buttons() }
            }
        } else {
            Column(Modifier.padding(horizontal = 6.dp, vertical = 6.dp)) {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    time()
                    Spacer(Modifier.weight(1f))
                    upNext(Modifier.padding(start = 12.dp))
                }
                Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                    buttons()
                    map(Modifier.weight(1f).padding(start = 6.dp, end = 10.dp))
                }
            }
        }
    }
}

private fun waveOf(a: TrackAnalysis) = SongWave(a.waveform, a.durationMs, a.audioStartMs, a.audioEndMs.takeIf { it > a.audioStartMs } ?: a.durationMs)

/** A track's waveform from [fromMs] to [toMs] as bars between [x0] and [x1]. */
private fun DrawScope.mapBars(wave: FloatArray?, fileMs: Long, fromMs: Long, toMs: Long, x0: Float, x1: Float, color: (Float) -> Color) {
    val bar = 3.dp.toPx()
    val gap = 1.5.dp.toPx()
    var x = x0
    val mid = size.height / 2f
    while (x + bar <= x1) {
        val ms = fromMs + (toMs - fromMs) * ((x - x0) / (x1 - x0))
        val v = if (wave == null || wave.isEmpty() || fileMs <= 0) 0.15f
        else wave[((ms.toDouble() / fileMs) * wave.size).toInt().coerceIn(0, wave.size - 1)].coerceIn(0.04f, 1f)
        val half = mid * (0.15f + 0.85f * v)
        drawRoundRect(color(x), Offset(x, mid - half), Size(bar, half * 2f), CornerRadius(bar / 2f))
        x += bar + gap
    }
}

/** Paints a frame of the diorama through its camera; [cover] is the track's cover, when it has one, [text] for its signs. */
private fun DrawScope.paint(list: DrawList, path: Path, cover: ImageBitmap?, text: TextMeasurer?) {
    withTransform({
        translate(list.shiftX, list.shiftY)
        scale(list.zoom, list.zoom, Offset(list.pivotX, list.pivotY))
    }) { paintShapes(list, path, cover, text) }
}

private fun DrawScope.paintShapes(list: DrawList, path: Path, cover: ImageBitmap?, text: TextMeasurer?) {
    val c = list.coords
    for (i in 0 until list.size) {
        val s = list.starts[i]
        val n = list.counts[i]
        val color = Color(list.colors[i])
        when (list.kinds[i]) {
            DrawList.Kind.RECT -> drawRect(color, Offset(c[s], c[s + 1]), Size(c[s + 2] - c[s], c[s + 3] - c[s + 1]))
            DrawList.Kind.CIRCLE -> drawCircle(color, c[s + 2], Offset(c[s], c[s + 1]))
            DrawList.Kind.RING -> drawCircle(color, c[s + 2], Offset(c[s], c[s + 1]), style = Stroke(list.widths[i]))
            DrawList.Kind.GRADIENT -> drawRect(
                Brush.verticalGradient(listOf(color, Color(list.colors2[i])), startY = c[s + 1], endY = c[s + 3]),
                Offset(c[s], c[s + 1]), Size(c[s + 2] - c[s], c[s + 3] - c[s + 1]),
            )
            DrawList.Kind.POLYGON -> if (n >= 6) {
                path.reset()
                path.moveTo(c[s], c[s + 1])
                var k = 2
                while (k < n) {
                    path.lineTo(c[s + k], c[s + k + 1])
                    k += 2
                }
                path.close()
                drawPath(path, color)
            }
            DrawList.Kind.LINE -> if (n == 4) {
                drawLine(color, Offset(c[s], c[s + 1]), Offset(c[s + 2], c[s + 3]), list.widths[i], StrokeCap.Round)
            } else if (n >= 4) {
                path.reset()
                path.moveTo(c[s], c[s + 1])
                var k = 2
                while (k < n) {
                    path.lineTo(c[s + k], c[s + k + 1])
                    k += 2
                }
                drawPath(path, color, style = Stroke(list.widths[i], cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            DrawList.Kind.TEXT -> if (text != null) list.texts[i]?.let { value ->
                val layout = text.measure(value, TextStyle(color = color, fontSize = c[s + 2].toSp(), fontWeight = FontWeight.SemiBold))
                rotate(c[s + 3] * (180f / kotlin.math.PI.toFloat()), Offset(c[s], c[s + 1])) {
                    drawText(layout, topLeft = Offset(c[s] - layout.size.width / 2f, c[s + 1] - layout.size.height / 2f))
                }
            }
            DrawList.Kind.COVER -> {
                val side = c[s + 2]
                val half = side / 2f
                rotate(c[s + 3] * (180f / kotlin.math.PI.toFloat()), Offset(c[s], c[s + 1])) {
                    if (cover != null) {
                        drawImage(
                            cover, IntOffset.Zero, IntSize(cover.width, cover.height),
                            IntOffset((c[s] - half).toInt(), (c[s + 1] - half).toInt()), IntSize(side.toInt(), side.toInt()),
                            alpha = color.alpha,
                        )
                    } else {
                        drawRect(color, Offset(c[s] - half, c[s + 1] - half), Size(side, side))
                        drawCircle(Color(0x66000000), side * 0.18f, Offset(c[s], c[s + 1]))
                    }
                }
            }
        }
    }
}

private val songLooks = object : LinkedHashMap<String, SongLook>(32, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SongLook>?) = size > 24
}

/**
 * The look of track [mediaId] from what is known of it and its own small cover, the same object
 * every time (for O'rama and Attacca World alike, as the playing song or as the next one): what
 * is seen coming at a song's end is then just what the song is when it comes.
 */
internal suspend fun songLook(context: android.content.Context, mediaId: String?): SongLook {
    if (mediaId == null) return Diorama.NEUTRAL
    synchronized(songLooks) { songLooks[mediaId] }?.let { return it }
    return withContext(Dispatchers.IO) {
        val cover = runCatching { com.sadurni.attacca.ui.common.Thumbnails.load(mediaId) }.getOrNull()
        val look = lookOf(context, mediaId, cover)
        // (Kept once its sound is known: before that it may still change.)
        if (look.known) synchronized(songLooks) { songLooks[mediaId] = look }
        look
    }
}

/** The track's mood (or two) and sound family, as words: "Calm (or Melancholic) · bright, smooth". */
internal fun moodLineOf(context: android.content.Context, mediaId: String?): String? {
    val local = mediaId?.takeIf { !it.contains("://") } ?: return null
    val kept = runCatching { context.app.chainLists.keptClassification() }.getOrNull() ?: return null
    val i = kept.first[local] ?: return null
    val k = kept.second
    val mood = k.moods.getOrNull(i)?.let { context.getString(com.sadurni.attacca.ui.chain.moodRes(it)) }
    val second = k.secondMoods.getOrNull(i)?.let { context.getString(com.sadurni.attacca.ui.chain.moodRes(it)) }
    val moods = when {
        mood == null -> null
        second != null && second != mood -> context.getString(R.string.mood_between, mood, second)
        else -> mood
    }
    val family = k.families.getOrNull(i)?.let(k.familyNames::getOrNull)?.let { com.sadurni.attacca.ui.chain.familyLabel(context, it) }
    return listOfNotNull(moods, family).joinToString(" · ").ifEmpty { null }
}

/** What is known of [mediaId] (measures, mood, family) and its cover's colours, as a look. */
internal fun lookOf(context: android.content.Context, mediaId: String?, cover: Bitmap?): SongLook {
    val accent = cover?.let { runCatching { ArtworkLoader.accentFrom(it, darkBackground = true)?.toArgb() }.getOrNull() }
    if (mediaId == null) return Diorama.NEUTRAL
    val app = context.app
    val local = mediaId.takeIf { !it.contains("://") }
    val features = local?.let { runCatching { app.chainAnalyzer.db.getFeatures(listOf(File(it)))[File(it).absolutePath] }.getOrNull() }
    val kept = local?.let { runCatching { app.chainLists.keptClassification() }.getOrNull() }
    val i = kept?.first?.get(local)
    val k = kept?.second
    val pixels = cover?.let { b ->
        runCatching {
            val small = Bitmap.createScaledBitmap(b, 24, 24, true)
            IntArray(24 * 24).also { small.getPixels(it, 0, 24, 0, 0, 24, 24) }
        }.getOrNull()
    }
    val tags = local?.let { runCatching { com.sadurni.attacca.library.TrackSort.db?.getAll(listOf(it))?.get(it) }.getOrNull() }
    return Looks.of(
        SongFacts(
            features = features,
            mood = i?.let { k?.moods?.getOrNull(it) },
            secondMood = i?.let { k?.secondMoods?.getOrNull(it) },
            energy = i?.let { k?.energy?.getOrNull(it) },
            positivity = i?.let { k?.positivity?.getOrNull(it) },
            voice = i?.let { k?.voice?.getOrNull(it) },
            family = i?.let { k?.let { c -> c.families.getOrNull(it)?.let(c.familyNames::getOrNull) } },
            cover = pixels,
            accent = accent,
            seed = mediaId.hashCode(),
            genre = tags?.genre,
            title = tags?.title?.ifBlank { null } ?: local?.let { java.io.File(it).nameWithoutExtension },
        ),
    )
}

/**
 * One landscape for the strip in Now playing and the whole O'rama: the traveller, the dogs, the
 * sky and what is happening in it go on from one to the other instead of each having its own.
 */
object OramaShared {
    val diorama = Diorama()

    /** Attacca World's whole screen is open (the strip in Now playing rests meanwhile). */
    val worldOpen = androidx.compose.runtime.mutableStateOf(false)

    /** What opening O'rama shows (Settings): ORAMA, WORLD or SPLIT. */
    @Volatile
    var openMode = "ORAMA"

    /** Attacca World shows O'rama beside it, the screen split in two (kept while the app runs). */
    val split = androidx.compose.runtime.mutableStateOf(false)

    /** Times the song's title was asked for with a tap over the whole screen (see [SongIntro]). */
    val titleAsks = androidx.compose.runtime.mutableIntStateOf(0)

    /** The song's title shows over the whole screen now. */
    val titleShowing = androidx.compose.runtime.mutableStateOf(false)

    /**
     * A tap on the landscape (on nothing that reacts to it): the whole screen; over it, the song's
     * title for a moment, and a tap while it shows brings the rest back.
     */
    fun tapLandscape(full: Boolean, onFull: (Boolean) -> Unit) {
        when {
            !full -> onFull(true)
            titleShowing.value -> onFull(false)
            else -> titleAsks.intValue++
        }
    }

    /** The whole O'rama is open (and moves the landscape on). */
    @Volatile
    var fullOpen = false

    /** A world chosen by hand (its name; null: each song's own), the same in O'rama and Attacca World. */
    val chosenWorld = androidx.compose.runtime.mutableStateOf<String?>(null)

    /** The next world in turn: the song's own, then each of the others. */
    fun nextWorld() {
        val all = com.sadurni.attacca.diorama.World.entries
        val i = all.indexOfFirst { it.name == chosenWorld.value }
        chosenWorld.value = if (i + 1 < all.size) all[i + 1].name else null
    }

    private var shownFrom: SongLook? = null
    private var shownWorld: String? = null
    private var shownLook: SongLook? = null

    /**
     * [look] in the chosen world, if one is chosen: made once for each look and world (the same
     * object every frame, so the landscape changes in place, as when a track's look is known better).
     */
    fun shown(look: SongLook): SongLook {
        val name = chosenWorld.value ?: return look
        val w = com.sadurni.attacca.diorama.World.entries.firstOrNull { it.name == name } ?: return look
        if (shownFrom !== look || shownWorld != name || shownLook == null) {
            shownLook = com.sadurni.attacca.diorama.Looks.inWorld(look, w)
            shownFrom = look
            shownWorld = name
        }
        return shownLook!!
    }
}

/** A world's name, for the chooser and the title. */
internal fun worldNameRes(w: com.sadurni.attacca.diorama.World): Int = when (w) {
    com.sadurni.attacca.diorama.World.MEADOW -> R.string.world_meadow
    com.sadurni.attacca.diorama.World.COAST -> R.string.world_coast
    com.sadurni.attacca.diorama.World.CASTLE -> R.string.world_castle
    com.sadurni.attacca.diorama.World.CITY -> R.string.world_city
    com.sadurni.attacca.diorama.World.NEON -> R.string.world_neon
    com.sadurni.attacca.diorama.World.ICE -> R.string.world_ice
    com.sadurni.attacca.diorama.World.SPACE -> R.string.world_space
    com.sadurni.attacca.diorama.World.DESERT -> R.string.world_desert
    com.sadurni.attacca.diorama.World.WALL -> R.string.world_wall
    com.sadurni.attacca.diorama.World.JUNGLE -> R.string.world_jungle
    com.sadurni.attacca.diorama.World.AMAZON -> R.string.world_amazon
    com.sadurni.attacca.diorama.World.CANYON -> R.string.world_canyon
    com.sadurni.attacca.diorama.World.JAPAN -> R.string.world_japan
    com.sadurni.attacca.diorama.World.REEF -> R.string.world_reef
    com.sadurni.attacca.diorama.World.VOLCANO -> R.string.world_volcano
}

/**
 * Over the whole screen with nothing else on it ([show]): the name of the song starting, in the
 * middle, fading in and out over a few seconds; also when a tap asks for it ([OramaShared.titleAsks]).
 */
@Composable
internal fun SongIntro(id: String?, title: String, artist: String?, show: Boolean, album: String? = null) {
    val alpha = remember { androidx.compose.animation.core.Animatable(0f) }
    // The song's name when another song has come in (a moment after it, once it has settled), and
    // when the whole screen is taken; not for the song already there when the view opens.
    val first = remember { id }
    val wasShown = remember { mutableStateOf(show) }
    LaunchedEffect(id, show) {
        val entering = show && !wasShown.value
        wasShown.value = show
        alpha.snapTo(0f)
        if (id == null) return@LaunchedEffect
        if (id != first || entering) {
            if (!entering) kotlinx.coroutines.delay(1800)
            alpha.animateTo(1f, androidx.compose.animation.core.tween(900))
            kotlinx.coroutines.delay(1900)
            alpha.animateTo(0f, androidx.compose.animation.core.tween(1200))
        }
        // Over the whole screen, now and then again: softly in, a moment (2-3 s), softly out.
        while (show) {
            kotlinx.coroutines.delay(40_000L + (Math.random() * 35_000).toLong())
            alpha.animateTo(1f, androidx.compose.animation.core.tween(1600))
            kotlinx.coroutines.delay(2600)
            alpha.animateTo(0f, androidx.compose.animation.core.tween(1800))
        }
    }
    // Asked for with a tap: in quickly, a few seconds, out (on its own, so the song's own showings go on).
    val asked = remember { androidx.compose.animation.core.Animatable(0f) }
    val asks = OramaShared.titleAsks.intValue
    val seenAsks = remember { asks }
    LaunchedEffect(asks) {
        if (asks == seenAsks || !show) return@LaunchedEffect
        asked.animateTo(1f, androidx.compose.animation.core.tween(350))
        kotlinx.coroutines.delay(3500)
        asked.animateTo(0f, androidx.compose.animation.core.tween(900))
    }
    LaunchedEffect(show) { if (!show) asked.snapTo(0f) }
    val shown = maxOf(alpha.value, asked.value)
    // While it shows, a tap brings the rest back.
    val visible = show && shown > 0.01f && title.isNotBlank()
    SideEffect { OramaShared.titleShowing.value = visible }
    DisposableEffect(Unit) { onDispose { OramaShared.titleShowing.value = false } }
    if (!visible) return
    val shadow = Shadow(Color.Black.copy(alpha = 0.75f), Offset(2f, 3f), 14f)
    Box(Modifier.fillMaxSize().padding(horizontal = 28.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.graphicsLayer { this.alpha = shown }) {
            Text(
                title,
                style = TextStyle(
                    color = Color.White, fontSize = 44.sp, lineHeight = 50.sp, shadow = shadow,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Cursive, fontWeight = FontWeight.SemiBold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                ),
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            artist?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it.uppercase(),
                    style = TextStyle(
                        color = Color.White.copy(alpha = 0.88f), fontSize = 14.sp, letterSpacing = 4.sp, shadow = shadow,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    ),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp),
                )
            }
            album?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = TextStyle(
                        color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp, shadow = shadow,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    ),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}


/**
 * A soft dark band (where the halves of the split screen meet, or along the bottom), filling with
 * the accent colour as the song goes on ([progress] 0 … 1; below 0 only the band): along it, or
 * from the bottom up when [vertical]. Only to look at: it takes no touch.
 */
@Composable
internal fun ProgressBand(progress: Float, vertical: Boolean, modifier: Modifier) {
    val accent = com.sadurni.attacca.ui.theme.LocalAccent.current
    val fade = listOf(Color.Transparent, Color.Black.copy(alpha = 0.34f), Color.Black.copy(alpha = 0.44f), Color.Black.copy(alpha = 0.34f), Color.Transparent)
    val glow = listOf(Color.Transparent, accent.copy(alpha = 0.24f), accent.copy(alpha = 0.48f), accent.copy(alpha = 0.24f), Color.Transparent)
    androidx.compose.foundation.Canvas(modifier) {
        drawRect(if (vertical) androidx.compose.ui.graphics.Brush.horizontalGradient(fade) else androidx.compose.ui.graphics.Brush.verticalGradient(fade))
        if (progress < 0f) return@Canvas
        if (vertical) {
            val h = size.height * progress
            drawRect(androidx.compose.ui.graphics.Brush.horizontalGradient(glow), topLeft = Offset(0f, size.height - h), size = androidx.compose.ui.geometry.Size(size.width, h))
        } else {
            val w = size.width * progress
            drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(glow), size = androidx.compose.ui.geometry.Size(w, size.height))
        }
    }
}

/** A thin line along the bottom filling with the accent as the song goes on (the split screen's progress). */
@Composable
internal fun ProgressLine(progress: Float, modifier: Modifier) {
    val accent = com.sadurni.attacca.ui.theme.LocalAccent.current
    androidx.compose.foundation.Canvas(modifier) {
        drawRect(Color.White.copy(alpha = 0.14f))
        drawRect(accent.copy(alpha = 0.8f), size = androidx.compose.ui.geometry.Size(size.width * progress.coerceIn(0f, 1f), size.height))
    }
}
