package com.sadurni.attacca.ui.art

import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Landscape
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.LayersClear
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.VerticalSplit
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sadurni.attacca.R
import com.sadurni.attacca.app
import com.sadurni.attacca.art.ArtSong
import com.sadurni.attacca.diorama.SongWave
import com.sadurni.attacca.ui.PlayerViewModel
import com.sadurni.attacca.ui.orama.OramaShared
import com.sadurni.attacca.ui.theme.LocalAccent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The song a painting is of, as words. */
private class SongWords(val title: String, val artist: String?, val album: String?)

/** A finished painting hanging a moment at the bottom left, to keep if wanted. */
private class Hung(val bitmap: Bitmap, val words: SongWords?, val at: Long = System.nanoTime())

/**
 * Art-tacca: the song paints a picture as it plays, a new one for each song (or each over the
 * last). When a song ends its painting hangs small at the bottom left for a while, to keep in the
 * drawer; the drawer holds the paintings kept, to look through later.
 */
@Composable
fun ArtScreen(viewModel: PlayerViewModel, onDismiss: () -> Unit, onOpenOrama: () -> Unit = {}, onOpenWorld: () -> Unit = {}, onOpenQueue: () -> Unit) {
    var full by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    Dialog(onDismissRequest = { if (full) full = false else onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        DisposableEffect(view) {
            view.keepScreenOn = true
            onDispose { view.keepScreenOn = false }
        }
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
        ArtContent(viewModel, full, { full = it }, onDismiss, onOpenOrama, onOpenWorld, onOpenQueue)
    }
}

@Composable
private fun ArtContent(
    viewModel: PlayerViewModel,
    full: Boolean,
    onFull: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onOpenOrama: () -> Unit,
    onOpenWorld: () -> Unit,
    onOpenQueue: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    val now by viewModel.nowPlaying.collectAsStateWithLifecycle()
    val position by viewModel.positionMs.collectAsStateWithLifecycle()
    val positionItem by viewModel.positionItem.collectAsStateWithLifecycle()
    val analyses by app.analysisResults.collectAsStateWithLifecycle()
    val ui by app.settings.ui.collectAsState()
    val id = now.mediaId
    val analysis = id?.let { analyses[it] }
    val clipEnd = now.clipEndMs ?: analysis?.durationMs ?: now.durationMs
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    val nextAnalysis = if (now.index + 1 in queue.indices) analyses[queue[now.index + 1].mediaId] else null
    val feed = rememberArtFeed(viewModel)
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    // The cover, small, by the title (as in O'rama and World).
    val cover by viewModel.cover.collectAsStateWithLifecycle()
    val coverBitmap = cover.bitmap.takeIf { cover.mediaId == id }
    val coverImage by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, coverBitmap) {
        value = coverBitmap?.let { b ->
            withContext(Dispatchers.Default) { runCatching { Bitmap.createScaledBitmap(b, 160, 160, true).asImageBitmap() }.getOrNull() }
        }
    }
    DisposableEffect(Unit) {
        ArtShared.open.value = true
        onDispose { ArtShared.open.value = false }
    }

    // The words of each song seen, for the paintings of songs already gone.
    val words = remember { HashMap<String, SongWords>() }
    if (id != null) words[id] = SongWords(now.title, now.artist?.takeIf { it.isNotBlank() }, now.album?.takeIf { it.isNotBlank() })


    // A finished painting hangs a while; kept into the drawer from there.
    var hung by remember { mutableStateOf<Hung?>(null) }
    DisposableEffect(feed) {
        feed.onFinished = { pic ->
            ArtStore.keepLast(context, pic.bitmap)
            main.post { hung = Hung(pic.bitmap, pic.songId?.let { words[it] }) }
        }
        onDispose { feed.onFinished = null }
    }
    var keptNote by remember { mutableStateOf(0L) }
    var drawer by remember { mutableStateOf(false) }
    val prefs = remember { context.getSharedPreferences("orama", android.content.Context.MODE_PRIVATE) }
    var fps60 by remember { mutableStateOf(prefs.getBoolean("fps60", false)) }

    val songProgress = (position.toFloat() / (clipEnd - now.clipStartMs).coerceAtLeast(1L)).coerceIn(0f, 1f)
    val partner by ArtShared.partner
    // With Attacca World beside it, the strip's World rests too.
    DisposableEffect(partner) {
        val world = partner == "WORLD" || partner == "BOTH"
        if (world) OramaShared.worldOpen.value = true
        onDispose { if (world) OramaShared.worldOpen.value = false }
    }
    Box(Modifier.fillMaxSize().background(Color(0xFF1C1B19))) {
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth > maxHeight
            val other = partner
            val w = maxWidth
            val h = maxHeight
            val melt = MELT
            // (With World the painting fades over twice the reach, half on each side of where they meet,
            // World reaching under it as far: the two even there, neither on top.)
            // Where each part goes; the parts reach a little into the next one and fade out there,
            // so they melt into each other where they meet (no band): the painting on a see-through
            // surface of its own, O'rama over everything.
            fun Modifier.at(x: androidx.compose.ui.unit.Dp, y: androidx.compose.ui.unit.Dp, width: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp) =
                this.offset(x, y).size(width, height)
            val world: @Composable (Modifier) -> Unit = { m -> Box(m) { com.sadurni.attacca.ui.world.WorldPane(viewModel, full, onFull) } }
            // (In the wide triple, O'rama's land rises only over the low line of the player under it;
            // upright, its band is short and the whole player lies over it: the land rises over that.)
            val oramaLift = when {
                other == "BOTH" && wide -> TRIPLE_BAR_ROOM
                other == "BOTH" -> TRIPLE_TALL_BAR_ROOM
                else -> null
            }
            val orama: @Composable (Modifier, androidx.compose.ui.unit.Dp, androidx.compose.ui.unit.Dp) -> Unit = { m, left, top ->
                Box(m.meltEdges(left, top)) { com.sadurni.attacca.ui.orama.OramaScene(viewModel, full, onFull, oramaLift) }
            }
            when {
                other == null -> ArtCanvas(feed, fps60, full, onFull)
                other == "BOTH" && wide -> {
                    // The painting a third on the left; World above and O'rama below in the rest.
                    world(Modifier.at(w / 3 - melt, 0.dp, w * 2 / 3 + melt, h / 2))
                    // The painting fades only where World is under it (above); below, O'rama fades into it.
                    Box(Modifier.at(0.dp, 0.dp, w / 3 + melt, h)) { ArtCanvas(feed, fps60, full, onFull, fade = Fade(right = melt * 2, rightUntil = 0.5f)) }
                    orama(Modifier.at(w / 3, h / 2 - melt, w * 2 / 3, h / 2 + melt), melt, melt)
                }
                other == "BOTH" -> {
                    // Three bands: the painting, World, O'rama.
                    world(Modifier.at(0.dp, h / 3 - melt, w, h / 3 + melt))
                    Box(Modifier.at(0.dp, 0.dp, w, h / 3 + melt)) { ArtCanvas(feed, fps60, full, onFull, fade = Fade(bottom = melt * 2)) }
                    orama(Modifier.at(0.dp, h * 2 / 3 - melt, w, h / 3 + melt), 0.dp, melt)
                }
                other == "WORLD" && wide -> {
                    world(Modifier.at(w / 2 - melt, 0.dp, w / 2 + melt, h))
                    Box(Modifier.at(0.dp, 0.dp, w / 2 + melt, h)) { ArtCanvas(feed, fps60, full, onFull, fade = Fade(right = melt * 2)) }
                }
                other == "WORLD" -> {
                    world(Modifier.at(0.dp, h / 2 - melt, w, h / 2 + melt))
                    Box(Modifier.at(0.dp, 0.dp, w, h / 2 + melt)) { ArtCanvas(feed, fps60, full, onFull, fade = Fade(bottom = melt * 2)) }
                }
                wide -> {
                    Box(Modifier.at(0.dp, 0.dp, w / 2, h)) { ArtCanvas(feed, fps60, full, onFull) }
                    orama(Modifier.at(w / 2 - melt, 0.dp, w / 2 + melt, h), melt, 0.dp)
                }
                else -> {
                    Box(Modifier.at(0.dp, 0.dp, w, h / 2)) { ArtCanvas(feed, fps60, full, onFull) }
                    orama(Modifier.at(0.dp, h / 2 - melt, w, h / 2 + melt), 0.dp, melt)
                }
            }
        }
        if (!full) {
            ArtOverlay(
                viewModel, now, position, analysis, clipEnd, nextAnalysis, id,
                coverImage = coverImage,
                paintOver = ui.artPaintOver,
                onPaintOver = { on -> app.settings.updateUi { it.copy(artPaintOver = on) } },
                style = ui.artStyle,
                onStyle = { s -> app.settings.updateUi { it.copy(artStyle = s) } },
                paintsCover = ui.artSubject == "COVER",
                onPaintsCover = { on -> app.settings.updateUi { it.copy(artSubject = if (on) "COVER" else "FREE") } },
                fps60 = fps60,
                onFps = { on ->
                    fps60 = on
                    prefs.edit().putBoolean("fps60", on).apply()
                    // (O'rama and World beside it follow at once.)
                    OramaShared.fpsChanged.intValue++
                },
                partner = partner,
                onPartner = { ArtShared.partner.value = it },
                onKeepNow = {
                    val w = id?.let { words[it] }
                    // Said at once; the picture is taken on the next frame and written meanwhile.
                    keptNote = System.nanoTime()
                    feed.snapshot = { pic ->
                        scope.launch(Dispatchers.IO) {
                            ArtStore.keep(context, pic.bitmap, w?.title ?: "", w?.artist, w?.album)
                            pic.bitmap.recycle()
                        }
                    }
                },
                onDrawer = { drawer = true },
                onFull = onFull,
                onDismiss = onDismiss,
                onOpenOrama = onOpenOrama,
                onOpenWorld = onOpenWorld,
                onOpenQueue = onOpenQueue,
            )
        }
        if (full && partner == null) com.sadurni.attacca.ui.orama.ProgressBand(songProgress, vertical = false, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(26.dp))
        if (full && partner != null) com.sadurni.attacca.ui.orama.ProgressLine(songProgress, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp))
        com.sadurni.attacca.ui.orama.SongIntro(id, now.title, now.artist, full, now.album)

        hung?.let { h ->
            HungPainting(
                h,
                Modifier.align(Alignment.BottomStart).windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(start = 16.dp, bottom = if (full) 36.dp else 132.dp),
                onKeep = { done ->
                    scope.launch(Dispatchers.IO) {
                        val kept = ArtStore.keep(context, h.bitmap, h.words?.title ?: "", h.words?.artist, h.words?.album)
                        withContext(Dispatchers.Main) { done(kept != null) }
                    }
                },
                onGone = { if (hung === h) hung = null },
            )
        }
        if (keptNote != 0L) KeptNote(keptNote, Modifier.align(Alignment.Center)) { keptNote = 0L }
    }
    if (drawer) ArtDrawer { drawer = false }
}

