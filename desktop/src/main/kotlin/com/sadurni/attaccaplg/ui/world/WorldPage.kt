package com.sadurni.attaccaplg.ui.world

import androidx.compose.foundation.layout.height

import androidx.compose.foundation.layout.fillMaxHeight

import androidx.compose.ui.graphics.toPixelMap

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import com.sadurni.attaccaplg.ui.paint
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sadurni.attacca.diorama.Diorama
import com.sadurni.attacca.diorama.SongLook
import com.sadurni.attacca.diorama.SongWave
import com.sadurni.attacca.world.WorldCamera
import com.sadurni.attaccaplg.ui.AppState
import com.sadurni.attaccaplg.ui.Covers
import com.sadurni.attaccaplg.ui.OramaBar
import com.sadurni.attaccaplg.ui.PANEL
import com.sadurni.attaccaplg.ui.Page
import com.sadurni.attaccaplg.ui.PlayerIcons
import com.sadurni.attaccaplg.ui.Repeat
import com.sadurni.attaccaplg.ui.lookOf
import com.sadurni.attaccaplg.ui.moodLineOf
import com.sadurni.attaccaplg.ui.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Attacca World on the desktop: the song as a land to go through behind the traveller and the
 * four dogs (see [SoftWorld]). Dragging turns the camera, the wheel brings it closer, dragging
 * with the right button raises it; a click on the traveller makes a move.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun WorldPage(state: AppState) {
    val p = state.player
    val item = p.queue.getOrNull(p.index)
    val nextItem = when {
        p.index < 0 || p.queue.isEmpty() -> null
        p.repeat == Repeat.ONE -> item
        p.index + 1 in p.queue.indices -> p.queue[p.index + 1]
        p.repeat == Repeat.ALL -> p.queue.first()
        else -> null
    }
    val lib = state.library
    // The song's look, known for this very song (not the one before's while it is worked out).
    val lookFor by produceState<Pair<String?, SongLook?>>(null to null, item?.path, lib) {
        value = item?.path to withContext(Dispatchers.IO) { lookOf(lib, item) }
    }
    val look = lookFor.second.takeIf { lookFor.first == item?.path }
    val moodLine by produceState<String?>(null, item?.path, lib) {
        value = withContext(Dispatchers.IO) { moodLineOf(lib, item) }
    }
    val cover by produceState<ImageBitmap?>(null, item?.path) {
        value = withContext(Dispatchers.IO) { item?.let { runCatching { Covers.of(it.path, 160) }.getOrNull() } }
    }
    val cutEnd = p.cutEndMs.takeIf { it > p.cutStartMs } ?: p.durationMs
    val input = remember { WorldInput() }
    // Behind the traveller, or through its eyes when chosen in Settings (the eye button changes it for the moment).
    val camera = remember { WorldCamera().also { it.firstPerson = state.prefs["world_eyes"] == "true" } }
    val world = remember { SoftWorld(input, camera) }
    DisposableEffect(Unit) { onDispose { world.close() } }
    // The next song's look (its world and traveller: the land comes into its world before the end).
    val nextLookRaw by produceState<SongLook?>(null, nextItem?.path, lib) {
        value = withContext(Dispatchers.IO) { nextItem?.let { lookOf(lib, it) } }
    }
    // Song after song, as in O'rama: two alike in a row, the second in another look; and where each starts on the land's timeline.
    val seq = remember { WorldSequence() }
    if (item?.path != seq.id) {
        seq.prevLook = seq.shown
        seq.origin += seq.length
        seq.id = item?.path
    }
    seq.length = (cutEnd - p.cutStartMs).toDouble().coerceAtLeast(0.0)
    val chosen by com.sadurni.attaccaplg.ui.OramaShared.chosenWorld
    val split by com.sadurni.attaccaplg.ui.OramaShared.split
    LaunchedEffect(item?.path, look, cutEnd, item?.waveform, nextLookRaw, chosen) {
        // Until the song's own look is known the land goes on as it was (never a world in between).
        if (look == null && item != null) return@LaunchedEffect
        val wave = item?.waveform?.let { SongWave(it, item.durationMs, p.cutStartMs, cutEnd) }
        val chosenWorld = com.sadurni.attacca.diorama.World.entries.firstOrNull { it.name == chosen }
        val shown = look?.let { l ->
            // O'rama's own decision for this song (the same world in both).
            if (chosenWorld == null) com.sadurni.attaccaplg.ui.OramaShared.diorama.lookForTrack(item?.path, l)
            else com.sadurni.attaccaplg.ui.OramaShared.diorama.trackLookFor(item?.path)?.takeIf { it.world == chosenWorld }
                ?: Diorama.following(com.sadurni.attaccaplg.ui.OramaShared.shown(l), seq.prevLook)
        }
        seq.shown = shown
        // What is seen coming at the end: the next song just as it will start (its first stretch's world).
        val nextWave = nextItem?.waveform?.let { SongWave(it, nextItem.durationMs, 0L, nextItem.durationMs) }
        val next = nextLookRaw?.let { n -> Diorama.startOf(Diorama.following(chosenWorld?.let { com.sadurni.attacca.diorama.Looks.inWorld(n, it) } ?: n, shown), if (chosenWorld == null) nextWave else null) }
        val origin = com.sadurni.attaccaplg.ui.OramaShared.diorama.originFor(item?.path) ?: seq.origin
        // All of the song at once for the picture (it reads them together).
        synchronized(input) {
            input.wave = wave
            input.peakMs = if (item != null && wave != null) Diorama.Song(item.path, look ?: Diorama.NEUTRAL, wave).peakMs else -1.0
            input.look = shown
            input.sectioned = chosenWorld == null
            input.nextLook = next
            input.hasNext = nextItem != null
            input.originMs = origin
            input.songId = item?.path
        }
    }
    // The festive days' look, as O'rama has it (on or off from O'rama's own button).
    val festivePeriod = com.sadurni.attaccaplg.ui.FestiveDesk.period
    input.festive = if (com.sadurni.attaccaplg.ui.FestiveDesk.on) festivePeriod?.festivity else null
    input.kings = com.sadurni.attaccaplg.ui.FestiveDesk.on && festivePeriod?.kings == true
    input.vikram = com.sadurni.attaccaplg.ui.FestiveDesk.show?.newYear == com.sadurni.attacca.festive.NewYear.VIKRAM
    // The boards' pictures: the cover, and the sign with the next song's place and title.
    LaunchedEffect(cover) {
        input.cover = cover?.let { c -> withContext(Dispatchers.Default) { runCatching { val m = c.toPixelMap(); Picture(m.buffer.copyOf(), m.width, m.height) }.getOrNull() } }
    }
    val nextPlace = when {
        nextItem == null || p.queue.isEmpty() -> null
        p.repeat == Repeat.ONE -> "${p.index + 1}/${p.queue.size}"
        p.index + 1 < p.queue.size -> "${p.index + 2}/${p.queue.size}"
        else -> "1/${p.queue.size}"
    }
    val signText = nextItem?.let { (nextPlace?.let { pl -> "$pl  " } ?: "") + it.title }
    LaunchedEffect(signText) {
        input.sign = signText?.let { t -> withContext(Dispatchers.Default) { Boards.sign(t) } }
    }
    var fps60 by remember { mutableStateOf(state.prefs["orama_fps"] == "60") }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var picture by remember { mutableStateOf<ImageBitmap?>(null) }
    var manual by remember { mutableStateOf(false) }
    // Through the traveller's own eyes (a first try).
    var eyes by remember { mutableStateOf(camera.firstPerson) }
    // Frame after frame, drawn away from the window's thread.
    LaunchedEffect(fps60) {
        val raw = FloatArray(3)
        var last = 0L
        while (true) {
            withFrameNanos { }
            val now = System.nanoTime()
            if (now - last < (if (fps60) 16_000_000L else 32_000_000L) - 4_000_000L) continue
            last = now
            val s = size
            if (s.width < 8 || s.height < 8) continue
            input.positionFor = p.queue.getOrNull(p.index)?.path
            input.positionMs = p.positionMs
            input.positionAtNs = now
            input.playing = p.playing
            val heard = p.heardLevels(raw)
            if (heard) System.arraycopy(raw, 0, input.levels, 0, 3)
            input.heard = heard
            // At most about 720 points across: drawn by the processor, smoothed when shown bigger.
            val scale = minOf(1f, 720f / s.width)
            val w = (s.width * scale).toInt().coerceAtLeast(8)
            val h = (s.height * scale).toInt().coerceAtLeast(8)
            picture = withContext(Dispatchers.Default) {
                world.frame(w, h)
                toImage(world.pixels, w, h)
            }
            manual = camera.manual
        }
    }
    val full = state.oramaFull
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val wide = maxWidth > maxHeight
        var dragFrom by remember { mutableStateOf<Offset?>(null) }
        var moved by remember { mutableStateOf(0f) }
        val sunTaps = remember { LongArray(5) { Long.MIN_VALUE / 2 } }
        // Split: World on the left (wide) or above, O'rama on the right or below, joined by a soft shadow.
        val half = when {
            !split -> Modifier.fillMaxSize()
            wide -> Modifier.fillMaxHeight().fillMaxWidth(0.5f).align(Alignment.CenterStart)
            else -> Modifier.fillMaxWidth().fillMaxHeight(0.5f).align(Alignment.TopCenter)
        }
        Canvas(
            half.clipToBounds().onSizeChanged { size = it }
                .onPointerEvent(PointerEventType.Press) { e ->
                    dragFrom = e.changes.first().position
                    moved = 0f
                }
                .onPointerEvent(PointerEventType.Move) { e ->
                    val from = dragFrom ?: return@onPointerEvent
                    val at = e.changes.first().position
                    val d = at - from
                    moved += kotlin.math.abs(d.x) + kotlin.math.abs(d.y)
                    val t = System.nanoTime() / 1_000_000L
                    if (e.buttons.isSecondaryPressed) camera.raise(d.y, t) else camera.drag(d.x, d.y, t)
                    dragFrom = at
                }
                .onPointerEvent(PointerEventType.Release) { e ->
                    val at = e.changes.first().position
                    dragFrom = null
                    if (moved < 6f && size.width > 0) {
                        // A click on the traveller: a move; elsewhere over the whole screen, the rest back.
                        // The picture is drawn smaller than the window: its points to the window's.
                        val k = (picture?.width ?: size.width).toFloat() / size.width
                        val dx = at.x * k - input.travellerX
                        val dy = at.y * k - input.travellerY
                        val reach = minOf(size.width, size.height) * k * 0.1f
                        val sx = at.x * k - input.sunX
                        val sy = at.y * k - input.sunY
                        if (input.travellerX >= 0f && dx * dx + dy * dy < reach * reach) input.tapMove = true
                        else if (input.sunX >= 0f && sx * sx + sy * sy < input.sunR * input.sunR) {
                            // Five quick clicks on the sun: it turns into a record for the rest of the song.
                            val t = System.currentTimeMillis()
                            for (j in 0 until sunTaps.size - 1) sunTaps[j] = sunTaps[j + 1]
                            sunTaps[sunTaps.size - 1] = t
                            if (t - sunTaps[0] < 4000) input.recordOf = input.songId
                        } else if (full) state.oramaFull = false
                    }
                }
                .onPointerEvent(PointerEventType.Scroll) { e ->
                    val dy = e.changes.first().scrollDelta.y
                    camera.zoom(if (dy > 0) 0.9f else 1.1f, System.nanoTime() / 1_000_000L)
                },
        ) {
            picture?.let {
                drawImage(
                    it,
                    dstSize = androidx.compose.ui.unit.IntSize(size.width, size.height),
                    filterQuality = FilterQuality.Medium,
                )
            }
        }
        // The festive sky over it, as in O'rama.
        Box(half) { FestiveSky(input) }
        // Split: O'rama over World's picture, reaching a little into its half and fading out towards
        // it, so the two landscapes melt into each other where they meet (no band).
        if (split) {
            val melt = 56.dp
            Box(
                (if (wide) Modifier.fillMaxHeight().width(maxWidth / 2 + melt).align(Alignment.CenterEnd) else Modifier.fillMaxWidth().height(maxHeight / 2 + melt).align(Alignment.BottomCenter))
                    .clipToBounds()
                    .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        val edge = melt.toPx()
                        val fade = if (wide) {
                            androidx.compose.ui.graphics.Brush.horizontalGradient(0f to Color.Transparent, edge / size.width to Color.Black, 1f to Color.Black)
                        } else {
                            androidx.compose.ui.graphics.Brush.verticalGradient(0f to Color.Transparent, edge / size.height to Color.Black, 1f to Color.Black)
                        }
                        drawRect(fade, blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
                    },
            ) {
                com.sadurni.attaccaplg.ui.OramaPage(state, embedded = true)
            }
        }
        // Over the whole screen the song's progress: a band along the bottom filling with the accent;
        // split, a thin line in the accent colour along the very bottom.
        val songProgress = ((p.positionMs - p.cutStartMs).toFloat() / (cutEnd - p.cutStartMs).coerceAtLeast(1L)).coerceIn(0f, 1f)
        if (full && split) {
            com.sadurni.attaccaplg.ui.ProgressLine(songProgress, Modifier.fillMaxWidth().height(3.dp).align(Alignment.BottomCenter))
        } else if (full) {
            com.sadurni.attaccaplg.ui.ProgressBand(songProgress, false, Modifier.fillMaxWidth().height(26.dp).align(Alignment.BottomCenter))
        }
        if (!full) {
            if (item != null) {
                val shadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(1f, 2f), 6f)
                // Split side by side: the title in the middle at the top.
                val centred = split && wide
                Box(
                    Modifier.align(if (centred) Alignment.TopCenter else Alignment.TopStart).padding(20.dp).fillMaxWidth(0.7f),
                    contentAlignment = if (centred) Alignment.TopCenter else Alignment.TopStart,
                ) {
                    Row(
                        Modifier.clip(RoundedCornerShape(16.dp)).background(PANEL).clickable { state.page = Page.PLAYING }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        cover?.let {
                            androidx.compose.foundation.Image(
                                it, contentDescription = null,
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                modifier = Modifier.padding(end = 12.dp).size(60.dp).clip(RoundedCornerShape(8.dp)),
                            )
                        }
                        Column(Modifier.padding(end = 4.dp)) {
                            Text(item.title, style = TextStyle(color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, shadow = shadow), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOf(item.artist, item.album).filter { it.isNotBlank() }.joinToString(" · "), style = TextStyle(color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp, shadow = shadow), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            // As in O'rama: mood · family · chosen world.
                            listOfNotNull(moodLine, com.sadurni.attacca.diorama.World.entries.firstOrNull { it.name == chosen }?.let { com.sadurni.attaccaplg.ui.worldName(it) }).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
                                Text(it, style = TextStyle(color = Color.White.copy(alpha = 0.72f), fontSize = 13.sp, shadow = shadow), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            // One under the other, half tucked away to the right (as in O'rama).
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
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = 20.dp)
                    .offset { IntOffset((panelWidth * tucked).toInt(), 0) }
                    .onSizeChanged { panelWidth = it.width },
            ) {
                Column(
                    Modifier.padding(end = 20.dp).background(PANEL, RoundedCornerShape(22.dp)).padding(vertical = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    IconButton(onClick = { used(); state.oramaFull = true }) {
                        Icon(PlayerIcons.Fullscreen, tr("Whole screen (F11)", "Pantalla completa (F11)"), tint = Color.White)
                    }
                    // The world: the song's own, or each of the others in turn (the same in O'rama).
                    IconButton(onClick = { used(); com.sadurni.attaccaplg.ui.OramaShared.nextWorld() }) {
                        Icon(PlayerIcons.Public, tr("Another world (the song's own, or each of the others in turn)", "Otro mundo (el de la canción o cada uno de los demás por turno)"), tint = Color.White)
                    }
                    if (chosen != null) {
                        IconButton(onClick = { used(); com.sadurni.attaccaplg.ui.OramaShared.chosenWorld.value = null }) {
                            Icon(PlayerIcons.MusicNote, tr("Each song's own world again", "Otra vez el mundo de cada canción"), tint = Color.White)
                        }
                    }
                    // Back to O'rama (the same song).
                    IconButton(onClick = { com.sadurni.attaccaplg.ui.OramaShared.split.value = false; state.page = Page.ORAMA }) {
                        Icon(PlayerIcons.Landscape, "O'rama", tint = Color.White)
                    }
                    // O'rama and Attacca World at once.
                    IconButton(onClick = { used(); com.sadurni.attaccaplg.ui.OramaShared.split.value = !split }) {
                        Icon(PlayerIcons.VerticalSplit, tr("O'rama and Attacca World together", "O'rama y Attacca World a la vez"), tint = Color.White)
                    }
                    // Through the traveller's own eyes, or the camera behind.
                    IconButton(onClick = { used(); eyes = !eyes; camera.firstPerson = eyes }) {
                        Icon(PlayerIcons.Visibility, tr("Through the traveller's eyes", "Con los ojos del viajero"), tint = if (eyes) Color.White else Color.White.copy(alpha = 0.6f))
                    }
                    if (manual) {
                        IconButton(onClick = { used(); camera.reset() }) {
                            Icon(PlayerIcons.Videocam, tr("Camera on its own again", "La cámara, sola otra vez"), tint = Color.White)
                        }
                    }
                    TextButton(onClick = {
                        used()
                        fps60 = !fps60
                        state.prefs["orama_fps"] = if (fps60) "60" else "30"
                    }) {
                        Text(if (fps60) "60 fps" else "30 fps", color = Color.White, fontSize = 12.sp)
                    }
                }
                if (!open) {
                    Box(Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures { open = true; used() } })
                }
            }
            // Split side by side: in the middle at the bottom, stretched (two lines) across both halves.
            val barModifier = if (split && wide) {
                Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp).width(minOf(680.dp, maxWidth * 0.62f))
            } else if (split) {
                Modifier.align(Alignment.BottomEnd).padding(end = if (wide) 84.dp else 16.dp, bottom = 12.dp).width(minOf(270.dp, maxWidth - 100.dp))
            } else if (wide) {
                Modifier.align(Alignment.BottomEnd).padding(end = 84.dp, bottom = 20.dp).width(minOf(300.dp, maxWidth - 108.dp))
            } else {
                Modifier.align(Alignment.BottomCenter).padding(horizontal = 24.dp, vertical = 20.dp).fillMaxWidth()
            }
            OramaBar(state, item, nextItem, cutEnd, (wide || split) && !(split && wide), barModifier)
        }
        // Over the whole screen: the song's name as it starts (one, in the middle, when split).
        com.sadurni.attaccaplg.ui.SongIntro(item?.path, item?.title ?: "", item?.artist, full, item?.album)
    }
}

/** The picture's points (ARGB) as an image for the window. */
private fun toImage(pixels: IntArray, w: Int, h: Int): ImageBitmap {
    val bytes = ByteArray(w * h * 4)
    // Little-endian ints of ARGB are B, G, R, A bytes.
    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(pixels, 0, w * h)
    val info = org.jetbrains.skia.ImageInfo(w, h, org.jetbrains.skia.ColorType.BGRA_8888, org.jetbrains.skia.ColorAlphaType.OPAQUE)
    return org.jetbrains.skia.Image.makeRaster(info, bytes, w * 4).toComposeImageBitmap()
}

/** What World keeps from song to song: the look shown and where each song starts on the land's own timeline. */
private class WorldSequence {
    var id: String? = null
    var shown: SongLook? = null
    var prevLook: SongLook? = null
    var origin = 0.0
    var length = 0.0
}

/** O'rama's festive sky (and the new year's show with its greeting) laid over Attacca World. */
@Composable
private fun FestiveSky(input: WorldInput) {
    val desk = com.sadurni.attaccaplg.ui.FestiveDesk
    val festive = if (desk.on) desk.period?.festivity else null
    val show = desk.show
    if (festive == null && show == null) return
    val dots by produceState<FloatArray?>(null, show) {
        value = show?.let { s -> withContext(Dispatchers.Default) { com.sadurni.attaccaplg.ui.GreetingDots.of(desk.greeting(s)) } }
    }
    val layer = remember { com.sadurni.attacca.diorama.FestiveLayer() }
    val path = remember { androidx.compose.ui.graphics.Path() }
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) withFrameNanos { frame = it } }
    Canvas(Modifier.fillMaxSize()) {
        if (frame < 0L) return@Canvas
        val celebration = show?.let { s ->
            com.sadurni.attacca.diorama.Celebration(s.newYear, java.time.Duration.between(s.startedAt, java.time.LocalDateTime.now()).toMillis(), dots)
        }
        if (layer.frame(size.width, size.height, input.look ?: Diorama.NEUTRAL, System.currentTimeMillis(), festive, desk.on && desk.period?.kings == true, celebration)) {
            paint(layer.list, path)
        }
    }
}
