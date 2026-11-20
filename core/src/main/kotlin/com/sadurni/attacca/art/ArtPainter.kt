package com.sadurni.attacca.art

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * One touch of paint on the canvas: where, how big, at which angle, its pigment and how strong,
 * and when it was laid (it spreads for [growMs] while wet, then dries into the canvas).
 */
class ArtMark(
    val kind: Int,
    val x: Float,
    val y: Float,
    val rx: Float,
    val ry: Float,
    val angle: Float,
    val r: Float,
    val g: Float,
    val b: Float,
    val alpha: Float,
    /** 0 … 1: what makes each mark's own irregular shape. */
    val seed: Float,
    val bornMs: Long,
    val growMs: Int,
) {
    /** How far it has spread at [nowMs] (0 … 1). */
    fun growth(nowMs: Long): Float = if (growMs <= 0) 1f else ((nowMs - bornMs).toFloat() / growMs).coerceIn(0f, 1f)

    /** The same mark, already dry. */
    fun dry(): ArtMark = if (growMs <= 0) this else ArtMark(kind, x, y, rx, ry, angle, r, g, b, alpha, seed, bornMs, 0)

    companion object {
        /** A watercolour blot that spreads and darkens at its edge as it dries. */
        const val BLOOM = 0

        /** A short brush stroke, its bristles showing. */
        const val DAB = 1

        /** A thin dry-brush line (the hatching of the high sounds). */
        const val LINE = 2

        /** A broad pale wash. */
        const val GLAZE = 3

        /** A small splash drop. */
        const val DROP = 4

        /** The painter's seal at the end. */
        const val SEAL = 5

        /** A colour over everything (the painting before, faded, when painting over it). */
        const val VEIL = 6

        /** The pen's flowing line while the song is sung. */
        const val INK = 7

        /** Oil: a thick stroke, its bristle ridges catching the light. */
        const val OIL = 8

        /** Oil: a thick touch of paint. */
        const val DOT = 9

        /** A ballpoint pen's line, trembling while it is drawn. */
        const val PEN = 10

        /** Coloured pencil hatching over an area. */
        const val PENCIL = 11
    }
}

/** How Art-tacca paints. */
enum class ArtStyle {
    /** Each song chooses: lively and loud in oil, quiet in watercolour, sung in ballpoint, the rest in ink. */
    AUTO,
    WATERCOLOUR,
    OIL,
    INK,
    PEN;

    companion object {
        /** The style a song paints itself in, for [AUTO]. */
        fun forSong(s: ArtSong): ArtStyle = when {
            s.energy > 0.62f || (s.roughness > 0.6f && s.energy > 0.45f) -> OIL
            s.energy < 0.35f && s.roughness < 0.35f -> WATERCOLOUR
            s.voice > 0.65f -> PEN
            s.energy < 0.5f -> INK
            else -> if (java.util.Random(s.seed).nextBoolean()) WATERCOLOUR else OIL
        }

        fun of(name: String?): ArtStyle = entries.firstOrNull { it.name == name } ?: WATERCOLOUR
    }
}

/**
 * Art-tacca's painter: listens to the song (its shape known beforehand, its levels, beats and notes
 * as they come) and lays marks on a canvas of [width] × [height], in its [style]. The low beats
 * burst out (blots and splashes, swirls of thick paint, scribbles), the beat lays short strokes, the
 * high sounds hatch or dot, a sung song flows into a line, quiet stretches get washes and the peak
 * the painting's big gesture; a seal ends it. The colours turn with the chords heard. Everything
 * comes from the song and its seed: the same song paints alike, never the same.
 *
 * Call [start] for each new painting and [frame] for every frame; marks that dried go to
 * [takeDried] (for the canvas) and [history] (to paint it all again); [wet] are still spreading.
 */
class ArtPainter {
    var width = 1f
        private set
    var height = 1f
        private set
    var song: ArtSong? = null
        private set

    /** The style asked for (maybe [ArtStyle.AUTO]) and the one painted in. */
    var chosen = ArtStyle.WATERCOLOUR
        private set
    var style = ArtStyle.WATERCOLOUR
        private set

    /** Ballpoint: squared paper instead of lined. */
    var squared = false
        private set

    /** What the canvas starts as: white paper, or (oil) a toned ground. */
    var ground: FloatArray = floatArrayOf(1f, 1f, 1f)
        private set

    /** Every dried mark of this painting, in order. */
    val history = ArrayList<ArtMark>()

    /** The marks still spreading. */
    val wet = ArrayList<ArtMark>()

    private val dried = ArrayList<ArtMark>()

    /** Song time painted up to (ms), or -1 before the first frame. */
    var paintedMs = -1.0
        private set

    private var rnd = java.util.Random(0)
    private var px = 0f
    private var py = 0f
    private var heading = 0f
    private var qx = 0f
    private var qy = 0f
    private var qHeading = 0f
    private var penDown = false
    private var lastOnset = Long.MIN_VALUE
    private var nextBeatMs = 0.0
    private var hatchDebt = 0f
    private var hatchTurn = false
    private var radialDebt = 0f
    private var glazeAtMs = -1e9
    private var washed = false
    private var peakAt = -1.0
    private var sealed = false
    private var signed = false

    /** The signature's touches still to write, each with when (steady clock). */
    private val signing = ArrayList<Pair<Long, ArtMark>>()
    private var focalX = 0f
    private var focalY = 0f
    private var horizon = 0f
    private var hatchAngle = 0f
    private var flowSeed = 0
    private var glazeSky = true
    private var roamA = 0f
    private var roamB = 0f
    private var penColour = floatArrayOf(0.12f, 0.18f, 0.5f)
    private val cover = FloatArray(GRID * GRID)

    // The chords: the notes heard, smoothed, and how far round the hues have turned for them.
    private val heardNotes = FloatArray(12)
    private var turn = 0f
    private var palette: ArtPalette = ArtPalette.of(ArtSong("", -1, 0f, 0.5f, 0.3f, 0.5f, 0.5f, 100f, 0.5f, null))
    private var turned: ArtPalette = palette
    private var turnedBy = 0

    // Painting the cover: the painter's eye, how close to the cover, and where it is going.
    private val guide = ArtGuide()

    /** What is painted (the cover only when the song has one) and how closely it follows the cover (0 … 1). */
    var subject = ArtSubject.FREE
        private set
    var likeness = 1f

    /** The painter's own rough copy of the canvas: columns, rows and red, green, blue per cell (for checks). */
    fun eye(): Triple<Int, Int, FloatArray> = guide.copy()

    /** Painting a cover now (the song's, or a record). */
    val guided: Boolean get() = guide.painting
    private var songAt = 0.0
    private var fillDebt = 0f
    private var hotAtMs = -1e9
    private var hotX = 0f
    private var hotY = 0f