/** What Art-tacca paints from (the song, where it is, what is heard, how to paint), kept up to date. */
@Composable
internal fun rememberArtFeed(viewModel: PlayerViewModel): ArtFeed {
    val context = LocalContext.current
    val app = context.app
    val now by viewModel.nowPlaying.collectAsStateWithLifecycle()
    val position by viewModel.positionMs.collectAsStateWithLifecycle()
    val positionItem by viewModel.positionItem.collectAsStateWithLifecycle()
    val analyses by app.analysisResults.collectAsStateWithLifecycle()
    val ui by app.settings.ui.collectAsState()
    val id = now.mediaId
    val analysis = id?.let { analyses[it] }
    val clipEnd = now.clipEndMs ?: analysis?.durationMs ?: now.durationMs
    val feed = remember { ArtFeed() }
    // What the painter needs of the song: its key, sound and shape (once its analysis is known, or
    // after a moment without it).
    LaunchedEffect(id, analysis != null, clipEnd) {
        if (id == null) return@LaunchedEffect
        if (analysis == null) delay(1500)
        val song = withContext(Dispatchers.IO) { artSongOf(context, id, analysis, now.clipStartMs, clipEnd, now.title) }
        feed.song = song
    }
    LaunchedEffect(position, now.isPlaying, positionItem) {
        feed.positionFor = positionItem
        feed.positionMs = position
        feed.positionAtNs = System.nanoTime()
        feed.playing = now.isPlaying
    }
    LaunchedEffect(Unit) {
        val raw = FloatArray(3)
        while (true) {
            val heard = app.dspEngine.heardLevels(raw)
            if (heard) System.arraycopy(raw, 0, feed.levels, 0, 3)
            feed.heard = heard
            delay(16)
        }
    }
    androidx.compose.runtime.SideEffect {
        feed.paintOver = ui.artPaintOver
        feed.style = com.sadurni.attacca.art.ArtStyle.of(ui.artStyle)
        feed.subject = com.sadurni.attacca.art.ArtSubject.of(ui.artSubject)
        feed.likeness = ui.artLikeness
    }
    // The notes heard, for the colours to follow the chords (listened to only while Art-tacca shows).
    DisposableEffect(Unit) {
        ArtShared.listeners++
        app.dspEngine.notesWanted = true
        onDispose {
            ArtShared.listeners--
            if (ArtShared.listeners <= 0) {
                app.dspEngine.notesWanted = false
                app.dspEngine.notes.clear()
            }
        }
    }
    LaunchedEffect(Unit) {
        val heard = FloatArray(12)
        while (true) {
            val known = withContext(Dispatchers.Default) { app.dspEngine.heardNotes(heard) }
            if (known) System.arraycopy(heard, 0, feed.notes, 0, 12)
            feed.notesKnown = known
            delay(120)
        }
    }
    return feed
}

