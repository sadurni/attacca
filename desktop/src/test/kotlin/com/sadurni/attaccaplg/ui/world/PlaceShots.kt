package com.sadurni.attaccaplg.ui.world

import com.sadurni.attacca.chain.Mood
import com.sadurni.attacca.diorama.Looks
import com.sadurni.attacca.diorama.SongFacts
import com.sadurni.attacca.diorama.SongWave
import com.sadurni.attacca.diorama.World
import com.sadurni.attacca.world.WorldCamera
import com.sadurni.attacca.world.WorldLand
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.sin

/**
 * Development: Attacca World's places and moments drawn to PNG files without a screen. Args:
 * output folder, world, next world (or "-"), origin ms, then the moments (seconds into the song)
 * to show; "places" as a moment shows a few seconds before each place.
 */
fun main(args: Array<String>) {
    val out = File(args[0]).apply { mkdirs() }
    if (args[1] == "RAINBOWCHECK") {
        val soft = SoftWorld(WorldInput(), WorldCamera())
        fun set(n: String, v: Any) = SoftWorld::class.java.getDeclaredField(n).also { it.isAccessible = true }.set(soft, v)
        set("peakNow", 1f); set("peakKind", 4f)
        val m = SoftWorld::class.java.declaredMethods.first { it.name.startsWith("peakShow") }.also { it.isAccessible = true }
        for (k in 0..20) {
            val ny = -0.2f + k * 0.06f
            val c = m.invoke(soft, -0.15f, ny, -0.3f, 1.6f, floatArrayOf(0f, 0f, 1f), floatArrayOf(-1f, 0f, 0f), floatArrayOf(0f, 1f, 0f), 0.6f, 1f, 0.5f, 0.6f, 0.9f) as FloatArray
            println("ny %.2f -> %.2f %.2f %.2f".format(ny, c[0], c[1], c[2]))
        }
        return
    }
    // The world, maybe with weather: MEADOW:rain,night,peak (also snow, storm, cloud).
    val world = World.valueOf(args[1].substringBefore(':'))
    val flags = args[1].substringAfter(':', "")
    val next = args[2].takeIf { it != "-" }?.let { World.valueOf(it) }
    val origin = args[3].toDouble()
    val bins = FloatArray(600) { i -> (0.45f + 0.3f * sin(i * 0.05f) + 0.15f * sin(i * 0.31f)).coerceIn(0.05f, 1f) }
    val wave = SongWave(bins, 300_000L, 0L, 300_000L)
    val base = Looks.inWorld(Looks.of(SongFacts(mood = Mood.CALM, energy = 0.5f, positivity = 0.6f, voice = 0.4f, seed = world.name.hashCode())), world)
    val look = if (flags.isEmpty()) base else com.sadurni.attacca.diorama.SongLook(
        world, base.traveller, base.palette,
        if ("night" in flags) 0.05f else base.sun, if ("cloud" in flags || "storm" in flags) 0.8f else base.clouds,
        if ("rain" in flags) 0.9f else 0f, if ("storm" in flags) 0.9f else 0f, if ("snow" in flags) 0.9f else 0f,
        base.bpm, base.pulse, base.energy,
    )
    // A made-up cover: bands of colour with a circle.
    val coverImg = BufferedImage(160, 160, BufferedImage.TYPE_INT_RGB).also { im ->
        val g = im.createGraphics()
        for (y in 0 until 160) { g.color = java.awt.Color.getHSBColor(y / 160f, 0.6f, 0.9f); g.drawLine(0, y, 160, y) }
        g.color = java.awt.Color.WHITE; g.fillOval(40, 40, 80, 80); g.dispose()
    }
    val nextLook = next?.let { Looks.inWorld(Looks.of(SongFacts(mood = Mood.CALM, energy = 0.6f, positivity = 0.7f, voice = 0.4f, seed = it.name.hashCode() + 3)), it) }
    val moments = ArrayList<Double>()
    for (a in args.drop(4)) {
        if (a == "places") {
            val land = WorldLand(wave, look, ("song-" + world.name).hashCode(), true, null, nextLook, origin)
            for (p in land.places) {
                println("place ${p.kind} rows ${p.z0}..${p.z1} side ${p.side} at ${p.z0 / 4f} s")
                moments += (p.z0 / 4.0 - 4.0)
                moments += (p.z0 / 4.0 + (p.z1 - p.z0) / 8.0)
            }
        } else moments += a.toDouble()
    }
    for (sec in moments) {
        val input = WorldInput()
        input.wave = wave
        input.look = look
        input.nextLook = nextLook
        input.hasNext = nextLook != null
        input.originMs = origin
        input.songId = "song-" + world.name
        input.playing = true
        input.cover = Boards.of(coverImg)
        input.sign = Boards.sign("7/23  Next Song Title")
        if ("peak" in flags) input.peakMs = sec * 1000
        if ("late" in flags) input.peakMs = sec * 1000 - 5800
        val camera = WorldCamera()
        val soft = SoftWorld(input, camera)
        val (w, h) = 800 to 450
        val from = (sec * 1000).toLong() - 2500L
        for (k in 0 until 80) {
            input.positionMs = from + k * 33L
            input.positionAtNs = System.nanoTime()
            soft.frame(w, h)
            Thread.sleep(4)
        }
        fun field(n: String) = SoftWorld::class.java.getDeclaredField(n).also { it.isAccessible = true }.get(soft)
        println("peak ${field("peakNow")} kind ${field("peakKind")} under ${field("under")}")
        println("at $sec s: eye ${camera.eyeX} ${camera.eyeY} ${camera.eyeZ} at ${camera.atX} ${camera.atY} ${camera.atZ} px ${Integer.toHexString(soft.pixels[10])} ${Integer.toHexString(soft.pixels[w * h / 2 + w / 2])} tris ${soft.triangles}")
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        img.setRGB(0, 0, w, h, soft.pixels, 0, w)
        ImageIO.write(img, "png", File(out, "${world.name.lowercase()}-${"%05.1f".format(sec).replace(',', '.')}.png"))
        soft.close()
    }
    println("shots in ${out.absolutePath}")
}