    /**
     * A new painting of [song] on a canvas of [w] × [h], in [style]; [veil] first fades what is on
     * the canvas. [subject] COVER paints the song's cover (without one, a record), as closely as [likeness].
     */
    fun start(song: ArtSong, w: Float, h: Float, nowMs: Long, veil: Boolean, style: ArtStyle = ArtStyle.WATERCOLOUR, subject: ArtSubject = ArtSubject.FREE, likeness: Float = this.likeness) {
        this.song = song
        chosen = style
        this.subject = subject
        this.likeness = likeness.coerceIn(0f, 1f)
        this.style = if (style == ArtStyle.AUTO) ArtStyle.forSong(song) else style
        width = max(1f, w)
        height = max(1f, h)
        history.clear()
        wet.clear()
        dried.clear()
        cover.fill(0f)
        paintedMs = -1.0
        rnd = java.util.Random(song.seed)
        flowSeed = rnd.nextInt()
        roamA = rnd.nextFloat() * 6.3f
        roamB = rnd.nextFloat() * 6.3f
        // Where the eye goes: one of the thirds; the horizon a little below the middle.
        focalX = width * (if (rnd.nextBoolean()) 0.36f else 0.64f) + (rnd.nextFloat() - 0.5f) * width * 0.08f
        horizon = height * (0.56f + rnd.nextFloat() * 0.12f)
        focalY = horizon - height * (0.12f + rnd.nextFloat() * 0.14f)
        hatchAngle = (if (rnd.nextBoolean()) 0.6f else 2.5f) + (rnd.nextFloat() - 0.5f) * 0.3f
        px = width * (0.3f + rnd.nextFloat() * 0.4f)
        py = height * (0.35f + rnd.nextFloat() * 0.3f)
        heading = rnd.nextFloat() * 2f * PI.toFloat()
        qx = px
        qy = py
        qHeading = heading + 1.5f
        penDown = false
        lastOnset = Long.MIN_VALUE
        nextBeatMs = 0.0
        hatchDebt = 0f
        radialDebt = 0f
        glazeAtMs = -1e9
        washed = false
        peakAt = -1.0
        sealed = false
        signed = false
        signing.clear()
        heardNotes.fill(0f)
        turn = 0f
        palette = if (this.style == ArtStyle.INK) song.palette.inked() else song.palette
        turned = palette
        turnedBy = 0
        squared = rnd.nextFloat() < 0.4f
        penColour = if (rnd.nextFloat() < 0.7f) floatArrayOf(0.13f, 0.2f, 0.52f) else floatArrayOf(0.12f, 0.12f, 0.15f)
        // Painting the cover: the song's own, or (without one) a record of it.
        val image = if (subject == ArtSubject.COVER) song.image ?: ArtCover.record(song) else null
        ground = if (this.style == ArtStyle.OIL) {
            val sky = song.palette.sky
            val gr = song.palette.ground
            val own = FloatArray(3) { (0.55f * sky[it] + 0.45f * gr[it]) * 0.92f + 0.06f }
            // (Painting a cover, the ground leans to the colour round its edge.)
            image?.edgeColour()?.let { e -> FloatArray(3) { own[it] * 0.3f + e[it] * 0.7f } } ?: own
        } else floatArrayOf(1f, 1f, 1f)
        // (The paper's grain greys it a little.)
        guide.reset(width, height, if (this.style == ArtStyle.OIL) ground else FloatArray(3) { ground[it] * 0.95f }, keep = veil)
        if (image != null) {
            guide.setCover(image)
            guide.focus()?.let { (fx, fy) ->
                focalX = fx
                focalY = fy
            }
        } else guide.clearCover()
        songAt = 0.0
        fillDebt = 0f
        hotAtMs = -1e9
        if (veil) emit(ArtMark(ArtMark.VEIL, width / 2, height / 2, width, height, 0f, ground[0], ground[1], ground[2], VEIL_ALPHA, 0f, nowMs, 0), instant = true)
    }

    /**
     * The canvas becomes [w] × [h] (the screen turned, or the painting shown in another shape): the
     * painting is laid out again over it, every mark in the same place across and down, the marks
     * themselves not stretched (washes and the veil spread with the canvas). Paint it all again after.
     */
    fun resize(w: Float, h: Float) {
        val nw = max(1f, w)
        val nh = max(1f, h)
        if (song == null) {
            width = nw
            height = nh
            return
        }
        val sx = nw / width
        val sy = nh / height
        // The marks take the room there is: as big when only turned, smaller on a smaller canvas.
        val s = kotlin.math.sqrt((nw * nh) / (width * height))
        fun moved(m: ArtMark): ArtMark = when (m.kind) {
            ArtMark.VEIL -> ArtMark(m.kind, nw / 2, nh / 2, nw, nh, m.angle, m.r, m.g, m.b, m.alpha, m.seed, m.bornMs, m.growMs)
            ArtMark.GLAZE -> ArtMark(m.kind, m.x * sx, m.y * sy, m.rx * sx, m.ry * sy, m.angle, m.r, m.g, m.b, m.alpha, m.seed, m.bornMs, m.growMs)
            else -> ArtMark(m.kind, m.x * sx, m.y * sy, m.rx * s, m.ry * s, m.angle, m.r, m.g, m.b, m.alpha, m.seed, m.bornMs, m.growMs)
        }
        for (list in listOf(history, wet, dried)) for (i in list.indices) list[i] = moved(list[i])
        px *= sx
        py *= sy
        qx *= sx
        qy *= sy
        focalX *= sx
        focalY *= sy
        horizon *= sy
        hotX *= sx
        hotY *= sy
        guide.resize(nw, nh)
        width = nw
        height = nh
    }

    /** Dried marks not yet on the canvas, moved into [into]. */
    fun takeDried(into: MutableList<ArtMark>) {
        into.addAll(dried)
        dried.clear()
    }

    /** Every mark still spreading dries now (the song changed). */
    fun dryAll() {
        // (The signature, if still being written, at once.)
        for ((_, m) in signing) {
            history.add(m)
            dried.add(m)
        }
        signing.clear()
        for (m in wet) {
            val d = m.dry()
            history.add(d)
            dried.add(d)
        }
        wet.clear()
    }

    /**
     * One frame at [nowMs] (a steady clock), the song at [songMs]: the live [level], [low] and
     * [high] (0 … 1), when the last strong low beat came ([onsetAtMs], same clock as [nowMs]) and
     * the share of each pitch class heard ([notes], 12; null when unknown).
     */
    fun frame(nowMs: Long, songMs: Double, playing: Boolean, level: Float, low: Float, high: Float, onsetAtMs: Long, notes: FloatArray? = null) {
        val s = song ?: return
        val iter = wet.iterator()
        while (iter.hasNext()) {
            val m = iter.next()
            if (m.growth(nowMs) >= 1f && nowMs >= m.bornMs) {
                iter.remove()
                val d = m.dry()
                history.add(d)
                dried.add(d)
            }
        }
        // The signature, written on letter by letter.
        while (signing.isNotEmpty() && signing[0].first <= nowMs) emit(signing.removeAt(0).second, instant = true)
        if (!playing) {
            lastOnset = onsetAtMs
            return
        }
        if (paintedMs < 0.0 || songMs > paintedMs + 3000.0) {
            // Opened in the middle, or a jump ahead: what would have been painted till here, at once.
            catchUp(nowMs, max(0.0, paintedMs), songMs)
            paintedMs = songMs
            lastOnset = onsetAtMs
            return
        }
        val dt = ((songMs - paintedMs) / 1000.0).coerceIn(0.0, 0.2).toFloat()
        paintedMs = songMs
        val onset = onsetAtMs != lastOnset && onsetAtMs > lastOnset
        lastOnset = onsetAtMs
        follow(s, notes, dt)
        step(s, nowMs, songMs, dt, level, low, high, onset, instant = false)
    }

