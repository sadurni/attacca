package com.sadurni.attaccaplg.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sadurni.attacca.diorama.Diorama
import com.sadurni.attacca.diorama.DrawList
import com.sadurni.attacca.diorama.Looks
import com.sadurni.attacca.diorama.SongFacts
import com.sadurni.attacca.diorama.SongLook
import com.sadurni.attacca.diorama.SongWave
import com.sadurni.attaccaplg.generate.Library
import com.sadurni.attaccaplg.player.PlayItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Attacca O'rama: the playing song as a moving landscape (see [Diorama]), with a small floating
 * player whose bar is the map of this track and the next. Over the whole screen it shows the
 * landscape alone (a click, Esc or F11 brings the rest back).
 */
@Composable
fun OramaPage(state: AppState) {
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
    val look by produceState<SongLook?>(null, item?.path, lib) {
        value = withContext(Dispatchers.IO) { lookOf(lib, item) }
    }
    // The next track's look: only its world is wanted, for the gate at the end.
    val nextLook by produceState<SongLook?>(null, nextItem?.path, lib) {
        value = withContext(Dispatchers.IO) { nextItem?.let { lookOf(lib, it) } }
    }
    val moodLine by produceState<String?>(null, item?.path, lib) {
        value = withContext(Dispatchers.IO) { moodLineOf(lib, item) }
    }
    val nextPlace = when {
        nextItem == null || p.queue.isEmpty() -> null
        p.repeat == Repeat.ONE -> "${p.index + 1}/${p.queue.size}"
        p.index + 1 < p.queue.size -> "${p.index + 2}/${p.queue.size}"
        else -> "1/${p.queue.size}"
    }
    val cover by produceState<ImageBitmap?>(null, item?.path) {
        value = withContext(Dispatchers.IO) { item?.let { runCatching { Covers.of(it.path, 160) }.getOrNull() } }
    }
    val diorama = remember { Diorama() }
    val list = remember { DrawList() }
    val path = remember { Path() }
    val raw = remember { FloatArray(3) }
    val measurer = rememberTextMeasurer()
    var fps60 by remember { mutableStateOf(state.prefs["orama_fps"] == "60") }
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(fps60) {
        var last = 0L
        while (true) {
            withFrameNanos { t ->
                if (fps60 || t - last >= 30_000_000L) {
                    frame = t
                    last = t
                }
            }
        }
    }
    val full = state.oramaFull
    val cutEnd = p.cutEndMs.takeIf { it > p.cutStartMs } ?: p.durationMs
    // Upright, the land rises over the floating player so the traveller stays in sight.
    var barTop by remember { mutableFloatStateOf(-1f) }
    var canvasHeight by remember { mutableFloatStateOf(0f) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > maxHeight
        val lift by animateFloatAsState(if (!full && !wide && barTop > 0f) (canvasHeight - barTop) * 0.85f else 0f, label = "lift")
        Canvas(
            Modifier.fillMaxSize().clipToBounds()
                // A click on something in the landscape makes it react; elsewhere, over the whole screen, it brings the rest back.
                .pointerInput(full) {
                    detectTapGestures { at ->
                        val hit = diorama.tap(list, at.x, at.y, System.nanoTime() / 1_000_000L)
                        if (!hit && full) state.oramaFull = false
                    }
                },
        ) {
            frame
            val now = System.nanoTime() / 1_000_000L
            val song = item?.let {
                Diorama.Song(
                    it.path, look ?: Diorama.NEUTRAL, it.waveform?.let { w -> SongWave(w, it.durationMs, p.cutStartMs, cutEnd) },
                    it.album.ifBlank { it.title }, it.artist,
                )
            }
            val next = nextItem?.let { n -> n.waveform?.let { SongWave(it, n.durationMs, n.soundStartMs ?: 0, n.soundEndMs ?: n.durationMs) } }
            val period = FestiveDesk.period
            diorama.festive(if (FestiveDesk.on) period?.festivity else null, period?.kings == true)
            diorama.celebrate(
                FestiveDesk.show?.let { s ->
                    com.sadurni.attacca.diorama.Celebration(
                        s.newYear, java.time.Duration.between(s.startedAt, java.time.LocalDateTime.now()).toMillis(), GreetingDots.of(FestiveDesk.greeting(s)),
                    )
                },
            )
            diorama.show(song, next, now, nextItem?.title, nextPlace, nextLook)
            val heard = p.heardLevels(raw)
            canvasHeight = size.height
            diorama.draw(list, size.width, size.height, (p.positionMs - p.cutStartMs).coerceAtLeast(0), p.playing, if (heard) raw else null, now, lift)
            paint(list, path, cover, measurer)
        }
        if (!full) {
            if (item != null) {
                val shadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(1f, 2f), 6f)
                // On a box like the small player's, so they read on any sky.
                Box(Modifier.align(Alignment.TopStart).padding(20.dp).fillMaxWidth(0.7f)) {
                // A click on it goes to Now playing.
                androidx.compose.foundation.layout.Row(
                    Modifier.clip(RoundedCornerShape(16.dp)).background(PANEL).clickable { state.page = Page.PLAYING }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The track's cover, small, beside its title.
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
                        moodLine?.let { Text(it, style = TextStyle(color = Color.White.copy(alpha = 0.72f), fontSize = 13.sp, shadow = shadow), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
                }
            }
            // One under the other: whole screen, frame rate. Half tucked away to the right so the
            // sky shows; a click brings it out, and it goes back a few seconds after the last use.
            var open by remember { mutableStateOf(false) }
            var usedAt by remember { mutableLongStateOf(0L) }
            LaunchedEffect(open, usedAt) {
                if (open) {
                    kotlinx.coroutines.delay(4000)
                    open = false
                }
            }
            var panelWidth by remember { androidx.compose.runtime.mutableIntStateOf(0) }
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
                    // Only on festive days: their look on or off.
                    FestiveDesk.period?.let { period ->
                        val name = FestiveDesk.name(period.festivity)
                        TextButton(onClick = { used(); FestiveDesk.toggle() }) {
                            Text(
                                "🎉",
                                color = if (FestiveDesk.on) Color.White else Color.White.copy(alpha = 0.4f),
                                modifier = Modifier.semantics {
                                    contentDescription = if (FestiveDesk.on) tr("Turn off the look of {0}", "Quitar el aspecto de {0}").replace("{0}", name)
                                    else tr("Turn on the look of {0}", "Poner el aspecto de {0}").replace("{0}", name)
                                },
                            )
                        }
                    }
                    TextButton(
                        onClick = {
                            used()
                            fps60 = !fps60
                            state.prefs["orama_fps"] = if (fps60) "60" else "30"
                        },
                    ) {
                        Text(if (fps60) "60 fps" else "30 fps", color = Color.White, fontSize = 12.sp)
                    }
                }
                // Tucked: a click anywhere on it only brings it out.
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
            // The small player floats: across the bottom when upright; when wide, a compact block in
            // the bottom right corner (what comes next, the time and map, the buttons).
            val barModifier = if (wide) {
                Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 20.dp).width(minOf(300.dp, maxWidth - 48.dp))
            } else {
                Modifier.align(Alignment.BottomCenter).padding(horizontal = 24.dp, vertical = 20.dp).fillMaxWidth()
            }
            OramaBar(state, item, nextItem, cutEnd, wide, barModifier.onGloballyPositioned { barTop = it.positionInParent().y })
        }
    }
}

