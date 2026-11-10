package com.sadurni.attacca.diorama

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** A track's loudness along its playing part, from its waveform (the loudest bin 1). */
class SongWave(bins: FloatArray, val fileMs: Long, val startMs: Long, val endMs: Long) {

    private val n = bins.size
    private val prefix = DoubleArray(n + 1).also { p -> for (i in 0 until n) p[i + 1] = p[i] + bins[i] }
    private val values = bins

    val lengthMs: Long get() = max(1L, endMs - startMs)

    // The playing part's own quiet and loud (10th and 95th percentiles of its bins).
    private val low: Float
    private val high: Float

    init {
        val a = if (fileMs > 0) (startMs.toDouble() / fileMs * n).toInt().coerceIn(0, max(0, n - 1)) else 0
        val b = if (fileMs > 0) (endMs.toDouble() / fileMs * n).toInt().coerceIn(a + 1, max(a + 1, n)) else n
        val part = if (n == 0) FloatArray(0) else values.copyOfRange(a, min(b, n)).also { it.sort() }
        low = part.getOrElse((part.size * 0.10).toInt()) { 0f }
        high = part.getOrElse((part.size * 0.95).toInt().coerceAtMost(part.size - 1)) { 1f }
    }

    /**
     * Like [mean], spread over the track's own range: its quiet parts near 0 and its loud ones
     * near 1, so even a loud, even track has hills and valleys.
     */
    fun shape(ms: Double, windowMs: Double): Float {
        val span = high - low
        if (span < 0.05f) return mean(ms, windowMs)
        return ((mean(ms, windowMs) - low) / span).coerceIn(0f, 1f)
    }

    /** Mean loudness (0 … 1) over [windowMs] around the file's moment [ms]. */
    fun mean(ms: Double, windowMs: Double): Float {
        if (n == 0 || fileMs <= 0) return 0f
        val perBin = fileMs.toDouble() / n
        val centre = ms / perBin
        val half = windowMs / perBin / 2.0
        if (half < 1.0) {
            // Narrower than a bin: in between the two nearest.
            val x = (centre - 0.5).coerceIn(0.0, (n - 1).toDouble())
            val i = x.toInt()
            val j = min(i + 1, n - 1)
            val f = (x - i).toFloat()
            return values[i] + (values[j] - values[i]) * f
        }
        val a = (centre - half).toInt().coerceIn(0, n - 1)
        val b = (centre + half).toInt().coerceIn(a + 1, n)
        return ((prefix[b] - prefix[a]) / (b - a)).toFloat()
    }
}

/**
 * One track's land as it is drawn: its own look and pace, its wave with those around it, where
 * the traveller is in it ([posMs]) and where it starts on the land's own timeline ([originMs]).
 */
internal class Lane(
    val look: SongLook,
    val wave: SongWave?,
    val before: SongWave?,
    val after: SongWave?,
    val posMs: Double,
    val originMs: Double,
    /** Where the track is loudest for a while (its peak moment), or -1. */
    val peakMs: Double = -1.0,
    /** The next track's title, for the sign before the end. */
    val nextTitle: String? = null,
    /** Where the next track is in the list ("7/23"), for the same sign. */
    val nextPlace: String? = null,
    /** The world the next track begins in, for the gate at the end (null: a plain finish). */
    val nextWorld: World? = null,
    /** The record's title and artist, for its sleeve. */
    val title: String? = null,
    val artist: String? = null,
) {

    /**
     * The land's height (0 … 1) at the moment [ms] of the track's playing part, averaged over
     * [windowMs]. Where it meets the track before or after, one runs into the other over that
     * window: no step, which would flicker as the points drawn fall on one side or the other.
     */
    fun height(ms: Double, windowMs: Double): Float {
        val cur = wave
            ?: return (0.35f + 0.12f * sin(ms / 4100.0).toFloat() + 0.06f * sin(ms / 1300.0).toFloat()).coerceIn(0f, 1f)
        val len = cur.lengthMs.toDouble()
        val half = max(windowMs / 2.0, 1.0)
        // Before the start: the track before, else the start seen in a mirror; after the end, the next one.
        fun own(m: Double) = cur.shape(cur.startMs + m.coerceIn(0.0, len), windowMs)
        fun before(m: Double) = before?.let { it.shape(it.endMs + min(m, 0.0), windowMs) } ?: cur.shape(cur.startMs - min(m, 0.0), windowMs)
        fun after(m: Double) = after?.let { it.shape(it.startMs + max(m - len, 0.0), windowMs) } ?: 0.05f
        fun blend(a: Float, b: Float, t: Double) = a + (b - a) * t.coerceIn(0.0, 1.0).toFloat()
        val v = when {
            ms < -half -> before(ms)
            ms < half -> blend(before(ms), own(ms), (ms + half) / (2 * half))
            ms <= len - half -> own(ms)
            ms < len + half -> blend(own(ms), after(ms), (ms - (len - half)) / (2 * half))
            else -> after(ms)
        }
        return v.coerceIn(0f, 1f)
    }
}