    /** The hues turn round the circle of fifths as far as the chord heard is from the song's key. */
    private fun follow(s: ArtSong, notes: FloatArray?, dt: Float) {
        if (notes != null) {
            val k = 1f - exp(-dt / 0.5f)
            for (i in 0 until 12) heardNotes[i] += (notes[i] - heardNotes[i]) * k
        }
        var root = 0
        for (i in 1 until 12) if (heardNotes[i] > heardNotes[root]) root = i
        val clear = heardNotes[root] - heardNotes.average().toFloat()
        val tonic = if (s.key < 0) root else s.key % 12
        var steps = ((root - tonic) * 7 % 12 + 12) % 12
        if (steps > 6) steps -= 12
        val target = if (notes != null && clear > 0.04f) steps * TURN_PER_FIFTH else 0f
        turn += (target - turn) * (1f - exp(-dt / 1.6f))
        val by = (turn / 6f).toInt() * 6
        if (by != turnedBy) {
            turnedBy = by
            turned = palette.turned(by.toFloat())
        }
    }

    /** Paints from [fromMs] to [toMs] at once, from the song's shape alone. */
    private fun catchUp(nowMs: Long, fromMs: Double, toMs: Double) {
        val s = song ?: return
        val beat = beatMs(s)
        var t = fromMs
        var nextOnset = fromMs
        while (t < toMs) {
            val e = s.shapeAt(t)
            var onset = false
            if (t >= nextOnset) {
                nextOnset += beat * (if (e > 0.6f) 1.0 else 2.0)
                onset = e > 0.35f
            }
            step(s, nowMs, t, (CATCH_STEP / 1000.0).toFloat(), e * 0.85f, e * 0.8f, e * (0.3f + 0.6f * s.brightness), onset, instant = true)
            t += CATCH_STEP
        }
    }

    private fun beatMs(s: ArtSong): Double {
        var bpm = if (s.bpm in 40f..260f) s.bpm else 100f
        while (bpm > 150f) bpm /= 2f
        while (bpm < 60f) bpm *= 2f
        return 60_000.0 / bpm
    }

    private fun step(s: ArtSong, nowMs: Long, songMs: Double, dt: Float, level: Float, low: Float, high: Float, onset: Boolean, instant: Boolean) {
        val p = turned
        val minDim = min(width, height)
        val e = s.shapeAt(songMs)
        val stretch = s.sections?.modeAt(songMs) ?: 0
        if (!washed) {
            washed = true
            firstWashes(palette, nowMs, instant)
        }
        // How near the peak: the brush is drawn to the painting's centre of attention.
        val peak = s.peakMs
        val toPeak = if (peak > 0) abs(songMs - (peak + 6000.0)) else 1e9
        val near = exp(-(toPeak / 22_000.0)).toFloat()
        if (peak > 0 && peakAt < 0 && songMs >= peak) {
            peakAt = songMs
            bigGesture(p, nowMs, instant)
        }

        // The brush roams the whole canvas on a slow path of its own; the focus pulls it only near the peak.
        val speed = minDim * (0.035f + 0.13f * level + 0.05f * e)
        val margin = minDim * 0.07f
        val outside = px < margin || px > width - margin || py < margin || py > height - margin
        val t = songMs.toFloat()
        songAt = songMs
        var roamX = width * (0.5f + 0.4f * sin(t * 0.00013f + roamA))
        var roamY = height * (0.5f + 0.4f * sin(t * 0.000083f + roamB))
        if (guided) {
            // Painting the cover: the brush goes, now and then, to where the painting is furthest from it.
            if (songMs - hotAtMs > 1500.0) {
                hotAtMs = songMs
                guide.hotSpot(rnd, minDim * 0.08f * sizeFactor(), guideBlend())?.let { (hx, hy) ->
                    hotX = hx
                    hotY = hy
                }
            }
            val k = 0.5f + 0.4f * likeness
            roamX += (hotX - roamX) * k
            roamY += (hotY - roamY) * k
        }
        val tx = if (outside) width / 2 else roamX + (focalX - roamX) * near
        val ty = if (outside) height / 2 else roamY + (focalY - roamY) * near
        val want = atan2(ty - py, tx - px)
        val pull = (if (outside) 3f else 0f) + 1.4f * near + 0.7f
        heading += angleTo(heading, want) * min(1f, pull * dt) + (rnd.nextFloat() - 0.5f) * dt * 3f
        px += cos(heading) * speed * dt
        py += sin(heading) * speed * dt

        val strong = when {
            stretch > 0 -> 1.25f
            stretch < 0 -> 0.7f
            else -> 1f
        } * (1f + 0.6f * near)

        if (onset) {
            val strength = ((0.4f + 0.6f * low) * (0.6f + 0.6f * e) * strong).coerceIn(0.2f, 1.6f)
            val (bx, by) = spot(px + gauss() * minDim * 0.06f, py + gauss() * minDim * 0.06f, minDim * 0.12f)
            burst(p, bx, by, strength, near, stretch, nowMs, instant)
            nextBeatMs = songMs + beatMs(s)
        } else if (songMs >= nextBeatMs) {
            val loose = (1f - s.pulse).coerceIn(0f, 1f)
            nextBeatMs = max(songMs, nextBeatMs) + beatMs(s) * (1.0 + loose * (rnd.nextDouble() - 0.5) * 0.8)
            if (level > 0.12f) {
                val count = if (style == ArtStyle.PEN) (if (rnd.nextFloat() < 0.6f) 1 else 0) else 1 + (level * 2.5f * strong).toInt()
                repeat(count) {
                    val (dx, dy) = spot(px + gauss() * minDim * 0.1f, py + gauss() * minDim * 0.1f, minDim * 0.1f)
                    beatStroke(p, dx, dy, level, near, stretch, nowMs, instant)
                }
            }
        }

        // The high sounds: fine hatching (or dots of light), rougher for a rough song.
        hatchDebt += high * high * (5f + 24f * s.roughness) * dt
        while (hatchDebt >= 1f) {
            hatchDebt -= 1f
            val (hx, hy) = spot(px + gauss() * minDim * 0.14f, py + gauss() * minDim * 0.14f, minDim * 0.08f)
            highTouch(p, hx, hy, high, nowMs, instant)
        }

        // Washes: often in quiet stretches, now and then otherwise; sky above the horizon, ground below.
        val every = if (stretch < 0 || level < 0.25f) 2600.0 else 9000.0
        if (songMs - glazeAtMs > every) {
            glazeAtMs = songMs
            glazeSky = !glazeSky
            val gy = if (glazeSky) horizon * (0.2f + rnd.nextFloat() * 0.7f) else horizon + (height - horizon) * (0.15f + rnd.nextFloat() * 0.75f)
            val gx = width * (0.15f + rnd.nextFloat() * 0.7f)
            wash(p, gx, gy, glazeSky, nowMs, instant)
        }

        // A sung song: a line flows while it is loud enough, lifted in the quiet.
        if (s.voice > 0.25f) {
            if (penDown && level < 0.18f) penDown = false
            if (!penDown && level > 0.3f && e > 0.15f) {
                penDown = true
                qx = (px + gauss() * minDim * 0.2f).coerceIn(margin, width - margin)
                qy = (py + gauss() * minDim * 0.2f).coerceIn(margin, height - margin)
                qHeading = rnd.nextFloat() * 6.3f
                if (guided) {
                    // Painting the cover: the pen sets down on one of its edges, where it is still missing.
                    aim(qx, qy, minDim * 0.3f, minDim * 0.02f, ArtGuide.DARKEN, edges = true).let { (ex, ey) ->
                        qx = ex
                        qy = ey
                    }
                    qHeading = guide.alongAt(qx, qy).first + if (rnd.nextBoolean()) 0f else PI.toFloat()
                }
            }
            if (penDown) {
                val qOut = qx < margin || qx > width - margin || qy < margin || qy > height - margin
                if (qOut) qHeading += angleTo(qHeading, atan2(height / 2 - qy, width / 2 - qx)) * min(1f, 3f * dt)
                if (guided) {
                    // ... and follows the edge it is on, as far as it goes.
                    val (al, st) = guide.alongAt(qx, qy)
                    val way = if (abs(angleTo(qHeading, al)) < PI / 2) al else al + PI.toFloat()
                    qHeading += angleTo(qHeading, way) * min(1f, (2f + 8f * st) * likeness * dt)
                    if (st < 0.08f && rnd.nextFloat() < dt * 1.5f) penDown = false
                }
                qHeading += (rnd.nextFloat() - 0.5f) * dt * 3f + sin((songMs / 650.0).toFloat()) * dt * 1.6f * (if (guided) 1f - likeness else 1f)
                val qs = minDim * (0.05f + 0.09f * level)
                val nx = qx + cos(qHeading) * qs * dt
                val ny = qy + sin(qHeading) * qs * dt
                if (hypot(nx - qx, ny - qy) > 0f) voiceLine(p, s, qx, qy, nx, ny, level, nowMs, instant)
                qx = nx
                qy = ny
            }
        }

        // Painting the cover: more touches, all where it is still missing, steadily (more with the music).
        if (guided) {
            val rate = (2f + 16f * likeness) * (0.35f + level) * (0.6f + 1.2f * progress()) * (if (style == ArtStyle.PEN) 0.45f else 1f)
            fillDebt += rate * dt
            while (fillDebt >= 1f) {
                fillDebt -= 1f
                fill(p, level, near, stretch, nowMs, instant)
            }
        }

        // After the peak's big gesture: strokes bursting out of it for a few seconds.
        if (peakAt >= 0 && songMs - peakAt < 5000) {
            radialDebt += dt * (4f + 10f * level)
            while (radialDebt >= 1f) {
                radialDebt -= 1f
                val a = rnd.nextFloat() * 6.3f
                val d = minDim * (0.1f + rnd.nextFloat() * 0.22f)
                val len = minDim * (0.035f + rnd.nextFloat() * 0.05f)
                // (In ink, dark strokes with the colour now and then.)
                val c = jitter(if (rnd.nextFloat() < (if (style == ArtStyle.INK) 0.15f else 0.5f)) p.accent else p.main, 0.06f)
                val x = focalX + cos(a) * d
                val y = focalY + sin(a) * d
                when (style) {
                    ArtStyle.OIL -> emit(mark(ArtMark.OIL, x, y, len, len * 0.3f, a, c, 0.92f, nowMs, 200), instant)
                    ArtStyle.PEN -> line(ArtMark.PEN, x, y, x + cos(a) * len, y + sin(a) * len, penWidth(), penColour, 0.9f, nowMs, 400, instant)
                    else -> emit(mark(ArtMark.DAB, x, y, len, len * 0.28f, a, c, 0.45f, nowMs, 200), instant)
                }
            }
        }

        // The signature, the song's title written small beside where the seal goes, before the end.
        val length = s.lengthMs
        if (!signed && length > 20_000 && songMs > length - 9000) {
            signed = true
            sign(p, nowMs, instant)
        }

        // The seal, as the song ends.
        if (!sealed && length > 20_000 && songMs > length - 2500) {
            sealed = true
            val size = minDim * 0.032f
            val m = minDim * 0.05f
            emit(mark(ArtMark.SEAL, width - m - size, height - m - size, size, size, (rnd.nextFloat() - 0.5f) * 0.08f, palette.seal, 0.85f, nowMs, 350), instant)
        }
    }

