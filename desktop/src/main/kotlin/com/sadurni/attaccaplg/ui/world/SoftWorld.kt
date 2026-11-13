package com.sadurni.attaccaplg.ui.world

import com.sadurni.attacca.diorama.SongLook
import com.sadurni.attacca.diorama.SongWave
import com.sadurni.attacca.diorama.World
import com.sadurni.attacca.world.Cast
import com.sadurni.attacca.world.Draw
import com.sadurni.attacca.world.LandMesher
import com.sadurni.attacca.world.Mat4
import com.sadurni.attacca.world.Mesh
import com.sadurni.attacca.world.Props
import com.sadurni.attacca.world.WorldCamera
import com.sadurni.attacca.world.WorldLand
import com.sadurni.attacca.world.blob
import com.sadurni.attacca.world.tri
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** What Attacca World's page tells the renderer: the song, where it is, what is heard. */
class WorldInput {
    @Volatile var songId: String? = null
    @Volatile var look: SongLook? = null
    @Volatile var wave: SongWave? = null
    @Volatile var peakMs: Double = -1.0
    @Volatile var positionMs: Long = 0L
    /** The song [positionMs] belongs to (null: not known): a place of another song is not taken. */
    @Volatile var positionFor: String? = null
    @Volatile var positionAtNs: Long = 0L
    @Volatile var playing: Boolean = false
    @Volatile var heard: Boolean = false
    val levels = FloatArray(3)

    /** Whether the song's quiet and loud stretches may change the world. */
    @Volatile var sectioned: Boolean = true

    /** Where the traveller is on the picture (px of the picture; -1 out of sight), for a click on it. */
    @Volatile var travellerX = -1f
    @Volatile var travellerY = -1f

    /** The traveller was clicked: a move. */
    @Volatile var tapMove = false

    /** The next song's look (as O'rama shows it) and whether there is a next song, if known. */
    @Volatile var nextLook: SongLook? = null
    @Volatile var hasNext: Boolean = false

    /** Where the song starts on the land's own timeline (ms), as in O'rama: its places fall at the same moments. */
    @Volatile var originMs: Double = 0.0

    /** The pictures on the boards by the way: the cover, and the sign with the next song (ARGB, width × height). */
    @Volatile var cover: Picture? = null
    @Volatile var sign: Picture? = null

    /** The festive days' look (null: none), with the Three Kings; [vikram]: the Hindu new year's show going on. */
    @Volatile var festive: com.sadurni.attacca.festive.Festivity? = null
    @Volatile var kings: Boolean = false
    @Volatile var vikram: Boolean = false

    /** Where the sun (or moon) is on the picture (px; -1 out of sight) and how far a click on it may be. */
    @Volatile var sunX = -1f
    @Volatile var sunY = -1f
    @Volatile var sunR = 0f

    /** The song whose sun turned into a record (clicked five times quickly). */
    @Volatile var recordOf: String? = null
}

/** A picture's points (ARGB) and size. */
class Picture(val pixels: IntArray, val width: Int, val height: Int)

/**
 * Attacca World drawn without a graphics card's help: the same land, things, figures and camera
 * as on the phone (from the core), drawn into a picture of [width] × [height] by the processor,
 * a few threads at a time. Its light, haze, water and sky follow the phone's.
 */
class SoftWorld(private val input: WorldInput, val camera: WorldCamera) {

    private var land: WorldLand? = null
    private var landFor: Triple<String?, SongLook?, SongWave?>? = null
    private var cast: Cast? = null
    private var person = 0
    private var lastTz = 0f
    private var posMs = 0.0
    private var lastFrameNs = 0L

    // The beat.
    private var level = 0.5f
    private var slowLevel = 0f
    private var pulse = 0f
    private var lastBeatMs = 0L
    private var beatGrid = -1L
    private var wave = 0f
    private val ripples = FloatArray(16)
    private var nextRipple = 0
    private var rippleBase = 0L
    private var openness = 1f
    private var openTarget = 1f
    private var openAt = 0L
    private var extras: com.sadurni.attacca.world.Extras? = null
    private var oldCast: Cast? = null
    private var oldDz = 0f
    private var arrivedOn = false
    private var sightAt = 0L
    private var sight: FloatArray? = null
    private var dragonTurn = 0f
    private var dragonTan = 0.5f

    // Weather: clouds, rain or snow (or bubbles under the sea), lightning.
    private var cloudRigs: List<com.sadurni.attacca.world.Rig> = emptyList()
    private var cloudFor: SongLook? = null
    private val cloudPose = com.sadurni.attacca.world.Pose(1)
    private var flashUntil = 0L
    private var nextFlash = 0L
    private var flash = 0f
    private val drops = java.util.Random(7).let { r -> FloatArray(DROPS * 3) { r.nextFloat() } }

    // The sky's show at the peak, the moon going red, the light carried in the dark, under the water.
    private var peakKind = 0f
    private var peakNow = 0f
    private var moonRed = 0f
    private var nightNow = 0f
    private var sunColourNow = floatArrayOf(1f, 1f, 1f)
    private val sunSky = floatArrayOf(-0.2f, 0.27f, 1f)
    private val lamp = FloatArray(7)
    private var under = false
    private var beatNow = 0f

    /** A land chunk: its mesh and each point's light (the land's colours lit by the sun and the sky). */
    /**
     * A land chunk: each point once (the mesh repeats a point for every triangle it is in), its
     * light (the land's colours lit by the sun and the sky), and the triangles as indices.
     */
    private class Chunk(
        val step: Float,
        val x: FloatArray, val y: FloatArray, val z: FloatArray,
        val lit: FloatArray, val water: BooleanArray, val glow: BooleanArray,
        val index: IntArray,
        /** Each point's own colour and slope (for the light carried in the dark). */
        val base: FloatArray, val nrm: FloatArray,
        /** How high up a swaying plant each point is (0 … 1), or -1. */
        val sway: FloatArray,
    )

    private val chunks = ConcurrentHashMap<Int, Chunk>()

    /** The sky's colours and night as shown, easing towards the land's (see [eased]). */
    private val skyEase = FloatArray(16)
    /** The weather now (clouds, storm, rain, snow) and the look the clouds are made from, as the land shows them. */
    private var skyNow = FloatArray(4)
    private var skyLookNow: SongLook? = null
    private var skyEaseNs = 0L

    /** How far to ease this frame (about 1.2 s to most of the way). */
    private fun skyEaseStep(): Float {
        val now = System.nanoTime()
        val dt = if (skyEaseNs == 0L) -1.0 else (now - skyEaseNs) / 1e6
        skyEaseNs = now
        return if (dt < 0) 1f else (1 - kotlin.math.exp(-dt.coerceIn(0.0, 250.0) / 450.0)).toFloat()
    }

