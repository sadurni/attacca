package com.sadurni.attaccaplg.ui.world

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage

/** The pictures on Attacca World's boards, made for the renderer (see [Picture]). */
object Boards {

    /** The sign before the end: an arrow over the next song's place and title, painted on wood. */
    fun sign(text: String): Picture {
        val w = 512
        val h = 206
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color(0x8A5A34)
        g.fillRect(0, 0, w, h)
        g.color = Color(0x7A4E2C)
        g.stroke = BasicStroke(3f)
        for (y in 12 until h step 22) g.drawLine(0, y, w, y + 6)
        g.color = Color(0x5A3A22)
        g.stroke = BasicStroke(12f)
        g.drawRect(6, 6, w - 12, h - 12)
        fun centred(s: String, y: Int, font: Font) {
            g.font = font
            val x = (w - g.fontMetrics.stringWidth(s)) / 2
            g.color = Color(0x3A2414)
            g.drawString(s, x + 2, y + 2)
            g.color = Color(0xFFF4DC)
            g.drawString(s, x, y)
        }
        centred("→", 62, Font(Font.SERIF, Font.BOLD, 40))
        // As large as fits the board's width, cut short if it still does not.
        var size = 64
        var font = Font(Font.SERIF, Font.BOLD, size)
        g.font = font
        while (g.fontMetrics.stringWidth(text) > w - 40 && size > 26) {
            size -= 2
            font = Font(Font.SERIF, Font.BOLD, size)
            g.font = font
        }
        var shown = text
        while (g.fontMetrics.stringWidth(shown) > w - 40 && shown.length > 2) shown = shown.dropLast(2) + "…"
        centred(shown, 140, font)
        g.dispose()
        return Picture(img.getRGB(0, 0, w, h, null, 0, w), w, h)
    }

    /** A picture from an image's points. */
    fun of(img: BufferedImage): Picture = Picture(img.getRGB(0, 0, img.width, img.height, null, 0, img.width), img.width, img.height)
}
