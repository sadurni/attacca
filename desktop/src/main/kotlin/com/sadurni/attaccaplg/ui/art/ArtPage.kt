package com.sadurni.attaccaplg.ui.art

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sadurni.attacca.art.ArtSong
import com.sadurni.attacca.art.ArtCover
import com.sadurni.attacca.art.ArtStyle
import com.sadurni.attacca.art.ArtSubject
import kotlin.math.roundToInt
import com.sadurni.attacca.diorama.LiveLevels
import com.sadurni.attacca.diorama.SongWave
import com.sadurni.attaccaplg.player.PlayItem
import com.sadurni.attaccaplg.ui.AppState
import com.sadurni.attaccaplg.ui.Covers
import com.sadurni.attaccaplg.ui.OramaBar
import com.sadurni.attaccaplg.ui.PANEL
import com.sadurni.attaccaplg.ui.Page
import com.sadurni.attaccaplg.ui.PlayerIcons
import com.sadurni.attaccaplg.ui.Repeat
import com.sadurni.attaccaplg.ui.lookOf
import com.sadurni.attaccaplg.ui.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What stays while Art-tacca's page is not shown: the painting under way, its thread, and what shares the screen. */
object ArtDesk {
    val canvas = ArtDeskCanvas()

    /** The painter's own thread: everything the canvas does runs on it. */
    val thread = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "Art-tacca").apply { isDaemon = true } }.asCoroutineDispatcher()

    /** What shares the screen: "ORAMA", "WORLD", "BOTH", or null (alone). */
    val partner = mutableStateOf<String?>(null)
}

/** The song a painting is of, as words. */
private class SongWords(val title: String, val artist: String?, val album: String?)

/** A finished painting hanging a moment at the bottom left, to keep if wanted. */
private class Hung(val image: org.jetbrains.skia.Image, val shown: ImageBitmap, val words: SongWords?)

private val STYLES = listOf(
    "AUTO" to { tr("By the song", "Según la canción") },
    "WATERCOLOUR" to { tr("Watercolour", "Acuarela") },
    "OIL" to { tr("Impressionist oil", "Óleo impresionista") },
    "INK" to { tr("Ink", "Tinta") },
    "PEN" to { tr("Ballpoint on a notebook", "Boli en una libreta") },
)

/**
 * Art-tacca on the desktop: the song paints a picture as it plays (in watercolour, oil, ink or
 * ballpoint, or each song its own), alone or beside O'rama and Attacca World, melting into them.
 * When a song ends its painting hangs small at the bottom left for a while, to keep in the drawer
 * (the Pictures folder's Attacca folder).
 */
