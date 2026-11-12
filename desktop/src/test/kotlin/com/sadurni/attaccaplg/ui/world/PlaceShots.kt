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
    if (args[1] == "COSTS") {
        // What a new song's land costs to set up (ms), part by part, a few times (warm JIT last).
        val bins = FloatArray(1200) { i -> (0.45f + 0.3f * sin(i * 0.05f) + 0.15f * sin(i * 0.31f)).coerceIn(0.05f, 1f) }
        val wave = SongWave(bins, 240_000L, 0L, 240_000L)
        for (round in 0 until 4) for (w in listOf(World.MEADOW, World.DESERT, World.CITY, World.CASTLE)) {
            val look = Looks.inWorld(Looks.of(SongFacts(mood = Mood.CALM, energy = 0.5f, positivity = 0.6f, voice = 0.4f, seed = round * 7 + w.ordinal)), w)
            var t = System.nanoTime()
            fun lap(): Double { val n = System.nanoTime(); val d = (n - t) / 1e6; t = n; return d }
            val land = WorldLand(wave, look, round * 13 + w.ordinal, true, null, look, 0.0)
            val tLand = lap()
            land.places.size
            val tPlaces = lap()
            land.heightAt(0f, 10f); land.lookBlend(10f)
            val tFirst = lap()
            val cast = com.sadurni.attacca.world.Cast(land, look.traveller, 0, round, false)
            val tCast = lap()
            val ex = com.sadurni.attacca.world.Extras(land, round)
            val tEx = lap()
            for (k in -1..1) com.sadurni.attacca.world.LandMesher.chunk(land, k)
            val tChunks = lap()
            val draws = ArrayList<com.sadurni.attacca.world.Draw>()
            cast.frame(2f, 0.0, true, 1000L, draws)
            ex.frame(2f, land.pathAt(2f), 1000L, true, 0f, null, look.palette.accent, true, draws)
            val tFrame = lap()
            println("round $round ${w.name}: land %.1f places %.1f first %.1f cast %.1f extras %.1f 3chunks %.1f firstframe %.1f".format(tLand, tPlaces, tFirst, tCast, tEx, tChunks, tFrame))
        }
        return
    }
    if (args[1] == "SWITCH") {
        // One song into the next in the same picture: SWITCH A B skipAtSeconds (0: to its end).
        val wa = World.valueOf(args[2])
        val wb = World.valueOf(args[3])
        val skipAt = args.getOrNull(4)?.toDoubleOrNull() ?: 0.0
        val binsA = FloatArray(600) { i -> (0.45f + 0.3f * sin(i * 0.05f) + 0.15f * sin(i * 0.31f)).coerceIn(0.05f, 1f) }
        // B starts quietly (a quiet stretch's world first), then grows loud.
        val binsB = FloatArray(600) { i -> (if (i < 160) 0.08f else 0.7f) + 0.05f * sin(i * 0.07f) }
        val waveA = SongWave(binsA, 120_000L, 0L, 120_000L)
        val waveB = SongWave(binsB, 120_000L, 0L, 120_000L)
        val lookA = Looks.inWorld(Looks.of(SongFacts(mood = Mood.CALM, energy = 0.5f, positivity = 0.6f, voice = 0.4f, seed = 11)), wa)
        val lookB = Looks.inWorld(Looks.of(SongFacts(mood = Mood.CALM, energy = 0.2f, positivity = 0.6f, voice = 0.4f, seed = 12)), wb)
        val input = WorldInput()
        input.songId = "a"; input.look = lookA; input.wave = waveA; input.nextLook = if ("mispredict" in args) Looks.inWorld(lookB, World.ICE) else com.sadurni.attacca.diorama.Diorama.startOf(lookB, waveB); input.hasNext = true; input.playing = true
        val camera = WorldCamera()
        val soft = SoftWorld(input, camera)
        val (w, h) = 800 to 450
        fun shot(name: String) {
            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            img.setRGB(0, 0, w, h, soft.pixels, 0, w)
            ImageIO.write(img, "png", File(out, "$name.png"))
        }
        val endA = if (skipAt > 0) skipAt * 1000 else 120_000.0
        val wantA = listOf(endA - 6000, endA - 70)
        var t = endA - 9000
        var k = 0
        while (t < endA) {
            input.positionMs = t.toLong(); input.positionAtNs = System.nanoTime()
            soft.frame(w, h)
            if (wantA.any { kotlin.math.abs(it - t) < 17 }) shot("switch-a-%02d".format(k++))
            t += 33.0
            Thread.sleep(2)
        }
        synchronized(input) {
            input.songId = "b"; input.look = lookB; input.wave = waveB; input.nextLook = null; input.hasNext = false
            input.positionMs = 0L; input.positionAtNs = System.nanoTime()
        }
        val wantB = listOf(0.0, 300.0, 1500.0, 4000.0, 9000.0, 16000.0)
        t = 0.0
        k = 0
        while (t < 16_100.0) {
            input.positionMs = if ("stale" in args && t < 400) 118_000L else t.toLong(); input.positionAtNs = System.nanoTime()
            soft.frame(w, h)
            if (wantB.any { kotlin.math.abs(it - t) < 17 }) shot("switch-b-%02d".format(k++))
            if (k == 1 && kotlin.math.abs(t - 33.0) < 1) println("B starts: " + (SoftWorld::class.java.getDeclaredField("land").also { it.isAccessible = true }.get(soft) as WorldLand).describeStart())
            t += 33.0
            Thread.sleep(2)
        }
        soft.close()
        return
    }
    if (args[1] == "DRAGONCHECK") {
        // The Great Wall's peak dragon alone on a plain sky, its head in the middle.
        val soft = SoftWorld(WorldInput(), WorldCamera())
        fun set(n: String, v: Any) = SoftWorld::class.java.getDeclaredField(n).also { it.isAccessible = true }.set(soft, v)
        set("peakNow", 1f); set("peakKind", 5f)
        val m = SoftWorld::class.java.declaredMethods.first { it.name.startsWith("peakShow") }.also { it.isAccessible = true }
        val w = 640; val h = 320
        val img = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) for (x in 0 until w) {
            val nx = -0.25f + 0.6f * x / w
            val ny = 0.75f - 0.3f * y / h
            val c = m.invoke(soft, nx, ny, 0f, 1f, floatArrayOf(0f, 0f, 1f), floatArrayOf(-1f, 0f, 0f), floatArrayOf(0f, 1f, 0f), 0.6f, 4.5f, 0.55f, 0.75f, 0.95f) as FloatArray
            fun ch(v: Float) = (v.coerceIn(0f, 1f) * 255).toInt()
            img.setRGB(x, y, (ch(c[0]) shl 16) or (ch(c[1]) shl 8) or ch(c[2]))
        }
        javax.imageio.ImageIO.write(img, "png", File(out, "dragon.png"))
        return
    }
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
        world, if ("walker" in flags) com.sadurni.attacca.diorama.Traveller.WALKER else base.traveller, base.palette,
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
        // Festive days (xmas, kings, diwali, eid, lunar) and the sun as a record (record).
        input.festive = when {
            "xmas" in flags || "kings" in flags -> com.sadurni.attacca.festive.Festivity.CHRISTMAS
            "diwali" in flags -> com.sadurni.attacca.festive.Festivity.DIWALI
            "eid" in flags -> com.sadurni.attacca.festive.Festivity.EID
            "lunar" in flags -> com.sadurni.attacca.festive.Festivity.LUNAR_NEW_YEAR
            else -> null
        }
        input.kings = "kings" in flags
        if ("record" in flags) input.recordOf = input.songId
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
