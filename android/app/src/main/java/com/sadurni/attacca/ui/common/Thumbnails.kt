package com.sadurni.attacca.ui.common

import com.sadurni.attacca.ui.theme.AttaccaShapes
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sadurni.attacca.library.AudioFiles
import com.sadurni.attacca.ui.theme.LocalAttaccaColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Small covers for list rows: the folder image when there is one (cheap, shared by the whole
 * album), otherwise the picture embedded in the file. Cached in memory.
 */
object Thumbnails {

    private const val SIZE_PX = 128

    /** Bigger pictures (album grids, headers) are decoded at one of these sizes. */
    private val SIZES = intArrayOf(SIZE_PX, 256, 512)

    fun sizeFor(px: Int): Int = SIZES.firstOrNull { it >= px } ?: SIZES.last()

    /** Keyed by folder cover path or by file path for embedded art. */
    private val cache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Files known to have no artwork at all. */
    private val missing = HashSet<String>()

    /** Embedded art means opening the file; keep it to a couple at a time while scrolling. */
    private val extractors = Semaphore(2)

    /** Drops the pictures made from [path] (a cover file that changed). */
    fun forget(path: String) {
        for (size in SIZES) cache.remove(if (size == SIZE_PX) path else "$path@$size")
        synchronized(missing) { missing.clear() }
    }

    suspend fun load(mediaId: String, sizePx: Int = SIZE_PX): Bitmap? = withContext(Dispatchers.IO) {
        if (mediaId.contains("://")) return@withContext null
        val file = File(mediaId)
        synchronized(missing) { if (mediaId in missing) return@withContext null }

        val cover = file.parentFile?.let(AudioFiles::cachedFolderCover)
        val suffix = if (sizePx == SIZE_PX) "" else "@$sizePx"
        if (cover != null) {
            cache.get(cover.path + suffix)?.let { return@withContext it }
            decodeFile(cover, sizePx)?.let {
                cache.put(cover.path + suffix, it)
                return@withContext it
            }
        }

        cache.get(mediaId + suffix)?.let { return@withContext it }
        val embedded = extractors.withPermit { embeddedPicture(file) }?.let { decodeBytes(it, sizePx) }
        // Not "no artwork" for a file that cannot be read now (storage not mounted yet after a restart).
        if (embedded != null) cache.put(mediaId + suffix, embedded) else if (file.isFile) synchronized(missing) { missing.add(mediaId) }
        embedded
    }

    private fun embeddedPicture(file: File): ByteArray? = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            retriever.embeddedPicture
        } finally {
            retriever.release()
        }
    }.getOrNull()

    private fun decodeFile(file: File, size: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds, size) })
            ?.let { scaled(it, size) }
    }.getOrNull()

    private fun decodeBytes(data: ByteArray, size: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds, size) })
            ?.let { scaled(it, size) }
    }.getOrNull()

    private fun sampleSize(bounds: BitmapFactory.Options, size: Int): Int {
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= size && bounds.outHeight / (sample * 2) >= size) sample *= 2
        return sample
    }

    private fun scaled(bitmap: Bitmap, size: Int): Bitmap =
        if (bitmap.width <= size * 2) bitmap else Bitmap.createScaledBitmap(bitmap, size, size, true)
}

/** The first track found in [folder] (or in its first subfolders), whose cover stands for it; cached. */
private val folderTracks = java.util.concurrent.ConcurrentHashMap<String, String>()

private fun firstTrack(folder: java.io.File, depth: Int = 0): String? {
    folderTracks[folder.path]?.let { return it.ifEmpty { null } }
    val children = folder.listFiles()?.sortedBy { it.name.lowercase() }.orEmpty()
    val found = children.firstOrNull { it.isFile && com.sadurni.attacca.library.AudioFiles.isAudio(it) }?.path
        ?: if (depth < 2) children.asSequence().filter { it.isDirectory && !it.name.startsWith(".") }.take(6).firstNotNullOfOrNull { firstTrack(it, depth + 1) } else null
    folderTracks[folder.path] = found.orEmpty()
    return found
}

/** A folder's cover (its picture, or that of the first track inside), [fallback] while there is none. */
@Composable
fun FolderThumbnail(folder: java.io.File, size: Dp = 44.dp, fallback: @Composable () -> Unit) {
    val track by produceState<String?>(initialValue = folderTracks[folder.path]?.ifEmpty { null }, folder.path) {
        value = withContext(Dispatchers.IO) { runCatching { firstTrack(folder) }.getOrNull() }
    }
    val path = track
    if (path != null) TrackThumbnail(path, size) else fallback()
}

/** A list-row thumbnail with a note icon while loading or when there is no artwork. */
@Composable
fun TrackThumbnail(mediaId: String, size: Dp = 44.dp) {
    val px = with(androidx.compose.ui.platform.LocalDensity.current) { size.roundToPx() }
    val bitmap by produceState<Bitmap?>(initialValue = null, mediaId, px) { value = Thumbnails.load(mediaId, Thumbnails.sizeFor(px)) }
    val shape = AttaccaShapes.small
    val image = bitmap
    if (image != null) {
        Image(
            bitmap = image.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(shape),
        )
    } else {
        Box(
            Modifier.size(size).clip(shape).background(LocalAttaccaColors.current.surface),
            contentAlignment = Alignment.Center,
        ) {
            com.sadurni.attacca.ui.theme.ThemedIcon(
                Icons.Rounded.MusicNote, null, LocalAttaccaColors.current.textGrayed, slot = com.sadurni.attacca.settings.AppIcon.NO_COVER,
            )
        }
    }
}
