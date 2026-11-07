package com.sadurni.attacca.diorama

import com.sadurni.attacca.chain.FamilyName
import com.sadurni.attacca.chain.Mood
import com.sadurni.attacca.chain.Trait
import com.sadurni.attacca.chain.TrackFeatures
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** The kind of land a song is: chosen from its mood, sound family and measures. */
enum class World {
    /** Dark medieval land: crags, castles, dead trees, under a moon. */
    CASTLE,

    /** A city by day: skyline and street. */
    CITY,

    /** A city at night with neon lights. */
    NEON,

    /** A seaside town: hills, sea and sand. */
    COAST,

    /** Meadows, forests and mountains. */
    MEADOW,

    /** A cold sea with icebergs adrift. */
    ICE,

    /** Space: planets, stars and the surface of a moon. */
    SPACE,
}

/** Who or what goes through the land. */
enum class Traveller { RIDER, RUNNER, CAR, CYCLIST, WALKER, ICEBERG, SHIP }

/** Colours of a scene, ARGB. */
class Palette(
    val skyTop: Int,
    val skyBottom: Int,
    /** Farthest land (mountains, skyline). */
    val far: Int,
    val mid: Int,
    /** The ground the traveller goes on. */
    val near: Int,
    /** Small bright things: flowers, torches, neon, signs. */
    val accent: Int,
    /** Sun, moon, lamps, windows. */
    val light: Int,
) {
    /**
     * The same colours with the land standing out from the sky: the far and middle land more
     * saturated, and darker (or lighter, on a dark sky) where they are close to the sky's colour.
     */
    fun vivid(): Palette {
        val sky = Colors.luma(skyBottom)
        fun stand(c: Int, gap: Float): Int {
            val s = Colors.saturate(c, 1.35f)
            val d = Colors.luma(s) - sky
            if (kotlin.math.abs(d) >= gap) return s
            return if (sky > 0.45f) Colors.darker(s, (gap - kotlin.math.abs(d)) * 1.6f) else Colors.lighter(s, (gap - kotlin.math.abs(d)) * 1.2f)
        }
        return Palette(skyTop, skyBottom, stand(far, 0.15f), stand(mid, 0.22f), Colors.saturate(near, 1.15f), accent, light)
    }

    fun mix(other: Palette, t: Float) = Palette(
        Colors.mix(skyTop, other.skyTop, t), Colors.mix(skyBottom, other.skyBottom, t),
        Colors.mix(far, other.far, t), Colors.mix(mid, other.mid, t), Colors.mix(near, other.near, t),
        Colors.mix(accent, other.accent, t), Colors.mix(light, other.light, t),
    )
}

/** How a song looks in the diorama. Weather values go from 0 (none) to 1. */
class SongLook(
    val world: World,
    val traveller: Traveller,
    val palette: Palette,
    /** Height of the sun: 0 night … 1 bright noon. */
    val sun: Float,
    val clouds: Float,
    val rain: Float,
    /** Dark storm with lightning. */
    val storm: Float,
    val snow: Float,
    /** Beats per minute, for steps and speed. */
    val bpm: Float,
    /** How clearly the beat repeats (0 … 1): steady steps or a loose wander. */
    val pulse: Float,
    /** Energy in the collection (0 … 1): jumps, birds, how lively everything is. */
    val energy: Float,
    /** What it was made from, for its other looks ([variant], [reseeded]). */
    internal val facts: SongFacts? = null,
    /** -1 a quiet stretch's look, +1 a loud one's, 0 the track's own. */
    val mode: Int = 0,
) {
    /** Night: dark sky, stars, lit windows. */
    val night: Float get() = (1f - sun * 2.5f).coerceIn(0f, 1f)

    private var quiet: SongLook? = null
    private var loud: SongLook? = null

    /** The look of a quiet (-1) or loud (+1) stretch of this track, or its own (0). */
    fun variant(mode: Int): SongLook {
        val f = facts ?: return this
        if (mode == this.mode) return this
        return when {
            mode < 0 -> quiet ?: Looks.of(f, -1).also { quiet = it }
            mode > 0 -> loud ?: Looks.of(f, 1).also { loud = it }
            else -> Looks.of(f, 0)
        }
    }

    /** Another look for the same track (another traveller, time of day, shade): for one alike just before. */
    fun reseeded(): SongLook = facts?.let { Looks.of(it.reseeded(), mode) } ?: this
}

