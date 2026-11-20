package com.sadurni.attacca.ui.art

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File

/**
 * Art-tacca's drawer: the paintings kept, each a picture with the song it is of (title, artist,
 * album) and when it was painted. They live in Pictures/Attacca (like Music/Attacca), where the
 * gallery and other apps see them and they stay if Attacca goes; what each is of, in a hidden
 * folder beside them. Without access to it, in the app's own files.
 */
object ArtStore {
    class Painting(val file: File, val title: String, val artist: String?, val album: String?, val madeAt: Long)

    /** The drawer changed (a painting kept or taken out): its count, to refresh what shows it. */
    val changes = kotlinx.coroutines.flow.MutableStateFlow(0)

    private val pictures: File
        get() = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES), "Attacca")

    @Volatile
    private var moved = false

    /** Where the drawer is: Pictures/Attacca, or the app's own files without access to it. */
    private fun dir(context: Context): File {
        val visible = pictures
        val ok = runCatching { visible.mkdirs(); visible.isDirectory && visible.canWrite() }.getOrDefault(false)
        if (!ok) return File(context.filesDir, "art").apply { mkdirs() }
        if (!moved) {
            moved = true
            moveFromPrivate(context, visible)
        }
        return visible
    }

    /** What each painting is of, beside them, hidden from the gallery. */
    private fun metaOf(png: File): File {
        val hidden = File(png.parentFile, ".art")
        if (!hidden.isDirectory) {
            hidden.mkdirs()
            runCatching { File(hidden, ".nomedia").createNewFile() }
        }
        return File(hidden, png.name.removeSuffix(".png") + ".json")
    }

    /** The paintings kept before the drawer moved to Pictures/Attacca, moved there. */
    private fun moveFromPrivate(context: Context, to: File) {
        val old = File(context.filesDir, "art")
        val pngs = old.listFiles { f -> f.name.endsWith(".png") }.orEmpty()
        val moved = ArrayList<String>()
        for (png in pngs) {
            runCatching {
                val target = File(to, png.name)
                png.copyTo(target, overwrite = false)
                File(old, png.name.removeSuffix(".png") + ".json").takeIf { it.isFile }?.copyTo(metaOf(target), overwrite = true)
                png.delete()
                File(old, png.name.removeSuffix(".png") + ".json").delete()
                moved += target.path
            }
        }
        if (moved.isNotEmpty()) scan(context, moved)
    }

    /** Tells the phone's gallery about [paths] (new, or gone). */
    private fun scan(context: Context, paths: List<String>) {
        runCatching { android.media.MediaScannerConnection.scanFile(context.applicationContext, paths.toTypedArray(), null, null) }
    }

    /** A file name for a painting of [title] made at [at]: "2026-11-20 05.49 Title.png". */
    private fun nameFor(title: String, at: Long): String {
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH.mm.ss", java.util.Locale.ROOT).format(java.util.Date(at))
        val clean = title.replace(Regex("[\\/:*?\"<>|\u0000-\u001f]"), "_").trim().take(60)
        return if (clean.isEmpty()) "Art-tacca $stamp" else "$stamp $clean"
    }

    /** Keeps [bitmap] in the drawer; null when it could not be written. Call off the main thread. */
    fun keep(context: Context, bitmap: Bitmap, title: String, artist: String?, album: String?): Painting? {
        val at = System.currentTimeMillis()
        val folder = dir(context)
        val base = nameFor(title, at)
        var file = File(folder, "$base.png")
        var n = 1
        while (file.exists()) file = File(folder, "$base ${n++}.png")
        return runCatching {
            // (Who made it, of which song and when, kept in the file itself.)
            val png = java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            file.writeBytes(com.sadurni.attacca.art.PngWords.with(png, com.sadurni.attacca.art.PngWords.forPainting(title, artist, album, at)))
            val meta = JSONObject().put("title", title).put("artist", artist ?: "").put("album", album ?: "").put("at", at)
            metaOf(file).writeText(meta.toString())
            scan(context, listOf(file.path))
            changes.value++
            Painting(file, title, artist, album, at)
        }.getOrElse {
            file.delete()
            null
        }
    }

    private fun lastFile(context: Context, name: String) = File(context.filesDir, "$name.png")

    /** The app's own last painting finished. */
    const val APP_LAST = "art_last"

    /** The wallpaper's own last painting (finished, or as it was when the music stopped). */
    const val WALL_LAST = "art_wall_last"

    /** Keeps [bitmap] as the last painting [name] (apart from the drawer). Writes in the background. */
    fun keepLast(context: Context, bitmap: Bitmap, name: String = APP_LAST) {
        val app = context.applicationContext
        Thread {
            runCatching {
                val tmp = File(app.filesDir, "$name.tmp")
                tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                tmp.renameTo(lastFile(app, name))
            }
        }.start()
    }

    /** The last painting [name], or null. Call off the main thread. */
    fun last(context: Context, name: String = APP_LAST): Bitmap? =
        runCatching { lastFile(context, name).takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path) } }.getOrNull()

    /** Every painting kept, the newest first. */
    fun all(context: Context): List<Painting> =
        dir(context).listFiles { f -> f.name.endsWith(".png") }.orEmpty().map { f ->
            val meta = runCatching { JSONObject(metaOf(f).readText()) }.getOrNull()
                ?: runCatching { JSONObject(File(f.path.removeSuffix(".png") + ".json").readText()) }.getOrNull()
            Painting(
                f,
                meta?.optString("title")?.takeIf { it.isNotEmpty() } ?: "",
                meta?.optString("artist")?.takeIf { it.isNotEmpty() },
                meta?.optString("album")?.takeIf { it.isNotEmpty() },
                meta?.optLong("at")?.takeIf { it > 0 } ?: f.lastModified(),
            )
        }.sortedByDescending { it.madeAt }

    fun remove(context: Context, p: Painting) {
        p.file.delete()
        metaOf(p.file).delete()
        File(p.file.path.removeSuffix(".png") + ".json").delete()
        scan(context, listOf(p.file.path))
        changes.value++
    }

    /** [p], no larger than [maxSide] on its longest side. Call off the main thread. */
    fun picture(p: Painting, maxSide: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(p.file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        BitmapFactory.decodeFile(p.file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    /** Offers [p] to other apps. */
    fun share(context: Context, p: Painting, title: String) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", p.file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** The phone keeps pictures for the app in Pictures (Android 10 and later). */
    val canCopyToPictures: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** A copy of [p] in Pictures/Attacca; false when it could not be made. Call off the main thread. */
    fun copyToPictures(context: Context, p: Painting): Boolean {
        if (!canCopyToPictures) return false
        return runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, p.file.name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Attacca")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
            resolver.openOutputStream(uri)?.use { out -> p.file.inputStream().use { it.copyTo(out) } } ?: return false
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        }.getOrDefault(false)
    }
}