/**
 * Art-tacca in Now playing's strip: the painting under way, made at the screen's size and shown
 * filling the strip; a tap opens the whole Art-tacca. It rests while the whole one is open.
 */
@Composable
internal fun ArtStrip(viewModel: PlayerViewModel, modifier: Modifier, onOpen: () -> Unit) {
    val open by ArtShared.open
    val metrics = LocalContext.current.resources.displayMetrics
    Box(modifier.background(Color(0xFF1C1B19))) {
        if (!open) {
            val feed = rememberArtFeed(viewModel)
            androidx.compose.runtime.SideEffect {
                feed.canvasHintW = metrics.widthPixels
                feed.canvasHintH = metrics.heightPixels
            }
            ArtCanvas(feed, fps60 = false, full = false, onFull = {}, onTap = onOpen)
        }
    }
}

/** What the painter knows of [id]: its key from the notes at its edges, its sound, its pace and its shape. */
internal suspend fun artSongOf(context: android.content.Context, id: String, analysis: com.sadurni.attacca.analysis.TrackAnalysis?, clipStart: Long, clipEnd: Long, title: String? = null): ArtSong {
    val app = context.app
    val look = runCatching { com.sadurni.attacca.ui.orama.songLook(context, id) }.getOrNull()
    val local = id.takeIf { !it.contains("://") }
    val f = local?.let { runCatching { app.chainAnalyzer.db.getFeatures(listOf(File(it)))[File(it).absolutePath] }.getOrNull() }
    val edges = local?.let { app.chainAnalyzer.knownEdges(it) }
    var (key, clarity) = ArtSong.keyOf(edges?.head?.chroma, edges?.tail?.chroma)
    if (key < 0 && f != null) {
        key = Math.floorMod(id.hashCode(), 12) + if (f.mode < 0f) 12 else 0
        clarity = f.keyClarity
    }
    return ArtSong(
        id = id,
        key = key,
        keyClarity = clarity.coerceIn(0f, 1f),
        brightness = f?.let { ((it.brightness - 10.3f) / 1.6f).coerceIn(0f, 1f) } ?: 0.5f,
        roughness = f?.let { ((it.noisiness - 0.08f) / 0.4f).coerceIn(0f, 1f) } ?: 0.3f,
        voice = f?.let { (it.voice * 4f).coerceIn(0f, 1f) } ?: 0.5f,
        energy = look?.energy ?: 0.5f,
        bpm = f?.tempoBpm ?: look?.bpm ?: 100f,
        pulse = look?.pulse ?: f?.pulseClarity ?: 0.5f,
        wave = analysis?.let { SongWave(it.waveform, it.durationMs, clipStart, clipEnd) },
        cover = coverColoursOf(id),
        image = coverImageOf(id),
        titleImage = titleImageOf(title),
        duration = (clipEnd - clipStart).coerceAtLeast(0L),
    )
}