/** What is known of a song for its look; anything may be missing (not analysed yet). */
class SongFacts(
    val features: TrackFeatures? = null,
    val mood: Mood? = null,
    val secondMood: Mood? = null,
    /** Percentiles in the collection (0 … 1). */
    val energy: Float? = null,
    val positivity: Float? = null,
    val voice: Float? = null,
    val family: FamilyName? = null,
    /** The cover's pixels (ARGB), a small copy (e.g. 24 × 24); null without a cover. */
    val cover: IntArray? = null,
    /** The cover's accent colour as the app's theme takes it, when it has one (grey covers don't). */
    val accent: Int? = null,
    /**
     * A number of the track's own (e.g. from its path): tracks alike still differ in their
     * traveller, time of day and shades.
     */
    val seed: Int = 0,
) {
    internal fun reseeded() = SongFacts(features, mood, secondMood, energy, positivity, voice, family, cover, accent, seed * 31 + 17)
}

object Looks {

    /**
     * The look of a song from what is known of it; [mode] -1 or +1 for a quiet or loud stretch of
     * it (a calmer or wilder world next to its own, a slower or quicker pace).
     */
    fun of(facts: SongFacts, mode: Int = 0): SongLook {
        val f = facts.features
        val seed = facts.seed
        val ownEnergy = facts.energy ?: f?.let { ((it.loudnessDb + 30f) / 22f).coerceIn(0f, 1f) } ?: 0.5f
        val energy = when {
            mode < 0 -> ownEnergy * 0.5f
            mode > 0 -> max(ownEnergy, 0.85f)
            else -> ownEnergy
        }
        val positivity = facts.positivity ?: f?.let { (0.5f + it.mode * 2f).coerceIn(0f, 1f) } ?: 0.6f
        val voice = facts.voice ?: f?.let { (it.voice * 4f).coerceIn(0f, 1f) } ?: 0.5f
        fun trait(t: Trait): Boolean? = facts.family?.traits?.firstOrNull { it.first == t }?.second
        val distorted = trait(Trait.DISTORTION) == true || (f?.noisiness ?: 0f) > 0.35f
        val heavyBass = trait(Trait.BASS) == true || (f?.bassDb ?: -20f) > -6f
        val percussive = trait(Trait.PERCUSSION) ?: ((f?.percussiveness ?: 0f) > 0.9f)
        val pulse = f?.pulseClarity ?: 0.4f
        val ownBpm = f?.tempoBpm?.takeIf { it in 40f..240f } ?: 100f
        val bpm = when {
            mode < 0 -> ownBpm * 0.8f
            mode > 0 -> ownBpm * 1.12f
            else -> ownBpm
        }

        fun worldOf(mood: Mood?) = when {
            f == null && mood == null -> World.MEADOW
            mood == Mood.TENSE || (distorted && positivity < 0.35f && ownEnergy > 0.5f) -> World.CASTLE
            mood == Mood.ENERGETIC -> if (voice < 0.4f || (heavyBass && pulse > 0.5f)) World.NEON else World.CITY
            mood == Mood.CHEERFUL -> World.COAST
            mood == Mood.MELANCHOLIC -> if (!percussive && ownEnergy < 0.45f) World.ICE else World.MEADOW
            mood == Mood.CALM -> if (ownEnergy < 0.15f && !percussive && voice < 0.35f) World.SPACE else World.MEADOW
            ownEnergy > 0.6f -> World.CITY
            else -> World.MEADOW
        }
        // Between two moods, now and then the second one's world.
        val own = if (facts.secondMood != null && hash(seed, 3) < 0.35f) worldOf(facts.secondMood) else worldOf(facts.mood)
        val world = when {
            mode < 0 -> CALMER.getValue(own)
            mode > 0 -> WILDER.getValue(own)
            else -> own
        }
        val choices = TRAVELLERS.getValue(world)
        val traveller = choices[(hash(seed, 4) * choices.size).toInt().coerceAtMost(choices.size - 1)]

        // Brighter sound: a higher sun. The dark worlds keep their night.
        val brightness = f?.let { ((it.brightness - 10.3f) / 1.6f).coerceIn(0f, 1f) } ?: 0.6f
        // Each track its own hour: some a little earlier or later in the day.
        val hour = (hash(seed, 5) - 0.5f) * 0.55f
        val sun = when (world) {
            World.NEON, World.SPACE -> 0f
            World.CASTLE -> 0.05f * brightness
            World.ICE -> (0.15f + 0.3f * brightness + hour * 0.4f).coerceIn(0.05f, 0.6f)
            else -> (0.25f + 0.75f * (0.6f * brightness + 0.4f * positivity) + hour).coerceIn(0.12f, 1f)
        }
        val sad = ((1f - positivity) * (1f - energy)).coerceIn(0f, 1f)
        val storm = if (heavyBass && energy > 0.55f && positivity < 0.5f) ((energy - 0.4f) * 2f).coerceIn(0f, 1f) else 0f
        val rain = when (world) {
            World.SPACE, World.NEON, World.ICE -> 0f
            else -> max(storm, ((sad - 0.35f) * 2.2f).coerceIn(0f, 1f))
        }
        val clouds = when (world) {
            World.SPACE -> 0f
            else -> max(max(rain, storm), (1f - positivity) * 0.7f + 0.15f).coerceIn(0f, 1f)
        }
        val snow = if (world == World.ICE) 0.35f + 0.4f * (1f - energy) else 0f

        var palette = shifted(BASE.getValue(world), (hash(seed, 6) - 0.5f) * 36f)
        palette = weathered(palette, sun, max(clouds * 0.6f, max(rain, storm)), world)
        facts.cover?.let { palette = tinted(palette, it, facts.accent) }
        return SongLook(world, traveller, palette, sun, clouds, rain, storm, snow, bpm, pulse, energy, facts, mode)
    }