    private fun eased(slot: Int, v: Float, k: Float): Float {
        skyEase[slot] += (v - skyEase[slot]) * k
        return skyEase[slot]
    }
    /** The last land's pieces (moved [staleDz] rows), drawn where the new land's are not made yet. */
    private val stale = HashMap<Int, Chunk>()
    private var staleDz = 0f
    private val pending = ConcurrentHashMap.newKeySet<Int>()
    private val ready = ConcurrentLinkedQueue<Triple<WorldLand, Int, Chunk>>()
    private val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
    private val pool = Executors.newFixedThreadPool(threads) { r -> Thread(r, "attacca-world").apply { isDaemon = true } }
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "attacca-world-land").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }

    // The picture and its depth.
    private var width = 0
    private var height = 0
    var pixels = IntArray(0)
        private set
    private var depth = FloatArray(0)

    /** Triangles ready to fill: per vertex screen x, y, depth and colour r, g, b; then alpha. */
    private class Tris {
        var data = FloatArray(19 * 4096)
        var count = 0
        fun addFrom(other: Tris, k: Int) {
            if ((count + 1) * 19 > data.size) data = data.copyOf(data.size * 2)
            System.arraycopy(other.data, k * 19, data, count * 19, 19)
            count++
        }

        fun add(v: FloatArray, alpha: Float) {
            if ((count + 1) * 19 > data.size) data = data.copyOf(data.size * 2)
            System.arraycopy(v, 0, data, count * 19, 18)
            data[count * 19 + 18] = alpha
            count++
        }
    }

    // Light for this frame.
    private val sunDir = floatArrayOf(0.5f, 0.75f, -0.42f).also { normalize(it) }
    private var sunLight = floatArrayOf(1f, 0.9f, 0.7f)
    private var skyLight = floatArrayOf(0.6f, 0.7f, 0.9f)
    private var fog = floatArrayOf(0.8f, 0.85f, 0.9f)
    private var skyTop = floatArrayOf(0.4f, 0.6f, 0.9f)
    private var brightness = 1f
    private var fogNear = 45f
    private var fogFar = FAR
    private var eye = floatArrayOf(0f, 0f, 0f)
    private var vp = Mat4.identity()
    private var timeS = 0f
    private var recordShown = 0f
    private var placeGuardUntil = 0L
    private var christmas = false
    private var tzNow = 0f
    private var terrainSwell = true

    fun close() {
        pool.shutdownNow()
        worker.shutdownNow()
    }

    /** Draws the next frame into [pixels] ([w] × [h]). */
    fun frame(w: Int, h: Int) {
        if (w != width || h != height) {
            width = w
            height = h
            pixels = IntArray(w * h)
            depth = FloatArray(w * h)
        }
        val nowNs = System.nanoTime()
        val dtMs = if (lastFrameNs == 0L) 16L else ((nowNs - lastFrameNs) / 1_000_000L).coerceIn(1L, 100L)
        lastFrameNs = nowNs
        val nowMs = nowNs / 1_000_000L
        // The song as the page gave it, all of it at once (never half of one song and half of another).
        val songId: String?
        val feedLook: SongLook?
        val songWave: SongWave?
        val nextLook: SongLook?
        val origin: Double
        val sectioned: Boolean
        synchronized(input) {
            songId = input.songId; feedLook = input.look; songWave = input.wave
            nextLook = input.nextLook; origin = input.originMs; sectioned = input.sectioned
        }
        // Nothing until the song's look is known (no default world first).
        val look = feedLook ?: run {
            pixels.fill(0xFF000000.toInt())
            return
        }

        // A new land when the song, its look or its wave changes; another song goes on from the last one's land.
        val key = Triple(songId, feedLook, songWave)
        if (key != landFor || nextLook !== land?.next || origin != land?.originMs) {
            val old = land
            val sameSong = landFor?.first == songId
            // Only the next song's look changed: what is in sight now stays as it is.
            val onlyNext = key == landFor && origin == old?.originMs
            val before = when {
                old == null || landFor?.first == null -> null
                sameSong -> old.before
                else -> WorldLand.Before(old, lastTz)
            }
            landFor = key
            // Still aboard from the song before when the same vehicle goes on.
            val prevCast = cast
            val arrives = if (sameSong) arrivedOn else prevCast != null && before != null && prevCast.kind == look.traveller && !prevCast.leaves
            if (!sameSong) {
                oldCast = prevCast?.takeIf { before != null && it.leaves }
                oldDz = -(before?.atZ ?: 0f)
            }
            arrivedOn = arrives
            // A new song starts in what was seen arriving at the end of the last one.
            val cameIn = if (!sameSong && before != null) old?.lookAt(lastTz) else if (sameSong) old?.cameIn else null
            land = WorldLand(songWave, look, (songId ?: "").hashCode(), sectioned, before, nextLook, origin, arrives, cameIn)
            if (!sameSong) {
                posMs = (songWave?.startMs ?: 0L).toDouble()
                placeGuardUntil = nowMs + 2500
            }
            // The land in sight is kept until the new one's pieces are made (no gap, no flicker):
            // the old pieces, moved to the new song's rows.
            stale.clear()
            val keepTo = if (onlyNext && old != null) minOf(old.endZ, land!!.endZ) - WorldLand.ARRIVE - 8f else Float.NEGATIVE_INFINITY
            val dz = when {
                sameSong -> 0f
                before != null -> -before.atZ
                else -> Float.NaN
            }
            for ((k, c) in chunks) {
                if ((k + 1) * LandMesher.CHUNK < keepTo) continue
                if (!dz.isNaN()) stale[k] = if (dz == 0f) c else Chunk(c.step, c.x, c.y, FloatArray(c.z.size) { c.z[it] + dz }, c.lit, c.water, c.glow, c.index, c.base, c.nrm, c.sway)
            }
            staleDz = if (dz.isNaN()) 0f else dz
            chunks.keys.removeAll { (it + 1) * LandMesher.CHUNK >= keepTo }
            pending.clear()
            ready.clear()
            cast = Cast(land!!, look.traveller, person, (songId ?: "").hashCode(), arrives).also { it.carryDogsFrom(prevCast) }
            extras = com.sadurni.attacca.world.Extras(land!!, (songId ?: "").hashCode())
        }
        val land = land ?: return

        val target = input.positionMs + if (input.playing) (nowNs - input.positionAtNs) / 1_000_000.0 else 0.0
        // (Just after a new song came in the player may still give the last one's place: not taken.)
        val otherSong = input.positionFor.let { it != null && it != landFor?.first }
        if (otherSong || (nowMs < placeGuardUntil && target - posMs > 2500)) posMs += if (input.playing) dtMs.toDouble() else 0.0
        else if (abs(target - posMs) > 1500) posMs = target
        else posMs += (if (input.playing) dtMs.toDouble() else 0.0) + (target - posMs) * 0.08
        val tz = land.zAt(posMs)
        lastTz = tz
        tzNow = tz
        val tx = land.centerAt(tz)
        val heading = land.headingAt(tz)
        val ty = land.pathAt(tz)
        cast?.let { person = it.personAt(tz) }
        timeS = (nowMs % 100_000L) / 1000f
        ageRipples(nowMs)

        // The beat: a strong rise of the low sound sends a ring over the land (or the tempo does).
        val low = if (input.heard) input.levels[1] else 0f
        level += ((if (input.heard) input.levels[0] else 0.5f) - level) * 0.15f
        slowLevel += (low - slowLevel) * 0.04f
        val rise = (low - slowLevel).coerceAtLeast(0f)
        pulse = max(pulse * 0.86f, rise * 2.5f)
        val bpm = look.bpm.takeIf { it in 40f..220f } ?: 100f
        val beatMs = 60_000.0 / bpm
        if (input.playing) {
            if (input.heard) {
                if (rise > 0.06f + slowLevel * 0.25f && nowMs - lastBeatMs > beatMs * 0.7) {
                    ripple(land, tz, nowMs, (rise * 5f).coerceIn(0.35f, 1.3f))
                    lastBeatMs = nowMs
                }
            } else {
                val n = (posMs / beatMs).toLong()
                if (n != beatGrid) {
                    beatGrid = n
                    ripple(land, tz, nowMs, if (n % 4 == 0L) 0.9f else 0.4f)
                    pulse = max(pulse, if (n % 4 == 0L) 0.8f else 0.35f)
                }
            }
        }
        val intensity = land.intensityAt(tz)
        val peak = if (input.peakMs >= 0) {
            val d = abs(posMs - input.peakMs)
            when {
                d < 3000 -> 1f
                d < 8000 -> (1 - (d - 3000) / 5000).toFloat()
                else -> 0f
            }
        } else 0f
        if (nowMs - openAt > 500) {
            openAt = nowMs
            var r = 0f
            for (k in 0 until 12) {
                val a = k * 0.5236f
                r += (land.heightAt(tx + cos(a) * 12f, tz + sin(a) * 12f) - ty).coerceAtLeast(0f)
            }
            openTarget = (1f - r / 12f / 7f).coerceIn(0f, 1f)
        }
        openness += (openTarget - openness) * 0.02f
        val ex = extras
        if (nowMs - sightAt > 400) {
            sightAt = nowMs
            sight = ex?.sight(tz)
        }
        val tunnel = ex?.tunnelAt(tz) == true
        // Through the traveller's eyes: not drawn, the dogs ahead.
        cast?.throughEyes = camera.firstPerson
        // The Great Wall's dragon over the sky, for the eyes to follow (where it would be looking straight on).
        dragonTan = kotlin.math.tan(Math.toRadians(if (w > h) 55.0 else 68.0) / 2).toFloat()
        dragonTurn = com.sadurni.attacca.world.SkyShow.turnOf(com.sadurni.attacca.world.SkyShow.dragonX((nowMs % 100_000L) / 1000f, w.toFloat() / h), dragonTan)
        camera.update(
            dtMs, nowMs, tx, ty, tz, heading, intensity, peak, { x, z -> land.heightAt(x, z) }, openness,
            { x, z -> maxOf(Props.topNear(land, x, z), cast?.topNear(x, z) ?: Float.NEGATIVE_INFINITY, ex?.topNear(x, z) ?: Float.NEGATIVE_INFINITY) },
            sight, if (ex?.tunnelNear(tz) == true) 1f else 0f, { x, z -> minOf(ex?.ceilingAt(x, z) ?: Float.POSITIVE_INFINITY, if (cast?.underwater == true) WorldLand.WATER - 0.8f else Float.POSITIVE_INFINITY) },
            { z -> land.centerAt(z) }, cast?.eye, ex?.peakSight(),
            if (look.world == World.WALL && peak > 0.05f) dragonTurn else null, cast?.dogsNear, cast?.dogsNearCount ?: 0,
            ex?.boardSight(tz),
        )
        wave = max(wave * 0.97f, pulse.coerceAtMost(1.3f) * (0.4f + 0.6f * intensity))

        // Chunks in sight, the nearest made at once, the rest by the worker.
        val first = floor((tz - 40) / LandMesher.CHUNK).toInt()
        val last = floor((tz + FAR) / LandMesher.CHUNK).toInt()
        chunks.keys.filter { it < first - 1 || it > last + 1 }.forEach { chunks.remove(it) }
        while (true) {
            val (forLand, k, c) = ready.poll() ?: break
            if (forLand !== land) continue
            pending.remove(k)
            if (k in first - 1..last + 1) chunks[k] = c
        }
        val here = floor(tz / LandMesher.CHUNK).toInt()
        // Near chunks in full detail, far ones with half as many points each way (the haze hides it).
        fun stepFor(k: Int) = if (abs(k * LandMesher.CHUNK + 8 - tz) < 72f) 1f else 2f
        for (k in here - 1..here + 1) if (chunks[k]?.step != 1f) {
            chunks[k] = chunk(land, k, 1f)
            pending.remove(k)
        }
        for (k in (first..last).sortedBy { abs(it * LandMesher.CHUNK + 8 - tz) }) {
            val step = stepFor(k)
            if (chunks[k]?.step != step && k !in pending && pending.size < 4) {
                pending += k
                worker.execute { ready += Triple(land, k, chunk(land, k, step)) }
            }
        }

        // Colours: the stretch's sky, a warm haze towards the horizon, darker at night.
        val (skyA, skyB, skyT) = land.lookBlend(tz)
        fun blend(a: Int, b: Int) = rgb(a).let { x -> if (skyT <= 0f) x else rgb(b).let { y -> FloatArray(3) { x[it] + (y[it] - x[it]) * skyT } } }
        // (The sky's colours are eased over a moment: a song skipped halfway never turns night into day at once.)
        val easeK = skyEaseStep()
        val night = eased(9, skyA.night + (skyB.night - skyA.night) * skyT, easeK)
        // The weather and the sun as the land shows them here (blended, eased): never a jump as a song comes in.
        fun blendF(a: Float, b: Float) = a + (b - a) * skyT
        val skyOf = if (skyT < 0.5f) skyA else skyB
        val skySun = eased(10, blendF(skyA.sun, skyB.sun), easeK)
        val skyStorm = eased(11, blendF(skyA.storm, skyB.storm), easeK)
        val skyClouds = eased(12, blendF(skyA.clouds, skyB.clouds), easeK)
        val skyRain = eased(13, blendF(skyA.rain, skyB.rain), easeK)
        val skySnow = eased(14, blendF(skyA.snow, skyB.snow), easeK)
        skyNow = floatArrayOf(skyClouds, skyStorm, skyRain, skySnow)
        skyLookNow = skyOf
        // Under the water (the eye below its surface): the sea's own blues instead of the sky's.
        under = camera.eyeY < WorldLand.WATER - 0.2f
        val top = if (under) floatArrayOf(0.04f, 0.2f, 0.34f) else blend(skyA.palette.skyTop, skyB.palette.skyTop).let { c -> FloatArray(3) { eased(it, c[it], easeK) } }
        val bottom = if (under) floatArrayOf(0.1f, 0.42f, 0.55f) else blend(skyA.palette.skyBottom, skyB.palette.skyBottom).let { c -> FloatArray(3) { eased(3 + it, c[it], easeK) } }
        val sunColour = blend(skyA.palette.light, skyB.palette.light).let { c -> FloatArray(3) { eased(6 + it, c[it], easeK) } }
        val haze = if (under) floatArrayOf(0.1f, 0.4f, 0.52f) else FloatArray(3) { bottom[it] * 0.8f + sunColour[it] * 0.2f }
        // Storms: now and then a flash of lightning.
        if (skyStorm > 0.3f && nowMs > nextFlash && input.playing) {
            flashUntil = nowMs + 120
            nextFlash = nowMs + (2500 + Math.random() * 9000 / skyStorm).toLong()
        }
        flash = if (nowMs < flashUntil) 1f else 0f
        nightNow = night
        sunColourNow = sunColour
        sunSky[1] = 0.07f + 0.2f * skySun
        peakNow = peak
        moonRed = if (look.world == World.CASTLE) peak else 0f
        // The peak in the sky as on the phone (and O'rama): fireworks over a city at night,
        // shooting stars on other nights, else a rainbow; each new world its own.
        peakKind = when (look.world) {
            World.ICE -> 1f
            World.CASTLE -> -1f
            World.SPACE -> 3f
            World.DESERT -> 12f
            World.WALL -> 5f
            World.JUNGLE -> 6f
            World.AMAZON -> 7f
            World.CANYON -> 8f
            World.JAPAN -> 9f
            World.REEF -> 10f
            World.VOLCANO -> 11f
            World.NEON -> 0f
            World.CITY -> if (night > 0.5f) 0f else 4f
            else -> if (night > 0.5f) 3f else 4f
        }
        skyTop = top
        fog = haze
        brightness = ((1f - 0.5f * night) * (1f - 0.35f * skyStorm) * (0.9f + 0.18f * level + 0.08f * pulse.coerceAtMost(1f)) + flash * 0.8f) * (1f - 0.65f * (ex?.tunnelDepth(tz) ?: 0f))
        sunLight = FloatArray(3) { (sunColour[it] * 0.55f + WARM[it] * 0.45f) * (1.05f - 0.4f * night) }
        skyLight = FloatArray(3) { top[it] * 0.55f + 0.45f * COOL[it] }
        val underSea = camera.eyeY < WorldLand.WATER - 0.2f
        fogNear = if (underSea) 12f else 45f
        fogFar = if (underSea) FAR * 0.62f else FAR

        val view = Mat4.lookAt(camera.eyeX, camera.eyeY, camera.eyeZ, camera.atX, camera.atY, camera.atZ)
        val projection = Mat4.perspective(Math.toRadians(if (w > h) 55.0 else 68.0).toFloat(), w.toFloat() / h, NEAR, FAR + 60f)
        vp = Mat4.mul(projection, view)
        eye = floatArrayOf(camera.eyeX, camera.eyeY, camera.eyeZ)

        // Where the traveller is on the picture, for a click on it; a click asked for a move.
        project(tx, ty + 1.2f, tz)?.let { (sx, sy) -> input.travellerX = sx; input.travellerY = sy } ?: run { input.travellerX = -1f }
        if (input.tapMove) {
            input.tapMove = false
            cast?.tap(nowMs, tz, Math.random().toFloat())
        }
        // The sun on the picture, for clicks on it (five quick ones turn it into a record).
        project(camera.eyeX - 0.2f * 500, camera.eyeY + sunSky[1] * 500, camera.eyeZ + 500f)?.let { (sx, sy) ->
            input.sunX = sx; input.sunY = sy; input.sunR = 0.12f * h
        } ?: run { input.sunX = -1f }
        recordShown += ((if (input.recordOf != null && input.recordOf == input.songId) 1f else 0f) - recordShown) * 0.12f
        christmas = input.festive == com.sadurni.attacca.festive.Festivity.CHRISTMAS || input.festive == com.sadurni.attacca.festive.Festivity.ORTHODOX_CHRISTMAS

        val t0 = System.nanoTime()
        skyBands(top, haze, sunColour, night, peak, look)
        val t1 = System.nanoTime()

        // The figures (and what lives and stands about), before the land: the light they carry lights it.
        val draws = ArrayList<Draw>()
        cast?.santa = christmas
        cast?.frame(tz, posMs / beatMs, input.playing, nowMs, draws, ex?.darkAt(tz, night) == true, tunnel)
        oldCast?.let { if (land.before != null) it.parked(oldDz, draws) else oldCast = null }
        // On the beat's wave, not sunk in it: the figures (and the traveller's eyes) rise with the land.
        com.sadurni.attacca.world.Swell.liftFigures(land, draws, 0, ripples, pulse, tz, cast?.eye)
        ex?.let { it.festivity = input.festive; it.kings = input.kings; it.vikram = input.vikram }
        ex?.frame(tz, ty, nowMs, input.playing, peak, if (input.peakMs >= 0) land.zAt(input.peakMs) else null, look.palette.accent, input.hasNext, draws)
        cast?.lamp?.copyInto(lamp) ?: lamp.fill(0f)
        beatNow = ((posMs / beatMs) % 64.0).toFloat()
        weather(skyLookNow ?: look, tz, nowMs, draws)

        // The land, chunk by chunk on the threads (where the new land has no piece yet, the old one's).
        val list = ArrayList(chunks.values)
        if (stale.isNotEmpty()) {
            val shown = HashSet<Int>()
            var missing = false
            for (k in floor((tz - 40) / LandMesher.CHUNK).toInt()..floor((tz + FAR) / LandMesher.CHUNK).toInt()) {
                if (chunks.containsKey(k)) continue
                missing = true
                val a = floor((k * LandMesher.CHUNK - staleDz) / LandMesher.CHUNK).toInt()
                val b = floor(((k + 1) * LandMesher.CHUNK - staleDz) / LandMesher.CHUNK).toInt()
                for (j in a..b) if (j in stale) shown += j
            }
            if (!missing) stale.clear() else for (j in shown) list += stale[j]!!
        }
        terrainSwell = true
        val made = pool.invokeAll(list.map { c -> Callable { Tris().also { landTris(c, it, nowMs) } } }).map { it.get() }
        val t2 = System.nanoTime()
        val parts = pool.invokeAll(draws.map { d -> Callable { Pair(Tris(), Tris()).also { (o, g) -> figureTris(d, o, g) } } }).map { it.get() }
        val figures = Tris()
        val glass = Tris()
        for ((o, g) in parts) {
            for (k in 0 until o.count) figures.addFrom(o, k)
            for (k in 0 until g.count) glass.addFrom(g, k)
        }

        // The boards' pictures: the cover by the way, the next song's sign.
        val boards = ArrayList<Board>()
        ex?.billboards?.forEach { bb ->
            val pic = if (bb.kind == com.sadurni.attacca.world.Billboard.NEXT) input.sign else input.cover
            if (pic != null) board(bb.corners, pic)?.let { boards += it }
        }
        val t3 = System.nanoTime()
        // Fill: each thread its band of rows, the see-through glass last.
        val bands = threads
        pool.invokeAll((0 until bands).map { b ->
            Callable {
                val y0 = h * b / bands
                val y1 = h * (b + 1) / bands
                for (t in made) fill(t, y0, y1)
                fill(figures, y0, y1)
                for (q in boards) fillPicture(q, y0, y1)
                fill(glass, y0, y1)
            }
        }).forEach { it.get() }
        val t4 = System.nanoTime()
        timings[0] += t1 - t0; timings[1] += t2 - t1; timings[2] += t3 - t2; timings[3] += t4 - t3
        triangles = made.sumOf { it.count } + figures.count
    }

    /** Time spent (ns, added up) on the sky, the land, the figures and the filling; for tuning. */
    val timings = LongArray(4)
    var triangles = 0

    // ---- The sky ----

    private fun skyBands(top: FloatArray, haze: FloatArray, sun: FloatArray, night: Float, peak: Float, look: SongLook) {
        val h = height
        pool.invokeAll((0 until threads).map { b -> Callable { sky(top, haze, sun, night, peak, look, h * b / threads, h * (b + 1) / threads) } }).forEach { it.get() }
    }

    private fun sky(top: FloatArray, haze: FloatArray, sun: FloatArray, night: Float, peak: Float, look: SongLook, y0: Int, y1: Int) {
        val w = width
        val h = height
        // The horizon straight ahead, and the sun (or the moon) as drawn: low, near straight ahead.
        val fx = camera.atX - camera.eyeX
        val fz = camera.atZ - camera.eyeZ
        val fl = sqrt(fx * fx + fz * fz).coerceAtLeast(0.001f)
        val horizon = clipY(camera.eyeX + fx / fl * 1000f, camera.eyeY, camera.eyeZ + fz / fl * 1000f) ?: -1f
        val sunAt = clip(camera.eyeX - 0.2f * 500, camera.eyeY + sunSky[1] * 500, camera.eyeZ + 500f)
        val sunStrength = (0.6f + 0.4f * (1 - night)) * (1f + peak * 0.6f) * (0.85f + 0.3f * level + 0.25f * pulse.coerceAtMost(1f))
        val aspect = w.toFloat() / h
        val yaw = kotlin.math.atan2(fx, fz)
        // The camera's own axes, for where each point of the sky looks in the world.
        val fwd = floatArrayOf(camera.atX - camera.eyeX, camera.atY - camera.eyeY, camera.atZ - camera.eyeZ).also { normalize(it) }
        // As the view is made (Mat4.lookAt): right = forward × up, up = right × forward.
        val right = floatArrayOf(-fwd[2], 0f, fwd[0]).also { normalize(it) }
        val upv = floatArrayOf(right[1] * fwd[2] - right[2] * fwd[1], right[2] * fwd[0] - right[0] * fwd[2], right[0] * fwd[1] - right[1] * fwd[0])
        val tanY = Math.tan(Math.toRadians(if (w > h) 27.5 else 34.0)).toFloat()
        val show = peakNow > 0.01f && !under
        // The sun and the moon beat with the low hits.
        val moonSize = (0.06f + 0.08f * moonRed) * (1f + 0.22f * pulse.coerceAtMost(1f))
        val sunC = floatArrayOf(sun[0] + (0.9f - sun[0]) * moonRed, sun[1] + (0.2f - sun[1]) * moonRed, sun[2] + (0.12f - sun[2]) * moonRed)
        val time = timeS
        for (y in y0 until y1) {
            val ny = 1f - 2f * (y + 0.5f) / h
            val t = ((ny - horizon) / 1.4f).coerceIn(0f, 1f)
            val k = Math.pow(t.toDouble(), 0.7).toFloat()
            val r0 = haze[0] + (top[0] - haze[0]) * k
            val g0 = haze[1] + (top[1] - haze[1]) * k
            val b0 = haze[2] + (top[2] - haze[2]) * k
            val row = y * w
            for (x in 0 until w) {
                var r = r0
                var g = g0
                var b = b0
                val nx = 2f * (x + 0.5f) / w - 1f
                if (show && t > 0.02f) {
                    val c = peakShow(nx, ny, horizon, aspect, fwd, right, upv, tanY, time, r, g, b)
                    r = c[0]; g = c[1]; b = c[2]
                }
                if (input.kings && !under && t > 0.0f) {
                    val c = kingsStar(nx, ny, aspect, fwd, right, upv, tanY, time, r, g, b)
                    r = c[0]; g = c[1]; b = c[2]
                }
                if (sunAt != null && !under) {
                    val dx = (nx - sunAt[0]) * aspect
                    val dy = ny - sunAt[1]
                    val d2 = dx * dx + dy * dy
                    // Its glow fades out within a few of its sizes: further, nothing to add. At the
                    // castles' peak the moon grows great and red, where it is.
                    if (d2 < 9f) {
                        val d = sqrt(d2)
                        val disc = 1f - smooth(moonSize, moonSize + 0.015f, d)
                        val glow = (exp(-d * 4f) * 0.55f + exp(-d * 1.2f) * 0.18f) * sunStrength * (1f + moonRed)
                        r += sunC[0] * glow; g += sunC[1] * glow; b += sunC[2] * glow
                        val k2 = (disc * sunStrength * (1f - recordShown)).coerceIn(0f, 1f)
                        val bright = 1f + 0.4f * (1f - moonRed)
                        r += (sunC[0] * bright - r) * k2; g += (sunC[1] * bright - g) * k2; b += (sunC[2] * bright - b) * k2
                        if (recordShown > 0.01f) {
                            val c = record(dx, dy, d, time, sunC, r, g, b)
                            r = c[0]; g = c[1]; b = c[2]
                        }
                    }
                }
                if (flash > 0f) { r += 0.75f * flash; g += 0.78f * flash; b += 0.95f * flash }
                // Stars at night, fixed to the sky.
                if (night > 0.05f && t > 0.05f) {
                    val sx = ((nx * aspect * 0.5f + yaw * 1.6f) * 60f).toInt()
                    val sy = ((ny - horizon) * 60f).toInt()
                    val hsh = ((sx * 73856093) xor (sy * 19349663)) and 0xFFFF
                    if (hsh < 70 && ((x + y) and 1) == 0) {
                        val star = night * t
                        r += star; g += star; b += star
                    }
                }
                pixels[row + x] = pack(r, g, b)
                depth[row + x] = Float.POSITIVE_INFINITY
            }
        }
    }

    // ---- The land ----

    private fun chunk(land: WorldLand, k: Int, step: Float): Chunk {
        val mesh = LandMesher.chunk(land, k, step, if (step > 1f) 1.3f else 0f)
        val n = mesh.vertices
        val d = mesh.data
        // Each point once: the same place with the same colour and slope is the same point.
        val ids = HashMap<Key, Int>(n / 4)
        val index = IntArray(n)
        val first = IntArray(n)
        var count = 0
        for (i in 0 until n) {
            val o = i * Mesh.STRIDE
            val key = Key(d[o], d[o + 1], d[o + 2], d[o + 3], d[o + 7])
            val id = ids.getOrPut(key) { first[count] = i; count++ }
            index[i] = id
        }
        val x = FloatArray(count)
        val y = FloatArray(count)
        val z = FloatArray(count)
        val lit = FloatArray(count * 3)
        val water = BooleanArray(count)
        val glow = BooleanArray(count)
        val base = FloatArray(count * 3)
        val nrm = FloatArray(count * 3)
        val sway = FloatArray(count)
        // Lit with the light of the song's look where the chunk is (the frame's brightness comes on top).
        val look = land.lookAt(k * LandMesher.CHUNK + 8f)
        val pal = look.palette
        val top = rgb(pal.skyTop)
        val sunC = rgb(pal.light)
        val night = look.night
        val sunL = FloatArray(3) { (sunC[it] * 0.55f + WARM[it] * 0.45f) * (1.05f - 0.4f * night) }
        val skyL = FloatArray(3) { top[it] * 0.55f + 0.45f * COOL[it] }
        for (p in 0 until count) {
            val o = first[p] * Mesh.STRIDE
            x[p] = d[o]; y[p] = d[o + 1]; z[p] = d[o + 2]
            val cr = d[o + 3]; val cg = d[o + 4]; val cb = d[o + 5]
            var nxv = d[o + 6]; var nyv = d[o + 7]; var nzv = d[o + 8]
            // A plant's point: marked by its normal's length (see Props.swayOf), lit as any.
            val nlen = sqrt(nxv * nxv + nyv * nyv + nzv * nzv)
            sway[p] = Props.swayOf(nlen)
            if (sway[p] >= 0f) { nxv /= nlen; nyv /= nlen; nzv /= nlen }
            base[p * 3] = cr; base[p * 3 + 1] = cg; base[p * 3 + 2] = cb
            nrm[p * 3] = nxv; nrm[p * 3 + 1] = nyv; nrm[p * 3 + 2] = nzv
            if (cr > 1.5f || cg > 1.5f || cb > 1.5f) {
                glow[p] = true
                lit[p * 3] = cr - 2f; lit[p * 3 + 1] = cg - 2f; lit[p * 3 + 2] = cb - 2f
                continue
            }
            if (nyv > 1.5f) {
                water[p] = true
                // Water: deep colour with the sky mirrored in it.
                for (c in 0..2) lit[p * 3 + c] = (d[o + 3 + c] * (skyL[c] * 0.5f + sunL[c] * 0.35f)) * 0.55f + (top[c] * 0.9f + sunL[c] * 0.1f) * 0.45f
                continue
            }
            val sun = max(nxv * sunDir[0] + nyv * sunDir[1] + nzv * sunDir[2], 0f)
            val up = nyv * 0.5f + 0.5f
            var r = cr * (skyL[0] * (0.32f + 0.3f * up) + sunL[0] * sun)
            var g = cg * (skyL[1] * (0.32f + 0.3f * up) + sunL[1] * sun)
            var b = cb * (skyL[2] * (0.32f + 0.3f * up) + sunL[2] * sun)
            val grey = r * 0.3f + g * 0.59f + b * 0.11f
            r = grey + (r - grey) * 1.18f; g = grey + (g - grey) * 1.18f; b = grey + (b - grey) * 1.18f
            lit[p * 3] = r; lit[p * 3 + 1] = g; lit[p * 3 + 2] = b
        }
        return Chunk(step, x, y, z, lit, water, glow, index, base, nrm, sway)
    }

    private data class Key(val x: Float, val y: Float, val z: Float, val r: Float, val ny: Float)

    private fun landTris(c: Chunk, out: Tris, nowMs: Long) {
        // Every point once onto the picture (7 floats: clip x, y, z, w, then r, g, b).
        val n = c.x.size
        val pts = FloatArray(n * 7)
        val one = FloatArray(7)
        val t = timeS
        val beat = beatNow
        for (p in 0 until n) {
            var x = c.x[p]
            var y = c.y[p]
            var z = c.z[p]
            val sw = c.sway[p]
            if (sw >= 0f) {
                // Plants sway with the music, the higher up the more.
                val ph = x * 0.31f + z * 0.17f
                val amp = sw * sw * (0.07f + 0.32f * pulse.coerceAtMost(1.5f))
                x += sin(beat * 3.1416f + ph) * amp
                z += cos(beat * 3.1416f * 0.5f + ph) * amp * 0.6f
            }
            var r = c.lit[p * 3]
            var g = c.lit[p * 3 + 1]
            var b = c.lit[p * 3 + 2]
            if (c.water[p]) {
                y += sin(x * 0.35f + t * 1.3f) * 0.09f + sin(z * 0.5f - t * 1.7f) * 0.07f +
                    sin(z * 0.12f - t * 2.2f + x * 0.05f) * (0.12f + wave * 0.9f)
                if (eye[1] < WorldLand.WATER) {
                    // Seen from under the water: its surface bright with the light above, rippling.
                    val ripple = 0.5f + 0.5f * sin(x * 0.9f + t * 1.4f) * sin(z * 0.7f - t * 1.1f)
                    val k = (0.35f + 0.4f * ripple)
                    val dim = 1f - 0.6f * nightNow
                    r = (0.18f + 0.44f * k) * dim; g = (0.5f + 0.36f * k) * dim; b = (0.62f + 0.3f * k) * dim
                } else {
                    // The sun or the moon mirrored: a path of light towards it, glittering; at night a little of the sky's light.
                    var vx = eye[0] - x; var vy = eye[1] - y; var vz = eye[2] - z
                    val vl = sqrt(vx * vx + vy * vy + vz * vz).coerceAtLeast(1e-4f)
                    vx /= vl; vy /= vl; vz /= vl
                    var wx = (sin(x * 1.7f + t * 1.3f) * 0.05f + sin(z * 0.9f - t) * 0.04f) * 2.2f
                    var wz = (cos(z * 1.9f + t * 1.1f) * 0.05f + sin(x * 0.7f + t * 0.8f) * 0.04f) * 2.2f
                    var wy = 1f
                    val wl = sqrt(wx * wx + wy * wy + wz * wz)
                    wx /= wl; wy /= wl; wz /= wl
                    val dvn = vx * wx + vy * wy + vz * wz
                    val rx = 2 * dvn * wx - vx; val ry = 2 * dvn * wy - vy; val rz = 2 * dvn * wz - vz
                    val sl = sqrt(sunSky[0] * sunSky[0] + sunSky[1] * sunSky[1] + sunSky[2] * sunSky[2])
                    val m = max((rx * sunSky[0] + ry * sunSky[1] + rz * sunSky[2]) / sl, 0f)
                    val m40 = m.toDouble().let { Math.pow(it, 40.0) }.toFloat()
                    val path = (Math.pow(m.toDouble(), 260.0).toFloat() * 3f + m40 * 0.35f) * (1f + 1.6f * nightNow)
                    val fres = (1f - max(dvn, 0f)).let { it * it * it }
                    r += sunColourNow[0] * path + skyTop[0] * 0.18f * nightNow * (0.4f + fres)
                    g += sunColourNow[1] * path + skyTop[1] * 0.18f * nightNow * (0.4f + fres)
                    b += sunColourNow[2] * path + skyTop[2] * 0.18f * nightNow * (0.4f + fres)
                }
            } else {
                // The beat's rings swell the land as they run out over it.
                val s = swell(x, z)
                y += s * 0.55f
                // The beat lifts the land near the traveller, the higher the more.
                val dz = (z - tzNow) / 26f
                if (y > 3f && dz * dz < 9f) y += y * pulse.coerceAtMost(1.5f) * 0.07f * exp(-dz * dz)
                if (!c.glow[p] && s > 0.01f) {
                    val boost = s.coerceIn(0f, 1f) * 0.22f
                    r += sunLight[0] * boost; g += sunLight[1] * boost; b += sunLight[2] * boost
                }
                if (!c.glow[p] && lamp[6] > 0f) {
                    val add = lampAt(x, y, z, c.nrm[p * 3], c.nrm[p * 3 + 1], c.nrm[p * 3 + 2])
                    if (add > 0f) {
                        r += c.base[p * 3] * add * lampTint(0); g += c.base[p * 3 + 1] * add * lampTint(1); b += c.base[p * 3 + 2] * add * lampTint(2)
                    }
                }
            }
            vertex(x, y, z, r, g, b, c.glow[p], one, 0)
            System.arraycopy(one, 0, pts, p * 7, 7)
        }
        val v = FloatArray(3 * 7)
        val tri = FloatArray(18)
        val idx = c.index
        var i = 0
        while (i + 2 < idx.size) {
            System.arraycopy(pts, idx[i] * 7, v, 0, 7)
            System.arraycopy(pts, idx[i + 1] * 7, v, 7, 7)
            System.arraycopy(pts, idx[i + 2] * 7, v, 14, 7)
            emit(v, tri, out, 1f)
            i += 3
        }
    }

    private fun swell(x: Float, z: Float): Float {
        var s = 0f
        for (k in 0 until 4) {
            val strength = ripples[k * 4 + 3]
            if (strength <= 0f) continue
            val t = ripples[k * 4 + 2]
            if (t >= 3f) continue
            val dx = x - ripples[k * 4]
            val dz = z - ripples[k * 4 + 1]
            val front = sqrt(dx * dx + dz * dz) - t * 22f
            if (abs(front) > 8f) continue
            s += strength * exp(-front * front / 6f) * exp(-t * 1.4f)
        }
        return s
    }

    private fun ripple(land: WorldLand, z: Float, nowMs: Long, strength: Float) {
        if (rippleBase == 0L) rippleBase = nowMs
        val k = nextRipple
        nextRipple = (nextRipple + 1) % 4
        ripples[k * 4] = land.centerAt(z)
        ripples[k * 4 + 1] = z
        ripples[k * 4 + 2] = 0f
        ripples[k * 4 + 3] = strength
        rippleAt[k] = nowMs
        cast?.beat(strength, land.intensityAt(z), nowMs, z, Math.random().toFloat())
    }

    private val rippleAt = LongArray(4)

    private fun ageRipples(nowMs: Long) {
        for (k in 0 until 4) ripples[k * 4 + 2] = (nowMs - rippleAt[k]) / 1000f
    }

    // ---- The figures ----

    private fun figureTris(d: Draw, out: Tris, glass: Tris) {
        val rig = d.rig
        val m = Array(rig.bones.size) { FloatArray(16) }
        rig.matrices(d.base, d.pose, m)
        val v = FloatArray(3 * 7)
        val tri = FloatArray(18)
        for ((j, bone) in rig.bones.withIndex()) {
            val mat = m[j]
            val data = bone.mesh.data
            val n = bone.mesh.vertices
            val alpha = bone.alpha * d.alpha
            val target = if (alpha < 1f) glass else out
            var i = 0
            while (i + 2 < n) {
                for (q in 0..2) {
                    val o = (i + q) * Mesh.STRIDE
                    val px = data[o]; val py = data[o + 1]; val pz = data[o + 2]
                    val x = mat[0] * px + mat[4] * py + mat[8] * pz + mat[12]
                    val y = mat[1] * px + mat[5] * py + mat[9] * pz + mat[13]
                    val z = mat[2] * px + mat[6] * py + mat[10] * pz + mat[14]
                    val cr = data[o + 3]; val cg = data[o + 4]; val cb = data[o + 5]
                    val glow = cr > 1.5f || cg > 1.5f || cb > 1.5f
                    var r: Float
                    var g: Float
                    var b: Float
                    if (glow) {
                        r = cr - 2f; g = cg - 2f; b = cb - 2f
                    } else {
                        val ax = data[o + 6]; val ay = data[o + 7]; val az = data[o + 8]
                        var nx = mat[0] * ax + mat[4] * ay + mat[8] * az
                        var ny = mat[1] * ax + mat[5] * ay + mat[9] * az
                        var nz = mat[2] * ax + mat[6] * ay + mat[10] * az
                        val l = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f)
                        nx /= l; ny /= l; nz /= l
                        val sun = max(nx * sunDir[0] + ny * sunDir[1] + nz * sunDir[2], 0f)
                        val up = ny * 0.5f + 0.5f
                        r = cr * (skyLight[0] * (0.32f + 0.3f * up) + sunLight[0] * sun)
                        g = cg * (skyLight[1] * (0.32f + 0.3f * up) + sunLight[1] * sun)
                        b = cb * (skyLight[2] * (0.32f + 0.3f * up) + sunLight[2] * sun)
                        if (lamp[6] > 0f) {
                            val add = lampAt(x, y, z, nx, ny, nz)
                            if (add > 0f) { r += cr * add * lampTint(0); g += cg * add * lampTint(1); b += cb * add * lampTint(2) }
                        }
                        if (bone.alpha < 1f) {
                            // A glass's shine (not on smoke).
                            val ex = eye[0] - x; val ey = eye[1] - y; val ez = eye[2] - z
                            val el = sqrt(ex * ex + ey * ey + ez * ez).coerceAtLeast(1e-6f)
                            val f = 1f - abs((nx * ex + ny * ey + nz * ez) / el)
                            r += 0.6f * f * f; g += 0.6f * f * f; b += 0.6f * f * f
                        }
                    }
                    vertex(x, y, z, r, g, b, glow, v, q)
                }
                emit(v, tri, target, alpha)
                i += 3
            }
        }
    }

    // ---- The light carried in the dark ----

    /** How much the traveller's light falls on a point at (x, y, z) facing (nx, ny, nz): a beam, or a torch's glow all round. */
    private fun lampAt(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float): Float {
        var lx = lamp[0] - x; var ly = lamp[1] - y; var lz = lamp[2] - z
        val d2 = lx * lx + ly * ly + lz * lz
        if (d2 > 900f) return 0f
        val d = sqrt(d2).coerceAtLeast(0.001f)
        lx /= d; ly /= d; lz /= d
        val facing = 0.35f + 0.65f * max(nx * lx + ny * ly + nz * lz, 0f)
        val torch = lamp[6] > 1.5f
        val reach = if (torch) (0.85f + 0.1f * sin(timeS * 13f) + 0.05f * sin(timeS * 23f)) / (1f + d2 * 0.09f) else 1f / (1f + d2 * 0.012f)
        val cone = if (torch) 1f else smooth(0.78f, 0.93f, -(lx * lamp[3] + ly * lamp[4] + lz * lamp[5]))
        return facing * reach * cone * 1.6f
    }

    private fun lampTint(c: Int): Float = if (lamp[6] > 1.5f) TORCH[c] else BEAM[c]

    // ---- Weather ----

    /** Clouds drifting high, rain or snow falling about the camera (bubbles rising under the sea), as on the phone. */
    private fun weather(look: SongLook, tz: Float, nowMs: Long, draws: MutableList<Draw>) {
        if (cloudFor !== look) {
            cloudFor = look
            cloudRigs = if (look.clouds > 0.05f && look.world != World.SPACE) List(3) { k ->
                com.sadurni.attacca.world.Rig(listOf(com.sadurni.attacca.world.Bone(cloudMesh(k, look), -1, 0f, 0f, 0f)))
            } else emptyList()
        }
        if (cloudRigs.isNotEmpty()) {
            val n = (skyNow[0] * 14).toInt().coerceAtMost(14)
            for (c in 0 until n) {
                val drift = (nowMs / 1000f * 1.2f + c * 37f) % 300f
                val cz = (tz - 40f) + ((c * 53) % 260)
                val cx = -150f + drift + (c * 71 % 60)
                val cy = 46f + (c * 13 % 18) - skyNow[1] * 12f
                draws += Draw(cloudRigs[c % 3], Mat4.model(cx, cy, cz, c * 1.3f, s = 1f + (c % 3) * 0.4f), cloudPose)
            }
        }
        val rain = skyNow[2].coerceAtLeast(skyNow[1])
        // Christmas days: snow falling (but in space).
        val snow = if (christmas) max(skyNow[3], 0.45f) else skyNow[3]
        val bubbles = under
        if (!bubbles && !((rain > 0.1f || snow > 0.1f) && look.world != World.SPACE && look.world != World.REEF)) return
        val isSnow = snow >= rain || bubbles
        // Fewer than on the phone: each is drawn by the processor.
        val count = if (bubbles) 160 else (DROPS * max(rain, snow)).toInt().coerceIn(120, DROPS)
        val time = (nowMs % 100_000L) / 1000f
        val view = Mat4.lookAt(camera.eyeX, camera.eyeY, camera.eyeZ, camera.atX, camera.atY, camera.atZ)
        val rx = view[0]; val ry = view[4]; val rz = view[8]
        val ux = view[1]; val uy = view[5]; val uz = view[9]
        val w = if (bubbles) 0.07f else if (isSnow) 0.09f else 0.025f
        val hh = if (isSnow) 0.09f else 0.55f
        val col = (if (bubbles) 0xFFBFF4FF.toInt() else if (isSnow) 0xFFFFFFFF.toInt() else 0xFFA8BCEA.toInt()).let { Props.glow(it) }
        val out = com.sadurni.attacca.world.FloatList(count * 6 * 9 + 16)
        for (d in 0 until count) {
            val bx = drops[d * 3]; val by = drops[d * 3 + 1]; val bz = drops[d * 3 + 2]
            val fall = if (bubbles) -1.6f else if (isSnow) 2.2f else 26f
            var px = bx * 60f + if (isSnow) sin(time * 0.7f + bz * 20f) * 1.2f else 0f
            var py = by * 30f - time * fall * (0.8f + 0.4f * bx)
            var pz = bz * 60f
            px = camera.eyeX + wrap(px - camera.eyeX + 30f, 60f) - 30f
            py = camera.eyeY + wrap(py - camera.eyeY + 15f, 30f) - 15f
            pz = camera.eyeZ + wrap(pz - camera.eyeZ + 30f, 60f) - 30f
            val vx = if (isSnow) ux * hh else 0f
            val vy = if (isSnow) uy * hh else hh
            val vz = if (isSnow) uz * hh else 0f
            val ax = rx * w; val ay = ry * w; val az = rz * w
            out.tri(px - ax - vx, py - ay - vy, pz - az - vz, px + ax - vx, py + ay - vy, pz + az - vz, px + ax + vx, py + ay + vy, pz + az + vz, col)
            out.tri(px - ax - vx, py - ay - vy, pz - az - vz, px + ax + vx, py + ay + vy, pz + az + vz, px - ax + vx, py - ay + vy, pz - az + vz, col)
        }
        val rig = com.sadurni.attacca.world.Rig(listOf(com.sadurni.attacca.world.Bone(out.mesh(), -1, 0f, 0f, 0f)))
        draws += Draw(rig, Mat4.identity(), com.sadurni.attacca.world.Pose(1))
    }

    private fun wrap(v: Float, m: Float): Float = v - m * floor(v / m)

    /** A cloud: soft white heaps, a little grey underneath when the sky is stormy. */
    private fun cloudMesh(k: Int, look: SongLook): Mesh {
        val out = com.sadurni.attacca.world.FloatList(8192)
        val white = if (look.storm > 0.3f) 0xFFA8A8B4.toInt() else if (look.night > 0.5f) 0xFF8A8CA8.toInt() else 0xFFF8F8FC.toInt()
        val r = java.util.Random(k * 31L + 5)
        for (i in 0 until 6) {
            val x = (r.nextFloat() - 0.5f) * 16f
            val z = (r.nextFloat() - 0.5f) * 8f
            val w = 5f + r.nextFloat() * 5f
            out.blob(x - w, -2f, z - w * 0.6f, x + w, 2.5f + r.nextFloat() * 2f, z + w * 0.6f, white, 10, 6)
        }
        return out.mesh()
    }

    // ---- The sky's show at the song's peak (as the phone's sky) ----

    private fun fract(v: Float) = v - floor(v)
    private fun hash1(n: Float) = fract(sin(n * 91.7f) * 43758.547f)

    /**
     * The colour (r, g, b) of the sky point (nx, ny) with the peak's show on it. Placed on the sky
     * in sight (q), except the rainbow, which stays where it is in the world (by [fwd] etc.).
     */
    private fun peakShow(nx: Float, ny: Float, horizon: Float, aspect: Float, fwd: FloatArray, right: FloatArray, up: FloatArray, tanY: Float, time: Float, r0: Float, g0: Float, b0: Float): FloatArray {
        var r = r0; var g = g0; var b = b0
        val p = peakNow
        val kind = peakKind
        val v = ((ny - horizon) / max(1f - horizon, 0.2f)).coerceIn(0f, 1f)
        val qx = nx * aspect
        val qy = v
        fun mix(cr: Float, cg: Float, cb: Float, a: Float) { r += (cr - r) * a; g += (cg - g) * a; b += (cb - b) * a }
        fun add(cr: Float, cg: Float, cb: Float, a: Float) { r += cr * a; g += cg * a; b += cb * a }
        when {
            kind > 3.5f && kind < 4.5f -> {
                // A rainbow standing over the land ahead, fixed in the sky.
                val dx = fwd[0] + (right[0] * nx * aspect + up[0] * ny) * tanY
                val dy = fwd[1] + (right[1] * nx * aspect + up[1] * ny) * tanY
                val dz = fwd[2] + (right[2] * nx * aspect + up[2] * ny) * tanY
                val l = sqrt(dx * dx + dy * dy + dz * dz)
                val cl = sqrt(0.25f * 0.25f + 0.2f * 0.2f + 1f)
                val a = kotlin.math.acos(((dx * 0.25f - dy * 0.2f + dz) / (l * cl)).coerceIn(-1f, 1f))
                val band = (a - 0.6f) / 0.075f
                val up2 = dy / l
                if (band > 0f && band < 1f && up2 > -0.03f) {
                    val h = band * 0.8f
                    val cr = 0.5f + 0.5f * cos(6.2831f * h); val cg = 0.5f + 0.5f * cos(6.2831f * (h + 0.33f)); val cb = 0.5f + 0.5f * cos(6.2831f * (h + 0.67f))
                    mix(cr, cg, cb, 0.55f * p * smooth(-0.03f, 0.04f, up2) * sin(band * 3.1416f))
                }
                if (a < 0.6f) add(0.08f, 0.08f, 0.08f, p * smooth(0f, 0.2f, 0.6f - a))
            }
            kind < -0.5f -> {}
            kind < 0.5f -> for (k in 0 until 4) {
                // Fireworks: bursts of sparks, a new one every half second.
                val slot = floor(time * 2f) - k
                val age = time * 2f - slot
                val ax = (hash1(slot) - 0.5f) * 1.4f * aspect
                val ay = 0.3f + hash1(slot + 3f) * 0.45f
                val dx = qx - ax; val dy = qy - ay
                val rr = sqrt(dx * dx + dy * dy)
                if (rr > 0.36f) continue
                val ring = 1f - smooth(0f, 0.03f, abs(rr - age * 0.09f))
                val spark = smooth(0.75f, 1f, cos(kotlin.math.atan2(dy, dx) * 14f + slot))
                val hh = hash1(slot + 7f)
                val a = ring * spark * (1f - age / 4f) * 1.6f * p
                add(0.5f + 0.5f * cos(6.2831f * hh), 0.5f + 0.5f * cos(6.2831f * (hh + 0.33f)), 0.5f + 0.5f * cos(6.2831f * (hh + 0.67f)), a)
            }
            kind < 1.5f -> {
                // Northern lights.
                val wave = sin(qx * 3f + time * 0.6f) * 0.08f + sin(qx * 7.3f - time * 0.9f) * 0.04f
                val band = smooth(0f, 0.2f, v - 0.15f - wave) * (1f - smooth(0.55f, 0.95f, v - wave))
                val rays = 0.6f + 0.4f * sin(qx * 40f + time * 1.5f + sin(qx * 9f) * 2f)
                val k = smooth(0.5f, 0.85f, v - wave)
                add(0.2f + 0.45f * k, 1f - 0.65f * k, 0.55f + 0.45f * k, band * rays * 0.9f * p)
            }
            kind > 2.5f && kind < 3.5f -> for (k in 0 until 5) {
                // Shooting stars.
                val slot = floor(time * 1.5f + k * 0.37f)
                val age = fract(time * 1.5f + k * 0.37f)
                val fxs = (hash1(slot + k) - 0.3f) * 2f * aspect
                val fys = 0.55f + hash1(slot * 1.3f) * 0.45f
                val dxn = -0.912f; val dyn = -0.41f
                val hx = fxs + dxn * age * 1.2f; val hy = fys + dyn * age * 1.2f
                val dx = qx - hx; val dy = qy - hy
                val along = -(dx * dxn + dy * dyn)
                val across = abs(dx * -dyn + dy * dxn)
                if (along < 0f || along > 0.25f || across > 0.006f) continue
                val streak = (1f - smooth(0f, 0.006f, across)) * (1f - smooth(0f, 0.25f, along))
                add(0.85f, 0.95f, 1f, streak * (1f - age) * 1.5f * p)
            }
            kind > 4.5f && kind < 5.5f -> {
                // A dragon flying across.
                val headX = com.sadurni.attacca.world.SkyShow.screenX(dragonTurn, camera.skyTurn, dragonTan)
                for (k in 0 until 18) {
                    val bx = headX + k * 0.07f
                    val by = 0.55f + sin(time * 2.2f + k * 0.45f) * 0.1f
                    val rr = 0.045f * (1f - k / 26f)
                    val dx = qx - bx; val dy = qy - by
                    val d = sqrt(dx * dx + dy * dy)
                    if (d > rr) continue
                    if (k % 3 < 1) mix(1f, 0.8f, 0.25f, (1f - smooth(rr * 0.7f, rr, d)) * p) else mix(0.85f, 0.15f, 0.12f, (1f - smooth(rr * 0.7f, rr, d)) * p)
                }
                // Its head in front: red with a golden snout, horns, an eye and a waving whisker.
                val hy = 0.55f + sin(time * 2.2f - 0.25f) * 0.1f
                fun seg(ax: Float, ay: Float, bx: Float, by: Float): Float {
                    val px = qx - ax; val py = qy - ay; val ux = bx - ax; val uy = by - ay
                    val t = ((px * ux + py * uy) / (ux * ux + uy * uy)).coerceIn(0f, 1f)
                    return sqrt((px - ux * t) * (px - ux * t) + (py - uy * t) * (py - uy * t))
                }
                fun ell(cx: Float, cy: Float, rx: Float, ry: Float): Float = sqrt(((qx - cx) / rx) * ((qx - cx) / rx) + ((qy - cy) / ry) * ((qy - cy) / ry))
                if (abs(qx - (headX - 0.05f)) < 0.2f && abs(qy - hy) < 0.15f) {
                    val hax = headX - 0.01f; val hay = hy + 0.028f
                    for (s in 0 until 2) mix(1f, 0.82f, 0.3f, (1f - smooth(0.004f, 0.007f, seg(hax, hay, hax + 0.05f + s * 0.02f, hay + 0.05f - s * 0.012f))) * p)
                    val wax = headX - 0.1f; val way = hy - 0.018f
                    mix(1f, 0.8f, 0.25f, (1f - smooth(0.002f, 0.004f, seg(wax, way, wax - 0.05f, way - 0.03f + sin(time * 3f) * 0.02f))) * p)
                    mix(0.9f, 0.18f, 0.12f, (1f - smooth(0.85f, 1f, ell(headX - 0.035f, hy, 0.062f, 0.045f))) * p)
                    mix(1f, 0.8f, 0.25f, (1f - smooth(0.85f, 1f, ell(headX - 0.09f, hy - 0.01f, 0.042f, 0.026f))) * p)
                    mix(0.2f, 0.05f, 0.03f, (1f - smooth(0.003f, 0.006f, ell(headX - 0.115f, hy - 0.002f, 1f, 1f))) * p)
                    val ex = headX - 0.05f; val ey = hy + 0.017f
                    mix(1f, 0.97f, 0.8f, (1f - smooth(0.008f, 0.011f, ell(ex, ey, 1f, 1f))) * p)
                    mix(0.05f, 0.05f, 0.05f, (1f - smooth(0.004f, 0.006f, ell(ex - 0.003f, ey, 1f, 1f))) * p)
                }
            }
            kind > 5.5f && kind < 6.5f -> for (k in 0 until 6) {
                // Clouds of coloured powder.
                val age = fract(time * 0.6f + k / 6f)
                val slot = floor(time * 0.6f + k / 6f)
                val ax = (hash1(slot + k) - 0.5f) * 1.6f * aspect
                val ay = 0.3f + hash1(slot * 2f + k) * 0.4f
                val hh = hash1(slot + k * 3f)
                val d = sqrt((qx - ax) * (qx - ax) + (qy - ay) * (qy - ay))
                add(0.55f + 0.45f * cos(6.2831f * hh), 0.55f + 0.45f * cos(6.2831f * (hh + 0.33f)), 0.55f + 0.45f * cos(6.2831f * (hh + 0.67f)), (1f - smooth(0f, 0.08f + 0.2f * age, d)) * (1f - age) * 0.7f * p)
            }
            kind > 6.5f && kind < 7.5f -> {
                // A rainbow over the river.
                val rr = sqrt((qx - 0.1f * aspect) * (qx - 0.1f * aspect) + (qy + 0.2f) * (qy + 0.2f))
                val band = (rr - 0.55f) / 0.18f
                if (band > 0f && band < 1f) {
                    val h = band * 0.8f
                    mix(0.5f + 0.5f * cos(6.2831f * h), 0.5f + 0.5f * cos(6.2831f * (h + 0.33f)), 0.5f + 0.5f * cos(6.2831f * (h + 0.67f)), 0.5f * p)
                }
            }
            kind > 7.5f && kind < 8.5f -> {
                // A red sunset, an eagle wheeling.
                mix(1f, 0.45f, 0.2f, 0.35f * p * (1f - v))
                val ex = cos(time * 0.5f) * 0.5f * aspect
                val ey = 0.6f + sin(time * 0.5f) * 0.08f
                val dx = qx - ex; val dy = qy - ey
                val flap = sin(time * 3f) * 0.03f
                if (abs(dx) < 0.12f) mix(0.15f, 0.1f, 0.08f, (1f - smooth(0f, 0.012f, abs(dy - abs(dx) * 0.35f + flap))) * p)
            }
            kind > 8.5f && kind < 9.5f -> for (k in 0 until 3) {
                // Pink fireworks.
                val slot = floor(time * 1.2f) - k
                val age = time * 1.2f - slot
                val ax = (hash1(slot) - 0.5f) * 1.4f * aspect
                val ay = 0.35f + hash1(slot + 3f) * 0.4f
                val rr = sqrt((qx - ax) * (qx - ax) + (qy - ay) * (qy - ay))
                if (rr > 0.33f) continue
                add(1f, 0.6f, 0.75f, (1f - smooth(0f, 0.025f, abs(rr - age * 0.1f))) * (1f - age / 3f) * 1.4f * p)
            }
            kind > 9.5f && kind < 10.5f -> {
                // A whale passing above.
                val wcx = (1.3f - fract(time / 14f) * 2.6f) * aspect
                val wcy = 0.55f + sin(time * 0.6f) * 0.05f
                val ddx = (qx - wcx) / 0.32f; val ddy = (qy - wcy) / 0.09f
                mix(0.16f, 0.25f, 0.36f, (1f - smooth(0.85f, 1f, sqrt(ddx * ddx + ddy * ddy))) * 0.85f * p)
                val ring = fract(time * 0.7f)
                val d = sqrt((qx - wcx) * (qx - wcx) + (qy - wcy) * (qy - wcy))
                add(0.75f, 0.95f, 1f, (1f - smooth(0f, 0.01f, abs(d - ring * 0.6f))) * (1f - ring) * 0.6f * p)
            }
            kind > 10.5f && kind < 11.5f -> {
                // The volcano erupting.
                val tx = 0.3f * aspect; val ty = 0.05f
                val d0 = sqrt((qx - tx) * (qx - tx) + (qy - ty) * (qy - ty))
                add(1f, 0.35f, 0.1f, exp(-d0 * 3f) * 0.8f * p)
                for (k in 0 until 12) {
                    val age = fract(time * 0.6f + hash1(k.toFloat()))
                    val ax = tx + (hash1(k * 7f) - 0.5f) * 0.9f * age
                    val ay = ty + 0.9f * age - 0.8f * age * age
                    val d = sqrt((qx - ax) * (qx - ax) + (qy - ay) * (qy - ay))
                    if (d < 0.018f) add(1f, 0.6f, 0.2f, (1f - smooth(0f, 0.018f, d)) * (1f - age * 0.5f) * p)
                }
            }
        }
        return floatArrayOf(r, g, b)
    }

    // ---- The boards' pictures ----

    /** A picture on the screen: its two triangles' corners (x, y, depth, u/w, v/w, 1/w each) and how it is lit and hazed. */
    private class Board(val tri: FloatArray, val pic: Picture, val light: Float, val fog: Float)

    private fun board(corners: FloatArray, pic: Picture): Board? {
        val m = vp
        val pts = FloatArray(4 * 6)
        val uv = floatArrayOf(0f, 1f, 1f, 1f, 1f, 0f, 0f, 0f)
        for (k in 0 until 4) {
            val x = corners[k * 3]; val y = corners[k * 3 + 1]; val z = corners[k * 3 + 2]
            val w = m[3] * x + m[7] * y + m[11] * z + m[15]
            if (w < NEAR) return null
            pts[k * 6] = ((m[0] * x + m[4] * y + m[8] * z + m[12]) / w * 0.5f + 0.5f) * width
            pts[k * 6 + 1] = (0.5f - (m[1] * x + m[5] * y + m[9] * z + m[13]) / w * 0.5f) * height
            pts[k * 6 + 2] = (m[2] * x + m[6] * y + m[10] * z + m[14]) / w
            pts[k * 6 + 3] = uv[k * 2] / w
            pts[k * 6 + 4] = uv[k * 2 + 1] / w
            pts[k * 6 + 5] = 1f / w
        }
        val tri = FloatArray(36)
        for ((t, k) in intArrayOf(0, 1, 2, 0, 2, 3).withIndex()) System.arraycopy(pts, k * 6, tri, t * 6, 6)
        val cx = (corners[0] + corners[6]) / 2; val cy = (corners[1] + corners[7]) / 2; val cz = (corners[2] + corners[8]) / 2
        val dist = sqrt((cx - eye[0]) * (cx - eye[0]) + (cy - eye[1]) * (cy - eye[1]) + (cz - eye[2]) * (cz - eye[2]))
        val f = smooth(fogNear, fogFar, dist).let { it * it * (3 - 2 * it) }
        return Board(tri, pic, (brightness * 1.1f).coerceIn(0.35f, 1.2f), f)
    }

    private fun fillPicture(bd: Board, y0: Int, y1: Int) {
        val d = bd.tri
        val w = width
        val pic = bd.pic
        for (t in 0 until 2) {
            val o = t * 18
            val ax = d[o]; val ay = d[o + 1]; val bx = d[o + 6]; val by = d[o + 7]; val cx = d[o + 12]; val cy = d[o + 13]
            val minY = max(ceil(min(ay, min(by, cy)) - 0.5f).toInt(), y0)
            val maxY = min(floor(max(ay, max(by, cy)) - 0.5f).toInt(), y1 - 1)
            if (minY > maxY) continue
            val minX = max(ceil(min(ax, min(bx, cx)) - 0.5f).toInt(), 0)
            val maxX = min(floor(max(ax, max(bx, cx)) - 0.5f).toInt(), w - 1)
            if (minX > maxX) continue
            val area = (bx - ax) * (cy - ay) - (cx - ax) * (by - ay)
            if (abs(area) < 1e-6f) continue
            val inv = 1f / area
            for (y in minY..maxY) {
                val py = y + 0.5f
                val row = y * w
                for (x in minX..maxX) {
                    val px = x + 0.5f
                    val l0 = ((bx - px) * (cy - py) - (cx - px) * (by - py)) * inv
                    if (l0 < 0f) continue
                    val l1 = ((cx - px) * (ay - py) - (ax - px) * (cy - py)) * inv
                    if (l1 < 0f) continue
                    val l2 = 1f - l0 - l1
                    if (l2 < 0f) continue
                    val z = l0 * d[o + 2] + l1 * d[o + 8] + l2 * d[o + 14]
                    val i = row + x
                    if (z >= depth[i] || z < -1f) continue
                    val q = l0 * d[o + 5] + l1 * d[o + 11] + l2 * d[o + 17]
                    val u = (l0 * d[o + 3] + l1 * d[o + 9] + l2 * d[o + 15]) / q
                    val v = (l0 * d[o + 4] + l1 * d[o + 10] + l2 * d[o + 16]) / q
                    val c = pic.pixels[(v * (pic.height - 1)).toInt().coerceIn(0, pic.height - 1) * pic.width + (u * (pic.width - 1)).toInt().coerceIn(0, pic.width - 1)]
                    val k = bd.light
                    val r = ((c shr 16) and 0xFF) / 255f * k
                    val g = ((c shr 8) and 0xFF) / 255f * k
                    val b = (c and 0xFF) / 255f * k
                    depth[i] = z
                    pixels[i] = pack(r + (fog[0] - r) * bd.fog, g + (fog[1] - g) * bd.fog, b + (fog[2] - b) * bd.fog)
                }
            }
        }
    }

    // ---- Vertices to triangles on the picture ----

    /** Vertex [j] of [v] (7 floats each: clip x, y, z, w, then r, g, b after light, haze). */
    private fun vertex(x: Float, y: Float, z: Float, r0: Float, g0: Float, b0: Float, glow: Boolean, v: FloatArray, j: Int) {
        val m = vp
        val o = j * 7
        v[o] = m[0] * x + m[4] * y + m[8] * z + m[12]
        v[o + 1] = m[1] * x + m[5] * y + m[9] * z + m[13]
        v[o + 2] = m[2] * x + m[6] * y + m[10] * z + m[14]
        v[o + 3] = m[3] * x + m[7] * y + m[11] * z + m[15]
        val dx = x - eye[0]; val dy = y - eye[1]; val dz = z - eye[2]
        val dist = sqrt(dx * dx + dy * dy + dz * dz)
        var f = smooth(fogNear, fogFar, dist)
        if (glow) {
            f *= 0.7f
            // Flames flicker (torches, lava); lights glow up a little on the beat.
            var k = 1f + 0.15f * pulse.coerceAtMost(1.5f)
            var r = r0 * k; var g = g0 * k; var b = b0 * k
            if (r0 > 0.95f && g0 > 0.5f && g0 < 0.78f && b0 < 0.4f) {
                // Brightness and colour waver, now and then a flare: orange to yellow and back.
                val ph = x * 3.1f + z * 1.7f
                val n = 0.5f + 0.25f * sin(timeS * 13f + ph) + 0.15f * sin(timeS * 23.7f + ph * 1.9f) + 0.1f * sin(timeS * 41f + ph * 0.7f)
                val flare = Math.pow(max(sin(timeS * 2.3f + ph * 0.4f), 0f).toDouble(), 12.0).toFloat() * 0.35f
                val m = (n * 0.6f + flare).coerceIn(0f, 1f)
                val bright = 0.75f + 0.45f * n + flare
                r = (r * 0.7f + (1f - r * 0.7f) * m) * bright; g = (g * 0.7f + (0.86f - g * 0.7f) * m) * bright; b = (b * 0.7f + (0.45f - b * 0.7f) * m) * bright
            }
            v[o + 4] = r + (fog[0] - r) * f; v[o + 5] = g + (fog[1] - g) * f; v[o + 6] = b + (fog[2] - b) * f
            return
        }
        f = f * f * (3 - 2 * f)
        f = max(f, (dist / 600f).coerceIn(0f, 0.06f))
        val r = r0 * brightness; val g = g0 * brightness; val b = b0 * brightness
        v[o + 4] = r + (fog[0] - r) * f; v[o + 5] = g + (fog[1] - g) * f; v[o + 6] = b + (fog[2] - b) * f
    }

    /** One triangle (three clip vertices in [v]): cut at the near plane, on to the picture. */
    private fun emit(v: FloatArray, tri: FloatArray, out: Tris, alpha: Float) {
        val w0 = v[3]; val w1 = v[10]; val w2 = v[17]
        if (w0 < NEAR && w1 < NEAR && w2 < NEAR) return
        // All outside one side of the view: nothing to draw.
        if (v[0] > w0 && v[7] > w1 && v[14] > w2) return
        if (v[0] < -w0 && v[7] < -w1 && v[14] < -w2) return
        if (v[1] > w0 && v[8] > w1 && v[15] > w2) return
        if (v[1] < -w0 && v[8] < -w1 && v[15] < -w2) return
        if (w0 >= NEAR && w1 >= NEAR && w2 >= NEAR) {
            for (j in 0..2) screen(v, j * 7, tri, j)
            out.add(tri, alpha)
            return
        }
        // Cut against w = NEAR: a polygon of three or four corners, then a fan.
        val poly = FloatArray(4 * 7)
        var count = 0
        for (j in 0..2) {
            val a = j * 7
            val b = ((j + 1) % 3) * 7
            val aIn = v[a + 3] >= NEAR
            val bIn = v[b + 3] >= NEAR
            if (aIn) {
                System.arraycopy(v, a, poly, count * 7, 7)
                count++
            }
            if (aIn != bIn) {
                val t = (NEAR - v[a + 3]) / (v[b + 3] - v[a + 3])
                for (c in 0 until 7) poly[count * 7 + c] = v[a + c] + (v[b + c] - v[a + c]) * t
                count++
            }
        }
        for (j in 1 until count - 1) {
            screen(poly, 0, tri, 0)
            screen(poly, j * 7, tri, 1)
            screen(poly, (j + 1) * 7, tri, 2)
            out.add(tri, alpha)
        }
    }

    private fun screen(src: FloatArray, o: Int, tri: FloatArray, j: Int) {
        val w = src[o + 3]
        val t = j * 6
        tri[t] = (src[o] / w * 0.5f + 0.5f) * width
        tri[t + 1] = (0.5f - src[o + 1] / w * 0.5f) * height
        tri[t + 2] = src[o + 2] / w
        tri[t + 3] = src[o + 4]
        tri[t + 4] = src[o + 5]
        tri[t + 5] = src[o + 6]
    }

    /** Fills the triangles of [t] in rows [y0] … [y1) with depth (see-through ones blended, not hiding what is behind). */
    private fun fill(t: Tris, y0: Int, y1: Int) {
        val d = t.data
        val w = width
        for (k in 0 until t.count) {
            val o = k * 19
            val ax = d[o]; val ay = d[o + 1]
            val bx = d[o + 6]; val by = d[o + 7]
            val cx = d[o + 12]; val cy = d[o + 13]
            val minY = max(ceil(min(ay, min(by, cy)) - 0.5f).toInt(), y0)
            val maxY = min(floor(max(ay, max(by, cy)) - 0.5f).toInt(), y1 - 1)
            if (minY > maxY) continue
            val minX = max(ceil(min(ax, min(bx, cx)) - 0.5f).toInt(), 0)
            val maxX = min(floor(max(ax, max(bx, cx)) - 0.5f).toInt(), w - 1)
            if (minX > maxX) continue
            val area = (bx - ax) * (cy - ay) - (cx - ax) * (by - ay)
            if (abs(area) < 1e-6f) continue
            val inv = 1f / area
            val alpha = d[o + 18]
            val az = d[o + 2]; val bz = d[o + 8]; val cz = d[o + 14]
            for (y in minY..maxY) {
                val py = y + 0.5f
                val row = y * w
                for (x in minX..maxX) {
                    val px = x + 0.5f
                    val l0 = ((bx - px) * (cy - py) - (cx - px) * (by - py)) * inv
                    if (l0 < 0f) continue
                    val l1 = ((cx - px) * (ay - py) - (ax - px) * (cy - py)) * inv
                    if (l1 < 0f) continue
                    val l2 = 1f - l0 - l1
                    if (l2 < 0f) continue
                    val z = l0 * az + l1 * bz + l2 * cz
                    val i = row + x
                    if (z >= depth[i] || z < -1f) continue
                    val r = l0 * d[o + 3] + l1 * d[o + 9] + l2 * d[o + 15]
                    val g = l0 * d[o + 4] + l1 * d[o + 10] + l2 * d[o + 16]
                    val b = l0 * d[o + 5] + l1 * d[o + 11] + l2 * d[o + 17]
                    if (alpha >= 1f) {
                        depth[i] = z
                        pixels[i] = pack(r, g, b)
                    } else {
                        val p = pixels[i]
                        val pr = ((p shr 16) and 0xFF) / 255f
                        val pg = ((p shr 8) and 0xFF) / 255f
                        val pb = (p and 0xFF) / 255f
                        pixels[i] = pack(pr + (r - pr) * alpha, pg + (g - pg) * alpha, pb + (b - pb) * alpha)
                    }
                }
            }
        }
    }

    // ---- Helpers ----

    private fun clip(x: Float, y: Float, z: Float): FloatArray? {
        val m = vp
        val w = m[3] * x + m[7] * y + m[11] * z + m[15]
        if (w <= 0f) return null
        return floatArrayOf((m[0] * x + m[4] * y + m[8] * z + m[12]) / w, (m[1] * x + m[5] * y + m[9] * z + m[13]) / w)
    }

    private fun clipY(x: Float, y: Float, z: Float): Float? = clip(x, y, z)?.get(1)

    /** The star the Kings follow, fixed in the sky high over the way ahead, its tail trailing down (as on the phone). */
    private fun kingsStar(nx: Float, ny: Float, aspect: Float, fwd: FloatArray, right: FloatArray, up: FloatArray, tanY: Float, time: Float, r0: Float, g0: Float, b0: Float): FloatArray {
        val dx = fwd[0] + (right[0] * nx * aspect + up[0] * ny) * tanY
        val dy = fwd[1] + (right[1] * nx * aspect + up[1] * ny) * tanY
        val dz = fwd[2] + (right[2] * nx * aspect + up[2] * ny) * tanY
        val l = sqrt(dx * dx + dy * dy + dz * dz)
        val sl = sqrt(0.3f * 0.3f + 0.2f * 0.2f + 1f)
        val sx = 0.3f / sl; val sy = 0.2f / sl; val sz = 1f / sl
        val ex = dx / l; val ey = dy / l; val ez = dz / l
        if (ex * sx + ey * sy + ez * sz < 0.9f) return floatArrayOf(r0, g0, b0)
        // Its own axes: R across, U up.
        val rl = sqrt(sz * sz + sx * sx)
        val rx = -sz / rl; val rz = sx / rl
        val ux = 0f * sz - rz * sy; val uy = rz * sx - rx * sz; val uz = rx * sy - 0f * sx
        val px = ex * rx + ez * rz
        val py = ex * ux + ey * uy + ez * uz
        val len = sqrt(px * px + py * py)
        val tw = 0.85f + 0.15f * sin(time * 3f)
        var star = exp(-len * 90f) * 0.7f + (1f - smooth(0.004f, 0.007f, len))
        star += (1f - smooth(0f, 0.0025f, abs(px))) * (1f - smooth(0f, 0.05f, abs(py))) * tw
        star += (1f - smooth(0f, 0.0025f, abs(py))) * (1f - smooth(0f, 0.05f, abs(px))) * tw
        val qx = (px + py) * 0.7071f; val qy = (px - py) * 0.7071f
        star += (1f - smooth(0f, 0.002f, abs(qx))) * (1f - smooth(0f, 0.022f, abs(qy))) * 0.6f
        star += (1f - smooth(0f, 0.002f, abs(qy))) * (1f - smooth(0f, 0.022f, abs(qx))) * 0.6f
        val tdx = 0.8619f; val tdy = -0.507f
        val along = px * tdx + py * tdy
        val across = abs(px * tdy - py * tdx)
        val tail = if (along >= 0f) (1f - smooth(0f, 0.28f, along)) * (1f - smooth(0f, 0.003f + along * 0.05f, across)) * 0.5f * (1f - along / 0.28f) else 0f
        val a = star + tail
        return floatArrayOf(r0 + 1f * a, g0 + 0.95f * a, b0 + 0.75f * a)
    }

    /** The sun as a record turning in the sky (as on the phone): grooves catching the light, the cover on its label, notes falling. */
    private fun record(dx: Float, dy: Float, d: Float, time: Float, sunC: FloatArray, r0: Float, g0: Float, b0: Float): FloatArray {
        var r = r0; var g = g0; var b = b0
        val p = recordShown
        val big = 0.16f
        val spin = time * 3.4f
        if (d < big + 0.008f) {
            val a = (1f - smooth(big, big + 0.008f, d)) * p
            var cr: Float; var cg: Float; var cb: Float
            if (d < big * 0.36f) {
                val pic = input.cover
                if (pic != null) {
                    val la = kotlin.math.atan2(dy, dx) - spin
                    val u = (cos(la) * d / (big * 0.36f) * 0.5f + 0.5f).coerceIn(0f, 0.999f)
                    val v = (sin(la) * d / (big * 0.36f) * 0.5f + 0.5f).coerceIn(0f, 0.999f)
                    val px = pic.pixels[(((1f - v) * pic.height).toInt().coerceIn(0, pic.height - 1)) * pic.width + (u * pic.width).toInt()]
                    cr = ((px shr 16) and 0xFF) / 255f; cg = ((px shr 8) and 0xFF) / 255f; cb = (px and 0xFF) / 255f
                } else {
                    cr = sunC[0] * 0.9f + 0.1f; cg = sunC[1] * 0.9f + 0.1f; cb = sunC[2] * 0.9f + 0.1f
                }
            } else {
                val groove = 0.05f + 0.06f * (0.5f + 0.5f * sin(d * 520f))
                val sheen = 0.22f * Math.pow(max(0f, cos((kotlin.math.atan2(dy, dx) + spin) * 2f)).toDouble(), 18.0).toFloat() * smooth(big * 0.4f, big * 0.9f, d)
                cr = groove + sheen; cg = groove + sheen; cb = groove + 0.01f + sheen
            }
            val hole = 1f - smooth(big * 0.03f, big * 0.045f, d)
            cr += (0.02f - cr) * hole; cg += (0.02f - cg) * hole; cb += (0.02f - cb) * hole
            r += (cr - r) * a; g += (cg - g) * a; b += (cb - b) * a
        }
        for (k in 0 until 6) {
            val age = fract(time * 0.35f + k / 6f)
            val ax = (hash1(floor(time * 0.35f + k / 6f) + k) - 0.5f) * big * 3f
            val ay = -big - age * 0.5f
            val nx = dx - ax; val ny = dy - ay
            val head = 1f - smooth(0.012f, 0.016f, sqrt((nx / 1.25f) * (nx / 1.25f) + ny * ny))
            val stem = if (ny >= 0f && ny <= 0.05f) 1f - smooth(0.002f, 0.004f, abs(nx - 0.014f)) else 0f
            val flag = if (nx >= 0.014f && nx <= 0.032f) 1f - smooth(0.003f, 0.005f, abs(ny - 0.05f + (nx - 0.014f) * 0.6f)) else 0f
            val a = max(head, max(stem, flag)) * (1f - age) * p
            r += (sunC[0] * 1.1f + 0.15f - r) * a; g += (sunC[1] * 1.1f + 0.15f - g) * a; b += (sunC[2] * 1.1f + 0.15f - b) * a
        }
        return floatArrayOf(r, g, b)
    }

    private fun project(x: Float, y: Float, z: Float): Pair<Float, Float>? =
        clip(x, y, z)?.let { (it[0] * 0.5f + 0.5f) * width to (0.5f - it[1] * 0.5f) * height }

    private fun pack(r: Float, g: Float, b: Float): Int {
        val ri = (r * 255f).toInt().coerceIn(0, 255)
        val gi = (g * 255f).toInt().coerceIn(0, 255)
        val bi = (b * 255f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
    }

    private fun rgb(c: Int) = floatArrayOf(((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f)

    private fun smooth(a: Float, b: Float, x: Float): Float {
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }

    private fun normalize(v: FloatArray) {
        val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        for (i in 0..2) v[i] /= l
    }

    companion object {
        const val FAR = 150f
        const val NEAR = 0.3f
        private val WARM = floatArrayOf(1f, 0.86f, 0.62f)
        private val COOL = floatArrayOf(0.62f, 0.72f, 0.95f)
        private val TORCH = floatArrayOf(1f, 0.68f, 0.36f)
        private val BEAM = floatArrayOf(1f, 0.95f, 0.82f)

        /** Raindrops or snowflakes at most (fewer than on the phone: each one drawn by the processor). */
        private const val DROPS = 500
    }
}
