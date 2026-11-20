package com.sadurni.attacca.ui.art

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sadurni.attacca.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat

/** Art-tacca's drawer: the paintings kept, framed on a wall to look through; one opens big. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ArtDrawer(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val changes by ArtStore.changes.collectAsStateWithLifecycle()
    val paintings by produceState(emptyList<ArtStore.Painting>(), changes) {
        value = withContext(Dispatchers.IO) { ArtStore.all(context) }
    }
    var open by remember { mutableStateOf<Int?>(null) }
    Dialog(onDismissRequest = { if (open != null) open = null else onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(WALL)) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.cover_close), tint = Color.White) }
                    Text(stringResource(R.string.art_drawer_title), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (paintings.isNotEmpty()) Text("${paintings.size}", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
                }
                if (paintings.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.art_drawer_empty), color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp)
                    }
                } else {
                    LazyVerticalGrid(
                        GridCells.Adaptive(150.dp),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(paintings.size, key = { paintings[it].file.name }) { i ->
                            val p = paintings[i]
                            Column(Modifier.clickable { open = i }) {
                                Framed(p, 360, Modifier.fillMaxWidth().aspectRatio(1f))
                                Text(p.title, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                                Text(listOfNotNull(p.artist, dateOf(p)).joinToString(" · "), color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            open?.let { start ->
                if (paintings.isNotEmpty()) Viewer(paintings, start.coerceIn(0, paintings.size - 1)) { open = null }
            }
        }
    }
}

/** One painting big, the others a swipe away, with what can be done with it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Viewer(paintings: List<ArtStore.Painting>, start: Int, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(start) { paintings.size }
    var asking by remember { mutableStateOf<ArtStore.Painting?>(null) }
    val shareTitle = stringResource(R.string.share)
    // While a painting is looked at closely, the pages do not turn.
    var zoomed by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(WALL)) {
        HorizontalPager(pager, Modifier.fillMaxSize(), userScrollEnabled = !zoomed, key = { paintings[it].file.name }) { i ->
            Zoomable(onZoomed = { if (i == pager.currentPage) zoomed = it }) {
                Box(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 96.dp), contentAlignment = Alignment.Center) {
                    Framed(paintings[i], 3000, Modifier.fillMaxSize())
                }
            }
        }
        val p = paintings.getOrNull(pager.currentPage)
        Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).padding(start = 4.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.cover_close), tint = Color.White) }
            Column(Modifier.weight(1f)) {
                Text(p?.title ?: "", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                p?.let { Text(listOfNotNull(it.artist, it.album, dateOf(it)).joinToString(" · "), color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        if (p != null) {
            Row(Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(bottom = 16.dp).background(Color(0xCC202020), RoundedCornerShape(28.dp)).padding(horizontal = 8.dp)) {
                IconButton(onClick = { ArtStore.share(context, p, shareTitle) }) { Icon(Icons.Rounded.Share, shareTitle, tint = Color.White) }
                IconButton(onClick = { asking = p }) { Icon(Icons.Rounded.Delete, stringResource(R.string.art_remove), tint = Color.White) }
            }
        }
    }
    asking?.let { p ->
        AlertDialog(
            onDismissRequest = { asking = null },
            text = { Text(stringResource(R.string.art_remove_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    asking = null
                    scope.launch(Dispatchers.IO) { ArtStore.remove(context, p) }
                    if (paintings.size <= 1) onClose()
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { asking = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** A painting in its frame: a thin dark wood and a pale mount. */
@Composable
private fun Framed(p: ArtStore.Painting, maxSide: Int, modifier: Modifier) {
    val picture by produceState<ImageBitmap?>(null, p.file, maxSide) {
        value = withContext(Dispatchers.IO) { ArtStore.picture(p, maxSide)?.asImageBitmap() }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        picture?.let { img ->
            val ratio = img.width.toFloat() / img.height.coerceAtLeast(1)
            Box(
                Modifier.aspectRatio(ratio, matchHeightConstraintsFirst = ratio < 1f)
                    .shadow(8.dp, RoundedCornerShape(2.dp))
                    .background(Color(0xFF3A2E24), RoundedCornerShape(2.dp))
                    .padding(3.dp)
                    .background(Color(0xFFF3EFE6))
                    .padding(5.dp),
            ) {
                Image(img, contentDescription = p.title, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

/**
 * A painting to look at closely: pinched or double-tapped bigger (up to 6x), moved about while big,
 * the point under the fingers staying under them; [onZoomed] says whether it is bigger than it fits.
 */
@Composable
private fun Zoomable(onZoomed: (Boolean) -> Unit, content: @Composable () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    fun set(s: Float, o: Offset) {
        val mx = size.width * (s - 1f) / 2f
        val my = size.height * (s - 1f) / 2f
        scale = s
        offset = if (s <= 1.01f) Offset.Zero else Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
        onZoomed(s > 1.01f)
    }
    Box(
        Modifier.fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { p ->
                    if (scale > 1.01f) set(1f, Offset.Zero)
                    else set(ZOOM_TAP, (Offset(size.width / 2f, size.height / 2f) - p) * (ZOOM_TAP - 1f))
                })
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        // One finger only moves a painting already big (else the pager turns the page).
                        // (A finger lifting has no place any more: nothing to follow.)
                        val down = event.changes.count { it.pressed }
                        if (down > 0 && (down > 1 || scale > 1.01f)) {
                            val c = Offset(size.width / 2f, size.height / 2f)
                            val q = event.calculateCentroid(useCurrent = true)
                            val s = (scale * event.calculateZoom()).coerceIn(1f, ZOOM_MAX)
                            set(s, (offset - (q - c)) * (s / scale) + (q - c) + event.calculatePan())
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
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

private const val ZOOM_TAP = 2.5f
private const val ZOOM_MAX = 6f

private fun dateOf(p: ArtStore.Painting): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(java.util.Date(p.madeAt))

private val WALL = Color(0xFF1C1B19)