    /** Every colour's hue turned by [degrees]: tracks alike, in shades of their own. */
    private fun shifted(p: Palette, degrees: Float): Palette {
        fun t(c: Int): Int {
            val hsl = Colors.hsl(c)
            if (hsl[1] < 0.08f) return c
            return Colors.fromHsl(c ushr 24, (hsl[0] + degrees + 360f) % 360f, hsl[1], hsl[2])
        }
        return Palette(t(p.skyTop), t(p.skyBottom), t(p.far), t(p.mid), t(p.near), t(p.accent), p.light)
    }

    /** A steady pseudo-random value in 0 … 1. */
    private fun hash(i: Int, salt: Int) = Diorama.hash(i, salt)

    /** Who may cross [world]. */
    internal fun travellersOf(world: World): List<Traveller> = TRAVELLERS.getValue(world).distinct()

    /** Worlds always at night: no dusk. */
    private val NIGHT_WORLDS = setOf(World.CASTLE, World.NEON, World.SPACE)

    /** A quiet stretch's world next to a track's own, and a loud one's. */
    private val CALMER = mapOf(
        World.CITY to World.MEADOW, World.NEON to World.SPACE, World.CASTLE to World.ICE, World.COAST to World.MEADOW,
        World.MEADOW to World.ICE, World.ICE to World.SPACE, World.SPACE to World.ICE,
    )
    private val WILDER = mapOf(
        World.MEADOW to World.CITY, World.ICE to World.CASTLE, World.SPACE to World.NEON, World.COAST to World.CITY,
        World.CITY to World.NEON, World.NEON to World.CASTLE, World.CASTLE to World.NEON,
    )

    /** Who may cross each world (the first the likeliest when there are repeats). */
    private val TRAVELLERS = mapOf(
        World.CASTLE to listOf(Traveller.RIDER, Traveller.RIDER, Traveller.RUNNER),
        World.CITY to listOf(Traveller.RUNNER, Traveller.CYCLIST, Traveller.CAR),
        World.NEON to listOf(Traveller.CAR, Traveller.CAR, Traveller.CYCLIST, Traveller.RUNNER),
        World.COAST to listOf(Traveller.CYCLIST, Traveller.RUNNER, Traveller.WALKER),
        World.MEADOW to listOf(Traveller.WALKER, Traveller.RIDER, Traveller.CYCLIST, Traveller.RUNNER),
        World.ICE to listOf(Traveller.ICEBERG),
        World.SPACE to listOf(Traveller.SHIP),
    )

    /** Between two looks: colours and weather in between, the world and traveller of the nearer one. */
    fun mix(a: SongLook, b: SongLook, t: Float): SongLook {
        val n = if (t < 0.5f) a else b
        fun m(x: Float, y: Float) = x + (y - x) * t
        return SongLook(
            n.world, n.traveller, a.palette.mix(b.palette, t),
            m(a.sun, b.sun), m(a.clouds, b.clouds), m(a.rain, b.rain), m(a.storm, b.storm), m(a.snow, b.snow),
            m(a.bpm, b.bpm), m(a.pulse, b.pulse), m(a.energy, b.energy), n.facts, n.mode,
        )
    }

