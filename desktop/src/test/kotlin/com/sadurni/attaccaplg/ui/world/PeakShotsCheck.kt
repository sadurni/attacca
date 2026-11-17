package com.sadurni.attaccaplg.ui.world

import com.sadurni.attacca.chain.Mood
import com.sadurni.attacca.diorama.Looks
import com.sadurni.attacca.diorama.SongFacts
import com.sadurni.attacca.diorama.SongWave
import com.sadurni.attacca.diorama.World
import com.sadurni.attacca.world.WorldCamera
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sin

/**
 * Development (PEAK_SHOTS=<folder>): the song's peak over the castles (the red moon) and in the
 * desert (the pyramid), behind the traveller and through the eyes, and the submarine through the
 * eyes under the sea; frames to PNG and, for the moon, how long it is in sight during the peak.
 */
class PeakShotsCheck {
    private val bins = FloatArray(600) { i -> (0.45f + 0.3f * sin(i * 0.05f) + 0.15f * sin(i * 0.31f)).coerceIn(0.05f, 1f) }

    @Test
    fun shots() {
        val out = System.getenv("PEAK_SHOTS")?.let(::File)
        assumeTrue(out != null)
        out!!.mkdirs()
        val report = StringBuilder()
        for ((name, eyes) in listOf("CASTLE" to false, "CASTLE" to true, "DESERT" to false, "DESERT" to true, "REEF" to true, "REEF" to false)) {
            val world = World.valueOf(name)
            val look = Looks.inWorld(Looks.of(SongFacts(mood = Mood.CALM, energy = 0.5f, positivity = 0.6f, voice = 0.4f, seed = name.hashCode())), world)
            val input = WorldInput()
            input.wave = SongWave(bins, 300_000L, 0L, 300_000L)
            input.look = look
            input.songId = "song-$name"
            input.playing = true
            if (name != "REEF") input.peakMs = 60_000.0
            val camera = WorldCamera().also { it.firstPerson = eyes }
            val soft = SoftWorld(input, camera)
            val (w, h) = 640 to 360
            val tag = "${name.lowercase()}-${if (eyes) "eyes" else "behind"}"
            var peakFrames = 0
            var moonInSight = 0
            val from = if (name == "REEF") 30_000L else 50_000L
            val to = if (name == "REEF") 50_000L else 72_000L
            var k = 0
            var ms = from
            while (ms <= to) {
                input.positionMs = ms
                input.positionAtNs = System.nanoTime()
                soft.frame(w, h)
                val d = abs(ms - 60_000L)
                val peak = if (name == "REEF") 0f else if (d < 3000) 1f else if (d < 8000) 1 - (d - 3000) / 5000f else 0f
                if (peak > 0.05f) {
                    peakFrames++
                    val lookYaw = atan2(camera.atX - camera.eyeX, camera.atZ - camera.eyeZ)
                    var diff = lookYaw - atan2(-0.2f, 1f)
                    while (diff > PI) diff -= (2 * PI).toFloat()
                    while (diff < -PI) diff += (2 * PI).toFloat()
                    if (abs(diff) < 0.55f) moonInSight++
                }
                if (k % 60 == 0 && name == "REEF") {
                    val cast = SoftWorld::class.java.getDeclaredField("cast").apply { isAccessible = true }.get(soft) as com.sadurni.attacca.world.Cast?
                    val land = SoftWorld::class.java.getDeclaredField("land").apply { isAccessible = true }.get(soft) as com.sadurni.attacca.world.WorldLand?
                    report.append("$tag ${ms / 1000}s: eye y %.2f, traveller eye %s, underwater %s, floor %.2f\n".format(
                        camera.eyeY, cast?.eye?.joinToString(",") { "%.2f".format(it) }, cast?.underwater, land?.heightAt(camera.eyeX, camera.eyeZ) ?: Float.NaN))
                }
                if (k % 60 == 0) {
                    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
                    img.setRGB(0, 0, w, h, soft.pixels, 0, w)
                    ImageIO.write(img, "png", File(out, "$tag-${ms / 1000}s.png"))
                }
                k++
                ms += 33L
            }
            if (peakFrames > 0) report.append("$tag: looking towards the moon in %d of %d peak frames (%.0f %%)\n".format(moonInSight, peakFrames, 100.0 * moonInSight / peakFrames))
            soft.close()
        }
        File(out, "report.txt").writeText(report.toString())
        println(report)
    }
}