/** What the traveller is doing and the lightning: kept from frame to frame. */
internal class Moves {
    var handledOnset = Long.MIN_VALUE
    var onsets = 0
    var trick = Trick.NONE
    var trickAtMs = LiveLevels.NEVER
    var trickEndMs = LiveLevels.NEVER
    var boltAtMs = LiveLevels.NEVER
    var boltX = 0f

    /** The last frame's clock, and a clock that runs quicker with louder music (wheels, sails). */
    var lastNow = LiveLevels.NEVER
    var spinMs = 0.0
    private var spinLevel = 0f

    /** The traveller's colour as shown (it eases into a new one as the land behind changes). */
    var figureColor = 0

    /** How the head being drawn looks: 0 bare, 1 a ponytail, 2 a cap, 3 long hair, 4 headphones. */
    var headStyle = 0

    // What can be touched in the last frame: an id, a centre and a reach each.
    private var hits = 0
    private var hitIds = IntArray(64)
    private var hitXy = FloatArray(192)

    /** When each thing was last touched, and the things switched (lights) by touching them. */
    private val pokes = HashMap<Int, Long>()
    val switched = HashSet<Int>()

    /**
     * The four dogs, each once ([Dog] order: goldie, malamute, galgo, braco): what each is doing
     * (Scene.DOG_*), and a moment that goes with it (the ground it waits on, when it set off).
     */
    val dogState = IntArray(4)
    val dogMark = DoubleArray(4)
    var dogLastMs = Double.NaN

    /** The traveller was touched: a move as soon as it can. */
    var wantTrick = false

    /** Touches on the sun (or moon), and the track whose sun turned into a record. */
    private val sunTaps = LongArray(5) { LiveLevels.NEVER }
    var recordSunOf = Double.NaN

    fun clearHits() {
        hits = 0
    }

    fun hit(id: Int, x: Float, y: Float, r: Float) {
        if (hits == hitIds.size) {
            hitIds = hitIds.copyOf(hits * 2)
            hitXy = hitXy.copyOf(hits * 6)
        }
        hitIds[hits] = id
        hitXy[hits * 3] = x
        hitXy[hits * 3 + 1] = y
        hitXy[hits * 3 + 2] = r
        hits++
    }

    /** The touch at [x], [y] (scene coordinates): the nearest thing within reach, or null. */
    fun touched(x: Float, y: Float): Int? {
        var best: Int? = null
        var bestD = Float.MAX_VALUE
        for (k in 0 until hits) {
            val dx = x - hitXy[k * 3]
            val dy = y - hitXy[k * 3 + 1]
            val d = kotlin.math.sqrt(dx * dx + dy * dy)
            // The nearest by its own size: a small thing close by wins over a big one around it.
            val rel = d / hitXy[k * 3 + 2]
            if (rel <= 1f && rel < bestD) {
                bestD = rel
                best = hitIds[k]
            }
        }
        return best
    }

    fun poke(id: Int, now: Long) {
        pokes[id] = now
        if (!switched.add(id)) switched.remove(id)
        if (pokes.size > 64) pokes.entries.removeAll { now - it.value > 10_000 }
    }

    /** Milliseconds since [id] was touched, or -1. */
    fun pokeAge(id: Int, now: Long): Long = pokes[id]?.let { now - it }?.takeIf { it >= 0 } ?: -1