/** Under the texts and buttons over the landscape: dark and see-through, like the small player. */
private val PANEL = Color(0xFF15151A).copy(alpha = 0.55f)

/**
 * O'rama in a strip, for Now playing in place of the waveform: the same landscape at the track's
 * pace, drawn taller than the strip and its lower part shown (the way, the traveller, some sky),
 * so they keep their size. A click on nothing in the landscape opens the O'rama page.
 */
@Composable
fun OramaStrip(state: AppState, modifier: Modifier) {
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
    val look by produceState<SongLook?>(null, item?.path, lib) {
        value = withContext(Dispatchers.IO) { lookOf(lib, item) }
    }
    val nextLook by produceState<SongLook?>(null, nextItem?.path, lib) {
        value = withContext(Dispatchers.IO) { nextItem?.let { lookOf(lib, it) } }
    }
    val cover by produceState<ImageBitmap?>(null, item?.path) {
        value = withContext(Dispatchers.IO) { item?.let { runCatching { Covers.of(it.path, 160) }.getOrNull() } }
    }
    val diorama = remember { Diorama() }
    val list = remember { DrawList() }
    val path = remember { Path() }
    val raw = remember { FloatArray(3) }
    val measurer = rememberTextMeasurer()
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { t ->
                if (t - last >= 30_000_000L) {
                    frame = t
                    last = t
                }
            }
        }
    }
    val cutEnd = p.cutEndMs.takeIf { it > p.cutStartMs } ?: p.durationMs
    var offset by remember { mutableFloatStateOf(0f) }
    Canvas(
        modifier.clipToBounds().pointerInput(Unit) {
            // A click anywhere opens the O'rama page.
            detectTapGestures { state.page = Page.ORAMA }
        },
    ) {
        frame
        val now = System.nanoTime() / 1_000_000L
        val song = item?.let {
            Diorama.Song(
                it.path, look ?: Diorama.NEUTRAL, it.waveform?.let { w -> SongWave(w, it.durationMs, p.cutStartMs, cutEnd) },
                it.album.ifBlank { it.title }, it.artist,
            )
        }
        val next = nextItem?.let { n -> n.waveform?.let { SongWave(it, n.durationMs, n.soundStartMs ?: 0, n.soundEndMs ?: n.durationMs) } }
        val period = FestiveDesk.period
        diorama.festive(if (FestiveDesk.on) period?.festivity else null, period?.kings == true)
        diorama.show(song, next, now, nextItem?.title, null, nextLook)
        val heard = p.heardLevels(raw)
        val tall = maxOf(size.height, size.width / 2.4f)
        offset = (tall - size.height) * 0.7f
        diorama.draw(list, size.width, tall, (p.positionMs - p.cutStartMs).coerceAtLeast(0), p.playing, if (heard) raw else null, now, stripTop = offset, stripHeight = size.height)
        translate(top = -offset) { paint(list, path, cover, measurer) }
    }
}