    /** Night darkens the sky and the land; clouds and rain grey them. */
    private fun weathered(p: Palette, sun: Float, grey: Float, world: World): Palette {
        val night = (1f - sun * 2.5f).coerceIn(0f, 1f)
        val nightSky = when (world) {
            World.CASTLE, World.NEON, World.SPACE -> 0f // already night colours
            else -> night
        }
        fun land(c: Int) = Colors.greyed(Colors.mix(c, Colors.darker(c, 0.55f), nightSky), grey * 0.5f)
        fun sky(c: Int, nightColor: Int) = Colors.greyed(Colors.mix(c, nightColor, nightSky), grey * 0.75f)
        // A low sun (morning or evening) warms the horizon.
        val dusk = if (world in NIGHT_WORLDS) 0f else (1f - abs(sun - 0.3f) / 0.2f).coerceIn(0f, 1f)
        return Palette(
            sky(p.skyTop, 0xFF0B1230.toInt()), Colors.mix(sky(p.skyBottom, 0xFF2B3B63.toInt()), 0xFFF2A066.toInt(), dusk * 0.45f),
            land(p.far), land(p.mid), land(p.near), p.accent,
            if (nightSky > 0.5f) 0xFFF4F1DE.toInt() else p.light,
        )
    }

    /**
     * The scene takes the cover's colours: the land its main one, the far land and the horizon its
     * accent (the app's, else the cover's most vivid), the small bright things the accent itself.
     */
    private fun tinted(p: Palette, cover: IntArray, appAccent: Int?): Palette {
        val (main, vivid) = Colors.dominant(cover) ?: return p
        val accent = appAccent ?: vivid
        fun t(c: Int, to: Int, amount: Float) = Colors.towardHue(c, to, amount)
        return Palette(
            t(p.skyTop, main, 0.35f), t(p.skyBottom, accent, 0.45f), t(p.far, accent, 0.5f), t(p.mid, main, 0.5f), t(p.near, main, 0.45f),
            if (Colors.hsl(accent)[1] > 0.15f) Colors.mix(p.accent, accent, 0.9f) else p.accent,
            Colors.mix(p.light, Colors.lighter(accent, 0.75f), 0.3f),
        )
    }

    private val BASE = mapOf(
        World.CASTLE to palette(0xFF15131F, 0xFF4B3A5A, 0xFF2D2638, 0xFF1F1A28, 0xFF121016, 0xFFE8892B, 0xFFE9DFB8),
        World.CITY to palette(0xFF4FA3DE, 0xFFD2E9F7, 0xFF8C9BAE, 0xFF5D6D82, 0xFF3A4556, 0xFFF4C542, 0xFFFFF4C9),
        World.NEON to palette(0xFF07071F, 0xFF3B1659, 0xFF1D1838, 0xFF15122B, 0xFF0C0B1C, 0xFFFF2E97, 0xFF2CF2FF),
        World.COAST to palette(0xFF3EC1DD, 0xFFFDE8C4, 0xFF74B49B, 0xFF2D86AE, 0xFFEFCF93, 0xFFE4572E, 0xFFFFF2AE),
        World.MEADOW to palette(0xFF62BEEB, 0xFFE2F5FF, 0xFF8AAFC6, 0xFF66B556, 0xFF3D8A37, 0xFFF6D55E, 0xFFFFF7D4),
        World.ICE to palette(0xFF223448, 0xFF8EA8BE, 0xFFA7BFD1, 0xFFE6F0F7, 0xFF1C3D59, 0xFFBFE3F5, 0xFFEAF2F8),
        World.SPACE to palette(0xFF04020E, 0xFF1B1242, 0xFF3A2D78, 0xFF2B2160, 0xFF4B3F7C, 0xFFFF9F43, 0xFFFFFFFF),
    )

    private fun palette(vararg c: Long) = Palette(c[0].toInt(), c[1].toInt(), c[2].toInt(), c[3].toInt(), c[4].toInt(), c[5].toInt(), c[6].toInt())
}

/** Colour arithmetic on ARGB ints. */
object Colors {

