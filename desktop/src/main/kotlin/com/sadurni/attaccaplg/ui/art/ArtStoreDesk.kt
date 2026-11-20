package com.sadurni.attaccaplg.ui.art

import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.json.JSONObject
import java.io.File

/**
 * Art-tacca's drawer on the desktop: the paintings kept, in the Pictures folder's Attacca folder
 * (as the phone keeps them), each with the song it is of in a hidden folder beside them.
 */
object ArtStoreDesk {
    class Painting(val file: File, val title: String, val artist: String?, val album: String?, val madeAt: Long)

    /** The drawer changed (a painting kept or taken out). */
    val changes = kotlinx.coroutines.flow.MutableStateFlow(0)

    /** Pictures/Attacca: the system's own pictures folder (as Linux names it), or Pictures in the home folder. */
    val dir: File by lazy {
        val home = File(System.getProperty("user.home"))
        val xdg = runCatching {
            if (!System.getProperty("os.name").orEmpty().lowercase().contains("linux")) null
            else ProcessBuilder("xdg-user-dir", "PICTURES").redirectErrorStream(true).start().inputStream.bufferedReader().readText().trim()
                .takeIf { it.isNotEmpty() && File(it).isDirectory && File(it) != home }
        }.getOrNull()
        File(xdg?.let(::File) ?: File(home, "Pictures"), "Attacca").apply { mkdirs() }
    }

    private fun metaOf(png: File): File {
        val hidden = File(png.parentFile, ".art")
        if (!hidden.isDirectory) hidden.mkdirs()
        return File(hidden, png.name.removeSuffix(".png") + ".json")
    }

    private fun nameFor(title: String, at: Long): String {
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH.mm.ss", java.util.Locale.ROOT).format(java.util.Date(at))
        val clean = title.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().take(60)
        return if (clean.isEmpty()) "Art-tacca $stamp" else "$stamp $clean"
    }

    /** Keeps [image] in the drawer; null when it could not be written. Call off the window's thread. */
    fun keep(image: Image, title: String, artist: String?, album: String?): Painting? {
        val at = System.currentTimeMillis()
        val base = nameFor(title, at)
        var file = File(dir, "$base.png")
        var n = 1
        while (file.exists()) file = File(dir, "$base ${n++}.png")
        return runCatching {
            val data = image.encodeToData(EncodedImageFormat.PNG) ?: error("not encoded")
            // (Who made it, of which song and when, kept in the file itself.)
            file.writeBytes(com.sadurni.attacca.art.PngWords.with(data.bytes, com.sadurni.attacca.art.PngWords.forPainting(title, artist, album, at)))
            metaOf(file).writeText(JSONObject().put("title", title).put("artist", artist ?: "").put("album", album ?: "").put("at", at).toString())
            changes.value++
            Painting(file, title, artist, album, at)
        }.getOrElse {
            file.delete()
            null
        }
    }

    /** Every painting kept, the newest first. */
    fun all(): List<Painting> =
        dir.listFiles { f -> f.name.endsWith(".png") }.orEmpty().map { f ->
            val meta = runCatching { JSONObject(metaOf(f).readText()) }.getOrNull()
            Painting(
                f,
                meta?.optString("title")?.takeIf { it.isNotEmpty() } ?: f.nameWithoutExtension,
                meta?.optString("artist")?.takeIf { it.isNotEmpty() },
                meta?.optString("album")?.takeIf { it.isNotEmpty() },
                meta?.optLong("at")?.takeIf { it > 0 } ?: f.lastModified(),
            )
        }.sortedByDescending { it.madeAt }

    fun remove(p: Painting) {
        p.file.delete()
        metaOf(p.file).delete()
        changes.value++
    }

    /** [p], no larger than [maxSide] on its longest side, for the window. Call off the window's thread. */
    fun picture(p: Painting, maxSide: Int): androidx.compose.ui.graphics.ImageBitmap? = runCatching {
        val image = Image.makeFromEncoded(p.file.readBytes())
        val scale = minOf(1f, maxSide / maxOf(image.width, image.height).toFloat())
        if (scale >= 1f) return@runCatching image.toComposeImageBitmap()
        val w = (image.width * scale).toInt().coerceAtLeast(1)
        val h = (image.height * scale).toInt().coerceAtLeast(1)
        val s = org.jetbrains.skia.Surface.makeRasterN32Premul(w, h)
        s.canvas.drawImageRect(image, org.jetbrains.skia.Rect.makeWH(image.width.toFloat(), image.height.toFloat()), org.jetbrains.skia.Rect.makeWH(w.toFloat(), h.toFloat()), org.jetbrains.skia.SamplingMode.LINEAR, null, true)
        s.makeImageSnapshot().toComposeImageBitmap()
    }.getOrNull()

    /** Opens the drawer's folder in the system's file manager. */
    fun openFolder() {
        runCatching { java.awt.Desktop.getDesktop().open(dir) }
    }
}
