package com.sadurni.attaccaplg.ui

import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage

/**
 * The new year's greeting as dots for O'rama's lights to gather into: the text drawn in the
 * system's fonts (any script) and sampled on a grid, as x, y pairs in 0 … 1 of its box.
 */
object GreetingDots {
    private val cache = HashMap<String, FloatArray>()

    fun of(text: String, maxDots: Int = 900): FloatArray = cache.getOrPut(text) {
        val w = 520
        val h = 150
        val image = BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        // Two lines when long: the words split near the middle.
        val words = text.split(' ')
        val lines = if (text.length > 16 && words.size > 1) {
            var best = 1
            var bestGap = Int.MAX_VALUE
            for (k in 1 until words.size) {
                val gap = kotlin.math.abs(words.take(k).joinToString(" ").length - words.drop(k).joinToString(" ").length)
                if (gap < bestGap) { best = k; bestGap = gap }
            }
            listOf(words.take(best).joinToString(" "), words.drop(best).joinToString(" "))
        } else {
            listOf(text)
        }
        var size = 90
        var font = Font(Font.SANS_SERIF, Font.BOLD, size)
        while (size > 12) {
            font = Font(Font.SANS_SERIF, Font.BOLD, size)
            val m = g.getFontMetrics(font)
            if (lines.all { m.stringWidth(it) <= w - 8 } && m.height * lines.size <= h - 4) break
            size -= 4
        }
        g.font = font
        g.color = java.awt.Color.WHITE
        val m = g.fontMetrics
        val top = (h - m.height * lines.size) / 2
        for ((k, line) in lines.withIndex()) {
            g.drawString(line, (w - m.stringWidth(line)) / 2, top + m.ascent + k * m.height)
        }
        g.dispose()
        sample(w, h, maxDots) { x, y -> (image.raster.getSample(x, y, 0)) > 128 }
    }

    /** Lit points on a grid, as fine as [maxDots] allows. */
    fun sample(w: Int, h: Int, maxDots: Int, lit: (Int, Int) -> Boolean): FloatArray {
        var step = 3
        while (true) {
            val out = ArrayList<Float>()
            var y = step / 2
            while (y < h) {
                var x = step / 2
                while (x < w) {
                    if (lit(x, y)) { out += x / w.toFloat(); out += y / h.toFloat() }
                    x += step
                }
                y += step
            }
            if (out.size / 2 <= maxDots || step > 12) return out.toFloatArray()
            step++
        }
    }
}
