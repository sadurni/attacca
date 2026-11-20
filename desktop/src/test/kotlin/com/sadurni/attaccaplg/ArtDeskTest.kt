package com.sadurni.attaccaplg

import com.sadurni.attacca.art.ArtSong
import com.sadurni.attacca.art.ArtStyle
import com.sadurni.attacca.diorama.SongWave
import com.sadurni.attaccaplg.ui.art.ArtDeskCanvas
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Art-tacca on the desktop: each style paints (its shaders build) and the painting is not blank. */
class ArtDeskTest {
    private fun song(id: String): ArtSong {
        val n = 600
        val wave = FloatArray(n) { i -> (0.35f + 0.5f * kotlin.math.sin(i / 40f).let { it * it }) }
        return ArtSong(id, 9 + 12, 0.7f, 0.6f, 0.4f, 0.5f, 0.7f, 120f, 0.7f, SongWave(wave, 180_000L, 0L, 180_000L), intArrayOf(0xFF8A5030.toInt(), 0xFF2050A0.toInt()))
    }

    private val LENGTH_MS = (System.getenv("ART_TEST_MS") ?: "40000").toDouble()

    @Test
    fun everyStylePaints() {
        val out: File? = File("build/art-test").apply { mkdirs() }
        for (style in listOf(ArtStyle.WATERCOLOUR, ArtStyle.OIL, ArtStyle.INK, ArtStyle.PEN)) {
            val c = ArtDeskCanvas()
            val s = song("song-$style")
            var img: org.jetbrains.skia.Image? = null
            var now = 1_000_000L
            // Some of a song (20 s, or ART_TEST_MS), a frame every 33 ms, with beats.
            var songMs = 0.0
            val times = StringBuilder()
            var slow = 0L
            var frames = 0
            val t0 = System.nanoTime()
            while (songMs < LENGTH_MS) {
                val ft = System.nanoTime()
                val beat = (songMs % 500.0) < 40.0
                img = c.frame(s, songMs, true, 0.6f, if (beat) 0.9f else 0.4f, 0.3f, (now - (songMs % 500.0).toLong()), null, now, style, false, 1280, 720) { _, _ -> }
                val took = (System.nanoTime() - ft) / 1_000_000L
                if (took > slow) slow = took
                frames++
                if (frames % 30 == 0) times.append("${songMs.toInt() / 1000}s ${took}ms wet=${c.painter.wet.size}\n")
                now += 33
                songMs += 33.0
            }
            out?.let { File(it, "times_$style.txt").writeText("avg ${(System.nanoTime() - t0) / 1_000_000L / frames}ms max ${slow}ms\n$times") }
            assertNotNull(img)
            val bmp = org.jetbrains.skia.Bitmap()
            bmp.allocN32Pixels(img!!.width, img.height)
            img.readPixels(bmp, 0, 0)
            var differ = 0
            val first = bmp.getColor(0, 0)
            for (y in 0 until img.height step 8) for (x in 0 until img.width step 8) if (bmp.getColor(x, y) != first) differ++
            assertTrue("$style painted nothing", differ > 200)
            assertTrue("$style landscape", img.width > img.height)
            out?.let { File(it, "art_$style.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes) }
        }
    }
}
