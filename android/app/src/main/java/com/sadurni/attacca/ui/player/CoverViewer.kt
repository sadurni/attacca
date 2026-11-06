package com.sadurni.attacca.ui.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.sadurni.attacca.R
import com.sadurni.attacca.library.AudioFiles
import com.sadurni.attacca.ui.common.Icon
import com.sadurni.attacca.ui.common.IconButton
import com.sadurni.attacca.ui.theme.AttaccaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** The cover at full size: the folder's picture or the one in the file, decoded no larger than this. */
private const val MAX_SIDE = 4096

/** How far the cover can be enlarged. */
private const val MAX_ZOOM = 8f

/** The playing track's cover, as large as it is, from its file. Null when it has none. Blocking. */
fun fullCover(mediaId: String): Bitmap? {
    if (mediaId.contains("://")) return null
    val file = File(mediaId)
    val options = BitmapFactory.Options()
    fun decode(read: (BitmapFactory.Options) -> Bitmap?): Bitmap? {
        options.inJustDecodeBounds = true
        read(options)
        var sample = 1
        while (maxOf(options.outWidth, options.outHeight) / sample > MAX_SIDE) sample *= 2
        options.inJustDecodeBounds = false
        options.inSampleSize = sample
        return read(options)
    }
    file.parentFile?.let(AudioFiles::cachedFolderCover)?.let { cover ->
        decode { BitmapFactory.decodeFile(cover.path, it) }?.let { return it }
    }
    val bytes = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            retriever.embeddedPicture
        } finally {
            retriever.release()
        }
    }.getOrNull() ?: return null
    return decode { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, it) }
}

/**
 * The cover over the whole screen: pinch to enlarge it (up to 8×) and drag it around, double tap
 * to enlarge or go back; a tap outside a zoom, Back or the X closes it. Shows its size.
 */
@Composable
fun CoverViewer(mediaId: String, onDismiss: () -> Unit) {
    val image by produceState<Bitmap?>(null, mediaId) { value = withContext(Dispatchers.IO) { runCatching { fullCover(mediaId) }.getOrNull() } }
    var loaded by remember(mediaId) { mutableStateOf(false) }
    if (image != null) loaded = true
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
        offset = if (scale == 1f) Offset.Zero else offset + pan * scale
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            val bitmap = image
            if (bitmap == null) {
                CircularProgressIndicator(Modifier.size(32.dp), color = Color.White)
            } else {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { if (scale == 1f) onDismiss() },
                                onDoubleTap = { at ->
                                    if (scale > 1f) {
                                        scale = 1f
                                        offset = Offset.Zero
                                    } else {
                                        // Enlarged around the point tapped.
                                        scale = 3f
                                        offset = (Offset(size.width / 2f, size.height / 2f) - at) * 3f
                                    }
                                },
                            )
                        }
                        .transformable(transform)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                )
                Text(
                    "${bitmap.width} × ${bitmap.height}",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = AttaccaType.caption,
                    modifier = Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(12.dp),
                )
            }
            // In a box of its own: the button keeps its place at the top corner.
            Box(Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(8.dp)) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, stringResource(R.string.cover_close), tint = Color.White)
                }
            }
        }
    }
}