/** The painting's signature: [title] written small in an italic hand, "Art-tacca" smaller under it (the pixels' alpha is the ink). */
private fun titleImageOf(title: String?): com.sadurni.attacca.art.ArtCover? = runCatching {
    val t = title?.trim()?.takeIf { it.isNotEmpty() }?.let { if (it.length > 48) it.take(47).trimEnd() + "…" else it } ?: return null
    val italic = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.ITALIC)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 22f; typeface = italic; color = android.graphics.Color.WHITE }
    val small = android.graphics.Paint(paint).apply { textSize = 17f }
    val by = "Art-tacca"
    val fm = paint.fontMetrics
    val sm = small.fontMetrics
    val w = kotlin.math.ceil(maxOf(paint.measureText(t), small.measureText(by))).toInt() + 4
    val line = kotlin.math.ceil(fm.descent - fm.ascent).toInt()
    val h = line + kotlin.math.ceil(sm.descent - sm.ascent).toInt() + 2
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    // Both lines end at the right, by the seal.
    canvas.drawText(t, w - 2f - paint.measureText(t), -fm.ascent + 1f, paint)
    canvas.drawText(by, w - 2f - small.measureText(by), line - sm.ascent, small)
    val px = IntArray(w * h)
    bmp.getPixels(px, 0, w, 0, 0, w, h)
    bmp.recycle()
    com.sadurni.attacca.art.ArtCover(w, h, px)
}.getOrNull()

