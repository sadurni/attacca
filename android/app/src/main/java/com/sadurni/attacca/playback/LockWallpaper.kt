package com.sadurni.attacca.playback

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextUtils

/**
 * The lock screen's picture from the track playing, when chosen (Settings → Appearance): its
 * cover on a darkened copy of itself with the title, or a still of its O'rama landscape. Set again
 * at each track; turned off, the lock screen is given back the home screen's picture (the one it
 * had before cannot be read back by apps).
 */
object LockWallpaper {
    enum class Mode { OFF, COVER, ORAMA }

    private const val PREFS = "lock_wallpaper"

    /** Sets the picture for the track [mediaId]. Blocking (off the main thread). */
    fun apply(
        context: Context,
        mode: Mode,
        mediaId: String?,
        title: String?,
        artist: String?,
        album: String?,
        positionMs: Long,
        cover: Bitmap?,
    ) {
        if (mode == Mode.OFF) return
        val metrics = context.resources.displayMetrics
        val w = minOf(metrics.widthPixels, metrics.heightPixels)
        val h = maxOf(metrics.widthPixels, metrics.heightPixels)
        val picture = when (mode) {
            Mode.ORAMA -> mediaId?.takeIf { !it.contains("://") }?.let {
                OramaStill.render(context, it, title, artist, album, null, positionMs, cover, w / 2, h / 2)
            } ?: coverPicture(w, h, cover, title, artist)
            else -> coverPicture(w, h, cover, title, artist)
        }
        runCatching {
            WallpaperManager.getInstance(context).setBitmap(picture, null, true, WallpaperManager.FLAG_LOCK)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("set", true).apply()
        }
    }

    /** Gives the lock screen back the home screen's picture, when Attacca had set its own. */
    fun clear(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("set", false)) return
        runCatching { WallpaperManager.getInstance(context).clear(WallpaperManager.FLAG_LOCK) }
        prefs.edit().putBoolean("set", false).apply()
    }

    /** The cover big in the lower middle (the clock stays clear at the top), on a dark, blurred copy of itself, the title below. */
    private fun coverPicture(w: Int, h: Int, cover: Bitmap?, title: String?, artist: String?): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.rgb(16, 16, 20))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (cover != null) {
            // A blur without effects: the cover made tiny and stretched back over the whole screen.
            val tiny = Bitmap.createScaledBitmap(cover, 12, 12, true)
            val side = maxOf(w, h).toFloat()
            canvas.drawBitmap(tiny, null, RectF((w - side) / 2f, (h - side) / 2f, (w + side) / 2f, (h + side) / 2f), paint)
            canvas.drawColor(Color.argb(150, 0, 0, 0))
            val size = w * 0.78f
            val left = (w - size) / 2f
            val top = h * 0.36f
            val box = RectF(left, top, left + size, top + size)
            val clip = Path().apply { addRoundRect(box, size * 0.05f, size * 0.05f, Path.Direction.CW) }
            canvas.save()
            canvas.clipPath(clip)
            canvas.drawBitmap(cover, null, box, paint)
            canvas.restore()
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textSize = w * 0.055f
            }
            title?.let { canvas.drawText(TextUtils.ellipsize(it, android.text.TextPaint(text), size, TextUtils.TruncateAt.END).toString(), w / 2f, top + size + w * 0.1f, text) }
            artist?.let {
                text.typeface = Typeface.DEFAULT
                text.textSize = w * 0.042f
                text.color = Color.argb(210, 255, 255, 255)
                canvas.drawText(TextUtils.ellipsize(it, android.text.TextPaint(text), size, TextUtils.TruncateAt.END).toString(), w / 2f, top + size + w * 0.165f, text)
            }
        }
        return out
    }
}