    // ---------- What each style lays for each moment ----------

    /** The first layer: pale washes (watercolour, ink), the blocked-in sky and ground (oil), a little colour on the horizon (ballpoint). */
    private fun firstWashes(p: ArtPalette, nowMs: Long, instant: Boolean) {
        when (style) {
            ArtStyle.OIL -> {
                val minDim = min(width, height)
                repeat(70) { i ->
                    val y = height * rnd.nextFloat()
                    val sky = y < horizon
                    val c = jitter(if (sky) mixed(p.sky, p.second, 0.25f) else mixed(p.ground, p.main, 0.25f), 0.06f)
                    val len = minDim * (0.09f + rnd.nextFloat() * 0.09f)
                    emit(mark(ArtMark.OIL, width * rnd.nextFloat(), y, len, len * (0.3f + rnd.nextFloat() * 0.1f), (rnd.nextFloat() - 0.5f) * 0.5f,
                        c, 0.85f, nowMs + i * 25L, 260), instant)
                }
            }
            ArtStyle.PEN -> {
                val c = mixed(p.ground, p.main, 0.3f)
                emit(mark(ArtMark.PENCIL, width / 2, horizon, width * 0.42f, height * 0.035f, (rnd.nextFloat() - 0.5f) * 0.04f, c, 0.7f, nowMs, 1200), instant)
                val sk = mixed(p.sky, p.second, 0.2f)
                emit(mark(ArtMark.PENCIL, width * (0.3f + rnd.nextFloat() * 0.4f), horizon * 0.35f, width * 0.22f, height * 0.06f, (rnd.nextFloat() - 0.5f) * 0.1f, sk, 0.4f, nowMs, 1200), instant)
            }
            else -> {
                val sky = p.sky
                val ground = mixed(p.ground, p.main, 0.15f)
                emit(mark(ArtMark.GLAZE, width / 2, horizon * 0.48f, width * 0.75f, horizon * 0.62f, 0f, sky, 0.24f, nowMs, 2500), instant)
                emit(mark(ArtMark.GLAZE, width / 2, horizon + (height - horizon) * 0.55f, width * 0.75f, (height - horizon) * 0.7f, 0f, ground, 0.26f, nowMs, 2500), instant)
                val line = mixed(p.ground, p.third, 0.4f)
                emit(mark(ArtMark.GLAZE, width / 2, horizon, width * 0.6f, height * 0.025f, (rnd.nextFloat() - 0.5f) * 0.05f, line, 0.09f, nowMs, 3000), instant)
            }
        }
    }

