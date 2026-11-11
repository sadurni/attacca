package com.sadurni.attaccaplg.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import com.sadurni.attacca.chain.FamilyName
import com.sadurni.attacca.chain.Mood
import com.sadurni.attacca.chain.Trait
import com.sadurni.attacca.diorama.Diorama
import com.sadurni.attacca.diorama.DrawList
import com.sadurni.attacca.diorama.Looks
import com.sadurni.attacca.diorama.SongFacts
import com.sadurni.attacca.diorama.SongWave
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * Development: Attacca O'rama's worlds drawn without a screen, upright and wide, with a made-up
 * song shape and levels, and a frame halfway through a change of track. Args: output folder, and
 * optionally the start of the names of the shots to make (the others are skipped).
 */
fun main(args: Array<String>) {
    val out = File(args.getOrElse(0) { "build/orama-shots" }).apply { mkdirs() }
    only = args.getOrNull(1)
    val looks = linkedMapOf(
        "castle" to SongFacts(mood = Mood.TENSE, energy = 0.85f, positivity = 0.15f, voice = 0.5f, family = FamilyName(listOf(Trait.BASS to true, Trait.DISTORTION to true))),
        "city" to SongFacts(mood = Mood.ENERGETIC, energy = 0.8f, positivity = 0.7f, voice = 0.8f),
        "neon" to SongFacts(mood = Mood.ENERGETIC, energy = 0.85f, positivity = 0.6f, voice = 0.2f),
        "coast" to SongFacts(mood = Mood.CHEERFUL, energy = 0.55f, positivity = 0.9f, voice = 0.7f),
        "meadow" to SongFacts(mood = Mood.CALM, energy = 0.35f, positivity = 0.65f, voice = 0.4f),
        "meadow-rain" to SongFacts(mood = Mood.MELANCHOLIC, energy = 0.5f, positivity = 0.1f, voice = 0.6f, family = FamilyName(listOf(Trait.PERCUSSION to true))),
        "ice" to SongFacts(mood = Mood.MELANCHOLIC, energy = 0.25f, positivity = 0.2f, voice = 0.3f),
        "space" to SongFacts(mood = Mood.CALM, energy = 0.08f, positivity = 0.5f, voice = 0.1f),
        "w-desert" to SongFacts(mood = Mood.CALM, energy = 0.45f, positivity = 0.6f, voice = 0.4f, genre = "arabic"),
        "w-wall" to SongFacts(mood = Mood.MELANCHOLIC, energy = 0.5f, positivity = 0.6f, voice = 0.4f, genre = "chinese"),
        "w-jungle" to SongFacts(mood = Mood.CHEERFUL, energy = 0.6f, positivity = 0.6f, voice = 0.4f, genre = "bollywood"),
        "w-amazon" to SongFacts(mood = Mood.ENERGETIC, energy = 0.7f, positivity = 0.6f, voice = 0.4f, genre = "brazil"),
        "w-canyon" to SongFacts(mood = Mood.ENERGETIC, energy = 0.7f, positivity = 0.6f, voice = 0.4f, genre = "country"),
        "w-japan" to SongFacts(mood = Mood.CHEERFUL, energy = 0.55f, positivity = 0.6f, voice = 0.4f, genre = "j-pop"),
        "w-reef" to SongFacts(mood = Mood.CALM, energy = 0.35f, positivity = 0.6f, voice = 0.4f, genre = "ambient"),
        "w-volcano" to SongFacts(mood = Mood.TENSE, energy = 0.85f, positivity = 0.6f, voice = 0.4f, genre = "metal"),
        "unknown" to SongFacts(),
        // The meadow taking the made-up cover's colours.
        "meadow-cover" to SongFacts(mood = Mood.CALM, energy = 0.35f, positivity = 0.65f, voice = 0.4f, cover = coverPixels(), accent = 0xFFFF8A2A.toInt()),
    )
    val wave = songShape(Random(7))
    val nextWave = songShape(Random(11))
    val sizes = listOf("tall" to (540 to 900), "wide" to (1280 to 640))
    for ((name, facts) in looks) {
        for ((form, wh) in sizes) {
            // A few moments apart: other visitors pass, other things stand by the way.
            for ((k, at) in (if (form == "wide") listOf(5_000L, 27_000L, 49_000L) else listOf(16_000L)).withIndex()) {
                val d = Diorama()
                d.show(Diorama.Song(name, Looks.of(facts), SongWave(wave, 240_000, 2_000, 236_000)), SongWave(nextWave, 200_000, 1_000, 199_000), at)
                shot(File(out, "$name-$form${if (k == 0) "" else "-$k"}.png"), wh.first, wh.second, d, 60_000L + at * 3, startMs = at)
            }
        }
    }
    // Halfway through the change from the city to the ice.
    val d = Diorama()
    d.show(Diorama.Song("a", Looks.of(looks.getValue("city")), SongWave(wave, 240_000, 2_000, 236_000)), SongWave(nextWave, 200_000, 1_000, 199_000), 0)
    d.show(Diorama.Song("b", Looks.of(looks.getValue("ice")), SongWave(nextWave, 200_000, 1_000, 199_000)), null, 10_000)
    shot(File(out, "change-wide.png"), 1280, 640, d, 2_000L, startMs = 10_000)
    // A track with a quiet first minute, then loud: the meadow's quiet stretch is the ice, its loud one the city.
    val dynamic = FloatArray(512) { i -> if (i < 128) 0.12f + 0.03f * sin(i * 0.3).toFloat() else if (i < 300) 0.5f + 0.05f * sin(i * 0.2).toFloat() else 0.95f }
    for ((name, at) in listOf("stretch-quiet" to 30_000L, "stretch-own" to 120_000L, "stretch-loud" to 200_000L)) {
        val d = Diorama()
        d.show(Diorama.Song("dyn", Looks.of(looks.getValue("meadow")), SongWave(dynamic, 240_000, 0, 240_000)), null, 0)
        // Played up to there, so the stretch's world has come.
        shot(File(out, "$name.png"), 1280, 640, d, at, startMs = 0, warm = 600)
    }
    // The peak of a track in each world: what happens in its sky, standing out.
    for (name in listOf("meadow", "neon", "castle", "ice", "space", "w-desert", "w-wall", "w-jungle", "w-amazon", "w-canyon", "w-japan", "w-reef", "w-volcano")) {
        val song = Diorama.Song("peak-$name", Looks.of(looks.getValue(name)), SongWave(wave, 240_000, 2_000, 236_000))
        val at = song.peakMs.toLong().coerceAtLeast(0L)
        for ((k, into) in listOf(2_500L, 5_000L).withIndex()) {
            val d = Diorama()
            d.show(song, null, 0)
            shot(File(out, "peak-$name-$k.png"), 960, 480, d, at + into, startMs = 30_000L + k * 700L)
        }
        // Upright, as the phone shows it (the land lifted over the floating player).
        val d = Diorama()
        d.show(song, null, 0)
        shot(File(out, "peak-tall-$name.png"), 540, 1170, d, at + 4_000L, startMs = 30_000L, lift = 320f)
    }
    // A ride through whole tracks, a frame every 15 s: places, the peak, company, the end.
    for (name in listOf("meadow", "city", "neon", "castle", "ice", "space")) {
        for (k in 0 until 16) {
            val d = Diorama()
            d.show(Diorama.Song("tour-$name", Looks.of(looks.getValue(name)), SongWave(wave, 240_000, 2_000, 236_000)), SongWave(nextWave, 200_000, 1_000, 199_000), 0, "Bicycle Race")
            shot(File(out, "tour-$name-${"%02d".format(k)}.png"), 960, 480, d, k * 15_000L + 4_000L, startMs = 100_000L + k * 977L)
        }
    }
    // A strip of O'rama as Now playing shows it in place of the waveform (wide and low).
    for (name in listOf("meadow", "city", "castle", "coast", "ice", "space")) {
        val d = Diorama()
        d.show(Diorama.Song("strip-$name", Looks.of(looks.getValue(name)), SongWave(wave, 240_000, 2_000, 236_000)), null, 0)
        // Drawn taller than the strip (as the app does) and the lower part shown: the way, the traveller and some sky.
        shot(File(out, "strip-$name.png"), 1008, 420, d, 47_000L, startMs = 3_000L, warm = 150, stripTop = 98f, stripHeight = 280f)
    }
    // The meadow in a strip at loud and quiet moments (the ground high and low).
    for ((k, at) in listOf(20_000L, 70_000L, 120_000L, 180_000L).withIndex()) {
        val d = Diorama()
        d.show(Diorama.Song("strip-way", Looks.of(looks.getValue("meadow")), SongWave(wave, 240_000, 2_000, 236_000)), null, 0)
        shot(File(out, "strip-way-$k.png"), 1008, 420, d, at, startMs = 3_000L, warm = 150, stripTop = 98f, stripHeight = 280f)
    }
    // What flies over a strip (balloons, airships, geese…) at many moments: each thing whole, not squashed.
    for (name in listOf("meadow", "city")) for (k in 0 until 10) {
        val d = Diorama()
        d.show(Diorama.Song("strip-sky-$name", Looks.of(looks.getValue(name)), SongWave(wave, 240_000, 2_000, 236_000)), null, 0)
        shot(File(out, "strip-sky-$name-$k.png"), 1008, 420, d, 15_000L + k * 21_000L, startMs = 3_000L, warm = 150, stripTop = 98f, stripHeight = 280f)
    }
    // The ship in a strip, at a few moments (it rises and falls with the music).
    for ((k, at) in listOf(12_000L, 60_000L, 100_000L, 150_000L).withIndex()) {
        val d = Diorama()
        d.show(Diorama.Song("strip-ship", Looks.of(looks.getValue("space")), SongWave(wave, 240_000, 2_000, 236_000)), null, 0)
        shot(File(out, "strip-ship-$k.png"), 1008, 420, d, at, startMs = 3_000L, warm = 150, stripTop = 98f, stripHeight = 280f)
    }
    // The festive looks (some days a year) and the new year's shows at midnight.
    for (f in com.sadurni.attacca.festive.Festivity.entries) {
        for (kings in if (f == com.sadurni.attacca.festive.Festivity.CHRISTMAS) listOf(false, true) else listOf(false)) {
            for ((world, at) in listOf("meadow" to 20_000L, "city" to 47_000L)) {
                val d = Diorama()
                d.festive(f, kings)
                d.show(Diorama.Song("fest-$world", Looks.of(looks.getValue(world)), SongWave(wave, 240_000, 2_000, 236_000)), SongWave(nextWave, 200_000, 1_000, 199_000), 0)
                // The sleigh, dragon and cranes cross the sky at the start of their cycles.
                shot(File(out, "festive-${f.name.lowercase()}${if (kings) "-kings" else ""}-$world.png"), 1280, 640, d, at, startMs = 3_000L, warm = 150)
            }
        }
    }
    val greetings = linkedMapOf(
        com.sadurni.attacca.festive.NewYear.GREGORIAN to "¡Feliz año nuevo 2027!",
        com.sadurni.attacca.festive.NewYear.LUNAR to "新年快乐 🐐",
        com.sadurni.attacca.festive.NewYear.HIJRI to "عام هجري سعيد 1449",
        com.sadurni.attacca.festive.NewYear.YENNAYER to "ⴰⵙⴳⴳⵯⴰⵙ ⴰⵎⴰⵢⵏⵓ 2977",
        com.sadurni.attacca.festive.NewYear.ENKUTATASH to "መልካም አዲስ ዓመት 2019",
        com.sadurni.attacca.festive.NewYear.VIKRAM to "नव वर्ष की शुभकामनाएँ 2083",
    )
    for ((kind, text) in greetings) {
        for ((k, elapsed) in listOf(6_000L, 40_000L).withIndex()) {
            val d = Diorama()
            d.celebrate(com.sadurni.attacca.diorama.Celebration(kind, elapsed, GreetingDots.of(text)))
            d.show(Diorama.Song("show", Looks.of(looks.getValue("city")), SongWave(wave, 240_000, 2_000, 236_000)), null, 0)
            shot(File(out, "newyear-${kind.name.lowercase()}-$k.png"), 1280, 640, d, 30_000L, startMs = 9_000L + k * 700L, warm = 100)
        }
    }
    // The end of a meadow track, the gate into each next world: ahead, then going through.
    val len = 234_000L
    for (next in listOf("castle", "meadow", "city", "neon", "coast", "ice", "space", "none")) {
        for ((k, before) in listOf(2_500L, 600L).withIndex()) {
            val d = Diorama()
            val nextLook = looks[next]?.let { Looks.of(it) }
            d.show(Diorama.Song("gate", Looks.of(looks.getValue("coast")), SongWave(wave, 240_000, 2_000, 236_000), "Made Up Album", "Some Artist"), SongWave(nextWave, 200_000, 1_000, 199_000), 0, "Bicycle Race", "7/23", nextLook)
            shot(File(out, "gate-$next-$k.png"), 960, 480, d, len - before - 2_000, startMs = 50_000)
        }
    }
    // Through the first tunnels of the meadow and the city (places come every 45 s of music, a
    // third into their stretch): the torch taken, carried and left, the car's lights.
    for (name in listOf("meadow", "city", "neon", "castle")) {
        val kinds = when (name) {
            "meadow" -> listOf("bridge", "tunnel", "village")
            "city" -> listOf("bridge", "tunnel", "station")
            "neon" -> listOf("wheel", "screen", "tunnel")
            else -> listOf("ruins", "bridge", "tunnel")
        }
        val slot = (0 until 12).firstOrNull { i -> Diorama.hash(i, 501) <= 0.8f && kinds[(Diorama.hash(i, 502) * 3).toInt().coerceAtMost(2)] == "tunnel" } ?: continue
        val start = slot * 45_000L + 13_500L
        for ((k, into) in listOf(-800L, 400L, 2_500L, 5_000L, 9_000L).withIndex()) {
            val d = Diorama()
            d.show(Diorama.Song("tunnel-$name", Looks.of(looks.getValue(name)), SongWave(wave, 240_000, 2_000, 236_000), "Made Up Album", "Some Artist"), SongWave(nextWave, 200_000, 1_000, 199_000), 0)
            shot(File(out, "tunnel-$name-$k.png"), 960, 480, d, start + into - 2_000, startMs = 50_000)
        }
    }
    // Through a village of the meadow and the coast (the traveller goes behind the houses), each traveller.
    for (name in listOf("meadow", "coast")) {
        val kinds = if (name == "meadow") listOf("bridge", "tunnel", "village") else listOf("wheel", "bridge", "village")
        val slot = (0 until 60).firstOrNull { i -> (Diorama.hash(i, 512) < 0.5f) == (name == "coast") && Diorama.hash(i, 501) <= 0.8f && kinds[(Diorama.hash(i, 502) * 3).toInt().coerceAtMost(2)] == "village" } ?: continue
        val start = slot * 45_000L + 13_500L
        for (seed in 0 until 3) {
            val facts = looks.getValue(name).let { SongFacts(it.features, it.mood, it.secondMood, it.energy, it.positivity, it.voice, it.family, seed = seed * 7919) }
            for ((k, into) in listOf(1_500L, 4_000L, 7_000L, 10_000L).withIndex()) {
                val d = Diorama()
                d.show(Diorama.Song("village-$name-$seed", Looks.of(facts), SongWave(wave, 240_000, 2_000, 236_000)), null, 0)
                shot(File(out, "village-$name-$seed-$k.png"), 960, 480, d, start + into - 2_000, startMs = 50_000)
            }
        }
    }
    // Over bridges and along stations (the way stays level, things to jump on it), and on roads.
    for ((name, place) in listOf("meadow" to "bridge", "castle" to "bridge", "coast" to "bridge", "city" to "bridge", "city" to "station")) {
        val kinds = when (name) {
            "meadow" -> listOf("bridge", "tunnel", "village")
            "city" -> listOf("bridge", "tunnel", "station")
            "coast" -> listOf("wheel", "bridge", "village")
            else -> listOf("ruins", "bridge", "tunnel")
        }
        val slot = (0 until 60).firstOrNull { i -> Diorama.hash(i, 501) <= 0.8f && kinds[(Diorama.hash(i, 502) * 3).toInt().coerceAtMost(2)] == place } ?: continue
        val start = slot * 45_000L + 13_500L
        for ((k, into) in listOf(-2_500L, 0L, 2_000L, 4_000L, 6_500L).withIndex()) {
            val d = Diorama()
            d.show(Diorama.Song("way-$name", Looks.of(looks.getValue(name)), SongWave(wave, 240_000, 2_000, 236_000)), null, 0)
            shot(File(out, "way-$place-$name-$k.png"), 960, 480, d, start + into - 2_000, startMs = 50_000)
        }
        // The train at a station: in from the left, across, out on the right (a frame a second).
        if (place == "station") for (k in 0 until 8) {
            val d = Diorama()
            d.show(Diorama.Song("way-$name", Looks.of(looks.getValue(name)), SongWave(wave, 240_000, 2_000, 236_000)), null, 0)
            shot(File(out, "train-$k.png"), 960, 480, d, start - 2_000, startMs = 50_000L + k * 1_000L)
        }
    }
    for (name in listOf("city", "neon")) {
        for (k in 0 until 6) {
            val d = Diorama()
            d.show(Diorama.Song("road-$name", Looks.of(looks.getValue(name)), SongWave(wave, 240_000, 2_000, 236_000)), null, 0)
            shot(File(out, "road-$name-$k.png"), 960, 480, d, 20_000L + k * 9_000L, startMs = 50_000L + k * 211L)
        }
    }
    // The four dogs, one by one, and all together: moments when the meadow's runner is a dog or the pack
    // (the same choice as Visitors.groundVisitor: a 14 s period, kinds rabbit, deer, dog, pack).
    run {
        val wanted = mutableListOf<Pair<String, Int>>()
        val breeds = listOf("goldie", "malamute", "galgo", "braco")
        for (e in 1 until 4000) {
            if (Diorama.hash(e, 221) < 0.35f - 0.15f * 0.35f) continue
            val kind = (Diorama.hash(e, 222) * 4).toInt().coerceAtMost(3)
            val name = when (kind) {
                2 -> "dog-" + breeds[(Diorama.hash(e, 731) * 4).toInt().coerceAtMost(3)]
                3 -> "dog-pack"
                else -> continue
            }
            if (wanted.none { it.first == name }) wanted += name to e
            if (wanted.size == 5) break
        }
        for ((name, e) in wanted) {
            val d = Diorama()
            d.show(Diorama.Song("dogs", Looks.of(looks.getValue("meadow")), SongWave(wave, 240_000, 2_000, 236_000)), null, 0)
            shot(File(out, "$name.png"), 960, 480, d, 60_000L, startMs = e * 14_000L + (if (name == "dog-pack") 6_200L else 4_300L))
        }
    }
    // The four sitting and watching, in space (helmets) and on a floe in the cold sea: the first
    // place they sit (every 50 s of music, as in Dogs.dogsWatching), the traveller just reaching it.
    for (name in listOf("space", "ice")) {
        val i = (0 until 40).first { Diorama.hash(it, 750) <= 0.55f && it > 0 }
        val at = (i * 50_000.0 + 50_000.0 * (0.3 + 0.4 * Diorama.hash(i, 751))).toLong()
        for ((k, before) in listOf(2_500L, -1_500L).withIndex()) {
            val d = Diorama()
            d.show(Diorama.Song("watch-$name", Looks.of(looks.getValue(name)), SongWave(wave, 240_000, 2_000, 236_000)), null, 0)
            shot(File(out, "watch-$name-$k.png"), 960, 480, d, at - before - 2_000, startMs = 50_000)
        }
    }
    // The four dogs over two and a half minutes of a meadow track, played on: a frame every 5 s.
    run {
        val d = Diorama()
        val list = DrawList()
        d.show(Diorama.Song("walk", Looks.of(looks.getValue("meadow")), SongWave(wave, 240_000, 2_000, 236_000)), null, 0)
        val raw = FloatArray(3)
        val path = Path()
        var k = 0
        for (step in 0..4500) {
            val t = step * 33L
            val beat = (t % 500) < 80
            raw[0] = 0.25f + (if (beat) 0.2f else 0f); raw[1] = if (beat) 0.25f else 0.05f; raw[2] = 0.03f
            d.draw(list, 960f, 480f, 20_000L + t, true, raw, 50_000L + t)
            if (step % 150 == 0) {
                val scene = ImageComposeScene(960, 480, Density(1f)) {
                    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
                    Canvas(Modifier.fillMaxSize()) { paint(list, path, cover, measurer) }
                }
                File(out, "walk-${"%02d".format(k++)}.png").writeBytes(scene.render(0).encodeToData(EncodedImageFormat.PNG)!!.bytes)
                scene.close()
            }
        }
    }
    // The same, upright: the pack closes up behind the traveller.
    run {
        val d = Diorama()
        val list = DrawList()
        d.show(Diorama.Song("walk", Looks.of(looks.getValue("meadow")), SongWave(wave, 240_000, 2_000, 236_000)), null, 0)
        val raw = FloatArray(3)
        val path = Path()
        var k = 0
        for (step in 0..4500) {
            val t = step * 33L
            val beat = (t % 500) < 80
            raw[0] = 0.25f + (if (beat) 0.2f else 0f); raw[1] = if (beat) 0.25f else 0.05f; raw[2] = 0.03f
            d.draw(list, 540f, 960f, 20_000L + t, true, raw, 50_000L + t)
            if (step % 150 == 0) {
                val scene = ImageComposeScene(540, 960, Density(1f)) {
                    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
                    Canvas(Modifier.fillMaxSize()) { paint(list, path, cover, measurer) }
                }
                File(out, "walk-tall-${"%02d".format(k++)}.png").writeBytes(scene.render(0).encodeToData(EncodedImageFormat.PNG)!!.bytes)
                scene.close()
            }
        }
    }
    // Paused: two moments half a second apart (the traveller and the dogs wait, moving a little).
    run {
        val d = Diorama()
        val list = DrawList()
        d.show(Diorama.Song("pause", Looks.of(looks.getValue("meadow")), SongWave(wave, 240_000, 2_000, 236_000)), null, 0)
        val raw = FloatArray(3)
        val path = Path()
        // Played a while (so the dogs come along), then paused.
        for (step in 0..1200) d.draw(list, 960f, 480f, 20_000L + step * 33L, true, raw, 50_000L + step * 33L)
        for ((k, at) in listOf(0L, 500L).withIndex()) {
            repeat(20) { d.draw(list, 960f, 480f, 20_000L + 1200 * 33L, false, null, 50_000L + 1200 * 33L + 2_000L + at + it * 16L) }
            val scene = ImageComposeScene(960, 480, Density(1f)) {
                val measurer = androidx.compose.ui.text.rememberTextMeasurer()
                Canvas(Modifier.fillMaxSize()) { paint(list, path, cover, measurer) }
            }
            File(out, "paused-$k.png").writeBytes(scene.render(0).encodeToData(EncodedImageFormat.PNG)!!.bytes)
            scene.close()
        }
    }
    // Touches: the first thing found in the sky touched five times (the sun), the traveller, things on the ground.
    run {
        val d = Diorama()
        val list = DrawList()
        d.show(Diorama.Song("touch", Looks.of(looks.getValue("meadow")), SongWave(wave, 240_000, 2_000, 236_000)), null, 0)
        val raw = floatArrayOf(0.3f, 0.1f, 0.02f)
        var t = 50_000L
        fun frames(n: Int) = repeat(n) { d.draw(list, 960f, 480f, 30_000L + (t - 50_000L), true, raw, t); t += 16 }
        frames(30)
        var sun: Pair<Float, Float>? = null
        loop@ for (y in 20..200 step 10) for (x in 300..700 step 10) {
            if (d.tap(list, x.toFloat(), y.toFloat(), t)) { sun = x.toFloat() to y.toFloat(); break@loop }
        }
        sun?.let { (x, y) -> repeat(4) { d.tap(list, x, y, t + 100L * (it + 1)) } }
        frames(20)
        // The traveller (a third of the way across, about on the ground).
        for (y in 300..460 step 8) if (d.tap(list, 288f, y.toFloat(), t)) break
        frames(20)
        val shotList = list
        val path = Path()
        val scene = ImageComposeScene(960, 480, Density(1f)) {
            val measurer = androidx.compose.ui.text.rememberTextMeasurer()
            Canvas(Modifier.fillMaxSize()) { paint(shotList, path, cover, measurer) }
        }
        File(out, "touch-sun.png").writeBytes(scene.render(0).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
        println("sun touched at $sun")
    }
    // Touching a building of the city: the same moment drawn before and after.
    for ((name, at) in listOf("touch-building-before" to false, "touch-building-after" to true)) {
        val d = Diorama()
        val list = DrawList()
        d.show(Diorama.Song("touchcity", Looks.of(looks.getValue("neon")), SongWave(wave, 240_000, 2_000, 236_000)), null, 0)
        val raw = floatArrayOf(0.3f, 0.1f, 0.02f)
        repeat(30) { d.draw(list, 960f, 480f, 30_000L + it * 16L, false, raw, 50_000L + it * 16L) }
        if (at) {
            val found = (0 until 960 step 20).flatMap { x -> (0 until 480 step 20).map { y -> x to y } }.filter { (x, y) -> d.tap(list, x.toFloat(), y.toFloat(), 40_000L) }
            println("touchable points: ${found.size} e.g. ${found.take(12)}")
            println("building touched: " + d.tap(list, 700f, 300f, 50_500L))
        }
        d.draw(list, 960f, 480f, 30_000L + 30 * 16L, false, raw, 50_000L + 30 * 16L)
        val path = Path()
        val scene = ImageComposeScene(960, 480, Density(1f)) {
            val measurer = androidx.compose.ui.text.rememberTextMeasurer()
            Canvas(Modifier.fillMaxSize()) { paint(list, path, cover, measurer) }
        }
        File(out, "$name.png").writeBytes(scene.render(0).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
    }
    // Others joining the traveller at loud moments: frames every 4 s, a song with much voice.
    for (name in listOf("meadow", "castle", "space")) {
        val facts = looks.getValue(name).let { SongFacts(it.features, it.mood, it.secondMood, it.energy, 0.7f, 0.9f, it.family, seed = 11) }
        for (k in 0 until 40) {
            val d = Diorama()
            d.show(Diorama.Song("crowd-$name", Looks.of(facts), SongWave(wave, 240_000, 2_000, 236_000)), null, 0)
            shot(File(out, "crowd-$name-${"%02d".format(k)}.png"), 960, 480, d, 20_000L + k * 4_000L, startMs = 50_000L + k * 331L)
        }
    }
    // A run of frames for each traveller kind on a steep made-up land: legs, tilting on the slopes.
    val hills = FloatArray(512) { i -> (0.5f + 0.5f * sin(i * 0.35).toFloat()).coerceIn(0f, 1f) }
    for (name in listOf("meadow", "city", "castle", "coast")) {
        for (seed in 0 until 4) {
            val facts = looks.getValue(name).let { SongFacts(it.features, it.mood, it.secondMood, it.energy, it.positivity, it.voice, it.family, seed = seed * 7919) }
            for (k in 0 until 3) {
                val d = Diorama()
                d.show(Diorama.Song("hills-$name-$seed", Looks.of(facts), SongWave(hills, 240_000, 0, 240_000), "Made Up Album", "Some Artist"), null, 0)
                shot(File(out, "hills-$name-$seed-$k.png"), 960, 480, d, 30_000L + k * 2_300L, startMs = 50_000L + k * 133L)
            }
        }
    }
    println("shots in ${out.absolutePath}")
    System.exit(0)
}

/** A made-up cover: an orange sun on blue. */
private val cover: ImageBitmap by lazy {
    val scene = ImageComposeScene(120, 120, Density(1f)) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.verticalGradient(listOf(Color(0xFF1B3A8A), Color(0xFF3FA7D6))))
            drawCircle(Color(0xFFFF8A2A), 34f, Offset(60f, 52f))
            drawRect(Color(0xFF101830), Offset(0f, 88f), Size(120f, 32f))
        }
    }
    scene.render(0).toComposeImageBitmap().also { scene.close() }
}

