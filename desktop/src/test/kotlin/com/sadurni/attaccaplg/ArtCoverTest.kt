package com.sadurni.attaccaplg

import com.sadurni.attacca.art.ArtCover
import com.sadurni.attacca.art.ArtSong
import com.sadurni.attacca.art.ArtStyle
import com.sadurni.attacca.art.ArtSubject
import com.sadurni.attacca.diorama.SongWave
import com.sadurni.attaccaplg.ui.art.ArtDeskCanvas
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * Art-tacca painting a song's cover: a whole song in each style comes out like its cover (closer
 * than a free painting of the same song). With ART_COVERS (folder of covers, ';'-separated files or
 * a folder) it paints those too, to look at, into build/art-cover.
 */
class ArtCoverTest {
    private fun song(id: String, image: ArtCover?, lengthMs: Long, title: String? = "Darkness on the Edge of Town"): ArtSong {
        val n = 600
        val wave = FloatArray(n) { i -> (0.35f + 0.5f * kotlin.math.sin(i / 40f).let { it * it }) }
        return ArtSong(id, 9 + 12, 0.7f, 0.6f, 0.4f, 0.5f, 0.7f, 120f, 0.7f, SongWave(wave, lengthMs, 0L, lengthMs), intArrayOf(0xFF8A5030.toInt(), 0xFF2050A0.toInt()), image, com.sadurni.attaccaplg.ui.art.titleImageOf(title))
    }

    private fun coverOf(f: File, size: Int = 96): ArtCover {
        val src = ImageIO.read(f)
        val k = size / maxOf(src.width, src.height).toFloat()
        val w = maxOf(1, (src.width * k).toInt())
        val h = maxOf(1, (src.height * k).toInt())
        val small = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val g = small.createGraphics()
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(src, 0, 0, w, h, null)
        g.dispose()
        return ArtCover(w, h, small.getRGB(0, 0, w, h, null, 0, w))
    }

    /** A cover of colour fields and a dark diagonal, made here. */
    private fun madeCover(): ArtCover {
        val n = 64
        val px = IntArray(n * n) { i ->
            val x = i % n
            val y = i / n
            when {
                kotlin.math.abs(x - y) < 4 -> 0xFF101018.toInt()
                x < n / 2 && y < n / 2 -> 0xFFE0B020.toInt()
                x >= n / 2 && y < n / 2 -> 0xFF2060C0.toInt()
                x < n / 2 -> 0xFFC03020.toInt()
                else -> 0xFFF0F0E8.toInt()
            }
        }
        return ArtCover(n, n, px)
    }

    private fun paint(s: ArtSong, style: ArtStyle, subject: ArtSubject, likeness: Float, lengthMs: Double, w: Int, h: Int): org.jetbrains.skia.Image {
        val c = ArtDeskCanvas()
        var img: org.jetbrains.skia.Image? = null
        var now = 1_000_000L
        var songMs = 0.0
        val onset = 1_000_000L - 10_000L
        // Opened a moment in: the painting up to there is laid at once, then frame by frame.
        val first = lengthMs - 8000.0
        img = c.frame(s, 0.0, true, 0.6f, 0.5f, 0.3f, onset, null, now, style, false, w, h, subject, likeness) { _, _ -> }
        now += 33
        songMs = first
        while (songMs < lengthMs) {
            val beat = (songMs % 500.0) < 40.0
            img = c.frame(s, songMs, true, 0.6f, if (beat) 0.9f else 0.4f, 0.3f, now - (songMs % 500.0).toLong(), null, now, style, false, w, h, subject, likeness) { _, _ -> }
            now += 33
            songMs += 33.0
        }
        // Let the last marks dry.
        repeat(120) {
            img = c.frame(s, lengthMs, false, 0f, 0f, 0f, 0L, null, now, style, false, w, h, subject, likeness) { _, _ -> }
            now += 33
        }
        lastEye = c.painter.eye()
        return img!!
    }

    private var lastEye: Triple<Int, Int, FloatArray>? = null

    /** The painter's copy of the canvas against the painting itself: mean lightness of each (0 … 1). */
    private fun eyeCheck(img: org.jetbrains.skia.Image, name: String, out: File): String {
        val (cols, rows, m) = lastEye ?: return ""
        val bmp = org.jetbrains.skia.Bitmap()
        bmp.allocN32Pixels(img.width, img.height)
        img.readPixels(bmp, 0, 0)
        val pic = java.awt.image.BufferedImage(cols, rows, java.awt.image.BufferedImage.TYPE_INT_RGB)
        var a = 0.0
        var b = 0.0
        for (j in 0 until rows) for (i in 0 until cols) {
            val k = (j * cols + i) * 3
            val r = (m[k] * 255).toInt().coerceIn(0, 255)
            val g = (m[k + 1] * 255).toInt().coerceIn(0, 255)
            val bl = (m[k + 2] * 255).toInt().coerceIn(0, 255)
            pic.setRGB(i, j, (r shl 16) or (g shl 8) or bl)
            a += (m[k] + m[k + 1] + m[k + 2]) / 3.0
            val c = bmp.getColor(((i + 0.5) * img.width / cols).toInt().coerceAtMost(img.width - 1), ((j + 0.5) * img.height / rows).toInt().coerceAtMost(img.height - 1))
            b += (((c shr 16) and 0xff) + ((c shr 8) and 0xff) + (c and 0xff)) / 765.0
        }
        ImageIO.write(pic, "png", File(out, "eye_$name.png"))
        return "eye ${"%.3f".format(a / (cols * rows))} real ${"%.3f".format(b / (cols * rows))}"
    }