    /** A strong low beat: a blot with its splash, a swirl of thick strokes, or a scribbled loop. */
    private fun burst(p: ArtPalette, bx: Float, by: Float, strength: Float, near: Float, stretch: Int, nowMs: Long, instant: Boolean) {
        val minDim = min(width, height)
        val c = pick(p, accentChance = 0.08f + 0.3f * near)
        when (style) {
            ArtStyle.OIL -> {
                val n = 3 + (strength * 5).toInt() + rnd.nextInt(3)
                val r = minDim * (0.02f + 0.04f * strength) * vary(0.35f)
                val a0 = rnd.nextFloat() * 6.3f
                val pattern = rnd.nextInt(3)
                val arc = if (pattern == 2) 2.2f + rnd.nextFloat() * 1.5f else 6.283f
                val spin = if (rnd.nextBoolean()) 1f else -1f
                repeat(n) { i ->
                    val a = a0 + spin * i * arc / n + gauss() * 0.2f
                    val len = minDim * (0.025f + 0.035f * strength) * vary(0.35f)
                    val cc = jitter(if (i % 3 == 0) pick(p, 0.1f) else c, 0.05f)
                    val facing = if (pattern == 1) a else a + 1.5708f + gauss() * 0.25f
                    emit(mark(ArtMark.OIL, bx + cos(a) * r, by + sin(a) * r, len, len * (0.22f + rnd.nextFloat() * 0.2f), facing, cc, 0.93f, nowMs + i * 30L, 180), instant)
                }
            }
            ArtStyle.PEN -> {
                val r = minDim * (0.018f + 0.045f * strength)
                val coloured = strength > 0.7f || stretch > 0 || near > 0.3f
                if (coloured) {
                    emit(mark(ArtMark.PENCIL, bx, by, r * 1.05f, r * 0.95f, rnd.nextFloat() * 3.1f, c, 0.75f, nowMs, 600), instant)
                }
                // A loop now and then (always round a coloured one), else just a dot pressed in.
                if (coloured || rnd.nextFloat() < 0.35f) scribble(bx, by, r * vary(0.3f), 1.2f + rnd.nextFloat() * 1.0f, nowMs, instant, shape = rnd.nextInt(SCRIBBLES))
                else line(ArtMark.PEN, bx, by, bx + penWidth(), by, penWidth() * 1.8f, penColour, 0.95f, nowMs, 0, instant)
            }
            else -> {
                val ink = style == ArtStyle.INK
                val r = minDim * (0.03f + 0.075f * strength) * vary(0.4f)
                val blot = if (ink && rnd.nextFloat() > 0.08f + 0.3f * near) p.second else c
                emit(mark(ArtMark.BLOOM, bx, by, r, r * (0.5f + rnd.nextFloat() * 0.75f), rnd.nextFloat() * 6.3f,
                    blot, (0.3f + 0.22f * strength) * inkAt(bx, by), nowMs, (450 + 550 * strength).toInt()), instant)
                val drops = (2 + 7 * strength * (0.5f + (song?.roughness ?: 0.3f))).toInt()
                val dir = heading + (rnd.nextFloat() - 0.5f) * 2f
                val dropColour = if (ink) p.main else c
                repeat(drops) {
                    val a = dir + gauss() * (0.4f + rnd.nextFloat() * 0.6f)
                    val d = r * (1.1f + rnd.nextFloat() * 2.0f)
                    val dr = minDim * (0.002f + rnd.nextFloat() * 0.01f) * (0.6f + strength * 0.5f) * vary(0.3f)
                    val x = bx + cos(a) * d
                    val y = by + sin(a) * d
                    // (Drops flung off the canvas are lost.)
                    if (x < 0f || x > width || y < 0f || y > height) return@repeat
                    emit(mark(ArtMark.DROP, x, y, dr, dr, 0f, dropColour, 0.45f + rnd.nextFloat() * (if (ink) 0.4f else 0.2f), nowMs, 150 + rnd.nextInt(200)), instant)
                }
            }
        }
    }

    /** The beat: a short stroke along the flow (a calligraphic one in ink, a thick one in oil, a group of hatches in ballpoint). */
    private fun beatStroke(p: ArtPalette, dx: Float, dy: Float, level: Float, near: Float, stretch: Int, nowMs: Long, instant: Boolean) {
        val minDim = min(width, height)
        val len = minDim * (0.022f + 0.04f * level) * (if (stretch < 0) 0.8f else 1f) * vary(0.45f)
        val angle = if (rnd.nextFloat() < 0.28f) rnd.nextFloat() * 6.283f else flow(dx, dy) + gauss() * 0.45f
        when (style) {
            ArtStyle.OIL -> {
                val c = jitter(pick(p, accentChance = 0.04f + 0.15f * near), 0.08f)
                emit(mark(ArtMark.OIL, dx, dy, len * 1.1f, len * (0.2f + rnd.nextFloat() * 0.25f), angle, c, 0.92f, nowMs, 160), instant)
            }
            ArtStyle.PEN -> {
                val w = penWidth()
                // One set of hatches, now and then crossed by a second.
                val sets = if (rnd.nextFloat() < 0.25f) 2 else 1
                repeat(sets) { s ->
                    val n = 2 + rnd.nextInt(6)
                    val dir = angle + s * (0.9f + rnd.nextFloat() * 0.8f)
                    val gap = w * (2.5f + rnd.nextFloat() * 4f)
                    val nx = -sin(dir)
                    val ny = cos(dir)
                    repeat(n) { i ->
                        val o = (i - (n - 1) / 2f) * gap
                        val cx = dx + nx * o + gauss() * w
                        val cy = dy + ny * o + gauss() * w
                        val a = dir + gauss() * 0.08f
                        val l = len * (0.5f + rnd.nextFloat() * 0.8f) / 2
                        line(ArtMark.PEN, cx - cos(a) * l, cy - sin(a) * l, cx + cos(a) * l, cy + sin(a) * l, w, penColour, 0.9f, nowMs + (s * n + i) * 40L, 250, instant)
                    }
                }
            }
            ArtStyle.INK -> {
                val c = if (rnd.nextFloat() < 0.04f + 0.12f * near) p.accent else p.main
                emit(mark(ArtMark.DAB, dx, dy, len * 1.4f, len * (0.12f + rnd.nextFloat() * 0.22f), angle, c, (0.55f + 0.3f * level) * inkAt(dx, dy), nowMs, 160), instant)
            }
            else -> {
                val c = jitter(pick(p, accentChance = 0.04f + 0.15f * near), 0.07f)
                emit(mark(ArtMark.DAB, dx, dy, len, len * (0.16f + rnd.nextFloat() * 0.38f), angle, c, (0.3f + 0.3f * level) * inkAt(dx, dy), nowMs, 140), instant)
            }
        }
    }

    /** A high sound: a fine hatch (crossed in ink), a dot of light (oil), a tick (ballpoint). */
    private fun highTouch(p: ArtPalette, hx: Float, hy: Float, high: Float, nowMs: Long, instant: Boolean) {
        val minDim = min(width, height)
        when (style) {
            ArtStyle.OIL -> {
                val c = mixed(pick(p, 0.1f), floatArrayOf(1f, 1f, 1f), 0.45f)
                val r = minDim * (0.004f + rnd.nextFloat() * 0.006f)
                emit(mark(ArtMark.DOT, hx, hy, r, r, rnd.nextFloat() * 6.3f, jitter(c, 0.05f), 0.95f, nowMs, 0), instant)
            }
            ArtStyle.PEN -> {
                val l = minDim * (0.004f + rnd.nextFloat() * 0.008f)
                val a = hatchAngle + gauss() * 0.4f
                line(ArtMark.PEN, hx, hy, hx + cos(a) * l, hy + sin(a) * l, penWidth() * 0.9f, penColour, 0.85f, nowMs, 0, instant)
            }
            else -> {
                hatchTurn = !hatchTurn
                val angle = if (style == ArtStyle.INK && hatchTurn) hatchAngle + 1.2f else hatchAngle
                val len = minDim * (0.014f + rnd.nextFloat() * 0.035f)
                val w = max(1.2f, minDim * 0.0022f)
                emit(mark(ArtMark.LINE, hx, hy, len, w, angle + gauss() * 0.15f, mixed(p.ink, p.main, 0.3f), 0.2f + 0.25f * high, nowMs, 0), instant)
            }
        }
    }