/** The cover of [id] small (96 pixels its longer way), for painting it; null without one. */
private suspend fun coverImageOf(id: String): com.sadurni.attacca.art.ArtCover? = runCatching {
    val cover = com.sadurni.attacca.ui.common.Thumbnails.load(id) ?: return null
    val k = 96f / maxOf(cover.width, cover.height)
    val w = maxOf(1, (cover.width * k).toInt())
    val h = maxOf(1, (cover.height * k).toInt())
    val small = Bitmap.createScaledBitmap(cover, w, h, true)
    val pixels = IntArray(w * h)
    small.getPixels(pixels, 0, w, 0, 0, w, h)
    if (small !== cover) small.recycle()
    com.sadurni.attacca.art.ArtCover(w, h, pixels)
}.getOrNull()

/** The cover's colours of [id] (from its small picture), or null without one. */
private suspend fun coverColoursOf(id: String): IntArray? = runCatching {
    val cover = com.sadurni.attacca.ui.common.Thumbnails.load(id) ?: return null
    val small = Bitmap.createScaledBitmap(cover, 24, 24, true)
    val pixels = IntArray(24 * 24)
    small.getPixels(pixels, 0, 24, 0, 0, 24, 24)
    if (small !== cover) small.recycle()
    ArtSong.coverColours(pixels).takeIf { it.isNotEmpty() }
}.getOrNull()