/**
 * The small player: the time, what comes next (a click opens the queue), previous, play / pause,
 * next, and the map of this track and the next to move in; [compact] stacks them in a small block.
 */
@Composable
private fun OramaBar(state: AppState, item: PlayItem?, nextItem: PlayItem?, cutEnd: Long, compact: Boolean, modifier: Modifier) {
    val p = state.player
    val curLen = (cutEnd - p.cutStartMs).coerceAtLeast(1)
    val at = (p.positionMs - p.cutStartMs).coerceIn(0, curLen)
    val white = Color.White
    val time = @Composable { Text(duration(at) + " / " + duration(curLen), fontSize = 12.sp, color = white.copy(alpha = 0.85f), maxLines = 1) }
    val upNext = @Composable { m: Modifier ->
        nextItem?.let {
            Text(
                tr("Next: ", "Siguiente: ") + it.title,
                fontSize = 12.sp, color = white.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = m.clip(RoundedCornerShape(8.dp)).clickable { state.page = Page.PLAYING }.padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
    val buttons = @Composable {
        IconButton(onClick = { p.previous() }) { Icon(PlayerIcons.SkipPrevious, tr("Previous", "Anterior")) }
        IconButton(onClick = { p.toggle() }) {
            Icon(if (p.playing) PlayerIcons.Pause else PlayerIcons.PlayArrow, if (p.playing) tr("Pause", "Pausa") else tr("Play", "Reproducir"))
        }
        IconButton(onClick = { p.next() }) { Icon(PlayerIcons.SkipNext, tr("Next", "Siguiente")) }
        item?.let { playing ->
            val favorite = p.isFavorite(playing.path)
            IconButton(onClick = { p.toggleFavorite(playing.path) }) {
                Icon(if (favorite) PlayerIcons.Favorite else PlayerIcons.FavoriteBorder, tr("Favourite", "Favorito"))
            }
        }
    }
    val accent = MaterialTheme.colorScheme.primary
    val map = @Composable { m: Modifier ->
        val rest = white.copy(alpha = 0.4f)
        val upcoming = white.copy(alpha = 0.2f)
        val nextLen = nextItem?.let { ((it.soundEndMs ?: it.durationMs) - (it.soundStartMs ?: 0)).coerceAtLeast(1) } ?: 0L
        val share = curLen.toFloat() / (curLen + nextLen)
        fun seekAt(x: Float, width: Float) {
            val f = x / width
            if (f <= share) p.seek(p.cutStartMs + (f / share * curLen).toLong().coerceIn(0, curLen))
            else if (nextItem != null) p.next()
        }
        Canvas(
            m.height(44.dp)
                .pointerInput(share, curLen, nextItem) { detectTapGestures { seekAt(it.x, size.width.toFloat()) } }
                .pointerInput(share, curLen) {
                    detectDragGestures { change, _ -> if (change.position.x / size.width <= share) seekAt(change.position.x, size.width.toFloat()) }
                },
        ) {
            val curW = size.width * share
            val played = curW * (at.toFloat() / curLen)
            mapBars(item?.waveform, item?.durationMs ?: 0, p.cutStartMs, cutEnd, 0f, curW) { x -> if (x <= played) accent else rest }
            nextItem?.let { n ->
                mapBars(n.waveform, n.durationMs, n.soundStartMs ?: 0, n.soundEndMs ?: n.durationMs, curW, size.width) { upcoming }
                drawLine(rest, Offset(curW, 0f), Offset(curW, size.height), strokeWidth = 1.dp.toPx())
            }
            drawRoundRect(white, Offset(played - 1.5.dp.toPx(), 0f), Size(3.dp.toPx(), size.height), CornerRadius(2.dp.toPx()))
        }
    }
    Surface(modifier, shape = RoundedCornerShape(22.dp), color = Color(0xFF15151A).copy(alpha = 0.72f), contentColor = white) {
        if (compact) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                upNext(Modifier.padding(start = 2.dp))
                Row(Modifier.fillMaxWidth().padding(start = 8.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    time()
                    map(Modifier.weight(1f).padding(start = 10.dp, end = 6.dp))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) { buttons() }
            }
        } else {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    time()
                    Spacer(Modifier.weight(1f))
                    upNext(Modifier.padding(start = 12.dp))
                }
                Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                    buttons()
                    map(Modifier.weight(1f).padding(start = 6.dp, end = 8.dp))
                }
            }
        }
    }
}