    /** A wash: pale over the sky or the ground (oil: scumbled strokes; ballpoint: now and then some coloured pencil). */
    private fun wash(p: ArtPalette, gx: Float, gy: Float, sky: Boolean, nowMs: Long, instant: Boolean) {
        val c = if (sky) mixed(p.sky, p.second, 0.2f) else mixed(p.ground, p.main, 0.25f)
        when (style) {
            ArtStyle.OIL -> {
                val minDim = min(width, height)
                repeat(5) { i ->
                    val len = minDim * (0.08f + rnd.nextFloat() * 0.06f)
                    emit(mark(ArtMark.OIL, gx + gauss() * width * 0.12f, gy + gauss() * height * 0.02f, len, len * 0.35f, (rnd.nextFloat() - 0.5f) * 0.3f,
                        jitter(c, 0.05f), 0.5f, nowMs + i * 60L, 400), instant)
                }
            }
            ArtStyle.PEN -> if (rnd.nextFloat() < 0.6f) {
                emit(mark(ArtMark.PENCIL, gx, gy, width * (0.1f + rnd.nextFloat() * 0.12f), height * (0.025f + rnd.nextFloat() * 0.03f), (rnd.nextFloat() - 0.5f) * 0.2f, c, 0.6f, nowMs, 900), instant)
            }
            else -> emit(mark(ArtMark.GLAZE, gx, gy, width * (0.22f + rnd.nextFloat() * 0.25f), height * (0.03f + rnd.nextFloat() * 0.04f),
                (rnd.nextFloat() - 0.5f) * 0.16f, c, 0.13f + rnd.nextFloat() * 0.09f, nowMs, 1800), instant)
        }
    }

    /** The voice's line from (x0, y0) to (x1, y1): pen, ballpoint, or a long brush stroke in oil. */
    private fun voiceLine(p: ArtPalette, s: ArtSong, x0: Float, y0: Float, x1: Float, y1: Float, level: Float, nowMs: Long, instant: Boolean) {
        val minDim = min(width, height)
        when (style) {
            ArtStyle.OIL -> {
                val w = minDim * (0.0035f + 0.004f * level)
                line(ArtMark.OIL, x0, y0, x1, y1, w * 2f, mixed(p.main, p.ink, 0.35f), 0.85f, nowMs, 0, instant)
            }
            ArtStyle.PEN -> line(ArtMark.PEN, x0, y0, x1, y1, penWidth() * (0.9f + 0.4f * level), penColour, 0.95f, nowMs, 0, instant)
            else -> {
                val w = minDim * (0.0012f + 0.0035f * level) * (0.6f + 0.6f * s.voice)
                val c = if (style == ArtStyle.INK) p.ink else mixed(p.ink, p.main, 0.3f)
                line(ArtMark.INK, x0, y0, x1, y1, w, c, 0.5f * min(1f, 0.4f + s.voice) * (if (style == ArtStyle.INK) 1.5f else 1f), nowMs, 0, instant)
            }
        }
    }

    /** The peak: the painting's big gesture at its centre of attention. */
    private fun bigGesture(p: ArtPalette, nowMs: Long, instant: Boolean) {
        val minDim = min(width, height)
        when (style) {
            ArtStyle.OIL -> {
                // A swirling sun: rings of strokes round the focus, the colour across from the song's inside.
                var k = 0
                for (ring in 0 until 3) {
                    val r = minDim * (0.035f + 0.05f * ring)
                    val n = 8 + 7 * ring
                    repeat(n) { i ->
                        val a = i * 6.283f / n + ring * 0.4f + gauss() * 0.08f
                        val len = minDim * (0.035f + 0.012f * ring)
                        val c = jitter(if (ring == 0) p.accent else if (ring == 1) mixed(p.accent, p.main, 0.5f) else p.main, 0.06f)
                        emit(mark(ArtMark.OIL, focalX + cos(a) * r, focalY + sin(a) * r, len, len * 0.3f, a + 1.5708f, c, 0.94f, nowMs + (k++) * 22L, 200), instant)
                    }
                }
                repeat(10) {
                    val rr = minDim * 0.008f
                    emit(mark(ArtMark.DOT, focalX + gauss() * minDim * 0.015f, focalY + gauss() * minDim * 0.015f, rr, rr, 0f, mixed(p.accent, floatArrayOf(1f, 1f, 1f), 0.5f), 0.95f, nowMs + 900L, 0), instant)
                }
            }
            ArtStyle.PEN -> {
                val r = minDim * 0.1f
                emit(mark(ArtMark.PENCIL, focalX, focalY, r, r, rnd.nextFloat() * 3f, p.accent, 0.65f, nowMs, 1500), instant)
                scribble(focalX, focalY, r * 1.05f, 1.2f, nowMs, instant)
                repeat(16) { i ->
                    val a = i * 6.283f / 16 + gauss() * 0.06f
                    val r0 = r * 1.25f
                    val r1 = r * (1.6f + rnd.nextFloat() * 0.4f)
                    line(ArtMark.PEN, focalX + cos(a) * r0, focalY + sin(a) * r0, focalX + cos(a) * r1, focalY + sin(a) * r1, penWidth(), penColour, 0.9f, nowMs + 600L + i * 50L, 300, instant)
                }
            }
            else -> {
                val r = minDim * 0.16f
                emit(mark(ArtMark.BLOOM, focalX, focalY, r, r * 0.92f, rnd.nextFloat() * 6.3f, p.accent, 0.5f, nowMs, 2600), instant)
                repeat(8) { i ->
                    val a = i / 8f * 6.283f + rnd.nextFloat() * 0.4f
                    val d = minDim * (0.2f + rnd.nextFloat() * 0.06f)
                    val rr = minDim * (0.035f + rnd.nextFloat() * 0.03f)
                    val c = if (style == ArtStyle.INK) p.second else pick(p, accentChance = 0f)
                    emit(mark(ArtMark.BLOOM, focalX + cos(a) * d, focalY + sin(a) * d, rr, rr, rnd.nextFloat() * 6.3f, c, 0.32f, nowMs, 1200 + rnd.nextInt(800)), instant)
                }
            }
        }
    }

    /**
     * A ballpoint scribble round ([cx], [cy]), about [r] across: a loop going [turns] times round
     * (shape 0), a long tilted oval (1), a spiral in to the middle (2), a zigzag (3), a wave (4) or a
     * figure of eight (5).
     */
    private fun scribble(cx: Float, cy: Float, r: Float, turns: Float, nowMs: Long, instant: Boolean, shape: Int = 0) {
        val w = penWidth()
        val tilt = rnd.nextFloat() * 3.1416f
        val ct = cos(tilt)
        val st = sin(tilt)
        val squash = if (shape == 1) 0.3f + rnd.nextFloat() * 0.3f else 0.7f + rnd.nextFloat() * 0.3f
        val n = when (shape) {
            3 -> 4 + rnd.nextInt(6)
            4 -> 16
            5 -> 28
            else -> (turns * 18).toInt().coerceAtLeast(8)
        }
        var a = rnd.nextFloat() * 6.3f
        var rr = r * (0.85f + rnd.nextFloat() * 0.3f)
        val wobble = 0.03f + rnd.nextFloat() * 0.1f
        // Each point along the scribble in its own frame (x along, y across), turned by tilt.
        fun at(i: Int): Pair<Float, Float> {
            val t = i / n.toFloat()
            val (u, v) = when (shape) {
                2 -> (r * (1f - 0.85f * t)).let { s -> cos(a + t * turns * 6.283f) * s to sin(a + t * turns * 6.283f) * s * squash }
                3 -> (t * 2f - 1f) * r * 1.2f to (if (i % 2 == 0) -r * 0.4f else r * 0.4f) * (0.7f + rnd.nextFloat() * 0.6f)
                4 -> (t * 2f - 1f) * r * 1.4f to sin(t * 6.283f * (1.5f + turns * 0.5f)) * r * 0.35f
                5 -> sin(t * 6.283f) * r to sin(t * 12.566f) * r * 0.5f * squash
                else -> cos(a + t * turns * 6.283f) * rr to sin(a + t * turns * 6.283f) * rr * squash
            }
            rr = (rr + gauss() * r * wobble).coerceIn(r * 0.6f, r * 1.3f)
            return cx + u * ct - v * st to cy + u * st + v * ct
        }
        var (x0, y0) = at(0)
        for (i in 1..n) {
            val (x1, y1) = at(i)
            line(ArtMark.PEN, x0, y0, x1, y1, w, penColour, 0.9f, nowMs + i * 18L, 500, instant)
            x0 = x1
            y0 = y1
        }
    }