/** The canvas (GL) and its taps: the whole screen, the title, the rest back. */
@Composable
private fun ArtCanvas(feed: ArtFeed, fps60: Boolean, full: Boolean, onFull: (Boolean) -> Unit, onTap: (() -> Unit)? = null, fade: Fade = Fade()) {
    var surface by remember { mutableStateOf<GLSurfaceView?>(null) }
    var texture by remember { mutableStateOf<GLTextureView?>(null) }
    // The whole Art-tacca: drawn as part of the window (not a surface of its own), so its edges fade
    // into World or O'rama beside it as any view's do. The strip keeps a surface of its own.
    Box(Modifier.fillMaxSize().fadeEdges(fade)) {
        if (onTap == null) {
            AndroidView(
                factory = { c -> GLTextureView(c).apply { setRenderer(ArtRenderer(feed)); texture = this } },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            AndroidView(
                factory = { c ->
                    GLSurfaceView(c).apply {
                        setEGLContextClientVersion(3)
                        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
                        preserveEGLContextOnPause = true
                        setRenderer(ArtRenderer(feed, strip = true))
                        renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                        surface = this
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
    DisposableEffect(surface) {
        surface?.onResume()
        onDispose { surface?.onPause() }
    }
    LaunchedEffect(surface, texture, fps60) {
        feed.cap = if (fps60) 60 else 30
        val step = if (fps60) 16_000_000L else 32_000_000L
        var last = 0L
        while (true) {
            androidx.compose.runtime.withFrameNanos { t ->
                if (t - last >= step - 4_000_000L) {
                    surface?.requestRender()
                    texture?.requestRender()
                    last = t
                }
            }
        }
    }
    val fullNow by androidx.compose.runtime.rememberUpdatedState(full)
    val onFullNow by androidx.compose.runtime.rememberUpdatedState(onFull)
    val tapNow by androidx.compose.runtime.rememberUpdatedState(onTap)
    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { tapNow?.invoke() ?: OramaShared.tapLandscape(fullNow, onFullNow) } })
}

/** How far each edge of the painting fades out into the view beside it. */
private data class Fade(
    val left: androidx.compose.ui.unit.Dp = 0.dp,
    val top: androidx.compose.ui.unit.Dp = 0.dp,
    val right: androidx.compose.ui.unit.Dp = 0.dp,
    val bottom: androidx.compose.ui.unit.Dp = 0.dp,
    /** How far down (a share of the height) the right edge fades: where something else is under it. */
    val rightUntil: Float = 1f,
)

/** How far the parts of a split screen reach into each other, fading. */
private val MELT = 56.dp

/** O'rama fading out at its [left] and [top] edges into what is under it (the painting, World). */
private fun Modifier.meltEdges(left: androidx.compose.ui.unit.Dp, top: androidx.compose.ui.unit.Dp): Modifier = fadeEdges(Fade(left = left, top = top))

/** A part fading out at the edges [fade] asks for, into what is under it. */
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

/** The title, the side buttons and the small player. */
@Composable
private fun ArtOverlay(
    viewModel: PlayerViewModel,
    now: com.sadurni.attacca.ui.NowPlaying,
    position: Long,
    analysis: com.sadurni.attacca.analysis.TrackAnalysis?,
    clipEnd: Long,
    nextAnalysis: com.sadurni.attacca.analysis.TrackAnalysis?,
    id: String?,
    coverImage: androidx.compose.ui.graphics.ImageBitmap?,
    paintOver: Boolean,
    onPaintOver: (Boolean) -> Unit,
    style: String,
    onStyle: (String) -> Unit,
    paintsCover: Boolean,
    onPaintsCover: (Boolean) -> Unit,
    fps60: Boolean,
    onFps: (Boolean) -> Unit,
    partner: String?,
    onPartner: (String?) -> Unit,
    onKeepNow: () -> Unit,
    onDrawer: () -> Unit,
    onFull: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onOpenOrama: () -> Unit,
    onOpenWorld: () -> Unit,
    onOpenQueue: () -> Unit,
) {
    val panel = com.sadurni.attacca.ui.orama.PANEL
    val shadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(1f, 2f), 6f)
    // The song's mood and sound family, as in O'rama and World.
    val context = androidx.compose.ui.platform.LocalContext.current
    val moodLine by androidx.compose.runtime.produceState<String?>(null, id) {
        value = withContext(Dispatchers.IO) { com.sadurni.attacca.ui.orama.moodLineOf(context, id) }
    }
    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).padding(start = 16.dp, top = 8.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.weight(1f).padding(top = 4.dp, end = 8.dp)) {
                if (id != null) {
                    Row(Modifier.clip(RoundedCornerShape(16.dp)).background(panel).clickable(onClick = onDismiss).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    coverImage?.let {
                        Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.padding(end = 10.dp).size(52.dp).clip(RoundedCornerShape(8.dp)))
                    }
                    Column(Modifier.padding(end = 4.dp)) {
                        Text(now.title, style = TextStyle(color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, shadow = shadow), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val by = listOfNotNull(now.artist?.takeIf { it.isNotBlank() }, now.album?.takeIf { it.isNotBlank() }).joinToString(" · ")
                        if (by.isNotEmpty()) Text(by, style = TextStyle(color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp, shadow = shadow), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        moodLine?.let { Text(it, style = TextStyle(color = Color.White.copy(alpha = 0.72f), fontSize = 12.sp, shadow = shadow), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                    }
                }
            }
            // The side buttons, as in O'rama and World: half tucked away to the right so the painting
            // shows; a tap brings them out, and they go back a few seconds after the last use.
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
            androidx.compose.runtime.SideEffect { com.sadurni.attacca.ui.orama.OramaShared.sideShownPx.intValue = (panelWidth * (1f - tucked)).toInt() }
            Box(
                Modifier.padding(top = 4.dp)
                    .offset { IntOffset((panelWidth * tucked).toInt(), 0) }
                    .onSizeChanged { panelWidth = it.width }
                    .onGloballyPositioned { com.sadurni.attacca.ui.orama.OramaShared.sideBottomPx.intValue = it.boundsInWindow().bottom.toInt() }
                    // (Any touch on them while out counts as a use: they stay a while longer.)
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                usedAt = System.nanoTime()
                            }
                        }
                    },
            ) {
            com.sadurni.attacca.ui.orama.FitColumns(
                Modifier.padding(end = 8.dp)
                    .background(panel, RoundedCornerShape(22.dp)).padding(vertical = 2.dp),
            ) {
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, stringResource(R.string.cover_close), tint = Color.White) }
                IconButton(onClick = { onFull(true) }) { Icon(Icons.Rounded.Fullscreen, stringResource(R.string.orama_full_screen), tint = Color.White) }
                // How to paint: a menu of the styles (a change starts a new painting).
                Box {
                    var styles by remember { mutableStateOf(false) }
                    IconButton(onClick = { styles = true }) { Icon(androidx.compose.material.icons.Icons.Rounded.Palette, stringResource(R.string.art_style), tint = Color.White) }
                    androidx.compose.material3.DropdownMenu(styles, onDismissRequest = { styles = false }) {
                        for ((name, label) in ART_STYLES) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(stringResource(label)) },
                                onClick = { styles = false; onStyle(name) },
                                leadingIcon = { androidx.compose.material3.RadioButton(selected = style == name, onClick = null) },
                            )
                        }
                        // What is painted: freely, or the song's cover (a record without one).
                        androidx.compose.material3.HorizontalDivider()
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(stringResource(R.string.art_paint_cover)) },
                            onClick = { styles = false; onPaintsCover(!paintsCover) },
                            leadingIcon = { androidx.compose.material3.Checkbox(checked = paintsCover, onCheckedChange = null) },
                        )
                    }
                }
                // The screen split: the painting alone, or with O'rama or Attacca World beside it.
                Box {
                    var splits by remember { mutableStateOf(false) }
                    IconButton(onClick = { splits = true }) { Icon(androidx.compose.material.icons.Icons.Rounded.VerticalSplit, stringResource(R.string.art_split), tint = Color.White) }
                    androidx.compose.material3.DropdownMenu(splits, onDismissRequest = { splits = false }) {
                        for ((p, label) in listOf(null to R.string.art_alone, "ORAMA" to R.string.art_with_orama, "WORLD" to R.string.art_with_world, "BOTH" to R.string.art_with_both)) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(stringResource(label)) },
                                onClick = { splits = false; onPartner(p) },
                                leadingIcon = { androidx.compose.material3.RadioButton(selected = partner == p, onClick = null) },
                            )
                        }
                    }
                }
                // The drawer of paintings kept.
                IconButton(onClick = onDrawer) { Icon(Icons.Rounded.Inventory2, stringResource(R.string.art_drawer), tint = Color.White) }
                // The painting as it is now, into the drawer.
                IconButton(onClick = onKeepNow) { Icon(Icons.Rounded.SaveAlt, stringResource(R.string.art_keep_now), tint = Color.White) }
                // A new canvas for each song, or each painted over the last.
                IconButton(onClick = { onPaintOver(!paintOver) }) {
                    Icon(if (paintOver) Icons.Rounded.Layers else Icons.Rounded.LayersClear, stringResource(if (paintOver) R.string.art_paint_over else R.string.art_new_canvas), tint = Color.White)
                }
                IconButton(onClick = onOpenOrama) { Icon(Icons.Rounded.Landscape, stringResource(R.string.orama_name), tint = Color.White) }
                IconButton(onClick = { OramaShared.split.value = false; onOpenWorld() }) { Icon(Icons.Rounded.ViewInAr, stringResource(R.string.world_name), tint = Color.White) }
                // Frames a second: 30 or 60 (also for O'rama and World beside it).
                androidx.compose.material3.TextButton(onClick = { onFps(!fps60) }) {
                    Text(stringResource(R.string.orama_fps, if (fps60) 60 else 30), color = Color.White, fontSize = 12.sp)
                }
            }
            // Tucked: a tap anywhere on them only brings them out.
            if (!open) {
                Box(
                    Modifier.matchParentSize().pointerInput(Unit) {
                        detectTapGestures {
                            open = true
                            usedAt = System.nanoTime()
                        }
                    },
                )
            }
            }
        }
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
            val triple = maxWidth > maxHeight && partner == "BOTH"
            // Wide and split (with O'rama or World): one low line; alone, the compact block in the corner.
            val splitLine = maxWidth > maxHeight && partner != null && !triple
            val wide = maxWidth > maxHeight && !triple && !splitLine
            val barModifier = if (triple) {
                // Wide with World and O'rama: in one low line across O'rama's foot (not over its land).
                Modifier.align(Alignment.BottomEnd).then(com.sadurni.attacca.ui.orama.OramaShared.lineBar(maxHeight, start = maxWidth / 3 + 12.dp))
            } else if (splitLine) {
                Modifier.align(Alignment.BottomEnd).then(com.sadurni.attacca.ui.orama.OramaShared.lineBar(maxHeight))
            } else if (wide) {
                Modifier.align(Alignment.BottomEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(end = com.sadurni.attacca.ui.orama.OramaShared.barEnd(), bottom = 14.dp).width(264.dp)
            } else {
                Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 20.dp, vertical = 14.dp).fillMaxWidth()
            }
            com.sadurni.attacca.ui.orama.OramaBar(viewModel, now.isPlaying, position, analysis, now.clipStartMs, clipEnd, nextAnalysis, now.nextTitle, onOpenQueue, wide, barModifier, line = triple || splitLine)
        }
    }
}

