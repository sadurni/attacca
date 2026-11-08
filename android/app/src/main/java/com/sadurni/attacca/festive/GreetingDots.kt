package com.sadurni.attacca.festive

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface

/**
 * The new year's greeting as dots for O'rama's lights to gather into: the text drawn with the
 * phone's fonts (any script, emoji too) and sampled on a grid, as x, y pairs in 0 … 1 of its box
 * (520 × 150, the shape O'rama keeps).
 */
object GreetingDots {
    private val cache = HashMap<String, FloatArray>()

    fun of(text: String, maxDots: Int = 900): FloatArray = synchronized(cache) {
        cache.getOrPut(text) {
            val w = 520
            val h = 150
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            // Two lines when long: the words split near the middle.
            val words = text.split(' ')
            val lines = if (text.length > 16 && words.size > 1) {
                val best = (1 until words.size).minBy { k ->
                    kotlin.math.abs(words.take(k).joinToString(" ").length - words.drop(k).joinToString(" ").length)
                }
                listOf(words.take(best).joinToString(" "), words.drop(best).joinToString(" "))
            } else {
                listOf(text)
            }
            var size = 96f
            while (size > 12f) {
                paint.textSize = size
                val lineHeight = paint.fontSpacing
                if (lines.all { paint.measureText(it) <= w - 8 } && lineHeight * lines.size <= h - 4) break
                size -= 4f
            }
            val lineHeight = paint.fontSpacing
            val top = (h - lineHeight * lines.size) / 2f - paint.ascent()
            for ((k, line) in lines.withIndex()) canvas.drawText(line, w / 2f, top + k * lineHeight, paint)
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            bitmap.recycle()
            sample(w, h, maxDots) { x, y -> (pixels[y * w + x] ushr 24) > 128 }
        }
    }

    private fun sample(w: Int, h: Int, maxDots: Int, lit: (Int, Int) -> Boolean): FloatArray {
        var step = 3
        while (true) {
            val out = ArrayList<Float>()
            var y = step / 2
            while (y < h) {
                var x = step / 2
                while (x < w) {
                    if (lit(x, y)) {
                        out += x / w.toFloat()
                        out += y / h.toFloat()
                    }
                    x += step
                }
                y += step
            }
            if (out.size / 2 <= maxDots || step > 12) return out.toFloatArray()
            step++
        }
    }
}
