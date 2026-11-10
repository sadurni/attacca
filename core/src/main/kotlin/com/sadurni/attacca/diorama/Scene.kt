package com.sadurni.attacca.diorama

import com.sadurni.attacca.diorama.Diorama.Companion.hash
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * One frame's drawing: [look] gives the colours and weather of the moment (mixed while tracks
 * change); each [land] draws one track's land at its own pace.
 */
internal class Scene(
    val out: DrawList,
    val w: Float,
    val h: Float,
    val look: SongLook,
    val now: Long,
    val playing: Boolean,
    val live: LiveLevels,
    val moves: Moves,
    val driftMs: Double,
    /** Where the picture ends: the land lies in [h], the sky and the weather go down to here. */
    val bottom: Float = h,
    /**
     * Shown only from here down (a strip, as Now playing shows it): the far hills stay low and
     * the sky's things (sun or moon, clouds, birds…) come down into the part shown.
     */
    val stripTop: Float = 0f,
    /** How much is shown from [stripTop] down (0: all of it). */
    val stripHeight: Float = 0f,
) {
    val p = look.palette.vivid()
    val unit = min(w, h)
    val travellerX = w * 0.3f

    // The land being drawn (set by land()).
    var lane: Lane = Lane(look, null, null, null, 0.0, 0.0)
    var msPerPx = msPerPxOf(look)
    var posMs = 0.0

    /** Milliseconds of music per pixel of the ground: faster music, a quicker ride. Fixed for a track. */
    fun msPerPxOf(l: SongLook) = (16_000.0 * (110.0 / l.bpm.coerceIn(60f, 180f)) * (1.15 - 0.3 * l.energy)) / max(w, h)

    /** The moment shown at [x] on a layer [zoom] times as far (farther: more music, slower). */
    fun msAt(x: Float, zoom: Float) = posMs + (x - travellerX) * msPerPx * zoom

    fun xAt(ms: Double, zoom: Float) = (travellerX + (ms - posMs) / (msPerPx * zoom)).toFloat()

    // Never smoother than ~a second of music per layer step: a short track's wave is fine-grained and would turn jagged.
    fun heightAt(x: Float, zoom: Float, smoothPx: Float) = lane.height(msAt(x, zoom), max(msPerPx * zoom * smoothPx, 900.0 * zoom))

    // Layers: where their foot is and how high the loudest music lifts them.
    val farBase = h * 0.62f
    val farAmp = h * 0.36f * (if (stripTop > 0f) 0.4f else 1f)
    val midBase = h * 0.78f
    val midAmp = h * 0.27f
    // In a strip the near ground stays higher, clear of the strip's bottom edge.
    val nearBase = h * (if (stripTop > 0f) 0.86f else 0.93f)
    val nearAmp = h * 0.15f

    /**
     * The near ground follows the music: in the open (fields, paths, sand…) as closely as it
     * always did; roads are smoothed, and bridges and stations level. Where the way is smoothed or
     * level, the music's rises stand on it as things the traveller jumps over ([wayObstacles],
     * [jumpLift]): nobody floats over bumps that are not there.
     */
    fun nearY(x: Float) = nearBase - nearShown(x) * nearAmp

    /** The music's detail at [x], as the ground used to show it (0 … 1). */
    fun nearRaw(x: Float) = heightAt(x, 1f, 28f)

    private val roadWorld get() = lane.look.world == World.CITY || lane.look.world == World.NEON

    /** The ground's height at [x] (0 … 1): the music smoothed, level over bridges and stations. */
    fun nearShown(x: Float): Float {
        val smooth = if (roadWorld) lane.height(msAt(x, 1f), max(msPerPx * 28.0, ROAD_SMOOTH_MS)) else nearRaw(x)
        val f = flats
        var k = 0
        while (k < f.size) {
            val r0 = f[k]; val a = f[k + 1]; val b = f[k + 2]; val r1 = f[k + 3]
            if (x > r0 && x < r1) {
                val t = when {
                    x < a -> ease((x - r0) / (a - r0))
                    x > b -> ease((r1 - x) / (r1 - b))
                    else -> 1f
                }
                return smooth + (f[k + 4] - smooth) * t
            }
            k += FLAT_FIELDS
        }
        return smooth
    }

    private fun ease(u: Float) = u * u * (3f - 2f * u)

    /** How far the music rises above the ground shown at [x], in pixels. */
    fun hurdle(x: Float) = max(0f, nearRaw(x) - nearShown(x)) * nearAmp

    /** Where the way stays level this frame: [ramp start, start, end, ramp end, height, kind] each. */
    var flats = FloatArray(0)

    /** Whether [x] is on a level stretch, and of which kind (see [flatSpans]); -1: none. */
    fun flatKindAt(x: Float): Int {
        var k = 0
        while (k < flats.size) {
            if (x >= flats[k + 1] && x <= flats[k + 2]) return flats[k + 5].toInt()
            k += FLAT_FIELDS
        }
        return -1
    }

    /**
     * How high whoever goes along the ground at [x] is lifted to clear what rises there: the
     * things standing on a level way or a road; nothing elsewhere, where the ground itself rises.
     */
    fun jumpLift(x: Float): Float {
        val ts = min(h * 0.11f, w * 0.12f)
        var best = 0f
        if (roadWorld || flatKindAt(x) >= 0 || flatKindAt(x - ts) >= 0 || flatKindAt(x + ts) >= 0) {
            wayObstacleSlots { _, ox, height ->
                val d = (x - ox) / (ts * 0.95f)
                if (d > -1f && d < 1f) best = max(best, height * 1.05f * (1f - d * d))
            }
        }
        return best
    }

    /** The things standing on a level way or a road: their slot, place and height (px). */
    inline fun wayObstacleSlots(each: (Int, Float, Float) -> Unit) {
        val ts = min(h * 0.11f, w * 0.12f)
        val road = lane.look.world == World.CITY || lane.look.world == World.NEON
        slots(1f, ts * 1.7f) { i, x0, x1 ->
            val x = (x0 + x1) / 2f
            if (!road && flatKindAt(x) < 0) return@slots
            val height = min(hurdle(x) * 1.5f, ts * 0.5f)
            if (height >= ts * 0.18f) each(i, x, height)
        }
    }
    fun midY(x: Float) = midBase - heightAt(x, ZOOM_MID, 22f) * midAmp
    fun farY(x: Float) = farBase - heightAt(x, ZOOM_FAR, 30f) * farAmp

    // Where something [half] wide on each side of [x] stands: the lowest ground under it, so it
    // sits on the slope (its higher side sinks in) instead of floating over it.
    fun footNear(x: Float, half: Float) = max(nearY(x), max(nearY(x - half), nearY(x + half)))
    fun footMid(x: Float, half: Float) = max(midY(x), max(midY(x - half), midY(x + half)))
    fun footFar(x: Float, half: Float) = max(farY(x), max(farY(x - half), farY(x + half)))

    /** Where in the beat the music is (0 … 1), from the playing track's tempo. */
    var beat = 0f

    /** How far into the track's peak moment (0 … 1). */
    var peak = 0f

    /** The festive look on these days (null: none), and whether the Three Kings come. */
    var festive: com.sadurni.attacca.festive.Festivity? = null
    var kings = false

    /** The new year's show going on, or null. */
    var show: Celebration? = null

    /** Milliseconds since the last frame. */
    var dt = 0L

    /** A rabbit running by this frame: where, and which run it is (NaN: none). */
    var rabbitX = Float.NaN
    var rabbitRun = 0

    /** Where the camera looks: the ground under the traveller. */
    fun cameraY() = nearY(travellerX) - min(h * 0.11f, w * 0.12f) * 0.5f

    /** 1 at a strong beat, fading out in a fifth of a second. */
    val kick: Float get() = kotlin.math.exp(-(now - live.onsetAtMs).coerceAtLeast(0) / 200f)

    /**
     * A small jump (0 … 1) on the strong beats, a little later the higher [seed]'s place in a
     * group, so a flock or a row moves like a wave.
     */
    fun hop(seed: Int): Float {
        val age = now - live.onsetAtMs - (seed and 3) * 70
        return if (age in 0..320) sin(PI * age / 320.0).toFloat() else 0f
    }

    val step = max(4f, w / 150f)

    /**
     * Whether a place [i] gets a thing: [base] is its chance in an ordinary part, louder parts of
     * the song get more of them, quiet ones fewer.
     */
    fun dense(i: Int, salt: Int, base: Float, x: Float, zoom: Float): Boolean =
        hash(i, salt) < base * (0.3f + 1.4f * heightAt(x, zoom, 40f))

    fun sky() {
        out.gradient(p.skyTop, p.skyBottom, 0f, 0f, w, bottom)
        // At the track's peak the sky darkens a little, so what happens in it stands out.
        if (peak > 0f) out.rect(Colors.alpha(Colors.darker(p.skyTop, 0.55f), 0.28f * peak), 0f, 0f, w, bottom)
        val skyFrom = out.size
        val night = look.night
        if (night > 0.05f) stars(night)
        if (look.world == World.ICE) aurora()
        // The sun, the moon and space's planets behind everything else in the sky.
        if (look.world == World.SPACE) planets()
        sunOrMoon()
        // The red moon of the peak is far away: behind the clouds.
        if (look.world == World.CASTLE) peakMoment()
        clouds()
        birds()
        if (look.world != World.CASTLE) peakMoment()
        skyVisitor()
        festiveSky()
        // In a strip, what is in the sky is brought down (and closer together) into the part shown, above the low hills.
        if (stripTop > 0f) out.mapYWhole(skyFrom, out.size, stripTop, 0.4f)
    }

    private fun stars(night: Float) {
        val count = (90 * night * (0.7f + 0.6f * look.energy)).toInt()
        for (i in 0 until count) {
            val x = hash(i, 11) * w
            val y = hash(i, 12) * h * 0.62f
            val twinkle = 0.55f + 0.45f * sin(now / 700.0 + i * 1.7).toFloat()
            val r = unit * (0.0025f + 0.003f * hash(i, 13)) * (1f + (live.high * 0.6f + kick * 0.5f) * hash(i, 14))
            out.circle(Colors.alpha(0xFFFFFFFF.toInt(), night * twinkle), x, y, r)
        }
    }

    /** Northern lights over the cold sea, brighter with the sound. */
    private fun aurora() {
        val bands = 3
        for (b in 0 until bands) {
            val c = Colors.alpha(if (b == 1) p.accent else Colors.mix(0xFF5CFFB0.toInt(), p.accent, 0.3f), (0.10f + 0.16f * live.level) * look.night.coerceAtLeast(0.4f))
            out.polygon(c)
            var x = -step
            val base = h * (0.16f + b * 0.07f)
            while (x <= w + step * 3) {
                out.point(x, base + sin(x / (w * 0.22f) + now / (2600.0 + b * 700) + b).toFloat() * h * 0.05f)
                x += step * 3
            }
            x = w + step * 3
            while (x >= -step) {
                out.point(x, base + h * (0.07f + 0.04f * live.high) + sin(x / (w * 0.18f) + now / (3100.0 + b * 500)).toFloat() * h * 0.04f)
                x -= step * 3
            }
        }
    }

    private fun sunOrMoon() {
        if (look.world == World.SPACE) return
        val night = look.night
        // Along the track it goes slowly across the middle of the sky: never in the top corners,
        // where the apps put the title and the buttons.
        val along = lane.wave?.let { (posMs / it.lengthMs).toFloat().coerceIn(0f, 1f) } ?: 0.5f
        val r = unit * (if (night > 0.5f) 0.055f else 0.075f)
        val x = w * (0.38f + 0.3f * along)
        val y = max(h * (0.42f - 0.30f * max(look.sun, night * 0.8f)) - h * 0.05f * sin(PI * along).toFloat(), h * 0.17f + r * 1.6f)
        val glow = 1f + live.high * 0.5f
        touchable(SUN, x, y, r * 1.6f)
        if (moves.recordSunOf == lane.originMs) {
            recordSun(x, y, r * 1.25f)
            return
        }
        // The red moon of the peak takes its place in the sky: the usual one fades away meanwhile.
        val shown = if (look.world == World.CASTLE) 1f - (peak * 3f).coerceIn(0f, 1f) else 1f
        if (shown <= 0f) return
        out.circle(Colors.alpha(p.light, 0.12f * shown * (1f - look.clouds * 0.6f)), x, y, r * 2.4f * glow)
        out.circle(Colors.alpha(p.light, 0.22f * shown * (1f - look.clouds * 0.6f)), x, y, r * 1.6f * glow)
        out.circle(Colors.alpha(p.light, shown), x, y, r)
        if (kick > 0.05f) out.ring(Colors.alpha(p.light, 0.35f * kick * shown), x, y, r * (1.3f + (1f - kick) * 1.6f), unit * 0.004f)
        if (night > 0.5f) out.circle(Colors.alpha(Colors.mix(p.skyTop, p.skyBottom, 0.3f), (night - 0.5f) * 2f * shown), x + r * 0.45f, y - r * 0.2f, r * 0.85f)
    }

    /** The sun as a record turning in the sky, the cover on its label, notes falling from it. */
    private fun recordSun(x: Float, y: Float, r: Float) {
        out.circle(Colors.alpha(p.light, 0.15f + 0.2f * kick), x, y, r * 1.35f)
        out.circle(0xFF111114.toInt(), x, y, r)
        for (k in 1..4) out.ring(0x2AFFFFFF, x, y, r * (0.45f + 0.13f * k), unit * 0.0025f)
        val turn = (moves.spinMs / 600.0).toFloat()
        out.cover(p.accent, x, y, r * 0.62f, turn)
        out.circle(0xFF111114.toInt(), x, y, r * 0.06f)
        // A glint going round.
        out.circle(Colors.alpha(0xFFFFFFFF.toInt(), 0.35f), x + cos(turn * 0.7f) * r * 0.8f, y + sin(turn * 0.7f) * r * 0.8f, r * 0.06f)
        for (k in 0 until 6) {
            val cycle = 2600L
            val t = (now + k * 433L) % cycle / cycle.toFloat()
            val nx = x + (hash(k + (now / cycle).toInt() * 7, 171) - 0.5f) * r * 3f + sin(t * 6.0 + k).toFloat() * r * 0.2f
            val ny = y + r * 0.8f + t * h * 0.35f
            val note = if (look.night > 0.5f) p.light else Colors.darker(p.accent, 0.25f)
            out.text(Colors.alpha(note, 0.95f * (1f - t * t)), nx, ny, r * 0.7f, if (k % 2 == 0) "♪" else "♫")
        }
    }

    private fun clouds() {
        val n = (look.clouds * 8f).toInt()
        if (n == 0) return
        val color = Colors.mix(0xFFFFFFFF.toInt(), 0xFF5A6070.toInt(), max(look.storm, look.rain * 0.6f) + look.night * 0.5f)
        val span = w * 1.6f
        for (i in 0 until n) {
            val speed = 0.006f + 0.01f * hash(i, 21) + look.storm * 0.02f
            val x = ((hash(i, 22) * span - now * speed * unit / 400f - (driftMs / 60.0).toFloat()) % span + span) % span - w * 0.3f
            val s = unit * (0.06f + 0.06f * hash(i, 24)) * (1f + look.storm * 0.6f)
            val y = h * (0.06f + 0.30f * hash(i, 23)) + sin(beat * 2 * PI + i).toFloat() * s * 0.06f * (0.3f + live.level)
            // Whole: the sun or moon never shows through a cloud passing in front of it (the red
            // moon's glow a little, while it is up).
            val c = Colors.alpha(color, if (look.world == World.CASTLE && peak > 0f) 0.97f - 0.15f * peak else 0.97f)
            out.circle(c, x, y, s)
            out.circle(c, x + s * 0.9f, y + s * 0.15f, s * 0.8f)
            out.circle(c, x - s * 0.9f, y + s * 0.2f, s * 0.7f)
            out.circle(c, x + s * 0.3f, y - s * 0.45f, s * 0.75f)
        }
    }

    private fun birds() {
        if (look.night > 0.4f || look.world !in setOf(World.MEADOW, World.COAST, World.CITY) || look.storm > 0.3f) return
        val cycle = 26_000.0
        val ph = ((now % cycle.toLong()) / cycle).toFloat()
        if (ph > 0.55f) return
        val baseX = w * 1.15f - ph / 0.55f * w * 1.4f
        val baseY = h * (0.16f + 0.06f * hash((now / cycle.toLong()).toInt(), 31))
        val color = Colors.darker(p.far, 0.5f)
        if (look.world == World.CITY) {
            // A plane with its light blinking.
            val s = unit * 0.035f
            // Flying left: the nose first, the tail fin at the back.
            out.polygon(color)
            out.point(baseX - s * 1.7f, baseY); out.point(baseX - s * 1.4f, baseY - s * 0.22f); out.point(baseX + s * 1.5f, baseY - s * 0.22f)
            out.point(baseX + s * 1.7f, baseY - s * 0.7f); out.point(baseX + s * 1.95f, baseY - s * 0.7f); out.point(baseX + s * 1.85f, baseY + s * 0.18f); out.point(baseX - s * 1.4f, baseY + s * 0.18f)
            out.polygon(Colors.darker(color, 0.2f))
            out.point(baseX - s * 0.3f, baseY); out.point(baseX + s * 0.6f, baseY + s * 0.9f); out.point(baseX + s * 0.9f, baseY + s * 0.9f); out.point(baseX + s * 0.4f, baseY)
            if ((now / 500) % 2 == 0L) out.circle(0xFFFF4040.toInt(), baseX + s * 1.9f, baseY - s * 0.7f, s * 0.15f)
            return
        }
        flock(baseX, baseY, 4, color, unit * 0.016f)
    }

    /** [n] small birds flapping on the beat, the first at [x], [y]; they rise in a wave on strong beats. */
    fun flock(x: Float, y: Float, n: Int, color: Int, s: Float) {
        for (b in 0 until n) {
            val bx = x + b * s * 3.1f
            val by = y + (if (b % 2 == 0) 0f else s * 1.5f) + sin(now / 600.0 + b).toFloat() * s * 0.4f - hop(b) * s * 2.5f - live.low * s * 1.2f
            bird(bx, by, color, s, b)
        }
    }

    /** One bird, its wings beating twice a beat (more widely the louder). */
    fun bird(x: Float, y: Float, color: Int, s: Float, seed: Int) {
        val flap = cos((beat * 2f + seed * 0.13f) * 2 * PI).toFloat() * (0.6f + 0.6f * live.level)
        out.polyline(color, s * 0.25f)
        out.point(x - s, y - s * 0.5f * flap); out.point(x, y); out.point(x + s, y - s * 0.5f * flap)
    }

    /** Sets the track [l] as the one drawn and handles what the music just did (beats, lightning). */
    fun react(l: Lane) {
        dt = moves.tick(now, live.level, playing)
        use(l)
        val beats = posMs / 60_000.0 * l.look.bpm
        beat = (beats - floor(beats)).toFloat()
        peak = peakStrength()
        // Touched: a move now, whatever the beat.
        if (moves.wantTrick) {
            moves.wantTrick = false
            touchedMove(l.look)
        }
        val onset = live.onsetAtMs
        if (onset != moves.handledOnset) {
            moves.handledOnset = onset
            moves.onsets++
            onBeat(l.look)
            if (look.storm > 0.3f && now - moves.boltAtMs > 1200) bolt()
        }
        idleMove(l.look)
    }

    private fun use(l: Lane) {
        lane = l
        msPerPx = msPerPxOf(l.look)
        posMs = l.posMs
        flats = FloatArray(0)
        flats = flatSpans()
    }

    fun bolt() {
        moves.boltAtMs = now
        moves.boltX = w * (0.1f + 0.8f * hash((now / 97).toInt(), 151))
    }

    fun land(l: Lane, alpha: Float) {
        use(l)
        out.fade = alpha
        val song = l.look
        // The colours are those of the moment (mixed while changing); the shapes the track's own.
        when (song.world) {
            World.CITY -> { farMountains(p.far, smooth = false, skyline = true); buildings(false); billboards(false); road(false); cityThings() }
            World.NEON -> { farMountains(p.far, smooth = false, skyline = true); buildings(true); billboards(true); road(true); neonThings() }
            World.CASTLE -> { farMountains(p.far, smooth = false, skyline = false); castles(); banners(); darkGround() }
            World.MEADOW -> { farMountains(p.far, smooth = true, skyline = false); hillsWithTrees(); kites(); grass(); meadowThings() }
            World.COAST -> { farMountains(p.far, smooth = true, skyline = false); sea(); floatingAlbums(midBase - midAmp * 0.25f, ZOOM_MID); sand(); beachThings() }
            World.ICE -> { iceCliffs(); icebergs(); floatingAlbums(farBase + h * 0.07f, ZOOM_MID); coldSea() }
            // (Its planets are drawn with the sky, behind what flies by.)
            World.SPACE -> { ridge(); buriedAlbums(); moonGround(); spaceThings() }
        }
        landmarksBack()
        finish()
        wayObstacles()
        groundVisitor(song.world)
        crowd(song)
        traveller(song)
        companion(song)
        landmarksFront()
        foreground()
        out.fade = 1f
    }

    // ---------- Far ----------

    private fun farMountains(color: Int, smooth: Boolean, skyline: Boolean) {
        if (skyline) {
            slots(ZOOM_FAR, h * 0.05f) { i, x0, x1 ->
                val v = heightAt((x0 + x1) / 2f, ZOOM_FAR, 30f)
                val top = farBase + h * 0.04f - (0.25f + 0.75f * v) * farAmp * (0.55f + 0.45f * hash(i, 41))
                out.rect(color, x0, top, x1 - h * 0.004f, bottom)
                if (hash(i, 42) < 0.3f) out.rect(color, (x0 + x1) / 2f - h * 0.002f, top - h * 0.03f, (x0 + x1) / 2f + h * 0.002f, top)
            }
            return
        }
        out.polygon(color)
        out.point(0f, bottom)
        var x = 0f
        var k = 0
        while (x <= w + step) {
            val jag = if (smooth) 0f else (if (k % 2 == 0) -1f else 1f) * h * 0.018f * hash((msAt(x, ZOOM_FAR) / 900).toInt(), 43)
            out.point(x, farY(x) + jag)
            x += if (smooth) step else step * 2.2f
            k++
        }
        out.point(w, bottom)
        if (smooth && lane.look.world == World.COAST) {
            // Now and then a lighthouse on the far cape, its light turning.
            slotsMs(ZOOM_FAR, 70_000.0) { i, x0, x1 ->
                if (hash(i, 44) > 0.6f) return@slotsMs
                val cx = x0 + (x1 - x0) * hash(i, 45)
                lighthouse(cx, footFar(cx, h * 0.015f) + h * 0.01f, h * 0.09f)
            }
        }
        if (smooth && lane.look.world == World.MEADOW) {
            // A second, nearer ridge, a little darker.
            out.polygon(Colors.mix(color, p.mid, 0.4f))
            out.point(0f, bottom)
            x = 0f
            while (x <= w + step) {
                out.point(x, farBase + h * 0.06f - heightAt(x, ZOOM_FAR * 0.6f, 50f) * farAmp * 0.6f)
                x += step
            }
            out.point(w, bottom)
        }
    }

    private fun lighthouse(cx: Float, ground: Float, s: Float) {
        val body = Colors.lighter(p.far, 0.55f)
        // Its rock, darker than the far land, down into it: it never seems to float.
        out.polygon(Colors.darker(p.far, 0.35f))
        out.point(cx - s * 0.42f, ground + s * 0.35f); out.point(cx - s * 0.3f, ground - s * 0.02f); out.point(cx - s * 0.12f, ground - s * 0.06f)
        out.point(cx + s * 0.16f, ground - s * 0.05f); out.point(cx + s * 0.34f, ground + s * 0.02f); out.point(cx + s * 0.46f, ground + s * 0.35f)
        out.polygon(body)
        out.point(cx - s * 0.16f, ground); out.point(cx - s * 0.1f, ground - s); out.point(cx + s * 0.1f, ground - s); out.point(cx + s * 0.16f, ground)
        out.rect(Colors.mix(p.accent, 0xFFD03030.toInt(), 0.5f), cx - s * 0.135f, ground - s * 0.55f, cx + s * 0.135f, ground - s * 0.4f)
        out.rect(Colors.darker(p.far, 0.4f), cx - s * 0.12f, ground - s * 1.12f, cx + s * 0.12f, ground - s)
        val turn = (now / 1800.0) * 2 * PI
        val beam = Colors.alpha(p.light, (0.25f + 0.3f * look.night) * max(0f, cos(turn).toFloat()))
        out.polygon(beam)
        val dir = if (sin(turn) > 0) 1f else -1f
        out.point(cx, ground - s * 1.06f); out.point(cx + dir * s * 3f, ground - s * 1.3f); out.point(cx + dir * s * 3f, ground - s * 0.82f)
        out.circle(p.light, cx, ground - s * 1.06f, s * 0.07f)
    }

    private fun iceCliffs() {
        out.polygon(p.far)
        out.point(0f, bottom)
        slots(ZOOM_FAR, h * 0.08f) { i, x0, x1 ->
            val y = farBase + h * 0.05f - heightAt((x0 + x1) / 2f, ZOOM_FAR, 60f) * farAmp * (0.5f + 0.3f * hash(i, 51))
            out.point(x0, y + h * 0.01f)
            out.point(x0 + (x1 - x0) * 0.15f, y)
            out.point(x1 - (x1 - x0) * 0.1f, y)
        }
        out.point(w, bottom)
        // Mist over the water line.
        out.gradient(Colors.alpha(p.skyBottom, 0f), Colors.alpha(p.skyBottom, 0.55f), 0f, farBase - h * 0.05f, w, farBase + h * 0.08f)
    }

    private fun planets() {
        val drift = ((driftMs / 400.0) % (w * 2)).toFloat()
        val r = unit * 0.16f
        // Clear of the title and the buttons in the top corners.
        val x = w * 0.62f - drift * 0.02f
        val y = max(h * 0.28f, h * 0.17f + r * 1.2f)
        out.circle(Colors.alpha(p.light, 0.05f + live.high * 0.08f), x, y, r * 1.2f)
        out.circle(p.far, x, y, r)
        out.circle(Colors.alpha(Colors.lighter(p.far, 0.25f), 0.8f), x - r * 0.25f, y - r * 0.2f, r * 0.55f)
        // A tilted ring: an ellipse, its front half over the planet.
        out.polyline(Colors.alpha(p.accent, 0.8f), unit * 0.008f)
        for (k in 0..24) {
            val a = PI * k / 24
            out.point(x + (cos(a) * r * 1.7f).toFloat(), y + (sin(a) * r * 0.35f).toFloat() - (cos(a) * r * 0.2f).toFloat())
        }
        out.circle(Colors.lighter(p.mid, 0.4f), w * 0.2f - drift * 0.05f, h * 0.18f, unit * 0.035f)
    }

    // ---------- Middle ----------

    private fun buildings(neon: Boolean) {
        val night = if (neon) 1f else look.night
        slots(ZOOM_MID, h * 0.075f) { i, x0, x1 ->
            val gap = h * 0.008f * (0.5f + hash(i, 61))
            val v = heightAt((x0 + x1) / 2f, ZOOM_MID, 20f)
            val top = midBase + h * 0.05f - (0.3f + 0.7f * v) * midAmp * 1.6f * (0.6f + 0.4f * hash(i, 62))
            val left = x0 + gap
            val right = x1 - gap
            out.rect(p.mid, left, top, right, bottom)
            val bid = id(BUILDING, i)
            touchable(bid, (left + right) / 2f, (top + min(bottom, h)) / 2f, max(right - left, min(bottom, h) - top) / 2f)
            val switched = bid in moves.switched
            // Windows: lit at night (neon colours in the night city), glass by day; a touch turns them on or off.
            val cell = h * 0.022f
            val cols = ((right - left) / cell).toInt().coerceAtMost(6)
            val rows = ((h - top) / (cell * 1.4f)).toInt().coerceAtMost(14)
            val lit = if (neon) Colors.alpha(if (hash(i, 63) < 0.5f) p.accent else p.light, 0.55f + 0.45f * live.high) else Colors.alpha(p.light, 0.9f)
            val glass = Colors.mix(p.mid, p.skyBottom, 0.35f)
            for (r in 0 until rows) for (c in 0 until cols) {
                val on = hash(i * 131 + r * 17 + c + (if (neon) (moves.onsets / 2) * 7 else 0), 64) < 0.3f + 0.35f * v
                val color = if ((night > 0.4f) != switched) (if (on) lit else continue) else glass
                val wx = left + cell * 0.3f + c * (right - left - cell * 0.3f) / cols
                val wy = top + cell * 0.5f + r * cell * 1.4f
                out.rect(color, wx, wy, wx + cell * 0.55f, wy + cell * 0.75f)
            }
            if (neon && dense(i, 65, 0.22f, (x0 + x1) / 2f, ZOOM_MID)) {
                // A sign on the roof.
                val c = if (hash(i, 66) < 0.5f) p.accent else p.light
                out.rect(Colors.alpha(c, 0.25f + 0.5f * live.level), left, top - h * 0.035f, right, top - h * 0.008f)
                out.rect(c, left + h * 0.006f, top - h * 0.028f, right - h * 0.006f, top - h * 0.015f)
            } else if (!neon && hash(i, 67) < 0.25f) {
                // An antenna or a water tank.
                if (hash(i, 68) < 0.5f) out.line(p.mid, h * 0.003f, (left + right) / 2f, top, (left + right) / 2f, top - h * 0.04f)
                else {
                    out.rect(p.mid, left + (right - left) * 0.3f, top - h * 0.025f, left + (right - left) * 0.6f, top)
                    out.polygon(p.mid); out.point(left + (right - left) * 0.27f, top - h * 0.025f); out.point(left + (right - left) * 0.45f, top - h * 0.04f); out.point(left + (right - left) * 0.63f, top - h * 0.025f)
                }
            }
        }
    }

    private fun castles() {
        val hill = Colors.mix(p.mid, p.far, 0.3f)
        out.polygon(hill)
        out.point(0f, bottom)
        var x = 0f
        while (x <= w + step) {
            out.point(x, midY(x))
            x += step
        }
        out.point(w, bottom)
        slots(ZOOM_MID, h * 0.2f) { i, x0, x1 ->
            val cx = (x0 + x1) / 2f
            val y = midY(cx)
            val roll = hash(i, 71)
            if (roll < 0.22f) {
                val s = h * 0.11f * (0.8f + 0.5f * heightAt(cx, ZOOM_MID, 30f))
                castle(cx, footMid(cx, s * 0.75f) + h * 0.01f, s, p.mid, id(CASTLE, i))
            } else if (dense(i, 74, 0.45f, cx, ZOOM_MID)) {
                val tx = cx + (x1 - x0) * (hash(i, 72) - 0.5f) * 0.6f
                deadTree(tx, midY(tx) + h * 0.005f, h * 0.06f * (0.7f + 0.6f * hash(i, 73)), p.mid)
            }
        }
    }

    private fun castle(cx: Float, ground: Float, s: Float, color: Int, cid: Int) {
        touchable(cid, cx, ground - s * 0.6f, s * 0.8f)
        out.rect(color, cx - s * 0.5f, ground - s * 0.55f, cx + s * 0.5f, ground)
        out.rect(color, cx - s * 0.75f, ground - s * 0.9f, cx - s * 0.45f, ground)
        out.rect(color, cx + s * 0.45f, ground - s * 0.9f, cx + s * 0.75f, ground)
        out.rect(color, cx - s * 0.15f, ground - s * 1.2f, cx + s * 0.15f, ground - s * 0.5f)
        for (tower in floatArrayOf(-0.6f, 0.6f, 0f)) {
            val tx = cx + s * tower
            val top = ground - s * (if (tower == 0f) 1.2f else 0.9f)
            out.polygon(color)
            out.point(tx - s * 0.2f, top); out.point(tx, top - s * 0.4f); out.point(tx + s * 0.2f, top)
        }
        for (k in 0 until 4) {
            val bx = cx - s * 0.5f + k * s * 0.3f
            out.rect(color, bx, ground - s * 0.65f, bx + s * 0.12f, ground - s * 0.55f)
        }
        if (cid in moves.switched) {
            // Every window lit instead of the torch.
            for (k in -1..1) out.rect(Colors.alpha(p.light, 0.9f), cx + k * s * 0.6f - s * 0.04f, ground - s * 0.45f, cx + k * s * 0.6f + s * 0.04f, ground - s * 0.3f)
            return
        }
        val torch = Colors.alpha(p.accent, 0.5f + 0.5f * live.level)
        out.circle(Colors.alpha(p.accent, 0.25f * live.level + 0.25f * kick), cx, ground - s * 0.8f, s * (0.25f + 0.1f * kick))
        out.circle(torch, cx, ground - s * 0.8f, s * 0.06f)
    }

    private fun deadTree(x: Float, ground: Float, s: Float, color: Int) {
        val wdt = s * 0.08f
        out.line(color, wdt, x, ground, x, ground - s)
        out.line(color, wdt * 0.7f, x, ground - s * 0.6f, x - s * 0.35f, ground - s * 0.95f)
        out.line(color, wdt * 0.7f, x, ground - s * 0.75f, x + s * 0.3f, ground - s * 1.05f)
        out.line(color, wdt * 0.5f, x - s * 0.2f, ground - s * 0.8f, x - s * 0.3f, ground - s * 1.1f)
    }

    private fun hillsWithTrees() {
        out.polygon(p.mid)
        out.point(0f, bottom)
        var x = 0f
        while (x <= w + step) {
            out.point(x, midY(x))
            x += step
        }
        out.point(w, bottom)
        val dark = Colors.darker(p.mid, 0.3f)
        val light = Colors.lighter(p.mid, 0.12f)
        val sway = sin(beat * 2 * PI).toFloat() * (0.3f + live.level * 0.7f)
        slots(ZOOM_MID, h * 0.06f) { i, x0, x1 ->
            val cx = x0 + (x1 - x0) * hash(i, 82)
            if (!dense(i, 81, 0.3f + look.energy * 0.25f, cx, ZOOM_MID)) {
                // Where there is no tree, now and then a cow or a sheep grazing, or a house.
                val roll = hash(i, 85)
                if (roll < 0.12f) grazer(cx, footMid(cx, h * 0.022f), h * 0.028f, hash(i, 86) < 0.5f, i)
                else if (roll < 0.16f) house(cx, footMid(cx, h * 0.025f) + h * 0.004f, h * 0.045f, hash(i, 87) < 0.4f, id(HOUSE, i))
                return@slots
            }
            val y = midY(cx)
            val s = h * 0.05f * (0.7f + 0.7f * hash(i, 83))
            out.rect(Colors.darker(p.mid, 0.55f), cx - s * 0.07f, y - s * 0.6f, cx + s * 0.07f, y + s * 0.05f)
            if (hash(i, 84) < 0.45f) {
                out.polygon(dark)
                out.point(cx - s * 0.4f, y - s * 0.4f); out.point(cx + sway * s * 0.05f, y - s * 1.5f); out.point(cx + s * 0.4f, y - s * 0.4f)
            } else {
                out.circle(dark, cx + sway * s * 0.04f, y - s * 0.85f, s * 0.42f)
                out.circle(light, cx - s * 0.15f + sway * s * 0.05f, y - s * 1.0f, s * 0.25f)
            }
        }
    }

    /** A cow (patched) or a sheep (woolly), grazing: the head goes down and up. */
    private fun grazer(cx: Float, groundAt: Float, s: Float, cow: Boolean, seed: Int) {
        val gid = id(GRAZER, seed)
        touchable(gid, cx, groundAt - s * 0.6f, s * 1.1f)
        // Touched: a jump for joy (a cow too).
        val jump = bounce(poked(gid), 800)
        val ground = groundAt - (jump?.get(0) ?: 0f) * s * 1.6f
        val body = if (cow) 0xFFF2EFE8.toInt() else 0xFFF6F4EE.toInt()
        val dark = 0xFF2A2522.toInt()
        val legs = s * 0.5f
        for (lx in floatArrayOf(-0.55f, -0.3f, 0.35f, 0.6f)) out.line(dark, s * 0.12f, cx + lx * s, ground - legs, cx + lx * s, ground)
        val by = ground - legs - s * 0.3f
        if (cow) {
            out.rect(body, cx - s * 0.75f, by - s * 0.3f, cx + s * 0.75f, by + s * 0.3f)
            out.circle(dark, cx - s * 0.2f, by - s * 0.05f, s * 0.2f)
            out.circle(dark, cx + s * 0.4f, by + s * 0.1f, s * 0.14f)
        } else {
            for (k in -2..2) out.circle(body, cx + k * s * 0.3f, by + (if (k % 2 == 0) 0f else -s * 0.1f), s * 0.36f)
        }
        val graze = ((sin(now / 1700.0 + seed * 2.1).toFloat() + 1f) / 2f - hop(seed) * 1.5f).coerceIn(-0.3f, 1f)
        val hx = cx + s * 0.95f
        val hy = by - s * 0.15f + graze * s * 0.55f
        out.line(if (cow) body else dark, s * 0.22f, cx + s * 0.6f, by - s * 0.1f, hx, hy)
        out.circle(if (cow) body else dark, hx + s * 0.08f, hy, s * 0.2f)
    }

    fun house(cx: Float, ground: Float, s: Float, windmill: Boolean, hid: Int = 0) {
        val wall = Colors.lighter(p.mid, 0.55f)
        val roof = Colors.mix(0xFFB04A3A.toInt(), p.accent, 0.2f)
        if (hid != 0) touchable(hid, cx, ground - s * 0.5f, s * 0.6f)
        val switched = hid != 0 && hid in moves.switched
        if (windmill) {
            out.polygon(wall)
            out.point(cx - s * 0.3f, ground); out.point(cx - s * 0.18f, ground - s * 1.1f); out.point(cx + s * 0.18f, ground - s * 1.1f); out.point(cx + s * 0.3f, ground)
            val hubY = ground - s * 1.1f
            // Touched: the sails whirl for a few seconds.
            val whirl = poked(hid).let { if (it in 0..4000) (4000 - it) / 4000.0 else 0.0 }
            val a = moves.spinMs / 700.0 + whirl * whirl * 25.0
            for (k in 0 until 4) {
                val ang = a + k * PI / 2
                out.line(Colors.darker(wall, 0.4f), s * 0.06f, cx, hubY, cx + (cos(ang) * s * 0.8f).toFloat(), hubY + (sin(ang) * s * 0.8f).toFloat())
            }
            return
        }
        out.rect(wall, cx - s * 0.5f, ground - s * 0.6f, cx + s * 0.5f, ground)
        out.polygon(roof)
        out.point(cx - s * 0.62f, ground - s * 0.6f); out.point(cx, ground - s * 1.05f); out.point(cx + s * 0.62f, ground - s * 0.6f)
        val lit = (look.night > 0.4f) != switched
        if (lit) out.circle(Colors.alpha(p.light, 0.18f), cx - s * 0.19f, ground - s * 0.32f, s * 0.3f)
        out.rect(if (lit) p.light else Colors.mix(wall, p.skyBottom, 0.5f), cx - s * 0.3f, ground - s * 0.42f, cx - s * 0.08f, ground - s * 0.22f)
        out.rect(Colors.darker(roof, 0.35f), cx + s * 0.12f, ground - s * 0.36f, cx + s * 0.3f, ground)
    }

    private fun sea() {
        val top = midBase - midAmp * 0.25f
        out.rect(p.mid, 0f, top, w, bottom)
        val foam = Colors.alpha(Colors.lighter(p.mid, 0.5f), 0.5f)
        for (k in 0 until 6) {
            val y = top + (bottom - top) * (0.08f + k * 0.14f)
            val off = ((now / (50f + k * 15f)) + (lane.originMs + posMs) / (msPerPx * ZOOM_MID)).toFloat()
            var x = -((off * (1 + k * 0.3f)) % (h * 0.12f))
            while (x < w) {
                out.line(foam, unit * 0.004f, x, y, x + h * 0.04f, y)
                x += h * 0.12f
            }
        }
        // Sailing boats; dolphins leaping now and then.
        slots(ZOOM_MID, h * 0.2f) { i, x0, x1 ->
            val cx = (x0 + x1) / 2f
            if (hash(i, 91) in 0.25f..0.33f) {
                dolphin(cx, top + h * 0.03f, h * 0.035f, i)
                return@slots
            }
            if (!dense(i, 92, 0.2f, cx, ZOOM_MID)) return@slots
            val y = top + h * 0.035f + sin(now / 700.0 + i).toFloat() * h * 0.004f
            val s = h * 0.035f
            out.polygon(Colors.darker(p.mid, 0.5f))
            out.point(cx - s, y); out.point(cx + s, y); out.point(cx + s * 0.7f, y + s * 0.35f); out.point(cx - s * 0.7f, y + s * 0.35f)
            out.polygon(0xFFF8F8F2.toInt())
            out.point(cx, y - s * 1.4f); out.point(cx + s * 0.8f, y - s * 0.05f); out.point(cx, y - s * 0.05f)
        }
    }

    /** A dolphin leaping out of the water in an arc, then gone for a while. */
    private fun dolphin(cx: Float, waterY: Float, s: Float, seed: Int) {
        val cycle = 3200.0
        val u = (((now + seed * 977L) % cycle.toLong()) / cycle).toFloat()
        if (u > 0.45f) return
        val k = u / 0.45f
        val x = cx + (k - 0.5f) * s * 4f
        val y = waterY - sin(k * PI).toFloat() * s * 1.6f
        val angle = ((k - 0.5f) * 2.2f)
        out.turn(angle, x, y)
        val c = Colors.mix(0xFF5C7A99.toInt(), p.mid, 0.3f)
        out.polygon(c)
        out.point(x - s, y); out.point(x - s * 0.4f, y - s * 0.3f); out.point(x + s * 0.5f, y - s * 0.25f); out.point(x + s * 0.95f, y); out.point(x + s * 0.4f, y + s * 0.2f); out.point(x - s * 0.5f, y + s * 0.12f)
        out.polygon(c)
        out.point(x - s * 0.1f, y - s * 0.28f); out.point(x - s * 0.3f, y - s * 0.6f); out.point(x + s * 0.15f, y - s * 0.26f)
        out.polygon(c)
        out.point(x - s * 0.95f, y); out.point(x - s * 1.3f, y - s * 0.3f); out.point(x - s * 1.2f, y); out.point(x - s * 1.3f, y + s * 0.3f)
        out.straight()
    }

    private fun icebergs() {
        val water = farBase + h * 0.07f
        out.rect(Colors.mix(p.near, p.skyBottom, 0.25f), 0f, water, w, bottom)
        slots(ZOOM_MID, h * 0.16f) { i, x0, x1 ->
            val roll = hash(i, 101)
            if (roll in 0.55f..0.62f) whaleTail((x0 + x1) / 2f, water + h * 0.03f, h * 0.05f, i)
            if (roll > 0.55f) return@slots
            val cx = x0 + (x1 - x0) * hash(i, 102)
            val v = heightAt(cx, ZOOM_MID, 30f)
            val s = h * (0.03f + 0.09f * v) * (0.6f + 0.6f * hash(i, 103))
            val y = water + h * 0.02f + sin(now / 1300.0 + i).toFloat() * h * 0.003f
            berg(cx, y, s, i, p.mid)
            // Penguins on the bigger ones.
            if (s > h * 0.05f && hash(i, 108) < 0.35f) {
                val top = y - s * (0.6f + 0.4f * hash(i, 106))
                for (k in 0 until 1 + (hash(i, 109) * 3).toInt()) penguin(cx + s * 0.2f + k * s * 0.22f, top + s * 0.08f + k * s * 0.07f, s * 0.22f, i * 4 + k)
            }
        }
    }

    fun penguin(x: Float, groundY: Float, s: Float, seed: Int) {
        val pid = id(PENGUIN, seed)
        touchable(pid, x, groundY - s * 0.6f, s * 0.7f)
        val jump = bounce(poked(pid), 700)
        val wob = sin(now / 260.0 + seed).toFloat() * s * 0.06f
        val foot = groundY - hop(seed) * s * 0.5f - (jump?.get(0) ?: 0f) * s * 1.2f
        out.circle(0xFF1E2228.toInt(), x + wob, foot - s * 0.45f, s * 0.32f)
        out.circle(0xFF1E2228.toInt(), x + wob, foot - s * 0.85f, s * 0.22f)
        out.circle(0xFFF4F4F0.toInt(), x + wob + s * 0.08f, foot - s * 0.42f, s * 0.22f)
        out.circle(0xFFF2A33A.toInt(), x + wob + s * 0.24f, foot - s * 0.85f, s * 0.07f)
    }

    /** Now and then a whale's tail rises out of the water and sinks again. */
    private fun whaleTail(cx: Float, waterY: Float, s: Float, seed: Int) {
        val rise = sin(now / 2600.0 + seed * 1.3).toFloat()
        if (rise <= 0f) return
        val top = waterY - s * rise
        val color = Colors.darker(p.near, 0.25f)
        out.polygon(color)
        out.point(cx - s * 0.1f, waterY); out.point(cx - s * 0.06f, top + s * 0.25f)
        out.point(cx - s * 0.55f, top); out.point(cx - s * 0.2f, top + s * 0.12f)
        out.point(cx, top + s * 0.18f)
        out.point(cx + s * 0.2f, top + s * 0.12f); out.point(cx + s * 0.55f, top)
        out.point(cx + s * 0.06f, top + s * 0.25f); out.point(cx + s * 0.1f, waterY)
        out.line(Colors.alpha(p.accent, 0.5f), s * 0.04f, cx - s * 0.4f, waterY, cx + s * 0.4f, waterY)
    }

    private fun berg(cx: Float, waterY: Float, s: Float, seed: Int, color: Int) {
        out.polygon(color)
        out.point(cx - s * 1.3f, waterY)
        out.point(cx - s * (0.9f + 0.3f * hash(seed, 104)), waterY - s * 0.5f)
        out.point(cx - s * 0.3f, waterY - s * (0.8f + 0.5f * hash(seed, 105)))
        out.point(cx + s * 0.2f, waterY - s * (0.6f + 0.4f * hash(seed, 106)))
        out.point(cx + s * (0.8f + 0.3f * hash(seed, 107)), waterY - s * 0.35f)
        out.point(cx + s * 1.2f, waterY)
        out.polygon(Colors.alpha(Colors.darker(color, 0.2f), 0.6f))
        out.point(cx + s * 0.2f, waterY - s * (0.6f + 0.4f * hash(seed, 106)))
        out.point(cx + s * (0.8f + 0.3f * hash(seed, 107)), waterY - s * 0.35f)
        out.point(cx + s * 1.2f, waterY)
        out.point(cx + s * 0.3f, waterY)
    }

    private fun ridge() {
        out.polygon(p.mid)
        out.point(0f, bottom)
        var x = 0f
        var k = 0
        while (x <= w + step) {
            out.point(x, midY(x) - (if (k % 2 == 0) h * 0.01f else 0f))
            x += step * 1.6f
            k++
        }
        out.point(w, bottom)
    }

    // ---------- Near ----------

    /** The near ground again over [x0] … [x1], in front of what stands behind it there (a house of the back row). */
    internal fun groundStrip(x0: Float, x1: Float) {
        out.polygon(p.near)
        out.point(x0, bottom)
        var x = x0
        while (x < x1) {
            out.point(x, nearY(x))
            x += step
        }
        out.point(x1, nearY(x1))
        out.point(x1, bottom)
    }

    private fun ground(color: Int) {
        out.polygon(color)
        out.point(0f, bottom)
        var x = 0f
        while (x <= w + step) {
            out.point(x, nearY(x))
            x += step
        }
        out.point(w, bottom)
    }

    private fun road(neon: Boolean) {
        ground(p.near)
        val stripe = if (neon) p.accent else Colors.alpha(0xFFFFFFFF.toInt(), 0.7f)
        slots(1f, h * 0.06f) { i, x0, x1 ->
            val y0 = nearY(x0) + h * 0.03f
            val y1 = nearY(x1 - h * 0.025f) + h * 0.03f
            out.line(stripe, h * 0.006f, x0, y0, x1 - h * 0.025f, y1)
            if (i % 4 == 0) {
                // A street lamp.
                val y = nearY(x0)
                out.line(Colors.darker(p.near, 0.4f), h * 0.005f, x0, y, x0, y - h * 0.09f)
                val lamp = if (neon || look.night > 0.4f) p.light else Colors.lighter(p.near, 0.4f)
                if (neon || look.night > 0.4f) out.circle(Colors.alpha(p.light, 0.2f + 0.2f * live.level + 0.3f * kick), x0, y - h * 0.09f, h * (0.03f + 0.012f * kick))
                out.circle(lamp, x0, y - h * 0.09f, h * 0.008f)
            }
        }
    }

    private fun darkGround() {
        ground(p.near)
        val spike = Colors.lighter(p.near, 0.1f)
        slots(1f, h * 0.025f) { i, x0, _ ->
            val y = nearY(x0)
            val s = h * 0.02f * (0.5f + hash(i, 111))
            out.line(spike, h * 0.003f, x0, y + 1f, x0 + s * 0.2f, y - s)
            if (i % 9 == 0 && dense(i, 112, 0.8f, x0, 1f)) {
                if (hash(i, 113) < 0.5f) {
                    out.line(spike, h * 0.004f, x0, y, x0, y - h * 0.035f)
                    out.line(spike, h * 0.003f, x0 - h * 0.015f, y - h * 0.025f, x0 + h * 0.06f, y - h * 0.025f)
                } else {
                    // A gravestone.
                    out.rect(spike, x0 - h * 0.012f, y - h * 0.03f, x0 + h * 0.012f, y + h * 0.004f)
                    out.circle(spike, x0, y - h * 0.03f, h * 0.012f)
                }
            } else if (i % 13 == 5 && hash(i, 114) < 0.5f) {
                // A torch, flaring with the sound.
                out.line(spike, h * 0.004f, x0, y, x0, y - h * 0.05f)
                val f = h * (0.008f + 0.012f * live.level) * (0.85f + 0.15f * sin(now / 60.0 + i).toFloat())
                out.circle(Colors.alpha(p.accent, 0.3f), x0, y - h * 0.055f, f * 2f)
                out.circle(p.accent, x0, y - h * 0.055f, f)
            }
        }
    }

    private fun grass() {
        ground(p.near)
        val blade = Colors.lighter(p.near, 0.15f)
        val sway = sin(beat * 2 * PI).toFloat() * h * 0.004f * (0.5f + live.level)
        slots(1f, h * 0.02f) { i, x0, _ ->
            val y = nearY(x0)
            val s = h * 0.018f * (0.5f + hash(i, 121))
            out.line(blade, h * 0.003f, x0, y + 1f, x0 + sway, y - s)
            if (dense(i, 122, 0.14f, x0, 1f)) {
                val c = if (hash(i, 123) < 0.5f) p.accent else Colors.lighter(p.accent, 0.5f)
                out.circle(c, x0 + sway, y - s, h * 0.006f * (1f + live.high * 0.6f))
            }
        }
    }

    private fun sand() {
        ground(p.near)
        slots(1f, h * 0.14f) { i, x0, _ ->
            if (!dense(i, 131, 0.25f, x0, 1f)) return@slots
            val y = nearY(x0)
            val s = h * 0.11f * (0.8f + 0.4f * hash(i, 132))
            val trunk = Colors.darker(p.near, 0.5f)
            out.polyline(trunk, h * 0.008f)
            out.point(x0, y); out.point(x0 + s * 0.1f, y - s * 0.5f); out.point(x0 + s * 0.25f, y - s)
            val leaf = Colors.darker(p.far, 0.15f)
            val sway = sin(now / 800.0 + i).toFloat() * s * 0.05f * (0.5f + live.level)
            for (k in 0 until 5) {
                val a = -PI * (0.1 + k * 0.2)
                out.line(leaf, h * 0.006f, x0 + s * 0.25f, y - s, x0 + s * 0.25f + (s * 0.45f * cos(a)).toFloat() + sway, y - s + (s * 0.3f * -sin(a)).toFloat() * -1f + s * 0.12f)
            }
        }
    }

    private fun coldSea() {
        out.polygon(p.near)
        out.point(0f, bottom)
        var x = 0f
        while (x <= w + step) {
            out.point(x, seaY(x))
            x += step
        }
        out.point(w, bottom)
        val foam = Colors.alpha(p.accent, 0.45f)
        slots(1f, h * 0.05f) { _, x0, _ ->
            out.line(foam, h * 0.003f, x0, seaY(x0) + h * 0.004f, x0 + h * 0.025f, seaY(x0 + h * 0.025f) + h * 0.004f)
        }
    }

    /** The near sea: the song's swell plus small waves moving with the sound. */
    fun seaY(x: Float) = nearBase - heightAt(x, 1f, 60f) * nearAmp * 0.6f - sin(x / (h * 0.05f) + now / 500.0).toFloat() * h * (0.004f + 0.006f * live.low)

    private fun moonGround() {
        ground(p.near)
        val crater = Colors.darker(p.near, 0.25f)
        slots(1f, h * 0.09f) { i, x0, x1 ->
            val cx = (x0 + x1) / 2f
            if (!dense(i, 141, 0.35f, cx, 1f)) return@slots
            val y = nearY(cx) + h * 0.03f + hash(i, 142) * h * 0.03f
            val r = h * 0.012f * (1f + 1.5f * hash(i, 143))
            out.circle(crater, cx, y, r)
            out.circle(Colors.lighter(p.near, 0.1f), cx + r * 0.25f, y + r * 0.2f, r * 0.7f)
        }
    }

    // ---------- Weather in front ----------

    fun weather() {
        val rain = look.rain
        if (rain > 0.02f) {
            val len = h * 0.03f
            val slant = h * 0.008f * (1f + look.storm * 2f)
            // The drops drift left as they fall: they start further right so the whole width gets rain.
            val drift = h * slant / len * 0.3f + slant
            val n = (140 * rain * (w + drift) / max(h, 1f) * 0.9f).toInt().coerceIn(1, 420)
            val color = Colors.alpha(0xFFCFD8E8.toInt(), 0.35f + 0.25f * rain)
            for (i in 0 until n) {
                val speed = h * (0.0012f + 0.0006f * hash(i, 161))
                val y = ((hash(i, 162) * bottom + now * speed) % (bottom + len)) - len
                val x = hash(i, 163) * (w + drift * bottom / h) - y * slant / len * 0.3f
                out.line(color, unit * 0.0025f, x, y, x - slant, y + len)
            }
        }
        if (look.snow > 0.02f) {
            val n = (90 * look.snow * max(1f, w / h)).toInt()
            val color = Colors.alpha(0xFFFFFFFF.toInt(), 0.75f)
            for (i in 0 until n) {
                val speed = h * (0.00004f + 0.00004f * hash(i, 171))
                val y = (hash(i, 172) * bottom + now * speed) % bottom
                val x = (hash(i, 173) * w + sin(now / 1500.0 + i).toFloat() * h * 0.02f + w) % w
                out.circle(color, x, y, unit * (0.003f + 0.003f * hash(i, 174)))
            }
        }
        if (look.storm > 0.3f) {
            // Lightning on strong low beats, now and then without them too.
            if (now - moves.boltAtMs > 9000 / look.storm) bolt()
            val age = now - moves.boltAtMs
            if (age in 0..300) {
                val k = 1f - age / 300f
                out.rect(Colors.alpha(0xFFFFFFFF.toInt(), 0.35f * k * look.storm), 0f, 0f, w, bottom)
                val seed = moves.boltAtMs.toInt()
                // Down to the ground, with a branch.
                var bx = moves.boltX
                var by = h * 0.08f
                out.polyline(Colors.alpha(0xFFFFFFF0.toInt(), k), unit * 0.007f)
                out.point(bx, by)
                var j = 0
                var branchX = bx
                var branchY = by
                while (by < nearBase - nearAmp) {
                    by += h * 0.06f
                    bx += (hash(seed + j, 152) - 0.5f) * h * 0.08f
                    out.point(bx, by)
                    if (j == 3) {
                        branchX = bx
                        branchY = by
                    }
                    j++
                }
                out.polyline(Colors.alpha(0xFFFFFFF0.toInt(), k * 0.8f), unit * 0.004f)
                out.point(branchX, branchY)
                for (m in 1..3) out.point(branchX + m * h * 0.035f * (if (hash(seed, 153) < 0.5f) 1f else -1f), branchY + m * h * 0.045f + (hash(seed + m, 154) - 0.5f) * h * 0.03f)
            }
        }
    }

    /** Places tied to the music on a layer ([zoom]), each [widthPx] wide at the current speed, seen on screen. */
    inline fun slots(zoom: Float, widthPx: Float, each: (Int, Float, Float) -> Unit) = slotsMs(zoom, widthPx * msPerPx * zoom, each)

    /** Places tied to the music on a layer ([zoom]), each [slotMs] of music long, seen on screen. */
    inline fun slotsMs(zoom: Float, slotMs: Double, each: (Int, Float, Float) -> Unit) {
        if (slotMs <= 0.0) return
        val origin = lane.originMs
        val widthPx = (slotMs / (msPerPx * zoom)).toFloat()
        val first = floor((origin + msAt(-widthPx, zoom)) / slotMs).toLong()
        val last = floor((origin + msAt(w + widthPx, zoom)) / slotMs).toLong()
        var i = first
        while (i <= last) {
            val x0 = xAt(i * slotMs - origin, zoom)
            val x1 = xAt((i + 1) * slotMs - origin, zoom)
            each(i.toInt(), x0, x1)
            i++
        }
    }

    /** Something that can be touched this frame: [id] (stable while it is in sight) at [x], [y], [r] around (at least a fingertip). */
    fun touchable(id: Int, x: Float, y: Float, r: Float) {
        if (out.fade < 0.5f) return
        moves.hit(id, x, y, max(r, unit * 0.06f))
    }

    /** Since [id] was touched (ms), or -1. */
    fun poked(id: Int) = moves.pokeAge(id, now)

    /** A jump with a turn over, [age] ms after a touch: how high (0 … 1) and how far round (radians); none after [ms]. */
    fun bounce(age: Long, ms: Long = 900): FloatArray? {
        if (age < 0 || age >= ms) return null
        val u = age / ms.toFloat()
        return floatArrayOf(sin(PI * u).toFloat(), (2 * PI * u * u * (3 - 2 * u)).toFloat())
    }

    companion object {
        const val ZOOM_MID = 2.5f

        /** Music over which a road is smoothed (ms). */
        const val ROAD_SMOOTH_MS = 4_000.0
        const val FLAT_FIELDS = 6
        const val ZOOM_FAR = 6f

        /** Ids of what can be touched (the rest are made from a kind and a place). */
        const val TRAVELLER = -1
        const val SUN = -2
        const val GROUND_VISITOR = 1 shl 24
        const val GRAZER = 2 shl 24
        const val MATE = 3 shl 24
        const val BUILDING = 4 shl 24
        const val VILLAGE_HOUSE = 5 shl 24
        const val HOUSE = 6 shl 24
        const val CASTLE = 7 shl 24
        const val PENGUIN = 8 shl 24
        const val RECORD = 9 shl 24

        /** The id of thing [i] of a [kind]. */
        fun id(kind: Int, i: Int) = kind or (i and 0xFFFFFF)
    }
}