@Composable
fun ArtPage(state: AppState) {
    val p = state.player
    val item = p.queue.getOrNull(p.index)
    val nextItem = when {
        p.index < 0 || p.queue.isEmpty() -> null
        p.repeat == Repeat.ONE -> item
        p.index + 1 in p.queue.indices -> p.queue[p.index + 1]
        p.repeat == Repeat.ALL -> p.queue.first()
        else -> null
    }
    val cutEnd = p.cutEndMs.takeIf { it > p.cutStartMs } ?: p.durationMs
    val cover by produceState<ImageBitmap?>(null, item?.path) {
        value = withContext(Dispatchers.IO) { item?.let { runCatching { Covers.of(it.path, 160) }.getOrNull() } }
    }
    val words = remember { HashMap<String, SongWords>() }
    if (item != null) words[item.path] = SongWords(item.title, item.artist.takeIf { it.isNotBlank() }, item.album.takeIf { it.isNotBlank() })
    // What the painter needs of the song: its key, sound, shape and cover's colours.
    val song by produceState<ArtSong?>(null, item?.path, item?.waveform, cutEnd) {
        value = item?.let { withContext(Dispatchers.IO) { artSongOf(state, it, p.cutStartMs, cutEnd) } }
    }
    var style by remember { mutableStateOf(state.prefs["art_style"] ?: "WATERCOLOUR") }
    var paintOver by remember { mutableStateOf(state.prefs["art_paint_over"] == "true") }
    var subject by remember { mutableStateOf(ArtSubject.of(state.prefs["art_subject"])) }
    var likeness by remember { mutableStateOf(state.prefs["art_likeness"]?.toFloatOrNull() ?: 1f) }
    val partner by ArtDesk.partner
    var fps60 by remember { mutableStateOf(state.prefs["orama_fps"] == "60") }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var picture by remember { mutableStateOf<ImageBitmap?>(null) }
    var hung by remember { mutableStateOf<Hung?>(null) }
    var keptNote by remember { mutableStateOf(0L) }
    var drawer by remember { mutableStateOf(false) }
    var snapshotAsked by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // The notes heard, for the colours to follow the chords (only while Art-tacca shows).
    DisposableEffect(Unit) {
        p.notesWanted = true
        onDispose { p.notesWanted = false }
    }
    // Frame after frame, painted on the painter's own thread.
    LaunchedEffect(fps60) {
        val raw = FloatArray(3)
        val notes = FloatArray(12)
        val live = LiveLevels()
        var last = 0L
        var notesAt = 0L
        var notesKnown = false
        while (true) {
            withFrameNanos { }
            val now = System.nanoTime()
            if (now - last < (if (fps60) 16_000_000L else 32_000_000L) - 4_000_000L) continue
            last = now
            val s = size
            if (s.width < 8 || s.height < 8) continue
            val nowMs = now / 1_000_000L
            live.update(if (p.heardLevels(raw)) raw else null, nowMs)
            if (nowMs - notesAt > 120) {
                notesAt = nowMs
                notesKnown = p.heardNotes(notes)
            }
            val sg = song
            val songMs = if (sg != null && sg.id == p.queue.getOrNull(p.index)?.path) (p.positionMs - p.cutStartMs).toDouble() else -1.0
            val ask = snapshotAsked
            snapshotAsked = false
            val chosen = ArtStyle.of(style)
            val over = paintOver
            val what = subject
            val close = likeness
            val result = withContext(ArtDesk.thread) {
                var finished: Pair<org.jetbrains.skia.Image, String>? = null
                val img = ArtDesk.canvas.frame(
                    sg, songMs, p.playing, live.level, live.low, live.high, live.onsetAtMs,
                    if (notesKnown) notes else null, nowMs, chosen, over, s.width, s.height, what, close,
                ) { image, id -> finished = image to id }
                Triple(img?.toComposeImageBitmap(), finished?.let { (i, id) -> Triple(i, i.toComposeImageBitmap(), id) }, if (ask) img else null)
            }
            picture = result.first
            result.second?.let { (image, shown, id) -> hung = Hung(image, shown, words[id]) }
            result.third?.let { img ->
                val w = item?.let { words[it.path] }
                keptNote = System.nanoTime()
                scope.launch(Dispatchers.IO) { ArtStoreDesk.keep(img, w?.title ?: "", w?.artist, w?.album) }
            }
        }
    }
    val full = state.oramaFull
    BoxWithConstraints(Modifier.fillMaxSize().background(WALL)) {
        val wide = maxWidth > maxHeight
        val w = maxWidth
        val h = maxHeight
        val melt = 56.dp
        fun Modifier.at(x: Dp, y: Dp, width: Dp, height: Dp) = this.offset(x, y).size(width, height)
        val paint: @Composable (Modifier, Fade) -> Unit = { m, fade ->
            Canvas(
                m.clipToBounds().fadeEdges(fade).onSizeChanged { size = it }.clickable(enabled = full, indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { state.oramaFull = false },
            ) {
                picture?.let { img ->
                    // Filling the part (the canvas takes its shape when it differs much: little is cut off).
                    val k = maxOf(this.size.width / img.width, this.size.height / img.height)
                    val dw = img.width * k
                    val dh = img.height * k
                    drawImage(
                        img,
                        dstOffset = androidx.compose.ui.unit.IntOffset(((this.size.width - dw) / 2).toInt(), ((this.size.height - dh) / 2).toInt()),
                        dstSize = IntSize(dw.toInt(), dh.toInt()),
                        filterQuality = FilterQuality.Medium,
                    )
                }
            }
        }
        val world: @Composable (Modifier) -> Unit = { m -> Box(m) { com.sadurni.attaccaplg.ui.world.WorldPage(state, embedded = true) } }
        val orama: @Composable (Modifier, Dp, Dp) -> Unit = { m, left, top -> Box(m.fadeEdges(Fade(left = left, top = top))) { com.sadurni.attaccaplg.ui.OramaPage(state, embedded = true) } }
        // Each part reaching a little into the next, fading out there: the painting fades over twice
        // the reach where it meets World (World reaching under it as far), so neither is on top.
        when {
            partner == null -> paint(Modifier.fillMaxSize(), Fade())
            partner == "BOTH" && wide -> {
                world(Modifier.at(w / 3 - melt, 0.dp, w * 2 / 3 + melt, h / 2))
                // The painting fades only where World is under it (above); below, O'rama fades into it.
                paint(Modifier.at(0.dp, 0.dp, w / 3 + melt, h), Fade(right = melt * 2, rightUntil = 0.5f))
                orama(Modifier.at(w / 3, h / 2 - melt, w * 2 / 3, h / 2 + melt), melt, melt)
            }
            partner == "BOTH" -> {
                world(Modifier.at(0.dp, h / 3 - melt, w, h / 3 + melt))
                paint(Modifier.at(0.dp, 0.dp, w, h / 3 + melt), Fade(bottom = melt * 2))
                orama(Modifier.at(0.dp, h * 2 / 3 - melt, w, h / 3 + melt), 0.dp, melt)
            }
            partner == "WORLD" && wide -> {
                world(Modifier.at(w / 2 - melt, 0.dp, w / 2 + melt, h))
                paint(Modifier.at(0.dp, 0.dp, w / 2 + melt, h), Fade(right = melt * 2))
            }
            partner == "WORLD" -> {
                world(Modifier.at(0.dp, h / 2 - melt, w, h / 2 + melt))
                paint(Modifier.at(0.dp, 0.dp, w, h / 2 + melt), Fade(bottom = melt * 2))
            }
            wide -> {
                paint(Modifier.at(0.dp, 0.dp, w / 2, h), Fade())
                orama(Modifier.at(w / 2 - melt, 0.dp, w / 2 + melt, h), melt, 0.dp)
            }
            else -> {
                paint(Modifier.at(0.dp, 0.dp, w, h / 2), Fade())
                orama(Modifier.at(0.dp, h / 2 - melt, w, h / 2 + melt), 0.dp, melt)
            }
        }
        // Over the whole screen the song's progress: the band alone, the thin line split.
        val songProgress = ((p.positionMs - p.cutStartMs).toFloat() / (cutEnd - p.cutStartMs).coerceAtLeast(1L)).coerceIn(0f, 1f)
        if (full && partner == null) com.sadurni.attaccaplg.ui.ProgressBand(songProgress, false, Modifier.fillMaxWidth().height(26.dp).align(Alignment.BottomCenter))
        if (full && partner != null) com.sadurni.attaccaplg.ui.ProgressLine(songProgress, Modifier.fillMaxWidth().height(3.dp).align(Alignment.BottomCenter))
        if (!full) {
            if (item != null) {
                val shadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(1f, 2f), 6f)
                Box(Modifier.align(Alignment.TopStart).padding(20.dp).fillMaxWidth(0.7f)) {
                    Row(
                        Modifier.clip(RoundedCornerShape(16.dp)).background(PANEL).clickable { state.page = Page.PLAYING }.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        cover?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.padding(end = 12.dp).size(60.dp).clip(RoundedCornerShape(8.dp))) }
                        Column(Modifier.padding(end = 4.dp)) {
                            Text(item.title, style = TextStyle(color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, shadow = shadow), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOf(item.artist, item.album).filter { it.isNotBlank() }.joinToString(" · "), style = TextStyle(color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp, shadow = shadow), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            // The song's mood and sound family, as in O'rama and World.
                            val moodLine by produceState<String?>(null, item.path, state.library) { value = withContext(Dispatchers.IO) { com.sadurni.attaccaplg.ui.moodLineOf(state.library, item) } }
                            moodLine?.let { Text(it, style = TextStyle(color = Color.White.copy(alpha = 0.72f), fontSize = 13.sp, shadow = shadow), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                }
            }
            Column(Modifier.align(Alignment.TopEnd).padding(top = 20.dp, end = 20.dp).background(PANEL, RoundedCornerShape(22.dp)).padding(vertical = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(onClick = { state.oramaFull = true }) { Icon(PlayerIcons.Fullscreen, tr("Whole screen (F11)", "Pantalla completa (F11)"), tint = Color.White) }
                Box {
                    var open by remember { mutableStateOf(false) }
                    IconButton(onClick = { open = true }) { Icon(PlayerIcons.Palette, tr("Art-tacca's style", "Estilo de Art-tacca"), tint = Color.White) }
                    DropdownMenu(open, onDismissRequest = { open = false }) {
                        for ((name, label) in STYLES) {
                            DropdownMenuItem(text = { Text(label()) }, onClick = { open = false; style = name; state.prefs["art_style"] = name }, leadingIcon = { RadioButton(style == name, onClick = null) })
                        }
                        // What is painted: freely, or the song's cover (a record without one), and how closely.
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(tr("Paint the album cover", "Pintar la carátula")) },
                            onClick = {
                                subject = if (subject == ArtSubject.COVER) ArtSubject.FREE else ArtSubject.COVER
                                state.prefs["art_subject"] = subject.name
                            },
                            leadingIcon = { Checkbox(subject == ArtSubject.COVER, onCheckedChange = null) },
                        )
                        if (subject == ArtSubject.COVER) {
                            Text(tr("Likeness to the cover", "Parecido a la carátula") + ": ${(likeness * 100).toInt()} %", fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, top = 4.dp))
                            Slider(
                                likeness,
                                onValueChange = { likeness = (it * 10).roundToInt() / 10f },
                                onValueChangeFinished = { state.prefs["art_likeness"] = likeness.toString() },
                                steps = 9,
                                modifier = Modifier.width(240.dp).padding(horizontal = 12.dp),
                            )
                        }
                    }
                }
                Box {
                    var open by remember { mutableStateOf(false) }
                    IconButton(onClick = { open = true }) { Icon(PlayerIcons.VerticalSplit, tr("Split the screen", "Dividir la pantalla"), tint = Color.White) }
                    DropdownMenu(open, onDismissRequest = { open = false }) {
                        val choices = listOf(
                            null to tr("Art-tacca alone", "Art-tacca solo"),
                            "ORAMA" to tr("With O'rama", "Con O'rama"),
                            "WORLD" to tr("With Attacca World", "Con Attacca World"),
                            "BOTH" to tr("With World and O'rama", "Con World y O'rama"),
                        )
                        for ((value, label) in choices) {
                            DropdownMenuItem(text = { Text(label) }, onClick = { open = false; ArtDesk.partner.value = value }, leadingIcon = { RadioButton(partner == value, onClick = null) })
                        }
                    }
                }
                IconButton(onClick = { drawer = true }) { Icon(PlayerIcons.Archive, tr("Drawer of paintings", "Cajón de cuadros"), tint = Color.White) }
                IconButton(onClick = { snapshotAsked = true }) { Icon(PlayerIcons.SaveAlt, tr("Keep this painting as it is", "Guardar el cuadro tal como va"), tint = Color.White) }
                IconButton(onClick = { paintOver = !paintOver; state.prefs["art_paint_over"] = paintOver.toString() }) {
                    Icon(PlayerIcons.Layers, if (paintOver) tr("Paint over the last painting", "Pintar encima del último cuadro") else tr("A new canvas for each song", "Un lienzo nuevo para cada canción"), tint = if (paintOver) Color.White else Color.White.copy(alpha = 0.55f))
                }
                IconButton(onClick = { state.page = Page.ORAMA }) { Icon(PlayerIcons.Landscape, "O'rama", tint = Color.White) }
                IconButton(onClick = { state.page = Page.WORLD }) { Icon(PlayerIcons.Cube, "Attacca World", tint = Color.White) }
                TextButton(onClick = { fps60 = !fps60; state.prefs["orama_fps"] = if (fps60) "60" else "30" }) {
                    Text(if (fps60) "60 fps" else "30 fps", color = Color.White, fontSize = 12.sp)
                }
            }
            val barModifier = if (wide) {
                Modifier.align(Alignment.BottomEnd).padding(end = 84.dp, bottom = 20.dp).width(minOf(300.dp, maxWidth - 108.dp))
            } else {
                Modifier.align(Alignment.BottomCenter).padding(horizontal = 24.dp, vertical = 20.dp).fillMaxWidth()
            }
            OramaBar(state, item, nextItem, cutEnd, wide, barModifier)
        }
        com.sadurni.attaccaplg.ui.SongIntro(item?.path, item?.title ?: "", item?.artist, full, item?.album)
        hung?.let { hh ->
            HungPainting(hh, Modifier.align(Alignment.BottomStart).padding(start = 24.dp, bottom = if (full) 40.dp else 140.dp), onKeep = { done ->
                scope.launch(Dispatchers.IO) {
                    val kept = ArtStoreDesk.keep(hh.image, hh.words?.title ?: "", hh.words?.artist, hh.words?.album)
                    withContext(Dispatchers.Main) { done(kept != null) }
                }
            }, onGone = { if (hung === hh) hung = null })
        }
        if (keptNote != 0L) KeptNote(keptNote, Modifier.align(Alignment.Center)) { keptNote = 0L }
        if (drawer) ArtDrawerDesk { drawer = false }
    }
}

/** What the painter knows of [item]: its key from the notes at its edges, its sound, its pace, its shape and its cover's colours. */
private fun artSongOf(state: AppState, item: PlayItem, cutStart: Long, cutEnd: Long): ArtSong {
    val lib = state.library
    val rel = lib?.root?.let { root -> runCatching { File(item.path).relativeTo(root).invariantSeparatorsPath }.getOrNull() }
    val track = rel?.let { lib.byPath[it] }
    val f = track?.features
    val look = runCatching { lookOf(lib, item) }.getOrNull()
    var (key, clarity) = ArtSong.keyOf(track?.head?.chroma, track?.tail?.chroma)
    if (key < 0 && f != null) {
        key = Math.floorMod(item.path.hashCode(), 12) + if (f.mode < 0f) 12 else 0
        clarity = f.keyClarity
    }
    val cover = runCatching {
        Covers.of(item.path, 24)?.toPixelMap()?.let { pm -> IntArray(pm.width * pm.height) { pm[it % pm.width, it / pm.width].toArgb() } }
    }.getOrNull()?.let { ArtSong.coverColours(it).takeIf { c -> c.isNotEmpty() } }
    // The cover itself, small, for painting it.
    val image = runCatching {
        Covers.of(item.path, 96)?.toPixelMap()?.let { pm -> ArtCover(pm.width, pm.height, IntArray(pm.width * pm.height) { pm[it % pm.width, it / pm.width].toArgb() }) }
    }.getOrNull()
    return ArtSong(
        id = item.path,
        key = key,
        keyClarity = clarity.coerceIn(0f, 1f),
        brightness = f?.let { ((it.brightness - 10.3f) / 1.6f).coerceIn(0f, 1f) } ?: 0.5f,
        roughness = f?.let { ((it.noisiness - 0.08f) / 0.4f).coerceIn(0f, 1f) } ?: 0.3f,
        voice = f?.let { (it.voice * 4f).coerceIn(0f, 1f) } ?: 0.5f,
        energy = look?.energy ?: 0.5f,
        bpm = f?.tempoBpm ?: look?.bpm ?: 100f,
        pulse = look?.pulse ?: f?.pulseClarity ?: 0.5f,
        wave = item.waveform?.let { SongWave(it, item.durationMs, cutStart, cutEnd) },
        cover = cover,
        image = image,
        titleImage = titleImageOf(item.title),
        duration = (cutEnd - cutStart).coerceAtLeast(0L),
    )
}

/** How far each edge of a part fades out into what is under it. */
private data class Fade(val left: Dp = 0.dp, val top: Dp = 0.dp, val right: Dp = 0.dp, val bottom: Dp = 0.dp, val rightUntil: Float = 1f)

private fun Modifier.fadeEdges(fade: Fade): Modifier =
    if (fade.left <= 0.dp && fade.top <= 0.dp && fade.right <= 0.dp && fade.bottom <= 0.dp) this
    else this.clipToBounds()
        .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val dstIn = androidx.compose.ui.graphics.BlendMode.DstIn
            val brush = androidx.compose.ui.graphics.Brush
            if (fade.top > 0.dp) drawRect(brush.verticalGradient(0f to Color.Transparent, fade.top.toPx() / size.height to Color.Black, 1f to Color.Black), blendMode = dstIn)
            if (fade.bottom > 0.dp) drawRect(brush.verticalGradient(0f to Color.Black, 1f - fade.bottom.toPx() / size.height to Color.Black, 1f to Color.Transparent), blendMode = dstIn)
            if (fade.left > 0.dp) drawRect(brush.horizontalGradient(0f to Color.Transparent, fade.left.toPx() / size.width to Color.Black, 1f to Color.Black), blendMode = dstIn)
            if (fade.right > 0.dp) drawRect(brush.horizontalGradient(0f to Color.Black, 1f - fade.right.toPx() / size.width to Color.Black, 1f to Color.Transparent), size = size.copy(height = size.height * fade.rightUntil), blendMode = dstIn)
        }