    /**
     * The song's title ([ArtSong.titleImage]) written small at the bottom right, left of the seal,
     * in the style's own fine line, left to right over a couple of seconds (at once, [instant]).
     * Each row of the written title's ink becomes a fine stroke.
     */
    private fun sign(p: ArtPalette, nowMs: Long, instant: Boolean) {
        val t = song?.titleImage ?: return
        val minDim = min(width, height)
        val seal = minDim * 0.032f
        val m = minDim * 0.05f
        var px = minDim * SIGN_HEIGHT / SIGN_LINE_PX
        val maxW = width * 0.42f
        if (t.w * px > maxW) px = maxW / t.w
        val right = width - m - seal * 2f - minDim * 0.012f
        val left = right - t.w * px
        val top = height - m - seal - t.h * px / 2f
        val kind: Int
        val c: FloatArray
        val alpha: Float
        when (style) {
            ArtStyle.OIL -> {
                kind = ArtMark.OIL
                // (Light letters on a dark place, oil can.)
                c = if (guide.lightness(left + t.w * px / 2, top + t.h * px / 2) < 0.45f) floatArrayOf(0.95f, 0.92f, 0.85f) else mixed(p.ink, p.main, 0.35f)
                alpha = 0.95f
            }
            ArtStyle.PEN -> {
                kind = ArtMark.PEN
                c = penColour
                alpha = 0.95f
            }
            ArtStyle.INK -> {
                kind = ArtMark.INK
                c = p.ink
                alpha = 0.9f
            }
            else -> {
                kind = ArtMark.INK
                c = mixed(p.ink, p.main, 0.3f)
                alpha = 0.85f
            }
        }
        val w = px * 1.05f
        val out = ArrayList<Pair<Long, ArtMark>>()
        for (j in 0 until t.h) {
            val y = top + (j + 0.5f) * px
            var i = 0
            while (i < t.w) {
                if (((t.argb[j * t.w + i] ushr 24) and 0xff) < 128) {
                    i++
                    continue
                }
                val i0 = i
                while (i < t.w && ((t.argb[j * t.w + i] ushr 24) and 0xff) >= 128) i++
                val x0 = left + i0 * px + w / 2
                val x1 = max(x0 + px * 0.2f, left + i * px - w / 2)
                val d = x1 - x0
                val mk = ArtMark(kind, (x0 + x1) / 2, y, d / 2 + w / 2, w / 2, 0f, c[0], c[1], c[2], alpha, rnd.nextFloat(), nowMs, 0)
                out.add(nowMs + ((i0 / t.w.toFloat()) * SIGN_MS).toLong() to mk)
            }
        }
        if (instant) {
            for ((_, mk) in out) emit(mk, instant = true)
        } else {
            out.sortBy { it.first }
            signing.addAll(out)
        }
    }

    // ---------- Painting the cover ----------

    /** A touch where the cover is still missing: a stroke of the style (a small blot early on, in watercolour). */
    private fun fill(p: ArtPalette, level: Float, near: Float, stretch: Int, nowMs: Long, instant: Boolean) {
        val minDim = min(width, height)
        val r = minDim * 0.035f * sizeFactor()
        val (fx, fy) = aim(px + gauss() * minDim * 0.15f, py + gauss() * minDim * 0.15f, minDim * 0.15f, r, guideBlend())
        if (style == ArtStyle.WATERCOLOUR && progress() < 0.35f && rnd.nextFloat() < 0.3f) {
            emit(mark(ArtMark.BLOOM, fx, fy, r, r * (0.6f + rnd.nextFloat() * 0.4f), rnd.nextFloat() * 6.3f, pick(p, 0f), 0.35f, nowMs, 600), instant)
        } else {
            beatStroke(p, fx, fy, max(level, 0.35f), near, stretch, nowMs, instant)
        }
    }

    /**
     * Of a few places near ([x], [y]) within [reach] and a few anywhere on the cover (more, the closer
     * it is to follow), the one where the painting is furthest from the cover at the scale of [r]
     * ([edges]: and on an edge of it).
     */
    private fun aim(x: Float, y: Float, reach: Float, r: Float, blend: Int, edges: Boolean = false): Pair<Float, Float> {
        var bx = x.coerceIn(width * 0.02f, width * 0.98f)
        var by = y.coerceIn(height * 0.02f, height * 0.98f)
        var best = -1f
        val far = 2 + (likeness * 8).toInt()
        repeat(4 + far) { i ->
            val cx: Float
            val cy: Float
            if (i < 4) {
                cx = (if (i == 0) x else x + gauss() * reach).coerceIn(width * 0.02f, width * 0.98f)
                cy = (if (i == 0) y else y + gauss() * reach).coerceIn(height * 0.02f, height * 0.98f)
            } else {
                cx = guide.left + rnd.nextFloat() * (guide.right - guide.left)
                cy = guide.top + rnd.nextFloat() * (guide.bottom - guide.top)
            }
            var e = guide.error(cx, cy, r, blend)
            if (edges) e *= 0.2f + guide.alongAt(cx, cy).second
            if (e > best) {
                best = e
                bx = cx
                by = cy
            }
        }
        return bx to by
    }

    /** How the style's own touches lay their paint (what the painting's distance from the cover is measured by). */
    private fun guideBlend(): Int = when (style) {
        ArtStyle.OIL -> ArtGuide.OVER
        ArtStyle.PEN -> ArtGuide.DARKEN
        else -> ArtGuide.MULTIPLY
    }

    /** How far through the song, 0 … 1 (a song of unknown length: as if four minutes). */
    private fun progress(): Float {
        val length = song?.lengthMs ?: 0L
        return (songAt / (if (length > 20_000) length.toDouble() else 240_000.0)).toFloat().coerceIn(0f, 1f)
    }

    /** Painting the cover: big touches first, finer and finer to the end (more so, the closer it is to follow). */
    private fun sizeFactor(): Float {
        val raw = 1.6f - 1.38f * Math.pow(progress().toDouble(), 0.8).toFloat()
        return 1f + (raw - 1f) * (0.4f + 0.6f * likeness)
    }

    private fun penWidth() = max(1.3f, min(width, height) * 0.0017f)

