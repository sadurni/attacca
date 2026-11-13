package com.sadurni.attacca.world

import com.sadurni.attacca.diorama.SongLook
import com.sadurni.attacca.diorama.SongWave
import com.sadurni.attacca.diorama.World
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The land of Attacca World for one song: a smooth, rolling surface the traveller goes through
 * along z (forward) on a way at x = 0, [BLOCKS_PER_SECOND] units for each second of the song. How
 * loud the song is there raises the land on both sides: quiet stretches are open valleys and
 * lakes, loud ones mountains, so the colour of the ground, which follows its height, follows the
 * music's intensity too.
 */
class WorldLand(
    val wave: SongWave?,
    val look: SongLook,
    private val seed: Int,
    /** Whether the song's quiet and loud stretches change the world for a while (not with a world chosen by hand). */
    sectioned: Boolean = true,
    before: Before? = null,
    /** The next song's look, if known: the land comes into its world before the song ends. */
    val next: SongLook? = null,
    /** Where this song starts on the land's own timeline (ms): its places fall where O'rama's do. */
    val originMs: Double = 0.0,
    /** The traveller comes already aboard from the song before (the same vehicle goes on). */
    val arrives: Boolean = false,
    /**
     * The look the song before was showing as it ended (what was seen arriving): this song starts
     * in it and turns into its own over a while, never at once into something else.
     */
    val cameIn: SongLook? = null,
) {

    /**
     * The land of the song before (where it was left, row [atZ] of it): this one goes on from it,
     * the same for its first [CONT] rows, turning into its own over the next [CONT_BLEND].
     */
    class Before(val land: WorldLand, val atZ: Float)

    // Only one back: the one before forgets its own.
    @Volatile private var beforeKept: Before? = before?.also { it.land.forgetBefore() }

    /** The land this one goes on from, if any. */
    val before: Before? get() = beforeKept

    private fun forgetBefore() {
        beforeKept = null
    }

    /**
     * How long the one before goes on (rows, then rows turning into this one): as long as usual when
     * it was in this song's world already (what was seen arriving), briefly when it was another
     * (a song skipped halfway: no camel on the sea's floor for long).
     */
    private val otherBefore: Boolean by lazy {
        val b = before ?: return@lazy false
        // A song skipped halfway: left before this one's world had begun to come in at its end
        // (then what was there is not what this song is; whatever came in at an end goes on).
        b.land.endZ == Float.MAX_VALUE || b.atZ < b.land.endZ - ARRIVE
    }
    internal val contRows: Float get() = if (otherBefore) 4f else CONT
    internal val contBlendRows: Float get() = if (otherBefore) 14f else CONT_BLEND

    /** Where the land is wholly this song's own (0 without one before). */
    val contEnd: Float get() = if (beforeKept == null) 0f else contRows + contBlendRows

    /** Up to where the things standing are the last song's (its houses, trees…), from where this one's. */
    val contHalf: Float get() = if (beforeKept == null) 0f else contRows + contBlendRows / 2

    /** How much of row [z] is this land's own (0: still the one before, 1: its own). */
    fun ownAt(z: Float): Float = if (beforeKept == null) 1f else smoothstep(contRows, contRows + contBlendRows, z)

    /** The one before as it is at row [z] of this land, while it still shows there. */
    private fun beforeAt(z: Float): Before? = beforeKept?.takeIf { z < contRows + contBlendRows }

    private val sections = if (sectioned) wave?.let { com.sadurni.attacca.diorama.Sections.of(it) } else null

    /**
     * Where the song's vehicle waits (row): a little ahead; past the stretch that still goes on from
     * the last song when it waits at a shore of this land's own (a canoe, an iceberg, the submarine).
     */
    val startZ: Float by lazy { if (before != null && look.traveller.let { it == com.sadurni.attacca.diorama.Traveller.SUBMARINE || it == com.sadurni.attacca.diorama.Traveller.CANOE || it == com.sadurni.attacca.diorama.Traveller.ICEBERG }) contRows + contBlendRows * 0.6f + 8f else Cast.START }

    /** The row where the song ends. */
    val endZ: Float = wave?.let { zAt(it.endMs.toDouble()) } ?: Float.MAX_VALUE

    /** The traveller of the next song, if it is known (getting off before the end when it differs). */
    val leavesTo: com.sadurni.attacca.diorama.Traveller? get() = next?.traveller

    private fun sectionLookAt(z: Float): SongLook {
        val s = sections ?: return look
        val v = look.variant(s.modeAt(z * 1000.0 / BLOCKS_PER_SECOND))
        // Never under the sea for a while without the submarine (nor out of it with it): the
        // traveller's vehicle and the world go together, and the sea's water is the song's own.
        return if ((v.world == World.REEF) != (look.world == World.REEF)) look else v
    }

    /** What this song starts in (the look that arrived), when it is not its own first look. */
    // (Only with this song's own traveller: the vehicle and its world go together.)
    // (Whatever vehicle: what was seen coming is what is there; never into or out of the sea, though.)
    private val startLook: SongLook? by lazy {
        cameIn?.takeIf { it != sectionLookAt(0f) && !otherBefore && (it.world == World.REEF) == (look.world == World.REEF) }
    }

    /** How this song's land starts (for the log of World's song changes). */
    fun describeStart(): String {
        val b = before
        fun d(l: SongLook?) = l?.let { "${it.world}/${it.traveller}/${it.mode}" } ?: "-"
        return "own ${d(look)} first ${d(sectionLookAt(0f))} cameIn ${d(cameIn)} held ${startLook != null} " +
            "skip $otherBefore cont ${contRows.toInt()}+${contBlendRows.toInt()} left ${b?.let { "%.0f of %.0f".format(it.atZ, it.land.endZ) } ?: "-"} next ${d(next)}"
    }

    /** Where (row) the look that arrived has turned halfway into this song's own. */
    private val ownFrom = CONT + CONT_BLEND + SECTION_BLEND / 2

    /** The look at row [z]: the song's own, a quiet or loud stretch's (another world for a while, as in O'rama), or near the end the next song's. */
    fun lookAt(z: Float): SongLook {
        if (next != null && z >= endZ - ARRIVE / 2) return next
        if (startLook != null && z < ownFrom) return startLook!!
        return sectionLookAt(z)
    }

    /** Where the look changes (row), the look before and after, and over how many rows it comes in. */
    private class Change(val z: Float, val from: SongLook, val to: SongLook, val rows: Float)

    private val changes: List<Change> by lazy {
        val list = ArrayList<Change>()
        val stop = if (next != null) endZ - ARRIVE / 2 else endZ
        // From the look that arrived into this song's own.
        val came = startLook
        val from0 = if (came != null && ownFrom < stop) ownFrom else 0f
        if (came != null && ownFrom < stop) list += Change(ownFrom, came, sectionLookAt(ownFrom), SECTION_BLEND)
        if (sections != null) {
            var prev = sectionLookAt(from0)
            var z = from0 + 1f
            while (z < stop) {
                val l = sectionLookAt(z)
                if (l !== prev) {
                    list += Change(z, prev, l, SECTION_BLEND)
                    prev = l
                }
                z += 1f
            }
        }
        if (next != null && endZ < Float.MAX_VALUE) list += Change(stop, sectionLookAt(stop - 1f), next, ARRIVE)
        list
    }

    /**
     * The looks about row [z] and how far from the first to the second (0 … 1): a new world comes
     * in over some rows, seen coming from afar, not all at once.
     */
    fun lookBlend(z: Float): Triple<SongLook, SongLook, Float> {
        // Where the last song's land goes on, so does its blend (its end into this one's world).
        beforeKept?.let { b -> if (!otherBefore && z + b.atZ < b.land.endZ + 1f) return b.land.lookBlend(z + b.atZ) }
        for (c in changes) {
            val from = c.z - c.rows / 2
            if (z >= from && z <= c.z + c.rows / 2) return Triple(c.from, c.to, smoothstep(from, c.z + c.rows / 2, z))
        }
        val a = lookAt(z)
        return Triple(a, a, 0f)
    }

    /** The world of what stands at ([x], [z]): where one world turns into another, a mix of both. */
    fun worldAt(x: Float, z: Float): World {
        val (a, b, t) = lookBlend(z)
        if (t <= 0f) return a.world
        if (t >= 1f) return b.world
        return if (noise(x * 0.13f + 70f, z * 0.11f) < t) b.world else a.world
    }

    /** The places along the way (bridges, tunnels, a station…), as O'rama's. */
    val places: List<Place> by lazy { Places.of(this, originMs) }

    companion object {
        const val BLOCKS_PER_SECOND = 4f
        const val HALF_WIDTH = 72
        const val MAX_HEIGHT = 34f
        const val WATER = 2f

        /** Half the way's width. */
        const val PATH_HALF = 1.4f

        /** Rows from one bend of the way to the next, and how far across it may go. */
        const val BEND = 52f
        const val BEND_REACH = 15f

        /**
         * Rows a new song's land keeps the last one's shape (and its things), then rows it turns
         * into its own: further than the eye reaches, so what was seen coming is what is there.
         */
        const val CONT = 115f
        const val CONT_BLEND = 40f

        /** Rows over which a stretch's world comes in. */
        const val SECTION_BLEND = 80f

        /** Rows over which the next song's world comes in before the end. */
        const val ARRIVE = 90f
    }

    /** Where in the song (the file's ms) row [z] is. */
    fun msAt(z: Float): Double = (wave?.startMs ?: 0L) + z * 1000.0 / BLOCKS_PER_SECOND

    /** The row the song's moment [fileMs] is at. */
    fun zAt(fileMs: Double): Float = ((fileMs - (wave?.startMs ?: 0L)) / 1000.0 * BLOCKS_PER_SECOND).toFloat()

    private val cache = java.util.concurrent.ConcurrentHashMap<Int, Float>()

    /** How loud the song is about row [z] (0 … 1): the song's own quiet and loud, over 1.2 s. */
    fun intensity(z: Int): Float = cache.getOrPut(z) {
        val w = wave ?: return@getOrPut 0.3f + 0.2f * noise(z * 0.05f, 3.1f)
        val ms = msAt(z.toFloat())
        val fade = when {
            ms < w.startMs -> ((ms - w.startMs) / 6000.0 + 1).coerceIn(0.0, 1.0).toFloat()
            ms > w.endMs -> (1 - (ms - w.endMs) / 6000.0).coerceIn(0.0, 1.0).toFloat()
            else -> 1f
        }
        w.shape(ms.coerceIn(w.startMs.toDouble(), w.endMs.toDouble()), 1200.0) * fade + 0.08f * (1 - fade)
    }

    /** [intensity] between rows. */
    fun intensityAt(z: Float): Float {
        val i = floor(z).toInt()
        val f = z - i
        return intensity(i) + (intensity(i + 1) - intensity(i)) * f
    }

    private val slowCache = java.util.concurrent.ConcurrentHashMap<Int, Float>()

    /** The intensity the way follows, smoothed over about 3 s (hills, as in O'rama). */
    private fun slow(z: Int): Float = slowCache.getOrPut(z) {
        var s = 0f
        var n = 0f
        for (d in -4..4 step 2) {
            val w = 1f - abs(d) / 6f
            s += intensity(z + d) * w
            n += w
        }
        s / n
    }

    /** The height of the way at [z]: it rises and falls slowly with the music. */
    fun pathAt(z: Float): Float {
        val own = ownPathAt(z)
        val b = beforeAt(z) ?: return own
        val k = ownAt(z)
        return b.land.pathAt(z + b.atZ) * (1 - k) + own * k
    }

    private fun ownPathAt(z: Float): Float = Places.path(places, z, rawPathAt(z))

    /** The way's height at [z] from the music alone (before what places do to it). */
    internal fun rawPathAt(z: Float): Float {
        val i = floor(z).toInt()
        val f = smooth(z - i)
        val s = slow(i) + (slow(i + 1) - slow(i)) * f
        // Up on the loud parts, down to the water's edge on the quiet ones.
        val land = WATER + 1.2f + s * 11f
        if (look.world == World.REEF && look.traveller == com.sadurni.attacca.diorama.Traveller.SUBMARINE) {
            // Under the sea: a beach, shallow water where the submarine floats at the surface, then
            // down to the sea's floor (and back up to a beach before the end when it is left there).
            val shore = WATER + 0.6f + (WATER - 2.2f - WATER - 0.6f) * smoothstep(startZ - 6f, startZ - 1f, z) *
                (if (leavesSub) 1f - smoothstep(subParkZ + 1f, subParkZ + 5f, z) else 1f)
            val deep = WATER - 15f + s * 5f
            val dive = subDive(z)
            return shore + (deep - shore) * dive
        }
        // On the ice and the Amazon the way turns into water a little after the start (where the iceberg or the canoe waits).
        if (look.world == World.ICE || look.world == World.AMAZON) {
            var sea = smoothstep(startZ - 3f, startZ + 2f, z)
            // Back to land before the end when the next song goes on otherwise (the canoe or the iceberg is left at the shore).
            val nextFloats = leavesTo.let { it == com.sadurni.attacca.diorama.Traveller.ICEBERG || it == com.sadurni.attacca.diorama.Traveller.CANOE }
            if (next != null && !nextFloats && endZ < Float.MAX_VALUE) sea *= 1f - smoothstep(endZ - Cast.DISMOUNT - 7f, endZ - Cast.DISMOUNT + 1f, z)
            return land + (WATER - 1.2f - land) * sea
        }
        return land
    }

    private val leavesSub get() = next != null && leavesTo != com.sadurni.attacca.diorama.Traveller.SUBMARINE && endZ < Float.MAX_VALUE
    private val subParkZ get() = endZ - Cast.DISMOUNT

    /**
     * How far down the submarine has gone at [z] (0 floating at the surface, 1 down near the
     * sea's floor): it dives after the start and comes up again before it is left at the end.
     */
    fun subDive(z: Float): Float {
        var d = if (arrives) 1f else smoothstep(startZ + 3f, startZ + 28f, z)
        if (leavesSub) d *= 1f - smoothstep(subParkZ - 28f, subParkZ - 3f, z)
        return d
    }

    private val bendCache = java.util.concurrent.ConcurrentHashMap<Int, Float>()

    /** Where the way goes sideways at a bend's start: every [BEND] rows somewhere else. */
    private fun bend(k: Int): Float = bendCache.getOrPut(k) { (hash(k, 977) / 2147483647f - 0.5f) * 2f * BEND_REACH }

    /**
     * Where the way is across (x) at [z]: it winds, in long gentle curves and sharper turns from
     * one bend to the next, never far from the middle of the land.
     */
    fun centerAt(z: Float): Float {
        val own = ownCenterAt(z)
        val b = beforeAt(z) ?: return own
        val k = ownAt(z)
        return b.land.centerAt(z + b.atZ) * (1 - k) + own * k
    }

    private fun ownCenterAt(z: Float): Float {
        val k = floor(z / BEND).toInt()
        val f = z / BEND - k
        // Mostly straight, then a turn about the middle of each stretch: the turns are the sharp
        // ones, never so sharp that the figures going round them look odd.
        val t = smoothstep(0.15f, 0.85f, f)
        val turns = bend(k) + (bend(k + 1) - bend(k)) * t
        val sway = 4f * kotlin.math.sin(z * 0.045f + seed * 0.1f) + 1.4f * kotlin.math.sin(z * 0.11f + 1.7f)
        return turns + sway
    }

    /** The way's heading at [z] (radians; 0 straight ahead, positive towards +x). */
    fun headingAt(z: Float): Float = kotlin.math.atan2(centerAt(z + 2.5f) - centerAt(z - 2.5f), 5f)

    /** The way's heading at [z] over a longer stretch: for the figures, who round a sharp turn rather than snap to it. */
    fun softHeadingAt(z: Float): Float = kotlin.math.atan2(centerAt(z + 5f) - centerAt(z - 5f), 10f)

    /** How much of the land (rather than the way) is at [x] across from the way's middle: 0 on the way, 1 off it. */
    fun offPath(across: Float): Float = smoothstep(PATH_HALF, PATH_HALF + 2.5f, abs(across))

    /** Distance across from the way's middle at ([x], [z]), square to the way. */
    fun acrossAt(x: Float, z: Float): Float = (x - centerAt(z)) * kotlin.math.cos(headingAt(z))

    /** The land's height at ([x], [z]). */
    fun heightAt(x: Float, z: Float): Float {
        val own = ownHeightAt(x, z)
        val b = beforeAt(z) ?: return own
        val k = ownAt(z)
        return b.land.heightAt(x, z + b.atZ) * (1 - k) + own * k
    }

    private fun ownHeightAt(x: Float, z: Float): Float {
        val across = acrossAt(x, z)
        val ax = abs(across)
        val p = pathAt(z)
        val off = offPath(across)
        if (off <= 0f) return p
        // The intensity a little before or after, by a slow noise across: mountains, not stripes.
        val i = intensityAt(z + (noise(x * 0.06f, z * 0.025f) - 0.5f) * 16f)
        val n = noise(x * 0.05f + 11f, z * 0.05f)
        val fine = noise(x * 0.19f, z * 0.19f + 7f)
        // An open valley about the way, wide when it is quiet; past it the land rises, as high and
        // as steep as the song is loud there.
        val valley = 3f + 10f * (1 - i) + 6f * noise(z * 0.035f, x * 0.01f + 3f)
        val d = ax - PATH_HALF
        val out = (d - valley).coerceAtLeast(0f)
        val cap = 2f + i * i * (MAX_HEIGHT - 4) * (0.5f + 0.5f * n)
        // A soft limit (not a flat top): the higher, the slower it climbs.
        val raw = out * (0.12f + 0.6f * i) * (0.6f + 0.8f * n)
        val rise = cap * (1 - 1 / (1 + raw / cap.coerceAtLeast(0.1f)))
        val edge = abs(x)
        val ridge = if (edge > HALF_WIDTH - 14) (edge - (HALF_WIDTH - 14)) * 0.45f * (0.4f + i) else 0f
        // Quiet: the valley's floor sinks into lakes; loud: gentle mounds.
        val floor = -2.6f * (1 - i) + (fine - 0.45f) * 2.6f * (0.3f + i)
        var h = p + off * (floor + rise + ridge)
        if (look.world == World.COAST) {
            // The coast: on one side, past a beach, the land goes down into the sea.
            val shore = PATH_HALF + 7f + 6f * noise(z * 0.02f + 40f, 1.7f)
            val seaward = across * coastSide
            val sea = smoothstep(shore, shore + 9f, seaward)
            h += (WATER - 3.5f - h) * sea
        }
        // The places last: what stands on them stands on their ground (a big wheel by the sea on a pier of land).
        if (places.isNotEmpty()) h = Places.height(this, places, x, z, across, p, h)
        return h
    }

    /** The coast's sea is on this side of the way (1 right, -1 left). */
    private val coastSide = if (seed and 1 == 0) 1f else -1f

    /** The colour (ARGB) of the land at ([x], [z]) of height [h], where its slope's upright part is [ny] (1 flat). */
    fun colorAt(x: Float, z: Float, h: Float, ny: Float): Int {
        val (a, b, tt) = lookBlend(z)
        // A ragged edge where one world turns into the other.
        val t = if (tt > 0f && tt < 1f) (tt + (noise(x * 0.09f + 30f, z * 0.09f) - 0.5f) * 0.7f * (1f - abs(tt * 2 - 1))).coerceIn(0f, 1f) else tt
        var own = if (t >= 1f) colorIn(b.world, x, z, h, ny) else colorIn(a.world, x, z, h, ny)
        if (t > 0f && t < 1f) own = mix(own, colorIn(b.world, x, z, h, ny), t)
        val before = beforeAt(z) ?: return own
        return mix(before.land.colorAt(x, z + before.atZ, h, ny), own, ownAt(z))
    }

    /** The water's colour at row [z] (each stretch's world its own). */
    fun waterColorAt(z: Float): Int {
        // Changing slowly from one world's water to the next's, never at once.
        val (a, b, t) = lookBlend(z)
        val own = if (t <= 0f) Bands.water(a.world) else mix(Bands.water(a.world), Bands.water(b.world), t)
        val before = beforeAt(z) ?: return own
        return mix(before.land.waterColorAt(z + before.atZ), own, ownAt(z))
    }

    private fun colorIn(world: World, x: Float, z: Float, h: Float, ny: Float): Int {
        val bands = Bands.of(world)
        var c = bands.at(h / MAX_HEIGHT)
        // Steep slopes show rock.
        val steep = smoothstep(0.78f, 0.55f, ny)
        if (steep > 0f) c = mix(c, bands.rock, steep * 0.8f)
        // The way, fading into the land at its edges.
        val off = offPath(acrossAt(x, z))
        if (off < 1f) c = mix(Bands.path(world), c, off)
        // Broad patches of lighter and darker, as grass and earth vary.
        val v = (noise(x * 0.11f + 5f, z * 0.11f) - 0.5f) * 0.16f + (noise(x * 0.5f, z * 0.5f) - 0.5f) * 0.06f
        return scale(c, 1f + v)
    }

    val waterColor: Int get() = Bands.water(look.world)

    private fun smooth(t: Float) = t * t * (3 - 2 * t)

    private fun smoothstep(a: Float, b: Float, x: Float): Float {
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }

    private fun hash(a: Int, b: Int): Int {
        var h = a * 374761393 + b * 668265263 + seed * 1442695041
        h = (h xor (h ushr 13)) * 1274126177
        return (h xor (h ushr 16)) and 0x7FFFFFFF
    }

    /** Smooth value noise, 0 … 1. */
    fun noise(x: Float, z: Float): Float {
        val xi = floor(x).toInt()
        val zi = floor(z).toInt()
        val fx = smooth(x - xi)
        val fz = smooth(z - zi)
        fun v(a: Int, b: Int) = hash(a, b) / 2147483647f
        val top = v(xi, zi) + (v(xi + 1, zi) - v(xi, zi)) * fx
        val bottom = v(xi, zi + 1) + (v(xi + 1, zi + 1) - v(xi, zi + 1)) * fx
        return top + (bottom - top) * fz
    }
}