/** A track's waveform from [fromMs] to [toMs] as bars between [x0] and [x1]. */
private fun DrawScope.mapBars(wave: FloatArray?, fileMs: Long, fromMs: Long, toMs: Long, x0: Float, x1: Float, color: (Float) -> Color) {
    val bar = 3.dp.toPx()
    val gap = 1.dp.toPx()
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
internal fun DrawScope.paint(list: DrawList, path: Path, cover: ImageBitmap? = null, text: TextMeasurer? = null) {
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

/** The track's mood (or two) and sound family, as words: "Calm (or Melancholic) · bright, smooth". */
private fun moodLineOf(lib: Library?, item: PlayItem?): String? {
    if (lib == null || item == null) return null
    val rel = lib.root?.let { root -> runCatching { File(item.path).relativeTo(root).invariantSeparatorsPath }.getOrNull() } ?: return null
    val i = lib.indexOf(rel) ?: return null
    val k = lib.generator.classification
    val mood = k.moods.getOrNull(i)?.let(::moodName)
    val second = k.secondMoods.getOrNull(i)?.let(::moodName)
    val moods = when {
        mood == null -> null
        second != null && second != mood -> tr("$mood (or $second)", "$mood (o $second)")
        else -> mood
    }
    val family = k.families.getOrNull(i)?.let { familyLabel(lib, it) }
    return listOfNotNull(moods, family).joinToString(" · ").ifEmpty { null }
}

/** What the library knows of [item] (measures, mood, family) and its cover's colours, as a look. */
private fun lookOf(lib: Library?, item: PlayItem?): SongLook {
    if (item == null) return Diorama.NEUTRAL
    val rel = lib?.root?.let { root -> runCatching { File(item.path).relativeTo(root).invariantSeparatorsPath }.getOrNull() }
    val track = rel?.let { lib.byPath[it] }
    val i = rel?.let { lib.indexOf(it) }
    val k = lib?.generator?.classification
    val cover = runCatching {
        Covers.of(item.path, 24)?.toPixelMap()?.let { pm -> IntArray(pm.width * pm.height) { pm[it % pm.width, it / pm.width].toArgb() } }
    }.getOrNull()
    return Looks.of(
        SongFacts(
            features = track?.features,
            mood = i?.let { k?.moods?.get(it) },
            secondMood = i?.let { k?.secondMoods?.get(it) },
            energy = i?.let { k?.energy?.get(it) },
            positivity = i?.let { k?.positivity?.get(it) },
            voice = i?.let { k?.voice?.get(it) },
            family = i?.let { k?.let { c -> c.familyNames.getOrNull(c.families[it]) } },
            cover = cover,
            seed = item.path.hashCode(),
        ),
    )
}