    fun argb(a: Int, r: Int, g: Int, b: Int): Int = (a.coerceIn(0, 255) shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    fun alpha(c: Int, a: Float): Int = (((c ushr 24) * a.coerceIn(0f, 1f)).toInt() shl 24) or (c and 0xFFFFFF)

    fun mix(a: Int, b: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        fun ch(s: Int) = (((a ushr s) and 0xFF) + (((b ushr s) and 0xFF) - ((a ushr s) and 0xFF)) * u).toInt()
        return argb(ch(24), ch(16), ch(8), ch(0))
    }

    /** How light [c] looks (0 black … 1 white). */
    /** [c] with its saturation times [k] (around its own grey). */
    fun saturate(c: Int, k: Float): Int {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        val grey = r * 0.3f + g * 0.59f + b * 0.11f
        fun ch(v: Int) = (grey + (v - grey) * k).toInt().coerceIn(0, 255)
        return (c and 0xFF000000.toInt()) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }

    fun luma(c: Int): Float = (((c shr 16) and 0xFF) * 0.3f + ((c shr 8) and 0xFF) * 0.59f + (c and 0xFF) * 0.11f) / 255f

    fun darker(c: Int, amount: Float): Int = mix(c, (c and 0xFF000000.toInt()), amount)

    fun lighter(c: Int, amount: Float): Int = mix(c, (c and 0xFF000000.toInt()) or 0xFFFFFF, amount)

    /** Towards the grey of the same lightness. */
    fun greyed(c: Int, amount: Float): Int {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        val l = (r * 30 + g * 59 + b * 11) / 100
        return mix(c, argb(c ushr 24, l, l, l), amount)
    }

    /** [c] with its hue and saturation moved [amount] towards [target]'s, its lightness kept. */
    fun towardHue(c: Int, target: Int, amount: Float): Int {
        val a = hsl(c)
        val b = hsl(target)
        // A grey has no hue to go to: only greyer.
        if (b[1] < 0.12f) return greyed(c, amount * 0.5f)
        var dh = b[0] - a[0]
        if (dh > 180f) dh -= 360f
        if (dh < -180f) dh += 360f
        val h = (a[0] + dh * amount + 360f) % 360f
        val s = a[1] + (b[1] - a[1]) * amount * 0.6f
        return fromHsl(c ushr 24, h, s, a[2])
    }

    fun hsl(c: Int): FloatArray {
        val r = ((c shr 16) and 0xFF) / 255f
        val g = ((c shr 8) and 0xFF) / 255f
        val b = (c and 0xFF) / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val l = (mx + mn) / 2f
        if (mx == mn) return floatArrayOf(0f, 0f, l)
        val d = mx - mn
        val s = if (l > 0.5f) d / (2f - mx - mn) else d / (mx + mn)
        val h = when (mx) {
            r -> (g - b) / d + (if (g < b) 6f else 0f)
            g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        } * 60f
        return floatArrayOf(h, s, l)
    }

    fun fromHsl(alpha: Int, h: Float, s: Float, l: Float): Int {
        val c = (1f - abs(2f * l - 1f)) * s.coerceIn(0f, 1f)
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = l - c / 2f
        val (r, g, b) = when ((h / 60f).toInt() % 6) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return argb(alpha, ((r + m) * 255f).toInt(), ((g + m) * 255f).toInt(), ((b + m) * 255f).toInt())
    }

    /**
     * The cover's main colour (the commonest of four groups, a coloured one when it has any) and
     * its most vivid one; null for an empty picture.
     */
    fun dominant(pixels: IntArray): Pair<Int, Int>? {
        if (pixels.isEmpty()) return null
        val k = 4
        val centres = Array(k) { i -> pixels[(i * pixels.size) / k].let { floatArrayOf(((it shr 16) and 0xFF).toFloat(), ((it shr 8) and 0xFF).toFloat(), (it and 0xFF).toFloat()) } }
        val counts = IntArray(k)
        val sums = Array(k) { FloatArray(3) }
        repeat(8) {
            counts.fill(0)
            for (s in sums) s.fill(0f)
            for (p in pixels) {
                val r = ((p shr 16) and 0xFF).toFloat()
                val g = ((p shr 8) and 0xFF).toFloat()
                val b = (p and 0xFF).toFloat()
                var best = 0
                var bestD = Float.MAX_VALUE
                for (i in 0 until k) {
                    val c = centres[i]
                    val d = (r - c[0]) * (r - c[0]) + (g - c[1]) * (g - c[1]) + (b - c[2]) * (b - c[2])
                    if (d < bestD) {
                        bestD = d
                        best = i
                    }
                }
                counts[best]++
                sums[best][0] += r
                sums[best][1] += g
                sums[best][2] += b
            }
            for (i in 0 until k) if (counts[i] > 0) for (j in 0..2) centres[i][j] = sums[i][j] / counts[i]
        }
        val colors = IntArray(k) { argb(255, centres[it][0].toInt(), centres[it][1].toInt(), centres[it][2].toInt()) }
        val order = (0 until k).sortedByDescending { counts[it] }
        val main = order.firstOrNull { hsl(colors[it])[1] > 0.18f && counts[it] * 10 >= pixels.size } ?: order.first()
        val vivid = (0 until k).filter { counts[it] * 20 >= pixels.size }.maxByOrNull { val h = hsl(colors[it]); h[1] * (1f - abs(h[2] - 0.55f)) } ?: main
        return colors[main] to colors[vivid]
    }
}