/**
 * The painting just finished, small and framed, hanging a little crooked; a tap on its button
 * keeps it: it drops into the drawer and the button says so. It goes on its own after a while.
 */
@Composable
private fun HungPainting(h: Hung, modifier: Modifier, onKeep: (done: (Boolean) -> Unit) -> Unit, onGone: () -> Unit) {
    val accent = LocalAccent.current
    val shown = remember(h) { Animatable(0f) }
    val drop = remember(h) { Animatable(0f) }
    var state by remember(h) { mutableStateOf(0) } // 0 hanging, 1 keeping, 2 kept
    val image = remember(h) { h.bitmap.asImageBitmap() }
    LaunchedEffect(h) {
        shown.animateTo(1f, tween(700))
        delay(HANG_MS)
        if (state == 0) {
            shown.animateTo(0f, tween(900))
            onGone()
        }
    }
    LaunchedEffect(state) {
        if (state == 2) {
            drop.animateTo(1f, tween(650))
            delay(1400)
            shown.animateTo(0f, tween(500))
            onGone()
        }
    }
    val ratio = h.bitmap.width.toFloat() / h.bitmap.height.coerceAtLeast(1)
    val frameW = if (ratio >= 1f) 150.dp else 104.dp
    Column(modifier.graphicsLayer { alpha = shown.value }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .graphicsLayer {
                    // Hung a little crooked; kept, it falls into the drawer below, shrinking.
                    rotationZ = -2.5f * (1f - drop.value)
                    val s = 1f - 0.8f * drop.value
                    scaleX = s
                    scaleY = s
                    translationY = drop.value * 90.dp.toPx()
                    alpha = 1f - drop.value * drop.value
                    transformOrigin = TransformOrigin(0.5f, 0f)
                }
                .shadow(10.dp, RoundedCornerShape(2.dp))
                .background(Color(0xFF3A2E24), RoundedCornerShape(2.dp))
                .padding(4.dp)
                .background(Color(0xFFF3EFE6))
                .padding(6.dp),
        ) {
            Image(image, contentDescription = h.words?.title, contentScale = ContentScale.Fit, modifier = Modifier.width(frameW).height(frameW / ratio))
        }
        val label = when (state) {
            2 -> stringResource(R.string.art_kept)
            else -> stringResource(R.string.art_keep)
        }
        Row(
            Modifier.padding(top = 10.dp).clip(RoundedCornerShape(20.dp))
                .background(if (state == 2) accent else Color(0xE6202020))
                .border(1.dp, accent.copy(alpha = 0.7f), RoundedCornerShape(20.dp))
                .clickable(enabled = state == 0) {
                    state = 1
                    onKeep { ok -> state = if (ok) 2 else 0 }
                }
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (state == 2) Icons.Rounded.Check else Icons.Rounded.Inventory2, null, tint = if (state == 2) Color.White else accent, modifier = Modifier.size(18.dp))
            Text(label, color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp))
        }
        h.words?.title?.takeIf { it.isNotEmpty() && state == 0 }?.let {
            Text(it, color = Color.White, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp).widthIn(max = frameW + 20.dp).background(Color(0xB3202020), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp))
        }
    }
}

