package com.sadurni.attacca.ui.theme

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import java.io.File

object ArtworkLoader {

    /** Decodes embedded or folder artwork, downsampled to roughly [maxSize] px. */
    fun decode(data: ByteArray?, file: File?, maxSize: Int = 1024): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        when {
            data != null -> BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            file != null -> BitmapFactory.decodeFile(file.path, bounds)
            else -> return null
        }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxSize && bounds.outHeight / (sample * 2) >= maxSize) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        if (data != null) {
            BitmapFactory.decodeByteArray(data, 0, data.size, options)
        } else {
            BitmapFactory.decodeFile(file!!.path, options)
        }
    }.getOrNull()

    /**
     * Picks a vivid color from the artwork, adjusted so it stays
     * readable on the theme background.
     */
    fun accentFrom(bitmap: Bitmap, darkBackground: Boolean): Color? {
        val palette = Palette.from(bitmap).maximumColorCount(16).generate()
        val swatch = palette.vibrantSwatch
            ?: palette.lightVibrantSwatch
            ?: palette.darkVibrantSwatch
            ?: palette.dominantSwatch
            ?: return null
        val hsl = swatch.hsl.copyOf()
        if (hsl[1] < 0.15f) return null // Grayscale art: keep the theme accent.
        // Keep enough contrast with the background.
        hsl[2] = if (darkBackground) hsl[2].coerceIn(0.45f, 0.7f) else hsl[2].coerceIn(0.3f, 0.5f)
        return Color(ColorUtils.HSLToColor(hsl))
    }
}