    /** A touch on the sun: true on the fifth within four seconds. */
    fun sunTap(now: Long): Boolean {
        for (k in 0 until sunTaps.size - 1) sunTaps[k] = sunTaps[k + 1]
        sunTaps[sunTaps.size - 1] = now
        return now - sunTaps[0] < 4000
    }

    /** How long since the last frame (0 … 250 ms), moving the music's own clock on. */
    fun tick(now: Long, level: Float, playing: Boolean): Long {
        val dt = if (lastNow == LiveLevels.NEVER) 0L else (now - lastNow).coerceIn(0, 250)
        lastNow = now
        // Smoothed over a few seconds: a loud bar quickens the wheel gently, never in a jerk.
        spinLevel += (level - spinLevel) * (1f - kotlin.math.exp(-dt / 2500f))
        if (playing) spinMs += dt * (0.6 + 0.9 * spinLevel)
        return dt
    }
}

/** The traveller's moves, each [ms] long. */
internal enum class Trick(val ms: Long) {
    NONE(0), HOP(360), JUMP(520), DOUBLE(760), FLIP(760), CARTWHEEL(950), ARMS(1500), WHEELIE(1100), REAR(950), ROLL(950), LOOP(1400),
}

/**
 * Attacca O'rama: a moving picture of the song playing. The land is the song's waveform (loud
 * parts are hills and tall buildings), its look comes from the song ([SongLook]) and the traveller
 * crosses it at the pace of the music, moved by the live levels. Each track is a level: when the
 * next one starts its land is already ahead, and the old land fades into the new one.
 *
 * Call [show] when the track or what follows it changes and [draw] for every frame.
 */
class Diorama {

    class Song(val id: String, val look: SongLook, val wave: SongWave?, val title: String? = null, val artist: String? = null) {
        /** Its stretches of another world, if it has any (worked out once). */
        internal val sections: Sections? by lazy { wave?.let { Sections.of(it) } }

        /** Its loudest stretch's start (8 s windows), or -1 for a short or unknown track. */
        val peakMs: Double by lazy {
            val w = wave ?: return@lazy -1.0
            if (w.lengthMs < 60_000) return@lazy -1.0
            var best = -1.0
            var bestValue = -1f
            var at = 15_000.0
            while (at < w.lengthMs - 15_000) {
                val v = w.mean(w.startMs + at + 4000.0, 8000.0)
                if (v > bestValue) {
                    bestValue = v
                    best = at
                }
                at += 1000.0
            }
            best
        }
    }

    /** A land fading out under the current one: the track before, or this one's stretch before. */
    private class Fading(
        val look: SongLook,
        val wave: SongWave?,
        val before: SongWave?,
        val after: SongWave?,
        val originMs: Double,
        /** Its traveller's place when it began to fade, and the shown position then. */
        val atMs: Double,
        val shownAtMs: Double,
    )

    private var current: Song? = null
    private var previous: Song? = null
    private var nextWave: SongWave? = null
    private var changedAtMs = Long.MIN_VALUE
    private var fading: Fading? = null

    /** The current track's look (another one when it looks just like the one before) and its stretch's. */
    private var trackLook: SongLook = NEUTRAL
    private var mode = 0
    private var shownLook: SongLook = NEUTRAL

    /** Where the current track starts on the land's own timeline (stays put across tracks). */
    private var originMs = 0.0

    /** Live levels: give them the meter's values in [draw]. */
    val live = LiveLevels()

    // Smoothed playing position (the player reports it only now and then).
    private var shownMs = 0.0
    private var reportedMs = Long.MIN_VALUE
    private var lastDrawMs = Long.MIN_VALUE

    /** A clock that runs while playing, for what drifts on its own (clouds, far planets). */
    private var driftMs = 0.0

    private val moves = Moves()

    private var nextTitle: String? = null
    private var nextPlace: String? = null
    private var nextLook: SongLook? = null
    private var nextWorldOf: SongLook? = null
    private var nextWorld: World? = null

    /** The camera: closer in quiet parts, wide in loud ones. */
    private var zoom = 1f