    /** How far the painting's middle (where the cover is) is from the cover, mean per channel 0 … 1. */
    private fun distance(img: org.jetbrains.skia.Image, cover: ArtCover): Float {
        val bmp = org.jetbrains.skia.Bitmap()
        bmp.allocN32Pixels(img.width, img.height)
        img.readPixels(bmp, 0, 0)
        val side = minOf(img.width, img.height) * 0.94f
        val l = (img.width - side) / 2
        val t = (img.height - side) / 2
        var sum = 0.0
        var n = 0
        for (j in 0 until 24) for (i in 0 until 24) {
            val c = bmp.getColor((l + (i + 0.5f) * side / 24).toInt(), (t + (j + 0.5f) * side / 24).toInt())
            val p = cover.argb[((j + 0.5f) * cover.h / 24).toInt() * cover.w + ((i + 0.5f) * cover.w / 24).toInt()]
            for (sh in intArrayOf(16, 8, 0)) sum += kotlin.math.abs(((c shr sh) and 0xff) - ((p shr sh) and 0xff)) / 255.0
            n += 3
        }
        return (sum / n).toFloat()
    }

    @Test
    fun theCoverComesOut() {
        val out = File("build/art-cover").apply { mkdirs() }
        val cover = madeCover()
        val report = StringBuilder()
        for (style in listOf(ArtStyle.WATERCOLOUR, ArtStyle.OIL, ArtStyle.INK, ArtStyle.PEN)) {
            val s = song("made-$style", cover, 60_000L)
            val guided = paint(s, style, ArtSubject.COVER, 0.5f, 60_000.0, 1280, 720)
            val free = paint(s, style, ArtSubject.FREE, 0.5f, 60_000.0, 1280, 720)
            val dg = distance(guided, cover)
            val df = distance(free, cover)
            report.append("$style cover=$dg free=$df\n")
            File(out, "made_$style.png").writeBytes(guided.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            if (style != ArtStyle.INK && style != ArtStyle.PEN) assertTrue("$style: the cover does not come out ($dg vs free $df)", dg < df * 0.8f)
        }
        File(out, "report.txt").writeText(report.toString())
    }

    @Test
    fun theFileSaysWhoPaintedIt() {
        val img = paint(song("words", madeCover(), 30_000L, "Ça plane pour moi"), ArtStyle.OIL, ArtSubject.COVER, 1f, 30_000.0, 640, 360)
        val png = img.encodeToData(EncodedImageFormat.PNG)!!.bytes
        val kept = com.sadurni.attacca.art.PngWords.with(png, com.sadurni.attacca.art.PngWords.forPainting("Ça plane pour moi", "Plastic", null, 1_790_000_000_000L))
        val out = File("build/art-cover").apply { mkdirs() }
        File(out, "words.png").writeBytes(kept)
        // Still a picture, and its words readable.
        val back = ImageIO.read(java.io.ByteArrayInputStream(kept))
        assertTrue(back.width == 640)
        val text = String(kept, Charsets.UTF_8)
        assertTrue(text.contains("Created by Art-tacca (Attacca) 2026, painting Ça plane pour moi · Plastic"))
        val reader = ImageIO.getImageReadersByFormatName("png").next()
        reader.input = ImageIO.createImageInputStream(java.io.ByteArrayInputStream(kept))
        val meta = reader.getImageMetadata(0).getAsTree("javax_imageio_png_1.0") as org.w3c.dom.Element
        val itxt = meta.getElementsByTagName("iTXtEntry")
        val found = (0 until itxt.length).map { itxt.item(it) as org.w3c.dom.Element }.associate { it.getAttribute("keyword") to it.getAttribute("text") }
        assertTrue(found.toString(), found["Software"] == "Art-tacca (Attacca)" && found["Title"] == "Ça plane pour moi")
    }

    @Test
    fun withoutACoverARecord() {
        val out = File("build/art-cover").apply { mkdirs() }
        for ((k, style) in listOf(ArtStyle.WATERCOLOUR, ArtStyle.OIL, ArtStyle.INK, ArtStyle.PEN).withIndex()) {
            val s = song("no-cover-$k", null, 120_000L)
            val img = paint(s, style, ArtSubject.COVER, 0.6f, 120_000.0, 1280, 720)
            File(out, "record_$style.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            val rec = com.sadurni.attacca.art.ArtCover.record(s)
            assertTrue("$style: no record painted (${distance(img, rec)})", distance(img, rec) < (if (style == ArtStyle.PEN) 0.36f else 0.3f))
        }
    }

    @Test
    fun realCovers() {
        val list = System.getenv("ART_COVERS") ?: return
        val files = list.split(';').map { File(it) }.flatMap { if (it.isDirectory) it.listFiles()!!.filter { f -> f.name.endsWith(".jpg") || f.name.endsWith(".png") } else listOf(it) }
        val out = File("build/art-cover").apply { mkdirs() }
        val styles = (System.getenv("ART_STYLES") ?: "WATERCOLOUR,OIL,INK,PEN").split(',').map { ArtStyle.valueOf(it) }
        val likeness = (System.getenv("ART_LIKENESS") ?: "0.5").toFloat()
        val length = (System.getenv("ART_TEST_MS") ?: "200000").toDouble()
        val (w, h) = (System.getenv("ART_SIZE") ?: "1280x720").split('x').map { it.toInt() }
        for ((k, f) in files.withIndex()) {
            val cover = coverOf(f)
            for (style in styles) {
                val t0 = System.nanoTime()
                val img = paint(song("real-$k-${f.parentFile.name}", cover, length.toLong()), style, ArtSubject.COVER, likeness, length, w, h)
                val ms = (System.nanoTime() - t0) / 1_000_000
                File(out, "real_${k}_${style}_${(likeness * 100).toInt()}.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
                println("cover $k $style ${ms}ms d=${distance(img, cover)} ${eyeCheck(img, "${k}_$style", out)}")
            }
        }
    }
}
