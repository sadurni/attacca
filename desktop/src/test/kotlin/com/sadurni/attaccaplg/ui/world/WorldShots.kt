package com.sadurni.attaccaplg.ui.world

import com.sadurni.attacca.chain.Mood
import com.sadurni.attacca.diorama.Looks
import com.sadurni.attacca.diorama.SongFacts
import com.sadurni.attacca.diorama.SongWave
import com.sadurni.attacca.diorama.World
import com.sadurni.attacca.world.WorldCamera
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.sin

/**
 * Development: Attacca World on the desktop drawn to PNG files without a screen (a made-up song
 * shape), with the time each frame took. Args: output folder, then optionally worlds (MEADOW, DESERT…).
 */
fun main(args: Array<String>) {
    val out = File(args.getOrElse(0) { "build/world-shots" }).apply { mkdirs() }
    val worlds = args.drop(1).ifEmpty { listOf("MEADOW", "DESERT", "COAST", "REEF") }
    val bins = FloatArray(600) { i -> (0.45f + 0.3f * sin(i * 0.05f) + 0.15f * sin(i * 0.31f)).coerceIn(0.05f, 1f) }
    val wave = SongWave(bins, 300_000L, 0L, 300_000L)
    for (name in worlds) {
        val world = World.valueOf(name)
        val look = Looks.inWorld(Looks.of(SongFacts(mood = Mood.CALM, energy = 0.5f, positivity = 0.6f, voice = 0.4f, seed = name.hashCode())), world)
        val input = WorldInput()
        input.wave = wave
        input.look = look
        input.songId = "song-$name"
        input.playing = true
        val camera = WorldCamera()
        val soft = SoftWorld(input, camera)
        val (w, h) = 800 to 450
        var total = 0L
        var frames = 0
        // Some seconds into the song, frame after frame so the land around is made.
        for (k in 0 until 90) {
            input.positionMs = 40_000L + k * 33L
            input.positionAtNs = System.nanoTime()
            val t0 = System.nanoTime()
            soft.frame(w, h)
            if (k >= 30) {
                total += System.nanoTime() - t0
                frames++
            }
            if (k == 89 || k == 45) {
                val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
                img.setRGB(0, 0, w, h, soft.pixels, 0, w)
                ImageIO.write(img, "png", File(out, "world-${name.lowercase()}-$k.png"))
            }
            Thread.sleep(5)
        }
        println("$name: ${total / frames / 1_000_000.0} ms a frame (${w}x$h), ${soft.triangles} triangles; sky/land/figures/fill ms: " + soft.timings.joinToString(" ") { "%.1f".format(it / 90 / 1e6) })
        soft.close()
    }
    println("shots in ${out.absolutePath}")
}