    /**
     * Sets the playing track and the wave (and title, place in the list as "7/23", and look) of
     * the one after it.
     */
    fun show(song: Song?, next: SongWave?, nowMs: Long, nextTitle: String? = null, nextPlace: String? = null, nextLook: SongLook? = null) {
        nextWave = next
        this.nextTitle = nextTitle
        this.nextPlace = nextPlace
        this.nextLook = nextLook
        val old = current
        if (song?.id == old?.id) {
            // The same track, its look known better now (analysed, its cover read).
            if (song != null && song.look !== old?.look) {
                trackLook = if (alike(song.look, previous?.let { trackLookOfPrevious })) song.look.reseeded() else song.look
                shownLook = trackLook.variant(mode)
            }
            current = song
            return
        }
        if (old != null) fading = Fading(shownLook, old.wave, previous?.wave, song?.wave, originMs, shownMs, 0.0)
        previous = old
        trackLookOfPrevious = trackLook
        current = song
        changedAtMs = nowMs
        originMs += old?.wave?.lengthMs?.toDouble() ?: 0.0
        shownMs = 0.0
        reportedMs = Long.MIN_VALUE
        // Two tracks alike in a row: the second one in another look.
        val look = song?.look ?: NEUTRAL
        trackLook = if (old != null && alike(look, trackLookOfPrevious)) look.reseeded() else look
        // A track starting quiet (or loud) starts in that stretch's world.
        mode = song?.sections?.modeAt(0.0) ?: 0
        shownLook = trackLook.variant(mode)
    }

    private var trackLookOfPrevious: SongLook? = null

    private fun alike(a: SongLook, b: SongLook?) = b != null && a.world == b.world && a.traveller == b.traveller

    /**
     * One frame [w] × [h] into [out]: [positionMs] from the start of the track's playing part, the
     * raw live levels [raw] (level, low, high) or null, [nowMs] a steady clock. [lift] raises the
     * land that much over the bottom (to keep it clear of what floats there); the sky goes on below.
     */
    private var festivity: com.sadurni.attacca.festive.Festivity? = null
    private var kingsDays = false
    private var celebration: Celebration? = null

    /** The festive look of these days ([f], null: none; [kings]: the Three Kings come), as the app decides it. */
    fun festive(f: com.sadurni.attacca.festive.Festivity?, kings: Boolean = false) {
        festivity = f
        kingsDays = kings
    }

    /** The new year's show going on (null: none). */
    fun celebrate(c: Celebration?) {
        celebration = c
    }