    /** A straight piece of line of [kind] from (x0, y0) to (x1, y1), [w] wide. */
    private fun line(kind: Int, x0: Float, y0: Float, x1: Float, y1: Float, w: Float, c: FloatArray, alpha: Float, bornMs: Long, growMs: Int, instant: Boolean) {
        val d = hypot(x1 - x0, y1 - y0)
        if (d <= 0f) return
        emit(mark(kind, (x0 + x1) / 2, (y0 + y1) / 2, d / 2 + w / 2, w / 2, atan2(y1 - y0, x1 - x0), c, alpha, bornMs, growMs, piece = true), instant)
    }

    /**
     * A mark of [kind]. Painting the cover, its size follows the song's way from big to fine (not the
     * pieces of a line: they must meet), and its pigment (not the dark lines') leans to what brings the
     * canvas towards the cover there; round the cover, lighter.
     */
    private fun mark(kind: Int, x: Float, y: Float, rx: Float, ry: Float, angle: Float, c: FloatArray, alpha: Float, bornMs: Long, growMs: Int, piece: Boolean = false): ArtMark {
        if (!guided || kind == ArtMark.SEAL || kind == ArtMark.VEIL) return ArtMark(kind, x, y, rx, ry, angle, c[0], c[1], c[2], alpha, rnd.nextFloat(), bornMs, growMs)
        var sx = rx
        var sy = ry
        if (!piece && kind != ArtMark.GLAZE) {
            val f = sizeFactor()
            sx *= f
            sy *= f
        }
        var col = c
        var a = alpha
        val darkLine = kind == ArtMark.INK || kind == ArtMark.LINE || kind == ArtMark.PEN
        // (Now and then a touch keeps the song's own colour: less often, the closer it is to follow.)
        val free = if (guide.inside(x, y)) 0.2f * Math.pow((1f - likeness).toDouble(), 1.5).toFloat() else 0f
        if (!darkLine && rnd.nextFloat() >= free) {
            val (pc, pa) = guide.pigment(x, y, sx, sy, angle, alpha, ArtGuide.blendOf(kind), style == ArtStyle.INK)
            val k = 0.7f + 0.3f * likeness
            col = FloatArray(3) { c[it] + (pc[it] - c[it]) * k }
            a = alpha + (pa - alpha) * k
        }
        if (!guide.inside(x, y)) a *= 0.55f
        return ArtMark(kind, x, y, sx, sy, angle, col[0], col[1], col[2], a, rnd.nextFloat(), bornMs, growMs)
    }

    private fun emit(m: ArtMark, instant: Boolean) {
        guide.lay(m)
        val gx = ((m.x / width) * GRID).toInt().coerceIn(0, GRID - 1)
        val gy = ((m.y / height) * GRID).toInt().coerceIn(0, GRID - 1)
        if (m.kind != ArtMark.VEIL && m.kind != ArtMark.GLAZE) {
            val cell = (width / GRID) * (height / GRID)
            cover[gy * GRID + gx] += m.alpha * (PI.toFloat() * m.rx * m.ry) / cell
        }
        if (instant || m.growMs <= 0) {
            val d = m.dry()
            history.add(d)
            dried.add(d)
        } else {
            wet.add(m)
        }
    }

    /** Of a few places around ([x], [y]) within [reach], the least painted. */
    private fun spot(x: Float, y: Float, reach: Float): Pair<Float, Float> {
        // Painting the cover: where it is still missing.
        if (guided) return aim(x, y, reach, min(width, height) * 0.035f * sizeFactor(), guideBlend())
        var bx = x
        var by = y
        var best = Float.MAX_VALUE
        repeat(6) { i ->
            val cx = (if (i == 0) x else x + gauss() * reach).coerceIn(width * 0.03f, width * 0.97f)
            val cy = (if (i == 0) y else y + gauss() * reach).coerceIn(height * 0.03f, height * 0.97f)
            val c = coverAt(cx, cy)
            if (c < best) {
                best = c
                bx = cx
                by = cy
            }
        }
        return bx to by
    }

    private fun coverAt(x: Float, y: Float): Float {
        val gx = ((x / width) * GRID).toInt().coerceIn(0, GRID - 1)
        val gy = ((y / height) * GRID).toInt().coerceIn(0, GRID - 1)
        return cover[gy * GRID + gx]
    }

    /** Less pigment where much is already laid (no mud). */
    private fun inkAt(x: Float, y: Float): Float = (1.15f - coverAt(x, y) / 2.2f).coerceIn(0.15f, 1f)

    /** The direction strokes follow at ([x], [y]): a slow field of currents. */
    private fun flow(x: Float, y: Float): Float {
        if (guided) {
            // Painting the cover: along its edges, where there are any.
            val (al, st) = guide.alongAt(x, y)
            if (st > 0.12f) return al
        }
        val f = 2.2f / min(width, height)
        return noise(x * f, y * f, flowSeed) * 6.283f * 1.2f
    }

    private fun pick(p: ArtPalette, accentChance: Float): FloatArray {
        if (rnd.nextFloat() < accentChance) return p.accent
        return when (rnd.nextInt(5)) {
            0, 1 -> p.main
            2, 3 -> p.second
            else -> p.third
        }
    }

    private fun jitter(c: FloatArray, by: Float): FloatArray {
        val l = (rnd.nextFloat() - 0.5f) * by * 2f
        return FloatArray(3) { (c[it] + l + (rnd.nextFloat() - 0.5f) * by).coerceIn(0f, 1f) }
    }

    private fun gauss(): Float = rnd.nextGaussian().toFloat().coerceIn(-2.5f, 2.5f)

    /** A size factor around 1, now and then much smaller or larger ([spread]: how far apart). */
    private fun vary(spread: Float): Float = exp(gauss() * spread)

    companion object {
        private const val GRID = 16

        /** The signature's line height, of the canvas' shorter side (its picture written about [SIGN_LINE_PX] a line), and how long it takes to write (ms). */
        private const val SIGN_HEIGHT = 0.03f
        private const val SIGN_LINE_PX = 30f
        private const val SIGN_MS = 2500f
        private const val CATCH_STEP = 60.0

        /** How many kinds of ballpoint scribble there are. */
        private const val SCRIBBLES = 6

        /** How far round the hues turn for each fifth the chord heard is from the song's key, degrees. */
        private const val TURN_PER_FIFTH = 12f

        /** How much of the painting before shows through when painting over it. */
        const val VEIL_ALPHA = 0.55f

        private fun angleTo(from: Float, to: Float): Float {
            var d = (to - from) % 6.2831855f
            if (d > PI) d -= 6.2831855f
            if (d < -PI) d += 6.2831855f
            return d
        }

        private fun mixed(a: FloatArray, b: FloatArray, t: Float) = FloatArray(3) { a[it] + (b[it] - a[it]) * t }

        /** Smooth value noise, 0 … 1. */
        internal fun noise(x: Float, y: Float, seed: Int): Float {
            val x0 = floor(x).toInt()
            val y0 = floor(y).toInt()
            val fx = x - x0
            val fy = y - y0
            val sx = fx * fx * (3 - 2 * fx)
            val sy = fy * fy * (3 - 2 * fy)
            fun h(i: Int, j: Int): Float {
                var n = i * 374761393 + j * 668265263 + seed * 1442695041
                n = (n xor (n ushr 13)) * 1274126177
                return ((n xor (n ushr 16)) and 0xffff) / 65535f
            }
            val a = h(x0, y0) + (h(x0 + 1, y0) - h(x0, y0)) * sx
            val b = h(x0, y0 + 1) + (h(x0 + 1, y0 + 1) - h(x0, y0 + 1)) * sx
            return a + (b - a) * sy
        }
    }
}