/** The painting just finished, small and framed, hanging a little crooked; its button keeps it (it drops into the drawer). */
@Composable
private fun HungPainting(h: Hung, modifier: Modifier, onKeep: (done: (Boolean) -> Unit) -> Unit, onGone: () -> Unit) {
    val accent = androidx.compose.material3.MaterialTheme.colorScheme.primary
    val shown = remember(h) { Animatable(0f) }
    val drop = remember(h) { Animatable(0f) }
    var keepState by remember(h) { mutableStateOf(0) }
    LaunchedEffect(h) {
        shown.animateTo(1f, tween(700))
        delay(14_000)
        if (keepState == 0) {
            shown.animateTo(0f, tween(900))
            onGone()
        }
    }
    LaunchedEffect(keepState) {
        if (keepState == 2) {
            drop.animateTo(1f, tween(650))
            delay(1400)
            shown.animateTo(0f, tween(500))
            onGone()
        }
    }
    val ratio = h.shown.width.toFloat() / h.shown.height.coerceAtLeast(1)
    val frameW = if (ratio >= 1f) 200.dp else 130.dp
    Column(modifier.graphicsLayer { alpha = shown.value }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.graphicsLayer {
                rotationZ = -2.5f * (1f - drop.value)
                val s = 1f - 0.8f * drop.value
                scaleX = s
                scaleY = s
                translationY = drop.value * 110.dp.toPx()
                alpha = 1f - drop.value * drop.value
                transformOrigin = TransformOrigin(0.5f, 0f)
            }.shadow(10.dp, RoundedCornerShape(2.dp)).background(Color(0xFF3A2E24), RoundedCornerShape(2.dp)).padding(4.dp).background(Color(0xFFF3EFE6)).padding(6.dp),
        ) {
            Image(h.shown, h.words?.title, contentScale = ContentScale.Fit, modifier = Modifier.width(frameW).height(frameW / ratio))
        }
        Row(
            Modifier.padding(top = 10.dp).clip(RoundedCornerShape(20.dp)).background(if (keepState == 2) accent else Color(0xE6202020))
                .border(1.dp, accent.copy(alpha = 0.7f), RoundedCornerShape(20.dp))
                .clickable(enabled = keepState == 0) { keepState = 1; onKeep { ok -> keepState = if (ok) 2 else 0 } }
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (keepState == 2) PlayerIcons.Check else PlayerIcons.Archive, null, tint = if (keepState == 2) Color.White else accent, modifier = Modifier.size(18.dp))
            Text(if (keepState == 2) tr("In the drawer", "En el cajón") else tr("Keep", "Guardar"), color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp))
        }
        h.words?.title?.takeIf { it.isNotEmpty() && keepState == 0 }?.let {
            Text(it, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp).background(Color(0xB3202020), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp))
        }
    }
}