/** "In the drawer", for a moment, after keeping the painting as it is. */
@Composable
private fun KeptNote(key: Long, modifier: Modifier, onGone: () -> Unit) {
    val accent = LocalAccent.current
    val a = remember(key) { Animatable(0f) }
    LaunchedEffect(key) {
        a.animateTo(1f, tween(250))
        delay(1400)
        a.animateTo(0f, tween(400))
        onGone()
    }
    Row(
        modifier.graphicsLayer { alpha = a.value; scaleX = 0.9f + 0.1f * a.value; scaleY = 0.9f + 0.1f * a.value }
            .background(Color(0xE6202020), RoundedCornerShape(24.dp)).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(28.dp).background(accent, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Check, null, tint = Color.Black, modifier = Modifier.size(18.dp))
        }
        Text(stringResource(R.string.art_kept), color = Color.White, fontSize = 15.sp, modifier = Modifier.padding(start = 10.dp))
    }
}

/** Art-tacca's styles with their names, as offered (by the song first). */
internal val ART_STYLES = listOf(
    "AUTO" to R.string.art_style_auto,
    "WATERCOLOUR" to R.string.art_style_watercolour,
    "OIL" to R.string.art_style_oil,
    "INK" to R.string.art_style_ink,
    "PEN" to R.string.art_style_pen,
)

/** How long a finished painting hangs before it goes, unless kept. */
private const val HANG_MS = 14_000L

/** How much of O'rama's foot the low stretched player takes in the wide triple. */
private val TRIPLE_BAR_ROOM = com.sadurni.attacca.ui.orama.LINE_BAR_HEIGHT + 16.dp

/** How much of O'rama's foot the small player takes in the upright triple (the whole player, and the room under it). */
private val TRIPLE_TALL_BAR_ROOM = 150.dp