internal fun mix(a: Int, b: Int, t: Float): Int {
    fun ch(s: Int) = (((a shr s) and 0xFF) * (1 - t) + ((b shr s) and 0xFF) * t).roundToInt().coerceIn(0, 255)
    return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

internal fun scale(c: Int, k: Float): Int {
    fun ch(s: Int) = (((c shr s) and 0xFF) * k).roundToInt().coerceIn(0, 255)
    return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

/** Colours of the land by height (0 low … 1 the highest), one set for each world, blended smoothly. */
class Bands(private val stops: List<Pair<Float, Int>>, val rock: Int) {
    fun at(f: Float): Int {
        if (f <= stops.first().first) return stops.first().second
        for (k in 1 until stops.size) {
            val (b, cb) = stops[k]
            if (f < b) {
                val (a, ca) = stops[k - 1]
                val t = ((f - a) / (b - a)).coerceIn(0f, 1f)
                // Mostly the lower colour, blending into the next near the boundary.
                return mix(ca, cb, min(1f, (t - 0.55f).coerceAtLeast(0f) / 0.45f).let { it * it * (3 - 2 * it) })
            }
        }
        return stops.last().second
    }

    companion object {
        private fun c(v: Long) = v.toInt()

        private val MEADOW = Bands(
            listOf(0f to c(0xFFE0C88E), 0.08f to c(0xFF8CC657), 0.22f to c(0xFF5FA843), 0.4f to c(0xFF3D8040),
                0.58f to c(0xFF8C7F70), 0.74f to c(0xFFA9A196), 0.86f to c(0xFFF6F8FA)),
            c(0xFF8E8173),
        )
        private val COAST = Bands(
            listOf(0f to c(0xFFF2DEA4), 0.14f to c(0xFFE6CD88), 0.24f to c(0xFF96C866), 0.42f to c(0xFF66A64E),
                0.6f to c(0xFFC0A27A), 0.8f to c(0xFFEDE6D8)),
            c(0xFFB49572),
        )
        private val CASTLE = Bands(
            listOf(0f to c(0xFF4E4E3E), 0.1f to c(0xFF5C6744), 0.26f to c(0xFF48503A), 0.42f to c(0xFF625C68),
                0.62f to c(0xFF77708A), 0.82f to c(0xFFC0BBD0)),
            c(0xFF5E5866),
        )
        private val CITY = Bands(
            listOf(0f to c(0xFF98A4A8), 0.1f to c(0xFF88B66A), 0.26f to c(0xFFBCB4A6), 0.44f to c(0xFF96959E),
                0.64f to c(0xFF76849A), 0.82f to c(0xFFD0DCE8)),
            c(0xFF8A8A92),
        )
        private val NEON = Bands(
            listOf(0f to c(0xFF221E38), 0.12f to c(0xFF2E2756), 0.3f to c(0xFF432F78), 0.5f to c(0xFF9030B8),
                0.68f to c(0xFFE8389E), 0.84f to c(0xFF46EAF2)),
            c(0xFF3A2E5A),
        )
        private val ICE = Bands(
            listOf(0f to c(0xFFC4DAE8), 0.12f to c(0xFFDDEBF4), 0.32f to c(0xFFEDF5FA), 0.55f to c(0xFFA2CCE8),
                0.75f to c(0xFFFFFFFF)),
            c(0xFF9CC2DC),
        )
        private val SPACE = Bands(
            listOf(0f to c(0xFF4E485E), 0.12f to c(0xFF625C76), 0.3f to c(0xFF7B748E), 0.5f to c(0xFF948DAA),
                0.7f to c(0xFFBDB6D6), 0.86f to c(0xFFA0F2FF)),
            c(0xFF5A5468),
        )

        private val DESERT = Bands(
            listOf(0f to c(0xFFF2D49A), 0.12f to c(0xFFE8C07A), 0.3f to c(0xFFDBA862), 0.5f to c(0xFFC98A4E),
                0.7f to c(0xFFB0703E), 0.86f to c(0xFFE8D2B0)),
            c(0xFFA86A3E),
        )
        private val WALL = Bands(
            listOf(0f to c(0xFFC9B98A), 0.08f to c(0xFF7FA860), 0.24f to c(0xFF5A8A48), 0.42f to c(0xFF46703E),
                0.6f to c(0xFF8C8478), 0.78f to c(0xFFB0AAA0), 0.9f to c(0xFFF2F4F6)),
            c(0xFF8A8278),
        )
        private val JUNGLE = Bands(
            listOf(0f to c(0xFFB8955A), 0.08f to c(0xFF5E9A3E), 0.22f to c(0xFF3E8034), 0.42f to c(0xFF2E6A2E),
                0.62f to c(0xFF8A7050), 0.8f to c(0xFFA88C66)),
            c(0xFF8A6A44),
        )
        private val AMAZON = Bands(
            listOf(0f to c(0xFF7A6A44), 0.08f to c(0xFF3E7A34), 0.22f to c(0xFF2A6A2E), 0.42f to c(0xFF1E5A2A),
                0.66f to c(0xFF2E5034)),
            c(0xFF5A4A34),
        )
        private val CANYON = Bands(
            listOf(0f to c(0xFFE0B080), 0.1f to c(0xFFD49460), 0.22f to c(0xFFC4673E), 0.34f to c(0xFFD88A58),
                0.46f to c(0xFFB5583A), 0.58f to c(0xFFE0A070), 0.7f to c(0xFFA84A30), 0.84f to c(0xFFD0884E)),
            c(0xFFB0603C),
        )
        private val JAPAN = Bands(
            listOf(0f to c(0xFFD8C8A0), 0.08f to c(0xFF8CBE6A), 0.24f to c(0xFF6AA052), 0.42f to c(0xFF4E8040),
                0.6f to c(0xFF8A8C90), 0.78f to c(0xFFB4B6BC), 0.88f to c(0xFFF8FAFC)),
            c(0xFF8A8478),
        )
        private val REEF = Bands(
            listOf(0f to c(0xFFE8D8A8), 0.14f to c(0xFFD8C890), 0.3f to c(0xFF8AB8A0), 0.5f to c(0xFF5E9A9A),
                0.7f to c(0xFF4A7A8A)),
            c(0xFF6A8A8A),
        )
        private val VOLCANO = Bands(
            listOf(0f to c(0xFF3A2E2C), 0.12f to c(0xFF2A2224), 0.3f to c(0xFF3E3030), 0.5f to c(0xFF4A3634),
                0.7f to c(0xFF5A3A30), 0.86f to c(0xFF7A4030)),
            c(0xFF2A2224),
        )

        fun of(w: World): Bands = when (w) {
            World.DESERT -> DESERT
            World.WALL -> WALL
            World.JUNGLE -> JUNGLE
            World.AMAZON -> AMAZON
            World.CANYON -> CANYON
            World.JAPAN -> JAPAN
            World.REEF -> REEF
            World.VOLCANO -> VOLCANO
            World.MEADOW -> MEADOW
            World.COAST -> COAST
            World.CASTLE -> CASTLE
            World.CITY -> CITY
            World.NEON -> NEON
            World.ICE -> ICE
            World.SPACE -> SPACE
        }

        fun path(w: World): Int = when (w) {
            World.MEADOW, World.COAST -> c(0xFFC49A66)
            World.CASTLE -> c(0xFF6E645C)
            World.CITY -> c(0xFF64666E)
            World.NEON -> c(0xFF30304A)
            World.ICE -> c(0xFFF4FAFF)
            World.SPACE -> c(0xFF36324A)
            World.DESERT -> c(0xFFE6C690)
            World.WALL -> c(0xFFB0A288)
            World.JUNGLE -> c(0xFFA87E50)
            World.AMAZON -> c(0xFF6A5A40)
            World.CANYON -> c(0xFFC89A6A)
            World.JAPAN -> c(0xFFC8B89A)
            World.REEF -> c(0xFFF2E6C0)
            World.VOLCANO -> c(0xFF4A3A36)
        }

        fun water(w: World): Int = when (w) {
            World.MEADOW -> c(0xFF3F96DC)
            World.COAST -> c(0xFF30AEDE)
            World.CASTLE -> c(0xFF2E4260)
            World.CITY -> c(0xFF4C8CC0)
            World.NEON -> c(0xFF5E228E)
            World.ICE -> c(0xFF2466A0)
            World.SPACE -> c(0xFF241E40)
            World.DESERT -> c(0xFF2FA8C8)
            World.WALL -> c(0xFF4A86A8)
            World.JUNGLE -> c(0xFF3E8A8A)
            World.AMAZON -> c(0xFF5A7048)
            World.CANYON -> c(0xFF3E7AA0)
            World.JAPAN -> c(0xFF4A90C0)
            World.REEF -> c(0xFF1E6A8A)
            // In the volcano the low places are lava, glowing.
            World.VOLCANO -> (c(0xFFFF6A1A) and 0xFFFFFF) or com.sadurni.attacca.world.GLOW
        }
    }
}
