package com.sadurni.attaccaplg.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Rect
import java.io.File
import java.util.Collections

/**
 * Covers, as the phone finds them: a picture in the track's folder (cover, folder, front…), else
 * the one inside the file, else one found online for the folder ([CoverSearch]). Made small once
 * ([SIZE] or [LARGE] pixels) and kept a while in memory.
 */
object Covers {
    const val SIZE = 256
    const val LARGE = 1024

    private val NAMES = listOf("cover", "folder", "front", "album", "albumart")
    private val EXTENSIONS = listOf("jpg", "jpeg", "png", "webp")

    private val cache = Collections.synchronizedMap(object : LinkedHashMap<String, ImageBitmap?>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap?>?) = size > 400
    })

    /** The cover of the track at [path], at most [size] pixels a side; null without one. Blocking. */
    fun of(path: String, size: Int = SIZE): ImageBitmap? {
        val file = File(path)
        val key = "${file.parent}|$size"
        if (cache.containsKey(key)) cache[key]?.let { return it }
        // One found online and chosen comes before the one inside the files.
        val bytes = folderPicture(file.parentFile)?.readBytes()
            ?: file.parentFile?.let { com.sadurni.attaccaplg.library.CoverSearch.cached(it) }?.readBytes()
            ?: embedded(file)
        val image = bytes?.let { runCatching { scaled(it, size) }.getOrNull() }
        // A folder's picture serves all its tracks; an embedded one only its own file.
        if (image != null || folderPicture(file.parentFile) == null) cache[key] = image
        return image
    }

    /** Bumped when a folder's cover changed, so what shows covers loads them again. */
    var version by androidx.compose.runtime.mutableStateOf(0)

    /** Forgets what was loaded for [folder] (its cover was found or saved). */
    fun changed(folder: File) {
        synchronized(cache) { cache.keys.removeAll { it.startsWith(folder.path + "|") } }
        version++
    }

    /** Whether the album in [folder] (with [track] in it) has a cover of its own: a picture beside it or inside the file. */
    fun hasOwn(folder: File, track: File): Boolean = folderPicture(folder) != null || embedded(track) != null

    private fun folderPicture(folder: File?): File? {
        val files = folder?.listFiles() ?: return null
        for (name in NAMES) for (ext in EXTENSIONS) {
            files.firstOrNull { it.isFile && it.name.equals("$name.$ext", ignoreCase = true) }?.let { return it }
        }
        return files.firstOrNull { it.isFile && it.extension.lowercase() in EXTENSIONS }
    }

    private fun embedded(file: File): ByteArray? = runCatching { AudioFileIO.read(file).tag?.firstArtwork?.binaryData }.getOrNull()

    private fun scaled(bytes: ByteArray, size: Int): ImageBitmap {
        val image = Image.makeFromEncoded(bytes)
        val scale = minOf(1f, size.toFloat() / maxOf(image.width, image.height))
        if (scale >= 1f) return image.toComposeImageBitmap()
        val w = (image.width * scale).toInt().coerceAtLeast(1)
        val h = (image.height * scale).toInt().coerceAtLeast(1)
        val surface = Surface.makeRasterN32Premul(w, h)
        surface.canvas.drawImageRect(image, Rect.makeWH(image.width.toFloat(), image.height.toFloat()), Rect.makeWH(w.toFloat(), h.toFloat()),
            FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR), null, true)
        return surface.makeImageSnapshot().toComposeImageBitmap()
    }
}

/** The cover of [path] for the window, loaded off the window's thread. */
@Composable
fun coverOf(path: String?, size: Int = Covers.SIZE): ImageBitmap? =
    produceState<ImageBitmap?>(null, path, size, Covers.version) {
        value = path?.let { withContext(Dispatchers.IO) { runCatching { Covers.of(it, size) }.getOrNull() } }
    }.value