private fun coverPixels(): IntArray {
    val pm = cover.toPixelMap()
    return IntArray(24 * 24) { pm[(it % 24) * pm.width / 24, (it / 24) * pm.height / 24].toArgb() }
}

/** A song's loudness: a quiet start, verses and louder choruses, a fading end. */
private fun songShape(r: Random): FloatArray = FloatArray(512) { i ->
    val x = i / 511f
    val envelope = (x * 8f).coerceAtMost(1f) * ((1f - x) * 10f).coerceAtMost(1f)
    val chorus = if (sin(x * 18.0).toFloat() > 0.3f) 0.35f else 0f
    (envelope * (0.45f + chorus + 0.15f * abs(sin(x * 90.0).toFloat()) + 0.08f * r.nextFloat())).coerceIn(0f, 1f)
}

/** Runs [d] for two seconds of made-up levels from [startMs] (so the live values settle), then saves the frame. */
/** The start of the names of the shots to make; null: all. */
private var only: String? = null

private fun shot(file: File, w: Int, h: Int, d: Diorama, positionMs: Long, startMs: Long = 0, warm: Int = 120, stripTop: Float = 0f, stripHeight: Float = 0f, lift: Float = 0f) {
    if (only?.let { prefix -> prefix.split(',').none { file.name.startsWith(it) } } == true) return
    val list = DrawList()
    val raw = FloatArray(3)
    for (k in 0..warm) {
        val t = startMs + k * 16L
        val beat = (t % 500) < 80
        raw[0] = 0.2f + (if (beat) 0.2f else 0f)
        raw[1] = if (beat) 0.25f else 0.05f
        raw[2] = 0.02f + 0.01f * sin(t / 50.0).toFloat()
        d.draw(list, w.toFloat(), h.toFloat(), positionMs + k * 16L, true, raw, t, lift = lift, stripTop = stripTop, stripHeight = stripHeight)
    }
    val path = Path()
    val scene = ImageComposeScene(w, h, Density(1f)) {
        val measurer = androidx.compose.ui.text.rememberTextMeasurer()
        Canvas(Modifier.fillMaxSize()) { paint(list, path, cover, measurer) }
    }
    val image = scene.render(0)
    file.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
    scene.close()
}