    fun draw(out: DrawList, w: Float, h: Float, positionMs: Long, playing: Boolean, raw: FloatArray?, nowMs: Long, lift: Float = 0f, stripTop: Float = 0f, stripHeight: Float = 0f) {
        out.clear()
        if (w <= 0f || h <= 0f) return
        val dt = if (lastDrawMs == Long.MIN_VALUE) 0L else (nowMs - lastDrawMs).coerceIn(0, 250)
        lastDrawMs = nowMs
        live.update(if (playing) raw else null, nowMs)
        if (playing) {
            shownMs += dt
            driftMs += dt
        }
        if (positionMs != reportedMs) {
            // Just after a change the player may still give the old track's place: not taken.
            val stale = reportedMs == Long.MIN_VALUE && previous != null && nowMs - changedAtMs < 1500 && positionMs > 2500
            if (!stale) {
                val error = positionMs - shownMs
                shownMs = if (reportedMs == Long.MIN_VALUE || abs(error) > 700) positionMs.toDouble() else shownMs + error * 0.3
                reportedMs = positionMs
            }
        }

        // A stretch far quieter or louder than the rest: its land fades into another world.
        val song = current
        val stretch = song?.sections?.modeAt(shownMs) ?: 0
        if (song != null && stretch != mode && nowMs - changedAtMs > TRANSITION_MS) {
            fading = Fading(shownLook, song.wave, previous?.wave, nextWave, originMs, shownMs, shownMs)
            changedAtMs = nowMs
            mode = stretch
            shownLook = trackLook.variant(mode)
        }

        val cur = shownLook
        val old = fading
        val t = if (old == null || changedAtMs == Long.MIN_VALUE) 1f else ((nowMs - changedAtMs) / TRANSITION_MS.toFloat()).coerceIn(0f, 1f)
        if (t >= 1f) fading = null
        val look = if (old != null && t < 1f) Looks.mix(old.look, cur, smooth(t)) else cur

        moves.clearHits()
        val scene = Scene(out, w, (h - lift.coerceIn(0f, h * 0.4f)), look, nowMs, playing, live, moves, driftMs, h, stripTop, stripHeight)
        scene.festive = festivity
        scene.kings = kingsDays
        scene.show = celebration
        val lane = Lane(cur, song?.wave, previous?.wave, nextWave, shownMs, originMs, song?.peakMs ?: -1.0, nextTitle, nextPlace, nextWorld(), song?.title, song?.artist)
        scene.react(lane)
        scene.sky()
        if (old != null && t < 1f) {
            // Each land keeps its own pace: the old one goes on and fades under the new one.
            scene.land(Lane(old.look, old.wave, old.before, old.after, old.atMs + (shownMs - old.shownAtMs), old.originMs), 1f)
            scene.land(lane, smooth(t))
        } else {
            scene.land(lane, 1f)
        }
        scene.weather()
        scene.festiveFront()
        scene.celebration()

        // The camera comes closer in the track's quiet parts, opens up in loud ones and at its peak,
        // and shakes a little on strong low beats.
        val here = song?.wave?.let { it.shape(it.startMs + shownMs, 8000.0) } ?: 0.5f
        // (In a strip it stays where it is: coming closer would push what is shown out of it.)
        val target = if (stripTop > 0f) 1f else 1f + 0.16f * (1f - here) * (1f - scene.peak)
        zoom += (target - zoom) * (1f - exp(-dt / 2500f))
        val shake = exp(-(nowMs - live.onsetAtMs).coerceAtLeast(0) / 110f) * live.low * (if (cur.energy > 0.35f) 1f else 0.3f)
        val unit = min(w, h)
        out.camera(
            zoom, scene.travellerX, scene.cameraY(),
            (hash(moves.onsets, 401) - 0.5f) * unit * 0.016f * shake, (hash(moves.onsets, 402) - 0.5f) * unit * 0.016f * shake,
        )
    }

    private fun smooth(t: Float) = t * t * (3f - 2f * t)

    /**
     * A tap at [x], [y] on the picture as last drawn (before the camera): what is there reacts
     * (an animal jumps, a record slides out, lights go on or off, the traveller does a move).
     * True when something was there.
     */
    fun tap(out: DrawList, x: Float, y: Float, nowMs: Long): Boolean {
        val z = out.zoom.takeIf { it > 0f } ?: 1f
        val sx = (x - out.shiftX - out.pivotX) / z + out.pivotX
        val sy = (y - out.shiftY - out.pivotY) / z + out.pivotY
        val id = moves.touched(sx, sy) ?: return false
        when (id) {
            Scene.TRAVELLER -> moves.wantTrick = true
            Scene.SUN -> if (moves.sunTap(nowMs)) moves.recordSunOf = if (moves.recordSunOf == originMs) Double.NaN else originMs
            else -> moves.poke(id, nowMs)
        }
        return true
    }

    /** The world the next track will begin in, as [show] will choose it (worked out once per look). */
    private fun nextWorld(): World? {
        val n = nextLook ?: return null
        if (n !== nextWorldOf) {
            nextWorldOf = n
            val look = if (alike(n, trackLook)) n.reseeded() else n
            nextWorld = look.variant(nextWave?.let { Sections.of(it) }?.modeAt(0.0) ?: 0).world
        }
        return nextWorld
    }

    companion object {
        const val TRANSITION_MS = 4000L

        /** The look of a track with nothing known about it. */
        val NEUTRAL: SongLook = Looks.of(SongFacts())

        /** A steady pseudo-random value in 0 … 1 for [i] and [salt]. */
        fun hash(i: Int, salt: Int): Float {
            var x = i * 374761393 + salt * 668265263
            x = (x xor (x ushr 13)) * 1274126177
            x = x xor (x ushr 16)
            return (x and 0xFFFFFF) / 16777216f
        }
    }
}