/** "In the drawer", for a moment, after keeping the painting as it is. */
@Composable
private fun KeptNote(key: Long, modifier: Modifier, onGone: () -> Unit) {
    val accent = androidx.compose.material3.MaterialTheme.colorScheme.primary
    val a = remember(key) { Animatable(0f) }
    LaunchedEffect(key) {
        a.animateTo(1f, tween(250))
        delay(1400)
        a.animateTo(0f, tween(400))
        onGone()
    }
    Row(
        modifier.graphicsLayer { alpha = a.value }.background(Color(0xE6202020), RoundedCornerShape(24.dp)).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(28.dp).background(accent, CircleShape), contentAlignment = Alignment.Center) {
            Icon(PlayerIcons.Check, null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
        Text(tr("In the drawer", "En el cajón"), color = Color.White, fontSize = 15.sp, modifier = Modifier.padding(start = 10.dp))
    }
}

/** The drawer: the paintings kept, framed on a wall; one opens big, the others a click away. */
@Composable
private fun ArtDrawerDesk(onClose: () -> Unit) {
    val changes by ArtStoreDesk.changes.collectAsState()
    val paintings by produceState(emptyList<ArtStoreDesk.Painting>(), changes) { value = withContext(Dispatchers.IO) { ArtStoreDesk.all() } }
    var open by remember { mutableStateOf<Int?>(null) }
    var asking by remember { mutableStateOf<ArtStoreDesk.Painting?>(null) }
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize().background(WALL).clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { }) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (open != null) open = null else onClose() }) { Icon(PlayerIcons.ArrowBack, tr("Close", "Cerrar"), tint = Color.White) }
                val shown = open?.let { paintings.getOrNull(it) }
                Column(Modifier.weight(1f)) {
                    Text(shown?.title ?: tr("Art-tacca's drawer", "El cajón de Art-tacca"), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    shown?.let { Text(listOfNotNull(it.artist, it.album, dateOf(it)).joinToString(" · "), color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp) }
                }
                if (shown != null) {
                    IconButton(onClick = { open = (open!! - 1).coerceAtLeast(0) }) { Icon(PlayerIcons.SkipPrevious, null, tint = Color.White) }
                    IconButton(onClick = { open = (open!! + 1).coerceAtMost(paintings.size - 1) }) { Icon(PlayerIcons.SkipNext, null, tint = Color.White) }
                    IconButton(onClick = { asking = shown }) { Icon(PlayerIcons.Delete, tr("Take out of the drawer", "Sacar del cajón"), tint = Color.White) }
                }
                IconButton(onClick = { ArtStoreDesk.openFolder() }) { Icon(PlayerIcons.Folder, tr("Open its folder", "Abrir su carpeta"), tint = Color.White) }
            }
            val shown = open?.let { paintings.getOrNull(it) }
            if (shown != null) {
                // (Each painting comes in fitting; wheel, drag and double click look closer.)
                androidx.compose.runtime.key(shown.file.path) {
                    Zoomable { Box(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 24.dp), contentAlignment = Alignment.Center) { Framed(shown, 3000, Modifier.fillMaxSize()) } }
                }
            } else if (paintings.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.Center) {
                    Text(tr("The drawer is empty. When a song ends, its painting hangs for a moment at the bottom left: keep it from there, or keep the one being painted with the side buttons.", "El cajón está vacío. Cuando acaba una canción, su cuadro se queda colgado un momento abajo a la izquierda: guárdalo desde ahí, o guarda el que se está pintando con los botones del lado."), color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp)
                }
            } else {
                LazyVerticalGrid(GridCells.Adaptive(200.dp), contentPadding = PaddingValues(24.dp), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.fillMaxSize()) {
                    items(paintings.size) { i ->
                        val pt = paintings[i]
                        Column(Modifier.clickable { open = i }) {
                            Framed(pt, 400, Modifier.fillMaxWidth().aspectRatio(1f))
                            Text(pt.title, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                            Text(listOfNotNull(pt.artist, dateOf(pt)).joinToString(" · "), color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
    asking?.let { pt ->
        AlertDialog(
            onDismissRequest = { asking = null },
            text = { Text(tr("Take this painting out of the drawer? It will be deleted.", "¿Sacar este cuadro del cajón? Se borrará.")) },
            confirmButton = {
                TextButton(onClick = {
                    asking = null
                    if (paintings.size <= 1) open = null else open = open?.coerceAtMost(paintings.size - 2)
                    scope.launch(Dispatchers.IO) { ArtStoreDesk.remove(pt) }
                }) { Text(tr("Delete", "Borrar")) }
            },
            dismissButton = { TextButton(onClick = { asking = null }) { Text(tr("Cancel", "Cancelar")) } },
        )
    }
}

/**
 * A painting to look at closely: the mouse wheel makes it bigger or smaller round the pointer (up
 * to 6x), dragging moves it while big, a double click goes from fitting to 2.5x and back.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun Zoomable(content: @Composable () -> Unit) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    fun set(s: Float, o: androidx.compose.ui.geometry.Offset) {
        val mx = size.width * (s - 1f) / 2f
        val my = size.height * (s - 1f) / 2f
        scale = s
        offset = if (s <= 1.01f) androidx.compose.ui.geometry.Offset.Zero else androidx.compose.ui.geometry.Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }
    fun around(q: androidx.compose.ui.geometry.Offset, s: Float) {
        val c = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
        set(s, (offset - (q - c)) * (s / scale) + (q - c))
    }
    Box(
        Modifier.fillMaxSize()
            .clipToBounds()
            .onSizeChanged { size = it }
            .onPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Scroll) { e ->
                val ch = e.changes.first()
                around(ch.position, (scale * kotlin.math.exp(-ch.scrollDelta.y * 0.15f)).coerceIn(1f, 6f))
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { p -> around(p, if (scale > 1.01f) 1f else 2.5f) })
            }
            .pointerInput(Unit) {
                detectDragGestures { ch, drag ->
                    ch.consume()
                    set(scale, offset + drag)
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun Framed(p: ArtStoreDesk.Painting, maxSide: Int, modifier: Modifier) {
    val picture by produceState<ImageBitmap?>(null, p.file, maxSide) { value = withContext(Dispatchers.IO) { ArtStoreDesk.picture(p, maxSide) } }
    Box(modifier, contentAlignment = Alignment.Center) {
        picture?.let { img ->
            val ratio = img.width.toFloat() / img.height.coerceAtLeast(1)
            Box(
                Modifier.aspectRatio(ratio, matchHeightConstraintsFirst = ratio < 1f).shadow(8.dp, RoundedCornerShape(2.dp))
                    .background(Color(0xFF3A2E24), RoundedCornerShape(2.dp)).padding(3.dp).background(Color(0xFFF3EFE6)).padding(5.dp),
            ) {
                Image(img, p.title, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

private fun dateOf(p: ArtStoreDesk.Painting): String = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(p.madeAt))

private val WALL = Color(0xFF1C1B19)
